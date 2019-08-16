/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;


abstract public class ExecutionEngineRule {

 public enum ExecutionEngine {
    SPARK,
    STORE,
    NOT_DECIDED
  }

  public ExecutionEngine getExecutionEngine(DMLQueryInfo qInfo, ExecutionRuleContext context ) {

    context.engine = findExecutionEngine(qInfo, context);

    if (context.engine == ExecutionEngine.NOT_DECIDED) {
      List<SubQueryInfo> subqueries = qInfo.getSubqueryInfoList();
      if (subqueries.size() > 0) {
        for (DMLQueryInfo subquery : subqueries) {
          getExecutionEngine(subquery , context);
        }
      }
    }

    return context.engine;
  }

  abstract protected ExecutionEngine findExecutionEngine(DMLQueryInfo qInfo, ExecutionRuleContext context);

}
