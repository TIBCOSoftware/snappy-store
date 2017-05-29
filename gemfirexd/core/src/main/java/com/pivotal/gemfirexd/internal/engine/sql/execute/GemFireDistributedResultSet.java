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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryStreamingResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColumnQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SecondaryClauseQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.compile.types.DVDSet;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecAggregator;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.UserDataValue;
import com.pivotal.gemfirexd.internal.impl.sql.execute.AggregatorInfo;
import com.pivotal.gemfirexd.internal.impl.sql.execute.AggregatorInfoList;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BasicSortObserver;
import com.pivotal.gemfirexd.internal.impl.sql.execute.GenericAggregator;
import com.pivotal.gemfirexd.internal.impl.sql.execute.IndexColumnOrder;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.impl.store.access.sort.ArraySortScan;
import com.pivotal.gemfirexd.internal.impl.store.access.sort.ArraySorter;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * @author Asif
 * 
 */
public final class GemFireDistributedResultSet extends AbstractGemFireResultSet
    implements NoPutResultSet {

  private static final ExecRow ROUND_COMPLETE = new ValueRow(0);
  
  protected final AbstractGemFireDistributionActivation act;

  protected Collection<?> resultHolderList;

  protected AbstractRSIterator iterator;

  protected final SelectQueryInfo qInfo;

  protected final boolean isForUpdate;

  private final RowFormatter rowFormatter;

  private final boolean objectStore;

  private DataTypeDescriptor distinctAggUnderlyingType;

  protected final ParameterValueSet pvs;

  private final List<GemFireContainer> gfContainers;

  private ExecRow currentRow;

  // statistics data
  // ----------------

  protected transient int rowsReturned;

  // end of statistics data
  // -----------------------

  GemFireDistributedResultSet(Activation act) throws StandardException {
    super(act);
    this.act = (AbstractGemFireDistributionActivation)act;
    assert this.act == this.activation : "Activation instances cannot be different.";
    this.qInfo = (SelectQueryInfo)this.act.qInfo;
    ParameterValueSet _pvs = act.getParameterValueSet();
    if (_pvs != null && _pvs.getParameterCount() > 0) {
      this.pvs = _pvs;
    }
    else {
      this.pvs = null;
    }

    /* creating the rowTemplate that is expected in the 
     * resultset.
     */
    this.rowFormatter = this.qInfo.getRowFormatter();
    this.objectStore = this.qInfo.isObjectStore();
    this.gfContainers = this.qInfo.getContainerList();
    this.isForUpdate = this.qInfo.isSelectForUpdateQuery();
    if (observer != null) {
      observer.createdGemFireXDResultSet(this);
    }
  }

  @Override
  public ExecRow getNextRow() throws StandardException {
    final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;
    
    try {
      super.checkCancellationFlag();      
      if ((this.currentRow = iterator.next()) != null) {
        rowsReturned++;
      }
      this.setCurrentRow(this.currentRow);
      return this.currentRow;
    } catch (Exception ex) {
      this.iterator.finish();
      
      final Region<?, ?> region;
      if (this.gfContainers.size() > 0) {
        region = this.gfContainers.get(0).getRegion();
      }
      else {
        region = null;
      }
      final String method = "GemFireDistributedResultSet::getNextRow";
      GemFireXDUtils.processCancelException(method, ex, null);
      if(ex instanceof StandardException) {
        throw (StandardException)ex;
      }
      throw Misc.processFunctionException(method, ex, null, region);
    } catch (Error e) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
    } finally {
      if (beginTime != 0) {
        nextTime += XPLAINUtil.recordTiming(beginTime);
      }
    }
  }

  @Override
  public final void openCore() throws StandardException {
    // take the DML locks
    for (GemFireContainer container : this.gfContainers) {
      container.open(this.tran, ContainerHandle.MODE_READONLY);
    }
  }

  @Override
  public void setup(Object res, int numMembers) throws StandardException {
    final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;

    final Collection<?> results;
    if (res != null) {
      results = (Collection<?>)res;
    }
    else {
      results = Collections.emptyList();
      this.tran.getLockSpace().rcEnd(null);
    }
    this.resultHolderList = results;
    this.iterator = getIterator(numMembers);
    if (observer != null) {
      observer.beforeORM(this.activation, this);
    }

    if (beginTime != 0) {
      openTime += XPLAINUtil.recordTiming(beginTime);
    }
  }

  @Override
  public final void setupRC(final GfxdResultCollector<?> rc)
      throws StandardException {
    assert rc != null: "expected non-null ResultCollector";
    final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;

    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "setupRC: setting up ResultCollector " + rc);
    }
    rc.setupContainersToClose(this.gfContainers, this.tran);

    if (beginTime != 0) {
      openTime += XPLAINUtil.recordTiming(beginTime);
    }
  }

  @Override
  public final void reset(final GfxdResultCollector<?> rc)
      throws StandardException {
    // release and re-acquire the container locks
    // this is required especially for streaming RC that will
    // release the container locks regardless so container locks
    // have to be re-acquired (#39808)
    if (rc != null) {
      closeContainers();
      setupRC(rc);
      openCore();
    }
  }

  @Override
  public void finishResultSet(final boolean cleanupOnError) throws StandardException {
    try {
      closeContainers();
    } finally {
     
      if(this.iterator != null) {
        this.iterator.finish();
      }
      this.act.resetProjectionExecRow();
      if (observer != null) {
        observer.afterORM(this.activation, this);
      }
    }
  }

  private final void closeContainers() {
    // release the DML locks
    for (GemFireContainer container : this.gfContainers) {
      container.closeForEndTransaction(this.tran, false);
    }
  }
  
  @Override
  public long getTimeSpent(int type, int timeType) {
    if (timeType == ResultSet.ALL) {
      return PlanUtils.getTimeSpent(0, openTime, nextTime, closeTime, timeType) - (iterator != null ? iterator.getTimeSpent(ENTIRE_RESULTSET_TREE) : 0);
    }
    else {
      return PlanUtils.getTimeSpent(0, openTime, nextTime, closeTime, timeType);      
    }
  }
  

  @Override
  public boolean returnsRows() {
    return true;
  }

  @Override
  public void clearCurrentRow() {
    this.currentRow = null;
  }

  final RowFormatter getRowFormatter() {
    assert this.rowFormatter != null || this.objectStore || qInfo.isTableVTI();
    return this.rowFormatter;
  }

  final DataTypeDescriptor getDistinctAggUnderlyingType() {
    return distinctAggUnderlyingType;
  }

  private AbstractRSIterator getIterator(int numMembers)
      throws StandardException {
    AbstractRSIterator retIter = null;

    // if getting reply from just one member then use SequentialIterator
    // and skip all other iterator hierarchy (except for a few special
    //   cases like intersect/count distinct)
    // TODO: right now skipping too many cases due to failures; see if
    // some of them can be optimized to use SequentialIterator
    final int queryType = this.qInfo.getQueryFlag();
    if (numMembers == 1) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "creating a single node optimized iterator");
      }
      
      // Special Optimization for All Replicated case
      if (this.allTablesReplicatedOnRemote) {
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireDistributedResultSet.getIterator - " +
              "allTablesReplicatedOnRemote is True. " +
              "Select Plain vanilla SequentialIterator");
        }
        return new SequentialIterator();
      }
      
      if (!this.qInfo.hasIntersectOrExceptNode()) {
        if ((queryType & (QueryInfo.HAS_GROUPBY
            | QueryInfo.HAS_FETCH_ROWS_ONLY)) == 0) {
          if ((queryType & (QueryInfo.HAS_DISTINCT
              | QueryInfo.HAS_DISTINCT_SCAN)) != 0) {
            return new SequentialIterator(this.qInfo.getDistinctQI());
          }
          else if ((queryType & QueryInfo.HAS_ORDERBY) != 0) {
            return new SequentialIterator(this.qInfo.getOrderByQI());
          }
          else {
            return new SequentialIterator();
          }
        }
      }
    }

    if ((queryType & QueryInfo.HAS_DISTINCT) != 0) {
      retIter = new OrderedIterator(qInfo.getDistinctQI(), true /*distinct*/,
      // TODO: soubhik require to enhance where Ordered one should be guaranteed
          // to be on demand fetch.
          // tests concMultiTablesUniq.conf query
          // 'select distinct sid from trade.portfolio where (qty >=? and
          // subTotal >= ?) and tid =?'
          // right now making it functionally complete. some time we have to
          // anyway move to file based sorting.
          true /*fetch on eager*/, qInfo.isOuterJoinSpecialCase(),
          false /*intersect operator*/);
    }
    else if ((queryType & QueryInfo.HAS_DISTINCT_SCAN) != 0) {
      retIter = new OrderedIterator(qInfo.getDistinctQI(), true /*distinct*/,
          true /*fetch eager*/, qInfo.isOuterJoinSpecialCase(),
          false /*intersect operator*/);
    }
    else if ((queryType & QueryInfo.HAS_GROUPBY) != 0) {
      distinctAggUnderlyingType = qInfo.getGroupByQI()
          .getDistinctAggregateUnderlyingType();
      if (qInfo.getGroupByQI().doReGrouping()) {
        retIter = new GroupedIterator(new OrderedIterator(qInfo.getGroupByQI(),
            false /*non-distinct*/, true /*fetch eager*/, qInfo
                .isOuterJoinSpecialCase(), false /*intersect operator*/));
      }
      else {
        // Neeraj: check if case of outer join and then decide
        // whether source is Sequential iterator or outer join iterator
        AbstractRSIterator internalItr;
        if (this.qInfo.isOuterJoinSpecialCase()) {
          internalItr = new SpecialCaseOuterJoinIterator(qInfo.getGroupByQI());
        }
        else {
          internalItr = new SequentialIterator(qInfo.getGroupByQI());
        }
        retIter = new GroupedIterator(internalItr);
      }
    }

    if ((queryType & QueryInfo.HAS_ORDERBY) != 0) {

      if (retIter != null) {
        retIter = new OrderedIterator(retIter, qInfo.getOrderByQI()
            .getColumnOrdering(), false /*non-distinct*/, true /*fetch eager*/);
      }      
      else if (qInfo.hasIntersectOrExceptNode()) {
        // [vivek] Handle intersect Queries
        // TODO - All 3 cases above i.e. distinct-s and group by need
        // to be taken care of separately. In presence of intersect,
        // they MAY have intersect as source... 
        retIter = new SetOperatorIterator(new OrderedIterator(qInfo.getOrderByQI(),
            false /*non-distinct*/, false /*fetch on demand*/, qInfo
            .isOuterJoinSpecialCase(), 
            qInfo.hasIntersectOrExceptNode() /*intersect operator*/),
            qInfo.getNameOfIntersectOrExceptOperator());
      } 
      else {
        retIter = new OrderedIterator(qInfo.getOrderByQI(),
            false /*non-distinct*/, false /*fetch on demand*/, qInfo
                .isOuterJoinSpecialCase(), false /*intersect operator*/);
      }
    }

    // Neeraj: check if case of outer join and then decide
    // whether source is Sequential iterator or outer join iterator
    if (retIter == null) {
      if (qInfo.isOuterJoinSpecialCase()) {
        retIter = new SpecialCaseOuterJoinIterator();
      }
      else {
        retIter = new SequentialIterator();
      }

      // TODO change to appropriate flag for intersection
      SanityManager.ASSERT(qInfo.hasIntersectOrExceptNode() == false,
      "Intersection has by default 'Order By', so must have an iterator by now");
    }

    if ((queryType & QueryInfo.HAS_FETCH_ROWS_ONLY) != 0) {
      assert retIter != null;
      retIter = new RowCountIterator(retIter, qInfo.getRowCountOffSet(),
          qInfo.getRowCountFetchFirst(), qInfo.isDynamicOffset(),
          qInfo.isDyanmicFetchFirst());
    }

    retIter.initialize();
    return retIter;
  }

  /**
   * This interface encapsulates various iteration strategies required while
   * servicing various iteration models of the distributed resultset viz.
   * sequential, ordered, grouped, aggregated etc .
   * 
   * @author soubhikc
   * 
   */
  protected interface RSIterator {

    /**
     * Returns the next element in the iteration. .
     * 
     * @return the next element in the iteration.
     * @throws StandardException
     */
    ExecRow next() throws StandardException;

    long getTimeSpent(int type);
    
    /**
     * Returns the expected row for the current query.
     */
    ExecRow getExpectedRow();

    /**
     * 
     * @param visitor
     */
    void accept(IteratorStatisticsVisitor visitor);

    /**
     * Estimates the memory used 
     * @return
     * @throws StandardException 
     */
    long estimateMemoryUsage() throws StandardException;
   
    /**
     * Post operation close the iterator.
     * @throws StandardException
     */
    public void finish() throws StandardException;
  }

  /**
   * capturing statistical data common to all iterators.
   * 
   * @author soubhikc
   * 
   */
  protected abstract class AbstractRSIterator implements RSIterator {

    // statistics data
    // ----------------
    
    // to hold onto RHs if stats are on.
    protected final List<ResultHolder> statsRHs;
   
    AbstractRSIterator() {
     
      if(statisticsTimingOn) {
        this.statsRHs = new ArrayList<ResultHolder>();
      }
      else {
        this.statsRHs = Collections.emptyList();
      }
    }
    
    public transient int rowsReturned;

    protected transient long nextTime;

    protected transient short nestedTimingIndicator;

    protected abstract long getCurrentTimeSpent();
    
    public long getTimeSpent(int type) {
      if (type == ENTIRE_RESULTSET_TREE) {
        return nextTime;
      }
      
      return getCurrentTimeSpent();
    }

    
    // end of statistics data
    // -----------------------

    public void initialize() throws StandardException {
    }

    protected final RowFormatter getRowFormatter(
        final SecondaryClauseQueryInfo sqinfo) {
      final RowFormatter rf;
      if (sqinfo != null && (rf = sqinfo.getRowFormatter()) != null) {
        return rf;
      }
      return GemFireDistributedResultSet.this.getRowFormatter();
    }

    public void close() {
    }

    protected void freeOffHeapReferences(ResultHolder holder)
        throws StandardException {
      try {
        if (holder != null) {
          holder.freeOffHeapForCachedRowsAndCloseResultSet();
        }
      } catch (Throwable ignore) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager
              .DEBUG_PRINT(
                  GfxdConstants.TRACE_RSITER,
                  "AbstractRSIterator::freeOffHeapReferences: Problem in consuming the results. Ignoring",
                  ignore);
        }
      }
    }

  }

  /**
   * Iterates over List of ResultHolders sequentially consuming each
   * ResultHolder completely.
   * 
   * @author Asif
   * @author soubhikc
   * 
   */
  protected final class SequentialIterator extends AbstractRSIterator {

    private final Iterator<?> RSIterator;
    private final SecondaryClauseQueryInfo sqinfo;

    private ResultHolder active;
    private boolean activeHasProjection;

    private ExecRow templateValueRow;
    private ExecRow templateCompactRow;
    private ExecRow expectedRow;
    

    private boolean isAllDone = false;
   

    SequentialIterator() throws StandardException {
      this(null);
    }

    SequentialIterator(SecondaryClauseQueryInfo sqinfo) throws StandardException {
      super();
      this.RSIterator = resultHolderList.iterator();
      this.sqinfo = sqinfo;

      final ExecRow expectedRow;
      if (sqinfo != null) {
        expectedRow = sqinfo.getInComingProjectionExecRow().getNewNullRow();
      }
      else {
        expectedRow = act.getProjectionExecRow();
      }
      if (expectedRow instanceof AbstractCompactExecRow) {
        this.templateCompactRow = expectedRow;
      }
      else {
        this.templateValueRow = expectedRow;
      }
      
      while (this.RSIterator.hasNext()) {
        final ResultHolder next = (ResultHolder)this.RSIterator.next();
        boolean iterOk = false;
        try {
        if (next != null && next.hasNext(activation)) {
          this.active = next;
          this.activeHasProjection = next.hasProjectionFromRemote();
          if(statisticsTimingOn) {
            statsRHs.add(next);
          }
          this.active.setRowFormatter(getRowFormatter(sqinfo),
              objectStore || qInfo.isTableVTI());
          this.active
              .setDistinctAggUnderlyingType(GemFireDistributedResultSet.this
                  .getDistinctAggUnderlyingType());
          iterOk = true;
          break;
        }
        iterOk = true; 
        }finally {
          if(!iterOk) {
            this.finish();
          }
        }
      }
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Creating SequentialIterator");
        }
      }
    }            

    public ExecRow next() throws StandardException {

      
      if (isAllDone) {
        return null;
      }

      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;
      try {
        if (this.active == null) {
          this.isAllDone = true;
          return null;
        }

        if (this.active.hasNext(activation)) {
          this.expectedRow = extractRowFromActiveResultHolder();
          rowsReturned++;
          return expectedRow;
        }

        this.active = null;
        while (this.RSIterator.hasNext()) {
          final ResultHolder next = (ResultHolder) this.RSIterator.next();
          if (next.hasNext(activation)) {
            setActiveResultHolder(next);
            break;
          }
        }
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Returning next active ResultHolder " + this.active);
        }
        return next();
      }catch(StandardException se) {
        this.finish();
        throw se;
      }catch(RuntimeException re) {
        this.finish();
        throw re;
      }
      
      finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    private void setActiveResultHolder(final ResultHolder next) {
      this.active = next;
      this.activeHasProjection = next.hasProjectionFromRemote();
      if(statisticsTimingOn) {
        statsRHs.add(next);
      }
      this.active.setRowFormatter(getRowFormatter(sqinfo),
          objectStore || qInfo.isTableVTI());
      this.active
          .setDistinctAggUnderlyingType(GemFireDistributedResultSet.this
              .getDistinctAggUnderlyingType());
      this.isAllDone = false;
    }

    private ExecRow extractRowFromActiveResultHolder() throws StandardException {
      ExecRow row = null;
      if (this.activeHasProjection) {
        if (this.templateValueRow == null) {
          this.templateValueRow = new ValueRow(
              this.templateCompactRow.getRowArrayClone());
        }
        row = this.active
            .getNext(this.templateValueRow, activation);
      }
      else {
        row = templateCompactRow = this.active.getNext(
            templateCompactRow, activation);
      }
      if (GemFireXDUtils.isOffHeapEnabled()
          && this.active.isGenuinelyLocallyExecuted() && row != null) {
        @Unretained Object bs = row.getByteSource();
        if (bs instanceof OffHeapByteSource) {
          GemFireDistributedResultSet.this.tran
              .addByteSource((OffHeapByteSource)bs);
        }
        else {
          GemFireDistributedResultSet.this.tran.addByteSource(null);
        }
      }
      if (GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
            "Returning next row from active ResultHolder "
                + row);
      }
      return row;
    }

    public ExecRow getExpectedRow() {
      return this.expectedRow;
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      
      visitor.setNumberOfChildren(statsRHs.size());
      
      visitor.visit(this);
      
      Iterator<ResultHolder> rhi = statsRHs.iterator();

      while (rhi.hasNext()) {
        visitor.visit(rhi.next());
      }
      
    }
    
    @Override
    public long getCurrentTimeSpent() {
      Iterator<ResultHolder> rhi = statsRHs.iterator();

      long totalChildTime = 0;
      while (rhi.hasNext()) {
        ResultHolder rh = rhi.next();
        totalChildTime += (rh.ser_deser_time + rh.process_time + rh.throttle_time);
      }

      return nextTime - totalChildTime;
    }

    @Override
    public long estimateMemoryUsage() throws StandardException {
      if (observer != null) {
        // "this" passed here instead of ResultSet is deliberate since the
        // observer in tests depends on it to determine that iteration has
        // started using SequentialIterator
        observer.estimatingMemoryUsage(act.getStatementText(), this);
      }
      // for a streaming collector, the iterator is a one time affair
      Iterator<?> iterator;
      if (resultHolderList instanceof GfxdQueryStreamingResultCollector) {
        iterator = ((GfxdQueryStreamingResultCollector)resultHolderList)
            .reusableIterator();
      }
      else {
        iterator = resultHolderList.iterator();
      }
      long memory = 0L;
      while(iterator.hasNext()) {
        Object res = iterator.next();
        if (res instanceof  ResultHolder) {
          memory += ((ResultHolder)res).estimateMemoryUsage();
        }
      }
      memory += statsRHs.size();
      return memory;
    }

    @Override
    public void finish() throws StandardException {
      // exhaust active result holder
      if (GemFireXDUtils.isOffHeapEnabled()) {
        try {
          if (this.active != null && this.active.isGenuinelyLocallyExecuted()) {
            this.freeOffHeapReferences(this.active);
          } else {
            while (this.RSIterator.hasNext()) {
              final ResultHolder next = (ResultHolder) this.RSIterator.next();
              if (next != null && next.isGenuinelyLocallyExecuted()) {
                this.freeOffHeapReferences(next);
                break;
              }
            }
          }
        } catch (Throwable ignore) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_RSITER,
                    "SequentialIterator::finish: Problem in consuming the results. Ignoring",
                    ignore);
          }
        }
      }
    }

  } // End of SequentialIterator

  /**
   * Iterates over RH List consuming one entry from each ResultHolders. Call to
   * .next() returns null when one round of iteration completes. hasNext()
   * indicates whether there are any more rows available or not.
   * 
   * @author soubhikc
   * 
   */
  protected final class RoundRobinIterator extends AbstractRSIterator {

    private ExecRow templateValueRow;
    private ExecRow templateCompactRow;
    private ExecRow expectedRow;

    private Iterator<?> rhlistiter = resultHolderList.iterator();

    private final ArrayList<ResultHolder> rhlist;
    private final SecondaryClauseQueryInfo sqinfo;

    private ResultHolder active = null;

    private byte flags;

    private static final byte USING_RHLIST = 0x01;

    @SuppressWarnings("unchecked")
    public RoundRobinIterator(SecondaryClauseQueryInfo sqinfo) throws StandardException {
      super();
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Creating RoundRobinIterator");
        }
      }
      this.sqinfo = sqinfo;
      final ExecRow templateRow;
      if (sqinfo != null) {
        templateRow = sqinfo.getInComingProjectionExecRow().getNewNullRow();
      }
      else {
        templateRow = act.getProjectionExecRow();
      }
      if (templateRow instanceof AbstractCompactExecRow) {
        this.templateCompactRow = templateRow;
      }
      else {
        this.templateValueRow = templateRow;
      }
      if (resultHolderList instanceof ArrayList) {
        rhlist = (ArrayList<ResultHolder>)resultHolderList;
        this.flags = USING_RHLIST;
      }
      else {
        rhlist = new ArrayList<ResultHolder>();
      }
    }

    private boolean isRHListEmpty() {
      return (this.flags & USING_RHLIST) != 0 && rhlist.isEmpty();
    }

    /**
     * @see RSIterator#next()
     */
    public ExecRow next() throws StandardException {

      
      if (isRHListEmpty()) {
        return null;
      }
      
      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;
      try {
        // lets forget the last RH used when
        // we need to fetch round robin RHs.
        active = null;
        /*
         * null here implies one round of RHList visit is
         * complete. hasNext() should be used to infer more
         * rows are there or not. 
         */
        if (!rhlistiter.hasNext()) {
          rhlistiter = rhlist.iterator();
          this.flags = GemFireXDUtils.set(this.flags, USING_RHLIST);
          // return dummy object to indicate one round complete
          return ROUND_COMPLETE;
        }

        while (true) {

          if (isRHListEmpty()) {
            return null;
          }
          /* move to first ResultHolder in case 
           * the last ResultHolder gets removed and
           * we are at end of the iterator
           */
          if (!rhlistiter.hasNext()) {
            if (rhlist.isEmpty()) {
              return null;
            }
            rhlistiter = rhlist.iterator();
            this.flags = GemFireXDUtils.set(this.flags, USING_RHLIST);
          }

          active = (ResultHolder)rhlistiter.next();
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "Picking next round robin ResultHolder " + this.active);
          }
          active.setRowFormatter(getRowFormatter(sqinfo),
              objectStore || qInfo.isTableVTI());
          active.setDistinctAggUnderlyingType(GemFireDistributedResultSet.this
              .getDistinctAggUnderlyingType());
          boolean iterOk = false;
          try {
          if (!active.hasNext(activation)) {
            iterOk = true;
            // lets eliminate RHs consumed completely.
            if (GemFireXDUtils.isSet(this.flags, USING_RHLIST)) {
              rhlistiter.remove();
            }
            if (statisticsTimingOn) {
              statsRHs.add(this.active);
            }
            active = null;
            continue;
          }
          iterOk = true;
          }finally {
            if(!iterOk) {
              this.finish();
            }
          }
          if (!GemFireXDUtils.isSet(this.flags, USING_RHLIST)) {
            rhlist.add(active);
          }
          if (active.hasProjectionFromRemote()) {
            if (templateValueRow == null) {
              this.templateValueRow = new ValueRow(
                  this.templateCompactRow.getRowArrayClone());
            }
            expectedRow = active.getNext(templateValueRow, activation);
          }
          else {
            expectedRow = templateCompactRow = active.getNext(
                templateCompactRow, activation);
          }
          if (expectedRow != null) {
            @Unretained Object bs = expectedRow.getByteSource();
            if (bs instanceof OffHeapByteSource) {
              GemFireDistributedResultSet.this.tran
                  .addByteSource((OffHeapByteSource)bs);
            }
            else {
              GemFireDistributedResultSet.this.tran.addByteSource(null);
            }
          }
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "Picking next row from round robin ResultHolder "
                    + this.expectedRow + " RegionAndKeys = "
                    + (this.expectedRow != null ? this.expectedRow
                        .getAllRegionAndKeyInfo() : "null"));
          }
          rowsReturned++;
          return expectedRow;

        } // end of RHList iteration
      } finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    @Override
    public ExecRow getExpectedRow() {
      return this.expectedRow;
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      visitor.setNumberOfChildren(statsRHs.size());
      
      visitor.visit(this);
      
      final Iterator<ResultHolder> rhi = statsRHs.iterator();

      while (rhi.hasNext()) {
        final ResultHolder rh = rhi.next();
        rh.assertNoData();
        visitor.visit(rh);
      }
    }

    @Override
    public long getCurrentTimeSpent() {
      Iterator<ResultHolder> rhi = statsRHs.iterator();

      long totalChildTime = 0;
      while (rhi.hasNext()) {
        ResultHolder rh = rhi.next();
        totalChildTime += (rh.ser_deser_time + rh.process_time + rh.throttle_time);
      }

      return nextTime - totalChildTime;
    }
    
    @Override
    public long estimateMemoryUsage() throws StandardException {
      // for a streaming collector, the iterator is a one time affair
      Iterator<?> iterator;
      if (resultHolderList instanceof GfxdQueryStreamingResultCollector) {
        iterator = ((GfxdQueryStreamingResultCollector)resultHolderList)
            .reusableIterator();
      }
      else {
        iterator = resultHolderList.iterator();
      }
      long memory = 0L;
      while(iterator.hasNext()) {
        Object res = iterator.next();
        if (res instanceof ResultHolder) {
          memory += ((ResultHolder)res).estimateMemoryUsage();
        }
      }
      if(statisticsTimingOn) {
        memory += statsRHs.size();
      }
      return memory;
    }

    @Override
    public void finish() throws StandardException {
      // exhaust active result holder
      if (GemFireXDUtils.isOffHeapEnabled()) {
        try {
          if (this.active != null && this.active.isGenuinelyLocallyExecuted()) {
            this.freeOffHeapReferences(this.active);
          } else {
            if (isRHListEmpty()) {
              return;
            }
            if (GemFireXDUtils.isSet(this.flags, USING_RHLIST)) {
              this.rhlistiter = this.rhlist.iterator();
            }
            int loopCount = 0;
            outer: do {
              if (loopCount == 1) {
                this.rhlistiter = this.rhlist.iterator();
                this.flags = GemFireXDUtils.set(this.flags, USING_RHLIST);
              }
              while (this.rhlistiter.hasNext()) {
                final ResultHolder next = (ResultHolder) this.rhlistiter.next();
                if (next != null && next.isGenuinelyLocallyExecuted()) {
                  this.freeOffHeapReferences(next);
                  break outer;
                }
              }
              ++loopCount;
            } while (!GemFireXDUtils.isSet(this.flags, USING_RHLIST));
          }
        } catch (Throwable ignore) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_RSITER,
                    "RoundRobinIterator::finish: Problem in consuming the results. Ignoring",
                    ignore);
          }
        }
      }
    }
  }

  protected final class SpecialCaseOuterJoinIterator extends AbstractRSIterator {

    // RoundRobinIterator source;
    final SequentialIterator source;

    // TODO: See if it can be replaced with a TreeMap so that lookups may become
    // easy?
    LinkedHashMap lhm;

    private Map<String, Object> driverTablesMap = null;

    private Iterator<?> iter;

    private Iterator<?> activeListItr;

    public SpecialCaseOuterJoinIterator(SecondaryClauseQueryInfo sqinfo)
        throws StandardException {
      super();
      // source = new RoundRobinIterator(expectedRow);
      source = new SequentialIterator(sqinfo);
      initializeDriver();
    }

    private void initializeDriver() {
      // List<Region> list = qInfo.getOuterJoinRegions();
      // Region rgn = list.get(0);
      this.driverTablesMap = qInfo.getDriverRegionsForOuterJoins();
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "GemfireDistributedResultset::OJ_Itr::initializeDriver:driver="
                + this.driverTablesMap);
      }
    }

    public SpecialCaseOuterJoinIterator() throws StandardException {
      // source = new RoundRobinIterator();
      super();
      source = new SequentialIterator();
      initializeDriver();
    }
    
    private int getPRCount(TreeSet<RegionAndKey> allInfos, ExecRow erow) {
      int prCount = 0;
      Iterator<RegionAndKey> itr = allInfos.iterator();
      if (GemFireXDUtils.TraceOuterJoin) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
            "GemfireDistributedResultset::SpecialcaseOuteroinIterator::getPRCount::"
                + "finding pr count for erow: " + erow);
      }
      while (itr.hasNext()) {
        RegionAndKey tmprak = itr.next();
        if (!tmprak.isReplicatedRegion()) {
          ++prCount;
        }
        if (GemFireXDUtils.TraceOuterJoin) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
              "GemfireDistributedResultset::SpecialcaseOuteroinIterator::getPRCount::"
                  + "rak is: " + tmprak);
        }
      }
      return prCount;
    }

    Set<RegionAndKey> getDriverRegionKey(TreeSet<RegionAndKey> allInfos) {
      @SuppressWarnings("unchecked")
      Set<RegionAndKey> driverKeys = new THashSet(allInfos.size());
      Iterator<RegionAndKey> itr = allInfos.iterator();
      while (itr.hasNext()) {
        RegionAndKey tmprak = itr.next();
        if (tmprak.isReplicatedRegion()) {
          // Misc.getCacheLogWriter().info(
          // "KN: tmprak region name: " + tmprak.getRegionName()
          // + " and driverregions key set: "
          // + this.driverTablesMap.keySet());
          if (this.driverTablesMap.containsKey(tmprak.getRegionName())) {
            driverKeys.add(tmprak);
          }
        }
      }
      assert driverKeys.size() != 0;
      return driverKeys;
    }

    @SuppressWarnings("unchecked")
    void populateHashMap() throws StandardException {
      if (GemFireXDUtils.TraceOuterJoin) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
            "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                + "::populateHashMap: started with driverTablesMap: "
                + this.driverTablesMap);
      }
      int keysize = 0;
      if (this.lhm == null) {
        this.lhm = new LinkedHashMap();
        ExecRow erow = null; 
        while ( (erow = this.source.next()) != null) {
          if(erow == ROUND_COMPLETE) {
            continue;
          }
          ExecRow newErow = null;
          final TreeSet<RegionAndKey> allinfos;
          if (erow != null) {
            allinfos = erow.getAllRegionAndKeyInfo();
            if (GemFireXDUtils.TraceOuterJoin) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                  "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                      + "::populateHashMap: tuple is =" + erow);
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                  "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                      + "::populateHashMap: raks of the above tuple =" + allinfos);
            }
            if (allinfos != null) {
              newErow = erow.getClone();
              erow.clearAllRegionAndKeyInfo();
            }
            else {
              continue;
            }
          }
          else {
            continue;
          }
          Set<RegionAndKey> driverKeys = getDriverRegionKey(allinfos);

          int prCnt = getPRCount(allinfos, erow);
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                    + "::populateHashMap: driverKeys are: " + driverKeys + 
                    " and pr cnt is: " + prCnt);
          }
          if (allinfos.size() > keysize) {
            keysize = allinfos.size();
          }
          Object oldObj = this.lhm.get(driverKeys);
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                    + "::populateHashMap: old object for driverKeys: " + driverKeys +
                    " is " + oldObj);
          }
          if (oldObj != null) {
            if (oldObj instanceof List) {
              List prsetsExecRows = (List)oldObj;
              if (prCnt == 0) {
                if (GemFireXDUtils.TraceOuterJoin) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "GemfireDistributedResultset::OJ_Itr::popHashMap: "
                          + "Ignoring new row = " + newErow + 
                          " as a valid row containing pr row is already there");
                }
                // Ignored because if no pr data is there then this result is
                // is a duplicate one as with the same driverkeys the one with
                // no corresponding pr column has already been processed.
                continue;
              }
              else {
                if (GemFireXDUtils.TraceOuterJoin) {
                  SanityManager.DEBUG_PRINT(
                      GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "GemfireDistributedResultset::OJ_Itr::popHashMap:"
                          + "Old object List, match level > 0 "
                          + "adding new row = " + newErow + " for driverKeys: "
                          + driverKeys);
                }
                prsetsExecRows.add(newErow);
              }
            }
            else {
              assert oldObj instanceof ExecRow;

              if (prCnt == 0) {
                if (GemFireXDUtils.TraceOuterJoin) {
                  SanityManager.DEBUG_PRINT(
                      GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "GemfireDistributedResultset::OJ_Iterator::popHashMap: "
                          + "Ignoring tuple =" + newErow
                          + " because pr cnt is 0 and we have an old object "
                          + oldObj);
                }
                continue;
              }
              else {
                if (GemFireXDUtils.TraceOuterJoin) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "GemfireDistributedResultset::OJ_Iterator::popHashMap: "
                          + "Replacing " + oldObj + " with " + newErow);
                }
                List temp = new ArrayList(5);
                temp.add(newErow);
                if (GemFireXDUtils.TraceOuterJoin) {
                  SanityManager.DEBUG_PRINT(
                      GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                          + "::populateHashMap: putting_two driverKeys : "
                          + driverKeys + " values: " + temp + " in lhm");
                }
                this.lhm.put(driverKeys, temp);
              }

            }
          }
          else {
            Object toPut = null;
            if (prCnt == 0) {
              toPut = newErow;
            }
            else {
              List temp = new ArrayList(5);
              temp.add(newErow);
              toPut = temp;
            }
            if (GemFireXDUtils.TraceOuterJoin) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                  "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                      + "::populateHashMap: putting_one driverKeys : " + driverKeys + 
                      " values: " + toPut + " in lhm");
            }
            this.lhm.put(driverKeys, toPut);
          }
        }
        if (GemFireXDUtils.TraceOuterJoin) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
              "GemfireDistributedResultset::SpecialcaseOuteroinIterator"
                  + "::populateHashMap:Final Map is=" + this.lhm);
        }
        this.iter = this.lhm.entrySet().iterator();
        activeListItr = null;
      }
      // dumpEverythingInLhm();
    }

    /*
    private void dumpEverythingInLhm() {
      Iterator<?> it = this.lhm.entrySet().iterator();
      while (it.hasNext()) {
        Entry e = (Entry)it.next();
        Object val = e.getValue();
        Object key = e.getKey();
        if (val instanceof List) {
          Iterator<?> listitr = ((List)val).iterator();
          while (listitr.hasNext()) {
            ExecRowWithPrSet o = (ExecRowWithPrSet)listitr.next();
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "KN: rep keys, prkeys, row: " + key + ", " + o.prraks + ", "
                    + o.prerow);
          }
        }
        else {
          assert val instanceof ExecRowWithPrSet;
          ExecRowWithPrSet o = (ExecRowWithPrSet)val;
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "KN: rep keys, prkeys, row: " + key + ", " + o.prraks + ", "
                  + o.prerow);
        }
      }
    }
    */

    public ExecRow getExpectedRow() {
      return this.source.getExpectedRow();
    }

    private boolean hasNext() throws StandardException {
      if (this.lhm == null) {
        populateHashMap();
      }
      if (activeListItr == null) {
        return this.iter.hasNext();
      }
      else if (activeListItr.hasNext()) {
        return true;
      }
      else {
        activeListItr = null;
        return this.iter.hasNext();
      }
    }

    public ExecRow next() throws StandardException {
      
      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;
      try {
        if (!this.hasNext()) {
          return null;
        }
        if (activeListItr != null) {
          ExecRow tmprow = (ExecRow)activeListItr.next();
          rowsReturned++;
          return tmprow;
        }

        Entry e = (Entry)this.iter.next();
        Object obj = e.getValue();
        if (obj instanceof List) {
          List currlist = (List)obj;
          activeListItr = currlist.iterator();
          ExecRow tmprow = (ExecRow)activeListItr.next();
          rowsReturned++;
          return tmprow;
        }
        assert obj instanceof ExecRow;
        return (ExecRow)obj;
      } finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      visitor.setNumberOfChildren(1);
      
      visitor.visit(this);
      
      source.accept(visitor);
    }
    
    @Override
    public long getCurrentTimeSpent() {
      return nextTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public long estimateMemoryUsage() throws StandardException {
      long memory = 0l;
      
      if (this.lhm != null) {
        try {
          final Iterator iter = this.lhm.entrySet().iterator();
          while (iter.hasNext()) {
            final Map.Entry e = (Map.Entry)iter.next();
            final Object k = e.getKey();
            final Object v = e.getValue();
            assert k instanceof RegionAndKey;
            assert v instanceof List || v instanceof ExecRow;
            memory += ((RegionAndKey)k).estimateMemoryUsage();
            if (v instanceof List) {
              Iterator i = ((List)v).iterator();
              while (i.hasNext()) {
                final Object li = i.next();
                assert li instanceof RegionAndKey;
                memory += ((RegionAndKey)li).estimateMemoryUsage();
              }
            }
            else {
              memory += ((RegionAndKey)v).estimateMemoryUsage();
            }
          }
        } catch (ConcurrentModificationException ignore) {
        } catch (NoSuchElementException ignore) {
        }
      }
      
      memory += source.estimateMemoryUsage();
      return memory;
    }

    @Override
    public void finish() throws StandardException {
      source.finish();
    }

  }

  /**
   * SetOperatorIterator - Perform intersection or except.
   * Note: Possibly always get ValueRows, except getExpectedRow
   */
  protected final class SetOperatorIterator extends AbstractRSIterator {

    public static final int LEFT_CHILD = 0;
    public static final int RIGHT_CHILD = 1;

    public static final int INTERSECT_OP = 1;
    public static final int EXCEPT_OP = 2;
    public static final int INTERSECT_ALL_OP = 3;
    public static final int EXCEPT_ALL_OP = 4;
    private final int opType;

    final private OrderedIterator sourceOrderedIter;

    private ExecRow currentRow = null;
    private ExecRow previousRow = null;

    final private OrderedIterator.SetOrderedRow wrapCurrentRowToCompare;
    final private OrderedIterator.SetOrderedRow wrapPreviousRowToCompare;

    private boolean firstCall = true;
    private int previousRowDuplicates = 0;
    private final int[] perChildDuplicatesCount = {0, 0};

    // For debug assertion
    private boolean compareReturnPositive;
    private boolean compareReturnNegative;

    SetOperatorIterator(OrderedIterator orderedIter, String operatorName)
        throws StandardException {
      super();
      
      sourceOrderedIter = orderedIter;
      wrapCurrentRowToCompare = sourceOrderedIter.new SetOrderedRow();
      wrapPreviousRowToCompare = sourceOrderedIter.new SetOrderedRow();

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              " GemfireDistributedResultset::SetOperatorIterator "
              + "Creating SetOperatorIterator with source " 
              + sourceOrderedIter.getClass().getSimpleName() 
              + " Operator = " + operatorName);
        }
      }
      
      if (operatorName.equals("INTERSECT")) {
        opType = INTERSECT_OP;
      }
      else  if (operatorName.equals("EXCEPT")) {
        opType = EXCEPT_OP;
      } 
      else if (operatorName.equals("INTERSECTALL")) {
        opType = INTERSECT_ALL_OP;
      }
      else  if (operatorName.equals("EXCEPTALL")) {
        opType = EXCEPT_ALL_OP;
      } 
      else {
        opType = 0;
        SanityManager.ASSERT(true, "Operator name doesn't matches Intersect or Except");
      }
    }

    @Override
    public void initialize() throws StandardException {
      this.sourceOrderedIter.initialize();
    }

    private void setPreviousRow(ExecRow prow) throws StandardException {
      SanityManager.ASSERT(previousRowDuplicates == 0, " Duplicates should be zero ");

      this.previousRow = prow;
      wrapPreviousRowToCompare.setRow(previousRow);
      if  (previousRow != null) {
        initializeDuplicatesCount();
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              " SetOperatorIterator::setPreviousRow: new previous row " 
              + (this.previousRow == null ? "null" : wrapPreviousRowToCompare.toString()));
        }
      }
    }
    
    private void cleanUpBeforeReturn() {
      clearPreviousRow();
      currentRow = null;
      compareReturnNegative = false;
      compareReturnPositive = false;
    }
    
    private void clearPreviousRow() {
      this.previousRow = null;
      perChildDuplicatesCount[LEFT_CHILD] = 0;
      perChildDuplicatesCount[RIGHT_CHILD] = 0;
      previousRowDuplicates = 0;
    }
    
    private int getSetOperationCount() {
      { // verify sanity
        SanityManager.ASSERT(this.previousRow != null, 
        " SetOperatorIterator previousRow is null ");
        SanityManager.ASSERT(perChildDuplicatesCount[LEFT_CHILD] != 0
            || perChildDuplicatesCount[RIGHT_CHILD] != 0, 
        " SetOperatorIterator duplicate count should not be empty ");
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::getSetOperationCount: Dump of"
                  + " perChildDuplicatesCount = "
                  + perChildDuplicatesCount[LEFT_CHILD] + " "
                  + perChildDuplicatesCount[RIGHT_CHILD]);
        }
      }

      int rowDuplicates = Integer.MAX_VALUE;
      switch( opType)
      {
        case INTERSECT_OP:
          if (perChildDuplicatesCount[LEFT_CHILD] != 0
              && perChildDuplicatesCount[RIGHT_CHILD] != 0) {
            rowDuplicates = 1;
          } else {
            rowDuplicates = 0;
          }
          break;

        case EXCEPT_OP:
          if (perChildDuplicatesCount[LEFT_CHILD] != 0
              && perChildDuplicatesCount[RIGHT_CHILD] == 0) {
            rowDuplicates = 1;
          } else {
            rowDuplicates = 0;
          }
          break;

        case INTERSECT_ALL_OP:
        {
          rowDuplicates = perChildDuplicatesCount[LEFT_CHILD];
          if (rowDuplicates > perChildDuplicatesCount[RIGHT_CHILD]) {
            rowDuplicates = perChildDuplicatesCount[RIGHT_CHILD];
          }
        }
        break;

        case EXCEPT_ALL_OP: {
          rowDuplicates = perChildDuplicatesCount[LEFT_CHILD]
              - perChildDuplicatesCount[RIGHT_CHILD];
          if (rowDuplicates < 0) {
            rowDuplicates = 0;
          }
          break;
        }
      }
      if (rowDuplicates == Integer.MAX_VALUE) {
        SanityManager.THROWASSERT("SetOperatorIterator produces invalid count "
            + "for set operation opType: " + opType);
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::getSetOperationCount set operation opType: "
                  + opType + " returned duplicates = " + rowDuplicates);
        }
      }

      return rowDuplicates;
    }
        
    private void initializeDuplicatesCount() {
      { // sanity
        SanityManager.ASSERT(this.previousRow != null, 
        "SetOperatorIterator initializeDuplicatesCount");
        SanityManager.ASSERT(perChildDuplicatesCount[LEFT_CHILD] == 0
            && perChildDuplicatesCount[RIGHT_CHILD] == 0, 
        " SetOperatorIterator Map should be empty ");
      }
      
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::initializeDuplicatesCount: tuple is =" 
              + previousRow.toString());
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::initializeDuplicatesCount: raks of the above tuple =" 
              +  previousRow.getAllRegionAndKeyInfo().toString());
        }
      }

      if (previousRow.getAllRegionAndKeyInfo().first()
          .isLeftSideTreeOfSetOperatorNode()) {
        perChildDuplicatesCount[LEFT_CHILD]++;
      }
      else {
        perChildDuplicatesCount[RIGHT_CHILD]++;
      }
      
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::initializeDuplicatesCount: "
              + " Dump of perChildDuplicatesCount = " 
              + perChildDuplicatesCount[LEFT_CHILD] + " "
              + perChildDuplicatesCount[RIGHT_CHILD]);
        }
      }
    }

    private void updateDuplicatesCount(ExecRow vRow) {
      { // sanity
        SanityManager.ASSERT(this.previousRow != null, 
        "SetOperatorIterator updateDuplicatesCount");
        SanityManager.ASSERT(vRow != null, 
        "SetOperatorIterator updateDuplicatesCount");
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(
              GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::updateDuplicatesCount: tuple is ="
                  + vRow.toString());
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::updateDuplicatesCount: raks of the "
                  + "above tuple =" + vRow.getAllRegionAndKeyInfo().toString());
        }
      }

      if (vRow.getAllRegionAndKeyInfo().first().isLeftSideTreeOfSetOperatorNode()) {
        perChildDuplicatesCount[LEFT_CHILD]++;
      } 
      else {
        perChildDuplicatesCount[RIGHT_CHILD]++;
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "SetOperatorIterator::updateDuplicatesCount: "
              + " Dump of perChildDuplicatesCount = " 
              + perChildDuplicatesCount[LEFT_CHILD] + " "
              + perChildDuplicatesCount[RIGHT_CHILD]);
        }
      }
    }
    
    public ExecRow next() throws StandardException {
      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;

      try {
        if (firstCall) {
          firstCall = false;

          { // verify sanity...
            SanityManager.ASSERT(previousRow == null,
            " Previously row should be null ");
            SanityManager.ASSERT(currentRow == null, 
            " Current row should be null ");
            SanityManager.ASSERT(previousRowDuplicates == 0, 
            " Duplicate count should be zero ");
          }
          
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "SetOperatorIterator::next First call");
            }
          }

          // get first row from source and initialise previous row
          currentRow = sourceOrderedIter.next();
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              if (currentRow == null) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                    "SetOperatorIterator::next Found new row (first row) = Null ");
              }
              else {
                SanityManager.DEBUG_PRINT(
                    GfxdConstants.TRACE_RSITER,
                    "SetOperatorIterator::next Found new row (first row) = "
                        + GemFireXDUtils.addressOf(currentRow) + " "
                        + currentRow.toString());
              }
            }
          }
          assert checkRegionAndKeyInRow(currentRow); // verify sanity...
          setPreviousRow(currentRow);
          currentRow = null;
          return this.next(); 
        }
        else if (previousRow == null) {

          if(SanityManager.ASSERT) { // verify sanity...
            SanityManager.ASSERT(previousRowDuplicates == 0, 
            " Duplicates should be zero ");
          }

          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "SetOperatorIterator::next Exit ; Rows returned = " + rowsReturned);
            }
          }

          // we are done...
          cleanUpBeforeReturn();
          return null;
        }
        else if (previousRowDuplicates > 0) {
          
          { // verify sanity...
            SanityManager.ASSERT(previousRow != null,
            " Previously returned row is null ");
          }
          
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "SetOperatorIterator::next Returns duplicate ["
                      + this.previousRowDuplicates + "] for row: "
                      + previousRow.toString());
            }
          }
          
          // Return previous row
          ExecRow returnRow = previousRow;
          if (--previousRowDuplicates == 0) {
            clearPreviousRow();
            setPreviousRow(currentRow);
            currentRow = null;
          }

          rowsReturned++;
          return returnRow;
        } 
        else if (previousRowDuplicates == 0) {
          
          { // verify sanity...
            SanityManager.ASSERT(previousRow != null,
            " Previously returned row is null ");
            SanityManager.ASSERT(currentRow == null, 
            " Current row should be null ");
          }

          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "SetOperatorIterator::next; get next from source");
            }
          }
                   
          while ((currentRow = sourceOrderedIter.next()) != null) {
            assert checkRegionAndKeyInRow(currentRow); // verify sanity...
                     
            wrapCurrentRowToCompare.setRow(currentRow);
            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceRSIter) {
                if (currentRow == null) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                      "SetOperatorIterator::next Found new row = Null ");
                }
                else
                {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                      "SetOperatorIterator::next Found new row = "
                      + GemFireXDUtils.addressOf(currentRow) + " " + currentRow.toString());
                }
              }
            }
            
            // as an alternative, hashcode method be used
            int compareReturn = 0; 
            if ((compareReturn = wrapCurrentRowToCompare
                .compareTo(wrapPreviousRowToCompare)) == 0) {

              if (SanityManager.DEBUG) {
                if (GemFireXDUtils.TraceRSIter) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                      "SetOperatorIterator New found row is duplicate "
                          + "of previous");
                }
              }

              updateDuplicatesCount(currentRow);
            }
            else { 
              // inequality      
              if (SanityManager.DEBUG) {
                if (GemFireXDUtils.TraceRSIter) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                      "SetOperatorIterator new found row is not-duplicate "
                          + "of previous. compare = " + compareReturn);
                }

                if (compareReturn > 0) {
                  SanityManager.ASSERT(!compareReturnNegative,
                      " New found row is in out of order. ");
                  compareReturnPositive = true;
                }
                else {
                  SanityManager.ASSERT(!compareReturnNegative,
                      " New found row is in out of order. ");
                  compareReturnNegative = true;
                }
              }

              previousRowDuplicates = this.getSetOperationCount();
              if (previousRowDuplicates > 0) {
                return next();
              }

              // no success yet..go back to while
              // keeping this iterative than recursive
              clearPreviousRow();
              setPreviousRow(currentRow);
              currentRow = null;
            }
          }  // end of while

          SanityManager.ASSERT(currentRow == null,
              " Current row should be null ");
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceRSIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "SetOperatorIterator::next No more rows ");
            }
          }

          previousRowDuplicates = this.getSetOperationCount();
          if (previousRowDuplicates > 0) {
            return next();
          }

          // we are done...
          cleanUpBeforeReturn();
          return null;
        }

        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "SetOperatorIterator::next Unexpected return with duplicate [" 
                + previousRowDuplicates
                + "] for previous row: " 
                + (previousRow == null ? "" :previousRow.toString())
                + " and for current row: " 
                + (currentRow == null ? "" :currentRow.toString())
                + " . Total rows returned = " 
                + rowsReturned);
          }
        }

        // should not reach here
        SanityManager.THROWASSERT(" Previously returned row handlling error. ");
        return null;
      } finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    private boolean checkRegionAndKeyInRow(ExecRow currentRow) {
      if (currentRow != null) {
        SanityManager.ASSERT(currentRow.getAllRegionAndKeyInfo() != null,
            " First row's key-info is null ");
        SanityManager.ASSERT(currentRow.getAllRegionAndKeyInfo().size() > 0,
            " First row's key-info is empty ");
        SanityManager.ASSERT(currentRow.getAllRegionAndKeyInfo().first()
            .isForSpecialUseOfSetOperators(),
            " First row's key-info is invalid for set operators ");
      }
      return true;
    }

    @Override
    public ExecRow getExpectedRow() {
      return this.sourceOrderedIter.getExpectedRow();
    }
    
    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      visitor.setNumberOfChildren(1);

      visitor.visit(this);

      sourceOrderedIter.accept(visitor);
    }
    
    @Override
    public long getCurrentTimeSpent() {
      return nextTime - sourceOrderedIter.getTimeSpent(ENTIRE_RESULTSET_TREE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public long estimateMemoryUsage() throws StandardException {
      long memory = 0l;
      memory += sourceOrderedIter.estimateMemoryUsage();
      return memory;
    }

    @Override
    public void finish() throws StandardException {
      sourceOrderedIter.finish();
    }
  } //finish SetOperatorIterator
  
  /**
   * Implements RowCountResultSet where by user selects few rows by having Fetch
   * First Rows Only clause.
   * 
   * @author soubhikc
   * 
   */
  protected final class RowCountIterator extends AbstractRSIterator {

    private final long offset;

    private final long fetchFirst;
    
    private int rowsFetched = 0;

    private boolean offsetApplied = false;

    private final AbstractRSIterator source;

    RowCountIterator(AbstractRSIterator iterator, long offsetOrOffsetPrm,
        long fetchFirstOrFetchFirstPrm, boolean dynamicOffset,
        boolean dynamicFetchFirst) throws StandardException {
      super();
      this.source = iterator;
      //this.source.setTopLevelFlag(false);
      // default 0
      if (dynamicOffset) {
        this.offset = ((BaseActivation)getActivation())
            .getParameter((int)offsetOrOffsetPrm).getLong();
      }
      else {
        this.offset = offsetOrOffsetPrm;
      }
      
      // default -1
      if (dynamicFetchFirst) {
        this.fetchFirst = ((BaseActivation)getActivation())
            .getParameter((int)fetchFirstOrFetchFirstPrm).getLong();
      }
      else {
        this.fetchFirst = fetchFirstOrFetchFirstPrm;
      }
      // set the limits in OrderedIterator too for best performance
      final long fetchFirst = this.fetchFirst;
      if (fetchFirst > 0 && iterator instanceof OrderedIterator) {
        ((OrderedIterator)iterator).maxSortLimit = offset <= 0 ? fetchFirst
            : (fetchFirst + offset);
      }
    }

    @Override
    public void initialize() throws StandardException {
      this.source.initialize();
    }

    @Override
    public ExecRow getExpectedRow() {
      return source.getExpectedRow();
    }

    @Override
    public ExecRow next() throws StandardException {
      
      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;
      
      try {

        if (fetchFirst != -1 && rowsFetched >= fetchFirst) {
          return null;
        }

        ExecRow result = null;

        if (!offsetApplied && offset > 0) {

          // Only skip rows the first time around
          offsetApplied = true;

          long offsetCtr = offset;

          do {
            if ((result = source.next()) == null) {
              // couldn't get enough rows to skip over.
              return null;
            }

            offsetCtr--;
          } while (offsetCtr >= 0);

        }
        else {
          result = source.next();
        }

        if (result != null) {
          rowsFetched++;
        }

        rowsReturned++;
        return result;
      } finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      visitor.setNumberOfChildren(1);
      
      visitor.visit(this);
      
      source.accept(visitor);
    }
    
    @Override
    public long getCurrentTimeSpent() {
      return nextTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
    }

    @Override
    public long estimateMemoryUsage() throws StandardException {
      return source.estimateMemoryUsage();
    }

    @Override
    public void finish() throws StandardException {
      source.finish(); 
    }

  }

  private final class GemFireAggregator extends GenericAggregator {

    ExecAggregator inputAgg;

    UserDataValue inputVal = null;

    GemFireAggregator(AggregatorInfo aggInfo, ClassFactory cf) {
      super(aggInfo, cf);
    }

    @Override
    public void initialize(ExecRow row) throws StandardException {
      super.initialize(row);
      inputAgg = super.getAggregatorInstance();
    }

    @Override
    public void merge(ExecRow inputRow, ExecRow mergeRow)
        throws StandardException {
      if (inputRow == null) {
        return;
      }

      DataValueDescriptor mergeColumn = mergeRow
          .getColumn(aggregatorColumnId + 1);

      DataValueDescriptor inputColumn = inputRow.getColumn(inputColumnId);
      if (inputColumn.isNull()) {
        return;
      }

      if (aggInfo.isDistinct()) {
        mergeDistinctEntries(mergeRow.getColumn(resultColumnId + 1),
            inputColumn);
        return;
      }

      inputAgg.setup("", inputColumn);

      if (inputVal == null) {
        inputVal = (UserDataValue)mergeColumn.getClone();
      }
      inputVal.setValue(inputAgg);
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceAggreg) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
              "GemFireAggregator#merge: inputVal [" + this.inputVal
                  + "] mergeColumn: " + mergeColumn);
        }
      }
      merge(inputVal, mergeColumn);
    }

    boolean finish(ExecRow input, ExecRow output) throws StandardException {

      if (output == null)
        return false;

      if (aggInfo.isDistinct()) {
        enumerateDistinctEntries(input);
      }

      boolean eliminatedNulls = super.finish(input, resultColumnId);

      output.setColumn(inputColumnId, input.getColumn(resultColumnId));

      return eliminatedNulls;
    }

    void mergeDistinctEntries(DataValueDescriptor resultColumn,
        DataValueDescriptor inputColumn) {
      assert resultColumn instanceof DVDSet: " resultColumn must be instance of DVDSet";
      assert inputColumn instanceof DVDSet: " inputColumn must be instance of DVDSet";

      ((DVDSet)resultColumn).merge((DVDSet)inputColumn);
    }

    void enumerateDistinctEntries(ExecRow mergeRow) throws StandardException {
      DataValueDescriptor inputColumn = mergeRow.getColumn(resultColumnId + 1);
      DataValueDescriptor aggregator = mergeRow
          .getColumn(aggregatorColumnId + 1);

      final ArrayList<Object> values = ((DVDSet)inputColumn).getObject();
      for (Object dvd : values) {
        accumulate((DataValueDescriptor)dvd, aggregator);
      }
    }
  }

  protected final class GroupedIterator extends AbstractRSIterator {

    // This variable would take of NULL inputs scalar aggregation.
    private boolean nextSatisfied = false;

    private boolean isScalar;

    protected GemFireAggregator[] aggregates;

    protected ColumnOrdering[] order;

    int[] projectMapping;

    BaseActivation exprEvaluator;

    ArrayList<String> exprMethodList;

    AbstractRSIterator source;

    ExecRow previousEntry;

    GroupedIterator(AbstractRSIterator iter) throws StandardException {
      super();

      this.source = iter;
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Creating GroupedIterator");
        }
      }

      // if no group by clause then agg are scalar.
      isScalar = !qInfo.getGroupByQI().doReGrouping();
      AggregatorInfoList aggInfoList = qInfo.getGroupByQI().getAggInfo();

      ClassFactory cf = lcc.getLanguageConnectionFactory().getClassFactory();
      //List<GenericAggregator> tmpAggregators = new LinkedList<GenericAggregator>();
      int count = aggInfoList.size();
      aggregates = new GemFireAggregator[count];
      for (int i = 0; i < count; i++) {
        AggregatorInfo aggInfo = (AggregatorInfo)aggInfoList.elementAt(i);
        aggregates[i]=new GemFireAggregator(aggInfo, cf);
      }

      
      order = qInfo.getGroupByQI().getColumnOrdering();
      projectMapping = GemFireDistributedResultSet.this.qInfo.getGroupByQI()
          .getPostGroupingProjectMapping();

      // initialize expression class
      exprEvaluator = qInfo.getGroupByQI().getExpressionEvaluator(lcc);
      if (exprEvaluator != null && pvs != null) {
        exprEvaluator.setParameters(GemFireDistributedResultSet.this.pvs, null);
      }
      exprMethodList = qInfo.getGroupByQI().getExprMethodList();

      previousEntry = null;

    }

    @Override
    public void initialize() throws StandardException {
      this.source.initialize();
    }

    public ExecRow next() throws StandardException {
      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;

      ExecRow returnRow = null;
      try {
        do {
          returnRow = null;

          /*
           * AccumulateRow stores only aggregates, if any, 
           * whereas returnRow merges the group by columns.
           * Finally, agg.finish() on aggregates copies the 
           * aggregated data onto returnRow.
           *  
           * Note: This is to avoid un-necessary transformation of 
           * projection row (entry) to accumulate row & back.
           * 
           * Note: Aggregators expect the row in a specific 
           * format (OutputCol, InputCol, Aggregator) & hence
           * necessitates AccumulateRow.
           * 
           */
          ExecRow accumulateRow = qInfo.getGroupByQI().getTemplateRow()
              .getNewNullRow();
          for (GemFireAggregator currAggregate : aggregates)
            currAggregate.initialize(accumulateRow);

          ExecRow entry;
          while ((entry = source.next()) != null || previousEntry != null) {

            if (returnRow == null) {

              if (previousEntry != null) {
                // if previousEntry was fetched during previous .next() call,
                // merge that row into AccumulateRow.
                returnRow = previousEntry;
                previousEntry = null;
                for (GemFireAggregator currAggregate : aggregates)
                  currAggregate.merge(returnRow, accumulateRow);

                if (SanityManager.DEBUG) {
                  if (GemFireXDUtils.TraceGroupByIter) {
                    SanityManager
                        .DEBUG_PRINT(
                            GfxdConstants.TRACE_GROUPBYITER,
                            "GroupedIterator::next(): processing previously held row "
                                + returnRow
                                + (aggregates.length != 0 ? " resulting accumulated row "
                                    + accumulateRow
                                    : ""));
                  }
                }
              }
              else {
                returnRow = entry.getClone();
              }
            }

            if (!sameGroupingValues(returnRow, entry)) {
              // TODO support round robin with different group values.
              assert !(source instanceof RoundRobinIterator);
              previousEntry = entry;
              if (SanityManager.DEBUG) {
                if (GemFireXDUtils.TraceGroupByIter) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_GROUPBYITER,
                      "GroupedIterator::next(): holding as previous entry "
                          + previousEntry);
                }
              }
              break;
            }

            for (GemFireAggregator currAggregate : aggregates)
              currAggregate.merge(entry, accumulateRow);

            if (SanityManager.DEBUG) {
              if (GemFireXDUtils.TraceGroupByIter) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_GROUPBYITER,
                    "GroupedIterator::next(): entry="
                        + entry
                        + (aggregates.length != 0 ? " accumulateRow="
                            + accumulateRow : ""));
              }
            }

          } // end of while

          /*
           * #41407
           * Take care of zero rows input to scalar aggregates.
           * nextSatisfied is set true and while loop didn't fetched
           * any rows then declare END of result. 
           */
          if (nextSatisfied == true && returnRow == null) {
            return null;
          }
          else if (isScalar && nextSatisfied == false && returnRow == null) {
            // lets generate atleast one row for scalar agg.
            returnRow = qInfo.getGroupByQI().getInComingProjectionExecRow()
                .getNewNullRow();
            // once we have generated one row, lets turn it off. #47595
            isScalar = false;
          }

          boolean eliminatedNulls = false;
          for (GemFireAggregator currAggregate : aggregates)
            if (currAggregate.finish(accumulateRow, returnRow))
              eliminatedNulls = true;

          if (eliminatedNulls) {
            activation.addNullEliminatedWarning();
          }

          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceGroupByIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_GROUPBYITER,
                  "GroupedIterator::next(): returnRow before projection/having "
                      + "restriction "
                      + (returnRow != null ? returnRow : " null "));
            }
          }

          // lets move onto next valid row.
          // converting to do...while fixing #43454
        } while (restrictCurrentRow(returnRow));

        nextSatisfied = true;
        returnRow = doProjection(returnRow);

        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceGroupByIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_GROUPBYITER,
                "GroupedIterator::next(): returning "
                    + (returnRow != null ? returnRow : " null "));
          }
        }
        rowsReturned++;
        return returnRow;
      } finally {
        if (returnRow == null) {
          // cleanup the expression activation
          if (this.exprEvaluator != null) {
            try {
              this.exprEvaluator.close();
            } catch (Exception e) {
              // ignored
            }
          }
        }
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    private boolean sameGroupingValues(ExecRow currRow, ExecRow newRow)
        throws StandardException {
      if (currRow == null || newRow == null) {
        return true;
      }

      /* if order-ing is null with a GroupByNode, it means a simple
       * aggregation without a group by clause.
       * select count(*) from xxx
       */
      if (order == null) {
        return true;
      }

      for (ColumnOrdering element : order) {
        DataValueDescriptor currOrderable = currRow.getColumn(element
            .getColumnId() + 1);
        DataValueDescriptor newOrderable = newRow.getColumn(element
            .getColumnId() + 1);

        if (!(currOrderable.compare(DataValueDescriptor.ORDER_OP_EQUALS,
            newOrderable, true, true)))
          return false;
      }
      
      return true;
    }

    /**
     * Apply the restriction now i.e. havingClause.
     * 
     * @throws StandardException
     */
    private boolean restrictCurrentRow(ExecRow inputRow)
        throws StandardException {

      if (inputRow == null) {
        return false;
      }

      if (exprEvaluator == null) {
        return false;
      }

      exprEvaluator.setCurrentRow(inputRow, 0);

      boolean restrict = false;

      /*
       * Any method left out in methodList (non null) beyond projection
       * must be a restriction to be applied and hence getBoolean must
       * be applicable.
       */
      for (int index = (projectMapping == null ? 0 : projectMapping.length),
          msize = exprMethodList.size(); index < msize; index++) {

        String filter = exprMethodList.get(index);
        if (filter == null) {
          continue;
        }

        // evaluate filter
        DataValueDescriptor booleanRetVal = (DataValueDescriptor)exprEvaluator
            .getMethod(filter).invoke(exprEvaluator);

        /* booleanRetVal will indicate whether the expression passed (true) or failed (false).
         * so, we have to negate it to infer whether to filter out this row or not. 
         */
        restrict = (!booleanRetVal.isNull()) && !booleanRetVal.getBoolean();

        if (restrict) {
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceGroupByIter) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_GROUPBYITER,
                  "GroupedIterator::doProjection(): filtering out row "
                      + inputRow);
            }
          }
          break;
        }
      }

      return restrict;
    }

    private ExecRow doProjection(ExecRow inputRow) throws StandardException {

      if (projectMapping == null) {
        return inputRow;
      }

      if (inputRow == null) {
        return null;
      }

      if (exprEvaluator != null) {
        exprEvaluator.setCurrentRow(inputRow, 0);
      }

      ExecRow returnRow = new ValueRow(projectMapping.length);

      for (int index = 0; index < projectMapping.length; index++) {

        if (projectMapping[index] < 0) {

          if (exprEvaluator != null) {
            int idx = (-projectMapping[index]) - 1;

            // evaluate projection expression
            DataValueDescriptor result = (DataValueDescriptor)exprEvaluator
                .getMethod(exprMethodList.get(idx)).invoke(exprEvaluator);

            returnRow.setColumn(index + 1, result);
          }
          continue;
        }

        //returnRow.setColumn(index + 1, inputRow
        //    .getColumn(projectMapping[index]));

      }
      inputRow.setValuesInto(projectMapping, false, returnRow);

      return returnRow;
    }

    public ExecRow getExpectedRow() {
      return this.source.getExpectedRow();
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      visitor.setNumberOfChildren(1);
      
      visitor.visit(this);
      
      source.accept(visitor);
    }

    @Override
    public long getCurrentTimeSpent() {
      return nextTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
    }

    @Override
    public long estimateMemoryUsage() throws StandardException {
      return source.estimateMemoryUsage();
    }

    @Override
    public void finish() throws StandardException {
      source.finish();
    }
  }

  /**
   * Iterates over List of ResultHolders for total ordering of remote ResultSets
   * (ResultHolder rows) as per order by clause.
   * 
   * TODO: PERF: use a set of ExecRows instead of cloning everytime; for each RH
   * use an ExecRow (which is there in the RH itself) and do a full n-way merge
   * where a new row is either returned or inserted in the correct position in
   * the n-size array
   * 
   * @author soubhikc
   */
  protected final class OrderedIterator extends AbstractRSIterator {

    private ExecRow templateRow;

    protected final int[] colOrdering;

    protected final boolean[] colOrderingAsc;

    protected final boolean[] colOrderingNullsLow;

    protected final boolean allColumns;

    protected final boolean sortDistinct;

    private ArraySortScan scan;

    int[] projectMapping;

    /*
     * Whether on first .next() call All the resultHolders
     * are to be consumed or its okay to consume incrementally.
     */
    protected boolean fetchAllFirst;

    private boolean applyProjection;

    Properties sortProperties;

    private ColumnOrdering[] orderByCols;

    long maxSortLimit;

    private long sortId;

    private boolean isDone;

    private boolean caseOfIntersectOrExcept;

    /*
     * Default ordering when no other RHIterator (e.g. GroupedIterator)
     *  is source. Always do incremental fetch from the list.
     */
    final AbstractRSIterator source;

    // Statistics
    transient int rowsInput;

    OrderedIterator(final AbstractRSIterator iter,
        ColumnOrdering[] orderbycols, boolean isDistinct, boolean fetchEager)
        throws StandardException {
      super();

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Creating OrderedIterator with " + iter.getClass().getSimpleName());
        }
      }

      // if orderby is inorder with all projection columns skip keeping every
      // columns info.
      this.allColumns = (orderbycols == null);
      this.colOrdering = (orderbycols != null) ? new int[orderbycols.length]
          : new int[] {};
      this.colOrderingAsc = (orderbycols != null) ? new boolean[orderbycols.length]
          : new boolean[] { true };
      this.colOrderingNullsLow = (orderbycols != null) ? new boolean[orderbycols.length]
          : new boolean[] { false };
      if (orderbycols != null) {
        int i = 0;
        for (ColumnOrdering co : orderbycols) {
          this.colOrdering[i] = co.getColumnId();
          this.colOrderingAsc[i] = co.getIsAscending();
          this.colOrderingNullsLow[i] = co.getIsNullsOrderedLow();
          i++;
        }
      }
      this.sortDistinct = isDistinct;

      assert (iter != null && !(iter instanceof RoundRobinIterator))
          || (fetchEager == false): "RoundRobin iterator cannot handle fetchEager";

      source = iter;
      applyProjection = false;

      this.orderByCols = orderbycols;
    }

    OrderedIterator(SecondaryClauseQueryInfo qInfo, boolean isDistinct,
        boolean fetchEager, boolean isOuterJoinCase,
        boolean hasIntersectOrExcept) throws StandardException {
      // Neeraj: if (outerjoin to be handled)
      // then outer join iterator else the below code
      // [vivek] force RoundRobinIterator for intersect/except operators..
      this((isOuterJoinCase ? new SpecialCaseOuterJoinIterator(qInfo)
          : (fetchEager && !hasIntersectOrExcept ? new SequentialIterator(qInfo)
              : new RoundRobinIterator(qInfo))), (hasIntersectOrExcept ? null
                  : qInfo.getColumnOrdering()), (hasIntersectOrExcept ? false
                      : isDistinct), fetchEager);

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "Creating OrderedIterator");
        }
      }

      projectMapping = qInfo.getProjectMapping();
      applyProjection = true;
      caseOfIntersectOrExcept = hasIntersectOrExcept;
    }

    public ExecRow next() throws StandardException {
      ExecRow returnRow = null;
     
      if(isDone) {
        return null;
      }

      final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1)
          : 0;

      try {
        if (!scan.next()) {
          finish();
          isDone = true;
          return null;
        }
        //returnRow = templateRow.getNewNullRow();
        returnRow = scan.fetchRow(templateRow);

        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "OrderedIterator::next(): returning row " + returnRow);
          }
        }

        if (applyProjection) {
          // TODO: PERF: another clone below? use a templateRow inside
          // OrderedIterator and let higher derby layers clone if required
          returnRow = doProjection(returnRow);
          if (returnRow != null) {
            rowsReturned++;
            return returnRow;
          }
          return null;
        }
        else {
          if (returnRow != null) {
            rowsReturned++;
            return returnRow;
          }
          return null;
        }
      } finally {
        if (beginTime != 0) {
          nextTime += XPLAINUtil.recordTiming(beginTime);
          nestedTimingIndicator = 0;
        }
      }
    }

    @Override
    public void initialize() throws StandardException {
      final AbstractRSIterator source = this.source;
      source.initialize();

      while (true) {
        if ((templateRow = source.next()) != null) {
          if (templateRow != ROUND_COMPLETE) {
            break;
          }
        }
        else {
          isDone = true;
          return;
        }
      }
      final BasicSortObserver sortObserver = new BasicSortObserver(true,
          sortDistinct, templateRow, false);

      final GemFireTransaction tc = (GemFireTransaction)act
          .getLanguageConnectionContext().getTransactionExecute();
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceSortTuning) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
              "creating sort of template " + templateRow + " from row="
                  + templateRow.getClass().getName() + " with sort order "
                  + Arrays.toString(orderByCols));
        }
      }
      // determine on the fly about ordering by all columns, all Asc and Nulls
      // ordered low.
      if (orderByCols == null) {
        orderByCols = new IndexColumnOrder[templateRow.nColumns()];
        for (int idx = 0; idx < orderByCols.length; idx++) {
          orderByCols[idx] = new IndexColumnOrder(idx);
        }
      }
      sortId = tc.createSort(null, templateRow, orderByCols, sortObserver,
          (orderByCols == null || orderByCols.length == 0), -1, -1,
          this.maxSortLimit);

      final ArraySorter sorter = (ArraySorter)tc.openSort(sortId);
      sorter.insert(templateRow);
      // lets keep a blank copy instead of the one incoming row.
      templateRow = templateRow.getNewNullRow();

      ExecRow r;
      while ((r = source.next()) != null) {
        if (r != ROUND_COMPLETE) {
          sorter.insert(r);
        }
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceSortTuning) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
              "Creating sort properties for " + this + " from initScan");
        }
      }
      createSortProps(sorter);

      sorter.completedInserts();
      scan = (ArraySortScan)tc.openSortScan(sortId,
          activation.getResultSetHoldability());
    }

    private final void createSortProps(final SortController sorter)
        throws StandardException {

      if (!runtimeStatisticsOn) {
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceSortTuning) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
                "Not creating sort properties as runtimeStats is off.");
          }
        }
        return;
      }

      if (sorter != null) {
        sortProperties = sorter.getSortInfo().getAllSortInfo(new Properties());
      }
      else {
        sortProperties = new Properties();
        sortProperties.put(MessageService
            .getTextMessage(SQLState.STORE_RTS_NUM_ROWS_INPUT), Integer
            .toString(rowsInput));
        sortProperties.put(MessageService
            .getTextMessage(SQLState.STORE_RTS_NUM_ROWS_OUTPUT), Integer
            .toString(rowsReturned));
      }

      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceSortTuning) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
              "Sort properties " + sortProperties + " opening sort scan ...");
        }
      }
    }

    private ExecRow doProjection(ExecRow inputRow) throws StandardException {

      if (projectMapping == null) {
        return inputRow;
      }

      if (inputRow == null) {
        return null;
      }

      ExecRow returnRow = new ValueRow(projectMapping.length);

      /*
      for (int index = 0; index < projectMapping.length; index++) {
        returnRow.setColumn(index + 1,
            inputRow.getColumn(projectMapping[index]));
      }
      */
      inputRow.setValuesInto(projectMapping, false, returnRow);

      return returnRow;
      
    }

    /**
     * Class for Ordering of column(s).
     * 
     * @author soubhikc
     */
    protected class OrderedRow implements Comparable {

      protected ExecRow row = null;

      protected OrderedRow(ExecRow _row) throws StandardException {
        if (caseOfIntersectOrExcept && _row == null) {
          return;
        }

        this.row = _row;
        assert colOrdering.length <= this.row.nColumns();
      }

      /**
       * Compare two Order By column(s) DVD arrays. First unequal element of
       * 'this' object if greater than 'other' object's corresponding element,
       * after considering sort order and null ordering, then 'other' object
       * should go out first else vice versa.
       * 
       * @see DataValueDescriptor#compare(DataValueDescriptor)
       * @return -1 when first unequal other's elements' compare returns -1 for
       *         corresponding sort order. 0 if all the other's elements' equals
       *         to all this.cols elements. 1 when first unequal other's
       *         elements' compare return 1 for corresponding sort order.
       */
      public int compareTo(Object o) {

        if (this == (OrderedRow)o)
          return 0;

        if (this.row == ((OrderedRow)o).row)
          return 0;

        try {

          final DataValueDescriptor[] rowArr1 = this.row.getRowArray();
          final DataValueDescriptor[] rowArr2 = ((OrderedRow)o).row
              .getRowArray();
          int cmp = 0;
          final int cols2Compare = (allColumns ? rowArr1.length
              : colOrdering.length);

          for (int i = 0; i < cols2Compare; i++) {
            final int orderbycol = (allColumns ? i : colOrdering[i]);
            final boolean isNullsLow = (allColumns ? colOrderingNullsLow[0]
                : colOrderingNullsLow[i]);
            final boolean isAscending = (allColumns ? colOrderingAsc[0]
                : colOrderingAsc[i]);

            // return on first DVD not inlined with required criteria.
            if ((cmp = rowArr1[orderbycol].compare(rowArr2[orderbycol],
                isNullsLow)) != 0) {
              // lets flip if Descending order is desired.
              return (isAscending ? cmp : -cmp);
            }
          }
        } catch (StandardException e) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "OrderedRow exception", e);
        }
        // if all the values were equal (including NULL==NULL, then let it
        // chain).
        return 0;
      }

      @Override
      public int hashCode() {
        int ret = 0;
        final DataValueDescriptor[] rowArr = this.row.getRowArray();
        for (int i = 0; i < colOrdering.length; i++) {
          ret ^= rowArr[i].hashCode();
        }
        return ret;
      }

      @Override
      public String toString() {
        final StringBuilder orderingCols = new StringBuilder("Columns = ");
        for (DataValueDescriptor cl : this.row.getRowArray()) {
          orderingCols.append((cl != null ? cl.toString() : "null") + "  ");
        }
        return orderingCols.toString();
      }
    } // end of OrderedRow 
    
    /**
     * Class especially for Set Operators.
     */
    protected class SetOrderedRow extends OrderedRow {

      private SetOrderedRow() throws StandardException {
        super(null);
        SanityManager.ASSERT(caseOfIntersectOrExcept,
            " Only to be used for Set Operators");
      }

      private void setRow(ExecRow _row) throws StandardException {
        this.row = _row;
      }
      
      // an alternative to super.hashcode
      // not being used
      @Override
      public int hashCode() {
        int ret = 0;
        final DataValueDescriptor[] rowArr = this.row.getRowArray();
        for (int i = 0; i < colOrdering.length; i++) {
          ret = 31*ret + rowArr[i].hashCode();
        }
        return ret;
      }
    }
    
    public ExecRow getExpectedRow() {
      return this.source.getExpectedRow();
    }

    @Override
    public void accept(IteratorStatisticsVisitor visitor) {
      
      visitor.setNumberOfChildren(1);
      
      visitor.visit(this);
      
      source.accept(visitor);
    }

    @Override
    public long getCurrentTimeSpent() {
      return nextTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
    }

    @Override
    public long estimateMemoryUsage() throws StandardException {
      long memory = 0l;
      memory += source.estimateMemoryUsage();
      return memory;
    }

    @Override
    public void finish() throws StandardException {
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceSortTuning) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
              "About to finish ordered iterator for " + this + " scan is "
                  + (scan == null ? "null" : " not null"));
        }
      }
      if (scan != null) {
        act.getLanguageConnectionContext().getTransactionExecute().dropSort(
            sortId);
        scan.close();
        scan = null;
        isDone = true;
      }
      else {
       
        createSortProps(null); // collect sort properties if no scan got opened. 
      }
      source.finish();
    }

   

  } // end of OrderedIterator

  /*
   * These methods used primarily for testing.
   */
  public static final int ORDERED = 0x01, DISTINCT = 0x02, FETCH_EARLY = 0x04,
      FETCH_ONDEMAND = 0x08, SEQUENTIAL = 0x10;

  public final boolean isIterator(final int flag) {

    boolean retVal = false;

    if ((flag & ORDERED) == ORDERED) {
      retVal = (this.iterator instanceof OrderedIterator) ? true : false;
      if (!retVal)
        return retVal;

      OrderedIterator iter = (OrderedIterator)this.iterator;
      if ((flag & DISTINCT) == DISTINCT) {
        retVal = retVal && (iter.sortDistinct); 
        //retVal = retVal && iter.distinctSort;
      }

      if ((flag & FETCH_EARLY) == FETCH_EARLY) {
        retVal = retVal && (iter.fetchAllFirst);
      }

      if ((flag & FETCH_ONDEMAND) == FETCH_ONDEMAND) {
        retVal = retVal && !(iter.fetchAllFirst);
      }

    }
    else if ((flag & SEQUENTIAL) == SEQUENTIAL) {
      retVal = (this.iterator instanceof SequentialIterator) ? true : false;
      if (!retVal)
        return retVal;
    }

    return retVal;
  }

  // don't move this to parent class.
  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(0);
    visitor.visit(this);
  }

  private DataValueDescriptor[] cachedChangedRow;

  /**
   * @see UpdatableResultSet#isForUpdate()
   */
  @Override
  public final boolean isForUpdate() {
    return this.isForUpdate;
  }

  /**
   * @see UpdatableResultSet#canUpdateInPlace()
   */
  @Override
  public final boolean canUpdateInPlace() {
    return isForUpdate();
  }

  /**
   * @see UpdatableResultSet#updateRow(ExecRow)
   */
  @Override
  public final void updateRow(ExecRow updateRow) throws StandardException {
    final GemFireContainer gfContainer = this.qInfo.getGFContainer();
    Object key = getKeyForDirectModificationFromCurrentRow(gfContainer);
    this.cachedChangedRow = updateDirectly(key, null, updateRow,
        this.act.getUpdatedColumns(), gfContainer, this.qInfo, this.tran,
        this.lcc, this.cachedChangedRow);
  }

  /**
   * @see UpdatableResultSet#deleteRowDirectly()
   */
  @Override
  public void deleteRowDirectly() throws StandardException {
    final GemFireContainer gfContainer = this.qInfo.getGFContainer();
    Object key = getKeyForDirectModificationFromCurrentRow(gfContainer);
    gfContainer.delete(key, null, this.qInfo.isPrimaryKeyBased(), tran,
        gfContainer.getActiveTXState(tran), lcc, false);
  }

  private Object getKeyForDirectModificationFromCurrentRow(
      GemFireContainer gfContainer) throws StandardException {
    int[] pifk = this.qInfo.getProjectionIndexesForKey();
    Object key = null;
    if (pifk != null) {
      key = GemFireXDUtils.convertIntoGemFireRegionKey(this.currentRow,
          gfContainer, null, pifk);
    }
    else {
      key = getCurrentKey();
      if (key instanceof KeyWithRegionContext) {
        ((KeyWithRegionContext)key).setRegionContext(gfContainer.getRegion());
      }
    }
    return key;
  }

  public static DataValueDescriptor[] updateDirectly(final Object key,
      final Object callbackArg, ExecRow updateRow, boolean[] columnGotUpdated,
      final GemFireContainer gfContainer, final SelectQueryInfo qInfo,
      final GemFireTransaction tran, final LanguageConnectionContext lcc,
      DataValueDescriptor[] cachedChangedRow) throws StandardException {
    if (cachedChangedRow == null) {
      cachedChangedRow = new DataValueDescriptor[gfContainer
          .getTableDescriptor().getNumberOfColumns()];
    }

    for (int i = 0; i < cachedChangedRow.length; i++) {
      cachedChangedRow[i] = null;
    }

    int len = updateRow.nColumns();
    ColumnQueryInfo[] colsToBeUpdatedInfo = new ColumnQueryInfo[len];
    ColumnQueryInfo[] colInfo = qInfo.getProjectionColumnQueryInfo();
    int numOfUpdateColumns = 0;

    for (int i = 0; i < len; i++) {
      if (columnGotUpdated[i]) {
        DataValueDescriptor dvd = updateRow.getColumn(i + 1);
        int actualPosition = colInfo[i].getActualColumnPosition();
        cachedChangedRow[actualPosition - 1] = dvd;
        colsToBeUpdatedInfo[i] = colInfo[i];
        numOfUpdateColumns++;
      }
      else {
        colsToBeUpdatedInfo[i] = null;
      }
    }

    FormatableBitSet changedColumnBitSet = new FormatableBitSet(
        numOfUpdateColumns);
    for (int i = 0; i < colsToBeUpdatedInfo.length; i++) {
      ColumnQueryInfo cqi = colsToBeUpdatedInfo[i];
      if (cqi != null) {
        // DataValueDescriptor dvd = dvdarr[i];
        // cqi.isNotNullCriteriaSatisfied(dvd);
        changedColumnBitSet.grow(cqi.getActualColumnPosition());
        changedColumnBitSet.set(cqi.getActualColumnPosition() - 1);
      }
    }
    // Object callbackArg = getRoutingObject();

    // TODO: PERF: modify later to optimize passing the callback argument as
    // non-null if possible
    gfContainer.replacePartialRow(key, changedColumnBitSet, cachedChangedRow,
        null, tran, gfContainer.getActiveTXState(tran), lcc, null, false);
    return cachedChangedRow;
  }

  private Object getCurrentKey() {
    assert this.currentRow.getAllRegionAndKeyInfo() != null;
    assert this.qInfo.isSelectForUpdateCase();
    return (this.currentRow.getAllRegionAndKeyInfo().iterator().next())
        .getKey();
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    long memory = 0L;
    if(iterator != null) {
      memory += iterator.estimateMemoryUsage();
    }
    if(cachedChangedRow != null && cachedChangedRow.length > 0) {
      for(DataValueDescriptor d : cachedChangedRow) {
        memory += d.estimateMemoryUsage();
      }
    }
    return memory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean needsRowLocation() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rowLocation(RowLocation rl) throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ExecRow getNextRowFromRowSource() throws StandardException {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean needsToClone() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FormatableBitSet getValidColumns() {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void closeRowSource() {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markAsTopResultSet() {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void reopenCore() throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ExecRow getNextRowCore() throws StandardException {
    return getNextRow();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPointOfAttachment() {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScanIsolationLevel() {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTargetResultSet(TargetResultSet trs) {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNeedsRowLocation(boolean needsRowLocation) {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double getEstimatedRowCount() {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int resultSetNumber() {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCurrentRow(ExecRow row) {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean requiresRelocking() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TXState initLocalTXState() {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void upgradeReadLockToWrite(RowLocation rl, GemFireContainer container)
      throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateRowLocationPostRead() throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void filteredRowLocationPostRead(TXState localTXState) throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void markRowAsDeleted() throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void positionScanAtRowLocation(RowLocation rLoc)
      throws StandardException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDistributedResultSet() {
    return true;
  }

  @Override
  public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
      throws StandardException {
    throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
        " Currently this method is not implemented or overridden for class "
            + this.getClass().getSimpleName());
  }

  @Override
  public StringBuilder buildQueryPlan(StringBuilder builder, PlanUtils.Context context) {
    super.buildQueryPlan(builder, context);

    if (this.resultHolderList != null) {
      @SuppressWarnings("unchecked")
      Iterator<ResultHolder> iter = (Iterator<ResultHolder>) this.resultHolderList
          .iterator();
      while (iter.hasNext()) {
        ResultHolder holder = iter.next();
        if(holder != null) {
          holder.buildResultSetString(builder);
        }
      }
    }
    PlanUtils.xmlCloseTag(builder, context, this);

    return builder;
  }

  @Override
  public RowLocation fetch(final RowLocation loc, ExecRow destRow,
      FormatableBitSet validColumns, boolean faultIn, GemFireContainer container)
      throws StandardException {
    return RowUtil.fetch(loc, destRow, validColumns, faultIn, container, null,
        null, 0, this.tran);
  }

  @Override
  public void releasePreviousByteSource() {
  }

  @Override
  public void setMaxSortingLimit(long limit) {
    // will be set via QueryInfo
  }
} // end of GemFireDistributedResultSet
