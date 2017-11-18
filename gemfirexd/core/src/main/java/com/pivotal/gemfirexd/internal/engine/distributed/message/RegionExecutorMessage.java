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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.ColocationHelper;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalDataSet;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PRHARedundancyProvider;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RetryTimeKeeper;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.ProxyBucketRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.execute.InternalResultSender;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor.BucketVisitor;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectObjectProcedure;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * All function messages that need to execute on a single region should extend
 * this class.
 * 
 * @author swale
 */
public abstract class RegionExecutorMessage<T> extends GfxdFunctionMessage<T>
    implements InternalRegionFunctionContext {

  protected BitSetSet bucketBitSet;

  // transient arguments used as execution parameters

  protected transient LocalRegion region;

  protected transient THashSet colocatedRegions;

  protected transient Set<Object> routingObjects;

  /** cached colocated LocalDataSets */
  protected transient ArrayList<LocalDataSet> colocatedDataSets;

  protected transient HashMapOrSet membersToBucketIds;
  
  private THashMap ncjMetaData = null;

  protected THashMap getNcjMetaData() {
    return ncjMetaData;
  }

  protected void setNcjMetaData(THashMap ncjMetaInfo) {
    this.ncjMetaData = ncjMetaInfo;
  }

  private int numRecipients;

  private byte sendToAllReplicates;
  // possible values for sendToAllReplicates
  private static final byte SEND_ALL_REPLICATES_INCLUDE_ADMIN = 0x1;
  private static final byte SEND_ALL_REPLICATES_EXCLUDE_ADMIN = 0x2;

  // bitmasks used in AbstractOperationMessage.computeCompressedShort
  protected static final short HAS_BUCKET_BITSET =
    AbstractOperationMessage.UNRESERVED_FLAGS_START;
  /** the unreserved flags start for child classes */
  protected static final short UNRESERVED_FLAGS_START =
    (HAS_BUCKET_BITSET << 1);
  
  public static final BucketVisitor<HashMapOrSet> collectPrimaries =
      new CollectPrimaries();

  static final BucketVisitor<RegionExecutorMessage<?>> collectPreferredNodes =
    new CollectPreferredNodes();
  
  static final BucketVisitor<RegionExecutorMessage<?>> collectPrimaryAndSecondaryNodes = 
      new CollectPrimaryAndSecondaryNodes();

  protected String nameForLogging;

  /**
   * Adds an indicator to add or skip value portion for {@link CollectPrimaries}
   * thus acting like a set.
   */
  public static final class HashMapOrSet extends THashMap {

    private static final long serialVersionUID = 2189213582854257462L;

    private final boolean hasValues;
    private boolean isPersistent;

    public HashMapOrSet() {
      this.hasValues = true;
    }

    public HashMapOrSet(LocalRegion r) {
      this(true, r);
    }

    public HashMapOrSet(boolean hasValues, final LocalRegion r) {
      this.hasValues = hasValues;
      this.isPersistent = GemFireXDUtils.isPersistent(r);
    }

    public HashMapOrSet(final int initialCapacity, final float loadFactor,
        boolean hasValues, final LocalRegion r) {
      super(initialCapacity, loadFactor);
      this.hasValues = hasValues;
      this.isPersistent = GemFireXDUtils.isPersistent(r);
    }

    public final boolean hasValues() {
      return this.hasValues;
    }

    public final boolean isPersistent() {
      return this.isPersistent;
    }

    public final void setIsPersistent(boolean persistent) {
      this.isPersistent = persistent;
    }
  }

  /** Empty constructor for deserialization. Not to be invoked directly. */
  protected RegionExecutorMessage(boolean ignored) {
    super(true);
  }

  protected RegionExecutorMessage(ResultCollector<Object, T> collector,
      LocalRegion region, Set<Object> routingObjects, TXStateInterface tx,
      boolean timeStatsEnabled, boolean abortOnLowMemory) {
    super(collector, tx, timeStatsEnabled, abortOnLowMemory);
    this.region = region;
    this.routingObjects = routingObjects;
  }

  /** copy constructor */
  protected RegionExecutorMessage(final RegionExecutorMessage<T> other) {
    super(other);
    this.region = other.region;
    this.routingObjects = other.routingObjects;
    this.numRecipients = other.numRecipients;
    this.hasSetOperatorNode = other.hasSetOperatorNode;
    this.ncjMetaData = other.ncjMetaData;
    // check for TX match with that in thread-local
    // TXState can be passed as null for non-transactional messages
    assert getTXState() == null
        || getTXState() == TXManagerImpl.getCurrentTXState(): "unexpected "
        + "mismatch of current TX " + TXManagerImpl.getCurrentTXState()
        + ", and TX passed to message " + getTXState();
  }

  /**
   * @return the region
   */
  public LocalRegion getRegion() {
    return region;
  }

  public final String logName() {
    final String logName = this.nameForLogging;
    if (logName != null) {
      return logName;
    }
    else {
      return (this.nameForLogging = getClass().getSimpleName());
    }
  }

  @Override
  public final void setSendToAllReplicates(boolean includeAdmin) {
    this.sendToAllReplicates = includeAdmin ? SEND_ALL_REPLICATES_INCLUDE_ADMIN
        : SEND_ALL_REPLICATES_EXCLUDE_ADMIN;
  }

  /**
   * Set membersToBucketIds
   * @throws StandardException 
   */
  protected void setMembersToBucketIds(RetryTimeKeeper retryTime)
      throws StandardException {
    final PartitionedRegion pregion = (PartitionedRegion)this.region;
    final boolean optimizeForWrite = optimizeForWrite() || pregion.isHDFSReadWriteRegion();
    this.membersToBucketIds = new HashMapOrSet(pregion);
    final Set<PartitionedRegion> otherPRs = this.otherPartitionRegions;

    if (this.routingObjects == null || this.routingObjects.size() == 0) {
      final RegionAdvisor advisor = pregion.getRegionAdvisor();
      if (optimizeForWrite) {
        advisor.accept(collectPrimaries, this.membersToBucketIds);
      }
      else if (withSecondaries()) {
        advisor.accept(collectPrimaryAndSecondaryNodes, this);
      }
      else {
        advisor.accept(collectPreferredNodes, this);
      }
      if (membersToBucketIds.isEmpty()) {
        // no buckets found; probably no datastores are around
        GemFireXDUtils.checkForInsufficientDataStore(pregion);
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName()
            + ": execute on all "
            + (optimizeForWrite ? "primary" : "preferred")
            + " buckets with pruned map: " + membersToBucketIds);
      }

      if (this.hasSetOperatorNode && otherPRs != null) {
        // Handle union, intersect or except operators
        // Currently message will only go to nodes with driver table only
        // Lets also send them to nodes where driver table is not present
        // but primary buckets of the other tables of union/intersect/except
        // query are present
        // Hence, query will consider both primary and secondary buckets of
        // driver table, but would consider only primaries for non-driver
        // tables.
        final BitSetSet nullBucketSet = new BitSetSet(0);
        final HashMapOrSet otherMembersToBucketIds = new HashMapOrSet();
        final Iterator<PartitionedRegion> partitionIter = otherPRs.iterator();

        PartitionedRegion otherPartition = null;
        RegionAdvisor otherAdvisor = null;
        InternalDistributedMember dataMember = null;
        while (partitionIter.hasNext()) {
          otherPartition = partitionIter.next();
          if (otherPartition != null) {
            otherAdvisor = otherPartition.getRegionAdvisor();
            otherMembersToBucketIds.setIsPersistent(GemFireXDUtils
                .isPersistent(otherPartition));
            // ToDo should we use new instance of collectPrimaries?
            otherAdvisor.accept(collectPrimaries, otherMembersToBucketIds);

            if (!otherMembersToBucketIds.isEmpty()) {
              @SuppressWarnings("unchecked")
              final Set<DistributedMember> otherMembers =
                  otherMembersToBucketIds.keySet();
              final Iterator<DistributedMember> memberIter = otherMembers
                  .iterator();
              while (memberIter.hasNext()) {
                dataMember = (InternalDistributedMember)memberIter.next();
                if (!membersToBucketIds.containsKey(dataMember)) {
                  membersToBucketIds.put(dataMember, nullBucketSet);
                }
              }
              otherMembersToBucketIds.clear();
            }
          }
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName()
              + ": Revised Pruned bucket map for Union, Intersect Or Except " 
              + "operators query consideration: "
              + membersToBucketIds);
        }
      }
      else {
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName()
              + ": No changes to Pruned bucket map for Union, Intersect "
              + "Or Except operators query consideration");
        }
      }
    }
    else {
      InternalDistributedMember member;
      final BitSetSet allBucketIds = new BitSetSet(pregion
          .getPartitionAttributes().getTotalNumBuckets());
      for (Object routingObject : this.routingObjects) {
        final int bucketId = PartitionedRegionHelper.getHashKey(pregion,
            routingObject);
        if (allBucketIds.addInt(bucketId)) {
          if (optimizeForWrite) {
            member = pregion.getOrCreateNodeForBucketWrite(bucketId,
                retryTime);
          }
          else {
            member = pregion.getOrCreateNodeForInitializedBucketRead(
                bucketId, this.possibleDuplicate);
          }
          addBucketIdForMember(membersToBucketIds, member, bucketId,
              pregion);
        }
      }
      if (membersToBucketIds.isEmpty()) {
        // no buckets found; probably no datastores are around
        GemFireXDUtils.checkForInsufficientDataStore(pregion);
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName()
            + ": execute on buckets with routing objects {"
            + this.routingObjects + "} and pruned map: "
            + membersToBucketIds);
      }
    }

    // child colocated region(s) may not have all the buckets created
    // yet (#47210)
    if (otherPRs != null && !this.hasSetOperatorNode) {
      for (final PartitionedRegion pr : otherPRs) {
        this.membersToBucketIds.forEachValue(new TObjectProcedure() {
          @Override
          public boolean execute(Object o) {
            final BitSetSet bucketIds = (BitSetSet)o;
            final RegionAdvisor advisor = pr.getRegionAdvisor();
            for (int bucketId = bucketIds.nextSetBit(0, 0); bucketId >= 0;
                bucketId = bucketIds.nextSetBit(bucketId + 1)) {
              // fast check for existing bucket
              if (advisor.getBucketRedundancy(bucketId) < 0) {
                if (optimizeForWrite) {
                  pr.getOrCreateNodeForBucketWrite(bucketId, null);
                }
                else {
                  pr.getOrCreateNodeForInitializedBucketRead(bucketId,
                      possibleDuplicate);
                }
              }
            }
            return true;
          }
        });
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void executeFunction(final boolean enableStreaming)
      throws StandardException, SQLException {
    final DataPolicy policy = this.region.getAttributes().getDataPolicy();
    final GemFireCacheImpl cache = this.region.getCache();
    final InternalDistributedSystem sys = cache.getSystem();
    final DM dm = sys.getDistributionManager();
    final InternalDistributedMember myId = cache.getMyId();
    final Set<DistributedMember> messageAwareMembers;
    if (this.gfxdCollector != null) {
      messageAwareMembers = this.gfxdCollector.getResultMembers();
    }
    else {
      messageAwareMembers = null;
    }

    this.membersToBucketIds = null;
    if (policy.withPartitioning()) {
      final PartitionedRegion pregion = (PartitionedRegion)this.region;
      RetryTimeKeeper retryTime = null;
      final TXStateInterface tx = getTXState();
      final long beginMapTime = this.timeStatsEnabled ? XPLAINUtil
          .recordTiming(-1) : 0;

      if (this.hasSetOperatorNode) {
        // Handle union, intersect or except operators
        // routingObjects should be null
        assert this.routingObjects == null;
      }

      // [sumedh] Can prune the nodes to a minimum for read nodes case.
      // However, the tradeoff is that the more we parallelize, the better it
      // is considering that each node will do lesser work. So maybe the current
      // way is good enough.
      for (;;) {
        setMembersToBucketIds(retryTime);
        if (this.failedNodes == null) {
          break;
        }
        boolean hasFailedNode = false;
        for (final DistributedMember member : this.failedNodes) {
          if (membersToBucketIds.containsKey(member)) {
            // need to wait for things to stabilize and then calculate things
            if (retryTime == null) {
              retryTime = new RetryTimeKeeper(pregion.getRetryTimeout());
            }
            if (retryTime.overMaximum()) {
              throw new InternalFunctionInvocationTargetException(LocalizedStrings
                  .PRHARedundancyProvider_TIMED_OUT_ATTEMPTING_TO_0_IN_THE_PARTITIONED_REGION__1_WAITED_FOR_2_MS
                      .toLocalizedString(new Object[] {
                          "doing GemFireXD function execution",
                          PRHARedundancyProvider.regionStatus(pregion, null,
                              null, true),
                          Long.valueOf(pregion.getRetryTimeout()) })
                      + PRHARedundancyProvider.TIMEOUT_MSG);
            }
            retryTime.waitToRetryNode();
            hasFailedNode = true;
            break;
          }
        }
        if (!hasFailedNode) {
          this.failedNodes = null;
          break;
        }
        membersToBucketIds.clear();
        mapping_retry_count++;
      }

      final int numMembers = membersToBucketIds.size();

      setNumRecipients(numMembers);
      if (numMembers == 0) {
        this.userCollector.endResults();
        throw new EmptyRegionFunctionException(LocalizedStrings
            .PartitionedRegion_FUNCTION_NOT_EXECUTED_AS_REGION_IS_EMPTY
                .toLocalizedString());
      }
      else {
        final ArrayList<GfxdFunctionMessage<T>> msgsSent;
        if (beginMapTime != 0) {
          this.member_mapping_time = XPLAINUtil.recordTiming(beginMapTime);
          this.begin_scatter_time = XPLAINUtil.currentTimeStamp();
          ArrayList<GfxdFunctionMessage<T>> mmsgs;
          if ((mmsgs = this.membersMsgsSent) == null) {
            this.membersMsgsSent = mmsgs =
                new ArrayList<GfxdFunctionMessage<T>>(numMembers);
          }
          msgsSent = mmsgs;
        }
        else {
          msgsSent = null;
        }

        final Set<DistributedMember> destMembers = membersToBucketIds.keySet();
        final GfxdFunctionReplyMessageProcessor<T> processor;
        if (numMembers > 1) {
          processor = createReplyProcessor(dm, destMembers);
        }
        else {
          processor = createReplyProcessor(dm,
              (InternalDistributedMember)membersToBucketIds.firstKey());
        }
        setProcessor(processor);

        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "RegionExecutorMessage:: set the processor in "
              + this.gfxdCollector + " to " + (this.gfxdCollector != null
                  ? this.gfxdCollector.getProcessor() : null));
        }
        if (SanityManager.isFinerEnabled) {
          SanityManager.DEBUG_PRINT("finer:TRACE", logName()
              + ": executing region message " + toString() + " on nodes "
              + destMembers);
        }

        // any flush for batch TX ops required just before actual message send
        if (tx != null) {
          doTXFlushBeforeExecution(destMembers, null, myId, tx);
        }

        final BitSetSet selfBucketIds = (BitSetSet)membersToBucketIds
            .remove(myId);
        // execute on self at the very last
        try {
         if (this.membersToBucketIds.size() > 0) {
          processor.registerProcessor();
          this.membersToBucketIds.forEachEntry(new TObjectObjectProcedure() {
            private int numMbrs = numMembers;
            @Override
            public final boolean execute(final Object key, final Object value) {
              final long beginTime = beginMapTime != 0 ? XPLAINUtil
                  .recordTiming(-1) : 0;
              RegionExecutorMessage<T> msg;
              InternalDistributedMember member = (InternalDistributedMember)key;
              if (--this.numMbrs > 0) {
                msg = RegionExecutorMessage.this.clone();
                // Handle union, intersect or except operators
                // All node, except one, will ignore replicated regions
                msg.setIgnoreReplicateIfSetOperators(true);
              }
              else {
                msg = RegionExecutorMessage.this;
                // Handle union, intersect or except operators
                // only one node will handle replicated regions
                msg.setIgnoreReplicateIfSetOperators(false);
              }
              msg.setArgsForMember(member, messageAwareMembers);
              msg.bucketBitSet = (BitSetSet)value;
              try {
                msg.executeOnMember(sys, dm, member, false, enableStreaming);
              } catch (StandardException se) {
                throw new FunctionExecutionException(se);
              } catch (SQLException sqle) {
                throw new FunctionExecutionException(sqle);
              }
              if (beginTime != 0) {
                if (msg != RegionExecutorMessage.this) {
                  msg.process_time = XPLAINUtil.recordTiming(beginTime);
                }
                else {
                  msg.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
                }
                msgsSent.add(msg);
              }
              return true;
            }
          });
         }
        } catch (FunctionExecutionException fee) {
          if (fee.getCause() instanceof StandardException) {
            throw (StandardException)fee.getCause();
          }
          if (fee.getCause() instanceof SQLException) {
            throw (SQLException)fee.getCause();
          }
          throw fee;
        }
        // self is always the last one so will use this message itself
        if (selfBucketIds != null) {
          final long beginTime = beginMapTime != 0 ? XPLAINUtil
              .recordTiming(-1) : 0;
          setArgsForMember(myId, messageAwareMembers);
          this.bucketBitSet = selfBucketIds;
          // clear any cached LocalDataSets from previous retry
          this.colocatedDataSets = null;
          this.executeOnMember(sys, dm, myId, true, enableStreaming);
          if (beginTime != 0) {
            this.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
            msgsSent.add(this);
          }
        }
      }
    }
    else {
      InternalDistributedMember member;
      final DistributedRegion dreg = (DistributedRegion)this.region;
      Set<InternalDistributedMember> replicates = null;
      this.bucketBitSet = null;
      this.colocatedDataSets = null;
      final long beginMapTime = this.timeStatsEnabled ? XPLAINUtil
          .recordTiming(-1) : 0;
      if (this.sendToAllReplicates != 0) {
        replicates = dreg.getCacheDistributionAdvisor()
            .adviseInitializedReplicates();
        if (policy.withReplication() || policy.withPreloaded()) {
          if (replicates.size() == 0) {
            replicates = new THashSet(2);
          }
          replicates.add(myId);
        }
        // exclude Locators/agents/managers if required (#46036)
        if (this.sendToAllReplicates == SEND_ALL_REPLICATES_EXCLUDE_ADMIN) {
          // loop through all the members to check their VMKind; not very
          // efficient but doesn't matter since this is for the special case
          // of queries involving VTIs + regular tables
          ArrayList<InternalDistributedMember> adminVMs = null;
          final GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
          for (InternalDistributedMember m : replicates) {
            GfxdDistributionAdvisor.GfxdProfile p = advisor.getProfile(m);
            if (p != null && !p.getVMKind().isAccessorOrStore()) {
              if (adminVMs == null) {
                adminVMs = new ArrayList<InternalDistributedMember>(5);
              }
              adminVMs.add(m);
            }
          }
          if (adminVMs != null) {
            for (InternalDistributedMember m : adminVMs) {
              replicates.remove(m);
            }
          }
        }
        member = null;
      }
      else if (policy.withReplication() || policy.withPreloaded()) {
        member = myId;
      }
      else if (!policy.withStorage()) {
        if (this.failedNodes == null) {
          member = dreg.getRandomReplicate();
        }
        else {
          // Random approach is somewhat difficult since in the worst case the
          // failed node may be the only one available and then we could get
          // stuck in a long loop. A little inefficiency for failure scenario
          // will be fine.
          final Iterator<InternalDistributedMember> iter;
          Set<InternalDistributedMember> allMembers = dreg
              .getCacheDistributionAdvisor().adviseInitializedReplicates();
          member = null;
          allMembers.removeAll(this.failedNodes);
          if (allMembers.size() > 0) {
            int pos = PartitionedRegion.rand.nextInt(allMembers.size());
            iter = allMembers.iterator();
            while (pos-- >= 0 && iter.hasNext()) {
              member = iter.next();
            }
          }
        }
      }
      else {
        throw new GemFireXDRuntimeException(logName()
            + "#execute: cannot handle policy " + policy + " for region: "
            + region);
      }
      if (member != null) {
        setArgsForMember(member, messageAwareMembers);
      }
      else if (replicates == null || replicates.size() == 0) {
        throw new NoMemberFoundException(LocalizedStrings
            .DistributedRegion_NO_REPLICATED_REGION_FOUND_FOR_EXECUTING_FUNCTION_0
                .toLocalizedString(getClass().getName()));
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName()
            + ": executing region message " + this.toString() + " on node "
            + member);
      }

      long beginTime = 0;
      ArrayList<GfxdFunctionMessage<T>> msgsSent = null;
      if (beginMapTime != 0) {
        member_mapping_time = XPLAINUtil.recordTiming(beginMapTime);
        if ((msgsSent = this.membersMsgsSent) == null) {
          membersMsgsSent = msgsSent = new ArrayList<GfxdFunctionMessage<T>>(1);
        }
        begin_scatter_time = XPLAINUtil.currentTimeStamp();
        beginTime = XPLAINUtil.recordTiming(-1);
      }

      // any flush for batch TX ops required just before actual message send
      final TXStateInterface tx = getTXState();
      if (tx != null) {
        doTXFlushBeforeExecution(null, member, myId, tx);
      }

      // reference check here is fine since myId has been assigned to member
      if (this.sendToAllReplicates == 0) {
        setNumRecipients(1);
        executeOnMember(sys, dm, member, member == myId, enableStreaming);
      }
      else {
        setNumRecipients(replicates.size());
        @SuppressWarnings({ "rawtypes" })
        final Set<DistributedMember> targets = (Set)replicates;
        executeOnMembers(sys, dm, targets, enableStreaming);
      }

      if (beginTime != 0) {
        this.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
        msgsSent.add(this);
      }
    }
  }

  /**
   * @return the membersToBucketIds
   */
  protected HashMapOrSet getMembersToBucketIds() {
    return membersToBucketIds;
  }

  @Override
  protected final GemFireCacheImpl getGemFireCache() {
    return this.region.getGemFireCache();
  }

  public final void checkAllBucketsHosted() throws BucketMovedException {
    // check if buckets have moved; if region is null then it means
    // getPRBucketSet() was never invoked on remote node, so no need to check
    // for moved buckets anyways since bucketSet information was never used
    final int movedBucketId;
    final PartitionedRegion pr;
    if (this.bucketBitSet != null && this.region != null) {
      pr = (PartitionedRegion)this.region;
      if ((movedBucketId = areAllBucketsHosted(pr)) >= 0) {
        throw new BucketMovedException(
            LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                .toLocalizedString(), movedBucketId, pr.getFullPath());
      }
      if (this.colocatedRegions != null) {
        this.colocatedRegions.forEach(new TObjectProcedure() {
          @Override
          public boolean execute(Object r) {
            final int movedBucketId;
            PartitionedRegion pr = (PartitionedRegion)r;
            if ((movedBucketId = areAllBucketsHosted(pr)) >= 0) {
              throw new BucketMovedException(LocalizedStrings
                  .FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                      .toLocalizedString(), movedBucketId, pr.getFullPath());
            }
            return true;
          }
        });
      }
    }
  }

  protected final void setNumRecipients(final int n) {
    this.numRecipients = n;
    if (this.gfxdCollector != null) {
      this.gfxdCollector.setNumRecipients(n);
    }
  }

  protected final int areAllBucketsHosted(PartitionedRegion pr) {
    final boolean optimizeForWrite = optimizeForWrite();
    final RegionAdvisor advisor = pr.getRegionAdvisor();
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      if (optimizeForWrite) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "checking for primary bucketIds " + this.bucketBitSet);
      }
      else {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "checking for hosted bucketIds " + this.bucketBitSet);
      }
    }
    for (int bucketId = this.bucketBitSet.nextSetBit(0, 0); bucketId >= 0;
         bucketId = this.bucketBitSet.nextSetBit(bucketId + 1)) {
      // TODO vivek: also handle union here
      if (optimizeForWrite) {
        if (!advisor.getBucketAdvisor(bucketId).isPrimary()) {
          return bucketId;
        }
      }
      else {
        if (!advisor.getBucketAdvisor(bucketId).isHosting()) {
          return bucketId;
        }
      }
    }
    return -1;
  }

  protected abstract void setArgsForMember(DistributedMember member,
      Set<DistributedMember> messageAwareMembers);

  /**
   * Sub-classes can do a flush of batched messages if required just before
   * execution on a member. This allows StatementExecutorMessage, for example,
   * to skip the flush for all update messages in
   * {@link #requiresTXFlushBeforeExecution()} and only do it for messages that
   * will not go to the TX coordinator itself.
   * 
   * @param members
   *          if there are multiple target members for the case of PR
   * @param member
   *          if there is a single target member for the case of RR
   * @param self
   *          the distributed ID of this node
   * @param tx
   *          the current {@link TXStateInterface}
   */
  protected void doTXFlushBeforeExecution(Set<DistributedMember> members,
      DistributedMember member, DistributedMember self, TXStateInterface tx) {
  }

  @Override
  public final <K, V> Region<K, V> getDataSet() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @Override
  public final Set<?> getFilter() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @SuppressWarnings("unchecked")
  @Override
  public final <K, V> Region<K, V> getLocalDataSet(Region<K, V> region) {
    if (region.getAttributes().getPartitionAttributes() != null) {
      synchronized (this) {
        if (this.colocatedDataSets == null) {
          this.colocatedDataSets = new ArrayList<LocalDataSet>(4);
        }
        else {
          for (LocalDataSet lds : this.colocatedDataSets) {
            if (lds.getProxy() == region) {
              return lds;
            }
          }
        }
        final PartitionedRegion pr = (PartitionedRegion)region;
        final LocalDataSet lds = new LocalDataSet(pr, getPRBucketSet(pr),
            getTXState(pr));
        //lds.setFunctionContext(this); // don't need this
        this.colocatedDataSets.add(lds);
        return lds;
      }
    }
    return null;
  }

  @Override
  public final <K, V> Set<Integer> getLocalBucketSet(Region<K, V> region) {
    if (region.getAttributes().getPartitionAttributes() != null) {
      return getPRBucketSet((PartitionedRegion)region);
    }
    return null;
  }

  private Set<Integer> getPRBucketSet(PartitionedRegion pr) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "RegionExecutorMessage.getPRBucketSet." + " Region="
                + (this.region == null ? "null" : this.region.getDisplayName())
                + " pr=" + (pr == null ? "null" : pr.getDisplayName()));
      }
    }
    
    boolean firstCall = false;
    if (this.region == null) {
      this.region = pr;
      firstCall = true;
    }
    else if (this.region != pr) {
      if (this.hasSetOperatorNodeOnRemote()) {
        // Handle union, intersect or except operators
        // This would return all primary buckets of this region
        // that is actually non-driver tables
        return pr.getDataStore().getAllLocalPrimaryBucketIds();
      }

      // check for colocated region but only with trace on to avoid
      // unnecessary work
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        if (this.region instanceof PartitionedRegion
            && ColocationHelper.getLeaderRegion(pr) != ColocationHelper
                .getLeaderRegion((PartitionedRegion)this.region)) {
          throw new FunctionException("RegionExecutorMessage#getPRBucketSet"
              + ": unexpected invocation for region " + pr.getFullPath()
              + " with target region " + this.region.getFullPath());
        }
      }
      if (this.colocatedRegions == null) {
        this.colocatedRegions = new THashSet(5);
      }
      firstCall = this.colocatedRegions.add(pr);
    }

    // check if all required buckets are hosted here otherwise it may happen
    // that the bucket is not hosted here at this point but gets hosted later
    // before end of execution, so the check at the end of execution will not
    // catch it
    if (this.bucketBitSet != null) {
      final int movedBucketId;
      if (firstCall && (movedBucketId = areAllBucketsHosted(pr)) >= 0) {
        throw new BucketMovedException(
            LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                .toLocalizedString(), movedBucketId, pr.getFullPath());
      }
      return this.bucketBitSet;
    }
    else if (this.hasSetOperatorNodeOnRemote()) {
      // Handle union, intersect or except operators
      // We return bucket sets of driver tables - that one received from query
      // node but if that is null, simply means that we do not expect rows from
      // driver table on this node - but returning null would be wrongly
      // interpreted as:
      // a. for Table scan - "all (primary?) nodes"
      // b. for Index scan - True - that particular row will be included
      // To avoid above scenarios, lets return non-null-but-empty set
      return new BitSetSet(0);
    }
    else {
      return null;
    }
  }

  @Override
  public final InternalResultSender getResultSender() {
    return this;
  }

  @Override
  public final String getFunctionId() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @Override
  public final Object getArguments() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @Override
  public final Map<String, LocalDataSet> getColocatedLocalDataSets() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @Override
  public final int getNumRecipients() {
    return this.numRecipients;
  }

  @Override
  public abstract boolean optimizeForWrite();
  
  public abstract boolean withSecondaries();

  @Override
  protected abstract RegionExecutorMessage<T> clone();

  /**
   * @see AbstractOperationMessage#computeCompressedShort(short)
   */
  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.bucketBitSet != null) {
      flags |= HAS_BUCKET_BITSET;
    }
    return flags;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    InternalDataSerializer.writeArrayLength(this.numRecipients, out);
    if (this.bucketBitSet != null) {
      this.bucketBitSet.toData(out);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.numRecipients = InternalDataSerializer.readArrayLength(in);
    if ((flags & HAS_BUCKET_BITSET) != 0) {
      this.bucketBitSet = BitSetSet.fromData(in);
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    if (this.region != null) {
      sb.append(";region=").append(this.region.getFullPath());
    }
    sb.append(";bucketIds=").append(this.bucketBitSet);
    final Set<Object> routingObjects = this.routingObjects;
    if (routingObjects != null && routingObjects.size() > 0) {
      sb.append(";routingObjects=").append(routingObjects);
    }
    sb.append(";ncjMetaData=").append(this.ncjMetaData);
  }

  static final class CollectPrimaries implements BucketVisitor<HashMapOrSet> {
    public boolean visit(RegionAdvisor advisor, ProxyBucketRegion pbr,
        final HashMapOrSet membersToBucketIds) {
      // for persistent regions there may be offline partitions even with stores
      // available, so check available storage for all buckets for persistence
      final PartitionedRegion pr = pbr.getPartitionedRegion();
      if (pbr.getBucketRedundancy() >= 0 
          || membersToBucketIds.isPersistent()
          || pbr.getPartitionedRegion().isHDFSReadWriteRegion()) {
        final InternalDistributedMember member = pr
            .getOrCreateNodeForBucketWrite(pbr.getBucketId(), null);
        addBucketIdForMember(membersToBucketIds, member, pbr.getBucketId(),
            pr);
      }
      return true;
    }
  }

  static final class CollectPreferredNodes implements
      BucketVisitor<RegionExecutorMessage<?>> {
    public final boolean visit(final RegionAdvisor advisor,
        final ProxyBucketRegion pbr, final RegionExecutorMessage<?> msg) {
      // for persistent regions there may be offline partitions even with stores
      // available, so check available storage for all buckets for persistence
      final PartitionedRegion pr = pbr.getPartitionedRegion();
      if (pbr.getBucketRedundancy() >= 0
          || msg.membersToBucketIds.isPersistent()
          || pbr.getPartitionedRegion().isHDFSReadWriteRegion()) {
        final InternalDistributedMember member = pr
            .getOrCreateNodeForInitializedBucketRead(pbr.getBucketId(),
                msg.possibleDuplicate);
        addBucketIdForMember(msg.membersToBucketIds, member, pbr.getBucketId(),
            pr);
      }
      return true;
    }
  }

  /**
   * This is required to honor query hint withSecondaries=true. 
   * 
   * We determine all primary and secondary buckets 
   * in the accessor node beforehand and let the datastores figure out whether
   * bucket moved during query execution or not. In case bucket gets
   * rebalanced, the query will be retried from accessor with new
   * bucket to node mapping. 
   * @author soubhikc
   *
   */
  static final class CollectPrimaryAndSecondaryNodes implements
      BucketVisitor<RegionExecutorMessage<?>> {
    public final boolean visit(final RegionAdvisor advisor,
        final ProxyBucketRegion pbr, final RegionExecutorMessage<?> msg) {
      // for persistent regions there may be offline partitions even with stores
      // available, so check available storage for all buckets for persistence
      final PartitionedRegion pr = pbr.getPartitionedRegion();
      if (pbr.getBucketRedundancy() >= 0
          || msg.membersToBucketIds.isPersistent()
          || pbr.getPartitionedRegion().isHDFSReadWriteRegion()) {
        // make sure bucket is fully initialized.
        pr.getOrCreateNodeForBucketWrite(pbr.getBucketId(), null);
        
        // now lets add all owned members of the bucket
        for (InternalDistributedMember bowners : pbr.getBucketOwners()) {
          addBucketIdForMember(msg.membersToBucketIds, bowners, pbr.getBucketId(),
              pr);
        }
      }
      return true;
    }
  }

  static void addBucketIdForMember(HashMapOrSet membersToBucketIds,
      DistributedMember member, int bucketId, PartitionedRegion region) {
    if (member != null) {
      if (membersToBucketIds.hasValues) {
        BitSetSet bucketSet = (BitSetSet)membersToBucketIds.get(member);
        if (bucketSet == null) {
          bucketSet = new BitSetSet(region.getPartitionAttributes()
              .getTotalNumBuckets());
          membersToBucketIds.put(member, bucketSet);
        }
        bucketSet.addInt(bucketId);
      }
      else {
        membersToBucketIds.put(member, null);
      }
    }
  }

  /**
   * Must be called by user on QueryNode
   * Handle case of all Replicated Tables While message is being sent to remote
   * node, set that all of its tables are Replicated
   * 
   * @param allTablesReplicated
   *          Are all tables are replicated?
   */
  public void setAllTablesAreReplicatedOnRemote(boolean allTablesReplicated) {
    SanityManager.ASSERT(true, this.getClass().getSimpleName()
        + ": should only be called on an instance of "
        + "sub-class StatementExecutorMessage");
  }

  /**
   * Only used on Remote Nodes
   * Handle case of all Replicated Tables Verifies that Does this message, while
   * at node, has all of its tables Replicated? 
   */
  public boolean allTablesAreReplicatedOnRemote() {
    /*
    SanityManager.ASSERT(true, this.getClass().getSimpleName()
        + ": should only be called on an "
        + "instance of sub-class StatementExecutorMessage");
    */
    return false;
  }

  /*
   * Handle union, intersect or except operators
   * List of all possible regions in query
   * should not be serialized
   */
  protected transient Set<PartitionedRegion> otherPartitionRegions = null;

  /*
   * Handle union, intersect or except operators
   * Add a region to set of regions
   * As single thread uses RegionExecutorMessage - should be thread safe
   */
  public final void setPartitionRegions(Set<PartitionedRegion> regionSet) {
    otherPartitionRegions = regionSet;
  }

  /**
   * NCJ Note: Handle Non Collocated Join
   * 
   * 1. set ncjMetaData
   * 
   * 2. set IS_NCJ_QUERY_ON_REMOTE for serialization use
   */
  public void setNCJoinOnQN(THashMap ncjMetaData, LanguageConnectionContext lcc) {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + " StatementExecutorMessage or PrepStatementExecutorMessage but not "
        + this.getClass().getSimpleName());
  }

  /**
   * NCJ Note: Handle Non Collocated Join, Used on remote node, Ignored at Query
   * Node
   * 
   * @return ncjMetaData
   */
  public THashMap getNCJMetaDataOnRemote() {
    return this.getNcjMetaData();
  }
 
  /**
   * Handle Union, Intersect or Except Operator 
   * Flag at Source Node to identify presence of any set operator
   * Ignored at remote nodes
   */
  protected transient boolean hasSetOperatorNode = false;

  /**
   * Handle Union, Intersect or Except Operator, Must set by user Set
   * hasSetOperatorNode explicitly by users at runtime in subclass
   * StatementExecutorMessage
   */
  public void setHasSetOperatorNode(boolean hasSetOp,
      boolean needKeysFromRemote) {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + "StatementExecutorMessage");
  }

  /**
   * Handle Union, Intersect or Except Operator Verifies that Does this message,
   * when sent at remote node, has valid presence of any set operator Node?
   * Actual use in subclass StatementExecutorMessage
   */
  public boolean hasSetOperatorNodeOnRemote() {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + "StatementExecutorMessage");
    return false;
  }

  /**
   * Handle Intersect or Except Operator Set that need keys from remote Actual
   * use in subclass StatementExecutorMessage
   */
  public boolean needKeysForSetOperatorOnRemote() {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + "StatementExecutorMessage");
    return false;
  }

  /**
   * Handle union, intersect or except operators Verifies that Does this
   * message, when sent at remote node, want replicated tables to be ignored by
   * set operators? Actual use in subclass StatementExecutorMessage
   */
  public boolean doIgnoreReplicatesIfSetOperatorsOnRemote() {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + "StatementExecutorMessage");
    return false;
  }

  /**
   * Handle union, intersect or except operators To be used on source node, Must
   * be set by user
   */
  protected void setIgnoreReplicateIfSetOperators(boolean ignoreReplicate) {
    SanityManager.THROWASSERT(this.getClass().getSimpleName()
        + ": should only be called on an instance of sub-class "
        + "StatementExecutorMessage");
  }
}
