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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXState;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector.ListResultCollectorValue;
import com.pivotal.gemfirexd.internal.engine.distributed.message.BitSetSet;
import com.pivotal.gemfirexd.internal.engine.distributed.message.ContainsKeyBulkExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.BulkKeyLookupResult;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/**
 * This is an adapter class for scanning the underlying region.
 * @author yjing
 * @author swale
 * @author rdubey
 */
public class Hash1IndexScanController extends MemIndexScanController {

  protected LocalRegion baseRegion;

  protected transient DataValueDescriptor[] currentKey;

  protected transient RegionKey currentRegionKey;

  protected Iterator<?> gfKeysIterator;

  private Set<Integer> localBucketSet;
  
  protected Set<RegionKey> regionKeysSet;
  
  protected DataValueDescriptor[] failedKey = null;
  
  protected static final int NUM_KEYS_FOR_BULK_CHECKS = 10000;

  public Hash1IndexScanController() {
  }

  public int getType() {
    return MemConglomerate.HASH1INDEX;
  }

  @Override
  protected void initEnumerator() throws StandardException {

    this.baseRegion = this.baseContainer.getRegion();
    this.localBucketSet = null;
    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      GfxdIndexManager.traceIndex("Hash1IndexScanController: startKey=%s "
          + "stopKey=%s qualifier=%s for index container %s",
          this.init_startKeyValue, this.init_stopKeyValue, this.init_qualifier,
          this.openConglom.getGemFireContainer());
    }
    if (GemFireXDUtils.TraceIndex) {
      if (init_startKeyValue != null) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "Boolean1="
            + (init_startKeyValue.length == openConglom.getConglomerate()
                .keyColumns) + " startKeylength="
                + init_startKeyValue.length + " indexCols-1="
                + (openConglom.getConglomerate().keyColumns));
        if (init_qualifier != null) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "Boolean2="
              + RowUtil.qualifyRow(init_startKeyValue, null, init_qualifier, false));
        }
      }
    }
    // full index scan for bulk FK checking by RIBulkChecker
    if ((this.openMode & GfxdConstants.SCAN_OPENMODE_FOR_REFERENCED_PK) != 0) {
      this.hasNext = true;
      if (this.init_startKeyValue == null && this.init_stopKeyValue == null) {
        this.init_scanColumnList = null;
      }
      else {
        // start and stop key must be equal
        if (this.init_startKeyValue != this.init_stopKeyValue && RowUtil
            .compare(this.init_startKeyValue, this.init_stopKeyValue) != 0) {
          failScan();
        }
        // for partitioned table we accumulate keys to do bulk contains checks
        // in next()
        // for replicated table just do containsKey check in next()
        if (this.baseRegion.getDataPolicy().withPartitioning()) {
          if (regionKeysSet == null) {
            regionKeysSet = new HashSet<RegionKey>();
          }
          // need to clone init_startKeyValue to add to the set as RIBulkChecker
          // sends keys using the same DVD[] object repeatedly and therefore
          // the earlier formed keys in getRegionKey() will be changed as it could
          // just use the passed DVD for key
          DataValueDescriptor[] startKeyValueCopy = 
              new DataValueDescriptor[this.init_startKeyValue.length];
          for (int i = 0; i < this.init_startKeyValue.length; i++) {
            startKeyValueCopy[i] = this.init_startKeyValue[i].getClone();
          }
          regionKeysSet.add(getRegionKey(startKeyValueCopy));
        }
      }
    }
    else if (this.init_startKeyValue != null
        && this.init_stopKeyValue != null) {

      assert this.init_startKeyValue.length == (this.openConglom
          .getConglomerate().keyColumns);

      // get the base table partitioned region
      assert this.baseRegion != null: "The region should not be null";

      if (this.init_startKeyValue != this.init_stopKeyValue && RowUtil
          .compare(this.init_startKeyValue, this.init_stopKeyValue) != 0) {
        failScan();
      }

      if (this.init_qualifier != null && !RowUtil.qualifyRow(
          this.init_startKeyValue, null, this.init_qualifier, false)) {
        this.hasNext = false;
        return;
      }

      // generate the key of the region
      final RegionKey key;
      if (this.init_startKeyValue.length == 1) {
        key = GemFireXDUtils.convertIntoGemfireRegionKey(
            this.init_startKeyValue[0], this.baseContainer, false);
      }
      else {
        key = GemFireXDUtils.convertIntoGemfireRegionKey(this.init_startKeyValue,
            this.baseContainer, false);
      }

      // set the current key and RowLocation values
      this.currentKey = this.init_startKeyValue;
      if (this.baseRegion.getDataPolicy().withPartitioning()) {
        Set<Integer> bset = lcc == null ? null : lcc.getBucketIdsForLocalExecution();
        if (bset != null) {
          this.localBucketSet = bset;
        }
        else {
          this.localBucketSet = getLocalBucketSet(this.baseContainer,
              this.baseRegion, this.activation, "Hash1IndexScanController");
        }
        if (this.activation != null && this.localBucketSet == null
            && this.activation.getUseOnlyPrimaryBuckets()) {
          // put all primary buckets here
          if (this.baseContainer.isPartitioned()) {
            this.localBucketSet = ((PartitionedRegion)this.baseRegion)
                .getDataStore().getAllLocalPrimaryBucketIds();
          }
        }
      }
      getRowLocation(key);
    }
    else if (this.init_startKeyValue == null && this.init_stopKeyValue == null) {
      // this is the case of full scan on global index for ConsistencyChecker
      this.gfKeysIterator = this.baseRegion.keySet().iterator();
      this.init_scanColumnList = null;
      this.hasNext = true;
    }
    else {
      failScan();
    }
  }

  /**
   * @see ScanController#delete()
   */
  public boolean delete() throws StandardException {
    return false;
  }

  public void fetch(DataValueDescriptor[] destRow) throws StandardException {
    //assert this.hasNext == true;

    //try {
    if (this.currentKey == null) {
      setCurrentKey();
    }
    if (this.init_scanColumnList == null) {
      // access the whole row
      int i;
      int len = this.currentKey.length;
      if (len > destRow.length) {
        len = destRow.length;
      }
      for (i = 0; i < len; i++) {
        if (destRow[i] == null) {
          destRow[i] = this.currentKey[i].getClone();
        }
        else {
          destRow[i].setValue(this.currentKey[i]);
        }
      }
      if (i < destRow.length) {
        destRow[i] = this.currentRowLocation;
      }
    }
    else {
      final int len = destRow.length;
      for (int i = this.init_scanColumnList.anySetBit(); i > -1
          && i < len; i = this.init_scanColumnList.anySetBit(i)) {
        if (i == this.currentKey.length) {
          destRow[i] = this.currentRowLocation;
        }
        else if (destRow[i] == null) {
          destRow[i] = this.currentKey[i].getClone();
        }
        else {
          destRow[i].setValue(this.currentKey[i]);
        }
      }
    }
    this.hasNext = false;
  }

  public boolean fetchNext(DataValueDescriptor[] destRow)
      throws StandardException {
    if (this.gfKeysIterator != null) {
      // full index scan for ConsistencyChecker
      while (this.gfKeysIterator.hasNext()) {
        this.currentRegionKey = (RegionKey)this.gfKeysIterator.next();
        setCurrentKey();
        if (getRowLocation(this.currentRegionKey)) {
          fetch(destRow);
          if (init_qualifier != null) {
            if (RowUtil.qualifyRow(destRow, null, init_qualifier, true)) {
              return true;
            } else {
              continue;
            }
          } else {
            return true;
          }
        }
      }
    }
    else if (this.hasNext) {
      this.fetch(destRow);
      // Rahul : this is to fix the memory leak problem with cascasded deletes.
      // We need to revisite this and see if we should allow scanning over local
      // partitioned region entry.
      this.hasNext = false;
      return true;
    }
    else if (this.currentDataRegion != null) {
      this.currentDataRegion = null;
    }
    // check for JVM going down (#44944)
    checkCancelInProgress();
    return false;
  }

  /**
   * @see ScanController#next()
   */
  public boolean next() throws StandardException {
    if (this.currentDataRegion != null) {
      this.currentDataRegion = null;
    }
    if (this.gfKeysIterator != null) {
      // full index scan by ConsistencyChecker
      while (this.gfKeysIterator.hasNext()) {
        final RegionKey key = (RegionKey)this.gfKeysIterator.next();
        this.currentRegionKey = key;
        this.currentKey = null;
        if (getRowLocation(key)) {
          return true;
        }
      }
      // check for JVM going down (#44944)
      checkCancelInProgress();
      return false;
    }
    if ((this.openMode & GfxdConstants.SCAN_OPENMODE_FOR_REFERENCED_PK) != 0) {
      // full index scan for bulk FK checking by RIBulkChecker
      if (this.baseRegion.isEmpty()) {
        this.failedKey = this.init_startKeyValue;
        return false;
      }
      if (!this.baseRegion.getDataPolicy().withPartitioning()) {
        // single containsKey() check
        return containsKeyForReplicateTable();
      } else {
        // for performance, bulk check the FK constraints in the batches 
        // of NUM_KEYS_FOR_BULK_CHECKS
        // However this means that for last batch which could be lesser than
        // NUM_KEYS_FOR_BULK_CHECKS, we have to do the checks out side of next()
        if (regionKeysSet.size() < 
            Hash1IndexScanController.NUM_KEYS_FOR_BULK_CHECKS) {
          return true;
        }
        return containsKeyForPartitionedTable();
      }
    }
    return this.hasNext;
  }
  
  public Object[] getRoutingObjectsForKeys(Object[] regionKeysArray) {
    Object[] routingObjects = new Object[regionKeysArray.length];
    GfxdPartitionResolver refResolver = (GfxdPartitionResolver) this.baseRegion
        .getPartitionAttributes().getPartitionResolver();
    for (int i = 0; i < regionKeysArray.length; i++) {
      routingObjects[i] = refResolver.getRoutingObject(regionKeysArray[i],
          null, this.baseRegion);
    }
    return routingObjects;
  }

  /**
   * Bulk check whether a set of keys exist  
   */
  private boolean containsKeyForPartitionedTable() throws StandardException {
    if ((this.regionKeysSet == null) || (this.regionKeysSet.size() == 0)) {
      return true;
    }
    try {
      Object[] regionKeysArray = this.regionKeysSet.toArray();
      Object[] routingObjects = getRoutingObjectsForKeys(regionKeysArray);
      ContainsKeyBulkExecutorMessage msg = new ContainsKeyBulkExecutorMessage(
          this.baseRegion, regionKeysArray, routingObjects, this.txState, lcc);
      Object result = msg.executeFunction();
      assert result instanceof GfxdListResultCollector;
      ArrayList<Object> resultList = ((GfxdListResultCollector) result)
          .getResult();
      for (Object oneResult : resultList) {
        ListResultCollectorValue l = (ListResultCollectorValue) oneResult;
        BulkKeyLookupResult brs = (BulkKeyLookupResult) l.resultOfSingleExecution;
        // key does not exist
        if (!brs.exists) {
          if (brs.gfKey instanceof DataValueDescriptor[]) {
            this.failedKey = (DataValueDescriptor[]) brs.gfKey;
          } else {
            if (brs.gfKey instanceof CompactCompositeRegionKey) {
              ((CompactCompositeRegionKey) brs.gfKey)
                  .setRegionContext(this.baseRegion);
            }
            this.failedKey = new DataValueDescriptor[((RegionKey) brs.gfKey)
                .nCols()];
            ((RegionKey) brs.gfKey).getKeyColumns(this.failedKey);
          }
          return false;
        }
      }
    } catch (SQLException sqle) {
      throw Misc.wrapSQLException(sqle, sqle);
    } finally {
      // empty the set after checking all keys
      regionKeysSet.clear();
    }
    return true;
  }

  // just do containsKey() on a single key for replicated table
  private boolean containsKeyForReplicateTable() throws StandardException {
    boolean ret;
    if (this.txState != null) {
      ret = this.baseRegion.txContainsKey(getRegionKey(
          this.init_startKeyValue), null, this.txState, true);
    } else {
      ret = this.baseRegion.containsKey(
          getRegionKey(this.init_startKeyValue));
    }
    if (!ret) {
      failedKey = this.init_startKeyValue;
    }
    return ret;
  }
  
  // called for bulk FK constraint checks by RIBulkChecker for any leftover 
  // keys not checked in next()
  public boolean checkAnyAccumulatedKeys() throws StandardException {
    if (this.baseRegion.getDataPolicy().withPartitioning()) {
      return containsKeyForPartitionedTable();
    }
    return true;
  }
  
  public DataValueDescriptor[] getFailedKey() {
    return this.failedKey;
  }

  @Override
  public long getEstimatedRowCount() throws StandardException {
    return 1;
  }

  protected final void setCurrentKey() {
    if (this.currentKey == null) {
      this.currentKey = new DataValueDescriptor[this.currentRegionKey.nCols()];
    }
    this.currentRegionKey.getKeyColumns(this.currentKey);
  }

  protected boolean getRowLocation(Object key) throws StandardException {
    this.currentRowLocation = null;
    this.currentDataRegion = null;
    this.hasNext = false;
    final LocalRegion dataRegion;
    int bucketId = -1;
    try {
      final DataPolicy policy = this.baseRegion.getDataPolicy();
      if (policy.withPartitioning()) {
        PartitionedRegion pr = (PartitionedRegion)this.baseRegion;
        assert pr.getLocalMaxMemory() > 0: "Executing "
            + "a query in non data store node.";
        if (this.localBucketSet != null && this.localBucketSet.size() == 1) {
          if (this.localBucketSet instanceof BitSetSet) {
            bucketId = ((BitSetSet)this.localBucketSet).nextSetBit(0, 0);
          }
          else {
            bucketId = this.localBucketSet.iterator().next().intValue();
          }
        }
        else {
          bucketId = PartitionedRegionHelper.getHashKey(pr,
              Operation.GET_ENTRY, key, null, null);
          if (this.localBucketSet != null
              && !this.localBucketSet.contains(bucketId)) {
            if (GemFireXDUtils.TraceIndex) {
              GfxdIndexManager.traceIndex("Hash1IndexScanController"
                  + "#getRowLocation: Returning without any row as local "
                  + "bucket set %s does not contain bucketId %s",
                  this.localBucketSet, bucketId);
            }
            this.hasNext = false;
            return this.hasNext;
          }
        }
        dataRegion = pr.getDataStore().getInitializedBucketForId(key,
            bucketId);
      }
      else {
        assert policy.withStorage(): "Executing a query on non data store "
            + "node with policy: " + policy;
        dataRegion = this.baseRegion;
      }
      RegionEntry entry;
      if (this.queryHDFS) {
        entry = dataRegion.entries.getEntry(key);
      }
      else {
        entry = dataRegion.entries.getOperationalEntryInVM(key);
      }
      // the entry may disappear due to delete or bucket destroy/rebalance
      // before or during lookup (bug #39793)
      // not checking for entry destroyed here since TX checking below may raise
      // a conflict instead of entry not found
      if (entry == null) {
        return entryNotFound(key);
      }

      if (this.statNumRowsVisited != null) {
        this.statNumRowsVisited[0]++;
      }

      RowLocation rl = (RowLocation)entry;
      // check for TX value
      final TXState localTXState = this.localTXState;
      if (this.txState != null) {
        // TODO:Suranjan for local index we can start snapshot tx above and use this txState.
        if (localTXState != null && !localTXState.isEmpty()) {
          rl = (RowLocation)localTXState.getLocalEntry(this.baseRegion,
              dataRegion, -1 /* not used */, (AbstractRegionEntry)entry, this.forUpdate != 0);
          // the entry may disappear due to delete or bucket destroy/rebalance
          // before or during lookup (bug #39793)
          if (rl == null) {
            this.statNumDeletedRowsVisited++;
            return entryNotFound(key);
          }
          entry = (RegionEntry)rl;
        }
        // take SH lock to prevent the value from changing; skip if the entry is
        // already locked by this TX itself
        if (this.readLockMode != null && rl.getTXId() == null) {
          if (GemFireXDUtils.lockForRead(localTXState, this.lockPolicy,
              this.readLockMode, this.forUpdate, entry, this.baseContainer,
              dataRegion, this.observer)) {
            this.localTXState.addReadLockForScan(entry, this.readLockMode,
                dataRegion, this.lockContext);
            this.currentDataRegion = dataRegion;
          }
          else {
            this.statNumDeletedRowsVisited++;
            return entryNotFound(key);
          }
        }
        else if (entry.isDestroyedOrRemoved()) {
          this.statNumDeletedRowsVisited++;
          return entryNotFound(key);
        }
      }
      else if (entry.isDestroyedOrRemoved()) {
        this.statNumDeletedRowsVisited++;
        return entryNotFound(key);
      }
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer != null) {
        observer.beforeInvokingContainerGetTxRowLocation(rl);
      }
      // Asif:If this index is affected , skip the check as we would be taking
      // care of it during scan of the index
      this.currentRowLocation = rl;
      this.hasNext = true;
      this.statNumRowsQualified++;
    } catch (EntryNotFoundException e) {
      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("Hash1IndexScanController#getRowLocation: "
            + "entry not found for key=%s in region=%s",
            this.init_startKeyValue, this.baseRegion.getFullPath());
      }
      this.hasNext = false;
    } catch (ForceReattemptException e) {
      this.hasNext = false;
    }
    if (this.hasNext) {
      return true;
    }
    else {
      // check for JVM going down (#44944)
      checkCancelInProgress();
      return false;
    }
  }

  public static RowLocation fetchRowLocation(final Object key,
      final LocalRegion baseRegion) {
    final LocalRegion regionToWorkOn;
    int bucketId = -1;
    final DataPolicy policy = baseRegion.getDataPolicy();
    if (policy.withPartitioning()) {
      PartitionedRegion pr = (PartitionedRegion)baseRegion;
      assert pr.getLocalMaxMemory() > 0: "Executing "
          + "a query in non data store node.";
      bucketId = PartitionedRegionHelper.getHashKey(pr, Operation.GET_ENTRY,
          key, null, null);
      try {
        regionToWorkOn = pr.getDataStore().getInitializedBucketForId(key,
            bucketId);
      } catch (ForceReattemptException fre) {
        return null;
      }
    }
    else {
      assert policy.withStorage(): "Executing a query on empty data store "
          + "node with policy: " + policy;
      regionToWorkOn = baseRegion;
    }
    final RegionEntry entry = regionToWorkOn.entries.getEntry(key);
    // the entry may disappear due to delete or bucket destroy/rebalance
    // before or during lookup (bug #39793)
    if (entry != null && !entry.isDestroyedOrRemoved()) {
      return (RowLocation)entry;
    }
    return null;
  }

  protected RegionKey getRegionKey(final DataValueDescriptor[] keyArray)
      throws StandardException {
    final int size_1 = keyArray.length - 1;
    if (size_1 == 0) {
      return GemFireXDUtils.convertIntoGemfireRegionKey(keyArray[0],
          this.baseContainer, false);
    }
    if (keyArray[size_1] instanceof RowLocation) {
      if (size_1 == 1) {
        return GemFireXDUtils.convertIntoGemfireRegionKey(keyArray[0],
            this.baseContainer, false);
      }
      final DataValueDescriptor[] newKeyValue = new DataValueDescriptor[size_1];
      for (int index = 0; index < size_1; ++index) {
        newKeyValue[index] = keyArray[index];
      }
      return GemFireXDUtils.convertIntoGemfireRegionKey(newKeyValue,
          this.baseContainer, false);
    }
    return GemFireXDUtils.convertIntoGemfireRegionKey(keyArray,
        this.baseContainer, false);
  }

  private void failScan() {
    GemFireXDUtils.throwAssert("The local hash index does not support this "
        + "search operation with startKey {"
        + RowUtil.toString(this.init_startKeyValue) + "} stopKey {"
        + RowUtil.toString(this.init_stopKeyValue) + '}');
  }

  private boolean entryNotFound(final Object key)
      throws EntryNotFoundException {
    this.currentRowLocation = null;
    this.currentDataRegion = null;
    this.hasNext = false;
    //this.statNumDeletedRowsVisited++;
    // check for JVM going down (#44944)
    checkCancelInProgress();
    return false;
    //throw new EntryNotFoundException("getRowLocation: entry not found for key "
    //    + key);
  }

  @Override
  protected void closeScan() {
    this.baseRegion = null;
    this.currentKey = null;
    this.currentRegionKey = null;
    this.currentDataRegion = null;
    this.regionKeysSet = null;
    this.gfKeysIterator = null;
    this.localBucketSet = null;
  }
}
