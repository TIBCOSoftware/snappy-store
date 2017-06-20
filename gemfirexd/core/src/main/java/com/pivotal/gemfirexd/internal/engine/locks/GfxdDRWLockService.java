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

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.LeaseExpiredException;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.distributed.LockServiceDestroyedException;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.deadlock.DependencyMonitorManager;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.distributed.internal.membership.
    InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResponseCode;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdWaitingReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.
    GfxdDRWLockDumpMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService.
    DistributedLockOwner;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdRWLockDependencyMonitor;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * Implementation of distributed read-write lock service. The main feature of
 * this implementation is that the read locks are always local and involve no
 * messaging so are very efficient. The basic design is thus:
 * 
 * When a distributed write lock on an object is requested, then first a DLock
 * on the object is obtained using {@link DLockService}. After the DLock is
 * successfully obtained a distributed message is sent to all members of the DS
 * to acquire local write locks on the object in all the VMs. Failures in any of
 * the write lock acquisitions will lead to release of all write locks first
 * followed by release of the DLock. While releasing the distributed write lock,
 * the order is reverse in that the distributed message to release the write
 * locks on all VMs is sent first while the DLock is released after that. Read
 * locks are always local locks in the VM so that they will block any incoming
 * write lock requests (and conversely will block till the local write lock
 * acquired by the {@link GfxdDRWLockReleaseProcessor} is released).
 * 
 * This class uses a custom {@link GfxdReadWriteLock} implementation for actual
 * local read write lock that optionally allows for a non-owner thread to
 * release a write lock. This is required when releasing the distributed write
 * lock on remote VMs since there is no guarantee that the thread that acquired
 * the lock will be the one chosen to process the release request.
 * 
 * @author Sumedh Wale
 * @since 6.0
 */
public final class GfxdDRWLockService extends DLockService implements
    GfxdLockService, MembershipListener {

  /** contains the map of all GfxdDRWLockService's */
  private static final Map<String, GfxdDRWLockService> rwServices =
    new HashMap<String, GfxdDRWLockService>();

  /** local map of {@link GfxdReadWriteLock}s for this service */
  private final GfxdLocalLockService localLockMap;

  /**
   * Any {@link MembershipListener} to be invoked before write lock release is
   * done for a departed member.
   */
  private final MembershipListener beforeMemberDeparted;

  /** True if lock tracing is enabled. */
  private final boolean traceOn;
  
  static {
    //Allow discovery of GFXD read write lock dependencies.
    DependencyMonitorManager.addMonitor(GfxdRWLockDependencyMonitor.INSTANCE);
  }

  protected GfxdDRWLockService(String serviceName, DistributedSystem ds,
      boolean isDistributed, boolean destroyOnDisconnect,
      boolean automateFreeResources, long maxVMWriteLockWaitTime,
      GfxdReadWriteLock lockTemplate,
      final MembershipListener beforeMemberDeparted) {
    super(serviceName, ds, isDistributed, destroyOnDisconnect,
        automateFreeResources);
    this.localLockMap = new GfxdLocalLockService(serviceName, lockTemplate,
        maxVMWriteLockWaitTime);
    this.beforeMemberDeparted = beforeMemberDeparted;
    this.traceOn = getLogWriter().fineEnabled() || GemFireXDUtils.TraceLock
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_DDLOCK);
    // register self as membership listener to release orphan locks for departed
    // or crashed members
    getDistributionManager().addMembershipListener(this);
  }

  /**
   * Creates named <code>GfxdDRWLockService</code>.
   * 
   * @param serviceName
   *          name of the service
   * @param ds
   *          DistributedSystem
   * @param distributed
   *          true if lock service will be distributed; false will be local only
   * @param destroyOnDisconnect
   *          true if lock service should destroy itself using system disconnect
   *          listener
   * @param automateFreeResources
   *          true if freeResources should be automatically called during unlock
   * @param maxVMLockWaitTime
   *          the maximum write lock wait time for a VM after which locks will
   *          be released and reacquired to prevent deadlocks. A value of -1
   *          implies no maximum wait time and disables deadlock prevention
   *          mechanism.
   * @param lockTemplate
   *          the template object for creating the {@link GfxdReadWriteLock}
   *          objects used for obtaining read-write locks
   * @param beforeMemberDeparted
   *          any {@link MembershipListener} to be invoked before releasing the
   *          orphaned write locks of a departed/crashed member using this lock
   *          service's MembershipListener
   * 
   * @throws IllegalArgumentException
   *           if serviceName is invalid or this process has already created
   *           named service
   * @throws IllegalStateException
   *           if system is in process of disconnecting
   * 
   * @see #create(String, DistributedSystem)
   */
  public static GfxdDRWLockService create(String serviceName,
      DistributedSystem ds, boolean distributed, boolean destroyOnDisconnect,
      boolean automateFreeResources, long maxVMLockWaitTime,
      GfxdReadWriteLock lockTemplate, MembershipListener beforeMemberDeparted)
      throws IllegalArgumentException, IllegalStateException {
    synchronized (creationLock) {
      synchronized (services) { // disconnectListener syncs on this
        ds.getCancelCriterion().checkCancelInProgress(null);

        // make sure thread group is ready...
        readyThreadGroup();

        if (services.get(serviceName) != null) {
          throw new IllegalArgumentException(
              LocalizedStrings.DLockService_SERVICE_NAMED_0_ALREADY_CREATED
                  .toLocalizedString(serviceName));
        }
        // basicCreate will construct GfxdDRWLockService and it calls
        // getOrCreateStats...
        return basicCreate(serviceName, ds, distributed, destroyOnDisconnect,
            automateFreeResources, maxVMLockWaitTime, lockTemplate,
            beforeMemberDeparted);
      }
    }
  }

  /**
   * Factory method for creating a new instance of
   * <code>GfxdDRWLockService</code>. This ensures that adding the
   * {@link #disconnectListener} is done while synchronized on the fully
   * constructed instance.
   * <p>
   * Caller must be synchronized on {@link DLockService#services}.
   * 
   * @see com.gemstone.gemfire.distributed.DistributedLockService#create(String,
   *      DistributedSystem)
   */
  private static GfxdDRWLockService basicCreate(String serviceName,
      DistributedSystem ds, boolean isDistributed, boolean destroyOnDisconnect,
      boolean automateFreeResources, long maxVMLockWaitTime,
      GfxdReadWriteLock lockTemplate, MembershipListener beforeMemberDeparted)
      throws IllegalArgumentException {
    Assert.assertTrue(Thread.holdsLock(services));

    final LogWriterI18n log = ds.getLogWriter().convertToLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine("About to create DistributedLockService <" + serviceName + ">");
    }

    GfxdDRWLockService svc = new GfxdDRWLockService(serviceName, ds,
        isDistributed, destroyOnDisconnect, automateFreeResources,
        maxVMLockWaitTime, lockTemplate, beforeMemberDeparted);
    if (svc.init(log)) {
      // below now handled directly by GfxdDRWLockService by listening for
      // memberDeparted events
      //svc.setDLockLessorDepartureHandler(
      //    svc.new GfxdDRWLockMemberDepartureHandler());
      rwServices.put(svc.getName(), svc);
    }
    return svc;
  }

  /**
   * Get the maximum write lock wait time for a VM after which locks will be
   * released and reacquired to prevent deadlocks.
   * 
   * @return the maximum write lock wait time for a VM in milliseconds; a value
   *         of -1 indicates that this deadlock prevention mechanism is disabled
   */
  public long getMaxVMWriteLockWait() {
    return this.localLockMap.maxVMWriteLockWait;
  }

  /**
   * <p>
   * Attempts to acquire a read lock on object <code>name</code>. Returns
   * <code>true</code> as soon as the lock is acquired. If a write lock on the
   * same object is currently held by another thread in this or any other
   * process in the distributed system (by a call to
   * {@link #writeLock(Object, Object, long, long)}), this method keeps trying
   * to acquire the lock for up to <code>waitTimeMillis</code> before giving up
   * and returning <code>false</code>. If the lock is acquired, it is held until
   * <code>readUnlock(Object)</code> is invoked.
   * </p>
   * 
   * <p>
   * Obtaining a read lock is a local operation only and guaranteed to involve
   * no messaging. If a read lock is obtained on any of the processes in the
   * distributed system, then it will block any attempt to acquire a write lock
   * by any other process in the distributed. Thus it provides for
   * {@link java.util.concurrent.locks.ReadWriteLock} semantics in a distributed
   * manner. Note that the application must be careful of potential deadlock
   * situations. For example the following scenarios can end up in deadlocks:
   * <ul>
   * <li>If a thread holding read lock on an object tries to obtain a write lock
   * on the same object.</li>
   * 
   * <li>If two processes obtain read and write locks on different objects in
   * different orders.</li>
   * 
   * <li>If a process (or thread in a process) tries to obtain a read (or write)
   * lock while another process is holding read (or write) lock on the same
   * object, and the release of read lock by second process depends on the
   * release of lock by the first process.</li>
   * </ul>
   * It is possible for a thread to obtain read lock on an object while already
   * holding the write lock. Thus it is possible to atomically downgrade the
   * write lock to a read lock as follows:
   * 
   * <pre>
   * writeLock(obj, -1, -1, false);
   * ...
   * readLock(obj, -1);
   * writeUnlock(obj);
   * </pre>
   * 
   * </p>
   * 
   * <p>
   * Locks are reentrant. If a thread invokes this method n times on the same
   * instance, specifying the same <code>name</code>, without an intervening
   * release, the thread must invoke <code>readUnlock(Object)</code> the same
   * number of times before the lock is released.
   * </p>
   * 
   * @param name
   *          The name of the lock to acquire in this service. This object must
   *          conform to the general contract of <code>equals(Object)</code> and
   *          <code>hashCode()</code> as described in
   *          {@link java.lang.Object#hashCode()}.
   * @param owner
   *          the owner for this lock; can be null in which case ownership is
   *          ignored; the owner is only used when acquiring read lock while
   *          still holding the write lock (for atomic lock downgrade, for
   *          example); in most cases one can safely use
   *          {@link #newCurrentOwner()} if there are no special needs
   * @param waitTimeMillis
   *          The number of milliseconds to try to acquire the lock before
   *          giving up and returning false. A value of -1 causes this method to
   *          block until the lock is acquired. A value of 0 causes this method
   *          to return false without waiting for the lock if the lock is held
   *          by another member or thread.
   * 
   * @return true if the lock was acquired, false if the timeout
   *         <code>waitTimeMillis</code> passed without acquiring the lock.
   */
  public boolean readLock(Object name, Object owner, long waitTimeMillis) {
    return this.localLockMap.readLock(name, owner, waitTimeMillis);
  }

  /**
   * Attempts to acquire a read lock on given {@link GfxdLockable}. This method
   * has the same semantics as {@link #readLock(Object, Object, long)} with the
   * optimization that the {@link GfxdReadWriteLock} as obtained using
   * {@link GfxdLockable#getReadWriteLock()} is used if available instead of
   * looking up from the global map.
   * 
   * @param lockable
   *          The {@link GfxdLockable} to acquire in this service. This object
   *          must conform to the general contract of
   *          <code>equals(Object)</code> and <code>hashCode()</code> as
   *          described in {@link java.lang.Object#hashCode()}.
   * @param owner
   *          the owner for this lock; can be null in which case ownership is
   *          ignored; the owner is only used when acquiring read lock while
   *          still holding the write lock (for atomic lock downgrade, for
   *          example); in most cases one can safely use
   *          {@link #newCurrentOwner()} if there are no special needs; this
   *          should be an instanceof {@link DistributedLockOwner} for
   *          read-write locking to work correctly in the event of node failures
   *          so use {@link #newCurrentOwner()} unless you known what you are
   *          doing
   * @param waitTimeMillis
   *          The number of milliseconds to try to acquire the lock before
   *          giving up and returning false. A value of -1 causes this method to
   *          block until the lock is acquired. A value of 0 causes this method
   *          to return false without waiting for the lock if the lock is held
   *          by another member or thread.
   * 
   * @return true if the lock was acquired, false if the timeout
   *         <code>waitTimeMillis</code> passed without acquiring the lock.
   * 
   * @see #readLock(Object, Object, long)
   */
  public boolean readLock(GfxdLockable lockable, Object owner,
      long waitTimeMillis) {
    return this.localLockMap.readLock(lockable, owner, waitTimeMillis);
  }

  /**
   * Release the read lock previously granted for the given <code>name</code>.
   * 
   * @param name
   *          the object to unlock in this service
   * 
   * @throws LockNotHeldException
   *           if the current thread is not the owner of this lock
   */
  public void readUnlock(Object name) {
    this.localLockMap.readUnlock(name);
  }

  /**
   * Release the read lock previously granted for the given {@link GfxdLockable}
   * . This method has the same semantics as {@link #readUnlock(Object)} with
   * the optimization that the {@link GfxdReadWriteLock} as obtained using
   * {@link GfxdLockable#getReadWriteLock()} is used if available instead of
   * looking up from the global map.
   * 
   * @param lockable
   *          the {@link GfxdLockable} to unlock in this service
   * 
   * @throws LockNotHeldException
   *           if the current thread is not the owner of this lock
   * 
   * @see #readUnlock(Object)
   */
  public void readUnlock(GfxdLockable lockable) {
    this.localLockMap.readUnlock(lockable);
  }

  /**
   * Returns true if the current thread holds a read lock on the given object.
   * 
   * @param name
   *          object to be checked for read lock
   * 
   * @throws LockServiceDestroyedException
   *           if the service has been destroyed
   */
  public ReadLockState hasReadLock(Object name) {
    checkDestroyed();
    return this.localLockMap.hasReadLock(name);
  }

  /**
   * <p>
   * Attempts to acquire a write lock on object <code>name</code>. Returns
   * <code>true</code> as soon as the lock is acquired. If a read or write lock
   * on the same object is currently held by another thread in this or any other
   * process in the distributed system, or another thread in the system has
   * locked the entire service, this method keeps trying to acquire the lock for
   * up to <code>waitTimeMillis</code> before giving up and returning
   * <code>false</code>. If the lock is acquired, it is held until
   * <code>writeUnlock(Object, boolean)</code> is invoked, or until
   * <code>leaseTimeMillis</code> milliseconds have passed since the lock was
   * granted - whichever comes first.
   * </p>
   * 
   * <p>
   * Locks are reentrant. If a thread invokes this method n times on the same
   * instance, specifying the same <code>name</code>, without an intervening
   * release or lease expiration expiration on the lock, the thread must invoke
   * <code>writeUnlock(Object, boolean)</code> the same number of times before
   * the lock is released (unless the lease expires). When this method is
   * invoked for a lock that is already acquired, the lease time will be set to
   * the maximum of the remaining least time from the previous invocation, or
   * <code>leaseTimeMillis</code>
   * </p>
   * 
   * <p>
   * It is possible to obtain a normal DLock using
   * {@link #lock(Object, long, long)} without taking a distributed write lock.
   * However, for consistency, if a distributed write lock has been obtained on
   * the same object by a thread of any process of the distributed system at
   * some point, or a read lock has been obtained by a thread of this process
   * and the object has not been released (by a call to
   * {@link #freeResources(Object)}) then the {@link #lock(Object, long, long)}
   * invocation will result in a distributed write lock.
   * </p>
   * 
   * @param name
   *          the name of the lock to acquire in this service. This object must
   *          conform to the general contract of <code>equals(Object)</code> and
   *          <code>hashCode()</code> as described in
   *          {@link java.lang.Object#hashCode()}.
   * @param owner
   *          the owner for this lock; can be null in which case ownership is
   *          ignored and {@link #writeUnlock} should also be passed null; in
   *          most cases one can safely use {@link #newCurrentOwner()} if there
   *          are no special needs but this should always be an instanceof
   *          {@link DistributedLockOwner} (or a child class)
   * @param waitTimeMillis
   *          the number of milliseconds to try to acquire the lock before
   *          giving up and returning false. A value of -1 causes this method to
   *          block until the lock is acquired. A value of 0 causes this method
   *          to return false without waiting for the lock if the lock is held
   *          by another member or thread.
   * @param leaseTimeMillis
   *          the number of milliseconds to hold the lock after granting it,
   *          before automatically releasing it if it hasn't already been
   *          released by invoking {@link #writeUnlock(Object, Object)}. If
   *          <code>leaseTimeMillis</code> is -1, hold the lock until explicitly
   *          unlocked.
   * 
   * @return true if the lock was acquired, false if the timeout
   *         <code>waitTimeMillis</code> passed without acquiring the lock.
   * 
   * @throws IllegalArgumentException
   *           if the lock owner argument is not an instance of
   *           {@link DistributedLockOwner}
   * @throws LockServiceDestroyedException
   *           if this lock service has been destroyed
   */
  public boolean writeLock(Object name, Object owner, long waitTimeMillis,
      long leaseTimeMillis) {
    if (owner != null && !(owner instanceof DistributedLockOwner)) {
      throw new IllegalArgumentException(
          "unexpected distributed lock owner of class "
              + owner.getClass().getName() + ": " + owner.toString()
              + ", expected an instance of DistributedLockOwner");
    }
    final LogWriterI18n logger = getLogWriter();
    if (this.traceOn) {
      logger.info(LocalizedStrings.DEBUG, this.toString()
          + " writeLock for object [" + name + "] with owner=" + owner
          + ", timeout=" + waitTimeMillis + ", leaseTime=" + leaseTimeMillis);
    }
    // check for node going down first
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    GfxdResponseCode responseCode = GfxdResponseCode.GRANT(1);
    Throwable failureEx = null;
    // pre-create the lock if required; this is used to check if user asked for
    // a write lock or a normal dlock when creating request/release processors
    this.localLockMap.getOrCreateLock(name);
    // first take the DLock
    if (super.lock(name, waitTimeMillis, leaseTimeMillis)) {
      // next the distributed write lock
      try {
        GfxdWaitingReplyProcessor response = GfxdDRWLockRequestProcessor
            .requestDRWLock(this, name, owner, getDistributionManager(),
                waitTimeMillis, true, logger);
        if (response != null) {
          responseCode = response.getResponseCode();
          if (responseCode.isException()) {
            failureEx = response.getReplyException();
          }
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        responseCode = GfxdResponseCode.EXCEPTION;
        failureEx = ie;
      } finally {
        if (failureEx != null) {
          // check for VM going down
          Misc.checkIfCacheClosing(failureEx);
        }
      }
    }
    else {
      responseCode = GfxdResponseCode.TIMEOUT;
    }
    if (this.traceOn) {
      logger.info(LocalizedStrings.DEBUG, this.toString()
          + " writeLock for object [" + name + "] with owner=" + owner
          + ", result was " + responseCode, failureEx);
    }
    if (failureEx != null) {
      throw new InternalGemFireException(
          "unexpected failure in write lock acquisition", failureEx);
    }
    return responseCode.isGrant();
  }

  /**
   * Attempts to acquire a write lock on given {@link GfxdLockable} object. This
   * method has the same semantics as {@link #writeLock(Object, Object, long, long)}
   * with the optimization that when possible the {@link GfxdReadWriteLock} as
   * obtained using {@link GfxdLockable#getReadWriteLock()} is used if available
   * instead of looking up from the global map.
   * 
   * @param lockable
   *          The {@link GfxdLockable} to acquire in this service. This object
   *          must conform to the general contract of
   *          <code>equals(Object)</code> and <code>hashCode()</code> as
   *          described in {@link java.lang.Object#hashCode()}.
   * @param owner
   *          the owner for this lock; can be null in which case ownership is
   *          ignored and {@link #writeUnlock} should also be passed null; in
   *          most cases one can safely use {@link #newCurrentOwner()} if there
   *          are no special needs but this should always be an instanceof
   *          {@link DistributedLockOwner} (or a child class)
   * @param waitTimeMillis
   *          The number of milliseconds to try to acquire the lock before
   *          giving up and returning false. A value of -1 causes this method to
   *          block until the lock is acquired. A value of 0 causes this method
   *          to return false without waiting for the lock if the lock is held
   *          by another member or thread.
   * @param leaseTimeMillis
   *          the number of milliseconds to hold the lock after granting it,
   *          before automatically releasing it if it hasn't already been
   *          released by invoking {@link #writeUnlock(Object, Object)}. If
   *          <code>leaseTimeMillis</code> is -1, hold the lock until explicitly
   *          unlocked.
   * 
   * @return true if the lock was acquired, false if the timeout
   *         <code>waitTimeMillis</code> passed without acquiring the lock.
   * 
   * @throws IllegalArgumentException
   *           if the lock owner argument is not an instance of
   *           {@link DistributedLockOwner}
   * @throws LockServiceDestroyedException
   *           if this lock service has been destroyed
   */
  public boolean writeLock(GfxdLockable lockable, Object owner,
      long waitTimeMillis, long leaseTimeMillis) {
    return writeLock(lockable.getName(), owner, waitTimeMillis, leaseTimeMillis);
  }

  /**
   * Release the write lock previously granted for the given <code>name</code>.
   * This should have been granted by a previous call to {@link #writeLock} on
   * this node with same owner.
   * 
   * @param name
   *          the object to unlock in this service.
   * @param owner
   *          the owner for this lock provided in {@link #writeLock}
   * 
   * @throws LockNotHeldException
   *           if the current thread is not the owner of this lock
   * @throws LeaseExpiredException
   *           if the current thread was the owner of this lock, but it's lease
   *           has expired.
   * @throws LockServiceDestroyedException
   *           if the service has been destroyed
   */
  public void writeUnlock(Object name, Object owner) {
    final LogWriterI18n logger = getLogWriter();
    if (this.traceOn) {
      if (owner != null && !(owner instanceof DistributedLockOwner)) {
        Assert.fail("unexpected distributed lock owner of class "
            + owner.getClass().getName() + ": " + owner.toString());
      }
      logger.info(LocalizedStrings.DEBUG, this.toString()
          + " writeUnlock for object [" + name + "] for owner=" + owner);
    }
    // ignore if node is going down else it may release the lock too early (e.g.
    // for DDLs that will be aborted on remote nodes on member departure)
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    Exception unlockEx = null;
    try {
      // first release the distributed write lock and only if that is successful
      // go for dlock release
      GfxdDRWLockReleaseProcessor.releaseDRWLock(getDistributionManager(),
          this.serviceName, name, owner, logger);
      super.unlock(name);
    } catch (LockNotHeldException ex) {
      unlockEx = ex;
      throw ex;
    } catch (LeaseExpiredException ex) {
      unlockEx = ex;
      throw ex;
    } catch (RuntimeException ex) {
      unlockEx = ex;
      throw ex;
    } finally {
      if (this.traceOn) {
        logger.info(LocalizedStrings.DEBUG, this.toString()
            + " writeUnlock for object [" + name + "] for owner=" + owner
            + (unlockEx != null ? ", threw exception" : ", was successful"),
            unlockEx);
      }
    }
  }

  /**
   * Release the write lock previously granted for the
   * {@link GfxdLockable#getName()} of given <code>GfxdLockable</code>. This
   * should have been granted by a previous call to {@link #writeLock} on this
   * node.
   * 
   * @param lockable
   *          the {@link GfxdLockable} to unlock in this service
   * @param owner
   *          the owner for this lock provided in {@link #writeLock}
   * 
   * @throws LockNotHeldException
   *           if the current thread is not the owner of this lock
   * @throws LeaseExpiredException
   *           if the current thread was the owner of this lock, but it's lease
   *           has expired.
   * @throws LockServiceDestroyedException
   *           if the service has been destroyed
   */
  public final void writeUnlock(GfxdLockable lockable, Object owner) {
    writeUnlock(lockable.getName(), owner);
  }

  /**
   * Returns the current {@link DistributedMember} combined with a unique ID for
   * current thread for use as the lock owner in {@link #readLock}/
   * {@link #writeLock} methods.
   */
  public final DistributedLockOwner newCurrentOwner() {
    return new DistributedLockOwner(getDistributionManager()
        .getDistributionManagerId());
  }

  /**
   * Return a local {@link GfxdLockService} that can be used to obtain local
   * read/write locks rather than distributed ones. Note that the locks will
   * share the same locking space so if the same locking objects are shared
   * between the local and distributed locks (e.g. a local read or write lock
   * will prevent a distributed write lock from being taken on the same object).
   */
  public final GfxdLocalLockService getLocalLockService() {
    return this.localLockMap;
  }

  /**
   * <p>
   * Returns true if the current thread holds a local write lock on the given
   * object. So this method will return true if a thread of any process in the
   * distributed system holds a distributed write lock, or this thread holds a
   * local write lock.
   * </p>
   * 
   * @param name
   *          object to be checked for write lock
   * @param owner
   *          the owner for this lock provided when acquiring write lock
   * 
   * @throws LockServiceDestroyedException
   *           if the service has been destroyed
   */
  public boolean hasWriteLock(Object name, Object owner) {
    checkDestroyed();
    return this.localLockMap.hasWriteLock(name, owner);
  }

  /**
   * <p>
   * Returns the current local write lock owner for given object, or null if no
   * write is currently held.
   * </p>
   * 
   * @param name
   *          object to be checked for write lock
   * 
   * @throws LockServiceDestroyedException
   *           if the service has been destroyed
   */
  public Object getWriteLockOwner(Object name) {
    checkDestroyed();
    return this.localLockMap.getWriteLockOwner(name);
  }

  /**
   * Generate TimeoutException for given lock object name or
   * {@link GfxdLockable}.
   */
  public final TimeoutException getLockTimeoutRuntimeException(
      final Object lockObject, final Object owner, final boolean dumpAllLocks) {
    if (dumpAllLocks) {
      dumpAllRWLocks("LOCK TABLE at the time of failure", true, false, true);
    }
    return this.localLockMap.getLockTimeoutRuntimeException(lockObject, owner,
        false);
  }

  /**
   * Generate a {@link StandardException} for lock timeout for given lock object
   * name or {@link GfxdLockable}.
   */
  public final StandardException getLockTimeoutException(
      final Object lockObject, final Object owner, final boolean dumpAllLocks) {
    if (dumpAllLocks) {
      dumpAllRWLocks("LOCK TABLE at the time of failure", true, false, true);
    }
    return this.localLockMap.getLockTimeoutException(lockObject, owner,
        false);
  }

  /**
   * Dump all read-write locks acquired on this or all VMs at an info level for
   * diagnostic purpose. Also dumps the stack traces of all threads in the sane
   * build.
   * 
   * @param logPrefix
   *          any prefix to be prepended before the lock information
   * @param allVMs
   *          if true then read-write locks on all VMs are dumped to the
   *          log-file else only on this VM
   * @param stdout
   *          if true then dump is generated on standard output else it goes to
   *          standard GFXD log file
   * @param force
   *          if true then dump is forced immediately, else avoid multiple
   *          dumps in quick succession (e.g. when many thread timeout on lock)
   */
  public void dumpAllRWLocks(String logPrefix, boolean allVMs, boolean stdout,
      boolean force) {
    this.localLockMap.dumpAllRWLocks(logPrefix, stdout ? new PrintWriter(
        System.out) : null, force);
    if (allVMs) {
      GfxdDRWLockDumpMessage.send(this.ds, this.serviceName, logPrefix, stdout,
          getLogWriter());
    }
  }

  /**
   * Dump information about all DLock services.
   */
  static void dumpAllDLockServices(StringBuilder msg) {
    synchronized (services) {
      for (Map.Entry<String, DLockService> entry : services.entrySet()) {
        msg.append(entry.getKey()).append(": ");
        DLockService svc = entry.getValue();
        svc.dumpService(msg, true);
      }
    }
  }

  @Override
  protected final void postDestroyAction() {
    synchronized (services) {
      GfxdDRWLockService currentService = rwServices.remove(this.serviceName);
      assert currentService == this: "Unexpected service " + currentService
          + " in GFXD ReadWriteLock services map";
    }
  }

  /**
   * Remove the token from the DLock map as well as from the local
   * GfxdReadWriteLock map.
   */
  @Override
  protected Object removeTokenFromMap(Object name) {
    this.localLockMap.freeResources(name);
    return super.removeTokenFromMap(name);
  }

  @Override
  public void checkDestroyed() {
    if (isDestroyed()) {
      LockServiceDestroyedException ex = generateLockServiceDestroyedException(
          generateLockServiceDestroyedMessage());
      // first check for DM going down
      // check for VM going down
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ex);
      throw ex;
    }
  }

  /*
  @Override
  protected DLockRequestProcessor createRequestProcessor(
      LockGrantorId grantorId, Object name, int threadId, long startTime,
      long requestLeaseTime, long requestWaitTime, boolean reentrant,
      boolean tryLock) {
    if (this.localLockMap.containsKey(name)) {
      return new GfxdDRWLockRequestProcessor(grantorId, this, name, threadId,
          startTime, requestLeaseTime, requestWaitTime, reentrant, tryLock,
          getDistributionManager());
    }
    else {
      return super.createRequestProcessor(grantorId, name, threadId, startTime,
          requestLeaseTime, requestWaitTime, reentrant, tryLock);
    }
  }
  */

  public void memberDeparted(final InternalDistributedMember member,
      boolean crashed) {
    // first invoke any "before" listener if provided
    if (this.beforeMemberDeparted != null) {
      this.beforeMemberDeparted.memberDeparted(member, crashed);
    }
    // release the existing distributed write locks held by the departed member
    final LogWriterI18n logger = getLogWriter();
    final Iterator<Map.Entry<Object, GfxdReadWriteLock>> iter = localLockMap
        .entrySet().iterator();
    while (iter.hasNext()) {
      final Map.Entry<Object, GfxdReadWriteLock> entry = iter.next();
      final Object name = entry.getKey();
      final Object lockOwner = entry.getValue().getWriteLockOwner();
      // check for locks having the departed member as owner
      if (lockOwner instanceof DistributedLockOwner && member.equals(
          ((DistributedLockOwner)lockOwner).getOwnerMember())) {
        if (this.traceOn) {
          logger.info(LocalizedStrings.DEBUG, this.toString()
              + " releasing writeLock for object [" + name + "] with owner="
              + lockOwner + ", due to memberDeparted for " + member);
        }
        try {
          this.localLockMap.writeUnlock(entry.getKey(), lockOwner);
        } catch (LockNotHeldException e) {
          // we may have a race condition where lock gets released by regular
          // channel too so ignore this exception
          if (this.traceOn) {
            logger.info(LocalizedStrings.DEBUG, this.toString()
                + " ignoring LockNotHeldException for object [" + name
                + "] lock=" + entry.getValue());
          }
        }
      }
    }
  }

  @Override
  public void memberJoined(final InternalDistributedMember member) {
    // invoke any "before" listener if provided
    if (this.beforeMemberDeparted != null) {
      this.beforeMemberDeparted.memberJoined(member);
    }
  }

  @Override
  public void memberSuspect(final InternalDistributedMember member,
      final InternalDistributedMember whoSuspected) {
    // invoke any "before" listener if provided
    if (this.beforeMemberDeparted != null) {
      this.beforeMemberDeparted.memberSuspect(member, whoSuspected);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void quorumLost(Set<InternalDistributedMember> failures,
      List<InternalDistributedMember> remaining) {
    // invoke any "before" listener if provided
    if (this.beforeMemberDeparted != null) {
      this.beforeMemberDeparted.quorumLost(failures, remaining);
    }
  }

  @Override
  public String toString() {
    return "[GfxdDRWLockService: " + this.serviceName + "]";
  }
}
