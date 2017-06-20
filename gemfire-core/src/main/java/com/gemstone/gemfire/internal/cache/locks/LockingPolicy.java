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

import java.sql.Connection;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.LockTimeoutException;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;

/**
 * This enumeration defines how an entry or object should be locked, when should
 * the lock be released, exceptions to be thrown when lock acquisition fails,
 * retries for locks etc. where each lock is obtained locally on each node.
 * 
 * <p>
 * 
 * Multiple modes of operation are provided:
 * 
 * a) fail-fast (default): The operation fails immediately with a
 * {@link ConflictException} if the lock could not be obtained on any of the
 * nodes (eager conflict detection for transactions).
 * 
 * NOTE: the schemes mentioned below are not yet implemented except for
 * "b) waiting" which is now provided as a system property that applies to each
 * transaction {@link ExclusiveSharedSynchronizer#WRITE_LOCK_TIMEOUT_PROP}.
 * 
 * b) waiting: Wait for the lock to be acquired on all the nodes subject to a
 * maximum timeout. There is no special distributed deadlock detection so those
 * cases will simply timeout after the given limit is reached.
 * 
 * c) retry (future work): This is with optional random sleep between the
 * retries and subject to a maximum timeout. Again there is no special
 * distributed livelock detection so those cases will timeout after the given
 * limit is reached. Using the random sleep should minimize such cases.
 * 
 * d) retry with increased priority (future work): This is a variation of the
 * retry scheme where each retry is with a higher priority in addition to the
 * random sleep. This requires preemptable locks that will cause the other
 * operation currently holding the lock to fail if its priority is lower than
 * that of this operation.
 * 
 * e) wait with elder election for deadlocks (future work): In case of deadlock
 * detection on a single object (i.e. lock successful on some nodes while
 * failure on others), a distributed barrier will be created using the locks
 * with one of the members driving the operations and applying the operations on
 * the good side of the barrier to completion.
 * 
 * @author swale
 * @since 7.0
 */
public enum LockingPolicy {

  /**
   * Defines a dummy no locking policy.
   */
  NONE {

    @Override
    public LockMode getReadLockMode() {
      return null;
    }

    @Override
    public LockMode getWriteLockMode() {
      return null;
    }

    @Override
    public LockMode getReadOnlyLockMode() {
      return null;
    }

    @Override
    public final void acquireLock(ExclusiveSharedLockObject lockObj,
        LockMode mode, int flags, Object lockOwner, Object context,
        AbstractOperationMessage msg) throws ConflictException,
        LockTimeoutException {
      // nothing to be done
    }

    @Override
    public final long getTimeout(Object lockObj, LockMode newMode,
        LockMode currentMode, int flags, final long msecs) {
      return msecs;
    }

    @Override
    public final IsolationLevel getIsolationLevel() {
      return IsolationLevel.NONE;
    }

    @Override
    public final boolean isFailFast() {
      return false;
    }

    @Override
    public final Object lockForRead(final ExclusiveSharedLockObject lockObj,
        LockMode mode, Object lockOwner, final Object context,
        final int iContext, AbstractOperationMessage msg,
        boolean allowTombstones, ReadEntryUnderLock reader) {
      // no locking to be done
      return reader.readEntry(lockObj, context, iContext, allowTombstones);
    }

    @Override
    public final boolean lockedForWrite(ExclusiveSharedLockObject lockObj,
        Object owner, Object context) {
      return false; // not used
    }
  },

  /**
   * The default fail-fast mode to be used by
   * {@link Connection#TRANSACTION_READ_COMMITTED} transactions that will fail
   * with a {@link ConflictException} if write lock cannot be obtained
   * immediately. However when a {@link LockMode#SH} lock is to be obtained with
   * an existing {@link LockMode#EX} lock, then it will be blocking. The
   * rationale being that the {@link LockMode#EX} mode will be used for
   * transactional operations only during commit when atomicity of the commit is
   * required, so there is no actual conflict. In addition the read locks are
   * always acquired for zero duration only ({@link #lockForRead} releases the
   * lock immediately) so that reads will wait for any pending commits to
   * complete but will themselves not block any commits since we just want to
   */
  FAIL_FAST_TX {

    @Override
    public LockMode getReadLockMode() {
      return LockMode.SH;
    }

    @Override
    public LockMode getWriteLockMode() {
      return LockMode.EX_SH;
    }

    @Override
    public final void acquireLock(final ExclusiveSharedLockObject lockObj,
        final LockMode mode, final int flags, final Object lockOwner,
        final Object context, final AbstractOperationMessage msg)
        throws ConflictException, LockTimeoutException {
      acquireLockFailFast(lockObj, mode, flags, lockOwner, context, msg);
    }

    @Override
    public final long getTimeout(final Object lockObj, final LockMode newMode,
        final LockMode currentMode, final int flags, final long msecs) {
      return getTimeoutFailFast(lockObj, newMode, currentMode, flags);
    }

    @Override
    public final IsolationLevel getIsolationLevel() {
      return IsolationLevel.READ_COMMITTED;
    }

    @Override
    public final boolean readOnlyCanStartTX() {
      // now RC will also start TX for READ_ONLY (#49371)
      return true;
    }

    @Override
    public final boolean isFailFast() {
      return true;
    }

    @Override
    public final Object lockForRead(final ExclusiveSharedLockObject lockObj,
        final LockMode mode, final Object lockOwner, final Object context,
        final int iContext, final AbstractOperationMessage msg,
        boolean allowTombstones, ReadEntryUnderLock reader) {
      // no locking is done now for SH locks since we cannot guarantee
      // distributed commit atomicity for READ_COMMITTED in any case without
      // resorting to 2-phase commit with EX waiting for existing SH
      // indefinitely
      if (mode == LockMode.SH) {
        return reader.readEntry(lockObj, context, iContext, allowTombstones);
      }
      assert mode == LockMode.READ_ONLY: mode;
      // need to hold READ_ONLY lock so that any conflicts can be correctly
      // detected with batching (which is the default behaviour, #49371)
      acquireLockFailFast(lockObj, mode, 0, lockOwner, context, msg);
      return Locked;
      /*
      // read lock is zero duration
      acquireLockFailFast(lockObj, mode, 0, lockOwner, context, msg);
      try {
        // unlock immediately since we do not intend to hold the lock,
        // but its possible that entry disappeared after lock acquisition
        // (TX commit was in progress), so check for that case
        return reader.readEntry(lockObj, context, iContext, allowTombstones);
      } finally {
        releaseLock(lockObj, mode, lockOwner, false, context);
      }
      */
    }
  },

  /**
   * The default fail-fast mode to be used by
   * {@link Connection#TRANSACTION_REPEATABLE_READ} transactions that will fail
   * with a {@link ConflictException} if write lock cannot be obtained
   * immediately. However when a read lock ({@link LockMode#SH}) is to be
   * obtained with an existing {@link LockMode#EX} lock, then it will be
   * blocking. The rationale being that the {@link LockMode#EX} mode will be
   * used for transactional operations only during commit so those will be
   * blocked until the read lock is released. To provide repeatable-read
   * semantics, this policy will hold the read locks till the end of transaction
   * so no changes to the locked rows can be committed (i.e. underlying rows in
   * the region cannot be changed).
   */
  FAIL_FAST_RR_TX {

    @Override
    public LockMode getReadLockMode() {
      return LockMode.SH;
    }

    @Override
    public LockMode getWriteLockMode() {
      return LockMode.EX_SH;
    }

    @Override
    public final void acquireLock(final ExclusiveSharedLockObject lockObj,
        final LockMode mode, final int flags, final Object lockOwner,
        final Object context, final AbstractOperationMessage msg)
        throws ConflictException, LockTimeoutException {
      // We allow for READ_ONLY locks to co-exist with EX_SH locks to minimize
      // conflicts. It also helps us to enforce the policy that reads never get
      // a conflict. So in this case the commit becomes two-phase and the lock
      // upgrade to EX mode is done in pre-commit phase that throws a conflict
      // exception.
      // Removed the ALLOW_READ_ONLY_WITH_EX_SH flag due to the following
      // possible scenario:
      //  1) child starts transactional insert but still not reached insert
      //  2) parent delete ReferencedKeyChecker searches and finds nothing,
      //     not even a transactional entry so does nothing
      //  3) parent delete acquires write lock on parent entry
      //  4) child insert acquires write lock on child entry and READ_ONLY
      //     on parent entry (which does not get a conflict due to this flag)
      //  5) child insert finishes and commits successfully releasing READ_ONLY
      //  6) parent delete then finishes successfully
      acquireLockFailFast(lockObj, mode, flags, lockOwner, context, msg);
    }

    @Override
    public final long getTimeout(final Object lockObj,
        final LockMode requestedMode, final LockMode currentMode,
        final int flags, final long msecs) {
      if (TXStateProxy.LOG_FINEST) {
        final LogWriter logger = GemFireCacheImpl.getExisting().getLogger();
        if (logger.infoEnabled()) {
          logger.info("LockingPolicy." + name() + ": found existing lockMode "
              + currentMode + " requested " + requestedMode + " on object: "
              + lockObj);
        }
      }
      // For RR the policy is that all locks wait for EX lock indefinitely since
      // it is assumed to be taken only for short duration, while other
      // read-write and write-write combinations will have short timeout i.e.
      // will throw a conflict immediately. The difference from other policies
      // is that EX lock will conflict with READ_ONLY/SH locks, so that
      // read-write conflicts are only thrown in pre-commit phase (combined with
      // allowReadOnlyWithEXSH to allow for EX_SH and READ_ONLY to co-exist).
      switch (currentMode) {
        case EX:
          // assuming EX_SH is always upgraded to EX so this can never happen
          assert requestedMode != LockMode.EX: "unexpected requestedMode EX "
              + "with currentMode EX in getTimeout for " + lockObj;
          // check for the special flag that will cause SH/EX_SH to conflict
          // with EX -- see comments in
          // ExclusiveSharedSynchronizer.CONFLICT_WITH_EX
          final long exReadTimeout = Math.max(
              ExclusiveSharedSynchronizer.DEFAULT_READ_TIMEOUT,
              ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT * 2);
          if ((flags & ExclusiveSharedSynchronizer.CONFLICT_WITH_EX) == 0) {
            // wait for EX to be released during commit when acquiring SH lock
            // but don't wait a whole lot for update
            return (requestedMode == LockMode.SH
                && ((flags & ExclusiveSharedSynchronizer.FOR_UPDATE) != 0)
                ? exReadTimeout : Math.min(exReadTimeout * 10,
                    ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT));
          }
          else {
            // wait for some time before throwing a conflict for EX
            return exReadTimeout;
          }
        case EX_SH:
          if (requestedMode == LockMode.SH) {
            // SH should always be allowed with EX_SH so keep retrying
            return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
          }
          else if (requestedMode == LockMode.READ_ONLY) {
            // wait a bit for READ_ONLY
            return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
          }
          else {
            // conflict immediately for EX_SH
            return ExclusiveSharedSynchronizer.WRITE_LOCK_TIMEOUT;
          }
        case SH:
          if (requestedMode == LockMode.EX) {
            // wait for some time before throwing a conflict for EX
            return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
          }
          else if (requestedMode == LockMode.EX_SH) {
            // wait for SH => EX_SH upgrade for sometime and then fail, else it
            // can get stuck indefinitely if two or more threads are trying to
            // do the same (#49341, #46121 etc)
            return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
          }
          else {
            // all other locks are allowed with SH so keep retrying
            return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
          }
        default:
          assert currentMode == LockMode.READ_ONLY: "unexpected currentMode="
              + "READ_ONLY in getTimeout for " + lockObj;
          switch (requestedMode) {
            case EX:
              // wait for some time before throwing a conflict for EX
              return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
            case EX_SH:
              // this should fail immediately assuming that they are from
              // different txns (TXState level will handle for same TX case)
              return ExclusiveSharedSynchronizer.WRITE_LOCK_TIMEOUT;
            default:
              // SH and READ_ONLY are allowed with READ_ONLY so keep retrying
              return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
          }
      }
    }

    @Override
    public final IsolationLevel getIsolationLevel() {
      return IsolationLevel.REPEATABLE_READ;
    }

    @Override
    public final boolean zeroDurationReadLocks() {
      return false;
    }

    @Override
    public final boolean readCanStartTX() {
      return true;
    }

    @Override
    public final boolean readOnlyCanStartTX() {
      return true;
    }

    @Override
    public final boolean isFailFast() {
      return true;
    }

    @Override
    public final Object lockForRead(final ExclusiveSharedLockObject lockObj,
        final LockMode mode, final Object lockOwner, final Object context,
        final int iContext, final AbstractOperationMessage msg,
        boolean allowTombstones, ReadEntryUnderLock reader) {
      // currently only CONFLICT_WITH_EX flag is honoured; if more flags are
      // added then ensure that none overlap with those in ReadEntryUnderLock
      acquireLockFailFast(lockObj, mode,
          (iContext & ExclusiveSharedSynchronizer.CONFLICT_WITH_EX), lockOwner,
          context, msg);
      return Locked;
    }

    @Override
    public final boolean requiresTwoPhaseCommit(final TXStateProxy proxy) {
      // if there has been a write operation, then there is potential for
      // conflict at lock upgrade time, so need to use 2-phase commit
      return proxy.isDirty();
    }
  },

  /**
   * Like {@link #FAIL_FAST_TX} mode, except that for non-transactional
   * operations this will wait for other non-transactional operations instead of
   * failing. It will still fail eagerly when conflict with a transactional
   * operation is detected.
   */
  FAIL_FAST_NO_TX {

    @Override
    public LockMode getReadLockMode() {
      return null;
    }

    @Override
    public LockMode getWriteLockMode() {
      return LockMode.EX;
    }

    @Override
    public final void acquireLock(final ExclusiveSharedLockObject lockObj,
        final LockMode mode, final int flags, final Object lockOwner,
        final Object context, final AbstractOperationMessage msg)
        throws ConflictException, LockTimeoutException {
      acquireLockFailFast(lockObj, mode, flags, lockOwner, context, msg);
    }

    @Override
    public final long getTimeout(final Object lockObj, final LockMode newMode,
        final LockMode currentMode, final int flags, final long msecs) {
      return getTimeoutFailFast(lockObj, newMode, currentMode, flags);
    }

    @Override
    public final IsolationLevel getIsolationLevel() {
      return IsolationLevel.NONE;
    }

    @Override
    public final boolean isFailFast() {
      return true;
    }

    @Override
    public final Object lockForRead(final ExclusiveSharedLockObject lockObj,
        LockMode mode, Object lockOwner, final Object context,
        final int iContext, AbstractOperationMessage msg,
        boolean allowTombstones, final ReadEntryUnderLock reader) {
      // no locking to be done
      return reader.readEntry(lockObj, context, iContext, allowTombstones);
    }

    @Override
    public final boolean lockedForWrite(ExclusiveSharedLockObject lockObj,
        Object owner, Object context) {
      return lockObj.hasExclusiveLock(owner, context);
    }
  },
  /**
   * Defines a snapshot locking policy. i.e. no lock.
   * we can add lock later for write to detect write write conflict.
   * read can start tx
   * This is default lock policy for Transaction isolation level NONE.
   */
  SNAPSHOT {

    @Override
    public LockMode getReadLockMode() {
      return null;
    }

    @Override
    public LockMode getWriteLockMode() {
      return null;
    }

    @Override
    public LockMode getReadOnlyLockMode() {
      return null;
    }

    @Override
    public boolean readCanStartTX() {
      return true;
    }

    @Override
    public boolean readOnlyCanStartTX() {
      return true;
    }

    @Override
    public final void acquireLock(ExclusiveSharedLockObject lockObj,
        LockMode mode, int flags, Object lockOwner, Object context,
        AbstractOperationMessage msg) throws ConflictException,
        LockTimeoutException {
      // TODO: Suranjan Ideally no request should come in this mode.
      // put an assert here!
      acquireLockFailFast(lockObj, mode, flags, lockOwner, context, msg);
    }

    @Override
    public final long getTimeout(Object lockObj, LockMode newMode,
        LockMode currentMode, int flags, final long msecs) {
      return msecs;
    }

    @Override
    public final IsolationLevel getIsolationLevel() {
      return IsolationLevel.SNAPSHOT;
    }

    @Override
    public final boolean isFailFast() {
      return true;
    }

    @Override
    public final Object lockForRead(final ExclusiveSharedLockObject lockObj,
        LockMode mode, Object lockOwner, final Object context,
        final int iContext, AbstractOperationMessage msg,
        boolean allowTombstones, ReadEntryUnderLock reader) {
      // TODO: Suranjan try to see if we can add versioning information here and read
      return reader.readEntry(lockObj, context, iContext, allowTombstones);
    }
  },
  ;
  ;

  /**
   * Interface to be implemented by callers of {@link LockingPolicy#lockForRead}
   * for reading the region entry under the read lock.
   */
  public interface ReadEntryUnderLock {

    // all possible flags for iContext are below

    public static final int DO_NOT_LOCK_ENTRY = 0x1;
    public static final int DESER_UPDATE_STATS = 0x2;
    public static final int DESER_DISABLE_COPY_ON_READ = 0x4;
    public static final int DESER_PREFER_CD = 0x8;

    /** always point below to the last in the list above */
    public static final int LAST_FLAG = DESER_PREFER_CD;

    /**
     * Read the entry while the read lock is held by
     * {@link LockingPolicy#lockForRead} and return the value.
     */
    Object readEntry(ExclusiveSharedLockObject lockObj, Object context,
        int iContext, boolean allowTombstones);
  }

  @SuppressWarnings("unused")
  private static final class NullReader implements ReadEntryUnderLock {

    static {
      // check that iContext flags should not overlap with CONFLICT_WITH_EX
      if (LAST_FLAG >= ExclusiveSharedSynchronizer.CONFLICT_WITH_EX) {
        Assert.fail("unexpected LAST_FLAG=" + LAST_FLAG
            + ", CONFLICT_WITH_EX="
            + ExclusiveSharedSynchronizer.CONFLICT_WITH_EX);
      }
    }

    /**
     * @see ReadEntryUnderLock#readEntry(ExclusiveSharedLockObject, Object, int,
     *      boolean)
     */
    public final Object readEntry(ExclusiveSharedLockObject lockObj,
        Object context, int iContext, boolean allowTombstones) {
      return null;
    }
  }

  public static final ReadEntryUnderLock NULL_READER = new NullReader();

  /**
   * Indicates that the lock on object has been granted and is being held as the
   * result of {@link #lockForRead}.
   */
  public static final Object Locked = new Object();

  /**
   * Get the default {@link LockMode} to be used for a read operation.
   * 
   * @return the default {@link LockMode} to be used for acquiring the lock
   */
  public abstract LockMode getReadLockMode();

  /**
   * Get the default {@link LockMode} to be used for a write operation.
   * 
   * @return the default {@link LockMode} to be used for acquiring the lock
   */
  public abstract LockMode getWriteLockMode();

  /**
   * Get the default {@link LockMode} to be used for a read operation that will
   * disallow concurrent writers in every e.g. for GFXD foreign key checks.
   * 
   * @return the default {@link LockMode} to be used for acquiring the lock
   */
  public LockMode getReadOnlyLockMode() {
    return LockMode.READ_ONLY;
  }

  /**
   * Acquire the lock in given mode for the given object. The method can throw
   * different exceptions on lock failure including {@link ConflictException} to
   * indicate conflict detection mode, {@link LockTimeoutException} to indicate
   * a timeout or deadlock.
   * 
   * @param lockObj
   *          the object to be locked
   * @param mode
   *          the <code>LockMode</code> to acquire the lock
   * @param flags
   *          any additional flags to pass during locking
   * @param lockOwner
   *          the owner of the lock; can be null
   * @param context
   *          any context required to be passed to the
   *          {@link ExclusiveSharedLockObject#attemptLock} method that can be
   *          used by the particular locking implementation
   * @param msg
   *          the {@link AbstractOperationMessage} invoking this method; can be
   *          null
   * 
   * @throws ConflictException
   *           implementations can choose to throw a {@link ConflictException}
   *           to indicate a locking policy that fails eagerly detecting
   *           conflicts using the locks
   * @throws LockTimeoutException
   *           if the lock acquisition has timed out
   */
  public abstract void acquireLock(ExclusiveSharedLockObject lockObj,
      LockMode mode, int flags, Object lockOwner, Object context,
      AbstractOperationMessage msg) throws ConflictException,
      LockTimeoutException;

  /**
   * Release the lock acquired in given mode by a previous call to
   * {@link #acquireLock} for the given object. Implementations of
   * {@link ExclusiveSharedLockObject} will typically throw an
   * {@link IllegalMonitorStateException} if no lock was acquired previously or
   * if the owner does not match.
   * 
   * @param lockObj
   *          the object that is locked
   * @param mode
   *          the <code>LockMode</code> to release the lock
   * @param lockOwner
   *          the owner of the lock; can be null
   * @param releaseAll
   *          release all the read/write locks on the object acquired by the
   *          <code>lockOwner</code>
   * @param context
   *          any context required to be passed to the
   *          {@link ExclusiveSharedLockObject#releaseLock} method that can be
   *          used by the particular locking implementation
   */
  public final void releaseLock(ExclusiveSharedLockObject lockObj,
      LockMode mode, Object lockOwner, boolean releaseAll, Object context)
      throws IllegalMonitorStateException {
    if (mode != null) {
      LogWriter logger = null;
      if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
        logger = GemFireCacheImpl.getExisting().getLogger();
        if (TXStateProxy.LOG_FINEST) {
          logger.info("LockingPolicy." + name() + ": releasing lock in mode "
              + mode + " on object: " + lockObj);
        }
      }
      lockObj.releaseLock(mode, releaseAll, lockOwner, context);
      if (logger != null) {
        logger.info("LockingPolicy." + name()
            + ": released lock in mode " + mode + " on object: "
            + (TXStateProxy.LOG_FINEST ? lockObj : ArrayUtils.objectRefString(
                lockObj) + "[lockState=0x" + Integer.toHexString(
                    lockObj.getState()) + ']'));
      }
    }
  }

  /**
   * Get the timeout in millis that should be used for waiting in case lock is
   * not immediately available.
   * 
   * @param lockObj
   *          the object to be locked
   * @param newMode
   *          the new mode for which the lock has been requested
   * @param currentMode
   *          the current mode in which the lock is currently held
   * @param flags
   *          any additional flags being sent to alter the locking behaviour
   * @param msecs
   *          the base msecs value provided as lock timeout
   * 
   * @return the lock timeout in millis; a negative value indicates infinite
   *         wait
   */
  public abstract long getTimeout(Object lockObj, LockMode newMode,
      LockMode currentMode, int flags, long msecs);

  /**
   * Returns the transaction's {@link IsolationLevel} corresponding to this
   * {@link LockingPolicy}.
   */
  public abstract IsolationLevel getIsolationLevel();

  /**
   * If the read locks have to be held only momentarily (or not at all) during
   * the read and not the entire duration of the transaction.
   */
  public boolean zeroDurationReadLocks() {
    return true;
  }

  /**
   * Returns true if the locking policy will require starting a TXState on
   * remote node for read operation requiring SH locks (typically when
   * {@link IsolationLevel} for this policy is REPEATABLE_READ or higher).
   * 
   * This would normally be !zeroDurationReadLocks() though that is strictly not
   * a requirement.
   */
  public boolean readCanStartTX() {
    return false;
  }

  /**
   * Returns true if the locking policy will require starting a TXState on
   * remote node for read operation requiring READ_ONLY locks i.e. FK checks,
   * (typically when {@link IsolationLevel} for this policy is REPEATABLE_READ
   * or higher).
   */
  public boolean readOnlyCanStartTX() {
    return false;
  }

  /**
   * Returns true if the policy requires failing immediately with conflict.
   */
  public abstract boolean isFailFast();

  /**
   * Lock the given object/entry for reading with this {@link LockingPolicy}.
   * 
   * @return {@link #Locked} if the read lock on object was successfully
   *         acquired and is being held, result of
   *         {@link ReadEntryUnderLock#readEntry} if the read lock on object was
   *         successfully acquired and was immediately released while the object
   *         value was read when the lock was held,
   * 
   * @throws ConflictException
   *           implementations can choose to throw a {@link ConflictException}
   *           to indicate a locking policy that fails eagerly detecting
   *           conflicts using the locks
   * @throws LockTimeoutException
   *           if the lock acquisition has timed out
   */
  public abstract Object lockForRead(ExclusiveSharedLockObject lockObj,
      LockMode mode, Object lockOwner, Object context, int iContext,
      AbstractOperationMessage msg, boolean allowTombstones,
      ReadEntryUnderLock reader);

  /**
   * Returns true if the given lock object currently has a write lock (as
   * returned by {@link #getWriteLockMode()}) held on it.
   */
  public boolean lockedForWrite(final ExclusiveSharedLockObject lockObj,
      final Object owner, final Object context) {
    return lockObj.hasExclusiveSharedLock(owner, context);
  }

  /**
   * Returns whether the commit processing requires two-phase commit or not.
   */
  public boolean requiresTwoPhaseCommit(TXStateProxy proxy) {
    // if we have events that need to be published, then need 2-phase commit
    return proxy.isDirty() && proxy.getToBePublishedEvents() != null;
  }

  /**
   * Common lock acquisition routine for the fail-fast modes.
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "IMSE_DONT_CATCH_IMSE",
      justification = "LockingPolicy changes IMSE to ConflictException")
  protected final void acquireLockFailFast(
      final ExclusiveSharedLockObject lockObj, final LockMode mode,
      final int flags, final Object lockOwner, final Object context,
      final AbstractOperationMessage msg) throws ConflictException,
      LockTimeoutException {
    if (mode != null) {
      LogWriter logger = null;
      try {
        if (ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT) {
          logger = GemFireCacheImpl.getExisting().getLogger();
          if (ExclusiveSharedSynchronizer.TRACE_LOCK) {
            logger.info("LockingPolicy." + name() + ": acquiring lock in mode "
                + mode + " with flags=0x" + Integer.toHexString(flags)
                + " on object: " + (TXStateProxy.LOG_FINEST ? lockObj
                    : ArrayUtils.objectRefString(lockObj) + "[lockState=0x"
                + Integer.toHexString(lockObj.getState()) + ']'));
          }
        }
        if (lockObj.attemptLock(mode, flags, this, 0, lockOwner, context)) {
          if (logger != null) {
            if (ExclusiveSharedSynchronizer.TRACE_LOCK) {
              logger.info("LockingPolicy." + name()
                  + ": acquired lock in mode " + mode
                  + " with flags=0x" + Integer.toHexString(flags)
                  + " on object: " + (TXStateProxy.LOG_FINEST ? lockObj
                      : ArrayUtils.objectRefString(lockObj) + "[lockState=0x"
                  + Integer.toHexString(lockObj.getState()) + ']'));
            }
            else {
              logger.info("LockingPolicy." + name()
                  + ": acquired lock in mode " + mode + " on object: "
                  + ArrayUtils.objectRefString(lockObj) + "[lockState=0x"
                  + Integer.toHexString(lockObj.getState()) + ']');
            }
          }
          return;
        }
        if (logger != null) {
          logger.info("LockingPolicy." + name()
              + ": throwing ConflictException for lock in mode " + mode
              + " with flags=0x" + Integer.toHexString(flags) + " on object: "
              + lockObj);
        }
        throw new ConflictException(
            LocalizedStrings.TX_CONFLICT_ON_OBJECT
                .toLocalizedString(getLockObjectString(msg, lockObj, mode,
                    context, lockOwner), mode));
      } catch (IllegalMonitorStateException imse) {
        if (logger != null) {
          logger.info("LockingPolicy." + name()
              + ": throwing ConflictException for IllegalMonitorStateException"
              + " for lock in mode " + mode + " with flags=0x"
              + Integer.toHexString(flags) + " on object: " + lockObj);
        }
        throw new ConflictException(
            LocalizedStrings.TX_CONFLICT_LOCK_ILLEGAL.toLocalizedString(
                getLockObjectString(msg, lockObj, mode, context, lockOwner),
                mode.toString(), imse.getLocalizedMessage()), imse);
      }
    }
  }

  /**
   * Common lock timeout routine for the fail-fast modes.
   */
  protected final long getTimeoutFailFast(final Object lockObj,
      final LockMode requestedMode, final LockMode currentMode,
      final int flags) {
    if (TXStateProxy.LOG_FINEST) {
      final LogWriter logger = GemFireCacheImpl.getExisting().getLogger();
      if (logger.infoEnabled()) {
        logger.info("LockingPolicy." + name() + ": found existing lockMode "
            + currentMode + " on object: " + lockObj);
      }
    }
    // Here the policy is that all locks wait for EX lock indefinitely since it
    // is assumed to be taken only for short duration, while other read-write
    // and write-write combinations will have timeout of zero i.e. will throw a
    // conflict immediately. Similarly EX lock will wait for SH lock
    // indefinitely since SH locks are assumed to be acquired for very short
    // durations only.
    switch (currentMode) {
      case EX:
        // assuming EX_SH is always upgraded to EX so this can never happen
        assert requestedMode != LockMode.EX: "unexpected requestedMode EX "
            + "with currentMode EX in getTimeout for " + lockObj;
        // wait before failing since EX is assumed to be released after sometime
        final long exReadTimeout = Math.max(
            ExclusiveSharedSynchronizer.DEFAULT_READ_TIMEOUT,
            ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT * 2);
        // wait for EX to be released during commit when acquiring SH lock
        // but don't wait a whole lot for update
        return (requestedMode == LockMode.SH
            && ((flags & ExclusiveSharedSynchronizer.FOR_UPDATE) != 0)
            ? exReadTimeout : Math.min(exReadTimeout * 10,
                ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT));
      case EX_SH:
        if (requestedMode == LockMode.SH) {
          // SH should always be allowed with EX_SH so keep retrying
          return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
        }
        else if (requestedMode == LockMode.READ_ONLY) {
          // wait a bit for READ_ONLY
          return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
        }
        else {
          // conflict immediately for EX_SH
          return ExclusiveSharedSynchronizer.WRITE_LOCK_TIMEOUT;
        }
      case SH:
        if (requestedMode == LockMode.EX) {
          // wait indefinitely for EX locks since commit processing is expected
          // to be short and bounded; similarly for SH locks for EX lock request
          // for SH lock requesting with existing EX lock now conflicting after
          // some period otherwise it can deadlock now with multiple SH locks
          // being acquired by bulk table scan (t1 => SH on k1, SH on k2;
          //   t2 => EX on k2, EX on k1)
          // [sumedh] cannot fail with conflict here since it is single-phase
          // commit for RC which cannot fail during commit
          //return currentMode == LockMode.EX && requestedMode == LockMode.SH
          //    ? ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT : -1;
          return -1;
        }
        else if (requestedMode == LockMode.EX_SH) {
          // wait for SH => EX_SH upgrade for sometime and then fail, else it
          // can get stuck indefinitely if two or more threads are trying to
          // do the same (#49341, #46121 etc)
          return ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT;
        }
        else {
          // all other locks are allowed with SH so keep retrying
          return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
        }
      default:
        assert currentMode == LockMode.READ_ONLY: "unexpected currentMode="
            + currentMode + " in getTimeout for " + lockObj;
        switch (requestedMode) {
          case EX:
            // [sumedh] cannot fail with conflict here since it is single-phase
            // commit for RC which cannot fail during commit
            return -1;
          case EX_SH:
            // this should fail immediately assuming that they are from
            // different txns (TXState level will handle for same TX case)
            return ExclusiveSharedSynchronizer.WRITE_LOCK_TIMEOUT;
          default:
            // SH and READ_ONLY are allowed with READ_ONLY so keep retrying
            return ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT;
        }
    }
  }

  /**
   * Get a string representation of the locking object with context for logging.
   */
  protected final String getLockObjectString(
      final AbstractOperationMessage msg,
      final ExclusiveSharedLockObject lockObj, final LockMode lockMode,
      final Object context, final Object forOwner) {
    if (msg == null) {
      return lockObj + "; owner: "
          + getLockOwnerForConflicts(lockObj, lockMode, context, forOwner)
          + "; forOwner: " + forOwner + "; context: " + context;
    }
    return msg.getConflictObjectString(lockObj, lockMode, context, forOwner)
        + "] while processing message [" + msg.toString();
  }

  /**
   * This will return the lock owner by doing a possibly expensive search among
   * all active transactions etc. Should only be used for logging or exception
   * strings and never in regular code.
   */
  public static final Object getLockOwnerForConflicts(
      final ExclusiveSharedLockObject lockObj, final LockMode lockMode,
      final Object context, final Object forOwner) {
    final Object owner = lockObj.getOwnerId(context);
    if (owner == null && lockObj instanceof RegionEntry) {
      return TXManagerImpl.searchLockOwner((RegionEntry)lockObj, lockMode,
          context, forOwner);
    }
    return owner;
  }

  /**
   * Cache the array of all enumeration values for this enum.
   */
  static final LockingPolicy[] values = values();

  /**
   * Mapping of {@link IsolationLevel} ordinal to corresponding fail-fast
   * transaction {@link LockingPolicy}.
   */
  private static final LockingPolicy[] failFastPolicies;

  /**
   * Mapping of {@link IsolationLevel} ordinal to corresponding waiting mode
   * transaction {@link LockingPolicy}.
   */
  private static final LockingPolicy[] waitingModePolicies;

  static {
    int maxFF = -1, maxWM = -1;
    for (final LockingPolicy policy : values) {
      final int isolationOrdinal = policy.getIsolationLevel().ordinal();
      if (policy.isFailFast()) {
        if (isolationOrdinal > maxFF) {
          maxFF = isolationOrdinal;
        }
      }
      else {
        if (isolationOrdinal > maxWM) {
          maxWM = isolationOrdinal;
        }
      }
    }
    failFastPolicies = new LockingPolicy[maxFF + 1];
    waitingModePolicies = new LockingPolicy[maxWM + 1];
    for (final LockingPolicy policy : values) {
      final int isolationOrdinal = policy.getIsolationLevel().ordinal();
      if (policy.isFailFast()) {
        failFastPolicies[isolationOrdinal] = policy;
      }
      else {
        waitingModePolicies[isolationOrdinal] = policy;
      }
    }
  }

  /**
   * Get the {@link LockingPolicy} to use given the {@link IsolationLevel} and a
   * boolean indicating whether waiting mode is to be used, or default fail-fast
   * mode has to be used.
   */
  public static final LockingPolicy fromIsolationLevel(
      final IsolationLevel isolationLevel, final boolean waitMode) {
    final int isolationOrdinal = isolationLevel.ordinal();
    final LockingPolicy policy;
    if (waitMode) {
      if (isolationOrdinal < waitingModePolicies.length
          && (policy = waitingModePolicies[isolationOrdinal]) != null) {
        return policy;
      }
      throw new UnsupportedOperationException("Unimplemented transaction "
          + "isolation level for waiting mode: " + isolationLevel);
    }
    else {
      if (isolationOrdinal < failFastPolicies.length
          && (policy = failFastPolicies[isolationOrdinal]) != null) {
        return policy;
      }
      throw new UnsupportedOperationException("Unimplemented transaction "
          + "isolation level for fail-fast conflict detection mode: "
          + isolationLevel);
    }
  }

  /**
   * Get a {@link LockingPolicy} for given ordinal value ranging from 0 to
   * (number of enum values - 1).
   */
  public static final LockingPolicy fromOrdinal(final int ordinal) {
    return values[ordinal];
  }

  /**
   * Get the number of enumeration values defined for this enum.
   */
  public static final int size() {
    return values.length;
  }
}
