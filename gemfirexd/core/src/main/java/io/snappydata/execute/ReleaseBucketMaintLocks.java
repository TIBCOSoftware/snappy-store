/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package io.snappydata.execute;

import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.pivotal.gemfirexd.internal.engine.Misc;

/**
 * Distributed function to release bucket locks with given IDs (or null for all)
 * acquired by some owner across the distributed system.
 * <p>
 * Expects three arguments: tableName, forWrite, lockOwner, bucketIds
 */
public class ReleaseBucketMaintLocks implements Declarable, Function {

  public static final String ID = "ReleaseBucketMaintLocks";

  @Override
  public void init(Properties props) {
  }

  @Override
  public void execute(FunctionContext context) {
    // three arguments: tableName, forWrite, lockOwner, bucketIds
    Object[] args = (Object[])context.getArguments();
    String tableName = (String)args[0];
    boolean forWrite = (Boolean)args[1];
    String lockOwner = (String)args[2];
    @SuppressWarnings("unchecked") Set<Integer> bucketIds = (Set<Integer>)args[3];

    Region<?, ?> region = Misc.getRegionForTable(tableName, false);
    if (region instanceof PartitionedRegion) {
      PartitionedRegion pr = (PartitionedRegion)region;
      PartitionedRegionDataStore ds = pr.getDataStore();
      if (ds != null) {
        for (BucketRegion br : ds.getAllLocalBucketRegions()) {
          try {
            if (lockOwner == null) {
              // release all locks
              br.unlockAllAfterMaintenance(forWrite);
            } else if ((bucketIds == null || bucketIds.contains(br.getId()))
                && br.hasMaintenanceLock(forWrite, lockOwner)) {
              br.unlockAfterMaintenance(forWrite, lockOwner);
            }
          } catch (Exception e) {
            pr.getLogWriterI18n().warning(e);
          }
        }
      }
    }
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean hasResult() {
    return false;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return false;
  }
}
