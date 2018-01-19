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
package com.gemstone.gemfire.internal.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.concurrent.GuardedBy;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.util.ArraySortedCollection;
import com.gemstone.gemfire.internal.util.Enumerator;

import static com.gemstone.gemfire.internal.cache.GemFireCacheImpl.sysProps;

/**
 * A generic class to enable sorting of disk blocks across iterators.
 * Instead of a separate thread as a service for cross-iterator sorting,
 * the approach used is for all concurrent iterators submit their blocks
 * to the per-diskstore sorter object while having it locked for writes,
 * and then one of the iterators will open an iterator after which no further
 * adds can be done to this sorter. Any further iterators will create a new
 * sorter object for the same and share amongst themselves.
 * <p>
 * The diskstore will maintain the currently active sorter available for writing
 * that iterators can grab hold of and add their blocks. The cleanup of sorters
 * will happen automatically by GC as and when all iterators lose the reference
 * to a sorter after having done all the reads.
 * <p>
 * The overall flow looks like this in the "good" case:
 * <pre>
 * Iterator1 => getSorter => addEntry => addEntry => ... => enumerate(wait)
 * Iterator2 => getSorter => addEntry => addEntry => ... => enumerate(wait)
 * Iterator3 => getSorter => addEntry => addEntry => ... => enumerate(wait)
 * </pre>
 * In case some iterators finish early but others later then the flow is:
 * <pre>
 * Iterator1 => getSorter => addEntry => ... => enumerate(wait)
 * Iterator2 => getSorter => addEntry => ... => enumerate(wait)
 * Iterator3 => getSorter => addEntry => addEntry(fail) =>
 *              getSorter(previously added entries) => ... => enumerate(wait)
 * </pre>
 * This also means that iterators are expected to track the set of entries
 * they are adding to the sorter to be able to switch to a new sorter in case
 * addition fails due to the sorter going into a read-only mode.
 */
public final class DiskBlockSortManager {

  /**
   * Currently active sorter used by region iterators for cross iterator
   * sorting in case multiple concurrent iterators are open. The iterators
   * will grab hold of currently active sorter and submit their disk blocks
   * to be sorted and at some point one of those iterators will open the
   * sorter for reading at which point no new blocks can be added and
   * a new current sorter will be created.
   */
  private DiskBlockSorter blockSorter;

  private final Object lock;

  private final AtomicInteger readerId;

  /**
   * Size of a disk page used as boundaries over which sorting is done
   * by {@link DiskBlockSorter}.
   */
  static final long DISK_PAGE_SIZE = sysProps.getLong(
      "DISK_PAGE_SIZE", 8 * 1024L);

  /**
   * Maximum size of an array used by {@link DiskBlockSorter}.
   */
  private static final int DISK_BLOCK_SORTER_ARRAY_SIZE = sysProps.getInteger(
      "DISK_BLOCK_SORTER_ARRAY_SIZE", 50000);

  DiskBlockSortManager() {
    this.lock = new Object();
    this.readerId = new AtomicInteger(0);
  }

  public int newReaderId() {
    return this.readerId.incrementAndGet();
  }

  public DiskBlockSorter getSorter(LocalRegion region, boolean fullScan,
      Collection<DistributedRegion.DiskEntryPage> entries) {
    synchronized (this.lock) {
      DiskBlockSorter sorter = this.blockSorter;
      if (sorter == null || sorter.readOnlyMode) {
        this.blockSorter = sorter = new DiskBlockSorter(region, fullScan, lock);
      } else {
        sorter.incrementRefCount();
      }
      if (entries != null && !entries.isEmpty()) {
        for (DistributedRegion.DiskEntryPage entry : entries) {
          sorter.addNewEntry(entry);
        }
      }
      return sorter;
    }
  }

  public static final class DiskBlockSorter {
    /**
     * the underlying sorter
     */
    private ArraySortedCollection sorter;
    private final ArrayList<Object> sorted;
    private final boolean fullScan;

    private final Object lock;
    private final CancelCriterion regionStopper;
    private final CancelCriterion diskStoreStopper;

    /**
     * The number of readers that have opened this sorter. When the sorter
     * is to be read, then it can wait for some time before switching it to
     * {@link #readOnlyMode} mode if there are still readers that are adding blocks
     * to this sorter. After the timeout, any remaining readers will be
     * switched to a new {@link DiskBlockSorter} if they add new blocks.
     */
    private int refCount;

    boolean readOnlyMode;

    DiskBlockSorter(LocalRegion region, boolean fullScan, Object lock) {
      this.sorter = new ArraySortedCollection(
          DistributedRegion.DiskEntryPage.DEPComparator.instance, null, null,
          DISK_BLOCK_SORTER_ARRAY_SIZE, 0);
      this.sorted = new ArrayList<>();
      this.fullScan = fullScan;
      this.lock = lock;
      this.regionStopper = region.getCancelCriterion();
      this.diskStoreStopper = region.getDiskStore().getCancelCriterion();
      this.refCount = 1; // for the first reader
    }

    @GuardedBy("lock")
    void incrementRefCount() {
      this.refCount++;
    }

    /**
     * Add a new entry to this sorter. If the add fails due to iteration
     * having started (and sorter going into {@link #readOnlyMode}) then
     * this will return false. Caller is supposed to fetch a new sorter
     * if false is returned passing it the entries that were added to
     * this sorter so far. So a typical usage of this will be like:
     * <pre>
     * List&lt;DiskEntryPage&gt; allEntries;
     * ...
     * allEntries.add(entry);
     * if (!sorter.addEntry(entry)) {
     *   sorter = sortManager.getSorter(region, fullScan, allEntries);
     * }
     * </pre>
     */
    public boolean addEntry(DistributedRegion.DiskEntryPage entry) {
      synchronized (this.lock) {
        if (this.readOnlyMode) {
          return false;
        } else {
          addNewEntry(entry);
          return true;
        }
      }
    }

    /**
     * Get an {@link Enumerator} over sorted disk blocks for given readerId
     * (that must match {@link DistributedRegion.DiskEntryPage#readerId})
     * waiting for given maximum time for all readers to start iterating
     * results that will switch this sorter into read-only mode.
     * A negative time means wait indefinitely.
     */
    public ReaderIdEnumerator enumerate(int readerId, boolean faultIn,
        long maxWaitMillis) {
      synchronized (this.lock) {
        if (--this.refCount <= 0) {
          // notify any waiters
          this.lock.notifyAll();
        } else {
          // wait for given timeout
          if (maxWaitMillis < 0) {
            maxWaitMillis = Long.MAX_VALUE;
          }
          regionStopper.checkCancelInProgress(null);
          diskStoreStopper.checkCancelInProgress(null);
          // loop wait to check for cancellation and ignore spurious wakeups
          if (maxWaitMillis > 0 && this.refCount > 0) {
            final long start = System.currentTimeMillis();
            final long loopMillis = Math.min(maxWaitMillis, 10);
            do {
              try {
                this.lock.wait(loopMillis);
              } catch (InterruptedException ie) {
                regionStopper.checkCancelInProgress(ie);
                diskStoreStopper.checkCancelInProgress(ie);
              }
            } while (this.refCount > 0 &&
                (System.currentTimeMillis() - start) < maxWaitMillis);
          }
        }
        this.readOnlyMode = true;

        // capture sorter output once
        if (this.sorter != null) {
          Enumerator enumerator = this.sorter.enumerator();
          Object next;
          while ((next = enumerator.nextElement()) != null) {
            this.sorted.add(next);
          }
          this.sorter = null;
        }
        return new ReaderIdEnumerator(readerId, faultIn);
      }
    }

    @GuardedBy("lock")
    void addNewEntry(DistributedRegion.DiskEntryPage entry) {
      this.sorter.add(entry);
    }

    public final class ReaderIdEnumerator
        implements Enumerator<DistributedRegion.DiskEntryPage> {

      private final int requiredId;
      private final Iterator<Object> sortedIterator;
      private final boolean faultIn;
      private boolean faultInStopped;

      ReaderIdEnumerator(int readerId, boolean faultIn) {
        this.requiredId = readerId;
        this.sortedIterator = sorted.iterator();
        // no need to check for recovery condition if this is not a full scan
        this.faultIn = faultIn;
        this.faultInStopped = !fullScan && faultIn;
      }

      @Override
      public DistributedRegion.DiskEntryPage nextElement() {
        StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
        while (this.sortedIterator.hasNext()) {
          diskStoreStopper.checkCancelInProgress(null);
          DistributedRegion.DiskEntryPage entry =
              (DistributedRegion.DiskEntryPage)this.sortedIterator.next();
          boolean faultInEntry = faultIn;
          // if this is a full-scan and there is memory available then fault-in
          // the value in any case to do ordered disk reads as much as possible;
          // also once these faultins are stopped, it will never be checked again
          if (!faultInStopped) {
            faultInStopped = callbacks.shouldStopRecovery();
            if (!faultInStopped) {
              if (fullScan) {
                OffHeapHelper.release(entry.readEntryValue());
                faultInEntry = false;
              } else if (!faultInEntry) {
                faultInEntry = true; // fault-in in any case if possible
              }
            }
          }
          if (entry.readerId == this.requiredId) {
            if (faultInEntry) {
              OffHeapHelper.release(entry.readEntryValue());
            }
            return entry;
          }
        }
        return null;
      }
    }
  }
}
