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
/**
 * 
 */
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import java.util.ArrayList;
import java.util.Set;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.impl.sql.compile.RelationalOperator;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
 * This is the super class of the ComparisonQueryInfo. The junction Query info
 * class will also extend this class
 * 
 * This class may be converted into an interface once the system is stabilized &
 * all the functions present in QueryInfo have been given meaningful definition
 * and AbstractQueryInfo has been removed.
 * 
 * @author Asif
 * @see ComparisonQueryInfo
 * 
 */
public abstract class AbstractConditionQueryInfo extends AbstractQueryInfo  {
   //QueryInfo runTimePruner = null; //Uninitialized
  /**
   * Overloaded method for ComparisonQueryInfo which checks if the condition
   * satisfies the primary key requirement for conversion to "get" & returns the
   * primary Key This method has concrete implementation in ComparisonQueryInfo
   * only
   * 
   * @param pkColumn
   *          Actual position of the primary key column in the table ( 1 based
   *          index);
   * @param tableNumber
   *          Table number
   * @return Constant representing the primary key ( or component of the
   *         composite key) , if the column satisfies the requirement of
   *         region.get otherwise null
   */  
  Object isConvertibleToGet(int pkColumn, TableQueryInfo tqi)
      throws StandardException {
    throw new UnsupportedOperationException("This method should not get invoked");
  }

  /**
   * Overloaded method for ComparisonQueryInfo and JunctionQueryInfo which
   * checks if the condition satisfies the primary key requirement for
   * conversion to region.get & returns the primary Key ( or composite key) This
   * method has concrete implementation in ComparisonQueryInfo as well as
   * JunctionQueryInfo.
   * 
   * @param pkColumns
   *          int array containing the actual positions of primary key column ,
   *          if any ( 1 based column index)
   * @param tqi
   *          the TableQueryInfo of the table
   * 
   * @return Constant representing the primary key or composite key ( Object [])
   *         if can be converted into region.get else return null
   */
  Object isConvertibleToGet(int[][] pkColumns, TableQueryInfo tqi)
      throws StandardException
  {
    throw new UnsupportedOperationException("This method not supported");
  }
  
  /** 
   * Only applicable for local indexes
   */
  Object isConvertibleToGetOnLocalIndex(int[][] ikColumns, TableQueryInfo tqi)
      throws StandardException {
    throw new UnsupportedOperationException("This method not supported");
  }
  
  Object isConvertibleToGetOnLocalIndex(int ikColumn, TableQueryInfo tqi)
      throws StandardException {
    throw new UnsupportedOperationException("This method not supported");
  }

  /**
   * 
   * @return boolean indicating if the where clause is determined to be not
   *         convertible to region.get thus avoiding the need for analysing the
   *         conditions forming the where clause.
   * 
   */
  boolean isStaticallyNotGetConvertible() {
    throw new UnsupportedOperationException("This method not supported");
  }

  /**
   * 
   * @return int indicating the type of Relational operator
   * @see RelationalOperator
   */
  int getRelationalOperator()
  {
    throw new UnsupportedOperationException("This method not supported");
  }

 
 
 
  /**
   * This is the basic method which is invoked from SelectQueryInfo.
   * The case of merging involves two AbstractConditionQueryInfo objects, say
   * acqi1 & acqi2.  Either of the AbstractConditionQueryInfo could be any one
   * of the ComparisonQuerynfo, BooleanConstantQueryInfo or JunctionQueryInfo.
   * @param operand AbstractConditionQueryInfo ( JunctionQueryInfo, ComparisonQueryInfo
   * or BooleanConstantQueryInfo) to be merged into this AbstractConditionQueryInfo
   * @param operandJunctionType int indicating the junction type of the parent junction which 
   * has AbstractonditionQueryInfo as its left & right params
   * @return AbstractConditionQueryInfo  ( JunctionQueryInfo, ComparisonQueryInfo or BooleanConstantQueryInfo)
   * @see SelectQueryInfo#processJunctionNode
   */ 
  AbstractConditionQueryInfo mergeOperand(
      AbstractConditionQueryInfo operand, int operandJunctionType, boolean isTopLevel) throws StandardException {
    throw new UnsupportedOperationException("This method should not get invoked");
  }

  /**
   *The concrete implementation will be in all types of AbstractConditionQueryInfo.
   * Invocation of this method indicates that the operand is NOT a JunctionQueryInfo.
   * This helps identify whether a new JunctionQueryInfo needs to be created or can be
   * added in existing JunctionQueryInfo ( if the other operand happens to be JunctionQueryInfo)
   * @param operand AbstractConditionueryInfo ( of type which is not JunctionQueryInfo)
   * @param operandJunctionType parent junction type
   * @return JunctionQueryInfo 
   * @see ComparisonQueryInfo#mergeNonJunctionOperand(AbstractConditionQueryInfo, int)
   * @see BooleanConstantQueryInfo#mergeNonJunctionOperand(AbstractConditionQueryInfo, int)
   * @see JunctionQueryInfo#mergeNonJunctionOperand(AbstractConditionQueryInfo, int)
   */
  JunctionQueryInfo mergeNonJunctionOperand(
      AbstractConditionQueryInfo operand, int operandJunctionType)throws StandardException {
    throw new UnsupportedOperationException("This method should not get invoked");
  }

  /**
   * The concrete implementation will be in all types of AbstractConditionQueryInfo.
   * Invocation of this method indicates that the operand is A JunctionQueryInfo.
   * This helps identify whether a new JunctionQueryInfo needs to be created or can be
   * added in existing JunctionQueryInfo.  
   * @param junctionOperand JunctionQueryInfo which needs to be merged with the other operand
   * which can be ComparisonQueryInfo, BooleanConstantQueryInfo or JunctionQueryInfo
   * @param operandJunctionType parent junction type
   * @return JunctionQueryInfo 
   */
  JunctionQueryInfo mergeJunctionOperand(
      JunctionQueryInfo junctionOperand, int operandJunctionType) throws StandardException {
    throw new UnsupportedOperationException("This method should not get invoked");
  }
  
  
  /**
   * 
   * @return an int indicating the actual position if the CompraisonQueryInfo is
   *         of the form variable=constant. If the operand is a
   *         JunctionQueryInfo or if the operand is of the form var= var or
   *         constant=constant or if column poistion cannot be figured out , it
   *         will return -1;
   */
  int getActualColumnPostionOfOperand() {
    return -1;
  }
  
  @Override
  public void computeNodes(Set<Object> routingKeysToExecute,
      Activation activation, boolean forSingleHopPreparePhase)
      throws StandardException {

    LogWriter logger = Misc.getCacheLogWriter();
    if (logger.fineEnabled()) {
      logger.fine("AbstractConditionQueryInfo::computeNodes: "
          + "Before prunning nodes size is " + routingKeysToExecute.size());
      logger.fine("AbstractConditionQueryInfo::computeNodes: "
          + "Before prunning nodes are " + routingKeysToExecute);
    }
    assert routingKeysToExecute.size() == 1;
    assert routingKeysToExecute.contains(ResolverUtils.TOK_ALL_NODES);
   
    QueryInfo runTimePruner= this.getRuntimeNodesPruner(forSingleHopPreparePhase);
    
    
    assert runTimePruner != null;
    try {    
      runTimePruner.computeNodes(routingKeysToExecute, activation, forSingleHopPreparePhase);
    }
    finally {
      if (logger.fineEnabled()) {
        logger.fine("AbstractConditionQueryInfo::computeNodes: "
            + "After prunning nodes size is " + routingKeysToExecute.size());
        logger.fine("AbstractConditionQueryInfo::computeNodes: "
            + "After prunning nodes are " + routingKeysToExecute);
      }
      if (forSingleHopPreparePhase) {
        // TODO: KN may be a better way to clean up the run time node pruner
        // field after this is set during the single hop prepare phase. If this 
        // is not cleaned then the actual compute nodes uses this and we don't want this
        // because there are a lot of cases which are un-prunable in single hop
        // but are prunable otherwise.
        if (this instanceof ComparisonQueryInfo) {
          ((ComparisonQueryInfo)this).setRunTimePrunerToNull();
        }
      }
    }    
  }
  @Override
  public boolean isDynamic() {
    throw new UnsupportedOperationException("This method should not have got invoked");
  }

  /**
   * 
   * @return String uniquely identifying the column name . The String will
   *         formed will look like schema_name.table_name.column_name It is
   *         appropriately overridden in the concrete classes.
   */
  String getUniqueColumnName() {
    return null;
  }
 
 /**
   * This method is used for grouping of operands in an AND junction based on
   * Column name. If multiple AND conditions involving a column are present, it
   * attempts to group them by creating one of {@link RangeQueryInfo} ,
   * {@link ConstantConditionsWrapperQueryInfo},
   * {@link ParameterizedConditionsWrapperQueryInfo} or returning an existing
   * AbstractConditionQueryInfo object
   * 
   * @param operand
   *          AbstractConditionQueryInfo which needs to be merged with or added
   *          along with existing AbstractConditionQueryInfo
 * @param createConstantConditionsWrapperIfMergeNotPossible
   *          boolean if true indicates that if both the operands need to exist ,
   *          it should create a {@link ConstantConditionsWrapperQueryInfo} else
   *          return null.
 * @param forSingleHopPreparePhase TODO
 * @param activation
   *          Activation object which will be passed as null when the data
   *          structures are being formed during the parsing (compile) phase of
   *          the query. At execution time, the Activation object passed will be
   *          not null. This will also be used to identify the nature of
   *          parameterized ComparisonQueryInfo, whether it should be treated as
   *          dynamic (create ParameterizedConditionsWrapperQueryInfo) or as
   *          constant ( which it will be during execution time as Activation
   *          object will provide parameter's value)
   * @return AbstractConditionQueryInfo object representing the relevant
   *         structure which represents the existing and operand to be merged
   * @throws StandardException
   */
  AbstractConditionQueryInfo createOrAddToGroup(
      AbstractConditionQueryInfo operand,
      boolean createConstantConditionsWrapperIfMergeNotPossible,
      Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not get invoked");
  }
 
 
 
 /**
  * Checks if key1 operator key2 is true or not. The operator could be =, != , <, >,<=,>=
  * 
  * @param key1
  * @param key2
  * @param operator2 int indicating the relational operator of the condition 
  * of second parameter.
  * @return boolean true if the condition is satisfied else false. If true the first
  * parameter satisfies the condition & its operator will prevail
  * @throws TypeMismatchException
  * @throws StandardException 
  */
  static  boolean isConditionSatisfied(Object key1, Object key2, int operator)
     throws  StandardException {
   
   return ((Boolean)GemFireXDUtils.compare(key1, key2, operator)).booleanValue();
   
 }  

  /**
   * Generates unique column name
   * 
   * @param schema
   * @param table
   * @param column
   * @return String
   */
  public static String generateUniqueColumnName(String schema, String table,
      String column) {
    final StringBuilder uname = new StringBuilder();
    return Misc.generateFullTableName(uname, schema, table, null).append('.')
        .append(column).toString();
  }

  abstract boolean isWhereClauseDynamic();

  /**
   * This method is overridden in
   * {@link ComparisonQueryInfo#isEquiJoinCondition()}
   * 
   * @return true if a condition satisfies an equi join criteria.
   */
  boolean isEquiJoinCondition() {
    return false;
  }

  /**
   * Concrete implementation in
   * {@link ComparisonQueryInfo#isEquiJoinColocationCriteriaFullfilled()} and
   * {@link JunctionQueryInfo#isEquiJoinColocationCriteriaFullfilled()}. This
   * method is invoked from {@link SelectQueryInfo#init()} to find out if a
   * given multi table query can be executed via scatter/gather or nodes pruning
   * correctly
   * 
   * @return null if the colocated equijoin criteria for a given query is
   *         fullfilled, else the reason for colocation equijoin criteria
   *         failure
   */
  String isEquiJoinColocationCriteriaFullfilled(TableQueryInfo ncjTqi) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  QueryInfo getRuntimeNodesPruner(boolean forSingleHopPreparePhase) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  void seedColocationMatrixData(int rows, ArrayList<TableQueryInfo> tables,
      int numPRTables) {
  }


  Object getAllConditions(int[][] pkColumns, TableQueryInfo tqi)
      throws StandardException
  {
    return null;
  }
  protected boolean isPartofUpdate  = false;

}
