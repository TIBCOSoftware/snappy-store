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

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;

/**
 * State object used during getInitialImage Locked during clean up of destroyed
 * tokens.
 * 
 * @author Eric Zoerner
 */
public interface ImageState /* extends Lock */ {

  public boolean getRegionInvalidated();

  public void setRegionInvalidated(boolean b);

  public void setInRecovery(boolean b);

  public boolean getInRecovery();

  public void addDestroyedEntry(Object key);
  
  public void removeDestroyedEntry(Object key);

  public boolean hasDestroyedEntry(Object key);

  public java.util.Iterator<Object> getDestroyedEntries();

  public void addDeltaEntry(Object key);
  
  public void removeDeltaEntry(Object key);

  public boolean hasDeltaEntry(Object key);

  public java.util.Iterator<Object> getDeltaEntries();
  
  /**
   *  returns count of entries that have been destroyed by concurrent operations
   *  while in token mode
   */
  public int getDestroyedEntriesCount();

  /**
   * Register failed events at GemFireXD layer due to contraint violation in a
   * replicated table. These events will need to be skipped when applying
   * suspect events.
   */
  public void addFailedEvents(Collection<EventID> events);

  /**
   * Return true if this event is to be skipped in suspect processing due to
   * known failure on GII source node.
   */
  public boolean isFailedEvent(EventID eventId);

  /**
   * Clear the failed events collection.
   */
  public void clearFailedEvents();

  public void setClearRegionFlag(boolean isClearOn, RegionVersionVector rvv);

  public boolean getClearRegionFlag();
  public RegionVersionVector getClearRegionVersionVector();
  public boolean wasRegionClearedDuringGII();
  
  public void addVersionTag(Object key, VersionTag<?> tag);
  public Iterator<VersionTagEntry> getVersionTags();
  
  public void addLeftMember(VersionSource<?> mbr);
  public Set<VersionSource> getLeftMembers();
  public boolean hasLeftMembers();

  public void lockGII();
  public void unlockGII();
  public void readLockRI();
  public void readUnlockRI();
  public void writeLockRI();
  public void writeUnlockRI();

  /**
   * Acquire lock on pending TXRegionState list to prevent new additions.
   * Returns true if TXRegionState list is valid and lock was acquired (to be
   * released by {@link #unlockPendingTXRegionStates(boolean)}) else false if
   * {@link #clearPendingTXRegionStates} has already been invoked with "reset"
   * as false.
   * 
   * This should be a lock without any owner especially current Thread owner
   * since it can be acquired and released in two different threads.
   */
  public boolean lockPendingTXRegionStates(boolean forWrite, boolean force);

  /** release the lock on pending TXRegionState list */
  public void unlockPendingTXRegionStates(boolean forWrite);

  /**
   * Register a TXRegionState that has pending ops coming in before GII is
   * complete. If GII is not complete, then it will add to pending TXRS list and
   * return true, else it will return false.
   */
  public boolean addPendingTXRegionState(TXRegionState txrs);

  /**
   * Remove TX with given ID from the list of pending TX region states. Assumes
   * that lock has already been acquired by {@link #lockPendingTXRegionStates}.
   */
  public void removePendingTXRegionState(TXId txId);

  /**
   * Lookup pending TXRegionState for given TXId. If "lock" is true then read
   * lock is acquired else it is assumed that lock has been already acquired by
   * a call to {@link #lockPendingTXRegionStates}.
   */
  public TXRegionState getPendingTXRegionState(TXId txId, boolean lock);

  /**
   * Clear the pending TXRegionStates (write lock should have been acquired
   * first by {@link #lockPendingTXRegionStates}).
   * 
   * @param reset
   *          if true then only clear and restart (i.e. after a failed GII new
   *          GII source being tried), else null it out so then no further
   *          additions to pending TXRS is done
   */
  public void clearPendingTXRegionStates(boolean reset);

  /**
   * get the pending TXRegionStates (lock should have been acquired first by
   * {@link #lockPendingTXRegionStates})
   */
  public Collection<TXRegionState> getPendingTXRegionStates();

  /**
   * set a monotonically increasing integer as "finishOrder" in TXRegionState to
   * use for ordering of commit/rollback transactions to use for ordering when
   * applying to region at the end of GII
   */
  public void setTXOrderForFinish(TXRegionState txrs);

  /**
   * If a transaction has been recorded as being finished, then return a number
   * that can be used for ordering of commit/rollback. Returns zero if the
   * transaction has not been recorded as finished, and a negative of the order
   * if it was recorded as having been rolled back.
   */
  public long getFinishedTXOrder(TXId txId);

  /**
   * Adjust the orders returned by getPendingTXOrder using the ordering from
   * transaction manager for given list of TXIds.
   */
  public void mergeFinishedTXOrders(LocalRegion region, Collection<TXId> txIds);

  public boolean isReplicate();
  public boolean isClient();
  
  public void init();

  boolean requestedUnappliedDelta();
  public void setRequestedUnappliedDelta(boolean flag);


  public interface VersionTagEntry {
    public Object getKey();
    public VersionSource getMemberID();
    public long getRegionVersion();
  }
  
}
