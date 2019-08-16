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

package com.gemstone.gemfire.internal.snappy;

import java.net.URLClassLoader;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.snappy.memory.MemoryManagerStats;

public interface StoreCallbacks {

  String SHADOW_TABLE_SUFFIX = SystemProperties.SHADOW_TABLE_SUFFIX;

  String SHADOW_TABLE_BUCKET_TAG = SHADOW_TABLE_SUFFIX.replace("_", "__");

  void registerTypes();

  Set<Object> createColumnBatch(BucketRegion region, long batchID,
      int bucketID);

  void invokeColumnStorePutCallbacks(BucketRegion bucket, EntryEventImpl[] events);

  List<String> getInternalTableSchemas();

  boolean isColumnTable(String qualifiedName);

  boolean skipEvictionForEntry(LRUEntry entry);

  int getHashCodeSnappy(Object dvd, int numPartitions);

  int getHashCodeSnappy(Object dvds[], int numPartitions);

  String columnBatchTableName(String tableName);

  /**
   * Scan the entries of a column table. The returned value in ColumnTableEntry
   * will have reference count incremented, so caller should decrement once done.
   */
  CloseableIterator<ColumnTableEntry> columnTableScan(String qualifiedTable,
      int[] projection, byte[] serializedFilters,
      Set<Integer> bucketIds) throws SQLException;

  void registerCatalogSchemaChange();

  Object getSnappyTableStats();

  int getLastIndexOfRow(Object o);

  /**
   * Heap allocation calls will happen when creating region entries (and other
   *   places as required) and clearing them. Off-heap allocations and release
   * will be controlled by BufferAllocator. Reason being that it can track
   * the precise amount of memory still allocated (e.g. chunks removed from
   *   region still in use by iterators), can trigger a JVM reference collection
   * if memory is reaching threshold and can integrate with Spark off-heap
   * allocations which do not use UnsafeHolder.
   *
   * Bottom-line is that GemFire code that needs to use off-heap need not worry
   * about explicit calls to this layer and GemFireCacheImpl.getBufferAllocator
   * will do the accounting (so GemFire code should *never* be invoking
   *   store/release calls with offHeap=true).
   */
  boolean acquireStorageMemory(String objectName, long numBytes,
      UMMMemoryTracker buffer, boolean shouldEvict, boolean offHeap);

  void releaseStorageMemory(String objectName, long numBytes, boolean offHeap);

  void dropStorageMemory(String objectName, long ignoreBytes);

  /** wait for runtime manager to initialize and get set in callbacks */
  void waitForRuntimeManager(long maxWaitMillis);

  boolean isSnappyStore();

  void resetMemoryManager();

  long getStoragePoolUsedMemory(boolean offHeap);
  long getStoragePoolSize(boolean offHeap);
  long getExecutionPoolUsedMemory(boolean offHeap);
  long getExecutionPoolSize(boolean offHeap);

  boolean shouldStopRecovery();

  /**
   * Get the number of bytes used for off-heap storage for given object name.
   */
  long getOffHeapMemory(String objectName);

  /**
   * Returns true if system has off-heap configuration.
   */
  boolean hasOffHeap();

  /**
   * Log the used memory breakdown as maintained by the MemoryManager.
   */
  void logMemoryStats();

  /**
   * Initializes different memory manager related stats
   */
  void initMemoryStats(MemoryManagerStats stats);

  /**
   * Clear any existing connection pools (forced in case of major
   * authentication service changes, for example).
   */
  void clearConnectionPools();

  /**
   * Get the class loader of the lead
   */
  URLClassLoader getLeadClassLoader();

  /**
   * Clear SnappySession cache
   * @param onlyQueryPlanCache
   */
  void clearSessionCache(boolean onlyQueryPlanCache);

  /**
   * drop and recreate those policies who are affected by this ldap group
   * @param ldapGroup
   */
  void refreshPolicies(String ldapGroup);

  /**
   * Check permission for current user on given schema.
   */
  String checkSchemaPermission(String schema, String currentUser);

  /**
   * Removes the sampler from global map for the given sample table.
   * @param resolvedSampleTableName
   */
  void removeSampler(String resolvedSampleTableName);
}
