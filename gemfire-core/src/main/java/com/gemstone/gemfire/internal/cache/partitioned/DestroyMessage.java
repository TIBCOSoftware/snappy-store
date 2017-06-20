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
package com.gemstone.gemfire.internal.cache.partitioned;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
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
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.Breadcrumbs;

/**
 * A class that specifies a destroy operation.
 * 
 * Note: The reason for different classes for Destroy and Invalidate is to
 * prevent sending an extra bit for every DestroyMessage to differentiate an
 * invalidate versus a destroy. The assumption is that these operations are used
 * frequently, if they are not then it makes sense to fold the destroy and the
 * invalidate into the same message and use an extra bit to differentiate
 * 
 * @author mthomas
 * @author bruce
 * @since 5.0
 *  
 */
public class DestroyMessage extends PartitionMessageWithDirectReply {
  
  /** The key associated with the value that must be sent */
  private Object key;

  /** The callback arg */
  private Object cbArg;

  /** The operation performed on the sender */
  private Operation op;

  /** An additional object providing context for the operation, e.g., for BridgeServer notification */
  ClientProxyMembershipID bridgeContext;
  
  /** event identifier */
  EventID eventId;
  
  /** for relayed messages, this is the original sender of the message */
  InternalDistributedMember originalSender;
  
  /** expectedOldValue used for PartitionedRegion#remove(key, value) */
  private Object expectedOldValue; // TODO OFFHEAP make it a cd
  
  /** client routing information for notificationOnly=true messages */
  protected FilterRoutingInfo filterInfo;

  protected VersionTag versionTag;

  private static final byte HAS_VERSION_TAG = 0x01;
  private static final byte PERSISTENT_TAG = 0x02;
  
  /**
   * True if CacheWriter has to be invoked else false. Currently only used by
   * transactional ops.
   */
  private boolean cacheWrite;

  // additional bitmask flags used for serialization/deserialization

  protected static final short CACHE_WRITE = UNRESERVED_FLAGS_START;

  /**
   * Empty constructor to satisfy {@link DataSerializer} requirements
   */
  public DestroyMessage() {
  }

  protected DestroyMessage(InternalDistributedMember recipient,
                           boolean notifyOnly,
                           int regionId,
                           DirectReplyProcessor processor,
                           EntryEventImpl event,
                           TXStateInterface tx,
                           Object expectedOldValue,
                           boolean cacheWrite) {
    super(recipient, regionId, processor, tx);
    this.posDup = event.isPossibleDuplicate();
    initialize(notifyOnly, event, expectedOldValue, cacheWrite);
  }

  protected DestroyMessage(Set recipients,
                           boolean notifyOnly,
                           int regionId,
                           DirectReplyProcessor processor,
                           EntryEventImpl event,
                           TXStateInterface tx,
                           Object expectedOldValue,
                           boolean cacheWrite) {
    super(recipients, regionId, processor, event, tx);
    initialize(notifyOnly, event, expectedOldValue, cacheWrite);
  }

  private void initialize(boolean notifyOnly, EntryEventImpl event,
      Object expectedOldValue, boolean cacheWrite) {
    this.expectedOldValue = expectedOldValue;
    this.cacheWrite = cacheWrite;
    this.key = event.getKey();
    this.cbArg = event.getRawCallbackArgument();
    this.op = event.getOperation();
    this.notificationOnly = notifyOnly;
    this.bridgeContext = event.getContext();
    this.eventId = event.getEventId();
    this.versionTag = event.getVersionTag();
  }

  /** a cloning constructor for relaying the message to listeners */
  DestroyMessage(DestroyMessage original, EntryEventImpl event, Set members) {
    this(original);
    if (event != null) {
      this.posDup = event.isPossibleDuplicate();
      this.versionTag = event.getVersionTag();
    }
  }

  /** a cloning constructor for relaying the message to listeners */
  DestroyMessage(DestroyMessage original) {
    this.expectedOldValue = original.expectedOldValue;
    this.regionId = original.regionId;
    this.processorId = original.processorId;
    this.key = original.key;
    this.cbArg = original.cbArg;
    this.op = original.op;
    this.notificationOnly = true;
    this.bridgeContext = original.bridgeContext;
    this.originalSender = original.getSender();
//    Assert.assertTrue(original.eventId != null); bug #47235 - region invalidation has no event id, so this fails
    this.eventId = original.eventId;
    this.posDup = original.posDup;
    this.cacheWrite = original.cacheWrite;
    this.versionTag = original.versionTag;
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  /**
   * send a notification-only message to a set of listeners.  The processor
   * id is passed with the message for reply message processing.  This method
   * does not wait on the processor.
   * 
   * @param cacheOpReceivers receivers of associated bucket CacheOperationMessage
   * @param adjunctRecipients receivers that must get the event
   * @param filterRoutingInfo client routing information
   * @param r the region affected by the event
   * @param event the event that prompted this action
   * @param processor the processor to reply to
   * @return members that could not be notified
   */
  public static Set notifyListeners(Set cacheOpReceivers, Set adjunctRecipients,
      FilterRoutingInfo filterRoutingInfo, 
      PartitionedRegion r, EntryEventImpl event, 
      DirectReplyProcessor processor) {
    final DestroyMessage msg = new DestroyMessage(Collections.EMPTY_SET, true,
        r.getPRId(), processor, event, event.getTXState(r), null, true);
    msg.versionTag = event.getVersionTag();
    return msg.relayToListeners(cacheOpReceivers, adjunctRecipients,
        filterRoutingInfo, event, r, processor);
  }

  /**
   * Sends a DestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)}message to the
   * recipient
   * 
   * @param recipient the recipient of the message
   * @param r
   *          the PartitionedRegion for which the destroy was performed
   * @param event the event causing this message
   * @return the processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   * @throws ForceReattemptException if the peer is no longer available
   */
  public static DestroyResponse send(final InternalDistributedMember recipient,
                                     PartitionedRegion r,
                                     EntryEventImpl event,
                                     Object expectedOldValue,
                                     final boolean cacheWrite) 
  throws ForceReattemptException {
    // recipient may be null for event notification
    //Assert.assertTrue(recipient != null, "DestroyMessage NULL recipient");

    final DestroyMessage m = prepareSend(r.getSystem(), recipient, r, event,
        expectedOldValue, cacheWrite);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new ForceReattemptException(LocalizedStrings
          .DestroyMessage_FAILED_SENDING_0.toLocalizedString(m));
    }
    return (DestroyResponse)m.processor;
  }

  /**
   * Prepares a DestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)} for sending to
   * the recipient
   * 
   * @param sys
   *          the current distributed system
   * @param recipient
   *          the recipient of the message
   * @param r
   *          the PartitionedRegion for which the destroy was performed
   * @param event
   *          the event causing this message
   * 
   * @return the message created
   */
  public static DestroyMessage prepareSend(final InternalDistributedSystem sys,
      final InternalDistributedMember recipient, final PartitionedRegion r,
      final EntryEventImpl event, final Object expectedOldValue,
      final boolean cacheWrite) {
    // recipient may be null for event notification
    //Assert.assertTrue(recipient != null, "DestroyMessage NULL recipient");

    final TXStateInterface tx = event.getTXState(r);
    final DestroyResponse p = new DestroyResponse(sys, recipient, tx);
    p.requireResponse();
    DestroyMessage m = new DestroyMessage(recipient,
                                          false,
                                          r.getPRId(),
                                          p,
                                          event,
                                          tx,
                                          expectedOldValue,
                                          cacheWrite);
    return m;
  }

  /**
   * Sends a DestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)}message to the
   * recipients
   * 
   * @param recipients the recipients of the message
   * @param r
   *          the PartitionedRegion for which the destroy was performed
   * @param event the event causing this message
   * @return the processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   * @throws ForceReattemptException if the peer is no longer available
   */
  public static DestroyResponse send(final Set recipients,
                                     PartitionedRegion r,
                                     EntryEventImpl event,
                                     Object expectedOldValue,
                                     boolean cacheWrite) 
  throws ForceReattemptException {

    final TXStateInterface tx = event.getTXState(r);
    final DestroyResponse p = new DestroyResponse(r.getSystem(),
        recipients, tx);
    p.requireResponse();
    DestroyMessage m = new DestroyMessage(recipients,
                                          false,
                                          r.getPRId(),
                                          p,
                                          event,
                                          tx,
                                          expectedOldValue,
                                          cacheWrite);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new ForceReattemptException(LocalizedStrings
          .DestroyMessage_FAILED_SENDING_0.toLocalizedString(m));
    }
    return p;
  }

  @Override
  public PartitionMessage getMessageForRelayToListeners(EntryEventImpl event, Set members) {
    DestroyMessage msg = new DestroyMessage(this, event, members );
    //Asif: Old value needs to be sent to nodes having db synch or async event listener
    //Fix for 43000 - don't send the expected old value to listeners.
    msg.expectedOldValue = null;
    return msg;
  }
  
  /**
   * relay this message to another set of recipients for event notification
   * 
   * @param cacheOpRecipients
   *          recipients of associated bucket CacheOperationMessage
   * @param adjunctRecipients
   *          recipients who unconditionally get the message
   * @param filterRoutingInfo
   *          routing information for all recipients
   * @param event
   *          the event causing this message
   * @param r
   *          the region being operated on
   * @param processor
   *          the reply processor to be notified
   */
  @Override
  public Set relayToListeners(Set cacheOpRecipients, Set adjunctRecipients,

  FilterRoutingInfo filterRoutingInfo, EntryEventImpl event,
      PartitionedRegion r, DirectReplyProcessor processor) {
    Set failures1 = null;
    Set gfxdAsyncListenerRecepients = r.getRegionAdvisor()
        .adviseDBSynchOrAsyncListenerMembers();
    gfxdAsyncListenerRecepients.retainAll(adjunctRecipients);
    // Now remove those adjunct recepients which are present in
    // GfxdAsyncListenerRecepients
    adjunctRecipients.removeAll(gfxdAsyncListenerRecepients);
    failures1 = super.relayToListeners(cacheOpRecipients, adjunctRecipients,
        filterRoutingInfo, event, r, processor);
    if (!gfxdAsyncListenerRecepients.isEmpty()) {
      this.expectedOldValue = event.getOldValue();
      Set failures2 = super.relayToListeners(cacheOpRecipients,
          gfxdAsyncListenerRecepients, filterRoutingInfo, event, r, processor);
      if (failures1 != null && failures2 != null) {
        failures1.addAll(failures2);
      } else if (failures2 != null) {
        failures1 = failures2;
      }
    }
    return failures1;
  }

  /**
   * This method is called upon receipt and make the desired changes to the
   * PartitionedRegion Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgement
   */
  @Override
  protected boolean operateOnPartitionedRegion(DistributionManager dm,
      PartitionedRegion r, long startTime)
      throws EntryExistsException, DataLocationException
  {

    LogWriterI18n l = dm.getLoggerI18n();
//    if (DistributionManager.VERBOSE) {
//      l.fine("DistributedDestroyMessage operateOnRegion: " + r.getFullPath());
//    }

    
    InternalDistributedMember eventSender = originalSender;
    if (eventSender == null) {
       eventSender = getSender();
    }
    EntryEventImpl event = null;
    try {
    if (r.keyRequiresRegionContext()) {
      ((KeyWithRegionContext)this.key).setRegionContext(r);
    }
    if (this.bridgeContext != null) {
      event = EntryEventImpl.create(r, getOperation(), this.key, null/*newValue*/,
          getCallbackArg(), false/*originRemote*/, eventSender, 
          true/*generateCallbacks*/);
      event.setContext(this.bridgeContext);
    } // bridgeContext != null
    else {
      event = EntryEventImpl.create(
        r,
        getOperation(),
        this.key,
        null, /*newValue*/
        getCallbackArg(),
        false/*originRemote - false to force distribution in buckets*/,
        eventSender,
        true/*generateCallbacks*/,
        false/*initializeId*/);
    }
    if (this.versionTag != null) {
      this.versionTag.replaceNullIDs(getSender());
      event.setVersionTag(this.versionTag);
    }
    event.setInvokePRCallbacks(!notificationOnly);
    Assert.assertTrue(eventId != null);
    event.setEventId(eventId);
    event.setPossibleDuplicate(this.posDup);
    event.setLockingPolicy(getLockingPolicy());

    final TXStateInterface tx = getTXState(r);
    event.setTXState(tx);
    PartitionedRegionDataStore ds = r.getDataStore();
    boolean sendReply = true;
    
    if (!notificationOnly) {
      if (ds == null) {
        Assert.fail("This process should have storage for an item in "
            + this.toString());
      }
      try {
        r.operationStart();
        Integer bucket = Integer.valueOf(PartitionedRegionHelper.getHashKey(r,
            null, this.key, null, this.cbArg));
//        try {
//          // the event must show its true origin for cachewriter invocation
//          event.setOriginRemote(true);
//          event.setPartitionMessage(this);
//          r.doCacheWriteBeforeDestroy(event);
//        }
//        finally {
//          event.setOriginRemote(false);
//        }
        event.setCausedByMessage(this);
        InternalDataView view = (getLockingPolicy() == LockingPolicy.SNAPSHOT) ?
            r.getSharedDataView() : r.getDataView(tx);

        view.destroyOnRemote(event, this.cacheWrite,
            this.expectedOldValue);
        if (DistributionManager.VERBOSE) {
          l.fine(getClass().getName() + " updated bucket: " + bucket
              + " with key: " + this.key);
        }
      }
      catch (CacheWriterException cwe) {
        sendReply(getSender(), this.processorId, dm, new ReplyException(cwe), r, startTime);
        return false;
      }
      catch (EntryNotFoundException eee) {
        l.fine(getClass().getName() 
            + ": operateOnRegion caught EntryNotFoundException");
        ReplyMessage.send(getSender(), getProcessorId(), 
            new ReplyException(eee), getReplySender(dm), this,
            r.isInternalRegion());
        sendReply = false; // this prevents us from acking later
      }
      catch (PrimaryBucketException pbe) {
        sendReply(getSender(), getProcessorId(), dm, new ReplyException(pbe), r, startTime);
        sendReply = false;

      } finally {
        r.operationCompleted();
        this.versionTag = event.getVersionTag();
      }
    }
    else {
      EntryEventImpl e2 = createListenerEvent(event, r, dm.getDistributionManagerId());
      try {
      r.invokeDestroyCallbacks(EnumListenerEvent.AFTER_DESTROY, e2, r.isInitialized(), true);
      } finally {
        // if e2 == ev then no need to free it here. The outer finally block will get it.
        if (e2 != event) {
          e2.release();
        }
      }
    }

    return sendReply;
    } finally {
      if (event != null) {
        event.release();
      }
    }
  }

  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      ReplyException ex, PartitionedRegion pr, long startTime) {
    if (pr != null && startTime > 0) {
      pr.getPrStats().endPartitionMessagesProcessing(startTime);
    }
    if (ex == null) {
      DestroyReplyMessage.send(getSender(), getReplySender(dm),
          this.processorId, this.versionTag, this,
          pr != null && pr.isInternalRegion());
    }
    else {
      ReplyMessage.send(getSender(), this.processorId, ex, getReplySender(dm),
          this, pr != null && pr.isInternalRegion());
    }
  }

  public int getDSFID() {
    return PR_DESTROY;
  }

  @Override
  public final void fromData(final DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    setKey(DataSerializer.readObject(in));
    this.cbArg = DataSerializer.readObject(in);
    this.op = Operation.fromOrdinal(in.readByte());
    this.bridgeContext = ClientProxyMembershipID.readCanonicalized(in);
    this.originalSender = (InternalDistributedMember)DataSerializer.readObject(in);
    this.eventId = (EventID)DataSerializer.readObject(in);
    this.expectedOldValue = DataSerializer.readObject(in);

    final boolean hasFilterInfo = ((flags & HAS_FILTER_INFO) != 0);
    if (hasFilterInfo) {
      this.filterInfo = new FilterRoutingInfo();
      InternalDataSerializer.invokeFromData(this.filterInfo, in);
    }

    this.versionTag = DataSerializer.readObject(in);
}

  @Override
  public final void toData(final DataOutput out)
      throws IOException {
    super.toData(out);
    DataSerializer.writeObject(getKey(), out);
    DataSerializer.writeObject(this.cbArg, out);
    out.writeByte(this.op.ordinal);
    DataSerializer.writeObject(this.bridgeContext, out);
    DataSerializer.writeObject(this.originalSender, out);
    DataSerializer.writeObject(this.eventId, out);
    DataSerializer.writeObject(this.expectedOldValue, out);

    if (this.filterInfo != null) {
      InternalDataSerializer.invokeToData(this.filterInfo, out);
    }
    DataSerializer.writeObject(this.versionTag, out);    
  }

  @Override
  protected short computeCompressedShort(short s) {
    s = super.computeCompressedShort(s);
    if (this.filterInfo != null) s |= HAS_FILTER_INFO;
    if (this.cacheWrite) s |= CACHE_WRITE;
    return s;
  }

  @Override
  protected void setBooleans(short s) {
    super.setBooleans(s);
    this.cacheWrite = ((s & CACHE_WRITE) != 0);
  }

  @Override
  public EventID getEventID() {
    return this.eventId;
  }

  /** create a new EntryEvent to be used in notifying listeners, bridge servers, etc. */
  EntryEventImpl createListenerEvent(EntryEventImpl sourceEvent, PartitionedRegion r,
      InternalDistributedMember member) {
    final EntryEventImpl e2;
    if (this.notificationOnly && this.bridgeContext == null) {
      e2 = sourceEvent;
    }
    else {
      e2 = new EntryEventImpl(sourceEvent);
      if (this.bridgeContext != null) {
        e2.setContext(this.bridgeContext);
      }
    }
    e2.setRegion(r);
    e2.setOldValue(this.expectedOldValue);
    e2.setOriginRemote(true);
    e2.setInvokePRCallbacks(!notificationOnly);
    if (this.filterInfo != null) {
      e2.setLocalFilterInfo(this.filterInfo.getFilterInfo(member));
    }
    if (this.versionTag != null) {
      this.versionTag.replaceNullIDs(getSender());
      e2.setVersionTag(this.versionTag);
    }
    return e2;
  }

  /**
   * Assists the toString method in reporting the contents of this message
   * 
   * @see PartitionMessage#toString()
   */
  @Override
  protected void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; key=").append(getKey());
    if (originalSender != null) {
      buff.append("; originalSender=").append(originalSender);
    }
    if (this.cbArg != null) {
      buff.append("; callbackArg=").append(this.cbArg);
    }
    if (bridgeContext != null) {
      buff.append("; bridgeContext=").append(bridgeContext);
    }
    if (eventId != null) {
      buff.append("; eventId=").append(eventId);
    }
    if (this.versionTag != null) {
      buff.append("; version=").append(this.versionTag);
    }
    if (filterInfo != null) {
      buff.append("; ").append(filterInfo);
    }
    buff.append("; cacheWrite=").append(this.cacheWrite);
  }

  protected final Object getKey()
  {
    return this.key;
  }

  private final void setKey(Object key)
  {
    this.key = key;
  }

  public final Operation getOperation()
  {
    return this.op;
  }

  protected final Object getCallbackArg()
  {
    return this.cbArg;
  }
  
  @Override
  public void setFilterInfo(FilterRoutingInfo filterInfo){
    if (filterInfo != null){
      this.filterInfo = filterInfo;
    }
  }

  public static class DestroyReplyMessage extends ReplyMessage {
    private VersionTag versionTag;

    /** DSFIDFactory constructor */
    public DestroyReplyMessage() {
    }

    static void send(InternalDistributedMember recipient, ReplySender dm,
        int procId, VersionTag versionTag, AbstractOperationMessage srcMessage,
        boolean internal) {
      Assert
          .assertTrue(recipient != null, "DestroyReplyMessage NULL recipient");
      DestroyReplyMessage m = new DestroyReplyMessage(recipient, procId,
          versionTag, srcMessage);
      m.internal = internal;
      dm.putOutgoing(m);
    }

    DestroyReplyMessage(InternalDistributedMember recipient, int procId,
        VersionTag versionTag, AbstractOperationMessage srcMessage) {
      super(srcMessage, true, true);
      this.setProcessorId(procId);
      this.setRecipient(recipient);
      this.versionTag = versionTag;
    }

    @Override
    public int getDSFID() {
      return PR_DESTROY_REPLY_MESSAGE;
    }

    @Override
    public void process(final DM dm, final ReplyProcessor21 rp) {
      final long startTime = getTimestamp();
      LogWriterI18n l = dm.getLoggerI18n();
      if (DistributionManager.VERBOSE) {
        l.fine("DestroyReplyMessage process invoking reply processor with processorId:" + this.processorId);
      }
      //dm.getLogger().warning("RemotePutResponse processor is " + ReplyProcessor21.getProcessor(this.processorId));
      if (rp == null) {
        if (DistributionManager.VERBOSE) {
          l.fine("DestroyReplyMessage processor not found");
        }
        return;
      }
      if (this.versionTag != null) {
        this.versionTag.replaceNullIDs(getSender());
      }
      if (rp instanceof DestroyResponse) {
        DestroyResponse processor = (DestroyResponse)rp;
        if (this.versionTag != null) {
          this.versionTag.replaceNullIDs(this.getSender());
        }
        processor.setResponse(this.versionTag);
      }
      rp.process(this);

      if (DistributionManager.VERBOSE) {
        LogWriterI18n logger = dm.getLoggerI18n();
        logger.info(LocalizedStrings.RemotePutMessage_0__PROCESSED__1, new Object[] {rp, this});
      }
      dm.getStats().incReplyMessageTime(NanoTimer.getTime()-startTime);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      byte b = this.versionTag != null ? HAS_VERSION_TAG : 0;
      b |= this.versionTag instanceof DiskVersionTag ? PERSISTENT_TAG : 0; 
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
      boolean hasTag = (b & HAS_VERSION_TAG) != 0;
      boolean persistentTag = (b & PERSISTENT_TAG) != 0;
      if (hasTag) {
        this.versionTag = VersionTag.create(persistentTag, in);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = super.getStringBuilder();
      if (this.versionTag != null) {
        sb.append(" version=").append(this.versionTag);
      }
      sb.append(" from ");
      sb.append(this.getSender());
      ReplyException ex = getException();
      if (ex != null) {
        sb.append(" with exception ");
        sb.append(ex);
      }
      return sb.toString();
    }

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.distributed.internal.ReplyMessage#getInlineProcess()
     */
    @Override
    public boolean getInlineProcess() {
      return true;
    }

  }

  public static class DestroyResponse extends PartitionResponse {

    VersionTag versionTag;

    DestroyResponse(InternalDistributedSystem ds,
        InternalDistributedMember recipient, final TXStateInterface tx) {
      super(ds, recipient, false, tx);
    }

    DestroyResponse(InternalDistributedSystem ds, Set recipients,
        final TXStateInterface tx) {
      super(ds, recipients, false, tx);
    }

    void setResponse(VersionTag versionTag) {
      this.versionTag = versionTag;
    }

    public VersionTag getVersionTag() {
      return this.versionTag;
    }
  }
}
