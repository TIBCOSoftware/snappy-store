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
import java.util.Calendar;
import java.util.TreeSet;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import org.apache.spark.unsafe.types.UTF8String;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.OFFHEAP_COMPACT_EXEC_ROW_SOURCE;

/**
 * A compact implementation of Row used to minimize the footprint of a row and
 * to defer its expansion.
 * 
 * A row is stored in a GemFire Region using the bytes emitted from an instance
 * of this class. An instance of this class is used as a substitute for
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
public final class OffHeapCompactExecRow extends AbstractCompactExecRow {

  private static final long serialVersionUID = -8506169648261047433L;

  /**
   * The row data encoded in bytes. The format of the bytes is completely
   * delegated to RowFormatter.
   * 
   * If null, then represents a row of all null values.
   */
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_SOURCE)
  private Object source;

  ////////// Constructors //////////

  /** only to be used for deserialization */
  public OffHeapCompactExecRow() {
  }

  /**
   * Construct a OffHeapCompactExecRow with the given RowFormatter, and with all
   * null values.
   */
  OffHeapCompactExecRow(final RowFormatter rf) {
    super(rf);
    this.source = null; // treated as all null valued fields
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
  }

  /**
   * Construct a OffHeapCompactExecRow given a DataValueDescriptor[] and
   * RowFormatter. The dvds are assumed to be in the logical ordering.
   * 
   * @param dvds
   *          the DataValueDescriptor[] form of the row, or null to default to
   *          all null values
   * @param rf
   *          the RowFormatter for this row
   */
  OffHeapCompactExecRow(final DataValueDescriptor[] dvds, final RowFormatter rf)
      throws StandardException {
    super(rf);
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
    if (dvds != null) {
      this.source = rf.generateBytes(dvds);
    }
    else {
      this.source = null; // treated as all null valued fields
    }
  }

  /**
   * Construct a OffHeapCompactExecRow given the storage byte[] and
   * RowFormatter.
   * 
   * @param rf
   *          the RowFormatter for this row
   */
  OffHeapCompactExecRow(@Unretained final Object bytes,
      final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
  }

  OffHeapCompactExecRow(final byte[] bytes, final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
  }

  OffHeapCompactExecRow(@Unretained final OffHeapRow bytes,
      final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
  }

  /**
   * Construct a OffHeapCompactExecRow given the storage byte[], RowFormatter
   * and initial set of DVDs to cache.
   */
  OffHeapCompactExecRow(@Unretained final Object bytes,
      final RowFormatter rf, final DataValueDescriptor[] row, final int rowLen,
      final boolean doClone) {
    super(rf, row, rowLen, doClone);

    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a OffHeapCompactExecRowWithLobs instead";
  }

  ////////// OffHeapCompactExecRow specific methods //////////

  /**
   * Return the row as a byte[], or null if this row is full of null values.
   */
  @Override
  public final byte[] getRowBytes() {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        return ((OffHeapRow)source).getRowBytes();
      }
      else if (cls == byte[].class) {
        return (byte[])source;
      }
      else {
        return ((OffHeapRowWithLobs)source).getRowBytes();
      }
    }
    else {
      return null;
    }
  }

  @Override
  public final byte[] getRowBytes(int logicalPosition) {
    return this.getRowBytes();
  }

  @Override
  public final boolean hasByteArrays() {
    return false;
  }

  /**
   * Get the raw value of the row
   */
  @Override
  public final Object getRawRowValue(final boolean doClone) {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        return ((OffHeapRow)source).getRowBytes();
      }
      else if (cls == byte[].class) {
        byte[] sourceBytes = (byte[])source;
        if (doClone) {
          byte[] newBytes = new byte[sourceBytes.length];
          System.arraycopy(sourceBytes, 0, newBytes, 0, sourceBytes.length);
          return newBytes;
        }
        else {
          return sourceBytes;
        }
      }
      else {
        return ((OffHeapRowWithLobs)source).getRowBytes();
      }
    }
    else {
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getColumn(position, (OffHeapRow)source);
      }
      else if (cls == byte[].class) {
        return this.formatter.getColumn(position, (byte[])source);
      }
      else {
        return this.formatter.getColumn(position, (OffHeapRowWithLobs)source);
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
        if (cls == OffHeapRow.class) {
          this.source = this.formatter.setColumns(columns, values,
              (OffHeapRow)source, this.formatter);
        }
        else if (cls == byte[].class) {
          this.source = this.formatter.setColumns(columns, values,
              (byte[])source, this.formatter);
        }
        else {
          this.source = this.formatter.setColumns(columns, values,
              (OffHeapByteSource)source, this.formatter);
        }
      }
      else {
        this.source = this.formatter.setColumns(columns, values, (byte[])null,
            this.formatter);
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
      if (cls == OffHeapRow.class) {
        this.source = this.formatter.setColumn(columnIndex, value,
            (OffHeapRow)source, this.formatter);
      }
      else if (cls == byte[].class) {
        this.source = this.formatter.setColumn(columnIndex, value,
            (byte[])source, this.formatter);
      }
      else {
        this.source = this.formatter.setColumn(columnIndex, value,
            (OffHeapByteSource)source, this.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumn(columnIndex, value, (byte[])null,
          this.formatter);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetCompactColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException {
    this.source = this.formatter.setByteCompactColumns(columns, values);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(final int nCols,
      final DataValueDescriptor[] values) throws StandardException {
    if (values.length > 0) {
      final Object source = this.source;
      if (source != null) {
        final Class<?> cls = source.getClass();
        if (cls == OffHeapRow.class) {
          this.source = this.formatter.setColumns(nCols, values,
              (OffHeapRow)source);
        }
        else if (cls == byte[].class) {
          this.source = this.formatter
              .setColumns(nCols, values, (byte[])source);
        }
        else {
          this.source = this.formatter.setColumns(nCols, values,
              (OffHeapByteSource)source);
        }
      }
      else {
        this.source = this.formatter.setColumns(nCols, values, (byte[])null);
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
    final Object fromSource = srcRow.getByteSource();
    if (fromSource != null) {
      final Class<?> cls = fromSource.getClass();
      if (cls == OffHeapRow.class) {
        this.source = this.formatter.setColumns(columns,
            (OffHeapRow)fromSource, srcRow.formatter, baseColumnMap);
      }
      else if (cls == byte[].class) {
        this.source = this.formatter.setColumns(columns, (byte[])fromSource,
            srcRow.formatter, baseColumnMap);
      }
      else {
        this.source = formatter.setColumns(columns,
            (OffHeapByteSource)fromSource, srcRow.formatter, baseColumnMap);
      }
    }
    else {
      this.source = this.formatter.setColumns(columns, (byte[])null,
          srcRow.formatter, baseColumnMap);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(int[] columns, boolean zeroBased,
      AbstractCompactExecRow srcRow) throws StandardException {
    final Object fromSource = srcRow.getByteSource();
    if (fromSource != null) {
      final Class<?> cls = fromSource.getClass();
      if (cls == OffHeapRow.class) {
        this.source = this.formatter.setColumns(columns, zeroBased,
            (OffHeapRow)fromSource, srcRow.formatter);
      }
      else if (cls == byte[].class) {
        this.source = this.formatter.setColumns(columns, zeroBased,
            (byte[])fromSource, srcRow.formatter);
      }
      else {
        this.source = this.formatter.setColumns(columns, zeroBased,
            (OffHeapByteSource)fromSource, srcRow.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumns(columns, zeroBased, (byte[])null,
          srcRow.formatter);
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
    final Object fromSource = srcRow.getByteSource();
    if (fromSource != null) {
      final Class<?> cls = fromSource.getClass();
      if (cls == OffHeapRow.class) {
        this.source = this.formatter.setColumns(nCols, (OffHeapRow)fromSource,
            srcRow.formatter);
      }
      else if (cls == byte[].class) {
        this.source = this.formatter.setColumns(nCols, (byte[])fromSource,
            srcRow.formatter);
      }
      else {
        this.source = this.formatter.setColumns(nCols,
            (OffHeapByteSource)fromSource, srcRow.formatter);
      }
    }
    else {
      this.source = this.formatter.setColumns(nCols, (byte[])null,
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
    if (this.setOfKeys != null) {
      this.setOfKeys.clear();
    }
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
        if (cls == OffHeapRow.class) {
          return this.formatter.getAllColumns((OffHeapRow)source);
        }
        else if (cls == byte[].class) {
          return this.formatter.getAllColumns((byte[])source);
        }
        else {
          return this.formatter.getAllColumns((OffHeapRowWithLobs)source);
        }
      }
      else {
        return this.formatter.getAllColumns((byte[])null);
      }
    } catch (final StandardException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "OffHeapCompactExecRow#getRowArray: unexpected exception", e);
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
    if (this.formatter != otherRow.formatter) {
      this.formatter = otherRow.formatter;
    }
    // no need to call shallowClone(false) since it always returns this.
    this.source = otherRow.getByteSource();
  }

  /**
   * Set the array of objects
   */
  @Override
  protected final void basicSetRowArray(final byte[] rowArray,
      final RowFormatter formatter) {
    this.source = rowArray;
    if (this.formatter != formatter) {
      this.formatter = formatter;
    }
  }

  @Override
  protected void basicSetRowArray(byte[][] rowArray, RowFormatter formatter) {
    if (this.formatter.container == formatter.container) {
      // this can be happen due to ALTER TABLE
      basicSetRowArray(rowArray[0], formatter);
    }
    else {
      throw new UnsupportedOperationException(
          "OffHeapCompactExecRow does not support byte[][]");
    }
  }

  @Override
  protected void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, boolean zeroBased) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        final OffHeapRow ohRow = (OffHeapRow)source;
        final int bytesLen = ohRow.getLength();
        final long memAddr = ohRow.getUnsafeAddress(0, bytesLen);
        super.basicSetDVDValues(dvds, srcColumns, zeroBased,
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, ohRow,
            null);
      }
      else if (cls == byte[].class) {
        super.basicSetDVDValues(dvds, srcColumns, zeroBased, (byte[])source);
      }
      else {
        final OffHeapRowWithLobs ohLobRow = (OffHeapRowWithLobs)source;
        final int bytesLen = ohLobRow.getLength();
        final long memAddr = ohLobRow.getUnsafeAddress(0, bytesLen);
        super.basicSetDVDValues(dvds, srcColumns, zeroBased,
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, null,
            ohLobRow);
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
  protected final byte[][] getRowByteArrays() {
    throw new UnsupportedOperationException(
        "OffHeapCompactExecRow does not support byte[][]");
  }

  @Override
  protected final byte[][] getRowByteArraysIfPresent() {
    return null;
  }

  @Override
  public UTF8String getAsUTF8String(int index) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsUTF8String(index, (OffHeapRow)source);
      } else if (cls == byte[].class) {
        return this.formatter.getAsUTF8String(index, (byte[])source);
      } else {
        return this.formatter.getAsUTF8String(index,
            (OffHeapRowWithLobs)source);
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
      if (cls == OffHeapRow.class) {
        return this.formatter
            .getAsString(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsString(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsString(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter
            .getAsObject(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsObject(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsObject(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsBoolean(position, (OffHeapRow)source,
            wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsBoolean(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsBoolean(position,
            (OffHeapRowWithLobs)source, wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsByte(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsByte(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsByte(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsShort(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsShort(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsShort(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsInt(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsInt(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsInt(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsLong(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsLong(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsLong(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsFloat(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsFloat(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsFloat(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter
            .getAsDouble(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsDouble(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsDouble(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsBytes(position, (OffHeapRow)source, wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsBytes(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsBytes(position, (OffHeapRowWithLobs)source,
            wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsBigDecimal(position, (OffHeapRow)source,
            wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter
            .getAsBigDecimal(position, (byte[])source, wasNull);
      }
      else {
        return this.formatter.getAsBigDecimal(position,
            (OffHeapRowWithLobs)source, wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsDateMillis(index, (OffHeapRow)source, cal,
            wasNull);
      } else if (cls == byte[].class) {
        return this.formatter.getAsDateMillis(index, (byte[])source,
            cal, wasNull);
      } else {
        return this.formatter.getAsDateMillis(index, (OffHeapRowWithLobs)source,
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsDate(position, (OffHeapRow)source, cal,
            wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsDate(position, (byte[])source, cal, wasNull);
      }
      else {
        return this.formatter.getAsDate(position, (OffHeapRowWithLobs)source,
            cal, wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsTime(position, (OffHeapRow)source, cal,
            wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsTime(position, (byte[])source, cal, wasNull);
      }
      else {
        return this.formatter.getAsTime(position, (OffHeapRowWithLobs)source,
            cal, wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsTimestampMicros(index, (OffHeapRow)source, cal,
            wasNull);
      } else if (cls == byte[].class) {
        return this.formatter.getAsTimestampMicros(index, (byte[])source, cal,
            wasNull);
      } else {
        return this.formatter.getAsTimestampMicros(index,
            (OffHeapRowWithLobs)source, cal, wasNull);
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
      if (cls == OffHeapRow.class) {
        return this.formatter.getAsTimestamp(position, (OffHeapRow)source, cal,
            wasNull);
      }
      else if (cls == byte[].class) {
        return this.formatter.getAsTimestamp(position, (byte[])source, cal,
            wasNull);
      }
      else {
        return this.formatter.getAsTimestamp(position,
            (OffHeapRowWithLobs)source, cal, wasNull);
      }
    }
    else {
      return this.formatter
          .getAsTimestamp(position, (byte[])null, cal, wasNull);
    }
  }

  ////////// ExecRow methods //////////

  public final void setRowArrayClone(final ExecRow otherRow,
      final TreeSet<RegionAndKey> allKeys) {
    if (otherRow instanceof AbstractCompactExecRow) {
      basicSetRowArray((AbstractCompactExecRow)otherRow);
      clearCachedRow();
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
  public final OffHeapCompactExecRow getClone() {
    // no need to call shallowClone(false) since it always returns this
    // bytes (this.source) are immutable and the formatter does not need to be
    // cloned
    final OffHeapCompactExecRow row = new OffHeapCompactExecRow(this.source,
        this.formatter);
    row.setOfKeys = this.setOfKeys;
    return row;
  }

  @Override
  public final OffHeapCompactExecRow getShallowClone() {
    return getClone();
  }

  /**
   * Get a new row with the same columns type as this one, containing nulls.
   * 
   */
  @Override
  public final OffHeapCompactExecRow getNewNullRow() {
    return new OffHeapCompactExecRow(this.formatter);
  }

  @Override
  public final int compare(final ExecRow row, final int logicalPosition,
      final long thisOffsetWidth, final boolean nullsOrderedLow)
      throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        return compare(row, (OffHeapRow)source, logicalPosition,
            thisOffsetWidth, nullsOrderedLow);
      }
      else if (cls == byte[].class) {
        return compare(row, (byte[])source, logicalPosition, thisOffsetWidth,
            nullsOrderedLow);
      }
      else {
        return compare(row, (OffHeapRowWithLobs)source, logicalPosition,
            thisOffsetWidth, nullsOrderedLow);
      }
    }
    else {
      return compare(row, (byte[])null, logicalPosition, thisOffsetWidth,
          nullsOrderedLow);
    }
  }

  @Override
  public final int compare(final ExecRow row, final int logicalPosition,
      final boolean nullsOrderedLow) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        @Unretained
        final OffHeapRow bytes = (OffHeapRow)source;
        return super.compare(row, bytes, logicalPosition,
            this.formatter.getOffsetAndWidth(logicalPosition, bytes),
            nullsOrderedLow);
      }
      else if (cls == byte[].class) {
        final byte[] bytes = (byte[])source;
        return super.compare(row, bytes, logicalPosition,
            this.formatter.getOffsetAndWidth(logicalPosition, bytes),
            nullsOrderedLow);
      }
      else {
        @Unretained final OffHeapRowWithLobs bytes = (OffHeapRowWithLobs)source;
        // this should never lead to LOB comparison
        assert !this.formatter.getColumnDescriptor(logicalPosition - 1).isLob:
          this.formatter.getColumnDescriptor(logicalPosition - 1).toString();
        return super.compare(row, bytes, logicalPosition,
            this.formatter.getOffsetAndWidth(logicalPosition, bytes),
            nullsOrderedLow);
      }
    }
    else {
      return super.compare(row, (byte[])null, logicalPosition,
          RowFormatter.OFFSET_AND_WIDTH_IS_NULL, nullsOrderedLow);
    }
  }

  @Override
  public final int computeHashCode(final int position, int hash) {
    final Object source = this.source;
    if (source != null) {
      if (source instanceof byte[]) {
        return this.formatter.computeHashCode(position, (byte[])source, hash);
      }
      else {
        @Unretained
        final OffHeapByteSource bs = (OffHeapByteSource)source;
        final int bytesLen = bs.getLength();
        final long memAddr = bs.getUnsafeAddress(0, bytesLen);
        return this.formatter.computeHashCode(position,
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, bytesLen, hash);
      }
    }
    else {
      return ResolverUtils.addByteToBucketHash((byte)0, hash, this.formatter
          .getType(position).getTypeId().getTypeFormatId());
    }
  }

  @Override
  public long isNull(final int logicalPosition) throws StandardException {
    final Object source = this.source;
    if (source != null) {
      final Class<?> cls = source.getClass();
      if (cls == OffHeapRow.class) {
        return this.formatter.getOffsetAndWidth(logicalPosition,
            (OffHeapRow)source);
      }
      else if (cls == byte[].class) {
        return this.formatter.getOffsetAndWidth(logicalPosition,
            (byte[])source);
      }
      else {
        // this should never lead to LOB column read
        assert !this.formatter.getColumnDescriptor(logicalPosition - 1).isLob:
          this.formatter.getColumnDescriptor(logicalPosition - 1).toString();
        return this.formatter.getOffsetAndWidth(logicalPosition,
            (OffHeapRowWithLobs)source);
      }
    }
    else {
      return RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
    }
  }

  /*
  @Override
  public final int compareStringBytes(final byte[] sb1,
      final DataValueDescriptor dvd, final int logicalPosition)
      throws StandardException {
    return compareStringBytes(sb1, dvd, this.bytes, logicalPosition);
  }
  */

  ////////// Serialization related methods //////////

  /**
   * @see GfxdDataSerializable#getGfxdID()
   */
  @Override
  public byte getGfxdID() {
    throw new UnsupportedOperationException(
        "OffHeapCompactExecRow does not support getGfxdID()");
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    throw new UnsupportedOperationException(
        "OffHeapCompactExecRow does not support toData");
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.source = DataSerializer.readByteArray(in);
  }

  @Override
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_SOURCE)
  public Object getByteSource() {
    return this.source;
  }

  @Override
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_SOURCE)
  public Object getByteSource(int offset) {
    return this.source;
  }

  @Override
  void basicSetByteSource(
      @Unretained(OFFHEAP_COMPACT_EXEC_ROW_SOURCE) Object source) {
    this.source = source;
  }

  @Override
  @Released(OFFHEAP_COMPACT_EXEC_ROW_SOURCE)
  public void releaseByteSource() {
    if (OffHeapHelper.release(this.source)) {
      // no longer can safely refer to source
      this.source = null;
    }
  }

  @Override
  @Unretained(OFFHEAP_COMPACT_EXEC_ROW_SOURCE)
  public Object getBaseByteSource() {
    return this.source;
  }
}
