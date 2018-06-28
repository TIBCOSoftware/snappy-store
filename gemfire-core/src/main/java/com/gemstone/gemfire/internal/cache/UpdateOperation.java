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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_NEW_VALUE;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.distributed.internal.ConflationKey;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.EntryEventImpl.NewValueImporter;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;

/**
 * Handles distribution messaging for updating an entry in a region.
 *
 * @author Eric Zoerner
 */
public class UpdateOperation extends AbstractUpdateOperation
{

  /** Creates a new instance of UpdateOperation */
  public UpdateOperation(EntryEventImpl event, long lastModifiedTime) {
    super(event, lastModifiedTime);
  }

  // protected Set getRecipients() {
  // DistributionAdvisor advisor = getRegion().getDistributionAdvisor();
  // return super.getRecipients();
  // }

  @Override
  protected boolean supportsDeltaPropagation() {
    return true;
  }

  @Override
  protected CacheOperationMessage createMessage()
  {
    EntryEventImpl ev = getEvent();
    if (ev.isBridgeEvent()) {
      UpdateWithContextMessage mssgwithContxt = new UpdateWithContextMessage();
      // getContext is not in EntryEvent interface because it exposes a private
      // class
      mssgwithContxt.clientID = ev.getContext();
      return mssgwithContxt;
    }
    else {
      return new UpdateMessage(ev.getTXState());
    }
  }

  @Override
  protected void initMessage(CacheOperationMessage msg,
      DirectReplyProcessor p)
  {
    super.initMessage(msg, p);
    UpdateMessage m = (UpdateMessage)msg;
    EntryEventImpl ev = getEvent();
    m.event = ev;
    m.isPutDML = ev.isPutDML();
    m.eventId = ev.getEventId();
    m.key = ev.getKey();
    if (CachedDeserializableFactory.preferObject() || ev.hasDelta()) {
      m.deserializationPolicy = DESERIALIZATION_POLICY_EAGER;
    } else {
      m.deserializationPolicy = DESERIALIZATION_POLICY_LAZY;
    }
    ev.exportNewValue(m);
  }

  @Override
  protected void initProcessor(CacheOperationReplyProcessor p, CacheOperationMessage msg) {
    if (processor != null) {
      if(msg instanceof UpdateWithContextMessage){
        processor.msg = new UpdateWithContextMessage((UpdateWithContextMessage)msg); 
      }
      else{  
      processor.msg = new UpdateMessage((UpdateMessage)msg);
      } 
    }
  }

  @Override
  protected void checkForDataStoreAvailability(DistributedRegion region,
      Set<InternalDistributedMember> recipients) {

    if (region.getCache().isGFXDSystem()) {
      if (recipients.isEmpty() && !region.isUsedForMetaRegion()
          && !region.isUsedForPartitionedRegionAdmin()
          && !region.isUsedForPartitionedRegionBucket()
          && !region.getDataPolicy().withStorage()) {
        throw new NoDataStoreAvailableException(LocalizedStrings
            .DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                .toLocalizedString(region));
      }
    }
  }

  public static class UpdateMessage extends AbstractUpdateMessage implements NewValueImporter {

    /**
     * Indicates if and when the new value should be deserialized on the the
     * receiver
     */
    protected byte deserializationPolicy;

    protected EntryEventImpl event = null;

    protected EventID eventId = null;

    protected Object key;

    protected byte[] newValue;

    @Unretained(ENTRY_EVENT_NEW_VALUE) 
    protected transient Object newValueObj;

    private byte[] deltaBytes;

    private boolean sendDeltaWithFullValue = true;
    
    private transient boolean isPutDML = false;

    // extraFlags
    static final int HAS_EVENTID = getNextByteMask(DESERIALIZATION_POLICY_END);
    static final int HAS_DELTA_WITH_FULL_VALUE = getNextByteMask(HAS_EVENTID);
    static final int IS_PUT_DML = getNextByteMask(HAS_DELTA_WITH_FULL_VALUE);

    private long tailKey = 0L;

    public UpdateMessage(){}

    public UpdateMessage(TXStateInterface tx) {
      super(tx);
    }
    /**  
     * copy constructor
     */
    public UpdateMessage(UpdateMessage upMsg) {
      super(upMsg.getTXState());
      this.appliedOperation = upMsg.appliedOperation;
      this.callbackArg = upMsg.callbackArg;
      this.deserializationPolicy = upMsg.deserializationPolicy;
      this.directAck = upMsg.directAck;
      this.event = upMsg.event;
      this.eventId = upMsg.eventId;
      this.hasDelta = upMsg.hasDelta;
      this.key = upMsg.key;
      this.lastModified = upMsg.lastModified;
      this.newValue = upMsg.newValue;
      this.newValueObj = upMsg.newValueObj;
      this.op = upMsg.op;
      this.owner = upMsg.owner;
      this.possibleDuplicate = upMsg.possibleDuplicate;
      this.processorId = upMsg.processorId;
      this.regionAllowsConflation = upMsg.regionAllowsConflation;
      this.regionPath = upMsg.regionPath;
      this.sendDelta = upMsg.sendDelta;
      this.sender = upMsg.sender;
      this.processor = upMsg.processor;
      this.filterRouting = upMsg.filterRouting; 
      this.needsRouting = upMsg.needsRouting; 
      this.versionTag = upMsg.versionTag;
      this.isPutDML = upMsg.isPutDML;
    }
    
    @Override
    public ConflationKey getConflationKey()
    {
      if (!super.regionAllowsConflation || this.directAck
          || getProcessorId() != 0) {
        // if the publisher's region attributes do not support conflation
        // or if it is an ack region
        // then don't even bother with a conflation key
        return null;
      }
      else {
        // only conflate if it is not a create
        // and we don't want an ack
        return new ConflationKey(this.key, super.regionPath, getOperation()
            .isUpdate());
      }
    }

    @Override
    protected InternalCacheEvent createEvent(DistributedRegion rgn)
        throws EntryNotFoundException {
      EntryEventImpl ev = createEntryEvent(rgn);
      boolean evReturned = false;
      try {
      ev.setEventId(this.eventId);
      
      ev.setDeltaBytes(this.deltaBytes);

      if (hasDelta()) {
        this.newValueObj = null;
        // New value will be set once it is generated with fromDelta() inside
        // EntryEventImpl.processDeltaBytes()
        ev.setNewValue(this.newValueObj);
      }
      else {
        setNewValueInEvent(this.newValue, this.newValueObj, ev,
            this.deserializationPolicy);
      }
      if (this.filterRouting != null) {
        ev.setLocalFilterInfo(this.filterRouting
            .getFilterInfo(rgn.getMyId()));
      }
      ev.setTailKey(tailKey);
      ev.setVersionTag(this.versionTag);
      
      ev.setInhibitAllNotifications(this.inhibitAllNotifications);
      
      ev.setPutDML(this.isPutDML);
      
      evReturned = true;
      return ev;
      } finally {
        if (!evReturned) {
          ev.release();
        }
      }
    }

    @Override
    boolean processReply(final ReplyMessage replyMessage, CacheOperationReplyProcessor processor) {
      ReplyException ex = replyMessage.getException();
      if (ex != null && ex.getCause() instanceof InvalidDeltaException) {
        // msg can be null when PR data store throws exception back to
        // accessor.
        UpdateMessage message = this;
        if (!(message.hasBridgeContext() && message.getDataPolicy() == DataPolicy.EMPTY)) {
          final UpdateMessage updateMsg;
          final DM dm = this.event.getRegion().getDistributionManager();
          final LogWriterI18n log = dm.getLoggerI18n();
          if (this instanceof UpdateWithContextMessage) {
            updateMsg = new UpdateOperation.UpdateWithContextMessage(
                (UpdateWithContextMessage)this);
          }
          else {
            updateMsg = new UpdateOperation.UpdateMessage(
                this);
          }
          Runnable sendMessage = new Runnable() {
            public void run() {
              synchronized (updateMsg) { // prevent concurrent update of
                // recipient list
                updateMsg.resetRecipients();
                updateMsg.setRecipient(replyMessage.getSender());
                updateMsg.setSendDelta(false);
                updateMsg.setSendDeltaWithFullValue(false);
                if (log.fineEnabled()) {
                  log.fine("Sending full object (" + updateMsg + ") to "
                      + replyMessage.getSender());
                }
                dm.putOutgoing(updateMsg);
              }
              updateMsg.event.getRegion().getCachePerfStats()
                  .incDeltaFullValuesSent();
            }

            @Override
            public String toString() {
              return "Sending full object {" + updateMsg.toString() + "}";
            }
          };
          
          if (processor.isExpectingDirectReply()) {
            sendMessage.run();
          } else {
            dm.getWaitingThreadPool().execute(
                sendMessage);
          }
          return false;
        }
      }
      return true;
    }

    /**
     * Utility to set the new value in the EntryEventImpl based on the given
     * deserialization value; also called from QueuedOperation
     */
    static void setNewValueInEvent(byte[] newValue, Object newValueObj,
        EntryEventImpl event, byte deserializationPolicy) {
      if (newValue == null
          && deserializationPolicy != DESERIALIZATION_POLICY_EAGER) {
        // in an UpdateMessage this results from a create(key, null) call,
        // set local invalid flag in event if this is a normal region. Otherwise
        // it should be a distributed invalid.
        if(event.getRegion().getAttributes().getDataPolicy() == DataPolicy.NORMAL) {
          event.setLocalInvalid(true);
        }
        event.setNewValue(newValue);
        Assert.assertTrue(deserializationPolicy == DESERIALIZATION_POLICY_NONE);
        return;
      }

      switch (deserializationPolicy) {
        case DESERIALIZATION_POLICY_LAZY:
          event.setSerializedNewValue(newValue);
          break;
        case DESERIALIZATION_POLICY_NONE:
          event.setNewValue(newValue);
          break;
        case DESERIALIZATION_POLICY_EAGER:
          event.setNewValue(newValueObj);
          break;
        default:
          throw new InternalGemFireError(LocalizedStrings
              .UpdateOperation_UNKNOWN_DESERIALIZATION_POLICY_0
                  .toLocalizedString(Byte.valueOf(deserializationPolicy)));
      }
    }

    protected EntryEventImpl createEntryEvent(DistributedRegion rgn)
    {
      Object argNewValue = null;
      final boolean originRemote = true, generateCallbacks = true;

      if (rgn.keyRequiresRegionContext()) {
        final KeyWithRegionContext key = (KeyWithRegionContext)this.key;
        if (this.newValue != null) {
          key.afterDeserializationWithValue(this.newValue);
        }
        else if (!(this.newValueObj instanceof com.gemstone.gemfire.internal
            .cache.delta.Delta)) {
          key.afterDeserializationWithValue(this.newValueObj);
        }
        key.setRegionContext(rgn);
      }
      EntryEventImpl result = EntryEventImpl.create(rgn, getOperation(), this.key,
          argNewValue, // oldValue,
          this.callbackArg, originRemote, getSender(), generateCallbacks);
      setOldValueInEvent(result);
      result.setTailKey(this.tailKey);
      if (this.versionTag != null) {
        result.setVersionTag(this.versionTag);
      }
      return result;
    }

    @Override
    protected void appendFields(StringBuilder buff) {
      super.appendFields(buff);
      buff.append("; key=");
      buff.append(this.key);
      if (this.hasDelta()) {
        byte[] bytes;
        if (this.event != null) {
          bytes = this.event.getDeltaBytes();
        } else {
          bytes = this.deltaBytes;
        }
        if (bytes == null) {
          buff.append("; null delta bytes");
        } else {
          buff.append("; ").append(bytes.length).append(" delta bytes");
        }
      }
      else if (this.newValueObj != null) {
        buff.append("; newValueObj=");
        buff.append(this.newValueObj);
      }
      else {
        buff.append("; newValue=");
        // buff.append(this.newValue);
        buff.append(newValue == null ? "null" : "(" + newValue.length
            + " bytes)");
      }
      if (this.eventId != null) {
        buff.append("; eventId=").append(this.eventId);
      }
      buff.append("; deserializationPolicy=");
      buff.append(deserializationPolicyToString(this.deserializationPolicy));
    }

    public int getDSFID()
    {
      return UPDATE_MESSAGE;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      final byte extraFlags = in.readByte();
      final boolean hasEventId = (extraFlags & HAS_EVENTID) != 0;
      if (hasEventId) {
        this.eventId = new EventID();
        InternalDataSerializer.invokeFromData(this.eventId, in);
        this.tailKey = InternalDataSerializer.readSignedVL(in);
      }
      else {
        this.eventId = null;
      }
      this.key = DataSerializer.readObject(in);

      this.deserializationPolicy = (byte)(extraFlags
          & DESERIALIZATION_POLICY_MASK);
      if (hasDelta()) {
        this.deltaBytes = DataSerializer.readByteArray(in);
      }
      else {
        if (this.deserializationPolicy
            == DistributedCacheOperation.DESERIALIZATION_POLICY_EAGER) {
          this.newValueObj = DataSerializer.readObject(in);
        }
        else {
          this.newValue = DataSerializer.readByteArray(in);
        }
        if ((extraFlags & HAS_DELTA_WITH_FULL_VALUE) != 0) {
          this.deltaBytes = DataSerializer.readByteArray(in);
        }
        if ((extraFlags & IS_PUT_DML) != 0) {
          this.isPutDML = true;
        }
      }
    }

    @Override
    protected final void beforeToData(final DataOutput out) throws IOException {
      DistributedRegion region = (DistributedRegion)this.event.getRegion();
      setDeltaFlag(region);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      DistributedRegion region = (DistributedRegion)this.event.getRegion();
      super.toData(out);

      byte extraFlags = this.deserializationPolicy;
      if (this.eventId != null) {
        extraFlags |= HAS_EVENTID;
      }
      boolean sendDeltaWithFullValue = this.sendDeltaWithFullValue && this.event.getDeltaBytes() != null;
      if (sendDeltaWithFullValue) {
        extraFlags |= HAS_DELTA_WITH_FULL_VALUE;
      }
      if (this.isPutDML) {
        extraFlags |= IS_PUT_DML;
      }
      out.writeByte(extraFlags);

      if (this.eventId != null) {
        InternalDataSerializer.invokeToData(this.eventId, out);
        if (region instanceof BucketRegion) {
          PartitionedRegion pr = region.getPartitionedRegion();
          // TODO Kishor: Since here we are talking about tail key
          // then we are surely considering Paralle Gateway
          if (!pr.isLocalParallelWanEnabled()) {
            InternalDataSerializer.writeSignedVL(0, out);
          }
          else {
            InternalDataSerializer.writeSignedVL(this.event.getTailKey(), out);
          }
        }
        else {
          InternalDataSerializer.writeSignedVL(0, out);
        }
      }
      Object key = this.key;
      if (key instanceof KeyWithRegionContext) {
        if (this.newValue != null || !(this.newValueObj instanceof com.gemstone
            .gemfire.internal.cache.delta.Delta)) {
          key = ((KeyWithRegionContext)key).beforeSerializationWithValue(false);
        }
      }
      DataSerializer.writeObject(key, out);

//      if (this.event.getRegion().getLogWriterI18n().fineEnabled()) {
//        this.event.getRegion().getLogWriterI18n().fine("UpdateMessage.toData: hasDelta="+hasDelta());
//      }
      if (hasDelta()) {
        DataSerializer.writeByteArray(this.event.getDeltaBytes(), out);
        this.event.getRegion().getCachePerfStats().incDeltasSent();
      } else {
        DistributedCacheOperation.writeValue(this.deserializationPolicy, this.newValueObj, this.newValue, out);
        if (sendDeltaWithFullValue) {
          DataSerializer.writeObjectAsByteArray(this.event.getDeltaBytes(), out);
        }
      }
      
    }

    @Override
    public EventID getEventID() {
      return this.eventId;
    }

    private void setDeltaFlag(DistributedRegion region) {
      try {
//        if (region != null && region.getLogWriterI18n().fineEnabled()) {
//          region.getLogWriterI18n().fine("setDeltaFlag(sendDelta="+sendDelta+", scope="+region.scope
//              +", deltaBytes="+this.event.getDeltaBytes()+")");
//        }
        if (region != null
            && region.getSystem().getConfig().getDeltaPropagation()
            && this.sendDelta && !region.scope.isDistributedNoAck()
            && this.event.getDeltaBytes() != null) {
            setHasDelta(true);
            return;
        }
        setHasDelta(false);
      } catch (RuntimeException re) {
        throw new InvalidDeltaException(
            LocalizedStrings.DistributionManager_CAUGHT_EXCEPTION_WHILE_SENDING_DELTA
                .toLocalizedString(), re);
      }
    }

    @Override
    public List getOperations()
    {
      byte[] valueBytes = null;
      Object valueObj = null;
      if (this.newValueObj != null) {
        if (this.deserializationPolicy ==
          DistributedCacheOperation.DESERIALIZATION_POLICY_EAGER) {
          valueObj = this.newValueObj;
        }
        else {
          valueBytes = EntryEventImpl.serialize(this.newValueObj);
        }
      }
      else {
        valueBytes = this.newValue;
      }
      return Collections.singletonList(new QueuedOperation(getOperation(),
          this.key, valueBytes, valueObj, this.deserializationPolicy,
          this.callbackArg));
    }

    public boolean hasBridgeContext() {
      if (this.event != null) {
        return this.event.getContext() != null;
      }
      return false;
    }

    public DataPolicy getDataPolicy() {
      if (this.event != null) {
        return this.event.getRegion().getAttributes().getDataPolicy();
      }
      return null;
    }

    public void setSendDeltaWithFullValue(boolean bool) {
      this.sendDeltaWithFullValue = bool;
    }
    @Override
    public boolean prefersNewSerialized() {
      return true;
    }
    @Override
    public boolean isUnretainedNewReferenceOk() {
      return true;
    }
    @Override
    public void importNewObject(@Unretained(ENTRY_EVENT_NEW_VALUE) Object nv, boolean isSerialized) {
      if (nv == null) {
        this.deserializationPolicy = DESERIALIZATION_POLICY_NONE;
        this.newValue = null;
      } else {
        if (!isSerialized) {
          this.deserializationPolicy = DESERIALIZATION_POLICY_NONE;
        }
        this.newValueObj = nv;
      }
    }
    @Override
    public void importNewBytes(byte[] nv, boolean isSerialized) {
      if (!isSerialized) {
        this.deserializationPolicy = DESERIALIZATION_POLICY_NONE;
      }
      this.newValue = nv;
    }
  }

  public static final class UpdateWithContextMessage extends UpdateMessage
  {

    protected transient ClientProxyMembershipID clientID;

    @Override
    final public EntryEventImpl createEntryEvent(final DistributedRegion rgn) {
      final EntryEventImpl ev = super.createEntryEvent(rgn);
      ev.setContext(this.clientID);
      return ev;
      /*
      // Object oldValue = null;
      final Object argNewValue = null;
      // boolean localLoad = false, netLoad = false, netSearch = false,
      // distributed = true;
      final boolean originRemote = true, generateCallbacks = true;

      if (rgn.keyRequiresRegionContext()) {
        ((KeyWithRegionContext)this.key).setRegionContext(rgn);
      }
      EntryEventImpl ev = new EntryEventImpl(rgn, getOperation(), this.key,
          argNewValue, this.callbackArg, originRemote, getSender(),
          generateCallbacks);
      ev.setContext(this.clientID);
      setOldValueInEvent(ev);
      return ev;
      // localLoad, netLoad, netSearch,
      // distributed, this.isExpiration, originRemote, this.context);
      */
    }

    public UpdateWithContextMessage() {
    }
    
    public UpdateWithContextMessage(UpdateWithContextMessage msg) {
      super(msg);
      this.clientID = msg.clientID;
    }

    @Override
    protected void appendFields(StringBuilder buff) {
      super.appendFields(buff);
      buff.append("; context=").append(this.clientID);
    }

    @Override
    public int getDSFID()
    {
      return UPDATE_WITH_CONTEXT_MESSAGE;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException
    {
      super.fromData(in);
      ClientProxyMembershipID cid = new ClientProxyMembershipID();
      InternalDataSerializer.invokeFromData(cid, in);
      this.clientID = cid.canonicalReference();
    }

    @Override
    public void toData(DataOutput out) throws IOException
    {
      super.toData(out);
      InternalDataSerializer.invokeToData(this.clientID, out);
    }
  }
}
