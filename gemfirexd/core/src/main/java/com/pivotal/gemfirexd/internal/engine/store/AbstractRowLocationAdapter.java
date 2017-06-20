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
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXId;
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

public class AbstractRowLocationAdapter implements RowLocation {

  protected AbstractRowLocationAdapter() {
    // don't allow direct object creation
  }

  @Override
  public int getBucketID() {
    return 0;
  }

  @Override
  public RegionEntry getRegionEntry() {
    return null;
  }

  @Override
  public RegionEntry getUnderlyingRegionEntry() {
    return null;
  }

  @Override
  public Object getKey() {
    return null;
  }

  @Override
  public Object getKeyCopy() {
    return null;
  }
  
  public Object getRawKey() {
    return null;
  }

  @Override
  public Object getValue(GemFireContainer baseContainer) {
    return null;
  }

  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return null;
  }

  @Override
  public ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException {
    return null;
  }

  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    return null;
  }

  @Override
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    return null;
  }

  @Override
  public TXId getTXId() {
    return null;
  }

  @Override
  public boolean isDestroyedOrRemoved() {
    return true;
  }

  @Override
  public boolean isUpdateInProgress() {
    return false;
  }

  @Override
  public void checkHostVariable(int declaredLength) throws StandardException {
  }

  @Override
  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    return null;
  }

  @Override
  public int compare(DataValueDescriptor other) throws StandardException {
    return 0;
  }

  @Override
  public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
      throws StandardException {
    return 0;
  }

  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    return false;
  }

  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean nullsOrderedLow, boolean unknownRV)
      throws StandardException {
    return false;
  }

  @Override
  public BooleanDataValue equals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int colIdx, boolean orderedNulls, boolean unknownRV)
      throws StandardException {
    return false;
  }

  @Override
  public boolean compare(int op, CompactCompositeKey key, int colIdx,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    return false;
  }

  @Override
  public boolean canCompareBytesToBytes() {
    return false;
  }

  @Override
  public int equals(RowFormatter rf, byte[] bytes, boolean isKeyBytes,
      int logicalPosition, int keyBytesPos, final DataValueDescriptor[] outDVD)
      throws StandardException {
    return -1;
  }

  @Override
  public int estimateMemoryUsage() {
    return 0;
  }

  @Override
  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
  }

  @Override
  public boolean getBoolean() throws StandardException {
    return false;
  }

  @Override
  public byte getByte() throws StandardException {
    return 0;
  }

  @Override
  public byte[] getBytes() throws StandardException {
    return null;
  }

  @Override
  public DataValueDescriptor getClone() {
    return null;
  }

  @Override
  public Date getDate(Calendar cal) throws StandardException {
    return null;
  }

  @Override
  public double getDouble() throws StandardException {
    return 0;
  }

  @Override
  public float getFloat() throws StandardException {
    return 0;
  }

  @Override
  public int getInt() throws StandardException {
    return 0;
  }

  @Override
  public int getLength() throws StandardException {
    return 0;
  }

  @Override
  public int getLengthInBytes(DataTypeDescriptor dtd) throws StandardException {
    return 0;
  }

  @Override
  public long getLong() throws StandardException {
    return 0;
  }

  @Override
  public DataValueDescriptor getNewNull() {
    return null;
  }

  @Override
  public Object getObject() throws StandardException {
    return null;
  }

  @Override
  public short getShort() throws StandardException {
    return 0;
  }

  @Override
  public InputStream getStream() throws StandardException {
    return null;
  }

  @Override
  public String getString() throws StandardException {
    return null;
  }

  @Override
  public Time getTime(Calendar cal) throws StandardException {
    return null;
  }

  @Override
  public Timestamp getTimestamp(Calendar cal) throws StandardException {
    return null;
  }

  @Override
  public String getTraceString() throws StandardException {
    return null;
  }

  @Override
  public String getTypeName() {
    return null;
  }

  @Override
  public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

  @Override
  public BooleanDataValue greaterThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

  @Override
  public BooleanDataValue in(DataValueDescriptor left,
      DataValueDescriptor[] inList, boolean orderedList)
      throws StandardException {
    return null;
  }

  @Override
  public BooleanDataValue isNotNull() {
    return null;
  }

  @Override
  public BooleanDataValue isNullOp() {
    return null;
  }

  @Override
  public BooleanDataValue lessOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

  @Override
  public BooleanDataValue lessThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

  @Override
  public void normalize(DataTypeDescriptor dtd, DataValueDescriptor source)
      throws StandardException {
  }

  @Override
  public BooleanDataValue notEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    return null;
  }

 

  @Override
  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
  }

  @Override
  public DataValueDescriptor recycle() {
    return null;
  }

  @Override
  public void setBigDecimal(Number bigDecimal) throws StandardException {
  }

  @Override
  public void setInto(PreparedStatement ps, int position) throws SQLException,
      StandardException {
  }

  @Override
  public void setInto(ResultSet rs, int position) throws SQLException,
      StandardException {
  }

  @Override
  public void setObjectForCast(Object value, boolean instanceOfResultType,
      String resultTypeClassName) throws StandardException {
  }

  @Override
  public void setToNull() {
  }

  @Override
  public void setValue(int theValue) throws StandardException {
  }

  @Override
  public void setValue(double theValue) throws StandardException {
  }

  @Override
  public void setValue(float theValue) throws StandardException {
  }

  @Override
  public void setValue(short theValue) throws StandardException {
  }

  @Override
  public void setValue(long theValue) throws StandardException {
  }

  @Override
  public void setValue(byte theValue) throws StandardException {
  }

  @Override
  public void setValue(boolean theValue) throws StandardException {
  }

  @Override
  public void setValue(Object theValue) throws StandardException {
  }

  @Override
  public void setValue(byte[] theValue) throws StandardException {
  }

  @Override
  public void setValue(String theValue) throws StandardException {
  }

  @Override
  public void setValue(Time theValue) throws StandardException {
  }

  @Override
  public void setValue(Time theValue, Calendar cal) throws StandardException {
  }

  @Override
  public void setValue(Timestamp theValue) throws StandardException {
  }

  @Override
  public void setValue(Timestamp theValue, Calendar cal)
      throws StandardException {
  }

  @Override
  public void setValue(Date theValue) throws StandardException {
  }

  @Override
  public void setValue(Date theValue, Calendar cal) throws StandardException {
  }

  @Override
  public void setValue(DataValueDescriptor theValue) throws StandardException {
  }

  @Override
  public void setValue(InputStream theStream, int valueLength)
      throws StandardException {
  }

  @Override
  public void setValue(java.sql.Blob theValue) {
  }

  @Override
  public void setValue(java.sql.Clob theValue) {
  }

  @Override
  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
  }

  @Override
  public void toDataForOptimizedResultHolder(DataOutput dos) throws IOException {

  }

  @Override
  public int typePrecedence() {
    return 0;
  }

  @Override
  public int typeToBigDecimal() throws StandardException {
    return 0;
  }

  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    return 0;
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    return hash;
  }

  @Override
  public boolean isNull() {
    return false;
  }

  @Override
  public void restoreToNull() {
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
  }

  @Override
  public int getTypeFormatId() {
    return 0;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
  }

  @Override
  public int getDSFID() {
    return 0;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
  }

  @Override
  public Object cloneObject() {
    return null;
  }

  @Override
  public final void setRegionContext(LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final KeyWithRegionContext beforeSerializationWithValue(
      boolean valueIsToken) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final void afterDeserializationWithValue(Object val) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final int nCols() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final DataValueDescriptor getKeyColumn(int index) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final void getKeyColumns(DataValueDescriptor[] keys) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public final void getKeyColumns(Object[] keys)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
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
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth, ByteSource bs) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }
  
  @Override
  public Object getValueOrOffHeapEntry(LocalRegion owner)   {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public Object getRawValue() {
    
    return null;
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
