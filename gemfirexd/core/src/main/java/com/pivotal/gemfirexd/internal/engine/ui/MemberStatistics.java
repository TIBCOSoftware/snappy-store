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

import java.util.HashMap;
import java.util.UUID;

import org.apache.commons.collections4.queue.CircularFifoQueue;

public class MemberStatistics {

  public static final int MAX_SAMPLE_SIZE = 180;

  // Date in TimeMillis
  private long lastUpdatedOn;

  private String id;
  private String name;
  private String host;
  private String userDir;
  private String logFile;
  private String processId;
  private String diskStoreName;
  private UUID diskStoreUUID;

  private boolean isLocator;
  private boolean isLead;
  private boolean isLeadActive;
  private boolean isDataServer;

  private int cpuActive;
  private long clientsCount;
  private String status;

  private long jvmMaxMemory;
  private long jvmFreeMemory;
  private long jvmTotalMemory;
  private long jvmUsedMemory;

  private long heapStoragePoolUsed;
  private long heapStoragePoolSize;
  private long heapExecutionPoolUsed;
  private long heapExecutionPoolSize;
  private long heapMemoryUsed;
  private long heapMemorySize;

  private long offHeapStoragePoolUsed;
  private long offHeapStoragePoolSize;
  private long offHeapExecutionPoolUsed;
  private long offHeapExecutionPoolSize;
  private long offHeapMemoryUsed;
  private long offHeapMemorySize;

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


  public MemberStatistics(String id, String name, String diskStoreName, UUID diskStoreUUID) {
    this.id = id;
    this.name = name;
    this.diskStoreName = diskStoreName;
    this.diskStoreUUID = diskStoreUUID;
  }

  public MemberStatistics(HashMap memberStatsMap) {
    this.id = (String)memberStatsMap.get("id");
    this.name = (String)memberStatsMap.get("name");
    this.diskStoreName = (String)memberStatsMap.get("diskStoreName");
    this.diskStoreUUID = (UUID)memberStatsMap.get("diskStoreUUID");

    // update rest of the fields
    updateMemberStatistics(memberStatsMap);
  }

  public void updateMemberStatistics(HashMap memberStatsMap){

    if(!this.getDiskStoreUUID().equals(memberStatsMap.get("diskStoreUUID"))) {
      // wrong member stats passed
      return;
    }

    long lastUpdatedOn = (long)memberStatsMap.get("lastUpdatedOn");
    this.lastUpdatedOn = lastUpdatedOn;
    this.timeLine.add(lastUpdatedOn);

    this.host = (String)memberStatsMap.get("host");
    this.userDir = (String)memberStatsMap.get("userDir");
    this.logFile = (String)memberStatsMap.get("logFile");
    this.processId = (String)memberStatsMap.get("processId");

    this.isLocator = (boolean)memberStatsMap.get("locator");
    this.isLead = (boolean)memberStatsMap.get("lead");
    this.isLeadActive = (boolean)memberStatsMap.get("activeLead");
    this.isDataServer = (boolean)memberStatsMap.get("dataServer");
    this.clientsCount = (long)memberStatsMap.get("clients");

    int cpuActive = (int)memberStatsMap.get("cpuActive");
    this.cpuActive = cpuActive;
    this.cpuUsageTrend.add(cpuActive);

    long jvmTotalMemory = (long)memberStatsMap.get("totalMemory");
    long jvmUsedMemory = (long)memberStatsMap.get("usedMemory");
    this.jvmTotalMemory = jvmTotalMemory;
    this.jvmUsedMemory = jvmUsedMemory;
    this.jvmMaxMemory = (long)memberStatsMap.get("maxMemory");
    this.jvmFreeMemory = (long)memberStatsMap.get("freeMemory");

    if(jvmTotalMemory > 0) {
      this.jvmUsageTrend.add(((double)jvmUsedMemory * 100) / jvmTotalMemory);
    } else {
      this.jvmUsageTrend.add(0.0);
    }

    long heapStoragePoolUsed = (long)memberStatsMap.get("heapStoragePoolUsed");
    long heapStoragePoolSize = (long)memberStatsMap.get("heapStoragePoolSize");
    long heapExecutionPoolUsed = (long)memberStatsMap.get("heapExecutionPoolUsed");
    long heapExecutionPoolSize = (long)memberStatsMap.get("heapExecutionPoolSize");
    long heapMemoryUsed = (long)memberStatsMap.get("heapMemoryUsed");
    long heapMemorySize = (long)memberStatsMap.get("heapMemorySize");

    this.heapStoragePoolUsed = heapStoragePoolUsed;
    this.heapStoragePoolSize = heapStoragePoolSize;
    this.heapExecutionPoolUsed= heapExecutionPoolUsed;
    this.heapExecutionPoolSize = heapExecutionPoolSize;
    this.heapMemoryUsed = heapMemoryUsed;
    this.heapMemorySize = heapMemorySize;

    if (heapStoragePoolSize > 0) {
      this.heapStoragePoolUsageTrend.add(
          ((double)heapStoragePoolUsed * 100) / heapStoragePoolSize);
    } else {
      this.heapStoragePoolUsageTrend.add(0.0);
    }

    if (heapExecutionPoolSize > 0) {
      this.heapExecutionPoolUsageTrend.add(
          ((double)heapExecutionPoolUsed * 100) / heapExecutionPoolSize);
    } else {
      this.heapExecutionPoolUsageTrend.add(0.0);
    }

    if (heapMemorySize > 0) {
      this.heapUsageTrend.add(((double)heapMemoryUsed * 100) / heapMemorySize);
    } else {
      this.heapUsageTrend.add(0.0);
    }

    long offHeapStoragePoolUsed = (long)memberStatsMap.get("offHeapStoragePoolUsed");
    long offHeapStoragePoolSize = (long)memberStatsMap.get("offHeapStoragePoolSize");
    long offHeapExecutionPoolUsed = (long)memberStatsMap.get("offHeapExecutionPoolUsed");
    long offHeapExecutionPoolSize = (long)memberStatsMap.get("offHeapExecutionPoolSize");
    long offHeapMemoryUsed = (long)memberStatsMap.get("offHeapMemoryUsed");
    long offHeapMemorySize = (long)memberStatsMap.get("offHeapMemorySize");

    this.offHeapStoragePoolUsed = offHeapStoragePoolUsed;
    this.offHeapStoragePoolSize = offHeapStoragePoolSize;
    this.offHeapExecutionPoolUsed = offHeapExecutionPoolUsed;
    this.offHeapExecutionPoolSize = offHeapExecutionPoolSize;
    this.offHeapMemoryUsed = offHeapMemoryUsed;
    this.offHeapMemorySize = offHeapMemorySize;

    if (offHeapStoragePoolSize > 0) {
      this.offHeapStoragePoolUsageTrend.add(
          ((double)offHeapStoragePoolUsed * 100) / offHeapStoragePoolSize);
    } else {
      this.offHeapStoragePoolUsageTrend.add(0.0);
    }

    if (offHeapExecutionPoolSize > 0) {
      this.offHeapExecutionPoolUsageTrend.add(
          ((double)offHeapExecutionPoolUsed * 100) / offHeapExecutionPoolSize);
    } else {
      this.offHeapExecutionPoolUsageTrend.add(0.0);
    }

    if (offHeapMemorySize > 0) {
      this.offHeapUsageTrend.add(((double)offHeapMemoryUsed * 100) / offHeapMemorySize);
    } else {
      this.offHeapUsageTrend.add(0.0);
    }

  }

  public long getLastUpdatedOn() {
    return lastUpdatedOn;
  }

  public void setLastUpdatedOn(long lastUpdatedOn) {
    this.lastUpdatedOn = lastUpdatedOn;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getUserDir() {
    return userDir;
  }

  public void setUserDir(String userDir) {
    this.userDir = userDir;
  }

  public String getLogFile() {
    return logFile;
  }

  public void setLogFile(String logFile) {
    this.logFile = logFile;
  }

  public String getProcessId() {
    return processId;
  }

  public void setProcessId(String processId) {
    this.processId = processId;
  }

  public String getDiskStoreName() {
    return diskStoreName;
  }

  public void setDiskStoreName(String diskStoreName) {
    this.diskStoreName = diskStoreName;
  }

  public UUID getDiskStoreUUID() {
    return diskStoreUUID;
  }

  public void setDiskStoreUUID(UUID diskStoreUUID) {
    this.diskStoreUUID = diskStoreUUID;
  }

  public boolean isLocator() {
    return isLocator;
  }

  public void setLocator(boolean locator) {
    isLocator = locator;
  }

  public boolean isLead() {
    return isLead;
  }

  public void setLead(boolean lead) {
    isLead = lead;
  }

  public boolean isLeadActive() {
    return isLeadActive;
  }

  public void setLeadActive(boolean leadActive) {
    isLeadActive = leadActive;
  }

  public boolean isDataServer() {
    return isDataServer;
  }

  public void setDataServer(boolean dataServer) {
    isDataServer = dataServer;
  }

  public int getCpuActive() {
    return cpuActive;
  }

  public void setCpuActive(int cpuActive) {
    this.cpuActive = cpuActive;
  }

  public long getClientsCount() {
    return clientsCount;
  }

  public void setClientsCount(long clientsCount) {
    this.clientsCount = clientsCount;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public long getJvmMaxMemory() {
    return jvmMaxMemory;
  }

  public void setJvmMaxMemory(long jvmMaxMemory) {
    this.jvmMaxMemory = jvmMaxMemory;
  }

  public long getJvmFreeMemory() {
    return jvmFreeMemory;
  }

  public void setJvmFreeMemory(long jvmFreeMemory) {
    this.jvmFreeMemory = jvmFreeMemory;
  }

  public long getJvmTotalMemory() {
    return jvmTotalMemory;
  }

  public void setJvmTotalMemory(long jvmTotalMemory) {
    this.jvmTotalMemory = jvmTotalMemory;
  }

  public long getJvmUsedMemory() {
    return jvmUsedMemory;
  }

  public void setJvmUsedMemory(long jvmUsedMemory) {
    this.jvmUsedMemory = jvmUsedMemory;
  }

  public long getHeapStoragePoolUsed() {
    return heapStoragePoolUsed;
  }

  public void setHeapStoragePoolUsed(long heapStoragePoolUsed) {
    this.heapStoragePoolUsed = heapStoragePoolUsed;
  }

  public long getHeapStoragePoolSize() {
    return heapStoragePoolSize;
  }

  public void setHeapStoragePoolSize(long heapStoragePoolSize) {
    this.heapStoragePoolSize = heapStoragePoolSize;
  }

  public long getHeapExecutionPoolUsed() {
    return heapExecutionPoolUsed;
  }

  public void setHeapExecutionPoolUsed(long heapExecutionPoolUsed) {
    this.heapExecutionPoolUsed = heapExecutionPoolUsed;
  }

  public long getHeapExecutionPoolSize() {
    return heapExecutionPoolSize;
  }

  public void setHeapExecutionPoolSize(long heapExecutionPoolSize) {
    this.heapExecutionPoolSize = heapExecutionPoolSize;
  }

  public long getHeapMemoryUsed() {
    return heapMemoryUsed;
  }

  public void setHeapMemoryUsed(long heapMemoryUsed) {
    this.heapMemoryUsed = heapMemoryUsed;
  }

  public long getHeapMemorySize() {
    return heapMemorySize;
  }

  public void setHeapMemorySize(long heapMemorySize) {
    this.heapMemorySize = heapMemorySize;
  }

  public long getOffHeapStoragePoolUsed() {
    return offHeapStoragePoolUsed;
  }

  public void setOffHeapStoragePoolUsed(long offHeapStoragePoolUsed) {
    this.offHeapStoragePoolUsed = offHeapStoragePoolUsed;
  }

  public long getOffHeapStoragePoolSize() {
    return offHeapStoragePoolSize;
  }

  public void setOffHeapStoragePoolSize(long offHeapStoragePoolSize) {
    this.offHeapStoragePoolSize = offHeapStoragePoolSize;
  }

  public long getOffHeapExecutionPoolUsed() {
    return offHeapExecutionPoolUsed;
  }

  public void setOffHeapExecutionPoolUsed(long offHeapExecutionPoolUsed) {
    this.offHeapExecutionPoolUsed = offHeapExecutionPoolUsed;
  }

  public long getOffHeapExecutionPoolSize() {
    return offHeapExecutionPoolSize;
  }

  public void setOffHeapExecutionPoolSize(long offHeapExecutionPoolSize) {
    this.offHeapExecutionPoolSize = offHeapExecutionPoolSize;
  }

  public long getOffHeapMemoryUsed() {
    return offHeapMemoryUsed;
  }

  public void setOffHeapMemoryUsed(long offHeapMemoryUsed) {
    this.offHeapMemoryUsed = offHeapMemoryUsed;
  }

  public long getOffHeapMemorySize() {
    return offHeapMemorySize;
  }

  public void setOffHeapMemorySize(long offHeapMemorySize) {
    this.offHeapMemorySize = offHeapMemorySize;
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
