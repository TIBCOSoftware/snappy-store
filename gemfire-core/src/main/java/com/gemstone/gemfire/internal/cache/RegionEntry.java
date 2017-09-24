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

import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.EvictionCriteria;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.offheap.MemoryChunkWithRefCount;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Internal interface for a region entry.
 * Note that a region is implemented with a ConcurrentHashMap which
 * has its own entry class. The "value" field of each of these entries
 * will implement this interface.
 *
 * @since 3.5.1
 *
 * @author Darrel Schneider

Implementations:
  (unshared)
  ThinEntry
    protected volatile long lastModified;
  StatsEntry extends ThinEntry
    private volatile long lastAccessed;
    private volatile int hitCount;
    private volatile int missCount;
  DiskThinEntry extends ThinEntry
    private volatile long id;
  DiskStatsEntry extends StatsEntry
    private volatile long id;
  ThinLRUEntry extends ThinEntry
    int bitField1;
    LRUClockNode nextLRU;
    int size;
  StatsLRUEntry extends StatsEntry
    int bitField1;
    LRUClockNode nextLRU;
    int size;
  DiskThinLRUEntry extends ThinLRUEntry
    private volatile long id;
  DiskStatsLRUEntry extends StatsLRUEntry
    private volatile long id;
 *
 */
public interface RegionEntry extends ExclusiveSharedLockObject {
  // Our RegionEntry does not need to keep a reference to the key.
  // It is being referenced by the Map.Entry whose value field
  // references this RegionEntry.
  // Object getKey();
    
  /**
   * returns the time of last modification.  Prior to v7.0 this only reflected
   * creation time of an entry or the time it was last modified with an Update.
   * In 7.0 and later releases it also reflects invalidate and destroy.
   */
  public long getLastModified();

  /**
   * Set the last modified time of the entry. This will also update the last
   * access time of the entry if required.
   */
  public void setLastModified(long lastModifiedTime);

  /**
   * Only set the last modified time of the entry.
   */
  public void _setLastModified(long lastModifiedTime);

  /**
   * Returns true if this region entry has been created that is still in
   * progress and has marked this entry as locked.
   */
  public boolean isLockedForCreate();

  /**
   * Returns true if getLastAccessed, getHitCount, and getMissCount can be called
   * without throwing InternalStatisticsDisabledException.
   * Otherwise returns false.
   * @since 6.0
   */
  public boolean hasStats();
    
  public long getLastAccessed() throws InternalStatisticsDisabledException;
    
  public long getHitCount() throws InternalStatisticsDisabledException;
    
  public long getMissCount() throws InternalStatisticsDisabledException;

  public void updateStatsForPut(long lastModifiedTime);

  /**
   * @return the version information for this entry
   */
  public VersionStamp getVersionStamp();

  /**
   * @param member
   *          the member performing the change, or null if it's this member
   * @param isRemoteVersionSource
   *          if true, then the "member" argument represents a remote node
   * @param withDelta
   *          whether delta version information should be included
   * @param region
   *          the owner of the region entry
   * @param event
   *          the event causing this change
   * @return a new version tag for this entry (null if versions not supported)
   */
  public VersionTag generateVersionTag(VersionSource member,
      boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
      EntryEventImpl event);

  /**
   * Dispatch listener events for the given entry event.  If the event
   * is from another member and there are already pending events, this
   * will merely queue the event for later dispatch by one of those other
   * events.
   * 
   * This is done within the RegionEntry synchronization lock.
   */
  public boolean dispatchListenerEvents(EntryEventImpl event)
    throws InterruptedException;
  
  /**
   * Used to mark an LRU entry as having been recently used.
   */
  public void setRecentlyUsed();
    
  public void updateStatsForGet(boolean hit, long time);

  /**
   * Resets any entry state as needed for a transaction that did
   * a destroy to this entry.
   * @param currTime Current Cache Time.
   */
  public void txDidDestroy(long currTime);

  public void resetCounts() throws InternalStatisticsDisabledException;
    
  /**
   * this is done instead of removePhase2 if the entry needs to be kept for
   * concurrency control
   * @param r the region holding the entry
   * @param version whether the operation is from another member or local
   * @throws RegionClearedException 
   */
  public void makeTombstone(LocalRegion r, VersionTag version) throws RegionClearedException;
    
  /**
   * Mark this entry as being in the process of removal from the map that
   * contains it by setting its value to Token.REMOVED_PHASE1.  An entry
   * in this state may be resurrected by changing the value under
   * synchronization prior to the entry executing removePhase2.
   * 
   * @param r LocalRegion
   * @param clear - if we removing this entry as a result of a region clear.
   * @throws RegionClearedException If operation got aborted due to a clear
   *  
   */
  public void removePhase1(LocalRegion r, boolean clear) throws RegionClearedException;
  /**
   * Mark this entry as having been removed from the map that contained it
   * by setting its value to Token.REMOVED_PHASE2
   */
  public void removePhase2(LocalRegion r);

  /**
   * Returns true if this entry does not exist.  This is true if removal
   * has started (value == Token.REMOVED_PHASE1) or has completed
   * (value == Token.REMOVED_PHASE2).
   */
  public boolean isRemoved();

  /**
   * Returns true if this entry does not exist and will not be resurrected
   * (value == Token.REMOVED_PHASE2)
   */
  public boolean isRemovedPhase2();
  
  /**
   * Returns true if this entry is a tombstone, meaning that it has been
   * removed but is being retained for concurrent modification detection.
   * Tombstones may be resurrected just like an entry that is in REMOVED_PHASE1
   * state.
   */
  public boolean isTombstone();

    /**
   * Fill in value, and isSerialized fields
   * in this entry object (used for getInitialImage and sync recovered)
   * Also sets the lastModified time in cacheTime.
   * Only called for DistributedRegions.<p>
   * 
   * This method returns tombstones so that they can be transferred with
   * the initial image.
     * @param targetVersion TODO
   *
   * @see DistributedRegion#chunkEntries
   *
   * @return false if map entry not found
   * @since 3.2.1
   */
  public boolean fillInValue(LocalRegion r,
      @Retained(ABSTRACT_REGION_ENTRY_FILL_IN_VALUE) InitialImageOperation.Entry entry,
      DM mgr, Version targetVersion);

  /**
   * Returns true if this entry has overflowed to disk.
   * @param dp if overflowed then the position of the value is set in dp
   */
  public boolean isOverflowedToDisk(LocalRegion r, DistributedRegion.DiskPosition dp);

  /**
   * Gets the key for this entry.
   * 
   * Note that GemFireXD RegionEntries return "this" itself as the key with
   * appropriate overrides for equals() and hashCode() methods, so the caller
   * should ensure that RegionEntry as the key will also work fine (or else use
   * {@link #getKeyCopy()} but that will have perf impact so use that only if
   * really required).
   */
  public Object getKey();

  /**
   * Get a copy of the key object for this entry to be provided to external
   * callers for implementations that do not maintain a separate key object
   * (e.g. in GemFireXD).
   */
  public Object getKeyCopy();

  /**
   * Get the underlying raw key object. For GemFireXD this will return underlying
   * key bytes if snapshoted from value else null if the value is in memory.
   */
  public Object getRawKey();

  /**
   * Gets the value for this entry. For DiskRegions, faults in value and returns
   * it
   */
  @Retained
  public Object getValue(RegionEntryContext context);

  @Released
  public void setValue(RegionEntryContext context, @Unretained Object value) throws RegionClearedException;

  /**
   * Set the owner for this RegionEntry. Used only by GemFireXD.
   */
  public void setOwner(LocalRegion owner, Object previousOwner);

  /**
   * Obtain and return the value of this entry using {@link #_getValue()}.
   * If the value is a MemoryChunkWithRefCount then increment its refcount.
   * WARNING: if a MemoryChunkWithRefCount is returned then the caller MUST
   * call {@link MemoryChunkWithRefCount#release()}.
   * 
   * This is only retained in off-heap subclasses.  However, it's marked as
   * Retained here so that callers are aware that the value may be retained.
   * 
   * @param decompress if true returned value will be decompressed if it is compressed
   * @return possible OFF_HEAP_OBJECT (caller must release)
   */
  @Retained 
  public Object _getValueRetain(RegionEntryContext context, boolean decompress);
  /** Gets the value field of this entry. */
  
  @Unretained
  public Object _getValue();
  /**
   * Returns a tokenized form of the value.
   * If the value can not be represented as a token
   * then Token.NOT_A_TOKEN is returned.
   */
  public Token getValueAsToken();

  /**
   * Set the value of this entry and perform checks to see if a GC task
   * needs to be scheduled.
   * @param value the new value for this entry
   * @param event the cache event that caused this change
   * @throws RegionClearedException thrown if the region is concurrently cleared
   */
  public void setValueWithTombstoneCheck(@Unretained Object value, EntryEvent event) throws RegionClearedException;
  
  /**
   * Returns the value as stored by the RegionEntry implementation.  For instance, if compressed this
   * value would be the compressed form.
   *  
   * @since 7.5
   */
  @Retained
  public Object getTransformedValue();
  
  /**
   * Returns the value of an entry as it resides in the VM.
   * @return the value or EntryEvent.NOT_AVAILABLE token if it's not in
   * the VM or null if the entry doesn't exist.
   *
   * @see LocalRegion#getValueInVM
   */
  @Retained
  public Object getValueInVM(RegionEntryContext context);
  /**
   * Returns the value of an entry as it resides on disk.  For
   * testing purposes only.
   *
   * @see LocalRegion#getValueOnDisk
   */
  public Object getValueOnDisk(LocalRegion r)
    throws EntryNotFoundException;
  
  /**
   * Returns the value of an entry as it resides on buffer or disk.  For asynch mode
   * a value is not immediately written to the disk & so checking in asynch buffers
   * is needed .For testing purposes only.
   *
   * @see LocalRegion#getValueOnDisk
   */
  
  public Object getValueOnDiskOrBuffer(LocalRegion r)
  throws EntryNotFoundException;

  

  /**
   * Used to modify an existing RegionEntry when processing the
   * values obtained during a getInitialImage.
   */
  public boolean initialImagePut(LocalRegion region,
                                 long lastModified,
                                 Object newValue,
                                 boolean wasRecovered,
                                 boolean acceptedVersionTag) throws RegionClearedException;
  /**
   * Used to initialize a created RegionEntry when processing the
   * values obtained during a getInitialImage.
      // put if LOCAL_INVALID and nonexistant
      // put if INVALID and nonexistant, recovered, or LOCAL_INVALID
      // put if valid and nonexistant, recovered, or LOCAL_INVALID
      //
      // REGION_INVALIDATED:  (special case)
      // If the region itself has been invalidated since getInitialImage
      // started, and newValue is LOCAL_INVALID or valid,
      // then force creation of INVALID key if currently nonexistant
      // or invalidate if current recovered.
      //
      // must write-synchronize to protect agains puts from other
      // threads running this method
   */
  public boolean initialImageInit(LocalRegion region,
                                  long lastModified,
                                  Object newValue,
                                  boolean create,
                                  boolean wasRecovered,
                                  boolean acceptedVersionTag) throws RegionClearedException;
  /**
   * @param expectedOldValue only succeed with destroy if current value
   *        is equal to expectedOldValue
   * @param createdForDestroy true if caller just added this entry because we
   *   are inTokenMode and we need to "create" a destroyed entry to
   *   mark the destroy
   * @return true if destroy was done; false if not
   */
  @Released
  public boolean destroy(LocalRegion region,
                         EntryEventImpl event,
                         boolean inTokenMode,
                         boolean cacheWrite,
                         @Unretained Object expectedOldValue,
                         boolean createdForDestroy,
                         boolean removeRecoveredEntry)
    throws CacheWriterException, EntryNotFoundException, TimeoutException, RegionClearedException;

  /* No longer used in new TX impl
  /**
   * @return true if entry's value came from a netsearch
   * @since 6.5
   *
  public boolean getValueWasResultOfSearch();
  /**
   * @param v true if entry's value should be marked as having been
   * the result of a netsearch.
   * @since 6.5
   *
  public void setValueResultOfSearch(boolean v);
  */

  /**
   * Get the serialized bytes from disk.  This method only looks for the value
   * on the disk, ignoring heap data.
   * @param localRegion the persistent region 
   * @return the serialized value from disk
   * @since gemfire5.7_hotfix
   */
  public Object getSerializedValueOnDisk(LocalRegion localRegion);

  /**
   * Get the value in region or disk without faultin in raw form without any
   * change in storage format (SnappyData off-heap will return off-heap buffer).
   */
  @Retained
  public Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner);

  /**
   * Gets the value for this entry. For DiskRegions, unlike
   * {@link #getValue(RegionEntryContext)} this will not fault in the value rather
   * return a temporary copy. For GemFire XD this is used during table scans in
   * queries when faulting in every value will be only an unnecessary overhead.
   * The value returned will be kept off heap (and compressed) if possible.
   */
  @Retained
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner);

  /**
   * Used for GemFireXD to return ExtraTableInfo if available.
   */
  public Object getContainerInfo();

  /**
   * Used for GemFireXD to to initialize the table schema related information from
   * the owner region if key is part of the value row itself, or when value
   * changes (e.g. schema may change once value changes).
   */
  public Object setContainerInfo(LocalRegion owner, Object val);

  /**
   * RegionEntry is underUpdate as soon as RegionEntry lock is help by an
   * update thread to put a new value for an existing key. This is used during
   * query on the region to verify index entry with current region entry.
   *
   * @return true if RegionEntry is under update during cache put operation.
   */
  public boolean isUpdateInProgress();

  /**
   * Sets RegionEntry updateInProgress flag when put is happening for an
   * existing Region.Entry. Called ONLY in {@link AbstractRegionMap}.
   *
   * @param underUpdate
   */
  public void setUpdateInProgress(final boolean underUpdate);

  /**
   * Returns true if this entry has been marked for eviction for custom eviction
   * via {@link EvictionCriteria}.
   */
  public boolean isMarkedForEviction();

  /**
   * Marks this entry for eviction by custom eviction via
   * {@link EvictionCriteria}.
   */
  public void setMarkedForEviction();

  /**
   * Clears this entry as for eviction by custom eviction via
   * {@link EvictionCriteria} or when an update is done after it was marked for
   * eviction.
   */
  public void clearMarkedForEviction();

  /**
   * Event containing this RegionEntry is being passed through
   * dispatchListenerEvent for CacheListeners under RegionEntry lock. This is
   * used during deserialization for a VMCacheSerializable value contained by
   * this RegionEntry.
   * 
   * @return true if Event is being dispatched to CacheListeners.
   */
  public boolean isCacheListenerInvocationInProgress();

  /**
   * Sets RegionEntry isCacheListenerInvoked flag when put is happening for a
   * Region.Entry. Called ONLY in {@link LocalRegion#dispatchListenerEvent}.
   * 
   * @param isListenerInvoked
   */
  public void setCacheListenerInvocationInProgress(final boolean isListenerInvoked);
  
  /**
   * Returns true if the entry value is null.
   */
  public boolean isValueNull();
  
  /**
   * Returns true if the entry value is equal to {@link Token#INVALID} or {@link Token#LOCAL_INVALID}.
   */
  public boolean isInvalid();
  
  /**
   * Returns true if the entry value is equal to {@link Token#DESTROYED}.
   */
  public boolean isDestroyed();
  
  /**
   * Returns true if the entry value is equal to {@link Token#DESTROYED} or {@link Token#REMOVED_PHASE1} or {@link Token#REMOVED_PHASE2} or {@link Token#TOMBSTONE}.
   */
  public boolean isDestroyedOrRemoved();
  
  /**
   * Returns true if the entry value is equal to {@link Token#DESTROYED} or {@link Token#REMOVED_PHASE1} or {@link Token#REMOVED_PHASE2}.
   */
  public boolean isDestroyedOrRemovedButNotTombstone();
  
  /**
   * @see Token#isInvalidOrRemoved(Object)
   */
  public boolean isInvalidOrRemoved();

  /**
   * Returns true if the RegionEntry is for storing an off-heap value
   */
  public boolean isOffHeap();

  /**
   * Sets the entry value to null.
   */
  public void setValueToNull(RegionEntryContext context);

  public void returnToPool();
  
  @Retained(ABSTRACT_REGION_ENTRY_PREPARE_VALUE_FOR_CACHE)
  public Object prepareValueForCache(RegionEntryContext r, Object val, boolean isEntryUpdate,
      boolean valHasMetadataForGfxdOffHeapUpdate);
}
