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
import com.gemstone.gemfire.internal.shared.SystemProperties;

/**
 * Provides important contextual information that allows a {@link RegionEntry} to manage its state.
 * @author rholmes
 * @since 7.5
 */
public interface RegionEntryContext extends HasCachePerfStats {

  String DEFAULT_COMPRESSION_PROVIDER =
      "com.gemstone.gemfire.compression.SnappyCompressor";

  boolean COMPRESSION_ENABLED = SystemProperties.getServerInstance().getBoolean(
      "compression.enable", !GemFireCacheImpl.gfxdSystem() &&
          !SystemProperties.isUsingGemFireXDEntryPoint());

  /**
   * Returns the compressor to be used by this region entry when storing the
   * entry value.
   * 
   * @return null if no compressor is assigned or available for the entry.
   */
  public Compressor getCompressor();

  /**
   * Returns the name for compression codec to be used for column store.
   * Null is returned if no codec is defined or node has not initialized.
   */
  public String getColumnCompressionCodec();

  /** Get the full path of the corresponding region */
  public String getFullPath();

  /**
   * Returns true if region entries are stored off heap.
   */
  public boolean getEnableOffHeapMemory();
  
  /**
   * Returns true if this region is persistent.
   */
  public boolean isBackup();

  public void updateMemoryStats(Object oldValue, Object newValue);
}
