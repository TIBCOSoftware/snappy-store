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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.cache.hdfs.internal.AbstractBucketRegionQueue;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HoplogOrganizer;
import com.gemstone.gemfire.cache.partition.PartitionListener;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.AtomicLongWithTerminalState;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.BucketAdvisor.BucketProfile;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy.ReadEntryUnderLock;
import com.gemstone.gemfire.internal.cache.locks.ReentrantReadWriteWriteShareLock;
import com.gemstone.gemfire.internal.cache.partitioned.*;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientTombstoneMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientUpdateMessage;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.cache.wan.parallel.ConcurrentParallelGatewaySenderQueue;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;

/**
 * The storage used for a Partitioned Region.
 * This class asserts distributed scope as well as a replicate data policy
 * It does not support transactions
 * 
 * Primary election for a BucketRegion can be found in the 
 * {@link com.gemstone.gemfire.internal.cache.BucketAdvisor} class
 * 
 * @author Mitch Thomas
 * @since 5.1
 *
 */
public class BucketRegion extends DistributedRegion implements Bucket {

  /**
   * A special value for the bucket size indicating that this bucket
   * has been destroyed.
   */
  private static final long BUCKET_DESTROYED = Long.MIN_VALUE;
  private final AtomicLong counter = new AtomicLong();
  private AtomicLong limit;
  private final AtomicLong numOverflowOnDisk = new AtomicLong();
  private final AtomicLong numOverflowBytesOnDisk = new AtomicLong();
  private final AtomicLong numEntriesInVM = new AtomicLong();
  private final AtomicLong evictions = new AtomicLong();
  private static final ThreadLocal<BucketRegionIndexCleaner> bucketRegionIndexCleaner = 
      new ThreadLocal<BucketRegionIndexCleaner>() ;

  /**
   * Contains size in bytes of the values stored
   * in theRealMap. Sizes are tallied during put and remove operations.
   */
  private final AtomicLongWithTerminalState bytesInMemory =
    new AtomicLongWithTerminalState();

  private final AtomicLong inProgressSize = new AtomicLong();

  public static final ReadEntryUnderLock READ_SER_VALUE = new ReadEntryUnderLock() {
    public final Object readEntry(final ExclusiveSharedLockObject lockObj,
        final Object context, final int iContext, boolean allowTombstones) {
      final RegionEntry re = (RegionEntry)lockObj;
      final BucketRegion br = (BucketRegion)context;
      return br.getSerialized(re, (iContext & DO_NOT_LOCK_ENTRY) != 0);
    }
  };

  public static class RawValue {

    public static final RawValue NULLVALUE = GemFireCacheImpl.FactoryStatics
        .rawValueFactory.newInstance(null);
    public static final RawValue REQUIRES_ENTRY_LOCK = GemFireCacheImpl
        .FactoryStatics.rawValueFactory.newInstance(null);

    protected Object rawValue;

    protected transient boolean fromCacheLoader;

    protected RawValue(Object rawVal) {
      this.rawValue = rawVal;
    }

    public static RawValue newInstance(final Object rawVal,
        final GemFireCacheImpl cache) {
      return cache.getRawValueFactory().newInstance(rawVal);
    }

    public final boolean isValueByteArray() {
      return this.rawValue instanceof byte[];
    }

    public final Object getRawValue() {
      return this.rawValue;
    }

    public final void writeAsByteArray(DataOutput out) throws IOException {
      if (isValueByteArray()) {
        DataSerializer.writeByteArray((byte[]) this.rawValue, out);
      } else if (this.rawValue instanceof CachedDeserializable) {
        ((CachedDeserializable)this.rawValue).writeValueAsByteArray(out);
      } else if (Token.isInvalid(this.rawValue)) {
        DataSerializer.writeByteArray(null, out);
      } else if (this.rawValue == Token.TOMBSTONE) { 
        DataSerializer.writeByteArray(null, out);
      } else {
        DataSerializer.writeObjectAsByteArray(this.rawValue, out);
      }
    }

    /**
     * Return the de-serialized value without changing the stored form
     * in the heap.  This causes local access to create a de-serialized copy (extra work)
     * in favor of keeping values in serialized form which is important because
     * it makes remote access more efficient.  This assumption is that remote
     * access is much more frequent.
     * TODO Unused, but keeping for potential performance boost when local Bucket 
     * access de-serializes the entry (which could hurt perf.)
     * 
     * @return the de-serialized value
     */
    public final Object getDeserialized(boolean copyOnRead) {
      if (isValueByteArray()) {
        if (copyOnRead) {
          // TODO move this code to CopyHelper.copy?
          byte[] src = (byte[])this.rawValue;
          byte[] dest = new byte[src.length];
          System.arraycopy(this.rawValue, 0, dest, 0, dest.length);
          return dest;
        } else {
          return this.rawValue;
        }
      } else if (this.rawValue instanceof CachedDeserializable) {
        if (copyOnRead) {
          return ((CachedDeserializable)this.rawValue).getDeserializedWritableCopy(null, null);
        } else {
          return ((CachedDeserializable)this.rawValue).getDeserializedForReading();
        }
      } else if (Token.isInvalid(this.rawValue)) {
        return null;
      } else {
        if (copyOnRead) {
          return CopyHelper.copy(this.rawValue);
        } else {
          return this.rawValue;
        }
      }
    }

    public final RawValue setFromCacheLoader() {
      this.fromCacheLoader = true;
      return this;
    }

    public final boolean isFromCacheLoader() {
      return this.fromCacheLoader;
    }

    @Override
    public String toString() {
      return "RawValue(value=" + this.rawValue + ",isByteArray="
          + isValueByteArray() + ')';
    }
  }

  /**
   * Factory for {@link RawValue} instances. Overridden by GemFireXD to provide
   * own RawValues that will also apply projection during serialization.
   * 
   * @author swale
   * @since 7.0
   */
  public static class RawValueFactory {

    private static final RawValueFactory _instance = new RawValueFactory();

    protected RawValue newInstance(final Object rawVal) {
      return new RawValue(rawVal);
    }

    public static RawValueFactory getFactory() {
      return _instance;
    }
  }

  private final int redundancy;
  
  /** the partitioned region to which this bucket belongs */
  protected final PartitionedRegion partitionedRegion;
  private final Map<Object, ExpiryTask> pendingSecondaryExpires = new HashMap<Object, ExpiryTask>(); 
 
  /* one map per bucket region */
  private final HashMap<Object, LockObject> allKeysMap =
    new HashMap<Object, LockObject>();

  static final boolean FORCE_LOCAL_LISTENERS_INVOCATION = 
    Boolean.getBoolean("gemfire.BucketRegion.alwaysFireLocalListeners");
  // gemfire.BucktRegion.alwaysFireLocalListeners=true

  private volatile AtomicLong eventSeqNum = null;

  public static final long INVALID_UUID = VMIdAdvisor.INVALID_ID;

  public final ReentrantReadWriteLock columnBatchFlushLock =
      new ReentrantReadWriteLock();


  /**
   * A read/write lock to prevent writing to the bucket when GII from this bucket is in progress
   */
  private final ReentrantReadWriteWriteShareLock snapshotGIILock
      = new ReentrantReadWriteWriteShareLock();

  private final Object giiReadLockForSIOwner = new Object();
  private final Object giiWriteLockForSIOwner = new Object();

  private final boolean lockGIIForSnapshot =
      Boolean.getBoolean("snappydata.snapshot.isolation.gii.lock");

  public final AtomicLong getEventSeqNum() {
    return eventSeqNum;
  }

  protected final AtomicReference<HoplogOrganizer> hoplog = new AtomicReference<HoplogOrganizer>();
  
  public BucketRegion(String regionName, RegionAttributes attrs,
      LocalRegion parentRegion, GemFireCacheImpl cache,
      InternalRegionArguments internalRegionArgs) {
    super(regionName, attrs, parentRegion, cache, internalRegionArgs);
    if(PartitionedRegion.DISABLE_SECONDARY_BUCKET_ACK) {
      Assert.assertTrue(attrs.getScope().isDistributedNoAck());
    } 
    else {
      Assert.assertTrue(attrs.getScope().isDistributedAck());
    }
    Assert.assertTrue(attrs.getDataPolicy().withReplication());
    Assert.assertTrue( ! attrs.getEarlyAck());
    Assert.assertTrue( ! attrs.getEnableGateway());
    Assert.assertTrue(isUsedForPartitionedRegionBucket());
    Assert.assertTrue( ! isUsedForPartitionedRegionAdmin());
    Assert.assertTrue(internalRegionArgs.getBucketAdvisor() != null);
    Assert.assertTrue(internalRegionArgs.getPartitionedRegion() != null);
    this.redundancy = internalRegionArgs.getPartitionedRegionBucketRedundancy();
    this.partitionedRegion = internalRegionArgs.getPartitionedRegion();
  }
  
  // Attempt to direct the GII process to the primary first
  @Override
  protected void initialize(InputStream snapshotInputStream,
      InternalDistributedMember imageTarget,
      InternalRegionArguments internalRegionArgs) 
    throws TimeoutException, IOException, ClassNotFoundException
  {
    // Set this region in the ProxyBucketRegion early so that profile exchange will
    // perform the correct fillInProfile method
    getBucketAdvisor().getProxyBucketRegion().setBucketRegion(this);
    boolean success = false;
    try {
      if (this.partitionedRegion.isShadowPR()
          && this.partitionedRegion.getColocatedWith() != null) {
        PartitionedRegion parentPR = ColocationHelper
            .getLeaderRegion(this.partitionedRegion);
        BucketRegion parentBucket = parentPR.getDataStore().getLocalBucketById(
            getId());
        // needs to be set only once.
        if (parentBucket.eventSeqNum == null) {
          parentBucket.eventSeqNum = new AtomicLong(getId());
        }
      }
      if (this.partitionedRegion.getColocatedWith() == null) {
        this.eventSeqNum = new AtomicLong(getId());
      } else {
        PartitionedRegion parentPR = ColocationHelper
            .getLeaderRegion(this.partitionedRegion);
        BucketRegion parentBucket = parentPR.getDataStore().getLocalBucketById(
            getId());
        if (parentBucket == null) {
          if (getCache().getLoggerI18n().fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "The parentBucket of region"
                    + this.partitionedRegion.getFullPath() + " bucketId "
                    + getId() + " is NULL");
          }
        }
        Assert.assertTrue(parentBucket != null);
        this.eventSeqNum = parentBucket.eventSeqNum;
      }
      
      final InternalDistributedMember primaryHolder = 
        getBucketAdvisor().basicGetPrimaryMember();
      if (primaryHolder != null && ! primaryHolder.equals(getMyId())) {
        // Ignore the provided image target, use an existing primary (if any)
        super.initialize(snapshotInputStream, primaryHolder, internalRegionArgs);
      } else {
        super.initialize(snapshotInputStream, imageTarget, internalRegionArgs);
      }

      success = true;
    } finally {
      if(!success) {
        removeFromPeersAdvisors(false);
        getBucketAdvisor().getProxyBucketRegion().clearBucketRegion(this);
      }
    }
  }
  
  

  @Override
  public void initialized() {
    //announce that the bucket is ready
    //setHosting performs a profile exchange, so there
    //is no need to call super.initialized() here.
  }
  
  @Override
  protected DiskStoreImpl findDiskStore(RegionAttributes ra, InternalRegionArguments internalRegionArgs) {
    return internalRegionArgs.getPartitionedRegion().getDiskStore();
  }

  @Override
  public void createEventTracker() {
    this.eventTracker = new EventTracker(this);
    this.eventTracker.start();
  }
  
  @Override
  protected CacheDistributionAdvisor createDistributionAdvisor(
      InternalRegionArguments internalRegionArgs){
    return internalRegionArgs.getBucketAdvisor();
  }
  
  public final BucketAdvisor getBucketAdvisor() {
    return (BucketAdvisor)this.distAdvisor;
  }

  public final boolean isHosting() {
    return getBucketAdvisor().isHosting();
  }

  /**
   * Use it only when threads are doing incremental updates. If updates are random
   * then this method may not be optimal.
   */
  protected static boolean setIfGreater(AtomicLong l, long update) {
    while (true) {
      long cur = l.get();

      if (update > cur) {
        if (l.compareAndSet(cur, update))
          return true;
      } else
        return false;
    }
  }

  @Override
  protected EventID distributeTombstoneGC(Set<Object> keysRemoved) {
    EventID eventId = super.distributeTombstoneGC(keysRemoved);
    if (keysRemoved != null && keysRemoved.size() > 0) {
      // send the GC to members that don't have the bucket but have the PR so they
      // can forward the event to clients
      PRTombstoneMessage.send(this, keysRemoved, eventId);
    }
    return eventId;
  }

  @Override
  protected void notifyClientsOfTombstoneGC(Map<VersionSource, Long> regionGCVersions, Set<Object>removedKeys, EventID eventID, FilterInfo routing) {
    if (CacheClientNotifier.getInstance() != null) {
      // Only route the event to clients interested in the partitioned region.
      // We do this by constructing a region-level event and then use it to
      // have the filter profile ferret out all of the clients that have interest
      // in this region
      FilterProfile fp = getFilterProfile();
      if (routing != null ||
          (removedKeys != null && removedKeys.size() > 0 && fp != null)) { // fix for bug #46309 - don't send null/empty key set to clients
        RegionEventImpl regionEvent = new RegionEventImpl(getPartitionedRegion(), Operation.REGION_DESTROY, null, true, getMyId()); 
        FilterInfo clientRouting = routing;
        if (clientRouting == null) {
          clientRouting = fp.getLocalFilterRouting(regionEvent);
        }
        regionEvent.setLocalFilterInfo(clientRouting); 
          
        ClientUpdateMessage clientMessage = ClientTombstoneMessage.gc(getPartitionedRegion(), removedKeys,
            eventID);
        CacheClientNotifier.notifyClients(regionEvent, clientMessage);
      }
    }
  }

  /**
   * Search the CM for keys. If found any, return the first found one
   * Otherwise, save the keys into the CM, and return null
   * The thread will acquire the lock before searching.
   * 
   * @param keys
   * @return first key found in CM
   *         null means not found
   */
  private LockObject searchAndLock(Object keys[]) {
    final LogWriterI18n logger = getCache().getLoggerI18n();
    LockObject foundLock = null;

    synchronized (allKeysMap) {
      // check if there's any key in map
      for (int i = 0; i < keys.length; i++) {
        if ((foundLock = searchLock(keys[i], logger)) != null) {
          break;
        }
      }

      // save the keys when still locked
      if (foundLock == null) {
        for (int i = 0; i < keys.length; i++) {
          addNewLock(keys[i], logger);
        }
      }
    }
    return foundLock;
  }

  private LockObject searchLock(final Object key, final LogWriterI18n logger) {
    final LockObject foundLock;
    if ((foundLock = allKeysMap.get(key)) != null) {
      if (logger.finerEnabled()) {
        logger.finer("LockKeys: found key: " + key + ":"
            + foundLock.lockedTimeStamp);
      }
      return foundLock;
    }
    return null;
  }

  private void addNewLock(final Object key, final LogWriterI18n logger) {
    final LockObject lockValue = new LockObject(key,
        logger.fineEnabled() ? System.currentTimeMillis() : 0);
    allKeysMap.put(key, lockValue);
    if (logger.finerEnabled()) {
      logger.finer("LockKeys: add key: " + key + ":"
          + lockValue.lockedTimeStamp);
    }
  }

  /**
   * Search the CM for the key. If found, return the lock for the key.
   * Otherwise, save the key into the CM, and return null
   * The thread will acquire the lock before searching.
   * 
   * @param key
   */
  private LockObject searchAndLock(final Object key) {
    final LogWriterI18n logger = getCache().getLoggerI18n();
    LockObject foundLock = null;

    synchronized (allKeysMap) {
      // check if there's any key in map
      if ((foundLock = searchLock(key, logger)) == null) {
        // save the keys when still locked
        addNewLock(key, logger);
      }
    }
    return foundLock;
  }

  /**
   * After processed the keys, this method will remove them from CM. 
   * And notifyAll for each key. 
   * The thread needs to acquire lock of CM first.
   * 
   * @param keys
   */
  public void removeAndNotifyKeys(Object keys[]) {
    final LogWriterI18n logger = getCache().getLoggerI18n();
    synchronized (allKeysMap) {
      for (int i = 0; i < keys.length; i++) {
        removeAndNotifyKeyNoLock(keys[i], logger);
      } // for
    }
  }

  private void removeAndNotifyKeyNoLock(final Object key,
      final LogWriterI18n logger) {
    {
      {
        LockObject lockValue = allKeysMap.remove(key);
        if (lockValue != null) {
          // let current thread become the monitor of the key object
          synchronized (lockValue) {
            lockValue.setRemoved();
            if (logger.finerEnabled()) {
              long waitTime = System.currentTimeMillis()
                  - lockValue.lockedTimeStamp;
              logger.finer("LockKeys: remove key " + key
                  + ", notifyAll for " + lockValue + ". It waited " + waitTime);
            }
            lockValue.notifyAll();
          }
        }
      }
    }
  }

  /**
   * After processing the key, this method will remove it from CM. 
   * And notifyAll for each key. 
   * The thread needs to acquire lock of CM first.
   * 
   * @param key
   */
  public void removeAndNotifyKey(final Object key) {
    final LogWriterI18n logger = getCache().getLoggerI18n();
    synchronized (allKeysMap) {
      removeAndNotifyKeyNoLock(key, logger);
    }
  }

  /**
   * Keep checking if CM has contained any key in keys. If yes, wait for notify,
   * then retry again. This method will block current thread for long time. 
   * It only exits when current thread successfully save its keys into CM. 
   * 
   * @param keys
   */
  public void waitUntilLocked(Object keys[]) {
    final String title = "BucketRegion.waitUntilLocked:";
    final LogWriterI18n logger = getCache().getLoggerI18n();
    while (true) {
      LockObject foundLock = searchAndLock(keys);

      if (foundLock != null) {
        waitForLock(foundLock, logger, title);
      } else {
        // now the keys have been locked by this thread
        break;
      } // to lock and process
    } // while
  }

  private void waitForLock(final LockObject foundLock,
      final LogWriterI18n logger, final String title) {
    {
      {
        synchronized(foundLock) {
          try {
            while (!foundLock.isRemoved()) {
              this.partitionedRegion.checkReadiness();
              foundLock.wait(1000);
              // primary could be changed by prRebalancing while waiting here
              checkForPrimary();
            }
          }
          catch (InterruptedException e) {
            // TODO this isn't a localizable string and it's being logged at info level
            logger.info(LocalizedStrings.DEBUG, title+" interrupted while waiting for "+foundLock+":"+e.getMessage());
          }
          if (logger.fineEnabled()) {
            long waitTime = System.currentTimeMillis()-foundLock.lockedTimeStamp;
            logger.fine(title+" waited " + waitTime + " ms to lock "+foundLock);
          }
        }
      }
    }
  }

  /**
   * Keep checking if CM has contained any key in keys. If yes, wait for notify,
   * then retry again. This method will block current thread for long time. 
   * It only exits when current thread successfully save its keys into CM. 
   * 
   * @param key
   */
  public void waitUntilLocked(final Object key) {
    final String title = "BucketRegion.waitUntilLocked:";
    final LogWriterI18n logger = getCache().getLoggerI18n();
    while (true) {
      LockObject foundLock = searchAndLock(key);

      if (foundLock != null) {
        waitForLock(foundLock, logger, title);
      } else {
        // now the keys have been locked by this thread
        break;
      } // to lock and process
    } // while
  }

  // this is in secondary bucket
  @Override
  boolean basicUpdate(final EntryEventImpl event,
      final boolean ifNew,
      final boolean ifOld,
      final long lastModified,
      final boolean overwriteDestroyed,
      final boolean cacheWrite)
      throws TimeoutException,
      CacheWriterException {
    // check validity of key against keyConstraint
    if (this.keyConstraint != null) {
      if (!this.keyConstraint.isInstance(event.getKey()))
        throw new ClassCastException(LocalizedStrings.LocalRegion_KEY_0_DOES_NOT_SATISFY_KEYCONSTRAINT_1.
            toLocalizedString(new Object[]{event.getKey().getClass().getName(), this.keyConstraint.getName()}));
    }

    validateValue(event.basicGetNewValue());

    if (event.getTXState() != null && event.getTXState().isSnapshot()) {
      return getSharedDataView().putEntry(event, ifNew, ifOld, null, false,
          cacheWrite, lastModified, overwriteDestroyed);
    } else {
      return getDataView(event).putEntry(event, ifNew, ifOld, null, false,
          cacheWrite, lastModified, overwriteDestroyed);
    }
  }

  // Entry (Put/Create) rules
  // If this is a primary for the bucket
  //  1) apply op locally, aka update or create entry
  //  2) distribute op to bucket secondaries and bridge servers with synchrony on local entry
  //  3) cache listener with synchrony on entry
  // Else not a primary
  //  1) apply op locally
  //  2) update local bs, gateway
  @Override
  protected
  boolean virtualPut(EntryEventImpl event,
                     boolean ifNew,
                     boolean ifOld,
                     Object expectedOldValue,
                     boolean requireOldValue,
                     long lastModified,
                     boolean overwriteDestroyed)
 throws TimeoutException,
      CacheWriterException {

    final boolean locked = beginLocalWrite(event);
    boolean success = false;
    try {
      if (this.partitionedRegion.isLocalParallelWanEnabled()) {
        handleWANEvent(event);
      }
      if (!hasSeenEvent(event)) {
        forceSerialized(event);
        RegionEntry oldEntry = this.entries
            .basicPut(event, lastModified, ifNew, ifOld, expectedOldValue,
                requireOldValue, overwriteDestroyed);
//        if (DistributionManager.VERBOSE) {
//          getCache().getLoggerI18n().info(LocalizedStrings.DEBUG,
//              "BR.virtualPut: oldEntry returned = " + oldEntry + " so basic put returned: " + (oldEntry != null));
//        }
        success = true;
        return oldEntry != null;
      }
      if (event.getDeltaBytes() != null && event.getRawNewValue() == null) {
        // This means that this event has delta bytes but no full value.
        // Request the full value of this event.
        // The value in this vm may not be same as this event's value.
        throw new InvalidDeltaException(
            "Cache encountered replay of event containing delta bytes for key "
                + event.getKey());
      }
      // Forward the operation and event messages
      // to members with bucket copies that may not have seen the event. Their
      // EventTrackers will keep them from applying the event a second time if
      // they've already seen it.
      if (DistributionManager.VERBOSE) {
        getCache().getLoggerI18n().info(LocalizedStrings.DEBUG,
            "BR.virtualPut: this cache has already seen this event " + event);
      }
      distributeUpdateOperation(event, lastModified);
      success = true;
      return true;
    } finally {
      if (locked) {
        endLocalWrite(event);
        // create and insert column batch
        TXStateInterface tx = event.getTXState(this);
        if (success && checkForColumnBatchCreation(tx)) {
          createAndInsertColumnBatch(tx, false);
        }
        if (success && partitionedRegion.isInternalColumnTable()) {
          CallbackFactoryProvider.getStoreCallbacks()
              .invokeColumnStorePutCallbacks(this, new EntryEventImpl[]{event});
        }
      }
    }
  }

  public final boolean checkForColumnBatchCreation(TXStateInterface tx) {
    final PartitionedRegion pr = getPartitionedRegion();
    return pr.needsBatching()
        && (tx == null || !tx.getProxy().isColumnRolloverDisabled())
        && (getRegionSize() >= pr.getColumnMaxDeltaRows()
        || getTotalBytes() >= pr.getColumnBatchSize());
  }

  public final boolean createAndInsertColumnBatch(TXStateInterface tx,
      boolean forceFlush) {
    // do nothing if a flush is already in progress
    if (this.columnBatchFlushLock.isWriteLocked()) {
      return false;
    }
    final ReentrantReadWriteLock.WriteLock sync =
        this.columnBatchFlushLock.writeLock();
    sync.lock();
    try {
      return internalCreateAndInsertColumnBatch(tx, forceFlush);
    } finally {
      sync.unlock();
    }
  }

  private boolean internalCreateAndInsertColumnBatch(TXStateInterface tx,
      boolean forceFlush) {
    // TODO: with forceFlush, ideally we should merge with an existing
    // ColumnBatch if the current size to be flushed is small like < 1000
    // (and split if total size has become too large)
    boolean success = false;
    boolean doFlush = false;
    if (forceFlush) {
      doFlush = getRegionSize() >= getPartitionedRegion()
              .getColumnMinDeltaRows();
    }
    if (!doFlush) {
      doFlush = checkForColumnBatchCreation(tx);
    }
    // we may have to use region.size so that no state
    // has to be maintained
    // one more check for size to make sure that concurrent call doesn't succeed.
    // anyway batchUUID will be invalid in that case.
    if (doFlush && getBucketAdvisor().isPrimary()) {
      // need to flush the region
      if (getCache().getLoggerI18n().fineEnabled()) {
        getCache().getLoggerI18n().fine("createAndInsertColumnBatch: " +
                "Creating the column batch for bucket " + this.getId());
      }
      final TXManagerImpl txManager = getCache().getCacheTransactionManager();
      boolean txStarted = false;
      if (tx == null && getCache().snapshotEnabled()) {
        txManager.begin(IsolationLevel.SNAPSHOT, null);
        txStarted = true;
      }
      try {
        long batchId =  partitionedRegion.newUUID(false);
        if (getCache().getLoggerI18n().fineEnabled()) {
          getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, "createAndInsertCachedBatch: " +
              "The snapshot after creating cached batch is " + getTXState().getLocalTXState().getCurrentSnapshot() +
              " the current rvv is " + getVersionVector() + "batch id " + batchId);
        }
        //Check if shutdown hook is set
        if (null != getCache().getRvvSnapshotTestHook()) {
          getCache().notifyRvvTestHook();
          getCache().waitOnRvvSnapshotTestHook();
        }

        Set keysToDestroy = createColumnBatchAndPutInColumnTable(batchId);

        if (txManager.testRollBack) {
          throw new RuntimeException("Test Dummy Exception");
        }
        destroyAllEntries(keysToDestroy, batchId);
        //Check if shutdown hook is set
        if (null != getCache().getRvvSnapshotTestHook()) {
          getCache().notifyRvvTestHook();
          getCache().waitOnRvvSnapshotTestHook();
        }
        success = true;
      } finally {
        if (txStarted) {
          if (success) {
            txManager.commit();
            if (null != getCache().getRvvSnapshotTestHook()) {
              getCache().notifyRvvTestHook();
            }
          } else {
            txManager.rollback();
          }
        }
      }
    }
    return success;
  }

  public static boolean isValidUUID(long uuid) {
    return uuid != BucketRegion.INVALID_UUID;
  }

  private Set createColumnBatchAndPutInColumnTable(long key) {
    StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
    return callback.createColumnBatch(this, key, this.getId());
  }

  // TODO: Suranjan Not optimized way to destroy all entries, as changes at level of RVV required.
  // TODO: Suranjan Need to do something like putAll.
  // TODO: Suranjan Effect of transaction?

  // This destroy is under a lock which makes sure that there is no put into the region
  // No need to take the lock on key
  private void destroyAllEntries(Set keysToDestroy, long batchKey) {
    for(Object key : keysToDestroy) {
      if (getCache().getLoggerI18n().fineEnabled()) {
        getCache()
            .getLoggerI18n()
            .fine("Destroying the entries after creating ColumnBatch " + key +
                " batchid " + batchKey + " total size " + this.size() +
                " keysToDestroy size " + keysToDestroy.size());
      }
      EntryEventImpl event = EntryEventImpl.create(
          getPartitionedRegion(), Operation.DESTROY, null, null,
          null, false, this.getMyId());

      event.setKey(key);
      event.setBucketId(this.getId());

      TXStateInterface txState = event.getTXState(this);
      if (txState != null) {
        event.setRegion(this);
        txState.destroyExistingEntry(event, true, null);
      } else {
        this.getPartitionedRegion().basicDestroy(event,true,null);
      }
    }
    if (getCache().getLoggerI18n().fineEnabled()) {
      getCache()
          .getLoggerI18n()
          .fine("Destroyed all for batchID " + batchKey + " total size " + this.size());
    }
  }

  public void handleWANEvent(EntryEventImpl event) {
    if (this.eventSeqNum == null) {
      if (getCache().getLoggerI18n().fineEnabled()) {
        getCache()
            .getLoggerI18n()
            .fine(
                "The bucket corresponding to this user bucket is not created yet. This event will not go to remote wan site. Event: "
                    + event);
      }
    }
    
    if (!(this instanceof AbstractBucketRegionQueue)) {
      if (getBucketAdvisor().isPrimary()) {
        // see if it is a tx scenario
        if(event.hasTX() && event.getTailKey() != -1) {
          // DO Nothing
          if (getCache().getLoggerI18n().fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "WAN: On primary bucket " + getId() + " , tx phase1 has already set the seq number as "
                    + event.getTailKey());
          }
        }
        else {
          event.setTailKey(this.eventSeqNum.addAndGet(this.partitionedRegion.getTotalNumberOfBuckets()));
          if (getCache().getLoggerI18n().fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "WAN: On primary bucket " + getId() + " , setting the seq number as "
                    + this.eventSeqNum.get() + ". was it a tx operation? " + event.hasTX());
          }  
        }
      } else {
        // Can there be a race here? Like one thread has done put in primary but
        // its update comes later
        // in that case its possible that a tail key is missed.
        // we can handle that by only incrementing the tailKey and never
        // setting it less than the current value.
        setIfGreater(this.eventSeqNum, event.getTailKey());
        if (getCache().getLoggerI18n().fineEnabled()) {
          getCache().getLoggerI18n().fine(
              "WAN: On secondary bucket " + getId() + " , setting the seq number as "
                  + event.getTailKey()  + ". was it a tx operation? " + event.hasTX());
        }
      }
    }
  }

  public final long reserveWANSeqNumber(boolean checkWanPrimary) {
    long reservedSeqNumber = -1;
    final AtomicLong eventSeqNum = this.eventSeqNum;
    if (eventSeqNum != null) {
      final PartitionedRegion pr = this.partitionedRegion;
      if (!checkWanPrimary
          || (pr.isLocalParallelWanEnabled() || pr.isHDFSRegion())
          && !(this instanceof AbstractBucketRegionQueue)) {
        if (!checkWanPrimary || getBucketAdvisor().isPrimary()) {
          reservedSeqNumber = eventSeqNum.addAndGet(pr
              .getTotalNumberOfBuckets());
          if (getCache().getLoggerI18n().fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "WAN: On primary bucket " + getId()
                    + " , setting the seq number as " + eventSeqNum.get());
          }
        }
        else {
          if (getCache().getLoggerI18n().fineEnabled()) {
            getCache().getLoggerI18n().fine(
                "WAN: On secondary bucket " + getId()
                    + " , cannot reserve any seq number ");
          }
        }
      }
     }
    else {
      if (getCache().getLoggerI18n().fineEnabled()) {
        getCache().getLoggerI18n().fine(
            "Cannot reserve as the bucket corresponding "
                + "to this user bucket is not created yet. ");
      }
    }
    return reservedSeqNumber;
  }

  /**
   * Fix for Bug#45917
   * We are updating the seqNumber so that new seqNumbers are 
   * generated starting from the latest in the system.
   * @param l
   */

  public void updateEventSeqNum(long l) {
    setIfGreater(this.eventSeqNum, l);
    if (getCache().getLoggerI18n().fineEnabled()) {
      getCache().getLoggerI18n().fine(
          "WAN: On bucket " + getId() + " , setting the seq number as "
              + l + " , before GII");
    }
  }
  
  protected void distributeUpdateOperation(EntryEventImpl event, long lastModified) {
    if (!event.isOriginRemote()
        && !event.isNetSearch()
        && getBucketAdvisor().isPrimary()) {
      if (event.getPutAllOperation() == null) {
        new UpdateOperation(event, lastModified).distribute();
      } else {
        // consolidate the UpdateOperation for each entry into a PutAllMessage
        // since we did not call basicPutPart3(), so we have to explicitly addEntry here
        event.getPutAllOperation().addEntry(event, this.getId());
      }
    }
    if (!event.getOperation().isPutAll()) {  // putAll will invoke listeners later
      event.invokeCallbacks(this, true, true);
    }
  }

  /**
   * distribute the operation in basicPutPart2 so the region entry lock is
   * held
   */
  @Override
  protected long basicPutPart2(EntryEventImpl event, RegionEntry entry, boolean isInitialized,
      long lastModified, boolean clearConflict)  {
    // Assumed this is called with entry synchrony
    // Typically UpdateOperation is called with the
    // timestamp returned from basicPutPart2, but as a bucket we want to do
    // distribution *before* we do basicPutPart2.
    final long modifiedTime = event.getEventTime(lastModified);
    // Update the get stats if necessary. 
    if (this.partitionedRegion.getDataStore().hasClientInterest(event)) {
      updateStatsForGet(entry, true);
    }
    if (!event.isOriginRemote()) {
      if (event.getVersionTag() == null || event.getVersionTag().isGatewayTag()) {
        LogWriterI18n log = getCache().getLoggerI18n();
        boolean eventHasDelta = event.getDeltaBytes() != null;
        VersionTag v = entry.generateVersionTag(null, false, eventHasDelta,
            this, event);
        if (v != null) {
          if (log.fineEnabled()) { log.fine("generated version tag " + v + /*" for " + event.getKey() +*/ " in region " + this.getName()); }
        }
      }

      // This code assumes it is safe ignore token mode (GII in progress) 
      // because it assumes when the origin of the event is local,
      // the GII has completed and the region is initialized and open for local
      // ops
      long start = this.partitionedRegion.getPrStats().startSendReplication();
      try {
        if (event.getPutAllOperation() == null) {
          Delta deltaNewValue = null;
          if (event.isLoadedFromHDFS()) {
            LogWriterI18n log = getCache().getLoggerI18n();
            if (log.fineEnabled()) {
              log.fine("Removing delta from the event for propagation of " +
              		"update operation as it is loaded from HDFS "
                  + event);
            }
            // removing the delta so that old value gets replaced by new 
            // value on secondary for HDFS loaded event. As the row is not 
            // loaded on the secondary,  delta can not be applied 
            deltaNewValue = event.getDeltaNewValue();
            event.removeDelta();
          } 
          UpdateOperation op = new UpdateOperation(event, modifiedTime);
          op.distribute();
          //set the delta back again
          if (event.isLoadedFromHDFS() && (deltaNewValue != null)) {
            event.setNewValue(deltaNewValue);
          }
        } else {
          // consolidate the UpdateOperation for each entry into a PutAllMessage
          // basicPutPart3 will call event.getPutAllOperation().addEntry(event);
        }
      } finally {
        this.partitionedRegion.getPrStats().endSendReplication(start);
      }
    }

    return super.basicPutPart2(event, entry, isInitialized, lastModified, clearConflict);
  }

  protected void notifyGatewayHubs(EnumListenerEvent operation,
                                   EntryEventImpl event) {
    if (shouldNotifyGatewayHub()) {
      // We need to clone the event for PRs.
      EntryEventImpl prEvent = createEventForPR(event);
      try {
      this.partitionedRegion.notifyGatewayHubs(operation, prEvent);
      } finally {
        prEvent.release();
      }
    } else {
      // We don't need to clone the event for new Gateway Senders.
      // Preserve the bucket reference for resetting it later.
      LocalRegion bucketRegion = event.getRegion();
      try {
        event.setRegion(this.partitionedRegion);
        this.partitionedRegion.notifyGatewayHubs(operation, event);
      } finally {
        // reset the event region back to bucket region.
        // This should work as gateway queue create GatewaySenderEvent for queueing.
        event.setRegion(bucketRegion);
      }
    }
  }

  public void checkForPrimary() {
    final boolean isp = getBucketAdvisor().isPrimary();
    if (! isp){
      this.partitionedRegion.checkReadiness();
      checkReadiness();
      InternalDistributedMember primaryHolder = getBucketAdvisor().basicGetPrimaryMember();    
      throw new PrimaryBucketException("Bucket " + getName()
          + " is not primary. Current primary holder is "+primaryHolder);
    }
  }
  
  /**
   * Checks to make sure that this node is primary, and locks the bucket
   * to make sure the bucket stays the primary bucket while the write
   * is in progress. Any call to this method must be followed with a call
   * to endLocalWrite().
   * @param event
   */
  private boolean beginLocalWrite(EntryEventImpl event) {
    if (skipWriteLock(event)) {
      return false;
    }

    if (cache.isCacheAtShutdownAll()) {
      throw new CacheClosedException("Cache is shutting down");
    }

    final Object key = event.getKey();
    waitUntilLocked(key); // it might wait for long time

    boolean lockedForPrimary = false;
    try {
      doLockForPrimary(false);
      return (lockedForPrimary = true);
    } finally {
      if (!lockedForPrimary) {
        removeAndNotifyKey(key);
      }
    }
  }

  /**
   * lock this bucket and, if present, its colocated "parent"
   * @param tryLock - whether to use tryLock (true) or a blocking lock (false)
   * @return true if locks were obtained and are still held
   */
  public boolean doLockForPrimary(boolean tryLock) {
    boolean locked = lockPrimaryStateReadLock(tryLock);
    if(!locked) {
      return false;
    }
    
    boolean isPrimary = false;
    try {
      // Throw a PrimaryBucketException if this VM is assumed to be the
      // primary but isn't, preventing update and distribution
      checkForPrimary();

      if (cache.isCacheAtShutdownAll()) {
        throw new CacheClosedException("Cache is shutting down");
      }

      isPrimary = true;
    } finally {
      if(!isPrimary) {
        doUnlockForPrimary();
      }
    }
    
    return true;
  }

  private boolean lockPrimaryStateReadLock(boolean tryLock) {
    Lock activeWriteLock = this.getBucketAdvisor().getActiveWriteLock();
    Lock parentLock = this.getBucketAdvisor().getParentActiveWriteLock();
    for (;;) {
      boolean interrupted = Thread.interrupted();
      try {
        //Get the lock. If we have to wait here, it's because
        //this VM is actively becoming "not primary". We don't want
        //to throw an exception until this VM is actually no longer
        //primary, so we wait here for not primary to complete. See bug #39963
        if (parentLock != null) {
          if (tryLock) {
            boolean locked = parentLock.tryLock();
            if (!locked) {
              return false;
            }
          } else {
            parentLock.lockInterruptibly();
          }
          if (tryLock) {
            boolean locked = activeWriteLock.tryLock();
            if (!locked) {
              parentLock.unlock();
              return false;
            }
          } else {
            activeWriteLock.lockInterruptibly();
          }
        }
        else {
          if (tryLock) {
            boolean locked = activeWriteLock.tryLock();
            if (!locked) {
              return false;
            }
          } else {
            activeWriteLock.lockInterruptibly();
          }
        }
        break; // success
      } catch (InterruptedException e) {
        interrupted = true;
        cache.getCancelCriterion().checkCancelInProgress(null);
        // don't throw InternalGemFireError to fix bug 40102
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    
    return true;
  }

  public void doUnlockForPrimary() {
    Lock activeWriteLock = this.getBucketAdvisor().getActiveWriteLock();
    activeWriteLock.unlock();
    Lock parentLock = this.getBucketAdvisor().getParentActiveWriteLock();
    if(parentLock!= null){
      parentLock.unlock();
    }
  }

  private boolean readLockEnabled() {
    if (lockGIIForSnapshot) { // test hook
      return true;
    }
    if ((this.getPartitionedRegion().needsBatching() ||
        this.getPartitionedRegion().isInternalColumnTable()) &&
        cache.snapshotEnabled()) {
      return true;
    } else {
      return false;
    }
  }

  private boolean writeLockEnabled() {
    if (lockGIIForSnapshot) { // test hook
      return true;
    }
    if ((isRowBuffer() || this.getPartitionedRegion().isInternalColumnTable()) &&
        cache.snapshotEnabled()) {
      return true;
    } else {
      return false;
    }
  }

  private volatile Boolean rowBuffer = false;

  public boolean isRowBuffer() {
    final Boolean rowBuffer = this.rowBuffer;
    if (rowBuffer || this.getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
      return rowBuffer;
    }
    boolean isRowBuffer = false;
    List<PartitionedRegion> childRegions = ColocationHelper.getColocatedChildRegions(this.getPartitionedRegion());
    for (PartitionedRegion pr : childRegions) {
      isRowBuffer |= pr.getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX);
    }
    this.rowBuffer = isRowBuffer;
    return isRowBuffer;
  }


  public void takeSnapshotGIIReadLock() {
    if (readLockEnabled()) {
      if (this.getPartitionedRegion().
          getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
        BucketRegion bufferRegion = getBufferRegion();
        bufferRegion.takeSnapshotGIIReadLock();
      } else {
        final LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Taking readonly snapshotGIILock on bucket " + this);
        }
        snapshotGIILock.attemptLock(LockMode.SH, -1, giiReadLockForSIOwner);
      }
    }
  }


  public void releaseSnapshotGIIReadLock() {
    if (readLockEnabled()) {
      if (this.getPartitionedRegion().
          getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
        BucketRegion bufferRegion = getBufferRegion();
        bufferRegion.releaseSnapshotGIIReadLock();
      } else {
        final LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Releasing readonly snapshotGIILock on bucket " + this.getName());
        }
        snapshotGIILock.releaseLock(LockMode.SH, false, giiReadLockForSIOwner);
      }
    }
  }

  private MembershipListener giiListener = null;

  private volatile boolean snapshotGIILocked = false;

  public boolean takeSnapshotGIIWriteLock(MembershipListener listener) {
    if (writeLockEnabled()) {
      if (this.getPartitionedRegion().
          getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
        BucketRegion bufferRegion = getBufferRegion();
        return bufferRegion.takeSnapshotGIIWriteLock(listener);
      } else {
        final LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Taking exclusive snapshotGIILock on bucket " + this.getName());
        }
        snapshotGIILock.attemptLock(LockMode.EX, -1, giiWriteLockForSIOwner);
        getBucketAdvisor()
            .addMembershipListenerAndAdviseGeneric(listener);
        snapshotGIILocked = true;
        this.giiListener = listener; // Set the listener only after taking the write lock.
        if (logger.fineEnabled()) {
          logger.fine("Succesfully took exclusive lock on bucket " + this.getName());
        }
        return true;
      }
    } else {
      return false;
    }
  }

  public void releaseSnapshotGIIWriteLock() {
    if (writeLockEnabled()) {
      if (this.getPartitionedRegion().
          getName().toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)) {
        BucketRegion bufferRegion = getBufferRegion();
        bufferRegion.releaseSnapshotGIIWriteLock();
      } else {
        final LogWriterI18n logger = getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Releasing exclusive snapshotGIILock on bucket " + this.getName());
        }
        if (this.snapshotGIILock.hasExclusiveLock(giiWriteLockForSIOwner, null)) {
          if (snapshotGIILocked) {
            snapshotGIILock.releaseLock(LockMode.EX, false, giiWriteLockForSIOwner);
            getBucketAdvisor().removeMembershipListener(giiListener);
            this.giiListener = null;
            snapshotGIILocked = false;
          }
        }
        if (logger.fineEnabled()) {
          logger.fine("Released exclusive snapshotGIILock on bucket " + this.getName());
        }
      }
    }
  }

  private BucketRegion bufferRegion;
  private final Object bufferRegionSync = new Object();

  /**
   * Corresponding bucket from row buffer if its a shadow table.
   *
   * @return
   */
  public BucketRegion getBufferRegion() {
    if (bufferRegion != null) {
      return bufferRegion;
    }
    synchronized (bufferRegionSync) {
      if (bufferRegion != null) {
        return bufferRegion;
      }
      PartitionedRegion leaderReagion = ColocationHelper.getLeaderRegion(this.getPartitionedRegion());
      this.bufferRegion = leaderReagion.getDataStore().getLocalBucketById(this.getId());
    }
    return bufferRegion;
  }

  /**
   * Release the lock on the bucket that makes the bucket
   * stay the primary during a write.
   */
  private void endLocalWrite(EntryEventImpl event) {
    doUnlockForPrimary();

    removeAndNotifyKey(event.getKey());
  }

  protected boolean skipWriteLock(EntryEventImpl event) {
    return event.isOriginRemote() || event.isNetSearch() || event.hasTX()
        || event.getOperation().isLocal() || event.getOperation().isPutAll()
        || (event.isExpiration() && (isEntryEvictDestroyEnabled() || event.isPendingSecondaryExpireDestroy()));
  }

  // this is stubbed out because distribution is done in basicPutPart2 while
  // the region entry is still locked
  @Override
  protected void distributeUpdate(EntryEventImpl event, long lastModified) {
  }

  // Entry Invalidation rules
  // If this is a primary for the bucket
  //  1) apply op locally, aka update entry
  //  2) distribute op to bucket secondaries and bridge servers with synchrony on local entry
  //  3) cache listener with synchrony on entry
  //  4) update local bs, gateway
  // Else not a primary
  //  1) apply op locally
  //  2) update local bs, gateway
  @Override
  void basicInvalidate(EntryEventImpl event) throws EntryNotFoundException
  {
    basicInvalidate(event, isInitialized(), false);
  }
  
  @Override
  void basicInvalidate(final EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry)
      throws EntryNotFoundException {
    // disallow local invalidation
    Assert.assertTrue(! event.isLocalInvalid());
    // avoid Assert that incurs expensive ThreadLocal lookup
    assert !isTX(): "unexpected invocation with existing " + getTXState()
        + " for " + event;
    Assert.assertTrue(event.getOperation().isDistributed());

    final boolean locked = beginLocalWrite(event);
    try {
      // increment the tailKey so that invalidate operations are written to HDFS
      if (this.partitionedRegion.hdfsStoreName != null) {
        assert this.partitionedRegion.isLocalParallelWanEnabled();
        handleWANEvent(event);
      }
      // which performs the local op.
      // The ARM then calls basicInvalidatePart2 with the entry synchronized.
      if ( !hasSeenEvent(event) ) {
        if (event.getOperation().isExpiration()) { // bug 39905 - invoke listeners for expiration
          DistributedSystem sys =   cache.getDistributedSystem(); 
          EventID newID = new EventID(sys); 
          event.setEventId(newID);
          event.setInvokePRCallbacks(getBucketAdvisor().isPrimary());
        }
        boolean forceCallbacks = isEntryEvictDestroyEnabled(); 
        boolean done = this.entries.invalidate(event, invokeCallbacks, forceNewEntry, forceCallbacks); 
        ExpirationAction expirationAction = getEntryExpirationAction();
        if (done && !getBucketAdvisor().isPrimary() && expirationAction != null
            && expirationAction.isInvalidate()) {
          synchronized(pendingSecondaryExpires) {
            pendingSecondaryExpires.remove(event.getKey());
          }
        }
        return;
      }
      else {
        if (DistributionManager.VERBOSE) {
          getCache().getLoggerI18n().info(
            LocalizedStrings.DEBUG,
            "LR.basicInvalidate: this cache has already seen this event " + event);
        }
        if (!event.isOriginRemote()
            && getBucketAdvisor().isPrimary()) {
          // This cache has processed the event, forward operation
          // and event messages to backup buckets
          new InvalidateOperation(event).distribute();
        }
        event.invokeCallbacks(this,true, false);
        return;
      }
    } finally {
      if (locked) {
        endLocalWrite(event);
      }
    }
  }

  @Override
  void basicInvalidatePart2(final RegionEntry re, final EntryEventImpl event,
      boolean clearConflict, boolean invokeCallbacks)
  {
    // Assumed this is called with the entry synchronized
    if (!event.isOriginRemote()) {
      if (event.getVersionTag() == null || event.getVersionTag().isGatewayTag()) {
        LogWriterI18n log = getCache().getLoggerI18n();
        VersionTag v = re.generateVersionTag(null, false, false, this, event);
        if (log.fineEnabled() && v != null) { log.fine("generated version tag " + v + /*" for " + event.getKey() +*/ " in region " + this.getName()); }
        event.setVersionTag(v);
      }

      // This code assumes it is safe ignore token mode (GII in progress) 
      // because it assumes when the origin of the event is local,
      // the GII has completed and the region is initialized and open for local
      // ops
      
      // This code assumes that this bucket is primary
      // distribute op to bucket secondaries and event to other listeners
      InvalidateOperation op = new InvalidateOperation(event);
      op.distribute();
    }
    super.basicInvalidatePart2(re, event, clearConflict /*Clear conflict occurred */, invokeCallbacks);
  }

  @Override
  void distributeInvalidate(EntryEventImpl event) {
  }

  @Override
  protected void distributeInvalidateRegion(RegionEventImpl event) {
    // switch region in event so that we can have distributed region
    // send InvalidateRegion message.
    event.region = this;
    super.distributeInvalidateRegion(event);
    event.region = this.partitionedRegion;
  }

  @Override
  protected boolean shouldDistributeInvalidateRegion(RegionEventImpl event) {
    return getBucketAdvisor().isPrimary();
  }
  
  @Override
  protected boolean shouldGenerateVersionTag(RegionEntry entry, EntryEventImpl event) {
    if (event.getOperation().isLocal()) { // bug #45402 - localDestroy generated a version tag
      return false;
    }
    return this.concurrencyChecksEnabled && ((event.getVersionTag() == null) || event.getVersionTag().isGatewayTag());
  }
  
  @Override
  void expireDestroy(EntryEventImpl event, boolean cacheWrite) {
    
    /* Early out before we throw a PrimaryBucketException because we're not primary */
    if (!skipWriteLock(event) && !getBucketAdvisor().isPrimary()) {
      return;
    }
    try {
      super.expireDestroy(event, cacheWrite);
      return;
    } catch(PrimaryBucketException e) {
      //must have concurrently removed the primary
      return;
    }
  }
  
  @Override
  void expireInvalidate(EntryEventImpl event) {
    if(!getBucketAdvisor().isPrimary()) {
      return;
    }
    try {
      super.expireInvalidate(event);
    } catch (PrimaryBucketException e) {
      //must have concurrently removed the primary
    }
  }

  @Override
  final void performExpiryTimeout(ExpiryTask p_task) throws CacheException
  {
    ExpiryTask task = p_task;
    boolean isEvictDestroy = isEntryEvictDestroyEnabled();
    //Fix for bug 43805 - get the primary lock before
    //synchronizing on pendingSecondaryExpires, to match the lock
    //ordering in other place (like acquiredPrimaryLock)
    lockPrimaryStateReadLock(false);
    try {
      // Why do we care if evict destroy is configured?
      // See bug 41096 for the answer.
      if(!getBucketAdvisor().isPrimary() && !isEvictDestroy) {
        synchronized (this.pendingSecondaryExpires) {
          if (task.isPending()) {
            Object key = task.getKey();
            if (key != null) {
              this.pendingSecondaryExpires.put(key, task);
            }
          }
        }
      } else {
        super.performExpiryTimeout(task);
      }
    } finally {
      doUnlockForPrimary();
    }
  }

  protected boolean isEntryEvictDestroyEnabled() {
    return getEvictionAttributes() != null && EvictionAction.LOCAL_DESTROY.equals(getEvictionAttributes().getAction());
  }
  
  protected final void processPendingSecondaryExpires()
  {
    ExpiryTask[] tasks;
    while (true) {
      // note we just keep looping until no more pendingExpires exist
      synchronized (this.pendingSecondaryExpires) {
        if (this.pendingSecondaryExpires.isEmpty()) {
          return;
        }
        tasks = new ExpiryTask[this.pendingSecondaryExpires.size()];
        tasks = this.pendingSecondaryExpires.values().toArray(tasks);
        this.pendingSecondaryExpires.clear();
      }
      try {
        if (isCacheClosing() || isClosed() || this.isDestroyed) {
          return;
        }
        final LogWriterI18n logger = getCache().getLoggerI18n();
        for (int i = 0; i < tasks.length; i++) {
          try {
            if (logger.fineEnabled()) {
              logger.fine(tasks[i].toString() + " fired at "
                  + System.currentTimeMillis());
            }
            tasks[i].basicPerformTimeout(true);
            if (isCacheClosing() || isClosed() || isDestroyed()) {
              return;
            }
          }
          catch (EntryNotFoundException ignore) {
            // ignore and try the next expiry task
          }
        }
      }
      catch (RegionDestroyedException re) {
        // Ignore - our job is done
      }
      catch (CancelException ex) {
        // ignore
      }
      catch (Throwable ex) {
        Error err;
        if (ex instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)ex)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        getCache().getLoggerI18n().severe(LocalizedStrings.LocalRegion_EXCEPTION_IN_EXPIRATION_TASK, ex);
      }
    }
  }
  
  /**
   * Creates an event for the EVICT_DESTROY operation so that events will fire
   * for Partitioned Regions.
   * @param key - the key that this event is related to
   * @return an event for EVICT_DESTROY
   */
  @Override
  protected EntryEventImpl generateEvictDestroyEvent(Object key) {
    EntryEventImpl event = super.generateEvictDestroyEvent(key);
    event.setInvokePRCallbacks(true);   //see bug 40797
    return event;
  }
    
  // Entry Destruction rules
  // If this is a primary for the bucket
  //  1) apply op locally, aka destroy entry (REMOVED token)
  //  2) distribute op to bucket secondaries and bridge servers with synchrony on local entry
  //  3) cache listener with synchrony on local entry
  //  4) update local bs, gateway
  // Else not a primary
  //  1) apply op locally
  //  2) update local bs, gateway
  @Override
  protected
  void basicDestroy(final EntryEventImpl event,
                       final boolean cacheWrite,
                       Object expectedOldValue)
  throws EntryNotFoundException, CacheWriterException, TimeoutException {

    // avoid Assert that incurs expensive ThreadLocal lookup
//    assert (!(isTX() && !event.getOperation().isEviction())): "unexpected invocation with existing " + getTXState()
//        + " for " + event;
    
    Assert.assertTrue(event.getOperation().isDistributed());
    final boolean locked = beginLocalWrite(event);
    try {
      // increment the tailKey for the destroy event
      if (this.partitionedRegion.isLocalParallelWanEnabled()) {
        handleWANEvent(event);
      }
      LogWriterI18n log = getCache().getLoggerI18n();
      // In GemFire EVICT_DESTROY is not distributed, so in order to remove the entry
      // from memory, allow the destroy to proceed. fixes #49784
      if (GemFireCacheImpl.gfxdSystem() && event.isLoadedFromHDFS() && !getBucketAdvisor().isPrimary()) {
        if (log.fineEnabled()) {
          log.fine("Put the destory event in HDFS queue on secondary "
              + "and return as event is HDFS loaded " + event);
        }
        notifyGatewayHubs(EnumListenerEvent.AFTER_DESTROY, event);
        return;
      }else{
        if (log.fineEnabled()) {
          if (GemFireCacheImpl.gfxdSystem()) {
            log.fine("Going ahead with the destroy as not hdfs loaded event " + event + " am i primary " + getBucketAdvisor().isPrimary());
          } else {
            log.fine("Going ahead with the destroy on GemFire system");
          }
        }
      }
      // This call should invoke AbstractRegionMap (aka ARM) destroy method
      // which calls the CacheWriter, then performs the local op.
      // The ARM then calls basicDestroyPart2 with the entry synchronized.
      if ( !hasSeenEvent(event) ) {
        if (event.getOperation().isExpiration()) { // bug 39905 - invoke listeners for expiration
          DistributedSystem sys =   cache.getDistributedSystem(); 
          EventID newID = new EventID(sys); 
          event.setEventId(newID);
          event.setInvokePRCallbacks(getBucketAdvisor().isPrimary());
        }
        boolean done = mapDestroy(event,
                          cacheWrite,
                          event.getOperation().isEviction(), // isEviction
                          expectedOldValue);
        if(done && !getBucketAdvisor().isPrimary() && isEntryExpiryPossible()) {
          synchronized(pendingSecondaryExpires) {
            pendingSecondaryExpires.remove(event.getKey());
          }
        }
        return;
      }
      else {
        distributeDestroyOperation(event);
        return;
      }
    } finally {
      if (locked) {
        endLocalWrite(event);
      }
    }
  }
  
  protected void distributeDestroyOperation (EntryEventImpl event) {
    if (DistributionManager.VERBOSE) {
      getCache().getLoggerI18n().info(
         LocalizedStrings.DEBUG,
         "BR.basicDestroy: this cache has already seen this event " + event);
    }
    if (!event.isOriginRemote()
        && getBucketAdvisor().isPrimary()) {
      // This cache has processed the event, forward operation
      // and event messages to backup buckets
      event.setOldValueFromRegion();
      new DestroyOperation(event).distribute();
    }
    event.invokeCallbacks(this,true, false);    
  }

  @Override
  protected void basicDestroyBeforeRemoval(RegionEntry entry, EntryEventImpl event) {
    // Assumed this is called with entry synchrony
    if (!event.isOriginRemote()
        && !event.getOperation().isLocal()
        && !Operation.EVICT_DESTROY.equals(event.getOperation())
        && !(event.isExpiration() && isEntryEvictDestroyEnabled())) {

      if (event.getVersionTag() == null || event.getVersionTag().isGatewayTag()) {
        LogWriterI18n log = getCache().getLoggerI18n();
        VersionTag v = entry.generateVersionTag(null, false, false, this, event);
        if (log.fineEnabled() && v != null) { log.fine("generated version tag " + v + /*" for " + event.getKey() +*/ " in region " + this.getName()); }
      }

      // This code assumes it is safe ignore token mode (GII in progress)
      // because it assume when the origin of the event is local,
      // then GII has completed (the region has been completely initialized)

      // This code assumes that this bucket is primary
      new DestroyOperation(event).distribute();
    }
    super.basicDestroyBeforeRemoval(entry, event);
  }

  @Override
  void distributeDestroy(EntryEventImpl event, Object expectedOldValue) {
  }
  
  
// impl removed - not needed for listener invocation alterations
//  void basicDestroyPart2(RegionEntry re, EntryEventImpl event, boolean inTokenMode, boolean invokeCallbacks)

  /*
  @Override
  protected void validateArguments(Object key, Object value,
      Object aCallbackArgument) {
    // LocalRegion#supportsTransaction already ensures that TX is always null
    // for BucketRegions so no need for check below
    assert !isTX(); // avoid Assert that incurs expensive ThreadLocal lookup
    super.validateArguments(key, value, aCallbackArgument);
  }
  */

  public void forceSerialized(EntryEventImpl event) {
    event.makeSerializedNewValue();
//    Object obj = event.getRawNewValue();
//    if (obj instanceof byte[]
//                            || obj == null
//                            || obj instanceof CachedDeserializable
//                            || obj == NotAvailable.NOT_AVAILABLE
//                            || Token.isInvalidOrRemoved(obj)) {
//                          // already serialized
//                          return;
//                        }
//    throw new InternalGemFireError("event did not force serialized: " + event);
  }
  
  /**
   * This method is called when a miss from a get ends up
   * finding an object through a cache loader or from a server.
   * In that case we want to make sure that we don't move
   * this bucket while putting the value in the ache.
   * @see LocalRegion#basicPutEntry(EntryEventImpl, long) 
   */
  @Override
  protected RegionEntry basicPutEntry(final EntryEventImpl event,
      final long lastModified) throws TimeoutException, CacheWriterException {
    final boolean locked = beginLocalWrite(event);
    try {
      event.setInvokePRCallbacks(true);
      forceSerialized(event);
      return super.basicPutEntry(event, lastModified);
    } finally {
      if (locked) {
        endLocalWrite(event);
      }
    }
  }

  @Override
  void basicUpdateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {
    
    Assert.assertTrue(!isTX());
    Assert.assertTrue(event.getOperation().isDistributed());

    beginLocalWrite(event);
    try {
      
      if (!hasSeenEvent(event)) {
        this.entries.updateEntryVersion(event);
      } else {
        if (DistributionManager.VERBOSE) {
          getCache().getLoggerI18n().info(
              LocalizedStrings.DEBUG,
              "BR.basicUpdateEntryVersion: this cache has already seen this event "
                  + event);
        }
      }
      if (!event.isOriginRemote() && getBucketAdvisor().isPrimary()) {
        // This cache has processed the event, forward operation
        // and event messages to backup buckets
        new UpdateEntryVersionOperation(event).distribute();
      }
      return;
    } finally {
      endLocalWrite(event);
    }
  }

  public int getRedundancyLevel()
  {
    return this.redundancy;
  }

  public boolean isPrimary() {
    throw new UnsupportedOperationException(LocalizedStrings.BucketRegion_THIS_SHOULD_NEVER_BE_CALLED_ON_0.toLocalizedString(getClass()));
  }

  @Override
  public final boolean isDestroyed() {
    //TODO prpersist - Added this if null check for the partitioned region
    // because we create the disk store for a bucket *before* in the constructor
    // for local region, which is before this final field is assigned. This is why
    // we shouldn't do some much work in the constructors! This is a temporary
    // hack until I move must of the constructor code to region.initialize.
    return isBucketDestroyed()
        || (this.partitionedRegion != null
            && this.partitionedRegion.isLocallyDestroyed && !isInDestroyingThread()); 
  }

  /**
   * Return true if this bucket has been destroyed.
   * Don't bother checking to see if the PR that owns this bucket was destroyed;
   * that has already been checked.
   * @since 6.0
   */
  public final boolean isBucketDestroyed() {
    return super.isDestroyed();
  }

  @Override
  public String getColumnCompressionCodec() {
    return this.partitionedRegion.getColumnCompressionCodec();
  }

  @Override
  public boolean isHDFSRegion() {
    return this.partitionedRegion.isHDFSRegion();
  }

  @Override
  public boolean isHDFSReadWriteRegion() {
    return this.partitionedRegion.isHDFSReadWriteRegion();
  }

  @Override
  protected boolean isHDFSWriteOnly() {
    return this.partitionedRegion.isHDFSWriteOnly();
  }

  @Override
  public int sizeEstimate() {
    if (isHDFSReadWriteRegion()) {
      try {
        checkForPrimary();
        ConcurrentParallelGatewaySenderQueue q = getHDFSQueue();
        if (q == null) return 0;
        int hdfsBucketRegionSize = q.getBucketRegionQueue(
            partitionedRegion, getId()).size();
        int hoplogEstimate = (int) getHoplogOrganizer().sizeEstimate();
        if (getLogWriterI18n().fineEnabled()) {
          getLogWriterI18n().fine("for bucket " + getName() + " estimateSize returning "
                  + (hdfsBucketRegionSize + hoplogEstimate));
        }
        return hdfsBucketRegionSize + hoplogEstimate;
      } catch (ForceReattemptException e) {
        throw new PrimaryBucketException(e.getLocalizedMessage(), e);
      }
    }
    return size();
  }

  @Override
  public void checkReadiness()
  {
    super.checkReadiness();
    if (isDestroyed()) {
      throw new RegionDestroyedException(toString(), getFullPath());
    }
  }

  @Override
  public final PartitionedRegion getPartitionedRegion(){
    return this.partitionedRegion;
  }

  public BucketRegion getHostedBucketRegion() {
    return this;
  }

  /**
   * is the current thread involved in destroying the PR that
   * owns this region?
   */
  private final boolean isInDestroyingThread() {
    return this.partitionedRegion.locallyDestroyingThread
      == Thread.currentThread();
  }
//  public int getSerialNumber() {
//    String s = "This should never be called on " + getClass();
//    throw new UnsupportedOperationException(s);
//  }

  @Override
  public void fillInProfile(Profile profile) {
    super.fillInProfile(profile);
    BucketProfile bp = (BucketProfile) profile;
    bp.isInitializing = this.initializationLatchAfterGetInitialImage.getCount() > 0;
  }
  
  /** check to see if the partitioned region is locally destroyed or closed */
  public boolean isPartitionedRegionOpen() {
    return !this.partitionedRegion.isLocallyDestroyed &&
      !this.partitionedRegion.isClosed && !this.partitionedRegion.isDestroyed();
  }
  
  /**
   * Horribly plagiarized from the similar method in LocalRegion
   * 
   * @param key
   * @param updateStats
   * @param clientEvent holder for client version tag
   * @param returnTombstones whether Token.TOMBSTONE should be returned for destroyed entries
   * @return serialized form if present, null if the entry is not in the cache,
   *         or INVALID or LOCAL_INVALID re is a miss (invalid)
   * @throws IOException
   *           if there is a serialization problem
   * @see LocalRegion#getDeserializedValue(RegionEntry, Object, Object, boolean,
   *      boolean, boolean, TXStateInterface, EntryEventImpl, boolean, boolean)
   */
  private RawValue getSerialized(Object key, boolean updateStats,
      boolean doNotLockEntry, final TXStateInterface lockState,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS)
      throws EntryNotFoundException, IOException {
    RegionEntry re = null;
    if (allowReadFromHDFS) {
      re = this.entries.getEntry(key);
    } else {
      re = this.entries.getOperationalEntryInVM(key);
    }
    if (re == null) {
      return RawValue.NULLVALUE;
    }
    if (re.isTombstone() && !returnTombstones) {
      return RawValue.NULLVALUE;
    }
    Object v = null;

    try {
      if (lockState != null) {
        v = lockState.lockEntryForRead(re, key, this,
            doNotLockEntry ? ReadEntryUnderLock.DO_NOT_LOCK_ENTRY : 0,
            returnTombstones, READ_SER_VALUE);
      }
      else {
        v = getSerialized(re, doNotLockEntry); // TODO OFFHEAP: todo v ends up in a RawValue. For now this can be a copy of the offheap onto the heap. But it might be easy to track lifetime of RawValue
      }
      if (v == RawValue.REQUIRES_ENTRY_LOCK) {
        return RawValue.REQUIRES_ENTRY_LOCK;
      }
      if (clientEvent != null) {
        VersionStamp stamp = re.getVersionStamp();
        if (stamp != null) {
          clientEvent.setVersionTag(stamp.asVersionTag());
        }
      }
    } catch (DiskAccessException dae) {
      this.handleDiskAccessException(dae, true /* stop bridge servers */);
      throw dae;
    }

    if (v == null) {
      return RawValue.NULLVALUE;
    }
    else {
      if (updateStats) {
        updateStatsForGet(re, true);
      }
      return RawValue.newInstance(v, this.cache);
    }
  }

  private final Object getSerialized(final RegionEntry re,
      final boolean doNotLockEntry) {
    // TODO OFFHEAP: todo v ends up in a RawValue. For now this can be a copy of
    // the offheap onto the heap. But it might be easy to track lifetime of
    // RawValue
    Object v = re.getValue(this);
    if (doNotLockEntry) {
      if (v == Token.NOT_AVAILABLE || v == null) {
        return RawValue.REQUIRES_ENTRY_LOCK;
      }
    }
    return v;
  }

  /**
   * Return serialized form of an entry
   * <p>
   * Horribly plagiarized from the similar method in LocalRegion
   * 
   * @param keyInfo
   * @param generateCallbacks
   * @param clientEvent holder for the entry's version information 
   * @param returnTombstones TODO
   * @return serialized (byte) form
   * @throws IOException if the result is not serializable
   * @see LocalRegion#get(Object, Object, boolean, EntryEventImpl)
   */
  public RawValue getSerialized(final Object key, final Object callbackArg,
      KeyInfo keyInfo, boolean generateCallbacks, boolean doNotLockEntry,
      final TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws IOException {
    checkReadiness();
    checkForNoAccess();
    CachePerfStats stats = getCachePerfStats();
    long start = stats.startGet();

    boolean miss = true;
    try {
      boolean isCreate = false;
      RawValue result = getSerialized(key, true, doNotLockEntry, lockState,
          clientEvent, returnTombstones, allowReadFromHDFS);
      isCreate = result == RawValue.NULLVALUE
        || (result.getRawValue() == Token.TOMBSTONE && !returnTombstones);
      miss = (result == RawValue.NULLVALUE || Token.isInvalid(result
          .getRawValue()));
      if (miss) {
        // if scope is local and there is no loader, then
        // don't go further to try and get value
        if (hasServerProxy() || 
            basicGetLoader() != null) {
          if(doNotLockEntry) {
            return RawValue.REQUIRES_ENTRY_LOCK;
          }
          if (keyInfo == null) {
            keyInfo = new KeyInfo(key, callbackArg, getId());
          }
          // TODO OFFHEAP: optimze
          Object value = nonTxnFindObject(keyInfo, isCreate,
              generateCallbacks, result.getRawValue(), true, true, clientEvent, false, allowReadFromHDFS);
          if (value != null) {
            result = RawValue.newInstance(value, this.cache)
                .setFromCacheLoader();
          }
        }
        else { // local scope with no loader, still might need to update stats
          if (isCreate) {
            recordMiss(getTXState(), null, key);
          }
        }
      }
      // changed in 7.0 to return RawValue(Token.INVALID) if the entry is invalid
      return result;
    } finally {
      stats.endGet(start, miss);
    }
  } // getSerialized

  @Override
  public final int hashCode() {
    return RegionInfoShip
        .getHashCode(this.partitionedRegion.getPRId(), getId());
  }

  @Override
  protected StringBuilder getStringBuilder() {
    return super.getStringBuilder().append("; serial=")
        .append(getSerialNumber()).append("; primary=").append(
            getBucketAdvisor().getProxyBucketRegion().isPrimary());
  }

  @Override
  protected void distributedRegionCleanup(RegionEventImpl event)
  {
    // No need to close advisor, assume its already closed 
    // However we need to remove our listener from the advisor (see bug 43950).
    this.distAdvisor.removeMembershipListener(this.advisorListener);
  }
  
  /**
   * Tell the peers that this VM has destroyed the region.
   * 
   * Also marks the local disk files as to be deleted before 
   * sending the message to peers.
   * 
   * 
   * @param rebalance true if this is due to a rebalance removing the bucket
   */
  public void removeFromPeersAdvisors(boolean rebalance) {
    if(getPersistenceAdvisor() != null) {
      getPersistenceAdvisor().releaseTieLock();
    }
    
    DiskRegion diskRegion = getDiskRegion();
    
    //Tell our peers whether we are destroying this region
    //or just closing it.
    boolean shouldDestroy = rebalance || diskRegion == null
        || !diskRegion.isRecreated();
    Operation op = shouldDestroy ? Operation.REGION_LOCAL_DESTROY
        : Operation.REGION_CLOSE;
    
    RegionEventImpl event = new RegionEventImpl(this, op, null, false,
        getMyId(), generateEventID()/* generate EventID */);
    // When destroying the whole partitioned region, there's no need to
    // distribute the region closure/destruction, the PR RegionAdvisor.close() 
    // has taken care of it
    if (isPartitionedRegionOpen()) {
      
      
      //Only delete the files on the local disk if
      //this is a rebalance, or we are creating the bucket
      //for the first time
      if (diskRegion != null && shouldDestroy) { 
        diskRegion.beginDestroyDataStorage();
      }
      
      //Send out the destroy op to peers
      new DestroyRegionOperation(event, true).distribute();
    }
  }

  @Override
  protected void distributeDestroyRegion(RegionEventImpl event,
                                         boolean notifyOfRegionDeparture) {
    //No need to do this when we actually destroy the region,
    //we already distributed this info.
  }
  
  EntryEventImpl createEventForPR(EntryEventImpl sourceEvent) {
    EntryEventImpl e2 = new EntryEventImpl(sourceEvent);
    boolean returned = false;
    try {
    e2.setRegion(this.partitionedRegion);
    if (FORCE_LOCAL_LISTENERS_INVOCATION) {
      e2.setInvokePRCallbacks(true);
    }
    else {
      e2.setInvokePRCallbacks(sourceEvent.getInvokePRCallbacks());
    }
    DistributedMember dm = this.getDistributionManager().getDistributionManagerId();
    e2.setOriginRemote(!e2.getDistributedMember().equals(dm));
    returned = true;
    return e2;
    } finally {
      if (!returned) {
        e2.release();
      }
    }
  }

  
  
  @Override
  public void invokeTXCallbacks(
      final EnumListenerEvent eventType, final EntryEventImpl event,
      final boolean callDispatchListenerEvent, final boolean notifyGateway)
  {
    if (getCache().getLogger().fineEnabled()) {
      getCache().getLogger().fine("BR.invokeTXCallbacks for event " + event);
    }
    // bucket events may make it to this point even though the bucket is still
    // initializing.  We can't block while initializing or a GII state flush
    // may hang, so we avoid notifying the bucket
    if (this.isInitialized()) {
      boolean callThem = callDispatchListenerEvent;
      if (event.isPossibleDuplicate()
          && this.eventTracker.isInitialImageProvider(event.getDistributedMember())) {
        callThem = false;
      }
      super.invokeTXCallbacks(eventType, event, callThem, notifyGateway);
    }
    final EntryEventImpl prevent = createEventForPR(event);
    try {
      this.partitionedRegion.invokeTXCallbacks(eventType, prevent, this.partitionedRegion.isInitialized() ? callDispatchListenerEvent : false, false);
    } finally {
      prevent.release();
    }
  }
  
  
  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#invokeDestroyCallbacks(com.gemstone.gemfire.internal.cache.EnumListenerEvent, com.gemstone.gemfire.internal.cache.EntryEventImpl, boolean)
   */
  @Override
  public void invokeDestroyCallbacks(
      final EnumListenerEvent eventType, final EntryEventImpl event,
      final boolean callDispatchListenerEvent, boolean notifyGateways)
  {
    // bucket events may make it to this point even though the bucket is still
    // initializing.  We can't block while initializing or a GII state flush
    // may hang, so we avoid notifying the bucket
    if (this.isInitialized()) {
      boolean callThem = callDispatchListenerEvent;
      if (event.isPossibleDuplicate()
          && this.eventTracker.isInitialImageProvider(event.getDistributedMember())) {
        callThem = false;
      }
      super.invokeDestroyCallbacks(eventType, event, callThem, notifyGateways);
    }
    final EntryEventImpl prevent = createEventForPR(event);
    try {
      this.partitionedRegion.invokeDestroyCallbacks(eventType, prevent, this.partitionedRegion.isInitialized() ? callDispatchListenerEvent : false, false);
    } finally {
      prevent.release();
    }
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#invokeInvalidateCallbacks(com.gemstone.gemfire.internal.cache.EnumListenerEvent, com.gemstone.gemfire.internal.cache.EntryEventImpl, boolean)
   */
  @Override
  public void invokeInvalidateCallbacks(
      final EnumListenerEvent eventType, final EntryEventImpl event,
      final boolean callDispatchListenerEvent)
  {
    // bucket events may make it to this point even though the bucket is still
    // initializing.  We can't block while initializing or a GII state flush
    // may hang, so we avoid notifying the bucket
    if (this.isInitialized()) {
      boolean callThem = callDispatchListenerEvent;
      if (event.isPossibleDuplicate()
          && this.eventTracker.isInitialImageProvider(event.getDistributedMember())) {
        callThem = false;
      }
      super.invokeInvalidateCallbacks(eventType, event, callThem);
    }
    final EntryEventImpl prevent = createEventForPR(event);
    try {
      this.partitionedRegion.invokeInvalidateCallbacks(eventType, prevent, this.partitionedRegion.isInitialized() ? callDispatchListenerEvent : false);
    } finally {
      prevent.release();
    }
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.LocalRegion#invokePutCallbacks(com.gemstone.gemfire.internal.cache.EnumListenerEvent, com.gemstone.gemfire.internal.cache.EntryEventImpl, boolean)
   */
  @Override
  public void invokePutCallbacks(
      final EnumListenerEvent eventType, final EntryEventImpl event,
      final boolean callDispatchListenerEvent, boolean notifyGateways)
  {
    if (getCache().getLogger().finerEnabled()) {
      getCache().getLogger().finer("invoking put callbacks on bucket for event " + event);
    }
    // bucket events may make it to this point even though the bucket is still
    // initializing.  We can't block while initializing or a GII state flush
    // may hang, so we avoid notifying the bucket
    if (this.isInitialized()) {
      boolean callThem = callDispatchListenerEvent;
      if (callThem && event.isPossibleDuplicate()
          && this.eventTracker.isInitialImageProvider(event.getDistributedMember())) {
        callThem = false;
      }
      super.invokePutCallbacks(eventType, event, callThem, notifyGateways);
    }

    final EntryEventImpl prevent = createEventForPR(event);
    try {
      this.partitionedRegion.invokePutCallbacks(eventType, prevent,
              this.partitionedRegion.isInitialized() ? callDispatchListenerEvent : false, false);
    } finally {
      prevent.release();
    }
  }
  
  /**
   * perform adjunct messaging for the given operation and return a set of
   * members that should be attached to the operation's reply processor (if any)
   * @param event the event causing this messaging
   * @param cacheOpRecipients set of receiver which got cacheUpdateOperation.
   * @param adjunctRecipients recipients that must unconditionally get the event
   * @param filterRoutingInfo routing information for all members having the region
   * @param processor the reply processor, or null if there isn't one
   * @return the set of failed recipients
   */
  protected Set performAdjunctMessaging(EntryEventImpl event,
      Set cacheOpRecipients, Set adjunctRecipients,
      FilterRoutingInfo filterRoutingInfo,
      DirectReplyProcessor processor,
      boolean calculateDelta,
      boolean sendDeltaWithFullValue) {
    
    Set failures = Collections.EMPTY_SET;
    PartitionMessage msg = event.getPartitionMessage();
    if (calculateDelta) {
      setDeltaIfNeeded(event);
    }
    if (msg != null) {
      // The primary bucket member which is being modified remotely by a GemFire
      // thread via a received PartitionedMessage
      //Asif: Some of the adjunct recepients include those members which 
      // are GemFireXDHub & would need old value along with news
      msg = msg.getMessageForRelayToListeners(event, adjunctRecipients);
      msg.setSender(this.partitionedRegion.getDistributionManager()
          .getDistributionManagerId());
      msg.setSendDeltaWithFullValue(sendDeltaWithFullValue);
      
      failures = msg.relayToListeners(cacheOpRecipients, adjunctRecipients,
          filterRoutingInfo, event, this.partitionedRegion, processor);
    }
    else {
      // The primary bucket is being modified locally by an application thread locally 
      Operation op = event.getOperation();
      if (op.isCreate() || op.isUpdate()) {
        // note that at this point ifNew/ifOld have been used to update the
        // local store, and the event operation should be correct
        failures = PutMessage.notifyListeners(cacheOpRecipients,
            adjunctRecipients, filterRoutingInfo, this.partitionedRegion, 
            event, op.isCreate(), !op.isCreate(), processor,
            sendDeltaWithFullValue);
      }
      else if (op.isDestroy()) {
        failures = DestroyMessage.notifyListeners(cacheOpRecipients,
            adjunctRecipients, filterRoutingInfo,
            this.partitionedRegion, event, processor);
      }
      else if (op.isInvalidate()) {
        failures = InvalidateMessage.notifyListeners(cacheOpRecipients,
            adjunctRecipients, filterRoutingInfo, 
            this.partitionedRegion, event, processor);
      }
      else {
        failures = adjunctRecipients;
      }
    }
    return failures;
  }

  private void setDeltaIfNeeded(EntryEventImpl event) {
    Object instance;
    if (this.partitionedRegion.getSystem().getConfig().getDeltaPropagation()
        && event.getOperation().isUpdate() && event.getDeltaBytes() == null) {
      @Unretained Object rawNewValue = event.getRawNewValue();
      if (!(rawNewValue instanceof CachedDeserializable)) {
        return;
      }
      if (rawNewValue instanceof StoredObject && !((StoredObject) rawNewValue).isSerialized()) {
        // it is a byte[]; not a Delta
        return;
      }
      instance = ((CachedDeserializable)rawNewValue).getValue();
      final com.gemstone.gemfire.Delta delta;
      if (instance instanceof com.gemstone.gemfire.Delta
          && (delta = (com.gemstone.gemfire.Delta)instance).hasDelta()) {
        try {
          HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
          long start = DistributionStats.getStatTime();
          delta.toDelta(hdos);
          event.setDeltaBytes(hdos.toByteArray());
          this.partitionedRegion.getCachePerfStats().endDeltaPrepared(start);
        }
        catch (RuntimeException re) {
          throw re;
        }
        catch (Exception e) {
          throw new DeltaSerializationException(
              LocalizedStrings.DistributionManager_CAUGHT_EXCEPTION_WHILE_SENDING_DELTA
                  .toLocalizedString(), e);
        }
      }
    }
  }

  /**
   * create a PutAllPRMessage for notify-only and send it to all adjunct nodes. 
   * return a set of members that should be attached to the operation's reply processor (if any)
   * @param dpao DistributedPutAllOperation object for PutAllMessage
   * @param cacheOpRecipients set of receiver which got cacheUpdateOperation.
   * @param adjunctRecipients recipients that must unconditionally get the event
   * @param filterRoutingInfo routing information for all members having the region
   * @param processor the reply processor, or null if there isn't one
   * @return the set of failed recipients
   */
  public Set performPutAllAdjunctMessaging(DistributedPutAllOperation dpao,
      Set cacheOpRecipients, Set adjunctRecipients, FilterRoutingInfo filterRoutingInfo,
      DirectReplyProcessor processor) {
    // create a PutAllPRMessage out of PutAllMessage to send to adjunct nodes
    PutAllPRMessage prMsg = dpao.createPRMessagesNotifyOnly(getId());
    prMsg.initMessage(this.partitionedRegion, adjunctRecipients, true, processor);
    prMsg.setSender(this.partitionedRegion.getDistributionManager()
        .getDistributionManagerId());
    
    // find members who have clients subscribed to this event and add them
    // to the recipients list.  Also determine if there are any FilterInfo
    // routing tables for any of the receivers
//    boolean anyWithRouting = false;
    Set recipients = null;
    Set membersWithRouting = filterRoutingInfo.getMembers();
    for (Iterator it=membersWithRouting.iterator(); it.hasNext(); ) {
      Object mbr = it.next();
      if (!cacheOpRecipients.contains(mbr)) {
//        anyWithRouting = true;
        if (!adjunctRecipients.contains(mbr)) {
          if (recipients == null) {
            recipients = new HashSet();
            recipients.add(mbr);
          }
        }
      }
    }
    if (recipients == null) {
      recipients = adjunctRecipients;
    } else {
      recipients.addAll(adjunctRecipients);
    }

//    Set failures = Collections.EMPTY_SET;

//    if (!anyWithRouting) {
      Set failures = this.partitionedRegion.getDistributionManager().putOutgoing(prMsg);

//  } else {
//      // Send message to each member.  We set a FilterRoutingInfo serialization
//      // target so that serialization of the PutAllData objects held in the
//      // message will only serialize the routing entry for the message recipient
//      Iterator rIter = recipients.iterator();
//      failures = new HashSet();
//      while (rIter.hasNext()){
//        InternalDistributedMember member = (InternalDistributedMember)rIter.next();
//        FilterRoutingInfo.setSerializationTarget(member);
//        try {
//          prMsg.resetRecipients();
//          prMsg.setRecipient(member);
//          Set fs = this.partitionedRegion.getDistributionManager().putOutgoing(prMsg);
//          if (fs != null && !fs.isEmpty()) {
//            failures.addAll(fs);
//          }
//        } finally {
//          FilterRoutingInfo.clearSerializationTarget();
//        }
//      }
//    }

    return failures;
  }

  /**
   * return the set of recipients for adjunct operations
   */
  protected Set getAdjunctReceivers(EntryEventImpl event, Set cacheOpReceivers,
      Set twoMessages, FilterRoutingInfo routing) {
    Operation op = event.getOperation();
    if (op.isUpdate() || op.isCreate() || (op.isDestroy() && !op.isExpiration()) || op.isInvalidate()) {
      // this method can safely assume that the operation is being distributed from
      // the primary bucket holder to other nodes
      Set r = this.partitionedRegion.getRegionAdvisor()
        .adviseRequiresNotification(event);
            
      if (r.size() > 0) {
        r.removeAll(cacheOpReceivers);
      }
      
      // buckets that are initializing may transition out of token mode during
      // message transmission and need both cache-op and adjunct messages to
      // ensure that listeners are invoked
      if (twoMessages.size() > 0) {
        if (r.size() == 0) { // can't add to Collections.EMPTY_SET
          r = twoMessages;
        }
        else {
          r.addAll(twoMessages);
        }  
      }      
      if (routing != null) {
        // add adjunct messages to members with client routings
        for (InternalDistributedMember id: routing.getMembers()) {
          if (!cacheOpReceivers.contains(id)) {
            if (r.isEmpty()) {
              r = new HashSet();
            }
            r.add(id);
          }
        }
      }
      return r;
    } 
    else {
      return Collections.EMPTY_SET;
    }
  }

  @Override
  public final int getId() {
    return getBucketAdvisor().getProxyBucketRegion().getId();
  }

  @Override
  protected void cacheWriteBeforePut(EntryEventImpl event, Set netWriteRecipients,
      CacheWriter localWriter,
      boolean requireOldValue, Object expectedOldValue)
  throws CacheWriterException, TimeoutException {
    
    boolean origRemoteState = false;
    try {
      if (event.getPartitionMessage() != null || event.hasClientOrigin()) {
        origRemoteState=event.isOriginRemote();
        event.setOriginRemote(true);
      }
      event.setRegion(this.partitionedRegion);
      this.partitionedRegion.cacheWriteBeforePut(event, netWriteRecipients,
          localWriter, requireOldValue, expectedOldValue);
    } finally {
      if (event.getPartitionMessage() != null || event.hasClientOrigin()) {
        event.setOriginRemote(origRemoteState);
      }
      event.setRegion(this);
    }
  }

  @Override
  boolean cacheWriteBeforeDestroy(EntryEventImpl event, Object expectedOldValue)
  throws CacheWriterException, EntryNotFoundException, TimeoutException {
    
    boolean origRemoteState = false;
    boolean ret = false;
    try {
      if (event.getPartitionMessage() != null || event.hasClientOrigin()) {
        origRemoteState=event.isOriginRemote();
        event.setOriginRemote(true);
      }
      event.setRegion(this.partitionedRegion);
      ret = this.partitionedRegion.cacheWriteBeforeDestroy(event, expectedOldValue);
    } finally {
      if (event.getPartitionMessage() != null || event.hasClientOrigin()) {
        event.setOriginRemote(origRemoteState);
      }
      event.setRegion(this);
    }
    return ret;
    //  return super.cacheWriteBeforeDestroy(event);
  }

  @Override
  public CacheWriter basicGetWriter() {
    return this.partitionedRegion.basicGetWriter();
  }
   @Override
  void cleanUpOnIncompleteOp(EntryEventImpl event,   RegionEntry re, 
      boolean eventRecorded, boolean updateStats, boolean isReplace) {
     
    
    if(!eventRecorded || isReplace) {
      //No indexes updated so safe to remove.
      this.entries.removeEntry(event.getKey(), re, updateStats) ;      
    }/*else {
      //if event recorded is true, that means as per event tracker entry is in
      //system. As per gemfirexd, indexes have been updated. What is not done
      // is basicPutPart2( distribution etc). So we do nothing as PR's re-attempt
      // will do the required basicPutPart2. If we remove the entry here, than 
      //event tracker will not allow re insertion. So either we do nothing or
      //if we remove ,than we have to update gfxdindexes as well as undo recording
      // of event.
       //TODO:OQL indexes? : Hope they get updated during retry. The issue is that oql indexes
       // get updated after distribute , so it is entirely possible that oql index are 
        // not updated. what if retry fails?
       
    }*/
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.partitioned.Bucket#getBucketOwners()
   * @since gemfire59poc
   */
  public Set getBucketOwners() {
    return getBucketAdvisor().getProxyBucketRegion().getBucketOwners();    
  }

  public long getCounter() {
    return counter.get();
  }

  public void updateCounter(long delta) {
    if (delta != 0) {
      this.counter.getAndAdd(delta);
    }
  }

  public void resetCounter() {
    if (this.counter.get() != 0) {
      this.counter.set(0);
    }
  }

  public long getLimit() {
    if (this.limit == null) {
	  return 0;
	}
	return limit.get();
  }

  public void setLimit(long limit) {
	// This method can be called before object of this class is created
	if (this.limit == null) {
	  this.limit = new AtomicLong();
	}
	this.limit.set(limit);
  }

  static int calcMemSize(Object value) {
    if (value == null || value instanceof Token) {
      return 0;
    }
    if (value instanceof GatewaySenderEventImpl) {
      return ((GatewaySenderEventImpl)value).getSerializedValueSize();
    }
    if (!(value instanceof byte[])
        && !CachedDeserializableFactory.preferObject()
        && !(value instanceof CachedDeserializable)
        && !(value instanceof com.gemstone.gemfire.Delta)
        && !(value instanceof Delta)) {
    // ezoerner:20090401 it's possible this value is a Delta
      throw new InternalGemFireError("DEBUG: calcMemSize: weird value (class " 
          + value.getClass() + "): " + value);
    }
    
    try {
      return CachedDeserializableFactory.calcMemSize(value);
    } catch (IllegalArgumentException e) {
      return 0;
    }
  }

  boolean isDestroyingDiskRegion;

  @Override
  protected void updateSizeOnClearRegion(int sizeBeforeClear) {
    // This method is only called when the bucket is destroyed. If we
    // start supporting clear of partitioned regions, this logic needs to change
    // we can't just set these counters to zero, because there could be
    // concurrent operations that are also updating these stats. For example,
    //a destroy could have already been applied to the map, and then updates
    //the stat after we reset it, making the state negative.
    
    final PartitionedRegionDataStore prDs = this.partitionedRegion.getDataStore();
//     this.debugMap.clear();
//     this.createCount.set(0);
//     this.putCount.set(0);
//     this.invalidateCount.set(0);
//     this.evictCount.set(0);
//     this.faultInCount.set(0);
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnClearRegion (" + this + ")");
//     }
    long oldMemValue;


    if (!this.reservedTable() && needAccounting()) {
      long ignoreBytes = (this.isDestroyed || this.isDestroyingDiskRegion) ? getIgnoreBytes() :
              getIgnoreBytes() + regionOverHead;
      callback.dropStorageMemory(getFullPath(), ignoreBytes);
    }
    if(this.isDestroyed || this.isDestroyingDiskRegion) {
      //If this region is destroyed, mark the stat as destroyed.
      oldMemValue = this.bytesInMemory.getAndSet(BUCKET_DESTROYED);
            
    } else if(!this.isInitialized()) {
      //This case is rather special. We clear the region if the GII failed.
      //In the case of bucket regions, we know that there will be no concurrent operations
      //if GII has failed, because there is not primary. So it's safe to set these
      //counters to 0.
      oldMemValue = this.bytesInMemory.getAndSet(0);
    }
    // GemFireXD has table/DD locks, so clearing the flags is fine
    else if (GemFireCacheImpl.gfxdSystem()) {
      oldMemValue = this.bytesInMemory.getAndSet(0);
    }
    else {
      throw new InternalGemFireError("Trying to clear a bucket region that was not destroyed or in initialization.");
    }
    if(oldMemValue != BUCKET_DESTROYED) {
      this.partitionedRegion.getPrStats().incDataStoreEntryCount(-sizeBeforeClear);
      prDs.updateMemoryStats(-oldMemValue);
    }
    // explicitly clear overflow counters if no diskRegion is present
    // (for latter the counters are cleared by DiskRegion.statsClear)
    if (getDiskRegion() == null) {
      this.numOverflowOnDisk.set(0);
      this.numOverflowBytesOnDisk.set(0);
    }
  }

  @Override
  public int calculateValueSize(Object val) {
    // Only needed by BucketRegion
    return calcMemSize(val);
  }
  @Override
  public int calculateRegionEntryValueSize(RegionEntry re) {
    return calcMemSize(re._getValue()); // OFFHEAP _getValue ok
  }

  @Override
  void updateSizeOnPut(Object key, int oldSize, int newSize) {
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnPut (" + this
//                              + " key=" + key
//                              + ") oldSize = " + oldSize + " newSize = " + newSize
//                              + " cCount=" + this.createCount.get()
//                              + " rCount=" + this.removeCount.get()
//                              + " pCount=" + this.putCount.get()
//                              + " iCount=" + this.invalidateCount.get()
//                              + " eCount=" + this.evictCount.get()
//                              + " fCount=" + this.faultInCount.get()
//                              + " liveCount=" + getLiveCount()
//                              + " bSize=" + this.bytesInMemory.get());
//     }
//     if (newSize == 0) {
//       if (oldSize == 0 && oldVal != NotAvailable.NOT_AVAILABLE
//           && oldVal != Token.REMOVED_PHASE1) {
//         cache.getLogger().info("called updateSizeOnPut with oldVal=" + oldVal + " newVal=" + newVal,
//                                new RuntimeException("STACK"));
//       } else {
//         if (oldSize != 0) {
//           this.invalidateCount.incrementAndGet();
//         }
//         if (oldVal != Token.REMOVED_PHASE1) {
//           Assert.assertTrue(this.debugMap.containsKey(key));
//         } else {
//           // create invalid. No need to change stats
//           Assert.assertTrue(!this.debugMap.containsKey(key));
//           this.debugMap.put(key, 0);
//         }
//       }
//     } else if (oldSize == 0) {
//       if (oldVal != NotAvailable.NOT_AVAILABLE && oldVal != Token.REMOVED_PHASE1) {
//         cache.getLogger().info("called updateSizeOnPut with oldVal=" + oldVal,
//                                new RuntimeException("STACK"));
//       }
//       // count it as a create since it is in memory again
//       this.createCount.incrementAndGet();
//       if (oldVal != Token.REMOVED_PHASE1) {
//         Assert.assertTrue(this.debugMap.containsKey(key));
//         Assert.assertTrue(oldSize == this.debugMap.get(key), "expected " + oldSize + "==" + this.debugMap.get(key));
//       } else {
//         Assert.assertTrue(!this.debugMap.containsKey(key));
//       }
//       this.debugMap.put(key, newSize);
//     } else {
//       this.putCount.incrementAndGet();
//       Assert.assertTrue(oldSize == this.debugMap.get(key), "expected " + oldSize + "==" + this.debugMap.get(key));
//       this.debugMap.put(key, newSize);
//     }
    updateBucket2Size(oldSize, newSize, SizeOp.UPDATE);
  }

//   /**
//    * Return number of entries stored in memory on this bucket
//    */
//   private long getLiveCount() {
//     return this.createCount.get() - this.removeCount.get() - this.invalidateCount.get()
//       - (this.evictCount.get() - this.faultInCount.get());
//   }

  @Override
  void updateSizeOnCreate(Object key, int newSize) {
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnCreate (" + this
//                              + " key=" + key
//                              + ") newSize = " + newSize
//                              + " cCount=" + this.createCount.get()
//                              + " rCount=" + this.removeCount.get()
//                              + " pCount=" + this.putCount.get()
//                              + " iCount=" + this.invalidateCount.get()
//                              + " eCount=" + this.evictCount.get()
//                              + " fCount=" + this.faultInCount.get()
//                              + " liveCount=" + getLiveCount()
//                              + " bSize=" + this.bytesInMemory.get());
//     }
//     if (newSize == 0 && newVal != Token.INVALID) {
//       cache.getLogger().info("called updateSizeOnCreate with newVal=" + newVal,
//                              new RuntimeException("STACK"));
//     } else {
//       if (newSize != 0) {
//         this.createCount.incrementAndGet();
//       }
//       Assert.assertTrue(!this.debugMap.containsKey(key));
//       this.debugMap.put(key, newSize);
//     }
    this.partitionedRegion.getPrStats().incDataStoreEntryCount(1);
    updateBucket2Size(0, newSize, SizeOp.CREATE);
  }

  @Override
  void updateSizeOnRemove(Object key, int oldSize) {
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnRemove (" + this
//                              + " key=" + key
//                              + ") oldSize = " + oldSize
//                              + " oldObj=" + oldVal
//                              + " cCount=" + this.createCount.get()
//                              + " rCount=" + this.removeCount.get()
//                              + " pCount=" + this.putCount.get()
//                              + " iCount=" + this.invalidateCount.get()
//                              + " eCount=" + this.evictCount.get()
//                              + " fCount=" + this.faultInCount.get()
//                              + " liveCount=" + getLiveCount()
//                              + " bSize=" + this.bytesInMemory.get());
//     }
//     if (oldSize != 0) {
//       this.removeCount.incrementAndGet();
//     }
//     Assert.assertTrue(this.debugMap.containsKey(key));
//     Assert.assertTrue(oldSize == this.debugMap.get(key), "expected " + oldSize + "==" + this.debugMap.get(key));
//     this.debugMap.remove(key);
    this.partitionedRegion.getPrStats().incDataStoreEntryCount(-1);
    updateBucket2Size(oldSize, 0, SizeOp.DESTROY);
    freePoolMemory(oldSize + indexOverhead, true);
  }

  @Override
  int updateSizeOnEvict(Object key, int oldSize) {
    int newDiskSize = oldSize;
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnEvict (" + this
//                              + " key=" + key
//                              + ") oldSize=" + oldSize
//                              + " newDiskSize=" + newDiskSize
//                              + " cCount=" + this.createCount.get()
//                              + " rCount=" + this.removeCount.get()
//                              + " pCount=" + this.putCount.get()
//                              + " iCount=" + this.invalidateCount.get()
//                              + " eCount=" + this.evictCount.get()
//                              + " fCount=" + this.faultInCount.get()
//                              + " liveCount=" + getLiveCount()
//                              + " bSize=" + this.bytesInMemory.get());
//     }
//     if (oldSize != 0) {
//       this.evictCount.incrementAndGet();
//     }
//     Assert.assertTrue(this.debugMap.containsKey(key));
//     Assert.assertTrue(oldSize == this.debugMap.get(key), "expected " + oldSize + "==" + this.debugMap.get(key));
//     this.debugMap.put(key, 0);
    updateBucket2Size(oldSize, newDiskSize, SizeOp.EVICT);
    return newDiskSize;
  }

  @Override
  public void updateSizeOnFaultIn(Object key, int newMemSize, int oldDiskSize) {
//     if (cache.getLogger().infoEnabled()) {
//       cache.getLogger().info("updateSizeOnFaultIn (" + this
//                              + " key=" + key
//                              + ") oldDiskSize=" + oldDiskSize
//                              + " newSize=" + newMemSize
//                              + " cCount=" + this.createCount.get()
//                              + " rCount=" + this.removeCount.get()
//                              + " pCount=" + this.putCount.get()
//                              + " iCount=" + this.invalidateCount.get()
//                              + " eCount=" + this.evictCount.get()
//                              + " fCount=" + this.faultInCount.get()
//                              + " liveCount=" + getLiveCount()
//                              + " bSize=" + this.bytesInMemory.get());
//     }
//     if (newMemSize != 0) {
//       this.faultInCount.incrementAndGet();
//     }
//     Assert.assertTrue(this.debugMap.containsKey(key));
//     Assert.assertTrue(0 == this.debugMap.get(key), "expected " + 0 + "==" + this.debugMap.get(key));
//     this.debugMap.put(key, newMemSize);
    updateBucket2Size(oldDiskSize, newMemSize, SizeOp.FAULT_IN);
  }
  
  @Override
  public void initializeStats(long numEntriesInVM, long numOverflowOnDisk,
      long numOverflowBytesOnDisk) {
    super.initializeStats(numEntriesInVM, numOverflowOnDisk, numOverflowBytesOnDisk);
    incNumEntriesInVM(numEntriesInVM);
    incNumOverflowOnDisk(numOverflowOnDisk);
    incNumOverflowBytesOnDisk(numOverflowBytesOnDisk);
  }

  @Override
  protected void setMemoryThresholdFlag(MemoryEvent event) {
    Assert.assertTrue(false);
    //Bucket regions are not registered with ResourceListener,
    //and should not get this event
  }

  @Override
  public void initialCriticalMembers(boolean localHeapIsCritical,
      Set<InternalDistributedMember> critialMembers) {
    // The owner Partitioned Region handles critical threshold events
  }

  @Override
  protected void closeCallbacksExceptListener() {
    //closeCacheCallback(getCacheLoader()); - fix bug 40228 - do NOT close loader
    closeCacheCallback(getCacheWriter());
    closeCacheCallback(getEvictionController());
  }

  public long getSizeInMemory() {
    return Math.max(this.bytesInMemory.get(), 0L);
  }

  public long getInProgressSize() {
    return inProgressSize.get();
  }

  public void updateInProgressSize(long delta) {
    inProgressSize.addAndGet(delta);
  }

  public long getTotalBytes() {
    long result = this.bytesInMemory.get();
    if(result == BUCKET_DESTROYED) {
      return 0;
    }
    result += getNumOverflowBytesOnDisk();
    return result;
  }

  public void preDestroyBucket() {
    // cause abort of the GII thread
    ImageState is = this.getImageState();

    if (is != null) {
      is.setClearRegionFlag(true, null);
    }
  }

  @Override
  protected boolean clearIndexes(IndexUpdater indexUpdater, boolean lockForGII,
      boolean setIsDestroyed) {
      BucketRegionIndexCleaner cleaner = new BucketRegionIndexCleaner(lockForGII, !setIsDestroyed, this);
      bucketRegionIndexCleaner.set(cleaner);
      return false;
  }

  @Override
  protected boolean isExplicitRegionDestroy(RegionEventImpl event) {
    return event != null
        && Operation.REGION_LOCAL_DESTROY.equals(event.getOperation())
        && !PartitionedRegionDataStore.FOR_BUCKET_CLOSE.equals(event
            .getRawCallbackArgument());
  }

  protected void invokePartitionListenerAfterBucketRemoved() {
    PartitionListener[] partitionListeners = getPartitionedRegion().getPartitionListeners();
    if (partitionListeners == null || partitionListeners.length == 0) {
      return;
    }
    for (int i = 0; i < partitionListeners.length; i++) {
      PartitionListener listener = partitionListeners[i];
      if (listener != null) {
        listener.afterBucketRemoved(getId(), keySet());
      }
    }    
  }

  protected void invokePartitionListenerAfterBucketCreated() {
    PartitionListener[] partitionListeners = getPartitionedRegion().getPartitionListeners();
    if (partitionListeners == null || partitionListeners.length == 0) {
      return;
    }
    for (int i = 0; i < partitionListeners.length; i++) {
      PartitionListener listener = partitionListeners[i];
      if (listener != null) {
        listener.afterBucketCreated(getId(), keySet());
      }
    }    
  }

  enum SizeOp {
    UPDATE, CREATE, DESTROY, EVICT, FAULT_IN;

    int computeMemoryDelta(int oldSize, int newSize) {
      switch (this) {
      case CREATE:
        return newSize;
      case DESTROY:
        return - oldSize;
      case UPDATE:
        return newSize - oldSize;
      case EVICT:
        return - oldSize;
      case FAULT_IN:
        return newSize;
      default:
        throw new AssertionError("unhandled sizeOp: " + this);
      }
    }
  };
  
  /**
   * Updates the bucket size.
   */
  void updateBucket2Size(int oldSize, int newSize,
                         SizeOp op) {

    // now done from AbstractRegionEntry._setValue by a direct call to
    // updateBucketMemoryStats
    /*
    final int memoryDelta = op.computeMemoryDelta(oldSize, newSize);
    
    if (memoryDelta == 0) return;

//      cache.getLogger().fine("updateBucketSize(): delta " + delta
//                       + " b2size[" + bucketId + "]:" + b2size[(int) bucketId]
//                       + " bytesInUse: "+ bytesInUse 
//                       + " oldObjBytes: "  + oldSize
//                       + " newObjBytes: "+ newSize, new Exception());

    // do the bigger one first to keep the sum > 0
    updateBucketMemoryStats(memoryDelta);
    */
  }

  @Override
  public void updateMemoryStats(final Object oldValue, final Object newValue) {
    if (newValue != oldValue) {
      int oldValueSize = calcMemSize(oldValue);
      int newValueSize = calcMemSize(newValue);
      updateBucketMemoryStats(newValueSize - oldValueSize);
    }
  }

  void updateBucketMemoryStats(final int memoryDelta) {
    if (memoryDelta != 0) {

      final long bSize = bytesInMemory.compareAddAndGet(BUCKET_DESTROYED, memoryDelta);
       // debugging output for #40116
//      if (bSize <= 0) {
//       cache.getLogger().info("DEBUG: bSize=" + bSize + " delta=" + memoryDelta
//                              + " " +  System.identityHashCode(this),
//                ((bSize == 0 || bSize == memoryDelta)? new Exception("stack trace") : null));
//      }

      if(bSize == BUCKET_DESTROYED) {
        return;
      }

      if (bSize < 0 && getCancelCriterion().cancelInProgress() == null) {
         // cache.getLogger().info("DEBUG: death " + System.identityHashCode(this));
        throw new InternalGemFireError("Bucket " + this + " size (" +
            bSize + ") negative after applying delta of " + memoryDelta);
      }

      final PartitionedRegionDataStore prDS = this.partitionedRegion.getDataStore();
      prDS.updateMemoryStats(memoryDelta);
      //cache.getLogger().fine("DEBUG updateBucketMemoryStats delta=" + memoryDelta + " newSize=" + bytesInMemory.get(), new RuntimeException("STACK"));
    }
  }

  public static BucketRegionIndexCleaner getIndexCleaner() {
    BucketRegionIndexCleaner cleaner = bucketRegionIndexCleaner.get();
    bucketRegionIndexCleaner.set(null);
    return cleaner;
  }
  
  
  /**
   * Returns the current number of entries whose value has been
   * overflowed to disk by this bucket.This value will decrease when a value is
   * faulted in. 
   */
  public long getNumOverflowOnDisk() {
    return this.numOverflowOnDisk.get();
  }

  public long getNumOverflowBytesOnDisk() {
    return this.numOverflowBytesOnDisk.get();
  }

  /**
   * Returns the current number of entries whose value resides in the
   * VM for this bucket.  This value will decrease when the entry is overflowed to
   * disk. 
   */
  public long getNumEntriesInVM() {
    return this.numEntriesInVM.get();
  }

  /**
   * Increments the current number of entries whose value has been
   * overflowed to disk by this bucket, by a given amount.
   */
  void incNumOverflowOnDisk(long delta) {
    this.numOverflowOnDisk.addAndGet(delta);
  }

  void incNumOverflowBytesOnDisk(long delta) {
    if (delta == 0) return;
    this.numOverflowBytesOnDisk.addAndGet(delta);
    // The following could be reenabled at a future time.
    // I deadcoded for now to make sure I didn't have it break
    // the last 6.5 regression.
    // It is possible that numOverflowBytesOnDisk might go negative
    // for a short period of time if a decrement ever happens before
    // its corresponding increment.
//     if (res < 0) {
//       throw new IllegalStateException("numOverflowBytesOnDisk < 0 " + res);
//     }
  }

  /**
   * Increments the current number of entries whose value has been
   * overflowed to disk by this bucket,by a given amount.
   */
  void incNumEntriesInVM(long delta) {
    this.numEntriesInVM.addAndGet(delta);
  }
  
  public void incEvictions(long delta ) {
    this.evictions.getAndAdd(delta);
   }

  public long getEvictions( ) {
    return this.evictions.get();
  }

  @Override
  protected boolean isMemoryThresholdReachedForLoad() {
    return getBucketAdvisor().getProxyBucketRegion().isBucketSick();
  }
    public int getSizeForEviction() {
    EvictionAttributes ea = this.getAttributes().getEvictionAttributes();
    if (ea == null)
      return 0;
    EvictionAlgorithm algo = ea.getAlgorithm();
    if (!algo.isLRUHeap())
      return 0;
    EvictionAction action = ea.getAction();
    int size = action.isLocalDestroy() ? this.getRegionMap().sizeInVM() : (int)this
        .getNumEntriesInVM();
    return size;
  }
  @Override
  public HashMap getDestroyedSubregionSerialNumbers() {
    return new HashMap(0);
  }

  @Override
  public FilterProfile getFilterProfile(){
    return this.partitionedRegion.getFilterProfile();
  }

  @Override
  protected void generateLocalFilterRouting(InternalCacheEvent event) {
    if (event.getLocalFilterInfo() == null) {
      super.generateLocalFilterRouting(event);
    }
  }

  public void beforeAcquiringPrimaryState() {
    try {
      createHoplogOrganizer();
    } catch (IOException e) {
      // 48990: when HDFS was down, gemfirexd should still start normally
      getPartitionedRegion().getLogWriterI18n().warning(LocalizedStrings.HOPLOG_NOT_STARTED_YET, e);
    } catch(Throwable e) {
      SystemFailure.checkThrowable(e);
      //49333 - no matter what, we should elect a primary.
      getPartitionedRegion().getLogWriterI18n().error(LocalizedStrings.LocalRegion_UNEXPECTED_EXCEPTION, e);
    }
  }

  public HoplogOrganizer<?> createHoplogOrganizer() throws IOException {
    if (getPartitionedRegion().isHDFSRegion()) {
      HoplogOrganizer<?> organizer = hoplog.get();
      if (organizer != null) {
        //  hoplog is recreated by anther thread
        return organizer;
      }

      HoplogOrganizer hdfs = hoplog.getAndSet(getPartitionedRegion().hdfsManager.create(getId()));
      assert hdfs == null;
      return hoplog.get();
    } else {
      return null;
    }
  }

  public void afterAcquiringPrimaryState() {
    
  }
  /**
   * Invoked when a primary bucket is demoted.
   */
  public void beforeReleasingPrimaryLockDuringDemotion() {
    releaseHoplogOrganizer();
  }

  protected void releaseHoplogOrganizer() {
    // release resources during a clean transition
    HoplogOrganizer hdfs = hoplog.getAndSet(null);
    if (hdfs != null) {
      getPartitionedRegion().hdfsManager.close(getId());
    }
  }
  
  public HoplogOrganizer<?> getHoplogOrganizer() throws HDFSIOException {
    HoplogOrganizer<?> organizer = hoplog.get();
    if (organizer == null) {
      synchronized (getBucketAdvisor()) {
        checkForPrimary();
        try {
          organizer = createHoplogOrganizer();
        } catch (IOException e) {
          throw new HDFSIOException("Failed to create Hoplog organizer due to ", e);
        }
        if (organizer == null) {
          throw new HDFSIOException("Hoplog organizer is not available for " + this);
        }
      }
    }
    return organizer;
  }
  
  @Override
  public RegionAttributes getAttributes() {
    return this;
  }

  @Override
  public void hdfsCalled(Object key) {
    this.partitionedRegion.hdfsCalled(key);
  }

  @Override
  protected void clearHDFSData() {
    //clear the HDFS data if present
    if (getPartitionedRegion().isHDFSReadWriteRegion()) {
      // Clear the queue
      ConcurrentParallelGatewaySenderQueue q = getHDFSQueue();
      if (q == null) return;
      q.clear(getPartitionedRegion(), this.getId());
      HoplogOrganizer organizer = hoplog.get();
      if (organizer != null) {
        try {
          organizer.clear();
        } catch (IOException e) {
          throw new GemFireIOException(LocalizedStrings.HOPLOG_UNABLE_TO_DELETE_HDFS_DATA.toLocalizedString(), e);
        }
      }
    }
  }
  
  public EvictionCriteria getEvictionCriteria() {
    return this.partitionedRegion.getEvictionCriteria();
  }
  
  public CustomEvictionAttributes getCustomEvictionAttributes() {
    return this.partitionedRegion.getCustomEvictionAttributes();
  }
  
  /**
   * @return true if the evict destroy was done; false if it was not needed
   */
  public boolean customEvictDestroy(Object key)
  {
    checkReadiness();
    final EntryEventImpl event = 
          generateCustomEvictDestroyEvent(key);
    event.setCustomEviction(true);
    boolean locked = false;
    try {
      locked = beginLocalWrite(event);
      return mapDestroy(event,
                        false, // cacheWrite
                        true,  // isEviction
                        null); // expectedOldValue
    }
    catch (CacheWriterException error) {
      throw new Error(LocalizedStrings.LocalRegion_CACHE_WRITER_SHOULD_NOT_HAVE_BEEN_CALLED_FOR_EVICTDESTROY.toLocalizedString(), error);
    }
    catch (TimeoutException anotherError) {
      throw new Error(LocalizedStrings.LocalRegion_NO_DISTRIBUTED_LOCK_SHOULD_HAVE_BEEN_ATTEMPTED_FOR_EVICTDESTROY.toLocalizedString(), anotherError);
    }
    catch (EntryNotFoundException yetAnotherError) {
      throw new Error(LocalizedStrings.LocalRegion_ENTRYNOTFOUNDEXCEPTION_SHOULD_BE_MASKED_FOR_EVICTDESTROY.toLocalizedString(), yetAnotherError);
    } finally {
      if (locked) {
        endLocalWrite(event);
      }
      event.release();
    }
  }

  public boolean areSecondariesPingable() {
    
    Set<InternalDistributedMember> hostingservers = this.partitionedRegion.getRegionAdvisor()
        .getBucketOwners(this.getId());
    hostingservers.remove(cache.getDistributedSystem().getDistributedMember());
    
    if (cache.getLoggerI18n().fineEnabled())
      cache.getLoggerI18n().fine("Pinging secondaries of bucket " + this.getId() + " on servers "  + hostingservers);
   
    if (hostingservers.size() == 0)
      return true;
    
     return ServerPingMessage.send(cache, hostingservers);
    
  }

  @Override
  public boolean isSnapshotEnabledRegion() {
    // concurrency checks is by default true in column table
    return getPartitionedRegion().columnTable() ||
        getPartitionedRegion().needsBatching() || super.isSnapshotEnabledRegion();
  }

}
