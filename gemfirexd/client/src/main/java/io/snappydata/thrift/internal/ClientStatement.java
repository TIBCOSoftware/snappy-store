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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.*;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.ThriftExceptionUtil;

/**
 * Implementation of {@link Statement} for JDBC client.
 */
public class ClientStatement extends ClientFetchColumnValue implements
    Statement {

  protected final ClientConnection conn;
  protected final StatementAttrs attrs;
  // volatile for cancel which can happen from another thread
  protected volatile long statementId;
  protected RowSet currentRowSet;
  protected int currentUpdateCount;
  protected RowSet currentGeneratedKeys;
  protected volatile SnappyExceptionData warnings;
  protected volatile boolean isClosed;
  protected ArrayList<String> batchSQLs;

  private ClientStatement(ClientConnection conn, int holdability) {
    super(conn.getClientService(), (byte)snappydataConstants.INVALID_ID);
    this.conn = conn;
    this.attrs = (conn.commonAttrs != null
        ? new StatementAttrs(conn.commonAttrs) : new StatementAttrs())
        .setPendingTransactionAttrs(conn.getPendingTXFlags());
    if (holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
      this.attrs.setHoldCursorsOverCommit(true);
    }
    if (this.service.lobChunkSize > 0) {
      this.attrs.setLobChunkSize(this.service.lobChunkSize);
    }
    this.currentUpdateCount = -1;
  }

  ClientStatement(ClientConnection conn) {
    this(conn, conn.getHoldability());
    this.isClosed = false;
  }

  ClientStatement(ClientConnection conn, int rsType, int rsConcurrency,
      int rsHoldability) {
    this(conn, conn.getHoldability());
    byte attrsRsType = (byte)Converters.getThriftResultSetType(rsType);
    if (attrsRsType != snappydataConstants.DEFAULT_RESULTSET_TYPE) {
      this.attrs.setResultSetType(attrsRsType);
    }
    if (rsConcurrency == ResultSet.CONCUR_UPDATABLE) {
      this.attrs.setUpdatable(true);
    }
    if (rsHoldability == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
      this.attrs.setHoldCursorsOverCommit(true);
    } else if (rsHoldability == ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      this.attrs.setHoldCursorsOverCommit(false);
    }
    this.isClosed = false;
  }

  protected final void checkClosed() throws SQLException {
    if (this.service.isClosed()) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.NO_CURRENT_CONNECTION, null);
    } else {
      if (this.isClosed) {
        throw ThriftExceptionUtil.newSQLException(SQLState.ALREADY_CLOSED,
            null, "Statement");
      }
    }
  }

  public final StatementAttrs getAttributes() {
    final StatementAttrs attrs = this.attrs;
    return attrs.__isset_bitfield == 0 && attrs.autoIncColumnNames == null
        && attrs.autoIncColumns == null && attrs.cursorName == null
        && attrs.pendingTransactionAttrs == null && attrs.bucketIds == null
        && attrs.snapshotTransactionId == null ? null : attrs;
  }

  public final void setLocalExecutionBucketIds(Set<Integer> bucketIds,
      String tableName, boolean retain) {
    setLocalExecutionBucketIds(this.attrs, bucketIds, tableName, retain);
  }

  public static StatementAttrs setLocalExecutionBucketIds(StatementAttrs attrs,
      Set<Integer> bucketIds, String tableName, boolean retain) {
    return attrs.setBucketIds(bucketIds).setBucketIdsTable(tableName)
        .setRetainBucketIds(retain);
  }

  public final void setMetadataVersion(int version) {
    this.attrs.setMetadataVersion(version);
  }

  public final void setSnapshotTransactionId(String txId) {
    if (txId != null && !txId.isEmpty()) {
      this.attrs.setSnapshotTransactionId(txId);
    } else {
      this.attrs.unsetSnapshotTransactionId();
    }
  }

  final void clearPendingTransactionAttrs() {
    Map<TransactionAttribute, Boolean> txFlags = this.attrs
        .getPendingTransactionAttrs();
    if (txFlags != null) {
      this.conn.lock();
      try {
        // It is possible that other statements created off the same connection
        // flushed the pending TransactionAttribute flags and/or new flags got
        // added after this statement creation, so go through each flag and
        // flush only the matching flags with same flushed values
        for (Map.Entry<TransactionAttribute, Boolean> e : txFlags.entrySet()) {
          TransactionAttribute txFlag = e.getKey();
          boolean isSet = e.getValue();
          if (isSet == this.service.isTXFlagSet(txFlag, !isSet)) {
            this.conn.pendingTXFlags.remove(txFlag);
          }
        }
      } finally {
        this.conn.unlock();
      }
      this.attrs.setPendingTransactionAttrs(null);
    }
  }

  @Override
  protected void reset() {
    super.reset();
    setCurrentRowSet(null);
    this.currentUpdateCount = -1;
    this.currentGeneratedKeys = null;
    this.warnings = null;
  }

  protected void setCurrentRowSet(RowSet rs) {
    if (rs != null && (rs.getMetadata() != null || rs.getRowsSize() > 0)) {
      this.currentRowSet = rs;
      this.statementId = rs.statementId;
      setCurrentSource(snappydataConstants.BULK_CLOSE_STATEMENT,
          rs.statementId, rs);
    } else {
      this.currentRowSet = null;
      setCurrentSource(snappydataConstants.BULK_CLOSE_STATEMENT,
          snappydataConstants.INVALID_ID, null);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    checkClosed();
    reset();
    try {
      RowSet rs = this.service.executeQuery(sql, getAttributes());
      clearPendingTransactionAttrs();
      setCurrentRowSet(rs);
      this.warnings = rs.getWarnings();
      return new ClientResultSet(this.conn, this, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int executeUpdate(String sql) throws SQLException {
    return executeUpdate(sql, false, null, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxFieldSize() throws SQLException {
    return this.attrs.getMaxFieldSize();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    checkClosed();

    if (max > 0) {
      this.attrs.setMaxFieldSize(max);
    } else if (max == 0) {
      this.attrs.unsetMaxFieldSize();
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.INVALID_MAXFIELD_SIZE, null, max);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxRows() throws SQLException {
    return this.attrs.getMaxRows();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setMaxRows(int max) throws SQLException {
    checkClosed();

    if (max > 0) {
      this.attrs.setMaxRows(max);
    } else if (max == 0) {
      this.attrs.unsetMaxRows();
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.INVALID_MAX_ROWS_VALUE, null, max);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    checkClosed();
    this.attrs.setDoEscapeProcessing(enable);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getQueryTimeout() throws SQLException {
    return this.attrs.getTimeout();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    checkClosed();

    if (seconds > 0) {
      this.attrs.setTimeout(seconds);
    } else if (seconds == 0) {
      this.attrs.unsetTimeout();
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.INVALID_QUERYTIMEOUT_VALUE, null, seconds);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    // get the source before clearing the finalizer
    final HostConnection source = getLobSource(false, "closeStatement");
    clearFinalizer();

    // closing an already closed Statement is a no-op as per JDBC spec
    if (isClosed()) {
      return;
    }

    try {
      this.service.closeStatement(source, this.statementId, 0);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.isClosed = true;
      reset();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void cancel() throws SQLException {
    checkClosed();
    try {
      this.service.cancelStatement(getLobSource(true, "cancelStatement"),
          this.statementId);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLWarning getWarnings() throws SQLException {
    final RowSet rs = this.currentRowSet;
    SnappyExceptionData warning;
    if (rs != null) {
      warning = rs.getWarnings();
    } else {
      warning = this.warnings;
    }
    if (warning != null) {
      return ThriftExceptionUtil.newSQLWarning(warning, null);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearWarnings() throws SQLException {
    final RowSet rs = this.currentRowSet;
    if (rs != null) {
      rs.setWarnings(null);
    }
    this.warnings = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCursorName(String name) throws SQLException {
    checkClosed();
    this.attrs.setCursorName(name);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean execute(String sql) throws SQLException {
    return execute(sql, false, null, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getResultSet() throws SQLException {
    checkClosed();
    final RowSet rs = this.currentRowSet;
    if (rs != null) {
      return new ClientResultSet(this.conn, this, rs);
    } else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getUpdateCount() throws SQLException {
    checkClosed();
    return this.currentUpdateCount;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_ALL_RESULTS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkClosed();
    if (direction == ResultSet.FETCH_REVERSE) {
      this.attrs.setFetchReverse(true);
    } else if (direction == ResultSet.FETCH_FORWARD) {
      this.attrs.setFetchReverse(false);
    } else if (this.attrs.isSetFetchReverse()) {
      this.attrs.setFetchReverseIsSet(false);
      this.attrs.setFetchReverse(false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFetchDirection() throws SQLException {
    if (this.attrs.isSetFetchReverse()) {
      return this.attrs.isFetchReverse() ? ResultSet.FETCH_REVERSE
          : ResultSet.FETCH_FORWARD;
    } else {
      return ResultSet.FETCH_UNKNOWN;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkClosed();

    final int maxRows;
    if (rows > 0 && ((maxRows = getMaxRows()) == 0 || rows <= maxRows)) {
      this.attrs.setBatchSize(rows);
    } else if (rows == 0) {
      this.attrs.unsetBatchSize();
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.INVALID_FETCH_SIZE,
          null, rows);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFetchSize() {
    return this.attrs.isSetBatchSize() ? this.attrs.batchSize
        : snappydataConstants.DEFAULT_RESULTSET_BATCHSIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getResultSetConcurrency() throws SQLException {
    return this.attrs.isSetUpdatable() && this.attrs.isUpdatable()
        ? ResultSet.CONCUR_UPDATABLE : ResultSet.CONCUR_READ_ONLY;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getResultSetType() throws SQLException {
    if (this.attrs.isSetResultSetType()) {
      return Converters.getJdbcResultSetType(this.attrs.getResultSetType());
    } else {
      return ResultSet.TYPE_FORWARD_ONLY;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addBatch(String sql) throws SQLException {
    checkClosed();
    if (this.batchSQLs == null) {
      this.batchSQLs = new ArrayList<>();
    }
    this.batchSQLs.add(sql);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void clearBatch() throws SQLException {
    checkClosed();
    clearBatchData();
  }

  protected void clearBatchData() {
    this.batchSQLs = null;
    reset();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] executeBatch() throws SQLException {
    checkClosed();
    reset();
    final ArrayList<String> batch = this.batchSQLs;
    if (batch != null && batch.size() > 0) {
      try {
        UpdateResult ur = this.service.executeUpdate(batch, getAttributes());
        clearPendingTransactionAttrs();
        this.warnings = ur.getWarnings();
        this.currentGeneratedKeys = ur.getGeneratedKeys();
        List<Integer> updateCounts = ur.getBatchUpdateCounts();
        clearBatchData();
        if (updateCounts != null) {
          int[] result = new int[updateCounts.size()];
          for (int i = 0; i < result.length; i++) {
            result[i] = updateCounts.get(i);
          }
          return result;
        } else if (batch.size() == 1) {
          return new int[]{ur.getUpdateCount()};
        }
      } catch (SnappyException se) {
        throw ThriftExceptionUtil.newSQLException(se);
      }
    }
    return new int[0];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final ClientConnection getConnection() throws SQLException {
    checkClosed();
    return this.conn;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getMoreResults(int current) throws SQLException {
    checkClosed();

    RowSet rs = this.currentRowSet;
    if (rs != null &&
        (rs.flags & snappydataConstants.ROWSET_HAS_MORE_ROWSETS) != 0) {
      byte rsFlag = snappydataConstants.NEXTRS_CLOSE_ALL_RESULTS;
      switch (current) {
        case CLOSE_ALL_RESULTS:
          break;
        case CLOSE_CURRENT_RESULT:
          rsFlag = snappydataConstants.NEXTRS_CLOSE_CURRENT_RESULT;
          break;
        case KEEP_CURRENT_RESULT:
          rsFlag = snappydataConstants.NEXTRS_KEEP_CURRENT_RESULT;
          break;
      }
      // go to next results
      try {
        rs = this.service.getNextResultSet(
            getLobSource(true, "getMoreResults"), rs.cursorId, rsFlag);
        reset();
        setCurrentRowSet(rs);
        return this.currentRowSet != null;
      } catch (SnappyException se) {
        throw ThriftExceptionUtil.newSQLException(se);
      }
    } else {
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    checkClosed();
    final RowSet rs = this.currentGeneratedKeys;
    if (rs != null) {
      return new ClientResultSet(this.conn, this, rs);
    } else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys)
      throws SQLException {
    return executeUpdate(sql, autoGeneratedKeys == RETURN_GENERATED_KEYS,
        null, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int executeUpdate(final String sql, int[] columnIndexes)
      throws SQLException {
    if (columnIndexes != null && columnIndexes.length == 0) {
      columnIndexes = null;
    }
    return executeUpdate(sql, columnIndexes != null, columnIndexes, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int executeUpdate(final String sql, String[] columnNames)
      throws SQLException {
    if (columnNames != null && columnNames.length == 0) {
      columnNames = null;
    }
    return executeUpdate(sql, columnNames != null, null, columnNames);
  }

  private ArrayList<Integer> getIntegerList(int[] array) {
    final int len = array.length;
    ArrayList<Integer> list = new ArrayList<>(len);
    for (int v : array) {
      list.add(v);
    }
    return list;
  }

  protected final void setAutoIncAttributes(boolean getAutoInc,
      int[] autoIncColumns, String[] autoIncColumnNames) {
    if (getAutoInc) {
      this.attrs.setRequireAutoIncCols(true);
    } else if (this.attrs.isSetRequireAutoIncCols()) {
      this.attrs.setRequireAutoIncCols(false);
      this.attrs.setRequireAutoIncColsIsSet(false);
    }
    if (autoIncColumns != null) {
      this.attrs.setAutoIncColumns(getIntegerList(autoIncColumns));
    } else {
      this.attrs.setAutoIncColumns(null);
    }
    if (autoIncColumnNames != null) {
      this.attrs.setAutoIncColumnNames(Arrays.asList(autoIncColumnNames));
    } else {
      this.attrs.setAutoIncColumnNames(null);
    }
  }

  protected int executeUpdate(String sql, boolean getAutoInc,
      int[] autoIncColumns, String[] autoIncColumnNames) throws SQLException {
    checkClosed();
    reset();
    setAutoIncAttributes(getAutoInc, autoIncColumns, autoIncColumnNames);
    try {
      UpdateResult ur = this.service.executeUpdate(
          Collections.singletonList(sql), getAttributes());
      clearPendingTransactionAttrs();
      if (getAutoInc) {
        this.currentGeneratedKeys = ur.getGeneratedKeys();
      }
      this.warnings = ur.getWarnings();
      return (this.currentUpdateCount = ur.getUpdateCount());
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean execute(final String sql, int autoGeneratedKeys)
      throws SQLException {
    return execute(sql, autoGeneratedKeys == RETURN_GENERATED_KEYS, null,
        null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    if (columnIndexes != null && columnIndexes.length == 0) {
      columnIndexes = null;
    }
    return execute(sql, columnIndexes != null, columnIndexes, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean execute(String sql, String[] columnNames)
      throws SQLException {
    if (columnNames != null && columnNames.length == 0) {
      columnNames = null;
    }
    return execute(sql, columnNames != null, null, columnNames);
  }

  protected final boolean execute(final String sql, boolean getAutoInc,
      int[] autoIncColumns, String[] autoIncColumnNames) throws SQLException {
    checkClosed();
    reset();
    setAutoIncAttributes(getAutoInc, autoIncColumns, autoIncColumnNames);
    try {
      StatementResult sr = this.service.execute(sql, null, getAttributes());
      clearPendingTransactionAttrs();
      this.warnings = sr.getWarnings();
      if (getAutoInc) {
        this.currentGeneratedKeys = sr.getGeneratedKeys();
      }
      initializeProcedureOutParams(sr);
      final RowSet rs = sr.getResultSet();
      if (rs != null) {
        setCurrentRowSet(rs);
        return true;
      } else {
        this.currentUpdateCount = sr.getUpdateCount();
        return false;
      }
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  protected void initializeProcedureOutParams(
      StatementResult sr) throws SQLException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getResultSetHoldability() throws SQLException {
    return this.attrs.isSetHoldCursorsOverCommit()
        && this.attrs.isHoldCursorsOverCommit()
        ? ResultSet.HOLD_CURSORS_OVER_COMMIT
        : ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isClosed() {
    return this.isClosed || this.service.isClosed();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    checkClosed();
    this.attrs.setPoolable(poolable);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isPoolable() throws SQLException {
    return this.attrs.isSetPoolable() && this.attrs.isPoolable();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    checkClosed();
    try {
      return iface.cast(this);
    } catch (ClassCastException cce) {
      throw ThriftExceptionUtil.newSQLException(SQLState.UNABLE_TO_UNWRAP,
          cce, iface);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    checkClosed();
    return iface.isInstance(this);
  }

  // JDBC 4.1 methods

  @Override
  public void closeOnCompletion() throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "Statement.closeOnCompletion()");
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "Statement.isCloseOnCompletion()");
  }
}
