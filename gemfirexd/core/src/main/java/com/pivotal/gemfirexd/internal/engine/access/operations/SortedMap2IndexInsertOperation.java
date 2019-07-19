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

package com.pivotal.gemfirexd.internal.engine.access.operations;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.MapCallbackAdapter;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2Index;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexDeleteOperation.UpdateReplacementValue;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Implement the insert operation on the index based on the
 * ConcurrentSkipListMap data structure.
 * 
 * @see SortedMap2Index
 *
 * @author yjing
 * @author Rahul
 * @author swale
 */
public final class SortedMap2IndexInsertOperation extends MemIndexOperation {

  protected final boolean isUnique;
  protected boolean result;

  public SortedMap2IndexInsertOperation(GemFireContainer container, Object key,
      RowLocation value, boolean isUnique) {
    super(container, key, value);
    this.isUnique = isUnique;
  }

  @Override
  public void doMe(Transaction tran, LogInstant instant, LimitObjectInput in)
      throws StandardException, IOException {
    this.result = doMe(null, null, this.memcontainer, this.key, this.row,
        this.isUnique, null, false /* isPutDML */, false);
  }

  public static boolean doMe(GemFireTransaction tran, TXStateInterface tx,
      GemFireContainer container, Object key, RowLocation value,
      boolean isUnique, WrapperRowLocationForTxn wrapperToReplaceUniqEntry,
      boolean isPutDML, boolean skipConstraintChecks) throws StandardException {
    if (tran != null && tran.needLogging()) {
      SortedMap2IndexInsertOperation op = new SortedMap2IndexInsertOperation(
          container, key, value, isUnique);
      tran.logAndDo(op);
      return op.result;
    }

    return insertIntoSkipListMap(tx, container, key, value, isUnique,
        wrapperToReplaceUniqEntry, isPutDML, skipConstraintChecks);
  }

  private static boolean insertIntoSkipListMap(final TXStateInterface tx,
      final GemFireContainer container, Object key, final RowLocation value,
      final boolean isUnique,
      final WrapperRowLocationForTxn wrapperToReplaceUniqEntry,
      boolean isPutDML, boolean skipConstraintChecks) throws StandardException {
    isPutDML |= skipConstraintChecks;
    final ConcurrentSkipListMap<Object, Object> skipListMap = container
        .getSkipListMap();
    long lockTimeout = -1L;
    int waitThreshold = -1;
    if (GemFireXDUtils.TraceIndex) {
      if (wrapperToReplaceUniqEntry == null) {
        GfxdIndexManager
            .traceIndex("SortedMap2IndexInsertOp: inserting key=(%s) "
                + "value=%s into (%s)", key, value, container);
      }
      else {
        GfxdIndexManager.traceIndex(
            "SortedMap2IndexInsertOp: replacing for key=(%s) "
                + "value=%s to wrappervalue=%s into (%s)", key, value,
            wrapperToReplaceUniqEntry, container);
      }
    }
    assert skipListMap != null;

    assert key != null: "The key for index operation cannot be null";
    assert (!(key instanceof DataValueDescriptor[])
        || ((DataValueDescriptor[])key).length >= 2)
        && ((key instanceof DataValueDescriptor[])
          || (key instanceof DataValueDescriptor))
          || (key instanceof CompactCompositeIndexKey): "unexpected key " + key;
    assert value != null: "The value for index operation cannot be null";
    GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();

    // txn inserts will be accounted for at commit by replaceInSkipListMap
    if (observer != null && value.getTXId() != null) {
      observer = null;
    }

    for (;;) {

      // TODO: should we have stats during inserts/deletes/replace also
      // instead of passing numNodesCompared as null here?
      // we could have this special put return int instead of oldValue
      // (which is already read in callback)
      // putIfAbsent can use the enhanced put for that case
      final Object oldValue;
      if (isUnique) {
        oldValue = skipListMap.putIfAbsent(key, value);
      }
      else {
        try {
          oldValue = skipListMap.put(key, insertOrUpdateValue, value,
              container, null);
        } catch (IndexMaintenanceException ime) {
          if (isPutDML) {
            // indicate to higher layer to not try the delete portion if applicable
            return false;
          }
          // this always wraps a StandardException
          throw (StandardException)ime.getCause();
        }
        break;
      }

      if (oldValue == TOK_INDEX_KEY_DEL) {
        // Asif: If the value is TOKEN, then loop, till token is replaced by a
        // final outcome. Even if the map is reinserted by the delete thread,
        // still reinsertion of the RowLocation in that map will not be an
        // issue. For RowLocation[], or same object, it is not an issue
        // as TOK_INDEX_KEY_DEL is only used if existing value is a Map.
        // below is now for isUnique case only while rest are now handled
        // via MapCallback
        continue;
      }
      else if (oldValue == value && isPutDML) {
        // expected due to situations like #48944 when same key is inserted
        // leading to same RowLocation being sent at this layer
        return false;
      }
      else if (oldValue != null) {
        // check for TX conflict first
        final TXId txId, rlTXId;
        final RowLocation rl;
        if (tx != null && oldValue instanceof RowLocation
            && (rlTXId = (rl = (RowLocation) oldValue).getTXId()) != null) {
          if (!rlTXId.equals(txId = tx.getTransactionId())) {
            final LockingPolicy lockPolicy = tx.getLockingPolicy();
            if (lockTimeout < 0) { // not set
              final AbstractRegionEntry re = (AbstractRegionEntry) rl
                  .getUnderlyingRegionEntry();
              final LockMode currentMode;
              if (re == null || (currentMode = ExclusiveSharedSynchronizer
                  .getLockModeFromState(re.getState())) == null) {
                throw GemFireXDUtils.newDuplicateKeyViolation("unique constraint",
                    container.getQualifiedTableName(), "key=" + key.toString()
                        + ", row=" + value, rl, null, null);
              }
              lockTimeout = lockPolicy.getTimeout(rl,
                  lockPolicy.getWriteLockMode(), currentMode, 0, 0);
              waitThreshold = re.getWaitThreshold() * 1000;
              if (lockTimeout < 0) {
                lockTimeout = ExclusiveSharedSynchronizer.getMaxMillis();
              }
              continue;
            } else if (lockTimeout > 0) { // wait before throwing conflict
              final long sleepTime = lockTimeout < 5 ? lockTimeout : 5;
              final long start = System.nanoTime();
              final GemFireCacheImpl cache = Misc.getGemFireCache();
              try {
                Thread.sleep(sleepTime);
                long elapsed = (System.nanoTime() - start + 500000) / 1000000;
                if (elapsed <= 0) {
                  elapsed = 1;
                }
                final long origLockTimeout = lockTimeout;
                if (lockTimeout < elapsed) {
                  lockTimeout = 0;
                } else {
                  lockTimeout -= elapsed;
                }
                if (waitThreshold > 0) {
                  // check if there is a factor of waitThreshold between
                  // origLockTimeout and lockTimeout
                  final long origQuotient = origLockTimeout / waitThreshold;
                  final long quotient = lockTimeout / waitThreshold;
                  if (origQuotient > quotient
                      || (origLockTimeout % waitThreshold) == 0) {
                    final LogWriterI18n logger = cache.getLoggerI18n();
                    if (logger.warningEnabled()) {
                      logger.warning(LocalizedStrings.LocalLock_Waiting,
                          new Object[]{"SortedMap2IndexInsertOperation",
                              Double.toString(waitThreshold / 1000.0),
                              lockPolicy.getWriteLockMode().toString(),
                              "index key with oldValue=" + ArrayUtils
                                  .objectStringNonRecursive(oldValue) + ", owner="
                                  + rlTXId, ArrayUtils.objectString(key),
                              lockTimeout});
                    }
                    // increase waitThreshold by a factor of 2 for next iteration
                    waitThreshold <<= 1;
                  }
                }
              } catch (InterruptedException ie) {
                cache.getCancelCriterion().checkCancelInProgress(ie);
              }
              continue;
            }
            final ConflictException ce = new ConflictException(
                LocalizedStrings.TX_CONFLICT_ON_OBJECT.toLocalizedString("index="
                        + container + "; indexKey=" + ArrayUtils.objectString(key)
                        + "; having oldValue=" + ArrayUtils.objectStringNonRecursive(
                    oldValue) + "; owner TX=" + rlTXId
                        + "; requested for TX=" + txId,
                    lockPolicy.getWriteLockMode().toString()));
            throw StandardException.newException(
                SQLState.GFXD_OPERATION_CONFLICT, ce, ce.getMessage());
          } else {
            // This else part is mainly added to handle SNAP-2620
            if (oldValue instanceof WrapperRowLocationForTxn) {
              GfxdTXEntryState gfxdtxentry = (GfxdTXEntryState)value;
              gfxdtxentry.setCommittedRegionEntry(
                  ((WrapperRowLocationForTxn)oldValue).getUnderlyingRegionEntry());
              if (skipListMap.replace(key, oldValue, value)) {
                // remove txentry, wrapper pair for reinstated map
                // which is with TXRegionState
                TXRegionState txrs = tx.readRegion((gfxdtxentry).getDataRegion());
                if (txrs.getToBeReinstatedIndexMap() != null) {
                  Object wrappedRL = ((WrapperRowLocationForTxn)oldValue).getWrappedRowLocation();
                  txrs.getToBeReinstatedIndexMap().removeKeyPair(wrappedRL, container);
                  gfxdtxentry.updateIndexInfos(container, key);
                }
                break;
              }
              else {
                continue;
              }
            }
          }
        }
        if (wrapperToReplaceUniqEntry != null) {
          // if existing value is already a wrapper then no need to replace;
          // if coming from another txn then it would have already failed above
          if (!(oldValue instanceof WrapperRowLocationForTxn)) {
            if (skipListMap.replace(key, oldValue, wrapperToReplaceUniqEntry)) {
              final GfxdTXEntryState stxe = wrapperToReplaceUniqEntry
                  .getWrappedRowLocation();
              stxe.addUniqIdxInfosForReplacedEntries(container, key,
                  wrapperToReplaceUniqEntry);
              break;
            }
            else {
              continue;
            }
          }
          break;
        }

        // TODO: identify the cause of Bug SNAP-2627
        // Till then this is the crude fix
        if (oldValue instanceof AbstractRegionEntry) {
          final AbstractRegionEntry existingRe = (AbstractRegionEntry)oldValue;
          if (existingRe.isDestroyedOrRemoved()) {
            Callable<Boolean> deadEntryRemover = new Callable<Boolean>() {
              @Override
              public Boolean call() {
                synchronized (existingRe) {
                  if (existingRe.isDestroyedOrRemoved()) {
                    if (!existingRe.isRemovedPhase1()) {
                      // create a dummy exception
                      Throwable th = GemFireXDUtils.newDuplicateKeyViolation("unique constraint",
                          container.getQualifiedTableName(), "key=" + key.toString()
                              + ", row=" + value, oldValue, null, null);
                      final GemFireCacheImpl cache = Misc.getGemFireCache();
                      final LogWriter logger = cache.getLogger();
                      logger.error("Unique index constraint violation caused due to " +
                          "removed/destroyed entry. The index is corrupted. Cleaning the index", th);

                      skipListMap.remove(key, oldValue);
                    }
                    return true;
                  }

                }
                return false;
              }

            };

            ThreadPoolExecutor executor = Misc.getGemFireCache()
                .getWaitingThreadPoolOrDiskWritePool();
            try {
              Future<Boolean> outcome = executor.submit(deadEntryRemover);
              if (outcome.get(2000, TimeUnit.MILLISECONDS)) {
                // either the entry was token removed or token destroyed, try again
                continue;
              }
            } catch (Exception e) {
              // not much can be done? let this thread throw duplicate key exception
            }
          }
        }
        if (skipConstraintChecks) {
          // indicate to higher layer to not try the delete portion if applicable
          return false;
        }
        throw GemFireXDUtils.newDuplicateKeyViolation("unique constraint",
            container.getQualifiedTableName(), "key=" + key.toString()
                + ", row=" + value, oldValue, null, null);
      }
      else {
        break;
      }
    }

    if (observer != null) {
      observer.keyAndContainerAfterLocalIndexInsert(key, value, container);
    }
    // For UMM. Run an estimation after some puts
    container.runEstimation();

    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      GfxdIndexManager.traceIndex("SortedMap2IndexInsertOp: successfully "
          + "inserted key=(%s) value=(%s) into %s", key,
          GemFireXDUtils.TraceIndex ? value : ArrayUtils.objectRefString(value),
          container);
    }

    return true;
  }

  public static boolean replaceInSkipListMap(final GemFireContainer container,
      Object key, final RowLocation oldValue, final RowLocation value,
      final boolean isUnique, Object deletedValue, boolean isPutDML)
      throws StandardException {
    final ConcurrentSkipListMap<Object, Object> skipListMap = container
        .getSkipListMap();

    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager
          .traceIndex("SortedMap2IndexInsertOp: replacing key=(%s) "
              + "value=%s into (%s)", key, value, container);
    }
    assert skipListMap != null;

    assert key != null: "The key for index operation cannot be null";
    assert (!(key instanceof DataValueDescriptor[])
        || ((DataValueDescriptor[])key).length >= 2)
        && ((key instanceof DataValueDescriptor[])
          || (key instanceof DataValueDescriptor))
          || (key instanceof CompactCompositeIndexKey): "unexpected key " + key;
    assert value != null: "The value for index operation cannot be null";
    GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null && value.getTXId() != null) {
      observer = null;
    }

    oldValue.markDeleteFromIndexInProgress();
    boolean success;
    try {

      // callback arg is assumed to be the byte[] or ByteSource being replaced
      Object cbArg = SortedMap2IndexDeleteOperation
          .getRowByteSource(deletedValue);
      success = skipListMap.replace(key, oldValue, value,
          isPutDML ? replaceValuePut : replaceValue, container, cbArg, null);

      if (observer != null) {
        // for transactions, this is an insert if TXEntryState is being replaced
        // with RowLocation
        if (oldValue instanceof TXEntryState && value instanceof RowLocation) {
          observer.keyAndContainerAfterLocalIndexInsert(key, value, container);
        }
      }
      if(success){
        // For UMM. Run an estimation after some puts
        container.runEstimation();
      }

      if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
        GfxdIndexManager.traceIndex("SortedMap2IndexInsertOp: successfully "
            + "replace key=(%s) value=(%s) with oldValue=(%s) into %s", key,
            GemFireXDUtils.TraceIndex ? value : ArrayUtils.objectRefString(value),
            GemFireXDUtils.TraceIndex ? oldValue : ArrayUtils
                .objectRefString(oldValue), container);
      }
    } catch (IndexMaintenanceException ime) {
      if (isPutDML) {
        // indicate to higher layer to not try the delete portion if applicable
        return false;
      }
      // this always wraps a StandardException
      throw (StandardException)ime.getCause();
    } finally {
      oldValue.unmarkDeleteFromIndexInProgress();
    }
    return success;
  }

  public static final class InsertOrUpdateValue extends
      MapCallbackAdapter<Object, Object, RowLocation, GemFireContainer> {

    private final boolean isPutDML;

    InsertOrUpdateValue(boolean isPutDML) {
      this.isPutDML = isPutDML;
    }

    @Override
    public Object newValue(Object key, RowLocation value,
        GemFireContainer container, final MapResult result) {
      return value;
    }

    @Override
    public Object updateValue(Object key, Object oldValue, RowLocation value,
        GemFireContainer container) {

      Class<?> oldValueClass;
      if (oldValue == null || oldValue == TOK_INDEX_KEY_DEL) {
        // Asif: If the value is TOKEN, then loop, till token is replaced by a
        // final outcome. Even if the map is reinserted by the delete thread,
        // still reinsertion of the RowLocation in that map will not be an
        // issue. For RowLocation[], or same object, it is not an issue
        // as TOK_INDEX_KEY_DEL is only used if existing value is a Map.
        // null return here indicates retry
        return null;
      }
      else if ((oldValueClass = oldValue.getClass()) == RowLocation[].class) {
        return insertToRowLocationArray(container, key, value,
            (RowLocation[])oldValue, this.isPutDML);
      }
      else if (oldValueClass == ConcurrentTHashSet.class) {
        return insertToHashSet(container, key, value, oldValue, this.isPutDML);
      }
      else if (RowLocation.class.isAssignableFrom(oldValueClass)) {
        return insertToRowLocation(container, key, value,
            (RowLocation)oldValue, this.isPutDML);
      }
      else {
        if (GemFireXDUtils.TracePersistIndex) {
          dumpIndex(container, "Type of data structure is: "
            + oldValueClass);
        }
        GemFireXDUtils.throwAssert("Unknown Data Structure in the index: "
            + oldValueClass);
        // never reached
        return null;
      }
    }

    @Override
    public void afterUpdate(Object key, Object mapKey, Object newValue,
        RowLocation value) {
      // if key has been previously snapshotted from the value then change to
      // point to the newly inserted value
      if (value.getTXId() == null
          && mapKey.getClass() == CompactCompositeIndexKey.class) {
        final CompactCompositeIndexKey ccik = (CompactCompositeIndexKey)key;
        @Retained @Released Object vbs = ccik.getValueByteSource();
        if (vbs != null) {
          try {
            ((CompactCompositeIndexKey)mapKey).update(vbs, null);
          } finally {
            ccik.releaseValueByteSource(vbs);
          }
        }
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object onOperationFailed(Object key, Object oldValue,
        Object updatedValue, Object newValue, final RowLocation value,
        final GemFireContainer container) {
      // remove any inserted value if we failed to insert into map
      if (updatedValue instanceof ConcurrentTHashSet) {
        ((ConcurrentTHashSet<Object>)updatedValue).remove(value);
      }
      return null;
    }
  };

  public static final InsertOrUpdateValue insertOrUpdateValue =
      new InsertOrUpdateValue(false);
  public static final InsertOrUpdateValue insertOrUpdateValueForPut =
      new InsertOrUpdateValue(true);

  /**
   * Update value when existing one is a single RowLocation.
   */
  private static Object insertToRowLocation(GemFireContainer container,
      Object key, RowLocation insertedValue, final RowLocation oldValue,
      boolean isPutDML) throws IndexMaintenanceException {

    // should not happen when a duplicate entry from both GII and create
    // will be converted to an update by GFE and handled properly by
    // GfxdIndexManager#onEvent();
    // can happen in case update is fired on index column updating to the
    // old value itself
    if (insertedValue == oldValue) {
      GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
      if (observer != null) {
        observer.callAtOldValueSameAsNewValueCheckInSM2IIOp();
      }
      if (isPutDML) {
        return oldValue;
      }
      throw new IndexMaintenanceException(
          GemFireXDUtils.newDuplicateEntryViolation(
              container.getQualifiedTableName(), oldValue, insertedValue));
    }

    RowLocation[] newValues = new RowLocation[2];
    newValues[0] = oldValue;
    newValues[1] = insertedValue;

    return newValues;
  }

  /**
   * Update value when existing one is a RowLocation[].
   */
  private static Object insertToRowLocationArray(
      final GemFireContainer container, final Object key,
      final RowLocation insertedValue, final RowLocation[] existingValues,
      boolean isPutDML) throws IndexMaintenanceException {

    final int numExistingValues = existingValues.length;

    if ((numExistingValues + 1) >= ROWLOCATION_THRESHOLD) {
      ConcurrentTHashSet<Object> set = new ConcurrentTHashSet<Object>(8,
          numExistingValues + 1, 0.60f,
          ObjectEqualsHashingStrategy.getInstance(), container
              .getBaseContainer().getRegion().getRegionPerfStats());
      for (int i = 0; i < numExistingValues; i++) {
        // should not happen when a duplicate entry from both GII and create
        // will be converted to an update by GFE and handled properly by
        // GfxdIndexManager#onEvent();
        // can happen in case update is fired on index column updating to the
        // old value itself
        if (insertedValue == existingValues[i]) {
          if (isPutDML) {
            return existingValues;
          }
          throw new IndexMaintenanceException(
              GemFireXDUtils.newDuplicateEntryViolation(
                  container.getQualifiedTableName(), existingValues[i],
                  insertedValue));
        }
        set.add(existingValues[i]);
      }
      set.add(insertedValue);
      return set;
    }
    else {
      final RowLocation[] newValues = new RowLocation[numExistingValues + 1];
      for (int i = 0; i < numExistingValues; i++) {
        // should not happen when a duplicate entry from both GII and create
        // will be converted to an update by GFE and handled properly by
        // GfxdIndexManager#onEvent();
        // can happen in case update is fired on index column updating to the
        // old value itself
        if (insertedValue == existingValues[i]) {
          if (isPutDML) {
            return existingValues;
          }
          throw new IndexMaintenanceException(
              GemFireXDUtils.newDuplicateEntryViolation(
                  container.getQualifiedTableName(), existingValues[i],
                  insertedValue));
        }
        newValues[i] = existingValues[i];
      }
      newValues[numExistingValues] = insertedValue;

      return newValues;
    }
  }

  /**
   * Insert value into existing set.
   */
  private static Object insertToHashSet(GemFireContainer container, Object key,
      RowLocation insertedValue, Object oldValue, boolean isPutDML)
      throws IndexMaintenanceException {

    @SuppressWarnings("unchecked")
    final ConcurrentTHashSet<Object> set =
        (ConcurrentTHashSet<Object>)oldValue;

    Object oldRowLocObj;
    if ((oldRowLocObj = set.addKey(insertedValue)) == null || isPutDML) {
      return set;
    }
    assert oldRowLocObj.equals(insertedValue);
    // should not happen when a duplicate entry from both GII and create
    // will be converted to an update by GFE and handled properly by
    // GfxdIndexManager#onEvent();
    // can happen in case update is fired on index column updating to the
    // old value itself
    throw new IndexMaintenanceException(
        GemFireXDUtils.newDuplicateEntryViolation(
            container.getQualifiedTableName(), oldRowLocObj, insertedValue));
  }

  static final class ReplaceValue extends UpdateReplacementValue {
    private final boolean isPutDML;

    ReplaceValue(boolean isPutDML) {
      this.isPutDML = isPutDML;
    }

    @Override
    public Object replaceValue(Object key, Object oldValue,
        Object existingValue, Object newValue, GemFireContainer container,
        Object deletedValue) {
      Class<?> mapValueClass;
      if (existingValue == null || existingValue == TOK_INDEX_KEY_DEL) {
        // should not happen for replace, so indicate that replace failed
        return null;
      }
      else if ((mapValueClass = existingValue.getClass()) ==
          RowLocation[].class) {
        return replaceRowLocationArray(container, key, oldValue,
            (RowLocation)newValue, (RowLocation[])existingValue, this.isPutDML);
      }
      else if (mapValueClass == ConcurrentTHashSet.class) {
        return replaceInHashSet(container, key, oldValue,
            newValue, existingValue);
      }
      else {
        return newValue;
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object onOperationFailed(Object key, Object oldValue,
        Object updatedValue, Object newValue, final GemFireContainer container,
        Object params) {
      // revert any replaced value if we failed to replace in map
      if (updatedValue instanceof ConcurrentTHashSet) {
        ((ConcurrentTHashSet<Object>)updatedValue).replace(newValue, oldValue);
      }
      return null;
    }
  };

  static final ReplaceValue replaceValue = new ReplaceValue(false);
  static final ReplaceValue replaceValuePut = new ReplaceValue(false);

  /**
   * Replace given RowLocation from existing RowLocation[].
   */
  private static RowLocation[] replaceRowLocationArray(
      final GemFireContainer container, final Object key,
      final Object oldValue, final RowLocation insertedValue,
      final RowLocation[] existingValues, boolean isPutDML)
      throws IndexMaintenanceException {

    RowLocation[] newValues = new RowLocation[existingValues.length];
    boolean foundOldValue = false;
    int index = 0;
    for (RowLocation existingValue : existingValues) {
      // should not happen when a duplicate entry from both GII and create
      // will be converted to an update by GFE and handled properly by
      // GfxdIndexManager#onEvent();
      // can happen in case update is fired on index column updating to the
      // old value itself
      if (insertedValue != existingValue) {
        if (oldValue != existingValue) {
          try {
            newValues[++index] = existingValue;
          } catch (ArrayIndexOutOfBoundsException ae) {
            // throw back a proper exception for the case when value to be
            // replaced is not found
            throw new IndexMaintenanceException(
                GemFireXDUtils.newOldValueNotFoundException(key, oldValue,
                    existingValues, container));
          }
        }
        else if (!foundOldValue) {
          foundOldValue = true;
        }
        else {
          // corrupt index with dups? try to repair with severe logs
          SanityManager.DEBUG_PRINT("severe:" + GfxdConstants.TRACE_INDEX,
              "duplicate entry for tuple (" + oldValue + "), indexKey (" + key
                  + ") index=" + container.getQualifiedTableName());
          RowLocation[] newValues2 = new RowLocation[newValues.length - 1];
          System.arraycopy(newValues, 0, newValues2, 0, index);
          newValues = newValues2;
        }
      }
      else {
        throw new IndexMaintenanceException(
            GemFireXDUtils.newDuplicateEntryViolation(
                container.getQualifiedTableName(), existingValue,
                insertedValue));
      }
    }
    newValues[0] = insertedValue;

    return newValues;
  }

  /**
   * Replace given RowLocation from existing set of RowLocation.
   */
  private static ConcurrentTHashSet<Object> replaceInHashSet(
      GemFireContainer container, final Object key, final Object oldValue,
      final Object insertedValue, Object existingValue)
      throws IndexMaintenanceException {

    @SuppressWarnings("unchecked")
    final ConcurrentTHashSet<Object> set =
        (ConcurrentTHashSet<Object>)existingValue;

    if (set.replace(oldValue, insertedValue)) {
      return set;
    }
    else {
      // throw back a proper exception for the case when value to be
      // replaced is not found
      throw new IndexMaintenanceException(
          GemFireXDUtils.newOldValueNotFoundException(key, oldValue, set,
              container));
    }
  }

  @Override
  public Compensation generateUndo(Transaction xact, LimitObjectInput in)
      throws StandardException, IOException {
    Object value;
    if (this.memcontainer.getBaseContainer().isOffHeap()) {
      value = ((OffHeapRegionEntry)this.row).getAddress();
    }
    else {
      value = this.row.getValueOrOffHeapEntry(this.memcontainer
          .getBaseContainer().getRegion());
    }
    return new SortedMap2IndexDeleteOperation(this.memcontainer, this.key,
        this.row, this.isUnique, value);
  }
}
