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

package com.pivotal.gemfirexd.internal.engine;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ComparisonQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TriggerDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SelectNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule;

@SuppressWarnings("serial")
public class GemFireXDQueryObserverAdapter implements GemFireXDQueryObserver,
    Serializable {

  @Override
  public void afterQueryParsing(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
  }

  @Override
  public void beforeOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
  }

  @Override
  public void afterOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
  }

  @Override
  public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
  }

  @Override
  public void queryInfoObjectAfterPreparedStatementCompletion(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
  }

  @Override
  public void subQueryInfoObjectFromOptmizedParsedTree(
      List<SubQueryInfo> qInfoList, GenericPreparedStatement gps,
      LanguageConnectionContext lcc) {
  }

  @Override
  public void beforeQueryExecution(EmbedStatement stmt, Activation activation)
      throws SQLException {
  }

  @Override
  public PreparedStatement afterQueryPrepareFailure(Connection conn,
      String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability, int autoGeneratedKeys, int[] columnIndexes,
      String[] columnNames, SQLException sqle) throws SQLException {
    return null;
  }

  @Override
  public boolean afterQueryExecution(CallbackStatement stmt, SQLException sqle)
      throws SQLException {
    return false;
  }

  @Override
  public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
      throws SQLException {
  }

  @Override
  public void afterBatchQueryExecution(EmbedStatement stmt, int batchSize) {
  }

  @Override
  public void beforeQueryExecution(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc) throws StandardException {
  }

  @Override
  public void afterQueryExecution(GenericPreparedStatement stmt,
      Activation activation) throws StandardException {
  }

  @Override
  public void afterResultSetOpen(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc, ResultSet resultSet) {
  }

  @Override
  public void onEmbedResultSetMovePosition(EmbedResultSet rs, ExecRow newRow,
      ResultSet theResults) {
  }

  @Override
  public void beforeGemFireActivationCreate(AbstractGemFireActivation ac) {
  }

  @Override
  public void afterGemFireActivationCreate(AbstractGemFireActivation ac) {
  }

  @Override
  public void beforeGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) throws StandardException {
  }

  @Override
  public void afterGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) {
  }

  @Override
  public void beforeGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
  }

  @Override
  public void beforeComputeRoutingObjects(AbstractGemFireActivation activation) {
  }

  @Override
  public void afterComputeRoutingObjects(AbstractGemFireActivation activation) {
  }

  @Override
  public <T extends Serializable> void beforeQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
  }

  @Override
  public <T extends Serializable> void afterQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
  }

  @Override
  public void afterGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
  }

  @Override
  public void beforeGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
  }

  @Override
  public void beforeEmbedResultSetClose(EmbedResultSet rs, String query) {
  }

  @Override
  public void afterGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
  }

  @Override
  public void beforeResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
  }

  @Override
  public void beforeResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
  }

  @Override
  public void afterResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
  }

  @Override
  public void beforeResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
  }

  @Override
  public void afterResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
  }

  @Override
  public void afterResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es, String query) {
  }

  @Override
  public void beforeResultSetHolderRowRead(RowFormatter rf, Activation act) {
  }

  @Override
  public void afterResultSetHolderRowRead(RowFormatter rf, ExecRow row,
      Activation act) {
  }

  @Override
  public void beforeQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
  }

  @Override
  public void afterQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
  }

  @Override
  public void beforeQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
  }

  @Override
  public void afterQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
  }

  @Override
  public void beforeQueryReprepare(GenericPreparedStatement gpst,
      LanguageConnectionContext lcc) throws StandardException {
  }

  @Override
  public void createdGemFireXDResultSet(ResultSet rs) {
  }

  @Override
  public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
      EntryEventImpl event, RegionEntry entry) {
  }

  @Override
  public void beforeForeignKeyConstraintCheckAtRegionLevel() {
  }

  @Override
  public void beforeUniqueConstraintCheckAtRegionLevel() {
  }

  @Override
  public void beforeGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey) {
  }

  @Override
  public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey, Object result) {
  }

  @Override
  public void scanControllerOpened(Object sc, Conglomerate conglom) {
  }

  @Override
  public void beforeConnectionCloseByExecutorFunction(long[] connectionIDs) {
  }

  @Override
  public void afterConnectionCloseByExecutorFunction(long[] connectionIDs) {
  }

  @Override
  public void afterConnectionClose(Connection conn) {
  }

  @Override
  public void beforeORM(Activation activation, AbstractGemFireResultSet rs) {
  }

  @Override
  public void afterORM(Activation activation, AbstractGemFireResultSet rs) {
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost) {
    return optimzerEvalutatedCost;
  }

  @Override
  public double overrideDerbyOptimizerCostForMemHeapScan(
      GemFireContainer gfContainer, double optimzerEvalutatedCost) {
    return optimzerEvalutatedCost;
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost) {
    return optimzerEvalutatedCost;
  }

  @Override
  public void criticalUpMemoryEvent(GfxdHeapThresholdListener listener) {
  }

  @Override
  public void criticalDownMemoryEvent(GfxdHeapThresholdListener listener) {
  }

  @Override
  public void estimatingMemoryUsage(String stmtText, Object resultSet) {
  }

  @Override
  public long estimatedMemoryUsage(String stmtText, long memused) {
    return memused;
  }

  @Override
  public void putAllCalledWithMapSize(int size) {
  }

  @Override
  public void afterBulkOpDBSynchExecution(Event.Type type, int numRowsModified,
      Statement ps, String dml) {
  }

  @Override
  public void afterPKBasedDBSynchExecution(Event.Type type,
      int numRowsModified, Statement ps) {
  }

  @Override
  public void afterCommitDBSynchExecution(List<Event> batchProcessed) {
  }

  @Override
  public void afterCommit(Connection conn) {
  }

  @Override
  public void afterRollback(Connection conn) {
  }

  @Override
  public void afterClosingWrapperPreparedStatement(long wrapperPrepStatementID,
      long wrapperConnectionID) {
  }

  @Override
  public void updatingColocationCriteria(ComparisonQueryInfo cqi) {
  }

  @Override
  public void statementStatsBeforeExecutingStatement(StatementStats stats) {
  }

  @Override
  public void reset() {
  }

  @Override
  public void close() {
  }

  @Override
  public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
      GenericPreparedStatement gps, String subquery,
      List<Integer> paramPositions) {
  }

  @Override
  public void insertMultipleRowsBeingInvoked(int numElements) {
  }

  @Override
  public void keyAndContainerAfterLocalIndexInsert(Object key,
      Object rowLocation, GemFireContainer container) {
  }

  @Override
  public void keyAndContainerAfterLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
  }

  @Override
  public void keyAndContainerBeforeLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
  }

  @Override
  public void getAllInvoked(int numKeys) {
  }

  @Override
  public void getAllGlobalIndexInvoked(int numKeys) {
  }

  @Override
  public void getAllLocalIndexInvoked(int numKeys) {
  }

  @Override
  public void getAllLocalIndexExecuted() {
  }

  @Override
  public void ncjPullResultSetOpenCoreInvoked() {
  }

  @Override
  public void getStatementIDs(long stID, long rootID, int stLevel) {
  }

  @Override
  public void ncjPullResultSetVerifyBatchSize(int value) {
  }

  @Override
  public void ncjPullResultSetVerifyCacheSize(int value) {
  }
  
  @Override
  public void ncjPullResultSetVerifyVarInList(boolean value) {
  }

  @Override
  public void independentSubqueryResultsetFetched(Activation activation,
      ResultSet results) {
  }

  @Override
  public void beforeInvokingContainerGetTxRowLocation(RowLocation regionEntry) {
  }

  @Override
  public void afterGetRoutingObject(Object routingObject) {
  }

  @Override
  public long overrideUniqueID(long actualUniqueID, boolean forRegionKey) {
    return actualUniqueID;
  }

  @Override
  public boolean beforeProcedureResultSetSend(ProcedureSender sender,
      EmbedResultSet rs) {
    return true;
  }

  @Override
  public boolean beforeProcedureOutParamsSend(ProcedureSender sender,
      ParameterValueSet pvs) {
    return true;
  }

  @Override
  public void beforeProcedureChunkMessageSend(ProcedureChunkMessage message) {
  }

  @Override
  public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
      RegionEntry entry, boolean writeLock) {
  }

  @Override
  public void attachingKeyInfoForUpdate(GemFireContainer container,
      RegionEntry entry) {
  }

  @Override
  public int overrideSortBufferSize(ColumnOrdering[] columnOrdering,
      int sortBufferMax) {
    return sortBufferMax;
  }

  @Override
  public boolean avoidMergeRuns() {
    return true;
  }

  @Override
  public void callAtOldValueSameAsNewValueCheckInSM2IIOp() {
  }

  @Override
  public void onGetNextRowCore(ResultSet resultSet) {
  }

  @Override
  public void onGetNextRowCoreOfBulkTableScan(ResultSet resultSet) {
  }

  @Override
  public void onGetNextRowCoreOfGfxdSubQueryResultSet(ResultSet resultSet) {
  }

  @Override
  public void onDeleteResultSetOpen(ResultSet resultSet) {
  }

  @Override
  public void onSortResultSetOpen(ResultSet resultSet) {
  }

  @Override
  public void onGroupedAggregateResultSetOpen(ResultSet resultSet) {
  }

  @Override
  public void onDeleteResultSetOpenBeforeRefChecks(ResultSet resultSet) {
  }

  @Override
  public void onDeleteResultSetOpenAfterRefChecks(ResultSet resultSet) {
  }

  @Override
  public void setRoutingObjectsBeforeExecution(
      final Set<Object> routingKeysToExecute) {
  }

  @Override
  public void beforeDropGatewayReceiver() {
  }

  @Override
  public void beforeDropDiskStore() {
  }

  @Override
  public void memberConnectionAuthenticationSkipped(boolean skipped) {
  }

  @Override
  public void userConnectionAuthenticationSkipped(boolean skipped) {
  }

  @Override
  public void regionSizeOptimizationTriggered(FromBaseTable fbt,
      SelectNode selectNode) {
  }

  @Override
  public void regionSizeOptimizationTriggered2(SelectNode selectNode) {
  }

  @Override
  public void beforeFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
  }

  @Override
  public void afterFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
  }

  @Override
  public void invokeCacheCloseAtMultipleInsert() {
  }

  public boolean isCacheClosedForTesting() {
    return false;
  }

  @Override
  public void afterGlobalIndexInsert(boolean possDup) {
  }

  @Override
  public boolean needIndexRecoveryAccounting() {
    return false;
  }

  @Override
  public void setIndexRecoveryAccountingMap(THashMap map) {

  }

  @Override
  public boolean throwPutAllPartialException() {
    return false;
  }

  @Override
  public void afterIndexRowRequalification(Boolean success,
      CompactCompositeIndexKey ccKey, ExecRow row, Activation activation) {
  }

  @Override
  public void beforeRowTrigger(LanguageConnectionContext lcc, ExecRow execRow,
      ExecRow newRow) {
  }

  @Override
  public void afterRowTrigger(TriggerDescriptor trigD,
      GenericParameterValueSet gpvs) {
  }

  @Override
  public void beforeGlobalIndexDelete() {
  }

  @Override
  public void onUpdateResultSetOpen(ResultSet resultSet) {
  }

  @Override
  public void beforeDeferredUpdate() {
  }

  @Override
  public void beforeDeferredDelete() {
  }

  @Override
  public void onUpdateResultSetDoneUpdate(ResultSet resultSet) {
  }

  @Override
  public void bucketIdcalculated(int bid) {
  }

  @Override
  public void afterPuttingInCached(Serializable globalIndexKey, Object result) {
  }

  @Override
  public void beforeReturningCachedVal(Serializable globalIndexKey,
      Object cachedVal) {
  }

  @Override
  public void afterSingleRowInsert(Object routingObj) {
  }

  @Override
  public void afterQueryPlanGeneration() {
  }

  @Override
  public void afterLockingTableDuringImport() {
  }

  @Override
  public boolean testIndexRecreate() {
    return false;
  }

  @Override
  public void testExecutionEngineDecision(QueryInfo queryInfo,
      ExecutionEngineRule.ExecutionEngine engine, String queryText) {
  }

  public void regionPreInitialized(GemFireContainer container) {
  }
}
