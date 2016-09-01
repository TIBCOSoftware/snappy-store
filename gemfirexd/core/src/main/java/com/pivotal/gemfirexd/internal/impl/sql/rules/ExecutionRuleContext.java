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

public class ExecutionRuleContext {
  ExecutionEngineRule.ExecutionEngine engine;
  Object extraDecisionMakerParam;

  ExecutionRuleContext(ExecutionEngineRule.ExecutionEngine engine) {
    this.engine = engine;
    this.extraDecisionMakerParam = null;
  }

  public Object getExtraDecisionMakerParam() {
    return extraDecisionMakerParam;
  }

  public void setExtraDecisionMakerParam(Object extraDecisionMakerParam) {
    this.extraDecisionMakerParam = extraDecisionMakerParam;
  }

  public ExecutionEngineRule.ExecutionEngine getEngine() {
    return engine;
  }

  public void setEngine(ExecutionEngineRule.ExecutionEngine engine) {
    this.engine = engine;
  }
}
