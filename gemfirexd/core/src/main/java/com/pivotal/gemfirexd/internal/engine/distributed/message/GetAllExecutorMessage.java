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
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RetryTimeKeeper;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.OffHeapReleaseUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument.WithInfoFieldsType;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * @author vivekb
 * 
 */
public class GetAllExecutorMessage extends RegionMultiKeyExecutorMessage {

  /* Note: 
   * Map Member -> Buckets -> Keys
   * Should this be synchronized? No. Write once. Read access from multiple
   * copies of same message.
   */
  final private Map<InternalDistributedMember, Map<Integer, List<Object>>>
      allMembersAndBucketsAndKeys;

  /* Note: 
   * Routing Objects are in same order as Keys
   * Only valid for Global Index case
   * 
   * We wanted to keep @see routingObjects as null
   * and thus this separate object 
   */
  final protected Object[] inRoutingobjects;

  protected final Object[] inKeys;
  
  // key count per bucket id, later from bucket-bitset
  protected List<Integer> keysCountPerBucket = null;

  protected final LanguageConnectionContext lcc;

  protected final boolean isTX;

  private boolean forUpdate;
  private boolean queryHDFS;

  /**
   * @return the isTX
   */
  public boolean isTX() {
    return isTX;
  }
  
  // transient arguments used as execution parameters

  /** set to true if there is a loader anywhere in the system for the region */
  private transient boolean hasLoader;

  /**
   * @return the hasLoader
   */
  public boolean hasLoader() {
    return hasLoader;
  }

  /**
   * GET can start a TX at REPEATABLE_READ isolation level or if there is a
   * loader in the region.
   */
  private transient boolean canStartTX;

  // flags used in serialization are below

  protected static final short HAS_LOADER = UNRESERVED_FLAGS_START;
  protected static final short CAN_START_TX = (HAS_LOADER << 1);
  protected static final short FOR_UPDATE = (CAN_START_TX << 1);

  /** Empty constructor for deserialization. Not to be invoked directly. */
  public GetAllExecutorMessage() {
    super(true);
    this.inKeys = null;
    this.allMembersAndBucketsAndKeys = null;
    this.inRoutingobjects = null;
    this.lcc = null;
    this.isTX = false;
  }

  /**
   * Constructor
   */
  @SuppressWarnings("unchecked")
  public GetAllExecutorMessage(final LocalRegion region, Object[] keys,
      Object[] routingObjects, final RowFormatter targetFormat,
      final int[] projectionFixedColumns, final int[] projectionVarColumns,
      final int[] projectionLobColumns, final int[] projectionAllColumns,
      final TXStateInterface tx, final LanguageConnectionContext lcc,
      final boolean forUpdate, final boolean queryHDFS) {
    super(new GfxdListResultCollector(null, true),
        region, null /*routingObjects*/, tx, getTimeStatsSettings(lcc),
        targetFormat, projectionFixedColumns, projectionVarColumns,
        projectionLobColumns, projectionAllColumns);
    this.inKeys = keys;
    this.hasLoader = ((GemFireContainer)region.getUserAttribute())
        .getHasLoaderAnywhere();
    if (isTransactional() && !region.getScope().isLocal()) {
      // check for loader in the region
      this.canStartTX = getLockingPolicy().readCanStartTX() || this.hasLoader;
    }
    this.allMembersAndBucketsAndKeys = new THashMap();
    this.inRoutingobjects = routingObjects;
    this.lcc = lcc;
    this.isTX = tx != null;
    this.keysCountPerBucket = new ArrayList<Integer>();
    this.forUpdate = forUpdate;
    this.queryHDFS = queryHDFS;
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(this.inKeys != null);
      /* Note:
       * Current implementation is for PK only
       * Even though we have non-null routingObjects, we pass
       * null to super
       */
      SanityManager.ASSERT(this.routingObjects == null);

      /* Note:
       * GetAllExecutorMessage would only be called for partitioned
       * tables and not for replicated table which is handled by
       * @see StatementExecutorMessage.ALL_TABLES_REPLICATED_ON_REMOTE
       * 
       * Also, member function
       * @see GetAllExecutorMessage#setMembersToBucketIds
       * have been only implemented for partitioned table 
       */
      SanityManager.ASSERT(this.pr != null);
    }
  }

  /**
   * @param other
   */
  public GetAllExecutorMessage(GetAllExecutorMessage other) {
    super(other);
    this.inKeys = other.inKeys;
    this.allMembersAndBucketsAndKeys = other.allMembersAndBucketsAndKeys;
    this.inRoutingobjects = other.inRoutingobjects;
    this.hasLoader = other.hasLoader;
    this.canStartTX = other.canStartTX;
    this.lcc = other.lcc;
    this.isTX = other.isTX;
    this.keysCountPerBucket = other.keysCountPerBucket;
    this.forUpdate = other.forUpdate;
    this.queryHDFS = other.queryHDFS;
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.
   * RegionExecutorMessage#optimizeForWrite()
   */
  @Override
  public boolean optimizeForWrite() {
    return false;
  }
  
  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.
   * RegionExecutorMessage#withSecondaries()
   */
  @Override
  public boolean withSecondaries() {
    return false;
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.
   * RegionExecutorMessage#clone()
   */
  @Override
  protected GetAllExecutorMessage clone() {
    return new GetAllExecutorMessage(this);
  }

  /**
   * @see GfxdFunctionMessage#execute()
   */
  @Override
  protected void execute() throws GemFireCheckedException {
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;
    final ArrayList<Object> resultList = new ArrayList<Object>();
    final LogWriterI18n logger = region.getLogWriterI18n();
    final int numKeys = this.membersToKeys.size();
    final TIntArrayList bucketList = new TIntArrayList(this.bucketBitSet.size());
    {
      // duplicate bucket-id given count times
      if (SanityManager.DEBUG) {
        if (this.keysCountPerBucket.size() != this.bucketBitSet.size()) {
          SanityManager.ASSERT(false, "keysCountPerBucket=" +
              keysCountPerBucket + " bucketBitSet=" + bucketBitSet +
              " allMembersBuckets=" + allMembersAndBucketsAndKeys);
        }
      }
      Iterator<Integer> countItr = keysCountPerBucket.iterator();
      for (int bucketId = this.bucketBitSet.nextSetBit(0, 0); bucketId >= 0;
           bucketId = this.bucketBitSet.nextSetBit(bucketId + 1)) {
        int count = countItr.next();
        for (int i = 0; i < count; i++) {
          bucketList.add(bucketId);
        }
      }

      if (doLog) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            this.getID() + ".execute: Got keys " + this.membersToKeys
                + " of size [" + numKeys + "] " + " with bucketList "
                + bucketList + " of size [" + bucketList.size() + "] "
                + " for region " + this.region.getFullPath()
                + " , processorId=" + getProcessorId() + " , and callbackArg "
                + (this.commonCallbackArg != null));
      }

      if (SanityManager.DEBUG) {
        SanityManager.ASSERT(numKeys == bucketList.size());
      }
    }

    final boolean isPrimary = true;
    WithInfoFieldsType callbackArg = null;
    if (this.commonCallbackArg != null) {
      callbackArg = ((WithInfoFieldsType)this.commonCallbackArg).cloneObject();
    }
    int keyAndBucketIndex = 0;
    boolean throwingException = false;
    while (keyAndBucketIndex < numKeys) {
      final Object key = this.membersToKeys.get(keyAndBucketIndex);
      final int bucketId = bucketList.get(keyAndBucketIndex);
      Object result = null;
      try {
        if (callbackArg != null) {
          Integer routingObject = (Integer)GemFireXDUtils
              .getRoutingObject(bucketId);
          callbackArg.setRoutingObject(routingObject);
        }
        result = GetExecutorMessage.executeOneKey(this, key, callbackArg,
            bucketId, this.pr, this.region, this.regionPath,
            false, this.hasProjection, this.targetFormat,
            this.projectionFixedColumns, this.projectionVarColumns,
            this.projectionAllColumns, this.projectionLobColumns,
            this.targetFormatOffsetBytes, true, this.forUpdate, this.queryHDFS);
        Assert.assertTrue(result != GetExecutorMessage.INVALID_RESULT);
      } catch (CacheLoaderException ex) {
        final boolean isItENFE = GemFireResultSet.checkIfENFE(ex);
        if (isItENFE) {
          if (logger.fineEnabled()) {
            logger.fine("Got EntryNotFoundException wrapped "
                + "in CacheLoaderException");
          }
          result = null;
        }
        else {
          throwingException = true;
          throw ex;
        }
      } catch (EntryNotFoundException ex) {
        if (logger.fineEnabled()) {
          logger.fine("Got EntryNotFoundException exception", ex);
        }
        result = null;
      } catch (GemFireXDRuntimeException ex) {
        if (logger.fineEnabled()) {
          logger.fine("Got GemFireXDRuntimeException exception", ex);
        }
        if (ex.getCause() instanceof StandardException) {
          if (!GemFireResultSet.exceptionCanBeIgnored((StandardException)ex
              .getCause())) {
            throwingException = true;
            throw ex;
          }
        }
        else {
          throwingException = true;
          throw ex;
        }
      } catch (EntryExistsException ex) {
        final DataPolicy policy = region.getDataPolicy();
        boolean isTheRegionReplicate = policy.withReplication()
            || !policy.withStorage();
        if (isTheRegionReplicate) {
          result = ex.getOldValue();
        }
        else {
          throwingException = true;
          throw ex;
        }
      }catch(RuntimeException re) {
        throwingException = true;
        throw re;
      }      
      finally {
        if(throwingException) {
          if (GemFireXDUtils.isOffHeapEnabled() ) {
            OffHeapReleaseUtil.freeOffHeapReference(resultList);
          }
        }
      }
      

      if (doLog) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, this.getID()
            + ".execute: Got result for key= " + key.toString()
            + " , bucketId= " + bucketId + " , region " + region.getFullPath()
            + " [" + result == null ? "null" : result + "]" + ", keyIndex="
            + keyAndBucketIndex);
      }

      // Exception caught or not, add result to returning list
      resultList.add(result);
      keyAndBucketIndex++;
    }

    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, this.getID()
          + ".execute: Sending lastResult of size [" + resultList.size()
          + "] for region " + this.region.getFullPath() + " , processorId="
          + getProcessorId());
    }
    try {
      lastResult(resultList,
          isPrimary /* flush any entries added via loader */, isPrimary, true);
    } catch (RuntimeException re) {
      if (GemFireXDUtils.isOffHeapEnabled() && this.isLocallyExecuted()) {
        OffHeapReleaseUtil.freeOffHeapReference(resultList);
      }
      throw re;
    } finally {
      if (GemFireXDUtils.isOffHeapEnabled() && !this.isLocallyExecuted()) {
        // release the byte source
        OffHeapReleaseUtil.freeOffHeapReference(resultList);
      }
    }
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage#getGfxdID()
   */
  @Override
  public byte getGfxdID() {
    return GET_ALL_EXECUTOR_MSG;
  }

  /**
   * @see AbstractOperationMessage#computeCompressedShort(short)
   */
  @Override
  protected final short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.hasLoader) {
      flags |= HAS_LOADER;
    }
    if (this.canStartTX) {
      flags |= CAN_START_TX;
    }
    if (this.forUpdate) {
      flags |= FOR_UPDATE;
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
    InternalDataSerializer.writeObject(this.keysCountPerBucket, out);
    // NOTE: this could have been part of flags, but someone added as a separate
    // boolean increasing payload unnecessarily; not changing this now to
    // avoid rolling upgrade version checks but any new flags should be added
    // in flags like HAS_PROJECTION above; if they have been exhausted then
    // make the boolean below as a byte having bitmasks (with 0x1 for
    // QUERY_HDFS) so then 7 more flags will be available
    out.writeBoolean(this.queryHDFS);

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
    this.hasLoader = ((flags & HAS_LOADER) != 0);
    this.canStartTX = ((flags & CAN_START_TX) != 0);
    this.forUpdate = ((flags & FOR_UPDATE) != 0);
    this.keysCountPerBucket = InternalDataSerializer.readObject(in);
    this.queryHDFS = in.readBoolean();
    // recording end of de-serialization here instead of
    // AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  @Override
  public final int getMessageProcessorType() {
    return DistributionManager.PARTITIONED_REGION_EXECUTOR;
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    if (this.hasLoader) {
      sb.append(";hasLoader=").append(this.hasLoader);
    }
    if (this.canStartTX) {
      sb.append(";canStartTX=").append(this.canStartTX);
    }
    if (this.forUpdate) {
      sb.append(";forUpdate=true");
    }
    if (this.queryHDFS) {
      sb.append(";queryHDFS=true");
    }
  }

  /**
   * @see TransactionMessage#canStartRemoteTransaction()
   */
  @Override
  public final boolean canStartRemoteTransaction() {
    return this.canStartTX;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    // use TX proxy to enable batching of read locks
    return this.canStartTX;
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    // to flush any batched read locks
    return this.canStartTX;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void setArgsForMember(DistributedMember member,
      Set<DistributedMember> messageAwareMembers) {
    this.target = member;
    this.getKeysPerMember(member);

    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;
    if (doLog) {
      SanityManager.DEBUG_PRINT(
          GfxdConstants.TRACE_QUERYDISTRIB,
          this.getID() + ".setArgsForMember: keys ["
              + this.membersToKeys.toString()
              + "] with count of keys per bucket ["
              + this.keysCountPerBucket.toString() + "]"
              + " being sent to member " + member.getHost() + "/"
              + member.getId() + " , with processorId - "
              + member.getProcessId());
    }
  }

  /**
   * @see RegionExecutorMessage#setMembersToBucketIds(RetryTimeKeeper)
   */
  @Override
  protected void setMembersToBucketIds(RetryTimeKeeper retryTime)
      throws StandardException {
    final boolean doLog = DistributionManager.VERBOSE
        | GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ;
    this.allMembersAndBucketsAndKeys.clear();
    resetKeysPerMember();
    final PartitionedRegion pregion = (PartitionedRegion)this.region;
    final boolean optimizeForWrite = optimizeForWrite()
        || pregion.isHDFSReadWriteRegion();
    this.membersToBucketIds = new HashMapOrSet(pregion);
    InternalDistributedMember member;
    final BitSetSet allBucketIds = new BitSetSet(pregion
        .getPartitionAttributes().getTotalNumBuckets());

    GfxdCallbackArgument callbackArg = null;
    boolean needCallbackArg = false;
    if (hasLoader) {
      if (!isTX) {
        needCallbackArg = true;
      }
    }

    if (doLog) {
      StringBuilder debugString = new StringBuilder();
      debugString.append(this.getID());
      debugString.append(".setMembersToBucketIds: keys for ");
      debugString.append(this.regionPath);
      debugString.append(" [");
      for (Object key : this.inKeys) {
        debugString.append(key);
        debugString.append(", ");
      }
      debugString.append("] with callbackArg=");
      debugString.append(needCallbackArg);
      debugString.append(" ,optimizeForWrite=");
      debugString.append(optimizeForWrite);
      debugString.append(" ,hdfs-region=");
      debugString.append(pregion.isHDFSReadWriteRegion());
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          debugString.toString());
    }

    assert this.inRoutingobjects == null
        || this.inKeys.length == this.inRoutingobjects.length;
    for (int index = 0; index < this.inKeys.length; index++) {
      Object routingObject = this.inRoutingobjects == null ? null
          : this.inRoutingobjects[index];
      // It is possible that this PK based get may load data & hence
      // do internal put so we need to wrap it in GfxdCallbackArg
      callbackArg = GemFireXDUtils.wrapCallbackArgs(routingObject, lcc, false,
          true, true /* cache loaded */, true /* isPkBased */,
          lcc.isSkipListeners(), false, lcc.isSkipConstraintChecks());
      final int bucketId = PartitionedRegionHelper.getHashKey(this.pr,
          Operation.GET, this.inKeys[index], null, callbackArg);

      if (!allBucketIds.addInt(bucketId)) {
        // Duplicate Bucket id case; 2 keys map to same bucket id.
        // Handled below
      }

      if (optimizeForWrite) {
        member = pregion.getOrCreateNodeForBucketWrite(bucketId, retryTime);
      }
      else {
        member = pregion.getOrCreateNodeForInitializedBucketRead(bucketId,
            this.possibleDuplicate);
      }
      addBucketIdForMember(membersToBucketIds, member, bucketId, pregion);

      Map<Integer, List<Object>> bucketToKeylistMap = allMembersAndBucketsAndKeys
          .get(member);
      if (bucketToKeylistMap == null) {
        // list should be ordered on bucket id, the key
        // TODO: PERF: can we avoid a TreeMap and instead have an array
        // that can be sorted at the end
        bucketToKeylistMap = new TreeMap<Integer, List<Object>>();
        allMembersAndBucketsAndKeys.put(member, bucketToKeylistMap);
      }
      List<Object> keylist = bucketToKeylistMap.get(bucketId);
      if (keylist == null) {
        keylist = new ArrayList<Object>();
        bucketToKeylistMap.put(bucketId, keylist);
      }
      keylist.add(this.inKeys[index]);
    }
    if (needCallbackArg) {
      SanityManager.ASSERT(callbackArg instanceof WithInfoFieldsType);
      /* Note:
       * All the callbackArg has common flags and differ only in 
       * routingObject. So removed routingObject information from
       * the last callbackArg and serialize it to remote node, so
       * that same object can be re-used just by setting back
       * routingObject.
       */
      ((WithInfoFieldsType)callbackArg).setRoutingObject(null);
      this.commonCallbackArg = callbackArg;
    }
    if (this.membersToBucketIds.isEmpty()) {
      // no buckets found; probably no datastores are around
      GemFireXDUtils.checkForInsufficientDataStore(pregion);
    }
    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, this.getID()
          + ".setMembersToBucketIds: execute on buckets with pruned map :- "
          + membersToBucketIds);
    }
  }

  @Override
  public void reset() {
    super.reset();
    this.allMembersAndBucketsAndKeys.clear();
    resetKeysPerMember();
  }
  
  @Override
  public List<Object> getKeysPerMember(DistributedMember member) {
    this.resetKeysPerMember();
    assert this.membersToKeys != null;
    assert this.keysCountPerBucket != null;

    Map<Integer, List<Object>> bucketToKeylistMap = this.allMembersAndBucketsAndKeys
        .get(member);
    assert bucketToKeylistMap != null;
    for (List<Object> keylist : bucketToKeylistMap.values()) {
      this.keysCountPerBucket.add(keylist.size());
      for (Object key : keylist) {
        this.membersToKeys.add(key);
      }
    }
    return this.membersToKeys;
  }
  
  @Override
  public long estimateMemoryUsage() throws StandardException {
    long memory = 0;

    try {
      for (Map<Integer, List<Object>> bucketsAndKeys : this.allMembersAndBucketsAndKeys
          .values()) {
        for (List<Object> allKeys : bucketsAndKeys.values()) {
          for (Object k : allKeys) {
            if (k instanceof CompactCompositeKey) {
              memory += ((CompactCompositeKey)k).estimateMemoryUsage();
            }
            else if (k instanceof DataValueDescriptor) {
              memory += ((DataValueDescriptor)k).estimateMemoryUsage();
            }
            else if (k instanceof Long) {
              memory += Long.SIZE;
            }
          }
        }
      }
    } catch (ConcurrentModificationException ignore) {
    } catch (NoSuchElementException ignore) {
    }
    return memory;
  }

  @Override
  protected void resetKeysPerMember() {
    super.resetKeysPerMember();
    if (this.keysCountPerBucket != null) {
      this.keysCountPerBucket.clear();
    }
  }
}
