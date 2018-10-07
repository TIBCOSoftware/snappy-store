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

package com.pivotal.gemfirexd.internal.engine.locks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.LockSupport;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.locks.CompatibilitySpace;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockOwner;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class implements a {@link CompatibilitySpace} that allows locks to be
 * grouped by the transactions in which they were acquired. The idea is to allow
 * release of all locks in the transaction group at the end of transaction
 * (commit, abort or rollback). It also allows escalation of read locks to write
 * locks and de-escalation. These are not atomic operations and for GemFireXD
 * DDL/DML purposes the current escalation behaviour is good enough. It helps
 * avoid deadlocks by ordering read locks after write locks. So if there are
 * read locks held while write lock request is made, then the read locks can be
 * released and reacquired after the write lock. Also allows reentrancy without
 * assuming reentrancy for the underlying lock implementation in
 * {@link GfxdLockService}.
 * 
 * Some of the methods need to be protected now (
 * {@link #releaseLock(GfxdLockable, boolean, boolean)} and {@link #unlockAll}
 * ) since multiple threads can release locks on the same Transaction,
 * particularly with streaming where this should be done after processing is
 * done on all nodes, so release is done by the ResultCollector while TX commit
 * can be done by application thread. Also TX objects can be shared across
 * threads if we allow for two threads to concurrently use the same connection
 * like derby (bug #40656).
 * 
 * [sumedh] 20111204
 * 
 * Synchronized unlockAll against releaseLock that can be invoked by the
 * streaming result collector thread via
 * GfxdResultCollectorHelper.closeContainers (#44220). Add lock methods don't
 * need synchronization since those will never be invoked in parallel with
 * releaseLock/unlockAll and any invocation to addLock will wait for existing
 * pending streaming RC waiter to end first.
 * 
 * // !!!:ezoerner:20091117
 * 
 * Made this extend LockSpace so it inherits the ability to do locking based on
 * groups. The methods in LockSpace are mutually exclusive with this class with
 * the exception of getLockOwner(), so the behaviors are orthogonal. This is an
 * unconventional way to get an instance of GfxdLockSet to also be a LockSpace,
 * as a fix for #41295. Note that I also has to change LockSpace from a final
 * package class to a non-final public class to get this to work.
 * 
 * [sumedh] removed GfxdLockSet extending LockSpace and changed code to use
 * GfxdLockSet everywhere LockSpace was being used (DD boot, UpdateLoader).
 * 
 * @author swale
 */
public final class GfxdLockSet implements CompatibilitySpace {

  /**
   * An int representing max wait time for distributed lock in milliseconds.
   */
  public static int MAX_LOCKWAIT_VAL = GfxdConstants.MAX_LOCKWAIT_DEFAULT;

  /**
   * The factor used to set the VM {@link #MAX_VM_LOCKWAIT_VAL} from
   * {@link #MAX_LOCKWAIT_VAL}.
   */
  final static int MAX_VM_LOCKWAIT_RETRIES = 30;

  /**
   * An int representing max wait time for distributed lock on a single VM in
   * milliseconds. When this limit is exhausted then the lock acquisition is
   * retried (to avoid deadlocks) subject to maximum of
   * {@link GfxdConstants#MAX_LOCKWAIT} for the complete distributed acquisition.
   */
  public static int MAX_VM_LOCKWAIT_VAL = MAX_LOCKWAIT_VAL
      / MAX_VM_LOCKWAIT_RETRIES;

  /**
   * The factor used to set the VM {@link #MAX_WRITE_WAIT_RETRY} from
   * {@link #MAX_LOCKWAIT_VAL}.
   */
  final static int MAX_WRITE_WAIT_RETRIES = 10;

  /**
   * An int representing the max wait time while acquiring a write lock before
   * giving up and retrying due to possible deadlocks due to DD and container
   * lock order.
   */
  public static int MAX_WRITE_WAIT_RETRY = MAX_LOCKWAIT_VAL
      / MAX_WRITE_WAIT_RETRIES;

  // the status codes returned by acquireLock
  public static final int LOCK_FAIL = 0;
  public static final int LOCK_SUCCESS = 1;
  public static final int LOCK_REENTER = 2;

  /**
   * Number of retries already performed by the current thread for acquiring a
   * table/alias write lock.
   */
  private final static ThreadLocal<Map<Object, Integer>> numWriteRetries =
      new ThreadLocal<Map<Object, Integer>>() {
    @Override
    protected Map<Object, Integer> initialValue() {
      return new HashMap<Object, Integer>(5);
    }
  };

  /**
   * An int representing minimum wait time for local read lock in milliseconds.
   */
  public static final int READ_LOCKWAIT_FOR_REACQUIRE = 1000;

  /** Reference to the owner of this compatibility space. */
  private LockOwner owner;

  /** List of locks acquired so far with their types. */
  private final LockList acquiredLocks;

  /** The {@link GfxdLockService} used to do the real work. */
  private final GfxdLockService rwLockService;

  /**
   * Keeps track of any locks for which {@link GfxdLockService#freeResources}
   * has to be invoked at commit.
   */
  private ArrayList<Object> freeResourceList;

  /**
   * Number of open ResultSets etc. referring to this GfxdLockSet. This governs
   * whether {@link #unlockAll} will actually release all locks or not.
   */
  private int numRefs;

  /**
   * If there is an {@link GfxdResultCollector} that is pending to complete,
   * then allow the other thread to wait for it to complete before acquiring new
   * set of locks.
   */
  private volatile GfxdResultCollector<?> pendingRC;

  /**
   * Holds the thread, if any, that is waiting for an
   * {@link GfxdResultCollector} to complete collection of all results.
   */
  private volatile Thread rcWaiter;

  /** Time to wait for pending ResultCollector in loop. */
  private final static int RC_WAIT_NANOS = 100 * 1000 * 1000; // 100ms

  /**
   * Get the integer value from {@link GemFireStore} property set. This is like
   * {@link Integer#getInteger(String, int)} except that it uses the properties
   * in {@link GemFireStore} instead of system properties.
   */
  private static int getInteger(GemFireStore store, String propName,
      int defaultValue) {
    String val = null;
    if (store != null) {
      val = store.getBootProperty(propName);
    }
    if (val == null) {
      val = System.getProperty(propName);
    }
    if (val != null) {
      try {
        return Integer.decode(val).intValue();
      } catch (NumberFormatException e) {
      }
    }
    return defaultValue;
  }

  /**
   * Static method to initialize {@link GfxdConstants#MAX_LOCKWAIT},
   * {@link #MAX_VM_LOCKWAIT_VAL}, {@link #MAX_WRITE_WAIT_RETRY}
   * to given values, and return the {@link #MAX_VM_LOCKWAIT_VAL}. NOT
   * THREAD_SAFE.
   */
  public static int initConstants(GemFireStore store) {
    MAX_LOCKWAIT_VAL = getInteger(store, GfxdConstants.MAX_LOCKWAIT,
        GfxdConstants.MAX_LOCKWAIT_DEFAULT);
    if (MAX_LOCKWAIT_VAL != GfxdConstants.MAX_LOCKWAIT_DEFAULT) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
          "GfxdLockSet: setting maximum lock wait to " + MAX_LOCKWAIT_VAL + "ms");
    }
    MAX_VM_LOCKWAIT_VAL = MAX_LOCKWAIT_VAL / MAX_VM_LOCKWAIT_RETRIES;
    MAX_WRITE_WAIT_RETRY = MAX_LOCKWAIT_VAL / MAX_WRITE_WAIT_RETRIES;
    return MAX_VM_LOCKWAIT_VAL;
  }

  public GfxdLockSet(LockOwner owner, GfxdLockService lockService) {
    assert owner != null: "unexpected null LockOwner for GfxdLockSet";
    this.owner = owner;
    this.acquiredLocks = new LockList();
    this.rwLockService = lockService;
  }

  public final LockOwner getOwner() {
    return this.owner;
  }

  public final void setOwner(LockOwner newOwner) {
    this.owner = newOwner;
  }

  public final GfxdLockService getLockService() {
    return this.rwLockService;
  }

  /**
   * Wait for any pending {@link GfxdResultCollector}s that have still not
   * completed due to a previous streaming query, for example. This avoids using
   * synchronized() blocks to skip locking completely in case no wait is
   * required; also does not require a queue of waiters as would be tracked for
   * a usual java monitor.
   */
  public void waitForPendingRC() {
    GfxdResultCollector<?> pendingRC = this.pendingRC;
    if (pendingRC != null && pendingRC.getProcessor() != null) {
      final Thread thisThread = Thread.currentThread();
      final Thread waiter = this.rcWaiter;
      // we expect atmost one thread to wait for ResultCollector
      // waiter can be thisThread in some rare exception cleanup scenarios
      if (waiter != thisThread) {
        if (waiter != null) {
          SanityManager.THROWASSERT("Unexpected thread already waiting ["
              + waiter + "], current thread: " + thisThread);
        }
        this.rcWaiter = thisThread;
      }
      while ((pendingRC = this.pendingRC) != null) {
        if (GemFireXDUtils.TraceLock) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, toString()
              + "#waitForPendingRC: waiting for ResultCollector: " + pendingRC);
        }
        LockSupport.parkNanos(pendingRC, RC_WAIT_NANOS);
        // [bruce] always check for cache closure because application threads aren't interrupted
        Misc.checkIfCacheClosing(new InterruptedException());
//        if (Thread.interrupted()) {
//          Thread.currentThread().interrupt();
//          Misc.checkIfCacheClosing(new InterruptedException());
//        }
      }
      this.rcWaiter = null;
    }
  }

  /**
   * Set the given {@link GfxdResultCollector} as pending.
   */
  public void rcSet(final GfxdResultCollector<?> rc) {
    waitForPendingRC();
    if (GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, toString()
          + "#rcSet: setting new ResultCollector: " + rc);
    }
    this.pendingRC = rc;
  }

  /**
   * Method to be invoked from an {@link GfxdResultCollector} when it has
   * completed to signal any waiting thread that invoked {@link #rcWaiter}.
   */
  public void rcEnd(final GfxdResultCollector<?> rc) {
    final GfxdResultCollector<?> pendingRC = this.pendingRC;
    if (pendingRC != null) {
      if (rc == null || rc == pendingRC) {
        final Thread waiter = this.rcWaiter;
        this.pendingRC = null;
        if (waiter != null) {
          LockSupport.unpark(waiter);
        }
        if (GemFireXDUtils.TraceLock) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, toString()
              + "#rcEnd: cleared ResultCollector: " + rc + ", pending thread: "
              + waiter);
        }
      }
      else {
        if (GemFireXDUtils.TraceLock) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, toString()
              + "#rcEnd: ignored ResultCollector: " + rc + ", for pendingRC: "
              + pendingRC);
        }
      }
    }
  }

  /**
   * Request acquisition of a read or write lock. Also allows to release and
   * reacquire existing read locks for write lock requests to avoid deadlocks.
   * If an existing read lock is held and a write lock is requested then the
   * existing lock is released and upgraded to a write lock. Similarly an
   * existing local write lock will be upgraded to a distributed write lock.
   * Note that lock upgradations are NON-ATOMIC OPERATIONS, so the application
   * should be designed accordingly. Requests to de-escalate a lock (e.g.
   * existing write lock to read lock) are ignored.
   * 
   * @param lockObject
   *          the object to be locked
   * @param waitMillis
   *          if set to 0 then method will return immediately if lock is not
   *          available else it will wait for maximum given milliseconds for the
   *          lock to become available
   * @param forUpdate
   *          true if a write lock is desired and false for a read lock
   * @param local
   *          true to acquire a lock write lock as opposed to a distributed
   *          write lock; has no effect for read locks which are always local
   * @param reacquireReadLocks
   *          true to release existing read locks in the group for a write lock
   *          request, and reacquire those in order after acquiring the write
   *          lock
   * 
   * @return {@link #LOCK_FAIL} if the lock acquisition was unsuccessful,
   *         {@link #LOCK_REENTER} if the lock was already acquired in the lock
   *         set and was reentered, and {@link #LOCK_SUCCESS} if the lock was
   *         successfully acquired for the first time; a {@link #LOCK_FAIL} will
   *         be returned if the lock acquisition timed out or a no-wait lock is
   *         not available immediately
   */
  public int acquireLock(final GfxdLockable lockObject, long waitMillis,
      boolean forUpdate, boolean local, boolean reacquireReadLocks)
      throws StandardException {

    waitForPendingRC();
    LockType lockType = getLockType(forUpdate, local);
    LockEntry entry = this.acquiredLocks.getHead();
    final Object lockName = lockObject.getName();
    while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
      final GfxdLockable lockable = entry.getLockObject();
      if (lockable.getName().equals(lockName)) {
        final LockType currType = entry.getLockType();
        if (currType.isWrite()) {
          // nothing to be done since we already have good enough lock
          return LOCK_REENTER;
        }
        else if (currType == lockType) {
          // mark reentry for read locks
          if (currType.isRead()) {
            if (lockObject.traceLock()) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                  "GfxdLockSet: re-entering for " + currType + " lock on "
                      + lockObject);
            }
            entry.doReenter();
          }
          return LOCK_REENTER;
        }
        if (currType.isLocalWrite() && !forUpdate) {
          // already have a write lock so ignore read lock
          return LOCK_REENTER;
        }
        unlock(this.rwLockService, lockObject, this.owner, currType);
        this.acquiredLocks.removeEntry(entry);
        break;
      }
    }
    ArrayList<GfxdLockable> readLocks = null;
    long readWaitMillis = -1;
    if (reacquireReadLocks) {
      readWaitMillis = (waitMillis != 0 ? READ_LOCKWAIT_FOR_REACQUIRE : 0);
      if (!forUpdate) {
        // try to acquire the read lock with small wait and return if succeeds
        // else release the read locks acquired so far and reacquire to avoid
        // deadlocks
        if (addLock(lockObject, readWaitMillis, false, local, lockType)) {
          return LOCK_SUCCESS;
        }
      }
      // for write locks release and reacquire all locks
      readLocks = new ArrayList<GfxdLockable>();
      GfxdLockable lock;
      LockType currLockType;
      entry = this.acquiredLocks.getHead();
      while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
        currLockType = entry.lockType;
        if (currLockType.isRead()) {
          lock = entry.lockObject;
          unlock(this.rwLockService, lock, this.owner, LockType.READ);
          readLocks.add(lock);
          this.acquiredLocks.removeEntry(entry);
        }
      }
    }
    boolean success = addLock(lockObject, waitMillis, forUpdate, local,
        lockType);
    if (success && readLocks != null && readLocks.size() > 0) {
      long startTime = System.currentTimeMillis();
      long currentTime = startTime;
      success = false;
      while ((currentTime - startTime) < waitMillis) {
        success = true;
        for (int index = 0; index < readLocks.size(); ++index) {
          GfxdLockable lock = readLocks.get(index);
          if (!lock(this.rwLockService, lock, this.owner, readWaitMillis,
              false, false)) {
            // release all locks reacquired so far
            for (int rIndex = 0; rIndex < index; ++rIndex) {
              unlock(this.rwLockService, readLocks.get(rIndex), this.owner,
                  LockType.READ);
            }
            success = false;
            break;
          }
        }
        if (success) {
          break;
        }
        currentTime = System.currentTimeMillis();
      }
      if (success) {
        for (GfxdLockable lock : readLocks) {
          this.acquiredLocks.addLast(new LockEntry(lock, LockType.READ));
        }
      }
    }
    return success ? LOCK_SUCCESS : LOCK_FAIL;
  }

  public final LockType getLockType(final GfxdLockable lockObject) {
    LockEntry entry = this.acquiredLocks.getHead();
    final Object lockName = lockObject.getName();
    while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
      final GfxdLockable lockable = entry.getLockObject();
      if (lockable.getName().equals(lockName)) {
        return entry.getLockType();
      }
    }
    return null;
  }

  /**
   * Request release of an existing read or write lock. The lock release is
   * successful only if an existing lock of the same type
   * (read/write/local-write) is currently being held on the object in the given
   * group.
   * 
   * @param lockObject
   *          the object to be unlocked
   * @param forUpdate
   *          true if a write lock is desired and false for a read lock
   * @param local
   *          true to release a lock write lock as opposed to a distributed
   *          write lock; has no effect for read locks which are always local
   * 
   * @return true if the lock was successfully released and false otherwise; a
   *         false will only be returned if the lock on the given object in
   *         given group and of the given type (read/write/local-write) is not
   *         being currently held
   */
  public synchronized boolean releaseLock(final GfxdLockable lockObject,
      boolean forUpdate, boolean local) {
    LockEntry entry = this.acquiredLocks.getTail();
    final Object lockName = lockObject.getName();
    GfxdLockable lock;
    while ((entry = this.acquiredLocks.previousEntry(entry)) != null) {
      lock = entry.getLockObject();
      if (lock.getName().equals(lockName)) {
        // cannot remove the lock yet without checking for type to ensure
        // proper lock order
        final LockType lockType = entry.getLockType();
        final LockType expectedLockType = getLockType(forUpdate, local);
        if (lockType == expectedLockType) {
          if (!lockType.isRead() || entry.doRelease()) {
            unlock(this.rwLockService, lockObject, this.owner, lockType);
            this.acquiredLocks.removeEntry(entry);
            return true;
          }
          else {
            return false;
          }
        }
        else if (expectedLockType.isRead()
            || (expectedLockType.isLocalWrite() && lockType.isWrite())) {
          // do not unlock since we need to hold the "stronger" lock
          return false;
        }
      }
    }
    return false;
  }

  /**
   * Request unlock of all locks acquired so far. Typically used to release all
   * locks held in a transaction at the end of transaction, but with "force" as
   * false it is used by ResultSets etc. in non-transactional contexts to
   * release all locks only if there are no other open ResultSets being tracked.
   */
  public synchronized boolean unlockAll(boolean force, boolean removeRef) {
    if (SanityManager.isFineEnabled | GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
          "GfxdLockSet.unlockAll: force=" + force + " removeRef=" + removeRef
              + " numRefs=" + this.numRefs + " for " + this.owner);
    }
    boolean doUnlock = false;
    if (force) {
      // reset numRefs
      this.numRefs = 0;
      doUnlock = true;
    }
    else if (removeRef) {
      doUnlock = removeRef();
    }
    else {
      doUnlock = (this.numRefs <= 0);
    }
    if (doUnlock) {
      LockEntry entry = this.acquiredLocks.getHead();
      while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
        boolean success = false;
        try {
          unlock(this.rwLockService, entry.getLockObject(), this.owner,
              entry.getLockType());
          success = true;
        } finally {
          // remove from list even if failed to avoid retrying the same again
          // (and presumably failing again e.g. during system shutdown)
          if (!success) {
            this.acquiredLocks.removeEntry(entry);
          }
        }
      }
      this.acquiredLocks.clear();
      return true;
    }
    else {
      return false;
    }
  }

  /**
   * Add a reference for a ResultSet so that
   * {@link #unlockAll(boolean, boolean)} can track for any other open
   * ResultSets before releasing the locks.
   */
  public void addResultSetRef() {
    this.numRefs++;
    if (SanityManager.isFineEnabled | GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
          "GfxdLockSet.addResultSetRef: numRefs=" + this.numRefs + " for "
              + this.owner);
    }
  }

  private boolean removeRef() {
    switch (this.numRefs) {
      case 0:
        return true;
      case 1:
        this.numRefs = 0;
        return true;
      default:
        assert this.numRefs > 0: "unexpected numRefs=" + this.numRefs;
        this.numRefs--;
        return false;
    }
  }

  public int getNumRefs() {
    return this.numRefs;
  }

  /**
   * Add the given lock to the "to be freed resource list" so that
   * {@link GfxdLockService#freeResources} is invoked on it at commit. Not
   * thread-safe -- invoke within a sync block to handle concurrent access.
   */
  public void addToFreeResources(final GfxdLockable lockable) {
    if (this.freeResourceList == null) {
      this.freeResourceList = new ArrayList<Object>();
    }
    this.freeResourceList.add(lockable.getName());
  }

  /**
   * Invoke {@link GfxdLockService#freeResources(Object)} on all locks that were
   * added to the "to be freed resource list" by a call to
   * {@link #addToFreeResources(GfxdLockable)}. Not thread-safe -- invoke within
   * a sync block to handle concurrent access.
   */
  public void freeLockResources() {
    // check and free any resource in freeRourcesList (this will happen during
    // container drop commit)
    if (this.freeResourceList != null) {
      for (Object lockObject : this.freeResourceList) {
        this.rwLockService.freeResources(lockObject);
      }
      this.freeResourceList = null;
    }
  }

  public synchronized void dumpReadLocks(final StringBuilder msg,
      final String logPrefix, final Thread t) {
    boolean firstReadLock = true;
    LockEntry entry = this.acquiredLocks.getHead();
    while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
      if (entry.lockType.isRead()) {
        if (firstReadLock) {
          msg.append(logPrefix).append(": ").append(t);
          if (this.owner != null) {
            msg.append('[').append(this.owner).append(']');
          }
          msg.append(" has READ locks: ");
          entry.appendString(msg);
          firstReadLock = false;
        }
        else {
          msg.append(',');
          entry.appendString(msg);
        }
      }
    }
    if (!firstReadLock) {
      msg.append(SanityManager.lineSeparator);
    }
  }

  /**
   * Get the a list of a held read locks for debugging purposes.
   */
  public synchronized Collection<GfxdLockable> getReadLocksForDebugging() {
    Collection<GfxdLockable> results = new ArrayList<GfxdLockable>();
    LockEntry entry = this.acquiredLocks.getHead();
    while ((entry = this.acquiredLocks.nextEntry(entry)) != null) {
      if (entry.lockType.isRead()) {
        results.add(entry.lockObject);
      }
    }
    return results;
  }

  private static LockType getLockType(boolean forUpdate, boolean local) {
    return (!forUpdate ? LockType.READ : (!local ? LockType.WRITE
        : LockType.LOCAL_WRITE));
  }

  /**
   * @throws StandardException
   *           if a retry has to be attempted at higher level due to possibility
   *           of deadlock when acquiring a distributed write lock
   */
  public static boolean lock(final GfxdLockService lockService,
      final GfxdLockable lockObject, final Object owner, long waitMillis,
      final boolean forUpdate, final boolean local) throws StandardException {
    boolean success;
    if (forUpdate) {
      // check if we already hold the Data
      if (local) {
        success = lockService.getLocalLockService().writeLock(
            lockObject.getName(), owner, waitMillis, -1);
      }
      else {
        // check if we already hold the DataDictionary lock and in that case
        // wait for lesser amount of time and retry at higher level to avoid
        // some rare cases of deadlocks (#42901, #43019)
        boolean hasDDLock = false;
        if (waitMillis > 0) {
          final FabricDatabase db = Misc.getMemStore().getDatabase();
          final GfxdDataDictionary dd;
          if (db != null && (dd = db.getDataDictionary()) != null) {
            final boolean traceLock = lockObject.traceLock();
            final GfxdLockable ddLockObject = dd.getLockObject();;
            if (traceLock) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                  "GfxdLockSet: checking if " + owner + " already holds an "
                      + "exclusive lock on " + ddLockObject + " for lock of "
                      + lockObject);
            }
            if (lockObject != ddLockObject
                && lockService.hasWriteLock(ddLockObject.getName(), owner)) {
              waitMillis = waitMillis / MAX_WRITE_WAIT_RETRIES;
              hasDDLock = true;
            }
            if (traceLock) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                  "GfxdLockSet: hasDDLock=" + hasDDLock + " for " + owner
                      + " for lock of " + lockObject);
            }
          }
        }
        final Object lock = lockObject.getName();
        success = lockService.writeLock(lock, owner,
            waitMillis, -1);
        final Map<Object, Integer> retryMap = numWriteRetries.get();
        if (!success && hasDDLock) {
          // check if the number of retries has exceeded the max
          final Integer retries = retryMap.get(lock);
          if (retries == null || retries.intValue() <= MAX_WRITE_WAIT_RETRIES) {
            // increment the number of retries
            retryMap.put(lock, retries == null ? Integer.valueOf(1) : Integer
                .valueOf(retries.intValue() + 1));
            // fake a recompile to force a retry at higher level
            throw StandardException
                .newException(SQLState.LANG_STATEMENT_NEEDS_RECOMPILE);
          }
        }
        // reset the thread-local indicating the number of write retries
        retryMap.remove(lock);
      }
    }
    else {
      success = lockService.readLock(lockObject, owner, waitMillis);
    }
    return success;
  }

  public static void unlock(final GfxdLockService lockService,
      final GfxdLockable lockObject, final Object owner,
      final boolean forUpdate, final boolean local) {
    unlock(lockService, lockObject, owner, getLockType(forUpdate, local));
  }

  private static void unlock(final GfxdLockService lockService,
      final GfxdLockable lockObject, final Object owner, final LockType type)
      throws LockNotHeldException {
    if (type.isRead()) {
      lockService.readUnlock(lockObject);
    }
    else if (type.isLocalWrite()) {
      lockService.getLocalLockService().writeUnlock(lockObject, owner);
    }
    else {
      // we could get an exception when shutting down
      Throwable t = null;
      try {
        lockService.writeUnlock(lockObject.getName(), owner);
      } catch (RuntimeException ex) {
        t = ex;
      } catch (final Error err) {
        if (SystemFailure.isJVMFailureError(err)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        SystemFailure.checkFailure();
        // If the above returns then send back error since this may be assertion
        // or some other internal code bug.
        t = err;
      } finally {
        if (t != null) {
          // check for cache going down
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(t);
          if (t instanceof RuntimeException) {
            throw (RuntimeException)t;
          }
          throw (Error)t;
        }
      }
    }
  }

  private boolean addLock(final GfxdLockable lockObject, final long waitMillis,
      final boolean forUpdate, final boolean local, final LockType lockType)
      throws StandardException {
    if (lock(this.rwLockService, lockObject, this.owner, waitMillis, forUpdate,
        local)) {
      this.acquiredLocks.addLast(new LockEntry(lockObject, lockType));
      return true;
    }
    return false;
  }

  /**
   * Enumeration to indicate and store the type for a lock held in
   * {@link GfxdLockSet}. Currently the available types are: read, write,
   * local-write.
   * 
   * @author swale
   */
  public static enum LockType {
    READ, WRITE, LOCAL_WRITE;

    public final boolean isRead() {
      return (this == READ);
    }

    public final boolean isWrite() {
      return (this == WRITE);
    }

    public final boolean isLocalWrite() {
      return (this == LOCAL_WRITE);
    }
  }

  /**
   * A doubly linked circular list implementation that encapsulates both
   * {@link GfxdLockable} and {@link LockType} in a node of list.
   * 
   * @see LinkedList
   * @author swale
   */
  static final class LockList {

    /** The header of the list that is a marker and does not hold any data. */
    private final LockEntry header;

    /**
     * Constructs an empty list.
     */
    public LockList() {
      this.header = new LockEntry(null, null);
      this.header.next = this.header.previous = this.header;
    }

    /**
     * Get the element before the first element in the list that can be used
     * like an iterator to invoke {@link #nextEntry(LockEntry)}.
     */
    public LockEntry getHead() {
      return this.header;
    }

    /**
     * Get the entry after the current one or null if none present.
     */
    public LockEntry nextEntry(final LockEntry entry) {
      final LockEntry nextEntry = entry.next;
      if (nextEntry != this.header) {
        return nextEntry;
      }
      return null;
    }

    /**
     * Get the element after the last element in the list that can be used like
     * a reverse iterator to invoke {@link #previousEntry(LockEntry)}.
     */
    public LockEntry getTail() {
      return this.header;
    }

    /**
     * Get the entry before the current one or null if none present.
     */
    public LockEntry previousEntry(final LockEntry entry) {
      final LockEntry prevEntry = entry.previous;
      if (prevEntry != this.header) {
        return prevEntry;
      }
      return null;
    }

    /**
     * @see LinkedList#addFirst
     */
    public boolean addFirst(final LockEntry entry) {
      assert entry != null;
      addBefore(entry, this.header.next);
      return true;
    }

    /**
     * @see LinkedList#addLast
     */
    public boolean addLast(final LockEntry entry) {
      assert entry != null;
      addBefore(entry, this.header);
      return true;
    }

    /**
     * Remove the given {@link LockEntry} from this {@link LockList}.
     */
    public void removeEntry(final LockEntry entry) {
      if (entry == this.header) {
        throw new NoSuchElementException();
      }
      entry.previous.next = entry.next;
      entry.next.previous = entry.previous;
    }

    /**
     * @see Collection#clear()
     */
    public void clear() {
      this.header.next = this.header.previous = this.header;
    }

    private void addBefore(final LockEntry newEntry, final LockEntry entry) {
      newEntry.next = entry;
      newEntry.previous = entry.previous;
      newEntry.previous.next = newEntry;
      entry.previous = newEntry;
    }
  }

  /**
   * Encapsulates an {@link GfxdLockable} and its {@link LockType} in a node of
   * a {@link LockList}. It does not allow for null values for either of the two
   * (except for the special token header used internally by {@link LockList}).
   * 
   * @author swale
   */
  static final class LockEntry {

    private final GfxdLockable lockObject;

    private final LockType lockType;

    /**
     * The count of reentry in the lock for multiple acquireLocks. This will be
     * decremented in releaseLock but unlockAll will ignore it.
     */
    private int numReentry;

    private LockEntry next;

    private LockEntry previous;

    LockEntry(GfxdLockable lockObject, LockType lockType) {
      this.lockObject = lockObject;
      this.lockType = lockType;
    }

    public final GfxdLockable getLockObject() {
      return this.lockObject;
    }

    public final LockType getLockType() {
      return this.lockType;
    }

    public final void doReenter() {
      this.numReentry++;
    }

    public final boolean doRelease() {
      switch (this.numReentry) {
        case 0:
          return true;
        case 1:
          this.numReentry = 0;
          return false;
        default:
          assert this.numReentry > 0: "unexpected reentries=" + this.numReentry;
          this.numReentry--;
          return false;
      }
    }

    @Override
    public final boolean equals(Object other) {
      if (other instanceof LockEntry) {
        final LockEntry otherEntry = (LockEntry)other;
        return (this.lockType == otherEntry.lockType)
            && (this.lockObject.getName().equals(otherEntry.lockObject
                .getName()));
      }
      return false;
    }

    void appendString(StringBuilder sb) {
      sb.append(this.lockObject.getName());
      if (this.numReentry > 0) {
        sb.append("[reentries=").append(this.numReentry).append(']');
      }
    }
  }
}
