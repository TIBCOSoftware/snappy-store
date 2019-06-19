
/*

 Derived from source files from the Derby project.

 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to you under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

/*
 This file was based on the MemStore patch written by Knut Magne, published
 under the Derby issue DERBY-2798 and released under the same license,
 ASF, as described above. The MemStore patch was in turn based on Derby source
 files.
*/

package com.pivotal.gemfirexd.internal.engine.access.heap;

import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.execute.
    InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.partitioned.PREntriesIterator;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.MemScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalExecRowLocation;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapResourceHolder;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.BackingStoreHashtable;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowCountable;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Page;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * DOCUMENT ME!
 *
 * @author  $author$
 */
public class MemHeapScanController implements MemScanController, RowCountable,
    ScanInfo {

  /*
  public static final int SCAN_INIT = 1;

  public static final int SCAN_INPROGRESS = 2;

  public static final int SCAN_DONE = 3;

  public static final int SCAN_HOLD_INIT = 4;

  public static final int SCAN_HOLD_INPROGRESS = 5;
  */

  // it doesn't appear that a base table scan is supposed to do any kind of
  // column filtering, whole rows are always returned.
  //private FormatableBitSet init_scanColumnList;

  //private DataValueDescriptor[] init_startKeyValue;

  //private DataValueDescriptor[] init_stopKeyValue;

  private Qualifier[][] init_qualifier;

  //private int scan_state;

  private Iterator<?> entryIterator;

  private PREntriesIterator<?> prEntryIterator;

  private RowLocation currentRowLocation;

  private int bucketId = -1;

  /** ExecRow for the current entry */
  private ExecRow currentExecRow;

  /** Shell ExecRow used for raw bytes storage */
  private AbstractCompactExecRow templateCompactExecRow;
  private boolean byteArrayStore;

  private LocalRegion currentDataRegion;

  protected GemFireTransaction tran;

  private Set<Integer> bucketSet;

  private boolean hasNext = true;

  protected long numRows = -1L;

  private int rowlength = -1;

  protected int openMode;

  /**
   * non-zero (ExclusiveSharedSynchronized.FOR_UPDATE) if this scan is for
   * update else zero
   */
  protected int forUpdate;

  protected GemFireContainer gfContainer;

  protected boolean isOffHeap;

  private boolean addRegionAndKey = false;

  private boolean addKeyForSelectForUpdate = false;

  private String regionName = "";

  GemFireXDQueryObserver observer;

  private LanguageConnectionContext lcc;

  private Activation activation;

  private TXStateInterface txState;

  private TXId txId;

  private boolean snashotTxStarted;

  private LockingPolicy lockPolicy;

  private LockMode readLockMode;

  private TXState localTXState;

  private TXStateInterface localSnapshotTXState;

  private Object lockContext;

  private boolean restoreBatching = true;

  private int statNumRowsVisited;

  private int statNumRowsQualified;

  private int statNumDeletedRowsVisited;

  private FormatableBitSet statValidColumns;

  private static CountDownLatch testLatchAfterFirstQualify;

  private static CountDownLatch testBarrierBeforeFirstScan;
  private OffHeapResourceHolder offheapOwner;

  private static final ThreadLocal<Integer> waitForLatch =
      new ThreadLocal<Integer>() {
    @Override
    protected Integer initialValue() {
      return Integer.valueOf(0);
    }
  };
  
  private boolean queryHDFS = false;

  // Artificial synchronized write to set testLatchAfterFirstQualify correctly
  // as cannot make that volatile because product code also reads that field.
  // This method will be called only by test code and hence an artificial
  // memory barrier here.
  public static synchronized void setWaitForLatchForTEST(int sleepSecs) {
    waitForLatch.set(sleepSecs);
  }

  /**
   * Latch used as a hook for tests only.
   */
  public static void setWaitObjectAfterFirstQualifyForTEST(
      final CountDownLatch latch) {
    testLatchAfterFirstQualify = latch;
  }

  public static void setWaitBarrierBeforeFirstScanForTEST(
      final CountDownLatch barrier) {
    testBarrierBeforeFirstScan = barrier;
  }

  protected void init(GemFireTransaction tran, MemConglomerate conglomerate,
      int openMode, int lockLevel,
      com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy locking)
      throws StandardException {
    this.gfContainer = conglomerate.getGemFireContainer();
    assert this.gfContainer != null;
    this.isOffHeap = this.gfContainer.isOffHeap();
    conglomerate.openContainer(tran, openMode, lockLevel, locking);
    this.tran = tran;
    this.openMode = openMode;
    this.observer = GemFireXDQueryObserverHolder.getInstance();
  }

  public final GemFireContainer getGemFireContainer() {
    return this.gfContainer;
  }

  public final boolean isKeyed() {
    return false;
  }

  public final void init(GemFireTransaction tran, MemConglomerate conglomerate,
      int openMode, int lockLevel,
      com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy locking,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation act)
      throws StandardException {
    init(tran, conglomerate, openMode, lockLevel, locking);
    this.lcc = this.tran.getLanguageConnectionContext();

    if (this.lcc != null && this.lcc.getRunTimeStatisticsMode()) {
      this.statValidColumns = scanColumnList != null ? scanColumnList.clone()
          : null;
    }
    else {
      this.statValidColumns = null;
    }
    this.statNumRowsVisited = 0;
    this.statNumDeletedRowsVisited = 0;
    this.statNumRowsQualified = 0;

    if (this.observer != null) {
      this.observer.scanControllerOpened(this, conglomerate);
    }

    this.activation = act;
    positionAtInitScan(startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator, act);
  }

  public final int getType() {
    return MemConglomerate.HEAP;
  }

  public final boolean isScanClosed() {
    return (this.tran == null);
  }

  @SuppressWarnings("unchecked")
  protected final void positionAtInitScan(DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      final Activation act) throws StandardException {
    // get queryHDFS connection properties
    if (this.lcc != null) {
      this.queryHDFS = lcc.getQueryHDFS();
    }
    // get queryHDFS query hint, which overrides connection property
    if (act != null) {
      if (act.getHasQueryHDFS()) {
        // if user specifies queryHDFS query hint
        this.queryHDFS = act.getQueryHDFS();
      }
    }

    this.forUpdate = (openMode & TransactionController.OPENMODE_FORUPDATE) != 0
        ? ExclusiveSharedSynchronizer.FOR_UPDATE : 0;
    final LocalRegion region = this.gfContainer.getRegion();
    // region null implies create table is in progress
    if (region == null) {
      return;
    }
    else if (region instanceof PartitionedRegion) {
      if (this.forUpdate != 0) {
        // UPDATE/DELETE always goes to HDFS, regardless of queryHDFS
        this.queryHDFS = true;
      }
      ((PartitionedRegion) region).setQueryHDFS(this.queryHDFS);
    }
    this.regionName = this.gfContainer.getQualifiedTableName();
    FunctionContext fc = null;
    if (GemFireXDUtils.TraceOuterJoin) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
          "MemHeapScanController::positionAtInitScan this is: "
              + System.identityHashCode(this) + " activation is: "
              + (act != null ? System.identityHashCode(act) : "null")
              + " addregionandkey info is: "
              + (act != null ? act.isSpecialCaseOuterJoin() : "null"));
    }
    if (act != null) {
      this.addRegionAndKey = act.isSpecialCaseOuterJoin();
      this.addKeyForSelectForUpdate = act.needKeysForSelectForUpdate();
      // try to get FunctionContext from Activation
      fc = act.getFunctionContext();
    }
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "MemHeapScanController scanning table: " + this.regionName
              + ", openMode=" + openMode + " startKey="
              + ArrayUtils.objectString(startKeyValue) + " startOp="
              + startSearchOperator + " stopKey="
              + ArrayUtils.objectString(startKeyValue) + " stopOp="
              + stopSearchOperator + " qualifier="
              + ArrayUtils.objectString(qualifier)
              + ", and the function context: " + fc);
    }

    this.txState = this.gfContainer.getActiveTXState(this.tran);

    boolean restoreBatching = false;
    if (this.txState != null) {
      this.txId = this.txState.getTransactionId();
      this.lockPolicy = this.txState.getLockingPolicy();
      if (this.forUpdate != 0) {
        this.readLockMode = LockMode.SH;
        this.localTXState = this.txState.getTXStateForWrite();
        this.lockContext = this.localTXState
            .getReadLocksForScanContext(this.lcc);
        restoreBatching = this.localTXState.getProxy().remoteBatching(true);
      }
      else if (this.lockPolicy.zeroDurationReadLocks()) {
        this.readLockMode = null; // no momentary read locks for RC now
        this.localTXState = this.txState.getLocalTXState();
        this.lockContext = null;
        restoreBatching = true;
      }
      else {
        this.readLockMode = this.lockPolicy.getReadLockMode();
        this.localTXState = this.txState.getTXStateForRead();
        if (this.localTXState == null) {
          Assert.fail("unexpected null local read TXState lockingPolicy="
              + this.lockPolicy + " for " + this.txState);
        }
        this.lockContext = this.localTXState
            .getReadLocksForScanContext(this.lcc);
        restoreBatching = this.localTXState.getProxy().remoteBatching(true);
      }
    }// if fc is null then the query is from snappy side.
    else if (this.gfContainer.isRowBuffer() ||
        (region.getCache().snapshotEnabledForTest() && region.getConcurrencyChecksEnabled())) {

      // Start snapshot tx only for read operations.but not for read_only mode
      //boolean forReadOnly = (this.openMode & GfxdConstants
      //    .SCAN_OPENMODE_FOR_READONLY_LOCK) != 0;
      if (region.getConcurrencyChecksEnabled() &&
          (region.getCache().getCacheTransactionManager().getTXState() == null)
          /*&& (this.forUpdate == 0)*/
          /*&& !forReadOnly*/) {

        if (GemFireXDUtils.TraceQuery) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "MemHeapScanController scanning table: " + this.regionName
                  + ", openMode=" + openMode + " starting the gemfire snapshot tx.");
        }
        // We can begin each time as we have to clear below as we don't know when commit will take place.
        region.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        if (region.getCache().getRowScanTestHook() != null) {
          region.getCache().notifyScanTestHook();
          region.getCache().waitOnRowScanTestHook();
        }
        this.txState = region.getCache().getCacheTransactionManager().getTXState();
        this.localTXState = this.txState.getTXStateForRead();
        this.snashotTxStarted = true;
        this.txId = this.txState.getTransactionId();
        this.lockPolicy = this.txState.getLockingPolicy();
        this.readLockMode = this.lockPolicy.getReadLockMode();
        this.lockContext = null;
        restoreBatching = true;
      }
    } else {
      this.txId = null;
      this.readLockMode = null;
      this.localTXState = null;
      this.lockContext = null;
      restoreBatching = true;
    }

    // if the restoreBatching flag has already been set in previous open, then
    // don't reset to true due to a reopen
    if (!restoreBatching && this.restoreBatching) {
      this.restoreBatching = false;
    }

    this.currentDataRegion = null;

    if (fc != null) {
      if (fc instanceof RegionFunctionContext) {
        InternalRegionFunctionContext rfc = (InternalRegionFunctionContext)fc;

        boolean primaryOnly = true;
        if (fc instanceof GfxdFunctionMessage<?>) {
          primaryOnly = ((GfxdFunctionMessage<?>)fc).optimizeForWrite();
        }

        this.entryIterator = gfContainer.getEntrySetIteratorForFunctionContext(
            rfc, this.tran, this.txState, this.openMode, primaryOnly);
        this.bucketSet = rfc.getLocalBucketSet(region);

      }
      else {
        // [sumedh] If we support MultiRegionFunctionContext in future then
        // we still need the bucketSet for this container's region
        SanityManager.THROWASSERT(new UnsupportedOperationException(
            "unexpected function context: " + fc));
        /*
        assert fc instanceof MultiRegionFunctionContextImpl;
        if ((this.openMode & GfxdConstants.SCAN_OPENMODE_FOR_FULLSCAN) != 0) {
          this.entryIterator = this.gfContainer.getEntrySetIterator(tran,
              false /* include secondary *, this.openMode);
          // this.gfContainer.getAllEntrySetIterator();
        }
        else {
          this.entryIterator = this.gfContainer.getEntrySetIterator(tran,
              true /* only primary *, this.openMode);
        }
        */
      }
    } else if (lcc != null && lcc.getHDFSSplit() != null) {
      entryIterator = gfContainer.getEntrySetIteratorHDFSSplit(lcc.getHDFSSplit());
    }
    else {
      Set<Integer> bset = lcc == null ? null : lcc
          .getBucketIdsForLocalExecution();
      Region<?, ?> regionForBSet = null;
      boolean prpLEItr = false;
      if (bset != null) {
        regionForBSet = lcc.getRegionForBucketSet();
        if (regionForBSet == this.gfContainer.getRegion()) {
          prpLEItr = true;
        }
      }
      if (prpLEItr) {
        if (GemFireXDUtils.TraceQuery || SanityManager.TraceSingleHop) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
              "MemHeapScanController::positionAtInitScan bucketSet: " + bset
                  + " and forUpdate=" + (this.forUpdate != 0) + " this table: "
                  + this.gfContainer.getQualifiedTableName() + " and lcc is: "
                  + lcc + " region is: " + (regionForBSet != null
                      ? regionForBSet.getName() : "(null)"));
        }
        this.bucketSet = bset;
        // incase of snappy fetch remote entry of bucket by default
        this.entryIterator = gfContainer.getEntrySetIteratorForBucketSet(bset,
            tran, this.txState, openMode, this.forUpdate != 0, Misc.getMemStore().isSnappyStore());
      }
      else {
        boolean useOnlyPrimaryBuckets = ((this.openMode & GfxdConstants
            .SCAN_OPENMODE_FOR_FULLSCAN) == 0)
            || (act != null && act.getUseOnlyPrimaryBuckets());
        this.entryIterator = gfContainer.getEntrySetIterator(this.txState,
            useOnlyPrimaryBuckets, this.openMode, true);
      }
    }

    if (this.entryIterator instanceof PREntriesIterator<?>) {
      this.prEntryIterator = (PREntriesIterator<?>)this.entryIterator;
    }
    else {
      this.prEntryIterator = null;
    }

    // qualifier init.
    if ((qualifier != null) && (qualifier.length == 0)) {
      qualifier = null;
    }

    this.init_qualifier = qualifier;
    final RowFormatter rf;
    if (init_qualifier != null
        && (rf = this.gfContainer.getCurrentRowFormatter()) != null) {
      for (int idx = init_qualifier.length - 1; idx >= 0; idx--) {
        for (Qualifier q : init_qualifier[idx]) {
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceByteComparisonOptimization) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION,
                  "attempting to re-align qualifier " + q);
            }
          }
          q.alignOrderableCache(rf.getColumnDescriptor(q.getColumnId()),
              this.gfContainer);
        }
      }
    }

    if ((this.byteArrayStore = this.gfContainer.isByteArrayStore())) {
      this.templateCompactExecRow = (AbstractCompactExecRow)this.gfContainer
          .newTemplateRow();
    }
    else {
      this.templateCompactExecRow = null;
    }

    if (snashotTxStarted) {
      // clear the txState so that other thread local is cleared.
      TXManagerImpl.TXContext context = TXManagerImpl.getOrCreateTXContext();
      context.clearTXState();
      // gemfire tx shouldn't be cleared in case of row buffer scan
      if (!(this.getGemFireContainer().isRowBuffer() && (fc == null))) {
        if (GemFireXDUtils.TraceQuery) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "MemHeapScanController::positionAtInitScan bucketSet: " + " lcc.isSkipConstraintChecks " + lcc.isSkipConstraintChecks() +
          " " + "Setting snapshotTxStae to NULL.");
        }
        context.setSnapshotTXState(null);
        this.localSnapshotTXState = this.txState;
      }
      this.txState = null;
      this.localTXState = null;
      this.txId = null;
      this.lockPolicy = null;
      this.readLockMode = null;
      this.lockContext = null;
      restoreBatching = true;
      snashotTxStarted = false;
    }
  }

  public final boolean delete() throws StandardException {
    assert this.currentRowLocation != null;
    final RegionEntry entry = this.currentRowLocation.getRegionEntry();   
    return delete(this.tran, this.txState, this.gfContainer, entry,
        this.bucketId);    
  }

  static boolean delete(GemFireTransaction tran, TXStateInterface tx,
      GemFireContainer container, RegionEntry entry, int bucketId)
      throws StandardException {
    boolean deleted = true;
    try {
      if (entry == null || entry.isDestroyedOrRemoved()) {
        return false;
      }
      final Object regionKey = entry.getKeyCopy();
      Object routingObject = GemFireXDUtils.getRoutingObject(bucketId);
      if (routingObject == null && container.isPartitioned()) {
        routingObject = GemFireXDUtils.getRoutingObjectFromGlobalIndex(regionKey,
            entry, container.getRegion());
      }
      container.delete(regionKey, routingObject, false, tran, tx,
          GemFireTransaction.getLanguageConnectionContext(tran), false);
    } catch (EntryNotFoundException enfe) {
      // Can occur. Eat it.
      deleted = false;
    } catch (EntryDestroyedException ede) {
      // Can occur in getRoutingObjectFromGlobalIndex. Eat it.
      deleted = false;
    }
    return deleted;
  }

  /**
   * Returns true if the current position of the scan still qualifies under the
   * set of qualifiers passed to the openScan(). When called this routine will
   * reapply all qualifiers against the row currently positioned and return true
   * if the row still qualifies. If the row has been deleted or no longer passes
   * the qualifiers then this routine will return false.
   * 
   * This case can come about if the current scan or another scan on the same
   * table in the same transaction deleted the row or changed columns referenced
   * by the qualifier after the next() call which positioned the scan at this
   * row.
   * 
   * Note that for comglomerates which don't support update, like btree's, there
   * is no need to recheck the qualifiers.
   * 
   * The results of a fetch() performed on a scan positioned on a deleted row
   * are undefined, note that this can happen even if next() has returned true
   * (for instance the client can delete the row, or if using read uncommitted
   * another thread can delete the row after the next() call but before the
   * fetch).
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  public final boolean doesCurrentPositionQualify() throws StandardException {
    // ??ezoerner:20100128 not sure what to do here,
    // do we need to re-evaluate the current row to determine if it
    // still qualifies? This is used with cursors and updatable result sets.
    // For now, just assume it still qualifies
    return true;
  }

  /**
   * Fetch the (partial) row at the current position of the Scan. The value in
   * the destRow storable row is replaced with the value of the row at the
   * current scan position. The columns of the destRow row must be of the same
   * type as the actual columns in the underlying conglomerate. The number of
   * elements in fetch must be compatible with the number of scan columns
   * requested at the openScan call time.<BR>
   * A fetch can return a sub-set of the scan columns reqested at scan open time
   * by supplying a destRow will less elements than the number of requested
   * columns. In this case the N leftmost of the requested columns are fetched,
   * where N = destRow.length. In the case where all columns are rested and N =
   * 2 then columns 0 and 1 are returned. In the case where the openScan
   * FormatableBitSet requested columns 1, 4 and 7, then columns 1 and 4 would
   * be fetched when N = 2.<BR>
   * The results of a fetch() performed on a scan after next() has returned
   * false are undefined. A fetch() performed on a scan positioned on a deleted
   * row will throw a StandardException with state =
   * SQLState.AM_RECORD_NOT_FOUND. Note that this can happen even if next() has
   * returned true (for instance the client can delete the row, or if using read
   * uncommitted another thread can delete the row after the next() call but
   * before the fetch).
   *
   * @param  destRow  The row into which the value of the current position in
   *                  the scan is to be stored.
   *
   * @exception  StandardException  Standard exception policy.
   *
   * @see  RowUtil
   */
  public final void fetch(ExecRow destRow) throws StandardException {
    // !!ezoerner:20100128 this method is supposed to qualify the row
    // before returning it using the qualifiers passed into openScan

    assert this.currentRowLocation != null;
    assert this.currentExecRow != null;

    // if destRow is a ValueRow then we must be careful to not replace
    // the DataValueDescriptors, but to set the value instead.
    if (destRow instanceof ValueRow) {
      // this loop can partially fill the destination DVD array.
      DataValueDescriptor[] destDvds = destRow.getRowArray();
      int len = destDvds.length;
      final int nCols = this.currentExecRow.nColumns();
      if (nCols < len) {
        len = nCols;
      }
      for (int i = 0; i < len; i++) {
        // if the destination dvd is missing (is null), then skip over it,
        // but should also be considering scanColumnList here
        if (destDvds[i] != null) {
          destDvds[i].setValue(this.currentExecRow.getColumn(i + 1));
        }
      }
    }
    else {
      // the destination row is a CompactExecRow
      final int nCols = destRow.nColumns();
      if (nCols >= this.currentExecRow.nColumns()) {
        // move whole row
        destRow.setRowArray(this.currentExecRow);
      }
      else if (nCols > 0) {
        // set all columns at once with setColumns
        destRow.setColumns(nCols, this.currentExecRow);
      }
      
    }
    if (this.addRegionAndKey) {
      assert !this.addKeyForSelectForUpdate;
      destRow.clearAllRegionAndKeyInfo();
      destRow.addRegionAndKey(this.regionName,
          this.currentRowLocation.getKeyCopy(),
          !this.gfContainer.isPartitioned());
    }
    if (this.addKeyForSelectForUpdate) {
      assert this.addRegionAndKey == false;
      destRow.clearAllRegionAndKeyInfo();
      // require only key
      destRow.addRegionAndKey(null, this.currentRowLocation.getKeyCopy(),
          !this.gfContainer.isPartitioned());
    }
  }

  public void setAddRegionAndKey(){
    this.addRegionAndKey = true;
  }

  @Override
  public ExecRow fetchRow(ExecRow destRow) throws StandardException {
    throw new AssertionError("should not be called");
  }

  /**
   * The same as fetch, except that the qualifiers passed to the openScan() will
   * not be applied. destRow will contain the current row even if it has been
   * changed and no longer qualifies.
   * 
   * @param destRow
   *          The row into which the value of the current position in the scan
   *          is to be stored.
   * @exception StandardException
   *              Standard exception policy.
   */
  public final void fetchWithoutQualify(ExecRow destRow)
      throws StandardException {
    fetch(destRow);
  }

  /**
   * Fetch the location of the current position in the scan. The destination
   * location is replaced with the location corresponding to the current
   * position in the scan. The destination location must be of the correct
   * actual type to accept a location from the underlying conglomerate location.
   * The results of a fetchLocation() performed on a scan after next() has
   * returned false are undefined. The results of a fetchLocation() performed on
   * a scan positioned on a deleted row are undefined, note that this can happen
   * even if next() has returned true (for instance the client can delete the
   * row, or if using read uncommitted another thread can delete the row after
   * the next() call but before the fetchLocation).
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public final RowLocation fetchLocation(RowLocation destRowLocation)
      throws StandardException {
    if (destRowLocation == null
        || !(destRowLocation instanceof GlobalExecRowLocation)) {
      assert this.currentRowLocation.getUnderlyingRegionEntry() !=
          DataValueFactory.DUMMY;
      return this.currentRowLocation;
    }
    else {
      GlobalExecRowLocation destloc = (GlobalExecRowLocation)destRowLocation;
      destloc.setFrom(this.currentRowLocation.getUnderlyingRegionEntry());
    }
    return destRowLocation;
  }

  /**
   * Fetch the (partial) row at the next position of the Scan. If there is a
   * valid next position in the scan then the value in the destRow storable row
   * is replaced with the value of the row at the current scan position. The
   * columns of the destRow row must be of the same type as the actual columns
   * in the underlying conglomerate. The resulting contents of destRow after a
   * fetchNext() which returns false is undefined. The result of calling
   * fetchNext(row) is exactly logically equivalent to making a next() call
   * followed by a fetch(row) call. This interface allows implementations to
   * optimize the 2 calls if possible.
   *
   * @param  destRow  The destRow row into which the value of the next position
   *                  in the scan is to be stored.
   *
   * @return  True if there is a next position in the scan, false if there
   *          isn't.
   *
   * @exception  StandardException  Standard exception policy.
   *
   * @see  ScanController#fetch
   * @see  RowUtil
   */
  @Override
  public final boolean fetchNext(ExecRow destRow) throws StandardException {
    if (next() && this.currentRowLocation != null) {
      fetch(destRow);
      return true;
    }
    else {
      return false;
    }
  }

  /**
   * Move to the next position in the scan. If this is the first call to next(),
   * the position is set to the first row. Returns false if there is not a next
   * row to move to. It is possible, but not guaranteed, that this method could
   * return true again, after returning false, if some other operation in the
   * same transaction appended a row to the underlying conglomerate.
   *
   * @return  True if there is a next position in the scan, false if there
   *          isn't.
   *
   * @exception  StandardException  Standard exception policy.
   */
  public final boolean next() throws StandardException {

    // check whether this query needs to be cancelled due to
    // timeout or low memory
    final Iterator<?> entryIterator = this.entryIterator;
    final PREntriesIterator<?> prEntryIterator = this.prEntryIterator;

    // if the scan has been closed, then return false
    if (entryIterator == null) {
      return false;
    }

    final boolean isGlobalScan = (this.openMode &
        GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX) != 0;
    final boolean isRowBuffer = this.gfContainer.isRowBuffer();
    final boolean hasQualifier = this.init_qualifier != null &&
        this.init_qualifier.length > 0;
    LocalRegion owner;
    RegionEntry entry = null;
    while (entryIterator.hasNext()) {
      ExecRow row = null;
      try {
        this.currentDataRegion = null;
        this.currentRowLocation = (RowLocation)entryIterator.next();
        this.statNumRowsVisited++;
        if (this.currentRowLocation != null) {
          owner = this.gfContainer.getRegion();
          if (prEntryIterator != null) {
            this.bucketId = prEntryIterator.getBucketId();
            if (this.bucketId >= 0) {
              owner = prEntryIterator.getHostedBucketRegion();
            }
          }
          if (GemFireXDUtils.TraceConglomRead) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
                "MemHeapScanController#next: current entry="
                    + this.currentRowLocation + " bucketId=" + this.bucketId
                    + ", isIterOnPR=" + this.gfContainer.isPartitioned()
                    + (owner != null ? ", owner=" + owner.getFullPath() : ""));
          }
          // a null owner in full scan will happen for remote entries
          if (owner == null && !isGlobalScan &&
              !Misc.getMemStore().isSnappyStore()) {
            // Not a real bucket :
            // Asif: what should we do in this case? Assign PR as the
            // owner?
            // [sumedh] cannot proceed with a null owner in case this entry
            // is on disk so just skip; in case of GII this gets handled
            // properly by use of GfxdIndexManager#lockForGII/lockForIndexGII
            // curly braces below will catch this by grepLogs
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
                "MemHeapScanController#next: entry=" + this.currentRowLocation
                    + " bucketId=" + this.bucketId + ", isOnPR="
                    + this.gfContainer.isPartitioned()
                    + " but the Bucket obtained is a ProxyBucketRegion {2}");
            continue;
          }
          if (this.readLockMode != null
              && this.currentRowLocation.getTXId() == null) {
            // write locks for transactions: first take an SH lock that
            // will be upgraded to EX_SH when the entry is qualified by
            // all layers
            // read locks for transactions: take a read lock that will be
            // released if not qualified by this or higher layers for
            // REPEATABLE_READ, while for READ_COMMITTED it is not taken now
            entry = this.currentRowLocation.getUnderlyingRegionEntry();
            if (!GemFireXDUtils.lockForRead(this.localTXState, this.lockPolicy,
                this.readLockMode, this.forUpdate, entry, this.gfContainer,
                owner, this.observer)) {
              this.statNumDeletedRowsVisited++;
              continue;
            }
            this.currentDataRegion = owner;
          }
          if (this.templateCompactExecRow != null) {
            // always fault-in for row buffer
            if (isRowBuffer) {
              row = RegionEntryUtils.fillRowFaultInOptimized(this.gfContainer,
                  owner, this.currentRowLocation, this.templateCompactExecRow)
                  ? templateCompactExecRow : null;
            } else {
              row = RegionEntryUtils.fillRowWithoutFaultIn(this.gfContainer,
                  owner, this.currentRowLocation.getRegionEntry(),
                  this.templateCompactExecRow) ? templateCompactExecRow : null;
            }
          } else {
            // always fault-in for row buffer
            if (isRowBuffer) {
              row = RegionEntryUtils.getRow(this.gfContainer,
                  owner, this.currentRowLocation.getRegionEntry(),
                  this.currentRowLocation.getTableInfo(this.gfContainer));
            } else {
              row = RegionEntryUtils.getRowWithoutFaultIn(this.gfContainer,
                  owner, this.currentRowLocation.getRegionEntry(),
                  this.currentRowLocation.getTableInfo(this.gfContainer));
            }
          }
          if (row != null) {
            this.currentExecRow = row;           
          }
          else {
            // this can happen with concurrent deletes
            this.statNumDeletedRowsVisited++;
            continue;
          }
        }
        else {
          if (GemFireXDUtils.TraceConglomRead) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
                "MemHeapScanController#next: current entry null for bucketId="
                    + this.bucketId + ", isIterOnPR="
                    + this.gfContainer.isPartitioned());
          }
          this.statNumDeletedRowsVisited++;
          continue;
        }
      } catch (EntryDestroyedException e) {
        continue;
      } catch (EntryNotFoundException e) {
        continue;
      } catch (GemFireXDRuntimeException e) {
        Throwable t = e.getCause();
        if (t instanceof EntryDestroyedException
            || t instanceof EntryNotFoundException) {
          continue;
        }
        else {
          throw e;
        }
      } finally {
        if (this.currentDataRegion != null && row == null) {
          GemFireXDUtils.unlockEntryAfterRead(this.txId, this.lockPolicy,
              this.readLockMode, entry, this.gfContainer,
              this.currentDataRegion);
          this.currentDataRegion = null;
        }
      }

      // Neeraj: if the row qualifies and the transaction is ON then take
      // the lock and re-qualify and if it re-qualifies then only
      // return true.
      // [sumedh] Now taking an SH lock so that no commit can happen
      // in between qualification and acquisition of EX_SH lock. Later
      // when the upper layers declare the row as a valid result then
      // upgrade the lock to EX_SH atomically.
      if (testBarrierBeforeFirstScan != null) {
        try {
          testBarrierBeforeFirstScan.countDown();
          testBarrierBeforeFirstScan.await();
        } catch (InterruptedException ie) {
          throw new InternalGemFireError(ie);
        }
      }

      boolean rowQualified = false;
      try {
        if (!hasQualifier
            || RowFormatter.qualifyRow(this.currentExecRow,
                this.byteArrayStore, this.init_qualifier)) {
          rowQualified = true;
          // fault-in the entry if filters are present
          if (hasQualifier && !isRowBuffer) {
            this.currentRowLocation.getValueOrOffHeapEntry(owner);
          }
          this.statNumRowsQualified++;
          if (this.currentDataRegion != null) {
            this.localTXState.addReadLockForScan(entry, this.readLockMode,
                owner, this.lockContext);
          }
          if (testLatchAfterFirstQualify != null
              && waitForLatch.get().intValue() > 0) {
            try {
              testLatchAfterFirstQualify.await(waitForLatch.get().intValue(),
                  TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
              throw new InternalGemFireError(ie);
            }
          }
          return true;
        }
      } finally {
        if (!rowQualified ) {
          try {
          if( this.currentDataRegion != null) {
             GemFireXDUtils.unlockEntryAfterRead(this.txId, this.lockPolicy,
              this.readLockMode, entry, this.gfContainer,
              this.currentDataRegion);
             this.currentDataRegion = null;
          }
          }finally {
            if(this.currentExecRow != null) {
             this.currentExecRow.releaseByteSource();
             this.currentExecRow = null;
            }
          }
        }
        else {
          if (this.isOffHeap) {
            final OffHeapResourceHolder offheapOwner = this.offheapOwner != null
                ? this.offheapOwner : this.tran;
            if (this.currentExecRow != null) {
              @Unretained final Object bs = this.currentExecRow.getByteSource();
              if (bs instanceof OffHeapByteSource) {
                offheapOwner.addByteSource((OffHeapByteSource)bs);
              }
              else {
                offheapOwner.addByteSource(null);
              }
            }
            else {
              offheapOwner.addByteSource(null);
            }
          }
        }
      }
    }
    // check for JVM going down (#44944)
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    this.currentRowLocation = null;
    this.currentExecRow = null;
    this.currentDataRegion = null;
    this.bucketId = -1;
    if (entryIterator instanceof CloseableIterator) {
      ((CloseableIterator<?>)entryIterator).close();
    }

    return false;
  }

  public final boolean replace(DataValueDescriptor[] row,
      FormatableBitSet validColumns) throws StandardException {
    final RegionEntry regionEntry = this.currentRowLocation.getRegionEntry();

    assert regionEntry != null: "Replacing a null RegionEntry is not allowed!";
    boolean updated = true;

    try {
      this.gfContainer.replacePartialRow(regionEntry, validColumns, row,
          this.bucketId, this.tran, this.txState,
          this.tran.getLanguageConnectionContext());
    } catch (EntryDestroyedException ede) {
      // can occur as the row may be concurrently deleted
      updated = false;
    } catch (EntryNotFoundException enfe) {
      // Fix for Bug 40161. It is possible that by the time region.put is in
      // progress the entry is deleted & so the put is internally converted into
      // create. But since the new value is delta, which needs old value to be
      // meaningful, will not be there throwing Exception with message
      // "Cannot apply a delta without an existing value".
      updated = false;
    }finally {
      if(this.currentExecRow != null) {
        //this.currentExecRow.releaseByteSource();
      }
    }
    return updated;
  }

  private final void closeScan() throws StandardException {
    // If we are closed due to catching an error in the middle of init,
    // xact_manager may not be set yet.
    if (this.tran != null) {
      this.tran.closeMe(this);
      this.tran = null;
      if (this.localTXState != null) {
        if (this.lockContext != null) {
          // in proper close move any pending read locks to TXState assuming
          // they have qualified, else remove for update since for latter we
          // always ensure that lock has been upgraded to EX_SH if required
          if (this.forUpdate != 0) {
            this.localTXState.pendingReadLocksCleanup(this.lockPolicy,
                this.lockContext, this.lcc);
          }
          else {
            this.localTXState.pendingReadLocksToTXState(this.lockPolicy,
                this.lockContext, this.lcc);
          }
        }
        // reset batching flag (#43958)
        if (!this.restoreBatching) {
          this.localTXState.getProxy().remoteBatching(false);
          this.restoreBatching = true;
        }
      }
    }
    // help the garbage collector; also required since this scan may be
    // reopened/reinitialized
    this.activation = null;
    //TODO: Suranjan if the tx has been started for snapshot then clean it from hostedTxState too.
    // For smart connector use lcc attr to see if it needs to be committed later.
    if (this.localSnapshotTXState != null) {
      if (GemFireXDUtils.TraceQuery ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "MemHeapScanController::closeScan : " +
                " " + "Commiting snapshotTxStae. ");
      }
      this.localSnapshotTXState.commit(null);
    }

    this.lcc = null;
    this.txId = null;
    this.txState = null;

    this.localSnapshotTXState = null;
    this.readLockMode = null;
    this.localTXState = null;
    this.lockContext = null;
    this.init_qualifier = null;
    if (this.entryIterator instanceof CloseableIterator) {
      ((CloseableIterator) this.entryIterator).close();
    }
    this.entryIterator = null;
    this.prEntryIterator = null;
    this.currentRowLocation = null;
    this.currentExecRow = null;
    this.templateCompactExecRow = null;
    this.byteArrayStore = false;
    this.currentDataRegion = null;
    this.bucketId = -1;
    this.bucketSet = null;
    this.observer = null;
    this.hasNext = true;
    this.numRows = -1L;
    this.rowlength = -1;
  }

  public final void close() throws StandardException {
    if (!isScanClosed()) {
      closeScan();
    }
    this.offheapOwner = null;
  }

  public final ScanInfo getScanInfo() throws StandardException {
    return this;
  }

  public final boolean isTableLocked() {
    return false;
  }

  public final RowLocation newRowLocationTemplate() throws StandardException {
    return newRowLocationTemplate(this.gfContainer, this.openMode);
  }

  static RowLocation newRowLocationTemplate(GemFireContainer container,
      int openMode) throws StandardException {
    if ((openMode & GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX) > 0) {
      return new GlobalExecRowLocation();
    }
    else {
      return DataValueFactory.DUMMY;
    }
  }

  public final void reopenScan(DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation activation)
      throws StandardException {
    positionAtInitScan(startKeyValue,
        startSearchOperator,
        qualifier,
        stopKeyValue,
        stopSearchOperator,
        this.activation);
    this.hasNext=true;
  }

  public final void reopenScanByRowLocation(RowLocation startRowLocation,
      Qualifier[][] qualifier) throws StandardException {
  }

  final static boolean isForUpdate(int openMode) {
    // Is this an open for update or read? This will
    // be passed down to the raw store fetch methods, which allows
    // it to do the appropriate locking.
    return ((openMode & ContainerHandle.MODE_FORUPDATE) != 0);
  }

  public final long getEstimatedRowCount() throws StandardException {
    if (this.numRows == -1) {
      if (this.bucketSet != null) {
        this.numRows = this.gfContainer.getRegion().entryCountEstimate(this.txState,
            this.bucketSet, this.queryHDFS);
      }
      else {
        this.numRows = this.gfContainer.getEstimatedRowCount(0);
      }
    }
    return this.numRows;
  }

  public final void setEstimatedRowCount(long count) throws StandardException {
    this.numRows = count;
  }

  public final boolean closeForEndTransaction(boolean closeHeldScan)
      throws StandardException {
    if (!isScanClosed()) {
      if (closeHeldScan) {
        // close the scan as part of the commit/abort
        // this.scan_state = SCAN_DONE;
        closeScan();
        return true;
      }
      else {
        //this.gfContainer = null;
        // allow the scan to continue after the commit.
        // locks and latches will be released as part of the commit, so
        // no need to release them by hand.
        /*
        if (this.scan_state == SCAN_INPROGRESS) {
          this.scan_state = SCAN_HOLD_INPROGRESS;
        }
        else if (this.scan_state == SCAN_INIT) {
          this.scan_state = SCAN_HOLD_INIT;
        }
        */
      }
    }
    return false;
  }

  

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScanKeyGroupID() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  /**
   * Insert all rows that qualify for the current scan into the input Hash
   * table.
   *
   * <p>This routine scans executes the entire scan as described in the openScan
   * call. For every qualifying unique row value an entry is placed into the
   * HashTable. For unique row values the entry in the Hashtable has a key value
   * of the object stored in row[key_column_number], and the value of the data
   * is row. For row values with duplicates, the key value is also
   * row[key_column_number], but the value of the data is a Vector of rows. The
   * caller will have to call "instanceof" on the data value object if
   * duplicates are expected, to determine if the data value of the Hashtable
   * entry is a row or is a Vector of rows.
   *
   * <p>Note, that for this routine to work efficiently the caller must ensure
   * that the object in row[key_column_number] implements the hashCode and
   * equals method as appropriate for it's datatype.
   *
   * <p>It is expected that this call will be the first and only call made in an
   * openscan. Qualifiers and stop position of the openscan are applied just as
   * in a normal scan. This call is logically equivalent to the caller
   * performing the following: import java.util.Hashtable; hash_table = new
   * Hashtable(); while (next()) { row = create_new_row(); fetch(row); if
   * ((duplicate_value = hash_table.put(row[key_column_number], row)) != null) {
   * Vector row_vec; // inserted a duplicate if ((duplicate_value instanceof
   * vector)) { row_vec = (Vector) duplicate_value; } else { // allocate vector
   * to hold duplicates row_vec = new Vector(2); // insert original row into
   * vector row_vec.addElement(duplicate_value); // put the vector as the data
   * rather than the row hash_table.put(row[key_column_number], row_vec); } //
   * insert new row into vector row_vec.addElement(row); } }
   *
   * <p>The columns of the row will be the standard columns returned as part of
   * a scan, as described by the validColumns - see openScan for description.
   * RESOLVE - is this ok? or should I hard code somehow the row to be the first
   * column and the row location?
   *
   * <p>No overflow to external storage is provided, so calling this routine on
   * a 1 gigabyte conglomerate will incur at least 1 gigabyte of memory
   * (probably failing with a java out of memory condition). If this routine
   * gets an out of memory condition, or if "max_rowcnt" is exceeded then then
   * the routine will give up, empty the Hashtable, and return "false."
   *
   * <p>On exit from this routine, whether the fetchSet() succeeded or not the
   * scan is complete, it is positioned just the same as if the scan had been
   * drained by calling "next()" until it returns false (ie. fetchNext() and
   * next() calls will return false). reopenScan() can be called to restart the
   * scan.
   *
   * <p>RESOLVE - until we get row counts what should we do for sizing the the
   * size, capasity, and load factor of the hash table. For now it is up to the
   * caller to create the Hashtable, Access does not reset any parameters.
   *
   * <p>RESOLVE - I am not sure if access should be in charge of allocating the
   * new row objects. I know that I can do this in the case of btree's, but I
   * don't think I can do this in heaps. Maybe this is solved by work to be done
   * on the sort interface.
   *
   * @param  max_rowcnt  The maximum number of rows to insert into the Hash
   *                     table. Pass in -1 if there is no maximum.
   * @param  key_column_numbers  The column numbers of the columns in the scan
   *                             result row to be the key to the Hashtable. "0"
   *                             is the first column in the scan result row
   *                             (which may be different than the first row in
   *                             the table of the scan).
   *
   * @exception  StandardException  Standard exception policy.
   */
  public final void fetchSet(long max_rowcnt, int[] key_column_numbers,
      BackingStoreHashtable hash_table) throws StandardException {

    // !!!:ezoerner:20080331 doesn't handle duplicates yet
    // ???:ezoerner:20081219 seems to be ignoring key_column_numbers?    
    // @todo
    // !!!:ezoerner:20081219 BackingStoreHashtables need to be modified
    // to handle ExecRows with bytearrays, so that it doesn't call fetch with
    // DVD[]
    if (max_rowcnt == 0L) {
      return;
    }
    assert this.entryIterator != null;
    /*
    if (this.entryIterator == null) {
      this.entryIterator = this.gfContainer.getEntrySetIterator();
    }
     */

    // sets this.currentEntry and
    // this.currentExecRow if returns true
    this.hasNext = next();

    if (!hasNext) {
      return;
    }

    assert this.currentExecRow != null;
    assert this.currentRowLocation != null;

    if (this.rowlength < 0) {
      this.rowlength = this.currentExecRow.nColumns();
    }

    ExecRow row = this.gfContainer.newTemplateRow();
    fetch(row);
    RegionAndKey rak = null;
    final boolean isReplicated = !this.gfContainer.isPartitioned();
    // !!!:ezoerner:20081219 for now, accomodating the BackingStoreHashtable
    // with DataValueDescriptor[]; later, figure out if we can prevent
    // deserialization


    if (this.addRegionAndKey) {
      // Misc.getCacheLogWriter().info(
      // "KN: MHSC.fetchSet making new rak: " + rak + " to put in hash table"
      // + hash_table + " and row: " + row.getRowArray());
      rak = new RegionAndKey(regionName,
          this.currentRowLocation.getKeyCopy(), isReplicated);
    }
    if (this.addKeyForSelectForUpdate) {
      assert this.addRegionAndKey == false;
      rak = new RegionAndKey(null, this.currentRowLocation.getKeyCopy(),
          isReplicated);
    }

    hash_table.putRow(false, row.getRowArray(), rak);

    long count = 1L; // already did one row

    if (max_rowcnt == -1) {
      max_rowcnt = Long.MAX_VALUE;
    }

    while (count++ < max_rowcnt) {
      row = this.gfContainer.newTemplateRow();
      this.hasNext = fetchNext(row);
       
      
      if (!this.hasNext) {
        break;
      }
      // !!!:ezoerner:20081219 for now, accomodating the BackingStoreHashtable
      // with DataValueDescriptor[]; later, figure out if we can prevent
      // deserialization
      // rentry = getRegionEntry();
      if (this.addRegionAndKey) {
        assert this.addKeyForSelectForUpdate == false;
        rak = null;
        rak = new RegionAndKey(regionName,
            this.currentRowLocation.getKeyCopy(), isReplicated);
      }
      else if (this.addKeyForSelectForUpdate) {
        assert this.addRegionAndKey == false;
        rak = new RegionAndKey(null, this.currentRowLocation.getKeyCopy(),
            isReplicated);
      }
      hash_table.putRow(false, row.getRowArray(), rak);      
    }

    if ((count == max_rowcnt) && this.hasNext) {

      // max row count has been exceeded
      return; // according to GenericScanController implementation, we just
              // return...
    }

    return;
  }

  public final void savePosition(Conglomerate conglom, Page page)
      throws StandardException {
  }

  /**
   * Like fetch, this can be fetching the prefix of rows if the rows passed in
   * are shorter than a complete row.
   *
   * If row_array[0] is a ValueRow, then be careful to setValue on the
   * existing DataValueDescriptors instead of replacing them with new ones.
   * This is necessary for index creation.
   */
  public final int fetchNextGroup(ExecRow[] row_array,
      RowLocation[] rowloc_array, Object[] indexKeys, int[] nodeVersions,
      int[] scanKeyGroupID, LocalRegion[] dataRegions) throws StandardException {

    if (!this.hasNext) {
      return 0;
    }

    int i = 0;
    final boolean setRowLocation = (rowloc_array != null);

    assert row_array.length > 0 && row_array[0] != null;

    final int max_rowcnt = row_array.length;
    while (i < max_rowcnt) {
      ExecRow row;
      // if possible, reuse the rows from row_array
      row = row_array[i];
      if (row == null) {
        row = row_array[0].getNewNullRow();
      }

      // fetch directly in the row from row_array if one was provided
      this.hasNext = fetchNext(row);
      if (!this.hasNext) {
        break;
      }

      // rdubey : set the row location for loading the index.
      if (setRowLocation) {
        rowloc_array[i] = fetchLocation(rowloc_array[i]);
      }
      if (dataRegions != null) {
        dataRegions[i] = this.currentDataRegion;
      }

      // if we created the row, then set it in the row_array
      if (row_array[i] == null) {
        row_array[i] = row;
      }
      ++i;
    }
    // fix the row location array here
    return i;
  }

  /**
   * Return all information gathered about the scan.
   * <p>
   * This routine returns a list of properties which contains all information
   * gathered about the scan.  If a Property is passed in, then that property
   * list is appeneded to, otherwise a new property object is created and
   * returned.
   * <p>
   * Not all scans may support all properties, if the property is not 
   * supported then it will not be returned.  The following is a list of
   * properties that may be returned. These names have been internationalized,
   * the names shown here are the old, non-internationalized names:
   *
   *     scanType
   *         - type of the scan being performed:
   *           btree
   *           heap
   *           sort
   *     numPagesVisited
   *         - the number of pages visited during the scan.  For btree scans
   *           this number only includes the leaf pages visited.  
   *     numDeletedRowsVisited
   *         - the number of deleted rows visited during the scan.  This
   *           number includes only those rows marked deleted.
   *     numRowsVisited
   *         - the number of rows visited during the scan.  This number 
   *           includes all rows, including: those marked deleted, those
   *           that don't meet qualification, ...
   *     numRowsQualified
   *         - the number of rows which met the qualification.
   *     treeHeight (btree's only)
   *         - for btree's the height of the tree.  A tree with one page
   *           has a height of 1.  Total number of pages visited in a btree
   *           scan is (treeHeight - 1 + numPagesVisited).
   *     numColumnsFetched
   *         - the number of columns Fetched - partial scans will result
   *           in fetching less columns than the total number in the scan.
   *     columnsFetchedBitSet
   *         - The BitSet.toString() method called on the validColumns arg.
   *           to the scan, unless validColumns was set to null, and in that
   *           case we will return "all".
   *     NOTE - this list will be expanded as more information about the scan
   *            is gathered and returned.
   *
   * @param prop   Property list to fill in.
   *
   * @exception  StandardException  Standard exception policy.
   */
  public final Properties getAllScanInfo(Properties prop)
      throws StandardException {
    if (prop == null) {
      prop = new Properties();
    }
    prop.setProperty(MessageService.getTextMessage(SQLState.STORE_RTS_SCAN_TYPE),
        MessageService.getTextMessage(SQLState.STORE_RTS_HEAP));
    /*
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_PAGES_VISITED),
        Integer.toString(stat_numpages_visited));
    */
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_ROWS_VISITED),
        Integer.toString(statNumRowsVisited));
    prop.setProperty(MessageService
        .getTextMessage(SQLState.STORE_RTS_NUM_DELETED_ROWS_VISITED), Integer
        .toString(statNumDeletedRowsVisited));
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_ROWS_QUALIFIED),
        Integer.toString(statNumRowsQualified));
    if (this.gfContainer.isByteArrayStore()) {
      prop.setProperty(MessageService
          .getTextMessage(SQLState.STORE_RTS_NUM_COLUMNS_FETCHED), Integer
          .toString(statValidColumns != null ? statValidColumns.getNumBitsSet()
              : this.gfContainer.getNumColumns()));
    }
    prop.setProperty(
        MessageService
            .getTextMessage(SQLState.STORE_RTS_COLUMNS_FETCHED_BIT_SET),
        (statValidColumns == null ? MessageService
            .getTextMessage(SQLState.STORE_RTS_ALL) : statValidColumns
            .toString()));
    return prop;
  }

  // -------------------- unimplemented and unexpected method invocations below

  /**
   * Fetch the next N rows from the table.
   * <p>
   * The client allocates an array of N rows and passes it into the
   * fetchNextSet() call.  The client must at least allocate a row and
   * set row_array[0] to this row.  The client can optionally either leave
   * the rest of array entries null, or allocate rows to the slots.
   * If access finds an entry to be null, and wants to read a row into
   * it, it will allocate a row to the slot.  Once fetchNextGroup() returns
   * "ownership" of the row passes back to the client, access will not 
   * keep references to the allocated row.  Expected usage is that 
   * the client will specify an array of some number (say 10), and then 
   * only allocate a single row.  This way if only 1 row qualifies only
   * one row will have been allocated.
   * <p>
   * This routine does the equivalent of N 
   * fetchNext() calls, filling in each of the rows in the array.
   * Locking is performed exactly as if the N fetchNext() calls had
   * been made.
   * <p>
   * It is up to Access how many rows to return.  fetchNextGroup() will
   * return how many rows were filled in.  If fetchNextGroup() returns 0
   * then the scan is complete, (ie. the scan is in the same state as if
   * fetchNext() had returned false).  If the scan is not complete then
   * fetchNext() will return (1 <= row_count <= N).
   * <p>
   * The current position of the scan is undefined if fetchNextSet()
   * is used (ie. mixing fetch()/fetchNext() and fetchNextSet() calls
   * in a single scan does not work).  This is because a fetchNextSet()
   * request for 5 rows from a heap where the first 2 rows qualify, but
   * no other rows qualify will result in the scan being positioned at
   * the end of the table, while if 5 rows did qualify the scan will be
   * positioned on the 5th row.
   * <p>
   * If the row loc array is non-null then for each row fetched into
   * the row array, a corresponding fetchLocation() call will be made to
   * fill in the rowloc_array.  This array, like the row array can be 
   * initialized with only one non-null RowLocation and access will 
   * allocate the rest on demand.
   * <p>
   * Qualifiers, start and stop positioning of the openscan are applied
   * just as in a normal scan. 
   * <p>
   * The columns of the row will be the standard columns returned as
   * part of a scan, as described by the validColumns - see openScan for
   * description.
   * <p>
   * Expected usage:
   *
   * // allocate an array of 5 empty rows
   * DataValueDescriptor[][] row_array = allocate_row_array(5);
   * int row_cnt = 0;
   *
   * scan = openScan();
   *
   * while ((row_cnt = scan.fetchNextSet(row_array, null) != 0)
   * {
   *     // I got "row_cnt" rows from the scan.  These rows will be
   *     // found in row_array[0] through row_array[row_cnt - 1]
   * }
   *
   * <p>
   * @return The number of qualifying rows found and copied into the 
   *         provided array of rows.  If 0 then the scan is complete, 
   *         otherwise the return value will be: 
   *         1 <= row_count <= row_array.length
   *
   * @param row_array         The array of rows to copy rows into.  
   *                          row_array[].length must >= 1.   The first entry
   *                          must be non-null destination rows, other entries
   *                          may be null and will be allocated by access
   *                          if needed.
   *
   * @param rowloc_array      If non-null, the array of row locations to 
   *                          copy into.  If null, no row locations are
   *                          retrieved.
   *
   * @exception  StandardException  Standard exception policy.
   **/
  public int fetchNextGroup(DataValueDescriptor[][] row_array,
      RowLocation[] rowloc_array) throws StandardException {
    throw StandardException.newException(SQLState.HEAP_UNIMPLEMENTED_FEATURE);
  }

  public int fetchNextGroup(DataValueDescriptor[][] row_array,
      RowLocation[] oldrowloc_array, RowLocation[] newrowloc_array)
      throws StandardException {
    throw (StandardException.newException(SQLState.HEAP_UNIMPLEMENTED_FEATURE));
  }

  /**
   * The same as fetch, except that the qualifiers passed to the openScan() will
   * not be applied. destRow will contain the current row even if it has been
   * changed and no longer qualifies.
   * 
   * @param destRow
   *          The row into which the value of the current position in the scan
   *          is to be stored.
   * @exception StandardException
   *              Standard exception policy.
   */
  public void fetchWithoutQualify(DataValueDescriptor[] destRow)
      throws StandardException {
    throw (StandardException.newException(SQLState.HEAP_UNIMPLEMENTED_FEATURE));
  }

  public final boolean fetchNext(DataValueDescriptor[] destRow)
      throws StandardException {
    // invoked only by ConsistencyChecker so no need to be terribly efficient
    final ValueRow row = new ValueRow(destRow);
    return fetchNext(row);
  }

  public final boolean positionAtRowLocation(RowLocation rl)
      throws StandardException {
    return false;
  }

  public void didNotQualify() throws StandardException {
    throw new AssertionError("not expected to be called");
  }

  /**
   * Returns true if the current position of the scan is at a deleted row. This
   * case can come about if the current scan or another scan on the same table
   * in the same transaction deleted the row after the next() call which
   * positioned the scan at this row.
   * 
   * The results of a fetch() performed on a scan positioned on a deleted row
   * are undefined.
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  public final boolean isCurrentPositionDeleted() throws StandardException {
    return false;
  }

  public boolean isHeldAfterCommit() throws StandardException {
    throw new AssertionError("not expected to be called");
  }

  public void fetch(DataValueDescriptor[] destRow) throws StandardException {
    throw new AssertionError("not expected to be called");
  }

  @Override
  public final RowLocation getCurrentRowLocation() {
    return this.currentRowLocation;
  }

  @Override
  public final void upgradeCurrentRowLocationLockToWrite()
      throws StandardException {
    if (this.currentRowLocation != null && this.currentDataRegion != null) {
      final RegionEntry entry = this.currentRowLocation
          .getUnderlyingRegionEntry();
      final TXStateProxy txProxy = this.localTXState.getProxy();
      if (this.observer != null) {
        this.observer.lockingRowForTX(txProxy, this.gfContainer, entry, true);
      }
      try {
        // upgrade the lock since the entry has been qualified
        txProxy.lockEntry(entry, entry.getKey(), GemFireXDUtils
            .getRoutingObject(this.currentRowLocation.getBucketID()),
            this.gfContainer.getRegion(), this.currentDataRegion, true,
            TXEntryState.getLockForUpdateOp());
      } finally {
        // now release the SH lock after the atomic lock upgrade, or as
        // cleanup in case of lock upgrade failure
        GemFireXDUtils.releaseLockForReadOnPreviousEntry(entry,
            this.localTXState, this.txId, this.lockPolicy, this.readLockMode,
            this.gfContainer, this.currentDataRegion, this.lockContext);
      }
      this.currentDataRegion = null;
    }
  }

  @Override
  public final void releaseCurrentRowLocationReadLock()
      throws StandardException {
    if (this.currentRowLocation != null && this.currentDataRegion != null) {
      GemFireXDUtils.releaseLockForReadOnPreviousEntry(
          this.currentRowLocation.getUnderlyingRegionEntry(),
          this.localTXState, this.txId, this.lockPolicy, this.readLockMode,
          this.gfContainer, this.currentDataRegion, this.lockContext);
      this.currentDataRegion = null;
    }
  }
  
  public void setOffHeapOwner(OffHeapResourceHolder offHeapResourceHolder) {
    this.offheapOwner = offHeapResourceHolder;
  }
}
