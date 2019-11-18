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

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.Arrays;
import java.util.Calendar;

import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.callbacks.TableMetaData;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColumnQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.ServerResolverUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.types.*;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.sql.GenericColumnDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import org.apache.spark.unsafe.Platform;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * A RowFormatter translates between byte[] (and byte[][]) storage
 * representations of a row and DataValueDescriptors.
 *
 * In order to accomplish this conversion, the values, type information, and
 * default values are needed. The type information and default values are
 * obtained from the ColumnDescriptors.
 *
 * Note that in this implementation, fixed width CHARs are stored as variable
 * length fields. Nullable-typed fields are also stored as variable length
 * fields under the presumption that a nullable type will actually be null more
 * often than not, in which case the variable width format provides the most
 * compact representation for null values*. (*Technically this is only true for
 * fixed width data values that require at least fours bytes to represent, e.g.
 * an integer or larger).
 *
 * The type information is derived from the DataTypeDescriptors found in
 * ColumnDescriptors from the DataDictionary. Note that a DataValueDescriptor
 * does not encode whether a field is nullable or not, just whether a given
 * value is null, and so provides insufficient information. The ColumnDescriptor
 * provides the additional information.
 *
 * The physical ordering of the fields in bytes is derived from the jdbcTypeIds
 * in a systematic way: the fixed length fields are moved in front of the
 * variable length fields so all the variable length fields are at the end.
 * After the field data, an offset is stored for each variable length field.
 *
 * More information about the byte format can be found at:
 * <a href="https://wiki.gemstone.com/display/queryteam/Relational+Storage+Model">
 *   Relational Storage Model</a>
 *
 * @author Eric Zoerner
 * @author Rahul Dubey
 * @author swale
 */
@SuppressWarnings("serial")
public final class RowFormatter implements Serializable {

  /*
   * /** Special token offset value to denote that column value is null. This is
   * normally not used because the offset then is negative of next variable
   * column offset, but when serializing columns back to back with embedded
   * widths (rather than offsets) this is used to denote a null column.
   * 
   * public static final int OFFSET_IS_NULL = -1;
   */

  // metadata for interpreting the bytes, based on this ColumnDescriptorList

  /** number of bytes per int offset */
  private final int offsetBytes;

  /** special token offset value to denote that column value is default */
  private final int offsetIsDefault;

  /**
   * the version of the schema; will be > 0 only for table schema while zero
   * indicates non-versioned formatter
   */
  public final int schemaVersion;

  /**
   * pre-serialized {@link #schemaVersion}
   */
  private final byte[] versionBytes;

  /**
   * pre-serialized {@link #schemaVersion} when it occupies only one byte (the
   * usual case)
   */
  private final byte versionByte;

  /**
   * number of bytes required for storing {@link #schemaVersion}
   */
  public final int nVersionBytes;

  /** the maximum variable length width that is to be encoded in this row */
  private final int maxVarWidth;

  /** number of var width columns */
  private final int numVarWidthColumns;

  /** offset of the start of variable length data from the start of byte[] */
  private final int varDataOffset;

  /** a meta-data for the columns in this RowFormatter */
  private transient EmbedResultSetMetaData metadata;

  /** set to true if this RowFormatter is for the complete table */
  private final boolean isTableFormatter;

  /** set to true if this RowFormatter is Primary Key formatter for the table */
  private final boolean isPrimaryKeyFormatter;

  // positions of above field values in array temporarily used during
  // initialization of RowFormatter
  private static final int OFFSET_BYTES_POS = 0;
  private static final int OFFSET_IS_DEFAULT_POS = 1;
  private static final int MAX_VAR_WIDTH_POS = 2;
  private static final int NUM_VAR_WIDTH_COLS_POS = 3;
  private static final int VAR_DATA_OFFSET_POS = 4;
  private static final int NUM_POS = 5;

  /**
   * Token version used for rows recovered from older pre 1.1 version data files
   * that did not have schema version. For such cases this token version is
   * interpreted as the version just after recovery from disk.
   */
  public static final int TOKEN_RECOVERY_VERSION = -1;

  /**
   * Number of bytes required for serializing {@link #TOKEN_RECOVERY_VERSION}.
   */
  static final int TOKEN_RECOVERY_VERSION_BYTES = getCompactIntNumBytes(
      TOKEN_RECOVERY_VERSION);

  /**
   * Mapping between logical position of column and offset into the byte array
   * representation of the row, as follows:
   * <p>
   * Fixed width column: offset from beginning of the byte array to the data for
   * this column.
   * <p>
   * Variable width column: Negative offset from the end of the byte array to
   * the offset to the data for this column.
   * <p>
   * LOB type (BLOB or CLOB or SQLXML or JAVA_OBJECT): offset is negative index
   * of the byte array in the row byte[][] that represents this LOB.
   * <p>
   * Offsets are stored as 1, 2, 3 or 4 bytes depending on the max possible size
   * of the byte array. Offset values themselves can be:
   * <p>
   * Variable width value: offset is positive index of the start of serialized
   * bytes from start of array
   * <p>
   * Null value: offset is negative index of the start of the serialized bytes
   * of next variable length column, if any
   * <p>
   * Default value: offset is minimum -ve value possible in the offset bytes
   */
  int[] positionMap;

  /**
   * The {@link ColumnDescriptor}'s for this row that avoids having to go to
   * {@link ColumnDescriptorList} everytime.
   */
  public transient ColumnDescriptor[] columns;

  /** number of LOB columns types in a row */
  int numLobs;

  /** false if this is for an off-heap table else true */
  public final boolean isHeap;

  public final transient GemFireContainer container;

  // indicator return values from extract methods

  /** indicator for null value */
  public static final long OFFSET_AND_WIDTH_IS_NULL = -7;

  /** indicator for default value */
  public static final long OFFSET_AND_WIDTH_IS_DEFAULT = -6;

  /** Fixed delimiter character used internally for PXF formatting */
  public static final int DELIMITER_FOR_PXF = ',';

  /**
   * This interface allows specification of action to be taken for each column
   * e.g. for {@link RowFormatter#extractColumnBytes} and other such methods.
   *
   * @author swale
   * @since 7.0
   *
   * @param <O>
   *          the type of output class that will be written to
   */
  public interface ColumnProcessor<O> {

    /**
     * Handle a column with null value.
     *
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleNull(O output, int outputPosition, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, RowFormatter targetFormat,
        int targetIndex, int targetOffsetFromMap);

    /**
     * Handle a column with default value.
     *
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleDefault(O output, int outputPosition, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, RowFormatter targetFormat,
        int targetIndex, int targetOffsetFromMap);

    /**
     * Handle a column with fixed width value.
     *
     * @param row
     *          the row containing the column bytes to be written
     * @param columnOffset
     *          the offset in the row where the current column starts
     * @param columnWidth
     *          number of bytes to be written that encode the column value
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleFixed(byte[] row, int columnOffset, int columnWidth, O output,
        int outputPosition, RowFormatter formatter, int colIndex,
        RowFormatter targetFormat, int targetIndex, int targetOffsetFromMap);

    /**
     * Handle a column with variable width value.
     *
     * @param row
     *          the row containing the column bytes to be written
     * @param columnOffset
     *          the offset in the row where the current column starts
     * @param columnWidth
     *          number of bytes to be written that encode the column value
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleVariable(byte[] row, int columnOffset, int columnWidth, O output,
        int outputPosition, RowFormatter formatter, ColumnDescriptor cd,
        int colIndex, RowFormatter targetFormat, int targetIndex,
        int targetOffsetFromMap);
  }

  /**
   * This interface allows specification of action to be taken for each column
   * e.g. for {@link RowFormatter#extractColumnBytesOffHeap} and other such
   * methods for {@link OffHeapByteSource} rows.
   *
   * @author swale
   * @since gfxd 2.0
   *
   * @param <O>
   *          the type of output class that will be written to
   */
  public interface ColumnProcessorOffHeap<O> {

    /**
     * Handle a column with null value.
     *
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleNull(O output, int outputPosition, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, RowFormatter targetFormat,
        int targetIndex, int targetOffsetFromMap);

    /**
     * Handle a column with default value.
     *
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleDefault(O output, int outputPosition, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, RowFormatter targetFormat,
        int targetIndex, int targetOffsetFromMap);

    /**
     * Handle a column with fixed width value.
     *
     * @param memAddress
     *          the memory address of the start of off-heap row
     * @param columnOffset
     *          the offset in the row where the current column starts
     * @param columnWidth
     *          number of bytes to be written that encode the column value
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleFixed(long memAddress, int columnOffset,
        int columnWidth, O output, int outputPosition, RowFormatter formatter,
        int colIndex, RowFormatter targetFormat, int targetIndex,
        int targetOffsetFromMap);

    /**
     * Handle a column with variable width value.
     *
     * @param memAddress
     *          the memory address of the start of off-heap row
     * @param columnOffset
     *          the offset in the row where the current column starts
     * @param columnWidth
     *          number of bytes to be written that encode the column value
     * @param output
     *          the output object to be written to
     * @param outputPosition
     *          the current position in the output object; this is provided back
     *          everytime with the result returned by this method
     * @param formatter
     *          the RowFormatter for the row on which this is being invoked
     * @param cd
     *          the {@link ColumnDescriptor} of the curent column
     * @param colIndex
     *          the 0-based index of column in the row
     * @param targetFormat
     *          any target RowFormatter to be used for generating the output
     * @param targetIndex
     *          the 0-based index of column for the target formatter
     * @param targetOffsetFromMap
     *          the offset read from targetFormat's position map
     *
     * @return the new position in the output object
     */
    int handleVariable(long memAddress, int columnOffset,
        int columnWidth, O output, int outputPosition, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, RowFormatter targetFormat,
        int targetIndex, int targetOffsetFromMap);
  }

  private static final ColumnProcessor<byte[]> extractColumnBytes =
      new ColumnProcessor<byte[]>() {

    @Override
    public final int handleNull(final byte[] outputBuffer,
        final int varDataOffset, RowFormatter formatter, ColumnDescriptor cd,
        int colIndex, final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      writeInt(outputBuffer, targetFormat.getNullIndicator(varDataOffset),
          offsetOffset, targetFormat.getNumOffsetBytes());
      return varDataOffset;
    }

    @Override
    public final int handleDefault(final byte[] outputBuffer,
        final int varDataOffset, RowFormatter formatter, ColumnDescriptor cd,
        int colIndex, final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      final byte[] defaultBytes = cd.columnDefaultBytes;
      if (defaultBytes == null) {
        // write null
        writeInt(outputBuffer, targetFormat.getNullIndicator(varDataOffset),
            offsetOffset, targetFormat.getNumOffsetBytes());
        return varDataOffset;
      } else {
        writeInt(outputBuffer, targetFormat.getVarDataOffset(varDataOffset),
            offsetOffset, targetFormat.getNumOffsetBytes());
        System.arraycopy(defaultBytes, 0, outputBuffer, varDataOffset,
            defaultBytes.length);
        return varDataOffset + defaultBytes.length;
      }
    }

    @Override
    public final int handleFixed(final byte[] row, final int columnOffset,
        final int columnWidth, final byte[] outputBuffer,
        final int varDataOffset, RowFormatter formatter, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (targetWidth >= columnWidth) {
        System.arraycopy(row, columnOffset, outputBuffer, targetOffsetFromMap,
            columnWidth);
        return varDataOffset;
      }
      System.arraycopy(row, columnOffset, outputBuffer, targetOffsetFromMap,
          targetWidth);
      return varDataOffset;
    }

    @Override
    public final int handleVariable(final byte[] row, final int columnOffset,
        int columnWidth, final byte[] outputBuffer, final int varDataOffset,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check if target can encode columnWidth bytes else truncate
      if (columnWidth > targetFormat.maxVarWidth) {
        columnWidth = targetFormat.maxVarWidth;
      }
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      writeInt(outputBuffer, targetFormat.getVarDataOffset(varDataOffset),
          offsetOffset, targetFormat.getNumOffsetBytes());
      if (targetFormat.isPrimaryKeyFormatter()
          && shouldTrimTrailingSpaces(cd)) {
        columnWidth = trimTrailingSpaces(row, columnOffset, columnWidth);
      }
      System.arraycopy(row, columnOffset, outputBuffer, varDataOffset,
          columnWidth);
      return varDataOffset + columnWidth;
    }
  };

  private static final ColumnProcessorOffHeap<byte[]> extractColumnBytesOffHeap =
      new ColumnProcessorOffHeap<byte[]>() {

    @Override
    public final int handleNull(final byte[] outputBuffer,
        final int varDataOffset, RowFormatter formatter, ColumnDescriptor cd,
        int colIndex, final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      writeInt(outputBuffer, targetFormat.getNullIndicator(varDataOffset),
          offsetOffset, targetFormat.getNumOffsetBytes());
      return varDataOffset;
    }

    @Override
    public final int handleDefault(final byte[] outputBuffer,
        final int varDataOffset, RowFormatter formatter, ColumnDescriptor cd,
        int colIndex, final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      writeInt(outputBuffer, targetFormat.getOffsetDefaultToken(),
          offsetOffset, targetFormat.getNumOffsetBytes());
      return varDataOffset;
    }

    @Override
    public final int handleFixed(final long memAddress, final int columnOffset, final int columnWidth,
        final byte[] outputBuffer, final int varDataOffset,
        RowFormatter formatter, int colIndex, final RowFormatter targetFormat,
        int targetIndex, final int targetOffsetFromMap) {
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (targetWidth >= columnWidth) {
        UnsafeMemoryChunk.readUnsafeBytes(memAddress, columnOffset,
            outputBuffer, targetOffsetFromMap, columnWidth);
        return varDataOffset;
      }
      UnsafeMemoryChunk.readUnsafeBytes(memAddress, columnOffset,
          outputBuffer, targetOffsetFromMap, targetWidth);
      return varDataOffset;
    }

    @Override
    public final int handleVariable(final long memAddress, final int columnOffset, int columnWidth,
        final byte[] outputBuffer, final int varDataOffset,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check if target can encode columnWidth bytes else truncate
      if (columnWidth > targetFormat.maxVarWidth) {
        columnWidth = targetFormat.maxVarWidth;
      }
      final int offsetOffset = outputBuffer.length + targetOffsetFromMap;
      writeInt(outputBuffer, targetFormat.getVarDataOffset(varDataOffset),
          offsetOffset, targetFormat.getNumOffsetBytes());
      if (targetFormat.isPrimaryKeyFormatter()
          && shouldTrimTrailingSpaces(cd)) {
        columnWidth = trimTrailingSpaces(memAddress, columnOffset, columnWidth);
      }
      UnsafeMemoryChunk.readUnsafeBytes(memAddress, columnOffset,
          outputBuffer, varDataOffset, columnWidth);
      return varDataOffset + columnWidth;
    }
  };

  /**
   * Compare two byte[], ignore the trailing blanks in the longer byte[]
   *
   * @return new columnWidth after trimming trailing spaces.
   */
  private static int trimTrailingSpaces(final byte[] row,
      final int columnOffset, int columnWidth) {
    int byteOffset = columnOffset + columnWidth - 1;
    while (columnWidth > 0 && row[byteOffset] == 0x20) {
      byteOffset--;
      columnWidth--;
    }
    return columnWidth;
  }

  /**
   * Compare two byte[], ignore the trailing blanks in the longer byte[]
   *
   * @return new columnWidth after trimming trailing spaces.
   */
  private static int trimTrailingSpaces(
      final long memAddress, final int columnOffset, int columnWidth) {
    long memOffset = memAddress + columnOffset + columnWidth - 1;
    while (columnWidth > 0 && Platform.getByte(null, memOffset) == 0x20) {
      memOffset--;
      columnWidth--;
    }
    return columnWidth;
  }

  /////////////////// PUBLIC METHODS ///////////////////

  /**
   * Write an int into a byte[].
   *
   * @return the number of bytes written.
   */
  public static int writeInt(final byte[] bytes, int intValue, int offset) {
    assert bytes != null;

    // serialize in big-endian format to be compatible with DataOutput.writeInt
    // and also SQLInteger.computeHashCode
    if (UnsafeHolder.littleEndian) {
      Platform.putInt(bytes, offset + Platform.BYTE_ARRAY_OFFSET,
          Integer.reverseBytes(intValue));
      return 4; // bytes in int (= Integer.SIZE / 8);
    } else {
      Platform.putInt(bytes, offset + Platform.BYTE_ARRAY_OFFSET, intValue);
      return 4; // bytes in int (= Integer.SIZE / 8);
    }
  }

  /**
   * Writes the given number of bytes of an int at the given offset within the
   * bytes array.
   *
   * @param bytes
   *          the byte array.
   * @param intValue
   *          the value to be written.
   * @param offset
   *          the offset where to start writing the value.
   * @param numBytesToBeWritten
   *          number of bytes to be written.
   */
  public static void writeInt(final byte[] bytes, int intValue,
      final int offset, final int numBytesToBeWritten) {
    assert bytes != null;
    // don't need to be big-endian compatible for writing offsets
    for (int index = offset; index < (offset + numBytesToBeWritten);
        ++index, intValue >>>= 8) {
      bytes[index] = (byte) intValue;
    }
  }

  /**
   * @return the index where last encoded byte was written i.e. number of bytes
   *         written = (returned offset - passed offset + 1)
   *
   * @see InternalDataSerializer#writeSignedVL(long, DataOutput)
   */
  public static int writeCompactInt(final byte[] bytes, final int v,
      int offset) {
    long lv = InternalDataSerializer.encodeZigZag64(v);
    for (;;) {
      if ((lv & ~0x7FL) == 0) {
        bytes[offset] = (byte) lv;
        return offset;
      } else {
        bytes[offset] = (byte) ((lv & 0x7F) | 0x80);
        lv >>>= 7;
        offset++;
      }
    }
  }

  /**
   * First byte of {@link #writeCompactInt(byte[], int, int)}
   */
  private static byte getCompactIntByte(final int v) {
    long lv = InternalDataSerializer.encodeZigZag64(v);
    if ((lv & ~0x7FL) == 0) {
      return (byte)lv;
    }
    else {
      return (byte)((lv & 0x7F) | 0x80);
    }
  }

  /**
   * Return the number of bytes required to write an integer using
   * {@link #writeCompactInt(byte[], int, int)}.
   */
  public static int getCompactIntNumBytes(final int v) {
    long lv = InternalDataSerializer.encodeZigZag64(v);
    int numBytes = 1;
    for (;;) {
      if ((lv & ~0x7FL) == 0) {
        return numBytes;
      } else {
        lv >>>= 7;
        numBytes++;
      }
    }
  }

  /**
   * Writes the given number of bytes of an int to the given DataOutput.
   *
   * @param out
   *          the DataOutput.
   * @param intValue
   *          the value to be written.
   * @param numBytesToBeWritten
   *          number of bytes to be written.
   */
  public static void writeInt(final DataOutput out, int intValue,
      final int numBytesToBeWritten) throws IOException {
    assert out != null;
    for (int i = 0; i < numBytesToBeWritten; ++i, intValue >>>= 8) {
      out.writeByte((byte) intValue);
    }
  }

  /**
   * Returns an <code>int</code> value by reading the specified number of bytes
   * provided as an argument starting at offset.
   *
   * @return an <code>int</code> value.
   */
  public static int readInt(final UnsafeWrapper unsafe, long memOffset,
      final int numBytesToBeRead) {
    final long endAddr = memOffset + numBytesToBeRead;

    byte b = unsafe.getByte(memOffset);
    int intValue = (b & 0xFF);
    int shift = 8;
    while (++memOffset < endAddr) {
      b = unsafe.getByte(memOffset);
      intValue |= ((b & 0xFF) << shift);
      shift += 8;
    }
    // fill the sign bit in remaining places in the integer
    if (b < 0) {
      for (int index = numBytesToBeRead; index < (Integer.SIZE >>> 3);
          index++) {
        intValue |= (0xFF << shift);
        shift += 8;
      }
    }
    return intValue;
  }

  /**
   * Returns an <code>int</code> value by reading the specified number of bytes
   * provided as an argument starting at offset.
   *
   * @return an <code>int</code> value.
   */
  public static int readInt(final byte[] bytes, int offset,
      final int numBytesToBeRead) {
    assert bytes != null;
    byte b = bytes[offset];
    int intValue = (b & 0xFF);
    final int endPos = offset + numBytesToBeRead;
    int shift = 8;
    while (++offset < endPos) {
      b = bytes[offset];
      intValue |= ((b & 0xFF) << shift);
      shift += 8;
    }
    // fill the sign bit in remaining places in the integer
    if (b < 0) {
      for (int index = numBytesToBeRead; index < (Integer.SIZE >>> 3); index++) {
        intValue |= (0xFF << shift);
        shift += 8;
      }
    }
    return intValue;
  }

  /**
   * Read an short from off-heap memory.
   *
   * @return the short value read.
   */
  public static short readShort(final long memOffset) {
    if (UnsafeHolder.littleEndian) {
      return Short.reverseBytes(Platform.getShort(null, memOffset));
    } else {
      return Platform.getShort(null, memOffset);
    }
  }

  public static short readShort(final byte[] bytes, int offset) {
    assert bytes != null;

    if (UnsafeHolder.littleEndian) {
      return Short.reverseBytes(Platform.getShort(bytes,
          offset + Platform.BYTE_ARRAY_OFFSET));
    } else {
      return Platform.getShort(bytes, offset + Platform.BYTE_ARRAY_OFFSET);
    }
  }

  /**
   * Read an int from a off-heap memory.
   *
   * @return the int value read.
   */
  public static int readInt(final long memOffset) {
    if (UnsafeHolder.littleEndian) {
      return Integer.reverseBytes(Platform.getInt(null, memOffset));
    } else {
      return Platform.getInt(null, memOffset);
    }
  }

  public static int readInt(final byte[] bytes, int offset) {
    assert bytes != null;

    if (UnsafeHolder.littleEndian) {
      return Integer.reverseBytes(Platform.getInt(bytes,
          offset + Platform.BYTE_ARRAY_OFFSET));
    } else {
      return Platform.getInt(bytes, offset + Platform.BYTE_ARRAY_OFFSET);
    }
  }

  /**
   * @see InternalDataSerializer#readSignedVL(DataInput)
   */
  public static int readCompactInt(long memAddr) {
    int shift = 0;
    long result = 0;
    while (shift < 64) {
      final byte b = Platform.getByte(null, memAddr);
      result |= (long)(b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return (int)InternalDataSerializer.decodeZigZag64(result);
      }
      shift += 7;
      memAddr++;
    }
    throw new GemFireXDRuntimeException("Malformed variable length integer");
  }

  public static int readCompactInt(final byte[] bytes, int offset) {
    int shift = 0;
    long result = 0;
    while (shift < 64) {
      final byte b = bytes[offset];
      result |= (long)(b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return (int)InternalDataSerializer.decodeZigZag64(result);
      }
      shift += 7;
      offset++;
    }
    throw new GemFireXDRuntimeException("Malformed variable length integer");
  }

  /**
   * Write a long into a byte[].
   *
   * @return the number of bytes written.
   */
  public static int writeLong(final byte[] bytes, long longValue, int offset) {
    assert bytes != null;

    // serialize in big-endian format to be compatible with DataOutput.writeInt
    // and also SQLInteger.computeHashCode
    if (UnsafeHolder.littleEndian) {
      Platform.putLong(bytes, offset + Platform.BYTE_ARRAY_OFFSET,
          Long.reverseBytes(longValue));
      return 8; // bytes in long (= Long.SIZE / 8);
    } else {
      Platform.putLong(bytes, offset + Platform.BYTE_ARRAY_OFFSET, longValue);
      return 8; // bytes in long (= Long.SIZE / 8);
    }
  }

  /**
   * Read a long from off-heap memory.
   *
   * @return the long value read.
   */
  public static long readLong(final long memOffset) {
    if (UnsafeHolder.littleEndian) {
      return Long.reverseBytes(Platform.getLong(null, memOffset));
    } else {
      return Platform.getLong(null, memOffset);
    }
  }

  public static long readLong(final byte[] bytes, int offset) {
    assert bytes != null;

    if (UnsafeHolder.littleEndian) {
      return Long.reverseBytes(Platform.getLong(bytes,
          offset + Platform.BYTE_ARRAY_OFFSET));
    } else {
      return Platform.getLong(bytes, offset + Platform.BYTE_ARRAY_OFFSET);
    }
  }

  /**
   * Initialize the RowFormatter with the given ColumnDescriptorList for
   * provided schema/table.
   */
  public RowFormatter(final ColumnDescriptorList cdl, final String schemaName,
      final String tableName, final int schemaVersion,
      final GemFireContainer container) {
    this(cdl, cdl.size(), schemaName, tableName, schemaVersion, container,
        false);
  }

  /**
   * Initialize the RowFormatter with the given ColumnDescriptorList for
   * provided schema/table.
   */
  public RowFormatter(final ColumnDescriptorList cdl, final String schemaName,
      final String tableName, final int schemaVersion,
      final GemFireContainer container, final boolean isTableFormatter) {
    this(cdl, cdl.size(), schemaName, tableName, schemaVersion, container,
        isTableFormatter);
  }

  /**
   * The ColumnDescriptorList has changed, update the extra metadata for given
   * number of columns in the {@link ColumnQueryInfo} array.
   */
  RowFormatter(final ColumnQueryInfo[] cqi, final int schemaVersion,
      final GemFireContainer container) {
    this.schemaVersion = schemaVersion;
    this.nVersionBytes = getVersionNumBytes(schemaVersion);
    this.versionBytes = getVersionBytes(schemaVersion, this.nVersionBytes);
    this.versionByte = getVersionByte(schemaVersion, this.nVersionBytes);
    final int[] colsInfo = initNumVarWidthCols(cqi);
    this.offsetBytes = colsInfo[OFFSET_BYTES_POS];
    this.offsetIsDefault = colsInfo[OFFSET_IS_DEFAULT_POS];
    this.maxVarWidth = colsInfo[MAX_VAR_WIDTH_POS];
    this.numVarWidthColumns = colsInfo[NUM_VAR_WIDTH_COLS_POS];
    this.varDataOffset = colsInfo[VAR_DATA_OFFSET_POS];
    this.container = container;

    final int size = cqi.length;
    final int[] positionMap = new int[size];
    final ColumnDescriptor[] columns = new ColumnDescriptor[size];

    int fixedDataOffset = this.nVersionBytes;
    // Assumes that this.numVarWidthColumns has already been updated
    int nextNegativeVarDataOffsetOffset =
        -(this.offsetBytes * this.numVarWidthColumns);
    int nextLobIndex = 0;
    for (int index = 0; index < size; index++) {
      final ColumnDescriptor cd = cqi[index].getColumnDescriptor();
      columns[index] = cd;
      if (!cd.isLob) {
        final int fixedWidth = cd.fixedWidth;
        if (fixedWidth != -1) {
          // fixed width
          assert fixedWidth > -1;
          positionMap[index] = fixedDataOffset;
          fixedDataOffset += fixedWidth;
        } else {
          // var width
          positionMap[index] = nextNegativeVarDataOffsetOffset;
          nextNegativeVarDataOffsetOffset += this.offsetBytes;
        }
      } else {
        positionMap[index] = ++nextLobIndex;
      }
    }
    this.positionMap = positionMap;
    this.columns = columns;
    this.numLobs = nextLobIndex;
    this.isHeap = container == null || !container.isOffHeap();
    this.metadata = null;
    this.isTableFormatter = false;
    this.isPrimaryKeyFormatter = false;
//    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
//        "RowFormatter#cqi#column[] = " + ArrayUtils.toString(this.columns));
  }

  /**
   * The ColumnDescriptorList has changed, update the extra metadata for given
   * number of columns in the {@link ColumnDescriptorList}.
   */
  public RowFormatter(final ColumnDescriptor[] cdl, final int size,
      final int schemaVersion, final GemFireContainer container,
      final boolean isProjection) {
    this.schemaVersion = schemaVersion;
    this.nVersionBytes = getVersionNumBytes(schemaVersion);
    this.versionBytes = getVersionBytes(schemaVersion, this.nVersionBytes);
    this.versionByte = getVersionByte(schemaVersion, this.nVersionBytes);
    final int[] colsInfo = initNumVarWidthCols(cdl, size);
    this.offsetBytes = colsInfo[OFFSET_BYTES_POS];
    this.offsetIsDefault = colsInfo[OFFSET_IS_DEFAULT_POS];
    this.maxVarWidth = colsInfo[MAX_VAR_WIDTH_POS];
    this.numVarWidthColumns = colsInfo[NUM_VAR_WIDTH_COLS_POS];
    this.varDataOffset = colsInfo[VAR_DATA_OFFSET_POS];
    this.container = container;

    final int[] positionMap = new int[size];
    final ColumnDescriptor[] columns = new ColumnDescriptor[size];

    int fixedDataOffset = this.nVersionBytes;
    // Assumes that this.numVarWidthColumns has already been updated
    int nextNegativeVarDataOffsetOffset =
        -(this.offsetBytes * this.numVarWidthColumns);
    int nextLobIndex = 0;
    for (int index = 0; index < size; index++) {
      final ColumnDescriptor cd = cdl[index];
      columns[index] = isProjection ? cd : cd.cloneObject();
      if (cd.isLob) {
        positionMap[index] = ++nextLobIndex;
      } else {
        final int fixedWidth = cd.fixedWidth;
        if (fixedWidth != -1) {
          // fixed width
          assert fixedWidth > -1;
          positionMap[index] = fixedDataOffset;
          fixedDataOffset += fixedWidth;
        } else {
          // var width
          positionMap[index] = nextNegativeVarDataOffsetOffset;
          nextNegativeVarDataOffsetOffset += this.offsetBytes;
        }
      }
    }
    this.positionMap = positionMap;
    this.columns = columns;
    this.numLobs = nextLobIndex;
    this.isHeap = container == null || !container.isOffHeap();
    if (isProjection) {
      this.metadata = null;
      this.isTableFormatter = false;
    } else {
      if (container != null) {
        this.metadata = getMetaData(container.getSchemaName(),
            container.getTableName(), schemaVersion);
        container.hasLobs = hasLobs();
      }
      this.isTableFormatter = true;
    }
    this.isPrimaryKeyFormatter = false;
//    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
//        "RowFormatter#const1#column[] = " + ArrayUtils.toString(this.columns));
  }

  /**
   * The ColumnDescriptorList has changed, update the extra metadata for given
   * number of columns in the {@link ColumnDescriptorList}.
   */
  private RowFormatter(final ColumnDescriptorList cdl, final int size,
      final String schemaName, final String tableName, final int schemaVersion,
      final GemFireContainer container, final boolean isTableFormatter) {
    this.schemaVersion = schemaVersion;
    this.nVersionBytes = getVersionNumBytes(schemaVersion);
    this.versionBytes = getVersionBytes(schemaVersion, this.nVersionBytes);
    this.versionByte = getVersionByte(schemaVersion, this.nVersionBytes);
    final int[] colsInfo = initNumVarWidthCols(cdl, size);
    this.offsetBytes = colsInfo[OFFSET_BYTES_POS];
    this.offsetIsDefault = colsInfo[OFFSET_IS_DEFAULT_POS];
    this.maxVarWidth = colsInfo[MAX_VAR_WIDTH_POS];
    this.numVarWidthColumns = colsInfo[NUM_VAR_WIDTH_COLS_POS];
    this.varDataOffset = colsInfo[VAR_DATA_OFFSET_POS];
    this.container = container;

    final int[] positionMap = new int[size];
    final ColumnDescriptor[] columns = new ColumnDescriptor[size];

    int fixedDataOffset = this.nVersionBytes;
    // Assumes that this.numVarWidthColumns has already been updated
    int nextNegativeVarDataOffsetOffset =
        -(this.offsetBytes * this.numVarWidthColumns);
    int nextLobIndex = 0;
    for (int index = 0; index < size; index++) {
      final ColumnDescriptor cd = cdl.elementAt(index);
      columns[index] = isTableFormatter ? cd.cloneObject() : cd;
      if (cd.isLob) {
        positionMap[index] = ++nextLobIndex;
      } else {
        final int fixedWidth = cd.fixedWidth;
        if (fixedWidth != -1) {
          // fixed width
          assert fixedWidth > -1;
          positionMap[index] = fixedDataOffset;
          fixedDataOffset += fixedWidth;
        } else {
          // var width
          positionMap[index] = nextNegativeVarDataOffsetOffset;
          nextNegativeVarDataOffsetOffset += this.offsetBytes;
        }
      }
    }
    this.positionMap = positionMap;
    this.columns = columns;
    this.numLobs = nextLobIndex;
    this.isHeap = container == null || !container.isOffHeap();
    this.metadata = getMetaData(schemaName, tableName, schemaVersion);
    this.isTableFormatter = isTableFormatter;
    this.isPrimaryKeyFormatter = false;
    if (isTableFormatter && container != null) {
      container.hasLobs = hasLobs();
    }
//    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
//        "RowFormatter#const2#column[] = " + ArrayUtils.toString(this.columns));
  }

  /**
   * The ColumnDescriptorList has changed, update the extra metadata for given
   * columns in the {@link ColumnDescriptorList} as specified by the
   * FormatableBitSet.
   */
  RowFormatter(final ColumnDescriptorList cdl,
      final FormatableBitSet validColumns, final int schemaVersion,
      final GemFireContainer container) {
    this.schemaVersion = schemaVersion;
    this.nVersionBytes = getVersionNumBytes(schemaVersion);
    this.versionBytes = getVersionBytes(schemaVersion, this.nVersionBytes);
    this.versionByte = getVersionByte(schemaVersion, this.nVersionBytes);
    final int[] colsInfo = initNumVarWidthCols(cdl, validColumns);
    this.offsetBytes = colsInfo[OFFSET_BYTES_POS];
    this.offsetIsDefault = colsInfo[OFFSET_IS_DEFAULT_POS];
    this.maxVarWidth = colsInfo[MAX_VAR_WIDTH_POS];
    this.numVarWidthColumns = colsInfo[NUM_VAR_WIDTH_COLS_POS];
    this.varDataOffset = colsInfo[VAR_DATA_OFFSET_POS];
    this.container = container;

    final int size = validColumns.getNumBitsSet();
    final int[] positionMap = new int[size];
    final ColumnDescriptor[] columns = new ColumnDescriptor[size];

    int fixedDataOffset = this.nVersionBytes;
    // Assumes that this.numVarWidthColumns has already been updated
    int nextNegativeVarDataOffsetOffset =
        -(this.offsetBytes * this.numVarWidthColumns);
    int nextLobIndex = 0;
    for (int colIndex = validColumns.anySetBit(), index = 0; colIndex != -1;
        colIndex = validColumns.anySetBit(colIndex), index++) {
      final ColumnDescriptor cd = cdl.elementAt(colIndex);
      columns[index] = cd;
      if (cd.isLob) {
        positionMap[index] = ++nextLobIndex;
      } else {
        final int fixedWidth = cd.fixedWidth;
        if (fixedWidth != -1) {
          // fixed width
          assert fixedWidth > -1;
          positionMap[index] = fixedDataOffset;
          fixedDataOffset += fixedWidth;
        } else {
          // var width
          positionMap[index] = nextNegativeVarDataOffsetOffset;
          nextNegativeVarDataOffsetOffset += this.offsetBytes;
        }
      }
    }
    this.positionMap = positionMap;
    this.columns = columns;
    this.numLobs = nextLobIndex;
    this.isHeap = container == null || !container.isOffHeap();
    this.metadata = null;
    this.isTableFormatter = false;
    this.isPrimaryKeyFormatter = false;
//    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
//        "RowFormatter#const3#column[] = " + ArrayUtils.toString(this.columns));
  }

  /**
   * The ColumnDescriptorList has changed, update the extra metadata for given
   * columns in the {@link ColumnDescriptorList} as specified by the column
   * list.
   */
  RowFormatter(final ColumnDescriptorList cdl, final int[] validColumns,
      final String schemaName, final String tableName, final int schemaVersion,
      final GemFireContainer container, final boolean isPrimaryKeyFormatter) {
    this.schemaVersion = schemaVersion;
    this.nVersionBytes = getVersionNumBytes(schemaVersion);
    this.versionBytes = getVersionBytes(schemaVersion, this.nVersionBytes);
    this.versionByte = getVersionByte(schemaVersion, this.nVersionBytes);
    final int[] colsInfo = initNumVarWidthCols(cdl, validColumns);
    this.offsetBytes = colsInfo[OFFSET_BYTES_POS];
    this.offsetIsDefault = colsInfo[OFFSET_IS_DEFAULT_POS];
    this.maxVarWidth = colsInfo[MAX_VAR_WIDTH_POS];
    this.numVarWidthColumns = colsInfo[NUM_VAR_WIDTH_COLS_POS];
    this.varDataOffset = colsInfo[VAR_DATA_OFFSET_POS];
    this.container = container;

    final int size = validColumns.length;
    final int[] positionMap = new int[size];
    final ColumnDescriptor[] columns = new ColumnDescriptor[size];

    int fixedDataOffset = this.nVersionBytes;
    // Assumes that this.numVarWidthColumns has already been updated
    int nextNegativeVarDataOffsetOffset =
        -(this.offsetBytes * this.numVarWidthColumns);
    int nextLobIndex = 0;
    int colIndex;
    for (int index = 0; index < size; index++) {
      colIndex = validColumns[index] - 1;
      final ColumnDescriptor cd = cdl.elementAt(colIndex);
      // cloning since it is used by refkey formatter and PK formatter
      columns[index] = cd.cloneObject();
      if (cd.isLob) {
        positionMap[index] = ++nextLobIndex;
      } else {
        final int fixedWidth = cd.fixedWidth;
        if (fixedWidth != -1) {
          // fixed width
          assert fixedWidth > -1;
          positionMap[index] = fixedDataOffset;
          fixedDataOffset += fixedWidth;
        } else {
          // var width
          positionMap[index] = nextNegativeVarDataOffsetOffset;
          nextNegativeVarDataOffsetOffset += this.offsetBytes;
        }
      }
    }
    this.positionMap = positionMap;
    this.columns = columns;
    this.numLobs = nextLobIndex;
    this.isHeap = container == null || !container.isOffHeap();
    this.metadata = getMetaData(schemaName, tableName, schemaVersion);
    this.isTableFormatter = false;
    this.isPrimaryKeyFormatter = isPrimaryKeyFormatter;
//    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
//        "RowFormatter#const4#column[] = " + ArrayUtils.toString(this.columns));
  }

  /**
   * Get the fixed width, variable width and LOB column positions for this
   * formatter.
   */
  public final void getColumnPositions(final TIntArrayList fixedColumns,
      final TIntArrayList varColumns, final TIntArrayList lobColumns,
      final TIntArrayList allColumns, final TIntArrayList allColumnsWithLobs) {
    for (final ColumnDescriptor cd : this.columns) {
      final int pos = cd.getPosition();
      if (!cd.isLob) {
        if (cd.fixedWidth != -1) {
          // fixed width
          fixedColumns.add(pos);
        } else {
          // var width
          varColumns.add(pos);
        }
        allColumns.add(pos);
      } else {
        lobColumns.add(pos);
      }
      allColumnsWithLobs.add(pos);
    }
  }

  private EmbedResultSetMetaData getMetaData(final String schemaName,
      final String tableName, final int schemaVersion) {
    final int size = getNumColumns();
    final ResultColumnDescriptor[] gcds = new GenericColumnDescriptor[size];
    for (int index = 0; index < size; ++index) {
      final ColumnDescriptor cd = this.columns[index];
      gcds[index] = new GenericColumnDescriptor(cd.getColumnName(), schemaName,
          tableName, cd.getPosition(), cd.getType(), cd.updatableByCursor(),
          cd.isAutoincrement());
    }
    return new EmbedResultSetMetaData(gcds, true, schemaVersion);
  }

  /**
   * Get the metadata represented by this RowFormatter. As an optimization this
   * currently returns null for projection and other cases where the
   * RowFormatter is created on the fly and not used otherwise. Only assume that
   * this returns non-null for the full table and primary key RowFormatter.
   */
  public final TableMetaData getMetaData() {
    return this.metadata;
  }

  /** returns true if this RowFormatter is for the complete table */
  public final boolean isTableFormatter() {
    return this.isTableFormatter;
  }

  /** returns true if this RowFormatter is for the primary key of a table */
  public final boolean isPrimaryKeyFormatter() {
    return this.isPrimaryKeyFormatter;
  }

  /** Get the type descriptor for the column at given 1-based position. */
  public final DataTypeDescriptor getType(final int columnPosition) {
    return this.columns[columnPosition - 1].columnType;
  }

  /** Get the ColumnDescriptor at the given zero-based index. */
  public final ColumnDescriptor getColumnDescriptor(int index) {
    return this.columns[index];
  }

  /**
   * Fill in the DataValueDescriptors for specified columns in the table.
   *
   * @param bytes
   *          - byte array form of the row
   * @param dvds
   *          DVD array to fill in with fetched DVDs
   * @param validColumns
   *          - columns of interest, if null then all columns will be returned.
   */
  public final void getColumns(final byte[] bytes,
      final DataValueDescriptor[] dvds, final int[] validColumns)
      throws StandardException {
    DataValueDescriptor dvd, newDVD;
    if (validColumns != null) {
      final int nCols = validColumns.length;
      for (int index = 0; index < nCols; index++) {
        final int logicalPosition = validColumns[index];
        dvd = dvds[index];
        if (logicalPosition > 0) {
          newDVD = getColumn(logicalPosition, bytes);
          if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
            dvds[index] = newDVD;
          } else {
            dvd.setValue(newDVD);
          }
        } else {
          dvds[index] = null;
        }
      }
    } else {
      for (int column = 1; column <= getNumColumns(); ++column) {
        dvd = dvds[column - 1];
        newDVD = getColumn(column, bytes);
        if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
          dvds[column - 1] = newDVD;
        } else {
          dvd.setValue(newDVD);
        }
      }
    }
  }

  /**
   * Fill in the DataValueDescriptors for specified columns in the table.
   *
   * @param bs
   *          off-heap byte source of the row
   * @param dvds
   *          DVD array to fill in with fetched DVDs
   * @param validColumns
   *          columns of interest, if null then all columns will be returned.
   */
  public final void getColumns(@Unretained final OffHeapRow bs,
      final DataValueDescriptor[] dvds, final int[] validColumns)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bs.getLength();
    final long memAddr = bs.getUnsafeAddress(0, bytesLen);

    DataValueDescriptor dvd, newDVD;
    if (validColumns != null) {
      final int nCols = validColumns.length;
      for (int index = 0; index < nCols; index++) {
        final int logicalPosition = validColumns[index];
        dvd = dvds[index];
        if (logicalPosition > 0) {
          newDVD = getColumn(logicalPosition, unsafe, memAddr, bytesLen, bs);
          if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
            dvds[index] = newDVD;
          } else {
            dvd.setValue(newDVD);
          }
        } else {
          dvds[index] = null;
        }
      }
    } else {
      for (int column = 1; column <= getNumColumns(); ++column) {
        dvd = dvds[column - 1];
        newDVD = getColumn(column, unsafe, memAddr, bytesLen, bs);
        if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
          dvds[column - 1] = newDVD;
        } else {
          dvd.setValue(newDVD);
        }
      }
    }
  }

  /**
   * Fill in the DataValueDescriptors for specified columns in the table.
   *
   * @param byteArrays
   *          - byte arrays form of the row
   * @param dvds
   *          DVD array to fill in with fetched DVDs
   * @param validColumns
   *          - columns of interest, if null then all columns will be returned.
   */
  public final void getColumns(final byte[][] byteArrays,
      final DataValueDescriptor[] dvds, final int[] validColumns)
      throws StandardException {
    DataValueDescriptor dvd, newDVD;
    if (validColumns != null) {
      final int nCols = validColumns.length;
      for (int index = 0; index < nCols; index++) {
        final int logicalPosition = validColumns[index];
        dvd = dvds[index];
        if (logicalPosition > 0) {
          newDVD = getColumn(logicalPosition, byteArrays);
          if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
            dvds[index] = newDVD;
          } else {
            dvd.setValue(newDVD);
          }
        } else {
          dvds[index] = null;
        }
      }
    } else {
      for (int column = 1; column <= getNumColumns(); ++column) {
        dvd = dvds[column - 1];
        newDVD = getColumn(column, byteArrays);
        if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
          dvds[column - 1] = newDVD;
        } else {
          dvd.setValue(newDVD);
        }
      }
    }
  }

  /**
   * Fill in the DataValueDescriptors for specified columns in the table.
   *
   * @param byteArrays
   *          - byte arrays form of the row
   * @param dvds
   *          DVD array to fill in with fetched DVDs
   * @param validColumns
   *          - columns of interest, if null then all columns will be returned.
   */
  public final void getColumns(@Unretained final OffHeapRowWithLobs byteArrays,
      final DataValueDescriptor[] dvds, final int[] validColumns)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen0th = byteArrays.getLength();
    final long memAddr0th = byteArrays.getUnsafeAddress(0, bytesLen0th);

    DataValueDescriptor dvd, newDVD;
    if (validColumns != null) {
      final int nCols = validColumns.length;
      for (int index = 0; index < nCols; index++) {
        final int logicalPosition = validColumns[index];
        dvd = dvds[index];
        if (logicalPosition > 0) {
          newDVD = getColumn(logicalPosition, unsafe, memAddr0th, bytesLen0th,
              byteArrays);
          if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
            dvds[index] = newDVD;
          } else {
            dvd.setValue(newDVD);
          }
        } else {
          dvds[index] = null;
        }
      }
    } else {
      final int nCols = getNumColumns();
      for (int column = 1; column <= nCols; ++column) {
        dvd = dvds[column - 1];
        newDVD = getColumn(column, unsafe, memAddr0th, bytesLen0th, byteArrays);
        if (dvd == null || dvd.getTypeFormatId() == newDVD.getTypeFormatId()) {
          dvds[column - 1] = newDVD;
        } else {
          dvd.setValue(newDVD);
        }
      }
    }
  }

  /**
   * Process the qualifier list on the row, return true if it qualifies.
   * <p>
   * A two dimensional array is to be used to pass around a AND's and OR's in
   * conjunctive normal form. The top slot of the 2 dimensional array is
   * optimized for the more frequent where no OR's are present. The first array
   * slot is always a list of AND's to be treated as described above for single
   * dimensional AND qualifier arrays. The subsequent slots are to be treated as
   * AND'd arrays or OR's. Thus the 2 dimensional array qual[][] argument is to
   * be treated as the following, note if qual.length = 1 then only the first
   * array is valid and it is and an array of and clauses:
   *
   * (qual[0][0] and qual[0][0] ... and qual[0][qual[0].length - 1]) and
   * (qual[1][0] or qual[1][1] ... or qual[1][qual[1].length - 1]) and
   * (qual[2][0] or qual[2][1] ... or qual[2][qual[2].length - 1]) ... and
   * (qual[qual.length - 1][0] or qual[1][1] ... or qual[1][2])
   *
   *
   * @return true if the row qualifies.
   *
   * @param row
   *          The row being qualified.
   * @param qual_list
   *          2 dimensional array representing conjunctive normal form of simple
   *          qualifiers.
   *
   * @exception StandardException
   *              Standard exception policy.
   *
   * @see RowUtil#qualifyRow This version takes ExecRow instead of
   *      DataValueDescriptor[]
   **/
  public static boolean qualifyRow(final ExecRow row,
      final boolean byteArrayStore, final Qualifier[][] qual_list)
      throws StandardException {

    boolean row_qualifies = true;

    assert row != null;

    // First do the qual[0] which is an array of qualifer terms.

    // routine should not be called if there is no qualifier
    assert qual_list != null;
    assert qual_list.length > 0;

    for (int i = 0; i < qual_list[0].length; i++) {
      // process each AND clause

      // process each OR clause.

      final Qualifier q = qual_list[0][i];

      // Get the column from the possibly partial row, of the
      // q.getColumnId()'th column in the full row.
      final DataValueDescriptor qual = q.getOrderable();
      row_qualifies = qual.compare(q.getOperator(), row, byteArrayStore,
          q.getColumnId() + 1, q.getOrderedNulls(), q.getUnknownRV());

      if (q.negateCompareResult()) {
        row_qualifies = !row_qualifies;
      }

      // Once an AND fails the whole Qualification fails - do a return!
      if (!row_qualifies) {
        return (false);
      }
    }

    // all the qual[0] and terms passed, now process the OR clauses

    for (int and_idx = 1; and_idx < qual_list.length; and_idx++) {
      // loop through each of the "and" clause.

      row_qualifies = false;

      // Each OR clause must be non-empty.
      assert qual_list[and_idx].length > 0;

      for (int or_idx = 0; or_idx < qual_list[and_idx].length; or_idx++) {
        // Apply one qualifier to the row.
        final Qualifier q = qual_list[and_idx][or_idx];
        final int col_id = q.getColumnId();

        assert col_id < row.nColumns():
          "Qualifier is referencing a column not in the row.";

        // Get the column from the possibly partial row, of the
        // q.getColumnId()'th column in the full row.
        final DataValueDescriptor qual = q.getOrderable();

        assert row.getColumn(q.getColumnId() + 1) != null : "1:row = "
            + RowUtil.toString(row.getRowArray()) + "row.length = "
            + row.nColumns() + ";q.getColumnId() = " + q.getColumnId();

        row_qualifies = qual.compare(q.getOperator(), row, byteArrayStore,
            q.getColumnId() + 1, q.getOrderedNulls(), q.getUnknownRV());

        if (q.negateCompareResult()) {
          row_qualifies = !row_qualifies;
        }

        // SanityManager.DEBUG_PRINT("StoredPage.qual", "processing qual[" +
        // and_idx + "][" + or_idx + "] = " + qual_list[and_idx][or_idx] );

        // SanityManager.DEBUG_PRINT("StoredPage.qual", "value = " +
        // row_qualifies);

        // processing "OR" clauses, so as soon as one is true, break
        // to go and process next AND clause.
        if (row_qualifies) {
          break;
        }

      }

      // The qualifier list represented a set of "AND'd"
      // qualifications so as soon as one is false processing is done.
      if (!row_qualifies) {
        break;
      }
    }

    return (row_qualifies);
  }

  /**
   * Process the qualifier list on the index/region key, return true if it
   * qualifies.
   * <p>
   * A two dimensional array is to be used to pass around a AND's and OR's in
   * conjunctive normal form. The top slot of the 2 dimensional array is
   * optimized for the more frequent where no OR's are present. The first array
   * slot is always a list of AND's to be treated as described above for single
   * dimensional AND qualifier arrays. The subsequent slots are to be treated as
   * AND'd arrays or OR's. Thus the 2 dimensional array qual[][] argument is to
   * be treated as the following, note if qual.length = 1 then only the first
   * array is valid and it is and an array of and clauses:
   *
   * (qual[0][0] and qual[0][0] ... and qual[0][qual[0].length - 1]) and
   * (qual[1][0] or qual[1][1] ... or qual[1][qual[1].length - 1]) and
   * (qual[2][0] or qual[2][1] ... or qual[2][qual[2].length - 1]) ... and
   * (qual[qual.length - 1][0] or qual[1][1] ... or qual[1][2])
   *
   *
   * @return true if the key qualifies.
   *
   * @param key
   *          The index/region key being qualified.
   * @param qual_list
   *          2 dimensional array representing conjunctive normal form of simple
   *          qualifiers.
   *
   * @exception StandardException
   *              Standard exception policy.
   *
   * @see RowUtil#qualifyRow This version takes CompactCompositeKey instead of
   *      DataValueDescriptor[]
   **/
  public static boolean qualifyRow(final CompactCompositeKey key,
      final Qualifier[][] qual_list) throws StandardException {
    boolean key_qualifies = true;

    assert key != null;

    // First do the qual[0] which is an array of qualifer terms.

    // routine should not be called if there is no qualifier
    assert qual_list != null;
    assert qual_list.length > 0;

    for (int i = 0; i < qual_list[0].length; i++) {
      // process each AND clause

      // process each OR clause.

      final Qualifier q = qual_list[0][i];

      // Get the column from the possibly partial key, of the
      // q.getColumnId()'th column in the full key.
      final DataValueDescriptor qual = q.getOrderable();
      key_qualifies = qual.compare(q.getOperator(), key, q.getColumnId() + 1,
          q.getOrderedNulls(), q.getUnknownRV());

      if (q.negateCompareResult()) {
        key_qualifies = !key_qualifies;
      }

      // Once an AND fails the whole Qualification fails - do a return!
      if (!key_qualifies) {
        return (false);
      }
    }

    // all the qual[0] and terms passed, now process the OR clauses

    for (int and_idx = 1; and_idx < qual_list.length; and_idx++) {
      // loop through each of the "and" clause.

      key_qualifies = false;

      // Each OR clause must be non-empty.
      assert qual_list[and_idx].length > 0;

      for (int or_idx = 0; or_idx < qual_list[and_idx].length; or_idx++) {
        // Apply one qualifier to the key.
        final Qualifier q = qual_list[and_idx][or_idx];
        final int col_id = q.getColumnId();

        assert col_id < key.nCols():
          "Qualifier is referencing a column not in the key.";

        // Get the column from the possibly partial key, of the
        // q.getColumnId()'th column in the full key.
        final DataValueDescriptor qual = q.getOrderable();

        assert key.getKeyColumn(q.getColumnId()) != null : "1:key = " + key
            + "row.length = " + key.nCols() + ";q.getColumnId() = "
            + q.getColumnId();

        key_qualifies = qual.compare(q.getOperator(), key, q.getColumnId() + 1,
            q.getOrderedNulls(), q.getUnknownRV());

        if (q.negateCompareResult()) {
          key_qualifies = !key_qualifies;
        }

        // SanityManager.DEBUG_PRINT("StoredPage.qual", "processing qual[" +
        // and_idx + "][" + or_idx + "] = " + qual_list[and_idx][or_idx] );

        // SanityManager.DEBUG_PRINT("StoredPage.qual", "value = " +
        // key_qualifies);

        // processing "OR" clauses, so as soon as one is true, break
        // to go and process next AND clause.
        if (key_qualifies) {
          break;
        }
      }

      // The qualifier list represented a set of "AND'd"
      // qualifications so as soon as one is false processing is done.
      if (!key_qualifies) {
        break;
      }
    }

    return (key_qualifies);
  }

  /**
   * Generates a byte array from DVD array for storing the row as byte array.
   * The byte array contains the columns in the table in a physical ordering
   * that may be different from the logical order. Fixed length columns are
   * stored first and then variable length columns. Nullable columns and columns
   * with default values are stored as variable length columns, with special
   * offset values and no field data.
   *
   * @param dvds
   *          DVD array for all columns in the table in the logical order
   *          specified by the user. (Logical order can be different from the
   *          physical order)
   * @return the byte array corresponding to the columns in the table, or null
   *         if dvds is empty or null. Field values are in physical order.
   */
  public final byte[] generateBytes(final DataValueDescriptor[] dvds)
      throws StandardException {
    assert !hasLobs() : "unexpected generateBytes for row having LOBs: "
        + RowUtil.toString(dvds);

    final int len = computeRowLength(dvds);
    final byte[] bytes = new byte[len];
    // write the version, if any, first
    writeVersion(bytes);
    // offset to next var data field
    int varDataOffset = this.varDataOffset;
    for (int index = 0; index < dvds.length; ++index) {
      varDataOffset += generateColumn(index, dvds[index], this.columns[index],
          bytes, varDataOffset);
    }
    return bytes;
  }

  /**
   * Generates a byte array from single column DVD row for storing the row as
   * byte array. The byte array contains the columns in the table in a physical
   * ordering that may be different from the logical order. Fixed length columns
   * are stored first and then variable length columns. Nullable columns and
   * columns with default values are stored as variable length columns, with
   * special offset values and no field data.
   *
   * @param dvd
   *          DVD for single column table
   *
   * @return the byte array corresponding to the columns in the table, or null
   *         if dvds is empty or null. Field values are in physical order.
   */
  public final byte[] generateBytes(final DataValueDescriptor dvd)
      throws StandardException {
    assert !hasLobs() : "unexpected generateBytes for row having LOBs: " + dvd;

    final int len = computeRowLength(dvd);
    final byte[] bytes = new byte[len];
    // write the version, if any, first
    writeVersion(bytes);
    // offset to next var data field
    generateColumn(0, dvd, this.columns[0], bytes, varDataOffset);
    return bytes;
  }

  public final byte[] generateBytesWithIdenticalDVDTypes(
      final DataValueDescriptor[] dvds) throws StandardException {
    // in case the incoming DVDs do not match the PK column types, then try to
    // create new PK column DVDs with same value
    DataValueDescriptor[] newDVDs = null;
    for (int index = 0; index < dvds.length; index++) {
      final DataValueDescriptor dvd = dvds[index];
      final DataTypeDescriptor dtd = this.columns[index].columnType;
      if (dtd.getDVDTypeFormatId() != dvd.getTypeFormatId()) {
        final DataValueDescriptor newDVD = dtd.getNull();
        newDVD.setValue(dvd);
        if (newDVDs == null) {
          newDVDs = dvds.clone();
        }
        newDVDs[index] = newDVD;
      }
    }
    return generateBytes(newDVDs == null ? dvds : newDVDs);
  }

  public final byte[] generateBytesWithIdenticalDVDType(
      final DataValueDescriptor dvd) throws StandardException {
    // in case the incoming DVD does not match the PK column type, then try to
    // create new PK column DVD with same value
    final DataTypeDescriptor dtd = this.columns[0].columnType;
    if (dtd.getDVDTypeFormatId() != dvd.getTypeFormatId()) {
      final DataValueDescriptor newDVD = dtd.getNull();
      newDVD.setValue(dvd);
      return generateBytes(newDVD);
    }
    return generateBytes(dvd);
  }

  /**
   * Write the schema version at the start of byte array. The special version 0
   * indicates unversioned RowFormatter when nothing is written.
   */
  public final void writeVersion(final byte[] bytes) {
    if (this.schemaVersion != 0) {
      assert this.nVersionBytes > 0 : "unexpected versionBytes="
          + this.nVersionBytes + " for version=" + this.schemaVersion;

      // version is written at the start of byte array
      if (this.versionBytes == null) {
        bytes[0] = this.versionByte;
      }
      else {
        System.arraycopy(this.versionBytes, 0, bytes, 0, this.nVersionBytes);
      }
    }
  }

  /**
   * Write the schema version on the given {@link DataOutput}. The special
   * version 0 indicates unversioned RowFormatter when nothing is written.
   */
  public final void writeVersion(final DataOutput out) throws IOException {
    if (this.schemaVersion != 0) {
      assert this.nVersionBytes > 0 : "unexpected versionBytes="
          + this.nVersionBytes + " for version=" + this.schemaVersion;

      if (this.versionBytes == null) {
        out.writeByte(this.versionByte);
      }
      else {
        out.write(this.versionBytes, 0, this.nVersionBytes);
      }
    }
  }

  /**
   * Read the schema version from the start of byte array.
   */
  public static int readVersion(final byte[] bytes) {
    // version is written at the start of byte array
    final int schemaVersion = readCompactInt(bytes, 0);
    // special token TOKEN_RECOVERY_VERSION is used for upgrade from old Oplogs
    assert schemaVersion >= 0 || schemaVersion == TOKEN_RECOVERY_VERSION :
      "unexpected schemaVersion=" + schemaVersion + " for RF#readVersion";
    return schemaVersion;
  }

  /**
   * Read the schema version from the start of byte array.
   */
  static int readVersion(final long memAddr) {
    // version is written at the start of byte array
    final int schemaVersion = readCompactInt(memAddr);
    // special token TOKEN_RECOVERY_VERSION is used for upgrade from old Oplogs
    assert schemaVersion >= 0 || schemaVersion == TOKEN_RECOVERY_VERSION :
      "unexpected schemaVersion=" + schemaVersion + " for RF#readVersion";
    return schemaVersion;
  }

  /**
   * Convert older format serialized rows to the newer format. The pre 1.1
   * versions did not have schema version. Now using TOKEN_RECOVERY_VERSION as
   * an indicator that is placed in the byte[] and the row is assumed to be of
   * the last version recovered from disk.
   */
  public static byte[] convertPre11Row(byte[] row) {
    if (row != null) {
      final byte[] newRow = new byte[row.length + TOKEN_RECOVERY_VERSION_BYTES];
      writeCompactInt(newRow, TOKEN_RECOVERY_VERSION, 0);
      System
          .arraycopy(row, 0, newRow, TOKEN_RECOVERY_VERSION_BYTES, row.length);
      return newRow;
    } else {
      return null;
    }
  }

  /**
   * Convert older format serialized rows to the newer format. The pre 1.1
   * versions did not have schema version. Now using TOKEN_RECOVERY_VERSION as
   * an indicator that is placed in the byte[] and the row is assumed to be of
   * the last version recovered from disk.
   */
  public static byte[] convertPre11Row(ByteArrayDataInput in)
      throws IOException {
    final int len = InternalDataSerializer.readArrayLength(in);
    if (len >= 0) {
      final byte[] newRow = new byte[len + TOKEN_RECOVERY_VERSION_BYTES];
      writePre11Row(in, newRow, len);
      return newRow;
    } else {
      return null;
    }
  }

  private static void writePre11Row(ByteArrayDataInput in, byte[] newRow,
      int len) throws IOException {
    writeCompactInt(newRow, TOKEN_RECOVERY_VERSION, 0);
    in.readFully(newRow, TOKEN_RECOVERY_VERSION_BYTES, len);
  }

  /**
   * Convert older format serialized rows to the newer format. The pre 1.1
   * versions did not have schema version. Now using TOKEN_RECOVERY_VERSION as
   * an indicator that is placed in the byte[] and the row is assumed to be of
   * the last version recovered from disk.
   */
  public static byte[][] convertPre11RowWithLobs(ByteArrayDataInput in)
      throws IOException {
    final int nlen = InternalDataSerializer.readArrayLength(in);
    if (nlen > 0) {
      final byte[][] newRow = new byte[nlen][];
      newRow[0] = convertPre11Row(in);
      for (int i = 1; i < nlen; i++) {
        newRow[i] = InternalDataSerializer.readByteArray(in);
      }
      return newRow;
    } else {
      return null;
    }
  }

  /**
   * Convert older format serialized rows to the newer format. The pre 1.1
   * versions did not have schema version. Now using TOKEN_RECOVERY_VERSION as
   * an indicator that is placed in the byte[] and the row is assumed to be of
   * the last version recovered from disk.
   *
   * This version serializes directly to bytes with minimal copying.
   */
  public static byte[] convertPre11RowWithLobsToBytes(ByteArrayDataInput in,
      byte dscode) throws IOException {
    final int pos = in.position();
    final int nlen = InternalDataSerializer.readArrayLength(in);
    // get total length first
    if (nlen > 0) {
      final int mark = in.position();
      int totalLen = 1 /* for DSCODE */+ TOKEN_RECOVERY_VERSION_BYTES;
      int len;
      for (int i = 0; i < nlen; i++) {
        len = InternalDataSerializer.readArrayLength(in);
        if (len > 0) {
          in.skipBytes(len);
        }
      }
      totalLen += in.position() - pos;
      // rewind to after nlen for reading
      in.setPosition(mark);
      final byte[] newRow = new byte[totalLen];
      int offset = 0;
      // write DSCODE first
      newRow[offset++] = dscode;
      // next write nlen
      offset = InternalDataSerializer.writeArrayLength(nlen, newRow, offset);
      for (int i = 0; i < nlen; i++) {
        len = InternalDataSerializer.readArrayLength(in);
        // first row has extra length for TOKEN_RECOVERY_VERSION
        offset = InternalDataSerializer.writeArrayLength(i != 0 ? len : len
            + TOKEN_RECOVERY_VERSION_BYTES, newRow, offset);
        // write TOKEN_RECOVERY_VERSION for first byte[]
        // assumption is that TOKEN_RECOVERY_VERSION will fit in a single byte
        if (i == 0) {
          assert TOKEN_RECOVERY_VERSION_BYTES == 1 : TOKEN_RECOVERY_VERSION_BYTES;
          newRow[offset++] = (byte) InternalDataSerializer
              .encodeZigZag64(TOKEN_RECOVERY_VERSION);
        }
        if (len > 0) {
          in.readFully(newRow, offset, len);
          offset += len;
        }
      }
      return newRow;
    } else {
      return null;
    }
  }

  /**
   * Used when at least one of the dvds is a LOB type.
   */
  public final byte[][] generateByteArrays(final DataValueDescriptor[] dvds)
      throws StandardException {
    assert hasLobs() : "unexpected generateByteArrays for row not having LOBs: "
        + RowUtil.toString(dvds);

    final byte[][] byteArrays = new byte[this.numLobs + 1][];
    final int len = computeRowLength(dvds);
    final byte[] bytes = new byte[len];
    // write the version, if any, first
    writeVersion(bytes);
    // offset to next var data field
    int varDataOffset = this.varDataOffset;
    // index of the LOB byte[]
    int lobIndex = 1;
    for (int index = 0; index < dvds.length; ++index) {
      final ColumnDescriptor cd = this.columns[index];
      if (!cd.isLob) {
        varDataOffset += generateColumn(index, dvds[index], cd, bytes,
            varDataOffset);
      } else {
        byteArrays[lobIndex++] = generateLobColumn(dvds[index], cd);
      }
    }
    byteArrays[0] = bytes;
    return byteArrays;
  }

  /**
   * Return either a byte[] or a byte[][] depending on the presence of a LOB
   * column.
   */
  public final Object generateRowData(final DataValueDescriptor[] dvds)
      throws StandardException {
    if (this.numLobs == 0) {
      return generateBytes(dvds);
    }
    return generateByteArrays(dvds);
  }

  /**
   * Generate byte[][] for LOBs for the case of ALTER TABLE causing byte[]
   * storage going to byte[][] for an old row stored as a byte[] copying default
   * bytes
   */
  public final byte[][] createByteArraysWithDefaultLobs(final byte[] rowBytes) {
    // create a byte[][] on the fly with default values in LOB columns
    final byte[][] rowByteArrays = new byte[this.numLobs + 1][];
    final ColumnDescriptor[] cols = this.columns;
    rowByteArrays[0] = rowBytes;
    int i = 0;
    for (ColumnDescriptor cd : cols) {
      if (cd.isLob) {
        rowByteArrays[++i] = cd.columnDefaultBytes;
      }
    }
    return rowByteArrays;
  }

  /**
   * Add a column to this RowFormatter as part of ALTER TABLE. Note that this
   * will only adjust the lists for the new column marking it to have a default
   * value, and not change the formatting of this RowFormatter. For the actual
   * new formatting, a new RowFormatter with updated version will be used.
   */
  public final void addColumn(final ColumnDescriptor newCD) {
    final int nCols = this.positionMap.length;
    final int[] newPosMap = new int[nCols + 1];
    final ColumnDescriptor[] newColumns = new ColumnDescriptor[nCols + 1];
    System.arraycopy(this.positionMap, 0, newPosMap, 0, nCols);
    System.arraycopy(this.columns, 0, newColumns, 0, nCols);
    /*
     * Special value in the positionMap that indicates default value. Note that
     * 0 is no longer a valid value in table position map since the version
     * information is always at the very start.
     */
    newPosMap[nCols] = 0;
    newColumns[nCols] = newCD.cloneObject();
    this.positionMap = newPosMap;
    this.columns = newColumns;
    // also adjust numLobs
    if (newCD.isLob) {
      this.numLobs++;
    }
    // update metadata
    if (this.metadata != null) {
      this.metadata = getMetaData(this.container.getSchemaName(),
          this.container.getTableName(),
          this.container.getCurrentSchemaVersion());
    }
  }

  /**
   * Drop a column from this RowFormatter as part of ALTER TABLE. Note that this
   * will only adjust the lists for the dropped column removing the column, and
   * not change the formatting of this RowFormatter. For the actual new
   * formatting, a new RowFormatter with updated version will be used.
   */
  public final void dropColumn(final int columnPos) {
    final int nCols = this.positionMap.length;
    final int[] newPosMap = new int[nCols - 1];
    final ColumnDescriptor[] newColumns = new ColumnDescriptor[nCols - 1];
    if (columnPos > 1) {
      System.arraycopy(this.positionMap, 0, newPosMap, 0, columnPos - 1);
      System.arraycopy(this.columns, 0, newColumns, 0, columnPos - 1);
      if (columnPos < nCols) {
        System.arraycopy(this.positionMap, columnPos, newPosMap, columnPos - 1,
            nCols - columnPos);
        System.arraycopy(this.columns, columnPos, newColumns, columnPos - 1,
            nCols - columnPos);
      }
    } else {
      System.arraycopy(this.positionMap, 1, newPosMap, 0, nCols - 1);
      System.arraycopy(this.columns, 1, newColumns, 0, nCols - 1);
    }
    // also adjust numLobs
    if (this.columns[columnPos - 1].isLob) {
      this.numLobs--;
    }
    this.positionMap = newPosMap;
    this.columns = newColumns;
    dropColumnAdjustPositionInCD(columnPos);
    // update metadata
    if (this.metadata != null) {
      this.metadata = getMetaData(this.container.getSchemaName(),
          this.container.getTableName(),
          this.container.getCurrentSchemaVersion());
    }
  }

  /**
   * Adjust the column positions in cached ColumnDescriptors for a column drop.
   */
  public final void dropColumnAdjustPositionInCD(final int columnPos) {
    int pos;
    for (ColumnDescriptor cd : this.columns) {
      pos = cd.getPosition();
      if (pos > columnPos) {
        cd.setPosition(pos - 1);
      }
    }
  }

  /**
   * Return true if the row contains at least one LOB data type.
   */
  public final boolean hasLobs() {
    return this.numLobs > 0;
  }

  /**
   * Return the number of LOB type columns in a row.
   */
  public final int numLobs() {
    return this.numLobs;
  }

  /////////////////// PACKAGE METHODS ///////////////////

  /**
   * Set specified columns with srcValues and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues, final byte[] rowToChange,
      final RowFormatter currentFormat) throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    if (columns == null) {
      // complete row
      if (isLatestSchema) {
        return generateBytes(srcValues);
      } else {
        return currentFormat.generateBytes(srcValues);
      }
    }

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      if (isLatestSchema) {
        return generateColumns(columns, srcValues);
      } else {
        return currentFormat.generateColumns(columns, srcValues);
      }
    }

    final int bitSetSize = columns.getLength();
    int newRowLength;
    if (isLatestSchema) {
      // compute new row length by adding up the diffs between length of new
      // value and length of current value in the rowToChangeBytes
      newRowLength = rowToChange.length; // start with old and diff
      for (int colIndex = columns.anySetBit(); colIndex != -1;
          colIndex = columns.anySetBit(colIndex)) {
        final ColumnDescriptor cd = this.columns[colIndex];
        final int oldColWidth = getColumnWidth(colIndex, rowToChange, cd);
        int newColWidth = cd.fixedWidth;
        if (newColWidth == -1) {
          newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
        }
        newRowLength += newColWidth - oldColWidth;
      }
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newRowLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        int newColWidth;
        if (colIndex < bitSetSize && columns.isSet(colIndex)) {
          newColWidth = cd.fixedWidth;
          if (newColWidth == -1) {
            newColWidth = currentFormat.getVarColumnWidth(srcValues[colIndex],
                cd);
            newColWidth += currentFormat.getNumOffsetBytes();
          }
        } else {
          newColWidth = getTotalColumnWidth(colIndex, rowToChange, cd,
              currentFormat);
        }
        newRowLength += newColWidth;
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    currentFormat.writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      if (index < bitSetSize && columns.isSet(index)) {
        varDataOffset += currentFormat.generateColumn(index, srcValues[index],
            currentFormat.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += currentFormat.copyColumn(index, rowToChange, this,
            newBytes, index, varDataOffset);
      }
    }

    return newBytes;
  }

  /**
   * Set specified columns with srcValues and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final RowFormatter currentFormat,
      final boolean isLatestSchema) throws StandardException {

    final int bitSetSize = columns.getLength();
    int newRowLength;

    if (isLatestSchema) {
      // compute new row length by adding up the diffs between length of new
      // value and length of current value in the rowToChangeBytes
      newRowLength = bytesLen; // start with old and diff
      for (int colIndex = columns.anySetBit(); colIndex != -1;
          colIndex = columns.anySetBit(colIndex)) {
        final ColumnDescriptor cd = this.columns[colIndex];
        final int oldColWidth = getColumnWidth(colIndex, unsafe, memAddr,
            bytesLen, cd);
        int newColWidth = cd.fixedWidth;
        if (newColWidth == -1) {
          newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
        }
        newRowLength += newColWidth - oldColWidth;
      }
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newRowLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        int newColWidth;
        if (colIndex < bitSetSize && columns.isSet(colIndex)) {
          newColWidth = cd.fixedWidth;
          if (newColWidth == -1) {
            newColWidth = currentFormat.getVarColumnWidth(srcValues[colIndex],
                cd);
            newColWidth += currentFormat.getNumOffsetBytes();
          }
        } else {
          newColWidth = getTotalColumnWidth(colIndex, unsafe, memAddr,
              bytesLen, cd, currentFormat);
        }
        newRowLength += newColWidth;
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    currentFormat.writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      if (index < bitSetSize && columns.isSet(index)) {
        varDataOffset += currentFormat.generateColumn(index, srcValues[index],
            currentFormat.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += currentFormat.copyColumn(index, unsafe, memAddr, bytesLen,
            this, newBytes, index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with srcValues and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte source of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapRow rowToChange, final RowFormatter currentFormat)
      throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    if (columns == null) {
      // complete row
      if (isLatestSchema) {
        return generateBytes(srcValues);
      }
      else {
        return currentFormat.generateBytes(srcValues);
      }
    }

    assert columns.getNumBitsSet() > 0: "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      if (isLatestSchema) {
        return generateColumns(columns, srcValues);
      }
      else {
        return currentFormat.generateColumns(columns, srcValues);
      }
    }

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, srcValues, unsafe, memAddr, bytesLen, currentFormat,
        isLatestSchema);
  }

  /**
   * Set specified columns with srcValues and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte source of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapByteSource rowToChange,
      final RowFormatter currentFormat) throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    if (columns == null) {
      // complete row
      if (isLatestSchema) {
        return generateBytes(srcValues);
      }
      else {
        return currentFormat.generateBytes(srcValues);
      }
    }

    assert columns.getNumBitsSet() > 0: "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      if (isLatestSchema) {
        return generateColumns(columns, srcValues);
      }
      else {
        return currentFormat.generateColumns(columns, srcValues);
      }
    }

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, srcValues, unsafe, memAddr, bytesLen, currentFormat,
        isLatestSchema);
  }

  /**
   * Set specified column with srcValue and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columnIndex
   *          0-based index of column to be modified
   * @param srcValue
   *          value of the column to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      final byte[] rowToChange, final RowFormatter currentFormat)
      throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    int newRowLength;
    if (isLatestSchema) {
      // compute new row length by adding up the diffs between length of new
      // value and length of current value in the rowToChangeBytes
      newRowLength = rowToChange.length; // start with old and diff
      final ColumnDescriptor cd = this.columns[columnIndex];
      final int oldColWidth = getColumnWidth(columnIndex, rowToChange, cd);
      int newColWidth = cd.fixedWidth;
      // if srcValue type does not match then create a new DVD
      final DataTypeDescriptor dtd = cd.columnType;
      if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
        final DataValueDescriptor newValue = dtd.getNull();
        newValue.setValue(srcValue);
        srcValue = newValue;
      }
      if (newColWidth == -1) {
        newColWidth = getVarColumnWidth(srcValue, cd);
      }
      newRowLength += newColWidth - oldColWidth;
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newRowLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        int newColWidth;
        if (colIndex == columnIndex) {
          newColWidth = cd.fixedWidth;
          // if srcValue type does not match then create a new DVD
          final DataTypeDescriptor dtd = cd.columnType;
          if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
            final DataValueDescriptor newValue = dtd.getNull();
            newValue.setValue(srcValue);
            srcValue = newValue;
          }
          if (newColWidth == -1) {
            newColWidth = currentFormat.getVarColumnWidth(srcValue, cd);
            newColWidth += currentFormat.getNumOffsetBytes();
          }
        } else {
          newColWidth = getTotalColumnWidth(colIndex, rowToChange, cd,
              currentFormat);
        }
        newRowLength += newColWidth;
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    currentFormat.writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      if (index == columnIndex) {
        varDataOffset += currentFormat.generateColumn(index, srcValue,
            currentFormat.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += currentFormat.copyColumn(index, rowToChange, this,
            newBytes, index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified column with srcValue and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @return the new byte array
   */
  final byte[] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final RowFormatter currentFormat) throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    int newRowLength;

    if (isLatestSchema) {
      // compute new row length by adding up the diffs between length of new
      // value and length of current value in the rowToChangeBytes
      newRowLength = bytesLen; // start with old and diff
      final ColumnDescriptor cd = this.columns[columnIndex];
      final int oldColWidth = getColumnWidth(columnIndex, unsafe, memAddr,
          bytesLen, cd);
      int newColWidth = cd.fixedWidth;
      // if srcValue type does not match then create a new DVD
      final DataTypeDescriptor dtd = cd.columnType;
      if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
        final DataValueDescriptor newValue = dtd.getNull();
        newValue.setValue(srcValue);
        srcValue = newValue;
      }
      if (newColWidth == -1) {
        newColWidth = getVarColumnWidth(srcValue, cd);
      }
      newRowLength += newColWidth - oldColWidth;
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newRowLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        int newColWidth;
        if (colIndex == columnIndex) {
          newColWidth = cd.fixedWidth;
          // if srcValue type does not match then create a new DVD
          final DataTypeDescriptor dtd = cd.columnType;
          if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
            final DataValueDescriptor newValue = dtd.getNull();
            newValue.setValue(srcValue);
            srcValue = newValue;
          }
          if (newColWidth == -1) {
            newColWidth = currentFormat.getVarColumnWidth(srcValue, cd);
            newColWidth += currentFormat.getNumOffsetBytes();
          }
        } else {
          newColWidth = getTotalColumnWidth(colIndex, unsafe, memAddr,
              bytesLen, cd, currentFormat);
        }
        newRowLength += newColWidth;
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    currentFormat.writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      if (index == columnIndex) {
        varDataOffset += currentFormat.generateColumn(index, srcValue,
            currentFormat.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += currentFormat.copyColumn(index, unsafe, memAddr,
            bytesLen, this, newBytes, index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified column with srcValue and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columnIndex
   *          0-based index of column to be modified
   * @param srcValue
   *          value of the column to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      @Unretained final OffHeapRow rowToChange, final RowFormatter currentFormat)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    return setColumn(columnIndex, srcValue, unsafe, memAddr, bytesLen,
        currentFormat);
  }

  /**
   * Set specified column with srcValue and return a new byte array. Also
   * upgrades to the latest schema version if this is not the latest one.
   *
   * @param columnIndex
   *          0-based index of column to be modified
   * @param srcValue
   *          value of the column to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return the new byte array
   */
  final byte[] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      @Unretained final OffHeapByteSource rowToChange,
      final RowFormatter currentFormat) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    return setColumn(columnIndex, srcValue, unsafe, memAddr, bytesLen,
        currentFormat);
  }

  /**
   * Set specified columns with srcValues compacting them to map first set bit
   * position to position 1, second to position 2 and so on, and return a new
   * byte array.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   *
   * @return the new byte array
   */
  final byte[] setByteCompactColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues) throws StandardException {

    if (columns == null) {
      // complete row
      return generateBytes(srcValues);
    }

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    final int nCols = srcValues.length;
    int rowLength = this.nVersionBytes;
    for (int colIndex = columns.anySetBit(), index = 0; colIndex != -1
        && colIndex < nCols; colIndex = columns.anySetBit(colIndex), index++) {
      final ColumnDescriptor cd = this.columns[index];
      int colWidth = cd.fixedWidth;
      if (colWidth == -1) {
        colWidth = getVarColumnWidth(srcValues[colIndex], cd);
        colWidth += getNumOffsetBytes();
      }
      rowLength += colWidth;
    }

    final byte[] newBytes = new byte[rowLength];
    // write the version, if any, first
    writeVersion(newBytes);

    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues

    // beginning of var data offset
    int varDataOffset = this.varDataOffset;
    for (int colIndex = columns.anySetBit(), index = 0; colIndex != -1
        && colIndex < nCols; colIndex = columns.anySetBit(colIndex), index++) {
      varDataOffset += generateColumn(index, srcValues[colIndex],
          this.columns[index], newBytes, varDataOffset);
    }
    return newBytes;
  }

  /**
   * Set specified columns with srcValues and return a new byte array.
   *
   * @param nCols
   *          number of columns from start to be modified
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues, final byte[] rowToChange)
      throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      return generateColumns(nCols, srcValues);
    }

    int newRowLength;
    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    newRowLength = rowToChange.length; // start with old and diff
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      final ColumnDescriptor cd = this.columns[colIndex];
      final int oldColWidth = getColumnWidth(colIndex, rowToChange, cd);
      int newColWidth = cd.fixedWidth;
      if (newColWidth == -1) {
        newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
      }
      newRowLength += newColWidth - oldColWidth;
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = this.varDataOffset;
    final int numCols = this.positionMap.length;
    for (int index = 0; index < numCols; index++) {
      if (index < nCols) {
        varDataOffset += generateColumn(index, srcValues[index],
            this.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += copyColumn(index, rowToChange, this, newBytes, index,
            varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with srcValues and return a new byte array.
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen) throws StandardException {

    int newRowLength;
    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    newRowLength = bytesLen; // start with old and diff
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      final ColumnDescriptor cd = this.columns[colIndex];
      final int oldColWidth = getColumnWidth(colIndex, unsafe, memAddr,
          bytesLen, cd);
      int newColWidth = cd.fixedWidth;
      if (newColWidth == -1) {
        newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
      }
      newRowLength += newColWidth - oldColWidth;
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int varDataOffset = this.varDataOffset;
    final int numCols = this.positionMap.length;
    for (int index = 0; index < numCols; index++) {
      if (index < nCols) {
        varDataOffset += generateColumn(index, srcValues[index],
            this.columns[index], newBytes, varDataOffset);
      } else {
        varDataOffset += copyColumn(index, unsafe, memAddr, bytesLen, this,
            newBytes, index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with srcValues and return a new byte array.
   *
   * @param nCols
   *          number of columns from start to be modified
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapRow rowToChange) throws StandardException {

    assert nCols > 0: "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      return generateColumns(nCols, srcValues);
    }

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);

    return setColumns(nCols, srcValues, unsafe, memAddr, bytesLen);
  }

  /**
   * Set specified columns with srcValues and return a new byte array.
   *
   * @param nCols
   *          number of columns from start to be modified
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          byte array of the row to be modified, can be null if columns is
   *          null (all columns are being changed)
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapByteSource rowToChange) throws StandardException {

    assert nCols > 0: "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // source is a template row with null bytes
      return generateColumns(nCols, srcValues);
    }

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowToChange.getLength();
    final long memAddr = rowToChange.getUnsafeAddress(0, bytesLen);

    return setColumns(nCols, srcValues, unsafe, memAddr, bytesLen);
  }

  /**
   * Set specified columns with srcValues and return a new array of byte arrays.
   * Also upgrades to the latest schema version if this is not the latest one.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   * @param firstBytesToChange
   *          the first row of rowToChange (or if null then read from
   *          rowToChange); as a special case this can be non-null while
   *          rowToChange can be null when copying from non-LOB source to LOB
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapRowWithLobs rowToChange,
      @Unretained final OffHeapByteSource firstBytesToChange,
      final RowFormatter currentFormat, boolean prepareForOffHeap) throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    if (columns == null) {
      // complete row
      if (isLatestSchema) {
        return generateByteArrays(srcValues);
      } else {
        return currentFormat.generateByteArrays(srcValues);
      }
    }

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    final int bitSetSize = columns.getLength();
    int newFirstLength;
    boolean hasFirstBytesChange = false;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (rowToChange != null) {
      bytesLen = rowToChange.getLength();
      memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = firstBytesToChange.getLength();
      memAddr = firstBytesToChange.getUnsafeAddress(0, bytesLen);
    }

    if (isLatestSchema) {
      newFirstLength = bytesLen;
      hasFirstBytesChange = false;
      for (int colIndex = columns.anySetBit(); colIndex != -1; colIndex = columns
          .anySetBit(colIndex)) {
        final ColumnDescriptor cd = this.columns[colIndex];
        if (!cd.isLob) {
          final int oldColWidth = getColumnWidth(colIndex, unsafe, memAddr,
              bytesLen, cd);
          int newColWidth = cd.fixedWidth;
          if (newColWidth == -1) {
            newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
          }
          newFirstLength += newColWidth - oldColWidth;
          hasFirstBytesChange = true;
        }
      }
    }
    else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newFirstLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        if (!cd.isLob) {
          int newColWidth;
          if (colIndex < bitSetSize && columns.isSet(colIndex)) {
            newColWidth = cd.fixedWidth;
            if (newColWidth == -1) {
              newColWidth = currentFormat.getVarColumnWidth(
                  srcValues[colIndex], cd);
              newColWidth += currentFormat.getNumOffsetBytes();
            }
          }
          else {
            newColWidth = getTotalColumnWidth(colIndex, unsafe, memAddr,
                bytesLen, cd, currentFormat);
          }
          newFirstLength += newColWidth;
          hasFirstBytesChange = true;
        }
      }
    }
    // If the source is OffHeapByteSource, the last byte[] of byte[][] will keep
    // track of which byte[] arrays have changed. So that while writing to
    // Offheap only those positions are manipulated. If a byte[] has not changed
    // we do not need the value to be assigned
    final byte[][] newByteArrayArray;

    boolean copyLobsByValue = !isLatestSchema
        || !this.container.isInitialized() || !prepareForOffHeap;
    final FormatableBitSet byteArrayChanged;
    if (prepareForOffHeap) {
      newByteArrayArray = new byte[currentFormat.numLobs + 2][];
      byteArrayChanged = new FormatableBitSet(newByteArrayArray.length - 1);
    }
    else {
      newByteArrayArray = new byte[currentFormat.numLobs + 1][];
      byteArrayChanged = null;
    }

    byte[] newFirstBytes = null;
    if (hasFirstBytesChange || !this.container.isInitialized()) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      if(prepareForOffHeap) {
        byteArrayChanged.set(0);
      }

      // write the version, if any, first
      currentFormat.writeVersion(newFirstBytes);
    }
    else {
      if(prepareForOffHeap) {
        newByteArrayArray[0] = null;
      }else {
        newByteArrayArray[0] = rowToChange != null? rowToChange.getRowBytes()
            : firstBytesToChange.getRowBytes();
      }
    }

   // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      final ColumnDescriptor cd = currentFormat.columns[index];
      if (index < bitSetSize && columns.isSet(index)) {
        final DataValueDescriptor dvd = srcValues[index];
        if (!cd.isLob) {
          varDataOffset += currentFormat.generateColumn(index, dvd, cd,
              newFirstBytes, varDataOffset);
        } else {
          int lobIndx = currentFormat.positionMap[index];
          newByteArrayArray[lobIndx] = currentFormat.generateLobColumn(dvd, cd);
          if(prepareForOffHeap) {
            byteArrayChanged.set(lobIndx);
          }
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange || !this.container.isInitialized()) {
            varDataOffset += currentFormat.copyColumn(index, unsafe, memAddr,
                bytesLen, this, newFirstBytes, index, varDataOffset);
          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          final byte[] lobBytes;

          if (lobIndex > 0 && rowToChange != null) {
            if (copyLobsByValue) {
              lobBytes = rowToChange.getGfxdBytes(lobIndex);
            } else {
              lobBytes = null;
            }
          } else {
            lobBytes = cd.columnDefaultBytes;
          }
          if (isLatestSchema) {
            newByteArrayArray[lobIndex] = lobBytes;
            if (lobBytes != null && prepareForOffHeap) {
                byteArrayChanged.set(lobIndex);
            }
          } else {
            int lobIndx = currentFormat.positionMap[index];
            newByteArrayArray[lobIndx] = lobBytes;
            if ((lobBytes != null || copyLobsByValue) && prepareForOffHeap) {
              byteArrayChanged.set(lobIndx);
            }
          }
        }
      }
    }
    if(prepareForOffHeap) {
      newByteArrayArray[newByteArrayArray.length - 1] = byteArrayChanged
          .getByteArray();
    }
    return newByteArrayArray;
  }

  /**
   * Set specified columns with srcValues and return a new array of byte arrays.
   * Also upgrades to the latest schema version if this is not the latest one.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   * @param firstBytesToChange
   *          the first row of rowToChange (or if null then read from
   *          rowToChange); as a special case this can be non-null while
   *          rowToChange can be null when copying from non-LOB source to LOB
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues, final byte[][] rowToChange,
      byte[] firstBytesToChange, final RowFormatter currentFormat)
      throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    if (columns == null ||
        (rowToChange == null && firstBytesToChange == null)) {
      // complete row
      if (isLatestSchema) {
        return generateByteArrays(srcValues);
      } else {
        return currentFormat.generateByteArrays(srcValues);
      }
    }

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    final int bitSetSize = columns.getLength();
    int newFirstLength;
    boolean hasFirstBytesChange = false;
    if (firstBytesToChange == null) {
      firstBytesToChange = rowToChange[0];
    }
    if (firstBytesToChange != null) {
      if (isLatestSchema) {
        newFirstLength = firstBytesToChange.length; // start with old and diff
        hasFirstBytesChange = false;
        for (int colIndex = columns.anySetBit(); colIndex != -1;
            colIndex = columns.anySetBit(colIndex)) {
          final ColumnDescriptor cd = this.columns[colIndex];
          if (!cd.isLob) {
            final int oldColWidth = getColumnWidth(colIndex, firstBytesToChange, cd);
            int newColWidth = cd.fixedWidth;
            if (newColWidth == -1) {
              newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
            }
            newFirstLength += newColWidth - oldColWidth;
            hasFirstBytesChange = true;
          }
        }
      } else {
        // need to compute full row length since formatting may have changed
        // for one or more columns in currentFormat
        newFirstLength = currentFormat.nVersionBytes;
        final int nCols = currentFormat.positionMap.length;
        for (int colIndex = 0; colIndex < nCols; colIndex++) {
          final ColumnDescriptor cd = currentFormat.columns[colIndex];
          if (!cd.isLob) {
            int newColWidth;
            if (colIndex < bitSetSize && columns.isSet(colIndex)) {
              newColWidth = cd.fixedWidth;
              if (newColWidth == -1) {
                newColWidth = currentFormat.getVarColumnWidth(
                    srcValues[colIndex], cd);
                newColWidth += currentFormat.getNumOffsetBytes();
              }
            } else {
              newColWidth = getTotalColumnWidth(colIndex, firstBytesToChange, cd,
                  currentFormat);
            }
            newFirstLength += newColWidth;
            hasFirstBytesChange = true;
          }
        }
      }
    } else {
      firstBytesToChange = currentFormat.generateColumns(columns, srcValues);
      newFirstLength = 0;
    }

    final byte[][] newByteArrayArray = new byte[currentFormat.numLobs + 1][];
    byte[] newFirstBytes = null;
    if (hasFirstBytesChange) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      // write the version, if any, first
      currentFormat.writeVersion(newFirstBytes);
    } else {
      newByteArrayArray[0] = firstBytesToChange;
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      final ColumnDescriptor cd = currentFormat.columns[index];
      if (index < bitSetSize && columns.isSet(index)) {
        final DataValueDescriptor dvd = srcValues[index];
        if (!cd.isLob) {
          varDataOffset += currentFormat.generateColumn(index, dvd, cd,
              newFirstBytes, varDataOffset);
        } else {
          newByteArrayArray[currentFormat.positionMap[index]] = currentFormat
              .generateLobColumn(dvd, cd);
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange) {
            varDataOffset += currentFormat.copyColumn(index, firstBytesToChange, this,
                newFirstBytes, index, varDataOffset);
          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          final byte[] lobBytes = lobIndex > 0 && rowToChange != null
              ? rowToChange[lobIndex] : cd.columnDefaultBytes;
          if (isLatestSchema) {
            newByteArrayArray[lobIndex] = lobBytes;
          } else {
            newByteArrayArray[currentFormat.positionMap[index]] = lobBytes;
          }
        }
      }
    }
    return newByteArrayArray;
  }

  /**
   * Set specified column with srcValue and return a new array of byte arrays.
   * Also upgrades to the latest schema version if this is not the latest one.
   *
   * @param columnIndex
   *          0-based index of column to be modified
   * @param srcValue
   *          value of the column to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   * @param firstBytesToChange
   *          the first row of rowToChange (or if null then read from
   *          rowToChange); as a special case this can be non-null while
   *          rowToChange can be null when copying from non-LOB source to LOB
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      @Unretained final OffHeapRowWithLobs rowToChange,
      @Unretained final OffHeapByteSource firstBytesToChange,
      final RowFormatter currentFormat) throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    int newFirstLength;
    boolean hasFirstBytesChange = false;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (rowToChange != null) {
      bytesLen = rowToChange.getLength();
      memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = firstBytesToChange.getLength();
      memAddr = firstBytesToChange.getUnsafeAddress(0, bytesLen);
    }

    if (isLatestSchema) {
      newFirstLength = bytesLen; // start with old and diff
      hasFirstBytesChange = false;
      final ColumnDescriptor cd = this.columns[columnIndex];
      if (!cd.isLob) {
        final int oldColWidth = getColumnWidth(columnIndex, unsafe, memAddr,
            bytesLen, cd);
        int newColWidth = cd.fixedWidth;
        // if srcValue type does not match then create a new DVD
        final DataTypeDescriptor dtd = cd.columnType;
        if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
          final DataValueDescriptor newValue = dtd.getNull();
          newValue.setValue(srcValue);
          srcValue = newValue;
        }
        if (newColWidth == -1) {
          newColWidth = getVarColumnWidth(srcValue, cd);
        }
        newFirstLength += newColWidth - oldColWidth;
        hasFirstBytesChange = true;
      }
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newFirstLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        if (!cd.isLob) {
          int newColWidth;
          if (colIndex == columnIndex) {
            newColWidth = cd.fixedWidth;
            // if srcValue type does not match then create a new DVD
            final DataTypeDescriptor dtd = cd.columnType;
            if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
              final DataValueDescriptor newValue = dtd.getNull();
              newValue.setValue(srcValue);
              srcValue = newValue;
            }
            if (newColWidth == -1) {
              newColWidth = currentFormat.getVarColumnWidth(srcValue, cd);
              newColWidth += currentFormat.getNumOffsetBytes();
            }
          }
          else {
            newColWidth = getTotalColumnWidth(colIndex, unsafe, memAddr,
                bytesLen, cd, currentFormat);
          }
          newFirstLength += newColWidth;
          hasFirstBytesChange = true;
        }
      }
    }

    final byte[][] newByteArrayArray = new byte[currentFormat.numLobs + 1][];
    byte[] newFirstBytes = null;
    if (hasFirstBytesChange) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      // write the version, if any, first
      currentFormat.writeVersion(newFirstBytes);
    }
    else {
      newByteArrayArray[0] = rowToChange != null ? rowToChange.getRowBytes()
          : firstBytesToChange.getRowBytes();
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      final ColumnDescriptor cd = currentFormat.columns[index];
      if (index == columnIndex) {
        if (!cd.isLob) {
          varDataOffset += currentFormat.generateColumn(index, srcValue, cd,
              newFirstBytes, varDataOffset);
        } else {
          newByteArrayArray[currentFormat.positionMap[index]] = currentFormat
              .generateLobColumn(srcValue, cd);
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange) {
            varDataOffset += currentFormat.copyColumn(index, unsafe, memAddr,
                bytesLen, this, newFirstBytes, index, varDataOffset);

          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          final byte[] lobBytes;
          if (lobIndex > 0 && rowToChange != null) {
            lobBytes = rowToChange.getGfxdBytes(lobIndex);
          } else {
            lobBytes = cd.columnDefaultBytes;
          }

          if (isLatestSchema) {
            newByteArrayArray[lobIndex] = lobBytes;
          } else {
            newByteArrayArray[currentFormat.positionMap[index]] = lobBytes;
          }
        }
      }
    }
    return newByteArrayArray;
  }

  /**
   * Set specified column with srcValue and return a new array of byte arrays.
   * Also upgrades to the latest schema version if this is not the latest one.
   *
   * @param columnIndex
   *          0-based index of column to be modified
   * @param srcValue
   *          value of the column to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   * @param currentFormat
   *          if "this" formatter is for an old schema version, then this must
   *          be the handle of the current formatter, else it must point to
   *          "this" itself
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumn(final int columnIndex, DataValueDescriptor srcValue,
      final byte[][] rowToChange, final RowFormatter currentFormat)
      throws StandardException {

    final boolean isLatestSchema =
        (this.schemaVersion == currentFormat.schemaVersion);
    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    byte[] firstBytes = rowToChange[0];
    int newFirstLength;
    boolean hasFirstBytesChange = false;
    if (isLatestSchema) {
      newFirstLength = firstBytes.length; // start with old and diff
      hasFirstBytesChange = false;
      final ColumnDescriptor cd = this.columns[columnIndex];
      if (!cd.isLob) {
        final int oldColWidth = getColumnWidth(columnIndex, firstBytes, cd);
        int newColWidth = cd.fixedWidth;
        // if srcValue type does not match then create a new DVD
        final DataTypeDescriptor dtd = cd.columnType;
        if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
          final DataValueDescriptor newValue = dtd.getNull();
          newValue.setValue(srcValue);
          srcValue = newValue;
        }
        if (newColWidth == -1) {
          newColWidth = getVarColumnWidth(srcValue, cd);
        }
        newFirstLength += newColWidth - oldColWidth;
        hasFirstBytesChange = true;
      }
    } else {
      // need to compute full row length since formatting may have changed
      // for one or more columns in currentFormat
      newFirstLength = currentFormat.nVersionBytes;
      final int nCols = currentFormat.positionMap.length;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = currentFormat.columns[colIndex];
        if (!cd.isLob) {
          int newColWidth;
          if (colIndex == columnIndex) {
            newColWidth = cd.fixedWidth;
            // if srcValue type does not match then create a new DVD
            final DataTypeDescriptor dtd = cd.columnType;
            if (srcValue.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
              final DataValueDescriptor newValue = dtd.getNull();
              newValue.setValue(srcValue);
              srcValue = newValue;
            }
            if (newColWidth == -1) {
              newColWidth = currentFormat.getVarColumnWidth(srcValue, cd);
              newColWidth += currentFormat.getNumOffsetBytes();
            }
          } else {
            newColWidth = getTotalColumnWidth(colIndex, firstBytes, cd,
                currentFormat);
          }
          newFirstLength += newColWidth;
          hasFirstBytesChange = true;
        }
      }
    }

    final byte[][] newByteArrayArray = new byte[currentFormat.numLobs + 1][];
    byte[] newFirstBytes = null;
    if (hasFirstBytesChange) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      // write the version, if any, first
      currentFormat.writeVersion(newFirstBytes);
    } else {
      newByteArrayArray[0] = firstBytes;
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = currentFormat.varDataOffset;
    final int nCols = currentFormat.positionMap.length;
    for (int index = 0; index < nCols; index++) {
      final ColumnDescriptor cd = currentFormat.columns[index];
      if (index == columnIndex) {
        if (!cd.isLob) {
          varDataOffset += currentFormat.generateColumn(index, srcValue, cd,
              newFirstBytes, varDataOffset);
        } else {
          newByteArrayArray[currentFormat.positionMap[index]] = currentFormat
              .generateLobColumn(srcValue, cd);
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange) {
            varDataOffset += currentFormat.copyColumn(index, firstBytes, this,
                newFirstBytes, index, varDataOffset);
          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          final byte[] lobBytes = lobIndex > 0 ? rowToChange[lobIndex]
              : cd.columnDefaultBytes;
          if (isLatestSchema) {
            newByteArrayArray[lobIndex] = lobBytes;
          } else {
            newByteArrayArray[currentFormat.positionMap[index]] = lobBytes;
          }
        }
      }
    }
    return newByteArrayArray;
  }

  /**
   * Set specified columns with srcValues compacting them to map first set bit
   * position to position 1, second to position 2 and so on, and return a new
   * array of byte arrays.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcValues
   *          DVD array of the columns to be modified
   *
   * @return a new array of byte arrays
   */
  final byte[][] setByteArrayCompactColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues) throws StandardException {

    if (columns == null) {
      // complete row
      return generateByteArrays(srcValues);
    }

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    final int nCols = srcValues.length;
    int firstRowLength = this.nVersionBytes;
    for (int colIndex = columns.anySetBit(), index = 0; colIndex != -1
        && colIndex < nCols; colIndex = columns.anySetBit(colIndex), index++) {
      final ColumnDescriptor cd = this.columns[index];
      if (!cd.isLob) {
        int colWidth = cd.fixedWidth;
        if (colWidth == -1) {
          colWidth = getVarColumnWidth(srcValues[colIndex], cd);
          colWidth += getNumOffsetBytes();
        }
        firstRowLength += colWidth;
      }
    }

    final byte[][] newByteArrayArray = new byte[this.numLobs + 1][];
    final byte[] newFirstBytes = new byte[firstRowLength];
    newByteArrayArray[0] = newFirstBytes;
    // write the version, if any, first
    writeVersion(newFirstBytes);

    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues

    // beginning of var data offset
    int varDataOffset = this.varDataOffset;
    for (int colIndex = columns.anySetBit(), index = 0; colIndex != -1
        && colIndex < nCols; colIndex = columns.anySetBit(colIndex), index++) {
      final ColumnDescriptor cd = this.columns[index];
      final DataValueDescriptor dvd = srcValues[colIndex];
      if (!cd.isLob) {
        varDataOffset += generateColumn(index, dvd, cd, newFirstBytes,
            varDataOffset);
      } else {
        newByteArrayArray[this.positionMap[index]] = generateLobColumn(dvd, cd);
      }
    }
    return newByteArrayArray;
  }

  /**
   * Set specified columns with srcValues and return a new array of byte arrays.
   *
   * @param nCols
   *          number of columns from the start to be modified
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   * @param firstBytesToChange
   *          the first row of rowToChange (or if null then read from
   *          rowToChange); as a special case this can be non-null while
   *          rowToChange can be null when copying from non-LOB source to LOB
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues,
      @Unretained final OffHeapRowWithLobs rowToChange,
      @Unretained final OffHeapByteSource firstBytesToChange)
      throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    int newFirstLength;
    boolean hasFirstBytesChange;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (rowToChange != null) {
      bytesLen = rowToChange.getLength();
      memAddr = rowToChange.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = firstBytesToChange.getLength();
      memAddr = firstBytesToChange.getUnsafeAddress(0, bytesLen);
    }

    newFirstLength = bytesLen; // start with old and diff
    hasFirstBytesChange = false;
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      final ColumnDescriptor cd = this.columns[colIndex];
      if (!cd.isLob) {
        final int oldColWidth = getColumnWidth(colIndex, unsafe, memAddr,
            bytesLen, cd);
        int newColWidth = cd.fixedWidth;
        if (newColWidth == -1) {
          newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
        }
        newFirstLength += newColWidth - oldColWidth;
        hasFirstBytesChange = true;
      }
    }

    final byte[][] newByteArrayArray = new byte[this.numLobs + 1][];
    byte[] newFirstBytes = null;
    if (hasFirstBytesChange) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      // write the version, if any, first
      writeVersion(newFirstBytes);
    }
    else {
      newByteArrayArray[0] = rowToChange != null ? rowToChange.getRowBytes()
          : firstBytesToChange.getRowBytes();
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = this.varDataOffset;
    final int numCols = this.positionMap.length;
    for (int index = 0; index < numCols; index++) {
      final ColumnDescriptor cd = this.columns[index];
      if (index < nCols) {
        final DataValueDescriptor dvd = srcValues[index];
        if (!cd.isLob) {
          varDataOffset += generateColumn(index, dvd, cd, newFirstBytes,
              varDataOffset);
        } else {
          newByteArrayArray[this.positionMap[index]] = generateLobColumn(dvd,
              cd);
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange) {
            varDataOffset += copyColumn(index, unsafe, memAddr, bytesLen, this,
                newFirstBytes, index, varDataOffset);
          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          if (lobIndex != 0 && rowToChange != null) {
            newByteArrayArray[lobIndex] = rowToChange.getGfxdBytes(lobIndex);
          } else {
            newByteArrayArray[lobIndex] = cd.columnDefaultBytes;
          }
        }
      }
    }
    return newByteArrayArray;
  }

  /**
   * Set specified columns with srcValues and return a new array of byte arrays.
   *
   * @param nCols
   *          number of columns from the start to be modified
   * @param srcValues
   *          DVD array of the columns to be modified
   * @param rowToChange
   *          array of byte arrays for the row to be modified
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int nCols,
      final DataValueDescriptor[] srcValues, final byte[][] rowToChange)
      throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + RowUtil.toString(srcValues);

    if (rowToChange == null) {
      // complete row
      return generateByteArrays(srcValues);
    }

    // compute new row length by adding up the diffs between length of new
    // value and length of current value in the rowToChangeBytes
    byte[] firstBytes = rowToChange[0];
    int newFirstLength;
    boolean hasFirstBytesChange = false;
    if (firstBytes != null) {
      newFirstLength = firstBytes.length; // start with old and diff
      hasFirstBytesChange = false;
      for (int colIndex = 0; colIndex < nCols; colIndex++) {
        final ColumnDescriptor cd = this.columns[colIndex];
        if (!cd.isLob) {
          final int oldColWidth = getColumnWidth(colIndex, firstBytes, cd);
          int newColWidth = cd.fixedWidth;
          if (newColWidth == -1) {
            newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
          }
          newFirstLength += newColWidth - oldColWidth;
          hasFirstBytesChange = true;
        }
      }
    } else {
      firstBytes = generateColumns(nCols, srcValues);
      newFirstLength = 0;
    }

    final byte[][] newByteArrayArray = new byte[this.numLobs + 1][];
    byte[] newFirstBytes = null;
    if (hasFirstBytesChange) {
      newFirstBytes = new byte[newFirstLength];
      newByteArrayArray[0] = newFirstBytes;
      // write the version, if any, first
      writeVersion(newFirstBytes);
    } else {
      newByteArrayArray[0] = firstBytes;
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    // beginning of var data offset
    int lobIndex;
    int varDataOffset = this.varDataOffset;
    final int numCols = this.positionMap.length;
    for (int index = 0; index < numCols; index++) {
      final ColumnDescriptor cd = this.columns[index];
      if (index < nCols) {
        final DataValueDescriptor dvd = srcValues[index];
        if (!cd.isLob) {
          varDataOffset += generateColumn(index, dvd, cd, newFirstBytes,
              varDataOffset);
        } else {
          newByteArrayArray[this.positionMap[index]] = generateLobColumn(dvd,
              cd);
        }
      } else {
        if (!cd.isLob) {
          if (hasFirstBytesChange) {
            varDataOffset += copyColumn(index, firstBytes, this, newFirstBytes,
                index, varDataOffset);
          }
        } else {
          lobIndex = this.positionMap[index];
          // for the special case of default value after ALTER TABLE, copy the
          // default bytes
          if (lobIndex != 0) {
            newByteArrayArray[lobIndex] = rowToChange[lobIndex];
          } else {
            newByteArrayArray[lobIndex] = cd.columnDefaultBytes;
          }
        }
      }
    }
    return newByteArrayArray;
  }

  /**
   * Generate bytes for given columns in the DVD[].
   */
  private byte[] generateColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] srcValues) throws StandardException {

    // source is a template row with null bytes
    int newRowLength = this.nVersionBytes;
    for (int colIndex = columns.anySetBit(); colIndex != -1; colIndex = columns
        .anySetBit(colIndex)) {
      final ColumnDescriptor cd = this.columns[colIndex];
      int newColWidth;
      if ((newColWidth = cd.fixedWidth) == -1) {
        newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
        newColWidth += getNumOffsetBytes();
      }
      newRowLength += newColWidth;
    }
    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array
    int varDataOffset = this.varDataOffset; // beginning of var data offset
    final int bitSetSize = columns.getLength();
    final int numCols = this.positionMap.length;
    for (int idx = 0; idx < numCols; ++idx) {
      final ColumnDescriptor cd = this.columns[idx];
      if (!cd.isLob) {
        if (idx < bitSetSize && columns.isSet(idx)) {
          varDataOffset += generateColumn(idx, srcValues[idx],
              this.columns[idx], newBytes, varDataOffset);
        } else {
          final int width = cd.fixedWidth;
          if (width == -1) {
            final int offsetOffset = newRowLength + this.positionMap[idx];
            if (cd.columnDefault == null) {
              // null value; write negative offset+1 of next var column
              writeInt(newBytes, getNullIndicator(varDataOffset), offsetOffset,
                  getNumOffsetBytes());
            } else {
              // write an indicator as offset for value being default
              writeInt(newBytes, getOffsetDefaultToken(), offsetOffset,
                  getNumOffsetBytes());
            }
          }
        }
      }
    }
    return newBytes;
  }

  /**
   * Generate bytes for given columns in the DVD[].
   */
  private byte[] generateColumns(final int nCols,
      final DataValueDescriptor[] srcValues) throws StandardException {

    // source is a template row with null bytes
    int newRowLength = this.nVersionBytes;
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      final ColumnDescriptor cd = this.columns[colIndex];
      int newColWidth;
      if ((newColWidth = cd.fixedWidth) == -1) {
        newColWidth = getVarColumnWidth(srcValues[colIndex], cd);
        newColWidth += getNumOffsetBytes();
      }
      newRowLength += newColWidth;
    }
    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array
    int varDataOffset = this.varDataOffset; // beginning of var data offset
    final int numCols = this.positionMap.length;
    for (int idx = 0; idx < numCols; ++idx) {
      final ColumnDescriptor cd = this.columns[idx];
      if (!cd.isLob) {
        if (idx < nCols) {
          varDataOffset += generateColumn(idx, srcValues[idx],
              this.columns[idx], newBytes, varDataOffset);
        } else {
          final int width = cd.fixedWidth;
          if (width == -1) {
            final int offsetOffset = newRowLength + this.positionMap[idx];
            if (cd.columnDefault == null) {
              // null value; write negative offset+1 of next var column
              writeInt(newBytes, getNullIndicator(varDataOffset), offsetOffset,
                  getNumOffsetBytes());
            } else {
              // write an indicator as offset for value being default
              writeInt(newBytes, getOffsetDefaultToken(), offsetOffset,
                  getNumOffsetBytes());
            }
          }
        }
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns, final byte[] srcRow,
      final RowFormatter srcFormat, final int[] baseColumnMap)
      throws StandardException {

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + Arrays.toString(srcRow);

    final boolean isSameSchema = (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      newRowLength += getTotalColumnWidth(srcIndex, srcRow, srcFormat, cd,
          index, isSameSchema);
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      varDataOffset += copyColumn(srcIndex, srcRow, srcFormat, newBytes, index,
          varDataOffset);
      if (baseColumnMap != null) {
        baseColumnMap[index] = srcIndex;
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final RowFormatter srcFormat, final int[] baseColumnMap)
      throws StandardException {

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      newRowLength += getTotalColumnWidth(srcIndex, unsafe, memAddr, bytesLen,
          srcFormat, cd, index, isSameSchema);
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      varDataOffset += copyColumn(srcIndex, unsafe, memAddr, bytesLen, srcFormat,
          newBytes, index, varDataOffset);
      if (baseColumnMap != null) {
        baseColumnMap[index] = srcIndex;
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      @Unretained final OffHeapRow srcRow, final RowFormatter srcFormat,
      final int[] baseColumnMap) throws StandardException {

    assert columns.getNumBitsSet() > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, unsafe, memAddr, bytesLen, srcFormat,
        baseColumnMap);
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          bit set of columns to be modified, if null then all columns
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   *
   * @return the new byte array
   */
  final byte[] setColumns(final FormatableBitSet columns,
      @Unretained final OffHeapByteSource srcRow, final RowFormatter srcFormat,
      final int[] baseColumnMap) throws StandardException {

    assert columns.getNumBitsSet() > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, unsafe, memAddr, bytesLen, srcFormat,
        baseColumnMap);
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          positions of columns to be modified
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int[] columns, boolean zeroBased,
      final byte[] srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert columns.length > 0 : "setColumns with zero columns to set: "
        + Arrays.toString(srcRow);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    int srcColIndex;
    final int numCols = columns.length;
    for (int index = 0; index < numCols; index++) {
      srcColIndex = columns[index];
      if (!zeroBased) {
        srcColIndex--;
      }
      if (srcColIndex >= 0) {
        cd = srcFormat.columns[srcColIndex];
        newRowLength += getTotalColumnWidth(srcColIndex, srcRow, srcFormat, cd,
            index, isSameSchema);
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int index = 0; index < numCols; index++) {
      srcColIndex = columns[index];
      if (!zeroBased) {
        srcColIndex--;
      }
      if (srcColIndex >= 0) {
        varDataOffset += copyColumn(srcColIndex, srcRow, srcFormat, newBytes,
            index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int[] columns, boolean zeroBased,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final RowFormatter srcFormat) throws StandardException {

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    int srcColIndex;
    final int numCols = columns.length;
    for (int index = 0; index < numCols; index++) {
      srcColIndex = columns[index];
      if (!zeroBased) {
        srcColIndex--;
      }
      if (srcColIndex >= 0) {
        cd = srcFormat.columns[srcColIndex];
        newRowLength += getTotalColumnWidth(srcColIndex, unsafe, memAddr,
            bytesLen, srcFormat, cd, index, isSameSchema);
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int index = 0; index < numCols; index++) {
      srcColIndex = columns[index];
      if (!zeroBased) {
        srcColIndex--;
      }
      if (srcColIndex >= 0) {
        varDataOffset += copyColumn(srcColIndex, unsafe, memAddr, bytesLen,
            srcFormat, newBytes, index, varDataOffset);
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          positions of columns to be modified
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int[] columns, boolean zeroBased,
      @Unretained final OffHeapRow srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert columns.length > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, zeroBased, unsafe, memAddr, bytesLen, srcFormat);
  }

  /**
   * Set specified columns with serialized row and return a new byte array.
   *
   * @param columns
   *          positions of columns to be modified
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int[] columns, boolean zeroBased,
      @Unretained final OffHeapByteSource srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert columns.length > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(columns, zeroBased, unsafe, memAddr, bytesLen, srcFormat);
  }

  /**
   * Copy specified columns with serialized row and return a new byte array that
   * has remaining columns as default/null.
   *
   * @param columns
   *          positions of columns to be modified; this should be a sorted array
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] copyColumns(final int[] columns, final byte[] srcRow,
      final RowFormatter srcFormat) throws StandardException {

    assert columns.length > 0 : "setColumns with zero columns to set: "
        + Arrays.toString(srcRow);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    int srcIndex = 0;
    int srcColIndex = columns[0];
    srcColIndex--;
    final int srcColZeroIndex = srcColIndex;
    final int numCols = this.columns.length;
    for (int index = 0; index < numCols; index++) {
      if (index == srcColIndex) {
        cd = srcFormat.columns[srcColIndex];
        newRowLength += getTotalColumnWidth(srcColIndex, srcRow, srcFormat, cd,
            index, isSameSchema);
        srcIndex++;
        if (srcIndex < columns.length) {
          srcColIndex = columns[srcIndex];
          srcColIndex--;
        }
        else {
          // some value that will never match
          srcColIndex = -1;
        }
      }
      else {
        cd = this.columns[index];
        newRowLength += getTotalColumnWidth(index, null, this, cd, index, true);
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    srcIndex = 0;
    srcColIndex = srcColZeroIndex;
    for (int index = 0; index < numCols; index++) {
      if (index == srcColIndex) {
        varDataOffset += copyColumn(srcColIndex, srcRow, srcFormat, newBytes,
            index, varDataOffset);
        srcIndex++;
        if (srcIndex < columns.length) {
          srcColIndex = columns[srcIndex];
          srcColIndex--;
        }
        else {
          // some value that will never match
          srcColIndex = -1;
        }
      }
      else {
        final int offsetFromMap = this.positionMap[index];
        cd = this.columns[index];
        if (offsetFromMap >= this.nVersionBytes && !cd.isNullable) {
          // copy fixed null value
          DataValueDescriptor fixedZero = cd.columnType.getTypeId()
              .getStaticZeroForFixedType();
          fixedZero.writeBytes(newBytes, offsetFromMap, cd.columnType);
        }
        else {
          varDataOffset += generateColumn(index, null, cd, newBytes,
              varDataOffset);
        }
      }
    }
    return newBytes;
  }

  /**
   * Copy specified columns with serialized row and return a new byte array that
   * has remaining columns as default/null.
   *
   * @param columns
   *          positions of columns to be modified; this should be a sorted array
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] copyColumns(final int[] columns,
      @Unretained final OffHeapByteSource srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert columns.length > 0 : "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final boolean isSameSchema = (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);

    int newRowLength = this.nVersionBytes;
    int srcIndex = 0;
    int srcColIndex = columns[0];
    srcColIndex--;
    final int srcColZeroIndex = srcColIndex;
    final int numCols = this.columns.length;
    for (int index = 0; index < numCols; index++) {
      if (index == srcColIndex) {
        cd = srcFormat.columns[srcColIndex];
        newRowLength += getTotalColumnWidth(srcColIndex, unsafe, memAddr,
            bytesLen, srcFormat, cd, index, isSameSchema);
        srcIndex++;
        if (srcIndex < columns.length) {
          srcColIndex = columns[srcIndex];
          srcColIndex--;
        }
        else {
          // some value that will never match
          srcColIndex = -1;
        }
      }
      else {
        cd = this.columns[index];
        newRowLength += getTotalColumnWidth(index, null, this, cd, index, true);
      }
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    srcIndex = 0;
    srcColIndex = srcColZeroIndex;
    for (int index = 0; index < numCols; index++) {
      if (index == srcColIndex) {
        varDataOffset += copyColumn(srcColIndex, unsafe, memAddr, bytesLen,
            srcFormat, newBytes, index, varDataOffset);
        srcIndex++;
        if (srcIndex < columns.length) {
          srcColIndex = columns[srcIndex];
          srcColIndex--;
        }
        else {
          // some value that will never match
          srcColIndex = -1;
        }
      }
      else {
        final int offsetFromMap = this.positionMap[index];
        cd = this.columns[index];
        if (offsetFromMap >= this.nVersionBytes && !cd.isNullable) {
          // copy fixed null value
          DataValueDescriptor fixedZero = cd.columnType.getTypeId()
              .getStaticZeroForFixedType();
          fixedZero.writeBytes(newBytes, offsetFromMap, cd.columnType);
        }
        else {
          varDataOffset += generateColumn(index, null, cd, newBytes,
              varDataOffset);
        }
      }
    }
    return newBytes;
  }

  /**
   * Set specified columns with serialized row and return a new array of byte
   * arrays.
   *
   * @param columns
   *          bit set of columns to be modified
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final FormatableBitSet columns,
      @Unretained final OffHeapRowWithLobs srcRowArrays,
      @Unretained final OffHeapByteSource srcRow,
      final RowFormatter srcFormat, final int[] baseColumnMap)
      throws StandardException {

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema = (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    final int bitSetSize = columns.getLength();
    ColumnDescriptor cd;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (srcRowArrays != null) {
      bytesLen = srcRowArrays.getLength();
      memAddr = srcRowArrays.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = srcRow.getLength();
      memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    }

    int newRowLength = this.nVersionBytes;
    boolean hasFirstBytesChange = false;
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      if (!cd.isLob) {
        newRowLength += getTotalColumnWidth(srcIndex, unsafe, memAddr,
            bytesLen, srcFormat, cd, index, isSameSchema);
        hasFirstBytesChange = true;
      }
    }

    final byte[] newBytes;
    if (hasFirstBytesChange) {
      newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first
      writeVersion(newBytes);
    } else {
      newBytes = null;
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      if (!cd.isLob) {
        varDataOffset += copyColumn(srcIndex, unsafe, memAddr, bytesLen, srcFormat,
            newBytes, index, varDataOffset);
      }
      else {
        srcFormat.copyLobBytes(srcIndex, index, newByteArrays, srcRowArrays,
            columns, bitSetSize, cd, this);
      }
      if (baseColumnMap != null) {
        baseColumnMap[index] = srcIndex;
      }
    }
    return newByteArrays;
  }

  /**
   * Set specified columns with serialized row and return a new array of byte
   * arrays.
   *
   * @param columns
   *          bit set of columns to be modified
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final FormatableBitSet columns,
      final byte[][] srcRowArrays, byte[] srcRow,
      final RowFormatter srcFormat, final int[] baseColumnMap)
      throws StandardException {

    assert columns.getNumBitsSet() > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema = (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    if (srcRow == null && srcRowArrays != null) {
      srcRow = srcRowArrays[0];
    }
    final int bitSetSize = columns.getLength();
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    boolean hasFirstBytesChange = false;
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      if (!cd.isLob) {
        newRowLength += getTotalColumnWidth(srcIndex, srcRow, srcFormat, cd,
            index, isSameSchema);
        hasFirstBytesChange = true;
      }
    }

    final byte[] newBytes;
    if (hasFirstBytesChange) {
      newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first
      writeVersion(newBytes);
    } else {
      newBytes = null;
    }
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int srcIndex = columns.anySetBit(), index = 0; srcIndex != -1;
        srcIndex = columns.anySetBit(srcIndex), index++) {
      cd = srcFormat.columns[srcIndex];
      if (!cd.isLob) {
        varDataOffset += copyColumn(srcIndex, srcRow, srcFormat, newBytes,
            index, varDataOffset);
      } else {
        srcFormat.copyLobBytes(srcIndex, index, newByteArrays, srcRowArrays,
            columns, bitSetSize, cd, this);
      }
      if (baseColumnMap != null) {
        baseColumnMap[index] = srcIndex;
      }
    }
    return newByteArrays;
  }

  /**
   * Set specified columns with serialized row and return a new array of byte
   * arrays.
   *
   * @param columns
   *          positions of columns to be modified
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int[] columns, boolean zeroBased,
      @Unretained final OffHeapRowWithLobs srcRowArrays,
      @Unretained final OffHeapByteSource srcRow,
      final RowFormatter srcFormat) throws StandardException {

    assert columns.length > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    ColumnDescriptor cd;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (srcRowArrays != null) {
      bytesLen = srcRowArrays.getLength();
      memAddr = srcRowArrays.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = srcRow.getLength();
      memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    }

    boolean hasFirstBytesChange = false;
    int newRowLength = this.nVersionBytes;
    int srcIndex;
    final int numCols = columns.length;
    for (int index = 0; index < numCols; index++) {
      srcIndex = columns[index];
      if (!zeroBased) {
        srcIndex--;
      }
      if (srcIndex >= 0) {
        cd = srcFormat.columns[srcIndex];
        if (!cd.isLob) {
          newRowLength += getTotalColumnWidth(srcIndex, unsafe, memAddr,
              bytesLen, srcFormat, cd, index, isSameSchema);
          hasFirstBytesChange = true;
        }
      }
    }

    if (hasFirstBytesChange) {
      final byte[] newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first
      writeVersion(newBytes);
      // iterate through columns, for each column set value in new byte array,
      // taking the old value as dvd from srcValues or as bytes from rowToChange
      // as appropriate

      int varDataOffset = this.varDataOffset; // beginning of var data offset
      final int nCols = columns.length;
      for (int index = 0; index < nCols; index++) {
        srcIndex = columns[index];
        if (!zeroBased) {
          srcIndex--;
        }
        if (srcIndex >= 0) {
          cd = srcFormat.columns[srcIndex];
          if (!cd.isLob) {
            varDataOffset += copyColumn(srcIndex, unsafe, memAddr, bytesLen,
                srcFormat, newBytes, index, varDataOffset);
          }
          else {
            srcFormat.copyLobBytes(srcIndex, index, newByteArrays,
                srcRowArrays, cd, this);
          }
        }
      }
    } else {
      final int nCols = columns.length;
      for (int index = 0; index < nCols; index++) {
        srcIndex = columns[index];
        if (!zeroBased) {
          srcIndex--;
        }
        if (srcIndex >= 0) {
          cd = srcFormat.columns[srcIndex];
          if (cd.isLob) {
            srcFormat.copyLobBytes(srcIndex, index, newByteArrays,
                srcRowArrays, cd, this);
          }
        }
      }
    }
    return newByteArrays;
  }

  /**
   * Set specified columns with serialized row and return a new array of byte
   * arrays.
   *
   * @param columns
   *          positions of columns to be modified
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int[] columns, boolean zeroBased,
      final byte[][] srcRowArrays, byte[] srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert columns.length > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    if (srcRow == null && srcRowArrays != null) {
      srcRow = srcRowArrays[0];
    }
    ColumnDescriptor cd;

    boolean hasFirstBytesChange = false;
    int newRowLength = this.nVersionBytes;
    int srcIndex;
    final int numCols = columns.length;
    for (int index = 0; index < numCols; index++) {
      srcIndex = columns[index];
      if (!zeroBased) {
        srcIndex--;
      }
      if (srcIndex >= 0) {
        cd = srcFormat.columns[srcIndex];
        if (!cd.isLob) {
          newRowLength += getTotalColumnWidth(srcIndex, srcRow, srcFormat, cd,
              index, isSameSchema);
          hasFirstBytesChange = true;
        }
      }
    }

    if (hasFirstBytesChange) {
      final byte[] newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first

      writeVersion(newBytes);
      // iterate through columns, for each column set value in new byte array,
      // taking the old value as dvd from srcValues or as bytes from rowToChange
      // as appropriate

      int varDataOffset = this.varDataOffset; // beginning of var data offset
      final int nCols = columns.length;
      for (int index = 0; index < nCols; index++) {
        srcIndex = columns[index];
        if (!zeroBased) {
          srcIndex--;
        }
        if (srcIndex >= 0) {
          cd = srcFormat.columns[srcIndex];
          if (!cd.isLob) {
            varDataOffset += copyColumn(srcIndex, srcRow, srcFormat, newBytes,
                index, varDataOffset);
          }
          else {
            srcFormat.copyLobBytes(srcIndex, index, newByteArrays,
                srcRowArrays, cd, this);
          }
        }
      }
    } else {
      final int nCols = columns.length;
      for (int index = 0; index < nCols; index++) {
        srcIndex = columns[index];
        if (!zeroBased) {
          srcIndex--;
        }
        if (srcIndex >= 0) {
          cd = srcFormat.columns[srcIndex];
          if (cd.isLob) {
            srcFormat.copyLobBytes(srcIndex, index, newByteArrays,
                srcRowArrays, cd, this);
          }
        }
      }
    }
    return newByteArrays;
  }

  /**
   * Set specified number of columns with serialized row and return a new byte
   * array.
   *
   * @param nCols
   *          the number of columns from the start to copy from source
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols, final byte[] srcRow,
      final RowFormatter srcFormat) throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + Arrays.toString(srcRow);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    for (int index = 0; index < nCols; index++) {
      cd = srcFormat.columns[index];
      newRowLength += getTotalColumnWidth(index, srcRow, srcFormat, cd, index,
          isSameSchema);
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int index = 0; index < nCols; index++) {
      varDataOffset += copyColumn(index, srcRow, srcFormat, newBytes, index,
          varDataOffset);
    }
    return newBytes;
  }

  /**
   * Set specified number of columns with serialized row and return a new byte
   * array.
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final RowFormatter srcFormat)
      throws StandardException {

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    ColumnDescriptor cd;

    int newRowLength = this.nVersionBytes;
    for (int index = 0; index < nCols; index++) {
      cd = srcFormat.columns[index];
      newRowLength += getTotalColumnWidth(index, unsafe, memAddr, bytesLen,
          srcFormat, cd, index, isSameSchema);
    }

    final byte[] newBytes = new byte[newRowLength];
    // write the version, if any, first
    writeVersion(newBytes);
    // iterate through columns, for each column set value in new byte array,
    // taking the old value as dvd from srcValues or as bytes from rowToChange
    // as appropriate

    int varDataOffset = this.varDataOffset; // beginning of var data offset
    for (int index = 0; index < nCols; index++) {
      varDataOffset += copyColumn(index, unsafe, memAddr, bytesLen, srcFormat,
          newBytes, index, varDataOffset);
    }
    return newBytes;
  }

  /**
   * Set specified number of columns with serialized row and return a new byte
   * array.
   *
   * @param nCols
   *          the number of columns from the start to copy from source
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols, @Unretained final OffHeapRow srcRow,
      final RowFormatter srcFormat) throws StandardException {

    assert nCols > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(nCols, unsafe, memAddr, bytesLen, srcFormat);
  }

  /**
   * Set specified number of columns with serialized row and return a new byte
   * array.
   *
   * @param nCols
   *          the number of columns from the start to copy from source
   * @param srcRow
   *          the source row from which the columns have to be copied
   * @param srcFormat
   *          the formatter of the srcRow
   *
   * @return the new byte array
   */
  final byte[] setColumns(final int nCols,
      @Unretained final OffHeapByteSource srcRow, final RowFormatter srcFormat)
      throws StandardException {

    assert nCols > 0: "setColumns with zero columns to set: "
        + Arrays.toString(srcRow != null ? srcRow.getRowBytes() : null);

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = srcRow.getLength();
    final long memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    return setColumns(nCols, unsafe, memAddr, bytesLen, srcFormat);
  }

  /**
   * Set specified number of columns with serialized row and return a new array
   * of byte arrays. Also upgrades to the latest schema version if this is not
   * the latest one.
   *
   * @param nCols
   *          the number of columns from the start to copy from source
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int nCols,
      @Unretained final OffHeapRowWithLobs srcRowArrays,
      @Unretained final OffHeapByteSource srcRow,
      final RowFormatter srcFormat) throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    ColumnDescriptor cd;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen;
    final long memAddr;
    if (srcRowArrays != null) {
      bytesLen = srcRowArrays.getLength();
      memAddr = srcRowArrays.getUnsafeAddress(0, bytesLen);
    }
    else {
      bytesLen = srcRow.getLength();
      memAddr = srcRow.getUnsafeAddress(0, bytesLen);
    }

    boolean hasFirstBytesChange = false;
    int newRowLength = this.nVersionBytes;
    for (int index = 0; index < nCols; index++) {
      cd = srcFormat.columns[index];
      if (!cd.isLob) {
        newRowLength += getTotalColumnWidth(index, unsafe, memAddr, bytesLen,
            srcFormat, cd, index, isSameSchema);
        hasFirstBytesChange = true;
      }
    }

    if (hasFirstBytesChange) {
      final byte[] newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first
      writeVersion(newBytes);
      // iterate through columns, for each column set value in new byte array,
      // taking the old value as dvd from srcValues or as bytes from rowToChange
      // as appropriate

      int varDataOffset = this.varDataOffset; // beginning of var data offset
      for (int index = 0; index < nCols; index++) {
        cd = srcFormat.columns[index];
        if (!cd.isLob) {
          varDataOffset += copyColumn(index, unsafe, memAddr, bytesLen, srcFormat,
              newBytes, index, varDataOffset);
        } else {
          srcFormat.copyLobBytes(index, newByteArrays, srcRowArrays, nCols, cd,
              this);
        }
      }
    } else {
      for (int index = 0; index < nCols; index++) {
        cd = srcFormat.columns[index];
        if (cd.isLob) {
          srcFormat.copyLobBytes(index, newByteArrays, srcRowArrays, nCols, cd,
              this);
        }
      }
    }
    return newByteArrays;
  }

  /**
   * Set specified number of columns with serialized row and return a new array
   * of byte arrays. Also upgrades to the latest schema version if this is not
   * the latest one.
   *
   * @param nCols
   *          the number of columns from the start to copy from source
   * @param srcRowArrays
   *          the source array of byte arrays from which the columns have to be
   *          copied
   * @param srcRow
   *          the first row of srcRowArrays (or if null then read from
   *          srcRowArrays); as a special case this can be non-null while
   *          srcRowArrays can be null when copying from non-LOB source to LOB
   * @param srcFormat
   *          the formatter of the srcRowArrays
   *
   * @return a new array of byte arrays
   */
  final byte[][] setColumns(final int nCols, final byte[][] srcRowArrays,
      byte[] srcRow, final RowFormatter srcFormat) throws StandardException {

    assert nCols > 0 : "setColumns with zero columns to set: "
        + ArrayUtils.objectString(srcRowArrays);

    final boolean isSameSchema =
        (this.schemaVersion == srcFormat.schemaVersion);
    final byte[][] newByteArrays = new byte[this.numLobs + 1][];
    if (srcRow == null && srcRowArrays != null) {
      srcRow = srcRowArrays[0];
    }
    ColumnDescriptor cd;

    boolean hasFirstBytesChange = false;
    int newRowLength = this.nVersionBytes;
    for (int index = 0; index < nCols; index++) {
      cd = srcFormat.columns[index];
      if (!cd.isLob) {
        newRowLength += getTotalColumnWidth(index, srcRow, srcFormat, cd,
            index, isSameSchema);
        hasFirstBytesChange = true;
      }
    }

    if (hasFirstBytesChange) {
      final byte[] newBytes = new byte[newRowLength];
      newByteArrays[0] = newBytes;
      // write the version, if any, first
      writeVersion(newBytes);
      // iterate through columns, for each column set value in new byte array,
      // taking the old value as dvd from srcValues or as bytes from rowToChange
      // as appropriate

      int varDataOffset = this.varDataOffset; // beginning of var data offset
      for (int index = 0; index < nCols; index++) {
        cd = srcFormat.columns[index];
        if (!cd.isLob) {
          varDataOffset += copyColumn(index, srcRow, srcFormat, newBytes,
              index, varDataOffset);
        } else {
          srcFormat.copyLobBytes(index, newByteArrays, srcRowArrays, nCols, cd,
              this);
        }
      }
    } else {
      for (int index = 0; index < nCols; index++) {
        cd = srcFormat.columns[index];
        if (cd.isLob) {
          srcFormat.copyLobBytes(index, newByteArrays, srcRowArrays, nCols, cd,
              this);
        }
      }
    }
    return newByteArrays;
  }

  private void copyLobBytes(final int index, final int currentIndex,
      final byte[][] newByteArrays, @Unretained OffHeapRowWithLobs srcRowArrays,
      final ColumnDescriptor cd, final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[currentIndex];
    // for the special case of default value after ALTER TABLE, copy the
    // default bytes
    if (lobIndex != 0 && srcRowArrays != null) {
      newByteArrays[targetIndex] = srcRowArrays.getGfxdBytes(lobIndex);
    }
    else {
      newByteArrays[targetIndex] = cd.columnDefaultBytes;
    }
  }

  private void copyLobBytes(final int index, final int currentIndex,
      final byte[][] newByteArrays, final byte[][] srcRowArrays,
      final ColumnDescriptor cd, final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[currentIndex];
    // for the special case of default value after ALTER TABLE, copy the
    // default bytes
    if (lobIndex != 0 && srcRowArrays != null) {
      newByteArrays[targetIndex] = srcRowArrays[lobIndex];
    } else {
      newByteArrays[targetIndex] = cd.columnDefaultBytes;
    }
  }

  private void copyLobBytes(final int index, final int currentIndex,
      final byte[][] newByteArrays,
      @Unretained final OffHeapRowWithLobs srcRowArrays,
      final FormatableBitSet columns, final int numChanged,
      final ColumnDescriptor cd, final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[currentIndex];
    if (index < numChanged && columns.isSet(index)) {
      // for the special case of default value after ALTER TABLE, copy the
      // default bytes
      if (lobIndex != 0 && srcRowArrays != null) {
        newByteArrays[targetIndex] = srcRowArrays.getGfxdBytes(lobIndex);
      } else {
        newByteArrays[targetIndex] = cd.columnDefaultBytes;
      }
    }
  }

  private void copyLobBytes(final int index, final int currentIndex,
      final byte[][] newByteArrays, final byte[][] srcRowArrays,
      final FormatableBitSet columns, final int numChanged,
      final ColumnDescriptor cd, final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[currentIndex];
    if (index < numChanged && columns.isSet(index)) {
      // for the special case of default value after ALTER TABLE, copy the
      // default bytes
      if (lobIndex != 0 && srcRowArrays != null) {
        newByteArrays[targetIndex] = srcRowArrays[lobIndex];
      } else {
        newByteArrays[targetIndex] = cd.columnDefaultBytes;
      }
    }
  }

  private void copyLobBytes(final int index, final byte[][] newByteArrays,
      @Unretained final OffHeapRowWithLobs srcRowArrays, final int nCols,
      final ColumnDescriptor cd, final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[index];
    if (index < nCols) {
      // for the special case of default value after ALTER TABLE, copy the
      // default bytes
      if (lobIndex != 0 && srcRowArrays != null) {
        newByteArrays[targetIndex] = srcRowArrays.getGfxdBytes(lobIndex);
      } else {
        newByteArrays[targetIndex] = cd.columnDefaultBytes;
      }
    }
  }

  private void copyLobBytes(final int index, final byte[][] newByteArrays,
      final byte[][] srcRowArrays, final int nCols, final ColumnDescriptor cd,
      final RowFormatter currentFormat) {
    final int lobIndex = this.positionMap[index];
    final int targetIndex = currentFormat.positionMap[index];
    if (index < nCols) {
      // for the special case of default value after ALTER TABLE, copy the
      // default bytes
      if (lobIndex != 0 && srcRowArrays != null) {
        newByteArrays[targetIndex] = srcRowArrays[lobIndex];
      } else {
        newByteArrays[targetIndex] = cd.columnDefaultBytes;
      }
    }
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column to get (1-based)
   * @param bytes
   *          the row in byte array format, if null then a null-valued dvd is
   *          returned.
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      final byte[] bytes) throws StandardException {
    final int index = logicalPosition - 1;
    final DataValueDescriptor dvd;
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      dvd = cd.columnType.getNull();
      final int bytesRead = dvd.readBytes(bytes, offset, columnWidth);
      assert bytesRead == columnWidth : "bytesRead=" + bytesRead
          + ", columnWidth=" + columnWidth + " for " + dvd;
    } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
      dvd = cd.columnType.getNull();
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth
          + " for pos=" + logicalPosition;
      dvd = cd.columnType.getNull();
      if (cd.columnDefault != null) {
        dvd.setValue(cd.columnDefault);
      }
    }
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getColumn(byte[]): for column at position "
                + logicalPosition + " returning DVD {" + dvd + ", type: "
                + cd.columnType + '}');
      }
    }
    return dvd;
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column to get (1-based)
   * @param bytes
   *          the row in byte array format, if null then a null-valued dvd is
   *          returned.
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      @Unretained final OffHeapRow bytes) throws StandardException {

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    return getColumn(logicalPosition, unsafe, memAddr, bytesLen, bytes);
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final OffHeapRow row) throws StandardException {
    final int index = logicalPosition - 1;
    final DataValueDescriptor dvd;
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      dvd = cd.columnType.getNull();
      final int bytesRead = dvd.readBytes(memAddr + offset,
          columnWidth, row);
      assert bytesRead == columnWidth : "bytesRead=" + bytesRead
          + ", columnWidth=" + columnWidth + " for " + dvd;
    } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
      dvd = cd.columnType.getNull();
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth
          + " for pos=" + logicalPosition;
      dvd = cd.columnType.getNull();
      if (cd.columnDefault != null) {
        dvd.setValue(cd.columnDefault);
      }
    }
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getColumn(byte[]): for column at position "
                + logicalPosition + " returning DVD {" + dvd + ", type: "
                + cd.columnType + '}');
      }
    }
    return dvd;
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column to get (1-based)
   * @param byteArrays
   *          the row in array of byte array format
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays) throws StandardException {
    return getColumn(logicalPosition, UnsafeMemoryChunk.getUnsafeWrapper(), 0,
        0, byteArrays);
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column to get (1-based)
   * @param byteArrays
   *          the row in array of byte array format
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      final UnsafeWrapper unsafe, long memAddr0th, int bytesLen0th,
      @Unretained final OffHeapRowWithLobs byteArrays) throws StandardException {
    final int index = logicalPosition - 1;
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final DataValueDescriptor dvd;
    if (byteArrays != null) {
      if (!cd.isLob) {
        if (memAddr0th == 0) {
          bytesLen0th = byteArrays.getLength();
          memAddr0th = byteArrays.getUnsafeAddress(0, bytesLen0th);
        }

        final long offsetAndWidth = getOffsetAndWidth(index, unsafe,
            memAddr0th, bytesLen0th, offsetFromMap, cd);
        if (offsetAndWidth >= 0) {
          final int columnWidth = (int)offsetAndWidth;
          final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
          dvd = cd.columnType.getNull();
          final int bytesRead = dvd.readBytes(memAddr0th + offset,
              columnWidth, byteArrays);
          assert bytesRead == columnWidth : "bytesRead=" + bytesRead
              + ", columnWidth=" + columnWidth + " for " + dvd;
        } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
          dvd = cd.columnType.getNull();
        } else {
          assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
          dvd = cd.columnType.getNull();
          if (cd.columnDefault != null) {
            dvd.setValue(cd.columnDefault);
          }
        }
      } else {
        dvd = cd.columnType.getNull();
        // for the special case of default value after ALTER TABLE, copy the
        // default bytes
        if (offsetFromMap != 0) {
          final Object lobRow = byteArrays.getGfxdByteSource(offsetFromMap);
          if (lobRow != null) {
            if (lobRow instanceof byte[]) {
              final byte[] bytes = (byte[])lobRow;
              final int bytesRead = dvd.readBytes(bytes, 0, bytes.length);
              assert bytesRead == bytes.length: "bytesRead=" + bytesRead
                  + ", bytesLen=" + bytes.length + " for " + dvd;
            }
            else {
              final OffHeapByteSource bs = (OffHeapByteSource)lobRow;
              final int len = bs.getLength();
              final int bytesRead = dvd.readBytes(
                  bs.getUnsafeAddress(0, len), len, bs);
              assert bytesRead == len: "bytesRead=" + bytesRead + ", bytesLen="
                  + len + " for " + dvd;
            }
          }
        } else if (cd.columnDefault != null) {
          dvd.setValue(cd.columnDefault);
        }
      }
    } else {
      dvd = cd.columnType.getNull();
    }
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getColumn(byte[][]): for column at position "
                + logicalPosition + " returning DVD {" + dvd + ", type: "
                + cd.columnType + '}');
      }
    }
    return dvd;
  }

  /**
   * Get the Data Value Descriptor for specified column
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column to get (1-based)
   * @param byteArrays
   *          the row in array of byte array format
   * @return DVD for the specific column
   */
  public final DataValueDescriptor getColumn(final int logicalPosition,
      final byte[][] byteArrays) throws StandardException {
    final int index = logicalPosition - 1;
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final DataValueDescriptor dvd;
    if (byteArrays != null) {
      if (!cd.isLob) {
        final byte[] bytes = byteArrays[0];
        final long offsetAndWidth = getOffsetAndWidth(index, bytes,
            offsetFromMap, cd);
        if (offsetAndWidth >= 0) {
          final int columnWidth = (int)offsetAndWidth;
          final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
          dvd = cd.columnType.getNull();
          final int bytesRead = dvd.readBytes(bytes, offset, columnWidth);
          assert bytesRead == columnWidth : "bytesRead=" + bytesRead
              + ", columnWidth=" + columnWidth + " for " + dvd;
        } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
          dvd = cd.columnType.getNull();
        } else {
          assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
          dvd = cd.columnType.getNull();
          if (cd.columnDefault != null) {
            dvd.setValue(cd.columnDefault);
          }
        }
      } else {
        dvd = cd.columnType.getNull();
        // for the special case of default value after ALTER TABLE, copy the
        // default bytes
        if (offsetFromMap != 0) {
          final byte[] bytes = byteArrays[offsetFromMap];
          if (bytes != null) {
            final int bytesRead = dvd.readBytes(bytes, 0, bytes.length);
            assert bytesRead == bytes.length : "bytesRead=" + bytesRead
                + ", bytesLen=" + bytes.length + " for " + dvd;
          }
        } else if (cd.columnDefault != null) {
          dvd.setValue(cd.columnDefault);
        }
      }
    } else {
      dvd = cd.columnType.getNull();
    }
     if (SanityManager.DEBUG) {
       if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getColumn(byte[][]): for column at position "
                + logicalPosition + " returning DVD {" + dvd + ", type: "
                + cd.columnType + '}');
       }
    }
    return dvd;
  }

  /**
   * Compute hash for the specified column without deserializing.
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column (1-based)
   * @param bytes
   *          the row in byte array format
   * @param hash
   *          the hash value into which the hash of given column has to be
   *          accumulated
   *
   * @return the new hash after accumulating the hash of given column into
   *         <code>hash</code> argument
   */
  public final int computeHashCode(final int logicalPosition,
      final byte[] bytes, int hash) {
    final int colIndex = logicalPosition - 1;
    final int offsetFromMap = this.positionMap[colIndex];
    final ColumnDescriptor cd = this.columns[colIndex];
    final long offsetAndWidth = getOffsetAndWidth(colIndex, bytes,
        offsetFromMap, cd);
    final int typeId = cd.columnType.getTypeId().getTypeFormatId();
    int offset, width;
    if (offsetAndWidth >= 0) {
      width = (int)offsetAndWidth;
      offset = (int)(offsetAndWidth >>> Integer.SIZE);
      if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
        int idx = offset + width - 1;
        while (idx >= offset && bytes[idx] == 0x20) {
          idx--;
        }
        hash = ResolverUtils.addBytesToBucketHash(bytes, offset, idx + 1
            - offset, hash, typeId);
      }
      else {
        hash = ResolverUtils.addBytesToBucketHash(bytes, offset, width, hash,
            typeId);
        // KN: Blank padding of Varchar type should never be done.
        if (/*(typeId == StoredFormatIds.VARCHAR_TYPE_ID)
            ||*/(typeId == StoredFormatIds.CHAR_TYPE_ID)
            || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
          int maxWidth = cd.getType().getMaximumWidth();
          // KN: merge work --- again blank padding while calculating hash.
          while (width++ < maxWidth) {
            // blank padding for CHAR/CHAR FOR BIT DATA
            hash = ResolverUtils.addByteToHash((byte)0x20, hash);
          }
        }
      }
    }
    else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // add single 0 byte for null
      hash = ResolverUtils.addByteToBucketHash((byte)0, hash, typeId);
    }
    else {
      assert offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
      // add hash for serialized default DVD bytes
      final byte[] defaultBytes = cd.columnDefaultBytes;
      if (defaultBytes != null) {
        hash = ResolverUtils.addBytesToBucketHash(defaultBytes, hash, typeId);
      }
      else {
        // add single 0 byte for null
        hash = ResolverUtils.addByteToBucketHash((byte)0, hash, typeId);
      }
    }
    return hash;
  }

  /**
   * Compute hash for the specified column without deserializing.
   *
   * @param logicalPosition
   *          the logical ("ordinal") position of the column (1-based)
   * @param unsafe
   *          handle for unsafe memory reader
   * @param memAddr
   *          the base unsafe memory address of the row
   * @param bytesLen
   *          the length of row
   * @param hash
   *          the hash value into which the hash of given column has to be
   *          accumulated
   *
   * @return the new hash after accumulating the hash of given column into
   *         <code>hash</code> argument
   */
  public final int computeHashCode(final int logicalPosition,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      int hash) {
    final int colIndex = logicalPosition - 1;
    final int offsetFromMap = this.positionMap[colIndex];
    final ColumnDescriptor cd = this.columns[colIndex];
    final long offsetAndWidth = getOffsetAndWidth(colIndex, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    final int typeId = cd.columnType.getTypeId().getTypeFormatId();
    int offset, width;
    if (offsetAndWidth >= 0) {
      width = (int)offsetAndWidth;
      offset = (int)(offsetAndWidth >>> Integer.SIZE);
      if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
        final long offsetAddr = memAddr + offset;
        long idxAddr = offsetAddr + (width - 1);
        while (idxAddr >= offsetAddr && unsafe.getByte(idxAddr) == 0x20) {
          idxAddr--;
        }
        hash = ServerResolverUtils.addBytesToBucketHash(unsafe, offsetAddr,
            (int)(idxAddr + 1 - offsetAddr), hash, typeId);
      }
      else {
        hash = ServerResolverUtils.addBytesToBucketHash(unsafe,
            memAddr + offset, width, hash, typeId);
      }
      // KN: Blank padding of Varchar type should never be done.
      if (/*(typeId == StoredFormatIds.VARCHAR_TYPE_ID)
          ||*/(typeId == StoredFormatIds.CHAR_TYPE_ID)
          || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
        int maxWidth = cd.getType().getMaximumWidth();
        // KN: merge work --- again blank padding while calculating hash.
        while (width++ < maxWidth) {
          // blank padding for CHAR/CHAR FOR BIT DATA
          hash = ResolverUtils.addByteToHash((byte)0x20, hash);
        }
      }
    }
    else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
      // add single 0 byte for null
      hash = ResolverUtils.addByteToBucketHash((byte)0, hash, typeId);
    }
    else {
      assert offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
      // add hash for serialized default DVD bytes
      final byte[] defaultBytes = cd.columnDefaultBytes;
      if (defaultBytes != null) {
        hash = ResolverUtils.addBytesToBucketHash(defaultBytes, hash, typeId);
      }
      else {
        // add single 0 byte for null
        hash = ResolverUtils.addByteToBucketHash((byte)0, hash, typeId);
      }
    }
    return hash;
  }

  /**
   * Returns a {@link DataValueDescriptor} with no value set
   *
   * @param logicalPosition
   *          logical position of the column
   * @return the {@link DataValueDescriptor} of the column at specified logical
   *         position
   */
  public final DataValueDescriptor getNewNull(int logicalPosition)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    return cd.columnType.getNull();
  }

  /**
   * Set the value in the given Data Value Descriptor for specified column.
   *
   * @param result
   *          the {@link DataValueDescriptor} to be filled; assumed to be
   *          non-null
   * @param index
   *          the index of the column to get (0-based)
   * @param bytes
   *          the row in byte array format, if null then a null-valued dvd is
   *          returned.
   */
  public final void setDVDColumn(final DataValueDescriptor result,
      final int index, final byte[] bytes) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final DataTypeDescriptor dtd = cd.columnType;
      if (result.getTypeFormatId() == dtd.getDVDTypeFormatId()) {
        // if target and source types match, then directly read into the DVD
        final int bytesRead = result.readBytes(bytes, offset, columnWidth);
        assert bytesRead == columnWidth : "bytesRead=" + bytesRead
            + ", columnWidth=" + columnWidth + " for " + result;
      } else {
        // target and source types do not match, so set type specific value
        // directly into DVD
        DataTypeUtilities.setInDVD(result, bytes, offset, columnWidth, dtd);
      }
    } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
      result.restoreToNull();
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (cd.columnDefault != null) {
        result.setValue(cd.columnDefault);
      } else {
        result.restoreToNull();
      }
    }
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#setDVDColumn(byte[]): for column at position "
                + (index + 1) + " returning DVD {" + result + ", type: "
                + cd.columnType + '}');
      }
    }
  }

  private void setDVDColumn(final DataValueDescriptor result,
      final int index, final UnsafeWrapper unsafe, final long memAddr,
      final int bytesLen, @Unretained final OffHeapByteSource bs,
      final int offsetFromMap, final ColumnDescriptor cd)
      throws StandardException {

    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final DataTypeDescriptor dtd = cd.columnType;
      if (result.getTypeFormatId() == dtd.getDVDTypeFormatId()) {
        // if target and source types match, then directly read into the DVD
        final int bytesRead = result.readBytes(memAddr + offset,
            columnWidth, bs);
        assert bytesRead == columnWidth: "bytesRead=" + bytesRead
            + ", columnWidth=" + columnWidth + " for " + result;
      }
      else {
        // target and source types do not match, so set type specific value
        // directly into DVD
        DataTypeUtilities.setInDVD(result, unsafe, memAddr + offset,
            columnWidth, bs, dtd);
      }
    }
    else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
      result.restoreToNull();
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (cd.columnDefault != null) {
        result.setValue(cd.columnDefault);
      }
      else {
        result.restoreToNull();
      }
    }
  }

  /**
   * Set the value in the given Data Value Descriptor for specified column.
   *
   * @param result
   *          the {@link DataValueDescriptor} to be filled; assumed to be
   *          non-null
   * @param index
   *          the index of the column to get (0-based)
   * @param bytes
   *          the row in byte array format, if null then a null-valued dvd is
   *          returned.
   */
  public final void setDVDColumn(final DataValueDescriptor result,
      final int index, final UnsafeWrapper unsafe, final long memAddr,
      final int bytesLen, @Unretained final OffHeapRow bytes)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];

    setDVDColumn(result, index, unsafe, memAddr, bytesLen, bytes,
        offsetFromMap, cd);
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#setDVDColumn(OffHeapRow): for column at position "
                + (index + 1) + " returning DVD {" + result + ", type: "
                + cd.columnType + '}');
      }
    }
  }

  /**
   * Set the value in the given Data Value Descriptor for specified column.
   *
   * @param result
   *          the {@link DataValueDescriptor} to be filled; assumed to be
   *          non-null
   * @param index
   *          the index of the column to get (0-based)
   * @param byteArrays
   *          the row in byte array format, if null then a null-valued dvd is
   *          returned.
   */
  public final void setDVDColumn(final DataValueDescriptor result,
      final int index, final UnsafeWrapper unsafe, final long memAddr,
      final int bytesLen, @Unretained final OffHeapRowWithLobs byteArrays)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];

    if (!cd.isLob) {
      setDVDColumn(result, index, unsafe, memAddr, bytesLen, byteArrays,
          offsetFromMap, cd);
    }
    else {
      // for the special case of default value after ALTER TABLE, copy the
      // default bytes
      if (offsetFromMap != 0) {
        final Object lobRow = byteArrays.getGfxdByteSource(offsetFromMap);
        if (lobRow != null) {
          final DataTypeDescriptor dtd = cd.columnType;
          if (lobRow instanceof byte[]) {
            final byte[] bytes = (byte[])lobRow;
            if (result.getTypeFormatId() == dtd.getDVDTypeFormatId()) {
              final int bytesRead = result.readBytes(bytes, 0, bytes.length);
              assert bytesRead == bytes.length: "bytesRead=" + bytesRead
                  + ", bytesLen=" + bytes.length + " for " + result;
            }
            else {
              // target and source types do not match, so set type specific
              // value directly into DVD
              DataTypeUtilities.setInDVD(result, bytes, 0, bytes.length, dtd);
            }
          }
          else {
            @Unretained
            final OffHeapByteSource bs = (OffHeapByteSource)lobRow;
            final int len = bs.getLength();
            final long memOffset = bs.getUnsafeAddress(0, len);
            if (result.getTypeFormatId() == dtd.getDVDTypeFormatId()) {
              final int bytesRead = result
                  .readBytes(memOffset, len, bs);
              assert bytesRead == len: "bytesRead=" + bytesRead + ", bytesLen="
                  + len + " for " + result;
            }
            else {
              // target and source types do not match, so set type specific
              // value directly into DVD
              DataTypeUtilities.setInDVD(result, unsafe, memOffset, len, bs,
                  dtd);
            }
          }
        }
        else {
          result.restoreToNull();
        }
      }
      else if (cd.columnDefault != null) {
        result.setValue(cd.columnDefault);
      }
      else {
        result.restoreToNull();
      }
    }
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#setDVDColumn(OffHeapRowWithLobs): for column "
                + "at position " + (index + 1) + " returning DVD {" + result
                + ", type: " + cd.columnType + '}');
      }
    }
  }

  public final byte[] extractReferencedKeysForPKOrNullIfNull(
      final byte[] rowBytes, final int[] columns,
      final RowFormatter targetFormat, final int[] targetColumns,
      final RowFormatter targetTableFormat, final int[] targetPkCols) {
    int sz = targetFormat.nVersionBytes;
    int posIndex;
    final int numCols = columns.length;
    for (int index = 0; index < numCols; index++) {
      posIndex = columns[index] - 1;
      final int width = getColumnWidth(posIndex, rowBytes,
          this.columns[posIndex]);

      // skip FK check like other DBs if any column is null (bug #41168)
      if (width == 0) {
        return null;
      }

      sz += width;

      // account for data length to be written
      // fixed length source column (child table) can translate to variable
      // length target (parent table).
      // or it might be variable length column for both (parent & child) tables,
      // in this case parent table
      // offset is the number of bytes that is to be written.
      //
      // TODO: [sumedh] Below is not complete; converting CHAR to VARCHAR,
      // for example, requires more than just column width write since
      // the serialization itself is different; in general if the types
      // are different we will need to create DVD, then setValue and serialize.
      // Below is only useful to handle the case when getNumOffsetBytes are different
      // in source and target.
      if (targetTableFormat.positionMap[targetColumns[index] - 1] < 0) {
        sz += targetFormat.getNumOffsetBytes();
      }
    }

    final byte[] columnBytes = new byte[sz];
    int targetIndex, targetTablePos;
    int varOffset = targetFormat.varDataOffset;
    targetFormat.writeVersion(columnBytes);
    final int nCols = columns.length;
    for (int index = 0; index < nCols; index++) {
      targetTablePos = targetColumns[index];
      targetIndex = index;
      if (targetTablePos != targetPkCols[index]) {
        // rare case, so searching columns on the fly is okay w.r.t. perf
        int j;
        for (j = 0; j < targetPkCols.length; j++) {
          if (targetTablePos == targetPkCols[j]) {
            targetIndex = j;
            break;
          }
        }
        if (j >= targetPkCols.length) {
          Assert.fail("unexpected failure in locating the referenced column "
              + targetTableFormat.columns[targetTablePos]
              + " in target PK formatter having columns "
              + Arrays.toString(targetFormat.columns));
        }
      }
      varOffset = processColumn(rowBytes, columns[index] - 1,
          extractColumnBytes, columnBytes, varOffset, targetFormat,
          targetIndex);
    }
    return columnBytes;
  }

  public final byte[] extractReferencedKeysForPKOrNullIfNull(
      @Unretained final OffHeapByteSource rowBytes, final int[] columns,
      final RowFormatter targetFormat, final int[] targetColumns,
      final RowFormatter targetTableFormat, final int[] targetPkCols) {
    int sz = targetFormat.nVersionBytes;
    int posIndex;
    final int numCols = columns.length;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowBytes.getLength();
    final long memAddr = rowBytes.getUnsafeAddress(0, bytesLen);

    for (int index = 0; index < numCols; index++) {
      posIndex = columns[index] - 1;
      final int width = getColumnWidth(posIndex, unsafe, memAddr, bytesLen,
          this.columns[posIndex]);

      // skip FK check like other DBs if any column is null (bug #41168)
      if (width == 0) {
        return null;
      }

      sz += width;

      // account for data length to be written
      // fixed length source column (child table) can translate to variable
      // length target (parent table).
      // or it might be variable length column for both (parent & child) tables,
      // in this case parent table
      // offset is the number of bytes that is to be written.
      //
      // TODO: [sumedh] Below is not complete; converting CHAR to VARCHAR,
      // for example, requires more than just column width write since
      // the serialization itself is different; in general if the types
      // are different we will need to create DVD, then setValue and serialize.
      // Below is only useful to handle the case when getNumOffsetBytes are different
      // in source and target.
      if (targetTableFormat.positionMap[targetColumns[index] - 1] < 0) {
        sz += targetFormat.getNumOffsetBytes();
      }
    }

    final byte[] columnBytes = new byte[sz];
    int targetIndex, targetTablePos;
    int varOffset = targetFormat.varDataOffset;
    targetFormat.writeVersion(columnBytes);
    final int nCols = columns.length;
    for (int index = 0; index < nCols; index++) {
      targetTablePos = targetColumns[index];
      targetIndex = index;
      if (targetTablePos != targetPkCols[index]) {
        // rare case, so searching columns on the fly is okay w.r.t. perf
        int j;
        for (j = 0; j < targetPkCols.length; j++) {
          if (targetTablePos == targetPkCols[j]) {
            targetIndex = j;
            break;
          }
        }
        if (j >= targetPkCols.length) {
          Assert.fail("unexpected failure in locating the referenced column "
              + targetTableFormat.columns[targetTablePos]
              + " in target PK formatter having columns "
              + Arrays.toString(targetFormat.columns));
        }
      }
      varOffset = processColumn(unsafe, memAddr, bytesLen, columns[index] - 1,
          extractColumnBytesOffHeap, columnBytes, varOffset, targetFormat,
          targetIndex);
    }
    return columnBytes;
  }

  /**
   * Serialize columns as a byte[] using the given target RowFormatter. The
   * columns in target are assumed to be in sequence from 1 to size of "columns"
   * array i.e. for every <i>i</i> in 1, 2, 3, ... columns.length, column
   * position <i>columns[i-1]</i> in this RowFormatter maps to column position
   * <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target formatter.
   */
  public final byte[] generateColumns(final byte[] rowBytes,
      final int[] columns, final RowFormatter targetFormat) {
    int sz = targetFormat.nVersionBytes;
    int posIndex;
    ColumnDescriptor cd;
    final int numCols = columns.length;
    final boolean truncateSpaces = targetFormat.isPrimaryKeyFormatter();
    for (int index = 0; index < numCols; index++) {
      posIndex = columns[index] - 1;
      cd = this.columns[posIndex];
      if (!cd.isLob) {
        sz += getColumnWidth(posIndex, rowBytes, cd, truncateSpaces);

        // account for data length to be written
        // fixed length source column (child table) can translate to variable
        // length target (parent table).
        // or it might be variable length column for both (parent & child)
        // tables,
        // in this case parent table
        // offset is the number of bytes that is to be written.
        if (targetFormat.positionMap[index] < 0) {
          sz += targetFormat.getNumOffsetBytes();
        }
      }
    }

    final byte[] columnBytes = new byte[sz];
    // write the version, if any, first
    targetFormat.writeVersion(columnBytes);
    int varOffset = targetFormat.varDataOffset;
    final int nCols = columns.length;
    for (int index = 0; index < nCols; index++) {
      cd = targetFormat.columns[index];
      if (!cd.isLob) {
        varOffset = processColumn(rowBytes, columns[index] - 1,
            extractColumnBytes, columnBytes, varOffset, targetFormat, index);
      }
    }
    return columnBytes;
  }

  /**
   * Serialize columns as a byte[] using the given target RowFormatter. The
   * columns in target are assumed to be in sequence from 1 to size of "columns"
   * array i.e. for every <i>i</i> in 1, 2, 3, ... columns.length, column
   * position <i>columns[i-1]</i> in this RowFormatter maps to column position
   * <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target formatter.
   */
  public final byte[] generateColumns(
      @Unretained final OffHeapByteSource rowBytes, final int[] columns,
      final RowFormatter targetFormat) {
    int sz = targetFormat.nVersionBytes;
    int posIndex;
    ColumnDescriptor cd;
    final int numCols = columns.length;
    final boolean truncateSpaces = targetFormat.isPrimaryKeyFormatter();

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowBytes.getLength();
    final long memAddr = rowBytes.getUnsafeAddress(0, bytesLen);

    for (int index = 0; index < numCols; index++) {
      posIndex = columns[index] - 1;
      cd = this.columns[posIndex];
      if (!cd.isLob) {
        sz += getColumnWidth(posIndex, unsafe, memAddr, bytesLen, cd,
            truncateSpaces);

        // account for data length to be written
        // fixed length source column (child table) can translate to variable
        // length target (parent table).
        // or it might be variable length column for both (parent & child)
        // tables,
        // in this case parent table
        // offset is the number of bytes that is to be written.
        if (targetFormat.positionMap[index] < 0) {
          sz += targetFormat.getNumOffsetBytes();
        }
      }
    }

    final byte[] columnBytes = new byte[sz];
    // write the version, if any, first
    targetFormat.writeVersion(columnBytes);
    int varOffset = targetFormat.varDataOffset;
    for (int index = 0; index < numCols; index++) {
      cd = targetFormat.columns[index];
      if (!cd.isLob) {
        varOffset = processColumn(unsafe, memAddr, bytesLen,
            columns[index] - 1, extractColumnBytesOffHeap, columnBytes,
            varOffset, targetFormat, index);
      }
    }
    return columnBytes;
  }

  /**
   * Serialize columns directly onto the DataOutput using the given target
   * RowFormatter. The columns in target are assumed to be in sequence from 1 to
   * size of "columns" array i.e. for every <i>i</i> in 1, 2, 3, ...
   * columns.length, column position <i>columns[i-1]</i> in this RowFormatter
   * maps to column position <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target RowFormatter else undefined behaviour will result.
   */
  public final void serializeColumns(final byte[] rowBytes,
      final DataOutput out, final int[] fixedColumns, final int[] varColumns,
      final int targetFormatOffsetBytes, final int targetFormatOffsetIsDefault,
      final RowFormatter targetFormat) throws IOException {
    int posIndex, offset, width;
    long[] offsetAndWidths = null;
    long offsetAndWidth;
    int sz = targetFormat.nVersionBytes;
    int numVarWidths = 0;
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        sz += this.columns[pos - 1].fixedWidth;
      }
    }
    if (varColumns != null) {
      final boolean truncateSpaces = targetFormat.isPrimaryKeyFormatter();
      numVarWidths = varColumns.length;
      // store the offset+widths to avoid calculating it multiple times
      offsetAndWidths = new long[numVarWidths];
      for (int index = 0; index < numVarWidths; index++) {
        final int varPosition = varColumns[index];
        offsetAndWidth = getOffsetAndWidth(varPosition, rowBytes,
            truncateSpaces);
        if (offsetAndWidth >= 0) {
          sz += (int)offsetAndWidth;
        } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
          final byte[] defaultBytes = this.columns[varPosition
              - 1].columnDefaultBytes;
          if (defaultBytes != null) {
            sz += defaultBytes.length;
          }
        }
        sz += targetFormatOffsetBytes;
        offsetAndWidths[index] = offsetAndWidth;
      }
    }

    InternalDataSerializer.writeArrayLength(sz, out);
    // first write the version
    targetFormat.writeVersion(out);
    int varOffset = targetFormat.nVersionBytes;
    // next write the fixed width columns
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        posIndex = pos - 1;
        offset = this.positionMap[posIndex];
        width = this.columns[posIndex].fixedWidth;
        out.write(rowBytes, offset, width);
        varOffset += width;
      }
    }
    if (varColumns != null) {
      // now write the variable width columns
      for (int index = 0; index < numVarWidths; ++index) {
        if ((offsetAndWidth = offsetAndWidths[index]) >= 0) {
          width = (int)offsetAndWidth;
          offset = (int)(offsetAndWidth >>> Integer.SIZE);
          out.write(rowBytes, offset, width);
          // change offsetAndWidth to offset to be written at the end
          offsetAndWidths[index] = targetFormat.getVarDataOffset(varOffset);
          varOffset += width;
        } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
          offsetAndWidths[index] = targetFormat.getNullIndicator(varOffset);
        } else {
          assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
          offsetAndWidths[index] = targetFormatOffsetIsDefault;
        }
      }
      // finally write the variable width column offsets
      for (int index = 0; index < numVarWidths; ++index) {
        writeInt(out, (int)offsetAndWidths[index], targetFormatOffsetBytes);
      }
    }
  }

  /**
   * Serialize columns directly onto the DataOutput using the given target
   * RowFormatter. The columns in target are assumed to be in sequence from 1 to
   * size of "columns" array i.e. for every <i>i</i> in 1, 2, 3, ...
   * columns.length, column position <i>columns[i-1]</i> in this RowFormatter
   * maps to column position <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target RowFormatter else undefined behaviour will result.
   */
  public final void serializeColumns(
      @Unretained final OffHeapByteSource rowBytes, final DataOutput out,
      final int[] fixedColumns, final int[] varColumns,
      final int targetFormatOffsetBytes, final int targetFormatOffsetIsDefault,
      final RowFormatter targetFormat) throws IOException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = rowBytes.getLength();
    final long memAddr = rowBytes.getUnsafeAddress(0, bytesLen);

    int posIndex, offset, width;
    long[] offsetAndWidths = null;
    long offsetAndWidth;
    int sz = targetFormat.nVersionBytes;
    int numVarWidths = 0;
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        sz += this.columns[pos - 1].fixedWidth;
      }
    }
    if (varColumns != null) {
      final boolean truncateSpaces = targetFormat.isPrimaryKeyFormatter();
      numVarWidths = varColumns.length;
      // store the offset+widths to avoid calculating it multiple times
      offsetAndWidths = new long[numVarWidths];
      for (int index = 0; index < numVarWidths; index++) {
        offsetAndWidth = getOffsetAndWidth(varColumns[index], unsafe, memAddr,
            bytesLen, truncateSpaces);
        if (offsetAndWidth >= 0) {
          sz += (int)offsetAndWidth;
        }
        sz += targetFormatOffsetBytes;
        offsetAndWidths[index] = offsetAndWidth;
      }
    }

    InternalDataSerializer.writeArrayLength(sz, out);
    // first write the version
    targetFormat.writeVersion(out);
    int varOffset = targetFormat.nVersionBytes;
    // next write the fixed width columns
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        posIndex = pos - 1;
        offset = this.positionMap[posIndex];
        width = this.columns[posIndex].fixedWidth;
        OffHeapRegionEntryHelper.copyBytesToDataOutput(unsafe,
            memAddr + offset, width, out);
        varOffset += width;
      }
    }
    if (varColumns != null) {
      // now write the variable width columns
      for (int index = 0; index < numVarWidths; ++index) {
        if ((offsetAndWidth = offsetAndWidths[index]) >= 0) {
          width = (int)offsetAndWidth;
          offset = (int)(offsetAndWidth >>> Integer.SIZE);
          OffHeapRegionEntryHelper.copyBytesToDataOutput(unsafe,
              memAddr + offset, width, out);
          // change offsetAndWidth to offset to be written at the end
          offsetAndWidths[index] = targetFormat.getVarDataOffset(varOffset);
          varOffset += width;
        } else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
          offsetAndWidths[index] = targetFormat.getNullIndicator(varOffset);
        } else {
          assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
          // write actual default bytes here instead of default indicator
          final byte[] defaultBytes = this.columns[varColumns[index]
              - 1].columnDefaultBytes;
          if (defaultBytes == null) {
            offsetAndWidths[index] = targetFormat.getNullIndicator(varOffset);
          } else {
            out.write(defaultBytes, 0, defaultBytes.length);
            offsetAndWidths[index] = targetFormat.getVarDataOffset(varOffset);
          }
          offsetAndWidths[index] = targetFormatOffsetIsDefault;
        }
      }
      // finally write the variable width column offsets
      for (int index = 0; index < numVarWidths; ++index) {
        writeInt(out, (int)offsetAndWidths[index], targetFormatOffsetBytes);
      }
    }
  }

  /**
   * Serialize columns directly onto the DataOutput using the given target
   * RowFormatter. The columns in target are assumed to be in sequence from 1 to
   * size of "columns" array i.e. for every <i>i</i> in 1, 2, 3, ...
   * columns.length, column position <i>columns[i-1]</i> in this RowFormatter
   * maps to column position <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target RowFormatter else undefined behaviour will result.
   */
  public final int getColumnsWidth(final byte[] rowBytes,
      final int[] fixedColumns, final int[] varColumns,
      final RowFormatter targetFormat) {
    assert fixedColumns != null || varColumns != null;

    int sz = targetFormat.nVersionBytes;
    int posIndex;
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        sz += this.columns[pos - 1].fixedWidth;
      }
    }
    if (varColumns != null) {
      final int targetFormatOffsetBytes = targetFormat.getNumOffsetBytes();
      for (int pos : varColumns) {
        posIndex = pos - 1;
        sz += getColumnWidth(posIndex, rowBytes, this.columns[posIndex]);
        sz += targetFormatOffsetBytes;
      }
    }
    return sz;
  }

  /**
   * Serialize columns directly onto the DataOutput using the given target
   * RowFormatter. The columns in target are assumed to be in sequence from 1 to
   * size of "columns" array i.e. for every <i>i</i> in 1, 2, 3, ...
   * columns.length, column position <i>columns[i-1]</i> in this RowFormatter
   * maps to column position <i>i</i> in the target RowFormatter.
   * <p>
   * It is expected that columns types and widths are equal in this RowFormatter
   * and target RowFormatter else undefined behaviour will result.
   */
  public final int getColumnsWidth(
      @Unretained final OffHeapByteSource rowBytes, final int[] fixedColumns,
      final int[] varColumns, final RowFormatter targetFormat) {
    assert fixedColumns != null || varColumns != null;

    int sz = targetFormat.nVersionBytes;
    int posIndex;
    if (fixedColumns != null) {
      for (int pos : fixedColumns) {
        sz += this.columns[pos - 1].fixedWidth;
      }
    }
    if (varColumns != null) {
      final int targetFormatOffsetBytes = targetFormat.getNumOffsetBytes();

      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = rowBytes.getLength();
      final long memAddr = rowBytes.getUnsafeAddress(0, bytesLen);

      for (int pos : varColumns) {
        posIndex = pos - 1;
        sz += getColumnWidth(posIndex, unsafe, memAddr, bytesLen,
            this.columns[posIndex]);
        sz += targetFormatOffsetBytes;
      }
    }
    return sz;
  }

  public final void writeAsUTF8BytesForPXF(final int logicalPosition,
      final byte[] bytes, final ByteArrayDataOutput buffer)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      DataTypeUtilities.writeAsUTF8BytesForPXF(bytes, offset, columnWidth,
          cd.columnType, buffer);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          buffer.writeBytes(dvd.getString());
        }
      }
    }
  }

  public final void writeAsUTF8BytesForPXF(final int logicalPosition,
      final byte[][] byteArrays, final ByteArrayDataOutput buffer)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final byte[] bytes;
    final int columnWidth;
    final int offset;
    if (cd.isLob) {
      bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      offset = 0;
      if (bytes != null) {
        columnWidth = bytes.length;
      }
      else {
        return;
      }
    }
    else {
      bytes = byteArrays[0];
      final long offsetAndWidth = getOffsetAndWidth(index, bytes,
          offsetFromMap, cd);
      if (offsetAndWidth >= 0) {
        columnWidth = (int)offsetAndWidth;
        offset = (int)(offsetAndWidth >>> Integer.SIZE);
      }
      else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
            || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
        if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
          final DataValueDescriptor dvd = cd.columnDefault;
          if (dvd != null && !dvd.isNull()) {
            buffer.writeBytes(dvd.getString());
          }
        }
        return;
      }
    }
    DataTypeUtilities.writeAsUTF8BytesForPXF(bytes, offset, columnWidth,
        cd.columnType, buffer);
  }

  public final void writeAsUTF8BytesForPXF(final int index,
      final ColumnDescriptor cd, final UnsafeWrapper unsafe, long memAddr,
      final int bytesLen, @Unretained final OffHeapByteSource bs,
      final ByteArrayDataOutput buffer) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      DataTypeUtilities.writeAsUTF8BytesForPXF(unsafe, memAddr + offset,
          columnWidth, bs, cd.columnType, buffer);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          buffer.writeBytes(dvd.getString());
        }
      }
    }
  }

  public final void writeAsUTF8BytesForPXF(final int index,
      final ColumnDescriptor cd, final UnsafeWrapper unsafe,
      final long firstBSAddr, final int firstBytesLen,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ByteArrayDataOutput buffer) throws StandardException {
    if (!cd.isLob) {
      writeAsUTF8BytesForPXF(index, cd, unsafe, firstBSAddr, firstBytesLen,
          (OffHeapByteSource)byteArrays, buffer);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          DataTypeUtilities.writeAsUTF8BytesForPXF(bytes, 0, bytes.length,
              cd.columnType, buffer);
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final int bytesLen = bs.getLength();
          final long memAddr = bs.getUnsafeAddress(0, bytesLen);
          DataTypeUtilities.writeAsUTF8BytesForPXF(unsafe, memAddr, bytesLen,
              bs, cd.columnType, buffer);
        }
      }
    }
  }

  /**
   * Get the DataValueDescriptors for all columns in the table
   *
   * @return DataValueDescriptor[] for all the columns
   */
  final DataValueDescriptor[] getAllColumns(final byte[] bytes)
      throws StandardException {
    final int numColumns = getNumColumns();
    final DataValueDescriptor[] dvds = new DataValueDescriptor[numColumns];
    for (int index = 0; index < numColumns; ++index) {
      dvds[index] = getColumn(index + 1, bytes);
    }
    return dvds;
  }

  /**
   * Get the DataValueDescriptors for all columns in the table
   *
   * @return DataValueDescriptor[] for all the columns
   */
  final DataValueDescriptor[] getAllColumns(@Unretained final OffHeapRow bytes)
      throws StandardException {
    final int numColumns = getNumColumns();
    final DataValueDescriptor[] dvds = new DataValueDescriptor[numColumns];

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    for (int index = 0; index < numColumns; ++index) {
      dvds[index] = getColumn(index + 1, unsafe, memAddr, bytesLen, bytes);
    }
    return dvds;
  }

  /**
   * Get the DataValueDescriptors for all columns in the table
   *
   * @return DataValueDescriptor[] for all the columns
   */
  final DataValueDescriptor[] getAllColumns(
      @Unretained final OffHeapRowWithLobs byteArrays) throws StandardException {
    final int numColumns = getNumColumns();
    final DataValueDescriptor[] dvds = new DataValueDescriptor[numColumns];

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen0th = byteArrays.getLength();
    final long memAddr0th = byteArrays.getUnsafeAddress(0, bytesLen0th);

    for (int index = 0; index < numColumns; ++index) {
      dvds[index] = getColumn(index + 1, unsafe, memAddr0th, bytesLen0th,
          byteArrays);
    }
    return dvds;
  }

  /**
   * Get the DataValueDescriptors for all columns in the table
   *
   * @return DataValueDescriptor[] for all the columns
   */
  final DataValueDescriptor[] getAllColumns(final byte[][] byteArrays)
      throws StandardException {
    final int numColumns = getNumColumns();
    final DataValueDescriptor[] dvds = new DataValueDescriptor[numColumns];
    for (int index = 0; index < numColumns; ++index) {
      dvds[index] = getColumn(index + 1, byteArrays);
    }
    return dvds;
  }

  /** number of columns in this {@link RowFormatter} */
  public final int getNumColumns() {
    return this.positionMap.length;
  }

  /**
   * Return number of bytes representing an offset.
   */
  public final int getNumOffsetBytes() {
    return this.offsetBytes;
  }

  public final int getOffsetDefaultToken() {
    return this.offsetIsDefault;
  }

  /**
   * get the actual variable data offset to be written to row given the position
   * in the byte array from start
   */
  private int getVarDataOffset(final int varDataOffset) {
    return this.schemaVersion != TOKEN_RECOVERY_VERSION ? varDataOffset
        : (varDataOffset > 0 ? (varDataOffset - TOKEN_RECOVERY_VERSION_BYTES)
            : (varDataOffset + TOKEN_RECOVERY_VERSION_BYTES));
  }

  /** get the value to be written as the offset for a column with null value */
  public final int getNullIndicator(final int varOffset) {
    return this.schemaVersion != TOKEN_RECOVERY_VERSION ? -(varOffset + 1)
        : -(varOffset - TOKEN_RECOVERY_VERSION_BYTES + 1);
  }

  /**
   * read the actual offset from bytes given the position of the offset in the
   * byte[] (viz. "offsetOffset")
   */
  public final int readVarDataOffset(final UnsafeWrapper unsafe,
      final long memAddr, int offsetOffset) {

    final int offset = readInt(unsafe, memAddr + offsetOffset, getNumOffsetBytes());
    // for the special TOKEN_RECOVERY_VERSION where byte[] does not contain an
    // actual schema version, adjust the offset value by the size of
    // TOKEN_RECOVERY_VERSION written at the start which is not accounted in the
    // offset value when written
    return this.schemaVersion != TOKEN_RECOVERY_VERSION ? offset
        : (offset >= 0 ? (offset + TOKEN_RECOVERY_VERSION_BYTES)
            : (offset - TOKEN_RECOVERY_VERSION_BYTES));
  }

  /**
   * read the actual offset from bytes given the position of the offset in the
   * byte[] (viz. "offsetOffset")
   */
  public final int readVarDataOffset(final byte[] bytes, int offsetOffset) {

    final int offset = readInt(bytes, offsetOffset, getNumOffsetBytes());
    // for the special TOKEN_RECOVERY_VERSION where byte[] does not contain an
    // actual schema version, adjust the offset value by the size of
    // TOKEN_RECOVERY_VERSION written at the start which is not accounted in the
    // offset value when written
    return this.schemaVersion != TOKEN_RECOVERY_VERSION ? offset
        : (offset >= 0 ? (offset + TOKEN_RECOVERY_VERSION_BYTES)
            : (offset - TOKEN_RECOVERY_VERSION_BYTES));
  }

  /**
   * Indicates whether trailing spaces should be removed.
   *
   * @param cd
   *          ColumnDescriptor of a table.
   * @return true if trailing spaces is to be truncated.
   */
  public static boolean shouldTrimTrailingSpaces(
      final ColumnDescriptor cd) {
    final int typeId = cd.getType().getTypeId().getTypeFormatId();
    return ((typeId == StoredFormatIds.VARCHAR_TYPE_ID)
        // TODO || (typeId == StoredFormatIds.CHAR_TYPE_ID)
        || (typeId == StoredFormatIds.BIT_TYPE_ID));
  }

  /**
   * Returns true if the result of {@link #readVarDataOffset(byte[], int)}
   * represent a token indicating that value of the column is DEFAULT
   */
  public final boolean isVarDataOffsetDefaultToken(final int offset) {
    // for the special TOKEN_RECOVERY_VERSION case from an older row not having
    // schema version, the offset will need to be reduced by
    // TOKEN_RECOVERY_VERSION_BYTES since the incoming offset is actual offset
    // in the array
    return this.schemaVersion != TOKEN_RECOVERY_VERSION
        ? (offset == getOffsetDefaultToken())
        : ((offset - TOKEN_RECOVERY_VERSION_BYTES) == getOffsetDefaultToken());
  }

  /////////////////// PRIVATE METHODS ///////////////////

  /**
   * Set a field in a byte array from a dvd source for a non-LOB column.
   *
   * @param varDataOffset
   *          the offset to the data to be used for a var width field
   * @return int the number of bytes written to a var data field, or 0 if was
   *         fixed width
   */
  private int generateColumn(final int index, final DataValueDescriptor dvd,
      final ColumnDescriptor cd, final byte[] bytes, final int varDataOffset)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    assert !cd.isLob : "unexpected LOB column to generateColumn: " + cd;
    if (offsetFromMap < 0) {
      // var width, offsetFromMap is the negative offset of the offset
      final int offsetOffset = bytes.length + offsetFromMap;

      // check for default value and null
      final DataValueDescriptor defaultValue = cd.columnDefault;
      if (dvd != null && !dvd.isNull()) {
        if (defaultValue == null || !defaultValue.equals(dvd)) {
          // actually has real data, so write offset
          // writeInt(bytes, varDataOffset, offsetOffset);
          writeInt(bytes, getVarDataOffset(varDataOffset), offsetOffset,
              getNumOffsetBytes());

          // now write the data bytes
          final int numBytesWritten = dvd.writeBytes(bytes, varDataOffset,
              cd.columnType);
          assert numBytesWritten == dvd.getLengthInBytes(cd.columnType);
          return numBytesWritten;
        }
        // write an indicator as offset for value being default
        writeInt(bytes, getOffsetDefaultToken(), offsetOffset,
            getNumOffsetBytes());
        return 0;
      } else { // null value
        // null value; write negative offset+1 of next var column
        writeInt(bytes, getNullIndicator(varDataOffset), offsetOffset,
            getNumOffsetBytes());
        return 0;
      }
    }

    // offsetFromMap can never be less than versionBytes for generateColumn
    // i.e. it should always be invoked for the latest schema
    assert offsetFromMap >= this.nVersionBytes : "unexpected offsetFromMap="
        + offsetFromMap + " for schemaVersion=" + this.schemaVersion;

    // fixed field, offsetFromMap is the positive offset of the fixed width
    // data; fixed width field should not be null
    // dvd can temporarily be null here for newly created row -- it is
    // expected to be overwritten later
    if (dvd != null && !dvd.isNull()) {
      final int numBytesWritten = dvd.writeBytes(bytes, offsetFromMap,
          cd.columnType);
      assert numBytesWritten == dvd.getLengthInBytes(cd.columnType);
    }
    return 0; // contract is to return 0 if fixed width
  }

  /**
   * Return a byte array from a dvd source of a LOB column.
   */
  private byte[] generateLobColumn(final DataValueDescriptor dvd,
      final ColumnDescriptor cd) throws StandardException {
    assert cd.isLob : "unexpected non-LOB column to generateLobColumn: " + cd;
    final DataTypeDescriptor type = cd.columnType;
    final int jdbcType = type.getJDBCTypeId();
    switch (jdbcType) {
    case Types.BLOB:
    case Types.JAVA_OBJECT:
      if (dvd != null && !dvd.isNull()) {
        return dvd.getBytes();
      }
      // a null BLOB/JAVA_OBJECT returns null for getBytes()
      return null;
    default:
      // this can be a CLOB, SQLXML or JSON
      assert jdbcType == Types.CLOB || jdbcType == Types.SQLXML
          || jdbcType == JDBC40Translation.JSON :
          "unexpected LOB type to generateLobColumn: " + cd;
      if (dvd != null && !dvd.isNull()) {
        final byte[] lobBytes = new byte[dvd.getLengthInBytes(type)];
        final int numBytesWritten = dvd.writeBytes(lobBytes, 0, type);
        assert numBytesWritten == lobBytes.length;
        return lobBytes;
      }
      return null;
    }
  }

  /**
   * Copy bytes for a column from one row byte array to another.
   *
   * @param srcIndex
   *          the 0-based index of column to be copied
   * @param srcBytes
   *          the source byte array to copy from
   * @param srcFormat
   *          the formatter for srcBytes
   * @param outBytes
   *          the output byte array to copy to
   * @param outIndex
   *          the 0-based index of the column in the output
   * @param varDataOffset
   *          the offset to the data to be used for a var width field
   *
   * @return int the number of bytes written to a var data field, or 0 if it was
   *         fixed width
   */
  private int copyColumn(final int srcIndex, final byte[] srcBytes,
      final RowFormatter srcFormat, final byte[] outBytes, final int outIndex,
      final int varDataOffset) {
    final int offsetFromMap = this.positionMap[outIndex];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final ColumnDescriptor cd = this.columns[outIndex];
      final int srcOffsetFromMap = srcFormat.positionMap[srcIndex];
      final int columnWidth = cd.fixedWidth;

      if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        assert columnWidth > 0 : "unexpected fixed width " + columnWidth
            + " for column: " + cd;

        System.arraycopy(srcBytes, srcOffsetFromMap, outBytes, offsetFromMap,
            columnWidth);
        return 0; // contract requires returning 0 for fixed width
      }
      else {
        // need to copy variable width to fixed width; assuming column type does
        // not change
        final ColumnDescriptor srcCD = srcFormat.columns[srcIndex];
        assert cd.getType().equals(srcCD.getType()) :
            "unexpected change in column type from [" + srcCD + "] to [" + cd
            + "] from schemaVersion=" + srcFormat.schemaVersion
            + " to schemaVersion=" + schemaVersion;
        if (srcOffsetFromMap < 0) {
          final int srcOffsetOffset = srcBytes.length + srcOffsetFromMap;
          final int srcOffset = srcFormat.readVarDataOffset(srcBytes,
              srcOffsetOffset);
          if (srcOffset >= srcFormat.nVersionBytes) {
            System.arraycopy(srcBytes, srcOffset, outBytes, offsetFromMap,
                columnWidth);
            return 0; // contract requires returning 0 for fixed width
          }
        }
        // in this case the column has to default value since a null value
        // cannot be fixed width and ALTER itself should have complained about a
        // column with NULL being made non-null
        assert srcCD.columnDefaultBytes != null;
        System.arraycopy(srcCD.columnDefaultBytes, 0, outBytes, offsetFromMap,
            columnWidth);
        return 0; // contract requires returning 0 for fixed width
      }
    } else if (offsetFromMap < 0) {
      // var width, offsetFromMap is the negative offset of the offset
      final int srcOffsetFromMap = srcFormat.positionMap[srcIndex];
      ColumnDescriptor srcCD = null;
      final int outOffsetOffset = outBytes.length + offsetFromMap;
      final int srcOffset, columnWidth;

      if (srcOffsetFromMap < 0) {
        final int srcBytesLength = srcBytes.length;
        final int srcOffsetOffset = srcBytesLength + srcOffsetFromMap;
        srcOffset = srcFormat.readVarDataOffset(srcBytes, srcOffsetOffset);
        if (srcOffset >= srcFormat.nVersionBytes) {
          columnWidth = srcFormat.getVarColumnWidth(srcBytes, srcBytesLength,
              srcOffsetOffset, srcOffset);
        }
        else {
          columnWidth = 0;
        }
      } else if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        srcOffset = srcOffsetFromMap;
        srcCD = srcFormat.columns[srcIndex];
        columnWidth = srcCD.fixedWidth;
      } else if ((srcCD = srcFormat.columns[srcIndex]).columnDefault != null) {
        srcOffset = srcFormat.getOffsetDefaultToken();
        columnWidth = 0;
      } else {
        // value is null; put some indicator value here
        srcOffset = -1;
        columnWidth = 0;
      }
      if (srcOffset >= srcFormat.nVersionBytes) {
        // write offset
        writeInt(outBytes, getVarDataOffset(varDataOffset), outOffsetOffset,
            getNumOffsetBytes());
        // write data
        System.arraycopy(srcBytes, srcOffset, outBytes, varDataOffset,
            columnWidth);
        return columnWidth;
      } else if (srcOffset == srcFormat.getOffsetDefaultToken() && srcCD != null) {
        // target "this" formatter may not have default value after ALTER TABLE
        // (e.g. on the fly created RowFormatters for projection -- see #47054)
        final ColumnDescriptor cd = this.columns[outIndex];
        if (cd.columnDefault == srcCD.columnDefault) {
          writeInt(outBytes, getOffsetDefaultToken(), outOffsetOffset,
              getNumOffsetBytes());
        } else {
          // copy the default bytes from srcFormat
          final byte[] defaultBytes = srcCD.columnDefaultBytes;
          // write offset
          writeInt(outBytes, getVarDataOffset(varDataOffset), outOffsetOffset,
              getNumOffsetBytes());
          // write default bytes
          System.arraycopy(defaultBytes, 0, outBytes, varDataOffset,
              defaultBytes.length);
          return defaultBytes.length;
        }
      } else {
        // write -ve offset+1
        writeInt(outBytes, getNullIndicator(varDataOffset), outOffsetOffset,
            getNumOffsetBytes());
      }
      return 0;
    } else {
      // default value after addColumn
      return 0;
    }
  }

  /**
   * Copy bytes for a column from one row byte array to another.
   *
   * @param srcIndex
   *          the 0-based index of column to be copied
   * @param memAddr
   *          the start off-heap address of source byte array to copy from
   * @param srcFormat
   *          the formatter for srcBytes
   * @param outBytes
   *          the output byte array to copy to
   * @param outIndex
   *          the 0-based index of the column in the output
   * @param varDataOffset
   *          the offset to the data to be used for a var width field
   *
   * @return int the number of bytes written to a var data field, or 0 if it was
   *         fixed width
   */
  private int copyColumn(final int srcIndex, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final RowFormatter srcFormat,
      final byte[] outBytes, final int outIndex, final int varDataOffset) {
    final int offsetFromMap = this.positionMap[outIndex];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final int srcOffsetFromMap = srcFormat.positionMap[srcIndex];
      final ColumnDescriptor cd = this.columns[outIndex];
      final int columnWidth = cd.fixedWidth;

      if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        assert columnWidth > 0 : "unexpected fixed width " + columnWidth
            + " for column: " + cd;

        UnsafeMemoryChunk.readUnsafeBytes(memAddr, srcOffsetFromMap,
            outBytes, offsetFromMap, columnWidth);
        return 0; // contract requires returning 0 for fixed width
      }
      else {
        // need to copy variable width to fixed width; assuming column type does
        // not change
        final ColumnDescriptor srcCD = srcFormat.columns[srcIndex];
        assert cd.getType().equals(srcCD.getType()) :
            "unexpected change in column type from [" + srcCD + "] to [" + cd
            + "] from schemaVersion=" + srcFormat.schemaVersion
            + " to schemaVersion=" + schemaVersion;
        if (srcOffsetFromMap < 0) {
          final int srcOffsetOffset = bytesLen + srcOffsetFromMap;
          final int srcOffset = srcFormat.readVarDataOffset(unsafe, memAddr,
              srcOffsetOffset);
          if (srcOffset >= srcFormat.nVersionBytes) {
            UnsafeMemoryChunk.readUnsafeBytes(memAddr, srcOffset,
                outBytes, offsetFromMap, columnWidth);
            return 0; // contract requires returning 0 for fixed width
          }
        }
        // in this case the column has to default value since a null value
        // cannot be fixed width and ALTER itself should have complained about a
        // column with NULL being made non-null
        assert srcCD.columnDefaultBytes != null;
        System.arraycopy(srcCD.columnDefaultBytes, 0, outBytes, offsetFromMap,
            columnWidth);
        return 0; // contract requires returning 0 for fixed width
      }
    } else if (offsetFromMap < 0) {
      // var width, offsetFromMap is the negative offset of the offset
      final int srcOffsetFromMap = srcFormat.positionMap[srcIndex];
      ColumnDescriptor srcCD = null;
      final int outOffsetOffset = outBytes.length + offsetFromMap;
      final int srcOffset, columnWidth;

      if (srcOffsetFromMap < 0) {
        final int srcOffsetOffset = bytesLen + srcOffsetFromMap;
        srcOffset = srcFormat.readVarDataOffset(unsafe, memAddr,
            srcOffsetOffset);
        if (srcOffset >= srcFormat.nVersionBytes) {
          columnWidth = srcFormat.getVarColumnWidth(unsafe, memAddr, bytesLen,
              srcOffsetOffset, srcOffset);
        } else {
          columnWidth = 0;
        }
      } else if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        srcOffset = srcOffsetFromMap;
        srcCD = srcFormat.columns[srcIndex];
        columnWidth = srcCD.fixedWidth;
      } else if ((srcCD = srcFormat.columns[srcIndex]).columnDefault != null) {
        srcOffset = srcFormat.getOffsetDefaultToken();
        columnWidth = 0;
      } else {
        // value is null; put some indicator value here
        srcOffset = -1;
        columnWidth = 0;
      }
      if (srcOffset >= srcFormat.nVersionBytes) {
        // write offset
        writeInt(outBytes, getVarDataOffset(varDataOffset), outOffsetOffset,
            getNumOffsetBytes());
        // write data
        UnsafeMemoryChunk.readUnsafeBytes(memAddr, srcOffset, outBytes,
            varDataOffset, columnWidth);
        return columnWidth;
      } else if (srcOffset == srcFormat.getOffsetDefaultToken() && srcCD != null) {
        // target "this" formatter may not have default value after ALTER TABLE
        // (e.g. on the fly created RowFormatters for projection -- see #47054)
        final ColumnDescriptor cd = this.columns[outIndex];
        if (cd.columnDefault == srcCD.columnDefault ) {
          writeInt(outBytes, getOffsetDefaultToken(), outOffsetOffset,
              getNumOffsetBytes());
        } else {
          // copy the default bytes from srcFormat
          final byte[] defaultBytes = srcCD.columnDefaultBytes;
          // write offset
          writeInt(outBytes, getVarDataOffset(varDataOffset), outOffsetOffset,
              getNumOffsetBytes());
          // write default bytes
          System.arraycopy(defaultBytes, 0, outBytes, varDataOffset,
              defaultBytes.length);
          return defaultBytes.length;
        }
      } else {
        // write -ve offset+1
        writeInt(outBytes, getNullIndicator(varDataOffset), outOffsetOffset,
            getNumOffsetBytes());
      }
      return 0;
    } else {
      // default value after addColumn
      return 0;
    }
  }

  /**
   * Return the total length of a row to store the specified data. The length
   * does not include the LOBs.
   *
   * @param dvds
   *          the DataValueDescriptor array for the columns in the row
   *
   * @return row length in bytes
   */
  private int computeRowLength(final DataValueDescriptor[] dvds)
      throws StandardException {
    assert dvds.length == this.positionMap.length : "dvds.length="
        + dvds.length + ";RowFormatter.size=" + this.positionMap.length;

    // add the version bytes, if any
    int dataLength = this.nVersionBytes;
    int columnWidth;
    for (int index = 0; index < dvds.length; ++index) {
      final ColumnDescriptor cd = this.columns[index];
      if ((columnWidth = cd.fixedWidth) == -1) {
        dataLength += getVarColumnWidth(dvds[index], cd);
        // add space for an offset
        dataLength += this.offsetBytes;
      } else {
        dataLength += columnWidth;
      }
    }
    return dataLength;
  }

  /**
   * Return the total length of a row to store the specified data. The length
   * does not include the LOBs.
   *
   * @param dvd
   *          the DataValueDescriptor for a single column row
   *
   * @return row length in bytes
   */
  private int computeRowLength(final DataValueDescriptor dvd)
      throws StandardException {
    assert 1 == this.positionMap.length : "dvds.length=1"
        + ";RowFormatter.size=" + this.positionMap.length;

    // add the version bytes, if any
    int dataLength = this.nVersionBytes;
    int columnWidth;
    final ColumnDescriptor cd = this.columns[0];
    if ((columnWidth = cd.fixedWidth) == -1) {
      dataLength += getVarColumnWidth(dvd, cd);
      // add space for an offset
      dataLength += this.offsetBytes;
    } else {
      dataLength += columnWidth;
    }
    return dataLength;
  }

  /**
   * Return the number of var width columns in the row, and the offset
   * information.
   *
   * @param cdl
   *          ColumnDescriptorList for the row
   */
  private int[] initNumVarWidthCols(final ColumnDescriptorList cdl,
      final int numCols) {
    final int[] result = new int[NUM_POS];
    result[VAR_DATA_OFFSET_POS] = this.nVersionBytes;
    long maxDataLength = this.nVersionBytes;
    boolean hasUnknown = false;
    int width;
    for (int index = 0; index < numCols; index++) {
      final ColumnDescriptor cd = cdl.elementAt(index);
      width = calcMaxColumnWidth(cd, result);
      if (width >= 0) {
        maxDataLength += width;
      } else {
        hasUnknown = true;
      }
    }
    if (hasUnknown || maxDataLength > Integer.MAX_VALUE) {
      maxDataLength = Integer.MAX_VALUE;
    }
    //                                                  todo change is ok?
    calcNumOffsetBytesToUse((int)maxDataLength, result[NUM_VAR_WIDTH_COLS_POS],
        result);
    return result;
  }

  /**
   * Return the number of var width columns in the row, and the offset
   * information.
   *
   * @param cdl
   *          ColumnDescriptors for the row
   */
  private int[] initNumVarWidthCols(final ColumnDescriptor[] cdl,
      final int numCols) {
    final int[] result = new int[NUM_POS];
    result[VAR_DATA_OFFSET_POS] = this.nVersionBytes;
    long maxDataLength = this.nVersionBytes;
    boolean hasUnknown = false;
    int width;
    for (int index = 0; index < numCols; index++) {
      final ColumnDescriptor cd = cdl[index];
      width = calcMaxColumnWidth(cd, result);
      if (width >= 0) {
        maxDataLength += width;
      } else {
        hasUnknown = true;
      }
    }
    if (hasUnknown || maxDataLength > Integer.MAX_VALUE) {
      maxDataLength = Integer.MAX_VALUE;
    }
    calcNumOffsetBytesToUse((int)maxDataLength, result[NUM_VAR_WIDTH_COLS_POS],
        result);
    return result;
  }

  /**
   * Return the number of var width columns in the row, and the offset
   * information.
   *
   * @param cdl
   *          ColumnDescriptorList for the row
   */
  private int[] initNumVarWidthCols(final ColumnDescriptorList cdl,
      final FormatableBitSet validColumns) {
    final int[] result = new int[NUM_POS];
    result[VAR_DATA_OFFSET_POS] = this.nVersionBytes;
    long maxDataLength = this.nVersionBytes;
    boolean hasUnknown = false;
    int width;
    for (int colIndex = validColumns.anySetBit(); colIndex != -1;
        colIndex = validColumns.anySetBit(colIndex)) {
      final ColumnDescriptor cd = cdl.elementAt(colIndex);
      width = calcMaxColumnWidth(cd, result);
      if (width >= 0) {
        maxDataLength += width;
      } else {
        hasUnknown = true;
      }
    }
    if (hasUnknown || maxDataLength > Integer.MAX_VALUE) {
      maxDataLength = Integer.MAX_VALUE;
    }
    calcNumOffsetBytesToUse((int)maxDataLength, result[VAR_DATA_OFFSET_POS],
        result);
    return result;
  }

  /**
   * Return the number of var width columns in the row, and the offset
   * information.
   *
   * @param cdl
   *          ColumnDescriptorList for the row
   */
  private int[] initNumVarWidthCols(final ColumnDescriptorList cdl,
      final int[] validColumns) {
    final int[] result = new int[NUM_POS];
    result[VAR_DATA_OFFSET_POS] = this.nVersionBytes;
    long maxDataLength = this.nVersionBytes;
    boolean hasUnknown = false;
    int width;
    for (int pos : validColumns) {
      final ColumnDescriptor cd = cdl.elementAt(pos - 1);
      width = calcMaxColumnWidth(cd, result);
      if (width >= 0) {
        maxDataLength += width;
      } else {
        hasUnknown = true;
      }
    }
    if (hasUnknown || maxDataLength > Integer.MAX_VALUE) {
      maxDataLength = Integer.MAX_VALUE;
    }
    calcNumOffsetBytesToUse((int)maxDataLength, result[VAR_DATA_OFFSET_POS],
        result);
    return result;
  }

  /**
   * Return the number of var width columns in the row, and the offset
   * information.
   */
  private int[] initNumVarWidthCols(final ColumnQueryInfo[] cqi) {
    final int[] result = new int[NUM_POS];
    result[VAR_DATA_OFFSET_POS] = this.nVersionBytes;
    long maxDataLength = this.nVersionBytes;
    boolean hasUnknown = false;
    int width;
    for (int index = 0; index < cqi.length; ++index) {
      final ColumnDescriptor cd = cqi[index].getColumnDescriptor();
      width = calcMaxColumnWidth(cd, result);
      if (width >= 0) {
        maxDataLength += width;
      } else {
        hasUnknown = true;
      }
    }
    if (hasUnknown || maxDataLength > Integer.MAX_VALUE) {
      maxDataLength = Integer.MAX_VALUE;
    }
    calcNumOffsetBytesToUse((int)maxDataLength, result[VAR_DATA_OFFSET_POS],
        result);
    return result;
  }

  private int calcMaxColumnWidth(final ColumnDescriptor cd,
      final int[] result) {
    int width;
    if (!cd.isLob) {
      if ((width = getFixedWidthInBytes(cd.columnType, cd)) >= 0) {
        result[VAR_DATA_OFFSET_POS] += width;
        return width;
      } else {
        result[NUM_VAR_WIDTH_COLS_POS]++;
        width = getMaxWidthInBytes(cd.columnType);
        if (width < 0) {
          // for unknown width types use max possible data length
          if (width == Integer.MIN_VALUE) {
            return -1;
          }
          return -width;
        }
        return width;
      }
    }
    return 0;
  }

  /**
   * Returns the number of bytes that are used to represent the offset within
   * the byte array, the maximum variable data length that can be represented
   * and the flag that indicates that the offset value represents default column
   * value.
   *
   * @param maxDataLength
   *          max length of data in byte array
   * @param numVarLengthCols
   *          number of variable lenght columns.
   */
  private void calcNumOffsetBytesToUse(final int maxDataLength,
      final int numVarLengthCols, final int[] result) {
    final int offsetBytes;
    // for old RowFormatters without schema version (i.e. schemaVersion ==
    // TOKEN_RECOVERY_VERSION), the getNumOffsetBytes need to ignore the bytes
    // required to write schema version at the start
    if (this.schemaVersion != TOKEN_RECOVERY_VERSION) {
      offsetBytes = getOffsetBytesForLength(maxDataLength, numVarLengthCols);
    } else {
      assert this.nVersionBytes == TOKEN_RECOVERY_VERSION_BYTES:
        "unexpected versionBytes=" + this.nVersionBytes
          + " TOKEN_RECOVERY_VERSION_BYTES="
          + TOKEN_RECOVERY_VERSION_BYTES;
      offsetBytes = getOffsetBytesForLength(maxDataLength
          - TOKEN_RECOVERY_VERSION_BYTES, numVarLengthCols);
    }
    final int maxVarWidth, offsetIsDefault;
    switch (offsetBytes) {
    case 1:
      maxVarWidth = 0x7F;
      offsetIsDefault = -(maxVarWidth + 1);
      break;
    case 2:
      maxVarWidth = 0x7FFF;
      offsetIsDefault = -(maxVarWidth + 1);
      break;
    case 3:
      maxVarWidth = 0x7FFFFF;
      offsetIsDefault = -(maxVarWidth + 1);
      break;
    default:
      assert offsetBytes == 4 : offsetBytes;
      maxVarWidth = Integer.MAX_VALUE;
      offsetIsDefault = Integer.MIN_VALUE;
      break;
    }
    result[OFFSET_BYTES_POS] = offsetBytes;
    result[OFFSET_IS_DEFAULT_POS] = offsetIsDefault;
    result[MAX_VAR_WIDTH_POS] = maxVarWidth;
  }

  // passing maxDataLength as long to avoid possible INTEGER_MAX overflow
  public static int getOffsetBytesForLength(final long maxDataLength,
      final int numVarLengthCols) {
    if (maxDataLength + numVarLengthCols <= 0x7F) {
      return 1;
    }
    if (maxDataLength + (numVarLengthCols * 2) <= 0x7FFF) {
      return 2;
    }
    if (maxDataLength + (numVarLengthCols * 3) <= 0x7FFFFF) {
      return 3;
    }
    return 4;
  }

  public static int getOffsetValueForDefault(final int offsetBytes) {
    switch (offsetBytes) {
    case 1:
      return -(0x7F + 1);
    case 2:
      return -(0x7FFF + 1);
    case 3:
      return -(0x7FFFFF + 1);
    default:
      assert offsetBytes == 4 : offsetBytes;
      return Integer.MIN_VALUE;
    }
  }

  private int getVersionNumBytes(final int version) {
    return version != 0 ? getCompactIntNumBytes(version) : 0;
  }

  private byte[] getVersionBytes(final int version, final int nbytes) {
    if (nbytes > 1) {
      final byte[] vbytes = new byte[nbytes];
      writeCompactInt(vbytes, version, 0);
      return vbytes;
    }
    else {
      return null;
    }
  }

  private byte getVersionByte(final int version, final int nbytes) {
    return (nbytes == 1) ? getCompactIntByte(version) : 0;
  }

  /**
   * Return the width in bytes for a single variable field given its
   * DataValueDescriptor and ColumnDescriptor. Does not include space required
   * for the offset.
   */
  private int getVarColumnWidth(final DataValueDescriptor dvd,
      final ColumnDescriptor cd) throws StandardException {
    // check to see if this var length field is equal to the default value;
    // if it is, then data length is zero
    if (dvd != null) {
      final DataValueDescriptor defaultValue = cd.columnDefault;
      if (defaultValue == null || !defaultValue.equals(dvd)) {
        return dvd.getLengthInBytes(cd.columnType);
      }
      return 0;
    }
    // it can happen that dvd is null here if the dvd[] this row is based on
    // is sparse. If this dvd is null, treat it as a null value.
    return 0;
  }

  /**
   * Return the width in bytes for a single non-null/default variable field
   * where actual variable field offset has already been read from byte[].
   *
   * Does not include space required for the offset.
   */
  private int getVarColumnWidth(final UnsafeWrapper unsafe, final long memAddr,
      final int bytesLen, int offsetOffset, final int offset) {
    // Calculate columnWidth based on next offset or lack thereof.
    // The next offset will be absent only for DEFAULT value in
    // which case try the next column offset.
    while ((offsetOffset += this.offsetBytes) < bytesLen) {
      final int nextColumnOffset = readVarDataOffset(unsafe, memAddr,
          offsetOffset);
      if (nextColumnOffset >= 0) {
        return nextColumnOffset - offset;
      }
      if (!isVarDataOffsetDefaultToken(nextColumnOffset)) {
        return (-nextColumnOffset) - 1 - offset;
      }
    }
    // at end of row, was no next offset
    // so columnWidth is up to beginning of offsets
    return bytesLen - (this.numVarWidthColumns * this.offsetBytes) - offset;
  }

  /**
   * Return the width in bytes for a single non-null/default variable field
   * where actual variable field offset has already been read from byte[].
   *
   * Does not include space required for the offset.
   */
  private int getVarColumnWidth(final byte[] bytes, final int bytesLen,
      int offsetOffset, final int offset) {
    // Calculate columnWidth based on next offset or lack thereof.
    // The next offset will be absent only for DEFAULT value in
    // which case try the next column offset.
    while ((offsetOffset += this.offsetBytes) < bytesLen) {
      final int nextColumnOffset = readVarDataOffset(bytes, offsetOffset);
      if (nextColumnOffset >= 0) {
        return nextColumnOffset - offset;
      }
      if (!isVarDataOffsetDefaultToken(nextColumnOffset)) {
        return (-nextColumnOffset) - 1 - offset;
      }
    }
    // at end of row, was no next offset
    // so columnWidth is up to beginning of offsets
    return bytesLen - (this.numVarWidthColumns * this.offsetBytes) - offset;
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1). Does not include space required for the
   * offset if is a var width column.
   */
  private int getColumnWidth(final int index, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final ColumnDescriptor cd) {
    return getColumnWidth(index, unsafe, memAddr, bytesLen, cd, false);
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1). Does not include space required for the
   * offset if is a var width column.
   */
  private int getColumnWidth(final int index, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final ColumnDescriptor cd,
      final boolean truncateSpaces) {
    final int offsetFromMap = this.positionMap[index];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final int columnWidth = cd.fixedWidth;
      assert columnWidth > 0: "unexpected fixed width " + columnWidth
          + " for column: " + cd;
      return columnWidth;
    } else if (offsetFromMap < 0) {
      // var width, offsetFromMap is negative offset of offset from end
      // of byte array
      final int offsetOffset = bytesLen + offsetFromMap;
      final int offset = readVarDataOffset(unsafe, memAddr, offsetOffset);
      if (offset >= this.nVersionBytes) {
        if (truncateSpaces && shouldTrimTrailingSpaces(cd)) {
          return trimTrailingSpaces(memAddr, offset, getVarColumnWidth(unsafe,
              memAddr, bytesLen, offsetOffset, offset));
        } else {
          return getVarColumnWidth(unsafe, memAddr, bytesLen, offsetOffset,
              offset);
        }
      }
    }
    // column is either null or has default value
    return 0;
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1). Does not include space required for the
   * offset if is a var width column.
   */
  private int getColumnWidth(final int index, final byte[] bytes,
      final ColumnDescriptor cd) {
    return getColumnWidth(index, bytes, cd, false);
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1). Does not include space required for the
   * offset if is a var width column.
   */
  private int getColumnWidth(final int index, final byte[] bytes,
      final ColumnDescriptor cd, final boolean truncateSpaces) {
    final int offsetFromMap = this.positionMap[index];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final int columnWidth = cd.fixedWidth;
      assert columnWidth > 0: "unexpected fixed width " + columnWidth
          + " for column: " + cd;
      return columnWidth;
    } else if (offsetFromMap < 0) {
      final int size = bytes.length;
      // var width, offsetFromMap is negative offset of offset from end
      // of byte array
      final int offsetOffset = size + offsetFromMap;
      final int offset = readVarDataOffset(bytes, offsetOffset);
      if (offset >= this.nVersionBytes) {
        if (truncateSpaces && shouldTrimTrailingSpaces(cd)) {
          return trimTrailingSpaces(bytes, offset,
              getVarColumnWidth(bytes, size, offsetOffset, offset));
        } else {
          return getVarColumnWidth(bytes, size, offsetOffset, offset);
        }
      }
    }
    // column is either null or has default value
    return 0;
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1) for a target RowFormatter, including the space
   * required for the offset if is a var width column.
   */
  private int getTotalColumnWidth(final int index, final byte[] bytes,
      final ColumnDescriptor currentFormatCD, final RowFormatter currentFormat) {
    final int offsetFromMap = currentFormat.positionMap[index];
    if (offsetFromMap >= currentFormat.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final int columnWidth = currentFormatCD.fixedWidth;
      assert columnWidth > 0 : "unexpected fixed width " + columnWidth
          + " for column: " + currentFormatCD;
      return columnWidth;
    } else if (offsetFromMap < 0) {
      // if there is schema change then its possible that previously this
      // was a non-nullable fixed-width column
      int thisOffsetFromMap;
      if ((thisOffsetFromMap = this.positionMap[index]) < 0) {
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int size = bytes.length;
        final int offsetOffset = size + thisOffsetFromMap;
        final int offset = readVarDataOffset(bytes, offsetOffset);
        int len = currentFormat.getNumOffsetBytes();
        if (offset >= this.nVersionBytes) {
          len += getVarColumnWidth(bytes, size, offsetOffset, offset);
        }
        return len;
      } else if (thisOffsetFromMap >= this.nVersionBytes) {
        return (currentFormat.getNumOffsetBytes() + currentFormatCD.fixedWidth);
      } else {
        // value is either null or has default value

        // if value is default, then its possible that srcFormat has non-null
        // default but target "this" has null for on-the-fly created projection
        // RowFormatters, or current RowFormatter (#47054)
        ColumnDescriptor srcCD = this.getColumnDescriptor(index);
        if (this != currentFormat
             && srcCD.columnDefault != currentFormatCD.columnDefault) {
          return currentFormat.getNumOffsetBytes() + srcCD.columnDefaultBytes.length;
        }
        return currentFormat.getNumOffsetBytes();
      }
    }
    // column is either null or has default value
    return currentFormat.getNumOffsetBytes();
  }

  /**
   * Return the width in bytes for a single field given the row bytes and column
   * index (logical position - 1) for a target RowFormatter, including the space
   * required for the offset if is a var width column.
   */
  private int getTotalColumnWidth(final int index, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen,
      final ColumnDescriptor currentFormatCD, final RowFormatter currentFormat) {
    final int offsetFromMap = currentFormat.positionMap[index];
    if (offsetFromMap >= currentFormat.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final int columnWidth = currentFormatCD.fixedWidth;
      assert columnWidth > 0 : "unexpected fixed width " + columnWidth
          + " for column: " + currentFormatCD;
      return columnWidth;
    } else if (offsetFromMap < 0) {
      // if there is schema change then its possible that previously this
      // was a non-nullable fixed-width column
      int thisOffsetFromMap;
      if ((thisOffsetFromMap = this.positionMap[index]) < 0) {
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int offsetOffset = bytesLen + thisOffsetFromMap;
        final int offset = readVarDataOffset(unsafe, memAddr, offsetOffset);
        int len = currentFormat.getNumOffsetBytes();
        if (offset >= this.nVersionBytes) {
          len += getVarColumnWidth(unsafe, memAddr, bytesLen, offsetOffset,
              offset);
        }
        return len;
      } else if (thisOffsetFromMap >= this.nVersionBytes) {
        return (currentFormat.getNumOffsetBytes() + currentFormatCD.fixedWidth);
      } else {
        // value is either null or has default value

        // if value is default, then its possible that srcFormat has non-null
        // default but target "this" has null for on-the-fly created projection
        // RowFormatters, or current RowFormatter (#47054)
        ColumnDescriptor srcCD = this.getColumnDescriptor(index);
        if (this != currentFormat
             && srcCD.columnDefault != currentFormatCD.columnDefault) {
          return currentFormat.getNumOffsetBytes() + srcCD.columnDefaultBytes.length;
        }
        return currentFormat.getNumOffsetBytes();
      }
    }
    // column is either null or has default value
    return currentFormat.getNumOffsetBytes();
  }

  /**
   * Return the width in bytes for a single field given the row bytes having
   * given RowFormatter and column index (logical position - 1), including the
   * space required for the offset if is a var width column.
   */
  private int getTotalColumnWidth(final int srcIndex, final byte[] srcBytes,
      final RowFormatter srcFormat, final ColumnDescriptor srcCD,
      final int index, final boolean isSameSchema) {
    final int offsetFromMap = this.positionMap[index];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final ColumnDescriptor cd = isSameSchema ? srcCD : this.columns[index];
      final int columnWidth = cd.fixedWidth;
      assert columnWidth > 0: "unexpected fixed width " + columnWidth
          + " for column: " + cd;
      return columnWidth;
    }
    else if (offsetFromMap < 0) {
      // if there is schema change then its possible that previously this
      // was a non-nullable fixed-width column
      final boolean sameFormat = (srcFormat == this);
      int srcOffsetFromMap = offsetFromMap;
      if (sameFormat
          || (srcOffsetFromMap = srcFormat.positionMap[srcIndex]) < 0) {
        if (srcBytes == null) {
          // copy as null value
          return getNumOffsetBytes();
        }
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int srcBytesLength = srcBytes.length;
        final int offsetOffset = srcBytesLength + srcOffsetFromMap;
        final int offset = srcFormat.readVarDataOffset(srcBytes, offsetOffset);
        int len = getNumOffsetBytes();
        if (offset > 0) {
          len += srcFormat.getVarColumnWidth(srcBytes, srcBytesLength,
              offsetOffset, offset);
        }
        return len;
      }
      else if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        return (getNumOffsetBytes() + srcCD.fixedWidth);
      }
      else {
        // value is either null or has default value

        // if value is default, then its possible that srcFormat has non-null
        // default but target "this" has null for on-the-fly created projection
        // RowFormatters, or current RowFormatter (#47054)
        if (srcCD.columnDefault != this.columns[index].columnDefault) {
          return getNumOffsetBytes() + srcCD.columnDefaultBytes.length;
        }
        return getNumOffsetBytes();
      }
    }
    // column is either null or has default value
    return getNumOffsetBytes();
  }

  /**
   * Return the width in bytes for a single field given the row bytes having
   * given RowFormatter and column index (logical position - 1), including the
   * space required for the offset if is a var width column.
   */
  private int getTotalColumnWidth(final int srcIndex,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final RowFormatter srcFormat, final ColumnDescriptor srcCD,
      final int index, final boolean isSameSchema) {
    final int offsetFromMap = this.positionMap[index];
    if (offsetFromMap >= this.nVersionBytes) {
      // fixed width, offsetFromMap is offset to data
      final ColumnDescriptor cd = isSameSchema ? srcCD : this.columns[index];
      final int columnWidth = cd.fixedWidth;
      assert columnWidth > 0: "unexpected fixed width " + columnWidth
          + " for column: " + cd;
      return columnWidth;
    }
    else if (offsetFromMap < 0) {
      // if there is schema change then its possible that previously this
      // was a non-nullable fixed-width column
      final boolean sameFormat = (srcFormat == this);
      int srcOffsetFromMap = offsetFromMap;
      if (sameFormat
          || (srcOffsetFromMap = srcFormat.positionMap[srcIndex]) < 0) {
        if (memAddr == 0) {
          // copy as null value
          return getNumOffsetBytes();
        }
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int offsetOffset = bytesLen + srcOffsetFromMap;
        final int offset = srcFormat.readVarDataOffset(unsafe, memAddr,
            offsetOffset);
        int len = getNumOffsetBytes();
        if (offset > 0) {
          len += srcFormat.getVarColumnWidth(unsafe, memAddr, bytesLen,
              offsetOffset, offset);
        }
        return len;
      }
      else if (srcOffsetFromMap >= srcFormat.nVersionBytes) {
        return (getNumOffsetBytes() + srcCD.fixedWidth);
      }
      else {
        // value is either null or has default value

        // if value is default, then its possible that srcFormat has non-null
        // default but target "this" has null for on-the-fly created projection
        // RowFormatters, or current RowFormatter (#47054)
        if (srcCD.columnDefault != this.columns[index].columnDefault) {
          return getNumOffsetBytes() + srcCD.columnDefaultBytes.length;
        }
        return getNumOffsetBytes();
      }
    }
    // column is either null or has default value
    return getNumOffsetBytes();
  }

  /**
   * Return the width in bytes of the given type with the given id, or -1 if it
   * is a variable width type. If the type is nullable, returns -1 to indicate
   * variable width. LOBs are special, they return 0 since they are not stored
   * inside the main byte array.
   */
  public static int getFixedWidthInBytes(final DataTypeDescriptor dtd,
      final ColumnDescriptor cd) {
    final int jdbcTypeId = dtd.getJDBCTypeId();
    switch (jdbcTypeId) {
    case Types.BLOB:
    case Types.CLOB:
    case Types.JAVA_OBJECT:
    case Types.SQLXML:
    case JDBC40Translation.JSON:
      return 0;
    }

    // if nullable or having default, store as var width
    if (dtd.isNullable() || cd.columnDefault != null) {
      return -1;
    }
    final int maxWidth = getMaxWidthInBytes(dtd);
    if (maxWidth >= 0) {
      return maxWidth;
    }
    return -1;
  }

  /**
   * Return the maximum width in bytes of the given variable length type with
   * the given id.
   */
  private static int getMaxWidthInBytes(final DataTypeDescriptor dtd) {
    final int jdbcTypeId = dtd.getJDBCTypeId();
    switch (jdbcTypeId) {
    case Types.BIGINT:
      return TypeId.LONGINT_MAXWIDTH;
    case Types.BIT: // same as BOOLEAN
    case Types.BOOLEAN:
      return TypeId.BOOLEAN_MAXWIDTH;
    case Types.DATE:
      // dates are stored as an encoded integer
      return TypeId.INT_MAXWIDTH;
    case Types.FLOAT: // same as DOUBLE
    case Types.DOUBLE:
      return TypeId.DOUBLE_MAXWIDTH;
    case Types.INTEGER:
      return TypeId.INT_MAXWIDTH;
    case Types.REAL:
      return TypeId.REAL_MAXWIDTH;
    case Types.SMALLINT:
      return TypeId.SMALLINT_MAXWIDTH;
    case Types.TIME:
      // times are stored as as int (time) + int (time fraction)
      return TypeId.INT_MAXWIDTH * 2;
    case Types.TIMESTAMP:
      // timestamps are stored as int (date) + int (time) + int (nanoseconds)
      return TypeId.INT_MAXWIDTH * 3;
    case Types.TINYINT:
      return TypeId.TINYINT_MAXWIDTH;

      // Variable width types return -ve to distinguish from fixed width types
    case Types.BINARY:
    case Types.LONGVARBINARY:
    case Types.DECIMAL:
    case Types.NUMERIC:
    case Types.VARBINARY:
      return -(dtd.getMaximumWidth() + 1);
      // now we store CHAR as variable width value
    case Types.CHAR:
    case Types.VARCHAR:
    case Types.LONGVARCHAR:
      // *3 for worst case in UTF8 encoding
      return -(dtd.getMaximumWidth() * 3);

      // types for which width is not known
    case Types.OTHER:
    case Types.REF:
      return Integer.MIN_VALUE;

      // LOB types return 0
    case Types.BLOB:
    case Types.CLOB:
    case Types.JAVA_OBJECT:
    case Types.SQLXML:
    case JDBC40Translation.JSON:
      return 0;

      // unsupported types
    case Types.ARRAY:
    case Types.DATALINK:
    case Types.DISTINCT:
    case Types.NULL:
    case Types.STRUCT:
    default:
      throw new AssertionError("unsupported variable JDBC type: " + jdbcTypeId);
    }
  }

  public static boolean isLob(final DataTypeDescriptor dtd) {
    switch (dtd.getJDBCTypeId()) {
    case Types.BLOB:
    case Types.CLOB:
    case Types.JAVA_OBJECT:
    case Types.SQLXML:
    case JDBC40Translation.JSON:
      return true;
    default:
      return false;
    }
  }

  public static boolean isLob(final int dvdFormatId) {
    switch (dvdFormatId) {
    case StoredFormatIds.SQL_BLOB_ID:
    case StoredFormatIds.SQL_CLOB_ID:
    case StoredFormatIds.SQL_USERTYPE_ID_V3:
    case StoredFormatIds.SQL_XML_UTIL_V01_ID:
    case StoredFormatIds.XML_ID:
    case StoredFormatIds.SQL_JSON_ID:
      return true;
    default:
      return false;
    }
  }

  /**
   * Execute a given {@link ColumnProcessor} for the specified column as its 0
   * based index in the row. Returns the last result from
   * {@link ColumnProcessor} handle methods. The target RowFormatter must be
   * non-null for this method.
   */
  public final <O> int processColumn(final byte[] bytes, final int index,
      final ColumnProcessor<O> processColumn, final O out, int writePosition,
      final RowFormatter targetFormat, final int targetIndex) {
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final int targetOffsetFromMap = targetFormat.positionMap[targetIndex];
    if (offsetFromMap >= this.nVersionBytes) {
      final int columnWidth = cd.fixedWidth;
      // check if targetFormat is variable width then need to write the column
      // width too
      if (targetOffsetFromMap >= targetFormat.nVersionBytes) {
        return processColumn.handleFixed(bytes, offsetFromMap, columnWidth,
            out, writePosition, this, index, targetFormat, targetIndex,
            targetOffsetFromMap);
      }
      else if (targetOffsetFromMap < 0) {
        return processColumn.handleVariable(bytes, offsetFromMap, columnWidth,
            out, writePosition, this, cd, index, targetFormat, targetIndex,
            targetOffsetFromMap);
      }
      else {
        return processColumn.handleDefault(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
    }
    else {
      final long offsetAndWidth = getOffsetAndWidth(index, bytes,
          offsetFromMap, cd);
      if (offsetAndWidth >= 0) {
        final int columnWidth = (int)offsetAndWidth;
        final int offset = (int)(offsetAndWidth >>> Integer.SIZE);

        // check if targetFormat is variable width then need to skip the column
        // width write
        if (targetOffsetFromMap < 0) {
          return processColumn.handleVariable(bytes, offset, columnWidth, out,
              writePosition, this, cd, index, targetFormat, targetIndex,
              targetOffsetFromMap);
        }
        else if (targetOffsetFromMap >= targetFormat.nVersionBytes) {
          return processColumn.handleFixed(bytes, offset, columnWidth, out,
              writePosition, this, index, targetFormat, targetIndex,
              targetOffsetFromMap);
        }
        else {
          return processColumn.handleDefault(out, writePosition, this, cd,
              index, targetFormat, targetIndex, targetOffsetFromMap);
        }
      }
      else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        return processColumn.handleNull(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
      else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
        return processColumn.handleDefault(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
    }
  }

  /**
   * Execute a given {@link ColumnProcessorOffHeap} for the specified column as
   * its 0 based index in the row. Returns the last result from
   * {@link ColumnProcessor} handle methods. The target RowFormatter must be
   * non-null for this method.
   */
  public final <O> int processColumn(final UnsafeWrapper unsafe,
      final long memAddress, final int bytesLen, final int index,
      final ColumnProcessorOffHeap<O> processColumn, final O out,
      int writePosition, final RowFormatter targetFormat, final int targetIndex) {
    final int offsetFromMap = this.positionMap[index];
    final ColumnDescriptor cd = this.columns[index];
    final int targetOffsetFromMap = targetFormat.positionMap[targetIndex];
    if (offsetFromMap >= this.nVersionBytes) {
      final int columnWidth = cd.fixedWidth;
      // check if targetFormat is variable width then need to write the column
      // width too
      if (targetOffsetFromMap >= targetFormat.nVersionBytes) {
        return processColumn.handleFixed(memAddress, offsetFromMap,
            columnWidth, out, writePosition, this, index, targetFormat,
            targetIndex, targetOffsetFromMap);
      }
      else if (targetOffsetFromMap < 0) {
        return processColumn.handleVariable(memAddress, offsetFromMap,
            columnWidth, out, writePosition, this, cd, index, targetFormat,
            targetIndex, targetOffsetFromMap);
      }
      else {
        return processColumn.handleDefault(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
    }
    else {
      final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddress,
          bytesLen, offsetFromMap, cd);
      if (offsetAndWidth >= 0) {
        final int columnWidth = (int)offsetAndWidth;
        final int offset = (int)(offsetAndWidth >>> Integer.SIZE);

        // check if targetFormat is variable width then need to skip the column
        // width write
        if (targetOffsetFromMap < 0) {
          return processColumn.handleVariable(memAddress, offset,
              columnWidth, out, writePosition, this, cd, index, targetFormat,
              targetIndex, targetOffsetFromMap);
        }
        else if (targetOffsetFromMap >= targetFormat.nVersionBytes) {
          return processColumn.handleFixed(memAddress, offset,
              columnWidth, out, writePosition, this, index, targetFormat,
              targetIndex, targetOffsetFromMap);
        }
        else {
          return processColumn.handleDefault(out, writePosition, this, cd,
              index, targetFormat, targetIndex, targetOffsetFromMap);
        }
      }
      else if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        return processColumn.handleNull(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
      else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
        return processColumn.handleDefault(out, writePosition, this, cd, index,
            targetFormat, targetIndex, targetOffsetFromMap);
      }
    }
  }

  /**
   * Get the LOB column as a raw byte[]. Incoming position should correspond to
   * a LOB column.
   */
  public final byte[] getLob(
      @Unretained final OffHeapRowWithLobs byteArrays,
      final int logicalPosition) {
    final int index = logicalPosition - 1;
    if (byteArrays != null) {
      final int offsetFromMap = this.positionMap[index];
      if (offsetFromMap > 0) {
        if (SanityManager.DEBUG) {
          if (!this.columns[index].isLob) {
            SanityManager.THROWASSERT("unexpected non-LOB column at position "
                + logicalPosition + ", found: " + this.columns[index]);
          }
        }
        return byteArrays.getGfxdBytes(offsetFromMap);
      } else if (offsetFromMap == 0) {
        return this.columns[index].columnDefaultBytes;
      } else {
        SanityManager.THROWASSERT("getLob: unexpected offsetFromMap="
            + offsetFromMap + " for column at position " + logicalPosition
            + ", found: " + this.columns[index]);
        // never reached
        return null;
      }
    } else {
      return this.columns[index].columnDefaultBytes;
    }
  }

  /**
   * Serialize the LOB column as a byte[] (using
   * {@link InternalDataSerializer#writeByteArray}) on the given DataOutput.
   * Incoming position should correspond to a LOB column.
   */
  public final void serializeLob(
      @Unretained final OffHeapRowWithLobs byteArrays,
      final int logicalPosition, final DataOutput out) throws IOException {
    final int index = logicalPosition - 1;
    if (byteArrays != null) {
      final int offsetFromMap = this.positionMap[index];
      if (offsetFromMap > 0) {
        if (SanityManager.DEBUG) {
          if (!this.columns[index].isLob) {
            SanityManager.THROWASSERT("unexpected non-LOB column at position "
                + logicalPosition + ", found: " + this.columns[index]);
          }
        }
        byteArrays.serializeGfxdBytes(offsetFromMap, out);
      }
      else if (offsetFromMap == 0) {
        InternalDataSerializer.writeByteArray(
            this.columns[index].columnDefaultBytes, out);
      }
      else {
        SanityManager.THROWASSERT("getLob: unexpected offsetFromMap="
            + offsetFromMap + " for column at position " + logicalPosition
            + ", found: " + this.columns[index]);
        // never reached
      }
    }
    else {
      InternalDataSerializer.writeByteArray(
          this.columns[index].columnDefaultBytes, out);
    }
  }

  /**
   * Get the LOB column as a raw byte[]. Incoming position should correspond to
   * a LOB column.
   */
  public final byte[] getLob(final byte[][] byteArrays,
      final int logicalPosition) {
    final int index = logicalPosition - 1;
    if (byteArrays != null) {
      final int offsetFromMap = this.positionMap[index];
      if (offsetFromMap > 0) {
        if (SanityManager.DEBUG) {
          if (!this.columns[index].isLob) {
            SanityManager.THROWASSERT("unexpected non-LOB column at position "
                + logicalPosition + ", found: " + this.columns[index]);
          }
        }
        return byteArrays[offsetFromMap];
      } else if (offsetFromMap == 0) {
        return this.columns[index].columnDefaultBytes;
      } else {
        SanityManager.THROWASSERT("getLob: unexpected offsetFromMap="
            + offsetFromMap + " for column at position " + logicalPosition
            + ", found: " + this.columns[index]);
        // never reached
        return null;
      }
    } else {
      return this.columns[index].columnDefaultBytes;
    }
  }

  public final byte[] getColumnAsByteSource(final byte[][] byteArrays,
      final int logicalPosition) {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (byteArrays != null) {
      if (!cd.isLob) {
        return byteArrays[0];
      }

      final int offsetFromMap = this.positionMap[index];
      if (offsetFromMap > 0) {
        return byteArrays[offsetFromMap];
      }
      else if (offsetFromMap == 0) {
        return cd.columnDefaultBytes;
      }
      else {
        SanityManager.THROWASSERT("getLob: unexpected offsetFromMap="
            + offsetFromMap + " for column at position " + logicalPosition
            + ", found: " + cd);
        // never reached
        return null;
      }
    }
    else {
      return cd.columnDefaultBytes;
    }
  }

  @Unretained
  public final Object getColumnAsByteSource(
      @Unretained final OffHeapRowWithLobs byteArrays, final int logicalPosition) {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (byteArrays != null) {
      if (!cd.isLob) {
        return byteArrays;
      }

      final int offsetFromMap = this.positionMap[index];
      if (offsetFromMap > 0) {
        return byteArrays.getGfxdByteSource(offsetFromMap);
      }
      else if (offsetFromMap == 0) {
        return cd.columnDefaultBytes;
      }
      else {
        SanityManager.THROWASSERT("getLob: unexpected offsetFromMap="
            + offsetFromMap + " for column at position " + logicalPosition
            + ", found: " + cd);
        // never reached
        return null;
      }
    }
    else {
      return cd.columnDefaultBytes;
    }
  }

  /////////////////// PACKAGE VISIBLE METHODS ///////////////////

  final UTF8String getAsUTF8String(final int index,
      final byte[] bytes) throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes,
        offsetFromMap, cd, false);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsUTF8String(bytes, offset, columnWidth, cd);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        return null;
      } else {
        final byte[] defaultBytes = cd.columnDefaultBytes;
        if (defaultBytes != null) {
          return UTF8String.fromAddress(defaultBytes,
              Platform.BYTE_ARRAY_OFFSET, defaultBytes.length);
        } else {
          return null;
        }
      }
    }
  }

  final UTF8String getAsUTF8String(final int index,
      final byte[][] byteArrays) throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsUTF8String(index, byteArrays[0]);
    } else {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        return DataTypeUtilities.getAsUTF8String(bytes, 0, bytes.length, cd);
      } else {
        return null;
      }
    }
  }

  private UTF8String getAsUTF8String(final int index,
      final UnsafeWrapper unsafe, final long memAddr,
      final int bytesLen) throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsUTF8String(memAddr + offset,
          columnWidth, cd);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        return null;
      } else {
        final byte[] defaultBytes = cd.columnDefaultBytes;
        if (defaultBytes != null) {
          return UTF8String.fromAddress(defaultBytes,
              Platform.BYTE_ARRAY_OFFSET, defaultBytes.length);
        } else {
          return null;
        }
      }
    }
  }

  final UTF8String getAsUTF8String(final int index,
      @Unretained final OffHeapRow bytes) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);
    return getAsUTF8String(index, unsafe, memAddr, bytesLen);
  }

  final UTF8String getAsUTF8String(final int index,
      @Unretained final OffHeapRowWithLobs byteArrays)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsUTF8String(index, unsafe, memAddr, bytesLen);
    } else {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          return DataTypeUtilities.getAsUTF8String(bytes, 0, bytes.length, cd);
        } else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final int bytesLen = bs.getLength();
          final long memAddr = bs.getUnsafeAddress(0, bytesLen);
          return DataTypeUtilities.getAsUTF8String(memAddr, bytesLen, cd);
        }
      } else {
        return null;
      }
    }
  }

  final String getAsString(final int index, final ColumnDescriptor cd,
      final byte[] bytes, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsString(bytes, offset, columnWidth,
          cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getString();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final String getAsString(final int logicalPosition, final byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsString(index, this.columns[index], bytes, wasNull);
  }

  final String getAsString(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsString(index, cd, byteArrays[0], wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        return DataTypeUtilities.getAsString(bytes, 0, bytes.length,
            cd.columnType);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final String getAsString(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsString(unsafe, memAddr + offset,
          columnWidth, bs, cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getString();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final String getAsString(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];

    return getAsString(index, cd, unsafe, memAddr, bytesLen, bytes, wasNull);
  }

  final String getAsString(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsString(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          return DataTypeUtilities.getAsString(bytes, 0, bytes.length,
              cd.columnType);
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
          final int bytesLen = bs.getLength();
          final long memAddr = bs.getUnsafeAddress(0, bytesLen);
          return DataTypeUtilities.getAsString(unsafe, memAddr, bytesLen, bs,
              cd.columnType);
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final Object getAsObject(final int index, final ColumnDescriptor cd,
      final byte[] bytes, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsObject(bytes, offset, columnWidth,
          cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getObject();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  public final Object getAsObject(final int logicalPosition, final byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsObject(index, this.columns[index], bytes, wasNull);
  }

  public final Object getAsObject(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsObject(index, cd, byteArrays[0], wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        return DataTypeUtilities.getAsObject(bytes, 0, bytes.length,
            cd.columnType);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  public final Object getAsObject(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsObject(unsafe, memAddr + offset,
          columnWidth, bs, cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getObject();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  public final Object getAsObject(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];

    return getAsObject(index, cd, unsafe, memAddr, bytesLen, bytes, wasNull);
  }

  public final Object getAsObject(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsObject(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          return DataTypeUtilities.getAsObject(bytes, 0, bytes.length,
              cd.columnType);
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
          final int bytesLen = bs.getLength();
          final long memAddr = bs.getUnsafeAddress(0, bytesLen);
          return DataTypeUtilities.getAsObject(unsafe, memAddr, bytesLen, bs,
              cd.columnType);
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final boolean getAsBoolean(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsBoolean(bytes, offsetFromMap,
          cd.fixedWidth, cd.columnType);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBoolean(bytes, offset, columnWidth,
          cd.columnType);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return false;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getBoolean();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return false;
        }
      }
    }
  }

  final boolean getAsBoolean(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsBoolean(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BOOLEAN", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final boolean getAsBoolean(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBoolean(unsafe, memAddr + offset,
          columnWidth, bs, cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getBoolean();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return false;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return false;
      }
    }
  }

  final boolean getAsBoolean(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsBoolean(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final boolean getAsBoolean(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsBoolean(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BOOLEAN", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final byte getAsByte(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsByte(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsByte(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getByte();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  final byte getAsByte(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsByte(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TINYINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final byte getAsByte(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsByte(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getByte();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      }
    }
  }

  final byte getAsByte(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsByte(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final byte getAsByte(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsByte(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TINYINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final short getAsShort(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsShort(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsShort(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getShort();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  final short getAsShort(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsShort(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "SMALLINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final short getAsShort(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsShort(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getShort();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      }
    }
  }

  final short getAsShort(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsShort(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final short getAsShort(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsShort(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "SMALLINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  public final int getAsInt(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsInt(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsInt(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getInt();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      }
    }
  }

  public final int getAsInt(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsInt(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "INTEGER", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final int getAsInt(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsInt(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getInt();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      }
    }
  }

  public final int getAsInt(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsInt(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  public final int getAsInt(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsInt(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "INTEGER", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final long getAsLong(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsLong(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsLong(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0L;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getLong();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0L;
        }
      }
    }
  }

  final long getAsLong(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsLong(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BIGINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final long getAsLong(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsLong(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getLong();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0;
      }
    }
  }

  final long getAsLong(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsLong(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final long getAsLong(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsLong(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BIGINT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final float getAsFloat(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsFloat(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsFloat(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0.0f;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getFloat();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0.0f;
        }
      }
    }
  }

  final float getAsFloat(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsFloat(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "FLOAT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final float getAsFloat(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsFloat(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getFloat();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0.0f;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0.0f;
      }
    }
  }

  final float getAsFloat(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsFloat(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final float getAsFloat(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsFloat(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "FLOAT", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final double getAsDouble(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth;
    if (offsetFromMap >= this.nVersionBytes) {
      return DataTypeUtilities.getAsDouble(bytes, offsetFromMap,
          cd.fixedWidth, cd);
    } else if ((offsetAndWidth = getVarOffsetAndWidth(bytes,
        offsetFromMap, cd)) >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsDouble(bytes, offset, columnWidth, cd);
    } else {
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0.0;
      } else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDouble();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0.0;
        }
      }
    }
  }

  final double getAsDouble(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[logicalPosition - 1];
    if (!cd.isLob) {
      return getAsDouble(logicalPosition, byteArrays[0], wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DOUBLE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final double getAsDouble(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsDouble(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDouble();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0.0;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return 0.0;
      }
    }
  }

  final double getAsDouble(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsDouble(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final double getAsDouble(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsDouble(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DOUBLE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final BigDecimal getAsBigDecimal(final int index, final ColumnDescriptor cd,
      byte[] bytes, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBigDecimal(bytes, offset, columnWidth, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return SQLDecimal.getBigDecimal(dvd);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final BigDecimal getAsBigDecimal(final int logicalPosition, byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsBigDecimal(index, this.columns[index], bytes, wasNull);
  }

  final BigDecimal getAsBigDecimal(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsBigDecimal(index, cd, byteArrays[0], wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DECIMAL", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final BigDecimal getAsBigDecimal(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBigDecimal(memAddr + offset,
          columnWidth, bs, cd);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return SQLDecimal.getBigDecimal(dvd);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final BigDecimal getAsBigDecimal(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsBigDecimal(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, wasNull);
  }

  final BigDecimal getAsBigDecimal(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsBigDecimal(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DECIMAL", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final byte[] getAsBytes(final int index, final ColumnDescriptor cd,
      final byte[] bytes, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBytes(bytes, offset, columnWidth,
          cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getBytes();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final byte[] getAsBytes(final int logicalPosition, final byte[] bytes,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsBytes(index, this.columns[index], bytes, wasNull);
  }

  final byte[] getAsBytes(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsBytes(index, cd, byteArrays[0], wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        return DataTypeUtilities.getAsBytes(bytes, 0, bytes.length,
            cd.columnType);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final byte[] getAsBytes(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsBytes(memAddr, offset, columnWidth,
          bs, cd.columnType);
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getBytes();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final byte[] getAsBytes(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final ResultWasNull wasNull)
      throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];

    return getAsBytes(index, cd, unsafe, memAddr, bytesLen, bytes, wasNull);
  }

  final byte[] getAsBytes(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsBytes(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          wasNull);
    }
    else {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          return DataTypeUtilities.getAsBytes(bytes, 0, bytes.length,
              cd.columnType);
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final int bytesLen = bs.getLength();
          final long memAddr = bs.getUnsafeAddress(0, bytesLen);
          return DataTypeUtilities.getAsBytes(memAddr, 0, bytesLen, bs,
              cd.columnType);
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final long getAsDateMillis(final int index, byte[] bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsDateMillis(bytes, offset,
          columnWidth, cal, cd.columnType);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0L;
      } else {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDate(cal).getTime();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0L;
        }
      }
    }
  }

  final long getAsDateMillis(final int index, final byte[][] byteArrays,
      final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsDateMillis(index, byteArrays[0], cal, wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DATE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final long getAsDateMillis(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsDateMillis(unsafe, memAddr
          + offset, columnWidth, bs, cal, cd.columnType);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0L;
      } else {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDate(cal).getTime();
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0L;
        }
      }
    }
  }

  final long getAsDateMillis(final int index,
      @Unretained final OffHeapRow bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);
    return getAsDateMillis(index, this.columns[index], unsafe, memAddr,
        bytesLen, bytes, cal, wasNull);
  }

  final long getAsDateMillis(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsDateMillis(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          cal, wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DATE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Date getAsDate(final int index, final ColumnDescriptor cd,
      byte[] bytes, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Date v = DataTypeUtilities.getAsDate(bytes, offset,
          columnWidth, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDate(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Date getAsDate(final int logicalPosition, byte[] bytes,
      final Calendar cal, final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsDate(index, this.columns[index], bytes, cal, wasNull);
  }

  final java.sql.Date getAsDate(final int logicalPosition,
      final byte[][] byteArrays, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsDate(index, cd, byteArrays[0], cal, wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DATE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Date getAsDate(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Date v = DataTypeUtilities.getAsDate(unsafe, memAddr
          + offset, columnWidth, bs, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getDate(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Date getAsDate(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsDate(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, cal, wasNull);
  }

  final java.sql.Date getAsDate(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsDate(index, cd, unsafe, memAddr, bytesLen, byteArrays, cal,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "DATE", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Time getAsTime(final int index, final ColumnDescriptor cd,
      byte[] bytes, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Time v = DataTypeUtilities.getAsTime(bytes, offset,
          columnWidth, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getTime(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Time getAsTime(final int logicalPosition, byte[] bytes,
      final Calendar cal, final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    return getAsTime(index, this.columns[index], bytes, cal, wasNull);
  }

  final java.sql.Time getAsTime(final int logicalPosition,
      final byte[][] byteArrays, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsTime(index, cd, byteArrays[0], cal, wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIME", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Time getAsTime(final int index, final ColumnDescriptor cd,
      final UnsafeWrapper unsafe, long memAddr, final int bytesLen,
      @Unretained final OffHeapByteSource bs, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Time v = DataTypeUtilities.getAsTime(unsafe, memAddr
          + offset, columnWidth, bs, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getTime(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Time getAsTime(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsTime(index, this.columns[index], unsafe, memAddr, bytesLen,
        bytes, cal, wasNull);
  }

  final java.sql.Time getAsTime(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsTime(index, cd, unsafe, memAddr, bytesLen, byteArrays, cal,
          wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIME", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final long getAsTimestampMicros(final int index, final byte[] bytes,
      final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsTimestampMicros(bytes, offset, columnWidth,
          cal, cd.columnType);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0L;
      } else {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return DataTypeUtilities.getTimestampMicros(dvd.getTimestamp(cal));
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0L;
        }
      }
    }
  }

  final long getAsTimestampMicros(final int index, final byte[][] byteArrays,
      final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsTimestampMicros(index, byteArrays[0], cal, wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIMESTAMP", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final long getAsTimestampMicros(final int index,
      final ColumnDescriptor cd, final UnsafeWrapper unsafe, long memAddr,
      final int bytesLen, @Unretained final OffHeapByteSource bs,
      final Calendar cal, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return DataTypeUtilities.getAsTimestampMicros(unsafe,
          memAddr + offset, columnWidth, bs, cal, cd.columnType);
    } else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT : offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL) {
        if (wasNull != null) wasNull.setWasNull();
        return 0L;
      } else {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return DataTypeUtilities.getTimestampMicros(dvd.getTimestamp(cal));
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return 0L;
        }
      }
    }
  }

  final long getAsTimestampMicros(final int index,
      @Unretained final OffHeapRow bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);
    return getAsTimestampMicros(index, this.columns[index], unsafe, memAddr,
        bytesLen, bytes, cal, wasNull);
  }

  final long getAsTimestampMicros(final int index,
      @Unretained final OffHeapRowWithLobs byteArrays, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsTimestampMicros(index, cd, unsafe, memAddr, bytesLen,
          byteArrays, cal, wasNull);
    } else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIMESTAMP", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Timestamp getAsTimestamp(final int index,
      final ColumnDescriptor cd, byte[] bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, bytes, offsetFromMap,
        cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Timestamp v = DataTypeUtilities.getAsTimestamp(bytes,
          offset, columnWidth, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getTimestamp(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Timestamp getAsTimestamp(final int logicalPosition,
      byte[] bytes, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    return getAsTimestamp(index, this.columns[index], bytes, cal, wasNull);
  }

  final java.sql.Timestamp getAsTimestamp(final int logicalPosition,
      final byte[][] byteArrays, final Calendar cal, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      return getAsTimestamp(index, cd, byteArrays[0], cal, wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIMESTAMP", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Timestamp getAsTimestamp(final int index,
      final ColumnDescriptor cd, final UnsafeWrapper unsafe, long memAddr,
      final int bytesLen, @Unretained final OffHeapByteSource bs,
      final Calendar cal, final ResultWasNull wasNull) throws StandardException {
    final int offsetFromMap = this.positionMap[index];
    final long offsetAndWidth = getOffsetAndWidth(index, unsafe, memAddr,
        bytesLen, offsetFromMap, cd);
    if (offsetAndWidth >= 0) {
      final int columnWidth = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      final java.sql.Timestamp v = DataTypeUtilities.getAsTimestamp(unsafe,
          memAddr + offset, columnWidth, bs, cal, cd.columnType);
      if (v != null) {
        return v;
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
          || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
      if (offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT) {
        final DataValueDescriptor dvd = cd.columnDefault;
        if (dvd != null && !dvd.isNull()) {
          return dvd.getTimestamp(cal);
        } else {
          if (wasNull != null) wasNull.setWasNull();
          return null;
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
  }

  final java.sql.Timestamp getAsTimestamp(final int logicalPosition,
      @Unretained final OffHeapRow bytes, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getAsTimestamp(index, this.columns[index], unsafe, memAddr,
        bytesLen, bytes, cal, wasNull);
  }

  final java.sql.Timestamp getAsTimestamp(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays, final Calendar cal,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (!cd.isLob) {
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = byteArrays.getLength();
      final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
      return getAsTimestamp(index, cd, unsafe, memAddr, bytesLen, byteArrays,
          cal, wasNull);
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "TIMESTAMP", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Blob getAsBlob(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (cd.isLob) {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        return new HarmonySerialBlob(bytes);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BLOB", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Blob getAsBlob(final int logicalPosition,
      @Unretained OffHeapByteSource byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (cd.isLob) {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        if (lob instanceof byte[]) {
          return new HarmonySerialBlob((byte[])lob);
        }
        else {
          return new HarmonySerialBlob((OffHeapByteSource)lob);
        }
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "BLOB", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Clob getAsClob(final int logicalPosition,
      final byte[][] byteArrays, final ResultWasNull wasNull)
      throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (cd.isLob) {
      final int offsetFromMap = this.positionMap[index];
      final byte[] bytes = offsetFromMap != 0 ? byteArrays[offsetFromMap]
          : cd.columnDefaultBytes;
      if (bytes != null) {
        final int size = bytes.length;
        final char[] chars = new char[size];
        final int strlen = SQLChar.readIntoCharsFromByteArray(bytes, 0, size,
            chars);
        return new HarmonySerialClob(chars, strlen);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "CLOB", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  final java.sql.Clob getAsClob(final int logicalPosition,
      @Unretained final OffHeapByteSource byteArrays,
      final ResultWasNull wasNull) throws StandardException {
    final int index = logicalPosition - 1;
    final ColumnDescriptor cd = this.columns[index];
    if (cd.isLob) {
      final int offsetFromMap = this.positionMap[index];
      final Object lob = offsetFromMap != 0 ? byteArrays
          .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
      if (lob != null) {
        final char[] chars;
        final int strlen;
        if (lob instanceof byte[]) {
          final byte[] bytes = (byte[])lob;
          chars = new char[bytes.length];
          strlen = SQLChar.readIntoCharsFromByteArray(bytes, 0, bytes.length,
              chars);
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)lob;
          final int bytesLen = bs.getLength();
          chars = new char[bytesLen];
          strlen = SQLChar.readIntoCharsFromByteSource(UnsafeHolder.getUnsafe(),
              bs.getUnsafeAddress(0, bytesLen), bytesLen, bs, chars);
        }
        return new HarmonySerialClob(chars, strlen);
      } else {
        if (wasNull != null) wasNull.setWasNull();
        return null;
      }
    }
    else {
      throw StandardException.newException(
          SQLState.LANG_DATA_TYPE_GET_MISMATCH, "CLOB", cd.getType()
              .getFullSQLTypeName(), cd.getColumnName());
    }
  }

  /** Test method only. Not useful in general for reading anything. */
  public final int[] readAsIntArray(final int logicalPosition,
      final AbstractCompactExecRow row, final int numInts)
      throws StandardException {
    final int index = logicalPosition - 1;
    // final Object rawValue = row.getRawRowValue(false);
    final ColumnDescriptor cd = this.columns[index];
    final int offsetFromMap = this.positionMap[index];
    int offset = -1;
    Object bs = row.getBaseByteSource();
    byte[] bytes = null;
    @Unretained OffHeapByteSource obs = null;
    if (row.hasByteArrays()) {
      final Object byteArrays = bs;
      if (byteArrays != null) {
        if (byteArrays instanceof byte[][]) {
          if (cd.isLob) {
            bytes = offsetFromMap != 0 ? ((byte[][])byteArrays)[offsetFromMap]
                : cd.columnDefaultBytes;
            offset = 0;
          }
          else {
            bytes = ((byte[][])byteArrays)[0];
          }
        }
        else {
          @Unretained OffHeapByteSource lbs = (OffHeapByteSource)bs;
          if (cd.isLob) {
            bs = offsetFromMap != 0 ? lbs.getGfxdByteSource(offsetFromMap)
                : cd.columnDefaultBytes;
            offset = 0;
          }
          else {
            bs = lbs;
          }
          if (bs == null || bs instanceof byte[]) {
            bytes = (byte[])bs;
          }
          else {
            obs = (OffHeapByteSource)bs;
          }
        }
      }
    }
    else if (bs == null || bs instanceof byte[]) {
      bytes = (byte[])bs;
    }
    else {
      obs = (OffHeapByteSource)bs;
    }

    final UnsafeWrapper unsafe;
    final int bytesLen;
    final long memAddr;
    if (obs != null) {
      unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      bytesLen = obs.getLength();
      memAddr = obs.getUnsafeAddress(0, bytesLen);
    }
    else {
      unsafe = null;
      bytesLen = 0;
      memAddr = 0;
    }

    if (offset == -1) {
      final long offsetAndWidth = obs != null ? getOffsetAndWidth(index,
          unsafe, memAddr, bytesLen, offsetFromMap, cd) : getOffsetAndWidth(
          index, bytes, offsetFromMap, cd);
      if (offsetAndWidth >= 0) {
        offset = (int)(offsetAndWidth >>> Integer.SIZE);
      }
      else {
        assert offsetAndWidth == OFFSET_AND_WIDTH_IS_NULL
            || offsetAndWidth == OFFSET_AND_WIDTH_IS_DEFAULT: offsetAndWidth;
        // currently just returning null for default or null
        return null;
      }
    }
    if (bs != null) {
      final int[] ints = new int[numInts];
      for (int idx = 0; idx < numInts; ++idx) {
        ints[idx] = bytes != null ? readInt(bytes, offset) : readInt(
            memAddr + offset);
        offset += Integer.SIZE / 8;
      }
      return ints;
    }
    return null;
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      final byte[] bytes) {
    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, bytes, this.positionMap[index],
        this.columns[index]);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      final byte[] bytes, final boolean truncateSpaces) {
    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, bytes, this.positionMap[index],
        this.columns[index], truncateSpaces);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      @Unretained final OffHeapRow bytes) {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], this.columns[index]);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs bytes) {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], this.columns[index]);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      @Unretained final OffHeapByteSource bytes) {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bytes.getLength();
    final long memAddr = bytes.getUnsafeAddress(0, bytesLen);

    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], this.columns[index]);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen) {
    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], this.columns[index]);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final boolean truncateSpaces) {
    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], this.columns[index], truncateSpaces);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int logicalPosition,
      final byte[] bytes, final ColumnDescriptor cd) {
    final int index = logicalPosition - 1;
    return getOffsetAndWidth(index, bytes, this.positionMap[index], cd);
  }

  /**
   * method that extracts column offset & width.
   */
  public final long getOffsetAndWidth(final int index,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final ColumnDescriptor cd) {
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen,
        this.positionMap[index], cd);
  }

  /**
   * Utility method to get the offset and width of a column in given byte array.
   *
   * @return returns a long containing the offset and column width
   */
  final long getOffsetAndWidth(final int index, final byte[] bytes,
      final int offsetFromMap, final ColumnDescriptor cd) {
    return getOffsetAndWidth(index, bytes, offsetFromMap, cd, false);
  }

  /**
   * Utility method to get the offset and width of a column in given byte array.
   *
   * @return returns a long containing the offset and column width
   */
  final long getOffsetAndWidth(final int index, final byte[] bytes,
      final int offsetFromMap, final ColumnDescriptor cd,
      final boolean truncateSpaces) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getOffsetAndWidth: byte[] getting column "
                + "at position " + (index + 1) + ", cd: " + cd);
      }
    }

    // check that the version should match
    int columnWidth;
    if (bytes != null) {
      if (offsetFromMap >= this.nVersionBytes) {
        // fixed width, offsetFromMap is offset to data
        if (!cd.isLob) {
          columnWidth = cd.fixedWidth;
          assert columnWidth > 0: "unexpected columnWidth=" + columnWidth
              + " for " + cd;
          return (((long) offsetFromMap) << Integer.SIZE) | columnWidth;
        }
        else {
          return bytes.length;
        }
      } else if (offsetFromMap < 0) {
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int bytesLen = bytes.length;
        final int offsetOffset = bytesLen + offsetFromMap;
        final int offset = readVarDataOffset(bytes, offsetOffset);
        if (offset >= this.nVersionBytes) {
          columnWidth = getVarColumnWidth(bytes, bytesLen, offsetOffset, offset);
          // check for removal of trailing spaces
          if (truncateSpaces && shouldTrimTrailingSpaces(cd)) {
            columnWidth = trimTrailingSpaces(bytes, offset, columnWidth);
          }
          return (((long) offset) << Integer.SIZE) | columnWidth;
        } else if (!isVarDataOffsetDefaultToken(offset)) {
          // null value case
          return OFFSET_AND_WIDTH_IS_NULL;
        } else {
          return OFFSET_AND_WIDTH_IS_DEFAULT;
        }
      } else if (cd.columnDefault != null) {
        return OFFSET_AND_WIDTH_IS_DEFAULT;
      }
    }
    return OFFSET_AND_WIDTH_IS_NULL;
  }

  private long getVarOffsetAndWidth(final byte[] bytes,
      final int offsetFromMap, final ColumnDescriptor cd) {
    // var width, offsetFromMap is negative offset of offset from end
    // of byte array
    if (offsetFromMap < 0) {
      final int bytesLen = bytes.length;
      final int offsetOffset = bytesLen + offsetFromMap;
      final int offset = readVarDataOffset(bytes, offsetOffset);
      if (offset >= this.nVersionBytes) {
        int columnWidth = getVarColumnWidth(bytes, bytesLen,
            offsetOffset, offset);
        return (((long)offset) << Integer.SIZE) | columnWidth;
      } else if (!isVarDataOffsetDefaultToken(offset)) {
        // null value case
        return OFFSET_AND_WIDTH_IS_NULL;
      } else {
        return OFFSET_AND_WIDTH_IS_DEFAULT;
      }
    } else if (cd.columnDefault == null) {
      return OFFSET_AND_WIDTH_IS_NULL;
    } else {
      return OFFSET_AND_WIDTH_IS_DEFAULT;
    }
  }

  /**
   * Utility method to get the offset and width of a column in given byte array.
   *
   * @return returns a long containing the offset and column width
   */
  final long getOffsetAndWidth(final int index, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final int offsetFromMap,
      final ColumnDescriptor cd) {
    return getOffsetAndWidth(index, unsafe, memAddr, bytesLen, offsetFromMap,
        cd, false);
  }

  /**
   * Utility method to get the offset and width of a column in given byte array.
   *
   * @return returns a long containing the offset and column width
   */
  final long getOffsetAndWidth(final int index, final UnsafeWrapper unsafe,
      final long memAddr, final int bytesLen, final int offsetFromMap,
      final ColumnDescriptor cd, final boolean truncateSpaces) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceRowFormatter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ROW_FORMATTER,
            "RowFormatter#getOffsetAndWidth: byte[] getting column "
                + "at position " + (index + 1) + ", cd: " + cd);
      }
    }

    // check that the version should match
    int columnWidth;
    if (memAddr != 0) {
      if (offsetFromMap >= this.nVersionBytes) {
        // fixed width, offsetFromMap is offset to data
        if (!cd.isLob) {
          columnWidth = cd.fixedWidth;
          assert columnWidth > 0 : "unexpected columnWidth=" + columnWidth;
          return (((long) offsetFromMap) << Integer.SIZE) | columnWidth;
        } else {
          return bytesLen;
        }
      } else if (offsetFromMap < 0) {
        // var width, offsetFromMap is negative offset of offset from end
        // of byte array
        final int offsetOffset = bytesLen + offsetFromMap;
        final int offset = readVarDataOffset(unsafe, memAddr, offsetOffset);
        if (offset >= this.nVersionBytes) {
          columnWidth = getVarColumnWidth(unsafe, memAddr, bytesLen,
              offsetOffset, offset);
          // check for removal of trailing spaces
          if (truncateSpaces && shouldTrimTrailingSpaces(cd)) {
            columnWidth = trimTrailingSpaces(memAddr, offset, columnWidth);
          }
          return (((long) offset) << Integer.SIZE) | columnWidth;
        } else if (!isVarDataOffsetDefaultToken(offset)) {
          // null value case
          return OFFSET_AND_WIDTH_IS_NULL;
        } else {
          return OFFSET_AND_WIDTH_IS_DEFAULT;
        }
      } else if (cd.columnDefault != null) {
        return OFFSET_AND_WIDTH_IS_DEFAULT;
      }
    }
    return OFFSET_AND_WIDTH_IS_NULL;
  }

  // overrides for equality and hashCode to be able to use as map keys

  @Override
  public final int hashCode() {
    int hash = 0;
    final int numColumns = getNumColumns();
    hash = ResolverUtils.addIntToHash(numColumns, hash);
    hash = ResolverUtils.addIntToHash(this.numLobs, hash);
    hash = ResolverUtils.addIntToHash(this.numVarWidthColumns, hash);
    hash = ResolverUtils.addIntToHash(this.offsetBytes, hash);
    hash = ResolverUtils.addIntToHash(this.varDataOffset, hash);
    for (int index = 0; index < numColumns; ++index) {
      hash = ResolverUtils.addIntToHash(this.positionMap[index], hash);
      hash = ResolverUtils.addIntToHash(this.columns[index].getColumnName()
          .hashCode(), hash);
    }
    return hash;
  }

  @Override
  public final boolean equals(final Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof RowFormatter) {
      final RowFormatter other = (RowFormatter) o;
      // compare the position map first
      final int numColumns = getNumColumns();
      if (numColumns == other.getNumColumns() && this.numLobs == other.numLobs
          && this.schemaVersion == other.schemaVersion
          && this.numVarWidthColumns == other.numVarWidthColumns
          && this.offsetBytes == other.offsetBytes
          && this.varDataOffset == other.varDataOffset) {
        for (int index = 0; index < numColumns; ++index) {
          if (this.positionMap[index] != other.positionMap[index]) {
            return false;
          }
          if (!this.columns[index].getColumnName().equals(
              other.columns[index].getColumnName())) {
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }

  public final boolean equals(final RowFormatter other, final int numColumns) {
    if (other == this) {
      return true;
    }
    // compare the position map first
    for (int index = 0; index < numColumns; ++index) {
      if (this.positionMap[index] != other.positionMap[index]) {
        return false;
      }
      if (!this.columns[index].getColumnName().equals(
          other.columns[index].getColumnName())) {
        return false;
      }
    }
    return true;
  }
}
