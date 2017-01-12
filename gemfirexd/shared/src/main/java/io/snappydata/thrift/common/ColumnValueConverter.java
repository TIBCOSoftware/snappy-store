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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
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
    throw Converters.newTypeConversionException(
        getType().toString(), "boolean", columnIndex);
  }

  public byte toByte(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "byte", columnIndex);
  }

  public short toShort(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "short", columnIndex);
  }

  public int toInteger(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "int", columnIndex);
  }

  public long toLong(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "long", columnIndex);
  }

  public float toFloat(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "float", columnIndex);
  }

  public double toDouble(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "double", columnIndex);
  }

  public BigDecimal toBigDecimal(OptimizedElementArray row, int columnIndex)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "BigDecimal", columnIndex);
  }

  public abstract String toString(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException;

  public Date toDate(OptimizedElementArray row, int columnIndex, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Date", columnIndex);
  }

  public Time toTime(OptimizedElementArray row, int columnIndex, Calendar cal)
      throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Time", columnIndex);
  }

  public Timestamp toTimestamp(OptimizedElementArray row, int columnIndex,
      Calendar cal) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Timestamp", columnIndex);
  }

  public byte[] toBytes(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "byte[]", columnIndex);
  }

  public Blob toBlob(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Blob", columnIndex);
  }

  public InputStream toBinaryStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "InputStream", columnIndex);
  }

  public Clob toClob(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "Clob", columnIndex);
  }

  public Reader toCharacterStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "CharacterStream", columnIndex);
  }

  public InputStream toAsciiStream(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException {
    throw Converters.newTypeConversionException(
        getType().toString(), "AsciiStream", columnIndex);
  }

  public abstract Object toObject(OptimizedElementArray row, int columnIndex,
      LobService lobService) throws SQLException;

  public void setBoolean(OptimizedElementArray row, int columnIndex, boolean x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "boolean", columnIndex);
  }

  public void setByte(OptimizedElementArray row, int columnIndex, byte x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "byte", columnIndex);
  }

  public void setShort(OptimizedElementArray row, int columnIndex, short x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "short", columnIndex);
  }

  public void setInteger(OptimizedElementArray row, int columnIndex, int x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "int", columnIndex);
  }

  public void setLong(OptimizedElementArray row, int columnIndex, long x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "long", columnIndex);
  }

  public void setFloat(OptimizedElementArray row, int columnIndex, float x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "float", columnIndex);
  }

  public void setDouble(OptimizedElementArray row, int columnIndex, double x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "double", columnIndex);
  }

  public void setBigDecimal(OptimizedElementArray row, int columnIndex,
      BigDecimal x) throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "BigDecimal", columnIndex);
  }

  public abstract void setString(OptimizedElementArray row, int columnIndex,
      String x) throws SQLException;

  public void setDate(OptimizedElementArray row, int columnIndex, Date x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Date", columnIndex);
  }

  public void setTime(OptimizedElementArray row, int columnIndex, Time x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Time", columnIndex);
  }

  public void setTimestamp(OptimizedElementArray row, int columnIndex,
      Timestamp x) throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "Timestamp", columnIndex);
  }

  public void setBytes(OptimizedElementArray row, int columnIndex, byte[] x)
      throws SQLException {
    throw Converters.newTypeSetConversionException(
        getType().toString(), "byte[]", columnIndex);
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
      InputStream stream, long length) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      final int len = (int)length;
      int offset = 0;
      int readLen;
      byte[] bytes = new byte[len];
      try {
        while ((readLen = stream.read(bytes, offset, len - offset)) > 0) {
          offset += readLen;
          if (offset >= len) break;
        }
      } catch (IOException ioe) {
        throw ThriftExceptionUtil.newSQLException(SQLState
            .LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, ioe.getMessage());
      }
      if (offset < len) {
        bytes = Arrays.copyOf(bytes, offset);
      }
      setBytes(row, columnIndex, bytes);
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
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
      Reader reader, long length) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      final int len = (int)length;
      int offset = 0;
      int readLen;
      char[] chars = new char[len];
      try {
        while ((readLen = reader.read(chars, offset, len - offset)) > 0) {
          offset += readLen;
          if (offset >= len) break;
        }
      } catch (IOException ioe) {
        throw ThriftExceptionUtil.newSQLException(SQLState
            .LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, ioe.getMessage());
      }
      setString(row, columnIndex, new String(chars, 0, offset));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
  }

  public void setAsciiStream(OptimizedElementArray row, int columnIndex,
      InputStream stream, long length) throws SQLException {
    if (length <= Integer.MAX_VALUE) {
      final int len = (int)length;
      int offset = 0;
      int readLen;
      byte[] bytes = new byte[len];
      try {
        while ((readLen = stream.read(bytes, offset, len - offset)) > 0) {
          offset += readLen;
          if (offset >= len) break;
        }
      } catch (IOException ioe) {
        throw ThriftExceptionUtil.newSQLException(SQLState
            .LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, ioe.getMessage());
      }
      setString(row, columnIndex, new String(bytes, 0, offset,
          StandardCharsets.US_ASCII));
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, length, Integer.MAX_VALUE);
    }
  }

  public abstract void setObject(OptimizedElementArray row, int columnIndex,
      Object x) throws SQLException;

  public boolean isNull() {
    return false;
  }
}
