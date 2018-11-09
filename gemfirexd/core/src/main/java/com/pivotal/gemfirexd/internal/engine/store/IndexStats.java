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

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.StatisticsTypeFactoryImpl;

public class IndexStats {
  public static final String typeName = "IndexUsageStats";

  private static final StatisticsType type;

  /** Index used for point lookup essentially when start and end key are same*/
  protected static int pointLookupId;

  /** Index used for subMap when start key and end key are different */
  protected static int indexSubmapId;

  protected static final String INDEX_SCAN_POINT_LOOKUP = "IndexPointLookup";

  protected static final String INDEX_SCAN_RANGE = "IndexRangeScan";

  /**
   * Static initializer to create and initialize the <code>StatisticsType</code>
   */
  static {

    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();

    type = f.createType(
        typeName,
        "Stats for checking index effectiveness",
        new StatisticDescriptor[] {
            f.createLongCounter(INDEX_SCAN_POINT_LOOKUP,
                "Number of Times index used for point lookups.",
                "operations", true),
            f.createLongCounter(INDEX_SCAN_RANGE,
                "Number of Times index used for range lookups.",
                "operations", true) });

    // Initialize id fields
    pointLookupId = type.nameToId(INDEX_SCAN_POINT_LOOKUP);
    indexSubmapId = type.nameToId(INDEX_SCAN_RANGE);

  }

  private Statistics stats;

  public IndexStats(DistributedSystem dsys, String indexName) {
    this.stats = dsys.createAtomicStatistics(type, indexName);
  }

  public void close() {
    this.stats.close();
  }

  public void incPointLookupStats() {
    this.stats.incLong(pointLookupId, 1);
  }

  public void incScanStats() {
    this.stats.incLong(indexSubmapId, 1);
  }
}
