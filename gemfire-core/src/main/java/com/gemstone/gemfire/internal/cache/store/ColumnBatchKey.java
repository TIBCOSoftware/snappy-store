/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package com.gemstone.gemfire.internal.cache.store;

import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;

/**
 * Interface for a key object in the column store.
 */
public abstract class ColumnBatchKey implements Sizeable {

  /**
   * Get the number of rows in this column batch.
   * This will return a non-zero result only for the STATS keys while
   * for a key of DELETE bitmask it will return negative value
   * indicating the delete count.
   */
  public abstract int getColumnBatchRowCount(BucketRegion bucketRegion,
      SerializedDiskBuffer value);
}
