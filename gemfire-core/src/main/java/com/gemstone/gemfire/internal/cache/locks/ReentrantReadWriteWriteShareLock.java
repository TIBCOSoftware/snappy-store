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

/**
 * This class can be used by GemFire transactions to lock entries.
 * 
 * Three types of lock can be obtained.
 * 
 * 1. exclusive (EX) 2. exclusive-shared (EX_SH) 3. shared (SH)
 * 
 * EX lock will disallow an attempt to acquire any kind of lock.
 * 
 * EX_SH lock will disallow any other attempt to EX, EX_SH but will allow SH.
 * 
 * SH will disallow any attempt to EX but will allow EX_SH and SH
 * 
 * The EX and SH modes have the normal read-write lock semantics but the EX_SH
 * mode will be a hint that the guarded resource can be read but attempt to
 * write it should be prevented.
 * 
 * In addition to this, this lock provides an option to wait for a lock or to
 * return immediately on failure.
 * 
 * If the passed owner to the methods is null, then current Thread is taken to
 * be the owner.
 * 
 * @see ExclusiveSharedLockObject
 * @author kneeraj, swale
 * @since 7.0
 */
public final class ReentrantReadWriteWriteShareLock extends
    ExclusiveSharedSynchronizer implements ExclusiveSharedLockObject {

  private static final long serialVersionUID = -4890060816963241998L;

  private final QueuedSynchronizer sync;

  private volatile Object ownerId;

  public ReentrantReadWriteWriteShareLock() {
    this.sync = new QueuedSynchronizer();
  }

  public final boolean attemptLock(final LockMode mode, final long msecs,
      final Object owner) {
    return attemptLock(mode, 0, LockingPolicy.NONE, msecs, owner, null);
  }

  /**
   * @see ExclusiveSharedLockObject#attemptLock
   */
  public final boolean attemptLock(final LockMode mode, final int flags,
      final LockingPolicy lockPolicy, final long msecs, Object owner,
      final Object context) {
    if (owner == null) {
      owner = Thread.currentThread();
    }

    if (mode == LockMode.EX) {
      return attemptExclusiveLock(flags, lockPolicy, msecs, owner, context);
    }
    return attemptSharedLock(mode.getLockModeArg() | flags, lockPolicy, msecs,
        owner, context);
  }

  public final void releaseLock(final LockMode mode, final boolean releaseAll,
      Object owner) {
    releaseLock(mode, releaseAll, owner, null);
  }

  /**
   * @see ExclusiveSharedLockObject#releaseLock
   */
  public final void releaseLock(LockMode mode, boolean releaseAll,
      Object owner, Object context) {
    if (owner == null) {
      owner = Thread.currentThread();
    }
    if (mode == LockMode.EX) {
      releaseExclusiveLock(releaseAll ? RELEASE_ALL_MASK : 0, owner, context);
    }
    else {
      int lockModeArg = mode.getLockModeArg();
      if (releaseAll) {
        lockModeArg |= RELEASE_ALL_MASK;
      }
      releaseSharedLock(lockModeArg, owner, context);
    }
  }

  /**
   * @see ExclusiveSharedSynchronizer#getOwnerId(Object)
   */
  @Override
  public final Object getOwnerId(Object context) {
    return this.ownerId;
  }

  /**
   * @see ExclusiveSharedSynchronizer#setOwnerId(Object, Object)
   */
  @Override
  protected final void setOwnerId(Object owner, Object context) {
    this.ownerId = owner;
    this.sync.setOwnerThread();
  }

  /**
   * @see ExclusiveSharedSynchronizer#clearOwnerId(Object)
   */
  @Override
  protected final void clearOwnerId(Object context) {
    this.ownerId = null;
    this.sync.clearOwnerThread();
  }

  /**
   * @see ExclusiveSharedSynchronizer#getQueuedSynchronizer(Object)
   */
  @Override
  protected final QueuedSynchronizer getQueuedSynchronizer(Object context) {
    return this.sync;
  }

  /**
   * @see ExclusiveSharedSynchronizer#queuedSynchronizerCleanup(
   *            QueuedSynchronizer, java.lang.Object)
   */
  @Override
  protected void queuedSynchronizerCleanup(QueuedSynchronizer sync,
      Object context) {
    // nothing to be done
  }
}
