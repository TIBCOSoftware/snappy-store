/*

   Derby - Class com.pivotal.gemfirexd.internal.client.net.NetAgent

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.pivotal.gemfirexd.internal.client.net;

import java.net.SocketException;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.client.am.Agent;
import com.pivotal.gemfirexd.internal.client.am.Connection;
import com.pivotal.gemfirexd.internal.client.am.DisconnectException;
import com.pivotal.gemfirexd.internal.client.am.LogWriter;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.client.am.ClientMessageId;
import com.pivotal.gemfirexd.internal.client.am.Utils;

import com.pivotal.gemfirexd.internal.shared.common.i18n.MessageUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.MessageId;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public class NetAgent extends Agent {
    //---------------------navigational members-----------------------------------

    // All these request objects point to the same physical request object.
    public ConnectionRequestInterface connectionRequest_;
    public NetConnectionRequest packageRequest_;
    public StatementRequestInterface statementRequest_;
    public ResultSetRequestInterface resultSetRequest_;

    // All these reply objects point to the same physical reply object.
    public ConnectionReply connectionReply_;
    public ConnectionReply packageReply_;
    public StatementReply statementReply_;
    public ResultSetReply resultSetReply_;

    //---------------------navigational cheat-links-------------------------------
    // Cheat-links are for convenience only, and are not part of the conceptual model.
    // Warning:
    //   Cheat-links should only be defined for invariant state data.
    //   That is, the state data is set by the constructor and never changes.

    // Alias for (NetConnection) super.connection
    NetConnection netConnection_;

    // Alias for (Request) super.*Request, all in one
    // In the case of the NET implementation, these all point to the same physical request object.
    protected Request request_;
    public NetConnectionRequest netConnectionRequest_;
    public NetPackageRequest netPackageRequest_;
    public NetStatementRequest netStatementRequest_;
    public NetResultSetRequest netResultSetRequest_;

    // Alias for (Reply) super.*Reply, all in one.
    // In the case of the NET implementation, these all point to the same physical reply object.
    protected Reply reply_;
    public NetConnectionReply netConnectionReply_;
    public NetPackageReply netPackageReply_;
    public NetStatementReply netStatementReply_;
    public NetResultSetReply netResultSetReply_;

    //-----------------------------state------------------------------------------

    java.net.Socket socket_;
    java.io.InputStream rawSocketInputStream_;
    java.io.OutputStream rawSocketOutputStream_;

    String server_;
    int port_;
    int clientSSLMode_;

    public CcsidManager sourceCcsidManager_;
    public CcsidManager targetCcsidManager_;
    public Typdef typdef_;
    public Typdef targetTypdef_;
    public Typdef originalTargetTypdef_; // added to support typdef overrides

    protected int svrcod_;

    public int orignalTargetSqlam_ = NetConfiguration.MGRLVL_7;
    public int targetSqlam_ = orignalTargetSqlam_;

    public SqlException exceptionOpeningSocket_ = null;

    //---------------------constructors/finalizer---------------------------------
    public NetAgent(NetConnection netConnection,
                    com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter) throws SqlException {
        super(netConnection, logWriter);
        this.netConnection_ = netConnection;
    }

    NetAgent(NetConnection netConnection,
             com.pivotal.gemfirexd.internal.client.am.LogWriter netLogWriter,
             int loginTimeout,
             String server,
             int port,
             int clientSSLMode) throws SqlException {
        super(netConnection, netLogWriter);

        server_ = server;
        port_ = port;
        netConnection_ = netConnection;
        clientSSLMode_ = clientSSLMode;

// GemStone changes BEGIN
        loginTimeout = getLoginTimeout(loginTimeout);
// GemStone changes END
        if (server_ == null) {
            throw new DisconnectException(this, 
                new ClientMessageId(SQLState.CONNECT_REQUIRED_PROPERTY_NOT_SET),
                "serverName");
        }

        try {
            socket_ = (java.net.Socket) java.security.AccessController.doPrivileged(new OpenSocketAction(server, port, clientSSLMode_));
        } catch (java.security.PrivilegedActionException e) {
            throw new DisconnectException(this,
                new ClientMessageId(SQLState.CONNECT_UNABLE_TO_CONNECT_TO_SERVER),
                new Object[] { e.getException().getClass().getName(), server, 
                    Integer.toString(port), e.getException().getMessage() },
                e.getException());
        }

        // Set TCP/IP Socket Properties
        try {
            if (exceptionOpeningSocket_ == null) {
                socket_.setTcpNoDelay(true); // disables nagles algorithm
// GemStone changes BEGIN
                socket_.setSoLinger(false, 0);
                // now set below after getting the InputStream
                /*
                socket_.setKeepAlive(true); // PROTOCOL Manual: TCP/IP connection allocation rule #2
                */
// GemStone changes END
                socket_.setSoTimeout(loginTimeout * 1000);
            }
        } catch (java.net.SocketException e) {
            try {
                socket_.close();
            } catch (java.io.IOException doNothing) {
            }
            exceptionOpeningSocket_ = new DisconnectException(this,
                new ClientMessageId(SQLState.CONNECT_SOCKET_EXCEPTION),
                e.getMessage(), e);
        }

        try {
            if (exceptionOpeningSocket_ == null) {
                rawSocketOutputStream_ = socket_.getOutputStream();
                rawSocketInputStream_ = socket_.getInputStream();
// GemStone changes BEGIN
                ClientSharedUtils.setKeepAliveOptions(socket_,
                    rawSocketInputStream_, netConnection.keepAliveIdle_,
                    netConnection.keepAliveIntvl_, netConnection.keepAliveCnt_);
// GemStone changes END
            }
        } catch (java.io.IOException e) {
            try {
                socket_.close();
            } catch (java.io.IOException doNothing) {
            }
            exceptionOpeningSocket_ = new DisconnectException(this, 
                new ClientMessageId(SQLState.CONNECT_UNABLE_TO_OPEN_SOCKET_STREAM),
                e.getMessage(), e);
        }
// GemStone changes BEGIN
        initialize();
    }

    private void initialize() {
// GemStone changes END

        sourceCcsidManager_ = new EbcdicCcsidManager(); // delete these
        targetCcsidManager_ = sourceCcsidManager_; // delete these

        if (netConnection_.isXAConnection()) {
            NetXAConnectionReply netXAConnectionReply_ = new NetXAConnectionReply(this, netConnection_.commBufferSize_);
            netResultSetReply_ = (NetResultSetReply) netXAConnectionReply_;
            netStatementReply_ = (NetStatementReply) netResultSetReply_;
            netPackageReply_ = (NetPackageReply) netStatementReply_;
            netConnectionReply_ = (NetConnectionReply) netPackageReply_;
            reply_ = (Reply) netConnectionReply_;

            resultSetReply_ = new ResultSetReply(this,
                    netResultSetReply_,
                    netStatementReply_,
                    netConnectionReply_);
            statementReply_ = (StatementReply) resultSetReply_;
            packageReply_ = (ConnectionReply) statementReply_;
            connectionReply_ = (ConnectionReply) packageReply_;
            NetXAConnectionRequest netXAConnectionRequest_ = new NetXAConnectionRequest(this, sourceCcsidManager_, netConnection_.commBufferSize_);
            netResultSetRequest_ = (NetResultSetRequest) netXAConnectionRequest_;
            netStatementRequest_ = (NetStatementRequest) netResultSetRequest_;
            netPackageRequest_ = (NetPackageRequest) netStatementRequest_;
            netConnectionRequest_ = (NetConnectionRequest) netPackageRequest_;
            request_ = (Request) netConnectionRequest_;

            resultSetRequest_ = (ResultSetRequestInterface) netResultSetRequest_;
            statementRequest_ = (StatementRequestInterface) netStatementRequest_;
            packageRequest_ = (NetConnectionRequest) netPackageRequest_;
            connectionRequest_ = (ConnectionRequestInterface) netConnectionRequest_;
        } else {
            netResultSetReply_ = new NetResultSetReply(this, netConnection_.commBufferSize_);
            netStatementReply_ = (NetStatementReply) netResultSetReply_;
            netPackageReply_ = (NetPackageReply) netStatementReply_;
            netConnectionReply_ = (NetConnectionReply) netPackageReply_;
            reply_ = (Reply) netConnectionReply_;

            resultSetReply_ = new ResultSetReply(this,
                    netResultSetReply_,
                    netStatementReply_,
                    netConnectionReply_);
            statementReply_ = (StatementReply) resultSetReply_;
            packageReply_ = (ConnectionReply) statementReply_;
            connectionReply_ = (ConnectionReply) packageReply_;
            netResultSetRequest_ = new NetResultSetRequest(this, sourceCcsidManager_, netConnection_.commBufferSize_);
            netStatementRequest_ = (NetStatementRequest) netResultSetRequest_;
            netPackageRequest_ = (NetPackageRequest) netStatementRequest_;
            netConnectionRequest_ = (NetConnectionRequest) netPackageRequest_;
            request_ = (Request) netConnectionRequest_;

            resultSetRequest_ = (ResultSetRequestInterface) netResultSetRequest_;
            statementRequest_ = (StatementRequestInterface) netStatementRequest_;
            packageRequest_ = (NetConnectionRequest) netPackageRequest_;
            connectionRequest_ = (ConnectionRequestInterface) netConnectionRequest_;
        }
    }

    protected void resetAgent_(com.pivotal.gemfirexd.internal.client.am.LogWriter netLogWriter,
                               //CcsidManager sourceCcsidManager,
                               //CcsidManager targetCcsidManager,
                               int loginTimeout,
                               String server,
                               int port) throws SqlException {

        // most properties will remain unchanged on connect reset.
        targetTypdef_ = originalTargetTypdef_;
        svrcod_ = 0;
// GemStone changes BEGIN
        loginTimeout = getLoginTimeout(loginTimeout);
// GemStone changes END

        // Set TCP/IP Socket Properties
        try {
            socket_.setSoTimeout(loginTimeout * 1000);
            socket_.setSoLinger(false, 0);
        } catch (java.net.SocketException e) {
            try {
                socket_.close();
            } catch (java.io.IOException doNothing) {
            }
            throw new SqlException(logWriter_, 
                new ClientMessageId(SQLState.SOCKET_EXCEPTION),
                e.getMessage(), e);
        }
    }

// GemStone changes BEGIN

    final int getLoginTimeout(int loginTimeout) {
      // don't use infinite timeout since we can failover
      if (loginTimeout == 0) {
        loginTimeout = Connection.DEFAULT_LOGIN_TIMEOUT;
      }
      // but do use infinite timeout if specially asked for
      else if (loginTimeout == NetConnection.INFINITE_LOGIN_TIMEOUT) {
        loginTimeout = 0;
      }
      return loginTimeout;
    }

    public boolean reconnectAgent(LogWriter logWriter, int loginTimeout,
        String server, int port) throws SqlException {
      resetAgent(logWriter);

      // Set TCP/IP Socket Properties
      try {
        // if the server/port has changed then recreate the socket
        loginTimeout = getLoginTimeout(loginTimeout);
        if (this.port_ != port || !this.server_.equals(server)
            || this.socket_ == null || this.socket_.isClosed()) {
          // close the old socket ignoring exceptions
          try {
            close_();
          } catch (SqlException ex) {
            // deliberately ignored
          }
          try {
            socket_ = (java.net.Socket)java.security.AccessController
                .doPrivileged(new OpenSocketAction(server, port,
                    this.clientSSLMode_));
          } catch (java.security.PrivilegedActionException e) {
            throw new DisconnectException(this, new ClientMessageId(
                SQLState.CONNECT_UNABLE_TO_CONNECT_TO_SERVER), new Object[] {
                e.getException().getClass().getName(), server,
                Integer.toString(port), e.getException().getMessage() },
                e.getException());
          }

          // Set TCP/IP Socket Properties
          socket_.setTcpNoDelay(true); // disables nagles algorithm
          socket_.setSoLinger(false, 0);
          try {
            rawSocketInputStream_ = socket_.getInputStream();
            rawSocketOutputStream_ = socket_.getOutputStream();
            ClientSharedUtils.setKeepAliveOptions(socket_,
                rawSocketInputStream_, netConnection_.keepAliveIdle_,
                netConnection_.keepAliveIntvl_, netConnection_.keepAliveCnt_);
          } catch (java.io.IOException e) {
            try {
              socket_.close();
            } catch (java.io.IOException doNothing) {
            }
            throw new DisconnectException(this, new ClientMessageId(SQLState
                .CONNECT_UNABLE_TO_OPEN_SOCKET_STREAM), e.getMessage(), e);
          }
          this.server_ = server;
          this.port_ = port;
          // reinitialize the request/reply objects
          initialize();
          return true;
        }
        socket_.setSoTimeout(loginTimeout * 1000);
      } catch (java.net.SocketException e) {
        try {
          socket_.close();
        } catch (java.io.IOException doNothing) {
        }
        throw new SqlException(logWriter_, new ClientMessageId(
            SQLState.SOCKET_EXCEPTION), e.getMessage(), e);
      }
      return false;
    }
// GemStone changes END

    void setSvrcod(int svrcod) {
        if (svrcod > svrcod_) {
            svrcod_ = svrcod;
        }
    }

    void clearSvrcod() {
        svrcod_ = CodePoint.SVRCOD_INFO;
    }

    int getSvrcod() {
        return svrcod_;
    }

    public void flush_() throws DisconnectException {
        checkOpen();
        sendRequest();
        reply_.initialize();
    }

    // Close socket and its streams.
    public void close_() throws SqlException {
        // can we just close the socket here, do we need to close streams individually
        SqlException accumulatedExceptions = null;
        if (rawSocketInputStream_ != null) {
            try {
                rawSocketInputStream_.close();
            } catch (java.io.IOException e) {
                // note when {6} = 0 it indicates the socket was closed.
                // this should be ok since we are going to go an close the socket
                // immediately following this call.
                // changing {4} to e.getMessage() may require pub changes
                accumulatedExceptions = new SqlException(logWriter_,
                    new ClientMessageId(SQLState.COMMUNICATION_ERROR),
                    e.getMessage(), e);
            } finally {
                rawSocketInputStream_ = null;
            }
        }

        if (rawSocketOutputStream_ != null) {
            try {
                rawSocketOutputStream_.close();
            } catch (java.io.IOException e) {
                // note when {6} = 0 it indicates the socket was closed.
                // this should be ok since we are going to go an close the socket
                // immediately following this call.
                // changing {4} to e.getMessage() may require pub changes
                SqlException latestException = new SqlException(logWriter_,
                    new ClientMessageId(SQLState.COMMUNICATION_ERROR),
                    e.getMessage(), e);
                accumulatedExceptions = Utils.accumulateSQLException(latestException, accumulatedExceptions);
            } finally {
                rawSocketOutputStream_ = null;
            }
        }

        if (socket_ != null) {
            try {
                socket_.close();
            } catch (java.io.IOException e) {
                // again {6} = 0, indicates the socket was closed.
                // maybe set {4} to e.getMessage().
                // do this for now and but may need to modify or
                // add this to the message pubs.
                SqlException latestException = new SqlException(logWriter_,
                    new ClientMessageId(SQLState.COMMUNICATION_ERROR),
                        e.getMessage(), e);
                accumulatedExceptions = Utils.accumulateSQLException(latestException, accumulatedExceptions);
            } finally {
                socket_ = null;
            }
        }

        if (accumulatedExceptions != null) {
            throw accumulatedExceptions;
        }
    }

    /**
     * Specifies the maximum blocking time that should be used when sending
     * and receiving messages. The timeout is implemented by using the the 
     * underlying socket implementation's timeout support. 
     * 
     * Note that the support for timeout on sockets is dependent on the OS 
     * implementation. For the same reason we ignore any exceptions thrown
     * by the call to the socket layer.
     * 
     * @param timeout The timeout value in seconds. A value of 0 corresponds to 
     * infinite timeout.
     */
    protected void setTimeout(int timeout) {
        try {
            // Sets a timeout on the socket
// GemStone changes BEGIN
            if (timeout == NetConnection.INFINITE_LOGIN_TIMEOUT) {
              timeout = 0;
            }
// GemStone changes END
            socket_.setSoTimeout(timeout * 1000); // convert to milliseconds
        } catch (SocketException se) {
            // Silently ignore any exceptions from the socket layer
            if (SanityManager.DEBUG) {
                System.out.println("NetAgent.setTimeout: ignoring exception: " + 
                                   se);
            }
        }
    }

    /**
     * Returns the current timeout value that is set on the socket.
     * 
     * Note that the support for timeout on sockets is dependent on the OS 
     * implementation. For the same reason we ignore any exceptions thrown
     * by the call to the socket layer.
     * 
     * @return The timeout value in seconds. A value of 0 corresponds to
     * that no timeout is specified on the socket.
     */
    protected int getTimeout() {
        int timeout = 0; // 0 is default timeout for sockets

        // Read the timeout currently set on the socket
        try {
            timeout = socket_.getSoTimeout();
        } catch (SocketException se) {
            // Silently ignore any exceptions from the socket layer
            if (SanityManager.DEBUG) {
                System.out.println("NetAgent.getTimeout: ignoring exception: " + 
                                   se);
            }
        }

        // Convert from milliseconds to seconds (note that this truncates
        // the results towards zero but that should not be a problem).
        timeout = timeout / 1000;
        return timeout;
    }

    protected void sendRequest() throws DisconnectException {
        try {
            request_.flush(rawSocketOutputStream_);
        } catch (java.io.IOException e) {
            throwCommunicationsFailure(e);
        }
    }

    public java.io.InputStream getInputStream() {
        return rawSocketInputStream_;
    }

    public java.io.OutputStream getOutputStream() {
        return rawSocketOutputStream_;
    }

    void setInputStream(java.io.InputStream inputStream) {
        rawSocketInputStream_ = inputStream;
    }

    void setOutputStream(java.io.OutputStream outputStream) {
        rawSocketOutputStream_ = outputStream;
    }

    public void throwCommunicationsFailure(Throwable cause) 
        throws com.pivotal.gemfirexd.internal.client.am.DisconnectException {
        //com.pivotal.gemfirexd.internal.client.am.DisconnectException
        //accumulateReadExceptionAndDisconnect
        // note when {6} = 0 it indicates the socket was closed.
        // need to still validate any token values against message publications.
        accumulateChainBreakingReadExceptionAndThrow(
            new com.pivotal.gemfirexd.internal.client.am.DisconnectException(this,
                new ClientMessageId(SQLState.COMMUNICATION_ERROR),
                cause.getMessage(), cause));
    }
        
    // ----------------------- call-down methods ---------------------------------

    public com.pivotal.gemfirexd.internal.client.am.LogWriter newLogWriter_(java.io.PrintWriter printWriter,
                                                              int traceLevel) {
        return new NetLogWriter(printWriter, traceLevel);
    }

    protected void markChainBreakingException_() {
        setSvrcod(CodePoint.SVRCOD_ERROR);
    }

    public void checkForChainBreakingException_() throws SqlException {
        int svrcod = getSvrcod();
        clearSvrcod();
        if (svrcod > CodePoint.SVRCOD_WARNING) // Not for SQL warning, if svrcod > WARNING, then its a chain breaker
        {
            super.checkForExceptions(); // throws the accumulated exceptions, we'll always have at least one.
        }
    }

    private void writeDeferredResetConnection() throws SqlException {
        if (!netConnection_.resetConnectionAtFirstSql_) {
            return;
        }
        try {
            netConnection_.writeDeferredReset();
        } catch (SqlException sqle) {
            DisconnectException de = new DisconnectException(this, 
                new ClientMessageId(SQLState.CONNECTION_FAILED_ON_DEFERRED_RESET));
            de.setNextException(sqle);
            throw de;
        }
    }

    public void beginWriteChainOutsideUOW() throws SqlException {
        request_.initialize();
        writeDeferredResetConnection();
        super.beginWriteChainOutsideUOW();
    }

    public void beginWriteChain(com.pivotal.gemfirexd.internal.client.am.Statement statement) throws SqlException {
        request_.initialize();
        writeDeferredResetConnection();
        super.beginWriteChain(statement);
    }

    protected void endWriteChain() {
        super.endWriteChain();
    }

    private void readDeferredResetConnection() throws SqlException {
        if (!netConnection_.resetConnectionAtFirstSql_) {
            return;
        }
        try {
            netConnection_.readDeferredReset();
            checkForExceptions();
        } catch (SqlException sqle) {
            DisconnectException de = new DisconnectException(this, 
                new ClientMessageId(SQLState.CONNECTION_FAILED_ON_DEFERRED_RESET));
            de.setNextException(sqle);
            throw de;
        }
    }

    protected void beginReadChain(com.pivotal.gemfirexd.internal.client.am.Statement statement) throws SqlException {
        readDeferredResetConnection();
        super.beginReadChain(statement);
    }

    protected void beginReadChainOutsideUOW() throws SqlException {
        readDeferredResetConnection();
        super.beginReadChainOutsideUOW();
    }

    public void endReadChain() throws SqlException {
        super.endReadChain();
    }


    public String convertToStringTcpIpAddress(int tcpIpAddress) {
        StringBuilder ipAddrBytes = new StringBuilder();
        ipAddrBytes.append((tcpIpAddress >> 24) & 0xff);
        ipAddrBytes.append(".");
        ipAddrBytes.append((tcpIpAddress >> 16) & 0xff);
        ipAddrBytes.append(".");
        ipAddrBytes.append((tcpIpAddress >> 8) & 0xff);
        ipAddrBytes.append(".");
        ipAddrBytes.append((tcpIpAddress) & 0xff);

        return ipAddrBytes.toString();
    }

    public int getPort() {
        return port_;
    }

// GemStone changes BEGIN
    public String getServer() {
      return server_;
    }

    public final String getServerLocation() {
      return NetConnection.getServerUrl(this.server_, this.port_);
    }

    protected void checkOpen() throws DisconnectException {
      if (socket_ == null) {
        throw new DisconnectException(this, new ClientMessageId(
            SQLState.SOCKET_EXCEPTION), "socket closed");
      }
    }

    public String toString() {
      return "NetAgent@" + Integer.toHexString(System.identityHashCode(this))
          + ':' + getServerLocation();
    }
// GemStone changes END
}


