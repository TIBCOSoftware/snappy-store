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

import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXEntryState;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.MemScanController;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
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
import com.pivotal.gemfirexd.internal.iapi.store.raw.Page;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;

/**
 * A {@link ScanController} for GFXD indexes namely {@link SortedMap2Index},
 * {@link GlobalHashIndex}, {@link Hash1Index}.
 * 
 * @author yjing
 * @author swale
 */
public abstract class MemIndexScanController implements MemScanController,
    ScanInfo, RowCountable {

  public static final int MAX = 7; // fetchMax-search

  protected final OpenMemIndex openConglom;

  protected GemFireXDQueryObserver observer;

  /** Control the bulk and Max fetch */
  protected boolean hasNext = true;

  /**
   * A list of columns required by client
   * 
   * @todo Do we need the implementation of accessing the columns based on this
   *       variable?
   **/
  protected FormatableBitSet init_scanColumnList;

  protected DataValueDescriptor[] init_startKeyValue;

  protected int init_startSearchOperator;

  protected Qualifier[][] init_qualifier;

  protected DataValueDescriptor[] init_stopKeyValue;

  protected int init_stopSearchOperator;

  protected long estimatedRowCount = -1L;

  protected int openMode = -1;

  /**
   * non-zero (ExclusiveSharedSynchronized.FOR_UPDATE) if this scan is for
   * update else zero
   */
  protected int forUpdate;

  protected boolean forReadOnly;

  protected Activation activation;

  protected GemFireContainer baseContainer;

  // to check for JVM going down (#44944)
  protected CancelCriterion cc;

  /** store the current RowLocation value */
  protected RowLocation currentRowLocation;

  protected LanguageConnectionContext lcc;

  protected GemFireTransaction tran;

  protected TXStateInterface txState;

  protected TXId txId;

  protected LockingPolicy lockPolicy;

  protected LockMode readLockMode;

  protected TXState localTXState;

  protected LocalRegion currentDataRegion;

  protected Object lockContext;

  protected boolean restoreBatching = true;

  protected int[] statNumRowsVisited;

  protected int statNumRowsQualified;

  protected int statNumDeletedRowsVisited;

  protected FormatableBitSet statValidColumns;
  
  protected boolean queryHDFS = false;

  public MemIndexScanController() {
    this.openConglom = new OpenMemIndex();
  }

  public final void init(GemFireTransaction tran, MemConglomerate conglomerate,
      int openMode, int lockLevel,
      com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy locking,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation act)
      throws StandardException {
    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex("Opening MemIndexScanController for index "
          + "container=%s, in mode=0x%s with scanColumnList=%s startKey=%s "
          + "startOp=%s stopKey=%s stopOp=%s qualifier=%s",
          conglomerate.getGemFireContainer(), Integer.toHexString(openMode),
          scanColumnList, ArrayUtils.objectString(startKeyValue),
          startSearchOperator, ArrayUtils.objectString(stopKeyValue),
          stopSearchOperator, ArrayUtils.objectString(qualifier));
    }

    this.openMode = openMode;
    this.openConglom.init(tran, (MemIndex)conglomerate, openMode, lockLevel,
        locking);
    this.init_scanColumnList = scanColumnList;

    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.scanControllerOpened(this, conglomerate);
    }

    positionAtInitScan(startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator, act);
  }

  public final boolean isScanClosed() {
    return this.openConglom.isClosed();
  }

  final private void initializeSearchCondition(
      DataValueDescriptor[] startKeyValue, int startSearchOperator,
      Qualifier[][] qualifier, DataValueDescriptor[] stopKeyValue,
      int stopSearchOperator) {
    if (RowUtil.isRowEmpty(startKeyValue)) {
      this.init_startKeyValue = null;
    }
    else {
      this.init_startKeyValue = startKeyValue;
    }

    this.init_startSearchOperator = startSearchOperator;

    if ((qualifier != null) && (qualifier.length == 0)) {
      qualifier = null;
    }

    this.init_qualifier = qualifier;

    if (RowUtil.isRowEmpty(stopKeyValue)) {
      this.init_stopKeyValue = null;
    }
    else {
      this.init_stopKeyValue = stopKeyValue;
    }
    this.init_stopSearchOperator = stopSearchOperator;
  }

  /**
   * @param startKeyValue
   *          An indexable row which holds a (partial) key value which, in
   *          combination with the startSearchOperator, defines the starting
   *          position of the scan. If null, the starting position of the scan
   *          is the first row of the conglomerate. The startKeyValue must only
   *          reference columns included in the scanColumnList.
   * @param startSearchOperator
   *          an operator which defines how the startKeyValue is to be searched
   *          for. If startSearchOperation is ScanController.GE, the scan starts
   *          on the first row which is greater than or equal to the
   *          startKeyValue. If startSearchOperation is ScanController.GT, the
   *          scan starts on the first row whose key is greater than
   *          startKeyValue. The startSearchOperation parameter is ignored if
   *          the startKeyValue parameter is null.
   * @param stopKeyValue
   *          An indexable row which holds a (partial) key value which, in
   *          combination with the stopSearchOperator, defines the ending
   *          position of the scan. If null, the ending position of the scan is
   *          the last row of the conglomerate. The stopKeyValue must only
   *          reference columns included in the scanColumnList.
   * @param stopSearchOperator
   *          an operator which defines how the stopKeyValue is used to
   *          determine the scan stopping position. If stopSearchOperation is
   *          ScanController.GE, the scan stops just before the first row which
   *          is greater than or equal to the stopKeyValue. If
   *          stopSearchOperation is ScanController.GT, the scan stops just
   *          before the first row whose key is greater than stopKeyValue. The
   *          stopSearchOperation parameter is ignored if the stopKeyValue
   *          parameter is null.
   */
  protected final void positionAtInitScan(DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation act)
      throws StandardException {

    this.activation = act;
    this.baseContainer = this.openConglom.getBaseContainer();

    assert this.baseContainer != null: "GemFire baseContainer cannot be null";

    this.cc = this.baseContainer.getRegion().getCancelCriterion();

    if (this.baseContainer.isApplicationTableOrGlobalIndex()) {
      this.observer = GemFireXDQueryObserverHolder.getInstance();
    }
    this.tran = this.openConglom.getTransaction();
    this.lcc = this.tran.getLanguageConnectionContext();

    if (this.lcc != null && this.lcc.getRunTimeStatisticsMode()) {
      this.statNumRowsVisited = new int[] { 0 };
      this.statValidColumns = this.init_scanColumnList != null
          ? this.init_scanColumnList.clone() : null;
    }
    else {
      this.statNumRowsVisited = null;
      this.statValidColumns = null;
    }
    this.statNumDeletedRowsVisited = 0;
    this.statNumRowsQualified = 0;

    initializeSearchCondition(startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator);
    postProcessSearchCondition();

    if (GemFireXDUtils.TraceIndex) {
      // print the search conditions
      GfxdIndexManager.traceIndex("MemIndexScanController#positionAtInit: "
          + "startKey=%s, stopKey=%s", startKeyValue, stopKeyValue);
      if (this.init_qualifier != null) {
        StringBuilder qualStr = new StringBuilder();
        for (Qualifier[] qual : this.init_qualifier) {
          qualStr.append('(');
          if (qual != null) {
            for (Qualifier q : qual) {
              if (q != null) {
                qualStr.append("id=").append(q.getColumnId()).append(" value=")
                    .append(q.getOrderable());
              }
              else {
                qualStr.append("null");
              }
            }
            qualStr.append(',');
          }
          else {
            qualStr.append("NULL");
          }
          qualStr.append(')');
        }
        GfxdIndexManager.traceIndex(
            "MemIndexScanController#positionAtInit: qualifier=%s", qualStr
                .toString());
      }
      else {
        GfxdIndexManager.traceIndex("MemIndexScanController#positionAtInit: "
            + "qualifier is null");
      }
    }

    this.forUpdate = (openMode & TransactionController.OPENMODE_FORUPDATE) != 0
        ? ExclusiveSharedSynchronizer.FOR_UPDATE : 0;
    this.forReadOnly = (this.openMode & GfxdConstants
        .SCAN_OPENMODE_FOR_READONLY_LOCK) != 0;
    this.txState = this.baseContainer.getActiveTXState(this.tran);
    final boolean restoreBatching;
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
      else if (this.forReadOnly) {
        this.readLockMode = this.lockPolicy.getReadOnlyLockMode();
        this.localTXState = this.txState.getTXStateForWrite();
        this.lockContext = null;
        restoreBatching = this.localTXState.getProxy().remoteBatching(true);
      }
      // no read locks for READ_COMMITTED
      else if (this.lockPolicy.zeroDurationReadLocks()) {
        this.readLockMode = null;
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
    }
    else {
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

    this.currentRowLocation = null;
    
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

    if (this.forUpdate != 0) {
      // UPDATE/DELETE always goes to HDFS, regardless of queryHDFS
      this.queryHDFS = true;
    }
  
    final LocalRegion region = this.baseContainer.getRegion();
    if ((region != null) && (region instanceof PartitionedRegion)) {
      //TODO: Suranjan: Check if we have to enable queryHdfs here also.
      ((PartitionedRegion) region).setQueryHDFS(this.queryHDFS);
    }

    initEnumerator();
  }

  protected final boolean containRowLocation(DataValueDescriptor[] searchKey) {
    SanityManager.ASSERT(searchKey != null);
    if (searchKey[searchKey.length - 1] instanceof RowLocation) {
      return true;
    }
    return false;
  }

  // there are two options: one to make a copy as below, or second is to move
  // the instanceof check inside IndexKeyComparator; in the majority of cases
  // latter is likely to be less efficient due to potential of large number
  // of such instanceof checks for every potential match in index;
  // there is a temporary object creation overhead here, but I think in the
  // average case this will be the more performant route
  protected final DataValueDescriptor[] removeRowLocation(
      DataValueDescriptor[] searchKey) {
    DataValueDescriptor[] newSearchKey = new DataValueDescriptor[searchKey.length - 1];
    for (int i = 0; i < searchKey.length - 1; ++i) {
      newSearchKey[i] = searchKey[i];
    }
    return newSearchKey;
  }

  protected void postProcessSearchCondition() throws StandardException {
  }

  //abstract void initEnumerator() throws StandardException;
  protected abstract void initEnumerator() throws StandardException;

  public final RowLocation fetchLocation(RowLocation destRowLocation)
      throws StandardException {
    if (this.currentRowLocation != null) {
      if (destRowLocation == null
          || !(destRowLocation instanceof GlobalExecRowLocation)) {
        return this.currentRowLocation;
      }
      GlobalExecRowLocation destloc = (GlobalExecRowLocation)destRowLocation;
      destloc.setFrom(this.currentRowLocation.getRegionEntry());
      return destloc;
    }
    return null;
  }

  public static Set<Integer> getLocalBucketSet(final GemFireContainer gfc,
      final LocalRegion region, final Activation act, final String indexType) {
    try {
      final FunctionContext fc;
      if (act != null && (fc = act.getFunctionContext()) != null) {
        @SuppressWarnings("unchecked")
        final Set<Integer> localBucketSet = ((InternalRegionFunctionContext)fc)
            .getLocalBucketSet(region);
        if (GemFireXDUtils.TraceIndex) {
          GfxdIndexManager.traceIndex("%s the baseContainer is %s "
              + "and the local data set is %s, function context is %s",
              indexType, region.getFullPath(), localBucketSet, fc);
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, indexType
              + " scanning index: " + gfc + ", local data set is "
              + localBucketSet + ", and the function context: " + fc);
        }
        return localBucketSet;
      }
      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("%s the " + "FunctionContext is null ",
            indexType);
      }
    } catch (CacheClosedException ex) {
      // can happen if GemFireXD is still booting
      return null;
    }
    return null;
  }

  protected final void checkCancelInProgress() {
    if (this.cc == null) {
      this.cc = Misc.getGemFireCache().getCancelCriterion();
    }
    this.cc.checkCancelInProgress(null);
  }

  public final void dumpIndex(String marker) {
    this.openConglom.getConglomerate().dumpIndex(marker);
  }

  public int sizeOfIndex() { 
     return 0;
  }

  public long getEstimatedRowCount() throws StandardException {
    return 0;
  }

  public void didNotQualify() throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public boolean doesCurrentPositionQualify() throws StandardException {
    return false;
  }

  public void fetchWithoutQualify(DataValueDescriptor[] destRow)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public boolean isCurrentPositionDeleted() throws StandardException {
    return false;
  }

  public boolean isHeldAfterCommit() throws StandardException {
    return false;
  }

  public boolean positionAtRowLocation(RowLocation rl) throws StandardException {
    return false;
  }

  public boolean replace(DataValueDescriptor[] row,
      FormatableBitSet validColumns) throws StandardException {
    return false;
  }

  public final void close() throws StandardException {
    // If we are closed due to catching an error in the middle of init,
    // xact_manager may not be set yet.
    if (this.tran != null) {
      this.tran.closeMe(this);
      this.openConglom.close();
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
      closeScan();
    }
    this.estimatedRowCount = -1L;
    this.baseContainer = null;
    this.cc = null;
    this.activation = null;
    this.lcc = null;
    this.txId = null;
    this.txState = null;
    this.localTXState = null;
    this.lockContext = null;
    this.hasNext = false;
    this.init_qualifier = null;
    this.init_scanColumnList = null;
    this.init_startKeyValue = null;
    this.init_stopKeyValue = null;
    this.currentRowLocation = null;
    this.currentDataRegion = null;
    this.observer = null;
  }

  protected abstract void closeScan();

  public boolean isKeyed() {
    return true;
  }

  public boolean isTableLocked() {
    return false;
  }

  public RowLocation newRowLocationTemplate() throws StandardException {
    throw new AssertionError("not expected to be called");
  }

  public void reopenScan(DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation activation)
      throws StandardException {

    positionAtInitScan(startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator, activation /*local data set*/);
  }

  public void reopenScanByRowLocation(RowLocation startRowLocation,
      Qualifier[][] qualifier) throws StandardException {
    throw new AssertionError("not expected to be called");
  }

  public void setEstimatedRowCount(long count) throws StandardException {
   this.estimatedRowCount=count;

  }
  public String getIndexTypeName() {
    if(this.openConglom != null) {
      if(this.openConglom.getConglomerate() != null) {
        return this.openConglom.getConglomerate().getIndexTypeName();
      }
      return "Empty " + this.openConglom;
    }
    return "Empty openConglom";
  }

  @Override
  public final boolean closeForEndTransaction(boolean closeHeldScan)
      throws StandardException {
    //this.open_conglom.closeContainerForEndTransaction(closeHeldScan);
    if (closeHeldScan) {
      // close the scan as part of the commit/abort
      close();
      return true;
    }
    else {
      return false;
    }
  }
  
  @Override
  public int getScanKeyGroupID() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  @Override
  public final ScanInfo getScanInfo() throws StandardException {
    return this;
  }

  @Override
  public final Properties getAllScanInfo(Properties prop)
      throws StandardException {
    if (prop == null) {
      prop = new Properties();
    }
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_SCAN_TYPE),
        getIndexTypeName());
    /*
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_PAGES_VISITED),
        Integer.toString(stat_numpages_visited));
    */
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_ROWS_VISITED),
        Integer.toString(getNumRowsVisited()));
    prop.setProperty(MessageService
        .getTextMessage(SQLState.STORE_RTS_NUM_DELETED_ROWS_VISITED), Integer
        .toString(statNumDeletedRowsVisited));
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_NUM_ROWS_QUALIFIED),
        Integer.toString(statNumRowsQualified));
    prop.setProperty(
        MessageService.getTextMessage(SQLState.STORE_RTS_TREE_HEIGHT),
        Integer.toString(this.openConglom.getConglomerate().getHeight()));
    prop.setProperty(MessageService
        .getTextMessage(SQLState.STORE_RTS_NUM_COLUMNS_FETCHED), Integer
        .toString(statValidColumns != null ? statValidColumns.getNumBitsSet()
            : this.openConglom.getRowForExportTemplate().length));
    prop.setProperty(
        MessageService
            .getTextMessage(SQLState.STORE_RTS_COLUMNS_FETCHED_BIT_SET),
        (statValidColumns == null ? MessageService
            .getTextMessage(SQLState.STORE_RTS_ALL) : statValidColumns
            .toString()));
    return prop;
  }

  @Override
  public void fetchSet(long max_rowcnt, int[] key_column_numbers,
      BackingStoreHashtable hash_table) throws StandardException {
    if (!hasNext) {
      return;
    }
    for (;;) {
      DataValueDescriptor[] row = this.openConglom.getRowForExport();
      hasNext = fetchNext(row);

      if (!hasNext) {
        break;
      }

      hash_table.putRow(false, row, null);
    }

    return;

  }

  @Override
  public void savePosition(Conglomerate conglom, Page page)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);

  }

  // invoked during full index scan for bulk FK checking by RIBulkChecker
  @Override
  public final int fetchNextGroup(DataValueDescriptor[][] row_array,
      RowLocation[] rowloc_array) throws StandardException {

    if (!hasNext) {
      return 0;
    }

    int row_idx = 0;
    final int rowCnt = row_array.length;
    while (row_idx < rowCnt) {
      if (row_array[row_idx] != null) {
        hasNext = fetchNext(row_array[row_idx]);
        if (!hasNext) {
          break;
        }
      }
      else {
        DataValueDescriptor[] row = this.openConglom.getRowForExport();
        hasNext = fetchNext(row);
        if (!hasNext) {
          break;
        }
        row_array[row_idx] = row;
      }
      if (rowloc_array != null) {
        rowloc_array[row_idx] = fetchLocation(rowloc_array[row_idx]);
      }
      row_idx++;
    }
    return row_idx;
  }

  @Override
  public final int fetchNextGroup(ExecRow[] row_array,
      RowLocation[] rowloc_array, Object[] indexKeys, int[] nodeVersions,
      int[] scanKeyGroupID, LocalRegion[] dataRegions) throws StandardException {

    assert dataRegions == null;

    if (!hasNext) {
      return 0;
    }

    final int max_rowcnt = row_array.length;
    int i = 0;
    while (i < max_rowcnt) {
      if (row_array[i] != null) {
        hasNext = fetchNext(row_array[i]);
        if (!hasNext) {
          break;
        }
      }
      else {
        ExecRow row = row_array[0].getNewNullRow();
        hasNext = fetchNext(row);
        if (!hasNext) {
          break;
        }
        row_array[i] = row;
      }
      if (rowloc_array != null) {
        rowloc_array[i] = fetchLocation(rowloc_array[i]);
      }
      if (indexKeys != null) {
        indexKeys[i] = getCurrentKey();
      }
      if (nodeVersions != null) {
        nodeVersions[i] = getCurrentNodeVersion();
      }
      if (scanKeyGroupID != null) {
        scanKeyGroupID[i] = getScanKeyGroupID();
      }

      // System.out.println("the accessed row:"+RowUtil.toString(row));
      i++;
    }

    return i;
  }

  @Override
  public final int fetchNextGroup(DataValueDescriptor[][] row_array,
      RowLocation[] oldrowloc_array, RowLocation[] newrowloc_array)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  @Override
  public void fetch(ExecRow destRow) throws StandardException {
    assert destRow instanceof ValueRow;
    fetch(destRow.getRowArray());
  }

  @Override
  public ExecRow fetchRow(ExecRow destRow) throws StandardException {
    throw new AssertionError("should not be called");
  }

  @Override
  public boolean fetchNext(ExecRow destRow) throws StandardException {
    assert destRow instanceof ValueRow;
    return fetchNext(destRow.getRowArray());
  }

  @Override
  public final RowLocation getCurrentRowLocation() {
    return this.currentRowLocation;
  }

  public Object getCurrentKey() {
    return null;
  }

  public int getCurrentNodeVersion() {
    return 0;
  }

  @Override
  public final void upgradeCurrentRowLocationLockToWrite()
      throws StandardException {
    if (this.currentRowLocation != null && this.currentDataRegion != null) {
      final RegionEntry entry = this.currentRowLocation
          .getUnderlyingRegionEntry();
      final TXStateProxy txProxy = this.localTXState.getProxy();
      if (this.observer != null) {
        this.observer.lockingRowForTX(txProxy, this.baseContainer, entry, true);
      }
      try {
        // upgrade the lock since the entry has been qualified
        txProxy.lockEntry(entry, entry.getKey(), GemFireXDUtils
            .getRoutingObject(this.currentRowLocation.getBucketID()),
            this.baseContainer.getRegion(), this.currentDataRegion, true,
            TXEntryState.getLockForUpdateOp());
      } finally {
        // now release the SH lock after the atomic lock upgrade, or as
        // cleanup in case of lock upgrade failure
        GemFireXDUtils.releaseLockForReadOnPreviousEntry(entry,
            this.localTXState, this.txId, this.lockPolicy, this.readLockMode,
            this.baseContainer, this.currentDataRegion, this.lockContext);
      }
      this.currentDataRegion = null;
    }
  }

  @Override
  public void releaseCurrentRowLocationReadLock() throws StandardException {
    if (this.currentRowLocation != null && this.currentDataRegion != null) {
      GemFireXDUtils.releaseLockForReadOnPreviousEntry(
          this.currentRowLocation.getUnderlyingRegionEntry(),
          this.localTXState, this.txId, this.lockPolicy, this.readLockMode,
          this.baseContainer, this.currentDataRegion, this.lockContext);
      this.currentDataRegion = null;
    }
  }

  public void fetchWithoutQualify(ExecRow destRow) throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public String getQualifiedIndexName() {
    return this.openConglom.getConglomerate().getGemFireContainer()
        .getQualifiedTableName();
  }

  public final int getNumRowsVisited() {
    return this.statNumRowsVisited != null ? this.statNumRowsVisited[0] : 0;
  }

  public final int getNumRowsQualified() {
    return this.statNumRowsQualified;
  }
}
