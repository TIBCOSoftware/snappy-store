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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;

public interface StoreCallbacks {

  String SHADOW_TABLE_SUFFIX = "_COLUMN_STORE_";

  Set<Object> createCachedBatch(BucketRegion region, UUID batchID,
      int bucketID);

  List<String> getInternalTableSchemas();

  int getHashCodeSnappy(Object dvd, int numPartitions);

  int getHashCodeSnappy(Object dvds[], int numPartitions);

  public String cachedBatchTableName(String tableName);

  public String snappyInternalSchemaName();

  void invalidateReplicatedTableCache(LocalRegion region);

  void cleanUpCachedObjects(String table, Boolean sentFromExternalCluster);

  void registerRelationDestroyForHiveStore();
}
