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
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import org.apache.spark.unsafe.types.UTF8String;

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
public final class CompactExecRow extends AbstractCompactExecRow {

  private static final long serialVersionUID = -8506169648261047433L;

  /**
   * The row data encoded in bytes. The format of the bytes is completely
   * delegated to RowFormatter.
   * 
   * If null, then represents a row of all null values.
   */
  private byte[] source;

  ////////// Constructors //////////

  /** only to be used for deserialization */
  public CompactExecRow() {
  }

  /**
   * Construct a CompactExecRow with the given RowFormatter, and with all null
   * values.
   */
  CompactExecRow(final RowFormatter rf) {
    super(rf);
    this.source = null; // treated as all null valued fields
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a CompactExecRowWithLobs instead";
  }

  /**
   * Construct a CompactExecRow given a DataValueDescriptor[] and RowFormatter.
   * The dvds are assumed to be in the logical ordering.
   * 
   * @param dvds
   *          the DataValueDescriptor[] form of the row, or null to default to
   *          all null values
   * @param rf
   *          the RowFormatter for this row
   */
  CompactExecRow(final DataValueDescriptor[] dvds, final RowFormatter rf)
      throws StandardException {
    super(rf);
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a CompactExecRowWithLobs instead";
    if (dvds != null) {
      this.source = rf.generateBytes(dvds);
    }
    else {
      this.source = null; // treated as all null valued fields
    }
  }

  CompactExecRow(final byte[] bytes, final RowFormatter rf) {
    super(rf);
    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a CompactExecRowWithLobs instead";
  }

  /**
   * Construct a CompactExecRow given the storage byte[], RowFormatter and
   * initial set of DVDs to cache.
   */
  CompactExecRow(final byte[] bytes, final RowFormatter rf,
      final DataValueDescriptor[] row, final int rowLen, final boolean doClone) {
    super(rf, row, rowLen, doClone);

    this.source = bytes;
    assert rf.isTableFormatter() || !rf.hasLobs():
      "use a CompactExecRowWithLobs instead";
  }

  ////////// CompactExecRow specific methods //////////

  /**
   * Return the row as a byte[], or null if this row is full of null values.
   */
  @Override
  public final byte[] getRowBytes() {
    return this.source;
  }

  @Override
  public final byte[] getRowBytes(int logicalPosition) {
    return this.source;
  }

  @Override
  public final boolean hasByteArrays() {
    return false;
  }

  /**
   * Get the raw value of the row
   */
  @Override
  public final byte[] getRawRowValue(final boolean doClone) {
    if (doClone) {
      final byte[] source = this.source;
      if (source != null) {
        byte[] newBytes = new byte[source.length];
        System.arraycopy(source, 0, newBytes, 0, source.length);
        return newBytes;
      }
      else {
        return null;
      }
    }
    else {
      return this.source;
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
    return this.formatter.getColumn(position, this.source);
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
      this.source = this.formatter.setColumns(columns, values, this.source,
          this.formatter);
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
    this.source = this.formatter.setColumn(columnIndex, value, this.source,
        this.formatter);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetCompactColumns(FormatableBitSet columns,
      DataValueDescriptor[] values) throws StandardException {
    // this.bytes = this.formatter.setByteCompactColumns(columns, values);
    this.source = this.formatter.setByteCompactColumns(columns, values);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(final int nCols,
      final DataValueDescriptor[] values) throws StandardException {
    if (values.length > 0) {
      this.source = this.formatter.setColumns(nCols, values, this.source);
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
    this.source = this.formatter.setColumns(columns, srcRow.getRowBytes(),
        srcRow.formatter, baseColumnMap);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void basicSetColumns(int[] columns, boolean zeroBased,
      AbstractCompactExecRow srcRow) throws StandardException {
    this.source = this.formatter.setColumns(columns, zeroBased,
        srcRow.getRowBytes(), srcRow.formatter);
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
    this.source = this.formatter.setColumns(nCols, srcRow.getRowBytes(),
        srcRow.formatter);
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
   * Return the array of objects that the st ore needs.
   */
  @Override
  protected final DataValueDescriptor[] basicGetRowArray() {
    try {
      return this.formatter.getAllColumns(this.source);
    } catch (final StandardException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "CompactExecRow#getRowArray: unexpected exception", e);
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
    assert otherRow instanceof CompactExecRow:
      "unexpected otherRow (" + otherRow.getClass() + "): " + otherRow;
    // no need to call shallowClone(false) since it always returns this.
    this.source = otherRow.getRowBytes();
  }

  /** Set the array of objects */
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
          "CompactExecRow does not support byte[][]");
    }
  }

  @Override
  protected void basicSetDVDValues(final DataValueDescriptor[] dvds,
      final int[] srcColumns, boolean zeroBased) throws StandardException {
    super.basicSetDVDValues(dvds, srcColumns, zeroBased, this.source);
  }

  @Override
  protected final byte[][] getRowByteArrays() {
    throw new UnsupportedOperationException(
        "CompactExecRow does not support byte[][]");
  }

  @Override
  protected final byte[][] getRowByteArraysIfPresent() {
    return null;
  }

  @Override
  public UTF8String getAsUTF8String(int index) throws StandardException {
    return this.formatter.getAsUTF8String(index, this.source);
  }

  @Override
  protected String getString(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsString(position, this.source, wasNull);
  }

  @Override
  protected Object getObject(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsObject(position, this.source, wasNull);
  }

  @Override
  protected boolean getBoolean(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsBoolean(position, this.source, wasNull);
  }

  @Override
  protected byte getByte(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsByte(position, this.source, wasNull);
  }

  @Override
  protected short getShort(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsShort(position, this.source, wasNull);
  }

  @Override
  protected int getInt(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsInt(position, this.source, wasNull);
  }

  @Override
  protected long getLong(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsLong(position, this.source, wasNull);
  }

  @Override
  protected float getFloat(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsFloat(position, this.source, wasNull);
  }

  @Override
  protected double getDouble(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsDouble(position, this.source, wasNull);
  }

  @Override
  protected byte[] getBytes(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsBytes(position, this.source, wasNull);
  }

  @Override
  protected BigDecimal getBigDecimal(int position, ResultWasNull wasNull)
      throws StandardException {
    return this.formatter.getAsBigDecimal(position, this.source, wasNull);
  }

  @Override
  public long getAsDateMillis(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    return this.formatter.getAsDateMillis(index, this.source, cal, wasNull);
  }

  @Override
  protected java.sql.Date getDate(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    return this.formatter.getAsDate(position, this.source, cal, wasNull);
  }

  @Override
  protected java.sql.Time getTime(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    return this.formatter.getAsTime(position, this.source, cal, wasNull);
  }

  @Override
  public long getAsTimestampMicros(int index, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    return this.formatter.getAsTimestampMicros(index, this.source, cal, wasNull);
  }

  @Override
  protected java.sql.Timestamp getTimestamp(int position, Calendar cal,
      ResultWasNull wasNull) throws StandardException {
    return this.formatter.getAsTimestamp(position, this.source, cal, wasNull);
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
  public final CompactExecRow getClone() {
    // no need to call shallowClone(false) since it always returns this
    // bytes (this.source) are immutable and the formatter does not need to be
    // cloned
    final CompactExecRow row = new CompactExecRow(this.source, this.formatter);
    row.setOfKeys = this.setOfKeys;
    return row;
  }

  @Override
  public final CompactExecRow getShallowClone() {
    return getClone();
  }

  /**
   * Get a new row with the same columns type as this one, containing nulls.
   * 
   */
  @Override
  public final CompactExecRow getNewNullRow() {
    return new CompactExecRow(this.formatter);
  }

  @Override
  public final int compare(final ExecRow row, final int logicalPosition,
      final long thisOffsetWidth, final boolean nullsOrderedLow)
      throws StandardException {
    return compare(row, this.source, logicalPosition, thisOffsetWidth,
        nullsOrderedLow);
  }

  @Override
  public final int compare(final ExecRow row, final int logicalPosition,
      final boolean nullsOrderedLow) throws StandardException {
    final byte[] bytes = this.source;
    return super.compare(row, bytes, logicalPosition,
        this.formatter.getOffsetAndWidth(logicalPosition, bytes),
        nullsOrderedLow);
  }

  @Override
  public final int computeHashCode(final int position, int hash) {
    return this.formatter.computeHashCode(position, this.source, hash);
  }

  @Override
  public long isNull(final int logicalPosition) throws StandardException {
    return this.formatter.getOffsetAndWidth(logicalPosition, this.source);
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
    return COMPACT_EXECROW;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    throw new UnsupportedOperationException(
        "CompactExecRow does not support toData()");
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.source = DataSerializer.readByteArray(in);
  }

  @Override
  public byte[] getByteSource() {
    return this.source;
  }

  @Override
  public byte[] getByteSource(int offset) {
    return this.source;
  }

  @Override
  void basicSetByteSource(Object source) {
    throw new UnsupportedOperationException(
        "CompactExecRow does not support setByteSource");
  }

  @Override
  public void releaseByteSource() {
    this.source = null;
  }

  @Override
  public byte[] getBaseByteSource() {
    return this.source;
  }
}
