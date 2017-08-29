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
package com.gemstone.gemfire.internal.snappy;

/**
 * This class is used for bulk inserts on row tables/ row buffer.
 * Using this class UMM can make an initial estimate of how much memory will be needed
 * in a putAll operation. This estimation is done during first row insertion.
 * For subsequent puts UMM is not referred , instead this tracker is used to allocate memory.
 * At the end of operation all unused mmemory is released to UMM.
 */
public class UMMMemoryTracker {


  private long ID;
  private long memoryUsed = 0L;

  private boolean firstAllocation = true;


  private String firstAllocationObject;

  private int totalOperationsExpected;
  private long totalMemoryAllocated = 0L;

  public UMMMemoryTracker(long ID, int totalOperationsExpected){
    this.ID = ID;
    this.totalOperationsExpected = totalOperationsExpected;
  }

  public long getID() {
    return ID;
  }


  public void setMemoryUsed(long memoryUsed) {
    this.memoryUsed = memoryUsed;
  }
  public long getTotalMemoryAllocated() {
    return totalMemoryAllocated;
  }
  public void setTotalMemoryAllocated(int totalMemoryAllocated) {
    this.totalMemoryAllocated = totalMemoryAllocated;
  }

  public long getMemoryUsed() {
    return memoryUsed;
  }

  public long freeMemory(){
    return totalMemoryAllocated - memoryUsed;
  }

  public void incMemoryUsed(long numBytes){
    this.memoryUsed += numBytes;
    this.totalOperationsExpected--;
    if (this.totalOperationsExpected < 0) {
      System.out.println("Unexpected negative");
    }
  }

  public void incAllocatedMemory(long numBytes){
    this.totalMemoryAllocated += numBytes;
  }

  public int getTotalOperationsExpected() {
    return totalOperationsExpected;
  }

  public boolean isFirstAllocation() {
    return firstAllocation;
  }

  public void setFirstAllocation(boolean firstAllocation) {
    this.firstAllocation = firstAllocation;
  }

  public String getFirstAllocationObject() {
    return firstAllocationObject;
  }

  public void setFirstAllocationObject(String firstAllocationObject) {
    this.firstAllocationObject = firstAllocationObject;
  }
}
