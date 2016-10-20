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
package com.pivotal.gemfirexd.internal.engine.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.management.ManagementService;
import com.gemstone.gemfire.management.RegionMXBean;
import com.gemstone.gemfire.management.internal.SystemManagementService;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.tools.sizer.GemFireXDInstrumentation;

public class SnappyRegionStatsCollectorFunction implements Function, Declarable {

  public static String ID = "SnappyRegionStatsCollectorFunction";

  @Override
  public void init(Properties props) {

  }

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public void execute(FunctionContext context) {
    SnappyRegionStatsCollectorResult result = new SnappyRegionStatsCollectorResult();
    Map<String, SnappyRegionStats> cachBatchStats = new HashMap<>();
    ArrayList<SnappyRegionStats> otherStats = new ArrayList<>();

    try {
      List<GemFireContainer> containers = Misc.getMemStore().getAllContainers();

      final SystemManagementService managementService =
          (SystemManagementService)ManagementService.getManagementService(Misc.getGemFireCache());

      for (GemFireContainer container : containers) {
        if (container.isApplicationTable()) {
          LocalRegion r = container.getRegion();
          if (managementService != null && r != null ) {
            RegionMXBean bean = managementService.getLocalRegionMBean(r.getFullPath());
            if (bean != null && !(r.getFullPath().startsWith("/SNAPPY_HIVE_METASTORE/"))) {
              SnappyRegionStats dataCollector = collectDataFromBean(r, bean);
              if (dataCollector.isColumnTable()) {
                cachBatchStats.put(dataCollector.getRegionName(), dataCollector);
              } else {
                otherStats.add(dataCollector);
              }
            }
          }
        }
      }

      // Create one entry per Column Table by combining the results of row buffer and column table
      for (SnappyRegionStats tableStats : otherStats) {
        StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
        String catchBatchTableName = callback.cachedBatchTableName(tableStats.getRegionName());
        if (cachBatchStats.containsKey(catchBatchTableName)) {
          result.addRegionStat(tableStats.getCombinedStats(cachBatchStats.get(catchBatchTableName)));
        } else {
          result.addRegionStat(tableStats);
        }
      }
    } catch (CacheClosedException e) {
    } finally {
      context.getResultSender().lastResult(result);
    }

  }

  private SnappyRegionStats collectDataFromBean(LocalRegion lr, RegionMXBean bean) {
    String regionName = (Misc.getFullTableNameFromRegionPath(bean.getFullPath()));
    SnappyRegionStats tableStats =
        new SnappyRegionStats(regionName);
    boolean isColumnTable = bean.isColumnTable();
    tableStats.setColumnTable(isColumnTable);
    tableStats.setReplicatedTable(isReplicatedTable(lr.getDataPolicy()));
    tableStats.setRowCount(isColumnTable ? bean.getRowsInCachedBatches(): bean.getEntryCount());

    if (isReplicatedTable(lr.getDataPolicy())) {
      java.util.Iterator<RegionEntry> ri = lr.getBestLocalIterator(true);
      long size = 0;
      while (ri.hasNext()) {
        size += GemFireXDInstrumentation.getInstance().sizeof(ri.next());
      }
      tableStats.setSizeInMemory(size);
    } else {
      PartitionedRegionDataStore datastore = ((PartitionedRegion)lr).getDataStore();
      int sizeOfRegion =0;
      if (datastore != null) {
        Set<BucketRegion> bucketRegions = datastore.getAllLocalBucketRegions();
        for (BucketRegion br : bucketRegions) {
          sizeOfRegion += br.getTotalBytes();
        }
      }

      tableStats.setSizeInMemory(bean.getEntrySize());
      tableStats.setTotalSize(sizeOfRegion);
    }
    return tableStats;
  }


  public Boolean isReplicatedTable(DataPolicy dataPolicy) {
    if (dataPolicy == DataPolicy.PERSISTENT_REPLICATE || dataPolicy == DataPolicy.REPLICATE)
      return true;
    else
      return false;
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return true;
  }

}
