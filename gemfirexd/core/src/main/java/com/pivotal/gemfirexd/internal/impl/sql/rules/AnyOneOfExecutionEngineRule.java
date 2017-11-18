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

import java.util.List;

import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.TableQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

class AnyOneOfExecutionEngineRule extends ExecutionEngineRule {

  @Override
  protected ExecutionEngine findExecutionEngine(DMLQueryInfo qInfo, ExecutionRuleContext context) {

    //check for distinct and special case of outer join
    if (qInfo.isQuery(QueryInfo.HAS_DISTINCT, QueryInfo.HAS_DISTINCT_SCAN)
        || qInfo.isOuterJoinSpecialCase()) {
      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "AnyOneOfExecutionEngineRule:DISTINCT_QUERY_RULE:SPARK");
      }
      return ExecutionEngine.SPARK;
    }

    if ((qInfo.hasUnionNode() || qInfo.hasIntersectOrExceptNode())) {
      if (GemFireXDUtils.TraceExecution) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
            "AnyOneOfExecutionEngineRule:UNION_OR_INTERSECT_QUERY_RULE:SPARK");
      }
      return ExecutionEngine.SPARK;
    }

    //check for the "group by" queries
    if (qInfo.isQuery(QueryInfo.HAS_GROUPBY)) {
      // it is a group by query . need to check if it has indexes in the where clause.
      if (qInfo.getPrimaryKey() == null && qInfo.getLocalIndexKey() == null) {
        if (GemFireXDUtils.TraceExecution) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "AnyOneOfExecutionEngineRule:GROUPBY_QUERY_RULE:SPARK");
        }
        return ExecutionEngine.SPARK;
      }
    }

    if (qInfo.isSelect()) {
      if (!qInfo.isPrimaryKeyBased() && !qInfo.isGetAllOnLocalIndex()) {
        if (GemFireXDUtils.TraceExecution) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "AnyOneOfExecutionEngineRule:PRIMARY_KEY_BASED_RULE:STORE");
        }
        return ExecutionEngine.SPARK;
      }
    }

    // If more than one table are involved then such queries also should be routed
    // for better performance.
    List<TableQueryInfo> tqis = qInfo.getTableQueryInfoList();
    if (tqis != null && tqis.size() > 1) {
      return ExecutionEngine.SPARK;
    }
    return ExecutionEngine.NOT_DECIDED;
  }
}

