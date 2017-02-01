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

package com.pivotal.gemfirexd.internal.engine.locks.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.locks.QueuedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.ReadWriteLockObject;
import com.gemstone.gemfire.internal.cache.locks.TryLockObject;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockService.ReadLockState;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdReadWriteLock;
import com.pivotal.gemfirexd.internal.iapi.error.ShutdownException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class is an implementation of {@link GfxdReadWriteLock} for GemFireXD
 * specific needs. Unlike {@code WriterPreferenceReadWriteLock} or
 * {@code ReentrantReadWriteLock} this does not give a preference to writers to
 * avoid deadlocks. A case that can arise if readers wait on "waiting" writers
 * is: Thr1 -- A-rd, B-rw; Thr2 -- B-ww; Thr3 -- B-rd, A-rw; Thr4 -- A-ww
 * 
 * (rd: read-lock, rw: read-waiting, wr: write-lock, ww: write-waiting).
 * 
 * <p>
 * This is currently used by GemFireXD to acquire/release the write locks on
 * remote nodes. Since it is not guaranteed that the processor thread that
 * acquired the write lock will be the one that releases it, the
 * {@link ReentrantReadWriteLock} is not directly usable. Since the owner of the
 * write lock is checked on the originating node, the owner check can be skipped
 * on other nodes by passing a special owner to the
 * {@link #releaseWriteLock(Object)} method. By default this enforces that the
 * thread that invoked the write lock should be one to release it. The
 * {@code WriterPreferenceReadWriteLock} does not care about the owner thread of
 * the write lock. In addition this implementation is reentrant. It also allows
 * to specify whether the thread taking the write lock is allowed to downgrade
 * to a read lock (the reverse is never allowed in any case and will result in a
 * deadlock).
 * 
 * <p>
 * This implementation uses the {@link QueuedSynchronizer} class like the JDK
 * {@link ReentrantReadWriteLock} class for best efficiency. In particular
 * acquiring read locks without any write lock being held is completely
 * wait-free using the compare-and-swap primitives. Also, the addition of
 * reentrancy poses no additional performance overhead by allowing for a weak
 * form of thread-checks during lock release. The weak check being that only the
 * total count of readers is checked for consistency while the thread itself is
 * <i>not</i> checked to be the owner in read lock release. This allows for
 * multiple threads to work in the same transaction and let one thread release
 * all the locks, for example, in GemFireXD.
 * 
 * <p>
 * <b>Sample usage</b>. Here is some code sketch showing how to exploit
 * reentrancy to perform lock downgrading after updating a cache when the lock
 * is created so as to allow lock downgrading:
 * 
 * <pre>
 * class CachedData {
 *   Object data;
 *   volatile boolean cacheValid;
 *   GfxdReentrantReadWriteLock rwl = ...
 *   void processCachedData() {
 *     rwl.acquireReadLock();
 *     if (!cacheValid) {
 *        // upgrade lock:
 *        rwl.releaseReadLock();   // must release first to obtain writelock
 *        rwl.acquireWriteLock();
 *        if (!cacheValid) { // recheck
 *          data = ...
 *          cacheValid = true;
 *        }
 *        // downgrade lock
 *        rwl.acquireReadLock();  // reacquire read without giving up lock
 *        rwl.releaseWriteLock(); // release write, still hold read
 *     }
 *     use(data);
 *     rwl.releaseReadLock();
 *   }
 * }
 * </pre>
 * 
 * @see ReentrantReadWriteLock
 * 
 * @author swale
 * @since 6.5
 */
public final class GfxdReentrantReadWriteLock extends AtomicInteger implements
    GfxdReadWriteLock {

  private static final long serialVersionUID = -1440030740550786092L;

  /**
   * Performs all synchronization mechanics extending from
   * {@link AbstractQueuedSynchronizer}.
   */
  private final QueuedSynchronizer sync;

  /**
   * Name for the object this lock is used for.
   */
  private final Object lockName;

  /**
   * The current owner of the lock.
   */
  private Object lockOwner;

  /**
   * The value of "ack-wait-threshold" GemFire property that is also used to log
   * warning messages in case the lock is not obtained in the duration.
   */
  private final int waitThreshold;

  /**
   * Flags for this lock object including whether this lock is in global map of
   * {@link GfxdLocalLockService}.
   */
  byte flags;

  /**
   * If true then indicates that lock tracing is on for this lock.
   */
  byte traceLock;

  /**
   * Class prefix used for toString() output.
   */
  private static final String REF_PREFIX = "GfxdReentrantReadWriteLock@";

  /** indicates infinite wait */
  private static final int WAIT_INFINITE = -1;

  /**
   * indicates DistributedSystem was not found at construction for
   * {@link #waitThreshold}
   */
  private static final int DS_NOTFOUND = -2;

  /**
   * Flat to indicate that {@link #tryAcquireShared(int, Object, Object)} method
   * should wait for any current waiting writer else not.
   */
  private static final int WAIT_FOR_PENDING_WRITER = 1;

  /**
   * Maximum wait for any pending writers in milliseconds.
   */
  private static final long MAXWAIT_FOR_PENDING_WRITER =
      GfxdLockSet.MAX_LOCKWAIT_VAL / 10;

  /**
   * Mask to indicate whether this lock has been put in the global map of
   * {@link GfxdLocalLockService}.
   */
  static final byte IS_INMAP = 0x01;

  /**
   * Mask to indicate whether this lock can be downgraded from a write lock to a
   * read lock atomically.
   */
  static final byte ALLOW_LOCK_DOWNGRADE = 0x02;

  // flags for lock state below

  static final int SHARED_BITS = 20;

  static final int EXCLUSIVE_BITS = Integer.SIZE - SHARED_BITS;

  static final int SHARED_MASK = (1 << SHARED_BITS) - 1;

  static final int EXCLUSIVE_ONE = (1 << SHARED_BITS);

  static final int MAX_SHARED_COUNT = SHARED_MASK;

  static final int MAX_EXCLUSIVE_COUNT = (1 << EXCLUSIVE_BITS) - 1;

  static final int MAX_EXCLUSIVE_COUNT_1 = MAX_EXCLUSIVE_COUNT - 1;

  static final int MAX_SHARED_COUNT_1 = SHARED_MASK - 1;

  /**
   * Creates a new {@code GfxdReentrantReadWriteLock} with non-fair ordering
   * properties.
   */
  public GfxdReentrantReadWriteLock(Object name, boolean allowLockDowngrade) {
    this(checkName(name), allowLockDowngrade, getDSTimeoutSecs());
  }

  public static GfxdReentrantReadWriteLock createTemplate(
      boolean allowLockDowngrade) {
    return new GfxdReentrantReadWriteLock(null, allowLockDowngrade,
        getDSTimeoutSecs());
  }

  private GfxdReentrantReadWriteLock(Object name, boolean allowLockDowngrade,
      int timeoutSecs) {
    this.lockName = name;
    this.sync = new QueuedSynchronizer();
    this.waitThreshold = timeoutSecs;
    if (GemFireXDUtils.TraceLock) {
      this.traceLock = 1;
    }
    if (allowLockDowngrade) {
      this.flags = GemFireXDUtils.set(this.flags, ALLOW_LOCK_DOWNGRADE);
    }
  }

  private static Object checkName(Object name) {
    Assert.assertTrue(name != null,
        "Unexpected null name for GfxdReentrantReadWriteLock");
    return name;
  }

  /** Returns the number of shared holds represented in count */
  private static final int sharedCount(final int c) {
    return (c & SHARED_MASK);
  }

  /** Returns the number of exclusive holds represented in count */
  private static final int exclusiveCount(final int c) {
    return (c >>> SHARED_BITS);
  }

  private static final boolean isShared(final int c) {
    return (c & SHARED_MASK) != 0;
  }

  private static final boolean isExclusive(final int c) {
    return (c >>> SHARED_BITS) != 0;
  }

  private static int getDSTimeoutSecs() {
    final InternalDistributedSystem dsys = InternalDistributedSystem
        .getConnectedInstance();
    if (dsys != null) {
      final int timeoutSecs = dsys.getConfig().getAckWaitThreshold();
      if (timeoutSecs > 0) {
        return timeoutSecs;
      }
      return WAIT_INFINITE;
    }
    return DS_NOTFOUND;
  }

  private final boolean allowLockDowngrade() {
    return GemFireXDUtils.isSet(this.flags, ALLOW_LOCK_DOWNGRADE);
  }

  protected final long getMaxMillis() {
    return Long.MAX_VALUE / 1000; // division by 1000 for nanos
  }

  /**
   * @see GfxdReadWriteLock#getLockName()
   */
  public Object getLockName() {
    return this.lockName;
  }

  /**
   * @see ReadWriteLockObject#attemptReadLock(long, Object)
   */
  public boolean attemptReadLock(long msecs, final Object owner) {
    final boolean traceLock = traceLock();
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptReadLock: acquiring read lock, owner=")
              .append(owner).append(", for lock ")).toString());
    }
    // first try to acquire the lock without any blocking
    // wait for any pending writer to avoid starvation of writer
    if (tryAcquireShared(WAIT_FOR_PENDING_WRITER, owner, null) >= 0) {
      if (traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
            new StringBuilder("attemptReadLock: successfully acquired ")
                .append("read lock, owner=").append(owner)
                .append(", for lock ")).toString());
      }
      return true;
    }
    if (msecs == 0) {
      // return the result immediately for zero timeout
      if (traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
            new StringBuilder("attemptReadLock: FAILED acquire ")
                .append("read lock zero timeout, owner=").append(owner)
                .append(", for lock ")).toString());
      }
      return false;
    }
    if (msecs < 0) {
      msecs = getMaxMillis();
    }

    // we do this in units of "ack-wait-threshold" to allow writing log
    // messages in case lock acquire has not succeeded so far; also check for
    // CancelCriterion

    InternalDistributedSystem dsys = Misc.getDistributedSystem();
    LogWriterI18n logger = dsys.getLogWriterI18n();
    long timeoutMillis;
    if (this.waitThreshold > 0) {
      timeoutMillis = TimeUnit.SECONDS.toMillis(this.waitThreshold);
      if (timeoutMillis > MAXWAIT_FOR_PENDING_WRITER) {
        timeoutMillis = MAXWAIT_FOR_PENDING_WRITER;
      }
    }
    else {
      timeoutMillis = msecs;
    }

    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptReadLock: acquiring read lock with "
              + "timeout ").append(timeoutMillis).append("ms, owner=")
              .append(owner).append(", for lock ")).toString());
    }
    boolean res = false;
    long elapsedMillis = 0;
    int waitForPendingWriter = WAIT_FOR_PENDING_WRITER;
    while (msecs > timeoutMillis) {
      if (this.sync.tryAcquireSharedNanos(waitForPendingWriter, owner, this,
          TimeUnit.MILLISECONDS.toNanos(timeoutMillis), null, null)) {
        res = true;
        break;
      }
      dsys.getCancelCriterion().checkCancelInProgress(null);
      msecs -= timeoutMillis;
      if (waitForPendingWriter != 0
          && (elapsedMillis += timeoutMillis) >= MAXWAIT_FOR_PENDING_WRITER) {
        waitForPendingWriter = 0;
      }
      if (logger.warningEnabled()) {
        logger.warning(LocalizedStrings.LocalLock_Waiting, new Object[] {
            "GfxdReentrantReadWriteLock", Double.toString(
                timeoutMillis / 1000.0),
            "READ", this.lockName + ", owner=" + owner, toString(), msecs });
      }
      // increase the timeout by a factor for next iteration
      if (this.waitThreshold > 0) {
        timeoutMillis <<= 1;
      }
    }
    if (!res) {
      res = this.sync.tryAcquireSharedNanos(0, owner, this,
          TimeUnit.MILLISECONDS.toNanos(msecs), null, null);
    }
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptReadLock: ").append(res
              ? "successfully acquired" : "unsuccessful in").append(
                  " read lock with timeout ").append(timeoutMillis)
                  .append("ms, owner=").append(owner)
                  .append(", for lock ")).toString());
    }
    return res;
  }

  /**
   * @see ReadWriteLockObject#attemptWriteLock(long, Object)
   */
  public boolean attemptWriteLock(long msecs, final Object owner) {
    final boolean traceLock = traceLock();
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptWriteLock: acquiring write lock, owner=")
              .append(owner).append(", for lock ")).toString());
    }
    // first try to acquire the lock without any blocking
    if (tryAcquire(0 /* not used */, owner, null) >= 0) {
      if (traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
            new StringBuilder("attemptWriteLock: successfully acquired ")
                .append("write lock, owner=").append(owner)
                .append(", for lock ")).toString());
      }
      return true;
    }
    if (msecs == 0) {
      // return the result immediately for zero timeout
      if (traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
            new StringBuilder("attemptWriteLock: FAILED acquire ")
                .append("write lock with zero timeout, owner=").append(owner)
                .append(", for lock ")).toString());
      }
      return false;
    }
    if (msecs < 0) {
      msecs = getMaxMillis();
    }

    // we do this in units of "ack-wait-threshold" to allow writing log
    // messages in case lock acquire has not succeeded so far; also check for
    // CancelCriterion

    InternalDistributedSystem dsys = Misc.getDistributedSystem();
    LogWriterI18n logger = dsys.getLogWriterI18n();
    long timeoutMillis;
    if (this.waitThreshold > 0) {
      timeoutMillis = TimeUnit.SECONDS.toMillis(this.waitThreshold);
    }
    else {
      timeoutMillis = msecs;
    }

    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptWriteLock: acquiring write lock with "
              + "timeout ").append(timeoutMillis).append("ms, owner=")
              .append(owner).append(", for lock ")).toString());
    }
    boolean res = false;
    while (msecs > timeoutMillis) {
      if (this.sync.tryAcquireNanos(0 /* not used */, owner, this,
          TimeUnit.MILLISECONDS.toNanos(timeoutMillis), null, null)) {
        res = true;
        break;
      }
      dsys.getCancelCriterion().checkCancelInProgress(null);
      msecs -= timeoutMillis;
      if (logger.warningEnabled()) {
        logger.warning(LocalizedStrings.LocalLock_Waiting, new Object[] {
            "GfxdReentrantReadWriteLock", Double.toString(
                timeoutMillis / 1000.0),
            "WRITE", this.lockName + ", owner=" + owner, toString(), msecs });
      }
      // increase the timeout by a factor for next iteration
      if (this.waitThreshold > 0) {
        timeoutMillis <<= 1;
      }
    }
    if (!res) {
      res = this.sync.tryAcquireNanos(0 /* not used */, owner, this,
          TimeUnit.MILLISECONDS.toNanos(msecs), null, null);
    }
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("attemptWriteLock: ").append(res
              ? "successfully acquired" : "unsuccessful in").append(
                  " write lock with timeout ").append(timeoutMillis)
                  .append("ms, owner=").append(owner)
                  .append(", for lock ")).toString());
    }
    return res;
  }

  /**
   * @see ReadWriteLockObject#releaseReadLock()
   */
  public void releaseReadLock() {
    final boolean traceLock = traceLock();
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("releaseReadLock: releasing ")
            .append("read lock for ")).toString());
    }
    final boolean result = tryReleaseShared(0 /* not used */,
        null /* not used in release */, null);
    if (result) {
      this.sync.signalSharedWaiters();
    }
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("releaseReadLock: read lock release ")
            .append(result ? "successful" : "unsuccessful").append(
              " for lock ")).toString());
    }
  }

  /**
   * @see ReadWriteLockObject#releaseWriteLock(Object)
   */
  public void releaseWriteLock(final Object owner) {
    final boolean traceLock = traceLock();
    if (traceLock) {
      final StringBuilder sb = new StringBuilder(
          "releaseWriteLock: releasing write lock with owner=").append(owner)
          .append(", for ");
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
          .toString());
    }
    final boolean result = tryRelease(0 /* not used */, owner, null);
    if (result) {
      this.sync.signalWaiters();
    }
    if (traceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(
          new StringBuilder("releaseWriteLock: write lock release with owner=")
          .append(owner).append(result ? ", successful" : ", unsuccessful")
          .append(" for lock ")).toString());
    }
  }

  /**
   * @see GfxdReadWriteLock#hasReadLock()
   */
  public ReadLockState hasReadLock() {
    return ReadLockState.UNKNOWN;
  }

  /**
   * Queries if the write lock is held by the given owner.
   * 
   * @return {@code true} if the given owner (can be current thread) holds the
   *         write lock and {@code false} otherwise
   */
  public final boolean hasWriteLock(final Object owner) {
    return isExclusive(getState())
        && ArrayUtils.objectEquals(owner, this.lockOwner);
  }

  /**
   * @see GfxdReadWriteLock#getWriteLockOwner()
   */
  public final Object getWriteLockOwner() {
    return this.lockOwner;
  }

  /**
   * @see GfxdReadWriteLock#newLock(Object)
   */
  public GfxdReentrantReadWriteLock newLock(Object name) {
    int timeoutSecs = this.waitThreshold;
    if (timeoutSecs == DS_NOTFOUND) {
      timeoutSecs = getDSTimeoutSecs();
    }
    return new GfxdReentrantReadWriteLock(checkName(name),
        allowLockDowngrade(), timeoutSecs);
  }

  /**
   * Queries the number of read locks held for this lock. This method is
   * designed for use in monitoring system state, not for synchronization
   * control.
   * 
   * @return the number of read locks held.
   */
  public final int numReaders() {
    return sharedCount(getState());
  }

  // TryLockObject interface implementation below

  /**
   * @see TryLockObject#getState()
   */
  public final int getState() {
    return super.get();
  }

  /**
   * @see TryLockObject#tryAcquire(int, Object, Object)
   */
  @Override
  public final int tryAcquire(int arg, Object owner, Object context) {
    // If read count is non-zero or write count non-zero and given owner is
    // not the owner of the lock then fail. If the number of write locks has
    // exceeded the maximum then throw an internal error. Else, try to acquire
    // the lock by increasing the number of write holds and set the owner if
    // successful.
    for (;;) {
      final int currentState = getState();
      if (currentState == 0) {
        if (compareAndSet(0, EXCLUSIVE_ONE)) {
          this.lockOwner = owner;
          this.sync.setOwnerThread();
          return 1;
        }
      }
      final boolean traceLock = traceLock();
      final int currentWriteHolds = exclusiveCount(currentState);
      if (currentWriteHolds > 0) {
        if (!ArrayUtils.objectEquals(this.lockOwner, owner)) {
          if (traceLock) {
            final StringBuilder sb = new StringBuilder();
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
                .append(": tryAcquire: waiting for writer.").toString());
          }
          return -1;
        }
        if (currentWriteHolds == MAX_EXCLUSIVE_COUNT) {
          throw new IllegalMonitorStateException(
              "Maximum write lock count exceeded " + MAX_EXCLUSIVE_COUNT);
        }
        if (traceLock) {
          final StringBuilder sb = new StringBuilder();
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
              .append(": tryAcquire: re-entering for write.").toString());
        }
      }
      else if (isShared(currentState)) {
        if (traceLock) {
          final StringBuilder sb = new StringBuilder();
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
              .append(": tryAcquire: waiting for reader(s).").toString());
        }
        return -1;
      }

      if (compareAndSet(currentState, currentState + EXCLUSIVE_ONE)) {
        this.lockOwner = owner;
        this.sync.setOwnerThread();
        if (currentWriteHolds != MAX_EXCLUSIVE_COUNT_1) {
          return 1;
        }
        return 0;
      }
    }
  }

  /**
   * @see TryLockObject#tryRelease(int, Object, Object)
   */
  @Override
  public final boolean tryRelease(int arg, final Object owner, Object context) {
    // Check if currently this lock is being held in exclusive mode and also
    // check for the owner of this lock if held currently. Then decrement the
    // number of holds and return true if number of holds has gone down to zero
    // else set the new state while still holding the lock in this thread.

    int currentState = getState(); // volatile read before reading lockOwner
    if (owner != null && !owner.equals(this.lockOwner)) {
      IllegalMonitorStateException imse = new IllegalMonitorStateException(
          "attempt to release exclusive lock by a non owner [" + owner
              + "], current owner [" + this.lockOwner + ']');
      // if system is going down then this can happen in some rare cases
      Misc.getDistributedSystem().getCancelCriterion()
          .checkCancelInProgress(imse);
      throw imse;
    }

    for (;;) {
      if (currentState == EXCLUSIVE_ONE) {
        // we must clear the lock owner before giving up the lock else may
        // overwrite someone else's owner
        this.lockOwner = null;
        this.sync.clearOwnerThread();
        if (compareAndSet(EXCLUSIVE_ONE, 0)) {
          return true;
        }
      }
      final int writeHolds = exclusiveCount(currentState);
      if (writeHolds == 0) {
        IllegalMonitorStateException imse = new IllegalMonitorStateException(
            "write count is zero in release");
        // if system is going down then this can happen in some rare cases
        Misc.getDistributedSystem().getCancelCriterion()
            .checkCancelInProgress(imse);
        throw imse;
      }

      if (writeHolds == 1) {
        // we must clear the lock owner before giving up the lock else may
        // overwrite someone else's owner
        this.lockOwner = null;
        this.sync.clearOwnerThread();
      }
      if (compareAndSet(currentState, currentState - EXCLUSIVE_ONE)) {
        if (writeHolds != 1 && traceLock()) {
          final StringBuilder sb = new StringBuilder();
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
              .append(": tryRelease: still remaining ").append(writeHolds - 1)
              .append(" write holds for this thread.").toString());
        }
        return true;
      }
      currentState = getState();
    }
  }

  /**
   * @see TryLockObject#tryAcquireShared(int, Object, Object)
   */
  @Override
  public final int tryAcquireShared(int arg, Object owner, Object context) {
    // If this is write locked by another thread then fail.
    // If the read count has reached the maximum limit then throw an internal
    // error. Else increment the current number of holds and try to set the
    // state. Repeat these steps indefinitely.
    for (;;) {
      final int currentState = getState();
      if (currentState == 0) {
        if (compareAndSet(0, 1)) {
          return 1;
        }
      }
      // We allow for atomic downgrade of lock from write to shared, so an
      // owner can hold both locks simultaneously
      if (isExclusive(currentState)) {
        final boolean allowLockDowngrade = allowLockDowngrade();
        if (!allowLockDowngrade
            || !ArrayUtils.objectEquals(owner, this.lockOwner)) {
          if (traceLock()) {
            final StringBuilder sb = new StringBuilder();
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
                .append(": tryAcquireShared: waiting on writer with ")
                .append("allowLockDowngrade=").append(allowLockDowngrade)
                .toString());
          }
          return -1;
        }
      }
      final int currentReadHolds = sharedCount(currentState);
      if (currentReadHolds == MAX_SHARED_COUNT) {
        throw new IllegalMonitorStateException("Maximum read-only "
            + "lock count exceeded: " + MAX_SHARED_COUNT);
      }
      // check for any waiting writer to avoid starvation
      if (arg == WAIT_FOR_PENDING_WRITER
          && this.sync.apparentlyFirstQueuedIsExclusive()) {
        return -1;
      }
      if (compareAndSet(currentState, currentState + 1)) {
        // We need to return 0 when no further read will succeed else some +ve
        // number.
        if (currentReadHolds != MAX_SHARED_COUNT_1) {
          return 1;
        }
        return 0;
      }
    }
  }

  /**
   * @see TryLockObject#tryReleaseShared(int, Object, Object)
   */
  @Override
  public final boolean tryReleaseShared(int arg, Object owner, Object context) {
    // If there are no read holds on this lock then fail. Else decrement the
    // number of read holds and try to set the state. Repeat these steps
    // indefinitely.
    for (;;) {
      final int currentState = getState();
      if (currentState == 1) {
        if (compareAndSet(1, 0)) {
          return true;
        }
      }
      final int readHolds = sharedCount(currentState);
      if (readHolds == 0) {
        IllegalMonitorStateException imse = new IllegalMonitorStateException(
            "read-only lock count is zero in release");
        // if system is going down then this can happen in some rare cases
        Misc.getDistributedSystem().getCancelCriterion()
            .checkCancelInProgress(imse);
        throw imse;
      }
      if (compareAndSet(currentState, currentState - 1)) {
        if (readHolds != 1 && traceLock()) {
          final StringBuilder sb = new StringBuilder();
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK, fillSB(sb)
              .append(": tryReleaseShared: remaining ").append(readHolds - 1)
              .append(" read holds for this lock.").toString());
        }
        return true;
      }
    }
  }

  // End TryLockObject interface implementation

  // Instrumentation and status

  /**
   * @see GfxdReadWriteLock#inMap()
   */
  public final boolean inMap() {
    return GemFireXDUtils.isSet(this.flags, IS_INMAP);
  }

  /**
   * @see GfxdReadWriteLock#setInMap(boolean)
   */
  public final void setInMap(boolean inMap) {
    this.flags = GemFireXDUtils.set(this.flags, IS_INMAP, inMap);
  }

  /**
   * @see GfxdReadWriteLock#traceLock()
   */
  public final boolean traceLock() {
    return (this.traceLock == 1);
  }

  /**
   * @see GfxdReadWriteLock#setTraceLock()
   */
  public final void setTraceLock() {
    this.traceLock = 1;
  }

  /**
   * Dump lock information at info level for this object including all writer
   * and reader threads.
   * 
   * @param msg
   *          {@link StringBuilder} to append the lock information
   * @param lockObject
   *          the underlying associated object, if any, that has been locked
   * @param logPrefix
   *          any prefix to be prepended before the lock information
   */
  public final synchronized void dumpAllThreads(StringBuilder msg,
      Object lockObject, String logPrefix) {
    final int state = getState();
    final Object writer = this.lockOwner;
    final Collection<Thread> readWaiters = this.sync.getSharedQueuedThreads();
    final Collection<Thread> writeWaiters = this.sync
        .getExclusiveQueuedThreads();

    if (writer != null) {
      msg.append(logPrefix).append(": Lock object [").append(lockObject)
          .append(",state=0x").append(Integer.toHexString(state))
          .append("] WRITE locked by ").append(writer.toString())
          .append(SanityManager.lineSeparator);
    }
    dumpReaders(msg, lockObject, state, logPrefix);
    if (writeWaiters.size() > 0) {
      msg.append(logPrefix).append(": Lock object [").append(lockObject)
          .append(",state=0x").append(Integer.toHexString(state))
          .append("] has WRITE lock waiters: ").append(writeWaiters.toString())
          .append(SanityManager.lineSeparator);
    }
    if (readWaiters.size() > 0) {
      msg.append(logPrefix).append(": Lock object [").append(lockObject)
          .append(",state=0x").append(Integer.toHexString(state))
          .append("] has READ lock waiters: ").append(readWaiters.toString())
          .append(SanityManager.lineSeparator);
    }
  }

  /**
   * Get a the list of threads waiting on this lock for debugging purposes
   */
  public final synchronized Collection<Thread> getBlockedThreadsForDebugging() {
    Collection<Thread> shared = this.sync.getSharedQueuedThreads();
    Collection<Thread> exclusive = this.sync.getExclusiveQueuedThreads();
    ArrayList<Thread> results = new ArrayList<Thread>(shared.size()
        + exclusive.size());
    results.addAll(shared);
    results.addAll(exclusive);
    return results;
  }

  /**
   * @see GfxdReadWriteLock#dumpAllReaders(StringBuilder, String)
   */
  public final void dumpAllReaders(final StringBuilder msg,
      final String logPrefix) {
    msg.append(SanityManager.lineSeparator);
    // traverse all the available contexts to determine the available readers
    final GemFireXDUtils.Visitor<LanguageConnectionContext> dumpReadLocks =
        new GemFireXDUtils.Visitor<LanguageConnectionContext>() {
          @Override
          public boolean visit(LanguageConnectionContext lcc) {
            try {
              final TransactionController tc = lcc.getTransactionExecute();
              final GfxdLockSet lockSet;
              if (tc != null &&
                  (lockSet = ((GemFireTransaction)tc).getLockSpace()) != null) {
                lockSet.dumpReadLocks(msg, logPrefix,
                    lcc.getContextManager().getActiveThread());
              }
            } catch (ShutdownException se) {
              // ignore shutdown exception here
            }
            return true;
          }
        };
    GemFireXDUtils.forAllContexts(dumpReadLocks);
  }

  protected void dumpReaders(StringBuilder msg, Object lockObject,
      int state, String logPrefix) {
    final int numReaders = numReaders();
    if (numReaders > 0) {
      msg.append(logPrefix).append(": Lock object [").append(lockObject)
          .append(",state=0x").append(Integer.toHexString(state))
          .append("] has been ").append(" READ locked by ").append(numReaders)
          .append(" threads.").append(SanityManager.lineSeparator);
    }
  }

  // Useful methods from java.util.concurrent.locks.ReentrantReadWriteLock

  /**
   * Queries if the read lock is held by any thread. This method is designed
   * for use in monitoring system state, not for synchronization control.
   * 
   * @return {@code true} if any thread holds the read lock and {@code false}
   *         otherwise
   */
  public final boolean isReadLocked() {
    return isShared(getState());
  }

  /**
   * Queries if the write lock is held by any thread. This method is designed
   * for use in monitoring system state, not for synchronization control.
   * 
   * @return {@code true} if any thread holds the write lock and {@code false}
   *         otherwise
   */
  public final boolean isWriteLocked() {
    return isExclusive(getState());
  }

  /**
   * Queries the number of reentrant write holds on this lock by the current
   * thread. A writer thread has a hold on a lock for each lock action that is
   * not matched by an unlock action.
   * 
   * @return the number of holds on the write lock by the current thread, or
   *         zero if the write lock is not held by the current thread
   */
  public int getWriteHoldCount(Object owner) {
    final int writeHolds = exclusiveCount(getState());
    return (writeHolds > 0 && ArrayUtils.objectEquals(owner, this.lockOwner)
        ? writeHolds : 0);
  }

  /**
   * Queries whether any threads are waiting to acquire the read or write lock.
   * Note that because cancellations may occur at any time, a {@code true}
   * return does not guarantee that any other thread will ever acquire a lock.
   * This method is designed primarily for use in monitoring of the system
   * state.
   * 
   * @return {@code true} if there may be other threads waiting to acquire the
   *         lock
   */
  public final boolean hasQueuedThreads() {
    return this.sync.hasQueuedThreads();
  }

  /**
   * Queries whether the given thread is waiting to acquire either the read or
   * write lock. Note that because cancellations may occur at any time, a
   * {@code true} return does not guarantee that this thread will ever acquire a
   * lock. This method is designed primarily for use in monitoring of the system
   * state.
   * 
   * @param thread
   *          the thread
   * @return {@code true} if the given thread is queued waiting for this lock
   * @throws NullPointerException
   *           if the thread is null
   */
  public final boolean hasQueuedThread(Thread thread) {
    return this.sync.isQueued(thread);
  }

  /**
   * Returns an estimate of the number of threads waiting to acquire either the
   * read or write lock. The value is only an estimate because the number of
   * threads may change dynamically while this method traverses internal data
   * structures. This method is designed for use in monitoring of the system
   * state, not for synchronization control.
   * 
   * @return the estimated number of threads waiting for this lock
   */
  public final int getQueueLength() {
    return this.sync.getQueueLength();
  }

  /**
   * Returns a string identifying this lock, as well as its lock state. The
   * state, in brackets, includes the String {@code "writers="} followed by the
   * number of reentrantly held write locks, and the String {@code "readers="}
   * followed by the number of held read locks.
   * 
   * @return a string identifying this lock, as well as its lock state
   */
  @Override
  public final String toString() {
    return fillSB(new StringBuilder()).toString();
  }

  private StringBuilder toString(StringBuilder sb) {
    sb.append(REF_PREFIX).append(Integer.toHexString(hashCode())).append(',');
    return this.sync.toObjectString(sb).append("[name=").append(this.lockName)
        .append(']');
  }

  public StringBuilder fillSB(StringBuilder sb) {
    final int count = getState();
    final int readers = sharedCount(count);
    final int writers = exclusiveCount(count);

    toString(sb).append(" [");
    if (writers > 0) {
      sb.append("writer=").append(this.lockOwner).append("(count=")
          .append(writers).append("), ");
    }
    return sb.append("readers=").append(readers).append(']');
  }
}
