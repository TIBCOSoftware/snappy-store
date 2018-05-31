/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTLongObjectHashMap;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineLOB;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EnginePreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineStatement;
import com.pivotal.gemfirexd.internal.iapi.jdbc.WrapperEngineBLOB;
import com.pivotal.gemfirexd.internal.iapi.jdbc.WrapperEngineCLOB;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.ApplicationObjectInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.StatementType;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.xa.XAXactId;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedDatabaseMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.jdbc.EmbedXAConnection;
import com.pivotal.gemfirexd.internal.jdbc.EmbeddedXADataSource40;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.shared.common.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.*;
import io.snappydata.thrift.RowIdLifetime;
import io.snappydata.thrift.common.BufferedBlob;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.OptimizedElementArray;
import io.snappydata.thrift.common.ThriftUtils;
import io.snappydata.thrift.internal.ClientBlob;
import io.snappydata.thrift.server.ConnectionHolder.ResultSetHolder;
import io.snappydata.thrift.server.ConnectionHolder.StatementHolder;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TTransport;

/**
 * Server-side implementation of thrift SnappyDataService (see snappydata.thrift).
 */
public final class SnappyDataServiceImpl extends LocatorServiceImpl implements
    SnappyDataService.Iface {

  final GfxdHeapThresholdListener thresholdListener;
  final SecureRandom rand;
  final ConcurrentTLongObjectHashMap<ConnectionHolder> connectionMap;
  final ConcurrentTLongObjectHashMap<StatementHolder> statementMap;
  final ConcurrentTLongObjectHashMap<StatementHolder> resultSetMap;
  final ConcurrentHashMap<String, ClientTracker> clientTrackerMap;
  final ConcurrentHashMap<TTransport, ClientTracker> clientSocketTrackerMap;
  final AtomicLong currentConnectionId;
  final AtomicLong currentStatementId;
  final AtomicLong currentCursorId;
  volatile boolean recordStatementStartTime;

  /**
   * stores the current client's hostname + ID for a new openConnection
   */
  static final ThreadLocal<String> currentClientHostId = new ThreadLocal<>();

  /**
   * stores whether a closeConnection call has requested to also close the socket
   */
  static final ThreadLocal<Boolean> closeClientSocket = new ThreadLocal<>();

  private static final int INVALID_ID = snappydataConstants.INVALID_ID;

  static final Converters.ObjectInputStreamCreator javaObjectCreator =
      new Converters.ObjectInputStreamCreator() {
        @Override
        public ObjectInputStream create(InputStream stream) throws IOException {
          return new ApplicationObjectInputStream(
              stream, Misc.getMemStore().getDatabase().getClassFactory());
        }
      };

  public SnappyDataServiceImpl(String address, int port) {
    super(address, port);
    final GemFireStore store = Misc.getMemStoreBooting();
    this.thresholdListener = store.thresholdListener();

    this.rand = new SecureRandom();
    // Force the random generator to seed itself.
    final byte[] someBytes = new byte[55];
    this.rand.nextBytes(someBytes);

    this.connectionMap = new ConcurrentTLongObjectHashMap<>();
    this.statementMap = new ConcurrentTLongObjectHashMap<>();
    this.resultSetMap = new ConcurrentTLongObjectHashMap<>();
    this.clientTrackerMap = new ConcurrentHashMap<>();
    this.clientSocketTrackerMap = new ConcurrentHashMap<>();
    this.currentConnectionId = new AtomicLong(1);
    this.currentStatementId = new AtomicLong(1);
    this.currentCursorId = new AtomicLong(1);
    this.recordStatementStartTime = false;
  }

  /**
   * Custom Processor implementation to handle closeConnection by closing
   * server-side connection cleanly.
   */
  public static final class Processor extends
      SnappyDataService.Processor<SnappyDataServiceImpl> {

    private final SnappyDataServiceImpl inst;
    private final HashMap<String,
        ProcessFunction<SnappyDataServiceImpl, ?>> fnMap;

    public Processor(SnappyDataServiceImpl inst) {
      super(inst);
      this.inst = inst;
      this.fnMap = new HashMap<>(super.getProcessMapView());
    }

    @Override
    public final boolean process(final TProtocol in, final TProtocol out)
        throws TException {
      final TMessage msg = in.readMessageBegin();
      final ProcessFunction<SnappyDataServiceImpl, ?> fn = fnMap.get(msg.name);
      if (fn != null) {
        fn.process(msg.seqid, in, out, this.inst);
        Class<?> fnClass = fn.getClass();
        // register socket for a client in its tracker on an openConnection
        if (fnClass == SnappyDataService.Processor.openConnection.class) {
          String clientHostId = currentClientHostId.get();
          currentClientHostId.remove();
          ClientTracker tracker = ClientTracker.addOrGetTracker(
              clientHostId, inst);
          if (tracker != null) {
            tracker.addClientSocket(in.getTransport(), inst);
          }
        } else if (fnClass ==
            SnappyDataService.Processor.closeConnection.class) {
          Boolean closeSocket = closeClientSocket.get();
          closeClientSocket.remove();
          // terminate socket on receiving closeConnection with closeSocket=true
          // direct class comparison like above should be the fastest way
          return !(closeSocket != null && closeSocket);
        }
        return true;
      } else {
        TProtocolUtil.skip(in, TType.STRUCT);
        in.readMessageEnd();
        TApplicationException x = new TApplicationException(
            TApplicationException.UNKNOWN_METHOD, "Invalid method name: '" +
            msg.name + "'");
        out.writeMessageBegin(new TMessage(msg.name, TMessageType.EXCEPTION,
            msg.seqid));
        x.write(out);
        out.writeMessageEnd();
        out.getTransport().flush();
        return true;
      }
    }

    public void clientSocketClosed(final TTransport clientTransport) {
      // deregister the socket for client and check if all sockets
      // from that client have been closed
      ClientTracker.removeClientSocket(clientTransport, inst);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ConnectionProperties openConnection(OpenConnectionArgs arguments)
      throws SnappyException {
    currentClientHostId.remove();
    ConnectionHolder connHolder;
    try {
      Properties props = new Properties();
      String clientHost = null, clientId = null;

      boolean forXA = false;
      if (arguments != null) {
        if (arguments.isSetUserName()) {
          props.put(Attribute.USERNAME_ATTR, arguments.getUserName());
        }
        if (arguments.isSetPassword()) {
          props.put(Attribute.PASSWORD_ATTR, arguments.getPassword());
        }
        if (arguments.isSetProperties()) {
          props.putAll(arguments.getProperties());
        }
        clientHost = arguments.getClientHostName();
        clientId = arguments.getClientID();
        forXA = arguments.isSetForXA() && arguments.isForXA();
      }

      final String protocol;
      // default route-query to true on client connections
      if (Misc.getMemStoreBooting().isSnappyStore()) {
        if (!props.containsKey(Attribute.ROUTE_QUERY)) {
          props.setProperty(Attribute.ROUTE_QUERY, "true");
        }
        protocol = Attribute.SNAPPY_PROTOCOL;
      } else {
        protocol = Attribute.PROTOCOL;
      }
      EngineConnection conn;
      EmbedXAConnection xaConn;
      // initialize an XAConnection if required
      if (forXA) {
        EmbeddedXADataSource40 ds = new EmbeddedXADataSource40();
        String user = null, password = null;
        if (!props.isEmpty()) {
          StringBuilder sb = new StringBuilder();
          for (String key : props.stringPropertyNames()) {
            if (key.equalsIgnoreCase(Attribute.USERNAME_ATTR) ||
                key.equalsIgnoreCase(Attribute.USERNAME_ALT_ATTR)) {
              user = props.getProperty(key);
              continue;
            } else if (key.equalsIgnoreCase(Attribute.PASSWORD_ATTR)) {
              password = props.getProperty(key);
              continue;
            }
            if (sb.length() > 0) {
              sb.append(';');
            }
            sb.append(key).append('=').append(props.getProperty(key));
          }
          if (sb.length() > 0) {
            ds.setConnectionAttributes(sb.toString());
          }
        }
        if (user != null) {
          xaConn = (EmbedXAConnection)ds.getXAConnection(user, password);
        } else {
          xaConn = (EmbedXAConnection)ds.getXAConnection();
        }
        conn = (EngineConnection)xaConn.getConnection();
        // autocommit has to be false for XA connections
        xaConn.checkAutoCommit(false);
        conn.setAutoCommit(false);
        // set RC isolation level by default
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      } else {
        conn = (EngineConnection)InternalDriver.activeDriver()
            .connect(protocol, props, Converters.getJdbcIsolation(
                snappydataConstants.DEFAULT_TRANSACTION_ISOLATION));
        conn.setAutoCommit(snappydataConstants.DEFAULT_AUTOCOMMIT);
        xaConn = null;
      }
      while (true) {
        final long connId = getNextId(this.currentConnectionId);
        connHolder = new ConnectionHolder(conn, xaConn, arguments, connId,
            props, this.rand);
        if (this.connectionMap.putIfAbsent(connId, connHolder) == null) {
          ConnectionProperties connProps = new ConnectionProperties(connId,
              clientHost, clientId);
          connProps.setToken(connHolder.getToken());
          connProps.setDefaultSchema(conn.getCurrentSchemaName());

          // setup tracker for this client and put in ThreadLocal so that
          // processor can make the entry for current TSocket
          final String clientHostId = connHolder.getClientHostId();
          ClientTracker tracker = ClientTracker.addOrGetTracker(clientHostId,
              this);
          if (tracker != null) {
            tracker.addClientConnection(connId);
            currentClientHostId.set(clientHostId);
          }

          return connProps;
        }
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeConnection(long connId, boolean closeSocket,
      ByteBuffer token) {
    try {
      ConnectionHolder connHolder = this.connectionMap.getPrimitive(connId);
      if (connHolder != null) {
        if (connHolder.sameToken(token)) {
          connHolder.close(this, false);
          this.connectionMap.removePrimitive(connId);
          // also remove from client tracker map
          ClientTracker tracker = ClientTracker.addOrGetTracker(
              connHolder.getClientHostId(), this);
          if (tracker != null) {
            tracker.removeClientConnection(connId);
          }
          closeClientSocket.set(closeSocket);
        } else {
          throw tokenMismatchException(token, "closeConnection [connId="
              + connId + ']');
        }
      }
    } catch (Throwable t) {
      if (!ignoreNonFatalException(t)) {
        logger.info("Unexpected exception in closeConnection for CONNID=" +
            connId, t);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void bulkClose(List<EntityId> entities) {
    if (entities == null) {
      return;
    }
    for (EntityId entity : entities) {
      try {
        long id = entity.id;
        ByteBuffer token = entity.token;
        switch (entity.type) {
          case snappydataConstants.BULK_CLOSE_RESULTSET:
            closeResultSet(id, token);
            break;
          case snappydataConstants.BULK_CLOSE_LOB:
            freeLob(entity.connId, id, token);
            break;
          case snappydataConstants.BULK_CLOSE_STATEMENT:
            closeStatement(id, token);
            break;
          case snappydataConstants.BULK_CLOSE_CONNECTION:
            closeConnection(id, false, token);
            break;
          default:
            // ignore; this is a oneway function
            break;
        }
      } catch (SnappyException se) {
        if (SQLState.NET_CONNECT_AUTH_FAILED.substring(0, 5).equals(
            se.getExceptionData().getSqlState())) {
          logger.warn("AUTH exception in bulkClose (continuing with other " +
              "close operations): " + se);
        }
      } catch (Throwable t) {
        if (!ignoreNonFatalException(t)) {
          logger.info("Unexpected exception in bulkClose (continuing with other " +
              "close operations): " + t);
        }
      }
    }
  }

  void forceCloseConnection(long connId) {
    try {
      // remove upfront from map in any case
      ConnectionHolder connHolder = (ConnectionHolder)this.connectionMap
          .removePrimitive(connId);
      if (connHolder != null) {
        connHolder.close(this, true);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      logger.info("Unexpected exception in forceCloseConnection for CONNID=" +
          connId, t);
    }
  }

  @Override
  public void stop() {
    // first close all open client connections being served by this instance
    try {
      final long[] connIds = this.connectionMap.keys();
      for (long connId : connIds) {
        ConnectionHolder connHolder = (ConnectionHolder)this.connectionMap
            .removePrimitive(connId);
        if (connHolder != null) {
          try {
            connHolder.close(this, true);
          } catch (Throwable t) {
            checkSystemFailure(t);
            logger.info("Unexpected exception in connection close in stop " +
                "for CONNID=" + connId, t);
          }
        }
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      logger.info("Unexpected exception in stop", t);
    }
    super.stop();
  }

  /**
   * Helper method Validate the connection Id. If Id not found in the map throw
   * the connection unavailable exception.
   *
   * @return ConnectionHolder if found in the map.
   */
  private ConnectionHolder getValidConnection(long connId, ByteBuffer token)
      throws SnappyException {
    ConnectionHolder connHolder = this.connectionMap.getPrimitive(connId);
    if (connHolder != null) {
      if (connHolder.sameToken(token)) {
        return connHolder;
      } else {
        throw tokenMismatchException(token, "getConnection [connId=" + connId
            + ']');
      }
    } else {
      SnappyExceptionData exData = new SnappyExceptionData();
      exData.setReason("No connection with ID="
          + ConnectionHolder.getTokenAsString(token));
      exData.setSqlState(SQLState.NO_CURRENT_CONNECTION);
      exData.setErrorCode(ExceptionSeverity.STATEMENT_SEVERITY);
      throw new SnappyException(exData, getServerInfo());
    }
  }

  private XAResource getXAResource(ConnectionHolder connHolder)
      throws SQLException, XAException {
    EmbedXAConnection xaConn;
    if ((xaConn = connHolder.getXAConnection()) != null) {
      return xaConn.getXAResource();
    } else {
      throw new XAException(XAException.XAER_PROTO);
    }
  }

  private StatementHolder getStatement(ByteBuffer token, long stmtId,
      boolean isPrepared, String op) throws SnappyException {
    StatementHolder stmtHolder;
    if ((stmtHolder = this.statementMap.getPrimitive(stmtId)) != null) {
      if (stmtHolder.getConnectionHolder().sameToken(token)) {
        if (!isPrepared
            || stmtHolder.getStatement() instanceof PreparedStatement) {
          return stmtHolder;
        } else {
          throw statementNotFoundException(stmtId, op, true);
        }
      } else {
        throw tokenMismatchException(token, op);
      }
    } else {
      throw statementNotFoundException(stmtId, op, isPrepared);
    }
  }

  private StatementHolder getStatementForResultSet(ByteBuffer token,
      long cursorId, String op) throws SnappyException {
    StatementHolder stmtHolder;
    if ((stmtHolder = this.resultSetMap.getPrimitive(cursorId)) != null) {
      if (stmtHolder.getConnectionHolder().sameToken(token)) {
        return stmtHolder;
      } else {
        throw tokenMismatchException(token, op);
      }
    } else {
      throw resultSetNotFoundException(cursorId, op);
    }
  }

  SnappyException tokenMismatchException(ByteBuffer token, final String op) {
    SnappyExceptionData exData = new SnappyExceptionData();
    String message = token != null && token.hasRemaining()
        ? "connection token " + ConnectionHolder.getTokenAsString(token) +
        " mismatch for operation " + op
        : "No connection token passed for operation " + op;
    exData.setReason(MessageService.getTextMessage(
        SQLState.NET_CONNECT_AUTH_FAILED, message));
    exData.setSqlState(SQLState.NET_CONNECT_AUTH_FAILED.substring(0, 5));
    exData.setErrorCode(ExceptionSeverity.SESSION_SEVERITY);
    return new SnappyException(exData, getServerInfo());
  }

  SnappyException resultSetNotFoundException(long cursorId, String op) {
    SnappyExceptionData exData = new SnappyExceptionData();
    exData.setReason("No result set open with ID=" + cursorId
        + " for operation " + op);
    exData.setSqlState(SQLState.LANG_RESULT_SET_NOT_OPEN.substring(0, 5));
    exData.setErrorCode(ExceptionSeverity.STATEMENT_SEVERITY);
    return new SnappyException(exData, getServerInfo());
  }

  SnappyException statementNotFoundException(long stmtId, String op,
      boolean isPrepared) {
    SnappyExceptionData exData = new SnappyExceptionData();
    exData.setReason("No " + (isPrepared ? "prepared " : "")
        + "statement with ID=" + stmtId + " for operation " + op);
    exData.setSqlState(SQLState.LANG_DEAD_STATEMENT);
    exData.setErrorCode(ExceptionSeverity.STATEMENT_SEVERITY);
    return new SnappyException(exData, getServerInfo());
  }

  @Override
  protected String getServerInfo() {
    return "Server=" + this.hostAddress + '[' + this.hostPort + "] Thread="
        + Thread.currentThread().getName();
  }

  private static long getNextId(final AtomicLong id) {
    while (true) {
      long currentId = id.get();
      long nextId = currentId + 1;
      if (nextId == INVALID_ID) {
        nextId++;
      }
      if (id.compareAndSet(currentId, nextId)) {
        return currentId;
      }
    }
  }

  /**
   * Returns the resultType if set if not set returns the default
   * java.sql.ResultSet.TYPE_FORWARD_ONLY
   */
  static int getResultType(StatementAttrs attrs) {
    int rsType;
    if (attrs != null && attrs.isSetResultSetType()) {
      rsType = attrs.getResultSetType();
    } else {
      rsType = snappydataConstants.DEFAULT_RESULTSET_TYPE;
    }
    switch (rsType) {
      case snappydataConstants.RESULTSET_TYPE_FORWARD_ONLY:
        return ResultSet.TYPE_FORWARD_ONLY;
      case snappydataConstants.RESULTSET_TYPE_INSENSITIVE:
        return ResultSet.TYPE_SCROLL_INSENSITIVE;
      case snappydataConstants.RESULTSET_TYPE_SENSITIVE:
        return ResultSet.TYPE_SCROLL_SENSITIVE;
      default:
        throw new InternalGemFireError("unknown resultSet type "
            + attrs.getResultSetType());
    }
  }

  /**
   * Returns the Concurrency associated with the statement if set the the input
   * object if not set by default returns java.sql.ResultSet.CONCUR_READ_ONLY
   */
  static int getResultSetConcurrency(StatementAttrs attrs) {
    return attrs != null && attrs.isSetUpdatable() && attrs.isUpdatable()
        ? ResultSet.CONCUR_UPDATABLE : ResultSet.CONCUR_READ_ONLY;
  }

  /**
   * Returns the Holdability associated with the statement if set the the input
   * object if not set by default returns #ResultS
   */
  static int getResultSetHoldability(StatementAttrs attrs) {
    return attrs != null && attrs.isSetHoldCursorsOverCommit()
        && attrs.isHoldCursorsOverCommit() ? ResultSet.HOLD_CURSORS_OVER_COMMIT
        : ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  private static BlobChunk getAsLastChunk(Blob blob, int length)
      throws SQLException {
    if (blob instanceof BufferedBlob) {
      return ((BufferedBlob)blob).getAsLastChunk();
    } else {
      return new BlobChunk(ByteBuffer.wrap(blob.getBytes(1, length)), true);
    }
  }

  private BlobChunk handleBlob(Blob blob, ConnectionHolder connHolder,
      StatementAttrs attrs) throws SQLException {
    final long length = blob.length();
    if (length > Integer.MAX_VALUE) {
      throw Util.generateCsSQLException(SQLState.BLOB_TOO_LARGE_FOR_CLIENT,
          Long.toString(length), Long.toString(Integer.MAX_VALUE));
    }
    BlobChunk chunk = new BlobChunk().setOffset(0).setTotalLength(length);
    final int chunkSize;
    if (attrs != null && attrs.isSetLobChunkSize()) {
      chunkSize = attrs.lobChunkSize;
    } else {
      chunkSize = snappydataConstants.DEFAULT_LOB_CHUNKSIZE;
    }
    if (chunkSize > 0 && chunkSize < length) {
      chunk.chunk = ByteBuffer.wrap(blob.getBytes(1, chunkSize));
      chunk.setLast(false);
      // need to add explicit mapping for the LOB in this case
      long lobId;
      if (blob instanceof EngineLOB) {
        lobId = ((EngineLOB)blob).getLocator();
      } else {
        lobId = new WrapperEngineBLOB(connHolder.getConnection(), blob)
            .getLocator();
      }
      chunk.setLobId(lobId);
    } else {
      chunk = getAsLastChunk(blob, (int)length);
      blob.free();
    }
    return chunk;
  }

  private ClobChunk handleClob(Clob clob, ConnectionHolder connHolder,
      StatementAttrs attrs) throws SQLException {
    final long length = clob.length();
    if (length > Integer.MAX_VALUE) {
      throw Util.generateCsSQLException(SQLState.BLOB_TOO_LARGE_FOR_CLIENT,
          Long.toString(length), Long.toString(Integer.MAX_VALUE));
    }
    ClobChunk chunk = new ClobChunk().setOffset(0)
        .setTotalLength(length);
    final int chunkSize;
    if (attrs != null && attrs.isSetLobChunkSize()) {
      chunkSize = attrs.lobChunkSize;
    } else {
      chunkSize = snappydataConstants.DEFAULT_LOB_CHUNKSIZE;
    }
    if (chunkSize > 0 && chunkSize < length) {
      chunk.setChunk(clob.getSubString(1, chunkSize)).setLast(false);
      // need to add explicit mapping for the LOB in this case
      long lobId;
      if (clob instanceof EngineLOB) {
        lobId = ((EngineLOB)clob).getLocator();
      } else {
        lobId = new WrapperEngineCLOB(connHolder.getConnection(), clob)
            .getLocator();
      }
      chunk.setLobId(lobId);
    } else {
      chunk.setChunk(clob.getSubString(1, (int)length)).setLast(true);
      clob.free();
    }
    return chunk;
  }

  /**
   * Set a column value in a Row.
   * <p>
   * The java version of Row overrides the thrift one to make use of
   * {@link OptimizedElementArray} to reduce overhead/objects while still
   * keeping serialization compatible with thrift Row.
   * </p>
   */
  private long setColumnValue(ResultSet rs, SnappyType colType,
      int columnPosition, ConnectionHolder connHolder, StatementAttrs attrs,
      Row result) throws SQLException {
    final int index = columnPosition - 1;
    switch (colType) {
      case BOOLEAN:
        boolean boolValue = rs.getBoolean(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setBoolean(index, boolValue);
          return 1;
        }
      case TINYINT:
        byte byteValue = rs.getByte(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setByte(index, byteValue);
          return 1;
        }
      case SMALLINT:
        short shortValue = rs.getShort(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setShort(index, shortValue);
          return 2;
        }
      case INTEGER:
        int intValue = rs.getInt(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setInt(index, intValue);
          return 4;
        }
      case BIGINT:
        long longValue = rs.getLong(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setLong(index, longValue);
          return 8;
        }
      case FLOAT:
        float fltValue = rs.getFloat(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setFloat(index, fltValue);
          return 4;
        }
      case DOUBLE:
        double dblValue = rs.getDouble(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setDouble(index, dblValue);
          return 8;
        }
      case CHAR:
      case VARCHAR:
      case LONGVARCHAR:
        String strValue = rs.getString(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setObject(index, strValue, colType);
          return ((long)(ReflectionSingleObjectSizer.OBJECT_SIZE +
              strValue.length())) << 1L;
        }
      case BLOB:
        Blob blob = rs.getBlob(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          BlobChunk chunk = handleBlob(blob, connHolder, attrs);
          result.setObject(index, chunk, SnappyType.BLOB);
          return ReflectionSingleObjectSizer.OBJECT_SIZE * 3 +
              chunk.chunk.limit() + 12 /* for remaining fields */;
        }
      case CLOB:
      case JSON:
      case SQLXML:
        Clob clob = rs.getClob(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          ClobChunk chunk = handleClob(clob, connHolder, attrs);
          result.setObject(index, chunk, colType);
          return ReflectionSingleObjectSizer.OBJECT_SIZE * 3 +
              chunk.chunk.length() + 12 /* for remaining fields */;
        }
      case DECIMAL:
        BigDecimal bd = rs.getBigDecimal(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          if (connHolder.useStringForDecimal()) {
            String s = bd.toPlainString();
            result.setObject(index, s, SnappyType.VARCHAR);
            return ((long)(ReflectionSingleObjectSizer.OBJECT_SIZE +
                s.length())) << 1L;
          } else {
            result.setObject(index, bd, SnappyType.DECIMAL);
            return ReflectionSingleObjectSizer.OBJECT_SIZE * 3 +
                (bd.precision() << 2);
          }
        }
      case DATE:
        Date dtVal = rs.getDate(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setDateTime(index, dtVal);
          return 8;
        }
      case TIME:
        Time timeVal = rs.getTime(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setDateTime(index, timeVal);
          return 8;
        }
      case TIMESTAMP:
        java.sql.Timestamp tsVal = rs.getTimestamp(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setTimestamp(index, tsVal);
          return 8;
        }
      case BINARY:
      case VARBINARY:
      case LONGVARBINARY:
        byte[] byteArray = rs.getBytes(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setObject(index, byteArray, colType);
          return ReflectionSingleObjectSizer.OBJECT_SIZE + byteArray.length;
        }
      case NULLTYPE:
        result.setNull(index);
        return 1;
      case JAVA_OBJECT:
        Object o = rs.getObject(columnPosition);
        if (rs.wasNull()) {
          result.setNull(index);
          return 1;
        } else {
          result.setObject(index, new Converters.JavaObjectWrapper(
              o, columnPosition), SnappyType.JAVA_OBJECT);
        }
        // hard-code some fixed size
        return 128;
      case ARRAY:
      case MAP:
      case STRUCT:
        // TODO: proper implementation of above three
      default:
        throw Util.generateCsSQLException(SQLState.DATA_TYPE_NOT_SUPPORTED,
            Util.typeName(Converters.getJdbcType(colType)));
    }
  }

  /**
   * Get an output column value from CallableStatement in a Row.
   * <p>
   * The java version of Row overrides the thrift one to make use of
   * {@link OptimizedElementArray} to reduce overhead/objects while still
   * keeping serialization compatible with thrift Row.
   * </p>
   */
  private ColumnValue getColumnValue(CallableStatement cstmt,
      int paramPosition, int paramType, ConnectionHolder connHolder,
      StatementAttrs attrs) throws SQLException {
    ColumnValue cv = new ColumnValue();
    switch (paramType) {
      case Types.BOOLEAN:
        boolean boolValue = cstmt.getBoolean(paramPosition);
        if (boolValue || !cstmt.wasNull()) {
          cv.setBool_val(boolValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.TINYINT:
        byte byteValue = cstmt.getByte(paramPosition);
        if (byteValue != 0 || !cstmt.wasNull()) {
          cv.setByte_val(byteValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.SMALLINT:
        short shortValue = cstmt.getShort(paramPosition);
        if (shortValue != 0 || !cstmt.wasNull()) {
          cv.setI16_val(shortValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.INTEGER:
        int intValue = cstmt.getInt(paramPosition);
        if (intValue != 0 || !cstmt.wasNull()) {
          cv.setI32_val(intValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.BIGINT:
        long longValue = cstmt.getLong(paramPosition);
        if (longValue != 0 || !cstmt.wasNull()) {
          cv.setI64_val(longValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.REAL:
        float fltValue = cstmt.getFloat(paramPosition);
        if (fltValue != 0.0f || !cstmt.wasNull()) {
          cv.setFloat_val(Float.floatToIntBits(fltValue));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.DOUBLE:
      // map JDBC FLOAT types to double since it can have precision
      // more than what float can hold
      case Types.FLOAT:
        double dblValue = cstmt.getDouble(paramPosition);
        if (dblValue != 0.0 || !cstmt.wasNull()) {
          cv.setDouble_val(dblValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.LONGNVARCHAR:
        String strValue = cstmt.getString(paramPosition);
        if (strValue != null) {
          cv.setString_val(strValue);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.BLOB:
        Blob blob = cstmt.getBlob(paramPosition);
        if (blob != null) {
          cv.setBlob_val(handleBlob(blob, connHolder, attrs));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.CLOB:
      case JDBC40Translation.JSON:
      case Types.SQLXML:
        Clob clob = cstmt.getClob(paramPosition);
        if (clob != null) {
          cv.setClob_val(handleClob(clob, connHolder, attrs));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.DECIMAL:
      case Types.NUMERIC:
        BigDecimal bd = cstmt.getBigDecimal(paramPosition);
        if (bd != null) {
          if (connHolder.useStringForDecimal()) {
            cv.setString_val(bd.toPlainString());
          } else {
            cv.setDecimal_val(Converters.getDecimal(bd));
          }
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.DATE:
        Date dtVal = cstmt.getDate(paramPosition);
        if (dtVal != null) {
          cv.setDate_val(Converters.getDateTime(dtVal));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.TIME:
        Time timeVal = cstmt.getTime(paramPosition);
        if (timeVal != null) {
          cv.setTime_val(Converters.getDateTime(timeVal));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.TIMESTAMP:
        java.sql.Timestamp tsVal = cstmt.getTimestamp(paramPosition);
        if (tsVal != null) {
          cv.setTimestamp_val(Converters.getTimestampNanos(tsVal));
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        byte[] byteArray = cstmt.getBytes(paramPosition);
        if (byteArray != null) {
          cv.setBinary_val(byteArray);
          break;
        } else {
          cv.setNull_val(true);
          break;
        }
      case Types.NULL:
        cstmt.getObject(paramPosition);
        cv.setNull_val(cstmt.wasNull());
        break;
      case Types.JAVA_OBJECT:
        Object o = cstmt.getObject(paramPosition);
        if (o == null) {
          cv.setNull_val(true);
        } else {
          cv.setJava_val(Converters.getJavaObjectAsBytes(o, paramPosition));
        }
        break;
      case Types.ARRAY:
      case Types.STRUCT:
      case JDBC40Translation.MAP:
        // TODO: proper implementation of above three
      default:
        throw Util.generateCsSQLException(SQLState.DATA_TYPE_NOT_SUPPORTED,
            Util.typeName(paramType));
    }
    return cv;
  }

  private ArrayList<ColumnDescriptor> getRowSetMetaData(
      final ResultSetMetaData rsmd, final int columnCount,
      final boolean useStringForDecimal) throws SQLException {
    final ArrayList<ColumnDescriptor> descriptors =
        new ArrayList<>(columnCount);

    if (rsmd instanceof EmbedResultSetMetaData) {
      // using somewhat more efficient methods for EmbedResultSetMetaData
      final EmbedResultSetMetaData ersmd = (EmbedResultSetMetaData)rsmd;
      final boolean tableReadOnly = ersmd.isTableReadOnly();
      String columnName, schemaName, tableName, fullTableName;
      String typeName, className;
      int jdbcType, scale;
      SnappyType type;
      for (int colIndex = 1; colIndex <= columnCount; colIndex++) {
        ResultColumnDescriptor rcd = ersmd.getColumnDescriptor(colIndex);
        DataTypeDescriptor dtd = rcd.getType();
        TypeId typeId = dtd.getTypeId();
        jdbcType = typeId.getJDBCTypeId();
        type = Converters.getThriftSQLType(jdbcType, useStringForDecimal);
        ColumnDescriptor columnDesc = new ColumnDescriptor();
        columnName = rcd.getName();
        if (columnName != null) {
          columnDesc.setName(columnName);
        }
        schemaName = rcd.getSourceSchemaName();
        tableName = rcd.getSourceTableName();
        fullTableName = schemaName != null ? (schemaName + '.' + tableName)
            : tableName;
        columnDesc.setFullTableName(fullTableName);

        int nullable = DataTypeUtilities.isNullable(dtd);
        if (nullable == ResultSetMetaData.columnNullable) {
          columnDesc.setNullable(true);
        } else if (nullable == ResultSetMetaData.columnNoNulls) {
          columnDesc.setNullable(false);
        }
        if (!tableReadOnly) {
          if (rcd.updatableByCursor()) {
            columnDesc.setUpdatable(true);
          }
          if (ersmd.isDefiniteWritable_(colIndex)) {
            columnDesc.setDefinitelyUpdatable(true);
          }
        }
        if (rcd.isAutoincrement()) {
          columnDesc.setAutoIncrement(true);
        }
        columnDesc.setType(type);
        columnDesc
            .setPrecision((short)DataTypeUtilities.getDigitPrecision(dtd));
        scale = dtd.getScale();
        if (scale != 0) {
          columnDesc.setScale((short)scale);
        }
        if (jdbcType == Types.JAVA_OBJECT) {
          typeName = typeId.getSQLTypeName();
          className = typeId.getResultSetMetaDataTypeName();
          columnDesc.setUdtTypeAndClassName(typeName
              + (className != null ? ":" + className : ""));
        }
        descriptors.add(columnDesc);
      }
    } else {
      String schemaName, tableName, fullTableName;
      String typeName, className;
      String prevSchemaName = null, prevTableName = null;
      String prevTypeName = null, prevClassName = null;
      int jdbcType, scale;
      for (int colIndex = 1; colIndex <= columnCount; colIndex++) {
        ColumnDescriptor columnDesc = new ColumnDescriptor();
        columnDesc.setName(rsmd.getColumnName(colIndex));
        schemaName = rsmd.getSchemaName(colIndex);
        tableName = rsmd.getTableName(colIndex);
        if (colIndex > 1) {
          if ((schemaName != null && !schemaName.equals(prevSchemaName))
              || (tableName != null && !tableName.equals(prevTableName))) {
            fullTableName = schemaName != null ? (schemaName + '.' + tableName)
                : tableName;
            columnDesc.setFullTableName(fullTableName);
          }
        } else {
          fullTableName = schemaName != null ? (schemaName + '.' + tableName)
              : tableName;
          columnDesc.setFullTableName(fullTableName);
        }
        prevSchemaName = schemaName;
        prevTableName = tableName;

        int nullable = rsmd.isNullable(colIndex);
        if (nullable == ResultSetMetaData.columnNullable) {
          columnDesc.setNullable(true);
        } else if (nullable == ResultSetMetaData.columnNoNulls) {
          columnDesc.setNullable(false);
        }
        if (rsmd.isDefinitelyWritable(colIndex)) {
          columnDesc.setDefinitelyUpdatable(true);
        }
        if (rsmd.isWritable(colIndex)) {
          columnDesc.setUpdatable(true);
        }
        if (rsmd.isAutoIncrement(colIndex)) {
          columnDesc.setAutoIncrement(true);
        }
        jdbcType = rsmd.getColumnType(colIndex);
        columnDesc.setType(Converters.getThriftSQLType(jdbcType,
            useStringForDecimal));
        columnDesc.setPrecision((short)rsmd.getPrecision(colIndex));
        scale = rsmd.getScale(colIndex);
        if (scale != 0) {
          columnDesc.setScale((short)scale);
        }
        if (jdbcType == Types.JAVA_OBJECT) {
          typeName = rsmd.getColumnTypeName(colIndex);
          className = rsmd.getColumnClassName(colIndex);
          if ((typeName != null && !typeName.equals(prevTypeName))
              || (className != null && !className.equals(prevClassName))) {
            columnDesc.setUdtTypeAndClassName(typeName
                + (className != null ? ":" + className : ""));
          }
          prevTypeName = typeName;
          prevClassName = className;
        }
        descriptors.add(columnDesc);
      }
    }
    return descriptors;
  }

  /**
   * Encapsulates the ResultSet of statement execution as thrift RowSet.
   */
  private RowSet getRowSet(final Statement stmt, StatementHolder stmtHolder,
      final ResultSet rs, long cursorId, ResultSetHolder holder,
      final long connId, final StatementAttrs attrs, int offset,
      final boolean offsetIsAbsolute, final boolean fetchReverse,
      final int fetchSize, final ConnectionHolder connHolder,
      final String sql) throws SnappyException {
    boolean isLastBatch = true;
    try {
      RowSet result = createEmptyRowSet().setConnId(connId);
      final boolean isForwardOnly = rs.getType() == ResultSet.TYPE_FORWARD_ONLY;
      // first fill in the metadata
      final ResultSetMetaData rsmd = rs.getMetaData();
      final int columnCount = rsmd.getColumnCount();
      final ArrayList<ColumnDescriptor> descriptors = getRowSetMetaData(rsmd,
          columnCount, connHolder.useStringForDecimal());
      if (holder == null) { // skip sending descriptors for scrollCursor
        result.setMetadata(descriptors);
      }
      // now fill in the values
      final int batchSize;
      final boolean moveForward = !fetchReverse &&
          (attrs == null || !attrs.fetchReverse);
      boolean hasMoreRows = false;
      byte flags = 0;
      long startTime = 0;
      if (fetchSize > 0) {
        batchSize = fetchSize;
      } else if (attrs != null && attrs.isSetBatchSize()) {
        batchSize = attrs.batchSize;
      } else {
        batchSize = snappydataConstants.DEFAULT_RESULTSET_BATCHSIZE;
      }

      if (offsetIsAbsolute) {
        // +ve offset is 0, 1, ... while absolute numbers from 1
        hasMoreRows = rs.absolute(offset >= 0 ? ++offset : offset);
        if (!hasMoreRows) {
          // check if cursor moved to before first or after last
          if (offset > 0) {
            flags |= snappydataConstants.ROWSET_AFTER_LAST;
            // can still fetch after last in reverse
            if (!moveForward) {
              // move one previous to land on last row
              hasMoreRows = rs.previous();
            }
          } else {
            flags |= snappydataConstants.ROWSET_BEFORE_FIRST;
            // can still fetch before first in forward
            if (moveForward) {
              // move one next to land on last row
              hasMoreRows = rs.next();
            }
          }
        }
        // getRow() required for -ve offset
        result.setOffset(Math.max(0, rs.getRow() - 1));
      } else if (offset == 0) {
        if (holder == null) { // first call to create result set
          result.setOffset(0);
        } else {
          result.setOffset(holder.rsOffset);
        }
      } else {
        hasMoreRows = rs.relative(offset);
        if (!hasMoreRows) {
          // check if cursor moved to before first or after last
          if (offset > 0) {
            flags |= snappydataConstants.ROWSET_AFTER_LAST;
            // can still fetch after last in reverse
            if (!moveForward) {
              // move one previous to land on last row
              hasMoreRows = rs.previous();
            }
          } else {
            flags |= snappydataConstants.ROWSET_BEFORE_FIRST;
            // can still fetch before first in forward
            if (moveForward) {
              // move one next to land on last row
              hasMoreRows = rs.next();
            }
          }
        }
        result.setOffset(Math.max(0, rs.getRow() - 1));
      }

      final EngineConnection conn = connHolder.getConnection();
      final List<Row> rows = result.getRows();
      // empty row just to help in creating fast clones
      final Row templateRow = new Row(descriptors);
      EngineStatement estmt = null;
      long estimatedSize = 0L;
      int nrows = 0;
      if (rs instanceof EmbedResultSet) {
        final EmbedResultSet ers = (EmbedResultSet)rs;
        estmt = (EngineStatement)stmt;
        synchronized (conn.getConnectionSynchronization()) {
          LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
          ers.setupContextStack(false);
          ers.pushStatementContext(lcc, true);
          try {
            // skip the first move in case cursor was already positioned by an
            // explicit call to absolute or relative
            if (offset == 0) {
              hasMoreRows = moveForward ? ers.lightWeightNext() : ers
                  .lightWeightPrevious();
            }
            while (hasMoreRows) {
              Row eachRow = new Row(templateRow, true, true);
              for (int colIndex = 1; colIndex <= columnCount; colIndex++) {
                estimatedSize += setColumnValue(ers, descriptors.get(
                    colIndex - 1).type, colIndex, connHolder, attrs, eachRow);
              }
              rows.add(eachRow);
              if (((++nrows) % GemFireXDUtils.DML_SAMPLE_INTERVAL) == 0) {
                // throttle the processing and sends if CRITICAL_UP has been reached
                if (throttleIfCritical()) {
                  isLastBatch = false;
                  break;
                }
                getCancelCriterion().checkCancelInProgress(null);
                long currentTime = System.nanoTime();
                if (startTime > 0) {
                  if ((currentTime - startTime) >=
                      (1000000L * GemFireXDUtils.DML_MAX_CHUNK_MILLIS)) {
                    isLastBatch = false;
                    break;
                  }
                } else {
                  startTime = currentTime;
                }
              }
              if (nrows >= batchSize ||
                  estimatedSize > GemFireXDUtils.DML_MAX_CHUNK_SIZE) {
                isLastBatch = false;
                break;
              }
              hasMoreRows = moveForward ? ers.lightWeightNext() : ers
                  .lightWeightPrevious();
            }
          } finally {
            StatementContext context = lcc.getStatementContext();
            if (lcc.getStatementDepth() > 0) {
              lcc.popStatementContext(context, null);
            } else if (context != null && context.inUse()) {
              context.clearInUse();
            }
            ers.restoreContextStack();
          }
        }
      } else {
        if (offset == 0) {
          hasMoreRows = moveForward ? rs.next() : rs.previous();
        }
        while (hasMoreRows) {
          final Row eachRow = new Row(templateRow, true, true);
          for (int colIndex = 1; colIndex <= columnCount; colIndex++) {
            estimatedSize += setColumnValue(rs, descriptors.get(
                colIndex - 1).type, colIndex, connHolder, attrs, eachRow);
          }
          rows.add(eachRow);
          if (((++nrows) % GemFireXDUtils.DML_SAMPLE_INTERVAL) == 0) {
            // throttle the processing and sends if CRITICAL_UP has been reached
            if (throttleIfCritical()) {
              isLastBatch = false;
              break;
            }
            getCancelCriterion().checkCancelInProgress(null);
            long currentTime = System.nanoTime();
            if (startTime > 0) {
              if ((currentTime - startTime) >=
                  (1000000L * GemFireXDUtils.DML_MAX_CHUNK_MILLIS)) {
                isLastBatch = false;
                break;
              }
            } else {
              startTime = currentTime;
            }
          }
          if (nrows >= batchSize ||
              estimatedSize > GemFireXDUtils.DML_MAX_CHUNK_SIZE) {
            isLastBatch = false;
            break;
          }
          hasMoreRows = moveForward ? rs.next() : rs.previous();
        }
      }

      /*
      // reverse the list if traversing backwards (client always expects the
      // results in forward order that it will traverse in reverse if required)
      if (!moveForward) {
        Collections.reverse(rows);
      }
      */

      if (isLastBatch) flags |= snappydataConstants.ROWSET_LAST_BATCH;
      final boolean dynamicResults = estmt != null && estmt.hasDynamicResults();
      if (dynamicResults) flags |= snappydataConstants.ROWSET_HAS_MORE_ROWSETS;
      result.setFlags(flags);
      fillWarnings(result, rs);
      // send cursorId for scrollable, partial resultsets or open LOBs
      if (isLastBatch && isForwardOnly && !conn.hasLOBs() &&
          !dynamicResults) {
        if (stmtHolder == null || cursorId == INVALID_ID) {
          rs.close();
        } else {
          stmtHolder.closeResultSet(cursorId, this);
        }
        // setup the Statement for reuse
        if (holder == null && estmt != null && !estmt.isPrepared()) {
          connHolder.setStatementForReuse(estmt);
        }
        result.setCursorId(INVALID_ID);
        result.setStatementId(INVALID_ID);
      } else {
        if (holder == null) {
          cursorId = getNextId(this.currentCursorId);
          if (stmtHolder == null) {
            // upon creation the first ResultSet information will go into
            // StatementHolder itself, hence (holder = stmtHolder) below
            holder = stmtHolder = registerResultSet(cursorId, rs, connHolder,
                stmt, attrs, sql);
          } else {
            holder = registerResultSet(cursorId, rs, stmtHolder);
          }
        }
        result.setCursorId(cursorId);
        result.setStatementId(stmtHolder.getStatementId());
        // update the tracked resultset offset for next scrollCursor call
        if (moveForward) {
          holder.rsOffset = result.offset + rows.size();
        } else {
          holder.rsOffset = Math.max(0, result.offset - rows.size());
        }
      }
      return result;
    } catch (SQLException e) {
      throw SnappyException(e);
    }
  }

  private boolean isLast(RowSet rs) {
    return rs == null || (rs.flags & snappydataConstants.ROWSET_LAST_BATCH) != 0;
  }

  private boolean throttleIfCritical() {
    if (this.thresholdListener.isCritical()) {
      // throttle the processing and sends
      try {
        for (int tries = 1; tries <= 5; tries++) {
          Thread.sleep(4);
          if (!this.thresholdListener.isCritical()) {
            break;
          }
        }
      } catch (InterruptedException ie) {
        getCancelCriterion().checkCancelInProgress(ie);
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Create an empty row set.
   */
  private static RowSet createEmptyRowSet() {
    RowSet rs = new RowSet();
    rs.setRows(new ArrayList<Row>());
    return rs;
  }

  /**
   * Create an empty statement result.
   */
  private StatementResult createEmptyStatementResult() {
    return new StatementResult();
  }

  private StatementHolder registerResultSet(long cursorId, ResultSet rs,
      ConnectionHolder connHolder, Statement stmt, StatementAttrs attrs,
      String sql) {
    final StatementHolder stmtHolder;
    if (stmt != null) {
      final long stmtId = getNextId(this.currentStatementId);
      stmtHolder = connHolder.registerResultSet(stmt, attrs, stmtId, rs,
          cursorId, sql, recordStatementStartTime);
      this.statementMap.putPrimitive(stmtId, stmtHolder);
    } else {
      stmtHolder = connHolder.registerResultSet(null, null, INVALID_ID, rs,
          cursorId, sql, recordStatementStartTime);
    }
    this.resultSetMap.putPrimitive(cursorId, stmtHolder);
    return stmtHolder;
  }

  private ResultSetHolder registerResultSet(long cursorId, ResultSet rs,
      StatementHolder stmtHolder) {
    ResultSetHolder holder;
    final long stmtId = stmtHolder.getStatementId();
    if (stmtId != INVALID_ID) {
      holder = stmtHolder.getConnectionHolder().registerResultSet(stmtHolder,
          rs, cursorId);
      this.statementMap.putPrimitive(stmtId, stmtHolder);
    } else {
      holder = stmtHolder.addResultSet(rs, cursorId);
    }
    this.resultSetMap.putPrimitive(cursorId, stmtHolder);
    return holder;
  }

  private boolean processPendingTransactionAttributes(StatementAttrs attrs,
      EngineConnection conn) throws SnappyException {
    if (attrs == null) {
      return false;
    }
    Map<TransactionAttribute, Boolean> pendingTXAttrs = attrs
        .getPendingTransactionAttrs();
    if (pendingTXAttrs != null && !pendingTXAttrs.isEmpty()) {
      beginOrAlterTransaction(conn, snappydataConstants.TRANSACTION_NO_CHANGE,
          pendingTXAttrs, false);
    }
    if (attrs.possibleDuplicate) {
      conn.setPossibleDuplicate(true);
      return true;
    } else {
      return false;
    }
  }

  private void cleanupResultSet(ResultSet rs) {
    if (rs != null) {
      try {
        rs.close();
      } catch (Exception e) {
        // ignored
      }
    }
  }

  private void cleanupStatement(EngineStatement stmt) {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (Exception e) {
        // ignored
      }
    }
  }

  private void checkSystemFailure(Throwable t) {
    final Error err;
    if (t instanceof Error && SystemFailure.isJVMFailureError(
        err = (Error)t)) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    }
    // Whenever you catch Error or Throwable, you must also
    // check for fatal JVM error (see above). However, there is
    // _still_ a possibility that you are dealing with a cascading
    // error condition, so you also need to check to see if the JVM
    // is still usable.
    SystemFailure.checkFailure();
  }

  private boolean ignoreNonFatalException(Throwable t) {
    checkSystemFailure(t);
    // ignore for node going down
    return GemFireXDUtils.nodeFailureException(t);
  }

  private StatementAttrs applyMergeAttributes(StatementAttrs source,
      StatementAttrs target, EngineConnection conn, Statement stmt)
      throws SQLException {
    if (target == null) {
      target = source;
    } else if (source != null) {
      // copy over the attributes used by getRowSet()
      if (source.isSetBatchSize() && !target.isSetBatchSize()) {
        target.setBatchSize(source.getBatchSize());
      }
      if (source.isSetFetchReverse() && !target.isSetFetchReverse()) {
        target.setFetchReverse(source.isFetchReverse());
      }
      if (source.isSetLobChunkSize() && !target.isSetLobChunkSize()) {
        target.setLobChunkSize(source.getLobChunkSize());
      }
      if (source.isSetBucketIds() && !target.isSetBucketIds()) {
        target.setBucketIds(source.getBucketIds());
        target.setBucketIdsTable(source.getBucketIdsTable());
      }
      if (source.isSetRetainBucketIds() && !target.isSetRetainBucketIds()) {
        target.setRetainBucketIds(source.isRetainBucketIds());
      }
      if (source.isSetMetadataVersion() && !target.isSetMetadataVersion()) {
        target.setMetadataVersion(source.getMetadataVersion());
      }
      if (source.isSetSnapshotTransactionId() && !target.isSetSnapshotTransactionId()) {
        target.setSnapshotTransactionId(source.getSnapshotTransactionId());
      }
    }
    if (target != null) {
      // apply the attributes to statement and connection
      if (stmt != null) {
        if (target.isSetTimeout()) {
          stmt.setQueryTimeout(target.getTimeout());
        }
        if (target.isSetMaxRows()) {
          stmt.setMaxRows(target.getMaxRows());
        }
        if (target.isSetMaxFieldSize()) {
          stmt.setMaxFieldSize(target.getMaxFieldSize());
        }
        if (target.isSetCursorName()) {
          stmt.setCursorName(target.getCursorName());
        }
      }
      if (target.isSetBucketIds()) {
        GfxdSystemProcedures.setBucketsForLocalExecution(
            target.getBucketIdsTable(), target.getBucketIds(),
            target.isRetainBucketIds(), conn.getLanguageConnectionContext());
      }
      if (target.isSetMetadataVersion()) {
        final GfxdDistributionAdvisor.GfxdProfile profile = GemFireXDUtils.
            getGfxdProfile(Misc.getMyId());
        final int actualVersion = profile.getRelationDestroyVersion();
        final int metadataVersion = target.getMetadataVersion();
        if (metadataVersion != -1 && actualVersion != metadataVersion) {
          throw Util.generateCsSQLException(
              SQLState.SNAPPY_RELATION_DESTROY_VERSION_MISMATCH);
        }
      }
      if (target.isSetSnapshotTransactionId()) {
        GfxdSystemProcedures.useSnapshotTXId(target.getSnapshotTransactionId(),
            conn.getLanguageConnectionContext());
      }
    }
    return target;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatementResult execute(long connId, String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs,
      ByteBuffer token) throws SnappyException {

    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    EngineStatement stmt = null;
    ResultSet rs = null;
    boolean posDup = false;
    try {
      connHolder = getValidConnection(connId, token);
      conn = connHolder.getConnection();
      final String initialDefaultSchema = conn.getCurrentSchemaName();

      // first process any pending TX & other flags in StatementAttrs
      posDup = processPendingTransactionAttributes(attrs, conn);

      // Check if user has provided output parameters i.e. CallableStatement
      if (outputParams != null && !outputParams.isEmpty()) {
        // TODO: implement this case by adding support for output parameters
        // to EngineStatement itself (actually outputParams is not required
        // at all by the execution engine itself)
        // Also take care of open LOBs so avoid closing statement
        // for autocommit==true
        throw notImplementedException("unprepared CALL with output");
      } else { // Case : New statement Object
        stmt = connHolder.createNewStatement(attrs);
      }
      StatementHolder stmtHolder = connHolder.newStatementHolder(stmt, attrs,
          getNextId(this.currentStatementId), sql, recordStatementStartTime,
          "EXECUTING");
      connHolder.setActiveStatement(stmtHolder);
      applyMergeAttributes(null, attrs, conn, stmt);
      // Now we have valid statement object and valid connect id.
      // Create new empty StatementResult.
      StatementResult sr = createEmptyStatementResult();
      RowSet rowSet;

      if (stmt.execute(sql)) { // Case : result is a ResultSet
        stmtHolder.setStatus("FILLING RESULT SET");
        rs = stmt.getResultSet();
        rowSet = getRowSet(stmt, stmtHolder, rs, INVALID_ID, null, connId,
            attrs, 0, false, false, 0, connHolder, sql);
        sr.setResultSet(rowSet);
      } else { // Case : result is update count
        stmtHolder.setStatus("FILLING UPDATE COUNT");
        sr.setUpdateCount(stmt.getUpdateCount());
        rs = stmt.getGeneratedKeys();
        if (rs != null) {
          rowSet = getRowSet(stmt, null, rs, INVALID_ID, null, connId, attrs,
              0, false, false, 0, connHolder, "getGeneratedKeys");
          sr.setGeneratedKeys(rowSet);
        }
        String newDefaultSchema = conn.getCurrentSchemaName();
        // noinspection StringEquality
        if (initialDefaultSchema != newDefaultSchema) {
          sr.setNewDefaultSchema(newDefaultSchema);
        }
      }
      connHolder.clearActiveStatement(stmt);
      // don't attempt stmt cleanup after this point since we are reusing it
      final EngineStatement st = stmt;
      stmt = null;

      fillWarnings(sr, st);

      if (rs == null) {
        // setup the Statement for reuse
        connHolder.setStatementForReuse(st);
      }
      if (posDup) {
        conn.setPossibleDuplicate(false);
      }
      return sr;
    } catch (Throwable t) {
      if (posDup && conn != null) {
        conn.setPossibleDuplicate(false);
      }
      cleanupResultSet(rs);
      if (stmt != null) {
        connHolder.clearActiveStatement(stmt);
        cleanupStatement(stmt);
      }
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public UpdateResult executeUpdate(long connId, List<String> sqls,
      StatementAttrs attrs, ByteBuffer token) throws SnappyException {

    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    EngineStatement stmt = null;
    ResultSet rs = null;
    UpdateResult result;
    boolean posDup = false;
    try {
      connHolder = getValidConnection(connId, token);
      conn = connHolder.getConnection();
      final String initialDefaultSchema = conn.getCurrentSchemaName();

      // first process any pending TX & other flags in StatementAttrs
      posDup = processPendingTransactionAttributes(attrs, conn);

      stmt = connHolder.createNewStatement(attrs);
      final boolean singleUpdate = sqls.size() == 1;
      StatementHolder stmtHolder = connHolder.newStatementHolder(stmt, attrs,
          getNextId(this.currentStatementId), sqls, recordStatementStartTime,
          singleUpdate ? "EXECUTING UPDATE" : "EXECUTING BATCH UPDATE");
      connHolder.setActiveStatement(stmtHolder);
      applyMergeAttributes(null, attrs, conn, stmt);
      if (singleUpdate) {
        int updateCount = stmt.executeUpdate(sqls.get(0));
        stmtHolder.setStatus("FILLING UPDATE COUNT");
        result = new UpdateResult();
        result.setUpdateCount(updateCount);
      } else {
        stmt.clearBatch();
        for (String sql : sqls) {
          stmt.addBatch(sql);
        }
        int[] batchUpdateCounts = stmt.executeBatch();
        stmtHolder.setStatus("FILLING BATCH UPDATE COUNTS");
        result = new UpdateResult();
        for (int count : batchUpdateCounts) {
          result.addToBatchUpdateCounts(count);
        }
      }

      rs = stmt.getGeneratedKeys();
      if (rs != null) {
        RowSet rowSet = getRowSet(stmt, null, rs, INVALID_ID, null, connId,
            attrs, 0, false, false, 0, connHolder, "getGeneratedKeys");
        result.setGeneratedKeys(rowSet);
      }
      connHolder.clearActiveStatement(stmt);

      String newDefaultSchema = conn.getCurrentSchemaName();
      // noinspection StringEquality
      if (initialDefaultSchema != newDefaultSchema) {
        result.setNewDefaultSchema(newDefaultSchema);
      }

      // don't attempt stmt cleanup after this point since we are reusing it
      final EngineStatement st = stmt;
      stmt = null;

      fillWarnings(result, st);

      if (rs == null) {
        // setup the Statement for reuse
        connHolder.setStatementForReuse(st);
      }
      if (posDup) {
        conn.setPossibleDuplicate(false);
      }
      return result;
    } catch (Throwable t) {
      if (posDup && conn != null) {
        conn.setPossibleDuplicate(false);
      }
      cleanupResultSet(rs);
      if (stmt != null) {
        connHolder.clearActiveStatement(stmt);
        cleanupStatement(stmt);
      }
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (stmt != null && sqls != null && sqls.size() > 1) {
        stmt.forceClearBatch();
      }
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet executeQuery(long connId, final String sql,
      StatementAttrs attrs, ByteBuffer token) throws SnappyException {

    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    EngineStatement stmt = null;
    ResultSet rs = null;
    boolean posDup = false;
    try {
      connHolder = getValidConnection(connId, token);
      conn = connHolder.getConnection();

      // first process any pending TX & other flags in StatementAttrs
      posDup = processPendingTransactionAttributes(attrs, conn);

      stmt = connHolder.createNewStatement(attrs);
      StatementHolder stmtHolder = connHolder.newStatementHolder(stmt, attrs,
          getNextId(this.currentStatementId), sql, recordStatementStartTime,
          "EXECUTING QUERY");
      connHolder.setActiveStatement(stmtHolder);
      applyMergeAttributes(null, attrs, conn, stmt);
      rs = stmt.executeQuery(sql);
      stmtHolder.setStatus("FILLING RESULT SET");
      RowSet rowSet = getRowSet(stmt, stmtHolder, rs, INVALID_ID, null, connId,
          attrs, 0, false, false, 0, connHolder, sql);
      connHolder.clearActiveStatement(stmt);
      // don't attempt stmt cleanup after this point since we are reusing it
      stmt = null;

      if (posDup) {
        conn.setPossibleDuplicate(false);
      }
      return rowSet;
    } catch (Throwable t) {
      if (posDup && conn != null) {
        conn.setPossibleDuplicate(false);
      }
      cleanupResultSet(rs);
      if (stmt != null) {
        connHolder.clearActiveStatement(stmt);
        cleanupStatement(stmt);
      }
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (conn != null && (attrs != null && attrs.isSetBucketIds())) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PrepareResult prepareStatement(long connId, String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs,
      ByteBuffer token) throws SnappyException {

    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    PreparedStatement pstmt = null;
    StatementHolder stmtHolder = null;
    boolean posDup = false;
    try {
      connHolder = getValidConnection(connId, token);
      conn = connHolder.getConnection();

      // first process any pending TX & other flags in StatementAttrs
      posDup = processPendingTransactionAttributes(attrs, conn);

      long pstmtId;
      ArrayList<ColumnDescriptor> pmDescs;
      SnappyExceptionData sqlw = null;

      applyMergeAttributes(null, attrs, conn, null);
      final int resultSetType = getResultType(attrs);
      final int resultSetConcurrency = getResultSetConcurrency(attrs);
      final int resultSetHoldability = getResultSetHoldability(attrs);
      if (attrs != null && attrs.isSetRequireAutoIncCols()
          && attrs.requireAutoIncCols) {
        if (outputParams != null && !outputParams.isEmpty()) {
          throw newSnappyException(SQLState.REQUIRES_CALLABLE_STATEMENT,
              "AUTOINC not valid with output parameters: " + sql);
        }
        final List<Integer> autoIncCols = attrs.autoIncColumns;
        final List<String> autoIncColNames;
        int nCols;
        if (autoIncCols != null && (nCols = autoIncCols.size()) > 0) {
          int[] aiCols = new int[nCols];
          for (int index = 0; index < nCols; index++) {
            aiCols[index] = autoIncCols.get(index);
          }
          pstmt = conn.prepareStatement(sql, resultSetType,
              resultSetConcurrency, resultSetHoldability, aiCols);
        } else if ((autoIncColNames = attrs.autoIncColumnNames) != null
            && (nCols = autoIncColNames.size()) > 0) {
          pstmt = conn.prepareStatement(sql, resultSetType,
              resultSetConcurrency, resultSetHoldability,
              autoIncColNames.toArray(new String[nCols]));
        } else {
          pstmt = conn.prepareStatement(sql, resultSetType,
              resultSetConcurrency, resultSetHoldability,
              Statement.RETURN_GENERATED_KEYS);
        }
      } else {
        CallableStatement cstmt;
        pstmt = cstmt = conn.prepareCall(sql, resultSetType,
            resultSetConcurrency, resultSetHoldability);
        registerOutputParameters(cstmt, outputParams);
      }
      // prepared result consists of ID + ParameterMetaData
      ParameterMetaData pmd = pstmt.getParameterMetaData();
      int numParams = pmd.getParameterCount();
      pmDescs = new ArrayList<>(numParams);
      int pmType, mode, nullable, scale;
      SnappyType type;
      String typeName, className;
      for (int paramPosition = 1; paramPosition <= numParams; paramPosition++) {
        pmType = pmd.getParameterType(paramPosition);
        ColumnDescriptor pmDesc = new ColumnDescriptor();
        type = Converters.getThriftSQLType(pmType);
        mode = pmd.getParameterMode(paramPosition);
        switch (mode) {
          case ParameterMetaData.parameterModeIn:
            pmDesc.setParameterIn(true);
            break;
          case ParameterMetaData.parameterModeOut:
            pmDesc.setParameterOut(true);
            break;
          case ParameterMetaData.parameterModeInOut:
            pmDesc.setParameterIn(true);
            pmDesc.setParameterOut(true);
            break;
          default:
            pmDesc.setParameterIn(true);
            break;
        }
        nullable = pmd.isNullable(paramPosition);
        if (nullable == ParameterMetaData.parameterNullable) {
          pmDesc.setNullable(true);
        } else if (nullable == ParameterMetaData.parameterNoNulls) {
          pmDesc.setNullable(false);
        }
        pmDesc.setType(type);
        pmDesc.setPrecision((short)pmd.getPrecision(paramPosition));
        scale = pmd.getScale(paramPosition);
        if (scale != 0) {
          pmDesc.setScale((short)scale);
        }
        if (pmType == Types.JAVA_OBJECT) {
          typeName = pmd.getParameterTypeName(paramPosition);
          className = pmd.getParameterClassName(paramPosition);
          if (className != null) {
            pmDesc.setUdtTypeAndClassName(typeName + ':' + className);
          } else {
            pmDesc.setUdtTypeAndClassName(typeName);
          }
        }
        pmDescs.add(pmDesc);
      }
      // any warnings
      SQLWarning warnings = pstmt.getWarnings();
      if (warnings != null) {
        sqlw = snappyWarning(warnings);
      }
      pstmtId = getNextId(this.currentStatementId);
      stmtHolder = connHolder.registerPreparedStatement(pstmt, attrs, pstmtId,
          sql, recordStatementStartTime);
      this.statementMap.putPrimitive(pstmtId, stmtHolder);

      stmtHolder.setStatus("FILLING PREPARE RESULT");
      byte statementType;
      if (pstmt instanceof EnginePreparedStatement) {
        switch (((EnginePreparedStatement)pstmt).getStatementType()) {
          case StatementType.UNKNOWN:
            statementType = snappydataConstants.STATEMENT_TYPE_SELECT;
            break;
          case StatementType.UPDATE:
            statementType = snappydataConstants.STATEMENT_TYPE_UPDATE;
            break;
          case StatementType.INSERT:
          case StatementType.BULK_INSERT_REPLACE:
            statementType = snappydataConstants.STATEMENT_TYPE_INSERT;
            break;
          case StatementType.DELETE:
            statementType = snappydataConstants.STATEMENT_TYPE_DELETE;
            break;
          case StatementType.CALL_STATEMENT:
          case StatementType.DISTRIBUTED_PROCEDURE_CALL:
            statementType = snappydataConstants.STATEMENT_TYPE_CALL;
            break;
          default:
            statementType = snappydataConstants.STATEMENT_TYPE_DDL;
            break;

        }
      } else {
        String firstToken = ClientSharedUtils.getStatementToken(sql, 0);
        if (firstToken == null || firstToken.equalsIgnoreCase("select")) {
          statementType = snappydataConstants.STATEMENT_TYPE_SELECT;
        } else if (firstToken.equalsIgnoreCase("update")) {
          statementType = snappydataConstants.STATEMENT_TYPE_UPDATE;
        } else if (firstToken.equalsIgnoreCase("insert")) {
          statementType = snappydataConstants.STATEMENT_TYPE_INSERT;
        } else if (firstToken.equalsIgnoreCase("delete")) {
          statementType = snappydataConstants.STATEMENT_TYPE_DELETE;
        } else if (firstToken.equalsIgnoreCase("call")) {
          statementType = snappydataConstants.STATEMENT_TYPE_CALL;
        } else {
          statementType = snappydataConstants.STATEMENT_TYPE_DDL;
        }
      }
      PrepareResult result = new PrepareResult(pstmtId, statementType, pmDescs);
      if (sqlw != null) {
        result.setWarnings(sqlw);
      }
      // fill in ResultSet meta-data
      ResultSetMetaData rsmd = pstmt.getMetaData();
      if (rsmd != null) {
        result.setResultSetMetaData(getRowSetMetaData(rsmd,
            rsmd.getColumnCount(), connHolder.useStringForDecimal()));
      }

      if (posDup) {
        conn.setPossibleDuplicate(false);
      }
      return result;
    } catch (Throwable t) {
      if (posDup && conn != null) {
        conn.setPossibleDuplicate(false);
      }
      if (pstmt != null) {
        if (stmtHolder != null) {
          connHolder.closeStatement(stmtHolder, this);
        }
        try {
          pstmt.close();
        } catch (Exception e) {
          // ignore
        }
      }
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (pstmt != null) {
        connHolder.clearActiveStatement(pstmt);
      }
    }
  }

  private void registerOutputParameters(CallableStatement cstmt,
      Map<Integer, OutputParameter> outputParams) throws SQLException {
    if (outputParams != null && !outputParams.isEmpty()) {
      OutputParameter outParam;
      int jdbcType;
      for (Map.Entry<Integer, OutputParameter> param : outputParams
          .entrySet()) {
        outParam = param.getValue();
        jdbcType = Converters.getJdbcType(outParam.type);
        int paramPosition = param.getKey();
        if (outParam.isSetScale()) {
          cstmt.registerOutParameter(paramPosition, jdbcType, outParam.scale);
        } else if (outParam.isSetTypeName()) {
          cstmt.registerOutParameter(paramPosition, jdbcType, outParam.typeName);
        } else {
          cstmt.registerOutParameter(paramPosition, jdbcType);
        }
      }
    }
  }

  private void fillOutputParameterValues(CallableStatement cstmt,
      ParameterMetaData pmd, Map<Integer, OutputParameter> outputParams,
      ConnectionHolder connHolder, StatementAttrs attrs,
      StatementResult stmtResult) throws SQLException {
    final int numParams = pmd.getParameterCount();
    final HashMap<Integer, ColumnValue> outParams =
        new HashMap<>(outputParams.size());
    int paramMode;
    for (int paramPosition = 1; paramPosition <= numParams; paramPosition++) {
      paramMode = pmd.getParameterMode(paramPosition);
      if (paramMode == ParameterMetaData.parameterModeInOut ||
          paramMode == ParameterMetaData.parameterModeOut) {
        outParams.put(paramPosition, getColumnValue(cstmt, paramPosition,
            pmd.getParameterType(paramPosition), connHolder, attrs));
      }
    }
    stmtResult.setProcedureOutParams(outParams);
  }

  private void updateParameters(Row params, PreparedStatement pstmt,
      ParameterMetaData pmd, EngineConnection conn) throws SQLException {
    if (params != null) {
      final int numParams = params.size();
      for (int paramPosition = 1; paramPosition <= numParams; paramPosition++) {
        final int index = paramPosition - 1;
        // skip output-only parameters
        if (pmd != null && pmd.getParameterMode(
            paramPosition) == ParameterMetaData.parameterModeOut) {
          continue;
        }
        final int paramType = params.getType(index);
        Object paramVal;
        switch (Math.abs(paramType)) {
          case 9: // CHAR
          case 10: // VARCHAR
          case 11: // LONGVARCHAR
            if (paramType > 0) {
              pstmt.setString(paramPosition, (String)params.getObject(index));
            } else {
              pstmt.setNull(paramPosition, Converters.getJdbcType(
                  SnappyType.findByValue(paramType)));
            }
            break;
          case 4: // INTEGER
            if (paramType > 0) {
              pstmt.setInt(paramPosition, params.getInt(index));
            } else {
              pstmt.setNull(paramPosition, Types.INTEGER);
            }
            break;
          case 5: // BIGINT
            if (paramType > 0) {
              pstmt.setLong(paramPosition, params.getLong(index));
            } else {
              pstmt.setNull(paramPosition, Types.BIGINT);
            }
            break;
          case 12: // DATE
            if (paramType > 0) {
              pstmt.setDate(paramPosition, params.getDate(index));
            } else {
              pstmt.setNull(paramPosition, Types.DATE);
            }
            break;
          case 14: // TIMESTAMP
            if (paramType > 0) {
              pstmt.setTimestamp(paramPosition, params.getTimestamp(index));
            } else {
              pstmt.setNull(paramPosition, Types.TIMESTAMP);
            }
            break;
          case 7: // DOUBLE
            if (paramType > 0) {
              pstmt.setDouble(paramPosition, params.getDouble(index));
            } else {
              pstmt.setNull(paramPosition, Types.DOUBLE);
            }
            break;
          case 8: // DECIMAL
            if (paramType > 0) {
              pstmt.setBigDecimal(paramPosition,
                  (BigDecimal)params.getObject(index));
            } else {
              pstmt.setNull(paramPosition, Types.DECIMAL);
            }
            break;
          case 6: // FLOAT
            if (paramType > 0) {
              pstmt.setFloat(paramPosition, params.getFloat(index));
            } else {
              pstmt.setNull(paramPosition, Types.REAL);
            }
            break;
          case 3: // SMALLINT
            if (paramType > 0) {
              pstmt.setShort(paramPosition, params.getShort(index));
            } else {
              pstmt.setNull(paramPosition, Types.SMALLINT);
            }
            break;
          case 1: // BOOLEAN
            if (paramType > 0) {
              pstmt.setBoolean(paramPosition, params.getBoolean(index));
            } else {
              pstmt.setNull(paramPosition, Types.BOOLEAN);
            }
            break;
          case 2: // TINYINT
            if (paramType > 0) {
              pstmt.setByte(paramPosition, params.getByte(index));
            } else {
              pstmt.setNull(paramPosition, Types.TINYINT);
            }
            break;
          case 13: // TIME
            if (paramType > 0) {
              pstmt.setTime(paramPosition, params.getTime(index));
            } else {
              pstmt.setNull(paramPosition, Types.TIME);
            }
            break;
          case 18: // BLOB
            if (paramType <= 0) {
              pstmt.setNull(paramPosition, Types.BLOB);
            } else if ((paramVal = params.getObject(index)) instanceof byte[]) {
              pstmt.setBytes(paramPosition, (byte[])paramVal);
            } else {
              BlobChunk chunk = (BlobChunk)paramVal;
              Blob blob;
              // if blob chunks were sent separately, then lookup that blob
              // else create a new one
              if (chunk.isSetLobId()) {
                Object lob = conn.getLOBMapping(chunk.lobId);
                if (lob instanceof Blob) {
                  blob = (Blob)lob;
                } else {
                  throw Util.generateCsSQLException(
                      SQLState.LOB_LOCATOR_INVALID);
                }
              } else if (chunk.last) {
                // set as a Blob
                pstmt.setBlob(paramPosition, new ClientBlob(chunk.chunk));
                break;
              } else {
                blob = conn.createBlob();
              }
              long offset = 1;
              if (chunk.isSetOffset()) {
                offset += chunk.offset;
              }
              // TODO: need an EmbedBlob that can deal directly with BlobChunks
              blob.setBytes(offset, chunk.getChunk());
              // free any direct buffer immediately
              chunk.free();
              pstmt.setBlob(paramPosition, blob);
            }
            break;
          case 19: // CLOB
          case 20: // SQLXML
          case 25: // JSON
            if (paramType <= 0) {
              pstmt.setNull(paramPosition, paramType == 25 /* JSON */
                  ? JDBC40Translation.JSON : Types.CLOB);
            } else if ((paramVal = params.getObject(index)) instanceof String) {
              pstmt.setString(paramPosition, (String)paramVal);
            } else {
              Clob clob;
              ClobChunk chunk = (ClobChunk)paramVal;
              // if clob chunks were sent separately, then lookup that clob
              // else create a new one
              if (chunk.isSetLobId()) {
                Object lob = conn.getLOBMapping(chunk.lobId);
                if (lob instanceof Clob) {
                  clob = (Clob)lob;
                } else {
                  throw Util.generateCsSQLException(
                      SQLState.LOB_LOCATOR_INVALID);
                }
              } else if (chunk.last) {
                // set as a normal String
                pstmt.setString(paramPosition, chunk.getChunk());
                break;
              } else {
                clob = conn.createClob();
              }
              long offset = 1;
              if (chunk.isSetOffset()) {
                offset += chunk.offset;
              }
              clob.setString(offset, chunk.getChunk());
              pstmt.setClob(paramPosition, clob);
            }
            break;
          case 15: // BINARY
          case 16: // VARBINARY
          case 17: // LONGVARBINARY
            if (paramType > 0) {
              pstmt.setBytes(paramPosition, (byte[])params.getObject(index));
            } else {
              pstmt.setNull(paramPosition, Converters.getJdbcType(
                  SnappyType.findByValue(paramType)));
            }
            break;
          case 24: // NULLTYPE
            pstmt.setNull(paramPosition, Types.NULL);
            break;
          case 26: // JAVA_OBJECT
            if (paramType > 0) {
              pstmt.setObject(paramPosition, ((Converters.JavaObjectWrapper)
                  params.getObject(index)).getDeserialized(paramPosition,
                  javaObjectCreator));
            } else {
              pstmt.setNull(paramPosition, Types.JAVA_OBJECT);
            }
            break;
          default:
            SnappyType type = SnappyType.findByValue(paramType);
            throw Util.generateCsSQLException(SQLState.DATA_TYPE_NOT_SUPPORTED,
                type != null ? type.toString() : Integer.toString(paramType));
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatementResult executePrepared(long stmtId, final Row params,
      final Map<Integer, OutputParameter> outputParams, StatementAttrs attrs,
      ByteBuffer token) throws SnappyException {
    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    PreparedStatement pstmt = null;
    CallableStatement cstmt = null;
    ParameterMetaData pmd = null;
    ResultSet rs = null;
    RowSet rowSet = null;
    // prepared statement executions do not have posDup handling since
    // client-side will need to do prepare+execute after failure so only
    // prepare needs to handle posDup
    try {
      // Validate the statement Id
      StatementHolder stmtHolder = getStatement(token, stmtId, true,
          "executePrepared");
      connHolder = stmtHolder.getConnectionHolder();
      final long connId = connHolder.getConnectionId();
      conn = connHolder.getConnection();
      final String initialDefaultSchema = conn.getCurrentSchemaName();
      pstmt = (PreparedStatement)stmtHolder.getStatement();
      stmtHolder.setStatus("EXECUTING PREPARED");
      stmtHolder.incrementAccessFrequency();
      connHolder.setActiveStatement(stmtHolder);
      attrs = applyMergeAttributes(stmtHolder.getStatementAttrs(), attrs,
          conn, pstmt);

      if (outputParams != null && !outputParams.isEmpty()) {
        if (pstmt instanceof CallableStatement) {
          cstmt = (CallableStatement)pstmt;
          pmd = cstmt.getParameterMetaData();
          registerOutputParameters(cstmt, outputParams);
          stmtHolder.setStatus("EXECUTING PREPARED CALL");
        } else {
          throw newSnappyException(SQLState.REQUIRES_CALLABLE_STATEMENT,
              stmtHolder.getSQL());
        }
      }
      StatementResult stmtResult = createEmptyStatementResult();
      // clear any existing return parameters first
      pstmt.clearParameters();
      updateParameters(params, pstmt, pmd, conn);

      final boolean resultType = pstmt.execute();
      if (resultType) { // Case : result is a ResultSet
        stmtHolder.setStatus("FILLING RESULT SET");
        rs = pstmt.getResultSet();
        rowSet = getRowSet(pstmt, stmtHolder, rs, INVALID_ID, null,
            connId, attrs, 0, false, false, 0, connHolder,
            null /* already set */);
        stmtResult.setResultSet(rowSet);
        // set the output parameters if required
        if (cstmt != null) {
          fillOutputParameterValues(cstmt, pmd, outputParams, connHolder,
              attrs, stmtResult);
        }
      } else { // Case : result is update count
        stmtHolder.setStatus("FILLING UPDATE COUNT");
        stmtResult.setUpdateCount(pstmt.getUpdateCount());
        rs = pstmt.getGeneratedKeys();
        if (rs != null) {
          rowSet = getRowSet(pstmt, stmtHolder, rs, INVALID_ID, null,
              connId, attrs, 0, false, false, 0, connHolder,
              "getGeneratedKeys");
          stmtResult.setGeneratedKeys(rowSet);
        }
        String newDefaultSchema = conn.getCurrentSchemaName();
        // noinspection StringEquality
        if (initialDefaultSchema != newDefaultSchema) {
          stmtResult.setNewDefaultSchema(newDefaultSchema);
        }
      }
      // set the output parameters if required
      if (cstmt != null) {
        fillOutputParameterValues(cstmt, pmd, outputParams, connHolder,
            attrs, stmtResult);
      }
      fillWarnings(stmtResult, pstmt);
      return stmtResult;
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (pstmt != null) {
        try {
          if (isLast(rowSet)) {
            pstmt.clearParameters();
          }
        } catch (Throwable t) {
          // ignore exceptions at this point
          checkSystemFailure(t);
        }
        connHolder.clearActiveStatement(pstmt);
      }
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public UpdateResult executePreparedUpdate(long stmtId,
      final Row params, StatementAttrs attrs, ByteBuffer token)
      throws SnappyException {
    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    RowSet rowSet = null;
    // prepared statement executions do not have posDup handling since
    // client-side will need to do prepare+execute after failure so only
    // prepare needs to handle posDup
    try {
      StatementHolder stmtHolder = getStatement(token, stmtId, true,
          "executePreparedUpdate");
      connHolder = stmtHolder.getConnectionHolder();
      final long connId = connHolder.getConnectionId();
      conn = connHolder.getConnection();
      final String initialDefaultSchema = conn.getCurrentSchemaName();
      pstmt = (PreparedStatement)stmtHolder.getStatement();
      stmtHolder.setStatus("EXECUTING PREPARED UPDATE");
      stmtHolder.incrementAccessFrequency();
      connHolder.setActiveStatement(stmtHolder);
      attrs = applyMergeAttributes(stmtHolder.getStatementAttrs(), attrs,
          conn, pstmt);
      // clear any existing parameters first
      pstmt.clearParameters();
      updateParameters(params, pstmt, null, conn);

      int updateCount = pstmt.executeUpdate();
      stmtHolder.setStatus("FILLING UPDATE COUNT");
      UpdateResult result = new UpdateResult();
      result.setUpdateCount(updateCount);

      rs = pstmt.getGeneratedKeys();
      if (rs != null) {
        rowSet = getRowSet(pstmt, stmtHolder, rs, INVALID_ID, null,
            connId, attrs, 0, false, false, 0, connHolder, "getGeneratedKeys");
        result.setGeneratedKeys(rowSet);
      }
      String newDefaultSchema = conn.getCurrentSchemaName();
      // noinspection StringEquality
      if (initialDefaultSchema != newDefaultSchema) {
        result.setNewDefaultSchema(newDefaultSchema);
      }
      fillWarnings(result, pstmt);
      return result;
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (pstmt != null) {
        try {
          if (isLast(rowSet)) {
            pstmt.clearParameters();
          }
        } catch (Throwable t) {
          // ignore exceptions at this point
          checkSystemFailure(t);
        }
        connHolder.clearActiveStatement(pstmt);
      }
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet executePreparedQuery(long stmtId, final Row params,
      StatementAttrs attrs, ByteBuffer token) throws SnappyException {
    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    RowSet rowSet = null;
    // prepared statement executions do not have posDup handling since
    // client-side will need to do prepare+execute after failure so only
    // prepare needs to handle posDup
    try {
      StatementHolder stmtHolder = getStatement(token, stmtId, true,
          "executePreparedQuery");
      connHolder = stmtHolder.getConnectionHolder();
      final long connId = connHolder.getConnectionId();
      conn = connHolder.getConnection();
      pstmt = (PreparedStatement)stmtHolder.getStatement();
      stmtHolder.setStatus("EXECUTING PREPARED QUERY");
      stmtHolder.incrementAccessFrequency();
      connHolder.setActiveStatement(stmtHolder);
      attrs = applyMergeAttributes(stmtHolder.getStatementAttrs(), attrs,
          conn, pstmt);
      // clear any existing parameters first
      pstmt.clearParameters();
      updateParameters(params, pstmt, null, conn);

      rs = pstmt.executeQuery();
      stmtHolder.setStatus("FILLING RESULT SET");
      rowSet = getRowSet(pstmt, stmtHolder, rs, INVALID_ID, null, connId,
          attrs, 0, false, false, 0, connHolder, null /* already set */);
      return rowSet;
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (pstmt != null) {
        try {
          if (isLast(rowSet)) {
            pstmt.clearParameters();
          }
        } catch (Throwable t) {
          // ignore exceptions at this point
          checkSystemFailure(t);
        }
        connHolder.clearActiveStatement(pstmt);
      }
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public UpdateResult executePreparedBatch(long stmtId, List<Row> paramsBatch,
      StatementAttrs attrs, ByteBuffer token) throws SnappyException {
    ConnectionHolder connHolder = null;
    EngineConnection conn = null;
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    RowSet rowSet = null;
    // prepared statement executions do not have posDup handling since
    // client-side will need to do prepare+execute after failure so only
    // prepare needs to handle posDup
    try {
      StatementHolder stmtHolder = getStatement(token, stmtId, true,
          "executePreparedBatch");
      connHolder = stmtHolder.getConnectionHolder();
      final long connId = connHolder.getConnectionId();
      conn = connHolder.getConnection();
      pstmt = (PreparedStatement)stmtHolder.getStatement();
      stmtHolder.setStatus("EXECUTING PREPARED BATCH");
      stmtHolder.incrementAccessFrequency();
      connHolder.setActiveStatement(stmtHolder);
      attrs = applyMergeAttributes(stmtHolder.getStatementAttrs(), attrs,
          conn, pstmt);
      // clear any existing parameters first
      pstmt.clearParameters();
      pstmt.clearBatch();
      for (Row params : paramsBatch) {
        updateParameters(params, pstmt, null, conn);
        pstmt.addBatch();
      }
      int[] batchUpdateCounts = pstmt.executeBatch();
      stmtHolder.setStatus("FILLING BATCH UPDATE COUNTS");
      UpdateResult result = new UpdateResult();
      for (int count : batchUpdateCounts) {
        result.addToBatchUpdateCounts(count);
      }

      rs = pstmt.getGeneratedKeys();
      if (rs != null) {
        rowSet = getRowSet(pstmt, stmtHolder, rs, INVALID_ID, null,
            connId, attrs, 0, false, false, 0, connHolder, "getGeneratedKeys");
        result.setGeneratedKeys(rowSet);
      }

      fillWarnings(result, pstmt);
      return result;
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (pstmt != null) {
        try {
          if (isLast(rowSet)) {
            pstmt.clearParameters();
          }
        } catch (Throwable t) {
          // ignore exceptions at this point
          checkSystemFailure(t);
        }
        try {
          pstmt.clearBatch();
        } catch (Throwable t) {
          // ignore exceptions at this point
          checkSystemFailure(t);
        }
        connHolder.clearActiveStatement(pstmt);
      }
      if (conn != null && attrs != null && attrs.isSetBucketIds()) {
        conn.getLanguageConnectionContext().setExecuteLocally(
            null, null, false, null);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatementResult prepareAndExecute(long connId, String sql,
      final List<Row> paramsBatch, Map<Integer, OutputParameter> outputParams,
      StatementAttrs attrs, ByteBuffer token) throws SnappyException {
    PrepareResult prepResult = prepareStatement(connId, sql, outputParams,
        attrs, token);

    ConnectionHolder connHolder = null;
    final boolean posDup = (attrs != null && attrs.possibleDuplicate);
    try {
      StatementResult sr;

      if (posDup) {
        connHolder = getValidConnection(connId, token);
        connHolder.getConnection().setPossibleDuplicate(true);
      }
      if (paramsBatch.size() == 1) {
        sr = executePrepared(prepResult.statementId, paramsBatch.get(0),
            outputParams, attrs, token);
        // also copy the single update to list of updates
        // just in case user always reads the list of updates
        if (sr.updateCount >= 0) {
          sr.setBatchUpdateCounts(Collections.singletonList(sr.updateCount));
        }
      } else {
        UpdateResult ur = executePreparedBatch(prepResult.statementId,
            paramsBatch, attrs, token);
        sr = new StatementResult();
        sr.setBatchUpdateCounts(ur.getBatchUpdateCounts());
        sr.setGeneratedKeys(ur.getGeneratedKeys());
        sr.setNewDefaultSchema(ur.getNewDefaultSchema());
        sr.setWarnings(ur.getWarnings());
      }
      sr.setPreparedResult(prepResult);
      return sr;
    } finally {
      if (posDup && connHolder != null) {
        connHolder.getConnection().setPossibleDuplicate(false);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte beginTransaction(long connId, byte isolationLevel,
      Map<TransactionAttribute, Boolean> flags, ByteBuffer token)
      throws SnappyException {

    return beginOrAlterTransaction(getValidConnection(connId, token)
        .getConnection(), isolationLevel, flags, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTransactionAttributes(long connId,
      Map<TransactionAttribute, Boolean> flags, ByteBuffer token)
      throws SnappyException {
    if (flags != null && !flags.isEmpty()) {
      beginOrAlterTransaction(getValidConnection(connId, token).getConnection(),
          snappydataConstants.TRANSACTION_NO_CHANGE, flags, false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<TransactionAttribute, Boolean> getTransactionAttributes(
      long connId, ByteBuffer token) throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();

      final EnumMap<TransactionAttribute, Boolean> txAttrs = ThriftUtils
          .newTransactionFlags();
      EnumSet<TransactionFlag> txFlags = conn.getTransactionFlags();
      txAttrs.put(TransactionAttribute.AUTOCOMMIT, conn.getAutoCommit());
      txAttrs.put(TransactionAttribute.READ_ONLY_CONNECTION,
          conn.isReadOnly());
      if (txFlags != null) {
        txAttrs.put(TransactionAttribute.DISABLE_BATCHING,
            txFlags.contains(TransactionFlag.DISABLE_BATCHING));
        txAttrs.put(TransactionAttribute.SYNC_COMMITS,
            txFlags.contains(TransactionFlag.SYNC_COMMITS));
        txAttrs.put(TransactionAttribute.WAITING_MODE,
            txFlags.contains(TransactionFlag.WAITING_MODE));
      } else {
        txAttrs.put(TransactionAttribute.DISABLE_BATCHING, false);
        txAttrs.put(TransactionAttribute.SYNC_COMMITS, false);
        txAttrs.put(TransactionAttribute.WAITING_MODE, false);
      }
      return txAttrs;
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  private byte beginOrAlterTransaction(EngineConnection conn,
      byte isolationLevel, Map<TransactionAttribute, Boolean> flags,
      boolean commitExisting) throws SnappyException {

    try {
      EnumSet<TransactionFlag> txFlags = null;
      Boolean autoCommit = null;
      Boolean readOnly = null;
      boolean hasTXFlags = false;
      if (flags != null && flags.size() > 0) {
        txFlags = EnumSet.noneOf(TransactionFlag.class);
        for (Map.Entry<TransactionAttribute, Boolean> e : flags.entrySet()) {
          TransactionAttribute flag = e.getKey();
          switch (flag) {
            case AUTOCOMMIT:
              autoCommit = e.getValue();
              break;
            case DISABLE_BATCHING:
              if (e.getValue()) {
                txFlags.add(TransactionFlag.DISABLE_BATCHING);
              }
              hasTXFlags = true;
              break;
            case WAITING_MODE:
              if (e.getValue()) {
                txFlags.add(TransactionFlag.WAITING_MODE);
              }
              hasTXFlags = true;
              break;
            case SYNC_COMMITS:
              if (e.getValue()) {
                txFlags.add(TransactionFlag.SYNC_COMMITS);
              }
              hasTXFlags = true;
              break;
            case READ_ONLY_CONNECTION:
              readOnly = e.getValue();
              break;
          }
        }
        if (!hasTXFlags) {
          txFlags = null;
        }
      }
      if (readOnly != null) {
        conn.setReadOnly(readOnly);
      }
      if (autoCommit != null) {
        conn.setAutoCommit(autoCommit);
      }
      if (isolationLevel != snappydataConstants.TRANSACTION_NO_CHANGE) {
        conn.setTransactionIsolation(Converters.getJdbcIsolation(
            isolationLevel), txFlags);
        isolationLevel = Converters.getThriftTransactionIsolation(
            conn.getTransactionIsolation());
      } else {
        if (commitExisting) {
          conn.commit();
        }
        if (txFlags != null) {
          LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
          lcc.setTXFlags(txFlags);
        }
      }
      return isolationLevel;
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commitTransaction(long connId, final boolean startNewTransaction,
      Map<TransactionAttribute, Boolean> flags, ByteBuffer token)
      throws SnappyException {

    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      if (flags != null && !flags.isEmpty()) {
        beginOrAlterTransaction(conn, snappydataConstants.TRANSACTION_NO_CHANGE,
            flags, false);
      }
      conn.commit();
      LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      if (lcc != null) {
        lcc.clearExecuteLocally();
      }
      // JDBC starts a new transaction immediately; we need to set the isolation
      // explicitly to NONE to avoid that
      if (!startNewTransaction) {
        conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollbackTransaction(long connId,
      final boolean startNewTransaction,
      Map<TransactionAttribute, Boolean> flags, ByteBuffer token)
      throws SnappyException {

    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      if (flags != null && !flags.isEmpty()) {
        beginOrAlterTransaction(conn, snappydataConstants.TRANSACTION_NO_CHANGE,
            flags, false);
      }
      conn.rollback();
      LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      if (lcc != null) {
        lcc.clearExecuteLocally();
      }
      // JDBC starts a new transaction immediately; we need to set the isolation
      // explicitly to NONE to avoid that
      if (!startNewTransaction) {
        conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet scrollCursor(long cursorId, int offset,
      boolean offsetIsAbsolute, boolean fetchReverse, int fetchSize,
      ByteBuffer token) throws SnappyException {
    ConnectionHolder connHolder = null;
    Statement stmt = null;
    StatementAttrs attrs;
    RowSet rowSet = null;
    try {
      StatementHolder stmtHolder = getStatementForResultSet(token,
          cursorId, "scrollCursor");
      connHolder = stmtHolder.getConnectionHolder();
      final long connId = connHolder.getConnectionId();
      ResultSetHolder holder = stmtHolder.findResultSet(cursorId);
      if (holder != null) {
        stmt = stmtHolder.getStatement();
        stmtHolder.setStatus("SCROLLING CURSOR");
        stmtHolder.incrementAccessFrequency();
        connHolder.setActiveStatement(stmtHolder);
        attrs = stmtHolder.getStatementAttrs();
        rowSet = getRowSet(stmt, stmtHolder, holder.resultSet,
            holder.rsCursorId, holder, connId, attrs, offset,
            offsetIsAbsolute, fetchReverse, fetchSize, connHolder, null);
        return rowSet;
      } else {
        throw resultSetNotFoundException(cursorId, "scrollCursor");
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      // eagerly clear the parameters for last batch in FORWARD_ONLY cursor
      try {
        if (isLast(rowSet) && stmt != null &&
            stmt.getResultSetType() == ResultSet.TYPE_FORWARD_ONLY &&
            stmt instanceof PreparedStatement) {
          ((PreparedStatement)stmt).clearParameters();
        }
      } catch (Throwable t) {
        // ignore exceptions at this point
        checkSystemFailure(t);
      }
      if (connHolder != null) {
        connHolder.clearActiveStatement(stmt);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void executeCursorUpdate(long cursorId,
      List<CursorUpdateOperation> operations,
      List<Row> changedRows, List<List<Integer>> changedColumnsList,
      List<Integer> changedRowIndexes, ByteBuffer token) throws SnappyException {
    // TODO Auto-generated method stub
    throw notImplementedException("executeCursorUpdate");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void startXATransaction(long connId, TransactionXid xid,
      int timeoutInSeconds, int flags, ByteBuffer token)
      throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      xaResource.setTransactionTimeout(timeoutInSeconds);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      xaResource.start(xaXid, flags);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public int prepareXATransaction(long connId, TransactionXid xid,
      ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      return xaResource.prepare(xaXid);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public void commitXATransaction(long connId, TransactionXid xid,
      boolean onePhase, ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      xaResource.commit(xaXid, onePhase);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public void rollbackXATransaction(long connId, TransactionXid xid,
      ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      xaResource.rollback(xaXid);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public void forgetXATransaction(long connId, TransactionXid xid,
      ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      xaResource.forget(xaXid);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public void endXATransaction(long connId, TransactionXid xid,
      int flags, ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      XAXactId xaXid = new XAXactId(xid.getFormatId(), xid.getGlobalId(),
          xid.getBranchQualifier());
      xaResource.end(xaXid, flags);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  @Override
  public List<TransactionXid> recoverXATransaction(long connId, int flag,
      ByteBuffer token) throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      XAResource xaResource = getXAResource(connHolder);
      Xid[] result = xaResource.recover(flag);
      if (result != null && result.length > 0) {
        final ArrayList<TransactionXid> xids = new ArrayList<>(result.length);
        for (Xid xid : result) {
          xids.add(new TransactionXid().setFormatId(xid.getFormatId())
              .setGlobalId(xid.getGlobalTransactionId())
              .setBranchQualifier(xid.getBranchQualifier()));
        }
        return xids;
      } else {
        return new ArrayList<>(0);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet getNextResultSet(long cursorId, byte otherResultSetBehaviour,
      ByteBuffer token) throws SnappyException {
    ConnectionHolder connHolder = null;
    Statement stmt = null;
    ResultSet rs = null;
    try {
      StatementHolder stmtHolder = getStatementForResultSet(token, cursorId,
          "getNextResultSet");
      stmt = stmtHolder.getStatement();
      if (stmt == null) {
        // cannot return null result over thrift, so return empty RowSet;
        // empty descriptors will indicate absence of result to clients
        return createEmptyRowSet();
      }
      connHolder = stmtHolder.getConnectionHolder();
      stmtHolder.setStatus("CLOSE PREVIOUS RESULT SET");
      stmtHolder.incrementAccessFrequency();
      connHolder.setActiveStatement(stmtHolder);
      // close the previous ResultSet
      stmtHolder.closeResultSet(cursorId, this);

      stmtHolder.setStatus("GET NEXT RESULT SET");
      final boolean moreResults;
      if (otherResultSetBehaviour == 0) {
        moreResults = stmt.getMoreResults();
      } else {
        final int current;
        switch (otherResultSetBehaviour) {
          case snappydataConstants.NEXTRS_CLOSE_CURRENT_RESULT:
            current = EngineStatement.CLOSE_CURRENT_RESULT;
            break;
          case snappydataConstants.NEXTRS_KEEP_CURRENT_RESULT:
            current = EngineStatement.KEEP_CURRENT_RESULT;
            break;
          default:
            current = EngineStatement.CLOSE_ALL_RESULTS;
            break;
        }
        moreResults = stmt.getMoreResults(current);
      }
      if (moreResults) {
        rs = stmt.getResultSet();
        stmtHolder.setStatus("FILLING NEXT RESULT SET");
        return getRowSet(stmt, stmtHolder, rs, INVALID_ID, null,
            connHolder.getConnectionId(), stmtHolder.getStatementAttrs(), 0,
            false, false, 0, connHolder, null /* already set */);
      } else {
        // cannot return null result over thrift, so return empty RowSet;
        // empty descriptors will indicate absence of result to clients
        return createEmptyRowSet();
      }
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (connHolder != null) {
        connHolder.clearActiveStatement(stmt);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BlobChunk getBlobChunk(long connId, long lobId, long offset,
      int chunkSize, boolean freeLobAtEnd, ByteBuffer token)
      throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      Object lob = conn.getLOBMapping(lobId);
      if (lob instanceof Blob) {
        Blob blob = (Blob)lob;
        long length = blob.length() - offset;
        if (length > Integer.MAX_VALUE) {
          throw Util.generateCsSQLException(SQLState.BLOB_TOO_LARGE_FOR_CLIENT,
              Long.toString(length), Long.toString(Integer.MAX_VALUE));
        }
        BlobChunk chunk = new BlobChunk().setLobId(lobId).setOffset(offset);
        if (chunkSize > 0 && chunkSize < length) {
          chunk.chunk = ByteBuffer.wrap(blob.getBytes(offset + 1, chunkSize));
          chunk.setLast(false);
        } else {
          chunk = getAsLastChunk(blob, (int)length);
          if (freeLobAtEnd) {
            conn.removeLOBMapping(lobId);
            blob.free();
          }
        }
        return chunk;
      } else {
        throw Util.generateCsSQLException(SQLState.LOB_LOCATOR_INVALID);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClobChunk getClobChunk(long connId, long lobId, long offset,
      int chunkSize, boolean freeLobAtEnd, ByteBuffer token)
      throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      Object lob = conn.getLOBMapping(lobId);
      if (lob instanceof Clob) {
        Clob clob = (Clob)lob;
        long length = clob.length() - offset;
        if (length > Integer.MAX_VALUE) {
          throw Util.generateCsSQLException(SQLState.BLOB_TOO_LARGE_FOR_CLIENT,
              Long.toString(length), Long.toString(Integer.MAX_VALUE));
        }
        ClobChunk chunk = new ClobChunk().setLobId(lobId).setOffset(offset);
        if (chunkSize > 0 && chunkSize < length) {
          chunk.setChunk(clob.getSubString(offset + 1, chunkSize)).setLast(
              false);
        } else {
          chunk.setChunk(clob.getSubString(offset + 1, (int)length)).setLast(
              true);
          if (freeLobAtEnd) {
            conn.removeLOBMapping(lobId);
            clob.free();
          }
        }
        return chunk;
      } else {
        throw Util.generateCsSQLException(SQLState.LOB_LOCATOR_INVALID);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeResultSet(long cursorId, ByteBuffer token)
      throws SnappyException {
    if (cursorId == INVALID_ID) {
      return;
    }
    ConnectionHolder connHolder = null;
    Statement stmt = null;
    try {
      StatementHolder stmtHolder = getStatementForResultSet(token, cursorId,
          "closeResultSet");
      connHolder = stmtHolder.getConnectionHolder();
      stmtHolder.setStatus("CLOSING RESULT SET");
      connHolder.setActiveStatement(stmtHolder);
      stmt = stmtHolder.getStatement();

      stmtHolder.closeResultSet(cursorId, this);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (connHolder != null) {
        connHolder.clearActiveStatement(stmt);
      }
    }
  }

  private void checkDBOwner(long connId, ByteBuffer token, String module)
      throws SnappyException {
    // check for valid token
    ConnectionHolder connHolder = getValidConnection(connId, token);

    String authId;
    try {
      authId = IdUtil.getUserAuthorizationId(connHolder.getUserName());
    } catch (Exception e) {
      throw SnappyException(e);
    }
    if (!Misc.getMemStore().getDatabase().getDataDictionary()
        .getAuthorizationDatabaseOwner().equals(authId)) {
      throw newSnappyException(SQLState.LOGIN_FAILED,
          "administrator access required for " + module);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<ConnectionProperties> fetchActiveConnections(long connId,
      ByteBuffer token) throws SnappyException {
    // only allow admin user
    checkDBOwner(connId, token, "fetchActiveConnections");

    final ArrayList<ConnectionProperties> activeConns = new ArrayList<>(
        this.connectionMap.size());
    this.connectionMap.forEachValue(new TObjectProcedure() {
      @Override
      public boolean execute(Object h) {
        final ConnectionHolder connHolder = (ConnectionHolder)h;
        ConnectionProperties props = new ConnectionProperties(connHolder
            .getConnectionId(), connHolder.getClientHostName(), connHolder
            .getClientID());
        props.setUserName(connHolder.getUserName());
        activeConns.add(props);
        return true;
      }
    });
    return activeConns;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<Long, String> fetchActiveStatements(long connId, ByteBuffer token)
      throws SnappyException {
    // only allow admin user
    checkDBOwner(connId, token, "fetchActiveStatements");

    @SuppressWarnings("unchecked")
    final Map<Long, String> activeStmts = new THashMap(this.statementMap.size());
    this.statementMap.forEachValue(new TObjectProcedure() {
      @Override
      public boolean execute(Object h) {
        final StatementHolder stmtHolder = (StatementHolder)h;
        activeStmts.put(stmtHolder.getStatementId(),
            String.valueOf(stmtHolder.getSQL()));
        return true;
      }
    });
    return activeStmts;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void cancelStatement(long stmtId, ByteBuffer token)
      throws SnappyException {
    try {
      StatementHolder stmtHolder = getStatement(token, stmtId, false,
          "cancelStatement");
      stmtHolder.getStatement().cancel();
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void cancelCurrentStatement(long connId, ByteBuffer token)
      throws SnappyException {
    try {
      ConnectionHolder connHolder = getValidConnection(connId, token);
      // check for the current active statement
      Statement activeStatement = connHolder.uniqueActiveStatement(true);
      if (activeStatement != null) {
        activeStatement.cancel();
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeStatement(long stmtId, ByteBuffer token)
      throws SnappyException {
    ConnectionHolder connHolder = null;
    Statement stmt = null;
    try {
      StatementHolder stmtHolder = getStatement(token, stmtId, false,
          "closeStatement");
      connHolder = stmtHolder.getConnectionHolder();
      stmt = stmtHolder.getStatement();
      stmtHolder.setStatus("CLOSING STATEMENT");
      connHolder.setActiveStatement(stmtHolder);

      connHolder.closeStatement(stmtHolder, this);
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    } finally {
      if (connHolder != null) {
        connHolder.clearActiveStatement(stmt);
      }
    }
  }

  private SnappyExceptionData snappyWarning(SQLWarning warnings)
      throws SQLException {
    SnappyExceptionData warningData = new SnappyExceptionData(
        warnings.getMessage(), warnings.getErrorCode())
        .setSqlState(warnings.getSQLState());
    ArrayList<SnappyExceptionData> nextWarnings = null;
    SQLWarning next = warnings.getNextWarning();
    if (next != null) {
      nextWarnings = new ArrayList<>();
      do {
        nextWarnings.add(new SnappyExceptionData(next.getMessage(),
            next.getErrorCode()).setSqlState(next.getSQLState()));
      } while ((next = next.getNextWarning()) != null);
    }
    //SnappyExceptionData sqlw = new SnappyExceptionData(warningData);
    //sqlw.setNextWarnings(nextWarnings);
    if (nextWarnings != null) {
      warningData.setReason(warningData.getReason() + nextWarnings.toString());
    }
    return warningData;
  }

  private void fillWarnings(StatementResult sr, Statement stmt)
      throws SQLException {
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      sr.setWarnings(snappyWarning(warnings));
    }
  }

  private void fillWarnings(UpdateResult ur, Statement stmt)
      throws SQLException {
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      ur.setWarnings(snappyWarning(warnings));
    }
  }

  private void fillWarnings(RowSet rs, ResultSet resultSet)
      throws SQLException {
    SQLWarning warnings = resultSet.getWarnings();
    if (warnings != null) {
      rs.setWarnings(snappyWarning(warnings));
    }
  }

  private SnappyException internalException(String message) {
    SnappyExceptionData exData = new SnappyExceptionData();
    exData.setReason(message);
    exData.setSqlState(SQLState.JAVA_EXCEPTION);
    exData.setErrorCode(ExceptionSeverity.NO_APPLICABLE_SEVERITY);
    return new SnappyException(exData, getServerInfo());
  }

  private SnappyException notImplementedException(String method) {
    SnappyExceptionData exData = new SnappyExceptionData();
    exData.setReason("ASSERT: " + method + "() not implemented");
    exData.setSqlState(SQLState.JDBC_METHOD_NOT_SUPPORTED_BY_SERVER);
    exData.setErrorCode(ExceptionSeverity.STATEMENT_SEVERITY);
    return new SnappyException(exData, getServerInfo());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long sendBlobChunk(BlobChunk chunk, long connId, ByteBuffer token)
      throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      long lobId;
      Blob blob;
      if (chunk.isSetLobId()) {
        lobId = chunk.lobId;
        Object lob = conn.getLOBMapping(lobId);
        if (lob instanceof Blob) {
          blob = (Blob)lob;
        } else {
          throw Util.generateCsSQLException(SQLState.LOB_LOCATOR_INVALID);
        }
      } else {
        blob = conn.createBlob();
        lobId = ((EngineLOB)blob).getLocator();
      }
      long offset = 1;
      if (chunk.isSetOffset()) {
        offset += chunk.offset;
      }
      // TODO: need an EmbedBlob that can deal directly with BlobChunks
      blob.setBytes(offset, chunk.getChunk());
      // free any direct buffer immediately
      chunk.free();
      return lobId;
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long sendClobChunk(ClobChunk chunk, long connId, ByteBuffer token)
      throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      long lobId;
      Clob clob;
      if (chunk.isSetLobId()) {
        lobId = chunk.lobId;
        Object lob = conn.getLOBMapping(lobId);
        if (lob instanceof Clob) {
          clob = (Clob)lob;
        } else {
          throw Util.generateCsSQLException(SQLState.LOB_LOCATOR_INVALID);
        }
      } else {
        clob = conn.createClob();
        lobId = ((EngineLOB)clob).getLocator();
      }
      long offset = 1;
      if (chunk.isSetOffset()) {
        offset += chunk.offset;
      }
      clob.setString(offset, chunk.getChunk());
      return lobId;
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void freeLob(long connId, long lobId, ByteBuffer token)
      throws SnappyException {
    try {
      EngineConnection conn = getValidConnection(connId, token).getConnection();
      Object lob = conn.getLOBMapping(lobId);
      if (lob instanceof EngineLOB) {
        ((EngineLOB)lob).free();
        conn.removeLOBMapping(lobId);
      } else {
        throw Util.generateCsSQLException(SQLState.LOB_LOCATOR_INVALID);
      }
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ServiceMetaData getServiceMetaData(long connId, ByteBuffer token)
      throws SnappyException {

    try {
      final ConnectionHolder connHolder = getValidConnection(connId, token);
      EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connHolder
          .getConnection().getMetaData();
      ServiceMetaData metadata = new ServiceMetaData()
          .setCatalogSeparator(dmd.getCatalogSeparator())
          .setCatalogTerm(dmd.getCatalogTerm())
          .setDateTimeFunctions(toList(dmd.getTimeDateFunctions()))
          .setDefaultResultSetHoldabilityHoldCursorsOverCommit(dmd
              .getResultSetHoldability() == ResultSet.HOLD_CURSORS_OVER_COMMIT)
          .setDefaultResultSetType(
              snappydataConstants.RESULTSET_TYPE_FORWARD_ONLY)
          .setDefaultTransactionIsolation(
              snappydataConstants.DEFAULT_TRANSACTION_ISOLATION)
          .setExtraNameCharacters(dmd.getExtraNameCharacters())
          .setCatalogAtStart(dmd.isCatalogAtStart())
          .setIdentifierQuote(dmd.getIdentifierQuoteString())
          .setJdbcMajorVersion(dmd.getJDBCMajorVersion())
          .setJdbcMinorVersion(dmd.getJDBCMinorVersion())
          .setMaxBinaryLiteralLength(dmd.getMaxBinaryLiteralLength())
          .setMaxCatalogNameLength(dmd.getMaxCatalogNameLength())
          .setMaxCharLiteralLength(dmd.getMaxCharLiteralLength())
          .setMaxColumnNameLength(dmd.getMaxColumnNameLength())
          .setMaxColumnsInGroupBy(dmd.getMaxColumnsInGroupBy())
          .setMaxColumnsInIndex(dmd.getMaxColumnsInIndex())
          .setMaxColumnsInOrderBy(dmd.getMaxColumnsInOrderBy())
          .setMaxColumnsInSelect(dmd.getMaxColumnsInSelect())
          .setMaxColumnsInTable(dmd.getMaxColumnsInTable())
          .setMaxConnections(dmd.getMaxConnections())
          .setMaxCursorNameLength(dmd.getMaxCursorNameLength())
          .setMaxIndexLength(dmd.getMaxIndexLength())
          .setMaxOpenStatements(dmd.getMaxStatements())
          .setMaxProcedureNameLength(dmd.getMaxProcedureNameLength())
          .setMaxRowSize(dmd.getMaxRowSize())
          .setMaxSchemaNameLength(dmd.getMaxSchemaNameLength())
          .setMaxStatementLength(dmd.getMaxStatementLength())
          .setMaxTableNameLength(dmd.getMaxTableNameLength())
          .setMaxTableNamesInSelect(dmd.getMaxTablesInSelect())
          .setMaxUserNameLength(dmd.getMaxUserNameLength())
          .setNumericFunctions(toList(dmd.getNumericFunctions()))
          .setProcedureTerm(dmd.getProcedureTerm())
          .setProductMajorVersion(dmd.getDatabaseMajorVersion())
          .setProductMinorVersion(dmd.getDatabaseMinorVersion())
          .setProductName(dmd.getDatabaseProductName())
          .setProductVersion(dmd.getDatabaseProductVersion())
          .setSchemaTerm(dmd.getSchemaTerm())
          .setSearchStringEscape(dmd.getSearchStringEscape())
          .setSqlKeywords(toList(dmd.getSQLKeywords()))
          .setSqlStateIsXOpen(
              dmd.getSQLStateType() == DatabaseMetaData.sqlStateXOpen)
          .setStringFunctions(toList(dmd.getStringFunctions()))
          .setSystemFunctions(toList(dmd.getSystemFunctions()));

      // populate the TransactionAttribute defaults
      final EnumMap<TransactionAttribute, Boolean> txDefaults = ThriftUtils
          .newTransactionFlags();

      final SystemProperties sysProps = SystemProperties.getServerInstance();
      txDefaults.put(TransactionAttribute.AUTOCOMMIT,
          snappydataConstants.DEFAULT_AUTOCOMMIT);
      txDefaults.put(TransactionAttribute.DISABLE_BATCHING, sysProps
          .getBoolean(Property.GFXD_DISABLE_TX_BATCHING,
              !GfxdConstants.GFXD_TX_BATCHING_DEFAULT));
      txDefaults.put(TransactionAttribute.READ_ONLY_CONNECTION,
          dmd.isReadOnly());
      txDefaults.put(TransactionAttribute.SYNC_COMMITS, sysProps.getBoolean(
          Property.GFXD_TX_SYNC_COMMITS,
          GfxdConstants.GFXD_TX_SYNC_COMMITS_DEFAULT));
      txDefaults.put(TransactionAttribute.WAITING_MODE, sysProps.getBoolean(
          Property.GFXD_ENABLE_TX_WAIT_MODE,
          GfxdConstants.GFXD_TX_WAIT_MODE_DEFAULT));
      metadata.setTransactionDefaults(txDefaults);

      RowIdLifetime rowIdLifeTime;
      switch (dmd.getRowIdLifetime()) {
        case ROWID_VALID_OTHER:
          rowIdLifeTime = RowIdLifetime.ROWID_VALID_OTHER;
          break;
        case ROWID_VALID_FOREVER:
          rowIdLifeTime = RowIdLifetime.ROWID_VALID_FOREVER;
          break;
        case ROWID_VALID_SESSION:
          rowIdLifeTime = RowIdLifetime.ROWID_VALID_SESSION;
          break;
        case ROWID_VALID_TRANSACTION:
          rowIdLifeTime = RowIdLifetime.ROWID_VALID_TRANSACTION;
          break;
        case ROWID_UNSUPPORTED:
        default:
          rowIdLifeTime = RowIdLifetime.ROWID_UNSUPPORTED;
          break;
      }
      metadata.setRowIdLifeTime(rowIdLifeTime);

      // supported features
      HashSet<ServiceFeature> supportedFeatures = new HashSet<>();
      if (dmd.supportsAlterTableWithAddColumn()) {
        supportedFeatures.add(ServiceFeature.ALTER_TABLE_ADD_COLUMN);
      }
      if (dmd.supportsAlterTableWithDropColumn()) {
        supportedFeatures.add(ServiceFeature.ALTER_TABLE_DROP_COLUMN);
      }
      if (dmd.supportsANSI92EntryLevelSQL()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_ANSI92_ENTRY);
      }
      if (dmd.supportsANSI92FullSQL()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_ANSI92_FULL);
      }
      if (dmd.supportsANSI92IntermediateSQL()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_ANSI92_INTERMEDIATE);
      }
      if (dmd.supportsBatchUpdates()) {
        supportedFeatures.add(ServiceFeature.BATCH_UPDATES);
      }
      if (dmd.supportsCatalogsInDataManipulation()) {
        supportedFeatures.add(ServiceFeature.CATALOGS_IN_DMLS);
      }
      if (dmd.supportsCatalogsInIndexDefinitions()) {
        supportedFeatures.add(ServiceFeature.CATALOGS_IN_INDEX_DEFS);
      }
      if (dmd.supportsCatalogsInPrivilegeDefinitions()) {
        supportedFeatures.add(ServiceFeature.CATALOGS_IN_PRIVILEGE_DEFS);
      }
      if (dmd.supportsCatalogsInProcedureCalls()) {
        supportedFeatures.add(ServiceFeature.CATALOGS_IN_PROCEDURE_CALLS);
      }
      if (dmd.supportsCatalogsInTableDefinitions()) {
        supportedFeatures.add(ServiceFeature.CATALOGS_IN_TABLE_DEFS);
      }
      if (dmd.supportsColumnAliasing()) {
        supportedFeatures.add(ServiceFeature.COLUMN_ALIASING);
      }
      if (dmd.supportsConvert()) {
        supportedFeatures.add(ServiceFeature.CONVERT);
      }
      if (dmd.supportsCoreSQLGrammar()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_CORE);
      }
      if (dmd.supportsCorrelatedSubqueries()) {
        supportedFeatures.add(ServiceFeature.SUBQUERIES_CORRELATED);
      }
      if (dmd.supportsDataDefinitionAndDataManipulationTransactions()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS_BOTH_DMLS_AND_DDLS);
      }
      if (dmd.supportsDataManipulationTransactionsOnly()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS_DMLS_ONLY);
      }
      if (dmd.supportsDifferentTableCorrelationNames()) {
        supportedFeatures.add(ServiceFeature.TABLE_CORRELATION_NAMES_DIFFERENT);
      }
      if (dmd.supportsExpressionsInOrderBy()) {
        supportedFeatures.add(ServiceFeature.ORDER_BY_EXPRESSIONS);
      }
      if (dmd.supportsExtendedSQLGrammar()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_EXTENDED);
      }
      if (dmd.supportsFullOuterJoins()) {
        supportedFeatures.add(ServiceFeature.OUTER_JOINS_FULL);
      }
      if (dmd.supportsGetGeneratedKeys()) {
        supportedFeatures.add(ServiceFeature.GENERATED_KEYS_RETRIEVAL);
      }
      if (dmd.supportsGroupBy()) {
        supportedFeatures.add(ServiceFeature.GROUP_BY);
      }
      if (dmd.supportsGroupByBeyondSelect()) {
        supportedFeatures.add(ServiceFeature.GROUP_BY_BEYOND_SELECT);
      }
      if (dmd.supportsGroupByUnrelated()) {
        supportedFeatures.add(ServiceFeature.GROUP_BY_UNRELATED);
      }
      if (dmd.supportsIntegrityEnhancementFacility()) {
        supportedFeatures.add(ServiceFeature.INTEGRITY_ENHANCEMENT);
      }
      if (dmd.supportsLikeEscapeClause()) {
        supportedFeatures.add(ServiceFeature.LIKE_ESCAPE);
      }
      if (dmd.supportsLimitedOuterJoins()) {
        supportedFeatures.add(ServiceFeature.OUTER_JOINS_LIMITED);
      }
      if (dmd.supportsMinimumSQLGrammar()) {
        supportedFeatures.add(ServiceFeature.SQL_GRAMMAR_MINIMUM);
      }
      if (dmd.supportsMixedCaseIdentifiers()) {
        supportedFeatures.add(ServiceFeature.MIXEDCASE_IDENTIFIERS);
      }
      if (dmd.supportsMixedCaseQuotedIdentifiers()) {
        supportedFeatures.add(ServiceFeature.MIXEDCASE_QUOTED_IDENTIFIERS);
      }
      if (dmd.supportsMultipleOpenResults()) {
        supportedFeatures.add(ServiceFeature.MULTIPLE_RESULTSETS);
      }
      if (dmd.supportsMultipleTransactions()) {
        supportedFeatures.add(ServiceFeature.MULTIPLE_TRANSACTIONS);
      }
      if (dmd.supportsNamedParameters()) {
        supportedFeatures.add(ServiceFeature.CALLABLE_NAMED_PARAMETERS);
      }
      if (dmd.supportsNonNullableColumns()) {
        supportedFeatures.add(ServiceFeature.NON_NULLABLE_COLUMNS);
      }
      if (dmd.supportsOpenCursorsAcrossCommit()) {
        supportedFeatures.add(ServiceFeature.OPEN_CURSORS_ACROSS_COMMIT);
      }
      if (dmd.supportsOpenCursorsAcrossRollback()) {
        supportedFeatures.add(ServiceFeature.OPEN_CURSORS_ACROSS_ROLLBACK);
      }
      if (dmd.supportsOpenStatementsAcrossCommit()) {
        supportedFeatures.add(ServiceFeature.OPEN_STATEMENTS_ACROSS_COMMIT);
      }
      if (dmd.supportsOpenStatementsAcrossRollback()) {
        supportedFeatures.add(ServiceFeature.OPEN_STATEMENTS_ACROSS_ROLLBACK);
      }
      if (dmd.supportsOrderByUnrelated()) {
        supportedFeatures.add(ServiceFeature.ORDER_BY_UNRELATED);
      }
      if (dmd.supportsOuterJoins()) {
        supportedFeatures.add(ServiceFeature.OUTER_JOINS);
      }
      if (dmd.supportsPositionedDelete()) {
        supportedFeatures.add(ServiceFeature.POSITIONED_DELETE);
      }
      if (dmd.supportsPositionedUpdate()) {
        supportedFeatures.add(ServiceFeature.POSITIONED_UPDATE);
      }
      if (dmd.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
        supportedFeatures
            .add(ServiceFeature.RESULTSET_HOLDABILITY_CLOSE_CURSORS_AT_COMMIT);
      }
      if (dmd.supportsResultSetHoldability(
          ResultSet.HOLD_CURSORS_OVER_COMMIT)) {
        supportedFeatures
            .add(ServiceFeature.RESULTSET_HOLDABILITY_HOLD_CURSORS_OVER_COMMIT);
      }
      if (dmd.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY)) {
        supportedFeatures.add(ServiceFeature.RESULTSET_FORWARD_ONLY);
      }
      if (dmd.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE)) {
        supportedFeatures.add(ServiceFeature.RESULTSET_SCROLL_INSENSITIVE);
      }
      if (dmd.supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE)) {
        supportedFeatures.add(ServiceFeature.RESULTSET_SCROLL_SENSITIVE);
      }
      if (dmd.supportsSavepoints()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS_SAVEPOINTS);
      }
      if (dmd.supportsSchemasInDataManipulation()) {
        supportedFeatures.add(ServiceFeature.SCHEMAS_IN_DMLS);
      }
      if (dmd.supportsSchemasInIndexDefinitions()) {
        supportedFeatures.add(ServiceFeature.SCHEMAS_IN_INDEX_DEFS);
      }
      if (dmd.supportsSchemasInPrivilegeDefinitions()) {
        supportedFeatures.add(ServiceFeature.SCHEMAS_IN_PRIVILEGE_DEFS);
      }
      if (dmd.supportsSchemasInProcedureCalls()) {
        supportedFeatures.add(ServiceFeature.SCHEMAS_IN_PROCEDURE_CALLS);
      }
      if (dmd.supportsSchemasInTableDefinitions()) {
        supportedFeatures.add(ServiceFeature.SCHEMAS_IN_TABLE_DEFS);
      }
      if (dmd.supportsSelectForUpdate()) {
        supportedFeatures.add(ServiceFeature.SELECT_FOR_UPDATE);
      }
      if (dmd.supportsStatementPooling()) {
        supportedFeatures.add(ServiceFeature.STATEMENT_POOLING);
      }
      if (dmd.supportsStoredFunctionsUsingCallSyntax()) {
        supportedFeatures.add(ServiceFeature.STORED_FUNCTIONS_USING_CALL);
      }
      if (dmd.supportsStoredProcedures()) {
        supportedFeatures.add(ServiceFeature.STORED_PROCEDURES);
      }
      if (dmd.supportsSubqueriesInComparisons()) {
        supportedFeatures.add(ServiceFeature.SUBQUERIES_IN_COMPARISONS);
      }
      if (dmd.supportsSubqueriesInExists()) {
        supportedFeatures.add(ServiceFeature.SUBQUERIES_IN_EXISTS);
      }
      if (dmd.supportsSubqueriesInIns()) {
        supportedFeatures.add(ServiceFeature.SUBQUERIES_IN_INS);
      }
      if (dmd.supportsSubqueriesInQuantifieds()) {
        supportedFeatures.add(ServiceFeature.SUBQUERIES_IN_QUANTIFIEDS);
      }
      if (dmd.supportsTableCorrelationNames()) {
        supportedFeatures.add(ServiceFeature.TABLE_CORRELATION_NAMES);
      }
      if (dmd.supportsTransactions()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS);
      }
      if (dmd.supportsUnion()) {
        supportedFeatures.add(ServiceFeature.UNION);
      }
      if (dmd.supportsUnionAll()) {
        supportedFeatures.add(ServiceFeature.UNION_ALL);
      }
      if (dmd.allProceduresAreCallable()) {
        supportedFeatures.add(ServiceFeature.ALL_PROCEDURES_CALLABLE);
      }
      if (dmd.allTablesAreSelectable()) {
        supportedFeatures.add(ServiceFeature.ALL_TABLES_SELECTABLE);
      }
      if (dmd.autoCommitFailureClosesAllResultSets()) {
        supportedFeatures
            .add(ServiceFeature.AUTOCOMMIT_FAILURE_CLOSES_ALL_RESULTSETS);
      }
      if (dmd.dataDefinitionCausesTransactionCommit()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS_DDLS_IMPLICIT_COMMIT);
      }
      if (dmd.dataDefinitionIgnoredInTransactions()) {
        supportedFeatures.add(ServiceFeature.TRANSACTIONS_DDLS_IGNORED);
      }
      if (dmd.doesMaxRowSizeIncludeBlobs()) {
        supportedFeatures.add(ServiceFeature.MAX_ROWSIZE_INCLUDES_BLOBSIZE);
      }
      if (dmd.generatedKeyAlwaysReturned()) {
        supportedFeatures.add(ServiceFeature.GENERATED_KEYS_ALWAYS_RETURNED);
      }
      if (dmd.locatorsUpdateCopy()) {
        supportedFeatures.add(ServiceFeature.LOB_UPDATES_COPY);
      }
      if (dmd.nullPlusNonNullIsNull()) {
        supportedFeatures.add(ServiceFeature.NULL_CONCAT_NON_NULL_IS_NULL);
      }
      if (dmd.nullsAreSortedAtEnd()) {
        supportedFeatures.add(ServiceFeature.NULLS_SORTED_END);
      }
      if (dmd.nullsAreSortedAtStart()) {
        supportedFeatures.add(ServiceFeature.NULLS_SORTED_START);
      }
      if (dmd.nullsAreSortedHigh()) {
        supportedFeatures.add(ServiceFeature.NULLS_SORTED_HIGH);
      }
      if (dmd.nullsAreSortedLow()) {
        supportedFeatures.add(ServiceFeature.NULLS_SORTED_LOW);
      }
      if (dmd.storesLowerCaseIdentifiers()) {
        supportedFeatures.add(ServiceFeature.STORES_LOWERCASE_IDENTIFIERS);
      }
      if (dmd.storesLowerCaseQuotedIdentifiers()) {
        supportedFeatures
            .add(ServiceFeature.STORES_LOWERCASE_QUOTED_IDENTIFIERS);
      }
      if (dmd.storesMixedCaseIdentifiers()) {
        supportedFeatures.add(ServiceFeature.STORES_MIXEDCASE_IDENTIFIERS);
      }
      if (dmd.storesMixedCaseQuotedIdentifiers()) {
        supportedFeatures
            .add(ServiceFeature.STORES_MIXEDCASE_QUOTED_IDENTIFIERS);
      }
      if (dmd.storesUpperCaseIdentifiers()) {
        supportedFeatures.add(ServiceFeature.STORES_UPPERCASE_IDENTIFIERS);
      }
      if (dmd.storesUpperCaseQuotedIdentifiers()) {
        supportedFeatures
            .add(ServiceFeature.STORES_UPPERCASE_QUOTED_IDENTIFIERS);
      }
      if (dmd.usesLocalFilePerTable()) {
        supportedFeatures.add(ServiceFeature.USES_LOCAL_FILE_PER_TABLE);
      }
      if (dmd.usesLocalFiles()) {
        supportedFeatures.add(ServiceFeature.USES_LOCAL_FILES);
      }
      metadata.setSupportedFeatures(supportedFeatures);

      // CONVERT support
      final int[] allTypes = new int[]{Types.ARRAY, Types.BIGINT,
          Types.BINARY, Types.BIT, Types.BLOB, Types.BOOLEAN, Types.CHAR,
          Types.CLOB, Types.DATALINK, Types.DATE, Types.DECIMAL,
          Types.DISTINCT, Types.DOUBLE, Types.FLOAT, Types.INTEGER,
          Types.JAVA_OBJECT, Types.LONGNVARCHAR, Types.LONGVARBINARY,
          Types.LONGVARCHAR, Types.NCHAR, Types.NCLOB, Types.NULL,
          Types.NUMERIC, Types.NVARCHAR, Types.OTHER, Types.REAL, Types.REF,
          Types.ROWID, Types.SMALLINT, Types.SQLXML, Types.STRUCT, Types.TIME,
          Types.TIMESTAMP, Types.TINYINT, Types.VARBINARY, Types.VARCHAR,
          JDBC40Translation.MAP, JDBC40Translation.JSON};
      Map<SnappyType, Set<SnappyType>> convertMap =
          new HashMap<>();
      for (int fromType : allTypes) {
        HashSet<SnappyType> supportedConverts = new HashSet<>();
        for (int toType : allTypes) {
          if (dmd.supportsConvert(fromType, toType)) {
            supportedConverts.add(Converters.getThriftSQLType(toType));
          }
        }
        if (!supportedConverts.isEmpty()) {
          convertMap.put(Converters.getThriftSQLType(fromType),
              supportedConverts);
        }
      }
      metadata.setSupportedCONVERT(convertMap);

      // lastly features with parameters
      HashMap<ServiceFeatureParameterized, List<Integer>> featureParameters =
          new HashMap<>();
      ArrayList<Integer> supportedValues = new ArrayList<>(4);
      final int[] isolationLevels = new int[]{Connection.TRANSACTION_NONE,
          Connection.TRANSACTION_READ_UNCOMMITTED,
          Connection.TRANSACTION_READ_COMMITTED,
          Connection.TRANSACTION_REPEATABLE_READ,
          Connection.TRANSACTION_SERIALIZABLE};
      for (int isolationLevel : isolationLevels) {
        if (dmd.supportsTransactionIsolationLevel(isolationLevel)) {
          supportedValues.add((int)Converters
              .getThriftTransactionIsolation(isolationLevel));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(
            ServiceFeatureParameterized.TRANSACTIONS_SUPPORT_ISOLATION,
            supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      final int[] rsTypes = new int[]{ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.TYPE_SCROLL_SENSITIVE};
      for (int rsType : rsTypes) {
        if (dmd.supportsResultSetType(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(
            ServiceFeatureParameterized.RESULTSET_TYPE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      final int[] concurTypes = new int[]{ResultSet.CONCUR_READ_ONLY,
          ResultSet.CONCUR_UPDATABLE};
      for (int concurType : concurTypes) {
        ServiceFeatureParameterized thriftConcurType =
            (concurType == ResultSet.CONCUR_READ_ONLY
                ? ServiceFeatureParameterized.RESULTSET_CONCURRENCY_READ_ONLY
                : ServiceFeatureParameterized.RESULTSET_CONCURRENCY_UPDATABLE);
        for (int rsType : rsTypes) {
          if (dmd.supportsResultSetConcurrency(rsType, concurType)) {
            supportedValues.add(Converters.getThriftResultSetType(rsType));
          }
        }
        if (!supportedValues.isEmpty()) {
          featureParameters.put(thriftConcurType, supportedValues);
          supportedValues = new ArrayList<>(4);
        }
      }

      for (int rsType : rsTypes) {
        if (dmd.ownUpdatesAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OWN_UPDATES_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.ownDeletesAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OWN_DELETES_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.ownInsertsAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OWN_INSERTS_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }


      for (int rsType : rsTypes) {
        if (dmd.othersUpdatesAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OTHERS_UPDATES_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.othersDeletesAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OTHERS_DELETES_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.othersInsertsAreVisible(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_OTHERS_INSERTS_VISIBLE, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.updatesAreDetected(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_UPDATES_DETECTED, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.deletesAreDetected(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_DELETES_DETECTED, supportedValues);
        supportedValues = new ArrayList<>(4);
      }

      for (int rsType : rsTypes) {
        if (dmd.insertsAreDetected(rsType)) {
          supportedValues.add(Converters.getThriftResultSetType(rsType));
        }
      }
      if (!supportedValues.isEmpty()) {
        featureParameters.put(ServiceFeatureParameterized
            .RESULTSET_INSERTS_DETECTED, supportedValues);
      }

      metadata.setFeaturesWithParams(featureParameters);

      return metadata;
    } catch (Throwable t) {
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  private ArrayList<String> toList(String csv) {
    final ArrayList<String> strings = new ArrayList<>();
    SharedUtils.splitCSV(csv, SharedUtils.stringAggregator, strings,
        Boolean.TRUE);
    return strings;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet getSchemaMetaData(ServiceMetaDataCall schemaCall,
      ServiceMetaDataArgs args) throws SnappyException {
    ResultSet rs = null;
    try {
      final ConnectionHolder connHolder = getValidConnection(args.connId,
          args.token);
      EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connHolder
          .getConnection().getMetaData();
      boolean isODBC = args.getDriverType() == snappydataConstants.DRIVER_ODBC;
      switch (schemaCall) {
        case ATTRIBUTES:
          rs = dmd.getAttributes(null, args.getSchema(), args.getTypeName(),
              args.getAttributeName());
          break;
        case CATALOGS:
          rs = dmd.getCatalogs();
          break;
        case CLIENTINFOPROPS:
          rs = dmd.getClientInfoProperties();
          break;
        case COLUMNPRIVILEGES:
          rs = dmd.getColumnPrivileges(null, args.getSchema(), args.getTable(),
              args.getColumnName());
          break;
        case COLUMNS:
          if (isODBC) {
            rs = dmd.getColumnsForODBC(null, args.getSchema(), args.getTable(),
                args.getColumnName());
          } else {
            rs = dmd.getColumns(null, args.getSchema(), args.getTable(),
                args.getColumnName());
          }
          break;
        case CROSSREFERENCE:
          if (isODBC) {
            rs = dmd.getCrossReferenceForODBC(null, args.getSchema(),
                args.getTable(), null, args.getForeignSchema(),
                args.getForeignTable());
          } else {
            rs = dmd.getCrossReference(null, args.getSchema(), args.getTable(),
                null, args.getForeignSchema(), args.getForeignTable());
          }
          break;
        case EXPORTEDKEYS:
          rs = dmd.getExportedKeys(null, args.getSchema(), args.getTable());
          break;
        case FUNCTIONCOLUMNS:
          rs = dmd.getFunctionColumns(null, args.getSchema(),
              args.getFunctionName(), args.getColumnName());
          break;
        case FUNCTIONS:
          rs = dmd.getFunctions(null, args.getSchema(), args.getFunctionName());
          break;
        case IMPORTEDKEYS:
          rs = dmd.getImportedKeys(null, args.getSchema(), args.getTable());
          break;
        case PRIMARYKEYS:
          rs = dmd.getPrimaryKeys(null, args.getSchema(), args.getTable());
          break;
        case PROCEDURECOLUMNS:
          if (isODBC) {
            rs = dmd.getProcedureColumnsForODBC(null, args.getSchema(),
                args.getProcedureName(), args.getColumnName());
          } else {
            rs = dmd.getProcedureColumns(null, args.getSchema(),
                args.getProcedureName(), args.getColumnName());
          }
          break;
        case PROCEDURES:
          if (isODBC) {
            rs = dmd.getProceduresForODBC(null, args.getSchema(),
                args.getProcedureName());
          } else {
            rs = dmd.getProcedures(null, args.getSchema(),
                args.getProcedureName());
          }
          break;
        case PSEUDOCOLUMNS:
          rs = dmd.getPseudoColumns(null, args.getSchema(), args.getTable(),
              args.getColumnName());
          break;
        case SCHEMAS:
          if (args.getSchema() != null) {
            rs = dmd.getSchemas(null, args.getSchema());
          } else {
            rs = dmd.getSchemas();
          }
          break;
        case SUPERTABLES:
          rs = dmd.getSuperTables(null, args.getSchema(), args.getTypeName());
          break;
        case SUPERTYPES:
          rs = dmd.getSuperTypes(null, args.getSchema(), args.getTypeName());
          break;
        case TABLEPRIVILEGES:
          rs = dmd.getTablePrivileges(null, args.getSchema(), args.getTable());
          break;
        case TABLES:
          List<String> tableTypes = args.getTableTypes();
          String[] types = null;
          if (tableTypes != null && !tableTypes.isEmpty()) {
            types = tableTypes.toArray(new String[tableTypes.size()]);
          }
          // check for schema fetch with ODBC SQLTables('', '%', '')
          if (isODBC && "%".equals(args.getSchema()) &&
              args.getTable() != null && args.getTable().isEmpty()) {
            rs = dmd.getTableSchemas();
          } else {
            rs = dmd.getTables(null, args.getSchema(), args.getTable(), types);
          }
          break;
        case TABLETYPES:
          rs = dmd.getTableTypes();
          break;
        case TYPEINFO:
          if (isODBC) {
            rs = dmd.getTypeInfoForODBC((short)(args.isSetTypeId() ? Converters
                .getJdbcType(args.getTypeId()) : 0));
          } else {
            rs = dmd.getTypeInfo();
          }
          break;
        case VERSIONCOLUMNS:
          if (isODBC) {
            rs = dmd.getVersionColumnsForODBC(null, args.getSchema(),
                args.getTable());
          } else {
            rs = dmd.getVersionColumns(null, args.getSchema(), args.getTable());
          }
          break;
        default:
          throw internalException("unexpected metadata call: " + schemaCall);
      }
      return getRowSet(null, null, rs, INVALID_ID, null, args.connId, null, 0,
          false, false, 0, connHolder, "getSchemaMetaData");
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet getIndexInfo(ServiceMetaDataArgs args, boolean unique,
      boolean approximate) throws SnappyException {
    ResultSet rs = null;
    try {
      final ConnectionHolder connHolder = getValidConnection(args.connId,
          args.token);
      EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connHolder
          .getConnection().getMetaData();
      boolean isODBC = args.getDriverType() == snappydataConstants.DRIVER_ODBC;
      rs = isODBC ? dmd.getIndexInfoForODBC(null, args.getSchema(),
          args.getTable(), unique, approximate) : dmd.getIndexInfo(null,
          args.getSchema(), args.getTable(), unique, approximate);
      return getRowSet(null, null, rs, INVALID_ID, null, args.connId, null, 0,
          false, false, 0, connHolder, "getIndexInfo");
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet getUDTs(ServiceMetaDataArgs args, List<SnappyType> types)
      throws SnappyException {
    ResultSet rs = null;
    try {
      final ConnectionHolder connHolder = getValidConnection(args.connId,
          args.token);
      EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connHolder
          .getConnection().getMetaData();
      int[] sqlTypes = null;
      if (types != null && !types.isEmpty()) {
        sqlTypes = new int[types.size()];
        for (int index = 0; index < types.size(); index++) {
          sqlTypes[index] = Converters.getJdbcType(types.get(index));
        }
      }
      rs = dmd.getUDTs(null, args.getSchema(), args.getTypeName(), sqlTypes);
      return getRowSet(null, null, rs, INVALID_ID, null, args.connId, null, 0,
          false, false, 0, connHolder, "getUDTs");
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowSet getBestRowIdentifier(ServiceMetaDataArgs args, int scope,
      boolean nullable) throws SnappyException {
    ResultSet rs = null;
    try {
      final ConnectionHolder connHolder = getValidConnection(args.connId,
          args.token);
      EmbedDatabaseMetaData dmd = (EmbedDatabaseMetaData)connHolder
          .getConnection().getMetaData();
      boolean isODBC = args.getDriverType() == snappydataConstants.DRIVER_ODBC;
      rs = isODBC ? dmd.getBestRowIdentifierForODBC(null, args.getSchema(),
          args.getTable(), scope, nullable) : dmd.getBestRowIdentifier(null,
          args.getSchema(), args.getTable(), scope, nullable);
      return getRowSet(null, null, rs, INVALID_ID, null, args.connId, null, 0,
          false, false, 0, connHolder, "getBestRowIdentifier");
    } catch (Throwable t) {
      cleanupResultSet(rs);
      checkSystemFailure(t);
      throw SnappyException(t);
    }
  }
}
