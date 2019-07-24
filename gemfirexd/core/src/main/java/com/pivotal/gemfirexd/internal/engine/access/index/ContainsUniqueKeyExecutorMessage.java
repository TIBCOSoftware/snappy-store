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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXEntryState;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.FetchFromMap;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionSingleKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * Execution function message to perform containsKey() for foreign key on unique
 * key columns constraint check.
 * 
 * @author kneeraj
 */
public final class ContainsUniqueKeyExecutorMessage extends
    RegionSingleKeyExecutorMessage implements FetchFromMap {

  private int[] referenceKeyColumnIndexes;

  /**
   * the actual skiplist key object as read using the
   * {@link FetchFromMap#setMapKey(Object, int)} callback
   */
  private transient Object mapKey;

  /**
   * the skiplist node's version field as read using the
   * {@link FetchFromMap#setMapKey(Object, int)} callback
   */
  private transient int keyVersion;

  /** for deserialization */
  public ContainsUniqueKeyExecutorMessage() {
    super(true);
  }

  public ContainsUniqueKeyExecutorMessage(LocalRegion refregion,
      int[] refKeyColumnIndexes, Object indexKey, Object routingObject,
      TXStateInterface tx, LanguageConnectionContext lcc) {
    super(refregion, indexKey, null, routingObject, false, tx,
        getTimeStatsSettings(lcc));
    this.referenceKeyColumnIndexes = refKeyColumnIndexes;
  }

  protected ContainsUniqueKeyExecutorMessage(
      final ContainsUniqueKeyExecutorMessage other) {
    super(other);
    this.referenceKeyColumnIndexes = other.referenceKeyColumnIndexes;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected ContainsUniqueKeyExecutorMessage clone() {
    return new ContainsUniqueKeyExecutorMessage(this);
  }

  /**
   * @see TransactionMessage#canStartRemoteTransaction()
   */
  @Override
  public final boolean canStartRemoteTransaction() {
    return getLockingPolicy().readOnlyCanStartTX();
  }

  @Override
  public boolean isHA() {
    return true;
  }
  
  public static boolean existsKey(TXStateInterface tx, LocalRegion lr,
      int[] referenceKeyColumnIndexes, Object indexKey, Object callbackArg,
      FetchFromMap fetch) throws Exception {
    boolean containsKey = false;
    final GfxdIndexManager idmanager = (GfxdIndexManager) lr.getIndexUpdater();
    final List<GemFireContainer> localIndexContainers = idmanager
        .getIndexContainers();
    if (localIndexContainers != null && !localIndexContainers.isEmpty()) {
      for (final GemFireContainer indexContainer : localIndexContainers) {
        if (!indexContainer.isLocalIndex() || !indexContainer.isUniqueIndex()) {
          continue;
        }

        final int[] indexKeyPositions = indexContainer.getBaseColumnPositions();
        if (indexKeyPositions.length != referenceKeyColumnIndexes.length) {
          continue;
        }
        if (Arrays.equals(indexKeyPositions, referenceKeyColumnIndexes)) {
          // need to check the presence of indexkey in this index
          final ConcurrentSkipListMap<Object, Object> map = indexContainer
              .getSkipListMap();
          RowLocation rl = (RowLocation)map.get(OpenMemIndex
              .newLocalKeyObject(indexKey, indexContainer), fetch, null);
          // check if RowLocation is valid for this transaction, if any
          final TXId rlTXId;
          if (rl != null && (rlTXId = rl.getTXId()) != null) {
            rl = SortedMap2IndexScanController.AbstractRowLocationIterator
                .isRowLocationValidForTransaction(rl, rlTXId, tx,
                    GfxdConstants.SCAN_OPENMODE_FOR_READONLY_LOCK, indexContainer.isUniqueIndex());
          }
          final Object ckey = fetch.getCurrentKey();
          final CompactCompositeIndexKey mapKey = ckey != null
              // we never expect a subclass of CCIK as stored index key
              && ckey.getClass() == CompactCompositeIndexKey.class
              ? (CompactCompositeIndexKey)ckey : null;
          if (rl != null) {
            containsKey = true;
            boolean requalify = false;
            @Retained @Released Object val = null;
            if (tx != null) {
              final RegionEntry re = rl.getUnderlyingRegionEntry();
              final Object key = rl.getKey();
              final LocalRegion dataRegion = lr.getDataRegionForRead(
                  key, null, rl.getBucketID(), Operation.GET_ENTRY);
              if (tx.lockEntry(re, key, callbackArg, lr, dataRegion, false,
                  false, TXEntryState.getReadOnlyOp(),
                  TXState.LOCK_ENTRY_NOT_FOUND) == null) {
                containsKey = false;
              }
              else if (mapKey != null) {
                if (rl.isUpdateInProgress()) {
                  requalify = true;
                  val = rl.getValueWithoutFaultIn(idmanager.getContainer());
                }
                else {
                  // just access the value before getting the version
                  ((RegionEntry)rl).getValueAsToken();
                  if ((requalify = (mapKey.getVersion() != fetch
                      .getCurrentNodeVersion()))) {
                    val = rl.getValueWithoutFaultIn(idmanager.getContainer());
                  }
                }
              }
            }
            else if (mapKey != null && rl.isUpdateInProgress()) {
              // no need to check for versions for non-tx case since this is
              // single entry whose replace will be in order in case there is a
              // change in index and UPDATE_IN_PROGRESS flag check is enough
              requalify = true;
              val = rl.getValueWithoutFaultIn(idmanager.getContainer());
            }
            // re-qualify the entry if node version has changed or RowLocation
            // is being updated
            if (containsKey && requalify && val != null
                && !(val instanceof Token)) {
              try {
                boolean result = mapKey.equalsValueBytes(val);
                final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
                    .getInstance();
                if (observer != null) {
                  observer.afterIndexRowRequalification(result, mapKey, null,
                      null);
                }
              } finally {
                OffHeapHelper.release(val);
              }
            }
          }
          break;
        }
      }
    } else {
      throw new IllegalStateException(
          "expected at least one index for the table using region: "
              + lr.getFullPath());
    }
    return containsKey;
  }

  @Override
  protected void execute() throws Exception {
    final TXStateInterface tx = getTXState();

    boolean containsKey = existsKey(tx, this.region,
        this.referenceKeyColumnIndexes, this.key, this.callbackArg, this);

    if (this.isSecondaryCopy) {
      // DUMMY_RESULT indicates dummy result and only for locking
      lastResult(DUMMY_RESULT, false, false, true);
    }
    else {
      lastResult(Boolean.valueOf(containsKey), false, true, true);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setMapKey(Object key, int version) {
    this.mapKey = key;
    this.keyVersion = version;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getCurrentKey() {
    return this.mapKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getCurrentNodeVersion() {
    return this.keyVersion;
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
    return this.pendingTXId == null && getLockingPolicy() == LockingPolicy.NONE
        ? DistributionManager.SERIAL_EXECUTOR
        : DistributionManager.PARTITIONED_REGION_EXECUTOR;
  }

  @Override
  public byte getGfxdID() {
    return CONTAINS_UNIQUE_KEY_EXECUTOR_MSG;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.toData(out);
    DataSerializer.writeIntArray(this.referenceKeyColumnIndexes, out);
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
    this.referenceKeyColumnIndexes = DataSerializer.readIntArray(in);
    // recording end of de-serialization here instead of AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";refColumnIndexes=").append(
        Arrays.toString(this.referenceKeyColumnIndexes));
  }
}
