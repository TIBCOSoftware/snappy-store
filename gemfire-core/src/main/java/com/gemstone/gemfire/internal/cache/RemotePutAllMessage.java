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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryExistsException;
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
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.EntryVersionsList;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.PutAllEntryData;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gnu.trove.THashMap;

/**
 * A Replicate Region update message. Meant to be sent only to the peer who
 * hosts transactional data.
 * 
 * @since 6.5
 */
public final class RemotePutAllMessage extends
    RemoteOperationMessageWithDirectReply {

  private PutAllEntryData[] putAllData;

  private int putAllDataCount = 0;

  /**
   * An additional object providing context for the operation, e.g., for
   * BridgeServer notification
   */
  private ClientProxyMembershipID bridgeContext;

  private boolean posDup;

  protected static final short HAS_BRIDGE_CONTEXT = UNRESERVED_FLAGS_START;
  protected static final short SKIP_CALLBACKS = (HAS_BRIDGE_CONTEXT << 1);
  protected static final short IS_PUT_DML = (SKIP_CALLBACKS << 1);

  private EventID eventId;

  private boolean skipCallbacks;
  
  private boolean isPutDML;

  public void addEntry(PutAllEntryData entry) {
    this.putAllData[this.putAllDataCount++] = entry;
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  public int getSize() {
    return putAllDataCount;
  }

  /**
   * this is similar to send() but it selects an initialized replicate that is
   * used to proxy the message
   */
  public static boolean distribute(EntryEventImpl event,
      PutAllEntryData[] data, int dataCount) {
    boolean successful = false;
    
    DistributedRegion r = (DistributedRegion)event.getRegion();
    Collection<InternalDistributedMember> replicates = r
        .getCacheDistributionAdvisor().adviseInitializedReplicates();
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
        Set<InternalDistributedMember> recips = Collections
            .singleton(replicate);
        RemotePutAllMessage putAllMessage = new RemotePutAllMessage(event,
            recips, data, dataCount, posDup, null, null);
        PutAllResponse response = putAllMessage.send(recips, event, posDup);
        response.waitForCacheException();
        VersionedObjectList result = response.getResponse(r);

        // Set successful version tags in PutAllEntryData.
        if (result.hasVersions()) {
          List<?> successfulKeys = result.getKeys();
          THashMap keysMap = null;
          @SuppressWarnings("rawtypes")
          List<VersionTag> versions = result.getVersionTags();
          int index = 0;
          for (PutAllEntryData putAllEntry : data) {
            Object key = putAllEntry.getKey();
            if (keysMap == null) {
              if (key.equals(successfulKeys.get(index))) {
                putAllEntry.versionTag = versions.get(index);
                index++;
              }
              else {
                keysMap = new THashMap(successfulKeys.size());
                Iterator<?> keysIter = successfulKeys.iterator();
                Iterator<?> versionsIter = versions.iterator();
                while (versionsIter.hasNext()) {
                  keysMap.put(keysIter.next(), versionsIter.next());
                }
                putAllEntry.versionTag = (VersionTag<?>)keysMap.get(key);
              }
            }
            else {
              putAllEntry.versionTag = (VersionTag<?>)keysMap.get(key);
            }
          }
        }
        return true;

      } catch (TransactionException te) {
        throw te;

      } catch (CancelException e) {
        r.getCancelCriterion().checkCancelInProgress(e);
      } catch (EntryExistsException e) {
        throw e;
      } catch (CacheException e) {
        if (log.fineEnabled()) {
          log.fine(
              "RemotePutMessage caught CacheException during distribution", e);
        }
        successful = true; // not a cancel-exception, so don't complain any more
                           // about it
      } catch (RemoteOperationException e) {
        if (DistributionManager.VERBOSE || log.fineEnabled()) {
          log.info(LocalizedStrings.DEBUG, "RemotePutMessage caught an "
              + "unexpected exception during distribution", e);
        }
      }
    }
    return successful;
  }

  public RemotePutAllMessage(EntryEventImpl event,
      Set<InternalDistributedMember> recipients, PutAllEntryData[] putAllData,
      int putAllDataCount, boolean possibleDuplicate,
      ClientProxyMembershipID context, TXStateInterface tx) {
    super(recipients, event.getRegion(), null, tx);
    this.putAllData = putAllData;
    this.putAllDataCount = putAllDataCount;
    this.posDup = possibleDuplicate;
    this.eventId = event.getEventId();
    this.skipCallbacks = !event.isGenerateCallbacks();
    this.bridgeContext = context;
    this.isPutDML = event.isPutDML();
  }

  public RemotePutAllMessage() {
  }

  protected void setReplyProcessor(DirectReplyProcessor p) {
    this.processor = p;
    this.processorId = p.getProcessorId();
    if (this.isSevereAlertCompatible()) {
      p.enableSevereAlertProcessing();
    }
  }

  /**
   * this is similar to send() but it selects initialized replicates for sending
   * the message (except self)
   */
  public boolean distribute(EntryEventImpl event)
      throws RemoteOperationException {
    final DistributedRegion dr = (DistributedRegion)event.getRegion();
    final LogWriterI18n log = dr.getLogWriterI18n();
    int attempts = 0;
    for (;;) {
      try {
        attempts++;
        Set<InternalDistributedMember> replicates = dr
            .getCacheDistributionAdvisor().adviseReplicates();
        if (replicates.isEmpty()) {
          return false;
        }
        final boolean posDup = (attempts > 1);
        final PutAllResponse response = send(replicates, event, posDup);
        response.waitForCacheException();
        return true;

      } catch (TransactionException te) {
        throw te;
      } catch (CancelException e) {
        dr.getCancelCriterion().checkCancelInProgress(e);
        // go into the retry loop
      } catch (CacheException e) {
        if (log.fineEnabled()) {
          log.fine("RemotePutMessage caught "
              + "CacheException during distribution", e);
        }
        throw e;
      } catch (RemoteOperationException e) {
        if (DistributionManager.VERBOSE || log.fineEnabled()) {
          log.info(LocalizedStrings.DEBUG, "RemotePutMessage caught "
              + "an unexpected exception during distribution", e);
        }
        throw e;
      }
    }
  }

  /**
   * Sends a LocalRegion RemotePutAllMessage to the recipients
   * 
   * @param recipients
   *          the members to which the put message is sent
   * @return the processor used to await acknowledgement that the update was
   *         sent, or null to indicate that no acknowledgement will be sent
   * @throws ForceReattemptException
   *           if the peer is no longer available
   */
  private PutAllResponse send(Set<InternalDistributedMember> recipients,
      EntryEventImpl event, boolean possibleDuplicate)
      throws RemoteOperationException {
    PutAllResponse p = new PutAllResponse(event.getRegion().getSystem(),
        recipients);
    this.resetRecipients();
    if (recipients != null) {
      setRecipients(recipients);
    }
    setReplyProcessor(p);
    this.posDup = possibleDuplicate;
    Set<?> failures = event.getRegion().getDistributionManager()
        .putOutgoing(this);
    if (failures != null && failures.size() > 0) {
      throw new RemoteOperationException(
          LocalizedStrings.RemotePutMessage_FAILED_SENDING_0
              .toLocalizedString(toString() + " to " + failures));
    }
    return p;
  }

  public int getDSFID() {
    return REMOTE_PUTALL_MESSAGE;
  }

  @Override
  public final void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    this.eventId = DataSerializer.readObject(in);
    this.posDup = (flags & POS_DUP) != 0;
    if ((flags & HAS_BRIDGE_CONTEXT) != 0) {
      this.bridgeContext = DataSerializer.readObject(in);
    }
    this.skipCallbacks = (flags & SKIP_CALLBACKS) != 0;
    this.isPutDML = (flags & IS_PUT_DML) != 0;
    this.putAllDataCount = (int)InternalDataSerializer.readUnsignedVL(in);
    this.putAllData = new PutAllEntryData[putAllDataCount];
    if (this.putAllDataCount > 0) {
      final Version version = InternalDataSerializer
          .getVersionForDataStreamOrNull(in);
      final ByteArrayDataInput bytesIn = new ByteArrayDataInput();
      for (int i = 0; i < this.putAllDataCount; i++) {
        this.putAllData[i] = new PutAllEntryData(in, this.eventId, i, version,
            bytesIn);
      }

      boolean hasTags = in.readBoolean();
      if (hasTags) {
        EntryVersionsList versionTags = EntryVersionsList.create(in);
        for (int i = 0; i < this.putAllDataCount; i++) {
          this.putAllData[i].versionTag = versionTags.get(i);
        }
      }
    }
  }

  @Override
  public final void toData(DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeObject(this.eventId, out);
    if (this.bridgeContext != null) {
      DataSerializer.writeObject(this.bridgeContext, out);
    }
    InternalDataSerializer.writeUnsignedVL(this.putAllDataCount, out);

    if (this.putAllDataCount > 0) {

      EntryVersionsList versionTags = new EntryVersionsList(putAllDataCount);

      boolean hasTags = false;
      // get the "keyRequiresRegionContext" flag from first element assuming
      // all key objects to be uniform
      final boolean requiresRegionContext =
        (this.putAllData[0].key instanceof KeyWithRegionContext);
      for (int i = 0; i < this.putAllDataCount; i++) {
        if (!hasTags && putAllData[i].versionTag != null) {
          hasTags = true;
        }
        VersionTag<?> tag = putAllData[i].versionTag;
        versionTags.add(tag);
        putAllData[i].versionTag = null;
        this.putAllData[i].toData(out, requiresRegionContext);
        this.putAllData[i].versionTag = tag;
      }

      out.writeBoolean(hasTags);
      if (hasTags) {
        InternalDataSerializer.invokeToData(versionTags, out);
      }
    }
  }

  @Override
  protected short computeCompressedShort(short flags) {
    if (this.posDup) flags |= POS_DUP;
    if (this.bridgeContext != null) flags |= HAS_BRIDGE_CONTEXT;
    if (this.skipCallbacks) flags |= SKIP_CALLBACKS;
    if (this.isPutDML) flags |= IS_PUT_DML;
    return flags;
  }

  @Override
  public EventID getEventID() {
    return this.eventId;
  }

  @Override
  protected boolean operateOnRegion(DistributionManager dm,
      LocalRegion r,long startTime) throws RemoteOperationException {

    final InternalDistributedMember eventSender = getSender();

    // long lastModified = dm.getLocalSystemTimeMillis(0L);
    try {
      final EntryEventImpl baseEvent = EntryEventImpl.create(r,
          Operation.PUTALL_CREATE, null, null, null, false, eventSender,
          !skipCallbacks);
      doLocalPutAll(r, baseEvent, null, null, eventSender, true /* sendReply */);
      // basEvent.freeOffHeapResources called by doLocalPutAll
    } catch (RemoteOperationException e) {
      sendReply(eventSender, getProcessorId(), dm, new ReplyException(e), r,
          startTime);
      return false;
    }
    // reply already sent by doLocalPutAll
    return false;
  }

  /* we need a event with content for waitForNodeOrCreateBucket() */
  /**
   * This method is called by both operateOnLocalRegion() when processing a
   * remote msg or by sendMsgByBucket() when processing a msg targeted to local
   * JVM's LocalRegion. Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgment
   * 
   * @param r
   *          region eventSender the endpoint server who received request from
   *          client lastModified timestamp for last modification
   */
  public final void doLocalPutAll(final LocalRegion r,
      final EntryEventImpl baseEvent,
      final DistributedPutAllOperation putAllOp,
      final VersionedObjectList putsDone,
      final InternalDistributedMember eventSender, boolean sendReply)
      throws EntryExistsException, RemoteOperationException {
    final LogWriterI18n logger = r.getLogWriterI18n();
    try {

    // create a base event and a DPAO for PutAllMessage distributed btw
    // redundant buckets

    // set baseEventId to the first entry's event id. We need the thread id for
    // DACE
    baseEvent.setEventId(this.eventId);
    if (this.bridgeContext != null) {
      baseEvent.setContext(this.bridgeContext);
    }
    baseEvent.setPossibleDuplicate(this.posDup);
    baseEvent.setPutDML(this.isPutDML);
    if (logger.fineEnabled()) {
      logger.fine("RemotePutAllMessage.doLocalPutAll: eventSender is "
          + eventSender + ", baseEvent is " + baseEvent + ", msg is " + this );
    }
    
    final TXStateInterface txi = getTXState(r);
    final TXState tx = txi != null ? txi.getTXStateForWrite() : null;
    baseEvent.setTXState(tx);

    final DistributedPutAllOperation dpao = putAllOp == null
        ? new DistributedPutAllOperation(r, baseEvent, putAllDataCount,
            false) : putAllOp;
    try {
    final VersionedObjectList versions = putsDone == null
        ? new VersionedObjectList(this.putAllDataCount, true,
            r.getConcurrencyChecksEnabled()) : putsDone;
    r.syncPutAll(tx, new Runnable() {
      public void run() {
        final boolean requiresRegionContext = r.keyRequiresRegionContext();
        final InternalDistributedMember myId = r.getDistributionManager()
            .getDistributionManagerId();
        Object key, value;
        for (int i = 0; i < putAllDataCount; i++) {
          key = putAllData[i].key;
          value = putAllData[i].value;
          if (requiresRegionContext) {
            final KeyWithRegionContext keyWithContext =
                (KeyWithRegionContext)key;
            if (value != null && !(value instanceof Delta)) {
              keyWithContext.afterDeserializationWithValue(value);
            }
            keyWithContext.setRegionContext(r);
          }
          final EntryEventImpl ev = PutAllPRMessage.getEventFromEntry(r,
              null, myId, eventSender, i, key, putAllData, false,
              bridgeContext, posDup, !skipCallbacks, isPutDML);
          try {
          ev.setPutAllOperation(dpao);
          ev.setTXState(tx);
          if (logger.fineEnabled()) {
            logger.fine("invoking basicPut with " + ev);
          }
          if (r.basicPut(ev, false, false, null, false)) {
            putAllData[i].versionTag = ev.getVersionTag();
            versions.addKeyAndVersion(key, ev.getVersionTag());
          }
          } finally {
            ev.release();
          }
        }
      }
    }, baseEvent.getEventId());
    if (tx != null || r.getConcurrencyChecksEnabled()) {
      if (tx != null && tx.isSnapshot()) {
        r.getSharedDataView().postPutAll(dpao, versions, r);
      } else {
        r.getDataView(tx).postPutAll(dpao, versions, r);
      }
    }

    if (sendReply) {
      PutAllReplyMessage.send(getSender(), this.processorId,
          getReplySender(r.getDistributionManager()), versions, this);
    }
    } finally {
      // TODO: merge: is this correct for incoming putAllOp?
      dpao.freeOffHeapResources();
    }
    } finally {
      baseEvent.release();
    }
  }

  // override reply processor type from PartitionMessage
  RemoteOperationResponse createReplyProcessor(LocalRegion r,
      Set<?> recipients, Object key) {
    // r.getCache().getLogger().warning("RemotePutAllMessage.createReplyProcessor()",
    // new Exception("stack trace"));
    return new PutAllResponse(r.getSystem(), recipients);
  }

  // override reply message type from PartitionMessage
  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      ReplyException ex, LocalRegion r, long startTime) {
    ReplyMessage.send(member, procId, ex, getReplySender(dm), this, r != null
        && r.isInternalRegion());
  }

  @Override
  protected final void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; putAllDataCount=").append(putAllDataCount);
    if (this.bridgeContext != null) {
      buff.append("; bridgeContext=").append(this.bridgeContext);
    }
    for (int i = 0; i < putAllDataCount; i++) {
      buff.append("; entry" + i + ":").append(
          putAllData[i] == null ? "null" : putAllData[i].getKey());
    }
  }

  public static final class PutAllReplyMessage extends ReplyMessage {
    /** Result of the PutAll operation */
    //private PutAllResponseData[] responseData;
    private VersionedObjectList versions;

    @Override
    protected boolean getMessageInlineProcess() {
      return true;
    }

    private PutAllReplyMessage(int processorId,
        VersionedObjectList versionList, RemotePutAllMessage sourceMessage) {
      super(sourceMessage, true, true);
      this.versions = versionList;
      setProcessorId(processorId);
    }

    /** Send an ack */
    public static void send(InternalDistributedMember recipient,
        int processorId, ReplySender dm, VersionedObjectList versions,
        RemotePutAllMessage sourceMessage) {
      Assert.assertTrue(recipient != null,
          "PutAllReplyMessage NULL reply message");
      PutAllReplyMessage m = new PutAllReplyMessage(processorId, versions,
          sourceMessage);
      m.setRecipient(recipient);
      dm.putOutgoing(m);
    }

    /**
     * Processes this message. This method is invoked by the receiver of the
     * message.
     * 
     * @param dm
     *          the distribution manager that is processing the message.
     */
    @Override
    public void process(final DM dm, final ReplyProcessor21 rp) {
      final long startTime = getTimestamp();
      LogWriterI18n l = dm.getLoggerI18n();

      // dm.getLogger().warning("PutAllResponse processor is " +
      // ReplyProcessor21.getProcessor(this.processorId));
      if (rp == null) {
        if (DistributionManager.VERBOSE) {
          l.fine("PutAllReplyMessage processor not found");
        }
        return;
      }
      if (rp instanceof PutAllResponse) {
        PutAllResponse processor = (PutAllResponse)rp;
        processor.setResponse(this);
      }
      rp.process(this);

      if (DistributionManager.VERBOSE) {
        l.info(LocalizedStrings.PutMessage_0__PROCESSED__1, new Object[] { rp,
            this });
      }
      dm.getStats().incReplyMessageTime(NanoTimer.getTime() - startTime);
    }

    @Override
    public int getDSFID() {
      return REMOTE_PUTALL_REPLY_MESSAGE;
    }

    public PutAllReplyMessage() {
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.versions = (VersionedObjectList)DataSerializer.readObject(in);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.versions, out);
    }

    @Override
    public StringBuilder getStringBuilder() {
      StringBuilder sb = super.getStringBuilder();
      if (getException() == null && this.versions != null) {
        sb.append(" returning versionTags=").append(this.versions);
      }
      return sb;
    }
  }

  /**
   * A processor to capture the value returned by {@link RemotePutAllMessage}
   */
  public static class PutAllResponse extends RemoteOperationResponse {
    //private volatile PutAllResponseData[] returnValue;
    private VersionedObjectList versions;

    public PutAllResponse(InternalDistributedSystem ds, Set<?> recipients) {
      super(ds, recipients, false);
    }

    public void setResponse(PutAllReplyMessage putAllReplyMessage) {
      if (putAllReplyMessage.versions != null) {
        this.versions = putAllReplyMessage.versions;
        this.versions.replaceNullIDs(putAllReplyMessage.getSender());
      }
    }

    public VersionedObjectList getResponse(LocalRegion region) {
      if (this.versions != null) {
        this.versions.setRegionContext(region);
        return this.versions;
      }
      else {
        return null;
      }
    }
  }
}
