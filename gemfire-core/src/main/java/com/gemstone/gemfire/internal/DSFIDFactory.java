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

package com.gemstone.gemfire.internal;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.admin.internal.*;
import com.gemstone.gemfire.admin.jmx.internal.StatAlertNotification;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.client.internal.BridgeServerLoadMessage;
import com.gemstone.gemfire.cache.client.internal.locator.*;
import com.gemstone.gemfire.cache.client.internal.locator.wan.*;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSGatewayEventImpl;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.internal.*;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.types.CollectionTypeImpl;
import com.gemstone.gemfire.cache.query.internal.types.MapTypeImpl;
import com.gemstone.gemfire.cache.query.internal.types.ObjectTypeImpl;
import com.gemstone.gemfire.cache.query.internal.types.StructTypeImpl;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.locks.*;
import com.gemstone.gemfire.distributed.internal.locks.DLockRecoverGrantorProcessor.DLockRecoverGrantorMessage;
import com.gemstone.gemfire.distributed.internal.locks.DLockRecoverGrantorProcessor.DLockRecoverGrantorReplyMessage;
import com.gemstone.gemfire.distributed.internal.locks.NonGrantorDestroyedProcessor.NonGrantorDestroyedReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.streaming.StreamingOperation.StreamingReplyMessage;
import com.gemstone.gemfire.internal.admin.ClientMembershipMessage;
import com.gemstone.gemfire.internal.admin.remote.*;
import com.gemstone.gemfire.internal.admin.statalerts.GaugeThresholdDecoratorImpl;
import com.gemstone.gemfire.internal.admin.statalerts.NumberThresholdDecoratorImpl;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.BridgeServerAdvisor.BridgeServerProfile;
import com.gemstone.gemfire.internal.cache.ControllerAdvisor.ControllerProfile;
import com.gemstone.gemfire.internal.cache.DistributedClearOperation.ClearRegionMessage;
import com.gemstone.gemfire.internal.cache.DistributedClearOperation.ClearRegionWithContextMessage;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.EntryVersionsList;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.PutAllMessage;
import com.gemstone.gemfire.internal.cache.DistributedTombstoneOperation.TombstoneMessage;
import com.gemstone.gemfire.internal.cache.FindDurableQueueProcessor.FindDurableQueueMessage;
import com.gemstone.gemfire.internal.cache.FindDurableQueueProcessor.FindDurableQueueReply;
import com.gemstone.gemfire.internal.cache.FindVersionTagOperation.FindVersionTagMessage;
import com.gemstone.gemfire.internal.cache.FindVersionTagOperation.VersionTagReply;
import com.gemstone.gemfire.internal.cache.InitialImageFlowControl.FlowControlPermitMessage;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.InitialImageVersionedEntryList;
import com.gemstone.gemfire.internal.cache.InvalidateRegionOperation.InvalidateRegionMessage;
import com.gemstone.gemfire.internal.cache.RemoteRegionOperation.RemoteRegionOperationReplyMessage;
import com.gemstone.gemfire.internal.cache.SendQueueOperation.SendQueueMessage;
import com.gemstone.gemfire.internal.cache.StateFlushOperation.StateMarkerMessage;
import com.gemstone.gemfire.internal.cache.StateFlushOperation.StateStabilizationMessage;
import com.gemstone.gemfire.internal.cache.StateFlushOperation.StateStabilizedMessage;
import com.gemstone.gemfire.internal.cache.TXRemoteCommitPhase1Message.CommitPhase1ReplyMessage;
import com.gemstone.gemfire.internal.cache.UpdateEntryVersionOperation.UpdateEntryVersionMessage;
import com.gemstone.gemfire.internal.cache.VMIdAdvisor.VMIdProfile;
import com.gemstone.gemfire.internal.cache.compression.SnappyCompressedCachedDeserializable;
import com.gemstone.gemfire.internal.cache.control.ResourceAdvisor.ResourceManagerProfile;
import com.gemstone.gemfire.internal.cache.control.ResourceAdvisor.ResourceProfileMessage;
import com.gemstone.gemfire.internal.cache.ha.HARegionQueue.DispatchedAndCurrentEvents;
import com.gemstone.gemfire.internal.cache.ha.QueueRemovalMessage;
import com.gemstone.gemfire.internal.cache.partitioned.*;
import com.gemstone.gemfire.internal.cache.partitioned.BecomePrimaryBucketMessage.BecomePrimaryBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.BucketSizeMessage.BucketSizeReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.ContainsKeyValueMessage.ContainsKeyValueReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.CreateBucketMessage.CreateBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DeposePrimaryBucketMessage.DeposePrimaryBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.DumpB2NRegion.DumpB2NReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntriesMessage.FetchEntriesReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchEntryMessage.FetchEntryReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchKeysMessage.FetchKeysReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.FetchPartitionDetailsMessage.FetchPartitionDetailsReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.GetMessage.GetReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.IdentityRequestMessage.IdentityReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.IndexCreationMsg.IndexCreationReplyMsg;
import com.gemstone.gemfire.internal.cache.partitioned.InterestEventMessage.InterestEventReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.ManageBackupBucketMessage.ManageBackupBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.ManageBucketMessage.ManageBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.MoveBucketMessage.MoveBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PrimaryRequestMessage.PrimaryRequestReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage.PutAllReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage.PutReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.RemoveBucketMessage.RemoveBucketReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.RemoveIndexesMessage.RemoveIndexesReplyMessage;
import com.gemstone.gemfire.internal.cache.partitioned.SizeMessage.SizeReplyMessage;
import com.gemstone.gemfire.internal.cache.persistence.*;
import com.gemstone.gemfire.internal.cache.persistence.MembershipViewRequest.MembershipViewReplyMessage;
import com.gemstone.gemfire.internal.cache.persistence.PersistentStateQueryMessage.PersistentStateQueryReplyMessage;
import com.gemstone.gemfire.internal.cache.snapshot.FlowController.FlowControlAbortMessage;
import com.gemstone.gemfire.internal.cache.snapshot.FlowController.FlowControlAckMessage;
import com.gemstone.gemfire.internal.cache.snapshot.SnapshotPacket;
import com.gemstone.gemfire.internal.cache.snapshot.SnapshotPacket.SnapshotRecord;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier.ServerInterestRegistrationMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientBlacklistProcessor.ClientBlacklistMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.*;
import com.gemstone.gemfire.internal.cache.versions.DiskRegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.VMRegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VMVersionTag;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderAdvisor;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgumentImpl;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelQueueRemovalMessage;
import com.gemstone.gemfire.internal.cache.wan.serial.BatchDestroyOperation;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.management.internal.JmxManagerAdvisor.JmxManagerProfile;
import com.gemstone.gemfire.management.internal.JmxManagerAdvisor.JmxManagerProfileMessage;
import com.gemstone.gemfire.management.internal.JmxManagerLocatorRequest;
import com.gemstone.gemfire.management.internal.JmxManagerLocatorResponse;
import com.gemstone.gemfire.management.internal.ManagerStartupMessage;
import com.gemstone.gemfire.management.internal.cli.functions.CliFunctionResult;
import com.gemstone.gemfire.pdx.internal.CheckTypeRegistryState;
import com.gemstone.gemfire.pdx.internal.EnumId;
import com.gemstone.gemfire.pdx.internal.EnumInfo;
import com.gemstone.org.jgroups.View;
import com.gemstone.org.jgroups.protocols.pbcast.JoinRsp;
import com.gemstone.org.jgroups.stack.IpAddress;
import com.gemstone.org.jgroups.util.StreamableFixedID;
import io.snappydata.collection.IntObjectHashMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
import java.util.function.Supplier;

/**
 * Factory for instances of DataSerializableFixedID instances.
 * Note that this class implements DataSerializableFixedID to inherit constants but
 * is not actually an instance of this interface.
 *
 * @author Darrel Schneider
 * @since 5.7
 */
@SuppressWarnings("Convert2MethodRef")
public final class DSFIDFactory implements DataSerializableFixedID {
   
  private DSFIDFactory() {
    // no instances allowed
    throw new UnsupportedOperationException();
  }

  public final int getDSFID() {
    throw new UnsupportedOperationException();
  }
  public void toData(DataOutput out) throws IOException {
    throw new UnsupportedOperationException();
  }
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException();
  }

  public Version[] getSerializationVersions() {
    throw new UnsupportedOperationException();
  }

  private static volatile boolean typesRegistered;
  private static final Supplier[] dsfidMap = new Supplier[256];
  private static final IntObjectHashMap<Supplier<?>> dsfidMap2 =
      IntObjectHashMap.withExpectedSize(512);

  static {
    if (!InternalDistributedSystem.isHadoopGfxdLonerMode()) {
      registerTypes();
    }
  }

  static void registerDSFID(int dsfid, Supplier<?> creator) {
    if (dsfid >= Byte.MIN_VALUE && dsfid <= Byte.MAX_VALUE) {
      dsfidMap[dsfid + Byte.MAX_VALUE + 1] = creator;
    } else {
      dsfidMap2.justPut(dsfid, creator);
    }
  }

  public static synchronized void registerTypes() {
    if (typesRegistered) {
      return;
    }
    registerDSFID(CLIENT_TOMBSTONE_MESSAGE, () -> new ClientTombstoneMessage());
    registerDSFID(R_REGION_OP, () -> new RemoteRegionOperation());
    registerDSFID(R_REGION_OP_REPLY,
        () -> new RemoteRegionOperationReplyMessage());
    registerDSFID(WAIT_FOR_VIEW_INSTALLATION,
        () -> new WaitForViewInstallation());
    registerDSFID(DISPATCHED_AND_CURRENT_EVENTS,
        () -> new DispatchedAndCurrentEvents());
    registerDSFID(IP_ADDRESS, () -> new IpAddress());
    registerDSFID(DISTRIBUTED_MEMBER, () -> new InternalDistributedMember());
    registerDSFID(UPDATE_MESSAGE, () -> new UpdateOperation.UpdateMessage());
    registerDSFID(REPLY_MESSAGE, () -> new ReplyMessage());
    registerDSFID(PR_DESTROY, () -> new DestroyMessage());
    registerDSFID(CREATE_REGION_MESSAGE,
        () -> new CreateRegionProcessor.CreateRegionMessage());
    registerDSFID(CREATE_REGION_REPLY_MESSAGE,
        () -> new CreateRegionProcessor.CreateRegionReplyMessage());
    registerDSFID(REGION_STATE_MESSAGE,
        () -> new InitialImageOperation.RegionStateMessage());
    registerDSFID(QUERY_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.QueryMessage());
    registerDSFID(RESPONSE_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.ResponseMessage());
    registerDSFID(NET_SEARCH_REQUEST_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetSearchRequestMessage());
    registerDSFID(NET_SEARCH_REPLY_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetSearchReplyMessage());
    registerDSFID(NET_LOAD_REQUEST_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetLoadRequestMessage());
    registerDSFID(NET_LOAD_REPLY_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetLoadReplyMessage());
    registerDSFID(NET_WRITE_REQUEST_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetWriteRequestMessage());
    registerDSFID(NET_WRITE_REPLY_MESSAGE,
        () -> new SearchLoadAndWriteProcessor.NetWriteReplyMessage());
    registerDSFID(DLOCK_REQUEST_MESSAGE,
        () -> new DLockRequestProcessor.DLockRequestMessage());
    registerDSFID(DLOCK_RESPONSE_MESSAGE,
        () -> new DLockRequestProcessor.DLockResponseMessage());
    registerDSFID(DLOCK_RELEASE_MESSAGE,
        () -> new DLockReleaseProcessor.DLockReleaseMessage());
    registerDSFID(ADMIN_CACHE_EVENT_MESSAGE,
        () -> new SystemMemberCacheEventProcessor.SystemMemberCacheMessage());
    registerDSFID(CQ_ENTRY_EVENT, () -> new CqEntry());
    registerDSFID(REQUEST_IMAGE_MESSAGE,
        () -> new InitialImageOperation.RequestImageMessage());
    registerDSFID(IMAGE_REPLY_MESSAGE,
        () -> new InitialImageOperation.ImageReplyMessage());
    registerDSFID(IMAGE_ENTRY, () -> new InitialImageOperation.Entry());
    registerDSFID(CLOSE_CACHE_MESSAGE, () -> new CloseCacheMessage());
    registerDSFID(NON_GRANTOR_DESTROYED_MESSAGE,
        () -> new NonGrantorDestroyedProcessor.NonGrantorDestroyedMessage());
    registerDSFID(DLOCK_RELEASE_REPLY,
        () -> new DLockReleaseProcessor.DLockReleaseReplyMessage());
    registerDSFID(GRANTOR_REQUEST_MESSAGE,
        () -> new GrantorRequestProcessor.GrantorRequestMessage());
    registerDSFID(GRANTOR_INFO_REPLY_MESSAGE,
        () -> new GrantorRequestProcessor.GrantorInfoReplyMessage());
    registerDSFID(ELDER_INIT_MESSAGE,
        () -> new ElderInitProcessor.ElderInitMessage());
    registerDSFID(ELDER_INIT_REPLY_MESSAGE,
        () -> new ElderInitProcessor.ElderInitReplyMessage());
    registerDSFID(DEPOSE_GRANTOR_MESSAGE,
        () -> new DeposeGrantorProcessor.DeposeGrantorMessage());
    registerDSFID(STARTUP_MESSAGE, () -> new StartupMessage());
    registerDSFID(STARTUP_RESPONSE_MESSAGE, () -> new StartupResponseMessage());
    registerDSFID(STARTUP_RESPONSE_WITHVERSION_MESSAGE,
        () -> new StartupResponseWithVersionMessage());
    registerDSFID(SHUTDOWN_MESSAGE, () -> new ShutdownMessage());
    registerDSFID(DESTROY_REGION_MESSAGE,
        () -> new DestroyRegionOperation.DestroyRegionMessage());
    registerDSFID(PR_PUTALL_MESSAGE, () -> new PutAllPRMessage());
    registerDSFID(PR_PUT_MESSAGE, () -> new PutMessage());
    registerDSFID(INVALIDATE_MESSAGE,
        () -> new InvalidateOperation.InvalidateMessage());
    registerDSFID(DESTROY_MESSAGE, () -> new DestroyOperation.DestroyMessage());
    registerDSFID(DA_PROFILE, () -> new DistributionAdvisor.Profile());
    registerDSFID(CACHE_PROFILE,
        () -> new CacheDistributionAdvisor.CacheProfile());
    registerDSFID(HA_PROFILE, () -> new HARegion.HARegionAdvisor.HAProfile());
    registerDSFID(ENTRY_EVENT, () -> new EntryEventImpl());
    registerDSFID(UPDATE_ATTRIBUTES_MESSAGE,
        () -> new UpdateAttributesProcessor.UpdateAttributesMessage());
    registerDSFID(PROFILE_REPLY_MESSAGE,
        () -> new UpdateAttributesProcessor.ProfileReplyMessage());
    registerDSFID(PROFILES_REPLY_MESSAGE,
        () -> new UpdateAttributesProcessor.ProfilesReplyMessage());
    registerDSFID(REGION_EVENT, () -> new RegionEventImpl());
    registerDSFID(FILTER_PROFILE, () -> new FilterProfile());
    registerDSFID(REMOTE_PUTALL_REPLY_MESSAGE,
        () -> new RemotePutAllMessage.PutAllReplyMessage());
    registerDSFID(REMOTE_PUTALL_MESSAGE, () -> new RemotePutAllMessage());
    registerDSFID(VERSION_TAG, () -> new VMVersionTag());
    registerDSFID(ADD_CACHESERVER_PROFILE_UPDATE,
        () -> new AddCacheServerProfileMessage());
    registerDSFID(SERVER_INTEREST_REGISTRATION_MESSAGE,
        () -> new ServerInterestRegistrationMessage());
    registerDSFID(FILTER_PROFILE_UPDATE,
        () -> new FilterProfile.OperationMessage());
    registerDSFID(PR_GET_MESSAGE, () -> new GetMessage());
    registerDSFID(R_FETCH_ENTRY_MESSAGE, () -> new RemoteFetchEntryMessage());
    registerDSFID(R_FETCH_ENTRY_REPLY_MESSAGE,
        () -> new RemoteFetchEntryMessage.FetchEntryReplyMessage());
    registerDSFID(R_CONTAINS_MESSAGE,
        () -> new RemoteContainsKeyValueMessage());
    registerDSFID(R_CONTAINS_REPLY_MESSAGE,
        () -> new RemoteContainsKeyValueMessage.RemoteContainsKeyValueReplyMessage());
    registerDSFID(R_DESTROY_MESSAGE, () -> new RemoteDestroyMessage());
    registerDSFID(R_DESTROY_REPLY_MESSAGE,
        () -> new RemoteDestroyMessage.DestroyReplyMessage());
    registerDSFID(R_INVALIDATE_MESSAGE, () -> new RemoteInvalidateMessage());
    registerDSFID(R_INVALIDATE_REPLY_MESSAGE,
        () -> new RemoteInvalidateMessage.InvalidateReplyMessage());
    registerDSFID(R_GET_MESSAGE, () -> new RemoteGetMessage());
    registerDSFID(R_GET_REPLY_MESSAGE,
        () -> new RemoteGetMessage.GetReplyMessage());
    registerDSFID(R_PUT_MESSAGE, () -> new RemotePutMessage());
    registerDSFID(R_PUT_REPLY_MESSAGE,
        () -> new RemotePutMessage.PutReplyMessage());
    registerDSFID(PR_DESTROY_REPLY_MESSAGE,
        () -> new DestroyMessage.DestroyReplyMessage());
    registerDSFID(CLI_FUNCTION_RESULT, () -> new CliFunctionResult());
    registerDSFID(R_FETCH_KEYS_MESSAGE, () -> new RemoteFetchKeysMessage());
    registerDSFID(R_FETCH_KEYS_REPLY,
        () -> new RemoteFetchKeysMessage.RemoteFetchKeysReplyMessage());
    registerDSFID(PR_GET_REPLY_MESSAGE, () -> new GetReplyMessage());
    registerDSFID(PR_NODE, () -> new Node());
    registerDSFID(UPDATE_WITH_CONTEXT_MESSAGE,
        () -> new UpdateOperation.UpdateWithContextMessage());
    registerDSFID(DESTROY_WITH_CONTEXT_MESSAGE,
        () -> new DestroyOperation.DestroyWithContextMessage());
    registerDSFID(INVALIDATE_WITH_CONTEXT_MESSAGE,
        () -> new InvalidateOperation.InvalidateWithContextMessage());
    registerDSFID(REGION_VERSION_VECTOR, () -> new VMRegionVersionVector());
    registerDSFID(CLIENT_PROXY_MEMBERSHIPID,
        () -> new ClientProxyMembershipID());
    registerDSFID(EVENT_ID, () -> new EventID());
    registerDSFID(CLIENT_UPDATE_MESSAGE, () -> new ClientUpdateMessageImpl());
    registerDSFID(CLEAR_REGION_MESSAGE_WITH_CONTEXT,
        () -> new ClearRegionWithContextMessage());
    registerDSFID(CLIENT_INSTANTIATOR_MESSAGE,
        () -> new ClientInstantiatorMessage());
    registerDSFID(CLIENT_DATASERIALIZER_MESSAGE,
        () -> new ClientDataSerializerMessage());
    registerDSFID(REGISTRATION_MESSAGE,
        () -> new InternalInstantiator.RegistrationMessage());
    registerDSFID(REGISTRATION_CONTEXT_MESSAGE,
        () -> new InternalInstantiator.RegistrationContextMessage());
    registerDSFID(RESULTS_COLLECTION_WRAPPER,
        () -> new ResultsCollectionWrapper());
    registerDSFID(RESULTS_SET, () -> new ResultsSet());
    registerDSFID(SORTED_RESULT_SET, () -> new SortedResultSet());
    registerDSFID(SORTED_STRUCT_SET, () -> new SortedStructSet());
    registerDSFID(UNDEFINED, () -> new Undefined());
    registerDSFID(STRUCT_IMPL, () -> new StructImpl());
    registerDSFID(STRUCT_SET, () -> new StructSet());
    registerDSFID(END_OF_BUCKET, () -> new PRQueryProcessor.EndOfBucket());
    registerDSFID(STRUCT_BAG, () -> new StructBag());
    registerDSFID(LINKED_RESULTSET, () -> new LinkedResultSet());
    registerDSFID(LINKED_STRUCTSET, () -> new LinkedStructSet());
    registerDSFID(PR_BUCKET_BACKUP_MESSAGE, () -> new BucketBackupMessage());
    registerDSFID(PR_BUCKET_PROFILE_UPDATE_MESSAGE,
        () -> new BucketProfileUpdateMessage());
    registerDSFID(PR_ALL_BUCKET_PROFILES_UPDATE_MESSAGE,
        () -> new AllBucketProfilesUpdateMessage());
    registerDSFID(PR_BUCKET_SIZE_MESSAGE, () -> new BucketSizeMessage());
    registerDSFID(PR_CONTAINS_KEY_VALUE_MESSAGE,
        () -> new ContainsKeyValueMessage());
    registerDSFID(PR_DUMP_ALL_PR_CONFIG_MESSAGE,
        () -> new DumpAllPRConfigMessage());
    registerDSFID(PR_DUMP_BUCKETS_MESSAGE, () -> new DumpBucketsMessage());
    registerDSFID(PR_FETCH_ENTRIES_MESSAGE, () -> new FetchEntriesMessage());
    registerDSFID(PR_FETCH_ENTRY_MESSAGE, () -> new FetchEntryMessage());
    registerDSFID(PR_FETCH_KEYS_MESSAGE, () -> new FetchKeysMessage());
    registerDSFID(PR_FLUSH_MESSAGE, () -> new FlushMessage());
    registerDSFID(PR_IDENTITY_REQUEST_MESSAGE,
        () -> new IdentityRequestMessage());
    registerDSFID(PR_IDENTITY_UPDATE_MESSAGE,
        () -> new IdentityUpdateMessage());
    registerDSFID(PR_INDEX_CREATION_MSG, () -> new IndexCreationMsg());
    registerDSFID(PR_MANAGE_BUCKET_MESSAGE, () -> new ManageBucketMessage());
    registerDSFID(PR_PRIMARY_REQUEST_MESSAGE,
        () -> new PrimaryRequestMessage());
    registerDSFID(PR_PRIMARY_REQUEST_REPLY_MESSAGE,
        () -> new PrimaryRequestReplyMessage());
    registerDSFID(PR_SANITY_CHECK_MESSAGE, () -> new PRSanityCheckMessage());
    registerDSFID(PR_PUTALL_REPLY_MESSAGE, () -> new PutAllReplyMessage());
    registerDSFID(PR_PUT_REPLY_MESSAGE, () -> new PutReplyMessage());
    registerDSFID(PR_QUERY_MESSAGE, () -> new QueryMessage());
    registerDSFID(PR_REMOVE_INDEXES_MESSAGE, () -> new RemoveIndexesMessage());
    registerDSFID(PR_REMOVE_INDEXES_REPLY_MESSAGE,
        () -> new RemoveIndexesReplyMessage());
    registerDSFID(PR_SIZE_MESSAGE, () -> new SizeMessage());
    registerDSFID(PR_SIZE_REPLY_MESSAGE, () -> new SizeReplyMessage());
    registerDSFID(PR_BUCKET_SIZE_REPLY_MESSAGE,
        () -> new BucketSizeReplyMessage());
    registerDSFID(PR_CONTAINS_KEY_VALUE_REPLY_MESSAGE,
        () -> new ContainsKeyValueReplyMessage());
    registerDSFID(PR_FETCH_ENTRIES_REPLY_MESSAGE,
        () -> new FetchEntriesReplyMessage());
    registerDSFID(PR_FETCH_ENTRY_REPLY_MESSAGE,
        () -> new FetchEntryReplyMessage());
    registerDSFID(PR_IDENTITY_REPLY_MESSAGE, () -> new IdentityReplyMessage());
    registerDSFID(PR_INDEX_CREATION_REPLY_MSG,
        () -> new IndexCreationReplyMsg());
    registerDSFID(PR_MANAGE_BUCKET_REPLY_MESSAGE,
        () -> new ManageBucketReplyMessage());
    registerDSFID(PR_FETCH_KEYS_REPLY_MESSAGE,
        () -> new FetchKeysReplyMessage());
    registerDSFID(PR_DUMP_B2N_REGION_MSG, () -> new DumpB2NRegion());
    registerDSFID(PR_DUMP_B2N_REPLY_MESSAGE, () -> new DumpB2NReplyMessage());
    registerDSFID(DESTROY_PARTITIONED_REGION_MESSAGE,
        () -> new DestroyPartitionedRegionMessage());
    registerDSFID(INVALIDATE_PARTITIONED_REGION_MESSAGE,
        () -> new InvalidatePartitionedRegionMessage());
    registerDSFID(DESTROY_REGION_WITH_CONTEXT_MESSAGE,
        () -> new DestroyRegionOperation.DestroyRegionWithContextMessage());
    registerDSFID(PUT_ALL_MESSAGE, () -> new PutAllMessage());
    registerDSFID(CLEAR_REGION_MESSAGE, () -> new ClearRegionMessage());
    registerDSFID(TOMBSTONE_MESSAGE, () -> new TombstoneMessage());
    registerDSFID(INVALIDATE_REGION_MESSAGE,
        () -> new InvalidateRegionMessage());
    registerDSFID(SEND_QUEUE_MESSAGE, () -> new SendQueueMessage());
    registerDSFID(STATE_MARKER_MESSAGE, () -> new StateMarkerMessage());
    registerDSFID(STATE_STABILIZATION_MESSAGE,
        () -> new StateStabilizationMessage());
    registerDSFID(STATE_STABILIZED_MESSAGE, () -> new StateStabilizedMessage());
    registerDSFID(CLIENT_MARKER_MESSAGE_IMPL,
        () -> new ClientMarkerMessageImpl());
    //registerDSFID(TX_LOCK_UPDATE_PARTICIPANTS_MESSAGE,
    //  () -> new TXLockUpdateParticipantsMessage());
    //registerDSFID(TX_ORIGINATOR_RECOVERY_MESSAGE,
    //  () -> new TXOriginatorRecoveryMessage());
    //registerDSFID(TX_ORIGINATOR_RECOVERY_REPLY_MESSAGE,
    //  () -> new TXOriginatorRecoveryReplyMessage());
    registerDSFID(TX_REMOTE_COMMIT_MESSAGE, () -> new TXRemoteCommitMessage());
    registerDSFID(TX_REMOTE_ROLLBACK_MESSAGE,
        () -> new TXRemoteRollbackMessage());
    registerDSFID(TX_REMOTE_COMMIT_PHASE1_MESSAGE,
        () -> new TXRemoteCommitPhase1Message());
    //registerDSFID(JTA_BEFORE_COMPLETION_MESSAGE,
    //  () -> new JtaBeforeCompletionMessage());
    //registerDSFID(JTA_AFTER_COMPLETION_MESSAGE,
    //  () -> new JtaAfterCompletionMessage());
    registerDSFID(TX_NEW_GII_NODE, () -> new TXNewGIINode());
    registerDSFID(TX_BATCH_MESSAGE, () -> new TXBatchMessage());
    registerDSFID(TX_BATCH_REPLY_MESSAGE,
        () -> new TXBatchMessage.TXBatchReply());
    registerDSFID(TX_CLEANUP_ENTRY_MESSAGE, () -> new TXCleanupEntryMessage());
    registerDSFID(QUEUE_REMOVAL_MESSAGE, () -> new QueueRemovalMessage());
    registerDSFID(DLOCK_RECOVER_GRANTOR_MESSAGE,
        () -> new DLockRecoverGrantorMessage());
    registerDSFID(DLOCK_RECOVER_GRANTOR_REPLY_MESSAGE,
        () -> new DLockRecoverGrantorReplyMessage());
    registerDSFID(NON_GRANTOR_DESTROYED_REPLY_MESSAGE,
        () -> new NonGrantorDestroyedReplyMessage());
    registerDSFID(IDS_REGISTRATION_MESSAGE,
        () -> new InternalDataSerializer.RegistrationMessage());
    registerDSFID(PR_FETCH_PARTITION_DETAILS_MESSAGE,
        () -> new FetchPartitionDetailsMessage());
    registerDSFID(PR_FETCH_PARTITION_DETAILS_REPLY,
        () -> new FetchPartitionDetailsReplyMessage());
    registerDSFID(PR_DEPOSE_PRIMARY_BUCKET_MESSAGE,
        () -> new DeposePrimaryBucketMessage());
    registerDSFID(PR_DEPOSE_PRIMARY_BUCKET_REPLY,
        () -> new DeposePrimaryBucketReplyMessage());
    registerDSFID(PR_BECOME_PRIMARY_BUCKET_MESSAGE,
        () -> new BecomePrimaryBucketMessage());
    registerDSFID(PR_BECOME_PRIMARY_BUCKET_REPLY,
        () -> new BecomePrimaryBucketReplyMessage());
    registerDSFID(PR_REMOVE_BUCKET_MESSAGE, () -> new RemoveBucketMessage());
    /* TODO: merge: can be useful when new TX adds HA support
    registerDSFID(TX_MANAGER_REMOVE_TRANSACTIONS,
        () -> new TXManagerImpl.TXRemovalMessage());
    */
    registerDSFID(PR_REMOVE_BUCKET_REPLY, () -> new RemoveBucketReplyMessage());
    registerDSFID(PR_MOVE_BUCKET_MESSAGE, () -> new MoveBucketMessage());
    registerDSFID(PR_MOVE_BUCKET_REPLY, () -> new MoveBucketReplyMessage());
    registerDSFID(ADD_HEALTH_LISTENER_REQUEST,
        () -> new AddHealthListenerRequest());
    registerDSFID(ADD_HEALTH_LISTENER_RESPONSE,
        () -> new AddHealthListenerResponse());
    registerDSFID(ADD_STAT_LISTENER_REQUEST,
        () -> new AddStatListenerRequest());
    registerDSFID(ADD_STAT_LISTENER_RESPONSE,
        () -> new AddStatListenerResponse());
    registerDSFID(ADMIN_CONSOLE_DISCONNECT_MESSAGE,
        () -> new AdminConsoleDisconnectMessage());
    registerDSFID(ADMIN_CONSOLE_MESSAGE, () -> new AdminConsoleMessage());
    registerDSFID(MANAGER_STARTUP_MESSAGE, () -> new ManagerStartupMessage());
    registerDSFID(JMX_MANAGER_LOCATOR_REQUEST,
        () -> new JmxManagerLocatorRequest());
    registerDSFID(JMX_MANAGER_LOCATOR_RESPONSE,
        () -> new JmxManagerLocatorResponse());
    registerDSFID(ADMIN_FAILURE_RESPONSE, () -> new AdminFailureResponse());
    registerDSFID(ALERT_LEVEL_CHANGE_MESSAGE,
        () -> new AlertLevelChangeMessage());
    registerDSFID(ALERT_LISTENER_MESSAGE, () -> new AlertListenerMessage());
    registerDSFID(APP_CACHE_SNAPSHOT_MESSAGE,
        () -> new AppCacheSnapshotMessage());
    registerDSFID(BRIDGE_SERVER_REQUEST, () -> new BridgeServerRequest());
    registerDSFID(BRIDGE_SERVER_RESPONSE, () -> new BridgeServerResponse());
    registerDSFID(CACHE_CONFIG_REQUEST, () -> new CacheConfigRequest());
    registerDSFID(CACHE_CONFIG_RESPONSE, () -> new CacheConfigResponse());
    registerDSFID(CACHE_INFO_REQUEST, () -> new CacheInfoRequest());
    registerDSFID(CACHE_INFO_RESPONSE, () -> new CacheInfoResponse());
    registerDSFID(CANCELLATION_MESSAGE, () -> new CancellationMessage());
    registerDSFID(CANCEL_STAT_LISTENER_REQUEST,
        () -> new CancelStatListenerRequest());
    registerDSFID(CANCEL_STAT_LISTENER_RESPONSE,
        () -> new CancelStatListenerResponse());
    registerDSFID(DESTROY_ENTRY_MESSAGE, () -> new DestroyEntryMessage());
    registerDSFID(ADMIN_DESTROY_REGION_MESSAGE,
        () -> new DestroyRegionMessage());
    registerDSFID(FETCH_DIST_LOCK_INFO_REQUEST,
        () -> new FetchDistLockInfoRequest());
    registerDSFID(FETCH_DIST_LOCK_INFO_RESPONSE,
        () -> new FetchDistLockInfoResponse());
    registerDSFID(FETCH_HEALTH_DIAGNOSIS_REQUEST,
        () -> new FetchHealthDiagnosisRequest());
    registerDSFID(FETCH_HEALTH_DIAGNOSIS_RESPONSE,
        () -> new FetchHealthDiagnosisResponse());
    registerDSFID(FETCH_HOST_REQUEST, () -> new FetchHostRequest());
    registerDSFID(FETCH_HOST_RESPONSE, () -> new FetchHostResponse());
    registerDSFID(FETCH_RESOURCE_ATTRIBUTES_REQUEST,
        () -> new FetchResourceAttributesRequest());
    registerDSFID(FETCH_RESOURCE_ATTRIBUTES_RESPONSE,
        () -> new FetchResourceAttributesResponse());
    registerDSFID(FETCH_STATS_REQUEST, () -> new FetchStatsRequest());
    registerDSFID(FETCH_STATS_RESPONSE, () -> new FetchStatsResponse());
    registerDSFID(FETCH_SYS_CFG_REQUEST, () -> new FetchSysCfgRequest());
    registerDSFID(FETCH_SYS_CFG_RESPONSE, () -> new FetchSysCfgResponse());
    registerDSFID(FLUSH_APP_CACHE_SNAPSHOT_MESSAGE,
        () -> new FlushAppCacheSnapshotMessage());
    registerDSFID(HEALTH_LISTENER_MESSAGE, () -> new HealthListenerMessage());
    registerDSFID(OBJECT_DETAILS_REQUEST, () -> new ObjectDetailsRequest());
    registerDSFID(OBJECT_DETAILS_RESPONSE, () -> new ObjectDetailsResponse());
    registerDSFID(OBJECT_NAMES_REQUEST, () -> new ObjectNamesRequest());
    registerDSFID(OBJECT_NAMES_RESPONSE, () -> new ObjectNamesResponse());
    registerDSFID(REGION_ATTRIBUTES_REQUEST,
        () -> new RegionAttributesRequest());
    registerDSFID(REGION_ATTRIBUTES_RESPONSE,
        () -> new RegionAttributesResponse());
    registerDSFID(REGION_REQUEST, () -> new RegionRequest());
    registerDSFID(REGION_RESPONSE, () -> new RegionResponse());
    registerDSFID(REGION_SIZE_REQUEST, () -> new RegionSizeRequest());
    registerDSFID(REGION_SIZE_RESPONSE, () -> new RegionSizeResponse());
    registerDSFID(REGION_STATISTICS_REQUEST,
        () -> new RegionStatisticsRequest());
    registerDSFID(REGION_STATISTICS_RESPONSE,
        () -> new RegionStatisticsResponse());
    registerDSFID(REMOVE_HEALTH_LISTENER_REQUEST,
        () -> new RemoveHealthListenerRequest());
    registerDSFID(REMOVE_HEALTH_LISTENER_RESPONSE,
        () -> new RemoveHealthListenerResponse());
    registerDSFID(RESET_HEALTH_STATUS_REQUEST,
        () -> new ResetHealthStatusRequest());
    registerDSFID(RESET_HEALTH_STATUS_RESPONSE,
        () -> new ResetHealthStatusResponse());
    registerDSFID(ROOT_REGION_REQUEST, () -> new RootRegionRequest());
    registerDSFID(ROOT_REGION_RESPONSE, () -> new RootRegionResponse());
    registerDSFID(SNAPSHOT_RESULT_MESSAGE, () -> new SnapshotResultMessage());
    registerDSFID(STAT_LISTENER_MESSAGE, () -> new StatListenerMessage());
    registerDSFID(STORE_SYS_CFG_REQUEST, () -> new StoreSysCfgRequest());
    registerDSFID(STORE_SYS_CFG_RESPONSE, () -> new StoreSysCfgResponse());
    registerDSFID(SUB_REGION_REQUEST, () -> new SubRegionRequest());
    registerDSFID(SUB_REGION_RESPONSE, () -> new SubRegionResponse());
    registerDSFID(TAIL_LOG_REQUEST, () -> new TailLogRequest());
    registerDSFID(TAIL_LOG_RESPONSE, () -> new TailLogResponse());
    registerDSFID(VERSION_INFO_REQUEST, () -> new VersionInfoRequest());
    registerDSFID(VERSION_INFO_RESPONSE, () -> new VersionInfoResponse());
    registerDSFID(HIGH_PRIORITY_ACKED_MESSAGE,
        () -> new HighPriorityAckedMessage());
    registerDSFID(SERIAL_ACKED_MESSAGE, () -> new SerialAckedMessage());
    registerDSFID(BUCKET_PROFILE, () -> new BucketAdvisor.BucketProfile());
    registerDSFID(SERVER_BUCKET_PROFILE,
        () -> new BucketAdvisor.ServerBucketProfile());
    registerDSFID(PARTITION_PROFILE,
        () -> new RegionAdvisor.PartitionProfile());
    registerDSFID(GATEWAY_SENDER_PROFILE,
        () -> new GatewaySenderAdvisor.GatewaySenderProfile());
    registerDSFID(ROLE_EVENT, () -> new RoleEventImpl());
    registerDSFID(BRIDGE_REGION_EVENT, () -> new BridgeRegionEventImpl());
    registerDSFID(PR_INVALIDATE_MESSAGE, () -> new InvalidateMessage());
    registerDSFID(PR_INVALIDATE_REPLY_MESSAGE,
        () -> new InvalidateMessage.InvalidateReplyMessage());
    registerDSFID(STREAMING_REPLY_MESSAGE, () -> new StreamingReplyMessage());
    registerDSFID(PARTITION_REGION_CONFIG, () -> new PartitionRegionConfig());
    registerDSFID(PREFER_BYTES_CACHED_DESERIALIZABLE,
        () -> new PreferBytesCachedDeserializable());
    registerDSFID(VM_CACHED_DESERIALIZABLE, () -> new VMCachedDeserializable());
    registerDSFID(GATEWAY_EVENT_IMPL, () -> new GatewayEventImpl());
    registerDSFID(GATEWAY_SENDER_EVENT_IMPL,
        () -> new GatewaySenderEventImpl());
    registerDSFID(SUSPEND_LOCKING_TOKEN,
        () -> new DLockService.SuspendLockingToken());
    registerDSFID(OBJECT_TYPE_IMPL, () -> new ObjectTypeImpl());
    registerDSFID(STRUCT_TYPE_IMPL, () -> new StructTypeImpl());
    registerDSFID(COLLECTION_TYPE_IMPL, () -> new CollectionTypeImpl());
    registerDSFID(GATEWAY_EVENT_CALLBACK_ARGUMENT,
        () -> new GatewayEventCallbackArgument());
    registerDSFID(GATEWAY_SENDER_EVENT_CALLBACK_ARGUMENT,
        () -> new GatewaySenderEventCallbackArgumentImpl());
    registerDSFID(GATEWAY_EVENT_CALLBACK_ARGUMENT,
        () -> new GatewayEventCallbackArgument());
    registerDSFID(GATEWAY_SENDER_EVENT_CALLBACK_ARGUMENT,
        () -> new GatewaySenderEventCallbackArgumentImpl());
    registerDSFID(MAP_TYPE_IMPL, () -> new MapTypeImpl());
    registerDSFID(STORE_ALL_CACHED_DESERIALIZABLE,
        () -> new StoreAllCachedDeserializable());
    registerDSFID(INTEREST_EVENT_MESSAGE, () -> new InterestEventMessage());
    registerDSFID(INTEREST_EVENT_REPLY_MESSAGE,
        () -> new InterestEventReplyMessage());
    registerDSFID(HA_EVENT_WRAPPER, () -> new HAEventWrapper());
    registerDSFID(STAT_ALERTS_MGR_ASSIGN_MESSAGE,
        () -> new StatAlertsManagerAssignMessage());
    registerDSFID(UPDATE_ALERTS_DEFN_MESSAGE,
        () -> new UpdateAlertDefinitionMessage());
    registerDSFID(REFRESH_MEMBER_SNAP_REQUEST,
        () -> new RefreshMemberSnapshotRequest());
    registerDSFID(REFRESH_MEMBER_SNAP_RESPONSE,
        () -> new RefreshMemberSnapshotResponse());
    registerDSFID(REGION_SUB_SIZE_REQUEST,
        () -> new RegionSubRegionSizeRequest());
    registerDSFID(REGION_SUB_SIZE_RESPONSE,
        () -> new RegionSubRegionsSizeResponse());
    registerDSFID(CHANGE_REFRESH_INT_MESSAGE,
        () -> new ChangeRefreshIntervalMessage());
    registerDSFID(ALERTS_NOTIF_MESSAGE, () -> new AlertsNotificationMessage());
    registerDSFID(FIND_DURABLE_QUEUE, () -> new FindDurableQueueMessage());
    registerDSFID(FIND_DURABLE_QUEUE_REPLY, () -> new FindDurableQueueReply());
    registerDSFID(BRIDGE_SERVER_LOAD_MESSAGE,
        () -> new BridgeServerLoadMessage());
    registerDSFID(BRIDGE_SERVER_PROFILE, () -> new BridgeServerProfile());
    registerDSFID(CONTROLLER_PROFILE, () -> new ControllerProfile());
    registerDSFID(DLOCK_QUERY_MESSAGE,
        () -> new DLockQueryProcessor.DLockQueryMessage());
    registerDSFID(DLOCK_QUERY_REPLY,
        () -> new DLockQueryProcessor.DLockQueryReplyMessage());
    registerDSFID(VMID_PROFILE_MESSAGE, () -> new VMIdProfile());
    registerDSFID(PERSISTENT_UUID_PROFILE_MESSAGE,
        () -> new PersistentUUIDProfile());
    registerDSFID(LOCATOR_LIST_REQUEST, () -> new LocatorListRequest());
    registerDSFID(LOCATOR_LIST_RESPONSE, () -> new LocatorListResponse());
    registerDSFID(REMOTE_LOCATOR_JOIN_REQUEST,
        () -> new RemoteLocatorJoinRequest());
    registerDSFID(REMOTE_LOCATOR_JOIN_RESPONSE,
        () -> new RemoteLocatorJoinResponse());
    registerDSFID(REMOTE_LOCATOR_REQUEST, () -> new RemoteLocatorRequest());
    registerDSFID(REMOTE_LOCATOR_RESPONSE, () -> new RemoteLocatorResponse());
    registerDSFID(LOCATOR_JOIN_MESSAGE, () -> new LocatorJoinMessage());
    registerDSFID(REMOTE_LOCATOR_PING_REQUEST,
        () -> new RemoteLocatorPingRequest());
    registerDSFID(REMOTE_LOCATOR_PING_RESPONSE,
        () -> new RemoteLocatorPingResponse());
    registerDSFID(CLIENT_CONNECTION_REQUEST,
        () -> new ClientConnectionRequest());
    registerDSFID(CLIENT_CONNECTION_RESPONSE,
        () -> new ClientConnectionResponse());
    registerDSFID(QUEUE_CONNECTION_REQUEST,
        () -> new QueueConnectionRequest());
    registerDSFID(QUEUE_CONNECTION_RESPONSE,
        () -> new QueueConnectionResponse());
    registerDSFID(CLIENT_REPLACEMENT_REQUEST,
        () -> new ClientReplacementRequest());
    registerDSFID(OBJECT_PART_LIST, () -> new ObjectPartList());
    registerDSFID(VERSIONED_OBJECT_LIST, () -> new VersionedObjectList());
    registerDSFID(OBJECT_PART_LIST66, () -> new ObjectPartList651());
    registerDSFID(JGROUPS_VIEW, () -> new View());
    registerDSFID(JGROUPS_JOIN_RESP, () -> new JoinRsp());
    registerDSFID(PUTALL_VERSIONS_LIST, () -> new EntryVersionsList());
    registerDSFID(INITIAL_IMAGE_VERSIONED_OBJECT_LIST,
        () -> new InitialImageVersionedEntryList());
    registerDSFID(FIND_VERSION_TAG, () -> new FindVersionTagMessage());
    registerDSFID(VERSION_TAG_REPLY, () -> new VersionTagReply());
    registerDSFID(DURABLE_CLIENT_INFO_REQUEST,
        () -> new DurableClientInfoRequest());
    registerDSFID(DURABLE_CLIENT_INFO_RESPONSE,
        () -> new DurableClientInfoResponse());
    registerDSFID(CLIENT_INTEREST_MESSAGE,
        () -> new ClientInterestMessageImpl());
    registerDSFID(STAT_ALERT_DEFN_NUM_THRESHOLD,
        () -> new NumberThresholdDecoratorImpl());
    registerDSFID(STAT_ALERT_DEFN_GAUGE_THRESHOLD,
        () -> new GaugeThresholdDecoratorImpl());
    registerDSFID(CLIENT_HEALTH_STATS, () -> new ClientHealthStats());
    registerDSFID(STAT_ALERT_NOTIFICATION, () -> new StatAlertNotification());
    registerDSFID(FILTER_INFO_MESSAGE,
        () -> new InitialImageOperation.FilterInfoMessage());
    registerDSFID(SIZED_BASED_LOAD_PROBE, () -> new SizedBasedLoadProbe());
    registerDSFID(PR_MANAGE_BACKUP_BUCKET_MESSAGE,
        () -> new ManageBackupBucketMessage());
    registerDSFID(PR_MANAGE_BACKUP_BUCKET_REPLY_MESSAGE,
        () -> new ManageBackupBucketReplyMessage());
    registerDSFID(PR_CREATE_BUCKET_MESSAGE, () -> new CreateBucketMessage());
    registerDSFID(PR_CREATE_BUCKET_REPLY_MESSAGE,
        () -> new CreateBucketReplyMessage());
    registerDSFID(RESOURCE_MANAGER_PROFILE, () -> new ResourceManagerProfile());
    registerDSFID(RESOURCE_PROFILE_MESSAGE, () -> new ResourceProfileMessage());
    registerDSFID(JMX_MANAGER_PROFILE, () -> new JmxManagerProfile());
    registerDSFID(JMX_MANAGER_PROFILE_MESSAGE,
        () -> new JmxManagerProfileMessage());
    registerDSFID(CLIENT_BLACKLIST_MESSAGE, () -> new ClientBlacklistMessage());
    registerDSFID(REMOVE_CLIENT_FROM_BLACKLIST_MESSAGE,
        () -> new RemoveClientFromBlacklistMessage());
    registerDSFID(PR_FUNCTION_STREAMING_MESSAGE,
        () -> new PartitionedRegionFunctionStreamingMessage());
    registerDSFID(MEMBER_FUNCTION_STREAMING_MESSAGE,
        () -> new MemberFunctionStreamingMessage());
    registerDSFID(DR_FUNCTION_STREAMING_MESSAGE,
        () -> new DistributedRegionFunctionStreamingMessage());
    registerDSFID(FUNCTION_STREAMING_REPLY_MESSAGE,
        () -> new FunctionStreamingReplyMessage());
    registerDSFID(GET_ALL_SERVERS_REQUEST, () -> new GetAllServersRequest());
    registerDSFID(GET_ALL_SERVRES_RESPONSE, () -> new GetAllServersResponse());
    registerDSFID(PERSISTENT_MEMBERSHIP_VIEW_REQUEST,
        () -> new MembershipViewRequest());
    registerDSFID(PERSISTENT_MEMBERSHIP_VIEW_REPLY,
        () -> new MembershipViewReplyMessage());
    registerDSFID(PERSISTENT_STATE_QUERY_REQUEST,
        () -> new PersistentStateQueryMessage());
    registerDSFID(PERSISTENT_STATE_QUERY_REPLY,
        () -> new PersistentStateQueryReplyMessage());
    registerDSFID(PREPARE_NEW_PERSISTENT_MEMBER_REQUEST,
        () -> new PrepareNewPersistentMemberMessage());
    registerDSFID(MISSING_PERSISTENT_IDS_REQUEST,
        () -> new MissingPersistentIDsRequest());
    registerDSFID(MISSING_PERSISTENT_IDS_RESPONSE,
        () -> new MissingPersistentIDsResponse());
    registerDSFID(REVOKE_PERSISTENT_ID_REQUEST,
        () -> new RevokePersistentIDRequest());
    registerDSFID(REVOKE_PERSISTENT_ID_RESPONSE,
        () -> new RevokePersistentIDResponse());
    registerDSFID(REMOVE_PERSISTENT_MEMBER_REQUEST,
        () -> new RemovePersistentMemberMessage());
    registerDSFID(FUNCTION_STREAMING_ORDERED_REPLY_MESSAGE,
        () -> new FunctionStreamingOrderedReplyMessage());
    registerDSFID(REQUEST_SYNC_MESSAGE,
        () -> new InitialImageOperation.RequestSyncMessage());
    registerDSFID(PERSISTENT_MEMBERSHIP_FLUSH_REQUEST,
        () -> new MembershipFlushRequest());
    registerDSFID(SHUTDOWN_ALL_REQUEST, () -> new ShutdownAllRequest());
    registerDSFID(SHUTDOWN_ALL_RESPONSE, () -> new ShutdownAllResponse());
    registerDSFID(SHUTDOWN_ALL_GATEWAYHUBS_REQUEST,
        () -> new ShutdownAllGatewayHubsRequest());
    registerDSFID(SHUTDOWN_ALL_GATEWAYHUBS_RESPONSE,
        () -> new ShutdownAllGatewayHubsResponse());
    registerDSFID(CLIENT_MEMBERSHIP_MESSAGE,
        () -> new ClientMembershipMessage());
    registerDSFID(END_BUCKET_CREATION_MESSAGE,
        () -> new EndBucketCreationMessage());
    registerDSFID(PREPARE_BACKUP_REQUEST, () -> new PrepareBackupRequest());
    registerDSFID(PREPARE_BACKUP_RESPONSE, () -> new PrepareBackupResponse());
    registerDSFID(FINISH_BACKUP_REQUEST, () -> new FinishBackupRequest());
    registerDSFID(FINISH_BACKUP_RESPONSE, () -> new FinishBackupResponse());
    registerDSFID(COMPACT_REQUEST, () -> new CompactRequest());
    registerDSFID(COMPACT_RESPONSE, () -> new CompactResponse());
    registerDSFID(FLOW_CONTROL_PERMIT_MESSAGE,
        () -> new FlowControlPermitMessage());
    registerDSFID(REQUEST_FILTERINFO_MESSAGE,
        () -> new InitialImageOperation.RequestFilterInfoMessage());
    registerDSFID(PARALLEL_QUEUE_REMOVAL_MESSAGE,
        () -> new ParallelQueueRemovalMessage());
    registerDSFID(BATCH_DESTROY_MESSAGE,
        () -> new BatchDestroyOperation.DestroyMessage());
    registerDSFID(SERIALIZED_OBJECT_PART_LIST,
        () -> new SerializedObjectPartList());
    registerDSFID(FLUSH_TO_DISK_REQUEST, () -> new FlushToDiskRequest());
    registerDSFID(FLUSH_TO_DISK_RESPONSE, () -> new FlushToDiskResponse());
    registerDSFID(ENUM_ID, () -> new EnumId());
    registerDSFID(ENUM_INFO, () -> new EnumInfo());
    registerDSFID(CHECK_TYPE_REGISTRY_STATE,
        () -> new CheckTypeRegistryState());
    registerDSFID(PREPARE_REVOKE_PERSISTENT_ID_REQUEST,
        () -> new PrepareRevokePersistentIDRequest());
    registerDSFID(PERSISTENT_RVV, () -> new DiskRegionVersionVector());
    registerDSFID(PERSISTENT_VERSION_TAG, () -> new DiskVersionTag());
    registerDSFID(DISK_STORE_ID, () -> new DiskStoreID());
    registerDSFID(CLIENT_PING_MESSAGE_IMPL, () -> new ClientPingMessageImpl());
    registerDSFID(SNAPSHOT_PACKET, () -> new SnapshotPacket());
    registerDSFID(SNAPSHOT_RECORD, () -> new SnapshotRecord());
    registerDSFID(FLOW_CONTROL_ACK, () -> new FlowControlAckMessage());
    registerDSFID(FLOW_CONTROL_ABORT, () -> new FlowControlAbortMessage());
    registerDSFID(MGMT_COMPACT_REQUEST,
        () -> new com.gemstone.gemfire.management.internal.messages.CompactRequest());
    registerDSFID(MGMT_COMPACT_RESPONSE,
        () -> new com.gemstone.gemfire.management.internal.messages.CompactResponse());
    registerDSFID(MGMT_FEDERATION_COMPONENT,
        () -> new com.gemstone.gemfire.management.internal.FederationComponent());
    registerDSFID(LOCATOR_STATUS_REQUEST, () -> new LocatorStatusRequest());
    registerDSFID(LOCATOR_STATUS_RESPONSE, () -> new LocatorStatusResponse());
    registerDSFID(R_FETCH_VERSION_MESSAGE,
        () -> new RemoteFetchVersionMessage());
    registerDSFID(R_FETCH_VERSION_REPLY,
        () -> new RemoteFetchVersionMessage.FetchVersionReplyMessage());
    registerDSFID(RELEASE_CLEAR_LOCK_MESSAGE,
        () -> new ReleaseClearLockMessage());
    registerDSFID(PR_TOMBSTONE_MESSAGE, () -> new PRTombstoneMessage());
    registerDSFID(HDFS_GATEWAY_EVENT_IMPL, () -> new HDFSGatewayEventImpl());
    registerDSFID(REQUEST_RVV_MESSAGE,
        () -> new InitialImageOperation.RequestRVVMessage());
    registerDSFID(RVV_REPLY_MESSAGE,
        () -> new InitialImageOperation.RVVReplyMessage());
    registerDSFID(SNAPPY_COMPRESSED_CACHED_DESERIALIZABLE,
        () -> new SnappyCompressedCachedDeserializable());
    registerDSFID(UPDATE_ENTRY_VERSION_MESSAGE,
        () -> new UpdateEntryVersionMessage());
    registerDSFID(PR_UPDATE_ENTRY_VERSION_MESSAGE,
        () -> new PRUpdateEntryVersionMessage());
    registerDSFID(PR_DESTROY_ON_DATA_STORE_MESSAGE,
        () -> new DestroyRegionOnDataStoreMessage());
    registerDSFID(COMMIT_PHASE1_REPLY_MESSAGE,
        () -> new CommitPhase1ReplyMessage());
    registerDSFID(TOBJECTLONGHASHMAP, () -> new TObjectLongHashMapDSFID());
    registerDSFID(SERVER_PING_MESSAGE, () -> new ServerPingMessage());
    registerDSFID(SNAPSHOT_GII_UNLOCK_MESSAGE,
        () -> new InitialImageOperation.SnapshotBucketLockReleaseMessage());
    typesRegistered = true;
  }

  /**
   * Creates a DataSerializableFixedID or StreamableFixedID instance by
   * deserializing it from the data input.
   */
  public static Object create(int dsfid, DataInput in)
      throws IOException, ClassNotFoundException {
    switch (dsfid) {
      case REGION:
        return DataSerializer.readRegion(in);
      case END_OF_STREAM_TOKEN:
        return Token.END_OF_STREAM;
      case DLOCK_REMOTE_TOKEN:
        return DLockRemoteToken.createFromDataInput(in);
      case TRANSACTION_ID:
        return TXId.createFromData(in);
      case INTEREST_RESULT_POLICY:
        return readInterestResultPolicy(in);
      case UNDEFINED:
        return readUndefined(in);
      case RESULTS_BAG:
        return readResultsBag(in);
      case GATEWAY_EVENT_IMPL_66:
        return readGatewayEventImpl66(in);
      case GFXD_TYPE:
        return readGfxdMessage(in);
      case GFXD_DVD_OBJECT:
        return readDVD(in);
      case GFXD_GLOBAL_ROWLOC:
        return readGlobalRowLocation(in);
      case GFXD_GEMFIRE_KEY:
        return readGemFireKey(in);
      case GFXD_FORMATIBLEBITSET:
        return readGfxdFormatibleBitSet(in);
      case TOKEN_INVALID:
        return Token.INVALID;
      case TOKEN_LOCAL_INVALID:
        return Token.LOCAL_INVALID;
      case TOKEN_DESTROYED:
        return Token.DESTROYED;
      case TOKEN_REMOVED:
        return Token.REMOVED_PHASE1;
      case TOKEN_REMOVED2:
        return Token.REMOVED_PHASE2;
      case TOKEN_TOMBSTONE:
        return Token.TOMBSTONE;
      case NULL_TOKEN:
        return readNullToken(in);
      case PR_DESTROY_ON_DATA_STORE_MESSAGE:
        return readDestroyOnDataStore(in);
      default:
        Supplier<?> creator;
        if (dsfid >= Byte.MIN_VALUE && dsfid <= Byte.MAX_VALUE) {
          creator = dsfidMap[dsfid + Byte.MAX_VALUE + 1];
        } else {
          creator = dsfidMap2.get(dsfid);
        }
        if (creator == null && !typesRegistered) {
          registerTypes();
          if (dsfid >= Byte.MIN_VALUE && dsfid <= Byte.MAX_VALUE) {
            creator = dsfidMap[dsfid + Byte.MAX_VALUE + 1];
          } else {
            creator = dsfidMap2.get(dsfid);
          }
        }
        if (creator != null) {
          Object ds = creator.get();
          if (ds instanceof DataSerializableFixedID) {
            DataSerializableFixedID v = (DataSerializableFixedID)ds;
            assert v.getDSFID() == dsfid :
                "MISMATCH: registered DSFID=" + dsfid + ", actual=" + v.getDSFID();
            InternalDataSerializer.invokeFromData(v, in);
          } else {
            StreamableFixedID v = (StreamableFixedID)ds;
            assert v.getDSFID() == dsfid :
                "MISMATCH: registered SFID=" + dsfid + ", actual=" + v.getDSFID();
            InternalDataSerializer.invokeFromData(v, in);
          }
          return ds;
        }
        // before throwing exception, try to set the processorId
        try {
          // base AbstractOperationMessage writes a short for flags first
          // followed by the processor ID, if any; if there is no processorId
          // then it has to be a DirectReplyMessage which will be handled
          // appropriately by caller from internal.tcp.Connection class
          final short flags = in.readShort();
          int processorId = 0;
          if ((flags & ReplyMessage.PROCESSOR_ID_FLAG) != 0) {
            processorId = in.readInt();
          }
          ReplyProcessor21.setMessageRPId(processorId);
        } catch (IOException ex) {
          // give up
        }
        throw new DSFIDNotFoundException("Unknown DataSerializableFixedID: "
            + dsfid, dsfid);
    }
  }

  //////////////////  Reading Internal Objects  /////////////////
  /**
   * Reads an instance of <code>IpAddress</code> from a
   * <code>DataInput</code>.
   *
   * @throws IOException
   *         A problem occurs while reading from <code>in</code>
   */
  public static InternalDistributedMember readInternalDistributedMember(DataInput in)
    throws IOException, ClassNotFoundException {

    InternalDistributedMember o = new InternalDistributedMember();
    InternalDataSerializer.invokeFromData(o, in);
    return o;
  }

  private static ResultsBag readResultsBag(DataInput in) throws IOException, ClassNotFoundException {
    ResultsBag o = new ResultsBag(true);
    InternalDataSerializer.invokeFromData(o, in);
    return o;
  }

  private static Undefined readUndefined(DataInput in) throws IOException, ClassNotFoundException {
    Undefined o = (Undefined)QueryService.UNDEFINED;
    InternalDataSerializer.invokeFromData(o, in);
    return o;
  }

  /**
   * Reads an instance of <code>InterestResultPolicy</code> from a
   * <code>DataInput</code>.
   *
   * @throws IOException
   *           A problem occurs while reading from <code>in</code>
   */
  private static InterestResultPolicyImpl readInterestResultPolicy(DataInput in)
      throws IOException, ClassNotFoundException {
    byte ordinal = in.readByte();
    return (InterestResultPolicyImpl)InterestResultPolicy.fromOrdinal(ordinal);
  }

  private static GatewayEventImpl readGatewayEventImpl66(DataInput in)
      throws IOException, ClassNotFoundException {
    GatewayEventImpl o = new GatewayEventImpl();
    o.fromData66(in);
    return o;
  }

  private static DataSerializableFixedID readNullToken(DataInput in)
      throws IOException, ClassNotFoundException {
    DataSerializableFixedID serializable = (NullToken)IndexManager.NULL;
    serializable.fromData(in);
    return serializable;
  }
  
  private static DataSerializableFixedID readDestroyOnDataStore(DataInput in) 
      throws IOException, ClassNotFoundException {
    DataSerializableFixedID serializable = new DestroyRegionOnDataStoreMessage();
    serializable.fromData(in);
    return serializable;
  }

  /**
   * Map for GemFireXD specific classIds to the {@link Class} of an
   * implementation. We maintain this separate map for GemFireXD to allow
   * separation of GemFire and GemFireXD trees. This is particularly required
   * when implementing a new <code>DistributionMessage</code>. This requires the
   * classes to have a zero argument constructor.
   */
  @SuppressWarnings("unchecked")
  private static Supplier<GfxdDSFID>[] gfxdDSFIDClassMap =
    new Supplier[Byte.MAX_VALUE + 1 - Byte.MIN_VALUE];

  /**
   * Extends {@link DataSerializableFixedID} with a GemFireXD specific byte ID to
   * avoid adding new DSFIDs for all GemFireXD types/messages (uses fixed
   * {@link DataSerializableFixedID#GFXD_TYPE} as the real DSFID.
   */
  public static interface GfxdDSFID extends DataSerializableFixedID {

    /** Get the GFXD specific ID for this type. */
    public byte getGfxdID();
  }

  /**
   * Exception to indicate GemFireXD specific serialization exceptions
   */
  public static class GfxdSerializationException extends
      NotSerializableException {

    private static final long serialVersionUID = 5076687296705595933L;

    /**
     * Constructs a GfxdSerializationException object with message string.
     * 
     * @param msg
     *          exception message
     */
    public GfxdSerializationException(String msg) {
      super(msg);
    }
  }

  private static GfxdDSFID readGfxdMessage(DataInput in)
      throws IOException, ClassNotFoundException {
    // Use the first byte as the typeId of GemFireXD messages
    final byte gfxdId = in.readByte();
    final int gfxdIdIndex = gfxdId & 0xFF;
    final Supplier<GfxdDSFID> creator = gfxdDSFIDClassMap[gfxdIdIndex];
    GfxdSerializationException se = null;
    if (creator != null) {
      try {
        final GfxdDSFID gfxdObj = creator.get();
        assert gfxdObj.getGfxdID() == gfxdId :
            "MISMATCH: registered GFXDID=" + gfxdId +
                ", actual=" + gfxdObj.getGfxdID();
        InternalDataSerializer.invokeFromData(gfxdObj, in);
        return gfxdObj;
      } catch (IllegalArgumentException ex) {
        se = new GfxdSerializationException(LocalizedStrings.
            DSFIDFactory_ILLEGAL_ACCESS_FOR_GEMFIREXD_MESSAGE_CLASSID_0_1
              .toLocalizedString(gfxdId, ex));
      }
    }
    else {
      // if possible set the processor ID before throwing exception so
      // that failure exception is received by the sender
      if (gfxdIdIndex < 75) {
        try {
          // base AbstractOperationMessage writes a short for flags first
          // followed by the processor ID, if any; if there is no processorId
          // then it has to be a DirectReplyMessage which will be handled
          // appropriately by caller from internal.tcp.Connection class
          final short flags = in.readShort();
          int processorId = 0;
          if ((flags & ReplyMessage.PROCESSOR_ID_FLAG) != 0) {
            processorId = in.readInt();
          }
          ReplyProcessor21.setMessageRPId(processorId);
        } catch (IOException ex) {
          // give up
        }
      }
    }
    // check for VM going down before throwing serialization exception (#47367)
    if (se == null) {
      se = new GfxdSerializationException(
          LocalizedStrings.DSFIDFactory_UNKNOWN_CLASSID_0_FOR_GEMFIREXD_MESSAGE
              .toLocalizedString(gfxdId));
    }
    // bug #47368 - perform a cancellation check if the cache is there
    // and is closed
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null) {
      cache.getCancelCriterion().checkCancelInProgress(se);
    }
    throw se;
  }

  public static synchronized void registerGemFireXDClass(byte classId,
      Supplier<GfxdDSFID> creator) {
    final int gfxdIdIndex = classId & 0xFF;
    Supplier<?> oldCreator = gfxdDSFIDClassMap[gfxdIdIndex];
    if (oldCreator != null) {
      throw new AssertionError("DSFIDFactory#registerGemFireXDClass: cannot "
          + "re-register classId " + classId + " using " + creator
          + "; existing constructor: " + oldCreator);
    }
    gfxdDSFIDClassMap[gfxdIdIndex] = creator;
  }

  public static synchronized void clearGemFireXDClasses() {
    for (int index = 0; index < gfxdDSFIDClassMap.length; ++index) {
      gfxdDSFIDClassMap[index] = null;
    }
  }

  public interface DeserializeDVD {

    public DataSerializableFixedID getDSFID(DataInput in) throws IOException,
        ClassNotFoundException;

    public DataSerializableFixedID getGlobalRowLocation(DataInput in)
        throws IOException, ClassNotFoundException;

    public DataSerializableFixedID getGemFireKey(DataInput in)
        throws IOException, ClassNotFoundException;

    public DataSerializableFixedID getGfxdFormatibleBitSet(DataInput in)
        throws IOException, ClassNotFoundException;
  }

  private static DeserializeDVD dvdDeserializer;

  private static DataSerializableFixedID readDVD(DataInput in)
      throws IOException, ClassNotFoundException {
    return dvdDeserializer.getDSFID(in);
  }

  private static DataSerializableFixedID readGlobalRowLocation(DataInput in)
      throws IOException, ClassNotFoundException {
    return dvdDeserializer.getGlobalRowLocation(in);
  }

  private static DataSerializableFixedID readGemFireKey(DataInput in)
      throws IOException, ClassNotFoundException {
    return dvdDeserializer.getGemFireKey(in);
  }

  private static DataSerializableFixedID readGfxdFormatibleBitSet(DataInput in)
      throws IOException, ClassNotFoundException {
    return dvdDeserializer.getGfxdFormatibleBitSet(in);
  }

  public static void registerDVDDeserializer(DeserializeDVD d) {
    dvdDeserializer = d;
  }

  static Supplier<?>[] getDsfidmap() {
    return dsfidMap;
  }

  static IntObjectHashMap<Supplier<?>> getDsfidmap2() {
    return dsfidMap2;
  }
}
