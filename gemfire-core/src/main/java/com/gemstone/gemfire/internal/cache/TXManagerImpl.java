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

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.transaction.Transaction;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.TXManagerCancelledException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.OrderedMembershipListener;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.SystemTimer.SystemTimerTask;
import com.gemstone.gemfire.internal.cache.TXRemoteCommitMessage.CommitResponse;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantReadWriteLock;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
import com.gemstone.gemfire.internal.concurrent.MapCallback;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * <p>
 * The internal implementation of the {@link CacheTransactionManager} interface
 * returned by {@link GemFireCacheImpl#getCacheTransactionManager}. Internal
 * operations.
 * 
 * </code>TransactionListener</code> invocation, Region synchronization,
 * transaction statistics and transaction logging are handled here.
 * 
 * @author Mitch Thomas
 * @since 4.0
 * 
 * @see CacheTransactionManager
 */
public final class TXManagerImpl implements CacheTransactionManager,
    OrderedMembershipListener {

  public boolean testRollBack = false;
  // Thread specific context container
  private static final ThreadLocal<TXContext> txContext =
    new ThreadLocal<TXContext>();

  private static final WeakHashMap<TXContext, Boolean> jtaContexts =
    new WeakHashMap<TXContext, Boolean>();

  /**
   * Avoid doing the potentially expensive lock owner search in case of
   * conflicts by {@link #searchLockOwner}. Note that this is disabled by
   * default for log-level > info.
   */
  public static final boolean SKIP_LOCKOWNER_SEARCH = Boolean
      .getBoolean("gemfire.SKIP_LOCKOWNER_SEARCH");

  private final DM dm;
  private final GemFireCacheImpl cache;

  private final CachePerfStats cachePerfStats;
  private final LogWriterI18n logWriter;

  private static final TransactionListener[] EMPTY_LISTENERS =
    new TransactionListener[0];

  // TODO: TX: merge: temporarily added for compilation; this is not a valid
  // value and client TX code should be updated to deal with new TX model
  public static final int NO_TX = -1;

  public static final int PHASE_ONE_COMMIT = 1;
  public static final int PHASE_TWO_COMMIT = 2;
  public static final int FULL_COMMIT = 3;

  private final ArrayList<TransactionListener> txListeners =
    new ArrayList<TransactionListener>(8);
  private TransactionWriter writer = null;

  private TransactionObserver txObserver;

  private boolean closed = false;

  private final CustomEntryConcurrentHashMap<TXId, TXStateProxy> hostedTXStates;

  private static final ConcurrentTHashSet<TXContext> hostedTXContexts =
      new ConcurrentTHashSet<>(16, 128);

  private final ConcurrentHashMap<TXId, TXStateInterface> suspendedTXs;

  public static final int TXMAP_CONCURRENCY = Math.max(Runtime.getRuntime()
      .availableProcessors() * 8, 64);

  /**
   * Maintains the set of recently finished transactions. At some point this
   * will overflow to disk and hold a large amount of history though currently
   * this hold only last few seconds (current default 60).
   * 
   * @author swale
   * @since Helios
   */
  static final class TXFinishedMap {

    /**
     * Encapsulates information related to commit or abort of a transaction for
     * some duration.
     * <p>
     * Implements {@link HashEntry} so that it can be directly used as the map
     * entry for {@link CustomEntryConcurrentHashMap} and avoid creating
     * separate objects for each map entry.
     */
    static final class TXFinished implements HashEntry<Object, TXFinished> {
      final long txMemberId;
      final int txUniqId;
      final boolean isCommit;
      final long finishTime;
      TXFinished next;
      HashEntry<Object, TXFinished> nextMapEntry;

      TXFinished(long memberId, int uniqId, boolean commit, long finishTime) {
        this.txMemberId = memberId;
        this.txUniqId = uniqId;
        this.isCommit = commit;
        this.finishTime = finishTime;
      }

      TXFinished(TXId txId, boolean commit, long finishTime) {
        this(txId.getMemberId(), txId.getUniqId(), commit, finishTime);
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final TXFinished getKey() {
        return this;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final TXFinished getKeyCopy() {
        return this;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final boolean isKeyEqual(final Object k) {
        if (k != this) {
          if (k instanceof TXFinished) {
            final TXFinished other = (TXFinished)k;
            return this.txUniqId == other.txUniqId
                && this.txMemberId == other.txMemberId;
          }
          else {
            final TXId other = (TXId)k;
            return this.txUniqId == other.uniqId
                && this.txMemberId == other.memberId;
          }
        }
        else {
          return true;
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final TXFinished getMapValue() {
        return this;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void setMapValue(TXFinished newValue) {
        // nothing to be done
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final int getEntryHash() {
        return TXId.hashCode(this.txMemberId, this.txUniqId);
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void setNextEntry(HashEntry<Object, TXFinished> n) {
        this.nextMapEntry = n;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public HashEntry<Object, TXFinished> getNextEntry() {
        return this.nextMapEntry;
      }

      @Override
      public int hashCode() {
        return TXId.hashCode(this.txMemberId, this.txUniqId);
      }

      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        final long currentTime = System.currentTimeMillis();
        sb.append("TXFinished[").append(this.txMemberId).append(':')
            .append(this.txUniqId).append("] commit=" + this.isCommit)
            .append(" elapsed=" + (currentTime - this.finishTime));
        final TXFinished next = this.next;
        if (next != null) {
          sb.append(" next=").append(next.txMemberId).append(':')
              .append(next.txUniqId);
        }
        return sb.toString();
      }
    }

    /**
     * factory for {@link TXFinished} used by
     * {@link CustomEntryConcurrentHashMap} to create {@link TXFinished} entries
     */
    static final class TXFinishedCreator implements
        CustomEntryConcurrentHashMap.HashEntryCreator<Object, TXFinished> {

      @Override
      public TXFinished newEntry(final Object key, final int hash,
          final HashEntry<Object, TXFinished> next, final TXFinished value) {
        // everything already created, just update the next pointer
        value.nextMapEntry = next;
        return value;
      }

      @Override
      public int keyHashCode(final Object key, final boolean compareValues) {
        return key.hashCode();
      }
    };

    final NonReentrantReadWriteLock mapLock;
    final CustomEntryConcurrentHashMap<Object, TXFinished> finishedMap;
    transient TXFinished tail;
    public static final int TXFINISH_KEEP_DURATION;
    public static final int TXFINISH_KEEP_MIN_DURATION;

    static {
      final SystemProperties sysProps = SystemProperties.getServerInstance();
      TXFINISH_KEEP_DURATION = sysProps.getInteger("tx-keepstatus-secs", 60);
      if (TXFINISH_KEEP_DURATION < 1) {
        throw new IllegalArgumentException("Invalid value "
            + TXFINISH_KEEP_DURATION + " for "
            + sysProps.getSystemPropertyNamePrefix() + "tx-keepstatus-secs");
      }
      double minDuration = Math.round((double)TXFINISH_KEEP_DURATION * 92.0);
      TXFINISH_KEEP_MIN_DURATION = (int)minDuration;
    }

    TXFinishedMap(InternalDistributedSystem sys, CancelCriterion cancel) {
      this.mapLock = new NonReentrantReadWriteLock(sys, cancel);
      this.finishedMap = new CustomEntryConcurrentHashMap<Object, TXFinished>(
          128, 0.75f, TXMAP_CONCURRENCY, false, new TXFinishedCreator());
      this.tail = new TXFinished(0, 0, false, 0);
      this.tail.next = this.tail;
    }

    void txFinished(TXId txId, boolean commit) {
      final long currentTime = System.currentTimeMillis();
      TXFinished finished = new TXFinished(txId, commit, currentTime);
      ArrayList<TXFinished> removedItems = null;

      if (this.finishedMap.putIfAbsent(finished, finished) != null) {
        return;
      }

      int numRemoved = 0;
      this.mapLock.attemptWriteLock(-1);
      try {
        // first add the new item at tail
        finished.next = this.tail.next;
        this.tail.next = finished;
        this.tail = finished;

        // next check if items at start need to be removed to reduce the size
        final TXFinished head = this.tail.next;
        TXFinished next;
        final long txDuration = TXFINISH_KEEP_DURATION * 1000L;
        while ((next = head.next) != head) {
          if ((currentTime - next.finishTime) >= txDuration) {
            // remove head next
            head.next = next.next;
            next.next = null;
            if (removedItems == null) {
              removedItems = new ArrayList<TXFinished>();
            }
            removedItems.add(next);
            numRemoved++;
          }
          else if (numRemoved == 0) {
            break;
          }
          else {
            // try to remove at least some items if we removed even one
            // so that this thread does bulk of removal work
            final int minRemovals = (TXMAP_CONCURRENCY << 1);
            final long txMinDuration = TXFINISH_KEEP_MIN_DURATION * 1000L;
            while (numRemoved < minRemovals
                && ((currentTime - next.finishTime) >= txMinDuration)
                && (next = head.next) != head) {
              // remove head next
              head.next = next.next;
              next.next = null;
              removedItems.add(next);
              numRemoved++;
            }
            break;
          }
        }
      } finally {
        this.mapLock.releaseWriteLock();
      }

      if (numRemoved > 0) {
        while (--numRemoved >= 0) {
          this.finishedMap.remove(removedItems.get(numRemoved));
        }
      }
    }

    /**
     * Returns Boolean.TRUE if given TX has been recorded as committed,
     * Boolean.FALSE if it has been rolled back and null if no information
     * available for given TX.
     */
    Object isTXCommitted(TXId txId) {
      TXFinished result = this.finishedMap.get(txId);
      return result != null ? Boolean.valueOf(result.isCommit) : null;
    }

    /**
     * Get a relative ordering for committed/rolled back transactions. If
     * transaction has been rolled back then it returns negative of the ordering
     * to indicate so.
     */
    TObjectIntHashMap getTXCommitOrders(Collection<TXId> txIds) {
      final HashMap<TXId, TXId> txIdSet = new HashMap<TXId, TXId>(txIds.size());
      final TObjectIntHashMap txIdOrders = new TObjectIntHashMap();
      for (TXId txId : txIds) {
        txIdSet.put(txId, txId);
      }
      final TXId lookupId = new TXId();
      this.mapLock.attemptReadLock(-1);
      try {
        final TXFinished head = this.tail.next;
        TXFinished next = head;
        TXId txId;
        int pos = 1;
        while ((next = next.next) != head) {
          lookupId.memberId = next.txMemberId;
          lookupId.uniqId = next.txUniqId;
          if ((txId = txIdSet.get(lookupId)) != null) {
            txIdOrders.put(txId, next.isCommit ? pos : -pos);
            pos++;
          }
        }
        return txIdOrders;
      } finally {
        this.mapLock.releaseReadLock();
      }
    }

    boolean hasTransactions() {
      return !this.finishedMap.isEmpty();
    }
  }

  final TXFinishedMap finishedTXStates;

  /**
   * this map keeps track of all the threads that are waiting in
   * {@link #tryResume(TransactionId, long, TimeUnit)} for a particular
   * transactionId
   */
  private final ConcurrentHashMap<TransactionId, Queue<Thread>> waitMap;

  /**
   * map to track the scheduled expiry tasks of suspended transactions.
   */
  private final ConcurrentHashMap<TransactionId, SystemTimerTask> expiryTasks;

  /**
   * the time in minutes after which any suspended transaction are rolled back.
   * default is 30 minutes
   */
  private volatile long suspendedTXTimeout = Long.getLong(
      "gemfire.suspendedTxTimeout", 30);

  // Some commonly used EnumSet of TransactionFlag
  public static final EnumSet<TransactionFlag> WAITING_MODE = EnumSet
      .of(TransactionFlag.WAITING_MODE);
  public static final EnumSet<TransactionFlag> DISABLE_BATCHING = EnumSet
      .of(TransactionFlag.DISABLE_BATCHING);
  public static final EnumSet<TransactionFlag> SYNC_COMMITS = EnumSet
      .of(TransactionFlag.SYNC_COMMITS);

  /**
   * A flag to allow persistent transactions. public for testing.
   */
  public static boolean ALLOW_PERSISTENT_TRANSACTIONS = Boolean
      .getBoolean("gemfire.ALLOW_PERSISTENT_TRANSACTIONS");

  public static final int DUMP_STRING_LIMIT = 1000000;

  private final TXStateProxy newTXStateProxy(final TXId txId,
      final IsolationLevel isolationLevel, final boolean isJTA,
      final EnumSet<TransactionFlag> flags, final boolean initLocalTXState,
      final MapResult result) {
    return GemFireCacheImpl.FactoryStatics.txStateProxyFactory.newTXStateProxy(
        this, txId, isolationLevel, isJTA, flags, initLocalTXState);
  }

  /**
   * Creator for {@link TXStateProxy} on coordinator.
   */
  private final MapCallback<TXId, TXStateProxy,
      IsolationLevel, EnumSet<TransactionFlag>> txStateProxyCreator =
          new MapCallbackAdapter<TXId,
              TXStateProxy, IsolationLevel, EnumSet<TransactionFlag>>() {

    @Override
    public final TXStateProxy newValue(final TXId txId,
        final IsolationLevel isolationLevel,
        final EnumSet<TransactionFlag> txFlags, final MapResult result) {
      return newTXStateProxy(txId, isolationLevel, false, txFlags, false,
          result);
    }
  };

  /**
   * Creator for {@link TXStateProxy} for JTA on coordinator.
   */
  private final MapCallback<TXId, TXStateProxy,
      IsolationLevel, EnumSet<TransactionFlag>> txStateJTACreator =
          new MapCallbackAdapter<TXId,
              TXStateProxy, IsolationLevel, EnumSet<TransactionFlag>>() {

    @Override
    public final TXStateProxy newValue(final TXId txId,
        final IsolationLevel isolationLevel,
        final EnumSet<TransactionFlag> txFlags, final MapResult result) {
      return newTXStateProxy(txId, isolationLevel, true, txFlags, false,
          result);
    }
  };

  /**
   * Creator for {@link TXStateProxy} on remote cohort.
   */
  private final MapCallback<TXId, TXStateProxy, LockingPolicy, Boolean>
      txStateCreator = new MapCallbackAdapter<TXId, TXStateProxy,
          LockingPolicy, Boolean>() {

    @Override
    public final TXStateProxy newValue(final TXId txId,
        final LockingPolicy lockingPolicy,
        final Boolean checkFinishTX, final MapResult result) {
      // first check if this TX has already been marked finished
      if (finishedTXStates.isTXCommitted(txId) != null) {
        if (checkFinishTX.booleanValue()) {
          throw new IllegalTransactionStateException(
              LocalizedStrings.TRANSACTION_0_IS_NO_LONGER_ACTIVE
                  .toLocalizedString(txId.toString()));
        }
        else {
          // don't put it in hosted map in any case
          result.setNewValueCreated(false);
        }
      }
      return newTXStateProxy(txId, lockingPolicy.getIsolationLevel(), false,
          lockingPolicy.isFailFast() ? null : WAITING_MODE, true, result);
    }

    @Override
    public void oldValueRead(final TXStateProxy proxy) {
      proxy.incRefCount();
    }
  };

  /**
   * Remove callback for {@link TXStateProxy} on remote cohort.
   */
  private final MapCallback<TXId, TXStateProxy, Object, Boolean> txStateRemove =
      new MapCallbackAdapter<TXId, TXStateProxy, Object, Boolean>() {

    @Override
    public final Object removeValue(final Object key, Object value,
        final TXStateProxy txProxy, Object cbArg, final Boolean commit) {
      final TXId txId = txProxy.txId;
      if (TXStateProxy.LOG_FINE) {
        getLogger().info(LocalizedStrings.DEBUG,
            txId.shortToString() + ": removing from hosted list with commit="
                + commit);
      }
      // always remove from map at this point; callback is only to atomically
      // add to finishedTXStates in postRemove
      return null;
    }

    @Override
    public final void postRemove(final Object key, Object value,
        final TXStateProxy txProxy, final Object cbArg, final Boolean commit) {
      // add to finished TX map
      if (commit != null) {
        finishedTXStates.txFinished((TXId)key, commit.booleanValue());
      }
    }
  };

  /**
   * Transaction context stored in the {@link TXManagerImpl#txContext}
   * ThreadLocal. This combines both GFE {@link TXStateInterface} and JTA
   * {@link Transaction} to avoid two ThreadLocal lookups in every operation.
   * 
   * @author swale
   * @since 7.0
   */
  public static final class TXContext {

    private TXStateInterface txState;

    private TXStateInterface snapshotTXState;

    // below field is volatile since ReplyProcessor for this will remove itself
    // from TXContext when its done
    private final AtomicReference<CommitResponse> pendingCommit =
      new AtomicReference<CommitResponse>();

    /**
     * This pending TXId is set on the remote node for further relay to other
     * nodes (e.g. nested function execution).
     */
    private final AtomicReference<TXId> pendingTXId =
        new AtomicReference<TXId>();

    private transient TXStateProxy.MemberToGIIRegions commitRecipients;

    // transient flags for the current execution

    /** wait for phase2 commit messages */
    private transient boolean waitForPhase2Commit;
    /** force batching of messages in current execution context */
    transient boolean forceBatching;
    /** set to true if the current message uses TXStateProxy for distribution */
    transient boolean msgUsesTXProxy;

    /**
     * Below is volatile since cleanup by timeout expiry will happen in a
     * different thread.
     */
    private volatile Transaction jtaTX;

    private TXContext() {
    }

    private static TXContext newContext() {
      final TXContext context = new TXContext();
      txContext.set(context);
      hostedTXContexts.add(context);
      return context;
    }

    public final TXStateInterface getTXState() {
      return this.txState;
    }

    public final TXStateInterface getSnapshotTXState() {
      return this.snapshotTXState;
    }

    public final TXId getTXId() {
      if (this.txState != null) {
        return this.txState.getTransactionId();
      }
      return null;
    }

    public final void setTXState(final TXStateInterface tx) {
      assert tx != null;
      this.txState = tx;
      this.commitRecipients = null;
      this.waitForPhase2Commit = false;
    }

    public final void setSnapshotTXState(final TXStateInterface tx) {
      this.snapshotTXState = tx;
      this.commitRecipients = null;
      this.waitForPhase2Commit = false;
    }

    public final void clearTXState() {
      this.txState = null;
      this.commitRecipients = null;
      this.waitForPhase2Commit = false;
      this.msgUsesTXProxy = false;
    }

    public final void clearTXStateAll() {
      if (TXStateProxy.LOG_FINE) {
        LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null) {
          logger.info(LocalizedStrings.DEBUG, "clearAll for " + toString());
        }
      }
      clearTXState();
      this.snapshotTXState = null;
    }

    /** clear the TXContext and remove from global list when thread closes */
    public final void threadClose() {
      rollback(this.txState);
      rollback(this.snapshotTXState);
      clearTXStateAll();
      hostedTXContexts.remove(this);
    }

    private void rollback(TXStateInterface tx) {
      if (tx != null && !tx.isClosed()) {
        // skip if cache is closing
        GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache != null && !cache.isClosing) {
          try {
            tx.rollback(null);
          } catch (Throwable ignored) {
          }
        }
      }
    }

    public final Transaction getJTA() {
      return this.jtaTX;
    }

    public final void setJTA(final Transaction tx) {
      assert tx != null;
      if (this.jtaTX == null) {
        synchronized (jtaContexts) {
          jtaContexts.put(this, Boolean.TRUE);
        }
      }
      this.jtaTX = tx;
    }

    public final void clearJTA() {
      clearJTAOnly();
      synchronized (jtaContexts) {
        jtaContexts.remove(this);
      }
    }

    public final void clearJTAOnly() {
      this.jtaTX = null;
    }

    public final void waitForPendingCommit() {
      final CommitResponse response = this.pendingCommit.get();
      if (response != null) {
        try {
          response.waitForCommitReplies();
        } catch (TransactionException te) {
          final LogWriterI18n logger = GemFireCacheImpl.getExisting()
              .getLoggerI18n();
          // at this point just log any exceptions and move on
          if (logger.warningEnabled()) {
            logger.warning(
                LocalizedStrings.TXRemoteCommitMessage_UNEXPECTED_EXCEPTION,
                new Object[] { response, te }, te);
          }
        } catch (ReplyException re) {
          final LogWriterI18n logger = GemFireCacheImpl.getExisting()
              .getLoggerI18n();
          // at this point just log any exceptions and move on
          if (logger.infoEnabled()) {
            logger.info(
                LocalizedStrings.TXRemoteCommitMessage_UNEXPECTED_EXCEPTION,
                new Object[] { response, re }, re);
          }
        } finally {
          // may need to cleanup membership listener in case
          // waitForCommitReplies returned early with stillWaiting() as false
          // since we invoked startWait() explicitly earlier outside
          // waitForReplies of ReplyProcessor21 (see #47198 for similar case)
          response.endWait();
        }
        clearPendingCommit(response);
      }
    }

    public final void setPendingCommit(final CommitResponse response) {
      assert response != null;
      this.pendingCommit.set(response);
    }

    public final void clearPendingCommit(final CommitResponse response) {
      assert response != null;
      if (TXStateProxy.LOG_FINE) {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache != null) {
          cache.getLogger().info(
              "TXContext received pending commit response for clear "
                  + response + ", context: " + toString());
        }
      }
      this.pendingCommit.compareAndSet(response, null);
      final TXId origPendingTXId = this.pendingTXId.get();
      if (origPendingTXId != null
          && origPendingTXId.equals(response.getPendingTXId())) {
        this.pendingTXId.compareAndSet(origPendingTXId, null);
      }
    }

    public final void clearPendingTXId() {
      this.pendingTXId.set(null);
    }

    public final TXId getPendingTXId() {
      final CommitResponse response = this.pendingCommit.get();
      if (response != null) {
        return response.getPendingTXId();
      }
      return this.pendingTXId.get();
    }

    public final void setPendingTXId(final TXId txId) {
      this.pendingTXId.set(txId);
    }

    public final void setCommitRecipients(
        final TXStateProxy.MemberToGIIRegions recipients) {
      this.commitRecipients = recipients;
      this.waitForPhase2Commit = false;
    }

    public final TXStateProxy.MemberToGIIRegions getCommitRecipients() {
      return this.commitRecipients;
    }

    public final void setWaitForPhase2Commit() {
      this.waitForPhase2Commit = true;
    }

    public final boolean waitForPhase2Commit() {
      return this.waitForPhase2Commit;
    }

    final boolean remoteBatching(final boolean batching) {
      final boolean prevBatching = this.forceBatching;
      if (TXStateProxy.LOG_FINE) {
        GemFireCacheImpl.getExisting().getLogger().info("TXContext: setting "
            + "batching=" + batching + " previous=" + prevBatching);
      }
      if (batching != prevBatching) {
        this.forceBatching = batching;
      }
      return prevBatching;
    }

    final void setMessageUsesTXProxy(final boolean useTXProxy) {
      if (TXStateProxy.LOG_FINE) {
        GemFireCacheImpl.getExisting().getLogger().info("TXContext: setting "
            + "msgUsesTXProxy=" + useTXProxy);
      }
      this.msgUsesTXProxy = useTXProxy;
    }

    @Override
    public String toString() {
      return "TXContext@" + Integer.toHexString(System.identityHashCode(this))
          + "[txState=" + this.txState + ",pendingCommitProcessor="
          + this.pendingCommit.get() + ",pendingTXId=" + this.pendingTXId.get()
          + ",forceBatching=" + this.forceBatching + ",msgUsesTXProxy="
          + this.msgUsesTXProxy + ']';
    }
  }

  /**
   * Constructor that implements the {@link CacheTransactionManager} interface.
   * Only only one instance per {@link com.gemstone.gemfire.cache.Cache}
   */
  public TXManagerImpl(CachePerfStats cachePerfStats, LogWriterI18n logWriter,
      GemFireCacheImpl cache) {
    this.cache = cache;
    this.dm = cache.getDistributedSystem().getDistributionManager();
    this.cachePerfStats = cachePerfStats;
    this.logWriter = logWriter;
    this.hostedTXStates = new CustomEntryConcurrentHashMap<>(
        128, CustomEntryConcurrentHashMap.DEFAULT_LOAD_FACTOR,
        TXMAP_CONCURRENCY);
    this.suspendedTXs = new ConcurrentHashMap<TXId, TXStateInterface>();
    this.finishedTXStates = new TXFinishedMap(cache.getDistributedSystem(),
        cache.getCancelCriterion());
    this.waitMap = new ConcurrentHashMap<TransactionId, Queue<Thread>>();
    this.expiryTasks = new ConcurrentHashMap<TransactionId, SystemTimerTask>();
  }

  public final GemFireCacheImpl getCache() {
    return this.cache;
  }

  /**
   * Get the TransactionWriter for the cache
   * 
   * @return the current TransactionWriter
   * @see TransactionWriter
   */
  public final TransactionWriter getWriter() {
    return writer;
  }

  public final void setWriter(TransactionWriter writer) {
    this.writer = writer;
  }

  public final TransactionListener getListener() {
    synchronized (this.txListeners) {
      if (this.txListeners.isEmpty()) {
        return null;
      }
      else if (this.txListeners.size() == 1) {
        return this.txListeners.get(0);
      }
      else {
        throw new IllegalTransactionStateException(LocalizedStrings
            .TXManagerImpl_MORE_THAN_ONE_TRANSACTION_LISTENER_EXISTS
                .toLocalizedString());
      }
    }
  }

  public TransactionListener[] getListeners() {
    synchronized (this.txListeners) {
      int size = this.txListeners.size();
      if (size == 0) {
        return EMPTY_LISTENERS;
      }
      else {
        TransactionListener[] result = new TransactionListener[size];
        this.txListeners.toArray(result);
        return result;
      }
    }
  }

  public TransactionListener setListener(TransactionListener newListener) {
    synchronized (this.txListeners) {
      TransactionListener result = getListener();
      this.txListeners.clear();
      if (newListener != null) {
        this.txListeners.add(newListener);
      }
      if (result != null) {
        closeListener(result);
      }
      return result;
    }
  }

  public void addListener(TransactionListener aListener) {
    if (aListener == null) {
      throw new IllegalArgumentException(
          LocalizedStrings.TXManagerImpl_ADDLISTENER_PARAMETER_WAS_NULL
              .toLocalizedString());
    }
    synchronized (this.txListeners) {
      if (!this.txListeners.contains(aListener)) {
        this.txListeners.add(aListener);
      }
    }
  }

  public void removeListener(TransactionListener aListener) {
    if (aListener == null) {
      throw new IllegalArgumentException(
          LocalizedStrings.TXManagerImpl_REMOVELISTENER_PARAMETER_WAS_NULL
              .toLocalizedString());
    }
    synchronized (this.txListeners) {
      if (this.txListeners.remove(aListener)) {
        closeListener(aListener);
      }
    }
  }

  public void initListeners(TransactionListener[] newListeners) {
    synchronized (this.txListeners) {
      if (!this.txListeners.isEmpty()) {
        for (TransactionListener listener : this.txListeners) {
          closeListener(listener);
        }
        this.txListeners.clear();
      }
      if (newListeners != null && newListeners.length > 0) {
        final List<TransactionListener> nl = Arrays.asList(newListeners);
        if (nl.contains(null)) {
          throw new IllegalArgumentException(LocalizedStrings
              .TXManagerImpl_INITLISTENERS_PARAMETER_HAD_A_NULL_ELEMENT
                  .toLocalizedString());
        }
        this.txListeners.addAll(nl);
      }
    }
  }

  public final void setObserver(TransactionObserver observer) {
    synchronized (this.txListeners) {
      this.txObserver = observer;
    }
  }

  public final TransactionObserver getObserver() {
    return this.txObserver;
  }

  final CachePerfStats getCachePerfStats() {
    return this.cachePerfStats;
  }

  /**
   * Build a new {@link TXId}, use it as part of the transaction state and
   * associate with the current thread using a {@link ThreadLocal}.
   */
  public void begin() {
    beginTX(getOrCreateTXContext(), IsolationLevel.DEFAULT, null, null);
  }

  /**
   * @see CacheTransactionManager#begin(IsolationLevel, EnumSet)
   */
  public final void begin(final IsolationLevel isolationLevel,
      final EnumSet<TransactionFlag> txFlags) {
    beginTX(getOrCreateTXContext(), isolationLevel, txFlags, null);
  }

  public final TXStateProxy beginTX(final TXManagerImpl.TXContext context,
      final IsolationLevel isolationLevel,
      final EnumSet<TransactionFlag> txFlags, TXId nextTxID) {
    checkClosed();
    TXId txId = context.getTXId();
    if (txId != null) {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TXManagerImpl_TRANSACTION_0_ALREADY_IN_PROGRESS
              .toLocalizedString(txId));
    }
    if (nextTxID != null) {
      txId = nextTxID;
    }
    else {
      txId = TXId.newTXId(this.cache);
    }

    final TXStateProxy txState = this.hostedTXStates.create(txId,
        txStateProxyCreator, isolationLevel, txFlags, false);
    context.setTXState(txState);
    // For snapshot isolation, create tx state at the beginning
    if (txState.isSnapshot()) {
      txState.getTXStateForRead();
      context.setSnapshotTXState(txState);
    }

    return txState;
  }

  public TXId getNewTXId() {
    return TXId.newTXId(this.cache);
  }

  public final TXStateProxy resumeTX(final TXManagerImpl.TXContext context,
      final IsolationLevel isolationLevel,
      final EnumSet<TransactionFlag> txFlags, TXId txid) {
    checkClosed();
    TXId txId = context.getTXId();
    context.remoteBatching(false);
    if (txId != null) {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TXManagerImpl_TRANSACTION_0_ALREADY_IN_PROGRESS
              .toLocalizedString(txId));
    }
    txId = txid;
    // Do we have a proxy here
    TXStateProxy txState = this.hostedTXStates.get(txId);
    if (txState != null) {
      return txState;
    }
    txState = this.hostedTXStates.create(txId,
        txStateProxyCreator, isolationLevel, txFlags, false);
    // context.setTXState(txState);
    return txState;
  }
  
  /** Build a new {@link TXId}, use it as part of the transaction
   * state and associate with the current thread using a {@link
   * ThreadLocal}. Flag the transaction to be enlisted with a JTA
   * Transaction.  Should only be called in a context where we know
   * there is no existing transaction.
   */
  public TXStateProxy beginJTA() {
    checkClosed();
    final TXContext context = getOrCreateTXContext();
    final TXId txId = TXId.newTXId(this.cache);
    final TXStateProxy txState = this.hostedTXStates.create(txId,
        txStateJTACreator, IsolationLevel.DEFAULT, null, false);

    context.setTXState(txState);
    return txState;
  }

  /** Complete the transaction associated with the current
   *  thread. When this method completes, the thread is no longer
   *  associated with a transaction.
   *
   */
  public void commit() throws TransactionException {
    final TXContext context = txContext.get();
    final TXStateInterface tx = context != null ? context.getTXState() : null;
    commit(tx, null, FULL_COMMIT, context, false);
  }

  public final TXManagerImpl.TXContext commit(
      final TXStateInterface tx, final Object callbackArg,
      final int commitPhase, TXContext ctx, boolean isRemoteCommit)
      throws TransactionException {
    checkClosed();

    if (tx == null) {
      throw new IllegalTransactionStateException(LocalizedStrings
          .TXManagerImpl_THREAD_DOES_NOT_HAVE_AN_ACTIVE_TRANSACTION
            .toLocalizedString());
    }

    if (tx.isJTA()) {
      throw new IllegalTransactionStateException(LocalizedStrings
        .TXManagerImpl_CAN_NOT_COMMIT_THIS_TRANSACTION_BECAUSE_IT_IS_ENLISTED_WITH_A_JTA_TRANSACTION_USE_THE_JTA_MANAGER_TO_PERFORM_THE_COMMIT
          .toLocalizedString());
    }

    final long opStart = CachePerfStats.getStatTime();
    final long lifeTime = opStart - tx.getBeginTime();
    try {
      switch (commitPhase) {
        case FULL_COMMIT:
          tx.commit(callbackArg);
          break;

        case PHASE_ONE_COMMIT:
          ctx = tx.getProxy().commitPhase1(callbackArg);
          break;

        case PHASE_TWO_COMMIT:
          if (ctx == null) {
            ctx = currentTXContext();
          }
          tx.getProxy().commitPhase2(ctx, callbackArg);
          break;

        default:
          throw new IllegalTransactionStateException("unknown commit phase: "
              + commitPhase);
      }
    } catch (TransactionException ex) {
      noteCommitFailure(opStart, lifeTime, tx, isRemoteCommit);
      throw ex;
    }
    // TODO: TX: how to account for time in phase one
    if (commitPhase != PHASE_ONE_COMMIT) {
      noteCommitSuccess(opStart, lifeTime, tx, isRemoteCommit);
    }
    if (ctx == null) {
      ctx = currentTXContext();
    }
    if (ctx != null) {
      // clear the snapshot TXState
      ctx.setSnapshotTXState(null);
    }
    return ctx;
  }

  final void noteCommitFailure(final long opStart, final long lifeTime,
      TXStateInterface tx, final boolean isRemoteCommit) {
    long opEnd = CachePerfStats.getStatTime();
    final TXState txState = tx.getLocalTXState();
    if (txState != null) {
      tx = txState;
    }
    if(isRemoteCommit) {
      this.cachePerfStats.txRemoteFailure(opEnd - opStart, lifeTime, tx.getChanges());
    }
    else {
      this.cachePerfStats.txFailure(opEnd - opStart, lifeTime, tx.getChanges());
    }
    TransactionListener[] listeners = getListeners();
    boolean hasListeners = false;
    if (tx.isFireCallbacks() && listeners.length > 0) {
      final TXEvent e = tx.getEvent();
      try {
      for (int i = 0; i < listeners.length; i++) {
        try {
          listeners[i].afterFailedCommit(e);
          hasListeners = true;
        }
        catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
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
          getLogger().error(LocalizedStrings
              .TXManagerImpl_EXCEPTION_OCCURRED_IN_TRANSACTIONLISTENER, t);
        }
      }
      } finally {
        e.release();
      }
    }
    // don't cleanup for GemFireXD since that will invoke the rollback separately
    final TXStateProxy txProxy = tx.getProxy();
    if (!txProxy.isGFXD) {
      txProxy.cleanupCachedLocalState(hasListeners);
    }
  }

  final void noteCommitSuccess(final long opStart, final long lifeTime,
      TXStateInterface tx, final boolean isRemoteCommit) {
    long opEnd = CachePerfStats.getStatTime();
    final TXState txState = tx.getLocalTXState();
    if (txState != null) {
      tx = txState;
    }
    if(isRemoteCommit) {
      this.cachePerfStats.txRemoteSuccess(opEnd - opStart, lifeTime, tx.getChanges());
    }
    else {
      this.cachePerfStats.txSuccess(opEnd - opStart, lifeTime, tx.getChanges());
    }
    boolean hasListeners = false;
    TransactionListener[] listeners = getListeners();
    if (tx.isFireCallbacks() && listeners.length > 0) {
      final TXEvent e = tx.getEvent();
      try {
      for (final TransactionListener listener : listeners) {
        try {
          listener.afterCommit(e);
          hasListeners = true;
        } 
        catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
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
          getLogger().error(LocalizedStrings
              .TXManagerImpl_EXCEPTION_OCCURRED_IN_TRANSACTIONLISTENER, t);
        }
      }
      } finally {
        e.release();
      }
    }
    tx.getProxy().cleanupCachedLocalState(hasListeners);
  }

  /** Roll back the transaction associated with the current
   *  thread. When this method completes, the thread is no longer
   *  associated with a transaction.
   */
  public final void rollback() {
    rollback(getTXState(), null, false);
  }

  /**
   * Roll back the transaction associated with the current thread. When this
   * method completes, the thread is no longer associated with a transaction.
   */
  public final void rollback(final TXStateInterface tx, Object callbackArg,
      final boolean isRemoteRollback) {
    checkClosed();
    if (tx == null) {
      throw new IllegalTransactionStateException(LocalizedStrings
          .TXManagerImpl_THREAD_DOES_NOT_HAVE_AN_ACTIVE_TRANSACTION
            .toLocalizedString());
    }

    if (tx.isJTA()) {
      throw new IllegalTransactionStateException(LocalizedStrings
        .TXManagerImpl_CAN_NOT_ROLLBACK_THIS_TRANSACTION_IS_ENLISTED_WITH_A_JTA_TRANSACTION_USE_THE_JTA_MANAGER_TO_PERFORM_THE_ROLLBACK
          .toLocalizedString());
    }

    final long opStart = CachePerfStats.getStatTime();
    final long lifeTime = opStart - tx.getBeginTime();
    TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
    if (context != null) {
      context.clearTXStateAll();
    }
    tx.rollback(callbackArg);
    noteRollbackSuccess(opStart, lifeTime, tx, isRemoteRollback);
  }

  final void noteRollbackSuccess(final long opStart, final long lifeTime,
      TXStateInterface tx, final boolean isRemoteRollback) {
    long opEnd = CachePerfStats.getStatTime();
    final TXState txState = tx.getLocalTXState();
    if (txState != null) {
      tx = txState;
    }
    if (isRemoteRollback) {
      this.cachePerfStats.txRemoteRollback(opEnd - opStart, lifeTime, tx.getChanges());
    }
    else {
      this.cachePerfStats.txRollback(opEnd - opStart, lifeTime, tx.getChanges());
    }
    TransactionListener[] listeners = getListeners();
    boolean hasListeners = false;
    if (tx.isFireCallbacks() && listeners.length > 0) {
      final TXEvent e = tx.getEvent();
      try {
      for (int i = 0; i < listeners.length; i++) {
        try {
          listeners[i].afterRollback(e);
          hasListeners = true;
        } catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
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
          getLogger().error(LocalizedStrings
              .TXManagerImpl_EXCEPTION_OCCURRED_IN_TRANSACTIONLISTENER, t);
        }
      }
      } finally {
        e.release();
      }
    }
    tx.getProxy().cleanupCachedLocalState(hasListeners);
  }

  /**
   * Called from Commit and Rollback to unblock waiting threads
   */
  final void cleanup(TransactionId txId) {
    if (this.waitMap.isEmpty()) {
      return;
    }
    Queue<Thread> waitingThreads = this.waitMap.get(txId);
    if (waitingThreads != null) {
      if (waitingThreads.size() > 0) {
        for (Thread waitingThread : waitingThreads) {
          LockSupport.unpark(waitingThread);
        }
      }
      this.waitMap.remove(txId);
    }
  }

  /** Reports the existance of a Transaction for this thread
   *
   */
  public boolean exists() {
    return null != getCurrentTXState();
  }

  /** Gets the current transaction identifier or null if no transaction exists
   *
   */
  public final TXId getTransactionId() {
    return getCurrentTXId();
  }

  /**
   * Returns the TXStateInterface of the current thread; null if no transaction.
   */
  public final TXStateInterface getTXState() {
    return getCurrentTXState();
  }

  public static final TXContext getOrCreateTXContext() {
    final TXContext context = txContext.get();
    if (context != null) {
      return context;
    }
    return TXContext.newContext();
  }

  final TXContext setTXState(final TXStateInterface tx) {
    // the thread that created the TXStateInterface may be different from the
    // current thread so cannot rely on the context inside the TXStateProxy
    final TXContext context = getOrCreateTXContext();
    setTXState(tx, context);
    return context;
  }

  public final void setTXState(final TXStateInterface tx,
      final TXContext context) {
    if (TXStateProxy.LOG_FINE) {
      getLogger().info(LocalizedStrings.DEBUG,
          "TXContext setting local TXState as " + tx + " in " + context);
    }
    if (tx != null) {
      context.setTXState(tx);
    }
    else {
      context.clearTXState();
    }
  }

  final TXContext clearTXState() {
    final TXContext context = txContext.get();
    if (context != null) {
      context.clearTXState();
      return context;
    }
    return null;
  }

  //See #50072
  public void release() {
    final TXContext selfContext = getOrCreateTXContext();
    TXState tx;
    for (TXStateProxy proxy : getHostedTransactionsInProgress()) {
      if ((tx = proxy.getLocalTXState()) != null) {
        final TXRegionState[] txrs = tx.getTXRegionStatesSnap();
        for (TXRegionState txr : txrs) {
          if (txr != null) {
            UnifiedMap<Object, Object> entryMods = txr.getInternalEntryMap();
            for (Object obj : entryMods.values()) {
              if (obj instanceof TXEntryState) {
                TXEntryState txe = (TXEntryState) obj;
                txe.release();
              }
            }
          }
        }
      }
      if (!proxy.isClosed()) {
        try {
          setTXState(tx, selfContext);
          proxy.rollback(null);
        } catch (Throwable ignored) {
        }
      }
    }
    // clear the hosted TXContexts too
    for (TXContext context : hostedTXContexts) {
      context.clearTXStateAll();
    }
  }

  public void close() {
    if (isClosed()) {
      return;
    }
    this.closed = true;
    TransactionListener[] listeners = getListeners();
    for (TransactionListener listener : listeners) {
      closeListener(listener);
    }
  }

  private void closeListener(TransactionListener tl) {
    try {
      tl.close();
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
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
      getLogger().error(LocalizedStrings
          .TXManagerImpl_EXCEPTION_OCCURRED_IN_TRANSACTIONLISTENER, t);
    }
  }

  /**
   * If the current thread is in a transaction then suspend will cause it to no
   * longer be in a transaction.
   * 
   * @return the state of the transaction or null. Pass this value to
   *         {@link TXManagerImpl#resume} to reactivate the suspended
   *         transaction.
   */
  public final TXStateInterface internalSuspend() {
    final TXContext context = currentTXContext();
    TXStateInterface tx = null;
    if (context != null && (tx = context.getTXState()) != null) {
      context.clearTXState();
    }
    return tx;
  }

  /**
   * Activates the specified transaction on the calling thread.
   * 
   * @param tx
   *          the transaction to activate.
   * @throws IllegalTransactionStateException
   *           if this thread already has an active transaction
   */
  public final void resume(final TXStateInterface tx) {
    if (tx != null) {
      // the TX provided here could be created in another thread so cannot use
      // its TXContext
      final TXContext context = getOrCreateTXContext();
      if (context.getTXState() != null) {
        throw new IllegalTransactionStateException(
            LocalizedStrings.TXManagerImpl_TRANSACTION_0_ALREADY_IN_PROGRESS
                .toLocalizedString(context.getTXId()));
      }
      /* [sumedh] can happen if DiskAccessException is raised during commit
       * on remote node for example
      if (tx instanceof TXState) {
        throw new IllegalTransactionStateException("Found instance of TXState: "
            + tx);
      }
      */
      context.setTXState(tx);
      SystemTimerTask task = this.expiryTasks.remove(tx.getTransactionId());
      if (task != null) {
        task.cancel();
      }
    }
  }

  private final boolean isClosed() {
    return this.closed;
  }

  private final void checkClosed() {
    cache.getCancelCriterion().checkCancelInProgress(null);
    if (this.closed) {
      throw new TXManagerCancelledException(
          "This transaction manager is closed.");
    }
  }

  public final LogWriterI18n getLogger() {
    return this.logWriter;
  }

  public final DM getDM() {
    return this.dm;
  }

  public static TXStateInterface getCurrentTXState() {
    final TXContext context = txContext.get();
    if (context == null) {
      return null;
    }
    return context.getTXState();
  }

  public static TXStateInterface getCurrentSnapshotTXState() {
    final TXContext context = txContext.get();
    return context != null ? context.getSnapshotTXState() : null;
  }

  public static TXId getCurrentTXId() {
    final TXStateInterface tx = getCurrentTXState();
    if (tx != null) {
      return tx.getTransactionId();
    }
    return null;
  }

  public static Transaction getCurrentJTA() {
    final TXContext context = txContext.get();
    if (context == null) {
      return null;
    }
    return context.getJTA();
  }

  public static void endCurrentJTA() {
    final TXContext context = txContext.get();
    if (context != null) {
      context.clearJTA();
    }
  }

  public static void setCurrentJTA(final Transaction tx) {
    TXContext context = txContext.get();
    if (tx != null) {
      if (context == null) {
        context = TXContext.newContext();
      }
      context.setJTA(tx);
      return;
    }
    if (context != null) {
      context.clearJTA();
    }
  }

  public static void beginJTA(final Transaction tx, TXContext context) {
    if (context == null) {
      context = TXContext.newContext();
    }
    context.setJTA(tx);
  }

  /**
   * Returns the current {@link TXContext} in ThreadLocal.
   */
  public static TXContext currentTXContext() {
    return txContext.get();
  }

  public static Map<TXContext, Boolean> getJTAContexts() {
    return jtaContexts;
  }

  /**
   * Associate the remote txState with the thread processing this message. Some
   * messages like SizeMessage should not create a new txState.
   * 
   * @return {@link TXContext} the thread-local context for the transactional
   *         message
   */
  public final TXContext masqueradeAs(final AbstractOperationMessage msg,
      final boolean endTX, final boolean setBatching) {
    final TXId txId = msg.getTXId();
    if (txId == null) {
      return null;
    }

    final boolean useTXProxy = msg.useTransactionProxy();
    // check if TXStateInterface is already set in the message
    TXStateInterface tx = msg.getTXState();
    if (tx != null) {
      final TXContext context = setTXState(tx);
      prepareTX(endTX, context, msg, useTXProxy, setBatching);
      return context;
    }

    final boolean canStartTX = msg.canStartRemoteTransaction();
    final TXContext context = getOrCreateTXContext();
    TXStateProxy proxy;
    if (canStartTX) {
      proxy = getOrCreateHostedTXState(txId, msg.getLockingPolicy(), true);
    }
    else {
      proxy = this.hostedTXStates.get(txId, txStateCreator);
    }
    if (proxy != null) {
      msg.startTXProxyRead();
      if (!useTXProxy) {
        // for write operations create a new local TXState if not present
        if (canStartTX) {
          tx = proxy.getTXStateForWrite();
        }
        else {
          tx = proxy.getLocalTXState();
        }
        if (tx != null) {
          prepareTX(endTX, context, msg, useTXProxy, setBatching);
          context.setTXState(tx);
          msg.setTXState(tx);
          return context;
        }
        else {
          context.clearTXState();
          return null;
        }
      }
      else {
        prepareTX(endTX, context, msg, useTXProxy, setBatching);
        context.setTXState(proxy);
        msg.setTXState(proxy);
        return context;
      }
    }
    else {
      context.clearTXState();
      return null;
    }
  }

  private final void prepareTX(final boolean endTX, final TXContext context,
      final AbstractOperationMessage msg, final boolean useTXProxy,
      final boolean setBatching) {
    if (!endTX) {
      // if there is a pending TX commit then set in this too so that it can be
      // relayed further if required (e.g. in nested function execution)
      final TXId pendingTXId = msg.getPendingTXId();
      if (pendingTXId != null) {
        context.setPendingTXId(pendingTXId);
      }
      if (setBatching) {
        // force batching on remote nodes (and also local execution) if required
        context.remoteBatching(true);
      }
      context.setMessageUsesTXProxy(useTXProxy);
    }
  }

  /**
   * Associate the transactional state with this thread. Should be only used by
   * tests.
   * 
   * @param txState
   *          the transactional state.
   */
  public TXContext masqueradeAs(TXStateInterface txState) {
    return setTXState(txState);
  }

  /**
   * Remove the association created by
   * {@link #masqueradeAs(AbstractOperationMessage, boolean, boolean)}
   */
  public final void unmasquerade(final TXContext context,
      final boolean resetBatching) {
    if (context != null) {
      if (resetBatching) {
        // reset the thread-local flags
        context.remoteBatching(false);
      }
      context.clearTXState();
    }
  }

  /**
   * Cleanup the remote txState after commit and rollback
   * @param txId
   * @return the TXStateProxy
   */
  public final TXStateProxy removeHostedTXState(TXId txId, Boolean commit) {
    return removeHostedTXState(txId, this.txStateRemove, null, commit);
  }

  /**
   * Cleanup the remote txState subject to given condition atomically during
   * remove.
   */
  public final <C, P> TXStateProxy removeHostedTXState(TXId txId,
      MapCallback<TXId, TXStateProxy, C, P> condition, C context,
      P removeParams) {
    final TXStateProxy tx = this.hostedTXStates.remove(txId, condition,
        context, removeParams);
    getCache().removeTXId(txId);
    if (tx != null) {
      if (TXStateProxy.LOG_FINE) {
        getLogger().info(LocalizedStrings.DEBUG, "TX removed: " + tx);
      }
    }
    return tx;
  }

  /**
   * This will return the lock owner of a region entry by doing a possibly
   * expensive brute-force search among all active transactions. Should only be
   * used for logging or exception strings and never in other product code.
   */
  public static Object searchLockOwner(RegionEntry entry,
      final LockMode lockMode, final Object context, Object forOwner) {
    // search in all active transactions
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    boolean selfOwner = false;
    if (forOwner instanceof TXState) {
      forOwner = ((TXState)forOwner).txId;
    }
    if (cache != null && !cache.isClosed()
        // we only do this expensive lookup at log-level info or more
        && !SKIP_LOCKOWNER_SEARCH && cache.getLoggerI18n().infoEnabled()) {
      if (entry instanceof TXEntryId) {
        final TXId txId = ((TXEntryId)entry).getTXId();
        if (txId != null) {
          return txId;
        }
      }
      if (entry instanceof TXEntryState) {
        entry = ((TXEntryState)entry).regionEntry;
      }
      final TXManagerImpl txMgr = cache.getCacheTransactionManager();
      final Object key = entry.getKey();
      LocalRegion dataRegion = null;
      if (context != null && context instanceof LocalRegion) {
        dataRegion = (LocalRegion)context;
      }
      TXState tx;
      final Object[] readOwner = new Object[1];
      for (TXStateProxy proxy : txMgr.getHostedTransactionsInProgress()) {
        if ((tx = proxy.getLocalTXState()) != null) {
          // now check for all modified entries in the TXState;
          // we don't want to take a lock on the circular linked list to avoid
          // affecting other transactions so go through all TXRegionStates
          Object owner = null;
          if (dataRegion != null) {
            final TXRegionState txr = tx.getTXRegionStateUnsafe(context);
            if (txr != null) {
              owner = searchLockOwnerInTXRegionState(txr, entry, key, proxy,
                  tx, lockMode, forOwner, readOwner);
              if (owner == forOwner) {
                selfOwner = true;
              }
            }
          }
          if (owner == null || selfOwner) {
            final TXRegionState[] regions = tx.getTXRegionStatesSnapUnsafe();
            for (TXRegionState txr : regions) {
              owner = searchLockOwnerInTXRegionState(txr, entry, key, proxy,
                  tx, lockMode, forOwner, readOwner);
              if (owner != null) {
                if (owner == forOwner) {
                  selfOwner = true;
                }
                else {
                  break;
                }
              }
            }
          }
          if (owner != null && owner != forOwner) {
            return owner;
          }
        }
      }
      // check for possible read-write conflict
      if (readOwner[0] != null) {
        return readOwner[0];
      }
    }
    if (selfOwner) {
      return forOwner;
    }
    return null;
  }

  private static Object searchLockOwnerInTXRegionState(final TXRegionState txr,
      final RegionEntry entry, final Object key, final TXStateProxy proxy,
      final TXState tx, final LockMode lockMode, final Object forOwner,
      final Object[] readOwner) {
    boolean selfOwner = false;
    if (txr != null) {
      final TXId txId = tx.txId;
      // we might get a ConcurrentModificationException while iterating
      // the entryMods HashMap, so ignore it and try again
      int tries = 5;
      while (tries-- > 0) {
        // first check if TX is still valid; this is important
        // especially when retrying due to concurrent modification
        if (!proxy.isInProgress()) {
          return null;
        }
        try {
          final Object obj = txr.getInternalEntryMap().get(key);
          final TXEntryState txes;
          if (obj != null && obj instanceof TXEntryState
              && !(txes = (TXEntryState)obj).isOpNull()
              && entry == txes.regionEntry) {
            if (!txId.equals(forOwner)) {
              return txId;
            }
            else {
              // assume that self cannot conflict normally so skip
              // it and try others;
              // self cannot conflict for EX_SH => EX upgrade in any case
              if (!(txes.isDirty() && lockMode == LockMode.EX)) {
                selfOwner = true;
              }
            }
          }
          else if (obj instanceof AbstractRegionEntry
              && !txId.equals(forOwner)) {
            readOwner[0] = txId;
          }
          break;
        } catch (ConcurrentModificationException ignored) {
          // ignore this and try again
          continue;
        } catch (Exception ignored) {
          // ignore and try again
          continue;
        }
      }
    }
    // fallback to self if no other conflict is found
    if (selfOwner) {
      return forOwner;
    }
    return null;
  }

  /**
   * Dump all TX states to the given StringBuilder.
   */
  public static void dumpAllTXStates(final StringBuilder msg,
      final String logPrefix) {
    final InternalDistributedSystem sys = InternalDistributedSystem
        .getConnectedInstance();
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (sys == null || cache == null) {
      return;
    }
    final TXManagerImpl txMgr = cache.getCacheTransactionManager();
    final String lineSep = ClientSharedUtils.lineSeparator;
    msg.append(lineSep).append(logPrefix).append(':');
    for (TXStateProxy txProxy : txMgr.getHostedTransactionsInProgress()) {
      msg.append(' ');
      txProxy.getTransactionId().appendToString(msg, sys);
      msg.append(",state=").append(txProxy.state)
          .append('{').append(txProxy.getIsolationLevel()).append('}');
      msg.append('{').append(txProxy.creatorThread.toString()).append('}');
    }
  }

  /**
   * Dump all TX entry locks to the given StringBuilder.
   */
  public static void dumpAllEntryLocks(final StringBuilder msg,
      final String logPrefix, final PrintWriter pw) {
    final InternalDistributedSystem sys = InternalDistributedSystem
        .getConnectedInstance();
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (sys == null || cache == null) {
      return;
    }
    final TXManagerImpl txMgr = cache.getCacheTransactionManager();
    final String lineSep = ClientSharedUtils.lineSeparator;
    TXState txState;
    boolean txDumped, anyDumped = false;
    for (TXStateProxy txProxy : txMgr.getHostedTransactionsInProgress()) {
      txDumped = false;
      if (txProxy != null && (txState = txProxy.getLocalTXState()) != null) {
        // now check for all modified entries in the TXState;
        // we don't want to take a lock on the circular linked list to avoid
        // affecting other transactions so go through all TXRegionStates
        final TXRegionState[] regions = txState.getTXRegionStatesSnapUnsafe();
        for (TXRegionState txr : regions) {
          if (txr != null) {
            // we might get a ConcurrentModificationException while iterating
            // the entryMods HashMap, so ignore it and try again
            int tries = 5;
            while (tries-- > 0) {
              try {
                @SuppressWarnings("unchecked")
                final ArrayList<Object> entries = new ArrayList<Object>(
                    txr.getInternalEntryMap().values());
                // also dump the pending TSS read locks
                final TXState.ArrayListAppend[] pendingSHLocks = txState
                    .getProxy().getTSSPendingReadLocks();
                final TXState.ArrayListAppend pendingLocks;
                if (pendingSHLocks != null
                    && (pendingLocks = pendingSHLocks[0]) != null) {
                  final int sz = pendingLocks.size();
                  for (int index = 0; index < sz; index++) {
                    Object entry = pendingLocks.get(index);
                    if (entry != null) {
                      entries.add(entry);
                    }
                  }
                }
                for (int index = 0; index < entries.size(); index++) {
                  final Object obj = entries.get(index);
                  final TXEntryState txes = obj instanceof TXEntryState
                      ? (TXEntryState)obj : null;
                  if (txes == null || !txes.isOpNull()) {
                    if (!txDumped) {
                      msg.append(lineSep).append(logPrefix).append(": ");
                      txState.txId.appendToString(msg, sys);
                      msg.append(txProxy.creatorThread.toString()).append(": ");
                      txDumped = true;
                      anyDumped = true;
                    }
                    else {
                      msg.append(", ");
                    }
                    msg.append('[');
                    if (txes != null) {
                      msg.append(txes.shortToString()).append("]{");
                      if (txes.isDirty()) {
                        msg.append("WRITE}");
                      }
                      else {
                        msg.append("READ_ONLY}");
                      }
                    }
                    else if (obj instanceof AbstractRegionEntry) {
                      final AbstractRegionEntry re = (AbstractRegionEntry)obj;
                      re.shortToString(msg);
                      msg.append("]{READ}");
                    }
                    else {
                      msg.append(obj).append("]{READ?}");
                    }
                  }
                  if (msg.length() > (DUMP_STRING_LIMIT + 1000)) {
                    dumpMessage(msg, pw);
                    msg.setLength(0);
                  }
                }
                break;
              } catch (ConcurrentModificationException ignored) {
                // ignore this and try again
              } catch (ArrayIndexOutOfBoundsException ignored) {
                // ignore this and try again
              }
            }
            if (msg.length() > DUMP_STRING_LIMIT) {
              dumpMessage(msg, pw);
              msg.setLength(0);
            }
          }
        }
      }
    }
    if (anyDumped) {
      msg.append(lineSep);
    }
  }

  public static void dumpMessage(final StringBuilder msg, PrintWriter pw) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (pw != null) {
      pw.println(msg.toString());
    }
    else if (cache == null) {
      System.out.println(msg.toString());
    }
    else {
      cache.getLogger().info(msg.toString());
    }
  }

  /**
   * Used by test to verify that remote transaction is in progress
   * @param txId
   * @return true if the transaction is in progress, false otherwise
   */
  public boolean isHostedTxInProgress(TransactionId txId) {
    return this.hostedTXStates.containsKey(txId);
  }

  public TXStateProxy getHostedTXState(TXId txId) {
    return this.hostedTXStates.get(txId);
  }

  public TXStateProxy getOrCreateHostedTXState(final TXId txId,
      final LockingPolicy lockingPolicy, final boolean checkFinishTX) {
    final TXStateProxy txProxy = this.hostedTXStates.create(txId,
        txStateCreator, lockingPolicy, checkFinishTX, true);
    return txProxy;
  }

  /**
   * Return set of transaction in progress on behalf of remote and local nodes.
   */
  public Collection<TXStateProxy> getHostedTransactionsInProgress() {
    return !this.hostedTXStates.isEmpty() ? new ArrayList<TXStateProxy>(
        this.hostedTXStates.values()) : Collections.<TXStateProxy> emptySet();
  }

  /**
   * Return number of transaction in progress on behalf of remote nodes.
   * 
   * FOR TESTS ONLY.
   */
  public int hostedTransactionsInProgressForTest() {
    return this.hostedTXStates.size();
  }

  public static void waitForPendingCommitForTest() {
    final TXContext context = currentTXContext();
    if (context != null) {
      context.waitForPendingCommit();
    }
  }

  public static void waitForPendingCommitsForTest() {
    final TXManagerImpl txMgr = GemFireCacheImpl.getExisting()
        .getCacheTransactionManager();
    TXStateProxy[] hostedTXs = new TXStateProxy[txMgr.hostedTXStates.size()];
    hostedTXs = txMgr.hostedTXStates.values().toArray(hostedTXs);
    for (TXStateProxy tx : hostedTXs) {
      if (TXStateProxy.LOG_FINE) {
        LogWriterI18n logger = txMgr.getLogger();
        logger.info(LocalizedStrings.DEBUG, " TX is: " + tx);
      }
      tx.waitForLocalTXCommit(null, 0);
    }
  }

  /**
   * @see MembershipListener#memberDeparted(InternalDistributedMember, boolean)
   */
  @Override
  public final void memberDeparted(final InternalDistributedMember id,
      final boolean crashed) {
    // first ensure that the member is removed from VMIdAdvisor so that no
    // new transactions can initiate that have departing node as coordinator
    this.dm.getSystem().getVMIdAdvisor().getMembershipListener()
        .memberDeparted(id, crashed);
    // submit a task to cleanup the transactions hosted by departing node
    this.dm.getHighPriorityThreadPool().execute(new Runnable() {
      @Override
      public void run() {
        final Collection<TXStateProxy> txs = getHostedTransactionsInProgress();
        for (TXStateProxy tx : txs) {
          if (TXStateProxy.LOG_FINE || DistributionManager.VERBOSE) {
            getLogger().info(LocalizedStrings.DEBUG,
                "TX checking " + tx + " for departed: " + id);
          }
          if (tx.handleMemberDeparted(id, dm, crashed)) {
            // TODO: TX: currently always rolling back the transaction
            removeHostedTXState(tx.getTransactionId(), Boolean.FALSE);
            if (TXStateProxy.LOG_FINE || DistributionManager.VERBOSE) {
              getLogger().info(LocalizedStrings.DEBUG,
                  "TX removed after member " + id + " departed: " + tx);
            }
          }
        }
      }
    });
  }

  @Override
  public void memberJoined(InternalDistributedMember id) {
  }

  @Override
  public void quorumLost(Set<InternalDistributedMember> failures,
      List<InternalDistributedMember> remaining) {
  }

  @Override
  public void memberSuspect(InternalDistributedMember id,
      InternalDistributedMember whoSuspected) {
  }

  /**
   * @see OrderedMembershipListener#order()
   */
  public final int order() {
    // we want memberDeparted of TXManagerImpl to be invoked at the very start
    // before any other listeners (in particular the DistributionAdvisors).
    return O_TXMANAGER;
  }

  public TXContext masqueradeAs(Message msg, InternalDistributedMember member,
      boolean setBatching) {
    throw new UnsupportedOperationException("not implemented for client TX");
  }

  public TXId suspend() {
    final TXContext context = currentTXContext();
    TXStateInterface tx = null;
    if (context != null && (tx = context.getTXState()) != null) {
      final TXId txId = tx.getTransactionId();
      context.clearTXState();
      this.suspendedTXs.put(txId, tx);
      // wake up waiting threads
      Queue<Thread> waitingThreads = this.waitMap.get(txId);
      if (waitingThreads != null) {
        Thread waitingThread = null;
        while (true) {
          waitingThread = waitingThreads.poll();
          if (waitingThread == null
              || !Thread.currentThread().equals(waitingThread)) {
            break;
          }
        }
        if (waitingThread != null) {
          LockSupport.unpark(waitingThread);
        }
      }
      scheduleExpiry(txId);
      return txId;
    }
    return null;
  }

  public void resume(TransactionId transactionId) {
    if (transactionId == null) {
      throw new IllegalStateException(
          LocalizedStrings.TXManagerImpl_UNKNOWN_TRANSACTION_OR_RESUMED
              .toLocalizedString());
    }
    if (getTXState() != null) {
      throw new IllegalStateException(
          LocalizedStrings.TXManagerImpl_TRANSACTION_ACTIVE_CANNOT_RESUME
              .toLocalizedString());
    }
    TXStateInterface tx = this.suspendedTXs.remove(transactionId);
    if (tx == null) {
      throw new IllegalStateException(
          LocalizedStrings.TXManagerImpl_UNKNOWN_TRANSACTION_OR_RESUMED
              .toLocalizedString());
    }
    resume(tx);
  }

  public boolean isSuspended(TransactionId transactionId) {
    return this.suspendedTXs.containsKey(transactionId);
  }

  public boolean tryResume(TransactionId transactionId) {
    if (transactionId == null || getTXState() != null) {
      return false;
    }
    TXStateInterface tx = this.suspendedTXs.remove(transactionId);
    if (tx != null) {
      resume(tx);
      return true;
    }
    return false;
  }

  public boolean tryResume(final TransactionId transactionId, long time,
      final TimeUnit unit) {
    if (transactionId == null || getTXState() != null || !exists(transactionId)) {
      return false;
    }
    Queue<Thread> threadq = waitMap.get(transactionId);
    if (threadq == null || threadq.isEmpty()) {
      if (tryResume(transactionId)) {
        return true;
      }
    }
    long timeout = unit.toNanos(time);
    long startTime = System.nanoTime();
    Thread currentThread = Thread.currentThread();

    while (timeout > 0) {
      // after putting this thread in waitMap, we should check for
      // an entry in suspendedTXs. if no entry is found in suspendedTXs
      // next invocation of suspend() will unblock this thread
      if (tryResume(transactionId)) {
        threadq = waitMap.get(transactionId);
        if (threadq != null) {
          threadq.remove(currentThread);
        }
        return true;
      }
      // put the current thread in the waitingMap along with
      // the transactionId, then suspend the thread
      if (threadq == null) {
        threadq = new ConcurrentLinkedQueue<Thread>();
      }
      if (!threadq.contains(currentThread)) {
        // sync not required as only one thread will try to
        // add itself to the queue
        threadq.add(currentThread);
      }
      Queue<Thread> oldq = waitMap.putIfAbsent(transactionId, threadq);
      if (oldq != null) {
        // add the thread to the list of waiting threads
        if (!oldq.contains(currentThread)) {
          oldq.add(currentThread);
        }
        threadq = null;
      }
      LockSupport.parkNanos(timeout);
      if (tryResume(transactionId)) {
        threadq = waitMap.get(transactionId);
        if (threadq != null) {
          threadq.remove(currentThread);
        }
        return true;
      } else if (!exists(transactionId)) {
        return false;
      }
      long nowTime = System.nanoTime();
      timeout -= nowTime - startTime;
      startTime = nowTime;
    }
    return false;
  }

  public boolean exists(TransactionId transactionId) {
    return isHostedTxInProgress(transactionId) || isSuspended(transactionId);
  }

  /**
   * The timeout after which any suspended transactions are
   * rolled back if they are not resumed. If a negative
   * timeout is passed, suspended transactions will never expire.
   * @param timeout the timeout in minutes
   */
  public void setSuspendedTransactionTimeout(long timeout) {
    this.suspendedTXTimeout = timeout;
  }

  /**
   * Return the timeout after which suspended transactions
   * are rolled back.
   * @return the timeout in minutes
   * @see #setSuspendedTransactionTimeout(long)
   */
  public long getSuspendedTransactionTimeout() {
    return this.suspendedTXTimeout;
  }

  /**
   * schedules the transaction to expire after {@link #suspendedTXTimeout}
   * 
   * @param txId
   */
  private void scheduleExpiry(TransactionId txId) {
    final GemFireCacheImpl cache = this.cache;
    if (suspendedTXTimeout < 0) {
      if (logWriter.fineEnabled()) {
        logWriter.fine("TX: transaction: " + txId + " not scheduled to expire");
      }
      return;
    }
    SystemTimerTask task = new TXExpiryTask(txId);
    if (logWriter.fineEnabled()) {
      logWriter.fine("TX: scheduling transaction: " + txId
          + " to expire after:" + suspendedTXTimeout);
    }
    cache.getCCPTimer().schedule(task, suspendedTXTimeout * 60 * 1000);
    this.expiryTasks.put(txId, task);
  }

  /**
   * Task scheduled to expire a transaction when it is suspended. This task gets
   * canceled if the transaction is resumed.
   * 
   * @author sbawaska
   */
  public static class TXExpiryTask extends SystemTimerTask {

    /**
     * The txId to expire
     */
    private final TransactionId txId;

    public TXExpiryTask(TransactionId txId) {
      this.txId = txId;
    }

    @Override
    public void run2() {
      final TXManagerImpl mgr = GemFireCacheImpl.getExisting()
          .getCacheTransactionManager();
      TXStateInterface tx = mgr.suspendedTXs.remove(this.txId);
      if (tx != null) {
        final LogWriterI18n logger = mgr.getLogger();
        try {
          if (logger.fineEnabled()) {
            logger
                .fine("TX: Expiry task rolling back transaction:" + this.txId);
          }
          tx.rollback(null);
        } catch (GemFireException e) {
          if (logger.warningEnabled()) {
            logger.warning(LocalizedStrings
                .TXManagerImpl_EXCEPTION_IN_TRANSACTION_TIMEOUT, this.txId, e);
          }
        }
      }
    }

    @Override
    public LogWriterI18n getLoggerI18n() {
      return GemFireCacheImpl.getExisting().getLoggerI18n();
    }

  }
}
