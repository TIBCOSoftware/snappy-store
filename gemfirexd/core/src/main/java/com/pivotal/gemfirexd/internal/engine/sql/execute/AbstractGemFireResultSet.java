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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.sql.SQLWarning;
import java.sql.Timestamp;

import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils.Context;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

public abstract class AbstractGemFireResultSet implements ResultSet {

  final protected Activation activation;
  
  protected final LanguageConnectionContext lcc;

  protected final GemFireTransaction tran;

  protected boolean isClosed;

  protected final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
      .getInstance();

  // statistics data
  // ----------------
  protected transient boolean runtimeStatisticsOn;
  
  protected transient boolean statisticsTimingOn;
  
  protected transient boolean  explainConnection;
  
  protected transient boolean  statsEnabled;
  
  protected transient long beginExecutionTime;

  protected transient long endExecutionTime;

  public transient long openTime;

  public transient long nextTime;

  public transient long closeTime;

  protected NoPutResultSet[] subqueryTrackingArray;

  private UUID executionPlanID;

  // end of statistics data
  // -----------------------

  protected transient boolean allTablesReplicatedOnRemote;

  protected transient boolean hasLockReference;

  /**
   * @param allTablesReplicatedOnRemote the allTablesReplicatedOnRemote to set
   */
  public void setAllTablesReplicatedOnRemote(boolean allTablesReplicatedOnRemote) {
    this.allTablesReplicatedOnRemote = allTablesReplicatedOnRemote;
  }

  public AbstractGemFireResultSet(final Activation act) {
    this.activation = act;
    this.lcc = activation.getLanguageConnectionContext();
    this.runtimeStatisticsOn = lcc.getRunTimeStatisticsMode();
    this.statisticsTimingOn = lcc.getStatisticsTiming();
    this.explainConnection = lcc.explainConnection();
    this.statsEnabled = lcc.statsEnabled();
    
    if (this.statisticsTimingOn) {
      this.beginExecutionTime = XPLAINUtil.currentTimeMillis();
    }
    else {
      this.beginExecutionTime = 0;
    }
    
    this.tran = (GemFireTransaction)act.getTransactionController();
    this.isClosed = true;
  }

  @Override
  public final Activation getActivation() {
    return this.activation;
  }

  public final LanguageConnectionContext getLanguageConnectionContext() {
    return this.lcc;
  }

  @Override
  public void open() throws StandardException {
    if (!this.isClosed) {
      return;
    }

    this.runtimeStatisticsOn = lcc.getRunTimeStatisticsMode();
    this.statisticsTimingOn = lcc.getStatisticsTiming();
    this.explainConnection = lcc.explainConnection();
    this.statsEnabled = lcc.statsEnabled();

    final long beginTime;
    if (statisticsTimingOn) {
      if(beginExecutionTime == 0) {
        this.beginExecutionTime = XPLAINUtil.currentTimeMillis();
      }
      beginTime = XPLAINUtil.recordTiming(openTime == 0 ? openTime = -1 /*record*/
      : -2/*ignore nested call*/);
    }
    else {
      beginTime = 0;
    }

    this.isClosed = false;

    if (observer != null) {
      observer.beforeGemFireResultSetOpen(this, lcc);
    }
    // set the topResultSet in context so that proper cleanup can be
    // done in case of an error
    StatementContext sc = this.activation.getLanguageConnectionContext()
        .getStatementContext();
    sc.setTopResultSet(this, null);
    try {
      this.openCore();
    } finally {
      // this will recompile the statement if required (e.g. table shape
      // changes after lock is acquired)
      this.activation.checkStatementValidity();
    }
    if (observer != null) {
      observer.afterGemFireResultSetOpen(this, lcc);
    }
    if (beginTime != 0) {
      openTime = XPLAINUtil.recordTiming(beginTime);
    }
  }
  
  protected abstract void openCore() throws StandardException;

  /**
   * Open or close the foreign key table containers for given container to
   * acquire or release locks on those containers respectively.
   */
  public static void openOrCloseFKContainers(GemFireContainer container,
      GemFireTransaction tran, boolean doClose, boolean forUpdate)
      throws StandardException {
    final ExtraTableInfo tableInfo = container.getExtraTableInfo();
    final GemFireContainer[] fkContainers;
    if (tableInfo != null
        && (fkContainers = tableInfo.getForeignKeyContainers()) != null) {
      GemFireContainer refContainer;
      for (int index = 0; index < fkContainers.length; ++index) {
        refContainer = fkContainers[index];
        if (doClose) {
          refContainer.closeForEndTransaction(tran, forUpdate);
        }
        else {
          refContainer.open(tran, ContainerHandle.MODE_READONLY);
        }
      }
    }
  }

  public void setup(Object results, int numMembers) throws StandardException {
  }

  public void setupRC(GfxdResultCollector<?> rc) throws StandardException {
  }

  public void reset(GfxdResultCollector<?> rc) throws StandardException {
  }

  protected void setNumRowsModified(int numRowsModified) {
  }

  @Override
  public void close(final boolean cleanupOnError) throws StandardException {
    if (this.isClosed) {
      return;
    }
    // take the beginTime temporarily captured in closeTime from lower layer.
    final long beginTime = statisticsTimingOn ? (closeTime == 0 ? XPLAINUtil.recordTiming(-1) : closeTime) : 0;

    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();

    String query = null;
    if (observer != null) {
      final PreparedStatement ps = this.activation.getPreparedStatement();
      if (ps != null) {
        query = ps.getUserQueryString(lcc);
      }
      observer.beforeGemFireResultSetClose(this, query);
    }
    finishResultSet(cleanupOnError);
    if (this.activation instanceof AbstractGemFireActivation) {
      ((AbstractGemFireActivation)this.activation).currentRS = null;
    }
    if (observer != null) {
      observer.afterGemFireResultSetClose(this, query);
    }

    if (beginTime != 0) {
      closeTime = XPLAINUtil.recordTiming(beginTime);
    }
    // this should not be accounted for close time.
    try {
      if (this.runtimeStatisticsOn) {
        collectPlan(lcc, cleanupOnError);
      }
    } finally {
      this.isClosed = true;
      if (this.activation.isSingleExecution()) {
        this.activation.close();
      }
    }
  }

  private final void collectPlan(final LanguageConnectionContext lcc, final boolean cleanupOnError)
      throws StandardException {

    endExecutionTime = statisticsTimingOn ? XPLAINUtil.currentTimeMillis() : 0;

    // get the ResultSetStatisticsFactory, which gathers RuntimeStatistics
    ExecutionFactory ef = lcc.getLanguageConnectionFactory()
        .getExecutionFactory();
    lcc.setRunTimeStatisticsObject(ef.getResultSetStatisticsFactory()
        .getRunTimeStatistics(activation, this, null));

    HeaderPrintWriter istream = lcc.getLogQueryPlan() ? Monitor.getStream()
        : null;
    if (istream != null) {
      istream.printlnWithHeader("RunTime Statistics: "
          + LanguageConnectionContext.xidStr
          + lcc.getTransactionExecute().getTransactionIdString() + "), "
          + LanguageConnectionContext.lccStr + lcc.getInstanceNumber() + "), "
          + lcc.getRunTimeStatisticsObject().getStatementText() + " ******* "
          + lcc.getRunTimeStatisticsObject().getStatementExecutionPlanText());
    }

    if (!cleanupOnError) {
      // now explain gathered statistics, using an appropriate visitor
      ResultSetStatisticsVisitor visitor = ef.getXPLAINFactory()
          .getXPLAINVisitor(lcc, statsEnabled, explainConnection);
      visitor.doXPLAIN(this, activation, true, statisticsTimingOn, false);
      
      executionPlanID = visitor.getStatementUUID();
      
    }
    // reset the statistics variables
    resetStatistics();
  }
  
  public Context getNewPlanContext() {
    return new PlanUtils.Context();
  }
  
  public StringBuilder buildQueryPlan(final StringBuilder builder, final PlanUtils.Context context) {
    
    PlanUtils.xmlBeginTag(builder, context, this);
    
    return builder;
  }
  
  public UUID getExecutionPlanID() {
    return executionPlanID;
  }

  @Override
  public final void finish() throws StandardException {
    close(false);
  }

  public abstract void finishResultSet(final boolean cleanupOnError) throws StandardException;

  @Override
  public final boolean isClosed() {
    return this.isClosed;
  }

  @Override
  public final Timestamp getBeginExecutionTimestamp() {
      return new Timestamp(beginExecutionTime);
  }

  @Override
  public final Timestamp getEndExecutionTimestamp() {
      return new Timestamp(endExecutionTime);
  }
  
  abstract public long estimateMemoryUsage() throws StandardException;

  @Override
  public long getTimeSpent(int type, int timeType) {
    return PlanUtils.getTimeSpent(0, openTime, nextTime, closeTime, timeType);
  }
  
  @Override
  public final void checkCancellationFlag() throws StandardException {
    final Activation act = this.activation;
    if (act != null && act.isQueryCancelled()) {
      act.checkCancellationFlag();
    }
  }

  /************** Unsupported Operations ******************/

  @Override
  public final boolean checkRowPosition(int isType) throws StandardException {
    throw new UnsupportedOperationException("Not supported yet");
  }

  @Override
  public final void cleanUp(final boolean cleanupOnError) throws StandardException {
    close(cleanupOnError);
  }

  @Override
  public final ExecRow getAbsoluteRow(int row) throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public final ExecRow getFirstRow() throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public final ExecRow getLastRow() throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public ExecRow getNextRow() throws StandardException {
    throw StandardException.newException(SQLState.LANG_DOES_NOT_RETURN_ROWS,
        "next");
  }

  @Override
  public final ExecRow getPreviousRow() throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public final ExecRow getRelativeRow(int row) throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public final int getRowNumber() {
    return 0;
  }

  @Override
  public final ExecRow setBeforeFirstRow() throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  @Override
  public final ExecRow setAfterLastRow() throws StandardException {
    throw StandardException.newException(SQLState.SCROLL_NOT_SUPPORTED);
  }

  protected final void addWarning(SQLWarning w) {
    this.activation.addResultsetWarning(w);
  }

  @Override
  public final SQLWarning getWarnings() {
    return this.activation.getResultsetWarnings();
  }

  @Override
  public int modifiedRowCount() {
    return 0;
  }

  @Override
  public void clearCurrentRow() {
  }

  @Override
  public ResultSet getAutoGeneratedKeysResultset() {
    throw new UnsupportedOperationException("requires implementation in child");
  }

  /**
   * @see ResultSet#hasAutoGeneratedKeysResultSet
   */
  @Override
  public boolean hasAutoGeneratedKeysResultSet() {
    return false;
  }

  @Override
  public void flushBatch() throws StandardException {
    // no batching by default
  }
  
  @Override
  public void closeBatch() throws StandardException {
    // no batching by default
  }

  @Override
  public final String getCursorName() {
    return null;
  }

  @Override
  public final long getExecuteTime() {
    return -1;
  }

  /**
   * @see ResultSet#getSubqueryTrackingArray(int)
   */
  @Override
  public final NoPutResultSet[] getSubqueryTrackingArray(int numSubqueries) {
    if (this.subqueryTrackingArray == null) {
      this.subqueryTrackingArray = new NoPutResultSet[numSubqueries];
    }
    return this.subqueryTrackingArray;
  }
  
  public void markLocallyExecuted() {
    // nothing to do.
  }

  public void resetStatistics() {
    this.beginExecutionTime = 0;
    this.endExecutionTime = 0;
    this.openTime = 0;
    this.nextTime = 0;
    this.closeTime = 0;
  }

  public boolean supportsMoveToNextKey() {
    return false;
  }


  public int getScanKeyGroupID() {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDistributedResultSet() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean addLockReference(GemFireTransaction tran) {
    tran.getLockSpace().addResultSetRef();
    this.hasLockReference = true;
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean releaseLocks(GemFireTransaction tran) {
    if (this.hasLockReference) {
      tran.releaseAllLocks(false, true);
      this.hasLockReference = false;
      return true;
    }
    else {
      return false;
    }
  }
  
  /*
   * Blank method from NoPutResultSet
   */
  public void forceReOpenCore() throws StandardException { 
  }
}
