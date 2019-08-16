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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.AsyncDiskEntry;
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
import com.gemstone.gemfire.internal.cache.lru.LRUClockNode;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.persistence.BytesAndBits;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.store.WrappedBytes;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.FetchRequest;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.util.BlobHelper;

/**
 * Represents an entry in an {@link RegionMap} whose value may be
 * stored on disk.  This interface provides accessor and mutator
 * methods for a disk entry's state.  This allows us to abstract all
 * of the interesting behavior into a {@linkplain DiskEntry.Helper
 * helper class} that we only need to implement once.
 *
 * <P>
 *
 * Each <code>DiskEntry</code> has a unique <code>id</code> that is
 * used by the {@link DiskRegion} to identify the key/value pair.
 * Before the disk entry is written to disk, the value of the
 * <code>id</code> is {@link DiskRegion#INVALID_ID invalid}.  Once the
 * object has been written to disk, the <code>id</code> is a positive
 * number.  If the value is {@linkplain Helper#update updated}, then the
 * <code>id</code> is negated to signify that the value on disk is
 * dirty.
 *
 * @see DiskRegion
 *
 * @author David Whitlock
 *
 * @since 3.2
 */
public interface DiskEntry extends RegionEntry {


  /**
   * Sets the value with a {@link RegionEntryContext}.
   * @param context the value's context.
   * @param value an entry value.
   */
  public void setValueWithContext(RegionEntryContext context,Object value);
  
  /**
   * In some cases we need to do something just before we drop the value
   * from a DiskEntry that is being moved (i.e. overflowed) to disk.
   * @param context
   */
  public void handleValueOverflow(RegionEntryContext context);
  
  /**
   * In some cases we need to do something just after we unset the value
   * from a DiskEntry that has been moved (i.e. overflowed) to disk.
   * @param context
   */
  public void afterValueOverflow(RegionEntryContext context);

  /**
   * Returns true if the DiskEntry value is equal to {@link Token#DESTROYED}, {@link Token#REMOVED_PHASE1}, or {@link Token#REMOVED_PHASE2}.
   */
  public boolean isRemovedFromDisk();
  
  /**
   * Returns the id of this <code>DiskEntry</code>
   */
  public DiskId getDiskId();

  public void _removePhase1(LocalRegion r);

  public int updateAsyncEntrySize(EnableLRU capacityController);
  
  public DiskEntry getPrev();
  public DiskEntry getNext();
  public void setPrev(DiskEntry v);
  public void setNext(DiskEntry v);

  /**
   * Used as the entry value if it was invalidated.
   */
  public static final byte[] INVALID_BYTES = new byte[0];
  /**
   * Used as the entry value if it was locally invalidated.
   */
  public static final byte[] LOCAL_INVALID_BYTES = new byte[0];
  /**
   * Used as the entry value if it was tombstone.
   */
  public static final byte[] TOMBSTONE_BYTES = new byte[0];

  ///////////////////////  Inner Classes  //////////////////////

  /**
   * A Helper class for performing functions common to all
   * <code>DiskEntry</code>s. 
   */
  public static class Helper {

    public static ValueWrapper wrapBytes(byte[] b) {
      return b != null ? new ValueWrapper(true, new WrappedBytes(b)) : NULL_VW;
    }

    public static ValueWrapper wrapBytes(byte[] b, int length) {
      return b != null ? new ValueWrapper(true, new WrappedBytes(b, 0, length))
          : NULL_VW;
    }

    /**
     * Testing purpose only
     * Get the value of an entry that is on disk without faulting
     * it in and without looking in the io buffer.
     * @since 3.2.1
     */
    static Object getValueOnDisk(DiskEntry entry, DiskRegion dr) {
      DiskId id = entry.getDiskId();
      if (id == null) {
        return null;
      }
      dr.acquireReadLock();
      try {
      synchronized (id) {
        if ((dr.isBackup() && id.getKeyId() == DiskRegion.INVALID_ID)
            || (!entry.isValueNull() && id.needsToBeWritten() && !EntryBits.isRecoveredFromDisk(id.getUserBits()))/*fix for bug 41942*/) {
          return null;
        }

        return dr.getNoBuffer(id);
      }
      } finally {
        dr.releaseReadLock();
      }
    }
    
    /**
     * Get the serialized value directly from disk.  Returned object may be
     * a {@link CachedDeserializable}.  Goes straight to disk without faulting
     * into memory.  Only looks at the disk storage, not at heap storage.
     * @param entry the entry used to identify the value to fetch
     * @param dr the persistent storage from which to fetch the value
     * @return either null, byte array, or CacheDeserializable
     * @since gemfire57_hotfix
     */
    public static Object getSerializedValueOnDisk(
        DiskEntry entry, DiskRegion dr) {
      DiskId did = entry.getDiskId();
      if (did == null) {
        return null;
      }
      dr.acquireReadLock();
      try {
      synchronized (did) {
        if (did == null
            || (dr.isBackup() && did.getKeyId() == DiskRegion.INVALID_ID)) {
          return null;
        } else if (!entry.isValueNull() && did.needsToBeWritten() && !EntryBits.isRecoveredFromDisk(did.getUserBits())/*fix for bug 41942*/) {
          return null;
        }
        return dr.getSerializedData(did);
      }
      } finally {
        dr.releaseReadLock();
      }
    }

    
    /**
     * Get the value of an entry that is on disk without
     * faulting it in . It checks for the presence in the buffer also.
     * This method is used for concurrent map operations, GemFireXD and CQ processing
     * 
     * @throws DiskAccessException
     * @since 5.1
     */
    static Object getValueOnDiskOrBuffer(DiskEntry entry, DiskRegion dr, RegionEntryContext context) {
      @Released Object v = getOffHeapValueOnDiskOrBuffer(entry, dr, context, true);
      if (v instanceof CachedDeserializable) {
        if (v instanceof Chunk) {
          @Released Chunk ohv = (Chunk) v;
          try {
            v = ohv.getDeserializedValue(null, null);
            if (v == ohv) {
              throw new IllegalStateException("gfxd tried to use getValueOnDiskOrBuffer");
            }
          } finally {
            ohv.release(); // OFFHEAP the offheap ref is decremented here
          }
        } else {
          v = ((CachedDeserializable)v).getDeserializedValue(null, null);
        }
      }
      return v;
    }

    @Retained
    static Object getOffHeapValueOnDiskOrBuffer(DiskEntry entry,
        DiskRegionView dr, RegionEntryContext context, boolean faultin) {
      return getOffHeapValueOnDiskOrBuffer(entry, dr, context, faultin, false);
    }

    @Retained
    static Object getOffHeapValueOnDiskOrBuffer(DiskEntry entry,
        DiskRegionView dr, RegionEntryContext context,
        boolean faultin, boolean rawValue) {
      DiskId did = entry.getDiskId();
      Object syncObj = did;
      if (syncObj == null) {
        syncObj = entry;
      }
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
        synchronized (syncObj) {
          if (did != null && did.isPendingAsync()) {
            @Retained Object v = getValueRetain(entry, context, rawValue); // TODO:KIRK:OK Rusty had Object v = entry.getValueWithContext(context);
            if (Token.isRemovedFromDisk(v)) {
              v = null;
            }
            return v;
          }
          if (did == null
              || ( dr.isBackup() && did.getKeyId() == DiskRegion.INVALID_ID)
              || (!entry.isValueNull() && did.needsToBeWritten() && !EntryBits.isRecoveredFromDisk(did.getUserBits()))/*fix for bug 41942*/) {
            return null;
          }

          Object v = dr.getDiskStore().getSerializedDataWithoutLock(dr, did,
              faultin);
          if (!faultin && !rawValue && (v instanceof SerializedDiskBuffer)) {
            // convert to on-heap form to avoid buildup of off-heap data
            ((SerializedDiskBuffer)v).copyToHeap("NO_FAULTIN");
          }
          return v;
        }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
    }

    public static Object getValueOnDisk(final DiskId id,
        final DiskRegionView dr) {
      dr.acquireReadLock();
      try {
        synchronized (id) {
          return getValueOnDiskNoLock(id, dr);
        }
      } finally {
        dr.releaseReadLock();
      }
    }

    public static Object getValueOnDiskNoLock(final DiskId id,
        final DiskRegionView dr) {
      final BytesAndBits bb = dr.getDiskStore().getBytesAndBitsWithoutLock(
          dr, id, false, false);
      if (bb != DiskStoreImpl.CLEAR_BB) {
        return DiskStoreImpl.convertBytesAndBitsIntoObject(bb);
      } else {
        return Token.REMOVED_PHASE1;
      }
    }

    /**
     * Returns false if the entry is INVALID (or LOCAL_INVALID). Determines this
     * without faulting in the value from disk.
     * 
     * @since 3.2.1
     */
    /* TODO prpersist - Do we need this method? It was added by the gfxd merge
    static boolean isValid(DiskEntry entry, DiskRegion dr) {
      synchronized (entry) {
        if (entry.isRecovered()) {
          // We have a recovered entry whose value is still on disk.
          // So take a peek at it without faulting it in.
          //long id = entry.getDiskId().getKeyId();
          //entry.getDiskId().setKeyId(-id);
          byte bits = dr.getBits(entry.getDiskId());
          //TODO Asif:Check if resetting is needed
          return !EntryBits.isInvalid(bits) && !EntryBits.isLocalInvalid(bits);
        }
      }
    }*/

    static boolean isOverflowedToDisk(DiskEntry de, DiskRegionView dr,
        DistributedRegion.DiskPosition dp, boolean alwaysFetchPosition) {
      if (!alwaysFetchPosition && !de.isValueNull()) {
        return false;
      }
      DiskId did;
      synchronized (de) {
        did = de.getDiskId();
      }
      Object syncObj = did;
      if (syncObj == null) {
        syncObj = de;
      }
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
      synchronized (syncObj) {
        if (alwaysFetchPosition || de.isValueNull()) {
          if (did == null) {
            synchronized (de) {
              did = de.getDiskId();
            }
            assert did != null;
            return isOverflowedToDisk(de, dr, dp, alwaysFetchPosition);
          } else {
            dp.setPosition(did.getOplogId(), did.getOffsetInOplog());
            return true;
          }
        } else {
          return false;
        }
      }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
    }
    
    /**
     * Get the value of an entry that is on disk without faulting
     * it in.
     * @param targetVersion the destination where this filled in value will be sent.
     * @since 3.2.1
     */
    static boolean fillInValue(DiskEntry de, InitialImageOperation.Entry entry,
                               LocalRegion lr, DM mgr,
                               RegionEntryContext context, Version targetVersion) {
      LogWriterI18n logger = mgr.getLoggerI18n();
      DiskRegion dr = lr.getDiskRegion();
      @Retained @Released Object v = null;
      DiskId did;
      synchronized (de) {
        did = de.getDiskId();
      }
      Object syncObj = did;
      if (syncObj == null) {
        syncObj = de;
      }
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
      synchronized (syncObj) {
        entry.setLastModified(mgr, de.getLastModified());
                              
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(entry);
        v = de._getValueRetain(context, true); // OFFHEAP copied to heap entry; todo allow entry to refer to offheap since it will be copied to network.
        SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
        if (v == null) {
          if (did == null) {
            // fix for bug 41449
            synchronized (de) {
              did = de.getDiskId();
            }
            assert did != null;
            // do recursive call to get readLock on did
            return fillInValue(de, entry, lr, mgr, context, targetVersion);
          }
          if (logger.finerEnabled()) {
            logger.finer("DiskEntry.Helper.fillInValue, key=" + entry.key
                + "; getting value from disk, disk id=" + did);
          }
          BytesAndBits bb  = null;
          try {
            bb = dr.getBytesAndBits(did, false);
          }catch(DiskAccessException dae){
            return false;
          }
          if (EntryBits.isInvalid(bb.getBits())) {
            entry.setInvalid();
          }
          else if (EntryBits.isLocalInvalid(bb.getBits())) {
            entry.setLocalInvalid();
          }
          else if (EntryBits.isTombstone(bb.getBits())) {
            entry.setTombstone();
          }
          else {
            // in case of older version entry do the transformation first
            // before shipping
            final Version version = bb.getVersion();
            boolean deserializeForTarget = !Version.CURRENT
                .equals(targetVersion);
            if (deserializeForTarget) {
              // no need to deserialize for user regions as of now
              // TODO: upgrade: need to determine the cases when
              // deserialization is needed and when not (e.g. when Gem+GemXD
              // converge then certain internal types like BigInteger have
              // different serialization which might be embedded inside a user
              // object)
              if (!lr.isUsedForMetaRegion()
                  && !lr.isUsedForPartitionedRegionAdmin()) {
                deserializeForTarget = false;
              }
            }
            // TODO: upgrade: also need to handle cases of GemFire internal
            // objects (e.g. gateway events, _PR objects) changing
            // serialization below via a versioned CachedDeserializable
            if ((version == null && !deserializeForTarget)
                || !CachedDeserializableFactory.preferObject()) {
              entry.value = bb.toBytes();
              entry.setSerialized(EntryBits.isSerialized(bb.getBits()));
            }
            else if (EntryBits.isSerialized(bb.getBits())) {
              entry.value = readSerializedValue(bb, true);
              entry.setSerialized(false);
              entry.setEagerDeserialize();
            }
            else {
              entry.value = readRawValue(bb);
              entry.setSerialized(false);
            }
            // buffer will no longer be used so clean it up eagerly
            bb.release();
          }
          return true;
        }
      }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
      entry.setSerialized(false); // starting default value
      final Class<?> vclass;
      if (Token.isRemovedFromDisk(v)) {
        // fix for bug 31757
        return false;
      }
      else if ((vclass = v.getClass()) == byte[].class) {
        entry.value = v;
      }
      else if (vclass == byte[][].class) {
        if (CachedDeserializableFactory.preferObject()) {
          entry.value = v;
          entry.setEagerDeserialize();
        }
        else {
          serializeForEntry(entry, v);
        }
      }
      else if (v == Token.INVALID) {
        entry.setInvalid();
      }
      else if (v == Token.LOCAL_INVALID) {
        // fix for bug 31107
        entry.setLocalInvalid();
      }
      else if (v == Token.TOMBSTONE) {
        entry.setTombstone();
      }
      else if (CachedDeserializable.class.isAssignableFrom(vclass)) {
        try {
          if (StoredObject.class.isAssignableFrom(vclass)
              && !((StoredObject)v).isSerialized()) {
            entry.setSerialized(false);
            entry.value = ((StoredObject)v).getDeserializedForReading();
            // For GemFireXD we prefer eager deserialized
            if (CachedDeserializableFactory.preferObject()) {
              entry.setEagerDeserialize();
            }
          }
          else {
            // don't serialize here if it is not already serialized
            Object tmp = ((CachedDeserializable)v).getValue();
            // For GemFireXD we prefer eager deserialized
            if (CachedDeserializableFactory.preferObject()) {
              entry.setEagerDeserialize();
            }
            if (tmp instanceof byte[]) {
              byte[] bb = (byte[])tmp;
              entry.value = bb;
              entry.setSerialized(true);
            }
            else {
              serializeForEntry(entry, tmp);
            }
          }
        } finally {
          // If v == entry.value then v is assumed to be an OffHeapByteSource
          // and release() will be called on v after the bytes have been read from
          // off-heap.
          if (v != entry.value) {
            OffHeapHelper.releaseWithNoTracking(v);
          }
        }
      }
      else {
        Object preparedValue = v;
        if (preparedValue != null) {
          preparedValue = AbstractRegionEntry.prepareValueForGII(preparedValue);
          if (preparedValue == null) {
            return false;
          }
        }
        if (CachedDeserializableFactory.preferObject()) {
          entry.value = preparedValue;
          entry.setEagerDeserialize();
        }
        else {
          serializeForEntry(entry, preparedValue);
        }
      }
      return true;
    }

    private static void serializeForEntry(InitialImageOperation.Entry entry,
        Object v) {
      try {
        HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
        BlobHelper.serializeTo(v, hdos);
        hdos.trim();
        entry.value = hdos;
        entry.setSerialized(true);
      } catch (IOException e) {
        RuntimeException e2 = new IllegalArgumentException(
            LocalizedStrings.DiskEntry_AN_IOEXCEPTION_WAS_THROWN_WHILE_SERIALIZING
                .toLocalizedString());
        e2.initCause(e);
        throw e2;
      }
    }

    /**
     * Used to initialize a new disk entry
     */
    public static void initialize(DiskEntry entry, DiskRecoveryStore r, Object newValue) {
      DiskRegionView drv = null;
      if (r instanceof LocalRegion) {
        drv = ((LocalRegion)r).getDiskRegion();
      } else if (r instanceof DiskRegionView) {
        drv = (DiskRegionView)r;
      }
      if (drv == null) {
        throw new IllegalArgumentException(LocalizedStrings.DiskEntry_DISK_REGION_IS_NULL.toLocalizedString());
      }

      if (Token.isRemovedFromDisk(newValue)) {
        // it is not in vm and it is not on disk
        DiskId did = entry.getDiskId();
        if (did != null) {
          did.setKeyId(DiskRegion.INVALID_ID);
        }
      }
      else if (newValue instanceof RecoveredEntry) {
        // Set the id directly, the value will also be set if RECOVER_VALUES
        RecoveredEntry re = (RecoveredEntry)newValue;
        DiskId did = entry.getDiskId();
        did.setOplogId(re.getOplogId());
        did.setOffsetInOplog(re.getOffsetInOplog());
        did.setKeyId(re.getRecoveredKeyId());
        did.setUserBits(re.getUserBits());
        did.setValueLength(re.getValueLength());
        if (re.getRecoveredKeyId() < 0) {
          drv.incNumOverflowOnDisk(1L);
          incrementBucketStats(r, 0/*InVM*/, 1/*OnDisk*/, did.getValueLength());
        }
        else {
          RegionEntryContext context = (RegionEntryContext)r;
          entry.setValueWithContext(context, entry.prepareValueForCache(context,
              re.getValue(), false, false));
          drv.incNumEntriesInVM(1L);
          incrementBucketStats(r, 1/*InVM*/, 0/*OnDisk*/, 0);
        }
      }
      else {
        DiskId did = entry.getDiskId();
        if (did != null) {
          did.setKeyId(DiskRegion.INVALID_ID);
        }
        drv.incNumEntriesInVM(1L);
        incrementBucketStats(r, 1/*InVM*/, 0/*OnDisk*/, 0);
      }
    }

    public static final ByteBuffer NULL_BUFFER = ClientSharedData.NULL_BUFFER;
    public static final ByteBuffer INVALID_BUFFER =
        ByteBuffer.wrap(INVALID_BYTES);
    public static final ByteBuffer LOCAL_INVALID_BUFFER =
        ByteBuffer.wrap(LOCAL_INVALID_BYTES);
    public static final ByteBuffer TOMBSTONE_BUFFER =
        ByteBuffer.wrap(TOMBSTONE_BYTES);

    static final ValueWrapper NULL_VW = new ValueWrapper(
        true, new WrappedBytes(ClientSharedData.ZERO_ARRAY));
    static final ValueWrapper INVALID_VW = new ValueWrapper(
        true, new WrappedBytes(INVALID_BYTES));
    static final ValueWrapper LOCAL_INVALID_VW = new ValueWrapper(
        true, new WrappedBytes(LOCAL_INVALID_BYTES));
    static final ValueWrapper TOMBSTONE_VW = new ValueWrapper(
        true, new WrappedBytes(TOMBSTONE_BYTES));

    static class ValueWrapper {

      public final boolean isSerializedObject;
      public SerializedDiskBuffer buffer;

      public static ValueWrapper create(Object value) {
        if (value == Token.INVALID) {
          // even though it is not serialized we say it is because
          // bytes will never be an empty array when it is serialized
          // so that gives us a way to specify the invalid value
          // given a byte array and a boolean flag.
          return INVALID_VW;
        }
        else if (value == Token.LOCAL_INVALID) {
          // even though it is not serialized we say it is because
          // bytes will never be an empty array when it is serialized
          // so that gives us a way to specify the local-invalid value
          // given a byte array and a boolean flag.
          return LOCAL_INVALID_VW;
        }
        else if (value == Token.TOMBSTONE) {
          return TOMBSTONE_VW;
        }
        else {
          SerializedDiskBuffer buffer;
          boolean isSerializedObject = true;
          if (value instanceof byte[]) {
            isSerializedObject = false;
            buffer = new WrappedBytes((byte[])value);
          } else if ((value instanceof SerializedDiskBuffer) ||
              !(value instanceof CachedDeserializable)) {
            Assert.assertTrue(!Token.isRemovedFromDisk(value));
            buffer = EntryEventImpl.serializeBuffer(value, null);
            if (buffer.channelSize() == 0) {
              throw new IllegalStateException("serializing <" + value +
                  "> produced empty byte array");
            }
          } else {
            byte[] bytes;
            CachedDeserializable proxy = (CachedDeserializable)value;
            if (proxy instanceof StoredObject) {
              StoredObject ohproxy = (StoredObject) proxy;
              isSerializedObject = ohproxy.isSerialized();
              if (isSerializedObject) {
                bytes = ohproxy.getSerializedValue();
              } else {
                //TODO:Asif: Speak to Darrel for cleaner way
                if (ohproxy instanceof ByteSource) {
                  bytes = ((ByteSource)ohproxy).getRowBytes();
                } else {
                  bytes = (byte[])ohproxy.getDeserializedForReading();
                }
              }
            } else {
              bytes = proxy.getSerializedValue();
            }
            buffer = bytes != null ? new WrappedBytes(bytes) : NULL_VW.buffer;
          }
          return new ValueWrapper(isSerializedObject, buffer);
        }
      }

      private ValueWrapper(boolean isSerializedObject, SerializedDiskBuffer buffer) {
        this.isSerializedObject = isSerializedObject;
        this.buffer = buffer;
      }

      public void write(OutputStreamChannel channel) throws IOException {
        this.buffer.write(channel);
      }

      /**
       * Get the data as a ByteBuffer with a retain() invoked on it. Callers
       * should normally invoked {@link #release()} when done for eager release.
       */
      public ByteBuffer getBufferRetain() {
        return this.buffer.getBufferRetain();
      }

      public int size() {
        return this.buffer.channelSize();
      }

      public void release() {
        final SerializedDiskBuffer buffer = this.buffer;
        if (buffer != null && buffer.needsRelease()) {
          this.buffer = null;
          buffer.release();
        }
      }

      @Override
      public String toString() {
        final SerializedDiskBuffer buffer = this.buffer;
        return buffer != null ? buffer.toString() : "null";
      }
    }

    /**
     * Writes the key/value object stored in the given entry to disk
     * @throws RegionClearedException
     * 
     * @see DiskRegion#put
     */
    private static void writeToDisk(DiskEntry entry, LocalRegion region, boolean async) throws RegionClearedException {
      DiskRegion dr = region.getDiskRegion();
      @Retained @Released Object value = entry._getValueRetain(region, true); // TODO:KIRK:OK Rusty had Object value = entry.getValueWithContext(region);
      ValueWrapper vw;
      try {
        vw = ValueWrapper.create(value);
      } finally {
        OffHeapHelper.release(value);
      }
      writeBytesToDisk(entry, region, async, vw);
    }
    
    private static void writeBytesToDisk(DiskEntry entry, LocalRegion region, boolean async, ValueWrapper vw) throws RegionClearedException {
      // @todo does the following unmark need to be called when an async
      // write is scheduled or is it ok for doAsyncFlush to do it?
      entry.getDiskId().unmarkForWriting();
      region.getDiskRegion().put(entry, region, vw, async);
    }

    /**
     * Updates the value of the disk entry with a new value. This allows us to
     * free up disk space in the non-backup case.
     * 
     * @throws RegionClearedException
     */
    public static void update(DiskEntry entry, LocalRegion region, Object newValue) throws RegionClearedException {
      DiskRegion dr = region.getDiskRegion();
      if (newValue == null) {
        throw new NullPointerException(LocalizedStrings.DiskEntry_ENTRYS_VALUE_SHOULD_NOT_BE_NULL.toLocalizedString());
      }
      
      //If we have concurrency checks enabled for a persistent region, we need
      //to add an entry to the async queue for every update to maintain the RVV
      boolean maintainRVV = region.concurrencyChecksEnabled && dr.isBackup();
      
      Token oldValue = null;
      int oldValueLength = 0;
      boolean scheduleAsync = false;
      boolean callRemoveFromDisk = false;
      DiskId did = entry.getDiskId();
      VersionTag tag = null;
      Object syncObj = did;
      if (syncObj == null) {
        syncObj = entry;
      }
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
      synchronized (syncObj) {
        oldValue = entry.getValueAsToken();
        if (Token.isRemovedFromDisk(newValue)) {
          if (dr.isBackup()) {
            dr.testIsRecovered(did, true); // fixes bug 41409
          }
          RuntimeException rte = null;
          try {
            if (!Token.isRemovedFromDisk(oldValue)) {
              // removeFromDisk takes care of oldValueLength
              if (dr.isSync()) {
                removeFromDisk(entry, region, false);
              } else {
                callRemoveFromDisk = true; // do it outside the sync
              }
            }
          } catch (RuntimeException e) {
            rte = e;
            throw e;
          }
          finally {
            if (rte != null && (rte instanceof CacheClosedException)) {
             // 47616: not to set the value to be removedFromDisk since it failed to persist
            } else {
              // Asif Ensure that the value is rightly set despite clear so
              // that it can be distributed correctly
              entry.setValueWithContext(region, newValue); // OFFHEAP newValue was already preparedForCache
            }
          }
        }
        else if (newValue instanceof RecoveredEntry) {
          // Now that oplog creates are immediately put in cache
          // a later oplog modify will get us here
          RecoveredEntry re = (RecoveredEntry)newValue;
          long oldKeyId = did.getKeyId();
          long oldOplogId = did.getOplogId();
          long newOplogId = re.getOplogId();
          if (newOplogId != oldOplogId) {
            did.setOplogId(newOplogId);
            re.setOplogId(oldOplogId); // so caller knows oldoplog id
          }
          did.setOffsetInOplog(re.getOffsetInOplog());
          // id already set
          did.setUserBits(re.getUserBits());
          oldValueLength = did.getValueLength();
          did.setValueLength(re.getValueLength());
          // The following undo and then do fixes bug 41849
          // First, undo the stats done for the previous recovered value
          if (oldKeyId < 0) {
            dr.incNumOverflowOnDisk(-1L);
            incrementBucketStats(region, 0/*InVM*/, -1/*OnDisk*/, -oldValueLength);
          } else {
            dr.incNumEntriesInVM(-1L);
            incrementBucketStats(region, -1/*InVM*/, 0/*OnDisk*/, 0);
          }
          // Second, do the stats done for the current recovered value
          if (re.getRecoveredKeyId() < 0) {
            if (!entry.isValueNull()) {
              try {
                entry.handleValueOverflow(region);
                entry.setValueWithContext(region, null); // fixes bug 41119
              }finally {
                entry.afterValueOverflow(region);
              }
              
            }
            dr.incNumOverflowOnDisk(1L);
            incrementBucketStats(region, 0/*InVM*/, 1/*OnDisk*/,
                                 did.getValueLength());
          } else {
            entry.setValueWithContext(region, entry.prepareValueForCache(region, re.getValue(), false, false));
            dr.incNumEntriesInVM(1L);
            incrementBucketStats(region, 1/*InVM*/, 0/*OnDisk*/, 0);
          }
        }
        else {
          //The new value in the entry needs to be set after the disk writing 
          // has succeeded. If not , for GemFireXD , it is possible that other thread
          // may pick this transient value from region entry ( which for 
          //offheap will eventually be released ) as index key, 
          //given that this operation is bound to fail in case of
          //disk access exception.
          
          //entry.setValueWithContext(region, newValue); // OFFHEAP newValue already prepared
          if (dr.isBackup()) {
            dr.testIsRecovered(did, true); // fixes bug 41409
            oldValueLength = getValueLength(did);
            if (dr.isSync()) {
              //In case of compression the value is being set first 
              // because atleast for now , GemFireXD does not support compression
              // if and when it does support, this needs to be taken care of else
              // we risk Bug 48965
              if (AbstractRegionEntry.isCompressible(dr, newValue)) {
                entry.setValueWithContext(region, newValue); // OFFHEAP newValue already prepared
                
                // newValue is prepared and compressed. We can't write compressed values to disk.
                writeToDisk(entry, region, false);
              } else {
                writeBytesToDisk(entry, region, false, ValueWrapper.create(newValue));
                entry.setValueWithContext(region, newValue); // OFFHEAP newValue already prepared
                
              }
              
            } else if (did.isPendingAsync() && !maintainRVV) {
              entry.setValueWithContext(region, newValue); // OFFHEAP newValue already prepared
              
              // nothing needs to be done except
              // fixing up LRU stats
              // @todo fixup LRU stats if needed
              // I'm not sure anything needs to be done here.
              // If we have overflow and it decided to evict this entry
              // how do we handle that case when we are async?
              // Seems like the eviction code needs to leave the value
              // in memory until the pendingAsync is done.
            } else {
              //if the entry is not async, we need to schedule it
              //for regions with concurrency checks enabled, we add an entry
              //to the queue for every entry.
              scheduleAsync = true;
              did.setPendingAsync(true);
              VersionStamp stamp = entry.getVersionStamp();
              if(stamp != null) {
                tag = stamp.asVersionTag();
              }
              entry.setValueWithContext(region, newValue); 
            }
          } else if (did != null) {
            entry.setValueWithContext(region, newValue); // OFFHEAP newValue already prepared
            
            // Mark the id as needing to be written
            // The disk remove that this section used to do caused bug 30961
            // @todo this seems wrong. How does leaving it on disk fix the bug?
            did.markForWriting();
            //did.setValueSerializedSize(0);
          }else {
            entry.setValueWithContext(region, newValue);
          }
          
          if (Token.isRemovedFromDisk(oldValue)) {
            // Note we now initialize entries removed and then set their
            // value once we find no existing entry.
            // So this is the normal path for a brand new entry.
            dr.incNumEntriesInVM(1L);
            incrementBucketStats(region, 1/*InVM*/, 0/*OnDisk*/, 0);
          }
        }
        if (entry instanceof LRUEntry) {
          LRUEntry le = (LRUEntry)entry;
          boolean wasEvicted = le.testEvicted();
          le.unsetEvicted();
          if (!Token.isRemovedFromDisk(newValue)) {
            if (oldValue == null
                // added null check for bug 41759
                || wasEvicted && did != null && did.isPendingAsync()) {
              // Note we do not append this entry because that will be
              // done by lruEntryUpdate
              dr.incNumEntriesInVM(1L);
              dr.incNumOverflowOnDisk(-1L);
              incrementBucketStats(region, 1/*InVM*/, -1/*OnDisk*/, -oldValueLength);
            }
          }
        }
      }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
      if (callRemoveFromDisk) {
        removeFromDisk(entry, region, false, oldValue == null, false);
      } else if (scheduleAsync && did.isPendingAsync()) {
        // this needs to be done outside the above sync
        scheduleAsyncWrite(new AsyncDiskEntry(region, entry, tag));
      }
    }

    private static int getValueLength(DiskId did) {
      int result = 0;
      if (did != null) {
        synchronized (did) {
          result = did.getValueLength();
        }
      }
      return result;
    }

    public static void updateRecoveredEntry(PlaceHolderDiskRegion drv,
                                              DiskEntry entry,
                                              RecoveredEntry newValue,RegionEntryContext context)
    {
      if (newValue == null) {
        throw new NullPointerException(LocalizedStrings.DiskEntry_ENTRYS_VALUE_SHOULD_NOT_BE_NULL.toLocalizedString());
      }
      DiskId did = entry.getDiskId();
      synchronized (did) {
        boolean oldValueWasNull = entry.isValueNull();
        // Now that oplog creates are immediately put in cache
        // a later oplog modify will get us here
        long oldOplogId = did.getOplogId();
        long newOplogId = newValue.getOplogId();
        if (newOplogId != oldOplogId) {
          did.setOplogId(newOplogId);
          newValue.setOplogId(oldOplogId); // so caller knows oldoplog id
        }
        did.setOffsetInOplog(newValue.getOffsetInOplog());
        // id already set
        did.setUserBits(newValue.getUserBits());
        did.setValueLength(newValue.getValueLength());
        if (newValue.getRecoveredKeyId() >= 0) {
          entry.setValueWithContext(context, entry.prepareValueForCache(drv, newValue.getValue(), 
              false, false));
        } else {
          if (!oldValueWasNull) {
            try {
              entry.handleValueOverflow(context);
              entry.setValueWithContext(context,null); // fixes bug 41119
            }finally {
              entry.afterValueOverflow(context);
            }
          }
        }
        if (entry instanceof LRUEntry) {
          LRUEntry le = (LRUEntry)entry;
          assert !le.testEvicted();
          // we don't allow eviction during recovery
          if (oldValueWasNull) {
            // Note we do not append this entry because that will be
            // done by lruEntryUpdate
            drv.incNumEntriesInVM(1L);
            drv.incNumOverflowOnDisk(-1L);
          }
        }
      }
    }

    private static Object getValueRetain(DiskEntry entry, RegionEntryContext context,
        boolean rawValue) {
      @Retained Object v = entry._getValueRetain(context, true);
      if (rawValue && GemFireCacheImpl.hasNewOffHeap() &&
          (v instanceof SerializedDiskBuffer)) {
        return ((SerializedDiskBuffer)v).getValueRetain(FetchRequest.ORIGINAL);
      }
      return v;
    }

    @Retained
    public static Object getValueOffHeapOrDiskWithoutFaultIn(DiskEntry entry,
        LocalRegion region, boolean rawValue) {
      return getValueOffHeapOrDiskWithoutFaultIn(entry, region,
          region.getDiskRegion(), rawValue);
    }

    @Retained
    public static Object getValueOffHeapOrDiskWithoutFaultIn(DiskEntry entry,
        LocalRegion region, DiskRegion dr, boolean rawValue) {
      @Retained Object v = getValueRetain(entry, region, rawValue); // TODO:KIRK:OK Object v = entry.getValueWithContext(region);
      final boolean isRemovedFromDisk = Token.isRemovedFromDisk(v);
      if ((v == null || isRemovedFromDisk)
          && !region.isIndexCreationThread()) {
        synchronized (entry) {
          v = getValueRetain(entry, region, rawValue); // TODO:KIRK:OK v = entry.getValueWithContext(region);
          if (v == null) {
            v = Helper.getOffHeapValueOnDiskOrBuffer(entry,
                dr, region, false, rawValue);
          }
        }
      }
      if (isRemovedFromDisk) {
        // fix for bug 31800
        v = null;
      } else if (v instanceof ByteSource) {
        // If the ByteSource contains a Delta or ListOfDelta then we want to deserialize it
        final ByteSource bs = (ByteSource)v;
        Object deserVal = bs.getDeserializedForReading();
        if (deserVal != v) {
          bs.release();
          v = deserVal;
        }
      }
      return v;
    }

    @Retained
    public static Object getValueHeapOrDiskWithoutFaultIn(DiskEntry entry,
        LocalRegion region) {
      Object v;
      if (region.compressor == null) {
        v = entry._getValue();
        if (v != null && !Token.isRemovedFromDisk(v)) {
          return v;
        }
      } else {
        v = AbstractRegionEntry.decompress(region, entry._getValue());
        if (v != null && !Token.isRemovedFromDisk(v)) {
          return v;
        }
      }
      if (!region.isIndexCreationThread()) {
        synchronized (entry) {
          if (region.compressor == null) {
            v = entry._getValue();
          } else {
            v = AbstractRegionEntry.decompress(region, entry._getValue());
          }
          if (v == null) {
            v = Helper.getOffHeapValueOnDiskOrBuffer(entry,
                region.getDiskRegion(), region, false);
          }
        }
        return v;
      } else {
        return null;
      }
    }

    @Retained
    public static Object getValueOffHeapOrDiskWithoutFaultIn(DiskEntry entry,
        DiskRegionView dr, RegionEntryContext context) {
      @Retained Object v = entry._getValueRetain(context, true);
      if (v == null || Token.isRemovedFromDisk(v)) {
        synchronized (entry) {
          v = entry._getValueRetain(context, true);
          if (v == null) {
            v = Helper.getOffHeapValueOnDiskOrBuffer(entry, dr, context, false);
          }
        }
      }
      if (Token.isRemovedFromDisk(v)) {
        // fix for bug 31800
        v = null;
      } else if (v instanceof ByteSource) {
        // If the ByteSource contains a Delta or ListOfDelta then we want to deserialize it
        final ByteSource bs = (ByteSource)v;
        Object deserVal = bs.getDeserializedForReading();
        if (deserVal != v) {
          bs.release();
          v = deserVal;
        }
      }
      return v;
    }

    /**
     * 
     * @param entry
     * @param region
     * @return Value
     * @throws DiskAccessException
     */
    @Retained
    public static Object faultInValue(DiskEntry entry, LocalRegion region)
    {
      DiskRegion dr = region.getDiskRegion();
      @Retained Object v = entry._getValueRetain(region, true); // TODO:KIRK:OK Object v = entry.getValueWithContext(region);
      boolean lruFaultedIn = false;
      boolean done = false;
      try {
      //Asif: If the entry is instance of LRU then DidkRegion cannot be null.
      //Since GemFireXD is accessing this method direcly & it passes the owning region,
      //if the region happens to be persistent PR type, the owning region passed is PR,
      // but it will have DiskRegion as null. GemFireXD takes care of passing owning region
      // as BucketRegion in case of Overflow type entry. This is fix for Bug # 41804
      if (!dr.isSync() && entry instanceof LRUEntry) {
        synchronized (entry) {
          DiskId did = entry.getDiskId();
          if (did != null && did.isPendingAsync()) {
            done = true;
            // See if it is pending async because of a faultOut.
            // If so then if we are not a backup then we can unschedule the pending async.
            // In either case we need to do the lruFaultIn logic.
            boolean evicted = ((LRUEntry)entry).testEvicted();
            if (evicted) {
              if (!dr.isBackup()) {
                // @todo do we also need a bit that tells us if it is in the async queue?
                // Seems like we could end up adding it to the queue multiple times.
                did.setPendingAsync(false);
              }
              // since it was evicted fix the stats here
              dr.incNumEntriesInVM(1L);
              dr.incNumOverflowOnDisk(-1L);
              // no need to dec overflowBytesOnDisk because it was not inced in this case.
              incrementBucketStats(region, 1/*InVM*/, -1/*OnDisk*/, 0);
            }
            lruEntryFaultIn((LRUEntry) entry, region);
            lruFaultedIn = true;
          }
        }
      }
      if (!done
          && (v == null || Token.isRemovedFromDisk(v) && !region.isIndexCreationThread())) {
        synchronized (entry) {
          v = entry._getValueRetain(region, true); // TODO:KIRK:OK v = entry.getValueWithContext(region);
          if (v == null) {
            v = readValueFromDisk(entry, region);
            if (entry instanceof LRUEntry) {
              if (v != null && !Token.isInvalid(v)) {
                lruEntryFaultIn((LRUEntry) entry, region);
                lruFaultedIn = true;
              }
            }
          }
        }
      }
      } finally {
        if (region.getEnableOffHeapMemory()) {
          v = OffHeapHelper.copyAndReleaseIfNeeded(v);
        }
        // At this point v should be either a heap object or a retained gfxd off-heap reference.
      }
      if (Token.isRemoved(v)) {
        // fix for bug 31800
        v = null;
      } else {
        entry.setRecentlyUsed();
      }
      if (lruFaultedIn) {
       lruUpdateCallback(region);
      }
//      dr.getOwner().getCache().getLogger().info("DEBUG: faulted in entry " + entry.getKey());
      return v; // OFFHEAP: the value ends up being returned by RegionEntry.getValue
    }

    public static void recoverValue(DiskEntry entry, long oplogId,
        DiskRecoveryStore recoveryStore) {
      boolean lruFaultedIn = false;
      synchronized (entry) {
        if (entry.isValueNull()) {
          DiskId did = entry.getDiskId();
          if (did != null) {
            Object value = null;
            DiskRecoveryStore region = recoveryStore;
            DiskRegionView dr = region.getDiskRegionView();
            dr.acquireReadLock();
            try {
              synchronized (did) {
                // don't read if the oplog has changed.
                if (oplogId == did.getOplogId()) {
                  value = getValueFromDisk(dr, did);
                  if (value != null) {
                    setValueOnFaultIn(value, did, entry, dr, region);
                  } 
                }
              }
            } finally {
              dr.releaseReadLock();
            }
            if (entry instanceof LRUEntry) {
              if (value != null && !Token.isInvalid(value)) {
                lruEntryFaultIn((LRUEntry) entry, recoveryStore);
                lruFaultedIn = true;
              }
            }
          }
        }
      }
      if (lruFaultedIn) {
        lruUpdateCallback(recoveryStore);
      }
    }
    
    /**
     *  Caller must have "did" synced.
     */
    private static Object getValueFromDisk(DiskRegionView dr, DiskId did) {
      Object value;
      if (dr.isBackup() && did.getKeyId() == DiskRegion.INVALID_ID) {
        // must have been destroyed
        value = null;
      } else {
        if (did.isKeyIdNegative()) {
          did.setKeyId(- did.getKeyId());
        }
        // if a bucket region then create a CachedDeserializable here instead of object
        value = dr.getRaw(did); // fix bug 40192
        if (value instanceof BytesAndBits) {
          BytesAndBits bb = (BytesAndBits)value;
          final byte bits = bb.getBits();
          if (EntryBits.isInvalid(bits)) {
            value = Token.INVALID;
          } else if (EntryBits.isLocalInvalid(bits)) {
            value = Token.LOCAL_INVALID;
          } else if (EntryBits.isTombstone(bits)) {
            value = Token.TOMBSTONE;
          } else if (EntryBits.isSerialized(bits)) {
            value = readSerializedValue(bb, false);
          } else {
            value = readRawValue(bb);
          }
          // buffer will no longer be used so clean it up eagerly
          bb.release();
        }
      }
      return value;
    }

    private static void lruUpdateCallback(DiskRecoveryStore recoveryStore) {
      /* 
       * Used conditional check to see if
       * if its a LIFO Enabled,
       * yes then disable lruUpdateCallback()
       * and called updateStats()
       * its keep track of actual entries
       * present in memory - useful when 
       * checking capacity constraint
       */ 
      try {
        if (recoveryStore.getEvictionAttributes() != null
            && recoveryStore.getEvictionAttributes().getAlgorithm().isLIFO()) {
          ((VMLRURegionMap) recoveryStore.getRegionMap()).updateStats();
          return;
        }
        // this must be done after releasing synchronization
        recoveryStore.getRegionMap().lruUpdateCallback();
      }catch( DiskAccessException dae) {
        recoveryStore.handleDiskAccessException(dae, true/* stop bridge servers*/);
        throw dae;
      }
    }
    
    private static void lruEntryFaultIn(LRUEntry entry, DiskRecoveryStore recoveryStore) {
      RegionMap rm = recoveryStore.getRegionMap();
      try {
        rm.lruEntryFaultIn(entry);
        // Notify the GemFireXD IndexManager if present
        final IndexUpdater indexUpdater = rm.getIndexUpdater();
        if (indexUpdater != null) {
          indexUpdater.onFaultInFromDisk(entry);
        }
      } catch (DiskAccessException dae) {
        recoveryStore.handleDiskAccessException(dae, true/* stop bridge servers*/);
        throw dae;
      }
    }
    
    /**
     * Returns the value of this map entry, reading it from disk, if necessary.
     * Sets the value in the entry.
     * This is only called by the faultIn code once it has determined that
     * the value is no longer in memory.
     * return the result will only be off-heap if the value is a gfxd ByteSource. Otherwise result will be on-heap.
     * Caller must have "entry" synced.
     */
    @Retained
    public static Object readValueFromDisk(DiskEntry entry, DiskRecoveryStore region) {

      DiskRegionView dr = region.getDiskRegionView();
      DiskId did = entry.getDiskId();
      if (did == null) {
        return null;
      }
      dr.acquireReadLock();
      try {
      synchronized (did) {
        Object value = getValueFromDisk(dr, did);
        if (value == null) return null;
        @Unretained Object preparedValue = setValueOnFaultIn(value, did, entry, dr, region);
        // For Gemfirexd we want to return the offheap representation.
        // So we need to retain it for the caller to release.
        if (preparedValue instanceof Chunk) {
          // This is the only case in which we return a retained off-heap ref.
          ((Chunk)preparedValue).retain();
          return preparedValue;
        } else {
          return value;
        }
      }
      } finally {
        dr.releaseReadLock();
      }
    }
    
    /**
     * Caller must have "entry" and "did" synced and "dr" readLocked.
     * @return the unretained result must be used by the caller before it releases the sync on "entry".
     */
    @Unretained
    private static Object setValueOnFaultIn(Object value, DiskId did, DiskEntry entry, DiskRegionView dr, DiskRecoveryStore region) {
//    dr.getOwner().getCache().getLogger().info("DEBUG: faulting in entry with key " + entry.getKey());
      int bytesOnDisk = getValueLength(did);
      // Retained by the prepareValueForCache call for the region entry.
      // NOTE that we return this value unretained because the retain is owned by the region entry not the caller.
      @Retained Object preparedValue = entry.prepareValueForCache((RegionEntryContext) region, value,
          false, false);

      //Putting a workaround here. At this moment no real region is assigned for recovery.
      //All value calculations come as zero. The below updateSizeOnFaultIn is wrong and give does not update the
      // stats after recovery. @TODO fix the PR region Stats.
      int recoveredValueSize = BucketRegion.calcMemSize(preparedValue);

      if (!dr.isMetaTable()) {
        boolean acquired = CallbackFactoryProvider.getStoreCallbacks().acquireStorageMemory(
                dr.getName(), recoveredValueSize, null, true, false);

        if (!acquired) {
          Set<DistributedMember> sm = Collections.singleton(GemFireCacheImpl.getExisting().getMyId());
          throw new LowMemoryException("Could not obtain memory of size " + recoveredValueSize, sm);
        }
      }

      region.updateSizeOnFaultIn(entry.getKey(), recoveredValueSize, bytesOnDisk);
      //did.setValueSerializedSize(0);
      // I think the following assertion is true but need to run
      // a regression with it. Reenable this post 6.5
      // Assert.assertTrue(entry._getValue() == null);
      entry.setValueWithContext((RegionEntryContext) region, preparedValue);
      dr.incNumEntriesInVM(1L);
      dr.incNumOverflowOnDisk(-1L);
      incrementBucketStats(region, 1/*InVM*/, -1/*OnDisk*/, -bytesOnDisk);
      return preparedValue;
    }

    static Object readSerializedValue(byte[] valueBytes, Version version,
        ByteArrayDataInput in, boolean forceDeserialize) {
      if (forceDeserialize || CachedDeserializableFactory.preferObject()) {
        // deserialize checking for product version change
        return EntryEventImpl.deserialize(valueBytes, version, in);
      }
      else {
        // TODO: upgrades: is there a case where GemFire values are internal
        // ones that need to be upgraded transparently; probably messages
        // being persisted (gateway events?)
        return CachedDeserializableFactory.create(valueBytes);
      }
    }

    static Object readSerializedValue(BytesAndBits value,
        boolean forceDeserialize) {
      if (forceDeserialize || CachedDeserializableFactory.preferObject()) {
        // deserialize checking for product version change
        return value.deserialize();
      } else {
        // TODO: upgrades: is there a case where GemFire values are internal
        // ones that need to be upgraded transparently; probably messages
        // being persisted (gateway events?)
        return CachedDeserializableFactory.create(value.toBytes());
      }
    }

    static Object readRawValue(BytesAndBits value) {
      // no longer support pre SQLF 1.1 so no change for RowFormatter bytes
      /*
      final StaticSystemCallbacks sysCb;
      if (version != null && (sysCb = GemFireCacheImpl.FactoryStatics
          .systemCallbacks) != null) {
        // may need to change serialized shape for GemFireXD
        return sysCb.fromVersion(valueBuffer, valueBuffer.length, false, version);
      } else {
        return valueBuffer;
      }
      */
      return value.toBytes();
    }

    public static void incrementBucketStats(Object owner,
                                             int entriesInVmDelta,
                                             int overflowOnDiskDelta,
                                             int overflowBytesOnDiskDelta) {
      if (owner instanceof BucketRegion) {
        ((BucketRegion)owner).incNumEntriesInVM(entriesInVmDelta);
        ((BucketRegion)owner).incNumOverflowOnDisk(overflowOnDiskDelta);
        ((BucketRegion)owner).incNumOverflowBytesOnDisk(overflowBytesOnDiskDelta);
      } else if (owner instanceof DiskRegionView) {
        ((DiskRegionView)owner).incNumOverflowBytesOnDisk(overflowBytesOnDiskDelta);
      }
    }

    /**
     * Writes the value of this <code>DiskEntry</code> to disk and
     * <code>null</code> s out the reference to the value to free up VM space.
     * <p>
     * Note that if the value had already been written to disk, it is not
     * written again.
     * <p>
     * Caller must synchronize on entry and it is assumed the entry is evicted
     * 
     * see #writeToDisk
     * @throws RegionClearedException
     */
    public static int overflowToDisk(DiskEntry entry, LocalRegion region, EnableLRU ccHelper) throws RegionClearedException {
      {
        Token entryVal = entry.getValueAsToken();
        if (entryVal == null || Token.isRemovedFromDisk(entryVal)) {
          // Note it could be removed token now because
          // freeAllEntriesOnDisk is not able to sync on entry
          return 0;
        }
      }
      final int oldSize = region.calculateRegionEntryValueSize(entry);
      int diskIDOverhead =  0;
//      dr.getOwner().getCache().getLogger().info("DEBUG: overflowing entry with key " + entry.getKey());
      //Asif:Get diskID . If it is null, it implies it is
      // overflow only mode.
      //long id = entry.getDiskId().getKeyId();
      DiskId did = entry.getDiskId();
      if (did == null) {
        ((AbstractDiskLRURegionEntry)entry).setDelayedDiskId(region);
        did = entry.getDiskId();
        // add DiskId overhead to change
        diskIDOverhead += region.calculateDiskIdOverhead(did);
      }
      
      // Notify the GemFireXD IndexManager if present
     /* final IndexUpdater indexUpdater = region.getIndexUpdater();
      if(indexUpdater != null && dr.isSync()) {
        indexUpdater.onOverflowToDisk(entry);
      }*/

      int change = 0;
      boolean scheduledAsyncHere = false;
      final DiskRegion dr = region.getDiskRegion();
      dr.acquireReadLock();
      try {
      synchronized (did) {
        // check for a concurrent freeAllEntriesOnDisk
        if (entry.isRemovedFromDisk()) {
          return 0;
        }

        //TODO:Asif: Check if we need to overflow even when id is = 0
        boolean wasAlreadyPendingAsync = did.isPendingAsync();
        if (did.needsToBeWritten()) {
          if (dr.isSync()) {
            writeToDisk(entry, region, false);
          } else if (!wasAlreadyPendingAsync) {
            scheduledAsyncHere = true;
            did.setPendingAsync(true);
          } else {
            // it may have been scheduled to be written (isBackup==true)
            // and now we are faulting it out
          }
        }

        boolean movedValueToDisk = false; // added for bug 41849
        
        // If async then if it does not need to be written (because it already was)
        // then treat it like the sync case. This fixes bug 41310
        if (scheduledAsyncHere || wasAlreadyPendingAsync) {
          // we call _setValue(null) after it is actually written to disk
          change += entry.updateAsyncEntrySize(ccHelper);
          // do the stats when it is actually written to disk
        } else {
          //did.setValueSerializedSize(byteSizeOnDisk);
          try {
            entry.handleValueOverflow(region);
            entry.setValueWithContext(region,null);
          }finally {
            entry.afterValueOverflow(region);
          }
          region.updateSizeOnEvict(entry.getKey(), oldSize);
          movedValueToDisk = true;
          change = ((LRUClockNode)entry).updateEntrySize(ccHelper);
        }
        dr.incNumEntriesInVM(-1L);
        dr.incNumOverflowOnDisk(1L);
        int valueLength = 0;
        if (movedValueToDisk) {
          valueLength = getValueLength(did);
        }

        region.freePoolMemory(oldSize, false);
        if (diskIDOverhead > 0) {
          //Account positive memory increase for eviction thread.
          region.acquirePoolMemory(0, diskIDOverhead, false, null, false);
        }

        incrementBucketStats(region, -1/*InVM*/, 1/*OnDisk*/, valueLength);
      }
      } finally {
        dr.releaseReadLock();
      }
      if (scheduledAsyncHere && did.isPendingAsync()) {
        // this needs to be done outside the above sync
        // the version tag is null here because this method only needs
        // to write to disk for overflow only regions, which do not need
        // to maintain an RVV on disk.
        scheduleAsyncWrite(new AsyncDiskEntry(region, entry, null));
      }
      return change;
    }

    private static void scheduleAsyncWrite(AsyncDiskEntry ade) {
      DiskRegion dr = ade.region.getDiskRegion();
      dr.scheduleAsyncWrite(ade);
    }

    
    public static void handleFullAsyncQueue(DiskEntry entry, LocalRegion region, VersionTag tag) {
      DiskRegion dr = region.getDiskRegion();
      DiskId did = entry.getDiskId();
      synchronized (entry) {
      dr.acquireReadLock();
      try {
        synchronized (did) {
          if (did.isPendingAsync()) {
            did.setPendingAsync(false);
            final Token entryVal = entry.getValueAsToken();
            final int entryValSize = region.calculateRegionEntryValueSize(entry);
            boolean remove = false;
            try {
              if (Token.isRemovedFromDisk(entryVal)) {
                // onDisk was already deced so just do the valueLength here
                incrementBucketStats(region, 0/*InVM*/, 0/*OnDisk*/,
                                     -did.getValueLength());
                dr.remove(region, entry, true, false);
                if (dr.isBackup()) {
                  did.setKeyId(DiskRegion.INVALID_ID); // fix for bug 41340
                }
                remove = true;
              } else if (Token.isInvalid(entryVal) && !dr.isBackup()) {
                // no need to write invalid to disk if overflow only
              } else if (entryVal != null) {
                writeToDisk(entry, region, true);
              } else {
                //if we have a version tag we need to record the operation
                //to update the RVV
                if(tag != null) {
                  DiskEntry.Helper.doAsyncFlush(tag, region);
                }
                return;
              }
              assert !dr.isSync();
              // Only setValue to null if this was an evict.
              // We could just be a backup that is writing async.
              if (!remove
                  && !Token.isInvalid(entryVal)
                  && entry instanceof LRUEntry
                  && ((LRUEntry)entry).testEvicted()) {
                // Moved this here to fix bug 40116.
                region.updateSizeOnEvict(entry.getKey(), entryValSize);
                // note the old size was already accounted for
                // onDisk was already inced so just do the valueLength here
                incrementBucketStats(region, 0/*InVM*/, 0/*OnDisk*/,
                                     did.getValueLength());
                try {
                  entry.handleValueOverflow(region);
                  entry.setValueWithContext(region,null);
                }finally {
                  entry.afterValueOverflow(region);
                }
              }
              
              //See if we the entry we wrote to disk has the same tag
              //as this entry. If not, write the tag as a conflicting operation.
              //to update the RVV.
              VersionStamp stamp = entry.getVersionStamp();
              if(tag != null && stamp != null 
                  && (stamp.getMemberID() != tag.getMemberID()
                    || stamp.getRegionVersion() != tag.getRegionVersion())) {
                DiskEntry.Helper.doAsyncFlush(tag, region);
              }
            } catch (RegionClearedException ignore) {
              // no need to do the op since it was clobbered by a region clear
            }
          } else {
            //if we have a version tag we need to record the operation
            //to update the RVV, even if we don't write the entry
            if(tag != null) {
              DiskEntry.Helper.doAsyncFlush(tag, region);
            }
          }
        }
      } finally {
        dr.releaseReadLock();
      }
      } // sync entry
    }
    
    public static void doAsyncFlush(VersionTag tag, LocalRegion region) {
      if (region.isThisRegionBeingClosedOrDestroyed()) return;
      DiskRegion dr = region.getDiskRegion();
      if (!dr.isBackup()) {
        return;
      }
      assert !dr.isSync();
      dr.acquireReadLock();
      try {
        dr.getDiskStore().putVersionTagOnly(region, tag, true);
      } finally {
        dr.releaseReadLock();
      }
    }
    
    /**
     * Flush an entry that was previously scheduled to be written to disk.
     * @param tag 
     * @since prPersistSprint1
     */
    public static void doAsyncFlush(DiskEntry entry, LocalRegion region, VersionTag tag) {
      if (region.isThisRegionBeingClosedOrDestroyed()) return;
      DiskRegion dr = region.getDiskRegion();
      dr.setClearCountReference();
      synchronized (entry) { // fixes 40116
        // If I don't sync the entry and this method ends up doing an eviction
        // thus setting value to null
        // some other thread is free to fetch the value while the entry is synced
        // and think it has removed it or replaced it. This results in updateSizeOn*
        // being called twice for the same value (once when it is evicted and once
        // when it is removed/updated).
      try {
      dr.acquireReadLock();
      try {
        DiskId did = entry.getDiskId();
        synchronized (did) {
          if (did.isPendingAsync()) {
            did.setPendingAsync(false);
            final Token entryVal = entry.getValueAsToken();
            final int entryValSize = region.calculateRegionEntryValueSize(entry);
            boolean remove = false;
            try {
              if (Token.isRemovedFromDisk(entryVal)) {
                if (region.isThisRegionBeingClosedOrDestroyed()) return;
                // onDisk was already deced so just do the valueLength here
                incrementBucketStats(region, 0/*InVM*/, 0/*OnDisk*/,
                                     -did.getValueLength());
                dr.remove(region, entry, true, false);
                if (dr.isBackup()) {
                  did.setKeyId(DiskRegion.INVALID_ID); // fix for bug 41340
                }
                remove = true;
              } else if ((Token.isInvalid(entryVal) || entryVal == Token.TOMBSTONE) && !dr.isBackup()) {
                // no need to write invalid or tombstones to disk if overflow only
              } else if (entryVal != null) {
                writeToDisk(entry, region, true);
//                 dr.getOwner().getCache().getLogger().info("DEBUG: overflowing async entry with key " + entry.getKey());
              } else {
                // @todo why would we have a null value here?
                // I'm seeing it show up in tests:
// java.lang.IllegalArgumentException: Must not serialize  null  in this context.
// 	at com.gemstone.gemfire.internal.cache.EntryEventImpl.serialize(EntryEventImpl.java:1024)
// 	at com.gemstone.gemfire.internal.cache.DiskEntry$Helper.writeToDisk(DiskEntry.java:351)
// 	at com.gemstone.gemfire.internal.cache.DiskEntry$Helper.doAsyncFlush(DiskEntry.java:683)
// 	at com.gemstone.gemfire.internal.cache.DiskRegion$FlusherThread.run(DiskRegion.java:1055)
                //if we have a version tag we need to record the operation
                //to update the RVV
                if(tag != null) {
                  DiskEntry.Helper.doAsyncFlush(tag, region);
                }
                return;
              }
              assert !dr.isSync();
              // Only setValue to null if this was an evict.
              // We could just be a backup that is writing async.
              if (!remove
                  && !Token.isInvalid(entryVal)
                  && (entryVal != Token.TOMBSTONE)
                  && entry instanceof LRUEntry
                  && ((LRUEntry)entry).testEvicted()) {
                // Moved this here to fix bug 40116.
                region.updateSizeOnEvict(entry.getKey(), entryValSize);
                // note the old size was already accounted for
                // onDisk was already inced so just do the valueLength here
                incrementBucketStats(region, 0/*InVM*/, 0/*OnDisk*/,
                                     did.getValueLength());
                try {
                 entry.handleValueOverflow(region);
                 entry.setValueWithContext(region,null);
                }finally {
                  entry.afterValueOverflow(region);
                }
              }
            } catch (RegionClearedException ignore) {
              // no need to do the op since it was clobbered by a region clear
            }
            
            //See if we the entry we wrote to disk has the same tag
            //as this entry. If not, write the tag as a conflicting operation.
            //to update the RVV.
            VersionStamp stamp = entry.getVersionStamp();
            if(tag != null && stamp != null 
                && (stamp.getMemberID() != tag.getMemberID() 
                || stamp.getRegionVersion() != tag.getRegionVersion())) {
              DiskEntry.Helper.doAsyncFlush(tag, region);
            }
          } else {
            //if we have a version tag we need to record the operation
            //to update the RVV
            if(tag != null) {
              DiskEntry.Helper.doAsyncFlush(tag, region);
            }
          }
        }
      } finally {
        dr.releaseReadLock();
      }
      } finally {
        dr.removeClearCountReference();
      }
      } // sync entry
    }
    
    /**
     * Removes the key/value pair in the given entry from disk
     *
     * @throws RegionClearedException If the operation is aborted due to a clear 
     * @see DiskRegion#remove
     */
    public static void removeFromDisk(DiskEntry entry, LocalRegion region, boolean isClear) throws RegionClearedException {
      removeFromDisk(entry, region, true, false, isClear);
    }
    private static void removeFromDisk(DiskEntry entry, LocalRegion region,
                                      boolean checkValue, boolean valueWasNull, boolean isClear) throws RegionClearedException {
      DiskRegion dr = region.getDiskRegion();
      
      //If we have concurrency checks enabled for a persistent region, we need
      //to add an entry to the async queue for every update to maintain the RVV
      boolean maintainRVV = region.concurrencyChecksEnabled && dr.isBackup();
      
      DiskId did = entry.getDiskId();
      VersionTag tag = null;
      Object syncObj = did;
      if (did == null) {
        syncObj = entry;
      }
      boolean scheduledAsyncHere = false;
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
      synchronized (syncObj) { 

        if (did == null || (dr.isBackup() && did.getKeyId()== DiskRegion.INVALID_ID)) {
          // Not on disk yet
//          InternalDistributedSystem.getLoggerI18n().fine("DEBUG: removing Entry " + entry.getKey()
//              + (did==null? " that is not on disk"
//                  : "with dr.isBackup and invalid keyID"));
          dr.incNumEntriesInVM(-1L);
          incrementBucketStats(region, -1/*InVM*/, 0/*OnDisk*/, 0);
          dr.unscheduleAsyncWrite(did);
          return;
        } 
        //Asif: This will convert the -ve OplogKeyId to positive as part of fixing
        //Bug # 39989
        did.unmarkForWriting();

        //System.out.println("DEBUG: removeFromDisk doing remove(" + id + ")");
        int oldValueLength = 0;
        if (dr.isSync() || isClear) {
          oldValueLength = did.getValueLength();
          dr.remove(region, entry, false, isClear);
          if (dr.isBackup()) {
            did.setKeyId(DiskRegion.INVALID_ID); // fix for bug 41340
          }
          //If this is a clear, we should unschedule the async write for this
          //entry
          did.setPendingAsync(false);
        } else {
          if (!did.isPendingAsync() || maintainRVV) {
            scheduledAsyncHere = true;
            did.setPendingAsync(true);
            VersionStamp stamp = entry.getVersionStamp();
            if(stamp != null) {
              tag = stamp.asVersionTag();
            }
          }
        }
//        InternalDistributedSystem.getLoggerI18n().fine("DEBUG: removing Entry " + entry.getKey()
//            + " with value " + entry._getValue() + "checkValue=" + checkValue);
        if (checkValue) {
          valueWasNull = entry.isValueNull();
          entry._removePhase1(region);
        }
        if (valueWasNull) {
          dr.incNumOverflowOnDisk(-1L);
          incrementBucketStats(region, 0/*InVM*/, -1/*OnDisk*/, -oldValueLength);
        }
        else {
          dr.incNumEntriesInVM(-1L);
          incrementBucketStats(region, -1/*InVM*/, 0/*OnDisk*/, 0);
          if (!dr.isSync()) {
            // we are going to do an async remove of an entry that is not currently
            // overflowed to disk so we don't want to count its value length as being
            // on disk when we finally do the async op. So we clear it here.
            did.setValueLength(0);
          }
        }
      }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
      if (scheduledAsyncHere && did.isPendingAsync()) {
        // do this outside the sync
        scheduleAsyncWrite(new AsyncDiskEntry(region, entry, tag));
      }
    }

    /**
     * @param entry
     * @param region
     * @param tag
     */
    public static void updateVersionOnly(DiskEntry entry, LocalRegion region,
        VersionTag tag) {
      DiskRegion dr = region.getDiskRegion();
      if (!dr.isBackup()) {
        return;
      }
      
      assert tag != null && tag.getMemberID()!=null;
      boolean scheduleAsync = false;
      DiskId did = entry.getDiskId();
      Object syncObj = did;
      if (syncObj == null) {
        syncObj = entry;
      }
      if (syncObj == did) {
        dr.acquireReadLock();
      }
      try {
        synchronized (syncObj) {
          if (dr.isSync()) {
            dr.getDiskStore().putVersionTagOnly(region, tag, false);
          } else {
            scheduleAsync = true;
          }
        }
      } finally {
        if (syncObj == did) {
          dr.releaseReadLock();
        }
      }
      if (scheduleAsync) {
        // this needs to be done outside the above sync
        scheduleAsyncWrite(new AsyncDiskEntry(region, tag));
      }
    }

  }

  /**
   * A marker object for an entry that has been recovered from disk.
   * It is handled specially when it is placed in a region.
   */
  public static final class RecoveredEntry {

    /** The disk id of the entry being recovered */
    private final long recoveredKeyId;

    /** The value of the recovered entry */
    private final Object value;

    private final long offsetInOplog;
    private final byte userBits;
    private final int valueLength;

    private long oplogId;
    private VersionTag tag;

    /** last modified timestamp for no-versions (#45397) */
    private long lastModifiedTime;

    /**
     * Only for this constructor, the value is not loaded into the region & it is lying
     * on the oplogs. Since Oplogs rely on DiskId to furnish user bits so as to correctly 
     * interpret bytes, the userbit needs to be set correctly here.
     */
    public RecoveredEntry(long keyId, long oplogId, long offsetInOplog,
                          byte userBits, int valueLength) {
      this(-keyId, oplogId, offsetInOplog, userBits, valueLength, null);
    }

    public RecoveredEntry(long keyId, long oplogId, long offsetInOplog,
                          byte userBits, int valueLength, Object value) {
      this.recoveredKeyId = keyId;
      this.value = value;
      this.oplogId = oplogId;
      this.offsetInOplog = offsetInOplog;
      this.userBits = EntryBits.setRecoveredFromDisk(userBits, true);
      this.valueLength = valueLength;
    }

    /**
     * Returns the disk id of the entry being recovered
     */
    public long getRecoveredKeyId() {
      return this.recoveredKeyId;
    }
    /**
     * Returns the value of the recovered entry. Note that if the
     * disk id is < 0 then the value has not been faulted in and
     * this method will return null.
     */
    public Object getValue() {
      return this.value;
    }
    /**
     * 
     * @return byte indicating the user bits. The correct value is returned only in the specific case of
     * entry  recovered from oplog ( & not rolled to Htree) & the RECOVER_VALUES flag is false . In other cases
     * the exact value is not needed
     */
    public byte getUserBits() {
      return this.userBits;
    }
    public int getValueLength() {
      return this.valueLength;
    }
    public long getOffsetInOplog() {
      return offsetInOplog;
    }
    public long getOplogId() {
      return this.oplogId;
    }

    public void setOplogId(long v) {
      this.oplogId = v;
    }
    public VersionTag getVersionTag() {
      return this.tag;
    }
    public void setVersionTag(VersionTag tag) {
      this.tag = tag;
    }

    public final long getLastModifiedTime() {
      return this.lastModifiedTime;
    }

    public final void setLastModifiedTime(long timestamp) {
      this.lastModifiedTime = timestamp;
    }
  }
}
