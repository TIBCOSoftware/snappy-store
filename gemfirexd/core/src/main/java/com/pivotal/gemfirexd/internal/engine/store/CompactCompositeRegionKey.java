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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.COMPACT_COMPOSITE_KEY_VALUE_BYTES;
import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.REGION_ENTRY_VALUE;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * An instance of this class is used as the key in the Region when a primary key
 * is to be stored in byte[] form pointing to the value portion.
 * 
 * [sumedh] Now this is no longer used as the key in the Region rather the GFXD
 * RegionEntry implementations themselves encapsulate the keyBytes and
 * TableInfo. This is used for temporarily carrying out the key information
 * during processing (RegionEntry.getKeyCopy).
 * 
 * Keep this class as final since there are getClass() invocations that depend
 * on this.
 * 
 * @author soubhik
 */
public final class CompactCompositeRegionKey extends CompactCompositeKey
    implements Externalizable, GfxdSerializable, RegionKey, KeyWithRegionContext,
    Sizeable {

  private static final long serialVersionUID = -5397414534166595916L;

  /**
   * Token for sending across when value is also being sent. Actual bytes in key
   * will be restored from value portion on the receiving side.
   */
  private static final CompactCompositeRegionKey TOKEN_KEY =
    new CompactCompositeRegionKey();

  /**
   * The cached hashCode() calculated for this object.
   */
  private transient int hash;

  /**
   * Used for DataSerializer and TOKEN_KEY only.
   */
  public CompactCompositeRegionKey() {
  }

  /**
   * Create a new {@link CompactCompositeRegionKey} given the underlying OffHeapRegionEntry
   * and the {@link ExtraTableInfo} for the table.
   */
  public CompactCompositeRegionKey(final OffHeapRegionEntry value,
      final ExtraTableInfo tabInfo) {
    super(value, tabInfo);
  }
  
  /**
   * Create a new {@link CompactCompositeRegionKey} given the {@link OffHeapByteSource}
   * and the {@link ExtraTableInfo} for the table.
   * If it is a OffHeapByteSource , appropriate ref count increase
   * has already happened so it is safe to use
   */
  public CompactCompositeRegionKey(
      @Unretained(COMPACT_COMPOSITE_KEY_VALUE_BYTES) final OffHeapByteSource value,
      final ExtraTableInfo tabInfo) {
    super(value, tabInfo);
  }

  /**
   * Create a new {@link CompactCompositeRegionKey} given the underlying row
   * as bytes and the {@link ExtraTableInfo} for the table.
   */
  public CompactCompositeRegionKey(final byte[] value,
      final ExtraTableInfo tabInfo) {
    super(value, tabInfo);
  }

  /**
   * Create a new {@link CompactCompositeRegionKey} given the serialized key
   * column bytes and the {@link ExtraTableInfo} for the table.
   */
  public CompactCompositeRegionKey(final ExtraTableInfo tabInfo,
      final byte[] keyBytes) {
    super(tabInfo, keyBytes);
  }

  /**
   * Create a new {@link CompactCompositeRegionKey} given the DVD array
   * representing the primary key columns and the {@link ExtraTableInfo} for the
   * table.
   */
  public CompactCompositeRegionKey(final DataValueDescriptor[] value,
      final ExtraTableInfo tabInfo) throws StandardException {
    super(value, tabInfo);
  }

  /**
   * Create a new {@link CompactCompositeRegionKey} given a single DVD
   * representing the primary key column and the {@link ExtraTableInfo} for the
   * table.
   */
  public CompactCompositeRegionKey(final DataValueDescriptor value,
      final ExtraTableInfo tabInfo) throws StandardException {
    super(value, tabInfo);
  }

  //////////////////////////  PUBLIC METHODS  ////////////////////////////

  public final void setExtraTableInfo(final GemFireContainer container) {
    if (this.tableInfo == null && container.isByteArrayStore()) {
      setTableInfo(container);
    }
  }

  @Override
  public final void setRegionContext(LocalRegion region) {
    final GemFireContainer container;
    if (this.tableInfo == null
        && (container = (GemFireContainer)region.getUserAttribute())
            .isByteArrayStore()) {
      setTableInfo(container);
    }
  }

  private final void setTableInfo(final GemFireContainer container) {
    @Retained @Released final Object vbytes = getValueByteSource();
    if (vbytes == null) {
      this.tableInfo = container.getExtraTableInfo();
    } else {
      try {
        this.tableInfo = container.getExtraTableInfo(vbytes);
      } finally {
        this.releaseValueByteSource(vbytes);
      }
    }
  }

  public final ExtraTableInfo getTableInfo() {
    return (ExtraTableInfo)this.tableInfo;
  }

  @Override
  public final KeyWithRegionContext beforeSerializationWithValue(
      final boolean valueIsToken) {
    if (!valueIsToken) {
      // no data needs to be sent
      return TOKEN_KEY;
    }
    return this;
  }

  @Override
  public final void afterDeserializationWithValue(
      final Object val) {
    final Class<?> cls = val.getClass();
    if (cls == byte[][].class) {
      //this.valueBytes = ((byte[][])val)[0];
      this.setValueBytes(((byte[][])val)[0]);
      // null the tableInfo so setRegionContext/setTableInfo should be invoked
      // after this call
    }
    else if (cls == byte[].class || !Token.class.isAssignableFrom(cls)) {
       // this.valueBytes = val;
      this.setValueBytes(val);
      /*Assert.fail("CCRK.afterDeserializationWithValue: unexpected value class="
          + (val != null ? val.getClass().getName() : "null") + ": " + val);*/
    }
    this.tableInfo = null;
  }

  /**
   * @see Sizeable#getSizeInBytes()
   */
  @Override
  public final int getSizeInBytes() {
    int tries = 1;
    do {
      @Retained @Released final Object valBytes = getValueByteSource();
      try {
        final int size = RegionEntryUtils.entryKeySizeInBytes(this.getKeyBytes(),
            valBytes, this.tableInfo);
        if (size >= 0) {
          return size;
        }
      } finally {
        this.releaseValueByteSource(valBytes);
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }

    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeRegionKey#getSizeInBytes");
  }

  @Override
  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  @Override
  public byte getGfxdID() {
    return COMPOSITE_REGION_KEY;
  }

  @Override
  public final void fromData(final DataInput in) throws IOException,
      ClassNotFoundException {
    setKeyBytes(DataSerializer.readByteArray(in));
    //this.valueBytes = null;
    this.setValueBytes(null);
  }

  @Override
  protected Object getTokenKey() {
    return TOKEN_KEY;
  }

  @Override
  public final void toData(final DataOutput out) throws IOException {
    //GfxdDataSerializable.writeGfxdHeader(this, out);
    this.writeKeyBytes(out);
  }

  @Override
  public int hashCode() {
    int h = this.hash;
    if (h != 0) {
      return h;
    }
    else {
      return (this.hash = super.hashCode());
    }
  }

  @Override
  public long estimateMemoryUsage() {
    return getSizeInBytes() + ReflectionSingleObjectSizer.OBJECT_SIZE
        + 4 /* cached hash */
        + ReflectionSingleObjectSizer.REFERENCE_SIZE /* ExtraInfo reference */;
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    this.fromData(in);
    
  }

  @Override
  @Retained
  public Object getValueByteSource() {

    // return this.valueBytes;
    @Retained Object retVal = super.getValueByteSource();

    if (retVal != null) {
      final Class<?> rclass = retVal.getClass();
      if (rclass != byte[].class) {
        if (OffHeapByteSource.isOffHeapBytesClass(rclass)) {
          if (!((OffHeapByteSource)retVal).retain()) {
            retVal = null;
          }
        }
        else if (OffHeapRegionEntry.class.isAssignableFrom(rclass)) {
          retVal = RegionEntryUtils.convertOffHeapEntrytoByteSourceRetain(
              (OffHeapRegionEntry)retVal, null, null, false, true);
        }
      }
    }
    return retVal;
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    this.writeKeyBytes(out);  
  }

  @Override
  public final byte[] getKeyBytes() {
    byte[] keyBytes = super.get();
    if(keyBytes == null) {
      //check if the value byte source is OffHeapRegionEntry & if it has key bytes which will be
      // there in case value has overflown or removed
      @Unretained(REGION_ENTRY_VALUE) Object val = super.getValueByteSource();
      if(val instanceof AbstractRegionEntry) {
        Object temp = ((AbstractRegionEntry)val).getRawKey();
        if(temp instanceof byte[]) {
          keyBytes = (byte[])temp;
        }
      }
    }
    return keyBytes;
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
