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
package com.gemstone.gemfire.internal.snappy.memory;

import com.gemstone.gemfire.StatisticDescriptor;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.StatisticsTypeFactory;
import com.gemstone.gemfire.internal.StatisticsTypeFactoryImpl;

public class MemoryManagerStats {

  private static final StatisticsType type;
  private final Statistics stats;
  private final static int storagePoolSizeId;
  private final static int storageMemoryUsedId;
  private final static int executionPoolSizeId;
  private final static int executionPoolMemoryUsedId;
  private final static int numFailedStorageRequestId;
  private final static int numFailedExecutionRequestId;

  public MemoryManagerStats(StatisticsFactory factory, String name) {
    this.stats = factory.createAtomicStatistics(
        type, name);
  }

  static {
    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();
    type = f.createType(
        "MemoryManagerStats",
        "Statistics for Unified Memory Manager pools",
        new StatisticDescriptor[]{
            f.createLongGauge(
                "storagePoolSize",
                "Current storage pool size.",
                "byte"),
            f.createLongGauge(
                "storageMemoryUsed",
                "Current memory used from storage pool.",
                "byte"),
            f.createLongGauge(
                "executionPoolSize",
                "Current execution pool size.",
                "byte"),
            f.createLongGauge(
                "executionPoolMemoryUsed",
                "Current memory used from exxecution pool.",
                "byte"),
            f.createIntCounter(
                "numFailedStorageRequest",
                "Number of failed storage memory request.",
                "operations",
                false),
            f.createIntCounter(
                "numFailedExecutionRequest",
                "Number of failed storage memory request.",
                "operations",
                false),
        });

    storagePoolSizeId = type.nameToId("storagePoolSize");
    storageMemoryUsedId = type.nameToId("storageMemoryUsed");
    executionPoolSizeId = type.nameToId("executionPoolSize");
    executionPoolMemoryUsedId = type.nameToId("executionPoolMemoryUsed");
    numFailedStorageRequestId = type.nameToId("numFailedStorageRequest");
    numFailedExecutionRequestId = type.nameToId("numFailedExecutionRequest");

  }

  public void incStoragePoolSize(long delta) {
    this.stats.incLong(storagePoolSizeId, delta);
  }

  public void decStoragePoolSize(long delta) {
    this.stats.incLong(storagePoolSizeId, -delta);
  }

  public void incExecutionPoolSize(long delta) {
    this.stats.incLong(executionPoolSizeId, delta);
  }

  public void decExecutionPoolSize(long delta) {
    this.stats.incLong(executionPoolSizeId, -delta);
  }

  public void incStorageMemoryUsed(long delta) {
    this.stats.incLong(storageMemoryUsedId, delta);
  }

  public void decStorageMemoryUsed(long delta) {
    this.stats.incLong(storageMemoryUsedId, -delta);
  }

  public void incExecutionMemoryUsed(long delta) {
    this.stats.incLong(executionPoolMemoryUsedId, delta);
  }

  public void decExecutionMemoryUsed(long delta) {
    this.stats.incLong(executionPoolMemoryUsedId, -delta);
  }

  public void incNumFailedStorageRequest() {
    this.stats.incInt(numFailedStorageRequestId, 1);
  }

  public void incNumFailedExecutionRequest() {
    this.stats.incInt(numFailedExecutionRequestId, 1);
  }
}
