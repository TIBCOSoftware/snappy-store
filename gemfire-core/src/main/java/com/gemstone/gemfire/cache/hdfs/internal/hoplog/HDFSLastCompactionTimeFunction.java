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
package com.gemstone.gemfire.cache.hdfs.internal.hoplog;

import com.gemstone.gemfire.cache.execute.FunctionAdapter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;

/**
 * Function that returns the oldest timestamp among all the major
 * compacted buckets on the members
 *
 * @author sbawaska
 */
@SuppressWarnings("serial")
public class HDFSLastCompactionTimeFunction extends FunctionAdapter {

  public static final String ID = "HDFSLastCompactionTimeFunction";

  @Override
  public void execute(FunctionContext context) {
    RegionFunctionContext rfc = (RegionFunctionContext) context;
    PartitionedRegion pr = (PartitionedRegion) rfc.getDataSet();
    rfc.getResultSender().lastResult(pr.lastLocalMajorHDFSCompaction());
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean isHA() {
    return true;
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }
}
