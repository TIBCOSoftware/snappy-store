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

package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.compression.Compressor;

/**
 * Provides important contextual information that allows a {@link RegionEntry} to manage its state.
 * @author rholmes
 * @since 7.5
 */
public interface RegionEntryContext extends HasCachePerfStats {
  public static final String DEFAULT_COMPRESSION_PROVIDER="com.gemstone.gemfire.compression.SnappyCompressor";
  
  /**
   * Returns the compressor to be used by this region entry when storing the
   * entry value.
   * 
   * @return null if no compressor is assigned or available for the entry.
   */
  public Compressor getCompressor();
  
  /**
   * Returns true if region entries are stored off heap.
   */
  public boolean getEnableOffHeapMemory();
  
  /**
   * Returns true if this region is persistent.
   */
  public boolean isBackup();

  default void updateMemoryStats(Object oldValue, Object newValue) {
    // only used by BucketRegion as of now
  }

  default public void incInMemoryKeySize(Object key) {
  }

  default public void decInMemoryKeySize(Object key) {
  }
}
