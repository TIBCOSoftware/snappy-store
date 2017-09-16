/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.impl.sql.rules;

import java.util.LinkedList;

import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CostEstimate;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DMLStatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultSetNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule.ExecutionEngine;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import org.apache.commons.lang.exception.ExceptionUtils;

public class ExecutionEngineArbiter {

  LinkedList<ExecutionEngineRule> executionEngineRules = new LinkedList<>();

  private static Boolean  enableRoutingArbitor = Boolean.parseBoolean(
      PropertyUtil.getSystemProperty(
  GfxdConstants.GFXD_ROUTE_SELECTED_STORE_QUERIES_TO_SPARK , "true"));

  //public static final int DEFAULT_COST_BASED_OPTIMIZED_ROUTING_THRESHOLD = 1000000;

  //public static int testHookCostThresHold = DEFAULT_COST_BASED_OPTIMIZED_ROUTING_THRESHOLD;

  //private static int costBasedOptimizationThreshold = PropertyUtil.
  //    getSystemInt(GfxdConstants.GFXD_COST_OPTIMIZED_ROUTING_THRESHOLD,
  //        DEFAULT_COST_BASED_OPTIMIZED_ROUTING_THRESHOLD);

  public ExecutionEngineArbiter() {
    //Rules That needs to be applied regardless of the GFXD_ROUTE_SELECTED_STORE_QUERIES_TO_SPARK flag
    // mostly for spark queries.
    executionEngineRules.add(new ColumnTableExecutionEngineRule());

    //Rules that applies  on the store Queries
    if (enableRoutingArbitor) {
      executionEngineRules.add(new ReplicatedTableExecutionEngineRule());
      executionEngineRules.add(new AnyOneOfExecutionEngineRule());
    }
  }

  // These rules are applied recursively for each queryInfo
  // and subQueryInfo
  public ExecutionEngine getExecutionEngine(DMLQueryInfo qInfo) {
     ExecutionRuleContext context = new ExecutionRuleContext(ExecutionEngine.NOT_DECIDED);
    for (ExecutionEngineRule rule : executionEngineRules) {
      ExecutionEngine engine = rule.getExecutionEngine(qInfo, context);
      if (engine != ExecutionEngine.NOT_DECIDED) {
        return engine;
      }
    }
    return  ExecutionEngine.STORE;
  }

/*  public ExecutionEngine getExecutionEngine(StatementNode qt, GenericStatement gs, boolean routeQuery)
      throws StandardException {
    if (enableRoutingArbitor) {
      if (qt instanceof DMLStatementNode) {
        ResultSetNode resultSetNode = ((DMLStatementNode)qt).getResultSetNode();
        if (resultSetNode != null) {
          CostEstimate fcs = resultSetNode.getFinalCostEstimate();
          if (fcs != null) {
            if (fcs.getEstimatedCost() > costBasedOptimizationThreshold
                || fcs.getEstimatedCost() > testHookCostThresHold) {
              return ExecutionEngine.SPARK;
            }
          }
        }
      }
    }
    return ExecutionEngine.STORE;
  }*/

  //public static void setTestHookCostThreshold(int threshold){
  //  testHookCostThresHold = threshold;
  //}
}