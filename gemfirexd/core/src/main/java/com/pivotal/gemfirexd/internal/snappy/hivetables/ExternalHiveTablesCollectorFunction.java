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

package com.pivotal.gemfirexd.internal.snappy.hivetables;

import java.util.Collection;

import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.snappy.hivetables.dto.ExternalHiveTablesCollectorResult;

/**
 * Server node(s) use this function to retrieve metadata of hive tables stored
 * in external hive metastore (if configured) which is available on lead node.
 * Returns {@link ExternalHiveTablesCollectorResult}.
 */
public class ExternalHiveTablesCollectorFunction implements Function {

  public static final String ID = "ExternalHiveTablesCollectorFunction";

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public void execute(FunctionContext context) {
    Collection<ExternalTableMetaData> hiveTablesMetadata =
        CallbackFactoryProvider.getClusterCallbacks().getHiveTablesMetadata();
    context.getResultSender().lastResult(new ExternalHiveTablesCollectorResult(hiveTablesMetadata));
  }

  @Override
  public String getId() {
    return ID;
  }

  //todo[vatsal] : verify this return value
  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  //todo[vatsal] : verify this return value
  @Override
  public boolean isHA() {
    return false;
  }
}
