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
package com.gemstone.gemfire.internal.cache;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;
import com.gemstone.gemfire.internal.cache.lru.NewLRUClockHand;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
@SuppressWarnings("serial")
public class VMStatsDiskLRURegionEntryHeap extends VMStatsDiskLRURegionEntry
{
  public VMStatsDiskLRURegionEntryHeap (RegionEntryContext context, Object key,
    Object value
      ) {
    super(context,
          (value instanceof RecoveredEntry ? null : value)
        );
    initialize(context, value);
    this.key = key;
  }
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<VMStatsDiskLRURegionEntryHeap> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(VMStatsDiskLRURegionEntryHeap.class, "lastModified");
  protected long getlastModifiedField() {
    return lastModifiedUpdater.get(this);
  }
  protected final boolean compareAndSetLastModifiedField(long expectedValue,
      long newValue) {
    return lastModifiedUpdater.compareAndSet(this, expectedValue, newValue);
  }
  @Override
  public final int getEntryHash() {
    return this.hash;
  }
  @Override
  protected final void setEntryHash(int v) {
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
  protected final void initialize(RegionEntryContext drs, Object value) {
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
  public final DiskId getDiskId() {
    return this.id;
  }
  @Override
  public final void setDiskId(RegionEntry old) {
    this.id = ((AbstractDiskRegionEntry)old).getDiskId();
  }
  @Override
  public final void setDelayedDiskId(LocalRegion r) {
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
  private static final AtomicIntegerFieldUpdater<VMStatsDiskLRURegionEntryHeap> hitCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VMStatsDiskLRURegionEntryHeap.class, "hitCount");
  private static final AtomicIntegerFieldUpdater<VMStatsDiskLRURegionEntryHeap> missCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VMStatsDiskLRURegionEntryHeap.class, "missCount");
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
  private Object key;
  @Override
  public final Object getRawKey() {
    return this.key;
  }
  @Override
  protected final void _setRawKey(Object key) {
    this.key = key;
  }
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
  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new VMStatsDiskLRURegionEntryHeap(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return VMStatsDiskLRURegionEntryHeap.class;
    }
    public RegionEntryFactory makeVersioned() {
      return VMStatsDiskLRURegionEntryHeap.getEntryFactory();
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
      return this;
    }
  };
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }
}
