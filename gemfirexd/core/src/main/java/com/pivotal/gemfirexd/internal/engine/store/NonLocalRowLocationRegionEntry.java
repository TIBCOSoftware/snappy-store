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
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.NonLocalRegionEntry;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/**
 * Extension to {@link NonLocalRegionEntry} for GemFireXD that implements the
 * {@link RowLocation} interface.
 * 
 * @author swale
 * @since 7.0
 */
public final class NonLocalRowLocationRegionEntry extends NonLocalRegionEntry
    implements RowLocation {

  // for deserialization
  public NonLocalRowLocationRegionEntry() {
  }

  NonLocalRowLocationRegionEntry(RegionEntry re, LocalRegion br,
      boolean allowTombstones) {
    super(re, br, allowTombstones);
  }

  NonLocalRowLocationRegionEntry(RegionEntry re, LocalRegion br,
      boolean allowTombstones, boolean faultInValue) {
    super(re, br, allowTombstones, faultInValue);
  }

  NonLocalRowLocationRegionEntry(Object key, Object value, LocalRegion br,
      VersionTag<?> versionTag) {
    super(key, value, br, versionTag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RegionEntry getRegionEntry() {
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RegionEntry getUnderlyingRegionEntry() {
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValue(GemFireContainer baseContainer)
      throws StandardException {
    return this.value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer)
       {
    return this.value;
    
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException {
    return baseContainer.newExecRow(this.value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    return baseContainer.newExecRow(this.value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    return baseContainer.getExtraTableInfo(this.value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getRawKey() {
    return this.key;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getBucketID() {
    return -1;
  }


  @Override
  public int getDSFID() {
    throw new UnsupportedOperationException("not expected to be invoked for "
        + getClass().getName());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TXId getTXId() {
    return null;
  }

  // Unsupported methods from DVD

  /**
   * {@inheritDoc}
   */
  @Override
  public int getLength() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getString() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTraceString() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getBoolean() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte getByte() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public short getShort() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getInt() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getLong() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getFloat() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double getDouble() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int typeToBigDecimal() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getBytes() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getStream() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DataValueDescriptor getClone() {
    return this;
    //throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DataValueDescriptor recycle() {
    return this;
    //throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DataValueDescriptor getNewNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setInto(PreparedStatement ps, int position) throws SQLException,
      StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setInto(ResultSet rs, int position) throws SQLException,
      StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(int theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(double theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(float theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(short theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(long theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(byte theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(boolean theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Object theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(byte[] theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBigDecimal(Number bigDecimal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(String theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Blob theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Clob theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Time theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Time theValue, Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Timestamp theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Timestamp theValue, Calendar cal)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Date theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(Date theValue, Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(DataValueDescriptor theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setToNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void normalize(DataTypeDescriptor dtd, DataValueDescriptor source)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue isNullOp() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue isNotNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTypeName() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setObjectForCast(Object value, boolean instanceOfResultType,
      String resultTypeClassName) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int typePrecedence() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue equals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue notEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue lessThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue greaterThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue lessOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BooleanDataValue in(DataValueDescriptor left,
      DataValueDescriptor[] inList, boolean orderedList)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int compare(DataValueDescriptor other) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean nullsOrderedLow, boolean unknownRV)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValue(InputStream theStream, int valueLength)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void checkHostVariable(int declaredLength) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int estimateMemoryUsage() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getLengthInBytes(DataTypeDescriptor dtd) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth,
      ByteSource bs) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void toDataForOptimizedResultHolder(DataOutput dos) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isNull() {
    // For snapshot and tx this should be returned only for READ operations
    return isDestroyedOrRemoved();
    //throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void restoreToNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getTypeFormatId() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int nCols() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DataValueDescriptor getKeyColumn(int index) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void getKeyColumns(DataValueDescriptor[] keys) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void getKeyColumns(Object[] keys) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRegionContext(LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public KeyWithRegionContext beforeSerializationWithValue(boolean valueIsToken) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void afterDeserializationWithValue(Object val) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int colIdx, boolean orderedNulls, boolean unknownRV)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compare(int op, CompactCompositeKey key, int colIdx,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean canCompareBytesToBytes() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int equals(RowFormatter rf, byte[] bytes, boolean isKeyBytes,
      int logicalPosition, int keyBytesPos, DataValueDescriptor[] outDVD)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object cloneObject() {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  
  @Override
  public  byte getTypeId() {
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }
  
  @Override
  public  void writeNullDVD(DataOutput out) throws IOException{
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);    
  }

  @Override
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner) {
    return this.value;
  }
  
  @Override
  public Object getValueOrOffHeapEntry(LocalRegion owner) {
   
    return this.getValue(owner);
  }

  @Override
  public Object getRawValue() {
    return _getValue();
  }

  @Override
  public void markDeleteFromIndexInProgress() {
    //NOOP
    
  }

  @Override
  public void unmarkDeleteFromIndexInProgress() {
  //NOOP
    
  }

  @Override
  public boolean useRowLocationForIndexKey() {
    
    return true;
  }

  @Override
  public void endIndexKeyUpdate() {
  //NOOP
    
  }
  

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
