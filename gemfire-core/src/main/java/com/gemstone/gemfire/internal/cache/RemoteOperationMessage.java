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

package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gnu.trove.THashMap;

/**
 * The base PartitionedRegion message type upon which other messages should be
 * based.
 * 
 * @author mthomas
 * @author bruce
 * @since 6.5
 */
public abstract class RemoteOperationMessage extends AbstractOperationMessage
    implements MessageWithReply {

  /** default exception to ensure a false-positive response is never returned */
  static final ForceReattemptException UNHANDLED_EXCEPTION
     = (ForceReattemptException)new ForceReattemptException(LocalizedStrings
         .PartitionMessage_UNKNOWN_EXCEPTION.toLocalizedString())
             .fillInStackTrace();

  protected String regionPath;

  public RemoteOperationMessage() {
  }

  public RemoteOperationMessage(InternalDistributedMember recipient,
      LocalRegion r, ReplyProcessor21 processor, TXStateInterface tx) {
    super(tx);
    Assert.assertTrue(recipient != null,
        "PartitionMesssage recipient can not be null");
    setRecipient(recipient);
    this.regionPath = r.getFullPath();
    this.processorId = processor == null ? 0 : processor.getProcessorId();
    if (processor != null && this.isSevereAlertCompatible()) {
      processor.enableSevereAlertProcessing();
    }
    // note that Operation above may not be reliable but just gives an idea of
    // the type of operation i.e. read or write which is enough for
    // IndexUpdater.hasRemoteOperations
    //initProcessorType(r, op);

    // check for TX match with that in thread-local
    final TXStateInterface currentTX;
    assert tx == (currentTX = TXManagerImpl.getCurrentTXState()):
        "unexpected mismatch of current TX " + currentTX
            + ", and TX passed to message " + tx;
  }

  public RemoteOperationMessage(Set<?> recipients, LocalRegion r,
      ReplyProcessor21 processor, TXStateInterface tx) {
    super(tx);
    if (recipients != null) {
      setRecipients(recipients);
    }
    this.regionPath = r.getFullPath();
    this.processorId = processor == null ? 0 : processor.getProcessorId();
    if (processor != null && this.isSevereAlertCompatible()) {
      processor.enableSevereAlertProcessing();
    }
    // note that Operation above may not be reliable but just gives an idea of
    // the type of operation i.e. read or write which is enough for
    // IndexUpdater.hasRemoteOperations
    //initProcessorType(r, op);

    // check for TX match with that in thread-local
    final TXStateInterface currentTX;
    assert tx == (currentTX = TXManagerImpl.getCurrentTXState()):
        "unexpected mismatch of current TX " + currentTX
            + ", and TX passed to message " + tx;
  }

  /**
   * Copy constructor that initializes the fields declared in this class
   * @param other
   */
  public RemoteOperationMessage(RemoteOperationMessage other) {
    super(other);
    this.regionPath = other.regionPath;
  }

  /**
   * Severe alert processing enables suspect processing at the ack-wait-threshold
   * and issuing of a severe alert at the end of the ack-severe-alert-threshold.
   * Some messages should not support this type of processing
   * (e.g., GII, or DLockRequests)
   * @return whether severe-alert processing may be performed on behalf
   * of this message
   */
  @Override
  public boolean isSevereAlertCompatible() {
    return true;
  }

  protected int getMessageProcessorType() {
    // don't use SERIAL_EXECUTOR if we may have to wait for a pending TX
    // else if may deadlock as the p2p msg reader thread will be blocked
    // also don't use SERIAL_EXECUTOR for putAll
    // if this thread is a pool processor thread then use WAITING_POOL_EXECUTOR
    // to avoid deadlocks (#42459, #44913)
    /*
    final IndexUpdater indexUpdater;
    if (this.pendingTXId != null || op.isPutAll()
        || ((indexUpdater = r.getIndexUpdater()) != null && indexUpdater
            .avoidSerialExecutor(op))) {
      // ordering is not an issue here since these ops are always transactional
      this.processorType = DistributionManager.PARTITIONED_REGION_EXECUTOR;
    }
    */
    return DistributionManager.PARTITIONED_REGION_EXECUTOR;
  }

  @Override
  public int getProcessorType() {
    return this.processorType == 0 ? getMessageProcessorType()
        : this.processorType;
  }

  @Override
  public final void setProcessorType(boolean isReaderThread) {
    if (isReaderThread) {
      this.processorType = DistributionManager.WAITING_POOL_EXECUTOR;
    }
  }

  /**
   * @return the full path of the region
   */
  public final String getRegionPath() {
    return regionPath;
  }

  /**
   * @param processorId1
   *          the
   *          {@link com.gemstone.gemfire.distributed.internal.ReplyProcessor21}
   *          id associated with the message, null if no acknowlegement is
   *          required.
   */
  public final void registerProcessor(int processorId1) {
    this.processorId = processorId1;
  }

  /**
   * check to see if the cache is closing
   */
  final public boolean checkCacheClosing(DistributionManager dm) {
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    // return (cache != null && cache.isClosed());
    return cache == null || cache.isClosed();
  }

  /**
   * check to see if the distributed system is closing
   * 
   * @return true if the distributed system is closing
   */
  final public boolean checkDSClosing(DistributionManager dm) {
    InternalDistributedSystem ds = dm.getSystem();
    return (ds == null || ds.isDisconnecting());
  }

  /**
   * Upon receipt of the message, both process the message and send an
   * acknowledgement, not necessarily in that order. Note: Any hang in this
   * message may cause a distributed deadlock for those threads waiting for an
   * acknowledgement.
   * 
   * @throws PartitionedRegionException
   *           if the region does not exist (typically, if it has been
   *           destroyed)
   */
  @Override
  protected final void basicProcess(final DistributionManager dm) {
    Throwable thr = null;
    LogWriterI18n logger = null;
    boolean sendReply = true;
    LocalRegion r = null;
    long startTime = 0;    
    try {
      logger = dm.getLoggerI18n();
      if (checkCacheClosing(dm) || checkDSClosing(dm)) {
        thr = new CacheClosedException(
            LocalizedStrings.PartitionMessage_REMOTE_CACHE_IS_CLOSED_0
                .toLocalizedString(dm.getId()));
        return;
      }
      GemFireCacheImpl gfc = GemFireCacheImpl.getExisting();
      r = gfc.getRegionByPathForProcessing(this.regionPath);
      if (r == null && failIfRegionMissing()) {
        // if the distributed system is disconnecting, don't send a reply saying
        // the partitioned region can't be found (bug 36585)
        thr = new ForceReattemptException(LocalizedStrings
            .PartitionMessage_0_COULD_NOT_FIND_PARTITIONED_REGION_WITH_ID_1
                .toLocalizedString(new Object[] {
                    dm.getDistributionManagerId(), regionPath }));
        return;  // reply sent in finally block below
      }

      thr = UNHANDLED_EXCEPTION;

      if (getTXId() == null) {
        sendReply = operateOnRegion(dm, r, startTime);
      }
      else {
        // [bruce] r might be null here, so we have to go to the cache instance
        // to get the txmgr
        final TXManagerImpl txMgr = GemFireCacheImpl.getInstance()
            .getTxManager();
        final TXManagerImpl.TXContext context = txMgr.masqueradeAs(this, false,
            true);
        try {
          sendReply = operateOnRegion(dm, r, startTime);
        } finally {
          txMgr.unmasquerade(context, true);
        }
      }
      thr = null;

    } catch (RemoteOperationException fre) {
      thr = fre;
    }
    catch (DistributedSystemDisconnectedException se) {
      // bug 37026: this is too noisy...
//      throw new CacheClosedException("remote system shutting down");
//      thr = se; cache is closed, no point trying to send a reply
      thr = null;
      sendReply = false;
      if (logger.fineEnabled()) {
        logger.fine("shutdown caught, abandoning message: " + se);
      }
    }
    catch (RegionDestroyedException rde) {
//      if (logger.fineEnabled()) {
//        logger.fine("Region is Destroyed " + rde);
//      }
      // [bruce] RDE does not always mean that the sender's region is also
      //         destroyed, so we must send back an exception.  If the sender's
      //         region is also destroyed, who cares if we send it an exception
      //if (pr != null && pr.isClosed) {
      thr = new ForceReattemptException(LocalizedStrings
          .PartitionMessage_REGION_IS_DESTROYED_IN_0.toLocalizedString(dm
              .getDistributionManagerId()), rde);
      //}
    }
    catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      // log the exception at fine level if there is no reply to the message
      thr = null;
      if (sendReply) {
        if (!checkDSClosing(dm)) {
          thr = t;
        }
        else {
          // don't pass arbitrary runtime exceptions and errors back if this
          // cache/vm is closing
          thr = new ForceReattemptException(LocalizedStrings
              .PartitionMessage_DISTRIBUTED_SYSTEM_IS_DISCONNECTING
                  .toLocalizedString());
        }
      }
      if (logger.fineEnabled()) {
        if (DistributionManager.VERBOSE && (t instanceof RuntimeException)) {
          logger.fine("Exception caught while processing message", t);
        }
      }
    }
    finally {
      if (sendReply) {
        ReplyException rex = null;
        
        if (thr != null) {
          // don't transmit the exception if this message was to a listener
          // and this listener is shutting down
            rex = new ReplyException(thr);
        }

        // Send the reply if the operateOnPartitionedRegion returned true
        sendReply(getSender(), this.processorId, dm, rex, r, startTime);
      } 
    }
  }

  /**
   * Send a generic ReplyMessage. This is in a method so that subclasses can
   * override the reply message type
   * 
   * @param pr
   *          the Partitioned Region for the message whose statistics are
   *          incremented
   * @param startTime
   *          the start time of the operation in nanoseconds
   * @see PutMessage#sendReply
   */
  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      ReplyException ex, LocalRegion pr, long startTime) {
    if (pr != null && startTime > 0) {
      // pr.getPrStats().endRemoteOperationMessagesProcessing(startTime);
    }
    ReplyMessage.send(member, procId, ex, getReplySender(dm), this);
  }

  /**
   * Allow classes that over-ride to choose whether a RegionDestroyException is
   * thrown if no partitioned region is found (typically occurs if the message
   * will be sent before the PartitionedRegion has been fully constructed.
   * 
   * @return true if throwing a {@link RegionDestroyedException} is acceptable
   */
  protected boolean failIfRegionMissing() {
    return true;
  }

  /**
   * return a new reply processor for this class, for use in relaying a
   * response. This <b>must</b> be an instance method so subclasses can override
   * it properly.
   */
  RemoteOperationResponse createReplyProcessor(PartitionedRegion r,
      Set recipients) {
    return new RemoteOperationResponse(r.getSystem(), recipients);
  }

  protected abstract boolean operateOnRegion(DistributionManager dm,
      LocalRegion r, long startTime) throws RemoteOperationException;

  /**
   * Fill out this instance of the message using the <code>DataInput</code>
   * Required to be a {@link com.gemstone.gemfire.DataSerializable}Note: must be
   * symmetric with {@link #toData(DataOutput)} in what it reads
   */
  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException
  {
    super.fromData(in);
    this.regionPath = DataSerializer.readString(in);
  }

  /**
   * Send the contents of this instance to the DataOutput Required to be a
   * {@link com.gemstone.gemfire.DataSerializable}Note: must be symmetric with
   * {@link #fromData(DataInput)}in what it writes
   */
  @Override
  public void toData(DataOutput out) throws IOException
  {
    super.toData(out);
    DataSerializer.writeString(this.regionPath,out);
  }

  /**
   * Helper class of {@link #toString()}
   * 
   * @param buff
   *          buffer in which to append the state of this instance
   */
  @Override
  protected void appendFields(StringBuilder buff) {
    // className.substring(className.lastIndexOf('.', className.lastIndexOf('.')
    // - 1) + 1);  // partition.<foo> more generic version
    //buff.append(className.substring(className.indexOf(PN_TOKEN) +
    //PN_TOKEN.length())); // partition.<foo>
    buff.append("; regionPath="); // make sure this is the first one
    buff.append(this.regionPath);
    buff.append("; sender=").append(getSender());
    buff.append("; recipients=[");
    InternalDistributedMember[] recips = getRecipients();
    for(int i=0; i<recips.length-1; i++) {
      buff.append(recips[i]).append(',');
    }
    if (recips.length > 0) {
      buff.append(recips[recips.length-1]);
    }
    buff.append(']');
  }

  public InternalDistributedMember getRecipient() {
    return getRecipients()[0];
  }
  
  public void setOperation(Operation op) {
    // override in subclasses holding operations
  }
  
  /**
   * added to support old value to be written on wire.
   * @param value true or false
   * @since 6.5
   */
  public void setHasOldValue(boolean value) {
    // override in subclasses which need old value to be serialized.
    // overridden by classes like PutMessage, DestroyMessage.
  }

  /**
   * A processor on which to await a response from the
   * {@link RemoteOperationMessage} recipient, capturing any CacheException
   * thrown by the recipient and handle it as an expected exception.
   * 
   * @author Greg Passmore
   * @since 6.5
   * @see #waitForCacheException()
   */
  public static class RemoteOperationResponse extends DirectReplyProcessor {
    /**
     * The exception thrown when the recipient does not reply
     */
    volatile ForceReattemptException prce;
    
    /**
     * Whether a response has been received
     */
    volatile boolean responseReceived;
    
    /**
     * whether a response is required
     */
    boolean responseRequired;

    volatile THashMap exceptions;

    public RemoteOperationResponse(InternalDistributedSystem dm,
        Set<?> initMembers) {
      this(dm, initMembers, true);
    }

    public RemoteOperationResponse(InternalDistributedSystem dm,
        Set<?> initMembers, boolean register) {
      super(dm, initMembers);
      if (register) {
        register();
      }
    }

    public RemoteOperationResponse(InternalDistributedSystem dm,
        InternalDistributedMember member) {
      this(dm, member, true);
    }

    public RemoteOperationResponse(InternalDistributedSystem dm,
        InternalDistributedMember member, boolean register) {
      super(dm, member);
      if (register) {
        register();
      }
    }

    /**
     * require a response message to be received
     */
    public void requireResponse() {
      this.responseRequired = true;
    }

    @Override
    public void memberDeparted(final InternalDistributedMember id,
        final boolean crashed) {
      if (id != null) {
        if (removeMember(id, true)) {
          this.prce = new ForceReattemptException(LocalizedStrings
              .PartitionMessage_PARTITIONRESPONSE_GOT_MEMBERDEPARTED_EVENT_FOR_0_CRASHED_1
                  .toLocalizedString(new Object[] { id,
                      Boolean.valueOf(crashed) }));
        }
        checkIfDone();
      }
      else {
        Exception e = new Exception(
            LocalizedStrings.PartitionMessage_MEMBERDEPARTED_GOT_NULL_MEMBERID
                .toLocalizedString());
        getDistributionManager().getLoggerI18n().info(LocalizedStrings
            .PartitionMessage_MEMBERDEPARTED_GOT_NULL_MEMBERID_CRASHED_0,
                Boolean.valueOf(crashed), e);
      }
    }

    /**
     * Waits for the response from the {@link RemoteOperationMessage}'s
     * recipient
     * 
     * @throws CacheException
     *           if the recipient threw a cache exception during message
     *           processing
     * @throws ForceReattemptException
     *           if the recipient left the distributed system before the
     *           response was received.
     * @throws PrimaryBucketException
     */
    final public void waitForCacheException() throws CacheException,
        RemoteOperationException, PrimaryBucketException {
      try {
        waitForRepliesUninterruptibly();
        if (this.prce != null
            || (this.responseRequired && !this.responseReceived)) {
          throw new RemoteOperationException(
              LocalizedStrings.PartitionMessage_ATTEMPT_FAILED
                  .toLocalizedString(),
              this.prce);
        }
      } catch (ReplyException e) {
        Throwable t = e.getCause();
        if (t instanceof CacheException) {
          throw (CacheException)t;
        }
        else if (t instanceof RemoteOperationException) {
          RemoteOperationException ft = (RemoteOperationException)t;
          // See FetchEntriesMessage, which can marshal a ForceReattempt
          // across to the sender
          RemoteOperationException fre = new RemoteOperationException(
              LocalizedStrings.PartitionMessage_PEER_REQUESTS_REATTEMPT
                  .toLocalizedString(),
              t);
          if (ft.hasHash()) {
            fre.setHash(ft.getHash());
          }
          throw fre;
        }
        else if (t instanceof PrimaryBucketException) {
          // See FetchEntryMessage, GetMessage, InvalidateMessage,
          // PutMessage
          // which can marshal a ForceReattemptacross to the sender
          throw new PrimaryBucketException(
              LocalizedStrings.PartitionMessage_PEER_FAILED_PRIMARY_TEST
                  .toLocalizedString(),
              t);
        }
        else if (t instanceof CancelException) {
          if (getDistributionManager().getLoggerI18n().fineEnabled()) {
            getDistributionManager().getLoggerI18n().fine(
                "RemoteOperationResponse got CacheClosedException from "
                    + e.getSender() + ", throwing ForceReattemptException", t);
          }
          throw new RemoteOperationException(LocalizedStrings
              .PartitionMessage_PARTITIONRESPONSE_GOT_REMOTE_CACHECLOSEDEXCEPTION
                  .toLocalizedString(), t);
        }
        else if (t instanceof LowMemoryException) {
          if (getDistributionManager().getLoggerI18n().fineEnabled()) {
            getDistributionManager().getLoggerI18n().fine(
                "RemoteOperationResponse re-throwing remote "
                    + "LowMemoryException from " + e.getSender(), t);
          }
          throw (LowMemoryException)t;
        }
        e.handleAsUnexpected();
      }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public final Map<InternalDistributedMember, ReplyException> getExceptions() {
      if (this.exceptions != null) {
        synchronized (this) {
          return new THashMap((Map)this.exceptions);
        }
      }
      return null;
    }

    @Override
    protected final boolean stopBecauseOfExceptions() {
      // do not stop in transactions since we have to ensure that commit or
      // rollback is fired only after everything is done
      return false;
    }

    @Override
    protected final synchronized void processException(
        final DistributionMessage msg, final ReplyException ex) {
      if (this.exceptions == null) {
        this.exceptions = new THashMap();
      }
      this.exceptions.put(msg.getSender(), ex);
      super.processException(msg, ex);
    }

    /* overridden from ReplyProcessor21 */
    @Override
    public void process(DistributionMessage msg) {
      this.responseReceived = true;
      super.process(msg);
    }
  }
}
