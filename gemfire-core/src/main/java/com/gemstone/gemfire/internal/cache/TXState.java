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

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValue;
import com.gemstone.gemfire.internal.cache.LocalRegion.IteratorType;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy.ReadEntryUnderLock;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.concurrent.MapCallback;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THash;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TObjectHashingStrategy;
import com.gemstone.gnu.trove.TObjectProcedure;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * TXState is the entity that tracks the transaction state on a per thread
 * basis, noting changes to Region entries on a per operation basis. It lives on
 * the node where transaction data exists.
 * 
 * @author Mitch Thomas
 * @author swale
 * 
 * @since 4.0
 * 
 * @see TXManagerImpl
 */
public final class TXState implements TXStateInterface {

  // A map of transaction state for all regions participating in this TX locally
  // in this VM.
  private final ConcurrentTHashSet<TXRegionState> regions;

  private final BlockingQueue<VersionInformation> queue = new LinkedBlockingQueue<VersionInformation>();

  static final TXRegionState[] ZERO_REGIONS = new TXRegionState[0];

  /** the set of regions that will be committed or rolled back */
  private TXRegionState[] finalizeRegions = ZERO_REGIONS;

  /** hashing strategy used for {@link #regions} to allow search using region */
  @SuppressWarnings("serial")
  static final TObjectHashingStrategy compareTXRS =
      new TObjectHashingStrategy() {
    @Override
    public boolean equals(Object o1, Object o2) {
      if (o1 == o2) {
        return true;
      }
      if (o1 instanceof TXRegionState) {
        return ((TXRegionState)o1).region == o2;
      }
      else if (o2 instanceof TXRegionState) {
        return ((TXRegionState)o2).region == o1;
      }
      else {
        return false;
      }
    }

    @Override
    public int computeHashCode(Object o) {
      return o.hashCode();
    }
  };

  volatile State state;

  Map<String, Map<VersionSource,RegionVersionHolder>> snapshot;

  private final Map<Region, Boolean> writeRegions = new ConcurrentHashMap<>();

  /*
  private TXLockRequest locks = null;

  /**
   * Used to hand out modification serial numbers used to preserve the order of
   * operation done by this transaction.
   *
  private final AtomicInteger modSerialNum;
  */

  private final ArrayList<EntryEventImpl> pendingCallbacks;

  /**
   * List of batched pending ops to be flushed to remote nodes.
   */
  final ArrayList<Object> pendingOps;
  final ArrayList<Object> pendingOpsRegions;

  /**
   * Number of pending operations. This can be different from the size of
   * {@link #pendingOps} since we avoid deleting from the list unless required.
   */
  private volatile int numPendingOperations;

  /**
   * Used to generate eventIDs
   */
  private byte[] baseMembershipId;

  /**
   * Used to generate eventIDs
   */
  private long baseThreadId;

  /**
   * Used to generate eventIDs
   */
  private long baseSequenceId;

  private EventID.ThreadAndSequenceIDWrapper baseSequenceIdGenerator;

  private final TXManagerImpl txManager;

  private final TXStateProxy proxy;

  final TXId txId;

  private final LockingPolicy lockPolicy;

  /**
   * The head of the circular doubly linked list used to maintain the modified
   * entries in order.
   */
  final TXEntryState head;

  /**
   * Lock for the {@link #head}.
   */
  final NonReentrantLock headLock;

  /**
   * Lock for the TXState during commit/rollback.
   */
  final NonReentrantLock txLock;
  private final AtomicBoolean txLocked;

  /**
   * Denotes the state of this TXState.
   */
  static enum State {
    OPEN {
      public boolean isOpen() { return true;}
    },
    FINISH_STARTED,
    COMMIT_PHASE1_DONE {
      @Override
      public boolean isPhase1Done() {
        return true;
      }
    },
    CLOSED_COMMIT {
      @Override
      public boolean isPhase1Done() {
        return true;
      }
      @Override
      public boolean isClosed() {
        return true;
      }
    },
    CLOSED_ROLLBACK {
      @Override
      public boolean isPhase1Done() {
        return true;
      }
      @Override
      public boolean isClosed() {
        return true;
      }
    },
    ;

    public boolean isPhase1Done() {
      return false;
    }

    public boolean isClosed() {
      return false;
    }

    public boolean isOpen() {
      return false;
    }
  }

  /**
   * An ArrayList that avoids removing elements and instead marks them as null
   * that can be reused in a subsequent add. Also it traverses the list in
   * reverse order optimizing for stack kind of usage. To be typically used for
   * small lists that want to avoid reallocations in remove.
   */
  public static final class ArrayListAppend extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    public ArrayListAppend() {
    }

    public ArrayListAppend(int initialCapacity) {
      super(initialCapacity);
    }

    @Override
    public boolean add(final Object obj) {
      addObj(obj);
      return true;
    }

    public void append(final Object obj) {
      super.add(obj);
    }

    public int addObj(final Object obj) {
      int index = size();
      // don't try to search the entire list for empty slot if it is large
      int end = (index - 5);
      if (end < 0) {
        end = 0;
      }
      while (--index >= end) {
        if (get(index) == null) {
          set(index, obj);
          return index;
        }
      }
      super.add(obj);
      return -1;
    }

    public int addIfAbsent(final Object obj) {
      int emptyIndex = -1;
      int index;
      Object o;
      for (index = size() - 1; index >= 0; index--) {
        o = get(index);
        if (o == obj) {
          return index;
        }
        if (o == null) {
          emptyIndex = index;
          break;
        }
      }
      if (index >= 0) {
        while (--index >= 0) {
          o = get(index);
          if (o == obj) {
            return index;
          }
        }
        set(emptyIndex, obj);
        return emptyIndex;
      }
      super.add(obj);
      return -1;
    }

    public int removeObj(final Object obj) {
      for (int index = size() - 1; index >= 0; index--) {
        if (get(index) == obj) {
          set(index, null);
          return index;
        }
      }
      return -1;
    }

    public int find(final Object obj) {
      for (int index = size() - 1; index >= 0; index--) {
        if (get(index) == obj) {
          return index;
        }
      }
      return -1;
    }
  }

  // below two are used for pending read locks
  public static final int PENDING_LOCKS_NUM_ARRAYS = 3;
  private static final int PENDING_LOCKS_DEFAULT_SIZE = 4;

  private boolean firedWriter;

  private final boolean isGFXD;

  // Return values from lockEntry method. These should be bitmasks that can be
  // combined and passed into lockEntry methods.

  /**
   * Indicates that RegionEntry was not present after acquiring the lock.
   */
  public static final int LOCK_ENTRY_NOT_FOUND = 0x1;

  /**
   * Indicates that a lock on entry was added for this TX successfully.
   */
  public static final int LOCK_ADDED = 0x2;

  /**
   * Indicates that a zero duration {@link LockMode#READ_ONLY} or
   * {@link LockMode#SH} lock on entry was added and then released successfully.
   */
  public static final int LOCK_ZERO_DURATION = 0x4;

  /**
   * Indicates that a lock on entry was already taken in this TX previously.
   */
  public static final int LOCK_ALREADY_HELD_IN_TX = 0x8;

  /**
   * Indicates that a lock on entry was already taken in this TX previously but
   * no current value exists (entry destroyed in TX or only locked for reading).
   */
  public static final int LOCK_ALREADY_HELD_IN_TX_NO_VALUE = 0x10;

  /**
   * Should only be created by {@link TXStateProxy}.
   */
  TXState(final TXStateProxy proxy) {
    // don't care about concurrency here; just want to make the map thread-safe
    // using CM rather than synchronized maps to avoid read contention
    this.regions = new ConcurrentTHashSet<TXRegionState>(1, 3,
        THash.DEFAULT_LOAD_FACTOR, compareTXRS, null);
    this.pendingCallbacks = null; // no pending new ArrayList<EntryEventImpl>();
    this.pendingOps = new ArrayList<Object>(4);
    this.pendingOpsRegions = new ArrayList<Object>(4);
    this.numPendingOperations = 0;
    this.txManager = proxy.getTxMgr();
    this.proxy = proxy;
    this.txId = proxy.getTransactionId();
    this.lockPolicy = proxy.getLockingPolicy();
    // dummy head
    this.head = new TXEntryState(null, null, null, false, null);
    this.head.next = this.head.previous = this.head;
    this.headLock = this.proxy.lock;
    this.txLock = new NonReentrantLock(true);
    this.txLocked = new AtomicBoolean(false);
    this.isGFXD = this.proxy.isGFXD;
    this.state = State.OPEN;

    // We don't know the semantics for RR, so ideally there shouldn't be snapshot for it.
    // Need to disable it.
    if (isSnapshot() && getCache().snapshotEnabled()) {
      takeSnapshot(this.txId);
    } else {
      this.snapshot = null;
    }

    if (TXStateProxy.LOG_FINE) {
      this.txManager.getLogger().info(LocalizedStrings.DEBUG,
          toString() + ": created.");
    }
  }

  public void takeSnapshot(TXId txId) {
    this.snapshot = getCache().getSnapshotRVV(txId);
    if (TXStateProxy.LOG_FINE) {
      this.txManager.getLogger().info(LocalizedStrings.DEBUG,
          " The snapshot taken in txStats is " + this.snapshot);
    }
  }

  /**
   * Special equals implementation for TX lock upgrade to enable using TXState
   * as the owner, that will allow re-entrancy by pretending that null current
   * owner is okay.
   */
  @Override
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WITH_THIS")
  public final boolean equals(final Object other) {
    if (other == null || other == this) {
      return true;
    }
    if (other instanceof TXId) {
      return ((TXId)other).equals(this.txId);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return this.txId.hashCode();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append(this.getClass().getSimpleName()).append("@(");
    final TXId txId = this.txId;
    if (txId != null) {
      txId.appendToString(sb, this.txManager.getDM().getSystem());
    }
    sb.append(",state=").append(this.state);
    sb.append('{').append(getIsolationLevel()).append('}');
    if (isJTA()) {
      sb.append("{isJTA}");
    }
    if (isCoordinator()) {
      sb.append("{COORDINATOR}");
    }
    sb.append("@0x").append(Integer.toHexString(System.identityHashCode(this)))
        .append(')');
    return sb.toString();
  }

  public final TXId getTransactionId() {
    return this.txId;
  }

  public final LockingPolicy getLockingPolicy() {
    return this.lockPolicy;
  }

  public final IsolationLevel getIsolationLevel() {
    return this.lockPolicy.getIsolationLevel();
  }

  public final TXState getLocalTXState() {
    return this;
  }

  public final TXState getTXStateForWrite() {
    return this;
  }

  public final TXState getTXStateForRead() {
    return this;
  }

  public final TXStateProxy getProxy() {
    return this.proxy;
  }

  public void firePendingCallbacks() {
    final ArrayList<EntryEventImpl> callbacks = getPendingCallbacks();
    if (callbacks == null || callbacks.isEmpty()) {
      return;
    }
    for (EntryEventImpl ee : callbacks) {
      final Operation op = ee.getOperation();
      final LocalRegion region = ee.getRegion();
      if (op.isCreate()) {
        region.invokeTXCallbacks(EnumListenerEvent.AFTER_CREATE, ee, true, true /*notifyGateway*/);
      }
      else if (op.isDestroy()) {
        region.invokeTXCallbacks(EnumListenerEvent.AFTER_DESTROY, ee, true, true /*notifyGateway*/);
      }
      else if (op.isInvalidate()) {
        region.invokeTXCallbacks(EnumListenerEvent.AFTER_INVALIDATE, ee, true, true /*notifyGateway*/);
      }
      else {
        region.invokeTXCallbacks(EnumListenerEvent.AFTER_UPDATE, ee, true, true /*notifyGateway*/);
      }
    }
  }

  public final ArrayList<EntryEventImpl> getPendingCallbacks() {
    return this.pendingCallbacks;
  }

  public final TXRegionState readRegion(final LocalRegion r) {
    return r != null ? this.regions.get(r) : null;
  }

  public void rmRegion(final LocalRegion r) {
    final TXRegionState txr = this.regions.removeKey(r);
    if (txr != null) {
      final LockingPolicy lockPolicy = getLockingPolicy();
      txr.lock();
      try {
        txr.cleanup(lockPolicy, lockPolicy.getWriteLockMode(), false, true,
            null);
        txr.processPendingExpires();
        txr.cleanupSnapshotRegionEntries();
      } finally {
        txr.unlock();
      }
    }
    if (isEmpty()) {
      getProxy().removeSelfFromHostedIfEmpty(null);
    }
  }

  void cleanSnapshotEntriesForRegion(LocalRegion r) {
    final TXRegionState txr = this.regions.get(r);
    if (txr != null) {
      txr.lock();
      try {
        txr.cleanupSnapshotRegionEntries();
      } finally {
        txr.unlock();
      }
    }
  }

  /**
   * Creates a {@link TXRegionState} if not present for
   * {@link CustomEntryConcurrentHashMap#create(Object,
   *    MapCallback, Object, Object, boolean)}.
   */
  private static final class TXRegionCreator extends
      MapCallbackAdapter<LocalRegion, TXRegionState, TXState, Boolean> {

    private final boolean forRead;

    TXRegionCreator(boolean forRead) {
      this.forRead = forRead;
    }

    @Override
    public final TXRegionState newValue(final LocalRegion r,
        final TXState txState, final Boolean doCheck, final MapResult result) {
      // first check that commit/rollback has not already started
      final boolean checkTXState = doCheck.booleanValue();
      if (checkTXState && txState.state != State.OPEN) {
        throw new IllegalTransactionStateException(
            LocalizedStrings.TRANSACTION_0_IS_NO_LONGER_ACTIVE
                .toLocalizedString(txState.getTransactionId()));
      }
      // add as affected region to the proxy if required
      TXRegionState txr;
      final boolean initialized = r.isInitialized();
      boolean addAffectedRegion = this.forRead ? true : TXStateProxy
          .remoteMessageUsesTXProxy();
      if (initialized || (txr = r.getImageState().getPendingTXRegionState(
          txState.getTransactionId(), true)) == null) {
        final boolean doLog = TXStateProxy.LOG_FINE
            | ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT;
        txr = new TXRegionState(r, txState);
        if (!initialized && !r.isProfileExchanged()) {
          // if region has not even finished receiving CreateRegionMessage
          // replies then don't add to TXState regions map since we may even
          // miss receiving commit messages later
          // send back a valid TXRegionState for operation to continue in normal
          // flow but it will be discarded at the end of operation
          result.setNewValueCreated(false);
          addAffectedRegion = false;
          if (doLog) {
            final LogWriterI18n logger = txState.getTxMgr().getLogger();
            logger.info(LocalizedStrings.DEBUG, "skip adding new "
                + ArrayUtils.objectRefString(txr) + " for region "
                + r.getFullPath() + '[' + ArrayUtils.objectRefString(r)
                + "], to tx: " + txState.getProxy());
          }
        }
        else if (doLog) {
          final LogWriterI18n logger = txState.getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "added new "
              + ArrayUtils.objectRefString(txr) + " for region "
              + r.getFullPath() + '[' + ArrayUtils.objectRefString(r)
              + "], addAffectedRegionToProxy=" + addAffectedRegion
              + ", tx: " + txState.getProxy());
        }
      }
      else {
        if (TXStateProxy.LOG_FINEST) {
          final LogWriterI18n logger = txState.getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "returning existing "
              + ArrayUtils.objectRefString(txr) + " for region "
              + r.getFullPath() + '[' + ArrayUtils.objectRefString(r)
              + "], tx: " + txState.getProxy());
        }
      }
      if (addAffectedRegion) {
        txState.getProxy().addAffectedRegion(r, checkTXState);
      }

      return txr;
    }

    @Override
    public void onToArray(final TXState txState) {
      txState.state = State.FINISH_STARTED;
    }
  };

  private static final TXRegionCreator txRegionCreator =
      new TXRegionCreator(false);
  private static final TXRegionCreator txRegionCreatorForRead =
      new TXRegionCreator(true);

  /**
   * Used by transaction operations that are doing a write operation on the
   * specified region.
   * 
   * @return the TXRegionState for the given LocalRegion creating a new one if
   *         none exists
   */
  public final TXRegionState writeRegion(LocalRegion r)
      throws TransactionException {
    return this.regions.create(r, txRegionCreator, this, Boolean.TRUE);
  }

  /**
   * Used by transaction operations that are doing a write operation on the
   * specified region.
   * 
   * @return the TXRegionState for the given LocalRegion creating a new one if
   *         none exists
   */
  public final TXRegionState writeRegion(final LocalRegion r,
      final Boolean checkForTXFinish) throws TransactionException {
    return this.regions.create(r, txRegionCreator, this, checkForTXFinish);
  }

  /**
   * Used by transaction operations that are doing a read operation on the
   * specified region.
   * 
   * @return the TXRegionState for the given LocalRegion creating a new one if
   *         none exists
   */
  public final TXRegionState writeRegionForRead(LocalRegion r)
      throws TransactionException {
    return this.regions.create(r, txRegionCreatorForRead, this, Boolean.TRUE);
  }

  /**
   * Used by transaction operations that are doing a read operation on the
   * specified region.
   * 
   * @return the TXRegionState for the given LocalRegion creating a new one if
   *         none exists
   */
  public final TXRegionState writeRegionForRead(final LocalRegion r,
      final Boolean checkForTXFinish) throws TransactionException {
    return this.regions.create(r, txRegionCreatorForRead, this,
        checkForTXFinish);
  }

  public final long getBeginTime() {
    return this.proxy.getBeginTime();
  }

  public int getChanges() {
    int changes = 0;
    // no lock required here since this will be during commit/rollback
    // processing that is already locked by proxy.lock()
    final TXRegionState[] finalRegions = this.finalizeRegions;
    if (finalRegions != ZERO_REGIONS) {
      for (TXRegionState txrs : finalRegions) {
        // we already have the number of changes calculated during cleanup
        changes += txrs.numChanges;
      }
      return changes;
    }
    for (TXRegionState txrs : this.regions) {
      txrs.lock();
      try {
        changes += txrs.getChanges();
      } finally {
        txrs.unlock();
      }
    }
    return changes;
  }

  @Override
  public final boolean isInProgress() {
    return !this.state.isClosed();
  }

  public final boolean isOpen() {
    return this.state.isOpen();
  }

  @Override
  public boolean isClosed() {
    return this.state.isClosed();
  }

  public final boolean isCommitted() {
    return this.state == State.CLOSED_COMMIT;
  }

  public final boolean isRolledBack() {
    return this.state == State.CLOSED_ROLLBACK;
  }

  final byte[] getBaseMembershipId() {
    return this.baseMembershipId;
  }

  final long getBaseThreadId() {
    return this.baseThreadId;
  }

  final long getBaseSequenceId() {
    return this.baseSequenceId;
  }

  public final void applyPendingOperation(Object entry, int lockFlags,
      final TXRegionState txrs, final LocalRegion region,
      LocalRegion baseRegion, EntryEventImpl eventTemplate,
      boolean checkInitialized, Boolean checkForTXFinish,
      AbstractOperationMessage msg) {
    TXEntryState txes;
    RegionEntry re;
    Object key;

    if (entry instanceof TXEntryState) {
      txes = (TXEntryState)entry;
      txes.txRegionState = txrs;
      txes.performOpForRegionEntry(this, eventTemplate, lockFlags,
          checkInitialized, checkForTXFinish, msg);
    }
    else {
      if (!checkInitialized || txrs.isInitializedAndLockGII()) {
        if (!(entry instanceof AbstractRegionEntry)) {
          // object is still a key
          key = entry;
          re = baseRegion.basicGetEntryForLock(region, key);
        }
        else {
          re = (AbstractRegionEntry)entry;
          key = re.getKey();
        }
        // nothing to be done if RegionEntry is null
        if (re != null) {
          // flags are not used by NULL_READER below so can stuff in
          // conflictWithEX without worrying for possible overlaps
          lockEntryForRead(this.lockPolicy, re, key, region, txrs, this.txId, this,
              lockFlags, false, false, checkForTXFinish,
              LockingPolicy.NULL_READER);
        }
      }
      else {
        // add to pending TX ops list and continue
        txrs.addPendingTXOpAndUnlockGII(entry, lockFlags);
      }
    }
  }

  /**
   * @see TXStateInterface#commit(Object)
   */
  public void commit(final Object callbackArg) throws TransactionException {
    final State state = this.state;
    if (state.isClosed()) {
      return;
    }

    boolean commit = false;
    final TransactionObserver observer = getObserver();
    if (observer != null) {
      observer.duringIndividualCommit(this.proxy, callbackArg);
    }
    List<EntryEventImpl> eventsToFree = null;
    try {
      /*
       * Lock buckets so they can't be rebalanced.
       *
      try {
        lockBucketRegions();
      } catch (PrimaryBucketException pbe) {
        // ignore this for now. Neeraj
        // not sure what to do here yet
//        RuntimeException re = new TransactionDataNotColocatedException(
//            LocalizedStrings.PartitionedRegion_WHILE_COMMITTING_KEY_NOT_COLOCATED_WITH_TRANSACTION
//                .toLocalizedString());
//        re.initCause(pbe);
//        throw re;
      }
      */

      // the first phase of commit will do any lock upgrades and
      // pending SH lock cleanup, if missed, during scans etc.
      commitPhase1();

      // phase1 success; now we cannot rollback
      commit = true;

      if (observer != null) {
        observer.afterIndividualCommitPhase1(this.proxy, callbackArg);
      }

      // apply changes to the cache
      eventsToFree = commitPhase2();

      // For internal testing
      // if (this.internalAfterApplyChanges != null) {
      // this.internalAfterApplyChanges.run();
      // }

      firePendingCallbacks();

    } finally {
      cleanup(commit, observer);
      if (observer != null) {
        observer.afterIndividualCommit(this.proxy, callbackArg);
      }
      if(eventsToFree != null) {
        for(EntryEventImpl ev:eventsToFree) {
          ev.release();
        }
      }
    }
  }

  /**
   * @see TXStateInterface#rollback(Object)
   */
  public void rollback(final Object callbackArg) {
    if (this.state.isClosed()) {
      return;
    }
    // lock the TXRegionStates against GII and TXState
    lockTXRSAndTXState();
    TransactionException cleanEx = null;
    final TransactionObserver observer = getObserver();
    if (observer != null) {
      observer.duringIndividualRollback(this.proxy, callbackArg);
      try {
        cleanup(false, observer);

      } finally {
        observer.afterIndividualRollback(this.proxy, callbackArg);
      }
    }
    else {
      cleanup(false, null);
    }
  }


  public final void flushPendingOps(final DM dm) {
    this.proxy.flushPendingOps(dm);
  }

  public void setObserver(final TransactionObserver observer) {
    this.proxy.setObserver(observer);
  }

  public TransactionObserver getObserver() {
    return this.proxy.getObserver();
  }

  /*
  /** single threaded in commit, so no sync required here *
  private void lockBucketRegions() throws PrimaryBucketException {
    for (final LocalRegion r : this.regions.keySet()) {
      if (r.isUsedForPartitionedRegionBucket()) {
        // Lock the bucket so it doesnt get destroyed until we cleanup!
        try {
          ((BucketRegion)r).doLockForPrimary();
        } catch (RegionDestroyedException rde) {
          // ignore this
          if (this.logFine) {
            final LogWriterI18n logger = r.getCache().getLoggerI18n();
            logger.info(LocalizedStrings.DEBUG, "RegionDestroyedException "
                + "while locking bucket region " + r.getFullPath(), rde);
          }
        }
      }
    }
    gotBucketLocks = true;
  }
  */

  /**
   * The first phase of commit upgrades the write locks to exclusive mode, and
   * cleans up any pending SH locks. Note that it is possible for commitPhase1
   * to throw {@link ConflictException}s in REPEATABLE_READ during EX_SH to EX
   * lock upgrade.
   */
  final void commitPhase1() throws TransactionException {

    // return if phase1 commit already done
    final State state = this.state;
    if (state != null && state.isPhase1Done()) {
      return;
    }

    final TransactionObserver observer = getObserver();
    THashMap toBePublishedEvents = this.proxy.eventsToBePublished;

    // take a snapshot of regions to be committed since this can change
    // due to GII (and we don't want to block the entire GII)
    final TXRegionState[] txrss = this.finalizeRegions = this.regions.toArray(
        ZERO_REGIONS, txRegionCreator, this);
    for (TXRegionState txrs : txrss) {
      final LocalRegion region = txrs.region;

      txrs.applyChangesStart(region);
      if (region.isUsedForPartitionedRegionBucket()) {
        final BucketRegion br = (BucketRegion)region;
        final PartitionedRegion pr = br.getPartitionedRegion();
        if (pr.isLocalParallelWanEnabled() || pr.isHDFSRegion()) {
          if (toBePublishedEvents == null) {
            toBePublishedEvents = new THashMap();
            // For a full commit, setting eventsTobePublished ensures that the
            // tailKeys generated in this phase are set on the respective events
            // in the Phase2.
            // For two phase commit, setting eventsToBePublished does not make
            // any difference as the consolidated list of events received from
            // coordinator is set before phase 2.
            getProxy().addToBePublishedEvents(toBePublishedEvents);
          }
          txrs.tailKeysForParallelWAN = new TObjectLongHashMapDSFID(
              THash.DEFAULT_INITIAL_CAPACITY, THash.DEFAULT_LOAD_FACTOR, null,
              -1L);
          toBePublishedEvents
              .put(br.getFullPath(), txrs.tailKeysForParallelWAN);
        }
      }
    }

    // acquire the lock on TXRegionStates and TXState if required
    lockTXRSAndTXState();

    final LockingPolicy lockPolicy = getLockingPolicy();
    // release all pending read locks first, if any
    pendingReadLocksCleanup(lockPolicy, null, null);

    initBaseEventOffsetsForCommit();

    final TXEntryState head = this.head;
    TXEntryState currentEntry = head.next;
    // upgrade all the locks to EX mode atomically to force any new readers to
    // wait for TX to complete (existing readers can still see partially
    // committed data); don't care for owner since TXState will guarantee
    // correct so the dummy owner object allows successful comparison against
    // null owner
    while (currentEntry != head) {
      commitEntryPhase1(currentEntry, lockPolicy, toBePublishedEvents,
          observer);
      currentEntry = currentEntry.next;
    }

    this.state = State.COMMIT_PHASE1_DONE;
  }

  final void commitEntryPhase1(TXEntryState entry,
      final LockingPolicy lockPolicy, THashMap toBePublishedEvents,
      final TransactionObserver observer) {
    RegionEntry re;
    entry.generateEventOffsets(this);
    if (entry.isDirty() && (re = entry.regionEntry) != null) {

      assert re.hasExclusiveSharedLock(null, null):
        "unexpected absence of EX_SH lock during atomic upgrade";

      if (observer != null) {
        observer.beforeIndividualLockUpgradeInCommit(this.proxy, entry);
      }

      final TXRegionState txrs = entry.txRegionState;
      final LocalRegion dataRegion = txrs.region;

      lockPolicy.acquireLock(re, LockMode.EX, 0, this, dataRegion, null);
      // also mark RE as updating for < REPEATABLE_READ reads that do not
      // acquire read locks
      // TODO: PERF: we could combine this with EX lock above or even
      // just combine the two to say the UPDATE_IN_PROGRESS is same as EX
      // lock which will also fix many cases of non-TX + TX
      re.setUpdateInProgress(true);
      // Store the generated value in the Map to send it back to the
      // co-ordinator
      if (toBePublishedEvents != null) {
        final TObjectLongHashMapDSFID keyToTailKeyMap =
            txrs.tailKeysForParallelWAN;
        if (keyToTailKeyMap != null) {
          long reserveSeqNum = ((BucketRegion)dataRegion)
              .reserveWANSeqNumber(false);
          if (reserveSeqNum != -1) {
            keyToTailKeyMap.put(entry.regionKey, reserveSeqNum);
          }
        }
      }
    }
  }

  static final class SetKeyRegionContext implements TObjectProcedure {

    PartitionedRegion pr;

    @Override
    public boolean execute(Object key) {
      if (key instanceof KeyWithRegionContext) {
        ((KeyWithRegionContext)key).setRegionContext(this.pr);
      }
      return true;
    }
  }

  /**
   * The second phase of commit actually applies the changes to the underlying
   * regions in the cache also generating the EventID offsets. It is expected
   * that {@link #commitPhase1()} has already been invoked.
   * @return List of EntryEventImpl from which to free the offheap resources
   * after the cleanup is done. If the system is not (gfxd + offheap), the 
   * List will be null, as the offheap resources will be released in this
   * function itself.
   */
  private final List<EntryEventImpl> commitPhase2() {
    final LogWriterI18n logger = getTxMgr().getLogger();
    final boolean logFine = TXStateProxy.LOG_FINE;

    final TXEntryState head = this.head;
    TXEntryState currentEntry = head.next;
    EntryEventImpl cbEvent = this.proxy.newEntryEventImpl();
    List<EntryEventImpl> eventsToFree = null;
    GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
    final boolean reuseEV = !(cache.getOffHeapStore() != null 
        && cache.isGFXDSystem());
    if(!reuseEV) {
      eventsToFree = new ArrayList<EntryEventImpl>();
    }

    final TXRegionState[] finalRegions = this.finalizeRegions;
    boolean hasRVVLocks = false;
    boolean hasSnapshotLocks = false;
    Map<String, TObjectLongHashMapDSFID> publishEvents = getProxy()
        .getToBePublishedEvents();
    if (publishEvents != null && !publishEvents.isEmpty()) {
      final SetKeyRegionContext setContext = new SetKeyRegionContext();
      for (TXRegionState txrs : finalRegions) {
        final LocalRegion dataRegion = txrs.region;
        final RegionVersionVector<?> rvv = dataRegion.getVersionVector();
        if (rvv != null) {
          rvv.lockForCacheModification(dataRegion);
          hasRVVLocks = true;
        }
        // initialize tail keys if required
        if (txrs.tailKeysForParallelWAN == null
            && dataRegion.isUsedForPartitionedRegionBucket()) {
          final TObjectLongHashMapDSFID keysToTailKeyMap = publishEvents
              .get(dataRegion.getFullPath());
          txrs.tailKeysForParallelWAN = keysToTailKeyMap;
          if (keysToTailKeyMap != null) {
            setContext.pr = ((BucketRegion)dataRegion).getPartitionedRegion();
            keysToTailKeyMap.forEachKey(setContext);
          }
        }
      }
    }
    else {
      for (TXRegionState txr : finalRegions) {
        final LocalRegion dataRegion = txr.region;
        final RegionVersionVector<?> rvv = dataRegion.getVersionVector();
        if (rvv != null) {
          rvv.lockForCacheModification(dataRegion);
          hasRVVLocks = true;
        }
      }
    }

    boolean firstTime = true;

    try {
      while (currentEntry != head) {
        cbEvent = commitEntryPhase2(currentEntry, cbEvent, eventsToFree,
            firstTime, reuseEV, logger, logFine);
        if (cbEvent == null) {
          break;
        }
        firstTime = false;
        currentEntry = currentEntry.next;
      } // while
    } finally {
      if (hasRVVLocks) {
        for (TXRegionState txr : finalRegions) {
          final LocalRegion dataRegion = txr.region;
          final RegionVersionVector<?> rvv = dataRegion.getVersionVector();
          if (rvv != null) {
            rvv.releaseCacheModificationLock(dataRegion);
          }
        }
      }

      publishRecordedVersions();
      if (reuseEV) {
        cbEvent.release();
      }
      else {
        eventsToFree.add(cbEvent);
      }
    }
    return eventsToFree;
  }

  private void publishRecordedVersions() {
    final TXRegionState[] finalRegions = this.finalizeRegions;
    GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
    final LogWriterI18n logger = getTxMgr().getLogger();
    // No need to check for snapshot if we want to enable it for RC.
    if (cache.snapshotEnabled()) {
      if (isSnapshot() || cache.snapshotEnabledForTest()) {
        // first take a lock at cache level so that we don't go into deadlock or sort array before
        // This is for tx RC, for snapshot just record all the versions from the queue
        //TODO: this is performance issue: Need to make the lock granular at region level.
        // also write a different recordVersion which will record without making clone

        cache.acquireWriteLockOnSnapshotRvv();
        try {
          for (VersionInformation vi : queue) {
            if (TXStateProxy.LOG_FINE) {
              logger.info(LocalizedStrings.DEBUG, "Recording version " + vi + " from snapshot to " +
                  "region.");
            }
            ((LocalRegion)vi.region).getVersionVector().
                recordVersionForSnapshot((VersionSource)vi.member, vi.version, null);
          }
        } finally {
          cache.releaseWriteLockOnSnapshotRvv();
        }
      } else {
        // doing it for tx and non tx case.
        // tx may not record version in snapshot so non tx reads while taking
        // snapshot may miss it. in case of commit just copy the rvv to snapshot so that
        // any future non tx read will get all the entries
        cache.acquireWriteLockOnSnapshotRvv();
        try {
          for (TXRegionState txr : finalRegions) {
            final LocalRegion dataRegion = txr.region;
            final RegionVersionVector<?> rvv = dataRegion.getVersionVector();
            if (rvv != null) {
              rvv.reInitializeSnapshotRvv();
            }
          }
        } finally {
          cache.releaseWriteLockOnSnapshotRvv();
        }
      }
    }
  }

  final void initBaseEventOffsetsForCommit() {
    this.baseMembershipId = EventID.getMembershipId(this.txManager.getDM()
        .getSystem());
    EventID.ThreadAndSequenceIDWrapper wrapper = EventID.getWrapper();
    this.baseThreadId = wrapper.threadID;
    this.baseSequenceId = wrapper.sequenceID;
    this.baseSequenceIdGenerator = wrapper;
  }

  /**
   * Calculate and return the event offset based on the sequence id on TXState.
   * 
   * @since 5.7
   */
  final int generateEventOffset() {
    return (int)(this.baseSequenceIdGenerator.getAndIncrementSequenceID()
        - this.baseSequenceId);
  }

  final EntryEventImpl commitEntryPhase2(final TXEntryState entry,
      EntryEventImpl cbEvent, List<EntryEventImpl> eventsToFree,
      final boolean firstTime, final boolean reuseEV,
      final LogWriterI18n logger, final boolean logFine) {
    if (!firstTime) {
      if (reuseEV) {
        cbEvent.reset(true);
      }
      else {
        eventsToFree.add(cbEvent);
        cbEvent = this.proxy.newEntryEventImpl();
      }
    }
    try {
      final TXRegionState txrs = entry.txRegionState;
      final LocalRegion dataRegion = txrs.region;
      final TObjectLongHashMapDSFID tailKeys = txrs.tailKeysForParallelWAN;
      if (logFine) {
        final Object key = entry.regionKey;
        if (TXStateProxy.LOG_FINEST) {
          logger.info(LocalizedStrings.DEBUG, "Applying on region "
              + dataRegion.getFullPath() + ", key="
              + key + ", value=" + ArrayUtils
              .objectStringNonRecursive(entry.pendingValue)
              + ", delta=" + entry.pendingDelta + ", op="
              + entry.op + ", callbackArg=" + entry
                .getCallbackArgument() + ", tailKey="
              + (tailKeys != null ? tailKeys.get(key) : -1L)
              + " for " + txId.shortToString());
        }
        else {
          logger.info(LocalizedStrings.DEBUG, "Applying on region "
              + dataRegion.getFullPath() + ", key="
              + key + ", op=" + entry.op
              + ", callbackArg=" + entry.getCallbackArgument()
              + ", entry=" + ArrayUtils.objectRefString(entry)
              + ", tailKey=" + (tailKeys != null ? tailKeys.get(key) : -1L)
              + ", for " + this.txId.shortToString());
        }
      }
      if (tailKeys != null) {
        long tailKey = tailKeys.get(entry.regionKey);
        cbEvent.setTailKey(tailKey);
      }
      entry.applyChanges(this, cbEvent, dataRegion);
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
      // passed. So do nothing, but there is no use of continuing the apply.
      return null;
    } catch (RuntimeException re) {
      // log any other error and move to the next entry
      logger.severe(LocalizedStrings.BaseCommand_SEVERE_CACHE_EXCEPTION_0,
          re, re);
    }
    return cbEvent;
  }

  public TXEvent getEvent() {
    return new TXEvent(this, getTransactionId(), getCache());
  }

  @Override
  public final long getCommitTime() {
    return this.proxy.getCommitTime();
  }

  protected void cleanup(final boolean commit,
      final TransactionObserver observer) {
    this.state = commit ? State.CLOSED_COMMIT : State.CLOSED_ROLLBACK;

    final LockingPolicy lockPolicy = getLockingPolicy();
    
    final LockMode writeMode = commit ? LockMode.EX : lockPolicy
        .getWriteLockMode();

    try {
      if (observer != null) {
        observer.afterApplyChanges(this.proxy);
      }

      clearPendingOps();
      if (!commit) {
        // release all pending read locks, if any
        pendingReadLocksCleanup(lockPolicy, null, null);
      }

      writeRegions.keySet().stream().filter(region ->
          region instanceof BucketRegion
      ).forEach(region ->
          ((BucketRegion)region).releaseSnapshotGIIReadLock()
      );

    } finally {
      if (this.txLocked.compareAndSet(true, false)) {
        unlockTXState();
      }
      cleanupTXRS(this.finalizeRegions, lockPolicy, writeMode, commit, true,
          observer);
    }
  }

  final void cleanupTXRS(final TXRegionState[] regionStates,
      final LockingPolicy lockPolicy, final LockMode writeMode,
      final boolean commit, final boolean releaseTXRSGIILocks,
      final TransactionObserver observer) throws TransactionException {

    TransactionException cleanEx = null;
    for (TXRegionState txrs : regionStates) {
      /*
       * Need to unlock the primary lock for rebalancing so that rebalancing
       * can resume.
       *
      if (gotBucketLocks) {
        if (r.isUsedForPartitionedRegionBucket()) {
          try {
            ((BucketRegion)r).doUnlockForPrimary();
          } catch (RegionDestroyedException rde) {
            // ignore
            r.getCache().getLogger().fine(
                "RegionDestroyedException while unlocking bucket region "
                + r.getFullPath(), rde);
          } catch (Exception rde) {
            // ignore
            r.getCache().getLogger().fine("Exception while unlocking bucket "
                + "region " + r.getFullPath() + " this is probably because "
                + "the bucket was destroyed and never locked initially.", rde);
          }
        }
      }
      */

      txrs.lock();
      boolean isLocked = true;
      try {
        cleanEx = txrs.cleanup(lockPolicy, writeMode, commit, false, cleanEx);
        if (!commit) {
          Object[] entries = txrs.getAndClearSnapshotRegionEntries();
          // release lock after this point because actual rollback will
          // also acquire RegionEntry lock which can cause a deadlock
          txrs.unlock();
          isLocked = false;
          if (entries != null) {
            List<RegionEntry> uncommittedEntryReference = Arrays.asList(
                (RegionEntry[])entries[0]);
            List<Object> committedEntryReference = Arrays.asList(
                (Object[])entries[1]);
            rollBackUncommittedEntries(uncommittedEntryReference,
                committedEntryReference, txrs.region);
          }
        }
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        cleanEx = processCleanupException(t, cleanEx);
      } finally {
        if (isLocked) txrs.unlock();
        if (releaseTXRSGIILocks) {
          txrs.unlockPendingGII();
        }
        txrs.applyChangesEnd(txrs.region, commit);
      }
    }
    //gotBucketLocks = false;

    if (observer != null) {
      observer.afterReleaseLocalLocks(this.proxy);
    }
    // Now that all the locks have been released by cleanup
    // see if any pending expires need to be processed.
    // We need to do this in two phases to prevent deadlock
    for (TXRegionState txrs : regionStates) {
      txrs.processPendingExpires();
    }
    if (cleanEx != null) {
      throw cleanEx;
    }
  }

  //rollback can be done locally for snapshot just as commit is done
  private void rollBackUncommittedEntries(List<RegionEntry> unCommittedEntryReference,
      List<Object> committedEntryReference, LocalRegion region)
      throws RegionClearedException {
    // we need to take entry lock on primary otherwise the following can happen
    // on primary : we replace uncommitted entry with committed entry
    // on primary new write comes, which goes to secondary
    // on secondary : we replace the new entry with old committed entry
    // to avoid this we can compare the version or keep the uncommitted RE reference too in
    // the txStateand don't conflict on secondary.

    if (region.isDestroyed()) return;
    Iterator<RegionEntry> itr1 = unCommittedEntryReference.iterator();
    Iterator<Object> itr2 = committedEntryReference.iterator();

    if (TXStateProxy.LOG_FINEST) {
      GemFireCacheImpl.getInstance().getLogger().info("Rolling back the changes.. "
          + unCommittedEntryReference.size() + " " + committedEntryReference.size());
    }
    RegionEntry uncommitted;
    RegionEntry committed;
    VersionStamp originalStamp, stamp;
    VersionTag originalStampAsTag;
    EntryEventImpl event = EntryEventImpl.createVersionTagHolder();
    while (itr1.hasNext() && itr2.hasNext()) {
      uncommitted = itr1.next();
      Object entr = itr2.next();
      if (!(entr instanceof Token)) {
        committed = (RegionEntry)entr;
      }
      else {
        committed = null;
      }
      synchronized (uncommitted) {
        try {
        if (committed != null) {
          originalStamp = committed.getVersionStamp();
          stamp = uncommitted.getVersionStamp();
          if (stamp.getEntryVersion() > originalStamp.getEntryVersion() + 1) {
            // some modification has already happened,
            // if this is secondary and the change must have come through primary.
            // TODO: TEST
            // actually this should never happen!
            continue;
          }

          originalStampAsTag = null;
          if (originalStamp != null) {
            originalStampAsTag = originalStamp.asVersionTag();
          }
          if (stamp != null && originalStampAsTag != null) {
            stamp.setVersions(originalStampAsTag);
            stamp.setMemberID(originalStampAsTag.getMemberID());
          }
          event.setVersionTag(originalStampAsTag);
          event.setRegion(region);
          // we need to do this under the region entry lock
          // This has to handle index changes too.
          // set originRemote so that version is not generated but used from event
          //Suranjan TODO: This case can lead to two copies of same row in the index.
          // as one will be pointing two RE in RegionMap
          // and other will be pointing to oldRe in oldReMap.
          //How to make it atomic? Not supporting for rowtable.
          event.setOriginRemote(true);
          event.setNewValue(committed._getValue());
          if (uncommitted.isTombstone()) {
            event.setOperation(Operation.CREATE);
            event.putNewEntry(region, uncommitted);
          } else {
            event.setOperation(Operation.UPDATE);
            event.setEntryLastModified(uncommitted.getLastModified());
            event.setPutDML(true);
            final int oldSize = region.calculateRegionEntryValueSize(uncommitted);
            event.putExistingEntry(region, uncommitted, oldSize);
            //uncommitted.setValueWithTombstoneCheck(committed._getValue(), event);
          }
        } else {
          // we need to just delete the entry in regionMap and set the version back
          originalStampAsTag = VersionTag.create(uncommitted.getVersionStamp().
              asVersionTag().getMemberID());
          event.setVersionTag(originalStampAsTag);
          event.setRegion(region);
          event.setOriginRemote(true);
          event.setOperation(Operation.DESTROY);
          uncommitted.destroy(region, event, false, true, null, false, false);
        }
        } catch (RegionDestroyedException | PrimaryBucketException |
            BucketMovedException e) {
          // ignore these and move to next entries
          region.getLogWriterI18n().info(LocalizedStrings.ONE_ARG,
              "Skipping rollback for " + uncommitted +
                  " due to region/bucket destroy");
        }
      }

      // also to make sure that it happens only if the version of uncommited hasn't changed.
      // so we will have to store the version of uncommitted too?
      // We can get it from version that we store in TXState. We need to map RE with the version
      // What if multiple updates on same RE.
      // GemFireCacheImpl.getInstance().getLogger().info("SKKS rolling back the changes.. ");
    }
    // we need to take RVV lock and record the recorded version in the snapshot so that
    // there are no unnecessary exceptions recorded due to rollback
    publishRecordedVersions();
  }

  public void cleanupCachedLocalState(boolean hasListeners) {
    // clear the ordered list if there are no post processing listeners
    if (!hasListeners) {
      this.head.next = this.head.previous = this.head;
    }
    // clear the regions map to help GC
    this.regions.clear();
  }

  final void cleanupEvents() {
    if (this.state.isClosed()) {
      this.head.next = this.head.previous = this.head;
    }
  }

  static final TransactionException processCleanupException(
      final Throwable t, TransactionException te) {
    // Whenever you catch Error or Throwable, you must also
    // check for fatal JVM error (see above). However, there is
    // _still_ a possibility that you are dealing with a cascading
    // error condition, so you also need to check to see if the JVM
    // is still usable:
    SystemFailure.checkFailure();
    // check for node failure
    GemFireCacheImpl.getExisting().getCancelCriterion()
        .checkCancelInProgress(t);
    // log the exception at fine level if there is no reply to the message
    if (te == null) {
      te = new IllegalTransactionStateException(
          "Unexpected exception during cleanup", t);
    }
    else {
      Throwable cause = te;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      cause.initCause(t);
    }
    return te;
  }

/**
   * Revert changes to a {@link TXEntryState} for a failed operation on some
   * nodes e.g. due to {@link ConflictException}, {@link CacheWriterException}.
   */
  final void revertFailedOp(final LocalRegion dataRegion, final Object key,
      final byte originalOp, final byte destroy, final boolean bulkOp,
      final Object originalValue, final Delta originalDelta) {
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = dataRegion.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "revertFailedOp on region "
            + dataRegion.getFullPath() + " key(" + key + "), op=" + originalOp
            + ", destroy=" + destroy + ", bulkOp=" + bulkOp
            + ", originalValue=" + originalValue + ", originalDelta="
            + originalDelta + " for " + this.txId.shortToString());
      }
      final LockingPolicy lockPolicy = getLockingPolicy();
      txr.lock();
      try {
        final TXEntryState tx = (TXEntryState)txr.readEntry(key);
        final LocalRegion region;
        if (dataRegion.isUsedForPartitionedRegionBucket()) {
          region = dataRegion.getPartitionedRegion();
        }
        else {
          region = dataRegion;
        }
        // if there is an originalValue then just change the value of existing
        // entry
        if (originalValue != null) {
          tx.revert(originalOp, destroy, bulkOp, originalValue, originalDelta);
        }
        else {
          // no original value so cleanup completely
          tx.cleanup(this, region, lockPolicy, lockPolicy.getWriteLockMode(),
              true, true, null);
        }
      } finally {
        txr.unlock();
      }
    }
  }

  final List<EntryEventImpl> getEvents() {
    final ArrayList<EntryEventImpl> events =
      new ArrayList<EntryEventImpl>();
    final boolean isCohort = !isCoordinator();
    final InternalDistributedMember myId = this.proxy.self;
    final TXEntryState head = this.head;
    TXEntryState currentEntry = head.next;
    while (currentEntry != head) {
      if (currentEntry.isOpAnyEvent()) {
        events.add(currentEntry.getEvent(this, isCohort, myId));
      }
      currentEntry = currentEntry.next;
    }
    if (!events.isEmpty()) {
      return events;
    }
    return Collections.emptyList();
  }
  
  void invokeTransactionWriter() throws TransactionException {
    if (this.firedWriter) {
      return;
    }
    /*
     * If there is a TransactionWriter plugged in,
     * we need to to give it an opportunity to 
     * abort the transaction.
     */
    final TransactionWriter writer = this.txManager.getWriter();
    if (writer != null) {
      TXEvent e = null;
      try {
        // need to mark this so we don't fire again in commit
        this.firedWriter = true;
        e = getEvent();
        writer.beforeCommit(e);
      } catch (TransactionWriterException twe) {
        throw new TransactionException(twe);
      } finally {
        if (e != null) {
          e.release();
        }
      }
    }
  }

  public final GemFireCacheImpl getCache() {
    return this.txManager.getCache();
  }

  public final InternalDistributedMember getCoordinator() {
    return this.proxy.getCoordinator();
  }

  public final boolean isCoordinator() {
    return this.proxy.isCoordinator();
  }

  /**
   * ONLY FOR TESTS.
   */
  public Collection<LocalRegion> getRegions() {
    final ArrayList<LocalRegion> lrs = new ArrayList<LocalRegion>(
        this.regions.size());
    for (TXRegionState txrs : this.regions) {
      lrs.add(txrs.region);
    }
    return lrs;
  }

  /**
   * A snapshot of the current set of TXRegionStates in this TX. Once
   * commit/rollback has been issued it will return a stable set of states.
   */
  public final TXRegionState[] getTXRegionStatesSnap() {
    final TXRegionState[] txrs = this.finalizeRegions;
    if (txrs != ZERO_REGIONS) {
      return txrs;
    }
    else {
      return this.regions.toArray(ZERO_REGIONS);
    }
  }

  /**
   * A snapshot of the current set of TXRegionStates in this TX which skips
   * taking any locks. This may not return a stable set of states, can throw
   * exceptions, and no product code should use it except for monitoring or
   * other such purposes that wants to avoids locks.
   */
  public final TXRegionState[] getTXRegionStatesSnapUnsafe() {
    int tries = 5;
    while (tries-- > 0) {
      final TXRegionState[] txrs = this.finalizeRegions;
      if (txrs != ZERO_REGIONS) {
        return txrs;
      }
      else {
        try {
          ArrayList<TXRegionState> snap = new ArrayList<TXRegionState>();
          this.regions.toCollectionUnsafe(snap);
          return snap.toArray(new TXRegionState[snap.size()]);
        } catch (RuntimeException re) {
          // retry some number of times
        }
      }
    }
    // failed to get a snap
    return ZERO_REGIONS;
  }

  /** Lookup the TXRegionState for a given a region. */
  public final TXRegionState getTXRegionState(Object region) {
    return this.regions.get(region);
  }

  /**
   * Lookup the TXRegionState for a given a region without any locking. This
   * should never be used except by monitoring or such code that needs to avoid
   * locks.
   */
  public final TXRegionState getTXRegionStateUnsafe(Object region) {
    return this.regions.getUnsafe(region);
  }

  public final Object getReadLocksForScanContext(final Object context) {
    return this.proxy.getTSSPendingReadLocks(context);
  }

  public final void addReadLockForScan(final ExclusiveSharedLockObject lockObj,
      final LockMode readLockMode, final LocalRegion dataRegion,
      final Object context) {
    assert lockObj != null: "unexpected null lock object";
    int index;
    final ArrayListAppend[] tssLocks = (ArrayListAppend[])context;
    if (tssLocks[0] != null) {
      if ((index = tssLocks[0].addObj(lockObj)) >= 0) {
        tssLocks[1].set(index, readLockMode);
        tssLocks[2].set(index, dataRegion);
      }
      else {
        tssLocks[1].append(readLockMode);
        tssLocks[2].append(dataRegion);
      }
    }
    else {
      tssLocks[0] = new ArrayListAppend(PENDING_LOCKS_DEFAULT_SIZE);
      tssLocks[1] = new ArrayListAppend(PENDING_LOCKS_DEFAULT_SIZE);
      tssLocks[2] = new ArrayListAppend(PENDING_LOCKS_DEFAULT_SIZE);
      tssLocks[0].append(lockObj);
      tssLocks[1].append(readLockMode);
      tssLocks[2].append(dataRegion);
    }
  }

  public final LocalRegion removeReadLockForScan(
      final ExclusiveSharedLockObject lockObj, final Object context) {
    assert lockObj != null: "unexpected null lock object";
    int index;
    final ArrayListAppend[] tssLocks = (ArrayListAppend[])context;
    if (tssLocks[0] != null) {
      if ((index = tssLocks[0].removeObj(lockObj)) >= 0) {
        tssLocks[1].set(index, null);
        return (LocalRegion)tssLocks[2].set(index, null);
      }
    }
    return null;
  }

  /**
   * Cleanup any pending read locks before EX lock upgrade in commit since
   * potentially the same entry could have been acquired for EX_SH lock in the
   * same transaction. Also cleanup when replying to a message to remove any
   * remaining dangling lock in current thread execution.
   */
  public final void pendingReadLocksCleanup(final LockingPolicy lockPolicy,
      final Object lockContext, final Object context) {
    final ArrayListAppend[] tssLocks = lockContext != null
        ? (ArrayListAppend[])lockContext
        : this.proxy.getTSSPendingReadLocks(context);
    if (tssLocks != null && tssLocks[0] != null) {
      final int size = tssLocks[0].size();
      if (size > 0) {
        final TXId owner = getTransactionId();
        for (int index = 0; index < size; index++) {
          final Object lockObj = tssLocks[0].get(index);
          if (lockObj != null) {
            if (TXStateProxy.LOG_FINE) {
              getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
                  "releasing pending read lock on " + lockObj + " in "
                      + this.txId.shortToString());
            }
            lockPolicy.releaseLock((ExclusiveSharedLockObject)lockObj,
                (LockMode)tssLocks[1].get(index), owner, false,
                tssLocks[2].get(index));
          }
        }
        tssLocks[0] = null;
        tssLocks[1] = null;
        tssLocks[2] = null;
      }
    }
  }

  /**
   * Move any thread-local pending read locks to TXState and cleanup the
   * thread-local list.
   */
  public final void pendingReadLocksToTXState(final LockingPolicy lockPolicy,
      final Object lockContext, final Object context) {
    final ArrayListAppend[] tssLocks = lockContext != null
        ? (ArrayListAppend[])lockContext
        : this.proxy.getTSSPendingReadLocks(context);
    if (tssLocks != null && tssLocks[0] != null) {
      final int size = tssLocks[0].size();
      if (size > 0) {
        final boolean batchingEnabled = this.proxy.batchingEnabled();
        for (int index = 0; index < size; index++) {
          final Object lockObj = tssLocks[0].get(index);
          if (lockObj != null) {
            if (TXStateProxy.LOG_FINE) {
              getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
                  "moving pending read lock on " + (TXStateProxy.LOG_FINEST
                      ? lockObj : ArrayUtils.objectRefString(lockObj))
                      + " to " + this.txId.shortToString());
            }
            final RegionEntry entry = (RegionEntry)lockObj;
            addReadLock(entry, entry.getKey(),
                (LocalRegion)tssLocks[2].get(index), null, batchingEnabled,
                Boolean.TRUE);
          }
        }
        tssLocks[0] = null;
        tssLocks[1] = null;
        tssLocks[2] = null;
      }
    }
  }

  public boolean txPutEntry(final EntryEventImpl event, final boolean ifNew,
      final boolean requireOldValue,
      final boolean checkResources, final Object expectedOldValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  private final boolean putEntry(EntryEventImpl event, boolean ifNew,
      boolean ifOld, boolean checkResources, Object expectedOldValue,
      boolean requireOldValue, final boolean cacheWrite,
      final boolean markPending, final long lastModified,
      final boolean overwriteDestroyed) {

    final LocalRegion region = event.getRegion();

    if (isSnapshot()) {
      event.setTXState(this);
      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = getTxMgr().getLogger();
        logger.info(LocalizedStrings.DEBUG, "putEntry Region " + region.getFullPath()
            + ", event: " + (TXStateProxy.LOG_FINE ? event.toString()
            : event.shortToString()) + " for " + this.txId.toString()
            +", sending it back to region for snapshot isolation.");
      }
      return region.getSharedDataView().putEntry(event, ifNew, ifOld, expectedOldValue, requireOldValue,
          cacheWrite,
          lastModified, overwriteDestroyed);
    }

    if (checkResources) {
      if (!MemoryThresholds.isLowMemoryExceptionDisabled()) {
        region.checkIfAboveThreshold(event);
      }
    }
    // if requireOldValue then oldValue gets set in event
    // (even if ifNew and entry exists)
    // !!!:ezoerner:20080813 need to handle ifOld for transactional on
    // PRs when PRs become transactional
    final Object key = event.getKey();
    final Operation op = event.getOperation();

    // set originalRemote flag correctly using coordinator information
    // store the old originRemote flag first
    final boolean isRemote = event.isOriginRemote();
    final boolean isCoordinator = isCoordinator();
    event.setOriginRemote(!isCoordinator);

    final DataPolicy dp;
    final LocalRegion dataRegion = region.getDataRegionForWrite(event, op);

    // if coordinator, then wait for region to initialize
    if (isCoordinator) {
      dataRegion.waitForData();
    }

    final TXRegionState txr = writeRegion(dataRegion);
    final LockingPolicy lockPolicy = getLockingPolicy();
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = region.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG,
          "putEntry on region " + region.getFullPath() + " dataRegion "
              + dataRegion.getFullPath() + " initialized=" + dataRegion
                .isInitialized() + " for " + this.txId.shortToString()
              + ": " + (TXStateProxy.LOG_FINEST ? event.toString()
                  : event.shortToString()) + ", ifNew="
              + ifNew + ", ifOld=" + ifOld + ", requireOldValue="
              + requireOldValue);
    }

    // check for uninitialized region
    if (txr == null) {
      // assume success (like it would have received via TXBatch) since actual
      // result will be returned by a node which is primary or GII source etc
      return true;
    }
    else if (!txr.isInitializedAndLockGII()) {
      // if not initialized then add to pending ops list and return
      TXEntryState entry = txr.createEntry(key, null, null, false);
      entry.op = entry.advisePutOp(ifNew ? TXEntryState.OP_CREATE
          : TXEntryState.OP_PUT, op);
      if (event.hasDeltaPut()) {
        entry.pendingDelta = event.getDeltaNewValue();
      }
      else {
        entry.pendingValue = OffHeapHelper.copyIfNeeded(event.getRawNewValue());
      }
      entry.setCallbackArgument(event.getCallbackArgument());
      txr.addPendingTXOpAndUnlockGII(entry,
          AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH);
      return true;
    }

    txr.lock();
    boolean txEntryCreated = false;
    boolean success = false;
    boolean lockAcquired = false;
    boolean lockedForRead = false;
    boolean releaseReadOnlyLock = false;
    RegionEntry entry = null;
    TXEntryState tx = null;
    Object valueToReleaseOnFailure = null;
    try {
      Object val = null;
      final Object txEntry = txr.readEntry(key);
      if (txEntry == null
          || (lockedForRead = (txEntry instanceof AbstractRegionEntry))) {
        final int flags = (lockedForRead ? AbstractRegionEntry.FOR_UPDATE : 0);
        if (event.hasDeltaPut() || ifOld) {
          // Asif: If it is a gfxd system & a delta arrives it implies it is
          // update which means old value is must so, in this case there no
          // point in creating TxEntry or RegionEntry.
          entry = region.basicGetEntryForLock(dataRegion, key);
          if (entry != null) {
            lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(), flags,
                this.txId, dataRegion, null);
            lockAcquired = true;
           
            val = dataRegion.getREValueForTXRead(entry);
            valueToReleaseOnFailure = val;
           
            if (event.hasDeltaPut() && (val == null || val instanceof Token)) {
              throw new EntryNotFoundException("No previously created value "
                  + "to be updated for event: " + event);
            }
          }
          else {
            if (event.hasDeltaPut()) {
              throw new EntryNotFoundException(
                  "No previously created entry to be updated for event: "
                      + event);
            }
            // this is the case for NORMAL/PRELOADED regions when no
            // existing entry is found
            return false;
          }
        }
        else if ((dp = region.getDataPolicy()).withStorage()) {
          entry = region.lockRegionEntryForWrite(dataRegion, key, lockPolicy,
              lockPolicy.getWriteLockMode(), this.txId, flags);
          lockAcquired = true;
          if(txEntry instanceof TXEntryState) {
            val = ((TXEntryState)txEntry).getValueInTXOrRegion();
          }else {
           val = dataRegion.getREValueForTXRead(entry);
           valueToReleaseOnFailure = val;
          }
          if (ifNew) {
            if (val != null) {
              // Since "ifNew" is true then let caller know entry exists in cmt
              // state; also avoid creating a TXEntryState and locking of entry
              event.setOldValue(val);
              return false;
            }
            else if (isRemote && !dp.withPartitioning()
                && !dp.withReplication()) {
              // for NORMAL/PRELOADED regions no need to do anything more
              return false;
            }
          }
        }
        if (expectedOldValue != null) {
          checkExpectedOldValue(val, expectedOldValue, txr, region);
        }
        if( !(txEntry instanceof TXEntryState)) {
          tx = txr.createReadEntry(key, entry, val, markPending);
          txEntryCreated = true;
          valueToReleaseOnFailure = null;
        }else {
          tx = (TXEntryState)txEntry;
        }
      }
      else {
        tx = (TXEntryState)txEntry;
        val = tx.getValueInTXOrRegion();
        if (expectedOldValue != null) {
          checkExpectedOldValue(val, expectedOldValue, txr, region);
        }
        // throw exception if delta put on TX entry previously destroyed in TX
        if (event.hasDeltaPut() && tx.isOpDestroy()) {
          throw new EntryNotFoundException(
              "No previously created entry to be updated for event: " + event);
        }
        if (tx.isOpReadOnly()) {
          // We cannot upgrade the lock from READ_ONLY to WRITE mode atomically
          // in all cases because others may also have the lock. However, if
          // there is only on READ_ONLY lock then this TX itself is the owner of
          // that and so allow the lock to be acquired in that case to have
          // atomic upgrade from READ_ONLY to WRITE mode.
          entry = tx.getUnderlyingRegionEntry();
          lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(),
              AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH, this.txId,
              dataRegion, null);
          lockAcquired = true;
          releaseReadOnlyLock = true;
        }
      }
      if (tx.existsLocally()) {
        // GemFireXD requires oldValue for index maintenance etc.
        if (requireOldValue || this.isGFXD) {
          event.setTXOldValue(val);
        }
        if (ifNew) {
          // Since "ifNew" is true then let caller know entry exists
          // in tx state or committed state
          // if entry was received from TX GII, then indicate successful insert
          success = (tx.checkAndClearGIIEntry()
              && tx.op >= TXEntryState.OP_CREATE);
          return success;
        }
      }
      else {
        ifNew = true;
      }
      // distribute index operations dependent on the cacheWrite flag
      if (cacheWrite) {
        event.setDistributeIndexOps();
      }
      else {
        event.setSkipDistributionOps();
      }
      // acquire the lock on pending list first, else some other thread
      // can flush the list under our hood while we are still applying
      // the operation
      byte putOp = tx.basicPutPart1(event, ifNew, cacheWrite);
      if (markPending) {
        this.headLock.lock();
        try {
          tx.basicPutPart2(event, putOp);
          tx.updateForCommitNoLock(this, true);
        } finally {
          this.headLock.unlock();
        }
      }
      else {
        tx.basicPutPart2(event, putOp);
        tx.updateForCommit(this);
      }
      tx.basicPutPart3(dataRegion, event, ifNew);
      success = true;
      // release the read lock since write lock has been acquired
      if (lockedForRead && lockAcquired) {
        lockPolicy.releaseLock(entry, lockPolicy.getReadLockMode(), this.txId,
            false, dataRegion);
        if (markPending) {
          removePendingOpWithLock(entry);
        }
      }
      // release the READ_ONLY lock if required
      // this is now already released atomically by the acquireLock call
      /*
      else if (releaseReadOnlyLock) {
        releaseReadOnlyLock = false;
        lockPolicy.releaseLock(entry, lockPolicy.getReadOnlyLockMode(),
            this.txId, false, dataRegion);
      }
      */
      return true;
    } catch (EntryNotFoundException e) {
      if (this.isGFXD) {
        // Asif:throw entry not found exception as gemfirexd is relying on it
        // for transactional update on non existent row.
        throw e;
      }
    } finally {
      if (!success) {
        try {
          cleanupOnUnsuccessfulWrite(tx, entry, key, txr, dataRegion,
              lockPolicy, lockAcquired, txEntryCreated, lockedForRead,
              !releaseReadOnlyLock, Operation.UPDATE);
          OffHeapHelper.release(valueToReleaseOnFailure);
        } finally {
          txr.unlock();
        }
      }
      else {
        txr.unlock();
      }
    }
    return false;
  }

  @Override
  public final Object lockEntryForRead(final RegionEntry entry,
      final Object key, final LocalRegion dataRegion, final int iContext,
      final boolean allowTombstones, final ReadEntryUnderLock reader) {
    final LockingPolicy lockPolicy = getLockingPolicy();
    return lockEntryForRead(lockPolicy, entry, key, dataRegion, null, this.txId,
        this, iContext, false, allowTombstones, Boolean.TRUE, reader);
  }

  @Override
  public final Object lockEntry(final RegionEntry entry, final Object key,
      Object callbackArg, final LocalRegion region, LocalRegion dataRegion,
      boolean writeMode, boolean allowReadFromHDFS, final byte opType,
      final int falureFlags) throws EntryNotFoundException {

    final TXRegionState txr = writeRegionForRead(dataRegion);
    return lockEntry(entry, key, callbackArg, txr, region, dataRegion,
        writeMode, allowReadFromHDFS, opType, false, falureFlags, null);
  }

  final Object lockEntry(RegionEntry entry, Object key, Object callbackArg,
      final TXRegionState txr, final LocalRegion region,
      final LocalRegion dataRegion, boolean writeMode,
      boolean allowReadFromHDFS, final byte opType, final boolean markPending,
      final int failureFlags, final int[] resultFlags)
      throws EntryNotFoundException {

    int lockObtained = LOCK_ENTRY_NOT_FOUND;

    TXEntryState tx = null;
    if (txr != null) {
      final LockingPolicy lockPolicy = getLockingPolicy();
      final LockMode lockMode = writeMode ? lockPolicy.getWriteLockMode()
          : lockPolicy.getReadOnlyLockMode();

      if (TXStateProxy.LOG_FINE) {
        final LogWriterI18n logger = region.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "lockEntry for key=" + key
            + ", callbackArg=" + callbackArg + " on region "
            + region.getFullPath() + ", dataRegion " + dataRegion.getFullPath()
            + " writeMode=" + writeMode + " lockMode=" + lockMode + " opType="
            + opType + " for " + this.txId.shortToString());
      }

      // check for uninitialized region
      if (!dataRegion.isInitialized()) {
        // do not expect this to happen (all requisite callers should already
        // have added to pending list where required before getting the
        // RegionEntry)
        Assert.fail("TXState.lockEntry: unexpected uninitialized region "
            + dataRegion);
      }

      txr.lock();
      final Object txEntry = txr.readEntry(key);
      Object valToReleaseOnFailure = null;
 
      try {
        boolean lockedForRead = false;
        if (txEntry == null
            || (lockedForRead = (txEntry instanceof AbstractRegionEntry))) {
          try {
            entry = readEntry(entry, txEntry, key, region, dataRegion,
                allowReadFromHDFS);
            if (writeMode) {
              lockPolicy.acquireLock(entry, lockMode,
                  AbstractRegionEntry.FOR_UPDATE, this.txId, dataRegion, null);
            }
            else {
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              
              @Retained @Released final Object lockResult = lockPolicy
                  .lockForRead(entry, lockMode, this.txId, dataRegion, 0, null,
                      false, LocalRegion.READ_VALUE);
              
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
              try {
                if (lockResult != LockingPolicy.Locked) {
                  lockObtained = (!Token.isRemoved(lockResult)
                      ? LOCK_ZERO_DURATION : LOCK_ENTRY_NOT_FOUND);
                  if (resultFlags != null) {
                    resultFlags[0] = lockObtained;
                  }
                  return (lockObtained & failureFlags) == 0 ? entry : null;
                }
              } finally {
                OffHeapHelper.releaseWithNoTracking(lockResult);
              }
            }
            lockObtained = LOCK_ADDED;
            // check if entry has been removed after acquiring the lock
            if (entry.isDestroyedOrRemoved()) {
              // lock cleanup will be done by the finally block
              if (resultFlags != null) {
                resultFlags[0] = LOCK_ENTRY_NOT_FOUND;
              }
              return (LOCK_ENTRY_NOT_FOUND & failureFlags) == 0 ? entry : null;
            }
            if (key instanceof RegionEntry) {
              // need the separate key for storing in TXState
              key = ((RegionEntry)key).getKeyCopy();
            }
            final Object val = dataRegion.getREValueForTXRead(entry);
            valToReleaseOnFailure = val;
            tx = txr.createReadEntry(key, entry, val, markPending);
            valToReleaseOnFailure = null;
            // release the read lock since higher lock has been acquired
            if (lockedForRead) {
              lockPolicy.releaseLock(entry, lockPolicy.getReadLockMode(),
                  this.txId, false, dataRegion);
              if (markPending) {
                removePendingOpWithLock(entry);
              }
            }
            // put in batch list if required
            // acquire the lock on pending list first, else some other thread
            // can flush the list under our hood while we are still applying
            // the operation
            if (markPending) {
              markPending(tx, opType, callbackArg);
            }
            else {
              tx.setOpType(opType, callbackArg);
            }
            if (TXStateProxy.LOG_FINEST) {
              final LogWriterI18n logger = region.getLogWriterI18n();
              logger.info(LocalizedStrings.DEBUG, "tx created for read: " + tx
                  + " and op type set is: " + opType);
            }
          } finally {
            // if failed to create TXEntryState, then release the lock
            if (tx == null && lockObtained == LOCK_ADDED) {
              lockPolicy.releaseLock(entry, lockMode,
                  null /* no need of lockOwner here */, false, dataRegion);
              lockObtained = LOCK_ENTRY_NOT_FOUND;
            }
          }
        }
        else if ((tx = ((TXEntryState)txEntry)).isOpReadOnly()) {
          // We cannot upgrade the lock from READ_ONLY to WRITE mode atomically
          // in all cases because others may also have the lock. However, if
          // there is only on READ_ONLY lock then this TX itself is the owner of
          // that and so allow the lock to be acquired in that case to have
          // atomic upgrade from READ_ONLY to WRITE mode.
          if (writeMode) {
            if (entry == null) {
              entry = tx.getUnderlyingRegionEntry();
            }
            lockPolicy.acquireLock(entry, lockMode,
                AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH
                | AbstractRegionEntry.FOR_UPDATE, this.txId, dataRegion, null);
            lockObtained = LOCK_ADDED;
            // no need to check if entry has been removed after acquiring the
            // lock since READ_ONLY lock was already acquired that ensures that
            // no other TX can remove the entry

            // READ_ONLY lock has been already released by above acquireLock call
            //lockPolicy.releaseLock(entry, lockPolicy.getReadOnlyLockMode(),
            //    this.txId, false, dataRegion);

            // put in batch list if required
            // acquire the lock on pending list first, else some other thread
            // can flush the list under our hood while we are still applying
            // the operation
            if (markPending) {
              markPending(tx, opType, callbackArg);
            }
            else {
              tx.setOpType(opType, callbackArg);
            }
          }
          else {
            lockObtained = lockState(tx, opType, callbackArg, markPending);
          }
        }
        else {
          lockObtained = lockState(tx, opType, callbackArg, markPending);
        }
      } catch (EntryNotFoundException e) {
        if (this.isGFXD) {
          // Asif:throw entry not found exception as gemfirexd is relying on it
          // for transactional update on non existent row.
          throw e;
        }
      } finally {
        OffHeapHelper.release(valToReleaseOnFailure);
        txr.unlock();
      }
    }
    if (resultFlags != null) {
      resultFlags[0] = lockObtained;
    }
    return (lockObtained & failureFlags) == 0 ? tx : null;
  }

  private final int lockState(TXEntryState tx, byte opType, Object callbackArg,
      boolean markPending) {
    if (tx.existsLocally()) {
      if (tx.checkAndClearGIIEntry() && tx.op == opType) {
        // mark it as pending and return
        if (markPending) {
          markPending(tx, opType, callbackArg);
        }
        return LOCK_ADDED;
      }
      else {
        return LOCK_ALREADY_HELD_IN_TX;
      }
    }
    else {
      return LOCK_ALREADY_HELD_IN_TX_NO_VALUE;
    }
  }

  private final void markPending(TXEntryState tx, byte opType,
      Object callbackArg) {
    this.headLock.lock();
    try {
      tx.setOpType(opType, callbackArg);
      // mark for pending in the list so batch will be flushed at end of
      // message execution if required
      tx.updateForCommitNoLock(this, true);
    } finally {
      this.headLock.unlock();
    }
  }

  public final void lockTXState() {
    this.txLock.lock();
    if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
      final LogWriterI18n logger = getTxMgr().getLogger();
      logger.info(LocalizedStrings.DEBUG, "acquired lock on " + toString());
    }
  }

  final boolean lockTXRSAndTXState() {
    if (this.txLocked.compareAndSet(false, true)) {
      // take a snapshot of regions to be committed since this can change
      // due to GII (and we don't want to block the entire GII)
      if (this.finalizeRegions == ZERO_REGIONS) {
        this.finalizeRegions = this.regions.toArray(ZERO_REGIONS,
            txRegionCreator, this);
      }
      final TXRegionState[] finalRegions = this.finalizeRegions;
      // acquire the GII lock on all regions
      for (TXRegionState txrs : finalRegions) {
        txrs.lockPendingGII();
      }

      lockTXState();
      return true;
    }
    else {
      return false;
    }
  }

  final void unlockTXRSAndTXState() {
    if (this.txLocked.compareAndSet(true, false)) {
      unlockTXState();
      // release the GII lock on all regions
      final TXRegionState[] finalRegions = this.finalizeRegions;
      for (TXRegionState txrs : finalRegions) {
        txrs.unlockPendingGII();
      }
    }
  }

  final void unlockTXState() {
    this.txLock.unlock();
    if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
      final LogWriterI18n logger = getTxMgr().getLogger();
      logger.info(LocalizedStrings.DEBUG, "released lock on " + toString());
    }
  }

  private final RegionEntry readEntry(RegionEntry entry, final Object txEntry,
      final Object key, final LocalRegion localRegion,
      final LocalRegion dataRegion, boolean allowReadFromHDFS)
      throws EntryNotFoundException {
    if (entry != null) {
      return entry;
    }
    if (txEntry instanceof AbstractRegionEntry) {
      return (AbstractRegionEntry)txEntry;
    }
    entry = localRegion
        .basicGetEntryForLock(dataRegion, key, allowReadFromHDFS);
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = localRegion.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "readEntry: read entry="
          + (TXStateProxy.LOG_FINEST && entry != null ? entry.toString()
              : ArrayUtils.objectRefString(entry))
          + " for key=" + key + " from dataRegion=" + dataRegion.getFullPath()
          + " for " + this.txId.shortToString());
    }
    if (entry != null) {
      return entry;
    }
    throw new EntryNotFoundException(key.toString());
  }

  /*
  public final void unlockEntry(final RegionEntry entry, final Object key,
      final LocalRegion region, final LocalRegion dataRegion, boolean writeMode) {

    final TXRegionState txr = writeRegionForRead(dataRegion);
    final LockingPolicy lockPolicy = getLockingPolicy();
    final LockMode lockMode = writeMode ? lockPolicy.getWriteLockMode()
        : lockPolicy.getReadOnlyLockMode();
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = region.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG,
          "unlockEntry " + (TXStateProxy.LOG_FINEST && entry != null
              ? entry.toString() : ArrayUtils.objectRefString(entry))
              + " on region " + region.getFullPath() + " dataRegion "
              + dataRegion.getFullPath() + " for " + this.txId.shortToString());
    }
    txr.lock();
    try {
      final Object txEntry = txr.readEntry(key);
      if (txEntry instanceof TXEntryState) {
        ((TXEntryState)txEntry).cleanup(this, dataRegion, lockPolicy, lockMode,
            true, true);
      }
    } finally {
      txr.unlock();
    }
  }
  */

  // we can read entry here and return old entry if the read entry is omitted due to version
  /**
   * Lock the given RegionEntry for reading as per the provided
   * {@link LockingPolicy}.
   * 
   * @return the result of {@link ReadEntryUnderLock#readEntry} after lock
   *         acquisition
   */
  static final Object lockEntryForRead(final LockingPolicy lockPolicy,
      RegionEntry entry, final Object key, final LocalRegion dataRegion,
      TXRegionState txr, final TXId txId, final TXState txState, int iContext,
      final boolean markPending, final boolean allowTombstones,
      final Boolean checkForTXFinish, final ReadEntryUnderLock reader) {
    final LockMode mode = lockPolicy.getReadLockMode();
    if (lockPolicy == LockingPolicy.SNAPSHOT) {
      if (dataRegion.getVersionVector() != null) {
        if (!checkEntryInSnapshot(txState, dataRegion, entry)) {
          entry = (RegionEntry)getOldVersionedEntry(txState, dataRegion, key, entry);
        }
      }
    }

    final Object lockResult = lockPolicy.lockForRead(entry, mode, txId,
        dataRegion, iContext, null, allowTombstones, reader);
    if (lockResult != LockingPolicy.Locked) {
      // lock was immediately released
      return lockResult;
    }
    // adding the lock to the pending list
    // we need a local TXState; create a TXRegionState if required and add to that
    txState.addReadLock(entry, key, dataRegion, txr, markPending,
        checkForTXFinish);
    return reader.readEntry(entry, dataRegion, iContext, allowTombstones);
  }

  final RegionEntry basicGetEntryForLock(LocalRegion region,
      LocalRegion dataRegion, Object regionKey, String opName, byte op) {
    RegionEntry entry = region.basicGetEntryForLock(dataRegion, regionKey);
    // if entry is null then it can be due to new node join (#48707)
    // that should now be handled cleanly by TX ship support in GII
    if (entry != null) {
      return entry;
    }
    else {
      Assert.fail("unexpected null entry in " + opName + " with read op " + op
          + ": " + toString());
      // never reached
      return null;
    }
  }

  final boolean addReadLock(final RegionEntry entry, final Object key,
      final LocalRegion dataRegion, TXRegionState txr, boolean markPending,
      final Boolean checkForTXFinish) {
    // mark TXStateProxy as having read operations
    this.proxy.markHasReadOps();
    // adding the lock to the pending list
    // we need a local TXState; create a TXRegionState and add to that
    if (txr == null) {
      txr = writeRegionForRead(dataRegion, checkForTXFinish);
    }
    if (txr == null) {
      return false;
    }
    txr.lock();
    try {
      final UnifiedMap<Object, Object> entryMap = checkForTXFinish
          ? txr.getEntryMap() : txr.getInternalEntryMap();
      if (entryMap.putIfAbsent(key, entry) != null) {
        // entry exists and must be at least read locked, so release this lock
        this.lockPolicy.releaseLock(entry, this.lockPolicy.getReadLockMode(),
            this.txId, false, dataRegion);
        return false;
      }
    } finally {
      txr.unlock();
    }
    if (markPending) {
      this.headLock.lock();
      try {
        addPendingOp(entry, dataRegion);
      } finally {
        this.headLock.unlock();
      }
    }
    return true;
  }

  final int numPendingOps() {
    return this.numPendingOperations;
  }

  // volatile increment is done under a lock so supress findbugs warning
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="VO_VOLATILE_INCREMENT")
  final void addPendingOp(final Object op, final LocalRegion dataRegion) {
    assert this.headLock.isLocked();

    if (TXStateProxy.LOG_FINEST) {
      dataRegion.getLogWriterI18n().info(LocalizedStrings.DEBUG,
          "addPendingOp: adding op=" + op + " in dataRegion "
              + dataRegion.getFullPath());
    }

    this.pendingOps.add(op);
    this.pendingOpsRegions.add(dataRegion);
    ++this.numPendingOperations;
    // mark node as having cohorts
    this.proxy.hasCohorts = true;
  }

  final void removePendingOpWithLock(final Object op) {
    final NonReentrantLock headLock = this.headLock;
    headLock.lock();
    try {
      removePendingOp(op);
    } finally {
      headLock.unlock();
    }
  }

  // volatile increment is done under a lock so supress findbugs warning
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="VO_VOLATILE_INCREMENT")
  final void removePendingOp(final Object op) {
    assert this.headLock.isLocked();

    // usually expect to be at the end, so start search from end
    for (int index = this.pendingOps.size() - 1; index >= 0; index--) {
      if (op.equals(this.pendingOps.get(index))) {
        if (TXStateProxy.LOG_FINEST) {
          getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
              "removePendingOp: removing op=" + op + " in dataRegion "
                  + ((LocalRegion)this.pendingOpsRegions.get(index))
                      .getFullPath());
        }
        this.pendingOps.remove(index);
        this.pendingOpsRegions.remove(index);
        --this.numPendingOperations;
        break;
      }
    }
  }

  final void clearPendingOps() {
    this.pendingOps.clear();
    this.pendingOpsRegions.clear();
    this.numPendingOperations = 0;
  }

  /**
   * @see TXStateInterface#destroyExistingEntry(EntryEventImpl, boolean, Object)
   */
  public void destroyExistingEntry(final EntryEventImpl event,
      final boolean cacheWrite, Object expectedOldValue) {
    txDestroyExistingEntry(event, cacheWrite, false, expectedOldValue);
  }

  final boolean txDestroyExistingEntry(final EntryEventImpl event,
      final boolean cacheWrite, final boolean markPending,
      final Object expectedOldValue) {
    final Operation op = event.getOperation();
    final LocalRegion region = event.getRegion();

    if (isSnapshot()) {
      region.getSharedDataView().destroyExistingEntry(event, cacheWrite, expectedOldValue);
      return true;
    }

    final LocalRegion dataRegion = region.getDataRegionForWrite(event, op);
    // if coordinator, then wait for region to initialize
    if (isCoordinator()) {
      dataRegion.waitForData();
    }

    final TXRegionState txr = writeRegion(dataRegion);
    final LockingPolicy lockPolicy = getLockingPolicy();
    TXEntryState entry = null;

    // check for uninitialized region
    if (txr == null) {
      // assume success (like it would have received via TXBatch) since actual
      // result will be returned by a node which is primary or GII source etc
      return true;
    }
    else if (!txr.isInitializedAndLockGII()) {
      // if not initialized then add to pending ops list and return
      entry = txr.createEntry(event.getKey(), null, null, false);
      entry.op = entry.adviseDestroyOp(op);
      entry.setCallbackArgument(event.getCallbackArgument());
      txr.addPendingTXOpAndUnlockGII(entry,
          AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH);
      return true;
    }

    boolean txEntryCreated = false;
    boolean lockedForRead = false;
    boolean isReadOnly = false;
    boolean success = false;
    txr.lock();
    try {
      entry = txWriteExistingEntry(event, region, dataRegion, txr, markPending,
          expectedOldValue);
      if (entry == null) {
        return false;
      }
      else if (entry.checkAndClearGIIEntry()) {
        if (markPending) {
          markPending(entry, entry.op, event.getCallbackArgument());
        }
        return true;
      }
      txEntryCreated = entry.isOpNull();
      if ((lockedForRead = entry.isOpFlaggedForRead())) {
        entry.op = TXEntryState.OP_NULL;
        txEntryCreated = true;
      }
      else {
        isReadOnly = entry.isOpReadOnly();
      }
      // distribute index operations dependent on the cacheWrite flag
      if (cacheWrite) {
        event.setDistributeIndexOps();
      }
      else {
        event.setSkipDistributionOps();
      }
      // acquire the lock on pending list first, else some other thread
      // can flush the list under our hood while we are still applying
      // the operation
      entry.destroyPart1(event, cacheWrite);
      if (markPending) {
        this.headLock.lock();
        try {
          entry.destroyPart2(event);
          entry.updateForCommitNoLock(this, true);
        } finally {
          this.headLock.unlock();
        }
      }
      else {
        entry.destroyPart2(event);
        entry.updateForCommit(this);
      }
      entry.destroyPart3(dataRegion, event);
      success = true;
      // release the read lock since write lock has been acquired
      if (lockedForRead) {
        final RegionEntry re = entry.getUnderlyingRegionEntry();
        lockPolicy.releaseLock(re, lockPolicy.getReadLockMode(), this.txId,
            false, dataRegion);
        if (markPending) {
          removePendingOpWithLock(re);
        }
      }
      // READ_ONLY lock has already been released by the acquireLock call
      /*
      else if (isReadOnly) {
        // release the READ_ONLY lock since write lock has been acquired
        // and operation is successful
        lockPolicy.releaseLock(entry.getUnderlyingRegionEntry(),
            lockPolicy.getReadOnlyLockMode(), this.txId, false, dataRegion);
      }
      if (tx.destroy(event, cacheWrite)) {
        Object key = event.getKey();
        LocalRegion rr = region.getDataRegionForRead(event.getKeyInfo());
        readRegion(rr).rmEntryUserAttr(key);
      }
      */
      return true;
    } finally {
      // in case of failure release the lock
      if (!success && entry != null) {
        try {
          final RegionEntry re = entry.getUnderlyingRegionEntry();
          cleanupOnUnsuccessfulWrite(entry, re, event.getKey(), txr,
              dataRegion, lockPolicy, entry != null
                  && (txEntryCreated || isReadOnly), txEntryCreated,
              lockedForRead, false, Operation.DESTROY);
        } finally {
          txr.unlock();
        }
      }
      else {
        txr.unlock();
      }
    }
  }

  /**
   * @see TXStateInterface#invalidateExistingEntry(EntryEventImpl, boolean,
   *      boolean)
   */
  public final void invalidateExistingEntry(final EntryEventImpl event,
      final boolean invokeCallbacks, final boolean forceNewEntry) {
    txInvalidateExistingEntry(event, false, invokeCallbacks, forceNewEntry);
  }

  final boolean txInvalidateExistingEntry(final EntryEventImpl event,
      boolean markPending, boolean invokeCallbacks, boolean forceNewEntry) {
    assert invokeCallbacks && !forceNewEntry;

    final Operation op = event.getOperation();
    final LocalRegion region = event.getRegion();
    final LocalRegion dataRegion = region.getDataRegionForWrite(event, op);

    // if coordinator, then wait for region to initialize
    if (isCoordinator()) {
      dataRegion.waitForData();
    }

    final TXRegionState txr = writeRegion(dataRegion);
    final LockingPolicy lockPolicy = getLockingPolicy();
    TXEntryState entry = null;

    // check for uninitialized region
    if (txr == null) {
      // assume success (like it would have received via TXBatch) since actual
      // result will be returned by a node which is primary or GII source etc
      return true;
    }
    else if (!txr.isInitializedAndLockGII()) {
      // if not initialized then add to pending ops list and return
      entry = txr.createEntry(event.getKey(), null, null, false);
      entry.op = entry.adviseInvalidateOp(event, op);
      entry.setCallbackArgument(event.getCallbackArgument());
      txr.addPendingTXOpAndUnlockGII(entry,
          AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH);
      return true;
    }

    boolean txEntryCreated = false;
    boolean lockedForRead = false;
    boolean isReadOnly = false;
    boolean success = false;
    txr.lock();
    try {
      if ((entry = txWriteExistingEntry(event, region, dataRegion, txr,
          markPending, null)) != null) {
        if (entry.checkAndClearGIIEntry()) {
          if (markPending) {
            markPending(entry, entry.op, event.getCallbackArgument());
          }
          return true;
        }
        txEntryCreated = entry.isOpNull();
        if ((lockedForRead = entry.isOpFlaggedForRead())) {
          entry.op = TXEntryState.OP_NULL;
          txEntryCreated = true;
        }
        else {
          isReadOnly = entry.isOpReadOnly();
        }
        // acquire the lock on pending list first, else some other thread
        // can flush the list under our hood while we are still applying
        // the operation
        if (markPending) {
          this.headLock.lock();
          try {
            if ((success = entry.invalidate(event, op))) {
              entry.updateForCommitNoLock(this, true);
            }
          } finally {
            this.headLock.unlock();
          }
        }
        else {
          if ((success = entry.invalidate(event, op))) {
            entry.updateForCommit(this);
          }
        }
        if (success) {
          // release the read lock since write lock has been acquired
          if (lockedForRead) {
            final RegionEntry re = entry.getUnderlyingRegionEntry();
            lockPolicy.releaseLock(re, lockPolicy.getReadLockMode(),
                this.txId, false, dataRegion);
            if (markPending) {
              removePendingOpWithLock(entry);
            }
          }
          // READ_ONLY lock has already been released by the acquireLock call
          /*
          else if (isReadOnly) {
            // release the READ_ONLY lock since write lock has been acquired
            // and operation is successful
            lockPolicy.releaseLock(entry.getUnderlyingRegionEntry(),
                lockPolicy.getReadOnlyLockMode(), this.txId, false, dataRegion);
          }
          */
        }
        return true;
      }
      else {
        return false;
      }
    } finally {
      // in case of failure release the lock
      if (!success && entry != null) {
        try {
          final RegionEntry re = entry.getUnderlyingRegionEntry();
          cleanupOnUnsuccessfulWrite(entry, re, event.getKey(), txr,
              dataRegion, lockPolicy, entry != null
                  && (txEntryCreated || isReadOnly), txEntryCreated,
              lockedForRead, false, Operation.INVALIDATE);
        } finally {
          txr.unlock();
        }
      }
      else {
        txr.unlock();
      }
    }
  }

  private void cleanupOnUnsuccessfulWrite(TXEntryState tx, RegionEntry entry,
      Object key, TXRegionState txr, LocalRegion dataRegion,
      LockingPolicy lockPolicy, boolean lockAcquired, boolean txEntryCreated,
      boolean lockedForRead, boolean removeEntry, final Operation op) {
    if (lockAcquired) {
      // operation failed but write lock was acquired, so release it
      lockPolicy.releaseLock(entry, lockPolicy.getWriteLockMode(),
          null /* no need of lockOwner here */, false, dataRegion);
      // decrement the locked for create entry count in region
      if (tx != null) {
        tx.decrementLockedCountForRegion(dataRegion);
      }
      else {
        final AtomicInteger count = dataRegion.txLockCreateCount;
        for (;;) {
          final int current = count.get();
          if (current >= 1) {
            if (count.compareAndSet(current, current - 1)) {
              break;
            }
          }
          else {
            // never make -ve
            break;
          }
        }
      }
      if (removeEntry) {
        // also remove from map if the entry got added to map for locking
        if (entry.isLockedForCreate()) {
          dataRegion.entries.removeEntry(key, entry, false);
        }
      }
    }
    else if (dataRegion != null) {
      final AtomicInteger count = dataRegion.txLockCreateCount;
      for (;;) {
        final int current = count.get();
        if (current >= 1) {
          if (count.compareAndSet(current, current - 1)) {
            break;
          }
        }
        else {
          // never make -ve
          break;
        }
      }
    }
    // restore back the RegionEntry if it was locked for read, else remove
    if (txEntryCreated) {
      this.proxy.cleanupIndexEntry(txr, tx, op);
      if (lockedForRead) {
        Object oldEntryState = txr.getEntryMap().put(key, entry);
        if(oldEntryState instanceof TXEntryState) {
          ((TXEntryState)oldEntryState).release();
        }         
      }
      else {
        tx.removeFromList(this);
        //tx.release();
        Object currentEntryState =  txr.getEntryMap().remove(key);
        assert tx == currentEntryState;
        if(currentEntryState instanceof TXEntryState) {
          ((TXEntryState)currentEntryState).release();
        }
        
      }
    }
  }

  /**
   * Write an existing entry. This form takes an expectedOldValue which, if not
   * null, must be equal to the current value of the entry. If it is not, an
   * EntryNotFoundException is thrown.
   * 
   * @param event
   * @param expectedOldValue
   * @return the tx entry object
   * @throws EntryNotFoundException
   */
  private TXEntryState txWriteExistingEntry(final EntryEventImpl event,
      final LocalRegion region, final LocalRegion dataRegion,
      final TXRegionState txr, final boolean markPending,
      final Object expectedOldValue) throws EntryNotFoundException {
    assert !event.isExpiration();

    final Object eventKey = event.getKey();
    final Operation op = event.getOperation();
    Object val = null;

    // set originalRemote flag correctly using coordinator information
    event.setOriginRemote(!isCoordinator());

    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = region.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "txWriteExistingEntry on region "
          + region.getFullPath() + " dataRegion " + dataRegion.getFullPath()
          + " for " + this.txId.shortToString() + " markPending=" + markPending
          + ": " + (TXStateProxy.LOG_FINEST ? event.toString()
              : event.shortToString()));
    }

    final LockingPolicy lockPolicy = getLockingPolicy();
    final Object txEntry = txr.readEntry(eventKey);
    TXEntryState tx = null;
    RegionEntry entry = null;
    boolean txEntryCreated = false;
    boolean success = false;
    boolean lockedForRead = false;
    boolean lockAcquired = false;
    Object valToReleaseOnFailure = null;
    try {
      if (txEntry == null
          || (lockedForRead = (txEntry instanceof AbstractRegionEntry))) {
        final int flags = (lockedForRead ? AbstractRegionEntry.FOR_UPDATE : 0);
        entry = region.basicGetEntryForLock(dataRegion, eventKey);
        if (entry != null) {
          lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(), flags,
              this.txId, dataRegion, null);
          lockAcquired = true;
          val = dataRegion.getREValueForTXRead(entry);
          valToReleaseOnFailure = val;
        }
        // Avoid creating TXEntryState if we need to throw
        // EntryNotFoundException

        // Distributed operations on proxy regions need to be done
        // even if the entry does not exist locally.
        // But only if we don't already have a tx operation (once we have an op
        // then we honor tx.existsLocally since the tx has storage unlike the
        // proxy).
        // We must not throw EntryNotFoundException in this case
        else if (!region.isProxy() || op.isLocal()) {
          throw new EntryNotFoundException(eventKey.toString());
        }
        if (expectedOldValue != null) {
          checkExpectedOldValue(val, expectedOldValue, txr, region);
        }
        
        tx = txr.createReadEntry(eventKey, entry, val, false);
        txEntryCreated = true;
        valToReleaseOnFailure = null;
        // temporarily switch the op as a flag for callers
        if (lockedForRead) {
          tx.setOpFlagForRead();
        }
      }
      else {
        tx = (TXEntryState)txEntry;
        val = tx.getValueInTXOrRegion();
        if (expectedOldValue != null) {
          checkExpectedOldValue(val, expectedOldValue, txr, region);
        }
        if (tx.isOpReadOnly()) {
          // We cannot upgrade the lock from READ_ONLY to WRITE mode atomically
          // in all cases because others may also have the lock. However, if
          // there is only on READ_ONLY lock then this TX itself is the owner of
          // that and so allow the lock to be acquired in that case to have
          // atomic upgrade from READ_ONLY to WRITE mode.
          entry = tx.getUnderlyingRegionEntry();
          lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(),
              AbstractRegionEntry.ALLOW_ONE_READ_ONLY_WITH_EX_SH, this.txId,
              dataRegion, null);
          lockAcquired = true;
        }
      }

      assert tx != null;
      if (tx.existsLocally(true)) {
        event.setTXOldValue(val);
        final boolean invalidatingInvalidEntry = event.getOperation()
            .isInvalidate() && Token.isInvalid(val);
        // Ignore invalidating an invalid entry
        if (invalidatingInvalidEntry) {
          return null;
        }
      }
      else if (region.isProxy() && !op.isLocal() && !tx.isDirty()) {
        // Distributed operations on proxy regions need to be done
        // even if the entry does not exist locally.
        // But only if we don't already have a tx operation (once we have an op
        // then we honor tx#existsLocally since the tx has storage unlike the
        // proxy).
        // We must not throw EntryNotFoundException in this case
      }
      else {
        throw new EntryNotFoundException(eventKey.toString());
      }

      success = true;
      return tx;
    } finally {
      // in case of failure release the lock
      if (!success) {
        if (lockAcquired) {
          lockPolicy.releaseLock(entry, lockPolicy.getWriteLockMode(),
              null /* no need of lockOwner here */, false, dataRegion);
        }
        // replace back the read locked entry, else remove it
        if (txEntryCreated) {
          if (lockedForRead) {
            txr.getEntryMap().put(eventKey, entry);
          }
          else {
            txr.getEntryMap().remove(eventKey);
            if(tx != null) {
              tx.release();
            }
          }
        }
        OffHeapHelper.release(valToReleaseOnFailure);
      }
    }
  }

  /**
   * @see InternalDataView#getEntry(Object, Object, LocalRegion, boolean)
   */
  public Region.Entry<?, ?> getEntry(final Object key,
      final Object callbackArg, final LocalRegion localRegion,
      final boolean allowTombstones) {
    // creating a KeyInfo here to avoid possible multiple resolver calls
    // to get the bucketId
    final KeyInfo keyInfo = localRegion.getKeyInfo(key, callbackArg);
    return getEntry(keyInfo, localRegion, false, allowTombstones);
  }

  /**
   * @see InternalDataView#accessEntry(Object, Object, LocalRegion)
   */
  public Region.Entry<?, ?> accessEntry(final Object key,
      final Object callbackArg, final LocalRegion localRegion) {
    // creating a KeyInfo here to avoid possible multiple resolver calls
    // to get the bucketId
    final KeyInfo keyInfo = localRegion.getKeyInfo(key, callbackArg);
    return getEntry(keyInfo, localRegion, true, false);
  }

  final Region.Entry<?, ?> getEntry(final KeyInfo keyInfo,
      final LocalRegion localRegion, final boolean access,
      final boolean allowTombstones) {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(keyInfo,
        Operation.GET_ENTRY);
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      final Object key = keyInfo.getKey();
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            final TXEntryState txes = (TXEntryState)txEntry;
            if (txes.existsLocally()) {
              if (txes.isDirty()) {
                return new TXEntry(localRegion, dataRegion, key, txes, this);
              }
              return localRegion.new NonTXEntry(txes.getUnderlyingRegionEntry());
            }
            // It was destroyed by the transaction so skip
            // this key and try the next one
            return null; // fix for bug 34583
          }
          else {
            // for read locked entries, no need to take the read lock again
            final AbstractRegionEntry entry = (AbstractRegionEntry)txEntry;
            if (!entry.isDestroyedOrRemoved()) {
              return localRegion.new NonTXEntry(entry);
            }
            return null;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    // compare the version and then return correct re.
    return localRegion.txGetEntry(keyInfo, access, this, allowTombstones);
  }

  /**
   * Read {@link TXEntryState} for given key in region. Note that the returned
   * {@link TXEntryState} cannot be safely accessed exception under the
   * {@link TXRegionState#lock()} so caller must take the lock before calling
   * this method and hold the lock till access to returned {@link TXEntryState}
   * is done with. The entry can itself be concurrently modified by another
   * parallel function execution thread in same transaction or even statement
   * for example.
   * 
   * @param keyInfo
   *          the {@link KeyInfo} for the entry to be read
   * @param localRegion
   *          the {@link LocalRegion} from which the entry is to be read
   * @param dataRegion
   *          the actual underlying region containing the data (obtained from
   *          {@link LocalRegion#getDataRegionForRead} of
   *          {@link LocalRegion#getDataRegionForWrite}
   * @param txr
   *          the {@link TXRegionState} for the region obtained via
   *          {@link #readRegion(LocalRegion)} or
   *          {@link #writeRegion(LocalRegion)}; the
   *          {@link TXRegionState#lock()} must also have been invoked before
   *          calling this method
   * @param op
   *          the Cache {@link Operation} for which this method has been invoked
   * 
   * @return a txEntryState or null if the entry doesn't exist in the
   *         transaction and/or committed state.
   */
  public final TXEntryState txReadEntry(final KeyInfo keyInfo,
      final LocalRegion localRegion, final LocalRegion dataRegion,
      final TXRegionState txr, final Operation op) {
    // TXState lock should have been taken before
    assert txr.isHeldByCurrentThread();

    final Object txEntry = txr.readEntry(keyInfo.getKey());
    return txEntry instanceof TXEntryState ? (TXEntryState)txEntry : null;
  }

  private void checkExpectedOldValue(Object val, final Object expectedOldValue,
      final TXRegionState txr, final LocalRegion localRegion) {
    if (val instanceof CachedDeserializable) {
      val = ((CachedDeserializable)val).getDeserializedForReading();
    }
    else if (val instanceof Token) {
      val = null;
    }
    if (!expectedOldValue.equals(val)) {
      throw new EntryNotFoundException(LocalizedStrings
          .AbstractRegionMap_THE_CURRENT_VALUE_WAS_NOT_EQUAL_TO_EXPECTED_VALUE
              .toLocalizedString());
    }
  }

  @Override
  public final Object getDeserializedValue(Object key, Object callbackArg,
      LocalRegion localRegion, boolean updateStats, boolean disableCopyOnRead,
      boolean preferCD, TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) {
    return getDeserializedValue(key, callbackArg, localRegion, updateStats,
        disableCopyOnRead, preferCD, false, clientEvent, allowTombstones, allowReadFromHDFS);
  }


  private Object getDeserializedValue(Object key, Object callbackArg,
      LocalRegion localRegion, boolean updateStats, boolean disableCopyOnRead,
      boolean preferCD, boolean doCopy, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, KeyInfo.UNKNOWN_BUCKET, Operation.GET);
    AbstractRegionEntry re = null;
    TXState tx = this;
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            return getTXValue((TXEntryState)txEntry, localRegion, dataRegion,
                doCopy, preferCD);
          }
          else {
            // for read locked entries, no need to take the read lock again
            re = (AbstractRegionEntry)txEntry;
            tx = null;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    final Object val = localRegion.getDeserializedValue(re, key, callbackArg,
        updateStats, disableCopyOnRead, preferCD, tx, clientEvent,
        allowTombstones, allowReadFromHDFS);
    if (val != null && doCopy && !disableCopyOnRead
        && !localRegion.isCopyOnRead()) {
      return localRegion.makeCopy(val);
    }
    return val;
  }

  //Suranjan compare here for snapshot. This is for primary key based.
  @Retained
  public Object getLocally(Object key, Object callbackArg, int bucketId,
      LocalRegion localRegion, boolean doNotLockEntry, boolean localExecution,
      TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) throws DataLocationException {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, bucketId, Operation.GET);
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            @Retained final Object v = ((TXEntryState)txEntry).getRetainedValueInTXOrRegion();
            if (!Token.isInvalid(v)) {
              return v;
            }
            return null;
          }
          else {
            // for read locked entries, no need to take the read lock again
            // since this is REPEATABLE_READ we expect the value to be still
            // there in the region (expiration/eviction etc. should be blocked
            // for these entries)
            @Retained final Object v = dataRegion.getEntryValue((RegionEntry)txEntry);
            if (!Token.isInvalid(v)) {
              return v;
            }
            return null;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    // Get the entry
    return localRegion.getSharedDataView().getLocally(key, callbackArg,
        bucketId, localRegion, doNotLockEntry, localExecution, this,
        clientEvent, allowTombstones, allowReadFromHDFS);
  }

  @Unretained
  final Object getTXValue(final TXEntryState tx, final LocalRegion localRegion,
      final LocalRegion dataRegion, final boolean doCopy, boolean preferCD) {
    @Unretained Object val = tx.getValueInTXOrRegion();
    if (val != null) {
      if (!Token.isInvalid(val)) {
        val = tx.getDeserializedValue(dataRegion, val, preferCD);
        if ((doCopy || localRegion.isCopyOnRead())) {
          return localRegion.makeCopy(val);
        }
      }
      return val;
    }
    return null;
  }

  @Override
  @Retained
  public Object getSerializedValue(LocalRegion localRegion, KeyInfo keyInfo,
      boolean doNotLockEntry, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean allowTombstones, boolean allowReadFromHDFS)
      throws DataLocationException {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(keyInfo,
        Operation.GET);
    // first check if the entry is locked, and only then lookup in the TX state
    // rationale being that for majority of lookups the entry from Region has
    // to be fetched so no use looking up the TX state first
    final Object key = keyInfo.getKey();
    getLockingPolicy();
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            // if the value is in TX then return that even if invalid or
            // destroyed
            @Retained final Object val = ((TXEntryState)txEntry).getRetainedValueInTXOrRegion();
            if (!Token.isInvalid(val)) {
              return val;
            }
            return null;
          }
          else {
            // for read locked entries, no need to take the read lock again
            @Retained final Object val = dataRegion.getREValueForTXRead(
                (AbstractRegionEntry)txEntry);
            if (!Token.isInvalid(val)) {
              return val;
            }
            return null;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    // read the value under the read lock
    if (localRegion.getPartitionAttributes() != null) {
      assert localRegion instanceof PartitionedRegion;
      final PartitionedRegion pr = (PartitionedRegion)localRegion;
      return pr.getDataStore().getSerializedLocally(keyInfo, doNotLockEntry,
          this, clientEvent, allowTombstones, allowReadFromHDFS);
    }
    else {
      @Retained Object val = LocalRegionDataView.getEntryValue(localRegion, key, true,
          this);
      if (val != null && !Token.isInvalid(val)) {
        return val;
      }
      // check for a local loader
      if (localRegion.basicGetLoader() != null) {
        val = localRegion
            .findObjectInLocalSystem(key, keyInfo.getCallbackArg(),
                val == null, this, true, val, clientEvent);
        if (val != null && !Token.isInvalid(val)) {
          // wrap in RawValue to indicate that this is from CacheLoader
          return RawValue.newInstance(val, localRegion.getCache())
              .setFromCacheLoader();
        }
      }
    }
    // could not find the value locally so return null
    return null;
  }

  public int entryCount(LocalRegion localRegion) {
    int result = localRegion.getRegionSize();
    final TXRegionState txr = readRegion(localRegion);
    if (txr != null) {
      txr.lock();
      try {
        result += txr.entryCountMod();
      } finally {
        txr.unlock();
      }
    }
    if (result >= 0) {
      return result;
    }
    else {
      // This is to work around bug #40946.
      // Other threads can destroy all the keys, and so our entryModCount
      // can bring us below 0
      // [sumedh] should never happen in the new TX model; but as of now non-TX
      // ops can interfere and bring the count down without TX knowing about it
      return 0;
      //throw new InternalGemFireError("unexpected TXState#entryCount " + result);
    }
  }

  public boolean containsKeyWithReadLock(final Object key,
      final Object callbackArg, final LocalRegion localRegion) {
    return containsKeyWithReadLock(key, callbackArg, localRegion, false);
  }

  final boolean containsKeyWithReadLock(final Object key,
      final Object callbackArg, final LocalRegion localRegion,
      final boolean markPending) {
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = localRegion.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG,
          "containsKeyWithReadLock called for key: " + key + " for "
              + this.txId.shortToString() + " in localRegion: " + localRegion);
    }

    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, KeyInfo.UNKNOWN_BUCKET, Operation.CONTAINS_KEY);

    // if coordinator, then wait for region to initialize
    if (isCoordinator()) {
      dataRegion.waitForData();
    }

    final TXRegionState txr = writeRegionForRead(dataRegion);

    // check for uninitialized region
    if (txr == null) {
      return false;
    }
    else if (!txr.isInitializedAndLockGII()) {
      // if not initialized then add to pending ops list and return
      TXEntryState entry = txr.createEntry(key, null, null, false);
      entry.setOpType(TXEntryState.getReadOnlyOp(), callbackArg);
      txr.addPendingTXOpAndUnlockGII(entry, 0);
      return false;
    }

    try {
      final Object txEntry = lockEntry(null, key, callbackArg, txr,
          localRegion, dataRegion, false, true, TXEntryState.getReadOnlyOp(),
          markPending, LOCK_ENTRY_NOT_FOUND | LOCK_ALREADY_HELD_IN_TX_NO_VALUE,
          null);
      if (TXStateProxy.LOG_FINEST) {
        final LogWriterI18n logger = localRegion.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG,
            "containsKeyWithReadLock for key=" + key + "entry="
                + localRegion.basicGetEntryForLock(dataRegion, key)
                + " lockEntry=" + txEntry);
      }
      return txEntry != null;
    } catch (EntryNotFoundException enfe) {
      return false;
    }
  }

  public boolean containsKey(Object key, Object callbackArg,
      LocalRegion localRegion) {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, KeyInfo.UNKNOWN_BUCKET, Operation.CONTAINS_KEY);
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            return ((TXEntryState)txEntry).existsLocally();
          }
          else {
            // for read locked entries, no need to take the read lock again
            final AbstractRegionEntry re = (AbstractRegionEntry)txEntry;
            return !re.isDestroyedOrRemoved();
          }
        }
      } finally {
        txr.unlock();
      }
    }
    return localRegion.txContainsKey(key, callbackArg, this, false);
  }

  public boolean containsValueForKey(Object key, Object callbackArg,
      LocalRegion localRegion) {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, KeyInfo.UNKNOWN_BUCKET, Operation.CONTAINS_VALUE_FOR_KEY);
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            /**
             * Note that we don't consult this.getDataPolicy().isProxy() when
             * setting this because in this context we don't want proxies to
             * pretend they have a value.
             */
            return ((TXEntryState)txEntry).isLocallyValid(false);
          }
          else {
            // for read locked entries, no need to take the read lock again
            // no need to decompress since we only want to know
            // if we have an existing value
            @Retained @Released final Object val = ((AbstractRegionEntry)txEntry)._getValueRetain(
                dataRegion, false);
            return localRegion.containsValueInternal(val);
          }
        }
      } finally {
        txr.unlock();
      }
    }
    return localRegion.txContainsValueForKey(key, callbackArg, this);
  }

  @Override
  @Retained
  public final Object getValueInVM(Object key, Object callbackArg,
      LocalRegion localRegion) throws EntryNotFoundException {
    final LocalRegion dataRegion = localRegion.getDataRegionForRead(key,
        callbackArg, KeyInfo.UNKNOWN_BUCKET, Operation.GET_ENTRY);
    final TXRegionState txr = readRegion(dataRegion);
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            return ((TXEntryState)txEntry).getValueInVM(key);
          }
          else {
            @Retained final Object v = ((AbstractRegionEntry)txEntry)
                .getValueInVM(localRegion);
            if (Token.isRemoved(v)) {
              throw new EntryNotFoundException(String.valueOf(key));
            }
            if (v == Token.NOT_AVAILABLE) {
              return null;
            }
            return v;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    // no TX specific read locks since this is not a public method, and it is
    // invoked from all over the place (including internal accesses)
    return localRegion.nonTXbasicGetValueInVM(key, callbackArg);
  }

  /**
   * this version of putEntry takes a ConcurrentMap expectedOldValue parameter.
   * If not null, this value must match the current value of the entry or false
   * is returned
   * 
   * @param event
   *          the {@link EntryEvent} for the put operation
   * @param ifNew
   *          only write the entry if it currently does not exist
   * @param requireOldValue
   *          if true set the old value in the event, even if ifNew and entry
   *          doesn't currently exist (this is needed for putIfAbsent).
   * @param cacheWrite
   *          true if {@link CacheWriter} should be invoked for current
   *          operation
   * @param expectedOldValue
   *          the required old value or null
   */
  @Override
  public final boolean putEntry(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed) {
    validateDelta(event);
    return putEntry(event, ifNew, ifOld, true, expectedOldValue,
        requireOldValue, cacheWrite, false, lastModified, overwriteDestroyed);
  }

  public final boolean putEntryLocally(final EntryEventImpl event,
      final boolean ifNew, final boolean ifOld, final Object expectedOldValue,
      final boolean requireOldValue, final boolean markPending,
      final long lastModified, final boolean overwriteDestroyed) {
    validateDelta(event);
    return putEntry(event, ifNew, ifOld, true, expectedOldValue,
        requireOldValue, true, markPending, lastModified, overwriteDestroyed);
  }

  /**
   * throws an exception when cloning is disabled while using delta
   * 
   * @param event
   */
  private void validateDelta(EntryEventImpl event) {
    if (event.getDeltaBytes() != null
        && !event.getRegion().getAttributes().getCloningEnabled()) {
      throw new UnsupportedOperationInTransactionException(
          LocalizedStrings.TXState_DELTA_WITHOUT_CLONING_CANNOT_BE_USED_IN_TX
              .toLocalizedString());
    }
  }

  public boolean isDeferredStats() {
    return true;
  }

  @Override
  public Object findObject(KeyInfo keyInfo, LocalRegion r, boolean isCreate,
      boolean generateCallbacks, Object value, boolean disableCopyOnRead,
      boolean preferCD, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean allowTombstones, boolean allowReadFromHDFS) {
    // just invoke the region's findObject method since TXState view has already
    // been obtained in getDeserializedValue()
    return r.findObjectInSystem(keyInfo, isCreate, this, generateCallbacks,
        value, disableCopyOnRead, preferCD, requestingClient, clientEvent,
        allowTombstones, allowReadFromHDFS);
  }

  // TODO: Suranjan for snapshot isolation, allowTombstones should be true
  // also check for version of the entry with TOMBSTONES so that only those
  // should be checked in oldEntryMap.

  public Region.Entry<?, ?> getEntryForIterator(final KeyInfo keyInfo,
      final LocalRegion region, boolean allowTombstones) {
    // for local/distributed regions, the key is the RegionEntry itself
    // getDataRegion will work correctly neverthless
    final InternalDataView sharedView = region.getSharedDataView();
    final Object re = keyInfo.getKey();
    final Object key = sharedView.getKeyForIterator(re, region);
    if (key != null) {
      final LocalRegion dataRegion = region.getDataRegionForRead(keyInfo,
          Operation.GET_ENTRY);
      final TXRegionState txr = readRegion(dataRegion);
      if (txr != null) {
        txr.lock();
        try {
          final Object txEntry = txr.readEntry(key);
          if (txEntry != null) {
            if (txEntry instanceof TXEntryState) {
              final TXEntryState txes = (TXEntryState)txEntry;
              if (txes.existsLocally()) {
                if (txes.isDirty()) {
                  return new TXEntry(region, dataRegion, key, txes, this);
                }
                return region.new NonTXEntry(txes.getUnderlyingRegionEntry());
              }
              // It was destroyed by the transaction so skip
              // this key and try the next one
              return null; // fix for bug 34583
            }
            else {
              // for read locked entries, no need to take the read lock again
              final AbstractRegionEntry entry = (AbstractRegionEntry)txEntry;
              if (!entry.isDestroyedOrRemoved()) {
                return region.new NonTXEntry(entry);
              }
              return null;
            }
          }
        } finally {
          txr.unlock();
        }
      }
      return region
          .txGetEntryForIterator(keyInfo, false, this, allowTombstones);
    }
    return null;
  }

  @Unretained
  public Object getValueForIterator(final KeyInfo keyInfo,
      final LocalRegion region, boolean updateStats, boolean preferCD,
      final EntryEventImpl clientEvent, boolean allowTombstones) {
    // for local/distributed regions, the key is the RegionEntry itself
    // getDataRegion will work correctly neverthless
    final InternalDataView sharedView = region.getSharedDataView();
    final Object re = keyInfo.getKey();
    final Object key = sharedView.getKeyForIterator(re, region);
    final LocalRegion dataRegion = region.getDataRegionForRead(keyInfo,
        Operation.GET);
    final TXRegionState txr = readRegion(dataRegion);
    TXState tx = this;
    if (txr != null) {
      txr.lock();
      try {
        final Object txEntry = txr.readEntry(key);
        if (txEntry != null) {
          if (txEntry instanceof TXEntryState) {
            return getTXValue((TXEntryState)txEntry, region, dataRegion, false,
                preferCD);
          }
          else {
            // for read locked entries, no need to take the read lock again
            tx = null;
          }
        }
      } finally {
        txr.unlock();
      }
    }
    // use findObject for PRs, while use local lookups otherwise
    if (region.getPartitionAttributes() != null) {
      return region.findObjectInSystem(keyInfo, false, tx, true, null, false,
          preferCD, null, clientEvent, allowTombstones, false/*allowReadFromHDFS*/);
    }
    else {
      return region.getDeserializedValue((AbstractRegionEntry)re, key, null,
          updateStats, false, preferCD, tx, clientEvent, allowTombstones, false/*allowReadFromHDFS*/);
    }
  }

  // For snapshot return the key for tombstone as well
  // at higher level check if the key is present in the oldEntryMap
  public Object getKeyForIterator(final KeyInfo keyInfo,
      final LocalRegion region, boolean allowTombstones) {
    // only invoked for Local/Distributed Regions
    assert region.getPartitionAttributes() == null;

    final Object k = keyInfo.getKey();
    if (k != null) {
      assert k instanceof AbstractRegionEntry:
        "expected RegionEntry in iteration but got: " + k.getClass();
      final AbstractRegionEntry re = (AbstractRegionEntry)k;
      Object key = null;
      // need to check in TXState only if the entry has been locked by a TX
      if (getLockingPolicy().lockedForWrite(re, null, null)) {
        final TXRegionState txr = readRegion(region);
        if (txr != null) {
          key = region.getSharedDataView().getKeyForIterator(re, region);
          txr.lock();
          try {
            final Object txEntry = txr.readEntry(key);
            if (txEntry instanceof TXEntryState
                && !((TXEntryState)txEntry).existsLocally()) {
              // It was destroyed by the transaction so skip
              // this key and try the next one
              return null; // fix for bug 34583
            }
          } finally {
            txr.unlock();
          }
          if (allowTombstones || !re.isTombstone()) {
            return key;
          }
        }
      }
      if (!re.isDestroyedOrRemoved()
          || (allowTombstones && re.isTombstone())) {
        if (key == null) {
          return region.getSharedDataView().getKeyForIterator(re, region);
        }
        else {
          return key;
        }
      }
    }
    return null;
  }

  public Object getKeyForIterator(Object key, LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public Collection<?> getAdditionalKeysForIterator(LocalRegion currRgn) {
    // region already has the additional created keys due to locking so no need
    // to send anything more here
    return null;
    /*
    if (currRgn.getPartitionAttributes() != null) {
      final HashSet<Object> ret = new HashSet<Object>();
      for (TXRegionState rs : this.regions.values()) {
        if (rs.getPartitionedRegion() == currRgn) {
          rs.fillInCreatedEntryKeys(ret);
        }
      }
      return ret;
    }
    else {
      TXRegionState txr = readRegion(currRgn);
      if (txr != null) {
        final HashSet<Object> ret = new HashSet<Object>();
        txr.fillInCreatedEntryKeys(ret);
        return ret;
      }
      return null;
    }
    */
  }

  public boolean putEntryOnRemote(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed)
      throws DataLocationException {
    /*
     * Need to flip OriginRemote to true because it is certain that this came
     * from a remote TxStub.
     * [sumedh] No need to set origin remote flag. Now this is set in each
     * event by checking if this TXState is coordinator's or not (we may get
     *   remote call here even on coordinator via nested function execution)
     * see bug #41498
     */
    //event.setOriginRemote(true);
    // SNAPSHOT: Apply the operation in the region directly

    return putEntry(event, ifNew, ifOld, expectedOldValue, requireOldValue,
        cacheWrite, lastModified, overwriteDestroyed);
  }

  public boolean isFireCallbacks() {
    return true;
  }

  public void destroyOnRemote(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) throws DataLocationException {
    // SNAPSHOT: Apply the operation in the region directly
    txDestroyExistingEntry(event, cacheWrite, false, expectedOldValue);
  }

  public void invalidateOnRemote(EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry) throws DataLocationException {
    // SNAPSHOT: Apply the operation in the region directly
    invalidateExistingEntry(event, invokeCallbacks, forceNewEntry);
  }

  @Override
  public void checkSupportsRegionDestroy()
      throws UnsupportedOperationInTransactionException {
    getProxy().checkSupportsRegionDestroy();
  }

  @Override
  public void checkSupportsRegionInvalidate()
      throws UnsupportedOperationInTransactionException {
    getProxy().checkSupportsRegionInvalidate();
  }

  public Set<?> getBucketKeys(LocalRegion localRegion, int bucketId,
      boolean allowTombstones) {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.getBucketKeys(bucketId, allowTombstones);
  }

  public EntrySnapshot getEntryOnRemote(final KeyInfo keyInfo,
      final LocalRegion localRegion, boolean allowTombstones)
      throws DataLocationException {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    final Object key = keyInfo.getKey();
    final Object val = getLocally(key, keyInfo.callbackArg, keyInfo.bucketId,
        pr, false, true /* localExecution true to avoid RawValue */,
        null, null, allowTombstones, true);
    if (val != null) {
      NonLocalRegionEntry nlre = NonLocalRegionEntry.newEntry(key, val, pr,
          null);
      LocalRegion dataReg = localRegion.getDataRegionForRead(keyInfo,
          Operation.GET_ENTRY);
      return new EntrySnapshot(nlre, dataReg, pr, allowTombstones);
    }
    else {
      throw new EntryNotFoundException(
          LocalizedStrings.PartitionedRegionDataStore_ENTRY_NOT_FOUND
              .toLocalizedString());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<?> getRegionKeysForIteration(final LocalRegion currRegion,
      final boolean includeValues) {
    // region already has all entries due to locking, so just return that
    return currRegion.getSharedDataView().getRegionKeysForIteration(currRegion,
        includeValues);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<?> getLocalEntriesIterator(
      final InternalRegionFunctionContext context, final boolean primaryOnly,
      final boolean forUpdate, final boolean includeValues,
      final LocalRegion region) {
    // for PR we pass the TX along so its iterator can itself invoke
    // getLocalEntry with correct BucketRegion
    if (region.getPartitionAttributes() != null) {
      return ((PartitionedRegion)region).localEntriesIterator(context,
          primaryOnly, forUpdate, includeValues, this);
    }
    else {
      // this will in turn invoke getLocalEntry at each iteration and lookup
      // from local TXState if required
      return new EntriesSet.EntriesIterator(region, false,
          IteratorType.RAW_ENTRIES, this, forUpdate, true, true, true,
          includeValues);
    }
  }

  public Iterator<?> getLocalEntriesIterator(
      Set<Integer> bucketSet, final boolean primaryOnly,
      final boolean forUpdate, final boolean includeValues,
      final LocalRegion region) {
    // for PR we pass the TX along so its iterator can itself invoke
    // getLocalEntry with correct BucketRegion
    if (region.getPartitionAttributes() != null) {
      return ((PartitionedRegion)region).localEntriesIterator(bucketSet,
          primaryOnly, forUpdate, includeValues, this);
    }
    else {
      // this will in turn invoke getLocalEntry at each iteration and lookup
      // from local TXState if required
      return new EntriesSet.EntriesIterator(region, false,
          IteratorType.RAW_ENTRIES, this, forUpdate, true, true, true,
          includeValues);
    }
  }

  /**
   * @see InternalDataView#postPutAll(DistributedPutAllOperation,
   *      VersionedObjectList, LocalRegion)
   */
  public void postPutAll(DistributedPutAllOperation putAllOp,
      VersionedObjectList successfulPuts, LocalRegion region) {
    // nothing to be done here
    final LogWriterI18n logger = region.getLogWriterI18n();
    if (isSnapshot()) {
      if (logger.fineEnabled()) {
        logger.info(LocalizedStrings.DEBUG, "TXState: in postPutAll with tx " + this);
      }
      getProxy().addAffectedRegion(region);
      region.getSharedDataView().postPutAll(putAllOp, successfulPuts, region);
      return;
    }
  }

  /**
   * Return either the {@link TXEntryState} if the given entry is in TXState
   * else return the provided region entry itself.
   */
  public final Object getLocalEntry(final LocalRegion region,
      LocalRegion dataRegion, final int bucketId, final AbstractRegionEntry re, boolean isWrite) {

    // for local/distributed regions, the key is the RegionEntry itself
    // getDataRegion will work correctly neverthless

    // need to check in TXState only if the entry has been locked by a TX
    final boolean checkTX = getLockingPolicy().lockedForWrite(re, null, null);
    if (TXStateProxy.LOG_FINE) {
      final LogWriterI18n logger = region.getLogWriterI18n();
      if (re.getVersionStamp() != null) {
        logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
            + region.getFullPath() + " RegionEntry(" + re + ") checkTX="
            + checkTX + "version " + re.getVersionStamp().asVersionTag());
      }
    }
    if (checkTX) {
      final Object key = re.getKey();
      if (dataRegion == null) {
        dataRegion = region.getDataRegionForRead(key, null, bucketId,
            Operation.GET_ENTRY);
      }
      final TXRegionState txr = readRegion(dataRegion);
      if (txr != null) {
        txr.lock();
        try {
          final Object txEntry = txr.readEntry(key);
          if (txEntry instanceof TXEntryState) {
            final TXEntryState tx = (TXEntryState)txEntry;
            if (TXStateProxy.LOG_FINEST) {
              final LogWriterI18n logger = dataRegion.getLogWriterI18n();
              logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
                  + dataRegion.getFullPath() + " found " + tx
                  + " existsLocally=" + tx.existsLocally()
                  + " for RegionEntry(" + re + ')');
            }
            if (tx.existsLocally()) {
              if (tx.isDirty()) {
                return tx;
              }
              return re;
            }
            // It was destroyed by the transaction so skip
            // this key and try the next one
            return null; // fix for bug 34583
          } else if (!isWrite && shouldGetOldEntry(dataRegion)) {
            // the re has not been modified by this tx
            // check the re version with the snapshot version and then search in oldEntry
            if (dataRegion.getVersionVector() != null && !checkEntryInSnapshot(this, dataRegion, re)) {
              return getOldVersionedEntry(this, dataRegion, key, re);
            }
          }
        } finally {
          txr.unlock();
        }
      }
    } else if (!isWrite && shouldGetOldEntry(dataRegion)) {
      final Object key = re.getKeyCopy();
      if (dataRegion == null) {
        dataRegion = region.getDataRegionForRead(key, null, bucketId,
            Operation.GET_ENTRY);
      }
      if (dataRegion.getVersionVector() != null) {
        if (!checkEntryInSnapshot(this, dataRegion, re)) {
          return getOldVersionedEntry(this, dataRegion, key, re);
        }
      }
    }

    if (shouldGetOldEntry(dataRegion)) {
      synchronized (re) {
        if (checkEntryInSnapshot(this, dataRegion, re)) {
          // TODO: SW: this is a major performance problem because it will always read value
          // from disk in essentially random order and then do a faultin; higher level
          // DiskBlockSorter etc will be completely ineffective
          // Proper solution is to create a snapshot of the original RegionEntry with diskId
          // if value has been evicted, and create NLRE only for in-memory entries.
          return NonLocalRegionEntry.newEntry(re, dataRegion, true);
        }
      }
      return getOldVersionedEntry(this, dataRegion, re.getKey(), re);
    }
    return re;
  }

  private boolean shouldGetOldEntry(LocalRegion region) {
    return region.isSnapshotEnabledRegion();
  }

  // Writer should add old entry with tombstone with region version in the common map
  // wait till writer has written to common old entry map.
  private static Object getOldVersionedEntry(TXState tx, LocalRegion dataRegion, Object key, RegionEntry re) {
    Object oldEntry = dataRegion.getCache().readOldEntry(dataRegion, key, tx.getCurrentSnapshot(),
        true, re, tx);
    if (oldEntry != null) {
      return oldEntry;
    } else {
      // wait till it is populated..
      // The update/destroy guy can update the region version first and then modify the RE
      // later copy it to running tx so that when tx misses the entry it is sure that
      // it will be copied by writer thread
      // If we copy first and then update the region version and RE then there is a window where
      // concurrent tx can miss the old entry.
      // 1. Copy of the old value
      // 2. New tx starts and takes the snapshot
      // 3. old tx increments the regionVersion
      // 4. New tx scans and misses the changed RE as its version is higher than the snapshot.
      // 5. old tx changes the RE

      // For Transaction NONE we can get locally. For tx isolation level RC/RR
      // we will have to get from a common DS.
      oldEntry = dataRegion.getCache().readOldEntry(dataRegion, key, tx.getCurrentSnapshot(), true, re, tx);
      int numtimes = 0;
      while (oldEntry == null) {
        LogWriterI18n logger = dataRegion.getLogWriterI18n();
        if (TXStateProxy.LOG_FINE) {
          logger.info(LocalizedStrings.DEBUG, " Waiting for older entry for this snapshot to arrive " +
              "for key " + key + " re " + re + " for region " + dataRegion.getFullPath() + " my txId is "
              + tx.getTransactionId());
        }
        try {
          // Suranjan Should we wait indefinitely? or throw warning and return the current entry.
          if (numtimes < 10) {
            Thread.sleep(3);
            numtimes++;
          } else {
            Thread.sleep(100 * numtimes);
            numtimes++;
          }
          // Should we wait more before throwing exception? Make it a property.
          if (numtimes > 50) {
            throw new TransactionInDoubtException("The entry corresponding to snapshot could not be found.");
          }
        } catch (InterruptedException e) {
          if (TXStateProxy.LOG_FINE) {
            logger.info(LocalizedStrings.DEBUG, " Interrupted while waiting for older entry.");
          }
        }
        oldEntry = dataRegion.getCache().readOldEntry(dataRegion, key, tx.getCurrentSnapshot(), true, re, tx);
      }
      return oldEntry;
    }
  }
  /**
   * Test to see if this vector has seen the given version.
   * It should also include any changes done by this tx.
   * @return true if this vector has seen the given version
   */
  private boolean isVersionInSnapshot(Region region, VersionSource id, long version, boolean includeOwnChange) {
    // For snapshot we don't  need to check from the current version
    final LogWriterI18n logger = ((LocalRegion)region).getLogWriterI18n();

    if (includeOwnChange) {
      for (VersionInformation obj : this.queue) {
        if (id == obj.member && (version == obj.version) &&
            region == obj.region)

          if (TXStateProxy.LOG_FINE) {
            logger.info(LocalizedStrings.DEBUG, " The version found in the current tx : " + this);
          }
        return true;
      }
    }

    Map<VersionSource, RegionVersionHolder> regionSnapshot;
    if ((regionSnapshot = this.snapshot.get(region.getFullPath())) != null) {
      RegionVersionHolder holder = regionSnapshot.get(id);
      if (holder == null) {
        if (TXStateProxy.LOG_FINE) {
          logger.info(LocalizedStrings.DEBUG, " The holder against the region is null, returning false. ");
        }
        return false;
      } else {
        return holder.contains(version);
      }
    } else {
      return false;
    }
  }

  public static boolean checkEntryInSnapshot(TXStateInterface tx, Region region, RegionEntry entry) {
    if (tx.isSnapshot() && ((LocalRegion)region).concurrencyChecksEnabled) {
      VersionStamp stamp = entry.getVersionStamp();
      VersionSource id = stamp.getMemberID();
      final LogWriterI18n logger = ((LocalRegion)region).getLogWriterI18n();

      if (id == null) {
        if (((LocalRegion)region).getVersionVector().isDiskVersionVector()) {
          id = ((LocalRegion)region).getDiskStore().getDiskStoreID();
        } else {
          id = InternalDistributedSystem.getAnyInstance().getDistributedMember();
        }
        if (TXStateProxy.LOG_FINEST) {
          logger.info(LocalizedStrings.DEBUG, "checkEntryInSnapshot: for region "
              + region.getFullPath() + " RegionEntry(" + entry + ")" + " id not set in Entry, setting id to: " +
              id);
        }
      }
      // if rvv is not present then
      TXState state = tx.getLocalTXState();
      if (state.getCurrentRvvSnapShot() != null) {
        if (state.isVersionInSnapshot(region, id, stamp.getRegionVersion(), true)) {
          if (TXStateProxy.LOG_FINEST) {
            logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
                + region.getFullPath() + " RegionEntry(" + entry + ") with version " + stamp
                .getRegionVersion() + " id: " + id + " , returning true.");
          }
          return true;
        }
      }
      if (TXStateProxy.LOG_FINE) {
        logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
            + region.getFullPath() + " RegionEntry(" + entry + ") with version " + stamp
            .getRegionVersion() + " id: " + id + " , returning false.");
      }
      return false;
    }
    return true;
  }

  public static boolean checkEntryInSnapshotWithoutOwnChange(TXStateInterface tx, Region region, RegionEntry entry) {
    if (tx.isSnapshot() && ((LocalRegion)region).concurrencyChecksEnabled) {
      VersionStamp stamp = entry.getVersionStamp();
      VersionSource id = stamp.getMemberID();
      final LogWriterI18n logger = ((LocalRegion)region).getLogWriterI18n();

      if (id == null) {
        if (((LocalRegion)region).getVersionVector().isDiskVersionVector()) {
          id = ((LocalRegion)region).getDiskStore().getDiskStoreID();
        } else {
          id = InternalDistributedSystem.getAnyInstance().getDistributedMember();
        }
        if (TXStateProxy.LOG_FINEST) {
          logger.info(LocalizedStrings.DEBUG, "checkEntryInSnapshot: for region "
              + region.getFullPath() + " RegionEntry(" + entry + ")" + " id not set in Entry, setting id to: " +
              id);
        }
      }
      // if rvv is not present then
      TXState state = tx.getLocalTXState();
      if (state.getCurrentRvvSnapShot() != null) {
        if (state.isVersionInSnapshot(region, id, stamp.getRegionVersion(), false)) {
          if (TXStateProxy.LOG_FINEST) {
            logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
                + region.getFullPath() + " RegionEntry(" + entry + ") with version " + stamp
                .getRegionVersion() + " id: " + id + " , returning true.");
          }
          return true;
        }
      }
      if (TXStateProxy.LOG_FINE) {
        logger.info(LocalizedStrings.DEBUG, "getLocalEntry: for region "
            + region.getFullPath() + " RegionEntry(" + entry + ") with version " + stamp
            .getRegionVersion() + " id: " + id + " , returning false.");
      }
      return false;
    }
    return true;
  }

  public Map<String, Map<VersionSource, RegionVersionHolder>> getCurrentSnapshot() {
    return snapshot;
  }


  /**
   * This method is only for test purpose to check the current Rvv
   */
  public Map<String, Map<VersionSource,RegionVersionHolder>> getCurrentRvvSnapShot() {

    if (snapshot != null) {
      return snapshot;
    }
    return null;
  }


  public final boolean isEmpty() {
    return this.regions.isEmpty();
  }

  // Methods of TxState Proxy ... Neeraj

  public boolean isJTA() {
    return this.proxy.isJTA();
  }

  public TXManagerImpl getTxMgr() {
    return this.txManager;
  }

  @Override
  public Iterator<?> getLocalEntriesIterator(Set<Integer> bucketSet,
      boolean primaryOnly, boolean forUpdate, boolean includeValues,
      LocalRegion currRegion, boolean fetchRemote) {

    // for PR we pass the TX along so its iterator can itself invoke
    // getLocalEntry with correct BucketRegion
    //TODO: Suranjan ignoring fetchRemote for now.
    if (currRegion.getPartitionAttributes() != null) {
      return ((PartitionedRegion)currRegion).localEntriesIterator(bucketSet,
          primaryOnly, forUpdate, includeValues, this);
    }
    else {
      // this will in turn invoke getLocalEntry at each iteration and lookup
      // from local TXState if required
      return new EntriesSet.EntriesIterator(currRegion, false,
          IteratorType.RAW_ENTRIES, this, forUpdate, true, true, true,
          includeValues);
    }
    //throw new IllegalStateException("TXState.getLocalEntriesIterator: "
    //    + "this method is intended to be called only for PRs and no txns");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {
    // Do nothing. Not applicable for transactions.    
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setExecutionSequence(int execSeq) {
    throw new IllegalStateException(
        "setExecutionSeq for TXState should never be called: seqId=" + execSeq);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getExecutionSequence() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public boolean isSnapshot() {
    return getLockingPolicy() == LockingPolicy.SNAPSHOT;
  }

  @Override
  public void recordVersionForSnapshot(Object member, long version, Region region) {
    queue.add(new VersionInformation(member, version, region));
    Boolean wasPresent = writeRegions.putIfAbsent(region, true);
    if (wasPresent == null) {
      if (region instanceof BucketRegion) {
        BucketRegion br = (BucketRegion) region;
        br.takeSnapshotGIIReadLock();
      }
    }
  }

  class VersionInformation {
    Object member;
    long version;
    Region region;
    public VersionInformation(Object member, long version, Region reg){
      this.member = member;
      this.version = version;
      this.region = reg;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (obj == this)
        return true;
      if (!(obj instanceof VersionInformation)) {
        return false;
      }

      if (this.member == ((VersionInformation)obj).member && (this.version == (
          (VersionInformation)obj).version) &&
          this.region == ((VersionInformation)obj).region) {
        return true;
      }

      return false;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      sb.append("Member : " + member);
      sb.append(",version : " + version);
      sb.append(",region : " + region);
      return sb.toString();
    }
  }

  public void addCommittedRegionEntryReference(Object re, RegionEntry newRe, LocalRegion region) {
    TXRegionState txrs = writeRegion(region);
    txrs.lock();
    try {
      txrs.addSnapshotRegionEntry(re, newRe);
    } finally {
      txrs.unlock();
    }
  }
}
