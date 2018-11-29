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

package com.pivotal.gemfirexd.internal.engine.access;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.THashMapWithKeyPair;
import com.gemstone.gemfire.internal.cache.TObjectObjectObjectProcedure;
import com.gemstone.gemfire.internal.cache.TXEntryState;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXRegionState;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.TXStateProxyFactory;
import com.gemstone.gemfire.internal.cache.VMIdAdvisor;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.operations.GlobalHashIndexDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexInsertOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexRefreshIndexKeyOperation;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.ExtractingIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnectionContext;

/**
 * Extends GFE {@link TXStateProxy} to add GFXD specific artifacts including
 * DBSynchronizer message list to be sent out at commit time.
 * 
 * @author swale
 * @since 7.0
 */
@SuppressWarnings("serial")
public final class GfxdTXStateProxy extends TXStateProxy {

  private final static TXStateProxyFactory factory = new TXStateProxyFactory() {

    @Override
    public final TXStateProxy newTXStateProxy(final TXManagerImpl txMgr,
        final TXId txId, final IsolationLevel isolationLevel,
        final boolean isJTA, final EnumSet<TransactionFlag> flags,
        final boolean initLocalTXState) {
      return new GfxdTXStateProxy(txMgr, txId, isolationLevel, isJTA, flags,
          initLocalTXState);
    }
  };

  /**
   * All the GemFireTransactions that have this TX as the active one. We use an
   * ArrayList since the number of such GFTs will be small. Also avoid removing
   * elements from this list to avoid the copying and instead set them to null.
   */
  private volatile Object trans;

  /** updater for trans for fast paths not using locks */
  private static final AtomicReferenceFieldUpdater<GfxdTXStateProxy, Object>
      transUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          GfxdTXStateProxy.class, Object.class, "trans");

  /**
   * List of DBSynchronizer messages to be dispatched at the end a transaction.
   */
  final ArrayList<AbstractDBSynchronizerMessage> dbOps;

  public GfxdTXStateProxy(final TXManagerImpl txMgr, final TXId txId,
      final IsolationLevel isolationLevel, final boolean isJTA,
      final EnumSet<TransactionFlag> flags, final boolean initLocalTXState) {
    super(txMgr, txId, isolationLevel, isJTA, flags, initLocalTXState);
    this.dbOps = new ArrayList<AbstractDBSynchronizerMessage>(5);
  }

  public static final TXStateProxyFactory getGfxdFactory() {
    return factory;
  }

  @Override
  protected Set<InternalDistributedMember> getRollbackTargets(
      VMIdAdvisor advisor) {
    // hive meta-store tables are persisted on locators too, so send rollback
    // to all nodes for the case when "default-persistent" is set on the connection
    final LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null && lcc.isDefaultPersistent()) {
      return super.getRollbackTargets(advisor);
    }
    // send rollback only to GFXD stores
    @SuppressWarnings({ "unchecked", "rawtypes" })
    Set<InternalDistributedMember> stores = (Set)GemFireXDUtils
        .getGfxdAdvisor().adviseDataStores(null);
    stores.remove(Misc.getMyId());
    return stores;
  }

  final void addGemFireTransaction(final GemFireTransaction tran) {
    if (GemFireXDUtils.TraceTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "addGemFireTransaction: adding " + tran + " to " + toString(), new Exception());
    }
    while (true) {
      if (GemFireXDUtils.TraceTran | GemFireXDUtils.TraceQuery) {
        this.lock.lock();
        try {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "addGemFireTransaction: in loop tran = " + tran + " trans = " + this.trans);
        } finally {
          this.lock.unlock();
        }
      }
      final Object trans = this.trans;
      if (trans == tran) {
        return;
      }
      else if (trans == null) {
        if (transUpdater.compareAndSet(this, null, tran)) {
          return;
        }
      }
      else if (trans instanceof GemFireTransaction) {
        final TXState.ArrayListAppend trs = new TXState.ArrayListAppend(5);
        trs.append(trans);
        trs.append(tran);
        if (transUpdater.compareAndSet(this, trans, trs)) {
          return;
        }
      }
      else {
        this.lock.lock();
        try {
          ((TXState.ArrayListAppend)trans).addIfAbsent(tran);
        } finally {
          this.lock.unlock();
        }
        return;
      }
    }
  }

  final void clearGemFireTransaction(final GemFireTransaction tran) {
    if (GemFireXDUtils.TraceTran | GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "clearGemFireTransaction: removing " + tran + " from "
              + toString());
    }
    while (true) {
      final Object trans = this.trans;
      if (trans == tran) {
        if (transUpdater.compareAndSet(this, trans, null)) {
          return;
        }
      }
      else if (trans instanceof TXState.ArrayListAppend) {
        this.lock.lock();
        try {
          ((TXState.ArrayListAppend)trans).removeObj(tran);
        } finally {
          this.lock.unlock();
        }
        return;
      }
      else {
        return;
      }
    }
  }

  final void addDBSynchronizerMessage(final AbstractDBSynchronizerMessage dbm) {
    this.lock.lock();
    this.dbOps.add(dbm);
    this.lock.unlock();
  }

  /**
   * @see TXStateProxy#preCommit()
   */
  @Override
  public void preCommit() {
    // lock should be already held
    assert this.lock.isLocked();

    super.preCommit();
    // Apply DBSYnchronizer ops here as at this point GemFireXD locks have
    // been released so that GemFireXD operations are not hampered.
    for (AbstractDBSynchronizerMessage dsm : this.dbOps) {
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "Applying DBSynchronizer op=" + dsm);
      }
      try {
        dsm.applyOperation();
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // check VM failure
        SystemFailure.checkFailure();
        // check VM shutting down too
        Misc.checkIfCacheClosing(t);
        // log exceptions and move on
        SanityManager.DEBUG_PRINT("warning:"
            + GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "Exception in applying DBSynchronizer op=" + dsm, t);
      }
    }
    this.dbOps.clear();
  }

  /**
   * @see TXStateProxy#hasPreCommitActions()
   */
  @Override
  public boolean hasPreCommitActions() {
    // lock should be already held
    assert this.lock.isLocked();

    return this.dbOps.size() > 0;
  }

  @Override
  protected void onCommit(TXState localState, final Object callbackArg) {
    remoteConnCleanup(false, callbackArg);
  }

  @Override
  protected void onRollback(TXState localState, final Object callbackArg) {
    remoteConnCleanup(true, callbackArg);
  }

  @Override
  public final TXState.ArrayListAppend[] getTSSPendingReadLocks(
      final Object context) {
    // use LCC in GemFireXD
    if (context != null) {
      return ((LanguageConnectionContext)context).getPendingReadLocks();
    }
    final LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null) {
      return lcc.getPendingReadLocks();
    }
    return null;
  }

  @Override
  public TXState.ArrayListAppend[] getTSSPendingReadLocks() {
    // try from GemFireTransaction list first
    Object trans = this.trans;
    GemFireTransaction tran = null;
    if (trans != null) {
      if (trans instanceof GemFireTransaction) {
        tran = (GemFireTransaction)trans;
      }
      else {
        this.lock.lock();
        try {
          trans = this.trans;
          if (trans != null) {
            if (trans instanceof TXState.ArrayListAppend) {
              TXState.ArrayListAppend trs = (TXState.ArrayListAppend)trans;
              final int sz = trs.size();
              for (int index = 0; index < sz; index++) {
                Object tr = trs.get(index);
                if (tr != null) {
                  tran = (GemFireTransaction)tr;
                  break;
                }
              }
            }
            else {
              tran = (GemFireTransaction)trans;
            }
          }
        } finally {
          this.lock.unlock();
        }
      }
    }
    LanguageConnectionContext lcc = GemFireTransaction
        .getLanguageConnectionContext(tran);
    if (lcc != null) {
      return lcc.getPendingReadLocks();
    }
    return null;
  }

  @Override
  protected void cleanupEmpty(final Object callbackArg) {
    remoteConnCleanup(false, callbackArg);
  }

  @Override
  protected final void cleanupIndexEntry(final TXRegionState txr,
      final TXEntryState tx, final Operation op) {
    if (op.isUpdate()) {
      final THashMapWithKeyPair indexInfoMap = txr
          .getTransactionalIndexInfoMap();
      if (indexInfoMap != null) {
        indexInfoMap.forEachEntry(new TObjectObjectObjectProcedure() {
          @Override
          public final boolean execute(final Object k1, final Object k2,
              final Object v) {
            if (k1 == tx) {
              cleanupIndexEntryForInsert((GfxdTXEntryState)k1,
                  (GemFireContainer)k2, v, true);
            }
            return true;
          }
        });
      }
    }
    else if (op.isDestroy()) {
      final THashMapWithKeyPair toBeReinstatedIndexInfo = txr
          .getToBeReinstatedIndexMap();
      if (toBeReinstatedIndexInfo != null) {
        toBeReinstatedIndexInfo
            .forEachEntry(new TObjectObjectObjectProcedure() {
              @Override
              public final boolean execute(final Object k1, final Object k2,
                  final Object v) {
                if (k1 == tx) {
                  cleanupIndexEntryForDestroy(k2, v, true);
                }
                return true;
              }
            });
      }
    }
    else {
      SanityManager.THROWASSERT("unexpected op=" + op
          + " for cleanupIndexEntry");
    }
  }

  final void cleanupIndexEntryForInsert(final GfxdTXEntryState sqle,
      GemFireContainer container, final Object indexValue, boolean rollback) {
    try {
      if (!sqle.isDestroyedOrRemoved()) {
        updateIndexAtCommitAbortNoThrow(sqle, container, indexValue, rollback);
      }
    } catch (Exception ex) {
      // at this point we cannot fail so log and move on
      SanityManager.DEBUG_PRINT("error:" + GfxdConstants.TRACE_TRAN,
          "Unexpected exception during GemFireXD index cleanup for entry="
              + sqle + " in index=" + container, ex);
    }
  }

  final void refreshIndexKeyUnaffectedIndex(final GfxdTXEntryState sqle,
      GemFireContainer container, final ExtractingIndexKey indexKey) {
    try {
      if (!sqle.isDestroyedOrRemoved()) {
        if (GemFireXDUtils.TraceIndex ) {
          GfxdIndexManager.traceIndex("GfxdTXStateProxt::refreshIndexKeyUnaffectedIndex "
              + "Refreshing index key for  key=%s Old row=(%s) , " +
              "for index container=(%s), old value = %s",
              indexKey,sqle.getUnderlyingRegionEntry(), container, sqle.getOriginalValue());
        }
        SortedMap2IndexRefreshIndexKeyOperation.doMe(null, container, indexKey,
            (RowLocation) sqle.getUnderlyingRegionEntry(),
            sqle.getOriginalValue(), false, true);
      }
    } catch (Exception ex) {
      // at this point we cannot fail so log and move on
      SanityManager.DEBUG_PRINT("error:" + GfxdConstants.TRACE_TRAN,
          "Unexpected exception during GemFireXD index cleanup for entry=" + sqle
              + " in index=" + container, ex);
    }
  }

  final void cleanupIndexEntryForDestroy(Object container,
      final Object indexValue, boolean rollback) {
    GemFireContainer indexContainer = null;
    Object indexKey = null;
    try {
      indexContainer = (GemFireContainer)container;
      WrapperRowLocationForTxn wrapper = (WrapperRowLocationForTxn)indexValue;
      indexKey = wrapper.getIndexKey();
      boolean deleted;

      if (rollback) {
        // reinstate region entry
        final RowLocation oldRowLocation = (RowLocation)wrapper
            .getRegionEntry();
        deleted = SortedMap2IndexInsertOperation
            .replaceInSkipListMap(indexContainer, indexKey,
                wrapper /* value to be replaced */,
                oldRowLocation /* replacement */, false, null,
                false /* isPutDML */);

        if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
          GfxdIndexManager.traceIndex("SortedMap2Index cleanup: "
              + "rolled back key=%s to value=(%s) in %s", indexKey,
              GemFireXDUtils.TraceIndex ? oldRowLocation : ArrayUtils
                  .objectRefString(oldRowLocation), indexContainer);
        }
      }
      else {
        final GfxdTXEntryState sqle = wrapper.getWrappedRowLocation();
        deleted = SortedMap2IndexDeleteOperation.doMe(null, indexContainer,
            indexKey, wrapper, false, sqle.getOriginalValue());
      }
      if (!deleted) {
        final GfxdTXEntryState sqle = wrapper.getWrappedRowLocation();
        GfxdIndexManager.handleNotDeleted(false, sqle.getDataRegion(),
            indexContainer, wrapper, sqle.getUnderlyingRegionEntry(), indexKey,
            null /* exception */);
      }
    } catch (Exception e) {
      // at this point we cannot fail so log and move on no exception
      // expected here. but if we encounter any we need to move on after
      // logging as rest of the cleanup should happen.
      SanityManager.DEBUG_PRINT("error:" + GfxdConstants.TRACE_TRAN,
          "Unexpected exception during reinstating indexes for key="
              + indexKey + " in index=" + indexContainer, e);
    }
  }

  @Override
  protected final void updateIndexes(final boolean rollback,
      final THashMapWithKeyPair indexInfoMap,
      final THashMapWithKeyPair toBeReinstatedIndexInfo,
      final THashMapWithKeyPair unaffectedIndexInfo) {
    if (indexInfoMap != null) {
      indexInfoMap.forEachEntry(new TObjectObjectObjectProcedure() {
        @Override
        public final boolean execute(final Object k1, final Object k2,
            final Object v) {
          cleanupIndexEntryForInsert((GfxdTXEntryState)k1,
              (GemFireContainer)k2, v, rollback);
          return true;
        }
      });
    }
    
    if (unaffectedIndexInfo != null) {
      unaffectedIndexInfo.forEachEntry(new TObjectObjectObjectProcedure() {
        @Override
        public final boolean execute(final Object k1, final Object k2,
            final Object v) {
          if (!rollback /*
                         * && ( indexInfoMap == null ||
                         * !indexInfoMap.containsKey(k1, k2))
                         */) {
            refreshIndexKeyUnaffectedIndex((GfxdTXEntryState) k1,
                (GemFireContainer) k2, (ExtractingIndexKey) v);
          }
          return true;
        }
      });
    }

    if (toBeReinstatedIndexInfo != null) {
      toBeReinstatedIndexInfo.forEachEntry(new TObjectObjectObjectProcedure() {
        @Override
        public final boolean execute(final Object k1, final Object k2,
            final Object v) {
          cleanupIndexEntryForDestroy(k2, v, rollback);
          return true;
        }
      });
    }
  }

  @Override
  protected final void cleanup() {
    transUpdater.set(this, null);
    if (!this.dbOps.isEmpty()) {
      this.dbOps.clear();
    }
    super.cleanup();
  }

  private final void remoteConnCleanup(final boolean rollback,
      final Object callbackArg) {
    EmbedConnection conn = null;
    GemFireTransaction tran = null;
    Object trans = checkTransForConnCleanup(rollback);

    try {
      if (trans != null) {
        boolean cleanupDone = false;
        if (trans instanceof GemFireTransaction) {
          tran = (GemFireTransaction)trans;
          if (conn == null) {
            conn = getEmbedConnection(callbackArg, tran);
          }
          if (conn != null) {
            synchronized (conn.getConnectionSynchronization()) {
              tran.postTxCompletionOnDataStore(rollback);
              cleanupDone = true;
            }
          }
        }
        else {
          final Object[] transArr = (Object[])trans;
          for (Object t : transArr) {
            if (t != null) {
              tran = (GemFireTransaction)t;
              if (conn == null) {
                conn = getEmbedConnection(callbackArg, tran);
              }
              if (conn != null) {
                synchronized (conn.getConnectionSynchronization()) {
                  tran.postTxCompletionOnDataStore(rollback);
                  cleanupDone = true;
                }
              }
            }
            else {
              break;
            }
          }
        }
        if (cleanupDone) {
          return;
        }
      }

      if (conn == null) {
        conn = getEmbedConnection(callbackArg, tran);
      }
      if (conn != null) {
        synchronized (conn.getConnectionSynchronization()) {
          tran = (GemFireTransaction)conn.getLanguageConnection()
              .getTransactionExecute();
          tran.postTxCompletionOnDataStore(rollback);
        }
      }
    } catch (SQLException sqle) {
      // just log it at fine level
      final LogWriterI18n logger = Misc.getI18NLogWriter();
      if (GemFireXDUtils.TraceTran || logger.fineEnabled()) {
        logger.fine("Unexpected exception in remote GemFireXD "
            + "transaction cleanup", sqle);
      }
    }
  }

  private EmbedConnection getEmbedConnection(Object callbackArg,
      GemFireTransaction tran) throws SQLException {
    if (callbackArg instanceof EmbedConnection) {
      EmbedConnection conn = (EmbedConnection)callbackArg;
      if (GemFireXDUtils.TraceTran) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GfxdConnectionHolder: commonCleanup: using callbackArg "
                + "connection: " + conn + ", for transaction " + this);
      }
      return conn;
    }
    else {
      if (tran != null) {
        EmbedConnection conn = EmbedConnectionContext.getEmbedConnection(tran
            .getContextManager());
        if (conn != null) {
          return conn;
        }
      }
      if (GemFireXDUtils.TraceTran) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GfxdConnectionHolder: commonCleanup: connectionID=" + callbackArg
                + " for transaction " + this);
      }

      final Long connId = (Long)callbackArg;
      final GfxdConnectionWrapper wrapper;
      // if connection already cleaned up in rollback etc. then nothing to be
      // done
      if (connId != null && (wrapper = GfxdConnectionHolder.getHolder()
          .getExistingWrapper(connId)) != null) {
        return wrapper.getConnectionForSynchronization(false);
      }
    }
    return null;
  }

  private Object checkTransForConnCleanup(boolean rollback) {
    final Object tr = this.trans;
    if (tr instanceof GemFireTransaction) {
      transUpdater.set(this, null);
      return tr;
    }
    else if (tr != null) {
      int index = 0;
      Object[] trans = null;
      Object tran, stran = null;
      this.lock.lock();
      try {
        final TXState.ArrayListAppend trs = (TXState.ArrayListAppend)tr;
        final int sz = trs.size();
        for (int i = 0; i < sz; i++) {
          tran = trs.get(i);
          if (tran != null) {
            if (stran == null) {
              stran = tran;
            }
            else {
              if (trans == null) {
                trans = new Object[sz];
                trans[index++] = stran;
              }
              trans[index++] = tran;
            }
          }
        }
      } finally {
        this.lock.unlock();
      }
      transUpdater.set(this, null);
      return index > 0 ? trans : stran;
    }
    else {
      return null;
    }
  }

  private void updateIndexAtCommitAbortNoThrow(GfxdTXEntryState sqle,
      GemFireContainer indexContainer, Object indexKey, boolean rollback)
      throws StandardException {
    boolean deleted;
    Object valueBytesBeingReplaced = sqle.getPendingValue();
    
    //TODO:Asif: This will cause a memory leak if an existing entry is 
    //updated in txn, such a new index key is introduced, and then again
    // a txn update happens such that byte [] changes . Then 
    // the firts indexkey's byte[] will not match the pending value
    
    try {
      if (rollback) {
        if (!indexContainer.isGlobalIndex()) {
          deleted = SortedMap2IndexDeleteOperation.doMe(null, indexContainer,
              indexKey, sqle, false, valueBytesBeingReplaced);
        } else {
          deleted = true;
        }
      } else {
        if (!indexContainer.isGlobalIndex()) {
          if (sqle.getUnderlyingRegionEntry().isDestroyedOrRemoved()) {
            deleted = SortedMap2IndexDeleteOperation.doMe(null, indexContainer,
                indexKey, sqle, false, valueBytesBeingReplaced);
          } else {
            deleted = SortedMap2IndexInsertOperation.replaceInSkipListMap(
                indexContainer, indexKey, sqle,
                (RowLocation)sqle.getUnderlyingRegionEntry(), false,
                valueBytesBeingReplaced, false /* isPutDML */);
          }
        } else {
          if (sqle.getUnderlyingRegionEntry().isDestroyedOrRemoved()) {
            deleted = GlobalHashIndexDeleteOperation.doMe(null, null,
                indexContainer, indexKey, true);
          } else {
            deleted = true;// did not evict, nothing to do for global index
          }
        }
      }
      if (!deleted) {
        GfxdIndexManager.handleNotDeleted(false, sqle.getDataRegion(),
            indexContainer, sqle, sqle.getUnderlyingRegionEntry(), indexKey, 
            null /* exception*/);
      }
    } catch (StandardException e) {
      // exception might come due to index maintenance of unique indexes
      if (GemFireXDUtils.TraceIndex) {
        GfxdIndexManager.traceIndex("GfxdTXEntryState: unexpected exception "
            + "when updating index=%s for key=%s", indexContainer, indexKey);
      }
    }
  }

  public Checkpoint getACheckPoint() {
    return this.regions.checkpoint(null);
  }
}
