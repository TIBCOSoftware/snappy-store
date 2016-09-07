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

package com.pivotal.gemfirexd.internal.engine.store.entry;
// DO NOT modify this class. It was generated from LeafRegionEntry.cpp


import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;







import com.gemstone.gemfire.cache.EntryEvent;


import com.gemstone.gemfire.internal.cache.lru.EnableLRU;





import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;


import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;

import com.gemstone.gemfire.internal.cache.lru.NewLRUClockHand;



import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;

import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;




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
// macros whose definition changes this class:
// disk: DISK
// lru: LRU
// stats: STATS
// versioned: VERSIONED
// offheap: OFFHEAP
// rowlocation: ROWLOCATION
// local: LOCAL
// bucket: BUCKET
// package: PKG
/**
 * Do not modify this class. It was generated.
 * Instead modify LeafRegionEntry.cpp and then run
 * bin/generateRegionEntryClasses.sh from the directory
 * that contains your build.xml.
 */
public class VersionedLocalRowLocationStatsLRURegionEntryHeap extends RowLocationStatsLRURegionEntry
    implements VersionStamp
{
  public VersionedLocalRowLocationStatsLRURegionEntryHeap (RegionEntryContext context, Object key,
    Object value
      ) {
    super(context,
          value
        );
    // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
    this.tableInfo = RegionEntryUtils.entryGetTableInfo(context, key, value);
    this.key = RegionEntryUtils.entryGetRegionKey(key, value);
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  // common code
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<VersionedLocalRowLocationStatsLRURegionEntryHeap> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(VersionedLocalRowLocationStatsLRURegionEntryHeap.class, "lastModified");
  protected long getlastModifiedField() {
    return lastModifiedUpdater.get(this);
  }
  protected boolean compareAndSetLastModifiedField(long expectedValue, long newValue) {
    return lastModifiedUpdater.compareAndSet(this, expectedValue, newValue);
  }
  /**
   * @see HashEntry#getEntryHash()
   */
  @Override
  public final int getEntryHash() {
    return this.hash;
  }
  @Override
  protected void setEntryHash(int v) {
    this.hash = v;
  }
  /**
   * @see HashEntry#getNextEntry()
   */
  @Override
  public final HashEntry<Object, Object> getNextEntry() {
    return this.next;
  }
  /**
   * @see HashEntry#setNextEntry
   */
  @Override
  public final void setNextEntry(final HashEntry<Object, Object> n) {
    this.next = n;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  // lru code
  @Override
  public void setDelayedDiskId(LocalRegion r) {
  // nothing needed for LRUs with no disk
  }
  public final synchronized int updateEntrySize(EnableLRU capacityController) {
    return updateEntrySize(capacityController, _getValue()); // OFHEAP: _getValue ok w/o incing refcount because we are synced and only getting the size
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  public final synchronized int updateEntrySize(EnableLRU capacityController,
                                                Object value) {
    int oldSize = getEntrySize();
    int newSize = capacityController.entrySize(getRawKey(), value);
  //   GemFireCacheImpl.getInstance().getLoggerI18n().info("DEBUG updateEntrySize: oldSize=" + oldSize
  //                                               + " newSize=" + newSize);
    setEntrySize(newSize);
    int delta = newSize - oldSize;
  //   if ( debug ) log( "updateEntrySize key=" + getRawKey()
  //                     + (_getValue() == Token.INVALID ? " invalid" :
  //                        (_getValue() == Token.LOCAL_INVALID ? "local_invalid" :
  //                         (_getValue()==null ? " evicted" : " valid")))
  //                     + " oldSize=" + oldSize
  //                     + " newSize=" + this.size );
    return delta;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  private LRUClockNode nextLRU;
  private LRUClockNode prevLRU;
  //private int refCount;
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
  /*
  public final synchronized int getRefCount() {
    return this.refCount;
  }
  public final synchronized void incRefCount() {
    this.refCount++;
    // removal from the LruList is performed as part
    // of the eviction process (getHeadEntry())
  }

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  public final synchronized void decRefCount(NewLRUClockHand lruList) {
    if (this.refCount > 0) {
      this.refCount--;
      if (this.refCount == 0) {
        // No more transactions, place in lru list
        lruList.appendEntry(this);
      }
    }
  }
  public final synchronized void resetRefCount(NewLRUClockHand lruList) {
    if (this.refCount > 0) {
      this.refCount = 0;
      lruList.appendEntry(this);
    }
  }
  */
//@Override
//public StringBuilder appendFieldsToString(final StringBuilder sb) {
//  StringBuilder result = super.appendFieldsToString(sb);
//  result.append("; prev=").append(this.prevLRU==null?"null":"not null");
//  result.append("; next=").append(this.nextLRU==null?"null":"not null");
//  return result;
//}
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  // stats code
  @Override
  public final void updateStatsForGet(boolean hit, long time)
  {
    setLastAccessed(time);
    if (hit) {
      incrementHitCount();
    } else {
      incrementMissCount();
    }
  }
  @Override
  public final void setLastModified(long lastModified) {
    _setLastModified(lastModified);
    if (!DISABLE_ACCESS_TIME_UPDATE_ON_PUT) {
      setLastAccessed(lastModified);
    }
  }
  private volatile long lastAccessed;
  private volatile int hitCount;
  private volatile int missCount;
  private static final AtomicIntegerFieldUpdater<VersionedLocalRowLocationStatsLRURegionEntryHeap> hitCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VersionedLocalRowLocationStatsLRURegionEntryHeap.class, "hitCount");
  private static final AtomicIntegerFieldUpdater<VersionedLocalRowLocationStatsLRURegionEntryHeap> missCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VersionedLocalRowLocationStatsLRURegionEntryHeap.class, "missCount");
  @Override
  public final long getLastAccessed() throws InternalStatisticsDisabledException {
    return this.lastAccessed;
  }
  private void setLastAccessed(long lastAccessed) {
    this.lastAccessed = lastAccessed;
  }
  @Override
  public final long getHitCount() throws InternalStatisticsDisabledException {
    return this.hitCount & 0xFFFFFFFFL;
  }
  @Override
  public final long getMissCount() throws InternalStatisticsDisabledException {
    return this.missCount & 0xFFFFFFFFL;
  }
  private void incrementHitCount() {
    hitCountUpdater.incrementAndGet(this);
  }
  private void incrementMissCount() {
    missCountUpdater.incrementAndGet(this);
  }
  @Override
  public final void resetCounts() throws InternalStatisticsDisabledException {
    hitCountUpdater.set(this,0);
    missCountUpdater.set(this,0);
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  @Override
  public final void txDidDestroy(long currTime) {
    setLastModified(currTime);
    setLastAccessed(currTime);
    this.hitCount = 0;
    this.missCount = 0;
  }
  @Override
  public boolean hasStats() {
    return true;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  // versioned code
  private VersionSource memberID;
  private short entryVersionLowBytes;
  private short regionVersionHighBytes;
  private int regionVersionLowBytes;
  private byte entryVersionHighByte;
  private byte distributedSystemId;
  public int getEntryVersion() {
    return ((entryVersionHighByte << 16) & 0xFF0000) | (entryVersionLowBytes & 0xFFFF);
  }
  public long getRegionVersion() {
    return (((long)regionVersionHighBytes) << 32) | (regionVersionLowBytes & 0x00000000FFFFFFFFL);
  }
  public long getVersionTimeStamp() {
    return getLastModified();
  }
  public void setVersionTimeStamp(long time) {
    setLastModified(time);
  }
  public VersionSource getMemberID() {
    return this.memberID;
  }
  public int getDistributedSystemId() {
    return this.distributedSystemId;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  public void setVersions(VersionTag tag) {
    this.memberID = tag.getMemberID();
    int eVersion = tag.getEntryVersion();
    this.entryVersionLowBytes = (short)(eVersion & 0xffff);
    this.entryVersionHighByte = (byte)((eVersion & 0xff0000) >> 16);
    this.regionVersionHighBytes = tag.getRegionVersionHighBytes();
    this.regionVersionLowBytes = tag.getRegionVersionLowBytes();
    if (!(tag.isGatewayTag()) && this.distributedSystemId == tag.getDistributedSystemId()) {
      if (getVersionTimeStamp() <= tag.getVersionTimeStamp()) {
        setVersionTimeStamp(tag.getVersionTimeStamp());
      } else {
        tag.setVersionTimeStamp(getVersionTimeStamp());
      }
    } else {
      setVersionTimeStamp(tag.getVersionTimeStamp());
    }
    this.distributedSystemId = (byte)(tag.getDistributedSystemId() & 0xff);
  }
  public void setMemberID(VersionSource memberID) {
    this.memberID = memberID;
  }
  @Override
  public VersionStamp getVersionStamp() {
    return this;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  public VersionTag asVersionTag() {
    VersionTag tag = VersionTag.create(memberID);
    tag.setEntryVersion(getEntryVersion());
    tag.setRegionVersion(this.regionVersionHighBytes, this.regionVersionLowBytes);
    tag.setVersionTimeStamp(getVersionTimeStamp());
    tag.setDistributedSystemId(this.distributedSystemId);
    return tag;
  }
  public void processVersionTag(LocalRegion r, VersionTag tag,
      boolean isTombstoneFromGII, boolean hasDelta,
      VersionSource thisVM, InternalDistributedMember sender, boolean checkForConflicts) {
    basicProcessVersionTag(r, tag, isTombstoneFromGII, hasDelta, thisVM, sender, checkForConflicts);
  }
  @Override
  public void processVersionTag(EntryEvent cacheEvent) {
    // this keeps Eclipse happy.  without it the sender chain becomes confused
    // while browsing this code
    super.processVersionTag(cacheEvent);
  }
  /** get rvv internal high byte.  Used by region entries for transferring to storage */
  public short getRegionVersionHighBytes() {
    return this.regionVersionHighBytes;
  }
  /** get rvv internal low bytes.  Used by region entries for transferring to storage */
  public int getRegionVersionLowBytes() {
    return this.regionVersionLowBytes;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  // key code
  private Object key;
  @Override
  public final Object getRawKey() {
    return this.key;
  }
  @Override
  protected void _setRawKey(Object key) {
    this.key = key;
  }
  private volatile Object value;
  @Override
  protected Object getValueField() {
    return this.value;
  }
  @Override
  protected void setValueField(Object v) {
    this.value = v;
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
      // cleanup the key if required
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
    // just use some arbitrary criteria like hashCode for ordering
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
  // Unimplemented methods not expected to be invoked
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
    return this.getHeapValueInVMOrDiskWithoutFaultIn(owner);
  }
  @Override
  public Object getValueOrOffHeapEntry(LocalRegion owner) {
    return this.getValue(owner);
  }
  @Override
  public Object getRawValue() {
    return this._getValue();
  }
  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
  @Override
  public Object getValue(GemFireContainer baseContainer) {
     return RegionEntryUtils.getValue(baseContainer.getRegion(), this);
  }
  @Override
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValueWithoutFaultIn(baseContainer.getRegion(), this);
  }
  @Override
  public ExecRow getRow(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRow(baseContainer, baseContainer.getRegion(), this, this.tableInfo);
  }
  @Override
  public ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRowWithoutFaultIn(baseContainer, baseContainer.getRegion(), this, this.tableInfo);
  }
  @Override
  public int getBucketID() {
    return -1;
  }
  @Override
  protected StringBuilder appendFieldsToString(final StringBuilder sb) {
    sb.append("key=");
    // OFFHEAP _getValue ok: the current toString on OffHeapCachedDeserializable
    // is safe to use without incing refcount.
    final Object k = getRawKey();
    final Object val = _getValue();
    RegionEntryUtils.entryKeyString(k, val, getTableInfo(null), sb);
    sb.append("; rawValue=");
    ArrayUtils.objectStringNonRecursive(val, sb);
    sb.append("; lockState=0x").append(Integer.toHexString(getState()));
    return sb;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new VersionedLocalRowLocationStatsLRURegionEntryHeap(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return VersionedLocalRowLocationStatsLRURegionEntryHeap.class;
    }
    public RegionEntryFactory makeVersioned() {
      return this;
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
      return this;
    }
  };
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
}
