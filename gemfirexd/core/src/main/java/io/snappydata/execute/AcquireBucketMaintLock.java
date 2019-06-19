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

import java.util.Collections;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils;

/**
 * Distributed function to acquire a given bucket lock on primary.
 * <p>
 * Expects three arguments: regionPath, forWrite, lockOwner, bucketId
 */
public class AcquireBucketMaintLock implements Declarable, Function {

  public static final String ID = "AcquireBucketMaintLock";

  @Override
  public void init(Properties props) {
  }

  @Override
  public void execute(FunctionContext context) {
    // three arguments: regionPath, forWrite, lockOwner, bucketId
    Object[] args = (Object[])context.getArguments();
    String regionPath = (String)args[0];
    boolean forWrite = (Boolean)args[1];
    String lockOwner = (String)args[2];
    Integer bucketId = (Integer)args[3];

    PartitionedRegion pr = (PartitionedRegion)Misc.getRegion(
        regionPath, true, false);
    PartitionedRegionDataStore ds = pr.getDataStore();
    if (ds != null) {
      try {
        BucketRegion br = ds.getLocalBucketById(bucketId);
        // if bucket is present and primary, then lock locally else send to primary
        if (br != null && br.getBucketAdvisor().isPrimary()) {
          br.lockForMaintenance(forWrite, Long.MAX_VALUE, lockOwner);
          // primary check for bucket after acquiring the lock
          if (br.getBucketAdvisor().isPrimary()) {
            context.getResultSender().lastResult(Boolean.TRUE);
            return;
          } else {
            br.unlockAfterMaintenance(forWrite, lockOwner);
          }
        }
      } catch (Exception e) {
        pr.getLogWriterI18n().warning(e);
      }
    }
    // force retry in case of lock failure
    throw new BucketMovedException("Primary not found for locking by "
        + lockOwner, bucketId, regionPath);
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }

  @Override
  public boolean isHA() {
    return true;
  }

  public static final class GetPrimaryMember
      implements FunctionUtils.GetFunctionMembers {

    private final PartitionedRegion region;
    private final int bucketId;

    public GetPrimaryMember(PartitionedRegion region, int bucketId) {
      this.region = region;
      this.bucketId = bucketId;
    }

    @Override
    public Set<DistributedMember> getMembers() {
      return Collections.singleton(region.getBucketPrimary(bucketId));
    }

    @Override
    public Set<String> getServerGroups() {
      return null;
    }

    @Override
    public void postExecutionCallback() {
    }
  }
}
