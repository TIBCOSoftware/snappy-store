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
package com.gemstone.gemfire.internal.snappy.memory;

import com.gemstone.gemfire.StatisticDescriptor;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.StatisticsTypeFactory;
import com.gemstone.gemfire.internal.StatisticsTypeFactoryImpl;

public class MemoryManagerStats implements MemoryManagerStatsOps {

  private static final StatisticsType type;
  private final Statistics stats;
  private final static int heapStoragePoolSizeId;
  private final static int heapStorageMemoryUsedId;
  private final static int heapExecutionPoolSizeId;
  private final static int heapExecutionPoolMemoryUsedId;
  private final static int numFailedHeapStorageRequestId;
  private final static int numFailedHeapExecutionRequestId;
  private final static int offHeapStoragePoolSizeId;
  private final static int offHeapStorageMemoryUsedId;
  private final static int offHeapExecutionPoolSizeId;
  private final static int offHeapExecutionPoolMemoryUsedId;
  private final static int numFailedOffHeapStorageRequestId;
  private final static int numFailedOffHeapExecutionRequestId;
  private final static int maxOffHeapStorageSizeId;
  private final static int maxHeapStorageSizeId;
  private final static int failedHeapEvictionRequestsId;
  private final static int failedOffHeapEvictionRequestsId;


  public MemoryManagerStats(StatisticsFactory factory, String name) {
    this.stats = factory.createAtomicStatistics(type, name);
  }

  static {
    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();
    type = f.createType(
        "MemoryManagerStats",
        "Statistics for Unified Memory Manager pools",
        new StatisticDescriptor[]{
            f.createLongGauge(
                "heapStoragePoolSize",
                "Current storage pool size.",
                "byte"),
            f.createLongGauge(
                "heapStorageMemoryUsed",
                "Current memory used from heap storage pool.",
                "byte"),
            f.createLongGauge(
                "heapExecutionPoolSize",
                "Current heap execution pool size.",
                "byte"),
            f.createLongGauge(
                "heapExecutionPoolMemoryUsed",
                "Current memory used from heap execution pool.",
                "byte"),
            f.createIntCounter(
                "numFailedHeapStorageRequest",
                "Number of failed heap storage memory request.",
                "operations",
                false),
            f.createIntCounter(
                "numFailedHeapExecutionRequest",
                "Number of failed heap execution memory request.",
                "operations",
                false),
            f.createLongGauge(
                "offHeapStoragePoolSize",
                "Current storage pool size.",
                "byte"),
            f.createLongGauge(
                "offHeapStorageMemoryUsed",
                "Current memory used from storage pool.",
                "byte"),
            f.createLongGauge(
                "offHeapExecutionPoolSize",
                "Current execution pool size.",
                "byte"),
            f.createLongGauge(
                "offHeapExecutionPoolMemoryUsed",
                "Current memory used from execution pool.",
                "byte"),
            f.createIntCounter(
                "numFailedOffHeapStorageRequest",
                "Number of failed storage memory request.",
                "operations",
                false),
            f.createIntCounter(
                "numFailedOffHeapExecutionRequest",
                "Number of failed storage memory request.",
                "operations",
                false),
            f.createLongCounter(
                "maxOffHeapStorageSize",
                "Maximum off heap memory which can be used for storage",
                "operations",
                false),
            f.createLongCounter(
                "maxHeapStorageSize",
                "Maximum heap memory which can be used for storage",
                "operations",
                false),
            f.createIntCounter(
                "failedHeapEvictionRequests",
                "Number of failed heap eviction storage request ",
                "operations",
                false),
            f.createIntCounter(
                "failedOffHeapEvictionRequests",
                "Number of failed off heap eviction storage request ",
                "operations",
                false),
        });

    heapStoragePoolSizeId = type.nameToId("heapStoragePoolSize");
    heapStorageMemoryUsedId = type.nameToId("heapStorageMemoryUsed");
    heapExecutionPoolSizeId = type.nameToId("heapExecutionPoolSize");
    heapExecutionPoolMemoryUsedId = type.nameToId("heapExecutionPoolMemoryUsed");
    numFailedHeapStorageRequestId = type.nameToId("numFailedHeapStorageRequest");
    numFailedHeapExecutionRequestId = type.nameToId("numFailedHeapExecutionRequest");

    offHeapStoragePoolSizeId = type.nameToId("offHeapStoragePoolSize");
    offHeapStorageMemoryUsedId = type.nameToId("offHeapStorageMemoryUsed");
    offHeapExecutionPoolSizeId = type.nameToId("offHeapExecutionPoolSize");
    offHeapExecutionPoolMemoryUsedId = type.nameToId("offHeapExecutionPoolMemoryUsed");
    numFailedOffHeapStorageRequestId = type.nameToId("numFailedOffHeapStorageRequest");
    numFailedOffHeapExecutionRequestId = type.nameToId("numFailedOffHeapExecutionRequest");

    maxOffHeapStorageSizeId = type.nameToId("maxOffHeapStorageSize");
    maxHeapStorageSizeId = type.nameToId("maxHeapStorageSize");
    failedHeapEvictionRequestsId = type.nameToId("failedHeapEvictionRequests");
    failedOffHeapEvictionRequestsId = type.nameToId("failedOffHeapEvictionRequests");
  }


  @Override
  public void incStoragePoolSize(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapStoragePoolSizeId, delta);
    } else {
      this.stats.incLong(heapStoragePoolSizeId, delta);
    }
  }

  @Override
  public long getStoragePoolSize(boolean offHeap) {
    return offHeap ? stats.getLong(offHeapStoragePoolSizeId) :
        stats.getLong(heapStoragePoolSizeId);
  }

  @Override
  public void decStoragePoolSize(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapStoragePoolSizeId, -delta);
    } else {
      this.stats.incLong(heapStoragePoolSizeId, -delta);
    }
  }

  @Override
  public void incExecutionPoolSize(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapExecutionPoolSizeId, delta);
    } else {
      this.stats.incLong(heapExecutionPoolSizeId, delta);
    }
  }

  @Override
  public void decExecutionPoolSize(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapExecutionPoolSizeId, -delta);
    } else {
      this.stats.incLong(heapExecutionPoolSizeId, -delta);
    }
  }

  @Override
  public long getExecutionPoolSize(boolean offHeap) {
    return offHeap ? stats.getLong(offHeapExecutionPoolSizeId) :
        stats.getLong(heapExecutionPoolSizeId);
  }

  @Override
  public void incStorageMemoryUsed(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapStorageMemoryUsedId, delta);
    } else {
      this.stats.incLong(heapStorageMemoryUsedId, delta);
    }
  }

  @Override
  public void decStorageMemoryUsed(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapStorageMemoryUsedId, -delta);
    } else {
      this.stats.incLong(heapStorageMemoryUsedId, -delta);
    }
  }

  @Override
  public long getStorageMemoryUsed(boolean offHeap) {
    return offHeap ? stats.getLong(offHeapStorageMemoryUsedId) :
        stats.getLong(heapStorageMemoryUsedId);
  }

  @Override
  public void incExecutionMemoryUsed(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapExecutionPoolMemoryUsedId, delta);
    } else {
      this.stats.incLong(heapExecutionPoolMemoryUsedId, delta);
    }
  }

  @Override
  public void decExecutionMemoryUsed(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(offHeapExecutionPoolMemoryUsedId, -delta);
    } else {
      this.stats.incLong(heapExecutionPoolMemoryUsedId, -delta);
    }
  }

  @Override
  public long getExecutionMemoryUsed(boolean offHeap) {
    return offHeap ? stats.getLong(offHeapExecutionPoolMemoryUsedId) :
        stats.getLong(heapExecutionPoolMemoryUsedId);
  }

  @Override
  public void incNumFailedStorageRequest(boolean offHeap) {
    if (offHeap) {
      this.stats.incInt(numFailedOffHeapStorageRequestId, 1);
    } else {
      this.stats.incInt(numFailedHeapStorageRequestId, 1);
    }
  }

  @Override
  public int getNumFailedStorageRequest(boolean offHeap) {
    return offHeap ? stats.getInt(numFailedOffHeapStorageRequestId) :
        stats.getInt(numFailedHeapStorageRequestId);
  }

  @Override
  public void incNumFailedExecutionRequest(boolean offHeap) {
    if (offHeap) {
      this.stats.incInt(numFailedOffHeapExecutionRequestId, 1);
    } else {
      this.stats.incInt(numFailedHeapExecutionRequestId, 1);
    }
  }

  @Override
  public int getNumFailedExecutionRequest(boolean offHeap) {
    return offHeap ? stats.getInt(numFailedOffHeapExecutionRequestId) :
        stats.getInt(numFailedHeapExecutionRequestId);
  }

  @Override
  public void incNumFailedEvictionRequest(boolean offHeap) {
    if (offHeap) {
      this.stats.incInt(failedOffHeapEvictionRequestsId, 1);
    } else {
      this.stats.incInt(failedHeapEvictionRequestsId, 1);
    }
  }

  @Override
  public int getNumFailedEvictionRequest(boolean offHeap) {
    return offHeap ? stats.getInt(failedOffHeapEvictionRequestsId) :
        stats.getInt(failedHeapEvictionRequestsId);
  }

  @Override
  public void incMaxStorageSize(boolean offHeap, long delta) {
    if (offHeap) {
      this.stats.incLong(maxOffHeapStorageSizeId, delta);
    } else {
      this.stats.incLong(maxHeapStorageSizeId, delta);
    }
  }

  @Override
  public long getMaxStorageSize(boolean offHeap) {
    if (offHeap) {
      return this.stats.getLong(maxOffHeapStorageSizeId);
    } else {
      return this.stats.getLong(maxHeapStorageSizeId);
    }
  }
}
