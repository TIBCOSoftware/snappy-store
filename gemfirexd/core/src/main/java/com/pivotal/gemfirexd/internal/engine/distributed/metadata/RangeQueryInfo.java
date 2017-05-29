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

import java.util.Set;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.RelationalOperator;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
 * This class object represents a range having a lower bound and upper bound. It
 * will be needed for nodes computation. The instance of this class can get
 * created only when operands are being merged in an AND junction
 * 
 * @see AndJunctionQueryInfo#mergeNonJunctionOperand(AbstractConditionQueryInfo,
 *      int)
 * 
 * 
 * @author Asif
 * 
 */
public class RangeQueryInfo extends AbstractConditionQueryInfo {

  private DataValueDescriptor lowerBound;

  private int lowerBoundOperator;

  private DataValueDescriptor upperBound;

  private int upperBoundOperator;
  
  private final String columnName;
  
  private final Region region;
  
  private QueryInfo runTimePruner = null;

  /**
   * 
   * @param lowerBound
   *          Object representing lower bound ( the key corresponding to greater
   *          operator i.e a > 7 or a >=7 ,7 is the lower bound)
   * @param lowerBndOp
   *          int representing > or >=
   * @param upperBound
   *          Object representing upper bound ( the key corresponding to less
   *          operator i.e a < 7 or a <= 7 ,7 is the upper bound)
   * @param upperBndOp
   *          int representing < or <=
   * @see RelationalOperator
   */
  RangeQueryInfo( DataValueDescriptor lowerBound, int lowerBndOp, DataValueDescriptor upperBound,
      int upperBndOp, String colName, Region region) {
    this.lowerBound = lowerBound;
    this.lowerBoundOperator = lowerBndOp;
    this.upperBound = upperBound;
    this.upperBoundOperator = upperBndOp;
    this.columnName = colName;
    this.region = region;

  }

  

  
  /*
   * Attempts to merge the operand in this RangeQueryInfo or creates a
   * ConstantCondtionsWrapperQueryInfo if the boolean is true which stores both
   * the operand as well as the RangeQueryInfo. If the operand is parameterized
   * it creates ParameterizedConditionsWrapperQueryInfo
   */
  @Override
  AbstractConditionQueryInfo createOrAddToGroup(
      AbstractConditionQueryInfo operand,
      boolean createConstantConditionsWrapper, Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
    if (activation == null && operand.isWhereClauseDynamic()) {
      return new ParameterizedConditionsWrapperQueryInfo(operand, this);
    }
    else {
      return mergeInRangeQueryInfoIfPossible((ComparisonQueryInfo)operand,
          activation, createConstantConditionsWrapper);
    }
  }

  private AbstractConditionQueryInfo mergeInRangeQueryInfoIfPossible(
      ComparisonQueryInfo cqi, Activation activation,
      boolean createConstantConditionsWrapper) throws StandardException {
    AbstractConditionQueryInfo retVal = null;
    // Check if it falls within range. If th operand to be merged happens to be
    // an
    // equality operator, we will not try to merge it in range even if it
    // satisfies
    // the bounds. This will automatically allow INQueryInfo not to get merged
    // in Range.
    // Thus if the range is 4 <=a <=10 and we get another a =5 , ii ideally means
    // removing the RangeQueryInfo altogether. It is difficult at present to
    // remove already
    // created structures in ANDJunction, so we will keep the equality &
    // !equality cases away
    // from merging
    int opType = cqi.getRelationalOperator();

    if (opType == RelationalOperator.EQUALS_RELOP
        || opType == RelationalOperator.NOT_EQUALS_RELOP) {
      if (createConstantConditionsWrapper) {
        retVal = new ConstantConditionsWrapperQueryInfo(cqi, this);
      }

    }
    else {
      DataValueDescriptor const1 = ((ValueQueryInfo)cqi.rightOperand).evaluateToGetDataValueDescriptor(activation);
      // satisfies > type operator
      boolean satisfiesLowerBound = isConditionSatisfied(const1,
          this.lowerBound, this.lowerBoundOperator);
      boolean satisfiesUpperBound = isConditionSatisfied(const1,
          this.upperBound, this.upperBoundOperator);

      switch (opType) {
      case RelationalOperator.GREATER_EQUALS_RELOP:
      case RelationalOperator.GREATER_THAN_RELOP:
        if (satisfiesLowerBound && satisfiesUpperBound) {
          // The operand falls within the range
          // Replace the lower bound with new value
          this.lowerBound = const1;
          this.lowerBoundOperator = opType;
          retVal = this;
        }
        else if (satisfiesUpperBound) {
          // The operand does not fall within the range but supports the range
          // i.e say if
          // the range is a >1 and a <7 and if the operand is a > -5, then -5
          // supports the upper bound
          // This operand can be ignored so.
          retVal = this;
        }
        else if (createConstantConditionsWrapper) {
          // The operand does not fall within the range and also does not
          // supports the range i.e say if
          // the range is a >1 and a <7 and if the operand is a > 9, then 9
          // does not support the upper bound
          // This operand cannot ignored as it forms a mutually exclusive
          // condition.
          retVal = new ConstantConditionsWrapperQueryInfo(cqi, this);
        }
        break;
      case RelationalOperator.LESS_EQUALS_RELOP:
      case RelationalOperator.LESS_THAN_RELOP:
        if (satisfiesLowerBound && satisfiesUpperBound) {
          // Replace the lower bound with new value
          this.upperBound = const1;
          this.upperBoundOperator = opType;
          retVal = this;
        }
        else if (satisfiesLowerBound) {
          retVal = this;
        }
        else if (createConstantConditionsWrapper) {
          retVal = new ConstantConditionsWrapperQueryInfo(cqi, this);
        }
        break;
      default:
        throw new IllegalStateException("This case of operator type = "
            + opType + " should not have arisen");
      }
    }

    return retVal;
  }

 

  /**
   * 
   * @return Object representing lower bound key
   */
  DataValueDescriptor getLowerBound() {
    return this.lowerBound;
  }

  /**
   * 
   * @return int representing operator ( > or >=)
   */
  int getLowerBoundOperator() {
    return this.lowerBoundOperator;
  }

  /**
   * 
   * @return Object representing upper bound key
   */
  DataValueDescriptor getUpperBound() {
    return this.upperBound;
  }

  /**
   * 
   * @return int representing operator ( < or <=)
   */
  int getUpperBoundOperator() {
    return this.upperBoundOperator;
  }
  
  @Override
  public String toString() {
    StringBuilder sbuff = new StringBuilder();
    sbuff.append("lower bound = " );
    sbuff.append(this.lowerBound);
    sbuff.append("; lower bound operator = " );
    sbuff.append(this.lowerBoundOperator);
    sbuff.append(" ;upper bound = " );
    sbuff.append(this.upperBound);
    sbuff.append("; upper bound operator = " );
    sbuff.append(this.upperBoundOperator);
    return sbuff.toString();
  }


  
  @Override
  boolean isWhereClauseDynamic() {
    return false;
  }
  
  @Override
  QueryInfo getRuntimeNodesPruner(boolean forSingleHopPreparePhase)
  {
    if(this.runTimePruner == null) {
      this.runTimePruner = this.createRuntimeNodesPruner();
    }
    return this.runTimePruner;
  }



  
  private QueryInfo createRuntimeNodesPruner()
  {
    final LogWriter logger = Misc.getCacheLogWriter();
    QueryInfo pruner =null; 
    //For the timebeing assume that nodes pruning will occur only if
    // the region is partitioned
    if (this.region.getAttributes().getDataPolicy().withPartitioning()) {
      assert this.region instanceof PartitionedRegion;
      PartitionedRegion pr = (PartitionedRegion)this.region;
      final GfxdPartitionResolver rslvr = GemFireXDUtils.getResolver(pr);
      String rslvrColNames[] = null;
      if (rslvr != null
          && (rslvrColNames = rslvr.getColumnNames()).length == 1
          && rslvrColNames[0].equals(this.columnName)) {
        pruner = new AbstractQueryInfo() {
          @Override
          public void computeNodes(Set<Object> routingKeysToExecute,
              Activation activation, boolean forSingleHopPreparePhase)
              throws StandardException {
            try {
            if (logger.fineEnabled()) {
              logger.fine("RangeQueryInfo::computeNodes: Resolver ="
                  + rslvr.toString());

              logger
                  .fine("RangeQueryInfo::computeNodes: lower ="
                      + lowerBound
                      + " boolean = "
                      + (lowerBoundOperator == RelationalOperator.GREATER_EQUALS_RELOP)
                      + " Upper bound = "
                      + upperBound
                      + " boolean = "
                      + (upperBoundOperator == RelationalOperator.LESS_EQUALS_RELOP)
                      + " forSingleHopPreparePhase = " + forSingleHopPreparePhase);
            }
            Object[] routingObjects = rslvr
                .getRoutingObjectsForRange(
                    lowerBound,
                    lowerBoundOperator == RelationalOperator.GREATER_EQUALS_RELOP,
                    upperBound,
                    upperBoundOperator == RelationalOperator.LESS_EQUALS_RELOP);
            if (routingObjects != null) {
              routingKeysToExecute.remove(ResolverUtils.TOK_ALL_NODES);
              for (Object key : routingObjects) {
                routingKeysToExecute.add(key);
              }
            }
          }
          finally {

            if (logger.fineEnabled()) {
              logger
                  .fine("RangeQueryInfo::computeNodes: After prunning nodes size is"
                      + routingKeysToExecute.size());
              logger.fine("RangeQueryInfo::computeNodes: After prunning nodes are"
                  + routingKeysToExecute);
            }
          }
          
          }
        };
      }
       else {
        pruner = QueryInfoConstants.NON_PRUNABLE;
      }
    }
    else {
      pruner = QueryInfoConstants.NON_PRUNABLE;
    }    
    return pruner;
  }



 

}
