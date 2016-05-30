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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;

import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
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
  private final EmbedConnection conn;
  private final int connId;
  private final ByteBuffer token;
  private final String clientHostName;
  private final String clientID;
  private final String clientHostId;
  private final String userName;
  private final boolean useStringForDecimal;
  private EmbedStatement reusableStatement;
  private final ArrayList<StatementHolder> activeStatements;
  private final NonReentrantLock sync;

  ConnectionHolder(final EmbedConnection conn, final OpenConnectionArgs args,
      final int connId, final SecureRandom rnd) throws SQLException {
    this.conn = conn;
    this.connId = connId;

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
        }
        else {
          tokenSize = args.getTokenSize();
        }
      }
      byte[] rndBytes = new byte[tokenSize];
      rnd.nextBytes(rndBytes);
      this.token = ByteBuffer.wrap(rndBytes);
    }
    else {
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
    this.reusableStatement = (EmbedStatement)conn.createStatement();
    this.activeStatements = new ArrayList<>(4);
    this.sync = new NonReentrantLock(true);
  }

  static class ResultSetHolder {
    protected ResultSet resultSet;
    protected int rsCursorId;
    protected int rsOffset;

    ResultSetHolder(ResultSet rs, int cursorId, int offset) {
      this.resultSet = rs;
      this.rsCursorId = cursorId;
      this.rsOffset = offset;
    }
  }

  final class StatementHolder extends ResultSetHolder {
    private final Statement stmt;
    private final StatementAttrs stmtAttrs;
    private final int stmtId;
    private final String sql;
    private ArrayList<ResultSetHolder> moreResultSets;

    private StatementHolder(Statement stmt, StatementAttrs attrs, int stmtId,
        String sql) {
      super(null, snappydataConstants.INVALID_ID, 0);
      this.stmt = stmt;
      this.stmtAttrs = attrs;
      this.stmtId = stmtId;
      this.sql = sql;
    }

    final ConnectionHolder getConnectionHolder() {
      return ConnectionHolder.this;
    }

    final Statement getStatement() {
      return this.stmt;
    }

    final int getStatementId() {
      return this.stmtId;
    }

    final String getSQL() {
      return this.sql;
    }

    final StatementAttrs getStatementAttrs() {
      return this.stmtAttrs;
    }

    ResultSetHolder addResultSet(ResultSet rs, int cursorId) {
      final NonReentrantLock sync = ConnectionHolder.this.sync;
      sync.lock();
      try {
        return addResultSetNoLock(rs, cursorId);
      } finally {
        sync.unlock();
      }
    }

    private ResultSetHolder addResultSetNoLock(ResultSet rs, int cursorId) {
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

    ResultSetHolder findResultSet(int cursorId) {
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

    ResultSet removeResultSet(int cursorId) {
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

    void closeResultSet(int cursorId, final SnappyDataServiceImpl service) {
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

  EmbedStatement createNewStatement(StatementAttrs attrs) throws SQLException {
    // Get the result type
    int resultSetType = SnappyDataServiceImpl.getResultType(attrs);
    // Get the resultSetConcurrency
    int resultSetConcurrency = SnappyDataServiceImpl.getResultSetConcurrency(attrs);
    // Get the resultSetHoldability
    int resultSetHoldability = SnappyDataServiceImpl.getResultSetHoldability(attrs);
    this.sync.lock();
    try {
      final EmbedStatement stmt = this.reusableStatement;
      if (stmt != null) {
        stmt.reset(resultSetType, resultSetConcurrency, resultSetHoldability);
        this.reusableStatement = null;
        return stmt;
      }
    } finally {
      this.sync.unlock();
    }
    return (EmbedStatement)this.conn.createStatement(resultSetType,
        resultSetConcurrency, resultSetHoldability);
  }

  final EmbedConnection getConnection() {
    return this.conn;
  }

  final int getConnectionId() {
    return this.connId;
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
    }
    else {
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

  void setStatementForReuse(EmbedStatement stmt) throws SQLException {
    this.sync.lock();
    try {
      setStatementForReuseNoLock(stmt);
    } finally {
      this.sync.unlock();
    }
  }

  private void setStatementForReuseNoLock(final EmbedStatement stmt)
      throws SQLException {
    if (this.reusableStatement == null) {
      stmt.resetForReuse();
      this.reusableStatement = stmt;
    }
    else {
      stmt.close();
    }
  }

  StatementHolder registerStatement(Statement stmt, StatementAttrs attrs,
      int stmtId, String preparedSQL) {
    StatementHolder stmtHolder;
    this.sync.lock();
    try {
      stmtHolder = new StatementHolder(stmt, attrs, stmtId, preparedSQL);
      this.activeStatements.add(stmtHolder);
    } finally {
      this.sync.unlock();
    }
    return stmtHolder;
  }

  StatementHolder registerResultSet(Statement stmt, StatementAttrs attrs,
      int stmtId, ResultSet rs, int cursorId, String sql) {
    StatementHolder stmtHolder;
    this.sync.lock();
    try {
      stmtHolder = new StatementHolder(stmt, attrs, stmtId, sql);
      stmtHolder.addResultSetNoLock(rs, cursorId);
      this.activeStatements.add(stmtHolder);
    } finally {
      this.sync.unlock();
    }
    return stmtHolder;
  }

  void closeStatement(final StatementHolder stmtHolder,
      final SnappyDataServiceImpl service) {
    final int stmtId = stmtHolder.getStatementId();
    this.sync.lock();
    try {
      final Statement stmt;
      final EmbedStatement estmt;
 
      removeActiveStatementNoLock(stmtHolder);
      stmtHolder.closeAllResultSets(service);
      stmt = stmtHolder.getStatement();
      // set statement for reuse now that it is being closed on client
      if (stmt instanceof EmbedStatement
          && !(estmt = (EmbedStatement)stmt).isPrepared()) {
        setStatementForReuseNoLock(estmt);
      }
      else if (stmt != null) {
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
    final ArrayList<StatementHolder> statements = this.activeStatements;
    int size = statements.size();
    // usually we will find the statement faster from the back
    while (--size >= 0) {
      if (statements.get(size) == stmtHolder) {
        statements.remove(size);
        break;
      }
    }
  }

  void close(final SnappyDataServiceImpl service) {
    this.sync.lock();
    try {
      for (StatementHolder stmtHolder : this.activeStatements) {
        stmtHolder.closeAllResultSets(service);
        Statement stmt = stmtHolder.getStatement();
        if (stmt != null) {
          try {
            stmt.close();
          } catch (SQLException sqle) {
            // ignore exception at this point
            service.logger.error("unexpected exception in Statement.close()",
                sqle);
          } finally {
            service.statementMap.removePrimitive(stmtHolder.getStatementId());
          }
        }
      }

      if (this.reusableStatement != null) {
        try {
          this.reusableStatement.close();
        } catch (SQLException sqle) {
          // ignore exception at this point
          service.logger.error("unexpected exception in Statement.close()",
              sqle);
        }
      }
      try {
        this.conn.close();
      } catch (SQLException sqle) {
        // force close at this point
        this.conn.forceClose();
      }
    } finally {
      this.sync.unlock();
    }
  }

  final boolean equals(ByteBuffer otherId) {
    return ClientSharedUtils.equalBuffers(this.token, otherId);
  }

  @Override
  public final int hashCode() {
    return this.connId;
  }
}
