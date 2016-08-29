/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

class AnyOneOfExecutionEngineRule extends ExecutionEngineRule {
  private enum RuleName {
    DISTINCT_QUERY_RULE,
    GROUPBY_QUERY_RULE,
    UNION_OR_INTERSECT_QUERY_RULE,
    None
  }

  RuleName rule = RuleName.None;

  @Override
  protected boolean findExecutionEngine(DMLQueryInfo qInfo) {

    //check for distinct and special case of outer join
    if (rule == RuleName.None &&
        qInfo.isQuery(QueryInfo.HAS_DISTINCT, QueryInfo.HAS_DISTINCT_SCAN)
        || qInfo.isOuterJoinSpecialCase()) {
      rule = RuleName.DISTINCT_QUERY_RULE;
    }

    //check for the "group by" queries
    if (rule == RuleName.None && qInfo.isQuery(QueryInfo.HAS_GROUPBY)) {
      // it is a group by query . need to check if it has indexes in the where clause.
      if (qInfo.getPrimaryKey() == null && qInfo.getLocalIndexKey() == null) {
        rule = RuleName.GROUPBY_QUERY_RULE;
      }
    }


    if (rule == RuleName.None &&
        (qInfo.hasUnionNode() || qInfo.hasIntersectOrExceptNode())) {
      rule = RuleName.GROUPBY_QUERY_RULE;
    }


    return (rule == RuleName.None ? false : true);
  }

  @Override
  public ExecutionEngine getExecutionEngine() {
    if (rule == RuleName.None) {
      return ExecutionEngine.NOT_DECIDED;
    }

    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
        "AnyOneOfExecutionEngineRule:" + rule.name() + ":SPARK");
    return ExecutionEngine.SPARK;
  }
}

