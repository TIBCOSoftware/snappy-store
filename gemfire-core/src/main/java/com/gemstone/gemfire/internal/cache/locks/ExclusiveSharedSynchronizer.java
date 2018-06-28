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

package com.gemstone.gemfire.internal.cache.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;

/**
 * Base class that encapsulates non-blocking locking for different locking modes
 * as specified in {@link ExclusiveSharedLockObject} by manipulation of the
 * internal lock state integer only. The wait queue for state has been split out
 * in {@link QueuedSynchronizer} class.
 * 
 * Also provides helper protected methods to provide blocking lock when
 * explicitly provided a {@link QueuedSynchronizer} object.
 * 
 * This class now extends {@link AtomicInteger} instead of using
 * AtomicIntegerFieldUpdater to avoid the extra class cast and instanceof checks
 * in the latter when the base class does not match the actual implementation
 * class.
 * 
 * This implementation allows for providing arbitrary owner of the object for
 * reentrancy rather than requiring the current Thread as the owner. However,
 * the assignment to owner is done after CAS is done. This introduces a race
 * whereby reader can read a stale value of owner and mistakenly not reenter.
 * Even conceptually if two different threads come in concurrently with the same
 * owner then it is not possible to guard the underlying object against
 * concurrent access in any case. So users of this class must take care to not
 * try and reenter from two or more threads concurrently for the same lock
 * owner.
 * 
 * There is provision to atomically upgrade a lock from write-shared (EX_SH)
 * mode to exclusive (EX) mode. In addition users can get hold of a "weaker"
 * lock while holding a "stronger" lock e.g. shared (SH) lock when holding the
 * exclusive (EX) lock. Among other things this also allows for atomic downgrade
 * of locks. There is one exception: users can't acquire the write-shared
 * (EX_SH) lock while holding the exclusive (EX) lock. This restriction is due
 * to the way in which the lock counts are maintained and not an inherent design
 * limitation.
 * 
 * @see QueuedSynchronizer
 * @see ExclusiveSharedLockObject
 * 
 * @author kneeraj, swale
 * @since 7.0
 */
public abstract class ExclusiveSharedSynchronizer extends AtomicInteger
    implements TryLockObject, ExclusiveSharedLockObject {

  private static final long serialVersionUID = 4991816805286998913L;

  protected static final int READ_SHARED_MODE = LockMode.READ_SHARED_MODE;
  protected static final int READ_ONLY_MODE = LockMode.READ_ONLY_MODE;
  protected static final int WRITE_SHARED_MODE = LockMode.WRITE_SHARED_MODE;
  protected static final int WRITE_EXCLUSIVE_MODE =
      LockMode.WRITE_EXCLUSIVE_MODE;
  protected static final int MODE_MASK = LockMode.MODE_MASK;

  /** To allow READ_ONLY to co-exist with EX_SH but not with EX. */
  public static final int ALLOW_READ_ONLY_WITH_EX_SH =
      (WRITE_EXCLUSIVE_MODE << 1);

  /**
   * To allow a single READ_ONLY to co-exist with EX_SH but not more than one.
   * This is used when higher layer knows that one READ_ONLY is being held in
   * the same transactional context, so EX_SH can be held concurrently. Using
   * this flag also results in the READ_ONLY lock being released on the object
   * atomically so higher layer should not be doing it.
   */
  public static final int ALLOW_ONE_READ_ONLY_WITH_EX_SH =
      (ALLOW_READ_ONLY_WITH_EX_SH << 1);

  /**
   * Conflict SH locks against EX in case of TXBatch flush piggy-backing phase1
   * commits in REPEATABLE_READ to avoid deadlocks (see comments in #44743).
   */
  public static final int CONFLICT_WITH_EX =
      (ALLOW_ONE_READ_ONLY_WITH_EX_SH << 1);

  /**
   * Release all locks of given mode. Should be used only if the caller is sure
   * to be the only owner of that lock.
   */
  public static final int RELEASE_ALL_MASK = (CONFLICT_WITH_EX << 1);

  /**
   * Flag set when an SH lock is being acquired for upgrade to EX_SH later, if
   * row qualifies (else SH lock will be released if row does not qualify).
   */
  public static final int FOR_UPDATE = (RELEASE_ALL_MASK << 1);

  /**
   * Flag set to conflict READ_COMMITTED writes with REPEATABLE_READ reads (or
   * other transient reads) during operation time itself rather than during
   * EX_SH->EX upgrade. Cases where this is preferred is when RC is using
   * single-phase commit and thus cannot conflict during EX_SH->EX while we
   * don't want RR reads to start conflicting.
   */
  public static final int CONFLICT_WRITE_WITH_SH = (FOR_UPDATE << 1);

  //---------- number of bits and ONE for different modes below

  protected static final int READ_SHARED_BITS = 15;

  protected static final int READ_ONLY_BITS = 7;

  protected static final int READ_ONLY_ONE = 1 << READ_SHARED_BITS;

  protected static final int READ_BITS = READ_SHARED_BITS + READ_ONLY_BITS;

  // keep a few bits to stuff in unrelated booleans if required;
  // 3 are already used below
  protected static final int WRITE_BITS = (Integer.SIZE - READ_BITS) - 6;

  protected static final int WRITE_ONE = 1 << READ_BITS;

  //---------- masks and max counts for different modes below

  protected static final int READ_SHARED_MASK = (1 << READ_SHARED_BITS) - 1;

  protected static final int MAX_READ_SHARED_COUNT = READ_SHARED_MASK;

  protected static final int MAX_READ_SHARED_COUNT_1 =
    MAX_READ_SHARED_COUNT - 1;

  protected static final int READ_SHARED_CLEAR_MASK = ~READ_SHARED_MASK;

  protected static final int READ_ONLY_MASK =
    (WRITE_ONE - 1) - READ_SHARED_MASK;

  protected static final int MAX_READ_ONLY_COUNT = (1 << READ_ONLY_BITS) - 1;

  protected static final int MAX_READ_ONLY_COUNT_1 = MAX_READ_ONLY_COUNT - 1;

  protected static final int READ_ONLY_CLEAR_MASK = ~READ_ONLY_MASK;

  protected static final int READ_MASK = READ_SHARED_MASK | READ_ONLY_MASK;

  protected static final int MAX_WRITE_COUNT = (1 << WRITE_BITS) - 1;

  protected static final int MAX_WRITE_COUNT_1 = MAX_WRITE_COUNT - 1;

  protected static final int WRITE_EXCLUSIVE_BIT = WRITE_ONE << WRITE_BITS;

  protected static final int WRITE_MASK = WRITE_EXCLUSIVE_BIT - WRITE_ONE;

  /**
   * This is set when the EX_SH lock acquired allows for READ_ONLY lock to be
   * held simultaneously ({@link #ALLOW_READ_ONLY_WITH_EX_SH}).
   */
  protected static final int WRITE_ALLOWS_READ_ONLY = WRITE_EXCLUSIVE_BIT << 1;

  protected static final int WRITE_CLEAR_MASK =
    ~(WRITE_MASK | WRITE_EXCLUSIVE_BIT | WRITE_ALLOWS_READ_ONLY);

  /**
   * Indicates whether this lock has any waiters on it or not to check during
   * lock release.
   */
  protected static final int HAS_WAITERS = WRITE_ALLOWS_READ_ONLY << 1;

  /**
   * Enable detailed tracing of lock acquisition and release. This can be set
   * either by the system property "gemfire.TRACE_LOCKING" or will be enabled at
   * finer logging level or higher. This is deliberately not volatile since its
   * set at the very DS connect time and even otherwise it is not vital to
   * immediately reflect the value in all threads.
   */
  public static boolean TRACE_LOCK = TRACE_ON();

  /**
   * The system property suffix for {@link #TRACE_LOCK}.
   */
  public static final String TRACE_LOCK_PROPERTY =
      DistributionConfig.GEMFIRE_PREFIX + "TRACE_LOCKING";

  /**
   * Enable compact tracing of lock acquisition and release. This can be set
   * either by the system property "gemfire.TRACE_LOCKING_COMPACT" or will be
   * enabled at finer logging level or higher. This is deliberately not volatile
   * since its set at the very DS connect time and even otherwise it is not
   * vital to immediately reflect the value in all threads.
   */
  public static boolean TRACE_LOCK_COMPACT = TRACE_LOCK || TRACE_COMPACT_ON();

  /**
   * The system property suffix for {@link #TRACE_LOCK_COMPACT}.
   */
  public static final String TRACE_LOCK_COMPACT_PROPERTY =
      DistributionConfig.GEMFIRE_PREFIX + "TRACE_LOCKING_COMPACT";

  /**
   * The maximum timeout in milliseconds to use for lock acquisition when using
   * the waiting-mode in the {@link LockingPolicy}.
   * 
   * Default value is 60 seconds.
   * 
   * TODO: TX: make this a per TX setting
   * 
   * @see LockingPolicy#getTimeout(Object, LockMode, LockMode, int, long)
   */
  public static int LOCK_MAX_TIMEOUT = LOCK_MAX_TIMEOUT();

  /**
   * Minimum time to wait (in millis) before throwing a write-write conflict for
   * the case of READ_COMMITTED or REPEATABLE_READ.
   * 
   * Default value is 0.
   */
  public static int WRITE_LOCK_TIMEOUT = WRITE_LOCK_TIMEOUT();

  /**
   * Minimum time to wait (in millis) before throwing a read-write conflict for
   * the case of REPEATABLE_READ, or write-write conflict for READ_COMMITTED in
   * case where update scan is taking the read lock first.
   * 
   * Default value is {@value #DEFAULT_READ_TIMEOUT}.
   */
  public static int READ_LOCK_TIMEOUT = READ_LOCK_TIMEOUT();

  public static final int DEFAULT_READ_TIMEOUT = 500;

  /**
   * System property for {@link #LOCK_MAX_TIMEOUT}.
   */
  public static final String LOCK_MAX_TIMEOUT_PROP = "gemfire.LOCK_MAX_TIMEOUT";

  /**
   * System property for {@link #WRITE_LOCK_TIMEOUT}.
   * 
   * TODO: TX: make this per transaction/connection
   */
  public static final String WRITE_LOCK_TIMEOUT_PROP =
      "gemfire.WRITE_LOCK_TIMEOUT";

  /**
   * System property for {@link #READ_LOCK_TIMEOUT}.
   * 
   * TODO: TX: make this per transaction/connection
   */
  public static final String READ_LOCK_TIMEOUT_PROP =
      "gemfire.READ_LOCK_TIMEOUT";

  /**
   * Caches this VM's DistributedSystem. Deliberately not volatile since we are
   * okay with threads seeing a little old value and then setting this again.
   */
  private static InternalDistributedSystem DSYS;

  /**
   * Caches this VM's {@link DistributionConfig#getAckWaitThreshold()} used for
   * logging warnings when waiting for lock acquisition.Deliberately not
   * volatile since we are okay with threads seeing a little old value and then
   * setting this again.
   */
  private static int WAIT_THRESHOLD;

  protected static final int writeCount(final int c) {
    return (WRITE_MASK & c) >>> READ_BITS;
  }

  protected static final int readSharedCount(final int c) {
    return (READ_SHARED_MASK & c);
  }

  protected static final int readOnlyCount(final int c) {
    return (READ_ONLY_MASK & c) >>> READ_SHARED_BITS;
  }

  /**
   * Check if the given synchronization state is for an {@link LockMode#EX}
   * lock.
   */
  public static final boolean isExclusive(final int c) {
    return (WRITE_EXCLUSIVE_BIT & c) != 0;
  }

  /**
   * Check if the given synchronization state is for an {@link LockMode#EX} or
   * {@link LockMode#EX_SH} lock.
   */
  public static final boolean isWrite(final int c) {
    return (WRITE_MASK & c) != 0;
  }

  /**
   * Check if the given synchronization state is for an {@link LockMode#SH} or
   * {@link LockMode#READ_ONLY} lock.
   */
  public static final boolean isRead(final int c) {
    return (READ_MASK & c) != 0;
  }

  /**
   * Check if the given synchronization state is for an
   * {@link LockMode#READ_ONLY} lock.
   */
  public static final boolean isReadOnly(final int c) {
    return (READ_ONLY_MASK & c) != 0;
  }

  static {
    final long LARGEST_MASK = HAS_WAITERS & 0xFFFFFFFFL;
    Assert.assertTrue(LARGEST_MASK <= (1L << (Integer.SIZE - 1)),
        "unexpected overflow in " + "HAS_WAITERS: 0x"
            + Long.toHexString(LARGEST_MASK));
  }

  /**
   * Creates a new <tt>ExclusiveSharedSynchronizer</tt> instance with initial
   * synchronization state of zero.
   */
  protected ExclusiveSharedSynchronizer() {
  }

  /** Initialize the various system property dependent static flags. */
  public static void initProperties() {
    TRACE_LOCK = TRACE_ON() || TXStateProxy.VERBOSEVERBOSE_ON();
    TRACE_LOCK_COMPACT = TRACE_LOCK || TRACE_COMPACT_ON();
    LOCK_MAX_TIMEOUT = LOCK_MAX_TIMEOUT();
    WRITE_LOCK_TIMEOUT = WRITE_LOCK_TIMEOUT();
    READ_LOCK_TIMEOUT = READ_LOCK_TIMEOUT();
  }

  /** Returns true if verbose lock traces have to be produced. */
  private static boolean TRACE_ON() {
    return Boolean.getBoolean(TRACE_LOCK_PROPERTY);
  }

  /** Returns true if compact lock traces have to be produced. */
  private static boolean TRACE_COMPACT_ON() {
    return Boolean.getBoolean(TRACE_LOCK_COMPACT_PROPERTY);
  }

  private static int LOCK_MAX_TIMEOUT() {
    return Integer.getInteger(LOCK_MAX_TIMEOUT_PROP, 60000).intValue();
  }

  private static int WRITE_LOCK_TIMEOUT() {
    return Integer.getInteger(WRITE_LOCK_TIMEOUT_PROP, 0).intValue();
  }

  private static int READ_LOCK_TIMEOUT() {
    return Integer.getInteger(READ_LOCK_TIMEOUT_PROP,
        DEFAULT_READ_TIMEOUT).intValue();
  }

  /**
   * Returns the current value of synchronization state. This operation has
   * memory semantics of a <tt>volatile</tt> read.
   * 
   * @return current state value
   */
  @Override
  public final int getState() {
    return super.get();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasAnyLock() {
    return ((READ_MASK | READ_ONLY_MASK | WRITE_MASK | WRITE_EXCLUSIVE_BIT)
        & (super.get())) != 0;
  }

  /**
   * Atomically sets synchronization state to the given updated value if the
   * current state value equals the expected value. This operation has memory
   * semantics of a <tt>volatile</tt> read and write.
   * 
   * @param expect
   *          the expected value
   * @param update
   *          the new value
   * @return true if successful. False return indicates that the actual value
   *         was not equal to the expected value.
   */
  protected final boolean compareAndSetState(int expect, int update) {
    // See below for intrinsics setup to support this
    // return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    return super.compareAndSet(expect, update);
  }

  /**
   * Attempts to acquire in exclusive mode. This method should query if the
   * state of the object permits it to be acquired in the exclusive mode, and if
   * so to acquire it.
   * 
   * <p>
   * This method is always invoked by the thread performing acquire. If this
   * method reports failure, the acquire method may queue the thread, if it is
   * not already queued, until it is signalled by a release from some other
   * thread.
   * 
   * @param arg
   *          the acquire argument. This value is always the one passed to an
   *          acquire method, or is the value saved on entry to a condition
   *          wait. The value is otherwise uninterpreted and can represent
   *          anything you like.
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return a negative value on failure, with the actual value being negative
   *         of the current lock mode number (one of {@link #READ_SHARED_MODE},
   *         {@link #READ_ONLY_MODE}, {@link #WRITE_SHARED_MODE},
   *         {@link #WRITE_EXCLUSIVE_MODE}); zero if acquisition in exclusive
   *         mode succeeded but no subsequent exclusive-mode acquire can
   *         succeed; and a positive value if acquisition in exclusive mode
   *         succeeded and subsequent exclusive-mode acquires might also
   *         succeed, in which case a subsequent waiting thread must check
   *         availability. Upon success, this object has been acquired.
   * 
   * @throws IllegalMonitorStateException
   *           if acquiring would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  public final int tryAcquire(int arg, final Object ownerId,
      final Object context) {
    for (;;) {
      final int currentState = getState();
      if (currentState == 0) {
        if (compareAndSetState(0, WRITE_ONE | WRITE_EXCLUSIVE_BIT)) {
          setOwnerId(ownerId, context);
          return 1;
        }
      }
      final int currentWriteHolds = writeCount(currentState);
      if (currentWriteHolds > 0) {
        final boolean isEX = isExclusive(currentState);
        if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
          return isEX ? -WRITE_EXCLUSIVE_MODE : -WRITE_SHARED_MODE;
        }
        if (!isEX) {
          if (allowUpgradeOfWriteShare(currentState)) {
            // if there are any existing SH locks then wait for them to be
            // released; we also allow for READ_ONLY + EX_SH for RR locking
            // so check that case too
            if (isRead(currentState)) {
              if (isReadOnly(currentState)) {
                if ((currentState & WRITE_ALLOWS_READ_ONLY) != 0) {
                  return -READ_ONLY_MODE;
                }
                else {
                  Assert.fail("unexpected READ_ONLY lock held with EX_SH for "
                      + "object " + this + ", owner=" + ownerId);
                }
              }
              return -READ_SHARED_MODE;
            }
          }
          else {
            // Neeraj: This is the case of upgrade from exclusive-shared to
            // exclusive that is not allowed.
            return -WRITE_SHARED_MODE;
          }
        }
        if (currentWriteHolds == MAX_WRITE_COUNT) {
          throw new IllegalMonitorStateException(
              "Maximum write lock count exceeded " + MAX_WRITE_COUNT + " for "
                  + this);
        }
      }
      else if (isRead(currentState)) {
        return isReadOnly(currentState) ? -READ_ONLY_MODE : -READ_SHARED_MODE;
      }

      if (compareAndSetState(currentState, (currentState + WRITE_ONE)
          | WRITE_EXCLUSIVE_BIT)) {
        setOwnerId(ownerId, context);
        if (currentWriteHolds != MAX_WRITE_COUNT_1) {
          return 1;
        }
        return 0;
      }
    }
  }

  /**
   * Attempts to set the state to reflect a release in exclusive mode.
   * 
   * <p>
   * This method is always invoked by the thread performing release.
   * 
   * @param arg
   *          the release argument. This value is always the one passed to a
   *          release method, or the current state value upon entry to a
   *          condition wait. The value is otherwise uninterpreted and can
   *          represent anything you like.
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return {@code true} if this object is now in a fully released state, so
   *         that any waiting threads may attempt to acquire; and {@code false}
   *         otherwise.
   * 
   * @throws IllegalMonitorStateException
   *           if releasing would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  public final boolean tryRelease(final int arg, final Object ownerId,
      final Object context) {
    int currentState = getState(); // volatile read before owner check
    final Object currentOwnerId;
    if (ownerId != null && (currentOwnerId = getOwnerId(context)) != null) {
      if (!ownerId.equals(currentOwnerId)) {
        IllegalMonitorStateException imse = new IllegalMonitorStateException(
            "attempt to release exclusive lock by a non owner [" + ownerId
            + "], current owner [" + currentOwnerId + "] for " + this);
        if (writeCount(currentState) == 0) {
          // if system is going down then this can happen in some rare cases
          getDistributedSystem().getCancelCriterion().checkCancelInProgress(
              imse);
        }
        throw imse;
      }
    }

    final boolean releaseAll = (arg == RELEASE_ALL_MASK);
    for (;;) {
      final int currentWriteHolds = writeCount(currentState);
      if (currentWriteHolds == 0) {
        IllegalMonitorStateException imse = new IllegalMonitorStateException(
            "write count is zero in release for " + this);
        // if system is going down then this can happen in some rare cases
        getDistributedSystem().getCancelCriterion().checkCancelInProgress(imse);
        throw imse;
      }
      final int newState;
      // is this the last write lock?
      if (releaseAll || currentWriteHolds == 1) {
        newState = (currentState & WRITE_CLEAR_MASK);
        // we must clear the lock owner before giving up the lock else may
        // overwrite someone else's owner
        clearOwnerId(context);
      }
      else {
        newState = currentState - WRITE_ONE;
      }
      if (compareAndSetState(currentState, newState)) {
        return true;
      }
      currentState = getState();
    }
  }

  /**
   * Attempts to acquire in shared mode. This method should query if the state
   * of the object permits it to be acquired in the shared mode, and if so to
   * acquire it.
   * 
   * <p>
   * This method is always invoked by the thread performing acquire. If this
   * method reports failure, the acquire method may queue the thread, if it is
   * not already queued, until it is signalled by a release from some other
   * thread.
   * 
   * @param lockModeArg
   *          The acquire argument. This value is always the one passed to an
   *          acquire method, or is the value saved on entry to a condition
   *          wait. The value is otherwise uninterpreted and can represent
   *          anything you like.
   * @param ownerId
   *          The owner requesting the new lock. We allow an owner holding an
   *          exclusive lock to also acquire a read lock, for example.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return a negative value on failure, with the actual value being negative
   *         of the current lock mode number (one of {@link #READ_SHARED_MODE},
   *         {@link #READ_ONLY_MODE}, {@link #WRITE_SHARED_MODE},
   *         {@link #WRITE_EXCLUSIVE_MODE}); zero if acquisition in shared mode
   *         succeeded but no subsequent shared-mode acquire can succeed; and a
   *         positive value if acquisition in shared mode succeeded and
   *         subsequent shared-mode acquires might also succeed, in which case a
   *         subsequent waiting thread must check availability. (Support for
   *         three different return values enables this method to be used in
   *         contexts where acquires only sometimes act exclusively.) Upon
   *         success, this object has been acquired.
   * 
   * @throws IllegalMonitorStateException
   *           if acquiring would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  public final int tryAcquireShared(final int lockModeArg, final Object ownerId,
      final Object context) {
    final int lockMode = (lockModeArg & MODE_MASK);
    for (;;) {
      final int currentState = getState();
      final int currentReadHolds;
      final int currentReadOnlyHolds;
      final int currentWriteHolds;

      switch (lockMode) {
        case READ_SHARED_MODE:
          if (currentState == 0) {
            if (compareAndSetState(0, 1)) {
              return 1;
            }
          }
          // We allow for atomic downgrade of lock from exclusive to shared, so
          // an owner can hold both locks simultaneously
          if (isExclusive(currentState)) {
            if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
              return -WRITE_EXCLUSIVE_MODE;
            }
          }
          // if there are other readers, and another thread waiting on commit to
          // upgrade EX_SH to EX, then don't acquire the lock but wait, else
          // it can result in a livelock when application tries to repeatedly
          // perform an operation after conflict since EX_SH=>EX upgrade may
          // not be possible due to other threads holding SH (#49712, #46121)
          currentReadHolds = readSharedCount(currentState);
          if (isWrite(currentState) && currentReadHolds > 1 && hasWaiters()
              && !ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
            return -WRITE_SHARED_MODE;
          }
          if (currentReadHolds == MAX_READ_SHARED_COUNT) {
            throw new IllegalMonitorStateException(
                "Maximum read lock count exceeded " + MAX_READ_SHARED_COUNT
                    + " for " + this);
          }
          if (compareAndSetState(currentState, currentState + 1)) {
            // Neeraj: success. But we need to return 0 when no further read
            // will succeed else some +ve number
            if (currentReadHolds != MAX_READ_SHARED_COUNT_1) {
              return 1;
            }
            return 0;
          }
          continue;
        case READ_ONLY_MODE:
          // This is read-only lock that does not allow WRITE_SHARED_MODE.
          // However, locking policy can override this behaviour and it can
          // be allowed here in which case a conflict is thrown in pre-commit
          // time when EX_SH is upgraded to EX.
          if (currentState == 0) {
            if (compareAndSetState(0, READ_ONLY_ONE)) {
              return 1;
            }
          }
          // We allow for atomic downgrade of lock from write to shared, so an
          // owner can hold both locks simultaneously.
          if (isWrite(currentState)) {
            // Check if both EX_SH and READ_ONLY locks can be held
            // simultaneously. This will only be true if both the current lock
            // request allows for it and existing EX_SH lock allows for it.
            if ((lockModeArg & ALLOW_READ_ONLY_WITH_EX_SH) != 0
                && ((currentState & WRITE_ALLOWS_READ_ONLY) != 0)) {
              if (isExclusive(currentState)) {
                if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
                  return -WRITE_EXCLUSIVE_MODE;
                }
              }
            }
            else {
              if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
                return isExclusive(currentState) ? -WRITE_EXCLUSIVE_MODE
                    : -WRITE_SHARED_MODE;
              }
            }
          }
          currentReadOnlyHolds = readOnlyCount(currentState);
          if (currentReadOnlyHolds == MAX_READ_ONLY_COUNT) {
            throw new IllegalMonitorStateException("Maximum read-only "
                + "lock count exceeded " + MAX_READ_ONLY_COUNT + " for " + this);
          }
          if (compareAndSetState(currentState, currentState + READ_ONLY_ONE)) {
            // Neeraj: success. But we need to return 0 when no further read
            // will succeed else some +ve number
            if (currentReadOnlyHolds != MAX_READ_ONLY_COUNT_1) {
              return 1;
            }
            return 0;
          }
          continue;
        default:
          assert lockMode == WRITE_SHARED_MODE: lockMode;
          // Neeraj: This is write shared mode that allows for read mode
          // but not read-only mode.
          // Now allowing read-only when special flag is set.
          final boolean allowReadOnly =
              (lockModeArg & ALLOW_READ_ONLY_WITH_EX_SH) != 0;
          if (currentState == 0) {
            final int newState = allowReadOnly ? WRITE_ONE
                | WRITE_ALLOWS_READ_ONLY : WRITE_ONE;
            if (compareAndSetState(0, newState)) {
              setOwnerId(ownerId, context);
              return 1;
            }
          }
          final int newState;
          currentWriteHolds = writeCount(currentState);
          // We do not allow for atomic downgrade of lock from exclusive to
          // exclusive-shared since we do not account for them separately any
          // longer, so an owner cannot hold both locks simultaneously.
          if (currentWriteHolds > 0) {
            if (isExclusive(currentState)) {
              return -WRITE_EXCLUSIVE_MODE;
            }
            if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
              return -WRITE_SHARED_MODE;
            }
            if (currentWriteHolds == MAX_WRITE_COUNT) {
              throw new IllegalMonitorStateException(
                  "Maximum write lock count exceeded " + MAX_WRITE_COUNT
                      + " for " + this);
            }
            newState = (currentState + WRITE_ONE);
          }
          else {
            currentReadOnlyHolds = readOnlyCount(currentState);
            if (currentReadOnlyHolds > 0) {
              // allow for one READ_ONLY + EX_SH if asked for (when caller
              //   knows itself to be already holding the READ_ONLY lock)
              // also release the READ_ONLY lock for that case
              if (currentReadOnlyHolds == 1
                  && (lockModeArg & ALLOW_ONE_READ_ONLY_WITH_EX_SH) != 0) {
                newState = currentState - READ_ONLY_ONE + WRITE_ONE;
              }
              // allow for READ_ONLY + EX_SH if asked for (in REPEATABLE_READ)
              else if (allowReadOnly) {
                newState = (currentState + WRITE_ONE) | WRITE_ALLOWS_READ_ONLY;
              }
              else {
                return -READ_ONLY_MODE;
              }
            }
            // Let EX_SH conflict with SH in some cases (e.g. RC + RR mix where
            // RC is single-phase commit that cannot conflict in EX_SH->EX
            // (#49771)
            /*
            if ((lockModeArg & CONFLICT_WRITE_WITH_SH) != 0
                && isRead(currentState)) {
              // no conflict for SH->EX_SH upgrade for scan updates
              if ((lockModeArg & FOR_UPDATE) == 0
                  || readSharedCount(currentState) > 1) {
                return -READ_SHARED_MODE;
              }
            }
            */
            else {
              newState = (currentState + WRITE_ONE);
            }
          }
          if (compareAndSetState(currentState, newState)) {
            setOwnerId(ownerId, context);
            if (currentWriteHolds != MAX_WRITE_COUNT_1) {
              return 1;
            }
            return 0;
          }
          continue;
      }
    }
  }

  /**
   * Return the current {@link LockMode} corresponding to the lock state. This
   * checks for current lock owner etc. to return the lock mode for which the
   * timeout should be evaluated.
   * 
   * For example: current TX holds EX_SH, then some other node acquires SH which
   * is allowed, the current TX tries to upgrade to EX. In this case the
   * returned mode should be SH rather than EX_SH since latter will conflict
   * immediately.
   */
  protected final LockMode getCurrentLockModeForTimeout(final int lockModeArg,
      final Object ownerId, final Object context) {
    final int requestedLockMode = (lockModeArg & MODE_MASK);
    final int currentState = getState();
    final int currentReadOnlyHolds;
    final int currentWriteHolds;

    switch (requestedLockMode) {
      case READ_SHARED_MODE:
        if (isExclusive(currentState)) {
          // this case means that someone else holds the EX lock since there
          // can be no case where the same TX requests an SH lock after
          // upgrading to EX during commit
          return LockMode.EX;
        }
        return getLockModeFromState(currentState);
      case READ_ONLY_MODE:
        // We allow for atomic downgrade of lock from write to shared, so an
        // owner can hold both locks simultaneously.
        if (isWrite(currentState)) {
          // Check if both EX_SH and READ_ONLY locks can be held
          // simultaneously. This will only be true if both the current lock
          // request allows for it and existing EX_SH lock allows for it.
          if ((lockModeArg & ALLOW_READ_ONLY_WITH_EX_SH) != 0
              && ((currentState & WRITE_ALLOWS_READ_ONLY) != 0)) {
            if (isExclusive(currentState)) {
              // this case means that someone else holds the EX lock since there
              // can be no case where the same TX requests a READ_ONLY lock
              // after upgrading to EX during commit
              return LockMode.EX;
            }
          }
          else {
            if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
              return isExclusive(currentState) ? LockMode.EX : LockMode.EX_SH;
            }
          }
        }
        return getLockModeFromState(currentState);
      case WRITE_SHARED_MODE:
        // Neeraj: This is write shared mode that allows for read mode
        // but not read-only mode.
        // Now allowing read-only when special flag is set.
        final boolean allowReadOnly =
          (lockModeArg & ALLOW_READ_ONLY_WITH_EX_SH) != 0;
        currentWriteHolds = writeCount(currentState);
        // We do not allow for atomic downgrade of lock from exclusive to
        // exclusive-shared since we do not account for them separately any
        // longer, so an owner cannot hold both locks simultaneously.
        if (currentWriteHolds > 0) {
          if (isExclusive(currentState)) {
            return LockMode.EX;
          }
          if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
            return LockMode.EX_SH;
          }
        }
        else {
          currentReadOnlyHolds = readOnlyCount(currentState);
          if (currentReadOnlyHolds > 0) {
            // allow for READ_ONLY + EX_SH if asked for (in REPEATABLE_READ)
            if (!allowReadOnly) {
              return LockMode.READ_ONLY;
            }
            // allow for one READ_ONLY + EX_SH if asked for (when caller
            // knows itself to be already holding the READ_ONLY lock)
            else if (currentReadOnlyHolds > 1
                || (lockModeArg & ALLOW_ONE_READ_ONLY_WITH_EX_SH) == 0) {
              return LockMode.READ_ONLY;
            }
          }
        }
        return getLockModeFromState(currentState);
      default:
        assert requestedLockMode == WRITE_EXCLUSIVE_MODE: requestedLockMode;

        currentWriteHolds = writeCount(currentState);
        if (currentWriteHolds > 0) {
          final boolean isEX = isExclusive(currentState);
          if (!ArrayUtils.objectEquals(ownerId, getOwnerId(context))) {
            return isEX ? LockMode.EX : LockMode.EX_SH;
          }
          if (!isEX) {
            if (allowUpgradeOfWriteShare(currentState)) {
              // if there are any existing SH locks then wait for them to be
              // released; we also allow for READ_ONLY + EX_SH for RR locking
              // so check that case too
              if (isRead(currentState)) {
                if (isReadOnly(currentState)) {
                  if ((currentState & WRITE_ALLOWS_READ_ONLY) != 0) {
                    return LockMode.READ_ONLY;
                  }
                  else {
                    Assert.fail("unexpected READ_ONLY lock held with EX_SH "
                        + "for object " + this + ", owner=" + ownerId);
                  }
                }
                return LockMode.SH;
              }
            }
            else {
              // Neeraj: This is the case of upgrade from exclusive-shared to
              // exclusive that is not allowed.
              return LockMode.EX_SH;
            }
          }
        }
        else if (isRead(currentState)) {
          return isReadOnly(currentState) ? LockMode.READ_ONLY : LockMode.SH;
        }
        return getLockModeFromState(currentState);
    }
  }

  /**
   * Attempts to set the state to reflect a release in shared mode.
   * 
   * <p>
   * This method is always invoked by the thread performing release.
   * 
   * @param arg
   *          the release argument. This value is always the one passed to a
   *          release method, or the current state value upon entry to a
   *          condition wait. The value is otherwise uninterpreted and can
   *          represent anything you like.
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return {@code true} if this release of shared mode may permit a waiting
   *         acquire (shared or exclusive) to succeed; and {@code false}
   *         otherwise
   * 
   * @throws IllegalMonitorStateException
   *           if releasing would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  public final boolean tryReleaseShared(final int arg, Object ownerId,
      final Object context) {
    final int mode = (arg & MODE_MASK);
    final boolean releaseAll = (arg & RELEASE_ALL_MASK) == RELEASE_ALL_MASK;
    Object currentOwnerId = null;
    for (;;) {
      final int currentState = getState();
      final int newState;
      switch (mode) {
        case READ_SHARED_MODE:
          final int readCount = readSharedCount(currentState);
          if (readCount == 0) {
            IllegalMonitorStateException imse = new IllegalMonitorStateException(
                "read lock count is zero in release for " + this);
            // if system is going down then this can happen in some rare cases
            getDistributedSystem().getCancelCriterion().checkCancelInProgress(
                imse);
            throw imse;
          }
          newState = releaseAll ? (currentState & READ_SHARED_CLEAR_MASK)
              : (currentState - 1);
          if (compareAndSetState(currentState, newState)) {
            return true;
          }
          continue;
        case READ_ONLY_MODE:
          // This is read-only lock case.
          final int readOnlyCount = readOnlyCount(currentState);
          if (readOnlyCount == 0) {
            IllegalMonitorStateException imse = new IllegalMonitorStateException(
                "read-only lock count is zero in release for " + this);
            // if system is going down then this can happen in some rare cases
            getDistributedSystem().getCancelCriterion().checkCancelInProgress(
                imse);
            throw imse;
          }
          newState = releaseAll ? (currentState & READ_ONLY_CLEAR_MASK)
              : (currentState - READ_ONLY_ONE);
          if (compareAndSetState(currentState, newState)) {
            return true;
          }
          continue;
        default:
          assert mode == WRITE_SHARED_MODE: mode;
          // check owner
          if (ownerId != null
              && (currentOwnerId = getOwnerId(context)) != null) {
            if (!ownerId.equals(currentOwnerId)) {
              IllegalMonitorStateException imse = new IllegalMonitorStateException(
                  "attempt to release exclusive-shared lock by a non owner ["
                      + ownerId + "], current owner [" + currentOwnerId
                      + "] for " + this);
              if (writeCount(currentState) == 0) {
                // if system is going down then this can happen in some rare
                // cases
                getDistributedSystem().getCancelCriterion()
                    .checkCancelInProgress(imse);
              }
              throw imse;
            }
          }
          // clear write share bits
          final int currentWriteHolds = writeCount(currentState);
          if (currentWriteHolds == 0) {
            IllegalMonitorStateException imse = new IllegalMonitorStateException(
                "write count is zero in release for " + this);
            // if system is going down then this can happen in some rare cases
            getDistributedSystem().getCancelCriterion().checkCancelInProgress(
                imse);
            throw imse;
          }
          // is this the last write lock?
          if (releaseAll || currentWriteHolds == 1) {
            newState = (currentState & WRITE_CLEAR_MASK);
            // we must clear the lock owner before giving up the lock else may
            // overwrite someone else's owner
            clearOwnerId(context);
          }
          else {
            newState = currentState - WRITE_ONE;
          }
          if (compareAndSetState(currentState, newState)) {
            return true;
          }
          continue;
      }
    }
  }

  /** Return the number of active shared locks on this object. */
  public final int numSharedLocks() {
    return readSharedCount(getState());
  }

  /** Return the number of active read-only locks on this object. */
  public final int numReadOnlyLocks() {
    return readOnlyCount(getState());
  }

  /**
   * Returns {@code true} if synchronization is held exclusively with respect to
   * the given owner.
   * 
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return {@code true} if synchronization is held exclusively; {@code false}
   *         otherwise
   */
  public final boolean hasExclusiveLock(final Object ownerId,
      final Object context) {
    if (ownerId == null) {
      return isExclusive(super.get());
    }
    else {
      return (isExclusive(super.get()) && ArrayUtils.objectEquals(ownerId,
          getOwnerId(context)));
    }
  }

  /**
   * Returns {@code true} if synchronization is held in exclusive-shared mode
   * with respect to the given owner.
   * 
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object can be provided here (that will be passed to
   *          methods like {@link #getOwnerId(Object)}).
   * 
   * @return {@code true} if synchronization is held in exclusive-shared mode;
   *         {@code false} otherwise
   */
  public final boolean hasExclusiveSharedLock(final Object ownerId,
      final Object context) {
    final int currentState = super.get();
    if (ownerId == null) {
      return (writeCount(currentState) > 0 && !isExclusive(currentState));
    }
    else {
      return (writeCount(currentState) > 0 && !isExclusive(currentState)
          && ArrayUtils.objectEquals(ownerId, getOwnerId(context)));
    }
  }

  /** set the given bit mask in the lock state */
  protected final void setFlag(int currentState, final int flag) {
    InternalDistributedSystem sys = null;
    for (;;) {
      if (compareAndSetState(currentState, (currentState | flag))) {
        return;
      }

      // check if DS is disconnecting
      if (sys == null) {
        sys = getDistributedSystem();
      }
      sys.getCancelCriterion().checkCancelInProgress(null);

      currentState = getState();
    }
  }

  /** clear the given bit mask from the lock state */
  protected final void clearFlag(int currentState, final int flag) {
    InternalDistributedSystem sys = null;
    for (;;) {
      if (compareAndSetState(currentState, (currentState & ~flag))) {
        return;
      }

      // check if DS is disconnecting
      if (sys == null) {
        sys = getDistributedSystem();
      }
      sys.getCancelCriterion().checkCancelInProgress(null);

      currentState = getState();
    }
  }

  /**
   * Get the flag indicating whether there are threads waiting on this lock.
   */
  protected final boolean hasWaiters() {
    return (getState() & HAS_WAITERS) != 0;
  }

  /**
   * Set the flag indicating that there are threads waiting on this lock.
   */
  protected final void setHasWaiters() {
    setFlag(getState(), HAS_WAITERS);
  }

  /**
   * Clear the flag indicating that there are no more threads waiting on this
   * lock.
   */
  protected final void clearHasWaiters() {
    clearFlag(getState(), HAS_WAITERS);
  }

  /**
   * An attempt to acquire shared lock should succeed when there are no
   * exclusive or exclusive-shared locks already taken.
   * 
   * @param lockModeArg
   *          The mode for shared locks i.e. one of {@link #READ_SHARED_MODE},
   *          {@link #READ_ONLY_MODE}, {@link #WRITE_SHARED_MODE} combined with
   *          other flags like {@link #WRITE_ALLOWS_READ_ONLY}.
   * @param lockPolicy
   *          the {@link LockingPolicy} being used which is used to determine
   *          the wait timeout by invoking the {@link LockingPolicy#getTimeout}
   *          method. '-1' means wait indefinitely, '0' return immediately
   *          irrespective of success or failure. In case of failure, the
   *          timeout returned will be used as the max millis time to wait for
   *          the lock in given <code>sharedMode</code>.
   * @param msecs
   *          the timeout passed to the {@link LockingPolicy#getTimeout} method
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object to be passed through to
   *          {@link #getQueuedSynchronizer(Object)}.
   * 
   * @return - true if lock acquired successfully, false otherwise
   */
  protected final boolean attemptSharedLock(int lockModeArg,
      final LockingPolicy lockPolicy, long msecs, final Object ownerId,
      final Object context) {
    // first try to acquire the lock without any blocking
    final int currentMode;
    if ((currentMode = tryAcquireShared(lockModeArg, ownerId, context)) >= 0) {
      return true;
    }
    final LockMode specifiedMode = getLockMode(lockModeArg & MODE_MASK);
    msecs = getLockingPolicyWaitTime(lockPolicy, specifiedMode,
        getLockMode(-currentMode), lockModeArg, msecs);
    if (msecs == 0) {
      // return the result immediately for zero timeout
      return false;
    }

    return waitForLock(lockPolicy, ownerId, context, specifiedMode, false,
        lockModeArg, msecs);
  }

  /**
   * An attempt to writeExclusiveLock should succeed when there are no readLocks
   * already taken or a writeShareLock is already taken or a writeExclusiveLock
   * is already taken.
   * 
   * @param flags
   *          any additional flags being sent to alter the locking behaviour
   * @param lockPolicy
   *          the {@link LockingPolicy} being used which is used to determine
   *          the wait timeout by invoking the {@link LockingPolicy#getTimeout}
   *          method. '-1' means wait indefinitely, '0' return immediately
   *          irrespective of success or failure. In case of failure, the
   *          timeout returned will be used as the max millis time to wait for
   *          the lock in exclusive mode.
   * @param msecs
   *          the timeout passed to the {@link LockingPolicy#getTimeout} method
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object to be passed through to
   *          {@link #getQueuedSynchronizer(Object)}.
   * 
   * @return - true if lock acquired successfully, false otherwise
   */
  protected final boolean attemptExclusiveLock(final int flags,
      final LockingPolicy lockPolicy, long msecs, final Object ownerId,
      final Object context) {
    // first try to acquire the lock without any blocking
    final int currentMode;
    if ((currentMode = tryAcquire(0, ownerId, context)) >= 0) {
      return true;
    }
    msecs = getLockingPolicyWaitTime(lockPolicy, LockMode.EX,
        getLockMode(-currentMode), flags, msecs);
    if (msecs == 0) {
      // return the result immediately for zero timeout
      return false;
    }

    return waitForLock(lockPolicy, ownerId, context, LockMode.EX, true,
        WRITE_EXCLUSIVE_MODE | flags, msecs);
  }

  protected final long getLockingPolicyWaitTime(LockingPolicy lockPolicy,
      LockMode specifiedMode, LockMode currentMode, int lockModeArg,
      long specifiedTimeout) {
    specifiedTimeout = lockPolicy.getTimeout(this, specifiedMode, currentMode,
        lockModeArg, specifiedTimeout);
    if (specifiedTimeout == 0) {
      // return the result immediately for zero timeout
      return 0;
    }
    else if (specifiedTimeout > 0) {
      return specifiedTimeout;
    }
    else {
      return getMaxMillis();
    }
  }

  protected final boolean waitForLock(LockingPolicy lockPolicy, Object ownerId,
      Object context, LockMode specifiedMode, boolean exclusive,
      int lockModeArg, final long specifiedTimeout) {

    // we do the logging in units of "ack-wait-threshold" to allow writing log
    // messages in case lock acquire has not succeeded so far; also check for
    // CancelCriterion in each loop

    boolean result = false;
    long msecs = specifiedTimeout;
    final QueuedSynchronizer sync = getQueuedSynchronizer(context);
    final int waitThreshold = getWaitThreshold();
    long logInterval;
    InternalDistributedSystem sys = null;
    LogWriterI18n logger = null;
    // if ACK_WAIT is set then use it for warning at log interval = ACK_WAIT
    // regarding lock still not acquired; if not set then use default ACK_WAIT
    if (waitThreshold > 0) {
      logInterval = TimeUnit.SECONDS.toMillis(waitThreshold);
    }
    else {
      logInterval = TimeUnit.SECONDS
          .toMillis(DistributionConfig.DEFAULT_ACK_WAIT_THRESHOLD);
    }
    final long startTime = System.currentTimeMillis();
    long currentTime;
    // wait for these millis before recalculating total msecs to wait
    final long loopWaitNanos = TimeUnit.MILLISECONDS.toNanos(10);
    do {
      if (exclusive) {
        result = sync.tryAcquireNanos(0, ownerId, this, loopWaitNanos, context,
            null);
      }
      else {
        result = sync.tryAcquireSharedNanos(lockModeArg, ownerId, this,
            loopWaitNanos, context, null);
      }
      if (result) {
        break;
      }

      if (sys == null) {
        sys = getDistributedSystem();
        logger = sys.getLogWriterI18n();
      }
      sys.getCancelCriterion().checkCancelInProgress(null);
      // recalculate specifiedTimeout as per locking policy since current lock
      // mode may have changed by this point (#47225)
      LockMode currLockMode = getCurrentLockModeForTimeout(lockModeArg,
          ownerId, context);
      if (currLockMode != null) {
        msecs = getLockingPolicyWaitTime(lockPolicy, specifiedMode,
            currLockMode, lockModeArg, specifiedTimeout);
      }
      currentTime = System.currentTimeMillis();
      if ((currentTime - startTime) > logInterval) {
        if (logger.warningEnabled()) {
          logger.warning(LocalizedStrings.LocalLock_Waiting, new Object[] {
              "ExclusiveSharedSynchronizer", Double.toString(
                  logInterval / 1000.0), specifiedMode.toString(),
                  "this object with context=" + context + ", owner="
                      + LockingPolicy.getLockOwnerForConflicts(this,
                          specifiedMode, context, ownerId) + ", forOwner="
                      + ownerId, toString(), msecs });
        }
        // increase the timeout for logging by a factor for next iteration
        logInterval <<= 1;
      }
    } while ((currentTime - startTime) <= msecs);

    if (!result) {
      if (exclusive) {
        result = (tryAcquire(0, ownerId, context) >= 0);
      }
      else {
        result = (tryAcquireShared(lockModeArg, ownerId, context) >= 0);
      }
    }
    queuedSynchronizerCleanup(sync, context);
    return result;
  }

  /**
   * Releases in shared mode. Implemented by unblocking one or more threads if
   * {@link TryLockObject#tryReleaseShared} returns true.
   * 
   * @param sharedMode
   *          The mode for shared locks i.e. one of {@link #READ_SHARED_MODE},
   *          {@link #READ_ONLY_MODE}, {@link #WRITE_SHARED_MODE}. It can also
   *          be combined with other flags like {@link #RELEASE_ALL_MASK}.
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object to be passed through to
   *          {@link #getQueuedSynchronizer(Object)}.
   * 
   * @return the value returned from {@link #tryReleaseShared}
   * 
   * @throws IllegalMonitorStateException
   *           if releasing would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  protected final boolean releaseSharedLock(final int sharedMode,
      final Object ownerId, final Object context) {
    if (tryReleaseShared(sharedMode, ownerId, context)) {
      signalQueuedSynchronizer(context, true);
      return true;
    }
    return false;
  }

  /**
   * Releases in exclusive mode. Implemented by unblocking one or more threads.
   * 
   * @param flags
   *          Any flags for lock release such as {@link #RELEASE_ALL_MASK}.
   * @param ownerId
   *          The owner which will be used to check re-entrancy instead of
   *          current Thread.
   * @param context
   *          Any context object to be passed through to
   *          {@link #getQueuedSynchronizer(Object)}.
   * 
   * @return the value returned from {@link #tryRelease}
   * 
   * @throws IllegalMonitorStateException
   *           if releasing would place this synchronizer in an illegal state.
   *           This exception must be thrown in a consistent fashion for
   *           synchronization to work correctly.
   */
  protected final boolean releaseExclusiveLock(final int flags,
      final Object ownerId, final Object context) {
    if (tryRelease(flags, ownerId, context)) {
      signalQueuedSynchronizer(context, false);
      return true;
    }
    return false;
  }

  public static final long getMaxMillis() {
    return Long.MAX_VALUE / 1000000; // division by 1000000 for nanos
  }

  /**
   * Get the {@link LockMode} corresponding to the locking mode provided as an
   * integer value.
   */
  protected final LockMode getLockMode(final int mode) {
    switch (mode) {
      case READ_SHARED_MODE:
        return LockMode.SH;
      case READ_ONLY_MODE:
        return LockMode.READ_ONLY;
      case WRITE_SHARED_MODE:
        return LockMode.EX_SH;
      case WRITE_EXCLUSIVE_MODE:
        return LockMode.EX;
      default:
        throw new InternalGemFireError("unexpected lock mode: " + mode);
    }
  }

  /**
   * Get the "strongest" {@link LockMode} corresponding to the current
   * {@link ExclusiveSharedSynchronizer#getState()} of the object or null if no
   * lock is held.
   */
  public static final LockMode getLockModeFromState(final int state) {
    if (writeCount(state) > 0) {
      return isExclusive(state) ? LockMode.EX : LockMode.EX_SH;
    }
    if (isRead(state)) {
      return isReadOnly(state) ? LockMode.READ_ONLY : LockMode.SH;
    }
    return null;
  }

  /**
   * Get the owner of this lock, if any.
   * 
   * @param context
   *          context object passed in to the acquire or release lock methods
   */
  public abstract Object getOwnerId(Object context);

  /**
   * Set the owner of this lock to given object.
   * 
   * @param context
   *          context object passed in to the acquire lock methods
   */
  protected abstract void setOwnerId(Object ownerId, Object context);

  /**
   * Clear the owner of this lock.
   * 
   * @param context
   *          context object passed in to the release lock methods
   */
  protected abstract void clearOwnerId(Object context);

  /**
   * Return the {@link QueuedSynchronizer} to use as thread wait queue for the
   * lock when required.
   */
  protected abstract QueuedSynchronizer getQueuedSynchronizer(Object context);

  /**
   * Any cleanup required for {@link QueuedSynchronizer} of the lock.
   */
  protected abstract void queuedSynchronizerCleanup(QueuedSynchronizer sync,
      Object context);

  /**
   * Signal any waiting threads in the {@link QueuedSynchronizer}. By default
   * the {@link QueuedSynchronizer} is obtained by a call to
   * {@link #getQueuedSynchronizer}.
   */
  protected void signalQueuedSynchronizer(final Object context,
      final boolean shared) {
    final QueuedSynchronizer sync = getQueuedSynchronizer(context);
    if (shared) {
      sync.signalSharedWaiters();
    }
    else {
      sync.clearOwnerThread();
      sync.signalWaiters();
    }
  }

  /**
   * Returns true if atomic upgrade of lock from exclusive-shared to exclusive
   * is allowed.
   */
  protected boolean allowUpgradeOfWriteShare(int c) {
    return true;
  }

  /**
   * Returns true if atomic upgrade of lock from shared to exclusive-shared is
   * allowed.
   */
  protected boolean allowUpgradeOfReadShare(int c) {
    return true;
  }

  /**
   * Return the {@link InternalDistributedSystem} of this VM.
   */
  protected final InternalDistributedSystem getDistributedSystem() {
    return refreshCachedDS();
  }

  /**
   * Return the wait time after to try for the lock in a loop after which a
   * warning is logged.
   */
  public int getWaitThreshold() {
    refreshCachedDS();
    return WAIT_THRESHOLD;
  }

  protected final InternalDistributedSystem refreshCachedDS() {
    InternalDistributedSystem sys = DSYS;
    if (sys == null || !sys.isConnected()) {
      sys = InternalDistributedSystem.getConnectedInstance();
      // still not connected?
      if (sys == null) {
        throw InternalDistributedSystem.newDisconnectedException(null);
      }
      final int timeoutSecs = sys.getConfig().getAckWaitThreshold();
      if (timeoutSecs > 0) {
        WAIT_THRESHOLD = timeoutSecs;
      }
      else {
        WAIT_THRESHOLD = -1;
      }
      DSYS = sys;
    }
    return sys;
  }

  /**
   * Returns a string identifying this synchronizer's state. The state, in
   * brackets, includes the String {@code "State="} followed by the current
   * value of {@link #getState}.
   * 
   * @return a string identifying the state of this synchronizer
   */
  @Override
  public final String toString() {
    final StringBuilder sb = new StringBuilder(this.getClass().getSimpleName())
        .append('@').append(Integer.toHexString(System.identityHashCode(this)))
        .append('(');
    appendFieldsToString(sb);
    return sb.append(')').toString();
  }

  protected StringBuilder appendFieldsToString(final StringBuilder sb) {
    return sb.append("lockState=0x").append(Integer.toHexString(getState()));
  }
}
