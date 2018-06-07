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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.cache.query.internal.index.IndexManager;
import com.gemstone.gemfire.cache.query.internal.index.IndexProtocol;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.ha.HAContainerWrapper;
import com.gemstone.gemfire.internal.cache.ha.HARegionQueue;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantReadWriteLock;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.HAEventWrapper;
import com.gemstone.gemfire.internal.cache.versions.*;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntryCreator;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.pdx.PdxSerializationException;
import com.gemstone.gemfire.pdx.internal.ConvertableToBytes;
import com.gemstone.org.jgroups.util.StringId;

/**
 * Abstract implementation of {@link RegionMap}that has all the common
 * behavior.
 *
 * @since 3.5.1
 *
 * @author Darrel Schneider
 *
 */

//Asif: In case of GemFireXD System, we are creating a different set of RegionEntry 
// which are derived from the concrete  GFE RegionEntry classes.
// In future if any new concrete  RegionEntry class is defined, the new  GemFireXD
// RegionEntry Classes need to be created. There is a junit test in gemfirexd
// which checks for RegionEntry classes of GFE and validates the same with its 
// own classes.

abstract class AbstractRegionMap implements RegionMap {

  /** The underlying map for this region. */
  protected CustomEntryConcurrentHashMap<Object, Object/*RegionEntry*/> map;
  /** An internal Listener for index maintenance for GemFireXD. */
  protected IndexUpdater indexUpdater;
  /** a boolean used only in GemFireXD to handle creates
   * which might have failed elsewhere. See #47405
   */
  private boolean isReplicatedRegion;

  /**
   * This test hook is used to force the conditions for defect 48182.
   * This hook is used by Bug48182JUnitTest.
   */
  static Runnable testHookRunnableFor48182 =  null;

  private RegionEntryFactory entryFactory;
  private Attributes attr;
  private transient Object owner; // the region that owns this map
    
  protected AbstractRegionMap(InternalRegionArguments internalRegionArgs) {
    if (internalRegionArgs != null) {
      this.indexUpdater = internalRegionArgs.getIndexUpdater();
    }
    else {
      this.indexUpdater = null;
    }
  }

  @Override
  public final IndexUpdater getIndexUpdater() {
    return this.indexUpdater;
  }

  @Override
  public final void setIndexUpdater(IndexUpdater indexManager) {
    this.indexUpdater = indexManager;
  }

  @SuppressWarnings("unchecked")
  protected void initialize(Object owner,
                            Attributes attr,
                            InternalRegionArguments internalRegionArgs,
                            boolean isLRU) {
    _setAttributes(attr);
    setOwner(owner);

    String ownerPath = null;
    boolean isDisk;
    boolean withVersioning = false;
    this.isReplicatedRegion = false;
    boolean offHeap = false;
    if (owner instanceof LocalRegion) {
      LocalRegion region = (LocalRegion)owner;
      ownerPath = region.getFullPath();
      if (LocalRegion.isMetaTable(ownerPath)) {
        ownerPath = null;
      }
      isDisk = region.getDiskRegion() != null;
      withVersioning = region.getConcurrencyChecksEnabled();
      if (region.dataPolicy.withReplication()
          && !region.isUsedForPartitionedRegionBucket()
          && !(internalRegionArgs != null && internalRegionArgs
              .isUsedForPartitionedRegionBucket())) {
        this.isReplicatedRegion = true;
      }
      offHeap = region.getEnableOffHeapMemory();
    }
    else if (owner instanceof PlaceHolderDiskRegion) {
      PlaceHolderDiskRegion region = (PlaceHolderDiskRegion)owner;
      if (!region.isMetaTable()) {
        ownerPath = region.getFullPath();
      }
      offHeap = region.getEnableOffHeapMemory();
      isDisk = true;
      withVersioning = region.getFlags().contains(
          DiskRegionFlag.IS_WITH_VERSIONING);
    }
    else {
      throw new IllegalStateException(
          "expected LocalRegion or PlaceHolderDiskRegion");
    }

    if (GemFireCacheImpl.gfxdSystem()) {
      String provider = SystemProperties.GFXD_FACTORY_PROVIDER;
      try {
        Class<?> factoryProvider = Class.forName(provider);
        Method method = factoryProvider.getDeclaredMethod(
            "getHashEntryCreator");
        _setMap(createConcurrentMap(attr.initialCapacity, attr.loadFactor,
            attr.concurrencyLevel, false,
            (HashEntryCreator<Object, Object>)method.invoke(null)), ownerPath);

        method = factoryProvider.getDeclaredMethod("getRegionEntryFactory",
            new Class[] { Boolean.TYPE, Boolean.TYPE, Boolean.TYPE,
                Boolean.TYPE, Object.class, InternalRegionArguments.class });
        RegionEntryFactory ref = (RegionEntryFactory)method.invoke(
            null,
            new Object[] { Boolean.valueOf(attr.statisticsEnabled),
                Boolean.valueOf(isLRU), Boolean.valueOf(isDisk),
                Boolean.valueOf(withVersioning), owner, internalRegionArgs });

        setEntryFactory(ref);

        if(withVersioning) {
          Assert.assertTrue(VersionStamp.class.isAssignableFrom(ref.getEntryClass()));  
        }
      } catch (Exception e) {
        throw new IllegalStateException("Exception in obtaining RegionEntry "
            + "Factory provider class ", e);
      }
    }
    else {
      _setMap(createConcurrentMap(attr.initialCapacity, attr.loadFactor,
          attr.concurrencyLevel, false,
          new AbstractRegionEntry.HashRegionEntryCreator()), ownerPath);
      final RegionEntryFactory factory;
      if (attr.statisticsEnabled) {
        if (isLRU) {
          if (isDisk) {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedStatsDiskLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedStatsDiskLRURegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMStatsDiskLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMStatsDiskLRURegionEntryHeap.getEntryFactory();
              }
            }
          } else {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedStatsLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedStatsLRURegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMStatsLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMStatsLRURegionEntryHeap.getEntryFactory();
              }
            }
          }
        } else { // !isLRU
          if (isDisk) {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedStatsDiskRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedStatsDiskRegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMStatsDiskRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMStatsDiskRegionEntryHeap.getEntryFactory();
              }
            }
          } else {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedStatsRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedStatsRegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMStatsRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMStatsRegionEntryHeap.getEntryFactory();
              }
            }
          }
        }
      }
      else { // !statistics enabled
        if (isLRU) {
          if (isDisk) {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedThinDiskLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedThinDiskLRURegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMThinDiskLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMThinDiskLRURegionEntryHeap.getEntryFactory();
              }
            }
          }
          else {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedThinLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedThinLRURegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMThinLRURegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMThinLRURegionEntryHeap.getEntryFactory();
              }
            }
          }
        }
        else { // !isLRU
          if (isDisk) {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedThinDiskRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedThinDiskRegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMThinDiskRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMThinDiskRegionEntryHeap.getEntryFactory();
              }
            }
          }
          else {
            if (withVersioning) {
              if (offHeap) {
                factory = VersionedThinRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VersionedThinRegionEntryHeap.getEntryFactory();
              }
            } else {
              if (offHeap) {
                factory = VMThinRegionEntryOffHeap.getEntryFactory();
              } else {
                factory = VMThinRegionEntryHeap.getEntryFactory();
              }
            }
          }
        }
      }
      setEntryFactory(factory);
    }
  }

  protected CustomEntryConcurrentHashMap<Object, Object> createConcurrentMap(
      int initialCapacity, float loadFactor, int concurrencyLevel,
      boolean isIdentityMap, HashEntryCreator<Object, Object> entryCreator) {
    if (entryCreator != null) {
      return new CustomEntryConcurrentHashMap<Object, Object>(initialCapacity,
          loadFactor, concurrencyLevel, isIdentityMap, entryCreator);
    }
    else {
      return new CustomEntryConcurrentHashMap<Object, Object>(initialCapacity,
          loadFactor, concurrencyLevel, isIdentityMap);
    }
  }

  @Override
  public void changeOwner(LocalRegion r, InternalRegionArguments args) {
    this.isReplicatedRegion = r.dataPolicy.withReplication()
        && !r.isUsedForPartitionedRegionBucket()
        && !(args != null && args.isUsedForPartitionedRegionBucket());

    Object currentOwner = _getOwnerObject();
    if (r == currentOwner) {
      return;
    }
    setOwner(r);

    //// set the GemFireXD IndexUpdater
    if (args != null) {
      setIndexUpdater(args.getIndexUpdater());
      // index manager or owner changed so we need to update the indexes
      // [sumedh] indexes are now updated by IndexRecoveryTask
    }
    // iterate over the entries of the map to call setOwner for each RegionEntry
    for (RegionEntry re : r.getRegionMap().regionEntries()) {
      re.setOwner(r, currentOwner);
    }
  }

  @Override
  public final void setEntryFactory(RegionEntryFactory f) {
    this.entryFactory = f;
  }

  public final RegionEntryFactory getEntryFactory() {
    return this.entryFactory;
  }

  protected final void _setAttributes(Attributes a) {
    this.attr = a;
  }

  public final Attributes getAttributes() {
    return this.attr;
  }
  
  protected final LocalRegion _getOwner() {
    return (LocalRegion)this.owner;
  }

  protected final boolean _isOwnerALocalRegion() {
    return this.owner instanceof LocalRegion;
  }

  protected final Object _getOwnerObject() {
    return this.owner;
  }

  public final void setOwner(Object r) {
    this.owner = r;
  }
  
  protected final CustomEntryConcurrentHashMap<Object, Object> _getMap() {
    return this.map;
  }

  protected final void _setMap(CustomEntryConcurrentHashMap<Object, Object> m,
      String ownerPath) {
    this.map = m;
    m.setOwner(ownerPath);
  }

  public int size() {
    return _getMap().size();
  }

  // this is currently used by stats and eviction
  @Override
  public int sizeInVM() {
    return _getMap().size();
  }

  public boolean isEmpty() {
    return _getMap().isEmpty();
  }

  public final Set keySet() {
    return _getMap().keySet();
  }

  public final Set keyValueSet() {
    return _getMap().entrySet();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Collection<RegionEntry> regionEntries() {
    return (Collection)_getMap().values();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  public Collection<RegionEntry> regionEntriesInVM() {
    return (Collection)_getMap().values();
  }

  public final boolean containsKey(Object key) {
    RegionEntry re = getEntry(key);
    if (re == null) {
      return false;
    }
    if (re.isRemoved()) {
      return false;
    }
    return true;
  }

  public RegionEntry getEntry(Object key) {
    RegionEntry re = (RegionEntry)_getMap().get(key);
    if (re != null && re.isMarkedForEviction()) {
      // entry has been faulted in from HDFS
      return null;
    }
    return re;
  }

  protected RegionEntry getEntry(EntryEventImpl event) {
    return getEntry(event.getKey());
  }

  protected boolean getEntryNeedKeyCopy() {
    return false;
  }

  @Override
  public RegionEntry getEntryInVM(Object key) {
    return (RegionEntry) _getMap().get(key);
  }

  @Override
  public final RegionEntry getOperationalEntryInVM(Object key) {
    RegionEntry re = (RegionEntry)_getMap().get(key);
    if (re != null && re.isMarkedForEviction()) {
      // entry has been faulted in from HDFS
      return null;
    }
    return re;
  }

  public final RegionEntry putEntryIfAbsent(Object key, RegionEntry re) {
    RegionEntry value = (RegionEntry)_getMap().putIfAbsent(key, re);
    if (value == null && re.isOffHeap()
        && _isOwnerALocalRegion() && _getOwner().isThisRegionBeingClosedOrDestroyed()) {
      // prevent orphan during concurrent destroy (#48068)
      if (_getMap().remove(key, re)) {
        ((OffHeapRegionEntry)re).release();
      }
      _getOwner().checkReadiness(); // throw RegionDestroyedException
    }
    return value;
  }

  public final void removeEntry(Object key, RegionEntry re, boolean updateStat) {
    final LocalRegion owner = _getOwner();
    if (re.isTombstone() && _getMap().get(key) == re && !re.isMarkedForEviction()) {
      owner.getLogWriterI18n().severe(LocalizedStrings.AbstractRegionMap_ATTEMPT_TO_REMOVE_TOMBSTONE, key, new Exception("stack trace"));
      return; // can't remove tombstones except from the tombstone sweeper
    }
//    _getOwner().getLogWriterI18n().info(LocalizedStrings.DEBUG, "DEBUG: removing entry " + re, new Exception("stack trace"));
    if (_getMap().remove(key, re)) {
      re.removePhase2(owner);
      if (updateStat) {
        incEntryCount(-1);
      }
    }
  }

  public final void removeEntry(Object key, RegionEntry re, boolean updateStat,
      EntryEventImpl event, final LocalRegion owner,
      final IndexUpdater indexUpdater) {
    boolean success = false;
    if (re.isTombstone() && _getMap().get(key) == re && !re.isMarkedForEviction()) {
      _getOwner().getLogWriterI18n().severe(LocalizedStrings.AbstractRegionMap_ATTEMPT_TO_REMOVE_TOMBSTONE, key, new Exception("stack trace"));
      return; // can't remove tombstones except from the tombstone sweeper
    }
//    _getOwner().getLogWriterI18n().info(LocalizedStrings.DEBUG, "DEBUG: removing entry " + re, new Exception("stack trace"));
    boolean onEventInvoked = false;
    try {
      if (indexUpdater != null) {
        boolean isValueNull;
        boolean isOldValueAToken;
        if (event.hasOldValue()) {
          isValueNull = false;
          isOldValueAToken = event.isOldValueAToken();
        }
        else {
          @Released Object value = re.getValueOffHeapOrDiskWithoutFaultIn(owner);
          try {
            isValueNull = value == null;
            isOldValueAToken = value instanceof Token;
            if (!isValueNull && !isOldValueAToken) {
              event.setOldValue(value, true);
            }
          } finally {
            OffHeapHelper.release(value);
          }
        }
        if (!isValueNull && !isOldValueAToken) {
          onEventInvoked = true;
          indexUpdater.onEvent(owner, event, re);
        }
      }

      //This is messy, but custom eviction calls removeEntry
      //rather than re.destroy I think to avoid firing callbacks, etc.
      //However, the value still needs to be set to removePhase1
      //in order to remove the entry from disk.
      if(event.isCustomEviction() && !re.isRemoved()) {
        try {
          re.removePhase1(owner, false);
        } catch (RegionClearedException e) {
          //that's ok, we were just trying to do evict incoming eviction
        }
      }
      
      if (_getMap().remove(key, re)) {
        re.removePhase2(owner);
        success = true;
        if (updateStat) {
          incEntryCount(-1);
        }
      }
    } finally {
      if (onEventInvoked) {
        indexUpdater.postEvent(owner, event, re, success);
      }
    }
  }

  protected final void incEntryCount(int delta) {
    LocalRegion lr = _getOwner();
    if (lr != null) {
      CachePerfStats stats = lr.getCachePerfStats();
      if (stats != null) {
        stats.incEntryCount(delta);
      }
    }
  }
  
  final void incClearCount(LocalRegion lr) {
    if (lr != null && !(lr instanceof HARegion)) {
      CachePerfStats stats = lr.getCachePerfStats();
      if (stats != null) {
        stats.incClearCount();
      }
    }
  }

  private void _mapClear() {
    _getMap().clear();
  }

  public void close() {
    this.suspectEntriesLock.attemptWriteLock(-1);
    try {
      for (SuspectEntryList l : this.suspectEntries.values()) {
        for (EntryEventImpl e : l) {
          e.release();
        }
      }
    } finally {
      this.suspectEntriesLock.releaseWriteLock();
    }
    clear(null);
  }
  
  /**
   * Clear the region and, if an RVV is given, return a collection of the
   * version sources in all remaining tags
   */
  public Set<VersionSource> clear(RegionVersionVector rvv)
  {
    Set<VersionSource> result = new HashSet<VersionSource>();
    
    if(!_isOwnerALocalRegion()) {
      //Fix for #41333. Just clear the the map
      //if we failed during initialization.
      _mapClear();
      return null;
    }
    LogWriterI18n logger = _getOwner().getLogWriterI18n();
    if (logger.fineEnabled()) {
      logger.fine("Clearing entries for " + _getOwner() + " rvv=" + rvv);
    }
    LocalRegion lr = _getOwner();
    RegionVersionVector localRvv = lr.getVersionVector();
    incClearCount(lr);
    final ReentrantLock lockObj = lr.getConcurrencyChecksEnabled()? lr.getSizeGuard() : null;
    if (lockObj != null) {
      lockObj.lock();
    }
    try {
      if (rvv == null) {
        int delta = 0;
        try {
          delta = sizeInVM(); // TODO soplog need to determine if stats should
                              // reflect only size in memory or the complete thing
        } catch (GemFireIOException e) {
          // ignore rather than throwing an exception during cache close
        }
        int tombstones = lr.getTombstoneCount();
        _mapClear();
        _getOwner().updateSizeOnClearRegion(delta - tombstones);
        _getOwner().incTombstoneCount(-tombstones);
        if (delta != 0) {
          incEntryCount(-delta);
        }
      } else {
        int delta = 0;
        int tombstones = 0;
        VersionSource myId = _getOwner().getVersionMember();
        if (localRvv != rvv) {
          localRvv.recordGCVersions(rvv, null);
        }
        for (RegionEntry re : regionEntries()) {
          synchronized (re) {
            Token value = re.getValueAsToken();
            // if it's already being removed or the entry is being created we leave it alone
            if (value == Token.REMOVED_PHASE1 || value == Token.REMOVED_PHASE2) {
              continue;
            }
            
            VersionSource id = re.getVersionStamp().getMemberID();
            if (id == null) {
              id = myId;
            }
            if (rvv.contains(id, re.getVersionStamp().getRegionVersion())) {
              if (logger.finerEnabled()) {
                logger.finer("region clear op is removing " + re.getKeyCopy()
                    + " " + re.getVersionStamp());
              }
              boolean tombstone = re.isTombstone();
              // note: it.remove() did not reliably remove the entry so we use remove(K,V) here
              if (_getMap().remove(re.getKey(), re)) {
                if (OffHeapRegionEntryHelper.doesClearNeedToCheckForOffHeap()) {
                  GatewaySenderEventImpl.release(re._getValue());
                }
                //If this is an overflow only region, we need to free the entry on
                //disk at this point.
                try {
                  re.removePhase1(lr, true);
                } catch (RegionClearedException e) {
                  //do nothing, it's already cleared.
                }
                re.removePhase2(lr);
                lruEntryDestroy(re);
                if (tombstone) {
                  _getOwner().incTombstoneCount(-1);
                  tombstones += 1;
                } else {
                  delta += 1;
                }
              }
            } else { // rvv does not contain this entry so it is retained
              result.add(id);
            }
          }
        }
        _getOwner().updateSizeOnClearRegion(delta);
        incEntryCount(-delta);
        incEntryCount(-tombstones);
        if (logger.fineEnabled()) {
          logger.fine("Size after clearing = " + _getMap().size());
          if (logger.finerEnabled() && _getMap().size() < 20) {
            _getOwner().dumpBackingMap();
          }
        }
      }
    } finally {
      if (lockObj != null) {
        lockObj.unlock();
      }
    }
    return result;
  }

  public void lruUpdateCallback()
  {
    // By default do nothing; LRU maps needs to override this method
  }
  public void lruUpdateCallback(boolean b)
  {
    // By default do nothing; LRU maps needs to override this method
  }
  public void lruUpdateCallback(int i)
  {
    // By default do nothing; LRU maps needs to override this method
  }

  public boolean disableLruUpdateCallback()
  {
    // By default do nothing; LRU maps needs to override this method
    return false;
  }

  public void enableLruUpdateCallback()
  {
    // By default do nothing; LRU maps needs to override this method
  }

  public void resetThreadLocals()
  {
    // By default do nothing; LRU maps needs to override this method
  }

  /**
   * Tell an LRU that a new entry has been created
   */
  protected void lruEntryCreate(RegionEntry e)
  {
    // do nothing by default
  }

  /**
   * Tell an LRU that an existing entry has been destroyed
   */
  protected void lruEntryDestroy(RegionEntry e)
  {
    // do nothing by default
  }

  /**
   * Tell an LRU that an existing entry has been modified
   */
  protected void lruEntryUpdate(RegionEntry e)
  {
    // do nothing by default
  }

  public boolean lruLimitExceeded() {
    return false;
  }

  public void lruCloseStats() {
    // do nothing by default
  }

  public void lruEntryFaultIn(LRUEntry entry) {
    // do nothing by default
  }
  
  /**
   * Process an incoming version tag for concurrent operation detection.
   * This must be done before modifying the region entry.
   * @param re the entry that is to be modified
   * @param event the modification to the entry
   * @throws InvalidDeltaException if the event contains a delta that cannot be applied
   * @throws ConcurrentCacheModificationException if the event is in conflict
   *    with a previously applied change
   */
  private void processVersionTag(RegionEntry re, EntryEventImpl event) {
    VersionStamp<?> stamp = re.getVersionStamp();
    if (stamp != null) {
      stamp.processVersionTag(event);
      
      // during initialization we record version tag info to detect ops the
      // image provider hasn't seen
      VersionTag<?> tag = event.getVersionTag();
      if (tag != null && !event.getRegion().isInitialized()) {
        ImageState is = event.getRegion().getImageState();
        if (is != null && !event.getRegion().isUsedForPartitionedRegionBucket()) {
          if (_getOwner().getLogWriterI18n().finerEnabled()) {
            _getOwner().getLogWriterI18n().finer("recording version tag in image state: " + tag);
          }
          is.addVersionTag(event.getKey(), tag);
        }
      }
    }
  }

  private void processVersionTagForGII(RegionEntry re, LocalRegion owner, VersionTag entryVersion, boolean isTombstone, InternalDistributedMember sender, boolean checkConflicts) {
    
    re.getVersionStamp().processVersionTag(_getOwner(), entryVersion, isTombstone, false, owner.getMyId(), sender, checkConflicts);
  }

  public void copyRecoveredEntries(RegionMap rm, boolean entriesIncompatible) {
    //We need to sort the tombstones before scheduling them,
    //so that they will be in the correct order.
    OrderedTombstoneMap<RegionEntry> tombstones = new OrderedTombstoneMap<RegionEntry>();
    if (rm != null) {
      final LocalRegion owner = _getOwner();
      final AbstractRegionMap arm = (AbstractRegionMap)rm;
      final Object rmOwner = arm._getOwnerObject();
      // Read current time to later pass it to all calls to copyRecoveredEntry.  This is 
      // needed if a dummy version tag has to be created for a region entry
      final long currentTime = owner.getCache().cacheTimeMillis();
      
      CustomEntryConcurrentHashMap<Object, Object> other = arm._getMap();
      Iterator<Map.Entry<Object, Object>> it = other
          .entrySetWithReusableEntries().iterator();
      while (it.hasNext()) {
        Map.Entry<Object, Object> me = it.next();
        it.remove(); // This removes the RegionEntry from "rm" but it does not decrement its refcount to an offheap value.
        RegionEntry oldRe = (RegionEntry)me.getValue();
        Object key = me.getKey();

        if (!entriesIncompatible) {
          oldRe.setOwner(owner, rmOwner);
          _getMap().put(key, oldRe);
          // newRe is now in this._getMap().
          if (oldRe.isTombstone()) {
            VersionTag tag = oldRe.getVersionStamp().asVersionTag();
            tombstones.put(tag, oldRe);
          }
          // only for incrementing count while size is updated in RegionEntry.setOwner
          owner.updateSizeOnCreate(key, 0);
          // owner.calculateRegionEntryValueSize(oldRe));
          incEntryCount(1);
          lruEntryUpdate(oldRe);
          lruUpdateCallback();
          continue;
        }
        
        @Retained @Released Object value = oldRe._getValueRetain((RegionEntryContext)rmOwner, true);
        try {
          if (value == Token.NOT_AVAILABLE) {
            // fix for bug 43993
            value = null;
          }
          if (value == Token.TOMBSTONE && !owner.getConcurrencyChecksEnabled()) {
            continue;
          }
          RegionEntry newRe = getEntryFactory().createEntry(owner, key, value);
          copyRecoveredEntry(oldRe, newRe, owner, currentTime);
          // newRe is now in this._getMap().
          if (newRe.isTombstone()) {
            VersionTag tag = newRe.getVersionStamp().asVersionTag();
            tombstones.put(tag, newRe);
          }
          // only for incrementing count while size is updated in RegionEntry constructor
          owner.updateSizeOnCreate(key, 0);
          incEntryCount(1);
          lruEntryUpdate(newRe);
        } finally {
          if (OffHeapHelper.release(value)) {
            ((OffHeapRegionEntry)oldRe).release();
          }
        }
        lruUpdateCallback();
      }
    } else {
      incEntryCount(size());
      for (Iterator<RegionEntry> iter = regionEntries().iterator(); iter.hasNext(); ) {
        RegionEntry re = iter.next();
        if (re.isTombstone()) {
          if (re.getVersionStamp() == null) { // bug #50992 - recovery from versioned to non-versioned
            incEntryCount(-1);
            iter.remove();
            continue;
          } else {
            tombstones.put(re.getVersionStamp().asVersionTag(), re);
          }
        }

        int valueSize  = _getOwner().calculateRegionEntryValueSize(re);
        _getOwner().calculateEntryOverhead(re);
        // Always take the value size from recovery thread.
        if (!re.isTombstone()) {
          _getOwner().acquirePoolMemory(0, 0, true, null, false);
        }
        _getOwner().updateSizeOnCreate(re.getRawKey(), valueSize);
      }
      // Since lru was not being done during recovery call it now.
      lruUpdateCallback();
    }
    
    //Schedule all of the tombstones, now that we have sorted them
    Map.Entry<VersionTag, RegionEntry> entry;
    while((entry = tombstones.take()) != null) {
      // refresh the tombstone so it doesn't time out too soon
      _getOwner().scheduleTombstone(entry.getValue(), entry.getKey());
    }
    
  }
  
  protected void copyRecoveredEntry(RegionEntry oldRe, RegionEntry newRe,
      LocalRegion owner, long dummyVersionTs) {
    long lastModifiedTime = oldRe.getLastModified();
    if (lastModifiedTime != 0) {
      newRe.setLastModified(lastModifiedTime);
    }
    if (newRe.getVersionStamp() != null) {
      // [sjigyasu] Fixes #50794.
      // If the recovered entry does not have a version stamp and the newRe is
      // versioned, create a dummy version stamp.
      if (oldRe.getVersionStamp() == null) {
        VersionTag vt = createDummyTag(dummyVersionTs);
        newRe.getVersionStamp().setVersions(vt);
      }
      else {
        newRe.getVersionStamp().setVersions(
            oldRe.getVersionStamp().asVersionTag());
      }
    }

    if (newRe instanceof AbstractOplogDiskRegionEntry) {
      AbstractOplogDiskRegionEntry newDe = (AbstractOplogDiskRegionEntry)newRe;
      newDe.setDiskIdForRegion(owner, oldRe);
      _getOwner().getDiskRegion().replaceIncompatibleEntry((DiskEntry) oldRe, newDe);
    }
    _getMap().put(newRe.getKey(), newRe);
  }

  private synchronized VersionTag createDummyTag(long dummyVersionTs) {
    LocalRegion region = (LocalRegion)this.owner;
    VersionSource member = region.getDiskStore().getDiskStoreID();
    long regionVersion = region.getDiskRegion().getVersionForMember(member);
    VersionTag vt = VersionTag.create(member);
    vt.setEntryVersion(1);
    vt.setRegionVersion(regionVersion+1);
    vt.setMemberID(member);
    vt.setVersionTimeStamp(dummyVersionTs);
    vt.setDistributedSystemId(-1);
    return vt;
  }

  
  @Retained     // Region entry may contain an off-heap value
  public final RegionEntry initRecoveredEntry(Object key, DiskEntry.RecoveredEntry value) {
    boolean needsCallback = false;
    @Retained RegionEntry newRe = getEntryFactory().createEntry((RegionEntryContext) _getOwnerObject(), key, value);
    synchronized (newRe) {
      if (value.getVersionTag()!=null && newRe.getVersionStamp()!=null) {
        newRe.getVersionStamp().setVersions(value.getVersionTag());
      }
      long lastModifiedTime = value.getLastModifiedTime();
      if (lastModifiedTime != 0) {
        newRe.setLastModified(lastModifiedTime);
      }
      //TODO : Suranjan check here.
      RegionEntry oldRe = putEntryIfAbsent(key, newRe);
      LocalRegion owner = null;
      if (_isOwnerALocalRegion()) {
        owner = _getOwner();
      }
      while (oldRe != null) {
        synchronized (oldRe) {
          if (oldRe.isRemoved() && !oldRe.isTombstone()) {
            oldRe = putEntryIfAbsent(key, newRe);
            if (oldRe != null) {
              if (owner != null) {
                owner.getCachePerfStats().incRetries();
              }
            }
          } 
          /*
           * Entry already exists which should be impossible.
           * Free the current entry (if off-heap) and
           * throw an exception.
           */
          else {
            if (newRe.isOffHeap()) {
              ((OffHeapRegionEntry) newRe).release();
            }

            throw new IllegalStateException("Could not recover entry for key " + key + ".  The entry already exists!");
          }
        } // synchronized
      }
      if (owner != null) {
        owner.updateSizeOnCreate(key, owner.calculateRegionEntryValueSize(newRe));
        if (newRe.isTombstone()) {
          // refresh the tombstone so it doesn't time out too soon
          owner.scheduleTombstone(newRe, newRe.getVersionStamp().asVersionTag());
        }
        
        incEntryCount(1); // we are creating an entry that was recovered from disk including tombstone
      }
      lruEntryUpdate(newRe);
      needsCallback = true;
    }
    if (needsCallback) {
      lruUpdateCallback();
    }
    EntryLogger.logRecovery(_getOwnerObject(), key, value);
    return newRe;
  }

  public final RegionEntry updateRecoveredEntry(Object key, RegionEntry re,
      DiskEntry.RecoveredEntry value) {
    boolean needsCallback = false;
    if (re == null) {
      re = getEntry(key);
    }
    if (re == null) {
      return null;
    }
    synchronized (re) {
      if (re.isRemoved() && !re.isTombstone()) {
        return null;
      }
      if (value.getVersionTag()!=null && re.getVersionStamp()!=null) {
        re.getVersionStamp().setVersions(value.getVersionTag());
      }
      long lastModifiedTime = value.getLastModifiedTime();
      if (lastModifiedTime != 0) {
        re.setLastModified(lastModifiedTime);
      }
      try {
        if (_isOwnerALocalRegion()) {
          LocalRegion owner = _getOwner();
          if (re.isTombstone()) {
            // when a tombstone is to be overwritten, unschedule it first
            owner.unscheduleTombstone(re);
          }
          final int oldSize = owner.calculateRegionEntryValueSize(re);
          re.setValue(owner, value); // OFFHEAP no need to call AbstractRegionMap.prepareValueForCache because setValue is overridden for disk and that code takes apart value (RecoveredEntry) and prepares its nested value for the cache
          if (re.isTombstone()) {
            owner.scheduleTombstone(re, re.getVersionStamp().asVersionTag());
          }
          owner.updateSizeOnPut(key, oldSize, owner.calculateRegionEntryValueSize(re));
        } else {
          PlaceHolderDiskRegion phd = (PlaceHolderDiskRegion)_getOwnerObject();
          DiskEntry.Helper.updateRecoveredEntry(phd, (DiskEntry)re, value, phd);
        }
      } catch (RegionClearedException rce) {
        throw new IllegalStateException("RegionClearedException should never happen in this context", rce);
      }
      lruEntryUpdate(re);
      needsCallback = true;
    }
    if (needsCallback) {
      lruUpdateCallback();
    }
    EntryLogger.logRecovery(_getOwnerObject(), key, value);
    return re;
  }

  public final boolean initialImagePut(final Object key,
                                       long lastModified,
                                       Object newValue,
                                       final boolean wasRecovered,
                                       boolean deferLRUCallback,
                                       VersionTag entryVersion, InternalDistributedMember sender, boolean isSynchronizing)
  {
    boolean result = false;
    boolean done = false;
    boolean cleared = false;
    final boolean isCD;
    final LocalRegion owner = _getOwner();
    LogWriterI18n logger = owner.getLogWriterI18n();
    
    if (newValue == Token.TOMBSTONE && !owner.getConcurrencyChecksEnabled()) {

      return false;
    }

    // Since the testGiiFailure and giiExceptionSimulate has to be volatile
    // so keeping the check to these inside fineEnabled and gii finer trace
    if (logger.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
      if (InitialImageOperation.giiExceptionSimulate != null) {
        InitialImageOperation.giiExceptionSimulate.throwException();
      }
    }
    if (owner instanceof HARegion) {
      final Object actualVal;
      if (newValue instanceof CachedDeserializable) {
        actualVal = ((CachedDeserializable)newValue).getDeserializedValue(null,
            null);
        isCD = true;
      }
      else {
        actualVal = newValue;
        isCD = false;
      }
      if (actualVal instanceof HAEventWrapper) {
        HAEventWrapper haEventWrapper = (HAEventWrapper)actualVal;
        // Key was removed at sender side so not putting it into the HARegion
        if (haEventWrapper.getClientUpdateMessage() == null) {
          return false;
        }
        // Getting the instance from singleton CCN..This assumes only one bridge
        // server in the VM
        HAContainerWrapper haContainer = (HAContainerWrapper)CacheClientNotifier
            .getInstance().getHaContainer();
        Map.Entry entry = null;
        HAEventWrapper original = null;
        synchronized (haContainer) {
          entry = (Map.Entry)haContainer.getEntry(haEventWrapper);
          if (entry != null) {
            original = (HAEventWrapper)entry.getKey();
            original.incAndGetReferenceCount();
          }
          else {
            haEventWrapper.incAndGetReferenceCount();
            haEventWrapper.setHAContainer(haContainer);
            haContainer.put(haEventWrapper, haEventWrapper
                .getClientUpdateMessage());
            haEventWrapper.setClientUpdateMessage(null);
            haEventWrapper.setIsRefFromHAContainer(true);
          }
        }
        if (entry != null) {
          HARegionQueue.addClientCQsAndInterestList(entry, haEventWrapper,
              haContainer, owner.getName());
          haEventWrapper.setClientUpdateMessage(null);
          if (isCD) {
            newValue = CachedDeserializableFactory.create(original,
                ((CachedDeserializable)newValue).getSizeInBytes());
          }
          else {
            newValue = original;
          }
        }
      }
    }
    
    try {
      RegionEntry newRe = getEntryFactory().createEntry(owner, key,
          Token.REMOVED_PHASE1);
      EntryEventImpl event = null;
      
      @Retained @Released Object oldValue = null;
      
      final IndexUpdater indexUpdater = this.indexUpdater;
      try {
      RegionEntry oldRe = null;
      boolean oldIsTombstone = false;
      if (logger.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
        logger.info(LocalizedStrings.DEBUG, "DEBUG: initialImagePut for key " + key + " entryVersion=" + entryVersion);
      }
      synchronized (newRe) {
        try {
          // TODO: Suranjan in case of GII do the put here..see if oldEntryMap needs to be checked.
          oldRe = putEntryIfAbsent(key, newRe);
          while (!done && oldRe != null) {
            synchronized (oldRe) {
              if (oldRe.isRemovedPhase2()) {
                oldRe = putEntryIfAbsent(key, newRe);
                if (oldRe != null) {
                  owner.getCachePerfStats().incRetries();
                }
              }
              else {
                boolean acceptedVersionTag = false;
                if (entryVersion != null && owner.concurrencyChecksEnabled) {
                  Assert.assertTrue(entryVersion.getMemberID() != null, "GII entry versions must have identifiers");
                  try {
                    boolean isTombstone = (newValue == Token.TOMBSTONE);
                    // don't reschedule the tombstone if it hasn't changed
                    boolean isSameTombstone = oldRe.isTombstone() && isTombstone
                              && oldRe.getVersionStamp().asVersionTag()
                                .equals(entryVersion);
                    if (isSameTombstone) {
                      return true;
                    }
                    processVersionTagForGII(oldRe, owner, entryVersion, isTombstone, sender, !wasRecovered || isSynchronizing);
                    acceptedVersionTag = true;
                  } catch (ConcurrentCacheModificationException e) {
                    return false;
                  }
                }
                oldIsTombstone = oldRe.isTombstone();
                final int oldSize = owner.calculateRegionEntryValueSize(oldRe);
                // Neeraj: The below if block is to handle the special
                // scenario witnessed in GemFireXD for now. (Though its
                // a general scenario). The scenario is that during GII
                // it is possible that updates start coming before the
                // base value reaches through GII. In that scenario the deltas
                // for that particular key is kept on being added to a list
                // of deltas. When the base value arrives through this path
                // of GII the oldValue will be that list of deltas. When the
                // base values arrives the deltas are applied one by one on that list.
                // The same scenario is applicable for GemFire also but the below 
                // code will be executed only in case of gemfirexd now. Probably
                // the code can be made more generic for both GemFireXD and GemFire.
                
                final LogWriterI18n log = owner.getLogWriterI18n();
                VersionStamp stamp = null;
                VersionTag lastDeltaVersionTag = null;
                if (indexUpdater != null) {
                  oldValue = oldRe.getValueOffHeapOrDiskWithoutFaultIn(owner); // OFFHEAP: ListOfDeltas
                  if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
                    log.info(LocalizedStrings.DEBUG, "ARM::initialImagePut:oldRe = "+ oldRe + "; old value = "+ oldValue);
                  }
                  if (oldValue == Token.NOT_AVAILABLE) {
                    oldValue = null;
                  }
                  else if (oldValue instanceof ListOfDeltas) {
                    // apply the deltas on this new value. update index
                    // Make a new event object
                    // make it an insert operation
                    LocalRegion rgn = owner;  
                    if (owner instanceof BucketRegion) {
                      rgn = ((BucketRegion)owner).getPartitionedRegion();
                    }
                    event = EntryEventImpl.create(rgn, Operation.CREATE, key,
                        null,
                        Boolean.TRUE /* indicate that GII is in progress */,
                        false, null);                  
                    boolean invokingIndexManager = false;
                    try {
                      event.setOldValue(newValue);
                      
                      if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
                        log.info(LocalizedStrings.DEBUG, "initialImagePut: received base value for "
                            + "list of deltas; event: " + event);
                      }
                     //TODO: Need to add oldEntry for each of the deltas that has been stored?
                      ListOfDeltas lod = ((ListOfDeltas)oldValue);
                      lod.sortAccordingToVersionNum(entryVersion != null
                          && owner.concurrencyChecksEnabled, key);
                      lastDeltaVersionTag = lod.getLastDeltaVersionTag();
                      if (lastDeltaVersionTag != null) {
                        lastModified = lastDeltaVersionTag.getVersionTimeStamp();
                      }
                      lod.apply(event);
                      Object preparedNewValue =oldRe.prepareValueForCache(owner,
                          event.getNewValueAsOffHeapDeserializedOrRaw(), false, false);
                      if(preparedNewValue instanceof Chunk) {
                        event.setNewValue(preparedNewValue);
                      }
                      oldRe.setValue(owner,preparedNewValue );
                      stamp = oldRe.getVersionStamp();
                      event.setOldValue(null, true, true);
                      invokingIndexManager = true;
                      indexUpdater.onEvent(owner, event, oldRe);
                      lruEntryUpdate(oldRe);
                      int newSize = owner.calculateRegionEntryValueSize(oldRe);
                      //Can safely accquire memory here. If fails this block removes the current entry from map.
                      owner.acquirePoolMemory(newSize, oldSize, false, null, true);
                      owner.updateSizeOnPut(key, oldSize, newSize);
                      EntryLogger.logInitialImagePut(_getOwnerObject(), key,
                          newValue);
                      result = true;
                      done = true;
                      break;
                    } finally {
                      if (event != null) {
                        if (invokingIndexManager) {
                          // this must be done within the oldRe sync block
                          indexUpdater.postEvent(owner, event, oldRe, done);
                          if (stamp != null) {
                            stamp.setVersions(lastDeltaVersionTag);
                          }
                        }
                        event.release();
                        event = null;
                      }
                    }
                  }
                }
                try {
                  result = oldRe.initialImagePut(owner, lastModified, newValue,
                      wasRecovered, acceptedVersionTag);
                  if (result) {
                    if (indexUpdater != null) {
                      event = getGIIEventForIndexUpdater(owner, key,
                          oldRe._getValue(), oldValue, oldIsTombstone, wasRecovered);
                      if (event == null) {
                        result = false;
                        return false;
                      }
                      indexUpdater.onEvent(owner, event, oldRe);
                    }
                    if (oldIsTombstone) {
                      final boolean validateCount = newValue != Token.TOMBSTONE;
                      owner.unscheduleTombstone(oldRe, validateCount);
                      if (newValue != Token.TOMBSTONE){
                        lruEntryCreate(oldRe);
                      } else {
                        lruEntryUpdate(oldRe);
                      }
                    }
                    if (newValue == Token.TOMBSTONE) {
                      if (owner.getServerProxy() == null &&
                          owner.getVersionVector().isTombstoneTooOld(
                            entryVersion.getMemberID(),
                            entryVersion.getRegionVersion())) {
                        // a tombstone older than this has already been reaped,
                        // so don't retain it
                        removeTombstone(oldRe, entryVersion, false, false);
                        return false;
                      } else {
                        owner.scheduleTombstone(oldRe, entryVersion);
                        lruEntryDestroy(oldRe);
                      }
                    } else {
                      int newSize = owner.calculateRegionEntryValueSize(oldRe);
                      //Can safely accquire memory here. If fails this block removes the current entry from map.
                      owner.calculateEntryOverhead(newRe);
                      if(!oldIsTombstone) {
                        owner.acquirePoolMemory(newSize, oldSize, false, null, true);
                        owner.updateSizeOnPut(key, oldSize, newSize);
                      } else {
                        owner.acquirePoolMemory(0, newSize, true, null, true);
                        owner.updateSizeOnCreate(key, newSize);
                      }
                      EntryLogger.logInitialImagePut(_getOwnerObject(), key, newValue);
                    }
                  }
                  if (owner.getIndexManager() != null) {
                    owner.getIndexManager().updateIndexes(oldRe,
                        oldRe.isRemoved() ? IndexManager.ADD_ENTRY : IndexManager.UPDATE_ENTRY,
                        oldRe.isRemoved() ? IndexProtocol.OTHER_OP : IndexProtocol.AFTER_UPDATE_OP);
                  }
                  done = true;
                } finally {
                  if (event != null) {
                    if (result && indexUpdater != null) {
                      indexUpdater.postEvent(owner, event, oldRe, done);
                    }
                    event.release();
                    event = null;
                  }
                }
              }
            }
          }
          if (!done) {
            boolean versionTagAccepted = false;
            if (entryVersion != null && owner.concurrencyChecksEnabled) {
              Assert.assertTrue(entryVersion.getMemberID() != null, "GII entry versions must have identifiers");
              try {
                boolean isTombstone = (newValue == Token.TOMBSTONE);
                processVersionTagForGII(newRe, owner, entryVersion, isTombstone, sender, !wasRecovered || isSynchronizing);
                versionTagAccepted = true;
              } catch (ConcurrentCacheModificationException e) {
                return false;
              }
            }
            result = newRe.initialImageInit(owner, lastModified, newValue,
                true, wasRecovered, versionTagAccepted);
            try {
              if (result) {
                if (indexUpdater != null) {
                  if (oldValue == null && oldRe != null) {
                    oldValue = oldRe.getValueOffHeapOrDiskWithoutFaultIn(owner);
                    if (oldValue == Token.NOT_AVAILABLE) {
                      oldValue = null;
                    }
                    oldIsTombstone = oldRe.isTombstone();
                  }
                  @Unretained Object preparedNewValue = newRe._getValue(); 
                  event = getGIIEventForIndexUpdater(owner, key, preparedNewValue,
                      oldValue, oldIsTombstone, wasRecovered);
                  if (event == null) {
                    if (newValue == Token.TOMBSTONE) {
                      owner.scheduleTombstone(newRe, entryVersion);
                    }
                    result = false;
                    return false;
                  }
                  indexUpdater.onEvent(owner, event, newRe);
                }
                if (newValue == Token.TOMBSTONE) {
                  owner.scheduleTombstone(newRe, entryVersion);
                } else {
                  int newSize = owner.calculateRegionEntryValueSize(newRe);
                  //Can safely accquire memory here. If fails, this block removes the current entry from map.
                  owner.calculateEntryOverhead(newRe);
                  //System.out.println("Put "+newRe);
                  owner.acquirePoolMemory(0, newSize, true, null, true);
                  owner.updateSizeOnCreate(key, newSize);
                  EntryLogger.logInitialImagePut(_getOwnerObject(), key, newValue);
                  lruEntryCreate(newRe);
                }
                incEntryCount(1);
                //Update local indexes
                if (owner.getIndexManager() != null) {
                  owner.getIndexManager().updateIndexes(newRe, newRe.isRemoved() ? IndexManager.ADD_ENTRY : IndexManager.UPDATE_ENTRY, 
                      newRe.isRemoved() ? IndexProtocol.OTHER_OP : IndexProtocol.AFTER_UPDATE_OP);
                }
              }
              done = true;
            } finally {
              if (event != null) {
                if (result && indexUpdater != null) {
                  indexUpdater.postEvent(owner, event, newRe, done);
                }
                event.release();
                event = null;
              }
            }
          }
        }
        finally {
          if (done && result) {
            initialImagePutEntry(newRe);
          }
          if (!done) {
            if (!newRe.isTombstone()) {
              removeEntry(key, newRe, false);
            }
            if (owner.getIndexManager() != null) {
              owner.getIndexManager().updateIndexes(newRe, IndexManager.REMOVE_ENTRY, IndexProtocol.OTHER_OP);
            }
          } else if (owner.getIndexManager() != null) {
            // Do OQL index maintenance.
            try {
              if (owner.getLogWriterI18n().finerEnabled()) {
                owner.getLogWriterI18n().finer("Updating indexes.");
              }
              owner.getIndexManager().updateIndexes(newRe, IndexManager.ADD_ENTRY , IndexProtocol.OTHER_OP);
            } catch (Exception ex) {
              if (owner.getLogWriterI18n().fineEnabled()) {
                owner.getLogWriterI18n().fine("Exception while updating the index during GII. " + ex.getMessage());
              }
            }
          }
        }
        // Do OQL index maintenance.
        if (done && owner.getIndexManager() != null) {
          try {
            if (owner.getLogWriterI18n().finerEnabled()) {
              owner.getLogWriterI18n().finer("Updating indexes.");
            }
            owner.getIndexManager().updateIndexes(newRe, IndexManager.ADD_ENTRY , IndexProtocol.OTHER_OP);
          } catch (Exception ex) {
            if (owner.getLogWriterI18n().fineEnabled()) {
              owner.getLogWriterI18n().fine("Exception while updating the index during GII. " + ex.getMessage());
            }
          }
        }
      } // synchronized
      } finally {
        if (event != null) event.release();
        OffHeapHelper.release(oldValue);
      }
    } catch(RegionClearedException rce) {
      //Asif: do not issue any sort of callbacks
      done = false;
      cleared= true;
    }catch(QueryException qe) {
      done = false;
      cleared= true;
    }
    finally {
      if (done && !deferLRUCallback) {
        lruUpdateCallback();
      }
      else if (!cleared) {
        resetThreadLocals();
      }
    }
    return result;
  }
  
  private EntryEventImpl getGIIEventForIndexUpdater(LocalRegion owner, Object key,
      Object newValue, Object oldValue, boolean oldIsTombstone,
      boolean wasRecovered) {
    Operation op = null;
    if (newValue == Token.TOMBSTONE) {
      if (oldValue != null && !oldIsTombstone) {
          op = Operation.DESTROY;
      }
      else {
        // both newValue is deleted as well as oldValue so just return
        return null;
      }
    }
    else {
      if (oldIsTombstone || oldValue == null) {
        op = Operation.CREATE;
      }
      else {
        op = Operation.UPDATE;
      }
    }

    EntryEventImpl event = EntryEventImpl.create(owner, op, key, newValue,
        Boolean.TRUE /* indicate that GII is in progress */, false, null);
   // setting the oldValue in the context instead of the
    // event's oldValue to avoid suspect event check in
    // onEvent
    if (op.isUpdate()) {
      event.setContextObject(oldValue);
    }
    
    if (op.isDestroy()) {
      event.setOldValue(oldValue);
    }
    return event;
  }

  protected void initialImagePutEntry(RegionEntry newRe) {
  }

  boolean confirmEvictionDestroy(RegionEntry re)
  {
    /* We arn't in an LRU context, and should never get here */
//    Assert.assertTrue(false,
//        "Not an LRU region, can not confirm LRU eviction operation");
    // For custom eviction we need to evict
    return true;
  }

  public final boolean destroy(EntryEventImpl event,
                               boolean inTokenMode,
                               boolean duringRI,
                               boolean cacheWrite,
                               boolean isEviction,
                               Object expectedOldValue,
                               boolean removeRecoveredEntry)
  throws CacheWriterException, EntryNotFoundException, TimeoutException {
    
    final LocalRegion owner = _getOwner();

    if (owner == null) {
      Assert.assertTrue(false, "The owner for RegionMap " + this    // "fix" for bug 32440
          + " is null for event " + event);
    }
    
//    owner.getLogWriterI18n().fine("ARM.destroy called for " + event
//        + " isEviction=" + isEviction + " tokenMode=" + inTokenMode + " duringRI=" + duringRI + " tokens="
//        + owner.getImageState().getDestroyedEntriesCount());
    
    //mbid: this has been added to maintain consistency between the disk region
    // and
    //and the region map after clear() has been called. This will set the
    // reference of
    //the diskSegmentRegion as a ThreadLocal so that if the diskRegionSegment
    // is later changed
    //by another thread, we can do the necessary.

    boolean retry = true;
//    int retries = -1;
    
RETRY_LOOP:
  while (retry) {
    retry = false;
    /* this is useful for debugging if you get a hot thread
    retries++;
    if (retries > 0) {
      owner.getCachePerfStats().incRetries();
      if (retries == 1000000) {
        owner.getCache().getLoggerI18n().warning(
          LocalizedStrings.AbstractRegionMap_RETRIED_1_MILLION_TIMES_FOR_ENTRY_TO_GO_AWAY_0, retryEntry, retryEntry.removeTrace);
      }
    }
    */
    
//    boolean lruUpdateCallback = false;
    
    boolean indexLocked = false;
    final IndexUpdater indexUpdater = getIndexUpdater();
    if (indexUpdater != null) {
    // take read lock for GFXD index initializations if required
      indexLocked = indexUpdater.lockForIndexGII();
    }
    boolean opCompleted = false;
    boolean doPart3 = false;
    
    // We need to acquire the region entry while holding the lock to avoid #45620.
    // However, we also want to release the lock before distribution to prevent
    // potential deadlocks.  The outer try/finally ensures that the lock will be
    // released without fail.  I'm avoiding indenting just to preserve the ability
    // to track diffs since the code is fairly complex.
    boolean doUnlock = true;
    lockForCacheModification(owner, event);
    try {

    RegionEntry re = getOrCreateRegionEntry(owner, event, Token.REMOVED_PHASE1, null, true, true);
    
    /*
     * Execute the test hook runnable inline (not threaded) if it is not null. 
     */
    Runnable runme = testHookRunnableFor48182;
    if(null != runme) {
      runme.run();
    }    
    
    RegionEntry tombstone = null;
    boolean haveTombstone = false;
    try {
      if ((AbstractLRURegionMap.debug || TombstoneService.VERBOSE || TombstoneService.DEBUG_TOMBSTONE_COUNT)
          && !(owner instanceof HARegion)) {
        owner.getLogWriterI18n().info(LocalizedStrings.DEBUG,
            "ARM.destroy() inTokenMode="+inTokenMode +"; duringRI=" + duringRI
          +"; riLocalDestroy=" + event.isFromRILocalDestroy()
          +"; withRepl=" + owner.dataPolicy.withReplication()
          +"; fromServer=" + event.isFromServer()
          +"; concurrencyEnabled=" + owner.concurrencyChecksEnabled
          +"; isOriginRemote=" + event.isOriginRemote()
          +"; isEviction=" + isEviction
          +"; operation=" + event.getOperation()
          +"; re=" + re);
      }
      // if destroyEntry has been invoked then do not attempt to perform
      // index maintenance in removeEntry
      boolean destroyEntryInvoked = false;

      if (event.isFromRILocalDestroy()) {
        // for RI local-destroy we don't want to keep tombstones.
        // In order to simplify things we just set this recovery
        // flag to true to force the entry to be removed
        removeRecoveredEntry = true;
      }

      // the logic in this method is already very involved, and adding tombstone
      // permutations to (re != null) greatly complicates it.  So, we check
      // for a tombstone here and, if found, pretend for a bit that the entry is null
      if (re != null && re.isTombstone() && !removeRecoveredEntry) {
        tombstone = re;
        haveTombstone = true;
        re = null;
      }
      if (re == null) {
        // we need to create an entry if in token mode or if we've received
        // a destroy from a peer or WAN gateway and we need to retain version
        // information for concurrency checks
        boolean retainForConcurrency = (!haveTombstone
            && (owner.dataPolicy.withReplication() || event.isFromServer())
            && owner.concurrencyChecksEnabled
            && (event.isOriginRemote() /* destroy received from other must create tombstone */
                || event.isFromWANAndVersioned() /* wan event must create a tombstone */
                || event.isBridgeEvent())); /* event from client must create a tombstone so client has a version # */ 
        if (inTokenMode
            || retainForConcurrency) { 
          // removeRecoveredEntry should be false in this case
          RegionEntry newRe = getEntryFactory().createEntry(owner,
                                                            event.getKey(),
                                                            Token.REMOVED_PHASE1);
          // Fix for Bug #44431. We do NOT want to update the region and wait
          // later for index INIT as region.clear() can cause inconsistency if
          // happened in parallel as it also does index INIT.
          if (owner.getIndexManager() != null) {
            owner.getIndexManager().waitForIndexInit();
          }
          try {
            synchronized (newRe) {
              //TODO:MVCC this will get covered in destroyEntry
              RegionEntry oldRe = putEntryIfAbsent(event.getKey(), newRe);
              try { // bug #42228 - leaving "removed" entries in the cache
              while (!opCompleted && oldRe != null) {
                synchronized (oldRe) {
                  if (oldRe.isRemovedPhase2()) {
                    oldRe = putEntryIfAbsent(event.getKey(), newRe);
                    if (oldRe != null) {
                      owner.getCachePerfStats().incRetries();
                    }
                  } else {
                    event.setRegionEntry(oldRe);
                  
                    // Last transaction related eviction check. This should
                    // prevent
                    // transaction conflict (caused by eviction) when the entry
                    // is being added to transaction state.
                    if (isEviction) {
                      if (!confirmEvictionDestroy(oldRe) || (owner.getEvictionCriteria() != null && !owner.getEvictionCriteria().doEvict(event))) {
                        opCompleted = false;
                        return opCompleted;
                      }
                    }
                    try {
                      //if concurrency checks are enabled, destroy will
                      //set the version tag
                      destroyEntryInvoked = true;
                      boolean destroyed = destroyEntry(oldRe, event, inTokenMode, cacheWrite, expectedOldValue, false, removeRecoveredEntry);
                      if (destroyed) {
                        if (retainForConcurrency) {
                          owner.basicDestroyBeforeRemoval(oldRe, event);
                        }
                        owner.basicDestroyPart2(oldRe, event, inTokenMode,
                            false /* conflict with clear */, duringRI, true);
//                        if (!oldRe.isTombstone() || isEviction) {
                          lruEntryDestroy(oldRe);
//                        } else {  // tombstone 
//                          lruEntryUpdate(oldRe);
//                          lruUpdateCallback = true;
//                        }
                        doPart3 = true;
                      }
                    }
                    catch (RegionClearedException rce) {
                      // region cleared implies entry is no longer there
                      // so must throw exception if expecting a particular
                      // old value
  //                    if (expectedOldValue != null) {
  //                      throw new EntryNotFoundException("entry not found with expected value");
  //                    }
                      // Ignore. The exception will ensure that we do not update
                      // the LRU List
                      owner.basicDestroyPart2(oldRe, event, inTokenMode,
                          true/* conflict with clear */, duringRI, true);
                      doPart3 = true;
                    } catch (ConcurrentCacheModificationException ccme) {
                      VersionTag tag = event.getVersionTag();
                      if (tag != null && tag.isTimeStampUpdated()) {
                        // Notify gateways of new time-stamp.
                        owner.notifyTimestampsToGateways(event);
                      }
                      throw ccme;
                    }
                    re = oldRe;
                    opCompleted = true;
                  }
                } // synchronized oldRe
              } // while
              if (!opCompleted) {
                re = newRe;
                event.setRegionEntry(newRe);
                try {
                  //if concurrency checks are enabled, destroy will
                  //set the version tag
                  if (isEviction) {
                    opCompleted = false;
                    return opCompleted; 
                  }
                  destroyEntryInvoked = true;
                  opCompleted = destroyEntry(newRe, event, inTokenMode, cacheWrite, expectedOldValue, true, removeRecoveredEntry);
                  if (opCompleted) {
                    // This is a new entry that was created because we are in
                    // token mode or are accepting a destroy operation by adding
                    // a tombstone.  There is no oldValue, so we don't need to
                    // call updateSizeOnRemove
//                    owner.recordEvent(event);
                    event.setIsRedestroyedEntry(true);  // native clients need to know if the entry didn't exist
                    if (retainForConcurrency) {
                      owner.basicDestroyBeforeRemoval(oldRe, event);
                    }
                    owner.basicDestroyPart2(newRe, event, inTokenMode,
                        false /* conflict with clear */, duringRI, true);
                    doPart3 = true;
                  }
                }
                catch (RegionClearedException rce) {
                  // region cleared implies entry is no longer there
                  // so must throw exception if expecting a particular
                  // old value
  //                if (expectedOldValue != null) {
  //                  throw new EntryNotFoundException("entry not found with expected value");
  //                }
                  // Ignore. The exception will ensure that we do not update
                  // the LRU List
                  opCompleted = true;
                  EntryLogger.logDestroy(event);
//                  owner.recordEvent(event, newRe);
                  owner.basicDestroyPart2(newRe, event, inTokenMode, true /* conflict with clear*/, duringRI, true);
                  doPart3 = true;
                } catch (ConcurrentCacheModificationException ccme) {
                  VersionTag tag = event.getVersionTag();
                  if (tag != null && tag.isTimeStampUpdated()) {
                    // Notify gateways of new time-stamp.
                    owner.notifyTimestampsToGateways(event);
                  }
                  throw ccme;
                }
                // Note no need for LRU work since the entry is destroyed
                // and will be removed when gii completes
              } // !opCompleted
              } finally { // bug #42228
                if (!opCompleted && !haveTombstone && event.getOperation() == Operation.REMOVE) {
//                  owner.getLogWriterI18n().warning(LocalizedStrings.DEBUG, "BRUCE: removing incomplete entry for remove()");
                  removeEntry(event.getKey(), newRe, false);
                }
                if (!opCompleted && isEviction) {
                  removeEntry(event.getKey(), newRe, false);
                }
                if (newRe != re) {
                  newRe.returnToPool();
                }
              }
            } // synchronized newRe
          } finally {
            if (owner.getIndexManager() != null) {
              owner.getIndexManager().countDownIndexUpdaters();
            }
          }
        } // inTokenMode or tombstone creation
        else {
          if (!isEviction || owner.concurrencyChecksEnabled) {                                 
            // The following ensures that there is not a concurrent operation
            // on the entry and leaves behind a tombstone if concurrencyChecksEnabled.
            // It fixes bug #32467 by propagating the destroy to the server even though
            // the entry isn't in the client
            RegionEntry newRe = haveTombstone ? tombstone : getEntryFactory().createEntry(owner, event.getKey(),
                  Token.REMOVED_PHASE1);
            synchronized(newRe) {
              if (haveTombstone && !tombstone.isTombstone()) {
                // we have to check this again under synchronization since it may have changed
                retry = true;
                //retryEntry = tombstone; // leave this in place for debugging
                newRe.returnToPool();
                continue RETRY_LOOP;
              }
              //TODO: MVCC this case?
              re = (RegionEntry)_getMap().putIfAbsent(event.getKey(), newRe);
              if (re != null && re != tombstone) {
                // concurrent change - try again
                retry = true;
                //retryEntry = tombstone; // leave this in place for debugging
                newRe.returnToPool();
                continue RETRY_LOOP;
              }
              else if (!isEviction) {
                boolean throwex = false;
                EntryNotFoundException ex =  null;
                try {
                  if (!cacheWrite) {
                    throwex = true;
                  } else {
                    try {
                      if (!removeRecoveredEntry) {
                        throwex = !owner.bridgeWriteBeforeDestroy(event, expectedOldValue);
                      }
                    } catch (EntryNotFoundException e) {
                      throwex = true;
                      ex = e; 
                    }
                  }
                  if (throwex) {
                    if (!event.isOriginRemote() && !event.getOperation().isLocal() &&
                        (event.isFromBridgeOrPossDupAndVersioned() ||  // if this is a replayed client event that already has a version
                            event.isFromWANAndVersioned())) { // or if this is a WAN event that has been applied in another system
                      // we must distribute these since they will update the version information in peers
                      if (owner.getLogWriterI18n().fineEnabled()) {
                        owner.getLogWriterI18n().fine("ARM.destroy is allowing wan/client destroy of "
                            + event.getKey() + " to continue");
                      }
                      throwex = false;
                      event.setIsRedestroyedEntry(true);
                      // Distribution of this op happens on re and re might me null here before
                      // distributing this destroy op.
                      if (re == null) {
                        re = newRe;
                      }
                      doPart3 = true;
                    }
                  }
                  if (throwex) {                    
                    if (ex == null) {
                      // Fix for 48182, check cache state and/or region state before sending entry not found.
                      // this is from the server and any exceptions will propogate to the client
                      owner.checkEntryNotFound(event.getKey());
                    } else {
                      throw ex;
                    }
                  }
                } finally {
                  // either remove the entry or leave a tombstone
                  try {
                    if (!event.isOriginRemote() && event.getVersionTag() != null && owner.concurrencyChecksEnabled) {
                      // this shouldn't fail since we just created the entry.
                      // it will either generate a tag or apply a server's version tag
                      processVersionTag(newRe, event);
                      if (doPart3) {
                        owner.generateAndSetVersionTag(event, newRe);
                      }
                      try {
                        owner.recordEvent(event);
                        newRe.makeTombstone(owner, event.getVersionTag());
                      } catch (RegionClearedException e) {
                        // that's okay - when writing a tombstone into a disk, the
                        // region has been cleared (including this tombstone)
                      }
                      opCompleted = true;
  //                    lruEntryCreate(newRe);
                    } else if (!haveTombstone) {
                      try {
                        assert newRe != tombstone;
                        newRe.setValue(owner, Token.REMOVED_PHASE2);
                        removeEntry(event.getKey(), newRe, false);
                      } catch (RegionClearedException e) {
                        // that's okay - we just need to remove the new entry
                      }
                    } else if (event.getVersionTag() != null ) { // haveTombstone - update the tombstone version info
                      processVersionTag(tombstone, event);
                      if (doPart3) {
                        owner.generateAndSetVersionTag(event, newRe);
                      }
                      // This is not conflict, we need to persist the tombstone again with new version tag 
                      try {
                        tombstone.setValue(owner, Token.TOMBSTONE);
                      } catch (RegionClearedException e) {
                        // that's okay - when writing a tombstone into a disk, the
                        // region has been cleared (including this tombstone)
                      }
                      owner.recordEvent(event);
                      owner.rescheduleTombstone(tombstone, event.getVersionTag());
                      owner.basicDestroyPart2(tombstone, event, inTokenMode,
                          true /* conflict with clear*/, duringRI, true);
                      opCompleted = true;
                    }
                  } catch (ConcurrentCacheModificationException ccme) {
                    VersionTag tag = event.getVersionTag();
                    if (tag != null && tag.isTimeStampUpdated()) {
                      // Notify gateways of new time-stamp.
                      owner.notifyTimestampsToGateways(event);
                    }
                    throw ccme;
                  } finally {
                    if (newRe != re) {
                      newRe.returnToPool();
                    }
                  }
                }
              }
            } // synchronized(newRe)
          }
        }
      } // no current entry
      else { // current entry exists
        if (owner.getDiskRegion() != null && owner.getIndexManager() != null) {
          owner.getIndexManager().waitForIndexInit();
        }
        try {
          synchronized (re) {
            
            //          if (owner.getLogWriterI18n().fineEnabled()) {
            //            owner.getLogWriterI18n().fine("re.isRemoved()="+re.isRemoved()
            //                +"; re.isTombstone()="+re.isTombstone()
            //                +"; event.isOriginRemote()="+event.isOriginRemote());
            //          }
            // if the entry is a tombstone and the event is from a peer or a client
            // then we allow the operation to be performed so that we can update the
            // version stamp.  Otherwise we would retain an old version stamp and may allow
            // an operation that is older than the destroy() to be applied to the cache
            // Bug 45170: If removeRecoveredEntry, we treat tombstone as regular entry to be deleted
            boolean createTombstoneForConflictChecks = (owner.concurrencyChecksEnabled
                && (event.isOriginRemote() || event.getContext() != null || removeRecoveredEntry));
            if (!re.isRemoved() || createTombstoneForConflictChecks) {
              if (re.isRemovedPhase2()) {
                retry = true;
                continue RETRY_LOOP;
              }
              event.setRegionEntry(re);
              
              // See comment above about eviction checks
              if (isEviction) {
                assert expectedOldValue == null;
                if (!confirmEvictionDestroy(re) || (owner.getEvictionCriteria() != null && !owner.getEvictionCriteria().doEvict(event))) {
                  opCompleted = false;
                  return opCompleted;
                }
              }

              boolean removed = false;
              try {
                destroyEntryInvoked = true;
                opCompleted = destroyEntry(re, event, inTokenMode, cacheWrite, expectedOldValue, false, removeRecoveredEntry);
                if (opCompleted) {
                  // It is very, very important for Partitioned Regions to keep
                  // the entry in the map until after distribution occurs so that other
                  // threads performing a create on this entry wait until the destroy
                  // distribution is finished.
                  // keeping backup copies consistent. Fix for bug 35906.
                  // -- mthomas 07/02/2007 <-- how about that date, kinda cool eh?
                  owner.basicDestroyBeforeRemoval(re, event);

                  // do this before basicDestroyPart2 to fix bug 31786
                  if (!inTokenMode) {
                    if ( re.getVersionStamp() == null) {
                      re.removePhase2(owner);
                      // GFXD index maintenance will happen from destroyEntry call
                      removeEntry(event.getKey(), re, true);
                      removed = true;
                    }
                  }
                  if (inTokenMode && !duringRI) {
                    event.inhibitCacheListenerNotification(true);
                  }
                  doPart3 = true;
                  owner.basicDestroyPart2(re, event, inTokenMode, false /* conflict with clear*/, duringRI, true);
//                  if (!re.isTombstone() || isEviction) {
                    lruEntryDestroy(re);
//                  } else {
//                    lruEntryUpdate(re);
//                    lruUpdateCallback = true;
//                  }
                } else {
                  if (!inTokenMode) {
                    EntryLogger.logDestroy(event);
                    owner.recordEvent(event);
                    if (re.getVersionStamp() == null) {
                      re.removePhase2(owner);
                      // GFXD index maintenance will happen from destroyEntry call
                      removeEntry(event.getKey(), re, true);
                      lruEntryDestroy(re);
                    } else {
                      if (re.isTombstone()) {
                        // the entry is already a tombstone, but we're destroying it
                        // again, so we need to reschedule the tombstone's expiration
                        if (event.isOriginRemote()) {
                          owner.rescheduleTombstone(re, re.getVersionStamp().asVersionTag());
                        }
                      }
                    }
                    lruEntryDestroy(re);
                    opCompleted = true;
                  }
                }
              }
              catch (RegionClearedException rce) {
                // Ignore. The exception will ensure that we do not update
                // the LRU List
                opCompleted = true;
                owner.recordEvent(event);
                if (inTokenMode && !duringRI) {
                  event.inhibitCacheListenerNotification(true);
                }
                owner.basicDestroyPart2(re, event, inTokenMode, true /*conflict with clear*/, duringRI, true);
                doPart3 = true;
              }
              finally {
                if (re.isRemoved() && !re.isTombstone()) {
                  if (!removed) {
                    // GFXD index maintenance will happen from destroyEntry call
                    removeEntry(event.getKey(), re, true);
                  }
                }
              }
            } // !isRemoved
            else { // already removed
              if (owner.isHDFSReadWriteRegion() && re.isRemovedPhase2()) {
                // For HDFS region there may be a race with eviction
                // so retry the operation. fixes bug 49150
                retry = true;
                continue RETRY_LOOP;
              }
              if (re.isTombstone() && event.getVersionTag() != null) {
                // if we're dealing with a tombstone and this is a remote event
                // (e.g., from cache client update thread) we need to update
                // the tombstone's version information
                // TODO use destroyEntry() here
                processVersionTag(re, event);
                try {
                  re.makeTombstone(owner, event.getVersionTag());
                } catch (RegionClearedException e) {
                  // that's okay - when writing a tombstone into a disk, the
                  // region has been cleared (including this tombstone)
                }
              }
              if (expectedOldValue != null) {
                // if re is removed then there is no old value, so return false
                return false;
              }

              if (!inTokenMode && !isEviction) {
                owner.checkEntryNotFound(event.getKey());
              }
//              if (isEviction && re.isTombstone()) {
//                owner.unscheduleTombstone(re);
//                removeTombstone(re, re.getVersionStamp().getEntryVersion(), true);
//              }
            }
          } // synchronized re
        }  catch (ConcurrentCacheModificationException ccme) {
          VersionTag tag = event.getVersionTag();
          if (tag != null && tag.isTimeStampUpdated()) {
            // Notify gateways of new time-stamp.
            owner.notifyTimestampsToGateways(event);
          }
          throw ccme;
        } finally {
          if (owner.getDiskRegion() != null && owner.getIndexManager() != null) {
            owner.getIndexManager().countDownIndexUpdaters();
          }
        }
        // No need to call lruUpdateCallback since the only lru action
        // we may have taken was lruEntryDestroy. This fixes bug 31759.

      } // current entry exists
      if(opCompleted) {
        EntryLogger.logDestroy(event);
      }
      return opCompleted;
    }
    finally {
      releaseCacheModificationLock(owner, event);
      doUnlock = false;
      
      try {
        // release the GFXD index lock, if acquired
        if (indexLocked) {
          indexUpdater.unlockForIndexGII();
        }
                
        // This means basicDestroyPart2 has been called
        // So purge entries from suspect list for replicated tables
        if (owner.getLogWriterI18n().fineEnabled()) {
          owner.getLogWriterI18n().fine(
              "ARM.destroy done for key="
                  + event.getKey()
                  + " ,region: "
                  + owner.getName()
                  + " ,doPart3="
                  + doPart3
                  + " ,isReplicatedRegion="
                  + this.isReplicatedRegion
                  + " ,indexUpdater="
                  + indexUpdater
                  + " ,indexUpdater.handleSuspectEvents="
                  + (indexUpdater == null ? "null" : indexUpdater
                      .handleSuspectEvents()));
        }
        if (doPart3 && this.isReplicatedRegion && indexUpdater != null
            && indexUpdater.handleSuspectEvents()) {
          if (owner.getLogWriterI18n().fineEnabled()) {
            owner.getLogWriterI18n().fine(
                "ARM::destroy Purge suspect list. applyAllSuspectsFinished="
                    + applyAllSuspectsFinished
                    + " ,suspectEntries.isEmpty="
                    + this.suspectEntries.isEmpty()
                    + " ,owner.isInitialized=" + owner.isInitialized());
          }
          if (!owner.isInitialized()
              && !this.applyAllSuspectsFinished
              && !this.suspectEntries.isEmpty()) {
            SuspectEntryList list = null;
            // Lock from r43670 will be reintroduced, soon!
            // this.suspectEntriesLock.attemptReadLock(-1);
            try {
              if (!owner.isInitialized()
                  && !this.applyAllSuspectsFinished) {
                list = this.suspectEntries.remove(event.getKey());
              }
            } finally {
              // this.suspectEntriesLock.releaseReadLock();
            }
            if (list != null && owner.getLogWriterI18n().fineEnabled()) {
              StringBuffer sb = new StringBuffer();
              sb.append("entries=[");
              for (EntryEventImpl e : list) {
                sb.append(e + ", ");
              }
              sb.append("] ");
              owner.getLogWriterI18n().fine(
                  "ARM::destroy Purged suspect list: " + sb);
            }
          }
        }
             
        // If concurrency conflict is there and event contains gateway version tag then
        // do NOT distribute.
        if (event.isConcurrencyConflict() &&
            (event.getVersionTag() != null && event.getVersionTag().isGatewayTag())) {
          doPart3 = false;
        }
        // distribution and listener notification
        if (doPart3) {
          owner.basicDestroyPart3(re, event, inTokenMode, duringRI, true, expectedOldValue);
        }
//        if (lruUpdateCallback) {
//          lruUpdateCallback();
//        }
      } finally {
        if (opCompleted) {
          if (re != null) {
            owner.cancelExpiryTask(re);
          } else if (tombstone != null) {
            owner.cancelExpiryTask(tombstone);
          }
        }
        if (opCompleted && re != null) {
          re.returnToPool();
        }
      }
    }
    
    } finally { // failsafe on the read lock...see comment above
      if (doUnlock) {
        releaseCacheModificationLock(owner, event);
      }
    }
    } // retry loop
    return false;
  }

  @Override
  public final void txApplyDestroy(final RegionEntry re,
      final TXStateInterface txState, Object key, boolean inTokenMode,
      boolean inRI, boolean localOp, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent) {

    final LocalRegion owner = _getOwner();

    final boolean isRegionReady = !inTokenMode;
    boolean cbEventInPending = false;
    LogWriterI18n log = owner.getLogWriterI18n();
    try {

      // check that entry lock has already been upgraded to EX mode
      assert re.hasExclusiveLock(null, null):
        "unexpected absence of EX lock during txApply";

      // TODO: merge: isn't this better done once before commit?
      //Map<AbstractIndex, Long> lockedIndexes = owner
      //    .acquireWriteLocksOnCompactRangeIndexes();
      RegionEntry oldRe = null;
      try {
      if (!re.isDestroyedOrRemoved()) {
        final int oldSize = owner.calculateRegionEntryValueSize(re);
        // Create an entry event only if the calling context is
        // a receipt of a TXCommitMessage AND there are callbacks installed
        // for this region
        if (shouldCreateCBEvent(owner,
            false/* isInvalidate */, isRegionReady || inRI)) {
          // create new EntryEventImpl in case pendingCallbacks is non-null
          if (pendingCallbacks != null) {
            long lastMod = cbEvent.getEventTime(0L, owner);
            cbEvent = new EntryEventImpl();
            cbEvent.setEntryLastModified(lastMod);
          }
          cbEvent = createCBEvent(owner, localOp ? Operation.LOCAL_DESTROY
              : Operation.DESTROY, key, null, txState, eventId,
              aCallbackArgument, filterRoutingInfo, bridgeContext,
              versionTag, tailKey, cbEvent);
          cbEvent.setRegionEntry(re);
          
          @Retained @Released Object oldValue = re.getValueInVM(owner);
          
          try {
            cbEvent.setOldValue(oldValue);
          } finally {
            OffHeapHelper.release(oldValue);
          }
          if (log.fineEnabled()) {
            log.fine("txApplyDestroy cbEvent=" + cbEvent);
          }
        }
        else {
          cbEvent = null;
        }
        txRemoveOldIndexEntry(Operation.DESTROY, re);
        boolean clearOccured = false;
        try {
          processAndGenerateTXVersionTag(owner, cbEvent, re, txr);
          if (inTokenMode) {
            re.setValue(owner, Token.DESTROYED);
          }
          else if (cbEvent != null && owner.getConcurrencyChecksEnabled()
              && (versionTag = cbEvent.getVersionTag()) != null) {
            re.makeTombstone(owner, versionTag);
          }
          else {
            re.removePhase1(owner, false); // fix for bug 43063
            re.removePhase2(owner);
            removeEntry(key, re, true);
          }
          if (EntryLogger.isEnabled()) {
            EntryLogger.logTXDestroy(_getOwnerObject(), key);
          }
          owner.updateSizeOnRemove(key, oldSize);
        }
        catch (RegionClearedException rce) {
          clearOccured = true;
        }
        owner.txApplyDestroyPart2(re, key, inTokenMode,
            clearOccured /* Clear Conflciting with the operation */);
        if (cbEvent != null) {
          if (pendingCallbacks == null) {
            owner.invokeTXCallbacks(EnumListenerEvent.AFTER_DESTROY,
                cbEvent, true/*callDispatchListenerEvent*/, true/*notifyGateway*/);
          }
          else {
            pendingCallbacks.add(cbEvent);
            cbEventInPending = true;
          }
        }
        if (!clearOccured) {
          lruEntryDestroy(re);
        }
      }
      else if (inTokenMode || owner.concurrencyChecksEnabled) {

        if (shouldCreateCBEvent(owner,
            false /* isInvalidate */, isRegionReady || inRI)) {
          cbEvent = createCBEvent(owner, localOp ? Operation.LOCAL_DESTROY
              : Operation.DESTROY, key, null, txState, eventId,
              aCallbackArgument, filterRoutingInfo, bridgeContext,
              versionTag, tailKey, cbEvent);
          cbEvent.setRegionEntry(re);
          cbEvent.setOldValue(Token.NOT_AVAILABLE);
          if (log.fineEnabled()) {
            log.fine("txApplyDestroy token mode cbEvent=" + cbEvent);
          }
        }
        else {
          cbEvent = null;
        }
        try {
          EntryEventImpl txEvent = null;
          if (!isRegionReady) {
            // creating the event just to process the version tag.
            txEvent = createCBEvent(owner, localOp ? Operation.LOCAL_DESTROY
                    : Operation.DESTROY, key, null, txState, eventId,
                aCallbackArgument, filterRoutingInfo, bridgeContext,
                versionTag, tailKey, cbEvent);
          }
          processAndGenerateTXVersionTag(owner, (txEvent != null) ? txEvent : cbEvent, re, txr);

          int oldSize = 0;
          if (cbEvent != null && owner.getConcurrencyChecksEnabled()
              && (versionTag = cbEvent.getVersionTag()) != null) {
            if (re.isTombstone()) {
              // need to persist the tombstone again with new version tag
              re.setValue(owner, Token.TOMBSTONE);
              owner.rescheduleTombstone(re, versionTag);
            }
            else {
              oldSize = owner.calculateRegionEntryValueSize(re);
              re.makeTombstone(owner, versionTag);
            }
          }
          else {
            if (re.isTombstone()) {
              owner.unscheduleTombstone(re);
            }
            else {
              oldSize = owner.calculateRegionEntryValueSize(re);
            }
            re.setValue(owner, Token.DESTROYED);
          }
          if (EntryLogger.isEnabled()) {
            EntryLogger.logTXDestroy(_getOwnerObject(), key);
          }
          owner.updateSizeOnRemove(key, oldSize);
          owner.txApplyDestroyPart2(re, key, inTokenMode,
              false /* Clear Conflicting with the operation */);
          lruEntryDestroy(re);
        }
        catch (RegionClearedException rce) {
          owner.txApplyDestroyPart2(re, key, inTokenMode,
              true /* Clear Conflicting with the operation */);
        }

        // if this is a bucket we need to pass the event to listeners
        if (cbEvent != null) {
          if (pendingCallbacks == null) {
            owner.invokeTXCallbacks(EnumListenerEvent.AFTER_DESTROY,
                cbEvent, true/*callDispatchListenerEvent*/, true /*notifyGateway*/);
          }
          else {
            pendingCallbacks.add(cbEvent);
            cbEventInPending = true;
          }
        }
      /* (below case will not happen for new TX model)
      } else if (re == null) {
        // Fix bug#43594
        // In cases where bucket region is re-created, it may so happen that 
        // the destroy is already applied on the Initial image provider, thus 
        // causing region entry to be absent. 
        // Notify clients with client events.
        EntryEventImpl cbEvent = createCBEvent(owner, 
            localOp ? Operation.LOCAL_DESTROY : Operation.DESTROY, 
            key, null, txId, txEvent, eventId, aCallbackArgument, 
            filterRoutingInfo, bridgeContext, txEntryState, versionTag, tailKey);
        try {
        if (owner.isUsedForPartitionedRegionBucket()) {
          txHandleWANEvent(owner, cbEvent, txEntryState);
        }
        switchEventOwnerAndOriginRemote(cbEvent, hasRemoteOrigin);
        if (pendingCallbacks == null) {
          owner.invokeTXCallbacks(EnumListenerEvent.AFTER_DESTROY,cbEvent,false);
        } else {
          pendingCallbacks.add(cbEvent);
          cbEventInPending = true;
        }
        } finally {
          if (!cbEventInPending) cbEvent.freeOffHeapReferences();
        }
      */
      }
      } finally {
        //owner.releaseAcquiredWriteLocksOnIndexes(lockedIndexes);
        if (oldRe != null)
          oldRe.setUpdateInProgress(false);
      }
    } catch (DiskAccessException dae) {
      owner.handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
      if (!cbEventInPending && cbEvent != null) cbEvent.release();
    }
  }

  public final boolean invalidate(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry, boolean forceCallbacks)
      throws EntryNotFoundException
  {
    final LocalRegion owner = _getOwner();
    if (owner == null) {
      // "fix" for bug 32440
      Assert.assertTrue(false, "The owner for RegionMap " + this
          + " is null for event " + event);

    }
    final LogWriterI18n log = owner.getCache().getLoggerI18n();
    boolean didInvalidate = false;
    RegionEntry invalidatedRe = null;
    boolean clearOccured = false;

    //DiskRegion dr = owner.getDiskRegion();
    // Fix for Bug #44431. We do NOT want to update the region and wait
    // later for index INIT as region.clear() can cause inconsistency if
    // happened in parallel as it also does index INIT.
    if (owner.getIndexManager() != null) {
      owner.getIndexManager().waitForIndexInit();
    }
    lockForCacheModification(owner, event);
    try {
      if (forceNewEntry || forceCallbacks) {
        boolean opCompleted = false;
        RegionEntry newRe = getEntryFactory().createEntry(owner, event.getKey(),
            Token.REMOVED_PHASE1);
          synchronized (newRe) {
            try {
              RegionEntry oldRe = putEntryIfAbsent(event.getKey(), newRe);
              while (!opCompleted && oldRe != null) {
                synchronized (oldRe) {
                  // if the RE is in phase 2 of removal, it will really be removed
                  // from the map.  Otherwise, we can use it here and the thread
                  // that is destroying the RE will see the invalidation and not
                  // proceed to phase 2 of removal.
                  if (oldRe.isRemovedPhase2()) {
                    oldRe = putEntryIfAbsent(event.getKey(), newRe);
                    if (oldRe != null) {
                      owner.getCachePerfStats().incRetries();
                    }
                  } else {
                    opCompleted = true;
                    event.setRegionEntry(oldRe);
                    if (oldRe.isDestroyed()) {
                      if (log.finerEnabled()) {
                        log
                            .finer("mapInvalidate: Found DESTROYED token, not invalidated; key="
                                + event.getKey());
                      }
                    } else if (oldRe.isInvalid()) {
                    
                      // was already invalid, do not invoke listeners or increment
                      // stat
                      if (log.fineEnabled()) {
                        log.fine("mapInvalidate: Entry already invalid: '"
                            + event.getKey() + "'");
                      }
                      processVersionTag(oldRe, event);
                      try {
                        oldRe.setValue(owner, oldRe.getValueInVM(owner)); // OFFHEAP noop setting an already invalid to invalid; No need to call prepareValueForCache since it is an invalid token.
                      } catch (RegionClearedException e) {
                        // that's okay - when writing an invalid into a disk, the
                        // region has been cleared (including this token)
                      }
                    } else {
                      owner.cacheWriteBeforeInvalidate(event, invokeCallbacks, forceNewEntry);
                      if (owner.concurrencyChecksEnabled && event.noVersionReceivedFromServer()) {
                        // server did not perform the invalidation, so don't leave an invalid
                        // entry here
                        return false;
                      }
                      final int oldSize = owner.calculateRegionEntryValueSize(oldRe);
                      //added for cq which needs old value. rdubey
                      FilterProfile fp = owner.getFilterProfile();
                      if (!oldRe.isRemoved() && 
                          (fp != null && fp.getCqCount() > 0)) {
                        
                        @Retained @Released Object oldValue = oldRe.getValueInVM(owner); // OFFHEAP EntryEventImpl oldValue
                        
                        // this will not fault in the value.
                        try {
                        if (oldValue == Token.NOT_AVAILABLE){
                          event.setOldValue(oldRe.getValueOnDiskOrBuffer(owner));
                        } else {
                          event.setOldValue(oldValue);
                        }
                        } finally {
                          OffHeapHelper.release(oldValue);
                        }
                      }
                      boolean isCreate = false;
                      try {
                        if (oldRe.isRemoved()) {
                          processVersionTag(oldRe, event);
                          event.putNewEntry(owner, oldRe);
                          EntryLogger.logInvalidate(event);
                          owner.recordEvent(event);
                          if (!oldRe.isTombstone()) {
                            owner.updateSizeOnPut(event.getKey(), oldSize, event.getNewValueBucketSize());
                          } else {
                            owner.updateSizeOnCreate(event.getKey(), event.getNewValueBucketSize());
                            isCreate = true;
                          }
                        } else {
                          processVersionTag(oldRe, event);
                          event.putExistingEntry(owner, oldRe, oldSize);
                          EntryLogger.logInvalidate(event);
                          owner.recordEvent(event);
                          owner.updateSizeOnPut(event.getKey(), oldSize, event.getNewValueBucketSize());
                        }
                      }
                      catch (RegionClearedException e) {
                        // generate versionTag for the event
                        EntryLogger.logInvalidate(event);
                        owner.recordEvent(event);
                        clearOccured = true;
                      }
                      owner.basicInvalidatePart2(oldRe, event,
                          clearOccured /* conflict with clear */, invokeCallbacks);
                      if (!clearOccured) {
                        if (isCreate) {
                          lruEntryCreate(oldRe);
                        } else {
                          lruEntryUpdate(oldRe);
                        }
                      }                   
                      didInvalidate = true;
                      invalidatedRe = oldRe;
                    }
                  }
                } // synchronized oldRe
              } // while oldRe exists
              
              if (!opCompleted) {
                if (forceNewEntry && event.isFromServer()) {
                  // don't invoke listeners - we didn't force new entries for
                  // CCU invalidations before 7.0, and listeners don't care
                  event.inhibitCacheListenerNotification(true);
                }
                event.setRegionEntry(newRe);
                owner.cacheWriteBeforeInvalidate(event, invokeCallbacks, forceNewEntry);
                if (!forceNewEntry && event.noVersionReceivedFromServer()) {
                  // server did not perform the invalidation, so don't leave an invalid
                  // entry here
                  return false;
                }
                try {
                  if (!owner.isInitialized() && owner.getDataPolicy().withReplication()) {
                    final int oldSize = owner.calculateRegionEntryValueSize(newRe);
                    invalidateEntry(event, newRe, oldSize);
                  }
                  else {
                    invalidateNewEntry(event, owner, newRe);
                  }
                }
                catch (RegionClearedException e) {
                  // TODO: deltaGII: do we even need RegionClearedException?
                  // generate versionTag for the event
                  owner.recordEvent(event);
                  clearOccured = true;
                }
                owner.basicInvalidatePart2(newRe, event, clearOccured /*conflict with clear*/, invokeCallbacks);
                if (!clearOccured) {
                  lruEntryCreate(newRe);
                  incEntryCount(1);
                }            
                opCompleted = true;
                didInvalidate = true;
                invalidatedRe = newRe;
                // Don't leave an entry in the cache, if we
                // just wanted to force the distribution and events
                // for this invalidate
                if (!forceNewEntry) {
                  removeEntry(event.getKey(), newRe, false);
                } 
              } // !opCompleted
            } catch (ConcurrentCacheModificationException ccme) {
              VersionTag tag = event.getVersionTag();
              if (tag != null && tag.isTimeStampUpdated()) {
                // Notify gateways of new time-stamp.
                owner.notifyTimestampsToGateways(event);
              }
              throw ccme;
            } finally {
              if (!opCompleted) {
                removeEntry(event.getKey(), newRe, false);
              }
            }
          } // synchronized newRe
      } // forceNewEntry
      else { // !forceNewEntry
        boolean retry = true;
        // RegionEntry retryEntry = null;
        // int retries = -1;
        
      RETRY_LOOP:
        while (retry) {
          retry = false;
          /* this is useful for debugging if you get a hot thread
          retries++;
          if (retries > 0) {
            owner.getCachePerfStats().incRetries();
            if (retries == 1000000) {
              owner.getCache().getLoggerI18n().warning(
                LocalizedStrings.AbstractRegionMap_RETRIED_1_MILLION_TIMES_FOR_ENTRY_TO_GO_AWAY_0, retryEntry, retryEntry.removeTrace);
            }
          }
          */
          boolean entryExisted = false;
          RegionEntry re = getEntry(event);
          RegionEntry tombstone = null;
          boolean haveTombstone = false;
          /* this test fails when an invalidate(k,v) doesn't leave an entry in the cache:
                  parReg/bridge/serialParRegHABridge.conf
                  bridgeHosts=5
                  bridgeThreadsPerVM=1
                  bridgeVMsPerHost=1
                  edgeHosts=4
                  edgeThreadsPerVM=1
                  edgeVMsPerHost=1
                  numAccessors=1
                  numEmptyClients=1
                  numThinClients=1
                  numVMsToStop=2
                  redundantCopies=3
                  hydra.Prms-randomSeed=1328320674613;
           */
          if (re != null && re.isTombstone()) {
            tombstone = re;
            haveTombstone = true;
            re = null;
          }
          if (re == null) {
            if (!owner.isInitialized()) {
              // when GII message arrived or processed later than invalidate
              // message, the entry should be created as placeholder
              RegionEntry newRe = haveTombstone? tombstone : getEntryFactory().createEntry(owner, event.getKey(),
                  Token.INVALID);
              synchronized (newRe) {
                if (haveTombstone && !tombstone.isTombstone()) {
                  // state of the tombstone has changed so we need to retry
                  retry = true;
                  //retryEntry = tombstone; // leave this in place for debugging
                  continue RETRY_LOOP;
                }
                re = putEntryIfAbsent(event.getKey(), newRe);
                if (re == tombstone) {
                  re = null; // pretend we don't have an entry
                }
              }
            } else if (owner.getServerProxy() != null) {
              Object sync = haveTombstone? tombstone : new Object();
              synchronized(sync) {
                if (haveTombstone && !tombstone.isTombstone()) { 
                  // bug 45295: state of the tombstone has changed so we need to retry
                  retry = true;
                  //retryEntry = tombstone; // leave this in place for debugging
                  continue RETRY_LOOP;
                }
       
                // bug #43287 - send event to server even if it's not in the client (LRU may have evicted it)
                owner.cacheWriteBeforeInvalidate(event, true, false);
                if (owner.concurrencyChecksEnabled) {
                  if (event.getVersionTag() == null) {
                    // server did not perform the invalidation, so don't leave an invalid
                    // entry here
                    return false;
                  } else if (tombstone != null) {
                    processVersionTag(tombstone, event);
                    try {
                      if (!tombstone.isTombstone()) {
                        owner.getLogWriterI18n().warning(LocalizedStrings.DEBUG, "tombstone is no longer a tombstone. "+tombstone+":event="+event);
                      }
                      tombstone.setValue(owner, Token.TOMBSTONE);
                    } catch (RegionClearedException e) {
                      // that's okay - when writing a tombstone into a disk, the
                      // region has been cleared (including this tombstone)
                    } catch (ConcurrentCacheModificationException ccme) {
                      VersionTag tag = event.getVersionTag();
                      if (tag != null && tag.isTimeStampUpdated()) {
                        // Notify gateways of new time-stamp.
                        owner.notifyTimestampsToGateways(event);
                      }
                      throw ccme;
                    }
                    // update the tombstone's version to prevent an older CCU/putAll from overwriting it
                    owner.rescheduleTombstone(tombstone, event.getVersionTag());
                  }
                }
              }
              entryExisted = true;
            }
          }
          if (re != null) {
            // Gester: Race condition in GII
            // when adding the placeholder for invalidate entry during GII,
            // if the GII got processed earlier for this entry, then do 
            // normal invalidate operation
            synchronized (re) {
              if (re.isTombstone() || (!re.isRemoved() && !re.isDestroyed())) {
                entryExisted = true;
                if (re.isInvalid()) {
                  // was already invalid, do not invoke listeners or increment
                  // stat
                  if (log.fineEnabled()) {
                    log.fine("Invalidate: Entry already invalid: '" + event.getKey()
                        + "'");
                  }
                  if (event.getVersionTag() != null && owner.getVersionVector() != null) {
                    owner.getVersionVector().recordVersion((InternalDistributedMember) event.getDistributedMember(),
                        event.getVersionTag(), event);
                  }
                }
                else { // previous value not invalid
                  event.setRegionEntry(re);
                  owner.cacheWriteBeforeInvalidate(event, invokeCallbacks, forceNewEntry);
                  if (owner.concurrencyChecksEnabled && event.noVersionReceivedFromServer()) {
                    // server did not perform the invalidation, so don't leave an invalid
                    // entry here
                    if (log.fineEnabled()) {
                      log.fine("returning early because server did not generate a version stamp for this event:" + event);
                    }
                    return false;
                  }
             // in case of overflow to disk we need the old value for cqs.
                  if(owner.getFilterProfile().getCqCount() > 0){
                    //use to be getValue and can cause dead lock rdubey.
                    if (re.isValueNull()) {
                      event.setOldValue(re.getValueOnDiskOrBuffer(owner));
                    } else {
                      
                      @Retained @Released Object v = re.getValueInVM(owner);
                      
                      try {
                        event.setOldValue(v); // OFFHEAP escapes to EntryEventImpl oldValue
                      } finally {
                        OffHeapHelper.release(v);
                      }
                    }
                  }
                  final boolean oldWasTombstone = re.isTombstone();
                  final int oldSize = _getOwner().calculateRegionEntryValueSize(re);
                  try {
                    invalidateEntry(event, re, oldSize);
                  }
                  catch (RegionClearedException rce) {
                    // generate versionTag for the event
                    EntryLogger.logInvalidate(event);
                    _getOwner().recordEvent(event);
                    clearOccured = true;
                  } catch (ConcurrentCacheModificationException ccme) {
                    VersionTag tag = event.getVersionTag();
                    if (tag != null && tag.isTimeStampUpdated()) {
                      // Notify gateways of new time-stamp.
                      owner.notifyTimestampsToGateways(event);
                    }
                    throw ccme;
                  }
                  owner.basicInvalidatePart2(re, event,
                      clearOccured /* conflict with clear */, invokeCallbacks);
                  if (!clearOccured) {
                    if (oldWasTombstone) {
                      lruEntryCreate(re);
                    } else {
                      lruEntryUpdate(re);
                    }
                  }             
                  didInvalidate = true;
                  invalidatedRe = re;
                } // previous value not invalid
              }
            } // synchronized re
          } // re != null
          else {
            // At this point, either it's not in GII mode, or the placeholder
            // is in region, do nothing
          }
          if (!entryExisted) {
            owner.checkEntryNotFound(event.getKey());
          }
        } // while(retry)
      } // !forceNewEntry
    } catch( DiskAccessException dae) {
      invalidatedRe = null;
      didInvalidate = false;
      this._getOwner().handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
      releaseCacheModificationLock(owner, event);
      if (owner.getIndexManager() != null) {
        owner.getIndexManager().countDownIndexUpdaters();
      }
      if (invalidatedRe != null) {
        owner.basicInvalidatePart3(invalidatedRe, event, invokeCallbacks);
      }
      if (didInvalidate && !clearOccured) {
        try {
          lruUpdateCallback();
        } catch( DiskAccessException dae) {
          this._getOwner().handleDiskAccessException(dae, true/* stop bridge servers*/);
          throw dae;
        }
      }
      else if (!didInvalidate){
        resetThreadLocals();
      }
    }
    return didInvalidate;
  }

  protected void invalidateNewEntry(EntryEventImpl event,
      final LocalRegion owner, RegionEntry newRe) throws RegionClearedException {
    processVersionTag(newRe, event);
    event.putNewEntry(owner, newRe);
    owner.recordEvent(event);
    owner.updateSizeOnCreate(event.getKey(), event.getNewValueBucketSize());
  }

  protected void invalidateEntry(EntryEventImpl event, RegionEntry re,
      int oldSize) throws RegionClearedException {
    processVersionTag(re, event);
    event.putExistingEntry(_getOwner(), re, oldSize);
    EntryLogger.logInvalidate(event);
    _getOwner().recordEvent(event);
    _getOwner().updateSizeOnPut(event.getKey(), oldSize, event.getNewValueBucketSize());
  }

  
  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.RegionMap#updateEntryVersion(com.gemstone.gemfire.internal.cache.EntryEventImpl)
   */
  @Override
  public void updateEntryVersion(EntryEventImpl event) throws EntryNotFoundException {

    final LocalRegion owner = _getOwner();
    if (owner == null) {
      // "fix" for bug 32440
      Assert.assertTrue(false, "The owner for RegionMap " + this
          + " is null for event " + event);

    }
    final LogWriterI18n log = owner.getCache().getLoggerI18n();
    
    DiskRegion dr = owner.getDiskRegion();
    if (dr != null) {
      dr.setClearCountReference();
    }

    lockForCacheModification(owner, event);

    try {
      RegionEntry re = getEntry(event.getKey());

      boolean entryExisted = false;

      if (re != null) {
        // process version tag
        synchronized (re) {

          try {
            if (re.isTombstone()
                || (!re.isRemoved() && !re.isDestroyed())) {
              entryExisted = true;
            }
            processVersionTag(re, event);
            owner.generateAndSetVersionTag(event, re);
            EntryLogger.logUpdateEntryVersion(event);
            _getOwner().recordEvent(event);
          } catch (ConcurrentCacheModificationException ccme) {
            // Do nothing.
          }

        }
      }
      if (!entryExisted) {
        owner.checkEntryNotFound(event.getKey());
      }
    }  catch( DiskAccessException dae) {
      this._getOwner().handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
      releaseCacheModificationLock(owner, event);
      if (dr != null) {
        dr.removeClearCountReference();
      }
    }
  }

  @Override
  public final void txApplyInvalidate(final RegionEntry re,
      final TXStateInterface txState, Object key, Object newValue,
      boolean didDestroy, boolean localOp, EventID eventId,
      Object aCallbackArgument, List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent) {
    // boolean didInvalidate = false;
    final LocalRegion owner = _getOwner();
    boolean cbEventInPending = false;
    //boolean forceNewEntry = !owner.isInitialized() && owner.isAllEvents();

    //final boolean hasRemoteOrigin = !txState.isCoordinator();
    //DiskRegion dr = owner.getDiskRegion();
    // Fix for Bug #44431. We do NOT want to update the region and wait
    // later for index INIT as region.clear() can cause inconsistency if
    // happened in parallel as it also does index INIT.
    if (owner.getIndexManager() != null) {
      owner.getIndexManager().waitForIndexInit();
    }
    try {

      // check that entry lock has already been upgraded to EX mode
      assert re.hasExclusiveLock(null, null):
        "unexpected absence of EX lock during txApply";

      // TODO: TX: trunk code for GII; see if it can be useful
      /*
      if (forceNewEntry) {
        boolean opCompleted = false;
        RegionEntry newRe = getEntryFactory().createEntry(owner, key,
            Token.REMOVED_PHASE1);
          synchronized (newRe) {
            try {
              RegionEntry oldRe = putEntryIfAbsent(key, newRe);
              while (!opCompleted && oldRe != null) {
                synchronized (oldRe) {
                  if (oldRe.isRemovedPhase2()) {
                    oldRe = putEntryIfAbsent(key, newRe);
                    if (oldRe != null) {
                      owner.getCachePerfStats().incRetries();
                    }
                  }
                  else {
                    opCompleted = true;
                    final boolean oldWasTombstone = oldRe.isTombstone();
                    final int oldSize = owner.calculateRegionEntryValueSize(oldRe);
                    Object oldValue = oldRe.getValueInVM(owner); // OFFHEAP eei
                    // Create an entry event only if the calling context is
                    // a receipt of a TXCommitMessage AND there are callbacks
                    // installed
                    // for this region
                    boolean invokeCallbacks = shouldCreateCBEvent(owner, true, owner.isInitialized());
                    boolean cbEventInPending = false;
                    cbEvent = createCBEvent(owner, 
                        localOp ? Operation.LOCAL_INVALIDATE : Operation.INVALIDATE,
                        key, newValue, txId, txEvent, eventId, aCallbackArgument, filterRoutingInfo, bridgeContext, txEntryState, versionTag, tailKey);
                    try {
                    cbEvent.setRegionEntry(oldRe);
                    cbEvent.setOldValue(oldValue);
                    if (owner.getLogWriterI18n().fineEnabled()) {
                      owner.getLogWriterI18n().fine("txApplyInvalidate cbEvent=" + cbEvent);
                    }

                    txRemoveOldIndexEntry(Operation.INVALIDATE, oldRe);
                    if (didDestroy) {
                      oldRe.txDidDestroy(owner.cacheTimeMillis());
                    }
                    if (txEvent != null) {
                      txEvent.addInvalidate(owner, oldRe, oldRe.getKey(),
                          newValue,aCallbackArgument);
                    }
                    oldRe.setValueResultOfSearch(false);
                    processAndGenerateTXVersionTag(owner, cbEvent, oldRe, txEntryState);
                    boolean clearOccured = false;
                    try {
                      oldRe.setValue(owner, prepareValueForCache(owner, newValue));
                      EntryLogger.logTXInvalidate(_getOwnerObject(), key);
                      owner.updateSizeOnPut(key, oldSize, 0);
                      if (oldWasTombstone) {
                        owner.unscheduleTombstone(oldRe);
                      }
                    }
                    catch (RegionClearedException rce) {
                      clearOccured = true;
                    }
                    owner.txApplyInvalidatePart2(oldRe, oldRe.getKey(),
                        didDestroy, true, clearOccured);
  //                  didInvalidate = true;
                    if (invokeCallbacks) {
                      switchEventOwnerAndOriginRemote(cbEvent, hasRemoteOrigin);
                      if(pendingCallbacks==null) {
                        owner.invokeTXCallbacks(
                            EnumListenerEvent.AFTER_INVALIDATE, cbEvent,
                            true/*callDispatchListenerEvent*);
                      } else {
                        pendingCallbacks.add(cbEvent);
                        cbEventInPending = true;
                      }
                    }
                    if (!clearOccured) {
                      lruEntryUpdate(oldRe);
                    }
                    if (shouldPerformConcurrencyChecks(owner, cbEvent) && txEntryState != null) {
                      txEntryState.setVersionTag(cbEvent.getVersionTag());
                    }
                    } finally {
                      if (!cbEventInPending) cbEvent.freeOffHeapReferences();
                    }
                  }
                }
              }
              if (!opCompleted) {
                boolean invokeCallbacks = shouldCreateCBEvent( owner, true /* isInvalidate *, owner.isInitialized());
                boolean cbEventInPending = false;
                cbEvent = createCBEvent(owner, 
                    localOp ? Operation.LOCAL_INVALIDATE : Operation.INVALIDATE,
                        key, newValue, txId, txEvent, eventId, aCallbackArgument, filterRoutingInfo, bridgeContext, txEntryState, versionTag, tailKey);
                try {
                cbEvent.setRegionEntry(newRe);
                txRemoveOldIndexEntry(Operation.INVALIDATE, newRe);
                newRe.setValueResultOfSearch(false);
                boolean clearOccured = false;
                try {
                  processAndGenerateTXVersionTag(owner, cbEvent, newRe, txEntryState);
                  newRe.setValue(owner, prepareValueForCache(owner, newValue));
                  EntryLogger.logTXInvalidate(_getOwnerObject(), key);
                  owner.updateSizeOnCreate(newRe.getKey(), 0);//we are putting in a new invalidated entry
                }
                catch (RegionClearedException rce) {
                  clearOccured = true;
                }
                owner.txApplyInvalidatePart2(newRe, newRe.getKey(), didDestroy,
                    true, clearOccured);

                if (invokeCallbacks) {
                  switchEventOwnerAndOriginRemote(cbEvent, hasRemoteOrigin);
                  if(pendingCallbacks==null) {
                    owner.invokeTXCallbacks(
                        EnumListenerEvent.AFTER_INVALIDATE, cbEvent,
                        true/*callDispatchListenerEvent*);
                  } else {
                    pendingCallbacks.add(cbEvent);
                    cbEventInPending = true;
                  }
                }
                opCompleted = true;
                if (!clearOccured) {
                  lruEntryCreate(newRe);
                  incEntryCount(1);
                }
                if (shouldPerformConcurrencyChecks(owner, cbEvent) && txEntryState != null) {
                  txEntryState.setVersionTag(cbEvent.getVersionTag());
                }
                } finally {
                  if (!cbEventInPending) cbEvent.freeOffHeapReferences();
                }
              }
            }
            finally {
              if (!opCompleted) {
                removeEntry(key, newRe, false);
              }
            }
          }
      }
      else { /* !forceNewEntry *
        RegionEntry re = getEntry(key);
        if (re != null) {
            synchronized (re) {
              if (re.isRemoved()) {
                return;
              }
              else {
                final int oldSize = owner.calculateRegionEntryValueSize(re);
                boolean wasTombstone = re.isTombstone();
                Object oldValue = re.getValueInVM(owner); // OFFHEAP eei
                // Create an entry event only if the calling context is
                // a receipt of a TXCommitMessage AND there are callbacks
                // installed
                // for this region
                boolean invokeCallbacks = shouldCreateCBEvent(owner, true, owner.isInitialized());
                boolean cbEventInPending = false;
                cbEvent = createCBEvent(owner, 
                    localOp ? Operation.LOCAL_INVALIDATE : Operation.INVALIDATE, 
                        key, newValue, txId, txEvent, eventId, aCallbackArgument, filterRoutingInfo, bridgeContext, txEntryState, versionTag, tailKey);
                try {
                cbEvent.setRegionEntry(re);
                cbEvent.setOldValue(oldValue);
                txRemoveOldIndexEntry(Operation.INVALIDATE, re);
                if (didDestroy) {
                  re.txDidDestroy(owner.cacheTimeMillis());
                }
                if (txEvent != null) {
                  txEvent.addInvalidate(owner, re, re.getKey(), newValue,aCallbackArgument);
                }
                re.setValueResultOfSearch(false);
                processAndGenerateTXVersionTag(owner, cbEvent, re, txEntryState);
                boolean clearOccured = false;
                try {
                  re.setValue(owner, prepareValueForCache(owner, newValue));
                  EntryLogger.logTXInvalidate(_getOwnerObject(), key);
                  if (wasTombstone) {
                    owner.unscheduleTombstone(re);
                  }
                  owner.updateSizeOnPut(key, oldSize, 0);
                }
                catch (RegionClearedException rce) {
                  clearOccured = true;
                }
                owner.txApplyInvalidatePart2(re, re.getKey(), didDestroy, true,
                    clearOccured);
  //              didInvalidate = true;
                if (invokeCallbacks) {
                  switchEventOwnerAndOriginRemote(cbEvent, hasRemoteOrigin);
                  if(pendingCallbacks==null) {
                    owner.invokeTXCallbacks(
                        EnumListenerEvent.AFTER_INVALIDATE, cbEvent,
                        true/*callDispatchListenerEvent*);
                  } else {
                    pendingCallbacks.add(cbEvent);
                    cbEventInPending = true;
                  }
                }
                if (!clearOccured) {
                  lruEntryUpdate(re);
                }
                if (shouldPerformConcurrencyChecks(owner, cbEvent) && txEntryState != null) {
                  txEntryState.setVersionTag(cbEvent.getVersionTag());
                }
                } finally {
                  if (!cbEventInPending) cbEvent.freeOffHeapReferences();
                }
              }
            }
        } else  { //re == null
          // Fix bug#43594
          // In cases where bucket region is re-created, it may so happen 
          // that the invalidate is already applied on the Initial image 
          // provider, thus causing region entry to be absent. 
          // Notify clients with client events.
          boolean cbEventInPending = false;
          cbEvent = createCBEvent(owner, 
              localOp ? Operation.LOCAL_INVALIDATE : Operation.INVALIDATE, 
                  key, newValue, txId, txEvent, eventId, aCallbackArgument, 
                  filterRoutingInfo, bridgeContext, txEntryState, versionTag, tailKey);
          try {
          switchEventOwnerAndOriginRemote(cbEvent, hasRemoteOrigin);
          if (pendingCallbacks == null) {
            owner.invokeTXCallbacks(EnumListenerEvent.AFTER_INVALIDATE,
                cbEvent, false);
          } else {
            pendingCallbacks.add(cbEvent);
            cbEventInPending = true;
          }
          } finally {
            if (!cbEventInPending) cbEvent.freeOffHeapReferences();
          }
        }
      }
      */
      if (re.isDestroyedOrRemoved()) {
        return;
      }
      else {
        // TODO: merge: isn't this better done once before commit?
        //Map<AbstractIndex, Long> lockedIndexes = owner
        //    .acquireWriteLocksOnCompactRangeIndexes();
        //try {
        final long lastMod = cbEvent.getEventTime(0L, owner);
        final int oldSize = owner.calculateRegionEntryValueSize(re);
        // Create an entry event only if the calling context is
        // a receipt of a TXCommitMessage AND there are callbacks
        // installed for this region
        if (shouldCreateCBEvent(owner, true /* isInvalidate */,
            owner.isInitialized())) {
          // create new EntryEventImpl in case pendingCallbacks is non-null
          if (pendingCallbacks != null) {
            cbEvent = new EntryEventImpl();
            cbEvent.setEntryLastModified(lastMod);
          }
          cbEvent = createCBEvent(owner, localOp ? Operation.LOCAL_INVALIDATE
              : Operation.INVALIDATE, key, newValue, txState, eventId,
              aCallbackArgument, filterRoutingInfo, bridgeContext,
              versionTag, tailKey, cbEvent);
          cbEvent.setRegionEntry(re);
          
          @Retained @Released Object oldValue = re.getValueInVM(owner); // OFFHEAP eei
          
          try {
          cbEvent.setOldValue(oldValue);
          } finally {
            OffHeapHelper.release(oldValue);
          }
        }
        else {
          cbEvent = null;
        }
        txRemoveOldIndexEntry(Operation.INVALIDATE, re);
        if (didDestroy) {
          re.txDidDestroy(lastMod);
        }
        /*
        if (txEvent != null) {
          txEvent.addInvalidate(owner, re, re.getKey(), newValue,
              aCallbackArgument);
        }
        re.setValueResultOfSearch(false);
        */
        boolean clearOccured = false;
        try {
          processAndGenerateTXVersionTag(owner, cbEvent, re, txr);
          re.setValue(owner, re.prepareValueForCache(owner, newValue, true, true));
          if (EntryLogger.isEnabled()) {
            EntryLogger.logTXInvalidate(_getOwnerObject(), key);
          }
          owner.updateSizeOnPut(key, oldSize,
              owner.calculateRegionEntryValueSize(re));
        } catch (RegionClearedException rce) {
          clearOccured = true;
        }
        owner.txApplyInvalidatePart2(re, key, didDestroy, true, clearOccured);
        // didInvalidate = true;
        if (cbEvent != null) {
          if (pendingCallbacks == null) {
            owner.invokeTXCallbacks(EnumListenerEvent.AFTER_INVALIDATE, cbEvent,
                true/*callDispatchListenerEvent*/, true /*notifyGateway*/);
          }
          else {
            pendingCallbacks.add(cbEvent);
            cbEventInPending = true;
          }
        }
        if (!clearOccured) {
          lruEntryUpdate(re);
        }
        //} finally {
        //  owner.releaseAcquiredWriteLocksOnIndexes(lockedIndexes);
        //}
      }
    } catch (DiskAccessException dae) {
      owner.handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
      if (owner.getIndexManager() != null) {
        owner.getIndexManager().countDownIndexUpdaters();
      }
      if (!cbEventInPending && cbEvent != null) cbEvent.release();
    }
  }

  /**
   * This code may not be correct. It was added quickly to help customer's PR persistence to
   * not consume as much memory.
   */
  public void evictValue(Object key) {
    final LocalRegion owner = _getOwner();
    RegionEntry re = getEntry(key);
    if (re != null) {
      synchronized (re) {
        if (!re.isValueNull()) {
          re.setValueToNull(owner);
          owner.getDiskRegion().incNumEntriesInVM(-1L);
          owner.getDiskRegion().incNumOverflowOnDisk(1L);
          if(owner instanceof BucketRegion)
          {
            ((BucketRegion)owner).incNumEntriesInVM(-1L);
            ((BucketRegion)owner).incNumOverflowOnDisk(1L);
          }
        }
      }
    }
  }

  private RegionEntry getOrCreateRegionEntry(Object ownerRegion,
      EntryEventImpl event, Object value,
      MapCallbackAdapter<Object, Object, Object, Object> valueCreator,
      boolean onlyExisting, boolean returnTombstone) {
    Object key = event.getKey();
    RegionEntry retVal = null;
    if (event.isFetchFromHDFS()) {
      retVal = getEntry(event);
    } else {
      retVal = getEntryInVM(key);
    }
    if (onlyExisting) {
      if (!returnTombstone && (retVal != null && retVal.isTombstone())) {
        return null;
      }
      return retVal;
    }
    if (retVal != null) {
      return retVal;
    }
    if (valueCreator != null) {
      value = valueCreator.newValue(key, ownerRegion, value, null);
    }
    retVal = getEntryFactory().createEntry((RegionEntryContext) ownerRegion, key, value);
    RegionEntry oldRe = putEntryIfAbsent(key, retVal);
    if (oldRe != null) {
      if (retVal.isOffHeap()) {
        ((OffHeapRegionEntry) retVal).release();
      }
      return oldRe;
    }
    return retVal;
  }

  protected static final MapCallbackAdapter<Object, Object, Object, Object>
      listOfDeltasCreator = new MapCallbackAdapter<Object, Object,
          Object, Object>() {
    @Override
    public Object newValue(Object key, Object context, Object createParams,
        final MapResult result) {
      return new ListOfDeltas(4);
    }
  };
  
  /**
   * Neeraj: The below if block is to handle the special
   * scenario witnessed in GemFireXD for now. (Though its
   * a general scenario). The scenario is that the updates start coming 
   * before the base value reaches through GII. In that scenario the updates
   * essentially the deltas are added to a list and kept as oldValue in the
   * map and this method returns. When through GII the actual base value arrives
   * these updates or deltas are applied on it and the new value thus got is put
   * in the map.
   * @param event 
   * @param ifOld 
   * @param indexManager
   * @return true if delta was enqued
   */
  private boolean enqueDelta(EntryEventImpl event, boolean ifOld,
      IndexUpdater indexManager) {
    LocalRegion owner = _getOwner();
    final LogWriterI18n log = owner.getLogWriterI18n();
    if (indexManager != null && !owner.isInitialized() && event.hasDelta()
        && EntryEventImpl.SUSPECT_TOKEN != event.getContextObject()) {
      event.setContextObject(null);
      boolean isOldValueDelta = true;
      {
        final Delta delta = event.getDeltaNewValue();
        RegionEntry re = getOrCreateRegionEntry(owner, event, null,
            listOfDeltasCreator, false, false);
        assert re != null;
        synchronized (re) {
          // Asif:Entry exists .check if it is disk recovered entry.
          // Then delta should replace the old value without applying
          // on it. Also avoid writing the delta or list of delta to be
          // written on disk. Fixes #42440
          boolean isRecovered = false;
          // log.convertToLogWriter().info("enqueDelta: isRecovered =  " + isRecovered);
          DiskRegion dr = owner.getDiskRegion();
          if (dr != null) {
            isRecovered = dr.testIsRecovered(re, true);
          }
          
          @Retained @Released Object oVal = re.getValueOffHeapOrDiskWithoutFaultIn(owner);
          
          try {
            if (!isRecovered && (oVal != null)) {
              if (oVal instanceof ListOfDeltas) {
                if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
                  log.info(LocalizedStrings.DEBUG, "enqueDelta: adding delta to list of deltas: " + delta
                      + " for re = " + re + " oldVal = " + oVal);
                }
                delta.setVersionTag(event.getVersionTag());
                ((ListOfDeltas)oVal).merge(owner, delta);
                @Retained Object newVal = ((AbstractRegionEntry)re).prepareValueForCache(
                    owner, oVal, true, false);
                ((AbstractRegionEntry)re)._setValue(owner, newVal); // TODO:KIRK:48068
                                                             // prevent orphan
              }
              else {
                isOldValueDelta = false;
              }
            }
            else {
              if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
                log.info(LocalizedStrings.DEBUG, "enqueDelta: new list of deltas with delta: " + delta
                    + "for re =" + re + " oldVal = " + oVal);
              }
              if (oVal != null && isRecovered) {
                EntryEventImpl ev = EntryEventImpl.create(owner,
                    Operation.DESTROY, event.getKey(), null,

                    false /* indicate that GII is in progress */, false, null);
                try {              
                ev.setOldValue(oVal,true);
                boolean success = false;
                try {
                  indexManager.onEvent(owner, ev, re);
                  success = true;
                } finally {
                  indexManager.postEvent(owner, ev, re, success);
                }
                
                delta.setVersionTag(event.getVersionTag());
                // To avoid writing delta to disk directly set the value to
                // delta
                // TODO no need to call AbstractRegionMap.prepareValueForCache
                // here?
                @Retained Object newVal = ((AbstractRegionEntry)re).prepareValueForCache(owner,
                    new ListOfDeltas(delta), true, false);
                ((AbstractRegionEntry)re)._setValue(owner, newVal); // TODO:KIRK:48068
                                                             // prevent orphan
                ImageState imageState = owner.getImageState();
                if (imageState != null) {
                  imageState.addDeltaEntry(re.getKey());
                }
                } finally {
                  ev.release();
                }
              }
            }
          } finally {
            OffHeapHelper.release(oVal);
          }
        }
      }
      if (isOldValueDelta) {
        //log.convertToLogWriter().info("enqueDelta: returning true ");
        return true;
      }//
    }
    //log.convertToLogWriter().info("enqueDelta: returning false ");
    return false;
  }

  @SuppressWarnings("serial")
  static final class SuspectEntryList extends ArrayList<EntryEventImpl>
      implements HashEntry<Object, SuspectEntryList> {

    private final Object key;
    private int hash;
    private HashEntry<Object, SuspectEntryList> next;

    SuspectEntryList(Object key) {
      super(4);
      this.key = key;
    }

    @Override
    public Object getKey() {
      return this.key;
    }

    @Override
    public Object getKeyCopy() {
      return this.key;
    }

    @Override
    public boolean isKeyEqual(Object k) {
      return k.equals(getKey());
    }

    @Override
    public SuspectEntryList getMapValue() {
      return this;
    }

    @Override
    public void setMapValue(SuspectEntryList newValue) {
      super.clear();
      super.addAll(newValue);
    }

    @Override
    public int getEntryHash() {
      return this.hash;
    }

    @Override
    public HashEntry<Object, SuspectEntryList> getNextEntry() {
      return this.next;
    }

    @Override
    public void setNextEntry(HashEntry<Object, SuspectEntryList> n) {
      this.next = n;
    }
  }

  private static final HashEntryCreator<Object, SuspectEntryList>
      suspectEntryCreator = new HashEntryCreator<Object,
          SuspectEntryList>() {

    @Override
    public HashEntry<Object, SuspectEntryList> newEntry(Object key, int hash,
        HashEntry<Object, SuspectEntryList> next, SuspectEntryList entry) {
      entry.hash = hash;
      entry.next = next;
      return entry;
    }

    @Override
    public int keyHashCode(Object key, boolean compareValues) {
      return CustomEntryConcurrentHashMap.keyHash(key, compareValues);
    }    
  };

  private static final MapCallbackAdapter<Object, SuspectEntryList,
      LogWriterI18n, EntryEventImpl> suspectValueCreator =
          new MapCallbackAdapter<Object, SuspectEntryList,
              LogWriterI18n, EntryEventImpl>() {

    @Override
    public SuspectEntryList newValue(Object key, LogWriterI18n log,
        EntryEventImpl event, final MapResult result) {
      SuspectEntryList list = new SuspectEntryList(key);
      addSuspectAndSetEventState(key, list, event, log);
      return list;
    }

    @Override
    public SuspectEntryList updateValue(Object key,
        SuspectEntryList currentList, LogWriterI18n log, EntryEventImpl event) {
      addSuspectAndSetEventState(key, currentList, event, log);
      return currentList;
    }

    @Override
    public boolean requiresUpdateValue() {
      return true;
    }
  };

  private static final MapCallbackAdapter<Object, SuspectEntryList,
      LogWriterI18n, EntryEventImpl> suspectValueProcess =
          new MapCallbackAdapter<Object, SuspectEntryList,
              LogWriterI18n, EntryEventImpl>() {

    @Override
    public SuspectEntryList newValue(Object key, LogWriterI18n log,
        EntryEventImpl event, final MapResult result) {
      // Base value has come. So let the normal flow happen.
      return null;
    }

    @Override
    public SuspectEntryList updateValue(Object key,
        SuspectEntryList currentList, LogWriterI18n log, EntryEventImpl event) {
      // Base value has come. So try to apply all suspect including this one.
      addSuspectAndSetEventState(key, currentList, event, log);
      // return null to indicate that current value should be removed map
      // since applySuspect has been done
      // the applySuspect calls are done by caller outside the lock on
      // the returned currentList which is safe since that list has been
      // removed from map and so there are no concurrency concerns
      return null;
    }

    @Override
    public boolean requiresUpdateValue() {
      return true;
    }
  };

  private final CustomEntryConcurrentHashMap<Object, SuspectEntryList>
      suspectEntries = new CustomEntryConcurrentHashMap<Object,
          SuspectEntryList>(100, 0.75f, 8, false, suspectEntryCreator);
  private final NonReentrantReadWriteLock suspectEntriesLock =
      new NonReentrantReadWriteLock();
  /**
   * Indicates whether applyAllSuspects finished. 
   */
  private volatile boolean applyAllSuspectsFinished = false;

  /**
   * 
   */
  private LinkedHashSet<Object> listForKeyOrder = new LinkedHashSet<Object>(); 
  /**
   * Enqueue events for later processing when the node is coming up so that
   * operations which would have otherwise failed if the region was initialized
   * completely, without this later processing might sneak in and sit in this
   * map for ever.
   * 
   * @param event
   * @param lastModified
   * @param ifOld
   * @param indexManager
   * @return true if suspect was enqued
   */
  private boolean enqueSuspect(EntryEventImpl event, long lastModified,
      boolean ifOld, IndexUpdater indexManager) {
    LocalRegion owner = _getOwner();
    final LogWriterI18n log = owner.getLogWriterI18n();
    if (indexManager != null && indexManager.handleSuspectEvents()
        && !owner.isInitialized()) {
      Object key = event.getKey();
      RegionEntry re = getEntry(key);

      this.suspectEntriesLock.attemptReadLock(-1);
      // This takes care of the window in which applyAllSuspects() has
      // finished but the region has not been marked as initialized yet. In
      // that case, don't enqueue.
      if (owner.isInitialized() || this.applyAllSuspectsFinished) {
        this.suspectEntriesLock.releaseReadLock();
        return false;
      }
      EntryEventImpl psEvent = event;
      boolean finishedEnqueue = true;

      try {
        if (owner.enableOffHeapMemory) {
          // Make a copy that has its own off-heap refcount so fix bug 48837
          psEvent = new EntryEventImpl(event);
          finishedEnqueue = false;
        }
        if (psEvent.getEntryLastModified() == 0) {
          psEvent.setEntryLastModified(lastModified);
        }
        if (re != null) {
          boolean result = processSuspect(re, psEvent, lastModified, ifOld,
              indexManager, owner, log);
          finishedEnqueue = result;
          return result;
        }
        else {
          this.suspectEntries.create(key, suspectValueCreator, log, psEvent,
              true);
          finishedEnqueue = true;
          synchronized (this.listForKeyOrder) {
            this.listForKeyOrder.add(event.key);
          }
          return true;
        }

      } finally {
        this.suspectEntriesLock.releaseReadLock();
        if (!finishedEnqueue) {
          psEvent.release();
        }
      }
    }
    return false;
  }

  private boolean processSuspect(RegionEntry re, EntryEventImpl event,
      long lastModified, boolean ifOld, IndexUpdater indexManager,
      LocalRegion owner, LogWriterI18n log) {
    boolean isRecovered = false;
    DiskRegion dr = owner.getDiskRegion();
    if (dr != null) {
      isRecovered = dr.testIsRecovered(re, false);
    }
    Object key = event.getKey();
    if (!isRecovered && re.getValueAsToken() == Token.NOT_A_TOKEN) {
      SuspectEntryList suspectList = this.suspectEntries.create(key,
          suspectValueProcess, log, event, true);
      if (suspectList != null) {
        applySuspect(key, suspectList, owner, log);
        return true;
      } else {
        return false;
      }
    }
    else {
      this.suspectEntries.create(key, suspectValueCreator, log, event, true);
      synchronized (this.listForKeyOrder) {
        this.listForKeyOrder.add(event.key);
      }
      return true;
    }
  }

  private static void addSuspectAndSetEventState(Object key,
      SuspectEntryList list, EntryEventImpl event, LogWriterI18n log) {
    if (!list.contains(event)) {
      if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
        log.info(LocalizedStrings.DEBUG, "added suspect event: " + event + " in list: "
            + System.identityHashCode(list));
      }
      event.setContextObject(EntryEventImpl.SUSPECT_TOKEN);
      list.add(event);
    }
  }

  public void applyAllSuspects(final LocalRegion owner) {
    this.suspectEntriesLock.attemptWriteLock(-1);
    try {
      while (!this.suspectEntries.isEmpty()) {
        final LogWriterI18n log = owner.getLogWriterI18n();
        if (log.infoEnabled()) {
          log.info(LocalizedStrings.DEBUG,
              "applyAllSuspects called for region: " + owner.getName());
        }
        SuspectEntryList list;
        for (Object key : this.listForKeyOrder) {
          list = this.suspectEntries.remove(key);
          applySuspect(key, list, owner, log);
        }
      }

      // test method
      owner.suspendApplyAllSuspects();

      // clear the failed events list
      owner.getImageState().clearFailedEvents();
      this.applyAllSuspectsFinished = true;
    } finally {
      this.listForKeyOrder.clear();
      this.suspectEntriesLock.releaseWriteLock();
    }
  }

  private void applySuspect(Object key, SuspectEntryList list,
      LocalRegion owner, LogWriterI18n log) {
    if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
      log.info(LocalizedStrings.DEBUG, "applySuspect: called with list " + list + " for key: " + key);
    }
    IndexUpdater indexManager = getIndexUpdater();
    assert indexManager != null && indexManager.handleSuspectEvents();

    final ImageState imgState = owner.getImageState();
    if (list != null) {
      for (EntryEventImpl ev : list) {
        try {
          // check if event is a failed one
          if (imgState.isFailedEvent(ev.getEventId())) {
            continue;
          }
          if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
            log.info(LocalizedStrings.DEBUG, "applySuspect: processing event: " + ev + " for key: "
                + key);
          }
          ev.setContextObject(EntryEventImpl.SUSPECT_TOKEN);
          basicPut(ev, ev.getEntryLastModified(), !ev.hasDeltaPut(),
              ev.hasDeltaPut(), null, false, false);
        } catch (EntryNotFoundException enfe) {
          // ignore EntryNotFoundException in applySuspect
          if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
            log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.applySuspect: ignoring ENFE for event: " + ev);
          }
        } catch (ConcurrentCacheModificationException ccme) {
          // ignore ConcurrentCacheModificationException in applySuspect
          if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
            log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.applySuspect: ignoring CCME for event: " + ev);
          }
        } catch (RuntimeException ex) {
          final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
              .getInternalProductCallbacks();
          if (sysCb != null && sysCb.isConstraintViolation(ex)) {
            // ignore
            if (log.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
              log.info(LocalizedStrings.DEBUG, "Ignoring exception: " + ex
                  + " while post applying event: " + ev
                  + " which was a potential suspect for key: " + key);
            }
          } else {
            throw ex;
          }
        } finally {
          ev.release();
        }
      }
    }
  }

  /*
   * returns null if the operation fails
   */
  public RegionEntry basicPut(EntryEventImpl event,
                                    final long lastModified,
                                    final boolean ifNew,
                                    final boolean ifOld,
                                    Object expectedOldValue, // only non-null if ifOld
                                    boolean requireOldValue,
                                    final boolean overwriteDestroyed)
  throws CacheWriterException,
        TimeoutException {
    final LocalRegion owner = _getOwner();

    boolean clearOccured = false;
    if (owner == null) {
      // "fix" for bug 32440
      Assert.assertTrue(false, "The owner for RegionMap " + this
          + " is null for event " + event);
    }
    final LogWriterI18n log = owner.getLogWriterI18n();
    if ((AbstractLRURegionMap.debug || TombstoneService.DEBUG_TOMBSTONE_COUNT)
        && !(owner instanceof HARegion)) {
      log.info(LocalizedStrings.DEBUG, "ARM.basicPut called for " + event
        + " expectedOldValue=" + expectedOldValue
        + " requireOldValue=" + requireOldValue
        + " ifNew=" + ifNew + " ifOld=" + ifOld + " initialized=" + owner.isInitialized()
        + " overwriteDestroyed=" + overwriteDestroyed);
    }
    //  final LogWriterI18n log = owner.getCache().getLogger();
    RegionEntry result = null;
    long lastModifiedTime = 0;
    // copy into local var to prevent race condition with setter
    final CacheWriter cacheWriter = owner.basicGetWriter();
    //    log.severe("AbstractRegionMap.basicPut cachewriter: " + cacheWriter);
    final boolean cacheWrite = !event.isOriginRemote() && !event.isNetSearch() && event.isGenerateCallbacks()
        && (cacheWriter != null
            || owner.hasServerProxy()
            || owner.scope.isDistributed());
    
//    log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.basicPut remote: "
//        + event.isOriginRemote());
//    log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.basicPut netsearch: "
//        + event.isNetSearch());
//    log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.basicPut distributed: "
//        + ((cacheWriter != null || owner.scope.isDistributed())));
//    log.info(LocalizedStrings.DEBUG, "AbstractRegionMap.basicPut cacheWrite: "
//        + cacheWrite);
    /*
     * For performance reason, we try to minimize object creation and do as much
     * work as we can outside of synchronization, especially getting
     * distribution advice.
     */
    final Set netWriteRecipients;
    if (cacheWrite) {
      if (cacheWriter == null && owner.scope.isDistributed()) {
        netWriteRecipients = ((DistributedRegion)owner)
            .getCacheDistributionAdvisor().adviseNetWrite();
      }
      else {
        netWriteRecipients = null;
      }
    }
    else {
      netWriteRecipients = null;
    }

    // mbid: this has been added to maintain consistency between the disk region
    // and the region map after clear() has been called. This will set the
    // reference of the diskSegmentRegion as a ThreadLocal so that if the diskRegionSegment
    // is later changed by another thread, we can do the necessary.
    boolean uninitialized = !owner.isInitialized();
    // GemFireXD Changes - BEGIN
    final IndexUpdater indexManager = getIndexUpdater();

    if (indexManager != null && uninitialized && this.isReplicatedRegion
        && EntryEventImpl.SUSPECT_TOKEN != event.getContextObject()) {
      if (enqueSuspect(event, event.getEntryLastModified(), ifOld, indexManager)) {
        return ProxyRegionMap.markerEntry;
      }
    }

    if (uninitialized) {
      // Avoid taking lock on the deltaEntriesLock in ARM.basicPut
      // when DistributedRegion.testLatch is non-null
      // because there will be a deadlock between an insert thread which will
      // wait for the initialization to get over which will not finish
      // until this testLatch is counted down from the test code
      if (DistributedRegion.testLatch == null) {
          owner.readLockEnqueueDelta();
          try {
            if (!owner.getImageState().requestedUnappliedDelta()) {
              if (enqueDelta(event, ifOld, indexManager)) {
                // owner.recordEvent(event);
                return ProxyRegionMap.markerEntry;
              }
            }
          } finally {
            owner.readUnlockEnqueueDelta();
          }
      }
    }

    boolean indexLocked = false;
    // GemFireXD Changes - END

    boolean retrieveOldValueForDelta = event.hasDelta() ||
        (event.getDeltaBytes() != null && event.getRawNewValue() == null);
    lockForCacheModification(owner, event);
    try {
      // take read lock for GFXD index initializations if required; the index
      // GII lock is for any updates that may come in while index is being
      // loaded during replay see bug #41377; this will go away once we allow
      // for indexes to be loaded completely in parallel (#40899); need to
      // take this lock before the RegionEntry lock else a deadlock can happen
      // between this thread and index loading thread that will first take the
      // corresponding write lock on the IndexUpdater
      if (indexManager != null) {
        indexLocked = indexManager.lockForIndexGII();
      }
      // Fix for Bug #44431. We do NOT want to update the region and wait
      // later for index INIT as region.clear() can cause inconsistency if
      // happened in parallel as it also does index INIT.
      if (owner.getIndexManager() != null) {
        owner.getIndexManager().waitForIndexInit();
      }

      // fix for bug #42169, replace must go to server if entry not on client
      boolean replaceOnClient = event.getOperation() == Operation.REPLACE
                && owner.getServerProxy() != null; 
        // Rather than having two different blocks for synchronizing oldRe
        // and newRe, have only one block and synchronize re
        RegionEntry re = null;
        boolean eventRecorded = false;
          boolean onlyExisting = ifOld && !replaceOnClient;
          re = getOrCreateRegionEntry(owner, event,
              Token.REMOVED_PHASE1, null, onlyExisting, false);
          if (re == null) {
            throwExceptionForGemFireXD(event);
            return null;
          }
          while (true) {
            synchronized (re) {
              // if the re goes into removed2 state, it will be removed
              // from the map. otherwise we can append an event to it
              // and change its state
              if (re.isRemovedPhase2()) {
                re = getOrCreateRegionEntry(owner, event,
                    Token.REMOVED_PHASE1, null, onlyExisting, false);
                owner.getCachePerfStats().incRetries();
                if (re == null) {
                  // this will happen when onlyExisting is true
                  throwExceptionForGemFireXD(event);
                  return null;
                }
                continue;
              } else {
                @Released Object oldValueForDelta = null;
                if (retrieveOldValueForDelta) {
                  // Old value is faulted in from disk if not found in memory.
                  oldValueForDelta = re.getValue(owner); // OFFHEAP: if we are synced on oldRe no issue since we can use ARE's ref
                }

                try {
                  
                  event.setRegionEntry(re);
                  event.setHasOldRegionEntry(!re.isRemoved());
                  // set old value in event
                  // check if the event is loaded from HDFS and bucket is secondary then don't set
                  
                  setOldValueInEvent(event, re, cacheWrite, requireOldValue);
                  if (owner.isSnapshotEnabledRegion() && re.getVersionStamp() != null ) {
                    checkConflict(owner, event, re);
                  }
                  if (!continueUpdate(re, event, ifOld, replaceOnClient)) {
                    return null;
                  }
                  // overwrite destroyed?
                  if (!continueOverwriteDestroyed(re, event, overwriteDestroyed, ifNew)) {
                    return null;
                  }
                  // check expectedOldValue
                  if (!satisfiesExpectedOldValue(event, re, expectedOldValue, replaceOnClient)) {
                    return null;
                  }
                  // invoke cacheWriter
                  invokeCacheWriter(re, event, cacheWrite, cacheWriter,
                      netWriteRecipients, requireOldValue, expectedOldValue, replaceOnClient);
                  
                  // notify index of an update
                  notifyIndex(re, owner, true);
                  try {
                    NonLocalRegionEntry oldRe = null;
                    try {
                      final Object memoryValue = oldValueForDelta != null ? oldValueForDelta
                          : re._getValue();
                      final LocalRegion region = event.getLocalRegion();
                      final int oldSize = region.calculateValueSize(memoryValue);
                      if (owner.isSnapshotEnabledRegion() && re.getVersionStamp() != null ) {
                        // we need to do the same for secondary as well.
                        // need to set the version information.
                        int valueSize = oldSize;
                        if (re.getVersionStamp().asVersionTag().getEntryVersion() > 0) {
                          oldRe = NonLocalRegionEntry.newEntryWithoutFaultIn(re, event.getRegion(), true);
                          valueSize = memoryValue != null && oldSize > 0
                              ? oldSize : region.calculateValueSize(oldRe._getValue());
                          oldRe.setUpdateInProgress(true);
                          oldRe.setValueSize(valueSize);
                          checkConflict(owner, event, re);
                        }
                        // need to put old entry in oldEntryMap for MVCC
                        owner.getCache().addOldEntry(oldRe, re, owner, event);
                      }
                      if ((cacheWrite && event.getOperation().isUpdate()) // if there is a cacheWriter, type of event has already been set
                          || !re.isRemoved()
                          || replaceOnClient) {
                        // We should conflict here if the version of region entry is not present in the snapshot
                        updateEntry(event, requireOldValue, oldValueForDelta, re, oldSize);
                      } else {
                        // create
                        createEntry(event, owner, re);
                      }
                      owner.recordEvent(event);
                      eventRecorded = true;
                    } catch (RegionClearedException rce) {
                      clearOccured = true;
                      owner.recordEvent(event);
                    } catch (ConcurrentCacheModificationException ccme) {
                      VersionTag tag = event.getVersionTag();
                      if (tag != null && tag.isTimeStampUpdated()) {
                        // Notify gateways of new time-stamp.
                        owner.notifyTimestampsToGateways(event);
                      }
                      throw ccme;
                    } finally {
                      if (oldRe != null) {
                        oldRe.setUpdateInProgress(false);
                      }
                    }
                    if (uninitialized) {
                      event.inhibitCacheListenerNotification(true);
                    }
                    updateLru(clearOccured, re, event);

                    lastModifiedTime = owner.basicPutPart2(event, re,
                        !uninitialized, lastModifiedTime, clearOccured);
                  } finally {
                    notifyIndex(re, owner, false);
                  }
                  result = re;
                  break;
                } finally {
                  OffHeapHelper.release(oldValueForDelta);
                  if (re != null && !onlyExisting && !isOpComplete(re, event)) {
                    owner.cleanUpOnIncompleteOp(event, re, eventRecorded,
                        false/* updateStats */, replaceOnClient);
                  }
                  else if (re != null && owner.isUsedForPartitionedRegionBucket()) {
                    BucketRegion br = (BucketRegion)owner;
                    CachePerfStats stats = br.getPartitionedRegion().getCachePerfStats();
                    long startTime= stats.startCustomEviction();
                    CustomEvictionAttributes csAttr = br.getCustomEvictionAttributes();
                    // No need to update indexes if entry was faulted in but operation did not succeed. 
                    if (csAttr != null && (csAttr.isEvictIncoming() || re.isMarkedForEviction())) {
                      
                      if (csAttr.getCriteria().doEvict(event)) {
                        stats.incEvictionsInProgress();
                        // set the flag on event saying the entry should be evicted 
                        // and not indexed
                        EntryEventImpl destroyEvent = EntryEventImpl.create(owner, Operation.DESTROY, event.getKey(),
                            null/* newValue */, null, false, owner.getMyId());
                        try {

                        destroyEvent.setOldValueFromRegion();
                        destroyEvent.setCustomEviction(true);
                        destroyEvent.setPossibleDuplicate(event.isPossibleDuplicate());
                        if(owner.getLogWriterI18n().fineEnabled()) {
                          owner.getLogWriterI18n().fine("Evicting the entry " + destroyEvent);
                        }
                        if(result != null) {
                          removeEntry(event.getKey(),re, true, destroyEvent,owner, indexUpdater);
                        }
                        else{
                          removeEntry(event.getKey(),re, true, destroyEvent,owner, null);
                        }
                        //mark the region entry for this event as evicted 
                        event.setEvicted();
                        stats.incEvictions();
                        if(owner.getLogWriterI18n().fineEnabled()) {
                          owner.getLogWriterI18n().fine("Evicted the entry " + destroyEvent);
                        }
                        //removeEntry(event.getKey(), re);
                        } finally {
                          destroyEvent.release();
                          stats.decEvictionsInProgress();
                        }
                      } else {
                        re.clearMarkedForEviction();
                      }
                    }
                    stats.endCustomEviction(startTime);
                  }
                }
              }
            } // sync re
          }// end while
    } catch (DiskAccessException dae) {
      //Asif:Feel that it is safe to destroy the region here as there appears
      // to be no chance of deadlock during region destruction      
      result = null;
      owner.handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
        releaseCacheModificationLock(owner, event);
        if (indexLocked) {
          indexManager.unlockForIndexGII();
        }
        if (owner.getIndexManager() != null) {
          owner.getIndexManager().countDownIndexUpdaters();
        }
        if (result != null) {
          try {
            // Note we do distribution after releasing all sync to avoid deadlock
            final boolean invokeListeners = event.basicGetNewValue() != Token.TOMBSTONE;
            owner.basicPutPart3(event, result, !uninitialized,
                lastModifiedTime, invokeListeners, ifNew, ifOld, expectedOldValue, requireOldValue);
          } catch (EntryExistsException eee) {
            // GemFire XD changes BEGIN
            // ignore EntryExistsException in distribution from a non-empty
            // region since actual check will be done in this put itself
            // and it can happen in distribution if put comes in from
            // GII as well as distribution channel
            if (indexManager != null) {
              if (log.finerEnabled()) {
                log.finer("basicPut: ignoring EntryExistsException in "
                    + "distribution", eee);
              }
            }
            else {
              // can this happen for non-GemFire XD case?
              throw eee;
            }
            // GemFire XD changes END
          } finally {
            // bug 32589, post update may throw an exception if exception occurs
            // for any recipients
            if (!clearOccured) {
              try {
                lruUpdateCallback();
              } catch( DiskAccessException dae) {
                //Asif:Feel that it is safe to destroy the region here as there appears
                // to be no chance of deadlock during region destruction      
                result = null;
                owner.handleDiskAccessException(dae, true/* stop bridge servers*/);
                throw dae;
              }
            }
          } //  finally
        } else {
          resetThreadLocals();
        }
    } // finally

    return result;
  }

  private void checkConflict(LocalRegion owner, EntryEventImpl event, RegionEntry re) {
    //TODO: Make it property based and return oldValue taken from OldREgionEntry if confict and not
    // throwing conflictexception
    if (owner.isUsedForPartitionedRegionBucket() && !((BucketRegion)owner).getBucketAdvisor().isPrimary()) {
      return;
    }
    // Don't do conflict detection on secondary.
    if (event.getTXState() != null && event.getTXState().isSnapshot()) {
      TXState localState = event.getTXState().getLocalTXState();
      if (!firstEntry(re)) {
        // deltas will be merged and will not conflict
        if (!TXState.checkEntryInSnapshot(localState, event.getRegion(), re)
            && !event.hasColumnDelta()) {
          throw new ConflictException("The value has changed.");
        }
      }
    }
  }

  private boolean firstEntry(RegionEntry re) {
    return (re.getVersionStamp().getEntryVersion() == 0) && re.isRemoved();
  }

/*  private boolean shouldCopyOldEntry(LocalRegion owner, EntryEventImpl event) {
    return owner.getCache().snapshotEnabled() &&
        owner.concurrencyChecksEnabled && !owner.isUsedForMetaRegion();
  }*/

  /**
   * If the value in the VM is still REMOVED_PHASE1 Token, then the operation
   * was not completed (due to cacheWriter exception, concurrentMap operation) etc.
   */
  private boolean isOpComplete(RegionEntry re, EntryEventImpl event) {
    if (re.getValueAsToken() == Token.REMOVED_PHASE1) {
      return false;
    }
    return true;
  }

  private boolean satisfiesExpectedOldValue(EntryEventImpl event,
      RegionEntry re, Object expectedOldValue, boolean replaceOnClient) {
    // replace is propagated to server, so no need to check
    // satisfiesOldValue on client
    if (expectedOldValue != null && !replaceOnClient) {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      
      @Retained @Released Object v = re._getValueRetain(event.getLocalRegion(), true);
      
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
        if (!AbstractRegionEntry.checkExpectedOldValue(expectedOldValue, v)) {
          return false;
        }
      } finally {
        OffHeapHelper.releaseWithNoTracking(v);
      }
    }
    return true;
  }

  // Asif: If the new value is an instance of SerializableDelta, then
  // the old value requirement is a must & it needs to be faulted in
  // if overflown to disk without affecting LRU? This is needed for
  // Sql Fabric.
  // [sumedh] store both the value in VM and the value in VM or disk;
  // the former is used for updating the VM size calculations, while
  // the latter is used in other places like passing to
  // GfxdIndexManager or setting the old value in the event; this is
  // required since using the latter for updating the size
  // calculations will be incorrect in case the value was read from
  // disk but not brought into the VM like what getValueInVMOrDisk
  // method does when value is not found in VM
  // PRECONDITION: caller must be synced on re
  private void setOldValueInEvent(EntryEventImpl event, RegionEntry re, boolean cacheWrite, boolean requireOldValue) {
    boolean needToSetOldValue = getIndexUpdater() != null || cacheWrite || requireOldValue || event.getOperation().guaranteesOldValue();
    if (needToSetOldValue) {
      if (event.hasDelta() || event.getOperation().guaranteesOldValue()
          || GemFireCacheImpl.gfxdSystem()) {
        // In these cases we want to even get the old value from disk if it is not in memory
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Released Object oldValueInVMOrDisk = re.getValueOffHeapOrDiskWithoutFaultIn(event.getLocalRegion());
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (oldValueInVMOrDisk == Token.NOT_AVAILABLE) {
          oldValueInVMOrDisk = null;
        }
        try {
          event.setOldValue(oldValueInVMOrDisk, requireOldValue
              || GemFireCacheImpl.gfxdSystem());
        } finally {
          OffHeapHelper.releaseWithNoTracking(oldValueInVMOrDisk);
        }
      } else {
        // In these cases only need the old value if it is in memory
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        
        @Retained @Released Object oldValueInVM = re._getValueRetain(event.getLocalRegion(), true); // OFFHEAP: re synced so can use its ref.
        
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        try {
          event.setOldValue(oldValueInVM,
              requireOldValue || GemFireCacheImpl.gfxdSystem());
        } finally {
          OffHeapHelper.releaseWithNoTracking(oldValueInVM);
        }
      }
    } else {
      // if the old value is in memory then if it is a GatewaySenderEventImpl then
      // we want to set the old value.
      @Unretained Object ov = re._getValue();
      if (ov instanceof GatewaySenderEventImpl) {
        event.setOldValue(ov, true);
      }
    }
  }

  /**
   * Asif: If the system is GemFireXD and the event has delta, then re == null
   * implies update on non existent row . Throwing ENFE in that case 
   * As  returning a boolean etc has other complications in terms of PR reattempt etc 
   */
  private void throwExceptionForGemFireXD(EntryEventImpl event) {
    if (event.hasDeltaPut() && _getOwner().getGemFireCache().isGFXDSystem()) {
      throw new EntryNotFoundException(
          "GemFireXD::No row found for update");
    }
  }

  protected void createEntry(EntryEventImpl event, final LocalRegion owner,
      RegionEntry re) throws RegionClearedException {
    final boolean wasTombstone = re.isTombstone();
    processVersionTag(re, event);
    event.putNewEntry(owner, re);
    updateSize(event, 0, false, wasTombstone);
    if (!event.getLocalRegion().isInitialized()) {
      owner.getImageState().removeDestroyedEntry(event.getKey());
    }
  }

  protected void updateEntry(EntryEventImpl event, boolean requireOldValue,
      Object oldValueForDelta, RegionEntry re, int oldSize) throws RegionClearedException {

    final boolean wasTombstone = re.isTombstone();
    processVersionTag(re, event);
    event.putExistingEntry(event.getLocalRegion(), re, requireOldValue,
        oldValueForDelta, oldSize);
    EntryLogger.logPut(event);
    updateSize(event, oldSize, true/* isUpdate */, wasTombstone);
  }

  private void updateLru(boolean clearOccured, RegionEntry re, EntryEventImpl event) {
    if (!clearOccured) {
      if (event.getOperation().isCreate()) {
        lruEntryCreate(re);
      } else {
        lruEntryUpdate(re);
      }
    }
  }

  private void updateSize(EntryEventImpl event, int oldSize, boolean isUpdate, boolean wasTombstone) {
    if (isUpdate && !wasTombstone) {
      _getOwner().updateSizeOnPut(event.getKey(), oldSize, event.getNewValueBucketSize());
    } else {
      _getOwner().updateSizeOnCreate(event.getKey(), event.getNewValueBucketSize());
      if (!wasTombstone) {
        incEntryCount(1);
      }
    }
  }

  private void notifyIndex(RegionEntry re, LocalRegion owner,
      boolean isUpdating) {
    if (this.indexUpdater != null || owner.indexMaintenanceSynchronous) {
      re.setUpdateInProgress(isUpdating);
    }
  }

  private void invokeCacheWriter(RegionEntry re, EntryEventImpl event,
      boolean cacheWrite, CacheWriter cacheWriter, Set netWriteRecipients,
      boolean requireOldValue, Object expectedOldValue, boolean replaceOnClient) {
    // invoke listeners only if region is initialized
    if (_getOwner().isInitialized() && cacheWrite) {
      // event.setOldValue already called in setOldValueInEvent

      // bug #42638 for replaceOnClient, do not make the event create
      // or update since replace must propagate to server
      if (!replaceOnClient) {
        if (re.isDestroyedOrRemoved()) {
          event.makeCreate();
        } else {
          event.makeUpdate();
        }
      }
      _getOwner().cacheWriteBeforePut(event, netWriteRecipients, cacheWriter,
          requireOldValue, expectedOldValue);
    }
    if (!_getOwner().getGemFireCache().isGFXDSystem()) {
      // to fix bug 48763 keep the old value referenced if xd. xd needs the old value.
    if (!_getOwner().isInitialized() && !cacheWrite && !event.hasDelta()) {
      // block setting of old value in putNewValueNoSync, don't
      // need it
      event.oldValueNotAvailable();
    }
    }
  }

  private boolean continueOverwriteDestroyed(RegionEntry re,
      EntryEventImpl event, boolean overwriteDestroyed, boolean ifNew) {
    Token oldValueInVM = re.getValueAsToken();
    // if region is under GII, check if token is destroyed
    if (!overwriteDestroyed) {
      if (!_getOwner().isInitialized() && (oldValueInVM == Token.DESTROYED || oldValueInVM == Token.TOMBSTONE)) {
        event.setOldValueDestroyedToken();
        return false;
      }
    }
    if (ifNew && !Token.isRemoved(oldValueInVM)) {
      return false;
    }
    return true;
  }

  private boolean continueUpdate(RegionEntry re, EntryEventImpl event,
      boolean ifOld, boolean replaceOnClient) {
    if (ifOld) {
      // only update, so just do tombstone maintainence and exit
      if (re.isTombstone() && event.getVersionTag() != null) {
        // refresh the tombstone so it doesn't time out too soon
        processVersionTag(re, event);
        try {
          re.setValue(_getOwner(), Token.TOMBSTONE);
        } catch (RegionClearedException e) {
          // that's okay - when writing a tombstone into a disk, the
          // region has been cleared (including this tombstone)
        }
        _getOwner().rescheduleTombstone(re, re.getVersionStamp().asVersionTag());
        return false;
      }
      if (re.isRemoved() && !replaceOnClient) {
        return false;
      }
    }
    return true;
  }

  protected boolean destroyEntry(RegionEntry re, EntryEventImpl event,
      boolean inTokenMode, boolean cacheWrite, @Released Object expectedOldValue,
      boolean createdForDestroy, boolean removeRecoveredEntry)
      throws CacheWriterException, TimeoutException, EntryNotFoundException,
      RegionClearedException {

    NonLocalRegionEntry oldRe = null;
    boolean retVal = false;
    try {
      final Object memoryValue = re._getValue();
      final int oldSize = _getOwner().calculateValueSize(memoryValue);
      if (_getOwner().isSnapshotEnabledRegion() /*&& re.getVersionStamp() != null && re.getVersionStamp()
        .asVersionTag().getEntryVersion() > 0*/) {
        // we need to do the same for secondary as well.
        // check Conflict
        checkConflict(_getOwner(), event, re);
        oldRe = NonLocalRegionEntry.newEntryWithoutFaultIn(re, event.getRegion(), true);
        oldRe.setUpdateInProgress(true);
        final int valueSize = memoryValue != null && oldSize > 0
            ? oldSize : _getOwner().calculateValueSize(oldRe._getValue());
        oldRe.setValueSize(valueSize);
        oldRe.setForDelete();
        _getOwner().getCache().addOldEntry(oldRe, re, _getOwner(), event);
      }
      processVersionTag(re, event);


      retVal = re.destroy(event.getLocalRegion(), event, inTokenMode,
              cacheWrite, expectedOldValue, createdForDestroy, removeRecoveredEntry);
      // we can add the old value to
      if (retVal) {
        EntryLogger.logDestroy(event);
        _getOwner().updateSizeOnRemove(event.getKey(), oldSize);
      }

    } finally {
      if (oldRe != null)
        oldRe.setUpdateInProgress(false);
    }
    return retVal;
  }

  @Override
  public void txApplyPut(Operation putOp, final RegionEntry re,
      final TXStateInterface txState, final Object key, final Object nv,
      boolean didDestroy, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent, Delta delta) {

    final LocalRegion owner = _getOwner();
    if (owner == null) {
      // "fix" for bug 32440
      Assert.fail("The owner for RegionMap " + this + " is null");
    }
    final LogWriterI18n log = owner.getLogWriterI18n();
    Object newValue = nv;

    //final boolean hasRemoteOrigin = !txState.isCoordinator();
    final boolean isRegionReady = owner.isInitialized();
    final long lastMod = cbEvent.getEventTime(0L, owner);
    boolean cbEventInPending = false;
    boolean isLockedForTXCacheMod = false;
    boolean opCompleted = false;
    boolean entryCreated = false;
    RegionEntry oldRe = null;
    try {
      // create new EntryEventImpl in case pendingCallbacks is non-null
      if (pendingCallbacks != null) {
        cbEvent = new EntryEventImpl();
        cbEvent.setEntryLastModified(lastMod);
      }
      if (shouldCreateCBEvent(owner, false /* isInvalidate */, isRegionReady)) {
        cbEvent = createCBEvent(owner, putOp, key, newValue, txState, eventId,
            aCallbackArgument, filterRoutingInfo, bridgeContext, versionTag,
            tailKey, cbEvent);
        if (log.fineEnabled()) {
          log.fine("txApplyPut::cbEvent=" + cbEvent);
        }
      }
      else {
        if (log.fineEnabled()) {
          log.fine("txApplyPut::No callback event created");
        }
        cbEvent = null;
      }

      if (owner.isUsedForPartitionedRegionBucket()) {
        newValue = EntryEventImpl.getCachedDeserializable(nv);
        ((BucketRegion)owner).handleWANEvent(cbEvent);
      }
      entryCreated = re.isLockedForCreate();
      // Fix for Bug #44431. We do NOT want to update the region and wait
      // later for index INIT as region.clear() can cause inconsistency if
      // happened in parallel as it also does index INIT.
      if (owner.getIndexManager() != null) {
        owner.getIndexManager().waitForIndexInit();
      }
      isLockedForTXCacheMod = true;
      /*
      if (hasRemoteOrigin) {
        // If we are not a mirror then only apply the update to existing
        // entries
        // 
        // If we are a mirror then then only apply the update to
        // existing entries when the operation is an update and we
        // are initialized.
        // Otherwise use the standard create/update logic
        if (!owner.isAllEvents() || (!putOp.isCreate() && isRegionReady)) {
          // At this point we should only apply the update if the entry exists
          //re = getEntry(key); // Fix for bug 32347.
          if (re.isDestroyedOrRemoved()) {
            if (didDestroy) {
              owner.txApplyInvalidatePart2(re, key, true, false, false);
            }
            return;
          }
        }
      }
      */

      // check that entry lock has already been upgraded to EX mode
      assert re.hasExclusiveLock(null, null):
        "unexpected absence of EX lock during txApply";

      // TODO: merge: isn't this better done once before commit?
      //Map<AbstractIndex, Long> lockedIndexes = owner
      //    .acquireWriteLocksOnCompactRangeIndexes();
      //try {
      // Net writers are not called for received transaction data
      final int oldSize = owner.calculateRegionEntryValueSize(re);
      {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      
      @Retained @Released Object oldValue = re.getValueInVM(owner); // OFFHEAP eei      
      
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
      if (Token.isRemoved(oldValue)) {
        oldValue = null;
        putOp = putOp.getCorrespondingCreateOp();
        if (cbEvent != null) {
          cbEvent.setRegionEntry(re);
          cbEvent.setOldValue(null);
        }
      }
      else {
        putOp = putOp.getCorrespondingUpdateOp();
        if (cbEvent != null) {
          cbEvent.setRegionEntry(re);
          cbEvent.setOldValue(oldValue);
          if (delta != null) {
            cbEvent.setNewDelta(delta);
          }
        }
      }
      } finally {
        OffHeapHelper.releaseWithNoTracking(oldValue);
      }
      }
      boolean flag = false;
      EntryEventImpl txEvent = null;
      if (!isRegionReady && owner.getConcurrencyChecksEnabled()) {
        InitialImageOperation.Entry tmplEntry = new InitialImageOperation.Entry();

        flag = checkIfEqualValue(owner, re, tmplEntry, newValue);

        // creating the event just to process the version tag.
        txEvent = createCBEvent(owner, putOp, key, newValue, txState, eventId,
            aCallbackArgument, filterRoutingInfo, bridgeContext, versionTag,
            tailKey, cbEvent);
      }
      if (!flag) {
        processAndGenerateTXVersionTag(owner, !isRegionReady ? txEvent : cbEvent, re, txr);
      }
      txRemoveOldIndexEntry(putOp, re);
      if (didDestroy) {
        re.txDidDestroy(lastMod);
      }
      /*
      if (txEvent != null) {
        txEvent.addPut(putOp, owner, re, key, newValue, aCallbackArgument);
      }
      re.setValueResultOfSearch(putOp.isNetSearch());
      */
      boolean clearOccured = false;
      boolean isCreate = false;
      try {
        re.setValue(owner, re.prepareValueForCache(owner, newValue, !putOp.isCreate(), false));
        if (putOp.isCreate()) {
          isCreate = true;
          owner.updateSizeOnCreate(key, owner.calculateRegionEntryValueSize(re));
        }
        else if (putOp.isUpdate()) {
          isCreate = false;
          owner.updateSizeOnPut(key, oldSize,
              owner.calculateRegionEntryValueSize(re));
        }
      } catch (RegionClearedException rce) {
        clearOccured = true;
        isCreate = putOp.isCreate();
      } finally {
        if (oldRe != null)
          oldRe.setUpdateInProgress(false);
      }
      if (EntryLogger.isEnabled()) {
        EntryLogger.logTXPut(_getOwnerObject(), key, nv);
      }
      re.updateStatsForPut(lastMod);
      owner.txApplyPutPart2(re, key, newValue, lastMod, isCreate, didDestroy,
          clearOccured);
      opCompleted = true;
      if (cbEvent != null) {
        final EnumListenerEvent listenerEvent;
        if (isCreate) {
          cbEvent.makeCreate();
          listenerEvent = EnumListenerEvent.AFTER_CREATE;
        }
        else {
          cbEvent.makeUpdate();
          listenerEvent = EnumListenerEvent.AFTER_UPDATE;
        }
        if (pendingCallbacks == null) {
          owner.invokeTXCallbacks(listenerEvent, cbEvent,
              true /*callDispatchListenerEvent*/, true /*notifyGateway*/);
        }
        else {
          pendingCallbacks.add(cbEvent);
          cbEventInPending = true;
        }
      }
      if (!clearOccured) {
        if (isCreate) {
          lruEntryCreate(re);
          incEntryCount(1);
        }
        else {
          lruEntryUpdate(re);
        }
      }
      //} finally {
      //  owner.releaseAcquiredWriteLocksOnIndexes(lockedIndexes);
      //}
    } catch (DiskAccessException dae) {
      owner.handleDiskAccessException(dae, true/* stop bridge servers*/);
      throw dae;
    } finally {
      if (isLockedForTXCacheMod) {
        if (!opCompleted && (entryCreated || re.isMarkedForEviction())) {
          removeEntry(key, re, false);
        }
        if (owner.getIndexManager() != null) {
          owner.getIndexManager().countDownIndexUpdaters();
        }
      }
      if (opCompleted) {
        if (re != null && owner.isUsedForPartitionedRegionBucket()) {
          BucketRegion br = (BucketRegion)owner;
          CachePerfStats stats = br.getPartitionedRegion().getCachePerfStats();
          long startTime = stats.startCustomEviction();
          CustomEvictionAttributes csAttr = br.getCustomEvictionAttributes();
          // No need to update indexes if entry was faulted in but operation did
          // not succeed.
          if (csAttr != null
              && (csAttr.isEvictIncoming() || re.isMarkedForEviction())) {
            if (csAttr.getCriteria().doEvict(cbEvent)) {
              stats.incEvictionsInProgress();
              // set the flag on event saying the entry should be evicted
              // and not indexed
              EntryEventImpl destroyEvent = EntryEventImpl.create(owner,
                  Operation.DESTROY, cbEvent.getKey(), null/* newValue */,
                  null, false, owner.getMyId());
              
              try {
                destroyEvent.setOldValueFromRegion();
                destroyEvent.setCustomEviction(true);
                destroyEvent
                    .setPossibleDuplicate(cbEvent.isPossibleDuplicate());
                destroyEvent.setTXState(txState);
                
                if (owner.getLogWriterI18n().fineEnabled()) {
                  owner.getLogWriterI18n().fine(
                      "txApplyPut: Evicting the entry " + destroyEvent);
                }
                // TODO: Suranjan need to find if the index updation will be
                // taken care of later.
                removeEntry(cbEvent.getKey(), re, true, destroyEvent, owner,
                    indexUpdater);
                
                // else{
                // removeEntry(cbEvent.getKey(),re, true, destroyEvent,owner,
                // null);
                // }
                stats.incEvictions();
                if (owner.getLogWriterI18n().fineEnabled()) {
                  owner.getLogWriterI18n().fine(
                      "txApplyPut: Evicted the entry " + destroyEvent);
                }
                // removeEntry(event.getKey(), re);
              } finally {
                destroyEvent.release();
                stats.decEvictionsInProgress();
              }
            }
          }
          stats.endCustomEviction(startTime);
        }
      }

      if (!cbEventInPending && cbEvent != null) cbEvent.release();
    }
  }

  private boolean checkIfEqualValue(LocalRegion region, RegionEntry re, InitialImageOperation.Entry tmplEntry,
      Object tmpValue) {
    final DM dm = region.getDistributionManager();
    final HeapDataOutputStream out = new HeapDataOutputStream(
        Version.CURRENT);

    if (re.fillInValue(region, tmplEntry, dm, null)) {
      try {
        if (tmplEntry.value != null) {
          final byte[] valueInCache;
          boolean areEqual = true;
          final Class<?> vclass = tmplEntry.value.getClass();
          if (vclass == byte[].class) {
            valueInCache = (byte[])tmplEntry.value;
            return Arrays.equals(valueInCache, (byte[])tmpValue);
          } else if (vclass == byte[][].class) {
            if (tmpValue instanceof byte[][]) {
              final byte[][] v1 = (byte[][])tmplEntry.value;
              final byte[][] v2 = (byte[][])tmpValue;
              areEqual = ArrayUtils.areByteArrayArrayEquals(v1,
                  v2);
              if (areEqual) {
                return true;
              } else {
                valueInCache = null;
              }
            } else {
              valueInCache = EntryEventImpl
                  .serialize(tmplEntry.value, out);
            }
          } else if (ByteSource.class.isAssignableFrom(vclass)) {
            ByteSource bs = (ByteSource)tmplEntry.value;
            // TODO: PERF: Asif: optimize ByteSource to allow
            // comparison without reading byte[][] into memory
            Object storedObject = bs
                .getValueAsDeserializedHeapObject();
            final Class<?> cls;
            if (storedObject == null) {
              valueInCache = null;
            } else if ((cls = storedObject.getClass()) == byte[].class) {
              valueInCache = (byte[])storedObject;
            } else if (cls == byte[][].class
                && tmpValue instanceof byte[][]) {
              final byte[][] v1 = (byte[][])storedObject;
              final byte[][] v2 = (byte[][])tmpValue;
              areEqual = ArrayUtils.areByteArrayArrayEquals(v1,
                  v2);
              if (areEqual) {
                return true;
              } else {
                valueInCache = null;
              }
            } else {
              valueInCache = EntryEventImpl
                  .serialize(storedObject, out);
            }
          } else if (HeapDataOutputStream.class
              .isAssignableFrom(vclass)) {
            valueInCache = ((HeapDataOutputStream)tmplEntry.value)
                .toByteArray();
          } else {
            valueInCache = EntryEventImpl
                .serialize(tmplEntry.value, out);
          }
          // compare byte arrays
          if (areEqual) {
            byte[] tmpBytes = EntryEventImpl.serialize(tmpValue, out);
            if (Arrays.equals(valueInCache, tmpBytes)) {
              return true;
            }
          }
        }
      } finally {
        OffHeapHelper.release(tmplEntry.value);
      }
    }
    return false;
  }


  /**
   * called from txApply* methods to process and generate versionTags.
   */
  private void processAndGenerateTXVersionTag(final LocalRegion owner,
      EntryEventImpl cbEvent, RegionEntry re, TXRegionState txr) {
    if (cbEvent != null && owner.getConcurrencyChecksEnabled()
        && (!owner.getScope().isLocal() || owner.getDataPolicy()
            .withPersistence())) {
      PartitionedRegion pr = null;
      if (owner.isUsedForPartitionedRegionBucket()) {
        pr = (PartitionedRegion)cbEvent.getRegion();
        if (cbEvent != null) {
          cbEvent.setRegion(owner);
        }
      }
      try {
        /* now we copy only commitTime from remote
        if (txEntryState != null && txEntryState.getRemoteVersionTag() != null) {
          // to generate a version based on a remote VersionTag, we will
          // have to put the remote versionTag in the regionEntry
          VersionTag remoteTag = txEntryState.getRemoteVersionTag();
          if (re instanceof VersionStamp) {
            VersionStamp stamp = (VersionStamp) re;
            stamp.setVersions(remoteTag);
          }
        }
        */
        if (cbEvent.getVersionTag() != null) {
          processVersionTag(re, cbEvent);
        }
        else {
          boolean eventHasDelta = (cbEvent.getDeltaBytes() != null
              && owner.getSystem().getConfig().getDeltaPropagation()
              && !owner.getScope().isDistributedNoAck());
          VersionSource<?> source = txr.getVersionSource();
          // isRemoteVersionSource should be invoked only after the
          // getVersionSource call is complete
          VersionTag<?> tag = re.generateVersionTag(source,
              txr.isRemoteVersionSource(), eventHasDelta, owner, cbEvent);
          if (tag != null) {
            LogWriterI18n log = owner.getLogWriterI18n();
            if (log.fineEnabled()) {
              log.fine("generated version tag " + tag + " for "
                  + owner.getFullPath() + ", source=" + source
                  + ", isRemoteSource=" + txr.isRemoteVersionSource());
            }
            cbEvent.setVersionTag(tag);
          }
        }
      } catch (ConcurrentCacheModificationException ignore) {
        // ignore this execption, however invoke callbacks for this operation
      }
      finally {
        if (pr != null) {
          cbEvent.setRegion(pr);
        }
      }
    }
  }

  /**
   * Switch the event's region from BucketRegion to owning PR and set originRemote to the given value
   */
  static EntryEventImpl switchEventOwnerAndOriginRemote(EntryEventImpl event, boolean originRemote) {
    assert event != null;
    if (event.getRegion().isUsedForPartitionedRegionBucket()) {
      LocalRegion pr = event.getRegion().getPartitionedRegion();
      event.setRegion(pr);
    }
    event.setOriginRemote(originRemote);
    return event;
  }

  /**
   * Prepares and returns a value to be stored in the cache.
   * Current prep is to make sure a PdxInstance is not stored in the cache
   * and to copy values into offheap memory of the region is using off heap storage.
   * 
   * @param r the region the prepared object will be stored in
   * @param val the value that will be stored
   * @return the prepared value
   */
  public static Object prepareValueForCache(RegionEntryContext r, Object val) {
    Object nv = val;
    if (nv instanceof PdxInstance) {
      // We do not want to put PDXs in the cache as values.
      // So get the serialized bytes and use a CachedDeserializable.
      try {
        byte[] data = ((ConvertableToBytes)nv).toBytes();
        byte[] compressedData = compressBytes(r, data);
        if (data == compressedData) {
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
  
  private static byte[] compressBytes(RegionEntryContext context, byte[] value) {
    if (AbstractRegionEntry.isCompressible(context, value)) {
      value = context.getCompressor().compress(value);
    }
    return value;
  }
  
  /**
   * Removing the existing indexed value requires the current value in the cache, 
   * that is the one prior to applying the operation.
   * @param op
   * @param entry the RegionEntry that contains the value prior to applying the op
   */
  private void txRemoveOldIndexEntry(Operation op, RegionEntry entry) {
    if ((op.isUpdate() && !entry.isInvalid()) ||
        op.isInvalidate() || op.isDestroy()) {
      IndexManager idxManager = _getOwner().getIndexManager();
      if (idxManager != null) {
        try {
          idxManager.updateIndexes(entry,
                                  IndexManager.REMOVE_ENTRY,
                                  op.isUpdate() ?
                                  IndexProtocol.BEFORE_UPDATE_OP :
                                  IndexProtocol.OTHER_OP);
        } catch (QueryException e) {
          throw new IndexMaintenanceException(e);
        }
      }
    }
  }

  public void dumpMap(LogWriterI18n log) {
    StringId str = LocalizedStrings.DEBUG;
    log.info(str, "dump of concurrent map of size " + this._getMap().size() + " for region " + this._getOwner());
    for (Iterator it = this._getMap().values().iterator(); it.hasNext(); ) {
      log.info(str, it.next().toString());
    }
  }

  static final boolean shouldCreateCBEvent(final LocalRegion owner,
      final boolean isInvalidate, final boolean isInitialized) {
    LocalRegion lr = owner;
    boolean isPartitioned = lr.isUsedForPartitionedRegionBucket();

    if (isPartitioned) {
     /* if (!((BucketRegion)lr).getBucketAdvisor().isPrimary()) {
        if (!BucketRegion.FORCE_LOCAL_LISTENERS_INVOCATION) {
          return false;
        }
      }*/
      lr = owner.getPartitionedRegion();
    }
    if (isInvalidate) { // ignore shouldNotifyGatewayHub check for invalidates
      return (isPartitioned || isInitialized)
          && (lr.shouldDispatchListenerEvent()
            || lr.shouldNotifyBridgeClients()
            || lr.getConcurrencyChecksEnabled());
    } else {
      return (isPartitioned || isInitialized)
          && (lr.shouldDispatchListenerEvent()
            || lr.shouldNotifyBridgeClients() 
            || lr.shouldNotifyGatewayHub()
            || lr.shouldNotifyGatewaySenders()
            || lr.getConcurrencyChecksEnabled());
    }
  }

  /**
   * Create a callback event for applying a transactional change to the local
   * cache.
   */
  static final EntryEventImpl createCBEvent(final LocalRegion re, Operation op,
      Object key, Object newValue, final TXStateInterface txState,
      EventID eventId, Object aCallbackArgument,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag versionTag,
      long tailKey, EntryEventImpl retVal) {

    // txState should not be null even on localOrigin
    Assert.assertTrue(txState != null);

    final LogWriterI18n logger = re.getLogWriterI18n();
    if (logger.finerEnabled()) {
      logger.finer(txState + ": creating callback event for op=" + op.toString()
          + " eventId=" + eventId.toString() + " region: " + re);
    }

    final InternalDistributedMember originator = txState.getCoordinator();
    final boolean originRemote = (originator != re.cache.getMyId());
    LocalRegion eventRegion = re;
    int bucketId = KeyInfo.UNKNOWN_BUCKET;
    if (re.isUsedForPartitionedRegionBucket()) {
      final BucketRegion br = (BucketRegion)re;
      eventRegion = br.getPartitionedRegion();
      bucketId = br.getId();
    }
    if (retVal == null) {
      retVal = EntryEventImpl.create(eventRegion, op, key, newValue,
          aCallbackArgument, originRemote, originator);
    }
    else {
      retVal.reset(true);
      retVal.setRegion(eventRegion);
      retVal.setOperation(op);
      retVal.setKey(key);
      retVal.setNewValueForTX(newValue);
      retVal.setRawCallbackArgument(aCallbackArgument);
      retVal.setOriginRemote(originRemote);
      retVal.setGenerateCallbacks(true);
      retVal.distributedMember = originator;

      if (bucketId != KeyInfo.UNKNOWN_BUCKET) {
        retVal.setBucketId(bucketId);
      }
      else {
        eventRegion.updateKeyInfo(retVal, key, newValue);
      }
    }

    boolean returnedRetVal = false;
    try {

    if (bridgeContext != null) {
      retVal.setContext(bridgeContext);
    }

    if (eventRegion.generateEventID()) {
      retVal.setEventId(eventId);
    }

    if (versionTag != null) {
      retVal.setVersionTag(versionTag);
    }
    retVal.setTailKey(tailKey);
    
    FilterInfo localRouting = null;
    boolean computeFilterInfo = false;
    if (filterRoutingInfo == null) {
      computeFilterInfo = true;
    }
    else {
      localRouting = filterRoutingInfo.getLocalFilterInfo();
      if (localRouting != null) {
        // routing was computed in this VM but may need to perform local
        // interest processing
        computeFilterInfo = !filterRoutingInfo.hasLocalInterestBeenComputed();
      }
      else {
        // routing was computed elsewhere and is in the "remote" routing table
        localRouting = filterRoutingInfo.getFilterInfo(re.getMyId());
      }
      if (localRouting != null) {
        if (!computeFilterInfo) {
          retVal.setLocalFilterInfo(localRouting);
        }
      }
      else {
        computeFilterInfo = true;
      }
    }
    if (logger.finerEnabled()) {
      logger.finer("createCBEvent filterRouting=" + filterRoutingInfo
          + " computeFilterInfo=" + computeFilterInfo + " local routing="
          + localRouting);
    }

    if (re.isUsedForPartitionedRegionBucket()) {
      final BucketRegion bucket = (BucketRegion)re;
      if (BucketRegion.FORCE_LOCAL_LISTENERS_INVOCATION
          || bucket.getBucketAdvisor().isPrimary()) {
        retVal.setInvokePRCallbacks(true);
      }
      else {
        retVal.setInvokePRCallbacks(false);
      }

      if (computeFilterInfo) {
        if (bucket.getBucketAdvisor().isPrimary()) {
          if (logger.finerEnabled()) {
            logger.finer("createCBEvent computing routing for primary bucket");
          }
          FilterProfile fp = bucket.getPartitionedRegion().getFilterProfile();
          if (fp != null) {
            FilterRoutingInfo fri = fp.getFilterRoutingInfoPart2(
                filterRoutingInfo, retVal);
            if (fri != null) {
              retVal.setLocalFilterInfo(fri.getLocalFilterInfo());
            }
          }
        }
      }
    }
    else if (computeFilterInfo) { // not a bucket
      if (re.getLogWriterI18n().finerEnabled()) {
        logger.finer("createCBEvent computing routing for non-bucket");
      }
      FilterProfile fp = re.getFilterProfile();
      if (fp != null) {
        retVal.setLocalFilterInfo(fp.getLocalFilterRouting(retVal));
      }
    }
    retVal.setTXState(txState);
    returnedRetVal = true;
    return retVal;
    } finally {
      if (!returnedRetVal) {
        retVal.release();
      }
    }
  }

  public final void writeSyncIfPresent(Object key, Runnable runner)
  {
    RegionEntry re = getEntry(key);
    if (re != null) {
      final boolean disabled = disableLruUpdateCallback();
      try {
        synchronized (re) {
          if (!re.isRemoved()) {
            runner.run();
          }
        }
      }
      finally {
        if (disabled) {
          enableLruUpdateCallback();
        }
        try {
          lruUpdateCallback();
        }catch(DiskAccessException dae) {
          this._getOwner().handleDiskAccessException(dae, true/* stop bridge servers*/);
          throw dae;
        }
      }
    }
  }

  public final void removeIfDestroyed(Object key)
  {
    LocalRegion owner = _getOwner();
//    boolean makeTombstones = owner.concurrencyChecksEnabled;
    DiskRegion dr = owner.getDiskRegion();
      RegionEntry re = getEntry(key);
      if (re != null) {
        if (re.isDestroyed()) {
          synchronized (re) {
            if (re.isDestroyed()) {
              // [bruce] destroyed entries aren't in the LRU clock, so they can't be retained here
//              if (makeTombstones) {
//                re.makeTombstone(owner, re.getVersionStamp().asVersionTag());
//              } else {
              re.removePhase2(owner);
              removeEntry(key, re, true);
            }
          }
        }
      }
//      }
  }

  private boolean reHasDelta(LocalRegion owner, RegionEntry re) {
    @Retained @Released Object oVal = re.getValueOffHeapOrDiskWithoutFaultIn(owner);
    try {
      if (oVal != null && oVal instanceof ListOfDeltas) {
        return true;
      }
      return false;
    } finally {
      OffHeapHelper.release(oVal);
    }
  }
  
  public final boolean isListOfDeltas(Object key) {
    RegionEntry re = getEntry(key);
    if(re != null) {
      LocalRegion owner = _getOwner();
      return reHasDelta(owner, re);
    }
    return false;
  }
  
  public final void removeIfDelta(Object key) {
    RegionEntry re = getEntry(key);
    if (re != null) {
      LocalRegion owner = _getOwner();
      if (reHasDelta(owner, re)) {
        synchronized (re) {
          re.removePhase2(owner);
          removeEntry(key, re, true);
        }
      }
    }
  }
  
  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    return sizer.sizeof(this) + estimateChildrenMemoryOverhead(sizer);
  }

  protected long estimateChildrenMemoryOverhead(SingleObjectSizer sizer) {
    return _getMap().estimateMemoryOverhead(sizer);
  }

  public void testOnlySetIndexUpdater(IndexUpdater iu) {
    this.indexUpdater = iu;
  }

  /** get version-generation permission from the region's version vector */
  private void lockForCacheModification(LocalRegion owner, EntryEventImpl event) {
    boolean lockedByPutAll = event.getPutAllOperation() != null && owner.dataPolicy.withReplication();
    if (!event.isOriginRemote() && !lockedByPutAll) {
      RegionVersionVector vector = owner.getVersionVector();
      if (vector != null) {
        vector.lockForCacheModification(owner);
      }
    }
  }
  
  /** release version-generation permission from the region's version vector */
  private void releaseCacheModificationLock(LocalRegion owner, EntryEventImpl event) {
    boolean lockedByPutAll = event.getPutAllOperation() != null && owner.dataPolicy.withReplication();
    if (!event.isOriginRemote() && !lockedByPutAll) {
      RegionVersionVector vector = owner.getVersionVector();
      if (vector != null) {
        vector.releaseCacheModificationLock(owner);
      }
    }
  }

  public final void unscheduleTombstone(RegionEntry re) {
  }
  
  /**
   * for testing race conditions between threads trying to apply ops to the
   * same entry
   * @param entry the entry to attempt to add to the system
   */
  protected final RegionEntry putEntryIfAbsentForTest(RegionEntry entry) {
    return (RegionEntry)putEntryIfAbsent(entry.getKey(), entry);
  }

  public boolean isTombstoneNotNeeded(RegionEntry re, int destroyedVersion) {
    // no need for synchronization - stale values are okay here
    // GFXD can return RegionEntry itself in getKey() call that fails when sent
    // to HDFS due to serialization attempt (#49887)
    RegionEntry actualRe = getEntry(
        getEntryNeedKeyCopy() ? re.getKeyCopy() : re.getKey());
    // TODO this looks like a problem for regionEntry pooling
    if (actualRe != re) {  // null actualRe is okay here
      return true; // tombstone was evicted at some point
    }
    int entryVersion = re.getVersionStamp().getEntryVersion();
    boolean isSameTombstone = (entryVersion == destroyedVersion && re.isTombstone());
    return !isSameTombstone;
  }

  /** removes a tombstone that has expired locally */
  public final boolean removeTombstone(RegionEntry re, VersionHolder version, boolean isEviction, boolean isScheduledTombstone)  {
    boolean result = false;
    int destroyedVersion = version.getEntryVersion();
    DiskRegion dr = this._getOwner().getDiskRegion();
    LogWriterI18n log = _getOwner().getLogWriterI18n();
//    if (version.getEntryVersion() == 0 && version.getRegionVersion() == 0) {
//      if (log.fineEnabled()) {
//        log.fine("removing tombstone with v0 rv0", new Exception("stack trace"));
//      }
//    }
    ReentrantLock regionLock = null;
    try {
        synchronized (re) {
          regionLock = this._getOwner().getSizeGuard();
          if (regionLock != null) {
            regionLock.lock();
          }
          int entryVersion = re.getVersionStamp().getEntryVersion();
          boolean isTombstone = re.isTombstone();
          boolean isSameTombstone = (entryVersion == destroyedVersion && isTombstone);
          if (isSameTombstone || (isTombstone && entryVersion < destroyedVersion)) {
            if (log.fineEnabled() || TombstoneService.DEBUG_TOMBSTONE_COUNT) {
              // logs are at info level for TomstoneService.DEBUG_TOMBSTONE_COUNT so customer doesn't have to use fine level
              if (isSameTombstone) {
                // logging this can put tremendous pressure on the log writer in tests
                // that "wait for silence"
                if (TombstoneService.DEBUG_TOMBSTONE_COUNT) {
                  log.info(LocalizedStrings.DEBUG,
                    "removing tombstone for " + re.getKeyCopy() + " with v"
                        + destroyedVersion + " rv" + version.getRegionVersion()
                        + "; count is " + (this._getOwner().getTombstoneCount() - 1));
                }
              } else {
                log.info(LocalizedStrings.DEBUG,
                    "removing entry (v" + entryVersion + ") that is older than an expiring tombstone (v"
                    + destroyedVersion + " rv" + version.getRegionVersion() + ") for " + re.getKeyCopy());
              }
            }
            try {
              re.setValue(_getOwner(), Token.REMOVED_PHASE2);
              if (removeTombstone(re)) {
                result = true;
                incEntryCount(-1);
                // Bug 51118: When the method is called by tombstoneGC thread, current 're' is an
                // expired tombstone. Then we detected an destroyed (due to overwritingOldTombstone() 
                // returns true earlier) tombstone with bigger entry version, it's safe to delete
                // current tombstone 're' and adjust the tombstone count. 
  //              lruEntryDestroy(re); // tombstones are invisible to LRU
                if (isScheduledTombstone) {
                  _getOwner().incTombstoneCount(-1);
                }
                _getOwner().getVersionVector().recordGCVersion(version.getMemberID(), version.getRegionVersion(), null);
              }
            } catch (RegionClearedException e) {
              // if the region has been cleared we don't need to remove the tombstone
            } catch (RegionDestroyedException e) {
              //if the region has been destroyed, the tombstone is already
              //gone. Catch an exception to avoid an error from the GC thread.
            }
          } else {
            if (TombstoneService.VERBOSE || TombstoneService.DEBUG_TOMBSTONE_COUNT) {
              log.info(LocalizedStrings.DEBUG,
                  "tombstone for " + re.getKeyCopy() + " was resurrected with v"
                  + re.getVersionStamp().getEntryVersion()
                  + "; destroyed version was v" + destroyedVersion
                  + "; count is " + this._getOwner().getTombstoneCount()
                  + "; entryMap size is " + sizeInVM());
            }
          }
        }
    } finally {
      if (regionLock != null) {
        regionLock.unlock();
      }
    }
    return result;
  }

  protected boolean removeTombstone(RegionEntry re) {
    return _getMap().remove(re.getKey(), re);
  }

  // method used for debugging tombstone count issues
  public boolean verifyTombstoneCount(AtomicInteger numTombstones) {
    Set<RegionEntry> deadEntries = new HashSet<RegionEntry>();
    Set<RegionEntry> hdfsDeadEntries = new HashSet<RegionEntry>();
    LocalRegion lr = _getOwner();
    boolean isBucket = lr.isUsedForPartitionedRegionBucket();
    boolean includeHDFSEntries = false;
//    boolean dumpMap = false;
    PartitionedRegion pr = null;
    if (isBucket) {
      pr = ((BucketRegion)lr).getPartitionedRegion();
      includeHDFSEntries = pr.isHDFSRegion() && pr.includeHDFSResults();
    }
    try {
      Set<Object> keys = new HashSet<Object>();
      Set<Object> hdfsKeys = null;
      deadEntries = getTombstones(Collections.EMPTY_SET, keys);
      if (includeHDFSEntries) {
        pr.setQueryHDFS(true);
        hdfsKeys = new HashSet<Object>();
        hdfsDeadEntries = getTombstones(keys, hdfsKeys);
        pr.setQueryHDFS(false);
      }
      if (deadEntries.size() != numTombstones.get()) {
        lr.getLogWriterI18n().info(LocalizedStrings.DEBUG, 
            "tombstone count (" + numTombstones
            + ") in " + lr.getName() + " does not match actual number of tombstones (" + deadEntries.size() + "): "
                + keys/*, new Exception()*/);
        if (includeHDFSEntries && hdfsDeadEntries.size() > 0) {
          lr.getLogWriterI18n().info(LocalizedStrings.DEBUG, 
              "hdfs additional tombstone count is " + (hdfsDeadEntries.size()) + " : " + hdfsKeys);
        } else if (!includeHDFSEntries) {
          lr.getLogWriterI18n().info(LocalizedStrings.DEBUG,
              "region is not using hdfs.");
          if (pr != null) {
            lr.getLogWriterI18n().info(LocalizedStrings.DEBUG, "pr.isHDFSRegion()=" + pr.isHDFSRegion()
                +"; pr.includeHDFSResults()="+pr.includeHDFSResults());
          }
        }
//        dumpMap = true;
        return false;
      } else {
        lr.getLogWriterI18n().info(LocalizedStrings.DEBUG,
            "tombstone count verified");
      }
    } catch (Exception e) {
      // ignore
    } finally {
      if (isBucket && !includeHDFSEntries) {
        pr.setQueryHDFS(false);
      }
//      if (dumpMap) {
//        dumpMap(lr.getLogWriterI18n());
//      }
    }
    return true;
  }

  private Set<RegionEntry> getTombstones(Set<Object> ignoreKeys, Set<Object> gatherKeys) {
    Set<RegionEntry> result = new HashSet<RegionEntry>();
    for (Iterator it=regionEntries().iterator(); it.hasNext(); ) {
      RegionEntry re = (RegionEntry)it.next();
      if (re.isTombstone() && !ignoreKeys.contains(re.getKey())) {
        result.add(re);
        gatherKeys.add(re.getKey());
      }
    }
    return result;
  }
    

  public final Map<Object, SuspectEntryList> getTestSuspectMap() {
    return this.suspectEntries;
  }
}
