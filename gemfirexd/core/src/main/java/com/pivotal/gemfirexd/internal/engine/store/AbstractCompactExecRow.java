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

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.TreeSet;

import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * Common behavior for implementations of a compact Row used to minimize the
 * footprint of a row and to defer its expansion.
 * 
 * An instance of this class can represent an entire row of the table or a
 * partial row.
 * 
 * The row structure does not store type information or preserve logical column
 * ordering so additional type information is required at construction time in
 * the form of a ColumnDescriptorList.
 * 
 * Now this class has been extended to cache the DVDs that have been gotten from
 * it so far to avoid repeated deserialization (originally in
 * CompactCachedExecRow* classes). Also allows to initialize with a set of
 * initial DVD[] which is the way used by {@link GfxdIndexManager#onEvent} for
 * an update using the DVD[] from the {@link GemFireContainer.SerializableDelta}
 * .
 * 
 * @see com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow
 * @see com.pivotal.gemfirexd.internal.engine.store.RowFormatter
 * 
 * @author Eric Zoerner
 * @author swale
 */
@SuppressWarnings("serial")
public abstract class AbstractCompactExecRow extends GfxdDataSerializable
    implements ExecRow, Serializable {

  /** Stores the DVDs deserialized so far to avoid multiple deserializations */
  protected DataValueDescriptor[] deserializedCache;

  /** Number of valid DVDs in {@link #deserializedCache} */
  private int deserializedCacheLen;

  /**
   * Field types and logical order of fields. Ideally this object should be
   * shared across multiple instances of AbstractCompactExecRow.
   */
  protected RowFormatter formatter;
  /**
   * Base memory used for storing a byte. Used for computing byte[] memory
   * estimation.
   */
  public static final int BYTE_BASE_MEMORY_USAGE = ClassSize
      .estimateAndCatalogBase(byte.class);

  ////////// Constructors //////////

  /** for deserialization */
  protected AbstractCompactExecRow() {
  }

  /**
   * Construct a AbstractCompactExecRow with the given type descriptors,
   * and with all null field values.
   */
  public AbstractCompactExecRow(final RowFormatter rf) {
    assert rf != null: "RowFormatter must not be null";
    this.formatter = rf;
  }

  public AbstractCompactExecRow(final RowFormatter rf,
      final DataValueDescriptor[] row, final int size, final boolean doClone) {
    this(rf);
    this.deserializedCache = new DataValueDescriptor[rf.getNumColumns()];
    for (int index = 0; index < row.length; index++) {
      if (row[index] != null) {
        if (doClone) {
          this.deserializedCache[index] = row[index].getClone();
        }
        else {
          this.deserializedCache[index] = row[index];
        }
      }
    }
    this.deserializedCacheLen = size;
  }

  ////////// AbstractCompactExecRow specific methods //////////

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
  public final void setColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException {
    basicSetColumns(columns, values);
    setCachedColumns(columns, values);
  }

  /**
   * Set DataValueDescriptors in a Row in a compact way mapping first set bit in
   * <code>columns</code> to column 1, second to column 2 and so on.
   * 
   * @param columns
   *          which columns from values to set; should be non-null
   * @param values
   *          a sparse array of the values to set
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   */
  public final void setCompactColumns(final FormatableBitSet columns,
      final DataValueDescriptor[] values, final int[] baseColumnMap)
      throws StandardException {
    final int nCols = values.length;
    if (nCols > 0) {
      basicSetCompactColumns(columns, values);
      final DataValueDescriptor[] cache = getDeserializedCache();
      for (int colIndex = columns.anySetBit(), index = 0; colIndex != -1
          && colIndex < nCols; colIndex = columns.anySetBit(colIndex),
             index++) {
        if (cache[index] == null) {
          this.deserializedCacheLen++;
        }
        cache[index] = values[colIndex];
        if (baseColumnMap != null) {
          baseColumnMap[index] = colIndex;
        }
      }
    }
    else {
      assert this.formatter.getNumColumns() == 0: "if none of the values are "
          + "set such a condition can happen when DTD is also nothing";
      // e.g. select count(*) from TABLE_XXX
    }
  }

  /**
   * Set DataValueDescriptors in a Row.
   * 
   * @param nCols
   *          the number of columns from start to set
   * @param values
   *          a sparse array of the values to set
   */
  public final void setColumns(int nCols, DataValueDescriptor[] values)
      throws StandardException {
    basicSetColumns(nCols, values);
    setCachedColumns(nCols, values);
  }

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
  public final DataValueDescriptor getColumn(int position)
      throws StandardException {
    final DataValueDescriptor[] cache = getDeserializedCache();
    final DataValueDescriptor dvd = cache[position - 1];
    if (dvd == null) {
      this.deserializedCacheLen++;
      try {
        return (cache[position - 1] = basicGetColumn(position));
      } catch (RuntimeException e) {
        Object byteSource = getByteSource();
        if (byteSource instanceof OffHeapByteSource) {
          throw new RuntimeException("OffHeapByteSource of the corrupted row is"
              + byteSource, e);
        } else {
          throw e;
        }
      }
    }
    return dvd;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final DataValueDescriptor getLastColumn() throws StandardException {
    return getColumn(this.formatter.getNumColumns());
  }

  /**
   * Reset all the <code>DataValueDescriptor</code>s in the row array to (SQL)
   * null values. This method may reuse (and therefore modify) the objects
   * currently contained in the row array.
   */
  @Override
  public final void resetRowArray() {
    basicResetRowArray();
    clearCachedRow();
  }

  protected final void clearCachedRow() {
    if (this.deserializedCache != null) {
      this.deserializedCache = null;
      this.deserializedCacheLen = 0;
    }
  }

  /**
   * Get a clone of the array form of the row that Access expects.
   * 
   * @see ExecRow#getRowArray
   */
  @Override
  public final DataValueDescriptor[] getRowArrayClone() {
    // Row arrays from a AbstractCompactExecRow are always "clones".
    // Cannot cache results since it is supposed to be a clone.
    if (this.deserializedCacheLen == 0) {
      return basicGetRowArray();
    }
    else {
      final DataValueDescriptor[] dvds = new DataValueDescriptor[this.formatter
          .getNumColumns()];
      int index = 0;
      for (DataValueDescriptor dvd : this.deserializedCache) {
        if (dvd == null) {
          try {
            dvds[index] = basicGetColumn(index + 1);
          } catch (StandardException se) {
            throw GemFireXDRuntimeException.newRuntimeException(
                "unexpected exception in AbstractCompactExecRow#"
                    + "getRowArrayClone()", se);
          }
        }
        else {
          dvds[index] = dvd.getClone();
        }
        index++;
      }
      return dvds;
    }
  }

  /**
   * Return the array of objects that the store needs.
   */
  @Override
  public final DataValueDescriptor[] getRowArray() {
    final int rowLen = this.formatter.getNumColumns();
    if ((this.deserializedCacheLen != rowLen) || (rowLen == 0)) {
      final DataValueDescriptor[] cache = getDeserializedCache();
      try {
        for (int index = 0; index < rowLen; index++) {
          if (cache[index] == null) {
            cache[index] = basicGetColumn(index + 1);
          }
        }
      } catch (StandardException e) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "AbstractCompactExecRow#getRowArray: unexpected exception", e);
      }
      this.deserializedCacheLen = rowLen;
      return cache;
    }
    return this.deserializedCache;
  }

  @Override
  public final void setRowArray(ExecRow otherRow) {
    basicSetRowArray(otherRow);
    clearCachedRow();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setValue(int columnIndex, final DataValueDescriptor value)
      throws StandardException {
    basicSetColumn(columnIndex, value);
    DataValueDescriptor cached;
    if (this.deserializedCache != null
        && (cached = this.deserializedCache[columnIndex]) != null) {
      if (value != null && !value.isNull()) {
        cached.setValue(value);
      }
      else {
        cached.restoreToNull();
      }
    }
  }

  @Override
  public final void setValuesInto(final int[] srcColumns, boolean zeroBased,
      final ExecRow targetRow) throws StandardException {
    if (targetRow instanceof AbstractCompactExecRow) {
      ((AbstractCompactExecRow)targetRow).setColumns(srcColumns, zeroBased,
          this);
    }
    else {
      // set column by column from raw bytes
      final DataValueDescriptor[] dvds = targetRow.getRowArray();
      basicSetDVDValues(dvds, srcColumns, zeroBased);
    }
  }

  protected final void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, final boolean zeroBased, final byte[] source)
      throws StandardException {
    final RowFormatter formatter = this.formatter;
    final DataValueDescriptor[] cache = this.deserializedCache;
    final int nsrcCols = srcColumns.length;
    DataValueDescriptor dvd, srcDVD;
    int srcIndex;
    for (int index = 0; index < nsrcCols; index++) {
      dvd = dvds[index];
      srcIndex = srcColumns[index];
      if (!zeroBased) {
        srcIndex--;
      }
      if (srcIndex >= 0) {
        if (dvd != null) {
          if (cache == null || (srcDVD = cache[srcIndex]) == null) {
            formatter.setDVDColumn(dvd, srcIndex, source);
          }
          else {
            dvd.setValue(srcDVD);
          }
        }
        else {
          dvds[index] = formatter.getColumn(srcIndex + 1, source);
        }
      }
    }
  }

  protected final void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, boolean zeroBased, final byte[][] byteArrays)
      throws StandardException {
    final RowFormatter formatter = this.formatter;
    final DataValueDescriptor[] cache = this.deserializedCache;
    final int nsrcCols = srcColumns.length;
    DataValueDescriptor dvd, srcDVD;
    int srcIndex;
    for (int index = 0; index < nsrcCols; index++) {
      dvd = dvds[index];
      srcIndex = srcColumns[index];
      if (!zeroBased) {
        srcIndex--;
      }
      if (srcIndex >= 0) {
        if (dvd != null) {
          if (cache == null || (srcDVD = cache[srcIndex]) == null) {
            formatter.setDVDColumn(dvd, srcIndex,
                getRowBytes(srcIndex + 1, byteArrays));
          }
          else {
            dvd.setValue(srcDVD);
          }
        }
        else {
          dvds[index] = formatter.getColumn(srcIndex + 1, byteArrays);
        }
      }
    }
  }

  protected final void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, final boolean zeroBased,
      final UnsafeWrapper unsafe, final long memAddr, final int bytesLen,
      final OffHeapRow ohRow, final OffHeapRowWithLobs ohLobRow)
      throws StandardException {
    final RowFormatter formatter = this.formatter;
    final DataValueDescriptor[] cache = this.deserializedCache;
    final int nsrcCols = srcColumns.length;
    DataValueDescriptor dvd, srcDVD;
    int srcIndex;
    for (int index = 0; index < nsrcCols; index++) {
      dvd = dvds[index];
      srcIndex = srcColumns[index];
      if (!zeroBased) {
        srcIndex--;
      }
      if (srcIndex >= 0) {
        if (dvd != null) {
          if (cache == null || (srcDVD = cache[srcIndex]) == null) {
            if (ohRow != null) {
              formatter.setDVDColumn(dvd, srcIndex, unsafe, memAddr, bytesLen,
                  ohRow);
            }
            else {
              formatter.setDVDColumn(dvd, srcIndex, unsafe, memAddr, bytesLen,
                  ohLobRow);
            }
          }
          else {
            dvd.setValue(srcDVD);
          }
        }
        else if (ohRow != null) {
          dvds[index] = formatter.getColumn(srcIndex + 1, unsafe, memAddr,
              bytesLen, ohRow);
        }
        else {
          dvds[index] = formatter.getColumn(srcIndex + 1, unsafe, memAddr,
              bytesLen, ohLobRow);
        }
      }
    }
  }

  public final void setRowArray(AbstractCompactExecRow otherRow) {
    basicSetRowArray(otherRow);
    clearCachedRow();
  }

  protected final void setCachedColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) {
    final DataValueDescriptor[] cache = getDeserializedCache();
    if (columns != null) {
      for (int colIndex = columns.anySetBit(); colIndex != -1;
           colIndex = columns.anySetBit(colIndex)) {
        if (cache[colIndex] == null) {
          this.deserializedCacheLen++;
        }
        cache[colIndex] = values[colIndex];
      }
    }
    else {
      for (int colIndex = 0; colIndex < values.length; colIndex++) {
        if (cache[colIndex] == null) {
          this.deserializedCacheLen++;
        }
        cache[colIndex] = values[colIndex];
      }
    }
  }

  protected final void setCachedColumns(int nCols,
      final DataValueDescriptor[] values) {
    final DataValueDescriptor[] cache = getDeserializedCache();
    for (int colIndex = 0; colIndex < nCols; colIndex++) {
      if (cache[colIndex] == null) {
        this.deserializedCacheLen++;
      }
      cache[colIndex] = values[colIndex];
    }
  }

  /**
   * Get the {@link RowFormatter} for this row.
   */
  public final RowFormatter getRowFormatter() {
    return this.formatter;
  }

  /**
   * Set the {@link RowFormatter}. To be used only after deserialization.
   */
  public final void setRowFormatter(final RowFormatter rf) {
    this.formatter = rf;
  }

  /**
   * Set the values in the row using the source row.
   */
  public final void setColumns(FormatableBitSet columns, ExecRow srcRow)
      throws StandardException {
    // optimize for bytes
    if (srcRow instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow srcCompactRow =
        (AbstractCompactExecRow)srcRow;
      if (columns == null) {
        final int srcLen = srcCompactRow.nColumns();
        if (nColumns() == srcLen) {
          basicSetRowArray(srcCompactRow);
          clearCachedRow();
        }
        else {
          basicSetColumns(srcLen, srcCompactRow);
          if (this.deserializedCache != null) {
            for (int index = 0; index < srcLen; ++index) {
              if (this.deserializedCache[index] != null) {
                this.deserializedCache[index] = null;
                this.deserializedCacheLen--;
              }
            }
          }
          
        }
      }
      else {
        basicSetColumns(columns, srcCompactRow, null);
        if (this.deserializedCache != null) {
          for (int index = columns.anySetBit(); index != -1; index = columns
              .anySetBit(index)) {
            if (this.deserializedCache[index] != null) {
              this.deserializedCache[index] = null;
              this.deserializedCacheLen--;
            }
          }
        }
       
      }   

    }
    else {
      setColumns(columns, srcRow.getRowArray());
    }
  }

  /**
   * {@inheritDoc}
   */
  public final void setCompactColumns(final FormatableBitSet columns,
      final ExecRow row, final int[] baseColumnMap,
      final boolean copyColumns) throws StandardException {
    if (!copyColumns && baseColumnMap != null && columns != null) {
      final int nSrcCols = row.nColumns();
      final int nCols = nColumns();
      for (int i = columns.anySetBit(), pos = 0; i != -1 && i < nSrcCols
          && pos < nCols; i = columns.anySetBit(i), pos++) {
        baseColumnMap[pos] = i;
      }
      return;
    }
    // optimize for bytes
    if (row instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow srcCompactRow =
          (AbstractCompactExecRow)row;
      if (columns != null) {
        int nCols = columns.getNumBitsSet();
        // nothing to do if number of columns to set is zero
        if (nCols > 0) {
          basicSetColumns(columns, srcCompactRow, baseColumnMap);
          if (this.deserializedCache != null) {
            int srcNCols = srcCompactRow.nColumns();
            if (srcNCols < nCols) {
              nCols = srcNCols;
            }
            for (int index = 0; index < nCols; index++) {
              if (this.deserializedCache[index] != null) {
                this.deserializedCache[index] = null;
                this.deserializedCacheLen--;
              }
            }
          }
        }
        else {
          assert this.formatter.getNumColumns() == 0: "if none of the values "
              + "are set such a condition can happen when DTD is also nothing";
          // e.g. select count(*) from TABLE_XXX
        }
      }
      else {
        final int srcLen = srcCompactRow.nColumns();
        if (nColumns() == srcLen) {
          basicSetRowArray(srcCompactRow);
          clearCachedRow();
        }
        else {
          basicSetColumns(srcLen, srcCompactRow);
          if (this.deserializedCache != null) {
            for (int index = 0; index < srcLen; index++) {
              if (this.deserializedCache[index] != null) {
                this.deserializedCache[index] = null;
                this.deserializedCacheLen--;
              }
            }
          }
        }
      }
    }
    else if (columns != null) {
      setCompactColumns(columns, row.getRowArray(), baseColumnMap);
    }
    else {
      setColumns(null, row.getRowArray());
    }
  }

  /**
   * Set the values in the row using the source row.
   */
  public final void setColumns(int[] columns, boolean zeroBased, ExecRow srcRow)
      throws StandardException {
    // optimize for bytes
    if (srcRow instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow srcCompactRow =
        (AbstractCompactExecRow)srcRow;
      if (columns == null) {
        final int srcLen = srcCompactRow.nColumns();
        if (nColumns() == srcLen) {
          basicSetRowArray(srcCompactRow);
          clearCachedRow();
        }
        else {
          basicSetColumns(srcLen, srcCompactRow);
          if (this.deserializedCache != null) {
            for (int index = 0; index < srcLen; ++index) {
              if (this.deserializedCache[index] != null) {
                this.deserializedCache[index] = null;
                this.deserializedCacheLen--;
              }
            }
          }
        }
      }
      else {
        basicSetColumns(columns, zeroBased, srcCompactRow);
        if (this.deserializedCache != null) {
          for (int index = 0; index < columns.length; index++) {
            if (this.deserializedCache[index] != null) {
              this.deserializedCache[index] = null;
              this.deserializedCacheLen--;
            }
          }
        }
      }
    }
    else {
      final DataValueDescriptor[] dvds = srcRow.getRowArray();
      if (zeroBased) {
        for (int index = 0; index < columns.length; index++) {
          setColumn(index + 1, dvds[columns[index]]);
        }
      }
      else {
        for (int index = 0; index < columns.length; index++) {
          setColumn(index + 1, dvds[columns[index] - 1]);
        }
      }
    }
  }

  /**
   * Set the values in the row using the source row.
   */
  public final void setColumns(int nCols, ExecRow srcRow)
      throws StandardException {
    // optimize for bytes
    if (srcRow instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow srcCompactRow =
        (AbstractCompactExecRow)srcRow;
      if (nCols == nColumns() && nCols == srcCompactRow.nColumns()) {
        basicSetRowArray(srcCompactRow);
        clearCachedRow();
      }
      else {
        basicSetColumns(nCols, srcCompactRow);
        if (this.deserializedCache != null) {
          for (int index = 0; index < nCols; index++) {
            if (this.deserializedCache[index] != null) {
              this.deserializedCache[index] = null;
              this.deserializedCacheLen--;
            }
          }
        }
      }
    }
    else {
      setColumns(nCols, srcRow.getRowArray());
    }
  }

  public final void setRowArray(final byte[] rowArray,
      final RowFormatter formatter) {
    basicSetRowArray(rowArray, formatter);
    clearCachedRow();
  }

  public final void setRowArray(final byte[][] rowArray,
      final RowFormatter formatter) {
    basicSetRowArray(rowArray, formatter);
    clearCachedRow();
  }

  protected final DataValueDescriptor[] getDeserializedCache() {
    if (this.deserializedCache != null) {
      return this.deserializedCache;
    }
    return (this.deserializedCache = new DataValueDescriptor[this.formatter
        .getNumColumns()]);
  }

  ////////// Abstract methods to be implemented by child classes //////////

  /**
   * Set DataValueDescriptors in a Row.
   * 
   * @param columns
   *          which columns from values to set, or null if all the values should
   *          be set.
   * @param values
   *          a sparse array of the values to set
   */
  protected abstract void basicSetColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException;

  /**
   * Set a single DataValueDescriptor value in a row
   * 
   * @param columnIndex
   *          0-based index of column to set
   * @param value
   *          the value to set at the given column
   */
  protected abstract void basicSetColumn(int columnIndex,
      DataValueDescriptor value) throws StandardException;

  /**
   * Set DataValueDescriptors in a Row in a compact way mapping first set bit in
   * <code>columns</code> to column 1, second to column 2 and so on.
   * 
   * @param columns
   *          which columns from values to set; should be non-null
   * @param values
   *          a sparse array of the values to set
   */
  protected abstract void basicSetCompactColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException;

  /**
   * Set DataValueDescriptors in a Row.
   * 
   * @param nCols
   *          number of columns from start of values to set
   * @param values
   *          a sparse array of the values to set
   */
  protected abstract void basicSetColumns(int nCols,
      DataValueDescriptor[] values) throws StandardException;

  /**
   * Set columns from given ExecRow.
   * 
   * @param columns
   *          which columns from values to set
   * @param srcRow
   *          the source row from which to copy the columns
   * @param baseColumnMap
   *          if non-null then the column mapping from destination index to
   *          source index is stored in this
   */
  protected abstract void basicSetColumns(FormatableBitSet columns,
      AbstractCompactExecRow srcRow, int[] baseColumnMap)
      throws StandardException;

  /**
   * Set columns from given ExecRow.
   * 
   * @param columns
   *          column positions from values to set
   * @param zeroBased
   *          true if the columns array is 0-based positions else the array is
   *          1-based positions
   * @param srcRow
   *          the source row from which to copy the columns
   */
  protected abstract void basicSetColumns(int[] columns, boolean zeroBased,
      AbstractCompactExecRow srcRow) throws StandardException;

  /**
   * Set first n-columns from given ExecRow.
   * 
   * @param nCols
   *          number of columns from the start of ExecRow to be copied
   * @param srcRow
   *          the source row from which to copy the columns
   */
  protected abstract void basicSetColumns(int nCols,
      AbstractCompactExecRow srcRow) throws StandardException;

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
  protected abstract DataValueDescriptor basicGetColumn(int position)
      throws StandardException;

  /**
   * Reset all the <code>DataValueDescriptor</code>s in the row array to (SQL)
   * null values. This method may reuse (and therefore modify) the objects
   * currently contained in the row array.
   */
  protected abstract void basicResetRowArray();

  /**
   * Return the array of objects that the store needs.
   */
  protected abstract DataValueDescriptor[] basicGetRowArray();

  protected abstract void basicSetRowArray(ExecRow otherRow);

  protected abstract void basicSetRowArray(AbstractCompactExecRow otherRow);

  /**
   * Set the array of objects
   */
  protected abstract void basicSetRowArray(byte[] rowArray,
      RowFormatter formatter);
  protected abstract void basicSetRowArray(byte[][] rowArray,
      RowFormatter formatter);

  protected abstract void basicSetDVDValues(DataValueDescriptor[] dvds,
      int[] srcColumns, boolean zeroBased) throws StandardException;

  protected abstract byte[] getRowBytes();
  public abstract byte[] getRowBytes(int logicalPosition);
  protected abstract byte[][] getRowByteArrays();
  protected abstract byte[][] getRowByteArraysIfPresent();

  public abstract boolean hasByteArrays();

  protected final byte[] getRowBytes(final int logicalPosition,
      final byte[][] byteArrays) {
    if (!this.formatter.getColumnDescriptor(logicalPosition - 1).isLob) {
      if (byteArrays != null) {
        return byteArrays[0];
      }
      else {
        return null;
      }
    }
    else {
      return this.formatter.getLob(byteArrays, logicalPosition);
    }
  }

  protected final byte[] getRowBytes(final int logicalPosition,
      @Unretained final OffHeapRowWithLobs byteArrays) {
    if (!this.formatter.getColumnDescriptor(logicalPosition - 1).isLob) {
      return byteArrays.getRowBytes();
    }
    else {
      return this.formatter.getLob(byteArrays, logicalPosition);
    }
  }

  protected final byte[] getRowBytes(final int logicalPosition,
      @Unretained final OffHeapRow bytes) {
    if (!this.formatter.getColumnDescriptor(logicalPosition - 1).isLob) {
      return bytes.getRowBytes();
    }
    else {
      return this.formatter.getLob((OffHeapRowWithLobs)null, logicalPosition);
    }
  }

  @Override
  public final byte[] getRowBytes(RowFormatter formatter) {
    return getRowBytes();
  }

  @Override
  public final byte[][] getRowByteArrays(RowFormatter formatter) {
    return getRowByteArrays();
  }

  /**
   * Get the raw value of the row without cloning since implementations are COW.
   */
  public abstract Object getRawRowValue(boolean doClone);
  
  abstract void basicSetByteSource(Object source);

  ////////// Row methods //////////

  /**
   * Return the number of columns.
   *
   * @return the number of columns
   */
  public final int nColumns() {
    return this.formatter.getNumColumns();
  }

  public final ColumnDescriptor getColumnDescriptor(final int logicalPosition) {
    return this.formatter.columns[logicalPosition - 1];
  }

  /**
   * Set a DataValueDescriptor in a Row by ordinal position (1-based). If
   * setting multiple columns, it is preferable to use the method
   * setColumns(FormatableBitSet columns, DataValueDescriptor[] values).
   * 
   * @param position
   *          The ordinal position of the column.
   */
  public final void setColumn(int position, DataValueDescriptor value) {
    // ???:ezoerner:20081209 not sure we need this to be implemented,
    // we may always just use setColumns instead
    throw new UnsupportedOperationException("Use setColumns instead");
  }

  ////////// ExecRow methods //////////

  /**
   * Clone the Row and its contents.
   * 
   * @return Row A clone of the Row and its contents.
   */
  public abstract AbstractCompactExecRow getClone();

  /**
   * Clone the Row but not its contents.
   * 
   * @return Row A clone of the Row with same contents as in original.
   */
  public abstract AbstractCompactExecRow getShallowClone();

  /**
   * Clone the Row. The cloned row will contain clones of the specified columns
   * and the same object as the original row for the other columns.
   * 
   * @param clonedCols
   *          1-based FormatableBitSet representing the columns to clone.
   * 
   * @return Row A clone of the Row and its contents.
   */
  public final AbstractCompactExecRow getClone(FormatableBitSet clonedCols) {
    throw new UnsupportedOperationException(GfxdConstants.NOT_YET_IMPLEMENTED);
  }

  /**
   * Get a new row with the same columns type as this one, containing nulls.
   * 
   */
  public abstract AbstractCompactExecRow getNewNullRow();

  /**
   * Get a clone of a DataValueDescriptor from an ExecRow.
   *
   * @param position (1 based)
   */
  public final DataValueDescriptor cloneColumn(int position) {
    try {
      final DataValueDescriptor column;
      if (this.deserializedCache == null
          || (column = this.deserializedCache[position - 1]) == null) {
        return basicGetColumn(position);
      }
      else {
        return column.getClone();
      }
    } catch (StandardException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "AbstractCompactExecRow#cloneColumn: unexpected exception", e);
    }
  }

  /**
   * Set the array of objects
   */
  public final void setRowArray(DataValueDescriptor[] rowArray) {
    throw new UnsupportedOperationException(GfxdConstants.NOT_YET_IMPLEMENTED);
  }

  /**
   * Get a new DataValueDescriptor[]
   */
  public final void getNewObjectArray() {
    // ???:ezoerner:20081212 is this supposed to return an array of same
    // size as this row?
    throw new UnsupportedOperationException(GfxdConstants.NOT_YET_IMPLEMENTED);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getSimpleName() + "@"
        + System.identityHashCode(this));
    if (this.formatter != null) {
      ArrayUtils.objectStringNonRecursive(getRowArray(), sb);
    }
    else {
      sb.append("(NULL formatter) rawValue ");
      ArrayUtils.objectStringNonRecursive(getRawRowValue(false), sb);
    }
    return sb.toString();
  }

  //Asif: Methods which would help avoid creation of DataValueDescriptor objects
  public final double getAsDouble(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getDouble(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getDouble();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0.0;
    }
  }

  public final String getAsString(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getString(position, wasNull);
    }
    //if dvd is instance of SQLChar, it may require normalization, see #46933
    final String result = dvd.getString();
    if (result != null) {
      return result;
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  public final Object getAsObject(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getObject(position, wasNull);
    }
    final Object result = dvd.getObject();
    if (result != null) {
      return result;
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  public final boolean getAsBoolean(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getBoolean(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getBoolean();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return false;
    }
  }

  public final byte getAsByte(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getByte(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getByte();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0;
    }
  }

  public final short getAsShort(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getShort(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getShort();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0;
    }
  }

  public final int getAsInt(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getInt(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getInt();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0;
    }
  }

  public final long getAsLong(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getLong(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getLong();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0L;
    }
  }

  public final float getAsFloat(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getFloat(position, wasNull);
    }
    if (!dvd.isNull()) {
      return dvd.getFloat();
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return 0.0f;
    }
  }

  public final BigDecimal getAsBigDecimal(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getBigDecimal(position, wasNull);
    }
    if (!dvd.isNull()) {
      return SQLDecimal.getBigDecimal(dvd);
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  public final byte[] getAsBytes(int position, ResultWasNull wasNull)
      throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getBytes(position, wasNull);
    }
    final byte[] result = dvd.getBytes();
    if (result != null) {
      return result;
    } else {
      if (wasNull != null) wasNull.setWasNull();
      return null;
    }
  }

  public final java.sql.Date getAsDate(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getDate(position, cal, wasNull);
    }
    return dvd.getDate(cal);
  }

  public final java.sql.Time getAsTime(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getTime(position, cal, wasNull);
    }
    return dvd.getTime(cal);
  }

  public final java.sql.Timestamp getAsTimestamp(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    final DataValueDescriptor dvd;
    if (this.deserializedCache == null
        || (dvd = this.deserializedCache[position - 1]) == null) {
      return getTimestamp(position, cal, wasNull);
    }
    return dvd.getTimestamp(cal);
  }

  public Blob getAsBlob(int position, ResultWasNull wasNull)
      throws StandardException {
    final int index = position - 1;
    final ColumnDescriptor cd = this.formatter.getColumnDescriptor(index);
    throw StandardException.newException(
        SQLState.LANG_DATA_TYPE_GET_MISMATCH, "Blob", cd.getType()
            .getFullSQLTypeName(), cd.getColumnName());
  }

  public Clob getAsClob(int position, ResultWasNull wasNull)
      throws StandardException {
    final int index = position - 1;
    final ColumnDescriptor cd = this.formatter.getColumnDescriptor(index);
    throw StandardException.newException(
        SQLState.LANG_DATA_TYPE_GET_MISMATCH, "Clob", cd.getType()
            .getFullSQLTypeName(), cd.getColumnName());
  }

  // TODO: SW: fix to store same full UTF8 format in GemXD like in UTF8String
  public abstract UTF8String getAsUTF8String(int index)
      throws StandardException;
  protected abstract String getString(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract Object getObject(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract boolean getBoolean(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract byte getByte(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract short getShort(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract int getInt(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract long getLong(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract float getFloat(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract double getDouble(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract byte[] getBytes(int position, ResultWasNull wasNull)
      throws StandardException;
  protected abstract BigDecimal getBigDecimal(int position,
      ResultWasNull wasNull) throws StandardException;
  // invoked by generated code of SnappyData
  public abstract long getAsDateMillis(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException;
  protected abstract java.sql.Date getDate(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException;
  protected abstract java.sql.Time getTime(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException;
  // invoked by generated code of SnappyData
  public abstract long getAsTimestampMicros(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException;
  protected abstract java.sql.Timestamp getTimestamp(int position,
      Calendar cal, ResultWasNull wasNull) throws StandardException;

  //Asif

  //sb
  public final long estimateRowSize() throws StandardException {
    long sz = getRawRowSize(getBaseByteSource());

    final TreeSet<RegionAndKey> setOfKeys = this.setOfKeys;
    if (setOfKeys != null && setOfKeys.size() > 0) {
      try {
        for (RegionAndKey rak : setOfKeys) {
          sz += ClassSize.refSize + rak.estimateMemoryUsage();
        }
      } catch (ConcurrentModificationException | NoSuchElementException ignore) {
      }
    }
    return sz;
  }

  public static final long getRawRowSize(final Object row) {
    long sz = 0;
    if (row != null) {
      final Class<?> cls = row.getClass();
      if (cls == byte[].class) {
        // TODO: why is BYTE_BASE_MEMORY_USAGE added to sz?
        sz = ClassSize.estimateArrayOverhead() + BYTE_BASE_MEMORY_USAGE
          + ClassSize.refSize + ((byte[])row).length;
      }
      else if (cls == byte[][].class) {
        // TODO replace the existing sz calculation with this:
//         sz = ClassSize.estimateArrayOverhead() + (ClassSize.refSize * rowArr.length);
//         for (byte[] bytes : rowArr) {
//           if (bytes != null) {
//             sz += ClassSize.estimateArrayOverhead() + bytes.length;
//           }
//         }
        sz = ClassSize.estimateArrayOverhead() + ClassSize.refSize;
        final byte[][] rowArr = (byte[][])row;
        for (byte[] bytes : rowArr) {
          if (bytes != null) {
            sz += bytes.length;
          }
        }
        sz += (BYTE_BASE_MEMORY_USAGE + ClassSize.refSize) * rowArr.length;
      }
      else {
        sz = 8; // the heap reference to the off-heap row consumes 8 bytes (its a primitive long).
        sz += ((OffHeapByteSource)row).getSizeInBytes(); // add in the actual amount of off-heap memory used.
      }
    }
    return sz;
  }
  //sb

  //Neeraj
  protected TreeSet<RegionAndKey> setOfKeys;

  @Override
  public final void addRegionAndKey(final String regionName, final Object key,
      final boolean isReplicated) {
    getOrCreateRegionAndKeyInfo().add(
        new RegionAndKey(regionName, key, isReplicated));
  }

  @Override
  public final void addRegionAndKey(final RegionAndKey rak) {
    if (rak != null) {
      getOrCreateRegionAndKeyInfo().add(rak);
    }
  }

  @Override
  public final void addAllKeys(final TreeSet<RegionAndKey> allKeys) {
    if (allKeys != null) {
      getOrCreateRegionAndKeyInfo().addAll(allKeys);
    }
  }

  protected final TreeSet<RegionAndKey> getOrCreateRegionAndKeyInfo() {
    if (this.setOfKeys != null) {
      return this.setOfKeys;
    }
    return (this.setOfKeys = new TreeSet<RegionAndKey>());
  }

  @Override
  public final TreeSet<RegionAndKey> getAllRegionAndKeyInfo() {
    return this.setOfKeys;
  }

  @Override
  public final void setAllRegionAndKeyInfo(final TreeSet<RegionAndKey> keys) {
    this.setOfKeys = keys;
  }

  @Override
  public final void clearAllRegionAndKeyInfo() {
    this.setOfKeys = null;
  }
  //Neeraj

  ///// byte comparison methods /////

  protected final int compare(final ExecRow row, final byte[] bytes,
      final int logicalPosition, final long thisOffsetWidth,
      final boolean nullsOrderedLow) throws StandardException {

    if (row instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow cRow = (AbstractCompactExecRow)row;
      final RowFormatter rf = cRow.formatter;
      final Object rhsBytes = cRow.getByteSource(logicalPosition);
      if (rhsBytes != null) {
        final Class<?> rhsClass = rhsBytes.getClass();
        if (rhsClass == byte[].class) {
          final byte[] rhs = (byte[])rhsBytes;
          return DataTypeUtilities.compare(bytes, rhs, thisOffsetWidth,
              rf.getOffsetAndWidth(logicalPosition, rhs), nullsOrderedLow, true,
              rf.columns[logicalPosition - 1]);
        }
        else {
          final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
          final long memAddr;
          final int bytesLen;
          final OffHeapByteSource rhs;
          if (rhsClass == OffHeapRow.class) {
            final OffHeapRow rhsBS = (OffHeapRow)rhsBytes;
            bytesLen = rhsBS.getLength();
            memAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhs = rhsBS;
          }
          else {
            final OffHeapRowWithLobs rhsBS = (OffHeapRowWithLobs)rhsBytes;
            bytesLen = rhsBS.getLength();
            memAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhs = rhsBS;
          }
          final long rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition,
              unsafe, memAddr, bytesLen);
          return DataTypeUtilities.compare(unsafe, bytes, 0, null, null,
              memAddr, rhs, thisOffsetWidth, rhsOffsetWidth, nullsOrderedLow,
              true, rf.columns[logicalPosition - 1]);
        }
      }
      else {
        return DataTypeUtilities.compare(bytes, null,
            thisOffsetWidth, RowFormatter.OFFSET_AND_WIDTH_IS_NULL,
            nullsOrderedLow, true, rf.columns[logicalPosition - 1]);
      }
    }
    else {
      // self on right since we want to pass DVD so invert the result
      return -Integer.signum(DataTypeUtilities.compare(
          row.getColumn(logicalPosition), bytes, thisOffsetWidth,
          nullsOrderedLow, true, this.formatter.columns[logicalPosition - 1]));
    }
  }

  protected final int compare(final ExecRow row,
      @Unretained final OffHeapByteSource bytes, final int logicalPosition,
      final long thisOffsetWidth, final boolean nullsOrderedLow)
      throws StandardException {

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final long thisAddr = bytes.getUnsafeAddress();
    if (row instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow cRow = (AbstractCompactExecRow)row;
      final RowFormatter rf = cRow.formatter;
      final Object rhsBytes = cRow.getByteSource(logicalPosition);
      if (rhsBytes != null) {
        final Class<?> rhsClass = rhsBytes.getClass();
        if (rhsClass == byte[].class) {
          final byte[] rhs = (byte[])rhsBytes;
          return DataTypeUtilities.compare(unsafe, null, thisAddr, bytes, rhs,
              0, null, thisOffsetWidth,
              rf.getOffsetAndWidth(logicalPosition, rhs), nullsOrderedLow,
              true, rf.columns[logicalPosition - 1]);
        }
        else {
          final long memAddr;
          final int bytesLen;
          final OffHeapByteSource rhs;
          if (rhsClass == OffHeapRow.class) {
            final OffHeapRow rhsBS = (OffHeapRow)rhsBytes;
            bytesLen = rhsBS.getLength();
            memAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhs = rhsBS;
          }
          else {
            final OffHeapRowWithLobs rhsBS = (OffHeapRowWithLobs)rhsBytes;
            bytesLen = rhsBS.getLength();
            memAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rhs = rhsBS;
          }
          final long rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition,
              unsafe, memAddr, bytesLen);
          return DataTypeUtilities.compare(unsafe, null, thisAddr, bytes, null,
              memAddr, rhs, thisOffsetWidth, rhsOffsetWidth, nullsOrderedLow,
              true, rf.columns[logicalPosition - 1]);
        }
      }
      else {
        return DataTypeUtilities.compare(unsafe, null, thisAddr, bytes, null,
            0, null, thisOffsetWidth, RowFormatter.OFFSET_AND_WIDTH_IS_NULL,
            nullsOrderedLow, true, rf.columns[logicalPosition - 1]);
      }
    }
    else {
      // self on right since we want to pass DVD so invert the result
      return -Integer.signum(DataTypeUtilities.compare(unsafe,
          row.getColumn(logicalPosition), null, thisAddr, bytes,
          thisOffsetWidth, nullsOrderedLow, true,
          this.formatter.columns[logicalPosition - 1]));
    }
  }

  @Unretained
  public abstract Object getByteSource(int offset);

  public void setByteSource(Object source, RowFormatter rf) {
    this.setByteSource(source);
    this.formatter = rf;
  }

  public void setByteSource(Object source) {
    this.clearCachedRow();
    this.basicSetByteSource(source);
  }
}
