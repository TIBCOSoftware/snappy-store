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

import java.util.Collection;

import com.gemstone.gemfire.internal.cache.locks.ReadWriteLockObject;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockService.ReadLockState;

/**
 * An extension of the {@link ReadWriteLockObject} class for GemFireXD DDL locks. It
 * adds methods to obtain status, dump current locks and allow release of write
 * lock without checking the owner thread by passing a null owner to lock
 * acquire/release methods.
 * 
 * @author swale
 * @since 6.5
 */
public interface GfxdReadWriteLock extends ReadWriteLockObject {

  /**
   * Get the name of object locked.
   */
  Object getLockName();

  /**
   * create a new {@link GfxdReadWriteLock} object with given name that can be
   * used in a new locking context
   */
  GfxdReadWriteLock newLock(Object name);

  /**
   * Return a {@link ReadLockState} denoting the state of this lock.
   */
  ReadLockState hasReadLock();

  /** return the number of active readers on the current lock */
  int numReaders();

  /** return true if the given owner holds a write lock */
  boolean hasWriteLock(Object owner);

  /** return the current write lock owner of this object, if any */
  Object getWriteLockOwner();

  /** return true if the "inMap" flag has been set for this lock */
  boolean inMap();

  /**
   * set the "inMap" flag for this lock; used by {@link GfxdDRWLockService} when
   * adding this lock into the global map to avoid map lookup everytime
   */
  void setInMap(boolean inMap);

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
  void dumpAllThreads(StringBuilder msg, Object lockObject, String logPrefix);

  /**
   * If lock itself cannot determine the reader threads then this method can be
   * used to dump global information of all reader threads (e.g. using
   * ContextManagers). This will be invoked once at the end of all lock specific
   * dumps by {@link #dumpAllThreads}.
   * 
   * @param msg
   *          {@link StringBuilder} to append the lock information
   * @param logPrefix
   *          any prefix to be prepended before the lock information
   */
  void dumpAllReaders(StringBuilder msg, String logPrefix);
  
  
  /**
   * Get a the list of threads waiting on this lock for debugging purposes
   */
  public Collection<Thread> getBlockedThreadsForDebugging();

  /**
   * Return true to get a trace of lock/unlock for this object in logs. This is
   * in addition to the {@link GfxdConstants#TRACE_LOCK} flag that will trace
   * all locks in any case and this method should return true when that is the
   * case.
   */
  boolean traceLock();

  /** Enable trace for this lock */
  void setTraceLock();

  /** Fill String representation of the lock in the given StringBuilder. */
  StringBuilder fillSB(StringBuilder sb);

  /**
   * A value indicating infinite timeout in {@link #attemptReadLock} and
   * {@link #attemptWriteLock} methods.
   */
  public static final int TIMEOUT_INFINITE = -1;
}
