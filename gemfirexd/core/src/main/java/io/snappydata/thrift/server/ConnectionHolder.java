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

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;

import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection;
import com.pivotal.gemfirexd.internal.iapi.jdbc.EngineStatement;
import com.pivotal.gemfirexd.internal.jdbc.EmbedXAConnection;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.OpenConnectionArgs;
import io.snappydata.thrift.SecurityMechanism;
import io.snappydata.thrift.StatementAttrs;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

/**
 * Holder for a connection on the server side for each open client connection.
 */
final class ConnectionHolder {
  private final EngineConnection conn;
  private final EmbedXAConnection xaConn;
  private final long connId;
  private final Properties props;
  private final ByteBuffer token;
  private final String clientHostName;
  private final String clientID;
  private final String clientHostId;
  private final String userName;
  private final boolean useStringForDecimal;
  private EngineStatement reusableStatement;
  private volatile StatementHolder activeStatement;
  private final ArrayList<StatementHolder> registeredStatements;
  private final NonReentrantLock sync;
  private final long startTime;

  ConnectionHolder(final EngineConnection conn, final EmbedXAConnection xaConn,
      final OpenConnectionArgs args, final long connId, final Properties props,
      final SecureRandom rnd) throws SQLException {
    this.conn = conn;
    this.xaConn = xaConn;
    this.connId = connId;
    this.props = props;

    // generate a unique ID for the connection; this is a secure random string
    // rather than the internal long connection ID to ensure security and is
    // checked in every client-server RPC call if the client has so requested
    if (args.getSecurity() == SecurityMechanism.PLAIN
        || args.getSecurity() == SecurityMechanism.DIFFIE_HELLMAN) {
      int tokenSize = snappydataConstants.DEFAULT_SESSION_TOKEN_SIZE;
      if (args.isSetTokenSize()) {
        if (args.getTokenSize() < tokenSize) {
          // don't accept small token sizes
          throw ThriftExceptionUtil.newSQLException(
              SQLState.NET_CONNECT_AUTH_FAILED, null,
              "specified connection token size " + args.getTokenSize()
                  + " smaller than minimum allowed of " + tokenSize);
        } else {
          tokenSize = args.getTokenSize();
        }
      }
      byte[] rndBytes = new byte[tokenSize];
      rnd.nextBytes(rndBytes);
      this.token = ByteBuffer.wrap(rndBytes);
    } else {
      // no other security mechanism supported yet
      throw ThriftExceptionUtil.newSQLException(
          SQLState.NET_CONNECT_AUTH_FAILED, null,
          "unsupported security mechanism " + args.getSecurity());
    }

    this.clientHostName = args.getClientHostName();
    this.clientID = args.getClientID();
    this.clientHostId = ClientTracker.getClientHostId(this.clientHostName,
        this.clientID);
    this.userName = args.getUserName();
    this.useStringForDecimal = args.isSetUseStringForDecimal()
        && args.useStringForDecimal;
    this.reusableStatement = (EngineStatement)conn.createStatement();
    this.registeredStatements = new ArrayList<>(4);
    this.sync = new NonReentrantLock(true);
    this.startTime = System.currentTimeMillis();
  }

  static class ResultSetHolder {
    protected ResultSet resultSet;
    protected long rsCursorId;
    protected int rsOffset;

    ResultSetHolder(ResultSet rs, long cursorId, int offset) {
      this.resultSet = rs;
      this.rsCursorId = cursorId;
      this.rsOffset = offset;
    }
  }

  final class StatementHolder extends ResultSetHolder {
    private final Statement stmt;
    private final StatementAttrs statementAttrs;
    private final long stmtId;
    private final Object sql;
    private final long startTime;
    private volatile String status;
    private volatile int accessFrequency;
    private ArrayList<ResultSetHolder> moreResultSets;

    private StatementHolder(Statement stmt, StatementAttrs attrs, long stmtId,
        Object sql, long startTime, String status) {
      super(null, snappydataConstants.INVALID_ID, 0);
      this.stmt = stmt;
      this.statementAttrs = attrs;
      this.stmtId = stmtId;
      this.sql = sql;
      this.startTime = startTime;
      this.status = status;
      this.accessFrequency = 1;
    }

    final ConnectionHolder getConnectionHolder() {
      return ConnectionHolder.this;
    }

    final Statement getStatement() {
      return this.stmt;
    }

    final long getStatementId() {
      return this.stmtId;
    }

    final Object getSQL() {
      return this.sql;
    }

    final StatementAttrs getStatementAttrs() {
      return this.statementAttrs;
    }

    final long getStartTime() {
      return this.startTime;
    }

    final String getStatus() {
      return this.status;
    }

    final int getAccessFrequency() {
      return this.accessFrequency;
    }

    final void setStatus(String newStatus) {
      this.status = newStatus;
    }

    final void incrementAccessFrequency() {
      final int accessFrequency = this.accessFrequency;
      this.accessFrequency = accessFrequency + 1;
    }

    ResultSetHolder addResultSet(ResultSet rs, long cursorId) {
      final NonReentrantLock sync = ConnectionHolder.this.sync;
      sync.lock();
      try {
        return addResultSetNoLock(rs, cursorId);
      } finally {
        sync.unlock();
      }
    }

    private ResultSetHolder addResultSetNoLock(ResultSet rs, long cursorId) {
      if (this.resultSet == null) {
        this.resultSet = rs;
        this.rsCursorId = cursorId;
        // offset will always be zero in initial registration
        this.rsOffset = 0;
        return this;
      } else {
        if (this.moreResultSets == null) {
          this.moreResultSets = new ArrayList<>(4);
        }
        ResultSetHolder holder = new ResultSetHolder(rs, cursorId, 0);
        this.moreResultSets.add(holder);
        return holder;
      }
    }

    ResultSetHolder findResultSet(long cursorId) {
      final ArrayList<ResultSetHolder> moreResults;
      final NonReentrantLock sync = ConnectionHolder.this.sync;
      sync.lock();
      try {
        if (this.rsCursorId == cursorId) {
          return this;
        } else if ((moreResults = this.moreResultSets) != null) {
          for (ResultSetHolder holder : moreResults) {
            if (holder.rsCursorId == cursorId) {
              return holder;
            }
          }
        }
      } finally {
        sync.unlock();
      }
      return null;
    }

    ResultSet removeResultSet(long cursorId) {
      final ArrayList<ResultSetHolder> moreResults;
      final NonReentrantLock sync = ConnectionHolder.this.sync;
      sync.lock();
      try {
        if (this.rsCursorId == cursorId) {
          final ResultSet rs = this.resultSet;
          // move from list if present
          if ((moreResults = this.moreResultSets) != null) {
            ResultSetHolder holder = moreResults.remove(moreResults.size() - 1);
            this.resultSet = holder.resultSet;
            this.rsCursorId = holder.rsCursorId;
            this.rsOffset = holder.rsOffset;
          } else {
            this.resultSet = null;
            this.rsCursorId = snappydataConstants.INVALID_ID;
            this.rsOffset = 0;
          }
          return rs;
        } else if ((moreResults = this.moreResultSets) != null) {
          Iterator<ResultSetHolder> itr = moreResults.iterator();
          while (itr.hasNext()) {
            final ResultSetHolder holder = itr.next();
            if (holder.rsCursorId == cursorId) {
              itr.remove();
              if (moreResults.isEmpty()) {
                this.moreResultSets = null;
              }
              return holder.resultSet;
            }
          }
        }
      } finally {
        sync.unlock();
      }
      return null;
    }

    void closeResultSet(long cursorId, final SnappyDataServiceImpl service) {
      final ResultSet rs = removeResultSet(cursorId);
      if (rs != null) {
        service.resultSetMap.removePrimitive(cursorId);
        try {
          rs.close();
        } catch (Exception e) {
          // deliberately ignored
        }
      }
    }

    void closeAllResultSets(final SnappyDataServiceImpl service) {
      final ArrayList<ResultSetHolder> moreResults;
      final ResultSet rs = this.resultSet;
      if (rs != null) {
        try {
          rs.close();
        } catch (SQLException sqle) {
          // ignore exception at this point
          service.logger.error("unexpected exception in ResultSet.close()",
              sqle);
        } finally {
          service.resultSetMap.removePrimitive(this.rsCursorId);
          this.resultSet = null;
          this.rsCursorId = snappydataConstants.INVALID_ID;
          this.rsOffset = 0;
        }
        if ((moreResults = this.moreResultSets) != null) {
          for (ResultSetHolder holder : moreResults) {
            try {
              holder.resultSet.close();
            } catch (SQLException sqle) {
              // ignore exception at this point
              service.logger.error("unexpected exception in ResultSet.close()",
                  sqle);
            } finally {
              service.resultSetMap.removePrimitive(holder.rsCursorId);
            }
          }
          this.moreResultSets = null;
        }
      }
    }
  }

  EngineStatement createNewStatement(StatementAttrs attrs) throws SQLException {
    // Get the result type
    int resultSetType = SnappyDataServiceImpl.getResultType(attrs);
    // Get the resultSetConcurrency
    int resultSetConcurrency = SnappyDataServiceImpl.getResultSetConcurrency(attrs);
    // Get the resultSetHoldability
    int resultSetHoldability = SnappyDataServiceImpl.getResultSetHoldability(attrs);
    this.sync.lock();
    try {
      final EngineStatement stmt = this.reusableStatement;
      if (stmt != null) {
        stmt.reset(resultSetType, resultSetConcurrency, resultSetHoldability);
        this.reusableStatement = null;
        return stmt;
      }
    } finally {
      this.sync.unlock();
    }
    return (EngineStatement)this.conn.createStatement(resultSetType,
        resultSetConcurrency, resultSetHoldability);
  }

  final EngineConnection getConnection() {
    return this.conn;
  }

  final EmbedXAConnection getXAConnection() {
    return this.xaConn;
  }

  final long getConnectionId() {
    return this.connId;
  }

  final Properties getProperties() {
    return this.props;
  }

  final ByteBuffer getToken() {
    return this.token;
  }

  /**
   * Get given session token as a hex string.
   */
  static String getTokenAsString(ByteBuffer token) {
    if (token != null) {
      return ClientSharedUtils.toHexString(token);
    } else {
      return "NULL";
    }
  }

  final String getClientHostName() {
    return this.clientHostName;
  }

  final String getClientID() {
    return this.clientID;
  }

  final String getClientHostId() {
    return this.clientHostId;
  }

  final String getUserName() {
    return this.userName;
  }

  final boolean useStringForDecimal() {
    return this.useStringForDecimal;
  }

  final long getStartTime() {
    return this.startTime;
  }

  void setStatementForReuse(EngineStatement stmt) throws SQLException {
    this.sync.lock();
    try {
      setStatementForReuseNoLock(stmt);
    } finally {
      this.sync.unlock();
    }
  }

  private void setStatementForReuseNoLock(final EngineStatement stmt)
      throws SQLException {
    if (this.reusableStatement == null) {
      stmt.resetForReuse();
      this.reusableStatement = stmt;
    } else {
      stmt.close();
    }
  }

  StatementHolder getActiveStatement() {
    return this.activeStatement;
  }

  void setActiveStatement(StatementHolder stmtHolder) {
    this.sync.lock();
    this.activeStatement = stmtHolder;
    this.sync.unlock();
  }

  void clearActiveStatement(Statement stmt) {
    if (stmt != null) {
      this.sync.lock();
      final StatementHolder activeStatement = this.activeStatement;
      if (activeStatement != null && stmt == activeStatement.stmt) {
        this.activeStatement = null;
      }
      this.sync.unlock();
    }
  }

  StatementHolder newStatementHolder(Statement stmt, StatementAttrs attrs,
      long stmtId, Object sql, boolean recordStart, String status) {
    final long startTime = recordStart ? System.nanoTime() : 0L;
    return new StatementHolder(stmt, attrs, stmtId, sql, startTime, status);
  }

  StatementHolder registerPreparedStatement(PreparedStatement pstmt,
      StatementAttrs attrs, long stmtId, String sql, boolean recordStart) {
    StatementHolder stmtHolder;
    this.sync.lock();
    try {
      stmtHolder = newStatementHolder(pstmt, attrs, stmtId, sql,
          recordStart, "PREPARED");
      this.registeredStatements.add(stmtHolder);
      this.activeStatement = stmtHolder;
    } finally {
      this.sync.unlock();
    }
    return stmtHolder;
  }

  Statement uniqueActiveStatement(boolean skipPrepared) throws SQLException {
    Statement result = null;
    this.sync.lock();
    try {
      StatementHolder activeStatement = this.activeStatement;
      if (activeStatement != null) {
        result = activeStatement.getStatement();
        if (skipPrepared && result instanceof PreparedStatement) {
          result = null;
        }
      }
      for (StatementHolder holder : this.registeredStatements) {
        Statement stmt = holder.getStatement();
        if (stmt != result &&
            !(skipPrepared && stmt instanceof PreparedStatement)) {
          // if duplicate then throw exception
          if (result != null) {
            throw ThriftExceptionUtil.newSQLException(
                SQLState.CANCEL_NO_UNIQUE_STATEMENT, null);
          } else {
            result = stmt;
          }
        }
      }
      return result;
    } finally {
      this.sync.unlock();
    }
  }

  ResultSetHolder registerResultSet(final StatementHolder stmtHolder,
      ResultSet rs, long cursorId) {
    this.sync.lock();
    try {
      ResultSetHolder holder = stmtHolder.addResultSetNoLock(rs, cursorId);
      this.registeredStatements.add(stmtHolder);
      return holder;
    } finally {
      this.sync.unlock();
    }
  }

  StatementHolder registerResultSet(Statement stmt, StatementAttrs attrs,
      long stmtId, ResultSet rs, long cursorId, String sql,
      boolean recordStart) {
    final StatementHolder stmtHolder = newStatementHolder(stmt, attrs, stmtId, sql,
        recordStart, "INIT");
    registerResultSet(stmtHolder, rs, cursorId);
    return stmtHolder;
  }

  void closeStatement(final StatementHolder stmtHolder,
      final SnappyDataServiceImpl service) {
    final long stmtId = stmtHolder.getStatementId();
    this.sync.lock();
    try {
      final Statement stmt;
      final EngineStatement estmt;

      removeActiveStatementNoLock(stmtHolder);
      stmtHolder.closeAllResultSets(service);
      stmt = stmtHolder.getStatement();
      // set statement for reuse now that it is being closed on client
      if (stmt instanceof EngineStatement
          && !(estmt = (EngineStatement)stmt).isPrepared()) {
        setStatementForReuseNoLock(estmt);
      } else if (stmt != null) {
        stmt.close();
      }
    } catch (Exception e) {
      // deliberately ignored
    } finally {
      this.sync.unlock();
      service.statementMap.removePrimitive(stmtId);
    }
  }

  private void removeActiveStatementNoLock(
      final StatementHolder stmtHolder) {
    final ArrayList<StatementHolder> statements = this.registeredStatements;
    int size = statements.size();
    // usually we will find the statement faster from the back
    while (--size >= 0) {
      if (statements.get(size) == stmtHolder) {
        statements.remove(size);
        break;
      }
    }
  }

  void close(final SnappyDataServiceImpl service, boolean forceClose) {
    this.sync.lock();
    try {
      for (StatementHolder stmtHolder : this.registeredStatements) {
        stmtHolder.closeAllResultSets(service);
        Statement stmt = stmtHolder.getStatement();
        if (stmt != null) {
          try {
            if (forceClose) {
              if (stmt instanceof EngineStatement) {
                // connection is going to be force closed so no need for
                // any statement cleanup
                ((EngineStatement)stmt).clearFinalizer();
              }
            } else {
              stmt.close();
            }
          } catch (SQLException sqle) {
            // ignore exception at this point
            service.logger.error("unexpected exception in Statement.close()",
                sqle);
          } finally {
            service.statementMap.removePrimitive(stmtHolder.getStatementId());
          }
        }
      }

      final EngineStatement reusableStatement = this.reusableStatement;
      if (reusableStatement != null) {
        try {
          if (forceClose) {
            reusableStatement.clearFinalizer();
          } else {
            reusableStatement.close();
          }
        } catch (SQLException sqle) {
          // ignore exception at this point
          service.logger.error("unexpected exception in Statement.close()",
              sqle);
        }
      }
      if (forceClose) {
        // enqueue distribution of close
        final FinalizeObject finalizer = this.conn.getAndClearFinalizer();
        this.conn.forceClose();
        if (finalizer != null) {
          finalizer.clear();
          finalizer.getHolder().addToPendingQueue(finalizer);
        }
      } else {
        if (this.xaConn != null) {
          try {
            this.xaConn.close();
          } catch (SQLException sqle) {
            // ignore exception
          }
        }
        try {
          if (!this.conn.isClosed()) {
            this.conn.close();
          }
        } catch (SQLException sqle) {
          // force close at this point
          this.conn.forceClose();
        }
      }
    } finally {
      this.sync.unlock();
    }
  }

  final boolean sameToken(ByteBuffer otherId) {
    return ClientSharedUtils.equalBuffers(this.token, otherId);
  }

  @Override
  public final int hashCode() {
    return (int)(connId ^ (connId >>> 32));
  }
}
