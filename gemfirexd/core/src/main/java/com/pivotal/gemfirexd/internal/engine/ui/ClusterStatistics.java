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

import org.apache.commons.collections.buffer.CircularFifoBuffer;

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

  private int totalCores = 0;
  private int locatorCores = 0;
  private int leadCores = 0;
  private int dataServerCores = 0;

  private final CircularFifoBuffer timeLine =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer cpuUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer jvmUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);

  private final CircularFifoBuffer heapUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer heapStoragePoolUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer heapExecutionPoolUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);

  private final CircularFifoBuffer offHeapUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer offHeapStoragePoolUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);
  private final CircularFifoBuffer offHeapExecutionPoolUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);

  private final CircularFifoBuffer aggrMemoryUsageTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);

  private final CircularFifoBuffer diskStoreDiskSpaceTrend =
      new CircularFifoBuffer(MAX_SAMPLE_SIZE);

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
    long sumJvmUsedMemory = 0;
    long sumHeapStoragePoolUsed = 0;
    long sumHeapExecutionPoolUsed = 0;
    long sumHeapMemoryUsed = 0;
    long sumOffHeapStoragePoolUsed = 0;
    long sumOffHeapExecutionPoolUsed = 0;
    long sumOffHeapMemoryUsed = 0;
    long sumAggrMemoryUsed;
    long sumDiskStoreDiskSpace = 0;

    Set<String> hostsList = new HashSet<>();
    int totalCpuActive = 0;
    int cpuCount = 0;

    totalCores = 0;
    locatorCores = 0;
    leadCores = 0;
    dataServerCores = 0;

    for (MemberStatistics ms : memberStatsMap.values()) {

      lastMemberUpdatedTime = ms.getLastUpdatedOn();

      // CPU cores
      if(ms.isLocator()){
        this.locatorCores += ms.getCores();
      } else if(ms.isLead()) {
        this.leadCores += ms.getCores();
      } else {
        this.dataServerCores += ms.getCores();
      }
      this.totalCores += ms.getCores();

      // CPU Usage
      String host = ms.getHost();
      if (!hostsList.contains(host) && !ms.isLocator()) {
        hostsList.add(host);
        totalCpuActive += ms.getCpuActive();
        cpuCount++;
      }

      sumJvmUsedMemory += ms.getJvmUsedMemory();
      sumHeapStoragePoolUsed += ms.getHeapStoragePoolUsed();
      sumHeapExecutionPoolUsed += ms.getHeapExecutionPoolUsed();
      sumHeapMemoryUsed += ms.getHeapMemoryUsed();
      sumOffHeapStoragePoolUsed += ms.getOffHeapStoragePoolUsed();
      sumOffHeapExecutionPoolUsed += ms.getOffHeapExecutionPoolUsed();
      sumOffHeapMemoryUsed += ms.getOffHeapMemoryUsed();
      sumDiskStoreDiskSpace += ms.getDiskStoreDiskSpace();
    }

    sumAggrMemoryUsed = sumHeapMemoryUsed + sumOffHeapMemoryUsed;

    synchronized (this.timeLine) {
      this.timeLine.add(lastMemberUpdatedTime);
    }

    synchronized (this.cpuUsageTrend) {
      this.cpuUsageTrend.add(totalCpuActive / cpuCount);
    }

    synchronized (this.jvmUsageTrend) {
      this.jvmUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumJvmUsedMemory, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.heapStoragePoolUsageTrend) {
      this.heapStoragePoolUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumHeapStoragePoolUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.heapExecutionPoolUsageTrend) {
      this.heapExecutionPoolUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumHeapExecutionPoolUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.heapUsageTrend) {
      this.heapUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumHeapMemoryUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.offHeapStoragePoolUsageTrend) {
      this.offHeapStoragePoolUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumOffHeapStoragePoolUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.offHeapExecutionPoolUsageTrend) {
      this.offHeapExecutionPoolUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumOffHeapExecutionPoolUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.offHeapUsageTrend) {
      this.offHeapUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumOffHeapMemoryUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.aggrMemoryUsageTrend) {
      this.aggrMemoryUsageTrend.add(
          SnappyUtils.bytesToGivenUnits(sumAggrMemoryUsed, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

    synchronized (this.diskStoreDiskSpaceTrend) {
      this.diskStoreDiskSpaceTrend.add(
          SnappyUtils.bytesToGivenUnits(sumDiskStoreDiskSpace, SnappyUtils.STORAGE_SIZE_UNIT_GB));
    }

  }

  public int getTotalCores() {
    return totalCores;
  }

  public int getLocatorCores() {
    return locatorCores;
  }

  public int getLeadCores() {
    return leadCores;
  }

  public int getDataServerCores() {
    return dataServerCores;
  }

  public Object[] getUsageTrends(int trendType) {
    Object[] returnArray = null;
    switch (trendType) {
      case TREND_TIMELINE:
        returnArray = this.timeLine.toArray();
        break;
      case TREND_CPU_USAGE:
        returnArray = this.cpuUsageTrend.toArray();
        break;
      case TREND_JVM_HEAP_USAGE:
        returnArray = this.jvmUsageTrend.toArray();
        break;
      case TREND_HEAP_USAGE:
        returnArray = this.heapUsageTrend.toArray();
        break;
      case TREND_HEAP_STORAGE_USAGE:
        returnArray = this.heapStoragePoolUsageTrend.toArray();
        break;
      case TREND_HEAP_EXECUTION_USAGE:
        returnArray = this.heapExecutionPoolUsageTrend.toArray();
        break;
      case TREND_OFFHEAP_USAGE:
        returnArray = this.offHeapUsageTrend.toArray();
        break;
      case TREND_OFFHEAP_STORAGE_USAGE:
        returnArray = this.offHeapStoragePoolUsageTrend.toArray();
        break;
      case TREND_OFFHEAP_EXECUTION_USAGE:
        returnArray = this.offHeapExecutionPoolUsageTrend.toArray();
        break;
      case TREND_AGGR_MEMORY_USAGE:
        returnArray = this.aggrMemoryUsageTrend.toArray();
        break;
      case TREND_DISKSTORE_DISKSPACE_USAGE:
        returnArray = this.diskStoreDiskSpaceTrend.toArray();
        break;
    }

    return returnArray;
  }
}
