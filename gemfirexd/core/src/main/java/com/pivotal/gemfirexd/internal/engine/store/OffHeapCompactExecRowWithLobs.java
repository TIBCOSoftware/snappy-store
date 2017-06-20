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
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.util.Calendar;
import java.util.TreeSet;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import org.apache.spark.unsafe.types.UTF8String;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE;

/**
 * A compact implementation of Row that contains one or more LOBs (i.e. BLOBs or
 * CLOBs or SQLXMLs).
 * 
 * A row is stored in a GemFire Region using the byte[][] emitted from an
 * instance of this class. An instance of this class is used as a substitute for
 * DataValueDescriptor[] so that expansion is deferred.
 * 
 * An instance of this class can represent an entire row of the table or a
 * partial row.
 * 
 * The row structure does not store type information or preserve logical column
 * ordering so additional type information is required at construction time in
 * the form of a ColumnDescriptorList.
 * 
 * @see com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow
 * @see com.pivotal.gemfirexd.internal.engine.store.RowFormatter
 * 
 * @author Eric Zoerner
 */
public final class OffHeapCompactExecRowWithLobs extends AbstractCompactExecRow {

  private static final long serialVersionUID = 2591346199546303027L;

  /**
   * The row data encoded in an array of byte arrays. The format of the bytes is
   * completely delegated to RowFormatter. (But essentially: the first byte
   * array is the non-LOB fields, and each subsequent byte array is a single
   * LOB)
   * 
   * If null, then represents a row of all null values.
   */
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE)
  private Object source;

  ////////// Constructors //////////

  /** only to be used for deserialization */
  public OffHeapCompactExecRowWithLobs() {
  }

  /**
   * Construct a OffHeapCompactExecRowWithLobs with the given RowFormatter and
   * with all null values.
   */
  OffHeapCompactExecRowWithLobs(final RowFormatter rf) {
    super(rf);
    this.source = null; // treated as all null valued fields
  }

  /**
   * Construct a OffHeapCompactExecRowWithLobs given a DataValueDescriptor[] and
   * types. The dvds are assumed to be in the logical ordering.
   * 
   * @param dvds
   *          the DataValueDescriptor[] form of the row, or null to default to
   *          all null values
   * @param rf
   *          the RowFormatter for this row
   */
  OffHeapCompactExecRowWithLobs(final DataValueDescriptor[] dvds,
      final RowFormatter rf) throws StandardException {
    super(rf);
    if (dvds != null) {
      this.source = rf.generateByteArrays(dvds);
    }
    else {
      this.source = null; // treated as all null valued fields
    }
  }

  /**
   * Construct a OffHeapCompactExecRowWithLobs given the storage byte[][] and
   * given RowFormatter.
   * 
   * @param rf
   *          the RowFormatter for this row
   */
  OffHeapCompactExecRowWithLobs(
      @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE) Object bytes,
      final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || rf.hasLobs():
      "use a OffHeapCompactExecRow instead";
  }

  OffHeapCompactExecRowWithLobs(byte[][] bytes, final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || rf.hasLobs():
      "use a OffHeapCompactExecRow instead";
  }

  OffHeapCompactExecRowWithLobs(
      @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE) OffHeapByteSource bytes,
      final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || rf.hasLobs():
      "use a OffHeapCompactExecRow instead";
  }

  /**
   * Construct a OffHeapCompactExecRowWithLobs given the storage byte[][],
   * RowFormatter and initial set of DVDs to cache.
   */
  OffHeapCompactExecRowWithLobs(final byte[][] source,
      final RowFormatter rf, final DataValueDescriptor[] row, final int rowLen,
      final boolean doClone) {
    super(rf, row, rowLen, doClone);
    this.source = source;
    assert rf.isTableFormatter() || rf.hasLobs():
      "use a OffHeapCompactExecRow instead";
  }

  /**
   * Construct a OffHeapCompactExecRowWithLobs given the storage byte[][],
   * RowFormatter and initial set of DVDs to cache.
   */
  OffHeapCompactExecRowWithLobs(
      @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE) OffHeapByteSource bytes,
      final RowFormatter rf, final DataValueDescriptor[] row, final int rowLen,
      final boolean doClone) {
    super(rf, row, rowLen, doClone);
    this.source = bytes;
    assert rf.isTableFormatter() || rf.hasLobs():
      "use a OffHeapCompactExecRow instead";
  }

  ////////// OffHeapCompactExecRowWithLobs specific methods //////////

  /**
   * Return the row as a byte[][], or null if this row is full of null values.
   */
  @Override
  public final byte[][] getRowByteArrays() {
    return getRowByteArraysIfPresent();
  }

  @Override
  protected final byte[][] getRowByteArraysIfPresent() {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return ((OffHeapRowWithLobs)source).getRowByteArrays();
      }
      else if (cls == byte[][].class) {
        return (byte[][])source;
      }
      else {
        return this.formatter
            .createByteArraysWithDefaultLobs(((OffHeapRow)source).getRowBytes());
      }
    }
    else {
      return null;
    }
  }

  @Override
  public final byte[] getRowBytes(final int logicalPosition) {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return getRowBytes(logicalPosition, (OffHeapRowWithLobs)source);
      }
      else if (cls == byte[][].class) {
        return getRowBytes(logicalPosition, (byte[][])source);
      }
      else {
        return getRowBytes(logicalPosition, (OffHeapRow)source);
      }
    }
    else {
      return null;
    }
  }

  @Override
  public final boolean hasByteArrays() {
    return true;
  }

  /**
   * Get the raw value of the row
   */
  @Override
  public final Object getRawRowValue(final boolean doClone) {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return ((OffHeapRowWithLobs)source).getRowByteArrays();
      }
      else if (cls == byte[][].class) {
        if (doClone) {
          final byte[][] sourceBytes = (byte[][])source;
          byte[][] newBytes = new byte[sourceBytes.length][];
          int i = 0;
          for (byte[] row : sourceBytes) {
            newBytes[i] = new byte[row.length];
            System.arraycopy(row, 0, newBytes[i], 0, row.length);
          }
          return newBytes;
        }
        else {
          return source;
        }
      }
      else {
        return this.formatter
            .createByteArraysWithDefaultLobs(((OffHeapRow)source).getRowBytes());
      }
    }
    else {
      return null;
    }
  }

  @Override
  public UTF8String getAsUTF8String(int index) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsUTF8String(index,
            (OffHeapRowWithLobs)source);
      } else if (cls == byte[][].class) {
        return this.formatter.getAsUTF8String(index, (byte[][])source);
      } else {
        return this.formatter.getAsUTF8String(index, (OffHeapRow)source);
      }
    } else {
      return null;
    }
  }

  @Override
  protected String getString(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsString(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsString(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter
            .getAsString(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsString(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected Object getObject(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsObject(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsObject(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter
            .getAsObject(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsObject(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected boolean getBoolean(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsBoolean(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsBoolean(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter
            .getAsBoolean(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsBoolean(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected byte getByte(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsByte(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsByte(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsByte(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsByte(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected short getShort(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsShort(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsShort(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsShort(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsShort(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected int getInt(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsInt(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsInt(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsInt(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsInt(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected long getLong(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsLong(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsLong(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsLong(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsLong(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected float getFloat(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsFloat(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsFloat(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsFloat(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsFloat(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected double getDouble(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsDouble(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsDouble(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter
            .getAsDouble(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsDouble(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected byte[] getBytes(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsBytes(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsBytes(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsBytes(position, (OffHeapRow)source, wasNull);
      }
    }
    else {
      return this.formatter.getAsBytes(position, (byte[])null, wasNull);
    }
  }

  @Override
  protected BigDecimal getBigDecimal(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsBigDecimal(position,
            (OffHeapRowWithLobs)source, wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsBigDecimal(position, (byte[][])source,
            wasNull);
      }
      else {
        return this.formatter.getAsBigDecimal(position, (OffHeapRow)source,
            wasNull);
      }
    }
    else {
      return this.formatter.getAsBigDecimal(position, (byte[])null, wasNull);
    }
  }

  @Override
  public long getAsDateMillis(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsDateMillis(index,
            (OffHeapRowWithLobs)source, cal, wasNull);
      } else if (cls == byte[][].class) {
        return this.formatter.getAsDateMillis(index, (byte[][])source,
            cal, wasNull);
      } else {
        return this.formatter.getAsDateMillis(index, (OffHeapRow)source,
            cal, wasNull);
      }
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0L;
    }
  }

  @Override
  protected java.sql.Date getDate(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsDate(position, (OffHeapRowWithLobs)source,
            cal, wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsDate(position, (byte[][])source, cal,
            wasNull);
      }
      else {
        return this.formatter.getAsDate(position, (OffHeapRow)source, cal,
            wasNull);
      }
    }
    else {
      return this.formatter.getAsDate(position, (byte[])null, cal, wasNull);
    }
  }

  @Override
  protected java.sql.Time getTime(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsTime(position, (OffHeapRowWithLobs)source,
            cal, wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsTime(position, (byte[][])source, cal,
            wasNull);
      }
      else {
        return this.formatter.getAsTime(position, (OffHeapRow)source, cal,
            wasNull);
      }
    }
    else {
      return this.formatter.getAsTime(position, (byte[])null, cal, wasNull);
    }
  }

  @Override
  public long getAsTimestampMicros(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsTimestampMicros(index,
            (OffHeapRowWithLobs)source, cal, wasNull);
      } else if (cls == byte[][].class) {
        return this.formatter.getAsTimestampMicros(index, (byte[][])source,
            cal, wasNull);
      } else {
        return this.formatter.getAsTimestampMicros(index, (OffHeapRow)source,
            cal, wasNull);
      }
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0L;
    }
  }

  @Override
  protected java.sql.Timestamp getTimestamp(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsTimestamp(position,
            (OffHeapRowWithLobs)source, cal, wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsTimestamp(position, (byte[][])source, cal,
            wasNull);
      }
      else {
        return this.formatter.getAsTimestamp(position, (OffHeapRow)source, cal,
            wasNull);
      }
    }
    else {
      return this.formatter
          .getAsTimestamp(position, (byte[])null, cal, wasNull);
    }
  }

  @Override
  public final Blob getAsBlob(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsBlob(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsBlob(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsBlob(position, (OffHeapRow)source, wasNull);
      }
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  @Override
  public final Clob getAsClob(int position, ResultWasNull wasNull)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getAsClob(position, (OffHeapRowWithLobs)source,
            wasNull);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getAsClob(position, (byte[][])source, wasNull);
      }
      else {
        return this.formatter.getAsClob(position, (OffHeapRow)source, wasNull);
      }
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  ////////// Abstract methods from AbstractCompactExecRow //////////

  /**
   * Get a DataValueDescriptor in a Row by ordinal position (1-based).
   * 
   * @param position
   *          The ordinal position of the column.
   * 
   * @exception StandardException
   *              Thrown on failure.
   * @return The DataValueDescriptor, null if no such column exists
   */
  @Override
  protected final DataValueDescriptor basicGetColumn(int position)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getColumn(position, (OffHeapRowWithLobs)source);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getColumn(position, (byte[][])source);
      }
      else {
        return this.formatter.getColumn(position, (OffHeapRow)source);
      }
    }
    else {
      return this.formatter.getColumn(position, (byte[])null);
    }
  }

  /**
   * Set DataValueDescriptors in a Row.
   * 
   * @param columns
   *          which columns from values to set, or null if all the values should
   *          be set.
   * @param values
   *          a sparse array of the values to set
   */
  @Override
  protected final void basicSetColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException {
    if (values.length > 0) {
      final Object source = this.source;
      if (source != null) {
        final Class<?> cls = source.getClass();
        if (cls == OffHeapRowWithLobs.class) {
          this.source = this.formatter.setColumns(columns, values,
              (OffHeapRowWithLobs)source, null, this.formatter, false);
        }
        else if (cls == byte[][].class) {
          this.source = this.formatter.setColumns(columns, values,
              (byte[][])source, null, this.formatter);
        }
        else {
          this.source = this.formatter.setColumns(columns, values, null,
              (OffHeapRow)source, this.formatter, false);
        }
      }
      else {
        this.source = this.formatter.setColumns(columns, values,
            (byte[][])null, null, this.formatter);
      }
    }
    else {
      assert this.formatter.getNumColumns() == 0: "if none of the values are "
          + "set such a condition can happen when DTD is also nothing";
      // e.g. select count(*) from TABLE_XXX
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void basicSetColumn(int columnIndex, DataValueDescriptor value)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        this.source = this.formatter.setColumn(columnIndex, value,
            (OffHeapRowWithLobs)source, null, this.formatter);
      }
      else if (cls == byte[][].class) {
        this.source = this.formatter.setColumn(columnIndex, value,
            (byte[][])source, this.formatter);
      }
      else {
        this.source = this.formatter.setColumn(columnIndex, value, null,
            (OffHeapRow)source, this.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumn(columnIndex, value,
          (byte[][])null, this.formatter);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetCompactColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException {
    this.source = this.formatter.setByteArrayCompactColumns(columns, values);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(int nCols, DataValueDescriptor[] values)
      throws StandardException {
    if (values.length > 0) {
      final Object source = this.source;
      if (source != null) {
        final Class<?> cls = source.getClass();
        if (cls == OffHeapRowWithLobs.class) {
          this.source = this.formatter.setColumns(nCols, values,
              (OffHeapRowWithLobs)source, null);
        }
        else if (cls == byte[][].class) {
          this.source = this.formatter.setColumns(nCols, values,
              (byte[][])source);
        }
        else {
          this.source = this.formatter.setColumns(nCols, values, null,
              (OffHeapRow)source);
        }
      }
      else {
        this.source = this.formatter.setColumns(nCols, values, (byte[][])null);
      }
    }
    else {
      assert this.formatter.getNumColumns() == 0: "if none of the values are "
          + "set such a condition can happen when DTD is also nothing";
      // e.g. select count(*) from TABLE_XXX
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(final FormatableBitSet columns,
      final AbstractCompactExecRow srcRow, final int[] baseColumnMap)
      throws StandardException {
    final Object fromSource = srcRow.getBaseByteSource();
    if (fromSource != null) {
      final Class<?> sclass = fromSource.getClass();
      if (sclass == OffHeapRowWithLobs.class) {
        this.source = this.formatter.setColumns(columns,
            (OffHeapRowWithLobs)fromSource, null, srcRow.formatter,
            baseColumnMap);
      }
      else if (sclass == byte[][].class) {
        this.source = this.formatter.setColumns(columns, (byte[][])fromSource,
            null, srcRow.formatter, baseColumnMap);
      }
      else if (sclass == OffHeapRow.class) {
        this.source = this.formatter.setColumns(columns, null,
            (OffHeapRow)fromSource, srcRow.formatter, baseColumnMap);
      }
      else {
        this.source = this.formatter.setColumns(columns,
            srcRow.getRowByteArraysIfPresent(), srcRow.getRowBytes(),
            srcRow.formatter, baseColumnMap);
      }
    }
    else {
      this.source = this.formatter.setColumns(columns, (byte[][])null, null,
          srcRow.formatter, baseColumnMap);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(int[] columns, boolean zeroBased,
      AbstractCompactExecRow srcRow) throws StandardException {
    final Object fromSource = srcRow.getBaseByteSource();
    if (fromSource != null) {
      final Class<?> sclass = fromSource.getClass();
      if (sclass == OffHeapRowWithLobs.class) {
        this.source = this.formatter.setColumns(columns, zeroBased,
            (OffHeapRowWithLobs)fromSource, null, srcRow.formatter);
      }
      else if (sclass == byte[][].class) {
        this.source = this.formatter.setColumns(columns, zeroBased,
            (byte[][])fromSource, null, srcRow.formatter);
      }
      else if (sclass == OffHeapRow.class) {
        this.source = this.formatter.setColumns(columns, zeroBased, null,
            (OffHeapRow)fromSource, srcRow.formatter);
      }
      else {
        this.source = this.formatter.setColumns(columns, zeroBased,
            srcRow.getRowByteArraysIfPresent(), srcRow.getRowBytes(),
            srcRow.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumns(columns, zeroBased,
          (byte[][])null, null, srcRow.formatter);
    }
  }

  /**
   * Set first n-columns from given ExecRow.
   * 
   * @param nCols
   *          number of columns from the start of ExecRow to be copied
   * @param srcRow
   *          the source row from which to copy the columns
   */
  @Override
  protected final void basicSetColumns(int nCols, AbstractCompactExecRow srcRow)
      throws StandardException {
    final Object fromSource = srcRow.getBaseByteSource();
    if (fromSource != null) {
      final Class<?> sclass = fromSource.getClass();
      if (sclass == OffHeapRowWithLobs.class) {
        this.source = this.formatter.setColumns(nCols,
            (OffHeapRowWithLobs)fromSource, null, srcRow.formatter);
      }
      else if (sclass == byte[][].class) {
        this.source = this.formatter.setColumns(nCols, (byte[][])fromSource,
            null, srcRow.formatter);
      }
      else if (sclass == OffHeapRow.class) {
        this.source = this.formatter.setColumns(nCols, null,
            (OffHeapRow)fromSource, srcRow.formatter);
      }
      else {
        this.source = this.formatter.setColumns(nCols,
            srcRow.getRowByteArraysIfPresent(), srcRow.getRowBytes(),
            srcRow.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumns(nCols, (byte[][])null, null,
          srcRow.formatter);
    }
  }

  /**
   * Reset all the <code>DataValueDescriptor</code>s in the row array to (SQL)
   * null values. This method may reuse (and therefore modify) the objects
   * currently contained in the row array.
   */
  @Override
  protected final void basicResetRowArray() {
    this.source = null;
  }

  /**
   * Return the array of objects that the store needs.
   */
  @Override
  protected final DataValueDescriptor[] basicGetRowArray() {
    try {
      final Object source = this.source;
      if (source != null) {
        final Class<?> cls = source.getClass();
        if (cls == OffHeapRowWithLobs.class) {
          return this.formatter.getAllColumns((OffHeapRowWithLobs)source);
        }
        else if (cls == byte[][].class) {
          return this.formatter.getAllColumns((byte[][])source);
        }
        else {
          return this.formatter.getAllColumns((OffHeapRow)source);
        }
      }
      else {
        return this.formatter.getAllColumns((byte[][])null);
      }
    } catch (StandardException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "OffHeapCompactExecRowWithLobs#getRowArray: unexpected exception", e);
    }
  }

  @Override
  protected final void basicSetRowArray(final ExecRow otherRow) {
    if (otherRow instanceof AbstractCompactExecRow) {
      basicSetRowArray((AbstractCompactExecRow)otherRow);
    }
    else {
      throw new UnsupportedOperationException(GfxdConstants.NOT_YET_IMPLEMENTED);
    }
  }

  @Override
  protected final void basicSetRowArray(final AbstractCompactExecRow otherRow) {
    final RowFormatter otherFormatter = otherRow.formatter;
    if (otherRow.hasByteArrays()) {
      this.source = otherRow.getBaseByteSource();
    }
    else if (this.formatter.container == otherFormatter.container) {
      // can happen for the case of ALTER TABLE
      final Object fromSource = otherRow.getBaseByteSource();
      if (fromSource instanceof byte[]) {
        this.source = otherFormatter
            .createByteArraysWithDefaultLobs((byte[])fromSource);
      }
      else {
        this.source = fromSource;
      }
    }
    else {
      throw new UnsupportedOperationException(
          "OffHeapCompactExecRowWithLobs does not support byte[]");
    }
    if (this.formatter != otherFormatter) {
      this.formatter = otherFormatter;
    }
  }

  @Override
  protected void basicSetRowArray(byte[] rowArray, RowFormatter formatter) {
    if (this.formatter.container == formatter.container) {
      // this can be happen due to ALTER TABLE
      basicSetRowArray(formatter.createByteArraysWithDefaultLobs(rowArray),
          formatter);
    }
    else {
      throw new UnsupportedOperationException(
          "OffHeapCompactExecRowWithLobs does not support byte[]");
    }
  }

  @Override
  protected final void basicSetRowArray(final byte[][] rowArray,
      final RowFormatter formatter) {
    this.source = rowArray;
    if (this.formatter != formatter) {
      this.formatter = formatter;
    }
  }

  @Override
  protected void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, boolean zeroBased) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        final OffHeapRowWithLobs ohLobRow = (OffHeapRowWithLobs)source;
        final int bytesLen = ohLobRow.getLength();
        final long memAddr = ohLobRow.getUnsafeAddress(0, bytesLen);
        super.basicSetDVDValues(dvds, srcColumns, zeroBased,
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, null,
            ohLobRow);
      }
      else if (cls == byte[][].class) {
        super.basicSetDVDValues(dvds, srcColumns, zeroBased, (byte[][])source);
      }
      else {
        final OffHeapRow ohRow = (OffHeapRow)source;
        final int bytesLen = ohRow.getLength();
        final long memAddr = ohRow.getUnsafeAddress(0, bytesLen);
        super.basicSetDVDValues(dvds, srcColumns, zeroBased,
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, ohRow,
            null);
      }
    }
    else {
      for (DataValueDescriptor dvd : dvds) {
        if (dvd != null) {
          dvd.restoreToNull();
        }
      }
    }
  }

  @Override
  protected final byte[] getRowBytes() {
    final Object source = this.source;
    // return the first row as byte[]
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return ((OffHeapRowWithLobs)source).getRowBytes();
      }
      else if (cls == byte[][].class) {
        return ((byte[][])source)[0];
      }
      else {
        return ((OffHeapRow)source).getRowBytes();
      }
    }
    else {
      return null;
    }
  }

  protected final int numLobsFromValue() {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return ((OffHeapRowWithLobs)source).readNumLobsColumns(false);
      }
      else if (cls == byte[][].class) {
        return ((byte[][])source).length - 1;
      }
      else {
        return 0;
      }
    }
    else {
      return 0;
    }
  }

  ////////// ExecRow methods //////////

  public final void setRowArrayClone(final ExecRow otherRow,
      final TreeSet<RegionAndKey> allKeys) {
    if (otherRow instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow otherCRow = (AbstractCompactExecRow)otherRow;
      final Object byteSource = otherCRow.getBaseByteSource();
      final RowFormatter otherFormatter = otherCRow.formatter;
      if (byteSource != null) {
        final Class<?> cls = byteSource.getClass();
        if (cls == OffHeapRowWithLobs.class) {
          // TODO: PERF: avoid deserializing full row and instead clone
          // off-heap byte source
          // [sumedh] Asif, why we clone only for Offheap case but not
          // otherwise?
          basicSetRowArray(((OffHeapRowWithLobs)byteSource).getRowByteArrays(),
              otherFormatter);
        }
        else if (cls == byte[][].class) {
          this.source = byteSource;
          if (this.formatter != otherFormatter) {
            this.formatter = otherFormatter;
          }
        }
        else {
          basicSetRowArray(
              otherFormatter.createByteArraysWithDefaultLobs(((OffHeapRow)byteSource)
                  .getRowBytes()), otherFormatter);
        }
      }
    }
    else {
      setRowArray(otherRow.getRowArray());
    }
    this.setOfKeys = allKeys;
  }

  /**
   * Clone the Row and its contents.
   * 
   * @return Row A clone of the Row and its contents.
   */
  @Override
  public final OffHeapCompactExecRowWithLobs getClone() {
    // no need to call shallowClone(false) since all it does is return false
    final OffHeapCompactExecRowWithLobs row = new OffHeapCompactExecRowWithLobs(
        this.source, this.formatter);
    row.setOfKeys = this.setOfKeys;
    return row;
  }

  @Override
  public final OffHeapCompactExecRowWithLobs getShallowClone() {
    return getClone();
  }

  /**
   * Get a new row with the same columns type as this one, containing nulls.
   * 
   */
  @Override
  public final OffHeapCompactExecRowWithLobs getNewNullRow() {
    return new OffHeapCompactExecRowWithLobs(this.formatter);
  }

  @Override
  public final int compare(final ExecRow row, final int logicalPosition,
      final long thisOffsetWidth, final boolean nullsOrderedLow)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        final Object v = this.formatter.getColumnAsByteSource(
            (OffHeapRowWithLobs)source, logicalPosition);
        if (v == source) {
          return compare(row, (OffHeapRowWithLobs)v, logicalPosition,
              thisOffsetWidth, nullsOrderedLow);
        }
        else if (v instanceof OffHeapRow) {
          return compare(row, (OffHeapRow)v, logicalPosition, thisOffsetWidth,
              nullsOrderedLow);
        }
        else {
          return compare(row, (byte[])v, logicalPosition, thisOffsetWidth,
              nullsOrderedLow);
        }
      }
      else if (cls == byte[][].class) {
        return compare(row, getRowBytes(logicalPosition, (byte[][])source),
            logicalPosition, thisOffsetWidth, nullsOrderedLow);
      }
      else {
        final ColumnDescriptor cd = this.formatter
            .getColumnDescriptor(logicalPosition - 1);
        if (!cd.isLob) {
          return compare(row, (OffHeapRow)source, logicalPosition,
              thisOffsetWidth, nullsOrderedLow);
        }
        else {
          return compare(row, cd.columnDefaultBytes, logicalPosition,
              thisOffsetWidth, nullsOrderedLow);
        }
      }
    }
    else {
      return compare(row, (byte[])null, logicalPosition, thisOffsetWidth,
          nullsOrderedLow);
    }
  }

  @Override
  public int compare(final ExecRow row, final int logicalPosition,
      boolean nullsOrderedLow) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        final OffHeapRowWithLobs thisRow = (OffHeapRowWithLobs)source;
        final Object v = this.formatter.getColumnAsByteSource(thisRow,
            logicalPosition);
        if (v == thisRow) {
          return compare(row, thisRow, logicalPosition,
              this.formatter.getOffsetAndWidth(logicalPosition, thisRow),
              nullsOrderedLow);
        }
        else if (v instanceof OffHeapRow) {
          final OffHeapRow bytes = (OffHeapRow)v;
          return compare(row, bytes, logicalPosition,
              this.formatter.getOffsetAndWidth(logicalPosition, bytes),
              nullsOrderedLow);
        }
        else {
          final byte[] bytes = (byte[])v;
          return compare(row, bytes, logicalPosition,
              this.formatter.getOffsetAndWidth(logicalPosition, bytes),
              nullsOrderedLow);
        }
      }
      else if (cls == byte[][].class) {
        final byte[] bytes = getRowBytes(logicalPosition, (byte[][])source);
        return compare(row, bytes, logicalPosition,
            this.formatter.getOffsetAndWidth(logicalPosition, bytes),
            nullsOrderedLow);
      }
      else {
        final ColumnDescriptor cd = this.formatter
            .getColumnDescriptor(logicalPosition - 1);
        if (!cd.isLob) {
          final OffHeapRow bytes = (OffHeapRow)source;
          return compare(row, bytes, logicalPosition,
              this.formatter.getOffsetAndWidth(logicalPosition, bytes),
              nullsOrderedLow);
        }
        else {
          final byte[] bytes = cd.columnDefaultBytes;
          return compare(row, bytes, logicalPosition,
              this.formatter.getOffsetAndWidth(logicalPosition, bytes),
              nullsOrderedLow);
        }
      }
    }
    else {
      return compare(row, (byte[])null, logicalPosition,
          RowFormatter.OFFSET_AND_WIDTH_IS_NULL, nullsOrderedLow);
    }
  }

  @Override
  public final int computeHashCode(final int position, int hash) {
    final Object source = this.source;
    final RowFormatter rf = this.formatter;
    if (source != null) {
      final Class<?> cls = source.getClass();
      @Unretained
      final OffHeapByteSource bs;
      if (cls == OffHeapRowWithLobs.class) {
        @Unretained
        final OffHeapRowWithLobs byteArrays = (OffHeapRowWithLobs)source;
        final int index = position - 1;
        final ColumnDescriptor cd = rf.columns[index];
        if (!cd.isLob) {
          final int bytesLen = byteArrays.getLength();
          final long memAddr = byteArrays.getUnsafeAddress(0, bytesLen);
          return rf.computeHashCode(position,
              UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, hash);
        }
        else {
          final int offsetFromMap = rf.positionMap[index];
          final Object lob = offsetFromMap != 0 ? byteArrays
              .getGfxdByteSource(offsetFromMap) : cd.columnDefaultBytes;
          if (lob == null || lob instanceof byte[]) {
            return rf.computeHashCode(position, (byte[])lob, hash);
          }
          else {
            bs = (OffHeapByteSource)lob;
          }
        }
      }
      else if (cls == byte[][].class) {
        final byte[][] byteArrays = (byte[][])source;
        final int index = position - 1;
        if (!rf.columns[index].isLob) {
          return rf.computeHashCode(position, byteArrays[0], hash);
        }
        else {
          return rf.computeHashCode(position, rf.getLob(byteArrays, position),
              hash);
        }
      }
      else {
        bs = (OffHeapByteSource)source;
      }
      final int bytesLen = bs.getLength();
      final long memAddr = bs.getUnsafeAddress(0, bytesLen);
      return rf.computeHashCode(position, UnsafeMemoryChunk.getUnsafeWrapper(),
          memAddr, bytesLen, hash);
    }
    else {
      return ResolverUtils.addByteToBucketHash((byte)0, hash,
          rf.getType(position).getTypeId().getTypeFormatId());
    }
  }

  @Override
  public long isNull(final int logicalPosition) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      final RowFormatter rf = this.formatter;
      final ColumnDescriptor cd = rf.getColumnDescriptor(logicalPosition - 1);
      if (cls == OffHeapRowWithLobs.class) {
        if (!cd.isLob) {
          return rf.getOffsetAndWidth(logicalPosition,
              (OffHeapRowWithLobs)source);
        }
        else {
          final int offsetFromMap = rf.positionMap[logicalPosition - 1];
          if (offsetFromMap > 0) {
            return ((OffHeapRowWithLobs)source)
                .getLobDataSizeLength(offsetFromMap);
          }
          else {
            return cd.columnDefaultBytes == null
                ? RowFormatter.OFFSET_AND_WIDTH_IS_NULL
                : RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
          }
        }
      }
      else if (cls == byte[][].class) {
        return rf.getOffsetAndWidth(logicalPosition,
            getRowBytes(logicalPosition, (byte[][])source));
      }
      else {
        if (!cd.isLob) {
          return rf.getOffsetAndWidth(logicalPosition, (OffHeapRow)source);
        }
        else {
          return cd.columnDefaultBytes == null
              ? RowFormatter.OFFSET_AND_WIDTH_IS_NULL
              : RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
        }
      }
    }
    else {
      return RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
  }

  /**
   * Overridden to avoid giving the toString() for the LOB columns that may be
   * too large and unmanagable.
   */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getSimpleName());
    final Object source = this.source;
    if (this.formatter != null) {
      sb.append('(');
      final int rowLen = this.formatter.getNumColumns();
      try {
        for (int position = 1; position <= rowLen; position++) {
          if (position != 1) {
            sb.append(',');
          }
          final ColumnDescriptor cd = this.formatter
              .getColumnDescriptor(position - 1);
          if (cd.isLob) {
            sb.append("columnType=").append(cd.getType()).append(';');
            byte[] lob = null;
            if (source != null) {
              final Class<?> cls = source.getClass();
              if (cls == OffHeapRowWithLobs.class) {
                lob = this.formatter.getLob((OffHeapRowWithLobs)source,
                    position);
              }
              else if (cls == byte[][].class) {
                lob = this.formatter.getLob((byte[][])source, position);
              }
            }
            lobToString(lob, sb);
          }
          else {
            sb.append(getColumn(position));
          }
        }
      } catch (StandardException e) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "OffHeapCompactExecRowWithLobs#toString: unexpected exception", e);
      }
      sb.append(')');
    }
    else {
      sb.append("(NULL formatter) rawValue {");
      final byte[] rowBytes = getRowBytes();
      ArrayUtils.objectStringNonRecursive(rowBytes, sb);
      final int len = numLobsFromValue();
      for (int index = 1; index <= len; index++) {
        sb.append(',');
        byte[] lob = null;
        if (source != null) {
          final Class<?> cls = source.getClass();
          if (cls == OffHeapRowWithLobs.class) {
            lob = ((OffHeapRowWithLobs)source).getGfxdBytes(index);
          }
          else if (cls == byte[][].class) {
            lob = ((byte[][])source)[index];
          }
        }
        lobToString(lob, sb);
      }
      sb.append('}');
    }
    return sb.toString();
  }

  private static void lobToString(final byte[] column, final StringBuilder sb) {
    if (column != null) {
      sb.append("length=").append(column.length);
      sb.append(";hash=").append(ResolverUtils.addBytesToHash(column, 0));
    }
    else {
      sb.append("(NULL)");
    }
  }

  ////////// Serialization related methods //////////

  /**
   * @see GfxdDataSerializable#getGfxdID()
   */
  @Override
  public byte getGfxdID() {
    throw new UnsupportedOperationException(
        "OffHeapCompactExecRowWithLobs does not support getGfxdID()");
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    throw new UnsupportedOperationException(
        "OffHeapCompactExecRowWithLobs does not support toData");
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.source = DataSerializer.readArrayOfByteArrays(in);
  }

  @Override
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE)
  public Object getByteSource() {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return source;
      }
      else if (cls == byte[][].class) {
        return ((byte[][])source)[0];
      }
      else {
        return source;
      }
    }
    else {
      return null;
    }
  }

  @Override
  @Unretained
  // Unretained LOB ByteSource
  public Object getByteSource(int logicalPosition) {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRowWithLobs.class) {
        return this.formatter.getColumnAsByteSource((OffHeapRowWithLobs)source,
            logicalPosition);
      }
      else if (cls == byte[][].class) {
        return this.formatter.getColumnAsByteSource((byte[][])source,
            logicalPosition);
      }
      else {
        return source;
      }
    }
    else {
      return null;
    }
  }

  @Override
  void basicSetByteSource(
      @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE) final Object source) {
    this.source = source;
  }

  @Override
  @Released(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE)
  public void releaseByteSource() {
    if (OffHeapHelper.release(this.source)) {
      // no longer can safely refer to source
      this.source = null;
    }
  }

  @Override
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_WITH_LOBS_SOURCE)
  public Object getBaseByteSource() {
    return this.source;
  }
}
