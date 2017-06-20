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
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
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
import com.gemstone.gemfire.internal.cache.EntryEventImpl.OldValueImporter;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_OLD_VALUE;
import static com.gemstone.gemfire.internal.cache.DistributedCacheOperation.VALUE_IS_BYTES;
import static com.gemstone.gemfire.internal.cache.DistributedCacheOperation.VALUE_IS_SERIALIZED_OBJECT;
import static com.gemstone.gemfire.internal.cache.DistributedCacheOperation.VALUE_IS_OBJECT;
import com.gemstone.gemfire.internal.util.Breadcrumbs;

/**
 * A class that specifies a destroy operation.
 * Used by ReplicateRegions.
 * Note: The reason for different classes for Destroy and Invalidate is to
 * prevent sending an extra bit for every RemoteDestroyMessage to differentiate an
 * invalidate versus a destroy. The assumption is that these operations are used
 * frequently, if they are not then it makes sense to fold the destroy and the
 * invalidate into the same message and use an extra bit to differentiate
 * 
 * @since 6.5
 *  
 */
public class RemoteDestroyMessage extends RemoteOperationMessageWithDirectReply implements OldValueImporter {
  
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
  
  /**whether the message has old value */
  private boolean hasOldValue = false;

  /** whether old value is serialized */
  private byte oldValueIsSerialized;

  /** expectedOldValue used for PartitionedRegion#remove(key, value) */
  private Object expectedOldValue; // TODO OFFHEAP make it a cd
  
  private byte[] oldValBytes;
  
  @Unretained(ENTRY_EVENT_OLD_VALUE)
  private transient Object oldValObj;

  /**
   * True if CacheWriter has to be invoked else false. Currently only used by
   * transactional ops.
   */
  private boolean cacheWrite;

  boolean useOriginRemote;

  protected boolean possibleDuplicate;

  VersionTag<?> versionTag;

  // additional bitmask flags used for serialization/deserialization

  protected static final short USE_ORIGIN_REMOTE = UNRESERVED_FLAGS_START;
  protected static final short HAS_OLD_VALUE = (USE_ORIGIN_REMOTE << 1);
  protected static final short CACHE_WRITE = (HAS_OLD_VALUE << 1);
  protected static final short HAS_BRIDGE_CONTEXT = (CACHE_WRITE << 1);
  protected static final short HAS_ORIGINAL_SENDER = (HAS_BRIDGE_CONTEXT << 1);
  protected static final int HAS_VERSION_TAG = (HAS_ORIGINAL_SENDER << 1);

  /**
   * Empty constructor to satisfy {@link DataSerializer} requirements
   */
  public RemoteDestroyMessage() {
  }

  protected RemoteDestroyMessage(InternalDistributedMember recipient,
                           boolean notifyOnly,
                           LocalRegion r,
                           DirectReplyProcessor processor,
                           EntryEventImpl event,
                           Object expectedOldValue,
                           boolean cacheWrite,
                           boolean useOriginRemote,
                           boolean possibleDuplicate) {
    super(recipient, r, processor, event.getTXState(r));
    initialize(notifyOnly, event, expectedOldValue, cacheWrite,
        useOriginRemote, possibleDuplicate);
  }

  protected RemoteDestroyMessage(Set<?> recipients,
                           boolean notifyOnly,
                           LocalRegion r,
                           DirectReplyProcessor processor,
                           EntryEventImpl event,
                           Object expectedOldValue,
                           boolean cacheWrite,
                           boolean useOriginRemote,
                           boolean possibleDuplicate) {
    super(recipients, r, processor, event.getTXState(r));
    initialize(notifyOnly, event, expectedOldValue, cacheWrite,
        useOriginRemote, possibleDuplicate);
  }

  private final void initialize(final boolean notifyOnly,
      final EntryEventImpl event, final Object expectedOldValue,
      boolean cacheWrite, boolean useOriginRemote, boolean possibleDuplicate) {
    this.expectedOldValue = expectedOldValue;
    this.cacheWrite = cacheWrite;
    this.key = event.getKey();
    this.cbArg = event.getRawCallbackArgument();
    this.op = event.getOperation();
    this.bridgeContext = event.getContext();
    this.eventId = event.getEventId();
    Assert.assertTrue(this.eventId != null);
    this.useOriginRemote = useOriginRemote;
    this.possibleDuplicate = possibleDuplicate;
    this.versionTag = event.getVersionTag();

    // added for old value if available sent over the wire for bridge servers.
    if (event.hasOldValue()) {
      this.hasOldValue = true;
      event.exportOldValue(this);
    }
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  private final void setOldValBytes(byte[] valBytes) {
    this.oldValBytes = valBytes;
  }

  private final void setOldValObj(@Unretained(ENTRY_EVENT_OLD_VALUE) Object o) {
    this.oldValObj = o;
  }

  public final byte[] getOldValueBytes() {
    return this.oldValBytes;
  }

  protected final Object getOldValObj() {
    return this.oldValObj;
  }

  protected final boolean getHasOldValue() {
    return this.hasOldValue;
  }

  protected final byte getOldValueIsSerialized() {
    return this.oldValueIsSerialized;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static boolean distribute(EntryEventImpl event,
      Object expectedOldValue, boolean onlyPersistent) {
    boolean successful = false;
    DistributedRegion r = (DistributedRegion)event.getRegion();
    Collection<InternalDistributedMember> replicates = onlyPersistent ? r
        .getCacheDistributionAdvisor().adviseInitializedPersistentMembers()
        .keySet() : r.getCacheDistributionAdvisor()
        .adviseInitializedReplicates();
    if (replicates.isEmpty()) {
      return false;
    }
    LogWriterI18n log = r.getLogWriterI18n();
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
        RemoteDestroyReplyProcessor processor = send(replicate,
            event.getRegion(), event, expectedOldValue, false, false, posDup);
        processor.waitForCacheException();
        VersionTag<?> versionTag = processor.getVersionTag();
        if (versionTag != null) {
          event.setVersionTag(versionTag);
          final RegionVersionVector rvv = r.getVersionVector();
          if (rvv != null) {
            rvv.recordVersion(versionTag.getMemberID(), versionTag, event);
          }
        }
        event.setInhibitDistribution(true);
        return true;

      } catch (EntryNotFoundException e) {
        throw new EntryNotFoundException("" + event.getKey());

      } catch (TransactionException te) {
        throw te;

      } catch (CancelException e) {
        event.getRegion().getCancelCriterion().checkCancelInProgress(e);

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
   * Sends a RemoteDestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)} to the
   * recipient
   * 
   * @param recipient the recipient of the message
   * @param r
   *          the ReplicateRegion for which the destroy was performed
   * @param event the event causing this message
   * @param useOriginRemote
   *          whether the receiver should use originRemote=true in its event
   * @return the processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   */
  public static RemoteDestroyReplyProcessor send(
                                       InternalDistributedMember recipient,
                                       LocalRegion r,
                                       EntryEventImpl event,
                                       Object expectedOldValue,
                                       boolean cacheWrite,
                                       boolean useOriginRemote,
                                       boolean possibleDuplicate)
  throws RemoteOperationException {

    final RemoteDestroyMessage m = prepareSend(r.getSystem(), recipient, r,
        event, expectedOldValue, cacheWrite, useOriginRemote,
        possibleDuplicate);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new RemoteOperationException(
          LocalizedStrings.RemoteDestroyMessage_FAILED_SENDING_0
              .toLocalizedString(m));
    }
    return (RemoteDestroyReplyProcessor)m.processor;
  }

  /**
   * Prepares a RemoteDestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)} for sending to
   * the recipient
   * 
   * @param sys
   *          the distributed system
   * @param recipient
   *          the recipient of the message
   * @param r
   *          the ReplicateRegion for which the destroy was performed
   * @param event
   *          the event causing this message
   * @param useOriginRemote
   *          whether the receiver should use originRemote=true in its event
   * 
   * @return the message prepared for send
   */
  public static RemoteDestroyMessage prepareSend(
      final InternalDistributedSystem sys,
      final InternalDistributedMember recipient, final LocalRegion r,
      final EntryEventImpl event, final Object expectedOldValue,
      boolean cacheWrite, boolean useOriginRemote, boolean possibleDuplicate) {
    // recipient may be null for event notification
    //Assert.assertTrue(recipient != null, "RemoteDestroyMessage NULL recipient");

    final RemoteDestroyReplyProcessor p = new RemoteDestroyReplyProcessor(sys,
        recipient);
    p.requireResponse();
    final RemoteDestroyMessage m = new RemoteDestroyMessage(recipient,
                                          false,
                                          r,
                                          p,
                                          event,
                                          expectedOldValue,
                                          cacheWrite,
                                          false,
                                          false);
    return m;
  }

  /**
   * Sends a RemoteDestroyMessage
   * {@link com.gemstone.gemfire.cache.Region#destroy(Object)} message to the
   * recipients
   * 
   * @param recipients the recipients of the message
   * @param r
   *          the ReplicateRegion for which the destroy was performed
   * @param event the event causing this message
   * @return the processor used to await the potential
   *         {@link com.gemstone.gemfire.cache.CacheException}
   */
  public static RemoteDestroyReplyProcessor send(Set<?> recipients,
                                       LocalRegion r,
                                       EntryEventImpl event,
                                       Object expectedOldValue,
                                       boolean cacheWrite,
                                       boolean useOriginRemote,
                                       boolean possibleDuplicate)
  throws RemoteOperationException {
    final RemoteDestroyReplyProcessor p = new RemoteDestroyReplyProcessor(
        r.getSystem(), recipients);
    p.requireResponse();
    final RemoteDestroyMessage m = new RemoteDestroyMessage(recipients,
                                          false,
                                          r,
                                          p,
                                          event,
                                          expectedOldValue,
                                          cacheWrite,
                                          useOriginRemote,
                                          possibleDuplicate);
    final Set<?> failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new RemoteOperationException(LocalizedStrings
          .RemoteDestroyMessage_FAILED_SENDING_0.toLocalizedString(m));
    }
    return p;
  }

  /**
   * This method is called upon receipt and make the desired changes to the
   * PartitionedRegion Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgement
   */
  @Override
  protected boolean operateOnRegion(DistributionManager dm,
      LocalRegion r, long startTime)
      throws EntryExistsException, RemoteOperationException
  {

    LogWriterI18n l = dm.getLoggerI18n();
    InternalDistributedMember eventSender = originalSender;
    if (eventSender == null) {
       eventSender = getSender();
    }
    if (r.keyRequiresRegionContext()) {
      ((KeyWithRegionContext)this.key).setRegionContext(r);
    }
    EntryEventImpl event = null;
    try {
    if (this.bridgeContext != null) {
      event = EntryEventImpl.create(r, getOperation(), getKey(), null/*newValue*/,
          getCallbackArg(), false/*originRemote*/, eventSender, 
          true/*generateCallbacks*/);
      event.setContext(this.bridgeContext);
    } // bridgeContext != null
    else {
      event = EntryEventImpl.create(
        r,
        getOperation(),
        getKey(),
        null, /*newValue*/
        getCallbackArg(),
        this.useOriginRemote,
        eventSender,
        true/*generateCallbacks*/,
        false/*initializeId*/);
    }
    event.setCausedByMessage(this);

    if (this.versionTag != null) {
      this.versionTag.replaceNullIDs(getSender());
      event.setVersionTag(this.versionTag);
    }

    // for cq processing and client notification by BS.
    if (this.hasOldValue) {
      if (this.oldValueIsSerialized == VALUE_IS_SERIALIZED_OBJECT) {
        event.setSerializedOldValue(getOldValueBytes());
      }
      else if (this.oldValueIsSerialized == VALUE_IS_OBJECT) {
          event.setOldValue(getOldValObj());
      }
      else {
        event.setOldValue(getOldValueBytes());
      }
    }

    Assert.assertTrue(eventId != null);
    event.setEventId(eventId);
    event.setLockingPolicy(getLockingPolicy());
    event.setPossibleDuplicate(this.possibleDuplicate);

    try {
      if (!r.isCacheContentProxy()) {
        final TXStateInterface tx = getTXState(r);
        event.setTXState(tx);
        r.getDataView(tx).destroyOnRemote(event, this.cacheWrite,
            this.expectedOldValue);
        sendReply(dm, event.getVersionTag());
      }
    } catch (CacheWriterException cwe) {
      sendReply(getSender(), this.processorId, dm, new ReplyException(cwe), r,
          startTime);
      return false;
    } catch (EntryNotFoundException eee) {
      l.fine(getClass().getName()
          + ": operateOnRegion caught EntryNotFoundException");
      ReplyMessage.send(getSender(), getProcessorId(), new ReplyException(eee),
          getReplySender(dm), this, r.isInternalRegion());
    } catch (DataLocationException e) {
      l.fine(getClass().getName()
          + ": operateOnRegion caught DataLocationException");
      ReplyMessage.send(getSender(), getProcessorId(), new ReplyException(e),
          getReplySender(dm), this, r.isInternalRegion());
    }
    return false;
    } finally {
      if (event != null) {
        event.release();
      }
    }
  }

  public int getDSFID() {
    return R_DESTROY_MESSAGE;
  }

  private void sendReply(DM dm, VersionTag<?> versionTag) {
    DestroyReplyMessage.send(this.getSender(), getReplySender(dm),
        this.processorId, this, versionTag);
  }

  @Override
  public void fromData(DataInput in)
          throws IOException, ClassNotFoundException {
    super.fromData(in);
    setKey(DataSerializer.readObject(in));
    this.cbArg = DataSerializer.readObject(in);
    this.op = Operation.fromOrdinal(in.readByte());
    if ((flags & HAS_BRIDGE_CONTEXT) != 0) {
      this.bridgeContext = DataSerializer.readObject(in);
    }
    if ((flags & HAS_ORIGINAL_SENDER) != 0) {
      this.originalSender = DataSerializer.readObject(in);
    }
    this.eventId = DataSerializer.readObject(in);

    this.useOriginRemote = (flags & USE_ORIGIN_REMOTE) != 0;
    this.possibleDuplicate = (flags & POS_DUP) != 0;

    // for old values for CQs
    this.hasOldValue = ((flags & HAS_OLD_VALUE) != 0);
    if (this.hasOldValue) {
      this.oldValueIsSerialized = in.readByte();
      if (this.oldValueIsSerialized == VALUE_IS_OBJECT) {
        setOldValObj(DataSerializer.readObject(in));
      }
      else {
        setOldValBytes(DataSerializer.readByteArray(in));
      }
    }
    this.cacheWrite = ((flags & CACHE_WRITE) != 0);
    this.expectedOldValue = DataSerializer.readObject(in);
    if ((flags & HAS_VERSION_TAG) != 0) {
      // Since xd does the versionTag conditionally in writeObject
      // we also read it conditionally in readObject.
      // Note that GFE does both of them unconditionally.
      this.versionTag = DataSerializer.readObject(in);
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeObject(getKey(), out);
    DataSerializer.writeObject(this.cbArg, out);
    out.writeByte(this.op.ordinal);
    if (this.bridgeContext != null) {
      DataSerializer.writeObject(this.bridgeContext, out);
    }
    if (this.originalSender != null) {
      DataSerializer.writeObject(this.originalSender, out);
    }
    DataSerializer.writeObject(this.eventId, out);

    // this will be on wire for cqs old value generations.
    if (this.hasOldValue) {
      out.writeByte(this.oldValueIsSerialized);
      byte policy = DistributedCacheOperation.valueIsToDeserializationPolicy(oldValueIsSerialized);
      DistributedCacheOperation.writeValue(policy, getOldValObj(), getOldValueBytes(), out);
    }
    DataSerializer.writeObject(this.expectedOldValue, out);
    if (this.versionTag != null) {
      DataSerializer.writeObject(this.versionTag, out);
    }
  }

  @Override
  protected short computeCompressedShort(short s) {
    // this will be on wire for cqs old value generations.
    if (this.hasOldValue) s |= HAS_OLD_VALUE;
    if (this.cacheWrite) s |= CACHE_WRITE;
    if (this.useOriginRemote) s |= USE_ORIGIN_REMOTE;
    if (this.possibleDuplicate) s |= POS_DUP;
    if (this.bridgeContext != null) s |= HAS_BRIDGE_CONTEXT;
    if (this.originalSender != null) s |= HAS_ORIGINAL_SENDER;
    if (this.versionTag != null) s |= HAS_VERSION_TAG;
    return s;
  }

  @Override
  public EventID getEventID() {
    return this.eventId;
  }

  /**
   * Assists the toString method in reporting the contents of this message
   * 
   */
  @Override
  protected void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; key=").append(getKey());
    if (originalSender != null) {
      buff.append("; originalSender=").append(originalSender);
    }
    if (bridgeContext != null) {
      buff.append("; bridgeContext=").append(bridgeContext);
    }
    if (eventId != null) {
      buff.append("; eventId=").append(eventId);
    }
    buff.append("; hasOldValue=").append(this.hasOldValue);
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
  public boolean prefersOldSerialized() {
    return true;
  }

  @Override
  public boolean isUnretainedOldReferenceOk() {
    return true;
  }

  @Override
  public boolean isCachedDeserializableValueOk() {
    return false;
  }
  
  private void setOldValueIsSerialized(boolean isSerialized) {
    if (isSerialized) {
      if (CachedDeserializableFactory.preferObject()) {
        this.oldValueIsSerialized = VALUE_IS_OBJECT;
      } else {
        // Defer serialization until toData is called.
        this.oldValueIsSerialized = VALUE_IS_SERIALIZED_OBJECT;
      }
    } else {
      this.oldValueIsSerialized = VALUE_IS_BYTES;
    }
  }
  
  @Override
  public void importOldObject(@Unretained(ENTRY_EVENT_OLD_VALUE) Object ov, boolean isSerialized) {
    setOldValueIsSerialized(isSerialized);
    // Defer serialization until toData is called.
    setOldValObj(ov);
  }

  @Override
  public void importOldBytes(byte[] ov, boolean isSerialized) {
    setOldValueIsSerialized(isSerialized);
    setOldValBytes(ov);
  }

  public static class DestroyReplyMessage extends ReplyMessage {

    private static final byte HAS_VERSION = 0x01;
    private static final byte PERSISTENT = 0x02;

    private VersionTag<?> versionTag;

    /** DSFIDFactory constructor */
    public DestroyReplyMessage() {
    }

    static void send(InternalDistributedMember recipient, ReplySender dm,
        int procId, RemoteDestroyMessage sourceMessage, VersionTag<?> versionTag) {
      Assert
          .assertTrue(recipient != null, "DestroyReplyMessage NULL recipient");
      DestroyReplyMessage m = new DestroyReplyMessage(recipient, procId,
          sourceMessage, versionTag);
      dm.putOutgoing(m);
    }

    DestroyReplyMessage(InternalDistributedMember recipient, int procId,
        RemoteDestroyMessage sourceMessage, VersionTag<?> versionTag) {
      super(sourceMessage, true, true);
      this.setProcessorId(procId);
      this.setRecipient(recipient);
      this.versionTag = versionTag;
    }

    @Override
    public boolean getInlineProcess() {
      return true;
    }

    @Override
    public int getDSFID() {
      return R_DESTROY_REPLY_MESSAGE;
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
      if (rp instanceof RemoteDestroyReplyProcessor) {
        RemoteDestroyReplyProcessor processor = (RemoteDestroyReplyProcessor)rp;
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
      StringBuilder sb = super.getStringBuilder();
      sb.append(getShortClassName());
      sb.append(" processorId=");
      sb.append(this.processorId);
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
  }

  static final class RemoteDestroyReplyProcessor extends
      RemoteOperationResponse {
    VersionTag<?> versionTag;

    RemoteDestroyReplyProcessor(InternalDistributedSystem ds,
        InternalDistributedMember recipient) {
      super(ds, recipient, false);
    }

    RemoteDestroyReplyProcessor(InternalDistributedSystem ds, Set<?> recipients) {
      super(ds, recipients, false);
    }

    void setResponse(VersionTag<?> versionTag) {
      this.versionTag = versionTag;
    }

    VersionTag<?> getVersionTag() {
      return this.versionTag;
    }
  }
}
