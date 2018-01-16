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

package PKG;
// DO NOT modify this class. It was generated from LeafRegionEntry.cpp

#if defined(STATS)
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
#endif
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
#ifdef OFFHEAP
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
#endif
#if defined(VERSIONED)
import com.gemstone.gemfire.cache.EntryEvent;
#endif
#if defined(DISK) || defined(LRU)
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
#endif
#ifdef DISK
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
#endif
#ifdef STATS
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
#endif
#ifdef LRU
import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;
#ifdef STATS
import com.gemstone.gemfire.internal.cache.lru.NewLRUClockHand;
#endif
#endif
#ifdef VERSIONED
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
#endif
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
#if defined(DISK) && defined(LRU)
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
#endif
#ifdef ROWLOCATION
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
#ifdef OFFHEAP
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
#endif
#ifdef DISK
import com.gemstone.gemfire.internal.cache.DiskId;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.AbstractDiskRegionEntry;
#endif
#if defined(DISK) && defined(LRU)
import com.gemstone.gemfire.internal.cache.PlaceHolderDiskRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
#endif
#endif

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
#if !defined(ROWLOCATION)
@SuppressWarnings("serial")
#endif
public class LEAF_CLASS extends PARENT_CLASS
#if defined(OFFHEAP) && defined(VERSIONED)
    implements OffHeapRegionEntry, VersionStamp
#else
#ifdef OFFHEAP
    implements OffHeapRegionEntry
#endif
#ifdef VERSIONED
    implements VersionStamp
#endif
#endif
{
  public LEAF_CLASS  (RegionEntryContext context, Object key,
#ifdef OFFHEAP
    @Retained 
#endif
    Object value
      ) {
    super(context, 
#ifdef DISK
          (value instanceof RecoveredEntry ? null : value)
#else
          value
#endif
        );
    // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
#ifdef DISK
    initialize(context, value);
#endif
#ifdef ROWLOCATION
    this.tableInfo = RegionEntryUtils.entryGetTableInfo(context, key, value);
#ifdef BUCKET
    this.bucketId = RegionEntryUtils.getBucketId(context);
#endif
#endif
#ifdef ROWLOCATION
    this.key = RegionEntryUtils.entryGetRegionKey(key, value);
#else
    this.key = key;
#endif    
  }

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  // common code
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<LEAF_CLASS> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(LEAF_CLASS.class, "lastModified");
  protected long getlastModifiedField() {
    return lastModifiedUpdater.get(this);
  }
  protected final boolean compareAndSetLastModifiedField(long expectedValue,
      long newValue) {
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
  protected final void setEntryHash(int v) {
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
#ifdef DISK

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  // disk code
#ifdef LRU
  protected final void initialize(RegionEntryContext drs, Object value) {
    boolean isBackup;
    if (drs instanceof LocalRegion) {
      isBackup = ((LocalRegion)drs).getDiskRegion().isBackup();
    } else if (drs instanceof PlaceHolderDiskRegion) {
      isBackup = true;
    } else {
      throw new IllegalArgumentException("expected a LocalRegion or PlaceHolderDiskRegion");
    }
    // Delay the initialization of DiskID if overflow only
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
          /* reduce the ExtraInfo reference size */
          return size - ReflectionSingleObjectSizer.REFERENCE_SIZE;
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("DiskLRU RegionEntry#getKeySize");
    }
  }
#else
  protected final void initialize(RegionEntryContext context, Object value) {
    diskInitialize(context, value);
  }
  @Override
  public final int updateAsyncEntrySize(EnableLRU capacityController) {
    throw new IllegalStateException("should never be called");
  }
#endif

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  private void diskInitialize(RegionEntryContext context, Object value) {
    DiskRecoveryStore drs = (DiskRecoveryStore)context;
    DiskStoreImpl ds = drs.getDiskStore();
    long maxOplogSize = ds.getMaxOplogSize();
    //get appropriate instance of DiskId implementation based on maxOplogSize
    this.id = DiskId.createDiskId(maxOplogSize, true/* is persistence */, ds.needsLinkedList());
    Helper.initialize(this, drs, value);
  }

  /**
   * DiskId
   * 
   * @since 5.1
   */
  protected DiskId id;//= new DiskId();
  public final DiskId getDiskId() {
    return this.id;
  }
  @Override
  public final void setDiskId(RegionEntry old) {
    this.id = ((AbstractDiskRegionEntry)old).getDiskId();
  }
#endif
  
#ifdef LRU
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  // lru code
  @Override
  public final void setDelayedDiskId(LocalRegion r) {
#ifdef DISK
    DiskStoreImpl ds = r.getDiskStore();
    long maxOplogSize = ds.getMaxOplogSize();
    this.id = DiskId.createDiskId(maxOplogSize, false /* over flow only */, ds.needsLinkedList());
#else
  // nothing needed for LRUs with no disk
#endif
  }
  public final synchronized int updateEntrySize(EnableLRU capacityController) {
    return updateEntrySize(capacityController, _getValue());  // OFHEAP: _getValue ok w/o incing refcount because we are synced and only getting the size
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
  
#endif

#ifdef STATS
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
  
  private static final AtomicIntegerFieldUpdater<LEAF_CLASS> hitCountUpdater 
    = AtomicUpdaterFactory.newIntegerFieldUpdater(LEAF_CLASS.class, "hitCount");
  private static final AtomicIntegerFieldUpdater<LEAF_CLASS> missCountUpdater 
    = AtomicUpdaterFactory.newIntegerFieldUpdater(LEAF_CLASS.class, "missCount");
  
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
  public final boolean hasStats() {
    return true;
  }
#endif
  
#ifdef VERSIONED
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  // versioned code
  private VersionSource memberID;
  private short entryVersionLowBytes;
  private short regionVersionHighBytes;
  private int regionVersionLowBytes;
  private byte entryVersionHighByte;
  private byte distributedSystemId;

  public final int getEntryVersion() {
    return ((entryVersionHighByte << 16) & 0xFF0000) | (entryVersionLowBytes & 0xFFFF);
  }
  
  public final long getRegionVersion() {
    return (((long)regionVersionHighBytes) << 32) | (regionVersionLowBytes & 0x00000000FFFFFFFFL);  
  }
  
  
  public final long getVersionTimeStamp() {
    return getLastModified();
  }
  
  public final void setVersionTimeStamp(long time) {
    setLastModified(time);
  }

  public final VersionSource getMemberID() {
    return this.memberID;
  }
  public final int getDistributedSystemId() {
    return this.distributedSystemId;
  }

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  public final void setVersions(VersionTag tag) {
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

  public final void setMemberID(VersionSource memberID) {
    this.memberID = memberID; 
  }

  @Override
  public final VersionStamp getVersionStamp() {
    return this;
  }

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  public final VersionTag asVersionTag() {
    VersionTag tag = VersionTag.create(memberID);
    tag.setEntryVersion(getEntryVersion());
    tag.setRegionVersion(this.regionVersionHighBytes, this.regionVersionLowBytes);
    tag.setVersionTimeStamp(getVersionTimeStamp());
    tag.setDistributedSystemId(this.distributedSystemId);
    return tag;
  }

  public final void processVersionTag(LocalRegion r, VersionTag tag,
      boolean isTombstoneFromGII, boolean hasDelta,
      VersionSource thisVM, InternalDistributedMember sender, boolean checkForConflicts) {
    basicProcessVersionTag(r, tag, isTombstoneFromGII, hasDelta, thisVM, sender, checkForConflicts);
  }

  @Override
  public final void processVersionTag(EntryEvent cacheEvent) {
    // this keeps Eclipse happy.  without it the sender chain becomes confused
    // while browsing this code
    super.processVersionTag(cacheEvent);
  }

  /** get rvv internal high byte.  Used by region entries for transferring to storage */
  public final short getRegionVersionHighBytes() {
    return this.regionVersionHighBytes;
  }
  
  /** get rvv internal low bytes.  Used by region entries for transferring to storage */
  public final int getRegionVersionLowBytes() {
    return this.regionVersionLowBytes;
  }
#endif
  
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
  
  // key code
  private Object key;
  @Override
  public final Object getRawKey() {
    return this.key;
  }
  @Override
  protected final void _setRawKey(Object key) {
    this.key = key;
  }
  
#ifdef OFFHEAP
  /**
   * All access done using ohAddrUpdater so it is used even though the compiler can not tell it is.
   */
  @Retained @Released private volatile long ohAddress;
  /**
   * I needed to add this because I wanted clear to call setValue which normally can only be called while the re is synced.
   * But if I sync in that code it causes a lock ordering deadlock with the disk regions because they also get a rw lock in clear.
   * Some hardware platforms do not support CAS on a long. If gemfire is run on one of those the AtomicLongFieldUpdater does a sync
   * on the re and we will once again be deadlocked.
   * I don't know if we support any of the hardware platforms that do not have a 64bit CAS. If we do then we can expect deadlocks
   * on disk regions.
   */
  private final static AtomicLongFieldUpdater<LEAF_CLASS> ohAddrUpdater =
      AtomicUpdaterFactory.newLongFieldUpdater(LEAF_CLASS.class, "ohAddress");
  
  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp

  @Override
  public final boolean isOffHeap() {
    return true;
  }

  @Override
  public final Token getValueAsToken() {
    return OffHeapRegionEntryHelper.getValueAsToken(this);
  }
  
  @Override
  @Unretained
  protected final Object getValueField() {
    return OffHeapRegionEntryHelper._getValue(this);
  }

  @Override
  protected final void setValueField(@Unretained Object v) {
    OffHeapRegionEntryHelper.setValue(this, v);
  }

  @Override
  @Retained
  public final Object _getValueRetain(RegionEntryContext context,
      boolean decompress) {
    return OffHeapRegionEntryHelper._getValueRetain(this, decompress);
  }

  @Override
  public final long getAddress() {
    return ohAddrUpdater.get(this);
  }

  @Override
  public final boolean setAddress(long expectedAddr, long newAddr) {
    return ohAddrUpdater.compareAndSet(this, expectedAddr, newAddr);
  }
  
  @Override
  @Released
  public final void release() {
    OffHeapRegionEntryHelper.releaseEntry(this);
  }
#else
  private volatile Object value;

  @Override
  public final boolean isRemoved() {
    final Object o = this.value;
    return (o == Token.REMOVED_PHASE1) || (o == Token.REMOVED_PHASE2) || (o == Token.TOMBSTONE);
  }

  @Override
  public final boolean isDestroyedOrRemoved() {
    final Object o = this.value;
    return o == Token.DESTROYED || o == Token.REMOVED_PHASE1 || o == Token.REMOVED_PHASE2 || o == Token.TOMBSTONE;
  }

  @Override
  public final boolean isDestroyedOrRemovedButNotTombstone() {
    final Object o = this.value;
    return o == Token.DESTROYED || o == Token.REMOVED_PHASE1 || o == Token.REMOVED_PHASE2;
  }

  @Override
  protected final Object getValueField() {
    return this.value;
  }

  @Override
  protected final void setValueField(Object v) {
    this.value = v;
  }

  @Override
  public final Token getValueAsToken() {
    Object v = this.value;
    if (v == null) {
      return null;
    } else if (v instanceof Token) {
      return (Token)v;
    } else {
      return Token.NOT_A_TOKEN;
    }
  }

  @Override
  public final boolean isValueNull() {
    return this.value == null;
  }

#endif

#ifdef ROWLOCATION
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
  public final int estimateMemoryUsage() {
    return ClassSize.refSize;
  }

  @Override
  public final int getTypeFormatId() {
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
  public final DataValueDescriptor recycle() {
    return this;
  }

  @Override
  public final DataValueDescriptor getNewNull() {
    return DataValueFactory.DUMMY;
  }

  @Override
  public final boolean isNull() {
    return this == DataValueFactory.DUMMY;
  }

  @Override
  public final Object getObject() throws StandardException {
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
  public  byte getTypeId() {
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }

  @Override
  public  void writeNullDVD(DataOutput out) throws IOException{
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);    
  }


  @Override
  public final Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner) {
#ifdef OFFHEAP
    return this;
#elif defined(DISK)
    final Object value = this.value;
    return value != null && !Token.isRemovedFromDisk(value)
        ? value : Helper.getValueHeapOrDiskWithoutFaultIn(this, owner);
#else
    final Object value = this.value;
    return value != null ? value : Token.NOT_AVAILABLE;
#endif
  }

  @Override
  public final Object getValueOrOffHeapEntry(LocalRegion owner) {
#ifdef OFFHEAP
    return this;
#else
    return this.getValue(owner);
#endif
  }

  @Override
  public final Object getRawValue() {
#ifdef OFFHEAP
    Object val = OffHeapRegionEntryHelper._getValueRetain(this, false);
    if (val != null && !Token.isInvalidOrRemoved(val)
        && val != Token.NOT_AVAILABLE) {
      CachedDeserializable storedObject = (CachedDeserializable) val;
      return storedObject.getDeserializedValue(null, this);

    }
    return null;
#else
    return this._getValue();
#endif
  }
  
#ifdef OFFHEAP
  @Override
  public final Object prepareValueForCache(RegionEntryContext r, Object val,
      boolean isEntryUpdate, boolean valHasMetadataForGfxdOffHeapUpdate) {
    if (okToStoreOffHeap(val)
        && OffHeapRegionEntryUtils.isValidValueForGfxdOffHeapStorage(val)) {
      // TODO:Asif:Check if this is a valid supposition
      // final long address = this.getAddress();
      if (isEntryUpdate
      /*
       * (address == OffHeapRegionEntryHelper.REMOVED_PHASE1_ADDRESS || address
       * == OffHeapRegionEntryHelper.NULL_ADDRESS) || r instanceof
       * PlaceHolderDiskRegion
       */
      ) {
        return OffHeapRegionEntryUtils.prepareValueForUpdate(this, r, val, valHasMetadataForGfxdOffHeapUpdate);
      } else {
        return OffHeapRegionEntryUtils.prepareValueForCreate(r, val, false);
      }

    }
    return super.prepareValueForCache(r, val, isEntryUpdate, valHasMetadataForGfxdOffHeapUpdate);

  }

  @Override
  public final boolean destroy(LocalRegion region, EntryEventImpl event,
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
#endif

  @Override
  public final Version[] getSerializationVersions() {
    return null;
  }
#ifdef LOCAL
  @Override
  public final Object getValue(GemFireContainer baseContainer) {
     return RegionEntryUtils.getValue(baseContainer.getRegion(), this);
  }

  @Override
  public final Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValueWithoutFaultIn(baseContainer.getRegion(), this);
  }

  @Override
  public final ExecRow getRow(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRow(baseContainer, baseContainer.getRegion(), this, this.tableInfo);
  }

  @Override
  public final ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRowWithoutFaultIn(baseContainer, baseContainer.getRegion(), this, this.tableInfo);
  }

  @Override
  public final int getBucketID() {
    return -1;
  }
#endif
#ifdef BUCKET
  private final int bucketId;

  @Override
  public final Object getValue(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValue(baseContainer, this.bucketId, this);
  }

  @Override
  public final Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getValueWithoutFaultIn(baseContainer,this.bucketId, this);
  }

  @Override
  public final ExecRow getRow(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRow(baseContainer, this.bucketId, this, this.tableInfo);
  }

  @Override
  public final ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer) {
    return RegionEntryUtils.getRowWithoutFaultIn(baseContainer, this.bucketId, this,
        this.tableInfo);
  }

  @Override
  public final int getBucketID() {
    return this.bucketId;
  }
#endif

  @Override
  protected StringBuilder appendFieldsToString(final StringBuilder sb) {
    sb.append("key=");
    // OFFHEAP _getValue ok: the current toString on OffHeapCachedDeserializable
    // is safe to use without incing refcount.
#ifdef OFFHEAP
    final Object k = getKeyCopy();
    final Object val = OffHeapRegionEntryUtils.getHeapRowForInVMValue(this);
#else
    final Object k = getRawKey();
    final Object val = _getValue();
#endif
    RegionEntryUtils.entryKeyString(k, val, getTableInfo(null), sb);
#ifdef OFFHEAP
    sb.append("; byte source = "+ this._getValue());
#endif
    sb.append("; rawValue=");
    ArrayUtils.objectStringNonRecursive(val, sb);
    sb.append("; lockState=0x").append(Integer.toHexString(getState()));
#ifdef BUCKET
    sb.append("; bucketId=").append(this.bucketId);
#endif
    return sb;
  }
#endif

  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp

  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new LEAF_CLASS(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return LEAF_CLASS.class;
    }
    public RegionEntryFactory makeVersioned() {
#ifdef VERSIONED
      return this;
#else
      return VERSIONED_CLASS.getEntryFactory();
#endif
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
#ifdef OFFHEAP
      return HEAP_CLASS.getEntryFactory();
#else
      return this;
#endif
    }
  };
  
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }


  // DO NOT modify this class. It was generated from LeafRegionEntry.cpp
}
