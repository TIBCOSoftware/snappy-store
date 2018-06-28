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

package com.gemstone.gemfire.distributed.internal.locks;

import java.util.concurrent.CancellationException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.*;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.internal.*;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.StopWatch;
import com.gemstone.gemfire.internal.util.concurrent.FutureResult;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.distributed.internal.deadlock.UnsafeThreadLocal;
import com.gemstone.gemfire.distributed.internal.locks.DLockQueryProcessor.DLockQueryReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.*;

/**
 * Implements the distributed locking service with distributed lock grantors.
 *
 * @author Dave Monnie
 * @author Kirk Lund
 */
public class DLockService extends DistributedLockService {

  public static final long NOT_GRANTOR_SLEEP = Long.getLong(
      "gemfire.DLockService.notGrantorSleep", 100).longValue();
  
  public static final boolean DEBUG_DISALLOW_NOT_HOLDER = Boolean.getBoolean(
      "gemfire.DLockService.debug.disallowNotHolder");

  public static final boolean DEBUG_LOCK_REQUEST_LOOP = Boolean.getBoolean(
      "gemfire.DLockService.debug.disallowLockRequestLoop");

  public static final int DEBUG_LOCK_REQUEST_LOOP_COUNT = Integer.getInteger(
      "gemfire.DLockService.debug.disallowLockRequestLoopCount", 20).intValue();

  public static final boolean DEBUG_NONGRANTOR_DESTROY_LOOP = Boolean.getBoolean(
      "gemfire.DLockService.debug.nonGrantorDestroyLoop");

  public static final int DEBUG_NONGRANTOR_DESTROY_LOOP_COUNT = Integer.getInteger(
      "gemfire.DLockService.debug.nonGrantorDestroyLoopCount", 20).intValue();

  public static final boolean DEBUG_ENFORCE_SAFE_EXIT = Boolean.getBoolean(
      "gemfire.DLockService.debug.enforceSafeExit");
  
  public static final boolean AUTOMATE_FREE_RESOURCES = Boolean.getBoolean(
      "gemfire.DLockService.automateFreeResources");
  
  public static final int INVALID_LEASE_ID = -1;
  
  /** Unique name for this instance of the named locking service */
  protected final String serviceName;
  
  /** DistributionManager for this member */
  private final DM dm;
  
  /** 
   * DistributedSystem connection for this member 
   * (used for DisconnectListener, logging, etc) 
   */
  protected final InternalDistributedSystem ds;
  
  /** Known lock tokens for this service. Key:Object(name), Value:DLockToken */
  private final Map<Object, DLockToken> tokens = new HashMap<Object, DLockToken>();
  
  /** 
   * True if this member has destroyed this named locking service. Field is 
   * volatile only because it's referenced in {@link #toString()} (never 
   * synchronize in <code>toString</code>).
   */
  private volatile boolean destroyed = false;
  
  /** 
   * True if this is a distributed lock service; false if local to this vm 
   * only. TX has a "local" lock service which sets this to false. 
   */
  private final boolean isDistributed;

  /** Optional handler for departure of lease holders; used by grantor */
  private DLockLessorDepartureHandler lessorDepartureHandler;

  /** 
   * Hook for transactions which allows custom behavior in processing 
   * DLockRecoverGrantorMessage 
   */
  private DLockRecoverGrantorProcessor.MessageProcessor recoverGrantorProcessor;
  
  /** Thread-safe reference to DistributedLockStats */
  private final DistributedLockStats dlockStats = getOrCreateStats();
  
  /** 
   * Protects {@link #lockGrantorId}, {@link #grantor} and 
   * {@link #lockGrantorFutureResult}. Final granting of a lock occurs under 
   * this synchronization and only if <code>lockGrantorId</code> matches the
   * grantor that granted the lock.  
   */
  private final Object lockGrantorIdLock = new Object();
  
  /** Identifies the current grantor for this lock service. */
  private LockGrantorId lockGrantorId;
  
  /** 
   * Local instance of the lock grantor if this process is the grantor. This 
   * field is volatile for one use: 1) {@link #toString()} which should not
   * use synchronization due to potential for wrong lock ordering. Can
   * we make this non-volatile??
   */
  private volatile DLockGrantor grantor;
  
  /** 
   * Count of currently active locks and lock requests. Used to determine if
   * destroy must tell the grantor to release all held locks. 
   */
  private int activeLocks = 0;
  
  /** True if this service should be destroyed in system DisconnectListener */
  private final boolean destroyOnDisconnect;
  
  /** True if this service should automatically freeResources */
  private final boolean automateFreeResources;
  
  /** LogWriterI18n for dlock which checks for DistributedLockService.VERBOSE. */
  private final DLockLogWriter logWriter;
  
  /** Identifies the thread that is destroying this lock service. */
  private final ThreadLocal<Boolean> destroyingThread = new ThreadLocal<Boolean>();
  
  ///** Held during destory and creation of this lock service. */
  //private final Object serviceLock = new Object();
  
  /** Protects access to {@link #destroyed} and {@link #activeLocks}. */
  private final Object destroyLock = new Object();
  
  /** 
   * Created by the thread communicating directly with the elder. Other threads
   * will wait on this and then use the resulting lockGrantorId. This ensures
   * that only one message is sent to the elder and that only one thread does
   * so at a time. Protected by {@link #lockGrantorIdLock} and holds a
   * reference to a {@link LockGrantorId}.
   * <p>
   * Only outbound threads and operations should ever wait on this. Do NOT
   * allow inbound threads to use the <code>lockGrantorFutureResult</code>.
   */
  private FutureResult lockGrantorFutureResult;
  
  private final DLockStopper stopper;
  
  // -------------------------------------------------------------------------
  //   State and concurrency construct methods
  // -------------------------------------------------------------------------
  
  public boolean isDestroyed() {
    synchronized (this.destroyLock) {
      if (this.destroyed) {
        if (!isCurrentThreadDoingDestroy()) {
          return true;
        }
      }
      return false;
    }
  }
  
  public void checkDestroyed() {
    if (isDestroyed()) {
      throw generateLockServiceDestroyedException(
          generateLockServiceDestroyedMessage());
    }
  }
  
  /**
   * Create a new LockServiceDestroyedException for this lock service.
   * 
   * @param message the detail message that explains the exception
   * @return new LockServiceDestroyedException
   */
  protected LockServiceDestroyedException generateLockServiceDestroyedException(String message) {
    return new LockServiceDestroyedException(message);
  }
  
  /**
   * Returns the string message to use in a LockServiceDestroyedException for 
   * this lock service.
   * 
   * @return the detail message that explains LockServiceDestroyedException
   */
  protected String generateLockServiceDestroyedMessage() {
    return LocalizedStrings.DLockService_0_HAS_BEEN_DESTROYED
      .toLocalizedString(this);
  }
  
  /**
   * Returns true if {@link #lockGrantorId} is the same as the specified
   * LockGrantorId. Caller must synchronize on {@link #lockGrantorIdLock}.
   * 
   * @param someLockGrantorId the LockGrantorId to check 
   */
  private boolean checkLockGrantorId(LockGrantorId someLockGrantorId) {
    Assert.assertHoldsLock(this.lockGrantorIdLock,true);
    if (this.lockGrantorId == null) {
      return false;
    }
    return this.lockGrantorId.sameAs(someLockGrantorId);
  }
  
  /**
   * Returns true if lockGrantorId is the same as the specified
   * LockGrantorId. Caller must synchronize on lockGrantorIdLock.
   * 
   * @param someLockGrantorId the LockGrantorId to check 
   */
  public boolean isLockGrantorId(LockGrantorId someLockGrantorId) {
    synchronized(this.lockGrantorIdLock) {
      return checkLockGrantorId(someLockGrantorId);
    }
  }

  private final boolean isCurrentThreadDoingDestroy() {
    return Boolean.TRUE.equals(this.destroyingThread.get());
  }

  private final void setDestroyingThread() {
    this.destroyingThread.set(Boolean.TRUE);
  }
  
  private final void clearDestroyingThread() {
    this.destroyingThread.remove();
  }

  private InternalDistributedMember getElderId() {
    InternalDistributedMember elder = this.dm.getElderId();
    if (elder == null) {
      this.dm.getSystem().getCancelCriterion().checkCancelInProgress(null);
    }
    Assert.assertTrue(elder != null);
    return elder;
  }
  
  /**
   * Returns id of the current lock grantor for this service. If necessary,
   * a request will be sent to the elder to fetch this information.
   */
  public LockGrantorId getLockGrantorId() { 
    boolean ownLockGrantorFutureResult = false;
    FutureResult lockGrantorFutureResultRef = null;

    long statStart = -1;
    LockGrantorId theLockGrantorId = null;
    while (theLockGrantorId == null) {
      
      ownLockGrantorFutureResult = false;
      try {
        Assert.assertHoldsLock(this.destroyLock,false);
        synchronized (this.lockGrantorIdLock) {
          if (this.lockGrantorFutureResult != null) {
            lockGrantorFutureResultRef = this.lockGrantorFutureResult;
          }
          else if (this.lockGrantorId != null) {
            return this.lockGrantorId;
          }
          else {
            ownLockGrantorFutureResult = true;
            lockGrantorFutureResultRef = 
                new FutureResult(this.dm.getCancelCriterion());
            getLogWriter().fine("[getLockGrantorId] creating lockGrantorFutureResult");
            this.lockGrantorFutureResult = lockGrantorFutureResultRef;
          }
        }
      
        statStart = getStats().startGrantorWait();
        if (!ownLockGrantorFutureResult) {
          LockGrantorId lockGrantorIdRef = 
            waitForLockGrantorFutureResult(lockGrantorFutureResultRef);
          if (lockGrantorIdRef != null) {
            return lockGrantorIdRef;
          }
          else {
            continue;
          }
        }
      
        InternalDistributedMember elder = getElderId();
        Assert.assertTrue(elder != null);
        
        GrantorInfo gi = getGrantorRequest();
        theLockGrantorId = new LockGrantorId(
            this.dm, gi.getId(), gi.getVersionId(), gi.getSerialNumber());

        if (getLogWriter().fineEnabled()) {
          getLogWriter().fine("[getLockGrantorId] elder says grantor is " + theLockGrantorId);
        }
        
        // elder tells us to be the grantor...
        if (theLockGrantorId.isLocal(getSerialNumber())) {
          boolean needsRecovery = gi.needsRecovery();
          if (!needsRecovery) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("[getLockGrantorId] needsRecovery is false");
            }
            synchronized (this.lockGrantorIdLock) {
              // either no previous grantor or grantor is newer
              Assert.assertTrue(this.lockGrantorId == null ||
                  this.lockGrantorId.isNewerThan(theLockGrantorId) ||
                  this.lockGrantorId.sameAs(theLockGrantorId),
                  this.lockGrantorId + " should be null or newer than or same as " + theLockGrantorId);
            }
          }
          if (!createLocalGrantor(elder, needsRecovery, theLockGrantorId)) {
            theLockGrantorId = this.lockGrantorId;
          }
        }
        
        // elder says another member is the grantor
        else {
          synchronized (this.lockGrantorIdLock) {
            if (!setLockGrantorId(theLockGrantorId)) {
              theLockGrantorId = this.lockGrantorId;
            }
          }
        }
      }
      finally {
        synchronized (this.lockGrantorIdLock) {
          boolean getLockGrantorIdFailed = theLockGrantorId == null;
          if (statStart > -1) {
            getStats().endGrantorWait(statStart, getLockGrantorIdFailed);
          }
          if (ownLockGrantorFutureResult) {
            // this thread is doing the real work and must finish the future
            Assert.assertTrue(
                this.lockGrantorFutureResult == lockGrantorFutureResultRef);
            if (getLockGrantorIdFailed) {
              // failed so cancel lockGrantorFutureResult
              lockGrantorFutureResultRef.cancel(false);
            }
            else {
              // succeeded so set lockGrantorFutureResult
              lockGrantorFutureResultRef.set(theLockGrantorId);
            }
            // null out the reference so it is free for next usage
            this.lockGrantorFutureResult = null;
          }
        }
      } // finally block for lockGrantorFutureResult
    } // while theLockGrantorId == null
    return theLockGrantorId;
  }

  /**
   * Creates a local {@link DLockGrantor}.
   * 
   * if (!createLocalGrantor(xxx)) {
   *    theLockGrantorId = this.lockGrantorId;
   * }
   * 
   * @param elder the elder that told us to be the grantor
   * @param needsRecovery true if recovery is required
   * @param myLockGrantorId lockGrantorId to use
   * @return true if successfully created local grantor; false if aborted
   */
  private boolean createLocalGrantor(InternalDistributedMember elder, 
                                     boolean needsRecovery, 
                                     LockGrantorId myLockGrantorId) {
    DLockGrantor myGrantor = DLockGrantor.createGrantor(
        this, myLockGrantorId.getLockGrantorVersion());
    getLogWriter().fine("[createLocalGrantor] Calling makeLocalGrantor");
    return makeLocalGrantor(elder, needsRecovery, myLockGrantorId, myGrantor);
  }
  private boolean makeLocalGrantor(InternalDistributedMember elder, 
                                   boolean needsRecovery, 
                                   LockGrantorId myLockGrantorId,
                                   DLockGrantor myGrantor) {
    boolean success = false;
    try {
      synchronized (this.lockGrantorIdLock) {
        if (isDestroyed()) {
          checkDestroyed(); // exit
        }
        
        InternalDistributedMember currentElder = getElderId();
        if (!currentElder.equals(elder)) {
          // abort because elder changed
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine("Failed to create " + myLockGrantorId + 
                " because elder changed from " +
                elder + " to " + currentElder);
          }
          return false; // exit
        }
        
        if (this.deposingLockGrantorId != null) {
          if (this.deposingLockGrantorId.isNewerThan(myLockGrantorId)) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("Failed to create " + myLockGrantorId + 
                  " because I was deposed by " +  this.deposingLockGrantorId);
            }
            this.deposingLockGrantorId = null;
            return false; // exit
          }
          
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine(this.deposingLockGrantorId + 
                " failed to depose " + myLockGrantorId);
          }
          // older grantor couldn't depose us, so null him out...
          this.deposingLockGrantorId = null;
        }
        
        if (!setLockGrantorId(myLockGrantorId, myGrantor)) {
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine("[getLockGrantorId] failed to create " +
                myLockGrantorId + " because current grantor is " +
                this.lockGrantorId);
          }
          return false; // exit
        }
      } // release sync on this.lockGrantorIdLock
      
      // do NOT sync while doing recovery (because it waits for replies) 
      if (needsRecovery) {
        boolean recovered = DLockRecoverGrantorProcessor.recoverLockGrantor(
            this.dm.getDistributionManagerIds(), // include this vm
            this, // this lock service
            myGrantor,
            this.dm,
            elder); // the elder that told us to be the grantor
        if (!recovered) {
          checkDestroyed();
          return false; // exit
        }
      }
      
      // after recovery, resynchronize on lockGrantorIdLock again
      // check to see if myLockGrantorId has been deposed
      synchronized (this.lockGrantorIdLock) {
        if (isDestroyed()) {
          checkDestroyed(); // exit
        }
        
        if (this.deposingLockGrantorId != null) {
          if (this.deposingLockGrantorId.isNewerThan(myLockGrantorId)) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("Failed to create " + myLockGrantorId + 
                  " because I was deposed by " +  this.deposingLockGrantorId);
            }
            this.deposingLockGrantorId = null;
            return false; // exit
          }
          
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine(this.deposingLockGrantorId + 
                " failed to depose " + myLockGrantorId);
          }
          this.deposingLockGrantorId = null;
        }
        
        if (checkLockGrantorId(myLockGrantorId)) {
          success = myGrantor.makeReady(true); // do not enforce initializing
        }
      }
      
      return success; // exit
    }
    catch (Error e) {
      if (SystemFailure.isJVMFailureError(e)) {
        SystemFailure.initiateFailure(e);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw e;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above).  However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      getLogWriter().fine("[makeLocalGrantor] throwing Error", e);
      throw e;
    }
    catch (RuntimeException e) {
      getLogWriter().fine("[makeLocalGrantor] throwing RuntimeException", e);
      throw e;
    }
    finally {
      
      try {
        // abort if unsuccessful or if lock service was destroyed
        if (!success || isDestroyed()) {
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine("[makeLocalGrantor] aborting " + 
                myLockGrantorId + " and " + myGrantor);
          }
          nullLockGrantorId(myLockGrantorId);
          if (!myGrantor.isDestroyed()) {
            myGrantor.destroy();
          }
        }
      }
      finally {
        // assertion: grantor should now be either ready or destroyed!
      
        if (myGrantor.isInitializing() && 
            dm.getCancelCriterion().cancelInProgress() == null) { 
          getLogWriter().error(LocalizedStrings.DLockService_GRANTOR_IS_STILL_INITIALIZING);
        }
        if (!success && !myGrantor.isDestroyed() && 
            dm.getCancelCriterion().cancelInProgress() == null) {
          getLogWriter().error(
              LocalizedStrings.DLockService_GRANTOR_CREATION_WAS_ABORTED_BUT_GRANTOR_WAS_NOT_DESTROYED);
        }
      }
    }
  }
  
  /** 
   * Set {@link #lockGrantorId} to the given new value if the 
   * current value is null or is an older grantor version. Caller must hold 
   * {@link #lockGrantorIdLock}.
   * 
   * @param newLockGrantorId the new value for lockGrantorId
   */
  private boolean setLockGrantorId(LockGrantorId newLockGrantorId) {
    Assert.assertHoldsLock(this.lockGrantorIdLock,true);
    if (equalsLockGrantorId(newLockGrantorId)) {
      return true;
    } 
    else if (!newLockGrantorId.hasLockGrantorVersion()) {
      // proceed with temporary placeholder used by become grantor
      this.lockGrantorId = newLockGrantorId;
      return true;
    }
    else if (newLockGrantorId.isRemote() &&
             this.lockGrantorId != null &&
             this.lockGrantorId.hasLockGrantorVersion()) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[setLockGrantorId] tried to replace " + 
            this.lockGrantorId + " with " + newLockGrantorId);
      }
      return false;
    }
    else if (newLockGrantorId.isNewerThan(this.lockGrantorId)) {
      this.lockGrantorId = newLockGrantorId;
      return true;
    }
    else {
      return false;
    }
  }

  /** 
   * Set {@link #lockGrantorId} to the <code>localLockGrantorId</code> if
   * current value is null or is an older grantor version. This also atomically
   * sets {@link #grantor} to ensure that the two fields are kept in sync.
   * Caller must hold {@link #lockGrantorIdLock}.
   * 
   * @param localLockGrantorId the new value for lockGrantorId
   * @param localGrantor the new local intance of DLockGrantor
   */
  private boolean setLockGrantorId(LockGrantorId localLockGrantorId,
                                   DLockGrantor localGrantor) {
    Assert.assertHoldsLock(this.lockGrantorIdLock,true);
    Assert.assertTrue(localLockGrantorId.isLocal(getSerialNumber()));
    if (setLockGrantorId(localLockGrantorId)) {
      this.grantor = localGrantor;
      return true;
    }
    return false;
  }

  private LockGrantorId deposingLockGrantorId;
  
  /**
   * Deposes {@link #lockGrantorId} if <code>newLockGrantorId</code> is newer.
   * 
   * @param newLockGrantorId the new lock grantor
   */
  void deposeOlderLockGrantorId(LockGrantorId newLockGrantorId) {
    LockGrantorId deposedLockGrantorId = null;
    synchronized (this.lockGrantorIdLock) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[deposeOlderLockGrantorId] pre-deposing " + 
            deposedLockGrantorId + " for new " + newLockGrantorId);
      }
      this.deposingLockGrantorId = newLockGrantorId;
      deposedLockGrantorId = this.lockGrantorId;
    }
    if (deposedLockGrantorId != null &&
        deposedLockGrantorId.hasLockGrantorVersion() &&
        newLockGrantorId.isNewerThan(deposedLockGrantorId)) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[deposeOlderLockGrantorId] post-deposing " + 
            deposedLockGrantorId + " for new " + newLockGrantorId);
      }
      nullLockGrantorId(deposedLockGrantorId);
    }
//    if (verifyLockGrantorId(deposedLockGrantorId)) {
//      getLogWriter().severe(
//          "deposeOlderLockGrantorId attempted to depose elder verified grantor");
//    }
  }

  /**
   * Sets {@link #lockGrantorId} to null if the current value equals the
   * expected old value. Caller must hold {@link #lockGrantorIdLock}.
   * 
   * @param oldLockGrantorId the expected old value
   * @return true if lockGrantorId was set to null
   */
  private boolean nullLockGrantorId(LockGrantorId oldLockGrantorId) {
    Assert.assertHoldsLock(this.destroyLock,false);
    Assert.assertHoldsLock(this.lockGrantorIdLock,false);
    if (oldLockGrantorId == null) {
      return false;
    }
    DLockGrantor grantorToDestroy = null;
    try {
      synchronized (this.lockGrantorIdLock) {
        if (equalsLockGrantorId(oldLockGrantorId) ||
            (oldLockGrantorId.isLocal(getSerialNumber()) && isMakingLockGrantor())) {
//            this.lockGrantorId != null && this.lockGrantorId.isLocal())) {
          if (oldLockGrantorId.isLocal(getSerialNumber()) && 
              isLockGrantorVersion(
                  this.grantor, oldLockGrantorId.getLockGrantorVersion())) {
            // need to destroy and remove grantor
            grantorToDestroy = this.grantor;
            this.grantor = null;
          }
          this.lockGrantorId = null;
          return true;
        }
        else {
          return false;
        }
      }
    }
    finally {
      if (grantorToDestroy != null) {
        if (getLogWriter().fineEnabled()) {
          getLogWriter().fine("[nullLockGrantorId] destroying " + 
              grantorToDestroy);
        }
        grantorToDestroy.destroy();
      }
    }
  }

  /**
   * Returns true if the grantor version of <code>dlockGrantor</code> equals
   * the <code>grantorVersion</code>.
   * 
   * @param dlockGrantor the grantor instance to compare to grantorVersion
   * @param grantorVersion the grantor version number
   * @return true if dlockGrantor is the same grantor version
   */
  private boolean isLockGrantorVersion(DLockGrantor dlockGrantor,
                                       long grantorVersion) {
    if (dlockGrantor == null) {
      return false;
    }
    return dlockGrantor.getVersionId() == grantorVersion;
  }
  
  /**
   * Returns true if <code>someLockGrantor</code> equals the current
   * {@link #lockGrantorId}.
   * 
   * @param someLockGrantor
   * @return true if someLockGrantor equals the current lockGrantorId
   */
  private boolean equalsLockGrantorId(LockGrantorId someLockGrantor) {
    Assert.assertHoldsLock(this.lockGrantorIdLock,true);
    if (someLockGrantor == null) {
      return this.lockGrantorId == null;
    }
    return someLockGrantor.equals(this.lockGrantorId);
  }
  
  /**
   * Returns id of the current lock grantor for this service. If necessary,
   * a request will be sent to the elder to fetch this information.
   * Unlike getLockGrantorId this call will not become the lock grantor.
   */
  public LockGrantorId peekLockGrantorId() { 
    Assert.assertHoldsLock(this.destroyLock,false);
    synchronized (this.lockGrantorIdLock) {
      LockGrantorId currentLockGrantorId = this.lockGrantorId;
      if (currentLockGrantorId != null) {
        return currentLockGrantorId;
      }
    }

    long statStart = getStats().startGrantorWait();
    LockGrantorId theLockGrantorId = null;
    try {
      // 1st thread wins the right to request grantor info from elder
      GrantorInfo gi = peekGrantor();
      InternalDistributedMember lockGrantorMember = gi.getId();
      if (lockGrantorMember == null) {
        return null;
      }
      theLockGrantorId = new LockGrantorId(
          this.dm, lockGrantorMember, gi.getVersionId(), gi.getSerialNumber());
      return theLockGrantorId;
    }
    finally {
      boolean getLockGrantorIdFailed = theLockGrantorId == null;
      getStats().endGrantorWait(statStart, getLockGrantorIdFailed);
    }
  }

  /**
   * Increments {@link #activeLocks} while synchronized on {@link #destroyLock}
   * after calling {@link #checkDestroyed()}.
   */
  private void incActiveLocks() {
    synchronized (this.destroyLock) {
      checkDestroyed();
      this.activeLocks++;
    }
  }
  
  /**
   * Decrements {@link #activeLocks} while synchronized on {@link #destroyLock}.
   */
  private void decActiveLocks() {
    synchronized (this.destroyLock) {
      this.activeLocks--;
    }
  }
  
  /**
   * Returns lockGrantorId when lockGrantorFutureResultRef has been set by 
   * another thread.
   * 
   * @param lockGrantorFutureResultRef FutureResult to wait for
   * @return the LockGrantorId or null if FutureResult was cancelled
   */
  private LockGrantorId waitForLockGrantorFutureResult(FutureResult lockGrantorFutureResultRef) {
    LockGrantorId lockGrantorIdRef = null;
    while (lockGrantorIdRef == null) {
      boolean interrupted = Thread.interrupted();
      try {
        checkDestroyed();
        lockGrantorIdRef = (LockGrantorId) lockGrantorFutureResultRef.get();
      }
      catch (InterruptedException e) {
        interrupted = true;
        this.dm.getCancelCriterion().checkCancelInProgress(e);
        if (lockGrantorFutureResultRef.isCancelled()) {
          // cancelled Future might throw InterruptedException...?
          checkDestroyed();
          break; // return null
        }
        continue;
      }
      catch (CancellationException e) { // Future was cancelled
        checkDestroyed();
        break; // return null
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    return lockGrantorIdRef;
  }
  
  private void notLockGrantorId(LockGrantorId notLockGrantorId, boolean waitForGrantor) {
    if (notLockGrantorId.isLocal(getSerialNumber())) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("notLockGrantorId " + this.serviceName
                                 + " returning early because notGrantor "
                                 + notLockGrantorId
                                 + " was equal to the local dm "
                                 + this.dm.getId());
      }
      // Let the local destroy or processing of transfer do the clear
      return;
    }
    
    boolean ownLockGrantorFutureResult = false;
    FutureResult lockGrantorFutureResultRef = null;

    long statStart = -1;
    LockGrantorId currentLockGrantorId = null;
    
    try {
      Assert.assertHoldsLock(this.destroyLock,false);
      synchronized (this.lockGrantorIdLock) {
        currentLockGrantorId = this.lockGrantorId;
        if (this.lockGrantorFutureResult != null) {
          // some other thread is talking to elder
          lockGrantorFutureResultRef = this.lockGrantorFutureResult;
        }
        else if (!notLockGrantorId.sameAs(currentLockGrantorId)) {
          return;
        }
        else {
          // this thread needs to talk to elder
          ownLockGrantorFutureResult = true;
          lockGrantorFutureResultRef = 
              new FutureResult(this.dm.getCancelCriterion());
          this.lockGrantorFutureResult = lockGrantorFutureResultRef;
        }
      }
    
      statStart = getStats().startGrantorWait();
      if (!ownLockGrantorFutureResult) {
        if (waitForGrantor) { // fix for bug #43708
          waitForLockGrantorFutureResult(lockGrantorFutureResultRef);
        }
        return;
      }
    
      InternalDistributedMember elder = getElderId();
      Assert.assertTrue(elder != null);
      
      LockGrantorId elderLockGrantorId = null;
      GrantorInfo gi = peekGrantor();
      if (gi.getId() != null) {
        elderLockGrantorId = 
          new LockGrantorId(this.dm, gi.getId(), gi.getVersionId(), gi.getSerialNumber());
      }
      
      if (notLockGrantorId.sameAs(elderLockGrantorId)) {
        // elder says that notLockGrantorId is still the grantor...
        sleep(NOT_GRANTOR_SLEEP);
        return;
      }
      else {
        // elder says another member is the grantor
        nullLockGrantorId(notLockGrantorId);
        if (getLogWriter().fineEnabled()) {
          getLogWriter().fine("notLockGrantorId cleared lockGrantorId "
                              + " for service " + this.serviceName);
        }
      }
    }
    finally {
      synchronized (this.lockGrantorIdLock) {
        if (statStart > -1) {
          getStats().endGrantorWait(statStart, false);
        }
        if (ownLockGrantorFutureResult) {
          // this thread is doing the real work and must finish the future
          Assert.assertTrue(
              this.lockGrantorFutureResult == lockGrantorFutureResultRef);
          // cancel lockGrantorFutureResult
          lockGrantorFutureResultRef.cancel(false);
          // null out the reference so it is free for next usage
          this.lockGrantorFutureResult = null;
        }
      }
    } // finally block for lockGrantorFutureResult
  }
  
  /** 
   * All calls to GrantorRequestProcessor.clearGrantor must come through
   * this synchronization point.
   * <p>
   * This fixes a deadlock between this.becomeGrantorMonitor and 
   * DistributionManager.elderLock
   * <p>
   * All calls to the elder may result in elder recovery which may call back
   * into dlock and acquire synchronization on this.becomeGrantorMonitor.
   */
  void clearGrantor(long grantorVersion, boolean withLocks) {
    GrantorRequestProcessor.clearGrantor(grantorVersion, this, getSerialNumber(), 
        this.ds, withLocks);
  } 
  /** 
   * All calls to GrantorRequestProcessor.getGrantor must come through
   * this synchronization point.
   * <p>
   * This fixes a deadlock between this.becomeGrantorMonitor and 
   * DistributionManager.elderLock
   * <p>
   * All calls to the elder may result in elder recovery which may call back
   * into dlock and acquire synchronization on this.becomeGrantorMonitor.
   */
  private GrantorInfo getGrantorRequest() {
    return GrantorRequestProcessor.getGrantor(this, getSerialNumber(), 
        this.ds);
  }
  /** 
   * All calls to GrantorRequestProcessor.peekGrantor must come through
   * this synchronization point.
   * <p>
   * This fixes a deadlock between this.becomeGrantorMonitor and 
   * DistributionManager.elderLock
   * <p>
   * All calls to the elder may result in elder recovery which may call back
   * into dlock and acquire synchronization on this.becomeGrantorMonitor.
   */
  private GrantorInfo peekGrantor() {
    return GrantorRequestProcessor.peekGrantor(this, 
        this.ds);
  } 
  /** 
   * All calls to GrantorRequestProcessor.becomeGrantor must come through
   * this synchronization point.
   * <p>
   * This fixes a deadlock between this.becomeGrantorMonitor and 
   * DistributionManager.elderLock
   * <p>
   * All calls to the elder may result in elder recovery which may call back
   * into dlock and acquire synchronization on this.becomeGrantorMonitor.
   */
  private GrantorInfo becomeGrantor(InternalDistributedMember predecessor) {
    return GrantorRequestProcessor.becomeGrantor(this, getSerialNumber(), 
        predecessor, this.ds);
  } 
  
  // -------------------------------------------------------------------------
  //   New external API methods
  // -------------------------------------------------------------------------

  @Override
  public void becomeLockGrantor() {
    becomeLockGrantor((InternalDistributedMember)null);
  }

  public DLockGrantor getGrantor() {
    Assert.assertHoldsLock(this.destroyLock,false);
    synchronized (this.lockGrantorIdLock) {
      return this.grantor;
    }
  }
  
  public DLockGrantor getGrantorForElderRecovery() {
    return getGrantor();
  }
  
  public DLockGrantor getGrantorWithNoSync() {
    return this.grantor;
  }
  
  /**
   * @param predecessor non-null if a predecessor asked us to take over for him
   */
  void becomeLockGrantor(InternalDistributedMember predecessor) {
    Assert.assertTrue(predecessor == null);
    boolean ownLockGrantorFutureResult = false;
    FutureResult lockGrantorFutureResultRef = null;
    
    LockGrantorId myLockGrantorId = null;    
    try { // finally handles lockGrantorFutureResult
      
      // loop while other threads control the lockGrantorFutureResult
      //   terminate loop if other thread has already made us lock grantor
      //   terminate loop if this thread gets control of lockGrantorFutureResult
      while (!ownLockGrantorFutureResult) {
        Assert.assertHoldsLock(this.destroyLock,false);
        synchronized (this.lockGrantorIdLock) {
          if (isCurrentlyOrIsMakingLockGrantor()) {
            return;
          }
          else if (this.lockGrantorFutureResult != null) {
            // need to wait for other thread controlling lockGrantorFutureResult
            lockGrantorFutureResultRef = this.lockGrantorFutureResult;
          }
          else {
            // this thread is in control and will procede to become grantor
            // create new lockGrantorFutureResult for other threads to block on
            ownLockGrantorFutureResult = true;
            lockGrantorFutureResultRef = 
                new FutureResult(this.dm.getCancelCriterion());
            getLogWriter().fine("[becomeLockGrantor] creating lockGrantorFutureResult");
            this.lockGrantorFutureResult = lockGrantorFutureResultRef;
          }
        }
        if (!ownLockGrantorFutureResult) {
          waitForLockGrantorFutureResult(lockGrantorFutureResultRef);
          continue;
        }
      }
      
      // this thread is now in charge of the lockGrantorFutureResult future
      getStats().incBecomeGrantorRequests();
  
      // create the new grantor instance in non-ready state...
      long tempGrantorVersion = -1;
      LockGrantorId tempLockGrantorId =new LockGrantorId(
          this.dm, this.dm.getId(), tempGrantorVersion, getSerialNumber());
  
      DLockGrantor myGrantor = 
          DLockGrantor.createGrantor(this, tempGrantorVersion);
        
      try { // finally handles myGrantor

        synchronized (this.lockGrantorIdLock) {
          Assert.assertTrue(setLockGrantorId(tempLockGrantorId, myGrantor));
        }
        
        if (getLogWriter().fineEnabled()) {
          getLogWriter().fine("become set lockGrantorId to "
                              + this.lockGrantorId
                              + " for service " + this.serviceName);
        }
          
        InternalDistributedMember elder = getElderId();
        Assert.assertTrue(elder != null);
        
        // NOTE: elder currently returns GrantorInfo for the previous grantor
        // CONSIDER: add elderCommunicatedWith to GrantorInfo
        GrantorInfo gi = becomeGrantor(predecessor); 
        boolean needsRecovery = gi.needsRecovery();
        long myGrantorVersion = gi.getVersionId()+1;
        myGrantor.setVersionId(myGrantorVersion);
        
        myLockGrantorId = new LockGrantorId(
            this.dm, this.dm.getId(), myGrantorVersion, getSerialNumber());

        getLogWriter().fine("[becomeLockGrantor] Calling makeLocalGrantor");
        if (!makeLocalGrantor(elder, needsRecovery, myLockGrantorId, myGrantor)) {
          return;
        }
        
      }
      finally {
        Assert.assertTrue(myGrantor == null 
            || !myGrantor.isInitializing()
            || this.dm.getCancelCriterion().cancelInProgress() != null
            || isDestroyed(),
            "BecomeLockGrantor failed and left grantor non-ready");
      }
    }
    finally {
      synchronized (this.lockGrantorIdLock) {
        if (ownLockGrantorFutureResult) {
          // this thread is doing the real work and must finish the future
          Assert.assertTrue(
              this.lockGrantorFutureResult == lockGrantorFutureResultRef);
          boolean getLockGrantorIdFailed = myLockGrantorId == null;
          if (getLockGrantorIdFailed) {
            // failed so cancel lockGrantorFutureResult
            lockGrantorFutureResultRef.cancel(true); // interrupt waiting threads
          }
          else {
            this.dm.getCancelCriterion().checkCancelInProgress(null); // don't succeed if shutting down
            // succeeded so set lockGrantorFutureResult
            lockGrantorFutureResultRef.set(myLockGrantorId);
          }
          // null out the reference so it is free for next usage
          this.lockGrantorFutureResult = null;
        }
      }
    }
  }
  
  @Override
  public boolean isLockGrantor() {
    if (isDestroyed()) {
      return false;
    } else {
      return isCurrentlyLockGrantor();
    }
  }

  protected boolean isMakingLockGrantor() {
    Assert.assertHoldsLock(this.destroyLock,false);
    synchronized (this.lockGrantorIdLock) {
      return this.lockGrantorId != null && 
             this.lockGrantorId.isLocal(getSerialNumber()) &&
             this.grantor != null &&
             this.grantor.isInitializing();
    }
  }

  protected boolean isCurrentlyOrIsMakingLockGrantor() {
    Assert.assertHoldsLock(this.destroyLock,false);
    synchronized (this.lockGrantorIdLock) {
      return this.lockGrantorId != null && 
             this.lockGrantorId.isLocal(getSerialNumber());
    }
  }

  protected boolean isCurrentlyLockGrantor() {
    Assert.assertHoldsLock(this.destroyLock,false);
    synchronized (this.lockGrantorIdLock) {
      return this.lockGrantorId != null && 
             this.lockGrantorId.isLocal(getSerialNumber()) &&
             this.grantor != null &&
             this.grantor.isReady();
    }
  }
  
  // -------------------------------------------------------------------------
  //   External API methods
  // -------------------------------------------------------------------------
  
  @Override
  public void freeResources(Object name) {
    checkDestroyed();
    if (name == null) {
      removeAllUnusedTokens();
    }
    else {
      removeTokenIfUnused(name);
    }
  }
  
  /** 
   * Attempt to destroy and remove lock token. Synchronizes on tokens map
   * and the lock token.
   * 
   * @param name the name of the lock token
   * @return true if token has been destroyed and removed
   */
  private boolean removeTokenIfUnused(Object name) {
    synchronized (this.tokens) {
      if (this.destroyed) {
        getStats().incFreeResourcesFailed();
        return false;
      }
      DLockToken token = this.tokens.get(name);
      if (token != null) {
        synchronized (token) {
          if (!token.isBeingUsed()) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("Freeing " + token + " in " + this);
            }
            removeTokenFromMap(name);
            token.destroy();
            getStats().incTokens(-1);
            getStats().incFreeResourcesCompleted();
            return true;
          }
        }
      }
    }
    getStats().incFreeResourcesFailed();
    return false;
  }

  protected Object removeTokenFromMap(Object name) {
    return this.tokens.remove(name);
  }

  /** 
   * Attempt to destroy and remove all unused lock tokens. Synchronizes on
   * tokens map and each lock token.
   */
  private void removeAllUnusedTokens() {
    synchronized (this.tokens) {
      if (this.destroyed) {
        getStats().incFreeResourcesFailed();
        return;
      }
      Set unusedTokens = Collections.EMPTY_SET;
      for (Iterator iter = this.tokens.values().iterator(); iter.hasNext();) {
        DLockToken token = (DLockToken) iter.next();
        synchronized (token) {
          if (!token.isBeingUsed()) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("Freeing " + token + " in " + this);
            }
            if (unusedTokens == Collections.EMPTY_SET) {
              unusedTokens = new HashSet();
            }
            unusedTokens.add(token);
          }
          else {
            getStats().incFreeResourcesFailed();
          }
        }
      }
      for (Iterator iter = unusedTokens.iterator(); iter.hasNext();) {
        DLockToken token = (DLockToken)iter.next();
        synchronized (token) {
          int tokensSizeBefore = this.tokens.size();
          Object obj = removeTokenFromMap(token.getName());
          Assert.assertTrue(obj != null);
          int tokensSizeAfter = this.tokens.size();
          Assert.assertTrue(tokensSizeBefore - tokensSizeAfter == 1);
          token.destroy();
          getStats().incTokens(-1);
          getStats().incFreeResourcesCompleted();
        }
      }        
    }
  }

  /**
   * Destroys and removes all lock tokens. Caller must synchronize on 
   * destroyLock. Synchronizes on tokens map and each token.
   */
  private void removeAllTokens() {
    synchronized (this.tokens) {
      Assert.assertTrue(this.destroyed);
      for (Iterator iter = this.tokens.values().iterator(); iter.hasNext();) {
        DLockToken token = (DLockToken) iter.next();
        synchronized (token) {
          token.destroy();
        }
      }
      getStats().incTokens(-this.tokens.size());
      this.tokens.clear();
    }
  }
  
  @Override
  public boolean isHeldByCurrentThread(Object name) {
    checkDestroyed();
    synchronized (this.tokens) {
      DLockToken token = basicGetToken(name);
      if (token == null) return false;
      synchronized(token) {
        token.checkForExpiration();
        return token.isLeaseHeldByCurrentThread();
      }
    }
  }
  
  public boolean isHeldByThreadId(Object name, int threadId) {
    checkDestroyed();
    synchronized (this.tokens) {
      DLockToken token = basicGetToken(name);
      if (token == null) return false;
      synchronized(token) {
        token.checkForExpiration();
        if (token.getLesseeThread() == null) {
          return false;
        }
        return token.getLesseeThread().getThreadId() == threadId;
      }
    }
  }
  
  @Override
  public boolean isLockingSuspendedByCurrentThread() {
    checkDestroyed();
    return isHeldByCurrentThread(SUSPEND_LOCKING_TOKEN);
  }
  
  @Override
  public boolean lock(Object name, long waitTimeMillis, long leaseTimeMillis) {
    boolean tryLock = false;
    return lock(name, waitTimeMillis, leaseTimeMillis, tryLock);
  }
  
  public boolean lock(Object name, 
                      long waitTimeMillis, 
                      long leaseTimeMillis,
                      boolean tryLock) {
    return lock(name, waitTimeMillis, leaseTimeMillis, tryLock, false);
  }
  
  public boolean lock(Object name, 
                      long waitTimeMillis, 
                      long leaseTimeMillis,
                      boolean tryLock,
                      boolean disallowReentrant) {
    checkDestroyed();
    try {
      boolean interruptible = false;
      return lockInterruptibly(name, waitTimeMillis, leaseTimeMillis, tryLock, interruptible, disallowReentrant);
    } 
    catch (InterruptedException ex) { // LOST INTERRUPT
      Thread.currentThread().interrupt();
      // fail assertion
      getLogWriter().error(LocalizedStrings.DLockService_LOCK_WAS_INTERRUPTED, ex);
      Assert.assertTrue(false, "lock() was interrupted: " + ex.getMessage());
    }
    return false;
  }

  @Override
  public boolean lockInterruptibly(Object name, long waitTimeMillis, long leaseTimeMillis)
  throws InterruptedException {
    checkDestroyed();
    boolean tryLock = false;
    boolean interruptible = true;
    return lockInterruptibly(name, waitTimeMillis, leaseTimeMillis, tryLock, interruptible, false);
  }
  
  /** Causes the current thread to sleep for millis and may or may not be interruptible */
  private void sleep(long millis, boolean interruptible) throws InterruptedException {
    if (interruptible) {
      if (Thread.interrupted()) throw new InterruptedException();
      Thread.sleep(millis);
      return;
    }
    else {
      sleep(millis);
    }
  }
  
  /** Causes the current thread to sleep for millis uninterruptibly */ 
  private void sleep(long millis) {
    // Non-interruptible case
    StopWatch timer = new StopWatch(true);
    while (true) {
      boolean interrupted = Thread.interrupted();
      try {
        long timeLeft = millis - timer.elapsedTimeMillis();
        if (timeLeft <= 0) {
          break;
        }
        Thread.sleep(timeLeft);
        break;
      }
      catch (InterruptedException e) {
        interrupted = true;
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  protected DLockRequestProcessor createRequestProcessor(
      LockGrantorId grantorId, Object name, int threadId, long startTime,
      long requestLeaseTime, long requestWaitTime, boolean reentrant,
      boolean tryLock) {
    return new DLockRequestProcessor(grantorId, this, name, threadId,
        startTime, requestLeaseTime, requestWaitTime, reentrant, tryLock,
        this.dm);
  }

  protected boolean callReleaseProcessor(InternalDistributedMember grantor,
      Object name, boolean lockBatch, int lockId) {
    return DLockService.callReleaseProcessor(this.dm, this.serviceName,
        grantor, name, lockBatch, lockId);
  }

  public static boolean callReleaseProcessor(DM dm, String serviceName,
      InternalDistributedMember grantor, Object name, boolean lockBatch,
      int lockId) {
    final DLockReleaseProcessor processor = new DLockReleaseProcessor(dm,
        grantor, serviceName, name);
    return processor.release(grantor, serviceName, lockBatch, lockId);
  }

  /**
   * @param name the name of the lock to acquire in this service.  This object 
   * must conform to the general contract of <code>equals(Object)</code> and 
   * <code>hashCode()</code> as described in 
   * {@link java.lang.Object#hashCode()}.
   * 
   * @param waitTimeMillis the number of milliseconds to try to acquire
   * the lock before giving up and returning false.  A value of -1 causes
   * this method to block until the lock is acquired.
   * 
   * @param leaseTimeMillis the number of milliseconds to hold the lock after
   * granting it, before automatically releasing it if it hasn't already
   * been released by invoking {@link #unlock(Object)}.  If 
   * <code>leaseTimeMillis</code> is -1, hold the lock until explicitly 
   * unlocked.
   * 
   * @param tryLock true if the lock should be acquired or fail if currently
   * held.  waitTimeMillis will be ignored if the lock is currently held by
   * another client.
   *
   * @param interruptible true if this lock request is interruptible
   *
   * @return true if the lock was acquired, false if the timeout
   * <code>waitTimeMillis</code> passed without acquiring the lock.
   * 
   * @throws InterruptedException if the thread is interrupted before
   * or during this method.
   *
   * @throws UnsupportedOperationException if attempt to lock batch involves
   * non-tryLocks
   */
  public boolean lockInterruptibly(final Object name, 
                                   final long waitTimeMillis, 
                                   final long leaseTimeMillis,
                                   final boolean tryLock,
                                   final boolean interruptible,
                                   final boolean disallowReentrant)
  throws InterruptedException {
    checkDestroyed();

    boolean interrupted = Thread.interrupted();
    if (interrupted && interruptible) {
      throw new InterruptedException();
    }
    
    boolean abnormalExit = true;
    boolean safeExit = true;
    try { // try-block for abnormalExit and safeExit
      
    long statStart = getStats().startLockWait();
    long startTime = getLockTimeStamp(dm);
    
    long requestWaitTime = waitTimeMillis;
    long requestLeaseTime = leaseTimeMillis;
    
    // -1 means "lease forever".  Long.MAX_VALUE is pretty close.
    if (requestLeaseTime == -1) requestLeaseTime = Long.MAX_VALUE;
    // -1 means "wait forever".  Long.MAX_VALUE is pretty close.
    if (requestWaitTime == -1) requestWaitTime = Long.MAX_VALUE;
    
    long waitLimit = startTime + requestWaitTime;
    if (waitLimit < 0) waitLimit = Long.MAX_VALUE;

    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine(this + ", name: " + name + " - " + "entering lock()");
    }

    DLockToken token = getOrCreateToken(name);
    boolean gotLock = false;
    blockedOn.set(name);
    try { // try-block for end stats, token cleanup, and interrupt check
    
      ThreadRequestState requestState = 
          (ThreadRequestState) this.threadRequestState.get();
      if (requestState == null) {
        requestState = 
            new ThreadRequestState(incThreadSequence(), interruptible);
        this.threadRequestState.set(requestState);
      }
      else {
        requestState.interruptible = interruptible;
      }
      final int threadId = requestState.threadId;
      
      // if reentry and no change to expiration then grantor is not bothered
      
      long leaseExpireTime = 0;
      boolean keepTrying = true;
      int lockId = -1;
      incActiveLocks();
      
      int loopCount = 0;
      while (keepTrying) {
        if (DEBUG_LOCK_REQUEST_LOOP) {
          loopCount++;
          if (loopCount > DEBUG_LOCK_REQUEST_LOOP_COUNT) {
            Integer count = Integer.valueOf(DEBUG_LOCK_REQUEST_LOOP_COUNT);
            String s = LocalizedStrings.DLockService_DEBUG_LOCKINTERRUPTIBLY_HAS_GONE_HOT_AND_LOOPED_0_TIMES.toLocalizedString(count);
            
            
            InternalGemFireError e = new InternalGemFireError(s);
            getLogWriter().error(LocalizedStrings.DLockService_DEBUG_LOCKINTERRUPTIBLY_HAS_GONE_HOT_AND_LOOPED_0_TIMES, count, e);
            throw e;
          }
          /*if (loopCount > 1) {
            Thread.sleep(1000);
          }*/
        }
        
        checkDestroyed();
        interrupted = Thread.interrupted() || interrupted; // clear
        if (interrupted && interruptible) {
          throw new InterruptedException();
        }

        // Check for recursive lock
        boolean reentrant = false;
        int recursionBefore = -1;
        
        synchronized(token) {
          token.checkForExpiration();
          if (token.isLeaseHeldByCurrentThread()) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine(this + ", name: " + name + " - " + 
                                  "lock() is reentrant: " + token);
            }
            reentrant = true;
            if (reentrant && disallowReentrant) {
              throw new IllegalStateException(LocalizedStrings.DLockService_0_ATTEMPTED_TO_REENTER_NONREENTRANT_LOCK_1.toLocalizedString(new Object[] {Thread.currentThread(), token}));
            }
            recursionBefore = token.getRecursion();
            leaseExpireTime = token.getLeaseExpireTime(); // moved here from processor null-check under gotLock
            lockId = token.getLeaseId(); // keep lockId
            if (lockId < 0) {
              // loop back around due to expiration
              continue;
            }
          } // isLeaseHeldByCurrentThread
        } // token sync
        
        LockGrantorId theLockGrantorId = getLockGrantorId();
        
        if (reentrant) {
          Assert.assertTrue(lockId > -1, "Reentrant lock must have lockId > -1");
          //lockId = token.getLockId(); // keep lockId
        }
        else {
          // this thread is not current owner...
          lockId = -1; // reset lockId back to -1
        }
        
        DLockRequestProcessor processor = null;
        
        // if reentrant w/ infinite lease TODO: remove false to restore this...
        if (false && reentrant && leaseTimeMillis == Long.MAX_VALUE) {
          // Optimization:
          // thread is reentering lock and lease time is infinite so no
          // need to trouble the poor grantor
          gotLock = true;
          // check for race condition...
          Assert.assertTrue(token.isLeaseHeldByCurrentThread());
        }
        
        // non-reentrant or reentrant w/ non-infinite lease
        else {
          processor = createRequestProcessor(theLockGrantorId, name,
                threadId, startTime, requestLeaseTime, requestWaitTime,
                reentrant, tryLock);
          if (reentrant) {
            // check for race condition... reentrant expired already...
            // related to bug 32765, but client-side... see bug 33402
            synchronized (token) {
              if (!token.isLeaseHeldByCurrentThread()) {
                reentrant = false;
                recursionBefore = -1;
                token.checkForExpiration();
              }
            }
          }
          else {
            // set lockId since this is the first granting (non-reentrant)
            lockId = processor.getProcessorId();
          }
          
          try {
            safeExit = false;
            gotLock = processor.requestLock(interruptible, lockId);
          }
          catch (InterruptedException e) { // LOST INTERRUPT
            if (interruptible) {
              // TODO: BUG 37158: this can cause a stuck lock
              throw e;
            }
            else {
              interrupted = true;
              Assert.assertTrue(false, "Non-interruptible lock is trying to throw InterruptedException");
            }
          }
          if (getLogWriter().fineEnabled() && !gotLock) {
            getLogWriter().fine("Grantor " + theLockGrantorId + 
                " replied " + processor.getResponseCodeString());
          }
        } // else: non-reentrant or reentrant w/ non-infinite lease
        
          
        if (gotLock) {
//          if (processor != null) (cannot be null) 
          { // TODO: can be null after restoring above optimization
            // non-reentrant lock needs to getLeaseExpireTime
            leaseExpireTime = processor.getLeaseExpireTime();
          }
          int recursion = recursionBefore + 1;
          
          boolean granted = false;
          boolean needToReleaseOrphanedGrant = false;
          
          Assert.assertHoldsLock(this.destroyLock,false);
          synchronized (this.lockGrantorIdLock) {
            if (!checkLockGrantorId(theLockGrantorId)) {
              safeExit = true;
              // race: grantor changed
              if (getLogWriter().fineEnabled()) {
                getLogWriter().fine("Cannot honor grant from " + 
                    theLockGrantorId + " because " + this.lockGrantorId + 
                    " is now the grantor.");
              }
              continue;
            }
            
            else if (isDestroyed()) {
              // race: dls was destroyed
              if (getLogWriter().fineEnabled()) {
                getLogWriter().fine("Cannot honor grant from " +
                    theLockGrantorId + 
                    " because this lock service has been destroyed.");
              }
              needToReleaseOrphanedGrant = true;
            }
            
            else {
              safeExit = true;
              synchronized (this.tokens) {
                checkDestroyed();
                Assert.assertTrue(token == basicGetToken(name));
                RemoteThread rThread = new RemoteThread(
                    getDistributionManager().getId(), threadId);
                granted = token.grantLock(
                    leaseExpireTime, lockId, recursion, rThread);
              } // tokens sync
            }
          }
              
          if (needToReleaseOrphanedGrant /* && processor != null*/) {
            processor.getResponse().releaseOrphanedGrant(this.dm);
            safeExit = true;
            continue;
          }
          
          if (!granted) {
            Assert.assertTrue(granted, 
                "Failed to perform client-side granting on " + token +
                " which was granted by " + theLockGrantorId);
          }
          
          // make sure token is THE instance in the map to avoid race with
          // freeResources... ok to overwrite a newer instance too since only
          // one thread will own the lock at a time
//          synchronized (tokens) { // TODO: verify if this is needed
//            synchronized (token) {
//              if (tokens.put(name, token) == null) {
//                getStats().incTokens(1);
//              }
//            }
//          }
          
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine(this + ", name: " + name + " - " + 
                "granted lock: " + token);
          }
          keepTrying = false;
        } // gotLock is true
        
        // grantor replied destroyed (getLock is false)
        else if (processor.repliedDestroyed()) {
          safeExit = true;
          checkDestroyed();
          // should have thrown LockServiceDestroyedException
          Assert.assertTrue(isDestroyed(),
              "Grantor reports service " + this + " is destroyed: " + name);
        } // grantor replied destroyed
        
        // grantor replied NOT_GRANTOR or departed (getLock is false)
        else if (processor.repliedNotGrantor() || processor.hadNoResponse()) {
          safeExit = true;
          notLockGrantorId(theLockGrantorId, true);
          // keepTrying is still true... loop back around
        } // grantor replied NOT_GRANTOR or departed
        
        // grantor replied NOT_HOLDER for reentrant lock (getLock is false)
        else if (processor.repliedNotHolder()) {
          safeExit = true;
          if (DEBUG_DISALLOW_NOT_HOLDER) {
            String s = LocalizedStrings.DLockService_DEBUG_GRANTOR_REPORTS_NOT_HOLDER_FOR_0.toLocalizedString(token);
            InternalGemFireError e = new InternalGemFireError(s);
            getLogWriter().error(LocalizedStrings.DLockService_DEBUG_GRANTOR_REPORTS_NOT_HOLDER_FOR_0, token, e);
            throw e;
          }
          
          // fix part of bug 32765 - reentrant/expiration problem
          // probably expired... try to get non-reentrant lock
          reentrant = false;
          recursionBefore = -1;
          synchronized(token) {
            token.checkForExpiration();
            if (token.isLeaseHeldByCurrentThread()) {
              // THIS SHOULDN'T HAPPEN -- some sort of weird consistency
              // problem. Do what the grantor says and release the lock...
              getLogWriter().warning(
                LocalizedStrings.DLockService_GRANTOR_REPORTS_REENTRANT_LOCK_NOT_HELD_0,
                token);
              
              // Attempt at fault tolerance: We thought we owned it, but we 
              // don't; let's release it.  Removes hot loop in bug 37276, 
              // but does not address underlying consistency failure.
              RemoteThread rThread = new RemoteThread(
                  getDistributionManager().getId(), threadId);
              token.releaseLock(lockId, rThread, false);
            }
          } // token sync
        } // grantor replied NOT_HOLDER for reentrant lock
        
        // TODO: figure out when this else case can actually happen...
        else {
          safeExit = true;
          // either dlock service is suspended or tryLock failed
          // fixed the math here... bug 32765
          if (waitLimit > token.getCurrentTime() + 20) {
            sleep(20, interruptible);
          }
          keepTrying = waitLimit > token.getCurrentTime();
        }
        
      } // while (keepTrying)
    } // try-block for end stats, token cleanup, and interrupt check
    
    // finally-block for end stats, token cleanup, and interrupt check
    finally {
      getStats().endLockWait(statStart, gotLock);
      
      // cleanup token if failed to get lock
      if (!gotLock) {
        synchronized (token) {
          token.decUsage();
        }
        freeResources(token.getName());
      }
      
      // reset the interrupt state
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      
      // throw InterruptedException only if failed to get lock and interrupted
      if (!gotLock && interruptible && Thread.interrupted()) {
        throw new InterruptedException();
      }
      blockedOn.set(null);
    }

    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine(this + ", name: " + name + " - " + 
          "exiting lock() returning " + gotLock);
    }
    abnormalExit = false;
    return gotLock;
    } // try-block for abnormalExit and safeExit
    
    // finally-block for abnormalExit and safeExit
    finally {
      if (abnormalExit && getLogWriter().fineEnabled()) {
        getLogWriter().fine(this + ", name: " + name + " - " + 
            "exiting lock() without returning value");
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (DEBUG_ENFORCE_SAFE_EXIT) {
        Assert.assertTrue(safeExit);
      }
    }
  }

  /**
   * Allow locking to resume. 
   */
  @Override
  public void resumeLocking() {
    checkDestroyed();
    try {
      // need to resumeLocking before unlocking to avoid deadlock with
      // other thread attempting to suspendLocking
      unlock(SUSPEND_LOCKING_TOKEN);
    }
    catch (IllegalStateException e) {
      checkDestroyed();
      throw e;
    }
  }

  /**
   * Suspends granting of locks for this instance of DLockService.  If
   * distribute is true, sends suspendLocking to all other members that
   * have created this service.  Blocks until all outstanding locks have
   * been released (excluding those held by the initial calling thread).
   *
   * @param waitTimeMillis -1 means "wait forever", >=0 = milliseconds to wait
   *
   * @return true if locking is suspended and all locks have been
   * released.  Otherwise, resumeLocking is invoked and false is returned.
   */
  @Override
  public boolean suspendLocking(final long waitTimeMillis) {
    
    long startTime = System.currentTimeMillis();
    long requestWaitTime = waitTimeMillis;
    boolean interrupted = false;

    try {
      do {
        checkDestroyed();
        try {
          return suspendLockingInterruptibly(requestWaitTime, false);
        } catch (InterruptedException ex) {
          interrupted = true;
          long millisPassed = System.currentTimeMillis() - startTime;
          if (requestWaitTime >= 0) {
            requestWaitTime = Math.max(0, requestWaitTime - millisPassed);
          }
        }
      } while (requestWaitTime != 0);
      
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    
    return false;
  }
  
  @Override
  public boolean suspendLockingInterruptibly(long waitTimeMillis)
  throws InterruptedException {
    return suspendLockingInterruptibly(waitTimeMillis, true);
  }
  
  public boolean suspendLockingInterruptibly(long waitTimeMillis,
                                             boolean interruptible)
  throws InterruptedException {
    checkDestroyed();
    
    boolean wasInterrupted = false;
    if (Thread.interrupted()) {
      if (interruptible) {
        throw new InterruptedException();
      }
      else {
        wasInterrupted = true;
      }
    }
    
    try {

    if (isLockingSuspendedByCurrentThread()) {
      throw new IllegalStateException(LocalizedStrings.DLockService_CURRENT_THREAD_HAS_ALREADY_LOCKED_ENTIRE_SERVICE.toLocalizedString());
    }
    
    // have to use tryLock to avoid deadlock with other members that are
    // simultaneously attempting to suspend locking
    boolean tryLock = false; // go with false to queue up suspend lock requests
    // when tryLock is false, we get deadlock:
    //   thread 1 is this thread
    //   thread 2 is processing a SuspendMessage... goes thru 
    //     suspendLocking with distribute=false and gets stuck in
    //     waitForGrantorCallsInProgress
    
    SuspendLockingToken suspendToken = SUSPEND_LOCKING_TOKEN;
    
    boolean gotToken = false;
    boolean keepTrying = true;
    
    long startTime = System.currentTimeMillis();
    long waitLimit = startTime + waitTimeMillis;
    if (waitLimit < 0) waitLimit = Long.MAX_VALUE;
    
//    try {
      // we're now using a tryLock, but we need to keep trying until wait time
      // is used up or we're interrupted...
      while (!gotToken && keepTrying) {
        gotToken = lockInterruptibly(
          suspendToken, waitTimeMillis, -1, tryLock, interruptible, false);
        keepTrying = !gotToken && waitLimit > System.currentTimeMillis();
      }
      return gotToken;
//    }
//    finally {
//      synchronized(this.lockingSuspendedMonitor) {
//        this.lockingSuspendedMonitor.notifyAll();
//      }
//    }
    
    }
    finally {
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  @Override
  public void unlock(Object name) 
  throws LockNotHeldException, LeaseExpiredException {
    if (this.ds.isDisconnectListenerThread()) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine(this + ", name: " + name + " - " + 
            "disconnect listener thread is exiting unlock()");
      }
      return;
    }
    
    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine(this + ", name: " + name + " - " + 
          "entering unlock()");
    }
    
    long statStart = getStats().startLockRelease();
    
    boolean hadRecursion = false;
    boolean unlocked = false;
    int lockId = -1;
    DLockToken token = null;
    RemoteThread rThread = null;
    
    try {
      
      synchronized (this.tokens) {
        checkDestroyed();
        token = basicGetToken(name);
        if (token == null) {
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine(
                this + ", [unlock] no token found for: " + name);
          }
          throw new LockNotHeldException(LocalizedStrings.DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK.toLocalizedString(new Object[] {this, name}));
        }
        
        synchronized (token) {
          token.checkForExpiration();
          rThread = token.getLesseeThread();
          if (!token.isLeaseHeldByCurrentOrRemoteThread(rThread)) {
            token.throwIfCurrentThreadHadExpiredLease();
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine(
                this + ", [unlock] " + token + " not leased by this thread.");
            }
            throw new LockNotHeldException(LocalizedStrings.DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK_2.toLocalizedString(new Object[] {this, name, token}));
          }
          // if recursion > 0 then token will still be locked after calling release
          hadRecursion = token.getRecursion() > 0;
          lockId = token.getLeaseId();
          Assert.assertTrue(lockId > -1);
          if (hadRecursion) {
            unlocked = token.releaseLock(lockId, rThread);
          }
          else {
            token.setIgnoreForRecovery(true);
          }
        } // token sync
      } // tokens map sync
  
      if (!hadRecursion) {
        boolean lockBatch = false;
        boolean released = false;
        
        while (!released) {
          checkDestroyed();
          LockGrantorId theLockGrantorId = getLockGrantorId();
          try {
            released = callReleaseProcessor(theLockGrantorId
                .getLockGrantorMember(), name, lockBatch, lockId);
            synchronized (this.lockGrantorIdLock) {
              token.releaseLock(lockId, rThread);
              unlocked = true;
            }
          }
          catch (LockGrantorDestroyedException e) { // part of fix for bug 35239
            // loop back around to get next lock grantor
          }
          catch (LockServiceDestroyedException e) { // part of fix for bug 35239
            // done... NonGrantorDestroyedMessage will release locks for us
            released = true;
          }
          finally {
            if (!released) {
              notLockGrantorId(theLockGrantorId, true);
            }
          }
        } // while !released
      } // !hadRecursion
      
    } // try
    finally {
      try {
        if (!hadRecursion && lockId > -1 && token != null) {
          decActiveLocks();
          if (!unlocked) {
//            // token is still held if grantor was remote, so now we unlock...
//            checkDestroyed(); // part of fix for bug 35239
//            // this release is ok even if we have become the lock grantor
//            //   because the grantor will have no state for this lock
            token.releaseLock(lockId, rThread);
          }
        }
      }
      finally {
        getStats().endLockRelease(statStart);
        if (this.automateFreeResources) {
          freeResources(name);
        }
        if (getLogWriter().fineEnabled()) {
          getLogWriter().fine(this + ", name: " + name + " - " + 
              "exiting unlock()");
        }
      }
    }
  }

  /**
   * Query the grantor for current leasing information of a lock. Returns
   * the current lease info.
   * 
   * @param name the named lock to get lease information for
   * @return snapshot of the remote lock information
   * @throws LockServiceDestroyedException if local instance of lock service
   * has been destroyed
   */
  public DLockRemoteToken queryLock(final Object name) {
    //long statStart = getStats().startLockRelease();
    try {
      
      DLockQueryReplyMessage queryReply = null;
      while (queryReply == null || queryReply.repliedNotGrantor()) {
        checkDestroyed();
        // TODO: consider using peekLockGrantor instead...
        LockGrantorId theLockGrantorId = getLockGrantorId();
        try {
          queryReply = DLockQueryProcessor.query(
              theLockGrantorId.getLockGrantorMember(), 
              this.serviceName, 
              name, 
              false /* lockBatch */, 
              this.dm);
        }
        catch (LockGrantorDestroyedException e) {
          // loop back around to get next lock grantor
        }
        finally {
          if (queryReply != null && queryReply.repliedNotGrantor()) {
            notLockGrantorId(theLockGrantorId, true);
          }
        }
      } // while querying
      
      return DLockRemoteToken.create(
          name, queryReply.getLesseeThread(),
          queryReply.getLeaseId(), queryReply.getLeaseExpireTime());
      
    } // try
    finally {
      // getStats().endLockRelease(statStart);
    }
  }
  
  // -------------------------------------------------------------------------
  //   Creation methods
  // -------------------------------------------------------------------------
  
  /**
   * Factory method for creating a new instance of <code>DLockService</code>.
   * This ensures that adding the {@link #disconnectListener} is done while
   * synchronized on the fully constructed instance.
   * <p>
   * Caller must be synchronized on {@link 
   * DLockService#services}.
   * 
   * @see com.gemstone.gemfire.distributed.DistributedLockService#create(String, DistributedSystem)
   */
  static DLockService basicCreate(String serviceName, 
                                  InternalDistributedSystem ds,
                                  boolean isDistributed,
                                  boolean destroyOnDisconnect,
                                  boolean automateFreeResources)
  throws IllegalArgumentException {
    Assert.assertHoldsLock(services,true);
    
    final LogWriterI18n log = ds.getLogWriter().convertToLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine("About to create DistributedLockService <" + serviceName + ">");
    }
    
    DLockService svc = new DLockService(
        serviceName, ds, isDistributed, destroyOnDisconnect, 
            automateFreeResources);
    svc.init(log);
    return svc;
  }

  /** initialize this DLockService object */
  protected boolean init(final LogWriterI18n log) {
    boolean success = false;
    try {
      services.put(this.serviceName, this);
      getStats().incServices(1);
      this.ds.addDisconnectListener(disconnectListener);
      success = true;
      if (log.fineEnabled()) {
        log.fine("Created DistributedLockService <" + this.serviceName + ">");
      }
    } finally {
      if (!success) {
        services.remove(this.serviceName);
        getStats().incServices(-1);
      }
    }

    /** Added For M&M **/
    ds.handleResourceEvent(ResourceEvent.LOCKSERVICE_CREATE, this);
    
    return success;
  }

  // -------------------------------------------------------------------------
  //   Constructors
  // -------------------------------------------------------------------------

  /**
   * To create an instance, use 
   * DistributedLockService.create(Object, DistributedSystem) or
   * DLockService.create(Object, DistributedSystem, DistributionAdvisor)
   */
  protected DLockService(String serviceName, 
                         DistributedSystem ds,
                         boolean isDistributed,
                         boolean destroyOnDisconnect,
                         boolean automateFreeResources) {
    super();
    this.serialNumber = createSerialNumber();
    this.serviceName = serviceName;
    this.ds = (InternalDistributedSystem) ds;
    this.dm = this.ds.getDistributionManager();    
    this.stopper = new DLockStopper(this.dm, this);
    this.logWriter = new DLockLogWriter(
        serviceName, getSerialNumber(), this.dm.getLoggerI18n());
    this.isDistributed = isDistributed;
    this.destroyOnDisconnect = destroyOnDisconnect;
    this.automateFreeResources = 
        automateFreeResources || AUTOMATE_FREE_RESOURCES;
  }
  
  // -------------------------------------------------------------------------
  //   java.lang.Object methods
  // -------------------------------------------------------------------------
  
  @Override
  public String toString() {
    StringBuffer buffer = new StringBuffer(128);
    buffer.append('<')
      .append("DLockService")
      .append("@")
      .append(Integer.toHexString(System.identityHashCode(this)))
      .append(" named ")
      .append(this.serviceName)
      .append(" destroyed=")
      .append(this.destroyed)
      .append(" grantorId=")
      .append(this.lockGrantorId)
      .append(" grantor=")
      .append(this.grantor)
      .append('>');
    return buffer.toString();
  }
  
  // -------------------------------------------------------------------------
  //   Public instance methods
  // -------------------------------------------------------------------------

  public final DistributedLockStats getStats() {
    return this.dlockStats;
  }

  public void releaseTryLocks(DLockBatchId batchId, 
                              boolean onlyIfSameGrantor) {
    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine("[DLockService.releaseTryLocks] enter: " + batchId);
    }

    long statStart = getStats().startLockRelease();
    
    try {
      boolean lockBatch = true;
      boolean released = false;
      while (!released) {
        checkDestroyed();
        LockGrantorId theLockGrantorId = null;
        
        if (onlyIfSameGrantor) { // this was a fix for bug #38763, from r19555
          theLockGrantorId = batchId.getLockGrantorId();
          synchronized (this.lockGrantorIdLock) {
            if (!checkLockGrantorId(theLockGrantorId)) {
              // the grantor is different so break and skip DLockReleaseProcessor
              break;
            }
          }
        }
        else {
          theLockGrantorId = getLockGrantorId();
        }

        released = callReleaseProcessor(
            theLockGrantorId.getLockGrantorMember(), batchId, lockBatch, -1);
        if (!released) {
          final boolean waitForGrantor = onlyIfSameGrantor; // fix for bug #43708
          notLockGrantorId(theLockGrantorId, waitForGrantor);
        }
      }
    }
    finally {
      decActiveLocks();
      getStats().endLockRelease(statStart);
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[DLockService.releaseTryLocks] exit: " + batchId);
      }
    }
  }
  
  public boolean acquireTryLocks(final DLockBatch dlockBatch,
                                 final long waitTimeMillis, 
                                 final long leaseTimeMillis,
                                 final Object[] keyIfFailed)
  throws InterruptedException {
    checkDestroyed();
    if (Thread.interrupted()) throw new InterruptedException();
    if (keyIfFailed.length < 1) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_KEYIFFAILED_MUST_HAVE_A_LENGTH_OF_ONE_OR_GREATER.toLocalizedString());
    }
    
    long startTime = getLockTimeStamp(dm);

    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine("[acquireTryLocks] acquiring " + dlockBatch);
    }

    long requestWaitTime = waitTimeMillis;
    long requestLeaseTime = leaseTimeMillis;
    
    // -1 means "lease forever".  Long.MAX_VALUE is pretty close.
    if (requestLeaseTime == -1) requestLeaseTime = Long.MAX_VALUE;
    
    // -1 means "wait forever".  Long.MAX_VALUE is pretty close.
    if (requestWaitTime == -1) requestWaitTime = Long.MAX_VALUE;
    long waitLimit = startTime + requestWaitTime;
    if (waitLimit < 0) waitLimit = Long.MAX_VALUE;
      
    long statStart = getStats().startLockWait();
    boolean gotLocks = false;
      
    try {
      ThreadRequestState requestState = 
          (ThreadRequestState) this.threadRequestState.get();
      if (requestState == null) {
        requestState = new ThreadRequestState(incThreadSequence(), false);
        this.threadRequestState.set(requestState);
      }
      else {
        requestState.interruptible = false;
      }
      final int threadId = requestState.threadId;
    
      boolean keepTrying = true;
      incActiveLocks();
        
      while (keepTrying) {
        checkDestroyed();
        LockGrantorId theLockGrantorId = getLockGrantorId();

        boolean tryLock = true;
        boolean reentrant = false;
        DLockRequestProcessor processor = createRequestProcessor(
            theLockGrantorId, dlockBatch, threadId, startTime,
            requestLeaseTime, requestWaitTime, reentrant, tryLock);
        boolean interruptible = true;
        int lockId = processor.getProcessorId();
        gotLocks = processor.requestLock(interruptible, lockId);
        if (gotLocks) {
          dlockBatch.grantedBy(theLockGrantorId);
        }
        else if (processor.repliedDestroyed()) {
          checkDestroyed();
          // should have thrown LockServiceDestroyedException
          Assert.assertTrue(isDestroyed(),
              "Grantor reports service " + this + " is destroyed");
        }
        else if (processor.repliedNotGrantor() || processor.hadNoResponse()) {
          notLockGrantorId(theLockGrantorId, true);
        }
        else {
          keyIfFailed[0] = processor.getKeyIfFailed();
          if (keyIfFailed[0] == null) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine("[acquireTryLocks] " +
                "lock request failed but provided no conflict key" +
                "; responseCode=" + processor.getResponseCodeString()
                );
            }
          }
          else {
            break;
          }
        }
        
        long timeLeft = requestWaitTime;
        if (requestWaitTime < Long.MAX_VALUE) {
          // prevent txLock from performing next line...
          timeLeft = waitLimit - getLockTimeStamp(this.dm);
        }
        keepTrying = !gotLocks && timeLeft > 0;
        if (keepTrying && timeLeft > 10) {
          // didn't receive msg or processor timed out... sleep briefly
          try {
            Thread.sleep(10);
          }
          catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
          }
        }
      }
        
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[acquireTryLocks] " +
            (gotLocks ? "acquired" : "failed to acquire") +
            " locks for " + dlockBatch);
      }
    }
//    catch (Error e) {
//      if (getLogWriter().fineEnabled()) {
//        getLogWriter().fine("[acquireTryLocks] caught Error", e);
//      }
//      gotLocks = false;
//    }
//    catch (RuntimeException e) {
//      if (getLogWriter().fineEnabled()) {
//        getLogWriter().fine("[acquireTryLocks] caught RuntimeException", e);
//      }
//      gotLocks = false;
//    }
    finally {
      getStats().endLockWait(statStart, gotLocks);
    }
    return gotLocks;
  }
  
  /**
   * Returns copy of the tokens map. Synchronizes on token map.
   * <p>
   * Called by {@link 
   * com.gemstone.gemfire.internal.admin.remote.FetchDistLockInfoResponse}.
   * 
   * @return copy of the tokens map
   */
  public Map<Object, DLockToken> snapshotService() {
    synchronized (this.tokens) {
      return new HashMap(this.tokens);
    }
  }
  
  /**
   * Used for instrumenting blocked threads
   */
  public UnsafeThreadLocal<Object> getBlockedOn() {
    return blockedOn;
  }
  
  /** Returns true if the lock service is distributed; false if local only */
  public boolean isDistributed() {
    return this.isDistributed;
  }

  public final void setDLockLessorDepartureHandler(
      DLockLessorDepartureHandler handler) {
    this.lessorDepartureHandler = handler;
  }

  public final DLockLessorDepartureHandler getDLockLessorDepartureHandler() {
    return this.lessorDepartureHandler;
  }

  /** The name of this service */
  public final String getName() {
    return this.serviceName;
  }
  
  public final DM getDistributionManager() {
    return this.dm;
  }

  public final LogWriterI18n getLogWriter() {
    return this.logWriter;
  }
  
  public void setDLockRecoverGrantorMessageProcessor(
  DLockRecoverGrantorProcessor.MessageProcessor recoverGrantorProcessor) {
    this.recoverGrantorProcessor = recoverGrantorProcessor;
  }
  
  public DLockRecoverGrantorProcessor.MessageProcessor 
  getDLockRecoverGrantorMessageProcessor() {
    return this.recoverGrantorProcessor;
  }

  /** 
   * Returns true if any tokens in this service are currently held. 
   * Synchronizes on tokens map and on each token to check the lease.
   * 
   * @return true if any tokens in this service are currently held
   */
  boolean hasHeldLocks() {  
    synchronized (this.tokens) {
      for (Iterator iter = this.tokens.values().iterator(); iter.hasNext();) {
        DLockToken token = (DLockToken) iter.next();
        if (token.isLeaseHeld()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * @see com.gemstone.gemfire.distributed.DistributedLockService#destroy(String)
   */
  public static void destroyServiceNamed(String serviceName)
  throws IllegalArgumentException {
    DLockService svc = null;
    synchronized (services) {
      svc = services.get(serviceName);
    }
    if (svc == null) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_NOT_CREATED.toLocalizedString(serviceName));
    } else {
      svc.destroyAndRemove();
    }
  }
  
  /** Destroys all lock services in this VM. Used in test tearDown code. */
  public static void destroyAll() {
    Collection svcs = Collections.EMPTY_SET;
    synchronized (services) {
      svcs = new HashSet(services.values());
    }
    for (Iterator iter = svcs.iterator(); iter.hasNext();) {
      DLockService svc = 
        (DLockService) iter.next();
      try {
        svc.destroyAndRemove();
      }
      catch (CancelException e) {
        svc.getLogWriter().fine(
            "destroyAndRemove of " + svc + " terminated due to cancellation: " + e);
      }
      catch (RuntimeException e) {
        throw e;
      }
//      catch (Throwable t) {
//        Error err;
//        if (t instanceof Error && SystemFailure.isJVMFailureError(
//            err = (Error)t)) {
//          SystemFailure.initiateFailure(err);
//          // If this ever returns, rethrow the error. We're poisoned
//          // now, so don't let this thread continue.
//          throw err;
//        }
//        // Whenever you catch Error or Throwable, you must also
//        // check for fatal JVM error (see above).  However, there is
//        // _still_ a possibility that you are dealing with a cascading
//        // error condition, so you also need to check to see if the JVM
//        // is still usable:
//        SystemFailure.checkFailure();
//        try {
//          svc.getLogWriter().warning(
//            LocalizedStrings.DLockService_DESTROYANDREMOVE_OF_0_MAY_HAVE_FAILED,
//            svc,t);
//        }
//        catch (Throwable t2) {
//          Error err;
//          if (t2 instanceof Error && SystemFailure.isJVMFailureError(
//              err = (Error)t2)) {
//            SystemFailure.initiateFailure(err);
//            // If this ever returns, rethrow the error. We're poisoned
//            // now, so don't let this thread continue.
//            throw err;
//          }
//          // Whenever you catch Error or Throwable, you must also
//          // check for fatal JVM error (see above).  However, there is
//          // _still_ a possibility that you are dealing with a cascading
//          // error condition, so you also need to check to see if the JVM
//          // is still usable:
//          SystemFailure.checkFailure();
//          t.printStackTrace();
//          t2.printStackTrace();
//        }
//      }
    }
  }
    
  /**
   * Destroys an existing service and removes it from the map
   * @since 3.5
   */
  public void destroyAndRemove() {
    // isLockGrantor determines if we need to tell elder of destroy
    boolean isCurrentlyLockGrantor = false;
    boolean isMakingLockGrantor = false;
  
    // maybeHasActiveLocks determines if we need to tell grantor of destroy
    boolean maybeHasActiveLocks = false;
  
    synchronized (creationLock) {
      try {
        synchronized (services) {
          try {
            if (isDestroyed()) return;
            setDestroyingThread();
            synchronized (this.lockGrantorIdLock) { // force ordering in lock request
              synchronized (this.destroyLock) {
                this.destroyed = true;
                maybeHasActiveLocks = this.activeLocks > 0;
              }
              isCurrentlyLockGrantor = this.isCurrentlyLockGrantor();
              isMakingLockGrantor = this.isMakingLockGrantor();
            }  
          }
          finally {
            if (isCurrentThreadDoingDestroy()) {
              removeLockService(this);
            }
          }
        } // services sync
      }
      catch (CancelException e) {
        // don't report to caller
      }
      finally {
        if (isCurrentThreadDoingDestroy()) {
          try {
            this.basicDestroy(isCurrentlyLockGrantor, isMakingLockGrantor, maybeHasActiveLocks);
          }
          catch (CancelException e) {
            // don't propagate
          }
          finally {
            clearDestroyingThread();
          }
        }
        postDestroyAction();
      }
    } // creationLock sync
  }
  
  /**
   * Unlock all locks currently held by this process, and mark it as
   * destroyed. Returns true if caller performed actual destroy. Returns false
   * if this lock service has already been destroyed.
   * <p>
   * {@link #services} is held for the entire operation. {@link #destroyLock}
   * is held only while setting {@link #destroyed} to true and just prior to
   * performing any real work.
   * <p>
   * Caller must be synchronized on {@link 
   * DLockService#services};
   */
  private void basicDestroy(boolean isCurrentlyLockGrantor, 
                            boolean isMakingLockGrantor,
                            boolean maybeHasActiveLocks) {
    Assert.assertHoldsLock(services,false);
    //synchronized (this.serviceLock) {
      if (getLogWriter().fineEnabled()) {
        getLogWriter().fine("[DLockService.basicDestroy] Destroying " + this + 
            ", isCurrentlyLockGrantor=" + isCurrentlyLockGrantor +
            ", isMakingLockGrantor=" + isMakingLockGrantor);
      }
      
      // if hasActiveLocks, tell grantor we're destroying...
      if (!isCurrentlyLockGrantor && maybeHasActiveLocks && 
          !this.ds.isDisconnectListenerThread()) {
        boolean retry;
        int nonGrantorDestroyLoopCount = 0;
        do {
          retry = false;
          LockGrantorId theLockGrantorId = peekLockGrantorId();
          
          if (theLockGrantorId != null && !theLockGrantorId.isLocal(getSerialNumber())) {
            if (!NonGrantorDestroyedProcessor.send(this.serviceName, theLockGrantorId, dm)) {
              // grantor responded NOT_GRANTOR
              notLockGrantorId(theLockGrantorId, true); // nulls out grantor to force call to elder
              retry = true;
            }
          }
          
          if (DEBUG_NONGRANTOR_DESTROY_LOOP) {
            nonGrantorDestroyLoopCount++;
            if (nonGrantorDestroyLoopCount >= DEBUG_NONGRANTOR_DESTROY_LOOP_COUNT) {
              getLogWriter().severe(
                  LocalizedStrings.DLockService_FAILED_TO_NOTIFY_GRANTOR_OF_DESTRUCTION_WITHIN_0_ATTEMPTS,
                  Integer.valueOf(DEBUG_NONGRANTOR_DESTROY_LOOP_COUNT));
              Assert.assertTrue(false, 
                  LocalizedStrings.DLockService_FAILED_TO_NOTIFY_GRANTOR_OF_DESTRUCTION_WITHIN_0_ATTEMPTS
                  .toLocalizedString(
                      new Object[] {Integer.valueOf(DEBUG_NONGRANTOR_DESTROY_LOOP_COUNT)}));
            }
          }
          
        } while (retry);
      }
    
      // KIRK: probably don't need to do the following if isMakingLockGrantor
      if (isCurrentlyLockGrantor || isMakingLockGrantor) {
        // If forcedDisconnect is in progress, the membership view will not
        // change and no-one else can contact this member, so don't wait for a grantor
        if (this.ds.getCancelCriterion().cancelInProgress() != null) {
          // KIRK: probably don't need to waitForGrantor
          try {
            DLockGrantor.waitForGrantor(this);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch(DistributedSystemDisconnectedException e) {
            getLogWriter().fine("No longer waiting for grantor because of disconnect.", e);
          }
        }
        nullLockGrantorId(this.lockGrantorId);
      }
    //}
  }

  protected void postDestroyAction() {
    /** Added for M&M **/
    ds.handleResourceEvent(ResourceEvent.LOCKSERVICE_REMOVE, this);
    
  }

  // -------------------------------------------------------------------------
  //   Package instance methods
  // -------------------------------------------------------------------------

  boolean destroyOnDisconnect() {
    return this.destroyOnDisconnect;
  }
  
  /** 
   * Called by grantor recovery to return set of locks held by this process.
   * Synchronizes on lockGrantorIdLock, tokens map, and each lock token.
   * 
   * @param newlockGrantorId the newly recovering grantor
   */
  Set getLockTokensForRecovery(LockGrantorId newlockGrantorId) {
    Set heldLockSet = Collections.EMPTY_SET;
    
    LockGrantorId currentLockGrantorId = null;
    synchronized (this.lockGrantorIdLock) {
      if (isDestroyed()) {
        return heldLockSet;
      }
      
      currentLockGrantorId = this.lockGrantorId;
    }
    
    // destroy local grantor if currentLockGrantorId is local
    // and grantorVersion is greater than currentGrantorVersion 
    if (currentLockGrantorId != null &&
        currentLockGrantorId.hasLockGrantorVersion() &&
        newlockGrantorId.isNewerThan(currentLockGrantorId)) {
      nullLockGrantorId(currentLockGrantorId);
    }
      
    synchronized (this.lockGrantorIdLock) {
      synchronized (this.tokens) {
        // build up set of currently held locks
        for (Iterator iter = this.tokens.values().iterator(); iter.hasNext();) {
          DLockToken token = (DLockToken) iter.next();
          synchronized (token) {
            if (token.isLeaseHeld()) {
              
              // skip over token if ignoreForRecovery is true
              if (token.ignoreForRecovery()) {
                // unlock of token must be in progress... ignore for recovery
                if (getLogWriter().fineEnabled()) {
                  getLogWriter().fine("getLockTokensForRecovery is skipping " + token);
                }
              }
              
              // add token to heldLockSet
              else {
                if (heldLockSet == Collections.EMPTY_SET) {
                  heldLockSet = new HashSet();
                }
                heldLockSet.add(DLockRemoteToken.createFromDLockToken(token));
              }
            } // isLeaseHeld
          } // token sync
        } // tokens iter
      } // tokens sync
      
      return heldLockSet;
    }
  }
  
  /**
   * Returns the named lock token or null if it doesn't exist. Synchronizes on 
   * tokens map. 
   * 
   * @return the named lock token or null if it doesn't exist
   */
  public DLockToken getToken(Object name) {
    synchronized (this.tokens) {
      return this.tokens.get(name);
    }
  }
  
  /**
   * Returns the named lock token or null if it doesn't exist. Caller must 
   * synchronize on tokens map. 
   * 
   * @return the named lock token or null if it doesn't exist
   */
  private DLockToken basicGetToken(Object name) {
    return this.tokens.get(name);
  }
  
  /**
   * Returns an unmodifiable collection backed by the values of the DLockToken 
   * map for testing purposes only. Synchronizes on tokens map.
   * 
   * @return an unmodifiable collection of the tokens map values
   */
  public Collection getTokens() {
    synchronized (this.tokens) {
      return Collections.unmodifiableCollection(this.tokens.values());
    }
  }
  
  /**
   * Returns an existing or creates a new DLockToken. Synchronizes on tokens
   * map and the lock token. 
   *
   * @param name the name of the lock
   * @return an existing or new instantiated lock token
   */
  DLockToken getOrCreateToken(Object name) {
    synchronized (this.tokens) {
      checkDestroyed(); // check destroy after acquiring tokens map sync
      DLockToken token = this.tokens.get(name);
      boolean createNewToken = token == null;
      if (createNewToken) {
        token = new DLockToken(this.dm, getLogWriter(), name);
      }
      synchronized (token) {
        if (createNewToken) {
          this.tokens.put(name, token);
          if (getLogWriter().fineEnabled()) {
            getLogWriter().fine("Creating " + token + " in " + this);
          }
          getStats().incTokens(1);
        }
        token.incUsage();
      }
      return token;
    }
  }
  
  // -------------------------------------------------------------------------
  //   Private instance methods
  // -------------------------------------------------------------------------
  
  /**
   * Returns number of lock tokens currently leased by this member.
   * Synchronizes on tokens map and each lock token.
   * 
   * @return number of lock tokens currently leased by this member
   */
  private int numLocksHeldInThisVM() {
    int numLocksHeld = 0;
    synchronized (this.tokens) {
      for (Iterator iter = this.tokens.values().iterator(); iter.hasNext();) {
        DLockToken token = (DLockToken)iter.next();
        synchronized(token) {
          if (token.isLeaseHeld()) {
            numLocksHeld++;
          }
        }
      }
    }
    return numLocksHeld;
  }

  /**
   * TEST HOOK: Logs all lock tokens for this service at INFO level. 
   * Synchronizes on tokens map and each lock token.
   * 
   * @param log the LogWriter to use
   */
  protected void dumpService(LogWriterI18n log) {
    synchronized (this.tokens) {
      StringBuilder buffer = new StringBuilder();
      dumpServiceNoSync(buffer, false);
      log.info(LocalizedStrings.ONE_ARG, buffer);
    }
  }

  public void dumpService(StringBuilder buffer, boolean skipBucketLocks) {
    synchronized (this.tokens) {
      dumpServiceNoSync(buffer, skipBucketLocks);
    }
  }

  private void dumpServiceNoSync(StringBuilder buffer, boolean skipBucketLocks) {
    buffer.append("  ").append(this.tokens.size()).append(" tokens, ");
    buffer.append(numLocksHeldInThisVM()).append(" locks held\n");
    for (Iterator iter = this.tokens.entrySet().iterator(); iter.hasNext();) {
      Map.Entry entry = (Map.Entry)iter.next();
      DLockToken token = (DLockToken)entry.getValue();
      if (skipBucketLocks
          && !token.isLeaseHeld()
          && token.getName() instanceof String
          && token.getName().toString()
              .startsWith(PartitionedRegionHelper.BUCKET_REGION_PREFIX)) {
        continue;
      }
      buffer.append("    ").append(entry.getKey()).append(": ");
      buffer.append(token.toString()).append("\n");
    }
  }

  // ----------- new thread state for interruptible and threadId ------------
  private final AtomicInteger threadSequence = new AtomicInteger();
  protected static class ThreadRequestState {
    protected final int threadId;
    protected boolean interruptible;
    ThreadRequestState(int threadId, boolean interruptible) {
      this.threadId = threadId;
      this.interruptible = interruptible;
    }
  }
  private final ThreadLocal threadRequestState = new ThreadLocal();
  
  private final UnsafeThreadLocal<Object> blockedOn = new UnsafeThreadLocal<Object>();
  
  /** Returns true if the calling thread has an active lock request that 
   * is interruptible */
  boolean isInterruptibleLockRequest() {
    // this method is called by grantor for local lock requests in grantor vm
    final ThreadRequestState requestState = 
        (ThreadRequestState) threadRequestState.get();
    if (requestState == null) {
      return false;
    }
    return requestState.interruptible;
  }
  ThreadLocal getThreadRequestState() {
    return threadRequestState;
  }
  protected int incThreadSequence() {
    return this.threadSequence.incrementAndGet();
  }

  // -------------------------------------------------------------------------
  //   System disconnect listener
  // -------------------------------------------------------------------------
  
  /** Destroys all named locking services on disconnect from system */
  protected final static InternalDistributedSystem.DisconnectListener disconnectListener =
    new InternalDistributedSystem.DisconnectListener() {
      @Override
      public String toString() {
        return LocalizedStrings.DLockService_DISCONNECT_LISTENER_FOR_DISTRIBUTEDLOCKSERVICE.toLocalizedString();
      }
      
      public void onDisconnect(final InternalDistributedSystem sys) {
        sys.getLogWriter().fine("Shutting down Distributed Lock Services");
        long start = System.currentTimeMillis();
        try {
          destroyAll();
        }
        finally {
          closeStats();
          long delta = System.currentTimeMillis() - start;
          if (sys.getLogWriter().fineEnabled()) {
            sys.getLogWriter().fine(
                "Distributed Lock Services stopped (took " + delta + " ms)");
          }
        }
      }
    };

  // ----------------------------------------------------------------
    
  private static final DummyDLockStats DUMMY_STATS = new DummyDLockStats();
  
  public static final SuspendLockingToken SUSPEND_LOCKING_TOKEN = new SuspendLockingToken();
  
  // -------------------------------------------------------------------------
  //   Static fields
  // -------------------------------------------------------------------------

  /** Map of all locking services. Key:ServiceName, Value:DLockService */
  protected static final Map<String, DLockService> services = new HashMap<String, DLockService>();
  
  protected static final Object creationLock = new Object();
  
  /** All DLock threads belong to this group */
  static ThreadGroup threadGroup;

  /** DLock statistics; static because multiple dlock instances can exist */
  private static DistributedLockStats stats = DUMMY_STATS;

  // -------------------------------------------------------------------------
  //   Reserved lock service names
  // -------------------------------------------------------------------------
  
  public static final String LTLS = "LTLS";
  public static final String DTLS = "DTLS";
  static final String[] reservedNames = new String[] { LTLS, DTLS };
  
  // -------------------------------------------------------------------------
  //   DLS serial number (uniquely identifies local instance of DLS)
  // -------------------------------------------------------------------------
  
  /**
   * Specifies the starting serial number for the serialNumberSequencer
   */
  public static final int START_SERIAL_NUMBER = Integer.getInteger(
      "gemfire.DistributedLockService.startSerialNumber", 1).intValue();
  
  /**
   * Incrementing serial number used to identify order of DLS creation
   * @see DLockService#getSerialNumber()
   */
  private static final AtomicInteger serialNumberSequencer =
      new AtomicInteger(START_SERIAL_NUMBER);

  /** 
   * Identifies the static order in which this DLS was created in relation 
   * to other DLS or other instances of this DLS during the life of 
   * this JVM. Rollover to negative is allowed.
   */
  private final int serialNumber;
  
  /** 
   * Generates a serial number for identifying a DLS. Later instances of 
   * the same named DLS will have a greater serial number than earlier 
   * instances. This number increments statically throughout the life of this 
   * JVM. Rollover to negative is allowed.
   * @return the new serial number
   */
  protected static int createSerialNumber() {
    // NOTE: AtomicInteger should rollover if value is Integer.MAX_VALUE
    return serialNumberSequencer.incrementAndGet();
  }
  
  /** 
   * Returns the serial number which identifies the static order in which this 
   * DLS was created in relation to other DLS'es or other instances of 
   * this named DLS during the life of this JVM.
   */
  public int getSerialNumber() {
    return this.serialNumber;
  }
  
  // -------------------------------------------------------------------------
  //   External API methods
  // -------------------------------------------------------------------------
  
  /**
   * @see com.gemstone.gemfire.distributed.DistributedLockService#getServiceNamed(String)
   */
  public static DistributedLockService getServiceNamed(String serviceName) {
    DLockService svc = null;
    synchronized (services) {
      svc = services.get(serviceName);
      return svc;
    }
  }
  
  public static DistributedLockService create(String serviceName, 
                                              InternalDistributedSystem ds,
                                              boolean distributed,
                                              boolean destroyOnDisconnect) {
    return create(serviceName, ds, distributed, destroyOnDisconnect, false);
  }
  
  /**
   * Creates named <code>DistributedLockService</code>.
   *
   * @param serviceName 
   *        name of the service
   * @param ds 
   *        InternalDistributedSystem
   * @param distributed 
   *        true if lock service will be distributed; false will be local only
   * @param destroyOnDisconnect
   *        true if lock service should destroy itself using system disconnect 
   *        listener
   * @param automateFreeResources
   *        true if freeResources should be automatically called during unlock
   *
   * @throws IllegalArgumentException if serviceName is invalid or this process 
   * has already created named service
   *
   * @throws IllegalStateException if system is in process of disconnecting
   *
   * @see com.gemstone.gemfire.distributed.DistributedLockService#create(String, DistributedSystem)
   */
  public static DistributedLockService create(String serviceName, 
                                              InternalDistributedSystem ds,
                                              boolean distributed,
                                              boolean destroyOnDisconnect,
                                              boolean automateFreeResources)
  throws IllegalArgumentException, IllegalStateException {
    // basicCreate will construct DLockService and it calls getOrCreateStats...
    synchronized (creationLock) {
      synchronized (services) { // disconnectListener syncs on this
        ds.getCancelCriterion().checkCancelInProgress(null);
        
        // make sure thread group is ready...
        readyThreadGroup();
        
        if (services.get(serviceName) != null) {
          throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_ALREADY_CREATED.toLocalizedString(serviceName));
        }
        return DLockService.basicCreate(
            serviceName, ds, distributed, destroyOnDisconnect, automateFreeResources);
      }
    }
  }
    
  public static void becomeLockGrantor(String serviceName)
  throws IllegalArgumentException {
    becomeLockGrantor(serviceName, null);
  }
  
  public static void becomeLockGrantor(String serviceName, InternalDistributedMember oldTurk)
  throws IllegalArgumentException {
    if (serviceName == null || serviceName.length() == 0) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_IS_NOT_VALID.toLocalizedString(serviceName));
    }
    DLockService svc = null;
    synchronized (services) {
      svc = services.get(serviceName);
    }
    if (svc == null) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_NOT_CREATED.toLocalizedString(serviceName));
    } else {
      svc.becomeLockGrantor(oldTurk);
    }
  }
  
  public static boolean isLockGrantor(String serviceName)
  throws IllegalArgumentException {
    if (serviceName == null || serviceName.length() == 0) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_IS_NOT_VALID.toLocalizedString(serviceName));
    }
    DLockService svc = null;
    synchronized (services) {
      svc = services.get(serviceName);
    }
    if (svc == null) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_NOT_CREATED.toLocalizedString(serviceName));
    } else {
      return svc.isLockGrantor();
    }
  }

  /**
   * Fills lists of service names.
   * @param grantors filled with service names of all services we are currently the grantor of.
   * @param grantorVersions elder assigned grantor version ids
   * @param grantorSerialNumbers member specific DLS serial number hosting grantor
   * @param nonGrantors filled with service names of all services we have that we are not the grantor of.
   */
  public static void recoverRmtElder(ArrayList grantors, 
                                     ArrayList grantorVersions, 
                                     ArrayList grantorSerialNumbers, 
                                     ArrayList nonGrantors) {
    synchronized (services) {
      Iterator entries = services.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry entry = (Map.Entry)entries.next();
        String serviceName = (String)entry.getKey();
        DLockService service = (DLockService)entry.getValue();
        boolean foundGrantor = false;
        DLockGrantor grantor = service.getGrantor();
        if (grantor != null
            && grantor.getVersionId() != -1
            && !grantor.isDestroyed()) {
          foundGrantor = true;
          grantors.add(serviceName);
          grantorVersions.add(Long.valueOf(grantor.getVersionId()));
          grantorSerialNumbers.add(Integer.valueOf(service.getSerialNumber()));
        }
        if (!foundGrantor) {
          nonGrantors.add(serviceName);
        }
      }
    }
  }
  /**
   * Called when an elder is doing recovery.
   * For every service that we are the grantor for add it to the grantorMap
   * For every service we have that is not in the grantorMap add its name
   * to needsRecovery set.
   * @param dm our local DM 
   */
  public static void recoverLocalElder(DM dm, Map grantors, Set needsRecovery) {
    synchronized (services) {
      Iterator entries = services.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry entry = (Map.Entry)entries.next();
        String serviceName = (String)entry.getKey();
        DLockService service = (DLockService)entry.getValue();
        boolean foundGrantor = false;
        DLockGrantor grantor = service.getGrantor();
        if (grantor != null
            && grantor.getVersionId() != -1
            && !grantor.isDestroyed()) {
          foundGrantor = true;
          GrantorInfo oldgi = (GrantorInfo)grantors.get(serviceName);
          if (oldgi == null
              || oldgi.getVersionId() < grantor.getVersionId()) {
            grantors.put(serviceName, new GrantorInfo(dm.getId(), grantor.getVersionId(), service.getSerialNumber(), false));
            needsRecovery.remove(serviceName);
          }
        }
        // fix for elder init bug... adam elder was hitting the following
        // block and found no grantors for services that were just created
        // and flagged them as needing recovery even though it's not needed
        if (!foundGrantor && !(dm.isAdam() && !service.hasHeldLocks())) {
          // !F && !(T && !T) ==> T && !F ==> T
          if (!grantors.containsKey(serviceName)) {
            needsRecovery.add(serviceName);
          }
        }
      }
    }
  }
  // -------------------------------------------------------------------------
  //   Public static methods
  // -------------------------------------------------------------------------
  
  /** Convenience method to get named DLockService */
  public static DLockService getInternalServiceNamed(String serviceName) {
    return services.get(serviceName);
  }
  
  /** Validates service name for external creation */
  public static void validateServiceName(String serviceName) {
    if (serviceName == null || serviceName.length() == 0) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_LOCK_SERVICE_NAME_MUST_NOT_BE_NULL_OR_EMPTY.toLocalizedString());
    }
    for (int i = 0; i < reservedNames.length; i++) {
      if (serviceName.startsWith(reservedNames[i])) {
      throw new IllegalArgumentException(LocalizedStrings.DLockService_SERVICE_NAMED_0_IS_RESERVED_FOR_INTERNAL_USE_ONLY.toLocalizedString(serviceName));
      }
    }
  }
      
  /** Return a snapshot of all services */
  public static Map<String, DLockService> snapshotAllServices() { // used by: internal/admin/remote
    Map snapshot = null;
    synchronized(services) {
      snapshot = new HashMap(services);
    }
    return snapshot;
  }
  
  /**
   * TEST HOOK: Logs all lock tokens for every service at INFO level. 
   * Synchronizes on services map, service tokens maps and each lock token.
   */
  public static void dumpAllServices() {
    DistributedSystem existingSystem = InternalDistributedSystem.getAnyInstance();
    if (existingSystem != null) {
      dumpAllServices(existingSystem.getLogWriter().convertToLogWriterI18n());
    }
  }
  
  /**
   * TEST HOOK: Logs all lock tokens for every service at INFO level. 
   * Synchronizes on services map, service tokens maps and each lock token.
   * 
   * @param log the LogWriter to use
   */
  public static void dumpAllServices(LogWriterI18n log) { // used by: distributed/DistributedLockServiceTest
    StringBuilder buffer = new StringBuilder();
    synchronized (services) {
      log.info(LocalizedStrings.TESTING, "DLockService.dumpAllServices() - " + services.size() + " services:\n");
      Iterator entries = services.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry entry = (Map.Entry)entries.next();
        buffer.append("  " + entry.getKey() + ":\n");
        DLockService svc = (DLockService)entry.getValue();
        svc.dumpService(log);
        if (svc.isCurrentlyLockGrantor()) {
          svc.grantor.dumpService(log);
        }
        
      }
    }
  }
  
  // -------------------------------------------------------------------------
  //   Package static methods
  // -------------------------------------------------------------------------
  
  static ThreadGroup getThreadGroup() {
    return threadGroup;
  }
  
  /**
   * Return a timestamp that represents the current time for locking purposes.
   * @since 3.5
   */
  static long getLockTimeStamp(DM dm) {
    return dm.cacheTimeMillis();
  }
  
  /** Get or create static dlock stats */
  protected static synchronized DistributedLockStats getOrCreateStats() {
    if (stats == DUMMY_STATS) {
      InternalDistributedSystem ds =
          InternalDistributedSystem.getAnyInstance();
      Assert.assertTrue(ds != null, 
          "Cannot find any instance of InternalDistributedSystem");
      StatisticsFactory statFactory = ds;
      long statId = OSProcess.getId();
      stats = new DLockStats(statFactory, statId);
    }
    return stats;
  }

  protected static synchronized DistributedLockStats getDistributedLockStats() {
    return stats;
  }

  protected static void removeLockService(DLockService service) {
    service.removeAllTokens();

    InternalDistributedSystem system = null;
    synchronized (services) {
      DLockService removedService = services.remove(service.getName());
      if (removedService == null) {
        // another thread beat us to the removal... return
        return;
      }
      if (removedService != service) {
        services.put(service.getName(), removedService);
      } else {
        service.getStats().incServices(-1);
      }
      system = removedService.getDistributionManager().getSystem();
    }
    // if disconnecting and this was the last service, cleanup!
    if (services.isEmpty() && system.isDisconnecting()) {
      // get rid of stats and thread group...
      synchronized (DLockService.class) {
        closeStats();
      }
    }
  }

  static void closeStats() {
    if (stats != DUMMY_STATS) {
      ((DLockStats)stats).close();
      stats = DUMMY_STATS;
    }
    threadGroup = null;
  }
  /** Provide way to peek at current lock grantor id when dls does not exist */
  static GrantorInfo checkLockGrantorInfo(String serviceName, 
                                          InternalDistributedSystem system) {
    GrantorInfo gi = GrantorRequestProcessor.peekGrantor(serviceName, 
        system);
    if (system.getLogWriter().fineEnabled()) {
      system.getLogWriter().fine("[checkLockGrantorId] returning " + gi);
    }
    return gi;
  }

  // -------------------------------------------------------------------------
  //   Internal
  // -------------------------------------------------------------------------
  
  protected static synchronized void readyThreadGroup() {
    if (threadGroup == null) {
      InternalDistributedSystem ds = 
          InternalDistributedSystem.getAnyInstance();
      Assert.assertTrue(ds != null, 
          "Cannot find any instance of InternalDistributedSystem");
      LogWriterI18n logger = ds.getLogWriter().convertToLogWriterI18n();
     
      String threadGroupName = LocalizedStrings.DLockService_DISTRIBUTED_LOCKING_THREADS.toLocalizedString();
      final ThreadGroup group = LogWriterImpl.createThreadGroup(
          threadGroupName, logger);
      threadGroup = group;
    }
  }
  
  // -------------------------------------------------------------------------
  //   SuspendLockingToken inner class
  // -------------------------------------------------------------------------
  
  /** Used as the name (key) for the suspend locking entry in the tokens map */
  public static final class SuspendLockingToken implements DataSerializableFixedID {
    public SuspendLockingToken() {}
    
    @Override
    public boolean equals(Object o) {
      if (o == null) return false;
      return o instanceof SuspendLockingToken;
    }
    
    @Override
    public int hashCode() {
      // Since instances always equal each other, they return the same hashCode
      return 15325;
    }
    
    public int getDSFID() {
      return SUSPEND_LOCKING_TOKEN;
    }
    public void fromData(DataInput in) {}
    public void toData(DataOutput out) {}

    @Override
    public Version[] getSerializationVersions() {
       return null;
    }
  }

  /**
   * CancelCriterion which provides cancellation for DLS request and release
   * messages. Cancellation may occur if shutdown or if DLS is destroyed.
   */
  private static class DLockStopper extends CancelCriterion {
    
    /**
     * The DLockService this stopper will check for cancellation.
     */
    private final DLockService dls;
    
    /**
     * True if this stopper initiated cancellation for DLS destroy.
     */
//    private boolean stoppedByDLS = false; // used by single thread
    
    /**
     * Creates a new DLockStopper for the specified DLockService and DM.
     * 
     * @param dm the DM to check for shutdown
     * @param dls the DLockService to check for DLS destroy
     */
    DLockStopper(DM dm, DLockService dls) {
      Assert.assertTrue(dls != null);
      this.dls = dls;
      Assert.assertTrue(dls.getDistributionManager() != null);
    }
    
    @Override
    public String cancelInProgress() {
      String cancelInProgressString = 
        this.dls.getDistributionManager().getCancelCriterion().cancelInProgress();
      if (cancelInProgressString != null) {
        // delegate to underlying DM's CancelCriterion...
        return cancelInProgressString;
      }
      else if (this.dls.isDestroyed()) {
//        this.stoppedByDLS = true;
        return this.dls.generateLockServiceDestroyedMessage();
      }
      else {
        // return null since neither DM nor DLS are shutting down
        // cannot call super.cancelInProgress because it's abstract
        return null;
      }
    }

    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      String reason = cancelInProgress();
      if (reason == null) {
        return null;
      }
      return this.dls.generateLockServiceDestroyedException(reason);
//        if (this.stoppedByDLS) { // set and checked by same thread
//        return this.dls.generateLockServiceDestroyedException(reason);
//      }
//      return new DistributedSystemDisconnectedException(reason, e);
    }

  }
  
  public final CancelCriterion getCancelCriterion() {
    return stopper;
  }
  
}

