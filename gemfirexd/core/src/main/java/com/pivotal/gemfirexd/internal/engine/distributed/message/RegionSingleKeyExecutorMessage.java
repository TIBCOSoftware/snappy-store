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
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.BucketAdvisor;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor;
import com.gemstone.gemfire.internal.cache.DirectReplyMessage;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.KeyInfo;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PRHARedundancyProvider;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.ProxyBucketRegion;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RetryTimeKeeper;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.PrimaryBucketException;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Function messages that need to execute on a single key in a region can extend
 * this class.
 * 
 * @author swale
 * @since 7.0
 */
public abstract class RegionSingleKeyExecutorMessage extends
    GfxdFunctionMessage<Object> implements DirectReplyMessage {

  protected String regionPath;

  protected int prId = -1;

  protected Object key;

  protected Object callbackArg;

  protected int bucketId = KeyInfo.UNKNOWN_BUCKET;

  protected boolean isSecondaryCopy;

  // transient arguments used as execution parameters

  protected transient LocalRegion region;

  protected transient DistributedRegion dr;
  protected transient PartitionedRegion pr;

  protected transient Object routingObject;

  protected transient ProxyBucketRegion pbr;

  /** the target when a single node has to be targeted */
  protected transient DistributedMember target;

  protected boolean allCopies;

  protected transient final boolean txBatching;

  /**
   * Token.INVALID indicates a dummy reply from a node that just needs to lock
   * the row.
   */
  protected static final Object DUMMY_RESULT = Token.INVALID;

  // flags used in serialization are below

  protected static final short IS_PARTITIONED_TABLE =
    AbstractOperationMessage.UNRESERVED_FLAGS_START;
  protected static final short HAS_BUCKET_ID = (IS_PARTITIONED_TABLE << 1);
  /**
   * Indicates that this node is the "special chosen" one in case the message is
   * sent to all copies of the data. This allows the body of function to return
   * different results as required.
   */
  protected static final short SECONDARY_COPY = (HAS_BUCKET_ID << 1);
  protected static final short ALL_COPIES = (SECONDARY_COPY << 1);
  protected static final short HAS_CALLBACK_ARG = (ALL_COPIES << 1);

  /** the unreserved flags start for child classes */
  protected static final short UNRESERVED_FLAGS_START = (HAS_CALLBACK_ARG << 1);

  /** Empty constructor for deserialization. Not to be invoked directly. */
  protected RegionSingleKeyExecutorMessage(boolean ignored) {
    super(true);
    this.txBatching = false;
  }

  protected RegionSingleKeyExecutorMessage(final LocalRegion region,
      final Object key, final Object callbackArg, final Object routingObject,
      final boolean allCopies, final TXStateInterface tx,
      boolean timeStatsEnabled) {
    super(new GfxdSingleResultCollector(allCopies ? DUMMY_RESULT : null), tx,
        timeStatsEnabled, true);
    this.region = region;
    this.regionPath = region.getFullPath();
    if (region.getPartitionAttributes() != null) {
      this.pr = (PartitionedRegion)region;
      this.prId = this.pr.getPRId();
      if (routingObject == null) { // calculate directly from key
        this.bucketId = PartitionedRegionHelper.getHashKey(this.pr,
            Operation.GET, key, null, callbackArg);
      }
      else {
        this.bucketId = PartitionedRegionHelper.getHashKey(this.pr,
            routingObject);
      }
      this.pbr = this.pr.getRegionAdvisor()
          .getProxyBucketArray()[this.bucketId];
    }
    else {
      this.dr = (DistributedRegion)region;
    }
    this.key = key;
    this.callbackArg = callbackArg;
    this.routingObject = routingObject;
    this.allCopies = allCopies;
    this.txBatching = this.txProxy != null && this.txProxy.batchingEnabled();
  }

  /** copy constructor */
  protected RegionSingleKeyExecutorMessage(
      final RegionSingleKeyExecutorMessage other) {
    super(other);
    this.region = other.region;
    this.regionPath = other.regionPath;
    this.dr = other.dr;
    this.pr = other.pr;
    this.prId = other.prId;
    this.bucketId = other.bucketId;
    this.pbr = other.pbr;
    this.key = other.key;
    this.callbackArg = other.callbackArg;
    this.routingObject = other.routingObject;
    this.allCopies = other.allCopies;
    this.txBatching = other.txBatching;
    // check for TX match with that in thread-local
    // TXState can be passed as null for non-transactional messages
    assert getTXState() == null
        || getTXState() == TXManagerImpl.getCurrentTXState(): "unexpected "
        + "mismatch of current TX " + TXManagerImpl.getCurrentTXState()
        + ", and TX passed to message " + getTXState();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DistributionAdvisor getDistributionAdvisor() {
    return this.pbr != null ? this.pbr.getDistributionAdvisor() : this.dr
        .getDistributionAdvisor();
  }

  @Override
  protected void executeFunction(boolean enableStreaming)
      throws StandardException, SQLException {
    final GemFireCacheImpl cache = this.region.getCache();
    final InternalDistributedSystem sys = cache.getSystem();
    final DM dm = sys.getDistributionManager();
    final InternalDistributedMember myId = cache.getMyId();
    final String logName = getID();

    // GemFireXD loaders are guaranteed to be on all stores so no need for
    // explicitly checking for loader nodes
    this.target = null;
    this.isSecondaryCopy = false;
    if (this.pr != null) {
      final long beginMapTime = this.timeStatsEnabled ? XPLAINUtil.recordTiming(-1) : 0;
      RetryTimeKeeper retryTime = null;
      final boolean optimizeForWrite = optimizeForWrite()
          || region.isHDFSReadWriteRegion();
      InternalDistributedMember member;
      Set<InternalDistributedMember> members;
      boolean preferredIsSelf;
      for (;;) {
        member = null;
        members = null;
        preferredIsSelf = false;
        if (optimizeForWrite) {
          member = this.pr.getOrCreateNodeForBucketWrite(this.bucketId,
              retryTime);
        }
        else {
          member = this.pr.getOrCreateNodeForInitializedBucketRead(
              this.bucketId, this.possibleDuplicate);
        }
        preferredIsSelf = myId.equals(member);
        if (this.allCopies && !(this.txBatching && preferredIsSelf)) {
          ProxyBucketRegion pbr = this.pr.getRegionAdvisor()
              .getProxyBucketArray()[this.bucketId];
          // if there are uninitialized members, then don't send to all copies
          // to enable flushing as a batch message instead else target node
          // may not be able to execute the message
          if (!pbr.getBucketAdvisor().hasUninitialized()) {
            members = pbr.getBucketOwners();
            if (!members.contains(member)) {
              // rare case where "preferred node" goes down
              continue;
            }
          }
          else {
            this.allCopies = false;
          }
        }
        else {
          this.allCopies = false;
        }

        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName
              + ": execute on bucket [" + bucketId + "] with routing object ["
              + this.routingObject + "] for key: " + this.key + (members != null
                  ? " on members " + members : " on member " + member));
        }
        if (this.failedNodes == null) {
          break;
        }
        boolean hasFailedNode = false;
        for (DistributedMember m : this.failedNodes) {
          if ((members != null && members.contains(m)) || m.equals(member)) {
            if (retryTime == null) {
              retryTime = new RetryTimeKeeper(this.pr.getRetryTimeout());
            }
            // need to wait for things to stabilize and then calculate things
            if (retryTime.overMaximum()) {
              throw new InternalFunctionInvocationTargetException(LocalizedStrings
                  .PRHARedundancyProvider_TIMED_OUT_ATTEMPTING_TO_0_IN_THE_PARTITIONED_REGION__1_WAITED_FOR_2_MS
                      .toLocalizedString(new Object[] {
                          "doing GemFireXD function execution",
                          PRHARedundancyProvider.regionStatus(this.pr, null,
                              null, true),
                          Long.valueOf(this.pr.getRetryTimeout()) })
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
        this.mapping_retry_count++;
      }

      ArrayList<GfxdFunctionMessage<Object>> msgsSent = null;
      if (beginMapTime != 0) {
        this.member_mapping_time = XPLAINUtil.recordTiming(beginMapTime);
        this.begin_scatter_time = XPLAINUtil.currentTimeStamp();
        if ((msgsSent = this.membersMsgsSent) == null) {
          this.membersMsgsSent = msgsSent =
            new ArrayList<GfxdFunctionMessage<Object>>(1);
        }
      }

      if (member != null && members == null) {
        final GfxdFunctionReplyMessageProcessor<Object> processor =
          createReplyProcessor(dm, member);
        setProcessor(processor);

        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName
              + ": executing region message " + toString() + " on node "
              + member);
        }

        final long beginTime = beginMapTime != 0 ? XPLAINUtil
            .recordTiming(-1) : 0;
        if (preferredIsSelf) {
          this.target = myId;
          executeOnMember(sys, dm, myId, true, enableStreaming);
        }
        else {
          this.target = member;
          executeOnMember(sys, dm, member, false, enableStreaming);
        }
        if (beginTime != 0) {
          this.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
          msgsSent.add(this);
        }
      }
      else if (members != null && members.size() > 0) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Set<DistributedMember> targets = (Set)members;
        final GfxdFunctionReplyMessageProcessor<Object> processor =
            createReplyProcessor(dm, targets);
        setProcessor(processor);

        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName
              + ": executing region message " + toString() + " on nodes "
              + members);
        }

        final long beginTime = this.timeStatsEnabled ? XPLAINUtil
            .recordTiming(-1) : 0;
        if (!targets.remove(member)) {
          Assert.fail("unexpected missing \"preferred node\" " + member
              + " in allCopies list " + targets);
        }
        // also self-execution should be at the very end
        boolean toSelf = false;
        if (!preferredIsSelf) {
          toSelf = targets.remove(myId);
        }
        if (targets.size() > 0) {
          final RegionSingleKeyExecutorMessage msg = clone();
          // set the SECONDARY_COPY flag for other copies
          msg.isSecondaryCopy = true;
          msg.executeOnMembers(sys, dm, targets, enableStreaming);
          if (beginTime != 0) {
            msgsSent.add(msg);
          }
        }
        // execute on "preferred node"
        executeOnMember(sys, dm, member, preferredIsSelf, enableStreaming);
        // on self at the end if different from "preferred node" for some reason
        if (toSelf) {
          final RegionSingleKeyExecutorMessage msg = clone();
          msg.isSecondaryCopy = true;
          msg.executeOnMember(sys, dm, myId, true, enableStreaming);
          if (beginTime != 0) {
            msgsSent.add(msg);
          }
        }
        if (beginTime != 0) {
          this.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
          msgsSent.add(this);
        }
      }
      else {
        this.userCollector.endResults();
        throw new EmptyRegionFunctionException(LocalizedStrings
            .PartitionedRegion_FUNCTION_NOT_EXECUTED_AS_REGION_IS_EMPTY
                .toLocalizedString());
      }
    }
    else {
      InternalDistributedMember member;
      final long beginMapTime = this.timeStatsEnabled ? XPLAINUtil
          .recordTiming(-1) : 0;
      final DataPolicy policy = this.dr.getDataPolicy();
      final CacheDistributionAdvisor advisor = this.dr
          .getCacheDistributionAdvisor();
      Set<InternalDistributedMember> replicates = null;
      if (policy.withReplication() || policy.withPreloaded()) {
        member = myId;
      }
      else if (!policy.withStorage()) {
        if (this.failedNodes == null) {
          member = this.dr.getRandomReplicate();
        }
        else {
          // Random approach is somewhat difficult since in the worst case the
          // failed node may be the only one available and then we could get
          // stuck in a long loop. A little inefficiency for failure scenario
          // will be fine.
          replicates = advisor.adviseInitializedReplicates();
          final Iterator<InternalDistributedMember> iter;
          member = null;
          replicates.removeAll(this.failedNodes);
          if (replicates.size() > 0) {
            int pos = PartitionedRegion.rand.nextInt(replicates.size());
            iter = replicates.iterator();
            while (pos-- >= 0 && iter.hasNext()) {
              member = iter.next();
            }
          }
        }
      }
      else {
        throw new GemFireXDRuntimeException(logName
            + "#execute: unknown policy " + policy + " for region: "
            + this.region);
      }
      if (member == null) {
        throw new NoMemberFoundException(LocalizedStrings
            .DistributedRegion_NO_REPLICATED_REGION_FOUND_FOR_EXECUTING_FUNCTION_0
                .toLocalizedString(logName));
      }
      if (this.allCopies && !(this.txBatching && member == myId)
          && !advisor.hasUninitializedReplicate()) {
        // if there are uninitialized members, then don't send to all copies
        // to enable flushing as a batch message instead else target node
        // may not be able to execute the message
        if (replicates == null) {
          replicates = advisor.adviseInitializedReplicates();
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName
              + ": executing region message " + this.toString() + " on nodes "
              + replicates + " with \"preferred node\" " + member);
        }
      }
      else {
        this.allCopies = false;
        replicates = null;
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, logName
              + ": executing region message " + this.toString() + " on node "
              + member);
        }
      }

      long beginTime = 0;
      ArrayList<GfxdFunctionMessage<Object>> msgsSent = null;
      if (beginMapTime != 0) {
        this.member_mapping_time = XPLAINUtil.recordTiming(beginMapTime);
        if ((msgsSent = this.membersMsgsSent) == null) {
          this.membersMsgsSent = msgsSent =
              new ArrayList<GfxdFunctionMessage<Object>>(1);
        }
        begin_scatter_time = XPLAINUtil.currentTimeStamp();
        beginTime = XPLAINUtil.recordTiming(-1);
      }

      if (replicates != null && replicates.size() > 0) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Set<DistributedMember> targets = (Set)replicates;
        targets.add(member);
        final GfxdFunctionReplyMessageProcessor<Object> processor =
            createReplyProcessor(dm, targets);
        setProcessor(processor);

        targets.remove(member);
        if (targets.size() > 0) {
          final RegionSingleKeyExecutorMessage msg = clone();
          // set the SECONDARY_COPY flag for other copies
          msg.isSecondaryCopy = true;
          msg.executeOnMembers(sys, dm, targets, false);
          if (beginTime != 0) {
            msgsSent.add(msg);
          }
        }
        // execution on self always at the end if required for parallelism
        executeOnMember(sys, dm, member, member == myId, enableStreaming);
      }
      else {
        final GfxdFunctionReplyMessageProcessor<Object> processor =
            createReplyProcessor(dm, member);
        setProcessor(processor);

        // reference check here is fine since myId has been assigned to member
        this.target = member;
        executeOnMember(sys, dm, member, member == myId, enableStreaming);
      }

      if (beginTime != 0) {
        this.root_msg_send_time += XPLAINUtil.recordTiming(beginTime);
        msgsSent.add(this);
      }
    }
  }

  @Override
  protected final GemFireCacheImpl getGemFireCache() {
    return this.region.getGemFireCache();
  }

  @Override
  protected abstract RegionSingleKeyExecutorMessage clone();

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    // use TX proxy to enable batching of read locks but only if we did not
    // distribute to all copies (due to uninitialized member)
    return this.allCopies ? false : canStartRemoteTransaction();
  }

  @Override
  public boolean containsRegionContentChange() {
    // for TX state flush but not for inline processing case since for that
    // case the operation will be received when endOperation has been invoked
    // and CreateRegionMessage was waiting on ops in progress
    return isTransactional() && this.processorId != 0
        && canStartRemoteTransaction();
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    // to flush any batched read locks
    return this.allCopies ? false : canStartRemoteTransaction();
  }

  @Override
  public boolean canStartRemoteTransaction() {
    return getLockingPolicy().readCanStartTX();
  }

  public final DistributedMember getTarget() {
    return this.target;
  }

  @Override
  protected final void processMessage(DistributionManager dm)
      throws GemFireCheckedException {
    if (this.region == null) {
      if (this.prId >= 0) { // PR case
        this.pr = PartitionedRegion.getPRFromId(this.prId);
        this.region = this.pr;
        if (this.pr == null) {
          throw new ForceReattemptException(LocalizedStrings
              .PartitionMessage_0_COULD_NOT_FIND_PARTITIONED_REGION_WITH_ID_1
                  .toLocalizedString(new Object[] {
                      Misc.getGemFireCache().getMyId(),
                      Integer.valueOf(this.prId) }));
        }
      }
      else {
        this.region = Misc.getGemFireCache().getRegionByPathForProcessing(
            this.regionPath);
        if (this.region == null) {
          throw new ForceReattemptException(
              LocalizedStrings.Region_CLOSED_OR_DESTROYED
                  .toLocalizedString(this.regionPath));
        }
        this.dr = (DistributedRegion)this.region;
      }
    }
    if (this.key instanceof KeyWithRegionContext) {
      ((KeyWithRegionContext)this.key).setRegionContext(this.region);
    }
    if (this.pr != null) {
      if (this.bucketId < 0) { // calculate directly from key
        this.bucketId = PartitionedRegionHelper.getHashKey(this.pr,
            Operation.GET, this.key, null, this.callbackArg);
      }
      if (this.pbr == null) {
        this.pbr = this.pr.getRegionAdvisor()
            .getProxyBucketArray()[this.bucketId];
      }
    }
    super.processMessage(dm);
  }

  @Override
  public final void checkAllBucketsHosted() throws BucketMovedException {
    // check if bucket has moved
    if (this.pr != null) {
      final BucketAdvisor bucAdvisor = this.pr.getRegionAdvisor()
          .getBucketAdvisor(this.bucketId);
      if (optimizeForWrite()) {
        if (!bucAdvisor.isPrimary()) {
          this.pr.checkReadiness();
          InternalDistributedMember primaryHolder = bucAdvisor
              .basicGetPrimaryMember();
          throw new PrimaryBucketException("Bucket "
              + this.pr.getBucketName(this.bucketId)
              + " is not primary. Current primary holder is " + primaryHolder);
        }
      }
      else if (!bucAdvisor.isHosting()) {
        throw new BucketMovedException(
            LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
                .toLocalizedString(), this.bucketId, this.pr.getFullPath());
      }
    }
  }

  @Override
  public final boolean isSecondaryCopy() {
    return this.isSecondaryCopy;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DirectReplyProcessor getDirectReplyProcessor() {
    return this.processor;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsDirectAck() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void registerProcessor() {
    this.processorId = this.processor.register();
  }

  /**
   * @see AbstractOperationMessage#computeCompressedShort(short)
   */
  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.prId >= 0) {
      flags |= IS_PARTITIONED_TABLE;
      if (this.bucketId != KeyInfo.UNKNOWN_BUCKET) {
        flags |= HAS_BUCKET_ID;
      }
    }
    if (this.callbackArg != null) {
      flags |= HAS_CALLBACK_ARG;
    }
    if (this.isSecondaryCopy) {
      flags |= SECONDARY_COPY;
    }
    if (this.allCopies) {
      flags |= ALL_COPIES;
    }
    return flags;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    // write the region ID or path
    if (this.prId >= 0) {
      InternalDataSerializer.writeUnsignedVL(this.prId, out);
      if (this.bucketId != KeyInfo.UNKNOWN_BUCKET) {
        InternalDataSerializer.writeUnsignedVL(this.bucketId, out);
      }
    }
    else {
      DataSerializer.writeString(this.regionPath, out);
    }
    // write the key
    DataSerializer.writeObject(this.key, out);
    // callback argument
    if (this.callbackArg != null) {
      DataSerializer.writeObject(this.callbackArg, out);
    }
    if (begintime != 0) {
      this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
        : -2/*ignore nested call*/) : 0;

    this.bucketId = KeyInfo.UNKNOWN_BUCKET;
    // read region first
    if ((flags & IS_PARTITIONED_TABLE) != 0) {
      this.prId = (int)InternalDataSerializer.readUnsignedVL(in);
      if ((flags & HAS_BUCKET_ID) != 0) {
        this.bucketId = (int)InternalDataSerializer.readUnsignedVL(in);
      }
    }
    else {
      this.regionPath = DataSerializer.readString(in);
    }
    // read the key
    this.key = DataSerializer.readObject(in);
    // callback argument
    if ((flags & HAS_CALLBACK_ARG) != 0) {
      this.callbackArg = DataSerializer.readObject(in);
    }
    this.isSecondaryCopy = ((flags & SECONDARY_COPY) != 0);
    this.allCopies = ((flags & ALL_COPIES) != 0);
    // recording end of de-serialization here instead of AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  protected String getID() {
    return getShortClassName();
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";region=").append(this.regionPath);
    if (this.pr != null) {
      sb.append(";prId=").append(this.prId);
      sb.append(";bucketId=").append(this.bucketId);
    }
    sb.append(";key=");
    ArrayUtils.objectString(this.key, sb);
    if (this.callbackArg != null) {
      sb.append(";callbackArg=");
      ArrayUtils.objectString(this.callbackArg, sb);
    }
    sb.append(";isSecondaryCopy=").append(this.isSecondaryCopy);
    if (this.routingObject != null) {
      sb.append(";routingObject=").append(this.routingObject);
    }
  }
}
