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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
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

  public DiskBlockSorter getSorter(LocalRegion region,
      Collection<DistributedRegion.DiskEntryPage> entries) {
    synchronized (this.lock) {
      DiskBlockSorter sorter = this.blockSorter;
      if (sorter == null || sorter.readOnly) {
        this.blockSorter = sorter = new DiskBlockSorter(region, this.lock);
      } else {
        sorter.incrementRefCount();
      }
      if (entries != null) {
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
    private final ArraySortedCollection sorter;

    private final LocalRegion region;
    private final Object lock;

    /**
     * The number of readers that have opened this sorter. When the sorter
     * is to be read, then it can wait for some time before switching it to
     * {@link #readOnly} mode if there are still readers that are adding blocks
     * to this sorter. After the timeout, any remaining readers will be
     * switched to a new {@link DiskBlockSorter} if they add new blocks.
     */
    private int refCount;

    boolean readOnly;

    DiskBlockSorter(LocalRegion region, Object lock) {
      this.sorter = new ArraySortedCollection(
          new DistributedRegion.DiskEntryPage.DEPComparator(), null, null,
          DISK_BLOCK_SORTER_ARRAY_SIZE, 0);
      this.region = region;
      this.lock = lock;
      this.refCount = 1; // for the first reader
    }

    void incrementRefCount() {
      synchronized (this.lock) {
        this.refCount++;
      }
    }

    public boolean addEntry(DistributedRegion.DiskEntryPage entry) {
      synchronized (this.lock) {
        if (this.readOnly) {
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
    public ReaderIdEnumerator enumerate(int readerId, long maxWaitMillis) {
      synchronized (this.lock) {
        if (--this.refCount <= 0) {
          // notify any waiters
          this.lock.notifyAll();
        } else {
          // wait for given timeout
          if (maxWaitMillis < 0) {
            maxWaitMillis = Long.MAX_VALUE;
          }
          final CancelCriterion regionStopper = this.region.getCancelCriterion();
          final CancelCriterion diskStoreStopper = this.region.getDiskStore()
              .getCancelCriterion();
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
        this.readOnly = true;

        return new ReaderIdEnumerator(readerId, this.region, this.sorter);
      }
    }

    void addNewEntry(DistributedRegion.DiskEntryPage entry) {
      this.sorter.add(entry);
    }
  }

  public static final class ReaderIdEnumerator
      implements Enumerator<DistributedRegion.DiskEntryPage> {

    private final int requiredId;
    private final LocalRegion region;
    private final Enumerator allElements;

    ReaderIdEnumerator(int readerId, LocalRegion region,
        ArraySortedCollection sorter) {
      this.requiredId = readerId;
      this.region = region;
      this.allElements = sorter.enumerator();
    }

    @Override
    public DistributedRegion.DiskEntryPage nextElement() {
      Object next;
      while ((next = this.allElements.nextElement()) != null) {
        DistributedRegion.DiskEntryPage entry =
            (DistributedRegion.DiskEntryPage)next;
        // fault-in the value if required in any case to do ordered
        // disk reads as much as possible
        if (entry.faultIn) {
          OffHeapHelper.release(entry.entry.getValue(region));
        }
        if (entry.readerId == this.requiredId) {
          return entry;
        }
      }
      return null;
    }
  }
}
