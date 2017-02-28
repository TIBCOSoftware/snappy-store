package com.gemstone.gemfire.internal.snappy;


public class UMMMemoryTracker {


  private long ID;
  private long memoryUsed = 0L;

  private boolean firstAllocation = true;


  private String firstAllocationObject;

  private int totalOperationsExpected;
  private int totalMemoryAllocated;

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
  public int getTotalMemoryAllocated() {
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
