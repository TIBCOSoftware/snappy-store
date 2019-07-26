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

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXEntryState;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap.SimpleReusableEntry;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.FetchFromMap;
import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.GfxdTXStateProxy;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemIndexOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;

/**
 * the scan controller for the SortedMap2Index.
 * <p>
 * This scan only guarantees the weak consistency between the index and its base
 * table. In other words, some inserted rows during the scan process could miss
 * and it could return row locations, whose referenced rows do not exist in the
 * base table.
 * 
 * 
 * @author jing
 */
public final class SortedMap2IndexScanController extends MemIndexScanController
    implements FetchFromMap {

  /** The interface for navigating the index */
  private ConcurrentSkipListMap<Object, Object> skipListMap;

  // the RowLocation to be found
  private RowLocation initRowLocation;

  /** current key of the iterator */
  private Object currentKey;

  /** current key in the skiplist map */
  private Object currentIndexKey;

  /** version of the current node when it was first read */
  private int currentNodeVersion;

  private Object lookupValue;

//  private DataValueDescriptor[] initializedColumns;

  private AbstractRowLocationIterator rlIterator;

  private RowLocationArrayIterator cachedArrayIterator;
  // this is not just a cache but also required to close it properly
  // and release the read lock on the set
  private RowLocationSetIterator cachedSetIterator;

  //private boolean movedToNextIndexKey;

  private Iterator<?> sourceIterator;

  private boolean fullIteratorForStats;

  private LocalRegion baseRegion;

  /** the list of elements will be accessed */
  private int[] keyScanList;

  private boolean containRowLocation;

  private boolean needScanKey;

  private boolean[] localBucketSet;
  
  private int scanKeyGroupID =0;

  protected final int[] lockFlags = new int[1];

  public SortedMap2IndexScanController() {
  }

  public int getType() {
    return MemConglomerate.SORTEDMAP2INDEX;
  }

  /**
   * @throws StandardException
   */
  private void createKeyScanList(FormatableBitSet scanColumnList,
      int rowLocationColumn) throws StandardException {

    final int numBitsSet;
    this.needScanKey = true;
    if (scanColumnList == null
        || (numBitsSet = scanColumnList.getNumBitsSet()) == 0) {

      this.keyScanList = null;
      this.containRowLocation = true;
      return;
    }

    // determine if the scan column list containing row location column.
    if (scanColumnList.getLength() > rowLocationColumn) {
      this.containRowLocation = scanColumnList.isSet(rowLocationColumn);
    }
    else {
      this.containRowLocation = false;
    }

    int numKeyScanList = this.containRowLocation ? numBitsSet - 1 : numBitsSet;

    if (numKeyScanList == 0) {
      this.needScanKey = false;
      return;
    }
    this.keyScanList = new int[numKeyScanList];
    // System.out.println("The scanColumnList:"+scanColumnList.toString());
    for (int i = scanColumnList.anySetBit(), j = 0; i != -1
        && j < numKeyScanList; i = scanColumnList.anySetBit(i), ++j) {
      this.keyScanList[j] = i;
    }
  }

  @Override
  protected void postProcessSearchCondition() throws StandardException {

    this.createKeyScanList(this.init_scanColumnList,
        this.openConglom.getConglomerate().rowLocationColumn);
    // the RowLocation in the start key
    RowLocation startKeyRL = null;

    // the RowLocation in the stop key
    RowLocation stopKeyRL = null;
    if (this.init_startKeyValue != null
        && (startKeyRL = getRowLocation(this.init_startKeyValue)) != null) {
      this.init_startKeyValue = removeRowLocation(this.init_startKeyValue);
    }

    if (this.init_stopKeyValue != null
        && (stopKeyRL = getRowLocation(this.init_stopKeyValue)) != null) {
      this.init_stopKeyValue = removeRowLocation(this.init_stopKeyValue);
    }

    if (SanityManager.DEBUG) {
      // two keys contains the RowLocation or none at the same time
      if ((startKeyRL == null) != (stopKeyRL == null)) {
        SanityManager.THROWASSERT("unexpected mismatch of null values "
            + "of start and stop RowLocations");
      }

      // the RowLocation in the start key and stop key must be equal
      if (startKeyRL != null && stopKeyRL != null && startKeyRL != stopKeyRL) {
        SanityManager
            .THROWASSERT("unexpected mismatch of start and stop RowLocations");
      }
    }

    this.initRowLocation = startKeyRL;

    if (this.skipListMap == null) {
      this.skipListMap = this.openConglom.getGemFireContainer()
          .getSkipListMap();
      if (SanityManager.DEBUG) {
        SanityManager.ASSERT(skipListMap != null,
            "this index container does not exist!");
      }
    }
  }

  @Override
  protected void initEnumerator() throws StandardException {

    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      GfxdIndexManager.traceIndex("SortedMap2IndexScanController#initEnum: "
          + "container %s startKey=%s, stopKey=%s",
          this.openConglom.getConglomerate(), this.init_startKeyValue,
          this.init_stopKeyValue);
    }

    // Reset the rowlocation iterators. Refer Bug 42699. The scan may be opened
    // once and reinitialized multiple times with different start/stop keys for
    // e.g. while processing equijoins. In such cases, the previous key's
    // (partially exhausted)row location iterator should not get used.
    this.rlIterator = null;
    if (this.cachedArrayIterator != null) {
      this.cachedArrayIterator = null;
    }
    final RowLocationSetIterator rlSetItr = this.cachedSetIterator;
    if (rlSetItr != null) {
      rlSetItr.close();
      this.cachedSetIterator = null;
    }
    /*
    if (this.movedToNextIndexKey) {
      this.movedToNextIndexKey = false;
    }*/
    this.scanKeyGroupID = 0;
    this.sourceIterator = null;
    this.fullIteratorForStats = false;
    this.currentDataRegion = null;

    final GemFireContainer container = this.openConglom
        .getGemFireContainer();

    if (this.init_startSearchOperator == MAX) {
      this.sourceIterator = this.skipListMap
          .descendingMap(this.statNumRowsVisited).entrySet().iterator();
      container.incRangeScanStats();
    }
    else {

      // these must be ignored if the corresponding key is null
      final boolean fromInclusive =
          (this.init_startSearchOperator == ScanController.GE);
      final boolean toInclusive =
          (this.init_stopSearchOperator == ScanController.GT);

      if ((this.init_startKeyValue == null) &&
          (this.init_stopKeyValue == null)) {
        this.sourceIterator = this.skipListMap.entrySet().iterator();
        container.incRangeScanStats();
        if (this.statNumRowsVisited != null) {
          this.fullIteratorForStats = true;
        }
      }
      else if ((this.init_startKeyValue == null)
          && (this.init_stopKeyValue != null)) {
        this.sourceIterator = this.skipListMap.headMap(
            OpenMemIndex.newLocalKeyObject(this.init_stopKeyValue,
                this.openConglom.getGemFireContainer()), toInclusive,
            this.statNumRowsVisited).entrySet().iterator();
        container.incRangeScanStats();
      }
      else if ((this.init_startKeyValue != null)
          && (this.init_stopKeyValue == null)) {
        this.sourceIterator = this.skipListMap.tailMap(
            OpenMemIndex.newLocalKeyObject(this.init_startKeyValue,
                this.openConglom.getGemFireContainer()), fromInclusive,
            this.statNumRowsVisited).entrySet().iterator();
        container.incRangeScanStats();
      }
      else {
        // check if we can do just a get lookup and use the passed start key
        // itself as the currentKey
        // don't apply this optimization for non-byte array store to avoid
        // the lookup problem with non-blank padded CHAR values
        if (init_startKeyValue == this.init_stopKeyValue && fromInclusive
            && this.baseContainer.isByteArrayStore()) {
          // If there are CHAR columns in the init_startKeyValue,
          // And the length of the value is less than the PK descriptor,
          // Blank-pad the init_startKeyValue values before get().
//See #47148          
//          for (int i = 0; i < this.init_startKeyValue.length; i++) {
//            final DataValueDescriptor key = this.init_startKeyValue[i];
//            if (key.getTypeFormatId() == StoredFormatIds.SQL_CHAR_ID
//                && !key.isNull()) {
//              int maxLength = this.openConglom.getGemFireContainer()
//                  .getExtraIndexInfo().getPrimaryKeyFormatter()
//                  .getColumnDescriptor(i).getType().getMaximumWidth();
//              if (key.getLength() < maxLength) {
//                // Blank-pad the value up to maxLength using String.format
//                // String.format ("%-10s","hello") pads 5 blanks after 'hello'
//                // (Left-justified expanding to 10 bytes)
//                //String paddedValString = String.format("%-" + maxLength + "s",
//                //    key.getString());
//                final char[] chars = new char[maxLength];
//                int len = ((SQLChar)key).getCharArray(chars, 0);
//                SQLChar.appendBlanks(chars, len, maxLength - len);
//                // Set value to the padded string
//                key.setValue(ClientSharedUtils.newWrappedString(
//                    chars, 0, maxLength));
//              }
//            }
//          }
//          this.initializedColumns = this.init_startKeyValue;
          if (this.openConglom.getGemFireContainer().
              numColumns() == this.init_startKeyValue.length) {
            this.sourceIterator = null;
            this.lookupValue = this.skipListMap.get(OpenMemIndex
                .newLocalKeyObject(this.init_startKeyValue, container), this,
                null);
            if(this.lookupValue == MemIndexOperation.TOK_INDEX_KEY_DEL) {
              this.lookupValue = null;
            }
            container.incPointStats();
          }
          else {
            this.sourceIterator = this.skipListMap.subMap(
                OpenMemIndex.newLocalKeyObject(this.init_startKeyValue,
                    container), fromInclusive,
                OpenMemIndex.newLocalKeyObject(this.init_stopKeyValue,
                    container), toInclusive,
                this.statNumRowsVisited).entrySet().iterator();
            container.incRangeScanStats();
          }
        }
        else {
          this.sourceIterator = this.skipListMap.subMap(
              OpenMemIndex.newLocalKeyObject(this.init_startKeyValue,
                  container), fromInclusive,
              OpenMemIndex.newLocalKeyObject(this.init_stopKeyValue,
                  container), toInclusive,
              this.statNumRowsVisited).entrySet().iterator();
        }
      }
    }

    // adjust the qualifier to wrap in BinarySQLHybridType in case the
    // currentKey will be a CompactCompositeIndexKey
    if (this.sourceIterator != null && this.init_qualifier != null
        && this.baseContainer.isByteArrayStore()) {
      final RowFormatter rf = this.openConglom.getGemFireContainer()
          .getExtraIndexInfo().getPrimaryKeyFormatter();
      for (int idx = init_qualifier.length - 1; idx >= 0; idx--) {
        for (Qualifier q : init_qualifier[idx]) {
          if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceByteComparisonOptimization) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION,
                  "attempting to re-align qualifier " + q);
            }
          }
          q.alignOrderableCache(rf.getColumnDescriptor(q.getColumnId()), null);
        }
      }
    }

    this.baseRegion = this.baseContainer.getRegion();
    if (this.localBucketSet == null) {
      boolean bucketSetGotFromSingleHop = false;
      if (lcc != null) {
        Set<Integer> bset = lcc.getBucketIdsForLocalExecution();
        Region<?, ?> regionForBSet = null;
        boolean prpLEItr = false;
        if (bset != null) {
          regionForBSet = lcc.getRegionForBucketSet();
          if (regionForBSet == this.baseRegion) {
            prpLEItr = true;
          }
        }
        
        if (GemFireXDUtils.TraceQuery || SanityManager.TraceSingleHop) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
              "SortedMap2IndexScanController::initEnumerator bucketSet: "
                  + bset + " and forUpdate=" + (this.forUpdate != 0)
                  + " base region is: " + this.baseRegion + " and lcc: " + lcc
                  + " and region is: " + (regionForBSet != null
                      ? regionForBSet.getName() : "(null)"));
        }
        if (prpLEItr) {
          this.localBucketSet = getBucketSet(bset);
          bucketSetGotFromSingleHop = true;
        }
      }

      if (!bucketSetGotFromSingleHop) {
        this.localBucketSet = getBucketSet(getLocalBucketSet(container, this.baseRegion,
            this.activation, "SortedMap2IndexScanController"));
      }
    }
    
    if (this.activation != null && this.localBucketSet == null
        && this.activation.getUseOnlyPrimaryBuckets()) {
      // put all primary buckets here
      if (this.baseContainer.isPartitioned()) {
        this.localBucketSet = getBucketSet(((PartitionedRegion)this.baseRegion)
            .getDataStore().getAllLocalPrimaryBucketIds());
      }
    }
    this.hasNext = true;
  }

  private boolean[] getBucketSet(Set<Integer> bucketSet) {
    if (bucketSet != null) {
      int maxBucketId = ((PartitionedRegion)this.baseRegion).getTotalNumberOfBuckets();
      boolean[] buckets = new boolean[maxBucketId + 1];
      for (int b : bucketSet) {
        buckets[b] = true;
      }
      return buckets;
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setMapKey(Object key, int version) {
    this.currentIndexKey = key;
    this.currentNodeVersion = version;
  }

  @Override
  public final Object getCurrentKey() {
    return this.currentIndexKey;
  }

  @Override
  public final int getCurrentNodeVersion() {
    return this.currentNodeVersion;
  }

  private RowLocation getRowLocation(DataValueDescriptor[] searchKey) {
    final int lastIndex;
    if (searchKey == null || !(searchKey[lastIndex = (searchKey.length - 1)]
        instanceof RowLocation)) {
      return null;
    }
    return (RowLocation)searchKey[lastIndex];
  }

  public boolean delete() throws StandardException {
    final GemFireContainer container = this.openConglom.getGemFireContainer();
    Object value ;
    if(this.baseContainer.isOffHeap()) {
      value = ((OffHeapRegionEntry)this.currentRowLocation).getAddress();
    }else {
      value = this.currentRowLocation.getValueOrOffHeapEntry(baseRegion);
    }
    return SortedMap2IndexDeleteOperation.doMe(this.tran, container,
        OpenMemIndex.newLocalKeyObject(this.currentKey, container),
        // cloning the value since this can be the searchRowLocation which
        // is set from a template from top; since it will only be used by DD
        // operations there is no problem of efficiency
        this.currentRowLocation, this.openConglom.isUnique(), value);
  }

  public final boolean next() throws StandardException {

    if (!this.hasNext) {
      this.currentDataRegion = null;
      return false;
    }
    boolean ret = false;
    for (;;) {
      RowLocation rl;
      LocalRegion dataRegion = null;
      this.currentDataRegion = null;
      if (this.rlIterator == null) {
        rl = moveRowLocationIterator();
        if (rl == null) {
          this.currentRowLocation = null;
          ret = false;
          break;
        }
      }
      else {
        if ((rl = this.rlIterator.next()) != null) {
          if (this.statNumRowsVisited != null) {
            this.statNumRowsVisited[0]++;
          }
        }
        else {
          rl = moveRowLocationIterator();
          if (rl == null) {
            this.currentRowLocation = null;
            ret = false;
            break;
          }
        }
      }

      // Current entry maybe non-transactional since it is coming from an
      // index directly unaffected by it. So check if entry is locked and
      // then lookup the entry in local TXState and get that RowLocation.
      final TXState localTXState = this.localTXState;
      final int bucketId = rl.getBucketID();
      if (localTXState != null && !localTXState.isEmpty()
          && rl.getTXId() == null) {
        // this TX may have local state for this entry
        dataRegion = this.baseRegion.getDataRegionForRead(rl.getKey(), null,
            bucketId, Operation.GET_ENTRY);
        // TODO: Suranjan For index scan snapshot we can start snapshot tx and use that.
        rl = (RowLocation)localTXState.getLocalEntry(this.baseRegion,
            dataRegion, bucketId, (AbstractRegionEntry)rl, this.forUpdate != 0);
        if (rl == null) {
          this.currentRowLocation = null;
          ret = false;
          break;
        }
      }

      this.currentRowLocation = rl;
      final GemFireXDQueryObserver observer = this.observer;
      if (observer != null) {
        observer.beforeInvokingContainerGetTxRowLocation(rl);
      }

      ret = false;
      if (this.initRowLocation == null || rl == this.initRowLocation) {
        ret = true;
        if (this.readLockMode != null && !this.txId.equals(rl.getTXId())) {
          final RegionEntry entry = rl.getUnderlyingRegionEntry();
          if (dataRegion == null) {
            dataRegion = this.baseRegion.getDataRegionForRead(rl.getKey(),
                null, bucketId, Operation.GET_ENTRY);
          }
          if (this.forReadOnly) {
            this.lockFlags[0] = 0;
            if (localTXState.getProxy().lockEntry(entry, entry.getKey(),
                GemFireXDUtils.getRoutingObject(bucketId), this.baseRegion,
                dataRegion, false, TXEntryState.getReadOnlyOp(),
                this.lockFlags) == null) {
              continue;
            }
            // go for possible cleanup only if lock was added
            if (this.lockFlags[0] == TXState.LOCK_ADDED) {
              this.currentDataRegion = dataRegion;
            }
          }
          else {
            if (GemFireXDUtils.lockForRead(localTXState, this.lockPolicy,
                this.readLockMode, this.forUpdate, entry, this.baseContainer,
                dataRegion, this.observer)) {
              this.currentDataRegion = dataRegion;
              localTXState.addReadLockForScan(entry, this.readLockMode,
                  dataRegion, this.lockContext);
            }
            else {
              continue;
            }
          }
        }

        if (GemFireXDUtils.TraceIndex && ret) {
          
          if (this.currentRowLocation != null) {
            ExecRow row = this.currentRowLocation
                .getRowWithoutFaultIn(this.baseContainer); 
            try {
            final Object regionKey = this.currentRowLocation.getKey();
            GfxdIndexManager.traceIndex("SortedMap2IndexScanController: "
                + "Entry found for region key=%s, bucketID=%s, "
                + "baseContainer=%s, TX=%s (current TX: %s): %s", regionKey,
                this.currentRowLocation.getBucketID(), this.baseContainer,
                this.currentRowLocation.getTXId(), this.txState,row
                );
            }finally {
              if (row != null) {
                row.releaseByteSource();
              }
            }
          }
        }
        break;
      }
    }

    if (ret) {
      if (this.init_startSearchOperator == MAX) {
        this.hasNext = false;
      }
      statNumRowsQualified++;
      return true;
    }
    else {
      // check for JVM going down (#44944)
      checkCancelInProgress();
      return false;
    }
  }

  @Override
  public int sizeOfIndex() {
    int retval = 0;
    final Iterator<Object> itr = this.skipListMap.values().iterator();
    while (itr.hasNext()) {
      final Object value = itr.next();

      if (value != null) {
        final Class<?> valueCls = value.getClass();

        if (valueCls == RowLocation[].class) {
          retval += ((RowLocation[])value).length;
        }
        else if (valueCls == ConcurrentTHashSet.class) {
          retval += ((ConcurrentTHashSet<?>)value).size();
        }
        else {
          assert value instanceof RowLocation: "unexpected type in index "
              + valueCls.getName() + ": " + value;
          retval++;
        }
      }
    }
    return retval;
  }

  public void fetch(DataValueDescriptor[] destRow) throws StandardException {
    this.assembleRow(destRow);
  }

  /**
   * Assemble the key and RowLocation value into a row.
   */
  private final void assembleRow(DataValueDescriptor[] destRow)
      throws StandardException {

    int num = 0;
    if (this.needScanKey) {
      final Class<?> keyCls = this.currentKey.getClass();
      // we don't expect a subclass of CompactCompositeIndexKey ever as the
      // resident index key
      if (keyCls == CompactCompositeIndexKey.class) {
        // avoid deserializing columns already present in start/stop key for
        // the case when both are equal
        num = GemFireXDUtils.fetchValue(
            (CompactCompositeIndexKey)this.currentKey, destRow,
            /*this.initializedColumns,*/ this.keyScanList);
      }
      else if (keyCls == DataValueDescriptor[].class) {
        num = GemFireXDUtils.fetchValue((DataValueDescriptor[])this.currentKey,
            destRow, this.keyScanList);
      }
      else {
        if (this.keyScanList == null) {
          assert destRow[0] != null;
          destRow[0].setValue((DataValueDescriptor)this.currentKey);
        }
        else {
          destRow[this.keyScanList[0]]
              .setValue((DataValueDescriptor)this.currentKey);
        }
        num = 1;
      }
    }

    if (this.containRowLocation && num < destRow.length) {
      num = destRow.length - 1; // the last field must be RowLocation
      assert destRow[num] != null;
      destRow[num] = this.currentRowLocation;
    }
  }

  /**
   * Get RowLocation-object from index.
   */
  public final boolean fetchNext(DataValueDescriptor[] destRow)
      throws StandardException {

    if (next()) {
      if (destRow.length > 0) {
        assembleRow(destRow);
      }
      return true;
    }
    return false;
  }

  @Override
  public final void fetch(ExecRow destRow) throws StandardException {
    // TODO: PERF: can we have CompactExecRows corresponding to index key
    // columns here and avoid converting to DVD[]; we will need a ProjectionRow
    // extension of CompactExecRow that can carry a projection through till
    // the very end, or an appropriate RowFormatter inside CompactExecRow
    assert destRow instanceof ValueRow || destRow instanceof ExecIndexRow:
      "unexpected destRow " + destRow;

    assembleRow(destRow.getRowArray());
  }

  @Override
  public final boolean fetchNext(ExecRow destRow) throws StandardException {
    // TODO: PERF: can we have CompactExecRows corresponding to index key
    // columns here and avoid converting to DVD[]; we will need a ProjectionRow
    // extension of CompactExecRow that can carry a projection through till
    // the very end, or an appropriate RowFormatter inside CompactExecRow
    assert destRow instanceof ValueRow || destRow instanceof ExecIndexRow:
      "unexpected destRow " + destRow;

    final DataValueDescriptor[] rowArray = destRow.getRowArray();
    if (next()) {
      if (rowArray.length > 0) {
        assembleRow(rowArray);
      }
      return true;
    }
    return false;
  }

  @Override
  public long getEstimatedRowCount() throws StandardException {

    if (this.estimatedRowCount == -1L) {

      // !!!:ezoerner:20080325 it is very expensive to ask a
      // concurrent skiplist for its size. Get the row count from the base
      // table instead. This may need to be changed once we start working
      // with partitioned data
      /* soubhik20081223: 
       * now getNumRows is more efficient so using it instead
       * of LinkedList based skiplist.size() which is costly.
       * 
       * Derby created statistics is no good for us as it gets 
       * updated whenever there is a table scan query executed 
       * not per DML operation.
       * 
       * This is important because if this index row count changes
       * rapidly then query plans will get recompiled based on this
       * latest info. 
       * 
       * Probably we should re-compile based on the ratio between index
       * rows to base rows instead of simply number of index
       * entries as cost computation depends on that assuming uniform 
       * distribution.
       * 
       * caching esitmatedRowCount is of no use as scan controllers are
       * created from every *ResultSet(s). Lets cache NumRows.
       */
      return (this.estimatedRowCount = this.baseContainer.getNumRows());
      /* return (long)skiplist.size(); */
      //return (long)this.skipListMap.size();
    }
    return this.estimatedRowCount;
  }

  @Override
  protected void closeScan() {
    this.skipListMap = null;
    this.localBucketSet = null;
    this.initRowLocation = null;
    this.currentKey = null;
    this.currentIndexKey = null;
    this.rlIterator = null;
    if (this.cachedArrayIterator != null) {
      this.cachedArrayIterator = null;
    }
    final RowLocationSetIterator rlSetItr = this.cachedSetIterator;
    if (rlSetItr != null) {
      rlSetItr.close();
      this.cachedSetIterator = null;
    }
    /*if (this.movedToNextIndexKey) {
      this.movedToNextIndexKey = false;
    }*/
    this.scanKeyGroupID = 0;
    this.sourceIterator = null;
    this.lookupValue = null;
//    this.initializedColumns = null;
    this.keyScanList = null;
    this.fullIteratorForStats = false;
    this.baseRegion = null;
    this.currentDataRegion = null;
    this.observer = null;
  }

  @Override
  public final int getScanKeyGroupID() {
	  return this.scanKeyGroupID;
  }

  /*
   * * Standard toString() method. Prints out current position in scan.
   */
  @Override
  public String toString() {

    if (SanityManager.DEBUG) {
      String string = "\nSortedMap2IndexScanController\tskiplist-congid = "
          + (this.baseContainer != null ? this.baseContainer.getId() : -1)

          /* "\n\trh:" + scan_position.current_rh + */
          + "\n\tinit_scanColumnList = "
          + init_scanColumnList
          + "\n\tinit_scanColumnList.size() = "
          + ((init_scanColumnList != null) ? init_scanColumnList.size() : 0)
          +

          "\n\tinit_startKeyValue = "
          + RowUtil.toString(init_startKeyValue)
          + "\n\tinit_startSearchOperator = "
          + ((init_startSearchOperator == ScanController.GE) ? "GE"
              : ((init_startSearchOperator == ScanController.GT) ? "GT"
                  : Integer.toString(init_startSearchOperator)))
          + "\n\tinit_qualifier[]         = "
          + Arrays.deepToString(init_qualifier)
          + "\n\tinit_stopKeyValue = "
          + RowUtil.toString(init_stopKeyValue)
          + "\n\tinit_stopSearchOperator = "
          + ((init_stopSearchOperator == ScanController.GE) ? "GE"
              : ((init_stopSearchOperator == ScanController.GT) ? "GT"
                  : Integer.toString(init_stopSearchOperator)));

      return (string);
    }
    else {
      return null;
    }
  }

  /**
   * 
   * @author Asif
   */
  static abstract class AbstractRowLocationIterator {

    final TXStateInterface txState;
    final GemFireContainer container;
    final boolean[] localBucketSet;
    final int openMode;

    AbstractRowLocationIterator(final TXStateInterface tx,
        final GemFireContainer container, final boolean[] localBucketSet,
        final int openMode) {
      this.txState = tx;
      this.container = container;
      this.localBucketSet = localBucketSet;
      this.openMode = openMode;
    }

    public static final RowLocation isRowLocationValidForTransaction(
        final RowLocation rl, final TXId rlTXId, final TXStateInterface tx,
        final int openMode, final boolean isUniqIndex) {
      RowLocation ret = null;
      final boolean sameTransaction = tx != null
          && rlTXId.equals(tx.getTransactionId());
      // if this is a scan from another transaction requiring locks then the
      // entry should be visible so other TX can try to lock it and fail or wait
      // only required for reference key checking so that existing transactional
      // entry, if any, can conflict and fail while for others we don't read
      // "dirty" uncommitted entries
      if (!sameTransaction && tx != null
          && (openMode & GfxdConstants.SCAN_OPENMODE_FOR_READONLY_LOCK) != 0) {
        return rl;
      }
      if (rl.getClass() == WrapperRowLocationForTxn.class) {
        final WrapperRowLocationForTxn wrl = (WrapperRowLocationForTxn)rl;
        if (GfxdTXStateProxy.LOG_FINE | GemFireXDUtils.TraceIndex) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "Iterator returning entry deleted in transaction " + rlTXId
                  + " for current TX " + TXManagerImpl.getCurrentTXState()
                  + " (GFXD cached TX: " + tx + "): "
                  + ArrayUtils.objectRefString(wrl.getRegionEntry()));
        }
        if (!sameTransaction) {
          ret = (RowLocation)wrl.getRegionEntry();
        }
        else {
          ret = wrl.needsToBeConsideredForSameTransaction();
        }
      }
      else {
        assert rl instanceof GfxdTXEntryState:
          "unknown type of transactional RowLocation " + rl;
        if (GfxdTXStateProxy.LOG_FINE | GemFireXDUtils.TraceIndex) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "Iterator returning entry inserted in transaction " + rlTXId
                  + " for current TX " + TXManagerImpl.getCurrentTXState()
                  + " (GFXD cached TX: " + tx + "): "
                  + ArrayUtils.objectRefString(rl));
        }
        if (sameTransaction) {
          ret = rl;
        // check if it is a case of unique index and then see if the underlying region entry
        // should be returned or not.
        } else if (isUniqIndex) {
          ret = (RowLocation) ((GfxdTXEntryState)rl).getCommittedEntry();
        }
      }
      return ret;
    }

    public static final RowLocation isRowLocationValid(RowLocation rowloc,
        final LanguageConnectionContext lcc, final TXStateInterface tx,
        final GemFireContainer container, final boolean[] localBucketSet,
        final int openMode, final OpenMemIndex openConglom) {
      final TXId rlTXId;

      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("SortedMap2IndexScanController#"
            + "isRowLocationValid: For index baseContainer %s checking if "
            + "RowLocation [%s] is valid for TX %s, openMode=%s, buckets: %s",
            container.getId(), ArrayUtils.objectRefString(rowloc),
            tx != null ? tx.getTransactionId() : "(null)", Integer
                .toHexString(openMode), localBucketSet);
      }
      if ((rlTXId = rowloc.getTXId()) != null) {
        // below will tell whether RowLocation has to be skipped for this
        // transaction and also unwrap a TX deleted row if required
        rowloc = isRowLocationValidForTransaction(rowloc, rlTXId, tx,
            openMode, openConglom.isUnique());
        if (rowloc == null) {
          if (GfxdTXStateProxy.LOG_FINEST | GemFireXDUtils.TraceIndex) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                "SortedMap2IndexScanController#isRowLocationValid: returning "
                    + "null RowLocation due to TX mismatch (" + rlTXId + ')');
          }
          return null;
        }
        // for non-null RowLocation, check further for bucketID below
      }

      final int bucketID = rowloc.getBucketID();
      if (bucketID > -1 && container.hasBucketRowLoc()) {
        if (localBucketSet != null && !localBucketSet[bucketID]) {
          if (GemFireXDUtils.TraceIndex) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                "SortedMap2IndexScanController#isRowLocationValid: returning "
                    + "null RowLocation due to bucketId mismatch for " + rowloc);
          }
          return null;
        }
        // Neeraj: The assertion error below is not correct as when
        // ReferenceKeyCheckerFunction
        // is executed on all members then localbucket set is null.
        // assert ((PartitionedRegion)this.container.getRegion())
        // .getRegionAdvisor().getNumProfiles() == 0;
      }
      else {
        assert container == null || !container.isPartitioned()
            || (!container.isRedundant() && !container.isOverFlowType()):
              "unexpected bucketID < 0 " + bucketID + " for RowLocation ("
              + rowloc + ") in baseContainer: " + container;
      }
      if (!rowloc.getRegionEntry().isDestroyedOrRemoved()) {
        // skip rows being deleted from self-reference checks
        final Collection<RowLocation> refCheckRows;
        if (lcc != null
            && (refCheckRows = lcc.getReferencedKeyCheckRows()) != null) {
          if (refCheckRows.contains(rowloc)) {
            if (GemFireXDUtils.TraceIndex) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
                  "SortedMap2IndexScanController#isRowLocationValid: returning "
                      + "null RowLocation due to existing self-reference row");
            }
            return null;
          }
        }
        if (GemFireXDUtils.TraceIndex) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
              "SortedMap2IndexScanController#isRowLocationValid: returning "
                  + "valid RowLocation " + rowloc);
        }
        return rowloc;
      }
      if (GemFireXDUtils.TraceIndex) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
            "SortedMap2IndexScanController#isRowLocationValid: returning "
                + "null RowLocation due to existing existing entry destroyed");
      }
      return null;
    }

    protected abstract RowLocation next() throws StandardException;
  }

  private final class RowLocationArrayIterator extends
      AbstractRowLocationIterator {

    private RowLocation[] rlArray;
    private int index;

    RowLocationArrayIterator(final RowLocation[] array,
        final TXStateInterface tx, final GemFireContainer con,
        final boolean[] localBucketSet, final int openMode) {
      super(tx, con, localBucketSet, openMode);
      this.rlArray = array;
      this.index = 0;
    }

    @Override
    public RowLocation next() throws StandardException {
      for (;;) {
        if (this.index < this.rlArray.length) {
          RowLocation rl = isRowLocationValid(this.rlArray[this.index], lcc,
              txState, this.container, this.localBucketSet, openMode, openConglom);
          this.index++;
          if (rl != null) {
            return rl;
          }
          else {
            statNumDeletedRowsVisited++;
          }
        }
        else {
          return null;
        }
      }
    }

    void reset(RowLocation[] newArray) {
      this.rlArray = newArray;
      this.index = 0;
    }
  }

  /**
   * 
   * @author Asif
   */
  private final class RowLocationSetIterator extends
      AbstractRowLocationIterator {

    private Object[] itrCache;
    private int itrPos;

    private ConcurrentTHashSet<?>.Itr itr;
    private RSLockManager lockManager;

    RowLocationSetIterator(final ConcurrentTHashSet<?> vals,
        final TXStateInterface tx, final GemFireContainer con,
        final boolean[] localBucketSet, final int openMode) {
      super(tx, con, localBucketSet, openMode);
      init(vals, tx);
    }

    @Override
    public RowLocation next() throws StandardException {
      final ConcurrentTHashSet<?>.Itr itr;
      final Object[] itrCache;

      if ((itrCache = this.itrCache) != null) {
        final int itrLen = itrCache.length;
        while (itrPos < itrLen) {
          RowLocation rl = (RowLocation)itrCache[itrPos++];
          rl = isRowLocationValid(rl, lcc, txState, this.container,
              this.localBucketSet, openMode, openConglom);
          if (rl != null) {
            return rl;
          }
          else {
            statNumDeletedRowsVisited++;
          }
        }
      }
      else if ((itr = this.itr) != null) {
        while (itr.hasNext()) {
          RowLocation rl = isRowLocationValid((RowLocation)itr.next(), lcc,
              txState, this.container, this.localBucketSet, openMode, openConglom);
          if (rl != null) {
            return rl;
          }
          else {
            statNumDeletedRowsVisited++;
          }
        }
      }
      return null;
    }

    private final void init(final ConcurrentTHashSet<?> vals,
        final TXStateInterface tx) {
      final RSLockManager lm;
      if (tx == null) {
        this.itr = vals.iterator();
      }
      else if (vals.size() <= DistributedRegion.MAX_PENDING_ENTRIES) {
        this.itrCache = vals.toArray();
        this.itrPos = 0;
        if ((lm = this.lockManager) != null) {
          lm.unlock();
        }
        this.itr = null;
      }
      else {
        if ((lm = this.lockManager) != null) {
          lm.init(vals);
        }
        else {
          this.lockManager = new RSLockManager(this, vals);
        }
        this.itr = vals.iterator();
        this.itrCache = null;
        this.itrPos = 0;
      }
    }

    final void reset(final ConcurrentTHashSet<?> vals) {
      init(vals, this.txState);
    }

    private final void close() {
      final RSLockManager lm = this.lockManager;
      if (lm != null) {
        lm.clearAll();
        this.lockManager = null;
      }
      this.itr = null;
      this.itrCache = null;
      this.itrPos = 0;
    }
  }

  @SuppressWarnings("serial")
  private static final class RSLockManager extends FinalizeObject {

    private ConcurrentTHashSet<?> set;

    RSLockManager(RowLocationSetIterator itr, ConcurrentTHashSet<?> set) {
      super(itr, true);
      init(set);
    }

    void init(final ConcurrentTHashSet<?> set) {
      unlock();
      if (set != null) {
        set.lockAllSegmentsForRead();
        this.set = set;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean doFinalize() throws Exception {
      unlock();
      return true;
    }

    final void unlock() {
      final ConcurrentTHashSet<?> set = this.set;
      if (set != null) {
        set.unlockAllSegmentsAfterRead();
        this.set = null;
      }
    }

    @Override
    public final FinalizeHolder getHolder() {
      return getServerHolder();
    }

    @Override
    protected void clearThis() {
      unlock();
    }
  }

  private final RowLocation moveRowLocationIterator() throws StandardException {

    RowLocation rl;
    for (;;) {
      // the current value is a RowLocation object or we have reached the
      // end of the array, then get next qualifying entry in the Map.
      Object nextValue = nextMapValue();

      if (nextValue != null) {
        // need to check the qualifier only once here since all RowLocations
        // in this array/map share the same key
        final Qualifier[][] qualifiers = this.init_qualifier;
        if (qualifiers != null) {
          final Object ckey = this.currentKey;
          final Class<?> keyCls = ckey.getClass();
          // we don't expect a subclass of CompactCompositeIndexKey ever as the
          // resident index key
          if (keyCls == CompactCompositeIndexKey.class) {
            final CompactCompositeIndexKey ccKey =
                (CompactCompositeIndexKey)ckey;
            if (qualifiers != null) {
              if (!RowFormatter.qualifyRow(ccKey, qualifiers)) {
                // did not qualify; move to the next entry
                continue;
              }
            }
            /*
            DataValueDescriptor dvd[] = new DataValueDescriptor[ccKey.nCols()];
            for (int i = 0; i < ccKey.nCols(); i++) {
              dvd[i] = ccKey.getKeyColumn(i);
            }
            if (!RowUtil.qualifyRow(dvd, null, this.init_qualifier)) {
              // did not qualify; move to the next entry
              continue;
            }
            */
          }
          else if (keyCls == DataValueDescriptor[].class) {
            if (qualifiers != null) {
              if (!RowUtil.qualifyRow((DataValueDescriptor[])ckey, null,
                  qualifiers, false)) {
                // did not qualify; move to the next entry
                continue;
              }
            }
          }
          else {
            if (qualifiers != null) {
              if (!RowUtil
                  .qualifyRow(null, (DataValueDescriptor)ckey, qualifiers, false)) {
                // did not qualify; move to the next entry
                continue;
              }
            }
          }
        }
        // now create an iterator on the singleton/array/map of RowLocations
        final Class<?> valueCls = nextValue.getClass();
        if (valueCls == RowLocation[].class) {
          // if the value is an array
          final RowLocationArrayIterator arrayIter = this.cachedArrayIterator;
          if (arrayIter != null) {
            arrayIter.reset((RowLocation[])nextValue);
            this.rlIterator = arrayIter;
          }
          else {
            this.rlIterator = this.cachedArrayIterator =
                new RowLocationArrayIterator((RowLocation[])nextValue,
                    this.txState, this.baseContainer, this.localBucketSet,
                    this.openMode);
          }
          if ((rl = this.rlIterator.next()) != null) {
            //this.movedToNextIndexKey = true;
            ++this.scanKeyGroupID;
            return rl;
          }
        }
        else if (valueCls == ConcurrentTHashSet.class) {
          if ((rl = scanHashSet((ConcurrentTHashSet<?>)nextValue)) != null) {
            //this.movedToNextIndexKey = true;
            ++this.scanKeyGroupID;
            return rl;
          }
        }
        else {
          // if the value is an RowLocation
          this.rlIterator = null;
          if ((rl = AbstractRowLocationIterator.isRowLocationValid(
              (RowLocation)nextValue, lcc, this.txState, this.baseContainer,
              this.localBucketSet, this.openMode, openConglom)) != null) {
            //this.movedToNextIndexKey = true;
            ++this.scanKeyGroupID;
            return rl;
          }
          else {
            this.statNumDeletedRowsVisited++;
          }
        }
      }
      else {
        // no qualifying entry, stop scan
        this.rlIterator = null;
        if (this.cachedArrayIterator != null) {
          this.cachedArrayIterator = null;
        }
        final RowLocationSetIterator rlSetItr = this.cachedSetIterator;
        if (rlSetItr != null) {
          rlSetItr.close();
          this.cachedSetIterator = null;
        }
        return null;
      }
    }
  }

  /** re-qualify the row if required */
  public final boolean qualifyRow(RowLocation rl, ExecRow row, Object indexKey,
      int nodeVersion) throws StandardException {
    // we never expect subclasses of CompactCompositeIndexKey to be index key
    if (row != null && indexKey.getClass() == CompactCompositeIndexKey.class) {
      final CompactCompositeIndexKey ccKey =
          (CompactCompositeIndexKey)indexKey;
      final int version = ccKey.getVersion();
      final GemFireXDQueryObserver observer = this.observer;
      // need to requalify if the version has changed
      if (nodeVersion == version && !rl.isUpdateInProgress()) {
        if (observer != null) {
          observer.afterIndexRowRequalification(null, ccKey, row,
              this.activation);
        }
        return true;
      }
      final Object valueBytes = row.getByteSource();
      // If the valueBytes for this RowLocation doesn't match this index
      // key, then some other thread must have updated the value for that
      // entry but hasn't updated index yet.
      if (valueBytes != null) {
        boolean result = ccKey.equalsValueBytes(valueBytes);
        if (observer != null) {
          observer.afterIndexRowRequalification(result, ccKey, row,
              this.activation);
        }
        return result;
      }
    }
    return true;
  }

  /**
   * General scan for selects when value is a set of RowLocations.
   *
   * @return true if scan can return a value.
   */
  private final RowLocation scanHashSet(final ConcurrentTHashSet<?> set)
      throws StandardException {

    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex(
          "SortedMap2IndexScanController#scanHashMap: For index baseContainer %s"
              + " for key [%s] qualified values: %s", this.baseContainer,
          this.currentKey, set);
    }
    if (this.initRowLocation != null) {
      this.rlIterator = null;
      if (set.contains(this.initRowLocation)) {
        return AbstractRowLocationIterator.isRowLocationValid(
            this.initRowLocation, lcc, this.txState, this.baseContainer,
            this.localBucketSet, this.openMode, openConglom);
      }
      return null;
    }
    else {
      final RowLocationSetIterator setIter = this.cachedSetIterator;
      if (setIter != null) {
        setIter.reset(set);
        this.rlIterator = setIter;
      }
      else {
        this.rlIterator = this.cachedSetIterator = new RowLocationSetIterator(
            set, this.txState, this.baseContainer, this.localBucketSet,
            this.openMode);
      }
      return this.rlIterator.next();
    }
  }

  private final Object nextMapValue() throws StandardException {
    if (this.sourceIterator == null) {
      // case of equality lookup on the same start/stop key
      this.currentKey = this.init_startKeyValue;
      // currentIndexKey is already set by the FetchFromMap callback
      if (this.fullIteratorForStats) {
        this.statNumRowsVisited[0]++;
      }
      final Object result = this.lookupValue;
      this.lookupValue = null;
      return result;
    }
    else {
      Object retVal = null;
      while (this.sourceIterator.hasNext()) {
        @SuppressWarnings("unchecked")
        final SimpleReusableEntry<Object, Object> entry =
            (SimpleReusableEntry<Object, Object>)this.sourceIterator.next();
        this.currentKey = this.currentIndexKey = entry.getKey();
        this.currentNodeVersion = entry.getVersion();
        if (this.fullIteratorForStats) {
          this.statNumRowsVisited[0]++;
        }
        retVal = entry.getValue();
        if (retVal == MemIndexOperation.TOK_INDEX_KEY_DEL) {
          continue;
        }
        else {
          break;
        }
      }
      return retVal;
    }
  }
}
