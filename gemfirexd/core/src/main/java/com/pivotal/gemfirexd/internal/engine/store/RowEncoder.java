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
package com.pivotal.gemfirexd.internal.engine.store;

import java.util.Map;

import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * Conversions of a region key-value to/from {@link ExecRow}.
 */
public interface RowEncoder {

  /**
   * Encode the key-value to an {@link ExecRow}.
   */
  ExecRow toRow(Object rawKey, Object value, GemFireContainer container);

  /**
   * Decode the key-value from a row as encoded by {@link #toRow}.
   */
  Map.Entry<RegionKey, Object> fromRow(DataValueDescriptor[] row,
      GemFireContainer container);

  /**
   * Decode the key from key columns as encoded by {@link #toRow}.
   */
  RegionKey fromRowToKey(DataValueDescriptor[] key, GemFireContainer container);

  /**
   * An interface to do some common pre-processing (e.g. set common UUID key)
   * for a set of rows in a bulk/batch insert.
   */
  PreProcessRow getPreProcessorForRows(GemFireContainer container);

  /**
   * Any actions required after column store put operations which happen
   * outside of entry locks (unlike a CacheListener)
   */
  void afterColumnStorePuts(BucketRegion bucket, EntryEventImpl[] events);

  /**
   * An interface to do some common pre-processing for bulk/batch inserts.
   */
  interface PreProcessRow {

    /**
     * Preprocess the current row and return the result.
     */
    DataValueDescriptor[] preProcess(DataValueDescriptor[] row);
  }
}
