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


import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalRowLocation;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.RelationalOperator;
import com.pivotal.gemfirexd.internal.shared.common.ColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.MultiColumnRoutingObjectInfo;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * This class contains a flat structure of all the ComparisonQueryInfo Objects
 * and at the max, one JunctionQueryInfo . A presence of JunctionQueryInfo as an
 * operand present in this class, necessarily means that the child
 * JunctionQueryInfo is of type opposite to this JunctionQueryInfo. A
 * JunctionQueryInfo is formed if there exist more than one conditions in the
 * where clause
 * 
 * 
 * @author Asif
 * 
 */
public class AndJunctionQueryInfo extends JunctionQueryInfo {

  //Stores mostly inequality conditions, equality conditions only in exceptional circumstances
  private final Map<String, AbstractConditionQueryInfo> colOperands = new HashMap<String, AbstractConditionQueryInfo>();

  //Map containing key as the region name & value as Map containing unique column name & value as the condition
  private final Map<String, Map<String,ComparisonQueryInfo>> equalityConditions = new HashMap<String, Map<String,ComparisonQueryInfo>>(3);
  //TODO:Asif: Is there any point in storing non column operands? They will not be 
  //participating in node prunning. For the time being ignore storing them to avoid
  //complexity
  //private final List<AbstractConditionQueryInfo> nonColumnOperands = new ArrayList<AbstractConditionQueryInfo>();
  private QueryInfo runTimePruner = null;

  AndJunctionQueryInfo(AbstractConditionQueryInfo op1,
      AbstractConditionQueryInfo op2) throws StandardException {    
    int pos1 = op1.getActualColumnPostionOfOperand();
    int pos2 = op2.getActualColumnPostionOfOperand();     
    if (pos1 < pos2) {
      this.addNonJunctionOperandToTheStructuresInJunction(op1);
      this.addNonJunctionOperandToTheStructuresInJunction(op2);
    }
    else {
      this.addNonJunctionOperandToTheStructuresInJunction(op2);
      this.addNonJunctionOperandToTheStructuresInJunction(op1);
    }    
  }

  AndJunctionQueryInfo(int junctionType, JunctionQueryInfo jqi,
      AbstractConditionQueryInfo op1) throws StandardException {   
    
    super(jqi);
    if(op1 != null) {
      this.addNonJunctionOperandToTheStructuresInJunction(op1);
    }

  }
  
  AndJunctionQueryInfo( JunctionQueryInfo jqi1,
      JunctionQueryInfo jqi2) { 
    super(jqi1,jqi2);
  }

  private void addNonJunctionOperandToTheStructuresInJunction(
      AbstractConditionQueryInfo operand) throws StandardException {
    // Asif: Check the type of operand. If it has a unique name
    // see if it can form a range. If it forms a range static get convertible
    // will
    // be false
    if(!this.isWhereClauseDynamic()) {
      this.setIsDynamic(operand.isWhereClauseDynamic());
    }    
    if( !this.isStaticallyNotGetConvertible()) {
       this.setIsStaticallyNotGetConvertible(operand.isStaticallyNotGetConvertible());
    }    
    //TODO:See if a cleaner way is available
    if( !this.hasINPredicate()) {
      this.setHasINPredicate(operand instanceof InQueryInfo);
    }

    if (operand.getActualColumnPostionOfOperand() != -1) {
      // Get the name of the column
      String uname = operand.getUniqueColumnName();    
      assert uname != null;
      // See if this name already exists. For the tiem being assume that if the
      // uname exists , it cannot be converted into get
      // If the condition is an equality , put in the equality Condition
      // Operands.
      // If it is not, then it cannot be converted into region.get
      boolean isProcessed = false;
      if (operand.getRelationalOperator() == RelationalOperator.EQUALS_RELOP && !operand.isTableVTI()) {
        // TODO:Asif: For the time being assume if same column name is present
        // twice, it is not get convertible ( irrespective of whether it
        // represents
        // identical value i.e a = 7 and a = 7. Also if the name exists more
        // than once
        // then let the recurrence be processed by the inequality handler
        String regionName = ((ComparisonQueryInfo)operand).getRegionName();
        Map<String,ComparisonQueryInfo> unameToCondition = this.equalityConditions.get(regionName);
        if(unameToCondition == null) {
          unameToCondition = new LinkedHashMap<String,ComparisonQueryInfo>();
          unameToCondition.put(uname,(ComparisonQueryInfo)operand);
          this.equalityConditions.put(regionName, unameToCondition);
          isProcessed = true;
          if(!this.isStaticallyNotGetConvertible()) {
            this.setIsStaticallyNotGetConvertible(this.equalityConditions.size() > 1);
          }
        }else if (!unameToCondition.containsKey(uname)) {
          unameToCondition.put(uname, (ComparisonQueryInfo)operand);
          isProcessed = true;
        }        
      }
      
      if (!isProcessed) {
        // If the operand is not of type equality , at this point , we can
        // safely assume
        // that it cannot be converted into region.get
        this.setIsStaticallyNotGetConvertible(true);
        AbstractConditionQueryInfo oldCondition = this.colOperands.get(uname);
        if (oldCondition == null) {
          // The operand could be parameterzied
          this.colOperands.put(uname, operand);
        }
        else {
          AbstractConditionQueryInfo newAcqi = oldCondition.createOrAddToGroup(
              operand, true/*
                             * create ConstantConditionsWrapper if merge not
                             * possible
                             */, null /* Activation object is null during compile time */, false);
          assert newAcqi != null;
          if (newAcqi != oldCondition) {
            this.colOperands.put(uname, newAcqi);
          }

          this.setIsStaticallyNotGetConvertible(true);
        }
      }
    }
    // Ignore for the present
    // this.nonColumnOperands.add(operand);
    // check if the condition is an equi join condition
    if (operand.isEquiJoinCondition()) {
      ComparisonQueryInfo cqi = (ComparisonQueryInfo)operand;
      if (this.colocationCriteria == null) {
        int rowCount = cqi.getColocationMatrixRowCount();
        if (rowCount > 0 && cqi.getColocationMatrixPRTableCount() > 1) {
          this.colocationCriteria = new ColocationCriteria(rowCount, cqi
              .getColocationMatrixTables());
        }
      }
      if (this.colocationCriteria != null) {
        this.colocationCriteria.updateColocationCriteria(cqi);
      }
    }
  }

  // TODO:Asif: For the time being ingore the unrealistic queryies like a = 5
  // and a <7 types which can
  // be converted into region.get , but will not get converted so in the current
  // code
  @Override
  JunctionQueryInfo mergeNonJunctionOperand(AbstractConditionQueryInfo operand,
      int operandJunctionType) throws StandardException {
    if (this.getJunctionType() == operandJunctionType) {
      this.addNonJunctionOperandToTheStructuresInJunction(operand);
      return this;
    }
    else {
      return new OrJunctionQueryInfo(operandJunctionType, this, operand);
    }

  }
  
  /*
   * (non-Javadoc)
   * @see isConvertibleToGet(...)
   */
  @Override
  Object isConvertibleToGetOnLocalIndex(int[][] ikColumns, TableQueryInfo tqi)
      throws StandardException {
    Object retType = null;
    Object[] iks = null;

    if (!this.isStaticallyNotGetConvertible()) {
      assert this.equalityConditions.size() == 1;
      Map<String, ComparisonQueryInfo> unameToCondition = this.equalityConditions
          .values().iterator().next();
      int opsLen = unameToCondition.size();
      if (ikColumns.length == opsLen) {
        AbstractConditionQueryInfo conditions[] = new AbstractConditionQueryInfo[opsLen];
        unameToCondition.values().toArray(conditions);
        if (opsLen > 2) {
          sortOperandInIncreasingColumnPosition(conditions);
        }
        iks = this.isWhereClauseDynamic() || this.hasINPredicate() ? new Object[opsLen]
            : new DataValueDescriptor[opsLen];

        for (int index = 0; index < opsLen; ++index) {
          AbstractConditionQueryInfo aqi = conditions[index];
          Object temp = aqi.isConvertibleToGetOnLocalIndex(ikColumns[index][0],
              tqi);
          if (temp == null) {
            iks = null;
            break;
          }
          else {
            iks[ikColumns[index][1] - 1] = temp;
          }
        }
      }
    }
    if (iks != null) {
      if (this.hasINPredicate()) {
        retType = this.generateCompositeKeysForBulkOp(iks, tqi);
      }
      else {
        if (this.isWhereClauseDynamic()) {
          retType = new CompositeDynamicKey(iks);
        }
        else {
          retType = (DataValueDescriptor[])iks;
        }
      }
    }
    return retType;
  }

  /*
   * (non-Javadoc)
   * @see isConvertibleToGet(...)
   */
  @Override
  Object isConvertibleToGet(int[][] fkColumns, TableQueryInfo tqi)
      throws StandardException {
    Object retType = null;
    Object[] pks = null;

    if (!this.isStaticallyNotGetConvertible()) {
      assert this.equalityConditions.size() == 1;
      Map<String, ComparisonQueryInfo> unameToCondition = this.equalityConditions
          .values().iterator().next();
      int opsLen = unameToCondition.size();
      if (fkColumns.length == opsLen) {
        AbstractConditionQueryInfo conditions[] = new AbstractConditionQueryInfo[opsLen];
        unameToCondition.values().toArray(conditions);
        if (opsLen > 2) {
          sortOperandInIncreasingColumnPosition(conditions);
        }
        pks = this.isWhereClauseDynamic() || this.hasINPredicate() ? new Object[opsLen]
            : new DataValueDescriptor[opsLen];
        for (int index = 0; index < opsLen; ++index) {
          AbstractConditionQueryInfo aqi = conditions[index];
          Object temp = aqi.isConvertibleToGet(fkColumns[index][0], tqi);
          if (temp == null) {
            pks = null;
            break;
          }
          else {
            pks[fkColumns[index][1] - 1] = temp;
          }
        }
      }
    }
    if (pks != null) {
      if (this.hasINPredicate()) {
        retType = this.generateCompositeKeysForBulkOp(pks, tqi);
      }
      else {
        if (this.isWhereClauseDynamic()) {
          retType = new CompositeDynamicKey(pks);
        }
        else {
          final GemFireContainer container = (GemFireContainer)tqi.getRegion()
              .getUserAttribute();
          if (container.isPrimaryKeyBased()) {
            retType = GemFireXDUtils.convertIntoGemfireRegionKey(
                (DataValueDescriptor[])pks, container, false);
          }
        }
      }
    }
    return retType;
  }

  private Object[] generateCompositeKeysForBulkOp(Object[] baseKeys, TableQueryInfo tqi)
      throws StandardException {
    Object[] template = this.isWhereClauseDynamic() ? new Object[baseKeys.length]
        : new DataValueDescriptor[baseKeys.length];
    List primaryKeys = new ArrayList();
    recursiveGeneration(baseKeys, 0, template, primaryKeys,
        false /* start with flag as false for isTupleDynamic*/, tqi);
    return primaryKeys.toArray();
  }

  private void recursiveGeneration(Object[] baseKeys, final int indx,
      Object[] template, List keysHolder, final boolean isTupleDynamic, TableQueryInfo tqi)
      throws StandardException {
    int baseKeysLen = baseKeys.length;
    if (indx == baseKeysLen) {
      Object[] pks = isTupleDynamic ? new Object[baseKeysLen]
          : new DataValueDescriptor[baseKeysLen];

      for (int i = 0; i < baseKeysLen; ++i) {
        pks[i] = template[i];
      }

      if (isTupleDynamic) {
        keysHolder.add(new CompositeDynamicKey(pks));
      }
      else {
        final GemFireContainer container = (GemFireContainer)tqi.getRegion()
            .getUserAttribute();
        keysHolder.add(GemFireXDUtils.convertIntoGemfireRegionKey(
            (DataValueDescriptor[])pks, container, false));
      }
      return;
    }
    Object individualKey = baseKeys[indx];
    if (individualKey instanceof Object[]) {
      Object[] multipleValues = (Object[])individualKey;
      for (int j = 0; j < multipleValues.length; ++j) {
        template[indx] = multipleValues[j];
        this.recursiveGeneration(baseKeys, indx + 1, template, keysHolder,
            isTupleDynamic || multipleValues[j] instanceof DynamicKey, tqi);
      }
    }
    else {
      template[indx] = individualKey;
      this.recursiveGeneration(baseKeys, indx + 1, template, keysHolder,
          isTupleDynamic || individualKey instanceof DynamicKey, tqi);
    }

  }

  private void sortOperandInIncreasingColumnPosition(
      AbstractConditionQueryInfo conditions[]) {
    // The checks before this function is invoked
    // have ensured that all the operands are of type ComparisonQueryInfo
    // and of the form var = constant. Also need for sorting will not arise
    // if there are only two operands
    int len = conditions.length;
    outer: for (int j = 0; j < len; ++j) {
      AbstractConditionQueryInfo toSort = conditions[j];
      int posToSort = toSort.getActualColumnPostionOfOperand();
      inner: for (int i = j - 1; i > -1; --i) {
        AbstractConditionQueryInfo currSorted = conditions[i];
        if (currSorted.getActualColumnPostionOfOperand() < posToSort) {
          // Found the position
          // Pick the next to sort
          if (i + 1 != j) {
            conditions[i + 1] = toSort;
          }
          break inner;
        }
        else {
          // Advance the current sorted to next & create an hole
          conditions[i + 1] = currSorted;
          if (i == 0) {
            // Reached the end just set the toSort at 0
            conditions[0] = toSort;
          }
        }
      }
    }

  }

  /**
   * Test API only
   * 
   * @return Returns a List containing ComparisonQueryInfo & JunctionQueryInfo
   *         Objects in the AND or OR Junction
   * 
   */
  @Override
  List getOperands() {
    //return Collections.unmodifiableList(this.colOperands.values());
    List temp = new ArrayList();
    Iterator<Map<String,ComparisonQueryInfo>> itr = this.equalityConditions.values().iterator();
    while(itr.hasNext()) {
      temp.addAll(itr.next().values());
    }    
    temp.addAll(this.colOperands.values());
    return temp;
  }

  /**
   * Test API
   * 
   * @return int indicating the type of junction ( AND orOR)
   */
  @Override
  int getJunctionType() {
    return QueryInfoConstants.AND_JUNCTION;
  }
  
  private QueryInfo createRuntimeNodesPruner()
  {

    final LogWriter logger = Misc.getCacheLogWriter();

    final QueryInfo[] equalityCondnPruners = new QueryInfo[this.equalityConditions
        .size()];
    final QueryInfo[] nonEqualityCondnPruners = new QueryInfo[this.colOperands
        .size()];
    //  We need to use only column based operands.
    // Ideally it would make sense to call compute nodes
    // only on those columns which have some global index or are partitioning
    // key.
    // Since it is not possible to get handle of Region here , we will for time
    // being
    // iterate over the map
    int i = 0;
    Iterator<Map.Entry<String, Map<String, ComparisonQueryInfo>>> equalityItr = this.equalityConditions
        .entrySet().iterator();
    while (equalityItr.hasNext()) {
      Map.Entry<String, Map<String, ComparisonQueryInfo>> entry = equalityItr
          .next();
      //For a region if the number of conditions is exactly one, no need to go further
      // return the condition as the prunner.
      String regionName = entry.getKey();
      Map<String,ComparisonQueryInfo> condnMap = entry.getValue();
      if(condnMap.size() == 1) {
        equalityCondnPruners[i++] = condnMap.values().iterator().next();
      }else {
        equalityCondnPruners[i++] = this.seggregateEqualityConditions(regionName, condnMap);  
      }
    }
    
    Iterator<Map.Entry<String, AbstractConditionQueryInfo>> itr = this.colOperands
        .entrySet().iterator();
    i = 0;
    while (itr.hasNext()) {
      nonEqualityCondnPruners[i++] = itr.next().getValue();
    }
    
    QueryInfo pruner = new AbstractQueryInfo() {
      @Override
      public void computeNodes(Set<Object> routingKeys, Activation activation,
          boolean forSingleHopPreparePhase) throws StandardException {
        if (forSingleHopPreparePhase) {
          if (nonEqualityCondnPruners != null && !(nonEqualityCondnPruners.length ==0)) {
            routingKeys.clear();
            routingKeys.add(ResolverUtils.TOK_ALL_NODES);
            return;
          }
        }
        Set<Object> nodesCollector = new HashSet<Object>();
        nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
        if (logger.fineEnabled()) {
          logger
              .fine("AndJunctionQueryInfo::computeNodes: Before prunning nodes are"
                  + routingKeys);
        }
        for (QueryInfo qi : equalityCondnPruners) {
          qi.computeNodes(nodesCollector, activation, forSingleHopPreparePhase);
          if (!nodesCollector.contains(ResolverUtils.TOK_ALL_NODES)) {
            if (routingKeys.contains(ResolverUtils.TOK_ALL_NODES)) {
              routingKeys.remove(ResolverUtils.TOK_ALL_NODES);
              routingKeys.addAll(nodesCollector);
            }
            else {
              routingKeys.retainAll(nodesCollector);
            }
            nodesCollector.clear();
            nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
          }

        }
        if(!forSingleHopPreparePhase) {
          assert nodesCollector.size() == 1;
          assert nodesCollector.contains(ResolverUtils.TOK_ALL_NODES);
        }
        else {
          nodesCollector.clear();
          nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
        }
        
        for (QueryInfo qi : nonEqualityCondnPruners) {
          qi.computeNodes(nodesCollector, activation, forSingleHopPreparePhase);
          // The nodesCollector contains members satisfied for
          // an individual condition. Since this is an AndJunction
          // we should retain only those members of the nodes Set, which are
          // also present in the nodesCollector
          // If the nodes collector contains TOK_ALL_NODES ,it implies
          // that no prunning is possible by the condition under
          // consideration & that it represents all the members of the
          // system. And as far as the condition is concerned , the query needs
          // to
          // be sprayed to all members
          if (!nodesCollector.contains(ResolverUtils.TOK_ALL_NODES)) {
            if (routingKeys.contains(ResolverUtils.TOK_ALL_NODES)) {
              routingKeys.remove(ResolverUtils.TOK_ALL_NODES);
              routingKeys.addAll(nodesCollector);
            }
            else {
              routingKeys.retainAll(nodesCollector);
            }
            nodesCollector.clear();
            nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
          }
          /*
           * else { //Ignore. The condition will not cause any prunning }
           */}
        if (!forSingleHopPreparePhase) {
          assert nodesCollector.size() == 1;
          assert nodesCollector.contains(ResolverUtils.TOK_ALL_NODES);
        }
        else {
          nodesCollector.clear();
          nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
        }

        if (children != null) {
          for (int i = 0; i < children.length; ++i) {

            children[i].computeNodes(nodesCollector, activation, forSingleHopPreparePhase);
            // The nodesCollector contains members satisfied for
            // an individual condition. Since this is an AndJunction
            // we should retain only those members of the nodes Set, which are
            // also present in the nodesCollector
            // If the nodes collector contains TOK_ALL_NODES ,it implies
            // that no prunning is possible by the condition under
            // consideration & that it represents all the members of the
            // system. And as far as the condition is concerned , the query
            // needs to
            // be sprayed to all members
            if (!nodesCollector.contains(ResolverUtils.TOK_ALL_NODES)) {
              if (routingKeys.contains(ResolverUtils.TOK_ALL_NODES)) {
                routingKeys.remove(ResolverUtils.TOK_ALL_NODES);
                routingKeys.addAll(nodesCollector);
              }
              else {
                routingKeys.retainAll(nodesCollector);
              }
              nodesCollector.clear();
              nodesCollector.add(ResolverUtils.TOK_ALL_NODES);
            }

          }

        }
        if (logger.fineEnabled()) {
          logger
              .fine("AndJunctionQueryInfo::computeNodes: After prunning nodes are"
                  + routingKeys);
        }

      }

    };
    
    return pruner;
  }
  
  
  
  
  //TODO:Asif: This function will be further organized to move some common
  //functionality to the super class and also if creation of anonymous inner class
  // is the right thing to do
  
  /**
   * Segregates the equality conditions depending upon the column/columns on
   * which the gfxd partition resolver is defined. It returns an Object of type
   * QueryInfo the actual implementation class could be either anonymous inner
   * class extending Abstract QuerInfo , or anonymous class extending
   * ValueListQueryInfo or a ComparisonQueryInfo. The returned object ignores
   * those conditions of those columns on which the resolver is not defined. This
   * may change later, when Global index come into picture. 
   *
   *In case of equality conditions , if a resolver is getting used, we will in any case get only one
   *routing object, so  there is no point in consulting the Global Index because at the max may
   *we may get a routing object whose intersection will result in zero nodes. So it does not give major
   *benefit as there will be cost to consult Global Index which will offset any advantage.
   * 
   * @param regionName
   *          String identifying the region for the table
   * @param unameToCondnMap
   *          Map containing a mapping of column name to ComparisonQueryInfo
   * @return QueryInfo object . In some cases it will be a transient object
   *         created just for computing nodes.
   */
  private QueryInfo seggregateEqualityConditions(String regionName,
      Map<String, ComparisonQueryInfo> unameToCondnMap) {
    QueryInfo retVal = null;
    Region<?, ?> rgn = Misc.getRegionByPath(regionName, true);
    boolean noPruningPossible = true;
    if (rgn.getAttributes().getDataPolicy().withPartitioning()) {
      final PartitionedRegion pr = (PartitionedRegion)rgn;
      // Check if resolver is defined
      final GfxdPartitionResolver spr = GemFireXDUtils.getResolver(pr);

      final LogWriter logger = Misc.getCacheLogWriter();
      if (spr != null) {
        String[] rslvrColNames = spr.getColumnNames();
        if (rslvrColNames.length == 1) {
          // TODO: Atleast till Global indexes are unavailable, just
          // return the condition for the column on which resolver
          // is present. Ignore the other cols.
          String uname = generateUniqueColumnName(spr.getSchemaName(), spr
              .getTableName(), spr.getColumnNames()[0]);
          retVal = unameToCondnMap.get(uname);
          if(retVal == null) {
          //Check if a canonicalized equality expression is available
            String canonicaliedExpr =spr.getCanonicalizedExpression() ; 
            
            if( canonicaliedExpr!= null) {
              uname = generateUniqueColumnName(spr.getSchemaName(), spr
                  .getTableName(), canonicaliedExpr);
              retVal = unameToCondnMap.get(uname);
            }
          }
          noPruningPossible = (retVal == null);
        }
        else if (rslvrColNames.length > 1) {
          // TODO: Asif: with global index in place , this portion
          // of code may be modified to add the conditions on column which may
          // have
          // global index defined
          final ValueQueryInfo[] partitioningConstants = new ValueQueryInfo[rslvrColNames.length];
          int i = 0;
          noPruningPossible = false;
          boolean foundInPredicate = false;
          for (String uname : rslvrColNames) {
            uname = generateUniqueColumnName(spr.getSchemaName(), spr
                .getTableName(), uname);
            AbstractConditionQueryInfo temp = unameToCondnMap.get(uname);
            if (logger.fineEnabled()) {
              logger
                  .fine("AndJunctionQueryInfo::Segregartion:computeNodes: uname = "
                      + uname + " Condition = " + temp);
            }

            if (temp == null) {
              noPruningPossible = true;
              break;
            }
            else {
              foundInPredicate  = foundInPredicate || temp instanceof InQueryInfo;
              partitioningConstants[i++] = (ValueQueryInfo)((ComparisonQueryInfo)temp).rightOperand;
            }
          }
          if (!noPruningPossible) {
            if(foundInPredicate) {
              retVal =this.createHelperForMultiColumnPartitioningWithInCondition(partitioningConstants, spr);
            }else {
              retVal = this.createHelperForMultiColumnPartitioningNoInCondition(partitioningConstants, spr);
            }
          }else {
            //Check for canonicalized expression
            String canonicaliedExpr =spr.getCanonicalizedExpression() ; 
            if( canonicaliedExpr!= null) {
              String uname = generateUniqueColumnName(spr.getSchemaName(), spr
                  .getTableName(), canonicaliedExpr);
              retVal = unameToCondnMap.get(uname);
            }
            noPruningPossible = retVal == null;
          }
        }
      }  

      if (noPruningPossible) {
        // The pruning could not be accomplished by resolver , so we need to find
        // if the pruning is
        // possible by Global Index. If there are multiple global indexes
        // available then we need
        // to select the one with maximum span , i.e out of all the eligible
        // global indexes, the one
        // which has maximum base columns
        // Take the first condition & obtain the table descriptor from the
        // column query info
        ComparisonQueryInfo condn = unameToCondnMap.values().iterator().next();
        List<ConglomerateDescriptor> sortedGlobalIndexes = this
            .sortGlobalIndexesOnBaseColumns(condn.getTableDescriptor());
        // Select the first Global index which satisfies the column positions
        long conglomID = -1;
        ValueQueryInfo[] partitioningConstants = null;
        Iterator<ConglomerateDescriptor> itr = sortedGlobalIndexes.iterator();
        while (itr.hasNext()) {
          ConglomerateDescriptor cd = itr.next();
          int[] baseColPos = cd.getIndexDescriptor().baseColumnPositions();
          partitioningConstants = new ValueQueryInfo[baseColPos.length];
          ComparisonQueryInfo[] condnsMatchingGlobalIndex  = isGlobalIndexApplicable(cd,
              partitioningConstants, unameToCondnMap.values().iterator(),
              baseColPos);
          if (condnsMatchingGlobalIndex != null) {
            if(condnsMatchingGlobalIndex.length == 1) {
              //there is only one condition matching the global index implying a single key
              // global index. Let it take care of itself.
              retVal = condnsMatchingGlobalIndex[0];
              noPruningPossible = false;              
            }else {
              conglomID = cd.getConglomerateNumber();
            }
            
            break;
          }
        }
        if (conglomID != -1) {
          noPruningPossible = false;
          final long indexConglomID = conglomID;
          retVal = new ValueListQueryInfo(partitioningConstants, false) {
            @Override
            public void computeNodes(Set<Object> routingKeys,
                Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
              if (forSingleHopPreparePhase) {
                return;
              }
              assert routingKeys.size() == 1;
              assert routingKeys.contains(ResolverUtils.TOK_ALL_NODES);
              if (logger.fineEnabled()) {
                logger.fine("AndJunctionQueryInfo::computeNodes: "
                    + "Before prunning nodes are" + routingKeys);
              }
              DataValueDescriptor indexKey[] = this
                  .evaluateToGetDataValueDescriptorArray(activation);
              TransactionController tc = activation.getTransactionController();
              ScanController scan = tc.openScan(
                  indexConglomID,
                  false, // hold
                  0, // open read only
                  TransactionController.MODE_TABLE,
                  TransactionController.ISOLATION_SERIALIZABLE,
                  (FormatableBitSet)null, indexKey, // start key value
                  ScanController.GE, // start operator
                  null, // qualifier
                  indexKey, // stop key value
                  ScanController.GE, null);
              if (scan.next()) {
                // Remove TOKEN_ALL_NODES
                routingKeys.clear();
                final DataValueDescriptor[] fetchKey =
                  new DataValueDescriptor[indexKey.length + 1];
                for (int i = 0; i < indexKey.length; ++i) {
                  fetchKey[i] = indexKey[i];
                }

                while (scan.next()) {
                  GlobalRowLocation grl = new GlobalRowLocation();
                  fetchKey[indexKey.length] = grl;
                  scan.fetch(fetchKey);
                  Object routingObject = grl.getRoutingObject();
                  if (logger.fineEnabled()) {
                    logger.fine("AndJunctionQueryInfo::computeNodes:"
                        + "pruneUsingGlobalIndex: Scan of Global index "
                        + "found a row = " + grl);
                    logger.fine("AndJunctionQueryInfo::computeNodes:"
                        + "pruneUsingGlobalIndex: Scan of Global index "
                        + "found routing object = " + routingObject);
                  }

                  assert routingObject != null;
                  routingKeys.add(routingObject);
                }
              }
              else {
                if (logger.fineEnabled()) {
                  logger.fine("AndJunctionQueryInfo::computeNodes:"
                      + "pruneUsingGlobalIndex: Scan of Global index "
                      + "found NO row");
                }
                // The row has not been inserted yet. Clear the map
                routingKeys.clear();
              }
              scan.close();
            }
          };
        }
      }     
    }

    if (noPruningPossible) {
      //Now we need to find out of nodes pruning is possible
      retVal = QueryInfoConstants.NON_PRUNABLE;
    }
    return retVal;
  }  

  private QueryInfo createHelperForMultiColumnPartitioningWithInCondition(
      final ValueQueryInfo[] partitioningConstants,
      final GfxdPartitionResolver spr) {
    final LogWriter logger = Misc.getCacheLogWriter();
    QueryInfo retVal = new AbstractQueryInfo() {
      @Override
      public void computeNodes(Set<Object> routingKeys, Activation activation, boolean forSingleHopPreparePhase)
          throws StandardException
      {
        assert routingKeys.size() == 1;
        assert routingKeys.contains(ResolverUtils.TOK_ALL_NODES);
        if (logger.fineEnabled()) {
          logger
              .fine("AndJunctionQueryInfo::computeNodes: Before prunning nodes are"
                  + routingKeys);
        }
        // Create an Empty ArrayList which will hold all the combinations of
        // routing columns
        if (!forSingleHopPreparePhase) {
        List<DataValueDescriptor[]> collector = new ArrayList<DataValueDescriptor[]>(5);
        DataValueDescriptor[] holder = new DataValueDescriptor[partitioningConstants.length];
        this.expand(partitioningConstants, 0, collector, holder, activation);
        Iterator<DataValueDescriptor[]> partitionValsItr = collector.iterator();
        while (partitionValsItr.hasNext()) {
          Object routingObject = spr
              .getRoutingObjectsForPartitioningColumns(partitionValsItr.next());
          assert routingObject != null;
          if (routingKeys.contains(ResolverUtils.TOK_ALL_NODES)) {
            routingKeys.clear();
          }
          routingKeys.add(routingObject);
        }
        }
        else {
          // TODO: KN this is a perfect case of hoppable query but not supporting it for
          // 1.0.2 // see test 
          routingKeys.clear();
          routingKeys.add(ResolverUtils.TOK_ALL_NODES);
          return;
        }
      }

      private void expand(ValueQueryInfo[] partitionConstants, int col,
          List<DataValueDescriptor[]> collector, DataValueDescriptor[] holder, Activation activation)
          throws StandardException
      {
        ValueQueryInfo vqi = partitionConstants[col];
        if (vqi instanceof ValueListQueryInfo) {
          ValueListQueryInfo vlqi = (ValueListQueryInfo)vqi;
          ValueQueryInfo[] vqiArr = vlqi.getOperands();
          for (int i = 0; i < vqiArr.length; ++i) {
            holder[col] = vqiArr[i].evaluateToGetDataValueDescriptor(activation);
            if (col + 1 == partitionConstants.length) {
              DataValueDescriptor[] values = new DataValueDescriptor[col + 1];
              int j = 0;
              for (DataValueDescriptor obj : holder) {
                values[j++] = obj;
              }
              collector.add(values);
            }
            else {
              this.expand(partitionConstants, col + 1, collector, holder,
                  activation);
            }
          }
        }
        else {
          holder[col] = vqi.evaluateToGetDataValueDescriptor(activation);
          if (col + 1 == partitionConstants.length) {
            DataValueDescriptor[] values = new DataValueDescriptor[col + 1];
            int j = 0;
            for (DataValueDescriptor obj : holder) {
              values[j++] = obj;
            }
            collector.add(values);
          }
          else {
            this.expand(partitionConstants, col + 1, collector, holder,
                activation);
          }
        }
      }

    };
    return retVal;
  }
  
  
  private QueryInfo createHelperForMultiColumnPartitioningNoInCondition(final ValueQueryInfo[] partitioningConstants, final  GfxdPartitionResolver spr) {
    final LogWriter logger = Misc.getCacheLogWriter();
    QueryInfo retVal  = new ValueListQueryInfo(partitioningConstants, false) {
      @Override
      public void computeNodes(Set<Object> routingKeys,
          Activation activation, boolean forSingleHopPreparePhase) throws StandardException {
        assert routingKeys.size() == 1;
        assert routingKeys.contains(ResolverUtils.TOK_ALL_NODES);
        if (logger.fineEnabled()) {
          logger
              .fine("AndJunctionQueryInfo::computeNodes: Before prunning nodes are"
                  + routingKeys);
        }
        if (!forSingleHopPreparePhase) {
        Object routingObject = spr
            .getRoutingObjectsForPartitioningColumns(this
                .evaluateToGetDataValueDescriptorArray(activation));
         assert routingObject != null;
         routingKeys.clear();
         routingKeys.add(routingObject);
        }
        else {
          ColumnRoutingObjectInfo[] crinfo = this.evaluateToGetColumnInfos(activation);
          MultiColumnRoutingObjectInfo mcroinfo = new MultiColumnRoutingObjectInfo(crinfo);
          routingKeys.clear();
          routingKeys.add(mcroinfo);
        }
       
      }
    };
    return retVal;
  }
  private List<ConglomerateDescriptor> sortGlobalIndexesOnBaseColumns(
      TableDescriptor td) {

    ConglomerateDescriptorList cdl = td.getConglomerateDescriptorList();
    Iterator itr = cdl.iterator();

    List<ConglomerateDescriptor> sortedIndexes = new LinkedList<ConglomerateDescriptor>();
    while (itr.hasNext()) {
      ConglomerateDescriptor cd = (ConglomerateDescriptor)itr.next();
      if (cd.isIndex() || cd.isConstraint()) {
        IndexDescriptor id = cd.getIndexDescriptor();
        if (id.indexType().equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
          int[] baseColPos = id.baseColumnPositions();
          ListIterator<ConglomerateDescriptor> li = sortedIndexes
              .listIterator();
          while (li.hasNext()) {
            ConglomerateDescriptor currSorted = li.next();
            if (baseColPos.length > currSorted.getIndexDescriptor()
                .baseColumnPositions().length) {
              break;
            }
          }
          li.add(cd);
        }
      }
    }
    return sortedIndexes;

  }

  /**
   * Finds out the condition or group of conditions whose column/columns completely match the
   * key/keys of the GlobalIndex. If the conditions do not completely define the global index
   * null is returned
   * @param cd
   * @param partitioningConstants
   * @param condnsToCheck
   * @param baseColsPos
   * @return ComparisonQueryInfo[]
   */
  ComparisonQueryInfo[] isGlobalIndexApplicable(ConglomerateDescriptor cd,
      final ValueQueryInfo[] partitioningConstants,
      Iterator<ComparisonQueryInfo> condnsToCheck, final int[] baseColsPos) {
    ComparisonQueryInfo[] condnsConstitutingGlobalIndex = new ComparisonQueryInfo[baseColsPos.length];
    Set<Integer> colPosSatisfied = new HashSet<Integer>(baseColsPos.length);
    while (condnsToCheck.hasNext()) {
      ComparisonQueryInfo condn = condnsToCheck.next();
      int indexPos = condn.isPartOfGlobalIndex(baseColsPos);
      if (indexPos != -1) {
        colPosSatisfied.add(Integer.valueOf(indexPos));
        partitioningConstants[indexPos] = (ValueQueryInfo)(condn).rightOperand;
        condnsConstitutingGlobalIndex[indexPos] = condn;
      }
    }
    if(colPosSatisfied.size() != baseColsPos.length) {
        condnsConstitutingGlobalIndex = null;
    }
    return condnsConstitutingGlobalIndex;
     

  }
  
  @Override
  public String isEquiJoinColocationCriteriaFullfilled(TableQueryInfo ncjTqi) {
    if (this.colocationCriteria != null) {
      return this.colocationCriteria
          .isEquiJoinColocationCriteriaFullfilled(ncjTqi);
    }
    else if (this.colocationFailReason != null) {
      return this.colocationFailReason;
    }
    return "no colocation criteria could be determined";
  }
  
  
  /**
   * Test API only
   * @return Map
   */
  Map getEqualityConditionsMap() {
    return Collections.unmodifiableMap(this.equalityConditions);
  }
  
  /**
   * Test API only
   * @return Map
   */
  Map getInEqualityConditionsMap() {
    return Collections.unmodifiableMap(this.colOperands);
  }

  @Override
  QueryInfo getRuntimeNodesPruner(boolean forSingleHopPreparePhase)
  {
    if(this.runTimePruner == null) {
      this.runTimePruner = createRuntimeNodesPruner();
    }
    return this.runTimePruner;
  }
  
}
