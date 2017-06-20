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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.LockTimeoutException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.distributed.internal.membership.
    InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * Implementation of {@link GfxdLockService} to obtain just local read/write
 * locks and keep track of those in a map.
 * 
 * @author swale
 */
public final class GfxdLocalLockService extends
    ConcurrentHashMap<Object, GfxdReadWriteLock> implements GfxdLockService {

  private static final long serialVersionUID = -3658472369312965223L;

  /** name used for logging */
  private final String serviceName;

  /** template object used for creating {@link GfxdReadWriteLock}s */
  private final GfxdReadWriteLock lockTemplate;

  /**
   * Maximum lock wait time for a VM for write locks after which write locks
   * obtained so far will be released and reacquired.
   */
  final long maxVMWriteLockWait;

  /**
   * record of the last dump time to avoid dumping by multiple threads fairly
   * close together
   */
  private long lastDumpTime;

  public GfxdLocalLockService(String serviceName,
      GfxdReadWriteLock lockTemplate, long maxVMWriteLockWait) {
    this.serviceName = serviceName;
    if (lockTemplate != null) {
      this.lockTemplate = lockTemplate;
    }
    else {
      this.lockTemplate = GfxdReentrantReadWriteLock.createTemplate(true);
    }
    this.maxVMWriteLockWait = maxVMWriteLockWait;
    this.lastDumpTime = -1;
  }

  /**
   * @see GfxdLockService#readLock(Object, Object, long)
   */
  public final boolean readLock(final Object name, final Object owner,
      long waitTimeMillis) {
    return getOrCreateLock(name).attemptReadLock(waitTimeMillis, owner);
  }

  /**
   * @see GfxdLockService#readLock(GfxdLockable, Object, long)
   */
  public boolean readLock(GfxdLockable lockable, Object owner,
      long waitTimeMillis) {
    return getOrCreateLock(lockable, lockable.getName()).attemptReadLock(
        waitTimeMillis, owner);
  }

  /**
   * @see GfxdLockService#readUnlock(Object)
   */
  public void readUnlock(Object name) {
    readUnlock(name, getOrCreateLock(name));
  }

  /**
   * @see GfxdLockService#readUnlock(GfxdLockable)
   */
  public void readUnlock(GfxdLockable lockable) {
    final Object name = lockable.getName();
    readUnlock(name, getOrCreateLock(lockable, name));
  }

@edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IMSE_DONT_CATCH_IMSE",
  justification="lock-service code is allowed to catch this exception")
  private void readUnlock(Object name, GfxdReadWriteLock rwLock) {
    try {
      if (rwLock != null) {
        rwLock.releaseReadLock();
      }
      else {
        throw new LockNotHeldException(LocalizedStrings
            .DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK
                .toLocalizedString(new Object[] { this, name }));
      }
    } catch (IllegalMonitorStateException ex) {
      final LockNotHeldException lnhe = new LockNotHeldException(
          LocalizedStrings
              .DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK
                  .toLocalizedString(new Object[] { this, name }));
      lnhe.initCause(ex);
      throw lnhe;
    }
  }

  /**
   * @see GfxdLockService#hasReadLock(Object)
   */
  public ReadLockState hasReadLock(Object name) {
    final GfxdReadWriteLock rwLock = this.get(name);
    if (rwLock != null) {
      return rwLock.hasReadLock();
    }
    return ReadLockState.NOT_HELD;
  }

  /**
   * Attempts to acquire a local write lock on object <code>name</code>.
   * 
   * @see GfxdLockService#writeLock(Object, Object, long, long)
   */
  public boolean writeLock(Object name, Object owner, long waitTimeMillis,
      long leaseTimeMillis) {
    return localWriteLock(name, getOrCreateLock(name), owner, waitTimeMillis);
  }

  /**
   * @see GfxdLockService#writeLock(GfxdLockable, Object, long, long)
   */
  public boolean writeLock(GfxdLockable lockable, Object owner,
      long waitTimeMillis, long leaseTimeMillis) {
    final Object name = lockable.getName();
    return localWriteLock(name, getOrCreateLock(lockable, name), owner,
        waitTimeMillis);
  }

  /**
   * Release the local write lock previously granted for the given
   * <code>name</code> to the given owner.
   * 
   * @throws LockNotHeldException
   *           if the current thread is not the owner of this lock
   * 
   * @see GfxdLockService#writeUnlock(Object, Object)
   */
  public void writeUnlock(Object name, Object owner) {
    if (!localWriteUnlock(getOrCreateLock(name), owner)) {
      throw new LockNotHeldException(LocalizedStrings
          .DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK
              .toLocalizedString(new Object[] { this, name }));
    }
  }

  /**
   * @see GfxdLockService#writeUnlock(GfxdLockable, Object)
   */
  public void writeUnlock(GfxdLockable lockable, Object owner) {
    final Object name = lockable.getName();
    if (!localWriteUnlock(getOrCreateLock(lockable, name), owner)) {
      throw new LockNotHeldException(LocalizedStrings
          .DLockService_ATTEMPTING_TO_UNLOCK_0_1_BUT_THIS_THREAD_DOESNT_OWN_THE_LOCK
              .toLocalizedString(new Object[] { this, name }));
    }
  }

  /**
   * Returns the current thread as the lock owner for local locks.
   * 
   * @see GfxdLockService#newCurrentOwner()
   */
  @Override
  public final Thread newCurrentOwner() {
    return Thread.currentThread();
  }

  /**
   * @see GfxdLockService#getLocalLockService()
   */
  public GfxdLocalLockService getLocalLockService() {
    return this;
  }

  /**
   * Returns true if the given owner holds a local write lock on the given
   * object.
   * 
   * @see GfxdLockService#hasWriteLock(Object, Object)
   */
  public boolean hasWriteLock(Object name, Object owner) {
    final GfxdReadWriteLock rwLock = this.get(name);
    if (rwLock != null) {
      return rwLock.hasWriteLock(owner);
    }
    return false;
  }

  /**
   * <p>
   * Returns the current local write lock owner for given object, or null if no
   * write is currently held.
   * </p>
   * 
   * @param name
   *          object to be checked for write lock
   */
  public Object getWriteLockOwner(Object name) {
    final GfxdReadWriteLock rwLock = this.get(name);
    if (rwLock != null) {
      return rwLock.getWriteLockOwner();
    }
    return null;
  }

  /**
   * @see GfxdLockService#freeResources(Object)
   */
  public void freeResources(Object name) {
    GfxdReadWriteLock lock = this.remove(name);
    if (lock != null) {
      lock.setInMap(false);
    }
  }

  public final TimeoutException getLockTimeoutRuntimeException(
      final Object lockObject, final Object lockOwner,
      final boolean dumpAllLocks) {
    if (dumpAllLocks) {
      dumpAllRWLocks("LOCK TABLE at the time of failure", null, true);
    }
    // check if the current thread was interrupted or system shutting down
    Throwable cause = null;
    if (Thread.interrupted()) {
      cause = new InterruptedException();
      // restore the interrupt flag
      Thread.currentThread().interrupt();
    }
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(cause);
    final GfxdReadWriteLock lock;
    if (lockObject instanceof GfxdLockable) {
      lock = ((GfxdLockable)lockObject).getReadWriteLock();
    }
    else {
      lock = this.get(lockObject);
    }
    String exStr = "lock timeout for object: " + lockObject + ", for lock: "
        + lock;
    if (lockOwner != null) {
      exStr += ", requested for owner: " + lockOwner;
    }
    return new LockTimeoutException(exStr, cause);
  }

  public final StandardException getLockTimeoutException(
      final Object lockObject, final Object owner, final boolean dumpAllLocks) {
    return StandardException.newException(SQLState.LOCK_TIMEOUT,
        getLockTimeoutRuntimeException(lockObject, owner, dumpAllLocks));
  }

  /**
   * Dump all read-write locks acquired on this VM at an info level for
   * diagnostic purpose. Also dumps the stack traces of all threads in the sane
   * build.
   * 
   * This does not really belong to GfxdLocalLockService since it is not lock
   * service specific.
   * 
   * @param logPrefix
   *          any prefix to be prepended before the lock information
   * @param pw
   *          if non-null then dump is generated on given PrintWriter else it
   *          goes to standard GFXD log file
   * @param force
   *          if true then dump is forced immediately, else avoid multiple
   *          dumps in quick succession (e.g. when many thread timeout on lock)
   */
  public void dumpAllRWLocks(String logPrefix, PrintWriter pw, boolean force) {
    // do not do the dump multiple times since multiple threads will try
    // to dump fairly close together
    synchronized (this) {
      long currentTime = System.currentTimeMillis();
      if (!force && this.lastDumpTime > 0) {
        if ((currentTime - this.lastDumpTime) < this.maxVMWriteLockWait) {
          return;
        }
      }
      this.lastDumpTime = currentTime;
    }
    final String marker = "==========================================="
        + "======================";
    final StringBuilder msg = new StringBuilder();
    final String thisToString = getClass().getSimpleName() + '@'
        + Integer.toHexString(System.identityHashCode(this)) + '['
        + this.serviceName + ']';
    final LogWriterI18n logger = Misc.getI18NLogWriter();
    msg.append(thisToString).append(": ").append(logPrefix)
        .append(SanityManager.lineSeparator).append(marker)
        .append(SanityManager.lineSeparator);
    try {
      GfxdReadWriteLock lock = null;
      for (Map.Entry<Object, GfxdReadWriteLock> lockEntry : entrySet()) {
        lock = lockEntry.getValue();
        lock.dumpAllThreads(msg, lockEntry.getKey(), thisToString);
      }
      if (lock != null) {
        lock.dumpAllReaders(msg, thisToString);
      }

      // dump all other DLockServices too
      GfxdDRWLockService.dumpAllDLockServices(msg);

      if (msg.length() > TXManagerImpl.DUMP_STRING_LIMIT) {
        TXManagerImpl.dumpMessage(msg, pw);
        msg.setLength(0);
      }

      // also dump all active TXStates
      msg.append(SanityManager.lineSeparator);
      TXManagerImpl.dumpAllTXStates(msg, "TX states");

      // now dump all TXState locks
      msg.append(SanityManager.lineSeparator);
      TXManagerImpl.dumpAllEntryLocks(msg, "Entry lock", pw);

      if (msg.length() > TXManagerImpl.DUMP_STRING_LIMIT) {
        TXManagerImpl.dumpMessage(msg, pw);
        msg.setLength(0);
      }

      // then all the thread stacks
      msg.append(SanityManager.lineSeparator).append("Full Thread Dump:")
          .append(SanityManager.lineSeparator)
          .append(SanityManager.lineSeparator);
      generateThreadDump(msg);
      msg.append(marker).append(SanityManager.lineSeparator);
      TXManagerImpl.dumpMessage(msg, pw);
    } catch (Exception ex) {
      logger.severe(LocalizedStrings.DEBUG,
          "Exception while dumping lock table of this JVM", ex);
    }
  }

  public static void generateThreadDump(StringBuilder msg) {
    ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
    for (ThreadInfo tInfo : mbean.dumpAllThreads(true, true)) {
      ClientSharedUtils.dumpThreadStack(tInfo, msg,
          SanityManager.lineSeparator);
    }
  }

  private boolean localWriteLock(Object name, GfxdReadWriteLock rwLock,
      Object owner, long waitTimeMillis) {
    if (owner instanceof DistributedLockOwner) {
      ((DistributedLockOwner)owner).setVMCreatorThread();
    }
    return rwLock.attemptWriteLock(waitTimeMillis, owner);
  }

@edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IMSE_DONT_CATCH_IMSE",
  justification="lock-service code is allowed to catch this exception")
  private boolean localWriteUnlock(GfxdReadWriteLock rwLock, Object owner) {
    if (rwLock != null) {
      try {
        rwLock.releaseWriteLock(owner);
        return true;
      } catch (IllegalMonitorStateException ex) {
        // fail release
      }
    }
    return false;
  }

  GfxdReadWriteLock getOrCreateLock(final Object name) {
    return getOrCreateLock(name, null);
  }

  private GfxdReadWriteLock getOrCreateLock(final Object name,
      final GfxdReadWriteLock origLock) {
    GfxdReadWriteLock rwLock = this.get(name);
    if (rwLock == null) {
      if (origLock != null) {
        rwLock = origLock;
      }
      else {
        rwLock = this.lockTemplate.newLock(name);
        rwLock.setInMap(true);
      }
      GfxdReadWriteLock oldLock = this.putIfAbsent(name, rwLock);
      if (oldLock != null) {
        rwLock = oldLock;
      }
      else if (origLock != null) {
        rwLock.setInMap(true);
      }
    }
    return rwLock;
  }

  private GfxdReadWriteLock getOrCreateLock(final GfxdLockable lockable,
      final Object name) {
    GfxdReadWriteLock rwLock = lockable.getReadWriteLock();
    if (rwLock != null && rwLock.inMap()) {
      return rwLock;
    }
    synchronized (lockable) {
      rwLock = getOrCreateLock(name, rwLock);
      lockable.setReadWriteLock(rwLock);
      if (lockable.traceLock()) {
        rwLock.setTraceLock();
      }
    }
    return rwLock;
  }

  // -------------------------------------------------------------------------
  // DistributedLockOwner
  // -------------------------------------------------------------------------
  /**
   * Encapsulates the current VM (as its {@link DistributedMember}) and unique
   * ID for current thread to give a unique owner in the DistributedSystem.
   * Suitable for passing to the owner argument for {@link GfxdDRWLockService}'s
   * read/write synchronization methods.
   * 
   * @author swale
   * @since 7.0
   */
  public static class DistributedLockOwner extends GfxdDataSerializable {

    private InternalDistributedMember ownerMember;

    private long ownerThreadId;

    private String ownerThreadName;

    private transient Thread vmCreatorThread;

    /** for deserialization */
    public DistributedLockOwner() {
    }

    protected DistributedLockOwner(final InternalDistributedMember myId) {
      this(myId, EventID.getThreadId());
    }

    protected DistributedLockOwner(final InternalDistributedMember myId,
        final long threadId) {
      this.ownerMember = myId;
      this.ownerThreadId = threadId;
      this.vmCreatorThread = Thread.currentThread();
      this.ownerThreadName = this.vmCreatorThread.toString();
    }

    /**
     * Get the {@link DistributedMember} that represents this lock owner.
     */
    public final InternalDistributedMember getOwnerMember() {
      return this.ownerMember;
    }

    /**
     * Get a unique ID for the thread on {@link DistributedMember} that
     * represents this lock owner.
     */
    public final long getOwnerThreadId() {
      return this.ownerThreadId;
    }

    public final String getOwnerThreadName() {
      return this.ownerThreadName;
    }

    protected final Thread getVMCreatorThread() {
      return this.vmCreatorThread;
    }

    protected final void setVMCreatorThread() {
      this.vmCreatorThread = Thread.currentThread();
    }

    @Override
    public int hashCode() {
      final long threadId = this.ownerThreadId;
      // shift threadId if it only lies in lower half
      if (threadId <= Short.MAX_VALUE) {
        return this.ownerMember.hashCode() ^ (int)(threadId << Short.SIZE);
      }
      else if (threadId <= Integer.MAX_VALUE) {
        return this.ownerMember.hashCode() ^ (int)threadId;
      }
      return this.ownerMember.hashCode()
          ^ (int)(threadId ^ (threadId >>> Integer.SIZE));
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof DistributedLockOwner) {
        final DistributedLockOwner otherOwner = (DistributedLockOwner)other;
        return this.ownerThreadId == otherOwner.ownerThreadId
            && this.ownerMember.equals(otherOwner.ownerMember);
      }
      return false;
    }

    @Override
    public String toString() {
      return "DistributedLockOwner(member=" + this.ownerMember + ",threadId="
          + this.ownerThreadId + ",ownerThread=" + this.ownerThreadName
          + ",vmCreatorThread=" + getVMCreatorThread() + ')';
    }

    /**
     * @see GfxdDataSerializable#getGfxdID()
     */
    @Override
    public byte getGfxdID() {
      return DISTRIBUTED_LOCK_OWNER;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      this.ownerMember.toData(out);
      InternalDataSerializer.writeSignedVL(this.ownerThreadId, out);
      DataSerializer.writeString(this.ownerThreadName, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      this.ownerMember = new InternalDistributedMember();
      this.ownerMember.fromData(in);
      this.ownerThreadId = InternalDataSerializer.readSignedVL(in);
      this.ownerThreadName = DataSerializer.readString(in);
    }
  }
}
