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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.persistence.PersistentReplicatesOfflineException;
import com.gemstone.gemfire.cache.query.internal.CqService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.ha.ThreadIdentifier;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.partitioned.PutAllPRMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.UMMMemoryTracker;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TObjectIntHashMap;

/**
 * Handles distribution of a Region.putall operation.
 * 
 * @author Darrel Schneider
 * @since 5.0
 */
public final class DistributedPutAllOperation extends AbstractUpdateOperation {

  protected final PutAllEntryData[] putAllData;

  public int putAllDataSize;

  protected final TXStateInterface txState;

  protected boolean isBridgeOp = false;

  Object[] callbackArgs = null;

  static final byte USED_FAKE_EVENT_ID = 0x01;
  static final byte NOTIFY_ONLY = 0x02;
  static final byte FILTER_ROUTING = 0x04;
  static final byte VERSION_TAG = 0x08;
  static final byte POSDUP = 0x10;
  static final byte PERSISTENT_TAG = 0x20;
  static final byte HAS_CALLBACKARG = 0x40;

  // flags for CachedDeserializable; additional flags can be combined
  // with these if required
  static final byte IS_CACHED_DESER = 0x1;
  static final byte IS_OBJECT = 0x2;

//  private boolean containsCreate = false;

  public DistributedPutAllOperation(final LocalRegion r,
      final EntryEventImpl event, int size, boolean isBridgeOp) {
    super(event, event.getEventTime(0L));
    this.putAllData = new PutAllEntryData[size];
    this.putAllDataSize = 0;
    this.isBridgeOp = isBridgeOp;
    this.txState = event.getTXState(r);
  }

  public DistributedPutAllOperation(final LocalRegion r,
      final EntryEventImpl event, int size, boolean isBridgeOp,
      PutAllEntryData[] paed) {
    this(r, event, size, isBridgeOp);
    boolean isPosDup = event.isPossibleDuplicate();
    this.callbackArgs = new Object[size];
    for (int i = 0; i < size; ++i) {
      this.callbackArgs[i] = paed[i].callbackArg;
      if (isPosDup) {
        paed[i].setPossibleDuplicate(true);
      }
    }
  }

  /**
   * return if the operation is bridge operation
   */
  public boolean isBridgeOperation()
  {
    return this.isBridgeOp;
  }
  
  public PutAllEntryData[] getPutAllEntryData() {
	  return putAllData;
  }
  
  /**
   * Add an entry that this putall operation should distribute.
   */
  public void addEntry(EntryEventImpl ev)
  {
    final Object callbackArg = this.callbackArgs != null
        ? ev.getRawCallbackArgument() : null;
    this.putAllData[this.putAllDataSize] = new PutAllEntryData(ev, callbackArg);
    this.putAllDataSize++;

    // cachedEvents.add(ev);
  }

  /**
   * Add an entry that this putall operation should distribute. This method is
   * for a special case: the callback will be called after this in
   * hasSeenEvent() case, so we should change the status beforehand
   */
  public void addEntry(EntryEventImpl ev, boolean newCallbackInvoked)
  {
    final Object callbackArg = this.callbackArgs != null
        ? ev.getRawCallbackArgument() : null;
    this.putAllData[this.putAllDataSize] = new PutAllEntryData(ev, callbackArg);
    this.putAllData[this.putAllDataSize].setCallbacksInvoked(newCallbackInvoked);
    this.putAllDataSize++;

    // cachedEvents.add(ev);
  }

  /**
   * Add an entry for PR bucket's msg.
   * 
   * @param ev
   *          event to be added
   * @param bucketId
   *          message is for this bucket
   */
  public void addEntry(EntryEventImpl ev, Integer bucketId)
  {
    final Object callbackArg = this.callbackArgs != null
        ? ev.getRawCallbackArgument() : null;
    this.putAllData[this.putAllDataSize] = new PutAllEntryData(ev, callbackArg);
    this.putAllData[this.putAllDataSize].setBucketId(bucketId);
    this.putAllDataSize++;

    // cachedEvents.add(ev);
  }

  /**
   * set using fake thread id
   *
   * @param status 
   *            whether the entry is using fake event id
   */
  public void setUseFakeEventId(boolean status) {
    if (status) {
      for (int i = 0; i < putAllDataSize; i++) {
        putAllData[i].flags |= USED_FAKE_EVENT_ID;
      }
    }
    else {
      for (int i = 0; i < putAllDataSize; i++) {
        putAllData[i].flags &= ~USED_FAKE_EVENT_ID;
      }
    }
  }

  /**
   * @param index
   * @return the routing information for the given entry
   */
  public FilterRoutingInfo getFilterRoutingInfo(int index) {
    return this.putAllData[index].filterRouting;
  }

  /**
   * In the originating cache, this returns an iterator on the list
   * of events caused by the putAll operation.  This is cached for
   * listener notification purposes.  The iterator is guaranteed to return
   * events in the order they are present in putAllData[]
   */
  public Iterator eventIterator() {
    return new Iterator() {
      int position = 0;
      public boolean hasNext() {
        return DistributedPutAllOperation.this.putAllDataSize > position;
      };
      public Object next() {
        EntryEventImpl ev = getEventForPosition(position);
        position++;
        return ev;
      };
      public void remove() {
        throw new UnsupportedOperationException();
      };
    };
  }
  
  public void freeOffHeapResources() {
    // I do not use eventIterator here because it forces the lazy creation of EntryEventImpl by calling getEventForPosition.
    for (int i=0; i < this.putAllDataSize; i++) {
      PutAllEntryData entry = this.putAllData[i];
      if (entry != null && entry.event != null) {
        entry.event.release();
      }
    }
  }
  
  
  public EntryEventImpl getEventForPosition(int position) {
    PutAllEntryData entry = this.putAllData[position];
    if (entry == null) {
      return null;
    }
    if (entry.event != null) {
      return entry.event;
    }
    EntryEventImpl ev = EntryEventImpl.create(
        (LocalRegion)this.event.getRegion(),
        entry.getOp(),
        entry.getKey(), null/* value */, entry.getCallbackArg(),
        false /* originRemote */,
        this.event.getDistributedMember(),
        this.event.isGenerateCallbacks(),
        entry.getEventID());
    boolean returnedEv = false;
    try {
    ev.setPossibleDuplicate(entry.isPossibleDuplicate());
    if (entry.versionTag != null) {
      VersionSource id = entry.versionTag.getMemberID();
      if (id!= null) {
        entry.versionTag.setMemberID(
            ev.getRegion().getVersionVector().getCanonicalId(id));
      }
      ev.setVersionTag(entry.versionTag);
    }
      
    entry.event = ev;
    returnedEv = true;
    if (entry.getValue() == null && ev.getRegion().getAttributes().getDataPolicy() == DataPolicy.NORMAL) {
      ev.setLocalInvalid(true);
    }
    ev.setNewValue(entry.getValue());
    ev.setOldValue(entry.getOldValue());
    if (CqService.isRunning() && !entry.getOp().isCreate() && !ev.hasOldValue()) {
      ev.setOldValueForQueryProcessing();
    }
    ev.setInvokePRCallbacks(!entry.isNotifyOnly());
    if (getEvent().getContext() != null) {
      ev.setContext(getEvent().getContext());
    }
    ev.callbacksInvoked(entry.isCallbacksInvoked());
    ev.setTailKey(entry.getTailKey());
    return ev;
    } finally {
      if (!returnedEv) {
        ev.release();
      }
    }
  }

  public final EntryEventImpl getBaseEvent() {
    return (EntryEventImpl)this.event;
  }

  public void setCallbackArgs(Object[] args) {
    this.callbackArgs = args;   
  }

  public Object getCallbackArg(int index) {
    return (this.callbackArgs != null ) ? this.callbackArgs[index]:null;
  }

  /**
   * Data that represents a single entry being putall'd.
   */
  public static final class PutAllEntryData {

    final Object key;

    final Object value;

    private final Object oldValue;

    private final Operation op;

    private EventID eventID;
    
    transient EntryEventImpl event;
    
    private Integer bucketId = Integer.valueOf(-1);

    public FilterRoutingInfo filterRouting;

    final private Object callbackArg;

    // One flags byte for all booleans
    byte flags = 0x00;

    // TODO: Yogesh, this should be intialized and sent on wire only when
    // parallel wan is enabled
    private long tailKey = 0L;

    public VersionTag versionTag;

    // following two are not serialized so they can coincide with
    // serialized ones like VERSION_TAG_BIT
    private static final byte INHIBIT_DIST_BIT = 0x04;
    private static final byte CALLBACKS_INVOKED_BIT = 0x08;
    private static final byte CLEAR_TRANSIENT =
        ~(INHIBIT_DIST_BIT | CALLBACKS_INVOKED_BIT);

    /**
     * Constructor to use when preparing to send putall data out
     */
    public PutAllEntryData(EntryEventImpl event, Object callbackArg) {

      this.key = event.getKey();
      this.value = event.getRawNewValueAsHeapObject();
      Object oldValue = event.getRawOldValueAsHeapObject();

      if (oldValue == Token.NOT_AVAILABLE || Token.isRemoved(oldValue)) {
        this.oldValue = null;
      } else {
        this.oldValue = oldValue;
      }

      this.op = event.getOperation();
      this.eventID = event.getEventId();
      this.callbackArg = callbackArg;
      this.tailKey = event.getTailKey();
      this.versionTag = event.getVersionTag();

      setNotifyOnly(!event.getInvokePRCallbacks());
      setCallbacksInvoked(event.callbacksInvoked());
      setPossibleDuplicate(event.isPossibleDuplicate());
      setInhibitDistribution(event.getInhibitDistribution());
    }

    /**
     * Constructor to use when receiving a putall from someone else
     */
    public PutAllEntryData(DataInput in, EventID baseEventID, int idx,
        Version version, ByteArrayDataInput bytesIn) throws IOException,
        ClassNotFoundException {
      this.key = DataSerializer.readObject(in);
      byte flgs = in.readByte();
      if ((flgs & IS_OBJECT) != 0) {
        this.value = DataSerializer.readObject(in);
      }
      else {
        byte[] bb = DataSerializer.readByteArray(in);
        if ((flgs & IS_CACHED_DESER) != 0) {
          if (CachedDeserializableFactory.preferObject()) {
            this.value = EntryEventImpl.deserialize(bb, version, bytesIn);
          }
          else {
            this.value = CachedDeserializableFactory.create(bb);
          }
        }
        else {
          this.value = bb;
        }
      }
      this.oldValue = null;
      this.op = Operation.fromOrdinal(in.readByte());
      this.flags = in.readByte();
      if ((this.flags & FILTER_ROUTING) != 0) {
        this.filterRouting = (FilterRoutingInfo)DataSerializer.readObject(in);
      }
      if ((this.flags & VERSION_TAG) != 0) {
        boolean persistentTag = (this.flags & PERSISTENT_TAG) != 0;
        this.versionTag = VersionTag.create(persistentTag, in);
      }
      if (isUsedFakeEventId()) {
        this.eventID = new EventID();
        InternalDataSerializer.invokeFromData(this.eventID, in);
      }
      else {
        this.eventID = new EventID(baseEventID, idx);
      }
      if ((this.flags & HAS_CALLBACKARG) != 0) {
        this.callbackArg = DataSerializer.readObject(in);
      }
      else {
        this.callbackArg = null;
      }
      this.tailKey = InternalDataSerializer.readSignedVL(in);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(50);
      sb.append("(op=").append(getOp()).append(',').append(getKey()).append(",").
        append(getValue()).append(",").
        append(getOldValue());
      if (this.bucketId > 0) {
        sb.append(", b").append(this.bucketId);
      }
      if (versionTag != null) {
        sb.append(",v").append(versionTag.getEntryVersion())
            .append(",rv=").append(versionTag.getRegionVersion());
      }
      if (filterRouting != null) {
        sb.append(", ").append(filterRouting);
      }
      if (callbackArg != null) {
        sb.append(",callbackArg=").append(callbackArg);
      }
      sb.append(")");
      return sb.toString();
    }

    void setSender(InternalDistributedMember sender) {
      if (this.versionTag != null) {
        this.versionTag.replaceNullIDs(sender);
      }
    }

    /**
     * Used to serialize this instances data to <code>out</code>.
     * Post 7.1, if changes are made to this method make sure that it is backwards
     * compatible by creating toDataPreXX methods. Also make sure that the callers
     * to this method are backwards compatible by creating toDataPreXX methods for
     * them even if they are not changed. <br>
     * Callers for this method are: <br>
     * {@link PutAllMessage#toData(DataOutput)} <br>
     * {@link PutAllPRMessage#toData(DataOutput)} <br>
     * {@link RemotePutAllMessage#toData(DataOutput)} <br>
     */
    public final void toData(final DataOutput out,
        final boolean requiresRegionContext) throws IOException {
      Object key = this.key;
      final Object v = this.value;
      if (requiresRegionContext && v != null && !(v
          instanceof com.gemstone.gemfire.internal.cache.delta.Delta)) {
        key = ((KeyWithRegionContext)key).beforeSerializationWithValue(false);
      }

      DataSerializer.writeObject(key, out);

      if (v instanceof byte[] || v == null) {
        out.writeByte(0);
        DataSerializer.writeByteArray((byte[])v, out);
      } else if (CachedDeserializableFactory.preferObject()) {
        out.writeByte(IS_OBJECT);
        DataSerializer.writeObject(v, out);
      }

      else if (v instanceof CachedDeserializable) {
        CachedDeserializable cd = (CachedDeserializable)v;
        out.writeByte(IS_CACHED_DESER);
        DataSerializer.writeByteArray(cd.getSerializedValue(), out);
      }
      else {
        out.writeByte(IS_CACHED_DESER);
        DataSerializer.writeObjectAsByteArray(v, out);
      }
      out.writeByte(this.op.ordinal);
      byte bits = (byte)(this.flags & CLEAR_TRANSIENT);

      if (this.filterRouting != null) bits |= FILTER_ROUTING;
      if (this.versionTag != null) {
        bits |= VERSION_TAG;
        if (this.versionTag instanceof DiskVersionTag) {
          bits |= PERSISTENT_TAG;
        }
      }
      if (this.callbackArg != null) bits |= HAS_CALLBACKARG;

      //TODO: Yogesh, this should be conditional,
      // make sure that we sent it on wire only 
      // when parallel wan is enabled
      // bits |= HAS_TAILKEY;

      out.writeByte(bits);

      if (this.filterRouting != null) {
        DataSerializer.writeObject(this.filterRouting, out);
      }
      if (this.versionTag != null) {
        InternalDataSerializer.invokeToData(this.versionTag, out);
      }
      if (isUsedFakeEventId()) {
        // fake event id should be serialized 
        InternalDataSerializer.invokeToData(this.eventID, out);
      }
      if (this.callbackArg != null) {
        DataSerializer.writeObject(this.callbackArg, out);
      }
      InternalDataSerializer.writeSignedVL(this.tailKey, out);
    }

    /**
     * Returns the key
     */
    public Object getKey()
    {
      return this.key;
    }

    /**
     * Returns the value
     */
    public Object getValue()
    {
      return this.value;
    }
    
    /**
     * Returns the old value
     */
    public Object getOldValue()
    {
      return this.oldValue;
    }

    public long getTailKey() {
      return this.tailKey;
    }

    public void setTailKey(long key) {
      this.tailKey = key;
    }

    /**
     * Returns the operation
     */
    public Operation getOp()
    {
      return this.op;
    }

    public EventID getEventID()
    {
      return this.eventID;
    }
    
    /**
     * Returns the callback arg
     */
    public Object getCallbackArg()
    {
      return this.callbackArg;
    }
    /**
     * change event id for the entry
     *
     * @param eventId 
     *               new event id
     */
    public void setEventId(EventID eventId) {
      this.eventID = eventId;
    }
    
    /**
     * change bucket id for the entry
     *
     * @param bucketId 
     *                new bucket id
     */
    public void setBucketId(Integer bucketId) {
      this.bucketId = bucketId;
    }

    /**
     * get bucket id for the entry
     *
     * @return bucket id
     */
    public Integer getBucketId() {
      return this.bucketId;
    }

    /**
     * change event id into fake event id
     * The algorithm is to change the threadid into 
     * bucketid*MAX_THREAD_PER_CLIENT+oldthreadid. So from the log, we can
     * derive the original thread id.
     *
     * @return wether current event id is fake or not 
     *                new bucket id
     */
    public boolean setFakeEventID() {
      if (bucketId.intValue() < 0) {
        return false;
      }

      if (!isUsedFakeEventId()) {
        // assign a fake big thread id. bucket id starts from 0. In order to distinguish
        // with other read thread, let bucket id starts from 1 in fake thread id
        long threadId = ThreadIdentifier.createFakeThreadIDForPutAll(bucketId.intValue(), eventID.getThreadID());
        this.eventID = new EventID(eventID.getMembershipID(), threadId, eventID.getSequenceID());
        this.setUsedFakeEventId(true);
      }
      return true;
    }

    boolean isNotifyOnly() {
      return (this.flags & NOTIFY_ONLY) != 0;
    }

    void setNotifyOnly(boolean notifyOnly) {
      if (notifyOnly) {
        flags |= NOTIFY_ONLY;
      } else {
        flags &= ~(NOTIFY_ONLY);
      }
    }

    boolean isUsedFakeEventId() {
      return (this.flags & USED_FAKE_EVENT_ID) != 0;
    }

    void setUsedFakeEventId(boolean usedFakeEventId) {
      if (usedFakeEventId) {
        flags |= USED_FAKE_EVENT_ID;
      } else {
        flags &= ~(USED_FAKE_EVENT_ID);
      }
    }

    boolean isPossibleDuplicate() {
      return (this.flags & POSDUP) != 0;
    }

    void setPossibleDuplicate(boolean possibleDuplicate) {
      if (possibleDuplicate) {
        flags |= POSDUP;
      }
      else {
        flags &= ~(POSDUP);
      }
    }

    public boolean isInhibitDistribution() {
      return (flags & INHIBIT_DIST_BIT) != 0;
    }

    public void setInhibitDistribution(boolean inhibitDistribution) {
      if (inhibitDistribution) {
        flags |= INHIBIT_DIST_BIT;
      } else {
        flags &= ~(INHIBIT_DIST_BIT);
      }
    }

    public boolean isCallbacksInvoked() {
      return (flags & CALLBACKS_INVOKED_BIT) != 0;
    }

    public void setCallbacksInvoked(boolean callbacksInvoked) {
      if (callbacksInvoked) {
        flags |= CALLBACKS_INVOKED_BIT;
      } else {
        flags &= ~(CALLBACKS_INVOKED_BIT);
      }
    }
  }

  public static final class EntryVersionsList extends ArrayList<VersionTag>
      implements DataSerializableFixedID, Externalizable {

    public static final boolean DEBUG = Boolean
        .getBoolean("gemfire.InitialImageVersionedObjectList.DEBUG");

    public EntryVersionsList () {
      // Do nothing
    }

    public EntryVersionsList (int size) {
      super(size);
    }

    public static EntryVersionsList create(DataInput in)
        throws IOException, ClassNotFoundException {
      EntryVersionsList newList = new EntryVersionsList();
      InternalDataSerializer.invokeFromData(newList, in);
      return newList;
    }

    private boolean extractVersion(PutAllEntryData entry) {

      VersionTag versionTag = entry.versionTag;
      // version tag can be null if only keys are sent in InitialImage.
      if (versionTag != null) { 
        add(versionTag);
        // Add entry without version tag in entries array.
        entry.versionTag = null;
        return true;
      }

      return false;
    }

    private VersionTag<VersionSource> getVersionTag(int index) {
      VersionTag tag = null;
      if (this.size() > 0) {
        tag = get(index);
      }
      return tag;
    }

    /**
     * replace null membership IDs in version tags with the given member ID.
     * VersionTags received from a server may have null IDs because they were
     * operations  performed by that server.  We transmit them as nulls to cut
     * costs, but have to do the swap on the receiving end (in the client)
     * @param sender
     */
    public void replaceNullIDs(DistributedMember sender) {
      for (VersionTag versionTag: this) {
        if (versionTag != null) {
          versionTag.replaceNullIDs((InternalDistributedMember) sender);
        }
      }
    }

    @Override
    public int getDSFID() {
      return DataSerializableFixedID.PUTALL_VERSIONS_LIST;
    }

    static final byte FLAG_NULL_TAG = 0;
    static final byte FLAG_FULL_TAG = 1;
    static final byte FLAG_TAG_WITH_NEW_ID = 2;
    static final byte FLAG_TAG_WITH_NUMBER_ID = 3;

    @Override
    public void toData(DataOutput out) throws IOException {
      LogWriterI18n log = null;
      int flags = 0;
      boolean hasTags = false;

      if (this.size() > 0) {
        flags |= 0x04;
        hasTags = true;
        for (VersionTag tag : this) {
          if (tag != null) {
            if (tag instanceof DiskVersionTag) {
              flags |= 0x20;
            }
            break;
          }
        }
      }

      if (DEBUG) {
        if (log == null) {
          log = InternalDistributedSystem.getLoggerI18n();
        }
        log.info(LocalizedStrings.DEBUG, "serializing " + this
            + " with flags 0x" + Integer.toHexString(flags));
      }

      out.writeByte(flags);

      if (hasTags) {
        InternalDataSerializer.writeUnsignedVL(this.size(), out);
        TObjectIntHashMap ids = new TObjectIntHashMap(this.size());
        int idCount = 0;
        for (VersionTag tag : this) {
          if (tag == null) {
            out.writeByte(FLAG_NULL_TAG);
          }
          else {
            VersionSource id = tag.getMemberID();
            if (id == null) {
              out.writeByte(FLAG_FULL_TAG);
              InternalDataSerializer.invokeToData(tag, out);
            }
            else {
              int idNumber = ids.get(id);
              if (idNumber == 0) {
                out.writeByte(FLAG_TAG_WITH_NEW_ID);
                idNumber = ++idCount;
                ids.put(id, idNumber);
                InternalDataSerializer.invokeToData(tag, out);
              }
              else {
                out.writeByte(FLAG_TAG_WITH_NUMBER_ID);
                tag.toData(out, false);
                tag.setMemberID(id);
                InternalDataSerializer.writeUnsignedVL(idNumber - 1, out);
              }
            }
          }
        }
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      LogWriterI18n log = null;
      if (DEBUG) {
        log = InternalDistributedSystem.getLoggerI18n();
      }
      int flags = in.readByte();
      boolean hasTags = (flags & 0x04) == 0x04;
      boolean persistent= (flags & 0x20) == 0x20;

      if (DEBUG) {
        log.info(LocalizedStrings.DEBUG,
            "deserializing a InitialImageVersionedObjectList with flags 0x"
                + Integer.toHexString(flags));
      }

      if (hasTags) {
        int size = (int)InternalDataSerializer.readUnsignedVL(in);
        if (DEBUG) {
          log.info(LocalizedStrings.DEBUG, "reading " + size + " version tags");
        }
        List<VersionSource> ids = new ArrayList<VersionSource>(size);
        for (int i=0; i<size; i++) {
          byte entryType = in.readByte();
          switch (entryType) {
          case FLAG_NULL_TAG:
            add(null);
            break;
          case FLAG_FULL_TAG:
            add(VersionTag.create(persistent, in));
            break;
          case FLAG_TAG_WITH_NEW_ID:
            VersionTag tag = VersionTag.create(persistent, in);
            ids.add(tag.getMemberID());
            add(tag);
            break;
          case FLAG_TAG_WITH_NUMBER_ID:
            tag = VersionTag.create(persistent, in);
            int idNumber = (int)InternalDataSerializer.readUnsignedVL(in);
            tag.setMemberID(ids.get(idNumber));
            add(tag);
            break;
          }
        }
      }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      toData(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException,
        ClassNotFoundException {
      fromData(in);
    }

    @Override
    public Version[] getSerializationVersions() {
      return null;
    }
  }

  @Override
  protected FilterRoutingInfo getRecipientFilterRouting(Set cacheOpRecipients) {
    // for putAll, we need to determine the routing information for each event and
    // create a consolidated routing object representing all events that can be
    // used for distribution
    CacheDistributionAdvisor advisor;
    LocalRegion region = (LocalRegion)this.event.getRegion();
    if (region instanceof PartitionedRegion) {
      advisor = ((PartitionedRegion)region).getCacheDistributionAdvisor();
    } else if (region.isUsedForPartitionedRegionBucket()) {
      advisor = ((BucketRegion)region).getPartitionedRegion().getCacheDistributionAdvisor();
    } else {
      advisor = ((DistributedRegion)region).getCacheDistributionAdvisor();
    }
    FilterRoutingInfo consolidated = new FilterRoutingInfo();
    for (int i=0; i<this.putAllData.length; i++) {
      EntryEventImpl ev = getEventForPosition(i);
      if (ev != null) {
        FilterRoutingInfo eventRouting = advisor.adviseFilterRouting(ev, cacheOpRecipients);
        if (eventRouting != null) {
          consolidated.addFilterInfo(eventRouting);
        }
        putAllData[i].filterRouting = eventRouting;
      }
    }
    // we need to create routing information for each PUT event
    return consolidated;
  }


  @Override
  protected FilterInfo getLocalFilterRouting(FilterRoutingInfo frInfo) {
//    long start = NanoTimer.getTime();
    FilterProfile fp = getRegion().getFilterProfile();
    if (fp == null) {
      return null;
    }

    // this will set the local FilterInfo in the events
    if (this.putAllData != null && this.putAllData.length > 0) {
      fp.getLocalFilterRoutingForPutAllOp(this, this.putAllData);
    }

//    long finish = NanoTimer.getTime();
//    InternalDistributedSystem.getDMStats().incjChannelUpTime(finish-start);
    return null;
  }

  @Override
  protected CacheOperationMessage createMessage()
 {
    EntryEventImpl event = getEvent();
    PutAllMessage msg = new PutAllMessage(event.getTXState());
    msg.eventId = event.getEventId();
    msg.context = event.getContext();
    msg.lastModified = event.getEntryLastModified();
    msg.setFetchFromHDFS(event.isFetchFromHDFS());
    msg.setPutDML(event.isPutDML());
    return msg;
  }

  /**
   * Create PutAllPRMessage for notify only (to adjunct nodes)
   * 
   * @param bucketId
   *               create message to send to this bucket
   * @return PutAllPRMessage
   */
  public PutAllPRMessage createPRMessagesNotifyOnly(int bucketId) {
    final EntryEventImpl event = getEvent();
    PutAllPRMessage prMsg = new PutAllPRMessage(bucketId, putAllDataSize, true,
        event.isPossibleDuplicate(), !event.isGenerateCallbacks(), this.txState, false, false /*isPutDML*/);
    if (event.getContext() != null) {
      prMsg.setBridgeContext(event.getContext());
    }

    // will not recover event id here
    for (int i=0; i<putAllDataSize; i++) {
      prMsg.addEntry(putAllData[i]);  
    }

    return prMsg;
  }

  /**
   * Create PutAllPRMessages for primary buckets out of dpao
   * 
   * @return a HashMap contain PutAllPRMessages, key is bucket id
   */
  public THashMap createPRMessages()
  {
    //getFilterRecipients(Collections.EMPTY_SET); // establish filter recipient routing information
    THashMap prMsgMap = new THashMap();
    final EntryEventImpl event = getEvent();
    
    for (int i=0; i<putAllDataSize; i++) {
      Integer bucketId = putAllData[i].bucketId;
      PutAllPRMessage prMsg = (PutAllPRMessage)prMsgMap.get(bucketId);
      if (prMsg == null) {
        prMsg = new PutAllPRMessage(bucketId.intValue(), putAllDataSize, false,
            event.isPossibleDuplicate(),
            !event.isGenerateCallbacks(), this.txState, event.isFetchFromHDFS(), event.isPutDML());

        // set dpao's context(original sender) into each PutAllMsg
        // dpao's event's context could be null if it's P2P putAll in PR
        if (event.getContext() != null) {
          prMsg.setBridgeContext(event.getContext());
        }
      }

      // Modify the event id, assign new thread id and new sequence id
      // We have to set fake event id here, because we cannot derive old event id from baseId+idx as we
      // did in DR's PutAllMessage. 
      putAllData[i].setFakeEventID();
      // we only save the reference in prMsg. No duplicate copy
      prMsg.addEntry(putAllData[i]);
      prMsgMap.put(bucketId, prMsg);
    }
    return prMsgMap;
  }

  @Override
  protected void initMessage(CacheOperationMessage msg,
      DirectReplyProcessor proc)
  {
    super.initMessage(msg, proc);
    PutAllMessage m = (PutAllMessage)msg;
    
    // if concurrency checks are enabled and this is not a replicated
    // region we need to see if any of the entries have no versions and,
    // if so, cull them out and send a 1-hop message to a replicate that
    // can generate a version for the operation
    
    RegionAttributes attr = this.event.getRegion().getAttributes();
    if (attr.getConcurrencyChecksEnabled() && !attr.getDataPolicy().withReplication() && attr.getScope() != Scope.GLOBAL) {
      if (attr.getDataPolicy() == DataPolicy.EMPTY) {
        // all entries are without version tags
        boolean success = RemotePutAllMessage.distribute((EntryEventImpl)this.event, 
            this.putAllData, this.putAllDataSize);
        if (success) {
          m.callbackArg = this.event.getCallbackArgument();
          m.putAllData = new PutAllEntryData[0];
          m.putAllDataSize = 0;
          m.skipCallbacks = !event.isGenerateCallbacks();
          return;
          
        } else if (!getRegion().getGenerateVersionTag()) {
          // Fix for #45934.  We can't continue if we need versions and we failed
          // to distribute versionless entries.
          throw new PersistentReplicatesOfflineException();
        }
      } else {
        // some entries may have Create ops - these will not have version tags
        PutAllEntryData[] versionless = selectVersionedEntries(false);
        if (getRegion().getLogWriterI18n().finerEnabled()) {
          getRegion().getLogWriterI18n().finer("Found these versionless entries: " + Arrays.toString(versionless));
        }
        if (versionless.length > 0) {
          boolean success = RemotePutAllMessage.distribute((EntryEventImpl)this.event,
              versionless, versionless.length);
          if (success) {
            versionless = null;
            PutAllEntryData[] versioned = selectVersionedEntries(true); 
            if (getRegion().getLogWriterI18n().finerEnabled()) {
              getRegion().getLogWriterI18n().finer("Found these remaining versioned entries: " + Arrays.toString(versioned));
            }
            m.callbackArg = this.event.getCallbackArgument();
            m.putAllData = versioned;
            m.putAllDataSize = versioned.length;
            m.skipCallbacks = !event.isGenerateCallbacks();
            return;

          } else if (!getRegion().getGenerateVersionTag()) {
            // Fix for #45934.  We can't continue if we need versions and we failed
            // to distribute versionless entries.
            throw new PersistentReplicatesOfflineException();
          }
        } else {
          if (getRegion().getLogWriterI18n().fineEnabled()) {
            getRegion().getLogWriterI18n().fine("All entries have versions, so using normal DPAO message");
          }
        }
      }
    }
    m.callbackArg = this.event.getCallbackArgument();
    m.putAllData = this.putAllData;
    m.putAllDataSize = this.putAllDataSize;
    m.skipCallbacks = !event.isGenerateCallbacks();
  }


  @Override
  protected boolean shouldAck() {
    // bug #45704 - RemotePutAllOp's DPAO in another server conflicts with lingering DPAO from same thread, so
    // we require an ACK if concurrency checks are enabled to make sure that the previous op has finished first.
    return super.shouldAck() || getRegion().concurrencyChecksEnabled;
  }

  private PutAllEntryData[] selectVersionedEntries(boolean withVersion) {
    int numWithVersion = 0;
    int numBlanks = 0;
    for (int i=0; i<this.putAllData.length; i++) {
      if (this.putAllData[i] == null || this.putAllData[i].isInhibitDistribution()) { // ignore blank or pre-distributed entries
        numBlanks++;
      } else if (this.putAllData[i].versionTag != null) {
        numWithVersion++;
      }
    }
    PutAllEntryData[] result = new PutAllEntryData[withVersion? numWithVersion
        : (this.putAllData.length - numWithVersion - numBlanks)];
    int ri = 0;
    for (int i=0; i<this.putAllData.length; i++) {
      PutAllEntryData p = this.putAllData[i];
      if ((p != null && !p.isInhibitDistribution())
          && (withVersion ^ 
              ((p.versionTag == null)
                || !p.versionTag.hasValidVersion()))) {
        result[ri++] = p;
      }
    }
    return result;
  }
  
  /**
   * version tags are gathered from local operations and remote operation
   * responses.  This method gathers all of them and stores them in the
   * given list.
   * @param list
   */
  protected void fillVersionedObjectList(VersionedObjectList list) {
    for (PutAllEntryData entry: this.putAllData) {
      if (entry.versionTag != null) {
        list.addKeyAndVersion(entry.key, entry.versionTag);
      }
    }
  }

  public static class PutAllMessage extends AbstractUpdateMessage
   {

     public PutAllMessage(){}
     public PutAllMessage(TXStateInterface tx) {
       super(tx);
     }

    protected PutAllEntryData[] putAllData;

    protected int putAllDataSize;
    
    protected transient ClientProxyMembershipID context;

    protected boolean skipCallbacks;

    protected EventID eventId = null;

    private transient boolean isNew;
    
    // By default, fetchFromHDFS == true;
    private transient boolean fetchFromHDFS = true;
    
    private transient boolean isPutDML = false;

    protected static final short HAS_BRIDGE_CONTEXT = UNRESERVED_FLAGS_START;
    protected static final short SKIP_CALLBACKS =
      (short)(HAS_BRIDGE_CONTEXT << 1);

    /** test to see if this message holds any data */
    public boolean isEmpty() {
      return this.putAllData.length == 0;
    }
    /**
     * Note this this is a "dummy" event since this message contains a list of
     * entries each one of which has its own event. The key thing needed in this
     * event is the region. This is the event that gets passed to
     * basicOperateOnRegion
     */
    @Override
    protected InternalCacheEvent createEvent(DistributedRegion rgn)
    throws EntryNotFoundException
    {
      // Gester: We have to specify eventId for the message of MAP
      EntryEventImpl event = EntryEventImpl.create(
          rgn,
          Operation.PUTALL_UPDATE /* op */, null /* key */, null/* value */,
          null /* callbackArg */, true /* originRemote */, getSender());
      if (this.context != null) {
        event.context = this.context;
        event.setCallbackArgument(this.callbackArg);
      }
      event.setPossibleDuplicate(this.possibleDuplicate);
      event.setEventId(this.eventId);
      return event;
    }

    @Override
    public void appendFields(StringBuilder sb) {
      super.appendFields(sb);
      sb.append("; entries=").append(this.putAllDataSize);
      if (putAllDataSize <= 20) {
        // 20 is a size for test
        sb.append("; entry values=").append(Arrays.toString(this.putAllData));
      }
    }
    
    /**
     * Does the "put" of one entry for a "putall" operation. Note it calls back
     * to AbstractUpdateOperation.UpdateMessage#basicOperationOnRegion
     * 
     * @param entry
     *          the entry being put
     * @param rgn
     *          the region the entry is put in
     */
    protected final void doEntryPut(PutAllEntryData entry,
        DistributedRegion rgn, boolean requiresRegionContext,
        final TXStateInterface tx, boolean fetchFromHDFS, boolean isPutDML, long lastModifiedTime, UMMMemoryTracker memoryTracker) {
      EntryEventImpl ev = PutAllMessage.createEntryEvent(entry, getSender(),
          this.context, rgn, requiresRegionContext, this.possibleDuplicate,
          this.needsRouting, this.callbackArg, true, skipCallbacks,
          getLockingPolicy(), tx);
      if (tx == null)
        ev.setEntryLastModified(lastModifiedTime);
      ev.setFetchFromHDFS(fetchFromHDFS);
      ev.setPutDML(isPutDML);
      ev.setBufferedMemoryTracker(memoryTracker);
//      rgn.getLogWriterI18n().info(LocalizedStrings.DEBUG, "PutAllOp.doEntryPut sender=" + getSender() +
//          " event="+ev);
      // we don't need to set old value here, because the msg is from remote. local old value will get from next step
      try {
        super.basicOperateOnRegion(ev, rgn);
      } finally {
        if (ev.getVersionTag() != null && !ev.getVersionTag().isRecorded()) {
          if (rgn.getVersionVector() != null) {
            rgn.getVersionVector().recordVersion(getSender(), ev.getVersionTag(), ev);
          }
        }
        ev.release();
      }
    }
    
    /**
     * create an event for a PutAllEntryData element
     * @param entry
     * @param sender
     * @param context
     * @param rgn
     * @param requiresRegionContext
     * @param possibleDuplicate
     * @param needsRouting
     * @param callbackArg
     * @return the event to be used in applying the element
     */
    public static EntryEventImpl createEntryEvent(PutAllEntryData entry,
        InternalDistributedMember sender, ClientProxyMembershipID context,
        DistributedRegion rgn, boolean requiresRegionContext,
        boolean possibleDuplicate, boolean needsRouting, Object callbackArg,
        boolean originRemote, boolean skipCallbacks, LockingPolicy lockPolicy,
        TXStateInterface tx) {
      Object key = entry.getKey();
      final Object v = entry.getValue();
      if (requiresRegionContext) {
        final KeyWithRegionContext keyWithContext = (KeyWithRegionContext)key;
        if (v != null && !(v instanceof Delta)) {
          keyWithContext.afterDeserializationWithValue(v);
        }
        keyWithContext.setRegionContext(rgn);
      }
      EventID evId = entry.getEventID();      
      EntryEventImpl ev = EntryEventImpl.create(rgn, entry.getOp(),
          key, null/* value */, entry.getCallbackArg(),
          originRemote, sender, !skipCallbacks,
          evId);
      boolean returnedEv = false;
      try {
      if (context != null) {
        assert entry.getCallbackArg() == null;
        ev.setCallbackArgument(callbackArg);
        ev.context = context;
      }
      if (v == null && rgn.getDataPolicy() == DataPolicy.NORMAL) {
        ev.setLocalInvalid(true);
      }
      ev.setNewValue(v);
      ev.setPossibleDuplicate(possibleDuplicate);
      ev.setLockingPolicy(lockPolicy);
      ev.setTXState(tx);
      ev.setVersionTag(entry.versionTag);
//      if (needsRouting) {
//               FilterProfile fp = rgn.getFilterProfile();
//                if (fp != null) {
//                  FilterInfo fi = fp.getLocalFilterRouting(ev);
//                  ev.setLocalFilterInfo(fi);
//                }
//      }
      if (entry.filterRouting != null) {
        InternalDistributedMember id = rgn.getMyId();
        ev.setLocalFilterInfo(entry.filterRouting.getFilterInfo(id));
      }
      /**
       * Setting tailKey for the secondary bucket here. Tail key was update by the primary.
       */
      ev.setTailKey(entry.getTailKey());
      returnedEv = true;
      return ev;
      } finally {
        if (!returnedEv) {
          ev.release();
        }
      }
    }

    @Override
    protected void basicOperateOnRegion(final EntryEventImpl ev, final DistributedRegion rgn)
    {
      for (int i = 0; i < putAllDataSize; ++i) {
        if (putAllData[i].versionTag != null) {
          checkVersionTag(rgn, putAllData[i].versionTag);
        }
      }
      
      final TXStateInterface tx = ev.getTXState(rgn);
      TXManagerImpl txMgr = null;
      TXManagerImpl.TXContext context = null;
      if (getLockingPolicy() == LockingPolicy.SNAPSHOT) {
        txMgr = rgn.getCache().getTxManager();
        context = txMgr.masqueradeAs(this, false,
            true);
        ev.setTXState(getTXState());
      }
      ev.setTXState(tx);
      try {
        rgn.syncPutAll(tx, new Runnable() {
          public void run() {
            UMMMemoryTracker memoryTracker = null;
            if (CallbackFactoryProvider.getStoreCallbacks().isSnappyStore()
                    && !rgn.isInternalColumnTable()) {
              memoryTracker = new UMMMemoryTracker(
                      Thread.currentThread().getId(), putAllDataSize);
            }
            try {
              final boolean requiresRegionContext = rgn.keyRequiresRegionContext();
              for (int i = 0; i < putAllDataSize; ++i) {
                if (rgn.getLogWriterI18n().finerEnabled()) {
                  rgn.getLogWriterI18n().finer("putAll processing " + putAllData[i] + " with " + putAllData[i].versionTag);
                }
                putAllData[i].setSender(sender);
                doEntryPut(putAllData[i], rgn, requiresRegionContext, tx,
                    fetchFromHDFS, isPutDML, ev.getEntryLastModified(), memoryTracker);
              }
            } finally {
              if (memoryTracker != null) {
                long unusedMemory = memoryTracker.freeMemory();
                if (unusedMemory > 0) {
                  CallbackFactoryProvider.getStoreCallbacks().releaseStorageMemory(
                          memoryTracker.getFirstAllocationObject(), unusedMemory, false);
                }
              }
            }

          }
        }, ev.getEventId());
      }
      finally {
        if (getLockingPolicy() == LockingPolicy.SNAPSHOT) {
          txMgr.unmasquerade(context, true);
        }
      }
    }

    public int getDSFID() {
      return PUT_ALL_MESSAGE;
    }

    @Override
    protected int getMessageProcessorType() {
      // don't use SERIAL_EXECUTOR for putAll messages since processing
      // of these can take long time
      return DistributionManager.STANDARD_EXECUTOR;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {

      super.fromData(in);
      this.eventId = (EventID) DataSerializer.readObject(in);
      this.putAllDataSize = (int)InternalDataSerializer.readUnsignedVL(in);
      this.putAllData = new PutAllEntryData[this.putAllDataSize];
      if (this.putAllDataSize > 0) {
        final Version version = InternalDataSerializer
            .getVersionForDataStreamOrNull(in);
        final ByteArrayDataInput bytesIn = new ByteArrayDataInput();
        for (int i = 0; i < this.putAllDataSize; i++) {
          this.putAllData[i] = new PutAllEntryData(in, eventId, i, version,
              bytesIn);
        }

        boolean hasTags = in.readBoolean();
        if (hasTags) {
          EntryVersionsList versionTags = EntryVersionsList.create(in);
          for (int i = 0; i < this.putAllDataSize; i++) {
            this.putAllData[i].versionTag = versionTags.get(i);
          }
        }
      }

      if ((flags & HAS_BRIDGE_CONTEXT) != 0) {
        this.context = DataSerializer.readObject(in);
      }
      this.skipCallbacks = (flags & SKIP_CALLBACKS) != 0;
    }

    public void toData(final DataOutput out)
        throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.eventId, out);
      InternalDataSerializer.writeUnsignedVL(this.putAllDataSize, out);
      if (this.putAllDataSize > 0) {
        EntryVersionsList versionTags = new EntryVersionsList(putAllDataSize);

        boolean hasTags = false;
        // get the "keyRequiresRegionContext" flag from first element assuming
        // all key objects to be uniform
        final boolean requiresRegionContext =
          (this.putAllData[0].key instanceof KeyWithRegionContext);
        for (int i = 0; i < this.putAllDataSize; i++) {
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
      if (this.context != null) {
        DataSerializer.writeObject(this.context, out);
      }
    }

    @Override
    protected short computeCompressedShort(short s) {
      s = super.computeCompressedShort(s);
      if (this.context != null) s |= HAS_BRIDGE_CONTEXT;
      if (this.skipCallbacks) s |= SKIP_CALLBACKS;
      return s;
    }

    public int getOperationCount()
    {
      return this.putAllDataSize;
    }

    public ClientProxyMembershipID getContext()
    {
      return this.context;
    }
    
    public PutAllEntryData[] getPutAllEntryData()
    {
      return this.putAllData;
    }
    
   
    
    @Override
    public List getOperations()
    {
      QueuedOperation[] ops = new QueuedOperation[getOperationCount()];
      for (int i = 0; i < ops.length; i++) {
        PutAllEntryData entry = this.putAllData[i];
        byte[] valueBytes = null;
        Object valueObj = null;
        Object v = entry.getValue();
        byte deserializationPolicy;
        if (v instanceof byte[]) {
          deserializationPolicy = DESERIALIZATION_POLICY_NONE;
          valueBytes = (byte[])v;
        }
        else if (CachedDeserializableFactory.preferObject()
            || v instanceof Delta) {
          deserializationPolicy = DESERIALIZATION_POLICY_EAGER;
          valueObj = v;
        }
        else {
          deserializationPolicy = DESERIALIZATION_POLICY_LAZY;
          valueBytes = ((CachedDeserializable)v).getSerializedValue();
        }
        Object callbackArg = this.callbackArg != null ? this.callbackArg
            : entry.getCallbackArg();
        
        assert this.callbackArg == null || entry.getCallbackArg() == null; 

        ops[i] = new QueuedOperation(entry.getOp(), entry.getKey(), valueBytes,
            valueObj, deserializationPolicy, callbackArg);
      }
      return Arrays.asList(ops);
    }
    
    public void setFetchFromHDFS(boolean val) {
      this.fetchFromHDFS = val;
    }
    
    public void setPutDML(boolean val) {
      this.isPutDML = val;
    }
    
    protected short computeCompressedExtBits(short bits) {
      bits = super.computeCompressedExtBits(bits);
      if (fetchFromHDFS) {
        bits |= FETCH_FROM_HDFS;
      }
      if (isPutDML) {
        bits |= IS_PUT_DML;
      }
      return bits;
    }
  }
}
