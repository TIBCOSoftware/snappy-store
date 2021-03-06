/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
package com.gemstone.gemfire.internal.offheap;

import com.gemstone.gemfire.compression.Compressor;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.ChunkType;

/**
 * Basic contract for a heap that manages off heap memory. Any MemoryChunks allocated from a heap
 * are returned to that heap when freed.
 * 
 * @author darrel
 *
 */
public interface MemoryAllocator {
  /**
   * 
   * @param size the size in bytes of the chunk of memory to allocate
   * @param chunkType TODO
   * @return the allocated chunk of memory.
   * @throws IllegalStateException if the heap does not have enough memory to grant the request
   */
  public MemoryChunk allocate(int size, ChunkType chunkType);
  
  /**
   * Allocates off heap memory for the given data and returns a MemoryChunk
   * that is backed by this allocated memory and that contains the data.
   * @param data the bytes of the data to put in the allocated CachedDeserializable
   * @param isSerialized true if data contains a serialized object; false if it is an actual byte array.
   * @param isCompressed true if data is compressed; false if it is uncompressed.
   * @param chunkType TODO
   * @throws IllegalStateException if the heap does not have enough memory to grant the request
   */
  public StoredObject allocateAndInitialize(byte[] data, boolean isSerialized, boolean isCompressed, ChunkType chunkType);
  
  public long getFreeMemory();
  
  public long getUsedMemory();

  public long getTotalMemory();

  public OffHeapMemoryStats getStats();

  /**
   * This allocator will no longer be used so free up any system memory that belongs to it.
   */
  public void close();

  /**
   * Returns the off heap memory Compressor. Null if off heap memory is not compressed.
   */
  public Compressor getCompressor();
  /**
   * Sets the Compressor to be used by off heap memory.
   * @throws IllegalStateException if more than one code is set
   */
  public void setCompressor(Compressor compressor);
  
  public MemoryInspector getMemoryInspector();
  
  public void addMemoryUsageListener(MemoryUsageListener listener);
  
  public void removeMemoryUsageListener(MemoryUsageListener listener);
}
