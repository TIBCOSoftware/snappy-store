/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.types;

import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;

import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.ResultWasNull;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

import java.math.BigDecimal;
import java.sql.Types;
import java.sql.ResultSetMetaData;
import java.util.Calendar;

/**
 * A set of static utility methods for data types.
 */
public abstract class DataTypeUtilities {

  /**
   * Get the precision of the datatype.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static int getPrecision(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();

    switch (typeId) {
    case Types.CHAR: // CHAR et alia return their # characters...
    case Types.VARCHAR:
    case Types.LONGVARCHAR:
    case Types.CLOB:
    case Types.BINARY: // BINARY types return their # bytes...
    case Types.VARBINARY:
    case Types.LONGVARBINARY:
    case Types.BLOB:
    case JDBC40Translation.SQLXML:
    case JDBC40Translation.JSON:
      return dtd.getMaximumWidth();
    case Types.SMALLINT:
      return 5;
    case Types.BOOLEAN:
      return 1;
    }

    return dtd.getPrecision();
  }

  /**
   * Get the precision of the datatype, in decimal digits This is used by
   * EmbedResultSetMetaData.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static int getDigitPrecision(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();

    switch (typeId) {
    case Types.FLOAT:
    case Types.DOUBLE:
      return TypeId.DOUBLE_PRECISION_IN_DIGITS;
    case Types.REAL:
      return TypeId.REAL_PRECISION_IN_DIGITS;
    default:
      return getPrecision(dtd);
    }

  }

  /**
   * Is the data type currency.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static boolean isCurrency(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();

    // Only the NUMERIC and DECIMAL types are currency
    return ((typeId == Types.DECIMAL) || (typeId == Types.NUMERIC));
  }

  /**
   * Is the data type case sensitive.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static boolean isCaseSensitive(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();

    return (typeId == Types.CHAR || typeId == Types.VARCHAR
        || typeId == Types.CLOB || typeId == Types.LONGVARCHAR || typeId == JDBC40Translation.SQLXML
        || typeId == JDBC40Translation.JSON);
  }

  /**
   * Is the data type nullable.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static int isNullable(DataTypeDescriptor dtd) {
    return dtd.isNullable() ? ResultSetMetaData.columnNullable
        : ResultSetMetaData.columnNoNulls;
  }

  /**
   * Is the data type signed.
   * 
   * @param dtd
   *          data type descriptor
   */
  public static boolean isSigned(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();

    return (typeId == Types.INTEGER || typeId == Types.FLOAT
        || typeId == Types.DECIMAL || typeId == Types.SMALLINT
        || typeId == Types.BIGINT || typeId == Types.TINYINT
        || typeId == Types.NUMERIC || typeId == Types.REAL || typeId == Types.DOUBLE);
  }

  /**
   * Gets the display width of a column of a given type.
   * 
   * @param dtd
   *          data type descriptor
   * 
   * @return associated column display width
   */
  public static int getColumnDisplaySize(DataTypeDescriptor dtd) {
    int typeId = dtd.getTypeId().getJDBCTypeId();
    int storageLength = dtd.getMaximumWidth();
    return DataTypeUtilities.getColumnDisplaySize(typeId, storageLength);
  }

  public static int getColumnDisplaySize(int typeId, int storageLength) {
    int size;
    switch (typeId) {
    case Types.TIMESTAMP:
      size = 26;
      break;
    case Types.DATE:
      size = 10;
      break;
    case Types.TIME:
      size = 8;
      break;
    case Types.INTEGER:
      size = 11;
      break;
    case Types.SMALLINT:
      size = 6;
      break;
    case Types.REAL:
    case Types.FLOAT:
      size = 13;
      break;
    case Types.DOUBLE:
      size = 22;
      break;
    case Types.TINYINT:
      size = 15;
      break;

    case Types.BINARY:
    case Types.VARBINARY:
    case Types.LONGVARBINARY:
    case Types.BLOB:
      size = 2 * storageLength;
      if (size < 0)
        size = Integer.MAX_VALUE;
      break;

    case Types.BIGINT:
      size = 20;
      break;
    case Types.BIT:
    case Types.BOOLEAN:
      // Types.BIT == SQL BOOLEAN, so 5 chars for 'false'
      // In JDBC 3.0, Types.BIT or Types.BOOLEAN = SQL BOOLEAN
      size = 5;
      break;
    default:
      // MaximumWidth is -1 when it is unknown.
      int w = storageLength;
      size = (w > 0 ? w : JDBC30Translation.DEFAULT_COLUMN_DISPLAY_SIZE);
      break;
    }
    return size;
  }

  /**
   * Compute the maximum width (column display width) of a decimal or numeric
   * data value, given its precision and scale.
   * 
   * @param precision
   *          The precision (number of digits) of the data value.
   * @param scale
   *          The number of fractional digits (digits to the right of the
   *          decimal point).
   * 
   * @return The maximum number of chracters needed to display the value.
   */
  public static int computeMaxWidth(int precision, int scale) {
    // There are 3 possible cases with respect to finding the correct max
    // width for DECIMAL type.
    // 1. If scale = 0, only sign should be added to precision.
    // 2. scale=precision, 3 should be added to precision for sign, decimal and
    // an additional char '0'.
    // 3. precision > scale > 0, 2 should be added to precision for sign and
    // decimal.
    return (scale == 0) ? (precision + 1)
        : ((scale == precision) ? (precision + 3) : (precision + 2));
  }

// GemStone changes BEGIN

  /**
   * Extract the given column from raw bytes as a string. Parameter "wasNull"
   * can be null unlike other "getAs*" calls.
   */
  public static final String getAsString(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(inBytes, offset, columnWidth, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.toString(SQLInteger.getAsInteger(inBytes, offset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.toString(SQLLongint.getAsLong(inBytes, offset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.toString(SQLDouble.getAsDouble(inBytes, offset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.toString(SQLReal.getAsFloat(inBytes, offset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.toString(SQLSmallint.getAsShort(inBytes, offset));
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsString(inBytes, offset);
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsString(inBytes, offset);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsString(inBytes, offset);
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return Boolean.toString(SQLBoolean.getAsBoolean(inBytes, offset));
      case StoredFormatIds.TINYINT_TYPE_ID:
        return Byte.toString(SQLTinyint.getAsByte(inBytes, offset));
      case StoredFormatIds.XML_TYPE_ID:
        return XML.getAsString(inBytes, offset, columnWidth);
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        final String result = dvd.getString();
        if (result != null) {
          return result;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
    }
  }

  /**
   * Extract the given column from raw bytes as a string. Parameter "wasNull"
   * can be null unlike other "getAs*" calls.
   */
  public static final String getAsString(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(unsafe, memOffset, columnWidth, bs, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(unsafe, memOffset, columnWidth, bs);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.toString(SQLInteger.getAsInteger(unsafe, memOffset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsString(unsafe, memOffset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.toString(SQLLongint.getAsLong(unsafe, memOffset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.toString(SQLDouble.getAsDouble(unsafe, memOffset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.toString(SQLReal.getAsFloat(unsafe, memOffset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.toString(SQLSmallint.getAsShort(unsafe, memOffset));
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsString(unsafe, memOffset);
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsString(unsafe, memOffset);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsString(unsafe, memOffset);
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return Boolean.toString(SQLBoolean.getAsBoolean(unsafe, memOffset));
      case StoredFormatIds.TINYINT_TYPE_ID:
        return Byte.toString(SQLTinyint.getAsByte(unsafe, memOffset));
      case StoredFormatIds.XML_TYPE_ID:
        return XML.getAsString(unsafe, memOffset, columnWidth, bs);
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        final String result = dvd.getString();
        if (result != null) {
          return result;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
    }
  }

  /**
   * Extract the given column from raw bytes as a java object. Parameter
   * "wasNull" can be null unlike other "getAs*" calls.
   */
  public static final Object getAsObject(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(inBytes, offset, columnWidth, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.valueOf(SQLInteger.getAsInteger(inBytes, offset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          return bd;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.valueOf(SQLLongint.getAsLong(inBytes, offset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.valueOf(SQLDouble.getAsDouble(inBytes, offset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.valueOf(SQLReal.getAsFloat(inBytes, offset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.valueOf(SQLSmallint.getAsShort(inBytes, offset));
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDate(inBytes, offset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTime(inBytes, offset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStamp(inBytes, offset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return Boolean.valueOf(SQLBoolean.getAsBoolean(inBytes, offset));
      case StoredFormatIds.TINYINT_TYPE_ID:
        return Byte.valueOf(SQLTinyint.getAsByte(inBytes, offset));

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        return SQLBinary.getAsBytes(inBytes, offset, columnWidth);

      case StoredFormatIds.XML_TYPE_ID:
        return XML.getAsString(inBytes, offset, columnWidth);

      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        final Object result = dvd.getObject();
        if (result != null) {
          return result;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
    }
  }

  /**
   * Extract the given column from raw bytes as a java object. Parameter
   * "wasNull" can be null unlike other "getAs*" calls.
   */
  public static final Object getAsObject(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(unsafe, memOffset, columnWidth, bs, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(unsafe, memOffset, columnWidth, bs);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.valueOf(SQLInteger.getAsInteger(unsafe, memOffset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          return bd;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.valueOf(SQLLongint.getAsLong(unsafe, memOffset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.valueOf(SQLDouble.getAsDouble(unsafe, memOffset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.valueOf(SQLReal.getAsFloat(unsafe, memOffset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.valueOf(SQLSmallint.getAsShort(unsafe, memOffset));
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDate(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTime(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStamp(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar());
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return Boolean.valueOf(SQLBoolean.getAsBoolean(unsafe, memOffset));
      case StoredFormatIds.TINYINT_TYPE_ID:
        return Byte.valueOf(SQLTinyint.getAsByte(unsafe, memOffset));

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        return SQLBinary.getAsBytes(unsafe, memOffset, columnWidth);

      case StoredFormatIds.XML_TYPE_ID:
        return XML.getAsString(unsafe, memOffset, columnWidth, bs);

      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        final Object result = dvd.getObject();
        if (result != null) {
          return result;
        }
        else {
          if (wasNull != null) {
            wasNull.setWasNull();
          }
          return null;
        }
    }
  }

  static final byte[] INT_MIN_BYTES = new byte[] { (byte)'-', (byte)'2',
      (byte)'1', (byte)'4', (byte)'7', (byte)'4', (byte)'8', (byte)'3',
      (byte)'6', (byte)'4', (byte)'8' };

  static final byte[] LONG_MIN_BYTES = new byte[] { (byte)'-', (byte)'9',
      (byte)'2', (byte)'2', (byte)'3', (byte)'3', (byte)'7', (byte)'2',
      (byte)'0', (byte)'3', (byte)'6', (byte)'8', (byte)'5', (byte)'4',
      (byte)'7', (byte)'7', (byte)'5', (byte)'8', (byte)'0', (byte)'8' };

  /** requires positive argument */
  static int numDecimalDigits(int v) {
    int pow10 = 10;
    int digits = 1;
    while (v >= pow10) {
      // max number of digits is 10
      if (++digits != 10) {
        pow10 *= 10;
      }
      else {
        return 10;
      }
    }
    return digits;
  }

  /** requires positive argument */
  static int numDecimalDigits(long v) {
    long pow10 = 10;
    int digits = 1;
    while (v >= pow10) {
      // max number of digits is 19
      if (++digits != 19) {
        pow10 *= 10;
      }
      else {
        return 19;
      }
    }
    return digits;
  }

  /**
   * Appends string base 10 representation of given int value to buffer.
   */
  public static void toString(int v, final ByteArrayDataOutput buffer) {
    if (v < 0) {
      if (v != Integer.MIN_VALUE) {
        v = -v;
        buffer.write('-');
      }
      else {
        // handle MIN_VALUE in a special way since it overflows when made
        // positive
        buffer.write(INT_MIN_BYTES);
        return;
      }
    }

    final int size = numDecimalDigits(v);

    // expand the buffer to be able to contain the int
    int pos = buffer.ensureCapacity(size, buffer.position());
    pos += size;
    // using raw data to write for best performance
    final byte[] data = buffer.getData();
    do {
      data[--pos] = (byte)((v % 10) + '0');
      v /= 10;
    } while (v > 0);

    // update the buffer position to the end
    buffer.advance(size);
  }

  /**
   * Appends string base 10 representation of given long value to buffer.
   */
  public static void toString(long v, final ByteArrayDataOutput buffer) {
    if (v < 0) {
      if (v != Long.MIN_VALUE) {
        v = -v;
        buffer.write('-');
      }
      else {
        // handle MIN_VALUE in a special way since it overflows when made
        // positive
        buffer.write(LONG_MIN_BYTES);
        return;
      }
    }

    final int size = numDecimalDigits(v);

    // expand the buffer to be able to contain the long
    int pos = buffer.ensureCapacity(size, buffer.position());
    pos += size;
    // using raw data to write for best performance
    final byte[] data = buffer.getData();
    do {
      data[--pos] = (byte)((v % 10) + '0');
      v /= 10;
    } while (v > 0);

    // update the buffer position to the end
    buffer.advance(size);
  }

  /**
   * Appends string base 10 representation of given int value to buffer,
   * writing to the end of buffer till "endOffset" and returns the number of
   * characters written.
   */
  public static int toStringUnsigned(int v, char[] buffer, int endOffset) {
    assert v >= 0: Integer.toString(v);

    do {
      buffer[--endOffset] = (char)((v % 10) + '0');
      v /= 10;
    } while (v > 0);
    return endOffset;
  }

  /**
   * Appends string base 10 representation of given int value to buffer,
   * writing to the end of buffer till "endOffset" and returns the number of
   * characters written.
   */
  public static int toStringUnsigned(long v, char[] buffer, int endOffset) {
    assert v >= 0: Long.toString(v);

    do {
      buffer[--endOffset] = (char)((v % 10) + '0');
      v /= 10;
    } while (v > 0);
    return endOffset;
  }

  /**
   * Appends string base 10 representation of given int value to buffer with
   * decimal point at given position, writing to the end of buffer till
   * "endOffset" and returns the number of characters written.
   * <p>
   * Given integer should be positive.
   */
  public static int toStringUnsignedWithDecimalPoint(int v, char[] buffer,
      int endOffset, final int decimalPos) {
    assert v >= 0: Integer.toString(v);

    do {
      if (--endOffset == decimalPos) {
        buffer[endOffset--] = '.';
      }
      buffer[endOffset] = (char)((v % 10) + '0');
      v /= 10;
    } while (v > 0);
    return endOffset;
  }

  /**
   * Appends string base 10 representation of given int value to buffer with
   * decimal point at given position, writing to the end of buffer till
   * "endOffset" and returns the number of characters written.
   * <p>
   * Given integer should be positive.
   */
  public static int toStringUnsignedWithDecimalPoint(long v, char[] buffer,
      int endOffset, final int decimalPos) {
    assert v >= 0: Long.toString(v);

    do {
      if (--endOffset == decimalPos) {
        buffer[endOffset--] = '.';
      }
      buffer[endOffset] = (char)((v % 10) + '0');
      v /= 10;
    } while (v > 0);
    return endOffset;
  }

  /**
   * Convert a byte array to a String encoded as UTF8 bytes with a hexidecimal
   * format.<BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   * @param buffer
   *          the buffer into which the hexadecimal format of the byte array
   *          will be placed
   */
  public static void toHexString(final byte[] data, int offset,
      final int length, final ByteArrayDataOutput buffer) {
    final int end = offset + length;
    for (; offset < end; offset++) {
      final byte b = data[offset];
      buffer.write(ClientSharedUtils.HEX_DIGITS[(b & 0xf0) >>> 4]);
      buffer.write(ClientSharedUtils.HEX_DIGITS[(b & 0x0f)]);
    }
  }

  /**
   * Convert a byte array to a String encoded as UTF8 bytes with a hexidecimal
   * format.<BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param unsafe
   *          UnsafeWrapper to read off-heap byte array
   * @param memOffset
   *          starting unsafe byte (zero based) of the off-heap byte array
   * @param length
   *          number of bytes to convert.
   * @param buffer
   *          the buffer into which the hexadecimal format of the byte array
   *          will be placed
   */
  public static void toHexString(final UnsafeWrapper unsafe, long memOffset,
      final int length, final ByteArrayDataOutput buffer) {
    final long memEnd = memOffset + length;
    for (; memOffset < memEnd; memOffset++) {
      // TODO: PERF: can be made more efficient by reading in batches or getLong
      final byte b = unsafe.getByte(memOffset);
      buffer.write(ClientSharedUtils.HEX_DIGITS[(b & 0xf0) >>> 4]);
      buffer.write(ClientSharedUtils.HEX_DIGITS[(b & 0x0f)]);
    }
  }

  /** Extract the given column from raw bytes as UTF8 encoded string for PXF. */
  public static final void writeAsUTF8BytesForPXF(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd,
      final ByteArrayDataOutput buffer) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        SQLChar.writeAsUTF8String(inBytes, offset, columnWidth, dtd, buffer);
        return;
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID:
        SQLChar.writeAsUTF8String(inBytes, offset, columnWidth, columnWidth,
            buffer);
        return;
      case StoredFormatIds.INT_TYPE_ID:
        toString(SQLInteger.getAsInteger(inBytes, offset), buffer);
        return;
      case StoredFormatIds.LONGINT_TYPE_ID:
        toString(SQLLongint.getAsLong(inBytes, offset), buffer);
        return;

      case StoredFormatIds.DECIMAL_TYPE_ID:
        SQLDecimal.writeAsString(inBytes, offset, columnWidth, buffer);
        return;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        buffer.writeBytes(Double.toString(SQLDouble.getAsDouble(inBytes,
            offset)));
        return;
      case StoredFormatIds.REAL_TYPE_ID:
        buffer.writeBytes(Float.toString(SQLReal.getAsFloat(inBytes, offset)));
        return;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        toString(SQLSmallint.getAsShort(inBytes, offset), buffer);
        return;
      case StoredFormatIds.DATE_TYPE_ID:
        SQLDate.writeAsString(inBytes, offset, buffer);
        return;
      case StoredFormatIds.TIME_TYPE_ID:
        SQLTime.writeAsString(inBytes, offset, buffer);
        return;
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        SQLTimestamp.writeAsString(inBytes, offset, buffer);
        return;
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        buffer.writeBytes(Boolean.toString(SQLBoolean.getAsBoolean(inBytes,
            offset)));
        return;
      case StoredFormatIds.TINYINT_TYPE_ID:
        toString(SQLTinyint.getAsByte(inBytes, offset), buffer);
        return;

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        // first append '\x' as PostgreSQL requires
        buffer.write('\\');
        buffer.write('x');
        toHexString(inBytes, offset, columnWidth, buffer);
        return;

      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        final String result = dvd.getString();
        if (result != null) {
          // we don't expect non-ASCII characters except in *CHAR/CLOB/XML
          // types that have already been taken care of above
          buffer.writeBytes(result);
        }
        return;
    }
  }

  /** Extract the given column from raw bytes as UTF8 encoded string for PXF. */
  public static final void writeAsUTF8BytesForPXF(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd,
      final ByteArrayDataOutput buffer) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        SQLChar.writeAsUTF8String(unsafe, memOffset, columnWidth, bs, dtd,
            buffer);
        return;
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID:
        SQLChar.writeAsUTF8String(unsafe, memOffset, columnWidth, columnWidth,
            bs, buffer);
        return;
      case StoredFormatIds.INT_TYPE_ID:
        toString(SQLInteger.getAsInteger(unsafe, memOffset), buffer);
        return;
      case StoredFormatIds.LONGINT_TYPE_ID:
        toString(SQLLongint.getAsLong(unsafe, memOffset), buffer);
        return;

      case StoredFormatIds.DECIMAL_TYPE_ID:
        SQLDecimal.writeAsString(unsafe, memOffset, columnWidth, buffer);
        return;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        buffer.writeBytes(Double.toString(SQLDouble.getAsDouble(unsafe, memOffset)));
        return;
      case StoredFormatIds.REAL_TYPE_ID:
        buffer.writeBytes(Float.toString(SQLReal.getAsFloat(unsafe, memOffset)));
        return;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        toString(SQLSmallint.getAsShort(unsafe, memOffset), buffer);
        return;
      case StoredFormatIds.DATE_TYPE_ID:
        SQLDate.writeAsString(unsafe, memOffset, buffer);
        return;
      case StoredFormatIds.TIME_TYPE_ID:
        SQLTime.writeAsString(unsafe, memOffset, buffer);
        return;
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        SQLTimestamp.writeAsString(unsafe, memOffset, buffer);
        return;
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        buffer.writeBytes(Boolean.toString(SQLBoolean.getAsBoolean(unsafe, memOffset)));
        return;
      case StoredFormatIds.TINYINT_TYPE_ID:
        toString(SQLTinyint.getAsByte(unsafe, memOffset), buffer);
        return;

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        // first append '\x' as PostgreSQL requires
        buffer.write('\\');
        buffer.write('x');
        toHexString(unsafe, memOffset, columnWidth, buffer);
        return;

      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        final String result = dvd.getString();
        if (result != null) {
          // we don't expect non-ASCII characters except in *CHAR/CLOB/XML
          // types that have already been taken care of above
          buffer.writeBytes(result);
        }
        return;
    }
  }

  /**
   * Extract the given column value from raw bytes and set directly into the
   * given DVD.
   */
  public static final void setInDVD(final DataValueDescriptor dvd,
      final byte[] inBytes, final int offset, final int columnWidth,
      final DataTypeDescriptor dtd) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        dvd.setValue(SQLChar.getAsString(inBytes, offset, columnWidth, dtd));
        break;
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        dvd.setValue(SQLVarchar.getAsString(inBytes, offset, columnWidth));
        break;
      case StoredFormatIds.INT_TYPE_ID:
        dvd.setValue(SQLInteger.getAsInteger(inBytes, offset));
        break;
      case StoredFormatIds.DECIMAL_TYPE_ID:
        dvd.setBigDecimal(SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth));
        break;
      case StoredFormatIds.LONGINT_TYPE_ID:
        dvd.setValue(SQLLongint.getAsLong(inBytes, offset));
        break;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        dvd.setValue(SQLDouble.getAsDouble(inBytes, offset));
        break;
      case StoredFormatIds.REAL_TYPE_ID:
        dvd.setValue(SQLReal.getAsFloat(inBytes, offset));
        break;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        dvd.setValue(SQLSmallint.getAsShort(inBytes, offset));
        break;
      case StoredFormatIds.DATE_TYPE_ID:
        dvd.setValue(SQLDate.getAsDate(inBytes, offset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.TIME_TYPE_ID:
        dvd.setValue(SQLTime.getAsTime(inBytes, offset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        dvd.setValue(SQLTimestamp.getAsTimeStamp(inBytes, offset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        dvd.setValue(SQLBoolean.getAsBoolean(inBytes, offset));
        break;
      case StoredFormatIds.TINYINT_TYPE_ID:
        dvd.setValue(SQLTinyint.getAsByte(inBytes, offset));
        break;

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        dvd.setValue(SQLBinary.getAsBytes(inBytes, offset, columnWidth));
        break;
      case StoredFormatIds.XML_TYPE_ID:
        dvd.setValue(XML.getAsString(inBytes, offset, columnWidth));
        break;
      default:
        final DataValueDescriptor dvd2 = dtd.getNull();
        dvd2.readBytes(inBytes, offset, columnWidth);
        final Object result = dvd2.getObject();
        if (result != null) {
          dvd.setValue(result);
        }
        else {
          dvd.restoreToNull();
        }
        break;
    }
  }

  /**
   * Extract the given column value from raw bytes and set directly into the
   * given DVD.
   */
  public static final void setInDVD(final DataValueDescriptor dvd,
      final UnsafeWrapper unsafe, final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        dvd.setValue(SQLChar.getAsString(unsafe, memOffset, columnWidth, bs,
            dtd));
        break;
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        dvd.setValue(SQLVarchar.getAsString(unsafe, memOffset, columnWidth, bs));
        break;
      case StoredFormatIds.INT_TYPE_ID:
        dvd.setValue(SQLInteger.getAsInteger(unsafe, memOffset));
        break;
      case StoredFormatIds.DECIMAL_TYPE_ID:
        dvd.setBigDecimal(SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth));
        break;
      case StoredFormatIds.LONGINT_TYPE_ID:
        dvd.setValue(SQLLongint.getAsLong(unsafe, memOffset));
        break;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        dvd.setValue(SQLDouble.getAsDouble(unsafe, memOffset));
        break;
      case StoredFormatIds.REAL_TYPE_ID:
        dvd.setValue(SQLReal.getAsFloat(unsafe, memOffset));
        break;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        dvd.setValue(SQLSmallint.getAsShort(unsafe, memOffset));
        break;
      case StoredFormatIds.DATE_TYPE_ID:
        dvd.setValue(SQLDate.getAsDate(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.TIME_TYPE_ID:
        dvd.setValue(SQLTime.getAsTime(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        dvd.setValue(SQLTimestamp.getAsTimeStamp(unsafe, memOffset,
            ClientSharedData.getDefaultCalendar()));
        break;
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        dvd.setValue(SQLBoolean.getAsBoolean(unsafe, memOffset));
        break;
      case StoredFormatIds.TINYINT_TYPE_ID:
        dvd.setValue(SQLTinyint.getAsByte(unsafe, memOffset));
        break;

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        dvd.setValue(SQLBinary.getAsBytes(unsafe, memOffset, columnWidth));
        break;
      case StoredFormatIds.XML_TYPE_ID:
        dvd.setValue(XML.getAsString(unsafe, memOffset, columnWidth, bs));
        break;
      default:
        final DataValueDescriptor dvd2 = dtd.getNull();
        dvd2.readBytes(unsafe, memOffset, columnWidth, bs);
        final Object result = dvd2.getObject();
        if (result != null) {
          dvd.setValue(result);
        }
        else {
          dvd.restoreToNull();
        }
        break;
    }
  }

  public static final boolean getAsBoolean(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return SQLBoolean.getAsBoolean(inBytes, offset);
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        return value != 0.0;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(inBytes, offset);
        return value != 0;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getBoolean(bd);
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(inBytes, offset);
        return lv != 0;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        return flt != 0.0f;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = SQLSmallint.getAsShort(inBytes, offset);
        return shrt != 0;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          return !(str.equals("0") || str.equals("false"));
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          return !(str.equals("0") || str.equals("false"));
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getBoolean();
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
    }
  }

  public static final boolean getAsBoolean(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return SQLBoolean.getAsBoolean(unsafe, memOffset);
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        return value != 0.0;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(unsafe, memOffset);
        return value != 0;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getBoolean(bd);
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(unsafe, memOffset);
        return lv != 0;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(unsafe, memOffset);
        return flt != 0.0f;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = SQLSmallint.getAsShort(unsafe, memOffset);
        return shrt != 0;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          return !(str.equals("0") || str.equals("false"));
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          return !(str.equals("0") || str.equals("false"));
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getBoolean();
        }
        else {
          wasNull.setWasNull();
          return false;
        }
      }
    }
  }

  public static final byte getAsByte(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        if ((value > (Byte.MAX_VALUE + 1.0d))
            || (value < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)value;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(inBytes, offset);
        if (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE) {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        }
        return (byte)value;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Byte.MIN_VALUE) && (lv <= Byte.MAX_VALUE))
            return (byte)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return (byte)0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(inBytes, offset);
        if (lv > Byte.MAX_VALUE || lv < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());

        return (byte)lv;

      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        if ((flt > ((Byte.MAX_VALUE + 1.0d)))
            || (flt < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = SQLSmallint.getAsShort(inBytes, offset);
        if (shrt > Byte.MAX_VALUE || shrt < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)shrt;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Byte.parseByte(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Byte.parseByte(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getByte();
        }
        else {
          wasNull.setWasNull();
          return (byte)0;
        }
      }
    }
  }

  public static final byte getAsByte(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        if ((value > (Byte.MAX_VALUE + 1.0d))
            || (value < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)value;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(unsafe, memOffset);
        if (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE) {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        }
        return (byte)value;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Byte.MIN_VALUE) && (lv <= Byte.MAX_VALUE))
            return (byte)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return (byte)0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(unsafe, memOffset);
        if (lv > Byte.MAX_VALUE || lv < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());

        return (byte)lv;

      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(unsafe, memOffset);
        if ((flt > ((Byte.MAX_VALUE + 1.0d)))
            || (flt < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = SQLSmallint.getAsShort(unsafe, memOffset);
        if (shrt > Byte.MAX_VALUE || shrt < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)shrt;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Byte.parseByte(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Byte.parseByte(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getByte();
        }
        else {
          wasNull.setWasNull();
          return (byte)0;
        }
      }
    }
  }

  // TODO: SW: for both LANG_OUTSIDE_RANGE, LANG_FORMAT errors, should also
  // have the culprit value in exception message
  public static final short getAsShort(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        if ((value > (Short.MAX_VALUE + 1.0d))
            || (value < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(inBytes, offset);
        if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;

      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Short.MIN_VALUE) && (lv <= Short.MAX_VALUE))
            return (short)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return (short)0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(inBytes, offset);
        if (lv > Short.MAX_VALUE || lv < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        if ((flt > ((Short.MAX_VALUE + 1.0d)))
            || (flt < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Short.parseShort(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Short.parseShort(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getShort();
        }
        else {
          wasNull.setWasNull();
          return (short)0;
        }
      }
    }
  }

  public static final short getAsShort(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        if ((value > (Short.MAX_VALUE + 1.0d))
            || (value < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        final int value = SQLInteger.getAsInteger(unsafe, memOffset);
        if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;

      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Short.MIN_VALUE) && (lv <= Short.MAX_VALUE))
            return (short)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return (short)0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(unsafe, memOffset);
        if (lv > Short.MAX_VALUE || lv < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(unsafe, memOffset);
        if ((flt > ((Short.MAX_VALUE + 1.0d)))
            || (flt < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(unsafe, memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Short.parseShort(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Short.parseShort(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getShort();
        }
        else {
          wasNull.setWasNull();
          return (short)0;
        }
      }
    }
  }

  public static final int getAsInt(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        if ((value > (Integer.MAX_VALUE + 1.0d))
            || (value < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return SQLInteger.getAsInteger(inBytes, offset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Integer.MIN_VALUE) && (lv <= Integer.MAX_VALUE))
            return (int)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(inBytes, offset);
        if (lv > Integer.MAX_VALUE || lv < Integer.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        if ((flt > ((Integer.MAX_VALUE + 1.0d)))
            || (flt < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Integer.parseInt(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Integer.parseInt(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getInt();
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  public static final int getAsInt(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd, final ResultWasNull wasNull)
      throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        if ((value > (Integer.MAX_VALUE + 1.0d))
            || (value < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return SQLInteger.getAsInteger(unsafe, memOffset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          final long lv = SQLDecimal.getLong(bd);
          if ((lv >= Integer.MIN_VALUE) && (lv <= Integer.MAX_VALUE))
            return (int)lv;
          else {
            throw StandardException.newException(
                SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = SQLLongint.getAsLong(unsafe, memOffset);
        if (lv > Integer.MAX_VALUE || lv < Integer.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(unsafe, memOffset);
        if ((flt > ((Integer.MAX_VALUE + 1.0d)))
            || (flt < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(unsafe, memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Integer.parseInt(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Integer.parseInt(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getInt();
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  public static final long getAsLong(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        if ((value > (Long.MAX_VALUE + 1.0d))
            || (value < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return SQLInteger.getAsInteger(inBytes, offset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getLong(bd);
        }
        else {
          wasNull.setWasNull();
          return 0l;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        return SQLLongint.getAsLong(inBytes, offset);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        if ((flt > ((Long.MAX_VALUE + 1.0d)))
            || (flt < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Long.parseLong(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Long.parseLong(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getLong();
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  public static final long getAsLong(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd, final ResultWasNull wasNull)
      throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        if ((value > (Long.MAX_VALUE + 1.0d))
            || (value < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return SQLInteger.getAsInteger(unsafe, memOffset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getLong(bd);
        }
        else {
          wasNull.setWasNull();
          return 0l;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        return SQLLongint.getAsLong(unsafe, memOffset);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(unsafe, memOffset);
        if ((flt > ((Long.MAX_VALUE + 1.0d)))
            || (flt < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(unsafe, memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Long.parseLong(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Long.parseLong(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getLong();
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  public static final float getAsFloat(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        if (Float.isInfinite((float)value))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, TypeId.REAL_NAME,
              cd.getColumnName());
        return (float)value;
      }
      case StoredFormatIds.INT_TYPE_ID:
        return SQLInteger.getAsInteger(inBytes, offset);
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal localValue = SQLDecimal.getAsBigDecimal(inBytes,
            offset, columnWidth);
        if (localValue != null) {
          return NumberDataType.normalizeREAL(localValue.floatValue());
        }
        else {
          wasNull.setWasNull();
          return 0.0f;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return SQLLongint.getAsLong(inBytes, offset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(inBytes, offset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Float.parseFloat(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Float.parseFloat(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getFloat();
        }
        else {
          wasNull.setWasNull();
          return 0.0f;
        }
      }
    }
  }

  public static final float getAsFloat(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd, final ResultWasNull wasNull)
      throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(unsafe, memOffset);
        if (Float.isInfinite((float)value))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, TypeId.REAL_NAME,
              cd.getColumnName());
        return (float)value;
      }
      case StoredFormatIds.INT_TYPE_ID:
        return SQLInteger.getAsInteger(unsafe, memOffset);
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal localValue = SQLDecimal.getAsBigDecimal(unsafe,
            memOffset, columnWidth);
        if (localValue != null) {
          return NumberDataType.normalizeREAL(localValue.floatValue());
        }
        else {
          wasNull.setWasNull();
          return 0.0f;
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return SQLLongint.getAsLong(unsafe, memOffset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(unsafe, memOffset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(unsafe, memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Float.parseFloat(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.REAL_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Float.parseFloat(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.REAL_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getFloat();
        }
        else {
          wasNull.setWasNull();
          return 0.0f;
        }
      }
    }
  }

  public static final double getAsDouble(final byte[] inBytes,
      final int offset, final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(inBytes, offset);
      case StoredFormatIds.INT_TYPE_ID:
        return SQLInteger.getAsInteger(inBytes, offset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getDouble(bd);
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return SQLLongint.getAsLong(inBytes, offset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(inBytes, offset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Double.parseDouble(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        if (str != null) {
          try {
            return Double.parseDouble(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
      }
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getDouble();
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
    }
  }

  public static final double getAsDouble(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd, final ResultWasNull wasNull)
      throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(unsafe, memOffset);
      case StoredFormatIds.INT_TYPE_ID:
        return SQLInteger.getAsInteger(unsafe, memOffset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
            columnWidth);
        if (bd != null) {
          return SQLDecimal.getDouble(bd);
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return SQLLongint.getAsLong(unsafe, memOffset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(unsafe, memOffset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return SQLSmallint.getAsShort(unsafe, memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(unsafe, memOffset, columnWidth,
            bs, dtd).trim();
        if (str != null) {
          try {
            return Double.parseDouble(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(unsafe, memOffset,
            columnWidth, bs, dtd).trim();
        if (str != null) {
          try {
            return Double.parseDouble(str);
          } catch (NumberFormatException nfe) {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
                cd.getColumnName());
          }
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
      }
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getDouble();
        }
        else {
          wasNull.setWasNull();
          return 0.0;
        }
    }
  }

  public static final BigDecimal getAsBigDecimal(final byte[] inBytes,
      final int offset, final int columnWidth, final ColumnDescriptor cd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    try {
      switch (formatID) {
        case StoredFormatIds.DECIMAL_TYPE_ID: {
          final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
              columnWidth);
          if (bd != null) {
            return bd;
          }
          else {
            wasNull.setWasNull();
            return null;
          }
        }
        case StoredFormatIds.DOUBLE_TYPE_ID: {
          final double value = SQLDouble.getAsDouble(inBytes, offset);
          return BigDecimal.valueOf(value);
        }
        case StoredFormatIds.REAL_TYPE_ID: {
          final float flt = SQLReal.getAsFloat(inBytes, offset);
          return new BigDecimal(Float.toString(flt));
        }
        case StoredFormatIds.INT_TYPE_ID: {
          final int v = SQLInteger.getAsInteger(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.LONGINT_TYPE_ID: {
          final long v = SQLLongint.getAsLong(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.SMALLINT_TYPE_ID: {
          final short v = SQLSmallint.getAsShort(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.CHAR_TYPE_ID: {
          final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
              dtd);
          if (str != null) {
            return new BigDecimal(str);
          }
          else {
            wasNull.setWasNull();
            return BigDecimal.ZERO;
          }
        }
        case StoredFormatIds.LONGVARCHAR_TYPE_ID:
        case StoredFormatIds.VARCHAR_TYPE_ID:
          String str = SQLVarchar.getAsString(inBytes, offset, columnWidth);
          if (str != null) {
            return new BigDecimal(str);
          }
          else {
            wasNull.setWasNull();
            return BigDecimal.ZERO;
          }
        default: {
          final DataValueDescriptor dvd = dtd.getNull();
          dvd.readBytes(inBytes, offset, columnWidth);
          if (!dvd.isNull()) {
            return SQLDecimal.getBigDecimal(dvd);
          }
          else {
            wasNull.setWasNull();
            return null;
          }
        }
      }
    } catch (NumberFormatException nfe) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          "java.math.BigDecimal", cd.getColumnName());
    }
  }

  public static final BigDecimal getAsBigDecimal(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd, final ResultWasNull wasNull)
      throws StandardException {

    assert wasNull != null;
    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    try {
      switch (formatID) {
        case StoredFormatIds.DECIMAL_TYPE_ID: {
          final BigDecimal bd = SQLDecimal.getAsBigDecimal(unsafe, memOffset,
              columnWidth);
          if (bd != null) {
            return bd;
          }
          else {
            wasNull.setWasNull();
            return null;
          }
        }
        case StoredFormatIds.DOUBLE_TYPE_ID: {
          final double value = SQLDouble.getAsDouble(unsafe, memOffset);
          return BigDecimal.valueOf(value);
        }
        case StoredFormatIds.REAL_TYPE_ID: {
          final float flt = SQLReal.getAsFloat(unsafe, memOffset);
          return new BigDecimal(Float.toString(flt));
        }
        case StoredFormatIds.INT_TYPE_ID: {
          final int v = SQLInteger.getAsInteger(unsafe, memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.LONGINT_TYPE_ID: {
          final long v = SQLLongint.getAsLong(unsafe, memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.SMALLINT_TYPE_ID: {
          final short v = SQLSmallint.getAsShort(unsafe, memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.CHAR_TYPE_ID: {
          final String str = SQLChar.getAsString(unsafe, memOffset,
              columnWidth, bs, dtd);
          if (str != null) {
            return new BigDecimal(str);
          }
          else {
            wasNull.setWasNull();
            return BigDecimal.ZERO;
          }
        }
        case StoredFormatIds.LONGVARCHAR_TYPE_ID:
        case StoredFormatIds.VARCHAR_TYPE_ID:
          String str = SQLVarchar.getAsString(unsafe, memOffset, columnWidth,
              bs);
          if (str != null) {
            return new BigDecimal(str);
          }
          else {
            wasNull.setWasNull();
            return BigDecimal.ZERO;
          }
        default: {
          final DataValueDescriptor dvd = dtd.getNull();
          dvd.readBytes(unsafe, memOffset, columnWidth, bs);
          if (!dvd.isNull()) {
            return SQLDecimal.getBigDecimal(dvd);
          }
          else {
            wasNull.setWasNull();
            return null;
          }
        }
      }
    } catch (NumberFormatException nfe) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          "java.math.BigDecimal", cd.getColumnName());
    }
  }

  public static final byte[] getAsBytes(final byte[] inBytes, final int offset,
      final int columnWidth, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID: {
        // byte[]s are immutable in GemFireXD, so avoid copy if not required
        if (offset == 0 && columnWidth == inBytes.length) {
          return inBytes;
        }
        else {
          // TODO:Asif: avoid the extra byte array created
          final byte[] bytes = new byte[columnWidth];
          System.arraycopy(inBytes, offset, bytes, 0, columnWidth);
          return bytes;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getBytes();
        }
        else {
          wasNull.setWasNull();
          return null;
        }
      }
    }
  }

  public static final byte[] getAsBytes(final UnsafeWrapper unsafe,
      final long memAddr, final int addrOffset, final int columnWidth,
      final OffHeapByteSource bs, final DataTypeDescriptor dtd,
      final ResultWasNull wasNull) throws StandardException {

    assert wasNull != null;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID: {
        // off-heap always has to create a new byte[] so avoid extra calls of
        // getLength by just calling readBytes
        final byte[] bytes = new byte[columnWidth];
        UnsafeMemoryChunk.readAbsoluteBytes(memAddr, addrOffset, bytes, 0,
            columnWidth);
        return bytes;
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memAddr + addrOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getBytes();
        }
        else {
          wasNull.setWasNull();
          return null;
        }
      }
    }
  }

  public static final java.sql.Date getAsDate(final byte[] inBytes,
      final int offset, final int columnWidth, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDate(inBytes, offset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsDate(inBytes, offset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getDate(cal);
        }
      }
    }
  }

  public static final java.sql.Date getAsDate(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final Calendar cal, final DataTypeDescriptor dtd)
      throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDate(unsafe, memOffset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsDate(unsafe, memOffset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getDate(cal);
        }
      }
    }
  }

  public static final java.sql.Timestamp getAsTimestamp(final byte[] inBytes,
      final int offset, final int columnWidth, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsTimeStamp(inBytes, offset, cal);
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTimestamp(inBytes, offset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStamp(inBytes, offset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTimestamp(cal);
        }
      }
    }
  }

  public static final java.sql.Timestamp getAsTimestamp(
      final UnsafeWrapper unsafe, final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsTimeStamp(unsafe, memOffset, cal);
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTimestamp(unsafe, memOffset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStamp(unsafe, memOffset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTimestamp(cal);
        }
      }
    }
  }

  public static final java.sql.Time getAsTime(final byte[] inBytes,
      final int offset, final int columnWidth, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTime(inBytes, offset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTime(inBytes, offset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTime(cal);
        }
      }
    }
  }

  public static final java.sql.Time getAsTime(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final Calendar cal, final DataTypeDescriptor dtd)
      throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.TIME_TYPE_ID:
        return SQLTime.getAsTime(unsafe, memOffset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTime(unsafe, memOffset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(unsafe, memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTime(cal);
        }
      }
    }
  }

  public static final int NULL_MAX = Integer.MAX_VALUE >>> 1;
  public static final int NULL_MIN = -NULL_MAX;

  /**
   * Compare two columns (inside byte arrays or off-heap byte sources). Null
   * handling is done.
   */
  public static final int compare(final UnsafeWrapper unsafe, byte[] lhsBytes,
      long lhsMemAddr, OffHeapByteSource lhsBS, byte[] rhsBytes,
      long rhsMemAddr, OffHeapByteSource rhsBS, long lhsOffsetWidth,
      long rhsOffsetWidth, final boolean nullsOrderedLow,
      final boolean caseSensitive, final ColumnDescriptor cd)
      throws StandardException {

    if (lhsBS == null && rhsBS == null) {
      return compare(lhsBytes, rhsBytes, lhsOffsetWidth, rhsOffsetWidth,
          nullsOrderedLow, caseSensitive, cd);
    }

    // check for default values that are null first
    if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      lhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      rhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
    /*
     * null compare semantics if both null, then equal. if lhs null but rhs
     * non-null, then +ve if lhs non null but rhs null, then -ve.
     */
    if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
        return 0; // By convention, nulls sort High, and null == null
      } else {
        // indicates null
        return nullsOrderedLow ? NULL_MIN : NULL_MAX;
        // return nullsOrderedLow ? -1 : 1; // indicates null
      }
    } else if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // indicates null
      return nullsOrderedLow ? NULL_MAX : NULL_MIN;
      // return nullsOrderedLow ? 1 : -1; // indicates null
    }

    final DataTypeDescriptor dtd = cd.getType();
    final TypeId typeId = dtd.getTypeId();
    final int formatID = typeId.getTypeFormatId();

    int lhsColumnWidth = (int) (lhsOffsetWidth & 0xFFFFFFFF);
    int lhsOffset = (int) (lhsOffsetWidth >>> Integer.SIZE);

    int rhsColumnWidth;
    int rhsOffset;

    // check for default value
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
      if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
        // both default
        return 0;
      }
      // use the byte form of column default
      rhsBytes = cd.columnDefaultBytes;
      rhsMemAddr = 0;
      rhsBS = null;
      rhsOffset = 0;
      rhsColumnWidth = rhsBytes != null ? rhsBytes.length : 0;
    }
    else {
      if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
        lhsBytes = cd.columnDefaultBytes;
        lhsMemAddr = 0;
        lhsBS = null;
        lhsOffset = 0;
        lhsColumnWidth = lhsBytes != null ? lhsBytes.length : 0;
      }
      rhsColumnWidth = (int) (rhsOffsetWidth & 0xFFFFFFFF);
      rhsOffset = (int) (rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsMemAddr != 0 ? SQLSmallint.getAsShort(unsafe,
            lhsMemAddr + lhsOffset) : SQLSmallint.getAsShort(lhsBytes,
            lhsOffset);
        final int rhsV = rhsMemAddr != 0 ? SQLSmallint.getAsShort(unsafe,
            rhsMemAddr + rhsOffset) : SQLSmallint.getAsShort(rhsBytes,
            rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsMemAddr != 0 ? SQLInteger.getAsInteger(unsafe,
            lhsMemAddr + lhsOffset) : SQLInteger.getAsInteger(lhsBytes,
            lhsOffset);
        final long rhsV = rhsMemAddr != 0 ? SQLInteger.getAsInteger(unsafe,
            rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsMemAddr != 0 ? SQLLongint.getAsLong(unsafe,
            lhsMemAddr + lhsOffset) : SQLLongint.getAsLong(lhsBytes, lhsOffset);
        final long rhsV = rhsMemAddr != 0 ? SQLLongint.getAsLong(unsafe,
            rhsMemAddr + rhsOffset) : SQLLongint.getAsLong(rhsBytes, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = lhsMemAddr != 0 ? SQLDouble.getAsDouble(unsafe,
            lhsMemAddr + lhsOffset) : SQLDouble
            .getAsDouble(lhsBytes, lhsOffset);
        final double rhsV = rhsMemAddr != 0 ? SQLDouble.getAsDouble(unsafe,
            rhsMemAddr + rhsOffset) : SQLDouble
            .getAsDouble(rhsBytes, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = lhsMemAddr != 0 ? SQLReal.getAsFloat(unsafe,
            lhsMemAddr + lhsOffset) : SQLReal.getAsFloat(lhsBytes, lhsOffset);
        final float rhsV = rhsMemAddr != 0 ? SQLReal.getAsFloat(unsafe,
            rhsMemAddr + rhsOffset) : SQLReal.getAsFloat(rhsBytes, rhsOffset);
        return Float.compare(lhsV, rhsV);
      }

      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID: {
        if (caseSensitive) {
          if (lhsBS != null) {
            if (rhsBS != null) {
              return SQLChar.compareString(unsafe, lhsMemAddr + lhsOffset,
                  lhsColumnWidth, lhsBS, rhsMemAddr + rhsOffset,
                  rhsColumnWidth, rhsBS);
            }
            else {
              return -Integer.signum(SQLChar.compareString(unsafe, rhsBytes,
                  rhsOffset, rhsColumnWidth, lhsMemAddr + lhsOffset,
                  lhsColumnWidth, lhsBS));
            }
          }
          else if (rhsBS != null) {
            return SQLChar.compareString(unsafe, lhsBytes, lhsOffset,
                lhsColumnWidth, rhsMemAddr + rhsOffset, rhsColumnWidth, rhsBS);
          }
          else {
            return SQLChar.compareString(lhsBytes, lhsOffset, lhsColumnWidth,
                rhsBytes, rhsOffset, rhsColumnWidth);
          }
        }
        else if (lhsBS != null) {
          if (rhsBS != null) {
            return SQLChar.compareStringIgnoreCase(unsafe, lhsMemAddr
                + lhsOffset, lhsColumnWidth, lhsBS, rhsMemAddr + rhsOffset,
                rhsColumnWidth, rhsBS);
          }
          else {
            return -Integer.signum(SQLChar.compareStringIgnoreCase(unsafe,
                rhsBytes, rhsOffset, rhsColumnWidth, lhsMemAddr + lhsOffset,
                lhsColumnWidth, lhsBS));
          }
        }
        else if (rhsBS != null) {
          return SQLChar.compareStringIgnoreCase(unsafe, lhsBytes, lhsOffset,
              lhsColumnWidth, rhsMemAddr + rhsOffset, rhsColumnWidth, rhsBS);
        }
        else {
          return SQLChar.compareStringIgnoreCase(lhsBytes, lhsOffset,
              lhsColumnWidth, rhsBytes, rhsOffset, rhsColumnWidth);
        }
      }

      case StoredFormatIds.DECIMAL_TYPE_ID: {
        // TODO: PERF: optimize to use byte[]s directly?
        final BigDecimal lhsV = lhsMemAddr != 0 ? SQLDecimal.getAsBigDecimal(
            unsafe, lhsMemAddr + lhsOffset, lhsColumnWidth) : SQLDecimal
            .getAsBigDecimal(lhsBytes, lhsOffset, lhsColumnWidth);
        final BigDecimal rhsV = rhsMemAddr != 0 ? SQLDecimal.getAsBigDecimal(
            unsafe, rhsMemAddr + rhsOffset, rhsColumnWidth) : SQLDecimal
            .getAsBigDecimal(rhsBytes, rhsOffset, rhsColumnWidth);

        if (lhsV != null) {
          if (rhsV != null) {
            return lhsV.compareTo(rhsV);
          }
          else {
            return 1;
          }
        }
        else {
          return rhsV == null ? 0 : -1;
        }
      }

      case StoredFormatIds.DATE_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedDate = lhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, lhsMemAddr + lhsOffset) : SQLInteger.getAsInteger(lhsBytes,
            lhsOffset);
        final long rhsEncodedDate = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);
        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = lhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, lhsMemAddr + lhsOffset) : SQLInteger.getAsInteger(lhsBytes,
            lhsOffset);
        final long rhsEncodedTime = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final long lhsEncodedDate = lhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, lhsMemAddr + lhsOffset) : SQLInteger.getAsInteger(lhsBytes,
            lhsOffset);
        final long rhsEncodedDate = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);

        if (lhsEncodedDate < rhsEncodedDate) {
          return -1;
        }
        else if (lhsEncodedDate > rhsEncodedDate) {
          return 1;
        }
        else {
          lhsOffset += GemFireXDUtils.IntegerBytesLen;
          rhsOffset += GemFireXDUtils.IntegerBytesLen;
          final long lhsEncodedTime = lhsMemAddr != 0 ? SQLInteger
              .getAsInteger(unsafe, lhsMemAddr + lhsOffset) : SQLInteger
              .getAsInteger(lhsBytes, lhsOffset);
          final long rhsEncodedTime = rhsMemAddr != 0 ? SQLInteger
              .getAsInteger(unsafe, rhsMemAddr + rhsOffset) : SQLInteger
              .getAsInteger(rhsBytes, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            lhsOffset += GemFireXDUtils.IntegerBytesLen;
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = lhsMemAddr != 0 ? SQLInteger.getAsInteger(
                unsafe, lhsMemAddr + lhsOffset) : SQLInteger.getAsInteger(
                lhsBytes, lhsOffset);
            final long rhsNanos = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
                unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(
                rhsBytes, rhsOffset);

            return (int)(lhsNanos - rhsNanos);
          }
        }
      }
      case StoredFormatIds.BOOLEAN_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID: {
        final int lhsV = lhsMemAddr != 0 ? SQLTinyint.getAsByte(unsafe,
            lhsMemAddr + lhsOffset) : SQLTinyint.getAsByte(lhsBytes, lhsOffset);
        final int rhsV = rhsMemAddr != 0 ? SQLTinyint.getAsByte(unsafe,
            rhsMemAddr + rhsOffset) : SQLTinyint.getAsByte(rhsBytes, rhsOffset);
        return (lhsV - rhsV);
      }

      default:
        DataValueDescriptor lhsDVD = dtd.getNull();
        if (lhsBS != null) {
          lhsDVD.readBytes(unsafe, lhsMemAddr + lhsOffset, lhsColumnWidth,
              lhsBS);
        }
        else {
          lhsDVD.readBytes(lhsBytes, lhsOffset, lhsColumnWidth);
        }
        DataValueDescriptor rhsDVD = dtd.getNull();
        if (rhsBS != null) {
          rhsDVD.readBytes(unsafe, rhsMemAddr + rhsOffset, rhsColumnWidth,
              rhsBS);
        }
        else {
          rhsDVD.readBytes(rhsBytes, rhsOffset, rhsColumnWidth);
        }

        return lhsDVD.compare(rhsDVD);
    }
  }

  /**
   * Compare two columns (part of byte arrays or off-heap byte sources or left
   * side a DVD). Null handling is done.
   */
  public static final int compare(final UnsafeWrapper unsafe,
      DataValueDescriptor lhsDVD, byte[] rhsBytes, long rhsMemAddr,
      OffHeapByteSource rhsBS, long rhsOffsetWidth,
      final boolean nullsOrderedLow, final boolean caseSensitive,
      final ColumnDescriptor cd) throws StandardException {

    boolean lhsIsNull = false;
    // lhsDVD maybe a BinarySQLHybridType
    if (lhsDVD.getClass() == BinarySQLHybridType.class) {
      final BinarySQLHybridType bt = (BinarySQLHybridType)lhsDVD;
      lhsDVD = bt.sqlValue;
      if (lhsDVD == null) {
        final byte[] lhsBytes = bt.byteValue;
        final long lhsOffsetWidth;
        if (lhsBytes != null) {
          lhsOffsetWidth = lhsBytes.length;
        }
        else {
          lhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
        }
        return compare(unsafe, lhsBytes, 0, null, rhsBytes, rhsMemAddr, rhsBS,
            lhsOffsetWidth, rhsOffsetWidth, nullsOrderedLow, caseSensitive, cd);
      }
      else if (lhsDVD.isNull()) {
        lhsIsNull = true;
      }
    }
    else if (lhsDVD.isNull()) {
      lhsIsNull = true;
    }

    // check for default values that are null first
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      rhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }

    /*
     * null compare semantics if both null, then equal. if lhs null but rhs
     * non-null, then +ve if lhs non null but rhs null, then -ve.
     */
    if (lhsIsNull) {
      if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
        return 0; // By convention, nulls sort High, and null == null
      } else {
        // indicates null
        return nullsOrderedLow ? NULL_MIN : NULL_MAX;
        // return nullsOrderedLow ? -1 : 1; // indicates null
      }
    } else if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // indicates null
      return nullsOrderedLow ? NULL_MAX : NULL_MIN;
      // return nullsOrderedLow ? 1 : -1; // indicates null
    }

    final DataTypeDescriptor dtd = cd.getType();
    final TypeId typeId = dtd.getTypeId();
    final int formatID = typeId.getTypeFormatId();

    int rhsColumnWidth;
    int rhsOffset;

    // check for default value
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
      // use the byte form of column default
      rhsBytes = cd.columnDefaultBytes;
      rhsMemAddr = 0;
      rhsBS = null;
      rhsOffset = 0;
      rhsColumnWidth = rhsBytes != null ? rhsBytes.length : 0;
    }
    else {
      rhsColumnWidth = (int)(rhsOffsetWidth & 0xFFFFFFFF);
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsDVD.getShort();
        final int rhsV = rhsMemAddr != 0 ? SQLSmallint.getAsShort(unsafe,
            rhsMemAddr + rhsOffset) : SQLSmallint.getAsShort(rhsBytes,
            rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsDVD.getInt();
        final long rhsV = rhsMemAddr != 0 ? SQLInteger.getAsInteger(unsafe,
            rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsDVD.getLong();
        final long rhsV = rhsMemAddr != 0 ? SQLLongint.getAsLong(unsafe,
            rhsMemAddr + rhsOffset) : SQLLongint.getAsLong(rhsBytes, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = lhsDVD.getDouble();
        final double rhsV = rhsMemAddr != 0 ? SQLDouble.getAsDouble(unsafe,
            rhsMemAddr + rhsOffset) : SQLDouble
            .getAsDouble(rhsBytes, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = lhsDVD.getFloat();
        final float rhsV = rhsMemAddr != 0 ? SQLReal.getAsFloat(unsafe,
            rhsMemAddr + rhsOffset) : SQLReal.getAsFloat(rhsBytes, rhsOffset);
        return Float.compare(lhsV, rhsV);
      }

      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID: {
        final String lhsStr = lhsDVD.getString();
        if (caseSensitive) {
          if (rhsBS != null) {
            return SQLChar.compareString(unsafe, lhsStr,
                rhsMemAddr + rhsOffset, rhsColumnWidth, rhsBS);
          }
          else {
            return SQLChar.compareString(lhsStr, rhsBytes, rhsOffset,
                rhsColumnWidth);
          }
        }
        else if (rhsBS != null) {
          return SQLChar.compareStringIgnoreCase(unsafe, lhsStr, rhsMemAddr
              + rhsOffset, rhsColumnWidth, rhsBS);
        }
        else {
          return SQLChar.compareStringIgnoreCase(lhsStr, rhsBytes, rhsOffset,
              rhsColumnWidth);
        }
      }

      case StoredFormatIds.DECIMAL_TYPE_ID: {
        // TODO: PERF: optimize to use byte[]s directly?
        final BigDecimal lhsV = (BigDecimal)lhsDVD.getObject();
        final BigDecimal rhsV = rhsMemAddr != 0 ? SQLDecimal.getAsBigDecimal(
            unsafe, rhsMemAddr + rhsOffset, rhsColumnWidth) : SQLDecimal
            .getAsBigDecimal(rhsBytes, rhsOffset, rhsColumnWidth);

        if (lhsV != null) {
          if (rhsV != null) {
            return lhsV.compareTo(rhsV);
          }
          else {
            return 1;
          }
        }
        else {
          return rhsV == null ? 0 : -1;
        }
      }

      case StoredFormatIds.DATE_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedDate = ((DateTimeDataValue)lhsDVD)
            .getEncodedDate();
        final long rhsEncodedDate = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);
        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = ((DateTimeDataValue)lhsDVD)
            .getEncodedTime();
        final long rhsEncodedTime = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final DateTimeDataValue ts = (DateTimeDataValue)lhsDVD;
        final int lhsEncodedDate = ts.getEncodedDate();
        final long rhsEncodedDate = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
            unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(rhsBytes,
            rhsOffset);

        if (lhsEncodedDate < rhsEncodedDate) {
          return -1;
        }
        else if (lhsEncodedDate > rhsEncodedDate) {
          return 1;
        }
        else {
          rhsOffset += GemFireXDUtils.IntegerBytesLen;
          final int lhsEncodedTime = ts.getEncodedTime();
          final long rhsEncodedTime = rhsMemAddr != 0 ? SQLInteger
              .getAsInteger(unsafe, rhsMemAddr + rhsOffset) : SQLInteger
              .getAsInteger(rhsBytes, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = ts.getNanos();
            final long rhsNanos = rhsMemAddr != 0 ? SQLInteger.getAsInteger(
                unsafe, rhsMemAddr + rhsOffset) : SQLInteger.getAsInteger(
                rhsBytes, rhsOffset);

            return (int)(lhsNanos - rhsNanos);
          }
        }
      }
      case StoredFormatIds.BOOLEAN_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID: {
        final int lhsV = lhsDVD.getByte();
        final int rhsV = rhsMemAddr != 0 ? SQLTinyint.getAsByte(unsafe,
            rhsMemAddr + rhsOffset) : SQLTinyint.getAsByte(rhsBytes, rhsOffset);
        return (lhsV - rhsV);
      }

      default:
        DataValueDescriptor rhsDVD = dtd.getNull();
        if (rhsBS != null) {
          rhsDVD.readBytes(unsafe, rhsMemAddr + rhsOffset, rhsColumnWidth,
              rhsBS);
        }
        else {
          rhsDVD.readBytes(rhsBytes, rhsOffset, rhsColumnWidth);
        }

        return lhsDVD.compare(rhsDVD);
    }
  }

  /**
   * Null handling is done. Caller must handle default column values.
   * 
   * @throws StandardException
   */
  public static final int compare(byte[] lhs, byte[] rhs,
      long lhsOffsetWidth, long rhsOffsetWidth,
      final boolean nullsOrderedLow, final boolean caseSensitive,
      final ColumnDescriptor cd) throws StandardException {

    // check for default values that are null first
    if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      lhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      rhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
    /*
     * null compare semantics if both null, then equal. if lhs null but rhs
     * non-null, then +ve if lhs non null but rhs null, then -ve.
     */
    if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
        return 0; // By convention, nulls sort High, and null == null
      }
      else {
        // indicates null
        return nullsOrderedLow ? NULL_MIN : NULL_MAX;
        // return nullsOrderedLow ? -1 : 1; // indicates null
      }
    }
    else if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // indicates null
      return nullsOrderedLow ? NULL_MAX : NULL_MIN;
    }

    final DataTypeDescriptor dtd = cd.getType();
    final TypeId typeId = dtd.getTypeId();
    final int formatID = typeId.getTypeFormatId();

    int lhsColumnWidth = (int)(lhsOffsetWidth & 0xFFFFFFFF);
    int lhsOffset = (int)(lhsOffsetWidth >>> Integer.SIZE);

    int rhsColumnWidth;
    int rhsOffset;

    // check for default value
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
      if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
        // both default
        return 0;
      }
      // use the byte form of column default
      rhs = cd.columnDefaultBytes;
      rhsOffset = 0;
      rhsColumnWidth = rhs.length;
    }
    else {
      if (lhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
        lhs = cd.columnDefaultBytes;
        lhsOffset = 0;
        lhsColumnWidth = lhs.length;
      }
      rhsColumnWidth = (int)(rhsOffsetWidth & 0xFFFFFFFF);
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = SQLSmallint.getAsShort(lhs, lhsOffset);
        final int rhsV = SQLSmallint.getAsShort(rhs, rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = SQLInteger.getAsInteger(lhs, lhsOffset);
        final long rhsV = SQLInteger.getAsInteger(rhs, rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = SQLLongint.getAsLong(lhs, lhsOffset);
        final long rhsV = SQLLongint.getAsLong(rhs, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = SQLDouble.getAsDouble(lhs, lhsOffset);
        final double rhsV = SQLDouble.getAsDouble(rhs, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = SQLReal.getAsFloat(lhs, lhsOffset);
        final float rhsV = SQLReal.getAsFloat(rhs, rhsOffset);
        return Float.compare(lhsV, rhsV);
      }

      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID: {
        return caseSensitive ? SQLChar.compareString(lhs, lhsOffset,
            lhsColumnWidth, rhs, rhsOffset, rhsColumnWidth) : SQLChar
            .compareStringIgnoreCase(lhs, lhsOffset, lhsColumnWidth, rhs,
                rhsOffset, rhsColumnWidth);
      }

      case StoredFormatIds.DECIMAL_TYPE_ID: {
        // TODO: PERF: optimize to use byte[]s directly?
        final BigDecimal lhsV = SQLDecimal.getAsBigDecimal(lhs, lhsOffset,
            lhsColumnWidth);
        final BigDecimal rhsV = SQLDecimal.getAsBigDecimal(rhs, rhsOffset,
            rhsColumnWidth);

        if (lhsV != null) {
          if (rhsV != null) {
            return lhsV.compareTo(rhsV);
          }
          else {
            return 1;
          }
        }
        else {
          return rhsV == null ? 0 : -1;
        }
      }

      case StoredFormatIds.DATE_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedDate = RowFormatter.readInt(lhs, lhsOffset);
        final long rhsEncodedDate = RowFormatter.readInt(rhs, rhsOffset);

        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = RowFormatter.readInt(lhs, lhsOffset);
        final long rhsEncodedTime = RowFormatter.readInt(rhs, rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final int lhsEncodedDate = RowFormatter.readInt(lhs, lhsOffset);
        final int rhsEncodedDate = RowFormatter.readInt(rhs, rhsOffset);

        if (lhsEncodedDate < rhsEncodedDate) {
          return -1;
        }
        else if (lhsEncodedDate > rhsEncodedDate) {
          return 1;
        }
        else {
          lhsOffset += GemFireXDUtils.IntegerBytesLen;
          rhsOffset += GemFireXDUtils.IntegerBytesLen;
          final int lhsEncodedTime = RowFormatter.readInt(lhs, lhsOffset);
          final int rhsEncodedTime = RowFormatter.readInt(rhs, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            lhsOffset += GemFireXDUtils.IntegerBytesLen;
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = RowFormatter.readInt(lhs, lhsOffset);
            final long rhsNanos = RowFormatter.readInt(rhs, rhsOffset);

            return (int)(lhsNanos - rhsNanos);
          }
        }
      }
      case StoredFormatIds.BOOLEAN_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID: {
        return (lhs[0] - rhs[0]);
      }

      default:
        DataValueDescriptor lhsDVD = dtd.getNull();
        lhsDVD.readBytes(lhs, lhsOffset, lhsColumnWidth);
        DataValueDescriptor rhsDVD = dtd.getNull();
        rhsDVD.readBytes(rhs, rhsOffset, rhsColumnWidth);

        return lhsDVD.compare(rhsDVD);
    }
  }
 
  /**
   * Null handling is done. Caller must handle default column values.
   * 
   * @throws StandardException
   */
  public static final int compare(DataValueDescriptor lhsDVD, byte[] rhs,
      long rhsOffsetWidth, final boolean nullsOrderedLow,
      final boolean caseSensitive, final ColumnDescriptor cd)
      throws StandardException {

    boolean lhsIsNull = false;
    // lhsDVD maybe a BinarySQLHybridType
    if (lhsDVD.getClass() == BinarySQLHybridType.class) {
      final BinarySQLHybridType bt = (BinarySQLHybridType)lhsDVD;
      lhsDVD = bt.sqlValue;
      if (lhsDVD == null) {
        final byte[] lhs = bt.byteValue;
        final long lhsOffsetWidth;
        if (lhs != null) {
          lhsOffsetWidth = lhs.length;
        }
        else {
          lhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
        }
        return compare(lhs, rhs, lhsOffsetWidth, rhsOffsetWidth,
            nullsOrderedLow, caseSensitive, cd);
      }
      else if (lhsDVD.isNull()) {
        lhsIsNull = true;
      }
    }
    else if (lhsDVD.isNull()) {
      lhsIsNull = true;
    }

    // check for default values that are null first
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT
        && cd.columnDefault == null) {
      rhsOffsetWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
    /*
     * null compare semantics if both null, then equal. if lhs null but rhs
     * non-null, then +ve if lhs non null but rhs null, then -ve.
     */
    if (lhsIsNull) {
      if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
        return 0; // By convention, nulls sort High, and null == null
      }
      else {
        // indicates null
        return nullsOrderedLow ? NULL_MIN : NULL_MAX;
        // return nullsOrderedLow ? -1 : 1; // indicates null
      }
    }
    else if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // indicates null
      return nullsOrderedLow ? NULL_MAX : NULL_MIN;
    }

    final DataTypeDescriptor dtd = cd.getType();
    final TypeId typeId = dtd.getTypeId();
    final int formatID = typeId.getTypeFormatId();

    int rhsColumnWidth;
    int rhsOffset;

    // check for default value
    if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
      // use the byte form of column default
      rhs = cd.columnDefaultBytes;
      rhsOffset = 0;
      rhsColumnWidth = rhs.length;
    }
    else {
      rhsColumnWidth = (int)(rhsOffsetWidth & 0xFFFFFFFF);
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsDVD.getShort();
        final int rhsV = SQLSmallint.getAsShort(rhs, rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsDVD.getInt();
        final long rhsV = SQLInteger.getAsInteger(rhs, rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsDVD.getLong();
        final long rhsV = SQLLongint.getAsLong(rhs, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = lhsDVD.getDouble();
        final double rhsV = SQLDouble.getAsDouble(rhs, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = lhsDVD.getFloat();
        final float rhsV = SQLReal.getAsFloat(rhs, rhsOffset);
        return Float.compare(lhsV, rhsV);
      }

      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
      case StoredFormatIds.XML_TYPE_ID: {
        final String lhsStr = lhsDVD.getString();
        return caseSensitive ? SQLChar.compareString(lhsStr, rhs, rhsOffset,
            rhsColumnWidth) : SQLChar.compareStringIgnoreCase(lhsStr, rhs,
            rhsOffset, rhsColumnWidth);
      }

      case StoredFormatIds.DECIMAL_TYPE_ID: {
        // TODO: PERF: optimize to use byte[]s directly?
        final BigDecimal lhsV = (BigDecimal)lhsDVD.getObject();
        final BigDecimal rhsV = SQLDecimal.getAsBigDecimal(rhs, rhsOffset,
            rhsColumnWidth);

        if (lhsV != null) {
          if (rhsV != null) {
            return lhsV.compareTo(rhsV);
          }
          else {
            return 1;
          }
        }
        else {
          return rhsV == null ? 0 : -1;
        }
      }

      case StoredFormatIds.DATE_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedDate = ((DateTimeDataValue)lhsDVD)
            .getEncodedDate();
        final long rhsEncodedDate = RowFormatter.readInt(rhs, rhsOffset);

        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = ((DateTimeDataValue)lhsDVD)
            .getEncodedTime();
        final long rhsEncodedTime = RowFormatter.readInt(rhs, rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final DateTimeDataValue ts = (DateTimeDataValue)lhsDVD;
        final int lhsEncodedDate = ts.getEncodedDate();
        final int rhsEncodedDate = RowFormatter.readInt(rhs, rhsOffset);

        if (lhsEncodedDate < rhsEncodedDate) {
          return -1;
        }
        else if (lhsEncodedDate > rhsEncodedDate) {
          return 1;
        }
        else {
          rhsOffset += GemFireXDUtils.IntegerBytesLen;
          final int lhsEncodedTime = ts.getEncodedTime();
          final int rhsEncodedTime = RowFormatter.readInt(rhs, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = ts.getNanos();
            final long rhsNanos = RowFormatter.readInt(rhs, rhsOffset);

            return (int)(lhsNanos - rhsNanos);
          }
        }
      }
      case StoredFormatIds.BOOLEAN_TYPE_ID:
      case StoredFormatIds.TINYINT_TYPE_ID: {
        final int lhsV = lhsDVD.getByte();
        final int rhsV = rhs[0];
        return (lhsV - rhsV);
      }

      default:
        DataValueDescriptor rhsDVD = dtd.getNull();
        rhsDVD.readBytes(rhs, rhsOffset, rhsColumnWidth);

        return lhsDVD.compare(rhsDVD);
    }
  }

  public static TypeId getNumericTypeIdForNumber(String num) {
    try {
      Byte byet = Byte.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.TINYINT);
    } catch (NumberFormatException ignore) {
    }

    try {
      Short shorrt = Short.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.SMALLINT);
    } catch (NumberFormatException ignore) {
    }

    try {
      Integer integer = Integer.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.INTEGER);
    } catch (NumberFormatException ignore) {
    }

    try {
      Long loong = Long.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.BIGINT);
    } catch (NumberFormatException ignore) {
    }

    try {
      Float floaat = Float.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.REAL);
    } catch (NumberFormatException ignore) {
    }

    try {
      Double doouble = Double.valueOf(num);
      return TypeId.getBuiltInTypeId(Types.DOUBLE);
    } catch (NumberFormatException ignore) {
    }
    return null;
    // TODO: Will big decimal create trouble. Lets c . else we will see if the
    // string can be represented by Big decimal
  }
  // GemStone changes END
}
