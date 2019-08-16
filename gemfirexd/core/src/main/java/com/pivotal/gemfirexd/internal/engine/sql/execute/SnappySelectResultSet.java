/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.io.IOException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import com.gemstone.gemfire.internal.cache.TXState;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeExecutorMsg;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.GenericColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;

/**
 * Holds the resultSet obtained from lead node execution.
 */
public class SnappySelectResultSet
    extends AbstractGemFireResultSet implements NoPutResultSet {

  private ExecRow currentRow;
  protected Collection<?> resultHolderList;
  private Iterator<?> srhIterator;
  private SnappyResultHolder currentResultHolder;
  private SnappyResultHolder firstResultHolder;
  private boolean returnRows;

  public SnappySelectResultSet(Activation ac, boolean returnRows) {
    super(ac);
    this.returnRows = returnRows;
  }

  @Override
  public void markAsTopResultSet() {
  }

  @Override
  public void openCore() throws StandardException {
  }

  @Override
  public void reopenCore() throws StandardException {
  }

  @Override
  public ExecRow getNextRowCore() throws StandardException {
    return getNextRow();
  }

  @Override
  public int getPointOfAttachment() {
    return 0;
  }

  @Override
  public int getScanIsolationLevel() {
    return 0;
  }

  @Override
  public void setTargetResultSet(TargetResultSet trs) {
  }

  @Override
  public void setNeedsRowLocation(boolean needsRowLocation) {
  }

  @Override
  public double getEstimatedRowCount() {
    return 0;
  }

  @Override
  public int resultSetNumber() {
    return 0;
  }

  @Override
  public void setCurrentRow(ExecRow row) {

  }

  @Override
  public boolean requiresRelocking() {
    return false;
  }

  @Override
  public TXState initLocalTXState() {
    return null;
  }

  @Override
  public void upgradeReadLockToWrite(RowLocation rl, GemFireContainer container) throws StandardException {
  }

  @Override
  public void updateRowLocationPostRead() throws StandardException {
  }

  @Override
  public void filteredRowLocationPostRead(TXState localTXState) throws StandardException {
  }

  @Override
  public boolean isForUpdate() {
    return false;
  }

  @Override
  public boolean canUpdateInPlace() {
    return false;
  }

  @Override
  public void updateRow(ExecRow row) throws StandardException {

  }

  @Override
  public void deleteRowDirectly() throws StandardException {

  }

  @Override
  public void markRowAsDeleted() throws StandardException {
  }

  @Override
  public void positionScanAtRowLocation(RowLocation rLoc) throws StandardException {
  }

  @Override
  public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys) throws StandardException {
    throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
      " Currently this method is not implemented or overridden for class "
        + this.getClass().getSimpleName());
  }

  @Override
  public void releasePreviousByteSource() {
  }

  @Override
  public void setMaxSortingLimit(long limit) {
  }

  @Override
  public RowLocation fetch(RowLocation loc, ExecRow destRow,
      FormatableBitSet validColumns, boolean faultIn,
      GemFireContainer container) throws StandardException {
    throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
      " Currently this method is not implemented or overridden for class "
        + this.getClass().getSimpleName());
  }

  public ExecRow getNextRow() throws StandardException {
    try {
      nextExecRow();
      this.setCurrentRow(this.currentRow);
      return this.currentRow;
    } catch (Exception ex) {
      Misc.checkIfCacheClosing(ex);
      throw Misc.processFunctionException("SnappySelectResultSet:getNextRow ", ex, null, null);
    }
  }

  private ExecRow nextExecRow() throws IOException,
      ClassNotFoundException, StandardException {
    if (this.currentResultHolder == null) {
      return null;
    }
    this.currentRow = this.currentResultHolder.getNextRow();
    if (this.currentRow == null) {
      if (srhIterator.hasNext()) {
        try {
          this.currentResultHolder = (SnappyResultHolder)srhIterator.next();
        } catch (Exception ex) {
          Misc.checkIfCacheClosing(ex);
          throw Misc.processFunctionException("SnappySelectResultSet:next",
                  ex, null, null);
        }
        // set the metadata which is sent in only the first resultHolder
        if (this.currentResultHolder != null) {
          this.currentResultHolder.setMetadata(this.firstResultHolder);
        }
        return nextExecRow();
      } else {
        this.currentRow = null;
      }
    }
    return this.currentRow;
  }

  public ResultDescription makeResultDescription() {
    assert resultHolderList != null : "expected non null result holder";
    String[] colNames = firstResultHolder.getColumnNames();
    int[] colTypes = firstResultHolder.getColumnTypes();
    String[] tableNames = firstResultHolder.getTableNames();
    DataTypeDescriptor[] dtds = firstResultHolder.getDtds();
    if (colNames == null || colTypes == null || !(colNames.length > 0) || !(colTypes.length > 0)) {
        throw new IllegalStateException("colnames and colTypes are required");
    }

    GenericResultDescription resultDescription = new GenericResultDescription(
        new ResultColumnDescriptor[colTypes.length], null);

    for (int i = 0; i < colNames.length; i++) {
      String tableName = tableNames[i];
      String schema = "";
      String table = "";
      if (!tableName.isEmpty()) {
        int dotIndex = tableName.indexOf('.');
        schema = tableName.substring(0, dotIndex);
        table = tableName.substring(dotIndex + 1);
      }
      ResultColumnDescriptor rcd = new GenericColumnDescriptor(
          colNames[i], schema, table, i + 1, dtds[i], false, false);
      resultDescription.setColumnDescriptor(i, rcd);
    }
    final SQLWarning warnings = firstResultHolder.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }
    return resultDescription;
  }

  public final void setupRC(final GfxdResultCollector<?> rc)
    throws StandardException {
  }

  @Override
  public void finishResultSet(boolean cleanupOnError) throws StandardException {
    // TO IMPLEMENT
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    // TO IMPLEMENT
    return 0;
  }

  @Override
  public long getTimeSpent(int type, int timeType) {
    return -1;
  }

  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
  }

  public void setup(Object res, int numMembers) throws StandardException {
    this.resultHolderList = (Collection<?>)res;
    try {
      this.srhIterator = this.resultHolderList.iterator();
      // expect at least one result (for metadata)
      this.currentResultHolder = this.firstResultHolder =
          (SnappyResultHolder)srhIterator.next();
    } catch (RuntimeException ex) {
      ex = LeadNodeExecutorMsg.handleLeadNodeRuntimeException(ex);
      throw Misc.processFunctionException("SnappySelectResultSet:setup",
          ex, null, null);
    }
  }

  @Override
  public boolean returnsRows() {
    return returnRows;
  }

  @Override
  public void clearCurrentRow() {
    this.currentRow = null;
  }

  @Override
  public boolean needsRowLocation() {
    return false;
  }

  @Override
  public void rowLocation(RowLocation rl) throws StandardException {
  }

  @Override
  public ExecRow getNextRowFromRowSource() throws StandardException {
    return null;
  }

  @Override
  public boolean needsToClone() {
    return false;
  }

  @Override
  public FormatableBitSet getValidColumns() {
    return null;
  }

  @Override
  public void closeRowSource() {
  }
}
