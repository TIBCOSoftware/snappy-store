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

package com.gemstone.gemfire.internal.snappy;

import java.net.URLClassLoader;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;
import com.gemstone.gemfire.internal.snappy.memory.MemoryManagerStats;

public abstract class CallbackFactoryProvider {

  // no-op implementation.
  private static StoreCallbacks storeCallbacks = new StoreCallbacks() {

    @Override
    public void registerTypes() {
    }

    @Override
    public Set<Object> createColumnBatch(BucketRegion region, long batchID,
        int bucketID) {
      return null;
    }

    @Override
    public void invokeColumnStorePutCallbacks(BucketRegion bucket,
        EntryEventImpl[] events) {
    }

    @Override
    public List<String> getInternalTableSchemas() {
      return Collections.emptyList();
    }

    @Override
    public boolean isColumnTable(String qualifiedName) {
      return false;
    }

    @Override
    public boolean skipEvictionForEntry(LRUEntry entry) {
      return false;
    }

    @Override
    public int getHashCodeSnappy(Object dvd, int numPartitions) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public int getHashCodeSnappy(Object[] dvds, int numPartitions) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public String columnBatchTableName(String tableName) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public CloseableIterator<ColumnTableEntry> columnTableScan(
        String columnTable, int[] projection, byte[] serializedFilters,
        Set<Integer> bucketIds) throws SQLException {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public void registerCatalogSchemaChange() {
    }

    @Override
    public Object getSnappyTableStats() {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public int getLastIndexOfRow(Object o) {
      throw new UnsupportedOperationException("unexpected invocation for "
          + toString());
    }

    @Override
    public boolean acquireStorageMemory(String name, long numBytes,
        UMMMemoryTracker buffer, boolean shouldEvict, boolean offHeap) {
      return true;
    }

    @Override
    public void releaseStorageMemory(String objectName,
        long numBytes, boolean offHeap) {
    }

    @Override
    public void dropStorageMemory(String objectName, long ignoreBytes) {

    }

    @Override
    public void waitForRuntimeManager(long maxWaitMillis) {
    }

    @Override
    public boolean isSnappyStore() {
      return false;
    }


    @Override
    public void resetMemoryManager() {

    }

    @Override
    public long getStoragePoolUsedMemory(boolean offHeap) {
      return 0;
    }

    @Override
    public long getStoragePoolSize(boolean offHeap) {
      return 0;
    }

    @Override
    public long getExecutionPoolUsedMemory(boolean offHeap) {
      return 0;
    }

    @Override
    public long getExecutionPoolSize(boolean offHeap) {
      return 0;
    }

    @Override
    public boolean shouldStopRecovery() {
      return false;
    }

    @Override
    public long getOffHeapMemory(String objectName) {
      return 0L;
    }

    @Override
    public boolean hasOffHeap() {
      return false;
    }

    @Override
    public void logMemoryStats() {
    }

    @Override
    public void initMemoryStats(MemoryManagerStats stats) {
    }

    @Override
    public void clearConnectionPools() {
    }

    @Override
    public URLClassLoader getLeadClassLoader() { return null; }

    @Override
    public void clearSessionCache(boolean onlyQueryPlanCache) {}

    @Override
    public void refreshPolicies(String ldapGroup) {
    }

    @Override
    public String checkSchemaPermission(String schema, String currentUser) {
      return null;
    }

    @Override
    public void removeSampler(String sampleTableName) {
    }
  };

  public static void setStoreCallbacks(StoreCallbacks cb) {
    storeCallbacks = cb;
  }

  public static StoreCallbacks getStoreCallbacks() {
    return storeCallbacks;
  }
}
