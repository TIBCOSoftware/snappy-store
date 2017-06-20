/*

   Derby - Class com.pivotal.gemfirexd.internal.client.net.NetConnection

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

import com.pivotal.gemfirexd.internal.client.am.Agent;
import com.pivotal.gemfirexd.internal.client.am.CallableStatement;
import com.pivotal.gemfirexd.internal.client.am.DatabaseMetaData;
import com.pivotal.gemfirexd.internal.client.am.DisconnectException;
import com.pivotal.gemfirexd.internal.client.am.EncryptionManager;
import com.pivotal.gemfirexd.internal.client.am.PreparedStatement;
import com.pivotal.gemfirexd.internal.client.am.ProductLevel;
import com.pivotal.gemfirexd.internal.client.am.SignedBinary;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.client.am.ClientMessageId;
import com.pivotal.gemfirexd.internal.client.am.Statement;
import com.pivotal.gemfirexd.internal.client.am.Utils;
import com.pivotal.gemfirexd.internal.iapi.reference.DRDAConstants;
import com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource;
import com.pivotal.gemfirexd.internal.client.ClientPooledConnection;

// GemStone changes BEGIN
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.shared.common.BoundedLinkedQueue;
import com.pivotal.gemfirexd.internal.shared.common.QueueObjectCreator;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.i18n.MessageUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.MessageId;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;
import com.pivotal.gemfirexd.jdbc.ClientDRDADriver;
import io.snappydata.thrift.internal.ClientService;

// made abstract to enable compilation with both JDK 1.4 and 1.6
abstract
// GemStone changes END
public class NetConnection extends com.pivotal.gemfirexd.internal.client.am.Connection {
    
    // Use this to get internationalized strings...
    protected static MessageUtil msgutil = SqlException.getMessageUtil();

    private boolean snappyDRDAProtocol = false;
    protected NetAgent netAgent_;
    //contains a reference to the PooledConnection from which this created 
    //It then passes this reference to the PreparedStatement created from it
    //The PreparedStatement then uses this to pass the close and the error
    //occurred conditions back to the PooledConnection which can then throw the 
    //appropriate events.
    private final ClientPooledConnection pooledConnection_;
    private final boolean closeStatementsOnClose;

    // For XA Transaction
    protected int pendingEndXACallinfoOffset_ = -1;

    //-----------------------------state------------------------------------------

    // these variables store the manager levels for the connection.
    // they are initialized to the highest value which this driver supports
    // at the current time.  theses intial values should be increased when
    // new manager level support is added to this driver.  these initial values
    // are sent to the server in the excsat command.  the server will return a
    // set of values and these will be parsed out by parseExcsatrd and parseMgrlvlls.
    // during this parsing, these instance variable values will be reset to the negotiated
    // levels for the connection.  these values may be less than the
    // values origionally set here at constructor time.  it is these new values
    // (following the parse) which are the levels for the connection.  after
    // a successful excsat command, these values can be checked to see
    // what protocol is supported by this particular connection.
    // if support for a new manager class is added, the buildExcsat and parseMgrlvlls
    // methods will need to be changed to accomodate sending and receiving the new class.
    protected int targetAgent_ = NetConfiguration.MGRLVL_7;  //01292003jev monitoring
    protected int targetCmntcpip_ = NetConfiguration.MGRLVL_5;
    protected int targetRdb_ = NetConfiguration.MGRLVL_7;
    public int targetSecmgr_ = NetConfiguration.MGRLVL_7;
    protected int targetCmnappc_ = NetConfiguration.MGRLVL_NA;  //NA since currently not used by net
    protected int targetXamgr_ = NetConfiguration.MGRLVL_7;
    protected int targetSyncptmgr_ = NetConfiguration.MGRLVL_NA;
    protected int targetRsyncmgr_ = NetConfiguration.MGRLVL_NA;


    // this is the external name of the target server.
    // it is set by the parseExcsatrd method but not really used for much at this
    // time.  one possible use is for logging purposes and in the future it
    // may be placed in the trace.
    String targetExtnam_;
    String extnam_;

    // Server Class Name of the target server returned in excsatrd.
    // Again this is something which the driver is not currently using
    // to make any decions.  Right now it is just stored for future logging.
    // It does contain some useful information however and possibly
    // the database meta data object will make use of this
    // for example, the product id (prdid) would give this driver an idea of
    // what type of sevrer it is connected to.
    public String targetSrvclsnm_;

    // Server Name of the target server returned in excsatrd.
    // Again this is something which we don't currently use but
    // keep it in case we want to log it in some problem determination
    // trace/dump later.
    protected String targetSrvnam_;

    // Server Product Release Level of the target server returned in excsatrd.
    // specifies the procuct release level of a ddm server.
    // Again this is something which we don't currently use but
    // keep it in case we want to log it in some problem determination
    // trace/dump later.
    public String targetSrvrlslv_;

    // Keys used for encryption.
    transient byte[] publicKey_;
    transient byte[] targetPublicKey_;

    // Seeds used for strong password substitute generation (USRSSBPWD)
    transient byte[] sourceSeed_;   // Client seed
    transient byte[] targetSeed_;   // Server seed

    // Product-Specific Data (prddta) sent to the server in the accrdb command.
    // The prddta has a specified format.  It is saved in case it is needed again
    // since it takes a little effort to compute.  Saving this information is
    // useful for when the connect flows need to be resent (right now the connect
    // flow is resent when this driver disconnects and reconnects with
    // non unicode ccsids.  this is done when the server doesn't recoginze the
    // unicode ccsids).
    //

    byte[] prddta_;

    // Correlation Token of the source sent to the server in the accrdb.
    // It is saved like the prddta in case it is needed for a connect reflow.
    public byte[] crrtkn_;

    // The Secmec used by the target.
    // It contains the negotiated security mechanism for the connection.
    // Initially the value of this is 0.  It is set only when the server and
    // the target successfully negotiate a security mechanism.
    int targetSecmec_;

    // the security mechanism requested by the application
    protected int securityMechanism_;

    // stored the password for deferred reset only.
    private transient char[] deferredResetPassword_ = null;
    
    //If Network Server gets null connection from the embedded driver, 
    //it sends RDBAFLRM followed by SQLCARD with null SQLException.
    //Client will parse the SQLCARD and set connectionNull to true if the
    //SQLCARD is empty. If connectionNull=true, connect method in 
    //ClientDRDADriver will in turn return null connection.
    private boolean connectionNull = false;

    private void setDeferredResetPassword(String password) {
        deferredResetPassword_ = (password == null) ? null : flipBits(password.toCharArray());
    }

    private String getDeferredResetPassword() {
        if (deferredResetPassword_ == null) {
            return null;
        }
        String password = new String(flipBits(deferredResetPassword_));
        flipBits(deferredResetPassword_); // re-encrypt password
        return password;
    }

    protected byte[] cnntkn_ = null;

    // resource manager Id for XA Connections.
    private int rmId_ = 0;
    protected NetXAResource xares_ = null;
    protected java.util.Hashtable indoubtTransactions_ = null;
    protected int currXACallInfoOffset_ = 0;
    private short seqNo_ = 1;
    
    //flag for indicating if the driver is ODBC
    private boolean isODBCDriver = false;
    
    private static java.util.List<NetConnection> odbcConnectionList = 
        Collections.synchronizedList(new ArrayList<NetConnection>());;

    // Flag to indicate a read only transaction
    protected boolean readOnlyTransaction_ = true;

    //---------------------constructors/finalizer---------------------------------

    public NetConnection(NetLogWriter netLogWriter,
                         String databaseName,
                         java.util.Properties properties) throws SqlException {
        super(netLogWriter, 0, "", -1, databaseName, properties);
        this.pooledConnection_ = null;
        this.closeStatementsOnClose = true;
    }

    public NetConnection(NetLogWriter netLogWriter,
                         com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
                         String user,
                         String password) throws SqlException {
        super(netLogWriter, user, password, dataSource);
        this.pooledConnection_ = null;
        this.closeStatementsOnClose = true;
        setDeferredResetPassword(password);
    }

    // For jdbc 1 connections
    public NetConnection(NetLogWriter netLogWriter,
                         int driverManagerLoginTimeout,
                         String serverName,
                         int portNumber,
                         String databaseName,
                         java.util.Properties properties) throws SqlException {
        super(netLogWriter, driverManagerLoginTimeout, serverName, portNumber, databaseName, properties);
        this.pooledConnection_ = null;
        this.closeStatementsOnClose = true;
        netAgent_ = (NetAgent) super.agent_;
        if (netAgent_.exceptionOpeningSocket_ != null) {
            throw netAgent_.exceptionOpeningSocket_;
        }
        checkDatabaseName();
        String password = ClientBaseDataSource.getPassword(properties);
        securityMechanism_ = ClientBaseDataSource.getSecurityMechanism(properties);
        isODBCDriver = ClientBaseDataSource.getIsODBCDriver(properties);
        flowConnect(password, securityMechanism_, true /* GemStone change */);

        if(!isConnectionNull())
        	completeConnect();
        
        if (isODBCDriver) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "ODBC: Adding odbc connection to the connections list");
          }
          odbcConnectionList.add(this);
        }
    }

    // For JDBC 2 Connections
    public NetConnection(NetLogWriter netLogWriter,
                         String user,
                         String password,
                         com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
                         int rmId,
                         boolean isXAConn) throws SqlException {
        super(netLogWriter, user, password, isXAConn, dataSource);
        this.pooledConnection_ = null;
        this.closeStatementsOnClose = true;
        netAgent_ = (NetAgent) super.agent_;
        initialize(password, dataSource, rmId, isXAConn);
    }

    public NetConnection(NetLogWriter netLogWriter,
                         String ipaddr,
                         int portNumber,
                         com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
                         boolean isXAConn) throws SqlException {
        super(netLogWriter, isXAConn, dataSource);
        this.pooledConnection_ = null;
        this.closeStatementsOnClose = true;
        netAgent_ = (NetAgent) super.agent_;
        if (netAgent_.exceptionOpeningSocket_ != null) {
            throw netAgent_.exceptionOpeningSocket_;
        }
        checkDatabaseName();
        this.isXAConnection_ = isXAConn;
        flowSimpleConnect();
        productID_ = targetSrvrlslv_;
        super.completeConnect();
    }
    
    // For JDBC 2 Connections
    /**
     * This constructor is called from the ClientPooledConnection object 
     * to enable the NetConnection to pass <code>this</code> on to the associated 
     * prepared statement object thus enabling the prepared statement object 
     * to inturn  raise the statement events to the ClientPooledConnection object
     * @param netLogWriter NetLogWriter object associated with this connection
     * @param user         user id for this connection
     * @param password     password for this connection
     * @param dataSource   The DataSource object passed from the PooledConnection 
     *                     object from which this constructor was called
     * @param rmId         The Resource manager ID for XA Connections
     * @param isXAConn     true if this is a XA connection
     * @param cpc          The ClientPooledConnection object from which this 
     *                     NetConnection constructor was called. This is used
     *                     to pass StatementEvents back to the pooledConnection
     *                     object
     * @throws             SqlException
     */
    
    public NetConnection(NetLogWriter netLogWriter,
                         String user,
                         String password,
                         com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
                         int rmId,
                         boolean isXAConn,
                         ClientPooledConnection cpc) throws SqlException {
        super(netLogWriter, user, password, isXAConn, dataSource);
        netAgent_ = (NetAgent) super.agent_;
        initialize(password, dataSource, rmId, isXAConn);
        this.pooledConnection_=cpc;
        this.closeStatementsOnClose = !cpc.isStatementPoolingEnabled();
    }

// GemStone changes BEGIN
    protected static final String getServerUrl(String serverName, String port) {
      return serverName + '[' + port + ']';
    }

    protected static final String getServerUrl(String serverName, int port) {
      return getServerUrl(serverName, Integer.toString(port));
    }

    protected void preAgentInitialize(String password,
        com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
        java.util.Properties properties) throws SqlException {

      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "NetConnection::preInitialize new connection: " + this);
      }
      if (properties != null) {

        String drdaProtocol = properties.getProperty(
            ClientDRDADriver.DRDA_CONNECTION_PROTOCOL);
        if (drdaProtocol != null && drdaProtocol.toLowerCase().contains(
            ClientDRDADriver.SNAPPY_PROTOCOL)) {
          snappyDRDAProtocol = true;
        }

        String timeoutStr = properties.getProperty(ClientAttribute.READ_TIMEOUT);
        if (timeoutStr != null) {
          this.loginTimeout_ = Integer.parseInt(timeoutStr);
          if (this.loginTimeout_ == 0) { // indicate infinite timeout
            this.loginTimeout_ = INFINITE_LOGIN_TIMEOUT;
          }
        }
        String keepAliveIdleStr = properties
            .getProperty(ClientAttribute.KEEPALIVE_IDLE);
        if (keepAliveIdleStr != null) {
          this.keepAliveIdle_ = Integer.parseInt(keepAliveIdleStr);
        }
        String keepAliveIntvlStr = properties
            .getProperty(ClientAttribute.KEEPALIVE_INTVL);
        if (keepAliveIntvlStr != null) {
          this.keepAliveIntvl_ = Integer.parseInt(keepAliveIntvlStr);
        }
        String keepAliveCntStr = properties
            .getProperty(ClientAttribute.KEEPALIVE_CNT);
        if (keepAliveCntStr != null) {
          this.keepAliveCnt_ = Integer.parseInt(keepAliveCntStr);
        }
        String ncjBatchSizeStr = properties
            .getProperty(ClientAttribute.NCJ_BATCH_SIZE);
        if (ncjBatchSizeStr != null) {
          this.ncjBatchSize_ = Integer.parseInt(ncjBatchSizeStr);
        }
        String ncjCacheSizeStr = properties
            .getProperty(ClientAttribute.NCJ_CACHE_SIZE);
        if (ncjCacheSizeStr != null) {
          this.ncjCacheSize_ = Integer.parseInt(ncjCacheSizeStr);
        }
        String loadBalanceStr = properties
            .getProperty(ClientAttribute.LOAD_BALANCE);
        if (loadBalanceStr != null) {
          this.loadBalance_ = "true".equalsIgnoreCase(loadBalanceStr);
        }
        String alternateNetServersStr = properties
            .getProperty(ClientAttribute.SECONDARY_LOCATORS);
        if (alternateNetServersStr != null) {
          this.alternateNetServers_ = alternateNetServersStr;
        }
        String disableStreamingStr = properties.getProperty(
            ClientAttribute.DISABLE_STREAMING,
            System.getProperty(Attribute.CLIENT_JVM_PROPERTY_PREFIX
                + ClientAttribute.DISABLE_STREAMING));
        if (disableStreamingStr != null) {
          this.disableStreaming_ = "true".equalsIgnoreCase(disableStreamingStr);
        }

        String skipListeners = properties.getProperty(
            ClientAttribute.SKIP_LISTENERS, "false");
        if (skipListeners != null) {
          this.skipListeners_ = "true".equalsIgnoreCase(skipListeners);
        }
        String queryHDFS = properties.getProperty(
            ClientAttribute.QUERY_HDFS, "false");        
        if (queryHDFS != null) {
          this.queryHDFS_ = "true".equalsIgnoreCase(queryHDFS);
        }

        // Using the queryHDFS flag for query routing property
        // by default query-routing will be true for snappydata
        // URL but false otherwise
        boolean toRouteQuery = isSnappyDRDAProtocol();
        String routeQuery = properties.getProperty(
            ClientAttribute.ROUTE_QUERY);
        if (routeQuery != null) {
          toRouteQuery = "true".equalsIgnoreCase(routeQuery);
        }
        // can't set both query-HDFS and route-query due to above
        if (this.queryHDFS_ && toRouteQuery) {
          throw new SqlException(agent_.logWriter_,
              new ClientMessageId(SQLState.PROPERTY_INVALID_VALUE),
              ClientAttribute.QUERY_HDFS, "true");
        }
        this.queryHDFS_ |= toRouteQuery;
        String skipConstraints = properties.getProperty(
            ClientAttribute.SKIP_CONSTRAINT_CHECKS, "false");
        if (skipConstraints != null) {
          this.skipConstraints_ = "true".equalsIgnoreCase(skipConstraints);
        }

        String syncCommits = properties.getProperty(
            ClientAttribute.TX_SYNC_COMMITS,
            System.getProperty(ClientAttribute.GFXD_TX_SYNC_COMMITS,
            System.getProperty(ClientAttribute.SQLF_TX_SYNC_COMMITS)));
        if (syncCommits != null) {
          this.syncCommits_ = "true".equalsIgnoreCase(syncCommits);
        }

        String disableTXBatching = properties.getProperty(
            ClientAttribute.DISABLE_TX_BATCHING,
            System.getProperty(ClientAttribute.GFXD_DISABLE_TX_BATCHING,
            System.getProperty(ClientAttribute.SQLF_DISABLE_TX_BATCHING)));
        if (syncCommits != null) {
          this.disableTXBatching_ = "true".equalsIgnoreCase(disableTXBatching);
        }

        String disableCancel = properties.getProperty(
            ClientAttribute.DISABLE_THINCLIENT_CANCEL,
            System.getProperty(ClientAttribute.GFXD_DISABLE_THINCLIENT_CANCEL,
                System.getProperty(com.vmware.sqlfire.jdbc.ClientAttribute.SQLF_THINCLIENT_CANCEL)));
        if (disableCancel != null) {
          this.disableCancel_ = "true".equalsIgnoreCase(disableCancel);
        }

        String skipLocks = properties.getProperty(ClientAttribute.SKIP_LOCKS);
        if (skipLocks != null) {
          this.skipLocks_ = "true".equalsIgnoreCase(skipLocks);
          // disable load-balance property for this connection else it may
          // still get stuck on the (local) control connection locks etc
          if (this.skipLocks_ && loadBalanceStr == null) {
            this.loadBalance_ = false;
          }
        }

        String controlConn = properties.getProperty(PROP_CONTROL_CONN);
        if (controlConn != null) {
          if ("true".equalsIgnoreCase(controlConn)) {
            this.defaultIsolation_ = TRANSACTION_NONE;
            this.autoCommit_ = false;
          }
          properties.remove(PROP_CONTROL_CONN);
        }
      }

      if (dataSource != null) {
        securityMechanism_ = dataSource.getSecurityMechanism(password);
      }
      else if (properties != null) {
        securityMechanism_ = ClientBaseDataSource
            .getSecurityMechanism(properties);
      }

      setDeferredResetPassword(password);
      checkDatabaseName();
      dataSource_ = dataSource;
    }

    private void initialize(String password,
                            com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource dataSource,
                            int rmId,
                            boolean isXAConn) throws SqlException {
        this.rmId_ = rmId;
        this.isXAConnection_ = isXAConn;
        /* (original code)
        securityMechanism_ = dataSource.getSecurityMechanism(password);

        setDeferredResetPassword(password);
        checkDatabaseName();
        dataSource_ = dataSource;
        this.rmId_ = rmId;
        this.isXAConnection_ = isXAConn;
        */
// GemStone changes END
        flowConnect(password, securityMechanism_, true /* GemStone change */);
        completeConnect();

    }

    // preferably without password in the method signature.
    // We can probally get rid of flowReconnect method.
    public void resetNetConnection(com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter)
            throws SqlException {
        super.resetConnection(logWriter);
        //----------------------------------------------------
        // do not reset managers on a connection reset.  this information shouldn't
        // change and can be used to check secmec support.

        targetExtnam_ = null;
        targetSrvclsnm_ = null;
        targetSrvnam_ = null;
        targetSrvrlslv_ = null;
        publicKey_ = null;
        targetPublicKey_ = null;
        sourceSeed_ = null;
        targetSeed_ = null;
        targetSecmec_ = 0;
        resetConnectionAtFirstSql_ = false;
        // properties prddta_ and crrtkn_ will be initialized by
        // calls to constructPrddta() and constructCrrtkn()
        //----------------------------------------------------------
        boolean isDeferredReset = flowReconnect(getDeferredResetPassword(),
                                                securityMechanism_);
        completeReset(isDeferredReset);
    }


    protected void reset_(com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter)
            throws SqlException {
        if (inUnitOfWork_) {
            throw new SqlException(logWriter, 
                new ClientMessageId(
                    SQLState.NET_CONNECTION_RESET_NOT_ALLOWED_IN_UNIT_OF_WORK));
        }
        resetNetConnection(logWriter);
    }

    java.util.List getSpecialRegisters() {
        if (xares_ != null) {
            return xares_.getSpecialRegisters();
        } else {
            return null;
        }
    }

    public void addSpecialRegisters(String s) {
        if (xares_ != null) {
            xares_.addSpecialRegisters(s);
        }
    }

    public void completeConnect() throws SqlException {
        super.completeConnect();
    }

    protected void completeReset(boolean isDeferredReset)
            throws SqlException {
        super.completeReset(isDeferredReset, closeStatementsOnClose, xares_);
    }

    public void flowConnect(String password,
// GemStone changes BEGIN
                            int securityMechanism,
                            boolean doFailover) throws SqlException {
      // disable disconnect event that will close everything since we may be
      // able to failover and retry
      final boolean origDisableDisconnect = this.agent_.disableDisconnectEvent_;
      if (doFailover) {
        this.agent_.disableDisconnectEvent_ = true;
      }
      try {
       java.util.Set failedUrls = null;
       for (;;) {
         try {
           /* (original code)
                            int securityMechanism) throws SqlException {
           */
// GemStone changes END
        netAgent_ = (NetAgent) super.agent_;
        constructExtnam();
        // these calls need to be after newing up the agent
        // because they require the ccsid manager
        constructPrddta();  // construct product data

        netAgent_.typdef_ = new Typdef(netAgent_, 1208, NetConfiguration.SYSTEM_ASC, 1200, 1208);
        netAgent_.targetTypdef_ = new Typdef(netAgent_);
        netAgent_.originalTargetTypdef_ = netAgent_.targetTypdef_;
        setDeferredResetPassword(password);
        try {
            switch (securityMechanism) {
            case NetConfiguration.SECMEC_USRIDPWD: // Clear text user id and password
                checkUserPassword(user_, password);
                flowUSRIDPWDconnect(password);
                break;
            case NetConfiguration.SECMEC_USRIDONL: // Clear text user, no password sent to server
                checkUser(user_);
                flowUSRIDONLconnect();
                break;
            case NetConfiguration.SECMEC_USRENCPWD: // Clear text user, encrypted password
                checkUserPassword(user_, password);
                flowUSRENCPWDconnect(password);
                break;
            case NetConfiguration.SECMEC_EUSRIDPWD: // Encrypted user, encrypted password
                checkUserPassword(user_, password);
                flowEUSRIDPWDconnect(password);
                break;
            case NetConfiguration.SECMEC_EUSRIDDTA:
                checkUserPassword(user_, password);
                flowEUSRIDDTAconnect();
                break;
            case NetConfiguration.SECMEC_EUSRPWDDTA:
                checkUserPassword(user_, password);
                flowEUSRPWDDTAconnect(password);
                break;
            case NetConfiguration.SECMEC_USRSSBPWD: // Clear text user, strong password substitute
                checkUserPassword(user_, password);
                flowUSRSSBPWDconnect(password);
                break;

            default:
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.SECMECH_NOT_SUPPORTED),
                    new Integer(securityMechanism));
            }
        } catch (java.lang.Throwable e) { // if *anything* goes wrong, make sure the connection is destroyed
            // always mark the connection closed in case of an error.
            // This prevents attempts to use this closed connection
            // to retrieve error message text if an error SQLCA
            // is returned in one of the connect flows.
// GemStone changes BEGIN
           if (!this.loadBalance_)
// GemStone changes END
            markClosed();
            // logWriter may be closed in agent_.close(),
            // so SqlException needs to be created before that
            // but to be thrown after.
            SqlException exceptionToBeThrown;
            if (e instanceof SqlException) // rethrow original exception if it's an SqlException
            {
                exceptionToBeThrown = (SqlException) e;
            } else // any other exceptions will be wrapped by an SqlException first
            {
                exceptionToBeThrown = new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.JAVA_EXCEPTION),
                    e.getClass().getName(), e.getMessage(), e);
            }

            try {
                if (agent_ != null) {
                    agent_.close();
                }
            } catch (SqlException ignoreMe) {
            }

            throw exceptionToBeThrown;
        }
// GemStone changes BEGIN
        break;
        } catch (SqlException sqle) {
          if (doFailover) {
            failedUrls = handleFailover(failedUrls, sqle);
          }
          else { // failover already handled correctly in createNewAgentOrReset
            throw sqle;
          }
        }
      }
     } finally {
       if (doFailover) {
         this.agent_.disableDisconnectEvent_ = origDisableDisconnect;
       }
     }
// GemStone changes END
    }
    
    protected void flowSimpleConnect() throws SqlException {
        netAgent_ = (NetAgent) super.agent_;
        constructExtnam();
        // these calls need to be after newing up the agent
        // because they require the ccsid manager
        constructPrddta();  // construct product data

        netAgent_.typdef_ = new Typdef(netAgent_, 1208, NetConfiguration.SYSTEM_ASC, 1200, 1208);
        netAgent_.targetTypdef_ = new Typdef(netAgent_);
        netAgent_.originalTargetTypdef_ = netAgent_.targetTypdef_;

        try {
            flowServerAttributes();
        } catch (java.lang.Throwable e) { // if *anything* goes wrong, make sure the connection is destroyed
            // always mark the connection closed in case of an error.
            // This prevents attempts to use this closed connection
            // to retrieve error message text if an error SQLCA
            // is returned in one of the connect flows.
// GemStone changes BEGIN
           if (!this.loadBalance_)
// GemStone changes END
            markClosed();
            // logWriter may be closed in agent_.close(),
            // so SqlException needs to be created before that
            // but to be thrown after.
            SqlException exceptionToBeThrown;
            if (e instanceof SqlException) // rethrow original exception if it's an SqlException
            {
                exceptionToBeThrown = (SqlException) e;
            } else // any other exceptions will be wrapped by an SqlException first
            {
                exceptionToBeThrown = new SqlException(agent_.logWriter_,
                    new ClientMessageId(SQLState.JAVA_EXCEPTION),
                    e.getClass().getName(), e.getMessage(), e);
            }

            try {
                if (agent_ != null) {
                    agent_.close();
                }
            } catch (SqlException ignoreMe) {
            }

            throw exceptionToBeThrown;
        }
    }

    protected boolean flowReconnect(String password, int securityMechanism) throws SqlException {
        constructExtnam();
        // these calls need to be after newing up the agent
        // because they require the ccsid manager
        constructPrddta();  //modify this to not new up an array

        checkSecmgrForSecmecSupport(securityMechanism);
        try {
            switch (securityMechanism) {
            case NetConfiguration.SECMEC_USRIDPWD: // Clear text user id and password
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            case NetConfiguration.SECMEC_USRIDONL: // Clear text user, no password sent to server
                checkUser(user_);
                resetConnectionAtFirstSql_ = true;
                return true;
            case NetConfiguration.SECMEC_USRENCPWD: // Clear text user, encrypted password
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            case NetConfiguration.SECMEC_EUSRIDPWD: // Encrypted user, encrypted password
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            case NetConfiguration.SECMEC_EUSRIDDTA:
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            case NetConfiguration.SECMEC_EUSRPWDDTA:
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            case NetConfiguration.SECMEC_USRSSBPWD: // Clear text user, strong password substitute
                checkUserPassword(user_, password);
                resetConnectionAtFirstSql_ = true;
                setDeferredResetPassword(password);
                return true;
            default:
                throw new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.SECMECH_NOT_SUPPORTED),
                    new Integer(securityMechanism));
            }
        } catch (SqlException sqle) {            // this may not be needed because on method up the stack
// GemStone changes BEGIN
           if (!this.loadBalance_)
// GemStone changes END
            markClosed();                       // all reset exceptions are caught and wrapped in disconnect exceptions
            try {
                if (agent_ != null) {
                    agent_.close();
                }
            } catch (SqlException ignoreMe) {
            }
            throw sqle;
        }
    }

    protected void finalize() throws java.lang.Throwable {
        super.finalize();
    }

    protected byte[] getCnnToken() {
        return cnntkn_;
    }

    protected short getSequenceNumber() {
        return ++seqNo_;
    }

    //--------------------------------flow methods--------------------------------

    private void flowUSRIDPWDconnect(String password) throws SqlException {
        flowServerAttributesAndKeyExchange(NetConfiguration.SECMEC_USRIDPWD,
                null); // publicKey

        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                user_,
                password,
                null, //encryptedUserid
                null); //encryptedPassword
    }


    private void flowUSRIDONLconnect() throws SqlException {
        flowServerAttributesAndKeyExchange(NetConfiguration.SECMEC_USRIDONL,
                null); //publicKey

        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                user_,
                null, //password
                null, //encryptedUserid
                null); //encryptedPassword
    }


    private void flowUSRENCPWDconnect(String password) throws SqlException {
        flowServerAttributes();

        checkSecmgrForSecmecSupport(NetConfiguration.SECMEC_USRENCPWD);
        initializePublicKeyForEncryption();
        flowKeyExchange(NetConfiguration.SECMEC_USRENCPWD, publicKey_);

        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                user_,
                null, //password
                null, //encryptedUserid
                encryptedPasswordForUSRENCPWD(password));
    }


    private void flowEUSRIDPWDconnect(String password) throws SqlException {
        flowServerAttributes();

        checkSecmgrForSecmecSupport(NetConfiguration.SECMEC_EUSRIDPWD);
        initializePublicKeyForEncryption();
        flowKeyExchange(NetConfiguration.SECMEC_EUSRIDPWD, publicKey_);

        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                null, //user
                null, //password
                encryptedUseridForEUSRIDPWD(),
                encryptedPasswordForEUSRIDPWD(password));
    }

    private void flowEUSRIDDTAconnect() throws SqlException {
        flowServerAttributes();

        checkSecmgrForSecmecSupport(NetConfiguration.SECMEC_EUSRIDPWD);
        initializePublicKeyForEncryption();
        flowKeyExchange(NetConfiguration.SECMEC_EUSRIDDTA, publicKey_);


        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                null, //user
                null, //password
                encryptedUseridForEUSRIDPWD(),
                null);//encryptedPasswordForEUSRIDPWD (password),
    }

    private void flowEUSRPWDDTAconnect(String password) throws SqlException {
        flowServerAttributes();

        checkSecmgrForSecmecSupport(NetConfiguration.SECMEC_EUSRPWDDTA);
        initializePublicKeyForEncryption();
        flowKeyExchange(NetConfiguration.SECMEC_EUSRPWDDTA, publicKey_);


        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                null, //user
                null, //password
                encryptedUseridForEUSRIDPWD(),
                encryptedPasswordForEUSRIDPWD(password));
    }

    /**
     * The User ID and Strong Password Substitute mechanism (USRSSBPWD)
     * authenticates the user like the user ID and password mechanism, but
     * the password does not flow. A password substitute is generated instead
     * using the SHA-1 algorithm, and is sent to the application server.
     *
     * The application server generates a password substitute using the same
     * algorithm and compares it with the application requester's password
     * substitute. If equal, the user is authenticated.
     *
     * The SECTKN parameter is used to flow the client and server encryption
     * seeds on the ACCSEC and ACCSECRD commands.
     *
     * More information in DRDA, V3, Volume 3 standard - PWDSSB (page 650)
     */
    private void flowUSRSSBPWDconnect(String password) throws SqlException {
        flowServerAttributes();

        checkSecmgrForSecmecSupport(NetConfiguration.SECMEC_USRSSBPWD);
        // Generate a random client seed to send to the target server - in
        // response we will also get a generated seed from this last one.
        // Seeds are used on both sides to generate the password substitute.
        initializeClientSeed();

        flowSeedExchange(NetConfiguration.SECMEC_USRSSBPWD, sourceSeed_);

        flowSecurityCheckAndAccessRdb(targetSecmec_, //securityMechanism
                user_,
                null,
                null,
                passwordSubstituteForUSRSSBPWD(password)); // PWD Substitute
    }

    private void flowServerAttributes() throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        netAgent_.netConnectionRequest_.writeExchangeServerAttributes(extnam_, //externalName
                targetAgent_,
                netAgent_.targetSqlam_,
                targetRdb_,
                targetSecmgr_,
                targetCmntcpip_,
                targetCmnappc_,
                targetXamgr_,
                targetSyncptmgr_,
                targetRsyncmgr_);
        agent_.flowOutsideUOW();
        netAgent_.netConnectionReply_.readExchangeServerAttributes(this);
        agent_.endReadChain();
    }

    private void flowKeyExchange(int securityMechanism, byte[] publicKey) throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        netAgent_.netConnectionRequest_.writeAccessSecurity(securityMechanism,
                databaseName_,
                publicKey);
        agent_.flowOutsideUOW();
        netAgent_.netConnectionReply_.readAccessSecurity(this, securityMechanism);
        agent_.endReadChain();
    }

    private void flowSeedExchange(int securityMechanism, byte[] sourceSeed) throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        netAgent_.netConnectionRequest_.writeAccessSecurity(securityMechanism,
                databaseName_,
                sourceSeed);
        agent_.flowOutsideUOW();
        netAgent_.netConnectionReply_.readAccessSecurity(this, securityMechanism);
        agent_.endReadChain();
    }

    private void flowServerAttributesAndKeyExchange(int securityMechanism,
                                                    byte[] publicKey) throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        writeServerAttributesAndKeyExchange(securityMechanism, publicKey);
        agent_.flowOutsideUOW();
        readServerAttributesAndKeyExchange(securityMechanism);
        agent_.endReadChain();
    }

    private void flowServerAttributesAndSeedExchange(int securityMechanism,
                                                     byte[] sourceSeed) throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        writeServerAttributesAndSeedExchange(sourceSeed);
        agent_.flowOutsideUOW();
        readServerAttributesAndSeedExchange();
        agent_.endReadChain();
    }

    private void flowSecurityCheckAndAccessRdb(int securityMechanism,
                                               String user,
                                               String password,
                                               byte[] encryptedUserid,
                                               byte[] encryptedPassword) throws SqlException {
        agent_.beginWriteChainOutsideUOW();
        writeSecurityCheckAndAccessRdb(securityMechanism,
                user,
                password,
                encryptedUserid,
                encryptedPassword);
        agent_.flowOutsideUOW();
        readSecurityCheckAndAccessRdb();
        agent_.endReadChain();
    }

    private void writeAllConnectCommandsChained(int securityMechanism,
                                                String user,
                                                String password) throws SqlException {
        writeServerAttributesAndKeyExchange(securityMechanism,
                null); // publicKey
        writeSecurityCheckAndAccessRdb(securityMechanism,
                user,
                password,
                null, //encryptedUserid
                null); //encryptedPassword,
    }

    private void readAllConnectCommandsChained(int securityMechanism) throws SqlException {
        readServerAttributesAndKeyExchange(securityMechanism);
        readSecurityCheckAndAccessRdb();
    }

    private void writeServerAttributesAndKeyExchange(int securityMechanism,
                                                     byte[] publicKey) throws SqlException {
        netAgent_.netConnectionRequest_.writeExchangeServerAttributes(extnam_, //externalName
                targetAgent_,
                netAgent_.targetSqlam_,
                targetRdb_,
                targetSecmgr_,
                targetCmntcpip_,
                targetCmnappc_,
                targetXamgr_,
                targetSyncptmgr_,
                targetRsyncmgr_);
        netAgent_.netConnectionRequest_.writeAccessSecurity(securityMechanism,
                databaseName_,
                publicKey);
    }

    private void writeServerAttributesAndSeedExchange(byte[] sourceSeed)
                                                        throws SqlException {
        
        // For now, we're just calling our cousin method to do the job
        writeServerAttributesAndKeyExchange(NetConfiguration.SECMEC_USRSSBPWD,
                                            sourceSeed);
    }

    private void readServerAttributesAndKeyExchange(int securityMechanism) throws SqlException {
        netAgent_.netConnectionReply_.readExchangeServerAttributes(this);
        netAgent_.netConnectionReply_.readAccessSecurity(this, securityMechanism);
    }

    private void readServerAttributesAndSeedExchange() throws SqlException {
        // For now, we're just calling our cousin method to do the job
        readServerAttributesAndKeyExchange(NetConfiguration.SECMEC_USRSSBPWD);
    }

    private void writeSecurityCheckAndAccessRdb(int securityMechanism,
                                                String user,
                                                String password,
                                                byte[] encryptedUserid,
                                                byte[] encryptedPassword) throws SqlException {
        netAgent_.netConnectionRequest_.writeSecurityCheck(securityMechanism,
                databaseName_,
                user,
                password,
                encryptedUserid,
                encryptedPassword);
        netAgent_.netConnectionRequest_.writeAccessDatabase(databaseName_,
                false,
                crrtkn_,
                prddta_,
                netAgent_.typdef_);
    }

    private void readSecurityCheckAndAccessRdb() throws SqlException {
        netAgent_.netConnectionReply_.readSecurityCheck(this);
        netAgent_.netConnectionReply_.readAccessDatabase(this);
    }

    void writeDeferredReset() throws SqlException {
        // NetConfiguration.SECMEC_USRIDPWD
        if (securityMechanism_ == NetConfiguration.SECMEC_USRIDPWD) {
            writeAllConnectCommandsChained(NetConfiguration.SECMEC_USRIDPWD,
                    user_,
                    getDeferredResetPassword());
        }
        // NetConfiguration.SECMEC_USRIDONL
        else if (securityMechanism_ == NetConfiguration.SECMEC_USRIDONL) {
            writeAllConnectCommandsChained(NetConfiguration.SECMEC_USRIDONL,
                    user_,
                    null);  //password
        }
        // Either NetConfiguration.SECMEC_USRENCPWD,
        // NetConfiguration.SECMEC_EUSRIDPWD or
        // NetConfiguration.SECMEC_USRSSBPWD
        else {
            if (securityMechanism_ == NetConfiguration.SECMEC_USRSSBPWD)
                initializeClientSeed();
            else // SECMEC_USRENCPWD, SECMEC_EUSRIDPWD
                initializePublicKeyForEncryption();

            // Set the resetConnectionAtFirstSql_ to false to avoid going in an
            // infinite loop, since all the flow methods call beginWriteChain which then
            // calls writeDeferredResetConnection where the check for resetConnectionAtFirstSql_
            // is done. By setting the resetConnectionAtFirstSql_ to false will avoid calling the
            // writeDeferredReset method again.
            resetConnectionAtFirstSql_ = false;

            if (securityMechanism_ == NetConfiguration.SECMEC_USRSSBPWD)
                flowSeedExchange(securityMechanism_, sourceSeed_);
            else // SECMEC_USRENCPWD, SECMEC_EUSRIDPWD
                flowServerAttributesAndKeyExchange(securityMechanism_, publicKey_);

            agent_.beginWriteChainOutsideUOW();

            // Reset the resetConnectionAtFirstSql_ to true since we are done
            // with the flow method.
            resetConnectionAtFirstSql_ = true;

            // NetConfiguration.SECMEC_USRENCPWD
            if (securityMechanism_ == NetConfiguration.SECMEC_USRENCPWD) {
                writeSecurityCheckAndAccessRdb(NetConfiguration.SECMEC_USRENCPWD,
                        user_,
                        null, //password
                        null, //encryptedUserid
                        encryptedPasswordForUSRENCPWD(getDeferredResetPassword()));
            }
            // NetConfiguration.SECMEC_USRSSBPWD
            else if (securityMechanism_ == NetConfiguration.SECMEC_USRSSBPWD) {
                writeSecurityCheckAndAccessRdb(NetConfiguration.SECMEC_USRSSBPWD,
                        user_,
                        null,
                        null,
                        passwordSubstituteForUSRSSBPWD(getDeferredResetPassword()));
            }
            else {  // NetConfiguration.SECMEC_EUSRIDPWD
                writeSecurityCheckAndAccessRdb(NetConfiguration.SECMEC_EUSRIDPWD,
                        null, //user
                        null, //password
                        encryptedUseridForEUSRIDPWD(),
                        encryptedPasswordForEUSRIDPWD(getDeferredResetPassword()));
            }
        }
    }

    void readDeferredReset() throws SqlException {
        resetConnectionAtFirstSql_ = false;
        // either NetConfiguration.SECMEC_USRIDPWD or NetConfiguration.SECMEC_USRIDONL
        if (securityMechanism_ == NetConfiguration.SECMEC_USRIDPWD ||
                securityMechanism_ == NetConfiguration.SECMEC_USRIDONL) {
            readAllConnectCommandsChained(securityMechanism_);
        }
        // either NetConfiguration.SECMEC_USRENCPWD or NetConfiguration.SECMEC_EUSRIDPWD
        else {
            // either NetConfiguration.SECMEC_USRENCPWD or NetConfiguration.SECMEC_EUSRIDPWD
            readSecurityCheckAndAccessRdb();
        }
        if (agent_.loggingEnabled()) {
            agent_.logWriter_.traceConnectResetExit(this);
        }
    }

    //-------------------parse callback methods--------------------------------

    void setServerAttributeData(String extnam,
                                String srvclsnm,
                                String srvnam,
                                String srvrlslv) {
        targetExtnam_ = extnam;          // any of these could be null
        targetSrvclsnm_ = srvclsnm;      // since then can be optionally returned from the
        targetSrvnam_ = srvnam;          // server
        targetSrvrlslv_ = srvrlslv;
    }

    // secmecList is always required and will not be null.
    // secchkcd has an implied severity of error.
    // it will be returned if an error is detected.
    // if no errors and security mechanism requires a sectkn, then
    void setAccessSecurityData(int secchkcd,
                               int desiredSecmec,
                               int[] secmecList,
                               boolean sectknReceived,
                               byte[] sectkn) throws DisconnectException {
        // - if the secchkcd is not 0, then map to an exception.
        if (secchkcd != CodePoint.SECCHKCD_00) {
            // the implied severity code is error
            netAgent_.setSvrcod(CodePoint.SVRCOD_ERROR);
            agent_.accumulateReadException(mapSecchkcd(secchkcd));
        } else {
            // - verify that the secmec parameter reflects the value sent
            // in the ACCSEC command.
            // should we check for null list
            if ((secmecList.length == 1) &&
                    (secmecList[0] == desiredSecmec)) {
                // the security mechanism returned from the server matches
                // the mechanism requested by the client.
                targetSecmec_ = secmecList[0];

                if ((targetSecmec_ == NetConfiguration.SECMEC_USRENCPWD) ||
                        (targetSecmec_ == NetConfiguration.SECMEC_EUSRIDPWD) ||
                        (targetSecmec_ == NetConfiguration.SECMEC_USRSSBPWD) ||
                        (targetSecmec_ == NetConfiguration.SECMEC_EUSRIDDTA) ||
                        (targetSecmec_ == NetConfiguration.SECMEC_EUSRPWDDTA)) {

                    // a security token is required for USRENCPWD, or EUSRIDPWD.
                    if (!sectknReceived) {
                        agent_.accumulateChainBreakingReadExceptionAndThrow(
                            new DisconnectException(agent_, 
                                new ClientMessageId(SQLState.NET_SECTKN_NOT_RETURNED)));
                    } else {
                        if (targetSecmec_ == NetConfiguration.SECMEC_USRSSBPWD)
                            targetSeed_ = sectkn;
                        else
                            targetPublicKey_ = sectkn;
                        if (encryptionManager_ != null) {
                            encryptionManager_.resetSecurityKeys();
                        }
                    }
                }
            } else {
                // accumulate an SqlException and don't disconnect yet
                // if a SECCHK was chained after this it would receive a secchk code
                // indicating the security mechanism wasn't supported and that would be a
                // chain breaking exception.  if no SECCHK is chained this exception
                // will be surfaced by endReadChain
                // agent_.accumulateChainBreakingReadExceptionAndThrow (
                //   new DisconnectException (agent_,"secmec not supported ","0000", -999));
                agent_.accumulateReadException(new SqlException(agent_.logWriter_, 
                    new ClientMessageId(SQLState.NET_SECKTKN_NOT_RETURNED)));
            }
        }
    }

    void securityCheckComplete(int svrcod, int secchkcd) {
        netAgent_.setSvrcod(svrcod);
        if (secchkcd == CodePoint.SECCHKCD_00) {
            return;
        }
        agent_.accumulateReadException(mapSecchkcd(secchkcd));
    }

    void rdbAccessed(int svrcod,
                     String prdid,
                     boolean crrtknReceived,
                     byte[] crrtkn) {
        if (crrtknReceived) {
            crrtkn_ = crrtkn;
        }

        netAgent_.setSvrcod(svrcod);
        productID_ = prdid;
        // check for GFXD DRDA server
        if (prdid.length() == 8
            && prdid.startsWith(DRDAConstants.DERBY_DRDA_SERVER_ID)) {
          serverVersionLevel_ = Integer.parseInt(prdid.substring (3, 5));
          serverReleaseLevel_ = Integer.parseInt(prdid.substring (5, 7));
          serverModifyLevel_ = Integer.parseInt(prdid.substring (7, 8));
          // set the hashing scheme as per server version by default
          if (!ResolverUtils.isGFXD1302HashingStateSet()) {
            boolean useNewHashing = false;
            if ((serverVersionLevel_ > 10)
                || (serverVersionLevel_ == 10 && serverReleaseLevel_ > 4)
                || (serverVersionLevel_ == 10 && serverReleaseLevel_ == 4
                    && serverModifyLevel_ >= 1)) {
              useNewHashing = true;
            }
            if (useNewHashing) {
              ResolverUtils.setUseGFXD1302Hashing(true);
            }
            else {
              ResolverUtils.setUsePre1302Hashing(true);
            }
          }
        }
    }


    //-------------------Abstract object factories--------------------------------

// GemStone changes BEGIN

    public static final java.util.Random rand;

    /**
     * The maximum number of servers that are retained before the non-responsive
     * ones are purged. The total number of servers can exceed this limit if all
     * are responsive.
     */
    public static final int MAX_SERVER_LIMIT = 1024;

    /**
     * Indicates if this is connection is created during a failover retry so as
     * to enable setting the posDup flag on server for current statement.
     * Currently the {@link CodePoint#RDBALWUPD} byte doubles as a flag to
     * indicate failover retry to the server.
     */
    protected boolean statementFailover_ = false;

    /**
     * The original host:port given for this connection.
     */
    protected String connUrl;

    /**
     * The counterpart of {@link #connUrl} having hostname if {@link #connUrl}
     * has address and address if {@link #connUrl} has hostname.
     */
    protected String connUrl2;
    protected String connServer;

    private Properties connProps;

    /**
     * For single hop this statement would be used to re-fetch the bucket
     * locations on the server.
     */
    public java.sql.CallableStatement getBucketToServerDetails_;

    public static final String BUCKET_AND_SERVER_PROC_QUERY =
      "call SYS.GET_BUCKET_TO_SERVER_MAPPING2(?, ?)";

    /** array of SQLState strings that denote failover should be done */
    public static final String[] failoverSQLStateArray = new String[] { "08001",
        "08003", "08004", "08006", "X0J15", "X0Z32", "XN001", "XN014", "XN016",
        "58009", "58014", "58015", "58016", "58017", "57017", "58010", "30021",
        "XJ040", "XJ041", "XSDA3", "XSDA4", "XSDAJ", "XJ217" };

    /**
     * failoverSQLStateArray in Set form for faster access
     */
    private static final java.util.HashSet failoverSQLStates;

    static {
      rand = new java.util.Random(Long.getLong("gemfirexd.RandomSeed",
          System.currentTimeMillis()).longValue());
      failoverSQLStates = new java.util.HashSet(failoverSQLStateArray.length);
      for (int index = 0; index < failoverSQLStateArray.length; ++index) {
        failoverSQLStates.add(failoverSQLStateArray[index]);
      }
    }

    private String getNextServer(java.util.Iterator serverIter,
        java.util.Iterator alternates, java.util.Set failedUrls,
        boolean controlConn) {
      Object serverUrl;
      // for control connection, try the "alternates" first
      if (controlConn) {
        serverUrl = getNextAlternate(alternates, failedUrls);
        if (serverUrl != null) {
          return (String)serverUrl;
        }
      }
      // go to next server skipping currently failed ones if required
      while (serverIter.hasNext()) {
        serverUrl = serverIter.next();
        if (!failedUrls.contains(serverUrl)) {
          return (String)serverUrl;
        }
      }
      if (!controlConn) {
        serverUrl = getNextAlternate(alternates, failedUrls);
        if (serverUrl != null) {
          return (String)serverUrl;
        }
      }
      return null;
    }

    private String getNextAlternate(java.util.Iterator alternates,
        java.util.Set failedUrls) {
      String serverUrl;
      if (alternates != null) {
        while (alternates.hasNext()) {
          String alternate = (String)alternates.next();
          // convert to host[port] format while checking for a valid format
          int[] port = new int[1];
          String url = SharedUtils.getHostPort(alternate, port);
          serverUrl = getServerUrl(url, port[0]);
          if (!failedUrls.contains(serverUrl)) {
            return (String)serverUrl;
          }
        }
      }
      return null;
    }

    private java.util.Iterator getAlternateNetServers(
        java.util.Iterator alternates) {
      if (alternates == null && this.alternateNetServers_.length() > 0) {
        return SharedUtils.toSortedSet(this.alternateNetServers_, true)
            .iterator();
      }
      else {
        return null;
      }
    }

    private String getAlternateNetServers() {
      if (this.alternateNetServers_.length() > 0) {
        return ", secondary network servers: " + this.alternateNetServers_;
      }
      else {
        return "";
      }
    }

    /** keeps the count of retries done against a failed server */
    private java.util.Map failedServersRetryCounts;
    private String getPreferredServer(final String thisUrl,
        java.util.Set failedUrls, boolean refreshServerList,
        int[] prefServerPort, final Properties incomingProps)
            throws SQLException {

      // check that DS failover info object has been initialized
      assert this.failoverQueryInfo_ != null;
      // check that this is invoked under the global static lock
      assert Thread.holdsLock(staticSync_);

      final String qAllAndPreferredServer =
        "call SYS.GET_ALLSERVERS_AND_PREFSERVER2(?, ?, ?, ?)";
      final String qPreferredServer = "call SYS.GET_PREFSERVER(?, ?, ?)";
      final String qAllServersOnly = "select NETSERVERS, KIND from SYS." +
        "MEMBERS where LENGTH(NETSERVERS) > 0 order by LENGTH(LOCATOR) desc";

      String serverUrl = null;
      java.util.Iterator serverIter = null;
      java.util.Iterator alternates = null;

      // get the full server list from server/locator that will be used for
      // failover or load-balancing
      NetConnection conn = null;
      java.sql.CallableStatement cstmt;
      String allServers = null;
      java.sql.ResultSet rs = null;
      boolean triedAllServers = false;
      for (;;) {
        try {
          cstmt = null;
          if (this.failoverQueryInfo_.locateConn_ != null) {
            conn = this.failoverQueryInfo_.locateConn_;
            if (refreshServerList) {
              cstmt = this.failoverQueryInfo_.allStmt_;
            }
            else {
              cstmt = this.failoverQueryInfo_.prefStmt_;
            }
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Using control connection to host[port] '" + conn
                  .getServerLocation() + "' with refresh=" + refreshServerList);
            }
          }
          else {
            // initialize serverUrl with thisUrl if not failed, else loop to
            // find non-failed server
            if (serverUrl == null) {
              if (failedUrls == null || failedUrls.isEmpty()
                  || !failedUrls.contains(thisUrl)) {
                serverUrl = thisUrl;
              }
              else {
                if (serverIter == null) {
                  serverIter = this.failoverQueryInfo_.failoverAddresses_
                      .keySet().iterator();
                }
                alternates = getAlternateNetServers(alternates);
                serverUrl = getNextServer(serverIter, alternates,
                    failedUrls, false);
                // if nothing found then try thisUrl as last resort
                if (serverUrl == null) {
                  serverUrl = thisUrl;
                }
              }
            }
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Creating new control connection to host[port] '" + serverUrl
                      + "' with refresh=" + refreshServerList);
            }
            final Properties locateProps = getConnectionProperties(
                incomingProps);
            locateProps.setProperty(ClientAttribute.LOAD_BALANCE, "false");
            locateProps.setProperty(PROP_CONTROL_CONN, "true");
            // change URL to host:port format
            final int origPort = prefServerPort[0];
            String host = SharedUtils.getHostPort(serverUrl, prefServerPort);
            conn = (NetConnection)java.sql.DriverManager.getConnection(
                com.pivotal.gemfirexd.internal.client.am.Configuration
                    .jdbcDerbyNETProtocol() + host + ':' + prefServerPort[0],
                    locateProps);
            // force set the isolation-level,auto-commit to NONE,false
            try {
              if (conn.getTransactionIsolationX() != TRANSACTION_NONE) {
                conn.setTransactionIsolationX(TRANSACTION_NONE);
              }
              if (conn.autoCommit_) {
                conn.setAutoCommitX(false);
              }
            } catch (SqlException se) {
              throw se.getSQLException(conn.agent_ /* GemStoneAddition */);
            }
            prefServerPort[0] = origPort;
//            synchronized (this.failoverQueryInfo_.actualServerToConnectionMap_) {
//              this.failoverQueryInfo_.actualServerToConnectionMap_.put(serverUrl, conn); 
//            }
          }
          if (refreshServerList) {
            if (cstmt == null) {
              cstmt = conn.prepareCall(qAllAndPreferredServer);
            }
            cstmt.registerOutParameter(4, java.sql.Types.CLOB);
          }
          else {
            if (cstmt == null) {
              cstmt = conn.prepareCall(qPreferredServer);
            }
          }
          cstmt.registerOutParameter(2, java.sql.Types.VARCHAR);
          cstmt.registerOutParameter(3, java.sql.Types.INTEGER);
          if (failedUrls != null && failedUrls.size() > 0) {
            cstmt.setString(1, SharedUtils.toCSV(failedUrls));
          }
          else {
            cstmt.setNull(1, java.sql.Types.LONGVARCHAR);
          }
          cstmt.execute();
          if (refreshServerList) {
            allServers = cstmt.getString(4);
            // a null here indicates that result is too big and needs to be
            // obtained separately
            if (allServers == null) {
              rs = conn.createStatement().executeQuery(qAllServersOnly);
            }
          }
          break;
        } catch (SQLException ex) {
          // retry for connection/socket exceptions
          final FailoverStatus status;
          if ((status = doFailoverOnException(ex.getSQLState(),
              ex.getErrorCode(), ex)) != FailoverStatus.NONE) {
            String failUrl = serverUrl;
            final NetAgent netAgent;
            if (conn != null && (netAgent = conn.netAgent_) != null) {
              failUrl = netAgent.getServerLocation();
            }
            // close the connection ignoring any exceptions
            if (conn != null) {
              try {
                // try forcing the agent to close first since we want to free
                // up socket and other VM resources
                final Agent agent = conn.agent_;
                if (agent != null) {
                  try {
                    agent.close();
                  } catch (Throwable t) {
                    // deliberately ignored
                  }
                }
                conn.close();
              } catch (Throwable t) {
                // deliberately ignored
              }
            }
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Got connection exception when using control connection "
                      + "to host[port] '" + failUrl + "'");
            }
            if (failedUrls == null) {
              failedUrls = new java.util.HashSet();
            }
            if (status == FailoverStatus.NEW_SERVER) {
              failedUrls.add(failUrl);
            }
            // ignore the exception and try the next network server preferring
            // locator
            this.failoverQueryInfo_.clearControlConnection();
            // if no server is marked as failed then retry on all servers
            if (serverIter == null || failedUrls.isEmpty()) {
              serverIter = this.failoverQueryInfo_.failoverAddresses_.keySet()
                  .iterator();
            }
            alternates = getAlternateNetServers(alternates);
            serverUrl = getNextServer(serverIter, alternates, failedUrls, true);
            if (serverUrl == null) {
              // handle the case where the whole system may have gone down
              if (!triedAllServers) {
                serverUrl = this.connUrl;
                triedAllServers = true;
              }
              else {
                // exhausted all locator/server possibilities

                // for AUTH exceptions throw that back, else wrap in a generic
                // communication error
                if ("08004".equals(ex.getSQLState())) {
                  throw ex;
                }
                // clear the failedServersRetryCounts cached map
                this.failedServersRetryCounts = null;
                throw new DisconnectException(null, new ClientMessageId(
                    SQLState.COMMUNICATION_ERROR), "Failed after trying all "
                    + "available servers: " + this.failoverQueryInfo_
                    .failoverAddresses_.keySet() + ", for control URL: "
                    + getLocatorUrl(this.connUrl) + getAlternateNetServers(),
                      ex).getSQLException(this.agent_);
              }
            }
            refreshServerList = true;
          }
          else {
            throw ex;
          }
        }
      }
      java.util.LinkedHashMap newServerList = null;
      if (refreshServerList) {
        this.failoverQueryInfo_.locateConn_ = conn;
        this.failoverQueryInfo_.allStmt_ = cstmt;
        java.util.regex.Matcher matcher;
        newServerList = new java.util.LinkedHashMap();
        String serverList, host, portStr, vmKind = null;
        // handle both cases when result is in "allServers" list, or when
        // result is too large then in a separate ResultSet
        while (rs == null || rs.next()) {
          if (rs == null) {
            matcher = addrPat_.matcher(allServers);
          }
          else {
            serverList = rs.getString(1);
            matcher = addrPat_.matcher(serverList);
            vmKind = rs.getString(2);
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Received JVM kind '" + vmKind + "' with network servers: "
                      + serverList);
            }
          }
          while (matcher.find()) {
            host = matcher.group(1);
            portStr = matcher.group(3);
            if (rs == null) {
              vmKind = matcher.group(4);
              // trim the curly braces
              vmKind = vmKind.substring(1, vmKind.length() - 1);
            }
            // use the bind-address portion when host-name is empty
            if (host.length() == 0) {
              host = matcher.group(2).substring(1);
            }
            serverUrl = getServerUrl(host, portStr);
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "For JVM kind '" + vmKind + "' adding network server: "
                      + serverUrl);
            }
            // don't count the network servers on a stand-alone locator
            newServerList.put(serverUrl, vmKind.startsWith(SharedUtils
                .VM_LOCATOR) ? ServerType.LOCATOR : ServerType.QUERY);
          }
          if (rs == null) {
            break;
          }
        }
        if (rs != null) {
          rs.close();
        }
      }
      else {
        this.failoverQueryInfo_.prefStmt_ = cstmt;
      }
      // the first output parameter contains the preferred server
      // the second output parameter contains the port on preferred server
      serverUrl = cstmt.getString(2);
      if (serverUrl != null) {
        // host string is of the form: <name>/<address>
        // if <name> is present then use it else use <address>
        if (serverUrl.charAt(0) == '/') { // no host
          serverUrl = serverUrl.substring(1);
        }
        else {
          final int slashIndex = serverUrl.indexOf('/');
          if (slashIndex >= 0) {
            serverUrl = serverUrl.substring(0, slashIndex);
          }
        }
        prefServerPort[0] = cstmt.getInt(3);
        if (SanityManager.TraceClientStatementHA | SanityManager.TraceClientConn) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Received preferred server '" + serverUrl + '['
                  + prefServerPort[0] + "]'",
                  SanityManager.TraceClientConn ? new Throwable() : null);
        }
        // clear the failedServersRetryCounts cached map
        this.failedServersRetryCounts = null;
      }
      else {
        final java.util.LinkedHashMap failoverList;
        if (newServerList != null) {
          failoverList = newServerList;
        }
        else {
          failoverList = this.failoverQueryInfo_.failoverAddresses_;
        }
        if (SanityManager.TraceClientStatementHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Received no preferred server so trying among all available for "
                  + "host[port] '" + thisUrl + "': " + failoverList);
        }
        // could not find a locator or server as preferred, so try randomly
        // from among all the available servers preferring the current provided
        // one if it has not failed and is not a GemFireXD locator VM
        boolean prefSelf = true;
        ServerType type;
        if ((failedUrls != null && failedUrls.contains(thisUrl))
            || (newServerList != null && !newServerList.containsKey(thisUrl))
            || ((type = (ServerType)failoverList.get(thisUrl)) != null
                && type != ServerType.QUERY)) {
          prefSelf = false;
        }
        if (prefSelf) {
          if (SanityManager.TraceClientStatementHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Preferring given host[port] '" + thisUrl
                    + "' since it looks to be available");
          }
          serverUrl = SharedUtils.getHostPort(thisUrl, prefServerPort);
        }
        else {
          int numServers = failoverList.size();
          final java.util.ArrayList failoverAddresses =
            new java.util.ArrayList(numServers);
          java.util.Map.Entry urlEntry;
          java.util.Iterator iter = failoverList.entrySet().iterator();
          while (iter.hasNext()) {
            urlEntry = (java.util.Map.Entry)iter.next();
            if (urlEntry.getValue() == ServerType.QUERY
                && (failedUrls == null || failedUrls.isEmpty() || !failedUrls
                    .contains(urlEntry.getKey()))) {
              failoverAddresses.add(urlEntry.getKey());
            }
          }
          numServers = failoverAddresses.size();
          if (numServers == 0) {
            // also add just failed servers if they are present in master list
            // received from locator/server
            if (failedUrls != null && failedUrls.size() > 0
                && newServerList != null) {
              String failedUrl;
              iter = failedUrls.iterator();
              while (iter.hasNext()) {
                failedUrl = (String)iter.next();
                if (newServerList.containsKey(failedUrl)) {
                  // check for retry count
                  java.util.Map retryCounts = this.failedServersRetryCounts;
                  Integer retryCnt = Integer.valueOf(1);
                  if (retryCounts != null) {
                    Object cnt = retryCounts.get(failedUrl);
                    if (cnt != null) {
                      retryCnt = Integer.valueOf(((Integer)cnt).intValue() + 1);
                      // give up after 5 retries
                      if (retryCnt.intValue() > 5) {
                        continue;
                      }
                    }
                  }
                  else {
                    retryCounts = this.failedServersRetryCounts =
                        new java.util.HashMap();
                  }
                  retryCounts.put(failedUrl, retryCnt);
                  failoverAddresses.add(failedUrl);
                }
              }
            }
            numServers = failoverAddresses.size();
          }
          else {
            // clear the failedServersRetryCounts cached map
            this.failedServersRetryCounts = null;
          }
          if (numServers == 0) {
            // try erstwhile unresponsive servers as a fallback
            iter = failoverList.entrySet().iterator();
            while (iter.hasNext()) {
              urlEntry = (java.util.Map.Entry)iter.next();
              if (urlEntry.getValue() == ServerType.UNRESPONSIVE
                  && (failedUrls == null || failedUrls.isEmpty() || !failedUrls
                      .contains(urlEntry.getKey()))) {
                failoverAddresses.add(urlEntry.getKey());
              }
            }
            numServers = failoverAddresses.size();
          }
          if (numServers > 0) {
            final String url = (String)failoverAddresses.get(rand
                .nextInt(numServers));
            serverUrl = SharedUtils.getHostPort(url, prefServerPort);
            // if we picked a failedUrl then remove from failedUrls list
            if (failedUrls != null && failedUrls.size() > 0) {
              failedUrls.remove(url);
            }
          }
        }
      }
      // refresh the stored server list with the new one obtained from a server
      if (newServerList != null) {
        // Keep the old servers, including non-responsive ones till the
        // MAX_SERVER_LIMIT is not exceeded. This helps in determining that
        // a new connection to an unresponsive server can be serviced by
        // other servers in this DS's list.
        java.util.Map.Entry urlEntry;
        java.util.Iterator iter = this.failoverQueryInfo_.failoverAddresses_
            .entrySet().iterator();
        while (iter.hasNext()) {
          urlEntry = (java.util.Map.Entry)iter.next();
          if (!newServerList.containsKey(urlEntry.getKey())) {
            ServerType type = (ServerType)urlEntry.getValue();
            if (type == ServerType.QUERY) {
              // mark server as unresponsive so we will try to avoid failover
              // to it as far as possible
              type = ServerType.UNRESPONSIVE;
            }
            newServerList.put(urlEntry.getKey(), type);
            // keep at least one unresponsive server (i.e. the current one),
            // so this check is not done before the "for" loop
            if (newServerList.size() > MAX_SERVER_LIMIT) {
              break;
            }
          }
        }
        // add given URL to server list as an UNRESPONSIVE one if not provided
        if (connUrl != null && !newServerList.containsKey(connUrl)) {
          if (connUrl2 == null || !newServerList.containsKey(connUrl2)) {
            newServerList.put(connUrl, ServerType.UNRESPONSIVE);
          }
        }
        this.failoverQueryInfo_.failoverAddresses_ = newServerList;
      }

      return serverUrl;
    }

    protected Properties getConnectionProperties(Properties incomingProps) {
      final Properties props = new Properties();
      if (this.user_ != null) {
        props.setProperty(ClientAttribute.USERNAME, this.user_);
      }
      ClientBaseDataSource.setPassword(props,
          getDeferredResetPassword());
      ClientBaseDataSource.setSecurityMechanism(props,
          securityMechanism_);
      switch (this.clientSSLMode_) {
        case ClientBaseDataSource.SSL_OFF:
          break;
        case ClientBaseDataSource.SSL_BASIC:
          props.setProperty(ClientAttribute.SSL, "basic");
          break;
        case ClientBaseDataSource.SSL_PEER_AUTHENTICATION:
          props.setProperty(ClientAttribute.SSL,
              "peerAuthentication");
          break;
      }
      final Properties connProps = (this.connProps != null ? this.connProps
          : incomingProps);
      if (connProps != null) {
        final java.util.Enumeration incomingPropNames = connProps
            .propertyNames();
        while (incomingPropNames.hasMoreElements()) {
          final String propName = (String)incomingPropNames.nextElement();
          final String propValue = connProps.getProperty(propName);
          props.setProperty(propName, propValue);
        }
      }
      return props;
    }

    public Properties getConnectionProperties() {
      return getConnectionProperties(null);
    }

    private String preConnect(final String serverName,
        java.net.InetAddress serverAddr, final int[] port,
        final Properties incomingProps,
        final com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter)
        throws SqlException {
      try {
        synchronized (staticSync_) {
          DSConnectionInfo dsNetServers;
          java.util.LinkedHashMap failoverAddresses;
          java.util.Iterator iter = allDSQueryAddrs_.iterator();
          while (iter.hasNext()) {
            dsNetServers = (DSConnectionInfo)iter.next();
            failoverAddresses = dsNetServers.failoverAddresses_;
            if (failoverAddresses.size() == 0) {
              continue;
            }
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Checking for given host[port] '" + connUrl
                      + "' in the available server list: " + failoverAddresses);
            }
            if (failoverAddresses.containsKey(connUrl)) {
              this.failoverQueryInfo_ = dsNetServers;
              break;
            }
            // also try with connection URL having server/address other way
            else {
              if (this.connUrl2 == null && this.connServer != null) {
                try {
                  if (serverAddr == null) {
                    serverAddr = java.net.InetAddress.getByName(
                        this.connServer);
                  }
                  if (Character.isLetter(this.connServer.charAt(0))) {
                    // use IP address in case current server is a name
                    this.connUrl2 = getServerUrl(serverAddr.getHostAddress(),
                        port[0]);
                  }
                  else {
                    // else use host name
                    String serverHost = serverAddr.getCanonicalHostName();
                    if (serverHost != null && !serverHost.equals(
                        this.connServer)) {
                      this.connUrl2 = getServerUrl(serverHost, port[0]);
                    }
                  }
                } catch (java.net.UnknownHostException ignored) {
                }
              }
              if (this.connUrl2 != null) {
                if (failoverAddresses.containsKey(this.connUrl2)) {
                  this.failoverQueryInfo_ = dsNetServers;
                  break;
                }
              }
            }
          }
          String preferredServerHost;
          if (this.failoverQueryInfo_ == null) {
            // new DS?
            this.failoverQueryInfo_ = new DSConnectionInfo();
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "Getting new DS information for '" + connUrl + "'");
            }
            // get the full server list from server/locator that will be used for
            // failover or load-balancing
            preferredServerHost = getPreferredServer(connUrl, null, true, port,
                incomingProps);
            allDSQueryAddrs_.addLast(this.failoverQueryInfo_);
            // simplisitic policy of removing the first one in the list once
            // this fills up to maximum allowed
            if (allDSQueryAddrs_.size() > MAX_CACHED_DISTRIBUTED_SYSTEMS) {
              allDSQueryAddrs_.removeFirst();
            }
          }
          else {
            preferredServerHost = getPreferredServer(connUrl, null, false, port,
                incomingProps);
          }
          if (preferredServerHost == null) {
            if (SanityManager.TraceClientStatementHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "For host[port] '" + connUrl + "' got no preferred host[port]");
            }
            preferredServerHost = serverName;
          }
          final String prefUrl = getServerUrl(preferredServerHost, port[0]);
          if (this.failoverQueryInfo_.failoverAddresses_.get(prefUrl)
              == ServerType.LOCATOR) {
            // set a warning about using stand-alone locator but allow the
            // connection so that it will continue to work for many VTI queries
            // [sumedh] now throwing an exception; users can explicitly set the
            // "load-balance" property to false if they want to establish
            // connection to locator itself for some reason
            throw new SqlException(logWriter, new ClientMessageId(
                SQLState.COMMUNICATION_ERROR), "Cannot use stand-alone "
                + "locator '" + prefUrl + "' for data connection after "
                + "exhausting all servers: "
                + this.failoverQueryInfo_.failoverAddresses_.keySet());
          }
          if (SanityManager.TraceClientStatementHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "For host[port] '" + connUrl + "' using preferred host[port] '"
                    + prefUrl + "'");
          }
          return preferredServerHost;
        }
      } catch (SQLException sqle) {
        throw new SqlException(sqle);
      }
    }

    private String failover(final String thisUrl,
        final java.util.Set failedUrls, FailoverStatus status,
        final int[] newPort) throws SqlException {
      // expect a non-null failedUrls set
      assert failedUrls != null;

      try {
        if (SanityManager.TraceClientStatementHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA, "Trying "
            + (status != FailoverStatus.RETRY ? "failover" : "retry")
            + " for failed server '" + thisUrl + "'");
        }
        // initialize the failover address list in the first iteration
        synchronized (staticSync_) {
          if (this.failoverQueryInfo_ == null) {
            // cannot go further since no failover information is available
            return null;
          }
          if (status == FailoverStatus.NEW_SERVER) {
            failedUrls.add(thisUrl);
          }
          String preferredServerHost = getPreferredServer(thisUrl, failedUrls,
              true, newPort, null /* null indicating this object */
              /* has all required properties */);
          if (SanityManager.TraceClientStatementHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Trying failover from server '" + thisUrl + "' to '"
                    + getServerUrl(preferredServerHost, newPort[0]) + "'");
          }
          return preferredServerHost;
        }
      } catch (SQLException sqle) {
        throw new SqlException(sqle);
      }
    }

    protected final java.util.Set handleFailover(java.util.Set failedUrls,
        SqlException ex, FailoverStatus status) throws SqlException {
      final String thisUrl = getServerLocation();
      if (SanityManager.TraceClientStatementHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA, "server '"
            + thisUrl + "' failed for operation; trying failover");
      }
      if (failedUrls == null) {
        failedUrls = new java.util.HashSet();
      }
      // indicate that this is a failover retry that will enable setting
      // the posDup flag on server for next statement that will be executed
      this.statementFailover_ = true;
      createNewAgentOrReset(this.netAgent_.logWriter_, this.loginTimeout_,
          thisUrl, this.netAgent_.server_, new int[] { this.netAgent_.port_ },
          this.clientSSLMode_, failedUrls, ex, status);
      // reset the statement failover flag for the connection to false;
      // can be safely cleared since the handshake has already sent the flag
      this.statementFailover_ = false;
      // if failover successful then mark connection as open
      markOpen("handleFailover");
      if (SanityManager.TraceClientStatementHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "failover for server '" + thisUrl + "' complete for operation");
      }
      return failedUrls;
    }

    public final java.util.Set handleFailover(
        java.util.Set failedUrls, SqlException sqle)
        throws SqlException {
      final NetAgent netAgent = this.netAgent_;
      if (SanityManager.TraceClientStatementHA) {
        StringPrintWriter pw;
        if (SanityManager.TraceClientHA) {
          pw = new StringPrintWriter();
          SharedUtils.printStackTrace(sqle, pw, true);
        }
        else {
          pw = null;
        }
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            (this.loadBalance_ ? "Handling" : "Ignoring")
                + " failover for SQLState " + sqle.getSQLState()
                + " loadBalance=" + this.loadBalance_ + " for connection to "
                + netAgent.getServerLocation()
                + (pw != null ? ": " + pw.toString() : ""));
      }
      try {
        final FailoverStatus status;
        if (this.loadBalance_
            // don't failover to get SQLException message itself
            && (netAgent == null || !netAgent.forSqlException_)
            && (status = doFailoverOnException(sqle.getSQLState(),
                sqle.getErrorCode(), sqle)) != FailoverStatus.NONE) {
          return handleFailover(failedUrls, sqle, status);
        }
        else {
          throw sqle;
        }
      } catch (DisconnectException de) {
        // explicitly invoke disconnectEvent since it has been disabled
        // before the invocation of methods that allow for failover
        if (netAgent != null && netAgent.disableDisconnectEvent_
            && !netAgent.forSqlException_) {
          netAgent.disconnectEvent();
        }
        throw de;
      }
    }

    @Override
    public final FailoverStatus doFailoverOnException(final String sqlState,
        final int errorCode, final Exception thrownEx) {
      return getFailoverStatus(sqlState, errorCode, thrownEx);
    }

    public static FailoverStatus getFailoverStatus(final String sqlState,
        final int errorCode, final Exception thrownEx) {
      if (SanityManager.TraceClientStatementHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "Checking if failover required for SQLState " + sqlState);
      }
      if (SQLState.GFXD_NODE_SHUTDOWN_PREFIX.equals(sqlState)
          || SQLState.GFXD_NODE_BUCKET_MOVED_PREFIX.equals(sqlState)) {
        if (SanityManager.TraceClientStatementHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Need to do retry to same server for SQLState " + sqlState);
        }
        return FailoverStatus.RETRY;
      }
      // for 08001 we have to, unfortunately, resort to string search to
      // determine if failover makes sense or it is due to some problem
      // with authentication or invalid properties (see #43680)
      else if ("08001".equals(sqlState)) {
        String exceptionMessage = thrownEx.getMessage();
        if (exceptionMessage != null
            // cater to CONNECT_UNABLE_TO_CONNECT_TO_SERVER
            && (exceptionMessage.indexOf("rror") > 0
                // cater to CONNECT_SOCKET_EXCEPTION
                || exceptionMessage.indexOf("xception") > 0
                // cater to CONNECT_UNABLE_TO_OPEN_SOCKET_STREAM
                || exceptionMessage.indexOf("ocket") > 0)) {
          if (SanityManager.TraceClientStatementHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Need to do failover to a new server for SQLState " + sqlState);
          }
          return FailoverStatus.NEW_SERVER;
        }
      }
      // for 08004 we have to, unfortunately, resort to string search to
      // determine if failover makes sense or it is due to some problem
      // with authentication
      else if ("08004".equals(sqlState)) {
        String exceptionMessage = thrownEx.getMessage();
        if (exceptionMessage != null
            && (exceptionMessage = exceptionMessage.toLowerCase()).length() > 0
            // cater to connection refused errors
            && exceptionMessage.contains("connection refused")) {
          return FailoverStatus.NEW_SERVER;
        }
      }
      else if (failoverSQLStates.contains(sqlState)) {
        if (SanityManager.TraceClientStatementHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Need to do failover to a new server for SQLState " + sqlState);
        }
        return FailoverStatus.NEW_SERVER;
      }
      return FailoverStatus.NONE;
    }

    public final String getServerLocation() {
      final NetAgent netAgent = this.netAgent_;
      return netAgent != null ? netAgent.getServerLocation() : null;
    }

    final String getLocatorUrl(String thisUrl) {
      final DSConnectionInfo dsInfo = this.failoverQueryInfo_;
      final NetConnection locateConn;
      if (dsInfo != null && (locateConn = dsInfo.locateConn_) != null) {
        return locateConn.getServerLocation();
      }
      return (thisUrl != null ? thisUrl : getServerLocation());
    }

    public final String toString() {
      return "NetConnection@"
          + Integer.toHexString(System.identityHashCode(this)) + ",agent: "
          + this.netAgent_;
    }

    public final void setTimeout(int loginTimeout) throws SQLException {
      try {
        this.netAgent_.socket_.setSoTimeout(loginTimeout * 1000);
      } catch (java.net.SocketException e) {
        throw new SqlException(this.netAgent_.logWriter_, 
            new ClientMessageId(SQLState.SOCKET_EXCEPTION),
            e.getMessage(), e).getSQLException(this.netAgent_);
      }
    }

    public final int getTimeout() throws SQLException {
      try {
        return (this.netAgent_.socket_.getSoTimeout() / 1000);
      } catch (java.net.SocketException e) {
        throw new SqlException(this.netAgent_.logWriter_, 
            new ClientMessageId(SQLState.SOCKET_EXCEPTION),
            e.getMessage(), e).getSQLException(this.netAgent_);
      }
    }

    public final void setSendBufferSize(int size) throws SQLException {
      try {
        this.netAgent_.socket_.setSendBufferSize(size);
      } catch (java.net.SocketException e) {
        throw new SqlException(this.netAgent_.logWriter_, 
            new ClientMessageId(SQLState.SOCKET_EXCEPTION),
            e.getMessage(), e).getSQLException(this.netAgent_);
      }
    }

    public final int getSendBufferSize() throws SQLException {
      try {
        return this.netAgent_.socket_.getSendBufferSize();
      } catch (java.net.SocketException e) {
        throw new SqlException(this.netAgent_.logWriter_, 
            new ClientMessageId(SQLState.SOCKET_EXCEPTION),
            e.getMessage(), e).getSQLException(this.netAgent_);
      }
    }

    protected final int getCachedIsolation() {
      final int isolation = this.isolation_;
      if (isolation != TRANSACTION_UNKNOWN) {
        return isolation;
      }
      else {
        return this.defaultIsolation_;
      }
    }

    public final NetAgent createNewAgentOrReset(
        com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter,
        int loginTimeout, String thisUrl, String serverName, int[] port,
        int clientSSLMode, java.util.Set failedUrls,
        SqlException failoverEx, FailoverStatus status) throws SqlException {

      // carry over the isolation level and auto commit of parent connection
      final int isolation = getCachedIsolation();
      final boolean autoCommit = autoCommit_;
      // this is the retry loop trying to establish connection to a new server
      for (;;) {
        if (failoverEx != null) {
          if (failedUrls == null) {
            failedUrls = new java.util.HashSet();
          }
          final NetAgent agent = this.netAgent_;
          // don't failover if we can retry ("X0Z01" SQLState)
          if (status == FailoverStatus.NEW_SERVER) {
            // don't attempt failover if we are in the middle of a transaction
            // allow failover for the case of initial connection create
            if (isolation == TRANSACTION_NONE || agent == null) {
              serverName = failover(thisUrl, failedUrls, status, port);
            }
            else {
              if (SanityManager.TraceClientStatementHA) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                    "Ignoring failover for isolation " + isolation);
              }
              // convert server failure exception type to "40XD0" so application
              // can handle as required
              throw new SqlException(agent != null ? agent.logWriter_ : null,
                  new ClientMessageId(SQLState.DATA_CONTAINER_CLOSED),
                  new Object[] { thisUrl, "" }, failoverEx);
            }
          }
          else if (agent != null) {
            return agent;
          }
          if (serverName == null) {
            final java.util.HashSet allServers =
              new java.util.HashSet();
            if (this.failoverQueryInfo_ != null) {
              allServers.addAll(this.failoverQueryInfo_.failoverAddresses_
                  .keySet());
            }
            allServers.add(thisUrl);

            // for AUTH exceptions throw that back, else wrap in a generic
            // communication error
            if ("08004".equals(failoverEx.getSQLState())) {
              throw failoverEx;
            }
            // clear the failedServersRetryCounts cached map
            this.failedServersRetryCounts = null;
            throw new DisconnectException(null, new ClientMessageId(
                SQLState.COMMUNICATION_ERROR), "Failed after trying all "
                    + "available servers: " + allServers
                    + ", for control host[port]: " + getLocatorUrl(thisUrl)
                    + getAlternateNetServers(), failoverEx);
          }
          this.serverNameIP_ = serverName;
          this.portNumber_ = port[0];
          thisUrl = getServerUrl(this.serverNameIP_, this.portNumber_);
        }

        if (serverName == null) {
          throw new DisconnectException(this.netAgent_, new ClientMessageId(
              SQLState.CONNECT_REQUIRED_PROPERTY_NOT_SET), "serverName");
        }
        try {
          boolean setCurrentSchemaCalled = false;
          // if no agent created yet, then create a new one
          final NetAgent agent;
          if (this.netAgent_ == null) {
            final SqlException openEx;
            agent = new NetAgent(this, logWriter, loginTimeout,
                serverName, port[0], clientSSLMode);
            if ((openEx = agent.exceptionOpeningSocket_) != null) {
              try {
                agent.close_();
              } catch (Throwable t) {
                // deliberately ignored
              }
              throw openEx;
            }
          }
          else {
            // if an agent already present, reconnect it and change the socket
            // inside the agent itself since the agent will be cached all over
            //resetConnection(logWriter);
            clearWarningsX();
            final String failedServer = this.netAgent_.getServerLocation();
            if (this.netAgent_.reconnectAgent(logWriter, this.loginTimeout_,
                serverName, port[0])) {
              // clear the LOB locator procedures on failover (#43253)
              if (this.lobProcs != null) {
                // set the failed server field on the old lobProcs object
                // so that any open LOBs will throw an GFXD_NODE_SHUTDOWN
                // exception in case of an invalid locator on server
                this.lobProcs.failedServer = failedServer;
                this.lobProcs = null;
              }

              // do handshake with credentials etc.
              final String password = getDeferredResetPassword();
              flowConnect(password, this.securityMechanism_, false);

              setCurrentSchema();
              setCurrentSchemaCalled = true;
              // reset any statements etc
              completeReset(false, true, false);
            }
            agent = this.netAgent_;
          }
          // set isolation and auto commit explicitly to original if required
          if (isolation != getCachedIsolation()) {
            setTransactionIsolationX(isolation);
          }
          if (autoCommit != this.autoCommit_) {
            setAutoCommitX(autoCommit);
          }
          if (!setCurrentSchemaCalled) {
            setCurrentSchema();
          }
          if (SanityManager.TraceClientStatementHA) {
            final java.net.Socket s = agent.socket_;
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Agent now has local address[port] as '" + getServerUrl(
                    String.valueOf(s.getLocalAddress()), s.getLocalPort())
                    + "', remote address[port] as '" + getAgentString(agent)
                    + "' @" + hashCode());
          }
          return agent;
        } catch (SqlException ex) {
          if (SanityManager.TraceClientStatementHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Received SqlException when establishing connection"
                    + (this.netAgent_ != null ? (" (current agent: "
                        + getAgentString(this.netAgent_) + ')') : "")
                        + ": " + ex, (SanityManager.TraceClientHA ? ex : null));
          }
          final FailoverStatus s;
          if ((s = doFailoverOnException(ex.getSQLState(),
              ex.getErrorCode(), ex)) != FailoverStatus.NONE) {
            failoverEx = ex;
            status = s;
            thisUrl = getServerUrl(serverName, port[0]);
          }
          else {
            throw ex;
          }
        }
      }
    }

    private final String getAgentString(final NetAgent agent) {
      final java.net.Socket s;
      if (agent != null && (s = agent.socket_) != null) {
        return getServerUrl(String.valueOf(s.getInetAddress()), s.getPort());
      }
      return "null";
    }

    final void throwDisconnectException(Throwable cause)
        throws DisconnectException {
      final NetAgent netAgent = this.netAgent_;
      if (netAgent == null) {
        throw new DisconnectException(null, new ClientMessageId(
            SQLState.COMMUNICATION_ERROR), cause.getMessage(), cause);
      }
      else {
        try {
          netAgent.close_();
        } catch (Throwable t) {
          // deliberately ignored
        }
        netAgent.throwCommunicationsFailure(cause);
      }
    }

    protected static final SanityManager.PrintWriterFactory pwFactory =
        new SanityManager.PrintWriterFactory() {
      public final java.io.PrintWriter newPrintWriter(String file,
          boolean appendToFile) {
        try {
          return com.pivotal.gemfirexd.internal.client.am.LogWriter
              .getPrintWriter(file, appendToFile);
        } catch (SqlException e) {
          throw new RuntimeException(e.getCause());
        }
      }
    };

    static String gfxdLogFileNS;
    protected final com.pivotal.gemfirexd.internal.client.am.Agent newAgent_(
        final com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter,
        final int loginTimeout, String serverName, final int portNumber,
        final int clientSSLMode,
        final Properties incomingProps) throws SqlException {
      final int[] port = new int[] { portNumber };
      if (this.loadBalance_) {
        java.net.InetAddress serverAddr = null;
        // if server name is not fully qualified then try to find it since
        // the server list from locator will be fully qualified
        if (serverName.indexOf('.') < 0 && serverName.indexOf(':') < 0) {
          try {
            serverAddr = java.net.InetAddress.getByName(serverName);
            serverName = serverAddr.getCanonicalHostName();
          } catch (java.net.UnknownHostException ignore) {
          }
        }
        if (this.connUrl != null) {
          SanityManager.THROWASSERT("unexpected non-null connection host:port "
              + this.connUrl);
        }
        this.connUrl = getServerUrl(serverName, portNumber);
        this.connServer = serverName;
        if (SanityManager.TraceClientStatementHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "Trying to obtain a load-balanced connection for given "
                  + "host[port] '" + connUrl + "'");
        }
        serverName = preConnect(serverName, serverAddr, port, incomingProps,
            logWriter);
        this.serverNameIP_ = serverName;
        this.portNumber_ = port[0];
        if (SanityManager.TraceClientStatementHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "For given host[port] '" + connUrl + "' obtained a load-balanced "
                  + "connection '" + getServerUrl(serverName, port[0]) + "'");
        }
      }
      else {
        if (this.connUrl != null) {
          SanityManager.THROWASSERT("unexpected non-null connection host:port "
              + this.connUrl);
        }
        this.connUrl = getServerUrl(serverName, portNumber);
      }
      if (logWriter == null) {
        ClientService.initClientLogger(incomingProps, null, Level.CONFIG);
      } else {
        ClientService.initClientLogger(incomingProps,
            logWriter.getPrintWriter(), logWriter.getLogLevel());
      }
      String singleHopEnabled = incomingProps == null ? null : incomingProps
          .getProperty(ClientAttribute.SINGLE_HOP_ENABLED);
      if (singleHopEnabled != null) {
        this.singleHopEnabled_ = "true".equalsIgnoreCase(singleHopEnabled);
      }
      // set pre GFXD 1.3.0.2 hashing scheme for single-hop if specified
      if (this.singleHopEnabled_) {
        String pre1302Hashing = ClientBaseDataSource
            .readSystemProperty(ClientAttribute.GFXD_USE_PRE1302_HASHCODE);
        if (pre1302Hashing != null) {
          ResolverUtils.reset();
          if (Boolean.parseBoolean(pre1302Hashing)) {
            ResolverUtils.setUsePre1302Hashing(true);
          }
          else {
            ResolverUtils.setUseGFXD1302Hashing(true);
          }
        }
      }

      this.connProps = incomingProps;
      return createNewAgentOrReset(logWriter, loginTimeout, connUrl, serverName,
          port, clientSSLMode, null, null, FailoverStatus.NONE);
    /* (original code)
    protected com.pivotal.gemfirexd.internal.client.am.Agent newAgent_(com.pivotal.gemfirexd.internal.client.am.LogWriter logWriter, int loginTimeout, String serverName, int portNumber, int clientSSLMode)
            throws SqlException {
        return new NetAgent(this,
                (NetLogWriter) logWriter,
                loginTimeout,
                serverName,
                portNumber,
                clientSSLMode);
    */
// GemStone changes END
    }


    protected Statement newStatement_(int type, int concurrency, int holdability) throws SqlException {
        return new NetStatement(netAgent_, this, type, concurrency, holdability).statement_;
    }

    protected void resetStatement_(Statement statement, int type, int concurrency, int holdability) throws SqlException {
        ((NetStatement) statement.materialStatement_).resetNetStatement(netAgent_, this, type, concurrency, holdability);
    }

    protected PreparedStatement newPositionedUpdatePreparedStatement_(String sql,
                                                                      com.pivotal.gemfirexd.internal.client.am.Section section) throws SqlException {
        //passing the pooledConnection_ object which will be used to raise 
        //StatementEvents to the PooledConnection
        return new NetPreparedStatement(netAgent_, this, sql, section,pooledConnection_).preparedStatement_;
    }

    protected PreparedStatement newPreparedStatement_(String sql, int type, int concurrency, int holdability, int autoGeneratedKeys, String[] columnNames,
            int[] columnIndexes) throws SqlException {
        
        //passing the pooledConnection_ object which will be used to raise 
        //StatementEvents to the PooledConnection
        return new NetPreparedStatement(netAgent_, this, sql, type, concurrency, holdability, autoGeneratedKeys, columnNames,
                columnIndexes, pooledConnection_).preparedStatement_;
    }

    protected void resetPreparedStatement_(PreparedStatement ps,
                                           String sql,
                                           int resultSetType,
                                           int resultSetConcurrency,
                                           int resultSetHoldability,
                                           int autoGeneratedKeys,
                                           String[] columnNames,
                                           int[] columnIndexes) throws SqlException {
        ((NetPreparedStatement) ps.materialPreparedStatement_).resetNetPreparedStatement(netAgent_, this, sql, resultSetType, resultSetConcurrency, 
                resultSetHoldability, autoGeneratedKeys, columnNames, columnIndexes);
    }


    protected CallableStatement newCallableStatement_(String sql, int type, int concurrency, int holdability) throws SqlException {
        //passing the pooledConnection_ object which will be used to raise 
        //StatementEvents to the PooledConnection
        return new NetCallableStatement(netAgent_, this, sql, type, concurrency, holdability,pooledConnection_).callableStatement_;
    }

    protected void resetCallableStatement_(CallableStatement cs,
                                           String sql,
                                           int resultSetType,
                                           int resultSetConcurrency,
                                           int resultSetHoldability) throws SqlException {
        ((NetCallableStatement) cs.materialCallableStatement_).resetNetCallableStatement(netAgent_, this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }


    protected DatabaseMetaData newDatabaseMetaData_() {
            return ClientDRDADriver.getFactory().newNetDatabaseMetaData(netAgent_, this);
    }

    //-------------------private helper methods--------------------------------

    private void checkDatabaseName() throws SqlException {
        // netAgent_.logWriter may not be initialized yet
        if (databaseName_ == null) {
// GemStone changes BEGIN
            databaseName_ = "";
            /* (original code)
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.CONNECT_REQUIRED_PROPERTY_NOT_SET),
                "databaseName");
            */
// GemStone changes END
        }
    }

    private void checkUserLength(String user) throws SqlException {
        int usridLength = user.length();
        if ((usridLength == 0) || (usridLength > NetConfiguration.USRID_MAXSIZE)) {
            throw new SqlException(netAgent_.logWriter_, 
                new ClientMessageId(SQLState.CONNECT_USERID_LENGTH_OUT_OF_RANGE),
                new Integer(usridLength), 
                new Integer(NetConfiguration.USRID_MAXSIZE));
        }
    }

    private void checkPasswordLength(String password) throws SqlException {
        int passwordLength = password.length();
        if ((passwordLength == 0) || (passwordLength > NetConfiguration.PASSWORD_MAXSIZE)) {
            throw new SqlException(netAgent_.logWriter_,
                new ClientMessageId(SQLState.CONNECT_PASSWORD_LENGTH_OUT_OF_RANGE),
                new Integer(passwordLength),
                new Integer(NetConfiguration.PASSWORD_MAXSIZE));
        }
    }

    private void checkUser(String user) throws SqlException {
        if (user == null) {
            throw new SqlException(netAgent_.logWriter_, 
                new ClientMessageId(SQLState.CONNECT_USERID_ISNULL));
        }
        checkUserLength(user);
    }

    private void checkUserPassword(String user, String password) throws SqlException {
        checkUser(user);
        if (password == null) {
            throw new SqlException(netAgent_.logWriter_, 
                new ClientMessageId(SQLState.CONNECT_PASSWORD_ISNULL));
        }
        checkPasswordLength(password);
    }


    // Determine if a security mechanism is supported by
    // the security manager used for the connection.
    // An exception is thrown if the security mechanism is not supported
    // by the secmgr.
    private void checkSecmgrForSecmecSupport(int securityMechanism) throws SqlException {
        boolean secmecSupported = false;
        int[] supportedSecmecs = null;

        // Point to a list (array) of supported security mechanisms.
        supportedSecmecs = NetConfiguration.SECMGR_SECMECS;

        // check to see if the security mechanism is on the supported list.
        for (int i = 0; (i < supportedSecmecs.length) && (!secmecSupported); i++) {
            if (supportedSecmecs[i] == securityMechanism) {
                secmecSupported = true;
            }
        }

        // throw an exception if not supported (not on list).
        if (!secmecSupported) {
            throw new SqlException(agent_.logWriter_, 
                new ClientMessageId(SQLState.SECMECH_NOT_SUPPORTED),
                new Integer(securityMechanism));
        }
    }

    // If secchkcd is not 0, map to SqlException
    // according to the secchkcd received.
    private SqlException mapSecchkcd(int secchkcd) {
        if (secchkcd == CodePoint.SECCHKCD_00) {
            return null;
        }

        // the net driver will not support new password at this time.
        // Here is the message for -30082 (STATE "08001"):
        //    Attempt to establish connection failed with security
        //    reason {0} {1} +  reason-code + reason-string.
        switch (secchkcd) {
        case CodePoint.SECCHKCD_01:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_SECMECH_NOT_SUPPORTED));
        case CodePoint.SECCHKCD_10:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_PASSWORD_MISSING));
        case CodePoint.SECCHKCD_12:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_USERID_MISSING));
        case CodePoint.SECCHKCD_13:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                // GemStone changes BEGIN
                /*(original code) msgutil.getTextMessage(MessageId.CONN_USERID_OR_PASSWORD_INVALID));*/
                msgutil.getTextMessage(MessageId.CONN_USERID_OR_PASSWORD_INVALID, this.user_));
                // GemStone changes END
        case CodePoint.SECCHKCD_14:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_USERID_REVOKED));
        case CodePoint.SECCHKCD_15:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_NEW_PASSWORD_INVALID));
        case CodePoint.SECCHKCD_0A:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_SECSVC_NONRETRYABLE_ERR));
        case CodePoint.SECCHKCD_0B:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_SECTKN_MISSING_OR_INVALID));
        case CodePoint.SECCHKCD_0E:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_PASSWORD_EXPIRED));
        case CodePoint.SECCHKCD_0F:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                // GemStone changes BEGIN
                /*(original code) msgutil.getTextMessage(MessageId.CONN_USERID_OR_PASSWORD_INVALID));*/
                msgutil.getTextMessage(MessageId.CONN_USERID_OR_PASSWORD_INVALID, this.user_));
                // GemStone changes END
        default:  // ERROR SVRCOD
            return new SqlException(agent_.logWriter_,
                new ClientMessageId(SQLState.NET_CONNECT_AUTH_FAILED),
                msgutil.getTextMessage(MessageId.CONN_NOT_SPECIFIED));
        }
    }

    // Construct the correlation token.
    // The crrtkn has the following format.
    //
    // <Almost IP address>.<local port number><current time in millis>
    // |                   | |               ||                  |
    // +----+--------------+ +-----+---------++---------+--------+
    //      |                      |                |
    //    8 bytes               4 bytes         6 bytes
    // Total lengtho of 19 bytes.
    //
    // 1 char for each 1/2 byte in the IP address.
    // If the first character of the <IP address> or <port number>
    // starts with '0' thru '9', it will be mapped to 'G' thru 'P'.
    // Reason for mapping the IP address is in order to use the crrtkn as the LUWID when using SNA in a hop site.
    protected void constructCrrtkn() throws SqlException {
        byte[] localAddressBytes = null;
        long time = 0;
        int num = 0;
        int halfByte = 0;
        int i = 0;
        int j = 0;

        // allocate the crrtkn array.
        if (crrtkn_ == null) {
            crrtkn_ = new byte[19];
        } else {
            java.util.Arrays.fill(crrtkn_, (byte) 0);
        }

        localAddressBytes = netAgent_.socket_.getLocalAddress().getAddress();

        // IP addresses are returned in a 4 byte array.
        // Obtain the character representation of each half byte.
        for (i = 0, j = 0; i < 4; i++, j += 2) {

            // since a byte is signed in java, convert any negative
            // numbers to positive before shifting.
            num = localAddressBytes[i] < 0 ? localAddressBytes[i] + 256 : localAddressBytes[i];
            halfByte = (num >> 4) & 0x0f;

            // map 0 to G
            // The first digit of the IP address is is replaced by
            // the characters 'G' thro 'P'(in order to use the crrtkn as the LUWID when using
            // SNA in a hop site). For example, 0 is mapped to G, 1 is mapped H,etc.
            if (i == 0) {
                crrtkn_[j] = netAgent_.sourceCcsidManager_.numToSnaRequiredCrrtknChar_[halfByte];
            } else {
                crrtkn_[j] = netAgent_.sourceCcsidManager_.numToCharRepresentation_[halfByte];
            }

            halfByte = (num) & 0x0f;
            crrtkn_[j + 1] = netAgent_.sourceCcsidManager_.numToCharRepresentation_[halfByte];
        }

        // fill the '.' in between the IP address and the port number
        crrtkn_[8] = netAgent_.sourceCcsidManager_.dot_;

        // Port numbers have values which fit in 2 unsigned bytes.
        // Java returns port numbers in an int so the value is not negative.
        // Get the character representation by converting the
        // 4 low order half bytes to the character representation.
        num = netAgent_.socket_.getLocalPort();

        halfByte = (num >> 12) & 0x0f;
        crrtkn_[9] = netAgent_.sourceCcsidManager_.numToSnaRequiredCrrtknChar_[halfByte];
        halfByte = (num >> 8) & 0x0f;
        crrtkn_[10] = netAgent_.sourceCcsidManager_.numToCharRepresentation_[halfByte];
        halfByte = (num >> 4) & 0x0f;
        crrtkn_[11] = netAgent_.sourceCcsidManager_.numToCharRepresentation_[halfByte];
        halfByte = (num) & 0x0f;
        crrtkn_[12] = netAgent_.sourceCcsidManager_.numToCharRepresentation_[halfByte];

        // The final part of CRRTKN is a 6 byte binary number that makes the
        // crrtkn unique, which is usually the time stamp/process id.
        // If the new time stamp is the
        // same as one of the already created ones, then recreate the time stamp.
        time = System.currentTimeMillis();

        for (i = 0; i < 6; i++) {
            // store 6 bytes of 8 byte time into crrtkn
            crrtkn_[i + 13] = (byte) (time >>> (40 - (i * 8)));
        }
    }


    private void constructExtnam() throws SqlException {
        extnam_ = "derbydnc" + java.lang.Thread.currentThread().getName();
    }

    private void constructPrddta() throws SqlException {
        int prddtaLen = 1;

        if (prddta_ == null) {
            prddta_ = new byte[NetConfiguration.PRDDTA_MAXSIZE];
        } else {
            java.util.Arrays.fill(prddta_, (byte) 0);
        }

        for (int i = 0; i < NetConfiguration.PRDDTA_ACCT_SUFFIX_LEN_BYTE; i++) {
            prddta_[i] = netAgent_.sourceCcsidManager_.space_;
        }

// GemStone changes BEGIN
        // first write the magic bytes to indicate GFXD client >= 1.3, then the
        // version ordinal (hardcoded for now to avoid refactoring of Version
        //   in maint branch), then the additional properties
        final int magicLen = ClientSharedData.BYTES_PREFIX_CLIENT_VERSION_LENGTH;
        System.arraycopy(ClientSharedData.BYTES_PREFIX_CLIENT_VERSION,
            0, prddta_, prddtaLen, magicLen);
        prddtaLen += magicLen;

        // write the client version
        Version v = Version.CURRENT;
        SignedBinary.shortToBigEndianBytes(prddta_, prddtaLen, v.ordinal());
        prddtaLen += 2;

        int propFlags = 0x0;
        if (this.skipLocks_) {
          propFlags |= CodePoint.SKIP_LOCKS;
        }
        SignedBinary.intToBigEndianBytes(prddta_, prddtaLen, propFlags);
        prddtaLen += 4;

        prddtaLen = netAgent_.sourceCcsidManager_.convertFromUCS2(NetConfiguration.PRDID,
                prddta_,
                prddtaLen,
                netAgent_);

        prddtaLen = netAgent_.sourceCcsidManager_.convertFromUCS2(NetConfiguration.PRDDTA_PLATFORM_ID,
                prddta_,
                prddtaLen,
                netAgent_);

        int extnamTruncateLength = Utils.min(extnam_.length(), NetConfiguration.PRDDTA_APPL_ID_FIXED_LEN);
        netAgent_.sourceCcsidManager_.convertFromUCS2(extnam_.substring(0, extnamTruncateLength),
                prddta_,
                prddtaLen,
                netAgent_);
        prddtaLen += NetConfiguration.PRDDTA_APPL_ID_FIXED_LEN;

        if (user_ != null) {
            int userTruncateLength = Utils.min(user_.length(), NetConfiguration.PRDDTA_USER_ID_FIXED_LEN);
            netAgent_.sourceCcsidManager_.convertFromUCS2(user_.substring(0, userTruncateLength),
                    prddta_,
                    prddtaLen,
                    netAgent_);
        }

        prddtaLen += NetConfiguration.PRDDTA_USER_ID_FIXED_LEN;

        prddta_[NetConfiguration.PRDDTA_ACCT_SUFFIX_LEN_BYTE] = 0;
        prddtaLen++;
        // the length byte value does not include itself.
        prddta_[NetConfiguration.PRDDTA_LEN_BYTE] = (byte) (prddtaLen - 1);
    }

    private void initializePublicKeyForEncryption() throws SqlException {
        if (encryptionManager_ == null) {
            encryptionManager_ = new EncryptionManager(agent_);
        }
        publicKey_ = encryptionManager_.obtainPublicKey();
    }

    // SECMEC_USRSSBPWD security mechanism - Generate a source (client) seed
    // to send to the target (application) server.
    private void initializeClientSeed() throws SqlException {
        if (encryptionManager_ == null) {
            encryptionManager_ = new EncryptionManager(
                                    agent_,
                                    EncryptionManager.SHA_1_DIGEST_ALGORITHM);
        }
        sourceSeed_ = encryptionManager_.generateSeed();
    }

    private byte[] encryptedPasswordForUSRENCPWD(String password) throws SqlException {
        return encryptionManager_.encryptData(netAgent_.sourceCcsidManager_.convertFromUCS2(password, netAgent_),
                NetConfiguration.SECMEC_USRENCPWD,
                netAgent_.sourceCcsidManager_.convertFromUCS2(user_, netAgent_),
                targetPublicKey_);
    }

    private byte[] encryptedUseridForEUSRIDPWD() throws SqlException {
        return encryptionManager_.encryptData(netAgent_.sourceCcsidManager_.convertFromUCS2(user_, netAgent_),
                NetConfiguration.SECMEC_EUSRIDPWD,
                targetPublicKey_,
                targetPublicKey_);
    }

    private byte[] encryptedPasswordForEUSRIDPWD(String password) throws SqlException {
        return encryptionManager_.encryptData(netAgent_.sourceCcsidManager_.convertFromUCS2(password, netAgent_),
                NetConfiguration.SECMEC_EUSRIDPWD,
                targetPublicKey_,
                targetPublicKey_);
    }

    private byte[] passwordSubstituteForUSRSSBPWD(String password) throws SqlException {
        String userName = user_;
        
        // Define which userName takes precedence - If we have a dataSource
        // available here, it is posible that the userName has been
        // overriden by some defined as part of the connection attributes
        // (see ClientBaseDataSource.updateDataSourceValues().
        // We need to use the right userName as strong password
        // substitution depends on the userName when the substitute
        // password is generated; if we were not using the right userName
        // then authentication would fail when regenerating the substitute
        // password on the engine server side, where userName as part of the
        // connection attributes would get used to authenticate the user.
        if (dataSource_ != null)
        {
            String dataSourceUserName = dataSource_.getUser();
            if (!dataSourceUserName.equals("") &&
                userName.equalsIgnoreCase(
                    dataSource_.propertyDefault_user) &&
                !dataSourceUserName.equalsIgnoreCase(
                    dataSource_.propertyDefault_user))
            {
                userName = dataSourceUserName;
            }
        }
        return encryptionManager_.substitutePassword(
                userName, password, sourceSeed_, targetSeed_);
    }

    // Methods to get the manager levels for Regression harness only.
    public int getSQLAM() {
        return netAgent_.targetSqlam_;
    }

    public int getAGENT() {
        return targetAgent_;
    }

    public int getCMNTCPIP() {
        return targetCmntcpip_;
    }

    public int getRDB() {
        return targetRdb_;
    }

    public int getSECMGR() {
        return targetSecmgr_;
    }

    public int getXAMGR() {
        return targetXamgr_;
    }

    public int getSYNCPTMGR() {
        return targetSyncptmgr_;
    }

    public int getRSYNCMGR() {
        return targetRsyncmgr_;
    }


    private char[] flipBits(char[] array) {
        for (int i = 0; i < array.length; i++) {
            array[i] ^= 0xff;
        }
        return array;
    }

    public void writeCommitSubstitute_() throws SqlException {
        netAgent_.connectionRequest_.writeCommitSubstitute(this);
    }

    public void readCommitSubstitute_() throws SqlException {
        netAgent_.connectionReply_.readCommitSubstitute(this);
    }

    public void writeLocalXAStart_() throws SqlException {
        netAgent_.connectionRequest_.writeLocalXAStart(this);
    }

    public void readLocalXAStart_() throws SqlException {
        netAgent_.connectionReply_.readLocalXAStart(this);
    }

    public void writeLocalXACommit_() throws SqlException {
        netAgent_.connectionRequest_.writeLocalXACommit(this);
    }

    public void readLocalXACommit_() throws SqlException {
        netAgent_.connectionReply_.readLocalXACommit(this);
    }

    public void writeLocalXARollback_() throws SqlException {
// GemStone changes BEGIN
      this.baseConnection = null;
      this.bucketRegionIds = null;
// GemStone changes END
        netAgent_.connectionRequest_.writeLocalXARollback(this);
    }

    public void readLocalXARollback_() throws SqlException {
        netAgent_.connectionReply_.readLocalXARollback(this);
    }

    public void writeLocalCommit_() throws SqlException {
        netAgent_.connectionRequest_.writeLocalCommit(this);
    }

    public void readLocalCommit_() throws SqlException {
        netAgent_.connectionReply_.readLocalCommit(this);
    }

    public void writeLocalRollback_() throws SqlException {
// GemStone changes BEGIN
      this.baseConnection = null;
      this.bucketRegionIds = null;
// GemStone changes END
        netAgent_.connectionRequest_.writeLocalRollback(this);
    }

    public void readLocalRollback_() throws SqlException {
        netAgent_.connectionReply_.readLocalRollback(this);
    }


    protected void markClosed_() {
    }

    protected boolean isGlobalPending_() {
        return false;
    }

    protected boolean doCloseStatementsOnClose_() {
        return closeStatementsOnClose;
    }

    /**
     * Check if the connection can be closed when there are uncommitted
     * operations.
     *
     * @return if this connection can be closed when it has uncommitted
     * operations, {@code true}; otherwise, {@code false}
     */
    protected boolean allowCloseInUOW_() {
        // We allow closing in unit of work in two cases:
        //
        //   1) if auto-commit is on, since then Connection.close() will cause
        //   a commit so we won't leave uncommitted changes around
        //
        //   2) if we're not allowed to commit or roll back the transaction via
        //   the connection object (because the it is part of an XA
        //   transaction). In that case, commit and rollback are performed via
        //   the XAResource, and it is therefore safe to close the connection.
        //
        // Otherwise, the transaction must be idle before a call to close() is
        // allowed.

        return autoCommit_ || !allowLocalCommitRollback_();
    }

    // Driver-specific determination if local COMMIT/ROLLBACK is allowed;
    // Allow local COMMIT/ROLLBACK only if we are not in an XA transaction
    protected boolean allowLocalCommitRollback_() {
       
    	if (getXAState() == XA_T0_NOT_ASSOCIATED) {
            return true;
        }
        return false;
    }

    public void setInputStream(java.io.InputStream inputStream) {
        netAgent_.setInputStream(inputStream);
    }

    public void setOutputStream(java.io.OutputStream outputStream) {
        netAgent_.setOutputStream(outputStream);
    }

    public java.io.InputStream getInputStream() {
        return netAgent_.getInputStream();
    }

    public java.io.OutputStream getOutputStream() {
        return netAgent_.getOutputStream();
    }


    public void writeTransactionStart(Statement statement) throws SqlException {
    }

    public void readTransactionStart() throws SqlException {
        super.readTransactionStart();
    }

    public void setIndoubtTransactions(java.util.Hashtable indoubtTransactions) {
        if (isXAConnection_) {
            if (indoubtTransactions_ != null) {
                indoubtTransactions_.clear();
            }
            indoubtTransactions_ = indoubtTransactions;
        }
    }

    protected void setReadOnlyTransactionFlag(boolean flag) {
        readOnlyTransaction_ = flag;
    }

    public com.pivotal.gemfirexd.internal.client.am.SectionManager newSectionManager
            (String collection,
             com.pivotal.gemfirexd.internal.client.am.Agent agent,
             String databaseName) {
        return new com.pivotal.gemfirexd.internal.client.am.SectionManager(collection, agent, databaseName);
    }

    protected int getSocketAndInputOutputStreams(String server, int port, int clientSSLMode) {
        try {
            netAgent_.socket_ = (java.net.Socket) java.security.AccessController.doPrivileged(new OpenSocketAction(server, port, clientSSLMode));
        } catch (java.security.PrivilegedActionException e) {
            Exception openSocketException = e.getException();
            if (netAgent_.loggingEnabled()) {
                netAgent_.logWriter_.tracepoint("[net]", 101, "Client Re-route: " + openSocketException.getClass().getName() + " : " + openSocketException.getMessage());
            }
            return -1;
        }

        try {
            netAgent_.rawSocketOutputStream_ = netAgent_.socket_.getOutputStream();
            netAgent_.rawSocketInputStream_ = netAgent_.socket_.getInputStream();
        } catch (java.io.IOException e) {
            if (netAgent_.loggingEnabled()) {
                netAgent_.logWriter_.tracepoint("[net]", 103, "Client Re-route: java.io.IOException " + e.getMessage());
            }
            try {
                netAgent_.socket_.close();
            } catch (java.io.IOException doNothing) {
            }
            return -1;
        }
        return 0;
    }

    protected int checkAlternateServerHasEqualOrHigherProductLevel(ProductLevel orgLvl, int orgServerType) {
        if (orgLvl == null && orgServerType == 0) {
            return 0;
        }
        ProductLevel alternateServerProductLvl =
                netAgent_.netConnection_.databaseMetaData_.productLevel_;
        boolean alternateServerIsEqualOrHigherToOriginalServer =
                (alternateServerProductLvl.greaterThanOrEqualTo
                (orgLvl.versionLevel_,
                        orgLvl.releaseLevel_,
                        orgLvl.modificationLevel_)) ? true : false;
        // write an entry to the trace
        if (!alternateServerIsEqualOrHigherToOriginalServer &&
                netAgent_.loggingEnabled()) {
            netAgent_.logWriter_.tracepoint("[net]",
                    99,
                    "Client Re-route failed because the alternate server is on a lower product level than the origianl server.");
        }
        return (alternateServerIsEqualOrHigherToOriginalServer) ? 0 : -1;
    }

    public boolean willAutoCommitGenerateFlow() {
        // this logic must be in sync with writeCommit() logic
        if (!autoCommit_) {
            return false;
        }
        if (!isXAConnection_) {
            return true;
        }
        boolean doCommit = false;
        int xaState = getXAState();

        
        if (xaState == XA_T0_NOT_ASSOCIATED) {
            doCommit = true;
        }

        return doCommit;
    }

    public int getSecurityMechanism() {
        return securityMechanism_;
    }

    public EncryptionManager getEncryptionManager() {
        return encryptionManager_;
    }

    public byte[] getTargetPublicKey() {
        return targetPublicKey_;
    }

    public String getProductID() {
        return targetSrvclsnm_;
    }

    public void doResetNow() throws SqlException {
        if (!resetConnectionAtFirstSql_) {
            return; // reset not needed
        }
        agent_.beginWriteChainOutsideUOW();
        agent_.flowOutsideUOW();
        agent_.endReadChain();
    }
    
	/**
	 * @return Returns the connectionNull.
	 */
	public boolean isConnectionNull() {
		return connectionNull;
	}
	/**
	 * @param connectionNull The connectionNull to set.
	 */
	public void setConnectionNull(boolean connectionNull) {
		this.connectionNull = connectionNull;
	}

    /**
     * Check whether the server has full support for the QRYCLSIMP
     * parameter in OPNQRY.
     *
     * @return true if QRYCLSIMP is fully supported
     */
    public final boolean serverSupportsQryclsimp() {
        NetDatabaseMetaData metadata =
            (NetDatabaseMetaData) databaseMetaData_;
        return metadata.serverSupportsQryclsimp();
    }

    
    public final boolean serverSupportsLayerBStreaming() {
        
        NetDatabaseMetaData metadata =
            (NetDatabaseMetaData) databaseMetaData_;
        
        return metadata.serverSupportsLayerBStreaming();

    }
    
    
    /**
     * Check whether the server supports session data caching
     * @return true session data caching is supported
     */
    protected final boolean supportsSessionDataCaching() {

        NetDatabaseMetaData metadata =
            (NetDatabaseMetaData) databaseMetaData_;

        return metadata.serverSupportsSessionDataCaching();
    }

    /**
     * Check whether the server supports UDTs
     * @return true if UDTs are supported
     */
    protected final boolean serverSupportsUDTs() {

        NetDatabaseMetaData metadata =
            (NetDatabaseMetaData) databaseMetaData_;

        return metadata.serverSupportsUDTs();
    }

    /**
     * Checks whether the server supports locators for large objects.
     *
     * @return {@code true} if LOB locators are supported.
     */
    protected final boolean serverSupportsLocators() {
        // Support for locators was added in the same version as layer B
        // streaming.
        return serverSupportsLayerBStreaming();
    }

    /**
     * closes underlying connection and associated resource.
     */
    synchronized public void close() throws SQLException {
        // call super.close*() to do the close*
        super.close();
        
        // if ODBC driver remove the connection from the list
        if (isODBCDriver) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "ODBC: Removing odbc connection from the connections list");
          }
          odbcConnectionList.remove(this);
        }
        
        if (!isXAConnection_)
            return;
        if (isOpen()) {
            return; // still open, return
        }
        if (xares_ != null) {
            xares_.removeXaresFromSameRMchain();
        }
    }
    
    /**
     * closes underlying connection and associated resource.
     */
    synchronized public void closeX() throws SQLException {
        // call super.close*() to do the close*
        super.closeX();
        if (!isXAConnection_)
            return;
        if (isOpen()) {
            return; // still open, return
        }
        if (xares_ != null) {
            xares_.removeXaresFromSameRMchain();
        }
    }
    
    /**
     * Invalidates connection but keeps socket open.
     */
    synchronized public void closeForReuse() throws SqlException {
        // call super.close*() to do the close*
        super.closeForReuse(closeStatementsOnClose);
        if (!isXAConnection_)
            return;
        if (isOpen()) {
            return; // still open, return
        }
        if (xares_ != null) {
            xares_.removeXaresFromSameRMchain();
        }
    }
    
    /**
     * closes resources connection will be not available 
     * for reuse.
     */
    synchronized public void closeResources() throws SQLException {
        // call super.close*() to do the close*
        super.closeResources();
        if (!isXAConnection_)
            return;
        
        if (isOpen()) {
            return; // still open, return
        }
        if (xares_ != null) {
            xares_.removeXaresFromSameRMchain();
        }
    }
    
    
    /**
     * Invokes write commit on NetXAConnection
     */
    protected void writeXACommit_() throws SqlException {
        xares_.netXAConn_.writeCommit();
    }
    
    /**
     * Invokes readCommit on NetXAConnection
     */
    protected void readXACommit_() throws SqlException {
        xares_.netXAConn_.readCommit();
    }
    
    /**
     * Invokes writeRollback on NetXAConnection
     */
    protected void writeXARollback_() throws SqlException {
        xares_.netXAConn_.writeRollback();
    }
    
    /**
     * Invokes writeRollback on NetXAConnection
     */
    protected void readXARollback_() throws SqlException {
            xares_.netXAConn_.readRollback();
    }
    
    
    protected void writeXATransactionStart(Statement statement) throws SqlException {
        xares_.netXAConn_.writeTransactionStart(statement);
    }
// GemStone changes BEGIN

  // added the code block below till the "GemStone changes END" marker

  /**
   * The collection of network server groups in all locators/servers used for
   * querying for a new server for failover. Each set in this list depicts a
   * different distributed system so for each locator/server detected to be in a
   * new distributed system, it is placed in a new Set in this list.
   */
  private static final java.util.LinkedList allDSQueryAddrs_ =
    new java.util.LinkedList();

  /**
   * The maximum number of DistributedSystems that will be tracked in
   * {@link #allDSQueryAddrs_} beyond which it will be clean up the older
   * ones.
   */
  private static final int MAX_CACHED_DISTRIBUTED_SYSTEMS = 2;

  /** used as global lock for failover/load-balancing stuff */
  private static final Object staticSync_ = new Object();

  /**
   * The pattern to extract addresses from the result of
   * GET_ALLSERVERS_AND_PREFSERVER2 procedure; format is:
   * 
   * host1/addr1[port1]{kind1},host2/addr2[port2]{kind2},...
   */
  public static final java.util.regex.Pattern addrPat_ = java.util.regex
      .Pattern.compile("([^,/]*)(/[^,\\[]+)?\\[([\\d]+)\\](\\{[^}]+\\})?");

  /**
   * The collection of network server groups in locators/servers for the
   * distributed system this connection is connecting to that is used for
   * querying for a new server in case of failover or load balancing.
   */
  private DSConnectionInfo failoverQueryInfo_;

  // In single hop a pool of connection is used internally to directly
  // hop to the server of interest. Base connection is the reference
  // to the top level connection on which application is working.
  private NetConnection baseConnection;

  /**
   * internal connection property to indicate that the connection is a control
   * connection to locator
   */
  static final String PROP_CONTROL_CONN = "gemfirexd._control_conn";

  /**
   * Simple structure to encapsulate the network-servers in a distributed
   * system, and statements to be used against locators/servers to obtain
   * load-balanced connections etc.
   * 
   * @author swale
   */
  public static final class DSConnectionInfo {

    /**
     * The set of locators/servers in this DistributedSystem that can be used
     * for finding the failover server in case the current one goes down.
     */
    java.util.LinkedHashMap failoverAddresses_;

    /**
     * Static Connection to a locator/server that can be used to fire
     * meta-queries to get global locator/server information.
     */
    NetConnection locateConn_;

    /**
     * Cached callable statement for GET_ALLSERVERS_AND_PREFSERVER2 procedure
     * call on the {@link #locateConn_} connection.
     */
    java.sql.CallableStatement allStmt_;

    /**
     * Cached callable statement for GET_PREFSERVER procedure call on the
     * {@link #locateConn_} connection.
     */
    java.sql.CallableStatement prefStmt_;

    /**
     * This map is used to look up for an already established connection
     * corresponding to a server url of the form host1[port].
     */
    private HashMap actualServerToConnectionMap_;

    /** default constructor */
    DSConnectionInfo() {
      this.failoverAddresses_ = new java.util.LinkedHashMap();
      this.actualServerToConnectionMap_ = new HashMap();
    }

    public HashMap getServerToConnMap() {
      return this.actualServerToConnectionMap_;
    }
    
    void clearControlConnection() {
      this.locateConn_ = null;
      this.allStmt_ = null;
      this.prefStmt_ = null;
    }

    public NetConnection getConnectionForThisServerURLFromBQ(String host,
        String port, com.pivotal.gemfirexd.internal.client.am.Connection conn)
        throws SQLException, SqlException {
      Properties props = conn.getConnectionProperties();
      final String serverURL = host + "[" + port + "]";
      if (SanityManager.TraceSingleHop) {
        SanityManager
            .DEBUG_PRINT(
                SanityManager.TRACE_SINGLE_HOP,
                "NetConnection::DSConnectionInfo::getConnectionForThisServerURLFromBQ fetching connection for serverURL: "
                    + serverURL);
      }
      BoundedLinkedQueue bqueue = (BoundedLinkedQueue)this.actualServerToConnectionMap_
          .get(serverURL);
      if (bqueue == null) {
        synchronized (this.actualServerToConnectionMap_) {
          bqueue = (BoundedLinkedQueue)this.actualServerToConnectionMap_
              .get(serverURL);
          if (bqueue == null) {
            ConnectionCreator c = new ConnectionCreator(host, port, props);
            bqueue = new BoundedLinkedQueue(SINGLE_HOP_MAX_CONN_PER_SERVER, c);
            if (SanityManager.TraceSingleHop) {
              SanityManager.DEBUG_PRINT(
                 SanityManager.TRACE_SINGLE_HOP,
                   "NetConnection::DSConnectionInfo::getConnectionForThisServerURLFromBQ put bounded queue: "
                   + bqueue + " against serverURL: " + serverURL);
            }
            this.actualServerToConnectionMap_.put(serverURL, bqueue);
          }
        }
      }
      try {
        NetConnection nc = (NetConnection)bqueue.createOrGetExisting();
        nc.setServerURL(serverURL);
        // setting the top level connection in the pool connection object
        nc.setBaseConnection(conn);
        return nc;
      } catch (InterruptedException e) {
        throw new SqlException(locateConn_.netAgent_.logWriter_,
            new ClientMessageId(SQLState.CONN_INTERRUPT), e);
      }
    }

    public void returnConnection(NetConnection conn) throws SqlException {
      String serverURL = conn.serverURL_;
      BoundedLinkedQueue q = (BoundedLinkedQueue)this.actualServerToConnectionMap_.get(serverURL);
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "NetConnection::return connection conn = " + conn + ", to q = " + q
                + " for serverURL = " + serverURL);
      }
      try {
        q.returnBack(conn);
      } catch (InterruptedException e) {
        throw new SqlException(locateConn_.netAgent_.logWriter_,
            new ClientMessageId(SQLState.CONN_INTERRUPT), e);
      }
    }

    public void removeConnection(NetConnection conn) throws InterruptedException {
      String serverURL = conn.serverURL_;
      BoundedLinkedQueue q = (BoundedLinkedQueue)this.actualServerToConnectionMap_
          .get(serverURL);
      q.removeConnection(conn);
    }
  }

  public DSConnectionInfo getDSConnectionInfo() {
    return this.failoverQueryInfo_;
  }

  public void setBaseConnection(
      com.pivotal.gemfirexd.internal.client.am.Connection conn) {
    this.baseConnection = (NetConnection)conn;
  }

  public void setServerURL(String serverURL) {
    if (this.serverURL_ == null) {
      this.serverURL_ = serverURL;
    }
  }

  private String serverURL_;

  public boolean gotException_;
  
  public static final class ConnectionCreator implements QueueObjectCreator {

    private final String serverhost;
    private final String port;
    private final Properties connProps;

    public ConnectionCreator(String host, String port, Properties props) {
      this.serverhost = host;
      this.port = port;
      if (props != null) {
        this.connProps = new Properties(props);
      }
      else {
        this.connProps = new Properties();
      }
      this.connProps.setProperty(ClientAttribute.LOAD_BALANCE, "false");
      this.connProps.setProperty(ClientAttribute.SINGLE_HOP_ENABLED, "false");
    }

    public Object create() throws SQLException {
      String connURL = com.pivotal.gemfirexd.internal.client.am.Configuration.jdbcDerbyNETProtocol()
          + this.serverhost + ':' + this.port;
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "NetConnection::ConnectionCreator::create serverhost: "
                + this.serverhost + " and port: " + this.port
                + " and properties: " + this.connProps);
      }
      Connection conn = java.sql.DriverManager.getConnection(connURL,
          this.connProps);
      ((NetConnection)conn).loadBalance_ = false;
      ((NetConnection)conn).singleHopEnabled_ = false;
      if (SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
            "NetConnection::ConnectionCreator::create made a new connection: "
                + conn + " and put in actualServerToConnectionMap_");
      }
      return conn;
    }
  }

  /**
   * Indicates the type of server i.e. locator or datastore/accessor or an
   * unresponsive (possibly failed) one.
   * 
   * @author swale
   */
  private static final class ServerType {

    /** Indicates that the server is a GemFireXD locator. */
    private static final ServerType LOCATOR = new ServerType("LOCATOR");

    /**
     * Indicates that the server is either a GemFireXD datastore or an accessor
     * that is still available.
     */
    private static final ServerType QUERY = new ServerType("QUERY");

    /**
     * Indicates that the server is either a GemFireXD datastore or an accessor
     * that has been noted to have become unresponsive and so should be given
     * the least priority during failover by other or new connections.
     */
    private static final ServerType UNRESPONSIVE = new ServerType(
        "UNRESPONSIVE");

    /**
     * A string representation of this server type for toString().
     */
    private final String name;

    private ServerType(final String name) {
      this.name = name;
    }

    public final String toString() {
      return this.name;
    }

    public final int hashCode() {
      return this.name.hashCode();
    }
  }

  public int getSavePointToRollbackTo() {
    // KN: TODO check the implementation of this specially keeping in mind the
    // single hop contexts
    return this.prevSuccessfulSeq;
  }

  public void setTXID(long memberIdOfTxid, long uniqIdOfTxId) {
    this.currTXID = new TxID(memberIdOfTxid, uniqIdOfTxId);
    if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "NetConnection.setTXID setting new txid to: " + this.currTXID);
    }
  }
  
  public TxID getTxID() {
    return this.currTXID;
  }

  public void setServerVersion(Version serverVersion) {
    this.serverVersion = serverVersion;
  }

  public final boolean isSnappyDRDAProtocol() {
    return this.snappyDRDAProtocol;
  }

  public void updateRegionInfoForCommit(int prid, int bid) {
    if (SanityManager.TraceClientHA | SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "NetConnection.updateRegionInfoForCommit called: baseconn = "
              + baseConnection + ", prid = " + prid + " bid = " + bid);
    }
    if (this.baseConnection != null) {
      if (this.baseConnection.bucketRegionIds == null) {
        this.baseConnection.bucketRegionIds = new HashMap<Integer, HashSet<Integer>>();
      }
      HashSet<Integer> set = this.baseConnection.bucketRegionIds.get(Integer
          .valueOf(prid));
      if (set == null) {
        set = new HashSet<Integer>();
        this.baseConnection.bucketRegionIds.put(Integer.valueOf(prid), set);
      }
      set.add(Integer.valueOf(bid));
    }
    else {
      throw new IllegalStateException(
          "NetConnection.updateRegionInfoForCommit is not "
              + "suppossed to be called for a top level connection");
    }
  }

  // prid --> set of bucket ids
  HashMap<Integer, HashSet<Integer>> bucketRegionIds;

// GemStone changes END
}
