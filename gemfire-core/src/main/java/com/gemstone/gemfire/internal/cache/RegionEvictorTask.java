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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.internal.cache.lru.HeapEvictor;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * 
 * Takes delta to be evicted and tries to evict the least no of LRU entry which
 * would make evictedBytes more than or equal to the delta
 * 
 * @author Suranjan, Amardeep, Swapnil, Yogesh
 * @since 6.0
 * 
 */
public class RegionEvictorTask implements Callable<Object> {
  
  private static final int EVICTION_BURST_PAUSE_TIME_MILLIS;

  public static int TEST_EVICTION_BURST_PAUSE_TIME_MILLIS = Integer.MAX_VALUE;

  static {
    EVICTION_BURST_PAUSE_TIME_MILLIS = Integer.getInteger(
        "gemfire.evictionBurstPauseTimeMillis", 1000);
  }

  private static volatile long lastTaskCompletionTime = 0;

  public static void setLastTaskCompletionTime(long v) {
    lastTaskCompletionTime = v;
  }
  public static long getLastTaskCompletionTime() {
    return lastTaskCompletionTime;
  }

  private List<LocalRegion> regionSet;

  private final HeapEvictor evictor;

  private final long bytesToEvictPerTask ; 
  
  public RegionEvictorTask(List<LocalRegion> regionSet, HeapEvictor evictor, long bytesToEvictPerTask) {
    this.evictor = evictor;
    this.regionSet = regionSet;
    this.bytesToEvictPerTask = bytesToEvictPerTask;
  }

  
  public List<LocalRegion> getRegionList() {
    synchronized (this.regionSet) {
      return this.regionSet;
    }
  }

  private GemFireCacheImpl getGemFireCache() {
    return getHeapEvictor().getGemFireCache();
  }

  private HeapEvictor getHeapEvictor() {
    return this.evictor;
  }

  public Object call() throws Exception {
    final CachePerfStats stats = getGemFireCache().getCachePerfStats();
    stats.incEvictorJobsStarted();
    long bytesEvicted;
    long totalBytesEvicted = 0;
    final long start = CachePerfStats.getStatTime();
    try {
      while (true) {
        synchronized (this.regionSet) {
          if (this.regionSet.isEmpty()) {
            lastTaskCompletionTime = System.currentTimeMillis();
            return null;
          }
          // TODO: Yogesh : try Fisher-Yates shuffle algorithm
          Iterator<LocalRegion> iter = regionSet.iterator();
          while (iter.hasNext()) {
            LocalRegion region = iter.next();
            try {
              bytesEvicted = ((AbstractLRURegionMap)region.entries)
                  .centralizedLruUpdateCallback(false, true);
              if (bytesEvicted == 0) {
                iter.remove();
              }
              totalBytesEvicted += bytesEvicted;
              if (totalBytesEvicted >= bytesToEvictPerTask
                  || !getHeapEvictor().mustEvict() || this.regionSet.size() == 0) {
                lastTaskCompletionTime = System.currentTimeMillis();
                return null;
              }
            } catch (RegionDestroyedException rd) {
              region.cache.getCancelCriterion().checkCancelInProgress(rd);
            } catch (Exception e) {
              region.cache.getCancelCriterion().checkCancelInProgress(e);
              region.cache.getLoggerI18n().warning(
                  LocalizedStrings.Eviction_EVICTOR_TASK_EXCEPTION,
                  new Object[]{e.getMessage()}, e);
            }
          }
        }
      }
    } finally {
      if (start != 0L) {
        long end = CachePerfStats.getStatTime();
        stats.incEvictWorkTime(end - start);
      }
      stats.incEvictorJobsCompleted();
    }
  }

  public static int getEvictionBurstPauseTimeMillis() {
    if (TEST_EVICTION_BURST_PAUSE_TIME_MILLIS != Integer.MAX_VALUE) {
      return TEST_EVICTION_BURST_PAUSE_TIME_MILLIS;
    }
    return EVICTION_BURST_PAUSE_TIME_MILLIS;
  }
}
