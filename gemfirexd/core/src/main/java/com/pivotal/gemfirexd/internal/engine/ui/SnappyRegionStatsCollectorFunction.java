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
package com.pivotal.gemfirexd.internal.engine.ui;

import java.util.*;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.management.ManagementService;
import com.gemstone.gemfire.management.RegionMXBean;
import com.gemstone.gemfire.management.internal.SystemManagementService;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.tools.sizer.GemFireXDInstrumentation;
import com.pivotal.gemfirexd.tools.sizer.ObjectSizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnappyRegionStatsCollectorFunction implements Function, Declarable {

  private static final long serialVersionUID = 1966980144121152499L;

  public static String ID = "SnappyRegionStatsCollectorFunction";

  public static final ObjectSizer sizer = ObjectSizer.getInstance(true);

  static {
    sizer.setForInternalUse(true);
  }

  @Override
  public void init(Properties props) {

  }

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public void execute(FunctionContext context) {

    com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider.getClusterCallbacks().
        publishColumnTableStats();
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
            if (bean != null && !(r.getFullPath().startsWith(
                "/" + Misc.SNAPPY_HIVE_METASTORE + '/'))) {
              SnappyRegionStats dataCollector = collectDataFromBean(r, bean);
              if (dataCollector.isColumnTable()) {
                cachBatchStats.put(dataCollector.getRegionName(), dataCollector);
              } else {
                otherStats.add(dataCollector);
              }
            }
          }
          /*
          if(!LocalRegion.isMetaTable(r.getFullPath())){
            result.addAllIndexStat(getIndexStatForContainer(container));
          }
          */
        }
      }



      if (Misc.reservoirRegionCreated) {
        for (SnappyRegionStats tableStats : otherStats) {
          String rgnName = tableStats.getRegionName();
          StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
          String columnBatchTableName = callback.columnBatchTableName(tableStats.getRegionName());
          if (cachBatchStats.containsKey(columnBatchTableName)) {
            String reservoirRegionName = Misc.getReservoirRegionNameForSampleTable("APP", rgnName);
            PartitionedRegion pr = Misc.getReservoirRegionForSampleTable(reservoirRegionName);
            if (pr != null) {
              RegionMXBean reservoirBean = managementService.getLocalRegionMBean(pr.getFullPath());
              if (reservoirBean != null) {
                SnappyRegionStats rStats = collectDataFromBeanImpl(pr, reservoirBean, true);
                SnappyRegionStats cStats = cachBatchStats.get(columnBatchTableName);
                cachBatchStats.put(columnBatchTableName, cStats.getCombinedStats(rStats));
              }
            }
          }
        }
      }

      // Create one entry per Column Table by combining the results of row buffer and column table
      for (SnappyRegionStats tableStats : otherStats) {
        StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
        String columnBatchTableName = callback.columnBatchTableName(tableStats.getRegionName());
        if (cachBatchStats.containsKey(columnBatchTableName)) {
          result.addRegionStat(tableStats.getCombinedStats(cachBatchStats.get(columnBatchTableName)));
        } else {
          result.addRegionStat(tableStats);
        }
      }
    } catch (CacheClosedException ignored) {
    } finally {
      context.getResultSender().lastResult(result);
    }

  }

  private SnappyRegionStats collectDataFromBean(LocalRegion lr, RegionMXBean bean) {
    return collectDataFromBeanImpl(lr, bean, false);
  }

  private long getEntryOverhead(RegionEntry entry,
      GemFireXDInstrumentation sizer) {
    long entryOverhead = sizer.sizeof(entry);
    if (entry instanceof DiskEntry) {
      entryOverhead += sizer.sizeof(((DiskEntry)entry).getDiskId());
    }
    return entryOverhead;
  }

  private SnappyRegionStats collectDataFromBeanImpl(LocalRegion lr, RegionMXBean bean, boolean isReservoir) {
    String regionName = (Misc.getFullTableNameFromRegionPath(bean.getFullPath()));
    SnappyRegionStats tableStats =
        new SnappyRegionStats(regionName);
    boolean isColumnTable = bean.isColumnTable();
    tableStats.setColumnTable(isColumnTable);
    tableStats.setReplicatedTable(isReplicatedTable(lr.getDataPolicy()));
    if (isReservoir) {
      long numLocalEntries = bean.getRowsInReservoir();
      tableStats.setRowCount(numLocalEntries);
    } else {
      tableStats.setRowCount(isColumnTable ? bean.getRowsInColumnBatches() : bean.getEntryCount());
    }

    GemFireXDInstrumentation sizer = GemFireXDInstrumentation.getInstance();
    if (isReplicatedTable(lr.getDataPolicy())) {
      long entryCount = 0L;
      Iterator<RegionEntry> ri = lr.entries.regionEntries().iterator();
      long size = lr.estimateMemoryOverhead(sizer);
      long entryOverhead = -1;
      while (ri.hasNext()) {
        RegionEntry re = ri.next();
        if (entryOverhead < 0) {
          entryOverhead = getEntryOverhead(re, sizer);
        }
        size += entryOverhead;
        Object value = re._getValue();
        if (value != null) {
          size += CachedDeserializableFactory.calcMemSize(value);
        }
        entryCount++;
      }
      size +=  lr.getKeySizeInMemory();
      tableStats.setSizeInMemory(size);
      DiskRegion dr = lr.getDiskRegion();
      if (dr != null) {
        DiskRegionStats stats = dr.getStats();
        long diskBytes = stats.getBytesWritten();
        if (lr.getDataPolicy().withPersistence()) {
          // find the number of entries only on disk and adjust the size
          // in proportion to the in-memory size since the per-entry size
          // of in-memory vs on-disk will be different
          long numOverflow = stats.getNumOverflowOnDisk();
          if (numOverflow > 0) {
            double avgDiskEntrySize = Math.max(1.0, (double)diskBytes / entryCount);
            size += (long)(avgDiskEntrySize * numOverflow);
          }
        } else {
          size += diskBytes;
        }
      }
      tableStats.setTotalSize(size);
    } else {
      PartitionedRegion pr = (PartitionedRegion)lr;
      PartitionedRegionDataStore datastore = pr.getDataStore();
      long sizeInMemory = 0L;
      long sizeOfRegion = 0L;
      long offHeapBytes = 0L;
      long entryOverhead = 0L;
      long entryCount = 0L;
      if (datastore != null) {
        Set<BucketRegion> bucketRegions = datastore.getAllLocalBucketRegions();
        for (BucketRegion br : bucketRegions) {
          long constantOverhead = br.estimateMemoryOverhead(sizer);
          // get overhead of one entry and extrapolate it for all entries
          if (entryOverhead == 0) {
            Iterator<RegionEntry> iter = br.entries.regionEntries().iterator();
            if (iter.hasNext()) {
              RegionEntry re = iter.next();
              entryOverhead = getEntryOverhead(re, sizer);
            }
          }
          sizeInMemory += constantOverhead + br.getSizeInMemory();
          sizeInMemory += br.getKeySizeInMemory();
          sizeOfRegion += constantOverhead + br.getTotalBytes();
          entryCount += br.entryCount();
        }
        offHeapBytes = pr.getPrStats().getOffHeapSizeInBytes();
      }
      if (entryOverhead > 0) {
        entryOverhead *= entryCount;
      }

      tableStats.setSizeInMemory(sizeInMemory + offHeapBytes + entryOverhead);
      tableStats.setTotalSize(sizeOfRegion + offHeapBytes + entryOverhead);
    }
    return tableStats;
  }

  public ArrayList<SnappyIndexStats> getIndexStatForContainer(GemFireContainer c) {
    final LinkedHashMap<String, Object[]> retEstimates = new LinkedHashMap<>();
    final String baseTableContainerName = c.getQualifiedTableName();
    ArrayList<SnappyIndexStats> indexStats = new ArrayList<>();
    final LocalRegion reg = c.getRegion();
    final GfxdIndexManager idxMgr = (GfxdIndexManager)reg.getIndexUpdater();

    List<GemFireContainer> indexes = (idxMgr != null ? idxMgr.getAllIndexes()
        : Collections.emptyList());
    try {
      sizer.estimateIndexEntryValueSizes(baseTableContainerName, indexes,
          retEstimates, null);
      for (Map.Entry<String, Object[]> e : retEstimates.entrySet()) {
        long[] value = (long[])e.getValue()[0];
        long sum = 0L;
        sum += value[0]; //constantOverhead
        sum += value[1]; //entryOverhead[0] + /entryOverhead[1]
        sum += value[2]; //keySize
        sum += value[3]; //valueSize
        long rowCount = value[5];
        indexStats.add(new SnappyIndexStats(e.getKey(), rowCount, sum));
      }
    } catch (StandardException | IllegalAccessException | InterruptedException e) {
      Logger logger = LoggerFactory.getLogger(getClass().getName());
      logger.warn("Unexpected exception in getIndexStatForContainer: " +
          e.toString(), e);
    }
    return indexStats;
  }

  public Boolean isReplicatedTable(DataPolicy dataPolicy) {
    return dataPolicy == DataPolicy.PERSISTENT_REPLICATE ||
        dataPolicy == DataPolicy.REPLICATE;
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
