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

/**
 * Do not modify this class. It was generated.
 * Instead modify LeafRegionEntry.cpp and then run
 * bin/generateRegionEntryClasses.sh from the directory
 * that contains your build.xml.
 */
package com.pivotal.gemfirexd.internal.engine.store.entry;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import java.io.DataOutput;
import java.io.IOException;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.RegionEntryContext;
import com.gemstone.gemfire.internal.cache.RegionEntryFactory;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.RegionClearedException;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRegionEntryUtils;
import com.gemstone.gemfire.internal.cache.DiskId;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.AbstractDiskRegionEntry;
import com.gemstone.gemfire.internal.cache.PlaceHolderDiskRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
public class VMBucketRowLocationThinDiskLRURegionEntryOffHeap extends RowLocationThinDiskLRURegionEntry
    implements OffHeapRegionEntry
{
  public VMBucketRowLocationThinDiskLRURegionEntryOffHeap (RegionEntryContext context, Object key,
    @Retained
    Object value
      ) {
    super(context,
          (value instanceof RecoveredEntry ? null : value)
        );
    initialize(context, value);
    this.tableInfo = RegionEntryUtils.entryGetTableInfo(context, key, value);
    this.bucketId = RegionEntryUtils.getBucketId(context);
    this.key = RegionEntryUtils.entryGetRegionKey(key, value);
  }
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<VMBucketRowLocationThinDiskLRURegionEntryOffHeap> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(VMBucketRowLocationThinDiskLRURegionEntryOffHeap.class, "lastModified");
  protected long getlastModifiedField() {
    return lastModifiedUpdater.get(this);
  }
  protected boolean compareAndSetLastModifiedField(long expectedValue, long newValue) {
    return lastModifiedUpdater.compareAndSet(this, expectedValue, newValue);
  }
  @Override
  public final int getEntryHash() {
    return this.hash;
  }
  @Override
  protected void setEntryHash(int v) {
    this.hash = v;
  }
  @Override
  public final HashEntry<Object, Object> getNextEntry() {
    return this.next;
  }
  @Override
  public final void setNextEntry(final HashEntry<Object, Object> n) {
    this.next = n;
  }
  protected void initialize(RegionEntryContext drs, Object value) {
    boolean isBackup;
    if (drs instanceof LocalRegion) {
      isBackup = ((LocalRegion)drs).getDiskRegion().isBackup();
    } else if (drs instanceof PlaceHolderDiskRegion) {
      isBackup = true;
    } else {
      throw new IllegalArgumentException("expected a LocalRegion or PlaceHolderDiskRegion");
    }
    if (isBackup) {
      diskInitialize(drs, value);
    }
  }
  @Override
  public final synchronized int updateAsyncEntrySize(EnableLRU capacityController) {
    int oldSize = getEntrySize();
    int newSize = getKeySize(getRawKey(), capacityController);
    setEntrySize(newSize);
    int delta = newSize - oldSize;
    return delta;
  }
  private final int getKeySize(Object key, EnableLRU capacityController) {
    final GemFireCacheImpl.StaticSystemCallbacks sysCb =
        GemFireCacheImpl.getInternalProductCallbacks();
    if (sysCb == null || capacityController.getEvictionAlgorithm().isLRUEntry()) {
      return capacityController.entrySize(key, null);
    }
    else {
      int tries = 1;
      do {
        final int size = sysCb.entryKeySizeInBytes(key, this);
        if (size >= 0) {
          return size - ReflectionSingleObjectSizer.REFERENCE_SIZE;
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          Thread.yield();
        }
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("DiskLRU RegionEntry#getKeySize");
    }
  }
  private void diskInitialize(RegionEntryContext context, Object value) {
    DiskRecoveryStore drs = (DiskRecoveryStore)context;
    DiskStoreImpl ds = drs.getDiskStore();
    long maxOplogSize = ds.getMaxOplogSize();
    this.id = DiskId.createDiskId(maxOplogSize, true , ds.needsLinkedList());
    Helper.initialize(this, drs, value);
  }
  protected DiskId id;
  public DiskId getDiskId() {
    return this.id;
  }
  @Override
  public void setDiskId(RegionEntry old) {
    this.id = ((AbstractDiskRegionEntry)old).getDiskId();
  }
  @Override
  public void setDelayedDiskId(LocalRegion r) {
    DiskStoreImpl ds = r.getDiskStore();
    long maxOplogSize = ds.getMaxOplogSize();
    this.id = DiskId.createDiskId(maxOplogSize, false , ds.needsLinkedList());
  }
  public final synchronized int updateEntrySize(EnableLRU capacityController) {
    return updateEntrySize(capacityController, _getValue());
  }
  public final synchronized int updateEntrySize(EnableLRU capacityController,
                                                Object value) {
    int oldSize = getEntrySize();
    int newSize = capacityController.entrySize(getRawKey(), value);
    setEntrySize(newSize);
    int delta = newSize - oldSize;
    return delta;
  }
  private LRUClockNode nextLRU;
  private LRUClockNode prevLRU;
  private int size;
  public final void setNextLRUNode( LRUClockNode next ) {
    this.nextLRU = next;
  }
  public final LRUClockNode nextLRUNode() {
    return this.nextLRU;
  }
  public final void setPrevLRUNode( LRUClockNode prev ) {
    this.prevLRU = prev;
  }
  public final LRUClockNode prevLRUNode() {
    return this.prevLRU;
  }
  public final int getEntrySize() {
    return this.size;
  }
  protected final void setEntrySize(int size) {
    this.size = size;
  }
  private Object key;
  @Override
  public final Object getRawKey() {
    return this.key;
  }
  @Override
  protected void _setRawKey(Object key) {
    this.key = key;
  }
  @Retained @Released private volatile long ohAddress;
  private final static AtomicLongFieldUpdater<VMBucketRowLocationThinDiskLRURegionEntryOffHeap> ohAddrUpdater =
      AtomicUpdaterFactory.newLongFieldUpdater(VMBucketRowLocationThinDiskLRURegionEntryOffHeap.class, "ohAddress");
  @Override
  public boolean isOffHeap() {
    return true;
  }
  @Override
  public Token getValueAsToken() {
    return OffHeapRegionEntryHelper.getValueAsToken(this);
  }
  @Override
  @Unretained
  protected Object getValueField() {
    return OffHeapRegionEntryHelper._getValue(this);
  }
  @Override
  protected void setValueField(@Unretained Object v) {
    OffHeapRegionEntryHelper.setValue(this, v);
  }
  @Override
  @Retained
  public Object _getValueRetain(RegionEntryContext context, boolean decompress) {
    return OffHeapRegionEntryHelper._getValueRetain(this, decompress);
  }
  @Override
  public long getAddress() {
    return ohAddrUpdater.get(this);
  }
  @Override
  public boolean setAddress(long expectedAddr, long newAddr) {
    return ohAddrUpdater.compareAndSet(this, expectedAddr, newAddr);
  }
  @Override
  @Released
  public void release() {
    OffHeapRegionEntryHelper.releaseEntry(this);
  }
  private transient ExtraTableInfo tableInfo;
  @Override
  public final ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    return this.tableInfo;
  }
  @Override
  public final Object getContainerInfo() {
    return this.tableInfo;
  }
  @Override
  public final Object setContainerInfo(final LocalRegion owner, final Object val) {
    final GemFireContainer container;
    ExtraTableInfo tabInfo;
    if (owner == null) {
      final RowFormatter rf;
      if ((tabInfo = this.tableInfo) != null
          && (rf = tabInfo.getRowFormatter()) != null) {
        container = rf.container;
      }
      else {
        return null;
      }
    }
    else {
      container = (GemFireContainer)owner.getUserAttribute();
    }
    if (container != null && container.isByteArrayStore()) {
      tabInfo = container.getExtraTableInfo(val);
      this.tableInfo = tabInfo;
      if (tabInfo != null && tabInfo.regionKeyPartOfValue()) {
        return tabInfo;
      }
    }
    return null;
  }
  @Override
  public int estimateMemoryUsage() {
    return ClassSize.refSize;
  }
  @Override
  public int getTypeFormatId() {
    return StoredFormatIds.ACCESS_MEM_HEAP_ROW_LOCATION_ID;
  }
  @Override
  public final Object cloneObject() {
    return this;
  }
  @Override
  public final RowLocation getClone() {
    return this;
  }
  @Override
  public final int compare(DataValueDescriptor other) {
    if (this == other) {
      return 0;
    }
    return this.hashCode() - other.hashCode();
  }
  @Override
  public DataValueDescriptor recycle() {
    return this;
  }
  @Override
  public DataValueDescriptor getNewNull() {
    return DataValueFactory.DUMMY;
  }
  @Override
  public boolean isNull() {
    return this == DataValueFactory.DUMMY;
  }
  @Override
  public Object getObject() throws StandardException {
    return this;
  }
  @Override
  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean nullsOrderedLow, boolean unknownRV)
          throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue equals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public int getLengthInBytes(DataTypeDescriptor dtd) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue greaterThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue in(DataValueDescriptor left,
      DataValueDescriptor[] inList, boolean orderedList)
          throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue isNotNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue isNullOp() {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue lessOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public BooleanDataValue lessThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public void normalize(DataTypeDescriptor dtd, DataValueDescriptor source)
      throws StandardException {
  }
  @Override
  public BooleanDataValue notEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
  ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public void setValue(DataValueDescriptor theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new UnsupportedOperationException("unexpected invocation for " + toString());
  }
  @Override
  public final DataValueDescriptor getKeyColumn(int index) {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public final void getKeyColumns(DataValueDescriptor[] keys) {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int colIdx, boolean orderedNulls, boolean unknownRV)
          throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public boolean compare(int op, CompactCompositeKey key, int colIdx,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public int equals(RowFormatter rf, byte[] bytes, boolean isKeyBytes,
      int logicalPosition, int keyBytesPos, final DataValueDescriptor[] outDVD)
          throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
  @Override
  public byte getTypeId() {
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }
  @Override
  public void writeNullDVD(DataOutput out) throws IOException{
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }
  @Override
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner) {
    return this;
  }
  @Override
  public Object getValueOrOffHeapEntry(LocalRegion owner) {
    return this;
  }
  @Override
  public Object getRawValue() {
    Object val = OffHeapRegionEntryHelper._getValueRetain(this, false);
    if (val != null && !Token.isInvalidOrRemoved(val)
        && val != Token.NOT_AVAILABLE) {
      CachedDeserializable storedObject = (CachedDeserializable) val;
      return storedObject.getDeserializedValue(null, this);
    }
    return null;
  }
  @Override
  public Object prepareValueForCache(RegionEntryContext r, Object val,
      boolean isEntryUpdate, boolean valHasMetadataForGfxdOffHeapUpdate) {
    if (okToStoreOffHeap(val)
        && OffHeapRegionEntryUtils.isValidValueForGfxdOffHeapStorage(val)) {
      if (isEntryUpdate
      ) {
        return OffHeapRegionEntryUtils.prepareValueForUpdate(this, r, val, valHasMetadataForGfxdOffHeapUpdate);
      } else {
        return OffHeapRegionEntryUtils.prepareValueForCreate(r, val, false);
      }
    }
    return super.prepareValueForCache(r, val, isEntryUpdate, valHasMetadataForGfxdOffHeapUpdate);
  }
  @Override
  public boolean destroy(LocalRegion region, EntryEventImpl event,
      boolean inTokenMode, boolean cacheWrite, @Unretained Object expectedOldValue,
      boolean forceDestroy, boolean removeRecoveredEntry)
      throws CacheWriterException, EntryNotFoundException, TimeoutException,
      RegionClearedException {
    Object key = event.getKey();
    if (key instanceof CompactCompositeRegionKey) {
      byte[] keyBytes = ((CompactCompositeRegionKey)key)
          .snapshotKeyFromValue(false);
      if (keyBytes != null) {
        this._setRawKey(keyBytes);
      }
    }
    return super.destroy(region, event, inTokenMode, cacheWrite,
        expectedOldValue, forceDestroy, removeRecoveredEntry);
  }
  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
  private final int bucketId;
  @Override
  public Object getValue(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValue(baseContainer, this.bucketId, this);
  }
  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValueWithoutFaultIn(baseContainer,this.bucketId, this);
  }
  @Override
  public ExecRow getRow(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRow(baseContainer, this.bucketId, this, this.tableInfo);
  }
  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRowWithoutFaultIn(baseContainer, this.bucketId, this,
        this.tableInfo);
  }
  @Override
  public int getBucketID() {
    return this.bucketId;
  }
  @Override
  protected StringBuilder appendFieldsToString(final StringBuilder sb) {
    sb.append("key=");
    final Object k = getKeyCopy();
    final Object val = OffHeapRegionEntryUtils.getHeapRowForInVMValue(this);
    RegionEntryUtils.entryKeyString(k, val, getTableInfo(null), sb);
    sb.append("; byte source = "+ this._getValue());
    sb.append("; rawValue=");
    ArrayUtils.objectStringNonRecursive(val, sb);
    sb.append("; lockState=0x").append(Integer.toHexString(getState()));
    sb.append("; bucketId=").append(this.bucketId);
    return sb;
  }
  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new VMBucketRowLocationThinDiskLRURegionEntryOffHeap(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return VMBucketRowLocationThinDiskLRURegionEntryOffHeap.class;
    }
    public RegionEntryFactory makeVersioned() {
      return VersionedBucketRowLocationThinDiskLRURegionEntryOffHeap.getEntryFactory();
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
      return VMBucketRowLocationThinDiskLRURegionEntryHeap.getEntryFactory();
    }
  };
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }
}
