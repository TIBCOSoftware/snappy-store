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

  public boolean toBoolean(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "boolean", columnPosition);
  }

  public byte toByte(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "byte", columnPosition);
  }

  public short toShort(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "short", columnPosition);
  }

  public int toInteger(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "int", columnPosition);
  }

  public long toLong(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "long", columnPosition);
  }

  public float toFloat(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "float", columnPosition);
  }

  public double toDouble(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "double", columnPosition);
  }

  public BigDecimal toBigDecimal(OptimizedElementArray row, int columnPosition)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "BigDecimal", columnPosition);
  }

  public abstract String toString(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException;

  public Date toDate(OptimizedElementArray row, int columnPosition, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Date", columnPosition);
  }

  public Time toTime(OptimizedElementArray row, int columnPosition, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Time", columnPosition);
  }

  public Timestamp toTimestamp(OptimizedElementArray row, int columnPosition,
      Calendar cal) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Timestamp", columnPosition);
  }

  public byte[] toBytes(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "byte[]", columnPosition);
  }

  public Blob toBlob(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Blob", columnPosition);
  }

  public InputStream toBinaryStream(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "InputStream", columnPosition);
  }

  public Clob toClob(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Clob", columnPosition);
  }

  public Reader toCharacterStream(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "CharacterStream", columnPosition);
  }

  public InputStream toAsciiStream(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "AsciiStream", columnPosition);
  }

  public abstract Object toObject(OptimizedElementArray row, int columnPosition,
      LobService lobService) throws SQLException;

  public void setBoolean(OptimizedElementArray row, int columnPosition, boolean x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "boolean", columnPosition);
  }

  public void setByte(OptimizedElementArray row, int columnPosition, byte x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "byte", columnPosition);
  }

  public void setShort(OptimizedElementArray row, int columnPosition, short x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "short", columnPosition);
  }

  public void setInteger(OptimizedElementArray row, int columnPosition, int x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "int", columnPosition);
  }

  public void setLong(OptimizedElementArray row, int columnPosition, long x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "long", columnPosition);
  }

  public void setFloat(OptimizedElementArray row, int columnPosition, float x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "float", columnPosition);
  }

  public void setDouble(OptimizedElementArray row, int columnPosition, double x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "double", columnPosition);
  }

  public void setBigDecimal(OptimizedElementArray row, int columnPosition,
      BigDecimal x) throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "BigDecimal", columnPosition);
  }

  public abstract void setString(OptimizedElementArray row, int columnPosition,
      String x) throws SQLException;

  public void setDate(OptimizedElementArray row, int columnPosition, Date x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Date", columnPosition);
  }

  public void setTime(OptimizedElementArray row, int columnPosition, Time x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Time", columnPosition);
  }

  public void setTimestamp(OptimizedElementArray row, int columnPosition,
      Timestamp x) throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Timestamp", columnPosition);
  }

  public void setBytes(OptimizedElementArray row, int columnPosition, byte[] x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "byte[]", columnPosition);
  }

  public void setBlob(OptimizedElementArray row, int columnPosition, Blob x)
      throws SQLException {
    long len = x.length();
    if (len <= Integer.MAX_VALUE) {
      if (x instanceof BufferedBlob) {
        row.setObject(columnPosition - 1, ((BufferedBlob)x).getAsLastChunk(),
            SnappyType.BLOB);
      } else {
        setBytes(row, columnPosition, x.getBytes(1, (int)len));
      }
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, len, Integer.MAX_VALUE);
    }
  }

  public void setBinaryStream(OptimizedElementArray row, int columnPosition,
      InputStream stream, long length,
      LobService lobService) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      setBlob(row, columnPosition, lobService.createBlob(stream, length));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
  }

  public void setClob(OptimizedElementArray row, int columnPosition, Clob x)
      throws SQLException {
    long len = x.length();
    if (len <= Integer.MAX_VALUE) {
      setString(row, columnPosition, x.getSubString(1, (int)len));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, len, Integer.MAX_VALUE);
    }
  }

  public void setCharacterStream(OptimizedElementArray row, int columnPosition,
      Reader reader, long length, LobService lobService) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      setClob(row, columnPosition, lobService.createClob(reader, length));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
  }

  public void setAsciiStream(OptimizedElementArray row, int columnPosition,
      InputStream stream, long length,
      LobService lobService) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      setClob(row, columnPosition, lobService.createClob(stream, length));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
  }

  public abstract void setObject(OptimizedElementArray row, int columnPosition,
      Object x) throws SQLException;

  public boolean isNull() {
    return false;
  }
}
