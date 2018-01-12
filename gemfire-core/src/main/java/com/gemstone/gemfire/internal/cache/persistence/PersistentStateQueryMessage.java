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
package com.gemstone.gemfire.internal.cache.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.HighPriorityDistributionMessage;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.BucketPersistenceAdvisor;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * @author dsmith
 *
 */
public class PersistentStateQueryMessage extends
    HighPriorityDistributionMessage implements MessageWithReply {

  private String regionPath;
  private PersistentMemberID id;
  private int processorId;
  private PersistentMemberID initializingId;

  public PersistentStateQueryMessage() {
    
  }
  
  public PersistentStateQueryMessage(String regionPath, PersistentMemberID id, PersistentMemberID initializingId, int processorId) {
    this.regionPath = regionPath;
    this.id = id;
    this.initializingId = initializingId;
    this.processorId = processorId;
  }

  public static PersistentStateQueryResults send(
      Set<InternalDistributedMember> members, DM dm, String regionPath,
      PersistentMemberID persistentId, PersistentMemberID initializingId) throws ReplyException {
    PersistentStateQueryReplyProcessor processor = new PersistentStateQueryReplyProcessor(dm, members);
    PersistentStateQueryMessage msg = new PersistentStateQueryMessage(regionPath, persistentId, initializingId, processor.getProcessorId());
    msg.setRecipients(members);
    
    dm.putOutgoing(msg);
    processor.waitForRepliesUninterruptibly();
    return processor.results;
  }

  @Override
  protected void process(DistributionManager dm) {
    LogWriterI18n log = dm.getLoggerI18n();
    int oldLevel =         // Set thread local flag to allow entrance through initialization Latch
      LocalRegion.setThreadInitLevelRequirement(LocalRegion.ANY_INIT);

    PersistentMemberState state = null;
    PersistentMemberID myId = null;
    PersistentMemberID myInitializingId = null;
    DiskStoreID diskStoreId = null;
    HashSet<PersistentMemberID> onlineMembers = null;
    ReplyException exception = null;
    boolean successfulReply = false;
    try {
      // get the region from the path, but do NOT wait on initialization,
      // otherwise we could have a distributed deadlock

      Cache cache = CacheFactory.getInstance(dm.getSystem());
      Region region = cache.getRegion(this.regionPath);
      PersistenceAdvisor persistenceAdvisor = null;
      if(region instanceof DistributedRegion) {
        persistenceAdvisor = ((DistributedRegion) region).getPersistenceAdvisor();
      } else if ( region == null) {
        Bucket proxy = PartitionedRegionHelper.getProxyBucketRegion(GemFireCacheImpl.getInstance(), this.regionPath, false);
        if(proxy != null) {
          persistenceAdvisor = proxy.getPersistenceAdvisor();
        }
      }

      if (persistenceAdvisor!= null && persistenceAdvisor.getMembershipView() != null) {
        if(persistenceAdvisor instanceof BucketPersistenceAdvisor) {
          ((BucketPersistenceAdvisor)persistenceAdvisor).dump(log, "SK ");
        }
        log.info(LocalizedStrings.DEBUG, "OFFLINE Members are : " + persistenceAdvisor.getMembershipView().getOfflineMembers());
        log.info(LocalizedStrings.DEBUG, "ONLINE Members are : " + persistenceAdvisor.getMembershipView().getOnlineMembers());
        log.info(LocalizedStrings.DEBUG, "REVOKED Members are : " + persistenceAdvisor.getMembershipView().getRevokedMembers());
        log.info(LocalizedStrings.DEBUG, "EQUAL MEMBERS are : " + persistenceAdvisor.getPersistedOnlineOrEqualMembers());
      }

      if(persistenceAdvisor != null) {
        if(id != null) {
          state = persistenceAdvisor.getPersistedStateOfMember(id);

        }
        if(initializingId != null && state == null) {
          state = persistenceAdvisor.getPersistedStateOfMember(initializingId);
        }

        // if state is null check if same diskstore is stored here
        if (state == null) {

        }
        log.info(LocalizedStrings.DEBUG, "");
        myId = persistenceAdvisor.getPersistentID();
        myInitializingId = persistenceAdvisor.getInitializingID();
        onlineMembers = persistenceAdvisor.getPersistedOnlineOrEqualMembers();
        diskStoreId = persistenceAdvisor.getDiskStoreID();
        successfulReply = true;
      }
    } catch (RegionDestroyedException e) {
      log.fine("<RegionDestroyed> " + this);
    }
    catch (CancelException e) {
      log.fine("<CancelException> " + this);
    }
    catch(Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      SystemFailure.checkFailure();
      exception = new ReplyException(t);
    }
    finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
      ReplyMessage replyMsg;
      if(successfulReply) {
        PersistentStateQueryReplyMessage persistentReplyMessage = new PersistentStateQueryReplyMessage();
        persistentReplyMessage.myId = myId;
        persistentReplyMessage.persistedStateOfPeer= state;
        persistentReplyMessage.myInitializingId = myInitializingId;
        persistentReplyMessage.diskStoreId = diskStoreId;
        persistentReplyMessage.onlineMembers = onlineMembers;
        replyMsg = persistentReplyMessage;
      } else {
        replyMsg = new ReplyMessage();
      }
      replyMsg.setProcessorId(processorId);
      replyMsg.setRecipient(getSender());
      if(exception != null) {
        replyMsg.setException(exception);
      }
      if(log.fineEnabled()) {
        log.fine("Received " + this + ",replying with " + replyMsg);
      }
      dm.putOutgoing(replyMsg);
    }
  }

  public int getDSFID() {
    return PERSISTENT_STATE_QUERY_REQUEST;
  }
  
  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    regionPath = DataSerializer.readString(in);
    processorId = in.readInt();
    boolean hasId = in.readBoolean();
    if(hasId) {
      id = new PersistentMemberID();
      id.fromData(in);
    }
    boolean hasInitializingId = in.readBoolean();
    if(hasInitializingId) {
      initializingId = new PersistentMemberID();
      initializingId.fromData(in);
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(regionPath, out);
    out.writeInt(processorId);
    out.writeBoolean(id != null);
    if(id != null) {
      id.toData(out);
    }
    out.writeBoolean(initializingId != null);
    if(initializingId != null) {
      initializingId.toData(out);
    }
  }
  
  @Override
  public String toString() {
    return super.toString() + ",id=" + id + ",regionPath=" + regionPath + ",initializingId=" + initializingId; 
  }
  
  private static class PersistentStateQueryReplyProcessor extends ReplyProcessor21 {
    PersistentStateQueryResults results = new PersistentStateQueryResults();

    public PersistentStateQueryReplyProcessor(DM dm, Collection initMembers) {
      super(dm, initMembers);
    }
    
    @Override
    public void process(DistributionMessage msg) {
      if(msg instanceof PersistentStateQueryReplyMessage) {
        PersistentStateQueryReplyMessage reply = (PersistentStateQueryReplyMessage) msg;
        results.addResult(reply.persistedStateOfPeer, reply.getSender(), reply.myId, reply.myInitializingId, reply.diskStoreId, reply.onlineMembers);
       }
      super.process(msg);
    }
  }
  
  public static class PersistentStateQueryReplyMessage extends ReplyMessage {
    public HashSet<PersistentMemberID> onlineMembers;
    public DiskStoreID diskStoreId;
    private PersistentMemberState persistedStateOfPeer;
    private PersistentMemberID myId;
    private PersistentMemberID myInitializingId;

    @Override
    public int getDSFID() {
      return PERSISTENT_STATE_QUERY_REPLY;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      boolean hasId = in.readBoolean();
      if(hasId) {
        myId = new PersistentMemberID();
        myId.fromData(in);
      }
      boolean hasState = in.readBoolean();
      if(hasState) {
        persistedStateOfPeer = PersistentMemberState.fromData(in);
      }
      boolean hasInitializingId = in.readBoolean();
      if(hasInitializingId) {
        myInitializingId = new PersistentMemberID();
        myInitializingId.fromData(in);
      }
      boolean hasDiskStoreID = in.readBoolean();
      if(hasDiskStoreID) {
        long diskStoreIdHigh = in.readLong();
        long diskStoreIdLow = in.readLong();
        diskStoreId = new DiskStoreID(diskStoreIdHigh, diskStoreIdLow);
      }
      boolean hasOnlineMembers = in.readBoolean();
      if(hasOnlineMembers) {
        onlineMembers = DataSerializer.readHashSet(in);
      }
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      if(myId == null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        myId.toData(out);
      }
      if(persistedStateOfPeer == null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        persistedStateOfPeer.toData(out);
      }
      if(myInitializingId== null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        myInitializingId.toData(out);
      }
      if(diskStoreId== null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        out.writeLong(diskStoreId.getMostSignificantBits());
        out.writeLong(diskStoreId.getLeastSignificantBits());
      }
      if(onlineMembers== null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        DataSerializer.writeHashSet(onlineMembers, out);
      }
    }
    
    @Override
    public String toString() {
      return super.toString() + ",myId=" + myId + ",myInitializingId=" + myInitializingId + ",persistedStateOfPeer=" + persistedStateOfPeer; 
    }
  }
}
