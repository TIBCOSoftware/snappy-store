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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.transaction.Status;
import javax.transaction.Synchronization;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.ProfileVisitor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.MembershipManager;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.BucketAdvisor.BucketProfile;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.CacheProfile;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.PutAllEntryData;
import com.gemstone.gemfire.internal.cache.RemoteOperationMessage.RemoteOperationResponse;
import com.gemstone.gemfire.internal.cache.TXBatchMessage.TXBatchResponse;
import com.gemstone.gemfire.internal.cache.TXRemoteCommitPhase1Message.CommitPhase1Response;
import com.gemstone.gemfire.internal.cache.TXState.ArrayListAppend;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy.ReadEntryUnderLock;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantReadWriteLock;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.DestroyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DestroyMessage.DestroyResponse;
import com.gemstone.gemfire.internal.cache.partitioned.InvalidateMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionMessage.PartitionResponse;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor.PartitionProfile;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.concurrent.MapCallback;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.gemstone.gnu.trove.THash;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectLongProcedure;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * TXStateProxy lives on the source node when we are remoting a transaction. It
 * is a proxy for {@link TXState}. If the source node itself holds data relevant
 * to the transaction then it will have its own {@link TXState} inside its
 * {@link TXStateProxy}.
 * 
 * @author gregp, sbawaska, kneeraj, swale
 */
@SuppressWarnings("serial")
// using itself as lock for "regions"
public class TXStateProxy extends NonReentrantReadWriteLock implements
    TXStateInterface, Synchronization {

  private final static TXStateProxyFactory factory = new TXStateProxyFactory() {

    public final TXStateProxy newTXStateProxy(final TXManagerImpl txMgr,
        final TXId txId, final IsolationLevel isolationLevel,
        final boolean isJTA, final EnumSet<TransactionFlag> flags,
        final boolean initLocalTXState) {
      return new TXStateProxy(txMgr, txId, isolationLevel, isJTA, flags,
          initLocalTXState);
    }
  };

  private final TXManagerImpl txManager;

  protected final TXId txId;

  final Thread creatorThread;

  private final boolean isJTA;

  private boolean isJCA;

  private final LockingPolicy lockPolicy;

  /**
   * Set of regions affected in this TX on this node. Access to this is to be
   * guarded by read-write lock on "this" TXStateProxy.
   */
  protected final SetWithCheckpoint regions;

  /**
   * For persistent regions, this stores a consistent DiskStoreID (primary for
   * PRs and anyone for RRs) to be used for region versions by all commits.
   */
  protected THashMap regionDiskVersionSources;

  // observer, if any, for this transaction
  private TransactionObserver txObserver;

  protected final InternalDistributedMember self;

  private final InternalDistributedMember coordinator;

  private volatile TXState localTXState;

  THashMap eventsToBePublished;

  /**
   * This lock is used for changes to own state since it can be accessed by
   * multiple threads via distributed function execution.
   */
  protected final NonReentrantLock lock;

  /**
   * Used to check that a hosted TXStateProxy can be really removed only when
   * everyone has relinquished it.
   */
  protected final AtomicInteger refCount;

  private final long beginTime;

  /** True if operations related to transactions have to be logged globally. */
  private static boolean VERBOSE = VERBOSE_ON();
  private static boolean VERBOSEVERBOSE = VERBOSEVERBOSE_ON();
  public static boolean LOG_FINE = VERBOSE | VERBOSEVERBOSE;
  public static boolean LOG_FINEST = VERBOSEVERBOSE;
  public static boolean LOG_VERSIONS = LOG_FINE | StateFlushOperation.DEBUG
      | DistributionManager.VERBOSE;
  public static boolean TRACE_EXECUTE = LOG_FINE || DistributionManager.VERBOSE
      || EXECUTE_ON();
  // system properties for VERBOSE and VERBOSEVERBOSE
  public static final String TRACE_EXECUTE_PROPERTY = "TX.TRACE_EXECUTE";
  public static final String VERBOSE_PROPERTY = "TX.VERBOSE";
  public static final String VERBOSEVERBOSE_PROPERTY = "TX.VERBOSEVERBOSE";

  /**
   * The current {@link State} of the transaction.
   */
  final AtomicReference<State> state;

  protected volatile boolean hasCohorts;

  /** True if this transaction has any pending write operation for commit. */
  private volatile boolean isDirty;

  /**
   * True if this transaction has any pending read operations for commit.
   */
  private volatile boolean hasReadOps;

  private Throwable inconsistentThr;

  /**
   * Transient flag to indicate that rollover from row buffers to column store
   * are disabled.
   */
  private volatile boolean columnRolloverDisabled;

  /**
   * List of any function executions that have not yet invoked a getResult().
   * This will be invoked before commit to force any in-progress function
   * executions to complete.
   * TODO: TX: manage this list automatically instead of having explicit calls
   */
  private final ArrayList<ResultCollector<?, ?>> pendingResultCollectors;

  /**
   * This flag is to indicate wait for this TX to complete commit in case a new
   * transaction is started from the same source thread (the source does not
   * wait for commit replies to be all received).
   */
  protected volatile boolean waitingForCommit;

  /**
   * Set to true if this is a GemFireXD system.
   */
  protected final boolean isGFXD;

  /**
   * The view ID at the start of transaction to check for membership changes
   * before commit phase2.
   */
  private final long initViewId;

  /**
   * The batch-size used on coordinator/function-execution. Since this is only
   * done when this node is a datastore, its better to batch up indefinitely and
   * only flush before commit (or before sending lastResult for function
   * execution).
   */
  private static final boolean ENABLE_BATCHING = !Boolean
      .getBoolean("gemfire.tx-disable-batching");

  /**
   * Per TX flag to check if batching has been enabled. The default value is
   * determined by {@link #ENABLE_BATCHING} that is set by the
   * "gemfire.tx-disable-batching" system property.
   */
  protected final boolean enableBatching;

  /**
   * Per TX flag to wait on 2nd phase commit message.
   * 
   * @see TransactionFlag#SYNC_COMMITS
   */
  protected final boolean syncCommits;

  /**
   * the System.currentTimeInMillis() at the time of commit which is used to set
   * the lastModifiedTime of all updated entries in the transaction consistently
   */
  protected long commitTime;

  /**
   * This currentExecutionSeq will change at the start of every statement execution
   * by the client. This will be used to put savepoint markers in TXEntryState so
   * that rollback can be done till these at client failover
   */
  private int currentExecutionSeq;

  // flags below are for passing to performOp() method
  private static final int IF_NEW = 0x1;
  private static final int IF_OLD = 0x2;
  private static final int REQUIRED_OLD_VAL = 0x4;
  private static final int OVERWRITE_DESTROYED = 0x8;
  private static final int INVOKE_CALLBACKS = 0x10;
  private static final int FORCE_NEW_ENTRY = 0x20;
  private static final int SKIP_CACHEWRITE = 0x40;
  // end flags for performOp()

  /**
   * Retry duration while waiting for local TXState commit to finish when
   * subsequent operation from same coordinator is received.
   */
  private static final int TXCOMMIT_WAIT_RETRY_MILLIS = 5;

  /**
   * Indicates no TX set for EntryEventImpl (as opposed to null that indicates
   * non-TX operation).
   */
  public static final TXStateInterface TX_NOT_SET = new TXStateProxy();

  /**
   * Pending locks returned by {@link #getTSSPendingReadLocks}.
   */
  protected static final ThreadLocal<ArrayListAppend[]> tssPendingReadLocks =
      new ThreadLocal<ArrayListAppend[]>() {
    @Override
    protected final ArrayListAppend[] initialValue() {
      return new ArrayListAppend[TXState.PENDING_LOCKS_NUM_ARRAYS];
    }
  };

  // ProfileVisitors below are invoked before commit to check for other copies
  // of affected regions
  private static final ProfileVisitor<Void> preCommitBucketVisitor =
      new ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor,
        final Profile profile, int profileIndex, int numProfiles,
        Void aggregate) {
      assert profile instanceof BucketProfile;
      final BucketProfile bucketProfile = (BucketProfile)profile;
      return !bucketProfile.isHosting;
    }
  };
  private static final ProfileVisitor<Void> preCommitRegionVisitor =
      new ProfileVisitor<Void>() {
    public boolean visit(DistributionAdvisor advisor,
        final Profile profile, int profileIndex, int numProfiles,
        Void aggregate) {
      assert profile instanceof CacheProfile;
      if (profile instanceof PartitionProfile) {
        final PartitionProfile pp = (PartitionProfile)profile;
        return !(pp.isDataStore && pp.regionInitialized
            && !pp.memberUnInitialized);
      }
      else {
        final CacheProfile cp = (CacheProfile)profile;
        return !(cp.dataPolicy.withStorage() && cp.regionInitialized
            && !cp.memberUnInitialized);
      }
    }
  };

  // ProfileVisitors below are invoked to find the recipients for
  // commit/rollback messages
  private static final ProfileVisitor<MemberToGIIRegions> finishBucketVisitor =
      new ProfileVisitor<MemberToGIIRegions>() {
    @Override
    public boolean visit(DistributionAdvisor advisor,
        final Profile profile, int profileIndex, int numProfiles,
        final MemberToGIIRegions recipients) {
      assert profile instanceof BucketProfile;
      BucketProfile bp = (BucketProfile)profile;
      if (!bp.inRecovery && bp.cachedOrAllEventsWithListener()) {
        ArrayList<Object> uninitializedRegions = recipients.members.getIfAbsentPutWith(
            bp.getDistributedMember(), recipients, bp);
        if (uninitializedRegions != null) {
          final ProxyBucketRegion pbr = ((BucketAdvisor)advisor)
              .getProxyBucketRegion();
          final THashMap tailKeys = recipients.eventsToBePublished;
          if (tailKeys != null) {
            // at this point we still don't have the actual list, but we will
            // still populate the map with null maps expecting those to
            // show up after commit phase1, and will remove for which this
            // node itself is the primary; this avoids having to do full
            // profile scan later again
            ((THashMap)uninitializedRegions.get(0)).put(pbr.getFullPath(), null);
          }
          if (!bp.regionInitialized) {
            RegionInfoShip regionInfo = new RegionInfoShip(pbr);
            uninitializedRegions.add(regionInfo);
          }
        }
      }
      if (bp.isPersistent && bp.persistentID != null) {
        // if its primary definitely store its DiskStoreID to be used
        // for versioning, else store the first one just in case we don't
        // find a primary
        if (!recipients.hasRegionDiskVersionSource || bp.isPrimary) {
          THashMap versionSources = recipients.regionDiskVersionSources;
          if (versionSources == null) {
            recipients.regionDiskVersionSources = versionSources = new THashMap();
          }
          RegionInfoShip regionInfo = new RegionInfoShip(
              ((BucketAdvisor)advisor).getProxyBucketRegion());
          versionSources.put(regionInfo, bp.persistentID.diskStoreId);
          recipients.hasRegionDiskVersionSource = true;
        }
      }
      return true;
    }
  };
  private static final ProfileVisitor<MemberToGIIRegions> finishRegionVisitor =
      new ProfileVisitor<MemberToGIIRegions>() {
    @SuppressWarnings("unchecked")
    @Override
    public boolean visit(DistributionAdvisor advisor,
        final Profile profile, int profileIndex, int numProfiles,
        final MemberToGIIRegions recipients) {
      assert profile instanceof CacheProfile;
      // for partitioned regions, the routing is done by finishBucketVisitor
      if (!(profile instanceof PartitionProfile)) {
        final CacheProfile cp = (CacheProfile)profile;
        if (!cp.inRecovery && cp.cachedOrAllEventsWithListener()) {
          ArrayList<Object> uninitializedRegions = recipients.members.getIfAbsentPutWith(
              cp.getDistributedMember(), recipients, cp);
          if (uninitializedRegions != null) {
            if (!cp.regionInitialized) {
              RegionInfoShip regionInfo = new RegionInfoShip(advisor.getAdvisee());
              uninitializedRegions.add(regionInfo);
            }
          }
        }
        if (cp.isPersistent && cp.persistentID != null) {
          // store the first one
          if (!recipients.hasRegionDiskVersionSource) {
            THashMap versionSources = recipients.regionDiskVersionSources;
            if (versionSources == null) {
              recipients.regionDiskVersionSources = versionSources = new THashMap();
            }
            RegionInfoShip regionInfo = new RegionInfoShip(advisor.getAdvisee());
            versionSources.put(regionInfo, cp.persistentID.diskStoreId);
            recipients.hasRegionDiskVersionSource = true;
          }
        }
      }
      return true;
    }
  };

  public static final class MemberToGIIRegions
      implements Function<CacheProfile, ArrayList<Object>> {
    final UnifiedMap<InternalDistributedMember, ArrayList<Object>> members;
    final THashMap eventsToBePublished;
    long[] viewVersions;
    DistributionAdvisor[] viewAdvisors;
    boolean hasUninitialized;
    /**
     * If this is the transaction coordinator, this gets a consistent DiskStoreID
     * (primary for PRs and anyone for RRs) to be used for region versions by all
     * commits.
     */
    THashMap regionDiskVersionSources;
    /**
     * true if the last region profile visitor did set a valid VersionSource in
     * {@link #regionDiskVersionSources} when versioning is enabled
     */
    transient boolean hasRegionDiskVersionSource;

    public static final MemberToGIIRegions EMPTY = new MemberToGIIRegions(1, 0,
        null);

    public MemberToGIIRegions(int numRegions, THashMap eventsToBePublished) {
      this(THash.DEFAULT_INITIAL_CAPACITY, numRegions, eventsToBePublished);
    }

    MemberToGIIRegions(int initSize, int numRegions,
        THashMap eventsToBePublished) {
      this.members = new UnifiedMap<>(initSize);
      this.eventsToBePublished = eventsToBePublished;
      if (numRegions > 0) {
        this.viewVersions = new long[numRegions];
        for (int i = 0; i < numRegions; i++) {
          this.viewVersions[i] = -1;
        }
        this.viewAdvisors = new DistributionAdvisor[numRegions];
      }
      else {
        this.viewVersions = null;
        this.viewAdvisors = null;
      }
      this.hasUninitialized = false;
    }

    public final UnifiedMap<InternalDistributedMember, ArrayList<Object>> getMembers() {
      return this.members;
    }

    public final void endOperationSend(TXStateProxy proxy) {
      final long[] viewVersions = this.viewVersions;
      final DistributionAdvisor[] advisors;
      LogWriterI18n logger = null;
      long viewVersion = -1;
      if (viewVersions != null) {
        advisors = this.viewAdvisors;
        if (LOG_VERSIONS) {
          logger = InternalDistributedSystem.getLoggerI18n();
        }
        int numOps = viewVersions.length;
        while (--numOps >= 0) {
          viewVersion = viewVersions[numOps];
          if (viewVersion != -1) {
            advisors[numOps].endOperation(viewVersion);
            if (logger != null) {
              logger.info(LocalizedStrings.DEBUG, "TXCommit: "
                  + proxy.getTransactionId().shortToString()
                  + " done dispatching operation for "
                  + advisors[numOps].getAdvisee().getFullPath()
                  + " in view version " + viewVersion);
            }
          }
        }
        this.viewVersions = null;
        this.viewAdvisors = null;
      }
    }

    public final boolean hasUninitialized() {
      return this.hasUninitialized;
    }

    @Override
    public ArrayList<Object> valueOf(CacheProfile cp) {
      if (cp.regionInitialized) {
        if (this.eventsToBePublished == null) {
          return null;
        }
        else {
          // put an empty placeHolder expecting some events for the member
          final ArrayList<Object> data = new ArrayList<Object>(2);
          data.add(new THashMap(4));
          return data;
        }
      }
      else {
        this.hasUninitialized = true;
        final ArrayList<Object> data = new ArrayList<Object>(2);
        if (this.eventsToBePublished != null) {
          // put an empty placeHolder expecting some events for the member
          data.add(new THashMap(4));
        }
        return data;
      }
    }
  }

  private static MapCallback<TXId, TXStateProxy, Object, Void> checkEmpty =
      new MapCallbackAdapter<TXId, TXStateProxy, Object, Void>() {
    @Override
    public final Object removeValue(final Object key, Object value,
        final TXStateProxy txProxy, final Object cbArg, Void removeParams) {
      if (txProxy.removeIfEmpty(cbArg)) {
        if (LOG_FINE) {
          txProxy.txManager.getLogger().info(LocalizedStrings.DEBUG,
              txProxy.txId.shortToString()
              + ": removing from hosted list since it is empty");
        }
        return null;
      }
      else {
        return ABORT_REMOVE_TOKEN;
      }
    }
  };

  /**
   * The different states of this transaction.
   */
  private static enum State {
    OPEN,
    COMMIT_PHASE2_STARTED,
    ROLLBACK_STARTED,
    INCONSISTENT,
    CLOSED
  }

  /** only used by token {@link #TX_NOT_SET} */
  private TXStateProxy() {
    this.txManager = null;
    this.self = null;
    this.txId = null;
    this.creatorThread = null;
    this.lockPolicy = LockingPolicy.NONE;
    this.isJTA = false;
    this.isJCA = false;
    this.enableBatching = ENABLE_BATCHING;
    this.syncCommits = false;
    this.lock = null;
    this.refCount = null;
    this.state = null;
    this.regions = null;
    this.coordinator = null;
    this.pendingResultCollectors = null;
    this.beginTime = 0L;
    this.isGFXD = false;
    this.initViewId = 0L;
  }

  protected TXStateProxy(final TXManagerImpl txMgr, final TXId txId,
      final IsolationLevel isolationLevel, final boolean isJTA,
      final EnumSet<TransactionFlag> flags, final boolean initLocalTXState) {
    super(txMgr.getCache().getCancelCriterion());
    this.txManager = txMgr;
    this.txObserver = txMgr.getObserver();
    final GemFireCacheImpl cache = txMgr.getCache();
    final InternalDistributedSystem sys = cache.getSystem();
    this.self = cache.getMyId();
    this.txId = txId;
    this.creatorThread = Thread.currentThread();
    this.isJTA = isJTA;
    this.isJCA = false;
    if (flags == null) {
      this.lockPolicy = LockingPolicy.fromIsolationLevel(isolationLevel, false);
      this.enableBatching = (this.lockPolicy != LockingPolicy.SNAPSHOT) && ENABLE_BATCHING;
      this.syncCommits = this.lockPolicy == LockingPolicy.SNAPSHOT;
    }
    else {
      this.lockPolicy = LockingPolicy.fromIsolationLevel(isolationLevel,
          flags.contains(TransactionFlag.WAITING_MODE));
      if (flags.contains(TransactionFlag.DISABLE_BATCHING)) {
        this.enableBatching = false;
      }
      else {
        this.enableBatching = (this.lockPolicy != LockingPolicy.SNAPSHOT) && ENABLE_BATCHING;
      }
      this.syncCommits = (this.lockPolicy == LockingPolicy.SNAPSHOT) || flags.contains(TransactionFlag.SYNC_COMMITS);
    }
    this.inconsistentThr = null;

    this.lock = new NonReentrantLock(true, cache.getCancelCriterion());
    // start off with a single reference for the creator
    this.refCount = new AtomicInteger(1);
    this.regions = new SetWithCheckpoint(true);
    this.pendingResultCollectors = new ArrayList<ResultCollector<?, ?>>(5);

    this.isGFXD = cache.isGFXDSystem();
    this.beginTime = CachePerfStats.getStatTime();
    final LogWriterI18n logger = txMgr.getLogger();

    // turn on LOG_FINEST flag at finest level logging
    // don't care about non-volatile read/write here
    if (!LOG_FINEST && logger.finestEnabled()) {
      LOG_FINEST = true;
    }
    // also turn on LOG_FINE flag at fine level logging
    // don't care about non-volatile read/write here
    if (!LOG_FINE && (LOG_FINEST || logger.fineEnabled())) {
      LOG_FINE = true;
    }

    if (initLocalTXState) {
      // in this case the coordinator is a remote node
      this.coordinator = sys.getVMIdAdvisor().adviseDistributedMember(
          txId.getMemberId(), true);
      if (this.coordinator == null) {
        // throw node departed exception
        throw new TransactionDataNodeHasDepartedException(LocalizedStrings
            .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(" for " + txId));
      }
      this.localTXState = new TXState(this);
    }
    else {
      // this is a locally started TX
      this.coordinator = this.self;
      this.localTXState = null;
    }
    this.initViewId = getViewId(txMgr.getDM());

    this.state = new AtomicReference<State>(State.OPEN);

    if (TRACE_EXECUTE) {
      logger.info(LocalizedStrings.DEBUG, toString() + ": started.");
    }
  }

  public static final TXStateProxyFactory getFactory() {
    return factory;
  }

  private long getViewId(DM dm) {
    MembershipManager mm = dm.getMembershipManager();
    return mm != null ? mm.getViewId() : -1;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append(this.getClass().getSimpleName()).append("@(");
    if (this == TX_NOT_SET) {
      return sb.append("TX_NOT_SET)").toString();
    }
    final TXId txId = this.txId;
    if (txId != null) {
      txId.appendToString(sb, this.txManager.getDM().getSystem());
    }
    else {
      sb.append("TXId is null");
    }
    sb.append(",state=").append(this.state);
    sb.append('{').append(getIsolationLevel()).append('}');
    if (isJTA()) {
      sb.append("{isJTA}");
    }
    if (isCoordinator()) {
      sb.append("{COORDINATOR}");
    }
    final TXState localState = this.localTXState;
    if (localState != null) {
      sb.append("{LOCAL_TXSTATE@").append(Integer.toHexString(
          System.identityHashCode(localState))).append('}');
    }
    if (batchingEnabled()) {
      sb.append("{BATCHING}");
    }
    if (isDirty()) {
      sb.append("{DIRTY}");
    }
    if (this.syncCommits) {
      sb.append("{SYNCCOMMITS}");
    }
    if (this.hasReadOps) {
      sb.append("{HAS_READ_OPS}");
    }
    sb.append("@0x").append(Integer.toHexString(System.identityHashCode(this)))
        .append(')');
    return sb.toString();
  }

  public static final boolean EXECUTE_ON() {
    return Boolean.getBoolean(TRACE_EXECUTE_PROPERTY);
  }

  public static final boolean VERBOSE_ON() {
    return Boolean.getBoolean(VERBOSE_PROPERTY);
  }

  public static final boolean VERBOSEVERBOSE_ON() {
    return Boolean.getBoolean(VERBOSEVERBOSE_PROPERTY);
  }

  public static final void TRACE_SET(final boolean flagv, final boolean flagvv,
      final boolean flagvers, final GemFireCacheImpl cache) {
    VERBOSE = flagv;
    VERBOSEVERBOSE = flagvv;
    LOG_FINEST = flagvv || (cache != null && cache.getLogger().finestEnabled());
    LOG_FINE = flagv || LOG_FINEST
        || (cache != null && cache.getLogger().fineEnabled());
    LOG_VERSIONS = flagvers || LOG_FINE || StateFlushOperation.DEBUG
        || DistributionManager.VERBOSE;
    TRACE_EXECUTE = LOG_FINE || DistributionManager.VERBOSE || EXECUTE_ON();
  }

  public final boolean batchingEnabled() {
    final TXManagerImpl.TXContext context;
    return !isSnapshot() && (this.enableBatching || ((context = TXManagerImpl.currentTXContext())
        != null && context.forceBatching));
  }

  public final boolean skipBatchFlushOnCoordinator() {
    return this.enableBatching && isCoordinator();
  }

  public static boolean remoteMessageUsesTXProxy() {
    final TXManagerImpl.TXContext context;
    return ((context = TXManagerImpl.currentTXContext()) != null &&
        context.msgUsesTXProxy);
  }

  public final boolean remoteBatching(final boolean batching) {
    // nothing can be done if batching is already enabled (the default)
    if (this.enableBatching) {
      return true;
    }
    return TXManagerImpl.getOrCreateTXContext().remoteBatching(batching);
  }

  public final TXState getLocalTXState() {
    return this.localTXState;
  }

  public final TXStateProxy getProxy() {
    return this;
  }

  final TXManagerImpl.TXContext commitPhase1(final Object callbackArg)
      throws TransactionException {
    TXState localState = this.localTXState;
    checkTXState();
    // check that commit is invoked only from the coordinator
    if (!isCoordinator() && !isSnapshot()) {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TXManagerImpl_FINISH_NODE_NOT_COORDINATOR
              .toLocalizedString(getCoordinator()));
    }

    // get the set of recipients for commit using the list of affected regions
    final MemberToGIIRegions finishRecipients = getFinishMessageRecipients(true);
    final Set<?> recipients = finishRecipients.members.keySet();

    final TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
    context.setCommitRecipients(finishRecipients);
    Throwable thr = null;
    boolean interrupted = false;
    this.lock.lock();
    try {
      // refresh localTXState which could have changed in the call to
      // getFinishMessageRecipients()
      localState = this.localTXState;
      // TODO: TX: for now invoking the TXCacheWriter locally
      if (localState != null) {
        localState.invokeTransactionWriter();
      }

      final DM dm = this.txManager.getDM();

      // perform 2-phase commit if required
      TXRemoteCommitPhase1Message phase1Msg = null;
      boolean sendBatchOps = doSendPendingOps(localState);
      final int numRecipients = recipients.size();
      final boolean twoPhaseCommit = getLockingPolicy().requiresTwoPhaseCommit(
          this);

      if (twoPhaseCommit) {
        // wait for pending RCs before sending phase1 commit (#44475)
        if (waitForPendingRCs(dm)) {
          // batch ops may have been flushed, so refresh the boolean
          sendBatchOps = doSendPendingOps(localState);
        }

        // send the phase1 commit message separately if required
        // send it with even one recipient if there are batched messages
        // to be sent since we will need to wait for possible conflicts
        // so avoid the additional wait in phase2 commit
        if (numRecipients > 1 || (sendBatchOps &&
            numRecipients == 1)) { // only needed if more than one recipient
          phase1Msg = TXRemoteCommitPhase1Message.create(dm.getSystem(), dm,
              this, false, recipients);
          CommitPhase1Response response = phase1Msg.getReplyProcessor();

          // Flush any remaining batched messages; need to wait for replies to
          // detect any conflicts here; this also waits for any phase1 replies
          ArrayList<TXBatchResponse> batchResponses = null;
          if (sendBatchOps) {
            sendBatchOps = false;
            // conflict SH with EX in case of piggy-backed phase1 commit
            // message due to deadlocks (#44743)
            batchResponses = sendPendingOpsBeforeCommit(dm, localState,
                phase1Msg, this.isDirty);
            // need to send the phase1 message separately to remaining
            // recipients
            if (numRecipients > batchResponses.size()) {
              final THashSet remainingRecipients = new THashSet(recipients);
              for (TXBatchResponse batchResponse : batchResponses) {
                remainingRecipients.remove(batchResponse.recipient);
              }
              if (remainingRecipients.size() > 0) {
                phase1Msg.setRecipients(remainingRecipients);
                phase1Msg.processorId = response.register();
                dm.putOutgoing(phase1Msg);
              }
            }
          }
          else {
            // need to send the phase1 message separately to all recipients
            phase1Msg.setRecipients(recipients);
            phase1Msg.processorId = response.register();
            dm.putOutgoing(phase1Msg);
          }
          dm.getStats().incSentCommitPhase1Messages(1L);
          try {
            // wait for TXBatchMessage responses if required
            if (batchResponses != null) {
              waitForPendingOpsBeforeCommit(batchResponses);
            }
            // now do phase1 commit on self if required
            if (localState != null) {
              localState.commitPhase1();
            }
          } finally {
            // finally wait for phase1 commit message replies
            response.waitForProcessedReplies();
            // TODO: SW: discuss TX GII issues with tailKeys (e.g. region GIIed
            // + queue region but commitPhase2 still did not go out so queue is
            // missing those events); ideally queue region should also become
            // transactional whose events are generated from primary and kept in
            // pending queue being flushed in background and those will be
            // applied to the queue in its own commit where TX will wait for
            // pending queue flush in phase1

            // Consolidate all the events received to be dispatched member-wise
            // as already initialized by finishRecipients so that it can be sent
            // again in commitPhase2
            final THashMap tailKeys = finishRecipients.eventsToBePublished;
            final Map<InternalDistributedMember,
                Map<String, TObjectLongHashMapDSFID>> eventsToBeDispatched;
            if (tailKeys != null && (eventsToBeDispatched = response
                .getEventsToBeDispatched()) != null) {
              for (Map<String, TObjectLongHashMapDSFID> events :
                  eventsToBeDispatched.values()) {
                tailKeys.putAll(events);
              }
              // send events to member only if:
              // a) member hosts a copy of bucket (empty maps for each bucket
              //    already filled in by finishRecipients)
              // b) member is not the one that sent the map in the first place
              //    (check from the response and send separate commit messages)
              finishRecipients.members.forEachKeyValue((mbr, data) -> {
                  THashMap memberData = (THashMap)data.get(0);
                  Map<String, TObjectLongHashMapDSFID> memberEvents =
                      eventsToBeDispatched.get(mbr);
                  if (memberEvents != null && !memberEvents.isEmpty()) {
                    // remove events that member itself sent
                    final Set<?> primaryBuckets = memberEvents.keySet();
                    for (Object b : primaryBuckets) {
                      memberData.remove(b);
                    }
                  }
                  // populate all other bucket data
                  final THashMap.EntryIterator miter = memberData
                      .entryIterator();
                  while (miter.hasNext()) {
                    Object map = tailKeys.get(miter.nextKey());
                    if (map != null) {
                      miter.setValueAtCurrent(map);
                    }
                  }
              });
            }
          }
        }
      }
      if (sendBatchOps) {
        // Flush any remaining batched messages; need to wait for replies to
        // detect any conflicts here
        ArrayList<TXBatchResponse> batchResponses = sendPendingOpsBeforeCommit(
            dm, localState, null, false);
        if (batchResponses != null) {
          waitForPendingOpsBeforeCommit(batchResponses);
        }
      }

      if (twoPhaseCommit) {
        if (phase1Msg == null) {
          // now do phase1 commit on self if required
          if (localState != null) {
            localState.commitPhase1();
            //addEventsToConsolidatedMap(consolidatedMap,localKeysToTailKeyMap);
          }
          // set the waitForPhase2Commit flag
          context.setWaitForPhase2Commit();
        }
      }
      else {
        waitForPendingRCs(dm);
      }

      // add all the events that to localContext so that it be sent to remote members in phase2
      
      // any preCommit actions are executed if commitPhase1 has been successful
      //TODO: For parallelWAN and HDFS we do not need to send precommit message.
      if (hasPreCommitActions()) {
        // send to remote nodes too since there might be pending remote
        // preCommit actions
        CommitPhase1Response response = null;
        if (numRecipients > 0) {
          TXRemoteCommitPhase1Message preCommitMsg = TXRemoteCommitPhase1Message
              .create(dm.getSystem(), dm, this, true, recipients);
          response = preCommitMsg.getReplyProcessor();
          preCommitMsg.setRecipients(recipients);
          preCommitMsg.processorId = response.register();
          dm.putOutgoing(preCommitMsg);
        }

        // local preCommit next
        preCommit();

        // now wait for remote replies
        if (response != null) {
          response.waitForProcessedReplies();
        }
      }

      // send separate message to new GII target nodes
      // this can be piggybacked with other messages above and wait for all at
      // the end, but we are not particularly concerned with the performance hit
      // of this since it will be an uncommon case
      sendNewGIINodeMessages(finishRecipients, dm, true);

      final TransactionObserver observer = this.txObserver;
      if (observer != null) {
        observer.beforeSend(this, false);
      }
    } catch (TransactionException te) {
      thr = te;
      throw te;
    } catch (Throwable t) {
      thr = t;
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
      if (t instanceof InterruptedException) {
        interrupted = true;
      }
      throw new TransactionException(t);
    } finally {
      this.lock.unlock();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (thr != null) {
        getCache().getCancelCriterion().checkCancelInProgress(thr);
        // mark operation end for state flush
        finishRecipients.endOperationSend(this);
        // rollback the TX at this point
        markTXInconsistent(thr);
        // for GemFireXD don't rollback here since it will invoke the abort
        if (!this.isGFXD) {
          context.clearTXState();
          rollback(callbackArg, context);
        }
      }
    }
    return context;
  }

  /*
  private String onlyNames(SetWithCheckpoint regions) {
    if (regions != null && regions.size() > 0) {
      StringBuilder sb = new StringBuilder();
      for(Object ob : regions) {
        String tmp = "";
        if (ob instanceof Bucket) {
          tmp = ((Bucket)ob).getName() + ":" + (ob instanceof ProxyBucketRegion ? "PBR" : "BKT");
        }
        sb.append(tmp);
        sb.append(" ");
      }
      return sb.toString();
    }
    return null;
  }
  */

  /**
   * check for all copies down for a region just before commit
   */
  protected void checkAllCopiesDown(DM dm)
      throws TransactionException {
    // need to check this only if there was some change in membership
    if (getViewId(dm) != this.initViewId) {
      final TransactionException failure = checkAvailableRegionCopies(null);
      if (failure != null) {
        markTXInconsistentNoLock(failure);
        throw failure;
      }
    }
  }

  final void commitPhase2(final TXManagerImpl.TXContext context,
      final Object callbackArg) throws TransactionInDoubtException {

    Throwable thr = null;
    boolean interrupted = false;
    final TXState localState = this.localTXState;

    // get the set of recipients for commit using the list of affected regions
    // already obtained in phase1 processing
    MemberToGIIRegions finishRecipients = context.getCommitRecipients();
    if (finishRecipients == null) {
      finishRecipients = getFinishMessageRecipients(true);
    }
    try {
      final DM dm = this.txManager.getDM();

      // check for new nodes joined or all copies down for a region just before
      // final commit message
      checkAllCopiesDown(dm);

      final UnifiedMap<InternalDistributedMember, ArrayList<Object>> recipientsData =
          finishRecipients.members;
      // set the commit time only once
      if (this.commitTime == 0) {
        setCommitVersionSources(dm.cacheTimeMillis(),
            finishRecipients.regionDiskVersionSources);
      }
      if (!recipientsData.isEmpty()) {
        TXRemoteCommitMessage.send(dm.getSystem(), dm, this, callbackArg,
            recipientsData.keySet(), finishRecipients, context, !this.syncCommits
                && !context.waitForPhase2Commit(), this.commitTime);
      }
      context.clearTXState();

      if (LOG_FINE) {
        final LogWriterI18n logger = dm.getLoggerI18n();
        logger.info(LocalizedStrings.DEBUG, "Sent commit to cohorts: "
            + recipientsData + ", TXState " + (localState != null ? "not null"
                : "null") + " for " + this.txId.shortToString());
      }
      final TransactionObserver observer = this.txObserver;
      if (observer != null) {
        // we are committed to completing the TX at this point (except for fatal
        // Errors) so any exceptions in observer are just logged
        try {
          observer.afterSend(this, false);
        } catch (Exception ex) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          if (logger.warningEnabled()) {
            logger.warning(LocalizedStrings.DEBUG, ex);
          }
          thr = ex;
        }
      }
      if (localState != null) {
        commit(localState, callbackArg);
      }
      else {
        // remove from hosted txState list neverthless
        this.txManager.removeHostedTXState(this.txId, Boolean.TRUE);
        signalLocalTXCommit();
      }
      /*
      // we have to wait for batch responses to ensure ordered delivery of
      // transactions and so that any new transactions may not show phantom
      // conflicts etc.
      // required only if the optimization of not waiting for responses for
      // "preferred" node mentioned above is implemented
      if (batchResponses != null) {
        waitForPendingOps(batchResponses, true);
      }
      */
    } catch (TransactionException te) {
      thr = te;
      throw te;
    } catch (Throwable t) {
      thr = t;
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
      if (t instanceof InterruptedException) {
        interrupted = true;
      }
      throw new TransactionInDoubtException(t);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      // mark operation end for state flush
      finishRecipients.endOperationSend(this);
      if (thr == null) {
        cleanup();
      }
      else {
        getCache().getCancelCriterion().checkCancelInProgress(thr);
        // rollback the TX at this point
        markTXInconsistent(thr);
        // for GemFireXD don't rollback here since it will invoke the abort
        if (!this.isGFXD || !isCoordinator()) {
          context.clearTXState();
          rollback(callbackArg, context);
        }
      }
    }
  }

  final void setCommitVersionSources(long commitTime, THashMap versionSources) {
    this.commitTime = commitTime;
    this.regionDiskVersionSources = versionSources;
  }

  final boolean waitForPendingRCs(final DM dm) {
    // Wait for any pending result collectors to complete execution.
    // We could use pendingTXId kind of approach but that looks like quite a
    // lot of work for very little gain.
    final int numRCs = this.pendingResultCollectors.size();
    if (numRCs > 0) {
      for (ResultCollector<?, ?> rc : this.pendingResultCollectors) {
        // any exceptions in getResult here are definitely non-fatal since
        // user was not interested in the result or any exceptions therein
        // release the lock before the wait since addResult will also wait
        // on the lock
        this.lock.unlock();
        try {
          rc.getResult();
        } catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          SystemFailure.checkFailure();

          if (LOG_FINE) {
            final LogWriterI18n logger = dm.getLoggerI18n();
            logger.info(LocalizedStrings.DEBUG, "Received exception in "
                + "getResult for ResultCollector " + rc + " for "
                + this.txId.shortToString(), t);
          }
        } finally {
          this.lock.lock();
        }
      }
      this.pendingResultCollectors.clear();
      return true;
    }
    return false;
  }

  public final void commit(final Object callbackArg)
      throws TransactionException {
    final TXManagerImpl.TXContext context = commitPhase1(callbackArg);
    commitPhase2(context, callbackArg);
  }

  protected final void commit(final TXState localState,
      final Object callbackArg) throws TransactionException {

    // do not rollback due to member departed etc past this point
    while (true) {
      final State state = this.state.get();
      if (state == State.CLOSED || state == State.ROLLBACK_STARTED
          || state == State.INCONSISTENT) {
        // cannot commit if rollback already started or already closed
        return;
      }
      // if commit phase2 already started then wait for it complete
      if (state == State.COMMIT_PHASE2_STARTED) {
        waitForLocalTXCommit(null, ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT);
        return;
      }
      if (this.state.compareAndSet(state, State.COMMIT_PHASE2_STARTED)) {
        break;
      }
    }

    final TXManagerImpl txMgr = getTxMgr();
    boolean commitSuccessful = false;
    try {
      if (LOG_FINEST) {
        final LogWriterI18n logger = txMgr.getLogger();
        logger.info(LocalizedStrings.DEBUG,
            "TX: Committing: " + this.txId.shortToString());
      }
      if (localState == null) { // indicates remote commit
        final TXState txState = this.localTXState;
        if (txState != null) {
          txMgr.commit(txState, callbackArg, TXManagerImpl.FULL_COMMIT, null,
              true/*remote to coordinator*/);
          cleanup();
        }
      }
      else {
        localState.commit(callbackArg);
      }
      commitSuccessful = true;
      onCommit(localState, callbackArg);
    } finally {
      if (commitSuccessful) {
        txMgr.removeHostedTXState(this.txId, Boolean.TRUE);
        signalLocalTXCommit();
      }
    }
    if (TRACE_EXECUTE) {
      final LogWriterI18n logger = txMgr.getLogger();
      logger.info(LocalizedStrings.DEBUG,
          "TX: Committed: " + this.txId.shortToString() + " and commit was: "
              + (commitSuccessful == true ? "successful" : "unsuccessful"));
    }
  }

  public final void waitForLocalTXCommit(final TXId newTXId, long maxWaitMs) {
    final TXManagerImpl txMgr = getTxMgr();
    while (isInProgress()) {
      if (LOG_FINE) {
        txMgr.getLogger().info(LocalizedStrings.DEBUG, "In TX: "
            + (newTXId != null ? newTXId.shortToString() : "non TX")
            + " waiting for local transaction " + this.txId.shortToString()
            + " to end for " + TXCOMMIT_WAIT_RETRY_MILLIS
            + "ms, maxWaitMs=" + maxWaitMs);
      }
      boolean interrupted = Thread.interrupted();
      Throwable thr = null;
      this.waitingForCommit = true;
      synchronized (this) {
        try {
          if (isInProgress()) {
            this.wait(TXCOMMIT_WAIT_RETRY_MILLIS);
            if (maxWaitMs > 0) {
              if (maxWaitMs <= TXCOMMIT_WAIT_RETRY_MILLIS) {
                break;
              }
              else {
                maxWaitMs -= TXCOMMIT_WAIT_RETRY_MILLIS;
              }
            }
          }
        } catch (InterruptedException ie) {
          interrupted = true;
          thr = ie;
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
            getCache().getCancelCriterion().checkCancelInProgress(thr);
          }
        }
      }
    }
    if (LOG_FINE) {
      txMgr.getLogger().info(LocalizedStrings.DEBUG, "In TX: " + newTXId
          + " ending wait for local transaction " + this.txId.shortToString()
          + " with TX inProgress=" + isInProgress());
    }
  }

  /**
   * Any actions to be taken before commit. Failure in these will be fatal to
   * the transaction itself, and it will be rolled back implicitly.
   */
  public void preCommit() {
    // nothing to be done here yet
  }

  /**
   * Returns true if {@link #preCommit()} needs to be invoked on the coordinator
   * and cohorts (leading to a 2-phase commit).
   */
  public boolean hasPreCommitActions() {
    return false;
  }

  /** for any actions on successful commit */
  protected void onCommit(TXState localState, Object callbackArg) {
    // nothing by default
  }

  /** for any actions on rollback */
  protected void onRollback(TXState localState, Object callbackArg) {
    // nothing by default
  }

  private final void signalLocalTXCommit() {
    // the choice to not take the lock before checking the flag is a
    // deliberate one; in the worst case TXStateProxy#waitForLocalTXCommit
    // will end up waiting for sometime unnecessarily
    if (this.waitingForCommit) {
      synchronized (this) {
        this.waitingForCommit = false;
        this.notifyAll();
      }
    }
  }

  private static final TObjectLongProcedure endBatchSend =
      new TObjectLongProcedure() {
    @Override
    public boolean execute(Object o, long viewVersion) {
      if (viewVersion != -1) {
        DistributionAdvisor advisor = (DistributionAdvisor)o;
        advisor.endOperation(viewVersion);
        if (LOG_VERSIONS) {
          LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
          if (logger != null) {
            logger.info(LocalizedStrings.DEBUG,
                "TXBatch: done dispatching operation for "
                    + advisor.getAdvisee().getFullPath() + " in view version "
                    + viewVersion);
          }
        }
      }
      return true;
    }
  };
  /**
   * @see TXStateInterface#flushPendingOps(DM)
   */
  public final void flushPendingOps(DM dm) throws TransactionException {
    // TODO: TX: avoid this if this is a top-level function execution on
    // coordinator only; if this is done then need comprehensive tests to
    // check functions and nested functions on coordinator and otherwise
    // TODO: TX: another case that can be well optimized; if the source
    // node itself is the only batch flush target (in particular coordinator)
    // then send it as part of ReplyMessage (e.g. in TXChanges)
    final TXState localState = this.localTXState; // volatile read
    if (localState == null || localState.numPendingOps() == 0) {
      return;
    }
    final ArrayList<TXBatchResponse> batchResponses;
    if (dm == null) {
      dm = this.txManager.getDM();
    }

    TObjectLongHashMapWithIndex versions = new TObjectLongHashMapWithIndex();
    this.lock.lock();
    try {
      batchResponses = sendPendingOps(dm, localState, versions, null, false);
    } finally {
      if (!versions.isEmpty()) {
        versions.forEachEntry(endBatchSend);
      }
      this.lock.unlock();
    }
    if (batchResponses != null) {
      waitForPendingOps(batchResponses, false);
    }
  }

  private final ArrayList<TXBatchResponse> sendPendingOpsBeforeCommit(
      final DM dm, final TXState localState,
      final AbstractOperationMessage postMessage, boolean conflictWithEX) {
    // no locking required since we are assured of single thread access
    // at commit time
    // TODO: TX: one possible optimization can be to not wait for replies of
    // batch responses at this point if we are the "preferred" node for all
    // regions/buckets and instead do it at the end; however, for that case
    // the locking has to be in wait-mode to accomodate messages from other
    // txns that may not have conflicted on this node yet -- also lookout
    // for possible deadlocks with such an approach

    // need to wait for flush of pending ops before proceeding with commit
    final ArrayList<TXBatchResponse> batchResponses = sendPendingOps(dm,
        localState, null, postMessage != null ? Collections.singletonList(
            postMessage) : null, conflictWithEX);
    if (LOG_FINE) {
      if (batchResponses != null) {
        final LogWriterI18n logger = dm.getLoggerI18n();
        final THashSet batchRecipients = new THashSet();
        for (TXBatchResponse br : batchResponses) {
          batchRecipients.add(br.recipient);
        }
        logger.info(LocalizedStrings.DEBUG, "Sent pending operations before "
            + "commit to cohorts: " + batchRecipients + ", TXState "
            + (localState != null ? "not null" : "null") + " for "
            + this.txId.shortToString());
      }
    }
    return batchResponses;
  }

  private final boolean doSendPendingOps(final TXState localState) {
    return localState != null && localState.numPendingOps() > 0;
  }

  private final void waitForPendingOpsBeforeCommit(
      ArrayList<TXBatchResponse> batchResponses) throws TransactionException {
    // release the lock before doing the wait
    this.lock.unlock();
    try {
      waitForPendingOps(batchResponses, true);
    } finally {
      this.lock.lock();
    }
  }

  @SuppressWarnings("unchecked")
  private final ArrayList<TXBatchResponse> sendPendingOps(final DM dm,
      final TXState localState, final TObjectLongHashMapWithIndex versions,
      final List<AbstractOperationMessage> postMessages,
      final boolean conflictWithEX) throws TransactionException {
    int numPending;
    if ((numPending = localState.numPendingOps()) > 0) {
      if (LOG_FINE) {
        final LogWriterI18n logger = getTxMgr().getLogger();
        logger.info(LocalizedStrings.DEBUG, "Flushing " + numPending
            + " pending operations for " + this.txId.shortToString());
      }
      final ArrayList<TXBatchResponse> batchResponses =
        new ArrayList<TXBatchResponse>(numPending);
      final UnifiedMap<InternalDistributedMember, ArrayList<Object>> pendingOpsMap =
          new UnifiedMap<>();
      final UnifiedMap<InternalDistributedMember, ArrayList<LocalRegion>> pendingOpsRegionsMap =
          new UnifiedMap<>();
      final UnifiedMap<LocalRegion, Set<InternalDistributedMember>> pendingRegions =
          new UnifiedMap<>();
      ArrayList<Object> pendingOps;
      ArrayList<LocalRegion> pendingOpsRegions;
      Set<InternalDistributedMember> recipients;
      Iterator<Object> drItr = localState.pendingOpsRegions.iterator();
      for (Object op : localState.pendingOps) {
        final DistributedRegion dataRegion = (DistributedRegion)drItr.next();
        if (op != null) {
          // mark operation for state flush
          if (versions != null) {
            // put in the map only once (and also avoid calling startOperation
            // more than once for a region)
            DistributionAdvisor advisor = dataRegion.getDistributionAdvisor();
            int insertionIndex = versions.getInsertionIndex(advisor);
            if (insertionIndex >= 0) {
              long viewVersion = -1;
              viewVersion = advisor.startOperation();
              if (viewVersion != -1) {
                versions.putAtIndex(advisor, viewVersion, insertionIndex);
                if (LOG_VERSIONS) {
                  getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
                      "TXBatch: dispatching operation for "
                          + dataRegion.getFullPath() + " in view version "
                          + viewVersion);
                }
              }
            }
          }
          // find the recipients for this entry
          // cache the recipients per region
          if ((recipients = pendingRegions.get(dataRegion)) == null) {
            if (dataRegion.isUsedForPartitionedRegionBucket()) {
              final PartitionedRegion pr = dataRegion.getPartitionedRegion();
              recipients = getOtherMembersForOperation(pr,
                  (BucketRegion)dataRegion, null, op, true);
            }
            else {
              recipients = getOtherMembersForOperation(null, dataRegion, null,
                  op, true);
            }
            pendingRegions.put(dataRegion, recipients);
          }
          for (InternalDistributedMember member : recipients) {
            if ((pendingOps = pendingOpsMap.get(member)) == null) {
              pendingOps = new ArrayList<>();
              pendingOpsRegions = new ArrayList<>();
              pendingOpsMap.put(member, pendingOps);
              pendingOpsRegionsMap.put(member, pendingOpsRegions);
            }
            else {
              pendingOpsRegions = pendingOpsRegionsMap.get(member);
            }
            pendingOps.add(op);
            pendingOpsRegions.add(dataRegion);
          }
          numPending--;
        }
      }
      if (numPending != 0) {
        Assert.fail("Unexpected pending operations remaining " + numPending
            + " for " + toString() + " at the end of iteration.");
      }
      pendingOpsMap.forEachKeyValue(new Procedure2<InternalDistributedMember,
          ArrayList<Object>>() {
        final TXManagerImpl.TXContext context = TXManagerImpl
            .currentTXContext();
        @Override
        public final void value(InternalDistributedMember m, ArrayList<Object> ops) {
          batchResponses.add(TXBatchMessage.send(dm, m, TXStateProxy.this,
              this.context, ops, pendingOpsRegionsMap.get(m),
              postMessages, conflictWithEX));
        }
      });
      // cleanup pending information for the entries sent in the batch
      TXEntryState txes;
      for (Object op : localState.pendingOps) {
        if (op instanceof TXEntryState) {
          txes = (TXEntryState)op;
          if (txes.isPendingForBatch()) {
            txes.clearPendingForBatch();
          }
        }
      }
      localState.clearPendingOps();
      return batchResponses;
    }
    return null;
  }

  private final void waitForPendingOps(
      final ArrayList<TXBatchResponse> batchResponses, final boolean forCommit)
      throws TransactionException {
    if (batchResponses.size() > 0) {
      // check for any conflict or other fatal exceptions
      TransactionBatchException batchEx = null;
      for (TXBatchResponse reply : batchResponses) {
        batchEx = accumulateException(reply, reply.recipient, forCommit,
            batchEx);
      }
      if (batchEx != null) {
        if (forCommit) {
          // avoid lock since its already taken during commit
          markTXInconsistentNoLock(batchEx);
        }
        else {
          markTXInconsistent(batchEx);
        }
        batchEx.throwException();
      }
    }
  }

  protected final void sendNewGIINodeMessages(
      final MemberToGIIRegions finishRecipients, final DM dm,
      final boolean forCommit) {
    UnifiedMap<InternalDistributedMember, ArrayList<Object>> members;
    if (finishRecipients.hasUninitialized
        && !(members = finishRecipients.members).isEmpty()) {
      members.forEachKeyValue((m, l) -> {
        if (l != null) {
          TXNewGIINode.send(dm.getSystem(), dm, TXStateProxy.this, m, l, forCommit);
        }
      });
    }
  }

  /**
   * This should be invoked by TXState when non-null else from commit/rollback
   * here.
   */
  protected void cleanup() {
    this.hasCohorts = false;
    this.isDirty = false;
    this.hasReadOps = false;
    this.columnRolloverDisabled = false;
    if (!this.pendingResultCollectors.isEmpty()) {
      this.pendingResultCollectors.clear();
    }

    this.attemptWriteLock(-1);
    this.regions.clear();
    this.releaseWriteLock();

    this.commitTime = 0L;
    this.state.set(State.CLOSED);
  }

  @Override
  public void cleanupCachedLocalState(boolean hasListeners) {
    this.lock.lock();
    try {
      // mark TXStateProxy as closed before clearing the local TXState so that
      // any new TXState cannot be created
      this.state.set(State.CLOSED);
      final TXState localState = this.localTXState;
      if (localState != null) {
        localState.cleanupCachedLocalState(hasListeners);
        this.localTXState = null;
      }
    } finally {
      this.lock.unlock();
    }
    this.txManager.cleanup(this.txId);
  }

  /** cleanup indexes for one entry failed during write etc. */
  protected void cleanupIndexEntry(TXRegionState txr, TXEntryState tx,
      Operation op) {
  }

  /** cleanup all index related artifacts */
  protected void updateIndexes(boolean rollback,
      THashMapWithKeyPair transactionalIndexInfo,
      THashMapWithKeyPair toBeReinstatedIndexInfo,
      THashMapWithKeyPair unaffectedIndexInfo
      ) {
    // for GemFireXD indexes. nothing to be done here.
  }

  public final void destroyExistingEntry(EntryEventImpl event,
      boolean cacheWrite, Object expectedOldValue)
      throws EntryNotFoundException {
    checkTXState();
    final LocalRegion r = event.getLocalRegion();

    if (r.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)r;
      final ProxyBucketRegion pbr = PartitionedRegionHelper
          .getProxyBucketRegion(pr, event);
      try {
        PartitionedRegionHelper.initializeBucket(pr, pbr, true);
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "destroyExistingEntry PR "
              + pr.getFullPath() + ", bucket region " + pbr.getFullPath()
              + (LOG_FINEST ? ", event=" + event : ", key=" + event.getKey())
              + " for " + this.txId.shortToString());
        }
        final boolean toSelf = pbr.getCreatedBucketRegion() != null;
        final int bucketRedundancy = pbr.getBucketRedundancy();
        final boolean hasPossibleRecipients = bucketRedundancy > 0 || !toSelf
            || !pbr.isHosting();
        performOp(r, pr, pbr, event, toSelf, hasPossibleRecipients,
            performPRDestroy, expectedOldValue, 0L, cacheWrite, 0);
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(event.getKey(), pbr.getFullPath()),
                pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(e.getOrigin()));
        re.initCause(e);
        throw re;
      } catch (RemoteOperationException re) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", re);
      }
    }
    else {
      try {
        if (r.getScope().isLocal()) {
          performDRDestroy.operateLocally(getTXStateForWrite(), event,
              expectedOldValue, 0L, false, 0);
          return;
        }
        final DistributedRegion dr = (DistributedRegion)r;
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "destroyExistingEntry DR "
              + dr.getFullPath() + (LOG_FINEST ? ", event=" + event : ", key="
                  + event.getKey()) + " for " + this.txId.shortToString());
        }
        final boolean toSelf = r.getDataPolicy().withStorage();
        performOp(r, null, dr, event, toSelf, true, performDRDestroy,
            expectedOldValue, 0L, cacheWrite, 0);
      } catch (EntryNotFoundException enfe) {
        throw enfe;
      } catch (RegionDestroyedException rde) {
        throw new RegionDestroyedException(toString(), event.getLocalRegion()
            .getFullPath());
      } catch (RemoteOperationException roe) {
        throw new TransactionDataNodeHasDepartedException(roe);
      } catch (ForceReattemptException fre) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", fre);
      }
    }
  }

  private Set<InternalDistributedMember> getOtherMembersForOperation(
      final PartitionedRegion pr, final CacheDistributionAdvisee dataRegion,
      final EntryEventImpl event, final Object txEntry,
      final boolean forBatch) {
    // find the recipients for this operation
    if (pr != null) {
      final BucketAdvisor advisor = ((Bucket)dataRegion).getBucketAdvisor();
      final ProxyBucketRegion pbr = advisor.getProxyBucketRegion();
      if (advisor.getBucketRedundancy() >= 0) {
        return pbr.getBucketAdvisor().adviseCacheOp();
      }
      final int bucketId = pbr.getBucketId();
      pr.createBucket(bucketId, 0, null);
      pr.getNodeForBucketWrite(bucketId, null);
      return pbr.getBucketAdvisor().adviseCacheOp();
    }
    // check for distributed region
    final CacheDistributionAdvisor advisor = dataRegion
        .getCacheDistributionAdvisor();
    if (event != null) {
      final Operation op = event.getOperation();
      assert dataRegion instanceof DistributedRegion: "unexpected dataRegion "
          + dataRegion;
      // for creates+batch send to only replicates
      if (op.isCreate()) {
        if (forBatch) {
          return advisor.adviseCreateNullValue();
        }
        else {
          return advisor.adviseUpdate(event);
        }
      }
      else if (op.isUpdate()) {
        return advisor.adviseUpdate(event);
      }
      return advisor.adviseCacheOp();
    }
    else if (txEntry != null) {
      if (txEntry instanceof TXEntryState) {
        final TXEntryState txes = (TXEntryState)txEntry;
        if (txes.isOpCreate()) {
          if (forBatch) {
            return advisor.adviseCreateNullValue();
          }
        }
        if (txes.isOpPut() || txes.isOpCreate()) {
          // check for null value case as in adviseUpdate
          if (txes.hasPendingValue() || txes.bulkOp) {
            return advisor.adviseCacheOp();
          }
          return advisor.adviseCreateNullValue();
        }
      }
      return advisor.adviseCacheOp();
    }
    return Collections.emptySet();
  }

  public final long getBeginTime() {
    return this.beginTime;
  }

  public final GemFireCacheImpl getCache() {
    return this.txManager.getCache();
  }

  public final InternalDistributedMember getCoordinator() {
    return this.coordinator;
  }

  public final boolean isCoordinator() {
    return (this.coordinator == this.self);
  }

  public final int getChanges() {
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState.getChanges();
    }
    return 0;
  }

  @Override
  public final Object getDeserializedValue(Object key, Object callbackArg,
      LocalRegion localRegion, boolean updateStats, boolean disableCopyOnRead,
      boolean preferCD, TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) {
    final TXState localState = getTXStateForRead();
    if (localState != null) {
      return localState.getDeserializedValue(key, callbackArg, localRegion,
          updateStats, disableCopyOnRead, preferCD, lockState, clientEvent,
          allowTombstones, allowReadFromHDFS);
    }
    return localRegion.getDeserializedValue(null, key, callbackArg,
        updateStats, disableCopyOnRead, preferCD, this, clientEvent,
        allowTombstones, allowReadFromHDFS);
  }

  // primary key based read operations come here..
  public final Object getLocally(Object key, Object callbackArg, int bucketId,
      LocalRegion localRegion, boolean doNotLockEntry, boolean localExecution,
      TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) throws DataLocationException {
    final TXState localState = getTXStateForRead();
    if (localState != null) {
      return localState.getLocally(key, callbackArg, bucketId, localRegion,
          doNotLockEntry, localExecution, lockState, clientEvent,
          allowTombstones, allowReadFromHDFS);
    }
    return localRegion.getSharedDataView().getLocally(key, callbackArg,
        bucketId, localRegion, doNotLockEntry, localExecution, lockState,
        clientEvent, allowTombstones, allowReadFromHDFS);
  }

  private final InternalDistributedMember pickTheTargetForGet(
      final PartitionedRegion pr, final int bucketId, final Operation op,
      final String opStr) {
    final boolean readCanStartTX = getLockingPolicy().readCanStartTX();
    // SH locks for READ_COMMITTED will be zero-duration locks so no need for a
    // TXState
    final InternalDistributedMember target;
    if (readCanStartTX) {
      final ProxyBucketRegion pbr = pr.getRegionAdvisor()
          .getProxyBucketArray()[bucketId];
      addAffectedRegion(pbr);
      target = pr.getOrCreateNodeForInitializedBucketRead(bucketId, false);
    }
    else {
      target = pr.getNodeForInitializedBucketRead(bucketId, false);
    }
    if (LOG_FINEST) {
      final LogWriterI18n logger = pr.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "Selected member for PR TX " + op
          + '(' + opStr + ") operation in bucket " + pr.getBucketName(
              bucketId) + " on " + target + " for "
          + this.txId.shortToString());
    }
    if (this.self.equals(target)) {
      return this.self;
    }
    return target;
  }

  private final InternalDistributedMember pickTheTargetForGet(
      final PartitionedRegion pr, final Object key, final Object callbackArg,
      final Operation op, final String opStr) {
    final int bucketId = PartitionedRegionHelper.getHashKey(pr,
        Operation.GET_ENTRY, key, null, callbackArg);
    return pickTheTargetForGet(pr, bucketId, op, opStr);
  }

  private final InternalDistributedMember pickTheTargetForGet(
      final LocalRegion r, final String opStr) {
    final InternalDistributedMember target;
    if (r.getScope().isLocal()
        || (r.getDataPolicy().withReplication() && (r.basicGetLoader() != null || !r
            .hasNetLoader(((DistributedRegion)r).getCacheDistributionAdvisor())))) {
      target = this.self;
    }
    else {
      // SH locks for READ_COMMITTED will be zero-duration locks so no need for
      // a TXState
      if (getLockingPolicy().readCanStartTX()) {
        addAffectedRegion(r);
      }
      target = ((DistributedRegion)r).getRandomReplicate();
    }
    if (LOG_FINEST) {
      final LogWriterI18n logger = r.getLogWriterI18n();
      logger.info(LocalizedStrings.DEBUG, "Selected member for TX " + opStr
          + " operation in region " + r.getFullPath() + " on " + target
          + " for " + this.txId.shortToString());
    }
    return target;
  }

  public final Region.Entry<?, ?> getEntry(final Object key,
      final Object callbackArg, final LocalRegion region,
      final boolean allowTombstones) {
    return getEntry(null, key, callbackArg, region, false, false,
        allowTombstones);
  }

  public final Region.Entry<?, ?> accessEntry(final Object key,
      final Object callbackArg, final LocalRegion localRegion) {
    return getEntry(null, key, callbackArg, localRegion, true, false, false);
  }

  // TODO: Suranjan: should snapshot apply to these operations
  private Region.Entry<?, ?> getEntry(KeyInfo keyInfo, Object key,
      Object callbackArg, final LocalRegion region, final boolean access,
      final boolean forIterator, final boolean allowTombstones) {
    if (region.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)region;
      if (keyInfo == null) {
        // creating a KeyInfo here to avoid possible multiple resolver calls
        // to get the bucketId
        keyInfo = pr.getKeyInfo(key, callbackArg);
      }
      final InternalDistributedMember target = pickTheTargetForGet(pr,
          keyInfo.getBucketId(), Operation.GET_ENTRY, "getEntry");
      try {
        if (target == this.self) {
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            return localState.getEntry(keyInfo, pr, access, allowTombstones);
          }
          return pr.txGetEntry(keyInfo, access, this, allowTombstones);
        }
        else if (target != null) {
          return pr.getEntryRemotely(target, this, keyInfo.getBucketId(),
              keyInfo.getKey(), access, allowTombstones);
        }
        return null;
      } catch (EntryNotFoundException enfe) {
        return null;
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(key, (pr.getFullPath() + ':'
                    + keyInfo.getBucketId())), pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(target));
        re.initCause(e);
        throw re;
      }
    }
    else {
      // DR getEntry is always a local operation
      final TXState localState = getTXStateForRead();
      if (forIterator) {
        if (localState != null) {
          return localState.getEntryForIterator(keyInfo, region,
              allowTombstones);
        }
        return region.getSharedDataView().getEntryForIterator(keyInfo, region,
            allowTombstones);
      }
      if (key == null) {
        key = keyInfo.getKey();
        callbackArg = keyInfo.getCallbackArg();
      }
      if (localState != null) {
        return localState.getEntry(key, callbackArg, region, allowTombstones);
      }
      return region.nonTXGetEntry(key, callbackArg, access, allowTombstones);
    }
  }

  @Retained
  public final EntryEventImpl newEntryEventImpl() {
    EntryEventImpl event = new EntryEventImpl();
    event.setEntryLastModified(this.commitTime);
    return event;
  }

  @Override
  public final TXEvent getEvent() {
    return new TXEvent(getLocalTXState(), getTransactionId(), getCache());
  }

  @Override
  public final long getCommitTime() {
    return this.commitTime;
  }

  public List<?> getEvents() {
    throw new InternalGemFireError("not expected to be invoked");
  }

  /**
   * Get the set of regions + hosted bucket regions.
   * 
   * ONLY FOR TESTS.
   */
  @SuppressWarnings("unchecked")
  public final Collection<LocalRegion> getRegions() {
    final Collection<?> allRegions;
    // coordinator has the record of all regions
    this.attemptReadLock(-1);
    try {
      if (isCoordinator()) {
        allRegions = this.regions;
      }
      else {
        final TXState localState = this.localTXState;
        if (localState != null) {
          allRegions = localState.getRegions();
        }
        else {
          allRegions = Collections.emptySet();
        }
      }
      final THashSet regs = new THashSet(allRegions.size());
      for (Object owner : allRegions) {
        LocalRegion r;
        if (owner instanceof Bucket) {
          final Bucket bucket = (Bucket)owner;
          r = bucket.getHostedBucketRegion();
        }
        else {
          r = (LocalRegion)owner;
        }
        if (r != null) {
          regs.add(r);
        }
      }
      return regs;
    } finally {
      this.releaseReadLock();
    }
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

  public final void invalidateExistingEntry(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry) {
    checkTXState();
    final LocalRegion r = event.getLocalRegion();

    // create the flags for performOp()
    int flags = 0;
    if (invokeCallbacks) flags |= INVOKE_CALLBACKS;
    if (forceNewEntry) flags |= FORCE_NEW_ENTRY;

    if (r.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)r;
      final ProxyBucketRegion pbr = PartitionedRegionHelper
          .getProxyBucketRegion(pr, event);
      try {
        PartitionedRegionHelper.initializeBucket(pr, pbr, true);
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "invalidateExistingEntry PR "
              + pr.getFullPath() + ", bucket region " + pbr.getFullPath()
              + (LOG_FINEST ? ", event=" + event : ", key=" + event.getKey())
              + " for " + this.txId.shortToString());
        }
        final boolean toSelf = pbr.getCreatedBucketRegion() != null;
        final int bucketRedundancy = pbr.getBucketRedundancy();
        final boolean hasPossibleRecipients = bucketRedundancy > 0 || !toSelf
            || !pbr.isHosting();
        performOp(r, pr, pbr, event, toSelf, hasPossibleRecipients,
            performPRInvalidate, null, 0L, false, flags);
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(event.getKey(), pbr.getFullPath()),
                pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(e.getOrigin()));
        re.initCause(e);
        throw re;
      } catch (RemoteOperationException re) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", re);
      }
    }
    else {
      try {
        if (r.getScope().isLocal()) {
          performDRInvalidate.operateLocally(getTXStateForWrite(), event, null,
              0L, false, flags);
          return;
        }
        final DistributedRegion dr = (DistributedRegion)r;
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "invalidateExistingEntry DR "
              + dr.getFullPath() + (LOG_FINEST ? ", event=" + event : ", key="
                  + event.getKey()) + " for " + this.txId.shortToString());
        }
        final boolean toSelf = r.getDataPolicy().withStorage();
        performOp(r, null, dr, event, toSelf, true, performDRInvalidate, null,
            0L, false, flags);
      } catch (RemoteOperationException roe) {
        throw new TransactionDataNodeHasDepartedException(roe);
      } catch (ForceReattemptException fre) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", fre);
      }
    }
  }

  @Override
  public final boolean txPutEntry(final EntryEventImpl event, boolean ifNew,
      boolean requireOldValue, boolean checkResources,
      final Object expectedOldValue) {
    return putEntry(event, ifNew, false, expectedOldValue, requireOldValue,
        true, 0L, false);
  }

  @Override
  public final boolean isInProgress() {
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState.isInProgress() || (this.state.get() != State.CLOSED);
    }
    return (this.state.get() != State.CLOSED);
  }

  /**
   * Return true if this transaction has any pending write operations, and false
   * otherwise.
   */
  public final boolean isDirty() {
    // we don't have write ops in snapshot isolation with isolation level NONE
    return this.isDirty /*&& (getLockingPolicy() != LockingPolicy.SNAPSHOT)*/;
  }

  /**
   * Return true if this transaction has acquired read locks (for
   * REPEATABLE_READ isolation).
   */
  public final boolean hasReadOps() {
    return this.hasReadOps;
  }

  final void markDirty() {
    if (!this.isDirty) {
      this.isDirty = true;
    }
  }

  /**
   * Return true if this transaction has any pending read or write operations,
   * and false otherwise.
   */
  public final boolean hasOps() {
    return (this.isDirty || this.hasReadOps)
        && (this.state.get() != State.CLOSED);
  }

  final void markHasReadOps() {
    if (!this.hasReadOps) {
      this.hasReadOps = true;
    }
  }

  public final TXRegionState readRegion(final LocalRegion r) {
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState.readRegion(r);
    }
    return null;
  }

  public final void rmRegion(final LocalRegion r) {
    final TXState localState = this.localTXState;
    if (localState != null) {
      localState.rmRegion(r);
    }
  }

  public final void cleanSnapshotEntriesForRegion(LocalRegion r) {
    final TXState localState = this.localTXState;
    if (localState != null) {
      localState.cleanSnapshotEntriesForRegion(r);
    }
  }

  public final void rollback(final Object callbackArg)
      throws TransactionException {
    rollback(callbackArg, null);
  }

  private final void rollback(final Object callbackArg,
      final TXManagerImpl.TXContext context) throws TransactionException {
    final TXManagerImpl txMgr = getTxMgr();
    if (LOG_FINEST) {
      final LogWriterI18n logger = txMgr.getLogger();
      logger.info(LocalizedStrings.DEBUG, "TX: rolling back: " + this.txId);
    }
    TXState localState = this.localTXState;

    while (true) {
      final State state = this.state.get();
      if (state == State.CLOSED || state == State.COMMIT_PHASE2_STARTED) {
        throw new IllegalTransactionStateException(LocalizedStrings
            .TXManagerImpl_THREAD_DOES_NOT_HAVE_AN_ACTIVE_TRANSACTION
                .toLocalizedString());
      }
      if (state == State.INCONSISTENT) {
        break;
      }
      // if rollback already started due to some reason (e.g. coordinator
      // departed) then wait for it to finish and then return
      if (state == State.ROLLBACK_STARTED) {
        waitForLocalTXCommit(null,
            Math.max(5000L, ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT / 10));
        return;
      }
      if (this.state.compareAndSet(state, State.ROLLBACK_STARTED)) {
        break;
      }
    }
    // check that rollback is invoked only from the coordinator
    if (!isCoordinator()) {
      throw new IllegalTransactionStateException(
          LocalizedStrings.TXManagerImpl_FINISH_NODE_NOT_COORDINATOR
              .toLocalizedString(getCoordinator()));
    }

    /*
    if (!allMembers.isEmpty()) {
      TXGetChanges.send(dm.getSystem(), dm, this, this.inconsistentThr,
          allMembers);
    }
    */

    /*
    // send separate message to new GII target nodes
    // this can be piggybacked with other messages below and wait for all at the
    // end, but we are not particularly concerned with the performance hit of
    // this since it will be an uncommon case
    sendNewGIINodeMessages(finishRecipients, dm, false);
    */

    final TransactionObserver observer = this.txObserver;
    if (observer != null) {
      observer.beforeSend(this, true);
    }

    final DM dm = this.txManager.getDM();

    // prevent new members from being registered while rollback is in progress
    // since rollback may have to target the new incoming member
    final VMIdAdvisor advisor = getCache().getSystem().getVMIdAdvisor();
    final StoppableReentrantReadWriteLock mlock = advisor.getNewMemberLock();
    mlock.readLock().lock();
    try {
      // refresh the TX state from all nodes in the system since it is possible
      // that we failed to get proper TXChanges due to node failure while
      // shipping TXChanges (see #48021, #49509 etc)
      Set<InternalDistributedMember> recipients = getRollbackTargets(advisor);

      // local TXState may have changed
      localState = this.localTXState;
      ReplyProcessor21 response = null;
      try {
        if (localState != null) {
          // rollback on local node first to avoid possible deadlocks due to too
          // many threads waiting on locks held by this TX (#44743)
          rollback(localState, callbackArg);
        }
      } catch (PrimaryBucketException pbe) {
        // ignore this
      } catch (IllegalTransactionStateException ite) {
        // ignore already rolled back transaction
      } catch (TransactionStateReadOnlyException tsre) {
        // ignore already rolled back transaction
      } catch (Exception e) {
        getCache().getCancelCriterion().checkCancelInProgress(e);
        throw new TransactionException(
            LocalizedStrings.TXStateProxy_ROLLBACK_ON_NODE_0_FAILED
                .toLocalizedString(recipients), e);
      } finally {
        if (!recipients.isEmpty()) {
          final InternalDistributedSystem system = getCache()
              .getDistributedSystem();
          response = TXRemoteRollbackMessage.send(system, dm, this,
              callbackArg, recipients);
        }
      }
      if (LOG_FINE) {
        final LogWriterI18n logger = getTxMgr().getLogger();
        logger.info(LocalizedStrings.DEBUG, "Sent rollback to cohorts: "
            + recipients + ", TXState "
            + (localState != null ? "not null" : "null") + " for " + this.txId);
      }
      // at this point we cannot abort the rollback
      if (observer != null) {
        try {
          observer.afterSend(this, true);
        } catch (Exception ex) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          if (logger.warningEnabled()) {
            logger.warning(LocalizedStrings.DEBUG, ex);
          }
        }
      }

      try {
        if (response != null) {
          response.waitForReplies();
        }
      } catch (PrimaryBucketException pbe) {
        // ignore this
      } catch (IllegalTransactionStateException ite) {
        // ignore already rolled back transaction
      } catch (TransactionStateReadOnlyException tsre) {
        // ignore already rolled back transaction
      } catch (Exception e) {
        getCache().getCancelCriterion().checkCancelInProgress(e);
        throw new TransactionException(
            LocalizedStrings.TXStateProxy_ROLLBACK_ON_NODE_0_FAILED
                .toLocalizedString(recipients), e);
      }
    } finally {
      mlock.readLock().unlock();
      // remove from hosted txState list neverthless
      this.txManager.removeHostedTXState(this.txId, Boolean.FALSE);
      cleanup();
    }

    if (TRACE_EXECUTE) {
      final LogWriterI18n logger = txMgr.getLogger();
      logger.info(LocalizedStrings.DEBUG,
          "TX: rollback of " + this.txId.shortToString() + " complete");
    }
  }

  protected Set<InternalDistributedMember> getRollbackTargets(
      VMIdAdvisor advisor) {
    return advisor.getOtherMembers();
  }

  protected final void rollback(final TXState localState,
      final Object callbackArg) throws TransactionException {

    final TXManagerImpl txMgr = getTxMgr();
    while (true) {
      final State state = this.state.get();
      if (state == State.ROLLBACK_STARTED || state == State.INCONSISTENT) {
        break;
      }
      if (state == State.CLOSED || state == State.COMMIT_PHASE2_STARTED) {
        // can't do anything if done or commit phase2 already started
        // TODO: TX: this should actually never happen since a rollback due
        // to coordinator departure at this point should consult all members
        // (including self) and if anyone has already received a commit message
        // then it should not be rolled back (in this case self has received it)
        final LogWriterI18n logger = txMgr.getLogger();
        if (state == State.COMMIT_PHASE2_STARTED) {
          if (logger.warningEnabled()) {
            logger.warning(
                LocalizedStrings.TXStateProxy_CANNOT_ROLLBACK_AFTER_COMMIT,
                this.txId.shortToString());
          }
        }
        return;
      }
      if (this.state.compareAndSet(state, State.ROLLBACK_STARTED)) {
        break;
      }
    }

    try {
      if (TRACE_EXECUTE) {
        final LogWriterI18n logger = txMgr.getLogger();
        logger.info(LocalizedStrings.DEBUG,
            "TX: rolling back locally: " + this.txId.shortToString());
      }
      if (localState == null) { // indicates remote rollback
        final TXState txState = this.localTXState;
        try {
          if (txState != null) {
            txMgr.rollback(txState, callbackArg, true /*remote to coordinator*/);
          }
          onRollback(null, callbackArg);
        } finally {
          cleanup();
        }
      }
      else {
        localState.rollback(callbackArg);
        onRollback(localState, callbackArg);
      }
    } catch (IllegalTransactionStateException itse) {
      // ignore already rolled back transaction
    } catch (TransactionStateReadOnlyException tsre) {
      // ignore already rolled back transaction
    } finally {
      txMgr.removeHostedTXState(this.txId, Boolean.FALSE);
      signalLocalTXCommit();
    }
  }

  /** only for testing */
  public Set<?> getFinishMessageRecipientsForTest() {
    return getFinishMessageRecipients(false).getMembers().keySet();
  }

  private boolean checkIfRegionSetHasBucketRegion() {
    if (regions != null && !regions.isEmpty()) {
      for (Object ob : regions) {
        if (ob instanceof ProxyBucketRegion) {
          return true;
        }
        else if (ob instanceof BucketRegion) {
          if (((BucketRegion)ob).getRedundancyLevel() > 0) {
            return true;
          }
        }
      }
    }
    else {
      return false;
    }
    return false;
  }

  public MemberToGIIRegions getFinishMessageRecipients(
      final boolean doStateFlush) {
    boolean hasBucketRegion = this.hasCohorts;
    if (!hasBucketRegion) {
      hasBucketRegion = checkIfRegionSetHasBucketRegion(); 
    }
    if (hasBucketRegion) {
      this.lock.lock();
      this.attemptReadLock(-1);
      try {
        if (this.eventsToBePublished == null && isDirty() && hasParallelWAN()) {
          // initialize the events map so that member mapping will be populated
          // with empty event maps per-bucket by MemberToGIIRegions
          this.eventsToBePublished = new THashMap();
        }

        final SetWithCheckpoint regions = this.regions;
        final int numRegions = regions.size();
        final MemberToGIIRegions recipients = new MemberToGIIRegions(
            doStateFlush ? numRegions : 0, this.eventsToBePublished);
        final long[] viewVersions = recipients.viewVersions;
        final DistributionAdvisor[] viewAdvisors = recipients.viewAdvisors;
        for (int index = 0; index < numRegions; index++) {
          final Object reg = regions.elementAt(index);
          if (reg instanceof ProxyBucketRegion) {
            ProxyBucketRegion pbr = (ProxyBucketRegion)reg;
            BucketAdvisor advisor = pbr.getBucketAdvisor();
            // mark as operation start for state flush
            if (viewVersions != null) {
              viewVersions[index] = advisor.startOperation();
              viewAdvisors[index] = advisor;
              if (LOG_VERSIONS && viewVersions[index] != -1) {
                getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
                    "TXCommit: " + getTransactionId().shortToString()
                        + " dispatching operation for " + pbr.getFullPath()
                        + " in view version " + viewVersions[index]);
              }
            }
            // don't try to get VersionSource when concurrency-checks is
            // disabled for the region
            recipients.hasRegionDiskVersionSource = !pbr.getAttributes()
                .getConcurrencyChecksEnabled();
            advisor.accept(finishBucketVisitor, recipients);
            // force create TXRegionState locally if uninitialized
            BucketRegion br = pbr.getCreatedBucketRegion();
            if (br != null && !br.isInitialized()) {
              getTXStateForWrite(false, true).writeRegion(br, Boolean.FALSE);
            }
          }
          else if (reg instanceof DistributedRegion) {
            DistributedRegion dreg = (DistributedRegion)reg;
            DistributionAdvisor advisor = dreg.getDistributionAdvisor();
            // mark as operation start for state flush
            if (viewVersions != null) {
              viewVersions[index] = advisor.startOperation();
              viewAdvisors[index] = advisor;
              if (LOG_VERSIONS && viewVersions[index] != -1) {
                getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
                    "TXCommit: " + getTransactionId().shortToString()
                        + " dispatching operation for " + dreg.getFullPath()
                        + " in view version " + viewVersions[index]);
              }
            }
            recipients.hasRegionDiskVersionSource = !dreg
                .getConcurrencyChecksEnabled();
            advisor.accept(finishRegionVisitor, recipients);
            // force create TXRegionState locally if uninitialized
            if (dreg.getDataPolicy().withStorage() && !dreg.isInitialized()) {
              getTXStateForWrite(false, true).writeRegion(dreg, Boolean.FALSE);
            }
          }
        }
        return recipients;
      } finally {
        this.releaseReadLock();
        this.lock.unlock();
      }
    }
    else {
      return MemberToGIIRegions.EMPTY;
    }
  }

  public final void beforeCompletion() {
    final long opStart = CachePerfStats.getStatTime();
    try {
      commitPhase1(null);
    } catch (TransactionException commitConflict) {
      final long lifeTime = opStart - getBeginTime();
      this.txManager.noteCommitFailure(opStart, lifeTime, this, false);
      throw new SynchronizationCommitConflictException(
          LocalizedStrings.TXState_CONFLICT_DETECTED_IN_GEMFIRE_TRANSACTION_0
              .toLocalizedString(getTransactionId()),
          commitConflict);
    }
  }

  public final void afterCompletion(int status) {
    // System.err.println("start afterCompletion");
    final long opStart = CachePerfStats.getStatTime();
    final long lifeTime;
    switch (status) {
      case Status.STATUS_COMMITTED:
        // System.err.println("begin commit in afterCompletion");
        try {
          this.txManager.clearTXState();
          // commit phase1 already done in beforeCompletion()
          TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
          if (context != null) {
            // always wait for commit replies JTA transactions
            context.setWaitForPhase2Commit();
            commitPhase2(context, null);
          }
        } catch (TransactionInDoubtException error) {
          Assert.fail("Gemfire Transaction " + getTransactionId()
              + " afterCompletion failed due to TransactionInDoubtException: "
              + error);
        }
        lifeTime = opStart - getBeginTime();
        this.txManager.noteCommitSuccess(opStart, lifeTime, this, false);
        break;
      case Status.STATUS_ROLLEDBACK:
        this.txManager.clearTXState();
        rollback(null);
        lifeTime = opStart - getBeginTime();
        this.txManager.noteRollbackSuccess(opStart, lifeTime, this, false);
        break;
      default:
        Assert.fail("Unknown JTA Synchronization status " + status);
    }
  }

  public final boolean containsKeyWithReadLock(Object key, Object callbackArg,
      LocalRegion localRegion) {
    final TXState localState = getTXStateForWrite();
    return localState.containsKeyWithReadLock(key, callbackArg, localRegion,
        batchingEnabled());
  }

  public final boolean containsKey(final Object key, final Object callbackArg,
      final LocalRegion localRegion) {
    if (localRegion.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)localRegion;
      final InternalDistributedMember target = pickTheTargetForGet(pr, key,
          callbackArg, Operation.CONTAINS_KEY, "containsKey");
      int bucketId = KeyInfo.UNKNOWN_BUCKET;
      try {
        if (target == this.self) {
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            return localState.containsKey(key, callbackArg, pr);
          }
          return pr.txContainsKey(key, callbackArg, this, false);
        }
        else if (target != null) {
          bucketId = PartitionedRegionHelper.getHashKey(pr,
              Operation.CONTAINS_KEY, key, null, callbackArg);
          return pr.containsKeyRemotely(target, this, bucketId, key);
        }
        return false;
      } catch (EntryNotFoundException enfe) {
        return false;
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(key, (pr.getFullPath() + ':' + bucketId)),
                pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(target));
        re.initCause(e);
        throw re;
      }
    }
    else {
      // DR containsKey is always a local operation
      final TXState localState = getTXStateForRead();
      if (localState != null) {
        return localState.containsKey(key, callbackArg, localRegion);
      }
      return localRegion.txContainsKey(key, callbackArg, this, false);
    }
  }

  public final boolean containsValueForKey(final Object key,
      final Object callbackArg, final LocalRegion localRegion) {
    if (localRegion.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)localRegion;
      final InternalDistributedMember target = pickTheTargetForGet(pr, key,
          callbackArg, Operation.CONTAINS_VALUE_FOR_KEY, "containsValueForKey");
      int bucketId = KeyInfo.UNKNOWN_BUCKET;
      try {
        if (target == this.self) {
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            return localState.containsValueForKey(key, callbackArg, pr);
          }
          return pr.txContainsValueForKey(key, callbackArg, this);
        }
        else if (target != null) {
          bucketId = PartitionedRegionHelper.getHashKey(pr,
              Operation.CONTAINS_VALUE_FOR_KEY, key, null, callbackArg);
          return pr.containsValueForKeyRemotely(target, this, bucketId, key);
        }
        return false;
      } catch (EntryNotFoundException enfe) {
        return false;
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(key, (pr.getFullPath() + ':' + bucketId)),
                pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(target));
        re.initCause(e);
        throw re;
      }
    }
    else {
      // DR containsKey is always a local operation
      final TXState localState = getTXStateForRead();
      if (localState != null) {
        return localState.containsValueForKey(key, callbackArg, localRegion);
      }
      return localRegion.txContainsValueForKey(key, callbackArg, this);
    }
  }

  public final int entryCount(final LocalRegion localRegion) {
    assert !(localRegion instanceof PartitionedRegion);
    // entryCount is always a local-only operation
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState.entryCount(localRegion);
    }
    return localRegion.getSharedDataView().entryCount(localRegion);
  }

  @Override
  public final Object findObject(KeyInfo keyInfo, LocalRegion r,
      boolean isCreate, boolean generateCallbacks, Object value,
      boolean disableCopyOnRead, boolean preferCD,
      ClientProxyMembershipID requestingClient, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) {
    return findObject(keyInfo, r, isCreate, generateCallbacks, value, false,
        disableCopyOnRead, preferCD, requestingClient, clientEvent,
        allowTombstones, false, "findObject", allowReadFromHDFS);
  }

  public final Collection<?> getAdditionalKeysForIterator(final LocalRegion r) {
    // null returned here since the region also contains TX keys due to locking
    return null;
  }

  public final Region.Entry<?, ?> getEntryForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, final boolean allowTombstones) {
    return getEntry(keyInfo, null, null, currRgn, false, true, allowTombstones);
  }

  public final Object getKeyForIterator(final KeyInfo keyInfo,
      final LocalRegion region, final boolean allowTombstones) {
    // invoked only for Local/Distributed regions
    assert region.getPartitionAttributes() == null;

    final TXState localState = getTXStateForRead();
    if (localState != null) {
      return localState.getKeyForIterator(keyInfo, region, allowTombstones);
    }
    return region.getSharedDataView().getKeyForIterator(keyInfo, region,
        allowTombstones);
  }

  public final Object getKeyForIterator(Object key, LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final Object getValueForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, boolean updateStats, boolean preferCD,
      EntryEventImpl clientEvent, boolean allowTombstones) {
    return findObject(keyInfo, currRgn, false, false, null, updateStats, false,
        preferCD, null, clientEvent, allowTombstones, true,
        "getValueForIterator", false);
  }

  //TODO: Suranjan find out if snapshot is enabled for these operations.
  private final Object findObject(final KeyInfo keyInfo, final LocalRegion r,
      boolean isCreate, boolean generateCallbacks, final Object value,
      final boolean updateStats, final boolean disableCopyOnRead,
      final boolean preferCD, final ClientProxyMembershipID requestingClient,
      final EntryEventImpl clientEvent, final boolean allowTombstones,
      final boolean forIterator, final String op, boolean allowReadFromHDFS) {
    if (r.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)r;
      final InternalDistributedMember target = pickTheTargetForGet(pr,
          keyInfo.getBucketId(), Operation.GET, op);
      try {
        if (target == this.self) {
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            if (forIterator) {
              return localState.getValueForIterator(keyInfo, pr, updateStats,
                  preferCD, clientEvent, allowTombstones);
            }
            return localState.findObject(keyInfo, pr, isCreate,
                generateCallbacks, value, disableCopyOnRead, preferCD,
                requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
          }
          if (forIterator) {
            return pr.getSharedDataView().getValueForIterator(keyInfo, pr,
                updateStats, preferCD, clientEvent, allowTombstones);
          }
          return pr.findObjectInSystem(keyInfo, isCreate, this,
              generateCallbacks, value, disableCopyOnRead, preferCD,
              requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
        }
        else if (target != null) {
          return pr.getRemotely(target, this, keyInfo.getBucketId(),
              keyInfo.getKey(), keyInfo.getCallbackArg(), preferCD,
              requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
        }
        return null;
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(keyInfo.getKey(), (pr.getFullPath() + ':'
                    + keyInfo.getBucketId())), pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(target));
        re.initCause(e);
        throw re;
      }
    }
    else {
      try {
        if (forIterator) {
          // DR iteration is always a local operation
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            return localState.getValueForIterator(keyInfo, r, updateStats,
                preferCD, clientEvent, allowTombstones);
          }
          return r.getSharedDataView().getValueForIterator(keyInfo, r,
              updateStats, preferCD, clientEvent, allowTombstones);
        }
        final InternalDistributedMember target = pickTheTargetForGet(r,
            "findObject");
        if (target == this.self) {
          final TXState localState = getTXStateForRead();
          if (localState != null) {
            return localState.findObject(keyInfo, r, isCreate,
                generateCallbacks, value, disableCopyOnRead, preferCD,
                requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
          }
          return r.findObjectInSystem(keyInfo, isCreate, this,
              generateCallbacks, value, disableCopyOnRead, preferCD,
              requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
        }
        if (r.getDataPolicy().withStorage()) {
          // try the local loader first for NORMAL/PRELOADED regions
          final Object val = r.findObjectInLocalSystem(keyInfo.getKey(),
              keyInfo.getCallbackArg(), false /* don't register a miss */,
              this, generateCallbacks, value, clientEvent);
          if (val != null) {
            return val;
          }
        }
        if (target != null) {
          RemoteGetMessage.RemoteGetResponse response = RemoteGetMessage.send(
              target, r, this, keyInfo.getKey(), keyInfo.getCallbackArg(),
              requestingClient);
          return response.waitForResponse(preferCD);
        }
        else { // DR with no replicate, so fallback to netsearch
          final DistributedRegion dr = (DistributedRegion)r;
          @SuppressWarnings("unchecked")
          final Set<InternalDistributedMember> recipients = dr
              .getCacheDistributionAdvisor().adviseNetSearch();
          if (!recipients.isEmpty()) {
            RemoteGetMessage.RemoteGetResponse response = RemoteGetMessage
                .send(recipients, r, this, keyInfo.getKey(),
                    keyInfo.getCallbackArg(), requestingClient);
            return response.waitForResponse(preferCD);
          }
          // fallback to DR's findObject if nothing works
          return dr.findObjectInSystem(keyInfo, isCreate, this,
              generateCallbacks, value, disableCopyOnRead, preferCD,
              requestingClient, clientEvent, allowTombstones, allowReadFromHDFS);
        }
      } catch (RemoteOperationException roe) {
        throw new TransactionDataNodeHasDepartedException(roe);
      }
    }
  }

  @Override
  public final Object getValueInVM(final Object key, final Object callbackArg,
      final LocalRegion localRegion) throws EntryNotFoundException {
    // do not start local TX for reads in getValueInVM even for REPEATABLE_READ,
    // since this method is invoked only for internal stuff
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState.getValueInVM(key, callbackArg, localRegion);
    }
    return localRegion.nonTXbasicGetValueInVM(key, callbackArg);
  }

  @Override
  public final boolean isDeferredStats() {
    return true;
  }

  public final boolean putEntry(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed) {
    checkTXState();

    final LocalRegion r = event.getLocalRegion();
    // create the flags for performOp()
    int flags = 0;
    if (ifNew) flags |= IF_NEW;
    if (ifOld) flags |= IF_OLD;
    if (requireOldValue) flags |= REQUIRED_OLD_VAL;
    if (overwriteDestroyed) flags |= OVERWRITE_DESTROYED;

    if (r.getPartitionAttributes() != null) {
      final PartitionedRegion pr = (PartitionedRegion)r;
      final ProxyBucketRegion pbr = PartitionedRegionHelper
          .getProxyBucketRegion(pr, event);
      try {
        PartitionedRegionHelper.initializeBucket(pr, pbr, true);
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG, "putEntry PR " + pr.getFullPath()
              + ", event: " + (LOG_FINEST ? event.toString()
                  : event.shortToString()) + " for " + this.txId.toString());
        }
        final boolean toSelf = pbr.getCreatedBucketRegion() != null;
        final int bucketRedundancy = pbr.getBucketRedundancy();
        final boolean hasPossibleRecipients = bucketRedundancy > 0 || !toSelf
            || !pbr.isHosting();
        return performOp(r, pr, pbr, event, toSelf, hasPossibleRecipients,
            performPRPut, expectedOldValue, lastModified, true, flags);
      } catch (PrimaryBucketException e) {
        RuntimeException re = new TransactionDataRebalancedException(LocalizedStrings
            .PartitionedRegion_TRANSACTIONAL_DATA_MOVED_DUE_TO_REBALANCING
                .toLocalizedString(event.getKey(), pbr.getFullPath()),
                pr.getFullPath());
        re.initCause(e);
        throw re;
      } catch (ForceReattemptException e) {
        RuntimeException re = new TransactionDataNodeHasDepartedException(
            LocalizedStrings
              .PartitionedRegion_TRANSACTION_DATA_NODE_0_HAS_DEPARTED_TO_PROCEED_ROLLBACK_THIS_TRANSACTION_AND_BEGIN_A_NEW_ONE
                .toLocalizedString(e.getOrigin()));
        re.initCause(e);
        throw re;
      } catch (RemoteOperationException re) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", re);
      }
    }
    else {
      try {
        if (r.getScope().isLocal()) {
          return performDRPut.operateLocally(getTXStateForWrite(), event,
              expectedOldValue, lastModified, false, flags);
        }
        final DistributedRegion dr = (DistributedRegion)r;
        if (LOG_FINE) {
          final LogWriterI18n logger = getTxMgr().getLogger();
          logger.info(LocalizedStrings.DEBUG,
              "putEntry DR " + dr.getFullPath() + ", event: "
                  + (LOG_FINEST ? event.toString() : event.shortToString())
                  + " for " + this.txId.shortToString());
        }
        final boolean toSelf = r.getDataPolicy().withStorage();
        return performOp(r, null, dr, event, toSelf, true, performDRPut,
            expectedOldValue, lastModified, true, flags);
      } catch (RegionDestroyedException rde) {
        throw new RegionDestroyedException(toString(), event.getLocalRegion()
            .getFullPath());
      } catch (RemoteOperationException roe) {
        throw new TransactionDataNodeHasDepartedException(roe);
      } catch (ForceReattemptException fre) {
        // not expected to be thrown here
        throw new InternalGemFireError("unexpected exception", fre);
      }
    }
  }

  private final <P extends DirectReplyProcessor> boolean performOp(
      final LocalRegion r, final PartitionedRegion pr,
      final CacheDistributionAdvisee dataRegion, final EntryEventImpl event,
      boolean toSelf, final boolean hasPossibleRecipients,
      final PerformOp<P> performOp, final Object expectedOldValue,
      final long lastModified, final boolean cacheWrite, int flags)
      throws ForceReattemptException, RemoteOperationException {

    // do the send to other nodes first so processing can happen in parallel

    // add to the pendingOperations map and check if the size of any of the
    // recipients has hit batch limit
    boolean retVal = true;
    boolean selfSuccess = false;
    P response = null;
    P preferredNodeResponse = null;
    LogWriterI18n logger = null;
    boolean hasPendingOps = false;
    InternalDistributedMember preferredNode = null;
    markDirty();
    Set<InternalDistributedMember> recipients = Collections.emptySet();

    // for state flush
    DistributionAdvisor advisor = null;
    long viewVersion = -1;

    try {
      if (dataRegion != null) {
        if (hasPossibleRecipients && !isSnapshot()) {
          advisor = dataRegion.getDistributionAdvisor();
          viewVersion = advisor.startOperation();
          if (LOG_VERSIONS) {
            logger = r.getLogWriterI18n();
            logger.info(LocalizedStrings.DEBUG, "TX: "
                + getTransactionId().shortToString()
                + " dispatching operation in view version " + viewVersion);
          }
        }
        TransactionObserver observer = getObserver();
        if (observer != null) {
          logger = r.getLogWriterI18n();
          logger.info(LocalizedStrings.DEBUG, "TX: "
              + getTransactionId().shortToString()
              + " dispatching operation in view version " + viewVersion);
          observer.beforePerformOp(this);
        }

        // if checkTXState fails here..we don't have endOperation
        addAffectedRegion(dataRegion);
      }
      else {
        TransactionObserver observer = getObserver();
        if (observer != null) {
          logger = r.getLogWriterI18n();
          logger.info(LocalizedStrings.DEBUG, "TX: "
              + getTransactionId().shortToString()
              + " dispatching operation in view version " + viewVersion);
          observer.beforePerformOp(this);
        }
        // if checkTXState fails here..we don't have endOperation
        addAffectedRegion(r);
      }
      if (lockPolicy == LockingPolicy.SNAPSHOT) {
        event.setTXState(this);
        return performOp.operateOnSharedDataView(event, expectedOldValue, cacheWrite, lastModified, flags);
      }
      try {
        // in case of a local operation flush the pending ops else the batched
        // information can become incorrect
        final Operation op = event.getOperation();
        final boolean isLocalOp = op.isLocal();
        if (isLocalOp) {
          flushPendingOps(this.txManager.getDM());
          toSelf = true;
        }
        // batching is only done if local node is determined to be the
        // "preferred" one based on presence of CacheWriter and whether
        // current node is not a NORMAL/PRELOADED region; also avoid batching
        // for delta puts to handle delta propagation exceptions properly
        if (toSelf && hasPossibleRecipients
            && event.getDeltaBytes() == null && batchingEnabled()
            && (pr != null || r.getDataPolicy().withReplication())) {
          hasPendingOps = true;
          // at this point we need to check if there is another node that has to
          // be preferred over this one -- check for a net CacheWriter; prefer
          // remote replicates if self is not a replicate
          final PreferredNodeSelector prefNodeSelect = getPreferredNode(r, pr,
              dataRegion, null, toSelf, cacheWrite, event);
          if (!prefNodeSelect.isSelf()) {
            hasPendingOps = false;
            preferredNode = prefNodeSelect.prefNode;
            recipients = prefNodeSelect.getRecipients();
          }
        }
        else if (hasPossibleRecipients) {
          recipients = getOtherMembersForOperation(pr, dataRegion, event, null,
              false);
          if (recipients.size() > 0) {
            final PreferredNodeSelector prefNodeSelect = getPreferredNode(r,
                pr, dataRegion, recipients, toSelf, cacheWrite, event);
            if (prefNodeSelect != null && !prefNodeSelect.isSelf()) {
              preferredNode = prefNodeSelect.prefNode;
            }
          }
          // if there are no recipients for EMPTY region then force toSelf to
          // true to send events to listeners/clients/WAN etc.
          // 20141121: Why force toSelf to true. If we don't have any node to
          // apply then we should throw NoDataStoreAvailableException instead.
          // See Bug #51169
          else if (r.isProxy()) {
            // [sumedh] GFE API requires proxy region TX ops to continue without
            // failing and just send the ops to listeners etc. For example, see
            // ProxyJUnitTest.testAllMethodsWithTX, so keep the same for GFE.
            if (this.isGFXD) {
              throw new NoDataStoreAvailableException(LocalizedStrings
                  .DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                      .toLocalizedString(r));
            }
            else {
              toSelf = true;
            }
          }
        }
        if (LOG_FINE) {
          logger = r.getLogWriterI18n();
          logger.info(LocalizedStrings.DEBUG, "Recipients for event: "
              + (LOG_FINEST ? event.toString() : event.shortToString())
              + ", toSelf=" + toSelf + (preferredNode != null
                  ? ", preferredNode=" + preferredNode : "") + " : "
              + recipients + " for " + this.txId.shortToString());
        }

        boolean batchFlushed = false;
        if (recipients.size() > 0) {
          // don't allow local puts/destroys/invalidates in a transaction
          if (isLocalOp) {
            failTXForLocalOperation(op);
          }
          // if there are remote recipients and operation requires further
          // remote operations, then need to flush any batched ops (#44530)
          // TODO: PERF: the below send can be piggybacked or at least sends
          // be done together, and then wait for all together
          final IndexUpdater indexUpdater = r.getIndexUpdater();
          if (indexUpdater != null
              && indexUpdater.hasRemoteOperations(event.op)) {
            flushPendingOps(this.txManager.getDM());
          }
          batchFlushed = true;
          response = performOp.sendRemotely(recipients, event, r, pr,
              expectedOldValue, lastModified, flags);
        }
        // send a separate message to preferredNode to invoke CacheWriter
        if (preferredNode != null) {
          // don't allow local puts/destroys/invalidates in a transaction
          if (isLocalOp) {
            failTXForLocalOperation(op);
          }
          // if there are remote recipients and operation requires further
          // remote operations, then need to flush any batched ops (#44530)
          final IndexUpdater indexUpdater;
          if (!batchFlushed && (indexUpdater = r.getIndexUpdater()) != null
              && indexUpdater.hasRemoteOperations(event.op)) {
            flushPendingOps(this.txManager.getDM());
          }
          preferredNodeResponse = performOp.sendRemotelyPreferredNode(
              preferredNode, event, r, pr, expectedOldValue, lastModified,
              flags);
        }
        // message distribution, if any, is done at this point
        if (viewVersion != -1) {
          advisor.endOperation(viewVersion);
          if (LOG_VERSIONS) {
            logger.info(LocalizedStrings.DEBUG, "TX: "
                + getTransactionId().shortToString()
                + " done dispatching operation in view version " + viewVersion);
          }
          viewVersion = -1;
        }
        // does the recipients include self?
        if (toSelf) {
          // force global index updates for GemFireXD from this node
          if (hasPendingOps) {
            event.setDistributeIndexOps();
          }
          // skip distribution for index updates if someone else has been
          // selected as "preferred" to do it
          else if (preferredNode != null) {
            event.setSkipDistributionOps();
          }
          retVal = false;
          if (!cacheWrite) {
            flags |= SKIP_CACHEWRITE;
          }
          if (performOp.operateLocally(getTXStateForWrite(), event,
              expectedOldValue, lastModified, hasPendingOps, flags)) {
            retVal = true;
            if (hasPendingOps) {
              if (LOG_FINE) {
                logger.info(LocalizedStrings.DEBUG, "performOp: added event ["
                    + (LOG_FINEST ? event.toString() : event.shortToString())
                    + "] for batching to recipients in region " + dataRegion
                        .getFullPath() + " for " + this.txId.shortToString());
              }
            }
          }
          selfSuccess = true;
        }
      } finally {
        // fail if result from anywhere is false
        if (preferredNodeResponse != null) {
          try {
            retVal &= performOp.waitForRemoteResult(preferredNodeResponse,
                Collections.singleton(preferredNode), event, r, pr, flags);
          } finally {
            if (response != null) {
              retVal &= performOp.waitForRemoteResult(response, recipients,
                  event, r, pr, flags);
            }
          }
        }
        else if (response != null) {
          retVal &= performOp.waitForRemoteResult(response, recipients, event,
              r, pr, flags);
        }
      }
    } catch (TXCacheWriterException cwe) {
      // revert on remaining members, if any, and self
      // for GemFireXD currently we cannot revert due to indexes
      if (this.isGFXD) {
        markTXInconsistent(cwe);
      }
      else if (response != null) {
        revertFailedOp(r, event.getKey(), cwe, recipients,
            performOp.getResponseExceptions(response), selfSuccess);
      }
      throw cwe;
    } catch (TransactionException te) {
      // for GemFireXD currently we cannot revert due to indexes
      if (te.isTransactionSeverity() || this.isGFXD) {
        markTXInconsistent(te);
      }
      // revert on remaining members, if any, and self
      else if (response != null) {
        revertFailedOp(r, event.getKey(), te, recipients,
            performOp.getResponseExceptions(response), selfSuccess);
      }
      throw te;
    } finally {
      if (viewVersion != -1) {
        advisor.endOperation(viewVersion);
        if (LOG_VERSIONS) {
          logger.info(LocalizedStrings.DEBUG, "TX: " + performOp
              + " done dispatching operation in view version " + viewVersion);
        }
        viewVersion = -1;
      }
    }
    return retVal;
  }

  private final void failTXForLocalOperation(final Operation op)
      throws UnsupportedOperationInTransactionException {
    // don't allow local puts/destroys/invalidates in a transaction that
    // may need to go to remote nodes
    if (op.isUpdate() || op.isCreate()) {
      throw new UnsupportedOperationInTransactionException(
          LocalizedStrings.TXStateProxy_LOCAL_PUT_NOT_ALLOWED_IN_TRANSACTION
              .toLocalizedString());
    }
    else if (op.isDestroy()) {
      throw new UnsupportedOperationInTransactionException(
          LocalizedStrings.TXStateProxy_LOCAL_DESTROY_NOT_ALLOWED_IN_TRANSACTION
              .toLocalizedString());
    }
    else if (op.isInvalidate()) {
      throw new UnsupportedOperationInTransactionException(
          LocalizedStrings.TXStateProxy_LOCAL_INVALIDATE_NOT_ALLOWED_IN_TRANSACTION
              .toLocalizedString());
    }
  }

  public final TXState getTXStateForWrite() {
    return getTXStateForWrite(true, true);
  }

  public final TXState getTXStateForWrite(boolean doLock, boolean checkTX) {
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState;
    }
    return doLock ? createTXState(checkTX) : createTXStateNoLock(checkTX);
  }

  public final TXState getTXStateForRead() {
    // start local TXState if reads require so
    final TXState localState = this.localTXState;
    if (localState != null) {
      return localState;
    }
    if (this.lockPolicy.readCanStartTX()) {
      return createTXState(true);
    }
    else {
      return null;
    }
  }


  private final TXState createTXState(boolean checkTX) {
    final TXState localState;
    this.lock.lock();
    try {
      localState = this.localTXState;
      if (checkTX) {
        checkTXState();
      }
      if (localState != null) {
        return localState;
      }
      else {
        return (this.localTXState = new TXState(this));
      }
    } finally {
      this.lock.unlock();
    }
  }

  private final TXState createTXStateNoLock(boolean checkTX) {
    final TXState localState = this.localTXState;
    if (checkTX) {
      checkTXState();
    }
    if (localState != null) {
      return localState;
    }
    else {
      return (this.localTXState = new TXState(this));
    }
  }

  public final void addPendingResultCollector(final ResultCollector<?, ?> rc) {
    if (rc != null) {
      this.lock.lock();
      this.pendingResultCollectors.add(rc);
      this.lock.unlock();
    }
  }

  public final void removePendingResultCollector(
      final ResultCollector<?, ?> rc) {
    if (rc != null) {
      this.lock.lock();
      for (int idx = this.pendingResultCollectors.size() - 1; idx >= 0; idx--) {
        if (this.pendingResultCollectors.get(idx) == rc) {
          this.pendingResultCollectors.remove(idx);
        }
      }
      this.lock.unlock();
    }
  }

  final public boolean isClosed() {
    final State state = this.state.get();
    return state == State.CLOSED;
  }

  final void checkTXState() throws TransactionException {
    final State state = this.state.get();
    if (state == State.OPEN || state == State.COMMIT_PHASE2_STARTED) {
      return;
    }
    if (state == State.ROLLBACK_STARTED) {
      throw new IllegalTransactionStateException(LocalizedStrings
          .TransactionManagerImpl_TRANSACTIONMANAGERIMPL_COMMIT_TRANSACTION_ROLLED_BACK_BECAUSE_A_USER_MARKED_IT_FOR_ROLLBACK
          .toLocalizedString());
    }
    if (state == State.CLOSED) {
      throw new IllegalTransactionStateException(LocalizedStrings
          .TXManagerImpl_THREAD_DOES_NOT_HAVE_AN_ACTIVE_TRANSACTION
              .toLocalizedString());
    }
    if (state == State.INCONSISTENT) {
      final Throwable t = this.inconsistentThr;
      // throw underlying transaction exception e.g. when due to conflict
      if (t instanceof TransactionException) {
        throw (TransactionException)t;
      }
      throw new TransactionStateReadOnlyException(
          LocalizedStrings.TXState_INCONSISTENT.toLocalizedString(t), t);
    }
    throw new InternalGemFireError("unknown transaction state " + state);
  }

  public final void markTXInconsistent(final Throwable t) {
    this.lock.lock();
    markTXInconsistentNoLock(t);
    this.lock.unlock();
  }

  final void markTXInconsistentNoLock(Throwable t) {
    if (TRACE_EXECUTE) {
      final LogWriterI18n logger = getTxMgr().getLogger();
      logger.fine("marking " + toString() + " as inconsistent", t);
    }
    this.state.set(State.INCONSISTENT);
    this.inconsistentThr = t;
  }

  public final Throwable getTXInconsistent() {
    return this.state.get() != State.INCONSISTENT ? null : this.inconsistentThr;
  }

  /**
   * A profile visitor to select a node with CacheWriter installed, if any. Also
   * records a random replicate node that is chosen for distribution in case the
   * current node has storage but is not a replicate while we do find a remote
   * replicate.
   */
  private static final class PreferredNodeSelector implements
      ProfileVisitor<Void> {

    /** token to indicate that this node itself is the preferred node */
    static final PreferredNodeSelector SELF = new PreferredNodeSelector(null,
        false);

    private Set<InternalDistributedMember> recipients;

    private final boolean recordReplicate;

    InternalDistributedMember prefNode;

    private int randIndex;

    PreferredNodeSelector(final Set<InternalDistributedMember> recipients,
        final boolean recordReplicate) {
      setRecipients(recipients);
      this.recordReplicate = recordReplicate;
    }

    final void setRecipients(final Set<InternalDistributedMember> recipients) {
      this.recipients = recipients;
      this.randIndex = -1;
    }

    final Set<InternalDistributedMember> getRecipients() {
      return this.recipients;
    }

    final boolean isSelf() {
      return this == SELF;
    }

    public boolean visit(DistributionAdvisor advisor, final Profile profile,
        final int profileIndex, int numProfiles, Void aggregate) {
      final CacheProfile cp = (CacheProfile)profile;
      final InternalDistributedMember member = cp.getDistributedMember();
      // if region or member not initialized exclude
      if (cp.hasCacheWriter && cp.regionInitialized
          && !cp.memberUnInitialized) {
        if (this.recipients == null || this.recipients.contains(member)) {
          this.prefNode = member;
          // found a recipient itself that has a CacheWriter so break out
          // immediately
          return false;
        }
      }
      if (this.recordReplicate) {
        if (this.randIndex < 0) {
          if (this.recipients != null) {
            numProfiles = this.recipients.size();
          }
          // continue with CacheWriter search if no profiles found
          if (numProfiles == 0) {
            return true;
          }
          this.randIndex = PartitionedRegion.rand.nextInt(numProfiles);
        }
        if ((this.prefNode == null || profileIndex <= this.randIndex)
            && cp.dataPolicy.withReplication() && cp.regionInitialized
            && !cp.memberUnInitialized) {
          if (this.recipients == null || this.recipients.contains(member)) {
            // store the last replicated member in any case since in the worst
            // case there may be no replicated node after "randIndex" in which
            // case the last visited member will be used
            this.prefNode = member;
          }
        }
      }
      return true;
    }
  }

  private TransactionBatchException accumulateException(
      final ReplyProcessor21 reply, final InternalDistributedMember sender,
      final boolean forCommit, TransactionBatchException batchException) {
    Throwable thr = null;
    try {
      reply.waitForReplies();
    } catch (ReplyException replyEx) {
      thr = replyEx.getCause();
      if (thr instanceof CancelException) {
        // check if all copies are going down
        // avoid locking during commit since its already taken
        if (forCommit) {
          thr = checkAvailableRegionCopies(replyEx.getSender());
        }
        else {
          thr = checkAvailableRegions(replyEx.getSender());
        }
      }
      else {
        replyEx.fixUpRemoteEx(thr);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      thr = ie;
    } finally {
      if (thr != null) {
        // else add the exception to the batch
        if (batchException == null) {
          final String eventStr;
          if (forCommit) {
            eventStr = "commit for " + toString();
          }
          else {
            eventStr = "batch flush for " + toString();
          }
          batchException = new TransactionBatchException(
              "Failed in dispatching a batch of operations (event: " + eventStr
                  + ')', thr);
        }
        batchException.addException(sender, thr);
        // we cannot revert to a consistent safe state any longer so mark
        // this TX as inconsistent and allow only rollback henceforth
        // avoid locking during commit since its already taken
        if (forCommit) {
          markTXInconsistentNoLock(batchException);
        }
        else {
          markTXInconsistent(batchException);
        }
        // check for cache going down
        getCache().getCancelCriterion().checkCancelInProgress(thr);
      }
    }
    return batchException;
  }

  public final void addAffectedRegion(final Object region) {
    this.attemptWriteLock(-1);
    try {
      addAffectedRegionNoLock(region, true);
    } finally {
      this.releaseWriteLock();
    }
  }

  public final void addAffectedRegion(final Object region,
      final boolean checkTXState) {
    this.attemptWriteLock(-1);
    try {
      addAffectedRegionNoLock(region, checkTXState);
    } finally {
      this.releaseWriteLock();
    }
  }

  protected final void addAffectedRegionNoLock(Object region,
      boolean checkTXState) {
    if (checkTXState) {
      // abort if transaction has already been marked failed or done
      checkTXState();
    }
    // for BucketRegions add PBR for uniformity
    ProxyBucketRegion pbr = null;
    if (region instanceof BucketRegion) {
      pbr = ((BucketRegion)region).getBucketAdvisor()
          .getProxyBucketRegion();
      region = pbr;
    }
    if (this.regions.add(region)) {
      if (LOG_FINE) {
        getTxMgr().getLogger().info(LocalizedStrings.DEBUG, "added new region "
            + (pbr != null ? pbr.getFullPath() : region) + " to "
            + this.txId.shortToString()/*, new Exception()*/);
      }
      if (pbr != null) {
        this.regions.add(pbr.getPartitionedRegion());
      }
      else if (region instanceof ProxyBucketRegion) {
        this.regions.add(((ProxyBucketRegion)region)
            .getPartitionedRegion());
      }
    }
  }

  public final int numAffectedRegions() {
    this.attemptReadLock(-1);
    final int size = this.regions.size();
    this.releaseReadLock();
    return size;
  }

  public final boolean hasAffectedRegion(Object r) {
    // for bucket regions, check both bucket region and proxy bucket region
    LocalRegion lr = null;
    ProxyBucketRegion pbr = null;
    if (r instanceof ProxyBucketRegion) {
      pbr = (ProxyBucketRegion)r;
    }
    else if ((lr = (LocalRegion)r).isUsedForPartitionedRegionBucket()) {
      pbr = ((BucketRegion)lr).getBucketAdvisor().getProxyBucketRegion();
    }
    this.attemptReadLock(-1);
    try {
      return (lr != null && this.regions.contains(lr)
          || (pbr != null && this.regions.contains(pbr)));
    } finally {
      this.releaseReadLock();
    }
  }

  private final PreferredNodeSelector getPreferredNode(final LocalRegion r,
      final PartitionedRegion pr, final CacheDistributionAdvisee dataRegion,
      Set<InternalDistributedMember> recipients, final boolean toSelf,
      final boolean cacheWrite, final EntryEventImpl event) {
    if (toSelf) {
      // check for a net CacheWriter; prefer remote replicates if self is not
      // a replicate
      boolean preferDistribution = false;
      if (pr != null) {
        preferDistribution = dataRegion != null
            && !((Bucket)dataRegion).isHosting();
      }
      else {
        final DataPolicy dp = r.getDataPolicy();
        preferDistribution = (dp == DataPolicy.NORMAL
            || dp == DataPolicy.PRELOADED || !r.isInitialized());
      }
      // always send cacheWrite flag below as true to set the event.set*IndexOps
      // flag appropriately on one node
      if (dataRegion != null && (preferDistribution
          || (cacheWrite && r.basicGetWriter() == null))) {
        final PreferredNodeSelector selectNode = new PreferredNodeSelector(
            recipients, preferDistribution);
        if (!dataRegion.getCacheDistributionAdvisor().accept(selectNode, null)
            || (selectNode.prefNode != null)) {
          // if recipients is null then check if this is really a recipient
          if (recipients == null) {
            recipients = getOtherMembersForOperation(pr, dataRegion, event,
                null, false);
            if (recipients.size() > 0) {
              selectNode.setRecipients(recipients);
              if (recipients.remove(selectNode.prefNode)) {
                return selectNode;
              }
              // we will have to select again since we may have skipped some
              // other valid members the first time
              selectNode.prefNode = null;
              if (!dataRegion.getCacheDistributionAdvisor().accept(selectNode,
                  null) || (selectNode.prefNode != null)) {
                return selectNode;
              }
            }
          }
          else {
            recipients.remove(selectNode.prefNode);
            return selectNode;
          }
        }
      }
      return PreferredNodeSelector.SELF;
    }
    else if (cacheWrite && dataRegion != null) {
      final PreferredNodeSelector selectNode = new PreferredNodeSelector(
          recipients, true);
      if (!dataRegion.getCacheDistributionAdvisor().accept(selectNode, null)
          || (selectNode.prefNode != null)) {
        recipients.remove(selectNode.prefNode);
        return selectNode;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private final GemFireException revertFailedOp(final LocalRegion dataRegion,
      final Object regionKey, GemFireException failedEx,
      final Set<InternalDistributedMember> originalRecipients,
      final Map<InternalDistributedMember, ReplyException> exceptions,
      final boolean revertSelf) {
    final THashSet revertMembers = new THashSet(originalRecipients);
    final Class<?> failedExClass = failedEx.getClass();
    Throwable cause;
    if (exceptions != null) {
      for (Map.Entry<InternalDistributedMember, ReplyException> entry :
           exceptions.entrySet()) {
        cause = entry.getValue().getCause();
        // pick up the "stronger" exception as the one to revert/return
        if (cause instanceof ConflictException) {
          failedEx = (ConflictException)cause;
          revertMembers.remove(entry.getKey());
        }
        else if (failedExClass.isInstance(cause)) {
          revertMembers.remove(entry.getKey());
        }
      }
    }
    // check if nothing to revert
    if (revertMembers.isEmpty() && !revertSelf) {
      return failedEx;
    }
    final DM dm = this.txManager.getDM();
    final InternalDistributedSystem system = dm.getSystem();
    byte originalOp = 0;
    byte destroy = 0;
    boolean bulkOp = false;
    Object originalValue = null;
    Delta originalDelta = null;
    if (failedEx instanceof TXCacheWriterException) {
      final TXCacheWriterException cwe = (TXCacheWriterException)failedEx;
      originalOp = cwe.getOriginalOp();
      destroy = cwe.getOriginalDestroy();
      bulkOp = cwe.getOriginalBulkOp();
      originalValue = cwe.getOriginalValue();
      originalDelta = cwe.getOriginalDelta();
    }
    ReplyProcessor21 response = null;
    if (!revertMembers.isEmpty()) {
      response = TXCleanupEntryMessage.send(system, dm, this, revertMembers,
          dataRegion, regionKey, originalOp, destroy, bulkOp, originalValue,
          originalDelta);
    }

    boolean success = false;
    Throwable thr = null;
    try {
      try {
        if (revertSelf) {
          getLocalTXState().revertFailedOp(dataRegion, regionKey, originalOp,
              destroy, bulkOp, originalValue, originalDelta);
        }
      } finally {
        if (response != null) {
          response.waitForReplies();
        }
      }
      success = true;
    } catch (ReplyException ex) {
      // log and move on
      system.getLogWriterI18n().fine("revertFailedOp: received exception", ex);
    } catch (InterruptedException ie) {
      thr = ie;
      Thread.currentThread().interrupt();
    } finally {
      if (!success) {
        system.getCancelCriterion().checkCancelInProgress(thr);
      }
    }
    return failedEx;
  }

  @Override
  public final Object getSerializedValue(LocalRegion localRegion, KeyInfo key,
      boolean doNotLockEntry, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean allowTombstones, boolean allowReadFromHDFS) {
    throw new InternalGemFireError("not expected to be invoked");
  }

  public final boolean putEntryOnRemote(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed)
      throws DataLocationException {
    throw new InternalGemFireError("not expected to be invoked");
  }

  public final boolean isFireCallbacks() {
    // for the dirty case, the callbacks will be fired from underlying TXStates
    return !this.isDirty;
  }

  public final void destroyOnRemote(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) throws DataLocationException {
    throw new InternalGemFireError("not expected to be invoked");
  }

  public final void invalidateOnRemote(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry)
      throws DataLocationException {
    throw new InternalGemFireError("not expected to be invoked");
  }

  @Override
  public final void checkSupportsRegionDestroy()
      throws UnsupportedOperationInTransactionException {
    throw new UnsupportedOperationInTransactionException(
        LocalizedStrings.TXState_REGION_DESTROY_NOT_SUPPORTED_IN_A_TRANSACTION
            .toLocalizedString());
  }

  @Override
  public final void checkSupportsRegionInvalidate()
      throws UnsupportedOperationInTransactionException {
    throw new UnsupportedOperationInTransactionException(
        LocalizedStrings.TXState_REGION_INVALIDATE_NOT_SUPPORTED_IN_A_TRANSACTION
            .toLocalizedString());
  }

  public final Set<?> getBucketKeys(LocalRegion localRegion, int bucketId,
      boolean allowTombstones) {
    // nothing to add here; PR's getBucketKeys will automatically fetch from
    // remote with TX context if required
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.getBucketKeys(bucketId, allowTombstones);
  }

  public final EntrySnapshot getEntryOnRemote(KeyInfo key,
      LocalRegion localRegion, boolean allowTombstones)
      throws DataLocationException {
    throw new InternalGemFireError("not expected to be invoked");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Iterator<?> getRegionKeysForIteration(
      final LocalRegion dataRegion, final boolean includeValues) {
    // This is invoked with either LocalRegion or DistributedRegion while for
    // PR the iterator will be over the buckets (getBucketKeys).
    assert dataRegion.getPartitionAttributes() == null;
    final TXState localState = getTXStateForRead();
    if (localState != null) {
      return localState.getRegionKeysForIteration(dataRegion, includeValues);
    }
    return dataRegion.getSharedDataView().getRegionKeysForIteration(dataRegion,
        includeValues);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Iterator<?> getLocalEntriesIterator(
      final InternalRegionFunctionContext context, final boolean primaryOnly,
      boolean forUpdate, boolean includeValues, final LocalRegion region) {
    final TXState localState = getTXStateForRead();
    if (localState != null) {
      return localState.getLocalEntriesIterator(context, primaryOnly,
          forUpdate, includeValues, region);
    }
    return region.getSharedDataView().getLocalEntriesIterator(context,
        primaryOnly, forUpdate, includeValues, region);
  }

  /**
   * @see InternalDataView#postPutAll(DistributedPutAllOperation,
   *      VersionedObjectList, LocalRegion)
   */
  public void postPutAll(final DistributedPutAllOperation putAllOp,
      final VersionedObjectList successfulPuts, final LocalRegion region) {
    // TODO: TX: add support for batching using performOp as for other
    // update operations; add cacheWrite flag support for proper writer
    // invocation like in other ops; also support for NORMAL/PRELOADED regions?
    markDirty();

    if (isSnapshot()) {
      addAffectedRegion(region);
      region.getSharedDataView().postPutAll(putAllOp, successfulPuts, region);
      return;
    }
    if (region.getPartitionAttributes() != null) {
      // use PutAllPRMessage that already handles transactions
      region.postPutAllSend(putAllOp, this, successfulPuts);
    }
    else {
      try {
        final PutAllEntryData[] data = putAllOp.putAllData;
        final EntryEventImpl event = putAllOp.getBaseEvent();
        final RemotePutAllMessage msg = new RemotePutAllMessage(event, null,
            data, data.length, event.isPossibleDuplicate(), null, this);
        // process on self first
        if (region.getDataPolicy().withStorage()) {
          msg.doLocalPutAll(region, event, putAllOp, successfulPuts,
              region.getMyId(), false /* sendReply */);
        }
        addAffectedRegion(region);
        if (region.getScope().isDistributed()) {
          // distribute if required
          msg.distribute(event);
        }
      } catch (RemoteOperationException roe) {
        throw new TransactionDataNodeHasDepartedException(roe);
      }
    }
  }

  /**
   * Handle a departed member from the DS for this {@link TXStateProxy}.
   * 
   * @return true if this {@link TXStateProxy} is no longer required and can be
   *         removed, and false otherwise
   */
  final boolean handleMemberDeparted(final InternalDistributedMember id,
      final DM dm, boolean crashed) {
    if (isCoordinator()) {
      // check if all copies of a region have gone down
      final TransactionException failure = checkAvailableRegions(id);
      if (failure != null) {
        markTXInconsistent(failure);
      }
    }
    // check if outgoing member is the coordinator of this transaction
    else if (id.equals(this.coordinator)) {
      final boolean isClosed = (this.state.get() == State.CLOSED);
      final LogWriterI18n logger = dm.getLoggerI18n();
      if (logger.fineEnabled() || LOG_FINE) {
        logger.info(LocalizedStrings.DEBUG, "TXStateProxy: received "
            + "memberDeparted, cleaning up " + this.txId
            + " for outgoing coordinator: " + id);
      }
      // TODO: TX: need to select new coordinator and finish the TX is required,
      // else rollback on all nodes; currently only doing rollback
      final TXManagerImpl txMgr = getTxMgr();
      final TXManagerImpl.TXContext context = txMgr.masqueradeAs(this);
      try {
        rollback((TXState)null, (Object)null);
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
        if (!isClosed) {
          txMgr.getCache().getCancelCriterion().checkCancelInProgress(t);
          // log the error at this point and move on
          logger.severe(LocalizedStrings.DEBUG,
              "TXStateProxy#handleMemberDeparted: failed in rollback of "
                  + toString(), t);
        }
      } finally {
        txMgr.unmasquerade(context, false);
      }
      return true;
    }
    return false;
  }

  final TransactionException checkAvailableRegions(
      final InternalDistributedMember departedMember) {
    this.lock.lock();
    try {
      return checkAvailableRegionCopies(departedMember);
    } finally {
      this.lock.unlock();
    }
  }

  private final TransactionException checkAvailableRegionCopies(
      final InternalDistributedMember departedMember) {
    ProfileVisitor<Void> bucketVisitor = null;
    ProfileVisitor<Void> regionVisitor = null;

    final Object[] regions;
    this.attemptReadLock(-1);
    try {
      regions = this.regions.toArray();
    } finally {
      this.releaseReadLock();
    }

    for (Object reg : regions) {
      if (reg instanceof ProxyBucketRegion) {
        final ProxyBucketRegion pbr = (ProxyBucketRegion)reg;
        final BucketAdvisor ba = pbr.getBucketAdvisor();
        int redundancy = ba.getBucketRedundancy();
        if (redundancy == 0 && !ba.isHosting()) {
          if (bucketVisitor == null) {
            if (departedMember == null) {
              bucketVisitor = preCommitBucketVisitor;
            }
            else {
              bucketVisitor = new ProfileVisitor<Void>() {
                public boolean visit(DistributionAdvisor advisor,
                    final Profile profile, int profileIndex, int numProfiles,
                    Void aggregate) {
                  assert profile instanceof BucketProfile;
                  final BucketProfile bucketProfile = (BucketProfile)profile;
                  return !bucketProfile.isHosting
                      || departedMember.equals(bucketProfile
                          .getDistributedMember());
                }
              };
            }
          }
          if (ba.accept(bucketVisitor, null)) {
            redundancy = -1;
          }
        }
        if (redundancy < 0) {
          // no host left for the bucket
          return new TransactionDataNodeHasDepartedException(
              "No copy left for bucketId=" + pbr.getBucketId()
              + " in partitioned region " + pbr.getPartitionedRegion()
              + (departedMember != null ? " after departure of member "
                  + departedMember : ""));
        }
      }
      else if (reg instanceof CacheDistributionAdvisee) {
        final LocalRegion region = (LocalRegion)reg;
        final PartitionAttributes<?, ?> pattrs = region
            .getPartitionAttributes();
        // first check if this node itself hosts data for the region
        if ((pattrs != null && pattrs.getLocalMaxMemory() != 0)
            || region.getDataPolicy().withStorage()) {
          continue;
        }
        else {
          if (regionVisitor == null) {
            if (departedMember == null) {
              regionVisitor = preCommitRegionVisitor;
            }
            else {
              regionVisitor = new ProfileVisitor<Void>() {
                public boolean visit(DistributionAdvisor advisor,
                    final Profile profile, int profileIndex, int numProfiles,
                    Void aggregate) {
                  assert profile instanceof CacheProfile;
                  final CacheProfile cp;
                  boolean isStore = false;
                  if (profile instanceof PartitionProfile) {
                    final PartitionProfile pp = (PartitionProfile)profile;
                    cp = pp;
                    isStore = pp.isDataStore;
                  }
                  else {
                    cp = (CacheProfile)profile;
                    isStore = cp.dataPolicy.withStorage();
                  }
                  if (isStore && cp.regionInitialized
                      && !cp.memberUnInitialized) {
                    // found a hosting member; check its not the departed
                    // member
                    return departedMember.equals(cp.getDistributedMember());
                  }
                  return true;
                }
              };
            }
          }
          if (((CacheDistributionAdvisee)reg).getCacheDistributionAdvisor()
              .accept(regionVisitor, null)) {
            // did not break out of loop means no other copy is available
            return new TransactionDataNodeHasDepartedException(
                "No copy left for region " + region
                + (departedMember != null ? " after departure of member "
                    + departedMember : ""));
          }
        }
      }
    }
    return null;
  }

  // Methods of TxState Proxy ... Neeraj

  public final boolean isJTA() {
    return this.isJTA;
  }

  public final boolean isJCA() {
    return this.isJCA;
  }

  public final void setJCA(boolean enable) {
    this.isJCA = enable;
  }

  public final Object lockEntryForRead(final RegionEntry entry,
      final Object key, final LocalRegion dataRegion, final int iContext,
      final boolean allowTombstones, final ReadEntryUnderLock reader) {
    final LockingPolicy lockPolicy = getLockingPolicy();
    return TXState.lockEntryForRead(lockPolicy, entry, key, dataRegion, null,
        this.txId, getTXStateForRead(), iContext, batchingEnabled(),
        allowTombstones, Boolean.TRUE, reader);
  }

  @Override
  public final Object lockEntry(RegionEntry entry, Object key,
      Object callbackArg, LocalRegion region, LocalRegion dataRegion,
      boolean writeMode, boolean allowReadFromHDFS, final byte opType,
      final int failureFlags) {
    final TXState localState = getTXStateForWrite();
    final TXRegionState txr = localState.writeRegionForRead(dataRegion);
    return localState.lockEntry(entry, key, callbackArg, txr, region,
        dataRegion, writeMode, allowReadFromHDFS, opType, batchingEnabled(),
        failureFlags, null);
  }

  public final Object lockEntry(RegionEntry entry, Object key,
      Object callbackArg, LocalRegion region, LocalRegion dataRegion,
      boolean writeMode, final byte opType) {
    final TXState localState = getTXStateForWrite();
    final TXRegionState txr = localState.writeRegionForRead(dataRegion);
    return localState.lockEntry(entry, key, callbackArg, txr, region,
        dataRegion, writeMode, true, opType, batchingEnabled(),
        TXState.LOCK_ENTRY_NOT_FOUND, null);
  }

  public final Object lockEntry(RegionEntry entry, Object key,
      Object callbackArg, LocalRegion region, LocalRegion dataRegion,
      boolean writeMode, final byte opType, final int[] resultFlags) {
    final TXState localState = getTXStateForWrite();
    final TXRegionState txr = localState.writeRegionForRead(dataRegion);
    return localState.lockEntry(entry, key, callbackArg, txr, region,
        dataRegion, writeMode, true, opType, batchingEnabled(),
        TXState.LOCK_ENTRY_NOT_FOUND, resultFlags);
  }

  /*
  public final void unlockEntry(final RegionEntry entry, final Object key,
      final LocalRegion region, final LocalRegion dataRegion, boolean writeMode) {
    final TXState localState = getTXStateForWrite();
    localState.unlockEntry(entry, key, region, dataRegion, writeMode);
  }
  */

  public final TXManagerImpl getTxMgr() {
    return this.txManager;
  }

  public final void setObserver(TransactionObserver observer) {
    this.txObserver = observer;
  }

  /**
   * The list of existing SH locks that have been acquired in this transaction
   * but not accounted for in {@link TXState} (e.g. due to temporary locks for
   * iterators ) via TXEntryState, so need to be released in cleanup on error or
   * before commit. Currently used for GemFireXD iterators and REPEATABLE_READ
   * isolation level.
   * 
   * The choice to use multiple ArrayLists instead of List of object+region
   * pairs is a deliberate one to minimize the memory overhead of the same.
   */
  public TXState.ArrayListAppend[] getTSSPendingReadLocks(Object context) {
    return tssPendingReadLocks.get();
  }

  /**
   * Same as {@link #getTSSPendingReadLocks(Object)} but try to search if
   * required when context is not available.
   */
  public TXState.ArrayListAppend[] getTSSPendingReadLocks() {
    return tssPendingReadLocks.get();
  }

  public final TransactionObserver getObserver() {
    return this.txObserver;
  }

  /**
   * Remove this TX from the hosted tx list if its empty.
   */
  public final void removeSelfFromHostedIfEmpty(Object callbackArg) {
    final TXState localState = this.localTXState;
    // cleanup the pending SH locks, if any, before returning
    if (localState != null) {
      localState.pendingReadLocksCleanup(getLockingPolicy(), null, null);
    }
    if (!isCoordinator()) {
      TXManagerImpl.getOrCreateTXContext().setPendingTXId(null);
      if (isEmpty()) {
        removeSelfFromHosted(callbackArg, true);
      }
    }
  }

  protected final boolean isEmpty() {
    final TXState localState = this.localTXState;
    return (localState == null || localState.isEmpty())
        && this.refCount.get() <= 0;
  }

  protected boolean removeIfEmpty(Object callbackArg) {
    return isEmpty();
  }

  /** any post removal cleanup actions */
  protected void cleanupEmpty(Object callbackArg) {
  }

  protected final void removeSelfFromHosted(final Object callbackArg,
      final boolean doRemove) {
    if (doRemove) {
      TXStateProxy tx = this.txManager.removeHostedTXState(this.txId,
          checkEmpty, callbackArg, null);
      if (tx != null) {
        tx.cleanupEmpty(callbackArg);
      }
    }
    else {
      cleanupEmpty(callbackArg);
    }
  }

  protected final int incRefCount() {
    final int newCount = this.refCount.incrementAndGet();
    if (LOG_FINE) {
      getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
          this.txId.shortToString() + ": incremented refCount to " + newCount);
    }
    return newCount;
  }

  protected final int decRefCount() {
    final int newCount = this.refCount.decrementAndGet();
    if (LOG_FINE) {
      getTxMgr().getLogger().info(LocalizedStrings.DEBUG,
          this.txId.shortToString() + ": decremented refCount to " + newCount);
    }
    return newCount;
  }

  /**
   * Interface that encapsulates the various steps of an update operation
   * (put/create/destroy/invalidate). This allows the handling of all operations
   * to be consistent via a common {@link TXStateProxy#performOp} method.
   * 
   * @author swale
   * @since 7.0
   * 
   * @param <P>
   *          the type of {@link DirectReplyProcessor} for the operation
   */
  private interface PerformOp<P extends DirectReplyProcessor> {

    /**
     * Prepare a message to send to given node for cache write. This message is
     * piggybacked with any pending batch of operations for the node.
     * 
     * TODO: TX: if this method is ever used, then add calls to
     * DistributionAdvisor.start/endOperation for state flush
     */
    DirectReplyMessage preparePreferredNode(InternalDistributedSystem sys,
        InternalDistributedMember preferredNode, LocalRegion r,
        PartitionedRegion pr, EntryEventImpl event, Object expectedOldValue,
        int flags);

    /**
     * Send a message to given node for cache write but do not wait for replies.
     */
    P sendRemotelyPreferredNode(InternalDistributedMember preferredNode,
        EntryEventImpl event, LocalRegion r, PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException, RemoteOperationException;

    /**
     * Send message to given recipients but do not wait for replies.
     */
    P sendRemotely(Set<InternalDistributedMember> recipients,
        EntryEventImpl event, LocalRegion r, PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException, RemoteOperationException;

    /**
     * Do the operation on the local node which is a store for given event and
     * return the TXEntryState operated on.
     */
    boolean operateLocally(TXState txState, EntryEventImpl event,
        Object expectedOldValue, long lastModified, boolean markPending,
        int flags);

    /**
     * Wait for the result from remote node sent via one of
     * {@link #sendRemotely} or {@link #sendRemotelyPreferredNode}.
     */
    boolean waitForRemoteResult(P response,
        Set<InternalDistributedMember> recipients, EntryEventImpl event,
        LocalRegion r, PartitionedRegion pr, int flags)
        throws PrimaryBucketException, ForceReattemptException,
        RemoteOperationException;

    /**
     * Get the map of exceptions received from each of the nodes.
     */
    Map<InternalDistributedMember, ReplyException> getResponseExceptions(
        P response);

    boolean operateOnSharedDataView(EntryEventImpl event, Object expectedOldValue,
        boolean cacheWrite, long lastModified, int flags);
  }

  private static final PerformOp<PutMessage.PutResponse> performPRPut
   = new PerformOp<PutMessage.PutResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      if (LOG_FINE) {
        final LogWriterI18n logger = r.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "putEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            +", sending it back to region for snapshot isolation.");
      }
      return r.getSharedDataView().putEntry(event, (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue, (flags & REQUIRED_OLD_VAL) != 0,
          cacheWrite, lastModified, (flags & OVERWRITE_DESTROYED) != 0);
    }

    public final PutMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, LocalRegion r,
        final PartitionedRegion pr, final EntryEventImpl event,
        final Object expectedOldValue, final int flags) {
      if (flags == 0) {
        return PutMessage.prepareSend(sys, preferredNode, pr, event,
            System.currentTimeMillis(), false, false, expectedOldValue, false,
            true);
      }
      return PutMessage.prepareSend(sys, preferredNode, pr, event,
          System.currentTimeMillis(), (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, true);
    }

    public final PutMessage.PutResponse sendRemotelyPreferredNode(
        final InternalDistributedMember preferredNode,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        final Object expectedOldValue, final long lastModified, final int flags)
        throws ForceReattemptException {
      if (flags == 0) {
        return pr.sendPutRemotely(preferredNode, event, event.getEventTime(0L),
            false, false, expectedOldValue, false, true);
      }
      return pr.sendPutRemotely(preferredNode, event, event.getEventTime(0L),
          (flags & IF_NEW) != 0, (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, true);
    }

    public final PutMessage.PutResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        final Object expectedOldValue, final long lastModified, final int flags)
        throws ForceReattemptException {
      if (flags == 0) {
        return pr.sendPutRemotely(recipients, event, event.getEventTime(0L),
            false, false, expectedOldValue, false, false);
      }
      return pr.sendPutRemotely(recipients, event, event.getEventTime(0L),
          (flags & IF_NEW) != 0, (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, false);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, final Object expectedOldValue,
        final long lastModified, final boolean markPending, final int flags) {
      if (flags == 0) {
        return txState.putEntryLocally(event, false, false, expectedOldValue,
            false, markPending, lastModified, false);
      }
      return txState.putEntryLocally(event, (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, markPending, lastModified,
          (flags & OVERWRITE_DESTROYED) != 0);
    }

    public final boolean waitForRemoteResult(
        final PutMessage.PutResponse response,
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        final int flags) throws PrimaryBucketException, ForceReattemptException {
      return pr.waitForRemotePut(response, recipients, event,
          (flags & REQUIRED_OLD_VAL) != 0);
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(PutMessage.PutResponse response) {
      return response.getExceptions();
    }
  };

  private static final PerformOp<RemotePutMessage.RemotePutResponse> performDRPut
   = new PerformOp<RemotePutMessage.RemotePutResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      if (LOG_FINE) {
        final LogWriterI18n logger = r.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "putEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            +", sending it back to region for snapshot isolation.");
      }
      return r.getSharedDataView().putEntry(event, (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue, (flags & REQUIRED_OLD_VAL) != 0,
          cacheWrite, lastModified, (flags & OVERWRITE_DESTROYED) != 0);
    }

    public final RemotePutMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, final LocalRegion r,
        PartitionedRegion pr, final EntryEventImpl event,
        final Object expectedOldValue, final int flags) {
      if (flags == 0) {
        return RemotePutMessage.prepareSend(sys, preferredNode, pr, event,
            System.currentTimeMillis(), false, false, expectedOldValue, false,
            true, true, false);
      }
      return RemotePutMessage.prepareSend(sys, preferredNode, pr, event,
          System.currentTimeMillis(), (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, true, true, false);
    }

    public final RemotePutMessage.RemotePutResponse sendRemotelyPreferredNode(
        final InternalDistributedMember preferredNode,
        final EntryEventImpl event, final LocalRegion r,
        PartitionedRegion pr, final Object expectedOldValue,
        final long lastModified, final int flags)
        throws ForceReattemptException, RemoteOperationException {
      if (flags == 0) {
        return RemotePutMessage.send(preferredNode, r, event, lastModified,
            false, false, expectedOldValue, false, true, true, false);
      }
      return RemotePutMessage.send(preferredNode, r, event, lastModified,
          (flags & IF_NEW) != 0, (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, true, true, false);
    }

    public final RemotePutMessage.RemotePutResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, final LocalRegion r,
        PartitionedRegion pr, final Object expectedOldValue,
        final long lastModified, final int flags)
        throws ForceReattemptException, RemoteOperationException {
      if (flags == 0) {
        return RemotePutMessage.send(recipients, r, event, lastModified, false,
            false, expectedOldValue, false, false, true, false);
      }
      return RemotePutMessage.send(recipients, r, event, lastModified,
          (flags & IF_NEW) != 0, (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, false, false, false);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, final Object expectedOldValue,
        final long lastModified, final boolean markPending, final int flags) {
      if (flags == 0) {
        return txState.putEntryLocally(event, false, false, expectedOldValue,
            false, markPending, lastModified, false);
      }
      return txState.putEntryLocally(event, (flags & IF_NEW) != 0,
          (flags & IF_OLD) != 0, expectedOldValue,
          (flags & REQUIRED_OLD_VAL) != 0, markPending, lastModified,
          (flags & OVERWRITE_DESTROYED) != 0);
    }

    public final boolean waitForRemoteResult(
        final RemotePutMessage.RemotePutResponse response,
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, final LocalRegion r, PartitionedRegion pr,
        int flags) throws CacheException, RemoteOperationException {
      final RemotePutMessage.PutResult result = response.waitForResult();
      event.setOldValue(result.oldValue, true/*force*/);
      return result.returnValue;
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(RemotePutMessage.RemotePutResponse response) {
      return response.getExceptions();
    }
  };

  private static final PerformOp<PartitionResponse> performPRDestroy
   = new PerformOp<PartitionResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      final LogWriterI18n logger = r.getLogWriterI18n();
      if(LOG_FINE) {
        logger.info(LocalizedStrings.DEBUG, "destroyExistingEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            + ", sending it back to region for snapshot isolation.");
      }
      r.getSharedDataView().destroyExistingEntry(event, cacheWrite, expectedOldValue);
      return true;
    }

    public final DirectReplyMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, LocalRegion r,
        final PartitionedRegion pr, final EntryEventImpl event,
        final Object expectedOldValue, int flags) {
      return DestroyMessage.prepareSend(sys, preferredNode, pr, event,
          expectedOldValue, true);
    }

    public final PartitionResponse sendRemotelyPreferredNode(
        final InternalDistributedMember preferredNode,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        final Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException {
      return pr.sendDestroyRemotely(preferredNode, event, expectedOldValue,
          true);
    }

    public final PartitionResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        final Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException {
      return pr.sendDestroyRemotely(recipients, event, expectedOldValue, false);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, final Object expectedOldValue,
        long lastModified, final boolean markPending, final int flags) {
      return txState.txDestroyExistingEntry(event,
          (flags & SKIP_CACHEWRITE) == 0, markPending, expectedOldValue);
    }

    public final boolean waitForRemoteResult(final PartitionResponse response,
        final Set<InternalDistributedMember> recipients, EntryEventImpl event,
        LocalRegion r, final PartitionedRegion pr, int flags)
        throws PrimaryBucketException, ForceReattemptException {
      pr.waitForRemoteDestroy((DestroyResponse)response, recipients, event);
      return true;
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(PartitionResponse response) {
      return response.getExceptions();
    }
  };

  private static final PerformOp<RemoteOperationResponse> performDRDestroy
   = new PerformOp<RemoteOperationResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      final LogWriterI18n logger = r.getLogWriterI18n();
      if (LOG_FINE) {
        logger.info(LocalizedStrings.DEBUG, "destroyExistingEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            + ", sending it back to region for snapshot isolation.");
      }
      r.getSharedDataView().destroyExistingEntry(event, cacheWrite, expectedOldValue);
      return true;
    }

    public final DirectReplyMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, final LocalRegion r,
        PartitionedRegion pr, final EntryEventImpl event,
        final Object expectedOldValue, int flags) {
      return RemoteDestroyMessage.prepareSend(sys, preferredNode, r, event,
          expectedOldValue, true, false, false);
    }

    public final RemoteOperationResponse sendRemotelyPreferredNode(
        final InternalDistributedMember preferredNode,
        final EntryEventImpl event, final LocalRegion r, PartitionedRegion pr,
        final Object expectedOldValue, long lastModified, int flags)
        throws RemoteOperationException {
      return RemoteDestroyMessage.send(preferredNode, r, event,
          expectedOldValue, true, false, false);
    }

    public final RemoteOperationResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, final LocalRegion r, PartitionedRegion pr,
        final Object expectedOldValue, long lastModified, int flags)
        throws RemoteOperationException {
      return RemoteDestroyMessage.send(recipients, r, event, expectedOldValue,
          false, false, false);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, final Object expectedOldValue,
        long lastModified, final boolean markPending, final int flags) {
      return txState.txDestroyExistingEntry(event,
          (flags & SKIP_CACHEWRITE) == 0, markPending, expectedOldValue);
    }

    public final boolean waitForRemoteResult(
        final RemoteOperationResponse response,
        Set<InternalDistributedMember> recipients, EntryEventImpl event,
        LocalRegion r, PartitionedRegion pr, int flags)
        throws RemoteOperationException {
      response.waitForCacheException();
      return true;
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(RemoteOperationResponse response) {
      return response.getExceptions();
    }
  };

  private static final PerformOp<InvalidateMessage.InvalidateResponse>
   performPRInvalidate = new PerformOp<InvalidateMessage.InvalidateResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      if (LOG_FINE) {
        final LogWriterI18n logger = r.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "invalidateExistingEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            +", sending it back to region for snapshot isolation.");
      }
      r.getSharedDataView().invalidateExistingEntry(event,
          (flags &INVOKE_CALLBACKS) !=0, (flags & FORCE_NEW_ENTRY)!=0);
      return true;
    }

    public final DirectReplyMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, LocalRegion r,
        final PartitionedRegion pr, final EntryEventImpl event,
        Object expectedOldValue, int flags) {
      return InvalidateMessage.prepareSend(sys, preferredNode, pr, event);
    }

    public final InvalidateMessage.InvalidateResponse sendRemotelyPreferredNode(
        final InternalDistributedMember preferredNode,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException {
      return pr.sendInvalidateRemotely(preferredNode, event);
    }

    public final InvalidateMessage.InvalidateResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, LocalRegion r, final PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws ForceReattemptException {
      return pr.sendInvalidateRemotely(recipients, event);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, final Object expectedOldValue,
        long lastModified, final boolean markPending, final int flags) {
      return txState.txInvalidateExistingEntry(event, markPending,
          (flags & INVOKE_CALLBACKS) != 0, (flags & FORCE_NEW_ENTRY) != 0);
    }

    public final boolean waitForRemoteResult(
        final InvalidateMessage.InvalidateResponse response,
        final Set<InternalDistributedMember> recipients, EntryEventImpl event,
        LocalRegion r, final PartitionedRegion pr, int flags)
        throws PrimaryBucketException, ForceReattemptException {
      pr.waitForRemoteInvalidate(response, recipients, event);
      return true;
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(InvalidateMessage.InvalidateResponse response) {
      return response.getExceptions();
    }
  };

  private static final PerformOp<RemoteInvalidateMessage.InvalidateResponse>
   performDRInvalidate = new PerformOp<RemoteInvalidateMessage.InvalidateResponse>() {

    public final boolean operateOnSharedDataView(EntryEventImpl event,
        Object expectedOldValue, boolean cacheWrite, long lastModified,
        int flags) {
      final LocalRegion r = event.getLocalRegion();
      if (LOG_FINE) {
        final LogWriterI18n logger = r.getLogWriterI18n();
        logger.info(LocalizedStrings.DEBUG, "invalidateExistingEntry Region " + r.getFullPath()
            + ", event: " + (LOG_FINEST ? event.toString()
            : event.shortToString()) + " for " + event.getTXState().getTransactionId().toString()
            +", sending it back to region for snapshot isolation.");
      }
      r.getSharedDataView().invalidateExistingEntry(event,
          (flags &INVOKE_CALLBACKS) !=0, (flags & FORCE_NEW_ENTRY)!=0);
      return true;
    }

    public final DirectReplyMessage preparePreferredNode(
        final InternalDistributedSystem sys,
        final InternalDistributedMember preferredNode, final LocalRegion r,
        PartitionedRegion pr, final EntryEventImpl event,
        Object expectedOldValue, int flags) {
      return RemoteInvalidateMessage.prepareSend(sys, preferredNode, r, event,
          false, false);
    }

    public final RemoteInvalidateMessage.InvalidateResponse
      sendRemotelyPreferredNode(final InternalDistributedMember preferredNode,
        final EntryEventImpl event, final LocalRegion r, PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws RemoteOperationException {
      return RemoteInvalidateMessage
          .send(preferredNode, r, event, false, false);
    }

    public final RemoteInvalidateMessage.InvalidateResponse sendRemotely(
        final Set<InternalDistributedMember> recipients,
        final EntryEventImpl event, final LocalRegion r, PartitionedRegion pr,
        Object expectedOldValue, long lastModified, int flags)
        throws RemoteOperationException {
      return RemoteInvalidateMessage.send(recipients, r, event, false, false);
    }

    public final boolean operateLocally(final TXState txState,
        final EntryEventImpl event, Object expectedOldValue, long lastModified,
        final boolean markPending, final int flags) {
      return txState.txInvalidateExistingEntry(event, markPending,
          (flags & INVOKE_CALLBACKS) != 0, (flags & FORCE_NEW_ENTRY) != 0);
    }

    public final boolean waitForRemoteResult(
        final RemoteInvalidateMessage.InvalidateResponse response,
        Set<InternalDistributedMember> recipients, EntryEventImpl event,
        LocalRegion r, PartitionedRegion pr, int flags)
        throws RemoteOperationException {
      response.waitForResult();
      return true;
    }

    public final Map<InternalDistributedMember, ReplyException>
        getResponseExceptions(RemoteInvalidateMessage.InvalidateResponse
            response) {
      return response.getExceptions();
    }
  };

  @Override
  public Iterator<?> getLocalEntriesIterator(Set<Integer> bucketSet,
      boolean primaryOnly, boolean forUpdate, boolean includeValues,
      LocalRegion currRegion, boolean fetchRemote) {
    // We need to support this.
    final TXState localState = getTXStateForRead();
    if (localState != null) {
      //TODO: should tx fetch remote
      return localState.getLocalEntriesIterator(bucketSet, primaryOnly,
          forUpdate, includeValues, currRegion, fetchRemote);
    }
    return currRegion.getSharedDataView().getLocalEntriesIterator(bucketSet,
        primaryOnly, forUpdate, includeValues, currRegion, fetchRemote);

//    throw new IllegalStateException("TXStateProxy.getLocalEntriesIterator: "
//        + "this method is intended to be called only for PRs and no txns");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {
    // Do nothing. Not applicable for transactions.    
  }

  public final void addToBePublishedEvents(THashMap events) {
    if (this.eventsToBePublished != null) {
      if (events != null && !events.isEmpty()) {
        this.eventsToBePublished.putAll(events);
      }
    }
    else {
      this.eventsToBePublished = events != null && !events.isEmpty() ? events
          : null;
    }
  }

  @SuppressWarnings("unchecked")
  public final Map<String, TObjectLongHashMapDSFID> getToBePublishedEvents() {
    return this.eventsToBePublished;
  }

  /** need to hold this.attemptReadLock before invocation */
  private final boolean hasParallelWAN() {
    final SetWithCheckpoint regions = this.regions;
    final int numRegions = regions.size();
    for (int index = 0; index < numRegions; index++) {
      final Object r = regions.elementAt(index);
      if (r instanceof PartitionedRegion) {
        final PartitionedRegion pr = (PartitionedRegion)r;
        if (pr.isHDFSRegion() || pr.isLocalParallelWanEnabled()) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void setExecutionSequence(int execSeq) {
    if (execSeq > 0) {
      this.currentExecutionSeq = execSeq;
    }
  }

  public void setColumnRolloverDisabled(boolean disabled) {
    this.columnRolloverDisabled = disabled;
  }

  public boolean isColumnRolloverDisabled() {
    return this.columnRolloverDisabled;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getExecutionSequence() {
    return this.currentExecutionSeq;
  }
  
  public void rollback(int savepoint) {
    final DM dm = this.txManager.getDM();
    TXRollBackToSavepointMsg.send(dm.getSystem(), dm, this, savepoint);
  }

  public boolean isSnapshot() {
    return getLockingPolicy() == LockingPolicy.SNAPSHOT;
  }

  @Override
  public void recordVersionForSnapshot(Object member, long version, Region region) {
    final TXState localState = getTXStateForRead();
    localState.recordVersionForSnapshot(member, version, region);
  }
}
