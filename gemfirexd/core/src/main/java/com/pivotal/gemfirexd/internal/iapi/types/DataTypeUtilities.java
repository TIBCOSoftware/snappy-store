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

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.Calendar;

import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import org.apache.spark.unsafe.Platform;
import org.apache.spark.unsafe.types.UTF8String;

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
      size = (storageLength > 0 ? storageLength
          : JDBC30Translation.DEFAULT_COLUMN_DISPLAY_SIZE);
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
   * Extract the given column from raw bytes as a UTF8String.
   */
  public static UTF8String getAsUTF8String(final byte[] inBytes,
      final int offset, final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.columnType;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        // TODO: SW: change format in SQLChar to be full UTF8
        // and below is broken for > 3-character UTF8 strings
        return UTF8String.fromAddress(inBytes,
            Platform.BYTE_ARRAY_OFFSET + offset, columnWidth);
      default:
        throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
            "UTF8String", cd.getColumnName());
    }
  }

  /**
   * Extract the given column from raw bytes as a UTF8String.
   */
  public static UTF8String getAsUTF8String(final long memOffset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.columnType;
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return UTF8String.fromAddress(null, memOffset, columnWidth);
      default:
        throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
            "UTF8String", cd.getColumnName());
    }
  }

  /**
   * Extract the given column from raw bytes as a string.
   */
  public static String getAsString(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(inBytes, offset, columnWidth, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.toString(RowFormatter.readInt(inBytes, offset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.toString(RowFormatter.readLong(inBytes, offset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.toString(SQLDouble.getAsDouble(inBytes, offset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.toString(SQLReal.getAsFloat(inBytes, offset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.toString(RowFormatter.readShort(inBytes, offset));
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
        return dvd.getString();
    }
  }

  /**
   * Extract the given column from raw bytes as a string.
   */
  public static String getAsString(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(memOffset, columnWidth, bs, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(memOffset, columnWidth, bs);
      case StoredFormatIds.INT_TYPE_ID:
        return Integer.toString(RowFormatter.readInt(memOffset));
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsString(unsafe, memOffset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return Long.toString(RowFormatter.readLong(memOffset));
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return Double.toString(SQLDouble.getAsDouble(memOffset));
      case StoredFormatIds.REAL_TYPE_ID:
        return Float.toString(SQLReal.getAsFloat(memOffset));
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return Integer.toString(RowFormatter.readShort(memOffset));
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
        return XML.getAsString(memOffset, columnWidth, bs);
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getString();
    }
  }

  /**
   * Extract the given column from raw bytes as a java object.
   */
  public static Object getAsObject(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(inBytes, offset, columnWidth, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(inBytes, offset, columnWidth);
      case StoredFormatIds.INT_TYPE_ID:
        return RowFormatter.readInt(inBytes, offset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsBigDecimal(inBytes, offset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(inBytes, offset);
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(inBytes, offset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(inBytes, offset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return (int)RowFormatter.readShort(inBytes, offset);
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
        return SQLBoolean.getAsBoolean(inBytes, offset);
      case StoredFormatIds.TINYINT_TYPE_ID:
        return SQLTinyint.getAsByte(inBytes, offset);

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
        return dvd.getObject();
    }
  }

  /**
   * Extract the given column from raw bytes as a java object.
   */
  public static Object getAsObject(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        return SQLChar.getAsString(memOffset, columnWidth, bs, dtd);
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        return SQLVarchar.getAsString(memOffset, columnWidth, bs);
      case StoredFormatIds.INT_TYPE_ID:
        return RowFormatter.readInt(memOffset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        return SQLDecimal.getAsBigDecimal(memOffset, columnWidth);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(memOffset);
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(memOffset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(memOffset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return (int)RowFormatter.readShort(memOffset);
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
        return SQLBoolean.getAsBoolean(unsafe, memOffset);
      case StoredFormatIds.TINYINT_TYPE_ID:
        return SQLTinyint.getAsByte(unsafe, memOffset);

      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
        return SQLBinary.getAsBytes(unsafe, memOffset, columnWidth);

      case StoredFormatIds.XML_TYPE_ID:
        return XML.getAsString(memOffset, columnWidth, bs);

      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getObject();
    }
  }

  static byte[] INT_MIN_BYTES = new byte[] { (byte)'-', (byte)'2',
      (byte)'1', (byte)'4', (byte)'7', (byte)'4', (byte)'8', (byte)'3',
      (byte)'6', (byte)'4', (byte)'8' };

  static byte[] LONG_MIN_BYTES = new byte[] { (byte)'-', (byte)'9',
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
  public static void writeAsUTF8BytesForPXF(final byte[] inBytes,
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
        toString(RowFormatter.readInt(inBytes, offset), buffer);
        return;
      case StoredFormatIds.LONGINT_TYPE_ID:
        toString(RowFormatter.readLong(inBytes, offset), buffer);
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
        toString(RowFormatter.readShort(inBytes, offset), buffer);
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
        break;
    }
  }

  /** Extract the given column from raw bytes as UTF8 encoded string for PXF. */
  public static void writeAsUTF8BytesForPXF(final UnsafeWrapper unsafe,
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
        toString(RowFormatter.readInt(memOffset), buffer);
        return;
      case StoredFormatIds.LONGINT_TYPE_ID:
        toString(RowFormatter.readLong(memOffset), buffer);
        return;

      case StoredFormatIds.DECIMAL_TYPE_ID:
        SQLDecimal.writeAsString(unsafe, memOffset, columnWidth, buffer);
        return;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        buffer.writeBytes(Double.toString(SQLDouble.getAsDouble(memOffset)));
        return;
      case StoredFormatIds.REAL_TYPE_ID:
        buffer.writeBytes(Float.toString(SQLReal.getAsFloat(memOffset)));
        return;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        toString(RowFormatter.readShort(memOffset), buffer);
        return;
      case StoredFormatIds.DATE_TYPE_ID:
        SQLDate.writeAsString(memOffset, buffer);
        return;
      case StoredFormatIds.TIME_TYPE_ID:
        SQLTime.writeAsString(memOffset, buffer);
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
        dvd.readBytes(memOffset, columnWidth, bs);
        final String result = dvd.getString();
        if (result != null) {
          // we don't expect non-ASCII characters except in *CHAR/CLOB/XML
          // types that have already been taken care of above
          buffer.writeBytes(result);
        }
        break;
    }
  }

  /**
   * Extract the given column value from raw bytes and set directly into the
   * given DVD.
   */
  public static void setInDVD(final DataValueDescriptor dvd,
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
        dvd.setValue(RowFormatter.readInt(inBytes, offset));
        break;
      case StoredFormatIds.DECIMAL_TYPE_ID:
        dvd.setBigDecimal(SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth));
        break;
      case StoredFormatIds.LONGINT_TYPE_ID:
        dvd.setValue(RowFormatter.readLong(inBytes, offset));
        break;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        dvd.setValue(SQLDouble.getAsDouble(inBytes, offset));
        break;
      case StoredFormatIds.REAL_TYPE_ID:
        dvd.setValue(SQLReal.getAsFloat(inBytes, offset));
        break;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        dvd.setValue(RowFormatter.readShort(inBytes, offset));
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
  public static void setInDVD(final DataValueDescriptor dvd,
      final UnsafeWrapper unsafe, final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.CHAR_TYPE_ID:
        dvd.setValue(SQLChar.getAsString(memOffset, columnWidth, bs, dtd));
        break;
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID:
        dvd.setValue(SQLVarchar.getAsString(memOffset, columnWidth, bs));
        break;
      case StoredFormatIds.INT_TYPE_ID:
        dvd.setValue(RowFormatter.readInt(memOffset));
        break;
      case StoredFormatIds.DECIMAL_TYPE_ID:
        dvd.setBigDecimal(SQLDecimal.getAsBigDecimal(memOffset, columnWidth));
        break;
      case StoredFormatIds.LONGINT_TYPE_ID:
        dvd.setValue(RowFormatter.readLong(memOffset));
        break;
      case StoredFormatIds.DOUBLE_TYPE_ID:
        dvd.setValue(SQLDouble.getAsDouble(memOffset));
        break;
      case StoredFormatIds.REAL_TYPE_ID:
        dvd.setValue(SQLReal.getAsFloat(memOffset));
        break;
      case StoredFormatIds.SMALLINT_TYPE_ID:
        dvd.setValue(RowFormatter.readShort(memOffset));
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
        dvd.setValue(XML.getAsString(memOffset, columnWidth, bs));
        break;
      default:
        final DataValueDescriptor dvd2 = dtd.getNull();
        dvd2.readBytes(memOffset, columnWidth, bs);
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

  public static boolean getAsBoolean(final byte[] inBytes,
      final int offset, final int columnWidth, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return SQLBoolean.getAsBoolean(inBytes, offset);
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(inBytes, offset);
        return value != 0.0;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = RowFormatter.readInt(inBytes, offset);
        return value != 0;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        return SQLDecimal.getBoolean(bd);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(inBytes, offset);
        return lv != 0;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(inBytes, offset);
        return flt != 0.0f;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = RowFormatter.readShort(inBytes, offset);
        return shrt != 0;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        return !(str.equals("0") || str.equals("false"));
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        return !(str.equals("0") || str.equals("false"));
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getBoolean();
      }
    }
  }

  public static boolean getAsBoolean(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BOOLEAN_TYPE_ID:
        return SQLBoolean.getAsBoolean(unsafe, memOffset);
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        return value != 0.0;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = RowFormatter.readInt(memOffset);
        return value != 0;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset, columnWidth);
        return SQLDecimal.getBoolean(bd);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(memOffset);
        return lv != 0;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(memOffset);
        return flt != 0.0f;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = RowFormatter.readShort(memOffset);
        return shrt != 0;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        return !(str.equals("0") || str.equals("false"));
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        return !(str.equals("0") || str.equals("false"));
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getBoolean();
      }
    }
  }

  public static byte getAsByte(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

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
        final int value = RowFormatter.readInt(inBytes, offset);
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
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Byte.MIN_VALUE) && (lv <= Byte.MAX_VALUE))
          return (byte)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(inBytes, offset);
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
        final short shrt = RowFormatter.readShort(inBytes, offset);
        if (shrt > Byte.MAX_VALUE || shrt < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)shrt;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Byte.parseByte(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Byte.parseByte(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getByte();
      }
    }
  }

  public static byte getAsByte(final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        if ((value > (Byte.MAX_VALUE + 1.0d))
            || (value < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)value;
      }
      case StoredFormatIds.INT_TYPE_ID: {
        final int value = RowFormatter.readInt(memOffset);
        if (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE) {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        }
        return (byte)value;
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset, columnWidth);
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Byte.MIN_VALUE) && (lv <= Byte.MAX_VALUE))
          return (byte)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(memOffset);
        if (lv > Byte.MAX_VALUE || lv < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());

        return (byte)lv;

      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(memOffset);
        if ((flt > ((Byte.MAX_VALUE + 1.0d)))
            || (flt < (Byte.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final short shrt = RowFormatter.readShort(memOffset);
        if (shrt > Byte.MAX_VALUE || shrt < Byte.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TINYINT",
              cd.getColumnName());
        return (byte)shrt;
      }
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Byte.parseByte(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Byte.parseByte(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "TINYINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getByte();
      }
    }
  }

  // TODO: SW: for both LANG_OUTSIDE_RANGE, LANG_FORMAT errors, should also
  // have the culprit value in exception message
  public static short getAsShort(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

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
        final int value = RowFormatter.readInt(inBytes, offset);
        if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;

      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Short.MIN_VALUE) && (lv <= Short.MAX_VALUE))
          return (short)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(inBytes, offset);
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
        return RowFormatter.readShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Short.parseShort(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Short.parseShort(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getShort();
      }
    }
  }

  public static short getAsShort(final long memOffset, final int columnWidth,
      @Unretained final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        if ((value > (Short.MAX_VALUE + 1.0d))
            || (value < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        final int value = RowFormatter.readInt(memOffset);
        if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)value;

      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset,
            columnWidth);
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Short.MIN_VALUE) && (lv <= Short.MAX_VALUE))
          return (short)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(memOffset);
        if (lv > Short.MAX_VALUE || lv < Short.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(memOffset);
        if ((flt > ((Short.MAX_VALUE + 1.0d)))
            || (flt < (Short.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "SMALLINT",
              cd.getColumnName());
        return (short)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Short.parseShort(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Short.parseShort(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "SMALLINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getShort();
      }
    }
  }

  public static int getAsInt(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

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
        return RowFormatter.readInt(inBytes, offset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Integer.MIN_VALUE) && (lv <= Integer.MAX_VALUE))
          return (int)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(inBytes, offset);
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
        return RowFormatter.readShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getInt();
      }
    }
  }

  public static int getAsInt(final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        if ((value > (Integer.MAX_VALUE + 1.0d))
            || (value < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return RowFormatter.readInt(memOffset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset,
            columnWidth);
        final long lv = SQLDecimal.getLong(bd);
        if ((lv >= Integer.MIN_VALUE) && (lv <= Integer.MAX_VALUE))
          return (int)lv;
        else {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lv = RowFormatter.readLong(memOffset);
        if (lv > Integer.MAX_VALUE || lv < Integer.MIN_VALUE)
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)lv;
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(memOffset);
        if ((flt > ((Integer.MAX_VALUE + 1.0d)))
            || (flt < (Integer.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER",
              cd.getColumnName());
        return (int)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "INTEGER",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getInt();
      }
    }
  }

  public static long getAsLong(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

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
        return RowFormatter.readInt(inBytes, offset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        return SQLDecimal.getLong(bd);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        return RowFormatter.readLong(inBytes, offset);
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
        return RowFormatter.readShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Long.parseLong(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Long.parseLong(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getLong();
      }
    }
  }

  public static long getAsLong(final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        if ((value > (Long.MAX_VALUE + 1.0d))
            || (value < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)value;
      }

      case StoredFormatIds.INT_TYPE_ID: {
        return RowFormatter.readInt(memOffset);
      }
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset,
            columnWidth);
        return SQLDecimal.getLong(bd);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        return RowFormatter.readLong(memOffset);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float flt = SQLReal.getAsFloat(memOffset);
        if ((flt > ((Long.MAX_VALUE + 1.0d)))
            || (flt < (Long.MIN_VALUE - 1.0d)))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "BIGINT",
              cd.getColumnName());
        return (long)flt;
      }
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Long.parseLong(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Long.parseLong(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getLong();
      }
    }
  }

  public static float getAsFloat(final byte[] inBytes, final int offset,
      final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

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
        return RowFormatter.readInt(inBytes, offset);
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal localValue = SQLDecimal.getAsBigDecimal(inBytes,
            offset, columnWidth);
        return NumberDataType.normalizeREALAllowNaN(localValue.floatValue());
      }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(inBytes, offset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(inBytes, offset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Float.parseFloat(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Float.parseFloat(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "BIGINT",
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getFloat();
      }
    }
  }

  public static float getAsFloat(final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double value = SQLDouble.getAsDouble(memOffset);
        if (Float.isInfinite((float)value))
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, TypeId.REAL_NAME,
              cd.getColumnName());
        return (float)value;
      }
      case StoredFormatIds.INT_TYPE_ID:
        return RowFormatter.readInt(memOffset);
      case StoredFormatIds.DECIMAL_TYPE_ID: {
        final BigDecimal localValue = SQLDecimal.getAsBigDecimal(
            memOffset, columnWidth);
        return NumberDataType.normalizeREALAllowNaN(localValue.floatValue());
      }
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(memOffset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(memOffset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Float.parseFloat(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.REAL_NAME,
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Float.parseFloat(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.REAL_NAME,
              cd.getColumnName());
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getFloat();
      }
    }
  }

  public static double getAsDouble(final byte[] inBytes,
      final int offset, final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(inBytes, offset);
      case StoredFormatIds.INT_TYPE_ID:
        return RowFormatter.readInt(inBytes, offset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(inBytes, offset,
            columnWidth);
        return SQLDecimal.getDouble(bd);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(inBytes, offset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(inBytes, offset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(inBytes, offset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Double.parseDouble(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(inBytes, offset, columnWidth,
            dtd).trim();
        try {
          return Double.parseDouble(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
              cd.getColumnName());
        }
      }
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        return dvd.getDouble();
    }
  }

  public static double getAsDouble(final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DOUBLE_TYPE_ID:
        return SQLDouble.getAsDouble(memOffset);
      case StoredFormatIds.INT_TYPE_ID:
        return RowFormatter.readInt(memOffset);
      case StoredFormatIds.DECIMAL_TYPE_ID:
        final BigDecimal bd = SQLDecimal.getAsBigDecimal(memOffset,
            columnWidth);
        return SQLDecimal.getDouble(bd);
      case StoredFormatIds.LONGINT_TYPE_ID:
        return RowFormatter.readLong(memOffset);
      case StoredFormatIds.REAL_TYPE_ID:
        return SQLReal.getAsFloat(memOffset);
      case StoredFormatIds.SMALLINT_TYPE_ID:
        return RowFormatter.readShort(memOffset);
      case StoredFormatIds.CHAR_TYPE_ID: {
        final String str = SQLChar.getAsString(memOffset, columnWidth,
            bs, dtd).trim();
        try {
          return Double.parseDouble(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
              cd.getColumnName());
        }
      }
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        final String str = SQLVarchar.getAsString(memOffset,
            columnWidth, bs, dtd).trim();
        try {
          return Double.parseDouble(str);
        } catch (NumberFormatException nfe) {
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, TypeId.DOUBLE_NAME,
              cd.getColumnName());
        }
      }
      default:
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        return dvd.getDouble();
    }
  }

  public static BigDecimal getAsBigDecimal(final byte[] inBytes,
      final int offset, final int columnWidth, final ColumnDescriptor cd)
      throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    try {
      switch (formatID) {
        case StoredFormatIds.DECIMAL_TYPE_ID:
          return SQLDecimal.getAsBigDecimal(inBytes, offset, columnWidth);
        case StoredFormatIds.DOUBLE_TYPE_ID: {
          final double value = SQLDouble.getAsDouble(inBytes, offset);
          return BigDecimal.valueOf(value);
        }
        case StoredFormatIds.REAL_TYPE_ID: {
          final float flt = SQLReal.getAsFloat(inBytes, offset);
          return new BigDecimal(Float.toString(flt));
        }
        case StoredFormatIds.INT_TYPE_ID: {
          final int v = RowFormatter.readInt(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.LONGINT_TYPE_ID: {
          final long v = RowFormatter.readLong(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.SMALLINT_TYPE_ID: {
          final short v = RowFormatter.readShort(inBytes, offset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.CHAR_TYPE_ID: {
          final String str = SQLChar.getAsString(inBytes, offset, columnWidth,
              dtd);
          return new BigDecimal(str);
        }
        case StoredFormatIds.LONGVARCHAR_TYPE_ID:
        case StoredFormatIds.VARCHAR_TYPE_ID:
          String str = SQLVarchar.getAsString(inBytes, offset, columnWidth);
          return new BigDecimal(str);
        default: {
          final DataValueDescriptor dvd = dtd.getNull();
          dvd.readBytes(inBytes, offset, columnWidth);
          return SQLDecimal.getBigDecimal(dvd);
        }
      }
    } catch (NumberFormatException nfe) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          "java.math.BigDecimal", cd.getColumnName());
    }
  }

  public static BigDecimal getAsBigDecimal(final long memOffset,
      final int columnWidth, final OffHeapByteSource bs,
      final ColumnDescriptor cd) throws StandardException {

    final DataTypeDescriptor dtd = cd.getType();
    final int formatID = dtd.getTypeId().getTypeFormatId();
    try {
      switch (formatID) {
        case StoredFormatIds.DECIMAL_TYPE_ID:
          return SQLDecimal.getAsBigDecimal(memOffset, columnWidth);
        case StoredFormatIds.DOUBLE_TYPE_ID: {
          final double value = SQLDouble.getAsDouble(memOffset);
          return BigDecimal.valueOf(value);
        }
        case StoredFormatIds.REAL_TYPE_ID: {
          final float flt = SQLReal.getAsFloat(memOffset);
          return new BigDecimal(Float.toString(flt));
        }
        case StoredFormatIds.INT_TYPE_ID: {
          final int v = RowFormatter.readInt(memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.LONGINT_TYPE_ID: {
          final long v = RowFormatter.readLong(memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.SMALLINT_TYPE_ID: {
          final short v = RowFormatter.readShort(memOffset);
          return BigDecimal.valueOf(v);
        }
        case StoredFormatIds.CHAR_TYPE_ID: {
          final String str = SQLChar.getAsString(memOffset,
              columnWidth, bs, dtd);
          return new BigDecimal(str);
        }
        case StoredFormatIds.LONGVARCHAR_TYPE_ID:
        case StoredFormatIds.VARCHAR_TYPE_ID:
          String str = SQLVarchar.getAsString(memOffset, columnWidth,
              bs);
          return new BigDecimal(str);
        default: {
          final DataValueDescriptor dvd = dtd.getNull();
          dvd.readBytes(memOffset, columnWidth, bs);
          return SQLDecimal.getBigDecimal(dvd);
        }
      }
    } catch (NumberFormatException nfe) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          "java.math.BigDecimal", cd.getColumnName());
    }
  }

  public static byte[] getAsBytes(final byte[] inBytes, final int offset,
      final int columnWidth, final DataTypeDescriptor dtd)
      throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
      // UTF8 bytes
      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        // byte[]s are immutable in GemFireXD, so avoid copy if not required
        if (inBytes != null && columnWidth >= 0) {
          if (offset == 0 && columnWidth == inBytes.length) {
            return inBytes;
          } else {
            // TODO:Asif: avoid the extra byte array created
            final byte[] bytes = new byte[columnWidth];
            System.arraycopy(inBytes, offset, bytes, 0, columnWidth);
            return bytes;
          }
        } else {
          return null;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getBytes();
        }
        else {
          return null;
        }
      }
    }
  }

  public static byte[] getAsBytes(final long memAddr, final int addrOffset,
      final int columnWidth, final OffHeapByteSource bs,
      final DataTypeDescriptor dtd) throws StandardException {

    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.BIT_TYPE_ID:
      case StoredFormatIds.VARBIT_TYPE_ID:
      case StoredFormatIds.LONGVARBIT_TYPE_ID:
      case StoredFormatIds.BLOB_TYPE_ID:
      // UTF8 bytes
      case StoredFormatIds.CHAR_TYPE_ID:
      case StoredFormatIds.LONGVARCHAR_TYPE_ID:
      case StoredFormatIds.VARCHAR_TYPE_ID:
      case StoredFormatIds.CLOB_TYPE_ID: {
        // off-heap always has to create a new byte[] so avoid extra calls of
        // getLength by just calling readBytes
        if (columnWidth >= 0) {
          final byte[] bytes = new byte[columnWidth];
          UnsafeMemoryChunk.readAbsoluteBytes(memAddr, addrOffset, bytes, 0,
              columnWidth);
          return bytes;
        } else {
          return null;
        }
      }
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memAddr + addrOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getBytes();
        }
        else {
          return null;
        }
      }
    }
  }

  public static long getAsDateMillis(final byte[] inBytes,
      final int offset, final int columnWidth, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDateMillis(inBytes, offset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsDateMillis(inBytes, offset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return dvd.getDate(cal).getTime();
        } else {
          return 0L;
        }
      }
    }
  }

  public static long getAsDateMillis(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final Calendar cal, final DataTypeDescriptor dtd)
      throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsDateMillis(unsafe, memOffset, cal);
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsDateMillis(unsafe, memOffset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return dvd.getDate(cal).getTime();
        } else {
          return 0L;
        }
      }
    }
  }

  public static java.sql.Date getAsDate(final byte[] inBytes,
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

  public static java.sql.Date getAsDate(final UnsafeWrapper unsafe,
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
        dvd.readBytes(memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getDate(cal);
        }
      }
    }
  }

  public static long getTimestampMicros(final java.sql.Timestamp ts) {
    return ts.getTime() * 1000L + (ts.getNanos() / 1000L);
  }

  public static long getAsTimestampMicros(final byte[] inBytes,
      final int offset, final int columnWidth, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStampMicros(inBytes, offset, cal);
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsTimeStampMicros(inBytes, offset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(inBytes, offset, columnWidth);
        if (!dvd.isNull()) {
          return getTimestampMicros(dvd.getTimestamp(cal));
        } else {
          return 0L;
        }
      }
    }
  }

  public static long getAsTimestampMicros(
      final UnsafeWrapper unsafe, final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final Calendar cal,
      final DataTypeDescriptor dtd) throws StandardException {
    final int formatID = dtd.getTypeId().getTypeFormatId();
    switch (formatID) {
      case StoredFormatIds.TIMESTAMP_TYPE_ID:
        return SQLTimestamp.getAsTimeStampMicros(unsafe, memOffset, cal);
      case StoredFormatIds.DATE_TYPE_ID:
        return SQLDate.getAsTimeStampMicros(unsafe, memOffset, cal);
      default: {
        final DataValueDescriptor dvd = dtd.getNull();
        dvd.readBytes(memOffset, columnWidth, bs);
        if (!dvd.isNull()) {
          return getTimestampMicros(dvd.getTimestamp(cal));
        } else {
          return 0L;
        }
      }
    }
  }

  public static java.sql.Timestamp getAsTimestamp(final byte[] inBytes,
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

  public static java.sql.Timestamp getAsTimestamp(
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
        dvd.readBytes(memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTimestamp(cal);
        }
      }
    }
  }

  public static java.sql.Time getAsTime(final byte[] inBytes,
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

  public static java.sql.Time getAsTime(final UnsafeWrapper unsafe,
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
        dvd.readBytes(memOffset, columnWidth, bs);
        if (dvd.isNull()) {
          return null;
        }
        else {
          return dvd.getTime(cal);
        }
      }
    }
  }

  public static int NULL_MAX = Integer.MAX_VALUE >>> 1;
  public static int NULL_MIN = -NULL_MAX;

  /**
   * Compare two columns (inside byte arrays or off-heap byte sources). Null
   * handling is done.
   */
  public static int compare(final UnsafeWrapper unsafe, byte[] lhsBytes,
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

    int lhsColumnWidth = (int)lhsOffsetWidth;
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
      rhsColumnWidth = (int)rhsOffsetWidth;
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsMemAddr != 0 ? RowFormatter.readShort(
            lhsMemAddr + lhsOffset) : RowFormatter.readShort(lhsBytes,
            lhsOffset);
        final int rhsV = rhsMemAddr != 0 ? RowFormatter.readShort(
            rhsMemAddr + rhsOffset) : RowFormatter.readShort(rhsBytes,
            rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsMemAddr != 0 ? RowFormatter.readInt(
            lhsMemAddr + lhsOffset) : RowFormatter.readInt(lhsBytes,
            lhsOffset);
        final long rhsV = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsMemAddr != 0 ? RowFormatter.readLong(
            lhsMemAddr + lhsOffset) : RowFormatter.readLong(lhsBytes, lhsOffset);
        final long rhsV = rhsMemAddr != 0 ? RowFormatter.readLong(
            rhsMemAddr + rhsOffset) : RowFormatter.readLong(rhsBytes, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = lhsMemAddr != 0 ? SQLDouble.getAsDouble(
            lhsMemAddr + lhsOffset) : SQLDouble
            .getAsDouble(lhsBytes, lhsOffset);
        final double rhsV = rhsMemAddr != 0 ? SQLDouble.getAsDouble(
            rhsMemAddr + rhsOffset) : SQLDouble
            .getAsDouble(rhsBytes, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = lhsMemAddr != 0 ? SQLReal.getAsFloat(
            lhsMemAddr + lhsOffset) : SQLReal.getAsFloat(lhsBytes, lhsOffset);
        final float rhsV = rhsMemAddr != 0 ? SQLReal.getAsFloat(
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
            lhsMemAddr + lhsOffset, lhsColumnWidth) : SQLDecimal
            .getAsBigDecimal(lhsBytes, lhsOffset, lhsColumnWidth);
        final BigDecimal rhsV = rhsMemAddr != 0 ? SQLDecimal.getAsBigDecimal(
            rhsMemAddr + rhsOffset, rhsColumnWidth) : SQLDecimal
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
        final long lhsEncodedDate = lhsMemAddr != 0 ? RowFormatter.readInt(
            lhsMemAddr + lhsOffset) : RowFormatter.readInt(lhsBytes,
            lhsOffset);
        final long rhsEncodedDate = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);
        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = lhsMemAddr != 0 ? RowFormatter.readInt(
            lhsMemAddr + lhsOffset) : RowFormatter.readInt(lhsBytes,
            lhsOffset);
        final long rhsEncodedTime = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final long lhsEncodedDate = lhsMemAddr != 0 ? RowFormatter.readInt(
            lhsMemAddr + lhsOffset) : RowFormatter.readInt(lhsBytes,
            lhsOffset);
        final long rhsEncodedDate = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
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
          final long lhsEncodedTime = lhsMemAddr != 0 ? RowFormatter.readInt(
              lhsMemAddr + lhsOffset) : RowFormatter.readInt(
              lhsBytes, lhsOffset);
          final long rhsEncodedTime = rhsMemAddr != 0 ? RowFormatter.readInt(
              rhsMemAddr + rhsOffset) : RowFormatter.readInt(
              rhsBytes, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            lhsOffset += GemFireXDUtils.IntegerBytesLen;
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = lhsMemAddr != 0 ? RowFormatter.readInt(
                lhsMemAddr + lhsOffset) : RowFormatter.readInt(
                lhsBytes, lhsOffset);
            final long rhsNanos = rhsMemAddr != 0 ? RowFormatter.readInt(
                rhsMemAddr + rhsOffset) : RowFormatter.readInt(
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
          lhsDVD.readBytes(lhsMemAddr + lhsOffset, lhsColumnWidth,
              lhsBS);
        }
        else {
          lhsDVD.readBytes(lhsBytes, lhsOffset, lhsColumnWidth);
        }
        DataValueDescriptor rhsDVD = dtd.getNull();
        if (rhsBS != null) {
          rhsDVD.readBytes(rhsMemAddr + rhsOffset, rhsColumnWidth,
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
  public static int compare(final UnsafeWrapper unsafe,
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
      rhsColumnWidth = (int)rhsOffsetWidth;
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsDVD.getShort();
        final int rhsV = rhsMemAddr != 0 ? RowFormatter.readShort(
            rhsMemAddr + rhsOffset) : RowFormatter.readShort(rhsBytes,
            rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsDVD.getInt();
        final long rhsV = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsDVD.getLong();
        final long rhsV = rhsMemAddr != 0 ? RowFormatter.readLong(
            rhsMemAddr + rhsOffset) : RowFormatter.readLong(rhsBytes, rhsOffset);
        // can't use (lhsV - rhsV) since it can overflow
        return lhsV < rhsV ? -1 : (lhsV == rhsV ? 0 : 1);
      }
      case StoredFormatIds.DOUBLE_TYPE_ID: {
        final double lhsV = lhsDVD.getDouble();
        final double rhsV = rhsMemAddr != 0 ? SQLDouble.getAsDouble(
            rhsMemAddr + rhsOffset) : SQLDouble
            .getAsDouble(rhsBytes, rhsOffset);
        return Double.compare(lhsV, rhsV);
      }
      case StoredFormatIds.REAL_TYPE_ID: {
        final float lhsV = lhsDVD.getFloat();
        final float rhsV = rhsMemAddr != 0 ? SQLReal.getAsFloat(
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
            rhsMemAddr + rhsOffset, rhsColumnWidth) : SQLDecimal
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
        final long rhsEncodedDate = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);
        return (int)(lhsEncodedDate - rhsEncodedDate);
      }
      case StoredFormatIds.TIME_TYPE_ID: {
        // using long below to avoid overflow of (lhs - rhs)
        final long lhsEncodedTime = ((DateTimeDataValue)lhsDVD)
            .getEncodedTime();
        final long rhsEncodedTime = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
            rhsOffset);

        // [sb] right now we are not reading encodedTimeFraction as its always
        // zero for now. support for higher time precision will use it.
        // follow encodedTimeFraction in SQLTime
        return (int)(lhsEncodedTime - rhsEncodedTime);
      }
      case StoredFormatIds.TIMESTAMP_TYPE_ID: {
        final DateTimeDataValue ts = (DateTimeDataValue)lhsDVD;
        final int lhsEncodedDate = ts.getEncodedDate();
        final long rhsEncodedDate = rhsMemAddr != 0 ? RowFormatter.readInt(
            rhsMemAddr + rhsOffset) : RowFormatter.readInt(rhsBytes,
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
          final long rhsEncodedTime = rhsMemAddr != 0 ? RowFormatter.readInt(
              rhsMemAddr + rhsOffset) : RowFormatter.readInt(
              rhsBytes, rhsOffset);

          if (lhsEncodedTime < rhsEncodedTime) {
            return -1;
          }
          else if (lhsEncodedTime > rhsEncodedTime) {
            return 1;
          }
          else {
            rhsOffset += GemFireXDUtils.IntegerBytesLen;
            final long lhsNanos = ts.getNanos();
            final long rhsNanos = rhsMemAddr != 0 ? RowFormatter.readInt(
                rhsMemAddr + rhsOffset) : RowFormatter.readInt(
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
          rhsDVD.readBytes(rhsMemAddr + rhsOffset, rhsColumnWidth,
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
   */
  public static int compare(byte[] lhs, byte[] rhs,
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

    int lhsColumnWidth = (int)lhsOffsetWidth;
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
      rhsColumnWidth = (int)rhsOffsetWidth;
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = RowFormatter.readShort(lhs, lhsOffset);
        final int rhsV = RowFormatter.readShort(rhs, rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = RowFormatter.readInt(lhs, lhsOffset);
        final long rhsV = RowFormatter.readInt(rhs, rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = RowFormatter.readLong(lhs, lhsOffset);
        final long rhsV = RowFormatter.readLong(rhs, rhsOffset);
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
   */
  public static int compare(DataValueDescriptor lhsDVD, byte[] rhs,
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
      rhsColumnWidth = (int)rhsOffsetWidth;
      rhsOffset = (int)(rhsOffsetWidth >>> Integer.SIZE);
    }

    switch (formatID) {
      case StoredFormatIds.SMALLINT_TYPE_ID: {
        final int lhsV = lhsDVD.getShort();
        final int rhsV = RowFormatter.readShort(rhs, rhsOffset);
        return (lhsV - rhsV);
      }
      case StoredFormatIds.INT_TYPE_ID: {
        // using long below to avoid overflow of (lhsV - rhsV)
        final long lhsV = lhsDVD.getInt();
        final long rhsV = RowFormatter.readInt(rhs, rhsOffset);
        return (int)(lhsV - rhsV);
      }
      case StoredFormatIds.LONGINT_TYPE_ID: {
        final long lhsV = lhsDVD.getLong();
        final long rhsV = RowFormatter.readLong(rhs, rhsOffset);
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

    try {
      new BigDecimal(num);
      return TypeId.getBuiltInTypeId(Types.DECIMAL);
    } catch (NumberFormatException ignore) {
    }

    return null;
  }
// GemStone changes END
}
