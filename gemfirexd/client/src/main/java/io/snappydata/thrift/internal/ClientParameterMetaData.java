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

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.List;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.ColumnDescriptor;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

/**
 * Implementation of JDBC {@link ParameterMetaData} for the thrift JDBC driver.
 */
public class ClientParameterMetaData implements ParameterMetaData {

  private final List<ColumnDescriptor> descriptors;

  ClientParameterMetaData(List<ColumnDescriptor> metadata) {
    this.descriptors = metadata;
  }

  /** check if 1-based index of column is valid one */
  final void checkForValidColumn(int column) throws SQLException {
    final int numColumns = this.descriptors.size();
    if (column < 1 || column > numColumns) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_INVALID_PARAM_POSITION, null, column, numColumns);
    }
  }

  final ColumnDescriptor getDescriptor(int column) throws SQLException {
    checkForValidColumn(column);
    return this.descriptors.get(column - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getParameterCount() throws SQLException {
    return this.descriptors.size();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int isNullable(int param) throws SQLException {
    ColumnDescriptor desc = getDescriptor(param);
    if (desc.isSetNullable()) {
      return desc.isNullable() ? parameterNullable : parameterNoNulls;
    } else {
      return parameterNullableUnknown;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSigned(int param) throws SQLException {
    switch (getDescriptor(param).getType()) {
      case INTEGER:
      case DECIMAL:
      case SMALLINT:
      case BIGINT:
      case TINYINT:
      case DOUBLE:
      case FLOAT:
        return true;
      default:
        return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPrecision(int param) throws SQLException {
    return getDescriptor(param).getPrecision();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScale(int param) throws SQLException {
    ColumnDescriptor desc = getDescriptor(param);
    if (desc.isSetScale()) {
      return desc.getScale();
    }
    else {
      switch (desc.getType()) {
        case BOOLEAN:
        case TINYINT:
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case DOUBLE:
        case FLOAT:
        case DATE:
        case TIME:
          return 0;
        case TIMESTAMP:
          return 6;
        default:
          return snappydataConstants.COLUMN_SCALE_UNKNOWN;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getParameterType(int param) throws SQLException {
    return Converters.getJdbcType(getDescriptor(param).getType());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getParameterTypeName(int param) throws SQLException {
    ColumnDescriptor desc = getDescriptor(param);
    String typeAndClassName = desc.getUdtTypeAndClassName();
    if (typeAndClassName != null) {
      int colonIndex = typeAndClassName.indexOf(':');
      if (colonIndex >= 0) {
        return typeAndClassName.substring(0, colonIndex);
      }
      else {
        return typeAndClassName;
      }
    }
    else {
      switch (desc.getType()) {
        case TINYINT:
          return "TINYINT";
        case SMALLINT:
          return "SMALLINT";
        case INTEGER:
          return "INTEGER";
        case BIGINT:
          return "BIGINT";
        case DOUBLE:
          return "DOUBLE";
        case FLOAT:
          return "REAL";
        case DECIMAL:
          return "DECIMAL";
        case CHAR:
          return "CHAR";
        case VARCHAR:
          return "VARCHAR";
        case LONGVARCHAR:
          return "LONG VARCHAR";
        case DATE:
          return "DATE";
        case TIME:
          return "TIME";
        case TIMESTAMP:
          return "TIMESTAMP";
        case BINARY:
          return "CHAR FOR BIT DATA";
        case VARBINARY:
          return "VARCHAR FOR BIT DATA";
        case LONGVARBINARY:
          return "LONG VARCHAR FOR BIT DATA";
        case JAVA_OBJECT:
          return "JAVA";
        case BLOB:
          return "BLOB";
        case CLOB:
          return "CLOB";
        case BOOLEAN:
          return "BOOLEAN";
        case SQLXML:
          return "XML";
        case ARRAY:
          return "ARRAY";
        case MAP:
          return "MAP";
        case STRUCT:
          return "STRUCT";
        case JSON:
          return "JSON";
        default:
          return "UNKNOWN";
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getParameterClassName(int param) throws SQLException {
    ColumnDescriptor desc = getDescriptor(param);
    String typeAndClassName = desc.getUdtTypeAndClassName();
    if (typeAndClassName != null) {
      int colonIndex = typeAndClassName.indexOf(':');
      if (colonIndex >= 0) {
        return typeAndClassName.substring(colonIndex + 1);
      }
    }
    switch (desc.getType()) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return "java.lang.Integer";
      case BIGINT:
        return "java.lang.Long";
      case FLOAT:
        return "java.lang.Float";
      case DOUBLE:
        return "java.lang.Double";
      case DECIMAL:
        return "java.math.BigDecimal";
      case CHAR:
      case VARCHAR:
      case LONGVARCHAR:
        return "java.lang.String";
      case BOOLEAN:
        return "java.lang.Boolean";
      case DATE:
        return "java.sql.Date";
      case TIME:
        return "java.sql.Time";
      case TIMESTAMP:
        return "java.sql.Timestamp";
      case BINARY:
      case VARBINARY:
      case LONGVARBINARY:
        return "byte[]";
      case JAVA_OBJECT:
        return "java.lang.Object";
      case BLOB:
        return "java.sql.Blob";
      case CLOB:
      case JSON:
        return "java.sql.Clob";
      case SQLXML:
        return "java.sql.SQLXML";
      case ARRAY:
        return "java.sql.Array";
      case STRUCT:
        return "java.sql.Struct";
      case MAP:
        return "java.util.Map";
      default:
        return "java.lang.Object";
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getParameterMode(int param) throws SQLException {
    ColumnDescriptor desc = getDescriptor(param);
    if (desc.isParameterIn()) {
      return desc.isParameterOut() ? parameterModeInOut : parameterModeIn;
    } else if (desc.isParameterOut()) {
      return parameterModeOut;
    } else {
      // default is IN
      return parameterModeIn;
    }
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
}
