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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheWriter;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.lru.LRUEntry;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionHolder;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;

/**
 * Internal implementation of {@link RegionMap}for regions whose DataPolicy is
 * proxy. Proxy maps are always empty.
 * 
 * @since 5.0
 * 
 * @author Darrel Schneider
 *  
 */
public final class ProxyRegionMap implements RegionMap {

  /** An internal Listener for index maintenance for GemFireXD. */
  private IndexUpdater indexUpdater;

  protected ProxyRegionMap(LocalRegion owner, Attributes attr,
      InternalRegionArguments internalRegionArgs) {
    this.owner = owner;
    this.attr = attr;
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

  /**
   * the region that owns this map
   */
  private final LocalRegion owner;

  private final Attributes attr;

  public RegionEntryFactory getEntryFactory()
  {
    throw new UnsupportedOperationException();
  }

  public Attributes getAttributes() {
    return this.attr;
  }

  public void setOwner(Object r) {
    throw new UnsupportedOperationException();
  }

  public void changeOwner(LocalRegion r, InternalRegionArguments args) {
    throw new UnsupportedOperationException();
  }

  public int size() {
    return 0;
  }

  public boolean isEmpty() {
    return true;
  }

  public Set keySet() {
    return Collections.EMPTY_SET;
  }

  public Collection<RegionEntry> regionEntries() {
    return Collections.emptySet();
  }

  @Override
  public Collection<RegionEntry> regionEntriesInVM() {
    return Collections.emptySet();
  }

  public boolean containsKey(Object key) {
    return false;
  }

  public RegionEntry getEntry(Object key) {
    return null;
  }

  public RegionEntry putEntryIfAbsent(Object key, RegionEntry re) {
    return null;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  public Set<VersionSource> clear(RegionVersionVector rvv) {
    // nothing needs to be done
    RegionVersionVector v = this.owner.getVersionVector();
    if (v != null) {
      return v.getDepartedMembersSet();
    } else {
      return Collections.emptySet();
    }
  }

  public void diskClear() {
    // nothing needs to be done
  }

  public RegionEntry initRecoveredEntry(Object key, DiskEntry.RecoveredEntry value) {
    throw new UnsupportedOperationException();
  }

  public RegionEntry updateRecoveredEntry(Object key, RegionEntry re,
      DiskEntry.RecoveredEntry value) {
    throw new UnsupportedOperationException();
  }

  /**
   * Used to modify an existing RegionEntry or create a new one when processing
   * the values obtained during a getInitialImage.
   */
  public boolean initialImagePut(Object key, long lastModified, Object newValue,
      boolean wasRecovered, boolean deferLRUCallback, VersionTag version, InternalDistributedMember sender, boolean forceValue)
  {
    throw new UnsupportedOperationException();
  }

  public boolean destroy(EntryEventImpl event, 
                         boolean inTokenMode,
                         boolean duringRI,
                         boolean cacheWrite,
                         boolean isEviction,
                         Object expectedOldValue,
                         boolean removeRecoveredEntry) 
  throws CacheWriterException, EntryNotFoundException, TimeoutException {
    if (event.getOperation().isLocal()) {
      throw new EntryNotFoundException(event.getKey().toString());
    }
    if (cacheWrite) {
      this.owner.cacheWriteBeforeDestroy(event, expectedOldValue);
    }
    owner.recordEvent(event);
    this.owner.basicDestroyPart2(markerEntry, event, inTokenMode, false /*Clear conflict occured */, duringRI, true);
    this.owner.basicDestroyPart3(markerEntry, event, inTokenMode, duringRI, true, expectedOldValue);
    return true;
  }

  public boolean invalidate(EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry, boolean forceCallback)
      throws EntryNotFoundException {
    
    if (event.getOperation().isLocal()) {
      throw new EntryNotFoundException(event.getKey().toString());
    }
    this.owner.cacheWriteBeforeInvalidate(event, invokeCallbacks, forceNewEntry);
    this.owner.recordEvent(event);
    this.owner.basicInvalidatePart2(markerEntry, event, false /*Clear conflict occurred */, true);
    this.owner.basicInvalidatePart3(markerEntry, event, true);
    return true;
  }

  public void evictEntry(Object key) {
    // noop
  }
  public void evictValue(Object key) {
    // noop
  }

  /**
   * Used by basicPut to signal the caller that the put was successful.
   */
  static final RegionEntry markerEntry = new ProxyRegionEntry();

  public RegionEntry basicPut(EntryEventImpl event,
                              long lastModified,
                              boolean ifNew,
                              boolean ifOld,
                              Object expectedOldValue,
                              boolean requireOldValue,
                              boolean overwriteDestroyed)
  throws CacheWriterException, TimeoutException {
    if (!event.isOriginRemote() && event.getOperation() != Operation.REPLACE) { // bug 42167 - don't convert replace to CREATE
      event.makeCreate();
    }
    final CacheWriter cacheWriter = this.owner.basicGetWriter();
    final boolean cacheWrite = !event.isOriginRemote() && !event.isNetSearch() && !event.getInhibitDistribution() && event.isGenerateCallbacks()
        && (cacheWriter != null
            || this.owner.hasServerProxy()
            || this.owner.scope.isDistributed());
    if (cacheWrite) {
      final Set netWriteRecipients;
      if (cacheWriter == null && this.owner.scope.isDistributed()) {
        CacheDistributionAdvisor cda =
          ((DistributedRegion)this.owner).getDistributionAdvisor();
        netWriteRecipients = cda.adviseNetWrite();
      }
      else {
        netWriteRecipients = null;
      }
      if (event.getOperation() != Operation.REPLACE) { // bug #42167 - makeCreate() causes REPLACE to eventually become UPDATE
        event.makeCreate();
      }
      this.owner.cacheWriteBeforePut(event, netWriteRecipients,
          cacheWriter, requireOldValue, expectedOldValue);
    }

    owner.recordEvent(event);
    // Added to ensure that for DataPolicy Empty regions which have Gfxd delta,
    // should convert op = create to op = update
    if( event.hasDeltaPut()) {
      event.makeUpdate();
    }
    lastModified = // fix for bug 40129
      this.owner.basicPutPart2(event, markerEntry, true,
        lastModified, false /*Clear conflict occurred */);
    // invoke GemFireXD index manager if present
    final IndexUpdater indexUpdater = getIndexUpdater();
    if (indexUpdater != null) {
      // postEvent not required to be invoked since this is currently used
      // only for FK checks
      try {
        indexUpdater.onEvent(this.owner, event, markerEntry);
      } finally {
        indexUpdater.postEventCleanup(event);
      }
    }
    this.owner.basicPutPart3(event, markerEntry, true,
          lastModified, true, ifNew, ifOld, expectedOldValue, requireOldValue);
    return markerEntry;
  }

  public void writeSyncIfPresent(Object key, Runnable runner) {
    // nothing needed
  }

  public void removeIfDestroyed(Object key) {
    // nothing needed
  }

  @Override
  public void removeIfDelta(Object next) {
    // nothing needed
  }
  
  @Override
  public boolean isListOfDeltas(Object key) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void txApplyDestroy(RegionEntry re, TXStateInterface txState,
      Object key, boolean inTokenMode, boolean inRI, boolean localOp,
      EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent) {
    this.owner.txApplyDestroyPart2(markerEntry, key, inTokenMode,
        false /*Clear conflict occured */);
    if (!inTokenMode) {
      /*
      if (txEvent != null) {
        txEvent.addDestroy(this.owner, markerEntry, key,aCallbackArgument);
      }
      */
      if (AbstractRegionMap.shouldCreateCBEvent(this.owner,
                                                false, !inTokenMode)) {
        // fix for bug 39526
        cbEvent = AbstractRegionMap.createCBEvent(this.owner,
            localOp ? Operation.LOCAL_DESTROY : Operation.DESTROY, key, null,
            txState, eventId, aCallbackArgument, filterRoutingInfo,
            bridgeContext, versionTag, tailKey, cbEvent);
        boolean cbEventInPending = false;
        try {
        AbstractRegionMap.switchEventOwnerAndOriginRemote(cbEvent,
            !txState.isCoordinator());
        if (pendingCallbacks == null) {
          this.owner.invokeTXCallbacks(EnumListenerEvent.AFTER_DESTROY,
              cbEvent, true /* callDispatchListenerEvent */, true /*notifyGateway*/);
        }
        else {
          pendingCallbacks.add(cbEvent);
          cbEventInPending = true;
        }
        } finally {
          if (!cbEventInPending) cbEvent.release();
        }
      }
    }
  }

  @Override
  public void txApplyInvalidate(RegionEntry re, TXStateInterface txState,
      Object key, Object newValue, boolean didDestroy, boolean localOp,
      EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent) {
    this.owner.txApplyInvalidatePart2(markerEntry, key, didDestroy, true,
        false /*Clear conflic occured */);
    if (this.owner.isInitialized()) {
      /*
      if (txEvent != null) {
        txEvent.addInvalidate(this.owner, markerEntry, key, newValue,aCallbackArgument);
      }
      */
      if (AbstractRegionMap.shouldCreateCBEvent(this.owner,
                                                true, this.owner.isInitialized())) {
        // fix for bug 39526
        boolean cbEventInPending = false;
        cbEvent = AbstractRegionMap.createCBEvent(this.owner,
            localOp ? Operation.LOCAL_INVALIDATE : Operation.INVALIDATE, key,
            newValue, txState, eventId, aCallbackArgument, filterRoutingInfo,
            bridgeContext, versionTag, tailKey, cbEvent);
        try {
        AbstractRegionMap.switchEventOwnerAndOriginRemote(cbEvent,
            !txState.isCoordinator());
        if (pendingCallbacks == null) {
          this.owner.invokeTXCallbacks(EnumListenerEvent.AFTER_INVALIDATE,
              cbEvent, true/* callDispatchListenerEvent */, true /*notifyGateway*/);
        }
        else {
          pendingCallbacks.add(cbEvent);
          cbEventInPending = true;
        }
        } finally {
          if (!cbEventInPending) cbEvent.release();
        }
      }
    }
  }

  @Override
  public void txApplyPut(Operation putOp, RegionEntry re,
      TXStateInterface txState, Object key, Object newValue,
      boolean didDestroy, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks,
      FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, VersionTag<?> versionTag,
      long tailKey, TXRegionState txr, EntryEventImpl cbEvent, Delta delta) {
    putOp = putOp.getCorrespondingCreateOp();
    final long lastMod = cbEvent.getEventTime(0L, this.owner);
    this.owner.txApplyPutPart2(markerEntry, key, newValue, lastMod, true,
        didDestroy,  false /*Clear conflict occured */);
    if (this.owner.isInitialized()) {
      /*
      if (txEvent != null) {
        txEvent.addPut(putOp, this.owner, markerEntry, key, newValue,aCallbackArgument);
      }
      */
      if (AbstractRegionMap.shouldCreateCBEvent(this.owner,
                                                false, this.owner.isInitialized())) {
        // fix for bug 39526
        boolean cbEventInPending = false;
        cbEvent = AbstractRegionMap.createCBEvent(this.owner, putOp,
            key, newValue, txState, eventId, aCallbackArgument,
            filterRoutingInfo, bridgeContext, versionTag, tailKey, cbEvent);
        try {
        AbstractRegionMap.switchEventOwnerAndOriginRemote(cbEvent,
            !txState.isCoordinator());
        if (pendingCallbacks == null) {
          this.owner.invokeTXCallbacks(EnumListenerEvent.AFTER_CREATE, cbEvent,
              true/* callDispatchListenerEvent */, true /*notifyGateway*/);
        }
        else {
          pendingCallbacks.add(cbEvent);
          cbEventInPending = true;
        }
        } finally {
          if (!cbEventInPending) cbEvent.release();
        }
      }
    }
  }

  // LRUMapCallbacks methods
  public void lruUpdateCallback() {
    // nothing needed
  }

  public boolean disableLruUpdateCallback() {
    // nothing needed
    return false;
  }

  public void enableLruUpdateCallback() {
    // nothing needed
  }

  public final boolean lruLimitExceeded() {
    return false;
  }

  public void lruCloseStats() {
    // nothing needed
  }

  public void resetThreadLocals() {
    // nothing needed
  }
  
  public void removeEntry(Object key, RegionEntry entry, boolean updateStats) {
    // nothing to do
  }

  public void removeEntry(Object key, RegionEntry re, boolean updateStat,
      EntryEventImpl event, LocalRegion owner, IndexUpdater indexUpdater) {
    // nothing to do
  }

  /**
   * Provides a dummy implementation of RegionEntry so that basicPut can return
   * an instance that make the upper levels think it did the put.
   */
  public static class ProxyRegionEntry implements RegionEntry
  {
   
    public long getLastModified() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void _setLastModified(long lastModified) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public void setLastModified(long lastModified) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean isLockedForCreate() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public long getLastAccessed() throws InternalStatisticsDisabledException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public long getHitCount() throws InternalStatisticsDisabledException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public long getMissCount() throws InternalStatisticsDisabledException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    
    public VersionStamp getVersionStamp() {
      return null;
    }
    
    public boolean isTombstone() {
      return false;
    }

    public VersionTag generateVersionTag(VersionSource member,
        boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
        EntryEventImpl event) {
      return null; // proxies don't do versioning
    }

    public void processVersionTag(EntryEvent ev) {
      return;
    }

    public void makeTombstone(LocalRegion r, VersionTag isOperationRemote) {
      return;
    }
    
    public void updateStatsForPut(long lastModifiedTime) {
      // do nothing; called by LocalRegion.updateStatsForPut
    }

    public void setRecentlyUsed() {
      // do nothing; called by LocalRegion.updateStatsForPut
    }

    public void updateStatsForGet(boolean hit, long time) {
      // do nothing; no entry stats
    }

    public void txDidDestroy(long currTime) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void resetCounts() throws InternalStatisticsDisabledException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void removePhase1(LocalRegion r, boolean isClear) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void removePhase2(LocalRegion r) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean isRemoved() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean isRemovedOrDestroyed() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean isRemovedPhase2() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean fillInValue(LocalRegion r,
        InitialImageOperation.Entry entry, DM mgr, Version targetVersion) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean isOverflowedToDisk(LocalRegion r, DistributedRegion.DiskPosition dp) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public Object getKey() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public Object getKeyCopy() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public Object getRawKey() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public Object getValue(RegionEntryContext context) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void setValue(RegionEntryContext context, Object value) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    
    @Override
    public Object prepareValueForCache(RegionEntryContext r, Object val, boolean isEntryUpdate,
        boolean valHasMetadataForGfxdOffHeapUpdate) {
      throw new IllegalStateException("Should never be called");
    }

//    @Override
//    public void _setValue(Object value) {
//      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
//    }

    @Override
    public Object _getValue() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    @Override
    public Token getValueAsToken() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public void setOwner(LocalRegion owner, Object previousOwner) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public Object _getValueRetain(RegionEntryContext context, boolean decompress) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public Object getValueInVM(RegionEntryContext context) {
      return null; // called by TXRmtEvent.createEvent
    }

    public Object getValueOnDisk(LocalRegion r)
      throws EntryNotFoundException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    public Object getValueOnDiskOrBuffer(LocalRegion r)
      throws EntryNotFoundException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    /* (non-Javadoc)
     * @see com.gemstone.gemfire.internal.cache.RegionEntry#getSerializedValueOnDisk(com.gemstone.gemfire.internal.cache.LocalRegion)
     */
    public Object getSerializedValueOnDisk(LocalRegion localRegion) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean initialImagePut(LocalRegion region, long lastModified,
                                   Object newValue, boolean wasRecovered, boolean versionTagAccepted) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean initialImageInit(LocalRegion region, long lastModified,
                                    Object newValue, boolean create, boolean wasRecovered, boolean versionTagAccepted)  {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean destroy(LocalRegion region,
                           EntryEventImpl event,
                           boolean inTokenMode,
                           boolean cacheWrite,
                           Object expectedOldValue,
                           boolean forceDestroy,
                           boolean removeRecoveredEntry)
        throws CacheWriterException, EntryNotFoundException, TimeoutException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    public boolean dispatchListenerEvents(EntryEventImpl event) throws InterruptedException {
      // note that we don't synchronize on the RE before dispatching
      // events
      event.invokeCallbacks(event.getRegion(), event.inhibitCacheListenerNotification(), false);
      return true;
    }

    public boolean hasStats() {
      return false;
    }

    public Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner)
    {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    @Override
    public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#getOwnerId(Object)
     */
    public Object getOwnerId(Object context) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#attemptLock
     */
    public boolean attemptLock(LockMode mode, int flags,
        LockingPolicy lockPolicy, long msecs, Object owner, Object context) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#releaseLock
     */
    public void releaseLock(LockMode mode, boolean releaseAll, Object owner,
        Object context) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#numSharedLocks()
     */
    public int numSharedLocks() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#numReadOnlyLocks()
     */
    public int numReadOnlyLocks() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#hasExclusiveLock(Object, Object)
     */
    public boolean hasExclusiveLock(Object owner, Object context) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#hasExclusiveSharedLock(Object, Object)
     */
    public boolean hasExclusiveSharedLock(Object owner, Object context) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * @see ExclusiveSharedLockObject#getState()
     */
    public int getState() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasAnyLock() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public Object getContainerInfo() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    public Object setContainerInfo(LocalRegion owner, Object val) {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public boolean isUpdateInProgress() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public void setUpdateInProgress(boolean underUpdate) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public boolean isMarkedForEviction() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public void setMarkedForEviction() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public void clearMarkedForEviction() {
      throw new UnsupportedOperationException(LocalizedStrings
          .ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0
              .toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public boolean isValueNull() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public boolean isInvalid() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public boolean isDestroyed() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }

    @Override
    public void setValueToNull(RegionEntryContext context) {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
    }
    
    @Override
    public boolean isInvalidOrRemoved() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));      
    }

    @Override
    public boolean isOffHeap() {
      return false;
    }

    @Override
    public boolean isDestroyedOrRemoved() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));      
    }

    @Override
    public boolean isDestroyedOrRemovedButNotTombstone() {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));      
    }

    @Override
    public void returnToPool() {
      // TODO Auto-generated method stub
      
    }

    @Override
    public void setValueWithTombstoneCheck(Object value, EntryEvent event)
        throws RegionClearedException {
      throw new UnsupportedOperationException(LocalizedStrings.ProxyRegionMap_NO_ENTRY_SUPPORT_ON_REGIONS_WITH_DATAPOLICY_0.toLocalizedString(DataPolicy.EMPTY));
      }

    @Override
    public boolean isCacheListenerInvocationInProgress() {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public void setCacheListenerInvocationInProgress(boolean isListenerInvoked) {
      // TODO Auto-generated method stub
      
    }
  }

  public void lruUpdateCallback(int n) {
    //do nothing
  }
  
  public void lruEntryFaultIn(LRUEntry entry) {
    //do nothing.
    
  }

  public void copyRecoveredEntries(RegionMap rm, boolean entriesIncompatible) {
    throw new IllegalStateException("copyRecoveredEntries should never be called on proxy");
  }

  @Override
  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    return sizer.sizeof(this) ;
  }

  public boolean removeTombstone(RegionEntry re, VersionHolder destroyedVersion, boolean isEviction, boolean isScheduledTombstone) {
    throw new IllegalStateException("removeTombstone should never be called on a proxy");
  }

  public boolean isTombstoneNotNeeded(RegionEntry re, int destroyedVersion) {
    throw new IllegalStateException("removeTombstone should never be called on a proxy");
  }


  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.RegionMap#unscheduleTombstone(com.gemstone.gemfire.internal.cache.RegionEntry)
   */
  public void unscheduleTombstone(RegionEntry re) {
  }

  public void setEntryFactory(RegionEntryFactory f) {
    throw new IllegalStateException("Should not be called on a ProxyRegionMap");
  }

  @Override
  public void updateEntryVersion(EntryEventImpl event) {
    // Do nothing. Not applicable for clients.    
  }

  @Override
  public RegionEntry getEntryInVM(Object key) {
    return null;
  }

  @Override
  public RegionEntry getOperationalEntryInVM(Object key) {
    return null;
  }

  @Override
  public int sizeInVM() {
    return 0;
  }

  @Override
  public Map<?, ?> getTestSuspectMap() {
    return null;
  }

  @Override
  public void close() {
  }
}
