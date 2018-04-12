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

import java.util.Map;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import static com.pivotal.gemfirexd.internal.engine.ui.MemberStatistics.MAX_SAMPLE_SIZE;

public class ClusterStatistics {

  private ClusterStatistics() {
  }

  public static ClusterStatistics getInstance() {
    return SingletonHelper.INSTANCE;
  }

  private static class SingletonHelper {
    private static final ClusterStatistics INSTANCE = new ClusterStatistics();
  }

  private CircularFifoQueue<Long> timeLine = new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Integer> cpuUsageTrend = new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Double> jvmUsageTrend = new CircularFifoQueue(MAX_SAMPLE_SIZE);

  private CircularFifoQueue<Double> heapUsageTrend = new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Double> heapStoragePoolUsageTrend =
      new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Double> heapExecutionPoolUsageTrend =
      new CircularFifoQueue(MAX_SAMPLE_SIZE);

  private CircularFifoQueue<Double> offHeapUsageTrend = new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Double> offHeapStoragePoolUsageTrend =
      new CircularFifoQueue(MAX_SAMPLE_SIZE);
  private CircularFifoQueue<Double> offHeapExecutionPoolUsageTrend =
      new CircularFifoQueue(MAX_SAMPLE_SIZE);

  public static final int TREND_TIMELINE = 0;
  public static final int TREND_CPU_USAGE = 1;
  public static final int TREND_JVM_HEAP_USAGE = 2;
  public static final int TREND_HEAP_USAGE = 3;
  public static final int TREND_HEAP_STORAGE_USAGE = 4;
  public static final int TREND_HEAP_EXECUTION_USAGE = 5;
  public static final int TREND_OFFHEAP_USAGE = 6;
  public static final int TREND_OFFHEAP_STORAGE_USAGE = 7;
  public static final int TREND_OFFHEAP_EXECUTION_USAGE = 8;

  public void updateClusterStatistics(Map<String, MemberStatistics> memberStatsMap) {

    int membersCount = memberStatsMap.size();

    long lastMemberUpdatedTime = 0;

    int totalCpuActive = 0;

    long aggrJvmMaxMemory = 0;
    long aggrJvmFreeMemory = 0;
    long aggrJvmTotalMemory = 0;
    long aggrJvmUsedMemory = 0;

    long aggrHeapStoragePoolUsed = 0;
    long aggrHeapStoragePoolSize = 0;
    long aggrHeapExecutionPoolUsed = 0;
    long aggrHeapExecutionPoolSize = 0;
    long aggrHeapMemoryUsed = 0;
    long aggrHeapMemorySize = 0;

    long aggrOffHeapStoragePoolUsed = 0;
    long aggrOffHeapStoragePoolSize = 0;
    long aggrOffHeapExecutionPoolUsed = 0;
    long aggrOffHeapExecutionPoolSize = 0;
    long aggrOffHeapMemoryUsed = 0;
    long aggrOffHeapMemorySize = 0;

    for (MemberStatistics ms : memberStatsMap.values()) {

      lastMemberUpdatedTime = ms.getLastUpdatedOn();

      totalCpuActive += ms.getCpuActive();

      aggrJvmMaxMemory += ms.getJvmMaxMemory();
      aggrJvmFreeMemory += ms.getJvmFreeMemory();
      aggrJvmTotalMemory += ms.getJvmTotalMemory();
      aggrJvmUsedMemory += ms.getJvmUsedMemory();

      aggrHeapStoragePoolUsed += ms.getHeapStoragePoolUsed();
      aggrHeapStoragePoolSize += ms.getHeapStoragePoolSize();
      aggrHeapExecutionPoolUsed += ms.getHeapExecutionPoolUsed();
      aggrHeapExecutionPoolSize += ms.getHeapExecutionPoolSize();
      aggrHeapMemoryUsed += ms.getHeapMemoryUsed();
      aggrHeapMemorySize += ms.getHeapMemorySize();

      aggrOffHeapStoragePoolUsed += ms.getOffHeapStoragePoolUsed();
      aggrOffHeapStoragePoolSize += ms.getOffHeapStoragePoolSize();
      aggrOffHeapExecutionPoolUsed += ms.getOffHeapExecutionPoolUsed();
      aggrOffHeapExecutionPoolSize += ms.getOffHeapExecutionPoolSize();
      aggrOffHeapMemoryUsed += ms.getOffHeapMemoryUsed();
      aggrOffHeapMemorySize += ms.getOffHeapMemorySize();

    }

    this.timeLine.add(lastMemberUpdatedTime);

    this.cpuUsageTrend.add(totalCpuActive / membersCount);

    if (aggrJvmTotalMemory > 0) {
      this.jvmUsageTrend.add(((double)aggrJvmUsedMemory * 100) / aggrJvmTotalMemory);
    } else {
      this.jvmUsageTrend.add(0.0);
    }

    if (aggrHeapStoragePoolSize > 0) {
      this.heapStoragePoolUsageTrend.add(
          ((double)aggrHeapStoragePoolUsed * 100) / aggrHeapStoragePoolSize);
    } else {
      this.heapStoragePoolUsageTrend.add(0.0);
    }

    if (aggrHeapExecutionPoolSize > 0) {
      this.heapExecutionPoolUsageTrend.add(
          ((double)aggrHeapExecutionPoolUsed * 100) / aggrHeapExecutionPoolSize);
    } else {
      this.heapExecutionPoolUsageTrend.add(0.0);
    }

    if (aggrHeapMemorySize > 0) {
      this.heapUsageTrend.add(
          ((double)aggrHeapMemoryUsed * 100) / aggrHeapMemorySize);
    } else {
      this.heapUsageTrend.add(0.0);
    }

    if (aggrOffHeapStoragePoolSize > 0) {
      this.offHeapStoragePoolUsageTrend.add(
          ((double)aggrOffHeapStoragePoolUsed * 100) / aggrOffHeapStoragePoolSize);
    } else {
      this.offHeapStoragePoolUsageTrend.add(0.0);
    }

    if (aggrOffHeapExecutionPoolSize > 0) {
      this.offHeapExecutionPoolUsageTrend.add(
          ((double)aggrOffHeapExecutionPoolUsed * 100) / aggrOffHeapExecutionPoolSize);
    } else {
      this.offHeapExecutionPoolUsageTrend.add(0.0);
    }

    if (aggrOffHeapMemorySize > 0) {
      this.offHeapUsageTrend.add(
          ((double)aggrOffHeapMemoryUsed * 100) / aggrOffHeapMemorySize);
    } else {
      this.offHeapUsageTrend.add(0.0);
    }

  }

  public Object[] getUsageTrends(int trendType) {
    Object[] returnArray = null;
    switch (trendType) {
      case TREND_TIMELINE:
        synchronized (this.timeLine) {
          returnArray = this.timeLine.toArray();
        }
        break;
      case TREND_CPU_USAGE:
        synchronized (this.cpuUsageTrend) {
          returnArray = this.cpuUsageTrend.toArray();
        }
        break;
      case TREND_JVM_HEAP_USAGE:
        synchronized (this.jvmUsageTrend) {
          returnArray = this.jvmUsageTrend.toArray();
        }
        break;
      case TREND_HEAP_USAGE:
        synchronized (this.heapUsageTrend) {
          returnArray = this.heapUsageTrend.toArray();
        }
        break;
      case TREND_HEAP_STORAGE_USAGE:
        synchronized (this.heapStoragePoolUsageTrend) {
          returnArray = this.heapStoragePoolUsageTrend.toArray();
        }
        break;
      case TREND_HEAP_EXECUTION_USAGE:
        synchronized (this.heapExecutionPoolUsageTrend) {
          returnArray = this.heapExecutionPoolUsageTrend.toArray();
        }
        break;
      case TREND_OFFHEAP_USAGE:
        synchronized (this.offHeapUsageTrend) {
          returnArray = this.offHeapUsageTrend.toArray();
        }
        break;
      case TREND_OFFHEAP_STORAGE_USAGE:
        synchronized (this.offHeapStoragePoolUsageTrend) {
          returnArray = this.offHeapStoragePoolUsageTrend.toArray();
        }
        break;
      case TREND_OFFHEAP_EXECUTION_USAGE:
        synchronized (this.offHeapExecutionPoolUsageTrend) {
          returnArray = this.offHeapExecutionPoolUsageTrend.toArray();
        }
        break;
    }

    return returnArray;
  }
}
