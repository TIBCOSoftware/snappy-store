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
/**
 * 
 */
package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValue;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RetryTimeKeeper;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2IndexScanController;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.OffHeapReleaseUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * @author vivekb
 * 
 */
public class GetAllLocalIndexExecutorMessage extends
    RegionMultiKeyExecutorMessage {
  protected long connectionId;

  private String qualifiedIndexName;

  private final Object[] inKeys;

  /** Empty constructor for deserialization. Not to be invoked directly. */
  public GetAllLocalIndexExecutorMessage() {
    super(true);
    this.inKeys = null;
  }

  /**
   * Constructor
   * 
   * @param qualifiedName
   * @param keys
   * @param region
   * @param lcc
   * @param connectionId
   * @param tx
   * @param targetFormat
   * @param projectionFixedColumns
   * @param projectionVarColumns
   * @param projectionLobColumns
   * @param projectionAllColumns
   */
  public GetAllLocalIndexExecutorMessage(String qualifiedName, Object[] keys,
      LocalRegion region, final LanguageConnectionContext lcc,
      long connectionId, final TXStateInterface tx, RowFormatter targetFormat,
      int[] projectionFixedColumns, int[] projectionVarColumns,
      int[] projectionLobColumns, int[] projectionAllColumns) {
    super(new GfxdListResultCollector(null, false), region,
        null /*routingObjects*/, getCurrentTXState(lcc),
        getTimeStatsSettings(lcc), targetFormat, projectionFixedColumns,
        projectionVarColumns, projectionLobColumns, projectionAllColumns);
    this.qualifiedIndexName = qualifiedName;
    this.inKeys = keys;
    this.connectionId = connectionId;
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(this.qualifiedIndexName != null);
      SanityManager.ASSERT(this.inKeys != null);
      SanityManager.ASSERT(this.membersToKeys != null);
      // No scenario for this to be not null
      SanityManager.ASSERT(this.routingObjects == null);
    }
  }

  /**
   * @param other
   */
  public GetAllLocalIndexExecutorMessage(
      GetAllLocalIndexExecutorMessage other) {
    super(other);
    this.inKeys = other.inKeys;
    this.qualifiedIndexName = other.qualifiedIndexName;
    this.connectionId = other.connectionId;
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.RegionMultiKeyExecutorMessage#clone()
   */
  @Override
  protected GetAllLocalIndexExecutorMessage clone() {
    return new GetAllLocalIndexExecutorMessage(this);
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage#setArgsForMember(com.gemstone.gemfire.distributed.DistributedMember, java.util.Set)
   */
  @Override
  protected void setArgsForMember(DistributedMember member,
      Set<DistributedMember> messageAwareMembers) {
    this.target = member;
    // Do not set "membersToKeys"; @see setMembersToBucketIds
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, this.getID()
          + ".setArgsForMember: keys for " + this.regionPath + " :- ["
          + this.membersToKeys + "] for member= " + member.toString());
    }
  }

  @Override
  protected void setMembersToBucketIds(RetryTimeKeeper retryTime)
      throws StandardException {
    super.setMembersToBucketIds(retryTime);
    resetKeysPerMember();
    /* Note: 
     * Same "membersToKeys" would be sent to all Nodes 
     * So do not call reset from this class
     */
    for (Object key : this.inKeys) {
      this.membersToKeys.add(key);
    }
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage#execute()
   */
  @Override
  protected void execute() throws Exception {
    @Retained @Released final ArrayList<Object> resultList = new ArrayList<Object>();
    final int numKeys = this.membersToKeys.size();
    final TXStateInterface tx = this.getTXState(this.region);
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, this.getID()
          + ".execute: Got keys " + this.membersToKeys + " of size [" + numKeys
          + "] " + " for region " + this.region.getFullPath()
          + " , processorId=" + getProcessorId() + " ,tx="
          + (tx != null ? tx : "null"));
    }

    final GemFireContainer baseContainer = (GemFireContainer) this.region
        .getUserAttribute();
    final GfxdIndexManager idmanager = (GfxdIndexManager) this.region
        .getIndexUpdater();
    final List<GemFireContainer> localIndexContainers = idmanager
        .getIndexContainers();
    try {
      if (localIndexContainers != null && !localIndexContainers.isEmpty()) {
        boolean foundIndex = false;
        for (final GemFireContainer indexContainer : localIndexContainers) {
          if (indexContainer.isLocalIndex()
              && indexContainer.getQualifiedTableName().equals(
                  qualifiedIndexName)) {
            foundIndex = true;
            /*
             * @see ReferencedKeyCheckerMessage#execute Get wrapper and
             * connection to get GemFireTransaction
             */
            final GfxdConnectionWrapper wrapper = GfxdConnectionHolder
                .getOrCreateWrapper(null, this.connectionId, false, null);
            final EmbedConnection conn = wrapper
                .getConnectionForSynchronization();
            boolean scanOpened = false;
            ScanController sc = null;
            synchronized (conn.getConnectionSynchronization()) {
              LanguageConnectionContext lcc = null;
              GemFireTransaction tc = null;
              int oldLCCFlags = 0;
              try {
                /**
                 * Note: embedConn of wrapper may be Null at this stage since we
                 * are not converting to Hard Reference.
                 */
                lcc = conn.getLanguageConnectionContext();
                oldLCCFlags = lcc.getFlags();
                tc = (GemFireTransaction) lcc.getTransactionExecute();
                GfxdConnectionWrapper.checkForTransaction(conn, tc, tx);
                if (lcc.getBucketIdsForLocalExecution() != null) {
                  SanityManager
                      .THROWASSERT("lcc should have no region buckets set, but in lcc we are getting "
                          + lcc.getBucketIdsForLocalExecution());
                }
                
                // Set bucket ids received from QN for use in
                // @see SortedMap2IndexScanController#initEnumerator
                lcc.setExecuteLocally(this.bucketBitSet, this.region, false,
                    null);

                final DataValueDescriptor[] currentDvdKeyArr = new DataValueDescriptor[1];
                for (int keyIndex = 0; keyIndex < numKeys; keyIndex++) {
                  /*
                   * @see ComparisonQueryInfo#attemptToGetGlobalIndexBasedPruner
                   * 
                   * @see ReferencedKeyCheckerMessage#execute Prepare DVD from
                   * key and open scan controller
                   */
                  final DataValueDescriptor[] dvdKeyArr;
                  Object currentKey = this.membersToKeys.get(keyIndex);
                  if (currentKey instanceof DataValueDescriptor[]) {
                    dvdKeyArr = (DataValueDescriptor[]) currentKey;
                  }
                  else if (currentKey instanceof DataValueDescriptor) {
                    currentDvdKeyArr[0] = (DataValueDescriptor)currentKey;
                    dvdKeyArr = currentDvdKeyArr;
                  }
                  else {
                    SanityManager.THROWASSERT("currentkey=" + currentKey
                        + " ,keyIndex=" + keyIndex + " ,type="
                        + currentKey.getClass().getSimpleName());
                    dvdKeyArr = null;
                  }
                  if (sc == null) {
                    sc = tc.openScan(indexContainer.getId().getContainerId(),
                        true, 0, TransactionController.MODE_RECORD,
                        TransactionController.ISOLATION_NOLOCK/* not used */,
                        null, dvdKeyArr, ScanController.GE, null, dvdKeyArr,
                        ScanController.GT, null);
                    /*
                     * Scan controller @see SortedMap2IndexScanController#next
                     */
                    assert sc instanceof SortedMap2IndexScanController;
                    scanOpened = true;
                  } else {
                    sc.reopenScan(dvdKeyArr, ScanController.GE, null,
                        dvdKeyArr, ScanController.GT, null);
                  }
                  /*
                   * From row location, find row. Apply Projection if needed
                   * @see GetExecutorMessage#executeOneKey
                   */
                  int resultCount = 0;
                  RowLocation rl = null;
                  while (sc.next()) {
                    resultCount++;
                    rl = sc.getCurrentRowLocation();
                    if (rl != null) {
                      @Retained @Released Object val = rl.getValueWithoutFaultIn(baseContainer);
                      ProjectionRow projRow = null;
                      if (val instanceof RawValue) {
                        projRow = (ProjectionRow) val;
                        val = projRow.getRawValue();
                      }
                      if (val != null && !Token.isInvalid(val)) {
                        final Object result;
                        if (this.hasProjection) {
                          // if sending back result locally then directly create
                          // AbstractCompactExecRow
                          if (this.isLocallyExecuted()) {
                            try {
                              result = ProjectionRow.getCompactExecRow(val,
                                  baseContainer, this.targetFormat,
                                  this.projectionAllColumns,
                                  this.projectionLobColumns);
                            } finally {
                              OffHeapHelper.release(val);
                            }
                          } else {
                            // RawValue created will actually be ProjectionRow;
                            // just embed projection information in that
                            if (projRow == null) {
                              projRow = new ProjectionRow(val);
                            }
                            projRow.setProjectionInfo(
                                baseContainer.getRowFormatter(val),
                                this.projectionFixedColumns,
                                this.projectionVarColumns,
                                this.projectionLobColumns,
                                this.targetFormatOffsetBytes);
                            result = projRow;
                          }
                        } else {
                          result = val;
                        }
                        if (GemFireXDUtils.TraceRSIter) {
                          SanityManager.DEBUG_PRINT(
                              GfxdConstants.TRACE_RSITER,
                              this.getID() + ".execute: Got result for key= "
                                  + currentKey + " (keyIndex=" + keyIndex
                                  + " ) for Index on region "
                                  + region.getFullPath() + " result=" + " ["
                                  + result + "]");
                        }
                        resultList.add(result);
                      }
                    }
                  }

                  if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                        this.getID() + ".execute: Got " + resultCount
                            + " no of result for key= " + currentKey
                            + " (keyIndex=" + keyIndex
                            + " ) for Index on region " + region.getFullPath());
                  }
                }
              } finally {
                try {
                  if (scanOpened) {
                    sc.close();
                  }
                } finally {
                  if (lcc != null) {
                    lcc.setFlags(oldLCCFlags);
                    lcc.clearExecuteLocally();
                  }
                  // reset cached TXState
                  if (tc != null) {
                    tc.resetTXState();
                  }
                }
              }
            }
            break;
          }
        }
        if (!foundIndex) {
          StringBuilder indexList = new StringBuilder();
          for (final GemFireContainer indexContainer : localIndexContainers) {
            indexList.append("(");
            indexList.append(indexContainer.getQualifiedTableName());
            indexList.append(":");
            indexList.append(indexContainer.isLocalIndex());
            indexList.append(")");
          }
          throw new IllegalStateException("Did not find chosen index="
              + this.qualifiedIndexName + " for region: "
              + this.region.getFullPath() + " in index-list {" + indexList
              + "}");
        }
      } else {
        throw new IllegalStateException(
            "expected at least one index for the table using region: "
                + this.region.getFullPath());
      }

      try {
        lastResult(resultList, false, true, true);
      } catch (RuntimeException re) {
        if (GemFireXDUtils.isOffHeapEnabled() && this.isLocallyExecuted()) {
          OffHeapReleaseUtil.freeOffHeapReference(resultList);
        }
        throw re;
      }
    } finally {
      // Iterate over the resultlist to free the byte Source
      if (GemFireXDUtils.isOffHeapEnabled() && !this.isLocallyExecuted()) {
        OffHeapReleaseUtil.freeOffHeapReference(resultList);
      }
    }
  }

  @Override
  public final int getMessageProcessorType() {
    return DistributionManager.PARTITIONED_REGION_EXECUTOR;
  }

  /**
   * @see TransactionMessage#canStartRemoteTransaction()
   */
  @Override
  public final boolean canStartRemoteTransaction() {
    return getLockingPolicy().readCanStartTX();
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    // use TX proxy to enable batching of read locks
    return getLockingPolicy().readCanStartTX();
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    // to flush any batched read locks
    return getLockingPolicy().readCanStartTX();
  }

  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage#getGfxdID()
   */
  @Override
  public byte getGfxdID() {
    return GET_ALL_LOCAL_INDEX_EXECUTOR_MSG;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    final long beginTime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2 /*ignore nested call*/) : 0;
    super.toData(out);

    // @see StatementExecutorMessage#toData
    GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);

    DataSerializer.writeString(this.qualifiedIndexName, out);
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
    this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
    this.qualifiedIndexName = DataSerializer.readString(in);

    // recording end of de-serialization here instead of
    // AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";connectionId=").append(this.connectionId)
        .append(";qualifiedIndexName=").append(this.qualifiedIndexName);
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    long memory = 0;

    try {
      for (Object k : this.membersToKeys) {
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
    } catch (ConcurrentModificationException ignore) {
    } catch (NoSuchElementException ignore) {
    }
    return memory;
  }
  
  @Override
  public void reset() {
    super.reset();
    resetKeysPerMember();
  }

  @Override
  public boolean withSecondaries() {
    return false;
  }
}
