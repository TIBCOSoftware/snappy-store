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

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.SimpleReusableEntry;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashMap;

/**
 * Encapsulates concurrent jobs to merge the results from individual sorted job
 * results from {@link SortedIndexRecoveryJobPart}.
 * 
 * @author swale
 * @since gfxd 1.0
 */
public final class SortedIndexRecoveryJob extends BatchJobControl {

  private final DiskStoreImpl dsi;

  private final SortedIndexContainer indexContainer;
  private final boolean isNonUnique;
  private final SortedIndexRecoveryJobPart sortedFragments;

  public SortedIndexRecoveryJob(GemFireCacheImpl cache, DiskStoreImpl ds,
      CancelCriterion cc, SortedIndexContainer index) {
    this(cache, ds, cc, index, DEFAULT_MAX_QUEUED_JOBS);
  }

  public SortedIndexRecoveryJob(GemFireCacheImpl cache, DiskStoreImpl ds,
      CancelCriterion cc, SortedIndexContainer index, int maxQueuedJobs) {
    // use the function executor thread pool
    super(cache.getDistributionManager().getFunctionExcecutor(), cache
        .getLoggerI18n(), cc, maxQueuedJobs);
    this.dsi = ds;
    this.indexContainer = index;
    this.isNonUnique = !index.isUniqueIndex();
    this.sortedFragments = new SortedIndexRecoveryJobPart(cache, ds, cc, index,
        this);
  }

  public void addJob(SortedIndexKey indexKey, RegionEntry entry) {
    this.sortedFragments.addJob(indexKey, entry);
  }

  /**
   * This is synchronized since it is invoked internally at the end of one merge
   * by MergeJob.
   */
  synchronized final void addMergeJob(MergeJob currentJob,
      SortedIndexKey[] sortedKeys, int length) {
    getBatchJob(currentJob).addJob(sortedKeys, length);
  }

  public SortedIndexContainer getIndexContainer() {
    return indexContainer;
  }


  /**
   * {@inheritDoc}
   */
  @Override
  protected BatchJob newJob(BatchJob currentResult) {
    return new MergeJob();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected final void addJobResult(BatchJob job) {
    // add a new job out of the result if not the last one
    final MergeJob mergeJob = (MergeJob)job;
    InternalDistributedSystem ids = InternalDistributedSystem.getAnyInstance();
    final LogWriter logger = ids != null? ids.getLogWriter():null;
    if (mergeJob.isLast) {
      // do the final index build at this point
      // create an Iterator abstraction around the sorted array of index keys
      // having values embedded inside them
      this.indexContainer.buildIndexFromSorted(
          new Iterator<Map.Entry<SortedIndexKey, Object>>() {

        private final SortedIndexKey[] a = mergeJob.sortedResult;
        private final int size = mergeJob.resultSize;
        private int currentPosition;
        private final SimpleReusableEntry<SortedIndexKey, Object> entry =
            new SimpleReusableEntry<SortedIndexKey, Object>(null, null, false);
        private final THashMap accountingMap = dsi != null
            ? dsi.TEST_INDEX_ACCOUNTING_MAP : null;

        @Override
        public boolean hasNext() {
          return this.currentPosition < this.size;
        }

        @Override
        public Map.Entry<SortedIndexKey, Object> next() {
          SortedIndexKey key = this.a[this.currentPosition++];
          Object value = key.getTransientValue();
          this.entry.setRawKeyValue(key, value);
          key.setTransientValue(null);
          if (accountingMap != null) {
            synchronized (accountingMap) {
              Integer cnt = (Integer)accountingMap.get(indexContainer);
              int numEntries;
              if (value instanceof RegionEntry) {
                numEntries = 1;
              }
              else if (value instanceof Object[]) {
                numEntries = ((Object[])value).length;
              }
              else if (value instanceof ConcurrentTHashSet<?>) {
                numEntries = ((ConcurrentTHashSet<?>)value).size();
              }
              else {
                Assert.fail("value=" + value + ", valueClass="
                    + value.getClass());
                // never reached
                numEntries = 1;
              }
              if (cnt == null) {
                accountingMap.put(indexContainer, numEntries);
              }
              else {
                accountingMap.put(indexContainer, cnt.intValue() + numEntries);
              }
            }
          }
          if (logger != null && logger.finerEnabled()) {
            logger.finer("SortedIndexRecoveryJob: building index with key = "
                + entry.getKey() + "; value to be inserted ="
                + ((entry.getValue() instanceof Object[]) ? ArrayUtils
                    .toString((Object[])entry.getValue()) : entry
                        .getValue().toString()));
          }
          return this.entry;
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("SortedIndexRecoveryJob#"
              + "endJobs: iterator does not support remove");
        }
      });
    }
    else {
      addMergeJob(mergeJob, mergeJob.sortedResult, mergeJob.resultSize);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void submitLastJob() {
    MergeJob lastJob = (MergeJob)this.currentJob;
    if (lastJob != null && !lastJob.isLast) {
      this.sortedFragments.endJobs();
      // first wait for existing jobs to finish which can in turn add more jobs
      super.waitForJobs(0);
      // now wait for all jobs; we know there will be one last job that will do
      // the index loading at the end based on the isLast flag set below
      lastJob = (MergeJob)this.currentJob;
      lastJob.isLast = true;
      super.submitLastJob();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean doLog() {
    return DiskStoreImpl.INDEX_LOAD_DEBUG || logger.fineEnabled();
  }

  final class MergeJob extends BatchJob {

    private SortedIndexKey[] sortedKeyFragment1;
    private SortedIndexKey[] sortedKeyFragment2;
    private int fragment1Size;
    private int fragment2Size;
    private SortedIndexKey[] sortedResult;
    private int resultSize;
    private boolean isLast;

    @Override
    public boolean isFull() {
      return this.sortedKeyFragment2 != null;
    }

    @Override
    public int numSubmitted() {
      return this.sortedKeyFragment2 != null ? 2
          : (this.sortedKeyFragment1 != null ? 1 : 0);
    }

    public void addJob(SortedIndexKey[] sortedKeys, int length) {
      if (DiskStoreImpl.INDEX_LOAD_DEBUG_FINER) {
        logger.info(LocalizedStrings.DEBUG, String.format(
            "MergeJob#addJob index=%s, indexKeys=%s",
            indexContainer, Arrays.toString(sortedKeys)));
      }
      if (this.sortedKeyFragment1 == null) {
        this.sortedKeyFragment1 = sortedKeys;
        this.fragment1Size = length;
      }
      else {
        this.sortedKeyFragment2 = sortedKeys;
        this.fragment2Size = length;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void executeJob() throws Exception {
      if (isNonUnique) {
        // even for single array we still need to do duplicate merge, so
        // invoke mergeNonUnique in all cases which will optimize for
        // single array case appropriately
        mergeNonUnique();
        return;
      }
      SortedIndexKey[] part1 = this.sortedKeyFragment1;
      SortedIndexKey[] part2 = this.sortedKeyFragment2;
      if (part2 != null && part2.length > 0) {
        // We use simple merge sort kind of approach for parallel sorting since
        // it is easily parallelized and where each individual sort can go on
        // pretty much independently in parallel with merge jobs themselves.
        // It also fits in perfectly with the incremental data loading we have.
        // There are other parallel sorts like "sample sort" purported to be
        // faster but is unlikely to be significantly faster and lightweight
        // than this, though those should be checked out in future. With > 1
        // indexes, the performance of the parallel sort has a direct bearing on
        // the startup time so we need to choose a sort which will a) reduce the
        // number of comparisons, b) process load in parallel as much as
        // possible throughout. This approach is highly parallel in the initial
        // stages but as more and more merges commence and arrays become larger,
        // the parallelism decreases with the last merge being completely serial
        // (though still parallel per index to be loaded). In this regard we
        // might want to take a look at other known good parallel sort
        // algorithms that can work incrementally as we require.
        //
        // For comparison, the original direct index insert was overall 2X
        // slower than this approach on a 8-CPU server grade machine. However,
        // it is highly parallel throughout the lifetime of the processing so it
        // might as well turn out as good as this on a 32 CPU or so machine,
        // though it is very likely that concurrency of inserts even with
        // lock-free approach as in CSLM will take a significant hit at anything
        // more than > 20 or so concurrent inserts.
        int part1Len = this.fragment1Size;
        int part2Len = this.fragment2Size;
        // keep the final result as a combined array to be passed back as a new
        // job at the end of this execution
        final SortedIndexKey[] result = new SortedIndexKey[part1Len + part2Len];
        int index1 = 0, index2 = 0, index = 0;
        SortedIndexKey key1, key2;
        final Comparator<? super SortedIndexKey> cmp = indexContainer
            .getComparator();

        // TODO: PERF: checkout if searching for start/end of arrays inside
        // other like in TimSort's merge will give better performance overall
        // simple 2-way merge of the two sorted arrays
        // this version is slightly optimized version that does not need
        // to consider non-unique keys; the other version that also considers
        // duplicates is in mergeNonUnique
        // keys are assumed to have proper "equals" implementation which is
        // used for duplicate determination rather than comparator since
        // it will likely be better optimized than compare
        key1 = part1[index1];
        key2 = part2[index2];
        while (true) {
          int c = cmp.compare(key1, key2);
          if (c < 0) {
            result[index++] = key1;
            if (++index1 < part1Len) {
              key1 = part1[index1];
            }
            else {
              // shift to first array for ease of processing at the end
              part1 = part2;
              part1Len = part2Len;
              index1 = index2;
              break;
            }
          }
          else if (c > 0) {
            result[index++] = key2;
            if (++index2 < part2Len) {
              key2 = part2[index2];
            }
            else {
              break;
            }
          }
          else {
            result[index++] = key1;
            index1++; index2++;
            if (index1 < part1Len) {
              if (index2 < part2Len) {
                key1 = part1[index1];
                key2 = part2[index2];
              }
              else {
                break;
              }
            }
            else {
              // shift to first array for ease of processing at the end
              part1 = part2;
              part1Len = part2Len;
              index1 = index2;
              break;
            }
          }
        }
        // now the remaining elements are to be copied as such
        while (index1 < part1Len) {
          result[index++] = part1[index1++];
        }
        this.sortedResult = result;
        this.resultSize = index;
      }
      else {
        // if we have only one job then no need to merge
        this.sortedResult = this.sortedKeyFragment1;
        this.resultSize = this.fragment1Size;
      }
    }

    private void mergeNonUnique() {
      final SortedIndexContainer indexContainer = SortedIndexRecoveryJob.this
          .indexContainer;
      final Comparator<? super SortedIndexKey> cmp = indexContainer
          .getComparator();
      SortedIndexKey[] part1 = this.sortedKeyFragment1;
      SortedIndexKey[] part2 = this.sortedKeyFragment2;
      if (part2 != null && part2.length > 0) {
        int part1Len = this.fragment1Size;
        int part2Len = this.fragment2Size;
        // keep the final result as a combined array to be passed back as a new
        // job at the end of this execution
        final SortedIndexKey[] result = new SortedIndexKey[part1Len + part2Len];
        int index1 = 0, index2 = 0, index = 0;
        SortedIndexKey key1, key2, lastKey = null;

        key1 = part1[0];
        key2 = part2[0];
        while (true) {
          int c = cmp.compare(key1, key2);
          if (c < 0) {
            // merge duplicates
            //if (lastKey != null && cmp.compare(lastKey, key1) == 0) {
            if (lastKey != null && lastKey != key2 && lastKey.equals(key1)) {
              indexContainer.mergeValuesForNonUnique(lastKey, key1);
            }
            else {
              result[index++] = lastKey = key1;
            }
            if (++index1 < part1Len) {
              key1 = part1[index1];
            }
            else {
              // shift to first array for ease of comparison
              part1 = part2;
              part1Len = part2Len;
              index1 = index2;
              break;
            }
          }
          else if (c > 0) {
            // merge duplicates
            //if (lastKey != null && cmp.compare(lastKey, key2) == 0) {
            if (lastKey != null && lastKey != key1 && lastKey.equals(key2)) {
              indexContainer.mergeValuesForNonUnique(lastKey, key2);
            }
            else {
              result[index++] = lastKey = key2;
            }
            if (++index2 < part2Len) {
              key2 = part2[index2];
            }
            else {
              break;
            }
          }
          else {
            // merge duplicates
            //if (lastKey != null && cmp.compare(lastKey, key1) == 0) {
            if (lastKey != null && lastKey.equals(key1)) {
              indexContainer.mergeValuesForNonUnique(lastKey, key1);
              indexContainer.mergeValuesForNonUnique(lastKey, key2);
            }
            else {
              indexContainer.mergeValuesForNonUnique(key1, key2);
              result[index++] = lastKey = key1;
            }
            index1++; index2++;
            if (index1 < part1Len) {
              if (index2 < part2Len) {
                key1 = part1[index1];
                key2 = part2[index2];
              }
              else {
                break;
              }
            }
            else {
              // shift to first array for ease of comparison
              part1 = part2;
              part1Len = part2Len;
              index1 = index2;
              break;
            }
          }
        }
        while (index1 < part1Len) {
          key1 = part1[index1++];
          //if (lastKey == null || cmp.compare(lastKey, key1) != 0) {
          if (lastKey == null || !lastKey.equals(key1)) {
            result[index++] = lastKey = key1;
          }
          else {
            indexContainer.mergeValuesForNonUnique(lastKey, key1);
          }
        }
        this.sortedResult = result;
        this.resultSize = index;
      }
      else {
        // if we have only one job then no need to merge but still need to merge
        // the duplicates
        final int part1Len = this.fragment1Size;
        int index1 = 1;
        SortedIndexKey key1, lastKey = part1[0];
        int mergeIndex = 1;
        while (index1 < part1Len) {
          key1 = part1[index1++];
          //if (lastKey == null || cmp.compare(lastKey, key1) != 0) {
          if (!lastKey.equals(key1)) {
            part1[mergeIndex++] = lastKey = key1;
          }
          else {
            indexContainer.mergeValuesForNonUnique(lastKey, key1);
          }
        }

        this.sortedResult = part1;
        this.resultSize = mergeIndex;
      }
    }

    @Override
    protected String getCurrentStateString(Throwable t) {
      int len = this.sortedKeyFragment1.length;
      if (this.sortedKeyFragment2 != null) {
        len += this.sortedKeyFragment2.length;
      }
      return "merging " + len + " index keys, into index " + indexContainer;
    }

    @Override
    protected RuntimeException getFailureException(String reason, Throwable t) {
      final DiskStoreImpl dsi = SortedIndexRecoveryJob.this.dsi;
      return dsi != null ? new DiskAccessException(reason, t, dsi)
          : new GemFireIOException(reason, t);
    }
  }
}
