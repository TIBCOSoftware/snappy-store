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
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.UnsupportedOperationInTransactionException;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValue;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;

/**
 * 
 * @author mthomas
 * @since 6.0tx
 */
public class LocalRegionDataView implements InternalDataView {

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getDeserializedValue(Object key, Object callbackArg,
      LocalRegion localRegion, boolean updateStats, boolean disableCopyOnRead,
      boolean preferCD, final TXStateInterface lockState,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadsFromHDFS) {
    return localRegion.getDeserializedValue(null, key, callbackArg,
        updateStats, disableCopyOnRead, preferCD, lockState, clientEvent,
        returnTombstones, allowReadsFromHDFS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getLocally(final Object key, Object callbackArg, int bucketId,
      final LocalRegion localRegion, boolean doNotLockEntry,
      boolean localExecution, final TXStateInterface lockState,
      EntryEventImpl clientEvent, boolean allowTombstones, boolean allowReadFromHDFS)
      throws DataLocationException {
    // TODO should the allowReadFromHDFS flag be passed through?
    Object val = getEntryValue(localRegion, key, true, lockState);
    if (val != null && !Token.isInvalid(val)
        && (allowTombstones || (val != Token.TOMBSTONE))) {
      return val;
    }
    // check for a local loader
    if (localRegion.basicGetLoader() != null) {
      val = localRegion.findObjectInLocalSystem(key, callbackArg, val == null,
          lockState, true, val, clientEvent);
      if (val != null && !Token.isInvalid(val)) {
        // wrap in RawValue to indicate that this is from CacheLoader
        return RawValue.newInstance(val, localRegion.getCache())
            .setFromCacheLoader();
      }
    }
    return val;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroyExistingEntry(final EntryEventImpl event,
      final boolean cacheWrite, final Object expectedOldValue) {
    final LocalRegion lr = event.getLocalRegion();
    lr.mapDestroy(event,
        cacheWrite,
        false, // isEviction
        expectedOldValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void invalidateExistingEntry(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry) {
    try {
      event.getLocalRegion().entries.invalidate(event, invokeCallbacks, forceNewEntry,false);    
    } catch (ConcurrentCacheModificationException e) {
      // a newer event has already been applied to the cache.  this can happen
      // in a client cache if another thread is operating on the same key
    }
  }

  @Override
  public void updateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {
    try {
      event.getLocalRegion().entries.updateEntryVersion(event);
    } catch (ConcurrentCacheModificationException e) {
      // a later in time event has already been applied to the cache.  this can happen
      // in a cache if another thread is operating on the same key
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int entryCount(LocalRegion localRegion) {
    return localRegion.getRegionSize();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValueInVM(Object key, Object callbackArg,
      LocalRegion localRegion) {
    return localRegion.nonTXbasicGetValueInVM(key, callbackArg);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsKey(Object key, Object callbackArg,
      LocalRegion localRegion) {
    return localRegion.txContainsKey(key, callbackArg, null, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsValueForKey(Object key, Object callbackArg,
      LocalRegion localRegion) {
    return localRegion.txContainsValueForKey(key, callbackArg, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Region.Entry<?, ?> getEntry(Object key, Object callbackArg,
      LocalRegion localRegion, boolean allowTombstones) {
    return localRegion.nonTXGetEntry(key, callbackArg, false, allowTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Region.Entry<?, ?> accessEntry(Object key, Object callbackArg,
      LocalRegion localRegion) {
    return localRegion.nonTXGetEntry(key, callbackArg, true, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean putEntry(EntryEventImpl event, boolean ifNew, boolean ifOld,
      Object expectedOldValue, boolean requireOldValue, boolean cacheWrite,
      long lastModified, boolean overwriteDestroyed) {
    return event.getLocalRegion().virtualPut(event, ifNew, ifOld,
        expectedOldValue, requireOldValue, lastModified, overwriteDestroyed);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDeferredStats() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object findObject(KeyInfo keyInfo, LocalRegion r, boolean isCreate,
      boolean generateCallbacks, Object value, boolean disableCopyOnRead,
      boolean preferCD, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS) {
    return r.nonTxnFindObject(keyInfo, isCreate, generateCallbacks, value,
        disableCopyOnRead, preferCD, clientEvent, returnTombstones, allowReadFromHDFS);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getKeyForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, boolean allowTombstones) {
    final AbstractRegionEntry re = (AbstractRegionEntry)keyInfo.getKey();
    // fix for 42182, before returning a key verify that its value
    // is not a removed token
    if (re != null && !re.isDestroyedOrRemoved()
      || (allowTombstones  &&  re.isTombstone())) {
      return re.getKeyCopy();
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getKeyForIterator(final Object key, final LocalRegion currRgn) {
    final AbstractRegionEntry re = (AbstractRegionEntry)key;
    if (re != null) {
      return re.getKeyCopy();
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValueForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, final boolean updateStats,
      final boolean preferCD, EntryEventImpl clientEvent,
      boolean allowTombstones) {
    final AbstractRegionEntry re = (AbstractRegionEntry)keyInfo.getKey();
    return currRgn.getDeserializedValue(re, re.getKey(), null, updateStats,
        false, preferCD, null, clientEvent, allowTombstones, false/*allowReadFromHDFS*/);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Region.Entry<?, ?> getEntryForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, boolean allowTombstones) {
    return currRgn.txGetEntryForIterator(keyInfo, false, null, allowTombstones);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<?> getAdditionalKeysForIterator(LocalRegion currRgn) {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getSerializedValue(final LocalRegion region, final KeyInfo key,
      final boolean doNotLockEntry, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS)
      throws DataLocationException {
    throw new IllegalStateException();
  }

  static final Object getEntryValue(final LocalRegion region, final Object key,
      final boolean updateStats, final TXStateInterface lockState) {
    final DiskRegion dr = region.getDiskRegion();
    if (dr != null) {
      dr.setClearCountReference();
    }
    try {
      final RegionEntry entry = region.basicGetEntry(key);
      if (entry != null) {
        final Object v;
        if (lockState != null) {
          // read under read lock if required
          v = lockState.lockEntryForRead(entry, key, region, 0,
              false, LocalRegion.GET_VALUE);
        }
        else {
          v = region.getEntryValue(entry);
        }
        if (updateStats) {
          region.updateStatsForGet(entry, v != null && !Token.isInvalid(v));
        }
        return v;
      }
      return null;
    } finally {
      if (dr != null) {
        dr.removeClearCountReference();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean putEntryOnRemote(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed)
      throws DataLocationException {
    throw new IllegalStateException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroyOnRemote(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) throws DataLocationException {
    destroyExistingEntry(event, cacheWrite, expectedOldValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void invalidateOnRemote(EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry) throws DataLocationException {
    invalidateExistingEntry(event, invokeCallbacks, forceNewEntry);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<?> getBucketKeys(LocalRegion localRegion, int bucketId,
      boolean allowTombstones) {
    throw new IllegalStateException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public EntrySnapshot getEntryOnRemote(KeyInfo keyInfo,
      LocalRegion localRegion, boolean allowTombstones)
    throws DataLocationException {
    throw new IllegalStateException();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void checkSupportsRegionDestroy()
      throws UnsupportedOperationInTransactionException {
    // do nothing - this view supports it
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void checkSupportsRegionInvalidate()
      throws UnsupportedOperationInTransactionException {
    // do nothing - this view supports it
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<?> getRegionKeysForIteration(LocalRegion currRegion,
      boolean includeValues) {
    // returning RegionEntry collection here helps avoid another map lookup
    // for key/value in getKeyForIterator/getValueForIterator
    return currRegion.getBestLocalIterator(includeValues, 4.0, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public Iterator<?> getLocalEntriesIterator(
      InternalRegionFunctionContext context, boolean primaryOnly,
      boolean forUpdate, boolean includeValues, final LocalRegion currRegion) {
    assert context == null || context.getLocalBucketSet(currRegion) == null:
      "unexpected bucket set for LocalRegion " + currRegion + ": "
      + context.getLocalBucketSet(currRegion);
    return currRegion.getBestLocalIterator(includeValues, 1.0, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void postPutAll(DistributedPutAllOperation putallOp,
      VersionedObjectList successfulPuts, LocalRegion region) {
    if (!region.dataPolicy.withStorage() && region.concurrencyChecksEnabled
        && putallOp.getBaseEvent().isBridgeEvent()) {
      // if there is no local storage we need to transfer version information
      // to the successfulPuts list for transmission back to the client
      successfulPuts.clear();
      putallOp.fillVersionedObjectList(successfulPuts);
    }
    region.postPutAllSend(putallOp, null, successfulPuts);
    region.postPutAllFireEvents(putallOp, successfulPuts);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsKeyWithReadLock(Object key, Object callbackArg,
      LocalRegion localRegion) {
    return containsKey(key, callbackArg, localRegion);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<?> getLocalEntriesIterator(Set<Integer> bucketSet,
      boolean primaryOnly, boolean forUpdate, boolean includeValues,
      LocalRegion currRegion, boolean fetchRemote) {
    throw new IllegalStateException("getLocalEntriesIterator: "
        + "this method is intended to be called only for PRs");
  }
}
