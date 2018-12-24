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

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantReadWriteLock;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.concurrent.StoppableNonReentrantLock;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.gemstone.org.jgroups.util.StringId;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * Used on distributed replicated regions to track GII and various state.
 * Also used on pool regions to track register interest.
 * Note that currently a region will never have both a GII and RI in progress
 * at the same time.
 * @author Eric Zoerner
 * @author darrel
 */
public class UnsharedImageState implements ImageState {
  private final StoppableNonReentrantLock giiLock; // used for gii
  private final StoppableReentrantReadWriteLock riLock; // used for ri
  /**
   * Using CHS as a Set of keys
   */
  private volatile ConcurrentTHashSet<Object> destroyedEntryKeys;
  private volatile ConcurrentTHashSet<Object> deltaEntryKeys;
  private volatile boolean regionInvalidated = false;
  private volatile boolean mayDoRecovery = false;
  private volatile boolean inRecovery = false;
  private volatile boolean clearRegionFlag = false;
  private volatile RegionVersionVector clearRVV;
  private volatile boolean wasRegionClearedDuringGII = false;
  //private volatile DiskAccessException dae = null;
  private volatile ConcurrentTHashSet<VersionTagEntry> versionTags;
  private volatile ConcurrentTHashSet<VersionSource> leftMembers;

  /**
   * set of failed events during GII; used for GemFireXD failed events due to
   * constraint violations for replicated tables which should also be skipped if
   * these events are received directly
   */
  private ConcurrentTHashSet<EventID> failedEvents;
  /**
   * the set of pending TXRegionStates that have pending ops coming in before
   * GII is complete
   */
  private volatile UnifiedMap<TXId, TXRegionState> pendingTXRegionStates;
  private final NonReentrantReadWriteLock pendingTXRegionStatesLock;
  private volatile Thread pendingTXRegionStatesLockOwner;
  private final AtomicInteger pendingTXOrder;
  private volatile TObjectIntHashMap finishedTXIdOrders;

  UnsharedImageState(final boolean isClient,
                     final boolean isReplicate,
                     final boolean isLocal,
                     final boolean mayDoRecovery,
                     CancelCriterion stopper) {
    this.riLock = isClient ? new StoppableReentrantReadWriteLock(stopper) : null;
    
    this.giiLock = isReplicate ? new StoppableNonReentrantLock(stopper) : null;
    initDestroyedKeysMap();
    initDeltaKeysSet();
    initVersionTagsSet();
    initFailedMembersSet();
    this.mayDoRecovery = mayDoRecovery;
    if (mayDoRecovery) {
      this.inRecovery = true; // default to true to fix 41147
    }
    // we don't actually require much concurrency since putter will be only
    // the GII thread, but for multiple getters this is a concurrent set
    this.failedEvents = new ConcurrentTHashSet<EventID>(2);
    this.pendingTXRegionStates = isLocal ? null : new UnifiedMap<>();
    this.pendingTXRegionStatesLock = isLocal ? null
        : new NonReentrantReadWriteLock(stopper);
    this.pendingTXOrder = new AtomicInteger(0);
  }

  private void initDestroyedKeysMap() {
    this.destroyedEntryKeys = new ConcurrentTHashSet<Object>(4);
  }

  private void initDeltaKeysSet() {
    this.deltaEntryKeys = new ConcurrentTHashSet<Object>(4);
  }
  
  public boolean isReplicate() {
    return this.giiLock != null;
  }
  public boolean isClient() {
    return this.riLock != null;
  }
                              
  public void init() {
    if (isReplicate()) {
      this.wasRegionClearedDuringGII = false;
    }
  }

  private volatile boolean requestedDelta;

  @Override
  public void setRequestedUnappliedDelta(boolean flag) {
    requestedDelta = flag;
  }

  @Override
  public boolean requestedUnappliedDelta() {
    return requestedDelta;
  }

  public boolean getRegionInvalidated() {
    if (isReplicate()) {
      return this.regionInvalidated;
    } else {
      return false;
    }
  }
  
  public void setRegionInvalidated(boolean b) {
    if (isReplicate()) {
      this.regionInvalidated = b;
    }
  }  
  
  public void setInRecovery(boolean b) {
    if (this.mayDoRecovery) {
      this.inRecovery = b;
    }
  }
  
  public boolean getInRecovery() {
    if (this.mayDoRecovery) {
      return this.inRecovery;
    } else {
      return false;
    }
  }

  public void addDestroyedEntry(Object key) {
    // assert if ri then readLock held
    // assert if gii then lock held
    if (isReplicate() || isClient()) {
      this.destroyedEntryKeys.add(key);
    }
  }

  public void removeDestroyedEntry(Object key) {
    this.destroyedEntryKeys.remove(key);
  }
  
  public boolean hasDestroyedEntry(Object key) {
    return this.destroyedEntryKeys.contains(key);
  }

  public Iterator<Object> getDestroyedEntries() {
    // assert if ri then writeLock held
    // assert if gii then lock held
    Iterator<Object> result = this.destroyedEntryKeys.iterator();
    initDestroyedKeysMap();
    return result;
  }

  private void initVersionTagsSet() {
    this.versionTags = new ConcurrentTHashSet<VersionTagEntry>(4);
  }

  public void addVersionTag(Object key, VersionTag<?> tag) {
    this.versionTags.add(new VersionTagEntryImpl(key, tag.getMemberID(), tag.getRegionVersion()));
  }
  
  public Iterator<VersionTagEntry> getVersionTags() {
    Iterator<VersionTagEntry> result = this.versionTags.iterator();
    initVersionTagsSet();
    return result;
  }

  private void initFailedMembersSet() {
    this.leftMembers = new ConcurrentTHashSet<VersionSource>(4);
  }

  public void addLeftMember(VersionSource<?> mbr) {
    this.leftMembers.add(mbr);
  }
  
  public Set<VersionSource> getLeftMembers() {
    Set<VersionSource> result = this.leftMembers;
    initFailedMembersSet();
    return result;
  }
  
  public boolean hasLeftMembers() {
    return this.leftMembers.size() > 0;
  }

  public void dumpDestroyedEntryKeys(LogWriterI18n log) {
    StringId str = LocalizedStrings.DEBUG;
    if (this.destroyedEntryKeys == null) {
      log.info(str, "region has no destroyedEntryKeys in its image state");
    } else {
      log.info(str, "dump of image state destroyed entry keys of size " + this.destroyedEntryKeys.size());
      for (Iterator it = this.destroyedEntryKeys.iterator(); it.hasNext(); ) {
        Object key = it.next();
        log.info(str, "key=" + key);
      }
    }
  }

  /**
   *  returns count of entries that have been destroyed by concurrent operations
   *  while in token mode
   */
  public int getDestroyedEntriesCount() {
    return this.destroyedEntryKeys.size();
  }

  public void addDeltaEntry(Object key) {
    this.deltaEntryKeys.add(key);
  }

  public void removeDeltaEntry(Object key) {
    this.deltaEntryKeys.remove(key);
  }
  
  public boolean hasDeltaEntry(Object key) {
    return this.deltaEntryKeys.contains(key);
  }

  public Iterator<Object> getDeltaEntries() {
    Iterator<Object> result = this.deltaEntryKeys.iterator();
    // TODO: KN why is this re-initialized. Do we require
    // something similar for delta entries
    // initDestroyedKeysMap();
    return result;
  }
  
  @Override
  public void addFailedEvents(Collection<EventID> events) {
    this.failedEvents.addAll(events);
  }

  public boolean isFailedEvent(EventID eventId) {
    return eventId != null && this.failedEvents.contains(eventId);
  }

  @Override
  public void clearFailedEvents() {
    this.failedEvents.clear();
    this.failedEvents = new ConcurrentTHashSet<EventID>(1, 1, 0.8f, null, null);
  }

  public void setClearRegionFlag(boolean isClearOn, RegionVersionVector rvv) {
    if (isReplicate()) {
      this.clearRegionFlag = isClearOn;
      if (isClearOn) {
        this.clearRVV = rvv;  // will be used to selectively clear content
        this.wasRegionClearedDuringGII = true;
      }
    }
  }

  public boolean getClearRegionFlag() {
    if (isReplicate()) {
      return this.clearRegionFlag;
    } else {
      return false;
    }
  }
  
  public RegionVersionVector getClearRegionVersionVector() {
    if (isReplicate()) {
      return this.clearRVV;
    }
    return null;
  }
  
  /**
   * Returns true if a region clear was received on the region during a GII.
   * If true is returned the the flag is cleared.
   * This method is used by unit tests.
   */
  public boolean wasRegionClearedDuringGII() {
    if (isReplicate()) {
      boolean result = this.wasRegionClearedDuringGII;
      if (result) {
        this.wasRegionClearedDuringGII = false;
      }
      return result;
    } else {
      return false;
    }
  }

  public void lockGII() {
    this.giiLock.lock();
  }

  public void unlockGII() {
    this.giiLock.unlock();
  }

  public void readLockRI() {
    this.riLock.readLock().lock();
  }
  
  public void readUnlockRI() {
    this.riLock.readLock().unlock();
  }

  public void writeLockRI() {
    this.riLock.writeLock().lock();
  }
  
  public void writeUnlockRI() {
    this.riLock.writeLock().unlock();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean addPendingTXRegionState(TXRegionState txrs) {
    if (this.pendingTXRegionStates != null) {
      // don't add if the advisor has not been initialized yet, that is the
      // initial CreateRegionMessage replies are still on the wire so all
      // operations on TXRegionState are essentially ignored
      final LocalRegion region = txrs.region;
      if (region.isProfileExchanged()) {
        Object old;
        if ((old = this.pendingTXRegionStates.getIfAbsentPut(txrs.getTXState()
            .getTransactionId(), txrs)) != txrs) {
          Assert.fail("ImageState#addPendingTXRegionState: failed to add "
              + txrs + ", existing=" + old);
        }
        if (TXStateProxy.LOG_FINE) {
          final LogWriterI18n logger = region.getLogWriterI18n();
          logger.info(LocalizedStrings.DEBUG,
              "ImageState#addPendingTXRegionState: adding " + txrs);
        }
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void removePendingTXRegionState(TXId txId) {
    if (this.pendingTXRegionStates != null) {
      TXRegionState txrs;
      if ((txrs = (TXRegionState)this.pendingTXRegionStates.remove(
          txId)) != null) {
        if (TXStateProxy.LOG_FINE) {
          final LogWriterI18n logger = txrs.region.getLogWriterI18n();
          logger.info(LocalizedStrings.DEBUG,
              "ImageState#removePendingTXRegionState: removing " + txrs);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TXRegionState getPendingTXRegionState(TXId txId, boolean lock) {
    if (this.pendingTXRegionStates == null) {
      return null;
    }
    TXRegionState txrs = null;
    if (lock) {
      if (Thread.currentThread() != this.pendingTXRegionStatesLockOwner) {
        this.pendingTXRegionStatesLock.attemptReadLock(-1);
      }
      else {
        lock = false;
      }
    }
    try {
      if (this.pendingTXRegionStates != null) {
        txrs = this.pendingTXRegionStates.get(txId);
        if (TXStateProxy.LOG_FINE) {
          if (txrs != null) {
            final LogWriterI18n logger = txrs.region.getLogWriterI18n();
            logger.info(LocalizedStrings.DEBUG, "ImageState#"
                + "getPendingTXRegionState: result " + txrs + " for " + txId);
          }
        }
      }
    } finally {
      if (lock) {
        this.pendingTXRegionStatesLock.releaseReadLock();
      }
    }
    return txrs;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean lockPendingTXRegionStates(final boolean forWrite,
      final boolean force) {
    if (!force && this.pendingTXRegionStates == null) {
      return false;
    }
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
      if (logger != null) {
        logger.info(LocalizedStrings.DEBUG,
            "ImageState#lockPendingTXRegionStates: acquiring "
                + (forWrite ? "write" : "read") + " lock");
      }
    }
    if (forWrite) {
      this.pendingTXRegionStatesLock.attemptWriteLock(-1);
      this.pendingTXRegionStatesLockOwner = Thread.currentThread();
    }
    else {
      this.pendingTXRegionStatesLock.attemptReadLock(-1);
    }
    if (this.pendingTXRegionStates != null) {
      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null) {
          logger.info(LocalizedStrings.DEBUG,
              "ImageState#lockPendingTXRegionStates: lock acquired on "
                  + this.pendingTXRegionStatesLock);
        }
      }
      return true;
    }
    else {
      if (forWrite) {
        this.pendingTXRegionStatesLockOwner = null;
        this.pendingTXRegionStatesLock.releaseWriteLock();
      }
      else {
        this.pendingTXRegionStatesLock.releaseReadLock();
      }
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void unlockPendingTXRegionStates(final boolean forWrite) {
    if (this.pendingTXRegionStatesLock != null) {
      if (forWrite) {
        this.pendingTXRegionStatesLockOwner = null;
        this.pendingTXRegionStatesLock.releaseWriteLock();
      }
      else {
        this.pendingTXRegionStatesLock.releaseReadLock();
      }
      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null) {
          logger.info(LocalizedStrings.DEBUG, "ImageState#"
              + "unlockPendingTXRegionStates: " + (forWrite ? "write" : "read")
              + " lock released " + this.pendingTXRegionStatesLock);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<TXRegionState> getPendingTXRegionStates() {
    if (this.pendingTXRegionStates != null) {
      @SuppressWarnings("unchecked")
      final Collection<TXRegionState> result = this.pendingTXRegionStates
          .values();
      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null) {
          logger.info(LocalizedStrings.DEBUG,
              "ImageState#getPendingTXRegionStates: returning " + result);
        }
      }
      return result;
    }
    else {
      return Collections.emptySet();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTXOrderForFinish(TXRegionState txrs) {
    if (this.pendingTXRegionStatesLock != null) {
      TObjectIntHashMap finishedOrders;
      // assume read lock on pendingTXRegionStates is already held
      Assert.assertTrue(this.pendingTXRegionStatesLock.numReaders() > 0);
      if ((finishedOrders = this.finishedTXIdOrders) != null) {
        int order = finishedOrders.get(txrs.getTXState().getTransactionId());
        if (order != 0) {
          txrs.finishOrder = Math.abs(order);
          return;
        }
      }
    }
    txrs.finishOrder = this.pendingTXOrder.incrementAndGet();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFinishedTXOrder(TXId txId) {
    TObjectIntHashMap finishedOrders;
    if ((finishedOrders = this.finishedTXIdOrders) != null) {
      return finishedOrders.get(txId);
    }
    else {
      return 0;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void mergeFinishedTXOrders(final LocalRegion region,
      final Collection<TXId> txIds) {
    final UnifiedMap<TXId, TXRegionState> pendingTXRS = this.pendingTXRegionStates;
    if (pendingTXRS != null) {
      this.pendingTXRegionStatesLock.attemptWriteLock(-1);
      try {
        // first get the ordering for finished transactions from TX manager;
        // this is deliberately invoked under the lock to sync against
        // any concurrent getPendingTXOrder call
        final TXManagerImpl txMgr = region.getCache().getTxManager();
        TObjectIntHashMap txIdOrders = txMgr.finishedTXStates
            .getTXCommitOrders(txIds);
        // loop through existing TXRegionStates and reset the order to that
        // passed in txIdOrders
        // also conservatively increment the current order by the size of
        // txIdOrders since the numbering in txIdOrders will be 1, 2, 3, ...
        final int increment = txIdOrders.size();
        if (increment == 0) {
          return;
        }
        if (!pendingTXRS.isEmpty()) {
          for (Object e : pendingTXRS.values()) {
            TXRegionState txrs = (TXRegionState)e;
            if (txrs.finishOrder == 0) {
              continue;
            }
            int order = txIdOrders.get(txrs.getTXState().getTransactionId());
            if (order != 0) {
              txrs.finishOrder = Math.abs(order);
            }
            else {
              txrs.finishOrder += increment;
            }
          }
        }
        this.pendingTXOrder.addAndGet(increment);
        this.finishedTXIdOrders = txIdOrders;
      } finally {
        this.pendingTXRegionStatesLock.releaseWriteLock();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearPendingTXRegionStates(boolean reset) {
    if (reset) {
      // in this case only reset the list else null it out
      final UnifiedMap<TXId, TXRegionState> txrs = this.pendingTXRegionStates;
      if (txrs != null) {
        txrs.clear();
      }
      final TObjectIntHashMap txIds = this.finishedTXIdOrders;
      if (txIds != null) {
        txIds.clear();
      }
    }
    else {
      this.pendingTXRegionStates = null;
      this.finishedTXIdOrders = null;
    }
  }

  /** tracks RVV versions applied to the region during GII */
  private static final class VersionTagEntryImpl implements ImageState.VersionTagEntry {
    Object key;
    VersionSource member;
    long regionVersion;

    VersionTagEntryImpl(Object key, VersionSource<?> member, long regionVersion) {
      this.key = key;
      this.member = member;
      this.regionVersion = regionVersion;
    }
    public Object getKey() {
      return key;
    }
    public VersionSource getMemberID() {
      return member;
    }

    @Override
    public long getRegionVersion() {
      return regionVersion;
    }
    
    @Override
    public String toString() {
      return "{rv"+regionVersion+"; mbr="+member+"}";
    }
  }
}
