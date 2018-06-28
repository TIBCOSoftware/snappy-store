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

package com.pivotal.gemfirexd.internal.engine.store.entry;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;

import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskPosition;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.Entry;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.ExtractingIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Note: if changing to non-final, then change the getClass() comparison in
 * GemFireContainer.PREntriesFullIterator.extractRowLocationFromEntry
 * 
 * @author Asif
 * @author kneeraj
 * @author swale
 */
public final class GfxdTXEntryState extends TXEntryState implements
    RowLocation, RegionEntry {

  private final static TXEntryStateFactory factory = new TXEntryStateFactory() {

    @Override
    public final GfxdTXEntryState createEntry(final Object key,
        final RegionEntry re, final Object val, final boolean doFullValueFlush,
        final TXRegionState txrs) {
      return new GfxdTXEntryState(key, re, val, doFullValueFlush, txrs);
    }
  };

  public static final TXEntryStateFactory getGfxdFactory() {
    return factory;
  }

  private ArrayList<WrapperRowLocationForTxn> replacedEntriesForUniqIndexes;
  /*private final AtomicInteger indexKeyUseCount ;
  private static final int  UPDATE_IN_PROGRESS = 1;
  private static final int  NO_INDEX_USE_NO_UPDATE = 0;*/

  /**
   * This constructor is used when creating an entry
   */
  private GfxdTXEntryState(final Object key, final RegionEntry re,
      final Object val, final boolean doFullValueFlush, final TXRegionState txrs) {
    super(key, re, val, doFullValueFlush, txrs);
    //this.indexKeyUseCount = new AtomicInteger(0);

    // should never happen on a client/locator VM
    final VMKind myKind;
    if (!GemFireCacheImpl.getInternalProductCallbacks().isSnappyStore()) {
      assert (myKind = GemFireXDUtils.getMyVMKind()) == VMKind.DATASTORE :
          "unexpected creation of GfxdTXEntryState on VM of kind " + myKind;
    }
  }

  @Override
  protected final void basicPutPart3(final LocalRegion owner,
      final EntryEventImpl event, final boolean ifNew) throws TimeoutException {
    final IndexUpdater indexUpdater = owner.getIndexUpdater();
    if (indexUpdater != null) {
      boolean success = false;
      try {
        indexUpdater.onEvent(owner, event, this);
        success = true;
      } catch (RuntimeException e) {
        if (GemFireXDUtils.isTXAbort(e) != GemFireXDUtils.TXAbortState.NOT_ABORTED) {
          // ignore distribution in postEvent (global index rollback) since TX
          // will be rolled back anyways, and GFE will complain that writing to
          // TX is no longer allowed
          event.setSkipDistributionOps();
        }
        throw e;
      } finally {
        indexUpdater.postEvent(owner, event, this, success);
      }
    }
  }

  // A delete is always supposed to impact local index but is it needed as the
  // value token removed will be handled automatically by GemFireXD? The only
  // probable case may be select which is just selecting index key and select
  // returns tx data
  /**
   * @return true if destroy was done
   */
  @Override
  protected void destroyPart3(final LocalRegion owner,
      final EntryEventImpl event) throws EntryNotFoundException,
      TimeoutException {
    final IndexUpdater indexUpdater = owner.getIndexUpdater();
    if (indexUpdater != null) {
      boolean success = false;
      try {
        indexUpdater.onEvent(owner, event, this);
        success = true;
      } catch (RuntimeException e) {
        if (GemFireXDUtils.isTXAbort(e) != GemFireXDUtils.TXAbortState.NOT_ABORTED) {
          // ignore distribution in postEvent (global index rollback) since TX
          // will be rolled back anyways, and GFE will complain that writing to
          // TX is no longer allowed
          event.setSkipDistributionOps();
        }
        throw e;
      } finally {
        if (success) {
          indexUpdater.postEvent(owner, event, this, success);
        }
      }
    }
  }

  @Override
  protected void applyBatchOperationOnNewEntry(final TXState txState,
      final LockingPolicy lockPolicy, final TXRegionState txrs,
      final LocalRegion region, final LocalRegion dataRegion,
      final Object regionValue, final RegionEntry entry,
      final boolean lockedForRead, final boolean checkForTXFinish,
      final EntryEventImpl event) {
    final IndexUpdater indexUpdater = dataRegion.getIndexUpdater();
    if (isDirty() && indexUpdater != null) {
      // setup the reusable EntryEventImpl for index maintenance
      if (Token.isRemoved(regionValue)) {
        // for this case we do not expect to receive a delta from source
        if (this.pendingDelta != null) {
          // fail the transaction for this case
          //dataRegion.dumpBackingMap();
          Assert.fail("unexpected delta received for new region entry for key="
              + this.regionKey + ", entry=" + entry + ", regionValue="
              + regionValue + ", in region " + region.getFullPath()
              + ", dataRegion=" + dataRegion.getFullPath() + ": "
              + this.pendingDelta);
        }
        // if entry was created and then destroyed in TX itself then skip it
        if (isOpDestroy()) {
          super.applyBatchOperationOnNewEntry(txState, lockPolicy, txrs,
              region, dataRegion, regionValue, entry, lockedForRead,
              checkForTXFinish, event);
          return;
        }
        if (this.pendingValue == null) {
          Assert.fail("unexpected null new value for CREATE for key="
              + this.regionKey + ", in region " + region.getFullPath()
              + ", dataRegion=" + dataRegion.getFullPath());
        }
        event.setOperation(Operation.CREATE);
        event.setTXOldValue(null);
        event.setNewValue(this.pendingValue);
      }
      else if (isOpDestroy()) {
        if (this.pendingDelta != null || this.pendingValue != null) {
          // fail the transaction for this case
          Assert.fail("unexpected delta or value received for destroyed key="
              + regionKey + ", in region " + region.getFullPath() + ": "
              + toString());
        }
        event.setOperation(Operation.DESTROY);
        event.setTXOldValue(regionValue);
        event.setNewValue(null);
      }
      else if (this.pendingDelta == null) {
        if (this.pendingValue == null) {
          Assert.fail("unexpected null new value for UPDATE for key="
              + this.regionKey + ", in region " + region.getFullPath()
              + ", dataRegion=" + dataRegion.getFullPath());
        }
        event.setOperation(Operation.UPDATE);
        event.setTXOldValue(regionValue);
        event.setNewValue(this.pendingValue);
      }
      else {
        // for this case we expect to receive only delta and no value
        if (this.pendingValue != null) {
          // fail the transaction for this case
          Assert.fail("unexpected full value received for existing region "
              + "entry " + entry + ", in region " + region.getFullPath()
              + ", dataRegion=" + dataRegion.getFullPath() + ": "
              + this.pendingValue);
        }
        event.setOperation(Operation.UPDATE);
        event.setTXOldValue(regionValue);
        event.setNewDelta(this.pendingDelta);
      }
      event.setRegion(region);
      event.setKey(this.regionKey);
      event.setCallbackArgument(getCallbackArgument());
      // always skip index distribution ops
      event.setSkipDistributionOps();
      event.setContextObject(null);

      boolean success = false;
      try {
        indexUpdater.onEvent(dataRegion, event, this);
        success = true;
        super.applyBatchOperationOnNewEntry(txState, lockPolicy, txrs, region,
            dataRegion, regionValue, entry, lockedForRead, checkForTXFinish,
            event);
      } finally {
        indexUpdater.postEvent(dataRegion, event, this, success);
      }
    }
    else {
      super.applyBatchOperationOnNewEntry(txState, lockPolicy, txrs, region,
          dataRegion, regionValue, entry, lockedForRead, checkForTXFinish,
          event);
    }
  }

  @Override
  protected void applyBatchOperationOnExistingEntry(final TXState txState,
      final TXRegionState txrs, final LocalRegion region,
      final LocalRegion dataRegion, final TXEntryState currentTXEntry,
      final EntryEventImpl event, final int lockFlags,
      final AbstractOperationMessage msg) {
    final IndexUpdater indexUpdater = dataRegion.getIndexUpdater();
    if (isDirty() && indexUpdater != null) {
      // setup the reusable EntryEventImpl for index maintenance
      final GfxdTXEntryState gfxdEntry = (GfxdTXEntryState)currentTXEntry;
      if (isOpDestroy()) {
        // skip if event already received (e.g. via GII)
        if (gfxdEntry.isOpDestroy() && gfxdEntry.op >= this.op) {
          return;
        }
        if (this.pendingDelta != null || this.pendingValue != null) {
          // fail the transaction for this case
          Assert.fail("unexpected delta or value received for destroyed key="
              + regionKey + ", in region " + region.getFullPath() + ": "
              + toString());
        }
        event.setOperation(Operation.DESTROY);
        event.setNewValue(null);
      }
      else if (gfxdEntry.isOpDestroy()) {
        if (this.pendingDelta != null) {
          // fail the transaction for this case
          Assert.fail("unexpected delta received for previously destroyed key="
              + this.regionKey + ", in region " + region.getFullPath() + ": "
              + this.pendingDelta);
        }
        event.setOperation(Operation.CREATE);
        event.setNewValue(this.pendingValue);
      }
      else {
        if (this.pendingValue != null) {
          // skip if event already received (e.g. via GII)
          if (gfxdEntry.isOpCreate() || gfxdEntry.isOpPut()) {
            return;
          }
          // fail the transaction for this case
          Assert.fail("unexpected full value received for existing entry "
              + currentTXEntry + ", in region " + region.getFullPath() + ": "
              + this.pendingValue);
        }
        event.setOperation(Operation.UPDATE);
        event.setNewDelta(this.pendingDelta);
      }
      event.setRegion(region);
      event.setKey(this.regionKey);
      event.setTXOldValue(gfxdEntry.getValueInTXOrRegion());
      event.setCallbackArgument(getCallbackArgument());
      // always skip index distribution ops
      event.setSkipDistributionOps();
      event.setContextObject(null);

      boolean success = false;
      try {
        indexUpdater.onEvent(dataRegion, event, gfxdEntry);
        success = true;
        super.applyBatchOperationOnExistingEntry(txState, txrs, region,
            dataRegion, currentTXEntry, event, lockFlags, msg);
      } finally {
        indexUpdater.postEvent(dataRegion, event, gfxdEntry, success);
      }
    }
    else {
      super.applyBatchOperationOnExistingEntry(txState, txrs, region,
          dataRegion, currentTXEntry, event, lockFlags, msg);
    }
  }

  @Override
  public RegionEntry getRegionEntry() {
    return this;
  }

  @Override
  @Retained
  public Object getValue(GemFireContainer baseContainer) {
    return super.getRetainedValueInTXOrRegion();
   
  }

  /**
   * TX entries will always do a fault-in.
   * 
   * @see RowLocation#getValueWithoutFaultIn(GemFireContainer)
   */
  @Override
  @Retained
  public Object getValueWithoutFaultIn(GemFireContainer baseContainer) {
    return super.getRetainedValueInTXOrRegion();
    
  }

  @Override
  public final ExecRow getRow(GemFireContainer baseContainer)
      throws StandardException {
    final RegionEntry entry = this.regionEntry;
    if (isDirty()) {
      final Object value = getNearSidePendingValue();
      // txnal entries will always be of the current schema since ALTER TABLE
      // or any other DDL will either fail with txns, or block for txns
      return baseContainer.newExecRow(entry, value,
          baseContainer.getExtraTableInfo(), true);
    }
    else if (entry != null) {
      final Object value = this.originalVersionId;
      if (!Token.isRemoved(value)) {
        return baseContainer.newExecRow(entry, value,
            (ExtraTableInfo)entry.getContainerInfo(), true);
      }
    }
    return null;
  }

  /**
   * TX entries will always do a fault-in.
   * 
   * @see RowLocation#getRowWithoutFaultIn(GemFireContainer)
   */
  @Override
  public final ExecRow getRowWithoutFaultIn(GemFireContainer baseContainer)
      throws StandardException {
    return getRow(baseContainer);
  }

  @Override
  public ExtraTableInfo getTableInfo(GemFireContainer baseContainer) {
    if (isDirty()) {
      // txnal entries will always be of the current schema since ALTER TABLE
      // or any other DDL will either fail with txns, or block for txns
      return baseContainer.getExtraTableInfo();
    }
    else if (this.regionEntry != null) {
      return (ExtraTableInfo)this.regionEntry.getContainerInfo();
    }
    else {
      return null;
    }
  }

  public RowLocation getRegionRowLocation() {
    return (RowLocation)this.regionEntry;
  }

  @Override
  public int estimateMemoryUsage() {
    return ClassSize.refSize;
  }

  @Override
  public int getTypeFormatId() {
    return StoredFormatIds.ACCESS_MEM_HEAP_ROW_LOCATION_ID;
  }

  @Override
  public int getBucketID() {
    if (this.regionEntry != null) {
      return ((RowLocation)this.regionEntry).getBucketID();
    }
    return KeyInfo.UNKNOWN_BUCKET;
  }

  @Override
  public final Object cloneObject() {
    return this;
  }

  @Override
  public final RowLocation getClone() {
    return this;
  }

  @Override
  public final int compare(DataValueDescriptor other) {
    // just use some arbitrary criteria like hashCode for ordering

    if (this == other) {
      return 0;
    }
    return this.hashCode() - other.hashCode();
  }

  @Override
  public DataValueDescriptor recycle() {
    return this;
  }

  @Override
  public DataValueDescriptor getNewNull() {
    return DataValueFactory.DUMMY;
  }

  @Override
  public boolean isNull() {
    return this == DataValueFactory.DUMMY;
  }

  @Override
  public Object getObject() throws StandardException {
    return this;
  }

  @Override
  @Retained
  public Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
    return super.getRetainedValueInTXOrRegion();
  }

  @Override
  public boolean isRemoved() {
    return !existsLocally();
    /*
    return this.pendingValue == null && this.pendingDelta == null
        && (this.originalVersionId == Token.REMOVED_PHASE1
            || this.originalVersionId == Token.REMOVED_PHASE2);
    */
  }

  @Override
  public boolean isDestroyedOrRemoved() {
    return !existsLocally();
    /*
    return this.pendingValue == null && this.pendingDelta == null
        && Token.isRemoved(this.originalVersionId);
    */
  }

  @Override
  @Retained
  public Object getValue(RegionEntryContext context) {
    return super.getRetainedValueInTXOrRegion();
  }

  @Override
  @Unretained
  public Object _getValue() {
    return super.getValueInTXOrRegion();
  }

  @Override
  public Object getKey() {
    return this.regionKey;
  }

  @Override
  public Object getKeyCopy() {
    return this.regionKey;
  }

  @Override
  public Object getRawKey() {
    return this.regionKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Retained
  public Object _getValueRetain(RegionEntryContext context, boolean decompress) {
    return super.getRetainedValueInTXOrRegion();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Token getValueAsToken() {
    @Unretained Object v = super.getValueInTXOrRegion();
    return v instanceof Token ? (Token)v : null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Retained
  public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
    return super.getRetainedValueInTXOrRegion();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isValueNull() {
    return super.getValueInTXOrRegion() == null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isInvalid() {
    return Token.isInvalid(super.getValueInTXOrRegion());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDestroyed() {
    return Token.isDestroyed(super.getValueInTXOrRegion());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDestroyedOrRemovedButNotTombstone() {
    Token o = getValueAsToken();
    return o == Token.DESTROYED || o == Token.REMOVED_PHASE1
        || o == Token.REMOVED_PHASE2;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isInvalidOrRemoved() {
    return Token.isInvalidOrRemoved(super.getValueInTXOrRegion());
  }

  @Override
  public boolean isOffHeap() {
    return false;
  }

  @Override
  protected final void setCallbackArgument(Object callbackArgument) {
    // we cannot set a thread-local type of callbackArgument in the TXState
    // since it will be reused (#44074)
    if (callbackArgument instanceof GfxdCallbackArgument) {
      final GfxdCallbackArgument sca = (GfxdCallbackArgument)callbackArgument;
      if (sca.isThreadLocalInstance()) {
        final Object currentCallbackArg = getCallbackArgument();
        if (currentCallbackArg != null && sca.equals(currentCallbackArg)) {
          // nothing to do since we already have the same callback argument
          return;
        }
        else {
          callbackArgument = sca.cloneObject();
        }
      }
    }
    super.setCallbackArgument(callbackArgument);
  }

  private void cleanupReplacedUniqEntries() {
    Object key = null;
    for (WrapperRowLocationForTxn e : this.replacedEntriesForUniqIndexes) {
      try {
        if (e == null) {
          continue;
        }
        final ConcurrentSkipListMap<Object, Object> skipListMap = e
            .getIndexContainer().getSkipListMap();
        assert skipListMap != null;
        key = e.getIndexKey();
        if (GemFireXDUtils.TraceIndex) {
          GfxdIndexManager.traceIndex(
              "GfxdTXEntryState: replacing value of key=%s from "
                  + "wrapper=%s to %s in (%s) for TX: %s", key, e,
              this.regionEntry, e.getIndexContainer(), getTXId());
        }
        final boolean replaced = skipListMap.replace(key, e, this.regionEntry);
        // we expect the replace to always succeed
        if (SanityManager.DEBUG) {
          if (!replaced) {
            SanityManager.THROWASSERT("GfxdTXEntryState: failed to replace "
                + "key=" + ArrayUtils.objectString(key) + " from wrapper=" + e
                + " to " + this.regionEntry + " in (" + e.getIndexContainer()
                + ") for TX: " + getTXId());
          }
        }
      } catch (Exception ex) {
        // at this point we cannot fail so log and move on no exception expected
        // here. but if we encounter any we need to move on after logging as
        // rest of the cleanup should happen.
        Misc.getCacheLogWriter().error(
            "unexpected exception during reinstating indexes for key="
                + ArrayUtils.objectString(key) + " in index = "
                + e.getIndexContainer(), ex);
      }
    }
    this.replacedEntriesForUniqIndexes = null;
  }

  @Override
  protected final void cleanup(final TXState txState, final LocalRegion r,
      final LockingPolicy lockPolicy, final LockMode writeMode,
      final boolean removeFromList, final boolean removeFromMap,
      final Boolean forCommit) {
    // unique index replace should happen for each entry and is not a global
    // call so should not be in cleanupIndexes (#43761)
    if (this.replacedEntriesForUniqIndexes != null) {
      cleanupReplacedUniqEntries();
    }
    super.cleanup(txState, r, lockPolicy, writeMode, removeFromList,
        removeFromMap, forCommit);
    // update numRows estimate (#47914)
    if (forCommit != null && forCommit.booleanValue()) {
      int change = entryCountMod();
      if (change != 0) {
        getBaseContainer().updateNumRows(change < 0);
      }
    }
  }

  public WrapperRowLocationForTxn wrapperForRollback(
      GemFireContainer indexContainer, Object oldKey) {
    // in case of op = INSERT/CREATE we need not reinstate the old index
    // during rollback.
    if (wasCreatedByTX()) {
      return null;
    }
    // in case of op = destroy or op = update/put of an existing entry
    // we need to reinstate the old index during rollback
    if (!isOpPut() && !isOpDestroy()) {
      if (GemFireXDUtils.TraceTran | GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, "op is: " + this.op
            + " and opToString returns: " + this.opToString());
      }
    }
    boolean isOpDestroy = isOpDestroy();

    assert isOpPut() || isOpDestroy;

    final TXRegionState txrs = this.txRegionState;
    txrs.lock();
    try {
      THashMapWithKeyPair reinstateMap = txrs.getToBeReinstatedIndexMap();

      final int insertIndex;
      if (reinstateMap == null) {
        reinstateMap = new THashMapWithKeyPair(
            ObjectEqualsHashingStrategy.getInstance(),
            getDataRegion().getCachePerfStats());
        txrs.setToBeReinstatedIndexMap(reinstateMap);
        insertIndex = reinstateMap.insertionIndex(this, indexContainer);
      }
      else {
        insertIndex = reinstateMap.insertionIndex(this, indexContainer);
        if (insertIndex < 0) {
          return null;
        }
      }

      WrapperRowLocationForTxn wrapper = new WrapperRowLocationForTxn(this,
          oldKey, !isOpDestroy);
      Object old = reinstateMap.putIfAbsent(this, indexContainer, wrapper,
          insertIndex);
      return old == null ? wrapper : (WrapperRowLocationForTxn)old;
    } finally {
      txrs.unlock();
    }
  }

  public final void updateIndexInfos(GemFireContainer indexContainer, Object newKey) {
    final TXRegionState txrs = this.txRegionState;
    txrs.lock();
    try {
      THashMapWithKeyPair indexInfoMap = txrs.getTransactionalIndexInfoMap();

      if (indexInfoMap == null) {
        indexInfoMap = new THashMapWithKeyPair(
            ObjectEqualsHashingStrategy.getInstance(),
            getDataRegion().getCachePerfStats());
        txrs.setTransactionalIndexInfoMap(indexInfoMap);
      }
      indexInfoMap.put(this, indexContainer, newKey);
    } finally {
      txrs.unlock();
    }
  }

  public final void clearIndexInfos(GemFireContainer indexContainer) {
    final TXRegionState txrs = this.txRegionState;
    txrs.lock();
    try {
      THashMapWithKeyPair indexInfoMap = txrs.getTransactionalIndexInfoMap();
      if (indexInfoMap != null) {
        indexInfoMap.removeKeyPair(this, indexContainer);
      }
      indexInfoMap = txrs.getUnaffectedIndexInfoMap();
      if (indexInfoMap != null) {
        indexInfoMap.removeKeyPair(this, indexContainer);
      }
      // unique index replace should happen for each entry and is not a global
      // call so should not be in cleanupIndexes (#43761)
      if (this.replacedEntriesForUniqIndexes != null) {
        cleanupReplacedUniqEntries();
      }
    } finally {
      txrs.unlock();
    }
  }

  public void updateUnaffectedIndexInfos(GemFireContainer indexContainer,
      ExtractingIndexKey indexKey) {
    final TXRegionState txrs = this.txRegionState;
    txrs.lock();
    try {
      THashMapWithKeyPair indexInfoMap = txrs.getUnaffectedIndexInfoMap();

      if (indexInfoMap == null) {
        indexInfoMap = new THashMapWithKeyPair(
            ObjectEqualsHashingStrategy.getInstance(),
            getDataRegion().getCachePerfStats());
        txrs.setUnaffectedIndexInfoMap(indexInfoMap);
      }
      indexInfoMap.put(this, indexContainer, indexKey);
    } finally {
      txrs.unlock();
    }
  }

  public void addUniqIdxInfosForReplacedEntries(
      GemFireContainer indexContainer, Object key,
      WrapperRowLocationForTxn wrapper) {
    if (this.replacedEntriesForUniqIndexes == null) {
      this.replacedEntriesForUniqIndexes =
          new ArrayList<WrapperRowLocationForTxn>(4);
    }
    wrapper.setIndexContainer(indexContainer);
    wrapper.setIndexKey(key);
    this.replacedEntriesForUniqIndexes.add(wrapper);
  }

  public Object getContainerInfo() {
    if (isDirty()) {
      return getBaseContainer().getExtraTableInfo();
    }
    else if (this.regionEntry instanceof RowLocation) {
      return this.regionEntry.getContainerInfo();
    }
    else {
      return null;
    }
  }

  public Object setContainerInfo(LocalRegion owner, Object val) {
    return null;
  }

  protected final GemFireContainer getBaseContainer() {
    return (GemFireContainer)getBaseRegion().getUserAttribute();
  }

  // Unimplemented methods

  @Override
  public void checkHostVariable(int declaredLength) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public DataValueDescriptor coalesce(DataValueDescriptor[] list,
      DataValueDescriptor returnValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean compare(int op, DataValueDescriptor other,
      boolean orderedNulls, boolean nullsOrderedLow, boolean unknownRV)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue equals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public boolean getBoolean() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public byte getByte() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public byte[] getBytes() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Date getDate(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public double getDouble() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public float getFloat() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getInt() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getLength() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getLengthInBytes(DataTypeDescriptor dtd) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public long getLong() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public short getShort() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public InputStream getStream() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public String getString() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Time getTime(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Timestamp getTimestamp(Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public String getTraceString() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public String getTypeName() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue greaterThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue in(DataValueDescriptor left,
      DataValueDescriptor[] inList, boolean orderedList)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue isNotNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue lessOrEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue lessThan(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void normalize(DataTypeDescriptor dtd, DataValueDescriptor source)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public BooleanDataValue notEquals(DataValueDescriptor left,
      DataValueDescriptor right) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

 

  @Override
  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setBigDecimal(Number bigDecimal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setInto(PreparedStatement ps, int position) throws SQLException,
      StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setInto(ResultSet rs, int position) throws SQLException,
      StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setObjectForCast(Object value, boolean instanceOfResultType,
      String resultTypeClassName) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setToNull() {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(int theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(double theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(float theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(short theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(long theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(byte theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(boolean theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Object theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(byte[] theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(String theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Time theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Time theValue, Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Timestamp theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Timestamp theValue, Calendar cal)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Date theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(Date theValue, Calendar cal) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void setValue(DataValueDescriptor theValue) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(InputStream theStream, int valueLength)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(java.sql.Blob theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(java.sql.Clob theValue) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void toDataForOptimizedResultHolder(DataOutput dos) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int typePrecedence() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int typeToBigDecimal() throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public void restoreToNull() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getDSFID() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setOwner(LocalRegion owner, Object previousOwner) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean destroy(LocalRegion region, EntryEventImpl event,
      boolean inTokenMode, boolean cacheWrite, Object expectedOldValue,
      boolean forceDestroy, boolean removeRecoveredEntry)
      throws CacheWriterException, EntryNotFoundException, TimeoutException,
      RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean dispatchListenerEvents(EntryEventImpl event)
      throws InterruptedException {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public boolean fillInValue(LocalRegion r, Entry entry,
      DM mgr, Version targetVersion) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public long getHitCount() throws InternalStatisticsDisabledException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public long getLastAccessed() throws InternalStatisticsDisabledException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public long getLastModified() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void _setLastModified(long lastModified) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setLastModified(long lastModified) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isLockedForCreate() {
    // TXEntry is always locked
    return true;
  }

  @Override
  public long getMissCount() throws InternalStatisticsDisabledException
  {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Object getSerializedValueOnDisk(LocalRegion localRegion) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Object getValueInVM(RegionEntryContext context) {
    return this.getValueInVM(this.regionKey);
  }

  @Override
  public Object getValueOnDisk(LocalRegion r) throws EntryNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public Object getValueOnDiskOrBuffer(LocalRegion r)
      throws EntryNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean hasStats() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean initialImageInit(LocalRegion region, long lastModified,
      Object newValue, boolean create, boolean wasRecovered,
      boolean acceptedVersionTag) throws RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean initialImagePut(LocalRegion region, long lastModified,
      Object newValue, boolean wasRecovered, boolean acceptedVersionTag)
      throws RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isOverflowedToDisk(LocalRegion r, DiskPosition dp,
      boolean alwaysFetchPosition) {
    return false;
  }

  @Override
  public boolean isRemovedPhase2() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void removePhase1(LocalRegion r, boolean clear)
      throws RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void removePhase2(LocalRegion r) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void resetCounts() throws InternalStatisticsDisabledException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setRecentlyUsed() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setValue(RegionEntryContext context, Object value)
      throws RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void txDidDestroy(long lastModifiedTime) {
    throw new UnsupportedOperationException("unexpected invocation");

  }

  @Override
  public void updateStatsForGet(boolean hit, long time) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void updateStatsForPut(long lastModifiedTime) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public VersionStamp<?> getVersionStamp() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isTombstone() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public VersionTag<?> generateVersionTag(
      @SuppressWarnings("rawtypes") VersionSource member,
      boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
      EntryEventImpl event) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void makeTombstone(LocalRegion r,
      @SuppressWarnings("rawtypes") VersionTag isOperationRemote) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isUpdateInProgress() {
    final RegionEntry re = this.regionEntry;
    return re != null && re.isUpdateInProgress();
  }

  @Override
  public void setUpdateInProgress(boolean underUpdate) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isMarkedForEviction() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setMarkedForEviction() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void clearMarkedForEviction() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean isCacheListenerInvocationInProgress() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setCacheListenerInvocationInProgress(final boolean isListenerInvoked) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public BooleanDataValue isNullOp() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final int nCols() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final DataValueDescriptor getKeyColumn(int index) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final void getKeyColumns(DataValueDescriptor[] keys) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final void getKeyColumns(Object[] keys)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int colIdx, boolean orderedNulls, boolean unknownRV)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean compare(int op, CompactCompositeKey key, int colIdx,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public boolean canCompareBytesToBytes() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int equals(RowFormatter rf, byte[] bytes, boolean isKeyBytes,
      int logicalPosition, int keyBytesPos, final DataValueDescriptor[] outDVD)
      throws StandardException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void setRegionContext(LocalRegion region) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }
  
  @Override
  public Object prepareValueForCache(RegionEntryContext r, Object val,
      boolean isEntryUpdate, boolean valHasMetadataForGfxdOffHeapUpdate) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public final KeyWithRegionContext beforeSerializationWithValue(
      boolean valueIsToken) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public final void afterDeserializationWithValue(Object val) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public Object getOwnerId(final Object context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public boolean attemptLock(final LockMode mode, int flags,
      final LockingPolicy lockPolicy, final long msecs, final Object owner,
      final Object context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public void releaseLock(final LockMode mode, final boolean releaseAll,
      final Object owner, final Object context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public int numSharedLocks() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public int numReadOnlyLocks() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public boolean hasExclusiveLock(final Object owner, final Object context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public boolean hasExclusiveSharedLock(final Object owner, final Object context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public int getState() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public boolean hasAnyLock() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public  byte getTypeId() {
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);
  }
  
  @Override
  public  void writeNullDVD(DataOutput out) throws IOException{
    throw new UnsupportedOperationException("Implement the method for DataType="+ this);    
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValueWithTombstoneCheck(Object value, EntryEvent event)
      throws RegionClearedException {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setValueToNull(RegionEntryContext context) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void returnToPool() {
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }

  @Override
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth, ByteSource bs) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public Object getValueWithoutFaultInOrOffHeapEntry(LocalRegion owner)   {
    return super.getRetainedValueInTXOrRegion();
  }

  @Override
  public Object getValueOrOffHeapEntry(LocalRegion owner) {
   
    return this.getValue((RegionEntryContext)owner);
  }
  
  @Override
  public Object getRawValue() {
    
    return this._getValue();
  }
  @Override
  public void markDeleteFromIndexInProgress() {}

  @Override
  public void unmarkDeleteFromIndexInProgress() {}

  @Override
  public boolean useRowLocationForIndexKey() {return true;}

  @Override
  public void endIndexKeyUpdate() {}
  /*
  @Override
  public void markDeleteFromIndexInProgress() {
    int storedValue;   
    
    do {
      storedValue = this.indexKeyUseCount.get();
           
    } while (!(storedValue == 0
        && this.indexKeyUseCount.compareAndSet(storedValue,
            UPDATE_IN_PROGRESS)));
    
  }

  @Override
  public void unmarkDeleteFromIndexInProgress() {
    this.indexKeyUseCount.set(NO_INDEX_USE_NO_UPDATE);
  }

  @Override
  public boolean useRowLocationForIndexKey() {  
    int indexKeyUsers = this.indexKeyUseCount.get();
    int prevUserCount = indexKeyUsers;
    if(indexKeyUsers == 0) {
      indexKeyUsers =2;  
    }else if(indexKeyUsers > 1 && indexKeyUsers <  0xFF) {
     ++indexKeyUsers;
    }else {
      return false;
    }   
    return this.indexKeyUseCount.compareAndSet(prevUserCount, indexKeyUsers);
  }

  @Override
  public void endIndexKeyUpdate() {   
    int indexKeyUsers;
    int storedValue;
    do {
      storedValue = this.indexKeyUseCount.get();      
      indexKeyUsers = storedValue;
      assert indexKeyUsers != 1;
      
      if (indexKeyUsers > 2) {
        --indexKeyUsers;       
      }else {
        indexKeyUsers = 0;
      }
    } while (!this.indexKeyUseCount.compareAndSet(storedValue, indexKeyUsers));
    
  }*/
  
  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
