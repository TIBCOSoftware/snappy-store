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

package io.snappydata.thrift.common;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.SnappyType;

/**
 * Common interface to describe different type conversions for a column in an
 * {@link OptimizedElementArray}.
 */
public abstract class ColumnValueConverter {

  public abstract SnappyType getType();

  public boolean toBoolean(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters
        .newTypeConversionException(getType().toString(), "boolean");
  }

  public byte toByte(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "byte");
  }

  public short toShort(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "short");
  }

  public int toInteger(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "int");
  }

  public long toLong(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "long");
  }

  public float toFloat(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "float");
  }

  public double toDouble(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "double");
  }

  public BigDecimal toBigDecimal(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "BigDecimal");
  }

  public abstract String toString(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException;

  public Date toDate(OptimizedElementArray row, int columnIndex, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Date");
  }

  public Time toTime(OptimizedElementArray row, int columnIndex, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Time");
  }

  public Timestamp toTimestamp(OptimizedElementArray row, int columnIndex,
      Calendar cal) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "Timestamp");
  }

  public byte[] toBytes(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "byte[]");
  }

  public Blob toBlob(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Blob");
  }

  public InputStream toBinaryStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "InputStream");
  }

  public Clob toClob(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Clob");
  }

  public Reader toCharacterStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "CharacterStream");
  }

  public InputStream toAsciiStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "AsciiStream");
  }

  public abstract Object toObject(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException;

  public void setBoolean(OptimizedElementArray row, int columnIndex, boolean x)
      throws SQLException {
    throw Converters
        .newTypeConversionException(getType().toString(), "boolean");
  }

  public void setByte(OptimizedElementArray row, int columnIndex, byte x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "byte");
  }

  public void setShort(OptimizedElementArray row, int columnIndex, short x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "short");
  }

  public void setInteger(OptimizedElementArray row, int columnIndex, int x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "int");
  }

  public void setLong(OptimizedElementArray row, int columnIndex, long x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "long");
  }

  public void setFloat(OptimizedElementArray row, int columnIndex, float x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "float");
  }

  public void setDouble(OptimizedElementArray row, int columnIndex, double x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "double");
  }

  public void setBigDecimal(OptimizedElementArray row, int columnIndex,
      BigDecimal x) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "BigDecimal");
  }

  public abstract void setString(OptimizedElementArray row, int columnIndex,
      String x) throws SQLException;

  public void setDate(OptimizedElementArray row, int columnIndex, Date x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Date");
  }

  public void setTime(OptimizedElementArray row, int columnIndex, Time x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "Time");
  }

  public void setTimestamp(OptimizedElementArray row, int columnIndex,
      Timestamp x) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "Timestamp");
  }

  public void setBytes(OptimizedElementArray row, int columnIndex, byte[] x)
      throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(), "byte[]");
  }

  public void setBlob(OptimizedElementArray row, int columnIndex, Blob x)
      throws SQLException {
    long len = x.length();
    if (len <= Integer.MAX_VALUE) {
      setBytes(row, columnIndex, x.getBytes(1, (int)len));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, len, Integer.MAX_VALUE);
    }
  }

  public void setBinaryStream(OptimizedElementArray row, int columnIndex,
      InputStream x) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "InputStream");
  }

  public void setClob(OptimizedElementArray row, int columnIndex, Clob x)
      throws SQLException {
    long len = x.length();
    if (len <= Integer.MAX_VALUE) {
      setString(row, columnIndex, x.getSubString(1, (int)len));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, len, Integer.MAX_VALUE);
    }
  }

  public void setCharacterStream(OptimizedElementArray row, int columnIndex,
      Reader x) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "CharacterStream");
  }

  public void setAsciiStream(OptimizedElementArray row, int columnIndex,
      InputStream x) throws SQLException {
    throw Converters.newTypeConversionException(getType().toString(),
        "AsciiStream");
  }

  public abstract void setObject(OptimizedElementArray row, int columnIndex,
      Object x) throws SQLException;

  public boolean isNull() {
    return false;
  }
}
