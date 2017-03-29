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
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalRowLocation;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.*;
import com.pivotal.gemfirexd.internal.shared.common.CharColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.ColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.DecimalColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.DoubleColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.IntColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.LongIntColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.RangeRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.RealColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.RoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.SmallIntRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.VarCharColumnRoutingObjectInfo;

/**
 * The instance of this class represents a relational operation
 * @since GemFireXD
 * @author Asif
 * @see BinaryRelationalOperatorNode
 *
 */
public class ComparisonQueryInfo extends AbstractConditionQueryInfo {

  final QueryInfo leftOperand;

  final QueryInfo rightOperand;

  //RelationalOperator interface contains the relation determined by the
  //operator type
  final int opType;
  protected int colocationMatrixRows;
  protected ArrayList<TableQueryInfo> colocationMatrixTables =
    new ArrayList<TableQueryInfo>();
  protected int colocationMatrixPRTableCount;
  
  private QueryInfo runTimePruner = null;
  

  /**
   * 
   * @param left QueryInfo Object 
   * @param right QueryInfo Object 
   * @param opType int representing the operations 
   * @see RelationalOperator
   * @see BinaryRelationalOperatorNode
   */
  public ComparisonQueryInfo(QueryInfo left, QueryInfo right, int opType)
      throws StandardException {
    //TODO:Asif: Lets store ColumnQueryInfo in the leftOperand
    //what happens if both the operands are ColumnQueryinfo or neither
    // is a ColumnQueryInfo?
    QueryInfo temp;
    
    if(!( left instanceof AbstractColumnQueryInfo) ) {
      //Assume right is instance of ColumnQueryInfo
      temp = left;
      left = right;
      right = temp;
      opType = reflectOperator(opType);
    }
    this.leftOperand = left;
    // [sumedh] adjust the type of constants (bug #41196)
    if (left instanceof AbstractColumnQueryInfo) {
      adjustConstantType((AbstractColumnQueryInfo)left, right);
    }
    this.rightOperand = right;
    this.opType = opType;   
  }

  protected void adjustConstantType(AbstractColumnQueryInfo left, QueryInfo right)
      throws StandardException {
    if (right instanceof ConstantQueryInfo) {
      ConstantQueryInfo rightConst = (ConstantQueryInfo)right;
      DataTypeDescriptor leftDtd = left.getType();
      DataValueDescriptor dvd = rightConst.getValue();
      if (dvd != null && dvd.getTypeFormatId() != leftDtd.getDVDTypeFormatId()) {
        DataValueDescriptor newDvd = leftDtd.getNull();
        newDvd.setValue(dvd);
        rightConst.setValue(newDvd);
      }
    }
  }

  @Override
  Object isConvertibleToGet(int pkColumn, TableQueryInfo tqi)
      throws StandardException {

    // TODO:Asif: Would it be better if we try to identify the column node
    // and the value node once & for all?
    Object pk = null;
    if (this.opType == RelationalOperator.EQUALS_RELOP) {
      if (this.leftOperand instanceof ColumnQueryInfo
          && (this.rightOperand instanceof ValueQueryInfo)) {
        ColumnQueryInfo cqi = (ColumnQueryInfo)leftOperand;
        if (cqi.getActualColumnPosition() == pkColumn
            && cqi.getTableNumber() == tqi.getTableNumber()) {
          pk = this.rightOperand.getPrimaryKey();
        }
      }
    }
    return pk;
  }

  @Override
  Object isConvertibleToGet(int[][] pkColumns, TableQueryInfo tqi)
      throws StandardException {
    if (pkColumns.length == 1) {
      Object pk = this.isConvertibleToGet(pkColumns[0][0], tqi);
      // If the primary key is not null and also it is not a Primary dynamic key,
      // then it needs to be coverted into GemFireKey here.
      if (pk != null && !this.isWhereClauseDynamic()) {
        pk = GemFireXDUtils.convertIntoGemfireRegionKey((DataValueDescriptor)pk,
            (GemFireContainer)tqi.getRegion().getUserAttribute(), false);
      }
      return pk;
    }
    else {
      return null;
    }
  }
  /*
   * (non-Javadoc)
   * @seeisConvertibleToGet(...)
   */
  @Override
  Object isConvertibleToGetOnLocalIndex(int fkColumn, TableQueryInfo tqi)
      throws StandardException {
    Object fk = null;
    if (this.opType == RelationalOperator.EQUALS_RELOP) {
      if (this.leftOperand instanceof ColumnQueryInfo
          && (this.rightOperand instanceof ValueQueryInfo)) {
        ColumnQueryInfo cqi = (ColumnQueryInfo)leftOperand;
        if (cqi.getActualColumnPosition() == fkColumn
            && cqi.getTableNumber() == tqi.getTableNumber()) {
          fk = this.rightOperand.getIndexKey();
        }
      }
    }
    return fk;
  }
  
  /*
   * (non-Javadoc)
   * @seeisConvertibleToGet(...)
   */
  @Override
  Object isConvertibleToGetOnLocalIndex(int[][] ikColumns, TableQueryInfo tqi)
      throws StandardException {
    if (ikColumns.length == 1) {
      return this.isConvertibleToGetOnLocalIndex(ikColumns[0][0], tqi);
    }
    else {
      return null;
    }
  }

  @Override
  AbstractConditionQueryInfo mergeOperand(AbstractConditionQueryInfo operand,
      int operandJunctionType, boolean isTopLevel) throws StandardException {
    // If the operand is null then just return this
    // else if the operand is ComaprisonQueryInfo , then create a new junction.
    // else if the operand is JunctionQueryInfo of the type same as that passed
    // then add to the junction
    if (operand == null) {
      return this;
    }
    // turn new JunctionQueryInfo(this, operand, operandJunctionType);
    return operand.mergeNonJunctionOperand(this, operandJunctionType);
  }

  @Override
  JunctionQueryInfo mergeNonJunctionOperand(AbstractConditionQueryInfo operand,
      int operandJunctionType) throws StandardException {
    return operandJunctionType == QueryInfoConstants.AND_JUNCTION
        ? new AndJunctionQueryInfo(this, operand)
        : new OrJunctionQueryInfo(this, operand);
  }

  @Override
  JunctionQueryInfo mergeJunctionOperand(JunctionQueryInfo junctionOperand,
      int operandJunctionType) throws StandardException {
    return junctionOperand.mergeNonJunctionOperand(this, operandJunctionType);

  }

  /**
   * Test API
   * 
   * @return Returns an arary containing the left & right operand of the
   *         ComparisonQueryInfo
   */
  QueryInfo[] getOperands() {
    return new QueryInfo[] { this.leftOperand, this.rightOperand };
  }

  /**
   *
   * @return int indicating the relation operation type
   *  
   */
  @Override
  int getRelationalOperator() {
    return this.opType;
  }

  @Override
  int getActualColumnPostionOfOperand() {
    if (this.leftOperand instanceof AbstractColumnQueryInfo
        && (this.rightOperand instanceof ValueQueryInfo )) {
      AbstractColumnQueryInfo acqi = (AbstractColumnQueryInfo)leftOperand;
      return acqi.getActualColumnPosition();
    }    
    else {
      return -1;
    }
  }

  @Override
  public boolean isWhereClauseDynamic() {
    //Because the implementation is not yet complete, in some cases like isNullNode , the rhs operand may be
    //null so we need to take care of it for now.
    return (this.leftOperand != null && this.leftOperand.isDynamic()) || (this.rightOperand != null &&  this.rightOperand.isDynamic());
  }
  
  @Override
  QueryInfo getRuntimeNodesPruner(boolean forSingleHopPreparePhase)
  {
    if (this.runTimePruner == null) {
      this.runTimePruner = createRuntimeNodesPruner(forSingleHopPreparePhase);
    }
    return this.runTimePruner;
  }
  
  
  private QueryInfo createRuntimeNodesPruner(boolean forSingleHopPreparePhase)
  {

    // If the column happens to be a partition key or is having global index
    // utilize it
    // First identify the constant part & column part.
    // TODO:Asif: Is there a cleaner way to do it?
    final AbstractColumnQueryInfo acqi; // (this.leftOperand instanceof
    // ColumnQueryInfo)?(ColumnQueryInfo)this.leftOperand:(this.rightOperand
    // instanceof
    // ColumnQueryInfo)?(ColumnQueryInfo)this.rightOperand:null;
    final ValueQueryInfo vqi;
    QueryInfo pruner = null;
    if (this.leftOperand instanceof AbstractColumnQueryInfo) {
      acqi = (AbstractColumnQueryInfo)this.leftOperand;
      if (this.rightOperand instanceof ValueQueryInfo) {
        vqi = (ValueQueryInfo)this.rightOperand;
      }
      else {
        vqi = null;
      }
    }
    else {
      acqi = null;
      vqi = null;
    }

    if (acqi == null || vqi == null) {
      pruner = QueryInfoConstants.NON_PRUNABLE;
    }
    else {
      // Only if the region involved is a PR would it contribute to nodes
      // pruning.
      // For the time being assume that a replicated region will not contribute
      // to nodes
      // pruning
      GfxdPartitionResolver spr = acqi.getResolverIfSingleColumnPartition();
      if (spr != null) {
        pruner = getResolverBasedPruner(spr, vqi, acqi, this.opType);
      }
      else if (this.opType == RelationalOperator.EQUALS_RELOP
          && !acqi.isExpression() && !forSingleHopPreparePhase) {
        pruner = attemptToGetGlobalIndexBasedPruner(vqi, (ColumnQueryInfo)acqi);
      }
      else {
        pruner = QueryInfoConstants.NON_PRUNABLE;
      }
    }
    return pruner;
  }
  
  private static QueryInfo attemptToGetGlobalIndexBasedPruner(final ValueQueryInfo vqi, final ColumnQueryInfo cqi) {

    final LogWriter logger = Misc.getCacheLogWriter();
    List<ConglomerateDescriptor> globalIndexes = cqi.getAvailableGlobalHashIndexForColumn();
    // Pick only the index whose num cols is 1
    ConglomerateDescriptor cd = null;
    Iterator<ConglomerateDescriptor> itr = globalIndexes.iterator();
    QueryInfo pruner = null;
    while (itr.hasNext()) {
      ConglomerateDescriptor temp = itr.next();

      if (temp.getIndexDescriptor().baseColumnPositions().length == 1) {
        cd = temp;
        break;
      }
    }
    if (cd != null) {      
      final long conglomID = cd.getConglomerateNumber();
      pruner = new AbstractQueryInfo() {
        @Override
        public void computeNodes(Set<Object> routingKeysToExecute,
            Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
          TransactionController tc = activation.getTransactionController();   
          DataValueDescriptor keyDVD = vqi.evaluateToGetDataValueDescriptor(activation);
          DataValueDescriptor[] dvdKeyArr = new DataValueDescriptor[] { keyDVD };
          ScanController scan = tc.openScan(
              conglomID,
              false, // hold
              0, // open read only
              TransactionController.MODE_TABLE,
              TransactionController.ISOLATION_SERIALIZABLE, (FormatableBitSet)null,
              dvdKeyArr, // start key value
              ScanController.GE, // start operator
              null, // qualifier
              dvdKeyArr, // stop key value
              ScanController.GE, null);
          if (scan.next()) {
            // Remove TOKEN_ALL_NODES
            routingKeysToExecute.clear();
            final DataValueDescriptor[] fetchKey = new DataValueDescriptor[2];
            fetchKey[0] = keyDVD;
            while (scan.next()) {
              GlobalRowLocation grl = new GlobalRowLocation();
              fetchKey[1] = grl;
              scan.fetch(new DataValueDescriptor[] { keyDVD, grl });
              Object routingObject = grl.getRoutingObject();
              if (logger.fineEnabled()) {
                logger.fine("ComparisonQueryInfo::pruneUsingGlobalIndex: "
                    + "Scan of Global index found a row = " + grl);
                logger.fine("ComparisonQueryInfo::pruneUsingGlobalIndex: "
                    + "Scan of Global index found routing object = "
                    + routingObject);
              }

              assert routingObject != null;
              routingKeysToExecute.add(routingObject);
            }
          }
          else {
            if (logger.fineEnabled()) {
              logger.fine("ComparisonQueryInfo::pruneUsingGlobalIndex: "
                  + "Scan of Global index found NO row");
            }
            // The row has not been inserted yet. Clear the map
            routingKeysToExecute.clear();
          }
          scan.close();
        }
      };
    }
    else {
      pruner = QueryInfoConstants.NON_PRUNABLE;
    }
    return pruner;
  }

  /**
   * Checks if the column present in the condition is part of the
   * Global Index. It checks if the actual column position is 
   * present in the baseColPos array which contains column positions
   * constituting the Global index.
   * @param baseColPos int array containing the column positions of columns 
   * on which Global index is created
   * 
   * @return -1 if the column is not part of the column/s on which global index is created.
   * Otherwise , the baseColPos array index. This number will define the position of the
   * DVD ( of this column) in the Index Key. ( Though Global Index of compound type 
   * is independent of the sequence/order of index columns ).  
   */
  int isPartOfGlobalIndex(int[] baseColPos) {
    int indexPos = -1;
    int actualColPos = this.getActualColumnPostionOfOperand();
    if (actualColPos != -1) {
      int len = baseColPos.length;      
      for (int pos = 0 ; pos <len; ++pos) {
        if (baseColPos[pos] == actualColPos) {
          indexPos = pos;
          break;
        }
      }
    }
    return indexPos;
  }
  
  private static QueryInfo getResolverBasedPruner(
      final GfxdPartitionResolver spr, final ValueQueryInfo vqi,
      final AbstractColumnQueryInfo acqi, final int opType) {

    final LogWriter logger = Misc.getCacheLogWriter();
    // Set routingKeySet;
    final boolean isExpression = acqi.isExpression();
    if (isExpression && opType != RelationalOperator.EQUALS_RELOP) {
      return QueryInfoConstants.NON_PRUNABLE;
    }
    QueryInfo pruner = null;
    switch (opType) {
      case RelationalOperator.EQUALS_RELOP: {
        pruner = new AbstractQueryInfo() {
          @Override
          public void computeNodes(Set<Object> routingKeysToExecute,
              Activation activation, boolean forSingleHopPreparePhase)
              throws StandardException {
            if (!forSingleHopPreparePhase) {
              DataValueDescriptor key = vqi
                  .evaluateToGetDataValueDescriptor(activation);
              // Remove TOKEN_ALL_NODES
              if (logger.fineEnabled()) {
                logger
                    .fine("ComparisonQueryInfo.computeNodes: equality pruner");
              }
              routingKeysToExecute.clear();
              Object routingKey;
              if (isExpression) {
                routingKey = spr.getRoutingObjectForExpression(key);
              }
              else {
                routingKey = spr.getRoutingKeyForColumn(key);
              }
              assert routingKey != null;
              routingKeysToExecute.add(routingKey);
            }
            else {
              if (isExpression) {
                routingKeysToExecute.add(QueryInfoConstants.NON_PRUNABLE);
                return;
              }
              routingKeysToExecute.clear();
              int type = vqi.typeOfValue();
              switch (type) {
                case ValueQueryInfo.CONSTANT_VALUE:
                case ValueQueryInfo.PARAMETERIZED_CONSTANT_VALUE:
                  routingKeysToExecute.add(getAppropriateRoutingObjectInfo(activation, vqi, spr));
                  break;

                case ValueQueryInfo.PARAMETER_VALUE:
                  routingKeysToExecute.add(getAppropriateRoutingObjectInfo(activation, vqi, spr));
                  break;
              }
            }
          }
        };
        break;
      }
      case RelationalOperator.GREATER_EQUALS_RELOP:
      case RelationalOperator.GREATER_THAN_RELOP: {
        pruner = new AbstractQueryInfo() {
          @Override
          public void computeNodes(Set<Object> routingKeysToExecute,
              Activation activation, boolean forSingleHopPreparePhase)
              throws StandardException {
            if (!forSingleHopPreparePhase) {
              DataValueDescriptor key = vqi
                  .evaluateToGetDataValueDescriptor(activation);
              if (logger.fineEnabled()) {
                logger
                    .fine("ComparisonQueryInfo::computeNodes: range passed for Greater condition (lower bound ). Lower bound = "
                        + key
                        + "Inclusive ="
                        + (opType == RelationalOperator.GREATER_EQUALS_RELOP));
                logger.fine("ComparisonQueryInfo::computeNodes:Resolver is = "
                    + spr);
              }

              Object routingObjects[] = spr.getRoutingObjectsForRange(key,
                  opType == RelationalOperator.GREATER_EQUALS_RELOP, null,
                  false);
              if (routingObjects != null) {
                // Remove TOKEN_ALL_NODES
                if (logger.fineEnabled()) {
                  logger
                      .fine("ComparisonQueryInfo::computeNodes:routing keys length="
                          + routingObjects.length);
                  for (Object i : routingObjects) {
                    logger
                        .fine("ComparisonQueryInfo::computeNodes:routing key="
                            + i);
                  }

                }
                routingKeysToExecute.clear();
                // Set prunedNodes =
                // pr.getMembersFromRoutingObjects(routingObjects);
                for (int i = 0; i < routingObjects.length; ++i) {
                  routingKeysToExecute.add(routingObjects[i]);
                }
              }
            }
            else {
              if (isExpression) {
                routingKeysToExecute.add(QueryInfoConstants.NON_PRUNABLE);
                return;
              }
              int type = vqi.typeOfValue();
              switch (type) {
                case ValueQueryInfo.CONSTANT_VALUE:
                case ValueQueryInfo.PARAMETERIZED_CONSTANT_VALUE:
                  routingKeysToExecute.add(new RangeRoutingObjectInfo(
                      RoutingObjectInfo.INVALID, getAppropriateRoutingObjectInfo(activation, vqi, spr),
                      opType == RelationalOperator.GREATER_EQUALS_RELOP, null,
                      false, spr));
                  break;

                case ValueQueryInfo.PARAMETER_VALUE:
                  routingKeysToExecute.add(new RangeRoutingObjectInfo(
                      RoutingObjectInfo.INVALID, getAppropriateRoutingObjectInfo(activation, vqi, spr),
                      opType == RelationalOperator.GREATER_EQUALS_RELOP, null,
                      false, spr));
                  break;
              }
            }
          }
        };

        break;
      }
      case RelationalOperator.LESS_EQUALS_RELOP:
      case RelationalOperator.LESS_THAN_RELOP: {
        pruner = new AbstractQueryInfo() {
          @Override
          public void computeNodes(Set<Object> routingKeysToExecute,
              Activation activation, boolean forSingleHopPreparePhase)
              throws StandardException {
            if (!forSingleHopPreparePhase) {
              DataValueDescriptor key = vqi
                  .evaluateToGetDataValueDescriptor(activation);
              if (logger.fineEnabled()) {
                logger
                    .fine("ComparisonQueryInfo::computeNodes: range passed for Lower condition (upper bound) . Upper bound = "
                        + key
                        + "Inclusive ="
                        + (opType == RelationalOperator.LESS_EQUALS_RELOP));
                logger.fine("ComparisonQueryInfo::computeNodes:Resolver is = "
                    + spr);
              }

              Object routingObjects[] = spr.getRoutingObjectsForRange(null,
                  false, key, opType == RelationalOperator.LESS_EQUALS_RELOP);
              if (routingObjects != null) {
                if (logger.fineEnabled()) {
                  logger
                      .fine("ComparisonQueryInfo::computeNodes:routing keys length="
                          + routingObjects.length);
                  for (Object i : routingObjects) {
                    logger
                        .fine("ComparisonQueryInfo::computeNodes:routing key="
                            + i);
                  }

                }
                // Remove TOKEN_ALL_NODES
                routingKeysToExecute.clear();
                for (Object i : routingObjects) {
                  routingKeysToExecute.add(i);
                }

              }
            }
            else {
              if (isExpression) {
                routingKeysToExecute.add(QueryInfoConstants.NON_PRUNABLE);
                return;
              }
              int type = vqi.typeOfValue();
              switch (type) {
                case ValueQueryInfo.CONSTANT_VALUE:
                case ValueQueryInfo.PARAMETERIZED_CONSTANT_VALUE:
                  routingKeysToExecute.add(new RangeRoutingObjectInfo(
                      RoutingObjectInfo.INVALID, getAppropriateRoutingObjectInfo(activation, vqi, spr),
                      opType == RelationalOperator.LESS_EQUALS_RELOP, null,
                      false, spr));
                  break;

                case ValueQueryInfo.PARAMETER_VALUE:
                  routingKeysToExecute.add(new RangeRoutingObjectInfo(
                      RoutingObjectInfo.INVALID, getAppropriateRoutingObjectInfo(activation, vqi, spr),
                      opType == RelationalOperator.LESS_EQUALS_RELOP, null,
                      false, spr));
                  break;
              }
            }
          }
        };

        break;
      }
      default:
        pruner = QueryInfoConstants.NON_PRUNABLE;
    }
    return pruner;
  }

  public static ColumnRoutingObjectInfo getAppropriateRoutingObjectInfo(
      Activation activation, ValueQueryInfo vqi, GfxdPartitionResolver spi) throws StandardException {
    final int infotype = vqi.typeOfValue();
    switch (infotype) {
      case ValueQueryInfo.CONSTANT_VALUE:
      case ValueQueryInfo.PARAMETERIZED_CONSTANT_VALUE:
        DataValueDescriptor dvd = vqi.evaluateToGetConstant(activation);
        Object value = dvd.getObject();
        int type = dvd.getTypeFormatId();
        switch(type) {
          case StoredFormatIds.SQL_INTEGER_ID:
            return new IntColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);

          case StoredFormatIds.SQL_LONGINT_ID:
            return new LongIntColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            
          case StoredFormatIds.SQL_SMALLINT_ID:
            return new SmallIntRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            
          case StoredFormatIds.SQL_CHAR_ID:
            //return new CharColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            CharColumnRoutingObjectInfo ccroi = new CharColumnRoutingObjectInfo(
                RoutingObjectInfo.CONSTANT, value, spi);
            ccroi.setMaxWidth(vqi.getMaximumWidth());
            return ccroi;

          case StoredFormatIds.SQL_DECIMAL_ID:
            return new DecimalColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            
          case StoredFormatIds.SQL_REAL_ID:
            return new RealColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            
          case StoredFormatIds.SQL_DOUBLE_ID:
            return new DoubleColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);

          case StoredFormatIds.SQL_VARCHAR_ID:
            VarCharColumnRoutingObjectInfo vccroi = new VarCharColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT, value, spi);
            vccroi.setMaxWidth(vqi.getMaximumWidth());
            return vccroi;
        }
      break;

      case ValueQueryInfo.PARAMETER_VALUE:
        int paramNumber = vqi.evaluateToGetParameterNumber(activation);
        int paramType = vqi.getParamType(activation);
        switch(paramType) {
          case StoredFormatIds.SQL_INTEGER_ID:
            return new IntColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);

          case StoredFormatIds.SQL_LONGINT_ID:
            return new LongIntColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            
          case StoredFormatIds.SQL_SMALLINT_ID:
            return new SmallIntRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            
          case StoredFormatIds.SQL_CHAR_ID:
            //return new CharColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            CharColumnRoutingObjectInfo ccroi = new CharColumnRoutingObjectInfo(
                RoutingObjectInfo.PARAMETER, paramNumber, spi);
            ccroi.setMaxWidth(vqi.getMaximumWidth());
            return ccroi;

            
          case StoredFormatIds.SQL_DECIMAL_ID:
            return new DecimalColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            
          case StoredFormatIds.SQL_REAL_ID:
            return new RealColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            
          case StoredFormatIds.SQL_DOUBLE_ID:
            return new DoubleColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);

          case StoredFormatIds.SQL_VARCHAR_ID:
            VarCharColumnRoutingObjectInfo vccroi = new VarCharColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER, paramNumber, spi);
            int maxWidth = vqi.getMaximumWidth();
            vccroi.setMaxWidth(maxWidth);
            return vccroi;
        }
        break;
    }
    // TODO: KN return non prunable for now null
    return null;
  }
  
  public static ColumnRoutingObjectInfo getAppropriateRoutingObjectInfo(
      ValueNode vn, int type, boolean isParameter, int paramNumber,
      Object value, GfxdPartitionResolver spi, int maximumWidth)
      throws StandardException {
    if (!isParameter) {
      switch (type) {
        case StoredFormatIds.SQL_INTEGER_ID:
          return new IntColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_LONGINT_ID:
          return new LongIntColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_SMALLINT_ID:
          return new SmallIntRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_CHAR_ID:
          CharColumnRoutingObjectInfo ccroi = new CharColumnRoutingObjectInfo(
              RoutingObjectInfo.CONSTANT, value, spi);
          ccroi.setMaxWidth(maximumWidth);
          return ccroi;

        case StoredFormatIds.SQL_DECIMAL_ID:
          return new DecimalColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_REAL_ID:
          return new RealColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_DOUBLE_ID:
          return new DoubleColumnRoutingObjectInfo(RoutingObjectInfo.CONSTANT,
              value, spi);

        case StoredFormatIds.SQL_VARCHAR_ID:
          VarCharColumnRoutingObjectInfo vccroi = new VarCharColumnRoutingObjectInfo(
              RoutingObjectInfo.CONSTANT, value, spi);
          vccroi.setMaxWidth(maximumWidth);
          return vccroi;

        default:
          return null;
      }

    }
    else {

      switch (type) {
        case StoredFormatIds.SQL_INTEGER_ID:
          return new IntColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER,
              paramNumber, spi);

        case StoredFormatIds.SQL_LONGINT_ID:
          return new LongIntColumnRoutingObjectInfo(
              RoutingObjectInfo.PARAMETER, paramNumber, spi);

        case StoredFormatIds.SQL_SMALLINT_ID:
          return new SmallIntRoutingObjectInfo(RoutingObjectInfo.PARAMETER,
              paramNumber, spi);

        case StoredFormatIds.SQL_CHAR_ID:
          // return new CharColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER,
          // paramNumber, spi);
          CharColumnRoutingObjectInfo ccroi = new CharColumnRoutingObjectInfo(
              RoutingObjectInfo.PARAMETER, paramNumber, spi);
          ccroi.setMaxWidth(maximumWidth);
          return ccroi;

        case StoredFormatIds.SQL_DECIMAL_ID:
          return new DecimalColumnRoutingObjectInfo(
              RoutingObjectInfo.PARAMETER, paramNumber, spi);

        case StoredFormatIds.SQL_REAL_ID:
          return new RealColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER,
              paramNumber, spi);

        case StoredFormatIds.SQL_DOUBLE_ID:
          return new DoubleColumnRoutingObjectInfo(RoutingObjectInfo.PARAMETER,
              paramNumber, spi);

        case StoredFormatIds.SQL_VARCHAR_ID:
          VarCharColumnRoutingObjectInfo vccroi = new VarCharColumnRoutingObjectInfo(
              RoutingObjectInfo.PARAMETER, paramNumber, spi);
          vccroi.setMaxWidth(maximumWidth);
          return vccroi;
      }
    }
    return null;
  }
  
  private static int reflectOperator(int op) {
    switch (op) {
    case RelationalOperator.EQUALS_RELOP:
      return RelationalOperator.EQUALS_RELOP;
    case RelationalOperator.NOT_EQUALS_RELOP:
      return RelationalOperator.NOT_EQUALS_RELOP;
    case RelationalOperator.LESS_THAN_RELOP:
      return RelationalOperator.GREATER_THAN_RELOP;
    case RelationalOperator.GREATER_EQUALS_RELOP:
      return RelationalOperator.LESS_EQUALS_RELOP;
    case RelationalOperator.LESS_EQUALS_RELOP:
      return RelationalOperator.GREATER_EQUALS_RELOP;
    case RelationalOperator.GREATER_THAN_RELOP:
      return RelationalOperator.LESS_THAN_RELOP;
    default:
      return op;
    }
  }

  @Override
  String getUniqueColumnName() {

    if (this.leftOperand instanceof AbstractColumnQueryInfo) {
      AbstractColumnQueryInfo acqi = (AbstractColumnQueryInfo)this.leftOperand;
      return generateUniqueColumnName(this.leftOperand.getSchemaName(),this.leftOperand.getTableName(),acqi.getActualColumnName());
    }
    else {
      return null;
    }
  }

  @Override
  boolean isStaticallyNotGetConvertible() {
    return this.getActualColumnPostionOfOperand() == -1
        || this.opType != RelationalOperator.EQUALS_RELOP;
  }
  
  /**
   * For a ComparisonQueryInfo it will be a valid equi join condition if both the operands
   * are on columns of different tables with the underlying regions as PR & both the columns are
   * used in the partitioning scheme of their respective resolvers.
   * [sumedh] Also added the cases when a table is a replicated table since
   * its column can be used for tying two PR, or a non-partitioning column
   * of the same table can also be used for tying two partitioning columns.
   * @return true if a valid equi join condition
   */
  @Override  
  boolean  isEquiJoinCondition() {
    //For it to be an equijoin  condition, the criteria will be
   // 1) Common master which we do not have to worry here as it is checked in the
    // very begining in the SelectQueryInfo
    //2) operator should be equality
    //3) Both the operands should be ColumnQueryInfo and should be part of  partitioning scheme
    //4) They should belong to different tables
    // [sumedh] also check for replicated tables since they can be used
    // to tie two PR columns even though they themselves will not require any
    // colocation check; similarly a partitioning or non-partitioning column
    // or a constant can be used as tying condition
    if (this.opType == RelationalOperator.EQUALS_RELOP
        && this.leftOperand instanceof ColumnQueryInfo
        && (this.rightOperand instanceof ColumnQueryInfo
            || this.rightOperand instanceof ValueQueryInfo)) {
      return true;
    }
    return false;
  }

  String getRegionName() {    
    return this.leftOperand.getRegion().getFullPath();
  }

  @Override
  void seedColocationMatrixData(int rows, ArrayList<TableQueryInfo> tables,
      int numPRTables) {
    this.colocationMatrixPRTableCount = numPRTables;
    if(this.isEquiJoinCondition()) {
      this.colocationMatrixTables = tables;
      this.colocationMatrixRows = rows;      
    }
  }

  /**
   * The colocation matric is a 2 dimensional boolean array. The number of colums correspond to the
   * number of colocated PR tables in the query while the number of rows correspond to the number of
   * partitioning columns. For a colocated equi join query to execute successfully via scatter / gather
   * mechanism, if there are n colocated PR tables in query, there should be n-1 equi join condtions.
   * If the number of partitioning columns is more than one, then there should be such n-1 equi join condtions
   * involving each of the partitioning columns. So if the number of partitioning columns is m & the number of
   * colocated PR tables is n, the colocation matrix should be of size  m * n.
   * 
   * If the condition is a valid equi join condition then it will update the matrix at respective indexes as true.
   * 
   * @param colocationMatrix a two dimensional boolean array.
   
  void updateColocationMatrix(ColocationCriteria colocationMatrix) {    
    ColumnQueryInfo l = (ColumnQueryInfo)this.leftOperand;
    ColumnQueryInfo r = (ColumnQueryInfo)this.rightOperand;
    colocationMatrix[l.getPartitionColumnPosition()][l.getTableNumber()] = true;
    colocationMatrix[r.getPartitionColumnPosition()][r.getTableNumber()] = true;    
  }*/
  
  int getColocationMatrixRowCount() {
    return this.colocationMatrixRows;
  }

  ArrayList<TableQueryInfo> getColocationMatrixTables() {
    return this.colocationMatrixTables;
  }

  int getColocationMatrixPRTableCount() {
    return this.colocationMatrixPRTableCount;
  }

  /*
   * The existing ComparisonQueryInfo needs to be merged with the operand which
   * will be a ComparisonQueryInfo. The cases possible are one operand
   * annihilating the other, they coexist to form a RangeQueryInfo , both exist
   * but are diverging, in which case either a ConstantConditionsWrapperQuerInfo
   * gets created or null is returned
   * 
   */
  @Override
  AbstractConditionQueryInfo createOrAddToGroup(
      AbstractConditionQueryInfo operand,
      boolean createConstantConditionsWrapper, Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
    if (activation == null && (this.isWhereClauseDynamic() || operand.isWhereClauseDynamic())) {
      return new ParameterizedConditionsWrapperQueryInfo(this, operand);
    }
    else {
      // both the operands are of type constant.
      // So following are the possibilities
      // 1) one of them survives
      // 2) RangeQueryInfo is formed
      // 3 ConstantConditionsQueryInfo is formed
      return createSingleOrRangeQueryInfo(this, this.opType,
          (ComparisonQueryInfo)operand, ((ComparisonQueryInfo)operand).opType,
          activation, createConstantConditionsWrapper /*
                                                 * if merge not possible should
                                                 * return null or constant
                                                 * conditions wrapper
                                                 */, forSingleHopPreparePhase);

    }
  }

  private static AbstractConditionQueryInfo createSingleOrRangeQueryInfo(
      ComparisonQueryInfo cond1, int op1, ComparisonQueryInfo cond2, int op2,
      Activation activation, boolean createConstantConditionsWrapper, boolean forSingleHopPreparePhase)
      throws StandardException {
    // TODO: KN for now forSingleHop consider this case as non prunable
    if (forSingleHopPreparePhase) {
      return null;
    }
    // The cases can be bounded, one being made redundant because of other , or
    // both existing because of divergence
    DataValueDescriptor const1 = ((ValueQueryInfo)cond1.rightOperand).evaluateToGetDataValueDescriptor(activation);
    DataValueDescriptor const2 ;
    AbstractConditionQueryInfo retVal;
    switch (op2) {
    case RelationalOperator.GREATER_EQUALS_RELOP:
    case RelationalOperator.GREATER_THAN_RELOP:
      const2 = ((ValueQueryInfo)cond2.rightOperand).evaluateToGetDataValueDescriptor(activation) ;
      if (op1 == RelationalOperator.GREATER_EQUALS_RELOP
          || op1 == RelationalOperator.GREATER_THAN_RELOP) {
        // Only one of the condition will survive
        retVal = isConditionSatisfied(const1, const2, op2) ? cond1 : cond2;
      }
      else {
        boolean isRangeBound = checkForRangeBoundedNess(const1, op1, const2,
            op2);
        // if isRangeBound false it implies the conditions cannot be merged &
        // both will survive
        retVal = isRangeBound ? new RangeQueryInfo(const2, op2, const1, op1,
            ((ColumnQueryInfo)cond1.leftOperand).getActualColumnName(),
            ((ColumnQueryInfo)cond1.leftOperand).getRegion())
            : createConstantConditionsWrapper ? new ConstantConditionsWrapperQueryInfo(
                cond1, cond2)
                : null;
      }
      break;
    case RelationalOperator.LESS_EQUALS_RELOP:
    case RelationalOperator.LESS_THAN_RELOP:
      const2 = ((ValueQueryInfo)cond2.rightOperand).evaluateToGetDataValueDescriptor(activation) ;
      if (op1 == RelationalOperator.LESS_EQUALS_RELOP
          || op1 == RelationalOperator.LESS_THAN_RELOP) {
        // Only one of the condition will survive
        retVal = isConditionSatisfied(const1, const2, op2) ? cond1 : cond2;
      }
      else {
        boolean isRangeBound = checkForRangeBoundedNess(const2, op2, const1,
            op1);
        // if isRangeBoudn false it implies the conditions cannot be merged &
        // both will survive
        retVal = isRangeBound ? new RangeQueryInfo(const1, op1, const2, op2,
            ((ColumnQueryInfo)cond1.leftOperand).getActualColumnName(),
            ((ColumnQueryInfo)cond1.leftOperand).getRegion())
            : createConstantConditionsWrapper ? new ConstantConditionsWrapperQueryInfo(
                cond1, cond2)
                : null;
      }
      break;
    case RelationalOperator.EQUALS_RELOP:
    case RelationalOperator.NOT_EQUALS_RELOP:
      // both conditions survive
      retVal = createConstantConditionsWrapper ? new ConstantConditionsWrapperQueryInfo(
          cond1, cond2)
          : null;
      break;
    default:
      throw new IllegalArgumentException("operator type undefined");

    }

    return retVal;

  }

  // TODO:Asif: Not equal equal to conditions which can cause break in range is
  // not being considered for a while
  /**
   * Checks if the Range junction containing less & greater type of inequalities
   * has a lower and upper bound , in the sense that they do not represent a
   * mutually exclusive condition like a> 10 and a <9 etc.
   * 
   * @param lessCondnKey
   *          Key of the 'Less' condition operand
   * @param lessOperator
   *          Type of 'less' operator ( < or <=)
   * @param greaterCondnKey
   *          Key of the 'greater' condition operand
   * @param greaterCondnOp
   *          Type of 'greater' operator ( > or >=)
   * @return boolean true if the nature is bounded else false ( unbounded )
   * @throws TypeMismatchException
   */
  private static boolean checkForRangeBoundedNess(Object lessCondnKey,
      int lessOperator, Object greaterCondnKey, int greaterCondnOp)
      throws StandardException {
    // First check if the range is bounded or (unbounded and mutually
    // exclusive).
    // If it is unbounded immediately return a false

    return (isConditionSatisfied(greaterCondnKey, lessCondnKey, lessOperator) && isConditionSatisfied(
        lessCondnKey, greaterCondnKey, greaterCondnOp));

  }
  
  @Override
  public String toString() {
    StringBuilder sbuff = new StringBuilder();
    sbuff.append("left operand = " );
    sbuff.append(this.leftOperand.toString());
    sbuff.append("; right operand = " );
    sbuff.append(this.rightOperand.toString());
    sbuff.append("; operator = " );
    sbuff.append(this.opType);    
    return sbuff.toString();
  }
  
  /**
   * For ComparisonQueryInfo it will return as true iff the query contains two
   * PR regions which are colocated & have a single equijoin condition on the
   * partitioning column. This method will be invoked only if the where clause
   * contains a single condition which happens to be an equi join contion. In
   * all the other cases, the method of JunctionQueryInfo will be called.
   * 
   * @return true if the colocated equi join criteria for a given query is
   *         fullfilled, else false
   */
  @Override
  public String isEquiJoinColocationCriteriaFullfilled(TableQueryInfo ncjTqi) {
    // If the colocation matrix is not of tye 1 row * 2 column, the equijoin
    // criteria will not be satisfied. If it is then the next check will be that
    // current condition should also satisfy the equi join criteria
    // [sumedh] now there are various possible combinations like one column
    // of replicated table, both columns of replicated tables etc. so just
    // use the ColocationCriteria class which will handle all such cases
    if (this.colocationMatrixPRTableCount > 1) {
      if (this.isEquiJoinCondition()) {
        ColocationCriteria colocationCheck = new ColocationCriteria(
            this.colocationMatrixRows, this.colocationMatrixTables);
        colocationCheck.updateColocationCriteria(this);
        return colocationCheck
            .isEquiJoinColocationCriteriaFullfilled(ncjTqi);
      }
      else {
        return "non equijoin attempted on more than one partitioned tables";
      }
    }
    return null;
  }

  TableDescriptor getTableDescriptor() {
    assert this.leftOperand instanceof AbstractColumnQueryInfo;
    return ((AbstractColumnQueryInfo)this.leftOperand).getTableDescriptor();
    
  }

  @Override
  public boolean isTableVTI() {
    assert this.leftOperand instanceof AbstractQueryInfo;
    return ((AbstractQueryInfo)this.leftOperand).isTableVTI();
  }

  @Override
  public boolean routeQueryToAllNodes() {
    assert this.leftOperand instanceof AbstractQueryInfo;
    return ((AbstractQueryInfo)this.leftOperand).routeQueryToAllNodes();
  }

  public void setRunTimePrunerToNull() {
    this.runTimePruner = null;
  }
}