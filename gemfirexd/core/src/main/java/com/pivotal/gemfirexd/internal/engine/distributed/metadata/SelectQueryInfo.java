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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.compile.ParameterizedConstantNode;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import com.pivotal.gemfirexd.internal.impl.sql.compile.*;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;

/**
 * The top level QueryInfo object representing a Select query.
 *  This Object encapsulates the from clause, where clause & projection
 *  attributes  etc & provides information as to the nodes on which 
 *  the query needs to be sent, whether it is possible to convert into
 *  a region.get() etc. This class implements Visitor & traverses the
 *  optmized parsed tree.
 * @author Asif
 * @see CursorNode#computeQueryInfo(QueryInfoContext qic)
 * @since GemFireXD
 *
 */
@SuppressWarnings("serial")
public class SelectQueryInfo extends DMLQueryInfo {

  private ColumnQueryInfo[] projQueryInfo = null;

  private ExecRow projRow; // null until all result column descriptors in place

  private ResultDescription resultDescription;

 
  //Shoubhik
  protected OrderByQueryInfo orderbyQI;

  protected DistinctQueryInfo distinctQI;

  protected GroupByQueryInfo groupbyQI;

  private RowFormatter rowFormatter;
  private boolean objectStore;

  // column indexes for projection
  private int[] projectionFixedColumns;
  private int[] projectionVarColumns;
  private int[] projectionLobColumns;
  private int[] projectionAllColumns;
  private int[] projectionAllColumnsWithLobs;

  private ArrayList<DelayedComputeQIPair<? extends SingleChildResultSetNode>>
    postSelectQI;

  private long rowcountFetchFirst = -1;

  private long rowcountOffset = 0;
  
  private boolean dynamicOffset;
  
  private boolean dynamicFetchFirst;

  private boolean canObtainKeyFromProjection;

  private int[] projIndexesForKey;

  private Vector<?> updatableColumns;

  protected SelectQueryInfo(QueryInfoContext qic, boolean overload)
      throws StandardException {
    super(qic);
    assert overload == false: "initialize postSelectQI array otherwise";
  }

  public SelectQueryInfo(QueryInfoContext qic) throws StandardException {
    super(qic);
    postSelectQI = new ArrayList<DelayedComputeQIPair<? extends
        SingleChildResultSetNode>>(5);
  }

  public SelectQueryInfo(QueryInfoContext qic, Vector updatableColumns)
      throws StandardException {
    super(qic);
    postSelectQI = new ArrayList<DelayedComputeQIPair<? extends
        SingleChildResultSetNode>>(5);
    if (updatableColumns != null) {
      this.queryType = GemFireXDUtils.set(this.queryType, SELECT_FOR_UPDATE);
    }
    this.updatableColumns = updatableColumns;
  }

  public void setPrimaryKeyColumnsInformationFromProjection(
      LanguageConnectionContext lcc, String schemaName, String tableName)
      throws StandardException {
    this.selectForUpdateCase = true;
    TableDescriptor td = GemFireXDUtils.getTableDescriptor(schemaName,
        tableName, lcc);

    Map<String, Integer> pkmap = GemFireXDUtils
        .getPrimaryKeyColumnNamesToIndexMap(td, lcc);
    checkIfAllowedUpdatableColumns(updatableColumns, pkmap, td);
    if (pkmap != null) {
    Iterator<Map.Entry<String, Integer>> itr =
      pkmap.entrySet().iterator();
    this.projIndexesForKey = new int[pkmap.size()];
    this.canObtainKeyFromProjection = true;
    int pos = 0;
    while(itr.hasNext()) {
      Map.Entry<String, Integer> anEntry = itr.next();
      String columnName = anEntry.getKey();
      for(int i=0; i<this.projQueryInfo.length; i++) {
        if (columnName.equalsIgnoreCase(this.projQueryInfo[i].getActualColumnName())) {
          this.projIndexesForKey[pos++] = i;
          break;
        }
        else if (i == this.projQueryInfo.length - 1) {
          this.canObtainKeyFromProjection = false;
          break;
        }
      }
    }
    }
    else {
      this.canObtainKeyFromProjection = false;
    }
    if (!this.canObtainKeyFromProjection) {
      this.projIndexesForKey = null;
    }
  }

  private boolean selectForUpdateCase;

  private boolean routingFromGlobalIndex = true;

  private int[] partitioningColumnsFromProjection;

  private GemFireContainer gfContainerOfTheRegion;

  private void checkIfAllowedUpdatableColumns(Vector updatableColumns,
      Map<String, Integer> pkmap, TableDescriptor td) throws StandardException {
    final Iterator<?> itr = updatableColumns.iterator();
    DistributionDescriptor distdescp = td.getDistributionDescriptor();
    String[] partitioningcolumns = distdescp.getPartitionColumnNames();
    GfxdPartitionResolver resolver = null;

    while (itr.hasNext()) {
      String column = (String)itr.next();
      if (pkmap != null && pkmap.containsKey(column)) {
        throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
            "Update of column which is primary key "
                + "or is part of the primary key, not supported");
      }
      if (partitioningcolumns != null) {
        for (int i = 0; i < partitioningcolumns.length; i++) {
          if (partitioningcolumns[i].equals(column)) {
            throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                "Update of partitioning column not supported");
          }
        }
      }
    }

    int policy = distdescp.getPolicy();
    if (partitioningcolumns == null) {
      assert (policy == DistributionDescriptor.REPLICATE
          || policy == DistributionDescriptor.PARTITIONBYGENERATEDKEY);
      this.routingFromGlobalIndex = false;
    }
    else if (policy == DistributionDescriptor.PARTITIONBYPRIMARYKEY) {
      this.routingFromGlobalIndex = false;
    }
    else {
      if (policy != DistributionDescriptor.REPLICATE) {
        resolver = GemFireXDUtils.getResolver(getRegion());
        if (resolver != null && resolver.isPartitioningKeyThePrimaryKey()) {
          this.routingFromGlobalIndex = false;
        }
      }
    }

    if (this.routingFromGlobalIndex) {
      this.partitioningColumnsFromProjection =
        findPartitoningColumnsFromProjectionIfPossible(partitioningcolumns);
      if (this.partitioningColumnsFromProjection == null) {
        assert resolver != null;
        assert resolver.requiresGlobalIndex();
      }
    }
  }
  
  private int[] findPartitoningColumnsFromProjectionIfPossible(String[] partitioningColStrings) {
    if (partitioningColStrings == null) {
      return null;
    }
    int[] projectionIndexesForPartitoningColumns = new int[partitioningColStrings.length];
    int cnt = 0;
    for(int i=0; i<partitioningColStrings.length; i++) {
      String partColtmp = partitioningColStrings[i];
      int colInProjIndex = getColumnInProjIndex(partColtmp);
      if (colInProjIndex != -1) {
        projectionIndexesForPartitoningColumns[cnt++] = colInProjIndex;
      }
      else {
        projectionIndexesForPartitoningColumns = null;
        break;
      }
    }
    return projectionIndexesForPartitoningColumns;
  }

  private int getColumnInProjIndex(String colName) {
    int ret = -1;
    for (int i = 0; i < this.projQueryInfo.length; i++) {
      ColumnQueryInfo colInfo = this.projQueryInfo[i];
      if (colName.equals(colInfo.getActualColumnName())) {
        ret = i;
        break;
      }
    }
    return ret;
  }

  public boolean isSelectForUpdateCase() {
    return this.selectForUpdateCase;
  }

  @Override
  public boolean needKeysForSelectForUpdate() {
    return !this.canObtainKeyFromProjection;
  }

  public boolean isRoutingCalculationRequired() {
    return this.routingFromGlobalIndex;
  }

  public int[] getPartitioningColumnFromProjection() {
    return this.partitioningColumnsFromProjection;
  }

  public int[] getProjectionIndexesForKey() {
    return this.projIndexesForKey;
  }

  public GemFireContainer getGFContainer() {
    if (this.gfContainerOfTheRegion == null) {
      this.gfContainerOfTheRegion = (GemFireContainer)this.getRegion()
          .getUserAttribute();
    }
    return this.gfContainerOfTheRegion;
  }

  @Override
  public final boolean isSelect() {
    return true;
  }

  @Override
  public final boolean optimizeForWrite() {
    final boolean isOptimizedForWrite = this.qic.optimizeForWrite();
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "returning SelectQueryInfo:optimizeForWrite = "
                + isOptimizedForWrite);
      }
    }
    return isOptimizedForWrite;
  }
  
  @Override
  public final boolean withSecondaries() {
    final boolean withSecondaries = this.qic.withSecondaries();
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "returning SelectQueryInfo:withSecondaries= "
                + withSecondaries);
      }
    }
    return withSecondaries;
  }

  public boolean skipChildren(Visitable node) throws StandardException {
    return node instanceof ProjectRestrictNode || node instanceof AndNode
        || node instanceof ResultColumn
        || node instanceof ScrollInsensitiveResultSetNode
        || node instanceof GroupByNode || node instanceof OrderByNode
        || node instanceof DistinctNode || node instanceof SubqueryNode
        || node instanceof RowCountNode;
  }

  public boolean stopTraversal() {
    return false;
  }

  @Override
  public Visitable visit(Visitable node) throws StandardException {
    if (node instanceof ScrollInsensitiveResultSetNode) {
      handleScrollInsensitiveResultSetNode((ScrollInsensitiveResultSetNode)node);
    }
    else if (node instanceof ResultColumnList) {
      handleResultColumnListNode((ResultColumnList)node);
    }
    else if (node instanceof ResultColumn) {
      handleResultColumnNode((ResultColumn)node);
    }
    else if (node instanceof OrderByNode) {
      this.handleOrderByNode((OrderByNode)node);
    }
    else if (node instanceof GroupByNode) {
      if (qic.getNestingLevelOfScope() == 0) {
        handleGroupByNode((GroupByNode)node);
      }
      else {
        ResultSetNode rsn = ((GroupByNode)node).getChildResult();
        if (rsn instanceof ProjectRestrictNode) {
          this.handleProjectRestrictNode((ProjectRestrictNode)rsn);
        }
      }
    }
    else if (node instanceof DistinctNode) {
      handleDistinctNode((DistinctNode)node);
    }
    else if (node instanceof RowCountNode) {
      handleRowCountNode((RowCountNode)node);
    }
    else if (node instanceof FromVTI) {
      ((FromVTI)node).computeQueryInfo(this.qic);
    }
    else if (node instanceof UnionNode) {
      // Handle Union
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SelectQueryInfo::visit: Case of Union. Set hasUnionNode"); 
      }
            
      this.setHasUnionNode();
      super.visit(node);
    }
    else if (node instanceof IntersectOrExceptNode) {
      // Handle Intersect / Except
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SelectQueryInfo::visit: Case of Intersect or Except. Set name = "
            + ((IntersectOrExceptNode)node).getOperatorNameWithDistinct()); 
      }

      this.setHasIntersectOrExceptNode(((IntersectOrExceptNode)node).getOperatorNameWithDistinct());
      super.visit(node);
    }else if (node instanceof JoinNode) {
     
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SelectQueryInfo::visit: Found Join Node. "); 
      }
            
      this.setHasJoinNode();
      super.visit(node);
    }
    else {
      super.visit(node);
    }

    return node;
  } 

  private void handleScrollInsensitiveResultSetNode(
      ScrollInsensitiveResultSetNode node) throws StandardException {
    node.getChildResult().accept(this);
    this.init();
  }

  @Override
  int processResultColumnListFromProjectRestrictNodeAtLevel() {
    return 0;
  }

  private void handleResultColumnListNode(ResultColumnList rlc)
      throws StandardException {

    int numCols = rlc.size();
    this.projQueryInfo = new ColumnQueryInfo[numCols];

    //Just create an empty GenericResultDescription object with an array
    //of ResultcolumnDescription object with each member of the array left
    //uninitialized. Then as and when we encounter the column , we create
    // the ResultColumnDescriptor object & set it in the array
    this.resultDescription = new GenericResultDescription(
        new ResultColumnDescriptor[numCols], null);
    this.projRow = new ValueRow(numCols);
  }

  private void handleResultColumnNode(ResultColumn rc) throws StandardException {

    int virtualColPos = rc.getVirtualColumnId();
    // Store the column relative to the virtual position for projection
    // attribute
    ColumnQueryInfo cqi = new ColumnQueryInfo(rc, this.qic);
    // If the result column references anything but a ColumnReference
    //  the GFE get-conversion is not possible. 
    //  So flag the queryType as EMBED_GFE_RESULTSET
    //  Check ValueNode.hasExpression() to see if the node requires
    //   more than a straight project from the base table
    //  One example is SELECT <constant_value>, <column> FROM
    //                     <table> WHERE <pk_column> = <value>
    //      The constant_value cannot come from the GFE resultset
    //      It must be done via ProjectRestrictResultSet
    //  Other nodes that fall into this category are :
    //      CAST() nodes, CURRENT_DATE/CURRENT_TIME,
    //      Binary/unary/ternary operators
    ValueNode vn = rc.getExpression();
    if (!GemFireXDUtils.isSet(this.queryType, EMBED_GFE_RESULTSET)
        && vn!=null && !vn.isTableColumn()) {
      this.queryType = GemFireXDUtils.set(this.queryType, EMBED_GFE_RESULTSET);
    }

    assert this.projQueryInfo[virtualColPos - 1] == null;
    this.projQueryInfo[virtualColPos - 1] = cqi;
    // [sb] instead of picking up the underlying table's result type, it should
    // be top most result's type while returning ResultSet.metaData(). 
    DataTypeDescriptor dtd = rc.getType(); // cqi.getType();
    this.projRow.setColumn(virtualColPos, dtd.getNull());
    ResultColumnDescriptor rcd = new GenericColumnDescriptor(
        cqi.getExposedName(), cqi.getSchemaName(), cqi.getTableName(),
        virtualColPos, dtd, updatableColumns != null && updatableColumns
            .contains(cqi.getExposedName()) /* Is updatable */,
        false /* is autoIncrement */);
    ((GenericResultDescription)this.resultDescription).setColumnDescriptor(
        virtualColPos - 1, rcd);
  }

  private void handleRowCountNode(RowCountNode rcn) throws StandardException {
    this.queryType = GemFireXDUtils.set(this.queryType, HAS_FETCH_ROWS_ONLY);
    // GetAll should not handle fetch next / offset at query node
    this.queryType = GemFireXDUtils.set(this.queryType, MARK_NOT_GET_CONVERTIBLE);
    ValueNode offsetNode = rcn.getRowCountOffSet();
    if (offsetNode instanceof ConstantNode) {
      this.rowcountOffset = ((ConstantNode)offsetNode).getValue().getLong();
      this.dynamicOffset = false;
    }
    else if (offsetNode instanceof ParameterizedConstantNode) {
      this.rowcountOffset = ((ParameterizedConstantNode)offsetNode).getConstantNumber();
      this.dynamicFetchFirst = true;
    }
    else if (offsetNode != null) {
      assert offsetNode instanceof ParameterNode : offsetNode;
      this.rowcountOffset = ((ParameterNode)offsetNode).getParameterNumber();
      this.dynamicOffset = true;
    }
    
    ValueNode fetchFirstNode = rcn.getRowCountFetchFirst();
    if (fetchFirstNode instanceof ConstantNode) {
      this.rowcountFetchFirst = ((ConstantNode)fetchFirstNode).getValue().getLong();
      this.dynamicFetchFirst = false;
    }
    else if (fetchFirstNode instanceof ParameterizedConstantNode) {
      this.rowcountFetchFirst = ((ParameterizedConstantNode)fetchFirstNode).getConstantNumber();
      this.dynamicFetchFirst = true;
    }
    else if (fetchFirstNode != null) {
      assert fetchFirstNode instanceof ParameterNode : fetchFirstNode;
      this.rowcountFetchFirst = ((ParameterNode)fetchFirstNode).getParameterNumber();
      this.dynamicFetchFirst = true;
    }
    else {
      assert fetchFirstNode == null;
      this.rowcountFetchFirst = -1;
      this.dynamicFetchFirst = false;
    }
    this.visit(rcn.getChildResult());
  }

  private void handleOrderByNode(OrderByNode obn) throws StandardException {
    final DelayedComputeQIPair<OrderByNode> orderByPair =
      new DelayedComputeQIPair<OrderByNode>(obn, qic.getParentPRN());
    postSelectQI.add(orderByPair);
    this.queryType = GemFireXDUtils.set(this.queryType, EMBED_GFE_RESULTSET);
    qic.setParentPRN(null);
    this.visit(obn.getChildResult());
  }

  void handleGroupByNode(GroupByNode gbn) throws StandardException {
    final DelayedComputeQIPair<GroupByNode> groupByPair =
      new DelayedComputeQIPair<GroupByNode>(gbn, qic.getParentPRN());
    postSelectQI.add(groupByPair);
    this.queryType = GemFireXDUtils.set(this.queryType, EMBED_GFE_RESULTSET);
    qic.setParentPRN(null);
    this.visit(gbn.getChildResult());
  }

  void handleDistinctNode(DistinctNode dnode) throws StandardException {
    final DelayedComputeQIPair<DistinctNode> dnodePair =
      new DelayedComputeQIPair<DistinctNode>(dnode, qic.getParentPRN());
    this.queryType = GemFireXDUtils.set(this.queryType, EMBED_GFE_RESULTSET);
    postSelectQI.add(dnodePair);

    qic.setParentPRN(null);
    this.visit(dnode.getChildResult());
  }

  @Override
  void handleFromBaseTableNode(FromBaseTable fbtNode,
      boolean postParentClassHandling) throws StandardException {

    /*for a single table Select DISTINCT, the scan
     * itself is Distinct on the Data Nodes, but 
     * need to mark Query Distinct on the Query Node.
     * TODO: soubhik Address later for multiple tables. SelectNode probably will create DistinctNode. 
     */
     if(fbtNode.isDistinctScan()) {
       this.queryType = GemFireXDUtils.set(this.queryType,HAS_DISTINCT_SCAN);
      // this.queryType = GemFireXDUtils.set(this.queryType,HAS_EXPRESSION_IN_PROJECTION, true);
       ProjectRestrictNode parentPRN = this.qic.getParentPRN();
       
       this.distinctQI = new DistinctQueryInfo(qic,
                                  /* This is where we expect distinct table scan 
                                   * will have outermost projection returned from
                                   * data store.
                                   * @see SelectNode#genProjectRestrict(int origFromListSize)
                                   * if(distinctScanPossible)....
                                   */
                                  parentPRN.getInnerMostPRN().getResultColumns(), 
                                  parentPRN.getResultColumns(), false);;
     }
  }

  @Override
  public void init() throws StandardException {
    // check if initialization already done

    if (this.isDriverTableInitialized()) {
      return;
    }
    try {
      boolean isGFEActivation = false;
      // We are about to return so process the Where clause now.
      // The tableQueryNodes must all be ready by now, so safe to process where
      // clause.
      super.init();      
      TableQueryInfo driverTable = this.getDriverTableQueryInfo();
 
//      Iterator<PredicateList> itr1 = this.wherePredicateList.iterator();
//      while (itr1.hasNext()) {
//        this.handlePredicateList(itr1.next());
//        itr1.remove();
//      }
//      
//
//      // 1) For it to be convertible to get , there should be only one
//      // TableQueryInfo object in the from list
//      // 2) If the primary Key is single then the where clause should have only
//      // one condition
//      // 3) Else it should be an AND condition with all composite key elements
//      // with equality condition
//      // 4) Projection list should have exposed name equal to actual column name
//      // &
//      // order should be same & all be present
//      
//     
//      // Is Convertible to get
//      if (this.tableQueryInfoList.size() == 1 /*&& this.subqueries.size() == 0*/) {
//        this.driverTqi = this.tableQueryInfoList.get(0);
//        
//        if (this.driverTqi.isPrimaryKeyBased() && isWhereClauseSatisfactory(this.driverTqi)
//            && isProjectionSatisfactory(this.driverTqi)) {
//          // Check if the projection has expression and so requires modification
//          // in the tree
//          this.queryType = GemFireXDUtils.set(this.queryType,
//              IS_PRIMARY_KEY_TYPE);
//          if (GemFireXDUtils.isSet(this.queryType, EMBED_GFE_RESULTSET)) {
//            this.driverTqi.getTableNode().setCreateGFEResultSetTrue(this);
//          }
//          this.driverTqi.setBaseTableNodeAsNull();
//        }
//      }
//      else {        
//          this.driverTqi = identifyDriverTableQueryInfo();
//            
//      }
//      //Process collected  where clause based Sub queries if any now 
//      this.processSubqueries();
      
      boolean isWhereClauseSatisfactory = false;
      boolean isPrimaryKeyBased = false;

      if (numTablesInTableQueryInfoList() == 1
      /*&& this.subqueries.size() == 0*/) {
        
        // Need to call isWhereClauseSatisfactory first
        if ((isWhereClauseSatisfactory = isWhereClauseSatisfactory(driverTable))
            && (isPrimaryKeyBased = driverTable.isPrimaryKeyBased())
            && (driverTable.getTableNode() != null)) {
            //&& isProjectionSatisfactory(driverTable)) {
          // Check if the projection has expression and so requires modification
          // in the tree
          this.queryType = GemFireXDUtils.set(this.queryType,
              IS_PRIMARY_KEY_TYPE);
          if (GemFireXDUtils.isSet(this.queryType, EMBED_GFE_RESULTSET)) {
            driverTable.getTableNode().setCreateGFEResultSetTrue(this);
          }
          driverTable.setBaseTableNodeAsNull();
        }
        else if (this.getLocalIndexKey() != null
            && this.getChosenLocalIndex() != null) {
          boolean doGetAllOnLocalIndex = false;
          boolean embedGfeResultSet = GemFireXDUtils.isSet(this.queryType,
              EMBED_GFE_RESULTSET);
          boolean forceEmbedGfeResultSetRoute = DMLQueryInfo
              .enableGetAll_LocalIndex_withEmbedGfe();
          if (embedGfeResultSet) {
            if (forceEmbedGfeResultSetRoute) {
              doGetAllOnLocalIndex = true;
              driverTable.getTableNode().setCreateGFEResultSetTrue(this);
            }
            else {
              // driverTable.getTableNode().setCreateGFEResultSetTrue(this);
              // This means extra processing is required at Local Node that
              // could be not for performance. Avoid this route.
              doGetAllOnLocalIndex = false;
            }
          }
          else {
            doGetAllOnLocalIndex = true;
          }
          if (doGetAllOnLocalIndex) {
            this.queryType = GemFireXDUtils.set(this.queryType,
                IS_GETALL_ON_LOCAL_INDEX);
            driverTable.setBaseTableNodeAsNull();
          }

          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_QUERYDISTRIB,
                  "SelectQueryInfo:init Perform GetAll on Local Index="
                      + doGetAllOnLocalIndex + " ,this.getLocalIndexKey() = "
                      + this.getLocalIndexKey()
                      + " ,this.getChosenLocalIndex() = "
                      + this.getChosenLocalIndex() + " ,embedGfeResultSet="
                      + embedGfeResultSet + " ,forceEmbedGfeResultSetRoute="
                      + forceEmbedGfeResultSetRoute);
            }
          }
        }
      }
      
      isGFEActivation = createGFEActivation();
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceQuery) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "SelectQueryInfo:init createGFEActivation= " + isGFEActivation);
        }
      }

      /*
       * process the delayed list from bottom as parent node is on top while
       * visiting qt.
       * 
       * ( OrderByNode -> DistinctNode )
       */
      final CompilerContext cc = ((CompilerContext)ContextService
          .getContext(CompilerContext.CONTEXT_ID));
      for (int i = postSelectQI.size() - 1; i >= 0; i--) {
        DelayedComputeQIPair<?> post = postSelectQI.get(i);
        //skip GroupByNode.computeQueryInfo() for <local>, see #46553
        if (isGFEActivation &&
            (cc.isGlobalScope() || !(post.node instanceof GroupByNode))) {
          post.process();
        }
      }
      postSelectQI.clear();
      // set the hasDSID() flag from CompilerContext
      // [sb] optimizeForWrite only when non-system tables are involved.

      this.qic.setOptimizeForWrite(cc.optimizeForWrite());
      this.qic.setWithSecondaries(cc.withSecondaries());

      if (this.qic.optimizeForWrite() && this.isTableVTI()) {
        Region<?, ?> rgn = getRegion();
        if (rgn != null
            && !((GemFireContainer)rgn.getUserAttribute()).isApplicationTable()) {
          //[sb] switching off optimizeForWrite if joined with system tables.
          this.qic.setOptimizeForWrite(false);
        }
      }

      //If querying HDFS table, primary bucket has to be used.
      int numTabs = this.containerList.size();
      for (int i = 0; i < numTabs; i++) {
        if (containerList.get(i).getRegion() != null
            && containerList.get(i).getRegion().isHDFSReadWriteRegion()) {
          this.qic.setOptimizeForWrite(true);
          break;
        }
      }
      
      initRowFormatter();

      // Initialise non-driver table information for union/intersection/except
      // now initializing for all cases in base DMLQueryInfo
      //if (this.hasUnionNode() || this.hasIntersectOrExceptNode()) {
      //  setOtherRegions();
      //}

    } finally {
      for (DelayedComputeQIPair<?> post : postSelectQI) {
        post.clear();
      }
      postSelectQI.clear();
      wherePredicateList.clear();
    }
  }

  private final void initRowFormatter() {
    
    if (this.getDriverTableQueryInfo() != null) {
      final GemFireContainer container = getGFContainer();
      if (this.containerList.size() == 1) {
        // is projection required?
        // do the column query info represent the entire row in order?
        // first check that number of columns matches
        boolean projectionRequired = (this.projQueryInfo.length != container
            .numColumns());
        // further qualify that the column positions are sequential in order
        if (!projectionRequired) {
          for (int pos = 1; pos <= this.projQueryInfo.length; pos++) {
            if (this.projQueryInfo[pos - 1].getActualColumnPosition() != pos) {
              projectionRequired = true;
              break;
            }
          }
        }
        this.projectionFixedColumns = null;
        this.projectionVarColumns = null;
        this.projectionLobColumns = null;
        this.projectionAllColumns = null;
        this.projectionAllColumnsWithLobs = null;
        if (projectionRequired) {
          assert this.projQueryInfo.length > 0: "no projection columns";
          this.rowFormatter = container.getRowFormatter(this.projQueryInfo);
          this.objectStore = container.isObjectStore();
          final TIntArrayList fixedCols = new TIntArrayList();
          final TIntArrayList varCols = new TIntArrayList();
          final TIntArrayList lobCols = new TIntArrayList();
          final TIntArrayList allCols = new TIntArrayList();
          final TIntArrayList allColsWithLobs = new TIntArrayList();
          this.rowFormatter.getColumnPositions(fixedCols, varCols, lobCols,
              allCols, allColsWithLobs);
          if (fixedCols.size() > 0) {
            this.projectionFixedColumns = fixedCols.toNativeArray();
          }
          if (varCols.size() > 0) {
            this.projectionVarColumns = varCols.toNativeArray();
          }
          if (lobCols.size() > 0) {
            this.projectionLobColumns = lobCols.toNativeArray();
          }
          if (allCols.size() > 0) {
            this.projectionAllColumns = allCols.toNativeArray();
          }
          assert allColsWithLobs.size() > 0;
          this.projectionAllColumnsWithLobs = allColsWithLobs.toNativeArray();
        }
        else {
          this.rowFormatter = container.getCurrentRowFormatter();
          this.objectStore = container.isObjectStore();
        }
      }
      else {
        this.rowFormatter = container.getCurrentRowFormatter();
        this.objectStore = container.isObjectStore();
      }
    }
  }

  /**
   * 
   * @return The ExecRow describing the projection which will be used to convert
   *         the data obtained from the data nodes into meaningful columns on
   *         parse node.
   */
  
  final public ExecRow getProjectionExecRow() {
    return this.projRow.getNewNullRow();
  }


  public Map<String, Object> getDriverRegionsForOuterJoins() {
    Map<String, Object> map = new HashMap<String, Object>();
    if (isSpecialCaseOFOuterJoins) {
      for (Region<?, ?> reg : this.logicalLeftTableList) {
        if (reg.getAttributes().getPartitionAttributes() != null) {
          break;
        }
        String regName = (reg.getParentRegion().getName() + "." + reg.getName())
            .toUpperCase();
        map.put(regName, null);
      }
    }
    else {
      return null;
    }
    return map;
  }

  @Override
  public ResultDescription getResultDescription() {
    return this.resultDescription;
  }

  // TODO Asif: Avoid this call some how
  public ColumnQueryInfo[] getProjectionColumnQueryInfo() {
    return this.projQueryInfo;
  }

  public OrderByQueryInfo getOrderByQI() {
    assert orderbyQI != null: "OrderBy info is not expected to null.. "
        + "see handleOrderByQueryInfo";
    return orderbyQI;
  }

  public GroupByQueryInfo getGroupByQI() {
    assert groupbyQI != null: "GroupBy info is not expected to null.. "
        + "see handleGroupByQueryInfo";
    return groupbyQI;
  }

  public DistinctQueryInfo getDistinctQI() {
    assert distinctQI != null: "Distinct info is not expected to null.. "
        + "see handleDistinctQueryInfo";
    return distinctQI;
  }

  public ColumnOrdering[] getColumnOrdering() {

    if (SanityManager.DEBUG) {
      SanityManager
          .THROWASSERT("getColumnOrdering() not expected to be called for "
              + getClass().getName());
    }

    return null;
  }

  

  private final class DelayedComputeQIPair<N extends SingleChildResultSetNode> {

    private N node;
    private ProjectRestrictNode parentPRN;

    DelayedComputeQIPair(N node, ProjectRestrictNode parentPRN) {
       this.node = node;
       this.parentPRN = parentPRN;
    }

    public final void process() throws StandardException {

      QueryInfo retQi = null;
      final QueryInfoContext lqic = SelectQueryInfo.this.qic;
      final SelectQueryInfo thisO = SelectQueryInfo.this;

      try {
        lqic.setParentPRN(parentPRN);
        retQi = node.computeQueryInfo(lqic);

        switch (node.getNodeType()) {
          case C_NodeTypes.GROUP_BY_NODE: {
            thisO.groupbyQI = (GroupByQueryInfo)retQi;
            thisO.queryType = GemFireXDUtils.set(thisO.queryType, HAS_GROUPBY);
            break;
          }
          case C_NodeTypes.ORDER_BY_NODE: {
            thisO.orderbyQI = (OrderByQueryInfo)retQi;
            thisO.queryType = GemFireXDUtils.set(thisO.queryType, HAS_ORDERBY);
            break;
          }
          case C_NodeTypes.DISTINCT_NODE: {
            thisO.distinctQI = (DistinctQueryInfo)retQi;
            thisO.queryType = GemFireXDUtils.set(thisO.queryType, HAS_DISTINCT);
            break;
          }
          default:
            SanityManager.THROWASSERT("No other type expected as of now");
        }
      } finally {
        clear();
      }
    }

    public final void clear() {
      node = null;
      parentPRN = null;
      SelectQueryInfo.this.qic.setParentPRN(parentPRN);
    }
    
    @Override
    public final String toString() {
        return node.toString() + " PRN " + parentPRN.toString();
    }
  }

  public final RowFormatter getRowFormatter() {
    return this.rowFormatter;
  }

  public final boolean isObjectStore() {
    return this.objectStore;
  }

  public final boolean isProjectionRequired() {
    return this.projectionAllColumnsWithLobs != null;
  }

  public final int[] getProjectionFixedColumns() {
    return this.projectionFixedColumns;
  }

  public final int[] getProjectionVarColumns() {
    return this.projectionVarColumns;
  }

  public final int[] getProjectionLobColumns() {
    return this.projectionLobColumns;
  }

  public final int[] getProjectionAllColumns() {
    return this.projectionAllColumns;
  }

  public final int[] getProjectionAllColumnsWithLobs() {
    return this.projectionAllColumnsWithLobs;
  }

  public long getRowCountOffSet() {
    return rowcountOffset;
  }

  public long getRowCountFetchFirst() {
    return rowcountFetchFirst;
  }
  
  public boolean isDynamicOffset() {
    return dynamicOffset;
  }

  public boolean isDyanmicFetchFirst() {
    return dynamicFetchFirst;
  }

  @Override
  public boolean isTableVTI() {
    return this.qic.virtualTable() != null;
  }
  
  /*
   * (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireResultSet constructor
   */
  public boolean isPrimaryKeyComposite() {
    return this.getDriverTableQueryInfo().isPrimaryKeyBased()
        && this.getDriverTableQueryInfo().getPrimaryKeyColumns().length > 1;
  }

  @Override
  public boolean routeQueryToAllNodes() {
    return this.qic.isVTIDistributable();
  }
  
  /*
   * NCJ Usage
   */
  public final void clearPrimaryKeyBasedFlag() {
    this.queryType = GemFireXDUtils.clear(this.queryType, IS_PRIMARY_KEY_TYPE);
  }
}
