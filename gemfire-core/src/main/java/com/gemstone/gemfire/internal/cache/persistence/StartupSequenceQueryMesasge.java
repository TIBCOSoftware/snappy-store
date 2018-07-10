package com.gemstone.gemfire.internal.cache.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;


/**
 * Send to locator to figure out if there are any server up for this region.
 * It also checks if DDL replay is complete on the particular server to make
 * sure that initialization has taken place there.
 */
public class StartupSequenceQueryMesasge extends
    HighPriorityDistributionMessage implements MessageWithReply {

  private String regionPath;
  private int processorId;

  public StartupSequenceQueryMesasge() {

  }

  public StartupSequenceQueryMesasge(String regionPath, int processorId) {
    this.regionPath = regionPath;
    this.processorId = processorId;
  }

  public static Set<PersistentMemberID> send(
      Set<InternalDistributedMember> members, DM dm, String regionPath) throws ReplyException {
    StartupSequenceQueryMesasge.StartupSequenceQueryReplyProcessor processor =
        new StartupSequenceQueryMesasge.StartupSequenceQueryReplyProcessor(dm, members);
    StartupSequenceQueryMesasge msg =
        new StartupSequenceQueryMesasge(regionPath, processor.getProcessorId());
    msg.setRecipients(members);

    dm.putOutgoing(msg);
    processor.waitForRepliesUninterruptibly();

    return processor.offlinePersistentMember;
  }

  @Override
  protected void process(DistributionManager dm) {
    LogWriterI18n log = dm.getLoggerI18n();
    int oldLevel =         // Set thread local flag to allow entrance through initialization Latch
        LocalRegion.setThreadInitLevelRequirement(LocalRegion.ANY_INIT);

    Set<DiskStoreID> diskStoreId = new HashSet<>();
    Set<PersistentMemberID> offlineMembers = null;
    //HashSet<PersistentMemberID> onlineMembers = null;
    ReplyException exception = null;
    boolean successfulReply = false;
    try {
      // get the region from the path, but do NOT wait on initialization,
      // otherwise we could have a distributed deadlock

      GemFireCacheImpl cache = (GemFireCacheImpl)CacheFactory.getInstance(dm.getSystem());
      Region region = cache.getRegion(this.regionPath);
      PersistenceAdvisor persistenceAdvisor = null;
      if (region instanceof DistributedRegion) {
        persistenceAdvisor = ((DistributedRegion)region).getPersistenceAdvisor();
        PersistentMembershipView view = persistenceAdvisor.getMembershipView();
        HashSet<PersistentMemberID> onlineOrEqual = persistenceAdvisor.getPersistedOnlineOrEqualMembers();
        // find out a member whose DDLReplay is completed.
        Map<InternalDistributedMember, PersistentMemberID> onlineMembers = view.getOnlineMembers();
        if (dm.getLoggerI18n().fineEnabled()) {
          dm.getLoggerI18n().fine("The view is " + view);
        }
        boolean isInitialized = false;
        // Check how many online members
        for (InternalDistributedMember m : onlineMembers.keySet()) {
          if ((m.getVmKind() == DistributionManager.NORMAL_DM_TYPE) && cache.isSnappyDataStore(m)) {
            isInitialized = !cache.isUnInitializedMember(m);
          }
          if (isInitialized)
            break;
        }

        if (!isInitialized) {
          // check which are offline members
          offlineMembers = view.getOfflineMembers();
          //remove equal members too.
          offlineMembers.removeAll(onlineOrEqual);
          if (offlineMembers != null) {
            offlineMembers.forEach(pId -> {
              if (dm.getLoggerI18n().fineEnabled()) {
                dm.getLoggerI18n().fine("The offline members are " + pId + " the disk store " +
                        "id : " + pId.diskStoreId);
              }
              diskStoreId.add(pId.diskStoreId);
            });
          }
        }
      } else if (region == null) {
        dm.getLoggerI18n().info(LocalizedStrings.DEBUG, "This shouldn't have happened. ");
      }
      successfulReply = true;

    } catch (RegionDestroyedException e) {
      log.fine("<RegionDestroyed> " + this);
    } catch (CancelException e) {
      log.fine("<CancelException> " + this);
    } catch (Throwable t) {
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
    } finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
      ReplyMessage replyMsg;
      if (successfulReply) {
        StartupSequenceQueryMesasge.StartupSequenceQueryReplyMesasge persistentReplyMessage =
            new StartupSequenceQueryMesasge.StartupSequenceQueryReplyMesasge();
        //persistentReplyMessage.diskStoreId = diskStoreId;
        if (offlineMembers != null)
          persistentReplyMessage.persistentMemberIDS.addAll(offlineMembers);
        replyMsg = persistentReplyMessage;
      } else {
        replyMsg = new ReplyMessage();
      }
      replyMsg.setProcessorId(processorId);
      replyMsg.setRecipient(getSender());
      if (exception != null) {
        replyMsg.setException(exception);
      }
      if (log.fineEnabled()) {
        log.fine("Received " + this + ",replying with " + replyMsg);
      }
      dm.putOutgoing(replyMsg);
    }
  }

  public int getDSFID() {
    return STARTUP_SEQUENCE_QUERY_REQUEST;
  }

  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    regionPath = DataSerializer.readString(in);
    processorId = in.readInt();
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(regionPath, out);
    out.writeInt(processorId);
  }

  @Override
  public String toString() {
    return super.toString() + ",regionPath=" + regionPath;
  }

  private static class StartupSequenceQueryReplyProcessor extends ReplyProcessor21 {
    Set<DiskStoreID> results = new HashSet<DiskStoreID>(10);
    Set<PersistentMemberID> offlinePersistentMember =  new HashSet<PersistentMemberID>(10);

    public StartupSequenceQueryReplyProcessor(DM dm, Collection initMembers) {
      super(dm, initMembers);
    }

    @Override
    public void process(DistributionMessage msg) {
      if (msg instanceof StartupSequenceQueryMesasge.StartupSequenceQueryReplyMesasge) {
        StartupSequenceQueryMesasge.StartupSequenceQueryReplyMesasge reply = (StartupSequenceQueryMesasge.StartupSequenceQueryReplyMesasge)msg;

        if (reply.diskStoreId != null)
          results.add(reply.diskStoreId);
        if (reply.persistentMemberIDS != null)
          offlinePersistentMember.addAll(reply.persistentMemberIDS);
      }
      super.process(msg);
    }
  }

  public static class StartupSequenceQueryReplyMesasge extends ReplyMessage {
    public DiskStoreID diskStoreId;
    public HashSet<PersistentMemberID> persistentMemberIDS = new HashSet<>(10);

    @Override
    public int getDSFID() {
      return STARTUP_SEQUENCE_QUERY_REPLY;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      boolean hasDiskStoreID = in.readBoolean();
      if (hasDiskStoreID) {
        long diskStoreIdHigh = in.readLong();
        long diskStoreIdLow = in.readLong();
        diskStoreId = new DiskStoreID(diskStoreIdHigh, diskStoreIdLow);
      }
      boolean hasPersistentMemberId = in.readBoolean();
      if (hasPersistentMemberId) {
        persistentMemberIDS = DataSerializer.readHashSet(in);
      }
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      if (diskStoreId == null) {
        out.writeBoolean(false);
      } else {
        out.writeBoolean(true);
        out.writeLong(diskStoreId.getMostSignificantBits());
        out.writeLong(diskStoreId.getLeastSignificantBits());
      }

      if (persistentMemberIDS != null) {
        out.writeBoolean(true);
        DataSerializer.writeHashSet(persistentMemberIDS, out);
      }
    }

    @Override
    public String toString() {
      return super.toString() + ",diskStoreId=" + diskStoreId + ", persistentMemberId: " + persistentMemberIDS;
    }
  }
}
