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

package io.snappydata.thrift.internal;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ReverseListIterator;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.ResultSetWithNull;
import io.snappydata.thrift.*;
import io.snappydata.thrift.common.ColumnValueConverter;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.ThriftExceptionUtil;

/**
 * Implementation of {@link ResultSet} for JDBC client.
 */
@SuppressWarnings("WeakerAccess")
public final class ClientResultSet extends ClientFetchColumnValue implements
    ResultSetWithNull {

  private final ClientStatement statement;
  private final StatementAttrs attrs;
  private long cursorId;
  private RowSet rowSet;
  private int numColumns;
  private ListIterator<Row> rowsIter;
  private Row currentRow;
  private TObjectIntHashMap columnNameToIndex;
  private int fetchDirection;
  private int fetchSize;
  private Row changedRow;
  private Row insertRow;
  private BitSet changedColumns;
  private CursorUpdateOperation cursorOperationType;

  ClientResultSet(ClientConnection conn, StatementAttrs attrs, RowSet rs) {
    this(conn, null, attrs, rs);
  }

  ClientResultSet(ClientConnection conn, ClientStatement statement, RowSet rs) {
    this(conn, statement, statement.attrs, rs);
  }

  private ClientResultSet(ClientConnection conn, ClientStatement statement,
      StatementAttrs attrs, RowSet rs) {
    super(conn.getClientService(), rs.cursorId != snappydataConstants.INVALID_ID
        ? snappydataConstants.BULK_CLOSE_RESULTSET : snappydataConstants.INVALID_ID);
    this.statement = statement;
    this.attrs = attrs;
    this.rowsIter = rs.rows.listIterator();
    this.fetchDirection = attrs.fetchReverse ? FETCH_REVERSE : FETCH_FORWARD;
    if (attrs.isSetBatchSize()) {
      this.fetchSize = attrs.batchSize;
    }
    initRowSet(rs);
  }

  private void clearForPositioning() {
    this.currentRow = null;
    super.reset();
  }

  private void initRowSet(RowSet rs) {
    // copy metadata from previous RowSet if not set by server
    if (rs.metadata == null) {
      rs.setMetadata(this.rowSet.metadata);
    }
    this.rowSet = rs;
    this.numColumns = rs.metadata.size();
    this.cursorId = rs.cursorId;
    setCurrentSource(snappydataConstants.BULK_CLOSE_RESULTSET, rs.cursorId, rs);
  }

  /**
   * Fetch a row set at given position in terms of number of rows having
   * relative or absolute position. If the position is relative, then fetch the
   * row set starting at the given number of rows relative to the current
   * position of the local cursor (and not the remote cursor).
   * <p>
   * This method assumes that caller has already searched in the current row
   * set, and invoking this means that the rows at given position is not present
   * locally and has to be fetched from server.
   */
  private boolean fetchRowSet(boolean isAbsolute, int rowPosition,
      boolean fetchReverse) throws SQLException {
    assert rowPosition != 0;

    RowSet rs;
    try {
      final int offset = fetchReverse ? (rowPosition + 1) : (rowPosition - 1);
      rs = this.service.scrollCursor(getLobSource(true, "scrollCursor"),
          this.cursorId, offset, isAbsolute, fetchReverse, this.fetchSize);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
    if (rs != null) {
      if (fetchReverse) {
        this.rowsIter = new ReverseListIterator<>(rs.rows, 0);
      }
      else {
        this.rowsIter = rs.rows.listIterator();
      }
      initRowSet(rs);
      return true;
    }
    else {
      // position before first/last row as per JDBC requirement
      if (rowPosition > 0) {
        this.rowsIter = this.rowSet.rows
            .listIterator(this.rowSet.rows.size());
      }
      else {
        this.rowsIter = this.rowSet.rows.listIterator();
      }
      return false;
    }
  }

  final void checkClosed() throws SQLException {
    if (this.service.isClosed()) {
      this.rowSet = null;
      this.rowsIter = null;
      this.currentRow = null;
      throw ThriftExceptionUtil.newSQLException(
          SQLState.NO_CURRENT_CONNECTION);
    } else {
      if (this.rowSet == null) {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.CLIENT_RESULT_SET_NOT_OPEN);
      }
    }
  }

  final void checkOnRow() throws SQLException {
    if (this.currentRow == null) {
      throw nullRowException();
    }
  }

  final SQLException nullRowException() {
    if (this.rowSet == null) {
      return ThriftExceptionUtil
          .newSQLException(SQLState.CLIENT_RESULT_SET_NOT_OPEN);
    }
    else {
      return ThriftExceptionUtil.newSQLException(SQLState.NO_CURRENT_ROW);
    }
  }

  final Row checkValidColumn(int columnIndex) throws SQLException {
    final Row currentRow = this.currentRow;
    if (currentRow != null) {
      if ((columnIndex >= 1) && (columnIndex <= this.numColumns)) {
        return currentRow;
      }
      else {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_INVALID_COLUMN_POSITION, null, columnIndex,
            this.numColumns);
      }
    }
    else {
      throw nullRowException();
    }
  }

  final void checkScrollable() throws SQLException {
    if (this.attrs.getResultSetType() == snappydataConstants.RESULTSET_TYPE_FORWARD_ONLY) {
      throw ThriftExceptionUtil
          .newSQLException(SQLState.CURSOR_MUST_BE_SCROLLABLE);
    }
  }

  final void checkUpdatable(String operation) throws SQLException {
    if (this.attrs.updatable && this.cursorId != snappydataConstants.INVALID_ID) {
      checkOnRow();
    }
    else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.UPDATABLE_RESULTSET_API_DISALLOWED, null, operation);
    }
  }

  final int getSnappyType(int columnIndex, Row currentRow) {
    final int index = columnIndex - 1;
    int type = currentRow.getType(index);
    if (type > 0) {
      return type;
    }
    // update the row with the actual type from meta-data if required
    int expectedType = -this.rowSet.metadata.get(index).type.getValue();
    if (type != expectedType) {
      currentRow.setType(index, expectedType);
    }
    return expectedType;
  }

  final int getColumnIndex(final String columnName) throws SQLException {
    if (columnName != null) {
      if (this.columnNameToIndex == null) {
        checkClosed();
        this.columnNameToIndex = buildColumnNameToIndex(this.rowSet.metadata);
      }
      int index = this.columnNameToIndex.get(columnName);
      if (index > 0) {
        return index;
      }
      index = this.columnNameToIndex.get(
          SharedUtils.SQLToUpperCase(columnName));
      if (index > 0) {
        return index;
      } else {
        throw ThriftExceptionUtil.newSQLException(SQLState.COLUMN_NOT_FOUND,
            null, columnName);
      }
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.NULL_COLUMN_NAME);
    }
  }

  static TObjectIntHashMap buildColumnNameToIndex(
      final List<ColumnDescriptor> metadata) {
    final int size = metadata.size();
    final TObjectIntHashMap columnNameToIndex = new TObjectIntHashMap(size);
    // index is required to be 1-based (start from end for the reverse iterator)
    int index = size, dotIndex;
    // doing reverse iteration to prefer column names at front in case of
    // column name clashes
    ColumnDescriptor desc;
    String name, tableName;
    for (ListIterator<ColumnDescriptor> itr = metadata.listIterator(size);
         itr.hasPrevious(); index--) {
      desc = itr.previous();
      name = desc.getName();
      if (name == null || name.isEmpty()) {
        continue;
      }
      tableName = desc.getFullTableName();
      // allow looking up using name, table.name and schema.table.name
      columnNameToIndex.put(name, index);
      if (tableName != null && !tableName.isEmpty()) {
        columnNameToIndex.put(tableName + '.' + name, index);
        dotIndex = tableName.indexOf('.');
        if (dotIndex > 0) {
          columnNameToIndex.put(tableName.substring(dotIndex + 1) + '.'
              + name, index);
        }
      }
    }
    return columnNameToIndex;
  }

  private boolean moveNext() {
    if (this.rowsIter.hasNext()) {
      setCurrentRow(this.rowsIter.next());
      return true;
    }
    else {
      return false;
    }
  }

  private boolean movePrevious() {
    if (this.rowsIter.hasPrevious()) {
      setCurrentRow(this.rowsIter.previous());
      return true;
    }
    else {
      return false;
    }
  }

  private void setCurrentRow(Row row) {
    this.currentRow = row;
    if (this.cursorOperationType != null) {
      this.cursorOperationType = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean next() throws SQLException {
    checkClosed();
    clearForPositioning();

    if (moveNext()) {
      return true;
    }
    else if ((this.rowSet.flags & snappydataConstants.ROWSET_LAST_BATCH) == 0) {
      if (fetchRowSet(false, 1, false)) {
        return moveNext();
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    // get the source before clearing the finalizer
    final HostConnection source = getLobSource(false, "closeResultSet");
    clearFinalizer();

    // closing an already closed ResultSet is a no-op as per JDBC spec
    if (isClosed()) {
      return;
    }

    this.rowSet = null;
    this.rowsIter = null;
    this.currentRow = null;
    try {
      this.service.closeResultSet(source, this.cursorId, 0);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      reset();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isNull(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return currentRow.isNull(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean wasNull() throws SQLException {
    return this.wasNull;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final String getString(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getString(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean getBoolean(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBoolean(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte getByte(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getByte(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final short getShort(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getShort(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int getInt(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getInt(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long getLong(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getLong(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final float getFloat(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getFloat(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final double getDouble(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getDouble(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBigDecimal(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final BigDecimal getBigDecimal(int columnIndex, int scale)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBigDecimal(columnIndex, scale, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte[] getBytes(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBytes(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Date getDate(int columnIndex) throws SQLException {
    return getDate(columnIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Time getTime(int columnIndex) throws SQLException {
    return getTime(columnIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Timestamp getTimestamp(int columnIndex) throws SQLException {
    return getTimestamp(columnIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Date getDate(int columnIndex, Calendar cal)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getDate(columnIndex, cal, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Time getTime(int columnIndex, Calendar cal)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getTime(columnIndex, cal, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Timestamp getTimestamp(int columnIndex, Calendar cal)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getTimestamp(columnIndex, cal, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getObject(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getObject(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getObject(columnIndex, map, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getAsciiStream(int columnIndex)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getAsciiStream(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getUnicodeStream(int columnIndex)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("getUnicodeStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getBinaryStream(int columnIndex)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBinaryStream(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Reader getCharacterStream(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getCharacterStream(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Blob getBlob(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getBlob(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Clob getClob(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getClob(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getRef(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Array getArray(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getArray(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URL getURL(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getURL(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getRowId(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getNClob(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getSQLXML(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getNString(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getNString(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getNCharacterStream(columnIndex, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final String getString(String columnLabel) throws SQLException {
    return getString(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte getByte(String columnLabel) throws SQLException {
    return getByte(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final short getShort(String columnLabel) throws SQLException {
    return getShort(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int getInt(String columnLabel) throws SQLException {
    return getInt(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long getLong(String columnLabel) throws SQLException {
    return getLong(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final float getFloat(String columnLabel) throws SQLException {
    return getFloat(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final double getDouble(String columnLabel) throws SQLException {
    return getDouble(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final BigDecimal getBigDecimal(String columnLabel)
      throws SQLException {
    return getBigDecimal(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final BigDecimal getBigDecimal(String columnLabel, int scale)
      throws SQLException {
    return getBigDecimal(getColumnIndex(columnLabel), scale);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Date getDate(String columnLabel) throws SQLException {
    return getDate(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Time getTime(String columnLabel) throws SQLException {
    return getTime(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Date getDate(String columnLabel, Calendar cal)
      throws SQLException {
    return getDate(getColumnIndex(columnLabel), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Time getTime(String columnLabel, Calendar cal)
      throws SQLException {
    return getTime(getColumnIndex(columnLabel), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final java.sql.Timestamp getTimestamp(String columnLabel, Calendar cal)
      throws SQLException {
    return getTimestamp(getColumnIndex(columnLabel), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getAsciiStream(String columnLabel)
      throws SQLException {
    return getAsciiStream(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getUnicodeStream(String columnLabel)
      throws SQLException {
    return getUnicodeStream(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final InputStream getBinaryStream(String columnLabel)
      throws SQLException {
    return getBinaryStream(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getObject(String columnLabel) throws SQLException {
    return getObject(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Reader getCharacterStream(String columnLabel)
      throws SQLException {
    return getCharacterStream(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getObject(String columnLabel, Map<String, Class<?>> map)
      throws SQLException {
    return getObject(getColumnIndex(columnLabel), map);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Ref getRef(String columnLabel) throws SQLException {
    return getRef(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Blob getBlob(String columnLabel) throws SQLException {
    return getBlob(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Clob getClob(String columnLabel) throws SQLException {
    return getClob(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Array getArray(String columnLabel) throws SQLException {
    return getArray(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final URL getURL(String columnLabel) throws SQLException {
    return getURL(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final RowId getRowId(String columnLabel) throws SQLException {
    return getRowId(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final NClob getNClob(String columnLabel) throws SQLException {
    return getNClob(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final SQLXML getSQLXML(String columnLabel) throws SQLException {
    return getSQLXML(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final String getNString(String columnLabel) throws SQLException {
    return getNString(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Reader getNCharacterStream(String columnLabel)
      throws SQLException {
    return getNCharacterStream(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkClosed();

    SnappyExceptionData warnings = this.rowSet.getWarnings();
    if (warnings != null) {
      return ThriftExceptionUtil.newSQLWarning(warnings, null);
    }
    else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearWarnings() throws SQLException {
    checkClosed();

    this.rowSet.setWarnings(null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCursorName() throws SQLException {
    checkClosed();

    return this.rowSet.cursorName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();

    return new ClientRSMetaData(this.rowSet.metadata);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int findColumn(String columnLabel) throws SQLException {
    return getColumnIndex(columnLabel);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isBeforeFirst() throws SQLException {
    checkClosed();

    return (this.rowSet.flags & snappydataConstants.ROWSET_BEFORE_FIRST) != 0 &&
        !this.rowsIter.hasPrevious();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isAfterLast() throws SQLException {
    checkClosed();

    return (this.rowSet.flags & snappydataConstants.ROWSET_AFTER_LAST) != 0 &&
        !this.rowsIter.hasNext();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isFirst() throws SQLException {
    checkClosed();

    return this.rowSet.offset == 0 && !this.rowsIter.hasPrevious();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isLast() throws SQLException {
    checkClosed();

    return (this.rowSet.flags & snappydataConstants.ROWSET_LAST_BATCH) != 0 &&
        !this.rowsIter.hasNext();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void beforeFirst() throws SQLException {
    checkClosed();
    checkScrollable();
    clearForPositioning();

    setBeforeFirst();
  }

  private void setBeforeFirst() throws SQLException {
    if (this.rowSet.offset == 0) {
      this.rowsIter = this.rowSet.rows.listIterator();
    }
    else {
      fetchRowSet(true, 1, false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void afterLast() throws SQLException {
    checkClosed();
    checkScrollable();
    clearForPositioning();

    if ((this.rowSet.flags & snappydataConstants.ROWSET_LAST_BATCH) != 0) {
      this.rowsIter = this.rowSet.rows.listIterator(this.rowSet.rows.size());
    }
    else {
      fetchRowSet(true, -1, true);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean first() throws SQLException {
    beforeFirst();
    return moveNext();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean last() throws SQLException {
    afterLast();
    return movePrevious();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int getRow() throws SQLException {
    this.service.checkClosedConnection();
    if (this.rowSet != null) {
      int currentOffset = this.rowsIter.nextIndex();
      return (this.rowSet.offset + currentOffset);
    }
    else {
      return 0;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean absolute(int rows) throws SQLException {
    checkClosed();
    checkScrollable();
    clearForPositioning();

    // check if row is already available in current RowSet
    if (rows > 0) {
      if (rows > this.rowSet.offset
          && rows <= (this.rowSet.offset + this.rowSet.rows.size())) {
        this.rowsIter = this.rowSet.rows.listIterator(rows
            - this.rowSet.offset - 1);
        if (moveNext()) {
          return true;
        }
      }
    } else if (rows == 0) {
      setBeforeFirst();
      return false;
    } else if ((this.rowSet.flags & snappydataConstants.ROWSET_LAST_BATCH) != 0) {
      if ((-rows) <= this.rowSet.rows.size()) {
        this.rowsIter = this.rowSet.rows.listIterator(this.rowSet.rows.size()
            + rows);
        if (moveNext()) {
          return true;
        }
      }
    }
    // for absolute position we will rely on fetchDirection to determine
    // whether to fetch in reverse or forward direction
    return fetchRowSet(true, rows, (rows > 1 &&
        this.fetchDirection == FETCH_REVERSE) || (rows == -1)) && moveNext();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean relative(int rows) throws SQLException {
    switch (rows) {
      case 1:
        return next();
      case -1:
        return previous();
      case 0:
        return true; // no-op
      default:
        checkClosed();
        checkScrollable();
        clearForPositioning();

        // check if row is already available in current RowSet
        int nextIndex = this.rowsIter.nextIndex();
        final boolean moveForward = (rows > 0);
        if (moveForward) {
          int available = (this.rowSet.rows.size() - nextIndex);
          if (rows <= available) {
            this.rowsIter = this.rowSet.rows.listIterator(nextIndex + rows
                - 1);
            return moveNext();
          } else {
            // adjust by the number available in current batch
            rows -= available;
          }
        } else { // rows < 0
          if ((rows + nextIndex) >= 0) {
            this.rowsIter = this.rowSet.rows.listIterator(nextIndex + rows
                + 1);
            return movePrevious();
          } else {
            // adjust by the number available in current batch
            rows += nextIndex;
          }
        }
        // for relative position just rely on the sign of the position for
        // fetchReverse flag
        return fetchRowSet(false, rows, !moveForward) &&
            (moveForward ? moveNext() : movePrevious());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean previous() throws SQLException {
    checkClosed();
    checkScrollable();
    clearForPositioning();

    if (movePrevious()) {
      return true;
    }
    else if (this.rowSet.offset != 0) {
      if (fetchRowSet(false, -1, true)) {
        return movePrevious();
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkClosed();
    if (direction != FETCH_FORWARD) {
      checkScrollable();
    }

    switch (direction) {
      case FETCH_FORWARD:
      case FETCH_REVERSE:
      case FETCH_UNKNOWN:
        this.fetchDirection = direction;
        break;
      default:
        throw ThriftExceptionUtil.newSQLException(
            SQLState.INVALID_FETCH_DIRECTION, null, direction);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFetchDirection() throws SQLException {
    checkClosed();

    return this.fetchDirection;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setFetchSize(int rows) throws SQLException {
    checkClosed();

    final int maxRows;
    if (rows >= 0
        && ((maxRows = this.statement.getMaxRows()) == 0 || rows <= maxRows)) {
      this.fetchSize = rows;
    }
    else {
      throw ThriftExceptionUtil.newSQLException(SQLState.INVALID_FETCH_SIZE,
          null, rows);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int getFetchSize() throws SQLException {
    checkClosed();

    return this.fetchSize;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getType() throws SQLException {
    checkClosed();

    // noinspection MagicConstant
    return Converters.getJdbcResultSetType(this.attrs.getResultSetType());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getConcurrency() throws SQLException {
    checkClosed();

    return this.attrs.updatable ? CONCUR_UPDATABLE : CONCUR_READ_ONLY;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean rowUpdated() throws SQLException {
    checkClosed();

    return this.cursorOperationType == CursorUpdateOperation.UPDATE_OP;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean rowInserted() throws SQLException {
    checkClosed();

    return this.cursorOperationType == CursorUpdateOperation.INSERT_OP;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean rowDeleted() throws SQLException {
    checkClosed();

    return this.cursorOperationType == CursorUpdateOperation.DELETE_OP;
  }

  private void initRowUpdate(String operation) throws SQLException {
    checkUpdatable(operation);

    if (this.changedColumns == null) {
      this.changedColumns = new BitSet(this.currentRow.size());
    }
    if (this.changedRow == null) {
      this.changedRow = new Row(this.currentRow);
      setCurrentRow(this.changedRow);
    }
    else if (this.currentRow != this.changedRow) {
      setCurrentRow(this.changedRow);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNull(int columnIndex) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateNull");
    final int index = columnIndex - 1;
    currentRow.setNull(index);
    this.changedColumns.set(index);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBoolean(int columnIndex, boolean x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateBoolean");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "boolean", true, columnIndex);
    cvc.setBoolean(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateByte(int columnIndex, byte x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateByte");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "byte", true, columnIndex);
    cvc.setByte(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateShort(int columnIndex, short x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateShort");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "short", true, columnIndex);
    cvc.setShort(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateInt(int columnIndex, int x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateInt");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "int", true, columnIndex);
    cvc.setInteger(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateLong(int columnIndex, long x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateLong");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "long", true, columnIndex);
    cvc.setLong(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateFloat(int columnIndex, float x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateFloat");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "float", true, columnIndex);
    cvc.setFloat(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateDouble(int columnIndex, double x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateDouble");
    ColumnValueConverter cvc = Converters.getConverter(
        getSnappyType(columnIndex, currentRow), "double", true, columnIndex);
    cvc.setDouble(currentRow, columnIndex, x);
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBigDecimal(int columnIndex, BigDecimal x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateBigDecimal");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "BigDecimal", true, columnIndex);
      cvc.setBigDecimal(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateString(int columnIndex, String x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateString");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "String", true, columnIndex);
      cvc.setString(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBytes(int columnIndex, byte[] x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateBytes");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "byte[]", true, columnIndex);
      cvc.setBytes(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateDate(int columnIndex, java.sql.Date x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateDate");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "Date", true, columnIndex);
      cvc.setDate(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateTime(int columnIndex, java.sql.Time x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateTime");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "Time", true, columnIndex);
      cvc.setTime(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateTimestamp(int columnIndex, java.sql.Timestamp x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateTimestamp");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "Timestamp", true, columnIndex);
      cvc.setTimestamp(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateObject(int columnIndex, Object x, int scaleOrLength)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateObject");
    if (x != null) {
      if (x instanceof BigDecimal) {
        BigDecimal bd = (BigDecimal)x;
        if (bd.scale() != scaleOrLength) {
          // rounding as per server side EmbedResultSet20
          bd = new BigDecimal(bd.unscaledValue(), bd.scale());
          bd = bd.setScale(scaleOrLength, BigDecimal.ROUND_HALF_DOWN);
        }
        updateBigDecimal(columnIndex, bd);
      }
      else if (x instanceof InputStream) {
        updateBinaryStream(columnIndex, (InputStream)x, scaleOrLength);
      }
      else if (x instanceof Reader) {
        updateCharacterStream(columnIndex, (Reader)x, scaleOrLength);
      }
      else {
        ColumnValueConverter cvc = Converters.getConverter(
            getSnappyType(columnIndex, currentRow), "Object", true, columnIndex);
        cvc.setObject(currentRow, columnIndex, x);
      }
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateObject(int columnIndex, Object x)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateObject");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "Object", true, columnIndex);
      cvc.setObject(currentRow, columnIndex, x);
    }
    else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNull(String columnLabel) throws SQLException {
    updateNull(getColumnIndex(columnLabel));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBoolean(String columnLabel, boolean x)
      throws SQLException {
    updateBoolean(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateByte(String columnLabel, byte x)
      throws SQLException {
    updateByte(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateShort(String columnLabel, short x)
      throws SQLException {
    updateShort(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateInt(String columnLabel, int x) throws SQLException {
    updateInt(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateLong(String columnLabel, long x)
      throws SQLException {
    updateLong(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateFloat(String columnLabel, float x)
      throws SQLException {
    updateFloat(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateDouble(String columnLabel, double x)
      throws SQLException {
    updateDouble(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBigDecimal(String columnLabel, BigDecimal x)
      throws SQLException {
    updateBigDecimal(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateString(String columnLabel, String x)
      throws SQLException {
    updateString(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBytes(String columnLabel, byte[] x)
      throws SQLException {
    updateBytes(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateDate(String columnLabel, java.sql.Date x)
      throws SQLException {
    updateDate(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateTime(String columnLabel, java.sql.Time x)
      throws SQLException {
    updateTime(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateTimestamp(String columnLabel, java.sql.Timestamp x)
      throws SQLException {
    updateTimestamp(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateObject(String columnLabel, Object x,
      int scaleOrLength) throws SQLException {
    updateObject(getColumnIndex(columnLabel), x, scaleOrLength);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateObject(String columnLabel, Object x)
      throws SQLException {
    updateObject(getColumnIndex(columnLabel), x);
  }

  private ArrayList<Integer> getChangedColumns() {
    ArrayList<Integer> changedColumns = new ArrayList<>(
        this.changedColumns.cardinality());
    for (int index = this.changedColumns.nextSetBit(0); index >= 0;
        index = this.changedColumns.nextSetBit(index + 1)) {
      changedColumns.add(index + 1);
    }
    return changedColumns;
  }

  private void resetCurrentRow() {
    int currentIndex = this.rowsIter.nextIndex() - 1;
    if (currentIndex >= 0) {
      setCurrentRow(this.rowSet.rows.get(currentIndex));
    }
    else {
      this.currentRow = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void insertRow() throws SQLException {
    checkClosed();
    checkUpdatable("insertRow");

    // TODO: handle SCROLL_SENSITIVE for insertRow/updateRow/deleteRow
    // but first need to add that to server-side

    if (this.insertRow != null && this.changedColumns != null
        && this.currentRow == this.insertRow) {
      this.insertRow.setChangedColumns(this.changedColumns);
      try {
        this.service.executeCursorUpdate(getLobSource(true, "insertRow"),
            this.cursorId, CursorUpdateOperation.INSERT_OP, insertRow,
            getChangedColumns(), this.rowsIter.nextIndex() - 1);
      } catch (SnappyException se) {
        throw ThriftExceptionUtil.newSQLException(se);
      } finally {
        this.currentRow.setChangedColumns(null);
      }
      this.changedColumns.clear();
      this.cursorOperationType = CursorUpdateOperation.INSERT_OP;
      this.insertRow = null;
    }
    else {
      throw ThriftExceptionUtil
          .newSQLException(SQLState.CURSOR_NOT_POSITIONED_ON_INSERT_ROW);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateRow() throws SQLException {
    checkClosed();
    checkUpdatable("updateRow");

    if (this.changedColumns != null) {
      if (this.currentRow == this.insertRow) {
        throw ThriftExceptionUtil.newSQLException(SQLState.NO_CURRENT_ROW);
      }
      this.currentRow.setChangedColumns(this.changedColumns);
      try {
        this.service.executeCursorUpdate(getLobSource(true, "updateRow"),
            this.cursorId, CursorUpdateOperation.UPDATE_OP, this.currentRow,
            getChangedColumns(), this.rowsIter.nextIndex() - 1);
      } catch (SnappyException se) {
        throw ThriftExceptionUtil.newSQLException(se);
      } finally {
        this.currentRow.setChangedColumns(null);
      }
      this.changedColumns.clear();
      this.cursorOperationType = CursorUpdateOperation.UPDATE_OP;
    }
    else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.CURSOR_INVALID_OPERATION_AT_CURRENT_POSITION);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void deleteRow() throws SQLException {
    checkClosed();
    checkUpdatable("deleteRow");

    if (this.currentRow == this.insertRow) {
      throw ThriftExceptionUtil.newSQLException(SQLState.NO_CURRENT_ROW);
    }
    try {
      this.service.executeCursorUpdate(getLobSource(true, "deleteRow"),
          this.cursorId, CursorUpdateOperation.DELETE_OP, null, null,
          this.rowsIter.nextIndex() - 1);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
    if (this.changedColumns != null) {
      this.changedColumns.clear();
    }
    this.cursorOperationType = CursorUpdateOperation.DELETE_OP;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void refreshRow() throws SQLException {
    // not implemented
    throw ThriftExceptionUtil.notImplemented("refreshRow");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void cancelRowUpdates() throws SQLException {
    checkClosed();
    checkUpdatable("cancelRowUpdates");

    if (this.changedColumns != null) {
      this.changedColumns.clear();
      this.changedRow = new Row(this.currentRow);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveToInsertRow() throws SQLException {
    checkClosed();
    checkUpdatable("moveToInsertRow");

    if (this.changedColumns == null) {
      this.changedColumns = new BitSet(this.currentRow.size());
    }
    if (this.insertRow == null) {
      this.insertRow = new Row(this.rowSet.metadata);
    }
    setCurrentRow(this.insertRow);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveToCurrentRow() throws SQLException {
    checkClosed();
    checkUpdatable("moveToCurrentRow");

    resetCurrentRow();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientStatement getStatement() throws SQLException {
    return this.statement;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateRef");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateRef(String columnLabel, Ref x) throws SQLException {
    updateRef(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateArray");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateArray(String columnLabel, Array x)
      throws SQLException {
    updateArray(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateRowId");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateRowId(String columnLabel, RowId x)
      throws SQLException {
    updateRowId(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getHoldability() throws SQLException {
    checkClosed();

    return this.attrs.holdCursorsOverCommit ? HOLD_CURSORS_OVER_COMMIT
        : CLOSE_CURSORS_AT_COMMIT;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isClosed() throws SQLException {
    return this.rowSet == null || this.service.isClosed();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateSQLXML");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateSQLXML(String columnLabel, SQLXML xmlObject)
      throws SQLException {
    updateSQLXML(getColumnIndex(columnLabel), xmlObject);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateBinaryStream");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "BinaryStream",
          true, columnIndex);
      cvc.setBinaryStream(currentRow, columnIndex, x, length, this.service);
    } else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBinaryStream(int columnIndex, InputStream x)
      throws SQLException {
    updateBinaryStream(columnIndex, x, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBinaryStream(int columnIndex, InputStream x,
      int length) throws SQLException {
    updateBinaryStream(columnIndex, x, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBinaryStream(String columnLabel, InputStream x,
      long length) throws SQLException {
    updateBinaryStream(getColumnIndex(columnLabel), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBinaryStream(String columnLabel, InputStream x)
      throws SQLException {
    updateBinaryStream(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBinaryStream(String columnLabel, InputStream x,
      int length) throws SQLException {
    updateBinaryStream(getColumnIndex(columnLabel), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateCharacterStream");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "CharacterStream",
          true, columnIndex);
      cvc.setCharacterStream(currentRow, columnIndex, x, length, this.service);
    } else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateCharacterStream(int columnIndex, Reader x)
      throws SQLException {
    updateCharacterStream(columnIndex, x, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateCharacterStream(int columnIndex, Reader x,
      int length) throws SQLException {
    updateCharacterStream(columnIndex, x, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateCharacterStream(String columnLabel, Reader reader,
      long length) throws SQLException {
    updateCharacterStream(getColumnIndex(columnLabel), reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateCharacterStream(String columnLabel, Reader reader)
      throws SQLException {
    updateCharacterStream(getColumnIndex(columnLabel), reader);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateCharacterStream(String columnLabel, Reader reader,
      int length) throws SQLException {
    updateCharacterStream(getColumnIndex(columnLabel), reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length)
      throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateAsciiStream");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "AsciiStream",
          true, columnIndex);
      cvc.setAsciiStream(currentRow, columnIndex, x, length, this.service);
    } else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateAsciiStream(int columnIndex, InputStream x)
      throws SQLException {
    updateAsciiStream(columnIndex, x, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateAsciiStream(int columnIndex, InputStream x,
      int length) throws SQLException {
    updateAsciiStream(columnIndex, x, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateAsciiStream(String columnLabel, InputStream x,
      long length) throws SQLException {
    updateAsciiStream(getColumnIndex(columnLabel), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateAsciiStream(String columnLabel, InputStream x)
      throws SQLException {
    updateAsciiStream(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateAsciiStream(String columnLabel, InputStream x,
      int length) throws SQLException {
    updateAsciiStream(getColumnIndex(columnLabel), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    updateBinaryStream(columnIndex, inputStream, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBlob(int columnIndex, InputStream inputStream)
      throws SQLException {
    updateBlob(columnIndex, inputStream, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBlob(int columnIndex, Blob x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateBlob");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "blob", true, columnIndex);
      cvc.setBlob(currentRow, columnIndex, x);
    } else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBlob(String columnLabel, InputStream inputStream,
      long length) throws SQLException {
    updateBlob(getColumnIndex(columnLabel), inputStream, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBlob(String columnLabel, InputStream inputStream)
      throws SQLException {
    updateBlob(getColumnIndex(columnLabel), inputStream);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateBlob(String columnLabel, Blob x)
      throws SQLException {
    updateBlob(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateClob(int columnIndex, Reader reader, long length)
      throws SQLException {
    updateCharacterStream(columnIndex, reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateClob(int columnIndex, Reader reader)
      throws SQLException {
    updateClob(columnIndex, reader, -1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateClob(int columnIndex, Clob x) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);

    initRowUpdate("updateClob");
    if (x != null) {
      ColumnValueConverter cvc = Converters.getConverter(
          getSnappyType(columnIndex, currentRow), "clob", true, columnIndex);
      cvc.setClob(currentRow, columnIndex, x);
    } else {
      currentRow.setNull(columnIndex - 1);
    }
    this.changedColumns.set(columnIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateClob(String columnLabel, Reader reader, long length)
      throws SQLException {
    updateClob(getColumnIndex(columnLabel), reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateClob(String columnLabel, Reader reader)
      throws SQLException {
    updateClob(getColumnIndex(columnLabel), reader);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateClob(String columnLabel, Clob x)
      throws SQLException {
    updateClob(getColumnIndex(columnLabel), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateNString(int columnIndex, String nString)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateNString");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNString(String columnLabel, String nString)
      throws SQLException {
    updateNString(getColumnIndex(columnLabel), nString);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length)
      throws SQLException {
    throw ThriftExceptionUtil
        .notImplemented("ResultSet.updateNCharacterStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateNCharacterStream(int columnIndex, Reader x)
      throws SQLException {
    throw ThriftExceptionUtil
        .notImplemented("ResultSet.updateNCharacterStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNCharacterStream(String columnLabel, Reader reader,
      long length) throws SQLException {
    updateNCharacterStream(getColumnIndex(columnLabel), reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNCharacterStream(String columnLabel, Reader reader)
      throws SQLException {
    updateNCharacterStream(getColumnIndex(columnLabel), reader);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateNClob(int columnIndex, Reader reader, long length)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateNClob");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNClob(int columnIndex, Reader reader)
      throws SQLException {
    updateNClob(columnIndex, reader, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("ResultSet.updateNClob");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNClob(String columnLabel, Reader reader, long length)
      throws SQLException {
    updateNClob(getColumnIndex(columnLabel), reader, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNClob(String columnLabel, Reader reader)
      throws SQLException {
    updateNClob(getColumnIndex(columnLabel), reader);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void updateNClob(String columnLabel, NClob nClob)
      throws SQLException {
    updateNClob(getColumnIndex(columnLabel), nClob);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
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
    return iface.isInstance(this);
  }

  // JDBC 4.1 methods

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    final Row currentRow = checkValidColumn(columnIndex);
    return getObject(columnIndex, type, getSnappyType(columnIndex,
        currentRow), currentRow);
  }

  @Override
  public final <T> T getObject(String columnLabel, Class<T> type)
      throws SQLException {
    return getObject(getColumnIndex(columnLabel), type);
  }
}
