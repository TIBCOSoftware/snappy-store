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
package com.gemstone.gemfire.cache.query.internal;

import java.util.Iterator;

import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.internal.cache.DiskRegion;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;

public interface IndexUpdater {

  /**
   * This method is invoked when an entry is added, updated or destroyed in a
   * region for index maintenance. This method will do some pre-update
   * operations for the index like constraint checks or any other logging that
   * may be required, and any index updates if required.
   * 
   * @param owner
   *          the {@link Region} that owns this event; will be different from
   *          {@link EntryEventImpl#getRegion()} for partitioned regions
   * @param event
   *          the {@link EntryEventImpl} representing the operation.
   * @param entry
   *          the region entry.
   */
  public void onEvent(LocalRegion owner, EntryEventImpl event, RegionEntry entry);

  /**
   * This method is invoked after an entry has been added, updated or destroyed
   * in a region for index maintenance. This method will commit the changes to
   * the indexes or may rollback some of the changes done in {@link #onEvent} if
   * the entry operation failed for some reason.
   * 
   * @param owner
   *          the {@link Region} that owns this event; will be different from
   *          {@link EntryEventImpl#getRegion()} for partitioned regions
   * @param event
   *          the {@link EntryEventImpl} representing the operation.
   * @param entry
   *          the region entry.
   * @param success
   *          true if the entry operation succeeded and false otherwise.
   */
  public void postEvent(LocalRegion owner, EntryEventImpl event,
      RegionEntry entry, boolean success);

  /**
   * Cleanup method that should be invoked even when {@link #postEvent} is not.
   * Note that this does not need to be invoked if {@link #postEvent} has been,
   * but should always be invoked if higher layers skip {@link #postEvent} due
   * to some reason.
   * 
   * @param event
   */
  public void postEventCleanup(EntryEventImpl event);

  /**
   * Returns true if {@link #onEvent} and {@link #postEvent} need to be invoked.
   */
  public boolean needsRecovery();

  /**
   * This method is invoked when an entry is overflowed to disk in a region for
   * index maintenance. This method will do some pre-overflow operations for the
   * index like removing references to the entry value.
   * 
   * @param entry
   *          the region entry.
   */
  public void onOverflowToDisk(RegionEntry entry);

  /**
   * This method is invoked when an entry is faulted in from disk in a region
   * for index maintenance. This method will do some post operations operations
   * for the index to update the reference to the entry value.
   * 
   * @param entry
   *          the region entry.
   */
  void onFaultInFromDisk(RegionEntry entry);

  /**
   * Invoked to clear all index entries for a region before destroying it. This
   * can be a bucket region or a replicated region.
   * 
   * @param region
   *          the {@link LocalRegion} being destroyed
   * @param dr the {@link DiskRegion} to be used; normally is the DiskRegion
   *           of the "region", but can be different in case a bucket region has
   *           not yet been created in a failed GII when destroying the disk data
   * @param lockForGII
   *          if true then also acquire the {@link #lockForGII()}
   * @param holdIndexLock
   *          if true then hold on to the index level lock acquired by
   *          {@link #lockForGII()} at the end of this method which will block
   *          any new updates; caller needs to release the lock in a finally
   *          block when this is true by a call to
   *          {@link #releaseIndexLock(LocalRegion)}
   * @param bucketEntriesIter
   *         iterator on List of RegionEntry belonging to the bucket which is being destroyed.
   *         null is passed for non bucket regions.
   * @param destroyOffline
   * @return  Returns whether write lock was acqired by clearIndex or not
   */

  boolean clearIndexes(LocalRegion region, DiskRegion dr, boolean lockForGII,
      boolean holdIndexLock, Iterator<?> bucketEntriesIter, boolean destroyOffline);

  /**
   * should be invoked if "holdIndexLock" argument was true in
   * {@link #clearIndexes(LocalRegion, DiskRegion, boolean, boolean, Iterator, boolean)}
   */
  public void releaseIndexLock(LocalRegion region);

  /**
   * Take a read lock indicating that bucket/region GII is in progress to block
   * index list updates during the process.
   * 
   * @throws TimeoutException
   *           in case of timeout in acquiring the lock
   */
  public void lockForGII() throws TimeoutException;

  /**
   * Release the read lock taken for GII by {@link #lockForGII()}.
   * 
   * @throws LockNotHeldException
   *           if the current thread does not hold the read lock for GII
   */
  public void unlockForGII() throws LockNotHeldException;

  /**
   * Release the read/write lock taken for GII by {@link #lockForGII()}.
   * 
   * @throws LockNotHeldException
   *           if the current thread does not hold the read lock for GII
   */
  public void unlockForGII(boolean forWrite) throws LockNotHeldException;
  /**
   * Take a read lock to wait for completion of any index load in progress
   * during index creation. This is required since no table level locks are
   * acquired during initial DDL replay to avoid blocking most (if not all) DMLs
   * in the system whenever a new node comes up.
   * 
   * This will be removed at some point when we allow for concurrent loading and
   * initialization of index even while operations are in progress using
   * something similar to region GII token mode for indexes or equivalent (bug
   * 40899).
   * 
   * This is required to be a reentrant lock. The corresponding write lock that
   * will be taken by the implementation internally should also be reentrant.
   * 
   * @return true if an index lock was acquired, and false if no lock was
   *         required to be acquired in which case {@link #unlockForIndexGII()}
   *         should not be invoked
   * 
   * @throws TimeoutException
   *           in case of timeout in acquiring the lock
   */
  public boolean lockForIndexGII() throws TimeoutException;

  /**
   * Release the read lock taken for GII by {@link #lockForIndexGII()}.
   * 
   * @throws LockNotHeldException
   *           if the current thread does not hold the read lock
   */
  public void unlockForIndexGII() throws LockNotHeldException;

  /**
   * Returns true if index maintenance has to potentially perform remote
   * operations (e.g. global index maintenance, FK checks) for given operation.
   */
  public boolean hasRemoteOperations(Operation op);

  /**
   * Returns true if index maintenance has to potentially perform remote
   * operations (e.g. global index maintenance, FK checks) for given operation,
   * or a potentially expensive operation. This is used to switch the processor
   * type to non-serial executor at GFE layer to avoid blocking the P2P reader
   * thread.
   */
  public boolean avoidSerialExecutor(Operation op);

  public boolean handleSuspectEvents();

}
