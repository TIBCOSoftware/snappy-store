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
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.compile.CollectParameterNodeVisitor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.impl.sql.compile.AndNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.BinaryOperatorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.BooleanConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CursorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.HalfOuterJoinNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.HashTableNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.IndexToBaseRowNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.IsNullNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.JoinNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.LikeEscapeOperatorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.OrListNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.OrNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.Predicate;
import com.pivotal.gemfirexd.internal.impl.sql.compile.PredicateList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ProjectRestrictNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.QueryTreeNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumn;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumnList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultSetNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.TableOperatorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.UnaryOperatorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
/**
 * The abstract base class for {@link SelectQueryInfo} and
 * {@link UpdateQueryInfo}. It contains functionality common to both update &
 * select statements i.e Where clause and From clause
 * 
 * @author Asif
 * @see CursorNode#computeQueryInfo(QueryInfoContext qic)
 * @since GemFireXD
 * 
 */
public abstract class DMLQueryInfo extends AbstractQueryInfo implements Visitor {

  /*
   * main difference between DISTINCT & DISTINCT_SCAN is 
   * former uses ordering and latter uses hash to resolve
   * duplicates. While the store scan itself is distinct
   * it uses hash whereas for DistinctNode uses ordering 
   * (e.g. DistinctOrderByMerged=true).
   * @see GemfireDistributedResultSet#getIterator()
   */
  private static boolean TEST_FLAG_IGNORE_SINGLE_VM_CRITERIA = false;
    
  protected int queryType = 0x0;
  private AbstractConditionQueryInfo whereClause;

  final ArrayList<TableQueryInfo> tableQueryInfoList =
      new ArrayList<TableQueryInfo>(4);
  final ArrayList<GemFireContainer> containerList =
      new ArrayList<GemFireContainer>(4);
  // If this is true then it is possible that the indexed condition call is
  // invoked via explict FromVaseTableNode call , but the non idexed
  // conditions are invoked later. That case needs to be tackled
  // private boolean isIndextoBaseRowNode = false;

  private int currJunctionType = -1;

  private Object pk;

  private Object otherKeys;
  
  private Object localIndexKey = null;
  
  private ConglomerateDescriptor chosenLocalIndexDescriptor = null;

  final QueryInfoContext qic;  
  //The number of rows defines the number of partition columns
  private int colocMatrixRows;
  // Each column represents a Table
  // using ArrayList instead of List to ensure constant random access
  private final ArrayList<TableQueryInfo> colocMatrixTables =
    new ArrayList<TableQueryInfo>();
  private int colocMatrixPRTableCount;
  // Created to store the predicate list as we can process it only after the
  // table nodes have been processed.
  // This is required for colocation matrix to be created correctly
  final List<PredicateList> wherePredicateList = new ArrayList<PredicateList>(5);

//Asif: This int keeps track of ProjectRestrictNode. We want to evaluate ResultColumnList
  // of top level project restrict node on return journey.
  private int evaluateResultColumnList =0;

  protected boolean isOuterJoin = false;

  @SuppressWarnings({ "rawtypes", "unchecked" })
  protected final List<Region> logicalLeftTableList = new ArrayList<Region>();
  
  protected final List<Region> logicalRightTableList = new ArrayList<Region>();

  private final List<SubQueryInfo> subqueries = new ArrayList<SubQueryInfo>(2);
  private final List<SubqueryList> whereClauseSubqueries =
    new ArrayList<SubqueryList>(2);

  protected boolean isSpecialCaseOFOuterJoins;

  private int prTableCount;
  boolean needGfxdSubactivation = false;
 // boolean isSubQuery = false;
    
  /** The table ultimately used for routing the query. */
  private TableQueryInfo driverTqi = UNINITIALIZED;
  
  static byte DEFAULT_DML_SECTION = 0;
  static byte WHERE_DML_SECTION = 1;
  
  /**
   * For NCJ:
   * 
   * Store and ship meta data information like driver table, join order, and
   * other runtime parameters as batch size, cache size
   */
  private THashMap ncjMetaData = null;

  DMLQueryInfo(QueryInfoContext qic) throws StandardException {
    this.qic = qic;
    qic.pushScope(this);
   
    //TODO: Avoid escaping the refrence. But cannot think of a better way at this point 
    //this.qic.setRootQueryInfo(this);    
  }
  

  public Visitable visit(Visitable node) throws StandardException {
    FromBaseTable fbtNode = null;
    if (node instanceof AndNode) {
      handleAndNode((AndNode)node);
    }
    else if (node instanceof FromBaseTable) {
      fbtNode = (FromBaseTable)node;
      if (fbtNode.getTrulyTheBestAccessPath() != null
          && !fbtNode.considerSortAvoidancePath()) {
        setChosenLocalIndex(fbtNode);
      }
    }
    else if (node instanceof IndexToBaseRowNode) {
      fbtNode = ((IndexToBaseRowNode)node).getSource();
      if (fbtNode.getTrulyTheBestAccessPath() != null) {
        setChosenLocalIndex(fbtNode);
      }
    }
    else if (node instanceof ProjectRestrictNode) {
      handleProjectRestrictNode((ProjectRestrictNode)node);
    }
    else if (node instanceof TableOperatorNode) {
      final TableOperatorNode tabOpNode = (TableOperatorNode)node;
      // copy the old tableQueryInfoList separately to determine the new
      // additions
      final ArrayList<TableQueryInfo> origTQIList =
          new ArrayList<TableQueryInfo>(this.tableQueryInfoList);
      if (tabOpNode instanceof JoinNode) {
        this.handleJoinNode((JoinNode)tabOpNode);
      }
      final ResultSetNode leftChild = tabOpNode.getLeftResultSet();
      final ResultSetNode rightChild = tabOpNode.getRightResultSet();
      // First just create TableQueryInfo for the ProjectRestrictNode.
      // It should be handled first
      if (leftChild != null) {
        this.visit(leftChild);
      }
      if (rightChild != null) {
        this.visit(rightChild);
      }
      // add the "driver" table for column references (#43663, #39623)
      final int tableNum = tabOpNode.getTableNumber();
      addDriverTableForColumns(tableNum, origTQIList);
    }
    else if (node instanceof SubqueryNode) {
      handleSubqueryNode((SubqueryNode)node);
    }
    else if (node instanceof HashTableNode) {
      final HashTableNode hashNode = (HashTableNode)node;
      // copy the old tableQueryInfoList separately to determine the new
      // additions
      final ArrayList<TableQueryInfo> origTQIList =
          new ArrayList<TableQueryInfo>(this.tableQueryInfoList);
      final ResultSetNode child = hashNode.getChildResult();
      // First just create TableQueryInfo for the ProjectRestrictNode.
      // It should be handled first
      if (child != null) {
        this.visit(child);
      }
      PredicateList predList = hashNode.getJoinPredicateList();
      if (predList != null && predList.size() > 0) {
        this.wherePredicateList.add(predList);
      }
      predList = hashNode.getSearchPredicateList();
      if (predList != null && predList.size() > 0) {
        this.wherePredicateList.add(predList);
      }
      SubqueryList subqueryList = hashNode.getPSubqueryList();
      if (subqueryList != null && subqueryList.size() > 0) {
        this.whereClauseSubqueries.add(subqueryList);
      }
      subqueryList = hashNode.getRSubqueryList();
      if (subqueryList != null && subqueryList.size() > 0) {
        this.whereClauseSubqueries.add(subqueryList);
      }
      // add the "driver" table for column references (#43663, #39623)
      final int tableNum = hashNode.getTableNumber();
      addDriverTableForColumns(tableNum, origTQIList);
    }

    if (fbtNode != null) {
      this.handleFromBaseTableNode(fbtNode);
      // Add Predicate collected
      PredicateList temp = fbtNode.storeRestrictionList;
      if (temp != null && temp.size() > 0) {
        this.wherePredicateList.add(temp);
      }
      temp = fbtNode.nonStoreRestrictionList;
      if (temp != null && temp.size() > 0) {
        this.wherePredicateList.add(temp);
      }
      //this.handlePredicateList(fbtNode.storeRestrictionList);
      //this.handlePredicateList(fbtNode.nonStoreRestrictionList);
    }

    return node;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsDeltaMerge() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean initForDeltaState() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getAndResetDeltaState() {
    throw new InternalGemFireError("unexpected invocation");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Visitable mergeDeltaState(Object delta, Visitable node) {
    throw new InternalGemFireError("unexpected invocation");
  }

  //Needs to be overrwitten in Concrete classes
  int processResultColumnListFromProjectRestrictNodeAtLevel() {
    return -1;
  }

  public void init() throws StandardException{
    this.prTableCount = this.qic.getPRTableCount();
    Iterator<PredicateList> itr1 = this.wherePredicateList.iterator();
    this.qic.setQuerySectionUnderAnalysis(WHERE_DML_SECTION);
    while (itr1.hasNext()) {
      PredicateList predList = itr1.next();
      this.handlePredicateList(predList);
      itr1.remove();
    }
    this.qic.setQuerySectionUnderAnalysis(DEFAULT_DML_SECTION);

    // 1) For it to be convertible to get , there should be only one
    // TableQueryInfo object in the from list
    // 2) If the primary Key is single then the where clause should have only
    // one condition
    // 3) Else it should be an AND condition with all composite key elements
    // with equality condition
    // 4) Projection list should have exposed name equal to actual column name
    // &
    // order should be same & all be present
    
   
    // Is Convertible to get
    if (this.tableQueryInfoList.size() == 1
        /*&& this.subqueries.size() == 0*/) {
      TableQueryInfo driverTable = this.tableQueryInfoList.get(0);
      setDriverTableQueryInfo(driverTable);
      // Process collected where clause based Sub queries if any now
      this.processSubqueries();
    }
    else {
      final boolean isNCJoinSupportedOnQN = isNCJoinSupportedOnQN(this.qic
          .getPRTableCount());
      final AbstractConditionQueryInfo whereClause = getWhereClause();
      try {
        TableQueryInfo driverTable = identifyDriverTableQueryInfo(
            this.tableQueryInfoList, this.qic, whereClause, this.hasUnionNode()
                || this.hasIntersectOrExceptNode());
        setDriverTableQueryInfo(driverTable);
        this.processSubqueries();
      } catch (StandardException ncException) {
        if (!isNCJoinSupportedOnQN
            || (isNCJoinSupportedOnQN && !ncjSetDriverTable(whereClause))) {
          throw StandardException.plainWrapException(ncException);
        }
      }
    }
  }

  /*
   * For two consecutive PRNs, it's possible that 
   * there is a transposition of projections happening.
   * 
   * In such situations we want to determine whether
   * its a nopProjection or not and based on that 
   * apply projection. Hence we grab the most parent
   * PRN.
   * 
   * e.g. select b from T group by a,b 
   */
  boolean isConsecutivePRNs = false;
  private boolean insertAsSubSelect;
  private String targetTableName;

  void handleProjectRestrictNode(ProjectRestrictNode prn)
  throws StandardException {

    if (prn.restrictionList != null && prn.restrictionList.size() > 0) {
      // prn.restrictionList.accept(this);
      this.wherePredicateList.add(prn.restrictionList);
    }
    this.collectSubqueries(prn);
    this.processProjectRestrictNode(prn);
  }

  void processProjectRestrictNode(ProjectRestrictNode prn) throws StandardException {
  //this.processSubqueries(prn);
    // Get the child result
    ++this.evaluateResultColumnList;
    ResultSetNode child = prn.getChildResult();

    if(!isConsecutivePRNs) {
      qic.setParentPRN(prn);
    }

    if(child instanceof ProjectRestrictNode) {
      isConsecutivePRNs = true;
    }
    else {
      isConsecutivePRNs = false;
    }

    this.visit(child);
    --this.evaluateResultColumnList;
    if (this.evaluateResultColumnList == this.processResultColumnListFromProjectRestrictNodeAtLevel()) {
      ResultColumnList rcl = prn.getResultColumns();
      rcl.accept(this);

      if(this.isSelect()) {
          CollectParameterNodeVisitor collectParams = new CollectParameterNodeVisitor(qic);

          for(int i = 0, size = rcl.size(); i < size; i++) {
            ResultColumn rc = rcl.getResultColumn(i+1);
            /*
             * Even a RC in select projection can have ParameterNode. If there are any,
             * add it to ParameterQI. Also, PRN list we parse it at
             * the last, but this doesn't matters as ParameterIndex is already 
             * decided correctly by Derby.  
             * 
             * select case when count(id) + ? > ? then sum(id) end from tab;
             */
             final ValueNode expr;
             if(rc != null && (expr = rc.getExpression()) != null) {
               expr.accept(collectParams);
             }
          }
      }
    }
  }

  void collectSubqueries(ProjectRestrictNode prn) throws StandardException {
    if (prn.restrictSubquerys != null && prn.restrictSubquerys.size() > 0) {
      this.whereClauseSubqueries.add(prn.restrictSubquerys);
      // We do not use 'GetAll' OR 'GetAll on Local Index' with Where SubQuery.
      this.queryType = GemFireXDUtils.set(this.queryType,
          MARK_NOT_GET_CONVERTIBLE);
    }
  }

  @Override
  public void computeNodes(Set<Object> routingKeysToExecute,
      Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
    assert routingKeysToExecute.size() == 1;
    assert routingKeysToExecute.contains(ResolverUtils.TOK_ALL_NODES);
    // Lets first get the implementation of single table query
    if (this.isSelect() ||  this.isDelete() || this.isUpdate() ) {
      //Region rgn = this.getRegion();
      // If the region is not an instance of PR , implying it is a replicated
      // region
      // then GemfireDistributionActivation would not be created in the first
      // place.
      // The query would be executed locally using Derby's Activation object.
      //assert rgn instanceof PartitionedRegion;
      // PartitionedRegion pr = (PartitionedRegion)rgn;
      // Get all the nodes spanned by the PR
      // TODO:Asif:Instead of copying this set into the set passed try to find a
      // clean
      // way where we can utilize the set passed by PR , at the same time do not
      // have code examining the nature & number of regions involved ,
      // outside the SelectQueryInfo object

      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DMLQueryInfo::computeNodes: Starting number of nodes = "
                + routingKeysToExecute.size());
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DMLQueryInfo::computeNodes: The starting nodes are  = "
                + routingKeysToExecute);
      }

      // Now we need to apply the selectivity as per the where clause.
      // This function is being executed itself implies that the case is not
      // of
      // region.get convertible or
      // that of local execution using derby's activation. This is a case of
      // distribution of query
      // which can be trimmed by the where clause.
      if (this.whereClause != null) {
        this.whereClause.computeNodes(routingKeysToExecute, activation, forSingleHopPreparePhase);
        if (routingKeysToExecute.contains(ResolverUtils.TOK_ALL_NODES)) {
          // TODO: KN for single hop case sometimes size comes greater than 1
          // Need to look but this definitely means non hoppable
          if (!forSingleHopPreparePhase) {
            assert routingKeysToExecute.size() == 1;
          }
          else {
            routingKeysToExecute.clear();
            //routingKeysToExecute.add(ResolverUtils.TOK_ALL_NODES);
          }
        } else if (this.hasUnionNode() || this.hasIntersectOrExceptNode()) { 
          // Handle Union / Intersect / Except
          // TODO-vivek where-clause would be handled for union/intersect/except in future
          // Currently do not allow selective routing objects for Union query
          // But we still go into computeNodes to verify any additional error 
          // that might be present

          routingKeysToExecute.clear();
          routingKeysToExecute.add(ResolverUtils.TOK_ALL_NODES);

          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, 
                "DMLQueryInfo::computeNodes: Node prunning has been rendered ineffective - Set Operators ");
          }
        }
        else if (this.isNCJoinOnQN()) {
          if (routingKeysToExecute.isEmpty()) {
            // TODO - This is workaround to a defect in AndJunctionQueryInfo
            // related to GI and NCJ. Need to fix same
            routingKeysToExecute.add(ResolverUtils.TOK_ALL_NODES);
            if (GemFireXDUtils.TraceNCJ) {
              SanityManager
                  .DEBUG_PRINT(
                      GfxdConstants.TRACE_QUERYDISTRIB,
                      "DMLQueryInfo::computeNodes: Node prunning has been rendered ineffective - NCJ ");
            }
          }
          else {
            // TODO - This is another defect found related to
            // NCJoinTwoNonCollocatedTablesDUnit.testPR_PK_COL_Equality().
            // Need to fix same
            routingKeysToExecute.clear();
            routingKeysToExecute.add(ResolverUtils.TOK_ALL_NODES);
          }
        }
      } 

      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, 
            "DMLQueryInfo::computeNodes: Prunned number of nodes = "
            + routingKeysToExecute.size());
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, 
            "DMLQueryInfo::computeNodes: The prunned nodes are  = "
            + routingKeysToExecute);
      }

    }
    else if (!this.getRegion().getAttributes().getDataPolicy().withStorage()) {
      return;
    }
    else {
      throw new UnsupportedOperationException(
          "computeNodes implemented only for Select/Update/Delete");
    }

  }

  @Override
  public final boolean isPrimaryKeyBased() {
    return GemFireXDUtils.isSet(this.queryType, IS_PRIMARY_KEY_TYPE) ;
  }
  
  /*
   * @return IS_GETALL_ON_LOCAL_INDEX
   */
  public final boolean isGetAllOnLocalIndex() {
    return GemFireXDUtils.isSet(this.queryType, IS_GETALL_ON_LOCAL_INDEX) ;
  }

  /**
   * To be differentiated from HAS_JOIN_NODE, since later can be 
   * true for colocated joins also
   * To be used on Query Node only (QN)
   */
  public final boolean isNCJoinOnQN() {
    return this.ncjMetaData != null;
  }

  /**
   * @return ncjMetaData
   */
  public THashMap getNCJMetaData() {
    return this.ncjMetaData;
  }
  
  @Override
  public boolean isSelectForUpdateQuery() {
    return GemFireXDUtils.isSet(this.queryType, SELECT_FOR_UPDATE) ;
  }

  @Override
  public boolean needKeysForSelectForUpdate() {
    return false;
  }

//  @Override
//  public abstract LocalRegion getRegion();

  @Override
  public String getTableName() {
    // Asif: Should we store it in a local field? Will unnecessarily require
    // memory
    if(isTableVTI()) {
      return this.qic.virtualTable();
    }
    return this.tableQueryInfoList.get(0).getTableName();
  }

  @Override
  public String getFullTableName() {
    if(isTableVTI()) {
      return this.qic.virtualTable();
    }
    return this.tableQueryInfoList.get(0).getFullTableName();
  }

  @Override
  public String getSchemaName() {
    // Asif: Should we store it in a local field? Will unnecessarily require
    // memory
    return this.tableQueryInfoList.get(0).getSchemaName();
  }

  final void handleFromBaseTableNode(FromBaseTable fbtNode)
      throws StandardException {
    final TableQueryInfo tqi = (TableQueryInfo)fbtNode
        .computeQueryInfo(this.qic);
    this.updateColocationMatrixData(tqi);
    this.containerList
        .add((GemFireContainer)tqi.getRegion().getUserAttribute());
    addTableQueryInfoToList(tqi, tqi.getTableNumber());

    handleFromBaseTableNode(fbtNode, true);
  }

  final int numTablesInTableQueryInfoList() {
    int count = 0;
    for (Object o : this.tableQueryInfoList) {
      if(o != null) {
        count++;
      }
    }
    return count;
  }
  
  final void addTableQueryInfoToList(final TableQueryInfo tqi,
      final int tableNum) {
    if (tableNum < this.tableQueryInfoList.size()) {
      TableQueryInfo prev = this.tableQueryInfoList.set(tableNum, tqi);
      // [vivek] no idea of history of this assertion, but this should go
      // I am also not clear why one gets duplicate call, should investigate.
      // One case is presence of both primary and secondary region of driver table 
      if (! (this.hasUnionNode() || this.hasIntersectOrExceptNode())) {
        assert prev == null;
      } 
    }
    else {
      // Expand the list
      for (int i = this.tableQueryInfoList.size(); i < tableNum; ++i) {
        this.tableQueryInfoList.add(null);
      }
      this.tableQueryInfoList.add(tqi);
      // below assertion no longer is valid since tableNum can be different
      // from tqi.getTableNumber() for virtual HalfJoinNode, FromSubqueryNode
      // tables where the actual "driver" TableQueryInfo is used
      //assert tqi.getTableNumber() == this.tableQueryInfoList.size() - 1;
    }
  }

  final void addDriverTableForColumns(final int tableNum,
      final ArrayList<TableQueryInfo> origTQIList) {
    // add the "driver" table for column references (#43663, #39623)
    if (tableNum >= 0) {
      TableQueryInfo driverTQI = null;
      Iterator<TableQueryInfo> newIter = this.tableQueryInfoList.iterator();
      Iterator<TableQueryInfo> iter = origTQIList.iterator();
      TableQueryInfo tqi, newTQI;
      while (newIter.hasNext()) {
        tqi = null;
        newTQI = newIter.next();
        if (iter.hasNext()) {
          tqi = iter.next();
        }
        if (tqi == null && newTQI != null) {
          // newly added
          if (newTQI.isPartitionedRegion()) {
            driverTQI = newTQI;
            // break on first partitioned region
            break;
          }
          else if (driverTQI == null) {
            driverTQI = newTQI;
          }
        }
      }
      if (driverTQI != null) {
        addTableQueryInfoToList(driverTQI, tableNum);
      }
    }
  }

  void updateColocationMatrixData(TableQueryInfo tqi) {
    int partCol = tqi.getPartitioningColumnCount();
    this.colocMatrixTables.add(tqi);
    if (partCol > 0) {
      if (this.colocMatrixRows == 0) {
        this.colocMatrixRows = partCol;
      }
      ++this.colocMatrixPRTableCount;
    }
  }

  void handleFromBaseTableNode(FromBaseTable fbtNode,
      boolean postParentClassHandling) throws StandardException {
    // nothing to be done here. Override in the child classes for specific
    // handlings.
    return;
  }

  void handlePredicateList(PredicateList predicateList) throws StandardException {
    predicateList.accept(this);
    //Check Redundant predicate list
    Iterator<?> itr = predicateList.redundantPredicates.iterator();
    while(itr.hasNext()) {
      Predicate predicate = (Predicate)itr.next();
      predicate.accept(this);
    }
  }

  void handleAndNode(AndNode andNode) throws StandardException {
    boolean isLeftRR = false, isRightPR = false;
    boolean hasPR = false;
    Iterator<?> iter = this.logicalLeftTableList.iterator();
    while (iter.hasNext()) {
      LocalRegion r = (LocalRegion) iter.next();
      if (r.getPartitionAttributes() == null) {
        isLeftRR = true;
        break;
      }
    }
  
    iter = this.logicalRightTableList.iterator();
    while (iter.hasNext()) {
      LocalRegion r = (LocalRegion) iter.next();
      if (r.getPartitionAttributes() != null) {
        isRightPR = true;
        break;
      }
    }
    //See #46979, RR left outer join PR with IS NULL
    if (isLeftRR && isRightPR && andNode.leftOperand instanceof IsNullNode) {
      IsNullNode node = (IsNullNode) andNode.leftOperand;
      ValueNode vn = node.getOperand();
       if (vn instanceof ColumnReference) {
         throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
         "Outer join with IS NULL is not supported."); 
       }
      
    }

    AbstractConditionQueryInfo temp = this.processJunctionNode(andNode,
        QueryInfoConstants.AND_JUNCTION, true);
    if (this.whereClause != null) {
      this.whereClause = this.whereClause.mergeOperand(temp,
          QueryInfoConstants.AND_JUNCTION, true);
    }
    else {
      this.whereClause = temp;
    }
  }

  private void handleJoinNode(JoinNode joinNode) throws StandardException {
    if (joinNode instanceof HalfOuterJoinNode) {
      HalfOuterJoinNode ojNode = (HalfOuterJoinNode)joinNode;
      this.isOuterJoin = true;
      String logicalLeftTableName = ojNode.getLogicalLeftTableName();
      if (logicalLeftTableName != null) {
        Region<Object, Object> lregion = Misc
            .getRegionForTableByPath(logicalLeftTableName, false);
        if (lregion != null) {
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_OUTERJOIN_MERGING,
                    "DMLQueryInfo::handleJoinNode"
                        + "ltable name being added to list of logical left tables is: "
                        + logicalLeftTableName);
          }
          this.logicalLeftTableList.add(lregion);
          if (ojNode.getRightHalfOuterJoinNode() != null) {
            this.handleJoinNode(ojNode.getRightHalfOuterJoinNode());
          }
                    
          // add all tables in the LOJ query to logicalLeftTableList
          // SelecQueryInfo.getDriverRegionsForOuterJoins will then only 
          // return tables up to first PR in the query as driver tables of
          // SpecialCaseOuterJoinIterator
          String rightTableName = null;
          if ((rightTableName = ojNode.getLogicalRightTableName()) != null) {
            Region<Object, Object> rregion = Misc.getRegionForTableByPath(
                rightTableName, false);
            if (rregion != null) {
              if (GemFireXDUtils.TraceOuterJoin) {
                SanityManager
                  .DEBUG_PRINT(
                      GfxdConstants.TRACE_OUTERJOIN_MERGING,
                      "DMLQueryInfo::handleJoinNode"
                      + "rtable name being added to list of logical left tables is: "
                      + rightTableName);
              }
              this.logicalLeftTableList.add(rregion);
            }
          }
        }
        else {
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_OUTERJOIN_MERGING,
                    "DMLQueryInfo::handleJoinNode"
                        + "all logical left table names of node: " + ojNode + " being added");
          }
          ojNode.addAllLogicalLeftTableNamesToList(this.logicalLeftTableList);
        }
      }
      else {
        HalfOuterJoinNode leftHalfNode = ojNode.getLeftHalfOuterJoinNode();
        if (leftHalfNode != null) {
          leftHalfNode
              .addAllLogicalLeftTableNamesToList(this.logicalLeftTableList);
        }

        HalfOuterJoinNode rightHalfNode = ojNode.getRightHalfOuterJoinNode();
        if (rightHalfNode != null) {
          rightHalfNode
            .addAllLogicalLeftTableNamesToList(this.logicalLeftTableList);
        }

      }
      
      String logicalRightTableName = ojNode.getLogicalRightTableName();
      if (logicalRightTableName != null) {
        Region<Object, Object> rRegion = Misc
        .getRegionForTable(logicalRightTableName, false);
        if (rRegion != null) {
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_OUTERJOIN_MERGING,
                "DMLQueryInfo::handleJoinNode"
                    + "table name being added to list of logical right tables is: "
                    + logicalRightTableName);
          }
          this.logicalRightTableList.add(rRegion);
        }
      }
    }
  }

  private void handleSubqueryNode(SubqueryNode sqn) throws StandardException {
    if (sqn.hasCorrelatedCRsFromOuterScope()) {
      this.queryType = GemFireXDUtils.set(this.queryType,
          HAS_PR_DEPENDENT_SUBQUERY);
    }
  }

  private AbstractConditionQueryInfo processJunctionNode(
      BinaryOperatorNode bon, int junctionType, boolean isTopLevel) throws StandardException {
    this.currJunctionType = junctionType;
    AbstractConditionQueryInfo lhs = this.generateQueryInfo(bon.leftOperand);
    this.currJunctionType = junctionType;
    AbstractConditionQueryInfo rhs = this.generateQueryInfo(bon.rightOperand);
    // If where clause is null handle colocation matrix
     
    if (lhs != null ) {
      lhs.seedColocationMatrixData(
          this.colocMatrixRows, this.colocMatrixTables,
          this.colocMatrixPRTableCount);
    }
    if (rhs != null) {
      rhs.seedColocationMatrixData(
          this.colocMatrixRows, this.colocMatrixTables,
          this.colocMatrixPRTableCount);
    }    

    if (lhs != null) {
      return lhs.mergeOperand(rhs, junctionType,isTopLevel);
    }
    else {
      return rhs;
    }
  }

  private AbstractConditionQueryInfo generateQueryInfo(QueryTreeNode node)
      throws StandardException {
    if (node instanceof OrListNode) {
      node = ((OrListNode)node).getOrNode(0);
    }
    if (node instanceof AndNode) {
      return processJunctionNode((BinaryOperatorNode)node,
          QueryInfoConstants.AND_JUNCTION, false);
    }
    else if (node instanceof OrNode) {
      return processJunctionNode((BinaryOperatorNode)node,
          QueryInfoConstants.OR_JUNCTION, false);
    }
    else if (node instanceof BooleanConstantNode) {
      return processBooleanConstantNode((BooleanConstantNode)node);
    }
    else {
      QueryInfo temp = node.computeQueryInfo(this.qic);
      if (temp instanceof ColumnQueryInfo) {
        // This is apparently a situation where boolean column can act as a
        // standalone condition without relational operator. Since it cannot
        // contribute to nodes pruning, we can safely return null. For surity we
        // will assert the boolean data type of the column.
        assert ((ColumnQueryInfo)temp).columnDescriptor.getType().getTypeId()
            .equals(TypeId.BOOLEAN_ID);
        temp = null;
      }
      else if (temp == QueryInfoConstants.DUMMY && node instanceof SubqueryNode) {
        temp = null;
      }
      else if (temp != null && !(temp instanceof AbstractConditionQueryInfo)) {
        if (temp == QueryInfoConstants.DUMMY
            && (node instanceof BinaryOperatorNode
                || node instanceof UnaryOperatorNode)) {
          temp = null;
        }
        else {
          if(GemFireXDUtils.isSet(this.queryType, HAS_JOIN_NODE)) {
            throw StandardException.newException(SQLState.LANG_NON_BOOLEAN_JOIN_CLAUSE); 
          }else {            
            throw StandardException.newException(SQLState.LANG_BINARY_LOGICAL_NON_BOOLEAN,
                new IllegalStateException("Handle the node to have right "
                    + "computeQueryInfo method. The problematic node type is "
                    + node.getClass()));
          }
        }
      }
      else if (temp == null && node instanceof LikeEscapeOperatorNode) {
        /**
         * Since @see LikeEscapeOperatorNode .computeQueryInfo(QueryInfoContext)
         * has been called, and return null since its not implemented, we cannot
         * use 'GetAll on Local Index' with Like operator.
         */
        this.queryType = GemFireXDUtils.set(this.queryType,
            MARK_NOT_GET_CONVERTIBLE);
      }
      return (AbstractConditionQueryInfo)temp;
    }
  }

  private BooleanConstantQueryInfo processBooleanConstantNode(
      BooleanConstantNode bcn) {
    if ((this.currJunctionType == QueryInfoConstants.AND_JUNCTION && bcn
        .isBooleanTrue())
        || (this.currJunctionType == QueryInfoConstants.OR_JUNCTION && bcn
            .isBooleanFalse())) {
      return null;
    }
    else {
      // ("Handle the peculiar where clause which is an AND junction false or an
      // OR junction with true");
      return new BooleanConstantQueryInfo(bcn.isBooleanTrue());
    }
  }

  boolean isWhereClauseSatisfactory(TableQueryInfo tqi)
      throws StandardException {
    if (DMLQueryInfo.disableGetConvertible()) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DMLQueryInfo.isWhereClauseSatisfactory:"
                + " GetConvertible Queries Disabled");
      }
      return false;
    }

    boolean isOk = false;
    if (this.whereClause != null) {
      if (tqi.isPrimaryKeyBased()) {
        int[][] pkColumn = tqi.getPrimaryKeyColumns();
        //Set
        if(this.isUpdate()){
          this.whereClause.isPartofUpdate = true;
        }
        this.pk = this.whereClause.isConvertibleToGet(pkColumn, tqi);
        if(this.pk != null){
          this.otherKeys = this.whereClause.getAllConditions(pkColumn, tqi);
        }
        if (GemFireXDUtils.isSet(this.queryType, MARK_NOT_GET_CONVERTIBLE)) {
          this.pk = null;
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "DMLQueryInfo.isWhereClauseSatisfactory:"
                    + " markNotGetConvertible");
          }
        }
        isOk = this.pk != null;
      }

      /* Note:
       * 1. Evaluate possibility for GetAll on Local Index
       * 2. GetAll on LocalIndex would only be performed on Partitioned region
       * 3. In case of multiple Local Indexes, it is possible that Optimizer has
       *        not selected the Index with exactly matches the predicate-keys, 
       *        but this is an Optimizer issue and tracked by #47292. 
       * 4. If region has a loader, GetAll on LocalIndex cannot handle loader on
       *        such queries. Loader only works with PK based get/GetAll.
       */
      if (!isOk) {
        LocalRegion region = tqi.getRegion();
        boolean hasLoader = ((GemFireContainer)region.getUserAttribute())
            .getHasLoaderAnywhere();
        String indexRegion = null;
        if (this.chosenLocalIndexDescriptor != null
            && tqi.isPartitionedRegion() && !hasLoader
            && !DMLQueryInfo.disableGetAll_LocalIndex()) {
          IndexDescriptor id = this.chosenLocalIndexDescriptor
              .getIndexDescriptor();
          int[] baseColPos = id.baseColumnPositions();
          int[][] idxColumns = TableQueryInfo.getIndexKeyColumns(baseColPos);
          GemFireContainer indexContainer = Misc.getMemStore().getContainer(
              ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
                  this.chosenLocalIndexDescriptor.getConglomerateNumber()));
          indexRegion = indexContainer.getQualifiedTableName();
          Object idxKey = this.whereClause.isConvertibleToGetOnLocalIndex(
              idxColumns, tqi);
          if (idxKey != null
              && !GemFireXDUtils.isSet(this.queryType, MARK_NOT_GET_CONVERTIBLE)) {
            this.localIndexKey = idxKey;
          }
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager
              .DEBUG_PRINT(
                  GfxdConstants.TRACE_QUERYDISTRIB,
                  "DMLQueryInfo.isWhereClauseSatisfactory:"
                      + " Evaluate GetAll on Local Index [index-name= "
                      + (this.chosenLocalIndexDescriptor != null ? this.chosenLocalIndexDescriptor
                          .getConglomerateName() : "null")
                      + " ,index-key= "
                      + (this.localIndexKey != null ? this.localIndexKey
                          : "null")
                      + "], region="
                      + region.getDisplayName()
                      + " ,isPartitioned="
                      + tqi.isPartitionedRegion()
                      + "], index-region="
                      + indexRegion
                      + " ,hasLoader="
                      + hasLoader
                      + " ,force-Ignore-GetAllLocalIndex="
                      + DMLQueryInfo.disableGetAll_LocalIndex()
                      + " ,markNotGetConvertible="
                      + GemFireXDUtils.isSet(this.queryType,
                          MARK_NOT_GET_CONVERTIBLE));
        }
      }
    }
    return isOk;
  }

  /**
   * @return the chosenLocalIndexDescriptor
   */
  public ConglomerateDescriptor getChosenLocalIndex() {
    return chosenLocalIndexDescriptor;
  }
  
  /**
   * Select best possible index for GetAllLocalIndexExecutorMessage
   */
  private void setChosenLocalIndex(FromBaseTable fbtNode) {
    ConglomerateDescriptor cd = fbtNode.getTrulyTheBestAccessPath()
        .getConglomerateDescriptor();
    if (cd.isIndex() || cd.isConstraint()) {
      IndexDescriptor id = cd.getIndexDescriptor();
      if (id.indexType().equals(GfxdConstants.LOCAL_SORTEDMAP_INDEX_TYPE)) {
        this.chosenLocalIndexDescriptor = cd;
      }
    }
  }

  public TableQueryInfo getTableQueryInfo(int index) {
    return (index < this.tableQueryInfoList.size() ? this.tableQueryInfoList
        .get(index) : null);
  }
  
  //need to know in come cases where 
  public boolean isCustomEvictionEnabled() {
    return getRegion().getDataPolicy().withHDFS()
        && (getRegion().getAttributes().getCustomEvictionAttributes() != null);
  }

  public final List<TableQueryInfo> getTableQueryInfoList() {
    return Collections.unmodifiableList(this.tableQueryInfoList);
  }

  public final List<GemFireContainer> getContainerList() {
    return this.containerList;
  }

  @Override
  public final boolean isQuery(int flags) {
    return (queryType & flags) == flags;
  }

  @Override
  public final boolean isQuery(int ... flags) {
    for (int i = 0; i < flags.length; i++) {
      if ((queryType & flags[i]) == flags[i]) {
        return true;
      }
    }
    return false;
  }
  
  /**
   * Test API
   * 
   * @return AbstractConditionQueryInfo object representing the where clause
   */
  final AbstractConditionQueryInfo getWhereClause() {
    return this.whereClause;
  }

  @Override
  public boolean isDynamic() {
    return this.qic.getParamerCount() > 0 ;
  }

  /**
   * 
   * @return true if the where clause is not statically evaluatable i.e the
   *         where clause is parameterized , which will usually be the case with
   *         PreparedStatement, false otherwise
   */

  public boolean isWhereClauseDynamic() {
    return this.whereClause.isWhereClauseDynamic();
  }

  @Override
  public int getParameterCount() {
    return this.qic.getParamerCount();
  }

  @Override
  public Object getPrimaryKey() {
    return this.pk;
  }

  public Object getOtherKeys() {
    return this.otherKeys;
  }
  
  @Override
  public Object getLocalIndexKey() {
    return this.localIndexKey;
  }
  
  @Override
  public boolean isSelect() {
    return false;
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }

  @Override
  public boolean withSecondaries() {
    return false;
  }
  
  @Override
  public boolean isUpdate() {
    return false;
  }

  @Override
  public boolean isDelete() {
    return false;
  }

  @Override
  public boolean isDeleteWithReferencedKeys() {
    return false;
  }

  @Override
  public boolean isInsert() {
    return false;
  }

  @Override
  public final boolean createGFEActivation() throws StandardException
  {
        final boolean createQueryInfo = qic.createQueryInfo();

        final boolean isUpdate = isUpdate();
        // Asif: create Derby's byte codes only if it is not a Select Query
        // or if the Select Query is on the data node
        if (GemFireXDUtils.TraceActivation) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
              "GenericStatement::createGFEActivation createQueryInfo : "
                  + createQueryInfo + " isSelect " + isSelect() + " isUpdate "
                  + isUpdate);
        }

        if (!createQueryInfo) {
          return false;
        }

        if (!(isSelect() || isUpdate || isInsert() || isDelete())) {
          return false;
        }

        if (isInsert() && !((InsertQueryInfo)this).isInsertAsSubSelect()) {
          return false;
        }

        final boolean isTableVTIDistributable = routeQueryToAllNodes();

        // no GFE activation for updatable VTIs
        if (isTableVTIDistributable && isUpdate) {
          return false;
        }

        if (isPrimaryKeyBased()) {
          return !GemFireXDUtils.isSet(this.queryType, EMBED_GFE_RESULTSET);
        }
        
        final InternalDistributedSystem dsys = Misc.getDistributedSystem();
        if (!dsys.isLoner() && isGetAllOnLocalIndex()) {
          return !GemFireXDUtils.isSet(this.queryType, EMBED_GFE_RESULTSET);
        }

/*        if (isSelect()
            && GemFireXDUtils.isSet(this.queryType, DO_SPECIAL_REGION_SIZE)) {
          return false;
        }
*/
        if (isTableVTIDistributable) {
          return true;
        }

        // It is possible that the data is present in the parse node
        // itself. So we need to identify it here & generate the actual
        // Activation class
        // TODO:Asif: The below is a stopgap fix & it needs to be made more
        // cleaner. Otherwise there will be perf issue if the parse node
        // happens to be one of the data store also as it will otherwise cause
        // FunctionRouting to start the query on this node again
        // Get the underlying region ( currently assume a single region query)
        final Region<?, ?> rgn = getRegion();

        // Asif: If region cannot be identifed or is not available assume that the
        // query will be executed locally
        if (rgn == null) {
          return false;
        }
        final RegionAttributes<?, ?> ra = rgn.getAttributes();
        // If the region is a Replicated Region or if it is a PR with just
        // itself as a member then we should go with Derby's Activation Object
        final DataPolicy policy = ra.getDataPolicy();
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceActivation) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                "DMLQueryInfo#createGFEActivation:: region: " + rgn.getName()
                    + " region's DataPolicy: " + policy
                    + " region's Scope: " + ra.getScope()
                    + " isLoner: " + dsys.isLoner()
                    + " mcast-port: " + dsys.getConfig().getMcastPort()
                    + " locators: " + dsys.getConfig().getLocators());
          }
        }
        return (!dsys.isLoner() && !ra.getScope().isLocal()
               && (policy.withPartitioning() || !policy.withStorage()
                   || isDeleteWithReferencedKeys()
                   // use GFE activation for SELECT FOR UPDATE to enable
                   // proper lock upgrade during execution (#43937)
                   || isSelectForUpdateQuery()))
               || (getTestFlagIgnoreSingleVMCriteria()
                   && !policy.withReplication() && !ra.getScope().isLocal());
  }

  public void addSubQueryInfo(SubQueryInfo subq) {
    this.subqueries.add(subq);
  }

  void processSubqueries() throws StandardException {
    Iterator<SubqueryList> subqueries = this.whereClauseSubqueries.iterator();
    while (subqueries.hasNext()) {
      SubqueryList list = subqueries.next();
      int size = list.size();
      for (int i = 0; i < size; ++i) {
        SubqueryNode sqn = (SubqueryNode)list.elementAt(i);
        boolean hasDependent = sqn.hasCorrelatedCRsFromOuterScope();
        SubQueryInfo sqi = new SubQueryInfo(qic, sqn.getSubqueryString(),
            hasDependent);
        try {
          sqn.getResultSet().accept(sqi);
          sqi.init();
        } finally {
          qic.cleanUp();
          qic.popScope();
        }
      }
    }
    if (this.subqueries.size() > 0) {
      for (SubQueryInfo sqi : this.subqueries) {
        // skip index 0 as it contains Root Table Query Info
        // tempSubQueryTables.addAll(sqi.tableQueryInfoList.subList(1,
        // sqi.tableQueryInfoList.size()));
        this.needGfxdSubactivation = this.needGfxdSubactivation
            || sqi.needGfxdSubactivation;
      }
    }
  }

  public List<SubQueryInfo> getSubqueryInfoList() {
    return Collections.unmodifiableList(this.subqueries);
  }

  private static TableQueryInfo identifyDriverTableQueryInfo(
      final List<TableQueryInfo> tableQueryInfoList, QueryInfoContext qic,
      final AbstractConditionQueryInfo whereClause,
      final boolean hasSetOperatorClause) throws StandardException {
    final int prTableCount = qic.getPRTableCount();
    // If the tables in query is more than one, then the query will be
    // executable only if
    // 1) All the tables are replicated in same server groups
    // 2) One PR , rest all replicated in same server groups
    // 3) If contains more than one PR, all the PR tables should be
    // colocated with same master

    // TODO: Asif: If there are more than one PR but only having a single
    // node (the accessor node), the query should be allowed to execute locally.
    // How to handle it?
    TableQueryInfo tqi = null;
    boolean isCaseOfSingleNode = false;
    TableQueryInfo driverTqi = null;

    // Ensure that all the PR type tables are colocated & then there exists
    // right equi join conditions.
    // Identify all the PR TableQueryInfo objects. They should all have a
    // common root master table.
    String masterTable = null;
    SortedSet<String> serverGroups = null;
    TableQueryInfo sgMasterTable = null;
    TableQueryInfo prTqi = null;
    Iterator<TableQueryInfo> itr = null;

    itr = tableQueryInfoList.iterator();

    while (itr.hasNext()) {
      tqi = itr.next();
      if (tqi == null) {
        continue;
      }
      if (prTqi != null && hasSetOperatorClause) {
        // Handle Union, Intersect or Except
        // Why to go in loop if already selected one partitioned region
        // as driver table; lets stick with first partitioned table
        break;
      }
      if (tqi.isPartitionedRegion()) {
        if (prTableCount > 1) {
          // If all the Prs have only one node i.e accessor node then the
          // query should be allowed to execute locally
          final boolean singleVMCase = isSingleVMCase()
              || qic.isColocationCheckDisabled();
          if (isCaseOfSingleNode) {
            if (!singleVMCase) {
              throw StandardException
                  .newException(SQLState.NOT_COINCIDING_DATASTORES);
            }
            // else OK
          }
          else if (singleVMCase && masterTable == null) {
            isCaseOfSingleNode = true;
          }
          else {
            if (masterTable == null) {
              masterTable = tqi.getMasterTableName();
            }
            if (masterTable == null
                || !masterTable.equalsIgnoreCase(tqi.getMasterTableName())) {
              if (masterTable == null) {
                throw StandardException.newException(
                    SQLState.COLOCATION_NOT_DEFINED, tqi.getFullTableName());
              }
              else if (!qic.isColocationCheckDisabled()) {
                throw StandardException.newException(
                    SQLState.NOT_COLOCATED_WITH, tqi.getFullTableName(),
                    Misc.getFullTableNameFromRegionPath(masterTable));
              }
            }
          }
        }
        prTqi = tqi;
      }
      else {
        SortedSet<String> currSGs = getServerGroups(tqi);
        if (serverGroups == null) {
          serverGroups = currSGs;
          sgMasterTable = tqi;
        }
        else {
          int cmp = GemFireXDUtils.setCompare(serverGroups, currSGs);
          // If server groups of one replicated table is not contained
          // within those of other then reject the query since currently there
          // is no mechanism to determine if there are any common servers or
          // route query to just the common servers or server groups.
          if (cmp < -1) {
            throw StandardException.newException(
                SQLState.NOT_COLOCATED_WITH,
                tqi.getFullTableName() + "[server groups:"
                    + SharedUtils.toCSV(currSGs) + "]",
                sgMasterTable.getFullTableName() + "[server groups:"
                    + SharedUtils.toCSV(serverGroups) + "]");
          }
          else if (cmp == 1) {
            serverGroups = currSGs;
            sgMasterTable = tqi;
          }
        }
      }
    }
    // If this is a mix of replicated and PR tables, and replicated tables
    // do have server groups then we need to go through the PRs again and
    // check that the minimum set determined for replicated tables server
    // groups is a superset of at least one PR which shall then be used
    // for routing the query.
    if (serverGroups == null) {
      if (prTqi != null) {
        driverTqi = prTqi;
      }
    }
    else if (prTqi != null) {
      // mix of replicated and PR regions
      if (serverGroups != GemFireXDUtils.SET_MAX) {
        SortedSet<String> currSGs = null;
        itr = tableQueryInfoList.iterator();
        while (itr.hasNext()) {
          tqi = itr.next();
          if (tqi == null) {
            continue;
          }
          if (tqi.isPartitionedRegion()) {
            currSGs = getServerGroups(tqi);
            if (GemFireXDUtils.setCompare(serverGroups, currSGs) >= 0) {
              // found a PR whose server groups are subset of the RR
              driverTqi = tqi;
              break;
            }
          }
        }
        if (driverTqi == null) {
          currSGs = getServerGroups(prTqi);
          throw StandardException.newException(
              SQLState.NOT_COLOCATED_WITH,
              prTqi.getFullTableName() + "[server groups:"
                  + SharedUtils.toCSV(currSGs) + "]",
              sgMasterTable.getFullTableName() + "[server groups:"
                  + SharedUtils.toCSV(serverGroups) + "]");
        }
      }
      else {
        driverTqi = prTqi;
      }
    }
    else if (sgMasterTable != null) {
      // only replicated regions
      driverTqi = sgMasterTable;
    }
    // Handle Union, Intersect or Except
    // Disabled this check on unions or Intersection/Except
    if (prTableCount > 1 && !hasSetOperatorClause) {
      final String reason;
      // Now check for the where clause colocation matrix state.
      if (!isCaseOfSingleNode) {
        if (whereClause == null) {
          throw StandardException.newException(
              SQLState.COLOCATION_CRITERIA_UNSATISFIED,
              "cross join attempted on multiple partitioned tables");
        }
        else if ((reason = whereClause
            .isEquiJoinColocationCriteriaFullfilled(null)) != null) {
          throw StandardException.newException(
              SQLState.COLOCATION_CRITERIA_UNSATISFIED, reason);
        }
      }
    }

    return driverTqi;
  }

  static SortedSet<String> getServerGroups(TableQueryInfo tqi)
      throws StandardException {
    SortedSet<String> sgs = null;
    if (tqi == null) {
      return null;
    }
    DistributionDescriptor dd = tqi.getTableDescriptor()
        .getDistributionDescriptor();
    if (dd != null) {
      sgs = dd.getServerGroups();
    }
    // no server groups means all members of DS so set to SET_MAX
    if (sgs == null || sgs.size() == 0) {
      return GemFireXDUtils.SET_MAX;
    }
    return sgs;
  }

  // Test method
  boolean hasCorrelatedSubQuery() {
    boolean found = false;
    for (SubQueryInfo sqi : this.subqueries) {
      if (sqi.isCorrelated()) {
        found = true;
        break;
      }
    }
    return found;
  }

  @Override
  public LocalRegion getRegion() {
    if (this.driverTqi == UNINITIALIZED) {
      throw new AssertionError("SelectQueryInfo#getRegion: init not invoked!");
    }
    return this.driverTqi != null ? this.driverTqi.getRegion() : null;
  }

  TableQueryInfo getDriverTableQueryInfo() {
    if (this.driverTqi == UNINITIALIZED) {
      throw new AssertionError("SelectQueryInfo#getDriverTableQueryInfo: init not invoked!");
    }
    return this.driverTqi;
  }
  
  private void setDriverTableQueryInfo(TableQueryInfo tqi) {
    this.driverTqi = tqi;
    // initialize the set of other PR regions
    setOtherPRegions();
  }

  public boolean isDriverTableInitialized() {
    return this.driverTqi != UNINITIALIZED;
  }
  
  public static boolean isSingleVMCase() {
    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    // #44613: SingleVMCase is determined by whether the VM is a loner (mcast-port=0 and no locators),
    // instead of current number of profiles (VMs). Otherwise, other VM may join the system later.
    // Also, the colocation criteria for two tables should be determined by the partition
    // definition (foreign key, colocate with etc.), not on the numbers of VMs currently present 
    return dsys.isLoner() && !TEST_FLAG_IGNORE_SINGLE_VM_CRITERIA;
//  final boolean singleVMCase = ((PartitionedRegion)tqi.getRegion())
//      .getRegionAdvisor().getNumProfiles() == 0
//      && !TEST_FLAG_IGNORE_SINGLE_VM_CRITERIA;
  }
  
  @Override
  public List<Region> getOuterJoinRegions() {
    return this.logicalLeftTableList;
  }

  @Override
  public boolean isOuterJoin() {
    return this.isOuterJoin;
  }

  @Override
  public void setOuterJoinSpecialCase() {
    this.isSpecialCaseOFOuterJoins = true;
  }

  @Override
  public boolean isOuterJoinSpecialCase() {
    return this.isSpecialCaseOFOuterJoins;
  }

  @Override
  public void setInsertAsSubSelect(boolean b, String targetTableName) {
    this.insertAsSubSelect = true;
    this.targetTableName = targetTableName;
    this.qic.setOptimizeForWrite(true);
  }
  
  @Override
  public boolean isInsertAsSubSelect() {
    return this.insertAsSubSelect;
  }
  
  @Override
  public String getTargetTableName() {
    return this.targetTableName;  
  }
  
  int getPRTableCount() {
    return this.prTableCount;
  }

  public boolean isRemoteGfxdSubActivationNeeded() {
    return this.needGfxdSubactivation;
  }

  public boolean isSubQueryInfo() {
    return false;
  }

  public boolean isSubQuery() {
    return GemFireXDUtils.isSet(queryType, IS_SUBQUERY);
  }

  public void setIsSubQueryFlag(boolean flag) {
    this.queryType = GemFireXDUtils.set(this.queryType,IS_SUBQUERY,flag);
  }
  
  public boolean isNcjLevelTwoQueryWithVarIN() {
    return GemFireXDUtils.isSet(queryType, NCJ_LEVEL_TWO_QUERY_HAS_VAR_IN);
  }

  public void setNcjLevelTwoQueryWithVarIN(boolean flag) {
    this.queryType = GemFireXDUtils.set(this.queryType,
        NCJ_LEVEL_TWO_QUERY_HAS_VAR_IN, flag);
  }
  
  public boolean isPreparedStatementQuery() {
    return this.qic.isPreparedStatementQuery();
  }

 
  public boolean isSubqueryFlatteningAllowed() {
    return !GemFireXDUtils.isSet(queryType, DISALLOW_SUBQUERY_FLATTENING);
  }
  
  public void disallowSubqueryFlattening() {
    this.queryType = GemFireXDUtils.set(this.queryType,
        DISALLOW_SUBQUERY_FLATTENING);
  }

  public static void setTestFlagIgnoreSingleVMCriteria(boolean on) {
    TEST_FLAG_IGNORE_SINGLE_VM_CRITERIA = on;
  }

  static boolean getTestFlagIgnoreSingleVMCriteria() {
    return TEST_FLAG_IGNORE_SINGLE_VM_CRITERIA;
  }

  public static boolean disableGetConvertible() {
    String qt = PropertyUtil.findAndGetProperty(Misc.getMemStoreBooting()
        .getProperties(), GfxdConstants.GFXD_DISABLE_GET_CONVERTIBLE,
        Attribute.DISABLE_GET_CONVERTIBLE);
    if (qt != null) {
      if (Boolean.parseBoolean(qt)) {
        // Only valid value is true
        return true;
      }
    }

    // Default is false
    return false;
  }

  public static boolean disableGetAll_LocalIndex() {
    String qt = PropertyUtil.findAndGetProperty(Misc.getMemStoreBooting()
        .getProperties(), GfxdConstants.GFXD_DISABLE_GETALL_LOCALINDEX,
        Attribute.DISABLE_GETALL_LOCALINDEX);
    if (qt != null) {
      if (Boolean.parseBoolean(qt)) {
        // Only valid value is true
        return true;
      }
    }

    // Default is false
    return false;
  }

  public static boolean enableGetAll_LocalIndex_withEmbedGfe() {
    String qt = PropertyUtil.findAndGetProperty(Misc.getMemStoreBooting()
        .getProperties(),
        GfxdConstants.GFXD_ENABLE_GETALL_LOCALINDEX_EMBED_GFE,
        Attribute.ENABLE_GETALL_LOCALINDEX_EMBED_GFE);
    if (qt != null) {
      if (Boolean.parseBoolean(qt)) {
        // Only valid value is true
        return true;
      }
    }

    // Default is false
    return false;
  }

  @Override
  public boolean isDML() {
    return true;
  }

  /**
   * Handle Union, Intersect Or Except Node
   */
  private int setOperatorCount = 0;
  
  public void verifySetOperationSupported() throws StandardException {
    // if (this.createGFEActivation()) 
    // Currently this method is only called from  AbstractGemfireDistributionActivation, 
    // so no need to verify again that current query execution plan is of distribution

    if (setOperatorCount > 1 && this.driverTqi.isPartitionedRegion()) {
      // TODO This assertion is to avoid scenario of complex query
      // having multiple set operators in case of distribution - till 
      // they are supported. 
      // Though on single node such scenario would be supported leveraging derby
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED, 
      " Currently nested Set operators are not supported in distributed scenario ");
    }
    if (isOuterJoinSpecialCase()) {
      // TODO This assertion is to avoid scenario of complex query
      // having set operators with outer joins which uses region and key information
      // in case of distribution - till they are supported. 
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED, 
      " Currently Set operators are not supported with outer joins " +
      " especially in distributed scenario ");
    }
    if (isSubQuery()) {
      // TODO This assertion is to avoid scenario of complex query
      // having set operators within a SubQuery, in both the cases of
      // single node and distribution - till they are supported.
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
          " Currently Set operators are not supported with SubQueries ");
    }
  }

  /**
   * Handle Union
   */
  private boolean hasUnionNode = false;

  protected void setHasUnionNode() {
    this.hasUnionNode = true;
    setOperatorCount++;
  }

  @Override
  public boolean hasUnionNode() {
    return hasUnionNode;
  }

  /**
   * Handle Intersect Or Except Node
   */
  private String nameOfIntersectOrExceptOperator = null;

  protected void setHasIntersectOrExceptNode(String opName) {
    this.nameOfIntersectOrExceptOperator = opName;
    setOperatorCount++;
  }
  
  public String getNameOfIntersectOrExceptOperator() {
    return this.nameOfIntersectOrExceptOperator;
  }

  @Override
  public boolean hasIntersectOrExceptNode() {
    return nameOfIntersectOrExceptOperator != null;
  }

  /**
   * Handle Union, Intersect or Except Queries
   */
  protected Set<PartitionedRegion> otherPartitionRegions;

  /**
   * Handle Union, Intersect or Except Queries
   * Return list of partition that are not driver table
   */
  public Set<PartitionedRegion> getOtherRegions() {
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      if (this.otherPartitionRegions != null
          && !this.otherPartitionRegions.isEmpty()) {
        ArrayList<String> prs = new ArrayList<String>(
            this.otherPartitionRegions.size());
        for (PartitionedRegion pr : this.otherPartitionRegions) {
          prs.add(pr.getFullPath());
        }
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DMLQueryInfo::getOtherRegions: " + prs);
      }
    }
    return this.otherPartitionRegions;
  }

  /**
   * Initialise list of partition that are not driver table
   */
  private void setOtherPRegions() {
    if (GemFireXDUtils.TraceActivation) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
          "DMLQueryInfo::getOtherRegions: is Empty. "
              + "List of partitions being initialized");
    }

    Iterator<TableQueryInfo> itr = this.tableQueryInfoList.iterator();
    HashSet<PartitionedRegion> otherPRs = new HashSet<PartitionedRegion>(
        this.tableQueryInfoList.size());
    TableQueryInfo tqi = null;
    while (itr.hasNext()) {
      tqi = itr.next();
      if (tqi == null || tqi == this.driverTqi || !tqi.isPartitionedRegion()) {
        continue;
      }
      otherPRs.add((PartitionedRegion)tqi.getRegion());
    }
    if (otherPRs.size() > 0) {
      this.otherPartitionRegions = Collections.unmodifiableSet(otherPRs);
    }
    else {
      this.otherPartitionRegions = null;
    }
  }

  public int getQueryFlag() {
    return this.queryType;
  }
  
  @Override
  public void throwExceptionForInvalidParameterizedData(int invalidParamNumber) throws StandardException{
    int querySection = this.qic.getQuerySectionForParameter(invalidParamNumber) ;    
    if( querySection == WHERE_DML_SECTION) {      
        throw StandardException.newException(SQLState.LANG_NOT_COMPARABLE);      
    }else if(this.isInsert() || this.isUpdate()) {
      throw StandardException.newException(SQLState.LANG_NOT_STORABLE);
    }else {
      throw StandardException.newException(SQLState.LANG_NOT_COMPARABLE);
    }
  }
  

  protected void setHasJoinNode() {
    this.queryType = GemFireXDUtils.set(this.queryType,HAS_JOIN_NODE);
  }

  protected boolean hasJoinNode() {
    return GemFireXDUtils.isSet(this.queryType, HAS_JOIN_NODE);
  }

  /**
   * NCJ Note: For Non Collocated Join purpose: 1. No SubQuery for now
   */
  private boolean isNCJoinSupportedOnQN(final int prTableCount) {
    boolean isNCJoinSupported = false;
    if (prTableCount > 1 && hasJoinNode()) {
      if (Boolean.parseBoolean(PropertyUtil.getSystemProperty(
          GfxdConstants.OPTIMIZE_NON_COLOCATED_JOIN, "false"))) {
        isNCJoinSupported = true;
      }
    }

    if (isSubQuery()) {
      isNCJoinSupported = false;
    }

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "returning DMLQueryInfo:isNCJoinSupported=" + isNCJoinSupported
                + ", isSubQuery=" + isSubQuery());
      }
    }

    return isNCJoinSupported;
  }
  
  /**
   * NCJ Note: For Non Collocated Join. Find driver table on QN
   * 
   * @throws StandardException
   */
  private boolean ncjSetDriverTable(final AbstractConditionQueryInfo whereClause)
      throws StandardException {
    boolean invalidCase = false;
    boolean isSelfJoin = false;
    int prTabCount = 0;
    final ArrayList<TableQueryInfo> tqiList = new ArrayList<TableQueryInfo>(
        NcjHashMapWrapper.getTotalTableAllowed());
    for (TableQueryInfo tqi : this.tableQueryInfoList) {
      if (tqi != null) {
        if (tqi.isPartitionedRegion()) {
          prTabCount++;
        }
        if (!isSelfJoin && tqiList.contains(tqi)) {
          isSelfJoin = true;
        }
        tqiList.add(tqi);
      }
    }

    // At least 2 tables are required
    if (prTabCount < 2) {
      invalidCase = true;
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceNCJ) {
          SanityManager
              .DEBUG_PRINT(
                  GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                  "DMLQueryInfo::ncjSetDriverTable. No of PR tables is less than two. Marking invalid. "
                      + " ,num-pr-tabs="
                      + prTabCount
                      + " ,isSelfJoin="
                      + isSelfJoin
                      + " ,total-tables="
                      + tqiList.size()
                      + " ,tables=" + tqiList);
        }
      }
    }

    // More than eight tables not required
    if (!invalidCase
        && tqiList.size() > NcjHashMapWrapper.getTotalTableAllowed()) {
      invalidCase = true;
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceNCJ) {
          SanityManager
              .DEBUG_PRINT(
                  GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                  "DMLQueryInfo::ncjSetDriverTable. No of tables is more than eight. Marking invalid. "
                      + " ,num-pr-tabs="
                      + prTabCount
                      + " ,isSelfJoin="
                      + isSelfJoin
                      + " ,total-tables="
                      + tqiList.size()
                      + " ,tables=" + tqiList);
        }
      }
    }

    if (!invalidCase) {
      ArrayList<THashSet> colocationArr = this.qic
          .getTableColocationPrCorrName();
      SanityManager.ASSERT(colocationArr != null);
      SanityManager.ASSERT(colocationArr.size() > 0);

      if (colocationArr.size() > NcjHashMapWrapper.getMaxTableAllowed()) {
        invalidCase = true;
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceNCJ) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                    "DMLQueryInfo::ncjSetDriverTable. Marking invalid since more than four tables require network access."
                        + " num-colocation-grp="
                        + colocationArr.size()
                        + " colocation-grp="
                        + colocationArr
                        + " ,num-pr-tabs="
                        + prTabCount
                        + " ,total-tables="
                        + tqiList.size()
                        + " ,isSelfJoin=" + isSelfJoin + " ,tables=" + tqiList);
          }
        }
      }
      
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
              "DMLQueryInfo::ncjSetDriverTable. " + " num-colocation-grp="
                  + colocationArr.size() + " colocation-grp=" + colocationArr);
        }
      }
    }

    if (!invalidCase) {
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(
              GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
              "DMLQueryInfo::ncjSetDriverTable. Going to get driver table."
                  + " Num-pr-tabs=" + prTabCount + " ,total-tables="
                  + tqiList.size() + " ,isSelfJoin=" + isSelfJoin + " ,tables="
                  + tqiList);
        }
      }

      this.ncjMetaData = new THashMap();
      ncjSetDriverAndRemoteTables(tqiList);
    }

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "DMLQueryInfo::ncjSetDriverTable. Set driverTable="
                + (isDriverTableInitialized() ? getDriverTableQueryInfo()
                    .getTableName() : "null"));
      }
    }

    return isDriverTableInitialized();
  }
  
  /**
   * NCJ Note: For NCJ Purpose. Verify that group of collocated tables are
   * indeed collocated (should form a single group)
   * 
   * @return remote table info
   * 
   * @throws StandardException
   */
  private boolean ncjVerifyCollocatedTables(TableQueryInfo driverTqi,
      TableQueryInfo tqi) {
    if (driverTqi == null || tqi == null || driverTqi == tqi) {
      return false;
    }

    boolean retVal = false;
    {
      FromBaseTable driverFbt = driverTqi.getTableNode();
      SanityManager.ASSERT(driverFbt != null, "Must needed for self-join");
      String driverName = driverFbt.ncjGetCorrelationName();

      FromBaseTable fbt = tqi.getTableNode();
      SanityManager.ASSERT(fbt != null, "Must needed for self-join");
      String tabName = fbt.ncjGetCorrelationName();

      ArrayList<THashSet> colocationArr = this.qic
          .getTableColocationPrCorrName();
      for (THashSet hashSet : colocationArr) {
        if (hashSet.contains(driverName)) {
          if (hashSet.contains(tabName)) {
            retVal = true;
            break;
          }
        }
      }
    }

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(
            GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "DMLQueryInfo::ncjVerifyCollocatedTables. " + "driverTqi="
                + driverTqi.getTableName() + " ;master="
                + driverTqi.getMasterTableName() + " ,tqi="
                + tqi.getTableName() + " ;master=" + tqi.getMasterTableName()
                + " ,Colocation passed? " + retVal);
      }
    }

    return retVal;
  }

  private String ncjGetCorrName(TableQueryInfo tqi) {
    FromBaseTable fbt = tqi.getTableNode();
    SanityManager.ASSERT(fbt != null);
    return fbt.ncjGetCorrelationName();
  }

  private TableQueryInfo ncjSetDriverOnFirstTwoTables(TableQueryInfo firstTqi,
      TableQueryInfo secondTqi, int driverPrId) {
    TableQueryInfo driverTqi = null;
    if (firstTqi.isPartitionedRegion() && secondTqi.isPartitionedRegion()) {
      int prIdOne = ((PartitionedRegion)firstTqi.getRegion()).getPRId();
      int prIdTwo = ((PartitionedRegion)secondTqi.getRegion()).getPRId();
      if (prIdOne == driverPrId || prIdTwo == driverPrId) {
        // first verify if both are colocated?
        boolean areColocated = ncjVerifyCollocatedTables(firstTqi, secondTqi);
        if (areColocated) {
          if (prIdOne == driverPrId) {
            driverTqi = firstTqi;
          }
          else {
            driverTqi = secondTqi;
          }
          // None of them is Pull, since colocated with driver table
          NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
              ncjGetCorrName(firstTqi), false, firstTqi);
          NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
              ncjGetCorrName(secondTqi), false, secondTqi);
        }
        else if (prIdOne == driverPrId) {
          driverTqi = firstTqi;
          // Swap table positions
          NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
              ncjGetCorrName(secondTqi), true, secondTqi);
          NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
              ncjGetCorrName(firstTqi), false, firstTqi);
        }
        else {
          driverTqi = secondTqi;
          NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
              ncjGetCorrName(firstTqi), true, firstTqi);
          NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
              ncjGetCorrName(secondTqi), false, secondTqi);
        }
      }
      else {
        // Both are remote tables, none is driver
        NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
            ncjGetCorrName(firstTqi), true, firstTqi);
        NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
            ncjGetCorrName(secondTqi), true, secondTqi);
      }
    }
    else if (firstTqi.isPartitionedRegion()) {
      int prIdOne = ((PartitionedRegion)firstTqi.getRegion()).getPRId();
      if (prIdOne == driverPrId) {
        driverTqi = firstTqi;
        // none is pull
        NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
            ncjGetCorrName(firstTqi), false, firstTqi);
        NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
            ncjGetCorrName(secondTqi), false, secondTqi);
      }
      else {
        NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
            ncjGetCorrName(firstTqi), true, firstTqi);
        NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
            ncjGetCorrName(secondTqi), false, secondTqi);
      }
    }
    else if (secondTqi.isPartitionedRegion()) {
      int prIdTwo = ((PartitionedRegion)secondTqi.getRegion()).getPRId();
      if (prIdTwo == driverPrId) {
        driverTqi = secondTqi;
        // none is pull
        NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
            ncjGetCorrName(firstTqi), false, firstTqi);
        NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
            ncjGetCorrName(secondTqi), false, secondTqi);
      }
      else {
        // Swap table positions
        NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
            ncjGetCorrName(secondTqi), true, secondTqi);
        NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
            ncjGetCorrName(firstTqi), false, firstTqi);
      }
    }
    else {
      // None is partitioned table
      NcjHashMapWrapper.addTableAtFirstPosition(ncjMetaData,
          ncjGetCorrName(firstTqi), false, firstTqi);
      NcjHashMapWrapper.addTableAtSecondPosition(ncjMetaData,
          ncjGetCorrName(secondTqi), false, secondTqi);
    }
    return driverTqi;
  }

  /**
   * NCJ Note: For NCJ Purpose. Actual implementation to set Remote Table and
   * Driver Tables that information would also be distributed to all data nodes
   * with Query.
   * 
   * @param tqiList
   *          ArrayList<TableQueryInfo>
   */
  private void ncjSetDriverAndRemoteTables(ArrayList<TableQueryInfo> tqiList) {
    SanityManager.ASSERT(this.ncjMetaData != null);
    TableQueryInfo driverTqi = null;

    // Get Driver Table Information
    final int driverPrId = this.qic.getDriverTablePrID();
    SanityManager.ASSERT(driverPrId > 0);

    // Handle first two tables
    final TableQueryInfo firstTqi = tqiList.get(NcjHashMapWrapper
        .getFirstTablePositionIndex());
    final TableQueryInfo secondTqi = tqiList.get(NcjHashMapWrapper
        .getSecondTablePositionIndex());
    driverTqi = ncjSetDriverOnFirstTwoTables(firstTqi, secondTqi, driverPrId);

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "DMLQueryInfo::ncjSetDriverAndRemoteTables. From first two tables, first="
                + firstTqi + " ,second=" + secondTqi + " got Driver Table "
                + driverTqi);
      }
    }

    // Handle rest of the tables
    for (TableQueryInfo tqi : this.tableQueryInfoList) {
      if (tqi == null || tqi == firstTqi || tqi == secondTqi) {
        continue;
      }
      FromBaseTable fbt = tqi.getTableNode();
      SanityManager.ASSERT(fbt != null);
      final String corrName = fbt.ncjGetCorrelationName();
      boolean isPull = false;
      if (tqi.isPartitionedRegion()) {
        if (driverTqi == null
            && ((PartitionedRegion)tqi.getRegion()).getPRId() == driverPrId) {
          driverTqi = tqi;
        }
        else if (ncjVerifyCollocatedTables(driverTqi, tqi)) {
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                  "DMLQueryInfo::ncjSetDriverAndRemoteTables. Table " + tqi
                      + " is colocated to driver " + driverTqi);
            }
          }
        }
        else {
          isPull = true;
        }
      }
      NcjHashMapWrapper.addTableAtHigherPosition(ncjMetaData, corrName,
          isPull, tqi);
    }

    // Set Driver Table
    setDriverTableQueryInfo(driverTqi);

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "DMLQueryInfo::ncjSetDriverAndRemoteTables. "
                + "Remote Table and column indexes=" + this.ncjMetaData
                + ", driver table=" + driverTqi + ", driver PRId=" + driverPrId
                + " ,tables=" + tqiList);
      }
    }
  }
}
