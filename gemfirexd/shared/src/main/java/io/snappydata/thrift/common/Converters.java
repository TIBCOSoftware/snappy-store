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

package io.snappydata.thrift.common;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.Decimal;
import io.snappydata.thrift.SnappyType;
import io.snappydata.thrift.snappydataConstants;
import org.apache.commons.io.input.ReaderInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conversion utilities from thrift API values/enums to JDBC/SnappyData equivalent
 * values.
 */
public abstract class Converters {

  private Converters() {
    // no instance allowed
  }

  private static final Logger LOGGER = LoggerFactory
      .getLogger(Converters.class.getName());

  static final BigDecimal MAXLONG_PLUS_ONE = BigDecimal.valueOf(Long.MAX_VALUE)
      .add(BigDecimal.ONE);
  static final BigDecimal MINLONG_MINUS_ONE = BigDecimal
      .valueOf(Long.MIN_VALUE).subtract(BigDecimal.ONE);

  public static final long MICROS_PER_SECOND = 1000L * 1000L;
  public static final long NANOS_PER_SECOND = MICROS_PER_SECOND * 1000L;
  public static final long NANOS_PER_MILLI = 1000L * 1000L;

  public static final ColumnValueConverter BOOLEAN_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.BOOLEAN;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getBoolean(columnPosition - 1);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return row.getBoolean(columnPosition - 1) ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getBoolean(columnPosition - 1) ? "true" : "false";
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getBoolean(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setBoolean(columnPosition - 1, x);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0.0f);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      row.setBoolean(columnPosition - 1, x != 0.0);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      row.setBoolean(columnPosition - 1, !BigDecimal.ZERO.equals(x));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      row.setBoolean(columnPosition - 1,
          x != null && !(x.equals("0") || x.equalsIgnoreCase("false")));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "boolean", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getByte(columnPosition - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getByte(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Byte.toString(row.getByte(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getByte(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setByte(columnPosition - 1, x ? (byte)1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setByte(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnPosition - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnPosition - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnPosition - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnPosition - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      if (x >= Byte.MIN_VALUE && x <= Byte.MAX_VALUE) {
        row.setByte(columnPosition - 1, (byte)x);
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "byte", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getShort(columnPosition - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      short v = row.getShort(columnPosition - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getShort(columnPosition - 1);
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getShort(columnPosition - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getShort(columnPosition - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getShort(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getShort(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getShort(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Short.toString(row.getShort(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return (int)row.getShort(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setShort(columnPosition - 1, x ? (short)1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setShort(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setShort(columnPosition - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnPosition - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnPosition - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnPosition - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      if (x >= Short.MIN_VALUE && x <= Short.MAX_VALUE) {
        row.setShort(columnPosition - 1, (short)x);
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "short", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getInt(columnPosition - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      int v = row.getInt(columnPosition - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      int v = row.getInt(columnPosition - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getInt(columnPosition - 1);
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getInt(columnPosition - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getInt(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getInt(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getInt(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Integer.toString(row.getInt(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getInt(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setInt(columnPosition - 1, x ? 1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setInt(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setInt(columnPosition - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      row.setInt(columnPosition - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnPosition - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnPosition - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
        row.setInt(columnPosition - 1, (int)x);
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "int", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getLong(columnPosition - 1) != 0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = row.getLong(columnPosition - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = row.getLong(columnPosition - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = row.getLong(columnPosition - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getLong(columnPosition - 1);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getLong(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getLong(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getLong(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Long.toString(row.getLong(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getLong(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setLong(columnPosition - 1, x ? 1 : 0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setLong(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setLong(columnPosition - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      row.setLong(columnPosition - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      row.setLong(columnPosition - 1, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      if (x >= Long.MIN_VALUE && x <= Long.MAX_VALUE) {
        row.setLong(columnPosition - 1, (long)x);
      }
      else {
        throw newOutOfRangeException("long", columnPosition);
      }
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      if (x >= Long.MIN_VALUE && x <= Long.MAX_VALUE) {
        row.setLong(columnPosition - 1, (long)x);
      }
      else {
        throw newOutOfRangeException("long", columnPosition);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setLong(row, columnPosition, getLong(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "long", columnPosition);
      }
    }
  };

  public static final ColumnValueConverter FLOAT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.FLOAT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getFloat(columnPosition - 1) != 0.0f);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      float v = row.getFloat(columnPosition - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      float v = row.getFloat(columnPosition - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      float v = row.getFloat(columnPosition - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      float v = row.getFloat(columnPosition - 1);
      if (v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
        return (long)v;
      }
      else {
        throw newOutOfRangeException("long", columnPosition);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getFloat(columnPosition - 1);
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getFloat(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getFloat(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Float.toString(row.getFloat(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getFloat(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setFloat(columnPosition - 1, x ? 1.0f : 0.0f);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setFloat(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setFloat(columnPosition - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      row.setFloat(columnPosition - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      if (x >= Float.MIN_VALUE) {
        row.setFloat(columnPosition - 1, x);
      }
      else {
        throw newOutOfRangeException("float", columnPosition);
      }
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      row.setFloat(columnPosition - 1, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      if (x >= Float.MIN_VALUE && x <= Float.MAX_VALUE) {
        row.setFloat(columnPosition - 1, (float)x);
      }
      else {
        throw newOutOfRangeException("float", columnPosition);
      }
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setDouble(row, columnPosition, getDouble(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setDouble(row, columnPosition, getDouble(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "float", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (row.getDouble(columnPosition - 1) != 0.0);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = row.getDouble(columnPosition - 1);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = row.getDouble(columnPosition - 1);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = row.getDouble(columnPosition - 1);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = row.getDouble(columnPosition - 1);
      if (v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
        return (long)v;
      }
      else {
        throw newOutOfRangeException("long", columnPosition);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = row.getDouble(columnPosition - 1);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnPosition);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return row.getDouble(columnPosition - 1);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return new BigDecimal(row.getDouble(columnPosition - 1));
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return Double.toString(row.getDouble(columnPosition - 1));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getDouble(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      row.setDouble(columnPosition - 1, x ? 1.0 : 0.0);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      row.setDouble(columnPosition - 1, x);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setDouble(row, columnPosition, getDouble(x, columnPosition));
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      setDouble(row, columnPosition, getDouble(x, columnPosition));
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "double", columnPosition);
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
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      BigDecimal bd = (BigDecimal)row.getObject(columnPosition - 1);
      return bd != null && !bd.equals(BigDecimal.ZERO);
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      BigDecimal decimal = (BigDecimal)row.getObject(columnPosition - 1);
      return getLong(decimal, columnPosition);
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = getDouble((BigDecimal)row.getObject(columnPosition - 1),
          columnPosition);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnPosition);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return getDouble((BigDecimal)row.getObject(columnPosition - 1), columnPosition);
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return (BigDecimal)row.getObject(columnPosition - 1);
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      BigDecimal bd = (BigDecimal)row.getObject(columnPosition - 1);
      if (bd != null) {
        return bd.toPlainString();
      }
      else {
        return null;
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      setBigDecimal(row, columnPosition, x ? BigDecimal.ONE : BigDecimal.ZERO);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      setBigDecimal(row, columnPosition, new BigDecimal(x));
    }
    @Override
    public final void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.DECIMAL);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      row.setObject(columnPosition - 1, getBigDecimal(x, columnPosition),
          SnappyType.DECIMAL);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if ((c = o.getClass()) == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            c.getName(), "BigDecimal", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      java.sql.Date date = row.getDate(columnPosition - 1);
      return date.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Date(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Timestamp(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Timestamp(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Time(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getDate(columnPosition - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnPosition,
        java.sql.Date x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        java.sql.Timestamp x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnPosition,
        java.sql.Time x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      java.sql.Date date = java.sql.Date.valueOf(x);
      row.setDateTime(columnPosition - 1, date);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof java.util.Date) {
        row.setDateTime(columnPosition - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Date", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      java.sql.Time time = row.getTime(columnPosition - 1);
      return time.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Date(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Timestamp(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Timestamp(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long millis = row.getDateTimeMillis(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Time(millis);
      } else {
        cal.setTimeInMillis(millis);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getTime(columnPosition - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnPosition,
        java.sql.Date x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        java.sql.Timestamp x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnPosition,
        java.sql.Time x) throws SQLException {
      row.setDateTime(columnPosition - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      java.sql.Time dtime = java.sql.Time.valueOf(x);
      row.setTimestamp(columnPosition - 1, dtime);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof java.util.Date) {
        row.setDateTime(columnPosition - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Time", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      java.sql.Timestamp ts = row.getTimestamp(columnPosition - 1);
      return ts.toString();
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long ts = row.getLong(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Date(ts / NANOS_PER_MILLI);
      }
      else {
        cal.setTimeInMillis(ts / NANOS_PER_MILLI);
        return new java.sql.Date(cal.getTimeInMillis());
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      long ts = row.getLong(columnPosition - 1);
      if (cal == null) {
        return Converters.getTimestamp(ts);
      } else {
        // pass only the seconds for epoch time
        long seconds = ts / NANOS_PER_SECOND;
        long nanos = ts % NANOS_PER_SECOND;
        if (nanos < 0) {
          nanos += NANOS_PER_SECOND;
          seconds--;
        }
        cal.setTimeInMillis(seconds * 1000L);
        return Converters.getTimestamp(cal.getTimeInMillis() *
            NANOS_PER_MILLI + nanos);
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      long ts = row.getLong(columnPosition - 1);
      if (cal == null) {
        return new java.sql.Time(ts / NANOS_PER_MILLI);
      }
      else {
        cal.setTimeInMillis(ts / NANOS_PER_MILLI);
        return new java.sql.Time(cal.getTimeInMillis());
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getTimestamp(columnPosition - 1);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnPosition,
        java.sql.Date x) throws SQLException {
      row.setTimestamp(columnPosition - 1, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        java.sql.Timestamp x) throws SQLException {
      row.setTimestamp(columnPosition - 1, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnPosition,
        java.sql.Time x) throws SQLException {
      row.setTimestamp(columnPosition - 1, x);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf(x);
      row.setTimestamp(columnPosition - 1, timestamp);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof java.sql.Timestamp) {
        row.setTimestamp(columnPosition - 1, (java.sql.Timestamp)o);
      } else if (o instanceof java.util.Date) {
        row.setTimestamp(columnPosition - 1, (java.util.Date)o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Timestamp", columnPosition);
      }
    }
  };

  public static class StringConverter extends ColumnValueConverter {

    @Override
    public SnappyType getType() {
      return SnappyType.VARCHAR;
    }
    @Override
    public final boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      String str = toString(row, columnPosition);
      if (str != null) {
        str = str.trim();
        return !(str.equals("0") || str.equalsIgnoreCase("false"));
      }
      else {
        return false;
      }
    }
    @Override
    public final byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
        return (byte)v;
      }
      else {
        throw newOutOfRangeException("byte", columnPosition);
      }
    }
    @Override
    public final short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
        return (short)v;
      }
      else {
        throw newOutOfRangeException("short", columnPosition);
      }
    }
    @Override
    public final int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      long v = toLong(row, columnPosition);
      if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
        return (int)v;
      }
      else {
        throw newOutOfRangeException("int", columnPosition);
      }
    }
    @Override
    public final long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return getLong(toString(row, columnPosition), columnPosition);
    }
    @Override
    public final float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      double v = toDouble(row, columnPosition);
      if (v >= Float.MIN_VALUE && v <= Float.MAX_VALUE) {
        return (float)v;
      }
      else {
        throw newOutOfRangeException("float", columnPosition);
      }
    }
    @Override
    public final double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return getDouble(toString(row, columnPosition), columnPosition);
    }
    @Override
    public final BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      String str = toString(row, columnPosition);
      try {
        // BigDecimal constructor calls java.lang.Long.parseLong(),
        // which doesn't like spaces.
        return new BigDecimal(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("BigDecimal", columnPosition, nfe);
      }
    }
    private String toString(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return (String)row.getObject(columnPosition - 1);
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return toString(row, columnPosition);
    }
    @Override
    public final java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      String str = toString(row, columnPosition);
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
        int columnPosition, Calendar cal) throws SQLException {
      String str = toString(row, columnPosition);
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
        } else {
          cal.setTime(ts);
          return Converters.getTimestamp(cal.getTimeInMillis() *
              NANOS_PER_MILLI + ts.getNanos());
        }
      }
      else {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_DATE_SYNTAX_EXCEPTION, null, "<NULL>");
      }
    }
    @Override
    public final java.sql.Time toTime(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      String str = toString(row, columnPosition);
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
    public Reader toCharacterStream(OptimizedElementArray row,
        int columnPosition, LobService lobService) throws SQLException {
      return new StringReader(toString(row, columnPosition));
    }
    @Override
    public InputStream toAsciiStream(OptimizedElementArray row,
        int columnPosition, LobService lobService) throws SQLException {
      return new ReaderInputStream(new StringReader(toString(row,
          columnPosition)), StandardCharsets.US_ASCII);
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      setString(row, columnPosition, x ? "true" : "false");
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      setString(row, columnPosition, Byte.toString(x));
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      setString(row, columnPosition, Short.toString(x));
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      setString(row, columnPosition, Integer.toString(x));
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      setString(row, columnPosition, Long.toString(x));
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      setString(row, columnPosition, Float.toString(x));
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      setString(row, columnPosition, Double.toString(x));
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setString(row, columnPosition, x.toPlainString());
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.VARCHAR);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnPosition,
        java.sql.Date x) throws SQLException {
      setString(row, columnPosition, x.toString());
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        java.sql.Timestamp x) throws SQLException {
      setString(row, columnPosition, x.toString());
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnPosition,
        java.sql.Time x) throws SQLException {
      setString(row, columnPosition, x.toString());
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      final Class<?> c;
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if ((c = o.getClass()) == String.class) {
        setString(row, columnPosition, (String)o);
      } else if (c == Double.class) {
        setDouble(row, columnPosition, (Double)o);
      } else if (c == Float.class) {
        setFloat(row, columnPosition, (Float)o);
      } else if (c == Integer.class) {
        setInteger(row, columnPosition, (Integer)o);
      } else if (c == Byte.class) {
        setByte(row, columnPosition, (Byte)o);
      } else if (c == Short.class) {
        setShort(row, columnPosition, (Short)o);
      } else if (c == Long.class) {
        setLong(row, columnPosition, (Long)o);
      } else if (c == Boolean.class) {
        setBoolean(row, columnPosition, (Boolean)o);
      } else if (o instanceof BigDecimal) {
        setBigDecimal(row, columnPosition, (BigDecimal)o);
      } else if (o instanceof java.util.Date) {
        setString(row, columnPosition, o.toString());
      } else {
        throw newTypeSetConversionException(
            c.getName(), getType().toString(), columnPosition);
      }
    }
  }
  public static final ColumnValueConverter STRING_TYPE = new StringConverter();

  static class ClobConverter extends StringConverter {
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Clob clob = (Clob)row.getObject(columnPosition - 1);
      return clob.getSubString(1, (int)clob.length());
    }
    @Override
    public final Clob toClob(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return (Clob)row.getObject(columnPosition - 1);
    }
    @Override
    public Reader toCharacterStream(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Clob clob = (Clob)row.getObject(columnPosition - 1);
      return clob.getCharacterStream();
    }
    @Override
    public InputStream toAsciiStream(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Clob clob = (Clob)row.getObject(columnPosition - 1);
      return clob.getAsciiStream();
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return toClob(row, columnPosition, lobService);
    }
    private void freeClob(OptimizedElementArray row, final int index)
        throws SQLException {
      Object o;
      if (!row.isNull(index) && (o = row.getObject(index)) != null) {
        ((Clob)o).free();
      }
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      final int index = columnPosition - 1;
      freeClob(row, index);
      row.setObject(index, x, SnappyType.VARCHAR);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        final int index = columnPosition - 1;
        freeClob(row, index);
        row.setNull(index);
      } else if (o instanceof Clob) {
        // not chunking in sends yet
        Clob clob = (Clob)o;
        long clobLen = clob.length();
        if (clobLen > Integer.MAX_VALUE) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_LENGTH_TOO_LONG, null, clobLen);
        }
        setString(row, columnPosition, clob.getSubString(1, (int)clobLen));
      } else {
        super.setObject(row, columnPosition, o);
      }
    }
  }

  public static final ColumnValueConverter CLOB_TYPE = new ClobConverter() {
    @Override
    public SnappyType getType() {
      return SnappyType.CLOB;
    }
  };

  public static final ColumnValueConverter BINARY_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.VARBINARY;
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      byte[] bytes = (byte[])row.getObject(columnPosition - 1);
      if (bytes != null) {
        return ClientSharedUtils.toHexString(bytes, 0, bytes.length);
      }
      else {
        return null;
      }
    }
    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return (byte[])row.getObject(columnPosition - 1);
    }
    @Override
    public InputStream toBinaryStream(OptimizedElementArray row,
        int columnPosition, LobService lobService) throws SQLException {
      return new ByteArrayInputStream(toBytes(row, columnPosition, lobService));
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }
    @Override
    public final void setBytes(OptimizedElementArray row, int columnPosition,
        byte[] x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.VARBINARY);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      try {
        byte[] bytes = ClientSharedUtils.fromHexString(x, 0, x.length());
        setBytes(row, columnPosition, bytes);
      } catch (IllegalArgumentException iae) {
        throw newTypeSetConversionException(
            "String", getType().toString(), Integer.toString(columnPosition), iae);
      }
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof byte[]) {
        setBytes(row, columnPosition, (byte[])o);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "byte[]", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      byte[] bytes = toBytes(row, columnPosition, lobService);
      if (bytes != null) {
        return ClientSharedUtils.toHexString(bytes, 0, bytes.length);
      }
      else {
        return null;
      }
    }
    @Override
    public final byte[] toBytes(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Blob blob = (Blob)row.getObject(columnPosition - 1);
      return blob.getBytes(1, (int)blob.length());
    }
    @Override
    public final Blob toBlob(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return (Blob)row.getObject(columnPosition - 1);
    }
    @Override
    public InputStream toBinaryStream(OptimizedElementArray row,
        int columnPosition, LobService lobService) throws SQLException {
      Blob blob = (Blob)row.getObject(columnPosition - 1);
      return blob.getBinaryStream();
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return toBlob(row, columnPosition, lobService);
    }
    private void freeBlob(OptimizedElementArray row, final int index)
        throws SQLException {
      Object o;
      if (!row.isNull(index) && (o = row.getObject(index)) != null) {
        ((Blob)o).free();
      }
    }
    @Override
    public final void setBytes(OptimizedElementArray row, int columnPosition,
        byte[] x) throws SQLException {
      final int index = columnPosition - 1;
      freeBlob(row, index);
      row.setObject(index, x, SnappyType.VARBINARY);
    }
    @Override
    public void setString(OptimizedElementArray row, int columnPosition, String x)
        throws SQLException {
      try {
        byte[] bytes = ClientSharedUtils.fromHexString(x, 0, x.length());
        setBytes(row, columnPosition, bytes);
      } catch (IllegalArgumentException iae) {
        throw newTypeSetConversionException(
            "String", getType().toString(), Integer.toString(columnPosition), iae);
      }
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        final int index = columnPosition - 1;
        freeBlob(row, index);
        row.setNull(index);
      } else if (o instanceof byte[]) {
        setBytes(row, columnPosition, (byte[])o);
      } else if (o instanceof Blob) {
        // not chunking in sends yet
        Blob blob = (Blob)o;
        long blobLen = blob.length();
        if (blobLen > Integer.MAX_VALUE) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_LENGTH_TOO_LONG, null, blobLen);
        }
        setBytes(row, columnPosition, blob.getBytes(1, (int)blobLen));
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "Blob", columnPosition);
      }
    }
  };

  static final ObjectInputStreamCreator javaObjectCreator =
      new ObjectInputStreamCreator() {
        @Override
        public ObjectInputStream create(InputStream stream) throws IOException {
          return new ClientSharedUtils.ThreadContextObjectInputStream(stream);
        }
      };

  public static final ColumnValueConverter OBJECT_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.JAVA_OBJECT;
    }
    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Boolean) {
        return (Boolean)o;
      }
      else if (o instanceof Byte) {
        return (Byte)o != 0;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "boolean", columnPosition);
      }
    }
    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).byteValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "byte", columnPosition);
      }
    }
    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).shortValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "short", columnPosition);
      }
    }
    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).intValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "int", columnPosition);
      }
    }
    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).longValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "long", columnPosition);
      }
    }
    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).floatValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "float", columnPosition);
      }
    }
    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof Number) {
        return ((Number)o).doubleValue();
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "double", columnPosition);
      }
    }
    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof BigDecimal) {
        return (BigDecimal)o;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "BigDecimal", columnPosition);
      }
    }
    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o != null) {
        return o.toString();
      }
      else {
        return null;
      }
    }
    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof java.sql.Date) {
        return (java.sql.Date)o;
      } else if (o instanceof Number) {
        return Converters.getDate(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Date", columnPosition);
      }
    }
    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof java.sql.Timestamp) {
        return (java.sql.Timestamp)o;
      } else if (o instanceof Number) {
        return Converters.getTimestamp(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Timestamp", columnPosition);
      }
    }
    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof java.sql.Time) {
        return (java.sql.Time)o;
      } else if (o instanceof Number) {
        return Converters.getTime(((Number)o).longValue());
      } else {
        throw newTypeConversionException(
            getType().toString(), "Time", columnPosition);
      }
    }
    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Object o = toObject(row, columnPosition);
      if (o instanceof byte[]) {
        return (byte[])o;
      }
      else {
        throw newTypeConversionException(
            getType().toString(), "byte[]", columnPosition);
      }
    }
    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return toObject(row, columnPosition);
    }
    private Object toObject(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      JavaObjectWrapper jw = (JavaObjectWrapper)row.getObject(columnPosition - 1);
      if (jw != null) {
        return jw.getDeserialized(columnPosition, javaObjectCreator);
      } else {
        return null;
      }
    }
    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition,
        boolean x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setBigDecimal(OptimizedElementArray row,
        int columnPosition, BigDecimal x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public final void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setDate(OptimizedElementArray row, int columnPosition,
        java.sql.Date x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        java.sql.Timestamp x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setTime(OptimizedElementArray row, int columnPosition,
        java.sql.Time x) throws SQLException {
      setObject(row, columnPosition, x);
    }
    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      row.setObject(columnPosition - 1,
          new JavaObjectWrapper(o, columnPosition), SnappyType.JAVA_OBJECT);
    }
  };

  public static final ColumnValueConverter ARRAY_TYPE =
      new ColumnValueConverter() {

    @Override
    public SnappyType getType() {
      return SnappyType.ARRAY;
    }

    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnPosition - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof List<?>) {
        row.setObject(columnPosition - 1, o, SnappyType.ARRAY);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "ARRAY", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnPosition - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof Map<?, ?>) {
        row.setObject(columnPosition - 1, o, SnappyType.MAP);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "MAP", columnPosition);
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
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      Object o = row.getObject(columnPosition - 1);
      if (o != null) {
        return o.toString();
      } else {
        return null;
      }
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return row.getObject(columnPosition - 1);
    }

    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      row.setObject(columnPosition - 1, x, SnappyType.VARCHAR);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnPosition, Object o)
        throws SQLException {
      if (o == null) {
        row.setNull(columnPosition - 1);
      } else if (o instanceof List<?>) {
        row.setObject(columnPosition - 1, o, SnappyType.STRUCT);
      } else if (o instanceof String) {
        setString(row, columnPosition, (String)o);
      } else {
        throw newTypeSetConversionException(
            o.getClass().getName(), "STRUCT", columnPosition);
      }
    }
  };

  public static final ColumnValueConverter JSON_TYPE = new ClobConverter() {
    @Override
    public SnappyType getType() {
      return SnappyType.JSON;
    }
  };

  public static class NullConverter extends ColumnValueConverter {

    @Override
    public SnappyType getType() {
      return SnappyType.NULLTYPE;
    }

    @Override
    public boolean toBoolean(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return false;
    }

    @Override
    public byte toByte(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0;
    }

    @Override
    public short toShort(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0;
    }

    @Override
    public int toInteger(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0;
    }

    @Override
    public long toLong(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0;
    }

    @Override
    public float toFloat(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0.0f;
    }

    @Override
    public double toDouble(OptimizedElementArray row, int columnPosition)
        throws SQLException {
      return 0.0;
    }

    @Override
    public BigDecimal toBigDecimal(OptimizedElementArray row,
        int columnPosition) throws SQLException {
      return null;
    }

    @Override
    public String toString(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public java.sql.Date toDate(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      return null;
    }

    @Override
    public java.sql.Timestamp toTimestamp(OptimizedElementArray row,
        int columnPosition, Calendar cal) throws SQLException {
      return null;
    }

    @Override
    public java.sql.Time toTime(OptimizedElementArray row, int columnPosition,
        Calendar cal) throws SQLException {
      return null;
    }

    @Override
    public byte[] toBytes(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public Blob toBlob(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public InputStream toBinaryStream(OptimizedElementArray row,
        int columnIndex, LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public Clob toClob(OptimizedElementArray row, int columnIndex,
        LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public Reader toCharacterStream(OptimizedElementArray row,
        int columnIndex, LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public InputStream toAsciiStream(OptimizedElementArray row,
        int columnIndex, LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public Object toObject(OptimizedElementArray row, int columnPosition,
        LobService lobService) throws SQLException {
      return null;
    }

    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      throw Converters.newTypeSetConversionException(
          getType().toString(), "String", columnPosition);
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnPosition,
        Object x) throws SQLException {
      throw Converters.newTypeSetConversionException(
          getType().toString(), "Object", columnPosition);
    }

    @Override
    public boolean isNull() {
      return true;
    }
  }

  /**
   * This is used to delegate set* calls that must fail if the underlying
   * column type itself is NULLTYPE. Else if the value is null but underlying
   * type is a proper one, then {@link #NULL_TYPE} is used.
   */
  static final ColumnValueConverter NULL_ONLY_TYPE = new NullConverter();

  public static final ColumnValueConverter NULL_TYPE = new NullConverter() {

    private ColumnValueConverter getConverterForSet(int snappyTypeValue,
        String targetType, int columnPosition) throws SQLException {
      ColumnValueConverter conv = getConverter(snappyTypeValue, targetType,
          true, columnPosition);
      // for the rare case when underlying type itself is a NULLTYPE don't fall
      // into infinite recursion but use NULL_ONLY_TYPE to fail for set* calls
      return (conv != NULL_TYPE) ? conv : NULL_ONLY_TYPE;
    }

    @Override
    public void setBoolean(OptimizedElementArray row, int columnPosition, boolean x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "boolean",
          columnPosition).setBoolean(row, columnPosition, x);
    }

    @Override
    public void setByte(OptimizedElementArray row, int columnPosition, byte x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "byte",
          columnPosition).setByte(row, columnPosition, x);
    }

    @Override
    public void setShort(OptimizedElementArray row, int columnPosition, short x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "short",
          columnPosition).setShort(row, columnPosition, x);
    }

    @Override
    public void setInteger(OptimizedElementArray row, int columnPosition, int x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "int",
          columnPosition).setInteger(row, columnPosition, x);
    }

    @Override
    public void setLong(OptimizedElementArray row, int columnPosition, long x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "long",
          columnPosition).setLong(row, columnPosition, x);
    }

    @Override
    public void setFloat(OptimizedElementArray row, int columnPosition, float x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "float",
          columnPosition).setFloat(row, columnPosition, x);
    }

    @Override
    public void setDouble(OptimizedElementArray row, int columnPosition, double x)
        throws SQLException {
      // mark as non-null and call the underlying type's method
      getConverterForSet(row.setNotNull(columnPosition - 1), "double",
          columnPosition).setDouble(row, columnPosition, x);
    }

    @Override
    public void setBigDecimal(OptimizedElementArray row, int columnPosition,
        BigDecimal x) throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "BigDecimal",
            columnPosition).setBigDecimal(row, columnPosition, x);
      }
    }

    @Override
    public void setString(OptimizedElementArray row, int columnPosition,
        String x) throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "string",
            columnPosition).setString(row, columnPosition, x);
      }
    }

    @Override
    public void setDate(OptimizedElementArray row, int columnPosition, Date x)
        throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "date",
            columnPosition).setDate(row, columnPosition, x);
      }
    }

    @Override
    public void setTime(OptimizedElementArray row, int columnPosition, Time x)
        throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "time",
            columnPosition).setTime(row, columnPosition, x);
      }
    }

    @Override
    public void setTimestamp(OptimizedElementArray row, int columnPosition,
        Timestamp x) throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "timestamp",
            columnPosition).setTimestamp(row, columnPosition, x);
      }
    }

    @Override
    public void setBytes(OptimizedElementArray row, int columnPosition, byte[] x)
        throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "byte[]",
            columnPosition).setBytes(row, columnPosition, x);
      }
    }

    @Override
    public void setBlob(OptimizedElementArray row, int columnPosition, Blob x)
        throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "blob",
            columnPosition).setBlob(row, columnPosition, x);
      }
    }

    @Override
    public void setBinaryStream(OptimizedElementArray row, int columnPosition,
        InputStream stream, long length,
        LobService lobService) throws SQLException {
      if (stream != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "BinaryStream",
            columnPosition).setBinaryStream(row, columnPosition, stream,
            length, lobService);
      }
    }

    @Override
    public void setClob(OptimizedElementArray row, int columnPosition, Clob x)
        throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "clob",
            columnPosition).setClob(row, columnPosition, x);
      }
    }

    @Override
    public void setCharacterStream(OptimizedElementArray row, int columnPosition,
        Reader reader, long length, LobService lobService) throws SQLException {
      if (reader != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "CharacterStream",
            columnPosition).setCharacterStream(row, columnPosition, reader,
            length, lobService);
      }
    }

    @Override
    public void setAsciiStream(OptimizedElementArray row, int columnPosition,
        InputStream stream, long length,
        LobService lobService) throws SQLException {
      if (stream != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "AsciiStream",
            columnPosition).setAsciiStream(row, columnPosition, stream,
            length, lobService);
      }
    }

    @Override
    public void setObject(OptimizedElementArray row, int columnPosition,
        Object x) throws SQLException {
      if (x != null) {
        // mark as non-null and call the underlying type's method
        getConverterForSet(row.setNotNull(columnPosition - 1), "object",
            columnPosition).setObject(row, columnPosition, x);
      }
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
        case FLOAT:
          jdbcTypes[typeValue] = Types.REAL;
          typeConverters[typeValue] = FLOAT_TYPE;
          break;
        case DOUBLE:
          jdbcTypes[typeValue] = Types.DOUBLE;
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
      String sourceType, String targetType, int columnPosition) {
    return newTypeConversionException(sourceType, targetType,
        Integer.toString(columnPosition), null);
  }

  public static SQLException newTypeSetConversionException(
      String sourceType, String targetType, String column, Throwable cause) {
    return ThriftExceptionUtil.newSQLException(
        SQLState.LANG_DATA_TYPE_SET_MISMATCH, cause, sourceType, targetType,
        column);
  }

  public static SQLException newTypeSetConversionException(
      String sourceType, String targetType, int columnPosition) {
    return newTypeSetConversionException(sourceType, targetType,
        Integer.toString(columnPosition), null);
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
      String targetType, boolean forSet, int columnPosition) throws SQLException {
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
            typeString, targetType, Integer.toString(columnPosition), null);
      } else {
        throw newTypeConversionException(
            typeString, targetType, Integer.toString(columnPosition), null);
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

  public static BigDecimal getBigDecimal(String str, int columnPosition)
      throws SQLException {
    if (str != null) {
      try {
        return new BigDecimal(str);
      } catch (NumberFormatException nfe) {
        throw newFormatException("decimal", columnPosition, nfe);
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

  public static long getLong(String str, int columnPosition) throws SQLException {
    if (str != null) {
      try {
        return Long.parseLong(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("long", columnPosition, nfe);
      }
    }
    else {
      return 0;
    }
  }

  public static long getLong(BigDecimal decimal, int columnPosition)
      throws SQLException {
    if (decimal != null) {
      if ((decimal.compareTo(MINLONG_MINUS_ONE) == 1)
          && (decimal.compareTo(MAXLONG_PLUS_ONE) == -1)) {
        return decimal.longValue();
      }
      else {
        throw newOutOfRangeException("long", columnPosition);
      }
    }
    else {
      return 0;
    }
  }

  public static double getDouble(String str, int columnPosition)
      throws SQLException {
    if (str != null) {
      try {
        return Double.parseDouble(str.trim());
      } catch (NumberFormatException nfe) {
        throw newFormatException("double", columnPosition, nfe);
      }
    }
    else {
      return 0.0;
    }
  }

  public static double getDouble(BigDecimal decimal, int columnPosition)
      throws SQLException {
    if (decimal != null) {
      double v = decimal.doubleValue();
      if (!Double.isNaN(v) && !Double.isInfinite(v)) {
        return v;
      }
      else {
        throw newOutOfRangeException("double", columnPosition);
      }
    }
    else {
      return 0.0;
    }
  }

  public static java.sql.Timestamp getTimestamp(long ts) {
    // pass only the seconds for epoch time
    long seconds = ts / NANOS_PER_SECOND;
    long nanos = ts % NANOS_PER_SECOND;
    if (nanos < 0) {
      nanos += NANOS_PER_SECOND;
      seconds--;
    }
    java.sql.Timestamp jts = new java.sql.Timestamp(seconds * 1000L);
    jts.setNanos((int)nanos);
    return jts;
  }

  public static long getTimestampNanos(java.sql.Timestamp ts) {
    return (ts.getTime() * NANOS_PER_MILLI) + (ts.getNanos() % NANOS_PER_MILLI);
  }

  public static long getTimestampNanos(java.util.Date date) {
    return date.getTime() * NANOS_PER_MILLI;
  }

  public static Object getJavaObject(byte[] bytes, int columnPosition,
      ObjectInputStreamCreator creator) throws SQLException {
    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
    try (ObjectInputStream ois = creator.create(in)) {
      return ois.readObject();
    } catch (IOException | ClassNotFoundException e) {
      LOGGER.warn("Exception in deserialization of java object", e);
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, e,
          "Java object deserialization at position=" + columnPosition);
    }
  }

  public static byte[] getJavaObjectAsBytes(Object o, int columnPosition)
      throws SQLException {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream os = new ObjectOutputStream(bos);
      os.writeObject(o);
      os.flush();
      return bos.toByteArray();
    } catch (IOException ioe) {
      LOGGER.warn("Exception in serialization of java object", ioe);
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe,
          "Java object serialization at position=" + columnPosition);
    }
  }

  /**
   * Get JDBC {@link Types} type for given {@link SnappyType}.
   */
  public static int getJdbcType(SnappyType type) {
    return type != null ? jdbcTypes[type.getValue()] : Types.NULL;
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
      // GemFireXD FLOAT can be DOUBLE or REAL depending on precision
      case Types.FLOAT:
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
        return SnappyType.FLOAT;
      case Types.LONGVARCHAR:
        return SnappyType.LONGVARCHAR;
      case Types.BLOB:
        return SnappyType.BLOB;
      case Types.CLOB:
        return SnappyType.CLOB;
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

  /**
   * Wraps a java object and its serialized form. Serves two purpose:
   * <p>
   * a) Serialize/deserialize at application layer calls rather than during
   * serialization/deserialization at transport layer else latter can leave
   * dangling data on socket (or already flushed to the server)
   * <p>
   * b) Avoid multiple serialization/deserialization for an object.
   */
  public static final class JavaObjectWrapper {
    private Object deserialized;
    private byte[] serialized;

    public JavaObjectWrapper(Object deserialized, int columnPosition)
        throws SQLException {
      this.deserialized = deserialized;
      this.serialized = Converters.getJavaObjectAsBytes(
          this.deserialized, columnPosition);
    }

    public JavaObjectWrapper(byte[] serialized) {
      this.serialized = serialized;
    }

    public Object getDeserialized(int columnPosition,
        ObjectInputStreamCreator creator) throws SQLException {
      final Object deserialized = this.deserialized;
      if (deserialized != null) {
        return deserialized;
      } else {
        return (this.deserialized = Converters.getJavaObject(
            this.serialized, columnPosition, creator));
      }
    }

    public byte[] getSerialized() {
      return this.serialized;
    }
  }

  /**
   * Callback to enable creation of an ObjectInputStream given an InputStream
   * (primarily for custom class loading).
   */
  public interface ObjectInputStreamCreator {
    ObjectInputStream create(InputStream stream) throws IOException;
  }
}
