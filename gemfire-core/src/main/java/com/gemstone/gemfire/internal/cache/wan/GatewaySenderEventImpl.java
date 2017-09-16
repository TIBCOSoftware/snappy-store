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

package com.gemstone.gemfire.internal.cache.wan;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEvent;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.cache.wan.EventSequenceID;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EnumListenerEvent;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.GatewayEventCallbackArgument;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InternalDeltaEvent;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.WrappedCallbackArgument;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.Releasable;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.ChunkWithHeapForm;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Class <code>GatewaySenderEventImpl</code> represents an event sent between
 * <code>GatewaySender</code>
 * 
 * @author Suranjan Kumar
 * 
 * @since 7.0
 * 
 */
public class GatewaySenderEventImpl implements
    AsyncEvent<Object, Object>, DataSerializableFixedID, Conflatable, Sizeable,
    InternalDeltaEvent, Releasable {

  private static final long serialVersionUID = -5690172020872255422L;

  protected static final short VERSION = 0x10;

  protected EnumListenerEvent operation;

  /**
   * The action to be taken (e.g. AFTER_CREATE)
   */
  protected int action;

  /**
   * The number of parts for the <code>Message</code>
   * 
   * @see com.gemstone.gemfire.internal.cache.tier.sockets.Message
   */
  protected int numberOfParts;

  /**
   * The identifier of this event
   */
  protected EventID id;

  /**
   * The <code>Region</code> that was updated
   */
  private transient LocalRegion region;

  /**
   * The name of the region being affected by this event
   */
  protected String regionPath;

  /**
   * The key being affected by this event
   */
  protected Object key;

  /**
   * The serialized new value for this event's key.
   * May not be computed at construction time.
   */
  protected byte[] value;
  
  /**
   * The "object" form of the value.
   * Will be null after this object is deserialized.
   */
  @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  protected transient Object valueObj;
  protected transient boolean valueObjReleased;

  /**
   * Whether the value is a serialized object or just a byte[]
   * 0x00 - the actual value is a plain byte[]
   * 0x01 - the value is a serialized object
   * 0x02 - some gemfirexd delta thing???
   */
  protected byte valueIsObject;

  /**
   * The callback argument for this event
   */
  protected GatewaySenderEventCallbackArgument callbackArgument;

  /**
   * The version timestamp
   */
  protected long versionTimeStamp;
  
  /**
   * Whether this event is a possible duplicate
   */
  protected boolean possibleDuplicate;

  /**
   * Whether this event is acknowledged after the ack received by
   * AckReaderThread. As of now this is getting used for PDX related
   * GatewaySenderEvent. But can be extended for for other GatewaySenderEvent.
   */
  protected boolean isAcked;
  
  /**
   * Whether this event is dispatched by dispatcher. As of now this is getting
   * used for PDX related GatewaySenderEvent. But can be extended for for other
   * GatewaySenderEvent.
   */
  protected boolean isDispatched;
  /**
   * The creation timestamp in ms
   */
  protected long creationTime;

  // bucketId is required for parallel WAN to work
  protected int bucketId;
  // shodowKey is required for parallel WAN to work 
  protected Long shadowKey = Long.valueOf(-1L);
  
  protected boolean isInitialized;

//  /**
//   * Is this thread in the process of serializing this event?
//   */
//  public static final ThreadLocal isSerializingValue = new ThreadLocal() {
//    @Override
//    protected Object initialValue() {
//      return Boolean.FALSE;
//    }
//  };

  private static final int CREATE_ACTION = 0;

  private static final int UPDATE_ACTION = 1;

  private static final int DESTROY_ACTION = 2;

  // Used by gemfirexd to send bulk DML across wan
  public static final int BULK_DML_OP_ACTION = 3; 

  private static final int VERSION_ACTION = 4;

  private static final int INVALIDATE_ACTION = 5;

//  /**
//   * Is this thread in the process of deserializing this event?
//   */
//  public static final ThreadLocal isDeserializingValue = new ThreadLocal() {
//    @Override
//    protected Object initialValue() {
//      return Boolean.FALSE;
//    }
//  };
//
//  /**
//   * Is this thread in the process of deserializing this event?
//   */
//  public static final ThreadLocal isDeserializingValue = new ThreadLocal() {
//    @Override
//    protected Object initialValue() {
//      return Boolean.FALSE;
//    }
//  };

  /**
   * Constructor. No-arg constructor for data serialization.
   * 
   * @see DataSerializer
   */
  public GatewaySenderEventImpl() {
  }

  /**
   * Constructor. Creates an initialized <code>GatewayEventImpl</code>
   * 
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>EntryEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   * 
   * @throws IOException
   */
  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation,
      EntryEventImpl event, boolean hasNonWanDispatcher) throws IOException {
    this(operation, event, true, hasNonWanDispatcher);
  }

  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation,
      EntryEventImpl event, boolean initialize, int bucketId, boolean hasNonWanDispatcher)
      throws IOException {
    this(operation, event, initialize, hasNonWanDispatcher);
    this.bucketId = bucketId;
  }

  /**
   * Constructor.
   * 
   * @param operation
   *          The operation for this event (e.g. AFTER_CREATE)
   * @param event
   *          The <code>EntryEvent</code> on which this
   *          <code>GatewayEventImpl</code> is based
   * @param initialize
   *          Whether to initialize this instance
   * 
   * @throws IOException
   */
  @Retained
  public GatewaySenderEventImpl(EnumListenerEvent operation,
      EntryEventImpl event, boolean initialize, boolean hasNonWanDispatcher) throws IOException {
    // Set the operation and event
    this.operation = operation;

    // Initialize the region name. This is being done here because the event
    // can get serialized/deserialized (for some reason) between the time
    // it is set above and used (in initialize). If this happens, the
    // region is null because it is a transient field of the event.
    this.region = event.getRegion();
    this.regionPath = this.region.getFullPath();

    // Initialize the unique id
    initializeId(event);

    // Initialize possible duplicate
    this.possibleDuplicate = event.isPossibleDuplicate();
    
    //Initialize ack and dispatch status of events
    this.isAcked = false;
    this.isDispatched = false;
    

    // Initialize the creation timestamp
    this.creationTime = System.currentTimeMillis();

    if (event.getVersionTag() != null) {
      this.versionTimeStamp = event.getVersionTag().getVersionTimeStamp();
    }

    // Set key
    // System.out.println("this._entryEvent: " + event);
    // System.out.println("this._entryEvent.getKey(): " +
    // event.getKey());
    this.key = event.getKey();

    initializeValue(event, hasNonWanDispatcher);

    // Set the callback arg
    Object eventCallbackArg = event.getRawCallbackArgument();
    if (eventCallbackArg instanceof GatewayEventCallbackArgument) {
      this.callbackArgument = (GatewaySenderEventCallbackArgument)
          ((GatewayEventCallbackArgument)eventCallbackArg)
          .getOriginalCallbackArg();
    }
    else {
      this.callbackArgument =
        (GatewaySenderEventCallbackArgument)eventCallbackArg;
    }

    // Initialize the action and number of parts (called after _callbackArgument
    // is set above)
    initializeAction(this.operation);
    
    setShadowKey(event.getTailKey());
    
    if (initialize) {
      initialize();
    }   
  }

  /**
   * Used to create a heap copy of an offHeap event.
   * Note that this constructor produces an instance that does not need to be released.
   */
  protected GatewaySenderEventImpl(GatewaySenderEventImpl offHeapEvent) {
    this.operation = offHeapEvent.operation;
    this.action = offHeapEvent.action;
    this.numberOfParts = offHeapEvent.numberOfParts;
    this.id = offHeapEvent.id;
    this.region = offHeapEvent.region;
    this.regionPath = offHeapEvent.regionPath;
    this.key = offHeapEvent.key;
    this.callbackArgument = offHeapEvent.callbackArgument;
    this.versionTimeStamp = offHeapEvent.versionTimeStamp;
    this.possibleDuplicate = offHeapEvent.possibleDuplicate;
    this.isAcked = offHeapEvent.isAcked;
    this.isDispatched = offHeapEvent.isDispatched;
    this.creationTime = offHeapEvent.creationTime;
    this.bucketId = offHeapEvent.bucketId;
    this.shadowKey = offHeapEvent.shadowKey;
    this.isInitialized = offHeapEvent.isInitialized;

    this.valueObj = null;
    this.valueObjReleased = false;
    this.valueIsObject = offHeapEvent.valueIsObject;
    this.value = offHeapEvent.getSerializedValue();
  }
  
  /**
   * Returns this event's action
   * 
   * @return this event's action
   */
  public int getAction() {
    return this.action;
  }

  /**
   * Returns this event's operation
   * 
   * @return this event's operation
   */
  public Operation getOperation() {
    Operation op = null;
    switch (this.action) {
    case CREATE_ACTION:
      op = Operation.CREATE;
      break;
    case UPDATE_ACTION:
      op = Operation.UPDATE;
      break;
    case DESTROY_ACTION:
      op = Operation.DESTROY;
      break;
    case BULK_DML_OP_ACTION:
      op = Operation.BULK_DML_OP;
      break;
    case VERSION_ACTION:
      op = Operation.UPDATE_VERSION_STAMP;
      break;
    case INVALIDATE_ACTION:
      op = Operation.INVALIDATE;
      break;
    }
    return op;
  }

  public EnumListenerEvent getEnumListenerEvent(){
    return this.operation;
  }
  /**
   * Return this event's region name
   * 
   * @return this event's region name
   */
  public String getRegionPath() {
    return this.regionPath;
  }

  public boolean isInitialized() {
    return this.isInitialized;
  }
  /**
   * Returns this event's key
   * 
   * @return this event's key
   */
  public Object getKey() {
    // TODO:Asif : Ideally would like to have throw exception if the key
    // is TOKEN_UN_INITIALIZED, but for the time being trying to retain the GFE
    // behaviour
    // of returning null if getKey is invoked on un-initialized gateway event
    return isInitialized() ? this.key : null;
  }

  /**
   * Returns whether this event's value is a serialized object. A return value
   * of 0 indicates that value is a raw byte[], that of 1 indicates an object
   * and 2 indicates an internal delta object.
   * 
   * @return whether this event's value is a serialized object
   */
  public byte getValueIsObject() {
    return this.valueIsObject;
  }

  @Override
  public boolean hasDelta() {
    return this.valueIsObject == 0x02;
  }

  @Override
  public boolean isGFXDCreate(boolean updateAsCreateOnlyForPosDup) {
    switch (this.action) {
      case CREATE_ACTION:
        return true;
      case UPDATE_ACTION:
        return !hasDelta();
      default:
        return false;
    }
  }

  /**
   * Return this event's callback argument
   * 
   * @return this event's callback argument
   */
  public Object getCallbackArgument() {
    Object result = getSenderCallbackArgument();
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument)result;
      result = wca.getOriginalCallbackArg();
    }
    return result;
  }

  public GatewaySenderEventCallbackArgument getSenderCallbackArgument() {
    return this.callbackArgument;
  }

  public void initializeKey() {
    if (this.key instanceof KeyWithRegionContext) {
      final LocalRegion r = getRegion();
      if (r == null) {
        // log a warning
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null && logger.warningEnabled()) {
          logger.warning(LocalizedStrings.DEBUG,
              "GatewaySenderEventImpl.initializeKey: region "
              + this.regionPath + " not found while initializing key "
              + this.key);
        }
      }
      else if (r.keyRequiresRegionContext()) {
        ((KeyWithRegionContext)this.key).setRegionContext(r);
      }
    }
  }

  /**
   * Return this event's number of parts
   * 
   * @return this event's number of parts
   */
  public int getNumberOfParts() {
    return this.numberOfParts;
  }
  
  /**
   * Return the value as a byte[] array, if it is plain byte array,
   * otherwise return a cache deserializable or plain object, depending
   * on if the currently held form of the object is serialized or not.
   * 
   * If the object is held off heap, this will copy it to the heap return the heap copy.
   * 
   *  //OFFHEAP TODO: Optimize callers by returning a reference to the off heap value
   */
  public Object getValue() {
    if (CachedDeserializableFactory.preferObject()) {
      // gfxd does not use CacheDeserializable wrappers
      return getDeserializedValue();
    }
    Object rawValue = this.value;
    if (rawValue == null) {
      @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      Object vo = this.valueObj;
      if (vo instanceof StoredObject) {
        rawValue = ((StoredObject) vo).getValueAsHeapByteArray();
      } else {
        rawValue = vo;
      }
    }
    if (valueIsObject == 0x00) {
      //if the value is a byte array, just return it
      return rawValue;
    } else if (rawValue instanceof byte[]) {
      return CachedDeserializableFactory.create((byte[]) rawValue);
    } else {
      return rawValue;
    }
  }
  
  /**
   * Return the currently held form of the object.
   * May return a retained OFF_HEAP_REFERENCE.
   */
  @Retained
  public Object getRawValue() {
    @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object result = this.value;
    if (result == null) {
      result = this.valueObj;
      if (result instanceof Chunk) {
        if (this.valueObjReleased) {
          result = null;
        } else {
          Chunk ohref = (Chunk) result;
          if (!ohref.retain()) {
            result = null;
          } else if (this.valueObjReleased) {
            ohref.release();
            result = null;
          }
        }
      }
    }
    return result;
  }

  /**
   * This method is meant for internal use by the SimpleMemoryAllocatorImpl.
   * Others should use getRawValue instead.
   * @return if the result is an off-heap reference then callers must use it before this event is released.
   */
  @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  public Object getValueObject() {
    return this.valueObj;
  }

  /**
   * Return this event's deserialized value
   * 
   * @return this event's deserialized value
   */
  public Object getDeserializedValue() {
    if (this.valueIsObject == 0x00) {
      Object result = this.value;
      if (result == null) {
        @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
        Object so = this.valueObj;
        if (this.valueObjReleased) {
          throw new IllegalStateException("Value is no longer available. getDeserializedValue must be called before processEvents returns.");
        }
        if (so instanceof StoredObject) {
          return ((StoredObject)so).getValueAsDeserializedHeapObject();
        } else {
          throw new IllegalStateException("expected valueObj field to be an instance of StoredObject but it was " + so);
        }
      }
      return result;
    }
    else {
      Object vo = this.valueObj;
      if (vo != null) {
        if (vo instanceof StoredObject) {
          @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
          StoredObject so = (StoredObject)vo;
          return so.getValueAsDeserializedHeapObject();
        } else {
          return vo; // it is already deserialized
        }
      } else {
        if (this.value != null) {
          Object result = EntryEventImpl.deserialize(this.value);
          this.valueObj = result;
          return result;
        } else {
          if (this.valueObjReleased) {
            throw new IllegalStateException("Value is no longer available. getDeserializedValue must be called before processEvents returns.");
          }
          // both value and valueObj are null but we did not free it.
          return null;
        }
      }
    }
  }
  
  /**
   * Returns the value in the form of a String.
   * This should be used by code that wants to log
   * the value. This is a debugging exception.
   */
  public String getValueAsString(boolean deserialize) {
    Object v = this.value;
    if (deserialize) {
      try {
        v = getDeserializedValue();
      } catch (Exception e) {
        return "Could not convert value to string because " + e;
      } catch (InternalGemFireError e) { // catch this error for bug 49147
        return "Could not convert value to string because " + e;
      }
    }
    if (v == null) {
      @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      Object ov = this.valueObj;
      if (ov instanceof CachedDeserializable) {
        return ((CachedDeserializable) ov).getStringForm();
      }
    }
    if (v != null) {
      if (v instanceof byte[]) {
        byte[] bav = (byte[]) v;
        // Using Arrays.toString(bav) can cause us to run out of memory
        return "byte[" + bav.length + "]";
      } else {
        return v.toString();
      }
    } else {
      return "";
    }
  }

  /**
   * If the value owned of this event is just bytes return that byte array;
   * otherwise serialize the value object and return the serialized bytes.
   * If the value is off heap, it does not get copied to the heap. Hence, 
   * every time this function is called, the value is copied from offheap to 
   * onheap. 
   * Use {@link #getValueIsObject()} to determine if the result is raw or serialized bytes.
   */
  public byte[] getSerializedValue() {
    byte[] result = this.value;
    if (result == null) {
      @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
      Object vo = this.valueObj;
      if (vo instanceof StoredObject) {
        synchronized (this) {
          result = this.value;
          if (result == null) {
            StoredObject so = (StoredObject) vo;
            result = so.getValueAsHeapByteArray();
          }
        }
      } else {
        synchronized (this) {
          result = this.value;
          if (result == null && vo != null && !(vo instanceof Token)) {
            result = EntryEventImpl.serialize(vo);
            this.value = result;
          } else if (result == null) {
            if (this.valueObjReleased) {
              throw new IllegalStateException("Value is no longer available. getSerializedValue must be called before processEvents returns.");
            }
          }
        }
      }
    }
    return result;
  }

  public void setPossibleDuplicate(boolean possibleDuplicate) {
    this.possibleDuplicate = possibleDuplicate;
    if (this.callbackArgument != null) {
      this.callbackArgument.setPossibleDuplicate(possibleDuplicate);
    }
  }

  public boolean getPossibleDuplicate() {
    return this.possibleDuplicate;
  }

  public long getCreationTime() {
    return this.creationTime;
  }

  public int getDSFID() {
    return GATEWAY_SENDER_EVENT_IMPL;
  }

  public void toData(DataOutput out) throws IOException {
    // Make sure we are initialized before we serialize.
    initialize();
    out.writeShort(VERSION);
    out.writeInt(this.action);
    out.writeInt(this.numberOfParts);
    // out.writeUTF(this._id);
    DataSerializer.writeObject(this.id, out);
    DataSerializer.writeString(this.regionPath, out);
    // serialize key values. A different function so that base classes can 
    // override this function implement their own serialization. 
    out.writeByte(this.valueIsObject);
    serializeKey(out);
    DataSerializer.writeByteArray(getSerializedValue(), out);
    DataSerializer.writeObject(this.callbackArgument, out);
    out.writeBoolean(this.possibleDuplicate);
    out.writeLong(this.creationTime);
    out.writeInt(this.bucketId);
    out.writeLong(this.shadowKey);
    out.writeLong(getVersionTimeStamp());    
  }

  protected void serializeKey(DataOutput out) throws IOException {
    DataSerializer.writeObject(this.key, out);
  }

  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    short version = in.readShort();
    if (version != VERSION) {
      // warning?
    }
    this.isInitialized = true;
    this.action = in.readInt();
    this.numberOfParts = in.readInt();
    // this._id = in.readUTF();
    this.id = (EventID)DataSerializer.readObject(in);
    // TODO:Asif ; Check if this violates Barry's logic of not assiging VM
    // specific Token.FROM_GATEWAY
    // and retain the serialized Token.FROM_GATEWAY
    // this._id.setFromGateway(false);
    this.regionPath = DataSerializer.readString(in);
    // deserialize key values. A different function so that base classes can 
    // override this function implement their own deserialization for key values. 
    this.valueIsObject = in.readByte();
    deserializeKey(in);
    this.value = DataSerializer.readByteArray(in);
    this.callbackArgument = (GatewaySenderEventCallbackArgument)DataSerializer
        .readObject(in);
    this.possibleDuplicate = in.readBoolean();
    if (this.callbackArgument != null) {
      this.callbackArgument.setPossibleDuplicate(this.possibleDuplicate);
    }
    this.creationTime = in.readLong();
    this.bucketId = in.readInt();
    this.shadowKey = in.readLong();
    this.versionTimeStamp = in.readLong();
  }

  protected void deserializeKey(DataInput in) throws IOException,
      ClassNotFoundException {
    this.key = DataSerializer.readObject(in);
  }

  @Override
  public int hashCode() {
    return this.id.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof GatewaySenderEventImpl) {
      GatewaySenderEventImpl otherEvent = (GatewaySenderEventImpl)other;
      return this.id.equals(otherEvent.id)
          && this.versionTimeStamp == otherEvent.versionTimeStamp;
    }
    else {
      return false;
    }
  }

  @Override
  public String toString() {
    StringBuilder buffer = new StringBuilder();
    buffer.append(getClass().getName() + "[").append("id=").append(this.id)
        .append(";action=").append(this.action).append(";operation=")
        .append(getOperation()).append(";region=").append(this.regionPath)
        .append(";key=").append(this.key).append(";value=")
        .append(getValueAsString(true)).append(";valueIsObject=")
        .append(this.valueIsObject).append(";numberOfParts=")
        .append(this.numberOfParts).append(";callbackArgument=")
        .append(this.callbackArgument).append(";possibleDuplicate=")
        .append(this.possibleDuplicate).append(";creationTime=")
        .append(this.creationTime)
        .append(";shadowKey=").append(this.shadowKey)
        .append(";timeStamp=").append(this.versionTimeStamp)
        .append("]");
    return buffer.toString();
  }

//  public static boolean isSerializingValue() {
//    return ((Boolean)isSerializingValue.get()).booleanValue();
//  }

//  public static boolean isDeserializingValue() {
//    return ((Boolean)isDeserializingValue.get()).booleanValue();
//  }

  // / Conflatable interface methods ///

  /**
   * Determines whether or not to conflate this message. This method will answer
   * true IFF the message's operation is AFTER_UPDATE and its region has enabled
   * are conflation. Otherwise, this method will answer false. Messages whose
   * operation is AFTER_CREATE, AFTER_DESTROY, AFTER_INVALIDATE or
   * AFTER_REGION_DESTROY are not conflated.
   * 
   * @return Whether to conflate this message
   */
  public boolean shouldBeConflated() {
    // The event may be conflated if any of the following is true
    // - the event is an update
    // - the region is empty and the event's operation is a create
    // - internal Delta objects cannot be conflated
    // Otherwise, the event is not conflatable.
    return !hasDelta() && (isUpdate() || (getRegion() != null
          && !getRegion().getAttributes().getDataPolicy().withStorage()
          && isCreate()));
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

  public String getRegionToConflate() {
    return this.regionPath;
  }

  public Object getKeyToConflate() {
    return this.key;
  }

  public Object getValueToConflate() {
    // Since all the uses of this are for logging
    // changing it to return the string form of the value
    // instead of the actual value.
    return this.getValueAsString(true);
  }

  public void setLatestValue(Object value) {
    // Currently this method is never used.
    // If someone does want to use it in the future
    // then the implementation needs to be updated
    // to correctly update value, valueObj, and valueIsObject
    throw new UnsupportedOperationException();
  }

  // / End Conflatable interface methods ///

  /**
   * Returns whether this <code>GatewayEvent</code> represents an update.
   * 
   * @return whether this <code>GatewayEvent</code> represents an update
   */
  protected boolean isUpdate() {
    // This event can be in one of three states:
    // - in memory primary (initialized)
    // - in memory secondary (not initialized)
    // - evicted to disk, read back in (initialized)
    // In the first case, both the operation and action are set.
    // In the second case, only the operation is set.
    // In the third case, only the action is set.
    return this.operation == null ? this.action == UPDATE_ACTION
        : this.operation == EnumListenerEvent.AFTER_UPDATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a create.
   * 
   * @return whether this <code>GatewayEvent</code> represents a create
   */
  protected boolean isCreate() {
    // See the comment in isUpdate() for additional details
    return this.operation == null ? this.action == CREATE_ACTION
        : this.operation == EnumListenerEvent.AFTER_CREATE;
  }

  /**
   * Returns whether this <code>GatewayEvent</code> represents a destroy.
   * 
   * @return whether this <code>GatewayEvent</code> represents a destroy
   */
  protected boolean isDestroy() {
    // See the comment in isUpdate() for additional details
    return this.operation == null ? this.action == DESTROY_ACTION
        : this.operation == EnumListenerEvent.AFTER_DESTROY;
  }

  /**
   * Initialize the unique identifier for this event. This id is used by the
   * receiving <code>Gateway</code> to keep track of which events have been
   * processed. Duplicates can be dropped.
   */
  private void initializeId(EntryEventImpl event) {
    // CS43_HA
    this.id = event.getEventId();
    // TODO:ASIF :Once stabilized remove the check below
    if (this.id == null) {
      throw new IllegalStateException(
          LocalizedStrings.GatewayEventImpl_NO_EVENT_ID_IS_AVAILABLE_FOR_THIS_GATEWAY_EVENT
              .toLocalizedString());
    }

  }

  /**
   * Initialize this instance. Get the useful parts of the input operation and
   * event.
   */
  public void initialize() {
    if (isInitialized()) {
      return;
    }
    this.isInitialized = true;
    // any initialization required for key
    initializeKey();
  }

  // Initializes the value object. This function need a relook because the 
  // serialization of the value looks unnecessary.
  @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  protected void initializeValue(EntryEventImpl event, boolean hasNonWanDispatcher) throws IOException {
    // Set the value to be a byte[] representation of the value. If the value
    // is already serialized, use it.
    this.valueIsObject = 0x01;
    /**
     * so ends up being stored in this.valueObj
     */
    @Retained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    StoredObject so = null;
    if (event.hasDelta() && !shouldApplyDelta()) {
      this.valueIsObject = 0x02;
    } else {
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(this);
      so = obtainOffHeapValueBasedOnOp(event, hasNonWanDispatcher);
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);      
    }
    
    if (so != null) {
//    if (so != null  && !event.hasDelta()) {
      // Since GatewaySenderEventImpl instances can live for a long time in the gateway region queue
      // we do not want the StoredObject to be one that keeps the heap form cached.
      if (so instanceof ChunkWithHeapForm) {
        throw new UnsupportedOperationException("ChunkWithHeapForm is currently not supported");
      }
      this.valueObj = so;
      if (!so.isSerialized()) {
        this.valueIsObject = 0x00;
      }
    } else {
      final Object val = obtainHeapValueBasedOnOp(event, hasNonWanDispatcher);
      assert !(val instanceof StoredObject); // since we already called getOffHeapNewValue() and it returned null
      if (val instanceof byte[]) {
        // The value is byte[]. Set _valueIsObject flag to 0x00 (not an object)
        this.value = (byte[])val;
        this.valueIsObject = 0x00;
      } else if (val instanceof CachedDeserializable) {
        this.value = ((CachedDeserializable) val).getSerializedValue();
      } else {
        // The value is an object. It will be serialized later when getSerializedValue is called.
        this.valueObj = val;
        // to prevent bug 48281 we need to serialize it now
        this.getSerializedValue();
        this.valueObj = null;
      }
    }
  }

  /**
   * This method is overridden in HDFSGatewayEventImpl
   * @param event
   * @return StoredObject for the offheap value
   */
  protected StoredObject obtainOffHeapValueBasedOnOp(EntryEventImpl event, boolean hasNonWanDispatcher) {
    return  (hasNonWanDispatcher && GemFireCacheImpl.gfxdSystem() && event.getOperation().isDestroy())  ? 
        event.getOffHeapOldValue() : event.getOffHeapNewValue();
  }
  
  /**
   * This method is Overridden in HDFSGatewayEventImpl
   * @param event
   * @return Object for the on heap value
   */
  protected Object obtainHeapValueBasedOnOp(EntryEventImpl event, boolean hasNonWanDispatcher) {
    return (hasNonWanDispatcher && GemFireCacheImpl.gfxdSystem() && event.getOperation().isDestroy()) ?
        event.getRawOldValue() : event.getRawNewValue(shouldApplyDelta());
  }
  /**
   * Should a gemfirexd delta be applied before capturing the new value
   * of the event. Overridden in HDFSGatewayEventImpl
   */
  protected boolean shouldApplyDelta() {
    return false;
  }

  /**
   * Initialize this event's action and number of parts
   * 
   * @param operation
   *          The operation from which to initialize this event's action and
   *          number of parts
   */
  protected void initializeAction(EnumListenerEvent operation) {
    if (operation == EnumListenerEvent.AFTER_CREATE) {
      // Initialize after create action
      this.action = CREATE_ACTION;

      // Initialize number of parts
      // part 1 = action
      // part 2 = posDup flag
      // part 3 = regionPath
      // part 4 = eventId
      // part 5 = key
      // part 6 = value (create and update only)
      // part 7 = whether callbackArgument is non-null
      // part 8 = callbackArgument (if non-null)
      // part 9 = versionTimeStamp;
      this.numberOfParts = (this.callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_UPDATE) {
      // Initialize after update action
      this.action = UPDATE_ACTION;

      // Initialize number of parts
      this.numberOfParts = (this.callbackArgument == null) ? 8 : 9;
    } else if (operation == EnumListenerEvent.AFTER_DESTROY) {
      // Initialize after destroy action
      this.action = DESTROY_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this.numberOfParts = (this.callbackArgument == null) ? 7 : 8;
    } else if (operation == EnumListenerEvent.AFTER_BULK_DML_OP) {
      // part 1 = action
      // part 2 = posDup flag
      // part 3 = StatementEvent
      // part 4 = callbackArg
      this.numberOfParts = 4;
      this.action = BULK_DML_OP_ACTION;     
    } else if (operation == EnumListenerEvent.TIMESTAMP_UPDATE) {
      // Initialize after destroy action
      this.action = VERSION_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this.numberOfParts = (this.callbackArgument == null) ? 7 : 8;
    } else if (operation == EnumListenerEvent.AFTER_INVALIDATE) {
      // Initialize after invalidate action
      this.action = INVALIDATE_ACTION;

      // Initialize number of parts
      // Since there is no value, there is one less part
      this.numberOfParts = (this.callbackArgument == null) ? 7 : 8;
    }
  }

  public EventID getEventId() {
    return this.id;
  }

  /**
   * Return the EventSequenceID of the Event
   * @return    EventSequenceID
   */
  public EventSequenceID getEventSequenceID() {
    return new EventSequenceID(id.getMembershipID(), id.getThreadID(), id
        .getSequenceID());
  }
  
  public long getVersionTimeStamp() {
    return this.versionTimeStamp;
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
    // - the region and regionPath because they are references
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
    // _id reference = 4 bytes
    // _region reference = 4 bytes
    // _regionPath reference = 4 bytes
    // _key reference = 4 bytes
    // _value reference = 4 bytes
    // _callbackArgument reference = 4 bytes
    // _operation reference = 4 bytes
    // _entryEvent reference = 4 bytes
    size += 32;

    // Add primitive references
    // int _action = 4 bytes
    // int _numberOfParts = 4 bytes
    // byte _valueIsObject = 1 byte
    // boolean _possibleDuplicate = 1 byte
    // long creationTime = 8 bytes
    // int bucketId = 4 bytes
    size += 22;

    // [sumedh] key and regionName are references on the source node, but not
    // on secondaries after deserialization
    Object o = this.key;
    if (o != null) {
      size += Sizeable.PER_OBJECT_OVERHEAD + sizeOf(o);
    }
    o = this.regionPath;
    if (o != null) {
      size += Sizeable.PER_OBJECT_OVERHEAD + sizeOf(o);
    }

    // Add the shadow key. Reference + Long object + long inside
    size += Sizeable.PER_OBJECT_OVERHEAD + 12;

    // Add the id (an instance of EventId)
    // The hardcoded value below was estimated using a NullDataOutputStream
    size += Sizeable.PER_OBJECT_OVERHEAD + 56;

    // The value (a byte[])
    size += getSerializedValueSize();

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
      size = 4; // estimate
    } else if (obj instanceof Long) {
      size = 8; // estimate
    } else {
      size = CachedDeserializableFactory.calcMemSize(obj)
          - Sizeable.PER_OBJECT_OVERHEAD;
    }
    return size;
  }

  // Asif: If the GatewayEvent serializes to a node where the region itself may
  // not be present or the region is not created yet , and if the gateway event
  // queue is persistent, then even if we try to set the region in the fromData,
  // we may still get null. Though the product is not using this method anywhere
  // still not comfortable changing the Interface so modifying the
  // implementation a bit.
  public LocalRegion getRegion() {
    // The region will be null mostly for the other node where the gateway event
    // is serialized
    final GemFireCacheImpl cache;
    return this.region != null ? this.region
        : (this.region = ((cache = GemFireCacheImpl.getInstance()) != null
        ? (LocalRegion)cache.getRegion(this.regionPath) : null));
  }

  public int getBucketId() {
    return bucketId;
  }

  /**
   * @param tailKey
   *          the tailKey to set
   */
  public void setShadowKey(long tailKey) {
    this.shadowKey = tailKey;
  }

  /**
   * @return the tailKey
   */
  public Long getShadowKey() {
    return this.shadowKey;
  }

  public int getSerializedValueSize() {
    @Unretained(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object vo = this.valueObj;
    if (vo instanceof StoredObject) {
      return ((StoredObject) vo).getSizeInBytes();
    } else {
      return CachedDeserializableFactory.calcMemSize(getSerializedValue());
    }
  }
  
  @Override
  @Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
  public void release() {
    @Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE)
    Object vo = this.valueObj;
    if (OffHeapHelper.releaseAndTrackOwner(vo, this)) {
      this.valueObj = null;
      this.valueObjReleased = true;
    }
  }
  
  public static void release(@Released(OffHeapIdentifier.GATEWAY_SENDER_EVENT_IMPL_VALUE) Object o) {
    if (o instanceof GatewaySenderEventImpl) {
      ((GatewaySenderEventImpl) o).release();
    }
  }

  /**
   * Make a heap copy of this off-heap event and return it.
   * A copy only needs to be made if the event's value is stored off-heap.
   * If it is already on the java heap then just return "this".
   * If it was stored off-heap and is no longer available (because it was released) then return null.
   */
  public GatewaySenderEventImpl makeHeapCopyIfOffHeap() {
    if (this.value != null) {
      // we have the value stored on the heap so return this
      return this;
    } else {
      Object v = this.valueObj;
      if (v == null) {
        if (this.valueObjReleased) {
          // this means that the original off heap value was freed
          return null;
        } else {
          return this;
        }
      }
      if (v instanceof Chunk) {
        try {
          return makeCopy();
        } catch (IllegalStateException ex) {
          // this means that the original off heap value was freed
          return null;
        }
      } else {
        // the valueObj does not use refCounts so just return this.
        return this;
      }
    }
  }
  
  protected GatewaySenderEventImpl makeCopy() {
    return new GatewaySenderEventImpl(this);
  }

  public void copyOffHeapValue() {
    if (this.value == null) {
      this.value = getSerializedValue();
    }
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
