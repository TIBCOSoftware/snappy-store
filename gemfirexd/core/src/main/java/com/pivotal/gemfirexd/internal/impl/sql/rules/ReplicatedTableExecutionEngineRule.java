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

import java.util.List;

import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;

class ReplicatedTableExecutionEngineRule extends AccumulativeExecutionEngineRule {

  @Override
  protected ExecutionEngine findExecutionEngine(DMLQueryInfo qInfo , ExecutionRuleContext context) {
    List<GemFireContainer> containers = qInfo.getContainerList();
    for (GemFireContainer container : containers) {
      if (container.getRegion() instanceof PartitionedRegion) {
        context.setExtraDecisionMakerParam(new Boolean(true));
      }
    }
    return ExecutionEngine.NOT_DECIDED;
  }


  @Override
  ExecutionEngine applyAccumulativeRuleAndGetEngine(ExecutionRuleContext context) {
      if (context.getExtraDecisionMakerParam() == null) return ExecutionEngine.STORE;
      else return ExecutionEngine.NOT_DECIDED;
  }
}
