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
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.LeaseExpiredException;
import com.gemstone.gemfire.distributed.LockNotHeldException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.locks.DLockLogWriter;
import com.gemstone.gemfire.distributed.internal.locks.DLockRequestProcessor;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.distributed.internal.locks.LockGrantorId;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdReplyMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResponseCode;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdWaitingReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockReleaseProcessor.GfxdDRWLockReleaseMessage;

/**
 * This class provides handling of remote and local distributed write lock
 * requests. Currently this is used for GemFireXD write locks during DDL
 * execution by {@link GfxdDRWLockService}.
 * 
 * A lock client first obtains a DLock by sending a
 * <code>DLockRequestMessage</code> to the lock grantor as in
 * {@link DLockRequestProcessor}. Once the DLock is successfully obtained it
 * sends a write lock request to all members to acquire a write lock locally
 * on each of the members using {@link GfxdDRWLockService}. This allows the
 * read locks to be local to each member so that the distributed write lock
 * acquisition will wait until read locks on all members have been released.
 * 
 * In case the write lock acquisition fails due to time out or some other
 * condition then the acquired DLock and all write locks acquired thus far are
 * released by invoking {@link GfxdDRWLockReleaseProcessor} using a call to
 * {@link DLockResponseMessage#releaseOrphanedGrant(DM)}.
 * 
 * @see GfxdDRWLockService
 * @author swale
 */
public final class GfxdDRWLockRequestProcessor extends DLockRequestProcessor {

  // -------------------------------------------------------------------------
  // Constructor
  // -------------------------------------------------------------------------
  protected GfxdDRWLockRequestProcessor(final LockGrantorId lockGrantorId,
      final DLockService svc, final Object objectName, final int threadId,
      final long startTime, final long leaseMillis, final long waitMillis,
      final boolean reentrant, final boolean tryLock, final DM dm) {
    super(lockGrantorId, svc, objectName, threadId, startTime, leaseMillis,
        waitMillis, reentrant, tryLock, dm, false);
  }

  public static GfxdWaitingReplyProcessor requestDRWLock(
      final GfxdDRWLockService rwsvc, final Object objectName,
      final Object lockOwner, final DM dm, long waitMillis,
      final boolean interruptible, final LogWriterI18n log)
      throws InterruptedException {
    GfxdWaitingReplyProcessor response = null;
    GfxdResponseCode responseCode = null;
    if (waitMillis < 0) {
      waitMillis = Long.MAX_VALUE;
    }
    final long currentTime = System.currentTimeMillis();
    final long endTime = ((Long.MAX_VALUE - waitMillis) < currentTime
        ? Long.MAX_VALUE : (currentTime + waitMillis));
    Set<DistributedMember> members;
    final InternalDistributedMember myId = dm.getDistributionManagerId();
    Set<DistributedMember> grantedMembers = Collections.emptySet();
    boolean toSelf;
    try {
      while (waitMillis > 0
          && ((toSelf = (members = GfxdMessage.getAllGfxdMembers())
              .remove(myId)) || members.size() > 0)) {
        response = new GfxdWaitingReplyProcessor(dm, members, true, true);
        try {
          long vmWaitMillis = rwsvc.getMaxVMWriteLockWait();
          if (vmWaitMillis > waitMillis) {
            vmWaitMillis = waitMillis;
          }
          final boolean localGrant = GfxdDRWLockRequestMessage.send(dm,
              response, members, toSelf, rwsvc, objectName, lockOwner,
              vmWaitMillis, interruptible, log);
          responseCode = response.getResponseCode();
          grantedMembers = response.getGrantedMembers();
          // retry in case of timeouts while we don't hit the maximum limit
          if (localGrant
              && (responseCode.isGrant() || responseCode.isException())) {
            break;
          }
          // release the lock where it has been granted so far before retry
          if (grantedMembers.size() > 0) {
            try {
              toSelf = grantedMembers.remove(myId);
              GfxdDRWLockReleaseMessage.send(dm, grantedMembers, toSelf,
                  rwsvc.getName(), objectName, lockOwner, true, log);
            } catch (final ReplyException ex) {
              ex.handleAsUnexpected();
            }
            grantedMembers.clear();
          }
          if (log.infoEnabled()) {
            log.info(LocalizedStrings.LockRequest_RETRYING_FOR_LOCK,
                new Object[] { response.toString(), objectName, vmWaitMillis });
          }
        } finally {
          response.endWait();
        }
        if (waitMillis != Long.MAX_VALUE) {
          waitMillis = endTime - System.currentTimeMillis();
        }
      }
    } catch (final InterruptedException ie) {
      throw ie;
    } catch (final Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      SystemFailure.checkFailure();
      if (t.getCause() instanceof InterruptedException) {
        throw (InterruptedException)t.getCause();
      }
      if (log.fineEnabled()) {
        log.fine("GfxdDRWLockRequestProcessor caught Exception", t);
      }
    } finally {
      // if we failed to get the ReadWriteLock due to timeout or exception,
      // then release the DLock
      if (response != null && !response.getResponseCode().isGrant()) {
        // first release the write lock on any remaining members
        if (grantedMembers.size() > 0) {
          toSelf = grantedMembers.remove(myId);
          GfxdDRWLockReleaseMessage.send(dm, grantedMembers, toSelf,
              rwsvc.getName(), objectName, lockOwner, true, log);
        }
        try {
          rwsvc.unlock(objectName);
        } catch (LockNotHeldException ex) {
          // ignore exception in lock release at this point
          if (log.fineEnabled()) {
            log.fine("GfxdDRWLockRequestProcessor unexpected exception"
                + " in unlock after failed distributed write lock", ex);
          }
        } catch (LeaseExpiredException ex) {
          // ignore exception in lock release at this point
          if (log.fineEnabled()) {
            log.fine("GfxdDRWLockRequestProcessor unexpected exception"
                + " in unlock after failed distributed write lock", ex);
          }
        }
        // check for this VM going down before returning failure
        dm.getCancelCriterion().checkCancelInProgress(null);
      }
    }
    return response;
  }

  // -------------------------------------------------------------------------
  // GfxdDRWLockRequestMessage
  // -------------------------------------------------------------------------
  /**
   * Implements the distributed write lock message for Distributed
   * ReadWriteLock. The message is sent to all members of the DS allowing the
   * read locks to be local to each member.
   * 
   * @see GfxdDRWLockRequestProcessor
   * @author swale
   */
  public static final class GfxdDRWLockRequestMessage extends GfxdMessage
      implements MessageWithReply {

    /** The name of the DistributedLockService */
    protected String serviceName;

    /** The object name */
    protected Object objectName;

    /** The owner of the lock. */
    protected Object lockOwner;

    protected long waitMillis;

    /** Transient field to indicate whether grant was successful or not. */
    private transient boolean grant;

    /** Sequence ID of the replies sent. */
    private transient int sequenceId;

    /** The read-write lock service looked up locally on remote node. */
    private transient GfxdDRWLockService rwsvc;

    /**
     * Sends a {@link GfxdDRWLockRequestMessage} to given members of the
     * distributed system to obtain a distributed write lock.
     */
    public static boolean send(final DM dm,
        final GfxdWaitingReplyProcessor processor,
        final Set<DistributedMember> members, final boolean toSelf,
        final GfxdDRWLockService svc, final Object objectName,
        final Object lockOwner, final long waitMillis,
        final boolean interruptible, final LogWriterI18n log)
        throws InterruptedException {
      final GfxdDRWLockRequestMessage msg = new GfxdDRWLockRequestMessage();
      msg.processorId = processor.getProcessorId();
      msg.serviceName = svc.getName();
      msg.objectName = objectName;
      msg.lockOwner = lockOwner;
      msg.waitMillis = (waitMillis < 0 ? Long.MAX_VALUE : waitMillis);
      msg.setRecipients(members);
      if (log.fineEnabled()) {
        log.fine("GfxdDRWLockRequestMessage#send: acquiring writeLock for "
            + "object [" + objectName + "]");
      }
      boolean grant = true;
      if (toSelf) {
        grant = svc.getLocalLockService().writeLock(objectName, lockOwner,
            waitMillis, -1);
        if (log.fineEnabled()) {
          log.fine("GfxdDRWLockRequestMessage#send: writeLock for object ["
              + objectName + "] " + (grant ? "successful" : "unsuccessful"));
        }
        if (grant) {
          processor.addGrantedMember(dm.getDistributionManagerId(), 1);
        }
        else {
          processor.setResponseCode(GfxdResponseCode.TIMEOUT,
              dm.getDistributionManagerId());
        }
      }
      if (grant) {
        if (DistributionManager.VERBOSE || log.fineEnabled()) {
          log.fine(msg.toString() + "#send: sending writeLock message for "
              + "object [" + objectName + "] to members: " + members
              + ", myId: " + dm.getDistributionManagerId());
        }
        dm.putOutgoing(msg);
        waitForReplies(processor, interruptible, true);
      }
      return grant;
    }

    static void waitForReplies(final GfxdWaitingReplyProcessor processor,
        final boolean interruptible, final boolean checkWaiters)
        throws InterruptedException {
      ReplyException replyEx = null;
      try {
        if (interruptible) {
          if (checkWaiters) {
            processor.waitForReplies(0, processor.getWaitersLatch(), false);
          }
          else {
            processor.waitForReplies();
          }
        }
        else { // not interruptible
          if (checkWaiters) {
            processor.waitForRepliesUninterruptibly(0,
                processor.getWaitersLatch(), false);
          }
          else {
            processor.waitForRepliesUninterruptibly();
          }
        }
      } catch (ReplyException ex) {
        replyEx = ex;
      }
      if (replyEx == null) {
        replyEx = processor.getReplyException();
      }
      // ignore node failure exceptions from other nodes
      if (replyEx != null) {
        // first check this node going down
        Misc.getGemFireCache().getCancelCriterion()
            .checkCancelInProgress(replyEx);
      }
    }

    @Override
    protected void processMessage(final DistributionManager dm) {
      this.grant = false;
      final LogWriterI18n log = dm.getLoggerI18n();
      this.sequenceId = 1;
      final DistributedLockService svc = DistributedLockService
          .getServiceNamed(this.serviceName);
      this.grant = true;
      if (svc != null && svc instanceof GfxdDRWLockService) {
        // obtain the write lock
        this.rwsvc = (GfxdDRWLockService)svc;
        final GfxdLocalLockService lsvc = this.rwsvc.getLocalLockService();
        final InternalDistributedMember sender = getSender();
        if (log.fineEnabled()) {
          log.fine("GfxdDRWLockRequestMessage#process: getting writeLock "
              + "for object [" + this.objectName + "] with waitMillis="
              + this.waitMillis + " owner=" + this.lockOwner);
        }
        this.grant = lsvc.writeLock(this.objectName, this.lockOwner,
            this.waitMillis, -1);
        if (log.fineEnabled()) {
          log.fine("GfxdDRWLockRequestMessage#process: writeLock for object"
              + " [" + this.objectName + "] was "
              + (this.grant ? "successful" : "unsuccessful")
              + " with waitMillis=" + this.waitMillis + " owner="
              + this.lockOwner + ", sender=" + sender);
        }
        // check if sender is still alive else release the lock immediately
        if (this.grant) {
          if (!dm.isCurrentMember(sender)) {
            releaseAfterMemberDeparted(log, sender);
            this.grant = false;
          }
        }
      }
    }

    @Override
    protected void sendReply(ReplyException ex, DistributionManager dm) {
      final GfxdDRWLockResponseMessage response;
      final LogWriterI18n log = new DLockLogWriter(dm.getLoggerI18n());
      if (ex != null) {
        response = createResponse(GfxdResponseCode.EXCEPTION, ex, log);
      }
      else if (this.grant) {
        response = createResponse(GfxdResponseCode.GRANT(this.sequenceId),
            null, log);
      }
      else {
        response = createResponse(GfxdResponseCode.TIMEOUT, null, log);
      }
      final Set<?> noRecipients = dm.putOutgoing(response);
      // if the sender has gone down and did not receive the reply then release
      // the local write lock immediately
      if (noRecipients != null && this.rwsvc != null
          && response.getResponseCode().isGrant()
          && noRecipients.contains(getSender())) {
        releaseAfterMemberDeparted(log, sender);
      }
    }

    private final void releaseAfterMemberDeparted(final LogWriterI18n log,
        final InternalDistributedMember sender) {
      // lock may have been released by cleanup thread already
      boolean released = false;
      try {
        this.rwsvc.getLocalLockService().writeUnlock(this.objectName,
            this.lockOwner);
        released = true;
      } catch (LockNotHeldException ex) {
        // ignored
      }
      if (log.fineEnabled()) {
        log.fine("GfxdDRWLockRequestMessage#process: writeLock for object ["
            + this.objectName + "] with owner " + this.lockOwner + (released
                ? " released since" : " tried to be released but already "
                  + "unlocked after") + " the requester [" + sender
                  + "] has departed");
      }
    }

    @Override
    protected boolean waitForNodeInitialization() {
      return false;
    }

    private GfxdDRWLockResponseMessage createResponse(
        final GfxdResponseCode code,
        final Throwable replyException, final LogWriterI18n log) {
      final GfxdDRWLockResponseMessage response = new GfxdDRWLockResponseMessage();
      response.setProcessorId(getProcessorId());
      response.setRecipient(getSender());
      response.serviceName = this.serviceName;
      response.objectName = this.objectName;
      if (replyException != null) {
        response.setException(new ReplyException(replyException));
        if (log.fineEnabled()) {
          log.fine("While processing <" + this
              + ">, got exception, returning to sender", response
              .getException());
        }
      }
      else {
        response.responseCode = code;
      }
      return response;
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.DRWLOCK_REQUEST_MESSAGE;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.serviceName, out);
      DataSerializer.writeObject(this.objectName, out);
      DataSerializer.writeObject(this.lockOwner, out);
      out.writeLong(this.waitMillis);
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.serviceName = DataSerializer.readString(in);
      this.objectName = DataSerializer.readObject(in);
      this.lockOwner = DataSerializer.readObject(in);
      this.waitMillis = in.readLong();
    }

    @Override
    protected void appendFields(final StringBuilder sb) {
      super.appendFields(sb);
      sb.append("; serviceName=").append(this.serviceName);
      sb.append("; name=").append(this.objectName);
      sb.append("; owner=").append(this.lockOwner);
      sb.append("; sender=").append(getSender());
      sb.append("; waitMillis=").append(this.waitMillis);
    }
  }

  // -------------------------------------------------------------------------
  // GfxdDRWLockResponseMessage
  // -------------------------------------------------------------------------
  /**
   * This is a response to an {@link GfxdDRWLockRequestMessage}. A response
   * communicates one of four things:
   * 
   * 1. GRANT - the lock was successfully granted
   * 
   * 2. TIMEOUT - the lock request has timed out
   * 
   * 3. WAITING - the lock request is still waiting; this response can be sent
   * multiple times
   * 
   * 4. EXCEPTION - some exception during message processing
   * 
   * @see GfxdDRWLockRequestMessage
   * @author swale
   */
  public static final class GfxdDRWLockResponseMessage extends GfxdReplyMessage {

    /** The name of the DistributedLockService */
    protected String serviceName;

    /** The object name */
    protected Object objectName;

    /** Specifies the results of this response */
    protected GfxdResponseCode responseCode = GfxdResponseCode.EXCEPTION;

    public GfxdDRWLockResponseMessage() {
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.DRWLOCK_RESPONSE_MESSAGE;
    }

    @Override
    public GfxdResponseCode getResponseCode() {
      return this.responseCode;
    }

    @Override
    public void setException(ReplyException ex) {
      super.setException(ex);
      this.responseCode = GfxdResponseCode.EXCEPTION;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      this.responseCode.toData(out);
      out.writeUTF(this.serviceName);
      DataSerializer.writeObject(this.objectName, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      this.responseCode = GfxdResponseCode.fromData(in);
      this.serviceName = in.readUTF();
      this.objectName = DataSerializer.readObject(in);
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();

      sb.append("{GfxdDRWLockResponseMessage id=" + this.processorId);
      sb.append(" responseCode=" + this.responseCode);
      sb.append(" serviceName=" + this.serviceName);
      sb.append(" name=" + this.objectName);
      sb.append(" sender=" + getSender());
      sb.append(" processorId=" + this.processorId);
      sb.append("}");
      return sb.toString();
    }
  }

  // -------------------------------------------------------------------------
  // GfxdDRWLockDumpMessage
  // -------------------------------------------------------------------------
  /**
   * Message to dump all the locks using
   * {@link GfxdDRWLockService#dumpAllRWLocks} on all members
   * (other than this VM) of the DS.
   * 
   * @see GfxdDRWLockService#dumpAllRWLocks
   * @author swale
   */
  public static final class GfxdDRWLockDumpMessage extends GfxdMessage
      implements MessageWithReply {

    /** name of the DistributedLockService */
    private String serviceName;

    /** prefix to be used for logs */
    private String logPrefix;

    /**
     * if true then dump is generated on standard output rather than the GFXD
     * log file
     */
    private boolean stdout;

    private static final short ISSTDOUT = UNRESERVED_FLAGS_START;

    public GfxdDRWLockDumpMessage() {
    }

    /**
     * Sends a {@link GfxdDRWLockDumpMessage} to all other members of the
     * distributed system.
     */
    public static void send(final InternalDistributedSystem sys,
        final String serviceName, final String logPrefix, final boolean stdout,
        final LogWriterI18n log) {
      final GfxdDRWLockDumpMessage msg = new GfxdDRWLockDumpMessage();
      msg.serviceName = serviceName;
      msg.logPrefix = logPrefix;
      msg.stdout = stdout;
      DM dm = sys.getDistributionManager();
      Set<DistributedMember> otherMembers = getOtherMembers();
      ReplyProcessor21 processor = new ReplyProcessor21(sys, otherMembers);
      msg.setRecipients(otherMembers);
      msg.setProcessorId(processor.getProcessorId());
      dm.putOutgoing(msg);
      try {
        processor.waitForReplies();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // check for JVM going down
        Misc.checkIfCacheClosing(ie);
      } catch (ReplyException ex) {
        if (log.severeEnabled()) {
          log.severe(LocalizedStrings.DEBUG, ex.getMessage(), ex);
        }
      }
    }

    /**
     * Processes this message - invoked on all the nodes.
     */
    @Override
    protected void processMessage(final DistributionManager dm) {
      final DistributedLockService svc = DistributedLockService
          .getServiceNamed(this.serviceName);
      if (svc != null && svc instanceof GfxdDRWLockService) {
        // dump the locks
        final GfxdDRWLockService rwsvc = (GfxdDRWLockService)svc;
        rwsvc.dumpAllRWLocks(this.logPrefix, false, this.stdout, false);
      }
    }

    @Override
    protected void sendReply(ReplyException ex, DistributionManager dm) {
      ReplyMessage.send(getSender(), this.processorId, ex, dm, null);
    }

    @Override
    protected boolean waitForNodeInitialization() {
      return false;
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.DRWLOCK_DUMP_MESSAGE;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.serviceName, out);
      DataSerializer.writeString(this.logPrefix, out);
    }

    @Override
    protected short computeCompressedShort(short flags) {
      flags = super.computeCompressedShort(flags);
      if (this.stdout) {
        flags |= ISSTDOUT;
      }
      return flags;
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.serviceName = DataSerializer.readString(in);
      this.logPrefix = DataSerializer.readString(in);
      this.stdout = (flags & ISSTDOUT) != 0;
    }

    @Override
    protected void appendFields(final StringBuilder sb) {
      super.appendFields(sb);
      sb.append("; serviceName=").append(this.serviceName);
      sb.append("; logPrefix=").append(this.logPrefix);
      sb.append("; isStdout=").append(this.stdout);
      sb.append("; sender=").append(getSender());
    }
  } // GfxdDRWLockDumpMessage
}
