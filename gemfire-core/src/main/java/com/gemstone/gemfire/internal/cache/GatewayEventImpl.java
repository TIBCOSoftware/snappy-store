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

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.SerializationException;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.util.GatewayEvent;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.cache.wan.GatewayQueueEvent;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.cache.EntryEventImpl.NewValueImporter;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerHelper;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Class <code>GatewayEventImpl</code> represents an event sent between
 * <code>Gateway</code>
 *
 * @author Barry Oglesby
 *
 * @since 4.2
 *
 */
public final class GatewayEventImpl implements GatewayEvent, GatewayQueueEvent, DataSerializableFixedID, Conflatable, Sizeable, NewValueImporter  {
  private static final long serialVersionUID = -5690172020872255422L;
  private static final Object TOKEN_UN_INITIALIZED = new Object();
  private static final short VERSION = 0x10;
  protected EnumListenerEvent _operation;

  protected EntryEventImpl _entryEvent;

  /**
   * The action to be taken (e.g. AFTER_CREATE)
   */
  protected int _action;

  /**
   * The number of parts for the <code>Message</code>
   *
   * @see com.gemstone.gemfire.internal.cache.tier.sockets.Message
   */
  protected int _numberOfParts;

  /**
   * The identifier of this event
   */
  protected EventID _id;

  //protected String _id;

  /**
   * The <code>Region</code> that was updated
   */
   private LocalRegion _region;

  /**
   * The name of the region being affected by this event
   */
  protected String _regionName;

  /**
   * The key being affected by this event
   */
  private Object _key = TOKEN_UN_INITIALIZED;

  /**
   * The serialized new value for this event's key
   */
  protected byte[] _value;

  /**
   * Whether the value is a serialized object or just a byte[]
   */
  protected byte _valueIsObject;

  /**
   * The callback argument for this event
   */
  protected GatewayEventCallbackArgument _callbackArgument;
  
//  /*
//   * The version timestamp
//   */
//  private long versionTimeStamp;

  /**
   * Whether this event is a possible duplicate
   */
  protected boolean _possibleDuplicate;

  /**
   * The creation timestamp in ms
   */
  protected long _creationTime;

  /**
   * For ParalledGatewaySender we need bucketId of the PartitionRegion on which
   * the update operation was applied. 
   */
  private int bucketId;

  private Long shadowKey = new Long(1);

  public static final int CREATE_ACTION = 0; 
  public static final int UPDATE_ACTION = 1; 
  public static final int DESTROY_ACTION = 2; 
  // Used by gemfirexd to send bulk DML across wan
  public static final int BULK_DML_OP_ACTION = 3; 

  /**
   * Constructor. No-arg constructor for data serialization.
   *
   * @see DataSerializer
   */
  public GatewayEventImpl() {
  }

  /**
   * Constructor. Creates an initialized <code>GatewayEventImpl</code>
   *
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>CacheEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   *
   * @throws IOException
   */
  public GatewayEventImpl(EnumListenerEvent operation, CacheEvent event)
      throws IOException {
    this(operation, event, true);
  }

  public GatewayEventImpl(EnumListenerEvent operation, CacheEvent event,
      boolean initialize, int bucketId) throws IOException {
    this(operation, event, true);
    this.bucketId = bucketId;
  }
  
  /**
   * Constructor.
   *
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>CacheEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   * @param initialize
   *          Whether to initialize this instance
   *
   * @throws IOException
   */
  public GatewayEventImpl(EnumListenerEvent operation, CacheEvent event,
      boolean initialize) throws IOException {
    // Set the operation and event
    this._operation = operation;
    EntryEventImpl entryEvent = (EntryEventImpl)event;
    this._entryEvent = entryEvent; // this reference is discarded in initialize()

    // Initialize the region name. This is being done here because the event
    // can get serialized/deserialized (for some reason) between the time
    // it is set above and used (in initialize). If this happens, the
    // region is null because it is a transient field of the event.
    this._region  =  this._entryEvent.getRegion();
    this._regionName = this._region.getFullPath();

    // Initialize the unique id
    initializeId();

    // Initialize possible duplicate
    this._possibleDuplicate = false;

    // Initialize the creation timestamp
    this._creationTime = _region.cacheTimeMillis();

//    if (GatewayBatchOp.VERSION_WITH_OLD_WAN) {
//      if (this._entryEvent.getVersionTag() != null) {
//        this.versionTimeStamp = this._entryEvent.getVersionTag().getVersionTimeStamp();
//      }
//    }

    // Initialize the remainder of the event if necessary
    if (initialize) {
      initialize();     
    } else {
      this._entryEvent.copyOffHeapToHeap();
    }
  }

  /**
   * Returns this event's action
   *
   * @return this event's action
   */
  public int getAction()
  {
    return this._action;
  }

  /**
   * Returns this event's operation
   *
   * @return this event's operation
   */
  public Operation getOperation() {
    Operation operation = null;
    switch (this._action) {
      case CREATE_ACTION:
        operation = Operation.CREATE;
        break;
      case UPDATE_ACTION:
        operation = Operation.UPDATE;
        break;
      case DESTROY_ACTION:
        operation = Operation.DESTROY;
        break;
      case BULK_DML_OP_ACTION:
        operation = Operation.BULK_DML_OP;
        break;
        
    }
    return operation;
  }

  /**
   * Return this event's region name
   *
   * @return this event's region name
   */
  public String getRegionName()
  {
    return this._regionName;
  }
  

  /**
   * Returns this event's key
   *
   * @return this event's key
   */
  public Object getKey()
  {
    //TODO:Asif : Ideally would like to have throw exception if the key 
    //is TOKEN_UN_INITIALIZED, but for the time being trying to retain the GFE behaviour
    // of returning null if getKey is invoked on un-initialized gateway event 
    
    return this._key==TOKEN_UN_INITIALIZED?null:this._key;
  }

  /**
   * Returns this event's serialized value
   *
   * @return this event's serialized value
   */
  public byte[] getValue()
  {
    return this._value;
  }

  /**
   * Returns whether this event's value is a serialized object. A return value
   * of 0 indicates that value is a raw byte[], that of 1 indicates an object
   * and 2 indicates an internal delta object.
   * 
   * @return whether this event's value is a serialized object
   */
  public byte getValueIsObject() {
    return this._valueIsObject;
  }

  /**
   * Return this event's callback argument
   *
   * @return this event's callback argument
   */
  public Object getCallbackArgument()
  {
    Object result = getGatewayCallbackArgument();
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument)result;
      result = wca.getOriginalCallbackArg();
    }
    return result;
  }

  public GatewayEventCallbackArgument getGatewayCallbackArgument() {
    return this._callbackArgument;
  }

  /**
   * Return this event's number of parts
   *
   * @return this event's number of parts
   */
  public int getNumberOfParts()
  {
    return this._numberOfParts;
  }

  protected void initializeKey() {
    if (this._key instanceof KeyWithRegionContext) {
      final LocalRegion r = getRegion();
      if (r == null) {
        // log a warning
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null && logger.warningEnabled()) {
          logger.warning(LocalizedStrings.DEBUG,
              "GatewayEventImpl.initializeKey: region "
              + this._regionName + " not found while initializing key "
              + this._key);
        }
      }
      else if (r.keyRequiresRegionContext()) {
        ((KeyWithRegionContext)this._key).setRegionContext(r);
      }
    }
  }

  /**
   * Return this event's deserialized value
   * 
   * SUPPRESSES ANY DESERIALIZATION FAILURES.
   * 
   * @return this event's deserialized value
   */
  public Object getDeserializedValueForLogging()
  {
    Object deserializedObject = this._value;
    // This is a debugging method so ignore all exceptions like
    // ClassNotFoundException
    try {
      if(this._valueIsObject == 0x00){
        deserializedObject = this._value;
      } else {
        deserializedObject = EntryEventImpl.deserialize(this._value);
      }
    }
    catch (Exception e) {
    }
    return deserializedObject;
  }

  /**
   * Return this event's deserialized value
   *
   * @return this event's deserialized value
   */
  public Object getDeserializedValue()
  {
    if(this._valueIsObject == 0x00){
      return this._value;
    } else {
      return EntryEventImpl.deserialize(this._value);
    }
  }

  public byte[] getSerializedValue() {
    return this._value;
  }

  public void setPossibleDuplicate(boolean possibleDuplicate) {
    this._possibleDuplicate = possibleDuplicate;
  }

  public boolean getPossibleDuplicate() {
    return this._possibleDuplicate;
  }

  public long getCreationTime() {
    return this._creationTime;
  }
  
  public int getDSFID() {
    return GATEWAY_EVENT_IMPL;
  }

  public void toData(DataOutput out) throws IOException
  {
    // Make sure we are initialized before we serialize.
    if (this._key == TOKEN_UN_INITIALIZED) {
      initialize();
    }
    out.writeShort(VERSION);
    out.writeInt(this._action);
    out.writeInt(this._numberOfParts);
    //out.writeUTF(this._id);
    DataSerializer.writeObject(this._id, out);
    DataSerializer.writeString(this._regionName, out);
    out.writeByte(this._valueIsObject);
    DataSerializer.writeObject(this._key, out);
    DataSerializer.writeByteArray(this._value, out);
    DataSerializer.writeObject(this._callbackArgument, out);
    out.writeBoolean(this._possibleDuplicate);
    out.writeLong(this._creationTime);
    out.writeInt(this.bucketId);
    out.writeLong(this.shadowKey);
//    if (GatewayBatchOp.VERSION_WITH_OLD_WAN) {
//      out.writeLong(this.versionTimeStamp);
//    }
  }

  public void fromData66(DataInput in) throws IOException, ClassNotFoundException
  {
    this._action = in.readInt();
    this._numberOfParts = in.readInt();
    //this._id = in.readUTF();
    this._id = (EventID)DataSerializer.readObject(in);
    //TODO:Asif ; Check if this violates Barry's logic of not assiging VM
    // specific Token.FROM_GATEWAY
    //and retain the serialized Token.FROM_GATEWAY
    //this._id.setFromGateway(false);
    this._regionName = DataSerializer.readString(in);
    this._valueIsObject = in.readByte();
    this._key = DataSerializer.readObject(in);
    this._value = DataSerializer.readByteArray(in);
    this._callbackArgument = (GatewayEventCallbackArgument)DataSerializer.readObject(in);
    this._possibleDuplicate = in.readBoolean();
    this._creationTime = in.readLong();

    // skumar's comments:
    // bucketId and shadowKey is not required for SerialGatewaySender. So, 
    // the any default values will work with it.  We should provide 
    // documnetation for not using old disk files for ParallelGatewaySender. 
    // This cannot be supported.
    this.bucketId = 1;
    this.shadowKey = new Long(1);
//    if (GatewayBatchOp.VERSION_WITH_OLD_WAN) {
//      this.versionTimeStamp = in.readLong();
//    }
  }
  
  public void fromData(DataInput in) throws IOException, ClassNotFoundException
  {
    short version = in.readShort();
    if (version != VERSION) {
      // warning?
    }
    this._action = in.readInt();
    this._numberOfParts = in.readInt();
    //this._id = in.readUTF();
    this._id = (EventID)DataSerializer.readObject(in);
    this._regionName = DataSerializer.readString(in);
    this._valueIsObject = in.readByte();
    this._key = DataSerializer.readObject(in);
    this._value = DataSerializer.readByteArray(in);
    this._callbackArgument = (GatewayEventCallbackArgument)DataSerializer.readObject(in);
    this._possibleDuplicate = in.readBoolean();
    this._creationTime = in.readLong();
    this.bucketId = in.readInt();
    this.shadowKey = in.readLong();
//    if (GatewayBatchOp.VERSION_WITH_OLD_WAN) {
//      this.versionTimeStamp = in.readLong();
//    }
  }

  @Override
  public String toString()
  {
    StringBuilder buffer = new StringBuilder();
    buffer.append("GatewayEventImpl[")
      .append("id=")
      .append(this._id)
      .append(";action=")
      .append(this._action)
      .append(";operation=")
      .append(getOperation())
      .append(";region=")
      .append(this._regionName)
      .append(";key=")
      .append(this._key)
      .append(";value=")
      .append(getDeserializedValueForLogging())
      .append(";valueIsObject=")
      .append(this._valueIsObject)
      .append(";numberOfParts=")
      .append(this._numberOfParts)
      .append(";callbackArgument=")
      .append(this._callbackArgument)
      .append(";possibleDuplicate=")
      .append(this._possibleDuplicate)
      .append(";creationTime=")
      .append(this._creationTime)
      .append(":bucketId=")
      .append(this.bucketId)
      .append(":shadowKey=")
      .append(this.shadowKey);
//    if (GatewayBatchOp.VERSION_WITH_OLD_WAN) {
//      buffer.append(";versionStamp=")
//      .append(this.versionTimeStamp);
//    }
    buffer.append("]");
    return buffer.toString();
  }

  protected Object deserializeForLogging(byte[] serializedBytes)
  {
    Object deserializedObject = serializedBytes;
    // This is a debugging method so ignore all exceptions like
    // ClassNotFoundException
    try {
     if(this._valueIsObject == 0x00){
        deserializedObject = serializedBytes;
        } else {
       deserializedObject = EntryEventImpl.deserialize(serializedBytes);
       }
    }
    catch (Exception e) {
      throw new InternalGemFireError(e);
    }
    return deserializedObject;
  }

  /// Conflatable interface methods ///

  /**
   * Determines whether or not to conflate this message. This method will answer
   * true IFF the message's operation is AFTER_UPDATE and its region has enabled
   * are conflation. Otherwise, this method will answer false. Messages whose
   * operation is AFTER_CREATE, AFTER_DESTROY, AFTER_INVALIDATE or
   * AFTER_REGION_DESTROY are not conflated.
   *
   * @return Whether to conflate this message
   */
  public boolean shouldBeConflated()
  {
    // The event may be conflated if any of the following is true
    // - the event is an update
    // - the region is empty and the event's operation is a create
    // Otherwise, the event is not conflatable.
    return isUpdate()
        || (getRegion() != null
            && !getRegion().getAttributes().getDataPolicy().withStorage() && isCreate());
  }

  /**
   * {@inheritDoc}
   */
  public boolean shouldBeMerged() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  public boolean merge(Conflatable existing) {
    throw new AssertionError("not expected to be invoked");
  }

  public String getRegionToConflate()
  {
    return this._regionName;
  }

  public Object getKeyToConflate()
  {
    return this._key;
  }

  public Object getValueToConflate()
  {
    return this._value;
  }

  public void setLatestValue(Object value)
  {
    this._value = (byte[])value;
  }

  /// End Conflatable interface methods ///

  /**
   * Returns whether this <code>GatewayEvent</code> represents an update.
   * @return whether this <code>GatewayEvent</code> represents an update
   */
  protected boolean isUpdate()
  {
    // This event can be in one of three states: 
    // - in memory primary (initialized) 
    // - in memory secondary (not initialized) 
    // - evicted to disk, read back in (initialized) 
    // In the first case, both the operation and action are set. 
    // In the second case, only the operation is set. 
    // In the third case, only the action is set. 
    return this._operation == null 
      ? this._action == UPDATE_ACTION 
      : this._operation == EnumListenerEvent.AFTER_UPDATE; 
  }

  /**
    * Returns whether this <code>GatewayEvent</code> represents a create.
    * @return whether this <code>GatewayEvent</code> represents a create
    */
  protected boolean isCreate()
  {
    //See the comment in isUpdate() for additional details 
    return this._operation == null 
      ? this._action == CREATE_ACTION 
      : this._operation == EnumListenerEvent.AFTER_CREATE; 
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a destroy.
   * @return whether this <code>GatewayEvent</code> represents a destroy
   */
  protected boolean isDestroy()
  {
    // See the comment in isUpdate() for additional details 
    return this._operation == null 
      ? this._action == DESTROY_ACTION 
      : this._operation == EnumListenerEvent.AFTER_DESTROY; 
  }

  /**
   * Initialize the unique identifier for this event. This id is used by the
   * receiving <code>Gateway</code> to keep track of which events have been
   * processed. Duplicates can be dropped.
   */
  private void initializeId()
  {
    //CS43_HA
    this._id = this._entryEvent.getEventId();
    //TODO:ASIF :Once stabilized remove the check below
    if (this._id == null) {
      throw new IllegalStateException(LocalizedStrings.GatewayEventImpl_NO_EVENT_ID_IS_AVAILABLE_FOR_THIS_GATEWAY_EVENT.toLocalizedString());
    }

  }

  /**
   * Initialize this instance. Get the useful parts of the input operation and
   * event.
   *
   * @throws IOException
   */
  protected void initialize() throws IOException
  {
    // Set key
    //System.out.println("this._entryEvent: " + this._entryEvent);
    //System.out.println("this._entryEvent.getKey(): " +
    // this._entryEvent.getKey());
    if (this._key != TOKEN_UN_INITIALIZED) {
      // We have already initialized, or initialized elsewhere. Lets return.
      return;
    }
    this._key = this._entryEvent.getKey();

    // Set the value to be a byte[] representation of the value. If the value
    // is already serialized, use it.
    this._valueIsObject = 0x01;
    if (this._entryEvent.hasDelta()) {
      this._valueIsObject = 0x02;
    }
    this._entryEvent.exportNewValue(this);

    // Set the callback arg
    this._callbackArgument = (GatewayEventCallbackArgument)this._entryEvent.getRawCallbackArgument();

    // Initialize the action and number of parts (called after _callbackArgument
    // is set above)
    initializeAction(this._operation);
    
    // The entry event is no longer necessary. Null it so it can be GCed.
    this._entryEvent = null;

    // any initialization required for key
    initializeKey();
  }

  /**
   * Initialize this event's action and number of parts
   *
   * @param operation
   *          The operation from which to initialize this event's action and
   *          number of parts
   */
  protected void initializeAction(EnumListenerEvent operation)
  {
    if (operation == EnumListenerEvent.AFTER_CREATE) {
      // Initialize after create action
      this._action = CREATE_ACTION;

      // Initialize number of parts
      // part 1 = action
      // part 2 = posDup flag
      // part 3 = regionName
      // part 4 = eventId
      // part 5 = key
      // part 6 = value (create and update only)
      // part 7 = whether callbackArgument is non-null
      // part 8 = callbackArgument (if non-null)
      this._numberOfParts = (this._callbackArgument == null) ? 7 : 8;
    }
    else if (operation == EnumListenerEvent.AFTER_UPDATE) {
      // Initialize after update action
      this._action = UPDATE_ACTION;

      // Initialize number of parts
      this._numberOfParts = (this._callbackArgument == null) ? 7 : 8;
    }
    else if (operation == EnumListenerEvent.AFTER_DESTROY) {
      // Initialize after destroy action
      this._action = DESTROY_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this._numberOfParts = (this._callbackArgument == null) ? 6 : 7;
    }else if (operation == EnumListenerEvent.AFTER_BULK_DML_OP) {
      this._numberOfParts = 3;
      this._action = BULK_DML_OP_ACTION;     
    }
  }

  public EventID getEventId()
  {
    return this._id;
  }

  public int getSizeInBytes() {
    // Calculate the size of this event. This is used for overflow to disk.

    // The sizes of the following variables are calculated:
    //
    // - the value (byte[])
    // - the original callback argument (Object)
    // - primitive and object instance variable references
    //
    // The sizes of the following variables are not calculated:

    // - the key because it is a reference
    // - the region and regionName because they are references
    // - the operation because it is a reference
    // - the entry event because it is nulled prior to calling this method

    // The size of instances of the following internal datatypes were estimated
    // using a NullDataOutputStream and hardcoded into this method:

    // - the id (an instance of EventId)
    // - the callbackArgument (an instance of GatewayEventCallbackArgument)

    int size = 0;

    // Add this event overhead
    size += Sizeable.PER_OBJECT_OVERHEAD;

    // Add object references
    //_id reference = 4 bytes
    //_region reference = 4 bytes
    //_regionName reference = 4 bytes
    //_key reference = 4 bytes
    //_callbackArgument reference = 4 bytes
    //_operation reference = 4 bytes
    //_entryEvent reference = 4 bytes
    size += 28;

    // Add primitive references
    // int _action = 4 bytes
    // int _numberOfParts = 4 bytes
    // byte _valueIsObject = 1 byte
    // boolean _possibleDuplicate = 1 byte
    size += 10;

    // Add the id (an instance of EventId)
    // The hardcoded value below was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 56;

    // The value (a byte[])
    if (this._value != null) {
      size += CachedDeserializableFactory.calcMemSize(this._value);
    }

    // The callback argument (a GatewayEventCallbackArgument wrapping an Object
    // which is the original callback argument)
    // The hardcoded value below represents the GatewayEventCallbackArgument
    // and was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 194;
    // The sizeOf call gets the size of the input callback argument.
    size += Sizeable.PER_OBJECT_OVERHEAD + sizeOf(getCallbackArgument());
    
    // the version timestamp
    size += 8;

    return size;
  }

  private int sizeOf(Object obj) {
    int size = 0;
    if (obj == null) {
      return size;
    }
    if (obj instanceof String) {
      size = ObjectSizer.DEFAULT.sizeof(obj);
    } else if (obj instanceof Integer) {
      size = 4; //estimate
    } else if (obj instanceof Long) {
      size = 8; //estimate
    } else {
      size = CachedDeserializableFactory.calcMemSize(obj) - Sizeable.PER_OBJECT_OVERHEAD;
    }
    return size;
  }
  
  public EntryEvent<Object, Object> getEntryEvent() {
    return this._entryEvent;
  }
  
  public long getVersionTimeStamp() {
//    if (!GatewayBatchOp.VERSION_WITH_OLD_WAN) {
      throw new UnsupportedOperationException("versioning is not enabled with this WAN implementation");
//    }
//    return this.versionTimeStamp;
  }

  //Asif: If the GatewayEvent serializes to a node where the region itself may not be present or the
  // region is not created yet , and if the gateway event queue is persistent, then even if
  //we try to set the region in the fromData , we may still get null. Though the product is 
  //not using this method anywhere still not comfortable changing the Interface so 
  // modifying the implementation  a bit.

  public LocalRegion getRegion() {
    // The region will be null mostly for the other node where the gateway event
    // is serialized
    final GemFireCacheImpl cache;
    return this._region != null ? this._region
        : (this._region = ((cache = GemFireCacheImpl.getInstance()) != null
        ? (LocalRegion)cache.getRegion(this._regionName) : null));
  }

  public int getBucketId() {
    return bucketId;
  }

  /**
   * @param tailKey the tailKey to set
   */
  public void setShadowKey(Long tailKey) {
    this.shadowKey = tailKey;
  }

  /**
   * @return the tailKey
   */
  public Long getShadowKey() {
    return this.shadowKey;
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  @Override
  public boolean prefersNewSerialized() {
    return true;
  }

  @Override
  public boolean isUnretainedNewReferenceOk() {
    return false;
  }

  @Override
  public void importNewObject(Object nv, boolean isSerialized) {
    if (!isSerialized) {
      throw new IllegalStateException("For non-serialized data expected importNewBytes to be called.");
    }
    try {
      this._value = CacheServerHelper.serialize(nv);
    } catch (IOException e) {
      throw new SerializationException(LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING.toLocalizedString(), e);
    }
  }

  @Override
  public void importNewBytes(byte[] nv, boolean isSerialized) {
    if (!isSerialized) {
      this._valueIsObject = 0x00;
    }
    this._value = nv;
  }  

}
