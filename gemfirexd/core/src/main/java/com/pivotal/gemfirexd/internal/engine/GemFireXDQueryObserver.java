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
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.execute.QueryObserver;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColocationCriteria;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ComparisonQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
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
import com.pivotal.gemfirexd.internal.iapi.store.access.BackingStoreHashtable;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
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

/**
 * This interface is used by testing/debugging code to be notified of various
 * query events
 * 
 * @author Asif
 */
public interface GemFireXDQueryObserver extends QueryObserver {

  /**
   * Callback indicating query parsing is happening.
   * 
   * @param query
   *          Query string being executed
   * @param qt
   *          the {@link StatementNode} for the currently compiling statement
   * @param lcc
   *          the {@link LanguageConnectionContext} for the current statement
   */
  public void afterQueryParsing(String query, StatementNode qt,
      LanguageConnectionContext lcc);

  /**
   * Callback invoked after optimization of query tree providing the optimized
   * result.
   * 
   * @param query
   *          Query string being executed
   * @param qt
   *          the {@link StatementNode} for the currently compiling statement
   * @param lcc
   *          the {@link LanguageConnectionContext} for the current statement
   */
  public void beforeOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc);

  /**
   * Callback invoked after optimization of query tree providing the optimized
   * result.
   * 
   * @param query
   *          Query string being executed
   * @param qt
   *          the {@link StatementNode} for the currently compiling statement
   * @param lcc
   *          the {@link LanguageConnectionContext} for the current statement
   */
  public void afterOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc);

  /**
   * Callback invoked after optimization of query tree during statement prepare
   * phase.
   * @param gps
   *          GenericPreparedStatement for the query
   * @param lcc TODO
   * @param qInfo
   *          SubqueryInfo object created from the optimized parsed tree
   */
  public void subQueryInfoObjectFromOptmizedParsedTree(
      List<SubQueryInfo> qInfos, GenericPreparedStatement gps, LanguageConnectionContext lcc);

  /**
   * Callback invoked after optimization of query tree during statement prepare
   * phase.
   * 
   * @param qInfo
   *          QueryInfo object created from the optimized parsed tree
   * @param gps
   *          GenericPreparedStatement for the query
   * @param lcc TODO
   */
  public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc);

  /**
   * Callback invoked after prepared statement is complete initialized during
   * statement prepare phase.
   * 
   * @param qInfo
   *          QueryInfo object created from the optimized parsed tree
   * @param gps
   *          GenericPreparedStatement for the query
   * @param lcc TODO
   */
  public void queryInfoObjectAfterPreparedStatementCompletion(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc);

  /**
   * Callback invoked before start of a query execution.
   * 
   * @param stmt
   *          the {@link EmbedStatement} for the current query
   * @param activation
   *          the {@link Activation} object for current query
   */
  public void beforeQueryExecution(EmbedStatement stmt, Activation activation)
      throws SQLException;

  /**
   * Callback invoked before start of a batched query execution.
   * 
   * @param stmt
   *          the {@link EmbedStatement} for the current query
   * @param batchSize
   *          the number of elements in the batch
   */
  public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
      throws SQLException;

  /**
   * Callback invoked after end of a successful batch query execution.
   * 
   * @param stmt
   *          the {@link EmbedStatement} for the current query
   * @param batchSize
   *          the number of elements in the batch
   */
  public void afterBatchQueryExecution(EmbedStatement stmt, int batchSize);

  /**
   * Callback invoked iff SanityManager.DEBUG is true, before
   * activation.execute() call in
   * {@link GenericPreparedStatement#execute(Activation, boolean, long, boolean)}
   * 
   * At this point GenericResultSets are about to get created. <BR>
   * Primarily used as a query cancellation point.
   * 
   * @param stmt
   *          the {@link GenericPreparedStatement} used for the query.
   * @param lcc TODO
   * @throws StandardException
   */
  public void beforeQueryExecution(GenericPreparedStatement stmt, LanguageConnectionContext lcc)
      throws StandardException;

  /**
   * Callback invoked iff SanityManager.DEBUG is true, after
   * activation.execute() call in
   * {@link GenericPreparedStatement#execute(Activation, boolean, long, boolean)}
   * .
   * 
   * At this point GenericResultSets have just been created. Callback is skipped
   * on exception. <BR>
   * Primarily used as a query cancellation point.
   * 
   * @param stmt
   *          the {@link GenericPreparedStatement} used for the query.
   * @param activation
   *          the activatio for the statement
   * @throws StandardException
   */
  public void afterQueryExecution(GenericPreparedStatement stmt,
      Activation activation) throws StandardException;

  /**
   * Callback invoked iff SanityManager.DEBUG is true, after
   * activation.execute() call in
   * {@link GenericPreparedStatement#execute(Activation, boolean, long, boolean)}
   * .
   * 
   * At this point GenericResultSets have just been created. Depending on the
   * query execution has prepared to operate on the underlying store. Sometimes
   * in presence of grouping, ordering, distinct clauses in a query this will
   * fetch and replicate the required data from the store.
   * 
   * Callback is skipped on exception. <BR>
   * Primarily used as a query cancellation point.
   * 
   * @param stmt
   *          the {@link GenericPreparedStatement} used for the query.
   * @param lcc TODO
   * @param resultSet TODO
   */
  public void afterResultSetOpen(GenericPreparedStatement stmt, LanguageConnectionContext lcc, ResultSet resultSet);

  /**
   * Callback invoked iff SanityManager.DEBUG is true, and cursor movement
   * methods are called e.g. rs.next(). <BR>
   * Primarily used as a query cancellation point.
   * @param newRow
   *          the new {@link ExecRow} after the cursor is moved
   */
  public void onEmbedResultSetMovePosition(EmbedResultSet rs, ExecRow newRow,
      ResultSet theResults);

  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is opened.
   * 
   * @param ac
   *          the {@link AbstractGemFireResultSet} that has been opened
   */
  public void beforeGemFireActivationCreate(AbstractGemFireActivation ac);

  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is opened.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   */
  public void afterGemFireActivationCreate(AbstractGemFireActivation ac);

  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is opened.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param lcc TODO
   * @throws StandardException
   */
  public void beforeGemFireResultSetOpen(AbstractGemFireResultSet rs, LanguageConnectionContext lcc)
      throws StandardException;

  /**
   * Callback invoked after a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is successfully opened.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param lcc TODO
   */
  public void afterGemFireResultSetOpen(AbstractGemFireResultSet rs, LanguageConnectionContext lcc);

  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is opened.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param lcc TODO
   * @throws StandardException
   */
  public void beforeFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException;
  
  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is opened.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param lcc TODO
   * @throws StandardException
   */
  public void afterFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException;
  
  /**
   * Callback invoked before retrieving ResultSet using GemFireXD's Activation
   * class ( GemFireActivation or GemfireDistributedActivation) This callback is
   * generated from execute method of Activation class
   * 
   * @param activation
   *          Instance of type AbstractGemFireActivation
   * @see AbstractGemFireActivation
   * @see GemfireDistributionActivation
   * @see GemFireActivation
   */
  public void beforeGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation);

  /**
   * Callback invoked before computation of routing object.
   * 
   * @param activation
   *          Instance of {@link AbstractGemFireActivation}
   */
  public void beforeComputeRoutingObjects(AbstractGemFireActivation activation);

  /**
   * Callback invoked after computation of routing object.
   * 
   * @param activation
   *          Instance of {@link AbstractGemFireActivation}
   */
  public void afterComputeRoutingObjects(AbstractGemFireActivation activation);

  /**
   * Callback invoked before distribution of query to other nodes.
   * 
   * @param executorMessage
   *          the message being executed
   * @param activation
   *          Instance of {@link AbstractGemFireActivation}
   */
  public <T extends Serializable> void beforeQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming);

  /**
   * Callback invoked after distribution of query to other nodes.
   * 
   * @param executorMessage
   *          the message being executed
   * @param activation
   *          Instance of {@link AbstractGemFireActivation}
   */
  public <T extends Serializable> void afterQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming);

  /**
   * Callback invoked after retrieving ResultSet using GemFireXD's Activation
   * class (GemFireActivation or GemfireDistributedActivation). This callback is
   * generated from execute method of Activation class.
   * 
   * @param activation
   *          Instance of type AbstractGemFireActivation
   * 
   * @see AbstractGemFireActivation
   * @see GemfireDistributionActivation
   * @see GemFireActivation
   */
  public void afterGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation);

  /**
   * Callback invoked before a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is closed.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param query
   *          Query string that will be executed
   */
  public void beforeGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query);

  /**
   * Callback invoked after a {@link ResultSet} of type
   * {@link AbstractGemFireResultSet} is successfully closed.
   * 
   * @param rs
   *          the {@link AbstractGemFireResultSet} that has been opened
   * @param query
   *          Query string that has been executed
   */
  public void afterGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query);
  
  public void beforeEmbedResultSetClose(EmbedResultSet rs,
      String query);

  /**
   * Callback given after creating GemFireXD ResultSet(s) using GemFireXD's
   * Activation class ( GemFireActivation or GemfireDistributedActivation) This
   * callback is generated from constructor of GemFire..ResultSet class.
   * 
   * @param resultset
   *          Instance of type ResultSet
   * @see GemFireDistributedResultSet
   * @see GemFireUpdateResultSet
   */
  public void createdGemFireXDResultSet(ResultSet rs);

  /**
   * Callback invoked just before the StatementQueryExecutorFunction executes
   * the query on the data store node.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param stmt
   *          Instance of {@link EmbedStatement} that will execute the query.
   * @param query
   *          Query string that will be executed
   */
  public void beforeQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query);

  /**
   * Callback invoked just after the StatementQueryExecutorFunction successfully
   * executes the query on the data store node.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param stmt
   *          Instance of {@link EmbedStatement} that executed the query.
   * @param query
   *          Query string that was executed
   */
  public void afterQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query);

  /**
   * Callback invoked just before the PrepStatementQueryExecutorFunction
   * executes the query on the data store node.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param pstmt
   *          Instance of {@link EmbedPreparedStatement} that will be executed.
   * @param query
   *          Query string that was sent to this node. Note that this may be
   *          null in case the node has already seen the query so to get the
   *          actual query string use pstmt.getSQLText()
   */
  public void beforeQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query);

  /**
   * Callback invoked just after the PrepStatementQueryExecutorFunction
   * successfully executes the query on the data store node.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param pstmt
   *          Instance of {@link EmbedPreparedStatement} that was executed.
   * @param query
   *          Query string that was sent to this node. Note that this may be
   *          null in case the node has already seen the query so to get the
   *          actual query string use pstmt.getSQLText()
   */
  public void afterQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query);

  /**
   * Callback invoked before query execution and serialization in
   * {@link ResultHolder}.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   */
  public void beforeResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es);

  /**
   * Callback invoked before iteration of a single result in
   * {@link ResultHolder}.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   */
  public void beforeResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es);

  /**
   * Callback invoked after iteration of a single result in {@link ResultHolder}
   * .
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   */
  public void afterResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es);

  /**
   * Callback invoked before serialization of a single result in
   * {@link ResultHolder}.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   */
  public void beforeResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es);

  /**
   * Callback invoked after serialization of a single result in
   * {@link ResultHolder}.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   */
  public void afterResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es);

  /**
   * Callback invoked after successful query execution and serialization in
   * {@link ResultHolder}.
   * 
   * @param wrapper
   *          Instance of GfxdConnectionWrapper which stores the EmbedConnection
   *          for a connectionID and wraps the Statement Objects.
   * @param es
   *          the {@link EmbedStatement} for the current execution
   * @param query
   *          Query string that was executed
   */
  public void afterResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es, String query);

  /**
   * Callback invoked before deserialization of a row from {@link ResultHolder}.
   * 
   * @param cdl
   *          the {@link RowFormatter} for the current row
   * @param act
   *          {@link Activation} object for this execution
   */
  public void beforeResultSetHolderRowRead(RowFormatter rf, Activation act);

  /**
   * Callback invoked after successful deserialization of a row from
   * {@link ResultHolder}.
   * 
   * @param cdl
   *          the {@link RowFormatter} for the current row
   * @param row
   *          the {@link ExecRow} that was read from stream
   * @param act
   *          {@link Activation} object for this execution
   */
  public void afterResultSetHolderRowRead(RowFormatter rf, ExecRow row,
      Activation act);

  /**
   * Invoked before index updates performed by {@link GfxdIndexManager}.
   */
  public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
      EntryEventImpl event, RegionEntry entry);

  public void beforeForeignKeyConstraintCheckAtRegionLevel();

  public void beforeUniqueConstraintCheckAtRegionLevel();

  /**
   * Callback invoked before global index lookup of a primary key or unique key.
   * 
   * @param lcc
   *          the {@link LanguageConnectionContext} of the current operation
   * @param indexRegion
   *          the {@link PartitionedRegion} of the global index region
   * @param indexKey
   *          the global index lookup key
   */
  public void beforeGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey);

  /**
   * Callback invoked after global index lookup of a primary key or unique key.
   * 
   * @param lcc
   *          the {@link LanguageConnectionContext} of the current operation
   * @param indexRegion
   *          the {@link PartitionedRegion} of the global index region
   * @param indexKey
   *          the global index lookup key
   * @param result
   *          the result of global index lookup for the key
   */
  public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey, Object result);

  /**
   * Callback invoked when a scan is opened on a table on index.
   * 
   * @param sc
   *          a {@link ScanController} that has been opened in current
   *          query/update/delete, or a {@link BackingStoreHashtable} opened for
   *          hash/distinct scans
   * @param conglom
   *          {@link Conglomerate} for which the scan has been opened
   */
  public void scanControllerOpened(Object sc, Conglomerate conglom);

  /**
   * Calback invoked before executing connection close by the
   * DistributedConnectionCloseExecutorFunction
   */
  public void beforeConnectionCloseByExecutorFunction(long[] connectionIDs);

  /**
   * Calback invoked after successfully executing connection close by the
   * DistributedConnectionCloseExecutorFunction
   */
  public void afterConnectionCloseByExecutorFunction(long[] connectionIDs);

  /**
   * Callback invoked when the ORM process of ResultSet starts.
   */
  public void beforeORM(Activation activation, AbstractGemFireResultSet rs);

  /**
   * Callback invoked when the ORM process of ResultSet ends.
   */
  public void afterORM(Activation activation, AbstractGemFireResultSet rs);

  /**
   * Callback invoked during the process of identification of the optimal query
   * plan. This hook can be used to override the cost identified by the derby
   * query engine in using the HashIndex and thus forcing derby to pick up a
   * query plan as per our requirement. In case of Sql Fabric, the HashIndex
   * will usually refer to the underlying region of the table where fields are
   * stored against the primary key.
   * 
   * @param memIndex
   *          Instance of type OpenMemIndex which can provide information about
   *          the index being considered
   * 
   * @param optimzerEvalutatedCost
   *          double indicating the cost identified by the derby engine. If a
   *          test does not want to modify the value, it should return this
   *          value back.
   * @return double indicating the cost assosciated with the hash index to be
   *         used by derby
   */
  public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost);

  /**
   * Callback invoked during the process of identification of the optimal query
   * plan. This hook can be used to override the cost identified by the derby
   * query engine in using the Local Sorted Index and thus forcing derby to pick
   * up a query plan as per our requirement
   * 
   * @param memIndex
   *          Instance of type OpenMemIndex which can provide information about
   *          the index being considered
   * 
   * @param optimzerEvalutatedCost
   *          double indicating the cost identified by the derby engine. If a
   *          test does not want to modify the value, it should return this
   *          value back.
   * @return double indicating the cost assosciated with the local sorted index
   *         to be used by derby
   */
  public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost);

  /**
   * Callback invoked during the process of identification of the optimal query
   * plan. This hook can be used to override the cost identified by the derby
   * query engine in using the Table Scan and thus forcing derby to pick up a
   * query plan as per our requirement
   * 
   * @param gfContainer
   *          the GemfireContainer object on which table scan is proposed
   * @param optimzerEvalutatedCost
   *          double indicating the cost identified by the derby engine. If a
   *          test does not want to modify the value, it should return this
   *          value back.
   * @return double indicating the cost assosciated with the table scan to be
   *         used by derby
   */
  public double overrideDerbyOptimizerCostForMemHeapScan(
      GemFireContainer gfContainer, double optimzerEvalutatedCost);

  /**
   * This callback gets invoked when CRITICAL_UP memory event happens and
   * SanityManager.DEBUG is true i.e. only for sane builds.
   * 
   * @param listener
   *          singleton instance of the listener.
   */
  public void criticalUpMemoryEvent(GfxdHeapThresholdListener listener);

  /**
   * This callback gets invoked when CRITICAL_DOWN memory event happens and
   * SanityManager.DEBUG is true i.e. only for sane builds.
   * 
   * @param listener
   *          singleton instance of the listener.
   */
  public void criticalDownMemoryEvent(GfxdHeapThresholdListener listener);

  public void estimatingMemoryUsage(String stmtText, Object resultSet);

  public long estimatedMemoryUsage(String stmtText, long memused);

  public void putAllCalledWithMapSize(int size);

  public void afterClosingWrapperPreparedStatement(long wrapperPrepStatementID,
      long wrapperConnectionID);

  /**
   * Callback invoked on each call to
   * {@link ColocationCriteria#updateColocationCriteria(ComparisonQueryInfo)}
   */
  public void updatingColocationCriteria(ComparisonQueryInfo cqi);

  /**
   * Callback invoked during statement execution.
   * 
   * @param stats
   *          StatementStats for the current statement being executed.
   */
  public void statementStatsBeforeExecutingStatement(StatementStats stats);

  /**
   * Clear the state (if any) of the query observer. Some implementations may
   * choose to dump information prior to resetting.
   * 
   * The observer remains usable after this unlike in {@link #close()}.
   */
  public void reset();

  public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
      GenericPreparedStatement gps, String subquery,
      List<Integer> paramPositions);

  public void insertMultipleRowsBeingInvoked(int numElements);

  /**
   * Callback invoked after inserting a key into a local index.
   * 
   * @param key
   *          index key.
   * @param rowLocation
   *          the region entry/row location.
   * @param container
   *          the index container.
   */
  public void keyAndContainerAfterLocalIndexInsert(Object key,
      Object rowLocation, GemFireContainer container);

  /**
   * Callback invoked after deleting a key from a local index.
   * 
   * @param key
   *          index key.
   * @param rowLocation
   *          the region entry/row location.
   * @param container
   *          the index container.
   */
  public void keyAndContainerAfterLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container);
  
  public void keyAndContainerBeforeLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container);

  public void getAllInvoked(int numKeys);
  
  public void getAllGlobalIndexInvoked(int numKeys);
  
  public void getAllLocalIndexInvoked(int numKeys);
  
  public void getAllLocalIndexExecuted();
  
  public void ncjPullResultSetOpenCoreInvoked();
  
  public void getStatementIDs(long stID, long rootID, int stLevel);
  
  public void ncjPullResultSetVerifyBatchSize(int value);
  
  public void ncjPullResultSetVerifyCacheSize(int value);
  
  public void ncjPullResultSetVerifyVarInList(boolean value);

  public void independentSubqueryResultsetFetched(Activation activation,
      ResultSet results);

  /**
   * Invoked when Index Scan is being used and there is a potential of having
   * modified data in transaction, in which case instead of committed
   * RowLocation, uncommitted data needs to be returned.
   * 
   * @param regionEntry
   */
  public void beforeInvokingContainerGetTxRowLocation(RowLocation regionEntry);

  /**
   * Invoked after {@link GfxdPartitionResolver#getRoutObject} method has been
   * invoked.
   */
  public void afterGetRoutingObject(Object routingObject);

  public long overrideUniqueID(long actualUniqueID, boolean forRegionKey);

  /**
   * Invoked before send of a DAP ResultSet from execution node.
   * 
   * If this method returns false then the send will be skipped.
   */
  public boolean beforeProcedureResultSetSend(ProcedureSender sender,
      EmbedResultSet rs);

  /**
   * Invoked before send of a DAP out parameters from execution node.
   * 
   * If this method returns false then the send will be skipped.
   */
  public boolean beforeProcedureOutParamsSend(ProcedureSender sender,
      ParameterValueSet pvs);

  /**
   * Invoked before toData() of {@link ProcedureChunkMessage} is invoked.
   */
  public void beforeProcedureChunkMessageSend(ProcedureChunkMessage message);

  public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
      RegionEntry entry, boolean writeLock);

  public void attachingKeyInfoForUpdate(GemFireContainer container,
      RegionEntry entry);

  /**
   * Overrides decision to avoid merge run.
   * 
   * @return
   */
  public boolean avoidMergeRuns();

  /**
   * This overrides sort buffer size to simulate multiple merge runs.
   * 
   * @param columnOrdering
   * @param sortBufferMax
   * @return
   */
  public int overrideSortBufferSize(ColumnOrdering[] columnOrdering,
      int sortBufferMax);

  public void callAtOldValueSameAsNewValueCheckInSM2IIOp();

  public void onGetNextRowCore(ResultSet resultSet);
  
  public void onGetNextRowCoreOfBulkTableScan(ResultSet resultSet);
  
  public void onGetNextRowCoreOfGfxdSubQueryResultSet(ResultSet resultSet);
  
  public void onDeleteResultSetOpen(ResultSet resultSet);
  
  public void onSortResultSetOpen(ResultSet resultSet);
  
  public void onGroupedAggregateResultSetOpen(ResultSet resultSet);
  
  public void onUpdateResultSetOpen(ResultSet resultSet);
  
  
  public void onUpdateResultSetDoneUpdate(ResultSet resultSet);
  
  
  public void onDeleteResultSetOpenAfterRefChecks(ResultSet resultSet);
  public void onDeleteResultSetOpenBeforeRefChecks(ResultSet resultSet);

  public void setRoutingObjectsBeforeExecution(
      Set<Object> routingKeysToExecute);

  public void beforeDropGatewayReceiver();

  public void beforeDropDiskStore();

  /**
   * Sets whether authentication of a connection from a member (peer or internal
   * connection) was skipped.
   * 
   * @param skipped
   */
  public void memberConnectionAuthenticationSkipped(boolean skipped);

  /**
   * Sets whether authentication of a connection from user was skipped
   * 
   * @param skipped
   */
  public void userConnectionAuthenticationSkipped(boolean skipped);

  /**
   * Checks count(*) queries are converted to Region.size() messages instead of
   * full table scan.
   * 
   * @param fbt
   */
  public void regionSizeOptimizationTriggered(FromBaseTable fbt,
      SelectNode selectNode);
  
  /**
   * Checks count(*) queries are converted to Region.size() messages instead of
   * full table scan.
   * Similar to above #regionSizeOptimizationTriggered() function
   * but called at different place
   */
  public void regionSizeOptimizationTriggered2(SelectNode selectNode);
  
  /**
   * To test bug #47407 ... will invoke this just when it enters insert
   * multiple rows when it enters GemFireContainer.insertMultipleRows
   */
  public void invokeCacheCloseAtMultipleInsert();

  public boolean isCacheClosedForTesting();

  /**
   * Invoked after global index insert is completed 
   */
  public void afterGlobalIndexInsert(boolean posDup);
  
  /**
   * this map is filled during index recovery so that tests can do verifications
   */
  public boolean needIndexRecoveryAccounting();
  
  /**
   * setting the above map 
   */
  public void setIndexRecoveryAccountingMap(THashMap map);

  public void beforeQueryReprepare(GenericPreparedStatement gpst,
      LanguageConnectionContext lcc) throws StandardException;

  /** artificially throw the exception after even successful putAll */
  public boolean throwPutAllPartialException();

  /**
   * after a row a qualified or disqualified in the requalification phase (in
   * non-txn case) by SortedMap2IndexScanController
   * 
   * @param success
   *          true means requalification succeeded, false means it failed and
   *          null means that it was skipped with success since no change in
   *          value was detected
   */
  public void afterIndexRowRequalification(Boolean success,
      CompactCompositeIndexKey ccKey, ExecRow row, Activation activation);
  
  public void beforeRowTrigger(LanguageConnectionContext lcc, ExecRow execRow, ExecRow newRow);
  
  public void afterRowTrigger(TriggerDescriptor trigD, GenericParameterValueSet gpvs);

  /**
   * Invoked just before GlobalHashIndexDeleteOperation is fired
   */
  public void beforeGlobalIndexDelete(); 
  
  public void beforeDeferredUpdate();
  
  public void beforeDeferredDelete();

  public void bucketIdcalculated(int bid);

  public void beforeReturningCachedVal(Serializable globalIndexKey,
      Object cachedVal);

  public void afterPuttingInCached(Serializable globalIndexKey,
      Object result);

  public void afterSingleRowInsert(Object routingObj);

  public void afterQueryPlanGeneration(); 
  
  public void afterLockingTableDuringImport();

  public boolean testIndexRecreate();

  public void testExecutionEngineDecision(QueryInfo queryInfo,
      ExecutionEngineRule.ExecutionEngine engine, String queryText);

  public void regionPreInitialized(GemFireContainer container);
}
