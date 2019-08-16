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


public interface MemoryManagerStatsOps {


  public void incStoragePoolSize(boolean offHeap, long delta);

  public long getStoragePoolSize(boolean offHeap);

  public void decStoragePoolSize(boolean offHeap, long delta);

  public void incExecutionPoolSize(boolean offHeap, long delta);

  public void decExecutionPoolSize(boolean offHeap, long delta);

  public long getExecutionPoolSize(boolean offHeap);

  public void incStorageMemoryUsed(boolean offHeap, long delta);

  public void decStorageMemoryUsed(boolean offHeap, long delta);

  public long getStorageMemoryUsed(boolean offHeap);

  public void incExecutionMemoryUsed(boolean offHeap, long delta);

  public void decExecutionMemoryUsed(boolean offHeap, long delta);

  public long getExecutionMemoryUsed(boolean offHeap);

  public void incNumFailedStorageRequest(boolean offHeap);

  public int getNumFailedStorageRequest(boolean offHeap);

  public void incNumFailedExecutionRequest(boolean offHeap);

  public int getNumFailedExecutionRequest(boolean offHeap);

  public void incNumFailedEvictionRequest(boolean offHeap);

  public int getNumFailedEvictionRequest(boolean offHeap);

  public void incMaxStorageSize(boolean offHeap, long delta);

  public long getMaxStorageSize(boolean offHeap);
}
