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
/**
 * File comment
 */
package com.gemstone.gemfire.internal.cache;

import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * @author mthomas
 * @since 6.0tx
 */
public final class PartitionedRegionDataView extends LocalRegionDataView {

  
  @Override
  public void updateEntryVersion(EntryEventImpl event)
      throws EntryNotFoundException {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    pr.updateEntryVersionInBucket(event);
  }

  @Override
  public void invalidateExistingEntry(EntryEventImpl event,
      boolean invokeCallbacks, boolean forceNewEntry) {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    pr.invalidateInBucket(event);
  }

  @Override
  public void destroyExistingEntry(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    pr.destroyInBucket(event, expectedOldValue);
  }

  @Override
  public Region.Entry<?, ?> getEntry(Object key, Object callbackArg,
      LocalRegion localRegion, boolean allowTombstones) {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.nonTXGetEntry(key, callbackArg, false, allowTombstones);
  }

  @Override
  public Region.Entry<?, ?> accessEntry(Object key, Object callbackArg,
      LocalRegion localRegion) {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.nonTXGetEntry(key, callbackArg, true, false);
  }

  @Override
  public final Object findObject(KeyInfo key, LocalRegion r, boolean isCreate,
      boolean generateCallbacks, Object value, boolean disableCopyOnRead,
      boolean preferCD, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS) {
    return r.findObjectInSystem(key, isCreate, null, generateCallbacks, value,
        disableCopyOnRead, preferCD, requestingClient, clientEvent,
        returnTombstones, allowReadFromHDFS);
  }

  @Override
  public Object getLocally(final Object key, final Object callbackArg,
      final int bucketId, final LocalRegion localRegion,
      final boolean doNotLockEntry, final boolean localExecution,
      final TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean allowTombstones, boolean allowReadFromHDFS) throws PrimaryBucketException,
      ForceReattemptException, PRLocallyDestroyedException {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    if (!localExecution) {
      return pr.getDataStore().getSerializedLocally(key, callbackArg, bucketId,
          null, doNotLockEntry, lockState, clientEvent, allowTombstones, allowReadFromHDFS);
    }
    return pr.getDataStore().getLocally(bucketId, key, callbackArg, false,
        false, null, null, lockState, clientEvent, allowTombstones, allowReadFromHDFS);
  }

  @Override
  public Object getSerializedValue(LocalRegion localRegion, KeyInfo keyInfo,
      boolean doNotLockEntry, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS)
      throws DataLocationException {
    PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.getDataStore().getSerializedLocally(keyInfo, doNotLockEntry, null,
        clientEvent, returnTombstones, allowReadFromHDFS);
  }

  @Override
  public boolean putEntryOnRemote(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      boolean cacheWrite, long lastModified, boolean overwriteDestroyed)
      throws DataLocationException {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    return pr.getDataStore().putLocally(event.getBucketId(), event, ifNew,
        ifOld, expectedOldValue, requireOldValue, lastModified);
  }

  @Override
  public void destroyOnRemote(EntryEventImpl event, boolean cacheWrite,
      Object expectedOldValue) throws DataLocationException {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    pr.getDataStore().destroyLocally(event.getBucketId(), event,
        expectedOldValue);
    return;
  }

  @Override
  public void invalidateOnRemote(EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry) throws DataLocationException {
    PartitionedRegion pr = (PartitionedRegion)event.getLocalRegion();
    pr.getDataStore().invalidateLocally(event.getBucketId(), event);
  }

  @Override
  public Set<?> getBucketKeys(LocalRegion localRegion, int bucketId,
      boolean allowTombstones) {
    PartitionedRegion pr = (PartitionedRegion)localRegion;
    return pr.getBucketKeys(bucketId, allowTombstones);
  }

  @Override
  public EntrySnapshot getEntryOnRemote(final KeyInfo keyInfo,
      final LocalRegion localRegion, boolean allowTombstones)
      throws DataLocationException, EntryNotFoundException {
    final PartitionedRegion pr = (PartitionedRegion)localRegion;
    final EntrySnapshot entry = pr.getDataStore().getEntryLocally(
        keyInfo.getBucketId(), keyInfo.getKey(), false, null, allowTombstones);
    if (entry != null) {
      return entry;
    }
    throw new EntryNotFoundException(
        LocalizedStrings.PartitionedRegionDataStore_ENTRY_NOT_FOUND
            .toLocalizedString());
  }

  @Override
  public Object getValueForIterator(KeyInfo key, LocalRegion r,
      boolean updateStats, boolean preferCD, EntryEventImpl clientEvent,
      boolean allowTombstones) {
    return findObject(key, r, false /* isCreate */,
        true /* generateCallbacks */, null /* value */, false, preferCD,
        null, clientEvent, allowTombstones, true/*allowReadFromHDFS*/);
  }

  @Override
  public Object getKeyForIterator(KeyInfo curr, LocalRegion currRgn,
      boolean allowTombstones) {
    // do not perform a value check here, it will send out an
    // extra message. Also BucketRegion will check to see if
    // the value for this key is a removed token
    return curr.getKey();
  }

  @Override
  public Object getKeyForIterator(final Object key, final LocalRegion region) {
    return key;
  }

  @Override
  public Region.Entry<?, ?> getEntryForIterator(final KeyInfo keyInfo,
      final LocalRegion currRgn, boolean allowTombstones) {
    return currRgn.txGetEntry(keyInfo, false, null, allowTombstones);
  }

  @Override
  public Iterator<?> getLocalEntriesIterator(
      final InternalRegionFunctionContext context, final boolean primaryOnly,
      final boolean forUpdate, final boolean includeValues,
      final LocalRegion currRegion) {
    return ((PartitionedRegion)currRegion).localEntriesIterator(context,
        primaryOnly, false, includeValues, null);
  }

  @Override
  public Iterator<?> getLocalEntriesIterator(Set<Integer> bucketSet,
      boolean primaryOnly, boolean forUpdate, boolean includeValues,
      LocalRegion currRegion, boolean fetchRemote) {
    return ((PartitionedRegion)currRegion).getAppropriateLocalEntriesIterator(
        bucketSet, primaryOnly, forUpdate, includeValues, currRegion, fetchRemote);
  }
}
