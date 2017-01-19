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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.ClobChunk;
import io.snappydata.thrift.Decimal;
import io.snappydata.thrift.SnappyType;
import io.snappydata.thrift.snappydataConstants;

/**
 * Conversion utilities from thrift API values/enums to JDBC/SnappyData equivalent
 * values.
 */
public abstract class Converters {

  private Converters() {
    // no instance allowed
  }

  static final BigDecimal MAXLONG_PLUS_ONE = BigDecimal.valueOf(Long.MAX_VALUE)
      .add(BigDecimal.ONE);
  static final BigDecimal MINLONG_MINUS_ONE = BigDecimal
      .valueOf(Long.MIN_VALUE).subtract(BigDecimal.ONE);

  public static final ColumnValueConverter BOOLEAN_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.BOOLEAN;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getBoolean(columnIndex - 1);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return row.getBoolean(columnIndex - 1) ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getBoolean(columnIndex - 1) ? "true" : "false";
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getBoolean(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setBoolean(columnIndex - 1, x);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0.0f);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      row.setBoolean(columnIndex - 1, x != 0.0);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      row.setBoolean(columnIndex - 1, !BigDecimal.ZERO.equals(x));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      row.setBoolean(columnIndex - 1,
          x != null && !(x.equals("0") || x.equalsIgnoreCase("false")));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "boolean", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter BYTE_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.TINYINT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getByte(columnIndex - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getByte(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Byte.toString(row.getByte(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getByte(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setByte(columnIndex - 1, x ? (byte)1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setByte(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnIndex - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnIndex - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnIndex - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnIndex - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnIndex - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "byte", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter SHORT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.SMALLINT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getShort(columnIndex - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      short v = row.getShort(columnIndex - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getShort(columnIndex - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getShort(columnIndex - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getShort(columnIndex - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getShort(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getShort(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getShort(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Short.toString(row.getShort(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return (int)row.getShort(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setShort(columnIndex - 1, x ? (short)1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setShort(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setShort(columnIndex - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnIndex - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnIndex - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnIndex - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnIndex - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "short", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter INT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.INTEGER;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getInt(columnIndex - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      int v = row.getInt(columnIndex - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      int v = row.getInt(columnIndex - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getInt(columnIndex - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getInt(columnIndex - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getInt(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getInt(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getInt(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Integer.toString(row.getInt(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getInt(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setInt(columnIndex - 1, x ? 1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setInt(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setInt(columnIndex - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      row.setInt(columnIndex - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnIndex - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnIndex - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnIndex - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "int", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter LONG_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.BIGINT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getLong(columnIndex - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = row.getLong(columnIndex - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = row.getLong(columnIndex - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = row.getLong(columnIndex - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getLong(columnIndex - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getLong(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getLong(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getLong(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Long.toString(row.getLong(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getLong(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setLong(columnIndex - 1, x ? 1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setLong(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setLong(columnIndex - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      row.setLong(columnIndex - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      row.setLong(columnIndex - 1, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      if (x >= Long.MIN_VALUE && x <= Long.MAX_VALUE) {
        row.setLong(columnIndex - 1, (long)x);
      }
      else {
        throw newOutOfRangeException("long", columnIndex);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      if (x >= Long.MIN_VALUE && x <= Long.MAX_VALUE) {
        row.setLong(columnIndex - 1, (long)x);
      }
      else {
        throw newOutOfRangeException("long", columnIndex);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setLong(row, columnIndex, getLong(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "long", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter REAL_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.REAL;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getFloat(columnIndex - 1) != 0.0f);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      float v = row.getFloat(columnIndex - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      float v = row.getFloat(columnIndex - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      float v = row.getFloat(columnIndex - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      float v = row.getFloat(columnIndex - 1);
      if (v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
        return (long)v;
      }
      else {
        throw newOutOfRangeException("long", columnIndex);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getFloat(columnIndex - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getFloat(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getFloat(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Float.toString(row.getFloat(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getFloat(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setFloat(columnIndex - 1, x ? 1.0f : 0.0f);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setFloat(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setFloat(columnIndex - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      row.setFloat(columnIndex - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      if (x >= Float.MIN_VALUE) {
        row.setFloat(columnIndex - 1, x);
      }
      else {
        throw newOutOfRangeException("float", columnIndex);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      row.setFloat(columnIndex - 1, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      if (x >= Float.MIN_VALUE && x <= Float.MAX_VALUE) {
        row.setFloat(columnIndex - 1, (float)x);
      }
      else {
        throw newOutOfRangeException("float", columnIndex);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setDouble(row, columnIndex, getDouble(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setDouble(row, columnIndex, getDouble(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "float", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter DOUBLE_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.DOUBLE;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (row.getDouble(columnIndex - 1) != 0.0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = row.getDouble(columnIndex - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = row.getDouble(columnIndex - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = row.getDouble(columnIndex - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = row.getDouble(columnIndex - 1);
      if (v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
        return (long)v;
      }
      else {
        throw newOutOfRangeException("long", columnIndex);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = row.getDouble(columnIndex - 1);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnIndex);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return row.getDouble(columnIndex - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return new BigDecimal(row.getDouble(columnIndex - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return Double.toString(row.getDouble(columnIndex - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getDouble(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      row.setDouble(columnIndex - 1, x ? 1.0 : 0.0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      row.setDouble(columnIndex - 1, x);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setDouble(row, columnIndex, getDouble(x, columnIndex));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      setDouble(row, columnIndex, getDouble(x, columnIndex));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, (Integer)o, columnIndex);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "double", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter DECIMAL_TYPE =
      new ColumnValueConverter() {

    @Override
    public final SnappyType getType() {
      return SnappyType.DECIMAL;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      BigDecimal bd = (BigDecimal)row.getObject(columnIndex - 1);
      return bd != null && !bd.equals(BigDecimal.ZERO);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      BigDecimal decimal = (BigDecimal)row.getObject(columnIndex - 1);
      return getLong(decimal, columnIndex);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = getDouble((BigDecimal)row.getObject(columnIndex - 1),
          columnIndex);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnIndex);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return getDouble((BigDecimal)row.getObject(columnIndex - 1), columnIndex);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return (BigDecimal)row.getObject(columnIndex - 1);
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      BigDecimal bd = (BigDecimal)row.getObject(columnIndex - 1);
      if (bd != null) {
        return bd.toPlainString();
      }
      else {
        return null;
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      setBigDecimal(row, columnIndex, x ? BigDecimal.ONE : BigDecimal.ZERO);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      setBigDecimal(row, columnIndex, new BigDecimal(x));
    }
    @Override
    public final void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.DECIMAL);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      row.setObject(columnIndex - 1, getBigDecimal(x, columnIndex),
          SnappyType.DECIMAL);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if ((c = o.getClass()) == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "BigDecimal", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter DATE_TYPE =
      new ColumnValueConverter() {

    @Override
    public final SnappyType getType() {
      return SnappyType.DATE;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      java.sql.Date date = row.getDate(columnIndex - 1);
      return date.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      java.sql.Date date = row.getDate(columnIndex - 1);
      if (cal == null) {
        return date;
      }
      else {
        cal.setTime(date);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      long date = row.getLong(columnIndex - 1);
      if (cal == null) {
        return new java.sql.Timestamp(date * 1000L);
      }
      else {
        cal.setTimeInMillis(date * 1000L);
        return new java.sql.Timestamp(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      long date = row.getLong(columnIndex - 1);
      if (cal == null) {
        return Converters.getTime(date);
      }
      else {
        cal.setTimeInMillis(date * 1000L);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getDate(columnIndex - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnIndex,
        java.sql.Date x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnIndex,
        java.sql.Timestamp x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnIndex,
        java.sql.Time x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof java.util.Date) {
        row.setDateTime(columnIndex - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Date", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter TIME_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.TIME;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      java.sql.Time time = row.getTime(columnIndex - 1);
      return time.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      long time = row.getLong(columnIndex - 1);
      if (cal == null) {
        return Converters.getDate(time);
      }
      else {
        cal.setTimeInMillis(time * 1000L);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      long time = row.getLong(columnIndex - 1);
      if (cal == null) {
        return new java.sql.Timestamp(time * 1000L);
      }
      else {
        cal.setTimeInMillis(time * 1000L);
        return new java.sql.Timestamp(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      java.sql.Time time = row.getTime(columnIndex - 1);
      if (cal == null) {
        return time;
      }
      else {
        cal.setTime(time);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getTime(columnIndex - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnIndex,
        java.sql.Date x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnIndex,
        java.sql.Timestamp x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnIndex,
        java.sql.Time x) throws SQLException {
      row.setDateTime(columnIndex - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof java.util.Date) {
        row.setDateTime(columnIndex - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Time", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter TIMESTAMP_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.TIMESTAMP;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      java.sql.Timestamp ts = row.getTimestamp(columnIndex - 1);
      return ts.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      long ts = row.getLong(columnIndex - 1);
      if (cal == null) {
        return new java.sql.Date(ts / 1000000L);
      }
      else {
        cal.setTimeInMillis(ts / 1000000L);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      java.sql.Timestamp ts = row.getTimestamp(columnIndex - 1);
      if (cal == null) {
        return ts;
      }
      else {
        cal.setTime(ts);
        return new java.sql.Timestamp(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      long ts = row.getLong(columnIndex - 1);
      if (cal == null) {
        return new java.sql.Time(ts / 1000000L);
      }
      else {
        cal.setTimeInMillis(ts / 1000000L);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getTimestamp(columnIndex - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnIndex,
        java.sql.Date x) throws SQLException {
      row.setTimestamp(columnIndex - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnIndex,
        java.sql.Timestamp x) throws SQLException {
      row.setTimestamp(columnIndex - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnIndex,
        java.sql.Time x) throws SQLException {
      row.setTimestamp(columnIndex - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof java.sql.Timestamp) {
        row.setTimestamp(columnIndex - 1, (java.sql.Timestamp)o);
      } else if (o instanceof java.util.Date) {
        row.setTimestamp(columnIndex - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Timestamp", columnIndex);
      }
    }
  };

  public static class StringConverter extends ColumnValueConverter {

    @Override
    public SnappyType getType() {
      return SnappyType.VARCHAR;
    }
    @Override
    public final boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      String str = toString(row, columnIndex);
      if (str != null) {
        str = str.trim();
        return !(str.equals("0") || str.equalsIgnoreCase("false"));
      }
      else {
        return false;
      }
    }
    @Override
    public final byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnIndex);
      }
    }
    @Override
    public final short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnIndex);
      }
    }
    @Override
    public final int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      long v = toLong(row, columnIndex);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnIndex);
      }
    }
    @Override
    public final long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return getLong(toString(row, columnIndex), columnIndex);
    }
    @Override
    public final float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      double v = toDouble(row, columnIndex);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnIndex);
      }
    }
    @Override
    public final double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return getDouble(toString(row, columnIndex), columnIndex);
    }
    @Override
    public final BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      String str = toString(row, columnIndex);
      try {
        // BigDecimal constructor calls java.lang.Long.parseLong(),
        // which doesn't like spaces.
        return new BigDecimal(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("BigDecimal", columnIndex, nfe);
      }
    }
    private String toString(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return (String)row.getObject(columnIndex - 1);
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return toString(row, columnIndex);
    }
    @Override
    public final java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      String str = toString(row, columnIndex);
      if (str != null) {
        java.sql.Date date;
        try {
          date = java.sql.Date.valueOf(str);
        } catch (IllegalArgumentException iae) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.LANG_DATE_SYNTAX_EXCEPTION, iae, str);
        }
        if (cal == null) {
          return date;
        }
        else {
          cal.setTime(date);
          return new java.sql.Date(cal.getTimeInMillis());
        }
      }
      else {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_DATE_SYNTAX_EXCEPTION, null, "<NULL>");
      }
    }
    @Override
    public final java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      String str = toString(row, columnIndex);
      if (str != null) {
        java.sql.Timestamp ts;
        try {
          ts = java.sql.Timestamp.valueOf(str);
        } catch (IllegalArgumentException iae) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.LANG_DATE_SYNTAX_EXCEPTION, iae, str);
        }
        if (cal == null) {
          return ts;
        }
        else {
          cal.setTime(ts);
          return new java.sql.Timestamp(cal.getTimeInMillis());
        }
      }
      else {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_DATE_SYNTAX_EXCEPTION, null, "<NULL>");
      }
    }
    @Override
    public final java.sql.Time toTime(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      String str = toString(row, columnIndex);
      if (str != null) {
        java.sql.Time time;
        try {
          time = java.sql.Time.valueOf(str);
        } catch (IllegalArgumentException iae) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.LANG_DATE_SYNTAX_EXCEPTION, iae, str);
        }
        if (cal == null) {
          return time;
        }
        else {
          cal.setTime(time);
          return new java.sql.Time(cal.getTimeInMillis());
        }
      }
      else {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_DATE_SYNTAX_EXCEPTION, null, "<NULL>");
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      setString(row, columnIndex, x ? "true" : "false");
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      setString(row, columnIndex, Byte.toString(x));
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      setString(row, columnIndex, Short.toString(x));
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      setString(row, columnIndex, Integer.toString(x));
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      setString(row, columnIndex, Long.toString(x));
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      setString(row, columnIndex, Float.toString(x));
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      setString(row, columnIndex, Double.toString(x));
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setString(row, columnIndex, x.toPlainString());
    }
    @Override
    public final void setString(OptimizedElementArray row, int columnIndex,
        String x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnIndex,
        java.sql.Date x) throws SQLException {
      setString(row, columnIndex, x.toString());
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnIndex,
        java.sql.Timestamp x) throws SQLException {
      setString(row, columnIndex, x.toString());
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnIndex,
        java.sql.Time x) throws SQLException {
      setString(row, columnIndex, x.toString());
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      final Class<?> c;
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if ((c = o.getClass()) == String.class) {
        setString(row, columnIndex, (String)o);
      } else if (c == Double.class) {
        setDouble(row, columnIndex, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnIndex, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, columnIndex, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnIndex, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnIndex, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnIndex, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnIndex, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnIndex, (BigDecimal)o);
      } else if (o instanceof java.util.Date) {
        setString(row, columnIndex, o.toString());
      } else {
        throw newTypeSetConversionException(
            c.getName(), getType().toString(), columnIndex);
      }
    }
  }
  public static final ColumnValueConverter STRING_TYPE = new StringConverter();

  public static final ColumnValueConverter CLOB_TYPE = new StringConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.CLOB;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return getClobAsString(row.getClobChunk(columnIndex - 1, true),
          lobService);
    }
    @Override
    public final Clob toClob(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          false);
    }
    @Override
    public Reader toCharacterStream(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          true).getCharacterStream();
    }
    @Override
    public InputStream toAsciiStream(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          true).getAsciiStream();
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return toClob(row, columnIndex, lobService);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o instanceof Clob) {
        // not chunking in sends yet
        Clob clob = (Clob)o;
        long clobLen = clob.length();
        if (clobLen > Integer.MAX_VALUE) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_LENGTH_TOO_LONG, null, clobLen);
        }
        setString(row, columnIndex, clob.getSubString(1, (int)clobLen));
      } else {
        super.setObject(row, columnIndex, o);
      }
    }
  };

  public static final ColumnValueConverter BINARY_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.VARBINARY;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      byte[] bytes = (byte[])row.getObject(columnIndex - 1);
      if (bytes != null) {
        return ClientSharedUtils.toHexString(bytes, 0, bytes.length);
      }
      else {
        return null;
      }
    }
    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return (byte[])row.getObject(columnIndex - 1);
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }
    @Override
    public final void setBytes(OptimizedElementArray row, int columnIndex,
        byte[] x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARBINARY);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      try {
        byte[] bytes = ClientSharedUtils.fromHexString(x, 0, x.length());
        setBytes(row, columnIndex, bytes);
      } catch (IllegalArgumentException iae) {
        throw newTypeSetConversionException(
            "String", getType().toString(), Integer.toString(columnIndex), iae);
      }
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof byte[]) {
        setBytes(row, columnIndex, (byte[])o);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "byte[]", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter BLOB_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.BLOB;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      byte[] bytes = toBytes(row, columnIndex, lobService);
      if (bytes != null) {
        return ClientSharedUtils.toHexString(bytes, 0, bytes.length);
      }
      else {
        return null;
      }
    }
    @Override
    public final byte[] toBytes(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return getBlobAsBytes(row.getBlobChunk(columnIndex - 1, true),
          lobService);
    }
    @Override
    public final Blob toBlob(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createBlob(row.getBlobChunk(columnIndex - 1, true),
          false);
    }
    @Override
    public InputStream toBinaryStream(OptimizedElementArray row,
        int columnIndex, LobService lobService) throws SQLException {
      return lobService.createBlob(row.getBlobChunk(columnIndex - 1, true),
          true).getBinaryStream();
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return toBlob(row, columnIndex, lobService);
    }
    @Override
    public final void setBytes(OptimizedElementArray row, int columnIndex,
        byte[] x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARBINARY);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      try {
        byte[] bytes = ClientSharedUtils.fromHexString(x, 0, x.length());
        setBytes(row, columnIndex, bytes);
      } catch (IllegalArgumentException iae) {
        throw newTypeSetConversionException(
            "String", getType().toString(), Integer.toString(columnIndex), iae);
      }
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof byte[]) {
        setBytes(row, columnIndex, (byte[])o);
      } else if (o instanceof Blob) {
        // not chunking in sends yet
        Blob blob = (Blob)o;
        long blobLen = blob.length();
        if (blobLen > Integer.MAX_VALUE) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_LENGTH_TOO_LONG, null, blobLen);
        }
        setBytes(row, columnIndex, blob.getBytes(1, (int)blobLen));
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Blob", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter OBJECT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.JAVA_OBJECT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Boolean) {
        return (Boolean)o;
      }
      else if (o instanceof Byte) {
        return (Byte)o != 0;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "boolean", columnIndex);
      }
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).byteValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "byte", columnIndex);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).shortValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "short", columnIndex);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).intValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "int", columnIndex);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).longValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "long", columnIndex);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).floatValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "float", columnIndex);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof Number) {
        return ((Number)o).doubleValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "double", columnIndex);
      }
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof BigDecimal) {
        return (BigDecimal)o;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "BigDecimal", columnIndex);
      }
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o != null) {
        return o.toString();
      }
      else {
        return null;
      }
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof java.sql.Date) {
        return (java.sql.Date)o;
      } else if (o instanceof Number) {
        return Converters.getDate(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Date", columnIndex);
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof java.sql.Timestamp) {
        return (java.sql.Timestamp)o;
      } else if (o instanceof Number) {
        return Converters.getTimestamp(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Timestamp", columnIndex);
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof java.sql.Time) {
        return (java.sql.Time)o;
      } else if (o instanceof Number) {
        return Converters.getTime(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Time", columnIndex);
      }
    }
    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o instanceof byte[]) {
        return (byte[])o;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "byte[]", columnIndex);
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnIndex,
        boolean x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnIndex, byte x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnIndex, short x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnIndex, int x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnIndex, long x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnIndex, float x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnIndex, double x)
        throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnIndex, BigDecimal x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public final void setString(OptimizedElementArray row, int columnIndex,
        String x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnIndex,
        java.sql.Date x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnIndex,
        java.sql.Timestamp x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnIndex,
        java.sql.Time x) throws SQLException {
      setObject(row, columnIndex, x);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      row.setObject(columnIndex - 1, o, SnappyType.JAVA_OBJECT);
    }
  };

  public static final ColumnValueConverter ARRAY_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.ARRAY;
    }

    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnIndex,
        String x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof List<?>) {
        row.setObject(columnIndex - 1, o, SnappyType.ARRAY);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "ARRAY", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter MAP_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.MAP;
    }

    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnIndex,
        String x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof Map<?, ?>) {
        row.setObject(columnIndex - 1, o, SnappyType.MAP);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "MAP", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter STRUCT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.STRUCT;
    }

    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnIndex - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return row.getObject(columnIndex - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnIndex,
        String x) throws SQLException {
      row.setObject(columnIndex - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnIndex - 1);
      } else if (o instanceof List<?>) {
        row.setObject(columnIndex - 1, o, SnappyType.STRUCT);
      } else if (o instanceof String) {
        setString(row, columnIndex, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "STRUCT", columnIndex);
      }
    }
  };

  public static final ColumnValueConverter JSON_TYPE = new StringConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.JSON;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return getClobAsString(row.getClobChunk(columnIndex - 1, true),
          lobService);
    }
    @Override
    public final Clob toClob(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          false);
    }
    @Override
    public Reader toCharacterStream(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          true).getCharacterStream();
    }
    @Override
    public InputStream toAsciiStream(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return lobService.createClob(row.getClobChunk(columnIndex - 1, true),
          true).getAsciiStream();
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return toClob(row, columnIndex, lobService);
    }
  };

  public static final ColumnValueConverter NULL_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.NULLTYPE;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return false;
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0;
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0;
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0;
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0;
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0.0f;
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnIndex)
        throws SQLException {
      return 0.0;
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnIndex) throws SQLException {
      return BigDecimal.ZERO;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return null;
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      return null;
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnIndex, Calendar cal) throws SQLException {
      return null;
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnIndex,
        Calendar cal) throws SQLException {
      return null;
    }
    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return null;
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return null;
    }
    @Override
    public void setString(OptimizedElementArray row, int columnIndex, String x)
        throws SQLException {
      throw new AssertionError("unexpected invocation");
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnIndex, Object x)
        throws SQLException {
      throw new AssertionError("unexpected invocation");
    }
    @Override
    public boolean isNull() {
      return true;
    }
  };

  static final ColumnValueConverter[] typeConverters;
  static final int[] jdbcTypes;

  static {
    final SnappyType[] universe = SnappyType.values();
    typeConverters = new ColumnValueConverter[universe.length + 2];
    jdbcTypes = new int[universe.length + 2];
    for (SnappyType type : universe) {
      final int typeValue = type.getValue();
      switch (type) {
        case CHAR:
          jdbcTypes[typeValue] = Types.CHAR;
          typeConverters[typeValue] = STRING_TYPE;
          break;
        case VARCHAR:
          jdbcTypes[typeValue] = Types.VARCHAR;
          typeConverters[typeValue] = STRING_TYPE;
          break;
        case LONGVARCHAR:
          jdbcTypes[typeValue] = Types.LONGVARCHAR;
          typeConverters[typeValue] = STRING_TYPE;
          break;
        case INTEGER:
          jdbcTypes[typeValue] = Types.INTEGER;
          typeConverters[typeValue] = INT_TYPE;
          break;
        case BOOLEAN:
          jdbcTypes[typeValue] = Types.BOOLEAN;
          typeConverters[typeValue] = BOOLEAN_TYPE;
          break;
        case TINYINT:
          jdbcTypes[typeValue] = Types.TINYINT;
          typeConverters[typeValue] = BYTE_TYPE;
          break;
        case SMALLINT:
          jdbcTypes[typeValue] = Types.SMALLINT;
          typeConverters[typeValue] = SHORT_TYPE;
          break;
        case BIGINT:
          jdbcTypes[typeValue] = Types.BIGINT;
          typeConverters[typeValue] = LONG_TYPE;
          break;
        case REAL:
          jdbcTypes[typeValue] = Types.REAL;
          typeConverters[typeValue] = REAL_TYPE;
          break;
        case DOUBLE:
          jdbcTypes[typeValue] = Types.DOUBLE;
          typeConverters[typeValue] = DOUBLE_TYPE;
          break;
        case FLOAT:
          jdbcTypes[typeValue] = Types.FLOAT;
          typeConverters[typeValue] = DOUBLE_TYPE;
          break;
        case DECIMAL:
          jdbcTypes[typeValue] = Types.DECIMAL;
          typeConverters[typeValue] = DECIMAL_TYPE;
          break;
        case DATE:
          jdbcTypes[typeValue] = Types.DATE;
          typeConverters[typeValue] = DATE_TYPE;
          break;
        case TIME:
          jdbcTypes[typeValue] = Types.TIME;
          typeConverters[typeValue] = TIME_TYPE;
          break;
        case TIMESTAMP:
          jdbcTypes[typeValue] = Types.TIMESTAMP;
          typeConverters[typeValue] = TIMESTAMP_TYPE;
          break;
        case BINARY:
          jdbcTypes[typeValue] = Types.BINARY;
          typeConverters[typeValue] = BINARY_TYPE;
          break;
        case VARBINARY:
          jdbcTypes[typeValue] = Types.VARBINARY;
          typeConverters[typeValue] = BINARY_TYPE;
          break;
        case LONGVARBINARY:
          jdbcTypes[typeValue] = Types.LONGVARBINARY;
          typeConverters[typeValue] = BINARY_TYPE;
          break;
        case CLOB:
          jdbcTypes[typeValue] = Types.CLOB;
          typeConverters[typeValue] = CLOB_TYPE;
          break;
        case BLOB:
          jdbcTypes[typeValue] = Types.BLOB;
          typeConverters[typeValue] = BLOB_TYPE;
          break;
        case ARRAY:
          jdbcTypes[typeValue] = Types.ARRAY;
          typeConverters[typeValue] = ARRAY_TYPE;
          break;
        case MAP:
          jdbcTypes[typeValue] = JDBC40Translation.MAP;
          typeConverters[typeValue] = MAP_TYPE;
        case STRUCT:
          jdbcTypes[typeValue] = Types.STRUCT;
          typeConverters[typeValue] = STRUCT_TYPE;
        case JSON:
          jdbcTypes[typeValue] = JDBC40Translation.JSON;
          typeConverters[typeValue] = JSON_TYPE;
          break;
        case NULLTYPE:
          jdbcTypes[typeValue] = Types.NULL;
          typeConverters[typeValue] = NULL_TYPE;
          break;
        case JAVA_OBJECT:
          jdbcTypes[typeValue] = Types.JAVA_OBJECT;
          typeConverters[typeValue] = OBJECT_TYPE;
          break;
        case OTHER:
          jdbcTypes[typeValue] = Types.OTHER;
          typeConverters[typeValue] = OBJECT_TYPE;
          break;
        default:
          jdbcTypes[typeValue] = Types.OTHER;
          // no conversion support for other types
          break;
      }
    }
  }

  public static SQLException newTypeConversionException(
      String sourceType, String targetType, String column, Throwable cause) {
    return ThriftExceptionUtil.newSQLException(
        SQLState.LANG_DATA_TYPE_GET_MISMATCH, cause, targetType,
        sourceType, column);
  }

  public static SQLException newTypeConversionException(
      String sourceType, String targetType, int columnIndex) {
    return newTypeConversionException(sourceType, targetType,
        Integer.toString(columnIndex), null);
  }

  public static SQLException newTypeSetConversionException(
      String sourceType, String targetType, String column, Throwable cause) {
    return ThriftExceptionUtil.newSQLException(
        SQLState.LANG_DATA_TYPE_SET_MISMATCH, cause, targetType,
        sourceType, column);
  }

  public static SQLException newTypeSetConversionException(
      String sourceType, String targetType, int columnIndex) {
    return newTypeSetConversionException(sourceType, targetType,
        Integer.toString(columnIndex), null);
  }

  public static SQLException newOutOfRangeException(
      String type, int column) {
    return newOutOfRangeException(type, column, null);
  }

  public static SQLException newOutOfRangeException(
      String type, int column, Throwable cause) {
    return ThriftExceptionUtil.newSQLException(
        SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, cause, type, column);
  }

  public static SQLException newFormatException(String type, int column,
      Throwable cause) {
    return ThriftExceptionUtil.newSQLException(SQLState.LANG_FORMAT_EXCEPTION,
        cause, type, column);
  }

  public static ColumnValueConverter getConverter(int snappyTypeValue,
      String targetType, boolean forSet, int columnIndex) throws SQLException {
    ColumnValueConverter converter = snappyTypeValue > 0
        ? typeConverters[snappyTypeValue] : NULL_TYPE;
    if (converter != null) {
      return converter;
    } else {
      SnappyType type = SnappyType.findByValue(snappyTypeValue);
      String typeString = type != null ? type.toString()
          : Integer.toString(snappyTypeValue);
      if (forSet) {
        throw newTypeSetConversionException(
            typeString, targetType, Integer.toString(columnIndex), null);
      } else {
        throw newTypeConversionException(
            typeString, targetType, Integer.toString(columnIndex), null);
      }
    }
  }

  /**
   * Get the {@link BigDecimal} for given non-null {@link Decimal} value.
   *
   * @param decimal
   *          the {@link Decimal} to convert to {@link BigDecimal}
   *
   * @throws NullPointerException
   *           if decimal argument is null
   */
  public static BigDecimal getBigDecimal(Decimal decimal) {
    return new BigDecimal(
        new BigInteger(decimal.signum, decimal.getMagnitude()), decimal.scale);
  }

  public static BigDecimal getBigDecimal(String str, int columnIndex)
      throws SQLException {
    if (str != null) {
      try {
        return new BigDecimal(str);
      } catch (NumberFormatException nfe) {
        throw newFormatException("decimal", columnIndex, nfe);
      }
    } else {
      return null;
    }
  }

  /**
   * Get the {@link Decimal} for given non-null {@link BigDecimal} value.
   *
   * @param decimal
   *          the {@link BigDecimal} to convert to {@link Decimal}
   *
   * @throws NullPointerException
   *           if decimal argument is null
   */
  public static Decimal getDecimal(BigDecimal decimal) {
    decimal = adjustScale(decimal);
    BigInteger bi = decimal.unscaledValue();
    return new Decimal((byte)bi.signum(), decimal.scale(), ByteBuffer.wrap(bi
        .abs().toByteArray()));
  }

  public static java.sql.Date getDate(long date) {
    return new java.sql.Date(date * 1000L);
  }

  public static java.sql.Time getTime(long time) {
    return new java.sql.Time(time * 1000L);
  }

  public static long getDateTime(java.util.Date date) {
    return date.getTime() / 1000L;
  }

  public static BigDecimal adjustScale(final BigDecimal decimal) {
    if (decimal.scale() >= 0) {
      return decimal;
    }
    else {
      return decimal.setScale(0, BigDecimal.ROUND_UNNECESSARY);
    }
  }

  public static long getLong(String str, int columnIndex) throws SQLException {
    if (str != null) {
      try {
        return Long.parseLong(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("long", columnIndex, nfe);
      }
    }
    else {
      return 0;
    }
  }

  public static long getLong(BigDecimal decimal, int columnIndex)
      throws SQLException {
    if (decimal != null) {
      if ((decimal.compareTo(MINLONG_MINUS_ONE) == 1)
          && (decimal.compareTo(MAXLONG_PLUS_ONE) == -1)) {
        return decimal.longValue();
      }
      else {
        throw newOutOfRangeException("long", columnIndex);
      }
    }
    else {
      return 0;
    }
  }

  public static double getDouble(String str, int columnIndex)
      throws SQLException {
    if (str != null) {
      try {
        return Double.parseDouble(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("double", columnIndex, nfe);
      }
    }
    else {
      return 0.0;
    }
  }

  public static double getDouble(BigDecimal decimal, int columnIndex)
      throws SQLException {
    if (decimal != null) {
      double v = decimal.doubleValue();
      if (!Double.isNaN(v) && !Double.isInfinite(v)) {
        return v;
      }
      else {
        throw newOutOfRangeException("double", columnIndex);
      }
    }
    else {
      return 0.0;
    }
  }

  public static java.sql.Timestamp getTimestamp(long ts) {
    // pass only the seconds for epoch time
    java.sql.Timestamp jts = new java.sql.Timestamp((ts / 1000000000L) * 1000L);
    jts.setNanos((int)(ts % 1000000000L));
    return jts;
  }

  public static long getTimestamp(java.sql.Timestamp jts) {
    return jts.getTime() * 1000000L + jts.getNanos();
  }

  public static long getTimestamp(java.util.Date date) {
    return date.getTime() * 1000000L;
  }

  public static String getClobAsString(ClobChunk clob, LobService lobService)
      throws SQLException {
    if (clob.last) {
      return clob.chunk;
    }

    final long totalLength = clob.getTotalLength();
    if (totalLength <= 0) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_NONPOSITIVE_LENGTH, null, totalLength);
    }
    if (totalLength > Integer.MAX_VALUE) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, Long.toString(totalLength),
          Long.toString(Integer.MAX_VALUE));
    }
    if (!clob.isSetLobId()) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LOB_LOCATOR_INVALID, new RuntimeException("missing LOB id"));
    }

    final long lobId = clob.lobId;
    final StringBuilder sb = new StringBuilder((int)totalLength);
    String chunk = clob.chunk;
    sb.append(chunk);
    int offset = 0;
    while (!clob.last) {
      int chunkSize = chunk.length();
      offset += chunkSize;
      clob = lobService.getClobChunk(lobId, offset, chunkSize, true);
      chunk = clob.chunk;
      sb.append(chunk);
    }
    return sb.toString();
  }

  public static byte[] getBlobAsBytes(BlobChunk blob, LobService lobService)
      throws SQLException {
    if (blob.last) {
      return blob.getChunk();
    }

    final long totalLength = blob.getTotalLength();
    if (totalLength <= 0) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_NONPOSITIVE_LENGTH, null, totalLength);
    }
    if (totalLength > Integer.MAX_VALUE) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_TOO_LARGE_FOR_CLIENT, null, Long.toString(totalLength),
          Long.toString(Integer.MAX_VALUE));
    }
    if (!blob.isSetLobId()) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LOB_LOCATOR_INVALID, new RuntimeException("missing LOB id"));
    }

    final long lobId = blob.lobId;
    final byte[] fullBytes = new byte[(int)totalLength];
    byte[] chunk = blob.getChunk();
    int chunkSize = chunk.length;
    System.arraycopy(chunk, 0, fullBytes, 0, chunkSize);
    int offset = 0;
    while (!blob.last) {
      offset += chunkSize;
      blob = lobService.getBlobChunk(lobId, offset, chunkSize, true);
      chunk = blob.getChunk();
      chunkSize = chunk.length;
      System.arraycopy(chunk, 0, fullBytes, offset, chunkSize);
    }
    return fullBytes;
  }

  public static Object getJavaObject(byte[] bytes, int columnIndex)
      throws SQLException {
    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
    Object obj;
    try {
      obj = new ObjectInputStream(in).readObject();
    } catch (Exception e) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, e, columnIndex);
    }
    return obj;
  }

  public static byte[] getJavaObjectAsBytes(Object o) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ObjectOutputStream os = new ObjectOutputStream(bos);
    os.writeObject(o);
    os.flush();
    return bos.toByteArray();
  }

  /**
   * Get JDBC {@link Types} type for given {@link SnappyType}.
   */
  public static int getJdbcType(SnappyType type) {
    return jdbcTypes[type.getValue()];
  }

  /**
   * Get {@link SnappyType} for given JDBC {@link Types}.
   */
  public static SnappyType getThriftSQLType(int jdbcType) {
    return getThriftSQLType(jdbcType, false);
  }

  /**
   * Get {@link SnappyType} for given JDBC {@link Types}.
   */
  public static SnappyType getThriftSQLType(int jdbcType,
      boolean useStringForDecimal) {
    switch (jdbcType) {
      case Types.VARCHAR:
        return SnappyType.VARCHAR;
      case Types.INTEGER:
        return SnappyType.INTEGER;
      case Types.BIGINT:
        return SnappyType.BIGINT;
      case Types.DOUBLE:
        return SnappyType.DOUBLE;
      case Types.DECIMAL:
      case Types.NUMERIC:
        return useStringForDecimal ? SnappyType.VARCHAR : SnappyType.DECIMAL;
      case Types.CHAR:
        return SnappyType.CHAR;
      case Types.DATE:
        return SnappyType.DATE;
      case Types.TIMESTAMP:
        return SnappyType.TIMESTAMP;
      case Types.SMALLINT:
        return SnappyType.SMALLINT;
      case Types.BOOLEAN:
      case Types.BIT:
        return SnappyType.BOOLEAN;
      case Types.REAL:
        return SnappyType.REAL;
      case Types.LONGVARCHAR:
        return SnappyType.LONGVARCHAR;
      case Types.BLOB:
        return SnappyType.BLOB;
      case Types.CLOB:
        return SnappyType.CLOB;
      case Types.FLOAT:
        // Derby FLOAT can be DOUBLE or REAL depending on precision
        return SnappyType.FLOAT;
      case Types.ARRAY:
        return SnappyType.ARRAY;
      case Types.BINARY:
        return SnappyType.BINARY;
      case Types.JAVA_OBJECT:
        return SnappyType.JAVA_OBJECT;
      case JDBC40Translation.JSON:
        return SnappyType.JSON;
      case Types.LONGVARBINARY:
        return SnappyType.LONGVARBINARY;
      case JDBC40Translation.MAP:
        return SnappyType.MAP;
      case Types.NULL:
        return SnappyType.NULLTYPE;
      case Types.OTHER:
        return SnappyType.OTHER;
      case Types.SQLXML:
        return SnappyType.SQLXML;
      case Types.STRUCT:
        return SnappyType.STRUCT;
      case Types.TIME:
        return SnappyType.TIME;
      case Types.TINYINT:
        return SnappyType.TINYINT;
      case Types.VARBINARY:
        return SnappyType.VARBINARY;
      default:
        return SnappyType.OTHER;
    }
  }

  public static int getJdbcResultSetType(byte thriftType) {
    switch (thriftType) {
      case snappydataConstants.RESULTSET_TYPE_FORWARD_ONLY:
        return ResultSet.TYPE_FORWARD_ONLY;
      case snappydataConstants.RESULTSET_TYPE_INSENSITIVE:
        return ResultSet.TYPE_SCROLL_INSENSITIVE;
      case snappydataConstants.RESULTSET_TYPE_SENSITIVE:
        return ResultSet.TYPE_SCROLL_SENSITIVE;
      default:
        throw new IllegalArgumentException("Thrift ResultSet type="
            + thriftType);
    }
  }

  public static int getThriftResultSetType(int jdbcType) {
    switch (jdbcType) {
      case ResultSet.TYPE_FORWARD_ONLY:
        return snappydataConstants.RESULTSET_TYPE_FORWARD_ONLY;
      case ResultSet.TYPE_SCROLL_INSENSITIVE:
        return snappydataConstants.RESULTSET_TYPE_INSENSITIVE;
      case ResultSet.TYPE_SCROLL_SENSITIVE:
        return snappydataConstants.RESULTSET_TYPE_SENSITIVE;
      default:
        return snappydataConstants.RESULTSET_TYPE_UNKNOWN;
    }
  }

  public static int getJdbcIsolation(int thriftIsolationLevel) {
    switch (thriftIsolationLevel) {
      case snappydataConstants.TRANSACTION_NONE:
        return Connection.TRANSACTION_NONE;
      case snappydataConstants.TRANSACTION_READ_UNCOMMITTED:
        return Connection.TRANSACTION_READ_UNCOMMITTED;
      case snappydataConstants.TRANSACTION_READ_COMMITTED:
        return Connection.TRANSACTION_READ_COMMITTED;
      case snappydataConstants.TRANSACTION_REPEATABLE_READ:
        return Connection.TRANSACTION_REPEATABLE_READ;
      case snappydataConstants.TRANSACTION_SERIALIZABLE:
        return Connection.TRANSACTION_SERIALIZABLE;
      default:
        throw new IllegalArgumentException("Thrift isolation level="
            + thriftIsolationLevel);
    }
  }

  public static byte getThriftTransactionIsolation(int jdbcIsolationLevel) {
    switch (jdbcIsolationLevel) {
      case Connection.TRANSACTION_NONE:
        return snappydataConstants.TRANSACTION_NONE;
      case Connection.TRANSACTION_READ_UNCOMMITTED:
        return snappydataConstants.TRANSACTION_READ_UNCOMMITTED;
      case Connection.TRANSACTION_READ_COMMITTED:
        return snappydataConstants.TRANSACTION_READ_COMMITTED;
      case Connection.TRANSACTION_REPEATABLE_READ:
        return snappydataConstants.TRANSACTION_REPEATABLE_READ;
      case Connection.TRANSACTION_SERIALIZABLE:
        return snappydataConstants.TRANSACTION_SERIALIZABLE;
      default:
        return snappydataConstants.TRANSACTION_NO_CHANGE;
    }
  }
}
