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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ABSTRACT_REGION_ENTRY_FILL_IN_VALUE;
import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ABSTRACT_REGION_ENTRY_PREPARE_VALUE_FOR_CACHE;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexProtocol;
import com.gemstone.gemfire.cache.util.GatewayConflictHelper;
import com.gemstone.gemfire.cache.util.GatewayConflictResolver;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.QueuedSynchronizer;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
import com.gemstone.gemfire.internal.concurrent.MapCallback;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.MemoryAllocator;
import com.gemstone.gemfire.internal.offheap.OffHeapCachedDeserializable;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.GemFireChunk;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.gemstone.gemfire.internal.util.Versionable;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.pdx.PdxSerializable;
import com.gemstone.gemfire.pdx.PdxSerializationException;
import com.gemstone.gemfire.pdx.PdxSerializer;
import com.gemstone.gemfire.pdx.internal.ConvertableToBytes;
import com.gemstone.gemfire.pdx.internal.PdxInstanceImpl;

/**
 * Abstract implementation class of RegionEntry interface.
 * This is the topmost implementation class so common behavior
 * lives here.
 *
 * @since 3.5.1
 *
 * @author Darrel Schneider
 * @author bruce
 *
 */
public abstract class AbstractRegionEntry extends ExclusiveSharedSynchronizer
    implements RegionEntry, HashEntry<Object, Object>,
    ExclusiveSharedLockObject {

  private static final long serialVersionUID = -2017968895983070356L;

  /**
   * Whether to disable last access time update when a put occurs. The default
   * is false (enable last access time update on put). To disable it, set the
   * 'gemfire.disableAccessTimeUpdateOnPut' system property.
   */
  protected static final boolean DISABLE_ACCESS_TIME_UPDATE_ON_PUT = Boolean
      .getBoolean("gemfire.disableAccessTimeUpdateOnPut");

  private static boolean VERBOSE = Boolean.getBoolean("gemfire.AbstractRegionEntry.DEBUG");

//  public Exception removeTrace; // debugging hot loop in AbstractRegionMap.basicPut()

  /** used by GemFireXD entry implementations that don't store key separately */
  public static final int MAX_READ_TRIES = 10000000;
  public static final int MAX_READ_TRIES_YIELD = 1000;
  
  protected AbstractRegionEntry(RegionEntryContext context,
      @Retained(ABSTRACT_REGION_ENTRY_PREPARE_VALUE_FOR_CACHE) Object value) {
    
    setValue(context,this.prepareValueForCache(context, value, false, false),false);
//    setLastModified(System.currentTimeMillis()); [bruce] this must be set later so we can use ==0 to know this is a new entry in checkForConflicts
  }

  /////////////////////////////////////////////////////////////
  /////////////////////////// fields //////////////////////////
  /////////////////////////////////////////////////////////////
  // Do not add any instance fields to this class.
  // Instead add them to LeafRegionEntry.cpp
  
  /////////////////////////////////////////////////////////////////////
  ////////////////////////// instance methods /////////////////////////
  /////////////////////////////////////////////////////////////////////

  public boolean dispatchListenerEvents(final EntryEventImpl event) throws InterruptedException {
    final LocalRegion rgn = event.getRegion();
    final LogWriterI18n lw = rgn.getCache().getLoggerI18n();

    if (event.callbacksInvoked()) {
       return true;
    }

    // don't wait for certain events to reach the head of the queue before
    // dispatching listeners. However, we must not notify the gateways for
    // remote-origin ops out of order. Otherwise the other systems will have
    // inconsistent content.

    event.setCallbacksInvokedByCurrentThread();

    if (VERBOSE) {
      lw.info(LocalizedStrings.DEBUG, this.toString() + " dispatching event " + event);
    }
    // All the following code that sets "thr" is to workaround
    // spurious IllegalMonitorStateExceptions caused by JVM bugs.
    try {
      // call invokeCallbacks while synced on RegionEntry
      event.invokeCallbacks(rgn, event.inhibitCacheListenerNotification(), false);
      return true;

    } finally {
      if (isRemoved() && !isTombstone() && !event.isEvicted()) {
        // Phase 2 of region entry removal is done here. The first phase is done
        // by the RegionMap. It is unclear why this code is needed. ARM destroy
        // does this also and we are now doing it as phase3 of the ARM destroy.
        removePhase2(rgn);
        rgn.getRegionMap().removeEntry(event.getKey(), this, true, event, rgn, null);
      }
    }
  }

  public long getLastAccessed() throws InternalStatisticsDisabledException {
    throw new InternalStatisticsDisabledException();
  }
    
  public long getHitCount() throws InternalStatisticsDisabledException {
    throw new InternalStatisticsDisabledException();
  }
    
  public long getMissCount() throws InternalStatisticsDisabledException {
    throw new InternalStatisticsDisabledException();
  }

  @Override
  public void setLastModified(long lastModified) {
    _setLastModified(lastModified);
  }        

  public void txDidDestroy(long currTime) {
    setLastModified(currTime);
  }
  
  public final void updateStatsForPut(long lastModifiedTime) {
    setLastModified(lastModifiedTime);
  }
  
  public void updateStatsForGet(boolean hit, long time) {
    // nothing needed
  }

  public void resetCounts() throws InternalStatisticsDisabledException {
    throw new InternalStatisticsDisabledException();
  }
    
  public void _removePhase1(LocalRegion r) {
    _setValue(r, Token.REMOVED_PHASE1);
    // debugging for 38467 (hot thread in ARM.basicUpdate)
//    this.removeTrace = new Exception("stack trace for thread " + Thread.currentThread());
  }
  public void removePhase1(LocalRegion r, boolean isClear) throws RegionClearedException {
    _removePhase1(r);
  }
  
  public void removePhase2(LocalRegion r) {
    _setValue(r, Token.REMOVED_PHASE2);
//    this.removeTrace = new Exception("stack trace for thread " + Thread.currentThread());
  }

  public void makeTombstone(LocalRegion r, VersionTag version) throws RegionClearedException {
    assert r.getVersionVector() != null;
    assert version != null;
    if (r.getServerProxy() == null &&
        r.getVersionVector().isTombstoneTooOld(version.getMemberID(), version.getRegionVersion())) {
      // distributed gc with higher vector version preempts this operation
      if (!isTombstone()) {
        setValue(r, Token.TOMBSTONE);
        r.incTombstoneCount(1);
      }
      r.getRegionMap().removeTombstone(this, version, false, true);
    } else {
      if (isTombstone()) {
        // unschedule the old tombstone
        r.unscheduleTombstone(this);
      }
      setRecentlyUsed();
      boolean newEntry = (getValueAsToken() == Token.REMOVED_PHASE1);
      setValue(r, Token.TOMBSTONE);
      r.scheduleTombstone(this, version);
      if (newEntry) {
        // bug #46631 - entry count is decremented by scheduleTombstone but this is a new entry
        r.getCachePerfStats().incEntryCount(1);
      }
    }
  }
  

  @Override
  public void setValueWithTombstoneCheck(@Unretained Object v, EntryEvent e) throws RegionClearedException {
    if (v == Token.TOMBSTONE) {
      makeTombstone((LocalRegion)e.getRegion(), ((EntryEventImpl)e).getVersionTag());
    } else {
      setValue((LocalRegion)e.getRegion(), v);
    }
  }
  
  /**
   * Return true if the object is removed.
   * 
   * TODO this method does NOT return true if the object
   * is Token.DESTROYED. dispatchListenerEvents relies on that
   * fact to avoid removing destroyed tokens from the map.
   * We should refactor so that this method calls Token.isRemoved,
   * and places that don't want a destroyed Token can explicitly check
   * for a DESTROY token.
   */
  public boolean isRemoved() {
    Token o = getValueAsToken();
    return (o == Token.REMOVED_PHASE1) || (o == Token.REMOVED_PHASE2) || (o == Token.TOMBSTONE);
  }

  public boolean isDestroyedOrRemoved() {
    return Token.isRemoved(getValueAsToken());
  }
  
  public boolean isDestroyedOrRemovedButNotTombstone() {
    Token o = getValueAsToken();
    return o == Token.DESTROYED || o == Token.REMOVED_PHASE1 || o == Token.REMOVED_PHASE2;
  }
  
  public final boolean isTombstone() {
    return getValueAsToken() == Token.TOMBSTONE;
  }
  
  public final boolean isRemovedPhase2() {
    return getValueAsToken() == Token.REMOVED_PHASE2;
  }
  
  public boolean fillInValue(LocalRegion region,
                             @Retained(ABSTRACT_REGION_ENTRY_FILL_IN_VALUE) InitialImageOperation.Entry dst,
                             DM mgr, Version targetVersion)
  {
    dst.setSerialized(false); // starting default value

    @Retained(ABSTRACT_REGION_ENTRY_FILL_IN_VALUE) final Object v;
    if (isTombstone()) {
      v = Token.TOMBSTONE;
    } else {
      v = getValue(region); // OFFHEAP: need to incrc, copy bytes, decrc
      if (v == null) {
        return false;
      }
    }

    dst.setLastModified(mgr, getLastModified()); // fix for bug 31059
    final Class<?> vclass;
    if (v == Token.INVALID) {
      dst.setInvalid();
    }
    else if (v == Token.LOCAL_INVALID) {
      dst.setLocalInvalid();
    }
    else if (v == Token.TOMBSTONE) {
      dst.setTombstone();
    }
    else if ((vclass = v.getClass()) == byte[].class) {
      dst.value = v;
    }
    else if (vclass == byte[][].class) {
      if (CachedDeserializableFactory.preferObject()) {
        dst.value = v;
        dst.setEagerDeserialize();
      }
      else {
        serializeForEntry(dst, v);
      }
    }
    else if (CachedDeserializable.class.isAssignableFrom(vclass)) {
      // don't serialize here if it is not already serialized
      if (CachedDeserializableFactory.preferObject()) {
        // For GemFireXD we prefer eager deserialized
        dst.setEagerDeserialize();
      }

      if (StoredObject.class.isAssignableFrom(vclass)
          && !((StoredObject)v).isSerialized()) {
        dst.value = ((StoredObject)v).getDeserializedForReading();
      }
      else {
        if (CachedDeserializableFactory.preferObject()) {
          dst.value = v;
        }
        else {
          Object tmp = ((CachedDeserializable)v).getValue();
          if (tmp instanceof byte[]) {
            byte[] bb = (byte[])tmp;
            dst.value = bb;
            dst.setSerialized(true);
          }
          else {
            serializeForEntry(dst, tmp);
          }
        }
      }
    }
    else { 
      Object preparedValue = prepareValueForGII(v, vclass);
      if (preparedValue == null) {
        return false;
      }
      if (CachedDeserializableFactory.preferObject()) {
        dst.value = preparedValue;
        dst.setEagerDeserialize();
      }
      else {
        serializeForEntry(dst, preparedValue);
      }
    }
    return true;
  }

  private static void serializeForEntry(InitialImageOperation.Entry dst,
      Object v) {
    try {
      HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
      BlobHelper.serializeTo(v, hdos);
      hdos.trim();
      dst.value = hdos;
      dst.setSerialized(true);
    } catch (IOException e) {
      RuntimeException e2 = new IllegalArgumentException(
          LocalizedStrings.AbstractRegionEntry_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING
              .toLocalizedString());
      e2.initCause(e);
      throw e2;
    }
  }

  /**
   * To fix bug 49901 if v is a GatewaySenderEventImpl then make
   * a heap copy of it if it is offheap.
   * @return the value to provide to the gii request; null if no value should be provided.
   */
  public static Object prepareValueForGII(Object v) {
    assert v != null;
    if (v instanceof GatewaySenderEventImpl) {
      return ((GatewaySenderEventImpl) v).makeHeapCopyIfOffHeap();
    } else {
      return v;
    }
  }
  
  /**
   * To fix bug 49901 if v is a GatewaySenderEventImpl then make
   * a heap copy of it if it is offheap.
   * @return the value to provide to the gii request; null if no value should be provided.
   */
  public static Object prepareValueForGII(Object v, Class<?> vclass) {
    assert v != null;
    if (GatewaySenderEventImpl.class.isAssignableFrom(vclass)) {
      return ((GatewaySenderEventImpl) v).makeHeapCopyIfOffHeap();
    } else {
      return v;
    }
  }

  public boolean isOverflowedToDisk(LocalRegion r, DistributedRegion.DiskPosition dp) {
    return false;
  }

  @Override
  @Retained
  public Object getValue(RegionEntryContext context) {
    SimpleMemoryAllocatorImpl.createReferenceCountOwner();
    @Retained Object result = _getValueRetain(context, true);
    //Asif: If the thread is an Index Creation Thread & the value obtained is 
    //Token.REMOVED , we can skip  synchronization block. This is required to prevent
    // the dead lock caused if an Index Update Thread has gone into a wait holding the
    // lock of the Entry object. There should not be an issue if the Index creation thread
    // gets the temporary value of token.REMOVED as the  correct value will get indexed
    // by the Index Update Thread , once the index creation thread has exited.
    // Part of Bugfix # 33336
//    if ((result == Token.REMOVED_PHASE1 || result == Token.REMOVED_PHASE2) && !r.isIndexCreationThread()) {
//      synchronized (this) {
//        result = _getValue();
//      }
//    }
    
    if (Token.isRemoved(result)) {
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
      return null;
    } else {
      result = OffHeapHelper.copyAndReleaseIfNeeded(result); // gfxd does not dec ref count in this call
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
      setRecentlyUsed();
      return result;
    }
  }
  
  @Released
  public void setValue(RegionEntryContext context, @Unretained Object value) throws RegionClearedException {
    // @todo darrel: This will mark new entries as being recently used
    // It might be better to only mark them when they are modified.
    // Or should we only mark them on reads?
    setValue(context,value,true);
  }
  
  @Released
  protected void setValue(RegionEntryContext context, @Unretained Object value, boolean recentlyUsed) {
    _setValue(context, value);
    if (value != null && context != null && context instanceof LocalRegion
        && ((LocalRegion)context).isThisRegionBeingClosedOrDestroyed()
        && isOffHeap()) {
      release();
      ((LocalRegion)context).checkReadiness();
    }
    if (recentlyUsed) {
      setRecentlyUsed();
    }
  }

  /**
   * This method determines if the value is in a compressed representation and decompresses it if it is.
   *
   * @param context the values context. 
   * @param value a region entry value.
   * 
   * @return the decompressed form of the value parameter.
   */
  static Object decompress(RegionEntryContext context,Object value) {
    if(isCompressible(context, value)) {
      long time = context.getCachePerfStats().startDecompression();
      value = EntryEventImpl.deserialize(context.getCompressor().decompress((byte[]) value));
      context.getCachePerfStats().endDecompression(time);      
    }
    
    return value;
  }
  
  /**
   * This method determines if the value is compressible and compresses it if it is.
   *
   * @param context the values context. 
   * @param value a region entry value.
   * 
   * @return the compressed form of the value parameter.
   */
  static protected Object compress(RegionEntryContext context,Object value) {
    if(isCompressible(context, value)) {
      long time = context.getCachePerfStats().startCompression();
      byte[] serializedValue = EntryEventImpl.serialize(value);
      value = context.getCompressor().compress(serializedValue);
      context.getCachePerfStats().endCompression(time, serializedValue.length, ((byte []) value).length);
    }
    
    return value;    
  }
  
  @Retained
  public final Object getValueInVM(RegionEntryContext context) {
    SimpleMemoryAllocatorImpl.createReferenceCountOwner();
    @Retained Object v = _getValueRetain(context, true);
    
    if (v == null) { // should only be possible if disk entry
      v = Token.NOT_AVAILABLE;
    }
    @Retained Object result = OffHeapHelper.copyAndReleaseIfNeeded(v); // TODO OFFHEAP keep it offheap?
    SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
    return result;
  }

  @Retained
  public  Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
    Object v = getValueInVM(owner);
    if (GemFireCacheImpl.hasNewOffHeap() && (v instanceof SerializedDiskBuffer)) {
      ((SerializedDiskBuffer)v).retain();
    }
    return v;
  }

  @Retained
  public Object getHeapValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
    final Object v;
    if (owner.compressor == null) {
      v = _getValue();
      // null should only be possible if disk entry
      return v != null ? v : Token.NOT_AVAILABLE;
    } else {
      v = decompress(owner, _getValue());
      return v != null ? v : Token.NOT_AVAILABLE;
    }
  }

  @Override
  @Retained
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
    @Retained Object result = _getValueRetain(owner, true);
    if (result instanceof ByteSource) {
      // If the ByteSource contains a Delta or ListOfDelta then we want to deserialize it
      final ByteSource bs = (ByteSource)result;
      Object deserVal = bs.getDeserializedForReading();
      if (deserVal != result) {
        bs.release();
        result = deserVal;
      }
    }
    return result;
  }

  /**
   * Gets the value for this entry. For DiskRegions, unlike
   * {@link #getValue(RegionEntryContext)} this will not fault in the value rather
   * return a temporary copy. For GemFire XD this is used during table scans in
   * queries when faulting in every value will be only an unnecessary overhead.
   * The value returned will be kept off heap (and compressed) if possible.
   */
  @Retained
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner, DiskRegion dr) {
    return getValueOffHeapOrDiskWithoutFaultIn(owner);
  }

  public Object getValueOnDisk(LocalRegion r)
  throws EntryNotFoundException
  {
    throw new IllegalStateException(LocalizedStrings.AbstractRegionEntry_CANNOT_GET_VALUE_ON_DISK_FOR_A_REGION_THAT_DOES_NOT_ACCESS_THE_DISK.toLocalizedString());
  }

  public Object getSerializedValueOnDisk(final LocalRegion r)
  throws EntryNotFoundException
  {
    throw new IllegalStateException(LocalizedStrings.AbstractRegionEntry_CANNOT_GET_VALUE_ON_DISK_FOR_A_REGION_THAT_DOES_NOT_ACCESS_THE_DISK.toLocalizedString());
  }
  
 public Object getValueOnDiskOrBuffer(LocalRegion r)
  throws EntryNotFoundException
 {
  throw new IllegalStateException(LocalizedStrings.AbstractRegionEntry_CANNOT_GET_VALUE_ON_DISK_FOR_A_REGION_THAT_DOES_NOT_ACCESS_THE_DISK.toLocalizedString());
  // @todo darrel if value is Token.REMOVED || Token.DESTROYED throw EntryNotFoundException
 }

  public final boolean initialImagePut(final LocalRegion region,
                                       final long lastModifiedTime,
                                       Object newValue,
                                       boolean wasRecovered,
                                       boolean versionTagAccepted) throws RegionClearedException
  {
    // note that the caller has already write synced this RegionEntry
    return initialImageInit(region, lastModifiedTime, newValue, this.isTombstone(), wasRecovered, versionTagAccepted);
  }

  public boolean initialImageInit(final LocalRegion region,
                                        final long lastModifiedTime,
                                        final Object newValue,
                                        final boolean create,
                                        final boolean wasRecovered,
                                        final boolean versionTagAccepted) throws RegionClearedException
  {
    final LogWriterI18n logger = region.getCache().getLoggerI18n();
    // note that the caller has already write synced this RegionEntry
    boolean result = false;
    // if it has been destroyed then don't do anything
    Token vTok = getValueAsToken();
    if (InitialImageOperation.TRACE_GII_FINER) {
      logger.info(LocalizedStrings.DEBUG,
          "ARE.initilaImageInit called newVAlue=" + newValue + ", create="
              + create + ",wasRecovered=" + wasRecovered
              + ", versionTagAccepted=" + versionTagAccepted + ",vTok=" + vTok);
    }
    if (versionTagAccepted || create || (vTok != Token.DESTROYED || vTok != Token.TOMBSTONE)) { // OFFHEAP noop
      Object newValueToWrite = newValue;
      boolean putValue = versionTagAccepted || create
        || (newValueToWrite != Token.LOCAL_INVALID
            && (wasRecovered || (vTok == Token.LOCAL_INVALID))); // OFFHEAP noop
    
      if (region.isUsedForPartitionedRegionAdmin() && newValueToWrite instanceof CachedDeserializable) {
        // Special case for partitioned region meta data
        // We do not need the RegionEntry on this case.
        // Because the pr meta data region will not have an LRU.
        newValueToWrite = ((CachedDeserializable) newValueToWrite).getDeserializedValue(region, null);
        if (!create && newValueToWrite instanceof Versionable) {
          @Retained @Released final Object oldValue = getValueInVM(region); // Heap value should always be deserialized at this point // OFFHEAP will not be deserialized
          try {
          // BUGFIX for 35029. If oldValue is null the newValue should be put.
          if(oldValue == null) {
          	putValue = true;
          }
          else if (oldValue instanceof Versionable) {
            Versionable nv = (Versionable) newValueToWrite;
            Versionable ov = (Versionable) oldValue;
            putValue = nv.isNewerThan(ov);
          }  
          } finally {
            OffHeapHelper.release(oldValue);
          }
        }
      }

      if (InitialImageOperation.TRACE_GII_FINER) {
        logger.info(LocalizedStrings.DEBUG,
            "ARE.initilaImageInit called newVAlue=" + newValue + ", create="
                + create + ",wasRecovered=" + wasRecovered
                + ", versionTagAccepted=" + versionTagAccepted + ",vTok="
                + vTok);
      }
      if (putValue) {
        // change to INVALID if region itself has been invalidated,
        // and current value is recovered
        if (create || versionTagAccepted) {
          // At this point, since we now always recover from disk first,
          // we only care about "isCreate" since "isRecovered" is impossible
          // if we had a regionInvalidate or regionClear
          ImageState imageState = region.getImageState();
          // this method is called during loadSnapshot as well as getInitialImage
          if (imageState.getRegionInvalidated()) {
            if (newValueToWrite != Token.TOMBSTONE) {
              newValueToWrite = Token.INVALID;
            }
          }
          else if (imageState.getClearRegionFlag()) {
            boolean entryOK = false;
            RegionVersionVector rvv = imageState.getClearRegionVersionVector();
            if (rvv != null) { // a filtered clear
              VersionSource id = getVersionStamp().getMemberID();
              if (id == null) {
                id = region.getVersionMember();
              }
              if (!rvv.contains(id, getVersionStamp().getRegionVersion())) {
                entryOK = true;
              }
            }
            if (InitialImageOperation.TRACE_GII_FINER) {
              logger.info(LocalizedStrings.DEBUG,"ARE.initilaImageInit rvv="+rvv+", ebtryOk="+entryOK);
            }
            
            if (!entryOK) {
              //Asif: If the region has been issued cleared during
              // the GII , then those entries loaded before this one would have
              // been cleared from the Map due to clear operation & for the
              // currententry whose key may have escaped the clearance , will be
              // cleansed by the destroy token.
              newValueToWrite = Token.DESTROYED;
              imageState.addDestroyedEntry(this.getKeyCopy());
              throw new RegionClearedException(LocalizedStrings.AbstractRegionEntry_DURING_THE_GII_PUT_OF_ENTRY_THE_REGION_GOT_CLEARED_SO_ABORTING_THE_OPERATION.toLocalizedString());
            }
          }
        }
        if (InitialImageOperation.TRACE_GII_FINER) {
          logger.info(LocalizedStrings.DEBUG,"ARE.initilaImageInit set value called newValToWrite="+newValueToWrite);
        }
        setValue(region, this.prepareValueForCache(region, newValueToWrite, false, false));
        result = true;

        if (newValueToWrite != Token.TOMBSTONE){
          if (create) {
            region.getCachePerfStats().incCreates();
          }
          region.updateStatsForPut(this, lastModifiedTime, false);
        }
        
        //final LogWriterI18n logger = region.getCache().getLoggerI18n();
        if (logger.finerEnabled()) {
          if (newValueToWrite instanceof CachedDeserializable) {
            logger.finer("ProcessChunk: region=" +
                         region.getFullPath() +
                         "; put a CachedDeserializable (" +
                         getKeyCopy() + "," +
                         ((CachedDeserializable)newValueToWrite).getStringForm() +
                         ")");
          }
          else {
            logger.finer("ProcessChunk: region=" +
                         region.getFullPath() +
                         "; put(" +
                         getKeyCopy() + "," + LogWriterImpl.forceToString(newValueToWrite) + ")");
          }
        }
      }
    }
    return result;
  }
 
  /**
   * @throws EntryNotFoundException if expectedOldValue is
   * not null and is not equal to current value
   */
  @Released
  public boolean destroy(LocalRegion region,
                            EntryEventImpl event,
                            boolean inTokenMode,
                            boolean cacheWrite,
                            @Unretained Object expectedOldValue,
                            boolean createdForDestroy,
                            boolean removeRecoveredEntry)
    throws CacheWriterException,
           EntryNotFoundException,
           TimeoutException,
           RegionClearedException {
    boolean proceed = false;
    {
    // A design decision was made to not retrieve the old value from the disk
    // if the entry has been evicted to only have the CacheListener afterDestroy
    // method ignore it. We don't want to pay the performance penalty. The 
    // getValueInVM method does not retrieve the value from disk if it has been
    // evicted. Instead, it uses the NotAvailable token.
    //
    // If the region is a WAN queue region, the old value is actually used by the 
    // afterDestroy callback on a secondary. It is not needed on a primary.
    // Since the destroy that sets WAN_QUEUE_TOKEN always originates on the primary
    // we only pay attention to WAN_QUEUE_TOKEN if the event is originRemote.
    //
    // :ezoerner:20080814 We also read old value from disk or buffer
    // in the case where there is a non-null expectedOldValue
    // see PartitionedRegion#remove(Object key, Object value)
    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    @Retained @Released Object curValue = _getValueRetain(region, true);
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    try {
    if (curValue == null) curValue = Token.NOT_AVAILABLE;
    
    if (curValue == Token.NOT_AVAILABLE) {
      // In some cases we need to get the current value off of disk.
      
      // if the event is transmitted during GII and has an old value, it was
      // the state of the transmitting cache's entry & should be used here
      if (event.getCallbackArgument() != null
          && event.getCallbackArgument().equals(RegionQueue.WAN_QUEUE_TOKEN)
          && event.isOriginRemote()) { // check originRemote for bug 40508
        //curValue = getValue(region); can cause deadlock if GII is occurring
        curValue = getValueOnDiskOrBuffer(region);
      } 
      else {
        FilterProfile fp = region.getFilterProfile();
        // rdubey: Old value also required for GfxdIndexManager.
        if (fp != null && ((fp.getCqCount() > 0) || expectedOldValue != null
            || event.getRegion().getIndexUpdater() != null)) {
          //curValue = getValue(region); can cause deadlock will fault in the value
          // and will confuse LRU. rdubey.
          curValue = getValueOnDiskOrBuffer(region);
        }      
      }
    }

//    InternalDistributedSystem.getLoggerI18n().info(LocalizedStrings.DEBUG,
//        "for key " + this.key + ", expectedOldValue = " + expectedOldValue
//        + " and current value = " + curValue);

    if (expectedOldValue != null) {
      if (!checkExpectedOldValue(expectedOldValue, curValue)) {
            // fail, old value not equal to expected old value
//        LogWriterI18n log = InternalDistributedSystem.getLoggerI18n();
//        if (log.fineEnabled() ){
//          log.fine("remove operation failed with expectedValue=" +
//              expectedOldValue + " and actual value=" + curValue);
//        }
        throw new EntryNotFoundException(
          LocalizedStrings.AbstractRegionEntry_THE_CURRENT_VALUE_WAS_NOT_EQUAL_TO_EXPECTED_VALUE.toLocalizedString());
      }
    }

    if (inTokenMode && event.hasOldValue()) {
      proceed = true;
    }
    else {
      proceed = event.setOldValue(curValue, curValue instanceof GatewaySenderEventImpl) || removeRecoveredEntry
                || createdForDestroy || region.getConcurrencyChecksEnabled() // fix for bug #47868 - create a tombstone
                || (event.getOperation() == Operation.REMOVE // fix for bug #42242
                    && (curValue == null || curValue == Token.LOCAL_INVALID
                        || curValue == Token.INVALID));
    }
    } finally {
      OffHeapHelper.releaseWithNoTracking(curValue);
    }
    } // end curValue block
    
    if (proceed) {
      //Generate the version tag if needed. This method should only be 
      //called if we are in fact going to destroy the entry, so it must be
      //after the entry not found exception above.
      if(!removeRecoveredEntry) {
        region.generateAndSetVersionTag(event, this);
      }
      if (cacheWrite) {
        region.cacheWriteBeforeDestroy(event, expectedOldValue);
        if (event.getRegion().getServerProxy() != null) { // server will return a version tag
          // update version information (may throw ConcurrentCacheModificationException)
          VersionStamp stamp = getVersionStamp();
          if (stamp != null) {
            stamp.processVersionTag(event);
          }
        }
      }
      region.recordEvent(event);
      // don't do index maintenance on a destroy if the value in the
      // RegionEntry (the old value) is invalid
      if (!region.isProxy() && !isInvalid()) {
        IndexManager indexManager = region.getIndexManager();
        if (indexManager != null) {
          try {
            if(isValueNull()) {
              @Released Object value = getValueOffHeapOrDiskWithoutFaultIn(region);
              try {
              _setValue(region, prepareValueForCache(region, value, false, false));
              if (value != null && region != null && isOffHeap() && region.isThisRegionBeingClosedOrDestroyed()) {
                ((OffHeapRegionEntry)this).release();
                region.checkReadiness();
              }
              } finally {
                OffHeapHelper.release(value);
              }
            }
            indexManager.updateIndexes(this,
                IndexManager.REMOVE_ENTRY,
                IndexProtocol.OTHER_OP);
          }
          catch (QueryException e) {
            throw new IndexMaintenanceException(e);
          }
        }
        IndexUpdater gfxdIndexManager = region.getIndexUpdater();
        if (gfxdIndexManager != null && !createdForDestroy) {
          boolean hasOldValue = event.hasOldValue();
          boolean isOldValueAToken;
          boolean isOldValueNull;
          if (hasOldValue) {
            isOldValueNull = false;
            isOldValueAToken = event.isOldValueAToken();
          }
          else {
            @Released Object value = getValueOffHeapOrDiskWithoutFaultIn(region);
            try {
              isOldValueNull = value == null;
              isOldValueAToken = value instanceof Token;
              if (!isOldValueNull && !isOldValueAToken) {
                event.setOldValue(value,true);
              }
            } finally {
              OffHeapHelper.release(value);
            }
          }
          if (!isOldValueNull && !isOldValueAToken) {
            boolean success = false;
            try {
              gfxdIndexManager.onEvent(region, event, this);
              success = true;
            } finally {
              gfxdIndexManager.postEvent(region, event, this, success);
            }
          }
        }
      }

      boolean removeEntry = false;
      VersionTag v = event.getVersionTag();
      if (region.concurrencyChecksEnabled && !removeRecoveredEntry
          && !event.isFromRILocalDestroy()) { // bug #46780, don't retain tombstones for entries destroyed for register-interest
        // Destroy will write a tombstone instead 
        if (v == null || !v.hasValidVersion()) { 
          // localDestroy and eviction and ops received with no version tag
          // should create a tombstone using the existing version stamp, as should
          // (bug #45245) responses from servers that do not have valid version information
          VersionStamp stamp = this.getVersionStamp();
          if (stamp != null) {  // proxy has no stamps
            v = stamp.asVersionTag();
            event.setVersionTag(v);
          }
        }
        removeEntry = (v == null) || !v.hasValidVersion();
      } else {
        removeEntry = true;
      }

      // See #47887, we do not insert a tombstone for evicted HDFS
      // entries since the value is still present in HDFS
      // Check if we have to evict or just do destroy.
      boolean forceRemoveEntry = 
          (event.isEviction() || event.isExpiration()) 
          && event.getRegion().isUsedForPartitionedRegionBucket()
          && event.getRegion().getPartitionedRegion().isHDFSRegion();

      // this happens for eviction/expiration on PR
      if (removeEntry || forceRemoveEntry) {
        boolean isThisTombstone = isTombstone();
        if(inTokenMode && !event.getOperation().isEviction()) {
          setValue(region, Token.DESTROYED);
        } else {
//          if (event.getRegion().getLogWriterI18n().fineEnabled()) {
//            event.getRegion().getLogWriterI18n().fine("ARE.destroy calling removePhase1");
//          }
          removePhase1(region, false);
//          if (event.getRegion().getLogWriterI18n().fineEnabled()) {
//            event.getRegion().getLogWriterI18n().fine("ARE.destroy done calling removePhase1 for " + this);
//          }
        }
        if (isThisTombstone) {
          region.unscheduleTombstone(this);
        }
      } else {
        makeTombstone(region, v);
      }
      
      return true;
    }
    else {
      return false;
    }
  }
  
 

  static boolean checkExpectedOldValue(@Unretained Object expectedOldValue, @Unretained Object actualValue) {
    if (Token.isInvalid(expectedOldValue)) {
      return (actualValue == null) || Token.isInvalid(actualValue);
    } else {
      return checkEquals(expectedOldValue, actualValue);
    }
  }
  
  static boolean checkEquals(@Unretained Object v1, @Unretained Object v2) {
    // need to give PdxInstance#equals priority
    if (v1 instanceof PdxInstance) {
      return checkPdxEquals((PdxInstance)v1, v2);
    } else if (v2 instanceof PdxInstance) {
      return checkPdxEquals((PdxInstance)v2, v1);
    } else if (v1 instanceof OffHeapCachedDeserializable) {
      return checkOffHeapEquals((OffHeapCachedDeserializable)v1, v2);
    } else if (v2 instanceof OffHeapCachedDeserializable) {
      return checkOffHeapEquals((OffHeapCachedDeserializable)v2, v1);
    } else if (v1 instanceof CachedDeserializable) {
      return checkCDEquals((CachedDeserializable)v1, v2);
    } else if (v2 instanceof CachedDeserializable) {
      return checkCDEquals((CachedDeserializable)v2, v1);
    } else {
      if (v2 != null) {
        return v2.equals(v1);
      } else {
        return v1 == null;
      }
    }
  }
  private static boolean checkOffHeapEquals(@Unretained OffHeapCachedDeserializable cd, @Unretained Object obj) {
    if (cd.isSerializedPdxInstance()) {
      PdxInstance pi = InternalDataSerializer.readPdxInstance(cd.getSerializedValue(), GemFireCacheImpl.getForPdx("Could not check value equality"));
      return checkPdxEquals(pi, obj);
    }
    if (obj instanceof OffHeapCachedDeserializable) {
      return cd.checkDataEquals((OffHeapCachedDeserializable)obj);
    } else {
      byte[] serializedObj;
      if (obj instanceof CachedDeserializable) {
        if (!cd.isSerialized()) {
          if (obj instanceof StoredObject && !((StoredObject) obj).isSerialized()) {
            // both are byte[]
            byte[] cdBytes = (byte[]) cd.getDeserializedForReading();
            byte[] objBytes = (byte[]) ((StoredObject) obj).getDeserializedForReading();
            return Arrays.equals(cdBytes, objBytes);
          } else {
            return false;
          }
        }
        serializedObj = ((CachedDeserializable) obj).getSerializedValue();
      } else if (obj instanceof byte[]) {
        if (cd.isSerialized()) {
          return false;
        }
        serializedObj = (byte[]) obj;
      } else {
        if (!cd.isSerialized()) {
          return false;
        }
        if (obj == null || obj == Token.NOT_AVAILABLE
            || Token.isInvalidOrRemoved(obj)) {
          return false;
        }
        serializedObj = EntryEventImpl.serialize(obj);
      }
      return cd.checkDataEquals(serializedObj);
    }
  }
  
  private static boolean checkCDEquals(CachedDeserializable cd, Object obj) {
    if (cd instanceof StoredObject && !((StoredObject) cd).isSerialized()) {
      // cd is an actual byte[].
      byte[] ba2;
      if (obj instanceof StoredObject) {
        if (!((StoredObject) obj).isSerialized()) {
          return false;
        }
        ba2 = (byte[]) ((StoredObject) obj).getDeserializedForReading();
      } else if (obj instanceof byte[]) {
        ba2 = (byte[]) obj;
      } else {
        return false;
      }
      byte[] ba1 = (byte[]) cd.getDeserializedForReading();
      return Arrays.equals(ba1, ba2);
    }
    Object cdVal = cd.getValue();
    if (cdVal instanceof byte[]) {
      byte[] cdValBytes = (byte[])cdVal;
      PdxInstance pi = InternalDataSerializer.readPdxInstance(cdValBytes, GemFireCacheImpl.getForPdx("Could not check value equality"));
      if (pi != null) {
        return checkPdxEquals(pi, obj);
      }
      //byte[] serializedObj;
      /**
       * To be more compatible with previous releases do not compare the serialized forms here.
       * Instead deserialize and call the equals method.
       */
      Object deserializedObj;
      if (obj instanceof CachedDeserializable) {
        //serializedObj = ((CachedDeserializable) obj).getSerializedValue();
        deserializedObj =((CachedDeserializable) obj).getDeserializedForReading();
      } else {
        if (obj == null || obj == Token.NOT_AVAILABLE
            || Token.isInvalidOrRemoved(obj)) {
          return false;
        }
        // TODO OPTIMIZE: Before serializing all of obj we could get the top
        // level class name of cdVal and compare it to the top level class name of obj.
        //serializedObj = EntryEventImpl.serialize(obj);
        deserializedObj = obj;
      }
      return cd.getDeserializedForReading().equals(deserializedObj);
//      boolean result = Arrays.equals((byte[])cdVal, serializedObj);
//      if (!result) {
//        try {
//          Object o1 = BlobHelper.deserializeBlob((byte[])cdVal);
//          Object o2 = BlobHelper.deserializeBlob(serializedObj);
//          SimpleMemoryAllocatorImpl.debugLog("checkCDEquals o1=<" + o1 + "> o2=<" + o2 + ">", false);
//          if (o1.equals(o2)) {
//            SimpleMemoryAllocatorImpl.debugLog("they are equal! a1=<" + Arrays.toString((byte[])cdVal) + "> a2=<" + Arrays.toString(serializedObj) + ">", false);
//          }
//        } catch (IOException e) {
//          // TODO Auto-generated catch block
//          e.printStackTrace();
//        } catch (ClassNotFoundException e) {
//          // TODO Auto-generated catch block
//          e.printStackTrace();
//        }
//      }
//      return result;
    } else {
      // prefer object form
      if (obj instanceof CachedDeserializable) {
        // TODO OPTIMIZE: Before deserializing all of obj we could get the top
        // class name of cdVal and the top level class name of obj and compare.
        obj = ((CachedDeserializable) obj).getDeserializedForReading();
      }
      return cdVal.equals(obj);
    }
  }
  /**
   * This method fixes bug 43643
   */
  private static boolean checkPdxEquals(PdxInstance pdx, Object obj) {
    if (!(obj instanceof PdxInstance)) {
      // obj may be a CachedDeserializable in which case we want to convert it to a PdxInstance even if we are not readSerialized.
      if (obj instanceof CachedDeserializable) {
        if (obj instanceof StoredObject && !((StoredObject) obj).isSerialized()) {
          // obj is actually a byte[] which will never be equal to a PdxInstance
          return false;
        }
        Object cdVal = ((CachedDeserializable) obj).getValue();
        if (cdVal instanceof byte[]) {
          byte[] cdValBytes = (byte[]) cdVal;
          PdxInstance pi = InternalDataSerializer.readPdxInstance(cdValBytes, GemFireCacheImpl.getForPdx("Could not check value equality"));
          if (pi != null) {
            return pi.equals(pdx);
          } else {
            // since obj is serialized as something other than pdx it must not equal our pdx
            return false;
          }
        } else {
          // remove the cd wrapper so that obj is the actual value we want to compare.
          obj = cdVal;
        }
      }
      if (obj.getClass().getName().equals(pdx.getClassName())) {
        GemFireCacheImpl gfc = GemFireCacheImpl.getForPdx("Could not access Pdx registry");
        if (gfc != null) {
          PdxSerializer pdxSerializer;
          if (obj instanceof PdxSerializable) {
            pdxSerializer = null;
          } else {
            pdxSerializer = gfc.getPdxSerializer();
          }
          if (pdxSerializer != null || obj instanceof PdxSerializable) {
            // try to convert obj to a PdxInstance
            HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
            try {
              if (InternalDataSerializer.autoSerialized(obj, hdos) ||
                  InternalDataSerializer.writePdx(hdos, gfc, obj, pdxSerializer)) {
                PdxInstance pi = InternalDataSerializer.readPdxInstance(hdos.toByteArray(), gfc);
                if (pi != null) {
                  obj = pi;
                }
              }
            } catch (IOException ignore) {
              // we are not able to convert it so just fall through
            } catch (PdxSerializationException ignore) {
              // we are not able to convert it so just fall through
            }
          }
        }
      }
    }
    return obj.equals(pdx);
  }

  //The last byte is used by GEMFIREXD. This field has no space now if it is a gfxd system.
  //Bits 0x00E0000000000000L is used for a gfe flag. We can still make LAST_MODIFIED_MASK
  // smaller if we need more bits for flags.
  // The mask of 0x001FFFFFFFFFFFFFL gives us until the year 287,586 before we run out of time.
  static final long LAST_MODIFIED_MASK = 0x001FFFFFFFFFFFFFL;
  private static final long LISTENER_INVOCATION_IN_PROGRESS = 0x0080000000000000L;
  private static final long LRU_RECENTLY_USED = 0x0040000000000000L;
  private static final long LRU_EVICTED = 0x0020000000000000L;

  /**
   * Token to indicate that this entry is created for locking only currently.
   * The large value avoids it being considered for expiration etc.
   */
  private static final long LOCKED_TOKEN = LAST_MODIFIED_MASK - 1;

  static final long INDEX_KEY_UPDATERS_MASK= 0xFF00000000000000L;
  static final long INDEX_KEY_UPDATERS_SHIFT= 56;
  static final long UPDATE_IN_PROGRESS_MASK = 0x0100000000000000L;
  
  protected abstract long getlastModifiedField();
  protected abstract boolean compareAndSetLastModifiedField(long expectedValue, long newValue);

  /*
   * Flags for a Region Entry
   */
  //protected static final int VALUE_NETSEARCH_RESULT = (HAS_WAITERS << 1);
  protected static final int UPDATE_IN_PROGRESS = (HAS_WAITERS << 1);
  protected static final int TOMBSTONE_SCHEDULED = (UPDATE_IN_PROGRESS << 1);
  protected static final int MARKED_FOR_EVICTION = (TOMBSTONE_SCHEDULED << 1);
  // no more flags available in state; use lastModifiedField for any new ones

  //protected static final long TIMESTAMP_MASK = 0x0000ffffffffffffL;
  //protected static final long DSID_MASK = ~TIMESTAMP_MASK;

  public static class HashRegionEntryCreator implements
      CustomEntryConcurrentHashMap.HashEntryCreator<Object, Object> {

    public HashEntry<Object, Object> newEntry(final Object key, final int hash,
        final HashEntry<Object, Object> next, final Object value) {
      final AbstractRegionEntry entry = (AbstractRegionEntry)value;
      // if hash is already set then assert that the two should be same
      final int entryHash = entry.getEntryHash();
      if (hash == 0 || entryHash != 0) {
        if (entryHash != hash) {
          Assert.fail("unexpected mismatch of hash, expected=" + hash
              + ", actual=" + entryHash + " for " + entry);
        }
      }
      entry.setEntryHash(hash);
      entry.setNextEntry(next);
      return entry;
    }

    public int keyHashCode(final Object key, final boolean compareValues) {
      return CustomEntryConcurrentHashMap.keyHash(key, compareValues);
    }
  };

  protected static final MapCallback<RegionEntry,
      QueuedSynchronizer, AbstractRegionEntry, Void> waitQCreator =
        new MapCallbackAdapter<RegionEntry,
            QueuedSynchronizer, AbstractRegionEntry, Void>() {

    /**
     * @see MapCallback#newValue
     */
    @Override
    public final QueuedSynchronizer newValue(final RegionEntry key,
        final AbstractRegionEntry entry, final Void ignored,
        final MapResult result) {
      final QueuedSynchronizer sync = new QueuedSynchronizer();
      // set one waiter on the queue
      sync.initNumWaiters(1);
      // set the waiters flag on the entry
      entry.setHasWaiters();
      return sync;
    }

    /**
     * @see MapCallback#oldValueRead(Object)
     */
    @Override
    public final void oldValueRead(final QueuedSynchronizer sync) {
      // we are getting the queue for reading so increment the number
      // of waiters conservatively
      sync.incrementNumWaiters();
    }

    /**
     * @see MapCallback#removeValue(Object, Object, Object, Object, Object)
     */
    @Override
    public final Object removeValue(final Object key, Object value,
        final QueuedSynchronizer sync, final AbstractRegionEntry entry,
        final Void ignored) {
      if (sync.getNumWaiters() == 0) {
        // clear the waiters flag on the entry
        entry.clearHasWaiters();
        return null;
      }
      else {
        return ABORT_REMOVE_TOKEN;
      }
    }
  };

  private static byte[] compressBytes(RegionEntryContext context, byte[] value) {
    if (AbstractRegionEntry.isCompressible(context, value)) {
      value = context.getCompressor().compress(value);
    }
    return value;
  }
  
 protected boolean okToStoreOffHeap(Object v) {
    if (v == null) return false;
    if (Token.isInvalidOrRemoved(v)) return false;
    if (v == Token.NOT_AVAILABLE) return false;
    if (v instanceof DiskEntry.RecoveredEntry) return false; // The disk layer has special logic that ends up storing the nested value in the RecoveredEntry off heap
    if (!isOffHeap()) return false;
    // TODO should we check for deltas here or is that a user error?
    return true;
  }
  /**
   * In GemFireXD return "this" itself as the key without requiring to create a
   * separate key object when the key itself is part of the row value.
   * 
   * @see RegionEntry#getKey()
   */
  @Override
  public final Object getKey() {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return getRawKey();
    }
    else {
      int tries = 1;
      do {
        final Object key = sysCb.entryGetKey(getRawKey(), this);
        if (key != null) {
          return key;
        }
        // skip this check for off-heap entry since it will be expensive
        if (!isOffHeap()) {
          sysCb.entryCheckValue(_getValue());
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("RegionEntry#getKey");
    }
  }

  /**
   * In GemFireXD create a separate key object where required explicitly for byte
   * array storage else return this stored key object itself.
   * 
   * @see RegionEntry#getKeyCopy()
   */
  @Override
  public final Object getKeyCopy() {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return getRawKey();
    }
    else {
      int tries = 1;
      do {
        final Object key = sysCb.entryGetKeyCopy(getRawKey(), this);
        if (key != null) {
          return key;
        }
        // skip this check for off-heap entry since it will be expensive
        if (!isOffHeap()) {
          sysCb.entryCheckValue(_getValue());
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("RegionEntry#getKeyCopy");
    }
  }

  public abstract Object getRawKey();
  protected abstract void _setRawKey(Object key);

  /**
   * Default implementation. Override in subclasses with primitive keys
   * to prevent creating an Object form of the key for each equality check.
   */
  @Override
  public boolean isKeyEqual(Object k) {
    return k.equals(getKey());
  }

  public final void _setLastModified(long lastModifiedTime) {
    if (lastModifiedTime < 0 || lastModifiedTime > LAST_MODIFIED_MASK) {
      throw new IllegalStateException("Expected lastModifiedTime " + lastModifiedTime + " to be >= 0 and <= " + LAST_MODIFIED_MASK);
    }
    long storedValue;
    long newValue;
    do {
      storedValue = getlastModifiedField();
      newValue = storedValue & ~LAST_MODIFIED_MASK;
      newValue |= lastModifiedTime;
    } while (!compareAndSetLastModifiedField(storedValue, newValue));
  }

  protected final long _getLastModified() {
    return getlastModifiedField() & LAST_MODIFIED_MASK;
  }

  public final long getLastModified() {
    long lastModified = _getLastModified();
    // check for LOCKED_TOKEN (#48664, #48604, #48684)
    return lastModified != LOCKED_TOKEN ? lastModified : 0L;
  }

  @Override
  public final boolean isLockedForCreate() {
    return _getLastModified() == LOCKED_TOKEN;
  }

  final void markLockedForCreate() {
    _setLastModified(LOCKED_TOKEN);
  }

  final void _setValue(RegionEntryContext context, @Unretained final Object val) {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    final Object containerInfo;

    // clear MARKED_FOR_EVICTION flag if set
    InternalDistributedSystem sys = null;
    while (true) {
      int state = getState();
      if ((state & MARKED_FOR_EVICTION) != 0) {
        if (compareAndSetState(state, (state & ~MARKED_FOR_EVICTION))) {
          break;
        }
        // check if DS is disconnecting
        if (sys == null) {
          sys = getDistributedSystem();
        }
        sys.getCancelCriterion().checkCancelInProgress(null);
      }
      else {
        break;
      }
    }

    // release old SerializedDiskBuffer explicitly for eager cleanup
    final boolean isOffHeap = isOffHeap();
    Object rawOldVal = null;
    if (!isOffHeap) {
      rawOldVal = getValueField();
      if (rawOldVal != val && rawOldVal instanceof SerializedDiskBuffer) {
        setValueField(val);
        if (context != null) context.updateMemoryStats(rawOldVal, val);
        ((SerializedDiskBuffer)rawOldVal).release();
        return;
      }
    }

    if (sysCb == null
        // GFXD: this is the case of generated key so a fixed long key
        || (containerInfo = sysCb.entryGetContainerInfoForKey(this)) == null
        // GFXD: this is the case when there is an existing valid value being
        // replaced by a token; in this case we know key was already a snapshot
        // and will remain the same
        || (Token.isRemoved(val) && getValueAsToken() != Token.NOT_A_TOKEN)) {
      setValueField(val);
      if (!isOffHeap && context != null) {
        context.updateMemoryStats(rawOldVal, val);
      }
    }
    else {
      final LocalRegion region = sysCb
          .getRegionFromContainerInfo(containerInfo);
      // TODO: PERF: don't get oldValue at this point rather only inside
      // sysCb.entryRefreshKey if key is not available
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      @Retained @Released Object oldValue = _getValueRetain(region, true);
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
      int tries = 1;
      for (;;) {
        try {
          final Object key = sysCb.entryRefreshKey(getRawKey(), oldValue, val,
              containerInfo);
          // need to ensure the order of assignment to key/rawValue for two
          // cases
          // if rawValue is of a different schema version now (and consequently
          // a new tableInfo), it is still handled by the updated
          // RowLocation.getRow* methods that use the value bytes to determine
          // the current schema if required
          if (key != null) {
            _setRawKey(key);
            setValueField(val);
          }
          else {
            setValueField(val);
            _setRawKey(null);
          }
          if (!isOffHeap && context != null) {
            context.updateMemoryStats(rawOldVal, val);
          }
          // also upgrade GemFireXD schema information if required; there is no
          // problem of concurrency since GFXD DDL cannot happen concurrently
          // with DML operations
          if (val != null) {
            setContainerInfo(null, val);
          }
          return;
        } catch (IllegalAccessException e) {
          // indicates retry
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        if (tries++ <= MAX_READ_TRIES) {
          OffHeapHelper.releaseWithNoTracking(oldValue);
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          oldValue = _getValueRetain(region, true);
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          continue;
        }
        else {
          break;
        }
      }
      } finally {
        OffHeapHelper.releaseWithNoTracking(oldValue);
      }
      throw sysCb.checkCacheForNullKeyValue("RegionEntry#_setValue");
    }
  }

  @Override
  @Retained(ABSTRACT_REGION_ENTRY_PREPARE_VALUE_FOR_CACHE)
  public Object prepareValueForCache(RegionEntryContext r,
      @Retained(ABSTRACT_REGION_ENTRY_PREPARE_VALUE_FOR_CACHE) Object val,
      boolean isEntryUpdate, boolean valHasMetadataForGfxdOffHeapUpdate) {
    if (r != null && r.getEnableOffHeapMemory() && okToStoreOffHeap(val)) {
      if (val instanceof StoredObject) {
        // we can just reuse this object
        if (val instanceof Chunk) {
          // if the reused guy has a refcount then need to inc it
          if (!((Chunk)val).retain()) {
            throw new IllegalStateException("Could not use an off heap value because it was freed");
          }
        }
      } else {
        byte[] data;
        boolean isSerialized = !(val instanceof byte[]);
        if (isSerialized) {
          if (GemFireCacheImpl.gfxdSystem()) {
            data = EntryEventImpl.serialize(val);
          }
          else if (val instanceof CachedDeserializable) {
            data = ((CachedDeserializable)val).getSerializedValue();
          } else if (val instanceof PdxInstance) {
            try {
              data = ((ConvertableToBytes)val).toBytes();
            } catch (IOException e) {
              throw new PdxSerializationException("Could not convert " + val + " to bytes", e);
            }
          } else {
            data = EntryEventImpl.serialize(val);
          }
        } else {
          data = (byte[]) val;
        }
        MemoryAllocator ma = SimpleMemoryAllocatorImpl.getAllocator(); // fix for bug 47875
        byte[] compressedData = compressBytes(r, data);
        boolean isCompressed = compressedData != data;
        if (isCompressed) {
          // TODO OFFHEAP: This should not need to be set here every time.
          // It should be set when the region is created.
          ma.setCompressor(r.getCompressor());
        }
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(this);
        val = ma.allocateAndInitialize(compressedData, isSerialized, isCompressed, GemFireChunk.TYPE); // TODO:KIRK:48068 race happens right after this line
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
//        if (val instanceof Chunk && r instanceof LocalRegion) {
//          Chunk c = (Chunk) val;
//          LocalRegion lr = (LocalRegion) r;
//          SimpleMemoryAllocatorImpl.debugLog("allocated @" + Long.toHexString(c.getMemoryAddress()) + " reg=" + lr.getFullPath(), false);
//        }
      }
      return val;
    }
    @Unretained Object nv = val;
    if (nv instanceof StoredObject) {
      // This off heap value is being put into a on heap region.
      if (CachedDeserializableFactory.preferObject()) { // fix 49175
        // gfxd never uses CachedDeserializable
        nv = ((StoredObject) nv).getValueAsDeserializedHeapObject();
      } else {
        byte[] data = ((StoredObject) nv).getSerializedValue();
        nv = CachedDeserializableFactory.create(data);
      }
    }
    // don't bother checking for GemFireXD
    if (!GemFireCacheImpl.gfxdSystem() && nv instanceof PdxInstanceImpl) {
      // We do not want to put PDXs in the cache as values.
      // So get the serialized bytes and use a CachedDeserializable.
      try {
        byte[] data = ((ConvertableToBytes)nv).toBytes();
        byte[] compressedData = compressBytes(r, data);
        if (data == compressedData
            && !CachedDeserializableFactory.preferObject()) {
          nv = CachedDeserializableFactory.create(data);
        } else {
          nv = compressedData;
        }
      } catch (IOException e) {
        throw new PdxSerializationException("Could not convert " + nv + " to bytes", e);
      }
    } else {
      nv = AbstractRegionEntry.compress(r, nv);
    }
    return nv;
  }
  
  @Unretained
  public final Object _getValue() {
    return getValueField();
  }

  public final boolean isUpdateInProgress() {
    return (getState() & UPDATE_IN_PROGRESS) != 0;
  }

  public final void setUpdateInProgress(final boolean underUpdate) {
    if (underUpdate) {
      setFlag(getState(), UPDATE_IN_PROGRESS);
    }
    else {
      clearFlag(getState(), UPDATE_IN_PROGRESS);
    }
  }


  public final boolean isCacheListenerInvocationInProgress() {
    return (getlastModifiedField() & LISTENER_INVOCATION_IN_PROGRESS) != 0L;
  }

  public final void setCacheListenerInvocationInProgress(final boolean listenerInvoked) {
    long storedValue;
    long newValue;
    if (listenerInvoked) {
      do {
        storedValue = getlastModifiedField();
        newValue = storedValue | LISTENER_INVOCATION_IN_PROGRESS;
      } while (!compareAndSetLastModifiedField(storedValue, newValue));
    } else {
      do {
        storedValue = getlastModifiedField();
        newValue = storedValue & ~(LISTENER_INVOCATION_IN_PROGRESS);
      } while (!compareAndSetLastModifiedField(storedValue, newValue));
    }
  }
  
  // The following methods are used by subclasses that have LRU.
  public final boolean testRecentlyUsed() {
    return (getlastModifiedField() & LRU_RECENTLY_USED) != 0L;
  }
  public final void setRecentlyUsed() {
    long storedValue;
    long newValue;
    do {
      storedValue = getlastModifiedField();
      newValue = storedValue | LRU_RECENTLY_USED;
    } while (!compareAndSetLastModifiedField(storedValue, newValue));
  }
  public final void unsetRecentlyUsed() {
    long storedValue;
    long newValue;
    do {
      storedValue = getlastModifiedField();
      newValue = storedValue & ~LRU_RECENTLY_USED;
    } while (!compareAndSetLastModifiedField(storedValue, newValue));
  }
  public final boolean testEvicted() {
    return (getlastModifiedField() & LRU_EVICTED) != 0L;
  }
  public final void setEvicted() {
    long storedValue;
    long newValue;
    do {
      storedValue = getlastModifiedField();
      newValue = storedValue | LRU_EVICTED;
    } while (!compareAndSetLastModifiedField(storedValue, newValue));
  }
  public final void unsetEvicted() {
    long storedValue;
    long newValue;
    do {
      storedValue = getlastModifiedField();
      newValue = storedValue & ~LRU_EVICTED;
    } while (!compareAndSetLastModifiedField(storedValue, newValue));
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isMarkedForEviction() {
    return (getState() & MARKED_FOR_EVICTION) != 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setMarkedForEviction() {
    setFlag(getState(), MARKED_FOR_EVICTION);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void clearMarkedForEviction() {
    clearFlag(getState(), MARKED_FOR_EVICTION);
  }

  public final void setOwner(LocalRegion owner, Object previousOwner) {
    @Retained @Released Object val = _getValueRetain(owner, true);
    try {
    // update the memory stats if required
    if (owner != previousOwner && !isOffHeap()) {
      // add for new owner
      if (owner != null) owner.updateMemoryStats(null, val);
      // reduce from previous owner
      if (previousOwner instanceof RegionEntryContext) {
        ((RegionEntryContext)previousOwner).updateMemoryStats(val, null);
      }
    }
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null || (owner != null && !owner.keyRequiresRegionContext())) {
      // nothing by default
      return;
    }
    final Object containerInfo;
    if ((containerInfo = setContainerInfo(owner, val)) != null) {
      // refresh the key if required
      final Object key = getRawKey();
      int tries = 1;
      do {
        try {
          _setRawKey(sysCb.entryRefreshKey(key, val, val, containerInfo));
          return;
        } catch (IllegalAccessException e) {
          // indicates retry
        }
        sysCb.entryCheckValue(val);
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        OffHeapHelper.release(val);
        val = _getValueRetain(owner, true);
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("RegionEntry#setOwner");
    }
    } finally {
      OffHeapHelper.release(val);
    }
  }

  @Override
  public Token getValueAsToken() {
    Object v = getValueField();
    if (v == null || v instanceof Token) {
      return (Token)v;
    } else {
      return Token.NOT_A_TOKEN;
    }
  }
  
  /**
   * Reads the value of this region entry.
   * Provides low level access to the value field.
   * @return possible OFF_HEAP_OBJECT (caller uses region entry reference)
   */
  @Unretained
  protected abstract Object getValueField();
  /**
   * Set the value of this region entry.
   * Provides low level access to the value field.
   * @param v the new value to set
   */
  protected abstract void setValueField(@Unretained Object v);

  @Retained
  public Object getTransformedValue() {
    return _getValueRetain(null, false);
  }

  public final void setValueResultOfSearch(boolean v) {
    // TODO: TX: Below is not proper for new TX; need to avoid locking
    // in write mode for netsearch rather lock in read mode.
    // Get rid of these two methods once it is done.
    /*
    long storedValue;
    long newValue;
    if (v) {
      do {
        storedValue = lastModifiedUpdater.get(this);
        newValue = storedValue | VALUE_RESULT_OF_SEARCH;
      } while (!lastModifiedUpdater.compareAndSet(this, storedValue, newValue));
    } else {
      do {
        storedValue = lastModifiedUpdater.get(this);
        newValue = storedValue & ~(VALUE_RESULT_OF_SEARCH);
      } while (!lastModifiedUpdater.compareAndSet(this, storedValue, newValue));
    }
    */
  }

  public boolean hasValidVersion() {
    VersionStamp stamp = (VersionStamp)this;
    boolean has = stamp.getRegionVersion() != 0 || stamp.getEntryVersion() != 0;
    return has;
  }

  public boolean hasStats() {
    // override this in implementations that have stats
    return false;
  }

  /**
   * @see HashEntry#getMapValue()
   */
  public final Object getMapValue() {
    return this;
  }

  /**
   * @see HashEntry#setMapValue(Object)
   */
  public final void setMapValue(final Object newValue) {
    if (this != newValue) {
      Assert.fail("AbstractRegionEntry#setMapValue: unexpected setMapValue "
          + "with newValue=" + newValue + ", this=" + this);
    }
  }

  protected abstract void setEntryHash(int v);

  /**
   * Return the hashCode for the entry. In GemFireXD then entry can itself be the
   * key, so has to return the hashcode of the key.
   * 
   * @see Object#hashCode()
   */
  @Override
  public final int hashCode() {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null) {
      return super.hashCode();
    }
    int h = this.getEntryHash();
    if (h != 0) {
      return h;
    }
    int tries = 1;
    do {
      try {
        h = sysCb.entryHashCode(getRawKey(), this);
        this.setEntryHash(h);
        return h;
      } catch (IllegalAccessException e) {
        // indicates retry
      }
      // skip this check for off-heap entry since it will be expensive
      if (!isOffHeap()) {
        sysCb.entryCheckValue(_getValue());
      }
      if ((tries % MAX_READ_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_READ_TRIES);
    throw sysCb.checkCacheForNullKeyValue("RegionEntry#hashCode");
  }

  /**
   * Compare against another key object.
   * 
   * @see Object#equals(Object)
   */
  @Override
  public final boolean equals(final Object other) {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb == null || (other instanceof AbstractRegionEntry)) {
      return this == other;
    }
    int tries = 1;
    final LocalRegion region = sysCb
        .getRegionFromContainerInfo(getContainerInfo());
    do {
      // TODO: PERF: don't get value at this point rather only inside
      // sysCb.entryRefreshKey if key is not available
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      @Retained @Released Object val = _getValueRetain(region, true);
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
        return sysCb.entryEquals(getRawKey(), val, this, other);
      } catch (IllegalAccessException e) {
        // indicates retry
      } finally {
        OffHeapHelper.releaseWithNoTracking(val);
      }
      // skip this check for off-heap entry since it will be expensive
      if (!isOffHeap()) {
        sysCb.entryCheckValue(_getValue());
      }
      if ((tries % MAX_READ_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_READ_TRIES);
    throw sysCb.checkCacheForNullKeyValue("RegionEntry#equals");
  }

  /**
   * Attempt to lock the object in given <code>LockMode</code> subject to given
   * timeout.
   * <p>
   * This implementation saves on the storage for owner in the RegionEntry so
   * does not allow for reentrancy for exclusive or exclusive-shared locks. The
   * thread will deadlock if it attempts to do so.
   * 
   * Threads can reenter for shared locks, but owner tracking is not done so the
   * caller has to ensure that locks and unlocks are paired correctly.
   * 
   * @see ExclusiveSharedLockObject#attemptLock
   */
  public final boolean attemptLock(final LockMode mode,
      int flags, final LockingPolicy lockPolicy, final long msecs,
      final Object owner, Object context) {

    // TODO: TX: Somehow register the owner without an overhead in every
    // RegionEntry or operation so it can be dumped at higher level by JVM
    // thread dump (preferable) or separate dumping method for debugging.
    // Thread-local may be one option but its overhead seems to be too high.
    // Maybe enable that when some flag is enabled, or log-level >= info.

    assert context instanceof LocalRegion: "unexpected context: " + context;

    // for RC, allow EX_SH to conflict with SH after some timeout
    if (lockPolicy.zeroDurationReadLocks()) {
      flags |= CONFLICT_WRITE_WITH_SH;
    }

    if (mode == LockMode.EX) {
      return attemptExclusiveLock(flags, lockPolicy, msecs, owner, context);
    }
    return attemptSharedLock(mode.getLockModeArg() | flags, lockPolicy, msecs,
        owner, context);
  }

  /**
   * Release the lock for the object as acquired by a previous call to
   * {@link #attemptLock}.
   * <p>
   * Threads can reenter for shared locks, but owner tracking is not done so the
   * caller has to ensure that locks and unlocks are paired correctly.
   * 
   * @see ExclusiveSharedLockObject#releaseLock
   */
  public final void releaseLock(final LockMode mode, final boolean releaseAll,
      final Object owner, final Object context) {
    // Neeraj: log

    // if we need to wake the waiters for the lock then use the region-level
    // waiting queue; note that we may have waiters even if this thread
    // acquired the lock in fail-fast mode since other threads in wait mode
    // may be waiting for this one
    assert context instanceof LocalRegion: "unexpected context: " + context;

    if (mode == LockMode.EX) {
      releaseExclusiveLock(releaseAll ? RELEASE_ALL_MASK : 0, owner, context);
    }
    else {
      int lockModeArg = mode.getLockModeArg();
      if (releaseAll) {
        lockModeArg |= RELEASE_ALL_MASK;
      }
      releaseSharedLock(lockModeArg, owner, context);
    }
  }

  /**
   * @see ExclusiveSharedLockObject#getOwnerId(Object)
   */
  @Override
  public final Object getOwnerId(final Object context) {
    // re-entrancy required to be ensured at higher level
    return null;
  }

  /**
   * @see ExclusiveSharedSynchronizer#setOwnerId(Object, Object)
   */
  @Override
  public final void setOwnerId(Object owner, Object context) {
    // re-entrancy required to be ensured at higher level
  }

  /**
   * @see ExclusiveSharedSynchronizer#clearOwnerId(Object)
   */
  @Override
  protected final void clearOwnerId(Object context) {
    // re-entrancy required to be ensured at higher level
  }

  /**
   * @see ExclusiveSharedSynchronizer#getQueuedSynchronizer(Object)
   */
  @Override
  protected final QueuedSynchronizer getQueuedSynchronizer(Object context) {
    // if we need to wait for the lock or re-enter then use the region-level
    // waiting queue that has the owner information
    final CustomEntryConcurrentHashMap<RegionEntry, QueuedSynchronizer>
        lockWaiters = ((LocalRegion)context).getLockWaiters();
    return lockWaiters.create(this, waitQCreator, this, null, true);
  }

  /**
   * @see ExclusiveSharedSynchronizer#queuedSynchronizerCleanup(
   *            QueuedSynchronizer, Object)
   */
  @Override
  protected final void queuedSynchronizerCleanup(QueuedSynchronizer sync,
      Object context) {
    if (sync.decrementNumWaiters() == 0) {
      // If there are no more waiters for this lock, then cleanup the queue
      // from the map. We also need to check atomically that queue is empty
      // at the time of removal.
      final CustomEntryConcurrentHashMap<RegionEntry, QueuedSynchronizer>
          lockWaiters = ((LocalRegion)context).getLockWaiters();
      lockWaiters.remove(this, waitQCreator, this, null);
    }
  }

  /**
   * @see ExclusiveSharedSynchronizer#signalQueuedSynchronizer(Object, boolean)
   */
  @Override
  protected final void signalQueuedSynchronizer(final Object context,
      final boolean shared) {
    if (hasWaiters()) {
      // if there is waiting thread queue then need to empty that too
      final CustomEntryConcurrentHashMap<RegionEntry, QueuedSynchronizer>
          lockWaiters = ((LocalRegion)context).getLockWaiters();
      final QueuedSynchronizer sync = lockWaiters.get(this);
      if (sync != null) {
        if (shared) {
          sync.signalSharedWaiters();
        }
        else {
          sync.clearOwnerThread();
          sync.signalWaiters();
        }
      }
    }
  }

  public StringBuilder shortToString(final StringBuilder sb) {
    ArrayUtils.objectRefString(this, sb);
    sb.append("(key=").append(getRawKey()).append("; rawValue=");
    // OFFHEAP _getValue ok: the current toString on OffHeapCachedDeserializable
    // is safe to use without incing refcount.
    ArrayUtils.objectRefString(_getValue(), sb);
    return super.appendFieldsToString(sb.append("; ")).append(')');
  }

  @Override
  protected StringBuilder appendFieldsToString(final StringBuilder sb) {
    sb.append("key=").append(getRawKey()).append(";rawValue=");
    // OFFHEAP _getValue ok: the current toString on OffHeapCachedDeserializable
    // is safe to use without incing refcount.
    ArrayUtils.objectStringNonRecursive(_getValue(), sb);
    VersionStamp stamp = getVersionStamp();
    if (stamp != null) {
      sb.append(";version=").append(stamp.asVersionTag())
        .append(";member=").append(stamp.getMemberID());
    }
    return super.appendFieldsToString(sb.append(";"));
  }

  /*
   * (non-Javadoc)
   * This generates version tags for outgoing messages for all subclasses
   * supporting concurrency versioning.  It also sets the entry's version
   * stamp to the tag's values.
   * 
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#generateVersionTag(com.gemstone.gemfire.distributed.DistributedMember, boolean)
   */
  public final VersionTag generateVersionTag(VersionSource mbr,
      boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
      EntryEventImpl event) {
    VersionStamp stamp = this.getVersionStamp();
    if (stamp != null && region.getServerProxy() == null) { // clients do not generate versions
      int v = stamp.getEntryVersion()+1;
      if (v > 0xFFFFFF) {
        v -= 0x1000000; // roll-over
      }
      VersionSource previous = stamp.getMemberID();

      
      //For non persistent regions, we allow the member to be null and
      //when we send a message and the remote side can determine the member
      //from the sender. For persistent regions, we need to send
      //the persistent id to the remote side.
      //
      //TODO - RVV - optimize the way we send the persistent id to save
      //space. 
      if(mbr == null) {
        VersionSource regionMember = region.getVersionMember();
        if(regionMember instanceof DiskStoreID) {
          mbr = regionMember;
        }
      }

      VersionTag tag = VersionTag.create(mbr);
      tag.setEntryVersion(v);
      if (region.getVersionVector() != null) {
        if (isRemoteVersionSource) {
          tag.setRegionVersion(region.getVersionVector().getNextRemoteVersion(
              mbr, event));
        }
        else {
          tag.setRegionVersion(region.getVersionVector().getNextVersion(event));
        }
      }
      if (withDelta) {
        tag.setPreviousMemberID(previous);
      }
      VersionTag remoteTag = event.getVersionTag();
      if (remoteTag != null && remoteTag.isGatewayTag()) {
        // if this event was received from a gateway we use the remote system's
        // timestamp and dsid.
        tag.setVersionTimeStamp(remoteTag.getVersionTimeStamp());
        tag.setDistributedSystemId(remoteTag.getDistributedSystemId());
        tag.setAllowedByResolver(remoteTag.isAllowedByResolver());
      } else {
        long time = event.getEventTime(0L, region);
        int dsid = region.getDistributionManager().getDistributedSystemId();
        // a locally generated change should always have a later timestamp than
        // one received from a wan gateway, so fake a timestamp if necessary
        if (time <= stamp.getVersionTimeStamp() && dsid != tag.getDistributedSystemId()) {
          time = stamp.getVersionTimeStamp() + 1;
        }
        tag.setVersionTimeStamp(time);
        tag.setDistributedSystemId(dsid);
      }
      stamp.setVersions(tag);
      stamp.setMemberID(mbr);
      event.setVersionTag(tag);
      return tag;
    }
    return null;
  }

  /** set/unset the flag noting that a tombstone has been scheduled for this entry */
  public final void setTombstoneScheduled(boolean scheduled) {
    if (scheduled) {
      setFlag(getState(), TOMBSTONE_SCHEDULED);
    }
    else {
      clearFlag(getState(), TOMBSTONE_SCHEDULED);
    }
  }

  /**
   * return the flag noting whether a tombstone has been scheduled for this entry.  This should
   * be called under synchronization on the region entry if you want an accurate result.
   */
  public final boolean isTombstoneScheduled() {
    return (getState() & TOMBSTONE_SCHEDULED) != 0;
  }

  /*
   * (non-Javadoc)
   * This performs a concurrency check.
   * 
   * This check compares the version number first, followed by the member ID.
   * 
   * Wraparound of the version number is detected and handled by extending the
   * range of versions by one bit.
   * 
   * The normal membership ID comparison method is used.<p>
   * 
   * Note that a tag from a remote (WAN) system may be in the event.  If this
   * is the case this method will either invoke a user plugin that allows/disallows
   * the event (and may modify the value) or it determines whether to allow
   * or disallow the event based on timestamps and distributedSystemIDs.
   * 
   * @throws ConcurrentCacheModificationException if the event conflicts with
   * an event that has already been applied to the entry.
   * 
   * @see com.gemstone.gemfire.internal.cache.RegionEntry#concurrencyCheck(com.gemstone.gemfire.cache.EntryEvent)
   */
  public void processVersionTag(EntryEvent cacheEvent) {
    processVersionTag(cacheEvent, true);
  }
  
  public void release() {
    
  }

  protected final void processVersionTag(EntryEvent cacheEvent,
      boolean conflictCheck) {
    EntryEventImpl event = (EntryEventImpl)cacheEvent;
    VersionTag tag = event.getVersionTag();
    if (tag == null) {
      return;
    }

    LogWriterI18n log = event.getRegion().getLogWriterI18n();
    try {
      if (tag.isGatewayTag()) {
        // this may throw ConcurrentCacheModificationException or modify the event
        if (processGatewayTag(event)) {
          return;
        }
        assert false : "processGatewayTag failure - returned false";
      }

      if (!tag.isFromOtherMember()) {
        if (!event.getOperation().isNetSearch()) {
          // except for netsearch, all locally-generated tags can be ignored
          return;
        }
      }

      final InternalDistributedMember originator = (InternalDistributedMember)event.getDistributedMember();
      final VersionSource dmId = event.getRegion().getVersionMember();
      LocalRegion r = event.getLocalRegion();
      boolean eventHasDelta = event.getDeltaBytes() != null && event.getRawNewValue() == null;

      VersionStamp stamp = getVersionStamp();
      // bug #46223, an event received from a peer or a server may be from a different
      // distributed system than the last modification made to this entry so we must
      // perform a gateway conflict check
      if (stamp != null && !tag.isAllowedByResolver()) {
        int stampDsId = stamp.getDistributedSystemId();
        int tagDsId = tag.getDistributedSystemId();

        if (stampDsId != 0  &&  stampDsId != tagDsId  &&  stampDsId != -1) {
          StringBuilder verbose = null;
          if (log.fineEnabled() || TombstoneService.VERBOSE) {
            verbose = new StringBuilder();
            verbose.append("processing tag for key ").append(getKeyCopy())
                .append(", stamp=").append(stamp.asVersionTag())
                .append(", tag=").append(tag);
          }
          long stampTime = stamp.getVersionTimeStamp();
          long tagTime = tag.getVersionTimeStamp();
          
          // [sjigyasu] In GemFireXD, for delete-update or delete-insert conflicts, we make the delete trump
          // regardless of the timestamp.
          final GemFireCacheImpl.StaticSystemCallbacks sysCb =
              GemFireCacheImpl.FactoryStatics.systemCallbacks;
          boolean allowDeleteOnConflict = false;
          if (sysCb != null) {
            allowDeleteOnConflict = sysCb.shouldDeleteWinOnConflict();
          }
          
          if (stampTime > 0 && (tagTime > stampTime
              || (tagTime == stampTime  &&  tag.getDistributedSystemId() >= stamp.getDistributedSystemId()))
              || (event.getOperation().equals(Operation.DESTROY) && allowDeleteOnConflict)
              ) {
            if (verbose != null) {
              verbose.append(" - allowing event");
              log.info(LocalizedStrings.DEBUG, verbose);
            }
            // Update the stamp with event's version information.
            applyVersionTag(r, stamp, tag, originator);
            return;
          }
          // [sjigyasu] #48840: Don't throw the conflict exception in case of update operation.
          // Let the delta be applied even if there is conflict.
          // This is a known problem in GemFireXD where versioning does not necessarily provide consistency within DS
          // between copies of replicated tables or between primary and secondary of partitioned.
          // U1 + U2 is not necessarily equivalent to U2 alone if the delta is on different columns.
          // So here we apply the delta anyway even if there is conflict.  This can still give inconsistency
          // in case the delta has partially or fully overlapping columns, but not in case of non-overlapping columns.
          if (stampTime > 0 && !event.hasDeltaPut()) {
            if (verbose != null) {
              verbose.append(" - disallowing event");
              log.info(LocalizedStrings.DEBUG, verbose);
            }
            r.getCachePerfStats().incConflatedEventsCount();
            persistConflictingTag(r, tag);
            throw new ConcurrentCacheModificationException("conflicting event detected");
          }
        }
      }

//      if (log.fineEnabled()) {
//        if (this.getVersionStamp().getEntryVersion() == 0) {
//          log.fine("processVersionTag invoked entry hash=" + Integer.toHexString(System.identityHashCode(this))
//              + " event=" + event
//          /*, new Exception("stack trace")*/);
//        }
//        else {
//          log.fine("processVersionTag invoked entry hash=" + Integer.toHexString(System.identityHashCode(this)));
//        }
//      }

      if (r.getVersionVector() != null &&
          r.getServerProxy() == null &&
          (r.getDataPolicy().withPersistence() ||
              !r.getScope().isLocal())) { // bug #45258 - perf degradation for local regions and RVV
        VersionSource who = tag.getMemberID();
        if (who == null) {
          who = originator;
        }
        r.getVersionVector().recordVersion(who, tag, (EntryEventImpl)cacheEvent);
      }

      assert !tag.isFromOtherMember() || tag.getMemberID() != null : "remote tag is missing memberID";

      // [bruce] for a long time I had conflict checks turned off in clients when
      // receiving a response from a server and applying it to the cache.  This lowered
      // the CPU cost of versioning but eventually had to be pulled for bug #45453
//      if (r.getServerProxy() != null && conflictCheck) {
//        // events coming from servers while a local sync is held on the entry
//        // do not require a conflict check.  Conflict checks were already
//        // performed on the server and here we just consume whatever was sent back.
//        // Event.isFromServer() returns true for client-update messages and
//        // for putAll/getAll, which do not hold syncs during the server operation.
//        conflictCheck = event.isFromServer();
//      }
//      else
      
      // [bruce] for a very long time we had conflict checks turned off for PR buckets.
      // Bug 45669 showed a primary dying in the middle of distribution.  This caused
      // one backup bucket to have a v2.  The other bucket was promoted to primary and
      // generated a conflicting v2.  We need to do the check so that if this second
      // v2 loses to the original one in the delta-GII operation that the original v2
      // will be the winner in both buckets.
//      if (r.isUsedForPartitionedRegionBucket()) {
//        conflictCheck = false; // primary/secondary model 
//      }

      // The new value in event is not from GII, even it could be tombstone
      basicProcessVersionTag(r, tag, false, eventHasDelta, dmId, originator, conflictCheck);
    } catch (ConcurrentCacheModificationException ex) {
      event.isConcurrencyConflict(true);
      throw ex;
    }
  }

  protected final void basicProcessVersionTag(LocalRegion region, VersionTag tag, boolean isTombstoneFromGII,
      boolean deltaCheck, VersionSource dmId, InternalDistributedMember sender, boolean checkForConflict) {
    LogWriterI18n log = region.getLogWriterI18n();
    StringBuilder verbose = null;

    if (tag != null) {
      VersionStamp stamp = getVersionStamp();

      if (log.fineEnabled() || TombstoneService.VERBOSE) {
        VersionTag stampTag = stamp.asVersionTag();
        if (stampTag.hasValidVersion() && checkForConflict) { // only be verbose here if there's a possibility we might reject the operation
          verbose = new StringBuilder();
          verbose.append("processing tag for key ").append(getKeyCopy())
              .append(", stamp=").append(stamp.asVersionTag()).append(", tag=")
              .append(tag).append(", checkForConflict=")
              .append(checkForConflict); // .append(", current value=").append(_getValue());
        }
        //        if (this.value == Token.REMOVED_PHASE2) {
        //          log.severe(LocalizedStrings.DEBUG, "DEBUG: performing a concurrency check on an entry with REMOVED_PHASE2 value", new Exception("ConcurrencyCheckOnDestroyedEntry"));
        //        }
      }

      if (stamp == null) {
        throw new IllegalStateException("message contained a version tag but this region has no version storage");
      }

      boolean apply = true;

      try {
        if (checkForConflict) {
          apply = checkForConflict(region, stamp, tag, isTombstoneFromGII, deltaCheck, dmId, sender, verbose);
        }
      } catch (ConcurrentCacheModificationException e) {
        // Even if we don't apply the operation we should always retain the
        // highest timestamp in order for WAN conflict checks to work correctly
        // because the operation may have been sent to other systems and been
        // applied there
        if (!tag.isGatewayTag()
            && stamp.getDistributedSystemId() == tag.getDistributedSystemId()
            && tag.getVersionTimeStamp() > stamp.getVersionTimeStamp()) {
          stamp.setVersionTimeStamp(tag.getVersionTimeStamp());
          tag.setTimeStampApplied(true);
          if (verbose != null) {
            verbose.append("\nThough in conflict the tag timestamp was more recent and was recorded.");
          }
        }
        throw e; 
      } finally {
        if (verbose != null) {
          log.info(LocalizedStrings.DEBUG, verbose.toString());
        }
      }

      if (apply) {
        applyVersionTag(region, stamp, tag, sender);
      }
    }
  }

  private void applyVersionTag(LocalRegion region, VersionStamp stamp, VersionTag tag, InternalDistributedMember sender) {
    // stamp.setPreviousMemberID(stamp.getMemberID());
    VersionSource mbr = tag.getMemberID();
    if (mbr == null) {
      mbr = sender;
    }
    mbr = region.getVersionVector().getCanonicalId(mbr);
    tag.setMemberID(mbr);
    stamp.setVersions(tag);
    if (tag.hasPreviousMemberID()) {
      if (tag.getPreviousMemberID() == null) {
        tag.setPreviousMemberID(stamp.getMemberID());
      } else {
        tag.setPreviousMemberID(region.getVersionVector().getCanonicalId(
            tag.getPreviousMemberID()));
      }
    }
  }

  /** perform conflict checking for a stamp/tag */
  protected boolean checkForConflict(LocalRegion region,
      VersionStamp stamp, VersionTag tag,
      boolean isTombstoneFromGII,
      boolean deltaCheck, VersionSource dmId,
      InternalDistributedMember sender, StringBuilder verbose) {

    int stampVersion = stamp.getEntryVersion();
    int tagVersion = tag.getEntryVersion();

    boolean throwex = false;
    boolean apply = false;

    if (stamp.getVersionTimeStamp() != 0) { // new entries have no timestamp
      // check for wrap-around on the version number  
      long difference = tagVersion - stampVersion;
      if (0x10000 < difference || difference < -0x10000) {
        if (verbose != null) {
          verbose.append("\nversion rollover detected: tag="+tagVersion + " stamp=" + stampVersion);
        }
        if (difference < 0) {
          tagVersion += 0x1000000L;
        } else {
          stampVersion += 0x1000000L;
        }
      }
    }
    if (verbose != null) {
      verbose.append("\nstamp=v").append(stampVersion)
             .append(" tag=v").append(tagVersion);
    }

    if (deltaCheck) {
      checkForDeltaConflict(region, stampVersion, tagVersion, stamp, tag, dmId, sender, verbose);
    }

    if (stampVersion == 0  ||  stampVersion < tagVersion) {
      if (verbose != null) { verbose.append(" - applying change"); }
      apply = true;
    } else if (stampVersion > tagVersion) {
      if (overwritingOldTombstone(region, stamp, tag, verbose)) {
        apply = true;
      } else {
        if (tagVersion > 0
            && isExpiredTombstone(region, tag.getVersionTimeStamp(), isTombstoneFromGII)
            && tag.getVersionTimeStamp() > stamp.getVersionTimeStamp()) {
          // A special case to apply: when remote entry is expired tombstone, then let local vs remote with newer timestamp to win
          if (verbose != null) { verbose.append(" - applying change in Delta GII"); }
          apply = true;
        } else {
          if (verbose != null) { verbose.append(" - disallowing"); }
          throwex= true;
        }
      }
    } else {
      if (overwritingOldTombstone(region, stamp, tag, verbose)) {
        apply = true;
      } else {
        // compare member IDs
        VersionSource stampID = stamp.getMemberID();
        if (stampID == null) {
          stampID = dmId;
        }
        VersionSource tagID = tag.getMemberID();
        if (tagID == null) {
          tagID = sender;
        }
        if (verbose != null) { verbose.append("\ncomparing IDs"); }
        int compare = stampID.compareTo(tagID);
        if (compare < 0) {
          if (verbose != null) { verbose.append(" - applying change"); }
          apply = true;
        } else if (compare > 0) {
          if (verbose != null) { verbose.append(" - disallowing"); }
          throwex = true;
        } else if (tag.isPosDup()) {
          if (verbose != null) { verbose.append(" - disallowing duplicate marked with posdup"); }
          throwex = true;
        } else /* if (isTombstoneFromGII && isTombstone()) {
          if (verbose != null) { verbose.append(" - disallowing duplicate tombstone from GII"); }
          return false;  // bug #49601 don't schedule tombstones from GII if there's already one here
        } else */ {
          if (verbose != null) { verbose.append(" - allowing duplicate"); }
        }
      }
    }

    if (!apply && throwex) {
      region.getCachePerfStats().incConflatedEventsCount();
      persistConflictingTag(region, tag);
      throw new ConcurrentCacheModificationException();
    }

    return apply;
  }

  private boolean isExpiredTombstone(LocalRegion region, long timestamp, boolean isTombstone) {
    return isTombstone && (timestamp + TombstoneService.REPLICATED_TOMBSTONE_TIMEOUT) <= region.cacheTimeMillis();
  }
  
  private boolean overwritingOldTombstone(LocalRegion region, VersionStamp stamp, VersionTag tag, StringBuilder verbose) {
    // Tombstone GC does not use locking to stop operations when old tombstones
    // are being removed.  Because of this we might get an operation that was applied
    // in another VM that has just reaped a tombstone and is now using a reset
    // entry version number.  Because of this we check the timestamp on the current
    // local entry and see if it is old enough to have expired.  If this is the case
    // we accept the change and allow the tag to be recorded
    long stampTime = stamp.getVersionTimeStamp();
    if (isExpiredTombstone(region, stampTime, this.isTombstone())) {
      // no local change since the tombstone would have timed out - accept the change
      if (verbose != null) { verbose.append(" - accepting because local timestamp is old"); }
      return true;
    } else {
      return false;
    }
  }

  protected void persistConflictingTag(LocalRegion region, VersionTag tag) {
    // only persist region needs to persist conflict tag 
  }

  /**
   * for an event containing a delta we must check to see if the tag's
   * previous member id is the stamp's member id and ensure that the
   * version is only incremented by 1.  Otherwise the delta is being
   * applied to a value that does not match the source of the delta.
   * 
   * @throws InvalidDeltaException
   */
  private void checkForDeltaConflict(LocalRegion region,
      long stampVersion, long tagVersion,
      VersionStamp stamp, VersionTag tag,
      VersionSource dmId, InternalDistributedMember sender,
      StringBuilder verbose) {

    if (tagVersion != stampVersion+1) {
      if (verbose != null) {
        verbose.append("\ndelta requires full value due to version mismatch");
      }
      region.getCachePerfStats().incDeltaFailedUpdates();
      throw new InvalidDeltaException("delta cannot be applied due to version mismatch");

    } else {
      // make sure the tag was based on the value in this entry by checking the
      // tag's previous-changer ID against this stamp's current ID
      VersionSource stampID = stamp.getMemberID();
      if (stampID == null) {
        stampID = dmId;
      }
      VersionSource tagID = tag.getPreviousMemberID();
      if (tagID == null) {
        tagID = sender;
      }
      if (!tagID.equals(stampID)) {
        if (verbose != null) {
          verbose.append("\ndelta requires full value.  tag.previous=")
          .append(tagID).append(" but stamp.current=").append(stampID);
        }
        region.getCachePerfStats().incDeltaFailedUpdates();
        throw new InvalidDeltaException("delta cannot be applied due to version ID mismatch");
      }
    }
  }

  private boolean processGatewayTag(EntryEventImpl event) {
    // Gateway tags are installed in the server-side LocalRegion cache
    // modification methods.  They do not have version numbers or distributed
    // member IDs.  Instead they only have timestamps and distributed system IDs.

    // If there is a resolver plug-in, invoke it.  Otherwise we use the timestamps and
    // distributed system IDs to determine whether to allow the event to proceed.

    if (this.isRemoved() && !this.isTombstone()) {
      return true; // no conflict on a new entry
    }
    VersionTag tag = event.getVersionTag();
    long stampTime = getVersionStamp().getVersionTimeStamp();
    long tagTime = tag.getVersionTimeStamp();
    int stampDsid = getVersionStamp().getDistributedSystemId();
    int tagDsid = tag.getDistributedSystemId();
    LogWriterI18n log = event.getRegion().getLogWriterI18n();
    if (log.fineEnabled()) {
      log.fine("processing gateway version information for " + event.getKey() + ".  Stamp dsid="+stampDsid
          + " time=" + stampTime + " Tag dsid=" + tagDsid
          + " time=" + tagTime);
    }
    if (tagTime == VersionTag.ILLEGAL_VERSION_TIMESTAMP) {
      return true; // no timestamp received from other system - just apply it
    }
    if (tagDsid == stampDsid || stampDsid == -1) {
      return true;
    }
    GatewayConflictResolver resolver = event.getRegion().getCache().getGatewayConflictResolver();
    if (resolver != null) {
      if (log.fineEnabled()) {
        log.fine("invoking gateway conflict resolver");
      }
      final boolean[] disallow = new boolean[1];
      final Object[] newValue = new Object[] { this };
      GatewayConflictHelper helper = new GatewayConflictHelper() {
        @Override
        public void disallowEvent() {
          disallow[0] = true;
        }

        @Override
        public void changeEventValue(Object v) {
          newValue[0] = v;
        }
      };
      TimestampedEntryEventImpl timestampedEvent = (TimestampedEntryEventImpl)event
          .getTimestampedEvent(tagDsid, stampDsid, tagTime, stampTime);

      // gateway conflict resolvers will usually want to see the old value
      if (!timestampedEvent.hasOldValue() && isRemoved()) {
        timestampedEvent.setOldValue(getValue(timestampedEvent.getRegion())); // OFFHEAP: since isRemoved I think getValue will never be stored off heap in this case
      }

      Throwable thr = null;
      try {
        resolver.onEvent(timestampedEvent, helper);
      } catch (CancelException cancelled) {
        throw cancelled;
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error
            && SystemFailure.isJVMFailureError(err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        event.getRegion().getLogWriterI18n().error(
            LocalizedStrings.LocalRegion_EXCEPTION_OCCURRED_IN_CONFLICTRESOLVER, t);
        thr = t;
      } finally {
        timestampedEvent.release();
      }

      if (log.fineEnabled()) {
        log.fine("done invoking resolver", thr);
      }
      if (thr == null) {
        if (disallow[0]) {
          if (log.fineEnabled()) {
            log.fine("conflict resolver rejected the event for " + event.getKey());
          }
          throw new ConcurrentCacheModificationException("WAN conflict resolver rejected the operation");
        }
        
        tag.setAllowedByResolver(true);
        
        if (newValue[0] != this) {
          if (log.fineEnabled()) {
            log.fine("conflict resolver changed the value of the event for " + event.getKey());
          }
          // the resolver changed the event value!
          event.setNewValue(newValue[0]);
        }
        // if nothing was done then we allow the event
        if (log.fineEnabled()) {
          log.fine("change was allowed by conflict resolver: " + tag);
        }
        return true;
      }
    }

    // [sjigyasu] In GemFireXD, for delete-update or delete-insert conflicts, we make the delete trump
    // regardless of the timestamp.
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl.FactoryStatics.systemCallbacks;
    boolean allowDeleteOnConflict = false;
    if (sysCb != null) {
      allowDeleteOnConflict = sysCb.shouldDeleteWinOnConflict();
    }

    if (log.fineEnabled()) {
      log.fine("performing normal WAN conflict check");
    }
    if (tagTime > stampTime
        || (tagTime == stampTime && tagDsid >= stampDsid)
        || (event.getOperation().equals(Operation.DESTROY) && allowDeleteOnConflict)) {
      if (log.fineEnabled()) {
        log.fine("allowing event");
      }
      return true;
    }
    if (log.fineEnabled()) {
      log.fine("disallowing event for " + event.getKey());
    }
    throw new ConcurrentCacheModificationException("conflicting WAN event detected");
  }

  static boolean isCompressible(RegionEntryContext context, Object value) {
    return (context != null && context.getCompressor() != null &&
        value != null && !Token.isInvalidOrRemoved(value));
  }

  /* subclasses supporting versions must override this */
  public VersionStamp getVersionStamp() {
    return null;
  }

  /**
   * For GemFireXD RowLocation implementations that also implement RegionKey.
   */
  public final void setRegionContext(LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * For GemFireXD RowLocation implementations that also implement RegionKey.
   */
  public final KeyWithRegionContext beforeSerializationWithValue(
      boolean valueIsToken) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * For GemFireXD RowLocation implementations that also implement RegionKey.
   */
  public final void afterDeserializationWithValue(Object val) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * For GemFireXD RowLocation implementations that also implement RegionKey.
   */
  public final void waitForRegionInitialization(String regionPath) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * For GemFireXD implementations that also implement Sizeable.
   * Note that XD in somes cases returns the AbstractRegionEntry as
   * the key of the the region entry. In those cases we may call
   * getSizeInBytes. That should be the only time this getSizeInBytes
   * should be called.
   * NOTE: it should not be called (nor implemented) to get the size
   * of the actual region entry object. This method has been made
   * final to prevent subclasses from overriding it thinking it should
   * return the size of the regione entry.
   * 
   * @see Sizeable#getSizeInBytes()
   */
  public final int getSizeInBytes() {
    final StaticSystemCallbacks sysCb =
        GemFireCacheImpl.FactoryStatics.systemCallbacks;
    if (sysCb != null) {
      // We return the key overhead after overflow since this will be invoked
      // only on the result of getKey()
      int tries = 1;
      do {
        final int size = sysCb.entryKeySizeInBytes(getRawKey(), this);
        if (size >= 0) {
          return size;
        }
        // skip this check for off-heap entry since it will be expensive
        if (!isOffHeap()) {
          sysCb.entryCheckValue(_getValue());
        }
        if ((tries % MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_READ_TRIES);
      throw sysCb.checkCacheForNullKeyValue("RegionEntry#getSizeInBytes");
    }
    return 0; // can't get the size in bytes for key object in general
  }

  /**
   * For GemFireXD RowLocation implementations that can be transactional.
   */
  public final TXId getTXId() {
    return null;
  }

  /**
   * For GemFireXD RowLocation implementations that return the RegionEntry.
   */
  public final RegionEntry getRegionEntry() {
    return this;
  }

  /**
   * For GemFireXD RowLocation implementations that return the underlying
   * RegionEntry present in the region.
   */
  public final RegionEntry getUnderlyingRegionEntry() {
    return this;
  }

  /**
   * Used for GemFireXD to return the table schema information if available.
   */
  public Object getContainerInfo() {
    return null;
  }

  /**
   * Used for GemFireXD to to initialize the table schema related information from
   * the owner region if key is part of the value row itself, or when value
   * changes (e.g. schema may change once value changes).
   */
  public Object setContainerInfo(LocalRegion owner, Object val) {
    return null;
  }

  // BEGIN common unimplemented methods for GemFireXD.

  public final void checkHostVariable(int declaredLength) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final boolean getBoolean() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final byte getByte() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final byte[] getBytes() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final Date getDate(Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final double getDouble() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final float getFloat() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int getInt() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int getLength() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final long getLong() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final short getShort() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final InputStream getStream() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final String getString() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final Time getTime(Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final Timestamp getTimestamp(Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final String getTraceString() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final String getTypeName() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int readBytes(byte[] outBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int readBytes(long memOffset, int columnWidth, ByteSource bs) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setBigDecimal(Number bigDecimal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setInto(PreparedStatement ps, int position) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setInto(ResultSet rs, int position) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setObjectForCast(Object value,
      boolean instanceOfResultType, String resultTypeClassName) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setToNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(int theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(double theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(float theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(short theValue) {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  public final void setValue(long theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(byte theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(boolean theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(byte[] theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(String theValue) {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  public final void setValue(Time theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Time theValue, Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Timestamp theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Timestamp theValue, Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Date theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Date theValue, Calendar cal) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(InputStream theStream, int valueLength) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(Object theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(java.sql.Blob theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValue(java.sql.Clob theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void toDataForOptimizedResultHolder(DataOutput dos)
      throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int typePrecedence() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int typeToBigDecimal() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void restoreToNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void writeExternal(ObjectOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int getDSFID() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void toData(DataOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final int nCols() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final void getKeyColumns(Object[] keys) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public final boolean canCompareBytesToBytes() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  // END common unimplemented methods for GemFireXD.

  public boolean isValueNull() {
    return (null == getValueAsToken());
  }

  public boolean isInvalid() {
    return Token.isInvalid(getValueAsToken());
  }

  public boolean isDestroyed() {
    return Token.isDestroyed(getValueAsToken());
  }

  public void setValueToNull(RegionEntryContext context) {
    _setValue(context, null);
  }
  
  public boolean isInvalidOrRemoved() {
    return Token.isInvalidOrRemoved(getValueAsToken());
  }

  @Override
  public boolean isOffHeap() {
    return false;
  }

  /**
   * This is only retained in off-heap subclasses.  However, it's marked as
   * Retained here so that callers are aware that the value may be retained.
   */
  @Override
  @Retained 
  public Object _getValueRetain(RegionEntryContext context, boolean decompress) {
    if (decompress) {
      return decompress(context, _getValue());
    } else {
      return _getValue();
    }
  }
  
  @Override
  public void returnToPool() {
    // noop by default
  }
  
  public void markDeleteFromIndexInProgress() {
    long storedValue;   
    long indexKeyUsers;
    do {
      storedValue = getlastModifiedField();
      indexKeyUsers = INDEX_KEY_UPDATERS_MASK & storedValue;
      indexKeyUsers = indexKeyUsers >> INDEX_KEY_UPDATERS_SHIFT;
    } while (!(indexKeyUsers == 0
        && compareAndSetLastModifiedField(storedValue, UPDATE_IN_PROGRESS_MASK | storedValue)));
  }

  public void unmarkDeleteFromIndexInProgress() {
    long storedValue, newValue;
    do {

      storedValue = getlastModifiedField();
      assert (INDEX_KEY_UPDATERS_MASK & storedValue) >> INDEX_KEY_UPDATERS_SHIFT == 1;
      newValue = storedValue & LAST_MODIFIED_MASK;

    } while (!compareAndSetLastModifiedField(storedValue, newValue));

  }
  public boolean useRowLocationForIndexKey() {
    
   
    long storedValue = getlastModifiedField();
    long indexKeyUsers = INDEX_KEY_UPDATERS_MASK & storedValue;
    indexKeyUsers = indexKeyUsers >> INDEX_KEY_UPDATERS_SHIFT;
    if(indexKeyUsers == 0) {
      indexKeyUsers =2;  
    }else if(indexKeyUsers > 1 && indexKeyUsers <  0xFF) {
     ++indexKeyUsers;
    }else {
      return false;
    }
    //clear the old number of updates in progress 
    long newValue = storedValue & LAST_MODIFIED_MASK;
    newValue = (indexKeyUsers << INDEX_KEY_UPDATERS_SHIFT) | newValue;
    return compareAndSetLastModifiedField(storedValue, newValue);
 
  }

  public void endIndexKeyUpdate() {
    
    long newValue;
    long indexKeyUsers;
    long storedValue;
    do {
      storedValue = getlastModifiedField();
      indexKeyUsers = INDEX_KEY_UPDATERS_MASK & storedValue;
      indexKeyUsers = indexKeyUsers >> INDEX_KEY_UPDATERS_SHIFT;
      assert indexKeyUsers != 1;
      // clear the update count
      newValue = storedValue & LAST_MODIFIED_MASK;
      if (indexKeyUsers > 2) {
        --indexKeyUsers;
        newValue = (indexKeyUsers << INDEX_KEY_UPDATERS_SHIFT) | newValue;
      }     
    } while (!compareAndSetLastModifiedField(storedValue, newValue));

  }
  
}
