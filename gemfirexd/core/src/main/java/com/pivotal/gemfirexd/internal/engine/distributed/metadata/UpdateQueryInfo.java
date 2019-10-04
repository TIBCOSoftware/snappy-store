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

import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.AndNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.NormalizeResultSetNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ProjectRestrictNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumn;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultColumnList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.UpdateNode;

/**
 * The top level QueryInfo object representing an Update query. This Object
 * analyzes the Where clause for 
 * <p>
 * 1) If the Update can be converted into Region.put
 * 2) Or it needs to be executed in scatter/gather manner with or without pruning
 * <p>
 * It also analyzes the columns to be updated for presence of mathematical
 * expression, or if the columns being updated happen to be Partitioning
 * columns or primary key columns.
 * If the columns being updated are primary key or partitioning key based
 * then exception is thrown
 * 
 * @author Asif
 
 * @since GemFireXD
 * 
 */
public class UpdateQueryInfo extends DMLQueryInfo {
  
  //Create by default the number of rows to be updated as equal to the number of columns in the ResultsColumnList
  private QueryInfo[][] updateCols = null; 
  private int currentColumnIndex = 0;
  //private ValueQueryInfo[] foreignKeyVals = null;
  //private ForeignKeyConstraintDescriptor fcd = null;
  private int updateTargetTableNum = -1;
  public UpdateQueryInfo(QueryInfoContext qic) throws StandardException {
    super(qic);
    qic.setRootQueryInfo(this);
    //By default assume that it is primary key based & we will negate it later in the init
    this.queryType = GemFireXDUtils.set(this.queryType, IS_PRIMARY_KEY_TYPE);
  }

  @Override
  public void init() throws StandardException  {

    // this happens when updating VTI tables
    if (this.updateTargetTableNum == -1 || this.tableQueryInfoList.isEmpty()) {
      return;
    }
    super.init();
     /*For an update to be region.put convertible apart from where clause requirement
      additional requirements are:
      Either it should be partitioned region , or if replicated region, then
      1) The table should not have check type constraint
      2) The unique constraint if present should not be on columns being updated
      [sumedh] 2nd requirement has now been removed; we still have the first
      requirement since check constraints are not being evaluated in
      GemFireUpdateActivation (TODO: add this at some point)
     */
    this.checkUpdateFormatSupported();
    // Is Convertible to get
    TableQueryInfo tqi = this.tableQueryInfoList.get(updateTargetTableNum);    
    if ( GemFireXDUtils.isSet(this.queryType, IS_PRIMARY_KEY_TYPE) &&
        ( !tqi .isPrimaryKeyBased() ||  !isWhereClauseSatisfactory(tqi))) {
      this.queryType = GemFireXDUtils
          .clear(this.queryType, IS_PRIMARY_KEY_TYPE);
    }  
  }  

  /**
   * 
   * Checks if the columns being updated respect the not null check, if present   
   * @param activation  Instance of Activation . The activation object will be necessarily of type
   * GemFireUpdateDistributionActivation as this method will be invoked  only if the update is to be executed 
   * in a distributed manner
   * @throws StandardException
   */
  public void checkNotNullCriteria(Activation activation) throws StandardException {
    int numUpdateCols = this.updateCols.length;
    for(int i =0; i < numUpdateCols;++i) {
      ColumnQueryInfo cqi = (ColumnQueryInfo)this.updateCols[i][0];
      if(cqi != null) {       
        QueryInfo qi = this.updateCols[i][1];
        if(qi instanceof ValueQueryInfo) {
          ValueQueryInfo vqi = (ValueQueryInfo)qi;
          DataValueDescriptor dvd = vqi.evaluateToGetDataValueDescriptor(activation);        
          cqi.isNotNullCriteriaSatisfied(dvd);
        }
      }
    }
  }
  
  /**
   * This function should return true only under following cases:
   * 1)  No Foreign Key constraint is defined on the table.
   * 2) Or none of the columns being updated form part of foreign key constraint
   * 3) All the column forming part of foreign key are present in the update statement
   * 
   * Otherwise the function should return false
   * @return true or false depending upon the above cases
   */
  /*
  private boolean foreignKeyConstraintCheckPossible() { 
    if( this.fcd == null || this.foreignKeyVals == null) {
      return true;
    }
    //Foreign Key Constraint is  not null
    //we are here implying that  foreign key constraint exists & atleast on column being updated
    // is part of the foreign key.
    //Now we need to see if all the columns forming the foreign key constraint are present as 
    // columns to be updated.
    for( ValueQueryInfo vqi :this.foreignKeyVals) {
      if(vqi == null) {
        return false;
      }
    }     
  }*/
  
  private void checkUpdateFormatSupported() throws StandardException {
    // If number of tables is > 1, throw exception
    // If the column being updated are part of primary or
    // partition key throw exception
//    if (this.tableQueryInfoList.size() != 1) {
//      throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
//          "Multi table update not supported");
//    }
    TableQueryInfo tqi = this.tableQueryInfoList.get(this.updateTargetTableNum);
    boolean checkForColumnConstr = GemFireXDUtils.isSet(this.queryType,
        IS_PRIMARY_KEY_TYPE);

    if (checkForColumnConstr && tqi.isCheckTypeConstraintPresent()) {
      this.queryType = GemFireXDUtils
          .clear(this.queryType, IS_PRIMARY_KEY_TYPE);
      checkForColumnConstr = false;
    }

    /**
     * For sorted column batches allow delta insert that is implemented as update plan
     * It is not necessary to check if its update vs insert since there will be no row in row buffer
     */
    boolean isSortedColumnTable = false;
    LocalRegion region = tqi.getRegion();
    if (region != null) {
      GemFireContainer container = (GemFireContainer)region.getUserAttribute();
      if (container.isPartitioned()) {
        ExternalTableMetaData md = container.fetchHiveMetaData(false);
        if (md != null) {
          isSortedColumnTable = md.columnTableSortOrder.equalsIgnoreCase("ASC") ||
              md.columnTableSortOrder.equalsIgnoreCase("Ascending") ||
              md.columnTableSortOrder.equalsIgnoreCase("DESC") ||
              md.columnTableSortOrder.equalsIgnoreCase("Descending");
        }
      }
    }

    //ASIF: DISABLE FK Constraint check on query node
    //this.fcd = this.tableQueryInfoList.get(this.updateTargetTableNum).getForeignKeyConstraintDescriptorIfAny();
    for (int i = 0; i < this.updateCols.length; ++i) {
      ColumnQueryInfo cqi = (ColumnQueryInfo)this.updateCols[i][0];
      if (cqi != null) {
        if (cqi.isTableInfoMissing()) {
          cqi.setMissingTableInfo(tqi);
        }
        if (!isSortedColumnTable && cqi.isUsedInPartitioning()) {
          throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
              "Update of partitioning column not supported");
        }
        // For the time being since we are allowing only one table
        // the columns are necessarily part of the lone TableQueryInfo
        // Later the check needs to be more robust
        if (!isSortedColumnTable && cqi.isPartOfPrimaryKey(tqi.getPrimaryKeyColumns())) {
          throw StandardException
              .newException(
                  SQLState.NOT_IMPLEMENTED,
                  "Update of column which is primary key or is part of the primary key, not supported");
        }
        /* No need to go through function execution for unique constraints
        if(checkForColumnConstr && cqi.isReferencedByUniqueConstraint()) {
          checkForColumnConstr = false;
          this.queryType = GemFireXDUtils.clear(this.queryType,
              IS_PRIMARY_KEY_TYPE);
        }
        */
        /*
        if (this.isPrimaryKeyBased()) {
          if (fcd != null) {
            int index = cqi.isColumnReferencedByConstraint(fcd);
            if (index != -1) {
              if (this.foreignKeyVals == null) {
                this.foreignKeyVals = new ValueQueryInfo[fcd
                    .getReferencedColumns().length];
              }

              this.foreignKeyVals[index] = (ValueQueryInfo)this.updateCols[i][1];
            }
          }
        }
        */
      }
    }
  }

  public boolean skipChildren(Visitable node) throws StandardException {    
    return node instanceof ProjectRestrictNode || node instanceof FromBaseTable
        || node instanceof AndNode || node instanceof ResultColumn ;
  }

  public boolean stopTraversal() {    
    return false;
  }

  @Override
  public Visitable visit(Visitable node) throws StandardException {
    if (node instanceof UpdateNode) {
      final UpdateNode updNode = (UpdateNode)node;
      this.updateTargetTableNum = updNode.getTargetTableID();
      if (updNode.targetVTI != null) {
        updNode.targetVTI.computeQueryInfo(this.qic);
      }
      this.visit(updNode.getResultSetNode());
      /*Iterator<PredicateList>  itr = this.wherePredicateList.iterator();
      while(itr.hasNext()) {
        this.handlePredicateList(itr.next());
        itr.remove();
      } */
    }
    else if (node instanceof NormalizeResultSetNode) {
      this.visit(((NormalizeResultSetNode)node).getChildResult());
    }
    else if (node instanceof ResultColumnList) {
      handleResultColumnListNode((ResultColumnList)node);
    }
    else if (node instanceof ResultColumn) {
      handleResultColumnNode((ResultColumn)node);
    }
    else {
      super.visit(node);
    }
    return node;
  }

  @Override
  public final boolean isUpdate() {
    return true;
  }

  public void getChangedRowAndFormatableBitSet(Activation activation,
      Object pk, Object[] result) throws StandardException {

    int numUpdateCols = this.updateCols.length;
    DataValueDescriptor[] changedRow = new DataValueDescriptor[this.tableQueryInfoList
        .get(this.updateTargetTableNum).getNumColumns()];
    FormatableBitSet changedColumnBitSet = new FormatableBitSet(numUpdateCols);
    for (int i = 0; i < numUpdateCols; ++i) {
      ColumnQueryInfo cqi = (ColumnQueryInfo)this.updateCols[i][0];
      if (cqi != null) {
        ValueQueryInfo vqi = (ValueQueryInfo)this.updateCols[i][1];
        DataValueDescriptor dvd = vqi
            .evaluateToGetDataValueDescriptor(activation);
        cqi.isNotNullCriteriaSatisfied(dvd);
        //Hook for column constraint check
        //ASIF: DISABLE COLUMN CONSTRAINT ON QUERY NODE
       //cqi.checkForConstraintViolation(dvd,  activation, pk);
        changedRow[cqi.getActualColumnPosition() - 1] = dvd;
        changedColumnBitSet.grow(cqi.getActualColumnPosition());
        changedColumnBitSet.set(cqi.getActualColumnPosition() - 1);
      }
    }
    result[0] = changedRow;
    result[1] = changedColumnBitSet;
  }

  private void handleResultColumnListNode(ResultColumnList rlc)
      throws StandardException {
    //if (!this.process[RESULTS_LIST_NODE]) {
     // return;
    //}
    this.updateCols = new QueryInfo[rlc.size()][2];
    //this.process[RESULTS_LIST_NODE] = false;

  }
  
  @Override
  int processResultColumnListFromProjectRestrictNodeAtLevel() {
    return 0;
  }

  private void handleResultColumnNode(ResultColumn rc) throws StandardException {

   
      ++this.currentColumnIndex;
      if (rc.updated()) {
        ColumnQueryInfo cqi = new ColumnQueryInfo(rc,this.qic);
        this.updateCols[this.currentColumnIndex - 1][0] = cqi;
        QueryInfo rhs = rc.getExpression().computeQueryInfo(this.qic);
        if (rhs == null) {
          rhs = QueryInfoConstants.DUMMY;
        }
        this.updateCols[this.currentColumnIndex - 1][1] =rhs;
        //TODO: Asif: Identify a cleaner way
        if(rhs == QueryInfoConstants.DUMMY ||
            // #51906 clear flag for update query with set statement with no ValueQueryInfo
            // for ex. update t1 set col2  = col3 where col1 =1
            rhs instanceof ColumnQueryInfo) {
          this.queryType = GemFireXDUtils.clear(this.queryType,IS_PRIMARY_KEY_TYPE);
        }
        
      }

  } 

  public LocalRegion getTargetRegion() {
    return this.tableQueryInfoList.get(this.updateTargetTableNum).getRegion();
  }

  @Override
  public boolean isTableVTI() {
    return this.qic.virtualTable() != null;
  }

  @Override
  public boolean routeQueryToAllNodes() {
    return this.qic.isVTIDistributable();
  }
}
