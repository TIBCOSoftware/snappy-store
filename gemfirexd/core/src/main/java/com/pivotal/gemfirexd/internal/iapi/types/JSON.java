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
package com.pivotal.gemfirexd.internal.iapi.types;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.pdx.JSONFormatter;
import com.gemstone.gemfire.pdx.JSONFormatterException;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.pdx.internal.PdxInstanceImpl;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

public final class JSON extends SQLClob {

  byte[] jsonPdxInstanceBytes = null;
  
  public JSON() {
    super();
  }

  public JSON(String val) throws StandardException {
//    super(val);
    setValue(val);
  }

  public String getTypeName() {
    return TypeId.JSON_NAME;
  }
  
  /**
   * Return my format identifier.
   * 
   * @see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
   */
  public int getTypeFormatId() {
    return StoredFormatIds.SQL_JSON_ID;
  }
  
  @Override
  public void setValue(String theValue) throws StandardException {
    // stream = null;
    rawLength = -1;
//    cKey = null;

//    value = theValue;
    if (theValue != null) {
      PdxInstance jsonPdxInstance1 = null;
      try {
        jsonPdxInstance1 = JSONFormatter.fromJSON(theValue);
      } catch (JSONFormatterException je) {
        //showing only first 255 chars of the input
        String s = theValue;
        if (theValue.length() > 255) {
          s = theValue.substring(0, 255);
        }
        throw StandardException.newException(SQLState.LANG_INVALID_JSON, je, 
            s, 255);
      }
      jsonPdxInstanceBytes = ((PdxInstanceImpl) jsonPdxInstance1).toBytes();
    } else {
      jsonPdxInstanceBytes = null;
    }
    
    this.rawData = null;
  }
  
   public byte[] getPdxInstanceBytes() {
    return jsonPdxInstanceBytes;
  }

  public PdxInstance getPdxInstance() {
    final byte[] jsonBytes = this.jsonPdxInstanceBytes;
    return jsonBytes != null ? InternalDataSerializer.readPdxInstance(
        jsonBytes, GemFireCacheImpl.getForPdx(
            "Could not read PdxInstance bytes")) : null;
  }

  @Override
  public String getString() {
    final byte[] jsonBytes = this.jsonPdxInstanceBytes;
    if (jsonBytes != null) {
      PdxInstance jPdxInstance = InternalDataSerializer.readPdxInstance(
          jsonBytes,
          GemFireCacheImpl.getForPdx("Could not read PdxInstance bytes"));
      return JSONFormatter.toJSON(jPdxInstance);
    }
    else {
      return null;
    }
  }

  @Override
  public Object getObject() throws StandardException {
    if (this.isNull())
      return null;
    return this;
  }
  
  @Override
  public void setObject(Object obj) throws StandardException {
    setValue(((JSON)obj).getString());
  }
  
  /**
   * @see DataValueDescriptor#getNewNull
   *
   */
  public DataValueDescriptor getNewNull() {
      return new JSON();
  }
  
  /** @see DataValueDescriptor#getClone */
  public DataValueDescriptor getClone() {
    try {
      return new JSON(getString());
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Unexpected exception", se);
    }
  }

  @Override
  protected void setFrom(DataValueDescriptor theValue) 
      throws StandardException {
    switch (theValue.getTypeFormatId()) {
    case StoredFormatIds.SQL_JSON_ID:
      jsonPdxInstanceBytes = ((JSON) theValue).getPdxInstanceBytes();
      break;
    default:
      setValue(theValue.getString());
    }
  }

  @Override
  public void normalize(DataTypeDescriptor desiredType,
      DataValueDescriptor source) throws StandardException {
    setFrom(source);
  }

  @Override
  final void writeData(DataOutput out, boolean createArray) throws IOException {
    toDataForOptimizedResultHolder(out);
  }

  @Override
  final void readData(DataInput in, boolean reuseArrayIfPossible)
      throws IOException {
    int length = in.readUnsignedShort();
    byte[] b = new byte[length];
    in.readFully(b);
    try {
      setValue(new String(b));
    } catch (StandardException e) {
      // FIXME: may not always be IOException
      throw new IOException(e);
    }
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    boolean isNull = isNull();
    out.writeBoolean(isNull);
    if (!isNull) {
      toDataForOptimizedResultHolder(out);
    }
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException {
    boolean isNull = in.readBoolean();
    if (!isNull) {
      fromDataForOptimizedResultHolder(in);
      return;
    }
    setToNull();
  }

  @Override
  public final void toData(DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
//      writeData(out, true);
      toDataForOptimizedResultHolder(out);
      return;
    }
    this.writeNullDVD(out);
  }

  @Override
  public final void fromData(DataInput in) throws IOException {
    fromDataForOptimizedResultHolder(in);
  }

  @Override
  public final void toDataForOptimizedResultHolder(DataOutput dos)
      throws IOException {
    assert !isNull();
    dos.writeInt(jsonPdxInstanceBytes.length);
    dos.write(jsonPdxInstanceBytes);
  }

  @Override
  public final void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException {
    int length = dis.readInt();
    jsonPdxInstanceBytes = new byte[length];
    dis.readFully(jsonPdxInstanceBytes);
  }
  
  @Override
  public byte getTypeId() {
    return DSCODE.GFXD_JSON;
  }
  
  /**
   * see if the String value is null.
   @see Storable#isNull
  */
  @Override
  public final boolean isNull() {
      return (jsonPdxInstanceBytes == null);
  }
  
  @Override
  public void restoreToNull() {
    jsonPdxInstanceBytes = null;
  }
  
  @Override
  public int getLength() {
    if (!isNull()) {
      return jsonPdxInstanceBytes.length;
    } else {
      return 0;
    }
  }
  
  @Override
  public int getLengthInBytes(final DataTypeDescriptor dtd) {
    return getLength();
  }
  
  @Override
  public int writeBytes(final byte[] bytes, final int offset,
      final DataTypeDescriptor dtd) {
    // never called when value is null
    assert !isNull();
    System.arraycopy(jsonPdxInstanceBytes, 0, bytes, offset, 
        jsonPdxInstanceBytes.length);
    return jsonPdxInstanceBytes.length;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, final int offset,
      final int columnWidth) {
    assert inBytes.length == columnWidth;
    jsonPdxInstanceBytes = new byte[columnWidth];
    System.arraycopy(inBytes, offset, jsonPdxInstanceBytes, 0, columnWidth);
    return columnWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final long memOffset, int columnWidth, ByteSource bs) {
    jsonPdxInstanceBytes = new byte[columnWidth];
    UnsafeMemoryChunk.readUnsafeBytes(memOffset, jsonPdxInstanceBytes, columnWidth);
    return columnWidth;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    if (isNull()) {
      sb.append("NULL");
      return sb.toString();
    }
    else {
      String str = this.getString();
      // avoiding huge strings in logs
      if (str.length() > 4096) {
        sb.append("string value(first 4096 chars)=");
        sb.append(str.substring(0, 4096));
        sb.append("...<clipped>");
      }
      else {
        sb.append(str);
      }
      return sb.toString();
    }
  }
}
