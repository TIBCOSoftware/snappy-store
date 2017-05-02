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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ReplySender;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import static com.gemstone.gemfire.internal.cache.DistributedCacheOperation.VALUE_IS_BYTES;
import static com.gemstone.gemfire.internal.cache.DistributedCacheOperation.VALUE_IS_SERIALIZED_OBJECT;

public final class RemoteInvalidateMessage extends RemoteDestroyMessage {

  /**
   * Empty constructor to satisfy {@link com.gemstone.gemfire.DataSerializer}
   * requirements
   */
  public RemoteInvalidateMessage() {
  }

  private RemoteInvalidateMessage(InternalDistributedMember recipient,
                            boolean notifyOnly,
                            LocalRegion r,
                            DirectReplyProcessor processor,
                            EntryEventImpl event,
                            boolean useOriginRemote,
                            boolean possibleDuplicate) {
    super(recipient,
          notifyOnly,
          r,
          processor,
          event,
          null, // expectedOldValue
          false,
          useOriginRemote,
          possibleDuplicate);
  }

  private RemoteInvalidateMessage(Set<?> recipients,
                            boolean notifyOnly,
                            LocalRegion r,
                            DirectReplyProcessor processor,
                            EntryEventImpl event,
                            boolean useOriginRemote,
                            boolean possibleDuplicate) {
    super(recipients,
          notifyOnly,
          r,
          processor,
          event,
          null, // expectedOldValue
          false,
          useOriginRemote,
          possibleDuplicate);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  public static boolean distribute(EntryEventImpl event, boolean onlyPersistent) {
    boolean successful = false;
    DistributedRegion r = (DistributedRegion)event.getRegion();
    Collection<InternalDistributedMember> replicates = onlyPersistent ? r
        .getCacheDistributionAdvisor().adviseInitializedPersistentMembers()
        .keySet() : r.getCacheDistributionAdvisor()
        .adviseInitializedReplicates();
    if (replicates.isEmpty()) {
      return false;
    }
    LogWriterI18n log = event.getRegion().getLogWriterI18n();
    if (replicates.size() > 1) {
      ArrayList<InternalDistributedMember> l =
          new ArrayList<InternalDistributedMember>(replicates);
      Collections.shuffle(l);
      replicates = l;
    }
    int attempts = 0;
    for (InternalDistributedMember replicate : replicates) {
      try {
        attempts++;
        final boolean posDup = (attempts > 1);
        InvalidateResponse processor = send(replicate, event.getRegion(),
            event, false, posDup);
        processor.waitForCacheException();
        VersionTag<?> versionTag = processor.getVersionTag();
        if (versionTag != null) {
          event.setVersionTag(versionTag);
          final RegionVersionVector rvv = event.getRegion().getVersionVector();
          if (rvv != null) {
            rvv.recordVersion(versionTag.getMemberID(), versionTag, event);
          }
        }
        event.setInhibitDistribution(true);
        return true;

      } catch (TransactionException te) {
        throw te;

      } catch (CancelException e) {
        event.getRegion().getCancelCriterion().checkCancelInProgress(e);

      } catch (EntryNotFoundException e) {
        throw new EntryNotFoundException("" + event.getKey());

      } catch (CacheException e) {
        if (log.fineEnabled()) {
          log.fine(
              "RemoteDestroyMessage caught CacheException during distribution",
              e);
        }
        successful = true; // not a cancel-exception, so don't complain any more
                           // about it

      } catch (RemoteOperationException e) {
        if (DistributionManager.VERBOSE || log.fineEnabled()) {
          log.info(LocalizedStrings.DEBUG, "RemoteDestroyMessage caught an "
              + "unexpected exception during distribution", e);
        }
      }
    }
    return successful;
  }

  /**
   * Sends a RemoteInvalidateMessage
   * {@link com.gemstone.gemfire.cache.Region#invalidate(Object)} message to the
   * given recipient
   * 
   * @param recipient
   *          the recipient of the message
   * @param r
   *          the ReplicateRegion for which the invalidate was performed
   * @param event
   *          the event causing this message
   * @param useOriginRemote
   *          whether the receiver should use originRemote=true in its event
   * 
   * @return the InvalidateResponse processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   */
  public static InvalidateResponse send(
      final InternalDistributedMember recipient, final LocalRegion r,
      final EntryEventImpl event, boolean useOriginRemote,
      boolean possibleDuplicate) throws RemoteOperationException {
    final RemoteInvalidateMessage m = prepareSend(r.getSystem(), recipient, r,
        event, useOriginRemote, possibleDuplicate);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new RemoteOperationException(LocalizedStrings
          .InvalidateMessage_FAILED_SENDING_0.toLocalizedString(m));
    }
    return (InvalidateResponse)m.processor;
  }

  /**
   * Prepares a RemoteInvalidateMessage
   * {@link com.gemstone.gemfire.cache.Region#invalidate(Object)} for sending to
   * the recipient.
   * 
   * @param sys
   *          the distributed system
   * @param recipient
   *          the recipient of the message
   * @param r
   *          the ReplicateRegion for which the invalidate was performed
   * @param event
   *          the event causing this message
   * @param useOriginRemote
   *          whether the receiver should use originRemote=true in its event
   * 
   * @return the prepared RemoteInvalidateMessage
   */
  public static RemoteInvalidateMessage prepareSend(
      final InternalDistributedSystem sys,
      final InternalDistributedMember recipient, final LocalRegion r,
      final EntryEventImpl event, boolean useOriginRemote,
      boolean possibleDuplicate) {
    // recipient may be null for remote notifications
    //Assert.assertTrue(recipient != null, "RemoteInvalidateMessage NULL recipient");

    final InvalidateResponse p = new InvalidateResponse(sys,
        recipient, event.getKey());
    final RemoteInvalidateMessage m = new RemoteInvalidateMessage(recipient,
        false, r, p, event, useOriginRemote, possibleDuplicate);
    return m;
  }

  /**
   * Sends a RemoteInvalidateMessage
   * {@link com.gemstone.gemfire.cache.Region#invalidate(Object)}message to the
   * recipients
   * 
   * @param recipients the recipients of the message
   * @param r
   *          the ReplicateRegion for which the invalidate was performed
   * @param event the event causing this message
   * @param useOriginRemote
   *          whether the receiver should use originRemote=true in its event
   * @return the InvalidateResponse processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   */
  public static InvalidateResponse send(Set<?> recipients, LocalRegion r,
      EntryEventImpl event, boolean useOriginRemote, boolean possibleDuplicate)
      throws RemoteOperationException {
    // recipient may be null for remote notifications
    //Assert.assertTrue(recipient != null, "RemoteInvalidateMessage NULL recipient");

    final InvalidateResponse p = new InvalidateResponse(r.getSystem(),
        recipients, event.getKey());
    final RemoteInvalidateMessage m = new RemoteInvalidateMessage(recipients,
        false, r, p, event, useOriginRemote, possibleDuplicate);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new RemoteOperationException(LocalizedStrings
          .InvalidateMessage_FAILED_SENDING_0.toLocalizedString(m));
    }
    return p;
  }

  /**
   * This method is called upon receipt and make the desired changes to the
   * PartitionedRegion Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgement
   * 
   * @throws EntryExistsException
   * @throws DataLocationException 
   */
  @Override
  protected boolean operateOnRegion(DistributionManager dm,
      LocalRegion r, long startTime)
      throws EntryExistsException, RemoteOperationException
  {
    LogWriterI18n l = r.getCache().getLoggerI18n();
//    if (DistributionManager.VERBOSE) {
//      l.fine(getClass().getName() + " operateOnRegion: " + r.getFullPath());
//    }

    InternalDistributedMember eventSender = originalSender;
    if (eventSender == null) {
       eventSender = getSender();
    }
    final Object key = getKey();
    if (r.keyRequiresRegionContext()) {
      ((KeyWithRegionContext)key).setRegionContext(r);
    }
    final EntryEventImpl event = EntryEventImpl.create(
        r,
        getOperation(),
        key,
        null, /*newValue*/
        getCallbackArg(),
        this.useOriginRemote/*originRemote - false to force distribution in buckets*/,
        eventSender,
        true/*generateCallbacks*/,
        false/*initializeId*/);
    try {
    if (this.bridgeContext != null) {
      event.setContext(this.bridgeContext);
    }

    event.setCausedByMessage(this);

    if (this.versionTag != null) {
      this.versionTag.replaceNullIDs(getSender());
      event.setVersionTag(this.versionTag);
    }

    Assert.assertTrue(eventId != null);
    event.setEventId(eventId);
    event.setLockingPolicy(getLockingPolicy());
    event.setPossibleDuplicate(this.possibleDuplicate);

    // for cqs, which needs old value based on old value being sent on wire.
    boolean eventShouldHaveOldValue = getHasOldValue();
    if (eventShouldHaveOldValue) {
      if (getOldValueIsSerialized() == VALUE_IS_SERIALIZED_OBJECT) {
        event.setSerializedOldValue(getOldValueBytes());
      }
      else if (getOldValueIsSerialized() == VALUE_IS_BYTES) {
        event.setOldValue(getOldValueBytes());
      }
      else {
        event.setOldValue(getOldValObj());
      }
    }

    boolean sendReply = true;
    // boolean failed = false;
    try {
      if (!r.isCacheContentProxy()) {
        final TXStateInterface tx = getTXState(r);
        event.setTXState(tx);

        r.checkReadiness();
        r.checkForLimitedOrNoAccess();

        // if this is a mirrored region and we're still initializing, then
        // force new entry creation
        boolean forceNewEntry = r.dataPolicy.withReplication()
            && !r.isInitialized();
        boolean invokeCallbacks = r.isInitialized();
        r.basicInvalidate(event, invokeCallbacks, forceNewEntry);
        final LogWriterI18n log = r.getCache().getLoggerI18n();
        if (log.finerEnabled() || DistributionManager.VERBOSE) {
          log.finer("RemoteInvalidateMessage.operationOnRegion; key="
              + event.getKey());
        }
      }
      sendReply(getSender(), this.processorId, dm, /*ex*/null, 
          event.getRegion(), event.getVersionTag(), startTime);
      sendReply = false;
    } catch (EntryNotFoundException eee) {
      // failed = true;
      if (l.fineEnabled()) {
        l.fine(getClass().getName()
            + ": operateOnRegion caught EntryNotFoundException");
      }
      sendReply(getSender(), getProcessorId(), dm, new ReplyException(eee), r,
          startTime);
      sendReply = false; // this prevents us from acking later
    } catch (PrimaryBucketException pbe) {
      sendReply(getSender(), getProcessorId(), dm, new ReplyException(pbe),
          r, startTime);
      return false;
    }

    return sendReply;
    } finally {
      event.release();
    }
  }

  // override reply message type from PartitionMessage
  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      ReplyException ex, LocalRegion r, long startTime) {
    sendReply(member, procId, dm, ex, r, null, startTime);
  }

  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      ReplyException ex, LocalRegion r, VersionTag<?> versionTag, long startTime) {
    /*if (pr != null && startTime > 0) {
      pr.getPrStats().endPartitionMessagesProcessing(startTime); 
    }*/
    InvalidateReplyMessage.send(member, procId, getReplySender(dm), versionTag,
        ex, this);
  }

  @Override
  public int getDSFID() {
    return R_INVALIDATE_MESSAGE;
  }
  

  public static final class InvalidateReplyMessage extends ReplyMessage {
    private VersionTag<?> versionTag;

    private static final byte HAS_VERSION = 0x01;
    private static final byte PERSISTENT  = 0x02;

    /**
     * DSFIDFactory constructor
     */
    public InvalidateReplyMessage() {
    }

    private InvalidateReplyMessage(int processorId, VersionTag<?> versionTag,
        ReplyException ex, RemoteInvalidateMessage sourceMessage) {
      super(sourceMessage, true, true);
      setProcessorId(processorId);
      this.versionTag = versionTag;
      setException(ex);
    }

    /** Send an ack */
    public static void send(InternalDistributedMember recipient,
        int processorId, ReplySender replySender, VersionTag<?> versionTag,
        ReplyException ex, RemoteInvalidateMessage sourceMessage) {
      Assert.assertTrue(recipient != null,
          "InvalidateReplyMessage NULL reply message");
      InvalidateReplyMessage m = new InvalidateReplyMessage(processorId,
          versionTag, ex, sourceMessage);
      m.setRecipient(recipient);
      replySender.putOutgoing(m);
    }

    /**
     * Processes this message.  This method is invoked by the receiver
     * of the message.
     * @param dm the distribution manager that is processing the message.
     */
    @Override
    public void process(final DM dm, final ReplyProcessor21 rp) {
      final long startTime = getTimestamp();
      LogWriterI18n l = dm.getLoggerI18n();
      if (DistributionManager.VERBOSE) {
        l.fine("InvalidateReplyMessage process invoking reply processor with processorId:" + this.processorId);
      }
  
      //dm.getLogger().warning("InvalidateResponse processor is " + ReplyProcessor21.getProcessor(this.processorId));
      if (rp == null) {
        if (DistributionManager.VERBOSE) {
          l.fine("InvalidateReplyMessage processor not found");
        }
        return;
      }
      if (this.versionTag != null) {
        this.versionTag.replaceNullIDs(getSender());
      }
      if (rp instanceof InvalidateResponse) {
        InvalidateResponse processor = (InvalidateResponse)rp;
        processor.setResponse(this.versionTag);
      }
      rp.process(this);
  
      if (DistributionManager.VERBOSE) {
        LogWriterI18n logger = dm.getLoggerI18n();
        logger.info(LocalizedStrings.InvalidateMessage_0__PROCESSED__1, new Object[] {rp, this});
      }

      dm.getStats().incReplyMessageTime(NanoTimer.getTime()-startTime);
    }
    
    @Override
    public int getDSFID() {
      return R_INVALIDATE_REPLY_MESSAGE;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      byte b = 0;
      if (this.versionTag != null) {
        b |= HAS_VERSION;
      }
      if (this.versionTag instanceof DiskVersionTag) {
        b |= PERSISTENT;
      }
      out.writeByte(b);
      if (this.versionTag != null) {
        InternalDataSerializer.invokeToData(this.versionTag, out);
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      byte b = in.readByte();
      boolean hasTag = (b & HAS_VERSION) != 0;
      boolean persistentTag = (b & PERSISTENT) != 0;
      if (hasTag) {
        this.versionTag = VersionTag.create(persistentTag, in);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("InvalidateReplyMessage ")
      .append("processorid=").append(this.processorId)
      .append(" exception=").append(getException());
      if (this.versionTag != null) {
        sb.append("version=").append(this.versionTag);
      }
      return sb.toString();
    }
  }

  /**
   * A processor to capture the value returned by {@link RemoteInvalidateMessage}
   * @since 6.5
   */
  public static final class InvalidateResponse extends RemoteOperationResponse {

    private volatile boolean returnValueReceived;
    final Object key;
    VersionTag<?> versionTag;

    public InvalidateResponse(final InternalDistributedSystem ds,
        final InternalDistributedMember recipient, final Object key) {
      super(ds, recipient, false);
      this.key = key;
    }

    public InvalidateResponse(InternalDistributedSystem ds, Set<?> recipients,
        Object key) {
      super(ds, recipients, true);
      this.key = key;
    }

    public void setResponse(VersionTag<?> versionTag) {
      this.returnValueReceived = true;
      this.versionTag = versionTag;
    }

    /**
     * @throws CacheException if the peer generates an error
     */
    public void waitForResult() throws CacheException, RemoteOperationException {
      try {
        waitForCacheException();
      } catch (RemoteOperationException e) {
        e.checkKey(key);
        throw e;
      }
      if (!this.returnValueReceived) {
        throw new RemoteOperationException(LocalizedStrings
            .InvalidateMessage_NO_RESPONSE_CODE_RECEIVED.toLocalizedString());
      }
    }

    public VersionTag<?> getVersionTag() {
      return this.versionTag;
    }
  }
}
