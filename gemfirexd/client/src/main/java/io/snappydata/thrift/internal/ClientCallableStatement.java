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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.gemstone.gnu.trove.TIntIntHashMap;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.ColumnValue;
import io.snappydata.thrift.OutputParameter;
import io.snappydata.thrift.Row;
import io.snappydata.thrift.StatementResult;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

/**
 * Implementation of {@link CallableStatement} for JDBC client.
 */
public final class ClientCallableStatement extends ClientPreparedStatement
    implements CallableStatement {

  /**
   * Need a sorted map for outParams since the values that will be sent back by
   * server will be in order of parameter index as passed by
   * <code>registerOutParameter</code> methods.
   */
  private TreeMap<Integer, OutputParameter> outParams;
  private TIntIntHashMap outParamsPositionInRow;
  private Row outParamValues;
  private TObjectIntHashMap parameterNameToIndex;

  ClientCallableStatement(ClientConnection conn, String sql)
      throws SQLException {
    super(conn, sql);
  }

  ClientCallableStatement(ClientConnection conn, String sql, int rsType,
      int rsConcurrency, int rsHoldability) throws SQLException {
    super(conn, sql, rsType, rsConcurrency, rsHoldability);
  }

  private TreeMap<Integer, OutputParameter> getOutputParamsSet() {
    final TreeMap<Integer, OutputParameter> outParams = this.outParams;
    if (outParams != null) {
      return outParams;
    } else {
      return (this.outParams = new TreeMap<>());
    }
  }

  @Override
  protected void reset() {
    super.reset();
    this.outParamValues = null;
    this.outParamsPositionInRow = null;
  }

  @Override
  protected final Map<Integer, OutputParameter> getOutputParameters() {
    final TreeMap<Integer, OutputParameter> outParams = this.outParams;
    if (outParams == null || outParams.isEmpty()) {
      return Collections.emptyMap();
    } else {
      return outParams;
    }
  }

  @Override
  protected void initializeProcedureOutParams(
      StatementResult sr) throws SQLException {
    final TreeMap<Integer, OutputParameter> outParams = this.outParams;
    if (outParams != null) {
      Map<Integer, ColumnValue> outValues;
      if ((outValues = sr.getProcedureOutParams()) != null &&
          outValues.size() > 0) {
        setCurrentSource(snappydataConstants.BULK_CLOSE_STATEMENT, statementId,
            null);
        this.outParamsPositionInRow = new TIntIntHashMap(outParams.size());
        // starting index at 1 since get on TIntIntHashMap will give 0 if absent
        int outIndex = 1;
        // copy as a Row to outParamValues which also tracks ClientFinalizer
        // and makes the getters for all params in parent class uniform
        this.outParamValues = new Row();
        this.outParamValues.initialize(outParams.size());
        for (Integer parameterIndex : outParams.keySet()) {
          ColumnValue outValue = outValues.get(parameterIndex);
          this.outParamValues.setColumnValue(outIndex - 1, outValue);
          this.outParamsPositionInRow.put(parameterIndex, outIndex);
          outIndex++;
        }
        // create LOBs in the row, if any
        this.outParamValues.initializeLobs(this.service);
      }
    }
  }

  protected final int getOutputType(int parameterIndex) {
    return this.outParamValues.getType(parameterIndex - 1);
  }

  final int getOutputParameterIndex(int parameterIndex) throws SQLException {
    if (this.outParamValues != null) {
      int rowIndex = this.outParamsPositionInRow.get(parameterIndex);
      if (rowIndex > 0) {
        return rowIndex;
      }
    }
    return 0;
  }

  final int getParameterIndex(final String parameterName) throws SQLException {
    if (parameterName != null) {
      if (this.parameterNameToIndex == null) {
        checkClosed();
        this.parameterNameToIndex = ClientResultSet
            .buildColumnNameToIndex(this.parameterMetaData);
      }
      int index = this.parameterNameToIndex.get(parameterName);
      if (index > 0) {
        return index;
      }
      index = this.parameterNameToIndex.get(
          SharedUtils.SQLToUpperCase(parameterName));
      if (index > 0) {
        return index;
      } else {
        throw ThriftExceptionUtil.newSQLException(SQLState.COLUMN_NOT_FOUND,
            null, parameterName);
      }
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.NULL_COLUMN_NAME);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(int parameterIndex, int sqlType)
      throws SQLException {
    OutputParameter outParam = new OutputParameter(
        Converters.getThriftSQLType(sqlType));
    getOutputParamsSet().put(parameterIndex, outParam);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(int parameterIndex, int sqlType, int scale)
      throws SQLException {
    OutputParameter outParam = new OutputParameter(
        Converters.getThriftSQLType(sqlType)).setScale(scale);
    getOutputParamsSet().put(parameterIndex, outParam);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(int parameterIndex, int sqlType,
      String typeName) throws SQLException {
    OutputParameter outParam = new OutputParameter(
        Converters.getThriftSQLType(sqlType)).setTypeName(typeName);
    getOutputParamsSet().put(parameterIndex, outParam);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(String parameterName, int sqlType)
      throws SQLException {
    registerOutParameter(getParameterIndex(parameterName), sqlType);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    registerOutParameter(getParameterIndex(parameterName), sqlType, scale);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerOutParameter(String parameterName, int sqlType,
      String typeName) throws SQLException {
    registerOutParameter(getParameterIndex(parameterName), sqlType, typeName);
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
  public String getString(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getString(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getBoolean(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getBoolean(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte getByte(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getByte(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public short getShort(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getShort(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getInt(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getInt(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getLong(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getLong(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getFloat(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getFloat(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double getDouble(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getDouble(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal getBigDecimal(final int parameterIndex)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getBigDecimal(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal getBigDecimal(final int parameterIndex, final int scale)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getBigDecimal(columnIndex, scale, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getBytes(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getBytes(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(int parameterIndex) throws SQLException {
    return getDate(parameterIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(int parameterIndex) throws SQLException {
    return getTime(parameterIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(int parameterIndex) throws SQLException {
    return getTimestamp(parameterIndex, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(final int parameterIndex, final Calendar cal)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getDate(columnIndex, cal, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(final int parameterIndex, final Calendar cal)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getTime(columnIndex, cal, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(final int parameterIndex, final Calendar cal)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getTimestamp(columnIndex, cal, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getObject(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(final int parameterIndex,
      final Map<String, Class<?>> map) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getObject(columnIndex, map, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Ref getRef(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getRef(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Blob getBlob(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getBlob(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Clob getClob(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getClob(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Array getArray(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getArray(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URL getURL(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getURL(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowId getRowId(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getRowId(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLXML getSQLXML(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getSQLXML(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getNString(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getNString(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getNCharacterStream(final int parameterIndex)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getNCharacterStream(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getCharacterStream(final int parameterIndex)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getCharacterStream(columnIndex, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public NClob getNClob(final int parameterIndex) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getNClob(columnIndex, type, row);
  }

  @Override
  public <T> T getObject(final int parameterIndex, final Class<T> tp)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    final int columnIndex;
    final int outParamIndex = getOutputParameterIndex(parameterIndex);
    final int type;
    final Row row;
    if (outParamIndex > 0) {
      row = this.outParamValues;
      type = getOutputType(outParamIndex);
      columnIndex = outParamIndex;
    } else {
      row = this.paramsList;
      type = getType(parameterIndex);
      columnIndex = parameterIndex;
    }
    return getObject(columnIndex, tp, type, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getString(String parameterName) throws SQLException {
    return getString(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getBoolean(String parameterName) throws SQLException {
    return getBoolean(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte getByte(String parameterName) throws SQLException {
    return getByte(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public short getShort(String parameterName) throws SQLException {
    return getShort(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getInt(String parameterName) throws SQLException {
    return getInt(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getLong(String parameterName) throws SQLException {
    return getLong(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getFloat(String parameterName) throws SQLException {
    return getFloat(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double getDouble(String parameterName) throws SQLException {
    return getDouble(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getBytes(String parameterName) throws SQLException {
    return getBytes(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(String parameterName) throws SQLException {
    return getDate(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(String parameterName) throws SQLException {
    return getTime(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(String parameterName) throws SQLException {
    return getTimestamp(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(String parameterName) throws SQLException {
    return getObject(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal getBigDecimal(String parameterName) throws SQLException {
    return getBigDecimal(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(String parameterName, Map<String, Class<?>> map)
      throws SQLException {
    return getObject(getParameterIndex(parameterName), map);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Ref getRef(String parameterName) throws SQLException {
    return getRef(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Blob getBlob(String parameterName) throws SQLException {
    return getBlob(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Clob getClob(String parameterName) throws SQLException {
    return getClob(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Array getArray(String parameterName) throws SQLException {
    return getArray(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(String parameterName, Calendar cal) throws SQLException {
    return getDate(getParameterIndex(parameterName), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(String parameterName, Calendar cal) throws SQLException {
    return getTime(getParameterIndex(parameterName), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(String parameterName, Calendar cal)
      throws SQLException {
    return getTimestamp(getParameterIndex(parameterName), cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URL getURL(String parameterName) throws SQLException {
    return getURL(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowId getRowId(String parameterName) throws SQLException {
    return getRowId(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLXML getSQLXML(String parameterName) throws SQLException {
    return getSQLXML(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getNString(String parameterName) throws SQLException {
    return getNString(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getNCharacterStream(String parameterName) throws SQLException {
    return getNCharacterStream(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getCharacterStream(String parameterName) throws SQLException {
    return getCharacterStream(getParameterIndex(parameterName));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public NClob getNClob(String parameterName) throws SQLException {
    return getNClob(getParameterIndex(parameterName));
  }

  @Override
  public <T> T getObject(String parameterName, Class<T> type)
      throws SQLException {
    return getObject(getParameterIndex(parameterName), type);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setURL(String parameterName, URL x) throws SQLException {
    setURL(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNull(String parameterName, int sqlType) throws SQLException {
    setNull(getParameterIndex(parameterName), sqlType);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBoolean(String parameterName, boolean x) throws SQLException {
    setBoolean(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setByte(String parameterName, byte x) throws SQLException {
    setByte(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setShort(String parameterName, short x) throws SQLException {
    setShort(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setInt(String parameterName, int x) throws SQLException {
    setInt(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setLong(String parameterName, long x) throws SQLException {
    setLong(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setFloat(String parameterName, float x) throws SQLException {
    setFloat(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setDouble(String parameterName, double x) throws SQLException {
    setDouble(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBigDecimal(String parameterName, BigDecimal x)
      throws SQLException {
    setBigDecimal(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setString(String parameterName, String x) throws SQLException {
    setString(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBytes(String parameterName, byte[] x) throws SQLException {
    setBytes(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setDate(String parameterName, Date x) throws SQLException {
    setDate(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTime(String parameterName, Time x) throws SQLException {
    setTime(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTimestamp(String parameterName, Timestamp x)
      throws SQLException {
    setTimestamp(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAsciiStream(String parameterName, InputStream x, int length)
      throws SQLException {
    setAsciiStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBinaryStream(String parameterName, InputStream x, int length)
      throws SQLException {
    setBinaryStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setObject(String parameterName, Object x, int targetSqlType,
      int scale) throws SQLException {
    setObject(getParameterIndex(parameterName), x, targetSqlType, scale);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setObject(String parameterName, Object x, int targetSqlType)
      throws SQLException {
    setObject(getParameterIndex(parameterName), x, targetSqlType);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setObject(String parameterName, Object x) throws SQLException {
    setObject(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCharacterStream(String parameterName, Reader x, int length)
      throws SQLException {
    setCharacterStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setDate(String parameterName, Date x, Calendar cal)
      throws SQLException {
    setDate(getParameterIndex(parameterName), x, cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTime(String parameterName, Time x, Calendar cal)
      throws SQLException {
    setTime(getParameterIndex(parameterName), x, cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTimestamp(String parameterName, Timestamp x, Calendar cal)
      throws SQLException {
    setTimestamp(getParameterIndex(parameterName), x, cal);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNull(String parameterName, int sqlType, String typeName)
      throws SQLException {
    setNull(getParameterIndex(parameterName), sqlType, typeName);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRowId(String parameterName, RowId x) throws SQLException {
    setRowId(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNString(String parameterName, String x)
      throws SQLException {
    setNString(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNCharacterStream(String parameterName, Reader x, long length)
      throws SQLException {
    setNCharacterStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(String parameterName, NClob x) throws SQLException {
    setNClob(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setClob(String parameterName, Reader x, long length)
      throws SQLException {
    setClob(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBlob(String parameterName, InputStream x, long length)
      throws SQLException {
    setBlob(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(String parameterName, Reader x, long length)
      throws SQLException {
    setNClob(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSQLXML(String parameterName, SQLXML x) throws SQLException {
    setSQLXML(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBlob(String parameterName, Blob x) throws SQLException {
    setBlob(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setClob(String parameterName, Clob x) throws SQLException {
    setClob(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAsciiStream(String parameterName, InputStream x, long length)
      throws SQLException {
    setAsciiStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBinaryStream(String parameterName, InputStream x, long length)
      throws SQLException {
    setBinaryStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCharacterStream(String parameterName, Reader x, long length)
      throws SQLException {
    setCharacterStream(getParameterIndex(parameterName), x, length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAsciiStream(String parameterName, InputStream x)
      throws SQLException {
    setAsciiStream(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBinaryStream(String parameterName, InputStream x)
      throws SQLException {
    setBinaryStream(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCharacterStream(String parameterName, Reader x)
      throws SQLException {
    setCharacterStream(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNCharacterStream(String parameterName, Reader x)
      throws SQLException {
    setNCharacterStream(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setClob(String parameterName, Reader x) throws SQLException {
    setClob(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBlob(String parameterName, InputStream x) throws SQLException {
    setBlob(getParameterIndex(parameterName), x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(String parameterName, Reader x) throws SQLException {
    setNClob(getParameterIndex(parameterName), x);
  }
}
