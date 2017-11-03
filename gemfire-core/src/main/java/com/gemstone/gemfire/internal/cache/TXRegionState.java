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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.IllegalTransactionStateException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntArrayList;
import com.gemstone.gnu.trove.TObjectProcedure;

/**
 * TXRegionState is the entity that tracks all the changes a transaction has
 * made to a region.
 * 
 * 
 * On the remote node, the tx can potentially be accessed by multiple threads,
 * specially with function execution. This class extends lock and should be used
 * to synchronize access to the tx region state.
 * 
 * We _need_ at least region level locks rather than big TXState level locks
 * since GemFireXD global index maintenance can be invoked that will lead to
 * deadlocks with latter.
 * 
 * 
 * @author Darrel Schneider
 * 
 * @since 4.0
 * 
 * @see TXManagerImpl
 */
@SuppressWarnings("serial")
public final class TXRegionState extends ReentrantLock {

  // A map of Objects (entry keys) -> TXEntryState
  private final THashMapWithCreate entryMods;

  TObjectLongHashMapDSFID tailKeysForParallelWAN;

  // A map of Objects (entry keys) -> TXEntryUserAttrState
  //private HashMap uaMods;

  private transient final StoppableReentrantReadWriteLock.StoppableReadLock
      expiryReadLock;

  public transient final LocalRegion region;

  /**
   * The current {@link TXState} that owns this <code>TXRegionState</code>.
   */
  transient final TXState txState;

  private final boolean isPersistent;
  private transient VersionSource<?> versionSource;
  private transient boolean isRemoteVersionSource;

  /** holds the ops for uninitialized regions */
  private final ArrayList<Object> pendingTXOps;
  private final TIntArrayList pendingTXLockFlags;
  private List<Object> pendingGIITXOps;
  private int pendingGIILockFlag;
  private volatile boolean pendingGIITXLocked;
  /**
   * set to false after {@link #cleanup} has been invoked which disallows any
   * new operations on this TXRegionState
   */
  private boolean isValid;
  /** a sort order for commit/rollback if GII was not done */
  int finishOrder;

  // temporary transient variables
  private transient int tmpEntryCount;
  transient int numChanges;
  private transient TransactionException tmpEx;

  // These three hashmaps are for gemfirexd index maintenance
  private THashMapWithKeyPair transactionalIndexInfo;
  private THashMapWithKeyPair toBeReinstatedIndexesInfo;
  private THashMapWithKeyPair unaffectedIndexInfo;

  public TXRegionState(final LocalRegion r, final TXState tx) {
    // don't allow for expiration on replicated regions or eviction with local
    // destroy since some copies may have been expired/evicted while others may
    // not have been when the distributed transactional locks are taken
    if ((r.isEntryEvictionPossible() && !r.getEvictionAttributes().getAction()
        .isOverflowToDisk()) || (r.getDataPolicy().withReplication()
            && r.isEntryExpiryPossible())) {
      throw new UnsupportedOperationException(LocalizedStrings
          .TXRegionState_OPERATIONS_ON_EVICT_EXPIRY_REGION_ARE_NOT_ALLOWED_BECAUSE_THIS_THREAD_HAS_AN_ACTIVE_TRANSACTION
              .toLocalizedString(r.getFullPath()));
    }
    if (r.getPersistBackup() && !TXManagerImpl.ALLOW_PERSISTENT_TRANSACTIONS) {
      throw new UnsupportedOperationException(LocalizedStrings
          .TXRegionState_OPERATIONS_ON_PERSISTBACKUP_REGIONS_ARE_NOT_ALLOWED_BECAUSE_THIS_THREAD_HAS_AN_ACTIVE_TRANSACTION
              .toLocalizedString(r.getFullPath()));
    }
    // TODO: TX: why are global regions not supported?
    if (r.getScope().isGlobal()) {
      throw new UnsupportedOperationException(LocalizedStrings
          .TXRegionState_OPERATIONS_ON_GLOBAL_REGIONS_ARE_NOT_ALLOWED_BECAUSE_THIS_THREAD_HAS_AN_ACTIVE_TRANSACTION
              .toLocalizedString(r.getFullPath()));
    }

    if (r.isDestroyed()) {
      throw new TransactionDataRebalancedException(LocalizedStrings
          .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
              .toLocalizedString("<TX region create>", r.getFullPath()), r
              .getFullPath());
    }

    this.region = r;
    this.isPersistent = r.getDataPolicy().withPersistence();
    this.txState = tx;
    this.entryMods = new THashMapWithCreate(4);
    this.expiryReadLock = r.getTxEntryExpirationReadLock();
    this.isValid = true;

    if (!r.isInitialized() && r.getImageState().lockPendingTXRegionStates(true, false)) {
      try {
        if (!r.getImageState().addPendingTXRegionState(this)) {
          this.pendingTXOps = null;
          this.pendingTXLockFlags = null;
        } else {
          this.pendingTXOps = new ArrayList<Object>();
          this.pendingTXLockFlags = new TIntArrayList();
        }
      } finally {
        r.getImageState().unlockPendingTXRegionStates(true);
      }

    } else {
      this.pendingTXOps = null;
      this.pendingTXLockFlags = null;
    }
  }

  /**
   * NOT THREAD-SAFE; ONLY FOR TESTS
   */
  @SuppressWarnings("unchecked")
  public final Set<Object> getEntryKeys() {
    final THashSet keys = new THashSet(this.entryMods.size());
    this.entryMods.forEachKey(new TObjectProcedure() {
      public final boolean execute(final Object obj) {
        if (obj instanceof RegionEntry) {
          keys.add(((RegionEntry)obj).getKeyCopy());
        }
        else {
          keys.add(obj);
        }
        return true;
      }
    });
    return keys;
  }

  /**
   * Get the key to {@link TXEntryState} map.
   */
  final THashMapWithCreate getEntryMap() {
    if (this.isValid) {
      return this.entryMods;
    }
    else {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TRANSACTION_0_IS_NO_LONGER_ACTIVE
              .toLocalizedString(this.txState.getTransactionId()));
    }
  }

  /**
   * Gets raw handle to the map returned by {@link #getEntryMap()}. Do not use
   * unless sure that it is being used for read-only.
   */
  final THashMapWithCreate getInternalEntryMap() {
    return this.entryMods;
  }

  /**
   * NOT THREAD-SAFE.
   * This is called in case of hdfs enabled table to get newly
   * created entries in this tx.
   * As the iterator is on queue, this entry is not in the queue+hdfs.
   */
  
  @SuppressWarnings("unchecked")
  public final Set<Object> getCreatedEntryKeys() {
    final THashSet keys = new THashSet(this.entryMods.size());
    this.entryMods.forEachValue(new TObjectProcedure() {
      public final boolean execute(final Object obj) {
        if(obj instanceof TXEntryState) {
          if(((TXEntryState)obj).wasCreatedByTX()) {
            keys.add(((TXEntryState)obj).getUnderlyingRegionEntry());
          }
        }
        return true;
      }
    });
    return keys;
  }

  /**
   * Not thread-safe. Invoke only under the {@link #lock()} that takes a higher
   * level lock or in tests where only single thread is known to access the
   * region.
   * 
   * Returns either a {@link TXEntryState} object for a transactional entry
   * locked for write, while returns {@link RegionEntry} object for a
   * transactional entry locked for read.
   *
   * It should return old entry or new entry depending on version
   */
  public final Object readEntry(final Object entryKey) {
    return readEntry(entryKey, true);
  }

  /**
   * Not thread-safe. Invoke only under the {@link #lock()} that takes a higher
   * level lock or in tests where only single thread is known to access the
   * region.
   * 
   * Returns either a {@link TXEntryState} object for a transactional entry
   * locked for write, while returns {@link RegionEntry} object for a
   * transactional entry locked for read.
   */
  public final Object readEntry(final Object entryKey,
      final boolean checkValid) {
    if (!checkValid || this.isValid) {
      final THashMapWithCreate entryMap = this.entryMods;
      final Object txEntry = entryMap.size() > 0 ? entryMap.get(entryKey)
          : null;
      if (TXStateProxy.LOG_FINEST) {
        final LogWriterI18n logger = this.txState.getCache().getLoggerI18n();
        logger.info(LocalizedStrings.DEBUG,
            "TXRegionState#readEntry: return TX entry for key [" + entryKey
                + ",type=" + entryKey.getClass().getSimpleName() + "] for "
                + toString() + ": " + txEntry);
      }

      // suranjan we can check the version here, no need to check..as any entry in txr will have to be read anyway.
      return txEntry;
    }
    else {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TRANSACTION_0_IS_NO_LONGER_ACTIVE
              .toLocalizedString(this.txState.getTransactionId()));
    }
  }

  public final TXEntryState createEntry(final Object entryKey,
      final RegionEntry re, final Object val, final boolean doFullValueFlush) {
    final TXEntryState txes = GemFireCacheImpl.FactoryStatics.txEntryStateFactory
        .createEntry(entryKey, re, val, doFullValueFlush, this);
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = this.txState.getCache().getLoggerI18n();
      logger.info(LocalizedStrings.DEBUG, "TXRegionState#createEntry: for TX "
          + txState.txId.shortToString() + " created new TX entry for key ["
          + entryKey + ",type=" + entryKey.getClass().getName() + "] in region "
          + this.region.getFullPath() + " doFullValueFlush=" + doFullValueFlush
          + " with value=" + ArrayUtils.objectStringNonRecursive(val) + ": "
          + ArrayUtils.objectRefString(txes));
    }
    return txes;
  }

  public static final TXEntryState createEmptyEntry(final TXId txId,
      final Object entryKey, final RegionEntry re, final Object val,
      final String regionPath, final boolean doFullValueFlush) {
    final TXEntryState txes = GemFireCacheImpl.FactoryStatics.txEntryStateFactory
        .createEntry(entryKey, re, val, doFullValueFlush, null);
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = GemFireCacheImpl.getExisting()
          .getLoggerI18n();
      logger.info(LocalizedStrings.DEBUG, "TXRegionState#createEmptyEntry: "
          + "for TX " + txId.shortToString()
          + " created new TX entry for key [" + entryKey + "] in region "
          + regionPath + " doFullValueFlush=" + doFullValueFlush
          + " with value=" + ArrayUtils.objectStringNonRecursive(val) + ": "
          + ArrayUtils.objectRefString(txes));
    }
    return txes;
  }

  public final TXEntryState createReadEntry(final Object entryKey,
      final RegionEntry re, final Object val, final boolean doFullValueFlush) {
    final TXEntryState result = createEntry(entryKey, re, val,
        doFullValueFlush);
    getEntryMap().put(entryKey, result);
    return result;
  }

  public final TXState getTXState() {
    return this.txState;
  }

  /*
  public void rmEntry(Object entryKey, TXState txState, LocalRegion r) {
    rmEntryUserAttr(entryKey);
    TXEntryState e = (TXEntryState)this.entryMods.remove(entryKey);
    if (e != null) {
      e.cleanup(r);
    }
    if (this.uaMods == null && this.entryMods.size() == 0) {
      txState.rmRegion(r);
    }
  }

  public TXEntryUserAttrState readEntryUserAttr(Object entryKey) {
    TXEntryUserAttrState result = null;
    if (this.uaMods != null) {
      result = (TXEntryUserAttrState)this.uaMods.get(entryKey);
    }
    return result;
  }

  public TXEntryUserAttrState writeEntryUserAttr(Object entryKey, LocalRegion r) {
    if (this.uaMods == null) {
      this.uaMods = new HashMap();
    }
    TXEntryUserAttrState result = (TXEntryUserAttrState)this.uaMods
        .get(entryKey);
    if (result == null) {
      result = new TXEntryUserAttrState(r.basicGetEntryUserAttribute(entryKey));
      this.uaMods.put(entryKey, result);
    }
    return result;
  }

  public void rmEntryUserAttr(Object entryKey) {
    if (this.uaMods != null) {
      if (this.uaMods.remove(entryKey) != null) {
        if (this.uaMods.size() == 0) {
          this.uaMods = null;
        }
      }
    }
  }
  */

  /**
   * Returns the total number of modifications made by this transaction to this
   * region's entry count. The result will have a 1 for every create, a 0 for
   * every destroy of entry created in TX itself and -1 for every other destroy.
   */
  final int entryCountMod() {
    this.tmpEntryCount = 0;
    this.entryMods.forEachValue(new TObjectProcedure() {
      public final boolean execute(final Object txes) {
        if (txes instanceof TXEntryState) {
          tmpEntryCount += ((TXEntryState)txes).entryCountMod();
        }
        return true;
      }
    });
    return this.tmpEntryCount;
  }

  public final void setTransactionalIndexInfoMap(
      final THashMapWithKeyPair map) {
    this.transactionalIndexInfo = map;
  }
  
  public final void setUnaffectedIndexInfoMap(
      final THashMapWithKeyPair map) {
    this.unaffectedIndexInfo = map;
  }

  public final void setToBeReinstatedIndexMap(final THashMapWithKeyPair map) {
    this.toBeReinstatedIndexesInfo = map;
  }

  public final THashMapWithKeyPair getTransactionalIndexInfoMap() {
    return this.transactionalIndexInfo;
  }
  
  public final THashMapWithKeyPair getUnaffectedIndexInfoMap() {
    return this.unaffectedIndexInfo;
  }

  public final THashMapWithKeyPair getToBeReinstatedIndexMap() {
    return this.toBeReinstatedIndexesInfo;
  }

  /**
   * Check if GII for the underlying region including for TXRegionState, if any,
   * is complete. In case it is not complete, then it will also acquire the lock
   * to sync against a concurrent GII for this TXRegionState. The lock should be
   * released by invoking {@link #unlockGII()} or
   * {@link #addPendingTXOpAndUnlockGII}.
   * 
   * @return true if the underlying region has been initialized at least for
   *         this TXRegionState, and false otherwise in which case it will lock
   *         GII ops on this TXRegionState until {@link #unlockGII()} is invoked
   */
  public final boolean isInitializedAndLockGII() {
    if (this.pendingTXOps == null) {
      return true;
    }
    // if region is in the process of final initialization, then wait
    // for it since GII will also bring TXRegionState up-to-date
    final ImageState imgState = this.region.getImageState();
    return !imgState.lockPendingTXRegionStates(false, false);
  }

  public final void unlockGII() {
    final ImageState imgState = this.region.getImageState();
    imgState.unlockPendingTXRegionStates(false);
  }

  public final void addPendingTXOpAndUnlockGII(Object entry, int lockFlags) {
    lock();
    this.pendingTXOps.add(entry);
    this.pendingTXLockFlags.add(lockFlags);
    unlock();
    unlockGII();
  }

  public final void setGIITXOps(List<Object> entries, int lockFlags) {
    lock();
    // set the TXRegionState field
    for (Object entry : entries) {
      if (entry instanceof TXEntryState) {
        ((TXEntryState)entry).txRegionState = this;
      }
    }
    this.pendingGIITXOps = entries;
    this.pendingGIILockFlag = lockFlags;
    unlock();
  }

  public void applyPendingTXOps() {
    final TXState tx = this.txState;
    // reusable EntryEvent
    final EntryEventImpl eventTemplate = EntryEventImpl.create(null,
        Operation.UPDATE, null, null, null, true, null);
    eventTemplate.setTXState(tx);
    // apply as PUT DML so duplicate entry inserts etc. will go through fine
    eventTemplate.setPutDML(true);
    final LocalRegion region = this.region;
    final LocalRegion baseRegion;
    if (region.isUsedForPartitionedRegionBucket()) {
      baseRegion = region.getPartitionedRegion();
    }
    else {
      baseRegion = region;
    }

    lock();
    try {
      // first the GII ops, if any
      if (this.pendingGIITXOps != null) {
        final int lockFlags = this.pendingGIILockFlag;
        for (Object event : this.pendingGIITXOps) {
          if (TXStateProxy.LOG_FINE) {
            final LogWriterI18n logger = this.txState.getCache()
                .getLoggerI18n();
            logger.info(LocalizedStrings.DEBUG,
                "applyPendingTXOps: applying GII entry " + getEntryString(
                    event, TXStateProxy.LOG_FINEST) + " on " + toString());
          }
          tx.applyPendingOperation(event, lockFlags, this, region, baseRegion,
              eventTemplate, false, Boolean.FALSE, null);
        }
        this.pendingGIITXOps = null;
      }
      // next the pending TX ops in this list
      final int size = this.pendingTXOps.size();
      for (int index = 0; index < size; index++) {
        Object event = this.pendingTXOps.get(index);
        int lockFlags = this.pendingTXLockFlags.getQuick(index);
        if (TXStateProxy.LOG_FINE) {
          final LogWriterI18n logger = this.txState.getCache()
              .getLoggerI18n();
          logger.info(LocalizedStrings.DEBUG,
              "applyPendingTXOps: applying entry " + getEntryString(
                  event, TXStateProxy.LOG_FINEST) + " on " + toString());
        }
        tx.applyPendingOperation(event, lockFlags, this, region, baseRegion,
            eventTemplate, false, Boolean.FALSE, null);
      }
      this.pendingTXOps.clear();
      this.pendingTXLockFlags.clear();
    } finally {
      eventTemplate.release();
      unlock();
    }
  }

  private String getEntryString(Object entry, boolean verbose) {
    if (verbose) {
      return entry.toString();
    }
    else if (entry instanceof TXEntryState) {
      return ((TXEntryState)entry).shortToString();
    }
    else {
      return ArrayUtils.objectRefString(entry);
    }
  }

  public final VersionSource<?> getVersionSource() {
    VersionSource<?> vs = this.versionSource;
    if (vs != null) {
      return vs;
    }
    final LocalRegion r = this.region;
    if (!r.getConcurrencyChecksEnabled()) {
      return null;
    }
    final TXState tx = this.txState;
    // for non-persistent region, use the coordinator
    if (!this.isPersistent) {
      this.isRemoteVersionSource = !tx.isCoordinator();
      return (this.versionSource = tx.getCoordinator());
    }
    // else set from TXStateProxy
    THashMap versionSources = tx.getProxy().regionDiskVersionSources;
    if (versionSources != null) {
      vs = (VersionSource<?>)versionSources.get(r);
      if (vs != null) {
        this.isRemoteVersionSource = !vs.equals(r.getDiskStore()
            .getDiskStoreID());
        return (this.versionSource = vs);
      }
    }
    // version source not set in proxy for some reason?
    final LogWriterI18n logger = tx.getTxMgr().getLogger();
    logger.convertToLogWriter().warning(
        "Missing VersionSource for " + toString());
    // fallback to local diskId
    this.isRemoteVersionSource = false;
    return (this.versionSource = r.getDiskStore().getDiskStoreID());
  }

  public final boolean isRemoteVersionSource() {
    return this.isRemoteVersionSource;
  }

  public void commitPendingTXOps() {
    // first apply into TXRegionState and then commit it if required
    applyPendingTXOps();
    // initialize the base event offsets
    final TXState tx = getTXState();
    tx.initBaseEventOffsetsForCommit();
    // then commit to region
    lock();
    try {
      final LockingPolicy lockPolicy = tx.getLockingPolicy();
      final TransactionObserver observer = tx.getObserver();
      final LogWriterI18n logger = tx.getTxMgr().getLogger();
      final Collection<?> txess = this.entryMods.values();
        applyChangesStart(this.region);
        // we don't expect any conflicts etc. here due to pre-GII state
        for (Object entry : txess) {
          if (entry instanceof TXEntryState) {
            TXEntryState txes = (TXEntryState)entry;
            try {
              tx.commitEntryPhase1(txes, lockPolicy, null, observer);
            } catch (Throwable t) {
              handleGIICommitException(t, logger);
            }
          }
        }

      // now phase2, then cleanup
      List<EntryEventImpl> eventsToFree = null;
      try {
        final boolean logFine = TXStateProxy.LOG_FINE;
        boolean firstTime = true;
        EntryEventImpl cbEvent = tx.getProxy().newEntryEventImpl();
        GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
        final boolean reuseEV = !(cache.getOffHeapStore() != null && cache
            .isGFXDSystem());
        if (!reuseEV) {
          eventsToFree = new ArrayList<EntryEventImpl>();
        }
        for (Object entry : txess) {
          if (entry instanceof TXEntryState) {
            TXEntryState txes = (TXEntryState)entry;
            try {
              cbEvent = tx.commitEntryPhase2(txes, cbEvent, eventsToFree,
                  firstTime, reuseEV, logger, logFine);
              if (cbEvent == null) {
                break;
              }
              firstTime = false;
            } catch (Throwable t) {
              handleGIICommitException(t, logger);
            } finally {
              if (cbEvent != null) {
                if (reuseEV) {
                  cbEvent.release();
                }
                else {
                  eventsToFree.add(cbEvent);
                }
              }
            }
          }
        }
      } finally {
        try {
          tx.cleanupTXRS(new TXRegionState[] { this }, lockPolicy, LockMode.EX,
              true, false, observer);
        } catch (Throwable t) {
          handleGIICommitException(t, logger);
        }
        if (observer != null) {
          observer.afterIndividualCommit(tx.getProxy(), null);
        }
        if (eventsToFree != null) {
          for (EntryEventImpl ev : eventsToFree) {
            ev.release();
          }
        }
      }
    } finally {
      unlock();
    }
  }

  void handleGIICommitException(Throwable t, final LogWriterI18n logger) {
    Error err;
    if (t instanceof Error && SystemFailure.isJVMFailureError(err = (Error)t)) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    }
    logger.warning(LocalizedStrings.DEBUG,
        "Unexpected exception in TX GII commit", t);
  }

  final void applyChangesStart(LocalRegion r) {
    try {
      r.txLRUStart();
    } catch (RegionDestroyedException ex) {
      // Region was destroyed out from under us. So act as if the region
      // destroy happened right after the commit. We act this way by doing
      // nothing.
    } catch (BucketMovedException ex) {
      // Region was destroyed out from under us. So act as if the region
      // destroy happened right after the commit. We act this way by doing
      // nothing.
    } catch (CancelException ex) {
      // cache was closed out from under us; after conflict checking
      // passed. So do nothing.
    }
  }

  final void applyChangesEnd(LocalRegion r, boolean commit) {
    try {
      /*
      try {
        if (this.uaMods != null) {
          Iterator it = this.uaMods.entrySet().iterator();
          while (it.hasNext()) {
            Map.Entry me = (Map.Entry)it.next();
            Object eKey = me.getKey();
            TXEntryUserAttrState txes = (TXEntryUserAttrState)me.getValue();
            txes.applyChanges(r, eKey);
          }
        }
      } finally {
        r.txLRUEnd();
      }
      */
      r.txLRUEnd(commit);
    } catch (RegionDestroyedException ex) {
      // Region was destroyed out from under us. So act as if the region
      // destroy happened right after the commit. We act this way by doing
      // nothing.
    } catch (BucketMovedException ex) {
      // Region was destroyed out from under us. So act as if the region
      // destroy happened right after the commit. We act this way by doing
      // nothing.
    } catch (CancelException ex) {
      // cache was closed out from under us; after conflict checking
      // passed. So do nothing.
    }
  }

  final void lockPendingGII() {
    if (!this.pendingGIITXLocked) {
      this.pendingGIITXLocked = this.region.getImageState()
          .lockPendingTXRegionStates(false, false);
    }
  }

  final void unlockPendingGII() {
    if (this.pendingGIITXLocked) {
      this.pendingGIITXLocked = false;
      unlockGII();
    }
  }

  final TransactionException cleanup(final LockingPolicy lockPolicy,
      final LockMode writeMode, final Boolean forCommit,
      final boolean removeFromList, final TransactionException te) {
    final boolean rollback = (forCommit != null && !forCommit.booleanValue());
    final LocalRegion rgn = this.region;
    final TXState tx = getTXState();
    this.tmpEx = te;
    // check for GII in progress for the region
    if (this.pendingTXOps != null && this.pendingGIITXLocked) {
      // set the order for commit/rollback
      rgn.getImageState().setTXOrderForFinish(this);
    }
    else if (this.entryMods.size() > 0) {
      // cleanup indexes before releasing the locks
      // keep going even in case of exceptions
      try {
        tx.getProxy().updateIndexes(rollback, this.transactionalIndexInfo,
            this.toBeReinstatedIndexesInfo, this.unaffectedIndexInfo);
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        this.tmpEx = TXState.processCleanupException(t, this.tmpEx);
      }
      final TXId txId = tx.txId;
      final LockMode readMode = lockPolicy.getReadLockMode();
      this.numChanges = 0;
      this.entryMods.forEachValue(new TObjectProcedure() {
        public final boolean execute(final Object obj) {
          if (obj instanceof TXEntryState) {
            final TXEntryState txes = (TXEntryState)obj;
            try {
              // Now release all entry locks and for rollback also cleanup lock
              // entries created during operations
              final RegionEntry entry = txes.getUnderlyingRegionEntry();
              // remove the entry from map in case it was added due to put for
              // locking and we have to rollback
              if (entry != null && txes.entryCreatedForLock()
                  && (rollback || (!txes.hasPendingValue() && entry
                      .isDestroyedOrRemoved()))) {
                rgn.entries.removeEntry(entry.getKey(), entry, false);
              }
              if (txes.isDirty()) {
                numChanges++;
              }
              txes.cleanup(tx, rgn, lockPolicy, writeMode, removeFromList,
                  false, forCommit);
            } catch (Throwable t) {
              Error err;
              if (t instanceof Error && SystemFailure.isJVMFailureError(
                  err = (Error)t)) {
                SystemFailure.initiateFailure(err);
                // If this ever returns, rethrow the error. We're poisoned
                // now, so don't let this thread continue.
                throw err;
              }
              tmpEx = TXState.processCleanupException(t, tmpEx);
            }
            return true;
          }
          else {
            // case of read locked entry
            lockPolicy.releaseLock((AbstractRegionEntry)obj, readMode, txId,
                false, rgn);
            return true;
          }
        }
      });
    }
    this.transactionalIndexInfo = null;
    this.toBeReinstatedIndexesInfo = null;
    this.unaffectedIndexInfo = null;
    if (this.expiryReadLock != null) {
      this.expiryReadLock.unlock();
    }
    this.isValid = false;
    return this.tmpEx;
  }

  public final int getFinishOrder() {
    return this.finishOrder;
  }

  void processPendingExpires() {
    if (this.expiryReadLock != null) {
      this.region.processPendingExpires();
    }
  }

  int getChanges() {
    this.tmpEntryCount = 0;
    this.entryMods.forEachValue(new TObjectProcedure() {
      @Override
      public final boolean execute(final Object txes) {
        if (txes instanceof TXEntryState && ((TXEntryState)txes).isDirty()) {
          tmpEntryCount++;
        }
        return true;
      }
    });
    /*
    if (this.uaMods != null) {
      changes += this.uaMods.size();
    }
    */
    return this.tmpEntryCount;
  }

  @Override
  public final void lock() {
    super.lock();
    if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
      final LogWriterI18n logger = this.region.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "acquired lock on " + toString());
    }
  }

  @Override
  public final void unlock() {
    super.unlock();
    if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
      final LogWriterI18n logger = this.region.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "released lock on " + toString());
    }
  }

  @Override
  public final int hashCode() {
    return this.region.hashCode();
  }

  @Override
  public final String toString() {
    return ArrayUtils.objectRefString(this) + ",valid=" + this.isValid
        + ",dataRegion=" + this.region.getFullPath() + ','
        + this.txState.txId.shortToString() + ",TXState@0x"
        + Integer.toHexString(System.identityHashCode(txState));
  }

}
