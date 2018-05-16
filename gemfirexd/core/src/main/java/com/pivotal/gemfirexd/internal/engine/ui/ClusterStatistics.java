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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

  private CircularFifoQueue<Double> aggrMemoryUsageTrend = new CircularFifoQueue(MAX_SAMPLE_SIZE);

  private CircularFifoQueue<Double> diskStoreDiskSpaceTrend =
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
  public static final int TREND_AGGR_MEMORY_USAGE = 9;
  public static final int TREND_DISKSTORE_DISKSPACE_USAGE = 10;

  public void updateClusterStatistics(Map<String, MemberStatistics> memberStatsMap) {

    long lastMemberUpdatedTime = 0;

    long sumJvmMaxMemory = 0;
    long sumJvmFreeMemory = 0;
    long sumJvmTotalMemory = 0;
    long sumJvmUsedMemory = 0;

    long sumHeapStoragePoolUsed = 0;
    long sumHeapStoragePoolSize = 0;
    long sumHeapExecutionPoolUsed = 0;
    long sumHeapExecutionPoolSize = 0;
    long sumHeapMemoryUsed = 0;
    long sumHeapMemorySize = 0;

    long sumOffHeapStoragePoolUsed = 0;
    long sumOffHeapStoragePoolSize = 0;
    long sumOffHeapExecutionPoolUsed = 0;
    long sumOffHeapExecutionPoolSize = 0;
    long sumOffHeapMemoryUsed = 0;
    long sumOffHeapMemorySize = 0;

    long sumAggrMemoryUsed = 0;
    long sumAggrMemorySize = 0;

    long sumDiskStoreDiskSpace = 0;

    Set<String> hostsList = new HashSet<>();
    int totalCpuActive = 0;
    int cpuCount = 0;

    for (MemberStatistics ms : memberStatsMap.values()) {

      lastMemberUpdatedTime = ms.getLastUpdatedOn();

      // CPU Usage
      String host = ms.getHost();
      if(!hostsList.contains(host) && !ms.isLocator()){
        hostsList.add(host);
        totalCpuActive += ms.getCpuActive();
        cpuCount++;
      }

      sumJvmMaxMemory += ms.getJvmMaxMemory();
      sumJvmFreeMemory += ms.getJvmFreeMemory();
      sumJvmTotalMemory += ms.getJvmTotalMemory();
      sumJvmUsedMemory += ms.getJvmUsedMemory();

      sumHeapStoragePoolUsed += ms.getHeapStoragePoolUsed();
      sumHeapStoragePoolSize += ms.getHeapStoragePoolSize();
      sumHeapExecutionPoolUsed += ms.getHeapExecutionPoolUsed();
      sumHeapExecutionPoolSize += ms.getHeapExecutionPoolSize();
      sumHeapMemoryUsed += ms.getHeapMemoryUsed();
      sumHeapMemorySize += ms.getHeapMemorySize();

      sumOffHeapStoragePoolUsed += ms.getOffHeapStoragePoolUsed();
      sumOffHeapStoragePoolSize += ms.getOffHeapStoragePoolSize();
      sumOffHeapExecutionPoolUsed += ms.getOffHeapExecutionPoolUsed();
      sumOffHeapExecutionPoolSize += ms.getOffHeapExecutionPoolSize();
      sumOffHeapMemoryUsed += ms.getOffHeapMemoryUsed();
      sumOffHeapMemorySize += ms.getOffHeapMemorySize();

      sumDiskStoreDiskSpace += ms.getDiskStoreDiskSpace();

    }

    sumAggrMemoryUsed = sumHeapMemoryUsed + sumOffHeapMemoryUsed;
    sumAggrMemorySize = sumHeapMemorySize + sumOffHeapMemorySize;

    this.timeLine.add(lastMemberUpdatedTime);

    this.cpuUsageTrend.add(totalCpuActive / cpuCount);

    this.jvmUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumJvmUsedMemory, SnappyUtils.StorageSizeUnits.GB));

    this.heapStoragePoolUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumHeapStoragePoolUsed, SnappyUtils.StorageSizeUnits.GB));

    this.heapExecutionPoolUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumHeapExecutionPoolUsed, SnappyUtils.StorageSizeUnits.GB));

    this.heapUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumHeapMemoryUsed, SnappyUtils.StorageSizeUnits.GB));

    this.offHeapStoragePoolUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumOffHeapStoragePoolUsed, SnappyUtils.StorageSizeUnits.GB));

    this.offHeapExecutionPoolUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumOffHeapExecutionPoolUsed, SnappyUtils.StorageSizeUnits.GB));

    this.offHeapUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumOffHeapMemoryUsed, SnappyUtils.StorageSizeUnits.GB));

    this.aggrMemoryUsageTrend.add(
        SnappyUtils.bytesToGivenUnits(sumAggrMemoryUsed, SnappyUtils.StorageSizeUnits.GB));

    this.diskStoreDiskSpaceTrend.add(
        SnappyUtils.bytesToGivenUnits(sumDiskStoreDiskSpace, SnappyUtils.StorageSizeUnits.GB));

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
      case TREND_AGGR_MEMORY_USAGE:
        synchronized (this.aggrMemoryUsageTrend) {
          returnArray = this.aggrMemoryUsageTrend.toArray();
        }
        break;
      case TREND_DISKSTORE_DISKSPACE_USAGE:
        synchronized (this.diskStoreDiskSpaceTrend) {
          returnArray = this.diskStoreDiskSpaceTrend.toArray();
        }
        break;
    }

    return returnArray;
  }
}
