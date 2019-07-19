
/*

 Derived from source files from the Derby project.

 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to you under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 This file was based on the MemStore patch written by Knut Magne, published
 under the Derby issue DERBY-2798 and released under the same license,
 ASF, as described above. The MemStore patch was in turn based on Derby source
 files.
 */

package com.pivotal.gemfirexd.internal.engine.access.operations;

import java.io.IOException;

import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.Hash1IndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;

/**
 * The delete operation of the CNM2Index.
 * 
 * @author yjing
 * @author Rahul
 */
public final class SortedMap2IndexDeleteOperation extends MemIndexOperation {

  protected final boolean isUnique;

  protected boolean deleteSuccess;
  
  // TODO asif: it is not clear that this is a safe unretained reference. These operations in some code paths appear to live longer than the EntryEventImpl.
  // For example the static doMe method below calls tran.logAndDo(op) and op can end up added to an array in the tx.
  @Unretained private final Object deletedValue;

  public SortedMap2IndexDeleteOperation(GemFireContainer container, Object key,
      RowLocation deletedRow, boolean isUnique, @Unretained Object deletedValue) {
    super(container, key, deletedRow);
    this.isUnique = isUnique;
    this.deletedValue = deletedValue;
  }

  @Override
  public void doMe(Transaction tran, LogInstant instant, LimitObjectInput in)
      throws StandardException, IOException {
    this.deleteSuccess = doMe(null, this.memcontainer, this.key, this.row,
        this.isUnique, this.deletedValue);
  }

  public static boolean doMe(GemFireTransaction tran,
      GemFireContainer container, Object key, RowLocation deletedRow,
      boolean isUnique, @Unretained Object deletedValue) throws StandardException {

    if (tran != null && tran.needLogging()) {
      SortedMap2IndexDeleteOperation op = new SortedMap2IndexDeleteOperation(
          container, key, deletedRow, isUnique, deletedValue);
      tran.logAndDo(op);
      return op.deleteSuccess;
    }

    return deleteFromSkipListMap(container, key, deletedRow, isUnique,
        deletedValue);
  }

  private static boolean deleteFromSkipListMap(final GemFireContainer container,
      final Object key, final RowLocation deletedRow, final boolean isUnique,
      @Unretained Object deletedValue) throws StandardException {
    final ConcurrentSkipListMap<Object, Object> skipListMap = container
        .getSkipListMap();
    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp: deleting key=%s "
          + "with value=(%s) from %s", key, deletedRow, container);
    }
    assert key != null: "The key for index operation cannot be null";
    assert deletedRow != null: "The value for index operation cannot be null";

    GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
    if (observer != null && deletedRow.getTXId() != null
        // account for txn delete commits for not before commit in observer
        && !(deletedRow instanceof WrapperRowLocationForTxn)) {
      observer = null;
    }

    boolean success;
    deletedRow.markDeleteFromIndexInProgress();
    try {
      if (observer != null) {
        observer.keyAndContainerBeforeLocalIndexDelete(key, deletedRow,
            container);
      }

      // callback arg is assumed to be the byte[] or ByteSource being replaced
      Object cbArg = getRowByteSource(deletedValue);
      success = skipListMap.remove(key, deletedRow, deleteCb, container, cbArg,
          null);

      if (observer != null) {
        observer.keyAndContainerAfterLocalIndexDelete(key, deletedRow,
            container);
      }

      if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
        GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp: %s deleted "
            + "key=%s with value=(%s) from %s", (success ? "successfully"
            : "unsuccessfully"), key, GemFireXDUtils.TraceIndex ? deletedRow
            : ArrayUtils.objectRefString(deletedRow), container);
      }
    } catch (IndexMaintenanceException ime) {
      // this always wraps a StandardException
      throw (StandardException)ime.getCause();
    } finally {
      deletedRow.unmarkDeleteFromIndexInProgress();
    }
    return success;
  }

  /**
   * Token to indicate that map key was skipped by the map replace
   * callback (either it was not a {@link CompactCompositeIndexKey} or the value
   * stored in the key was not the one being deleted).
   */
  static final Object UPDATE_MAPKEY_SKIPPED_TOKEN = new Object();

  /**
   * Implements callbacks invoked on a delete that will adjust the map key's
   * valueBytes to point to a new RowLocation if it currently points to the
   * byte[] of RowLocation being deleted.
   */
  static class UpdateReplacementValue extends
      MapCallbackAdapter<Object, Object, GemFireContainer, Object> {

    @Override
    public final Object beforeReplace(final Object mapKey,
        final Object newValue, final GemFireContainer container,
        @Unretained final Object cbArg) {

      if (mapKey instanceof CompactCompositeIndexKey) {
        final CompactCompositeIndexKey ck = (CompactCompositeIndexKey)mapKey;
        // check if the map key's valueBytes point to the entry being deleted
        //If the deleted bytes is null for some unexpected reason, for offheap it can create problem
        // if the CCIK actually points to value bytes of the entry being deleted.
        //In such cases , we would be safe if we replace.
        @Unretained final Object deletedBytes = cbArg;
        @Retained @Released final Object vbs = ck.getValueByteSource();
        try {
          if ((deletedBytes != null && SortedMap2IndexRefreshIndexKeyOperation
              .bytesSameAsCCIKBytes(deletedBytes, vbs)) || (deletedBytes == null && vbs != null)) {
            if (GemFireXDUtils.TraceIndex) {
              GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::beforeReplace "
                          + "byte source being deleted = %s is present in index key",
                      deletedBytes);
            }
            Object replacementValue = null;
            // Find a value from one of the remaining entries to replace
            // the value the index key is pointing at.
            final Class<?> valClass = newValue.getClass();
            if (valClass == RowLocation[].class) {
              for (RowLocation value : (RowLocation[]) newValue) {
                if (value != null && value.useRowLocationForIndexKey()) {
                  replacementValue = value;
                  //getRowLocationByteSourceForKey(ccKey,value);
                  //if (replacementValue != null) {
                    break;
                  //}
                }
              }
            }
            else if (valClass == ConcurrentTHashSet.class) {
              @SuppressWarnings("unchecked")
              final ConcurrentTHashSet<Object> set =
                  (ConcurrentTHashSet<Object>)newValue;
              for (Object rl : set) {
                if (rl != null && ((RowLocation)rl)
                    .useRowLocationForIndexKey()) {
                  replacementValue = rl;
                  break;
                }
              }
            }
            else if (((RowLocation)newValue).useRowLocationForIndexKey()) {
              replacementValue = newValue;
              // getRowLocationByteSourceForKey(ccKey,(RowLocation) newValue);
            }
            if (GemFireXDUtils.TraceIndex) {
              GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::beforeReplace "
                  + "replacement byte source =%S",replacementValue);
            }
            return replacementValue;
          }
        } finally {
          ck.releaseValueByteSource(vbs);
        }
      }
      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::beforeReplace "
            + "returning token = UPDATE_MAPKEY_SKIPPED_TOKEN");
      }
      return UPDATE_MAPKEY_SKIPPED_TOKEN;
    }

    @Override
    public final void afterReplace(final Object mapKey, final Object newValue,
        final Object beforeResult, final GemFireContainer container,
        @Unretained final Object cbArg) {      
      @Retained @Released Object vBytesToUse = null;
      try {
        if (beforeResult != UPDATE_MAPKEY_SKIPPED_TOKEN) {
          // if replace succeeded then we are sure that the replacementValue
          // gotten above is correct and has not changed in key fields by a
          // concurrent update
          final CompactCompositeIndexKey ck = (CompactCompositeIndexKey)mapKey;
          if (beforeResult != null) {
            RowLocation rlToUse = (RowLocation) beforeResult;
            try {
              vBytesToUse = getRowLocationByteSourceForKey(ck, rlToUse);
              if (vBytesToUse != null) {

                @Unretained final Object deletedBytes = cbArg;// getRowLocationByteSource(
                                                  // (RowLocation)cbArg);
                if (deletedBytes != null) {
                  if(ck.update(vBytesToUse, deletedBytes)) {
                    if (GemFireXDUtils.TraceIndex) {
                      GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::afterReplace: "
                        + "replacement of deleted bytes = %s with bytes = %s done",
                              deletedBytes, vBytesToUse);
                    }
                  }else {
                    if (GemFireXDUtils.TraceIndex) {
                      GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::afterReplace: "
                          + "replacement of deleted bytes = %s with bytes = %s not done" +
                          "as CCIK possibly updated by another thread",
                          deletedBytes, vBytesToUse);
                    }
                  }
                }else {
                  if (GemFireXDUtils.TraceIndex) {
                    GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::afterReplace: "
                        + "For some unexpected reason the deleted bytes is null, to prevent index key from " +
                        "containing released bytes, replace it with %s", vBytesToUse);
                  }
                  ck.setValueBytes(vBytesToUse);
                }
              } else {
                if(ck.snapshotKeyFromValue() == null) {
                  throw new AssertionError("key bytes cannot be null");
                }
              }
            } finally {
              rlToUse.endIndexKeyUpdate();
            }
          } else {
            if(ck.snapshotKeyFromValue() == null) {
              throw new AssertionError("key bytes cannot be null");
            }
          }
        }
      } finally {
        OffHeapHelper.release(vBytesToUse);
      }
    }

    @Override
    public void onReplaceFailed(Object mapKey, Object newValue,
        Object beforeResult, GemFireContainer container, @Unretained Object cbArg) {
      if (beforeResult != null && beforeResult != UPDATE_MAPKEY_SKIPPED_TOKEN) {
        ((RowLocation)beforeResult).endIndexKeyUpdate();
      }
    }
  }

  /**
   * Callback invoked on a delete from a RowLocation[] value that will adjust
   * the map key's valueBytes to point to a new RowLocation if it currently
   * points to the byte[] of RowLocation being deleted.
   */
  static final UpdateReplacementValue deleteCb = new UpdateReplacementValue() {
    @Override
    public Object removeValue(final Object key, final Object value,
        final Object existingValue, final GemFireContainer container,
        @Unretained final Object deletedValue) {
      Class<?> cls;
      Object result = null;
      if ((cls = existingValue.getClass()) == RowLocation[].class) {
        result = deleteFromRowLocationArray(key, value, existingValue);
      }
      else if (cls == ConcurrentTHashSet.class) {
        result = deleteFromHashSet(key, value, existingValue, container);
      }
      else if (value == existingValue) {
        result = TOK_INDEX_KEY_DEL;
      }
      else if (!(existingValue instanceof RowLocation)) {
        snapshotKeyBytesOnError(key, deletedValue);
        if (GemFireXDUtils.TracePersistIndex) {
          dumpIndex(container, "Unknown Data type in index");
        }
        GemFireXDUtils.throwAssert("Unknown data type in index "
            + "with type: " + cls + "; oldValue: " + existingValue);
      }
      if (result != null) {
        if (result != TOK_INDEX_KEY_DEL) {
          return result;
        }
        else {
          // TOK_INDEX_KEY_DEL indicates that key has to be removed from map and
          // not replaced
          return null;
        }
      }
      else {
        // Before throwing exception check if the index map key has the byte
        // source being deleted,
        // if yes snap shot the index key
        snapshotKeyBytesOnError(key, deletedValue);
        // null return indicates that old value was not found
        throw new IndexMaintenanceException(
            GemFireXDUtils.newOldValueNotFoundException(key, value,
                existingValue, container));
      }
    }
    
    
    @Override
    public void postRemove(Object mapKey, Object value, Object existingValue,
        final GemFireContainer container, @Unretained final Object deletedValue) {
      if (mapKey instanceof CompactCompositeIndexKey) {
        if (GemFireXDUtils.TraceIndex) {
          GfxdIndexManager.traceIndex("SortedMap2IndexDeleteOp::postRemove "
              + "snap shotting key bytes in index key= %s", mapKey);
        }
        if(((CompactCompositeIndexKey)mapKey).snapshotKeyFromValue() == null) {
            GemFireXDUtils.throwAssert("key bytes cannot be null");          
        }
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object onOperationFailed(Object key, Object oldValue,
        Object updatedValue, Object newValue, final GemFireContainer container,
        Object params) {
      // if we already deleted successfully before from set, then presume
      // success at the end
      if (updatedValue instanceof ConcurrentTHashSet) {
        return !((ConcurrentTHashSet<Object>)updatedValue).contains(oldValue)
            ? updatedValue : null;
      }
      else {
        return null;
      }
    }
  };

  /***
   * Delete a RowLocation from RowLocation array and return the resulting
   * object, or null if RowLocation to be deleted could not be found.
   */
  private static Object deleteFromRowLocationArray(final Object key,
      final Object deletedRow, final Object existingValue) {

    RowLocation[] existingRows = (RowLocation[])existingValue;
    assert existingRows.length > 1: existingRows.length;

    final int newLength = existingRows.length - 1;

    // a set of RowLocation without one to be removed.

    final RowLocation[] newValues;
    if (newLength > 1) {
      newValues = new RowLocation[newLength];
    }
    else {
      newValues = null;
    }
    Object newValue = newValues;

    // find the specified RowLocation and remove it.
    // Asif: If a given RowLocation occurs multiple times then throw
    // AssertionError as it
    // indicates a logically incorrect situation.
    // Refer Bug 40041 & 40016
    boolean foundDeleted = false;
    int index = 0;
    for (RowLocation existingRow : existingRows) {
      if (existingRow == deletedRow) {
        if (!foundDeleted) {
          foundDeleted = true;
          continue;
        }
        else {
          throw new AssertionError("RowLocation array should not contain "
              + "same RegionEntry tuple present more than once. "
              + "Problematic RowLocation is: " + deletedRow
              + " Matched with: " + existingRow);
        }
      }
      else {
        if (newValues != null) {
          if (index == newLength) {
            if (((RowLocation)deletedRow).isDestroyedOrRemoved()) {
              return newValue;
            } else {
              // means that we exhausted all entries without finding the one
              // to be deleted
              return null;
            }
          }
          newValues[index++] = existingRow;
        }
        else {
          newValue = existingRow;
        }
      }
    }
    if (foundDeleted || ((RowLocation)deletedRow).isDestroyedOrRemoved()) {
      return newValue;
    }
    else {
      return null;
    }

    // [sumedh] Now using remove overload with callback to plug in the
    // replacement value instead of ReplacingKey
    /*
    byte[] replacementValue = null;
    if (key instanceof CompactCompositeIndexKey) {
      //Find a value from one of the remaining entries to replace
      //the value the index key is pointing at.
      if (tempValues != null) {
        for (RowLocation value : tempValues) {
          replacementValue = getRowLocationByteArrayForKey(key, value);
          if (replacementValue != null) {
            break;
          }
        }
      }
      else {
        replacementValue = getRowLocationByteArrayForKey(key, tempValue);
      }

      // This replacing key will sneakily replace the key at the same time we're
      // replacing the value, by using the comparator to replace the key.

      // Note here that the replace may fail, but the key will still be swapped
      // thats ok because this thread will retry and replace the key on the next
      // retry
      key = new ReplacingKey(key, replacementValue);
    }
    */
  }

  /**
   * Delete a RowLocation from set of values and return the resulting object,
   * TOK_INDEX_KEY_DEL if entire row has been deleted, or null if RowLocation to
   * be deleted could not be found.
   */
  private static Object deleteFromHashSet(Object key, Object deletedRow,
      Object existingValue, final GemFireContainer container) {
    @SuppressWarnings("unchecked")
    final ConcurrentTHashSet<Object> existingValues =
        (ConcurrentTHashSet<Object>)existingValue;

    // Rahul: I dont think the following assert is need.
    // assert existingValues.size() >= ROWLOCATION_THRESHOLD;
    if (!existingValues.remove(deletedRow) && !((RowLocation)deletedRow).isDestroyedOrRemoved()) {
      return null;
    }

    // Refer Bug 40016 & 40041
    if (!existingValues.isEmpty()) {
      return existingValues;
    }
    else {
      // Proceed with deletion of the index key; the CAS of node value with null
      // helps avoid problems with a concurrent insert into the set
      return TOK_INDEX_KEY_DEL;
    }
  }

  /**
   * Get a byte array from the given value that can be used to replace the index
   * key at the specified key
   * 
   * @param ccKey
   *          the index key we we intend to replace
   * @param value
   *          a candidate value
   * 
   * @return a byte array extracted from the value, or null of the value does
   *         not contain a byte array suitable to replace this key with in the
   *         index.
   */
  @Retained
  public static Object getRowLocationByteSourceForKey(
      final CompactCompositeIndexKey ccKey, final RowLocation rl) {

    @Released @Retained final Object valueBytes = getRowLocationByteSource(rl);
    // If the valueBytes we've extracted from the value don't match this region
    // key, then some other thread must have already changed the value for that
    // entry. Don't use those value bytes in this key.
    if (valueBytes != null) {
      if (ccKey.equalsValueBytes(valueBytes)) {
        return valueBytes;
      }
      else if (valueBytes instanceof OffHeapByteSource) {
        ((OffHeapByteSource)valueBytes).release();
      }
    }
    return null;
  }

  @Retained
  public static Object getRowLocationByteSource(final RowLocation rl) {
    final Object rawValue;
    if (rl.getTXId() != null) {
      // be conservative and don't use value bytes from an uncommitted
      // transactional entry; it will come if required in commit in any case
      return null;
    }
    else  {
      rawValue = rl.getRawValue();
    }
    return getRowByteSource(rawValue);
  }

  @Unretained
  public static Object getRowByteSource(final Object rawValue) {
    Object valueBytes;

    if (rawValue != null) {
      final Class<?> vClass = rawValue.getClass();
      if (vClass == byte[].class) {
        valueBytes = (byte[])rawValue;
      }
      else if (vClass == byte[][].class) {
        valueBytes = ((byte[][])rawValue)[0];
      }
      else if (vClass == OffHeapRow.class) {
        OffHeapRow obs = (OffHeapRow)rawValue;
        if (!OffHeapRegionEntryHelper.isAddressInvalidOrRemoved(obs
            .getMemoryAddress())) {
          valueBytes = rawValue;
        }
        else {
          return null;
        }
      }
      else if (vClass == OffHeapRowWithLobs.class) {
        OffHeapRowWithLobs obs = (OffHeapRowWithLobs)rawValue;
        if (!OffHeapRegionEntryHelper.isAddressInvalidOrRemoved(obs
            .getMemoryAddress())) {
          valueBytes = obs;
        }
        else {
          return null;
        }
      }
      else if (Token.isInvalidOrRemoved(rawValue)) {
        // The value was concurrently removed by another thread.
        return null;
      }
      else {
        // throw new AssertionError("Unknown type of rawValue " + rawValue);
        // For other types like dvd dvd[] for sys tables etc. even if this method
        // is called we should return null because this method is for application tables
        // only and is supposed to return either byte[] or OffHeapByteSource
        if (rawValue instanceof DataValueDescriptor
            || rawValue instanceof DataValueDescriptor[]) {
          return null;
        }
        else {
          Assert.fail("getRowByteSource: unexpected raw value: " + rawValue
              + "(class=" + rawValue.getClass() + ")");
          // Never reached. Just to remove compilation error
          return null;
        }
      }
    }
    else {
      return null;
    }

    /*
    CompactCompositeIndexKey ccKey = (CompactCompositeIndexKey) key;

    //If the valueBytes we've extracted from the value don't match this region
    //key, then some other thread must have already changed the value for that
    //entry. Don't use those value bytes in this key.
    if(!ccKey.equalsValueBytes(valueBytes)) {
      return null;
    }
    */

    return valueBytes;
  }

  private static void snapshotKeyBytesOnError(final Object key,
      final Object deletedValue) {
    if (key instanceof CompactCompositeIndexKey) {
      CompactCompositeIndexKey ccik = (CompactCompositeIndexKey) key;
      @Retained
      @Unretained
      Object indexBytes = ccik.getValueByteSource();
      try {
        if (deletedValue != null
            && SortedMap2IndexRefreshIndexKeyOperation.bytesSameAsCCIKBytes(
                deletedValue, indexBytes)) {
          ccik.snapshotKeyFromValue();
        }
      } finally {
        ccik.releaseValueByteSource(indexBytes);
      }
    }
  }

  @Override
  public Compensation generateUndo(Transaction xact, LimitObjectInput in)
      throws StandardException, IOException {
    // need to refresh the value since the underlying RegionEntry in the region
    // may have changed (causes #43889)
    final RegionEntry entry;
    if (this.row != null
        && (entry = this.row.getRegionEntry()).isDestroyedOrRemoved()) {
      this.row = Hash1IndexScanController.fetchRowLocation(
          entry.getKeyCopy(), this.memcontainer.getBaseContainer().getRegion());
    }
    SortedMap2IndexInsertOperation undoOp = new SortedMap2IndexInsertOperation(
        this.memcontainer, this.key, this.row, this.isUnique);
    return undoOp;
  }

  @Override
  public final boolean shouldBeConflated() {
    return true;
  }
}
