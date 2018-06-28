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

package com.pivotal.gemfirexd.internal.snappy;

import java.util.HashSet;
import java.util.Iterator;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;

/**
 * This class should be used to hold the callback factories that are used to communicate
 * with Snappy.
 * The callbacks need to be set when snappy is initializing
 * <p/>
 */
public abstract class CallbackFactoryProvider {
  // no-op implementation.
  private static ClusterCallbacks clusterCallbacks = new ClusterCallbacks() {

    public HashSet<String> getLeaderGroup() {
      return null;
    }

    public void launchExecutor(String driver_url, InternalDistributedMember driverDM) {
    }

    public String getDriverURL() {
      return null;
    }

    public void stopExecutor() {
    }

    @Override
    public SparkSQLExecute getSQLExecute(String sql, String schema,
        LeadNodeExecutionContext ctx, Version v, boolean isPreparedStatement,
        boolean isPreparedPhase, ParameterValueSet pvs) {
       return null;
    }

    @Override
    public Object readDataType(ByteArrayDataInput in) {
      return null;
    }

    @Override
    public Iterator<ValueRow> getRowIterator(DataValueDescriptor[] dvds,
        int[] types, int[] precisions, int[] scales, Object[] dataTypes,
        ByteArrayDataInput in) {
      return null;
    }

    @Override
    public void clearSnappySessionForConnection(Long connectionId) {

    }

    @Override
    public void publishColumnTableStats() {
    }

    @Override
    public String getClusterType() {
      return "";
    }

    @Override
    public void setLeadClassLoader() {
    }
  };

  public static ClusterCallbacks getClusterCallbacks() {
    return clusterCallbacks;
  }

  public static StoreCallbacks getStoreCallbacks() {
    return com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider
        .getStoreCallbacks();
  }

  public static void setClusterCallbacks(ClusterCallbacks cb) {
    clusterCallbacks = cb;
  }
}
