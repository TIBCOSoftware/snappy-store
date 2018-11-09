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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

import com.gemstone.gemfire.CopyHelper;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.DeltaSerializationException;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.SerializationException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.EntryOperation;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.SerializedCacheValue;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexProtocol;
import com.gemstone.gemfire.cache.query.internal.index.IndexUtils;
import com.gemstone.gemfire.cache.util.TimestampedEntryEvent;
import com.gemstone.gemfire.cache.wan.GatewayQueueEvent;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DSFIDFactory;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.Sendable;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PutMessage;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerHelper;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.Releasable;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_NEW_VALUE;
import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_OLD_VALUE;

import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.UMMMemoryTracker;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.gemstone.gemfire.pdx.internal.PeerTypeRegistration;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Implementation of an entry event
 */
// must be public for DataSerializableFixedID
public class EntryEventImpl extends KeyInfo implements
    EntryEvent<Object, Object>, GatewayQueueEvent<Object, Object>,
    InternalCacheEvent, DataSerializableFixedID,
    EntryOperation<Object, Object>, InternalDeltaEvent, Releasable {

  // PACKAGE FIELDS //
  public transient LocalRegion region;
  private transient RegionEntry re;

  //private long eventId;
  /** the event's id. Scoped by distributedMember. */
  protected EventID eventID;

  @Retained(ENTRY_EVENT_OLD_VALUE)
  private Object oldValue = null;
  protected Delta delta = null;
 
  protected short eventFlags = 0x0000;
  protected short extraEventFlags = 0x0000;

  /** the transaction state for the current operation */
  transient TXStateInterface txState = TXStateProxy.TX_NOT_SET;

  protected Operation op;

  /* To store the operation/modification type */
  private transient EnumListenerEvent eventType;

  /**
   * This field will be null unless this event is used for a putAll operation.
   *
   * @since 5.0
   */
  private transient DistributedPutAllOperation putAllOp;

  /**
   * The member that originated this event
   *
   * @since 5.0
   */
  protected DistributedMember distributedMember;

  
  /**
   * transient storage for the message that caused the event
   */
  transient DistributionMessage causedByMessage;
  
  
  //private static long eventID = 0;

  /**
   * The originating membershipId of this event.
   *
   * @since 5.1
   */
  protected ClientProxyMembershipID context = null;
  
  /**
   * A custom context object that can be used for any other contextual
   * information. Currently used by GemFireXD to pass around evaluated rows
   * from raw byte arrays and routing object.
   */
  private transient Object contextObj = null;

  private transient UMMMemoryTracker memoryTracker;

  /**
   * The current operation's lastModified stamp, if any.
   */
  private transient long entryLastModified = -1;

  /** Set to true if current operation has an existing RegionEntry. */
  private transient boolean hasOldRegionEntry;

  /**
   * this holds the bytes representing the change in value effected by this
   * event.  It is used when the value implements the Delta interface.
   */
  private byte[] deltaBytes = null;

  
  /** routing information for cache clients for this event */
  private FilterInfo filterInfo;
  
  /**new value stored in serialized form*/
  protected byte[] newValueBytes;
  /**old value stored in serialized form*/
  private byte[] oldValueBytes;

  /** the locking policy for the current operation */
  private LockingPolicy lockPolicy = LockingPolicy.NONE;

  /** version tag for concurrency checks */
  protected VersionTag versionTag;

  /** boolean to indicate that this operation should be optimized by not fetching from HDFS*/
  private transient boolean fetchFromHDFS = true;
  
  private transient boolean isPutDML = false;

  /** boolean to indicate that the RegionEntry for this event was loaded from HDFS*/
  private transient boolean loadedFromHDFS= false;
  
  private transient boolean isCustomEviction = false;
  
  /** boolean to indicate that the RegionEntry for this event has been evicted*/
  private transient boolean isEvicted = false;
  
  private transient boolean isPendingSecondaryExpireDestroy = false;
  
  public final static Object SUSPECT_TOKEN = new Object();
  /** for deserialization */
  public EntryEventImpl() {
  }

  /**
   * create a new entry event that will be used for conveying version information
   * and anything else of use while processing another event
   * @return the empty event object
   */
  @Retained
  public static EntryEventImpl createVersionTagHolder() {
    return new EntryEventImpl();
  }
  
  /**
   * create a new entry event that will be used for conveying version information
   * and anything else of use while processing another event
   * @return the empty event object
   */
  @Retained
  public static EntryEventImpl createVersionTagHolder(VersionTag tag) {
    EntryEventImpl result = new EntryEventImpl();
    result.setVersionTag(tag);
    return result;
  }

  /**
   * Reads the contents of this message from the given input.
   */
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.eventID = (EventID)DataSerializer.readObject(in);
    this.key = DataSerializer.readObject(in);
    this.op = Operation.fromOrdinal(in.readByte());
    short flags = in.readShort();
    this.eventFlags = (short)(flags & EventFlags.FLAG_TRANSIENT_MASK);
    this.extraEventFlags = (short)(flags & 0xFF);
    this.callbackArg = DataSerializer.readObject(in);

    final boolean prefObject = CachedDeserializableFactory.preferObject();
    Version version = null;
    ByteArrayDataInput bytesIn = null;
    if (prefObject) {
      version = InternalDataSerializer.getVersionForDataStreamOrNull(in);
      bytesIn = new ByteArrayDataInput();
    }
    if (in.readBoolean()) {     // isDelta
      this.delta = (Delta)DataSerializer.readObject(in);
    }
    else {
      // OFFHEAP Currently values are never deserialized to off heap memory. If that changes then this code needs to change.
      if (in.readBoolean()) {     // newValueSerialized
        this.newValueBytes = DataSerializer.readByteArray(in);
        if (prefObject) {
          this.newValue = deserialize(this.newValueBytes, version, bytesIn);
        }
        else {
          this.newValue = CachedDeserializableFactory.create(this.newValueBytes);
        }
      }
      else {
        this.newValue = DataSerializer.readObject(in);
      }
    }

    // OFFHEAP Currently values are never deserialized to off heap memory. If that changes then this code needs to change.
    if (in.readBoolean()) {     // oldValueSerialized
      this.oldValueBytes = DataSerializer.readByteArray(in);
      if (prefObject) {
        this.oldValue = deserialize(this.oldValueBytes, version, bytesIn);
      }
      else {
        this.oldValue = CachedDeserializableFactory.create(this.oldValueBytes);
      }
    }
    else {
      this.oldValue = DataSerializer.readObject(in);
    }
    this.distributedMember = DSFIDFactory.readInternalDistributedMember(in);
    this.context = ClientProxyMembershipID.readCanonicalized(in);
    this.tailKey = in.readLong();
  }

  @Retained
  protected EntryEventImpl(LocalRegion region, Operation op, Object key,
      boolean originRemote, DistributedMember distributedMember,
      boolean generateCallbacks, boolean fromRILocalDestroy) {
    super(key, null, null);
    this.region = region;
    this.op = op;
    setOriginRemote(originRemote);
    setGenerateCallbacks(generateCallbacks);
    this.distributedMember = distributedMember;
    setFromRILocalDestroy(fromRILocalDestroy);
  }

  /**
   * Doesn't specify oldValue as this will be filled in later as part of an
   * operation on the region, or lets it default to null.
   */
  @Retained
  protected EntryEventImpl(
      final LocalRegion region,
      Operation op, Object key, @Retained(ENTRY_EVENT_NEW_VALUE) Object newVal,
      Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember,
      boolean generateCallbacks, boolean initializeId) {
    super(key, null, callbackArgument);
    this.region = region;
    this.op = op;

    setNewValueForTX(newVal);
    setOriginRemote(originRemote);
    setGenerateCallbacks(generateCallbacks);
    this.distributedMember = distributedMember;
  }

  /**
   * Called by BridgeEntryEventImpl to use existing EventID
   */
  @Retained
  protected EntryEventImpl(LocalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument, boolean originRemote,
      DistributedMember distributedMember, boolean generateCallbacks,
      EventID eventID) {
    this(region, op, key, newValue,
        callbackArgument, originRemote, distributedMember, generateCallbacks,
        true /* initializeId */);
    Assert.assertTrue(eventID != null || !(region instanceof PartitionedRegion));
    this.setEventId(eventID);
  }

  /**
   * create an entry event from another entry event
   */
  @Retained
  public EntryEventImpl(@Retained({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE}) EntryEventImpl other) {
    this(other, true);
  }
  
  
  @Retained
  public EntryEventImpl(@Retained({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE}) EntryEventImpl other, boolean setOldValue) {
    super(other.key, null, other.bucketId);
    this.region = other.region;
    this.eventID = other.eventID;
    basicSetNewValue(other.basicGetNewValue());
    this.newValueBytes = other.newValueBytes;
    this.re = other.re;
    this.delta = other.delta;
    if (setOldValue) {
      retainAndSetOldValue(other.basicGetOldValue());
      this.oldValueBytes = other.oldValueBytes;
    }
    this.eventFlags = other.eventFlags;
    setEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED, false);
    this.op = other.op;
    this.distributedMember = other.distributedMember;
    this.filterInfo = other.filterInfo;
    final Object otherCallbackArg = other.getRawCallbackArgument();
    if (otherCallbackArg instanceof GatewayEventCallbackArgument) {
      super.setCallbackArgument((new GatewayEventCallbackArgument(
          (GatewayEventCallbackArgument)otherCallbackArg)));
    }
    else if (otherCallbackArg instanceof GatewaySenderEventCallbackArgument) {
      super.setCallbackArgument(((GatewaySenderEventCallbackArgument)
          otherCallbackArg).getClone());
    }
    else {
      super.setCallbackArgument(otherCallbackArg);
    }
    this.context = other.context;
    this.deltaBytes = other.deltaBytes;
    this.txState = other.txState;
    this.tailKey = other.tailKey;
    this.versionTag = other.versionTag;
    this.entryLastModified = other.entryLastModified;
    //set possible duplicate
    this.setPossibleDuplicate(other.isPossibleDuplicate());
  }

  @Retained
  public EntryEventImpl(Object key2) {
    super(key2, null, null);
  }
  
  /**
   * This constructor is used to create a bridge event in server-side
   * command classes.  Events created with this are not intended to be
   * used in cache operations.
   * @param id the identity of the client's event
   */
  @Retained
  public EntryEventImpl(EventID id) {
    this.eventID = id;
    this.offHeapOk = false;
  }

  /**
   * Creates and returns an EntryEventImpl.  Generates and assigns a bucket id to the
   * EntryEventImpl if the region parameter is a PartitionedRegion.
   */  
  @Retained
  public static EntryEventImpl create(LocalRegion region,
      Operation op,
      Object key, @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember) {
    return create(region,op,key,newValue,callbackArgument,originRemote,distributedMember,true,true);
  }
  
  /**
   * Creates and returns an EntryEventImpl.  Generates and assigns a bucket id to the
   * EntryEventImpl if the region parameter is a PartitionedRegion.
   */
  @Retained
  public static EntryEventImpl create(LocalRegion region,
      Operation op,
      Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue,
      Object callbackArgument,
      boolean originRemote,
      DistributedMember distributedMember,
      boolean generateCallbacks) {
    return create(region, op, key, newValue, callbackArgument, originRemote,
        distributedMember, generateCallbacks,true);
  }
  
  /**
   * Creates and returns an EntryEventImpl.  Generates and assigns a bucket id to the
   * EntryEventImpl if the region parameter is a PartitionedRegion.
   *  
   * Called by BridgeEntryEventImpl to use existing EventID
   * 
   * {@link EntryEventImpl#EntryEventImpl(LocalRegion, Operation, Object, Object, Object, boolean, DistributedMember, boolean, EventID)}
   */ 
  @Retained
  public static EntryEventImpl create(LocalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument, boolean originRemote,
      DistributedMember distributedMember, boolean generateCallbacks,
      EventID eventID) {
    EntryEventImpl entryEvent = new EntryEventImpl(region,op,key,newValue,callbackArgument,originRemote,distributedMember,generateCallbacks,eventID);

    if (null != region) {
      region.updateKeyInfo(entryEvent, key, null);
    }    
    
    return entryEvent;
  }
  
  /**
   * Creates and returns an EntryEventImpl.  Generates and assigns a bucket id to the
   * EntryEventImpl if the region parameter is a PartitionedRegion.
   * 
   * {@link EntryEventImpl#EntryEventImpl(LocalRegion, Operation, Object, boolean, DistributedMember, boolean, boolean)}
   */
  @Retained
  public static EntryEventImpl create(LocalRegion region, Operation op, Object key,
      boolean originRemote, DistributedMember distributedMember,
      boolean generateCallbacks, boolean fromRILocalDestroy) {
    EntryEventImpl entryEvent = new EntryEventImpl(region,op,key,originRemote,distributedMember,generateCallbacks,fromRILocalDestroy);
    
    if (null != region) {
      region.updateKeyInfo(entryEvent, key, null);
    }    
    
    return entryEvent;
  }  
  
  /**
   * Creates and returns an EntryEventImpl.  Generates and assigns a bucket id to the
   * EntryEventImpl if the region parameter is a PartitionedRegion.
   * 
   * This creator does not specify the oldValue as this will be filled in later as part of an
   * operation on the region, or lets it default to null.
   * 
   * {@link EntryEventImpl#EntryEventImpl(LocalRegion, Operation, Object, Object, Object, boolean, DistributedMember, boolean, boolean)}
   */
  @Retained
  public static EntryEventImpl create(final LocalRegion region,
      Operation op, Object key, @Retained(ENTRY_EVENT_NEW_VALUE) Object newVal,
      Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember,
      boolean generateCallbacks, boolean initializeId)  {
    EntryEventImpl entryEvent = new EntryEventImpl(region,op,key,newVal,callbackArgument,
        originRemote,distributedMember,generateCallbacks,initializeId);

    if(null != region) {
      region.updateKeyInfo(entryEvent, key, newVal);
    }
    
    return entryEvent;
  }
  
  /**
   * Creates a PutAllEvent given the distributed operation, the region, and the
   * entry data.
   *
   * @since 5.0
   */
  @Retained
  static EntryEventImpl createPutAllEvent(
      DistributedPutAllOperation putAllOp, LocalRegion region,
      Operation entryOp, Object entryKey, @Retained(ENTRY_EVENT_NEW_VALUE) Object entryNewValue, Object callbackArg)
  {
    EntryEventImpl e;
    if (putAllOp != null) {
      EntryEventImpl event = putAllOp.getEvent();
      if (event.isBridgeEvent()) {
        e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue,
            event.getRawCallbackArgument(), false, event.distributedMember,
            event.isGenerateCallbacks());
        e.setContext(event.getContext());
      }
      else {
        e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue,
            callbackArg, false, region.getMyId(), event.isGenerateCallbacks());
      }

    }
    else {
      e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue,
          callbackArg, false, region.getMyId(), true);
    }
    e.putAllOp = putAllOp;
    return e;
  }

  /** return the putAll operation for this event, if any */
  public final DistributedPutAllOperation getPutAllOperation() {
    return this.putAllOp;
  }

  public final DistributedPutAllOperation setPutAllOperation(
      DistributedPutAllOperation nv) {
    DistributedPutAllOperation result = this.putAllOp;
    this.putAllOp = nv;
    return result;
  }

  private final boolean testEventFlag(short mask) {
    return (this.eventFlags & mask) != 0;
  }

  private final void setEventFlag(short mask, boolean on) {
    this.eventFlags = (short)(on ? (this.eventFlags | mask)
        : (this.eventFlags & ~mask));
  }

  private final boolean testExtraEventFlag(short mask) {
    return (this.extraEventFlags & mask) != 0;
  }

  private final void setExtraEventFlag(short mask, boolean on) {
    this.extraEventFlags = (short)(on ? (this.extraEventFlags | mask)
        : (this.extraEventFlags & ~mask));
  }

  public final DistributedMember getDistributedMember() {
    return this.distributedMember;
  }

  /////////////////////// INTERNAL BOOLEAN SETTERS

  public final void setOriginRemote(boolean b) {
    if (b) {
      this.eventFlags |= EventFlags.FLAG_ORIGIN_REMOTE;
    }
    else {
      this.eventFlags &= ~EventFlags.FLAG_ORIGIN_REMOTE;
    }
  }

  public final void setLocalInvalid(boolean b) {
    setEventFlag(EventFlags.FLAG_LOCAL_INVALID, b);
  }

  final void setGenerateCallbacks(boolean b) {
    if (b) {
      this.eventFlags |= EventFlags.FLAG_GENERATE_CALLBACKS;
    }
    else {
      this.eventFlags &= ~EventFlags.FLAG_GENERATE_CALLBACKS;
    }
  }

  /** set the the flag telling whether callbacks should be invoked for a partitioned region */
  public final void setInvokePRCallbacks(boolean b) {
    setEventFlag(EventFlags.FLAG_INVOKE_PR_CALLBACKS, b);
  }

  /** get the flag telling whether callbacks should be invoked for a partitioned region */
  public final boolean getInvokePRCallbacks() {
    return testEventFlag(EventFlags.FLAG_INVOKE_PR_CALLBACKS);
  }

  public final boolean getInhibitDistribution() {
    return testEventFlag(EventFlags.FLAG_INHIBIT_DISTRIBUTION);
  }

  public final void setInhibitDistribution(boolean b) {
    setEventFlag(EventFlags.FLAG_INHIBIT_DISTRIBUTION, b);
  }

  /** was the entry destroyed or missing and allowed to be destroyed again? */
  public final boolean getIsRedestroyedEntry() {
    return testEventFlag(EventFlags.FLAG_REDESTROYED_TOMBSTONE);
  }
  
  public final void setIsRedestroyedEntry(boolean b) {
    setEventFlag(EventFlags.FLAG_REDESTROYED_TOMBSTONE, b);
  }
  
  public final void isConcurrencyConflict(boolean b) {
    setEventFlag(EventFlags.FLAG_CONCURRENCY_CONFLICT, b);
  }

  public final boolean isConcurrencyConflict() {
    return testEventFlag(EventFlags.FLAG_CONCURRENCY_CONFLICT);
  }

  /** set the DistributionMessage that caused this event */
  public final void setCausedByMessage(DistributionMessage msg) {
    this.causedByMessage = msg;
  }

  /**
   * get the PartitionMessage that caused this event, or null if
   * the event was not caused by a PartitionMessage
   */
  public final PartitionMessage getPartitionMessage() {
    if (this.causedByMessage != null
        && this.causedByMessage instanceof PartitionMessage) {
      return (PartitionMessage)this.causedByMessage;
    }
    return null;
  }

  /**
   * get the RemoteOperationMessage that caused this event, or null if the event
   * was not caused by a RemoteOperationMessage
   */
  public final RemoteOperationMessage getRemoteOperationMessage() {
    if (this.causedByMessage != null
        && this.causedByMessage instanceof RemoteOperationMessage) {
      return (RemoteOperationMessage)this.causedByMessage;
    }
    return null;
  }

  /////////////// BOOLEAN GETTERS

  public final boolean isLocalLoad() {
    return this.op.isLocalLoad();
  }

  public final boolean isNetSearch() {
    return this.op.isNetSearch();
  }

  public final boolean isNetLoad() {
    return this.op.isNetLoad();
  }

  public final boolean isDistributed() {
    return this.op.isDistributed();
  }

  public final boolean isExpiration() {
    return this.op.isExpiration();
  }

  public final boolean isEviction() {
    return this.op.isEviction();
  }

  public final boolean isCustomEviction() {
    return this.isCustomEviction;
  }
  
  public final void setCustomEviction(boolean customEvict) {
    this.isCustomEviction = customEvict;
  }
  
  public final void setEvicted() {
    this.isEvicted = true;
  }
  
  public final boolean isEvicted() {
    return this.isEvicted;
  }
  
  public final boolean isPendingSecondaryExpireDestroy() {
    return this.isPendingSecondaryExpireDestroy;
  }
  
  public final void setPendingSecondaryExpireDestroy (boolean value) {
    this.isPendingSecondaryExpireDestroy = value;
  }
  // Note that isOriginRemote is sometimes set to false even though the event
  // was received from a peer.  This is done to force distribution of the
  // message to peers and to cause concurrency version stamping to be performed.
  // This is done by all one-hop operations, like RemoteInvalidateMessage.
  public final boolean isOriginRemote() {
    return (this.eventFlags & EventFlags.FLAG_ORIGIN_REMOTE) != 0;
  }

  /* return whether this event originated from a WAN gateway and carries a WAN version tag */
  public final boolean isFromWANAndVersioned() {
    return (this.versionTag != null && this.versionTag.isGatewayTag());
  }

  /* return whether this event originated in a client or possible duplicate retry 
   * and carries a version tag */
  public final boolean isFromBridgeOrPossDupAndVersioned() {
    return (this.context != null || (this.isPossibleDuplicate() && this.eventID != null))
        && (this.versionTag != null);
  }

  public final boolean isGenerateCallbacks() {
    return (this.eventFlags & EventFlags.FLAG_GENERATE_CALLBACKS) != 0;
  }

  public final void setNewEventId(DistributedSystem sys) {
    Assert.assertTrue(this.eventID == null, "Double setting event id");
    EventID newID = new EventID(sys);
    if (this.eventID != null && BridgeServerImpl.VERBOSE) {
      sys.getLogWriter().convertToLogWriterI18n().info(LocalizedStrings.DEBUG, "Replacing event ID with " + newID + " in event " + this);
    }
    this.eventID = newID;
  }

  public final void reserveNewEventId(DistributedSystem sys, int count) {
    Assert.assertTrue(this.eventID == null, "Double setting event id");
    this.eventID = new EventID(sys);
    if (count > 1) {
      this.eventID.reserveSequenceId(count - 1);
    }
  }

  public final void setEventId(EventID id) {
    this.eventID = id;
  }

  /**
   * Return the event id, if any
   * 
   * @return null if no event id has been set
   */
  public final EventID getEventId() {
    return this.eventID;
  }

  public final boolean isBridgeEvent() {
    return hasClientOrigin();
  }

  public final boolean hasClientOrigin() {
    return getContext() != null;
  }

  /**
   * sets the ID of the client that initiated this event
   */
  public final void setContext(ClientProxyMembershipID contx) {
    Assert.assertTrue(contx != null);
    this.context = contx;
  }

  /**
   * sets the ID of the client that initiated this event
   */
  public final void clearContext() {
    this.context = null;
  }

  /**
   * gets the ID of the client that initiated this event.  Null if a server-initiated event
   */
  public final ClientProxyMembershipID getContext() {
    return this.context;
  }

  // INTERNAL
  final boolean isLocalInvalid() {
    return testEventFlag(EventFlags.FLAG_LOCAL_INVALID);
  }

  /////////////////////////////////////////////////

  /**
   * Returns the value in the cache prior to this event. When passed to an event
   * handler after an event occurs, this value reflects the value that was in
   * the cache in this VM, not necessarily the value that was in the cache VM
   * that initiated the operation.
   *
   * @return the value in the cache prior to this event.
   */
  public final Object getOldValue() {
    try {
      if (isOriginRemote() && this.region.isProxy()) {
        return null;
      }
      @Unretained Object ov = basicGetOldValue();
      if (ov == null) {
        return null;
      } else if (ov == Token.NOT_AVAILABLE) {
        return AbstractRegion.handleNotAvailable(ov);
      }
      boolean doCopyOnRead = getRegion().isCopyOnRead();
      if (ov != null) {
        final Class<?> ovclass = ov.getClass();
        if (ovclass == byte[].class || ovclass == byte[][].class) {
          if (doCopyOnRead) {
            return CopyHelper.copy(ov);
          } else {
            return ov;
          }
        }
        else if (StoredObject.class.isAssignableFrom(ovclass)) {
          return ((StoredObject) ov).getValueAsDeserializedHeapObject();
        }
        else if (CachedDeserializable.class.isAssignableFrom(ovclass)) {
          CachedDeserializable cd = (CachedDeserializable)ov;
          if (doCopyOnRead) {
            return cd.getDeserializedWritableCopy(this.region, this.re);
          } else {
            return cd.getDeserializedValue(this.region, this.re);
          }
        }
        else {
          if (doCopyOnRead) {
            return CopyHelper.copy(ov);
          } else {
            return ov;
          }
        }
      }
      return null;
    } catch(IllegalArgumentException i) {
      IllegalArgumentException iae = new IllegalArgumentException(LocalizedStrings.DONT_RELEASE.toLocalizedString("Error while deserializing value for key="+getKey()));
      iae.initCause(i);
      throw iae;
    }
  }

  /**
   * Like getRawNewValue except that if the result is an off-heap reference then copy it to the heap.
   * ALERT: If there is a Delta, returns that, not the (applied) new value.
   * TODO OFFHEAP: to prevent the heap copy use getRawNewValue instead
   */
  public final Object getRawNewValueAsHeapObject() {
    if (this.delta != null) {
      return this.delta;
    }
    return OffHeapHelper.getHeapForm(OffHeapHelper.copyIfNeeded(basicGetNewValue()));
  }
  
  /**
   * If new value is a Delta return it.
   * Else if new value is off-heap return the StoredObject form (unretained OFF_HEAP_REFERENCE). 
   * Its refcount is not inced by this call and the returned object can only be safely used for the lifetime of the EntryEventImpl instance that returned the value.
   * Else return the raw form.
   */
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public final Object getRawNewValue() {
    if (this.delta != null) return this.delta;
    return basicGetNewValue();
  }

  @Override
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public final Object getValue() {
    return basicGetNewValue();
  }
  
  /**
   * Returns the delta that represents the new value; null if no delta.
   * @return the delta that represents the new value; null if no delta.
   */
  public final Delta getDeltaNewValue() {
    return this.delta;
  }
  
  /**
   *  Applies the delta 
   */
  private Object applyDeltaWithCopyOnRead(boolean doCopyOnRead) {
    //try {
      if (applyDelta(true)) {
        Object applied = basicGetNewValue();
        // if applyDelta returns true then newValue should not be off-heap
        assert !(applied instanceof StoredObject);
        if (applied == this.oldValue && doCopyOnRead) {
          applied = CopyHelper.copy(applied);
        }
        return applied;
      }
    //} catch (EntryNotFoundException ex) {
      // only (broken) product code has the opportunity to call this before
      // this.oldValue is set. If oldValue is not set yet, then
      // we most likely haven't synchronized on the region entry yet.
      // (If we have, then make sure oldValue is set before
      // calling this method).
      //throw new AssertionError("too early to call getNewValue");
    //}
    return null;
  }

  @Released(ENTRY_EVENT_NEW_VALUE)
  private void basicSetNewValue(@Retained(ENTRY_EVENT_NEW_VALUE) Object v) {
    if (v == this.newValue) return;
    if (this.offHeapOk) {
      OffHeapHelper.releaseAndTrackOwner(this.newValue, this);
    }
    if (v instanceof Chunk) {
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(this);
      if (!((Chunk) v).retain()) {
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
        this.newValue = null;
        return;
      }
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
    }
    this.newValue = v;
  }
  /**
   * Returns true if this event has a reference to an off-heap new or old value.
   */
  public final boolean hasOffHeapValue() {
    return (this.newValue instanceof Chunk) || (this.oldValue instanceof Chunk);
  }
  
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  protected final Object basicGetNewValue() {
    Object result = this.newValue;
    if (!this.offHeapOk && result instanceof Chunk) {
      //this.region.getCache().getLogger().info("DEBUG new value already freed " + System.identityHashCode(result));
      throw new IllegalStateException("Attempt to access off heap value after the EntryEvent was released.");
    }
    return result;
  }
  
  private class OldValueOwner {
    private EntryEventImpl getEvent() {
      return EntryEventImpl.this;
    }
    @Override
    public int hashCode() {
      return getEvent().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof OldValueOwner) {
        return getEvent().equals(((OldValueOwner) obj).getEvent());
      } else {
        return false;
      }
    }
    @Override
    public String toString() {
      return "OldValueOwner " + getEvent().toString();
    }
  }

  /**
   * Note if v might be an off-heap reference that you did not retain for this EntryEventImpl
   * then call retainsAndSetOldValue instead of this method.
   * @param v the caller should have already retained this off-heap reference.
   */
  @Released(ENTRY_EVENT_OLD_VALUE)
  private void basicSetOldValue(@Unretained(ENTRY_EVENT_OLD_VALUE) Object v) {
    @Released final Object curOldValue = this.oldValue;
    if (v == curOldValue) return;
    if (this.offHeapOk) {
      if (curOldValue instanceof Chunk) {
        if (SimpleMemoryAllocatorImpl.trackReferenceCounts()) {
          OffHeapHelper.releaseAndTrackOwner(curOldValue, new OldValueOwner());
        } else {
          OffHeapHelper.release(curOldValue);
        }
      }
    }
    
    this.oldValue = v;
  }

  @Released(ENTRY_EVENT_OLD_VALUE)
  private void retainAndSetOldValue(@Retained(ENTRY_EVENT_OLD_VALUE) Object v) {
    if (v == this.oldValue) return;
    
    if (v instanceof Chunk) {
      if (SimpleMemoryAllocatorImpl.trackReferenceCounts()) {
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(new OldValueOwner());
        boolean couldNotRetain = (!((Chunk) v).retain());
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
        if (couldNotRetain) {
          this.oldValue = null;
          return;
        }
      } else {
        if (!((Chunk) v).retain()) {
          this.oldValue = null;
          return;
        }
      }
    }
    basicSetOldValue(v);
  }

  @Unretained(ENTRY_EVENT_OLD_VALUE)
  private Object basicGetOldValue() {
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    Object result = this.oldValue;
    if (!this.offHeapOk && result instanceof Chunk) {
      //this.region.getCache().getLogger().info("DEBUG old value already freed " + System.identityHashCode(result));
      throw new IllegalStateException("Attempt to access off heap value after the EntryEvent was released.");
    }
    return result;
  }

  /**
   * Like getRawOldValue except that if the result is an off-heap reference then copy it to the heap.
   * To avoid the heap copy use getRawOldValue instead.
   */
  public final Object getRawOldValueAsHeapObject() {
    return OffHeapHelper.getHeapForm(OffHeapHelper.copyIfNeeded(basicGetOldValue()));
  }
  /*
   * If the old value is off-heap return the StoredObject form (unretained OFF_HEAP_REFERENCE). 
   * Its refcount is not inced by this call and the returned object can only be safely used for the lifetime of the EntryEventImpl instance that returned the value.
   * Else return the raw form.
   */
  @Unretained(ENTRY_EVENT_OLD_VALUE)
  public final Object getRawOldValue() {
    return basicGetOldValue();
  }
  /**
   * Just like getRawOldValue except if the raw old value is off-heap deserialize it.
   * Note that in some cases gfxd ignores the request to deserialize.
   */
  @Unretained(ENTRY_EVENT_OLD_VALUE)
  public final Object getOldValueAsOffHeapDeserializedOrRaw() {
    Object result = basicGetOldValue();
    if (result instanceof StoredObject) {
      result = ((StoredObject) result).getDeserializedForReading();
    }
    return AbstractRegion.handleNotAvailable(result); // fixes 49499
  }

  /**
   * Added this function to expose isCopyOnRead function to the
   * child classes of EntryEventImpl  
   * 
   */
  protected final boolean isRegionCopyOnRead() {
    return getRegion().isCopyOnRead();
  }
 
  /**
   * Returns the value in the cache after this event.
   *
   * @return the value in the cache after this event.
   */
  public final Object getNewValue() {
    
    boolean doCopyOnRead = getRegion().isCopyOnRead();
    Object newValueWithDelta = applyDeltaWithCopyOnRead(doCopyOnRead);
    if (newValueWithDelta != null)
      return newValueWithDelta;
    @Unretained(ENTRY_EVENT_NEW_VALUE)
    Object nv = basicGetNewValue();
    if (nv != null) {
      if (nv == Token.NOT_AVAILABLE) {
        // I'm not sure this can even happen
        return AbstractRegion.handleNotAvailable(nv);
      }
      if (nv instanceof StoredObject) {
        return ((StoredObject) nv).getValueAsDeserializedHeapObject();
      } else
      if (nv instanceof CachedDeserializable) {
        CachedDeserializable cd = (CachedDeserializable)nv;
        Object v = null;
        // TODO OFFHEAP currently we copy offheap new value to the heap here. Check callers of this method to see if they can be optimized to use offheap values.
        if (doCopyOnRead) {
          v = cd.getDeserializedWritableCopy(this.region, this.re);
        } else {
          v = cd.getDeserializedValue(this.region, this.re);
        }
        assert !(v instanceof CachedDeserializable && !(v instanceof ByteSource)) : "for key "+this.getKey()+" found nested CachedDeserializable";
        return v;
      }
      else {
        if (doCopyOnRead) {
          return CopyHelper.copy(nv);
        } else {
          return nv;
        }
      }
    }
    return null;
  }

  protected final boolean applyDelta(boolean throwOnNullOldValue)
      throws EntryNotFoundException {
    if (this.newValue != null || this.delta == null) {
      return false;
    }
    if (this.oldValue == null && !this.delta.allowCreate()) {
      if (throwOnNullOldValue) {
        // !!!:ezoerner:20080611 It would be nice if the client got this
        // exception
        throw new EntryNotFoundException(
            "Cannot apply a delta without an existing value");
      }
      return false;
    }
    // swizzle BucketRegion in event for Delta.
    // !!!:ezoerner:20090602 this is way ugly; this whole class severely
    // needs refactoring
    LocalRegion originalRegion = this.region;
    try {
      if (originalRegion instanceof BucketRegion) {
        this.region = originalRegion.getPartitionedRegion();
      }
      basicSetNewValue(this.delta.apply(this));
      // clear delta after apply so full value gets sent to others
      // including GII sources which may not have the old value
      // (currently only cleared for SerializedDiskBuffers)
      if (this.delta instanceof SerializedDiskBuffer) {
        this.delta = null;
      }
    } finally {
      this.region = originalRegion;
    }
    return true;
  }

  /** Set a deserialized value */
  public final void setNewValue(@Retained(ENTRY_EVENT_NEW_VALUE) Object obj) {
    if (obj instanceof Delta) {
      setNewDelta((Delta)obj);
    }
    else {
      basicSetNewValue(obj);
      this.delta = null;
    }
  }

  public final void setNewDelta(Delta delta) {
    this.delta = delta;
    if (this.newValue != null) {
      basicSetNewValue(null);
    }
  }

  public final void setNewValueForTX(@Retained(ENTRY_EVENT_NEW_VALUE) Object newVal) {
    if (newVal instanceof Delta) {
      setNewDelta((Delta)newVal);
    }
    else if (newVal != Token.INVALID) {
      if (newVal != Token.LOCAL_INVALID) {
        basicSetNewValue(newVal);
      }
      else {
        /**
         * this might set txId for events done from a thread that has a tx even
         * though the op is non-tx. For example region ops.
         */
        setLocalInvalid(true);
      }
      this.delta = null;
    }
    else {
      if (this.newValue != null) {
        basicSetNewValue(null);
      }
      this.delta = null;
    }
  }

  public final TXId getTransactionId() {
    final TXStateInterface txState = getTXState();
    return txState != null ? txState.getTransactionId() : null;
  }

  public final TXStateInterface getTXState(final LocalRegion r) {
    if (this.txState != TXStateProxy.TX_NOT_SET) {
      return this.txState;
    }
    return (this.txState = r.getTXState());
  }

  public final TXStateInterface getTXState() {
    if (this.txState != TXStateProxy.TX_NOT_SET) {
      return this.txState;
    }
    return (this.txState = (this.region != null ? this.region.getTXState()
        : TXManagerImpl.getCurrentTXState()));
  }

  public final boolean hasTX() {
    final TXStateInterface txState = getTXState();
    return txState != null && !txState.isSnapshot();
  }

  public final void setTXState(final TXStateInterface tx) {
    this.txState = tx;
  }

  /**
   * Answer true if this event resulted from a loader.
   * 
   * @return true if isLocalLoad or isNetLoad
   */
  public final boolean isLoad() {
    return this.op.isLoad();
  }

  public final void setRegion(LocalRegion r) {
    this.region = r;
  }

  /**
   * @see com.gemstone.gemfire.cache.CacheEvent#getRegion()
   */
  public final LocalRegion getRegion() {
    return region;
  }

  public final Operation getOperation() {
    return this.op;
  }

  public final void setOperation(Operation op) {
    this.op = op;
    PartitionMessage prm = getPartitionMessage();
    if (prm != null) {
      prm.setOperation(this.op);
    }
  }

  /**
   * @see com.gemstone.gemfire.cache.CacheEvent#getCallbackArgument()
   */
  public final Object getCallbackArgument() {
    final Object result = this.callbackArg;
    if (result == null) {
      return null;
    }
    return getCallbackArgument(result);
  }

  public final Object getCallbackArgument(Object result) {
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument)result;
      result = wca.getOriginalCallbackArg();
    }
    if (result == Token.NOT_AVAILABLE) {
      result = AbstractRegion.handleNotAvailable(result);
    }
    return result;
  }

  public final boolean isCallbackArgumentAvailable() {
    return this.getRawCallbackArgument() != Token.NOT_AVAILABLE;
  }

  /**
   * Returns the value of the EntryEventImpl field.
   * This is for internal use only. Customers should always call
   * {@link #getCallbackArgument}
   * @since 5.5 
   */
  public final Object getRawCallbackArgument() {
    return this.callbackArg;
  }
  
  /**
   * Sets the value of raw callback argument field.
   */
  public final void setRawCallbackArgument(Object newCallbackArgument) {
    super.setCallbackArgument(newCallbackArgument);
  }

  @Override
  public final void setCallbackArgument(Object newCallbackArgument) {
    if (this.callbackArg instanceof WrappedCallbackArgument) {
      ((WrappedCallbackArgument)this.callbackArg)
          .setOriginalCallbackArgument(newCallbackArgument);
    }
    else {
      this.callbackArg = newCallbackArgument;
    }
  }

  /**
   * @return null if new value is not serialized; otherwise returns a SerializedCacheValueImpl containing the new value.
   */
  public final SerializedCacheValueImpl getSerializedNewValue() {
    // In the case where there is a delta that has not been applied yet,
    // do not apply it here since it would not produce a serialized new
    // value (return null instead to indicate the new value is not
    // in serialized form).
    @Unretained(ENTRY_EVENT_NEW_VALUE)
    final Object tmp = basicGetNewValue();
    if (tmp instanceof CachedDeserializable) {
      if (tmp instanceof StoredObject) {
        if (!((StoredObject) tmp).isSerialized()) {
          // TODO OFFHEAP can we handle offheap byte[] better?
          return null;
        }
      }
      return new SerializedCacheValueImpl(this, getRegion(), this.re,
          (CachedDeserializable)tmp, this.newValueBytes);
    }
    else {
      return null;
    }
  }
  
  /**
   * Implement this interface if you want to call {@link #exportNewValue}.
   * 
   * @author darrel
   *
   */
  public interface NewValueImporter {
    /**
     * @return true if the importer prefers the value to be in serialized form.
     */
    boolean prefersNewSerialized();

    /**
     * Only return true if the importer can use the value before the event that exported it is released.
     * If false is returned then off-heap values will be copied to the heap for the importer.
     * @return true if the importer can deal with the value being an unretained OFF_HEAP_REFERENCE.
     */
    boolean isUnretainedNewReferenceOk();

    /**
     * Import a new value that is currently in object form.
     * @param nv the new value to import; unretained if isUnretainedNewReferenceOk returns true
     * @param isSerialized true if the imported new value represents data that needs to be serialized; false if the imported new value is a simple sequence of bytes.
     */
    void importNewObject(@Unretained(ENTRY_EVENT_NEW_VALUE) Object nv, boolean isSerialized);

    /**
     * Import a new value that is currently in byte array form.
     * @param nv the new value to import
     * @param isSerialized true if the imported new value represents data that needs to be serialized; false if the imported new value is a simple sequence of bytes.
     */
    void importNewBytes(byte[] nv, boolean isSerialized);
  }
  
  /**
   * Export the event's new value to the given importer.
   */
  public final void exportNewValue(NewValueImporter importer) {
    final boolean prefersSerialized = importer.prefersNewSerialized();
    if (prefersSerialized) {
      if (this.newValueBytes != null && this.newValue instanceof CachedDeserializable) {
        importer.importNewBytes(this.newValueBytes, true);
        return;
      }
    }
    @Unretained(ENTRY_EVENT_NEW_VALUE) 
    final Object nv = getRawNewValue();
    if (nv instanceof StoredObject) {
      @Unretained(ENTRY_EVENT_NEW_VALUE)
      final StoredObject so = (StoredObject) nv;
      final boolean isSerialized = so.isSerialized();
      if (nv instanceof Chunk) {
        if (importer.isUnretainedNewReferenceOk()) {
          importer.importNewObject(nv, isSerialized);
        } else {
          if (!isSerialized || prefersSerialized) {
            importer.importNewBytes(so.getValueAsHeapByteArray(), isSerialized);
          } else {
            importer.importNewObject(so.getValueAsDeserializedHeapObject(), true);
          }
        }
      } else {
        importer.importNewObject(nv, isSerialized);
      }
    } else if (nv instanceof byte[]) {
      importer.importNewBytes((byte[])nv, false);
    } else if (nv instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) nv;
      Object cdV = cd.getValue();
      if (cdV instanceof byte[]) {
        importer.importNewBytes((byte[]) cdV, true);
      } else {
        importer.importNewObject(cdV, true);
      }
    } else {
      importer.importNewObject(nv, true);
    }
  }
  /**
   * Implement this interface if you want to call {@link #exportOldValue}.
   * 
   * @author darrel
   *
   */
  public interface OldValueImporter {
    /**
     * @return true if the importer prefers the value to be in serialized form.
     */
    boolean prefersOldSerialized();

    /**
     * Only return true if the importer can use the value before the event that exported it is released.
     * @return true if the importer can deal with the value being an unretained OFF_HEAP_REFERENCE.
     */
    boolean isUnretainedOldReferenceOk();
    
    /**
     * @return return true if you want the old value to possibly be an instanceof CachedDeserializable; false if you want the value contained in a CachedDeserializable.
     */
    boolean isCachedDeserializableValueOk();

    /**
     * Import an old value that is currently in object form.
     * @param ov the old value to import; unretained if isUnretainedOldReferenceOk returns true
     * @param isSerialized true if the imported old value represents data that needs to be serialized; false if the imported old value is a simple sequence of bytes.
     */
    void importOldObject(@Unretained(ENTRY_EVENT_OLD_VALUE) Object ov, boolean isSerialized);

    /**
     * Import an old value that is currently in byte array form.
     * @param ov the old value to import
     * @param isSerialized true if the imported old value represents data that needs to be serialized; false if the imported old value is a simple sequence of bytes.
     */
    void importOldBytes(byte[] ov, boolean isSerialized);
  }
  
  /**
   * Export the event's old value to the given importer.
   */
  public final void exportOldValue(OldValueImporter importer) {
    final boolean prefersSerialized = importer.prefersOldSerialized();
    if (prefersSerialized) {
      if (this.oldValueBytes != null && this.oldValue instanceof CachedDeserializable) {
        importer.importOldBytes(this.oldValueBytes, true);
        return;
      }
    }
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    final Object ov = getRawOldValue();
    if (ov instanceof StoredObject) {
      final StoredObject so = (StoredObject) ov;
      final boolean isSerialized = so.isSerialized();
      if (ov instanceof Chunk) {
        if (importer.isUnretainedOldReferenceOk()) {
          importer.importOldObject(ov, isSerialized);
        } else {
          if (!isSerialized || prefersSerialized) {
            importer.importOldBytes(so.getValueAsHeapByteArray(), isSerialized);
          } else {
            importer.importOldObject(so.getValueAsDeserializedHeapObject(), true);
          }
        }
      } else {
        importer.importOldObject(ov, isSerialized);
      }
    } else if (ov instanceof byte[]) {
      importer.importOldBytes((byte[])ov, false);
    } else if (!importer.isCachedDeserializableValueOk() && ov instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) ov;
      Object cdV = cd.getValue();
      if (cdV instanceof byte[]) {
        importer.importOldBytes((byte[]) cdV, true);
      } else {
        importer.importOldObject(cdV, true);
      }
    } else {
      importer.importOldObject(ov, true);
    }
  }

  /**
   * If applyDelta is true then first attempt to apply a delta (if we have one) and return the value.
   * Else if new value is a Delta return it.
   * Else if new value is off-heap return the StoredObject form (unretained OFF_HEAP_REFERENCE). 
   * Its refcount is not inced by this call and the returned object can only be safely used for the lifetime of the EntryEventImpl instance that returned the value.
   * Else return the raw form.
   */
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public final Object getRawNewValue(boolean applyDelta) {
    if (applyDelta) {
      boolean doCopyOnRead = getRegion().isCopyOnRead();
      Object newValueWithDelta = applyDeltaWithCopyOnRead(doCopyOnRead);
      if (newValueWithDelta != null) {
        return newValueWithDelta;
      }
      // if applyDelta is true and we have already applied the delta then
      // just return the applied value instead of the delta object.
      @Unretained(ENTRY_EVENT_NEW_VALUE)
      Object newValue = basicGetNewValue();
      if (newValue != null) return newValue;
    }
    return getRawNewValue();
  }
  /**
   * Just like getRawNewValue(true) except if the raw new value is off-heap deserialize it.
   * Note that in some cases gfxd ignores the request to deserialize.
   */
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public final Object getNewValueAsOffHeapDeserializedOrRaw() {
    Object result = getRawNewValue(true);
    if (result instanceof StoredObject) {
      result = ((StoredObject) result).getDeserializedForReading();
    }
    return AbstractRegion.handleNotAvailable(result); // fixes 49499
  }

  /**
   * If the new value is stored off-heap return a retained OFF_HEAP_REFERENCE (caller must release).
   * @return a retained OFF_HEAP_REFERENCE if the new value is off-heap; otherwise returns null
   */
  @Retained
  public final StoredObject getOffHeapNewValue() {
    final Object tmp = basicGetNewValue();
    if (tmp instanceof StoredObject) {
      StoredObject result = (StoredObject) tmp;
      if (!result.retain()) {
        return null;
      }
      return result;
    } else {
      return null;
    }
  }
  
  /**
   * If the old value is stored off-heap return a retained OFF_HEAP_REFERENCE (caller must release).
   * @return a retained OFF_HEAP_REFERENCE if the old value is off-heap; otherwise returns null
   */
  @Retained
  public final StoredObject getOffHeapOldValue() {
    final Object tmp = basicGetOldValue();
    if (tmp instanceof StoredObject) {
      StoredObject result = (StoredObject) tmp;
      if (!result.retain()) {
        return null;
      }
      return result;
    } else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getDeserializedValue() {
    if (this.delta == null) {
      final Object val = basicGetNewValue();
      if (val instanceof StoredObject) {
        return ((StoredObject) val).getValueAsDeserializedHeapObject();
      } else 
      if (val instanceof CachedDeserializable) {
        return ((CachedDeserializable)val).getDeserializedForReading();
      }
      else {
        return val;
      }
    }
    else {
      return this.delta;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte[] getSerializedValue() {
    if (this.newValueBytes == null) {
      final Object val;
      if (this.delta == null) {
        val = basicGetNewValue();
        if (val instanceof byte[]) {
          return (byte[])val;
        }
        else if (val instanceof CachedDeserializable) {
          return ((CachedDeserializable)val).getSerializedValue();
        }
      }
      else {
        val = this.delta;
      }
      try {
        return CacheServerHelper.serialize(val);
      } catch (IOException ioe) {
        throw new GemFireIOException("unexpected exception", ioe);
      }
    }
    else {
      return this.newValueBytes;
    }
  }

  /**
   * Forces this entry's new value to be in serialized form.
   * @since 5.0.2
   */
  public final void makeSerializedNewValue() {
    makeSerializedNewValue(false);
  }

  /**
   * @param isSynced true if RegionEntry currently under synchronization
   */
  private final void makeSerializedNewValue(boolean isSynced) {
    Object obj = basicGetNewValue();

    // ezoerner:20080611 In the case where there is an unapplied
    // delta, do not apply the delta or serialize yet unless entry is
    // under synchronization (isSynced is true) 
    if (isSynced) {
      this.setSerializationDeferred(false);
    }
    else if (obj == null && this.delta != null) {
      // defer serialization until setNewValueInRegion
      this.setSerializationDeferred(true);
      return;
    }
    basicSetNewValue(getCachedDeserializable(obj, this));
  }

  public static Object getCachedDeserializable(Object obj) {
    return getCachedDeserializable(obj, null);
  }

  private static Object getCachedDeserializable(Object obj, EntryEventImpl ev) {
    if (CachedDeserializableFactory.preferObject()
                            || obj instanceof byte[]
                            || obj == null
                            || obj instanceof CachedDeserializable
                            || obj == Token.NOT_AVAILABLE
                            || Token.isInvalidOrRemoved(obj)
                            // don't serialize delta object already serialized
                            || obj instanceof com.gemstone.gemfire.Delta
                            || obj instanceof Delta) { // internal delta
      return obj;
    }
    final byte[] b = serialize(obj);
    final CachedDeserializable cd = CachedDeserializableFactory.create(b);
    if (ev != null) {
      ev.newValueBytes = b;
    }
    return cd;
  }

  public final void setSerializedNewValue(byte[] serializedValue) {
    Object newVal = null;
    this.delta = null;
    if (serializedValue != null) {
      if (CachedDeserializableFactory.preferObject()) {
        newVal = deserialize(serializedValue);
      } else {
        newVal = CachedDeserializableFactory.create(serializedValue);
      }
      if (newVal instanceof Delta) {
        this.delta = (Delta)newVal;
        newVal = null;
        // We need the newValueBytes field and the newValue field to be in sync.
        // In the case of non-null delta set both fields to null.
        serializedValue = null;
      }
    }
    this.newValueBytes = serializedValue;
    basicSetNewValue(newVal);
  }

  public final void setSerializedOldValue(byte[] serializedOldValue) {
    this.oldValueBytes = serializedOldValue;
    final Object ov;
    if (CachedDeserializableFactory.preferObject()) {
      ov = deserialize(serializedOldValue);
    }
    else if (serializedOldValue != null) {
      ov = CachedDeserializableFactory.create(serializedOldValue);
    }
    else {
      ov = null;
    }
    retainAndSetOldValue(ov);
  }

  /**
   * If true (the default) then preserve old values in events.
   * If false then mark non-null values as being NOT_AVAILABLE.
   */
  private static final boolean EVENT_OLD_VALUE = !Boolean.getBoolean("gemfire.disable-event-old-value");

  final void putExistingEntry(final LocalRegion owner, RegionEntry re, int olValueSize)
      throws RegionClearedException {
    putExistingEntry(owner, re, false, null, olValueSize);
  }

  /**
   * Put a newValue into the given, write synced, existing, region entry.
   * Sets oldValue in event if hasn't been set yet.
   * @param oldValueForDelta Used by Delta Propagation feature
   * 
   * @throws RegionClearedException
   */
  final void putExistingEntry(final LocalRegion owner, final RegionEntry reentry,
     boolean requireOldValue, Object oldValueForDelta, int olValueSize) throws RegionClearedException {
    makeUpdate();
    // only set oldValue if it hasn't already been set to something
    if (this.oldValue == null) {
      if (!reentry.isInvalidOrRemoved()) {
        if (requireOldValue ||
            EVENT_OLD_VALUE
            || this.region instanceof HARegion // fix for bug 37909
            || GemFireCacheImpl.gfxdSystem()
            ) {
          @Retained Object ov;
          if (SimpleMemoryAllocatorImpl.trackReferenceCounts()) {
            SimpleMemoryAllocatorImpl.setReferenceCountOwner(new OldValueOwner());
            if (GemFireCacheImpl.gfxdSystem()) {
              ov = reentry.getValueOffHeapOrDiskWithoutFaultIn(this.region);
            } else {
              ov = reentry._getValueRetain(owner, true);
            }
            SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
          } else {
            if (GemFireCacheImpl.gfxdSystem()) {
              ov = reentry.getValueOffHeapOrDiskWithoutFaultIn(this.region);
            } else {
              ov = reentry._getValueRetain(owner, true);
            }
          }
          if (ov == null) ov = Token.NOT_AVAILABLE;
          // ov has already been retained so call basicSetOldValue instead of retainAndSetOldValue
          basicSetOldValue(ov);
        } else {
          basicSetOldValue(Token.NOT_AVAILABLE);
        }
      }
    }
    if (this.oldValue == Token.NOT_AVAILABLE) {
      FilterProfile fp = this.region.getFilterProfile();
      if (this.op.guaranteesOldValue() || 
          (fp != null /* #41532 */&& fp.entryRequiresOldValue(this.getKey()))) {
        setOldValueForQueryProcessing();
      }
    }

    //setNewValueInRegion(null);
    setNewValueInRegion(owner, reentry, oldValueForDelta, olValueSize);
  }

  /**
   * If we are currently a create op then turn us into an update
   *
   * @since 5.0
   */
  final void makeUpdate() {
    setOperation(this.op.getCorrespondingUpdateOp());
  }

  /**
   * If we are currently an update op then turn us into a create
   *
   * @since 5.0
   */
  final void makeCreate() {
    setOperation(this.op.getCorrespondingCreateOp());
  }

  /**
   * Put a newValue into the given, write synced, new, region entry.
   * @throws RegionClearedException
   */
  final void putNewEntry(final LocalRegion owner, final RegionEntry reentry)
      throws RegionClearedException {
    if (!this.op.guaranteesOldValue()) {  // preserves oldValue for map ops in clients
      basicSetOldValue(null);
    }
    makeCreate();
    setNewValueInRegion(owner, reentry, null, 0);
  }

  private void acquireMemory(final LocalRegion owner,
                             EntryEventImpl event,
                             int oldSize,
                             boolean isUpdate,
                             boolean wasTombstone) {

    if (isUpdate && !wasTombstone) {
      if (this.memoryTracker != null) {
        owner.acquirePoolMemory(oldSize,
                event.getNewValueBucketSize(),
                false,
                this.memoryTracker,
                true);
      } else {
        owner.delayedAcquirePoolMemory(oldSize,
                event.getNewValueBucketSize(),
                false,
                true);
      }
    } else {
      int indexOverhead = owner.indicesOverHead();
      if (this.memoryTracker != null) {
        owner.acquirePoolMemory(0,
                event.getNewValueBucketSize() + indexOverhead,
                true,
                this.memoryTracker,
                true);
      } else {
        owner.delayedAcquirePoolMemory(0,
                event.getNewValueBucketSize() + indexOverhead,
                true,
                true);
      }
    }
  }

  public final void setRegionEntry(RegionEntry re) {
    this.re = re;
  }

  public final RegionEntry getRegionEntry() {
    return this.re;
  }

  @Retained(ENTRY_EVENT_NEW_VALUE)
  private final void setNewValueInRegion(final LocalRegion owner,
      final RegionEntry reentry, Object oldValueForDelta, int oldValueSize)
      throws RegionClearedException {

    final LogWriterI18n logger = this.region.getCache().getLoggerI18n();
    boolean wasTombstone = reentry.isTombstone();
    
    // put in newValue

    if (applyDelta(this.op.isCreate())) {
      if (this.isSerializationDeferred()) {
        makeSerializedNewValue(true);
      }
    }

    // If event contains new value, then it may mean that the delta bytes should
    // not be applied. This is possible if the event originated locally.
    if (this.deltaBytes != null && this.newValue == null) {
      processDeltaBytes(oldValueForDelta);
    }   

    VersionStamp originalStamp = reentry.getVersionStamp();
    VersionTag originalStampAsTag = null;
    if (originalStamp != null) {
      originalStampAsTag = originalStamp.asVersionTag();
    }
    
    if (owner!=null) {
      owner.generateAndSetVersionTag(this, reentry);
    } else {
      this.region.generateAndSetVersionTag(this, reentry);
    }
    
    Object v = this.newValue;
    if (v == null) {
      v = isLocalInvalid() ? Token.LOCAL_INVALID : Token.INVALID;
    }
    else {
      this.region.regionInvalid = false;
    }

    //reentry.setValueResultOfSearch(this.op.isNetSearch());

    //dsmith:20090524
    //This is a horrible hack, but we need to get the size of the object
    //When we store an entry. This code is only used when we do a put
    //in the primary.
    if (region.isUsedForPartitionedRegionBucket()
        && !CachedDeserializableFactory.preferObject()
        && v instanceof com.gemstone.gemfire.Delta) {

      int vSize;
      Object ov = basicGetOldValue();
      if(ov instanceof CachedDeserializable && !GemFireCacheImpl.DELTAS_RECALCULATE_SIZE) {
        vSize = ((CachedDeserializable) ov).getValueSizeInBytes();
      } else {
        vSize = CachedDeserializableFactory.calcMemSize(v, region.getObjectSizer(), false);
      }
//       com.gemstone.gemfire.internal.cache.GemFireCache.getInstance().getLogger().info("DEBUG setNewValueInRegion: oldValue=" + oldValue + " newValue=" + v + " objSizer=" + region.getObjectSizer(), new RuntimeException("STACK"));
      v = CachedDeserializableFactory.create(v, vSize);
      basicSetNewValue(v);
      // OFFHEAP todo: should deltas be stored offheap? If so we need to call prepareValueForCache here.
    }
    
    Object preparedV = reentry.prepareValueForCache(this.region, v, this.hasDelta(), 
        this.getTransactionId() == null);
    if (preparedV != v) {
      v = preparedV;
      if (v instanceof Chunk) {
        // if we put it off heap then remember that value.
        // TODO COMPRESS: should we remember both the transformed and untransformed values?
        basicSetNewValue(v);
        
      }
    }
    boolean isTombstone = (v == Token.TOMBSTONE);
    boolean success = false;
    boolean calledSetValue = false;
    try {
    setNewValueBucketSize(owner, v);
    if(!region.reservedTable() && region.needAccounting()){
      owner.calculateEntryOverhead(reentry);
      acquireMemory(owner, this, oldValueSize, this.op.isUpdate(), isTombstone);
    }


    // ezoerner:20081030 
    // last possible moment to do index maintenance with old value in
    // RegionEntry before new value is set.
    // As part of an update, this is a remove operation as prelude to an add that
    // will come after the new value is set.
    // If this is an "update" from INVALID state, treat this as a create instead
    // for the purpose of index maintenance since invalid entries are not
    // indexed.
    
    if ((this.op.isUpdate() && !reentry.isInvalid()) || this.op.isInvalidate()) {
      IndexManager idxManager = IndexUtils.getIndexManager(this.region, false);
      if (idxManager != null) {
        try {
          idxManager.updateIndexes(reentry,
                                   IndexManager.REMOVE_ENTRY,
                                   this.op.isUpdate() ?
                                     IndexProtocol.BEFORE_UPDATE_OP :
                                     IndexProtocol.OTHER_OP);
        }
        catch (QueryException e) {
          throw new IndexMaintenanceException(e);
        }
      }
    }
    final IndexUpdater indexUpdater = this.region.getIndexUpdater();
    final TXStateInterface txState = getTXState();
    if (indexUpdater != null && (txState == null || txState.isSnapshot())) {
      final LocalRegion indexRegion;
      if (owner != null) {
        indexRegion = owner;
      }
      else {
        indexRegion = this.region;
      }
      try {
        indexUpdater.onEvent(indexRegion, this, reentry);
        calledSetValue = true;
        reentry.setValueWithTombstoneCheck(v, this); // already called prepareValueForCache
        success = true;
      } 
      finally {
        if(!success) {
          // Reset the version stamp
          VersionStamp stamp = reentry.getVersionStamp();
          if(stamp != null && originalStampAsTag != null) {
            stamp.setVersions(originalStampAsTag);
            stamp.setMemberID(originalStampAsTag.getMemberID());
          }
        }
        indexUpdater.postEvent(indexRegion, this, reentry, success);
      }
    }
    else {
      calledSetValue = true;
      reentry.setValueWithTombstoneCheck(v, this); // already called prepareValueForCache
      success = true;
    }
    } finally {
      if (!success && reentry.isOffHeap() && v instanceof Chunk) {
        OffHeapRegionEntryHelper.releaseEntry((OffHeapRegionEntry)reentry, (Chunk)v);
      }
    }
    if (logger.finerEnabled()) {
      if (v instanceof CachedDeserializable) {
        logger
            .finer("EntryEventImpl.setNewValueInRegion: put CachedDeserializable("
                + this.getKey()
                + ","
                + ((CachedDeserializable)v).getStringForm() + ")");
      }
      else {
        logger.finer("EntryEventImpl.setNewValueInRegion: put(" + this.getKey() + ","
            + LogWriterImpl.forceToString(v) + ")");
      }
    }

    if (!isTombstone  &&  wasTombstone) {
      owner.unscheduleTombstone(reentry);
    }
  }

  /**
   * The size the new value contributes to a pr bucket.
   * Note if this event is not on a pr then this value will be 0.
   */
  private transient int newValueBucketSize;
  public final int getNewValueBucketSize() {
    return this.newValueBucketSize;
  }
  private final void setNewValueBucketSize(LocalRegion lr, Object v) {
    if (lr == null) {
      lr = this.region;
    }
    this.newValueBucketSize = lr.calculateValueSize(v);
  }

  private void processDeltaBytes(Object oldValueInVM) {
    if (!this.region.hasSeenEvent(this)) {
      if (oldValueInVM == null || Token.isInvalidOrRemoved(oldValueInVM)) {
        this.region.getCachePerfStats().incDeltaFailedUpdates();
        throw new InvalidDeltaException("Old value not found for key "
            + getKey());
      }
      FilterProfile fp = this.region.getFilterProfile();
      // If compression is enabled then we've already gotten a new copy due to the
      // serializaion and deserialization that occurs.
      boolean copy = this.region.getCompressor() == null &&
          (this.region.isCopyOnRead()
          || this.region.getCloningEnabled()
          || (fp != null && fp.getCqCount() > 0));
      Object value = oldValueInVM;
      boolean wasCD = false;
      if (value instanceof CachedDeserializable) {
        wasCD = true;
        if (copy) {
          value = ((CachedDeserializable)value).getDeserializedWritableCopy(this.region, re);
        } else {
          value = ((CachedDeserializable)value).getDeserializedValue(
              this.region, re);
        }
      } else {
        if (copy) {
          value = CopyHelper.copy(value);
        }
      }
      boolean deltaBytesApplied = false;
      try {
        long start = CachePerfStats.getStatTime();
        ((com.gemstone.gemfire.Delta)value).fromDelta(new DataInputStream(
            new ByteArrayInputStream(getDeltaBytes())));
        this.region.getCachePerfStats().endDeltaUpdate(start);
        deltaBytesApplied = true;
      } catch (RuntimeException rte) {
        throw rte;
      } catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      } catch (Throwable t) {
        SystemFailure.checkFailure();
        throw new DeltaSerializationException(
            "Exception while deserializing delta bytes.", t);
      } finally {
        if (!deltaBytesApplied) {
          this.region.getCachePerfStats().incDeltaFailedUpdates();
        }
      }
      if (this.region.getLogWriterI18n().fineEnabled()) {
        this.region.getLogWriterI18n().fine(
            "Delta has been applied for key " + getKey());
      }
      // assert event.getNewValue() == null;
      if (wasCD) {
        CachedDeserializable old = (CachedDeserializable)oldValueInVM;
        int valueSize;
        if (GemFireCacheImpl.DELTAS_RECALCULATE_SIZE) {
          valueSize = CachedDeserializableFactory.calcMemSize(value, region
              .getObjectSizer(), false);
        } else {
          valueSize = old.getValueSizeInBytes();
        }
        value = CachedDeserializableFactory.create(value, valueSize);
      }
      setNewValue(value);
      if (this.causedByMessage != null
          && this.causedByMessage instanceof PutMessage) {
        ((PutMessage)this.causedByMessage).setDeltaValObj(value);
      }
    } else {
      this.region.getCachePerfStats().incDeltaFailedUpdates();
      throw new InvalidDeltaException(
          "Cache encountered replay of event containing delta bytes for key "
              + getKey());
    }
  }

  final void setTXEntryOldValue(Object oldVal, boolean mustBeAvailable) {
    if (Token.isInvalidOrRemoved(oldVal)) {
      oldVal = null;
    }
    else {
      if (mustBeAvailable || oldVal == null || EVENT_OLD_VALUE) {
        // set oldValue to oldVal
      }
      else {
        oldVal = Token.NOT_AVAILABLE;
      }
    }
    retainAndSetOldValue(oldVal);
  }

  final void putValueTXEntry(final TXEntryState txEntry, final byte op) {
    // !!!:ezoerner:20080611 Deltas do not yet work with transactions
    // [sumedh] 20101215: GemFireXD deltas now work correctly with transactions

    Object v = basicGetNewValue();
    if (v == null) {
      if (deltaBytes != null) {
        // since newValue is null, and we have deltaBytes
        // there must be a nearSidePendingValue
        processDeltaBytes(txEntry.getNearSidePendingValue());
        v = basicGetNewValue();
      }
      else if (delta == null) {
        if (this.op.isInvalidate()) {
          v = isLocalInvalid() ? Token.LOCAL_INVALID : Token.INVALID;
        }
      }
    }

    if (this.op != Operation.LOCAL_INVALIDATE
        && this.op != Operation.LOCAL_DESTROY) {
      // fix for bug 34387
      txEntry.setPendingValue(OffHeapHelper.copyIfNeeded(v),
          this.delta, this.region, op); // TODO OFFHEAP optimize
    }
    putTXEntryCallbackArg(txEntry);
  }

  final void putTXEntryCallbackArg(final TXEntryState txEntry) {
    final Object callbackArg = getRawCallbackArgument();
    if (callbackArg != null) {
      txEntry.setCallbackArgument(getCallbackArgument(callbackArg));
    }
    else {
      txEntry.setCallbackArgument(null);
    }
  }

  /** @return false if entry doesn't exist */
  public final boolean setOldValueFromRegion() {
    try {
      RegionEntry re = this.region.getRegionEntry(getKey());
      if (re == null) return false;
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      Object v = re._getValueRetain(this.region, true);
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
        return setOldValue(v);
      } finally {
        OffHeapHelper.releaseWithNoTracking(v);
      }
    } catch (EntryNotFoundException ex) {
      return false;
    }
  }

  /** Return true if old value is the DESTROYED token */
  public final boolean oldValueIsDestroyedToken() {
    return this.oldValue == Token.DESTROYED || this.oldValue == Token.TOMBSTONE;
  }

  final void setOldValueDestroyedToken() {
    basicSetOldValue(Token.DESTROYED);
  }

  public final boolean newValueIsDestroyedToken() {
    return this.newValue == Token.DESTROYED || this.newValue == Token.TOMBSTONE;
  }

  /**
   * @return false if value 'v' indicates that entry does not exist
   */
  public final boolean setOldValue(Object v) {
    return setOldValue(v, false);
  }

  /**
   * TODO: currently this method does not force for null/INVALID value?
   * Use the overloaded 3-arg method with "forceIfNull" arg to force
   * null value.
   * 
   * @param force true if the old value should be forcibly set, used
   * for HARegions, methods like putIfAbsent, etc.,
   * where the old value must be available.
   * @return false if value 'v' indicates that entry does not exist
   */
  public final boolean setOldValue(Object v, boolean force) {
    // TODO: [sumedh] why does this not force for null/INVALID?? currently
    // splitting out to a new method but this should really be fixed for all
    // code paths and addditional forceIfNull argument removed
    return setOldValue(v, force, false);
  }

  /**
   * @param force true if the old value should be forcibly set, used
   * for HARegions, partitioned region buckets, methods like putIfAbsent, etc.,
   * where the old value must be available.
   * @return false if value 'v' indicates that entry does not exist
   */
  public final boolean setOldValue(Object v, boolean force,
      boolean forceIfNull) {
    if (!forceIfNull && (v == null || Token.isRemoved(v))) {
      return false;
    }
    else {
      if (Token.isInvalid(v)) {
        v = null;
      }
      else {
        if (force || forceIfNull ||
            (this.region instanceof HARegion) // fix for bug 37909
            ) {
          // set oldValue to "v".
        } else if (EVENT_OLD_VALUE) {
          // TODO Rusty add compression support here
          // set oldValue to "v".
        } else {
          v = Token.NOT_AVAILABLE;
        }
      }
      retainAndSetOldValue(v);
      return true;
    }
  }

  public final void setTXOldValue(@Retained(ENTRY_EVENT_OLD_VALUE) final Object v) {
    final Object oldVal;
    if (Token.isInvalid(v)) {
      oldVal = null;
    } else {
      oldVal = v;
    }
    retainAndSetOldValue(oldVal);
  }

  /**
   * sets the old value for concurrent map operation results received
   * from a server.
   */
  public final void setConcurrentMapOldValue(Object v) {
    if (Token.isRemoved(v)) {
      return;
    } else {
      if (Token.isInvalid(v)) {
        v = null;
      }   
      retainAndSetOldValue(v);
    }
  }

  /** Return true if new value available */
  public final boolean hasNewValue() {
    Object tmp = this.newValue;
    if (tmp == null && hasDelta()) {
      // ???:ezoerner:20080611 what if applying the delta would produce
      // null or (strangely) NOT_AVAILABLE.. do we need to apply it here to
      // find out?
      return true;
    }
    return  tmp != null && tmp != Token.NOT_AVAILABLE;
  }

  public final boolean hasOldValue() {
    return this.oldValue != null && this.oldValue != Token.NOT_AVAILABLE;
  }
  public final boolean isOldValueAToken() {
    return this.oldValue instanceof Token;
  }
  
  public final void removeDelta() {
    this.delta = null;
  }

  /**
   * This should only be used in case of internal delta and <B>not for Delta of
   * Delta Propagation feature</B>.
   * 
   * @return boolean
   */
  @Override
  public final boolean hasDelta() {
    return (this.delta != null);
  }

  public final boolean hasColumnDelta() {
    return this.delta instanceof SerializedDiskBuffer;
  }

  /**
   * Return true for an internal Delta that requires an old value in region.
   */
  public final boolean hasDeltaPut() {
    return (this.delta != null && !this.delta.allowCreate());
  }

  @Override
  public final boolean isGFXDCreate(boolean updateAsCreateOnlyForPosDup) {
    if (this.op.isCreate()) {
      return true;
    }
    else if (updateAsCreateOnlyForPosDup) {
      return (isPossibleDuplicate() && this.op.isUpdate() && !hasDelta());
    }
    else {
      return (this.op.isUpdate() && !hasDelta());
    }
  }

  public final boolean isOldValueAvailable() {
    if (isOriginRemote() && this.region.isProxy()) {
      return false;
    } else {
      return basicGetOldValue() != Token.NOT_AVAILABLE;
    }
  }

  public final void oldValueNotAvailable() {
    basicSetOldValue(Token.NOT_AVAILABLE);
  }

  public static Object deserialize(byte[] bytes) {
    return deserialize(bytes, null, null);
  }

  public static Object deserialize(byte[] bytes, Version version,
      ByteArrayDataInput in) {
    if (bytes == null)
      return null;
    try {
      return BlobHelper.deserializeBlob(bytes, version, in);
    }
    catch (IOException e) {
      throw new SerializationException(LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_DESERIALIZING.toLocalizedString(), e);
    }
    catch (ClassNotFoundException e) {
      // fix for bug 43602
      throw new SerializationException(LocalizedStrings.EntryEventImpl_A_CLASSNOTFOUNDEXCEPTION_WAS_THROWN_WHILE_TRYING_TO_DESERIALIZE_CACHED_VALUE.toLocalizedString(), e);
    }
  }

  public static Object deserializeBuffer(ByteBuffer buffer, Version version) {
    if (buffer == null) return null;
    try {
      return BlobHelper.deserializeBuffer(buffer, version);
    } catch (IOException e) {
      throw new SerializationException(LocalizedStrings
          .EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_DESERIALIZING
          .toLocalizedString(), e);
    } catch (ClassNotFoundException e) {
      // fix for bug 43602
      throw new SerializationException(LocalizedStrings
          .EntryEventImpl_A_CLASSNOTFOUNDEXCEPTION_WAS_THROWN_WHILE_TRYING_TO_DESERIALIZE_CACHED_VALUE
          .toLocalizedString(), e);
    }
  }

  /**
   * Serialize an object into a <code>byte[]</code>
   *
   * @throws IllegalArgumentException
   *           If <code>obj</code> should not be serialized
   */
  public static byte[] serialize(Object obj) {
    return serialize(obj, (Version)null);
  }

  /**
   * Serialize an object into a <code>byte[]</code>
   *
   * @throws IllegalArgumentException
   *           If <code>obj</code> should not be serialized
   */
  public static byte[] serialize(Object obj, Version version) {
    if (obj == null || obj == Token.NOT_AVAILABLE
        || Token.isInvalidOrRemoved(obj))
      throw new IllegalArgumentException(LocalizedStrings.EntryEventImpl_MUST_NOT_SERIALIZE_0_IN_THIS_CONTEXT.toLocalizedString(obj));
    try {
      return BlobHelper.serializeToBlob(obj, version);
    }
    catch (IOException e) {
      throw new SerializationException(LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING.toLocalizedString(), e);
    }
  }

  /**
   * Serialize an object into a direct <code>ByteBuffer</code>.
   * If the object is itself a <code>SerializedDiskBuffer</code> then it would
   * have been retained once on return so caller can safely release the
   * result exactly once.
   *
   * @throws IllegalArgumentException If <code>obj</code> should not be serialized
   */
  public static SerializedDiskBuffer serializeBuffer(Object obj, Version version) {
    if (obj == null || obj == Token.NOT_AVAILABLE
        || Token.isInvalidOrRemoved(obj)) {
      throw new IllegalArgumentException(LocalizedStrings
          .EntryEventImpl_MUST_NOT_SERIALIZE_0_IN_THIS_CONTEXT
          .toLocalizedString(obj));
    }
    try {
      return BlobHelper.serializeToBuffer(obj, version);
    } catch (IOException e) {
      throw new SerializationException(LocalizedStrings
          .EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING
          .toLocalizedString(), e);
    }
  }

  /**
   * Serialize an object into a <code>byte[]</code>
   *
   * @throws IllegalArgumentException
   *           If <code>obj</code> should not be serialized
   */
  public static byte[] serialize(Object obj, HeapDataOutputStream hdos) {
    if (obj == null || obj == Token.NOT_AVAILABLE
        || Token.isInvalidOrRemoved(obj))
      throw new IllegalArgumentException(LocalizedStrings.EntryEventImpl_MUST_NOT_SERIALIZE_0_IN_THIS_CONTEXT.toLocalizedString(obj));
    try {
      return BlobHelper.serializeToBlob(obj, hdos);
    }
    catch (IOException e) {
      throw new SerializationException(LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING.toLocalizedString(), e);
    }
  }

  /**
   * Serialize an object into a <code>byte[]</code> . If the byte array
   * provided by the wrapper is sufficient to hold the data, it is used
   * otherwise a new byte array gets created & its reference is stored in the
   * wrapper. The User Bit is also appropriately set as Serialized
   * 
   * @param wrapper
   *                Object of type BytesAndBitsForCompactor which is used to fetch
   *                the serialized data. The byte array of the wrapper is used
   *                if possible else a the new byte array containing the data is
   *                set in the wrapper.
   * @throws IllegalArgumentException
   *                 If <code>obj</code> should not be serialized
   */
  public static void fillSerializedValue(BytesAndBitsForCompactor wrapper,
                                         Object obj, byte userBits) {
    if (obj == null || obj == Token.NOT_AVAILABLE
        || Token.isInvalidOrRemoved(obj))
      throw new IllegalArgumentException(
        LocalizedStrings.EntryEvents_MUST_NOT_SERIALIZE_0_IN_THIS_CONTEXT.toLocalizedString(obj));
    try {
      HeapDataOutputStream hdos = null;
      if (wrapper.getBytes().length < 32) {
        hdos = new HeapDataOutputStream(Version.CURRENT);
      }
      else {
        hdos = new HeapDataOutputStream(wrapper.getBytes());
      }
      DataSerializer.writeObject(obj, hdos);
      // return hdos.toByteArray();
      hdos.sendTo(wrapper, userBits);
    }
    catch (IOException e) {
      RuntimeException e2 = new IllegalArgumentException(
        LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING.toLocalizedString());
      e2.initCause(e);
      throw e2;
    }
  }

  final String getShortClassName() {
    String cname = getClass().getName();
    return cname.substring(getClass().getPackage().getName().length()+1);
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append(getShortClassName());
    buf.append('@').append(Integer.toHexString(System.identityHashCode(this)));
    buf.append("[");

    buf.append("region=");
    buf.append(getRegion() != null ? getRegion().getFullPath() : null);
    buf.append(";op=");
    buf.append(getOperation());
    buf.append(";key=");
    buf.append(this.getKey());
    buf.append(";bucketId=");
    buf.append(this.getBucketId());
    buf.append(";tailKey=");
    buf.append(this.getTailKey());
    buf.append(";oldValue=");
    try {
      ArrayUtils.objectStringNonRecursive(basicGetOldValue(), buf);
    } catch (IllegalStateException ex) {
      buf.append("OFFHEAP_VALUE_FREED");
    }
    if (hasDelta()) {
      buf.append(";delta=");
      buf.append(this.delta);
    }
    buf.append(";newValue=");
    try {
      ArrayUtils.objectStringNonRecursive(basicGetNewValue(), buf);
    } catch (IllegalStateException ex) {
      buf.append("OFFHEAP_VALUE_FREED");
    }
    buf.append(";callbackArg=");
    buf.append(this.getRawCallbackArgument());
    buf.append(";originRemote=");
    buf.append(isOriginRemote());
    buf.append(";originMember=");
    buf.append(getDistributedMember());
//    if (this.partitionMessage != null) {
//      buf.append("; partitionMessage=");
//      buf.append(this.partitionMessage);
//    }
    if (this.isPossibleDuplicate()) {
      buf.append(";posDup");
    }
    if (callbacksInvoked()) { 
      buf.append(";callbacksInvoked");
    }
    if (this.versionTag != null) {
      buf.append(";version=").append(this.versionTag);
    }
    if (getContext() != null) {
      buf.append(";context=");
      buf.append(getContext());
    }
    if (this.eventID != null) {
      buf.append(";id=");
      buf.append(this.eventID);
    }
    
    if (this.deltaBytes != null) {
      buf.append(";[" + this.deltaBytes.length + " deltaBytes]");
    }
//    else {
//      buf.append(";[no deltaBytes]");
//    }
    if (this.filterInfo != null) {
      buf.append(";routing=");
      buf.append(this.filterInfo);
    }
    if (this.txState != null) {
      buf.append(";TX=").append(this.txState);
    }

    buf.append(";possibleDuplicate="+isPossibleDuplicate());
    if (this.isFromServer()) {
      buf.append(";isFromServer");
    }
    if (this.isConcurrencyConflict()) {
      buf.append(";isInConflict");
    }
    if (this.getInhibitDistribution()) {
      buf.append(";inhibitDistribution");
    }
    if (this.skipFKChecks()) {
      buf.append(";skipFKChecks");
    }
    if (this.isLoadedFromHDFS()) {
      buf.append("; loadedFromHDFS");
    }
    if (this.isPutDML) {
      buf.append("; isPutDML");
    }
    buf.append("]");
    return buf.toString();
  }
    
  public String shortToString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("EntryEventImpl@");
    buf.append(Integer.toHexString(System.identityHashCode(this)));
    buf.append("[");

    buf.append("key=");
    buf.append(this.getKey());
    buf.append(";bucketId=");
    buf.append(this.getBucketId());
    if (hasDelta()) {
      buf.append(";delta=");
      buf.append(this.delta);
    }
    buf.append(";newValue=");
    buf.append(this.newValue);
    buf.append(";op=");
    buf.append(getOperation());
    buf.append(";callbackArg=");
    buf.append(this.getRawCallbackArgument());
    buf.append("]");
    return buf.toString();
  }

  public int getDSFID() {
    return ENTRY_EVENT;
  }

  public void toData(DataOutput out) throws IOException
  {
    DataSerializer.writeObject(this.eventID, out);
    DataSerializer.writeObject(this.key, out);
    out.writeByte(this.op.ordinal);
    out.writeShort((this.eventFlags & EventFlags.FLAG_TRANSIENT_MASK)
        | this.extraEventFlags);
    DataSerializer.writeObject(this.getRawCallbackArgument(), out);

    {
      boolean isDelta = this.delta != null;
      out.writeBoolean(isDelta);
      if (isDelta) {
        DataSerializer.writeObject(this.delta, out);
      }
      else {
        Object nv = basicGetNewValue();
        boolean newValueSerialized = nv instanceof CachedDeserializable;
        if (newValueSerialized) {
          if (nv instanceof StoredObject) {
            newValueSerialized = ((StoredObject) nv).isSerialized();
          }
        }
        out.writeBoolean(newValueSerialized);
        if (newValueSerialized) {
          if (this.newValueBytes != null) {
            DataSerializer.writeByteArray(this.newValueBytes, out);
          }
          else {
            CachedDeserializable cd = (CachedDeserializable)nv;
            DataSerializer.writeObjectAsByteArray(cd.getValue(), out);
          }
        }
        else {
          DataSerializer.writeObject(nv, out);
        }
      }
    }

    {
      Object ov = basicGetOldValue();
      boolean oldValueSerialized = ov instanceof CachedDeserializable;
      if (oldValueSerialized) {
        if (ov instanceof StoredObject) {
          oldValueSerialized = ((StoredObject) ov).isSerialized();
        }
      }
      out.writeBoolean(oldValueSerialized);
      if (oldValueSerialized) {
        if (this.oldValueBytes != null) {
          DataSerializer.writeByteArray(this.oldValueBytes, out);
        }
        else {
          CachedDeserializable cd = (CachedDeserializable)ov;
          DataSerializer.writeObjectAsByteArray(cd.getValue(), out);
        }
      }
      else {
        DataSerializer.writeObject(ov, out);
      }
    }
    InternalDataSerializer.invokeToData((InternalDistributedMember)this.distributedMember, out);
    DataSerializer.writeObject(getContext(), out);
    out.writeLong(tailKey);
  }

  private static abstract class EventFlags {

    private static final short FLAG_ORIGIN_REMOTE = 0x01;
    // localInvalid: true if a null new value should be treated as a local
    // invalid.
    private static final short FLAG_LOCAL_INVALID = 0x02;
    private static final short FLAG_GENERATE_CALLBACKS = 0x04;
    private static final short FLAG_POSSIBLE_DUPLICATE = 0x08;
    private static final short FLAG_INVOKE_PR_CALLBACKS = 0x10;
    private static final short FLAG_CONCURRENCY_CONFLICT = 0x20;
    private static final short FLAG_INHIBIT_LISTENER_NOTIFICATION = 0x40;
    private static final short FLAG_CALLBACKS_INVOKED = 0x80;
    private static final short FLAG_ISCREATE = 0x100;
    private static final short FLAG_SERIALIZATION_DEFERRED = 0x200;
    private static final short FLAG_FROM_SERVER = 0x400;
    private static final short FLAG_FROM_RI_LOCAL_DESTROY = 0x800;
    private static final short FLAG_INHIBIT_DISTRIBUTION = 0x1000;
    private static final short GFXD_SKIP_DISTRIBUTION_OPS = 0x2000;
    private static final short GFXD_DISTRIBUTE_INDEX_OPS = 0x4000;
    private static final short FLAG_REDESTROYED_TOMBSTONE = (short)0x8000;

    // extra event flags; currently these need to have same values as normal
    // flags that are transient so can be reused when sending across
    private static final short FLAG_INHIBIT_ALL_NOTIFICATIONS =
      FLAG_INHIBIT_LISTENER_NOTIFICATION;
    
    private static final short FLAG_SKIP_FK_CHECKS = 0x800;

    /** mask for clearing transient flags when serializing */
    private static final short FLAG_TRANSIENT_MASK =
      ~(FLAG_CALLBACKS_INVOKED
          | FLAG_ISCREATE
          | FLAG_INHIBIT_LISTENER_NOTIFICATION
          | FLAG_SERIALIZATION_DEFERRED
          | FLAG_FROM_SERVER
          | FLAG_FROM_RI_LOCAL_DESTROY
          | FLAG_INHIBIT_DISTRIBUTION
          | GFXD_SKIP_DISTRIBUTION_OPS
          | GFXD_DISTRIBUTE_INDEX_OPS
          | FLAG_REDESTROYED_TOMBSTONE
          );
  }

  /**
   * @return null if old value is not serialized; otherwise returns a SerializedCacheValueImpl containing the old value.
   */
  public final SerializedCacheValueImpl getSerializedOldValue() {
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    final Object tmp = basicGetOldValue();
    if (tmp instanceof CachedDeserializable) {
      if (tmp instanceof StoredObject) {
        if (!((StoredObject) tmp).isSerialized()) {
          // TODO OFFHEAP can we handle offheap byte[] better?
          return null;
        }
      }
      return new SerializedCacheValueImpl(this, this.region, this.re,
          (CachedDeserializable)tmp, this.oldValueBytes);
    }
    else {
      return null;
    }
  }
  
  /**
   * Compute an estimate of the size of the new value
   * for a PR. Since PR's always store values in a cached deserializable
   * we need to compute its size as a blob.
   *
   * @return the size of serialized bytes for the new value
   */
  public final int getNewValSizeForPR() {
    int newSize = 0;
    applyDelta(false);
    Object v = basicGetNewValue();
    if (v != null) {
      try {
        newSize = CachedDeserializableFactory.calcSerializedSize(v)
          + CachedDeserializableFactory.overhead();
      } catch (IllegalArgumentException iae) {
        this.region.getCache().getLoggerI18n().warning(
            LocalizedStrings.EntryEventImpl_DATASTORE_FAILED_TO_CALCULATE_SIZE_OF_NEW_VALUE,
            iae);
        newSize = 0;
      }
    }
    return newSize;
  }

  /**
   * Compute an estimate of the size of the old value
   *
   * @return the size of serialized bytes for the old value
   */
  public final int getOldValSize() {
    int oldSize = 0;
    if (hasOldValue()) {
      try {
        oldSize = CachedDeserializableFactory.calcMemSize(basicGetOldValue());
      } catch (IllegalArgumentException iae) {
        this.region.getCache().getLoggerI18n().warning(LocalizedStrings.EntryEventImpl_DATASTORE_FAILED_TO_CALCULATE_SIZE_OF_OLD_VALUE, iae);
        oldSize = 0;
      }
    }
    return oldSize;
  }

  public final EnumListenerEvent getEventType() {
    return this.eventType;
  }

  /**
   * Sets the operation type.
   * @param eventType
   */
  public final void setEventType(EnumListenerEvent eventType) {
    this.eventType = eventType;
  }
  
  /**
   * set this to true after dispatching the event to a cache listener
   */
  public final void callbacksInvoked(boolean dispatched) {
    setEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED, dispatched);
  }
  
  /**
   * has this event been dispatched to a cache listener?
   */
  public final boolean callbacksInvoked() {
    return testEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED);
  }
  
  /**
   * set this to true to inhibit application cache listener notification
   * during event dispatching
   */
  public final void inhibitCacheListenerNotification(boolean inhibit) {
    setEventFlag(EventFlags.FLAG_INHIBIT_LISTENER_NOTIFICATION, inhibit);
  }
  
  /**
   * are events being inhibited from dispatch to application cache listeners
   * for this event?
   */
  public final boolean inhibitCacheListenerNotification() {
    return testEventFlag(EventFlags.FLAG_INHIBIT_LISTENER_NOTIFICATION);
  }

  /**
   * dispatch listener events for this event
   * @param notifyGateways pass the event on to WAN queues
   */
  final void invokeCallbacks(LocalRegion rgn,boolean skipListeners,
      boolean notifyGateways) {
    if (!callbacksInvoked()) {
      callbacksInvoked(true);
      if (this.op.isUpdate()) {
        rgn.invokePutCallbacks(EnumListenerEvent.AFTER_UPDATE, this,
            !skipListeners, notifyGateways); // gateways are notified in part2 processing
      }
      else if (this.op.isCreate()) {
        rgn.invokePutCallbacks(EnumListenerEvent.AFTER_CREATE, this,
            !skipListeners, notifyGateways);
      }
      else if (this.op.isDestroy()) {
        rgn.invokeDestroyCallbacks(EnumListenerEvent.AFTER_DESTROY,
            this, !skipListeners, notifyGateways);
      }
      else if (this.op.isInvalidate()) {
        rgn.invokeInvalidateCallbacks(EnumListenerEvent.AFTER_INVALIDATE,
            this, !skipListeners);
      }
    }
  }

  private final void setFromRILocalDestroy(boolean on) {
    setEventFlag(EventFlags.FLAG_FROM_RI_LOCAL_DESTROY, on);
  }
  
  public final boolean isFromRILocalDestroy(){
    return testEventFlag(EventFlags.FLAG_FROM_RI_LOCAL_DESTROY);
  }

  protected long tailKey = -1L;
  /**
   * Return true if this event came from a server by the client doing a get.
   * @since 5.7
   */
  public final boolean isFromServer() {
    return testEventFlag(EventFlags.FLAG_FROM_SERVER);
  }

  /**
   * Sets the fromServer flag to v.  This must be set to true if an event
   * comes from a server while the affected region entry is not locked.  Among
   * other things it causes version conflict checks to be performed to protect
   * against overwriting a newer version of the entry.
   * @since 5.7
   */
  public final void setFromServer(boolean v) {
    setEventFlag(EventFlags.FLAG_FROM_SERVER, v);
  }

  /**
   * If true, the region associated with this event had already
   * applied the operation it encapsulates when an attempt was
   * made to apply the event.
   * @return the possibleDuplicate
   */
  public final boolean isPossibleDuplicate() {
    return testEventFlag(EventFlags.FLAG_POSSIBLE_DUPLICATE);
  }

  /**
   * If the operation encapsulated by this event has already been
   * seen by the region to which it pertains, this flag should be
   * set to true. 
   * @param possibleDuplicate the possibleDuplicate to set
   */
  public final void setPossibleDuplicate(boolean possibleDuplicate) {
    setEventFlag(EventFlags.FLAG_POSSIBLE_DUPLICATE, possibleDuplicate);
  }


  /**
   * are events being inhibited from dispatch to to gateway/async queues, 
   * client queues, cache listener and cache write. If set, sending
   * notifications for the data that is read from a persistent store (HDFS) and 
   * is being reinserted in the cache is skipped.
   */
  public final boolean inhibitAllNotifications() {
    return testExtraEventFlag(EventFlags.FLAG_INHIBIT_ALL_NOTIFICATIONS);
    
  }
  
  /**
   * set this to true to inhibit notifications that are sent to gateway/async queues, 
   * client queues, cache listener and cache write. This is used to skip sending
   * notifications for the data that is read from a persistent store (HDFS) and 
   * is being reinserted in the cache 
   */
  public final void setInhibitAllNotifications(boolean inhibit) {
    setExtraEventFlag(EventFlags.FLAG_INHIBIT_ALL_NOTIFICATIONS, inhibit);
  }
  
  public final void setSkipFKChecks(boolean skip) {
    setExtraEventFlag(EventFlags.FLAG_SKIP_FK_CHECKS, skip);
  }
  
  public final boolean skipFKChecks() {
    return testExtraEventFlag(EventFlags.FLAG_SKIP_FK_CHECKS);
  }
  
  
  /**
   * sets the routing information for cache clients
   */
  public final void setLocalFilterInfo(FilterInfo info) {
    this.filterInfo = info;
  }
  
  /**
   * retrieves the routing information for cache clients in this VM
   */
  public final FilterInfo getLocalFilterInfo() {
    return this.filterInfo;
  }

  // transient flags for GemFireXD index maintenance; using the eventFlags byte
  // instead of adding new booleans and even though eventFlags will be
  // serialized it does not matter since these flags are only set and read for
  // local index maintenance

  /** force skipping of distribution (FK, global index) for index maintenance */
  public final void setSkipDistributionOps() {
    this.eventFlags |= EventFlags.GFXD_SKIP_DISTRIBUTION_OPS;
  }

  public final boolean getSkipDistributionOps() {
    return (this.eventFlags & EventFlags.GFXD_SKIP_DISTRIBUTION_OPS) != 0;
  }

  /** force distribution (FK, global index) for index maintenance */
  public final void setDistributeIndexOps() {
    this.eventFlags |= EventFlags.GFXD_DISTRIBUTE_INDEX_OPS;
  }

  public final boolean getDistributeIndexOps() {
    return (this.eventFlags & EventFlags.GFXD_DISTRIBUTE_INDEX_OPS) != 0;
  }

  // end transient flags for GemFireXD index maintenance

  public final LocalRegion getLocalRegion() {
    return this.region;
  }

  /**
   * This method returns the delta bytes used in Delta Propagation feature.
   * <B>For internal delta, see getRawNewValue().</B>
   * 
   * @return delta bytes
   */
  public final byte[] getDeltaBytes() {
    return deltaBytes;
  }

  /**
   * This method sets the delta bytes used in Delta Propagation feature. <B>For
   * internal delta, see setNewValue().</B>
   * 
   * @param deltaBytes
   */
  public final void setDeltaBytes(byte[] deltaBytes) {
    this.deltaBytes = deltaBytes;
  }

  // TODO (ashetkar) Can this.op.isCreate() be used instead?
  public final boolean isCreate() {
    return testEventFlag(EventFlags.FLAG_ISCREATE);
  }

  /**
   * this is used to distinguish an event that merely has Operation.CREATE
   * from one that originated from Region.create() for delta processing
   * purposes.
   */
  public final EntryEventImpl setCreate(boolean isCreate) {
    setEventFlag(EventFlags.FLAG_ISCREATE, isCreate);
    return this;
  }

  public final void setContextObject(Object ctx) {
    this.contextObj = ctx;
  }

  public final Object getContextObject() {
    return this.contextObj;
  }

  public final void setBufferedMemoryTracker(UMMMemoryTracker memoryTracker) {
    this.memoryTracker = memoryTracker;
  }

  public final UMMMemoryTracker getMemoryTracker(){
    return this.memoryTracker;
  }

  public final void setEntryLastModified(long v) {
    this.entryLastModified = v;
  }

  public final long getEntryLastModified() {
    return this.entryLastModified;
  }

  public final void setHasOldRegionEntry(boolean v) {
    this.hasOldRegionEntry = v;
  }

  public final boolean getHasOldRegionEntry() {
    return this.hasOldRegionEntry;
  }

  /**
   * Get the {@link LockingPolicy} to be used for the current operation.
   */
  public final LockingPolicy getLockingPolicy() {
    return this.lockPolicy;
  }

  /**
   * Set the {@link LockingPolicy} to be used for the current operation.
   */
  public final void setLockingPolicy(final LockingPolicy policy) {
    this.lockPolicy = policy;
  }

  /**
   * establish the old value in this event as the current cache value,
   * whether in memory or on disk
   */
  public final void setOldValueForQueryProcessing() {
    RegionEntry reentry = this.region.entries.getEntry(this.getKey());
    if (reentry != null) {
      @Retained Object v = reentry.getValueOffHeapOrDiskWithoutFaultIn(this.region);
      if ( !(v instanceof Token) ) {
        // v has already been retained.
        basicSetOldValue(v);
        // this event now owns the retention of v.
      }
    }
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  /**
   * @param versionTag
   *          the versionTag to set
   */
  public final void setVersionTag(VersionTag versionTag) {
    this.versionTag = versionTag;
  }

  /**
   * @return the concurrency versioning tag for this event, if any
   */
  public final VersionTag getVersionTag() {
    return this.versionTag;
  }

  /**
   * this method joins together version tag timestamps and the "lastModified"
   * timestamps generated and stored in entries.  If a change does not already
   * carry a lastModified timestamp 
   * @param suggestedTime
   * @return the timestamp to store in the entry
   */
  public final long getEventTime(long suggestedTime) {
    return getEventTime(suggestedTime, null);
  }

  /**
   * this method joins together version tag timestamps and the "lastModified"
   * timestamps generated and stored in entries.  If a change does not already
   * carry a lastModified timestamp 
   * @param suggestedTime
   * @param region
   * @return the timestamp to store in the entry
   */
  public final long getEventTime(long suggestedTime, LocalRegion region) {
    long result = suggestedTime;
    if (this.versionTag != null) {
      if (suggestedTime != 0) {
        this.versionTag.setVersionTimeStamp(suggestedTime);
      } else {
        result = this.versionTag.getVersionTimeStamp();
      }
    }
    if (result <= 0) {
      result = this.entryLastModified;
    }
    if (result <= 0) {
      if (region == null) {
        region = this.region;
      }
      if (region != null) {
        result = region.cacheTimeMillis();
      } else {
        result = System.currentTimeMillis();
      }
    }
    return result;
  }

  public static final class SerializedCacheValueImpl
    implements SerializedCacheValue, CachedDeserializable, Sendable
  {
    private final EntryEventImpl event;
    @Unretained private final CachedDeserializable cd;
    private final Region r;
    private final RegionEntry re;
    private final byte[] serializedValue;
    
    SerializedCacheValueImpl(EntryEventImpl event, Region r, RegionEntry re, @Unretained CachedDeserializable cd, byte[] serializedBytes) {
      if (cd instanceof Chunk) {
        this.event = event;
      } else {
        this.event = null;
      }
      this.r = r;
      this.re = re;
      this.cd = cd;
      this.serializedValue = serializedBytes;
    }

    public byte[] getSerializedValue() {
      if(this.serializedValue != null){
        return this.serializedValue;
      }
      return getCd().getSerializedValue();
    }
    
    private CachedDeserializable getCd() {
      if (this.event != null && !this.event.offHeapOk) {
        throw new IllegalStateException("Attempt to access off heap value after the EntryEvent was released.");
      }
      return this.cd;
    }
    
    public Object getDeserializedValue() {
      return getDeserializedValue(this.r, this.re);
    }
    public Object getDeserializedForReading() {
      return OffHeapHelper.getHeapForm(getCd().getDeserializedForReading());
    }
    public Object getDeserializedWritableCopy(Region rgn, RegionEntry entry) {
      return OffHeapHelper.getHeapForm(getCd().getDeserializedWritableCopy(rgn, entry));
    }

    public Object getDeserializedValue(Region rgn, RegionEntry reentry) {
      return OffHeapHelper.getHeapForm(getCd().getDeserializedValue(rgn, reentry));
    }
    public Object getValue() {
      if(this.serializedValue != null){
        return this.serializedValue;
      }
      return getCd().getValue();
    }
    public void writeValueAsByteArray(DataOutput out) throws IOException {
      if (this.serializedValue != null) {
        DataSerializer.writeByteArray(this.serializedValue, out);
      } else {
        getCd().writeValueAsByteArray(out);
      }
    }
    public void fillSerializedValue(BytesAndBitsForCompactor wrapper, byte userBits) {
      if (this.serializedValue != null) {
        wrapper.setData(this.serializedValue, userBits, this.serializedValue.length, 
                        false /* Not Reusable as it refers to underlying value */);
      } else {
        getCd().fillSerializedValue(wrapper, userBits);
      }
    }
    public int getValueSizeInBytes() {
      return getCd().getValueSizeInBytes();
    }
    public int getSizeInBytes() {
      return getCd().getSizeInBytes();
    }

    public String getStringForm() {
      return getCd().getStringForm();
    }

    @Override
    public void sendTo(DataOutput out) throws IOException {
      DataSerializer.writeObject(getCd(), out);
    }
  }
//////////////////////////////////////////////////////////////////////////////////////////
  
  public final void setTailKey(long tailKey) {
    this.tailKey = tailKey;
  }

  public final long getTailKey() {
    return this.tailKey;
  }

  private Thread invokeCallbacksThread;
  private long currentOpLogKeyId = -1;

  /**
   * Mark this event as having its callbacks invoked by the current thread.
   * Note this is done just before the actual invocation of the callbacks.
   */
  public final void setCallbacksInvokedByCurrentThread() {
    this.invokeCallbacksThread = Thread.currentThread();
  }

  /**
   * Return true if this event was marked as having its callbacks invoked
   * by the current thread.
   */
  public final boolean getCallbacksInvokedByCurrentThread() {
    if (this.invokeCallbacksThread == null) return false;
    return Thread.currentThread().equals(this.invokeCallbacksThread);
  }

  public final void setCurrentOpLogKeyId(long val) {
    this.currentOpLogKeyId = val;
  }

  public final long getCurrentOpLogKeyId() {
    return this.currentOpLogKeyId;
  }

  /**
   * Returns whether this event is on the PDX type region.
   * @return whether this event is on the PDX type region
   */
  public final boolean isOnPdxTypeRegion() {
    return PeerTypeRegistration.REGION_FULL_PATH.equals(this.region
        .getFullPath());
  }

  /**
   * returns true if it is okay to process this event even though it has
   * a null version
   */
  public final boolean noVersionReceivedFromServer() {
    return versionTag == null
      && region.concurrencyChecksEnabled
      && region.getServerProxy() != null
      && !op.isLocal()
      && !isOriginRemote()
      ;
  }

  /**
   * returns a copy of this event with the additional fields for WAN conflict
   * resolution
   */
  public final TimestampedEntryEvent getTimestampedEvent(final int newDSID,
      final int oldDSID, final long newTimestamp, final long oldTimestamp) {
    return new TimestampedEntryEventImpl(this, newDSID, oldDSID, newTimestamp,
        oldTimestamp);
  }

  private final void setSerializationDeferred(boolean serializationDeferred) {
    setEventFlag(EventFlags.FLAG_SERIALIZATION_DEFERRED, serializationDeferred);
  }

  private final boolean isSerializationDeferred() {
    return testEventFlag(EventFlags.FLAG_SERIALIZATION_DEFERRED);
  }

  public final boolean isSingleHop() {
    return (this.causedByMessage != null && this.causedByMessage instanceof RemoteOperationMessage);
  }

  public final boolean isSingleHopPutOp() {
    return (this.causedByMessage != null && this.causedByMessage instanceof RemotePutMessage);
  }

  /**
   * True if it is ok to use old/new values that are stored off heap.
   * False if an exception should be thrown if an attempt is made to access old/new offheap values.
   */
  private transient boolean offHeapOk = true;
 
  @Override
  @Released({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE})
  public final void release() {
    // noop if already freed or values can not be off-heap
    if (!this.offHeapOk) return;
    // Note that this method does not set the old/new values to null but
    // leaves them set to the off-heap value so that future calls to getOld/NewValue
    // will fail with an exception.
//    LocalRegion lr = getLocalRegion();
//    if (lr != null) {
//      if (lr.isCacheClosing()) {
//        // to fix races during closing and recreating cache (see bug 47883) don't bother
//        // trying to decrement reference counts if we are closing the cache.
//        // TODO OFFHEAP: this will cause problems once offheap lives longer than a cache.
//        this.offHeapOk = false;
//        return;
//      }
//    }
    Object ov = basicGetOldValue();
    Object nv = basicGetNewValue();
    this.offHeapOk = false;
    
    if (ov instanceof Chunk) {
      //this.region.getCache().getLogger().info("DEBUG freeing ref to old value on " + System.identityHashCode(ov));
      if (SimpleMemoryAllocatorImpl.trackReferenceCounts()) {
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(new OldValueOwner());
        ((Chunk) ov).release();
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
      } else {
        ((Chunk) ov).release();
      }
    }
    OffHeapHelper.releaseAndTrackOwner(nv, this);
  }

  /**
   * Make sure that this event will never own an off-heap value.
   * Once this is called on an event it does not need to have release called.
   */
  public final void disallowOffHeapValues() {
    if (this.newValue instanceof Chunk || this.oldValue instanceof Chunk) {
      throw new IllegalStateException("This event does not support off-heap values");
    }
    this.offHeapOk = false;
  }
  
  /**
   * This copies the off-heap new and/or old value to the heap.
   * As a result the current off-heap new/old will be released.
   * @throws IllegalStateException if called with an event for gfxd data.
   */
  @Released({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE})
  public final void copyOffHeapToHeap() {
    Object ov = basicGetOldValue();
    if (ov instanceof Chunk) {
      if (SimpleMemoryAllocatorImpl.trackReferenceCounts()) {
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(new OldValueOwner());
        this.oldValue = OffHeapHelper.copyAndReleaseIfNeeded(ov);
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
      } else {
        this.oldValue = OffHeapHelper.copyAndReleaseIfNeeded(ov);
      }
    }
    Object nv = basicGetNewValue();
    if (nv instanceof Chunk) {
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(this);
      this.newValue = OffHeapHelper.copyAndReleaseIfNeeded(nv);
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
    }
    if (this.newValue instanceof Chunk || this.oldValue instanceof Chunk) {
      throw new IllegalStateException("event's old/new value still off-heap after calling copyOffHeapToHeap");
    }
    this.offHeapOk = false;
  }

  @Released({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE})
  public final void reset(boolean keepLastModifiedTime) {
    if (this.offHeapOk) {
      release();
    }
    this.key = null;
    this.bucketId = UNKNOWN_BUCKET;
    this.region = null;
    this.eventID = null;
    this.newValue = null;
    this.newValueBytes = null;
    this.re = null;
    this.delta = null;
    this.oldValue = null;
    this.oldValueBytes = null;
    this.eventFlags = 0;
    this.op = null;
    this.distributedMember = null;
    this.filterInfo = null;
    this.callbackArg = null;
    this.context = null;
    this.deltaBytes = null;
    this.txState = null;
    this.tailKey = -1L;
    this.versionTag = null;
    if (!keepLastModifiedTime) {
      this.entryLastModified = -1L;
    }
    this.causedByMessage = null;
    this.contextObj = null;
    this.currentOpLogKeyId = -1L;
    this.eventType = null;
    this.lockPolicy = null;
    this.hasOldRegionEntry = false;
    this.newValueBucketSize = 0;
    this.putAllOp = null;
    this.extraEventFlags = 0;
    this.offHeapOk = true;
    this.fetchFromHDFS = true;
    this.isPutDML = false;
    this.loadedFromHDFS = false;
  }

  public final boolean isOldValueOffHeap() {
    return this.oldValue instanceof Chunk;
  }

  public final boolean isFetchFromHDFS() {
    return fetchFromHDFS;
  }

  public final void setFetchFromHDFS(boolean fetchFromHDFS) {
    this.fetchFromHDFS = fetchFromHDFS;
  }

  public final boolean isPutDML() {
    return this.isPutDML;
  }

  public final void setPutDML(boolean val) {
    this.isPutDML = val;
  }

  public final boolean isLoadedFromHDFS() {
    return loadedFromHDFS;
  }

  public final void setLoadedFromHDFS(boolean loadedFromHDFS) {
    this.loadedFromHDFS = loadedFromHDFS;
  }
}
