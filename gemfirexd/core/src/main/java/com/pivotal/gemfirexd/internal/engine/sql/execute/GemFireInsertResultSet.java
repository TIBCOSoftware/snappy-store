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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.VMIdAdvisor;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RowEncoder;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.InsertConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TriggerEvent;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * An instance of this class is generated to inserts rows into the base table.
 * No triggers or constraints are evaluated here.
 * 
 * @author rdubey
 * @author ymahajan
 */
public final class GemFireInsertResultSet extends AbstractGemFireResultSet {

  private final NoPutResultSet sourceResultSet;

  private final GeneratedMethod checkGM;

  private final GemFireContainer gfContainer;

  private final RowEncoder.PreProcessRow processor;

  private final boolean hasSerialAEQorWAN;

  private boolean isPreparedBatch;

  private boolean isPutDML;

  /**
   * @return the isPreparedBatch
   */
  public boolean isPreparedBatch() {
    return isPreparedBatch;
  }

  private boolean batchLockTaken;

  private ArrayList<Object> batchRows;

  private long batchSize;

  private final GfxdHeapThresholdListener thresholdListener;

  private int rowCount;

  private int numOpens;

  private int[] autoGeneratedColumns;

  private AutogenKeysResultSet autoGeneratedKeysResultSet;

  public GemFireInsertResultSet(final NoPutResultSet source,
      final GeneratedMethod checkGM, final Activation activation)
      throws StandardException {
    super(activation);
    this.sourceResultSet = source;
    this.checkGM = checkGM;
    final InsertConstantAction constants = (InsertConstantAction)activation
        .getConstantAction();
    long heapConglom = constants.getConglomerateId();
    final MemConglomerate conglom = this.tran
        .findExistingConglomerate(heapConglom);
    this.gfContainer = conglom.getGemFireContainer();
    RowEncoder encoder = this.gfContainer.getRowEncoder();
    this.processor = encoder != null
        ? encoder.getPreProcessorForRows(this.gfContainer) : null;
    this.hasSerialAEQorWAN = this.gfContainer.getRegion().isSerialWanEnabled();
    this.thresholdListener = Misc.getMemStore().thresholdListener();
    this.isPutDML = activation.isPutDML();

    final boolean isColumnTable = gfContainer.isRowBuffer();
    final LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
    if (isColumnTable && !Misc.routeQuery(lcc) && !lcc.isSnappyInternalConnection()) {
      throw StandardException.newException(SQLState.SNAPPY_OP_DISALLOWED_ON_COLUMN_TABLES);
    }
  }

  @Override
  public void open() throws StandardException {
    if (!this.isClosed) {
      return;
    }

    final long beginTime = statisticsTimingOn ? XPLAINUtil
        .recordTiming(openTime = -1) : 0;
    long restOfOpenTime = 0;
    final LocalRegion reg = this.gfContainer.getRegion();
    final GfxdIndexManager sqim = (GfxdIndexManager)(reg != null ? reg
        .getIndexUpdater() : null);
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();

    TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
    // TOOD: decide on autocommit or this flag: Discuss
    if (tx == null && (this.gfContainer.isRowBuffer() || reg.getCache().snapshotEnabledForTest())) {
      this.tran.getTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
      ((GemFireTransaction)lcc.getTransactionExecute()).setActiveTXState(TXManagerImpl.getCurrentTXState(), false);
      this.tran.setImplicitSnapshotTxStarted(true);
    }
    tx = this.gfContainer.getActiveTXState(this.tran);

    if (sqim != null && !this.isPutDML) {
      sqim.fireStatementTriggers(TriggerEvent.BEFORE_INSERT, lcc, this.tran, tx);
    }

    super.open();
    if (beginTime != 0) {
      nextTime = XPLAINUtil.nanoTime();
      openTime = nextTime - beginTime;
    }
    ExecRow row;
    boolean usingPutAll = this.isPreparedBatch;
    if (usingPutAll) {
      if (this.batchRows == null) {
        this.batchRows = new ArrayList<Object>();
        this.batchSize = 0;
      }
      while ((row = this.sourceResultSet.getNextRowCore()) != null) {
        // Evaluate any check constraints on the row
        evaluateCheckConstraints();

        // insert any auto-generated keys into the row holder
        handleAutoGeneratedColumns(row);

        // Get gemfire container and directly do an insert into the
        // container.
        handleBatchInserts(row);

      }
    }
    else if (this.sourceResultSet.getEstimatedRowCount() > 1) {
      handleMultipleInserts();
      usingPutAll = true;
    }
    else {
      while ((row = this.sourceResultSet.getNextRowCore()) != null) {

        // Evaluate any check constraints on the row
        evaluateCheckConstraints();

        // insert any auto-generated keys into the row holder
        handleAutoGeneratedColumns(row);

        DataValueDescriptor[] rowArray = row.getRowArray();
        if (this.processor != null) {
          rowArray = this.processor.preProcess(rowArray);
        }
        this.gfContainer.insertRow(rowArray, this.tran, tx, lcc,
            this.isPutDML);
        this.rowCount++;

      }
    }
    if (beginTime != 0) {
      restOfOpenTime = XPLAINUtil.nanoTime();
      nextTime = restOfOpenTime - nextTime;
    }
    // for tx inserts, the distribution will be done by handle* methods
    // Suranjan: This needs to be avoided for Parallel DBSync or Parallel WAN
    if (!usingPutAll && tx != null) {
      if (this.hasSerialAEQorWAN) {
        ParameterValueSet pvs = this.activation.getParameterValueSet();
        this.activation.distributeBulkOpToDBSynchronizer(
            this.gfContainer.getRegion(), pvs != null
                && pvs.getParameterCount() > 0, this.tran, lcc.isSkipListeners(),
            null);
      }
    }

    if (sqim != null && !this.isPutDML) {
      sqim.fireStatementTriggers(TriggerEvent.AFTER_INSERT, lcc, this.tran, tx);
    }
    if (beginTime != 0) {
      openTime += XPLAINUtil.recordTiming(restOfOpenTime);
    }
  }

  private void handleBatchInserts(final ExecRow row) throws StandardException {
    // if we reached the max batch size or eviction/criticalUp then flush
    if (this.batchSize > GemFireXDUtils.DML_MAX_CHUNK_SIZE
        || (this.thresholdListener != null && (this.thresholdListener
            .isEviction() || this.thresholdListener.isCritical()))) {
      flushBatch();
    }

    this.batchSize += addRow(this.batchRows, row);
    this.rowCount++;
  }

  private void handleMultipleInserts() throws StandardException {
    ExecRow row;
    ArrayList<Object> rows = new ArrayList<Object>();
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();
    final TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
    try {
      while ((row = this.sourceResultSet.getNextRowCore()) != null) {
        // Evaluate any check constraints on the row
        evaluateCheckConstraints();

        // insert any auto-generated keys into the row holder
        handleAutoGeneratedColumns(row);

        // if we reached the max batch size or eviction/criticalUp then flush
        if ((this.batchSize > GemFireXDUtils.DML_MAX_CHUNK_SIZE
            || (this.thresholdListener != null && (this.thresholdListener
            .isEviction() || this.thresholdListener.isCritical())))
            && rows.size() > 0) {
          // now always publish this as a separate batch event to the queue
          this.gfContainer.insertMultipleRows(rows, tx, lcc, false,
              this.isPutDML);
          if (this.hasSerialAEQorWAN) {
            this.activation.distributeBulkOpToDBSynchronizer(
                this.gfContainer.getRegion(), true, this.tran,
                lcc.isSkipListeners(), rows);
          }
          rows.clear();
          this.batchSize = 0;
        }
        this.batchSize += addRow(rows, row);
        this.sourceResultSet.releasePreviousByteSource();
        this.rowCount++;
      }
    } finally {
      if (rows.size() > 0) {
        // now always publish this as a separate batch event to the queue
        this.gfContainer
            .insertMultipleRows(rows, tx, lcc, false, this.isPutDML);
        if (this.hasSerialAEQorWAN) {
          this.activation.distributeBulkOpToDBSynchronizer(
              this.gfContainer.getRegion(), true, this.tran,
              lcc.isSkipListeners(), rows);
        }
      }
      this.batchSize = 0;
    }
  }

  private long addRow(final ArrayList<Object> rows, final ExecRow row)
      throws StandardException {
    // optimize for byte[] store
    if (this.gfContainer.isByteArrayStore()) {
      //Since we are obtaining raw row value, it is ok
      // to release the offheap byte source
      Object rawRow = row.getRawRowValue(false);
      if (rawRow instanceof DataValueDescriptor[]) {
        final DataValueDescriptor[] dvds = (DataValueDescriptor[])rawRow;
        rawRow = this.gfContainer.getCurrentRowFormatter()
            .generateRowData(dvds);
      }
      rows.add(rawRow);
      return AbstractCompactExecRow.getRawRowSize(rawRow);
    }
    else {
      DataValueDescriptor[] rowArray = row.getClone().getRowArray();
      if (this.processor != null) {
        rowArray = this.processor.preProcess(rowArray);
      }
      rows.add(rowArray);
      return row.estimateRowSize();
    }
  }

  @Override
  public final void flushBatch() throws StandardException {
    final ArrayList<Object> rows = this.batchRows;
    if (rows != null && rows.size() > 0) {
      final TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
      try {
        final Activation act = this.activation;
        // now always publish this as a separate batch event to the queue
        this.gfContainer.insertMultipleRows(rows, tx,
            act.getLanguageConnectionContext(), false, this.isPutDML);
        if (act.isQueryCancelled()) {
          act.checkCancellationFlag();
        }
        if (this.hasSerialAEQorWAN) {
          act.distributeBulkOpToDBSynchronizer(this.gfContainer.getRegion(),
              true, this.tran, lcc.isSkipListeners(), rows);
        }
      } finally {
        rows.clear();
        this.batchSize = 0;
      }
    }
  }
  
  @Override
  public final void closeBatch() throws StandardException {
    assert this.isPreparedBatch;
    //assert this.batchLockTaken;
    releaseLocks();
    if(this.tran != null) {
      this.tran.release();
    }
  }

  @Override
  protected void openCore() throws StandardException {
    // reset row count and batch size
    this.rowCount = 0;
    this.batchSize = 0;
    if (this.numOpens++ == 0) {
      this.sourceResultSet.openCore();
    }
    else {
      this.sourceResultSet.reopenCore();
    }
    this.isPreparedBatch = this.activation.isPreparedBatch();
    if (!this.isPreparedBatch || !this.batchLockTaken) {
      takeLocks();
    }
    // open the row holder for generated keys
    if (this.autoGeneratedKeysResultSet == null
        && this.activation.getAutoGeneratedKeysResultsetMode()) {
      final ExtraTableInfo tableInfo = this.gfContainer.getExtraTableInfo();
      int[] cols = this.activation.getAutoGeneratedKeysColumnIndexes();
      String[] colNames = this.activation.getAutoGeneratedKeysColumnNames();

      // check for any invalid column indexes/names provided by user
      if (cols != null) {
        for (int pos : cols) {
          if (tableInfo.getAutoGeneratedColumn(pos) == null) {
            throw StandardException.newException(
                SQLState.LANG_INVALID_AUTOGEN_COLUMN_POSITION,
                Integer.valueOf(pos), this.gfContainer.getQualifiedTableName());
          }
        }
      }
      else if (colNames != null) {
        ColumnDescriptor cd;
        cols = new int[colNames.length];
        for (int index = 0; index < colNames.length; ++index) {
          if ((cd = tableInfo.getAutoGeneratedColumn(colNames[index])) != null) {
            cols[index] = cd.getPosition();
          }
          else {
            throw StandardException.newException(
                SQLState.LANG_INVALID_AUTOGEN_COLUMN_NAME, colNames[index],
                this.gfContainer.getQualifiedTableName());
          }
        }
      }
      else {
        // nothing provided by user so return all auto-gen columns
        cols = tableInfo.getAutoGeneratedColumns();
      }
      if (cols != null && cols.length > 0) {
        Arrays.sort(cols);
        // set in activation to avoid dealing with names again
        this.activation.setAutoGeneratedKeysResultsetInfo(cols, null);
        this.autoGeneratedColumns = cols;
        final Properties props = new Properties();
        this.gfContainer.getContainerProperties(props);
        this.autoGeneratedKeysResultSet = new AutogenKeysResultSet(
            this.activation, cols, tableInfo);
      }
    }
  }

  /**
   * Run the check constraints against the current row. Raise an error if a
   * check constraint is violated.
   * 
   * @exception StandardException
   *              thrown on error
   */
  private void evaluateCheckConstraints() throws StandardException {
    if (this.checkGM != null) {
      // Evaluate the check constraints. The expression evaluation
      // will throw an exception if there is a violation, so there
      // is no need to check the result of the expression.
      this.checkGM.invoke(this.activation);
    }
  }

  @Override
  public void finishResultSet(final boolean cleanupOnError) throws StandardException {
    try {
      if (this.sourceResultSet != null) {
        this.sourceResultSet.close(cleanupOnError);
      }
      if (!this.isPreparedBatch) {
        releaseLocks();
      }
      this.numOpens = 0;
    } finally {
      if(this.tran != null) {
        this.tran.release();
      }
    }
  }
  
  private void takeLocks() throws StandardException {
    this.gfContainer.open(this.tran, ContainerHandle.MODE_READONLY);
    // also get the locks on the fk child tables
    openOrCloseFKContainers(this.gfContainer, this.tran, false, true);
    if (this.isPreparedBatch) {
      this.batchLockTaken = true;
    }
  }
  
  private void releaseLocks() throws StandardException {
    // release the locks on the fk child tables
    openOrCloseFKContainers(this.gfContainer, this.tran, true, true);
    this.gfContainer.closeForEndTransaction(this.tran, true);
    if (this.isPreparedBatch) {
      this.batchLockTaken = false;
    }
  }
  
  @Override
  public int modifiedRowCount() {
    return this.rowCount;
  }

  @Override
  public boolean returnsRows() {
    return false;
  }

  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(0);
    visitor.visit(this);
  }

  public DataValueDescriptor getNextUUIDValue(final int columnPosition)
      throws StandardException {
    final ExtraTableInfo tabInfo = this.gfContainer.getExtraTableInfo();
    final ColumnDescriptor cd = tabInfo.getRowFormatter().getColumnDescriptor(
        columnPosition - 1);
    final DataTypeDescriptor dtd = cd.getType();
    final DataValueDescriptor dvd = dtd.getNull();
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();
    // check if int or bigint
    try {
      final LocalRegion region = this.gfContainer.getRegion();
      final long incStart = cd.getAutoincStart();
      if (dtd.getTypeId().getTypeFormatId() == StoredFormatIds.LONGINT_TYPE_ID) {
        long uuid = region.newUUID(true);
        if (uuid < incStart) {
          // need to reset the underlying VMId and local unique ID
          uuid = region.resetUUID(incStart);
        }
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        if (observer != null) {
          final int[] pkColumns = tabInfo.getPrimaryKeyColumns();
          final boolean forRegionKey = (pkColumns != null
              && pkColumns.length > 0 && pkColumns[0] == columnPosition);
          uuid = observer.overrideUniqueID(uuid, forRegionKey);
        }
        dvd.setValue(uuid);
        lcc.setIdentityValue(uuid);
        return dvd;
      }
      else {
        int shortUUID = region.newShortUUID();
        if (shortUUID < incStart) {
          if (incStart < 0 || incStart > Integer.MAX_VALUE) {
            SanityManager
                .THROWASSERT("unexpected value for autoincrement start "
                    + incStart + " column " + cd);
          }
          // need to reset the underlying VMId and local unique ID
          shortUUID = region
              .resetShortUUID((int)(incStart & VMIdAdvisor.UINT_MASK));
        }
        dvd.setValue(shortUUID);
        lcc.setIdentityValue(shortUUID);
        return dvd;
      }
    } catch (IllegalStateException ise) { // check for overflow
      throw StandardException.newException(SQLState.LANG_AI_OVERFLOW, ise,
          this.gfContainer.getQualifiedTableName(), cd.getColumnName());
    }
  }

  public DataValueDescriptor getMaxIdentityValue(final int columnPosition)
      throws StandardException {
    final ColumnDescriptor cd = this.gfContainer.getCurrentRowFormatter()
        .getColumnDescriptor(columnPosition - 1);
    final DataTypeDescriptor dtd = cd.getType();
    final DataValueDescriptor dvd = dtd.getNull();
    GemFireStore store = Misc.getMemStore();
    LocalRegion identityRgn = store.getIdentityRegion();
    assert identityRgn != null;
    final String key = this.gfContainer.getUUID();

    // we don't want to lock out a new generator node (when current one fails)
    // in retrieval and put else it can cause a deadlock (new generator node
    // waiting on this node to initialize, and this node sends the generation
    // message to the new generator node itself which will keep waiting for it
    // to initialize)
    final long increment = cd.getAutoincInc();
    long result;
    while (true) {
      IdentityValueManager.GetIdentityValueMessage msg =
          new IdentityValueManager.GetIdentityValueMessage(identityRgn, key,
              cd.getAutoincStart(), increment, getLanguageConnectionContext());
      final Object res;
      try {
        res = msg.executeFunction();
      } catch (SQLException sqle) {
        throw Misc.wrapSQLException(sqle, sqle);
      }
      assert res != null: "Expected one result for targeted function "
          + "execution of GetIdentityValueMessage";
      result = ((Long)res).longValue();
      // now record the generated value checking if any new generator node has
      // read the value in the meantime
      if (IdentityValueManager.getInstance().setGeneratedValue(key, result,
          increment, msg.getTarget())) {
        break;
      }
    }
    if (dtd.getTypeId().getTypeFormatId() == StoredFormatIds.LONGINT_TYPE_ID) {
      dvd.setValue(result);
      lcc.setIdentityValue(result);
      return dvd;
    }
    else {
      // check for overflow
      if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
        dvd.setValue((int)result);
        lcc.setIdentityValue((int)result);
        return dvd;
      }
      else {
        throw StandardException.newException(SQLState.LANG_AI_OVERFLOW,
            this.gfContainer.getQualifiedTableName(), cd.getColumnName());
      }
    }
  }

  @Override
  public ResultSet getAutoGeneratedKeysResultset() {
    // the value in LCC must also have been set for clients to work correctly
    if (SanityManager.DEBUG) {
      if (this.autoGeneratedKeysResultSet != null) {
        SanityManager.ASSERT(this.activation.getLanguageConnectionContext()
            .getIdentityVal() != 0);
      }
    }
    return this.autoGeneratedKeysResultSet;
  }

  /**
   * @see ResultSet#hasAutoGeneratedKeysResultSet
   */
  @Override
  public boolean hasAutoGeneratedKeysResultSet() {
    return (this.autoGeneratedKeysResultSet != null);
  }

  private void handleAutoGeneratedColumns(final ExecRow row)
      throws StandardException {
    // insert any auto-generated keys into the row holder
    if (this.autoGeneratedKeysResultSet != null) {
      this.autoGeneratedKeysResultSet.insertRow(row, this.autoGeneratedColumns);
    }
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    long memory = 0L;
    if (this.autoGeneratedKeysResultSet != null) {
      memory += this.autoGeneratedColumns.length * 4
          + ClassSize.estimateArrayOverhead();
      memory += this.autoGeneratedKeysResultSet.estimateMemoryUsage();
    }
    if (this.batchRows != null) {
      memory += ClassSize.estimateArrayOverhead();
      for (Object row : this.batchRows) {
        if (row instanceof DataValueDescriptor[]) {
          memory += ClassSize.estimateArrayOverhead();
          for (DataValueDescriptor dvd : (DataValueDescriptor[])row) {
            memory += dvd.estimateMemoryUsage();
          }
        }
        else {
          memory += CachedDeserializableFactory.calcMemSize(row);
        }
      }
    }
    return memory;
  }
}
