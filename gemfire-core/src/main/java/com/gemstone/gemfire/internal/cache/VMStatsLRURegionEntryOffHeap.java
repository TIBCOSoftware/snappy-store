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
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;
import com.gemstone.gemfire.internal.cache.lru.NewLRUClockHand;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
@SuppressWarnings("serial")
public class VMStatsLRURegionEntryOffHeap extends VMStatsLRURegionEntry
    implements OffHeapRegionEntry
{
  public VMStatsLRURegionEntryOffHeap (RegionEntryContext context, Object key,
    @Retained
    Object value
      ) {
    super(context,
          value
        );
    this.key = key;
  }
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<VMStatsLRURegionEntryOffHeap> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(VMStatsLRURegionEntryOffHeap.class, "lastModified");
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
  @Override
  public final void setDelayedDiskId(LocalRegion r) {
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
  private static final AtomicIntegerFieldUpdater<VMStatsLRURegionEntryOffHeap> hitCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VMStatsLRURegionEntryOffHeap.class, "hitCount");
  private static final AtomicIntegerFieldUpdater<VMStatsLRURegionEntryOffHeap> missCountUpdater
    = AtomicUpdaterFactory.newIntegerFieldUpdater(VMStatsLRURegionEntryOffHeap.class, "missCount");
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
  @Retained @Released private volatile long ohAddress;
  private final static AtomicLongFieldUpdater<VMStatsLRURegionEntryOffHeap> ohAddrUpdater =
      AtomicUpdaterFactory.newLongFieldUpdater(VMStatsLRURegionEntryOffHeap.class, "ohAddress");
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
  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new VMStatsLRURegionEntryOffHeap(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return VMStatsLRURegionEntryOffHeap.class;
    }
    public RegionEntryFactory makeVersioned() {
      return VMStatsLRURegionEntryOffHeap.getEntryFactory();
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
      return VMStatsLRURegionEntryHeap.getEntryFactory();
    }
  };
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }
}
