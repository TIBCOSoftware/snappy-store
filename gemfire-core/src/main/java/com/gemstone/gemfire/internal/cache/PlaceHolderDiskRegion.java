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
package com.gemstone.gemfire.internal.cache;

import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.lru.LRUStatistics;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;

/**
   * Used to represent a recovered disk region. Once the region actually exists
   * this instance will be thrown away and a real DiskRegion instance will replace it.
   * This class needs to keep track of any information that can be recovered
   * from the DiskInitFile.
   *
   * @author Darrel Schneider
   *
   * @since prPersistSprint2
   */
  public final class PlaceHolderDiskRegion extends AbstractDiskRegion
      implements DiskRecoveryStore {
    private final String name;
    private volatile long entryOverHead = -1L;
    private StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();

    /**
     * This constructor is used when creating a region found during recovery
     * of the DiskInitFile.
     */
    PlaceHolderDiskRegion(DiskStoreImpl ds, long id, String name) {
      super(ds, id, name);
      this.name = name;
      // do the remaining init in setConfig
    }

    /**
     * This constructor is used when we are closing an existing region.
     * We want to remember that it is still present in the disk store.
     */
    PlaceHolderDiskRegion(DiskRegionView drv) {
      super(drv);
      this.name = drv.getName();
    }

    LRUStatistics prlruStats;

    public LRUStatistics getPRLRUStats() {
      return this.prlruStats;
    }
    
    @Override
    public final String getName() {
      return this.name;
    }

    @Override
    public String getPrName() {
      assert isBucket();
      String bn = PartitionedRegionHelper.getBucketName(this.name);
      return PartitionedRegionHelper.getPRPath(bn);
    }

    @Override
    void beginDestroyRegion(LocalRegion region) {
      // nothing needed
    }
    public void finishPendingDestroy() {
      // nothing needed
    }
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("name=").append(this.name)
        .append(" id=").append(getId())
//         .append(" ").append(super.toString())
        ;
      return sb.toString();
    }

    // DiskRecoveryStore methods 
    public DiskRegionView getDiskRegionView() {
      return this;
    }

    public DiskEntry getDiskEntry(Object key) {
      RegionEntry re = getRecoveredEntryMap().getEntry(key);
      if (re != null && re.isRemoved() && !re.isTombstone()) {
        re = null;
      }
      return (DiskEntry)re;
    }
    public DiskEntry initializeRecoveredEntry(Object key, DiskEntry.RecoveredEntry value) {
      RegionEntry re = getRecoveredEntryMap().initRecoveredEntry(key, value);
      if (!(re.isTombstone() || re.isInvalidOrRemoved()) && callback.isSnappyStore()) {
        long size = calculateEntryOverhead(re);
        boolean success =
            callback.acquireStorageMemory(getFullPath(), size, null, false, false);
        if (!success){
          Set<DistributedMember> sm = Collections.singleton(GemFireCacheImpl.getExisting().getMyId());
          throw new LowMemoryException("Could not obtain memory of size " + size, sm);
        }
      }
      if (re == null) {
        throw new InternalGemFireError(LocalizedStrings.LocalRegion_ENTRY_ALREADY_EXISTED_0.toLocalizedString(key));
      }
      return (DiskEntry)re;
    }

    public DiskEntry updateRecoveredEntry(Object key, RegionEntry entry,
        DiskEntry.RecoveredEntry value) {
      return (DiskEntry)getRecoveredEntryMap().updateRecoveredEntry(key, entry, value);
    }
    public void destroyRecoveredEntry(Object key) {
      throw new IllegalStateException("destroyRecoveredEntry should not be called during recovery");
    }
    public void copyRecoveredEntries(RegionMap rm, boolean entriesIncompatible) {
      throw new IllegalStateException("copyRecoveredEntries should not be called during recovery");
    }
    public void foreachRegionEntry(LocalRegion.RegionEntryCallback callback) {
      throw new IllegalStateException("foreachRegionEntry should not be called during recovery");
    }
    public boolean lruLimitExceeded() {
      return getRecoveredEntryMap().lruLimitExceeded();
    }

    public DiskStoreID getDiskStoreID() {
      return getDiskStore().getDiskStoreID();
    }

    public void acquireReadLock() {
      //not needed. The only thread
      //using this method is the async recovery thread.
      //If this placeholder is replaced with a real region,
      //this is done under a different lock
      
    }
    
    public void releaseReadLock() {
      //not needed
    }

    public void updateSizeOnFaultIn(Object key, int newSize, int bytesOnDisk) {
      //only used by bucket regions
    }
    @Override
    public int calculateValueSize(Object val) {
      return 0;
    }
    @Override
    public int calculateRegionEntryValueSize(RegionEntry re) {
      return 0;
    }

    public RegionMap getRegionMap() {
      return getRecoveredEntryMap();
    }

    public void handleDiskAccessException(DiskAccessException dae, boolean b) {
      getDiskStore()
          .getCache()
          .getLoggerI18n()
          .error(
              LocalizedStrings.PlaceHolderDiskRegion_A_DISKACCESSEXCEPTION_HAS_OCCURED_WHILE_RECOVERING_FROM_DISK,
              getName(), dae);
      
    }

    public boolean didClearCountChange() {
      return false;
    }

    public CancelCriterion getCancelCriterion() {
      return getDiskStore().getCancelCriterion();
    }

    public boolean isSync() {
      return true;
    }

    public void endRead(long start, long end, long bytesRead) {
      //do nothing
    }

    public boolean isRegionClosed() {
      return false;
    }

    public void initializeStats(long numEntriesInVM, long numOverflowOnDisk,
        long numOverflowBytesOnDisk) {
      this.numEntriesInVM.set(numEntriesInVM);
      this.numOverflowOnDisk.set(numOverflowOnDisk);
      this.numOverflowBytesOnDisk.set(numOverflowBytesOnDisk);
    }

    protected long calculateEntryOverhead(RegionEntry entry) {
      if (entryOverHead == -1L && callback.isSnappyStore()) {
            entryOverHead = getEntryOverhead(entry);
      }
      return entryOverHead;
    }

    private long getEntryOverhead(RegionEntry entry) {
      long entryOverhead = ReflectionSingleObjectSizer.INSTANCE.sizeof(entry);
      Object key = entry.getRawKey();
      if (key != null) {
        entryOverhead += CachedDeserializableFactory.calcMemSize(key);
      }
      if (entry instanceof DiskEntry) {
        DiskId diskId = ((DiskEntry)entry).getDiskId();
        if (diskId != null) {
          entryOverhead += ReflectionSingleObjectSizer.INSTANCE.sizeof(diskId);
        }
      }
      return entryOverhead;
    }
  }
