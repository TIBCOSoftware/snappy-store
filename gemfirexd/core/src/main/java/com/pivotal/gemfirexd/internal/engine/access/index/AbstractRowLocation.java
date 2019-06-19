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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/**
 * @author yjing
 * 
 */
public abstract class AbstractRowLocation implements RowLocation {

  @Override
  public Object getValue(GemFireContainer baseContainer)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }
  
  @Override
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner)
       {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }
  
  public Object getValueOrOffHeapEntry(LocalRegion owner)   {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }
  

  @Override
  public ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public RegionEntry getRegionEntry() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public Object getKey() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public Object getKeyCopy() {
    return getKey();
  }
  
  public Object getRawKey() {
    return getKey();
  }

  @Override
  public final RegionEntry getUnderlyingRegionEntry() {
    return getRegionEntry();
  }

  @Override
  public boolean isDestroyedOrRemoved() {
    return getRegionEntry().isDestroyedOrRemoved();
  }

  @Override
  public boolean isUpdateInProgress() {
    return getRegionEntry().isUpdateInProgress();
  }

  public abstract Serializable getRoutingObject();

  @Override
  public TXId getTXId() {
    return null;
  }

  public void checkHostVariable(int declaredLength) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public int compare(DataValueDescriptor other) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean nullsOrderedLow, boolean unknownRV)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue equals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public int estimateMemoryUsage() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public boolean getBoolean() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public byte getByte() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public byte[] getBytes() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public Date getDate(Calendar cal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public double getDouble() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getFloat()
   */
  public float getFloat() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getInt()
   */
  public int getInt() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getLength()
   */
  public int getLength() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getLong()
   */
  public long getLong() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getObject()
   */
  public Object getObject() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getShort()
   */
  public short getShort() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getStream()
   */
  public InputStream getStream() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getString()
   */
  public String getString() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public Time getTime(Calendar cal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public Timestamp getTimestamp(Calendar cal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getTraceString()
   */
  public String getTraceString() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#getTypeName()
   */
  public String getTypeName() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue greaterThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue in(DataValueDescriptor left,
      DataValueDescriptor[] inList, boolean orderedList)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#isNotNull()
   */
  public BooleanDataValue isNotNull() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#isNullOp()
   */
  public BooleanDataValue isNullOp() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue lessOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue lessThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void normalize(DataTypeDescriptor dtd, DataValueDescriptor source)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public BooleanDataValue notEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public DataValueDescriptor recycle() {
    return null;
  }

  public void setBigDecimal(Number bigDecimal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setInto(PreparedStatement ps, int position) throws SQLException,
      StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setInto(ResultSet rs, int position) throws SQLException,
      StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setObjectForCast(Object value, boolean instanceOfResultType,
      String resultTypeClassName) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setToNull()
   */
  public void setToNull() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(int)
   */
  public void setValue(int theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(double)
   */
  public void setValue(double theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(float)
   */
  public void setValue(float theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(short)
   */
  public void setValue(short theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(long)
   */
  public void setValue(long theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(byte)
   */
  public void setValue(byte theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#setValue(boolean)
   */
  public void setValue(boolean theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public void setValue(Object theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public void setValue(byte[] theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(String theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Time theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Time theValue, Calendar cal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Timestamp theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Timestamp theValue, Calendar cal)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Date theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(Date theValue, Calendar cal) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(DataValueDescriptor theValue) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValue(InputStream theStream, int valueLength)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public void setValue(java.sql.Blob theValue) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public void setValue(java.sql.Clob theValue) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#typePrecedence()
   */
  public int typePrecedence() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor#typeToBigDecimal()
   */
  public int typeToBigDecimal() throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#isNull()
   */
  public boolean isNull() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.services.io.Storable#restoreToNull()
   */
  public void restoreToNull() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId()
   */
  public int getTypeFormatId() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /* (non-Javadoc)
   * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
   */
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new AssertionError("Should not serialize RowLocation with "
        + getClass());
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
   */
  public void writeExternal(ObjectOutput out) throws IOException {
    throw new AssertionError("Should not serialize RowLocation with "
        + getClass());
  }

  public int getDSFID() {
    throw new AssertionError("Should not serialize RowLocation with "
        + getClass());
  }

  public void toData(DataOutput out) throws IOException {
    throw new AssertionError("Should not serialize RowLocation with "
        + getClass());
  }

  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    throw new AssertionError("Should not serialize RowLocation with "
        + getClass());
  }

  /**
   * Return length of this value in bytes.
   */
  public int getLengthInBytes(DataTypeDescriptor dtd) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth, ByteSource bs) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public int getBucketID() {
    return -1;
  }

  public AbstractRowLocation getClone() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public AbstractRowLocation cloneObject() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void toDataForOptimizedResultHolder(DataOutput dos) throws IOException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final void setRegionContext(LocalRegion region) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final KeyWithRegionContext beforeSerializationWithValue(
      boolean valueIsToken) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final void afterDeserializationWithValue(Object val) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final int nCols() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final DataValueDescriptor getKeyColumn(int index) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final void getKeyColumns(DataValueDescriptor[] keys) {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public final void getKeyColumns(Object[] keys) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int colIdx, boolean orderedNulls, boolean unknownRV)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public boolean compare(int op, CompactCompositeKey key, int colIdx,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public boolean canCompareBytesToBytes() {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }

  @Override
  public int equals(RowFormatter rf, byte[] bytes, boolean isKeyBytes,
      int logicalPosition, int keyBytesPos, final DataValueDescriptor[] outDVD)
      throws StandardException {
    throw new AssertionError("Not expected to be invoked for " + getClass());
  }
  
  @Override
  public  byte getTypeId() {
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }
  
  @Override
  public  void writeNullDVD(DataOutput out) throws IOException{
    out.writeByte(DSCODE.NULL);
    out.writeByte(getTypeId());    
  }

  @Override
  public Object getRawValue() {
    throw new UnsupportedOperationException("Implement the method in concrete class");
  }

  @Override
  public boolean isValueNull() {
    throw new UnsupportedOperationException("Implement the method in concrete class");
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
  

  public Version[] getSerializationVersions() {
    return null;
  }
}
