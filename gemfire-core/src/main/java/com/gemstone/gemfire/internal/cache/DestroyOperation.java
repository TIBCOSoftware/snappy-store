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

import java.io.*;
import java.util.*;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.Breadcrumbs;

/**
 * Handles distribution messaging for destroying an entry in a region.
 * 
 * @author Eric Zoerner
 *  
 */
public class DestroyOperation extends DistributedCacheOperation
{

  /** Creates a new instance of DestroyOperation */
  public DestroyOperation(EntryEventImpl event) {
    super(event);
  }

  @Override
  protected CacheOperationMessage createMessage()
  {
    if (this.event.hasClientOrigin()) {
      DestroyWithContextMessage msgwithContxt = new DestroyWithContextMessage(event);
      msgwithContxt.context = ((EntryEventImpl)this.event).getContext();
      return msgwithContxt;
    }
    else {
      return new DestroyMessage(event);
    }
  }

  @Override
  protected void initMessage(CacheOperationMessage msg,
      DirectReplyProcessor processor)
  {
    super.initMessage(msg, processor);
    DestroyMessage m = (DestroyMessage)msg;
    EntryEventImpl event = getEvent();
    m.key = event.getKey();
    m.eventId = event.getEventId();
  }
  
  @Override
  protected void checkForDataStoreAvailability(DistributedRegion region,
      Set<InternalDistributedMember> recipients) {

    if (region.getCache().isGFXDSystem()) {
      if (recipients.isEmpty() && !region.isUsedForMetaRegion()
          && !region.isUsedForPartitionedRegionAdmin()
          && !region.isUsedForPartitionedRegionBucket()
          && !region.getDataPolicy().withStorage()) {
        throw new NoDataStoreAvailableException(
            LocalizedStrings.DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                .toLocalizedString(region)) ;
      }
    }
  }

  public static class DestroyMessage extends CacheOperationMessage
  {
    protected EventID eventId = null;

    protected Object key;
    
    protected EntryEventImpl event = null;

    private Boolean isLoadedFromHDFS = false;

    private long tailKey = 0L;

    public DestroyMessage() {
    }

    public DestroyMessage(TXStateInterface tx) {
      super(tx);
    }

    public DestroyMessage(InternalCacheEvent event) {
      super(((EntryEventImpl)event).getTXState());
      this.event = (EntryEventImpl) event; 
    }
    
    @Override
    protected boolean operateOnRegion(CacheEvent event, DistributionManager dm)
        throws EntryNotFoundException
    {
      EntryEventImpl ev = (EntryEventImpl)event;
      DistributedRegion rgn = (DistributedRegion)ev.region;
      TXManagerImpl txMgr = null;
      TXManagerImpl.TXContext context = null;
      if (getLockingPolicy() == LockingPolicy.SNAPSHOT) {
        txMgr = rgn.getCache().getTxManager();
        context = txMgr.masqueradeAs(this, false,
            true);
        ev.setTXState(getTXState());
      }
      try {
        if(!rgn.isCacheContentProxy()) {
          rgn.basicDestroy(ev,
                           false,
                           null); // expectedOldValue not supported on
                                  // non- partitioned regions

        }
        this.appliedOperation = true;
        
      } catch (ConcurrentCacheModificationException e) {
        dispatchElidedEvent(rgn, ev);
        return true;  // concurrent modifications are not reported to the sender
        
      } catch (EntryNotFoundException e) {
//        if (rgn.getLogWriterI18n().fineEnabled()) {
//          rgn.getLogWriterI18n().fine("Destroy threw an exception", e);
//        }
        dispatchElidedEvent(rgn, ev);
        //Added rgn.concurrencyChecksEnabled check for the defect #48190
        if (rgn.concurrencyChecksEnabled && !ev.isConcurrencyConflict()) {
          if (rgn.getCache().isGFXDSystem()) {
            rgn.notifyTimestampsToGateways(ev);
          } else {
            rgn.notifyGatewayHubs(EnumListenerEvent.AFTER_DESTROY, ev);
          }
        }
        throw e;
      }
      catch (CacheWriterException e) {
        throw new Error(LocalizedStrings.DestroyOperation_CACHEWRITER_SHOULD_NOT_BE_CALLED.toLocalizedString(), e);
      }
      catch (TimeoutException e) {
        throw new Error(LocalizedStrings.DestroyOperation_DISTRIBUTEDLOCK_SHOULD_NOT_BE_ACQUIRED.toLocalizedString(), e);
      } finally {
        if (getLockingPolicy() == LockingPolicy.SNAPSHOT) {
          txMgr.unmasquerade(context, true);
        }
      }
      return true;
    }

    @Override
    protected final InternalCacheEvent createEvent(DistributedRegion rgn)
        throws EntryNotFoundException {
      if (rgn.keyRequiresRegionContext()) {
        ((KeyWithRegionContext)this.key).setRegionContext(rgn);
      }
      EntryEventImpl ev = createEntryEvent(rgn);
      boolean evReturned = false;
      try {
      ev.setEventId(this.eventId);
      ev.setOldValueFromRegion();
      ev.setVersionTag(this.versionTag);
      if (this.filterRouting != null) {
        ev.setLocalFilterInfo(this.filterRouting
            .getFilterInfo(rgn.getMyId()));
      }
      ev.setLoadedFromHDFS(this.isLoadedFromHDFS);
      ev.setTailKey(tailKey);
      ev.setInhibitAllNotifications(this.inhibitAllNotifications);
      evReturned = true;
      return ev;
      } finally {
        if (!evReturned) {
          ev.release();
        }
      }
    }

    EntryEventImpl createEntryEvent(DistributedRegion rgn)
    {
      EntryEventImpl event = EntryEventImpl.create(rgn,
          getOperation(), this.key, null, this.callbackArg, true, getSender());
//      event.setNewEventId(); Don't set the event here...
      setOldValueInEvent(event);
      event.setLoadedFromHDFS(this.isLoadedFromHDFS);
      event.setTailKey(this.tailKey);
      return event;
    }

    @Override
    protected void appendFields(StringBuilder buff)
    {
      super.appendFields(buff);
      buff.append(" key=")
          .append(this.key)
          .append(" id=")
          .append(this.eventId)
          .append(" isLoadedFromHDFS=")
          .append(this.isLoadedFromHDFS);
    }

    public int getDSFID() {
      return DESTROY_MESSAGE;
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.eventId = (EventID)DataSerializer.readObject(in);
      this.key = DataSerializer.readObject(in);
      this.isLoadedFromHDFS = DataSerializer.readBoolean(in);
      this.tailKey = InternalDataSerializer.readSignedVL(in);
    }

    @Override
    public void toData(DataOutput out)
        throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.eventId, out);
      DataSerializer.writeObject(this.key, out);
      
      DataSerializer.writeBoolean(this.event.isLoadedFromHDFS(), out);
      DistributedRegion region = (DistributedRegion)this.event.getRegion();
      if (region instanceof BucketRegion) {
        PartitionedRegion pr = region.getPartitionedRegion();
        if (pr.isLocalParallelWanEnabled()) {
          InternalDataSerializer.writeSignedVL(this.event.getTailKey(), out);
        }
        else {
          InternalDataSerializer.writeSignedVL(0, out);
        }
      }
      else if(((LocalRegion)region).isUsedForSerialGatewaySenderQueue()){
        InternalDataSerializer.writeSignedVL(this.event.getTailKey(), out);
      }
      else{
        InternalDataSerializer.writeSignedVL(0, out);
      }
    }

    @Override
    public EventID getEventID() {
      return this.eventId;
    }

    @Override
    public List getOperations() {
      return Collections.singletonList(new QueuedOperation(getOperation(),
          this.key, null, null, DistributedCacheOperation
              .DESERIALIZATION_POLICY_NONE, this.callbackArg));
    }

    @Override
    public ConflationKey getConflationKey()
    {
      if (!super.regionAllowsConflation || getProcessorId() != 0) {
        // if the publisher's region attributes do not support conflation
        // or if it is an ack region
        // then don't even bother with a conflation key
        return null;
      }
      else {
        // don't conflate destroys
        return new ConflationKey(this.key, super.regionPath, false);
      }
    }
  }

  public static final class DestroyWithContextMessage extends DestroyMessage
  {
    transient ClientProxyMembershipID context;

    public DestroyWithContextMessage() {
    }
    
    public DestroyWithContextMessage(InternalCacheEvent event) {
      super(event);
    }
    
    @Override
    EntryEventImpl createEntryEvent(DistributedRegion rgn)
    {
      EntryEventImpl event = EntryEventImpl.create(rgn, getOperation(), 
          this.key, null, /* newvalue */
          this.callbackArg, true /* originRemote */, getSender(),
          true/* generateCallbacks */
      );
      event.setContext(this.context);
      return event;
    }

    @Override
    protected void appendFields(StringBuilder buff)
    {
      super.appendFields(buff);
      buff.append("; membershipID=");
      buff.append(this.context == null ? "" : this.context.toString());
    }

    @Override
    public int getDSFID() {
      return DESTROY_WITH_CONTEXT_MESSAGE;
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.context = ClientProxyMembershipID.readCanonicalized(in);
    }

    @Override
    public void toData(DataOutput out)
        throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.context, out);
    }
  }
}
