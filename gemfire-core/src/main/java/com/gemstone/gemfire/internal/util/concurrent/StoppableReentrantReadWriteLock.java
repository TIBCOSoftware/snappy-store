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

package com.gemstone.gemfire.internal.util.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.internal.Assert;

/**
 * Read-write reentrant locks that respond to cancellation
 * @author jpenney
 *
 */
public class StoppableReentrantReadWriteLock implements /* ReadWriteLock, */ java.io.Serializable  {
  private static final long serialVersionUID = -1185707921434766946L;
  
  /**
   * The underlying read lock
   */
  transient private final StoppableReadLock readLock;
  
  /**
   * the underlying write lock
   */
  transient private final StoppableWriteLock writeLock;
  
  /**
   * This is how often waiters will wake up to check for cancellation
   */
  private static final long RETRY_TIME = 5 * 1000; // milliseconds

  /**
   * Create a new instance
   * @param countbased true if counter should be used for readers rather than locks.
   * This enables two different reader threads to lock and unlock
   * @param stopper the cancellation criterion
   */
  public StoppableReentrantReadWriteLock(boolean countbased, CancelCriterion stopper) {
    this(countbased, false, stopper);
  }

  /**
   * Create a new instance
   * 
   * @param countbased
   *          true if counter should be used for readers rather than locks. This
   *          enables two different reader threads to lock and unlock
   * @param fair
   *          true if this lock will provide FIFO locking, else false
   * @param stopper
   *          the cancellation criterion
   */
  public StoppableReentrantReadWriteLock(boolean countbased, boolean fair,
      CancelCriterion stopper) {
    Assert.assertTrue(stopper != null);
    ReadWriteLock lock;
    if (countbased) {
      lock = new SemaphoreReadWriteLock(fair);
    }
    else {
      lock = new ReentrantReadWriteLock(fair);
    }
    this.readLock = new StoppableReadLock(lock, stopper);
    this.writeLock = new StoppableWriteLock(lock, stopper);
  }

  /**
   * Create a new instance
   * @param stopper the cancellation criterion
   */
  public StoppableReentrantReadWriteLock(CancelCriterion stopper) {
    Assert.assertTrue(stopper != null);
    ReadWriteLock lock = new ReentrantReadWriteLock();
    this.readLock = new StoppableReadLock(lock, stopper);
    this.writeLock = new StoppableWriteLock(lock, stopper);
  }
  
  /**
   * @return the read lock
   */
  public StoppableReadLock readLock() {
    return readLock;
  }
  
  /**
   * @return the write lock
   */
  public StoppableWriteLock writeLock() {
    return writeLock;
  }
  
  /**
   * read locks that are stoppable
   * @author jpenney
   */
  static public class StoppableReadLock {

    private final Lock lock;
    private final CancelCriterion stopper;
    
    /**
     * Create a new read lock from the given lock
     * @param lock the lock to be used
     * @param stopper the cancellation criterion
     */
    StoppableReadLock(ReadWriteLock lock, CancelCriterion stopper) {
      this.lock = lock.readLock();
      this.stopper = stopper;
      }

    public void lock() {
      boolean interrupted = Thread.interrupted();
      try {
        for (;;) {
          try {
            lockInterruptibly();
            break;
          }
          catch (InterruptedException e) {
            stopper.checkCancelInProgress(e);
            interrupted = true;
          }
        } // for
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    /**
     * @throws InterruptedException
     */
    public void lockInterruptibly() throws InterruptedException {
      for (;;) {
        stopper.checkCancelInProgress(null);
        if (lock.tryLock(RETRY_TIME, TimeUnit.MILLISECONDS))
          break;
      }
    }

    /**
     * Unlike <code>lock()</code> delay cancellation check to be done only if
     * lock acquisition is not successful for a while.
     */
    public void lockDelayCancel() {
      boolean interrupted = Thread.interrupted();
      try {
        for (;;) {
          try {
            if (lock.tryLock(RETRY_TIME, TimeUnit.MILLISECONDS)) break;
            stopper.checkCancelInProgress(null);
          } catch (InterruptedException e) {
            stopper.checkCancelInProgress(e);
            interrupted = true;
          }
        } // for
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    /**
     * @return true if the lock was acquired
     */
    public boolean tryLock() {
      stopper.checkCancelInProgress(null);
      return lock.tryLock();
    }

    /**
     * @see Lock#tryLock(long, TimeUnit)
     * 
     * @param time
     *          the maximum time to wait for the lock
     * @param unit
     *          the time unit of the {@code time} argument
     * @return {@code true} if the lock was acquired and {@code false} if the
     *         waiting time elapsed before the lock was acquired
     * @throws InterruptedException
     */
    public boolean tryLock(long time, TimeUnit unit)
        throws InterruptedException {
      stopper.checkCancelInProgress(null);
      return lock.tryLock(time, unit);
    }

    public void unlock() {
      lock.unlock();
    }

//     /**
//      * @return the new condition
//      */
//     public StoppableCondition newCondition() {
//       return new StoppableCondition(lock.newCondition(), stopper);
//     }
  }
  
  static public class StoppableWriteLock {
    
    /**
     * The underlying write lock
     */
    private final Lock lock;
    
    /**
     * the cancellation criterion
     */
    private final CancelCriterion stopper;
    
    /**
     * Create a new instance
     * @param lock the underlying lock
     * @param stopper cancel criterion object used to check for cancellation
     */
    public StoppableWriteLock(ReadWriteLock lock, CancelCriterion stopper) {
      this.lock = lock.writeLock();
      this.stopper = stopper;
      }

    public void lock() {
      boolean interrupted = Thread.interrupted();
      try {
        for (;;) {
          try {
            lockInterruptibly();
            break;
          }
          catch (InterruptedException e) {
            stopper.checkCancelInProgress(e);
            interrupted = true;
          }
        } // for
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }

    /**
     * @throws InterruptedException
     */
    public void lockInterruptibly() throws InterruptedException {
      for (;;) {
        stopper.checkCancelInProgress(null);
        if (lock.tryLock(RETRY_TIME, TimeUnit.MILLISECONDS))
          break;
      }
    }

    /**
     * @return true if the lock was acquired
     */
    public boolean tryLock() {
      stopper.checkCancelInProgress(null);
      return lock.tryLock();
    }

    /**
     * @see Lock#tryLock(long, TimeUnit)
     * 
     * @param time
     *          the maximum time to wait for the lock
     * @param unit
     *          the time unit of the {@code time} argument
     * @return {@code true} if the lock was acquired and {@code false} if the
     *         waiting time elapsed before the lock was acquired
     * @throws InterruptedException
     */
    public boolean tryLock(long time, TimeUnit unit)
        throws InterruptedException {
      stopper.checkCancelInProgress(null);
      return lock.tryLock(time, unit);
    }

    /**
     */
    public void unlock() {
      lock.unlock();
    }

//     /**
//      * @return the new condition
//      */
//     public StoppableCondition newCondition() {
//       return new StoppableCondition(lock.newCondition(), stopper);
//     }
    
  }
}
