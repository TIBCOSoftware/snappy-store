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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.lru.*;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;

/**
 * Abstract implementation of {@link RegionMap} that adds LRU behaviour.
 *
 * @since 3.5.1
 *
 * @author Darrel Schneider
 *
 */
public abstract class AbstractLRURegionMap extends AbstractRegionMap {
  protected abstract void _setCCHelper(EnableLRU ccHelper);
  protected abstract EnableLRU _getCCHelper();
  protected abstract void _setLruList(NewLRUClockHand lruList);
  protected abstract NewLRUClockHand _getLruList();
  
//  private Object lruCreatedKey;

  public static final boolean debug = Boolean.getBoolean("gemfire.verbose-lru");

  StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();

  private static volatile LogWriterI18n logWriter;

  private LRUAlgorithm evictionController;

  protected AbstractLRURegionMap(InternalRegionArguments internalRegionArgs) {
    super(internalRegionArgs);
  }

  protected void initialize(Object owner,
                            Attributes attr,
                            InternalRegionArguments internalRegionArgs) {

    super.initialize(owner, attr, internalRegionArgs, true/*isLRU*/);

    EvictionAlgorithm ea;
    LRUAlgorithm ec;
    if (owner instanceof LocalRegion) {
      ea = ((LocalRegion)owner).getEvictionAttributes().getAlgorithm();
      ec = ((LocalRegion)owner).getEvictionController();
    } else if (owner instanceof PlaceHolderDiskRegion) {
      PlaceHolderDiskRegion phdr = (PlaceHolderDiskRegion)owner;
      ea = phdr.getActualLruAlgorithm();
      ec = phdr.getEvictionAttributes().createEvictionController(null, phdr.getEnableOffHeapMemory());
    } else {
      throw new IllegalStateException("expected LocalRegion or PlaceHolderDiskRegion");
    }
    this.evictionController = ec;
    
    if (ea.isLRUMemory())  {
      ((MemLRUCapacityController) ec).setEntryOverHead(getEntryOverHead()); 
    }
    if (ea.isLRUHeap())  {
      ((HeapLRUCapacityController) ec).setEntryOverHead(getEntryOverHead()); 
    }    
    _setCCHelper(getHelper(ec));
    
    /*
     * modification for LIFO Logic incubation
     * 
     */
    if (ea == EvictionAlgorithm.LIFO_ENTRY || ea == EvictionAlgorithm.LIFO_MEMORY ) {
      _setLruList(new NewLIFOClockHand(owner, _getCCHelper(), internalRegionArgs));
    }
    else {
      _setLruList(new NewLRUClockHand(owner, _getCCHelper(), internalRegionArgs));
    }
    
    if (debug && logWriter == null) {
      logWriter = InternalDistributedSystem.getLoggerI18n();
    }
  }

  @Override
  public void changeOwner(LocalRegion r, InternalRegionArguments args) {
    super.changeOwner(r, args);
    _getLruList().setBucketRegion(r);
    this.evictionController.setBucketRegion(r);
  }

  /** The delta produced during a put for activating LRU cannot be used while some
   * outside party (LocalRegion) has a segment locked... so we'll keep it in a thread
   * local for a callback after the segment is released.
   */
  private final ThreadLocal lruDelta = new ThreadLocal();
  private final ThreadLocal mustRemove = new ThreadLocal();
  private final ThreadLocal callbackDisabled = new ThreadLocal();
  private final ThreadLocal mustRemoveDebug = debug ? new ThreadLocal() : null;

  private int getDelta( ) {
    Object d = lruDelta.get();
    lruDelta.set(null);         // We only want the delta consumed once
    if ( d == null ) return 0;
    return ((Integer)d).intValue();
  }

  private void setDelta( int delta ) {
    if (getCallbackDisabled()) {
      Integer delt = (Integer) lruDelta.get();
      if (delt != null) {
//        _getOwner().getLogWriterI18n().severe(LocalizedStrings.DEBUG, "DEBUG: delta not null", new Exception("stack trace"));
        delta += delt.intValue();
      }
    } else {
      if (getMustRemove()) {
        // after implementation of network-partition-detection we may
        // run into situations where a cache listener performs a cache
        // operation that needs to update LRU stats.  In order to do this
        // we first have to execute the previous LRU actions.
        lruUpdateCallback();
      }
      setMustRemove( true );
    }
    lruDelta.set( Integer.valueOf( delta ) );
  }


  /**
   * Marker class to indicate that the wrapped value
   * is owned by a CachedDeserializable and its form is
   * changing from serialized to deserialized.
   */
  public static class CDValueWrapper {
    private final Object v;
    CDValueWrapper(Object v) {
      this.v = v;
    }
    public Object getValue() {
      return this.v;
    }
  }
  
  /**
   * Used when a CachedDeserializable's value changes form.
   * PRECONDITION: caller has le synced
   * @param le the entry whose CachedDeserializable's value changed.
   * @param cd the CachedDeserializable whose form has changed
   * @param v the new form of the CachedDeserializable's value.
   * @return true if finishExpandValue needs to be called
   */
  public boolean beginChangeValueForm(LRUEntry le, CachedDeserializable cd, Object v) {
    // make sure this cached deserializable is still in the entry
    // @todo what if a clear is done and this entry is no longer in the region?
//    if (_getOwner().getLogWriterI18n().finerEnabled()) {
//      _getOwner().getLogWriterI18n().finer("beginChangeValueForm value = " + v);
//    }
    {
      Object curVal = le._getValue(); // OFFHEAP: _getValue ok
      if (curVal != cd) {
        if (cd instanceof StoredObject) {
          if (!cd.equals(curVal)) {
            return false;
          }
        } else {
          return false;
        }
      }
    }
    // TODO:KIRK:OK if (le.getValueInVM((RegionEntryContext) _getOwnerObject()) != cd) return false;
    boolean result = false;
    int delta = le.updateEntrySize(_getCCHelper(), new CDValueWrapper(v));
//     _getOwner().getCache().getLogger().info("DEBUG: changeValueForm delta=" + delta);
    if (delta != 0) {
      result = true;
      boolean needToDisableCallbacks = !getCallbackDisabled();
      if (needToDisableCallbacks) {
        setCallbackDisabled(true);
      }
      // by making sure that callbacks are disabled when we call
      // setDelta; it ensures that the setDelta will just inc the delta
      // value and not call lruUpdateCallback which we call in
      // finishChangeValueForm
      setDelta(delta);
      if (needToDisableCallbacks) {
        setCallbackDisabled(false);
      }
    }
    // fix for bug 42090
    if (_getCCHelper().getEvictionAlgorithm().isLRUHeap()
        && _getOwnerObject() instanceof BucketRegion
        && HeapEvictor.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST) {
      result = false;
    }
    return result;
  }

  public void finishChangeValueForm() {
    lruUpdateCallback();
  }
  
  private boolean getMustRemove( ) {
    Object d = mustRemove.get();
    if ( d == null ) return false;
    return ((Boolean)d).booleanValue();
  }

  private void setMustRemove( boolean b ) {
    mustRemove.set( b ? Boolean.TRUE : Boolean.FALSE );
     if (b) {
       if (debug) {
         Exception ex = new Exception(LocalizedStrings.AbstractLRURegionMap_SET_MUSTREMOVE_TO_TRUE.toLocalizedString());
         ex.fillInStackTrace();
         mustRemoveDebug.set(ex);
       }
     } else {
       mustRemove.set(null);
     }
  }
  private boolean getCallbackDisabled( ) {
    Object d = callbackDisabled.get();
    if ( d == null ) return false;
    return ((Boolean)d).booleanValue();
  }

  private void setCallbackDisabled( boolean b ) {
    callbackDisabled.set( b ? Boolean.TRUE : Boolean.FALSE );
  }
  /**
   * Returns the size of entries in this entries map
   */
  public int getEntryOverHead() {
    RegionEntryFactory f = getEntryFactory();
    if (f == null) { // fix for bug 31758
      // SM initializes the entry factory late; when setOwner is called
      // because it is an unshared field.
      return 0;
    } else {
      return (int) ReflectionSingleObjectSizer.sizeof(f.getEntryClass());
    }
  }
  
  /** 
   * This method is only for debugging messages, that are not intended to be seen by the customer.
   * It is expected that every invocation of this method is guarded by <code>if(debug)</code>
   */
  protected static void debugLogging( String msg ) {
    if (logWriter != null) {
      logWriter.info( LocalizedStrings.DEBUG, msg );
    }
  }

  /** 
   * This method is only for debugging messages, that are not intended to be seen by the customer.
   * It is expected that every invocation of this method is guarded by <code>if(debug)</code>
   */
  protected static void debugLogging( String msg, Exception e ) {
    if (logWriter != null) {
      logWriter.info( LocalizedStrings.DEBUG, msg, e );
    }
  }

  /** unsafe audit code. */
  public final void audit( ) {
    if (debug) debugLogging( "Size of LRUMap = " + sizeInVM() );
    _getLruList().audit();
  }

  /**
   * Evicts the given entry from the cache. Returns the total number of bytes
   * evicted. 
   * 1. For action local destroy, returns size(key + value)
   * 2. For action evict to disk, returns size(value)
   * @return number of bytes evicted, zero if no eviction took place
   */
  protected int evictEntry(LRUEntry entry, LRUStatistics stats) throws RegionClearedException  {
    EvictionAction action = _getCCHelper().getEvictionAction();
    LocalRegion region = _getOwner();
    if (action.isLocalDestroy()) {
      return handleLocalDestroy(entry);
    } else if (action.isOverflowToDisk()) {
      Assert.assertTrue(entry instanceof DiskEntry);
      int change = 0;
      synchronized (entry) {
        /*
        if (entry.getRefCount() > 0) {
          entry.unsetEvicted();
          if (debug) {
            debugLogging("No eviction of transactional entry for key="
                + entry.getKey());
          }
          return 0;
        }
        */

        // Do the following check while synchronized to fix bug 31761
        if (entry.isInvalidOrRemoved()) {
          // no need to evict these; it will not save any space
          // and the destroyed token needs to stay in memory
          if (debug) {
            debugLogging("no need to evict invalid/localInvalid/destroyed "
                + "token for key=" + entry.getKeyCopy());
          }
          return 0;
        }
        entry.setEvicted();
        change = DiskEntry.Helper.overflowToDisk((DiskEntry)entry, region, _getCCHelper());
      }
      boolean result = change < 0;
      if (result) {
        
        if (_getOwner() instanceof BucketRegion) {
          BucketRegion bucketRegion = (BucketRegion)_getOwner();
          bucketRegion.updateCounter(change);
          //if(bucketRegion.getBucketAdvisor().isPrimary()){
            stats.updateCounter(change);
         // }
        }
        else {
          stats.updateCounter(change);
        }

      } else {
        if ( debug ) debugLogging( "no need to evict token for key="
                          + entry.getKeyCopy()
                          + " because moving its value to disk resulted in a net change of "
                          + change + " bytes.");
       }
      return change * -1;

    } else {
      throw new InternalGemFireException(LocalizedStrings.AbstractLRURegionMap_UNKNOWN_EVICTION_ACTION_0.toLocalizedString(action));
    }
  }

  /**
   * Broken-out so that it can be overridden
   */
  protected int handleLocalDestroy(LRUEntry entry) {
    assert _getCCHelper().getEvictionAction().isLocalDestroy();
    int size = entry.getEntrySize();
    if (_getOwner().evictDestroy(entry)) {
      return size;
    } else {
      return 0;
    }
  }
  
  /**
   *  update the running counter of all the entries
   *
   * @param  delta  Description of the Parameter
   */
  protected final void changeTotalEntrySize(int delta) {
    if (_getOwnerObject() instanceof BucketRegion) {
      BucketRegion bucketRegion = (BucketRegion)_getOwnerObject();
      bucketRegion.updateCounter(delta);
    }
    _getLruList().stats().updateCounter(delta);

    if ( debug && delta > 0 ) {
      debugLogging( "total lru size is now: " + getTotalEntrySize() );
    }
  }

  /**
   *  access the getHelper method on the eviction controller to initialize
   *  the ccHelper field.
   *
   * @param  ec  The governing eviction controller.
   * @return     the helper instance from the eviction controller.
   */
  private static EnableLRU getHelper( LRUAlgorithm ec ) {
    return ec.getLRUHelper();
  }
  @Override
  public void evictValue(Object key) {
    throw new IllegalStateException("The evictValue is not supported on regions with eviction attributes.");
  }

  /**
   *  Gets the total entry size limit for the map from the capacity
   *  controller helper.
   *
   * @return    The total allowable size of this maps entries.
   */
  protected final long getLimit() {
    if (_getOwner() instanceof BucketRegion) {
      BucketRegion bucketRegion = (BucketRegion)_getOwner();
      return bucketRegion.getLimit();
    }
    return _getLruList().stats().getLimit();
  }
  
  public final LRUStatistics getLRUStatistics() {
    return _getLruList().stats();
  }


  /**
   * return the current size of all the entries.
   * 
   * @return The current size of all the entries.
   */
  protected final long getTotalEntrySize() {
    if (_getOwnerObject() instanceof BucketRegion) {
      BucketRegion bucketRegion = (BucketRegion)_getOwner();
      return bucketRegion.getCounter();
    }
    return _getLruList().stats().getCounter();
  }

  @Override
  public final void lruUpdateCallback() {
    if (getCallbackDisabled()) {
      return;
    }
    final int delta = getDelta();
    int bytesToEvict = delta;
    resetThreadLocals();
    final NewLRUClockHand lruList = _getLruList();
    LocalRegion owner = null;
    LRUEntry removalEntry = null;
    if (_isOwnerALocalRegion()) {
      owner = _getOwner();
    }
    if (debug && owner != null) {
      debugLogging("lruUpdateCallback"
          + "; list size is: " + getTotalEntrySize()
          + "; actual size is: " + lruList.getExpensiveListCount()
          + "; map size is: " + sizeInVM()
          + "; delta is: " + delta + "; limit is: " + getLimit()
          + "; tombstone count=" + owner.getTombstoneCount()
          /*+ "; tokens=" + _getOwner().getImageState().getDestroyedEntriesCount()*/);
//      debugLogging(_getLruList().getAuditReport());  // this is useful to see if there are problems with the counter
      //if (size > 90) {
      //  _getLruList().dumpList(logWriter);
      //}
    }
    LRUStatistics stats = lruList.stats();
    if (owner == null) {
      changeTotalEntrySize(delta);
      // instead of evicting we just quit faulting values in
    } else
    if (_getCCHelper().getEvictionAlgorithm().isLRUHeap()) {
      changeTotalEntrySize(delta);
      final ArrayList<LRUClockNode> skipped = new ArrayList<>(2);
      // suspend tx otherwise this will go into transactional size read etc.
      // again leading to deadlock (#44081, #44175)
      TXStateInterface tx = null;
      TXManagerImpl txMgr = null;
      if (owner != null && owner.getCache() != null) {
        txMgr = owner.getCache().getCacheTransactionManager();
        tx = txMgr.internalSuspend();
      }
      try {
          while (bytesToEvict > 0 && _getCCHelper().mustEvict(stats, _getOwner(), bytesToEvict)) {
            boolean evictFromThisRegion = true;
          if (HeapEvictor.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST && owner instanceof BucketRegion) {
            long bytesEvicted = 0;
            long totalBytesEvicted = 0;
            List<BucketRegion> regions = ((BucketRegion)owner)
                .getPartitionedRegion().getSortedBuckets();
            Iterator<BucketRegion> iter = regions.iterator();
            while (iter.hasNext()) {
              BucketRegion region = iter.next();
              //only secondaries can trigger inline eviction fix for 49694 
          	  if(!region.getBucketAdvisor().isPrimary()){       	
              try {
                bytesEvicted = ((AbstractLRURegionMap)region.entries)
                    .centralizedLruUpdateCallback(false, true);
                if (bytesEvicted == 0) {
                  iter.remove();
                } else {
                  evictFromThisRegion = false;
                }
                totalBytesEvicted += bytesEvicted;
                bytesToEvict -= bytesEvicted;
                if(bytesEvicted > bytesToEvict){
                  bytesToEvict = 0;
                  break;
                }                  
                if(totalBytesEvicted > bytesToEvict) {
                  break;
                }
              }
              catch (RegionDestroyedException rd) {
                region.cache.getCancelCriterion().checkCancelInProgress(rd);
              }
              catch (Exception e) {
                region.cache.getCancelCriterion().checkCancelInProgress(e);
                region.cache.getLoggerI18n().warning(
                    LocalizedStrings.Eviction_EVICTOR_TASK_EXCEPTION,
                    new Object[] { e.getMessage() }, e);
              }
            }
            }
          }
          if(evictFromThisRegion) {
            // skip locked entries in LRU and keep the lock till evictEntry (SNAP-2041)
            removalEntry = (LRUEntry)lruList.getLRUEntry(skipped);
            if (removalEntry != null) {
              int sizeOfValue = evictEntry(removalEntry, stats);
              if (sizeOfValue != 0) {
                bytesToEvict -= sizeOfValue;
                if (debug) {
                  debugLogging("evicted entry key=" + removalEntry.getKeyCopy()
                      + " total entry size is now: " + getTotalEntrySize()
                      + " bytesToEvict :" + bytesToEvict);
                }
                stats.incEvictions();
                if (owner instanceof BucketRegion) {
                  ((BucketRegion)owner).incEvictions(1);
                }
                if (debug)
                  debugLogging("evictions=" + stats.getEvictions());
                _getCCHelper().afterEviction();
              }
              // release the lock held on by getLRUEntry
              UnsafeHolder.monitorExit(removalEntry);
              removalEntry = null;
            }
            else {
              if (debug && getTotalEntrySize() != 0) {
                debugLogging("leaving evict loop early");
              }
              break;
            }
          }
        }
      }
      catch (RegionClearedException e) {
        // TODO Auto-generated catch block
        if (debug) debugLogging("exception =" + e.getCause());
      } finally {
        // release the extra lock by getLRUEntry if remaining
        if (removalEntry != null) {
          UnsafeHolder.monitorExit(removalEntry);
        }
        if (tx != null) {
          txMgr.resume(tx);
        }
        final int numSkipped = skipped.size();
        for (int i = 0; i < numSkipped; i++) {
          lruList.appendEntry(skipped.get(i));
        }
      }
    }
    else {
      final ArrayList<LRUClockNode> skipped = new ArrayList<>(2);
      // suspend tx otherwise this will go into transactional size read etc.
      // again leading to deadlock (#44081, #44175)
      TXStateInterface tx = null;
      TXManagerImpl txMgr = null;
      if (owner != null && owner.getCache() != null) {
        txMgr = owner.getCache().getCacheTransactionManager();
        tx = txMgr.internalSuspend();
      }
      try {
        // to fix bug 48285 do no evict if bytesToEvict <= 0.
        while (bytesToEvict > 0 && _getCCHelper().mustEvict(stats, _getOwner(), bytesToEvict)) {
          // skip locked entries in LRU and keep the lock till evictEntry (SNAP-2041)
          removalEntry = (LRUEntry)lruList.getLRUEntry(skipped);
          if (removalEntry != null) {
            if (evictEntry(removalEntry, stats) != 0) {
              if (debug) {
                debugLogging("evicted entry key(2)=" + removalEntry.getKey()
                    + " total entry size is now: " + getTotalEntrySize()
                    + " bytesToEvict :" + bytesToEvict);
//                if (NewLRUClockHand.debug && logWriter != null) {
//                  _getLruList().dumpList(logWriter);
//                }
              }
              stats.incEvictions();
              if (owner instanceof BucketRegion) {
                ((BucketRegion)owner).incEvictions(1);
              }
              if (debug)
                debugLogging("evictions=" + stats.getEvictions());
              _getCCHelper().afterEviction();

            }
            // release the lock held on by getLRUEntry
            UnsafeHolder.monitorExit(removalEntry);
            removalEntry = null;
          }
          else {
            if (debug && getTotalEntrySize() != 0) {
              debugLogging("leaving evict loop early");
            }
            break;
          }
        }
        changeTotalEntrySize(delta);
      }
      catch (RegionClearedException e) {
        // TODO Auto-generated catch block
        if (debug) debugLogging("exception =" + e.getCause());
      } finally {
        // release the extra lock by getLRUEntry if remaining
        if (removalEntry != null) {
          UnsafeHolder.monitorExit(removalEntry);
        }
        if (tx != null) {
          txMgr.resume(tx);
        }
        final int numSkipped = skipped.size();
        for (int i = 0; i < numSkipped; i++) {
          lruList.appendEntry(skipped.get(i));
        }
      }
    }
    if (debug)
      debugLogging("callback complete.  LRU size is now " + stats.getCounter());
    // If in transaction context (either local or message)
    // reset the tx thread local
 } 
  
  private boolean mustEvict() {
    if(callback.isSnappyStore()){
      return this.sizeInVM() > 0;
    }
    LocalRegion owner = _getOwner();
    InternalResourceManager resourceManager = owner.getCache().getResourceManager();
    
    final boolean monitorStateIsEviction;
    if (!owner.getAttributes().getEnableOffHeapMemory()) {
      monitorStateIsEviction = resourceManager.getHeapMonitor().getState().isEviction();
    } else {
      monitorStateIsEviction = resourceManager.getOffHeapMonitor().getState().isEviction();
    }
    
    return monitorStateIsEviction && this.sizeInVM() > 0;
  }

  private void monitorExit(LRUEntry entry, SerializedDiskBuffer buffer) {
    if (buffer != null) {
      UnsafeHolder.monitorExit(buffer);
    }
    UnsafeHolder.monitorExit(entry);
  }

  /**
   * Evict an entry as per LRU and return a long value having heap bytes
   * evicted in the LSB integer, and the (new SerializedDiskBuffer) off-heap
   * evicted bytes in the MSB integer if "includeOffHeapBytes" is true.
   */
  public final long centralizedLruUpdateCallback(boolean includeOffHeapBytes,
      boolean skipLockedEntries) {
    long evictedBytes = 0;
    int offHeapSize = 0;
    if (getCallbackDisabled()) {
      return evictedBytes;
    }
    getDelta();
    resetThreadLocals();
    if (debug) {
      debugLogging("centralLruUpdateCallback: lru size is now "
          + getTotalEntrySize());
      debugLogging("limit is: " + getLimit());
    }
    final NewLRUClockHand lruList = _getLruList();
    final ArrayList<LRUClockNode> skipped = skipLockedEntries
        ? new ArrayList<>(2) : null;
    LRUStatistics stats = lruList.stats();
    LRUEntry removalEntry = null;
    SerializedDiskBuffer buffer = null;
    try {
      while (mustEvict() && (evictedBytes == 0 ||
          (includeOffHeapBytes && offHeapSize == 0))) {
        buffer = null;
        removalEntry = (LRUEntry)lruList.getLRUEntry(skipped);
        if (removalEntry != null) {
          // get the handle to off-heap entry before eviction
          if (includeOffHeapBytes && !removalEntry.isOffHeap()) {
            // add off-heap size to the MSB of evictedBytes
            Object value = removalEntry._getValue();
            if (value instanceof SerializedDiskBuffer) {
              buffer = (SerializedDiskBuffer)value;
            }
          }
          if (skipLockedEntries && buffer != null) {
            if (!UnsafeHolder.tryMonitorEnter(buffer, true)) {
              UnsafeHolder.monitorExit(removalEntry);
              skipped.add(removalEntry);
              removalEntry = null;
              continue;
            }
          }
          int evicted = evictEntry(removalEntry, stats);
          if (skipLockedEntries) {
            // release the lock held on by getLRUEntry
            monitorExit(removalEntry, buffer);
            removalEntry = null;
          }
          evictedBytes += evicted;
          if (evicted != 0) {
            // check if off-heap entry was evicted
            if (buffer != null && buffer.referenceCount() <= 0) {
              offHeapSize += buffer.getOffHeapSizeInBytes();
            }
            Object owner = _getOwnerObject();
            if (owner instanceof BucketRegion) {
              ((BucketRegion)owner).incEvictions(1);
            }
            stats.incEvictions();
            if (debug)
              debugLogging("evictions=" + stats.getEvictions());
            _getCCHelper().afterEviction();
          }
        }
        else {
          if (debug && getTotalEntrySize() != 0) {
            debugLogging("leaving evict loop early");
          }
          break;
        }
      }
    } catch (RegionClearedException rce) {
      // Ignore
      if (debug) debugLogging("exception =" + rce.getCause());
    } finally {
      // release the extra lock by getLRUEntry if remaining
      if (skipLockedEntries) {
        if (removalEntry != null) {
          monitorExit(removalEntry, buffer);
        }
        // add back any skipped entries due to locks to LRU list
        final int numSkipped = skipped.size();
        for (int i = 0; i < numSkipped; i++) {
          lruList.appendEntry(skipped.get(i));
        }
      }
    }
    if (debug)
      debugLogging("callback complete");
    // Return the heap evicted bytes and (SerializedDiskBuffer) off-heap
    // evicted bytes ORed. If "includeOffHeapBytes" parameter is false
    // then latter is zero.
    return (evictedBytes | (((long)offHeapSize) << 32L));
  }
  
 
  
  /**
   * Update counter related to limit in list
   * 
   * @since 5.7
   */
  // TODO this method acts as LRUupdateCallbacks
  // do we need to put it here are insert one level up
  public final void updateStats() {
    final int delta = getDelta();
    resetThreadLocals();
    if (debug) {
      debugLogging("delta is: " + delta);
      debugLogging("total is: " + getTotalEntrySize());
      debugLogging("limit is: " + getLimit());
      debugLogging("Call while faulting from disk delta ::"+delta);
    }

    if (delta != 0) {
      changeTotalEntrySize(delta);
    }
  }
  
  @Override
  public final boolean disableLruUpdateCallback() {
    if (getCallbackDisabled()) {
      return false;
    } else {
      setCallbackDisabled(true);
      return true;
    }
  }
  @Override
  public final void enableLruUpdateCallback() {
    setCallbackDisabled(false);
  }
  //TODO rebalancing these methods are new on the
  //rebalancing branch but never used???
  public final void disableLruUpdateCallbackForInline() {
    setCallbackDisabled(true);
  }
  public final void enableLruUpdateCallbackForInline() {
    setCallbackDisabled(false);
  }
  
  @Override
  public final void resetThreadLocals() {
    mustRemove.set(null);
    lruDelta.set(null);
    callbackDisabled.set(null);
  }

  @Override
  public final Set<VersionSource> clear(RegionVersionVector rvv)
  {
    _getLruList().clear(rvv);
    return super.clear(rvv);
  }
  
  /*Asif :
   * Motivation: An entry which is no longer existing in the system due to clear
   * operation, should not be present the LRUList being used by the region.
   * 
   * Case1 : An entry has been written to disk & on its return code path, it 
   * invokes lruCreate or lruUpdate. Before starting the operation of writing to disk,
   * the HTree reference is set in the threadlocal. A clear operation changes
   * the Htree reference in a write lock.
   * Thus if the htree reference has not changed till this point, it would mean either
   * the entry is still valid or a clear operation is in progress but has not changed the 
   * Htree Reference . Since we store the LRUList in a local variable, it implies that if 
   * clear occurs , it will go in the stale list & if not it goes in the right list.
   * Both ways we are safe.
   * 
   * Case 2: The Htree reference has changed ( implying a clear conflic with put)
   *  but the entry is valid. This is possible as we first set the Htree Ref in thread local.
   *  Now before the update operation has acquired the entry , clear happens. As a result
   *  the update operation has become create. Since the clear changes the Htree Ref & the LRUList
   *  in a write lock & hence by the time the original update operation acquires the read lock,
   *  the LRUList  has already been changed by clear. Now in the update operation's return path
   *  the List which it stores in local variable is bound to be the new List. 
   *  Since our code checks if the entry reference exists in the region in case of conflict
   *  & if yes, we append the entry to the List. It is guaranteed to be added to the 
   *  new List.
   *  
   *    Also it is necessary that when we clear the region, first the concurrent map of the region
   *    containing entries needs to be cleared. The Htree Reference should be reset after 
   *    that. And then we should be resetting the LRUList. 
   *    Previously the Htree  reference was being set before clearing the Map. This caused
   *    Bug 37606. 
   *    If the order of clear operation on disk region is ( incorrect ) 
   *    1) map.clear 2) Resetting the LRUList 3) Changing the Htree ref 
   *    Then following bug  can occur.,
   *    During entry operation on its return path, invokes lruUpdate/lruCreate. 
   *    By that time the clear proceeds & it has reset the LRUList & cleared the entries.
   *    But as the Htree ref has not changed, we would take the locally available LRUList
   *    ( which may be the new List) & append the entry to the List.
   *    
   * 
   * 
  */
  @Override
  protected final void lruEntryCreate(RegionEntry re) {
    LRUEntry e = (LRUEntry)re;
    // Assert.assertFalse(e._getValue() instanceof DiskEntry.RecoveredEntry)
    if ( debug ) {
      debugLogging( "lruEntryCreate for key=" + re.getKeyCopy() 
          + "; list size is: " + getTotalEntrySize()
          + "; actual size is: " + this._getLruList().getExpensiveListCount()
          + "; map size is: " + sizeInVM()
          + "; entry size: " + e.getEntrySize()
          + "; in lru clock: " + !e.testEvicted());
    }
//    this.lruCreatedKey = re.getKey(); // [ bruce ] for DEBUGGING only
    e.unsetEvicted();
    NewLRUClockHand lruList = _getLruList();   
    DiskRegion disk =  _getOwner().getDiskRegion();
    boolean possibleClear = disk != null && disk.didClearCountChange();
    if(!possibleClear || this._getOwner().basicGetEntry(re.getKey()) == re ) {
      lruList.appendEntry(e);
      lruEntryUpdate(e);
    }    
  }
  
  @Override
  protected final void lruEntryUpdate(RegionEntry re ) {
    final LRUEntry e = (LRUEntry)re;
    setDelta(e.updateEntrySize(_getCCHelper()));
    if (debug) debugLogging("lruEntryUpdate for key=" + re.getKeyCopy()
          + " size=" + e.getEntrySize());
    NewLRUClockHand lruList = _getLruList();
    if (_isOwnerALocalRegion()) {
      LocalRegion owner = _getOwner();
      DiskRegion disk = owner.getDiskRegion();
      boolean possibleClear = disk != null && disk.didClearCountChange();
      if (!possibleClear || owner.basicGetEntry(re.getKey()) == re) {
        if (e instanceof DiskEntry) {
          if (!e.testEvicted()) {
            lruList.appendEntry(e);
          }
        }
        //e.resetRefCount(lruList);
      }
    } else {
      // We are recovering the region so it is a DiskEntry.
      // Also clear is not yet possible and this entry will be in the region.
      // No need to call resetRefCount since tx are not yet possible.
      if (!e.testEvicted()) {
        lruList.appendEntry(e);
      }
    }
  }
  @Override
  protected final void lruEntryDestroy(RegionEntry re) {
    final LRUEntry e = (LRUEntry)re;
    if ( debug ) {
      debugLogging( "lruEntryDestroy for key=" + re.getKeyCopy() 
          + "; list size is: " + getTotalEntrySize()
          + "; actual size is: " + this._getLruList().getExpensiveListCount()
          + "; map size is: " + sizeInVM()
          + "; entry size: " + e.getEntrySize()
          + "; in lru clock: " + !e.testEvicted());
    }
//    if (this.lruCreatedKey == re.getKey()) {
//      String method = Thread.currentThread().getStackTrace()[5].getMethodName(); 
//      if (logWriter != null && !method.equals("destroyExistingEntry")) {
//        logWriter.fine("   ("+method+") evicting a key that was just added", new Exception("stack trace"));
//        _getLruList().dumpList(logWriter);
//        this._getOwner().dumpBackingMap();
//      }
//    }
//    boolean wasEvicted = e.testEvicted();
    /*boolean removed = */_getLruList().unlinkEntry(e);
//    if (removed || wasEvicted) { // evicted entries have already been removed from the list
    changeTotalEntrySize(-1 * e.getEntrySize());// subtract the size.
    Token vTok = re.getValueAsToken();
    if (vTok == Token.DESTROYED || vTok == Token.TOMBSTONE) { // OFFHEAP noop TODO: use re.isDestroyedOrTombstone
      // if in token mode we need to recalculate the size of the entry since it's
      // staying in the map and may be resurrected
      e.updateEntrySize(_getCCHelper());
    }
//    } else if (debug) {
//      debugLogging("entry not removed from LRU list");
//    }

  }
  /** Called by DiskEntry.Helper.faultInValue
   */
  @Override
  public final void lruEntryFaultIn(LRUEntry e) {
    if (debug)
      debugLogging("lruEntryFaultIn for key=" + e.getKeyCopy() + " size="
          + e.getEntrySize());
    NewLRUClockHand lruList = _getLruList();
    if (_isOwnerALocalRegion()) {
      LocalRegion owner = _getOwner();
      DiskRegion disk = owner.getDiskRegion();
      boolean possibleClear = disk != null && disk.didClearCountChange();
      if (!possibleClear || owner.basicGetEntry(e.getKey()) == e) {
        lruEntryUpdate(e);
        e.unsetEvicted();
        lruList.appendEntry(e);
      }
    } else {
      lruEntryUpdate(e);
      lruList.appendEntry(e);
    }
    lruList.stats().incFaultins();
  }

  /*
  @Override
  public final void lruDecRefCount(RegionEntry re) {
    ((LRUEntry) re).decRefCount(_getLruList());
  }
  */

  @Override
  public final boolean lruLimitExceeded() {
    return _getCCHelper().mustEvict(_getLruList().stats(), null, 0);
  }
  
  @Override
  public void lruCloseStats() {
    _getLruList().closeStats();
  }

  @Override
  final boolean confirmEvictionDestroy(RegionEntry re) {
    // We assume here that a LRURegionMap contains LRUEntries
    LRUEntry lruRe = (LRUEntry) re;
    // TODO: TX: currently skip LRU processing for a transactionally locked
    // entry since value is already in TXState so no use of evicting it until we
    // have on-disk TXStates
    if (ExclusiveSharedSynchronizer.isWrite(lruRe.getState())
        || lruRe.isDestroyed()) {
      lruRe.unsetEvicted();
      return false;
    } else {
      return true;
    }
  }

  @Override
  protected long estimateChildrenMemoryOverhead(SingleObjectSizer sizer) {
    return sizer.sizeof(evictionController) + super.estimateChildrenMemoryOverhead(sizer);
  }
}
