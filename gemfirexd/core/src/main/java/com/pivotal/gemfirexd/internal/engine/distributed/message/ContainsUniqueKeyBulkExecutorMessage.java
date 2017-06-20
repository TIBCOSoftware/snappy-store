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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.concurrent.FetchFromMap;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.index.ContainsUniqueKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.BulkKeyLookupResult;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Class that checks existence of an array of (FK) keys in a given FK
 * refContainer (unique key based)
 * 
 * @author shirishd
 * 
 */
public class ContainsUniqueKeyBulkExecutorMessage extends
    RegionExecutorMessage<Object> implements FetchFromMap {
  
  /** array of all keys (FKs) whose existence is to be checked */ 
  protected Object[] inKeys;
  protected Object[] inRoutingObjects;
  protected String regionPath;
  protected PartitionedRegion pr;
  protected int prId;
  protected DistributedMember member;
  private int[] referenceKeyColumnIndexes;
  /** keys to be processed for the current node */
  List<Object> keysForTheCurrentNode;
  
  protected static final short IS_PARTITIONED_TABLE =
      RegionExecutorMessage.UNRESERVED_FLAGS_START;

  // for FetchFromMap interface implementation
  /**
   * the actual skiplist key object as read using the
   * {@link FetchFromMap#setMapKey(Object, int)} callback
   */
  private transient Object currentMapKey;

  /**
   * the skiplist node's version field as read using the
   * {@link FetchFromMap#setMapKey(Object, int)} callback
   */
  private transient int currentKeyNodeVersion;


  public ContainsUniqueKeyBulkExecutorMessage() {
    super(true);
    this.inKeys = null;
    this.inRoutingObjects = null;
    this.regionPath = null;
    this.pr = null;
    this.keysForTheCurrentNode = null;
  }
  
  @SuppressWarnings("unchecked")
  public ContainsUniqueKeyBulkExecutorMessage(final LocalRegion region,
      int[] referenceKeyColumnIndexes, Object[] keys, Object[] routingObjects,
      final TXStateInterface tx, final LanguageConnectionContext lcc) {
    super(new GfxdListResultCollector(null, true), region, new THashSet(
        Arrays.asList(routingObjects)) /*routingObjects*/, tx,
        getTimeStatsSettings(lcc), true);

    this.inKeys = keys;
    this.referenceKeyColumnIndexes = referenceKeyColumnIndexes;
    this.inRoutingObjects = routingObjects;
    this.regionPath = region.getFullPath();
    if (region.getPartitionAttributes() != null) {
      this.pr = (PartitionedRegion)region;
      this.prId = this.pr.getPRId();
    }
  }
  
  public ContainsUniqueKeyBulkExecutorMessage(ContainsUniqueKeyBulkExecutorMessage other) {
    super(other);
    this.inKeys = other.inKeys;
    this.referenceKeyColumnIndexes = other.referenceKeyColumnIndexes;
    this.inRoutingObjects = other.inRoutingObjects;
    this.pr = other.pr;
    this.prId = other.prId;
    this.regionPath = region.getFullPath();
    this.routingObjects = other.routingObjects;
    this.membersToBucketIds = other.membersToBucketIds;
    this.keysForTheCurrentNode = other.keysForTheCurrentNode;
    this.member = other.member;
  }

  @Override
  protected ContainsUniqueKeyBulkExecutorMessage clone() {
    return new ContainsUniqueKeyBulkExecutorMessage(this);
  }

  @Override
  protected void execute() throws Exception {
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery;

    final TXStateInterface tx = getTXState();
    boolean containsKey;

    if (this.keysForTheCurrentNode == null) {
      populateKeysForTheCurrentNode(this.bucketBitSet);
    }
    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "ContainsUniqueKeyBulkExecutorMessage#execute: region="
              + this.regionPath + " keys for the current member are:"
              + this.keysForTheCurrentNode);
    }
    for (Object key : this.keysForTheCurrentNode) {
      if (key instanceof CompactCompositeRegionKey) {
        ((CompactCompositeRegionKey)key).setRegionContext(this.region);
      }
      containsKey = ContainsUniqueKeyExecutorMessage
          .existsKey(tx, this.region, referenceKeyColumnIndexes, key,
              null /* callbackArg */, this /* FetchFromMap */);
      // send only failed lookups
      if (!containsKey) {
        final BulkKeyLookupResult oneResult = new BulkKeyLookupResult(key,
            containsKey);
        sendResult(oneResult);
      }
    }
    // send last result
    final BulkKeyLookupResult finalResult = new BulkKeyLookupResult(
        Token.INVALID, true);
    lastResult(finalResult);
  }

  @Override
  protected final void processMessage(DistributionManager dm)
      throws GemFireCheckedException {
    if (this.region == null) {
      if (this.prId >= 0) { // PR case
        this.pr = PartitionedRegion.getPRFromId(this.prId);
        if (this.pr == null) {
          throw new ForceReattemptException(
              LocalizedStrings.
              PartitionMessage_0_COULD_NOT_FIND_PARTITIONED_REGION_WITH_ID_1
                  .toLocalizedString(new Object[] {
                      Misc.getGemFireCache().getMyId(),
                      Integer.valueOf(this.prId) }));
        }
        this.region = this.pr;
        this.regionPath = region.getFullPath();
      }
      else {
        this.region = Misc.getGemFireCache().getRegionByPathForProcessing(
            this.regionPath);
        if (this.region == null) {
          throw new ForceReattemptException(
              LocalizedStrings.Region_CLOSED_OR_DESTROYED
                  .toLocalizedString(this.regionPath));
        }
      }
    }
    super.processMessage(dm);
  }
  
  protected String getID() {
    return getShortClassName();
  }
  
  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.prId >= 0) {
      flags |= IS_PARTITIONED_TABLE;
    }
    return flags;
  }
  
  @Override
  public void toData(DataOutput out)
      throws IOException {
    final long beginTime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2 /*ignore nested call*/) : 0;
    super.toData(out);
//    InternalDataSerializer.writeObject(this.inKeys, out);
    DataSerializer.writeIntArray(this.referenceKeyColumnIndexes, out);
//    InternalDataSerializer.writeObject(this.inRoutingObjects, out);
    InternalDataSerializer.writeObject(this.keysForTheCurrentNode, out);
    // write the region ID or path
    if (this.prId >= 0) {
      InternalDataSerializer.writeUnsignedVL(this.prId, out);
    }
    else {
      DataSerializer.writeString(this.regionPath, out);
    }
    if (beginTime != 0) {
      this.ser_deser_time = XPLAINUtil.recordTiming(beginTime);
    }
  }
  
  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
    : -2/*ignore nested call*/) : 0;
    super.fromData(in);
//    this.inKeys = InternalDataSerializer.readObject(in);
    this.referenceKeyColumnIndexes = DataSerializer.readIntArray(in);
//    this.inRoutingObjects = InternalDataSerializer.readObject(in);
    this.keysForTheCurrentNode = InternalDataSerializer.readObject(in);
    if ((flags & IS_PARTITIONED_TABLE) != 0) {
      this.prId = (int)InternalDataSerializer.readUnsignedVL(in);
    }
    else {
      this.regionPath = DataSerializer.readString(in);
    }
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  public long estimateMemoryUsage() throws StandardException {
    long memory = 0;
    for (Object key : this.keysForTheCurrentNode) {
      if (key instanceof CompactCompositeRegionKey) {
        memory += ((CompactCompositeRegionKey) key).estimateMemoryUsage();
      } else if (key instanceof CompactCompositeKey) {
        memory += ((CompactCompositeKey) key).estimateMemoryUsage();
      } else if (key instanceof CompositeRegionKey) {
        memory += ((CompositeRegionKey) key).estimateMemoryUsage();
      } else if (key instanceof DataValueDescriptor) {
        memory += ((DataValueDescriptor) key).estimateMemoryUsage();
      }
    }
    return memory;
  }

 /**
  * Set the keys for this member
  */
  @Override
  protected void setArgsForMember(DistributedMember member,
      Set<DistributedMember> messageAwareMembers) {
    this.member = member;
    final BitSetSet bucketBitSet = (BitSetSet) membersToBucketIds
        .get(this.member);
    // bucketBitSet could be null in the case of self (message sending data 
    // node) the list is populated in execute() in that case 
    if (bucketBitSet != null) {
      this.populateKeysForTheCurrentNode(bucketBitSet);
    }
  }
  
  private void populateKeysForTheCurrentNode(final BitSetSet bucketBitSet) {
//  final boolean isPR = (this.region !=null && region.getPartitionAttributes() != null);
    this.keysForTheCurrentNode = new ArrayList<Object>();
    
    int bucketId = 0;
    for (int j = 0; j < inKeys.length; j++) {
      if (inKeys[j] != null) {
        // if (isPR) {
        if (inRoutingObjects != null) {
          bucketId = PartitionedRegionHelper.getHashKey(this.pr,
              inRoutingObjects[j]);
        } else {
          if (inKeys[j] instanceof CompactCompositeRegionKey) {
            ((CompactCompositeRegionKey) inKeys[j])
                .setRegionContext(this.region);
          }
          bucketId = PartitionedRegionHelper.getHashKey(this.pr,
              Operation.GET, inKeys[j], null, null /* callbackArg */);
        }
        // }
        if (bucketBitSet.containsInt(bucketId)) {
          this.keysForTheCurrentNode.add(inKeys[j]);
        }
      }
    }
  }
  
    @Override
  public boolean withSecondaries() {
    return false;
  }

  @Override
  public byte getGfxdID() {
    return CONTAINS_UNIQUEKEY_BULK_EXECUTOR_MSG;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  /**
   * @see TransactionMessage#canStartRemoteTransaction()
   */
  @Override
  public final boolean canStartRemoteTransaction() {
    return true;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    // use TX proxy to enable batching of read locks
    return getLockingPolicy().readOnlyCanStartTX();
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    // to flush any batched read locks
    return getLockingPolicy().readOnlyCanStartTX();
  }

  @Override
  public boolean isHA() {
    return true;
  }
  
  @Override
  protected void setIgnoreReplicateIfSetOperators(boolean ignoreReplicate) {
    // do nothing
  }
  
  @Override
  public final int getMessageProcessorType() {
    // Make this serial so that it will be processed in the p2p msg reader
    // which gives it better performance.
    // don't use SERIAL_EXECUTOR if we may have to wait for a pending TX
    // else if may deadlock as the p2p msg reader thread will be blocked
    // don't use SERIAL_EXECUTOR for RepeatableRead isolation level else
    // it can block P2P reader thread thus blocking any possible commits
    // which could have released the SH lock this thread is waiting on
    return this.pendingTXId == null
        && getLockingPolicy() == LockingPolicy.NONE
        ? DistributionManager.SERIAL_EXECUTOR
        : DistributionManager.PARTITIONED_REGION_EXECUTOR;
  }
  
  // for FetchFromMap interface implementation
  /**
   * {@inheritDoc}
   */
  @Override
  public void setMapKey(Object key, int version) {
    this.currentMapKey = key;
    this.currentKeyNodeVersion = version;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getCurrentKey() {
    return this.currentMapKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getCurrentNodeVersion() {
    return this.currentKeyNodeVersion;
  }
}
