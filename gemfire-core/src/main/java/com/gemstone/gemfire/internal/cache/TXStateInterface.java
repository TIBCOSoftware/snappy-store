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

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy.ReadEntryUnderLock;

/**
 * An entity that tracks transactions must implement this interface. 
 * 
 * @author mthomas
 */
public interface TXStateInterface extends InternalDataView {

  /**
   * The ID of this transaction.
   */
  public TXId getTransactionId();

  /**
   * Get the {@link LockingPolicy} to be used for this transaction.
   */
  public LockingPolicy getLockingPolicy();

  /**
   * Get the {@link IsolationLevel} set for this transaction.
   */
  public IsolationLevel getIsolationLevel();

  /**
   * Get the underlying {@link TXState} if any.
   */
  public TXState getLocalTXState();

  /**
   * Get or create the underlying {@link TXState} for write.
   */
  public TXState getTXStateForWrite();

  /**
   * Get the underlying {@link TXState} for read (can also create it for
   * REPEATABLE_READ isolation).
   */
  public TXState getTXStateForRead();

  /**
   * Get the proxy for remoting operations.
   */
  public TXStateProxy getProxy();

  /**
   * Used by transaction operations that are doing a read operation on the
   * specified region.
   * 
   * @return the TXRegionState for the given LocalRegion or null if no state
   *         exists
   */
  public TXRegionState readRegion(LocalRegion r);

  /**
   * Returns a nanotimer timestamp that marks when begin was
   * called on this transaction.
   */
  public long getBeginTime();

  /**
   * Returns the number of changes this transaction would have made
   * if it successfully committed.
   */
  public int getChanges();

  /**
   * Determines if a transaction is in progress.
   * Transactions are in progress until they commit or rollback.
   * @return true if this transaction has completed.
   */
  public boolean isInProgress();

  public boolean isOpen();

  /**
   * Only check if this TX (proxy or local) is closed.
   * Differs from "isInProgress" in that this will not check for
   * the state of inner TXState.
   */
  public boolean isClosed();

  public void commit(Object callbackArg) throws TransactionException;

  public void rollback(Object callbackArg);

  /** Implement TransactionEvent's getCache */
  public GemFireCacheImpl getCache();

  /** For tests only. Not thread-safe. */
  public Collection<LocalRegion> getRegions();

  public InternalDistributedMember getCoordinator();

  /**
   * Returns true if this node itself is the coordinator for this transaction.
   */
  public boolean isCoordinator();
  public long getCommitTime();
  public TXEvent getEvent();

  public boolean txPutEntry(EntryEventImpl event, boolean ifNew,
      boolean requireOldValue, boolean checkResources, Object expectedOldValue);

  public void rmRegion(LocalRegion r);

  /**
   * 
   * @return true if callbacks should be fired for this TXState
   */
  public boolean isFireCallbacks();

  /**
   * Do any cleanup required for the local cache (e.g. losing reference to large
   * objects to help GC).
   */
  public void cleanupCachedLocalState(boolean hasListeners);

  /**
   * Flush any pending operation remaining in the transaction.
   */
  public void flushPendingOps(DM dm);

  public boolean isJTA();

  public TXManagerImpl getTxMgr();

  public void setObserver(TransactionObserver observer);

  public TransactionObserver getObserver();

  /**
   * Lock the given RegionEntry for reading as per the provided
   * {@link LockingPolicy}.
   * 
   * @return the result of {@link ReadEntryUnderLock#readEntry} after lock
   *         acquisition
   */
  public Object lockEntryForRead(RegionEntry entry, Object key,
      LocalRegion dataRegion, int context, boolean allowTombstones,
      ReadEntryUnderLock reader);

  public Object lockEntry(RegionEntry entry, Object key, Object callbackArg,
      LocalRegion region, LocalRegion dataRegion, boolean writeMode,
      boolean allowReadFromHDFS, byte opType, int failureFlags);
  
  public void setExecutionSequence(int execSeq);
  
  public int getExecutionSequence();

  public boolean isSnapshot();

  public void recordVersionForSnapshot(Object member, long version, Region region);
}
