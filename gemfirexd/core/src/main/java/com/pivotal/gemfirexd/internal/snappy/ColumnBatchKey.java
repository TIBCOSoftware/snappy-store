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
package com.pivotal.gemfirexd.internal.snappy;

import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;

/**
 * Interface for a key object in the column store.
 */
public interface ColumnBatchKey extends Sizeable {

  /**
   * Get the number of columns defined for the given
   * column table (qualified name).
   */
  int getNumColumnsInTable(GemFireContainer columnTable);

  /**
   * Get the number of rows in this column batch.
   */
  int getColumnBatchRowCount(BucketRegion bucketRegion, RegionEntry re,
      int numColumnsInTable);
}
