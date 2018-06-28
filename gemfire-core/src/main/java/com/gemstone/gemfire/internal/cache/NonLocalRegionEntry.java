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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.StatisticsDisabledException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.FactoryStatics;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.shared.Version;

public class NonLocalRegionEntry implements RegionEntry, VersionStamp {
  protected long lastModified;
  protected boolean isRemoved;
  protected Object key;
  protected Object value;
  private VersionTag<?> versionTag;
  private boolean updateInProgress = false;
  private transient int valueSize;
  private transient boolean forDelete;

  /**
   * Create one of these in the local case so that we have a snapshot of the
   * state and can allow the bucket to move out from under us.
   */
  protected NonLocalRegionEntry(RegionEntry re, LocalRegion br,
      boolean allowTombstones) {
    this.key = re.getKeyCopy();
    // client get() operations need to see tombstone values
    if (allowTombstones && re.isTombstone()) {
      this.value = Token.TOMBSTONE;
    } else {
      this.value = re.getValue(br); // OFFHEAP: copy into heap cd
    }
    Assert.assertTrue(this.value != Token.NOT_AVAILABLE,
        "getEntry did not fault value in from disk");
    this.lastModified = re.getLastModified();
    this.isRemoved = re.isRemoved();
    VersionStamp<?> stamp = re.getVersionStamp();
    if (stamp != null) {
      this.versionTag = stamp.asVersionTag();
    }
  }

  protected NonLocalRegionEntry(RegionEntry re, LocalRegion br,
      boolean allowTombstones, boolean faultInValue) {
    this.key = re.getKeyCopy();
    // client get() operations need to see tombstone values
    if (allowTombstones && re.isTombstone()) {
      this.value = Token.TOMBSTONE;
    } else {
      @Released Object v = null;
      if (faultInValue) {
        v = re.getValue(br);
        // do an additional retain to match the behaviour of
        // getValueInVMOrDiskWithoutFaultIn
        if (GemFireCacheImpl.hasNewOffHeap() &&
            (v instanceof SerializedDiskBuffer)) {
          ((SerializedDiskBuffer)v).retain();
        }
      } else {
        v = re.getValueInVMOrDiskWithoutFaultIn(br);
      }
      try {
        this.value = OffHeapHelper.getHeapForm(v);  // OFFHEAP: copy into heap cd
      } finally {
        OffHeapHelper.release(v);
      }
    }
    Assert.assertTrue(this.value != Token.NOT_AVAILABLE,
        "getEntry did not fault value in from disk");
    this.lastModified = re.getLastModified();
    this.isRemoved = re.isRemoved();
    VersionStamp<?> stamp = re.getVersionStamp();
    if (stamp != null) {
      this.versionTag = stamp.asVersionTag();
    }
  }

  /* If below is enabled then use the factory methods below to work correctly
   * for GemFireXD
   *
   * Create one of these in the local case so that we have a snapshot of the state
   * and can allow the bucket to move out from under us.
   *
  public NonLocalRegionEntry(LocalRegion br,Object key,Object value) {
    this.key = key;
    this.value = value;
    Assert.assertTrue(this.value != Token.NOT_AVAILABLE, "getEntry did not fault value in from disk");
//    this.lastModified = re.getLastModified();
//    this.isRemoved = re.isRemoved();
  }
  */

  /**
   * Create one of these in the local case so that we have a snapshot of the state
   * and can allow the bucket to move out from under us.
   */
  protected NonLocalRegionEntry(Object key, Object value, LocalRegion br,
      VersionTag<?> versionTag) {
    this.key = key;
    this.value = value;
    if (this.value instanceof CachedDeserializable) {
      // We make a copy of the CachedDeserializable.
      // That way the NonLocalRegionEntry will be disconnected
      // from the CachedDeserializable that is in our cache and
      // will not modify its state.
      this.value = CachedDeserializableFactory.create((CachedDeserializable)this.value);
    }
    Assert.assertTrue(this.value != Token.NOT_AVAILABLE, "getEntry did not fault value in from disk");
    this.lastModified = 0l;//re.getStatistics().getLastModifiedTime();
    this.isRemoved = Token.isRemoved(value);
    // TODO need to get version information from transaction entries
    this.versionTag = versionTag;
  }

  public void setValueSize(int size) {
    this.valueSize = size;
  }

  public int getValueSize() {
    return this.valueSize;
  }

  public void setForDelete() {
    this.forDelete = true;
  }

  public boolean isForDelete() {
    return this.forDelete;
  }

  @Override
  public String toString() {
    return "NonLocalRegionEntry("+this.key + "; value="  + this.value + "; version=" + this.versionTag;
  }

  public static NonLocalRegionEntry newEntry() {
    final StaticSystemCallbacks sysCb = FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return new NonLocalRegionEntry();
    }
    else {
      return sysCb.newNonLocalRegionEntry();
    }
  }

  public static NonLocalRegionEntry newEntry(RegionEntry re,
      LocalRegion region, boolean allowTombstones) {
    final StaticSystemCallbacks sysCb = FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return new NonLocalRegionEntry(re, region, allowTombstones);
    }
    else {
      return sysCb.newNonLocalRegionEntry(re, region, allowTombstones);
    }
  }

  public static NonLocalRegionEntry newEntryWithoutFaultIn(RegionEntry re,
      LocalRegion region, boolean allowTombstones) {
    final StaticSystemCallbacks sysCb = FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return new NonLocalRegionEntry(re, region, allowTombstones, false);
    }
    else {
      return sysCb.newNonLocalRegionEntry(re, region, allowTombstones, false);
    }
  }

  public static NonLocalRegionEntry newEntry(Object key, Object value,
      LocalRegion region, VersionTag<?> versionTag) {
    final StaticSystemCallbacks sysCb = FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return new NonLocalRegionEntry(key, value, region, versionTag);
    }
    else {
      return sysCb.newNonLocalRegionEntry(key, value, region, versionTag);
    }
  }

  
  public void makeTombstone(LocalRegion r, VersionTag isOperationRemote) {
    throw new UnsupportedOperationException();
  }

  public boolean dispatchListenerEvents(EntryEventImpl event) {
    throw new UnsupportedOperationException();
  }
  
  public VersionStamp getVersionStamp() {
    return this;
  }
  
  public boolean hasValidVersion() {
    return this.versionTag != null && this.versionTag.hasValidVersion();
  }
  
  public void setVersionTimeStamp(long time) {
    throw new UnsupportedOperationException();
  }

  public void processVersionTag(EntryEvent ev) {
    throw new UnsupportedOperationException();
  }

  public NonLocalRegionEntry() {
    // for fromData
  }

  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeObject(this.key, out);
    DataSerializer.writeObject(this.value, out);
    out.writeLong(this.lastModified);
    out.writeBoolean(this.isRemoved);
    DataSerializer.writeObject(this.versionTag, out);
  }

  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    this.key = DataSerializer.readObject(in);
    this.value = DataSerializer.readObject(in);
    this.lastModified = in.readLong();
    this.isRemoved = in.readBoolean();
    this.versionTag = (VersionTag)DataSerializer.readObject(in);
  }

  public long getLastModified() {
    return this.lastModified;
  }

  @Override
  public boolean isLockedForCreate() {
    return false;
  }

  public void _setLastModified(long lastModified) {
    this.lastModified = lastModified;
  }

  public void setLastModified(long lastModified) {
    this.lastModified = lastModified;
  }

  public long getLastAccessed() throws StatisticsDisabledException {
    return -1;
  }

  public long getHitCount() throws StatisticsDisabledException {
    return -1;
  }

  public long getMissCount() throws StatisticsDisabledException {
    return -1;
  }

  public boolean isRemoved() {
    return this.isRemoved;
  }

  public boolean isDestroyedOrRemoved() {
    return this.isRemoved;
  }

  public boolean isRemovedPhase2() {
    return this.isRemoved;
  }
  
  public boolean isTombstone() {
    return this.value == Token.TOMBSTONE;
  }

  public boolean fillInValue(LocalRegion r,
      InitialImageOperation.Entry entry, DM mgr, Version targetVersion) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public boolean isOverflowedToDisk(LocalRegion r,
      DistributedRegion.DiskPosition dp, boolean alwaysFetchPosition) {
    return false;
  }

  public Object getKey() {
    return this.key;
  }

  public Object getKeyCopy() {
    return this.key;
  }

  public Object getRawKey() {
    return this.key;
  }

  public final Object getValue(RegionEntryContext context) {
    if (Token.isRemoved(this.value)) {
      return null;
    }
    return this.value;
  }
  
  /** update the value held in this non-local region entry */
  void setCachedValue(Object newValue) {
    this.value = newValue;
  }

  // now for the fun part
  public void updateStatsForPut(long lastModifiedTime) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void setRecentlyUsed() {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void updateStatsForGet(boolean hit, long time)
      throws StatisticsDisabledException {
    // this method has been made a noop to fix bug 37436
  }

  public void txDidDestroy(long currTime) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void resetCounts() throws StatisticsDisabledException {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void removePhase1(LocalRegion r, boolean isClear)
  {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void removePhase2(LocalRegion r)
  {
    throw new UnsupportedOperationException(
        "Not appropriate for PartitionedRegion.NonLocalRegionEntry");
  }

  public void setValue(RegionEntryContext context, Object value) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  @Override
  public Object _getValue() {
    return value;
    //throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public void setOwner(LocalRegion owner, Object previousOwner) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  @Override
  public Token getValueAsToken() {
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
  public Object _getValueRetain(RegionEntryContext context, boolean decompress) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public final Object getValueInVM(RegionEntryContext context) {
    return this.value;
  }

  public Object getValueOnDisk(LocalRegion r) throws EntryNotFoundException {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public boolean initialImagePut(LocalRegion region, long lastModified1,
      Object newValue, boolean wasRecovered, boolean versionTagAccepted) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public boolean initialImageInit(LocalRegion region, long lastModified1,
      Object newValue, boolean create, boolean wasRecovered, boolean versionTagAccepted) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  public boolean destroy(LocalRegion region,
                         EntryEventImpl event,
                         boolean inTokenMode,
                         boolean cacheWrite,
                         Object expectedOldValue,
                         boolean forceDestroy,
                         boolean removeRecoveredEntry)
  throws CacheWriterException, EntryNotFoundException, TimeoutException {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#getValueOnDiskOrBuffer(com.gemstone.gemfire.internal.cache.LocalRegion)
   */
  public Object getValueOnDiskOrBuffer(LocalRegion r)
      throws EntryNotFoundException {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }
  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#getSerializedValueOnDisk(com.gemstone.gemfire.internal.cache.LocalRegion)
   */
  public Object getSerializedValueOnDisk(LocalRegion localRegion) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  
  public boolean hasStats() {
    return false;
  }

  public final Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
    return this.value;
  }
  @Override
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
    return this.value;
  }

  public Object getContainerInfo() {
    return null;
  }

  public Object setContainerInfo(LocalRegion owner, Object val) {
    return null;
  }

  public void setKey(Object key2) {
    this.key = key2;
  }

  /**
   * @see ExclusiveSharedLockObject#getOwnerId(Object)
   */
  public Object getOwnerId(Object context) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#attemptLock
   */
  public boolean attemptLock(LockMode mode, int flags,
      LockingPolicy lockPolicy, long msecs, Object owner, Object context) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#releaseLock
   */
  public void releaseLock(LockMode mode, boolean releaseAll, Object owner,
      Object context) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#numSharedLocks()
   */
  public int numSharedLocks() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#numReadOnlyLocks()
   */
  public int numReadOnlyLocks() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#hasExclusiveLock(Object, Object)
   */
  public boolean hasExclusiveLock(Object owner, Object context) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#hasExclusiveSharedLock(Object, Object)
   */
  public boolean hasExclusiveSharedLock(Object owner, Object context) {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  /**
   * @see ExclusiveSharedLockObject#getState()
   */
  @Override
  public int getState() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  @Override
  public boolean hasAnyLock() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  // VersionStamp methods ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#generateVersionTag(com.gemstone.gemfire.distributed.DistributedMember, boolean)
   */
  public VersionTag generateVersionTag(VersionSource member,
      boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
      EntryEventImpl event) {
    throw new UnsupportedOperationException(); // no text needed - not a customer visible method
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#concurrencyCheck(com.gemstone.gemfire.internal.cache.LocalRegion, com.gemstone.gemfire.internal.cache.versions.VersionTag, com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember, com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember)
   */
  public void processVersionTag(LocalRegion r, VersionTag tag,
      InternalDistributedMember thisVM, InternalDistributedMember sender) {
    throw new UnsupportedOperationException();
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#getEntryVersion()
   */
  public int getEntryVersion() {
    if (this.versionTag != null) {
      return this.versionTag.getEntryVersion();
    }
    return 0;
  }

  public long getRegionVersion() {
    if (this.versionTag != null) {
      return this.versionTag.getRegionVersion();
    }
    return 0;
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#getMemberID()
   */
  public VersionSource getMemberID() {
    if (this.versionTag != null) {
      return this.versionTag.getMemberID();
    }
    return null;
  }
  
  public int getDistributedSystemId() {
    if (this.versionTag != null) {
      return this.versionTag.getDistributedSystemId();
    }
    return -1;
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#setEntryVersion(int)
   */
  public void setVersions(VersionTag tag) {
    throw new UnsupportedOperationException();
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#setMemberID(com.gemstone.gemfire.distributed.DistributedMember)
   */
  public void setMemberID(VersionSource memberID) {
    throw new UnsupportedOperationException();
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#setPreviousMemberID(com.gemstone.gemfire.distributed.DistributedMember)
   */
  public void setPreviousMemberID(DistributedMember previousMemberID) {
    throw new UnsupportedOperationException();
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#asVersionTag()
   */
  public VersionTag asVersionTag() {
    return this.versionTag;
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#processVersionTag(com.gemstone.gemfire.internal.cache.LocalRegion, com.gemstone.gemfire.internal.cache.versions.VersionTag, boolean, com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember, com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember)
   */
  public void processVersionTag(LocalRegion r, VersionTag tag,
      boolean isTombstoneFromGII, boolean hasDelta,
      VersionSource thisVM, InternalDistributedMember sender, boolean checkForConflicts) {
    throw new UnsupportedOperationException();
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.versions.VersionStamp#getVersionTimeStamp()
   */
  @Override
  public long getVersionTimeStamp() {
    return this.versionTag != null? this.versionTag.getVersionTimeStamp() : 0;
  }
  
  /** get rvv internal high byte.  Used by region entries for transferring to storage */
  public short getRegionVersionHighBytes() {
    return this.versionTag != null? this.versionTag.getRegionVersionHighBytes() : 0;
  }
  
  /** get rvv internal low bytes.  Used by region entries for transferring to storage */
  public int getRegionVersionLowBytes() {
    return this.versionTag != null? this.versionTag.getRegionVersionLowBytes() : 0;
  }

  @Override
  public boolean isUpdateInProgress() {
    // In case of Snapshot we will return this only for read operations:
    // so update in progress should be false
    return updateInProgress;
    /*throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());*/
  }

  @Override
  public void setUpdateInProgress(boolean underUpdate) {
    /*throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());*/
    updateInProgress = underUpdate;
  }

  @Override
  public boolean isMarkedForEviction() {
    return false;
  }
  @Override
  public void setMarkedForEviction() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  @Override
  public void clearMarkedForEviction() {
    throw new UnsupportedOperationException(LocalizedStrings
        .PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY
            .toLocalizedString());
  }

  @Override
  public boolean isValueNull() {
    return this.value == null;
  }

  @Override
  public boolean isInvalid() {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  @Override
  public boolean isDestroyed() {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  @Override
  public void setValueToNull(RegionEntryContext context) {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }
  
  @Override
  public boolean isInvalidOrRemoved() {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());    
  }

  @Override
  public boolean isDestroyedOrRemovedButNotTombstone() {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  @Override
  public boolean isOffHeap() {
    return false;
  }

  @Override
  public void returnToPool() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void setValueWithTombstoneCheck(Object value, EntryEvent event)
      throws RegionClearedException {
    throw new UnsupportedOperationException(LocalizedStrings.PartitionedRegion_NOT_APPROPRIATE_FOR_PARTITIONEDREGIONNONLOCALREGIONENTRY.toLocalizedString());
  }

  @Override
  public Object prepareValueForCache(RegionEntryContext r, Object val, boolean isEntryUpdate, boolean valHasMetadataForGfxdOffHeapUpdate ) {
    throw new IllegalStateException("Should never be called");
  }

  @Override
  public boolean isCacheListenerInvocationInProgress() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void setCacheListenerInvocationInProgress(boolean isListenerInvoked) {
    // TODO Auto-generated method stub
    
  }
}
