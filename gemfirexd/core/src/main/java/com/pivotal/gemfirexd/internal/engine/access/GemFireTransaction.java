
/*
 * 
 * Derived from source files from the Derby project.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.pivotal.gemfirexd.internal.engine.access;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.cache.Checkpoint;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RecoveryLock;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl.TXContext;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gnu.trove.TIntArrayList;
import com.gemstone.gnu.trove.TLongObjectHashMap;
import com.gemstone.gnu.trove.TLongObjectIterator;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.operations.ContainerCreateOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.ContainerDropOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemOperation;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.raw.log.MemLogger;
import com.pivotal.gemfirexd.internal.engine.raw.store.FileStreamInputOutput;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.offheap.ArrayOHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.LinkedListOHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapOHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapResourceHolder;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.daemon.Serviceable;
import com.pivotal.gemfirexd.internal.iapi.services.io.DynamicByteArrayOutputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockFactory;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockOwner;
import com.pivotal.gemfirexd.internal.iapi.services.property.PersistentSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.*;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.MethodFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.ScanControllerRowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.ScanManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Sort;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.SortFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;
import com.pivotal.gemfirexd.internal.iapi.store.raw.*;
import com.pivotal.gemfirexd.internal.iapi.store.raw.data.DataFactory;
import com.pivotal.gemfirexd.internal.iapi.store.raw.data.RawContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogFactory;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.Logger;
import com.pivotal.gemfirexd.internal.iapi.store.raw.xact.RawTransaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.xact.TransactionId;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.util.ByteArray;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.store.access.conglomerate.ConglomerateUtil;
import com.pivotal.gemfirexd.internal.impl.store.access.sort.ArraySorter;
import com.pivotal.gemfirexd.internal.impl.store.raw.xact.GlobalXactId;
import com.pivotal.gemfirexd.internal.impl.store.raw.xact.TransactionTable;
import com.pivotal.gemfirexd.internal.impl.store.raw.xact.XactId;
import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.GEMFIRE_TRANSACTION_BYTE_SOURCE;

/**
 * This class implements a {@link TransactionManager} to control a transaction
 * for GemFireXD. It also now doubles as a {@link RawTransaction} since
 * separating the two is not useful for GemFireXD when the two always have a
 * one-to-one relationship.
 * 
 * @author Eric Zoerner
 * @author swale
 * @author rdubey
 */
public final class GemFireTransaction extends RawTransaction implements
    XATransactionController, TransactionManager, OffHeapResourceHolder {

  /** indicates the name of the transaction */
  private String transName;

  @Unretained(GEMFIRE_TRANSACTION_BYTE_SOURCE)
  private final OHAddressCache ownerlessAddressesToFree;

  private final Set<OffHeapResourceHolder> owners;

  /** the associated {@link LanguageConnectionContext}, if any */
  private LanguageConnectionContext lcc;

  /** {@link GfxdLockSet} used for locking in this transaction */
  private final GfxdLockSet lockSet;

  /** {@link LogFactory} used to create {@link Logger}s for undo/redo logs */
  private final LogFactory logFactory;

  /** {@link Logger} used for operation logging, undo, commit if enabled */
  private MemLogger logger;

  /** {@link DataValueFactory} used to create DVDs */
  private final DataValueFactory dataValueFactory;

  /** The context this transaction is being managed by. */
  private GemFireTransactionContext context;

  /** The parent transaction if this is a nested user transaction. */
  private final GemFireTransaction parentTran;

  /** The cached child read-only transaction. */
  private GemFireTransaction childTran;

  /** The cached child read-write transaction. */
  private GemFireTransaction childTranUpd;

  /** List of open {@link ScanController}s in the current Transaction. */
  private final ArrayList<ScanManager> scanControllers;

  /**
   * List of open {@link MemConglomerateController}s in the current Transaction.
   */
  private final ArrayList<MemConglomerateController> conglomerateControllers;

  /** List of open {@link Sort}s in the current transaction. */
  private ArrayList<Sort> sorts;

  /** List of open {@link SortController}s in the current transaction. */
  private ArrayList<SortController> sortControllers;

  /**
   * List of sort identifiers (represented as <code>Integer</code> objects)
   * which can be reused. Since sort identifiers are used as array indexes, we
   * need to reuse them to avoid leaking memory (DERBY-912).
   */
  private TIntArrayList freeSortIds;

  /** Where to look for temporary conglomerates. */
  private TLongObjectHashMap tempCongloms;

  /** Next id to use for a temporary conglomerate. */
  private static AtomicLong nextTempConglomId = new AtomicLong(-1);

  /** public TransactionTable ttab; */
  private static final AtomicLong currTranId = new AtomicLong(0);

  private final long myId;

  private long ddlId;

  private TransactionId myXactId;

  //private GlobalTransactionId myGlobalId;

  /* The transaction is only allowed read operations, no log writes. */
  // private final boolean readOnly;

  /** True if this transaction should skip taking read/write locks. */
  private boolean skipLocks;

  /**
   * True if <code>Attribute.SKIP_LOCKS</code> is set on connection indicating
   * setting skipLocks everywhere for all operations on the connection
   */
  private boolean skipLocksOnConnection;

  /**
   * True if {@link Logger} has been enabled for this transaction (e.g. for
   * rollback).
   */
  private boolean needLogging;

  /**
   * Flag to denote the state of this TX i.e. one of {@link #ACTIVE},
   * {@link #IDLE} or {@link #CLOSED}. We access this without synchronization.
   */
  protected int state;

  /** Context ID for a user Transaction. */
  private static final String USER_CONTEXT_ID =
    AccessFactoryGlobals.USER_TRANS_NAME;

  /** * Static Fields */
  protected static final int CLOSED = 0;

  /** DOCUMENT ME! */
  protected static final int IDLE = 1;

  /** DOCUMENT ME! */
  protected static final int ACTIVE = 2;

  /**
   * private static - make sure these bits don't overwrite bits in
   * Transaction.commit commitflag
   */
  private static final int COMMIT_SYNC = 0x00010000;

  private static final int COMMIT_NO_SYNC = 0x00020000;

  /**
   * The GFE transaction manager implementation.
   */
  private final TXManagerImpl txManager;

  /**
   * Locally cached GFE transaction state.
   */
  private TXStateInterface txState;

  /**
   * Locally cached GemFireXD TXStateProxy.
   */
  private GfxdTXStateProxy txProxy;

  /**
   * The isolation-level set for this transaction.
   */
  private IsolationLevel isolationLevel;

  /**
   * Currently suspended GFE transactional state, if any.
   */
  private TXStateInterface txStateSuspended;

  /**
   * The isolation-level for {@link #txStateSuspended}.
   */
  private IsolationLevel isolationSuspended;

  final private Long connectionID;

  private TXContext txContextFromPrepareStage;

  private boolean prepared;
  
  private RecoveryLock regionRecoveryLock = null;

  // after commit and rollback generate a new one and send it to the client
  // so that client are aware of the new txid on return of commit and rollback.
  private TXId nextTxID;
  private boolean implicitSnapshotTxStarted;

  /**
   * Create a new {@link GemFireTransaction} object.
   * 
   * @param name
   *          name of this Transaction
   * @param parentTransaction
   *          parent Transaction, if any
   * @param skipLocks
   *          true to skip taking any locks in this Transaction
   * @param compatibilitySpace
   *          any {@link GfxdLockSet} for this Transaction, if any
   * @throws StandardException
   *           standard error policy
   */
  private GemFireTransaction(String name, GemFireTransaction parentTransaction,
      boolean skipLocks, GfxdLockSet compatibilitySpace, long connectionID,
      boolean isTxExecute) throws StandardException {
    this.transName = name;
    this.parentTran = parentTransaction;
    this.scanControllers = new ArrayList<ScanManager>();
    this.conglomerateControllers = new ArrayList<MemConglomerateController>();
    final FabricDatabase db = Misc.getMemStore().getDatabase();
    this.logFactory = db.getLogFactory();
    this.dataValueFactory = db.getDataValueFactory();
    this.connectionID = Long.valueOf(connectionID);
    this.sorts = null; // allocated on demand.
    this.freeSortIds = null; // allocated on demand.
    this.sortControllers = null; // allocated on demand

    if (GemFireXDUtils.isOffHeapEnabled()) {
      this.ownerlessAddressesToFree = createOHAddressCache();
      this.owners = new HashSet<OffHeapResourceHolder>();
    }
    else {
      this.ownerlessAddressesToFree = null;
      this.owners = null;
    }

    this.myId = currTranId.incrementAndGet();
    if (parentTransaction != null) {
      // allow nested transactions to see temporary conglomerates which were
      // created in the parent transaction. This is necessary for language
      // which compiling plans in nested transactions against user temporaries
      // created in parent transactions.
      this.tempCongloms = parentTransaction.tempCongloms;
      this.skipLocks = parentTransaction.skipLocks;
      this.skipLocksOnConnection = parentTransaction.skipLocksOnConnection;
    }
    else {
      this.tempCongloms = null; // allocated on demand
    }
    if (compatibilitySpace == null) {
      // inherit LockOwner from parent transaction
      this.lockSet = new GfxdLockSet(
          parentTransaction != null ? parentTransaction.getLockSpace()
              .getOwner() : new DistributedTXLockOwner(this.myId), Misc
              .getMemStore().getDDLLockService());
    }
    else {
      this.lockSet = compatibilitySpace;
    }
    this.skipLocks = skipLocks;
    if (isTxExecute) {
      this.txManager = Misc.getGemFireCache().getCacheTransactionManager();
      final TXStateInterface tx = TXManagerImpl.getCurrentTXState();
      setTXState(tx);
      if (tx != null) {
        this.isolationLevel = tx.getIsolationLevel();
      }
      else {
        this.isolationLevel = IsolationLevel.NONE;
      }
    }
    else {
      this.txManager = null;
      setTXState(null);
      this.isolationLevel = IsolationLevel.NONE;
    }
    this.isolationSuspended = IsolationLevel.NONE;
  }

  private static final boolean ARRAY_OH_ADDRESS_CACHE = SystemProperties
      .getServerInstance().getBoolean("ArrayOHAddressCache", false);
  private static final boolean LINKED_LIST_OH_ADDRESS_CACHE = SystemProperties
      .getServerInstance().getBoolean("LinkedListOHAddressCache", false);
  // OFF_HEAP is the default
  private static final boolean OFF_HEAP_OH_ADDRESS_CACHE =
      !ARRAY_OH_ADDRESS_CACHE && !LINKED_LIST_OH_ADDRESS_CACHE;

  public static OHAddressCache createOHAddressCache() {
    if (OFF_HEAP_OH_ADDRESS_CACHE) return new OffHeapOHAddressCache();
    if (ARRAY_OH_ADDRESS_CACHE) return new ArrayOHAddressCache();
    if (LINKED_LIST_OH_ADDRESS_CACHE) return new LinkedListOHAddressCache();
    throw new IllegalStateException("expected one of the OHAddressCache constants to be set");
  }

  public final LanguageConnectionContext getLanguageConnectionContext() {
    if (this.lcc != null) {
      return this.lcc;
    }
    return (this.lcc = (LanguageConnectionContext)getContextManager()
        .getContext(LanguageConnectionContext.CONTEXT_ID));
  }

  public static LanguageConnectionContext getLanguageConnectionContext(
      final GemFireTransaction tran) {
    if (tran != null) {
      return tran.getLanguageConnectionContext();
    }
    return Misc.getLanguageConnectionContext();
  }

  public final void setLanguageConnectionContext(
      final LanguageConnectionContext lcc) {
    this.lcc = lcc;
  }

  /**
   * Private/Protected methods of this class.
   ************************************************************************* 
   */
  private void closeControllers(boolean closeHeldControllers)
      throws StandardException {
    if (!this.scanControllers.isEmpty()) {
      // loop from end to beginning, removing scans which are not held.
      for (int i = this.scanControllers.size() - 1; i >= 0; --i) {
        final ScanManager sc = this.scanControllers.get(i);
        sc.closeForEndTransaction(true);
        // if self altering the arrayList from #closeMe doing
        // scanControllers.remove(..)
        // re-closing existing MemScanControllers won't hurt.
        if (i >= this.scanControllers.size()) {
          i = this.scanControllers.size() - 1;
        }
        //if (sc.closeForEndTransaction(closeHeldControllers)) {
          // now counting on scan's removing themselves by
          // calling the closeMe() method.
          /* scanControllers.removeElementAt(i); */
        //}
      }
      if (closeHeldControllers) {
        // just to make sure everything has been closed and removed.
        this.scanControllers.clear();
      }
    }

    if (!this.conglomerateControllers.isEmpty()) {
      // loop from end to beginning, removing scans which are not held.
      for (int i = conglomerateControllers.size() - 1; i >= 0; --i) {
        final ConglomerateController cc = conglomerateControllers.get(i);
        cc.closeForEndTransaction(true);
        //if (cc.closeForEndTransaction(closeHeldControllers)) {
          // now counting on cc's removing themselves by
          // calling the closeMe() method.
          /* conglomerateControllers.removeElementAt(i); */
        //}
      }
      if (closeHeldControllers) {
        // just to make sure everything has been closed and removed.
        this.conglomerateControllers.clear();
      }
    }

    final ArrayList<SortController> sortControllers = this.sortControllers;
    if (sortControllers != null) {
      if (closeHeldControllers) {
        // Loop from the end since the call to close() will remove the
        // element from the list.
        for (int i = sortControllers.size() - 1; i >= 0; --i) {
          final SortController sc = sortControllers.get(i);
          sc.completedInserts();
        }
      }
      this.sortControllers = null;
    }

    final ArrayList<Sort> sorts = this.sorts;
    if (sorts != null) {
      if (closeHeldControllers) {
        // Loop from the end since the call to drop() will remove the
        // element from the list.
        for (int i = sorts.size() - 1; i >= 0; --i) {
          final Sort sort = sorts.get(i);
          if (sort != null) {
            sort.drop(this);
          }
        }
      }
      this.sorts = null;
    }

    this.freeSortIds = null;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public final MemConglomerate findExistingConglomerate(final long conglomId)
      throws StandardException {
    return findExistingConglomerate(conglomId, this.lcc, this);
  }

  public static MemConglomerate findExistingConglomerate(final long conglomId,
      final LanguageConnectionContext lcc, final GemFireTransaction tran)
      throws StandardException {
    final MemConglomerate conglom = findConglomerate(conglomId, lcc, tran);
    if (conglom != null) {
      return conglom;
    }
    // unexpected situation; dump the lock tables for debugging
    final GemFireStore store = Misc.getMemStore();
    final GfxdDRWLockService lockService = store.getDDLLockService();
    lockService.dumpAllRWLocks(
        "LOCK TABLE at the time of missing conglomerate [" + conglomId + "]",
        true, false, true);
    // also dump the SYS.SYSCONGLOMERATES contents
    final StringBuilder sb = new StringBuilder("Conglomerate Dump")
        .append(SanityManager.lineSeparator);
    for (ConglomerateDescriptor cd : store.getDatabase().getDataDictionary()
        .getAllConglomerateDescriptors()) {
      sb.append(cd.toString()).append(SanityManager.lineSeparator);
    }
    SanityManager.DEBUG_PRINT("DumpConglomerates", sb.toString());
    throw StandardException.newException(
        SQLState.STORE_CONGLOMERATE_DOES_NOT_EXIST, Long.valueOf(conglomId));
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public final MemConglomerate findConglomerate(final long conglomId)
      throws StandardException {
    return findConglomerate(conglomId, this.lcc, this);
  }

  public static MemConglomerate findConglomerate(final long conglomId,
      final LanguageConnectionContext lcc, final GemFireTransaction tran)
      throws StandardException {
    if (conglomId >= 0) {
      return Misc.getMemStore().findConglomerate(
          ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT, conglomId));
    }

    MemConglomerate tempconglom = null;
    if (tran.tempCongloms != null) {
      tempconglom = (MemConglomerate)tran.tempCongloms.get(conglomId);
    }

    if (tempconglom == null) {
      tempconglom = lcc.getConglomerateForDeclaredGlobalTempTable(conglomId);
    }

    return tempconglom;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglom
   *          DOCUMENT ME!
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param static_info
   *          DOCUMENT ME!
   * @param dynamic_info
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  private MemConglomerateController openConglomerate(MemConglomerate conglom,
      boolean hold, int open_mode, int lock_level, int isolation_level,
      StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info) throws StandardException {
    if (SanityManager.DEBUG) {
      if ((open_mode & ~(ContainerHandle.MODE_UNLOGGED
          | ContainerHandle.MODE_CREATE_UNLOGGED
          | ContainerHandle.MODE_FORUPDATE | ContainerHandle.MODE_READONLY
          | ContainerHandle.MODE_TRUNCATE_ON_COMMIT
          | ContainerHandle.MODE_DROP_ON_COMMIT
          | ContainerHandle.MODE_OPEN_FOR_LOCK_ONLY
          | ContainerHandle.MODE_LOCK_NOWAIT
          | ContainerHandle.MODE_TRUNCATE_ON_ROLLBACK
          | ContainerHandle.MODE_FLUSH_ON_COMMIT
          | ContainerHandle.MODE_NO_ACTIONS_ON_COMMIT
          | ContainerHandle.MODE_TEMP_IS_KEPT
          | ContainerHandle.MODE_USE_UPDATE_LOCKS
          | ContainerHandle.MODE_SECONDARY_LOCKED
          | ContainerHandle.MODE_BASEROW_INSERT_LOCKED)) != 0) {
        SanityManager.THROWASSERT("Bad open mode to openConglomerate: 0x"
            + Integer.toHexString(open_mode));
      }
      SanityManager.ASSERT(conglom != null);
      if (lock_level != MODE_RECORD && lock_level != MODE_TABLE) {
        SanityManager.THROWASSERT("Bad lock level to openConglomerate:"
            + lock_level);
      }
    }
    // Get a conglomerate controller.
    return openConglomerateController(conglom, open_mode, lock_level, null);
  }

  /**
   * Check if we already have a closed controller of the same type then
   * reinitialize with new parameters, else open a new controller. Note that
   * this can potentially be against a new Conglomerate.
   */
  private MemConglomerateController openConglomerateController(
      MemConglomerate conglomerate, int openMode, int lockLevel,
      LockingPolicy locking) throws StandardException {
    MemConglomerateController cc;
    final int conglomType = conglomerate.getType();
    for (int index = 0; index < this.conglomerateControllers.size(); ++index) {
      cc = this.conglomerateControllers.get(index);
      if (cc.isClosed() && conglomType == cc.getType()) {
        cc.init(this, conglomerate, openMode, lockLevel, locking);
        return cc;
      }
    }
    cc = conglomerate.open(this, openMode, lockLevel, locking);
    // Keep track of it so we can reuse later.
    this.conglomerateControllers.add(cc);
    return cc;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglom
   *          DOCUMENT ME!
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param scanColumnList
   *          DOCUMENT ME!
   * @param startKeyValue
   *          DOCUMENT ME!
   * @param startSearchOperator
   *          DOCUMENT ME!
   * @param qualifier
   *          DOCUMENT ME!
   * @param stopKeyValue
   *          DOCUMENT ME!
   * @param stopSearchOperator
   *          DOCUMENT ME!
   * @param static_info
   *          DOCUMENT ME!
   * @param dynamic_info
   *          DOCUMENT ME!
   * @param act
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  private MemScanController openScan(MemConglomerate conglom, boolean hold,
      int open_mode, int lock_level, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info, Activation act)
      throws StandardException {
    if (SanityManager.DEBUG) {
      if ((open_mode & ~(TransactionController.OPENMODE_FORUPDATE
          | TransactionController.OPENMODE_USE_UPDATE_LOCKS
          | TransactionController.OPENMODE_FOR_LOCK_ONLY
          | TransactionController.OPENMODE_LOCK_NOWAIT
          | TransactionController.OPENMODE_SECONDARY_LOCKED
          | GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX
          | GfxdConstants.SCAN_OPENMODE_FOR_FULLSCAN
          | GfxdConstants.SCAN_OPENMODE_FOR_REFERENCED_PK
          | GfxdConstants.SCAN_OPENMODE_FOR_READONLY_LOCK)) != 0) {
        SanityManager.THROWASSERT("Bad open mode to openScan: 0x"
            + Integer.toHexString(open_mode));
      }
      if (!((lock_level == MODE_RECORD) | (lock_level == MODE_TABLE))) {
        SanityManager.THROWASSERT("Bad lock level to openScan:" + lock_level);
      }
    }
    // open a scan with local data set.
    return openScanController(conglom, hold, open_mode, lock_level,
        isolation_level, scanColumnList, startKeyValue, startSearchOperator,
        qualifier, stopKeyValue, stopSearchOperator, static_info, dynamic_info,
        act);
  }

  /*
   * Public Methods of TransactionController interface.
   *************************************************************************
   */

  /**
   * Add a column to a conglomerate. The conglomerate must not be open in the
   * current transaction. This also means that there must not be any active
   * scans on it. The column can only be added at the spot just after the
   * current set of columns. The template_column must be nullable. After this
   * call has been made, all fetches of this column from rows that existed in
   * the table prior to this call will return "null".
   * 
   * @param conglomId
   *          The identifier of the conglomerate to drop.
   * @param column_id
   *          The column number to add this column at.
   * @param template_column
   *          An instance of the column to be added to table.
   * @param collation_id
   *          collation id of the added column.
   * 
   * @exception StandardException
   *              Only some types of conglomerates can support adding a column,
   *              for instance "heap" conglomerates support adding a column
   *              while "btree" conglomerates do not. If the column can not be
   *              added an exception will be thrown.
   */
  public void addColumnToConglomerate(long conglomId, int column_id,
      Storable template_column, int collation_id) throws StandardException {
    final MemConglomerate conglom = findExistingConglomerate(conglomId);
    // Get exclusive lock on the table being altered.
    final MemConglomerateController cc = openConglomerateController(conglom,
        OPENMODE_FORUPDATE, MODE_TABLE, null);
    conglom.addColumn(this, column_id, template_column, collation_id);
    cc.close();
  }

  /**
   * Return static information about the conglomerate to be included in a a
   * compiled plan.
   * 
   * <p>
   * The static info would be valid until any ddl was executed on the conglomid,
   * and would be up to the caller to throw away when that happened. This ties
   * in with what language already does for other invalidation of static info.
   * The type of info in this would be containerid and array of format id's from
   * which templates can be created. The info in this object is read only and
   * can be shared among as many threads as necessary.
   * 
   * <p>
   * 
   * @return The static compiled information.
   * 
   * @param conglomId
   *          The identifier of the conglomerate to open.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public StaticCompiledOpenConglomInfo getStaticCompiledConglomInfo(
      long conglomId) throws StandardException {
    return (findExistingConglomerate(conglomId).getStaticCompiledConglomInfo(
        this, conglomId));
  }

  /**
   * Return dynamic information about the conglomerate to be dynamically reused
   * in repeated execution of a statement.
   * 
   * <p>
   * The dynamic info is a set of variables to be used in a given ScanController
   * or ConglomerateController. It can only be used in one controller at a time.
   * It is up to the caller to insure the correct thread access to this info.
   * The type of info in this is a scratch template for btree traversal, other
   * scratch variables for qualifier evaluation, ...
   * 
   * <p>
   * 
   * @return The dynamic information.
   * 
   * @param conglomId
   *          The identifier of the conglomerate to open.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public DynamicCompiledOpenConglomInfo getDynamicCompiledConglomInfo(
      long conglomId) throws StandardException {
    // no longer used by GemFireXD; all relevant information is there in
    // MemHeap/MemIndex
    return null;
  }

  /**
   * DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   */
  private int countCreatedSorts() {
    int numSorts = 0;
    if (this.sortControllers != null) {
      SortController sc;
      for (int index = 0; index < this.sortControllers.size(); ++index) {
        sc = this.sortControllers.get(index);
        if (sc != null) {
          ++numSorts;
        }
      }
    }
    return numSorts;
  }

  /**
   * Report on the number of open conglomerates in the transaction.
   * 
   * <p>
   * There are 4 types of open "conglomerates" that can be tracked, those opened
   * by each of the following: openConglomerate(), openScan(), openSort(), and
   * openSortScan(). This routine can be used to either report on the number of
   * all opens, or may be used to track one particular type of open. This
   * routine is expected to be used for debugging only. An implementation may
   * only track this info under SanityManager.DEBUG mode. If the implementation
   * does not track the info it will return -1 (so code using this call to
   * verify that no congloms are open should check for return <= 0 rather than
   * == 0). The return value depends on the "which_to_count" parameter as
   * follows: OPEN_CONGLOMERATE - return # of openConglomerate() calls not
   * close()'d. OPEN_SCAN - return # of openScan() calls not close()'d.
   * OPEN_CREATED_SORTS - return # of sorts created (createSort()) in current
   * xact. There is currently no way to get rid of these sorts before end of
   * transaction. OPEN_SORT - return # of openSort() calls not close()'d.
   * OPEN_TOTAL - return total # of all above calls not close()'d. - note an
   * implementation may return -1 if it does not track the above information.
   * 
   * @return The nunber of open's of a type indicated by "which_to_count"
   *         parameter.
   * 
   * @param which_to_count
   *          Which kind of open to report on.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public int countOpens(int which_to_count) throws StandardException {
    int count = -1;
    switch (which_to_count) {
      case OPEN_CONGLOMERATE: {
        count = countOpenConglomerateControllers();
        break;
      }
      case OPEN_SCAN: {
        count = countOpenScanManagers();
        break;
      }
      case OPEN_CREATED_SORTS: {
        count = countCreatedSorts();
        break;
      }
      case OPEN_SORT: {
        count = this.sortControllers != null ? this.sortControllers.size() : 0;
        break;
      }
      case OPEN_TOTAL: {
        count = countOpenConglomerateControllers()
            + countOpenScanManagers()
            + ((this.sortControllers != null) ? this.sortControllers.size() : 0)
            + countCreatedSorts();
        break;
      }
    }
    return count;
  }

  private int countOpenConglomerateControllers() {
    int count = 0;
    MemConglomerateController cc;
    for (int index = 0; index < this.conglomerateControllers.size(); ++index) {
      cc = this.conglomerateControllers.get(index);
      if (!cc.isClosed()) {
        ++count;
      }
    }
    return count;
  }

  private int countOpenScanManagers() {
    int count = 0;
    ScanManager sm;
    for (int index = 0; index < this.scanControllers.size(); ++index) {
      sm = this.scanControllers.get(index);
      if (!sm.isScanClosed()) {
        ++count;
      }
    }
    return count;
  }

  /**
   * Create a new conglomerate.
   * 
   * <p>
   * 
   * @see TransactionController#createConglomerate
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public long createConglomerate(String implementation,
      DataValueDescriptor[] template, ColumnOrdering[] columnOrder,
      int[] collationIds, Properties properties, int temporaryFlag)
      throws StandardException {

    final GemFireStore memStore = Misc.getMemStore();
    // Find the appropriate factory for the desired implementation.
    MethodFactory mfactory = memStore.findMethodFactoryByImpl(implementation);
    if ((mfactory == null) || !(mfactory instanceof MemConglomerateFactory)) {
      throw StandardException.newException(
          SQLState.AM_NO_SUCH_CONGLOMERATE_TYPE, implementation);
    }
    final MemConglomerateFactory cfactory = (MemConglomerateFactory)mfactory;
    // Create the conglomerate
    int segmentId;
    long conglomId;
    if ((temporaryFlag & TransactionController.IS_TEMPORARY)
        == TransactionController.IS_TEMPORARY) {
      segmentId = ContainerHandle.TEMPORARY_SEGMENT;
      conglomId = getNextTempConglomId();
    }
    else {
      // RESOLVE - only using segment 0
      segmentId = ContainerHandle.TABLE_SEGMENT;
      conglomId = memStore.getNextConglomId();
    }
    if (GemFireXDUtils.TraceConglom) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "GemFireTransaction TX " + toString()
              + ": creating conglomerate with id " + conglomId);
    }
    // call the factory to actually create the conglomerate.
    final MemConglomerate conglom = cfactory.createConglomerate(this,
        segmentId, conglomId, template, columnOrder, collationIds, properties,
        temporaryFlag);
    conglom.create(this, segmentId, conglomId, template, columnOrder,
        collationIds, properties, temporaryFlag);
    if (needLogging()) {
      final ContainerCreateOperation operation = new ContainerCreateOperation(
          conglom, properties);
      logAndDo(operation);
    }
    else {
      ContainerCreateOperation.doMe(this, conglom, properties);
    }

    if ((temporaryFlag & TransactionController.IS_TEMPORARY)
        == TransactionController.IS_TEMPORARY) {
      if (this.tempCongloms == null) {
        this.tempCongloms = new TLongObjectHashMap();
      }
      this.tempCongloms.put(conglomId, conglom);
    }
    final GemFireContainer container = conglom.getGemFireContainer();
    if (container != null) {
      if (GemFireXDUtils.TraceConglom
          || (container.isApplicationTableOrGlobalIndex() && !"SYSSTAT"
              .equalsIgnoreCase(container.getSchemaName())
          && !Misc.isSnappyHiveMetaTable((container.getSchemaName())))) {
        SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
            "GemFireTransaction TX " + (GemFireXDUtils.TraceConglom ? toString()
                : Long.toString(this.myId)) + ": created conglomerate with id "
                + conglomId + ": " + Misc.getMemStore().getContainer(
                    conglom.getId()));
      }
    }
    return conglomId;
  }

  /**
   * Create a conglomerate and populate it with rows from rowSource.
   * 
   * @see TransactionController#createAndLoadConglomerate
   * 
   * @exception StandardException
   *              Standard Derby Error Policy
   */
  public long createAndLoadConglomerate(String implementation,
      DataValueDescriptor[] template, ColumnOrdering[] columnOrder,
      int[] collationIds, Properties properties, int temporaryFlag,
      RowLocationRetRowSource rowSource, long[] rowCount)
      throws StandardException {
    return (recreateAndLoadConglomerate(implementation, true, template,
        columnOrder, collationIds, properties, temporaryFlag,
        0 /* unused if recreate_ifempty is true */, rowSource, rowCount));
  }

  /**
   * recreate a conglomerate and populate it with rows from rowSource.
   * 
   * @see TransactionController#createAndLoadConglomerate
   * 
   * @exception StandardException
   *              Standard Derby Error Policy
   */
  public long recreateAndLoadConglomerate(String implementation,
      boolean recreate_ifempty, DataValueDescriptor[] template,
      ColumnOrdering[] columnOrder, int[] collationIds, Properties properties,
      int temporaryFlag, long orig_conglomId,
      RowLocationRetRowSource rowSource, long[] rowCount)
      throws StandardException {
    if ("heap".equalsIgnoreCase(implementation)) {
      throw new UnsupportedOperationException("Not implemented for HEAPs");
    }
    long conglomId = createConglomerate(implementation, template, columnOrder,
        collationIds, properties, temporaryFlag);
    long rows_loaded = loadConglomerate(conglomId,
        true /* conglom is being created */, rowSource);
    if (rowCount != null) {
      rowCount[0] = rows_loaded;
    }
    if (!recreate_ifempty && (rows_loaded == 0)) {
      dropConglomerate(conglomId);
      conglomId = orig_conglomId;
    }
    return conglomId;
  }

  /**
   * Return a string with debug information about opened congloms/scans/sorts.
   * 
   * <p>
   * Return a string with debugging information about current opened
   * congloms/scans/sorts which have not been close()'d. Calls to this routine
   * are only valid under code which is conditional on SanityManager.DEBUG.
   * 
   * <p>
   * 
   * @return String with debugging information.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public String debugOpened() throws StandardException {
    final StringBuilder sb = new StringBuilder();
    if (SanityManager.DEBUG) {
      for (ScanController sc : this.scanControllers) {
        if (!sc.isScanClosed()) {
          sb.append("open scan controller: ").append(sc)
              .append(SanityManager.lineSeparator);
        }
      }
      for (MemConglomerateController cc : this.conglomerateControllers) {
        if (!cc.isClosed()) {
          sb.append("open conglomerate controller: ").append(cc)
              .append(SanityManager.lineSeparator);
        }
      }
      if (this.sortControllers != null) {
        for (SortController sc : this.sortControllers) {
          sb.append("open sort controller: ").append(sc)
              .append(SanityManager.lineSeparator);
        }
      }
      if (this.sorts != null) {
        for (Sort sort : this.sorts) {
          if (sort != null) {
            sb.append("sorts created by createSort() in current TX: ")
                .append(sort).append(SanityManager.lineSeparator);
          }
        }
      }
      if (this.tempCongloms != null) {
        TLongObjectIterator iter = this.tempCongloms.iterator();
        while (iter.hasNext()) {
          iter.advance();
          long key = iter.key();
          Object val = iter.value(); // A MemConglomerate object
          sb.append("temp conglomerate id = ").append(key).append(": ").append(
              val);
        }
      }
    }
    return sb.toString();
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public boolean conglomerateExists(long conglomId) throws StandardException {
    return findConglomerate(conglomId) != null;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public void dropConglomerate(long conglomId) throws StandardException {
    Conglomerate conglom = findExistingConglomerate(conglomId);
    if (GemFireXDUtils.TraceConglom) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "GemFireTransaction TX " + toString()
              + ": dropping conglomerate with id " + conglomId + ": "
              + Misc.getMemStore().getContainer(conglom.getId()));
    }
    conglom.drop(this);

    if (conglomId < 0) {
      if (this.tempCongloms != null) {
        this.tempCongloms.remove(conglomId);
      }
      if (GemFireXDUtils.TraceConglom) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
            "GemFireTransaction TX " + toString()
                + ": dropped temporary conglomerate with id " + conglomId);
      }
    }
    else {
      // always log non-temporary conglomerate drops
      SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
          "GemFireTransaction TX " + toString()
              + ": dropped conglomerate with id " + conglomId);
    }
  }

  /**
   * Retrieve the maximum value row in an ordered conglomerate.
   * 
   * <p>
   * Returns true and fetches the rightmost row of an ordered conglomerate into
   * "fetchRow" if there is at least one row in the conglomerate. If there are
   * no rows in the conglomerate it returns false.
   * 
   * <p>
   * Non-ordered conglomerates will not implement this interface, calls will
   * generate a StandardException.
   * 
   * <p>
   * RESOLVE - this interface is temporary, long term equivalent (and more)
   * functionality will be provided by the openBackwardScan() interface.
   * 
   * @param conglomId
   *          The identifier of the conglomerate to open the scan for.
   * @param open_mode
   *          Specifiy flags to control opening of table. OPENMODE_FORUPDATE -
   *          if set open the table for update otherwise open table shared.
   * @param lock_level
   *          One of (MODE_TABLE, MODE_RECORD, or MODE_NONE).
   * @param isolation_level
   *          The isolation level to lock the conglomerate at. One of
   *          (ISOLATION_READ_COMMITTED or ISOLATION_SERIALIZABLE).
   * @param scanColumnList
   *          A description of which columns to return from every fetch in the
   *          scan. template, and scanColumnList work together to describe the
   *          row to be returned by the scan - see RowUtil for description of
   *          how these three parameters work together to describe a "row".
   * @param fetchRow
   *          The row to retrieve the maximum value into.
   * 
   * @return boolean indicating if a row was found and retrieved or not.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public boolean fetchMaxOnBtree(long conglomId, int open_mode, int lock_level,
      int isolation_level, FormatableBitSet scanColumnList,
      DataValueDescriptor[] fetchRow) throws StandardException {

    // Find the conglomerate.
    MemConglomerate conglom = findExistingConglomerate(conglomId);

    // Get a scan controller.
    return (conglom.fetchMaxOnBTree(this, null, conglomId, open_mode,
        lock_level, null, isolation_level, scanColumnList, fetchRow));
  }

  /**
   * A superset of properties that "users" can specify.
   * 
   * <p>
   * A superset of properties that "users" (ie. from sql) can specify. Store may
   * implement other properties which should not be specified by users. Layers
   * above access may implement properties which are not known at all to Access.
   * 
   * <p>
   * This list is a superset, as some properties may not be implemented by
   * certain types of conglomerates. For instant an in-memory store may not
   * implement a pageSize property. Or some conglomerates may not support
   * pre-allocation.
   * 
   * <p>
   * This interface is meant to be used by the SQL parser to do validation of
   * properties passsed to the create table statement, and also by the various
   * user interfaces which present table information back to the user.
   * 
   * <p>
   * Currently this routine returns the following list:
   * gemfirexd.storage.initialPages gemfirexd.storage.minimumRecordSize
   * gemfirexd.storage.pageReservedSpace gemfirexd.storage.pageSize
   * 
   * @return The superset of properties that "users" can specify.
   */
  public Properties getUserCreateConglomPropList() {
    Properties ret_properties = ConglomerateUtil
        .createUserRawStorePropertySet((Properties)null);

    return (ret_properties);
  }

  /**
   * See if this transaction is in the idle state, called by other thread to
   * test the state of this transaction.
   * 
   * @return true if it is idle, otherwise false
   */
  public final boolean isIdle() {
    final TXStateInterface tx = getTXState(this.txState);
    if (tx != null) {
      return !tx.getProxy().hasOps();
    }
    return this.state != ACTIVE;
  }

  public final boolean isClosed() {
    return this.state == CLOSED;
  }

  /**
   * Bulk load into the conglomerate. Rows being loaded into the conglomerate
   * are not logged.
   * 
   * @param conglomId
   *          The conglomerate Id.
   * @param createConglom
   *          If true, the conglomerate is being created in the same operation
   *          as the loadConglomerate. The enables further optimization as
   *          recovery does not require page allocation to be logged.
   * @param rowSource
   *          Where the rows come from.
   * 
   * @return true The number of rows loaded.
   * 
   * @exception StandardException
   *              Standard Derby Error Policy
   */
  public long loadConglomerate(long conglomId, boolean createConglom,
      RowLocationRetRowSource rowSource) throws StandardException {

    // Find the conglomerate.
    MemConglomerate conglom = findExistingConglomerate(conglomId);

    // Load up the conglomerate with rows from the rowSource.
    // Don't need to keep track of the conglomerate controller because load
    // automatically closes it when it finished.
    return (conglom.load(this, createConglom, rowSource));
  }

  /**
   * Log an operation and then action it in the context of this transaction.
   * 
   * <p>
   * This simply passes the operation to the RawStore which logs and does it.
   * 
   * <p>
   * 
   * @param operation
   *          the operation that is to be applied
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void logAndDo(Loggable operation) throws StandardException {
    // memory operations don't care about the LogInstant or LimitObjectInput
    // objects anyway, so just pass null
    assert operation instanceof MemOperation: "the loggable is supposed "
        + "to be a MemOperation";
    if (this.logger == null) {
      this.logger = (MemLogger)this.logFactory.getLogger();
      setActiveState();
    }
    if (GemFireXDUtils.TraceTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, toString()
          + " logAndDo operation: " + operation);
    }
    this.logger.logAndDo(this, operation);
  }

  /**
   * @exception StandardException
   *              Standard Derby exception policy
   * 
   * @see Transaction#openContainer
   */
  public ContainerHandle openContainer(ContainerKey containerId, int mode)
      throws StandardException {
    return openContainer(containerId, null, mode);
  }

  /**
   * @exception StandardException
   *              Standard Derby exception policy
   * 
   * @see Transaction#openContainer
   */
  public ContainerHandle openContainer(ContainerKey containerId,
      LockingPolicy locking, int mode) throws StandardException {
    if (SanityManager.DEBUG) {
      if ((mode & (ContainerHandle.MODE_READONLY
          | ContainerHandle.MODE_FORUPDATE)) == (ContainerHandle.MODE_READONLY
              | ContainerHandle.MODE_FORUPDATE)) {
        SanityManager.THROWASSERT("update and readonly mode specified");
      }
    }
    final GemFireContainer container = Misc.getMemStore().getContainer(
        containerId);
    if (container != null) {
      container.open(this, mode);
      // do not care about the return value of lockContainer since it can be
      // false if this is a remote node; if there is a real timeout then
      // GFContainerLocking will throw the appropriate exception and in this
      // respect it deviates from the contract of LockingPolicy
    }
    return container;
  }

  /**
   * @see Transaction#dropContainer
   * 
   * @exception StandardException
   *              Standard Derby error policy
   */
  public void dropContainer(ContainerKey containerId) throws StandardException {
    final GemFireStore memStore = Misc.getMemStore();
    final GemFireContainer container;
    final LockingPolicy locking;
    if ((container = memStore.getContainer(containerId)) != null
        && !skipLocks(container, null)
        && (locking = container.getLockingPolicy()) != null) {
      // take the write lock (distributed lock for non-temporary tables)
      // before dropping the container
      locking.lockContainer(this, container, true, true);
    }
    if (needLogging()) {
      ContainerDropOperation operation = new ContainerDropOperation(
          containerId, false);
      logAndDo(operation);
    }
    else {
      ContainerDropOperation.doMe(this, containerId);
    }
  }

  /**
   * DOCUMENT ME!
   * 
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param static_info
   *          DOCUMENT ME!
   * @param dynamic_info
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public MemConglomerateController openCompiledConglomerate(boolean hold,
      int open_mode, int lock_level, int isolation_level,
      StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info) throws StandardException {

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(static_info != null);
    }
    // in the current implementation, only Conglomerate's are passed around
    // as StaticCompiledOpenConglomInfo.
    return openConglomerate((MemConglomerate)static_info.getConglom(), hold,
        open_mode, lock_level, isolation_level, static_info, dynamic_info);
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public MemConglomerateController openConglomerate(long conglomId,
      boolean hold, int open_mode, int lock_level, int isolation_level)
      throws StandardException {
    return openConglomerate(findExistingConglomerate(conglomId), hold,
        open_mode, lock_level, isolation_level, null, null);
  }

  /**
   * DOCUMENT ME!
   * 
   * @param container_id
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public long findConglomid(long container_id) throws StandardException {
    return (container_id);
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglom_id
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public long findContainerid(long conglom_id) throws StandardException {
    return (conglom_id);
  }

  /**
   * Create a BackingStoreHashtable which contains all rows that qualify for the
   * described scan.
   */
  public BackingStoreHashtable createBackingStoreHashtableFromScan(
      long conglomId, int open_mode, int lock_level, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      long max_rowcnt, int[] key_column_numbers, boolean remove_duplicates,
      long estimated_rowcnt, long max_inmemory_rowcnt, int initialCapacity,
      float loadFactor, boolean collect_runtimestats,
      boolean skipNullKeyColumns, boolean keepAfterCommit, Activation activation)
      throws StandardException {

    final BackingStoreHashTableFromScan ht = (new BackingStoreHashTableFromScan(
        this, conglomId, open_mode, lock_level, isolation_level,
        scanColumnList, startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator, max_rowcnt, key_column_numbers,
        remove_duplicates, estimated_rowcnt, max_inmemory_rowcnt,
        initialCapacity, loadFactor, collect_runtimestats, skipNullKeyColumns,
        keepAfterCommit, activation));
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.scanControllerOpened(ht, findExistingConglomerate(conglomId));
    }
    return ht;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param scanColumnList
   *          DOCUMENT ME!
   * @param startKeyValue
   *          DOCUMENT ME!
   * @param startSearchOperator
   *          DOCUMENT ME!
   * @param qualifier
   *          DOCUMENT ME!
   * @param stopKeyValue
   *          DOCUMENT ME!
   * @param stopSearchOperator
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public MemScanController openGroupFetchScan(long conglomId, boolean hold,
      int open_mode, int lock_level, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator)
      throws StandardException {
    return openScan(findExistingConglomerate(conglomId), hold, open_mode,
        lock_level, isolation_level, scanColumnList, startKeyValue,
        startSearchOperator, qualifier, stopKeyValue, stopSearchOperator, null,
        null, null);
  }

  /**
   * Check if we already have a closed scan of the same type then reinitialize
   * with new parameters, else open a new scan. Note that this is different from
   * reopenScan since this can potentially be against a new Conglomerate.
   */
  private MemScanController openScanController(MemConglomerate conglom,
      boolean hold, int openMode, int lockLevel, int isolationLevel,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo staticInfo,
      DynamicCompiledOpenConglomInfo dynamicInfo, Activation act)
      throws StandardException {
    // search for the scan in existing ScanManagers
    final MemScanController sc;
    final int conglomType = conglom.getType();
    ScanManager sm;
    for (int index = 0; index < this.scanControllers.size(); ++index) {
      sm = this.scanControllers.get(index);
      if (sm.isScanClosed() && conglomType == sm.getType()) {
        sc = (MemScanController)sm;
        sc.init(this, conglom, openMode, lockLevel, null, scanColumnList,
            startKeyValue, startSearchOperator, qualifier, stopKeyValue,
            stopSearchOperator, act);
        return sc;
      }
    }
    sc = conglom.openScan(this, this, hold, openMode, lockLevel, null,
        isolationLevel, scanColumnList, startKeyValue, startSearchOperator,
        qualifier, stopKeyValue, stopSearchOperator, null, dynamicInfo, act);
    // Keep track of it so we can reuse it later.
    this.scanControllers.add(sc);
    return sc;
  }

  /**
   * Purge all committed deleted rows from the conglomerate.
   * 
   * <p>
   * This call will purge committed deleted rows from the conglomerate, that
   * space will be available for future inserts into the conglomerate.
   * 
   * <p>
   * 
   * @param conglomId
   *          Id of the conglomerate to purge.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void purgeConglomerate(long conglomId) throws StandardException {
    findExistingConglomerate(conglomId).purgeConglomerate(this, this);
  }

  /**
   * Return free space from the conglomerate back to the OS.
   * 
   * <p>
   * Returns free space from the conglomerate back to the OS. Currently only the
   * sequential free pages at the "end" of the conglomerate can be returned to
   * the OS.
   * 
   * <p>
   * 
   * @param conglomId
   *          Id of the conglomerate to purge.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void compressConglomerate(long conglomId) throws StandardException {
    findExistingConglomerate(conglomId).compressConglomerate(this, this);
  }

  /**
   * Compress table in place.
   * 
   * <p>
   * Returns a GroupFetchScanController which can be used to move rows around in
   * a table, creating a block of free pages at the end of the table. The
   * process will move rows from the end of the table toward the beginning. The
   * GroupFetchScanController will return the old row location, the new row
   * location, and the actual data of any row moved. Note that this scan only
   * returns moved rows, not an entire set of rows, the scan is designed
   * specifically to be used by either explicit user call of the
   * ONLINE_COMPRESS_TABLE() procedure, or internal background calls to compress
   * the table. The old and new row locations are returned so that the caller
   * can update any indexes necessary. This scan always returns all collumns of
   * the row. All inputs work exactly as in openScan(). The return is a
   * GroupFetchScanController, which only allows fetches of groups of rows from
   * the conglomerate.
   * 
   * <p>
   * 
   * @return The GroupFetchScanController to be used to fetch the rows.
   * 
   * @param conglomId
   *          see openScan()
   * @param hold
   *          see openScan()
   * @param open_mode
   *          see openScan()
   * @param lock_level
   *          see openScan()
   * @param isolation_level
   *          see openScan()
   * 
   * @exception StandardException
   *              Standard exception policy.
   * 
   * @see ScanController
   * @see GroupFetchScanController
   */
  public MemScanController defragmentConglomerate(long conglomId,
      boolean online, boolean hold, int open_mode, int lock_level,
      int isolation_level) throws StandardException {
    if (SanityManager.DEBUG) {
      if ((open_mode & ~(TransactionController.OPENMODE_FORUPDATE
          | TransactionController.OPENMODE_FOR_LOCK_ONLY
          | TransactionController.OPENMODE_SECONDARY_LOCKED)) != 0) {
        SanityManager.THROWASSERT("Bad open mode to openScan: 0x"
            + Integer.toHexString(open_mode));
      }
      if (!((lock_level == MODE_RECORD) | (lock_level == MODE_TABLE))) {
        SanityManager.THROWASSERT("Bad lock level to openScan:" + lock_level);
      }
    }
    // Find the conglomerate.
    final MemConglomerate conglom = findExistingConglomerate(conglomId);
    // Get a scan controller.
    final MemScanController sc = conglom.defragmentConglomerate(this, this,
        hold, open_mode, lock_level, null, isolation_level);
    // Keep track of it so we can release on close.
    this.scanControllers.add(sc);
    return sc;
  }

  /**
   * DOCUMENT ME!
   * 
   * @param conglomId
   *          DOCUMENT ME!
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param scanColumnList
   *          DOCUMENT ME!
   * @param startKeyValue
   *          DOCUMENT ME!
   * @param startSearchOperator
   *          DOCUMENT ME!
   * @param qualifier
   *          DOCUMENT ME!
   * @param stopKeyValue
   *          DOCUMENT ME!
   * @param stopSearchOperator
   *          DOCUMENT ME!
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public MemScanController openScan(long conglomId, boolean hold,
      int open_mode, int lock_level, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator, Activation activation)
      throws StandardException {
    return openScan(findExistingConglomerate(conglomId), hold, open_mode,
        lock_level, isolation_level, scanColumnList, startKeyValue,
        startSearchOperator, qualifier, stopKeyValue, stopSearchOperator, null,
        null, activation);
  }

  /**
   * DOCUMENT ME!
   * 
   * @param hold
   *          DOCUMENT ME!
   * @param open_mode
   *          DOCUMENT ME!
   * @param lock_level
   *          DOCUMENT ME!
   * @param isolation_level
   *          DOCUMENT ME!
   * @param scanColumnList
   *          DOCUMENT ME!
   * @param startKeyValue
   *          DOCUMENT ME!
   * @param startSearchOperator
   *          DOCUMENT ME!
   * @param qualifier
   *          DOCUMENT ME!
   * @param stopKeyValue
   *          DOCUMENT ME!
   * @param stopSearchOperator
   *          DOCUMENT ME!
   * @param static_info
   *          DOCUMENT ME!
   * @param dynamic_info
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public MemScanController openCompiledScan(boolean hold, int open_mode,
      int lock_level, int isolation_level, FormatableBitSet scanColumnList,
      DataValueDescriptor[] startKeyValue, int startSearchOperator,
      Qualifier[][] qualifier, DataValueDescriptor[] stopKeyValue,
      int stopSearchOperator, StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info) throws StandardException {
    // in the current implementation, only Conglomerate's are passed around
    // as StaticCompiledOpenConglomInfo.
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(static_info != null);
      SanityManager
          .ASSERT(static_info instanceof StaticCompiledOpenConglomInfo);
    }
    return openScan(((MemConglomerate)static_info.getConglom()), hold,
        open_mode, lock_level, isolation_level, scanColumnList, startKeyValue,
        startSearchOperator, qualifier, stopKeyValue, stopSearchOperator,
        static_info, dynamic_info, null);
  }

  public MemScanController openCompiledScan(boolean hold, int open_mode,
      int lock_level, int isolation_level, FormatableBitSet scanColumnList,
      DataValueDescriptor[] startKeyValue, int startSearchOperator,
      Qualifier[][] qualifier, DataValueDescriptor[] stopKeyValue,
      int stopSearchOperator, StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info, Activation act)
      throws StandardException {
    // in the current implementation, only Conglomerate's are passed around
    // as StaticCompiledOpenConglomInfo.
    return openScan(((MemConglomerate)static_info.getConglom()), hold,
        open_mode, lock_level, isolation_level, scanColumnList, startKeyValue,
        startSearchOperator, qualifier, stopKeyValue, stopSearchOperator,
        static_info, dynamic_info, act);
  }

  /**
   * Return an open StoreCostController for the given conglomid.
   * 
   * <p>
   * Return an open StoreCostController which can be used to ask about the
   * estimated row counts and costs of ScanController and ConglomerateController
   * operations, on the given conglomerate.
   * 
   * <p>
   * 
   * @return The open StoreCostController.
   * 
   * @param conglomId
   *          The identifier of the conglomerate to open.
   * 
   * @exception StandardException
   *              Standard exception policy.
   * 
   * @see StoreCostController
   */
  public StoreCostController openStoreCost(long conglomId)
      throws StandardException {
    // Find the conglomerate.
    final MemConglomerate conglom = findExistingConglomerate(conglomId);
    // Get a scan controller.
    return conglom.openStoreCost(this, this);
  }

  @Override
  public long createSort(Properties implParameters,
      DataValueDescriptor[] template, ColumnOrdering[] columnOrdering,
      SortObserver sortObserver, boolean alreadyInOrder, long estimatedRows,
      int estimatedRowSize) throws StandardException {
    /*
    String implementation = null;
    if (implParameters != null) {
      implementation = implParameters
          .getProperty(AccessFactoryGlobals.IMPL_TYPE);
    }
    if (implementation == null) {
      implementation = AccessFactoryGlobals.SORT_EXTERNAL;
    }
    // Find the appropriate factory for the desired implementation.
    final MethodFactory mfactory = Misc.getMemStore().findMethodFactoryByImpl(
        implementation);
    if ((mfactory == null) || !(mfactory instanceof SortFactory)) {
      throw (StandardException.newException(
          SQLState.AM_NO_FACTORY_FOR_IMPLEMENTATION, implementation));
    }
    SortFactory sfactory = (SortFactory)mfactory;
    // Decide what segment the sort should use.
    int segment = 0;
    // Create the sort.
    Sort sort = sfactory.createSort(this, segment, implParameters, template,
        columnOrdering, sortObserver, alreadyInOrder, estimatedRows,
        estimatedRowSize);
    //*/
    final ArraySorter sort = ArraySorter.create(this, columnOrdering,
        sortObserver, alreadyInOrder, estimatedRows, estimatedRowSize, 0);
    // Add the sort to the sorts vector
    if (this.sorts == null) {
      this.sorts = new ArrayList<Sort>(4);
      this.freeSortIds = new TIntArrayList(4);
    }

    int sortid;
    if (this.freeSortIds.isEmpty()) {
      // no free identifiers, add sort at the end
      sortid = this.sorts.size();
      this.sorts.add(sort);
    }
    else {
      // reuse a sort identifier
      sortid = freeSortIds.remove(freeSortIds.size() - 1);
      this.sorts.set(sortid, sort);
    }
    return sortid;
  }

  @Override
  public long createSort(Properties implParameters, ExecRow template,
      ColumnOrdering[] columnOrdering, SortObserver sortObserver,
      boolean alreadyInOrder, long estimatedRows, int estimatedRowSize,
      long maxSortLimit) throws StandardException {
    final ArraySorter sort = ArraySorter.create(this, columnOrdering,
        sortObserver, alreadyInOrder, estimatedRows, estimatedRowSize,
        maxSortLimit);
    // Add the sort to the sorts vector
    if (this.sorts == null) {
      this.sorts = new ArrayList<Sort>(4);
      this.freeSortIds = new TIntArrayList(4);
    }

    int sortId;
    if (this.freeSortIds.isEmpty()) {
      // no free identifiers, add sort at the end
      sortId = this.sorts.size();
      this.sorts.add(sort);
    }
    else {
      // reuse a sort identifier
      sortId = freeSortIds.remove(freeSortIds.size() - 1);
      this.sorts.set(sortId, sort);
    }
    return sortId;
  }

  /**
   * Drop a sort.
   * 
   * <p>
   * Drop a sort created by a call to createSort() within the current
   * transaction (sorts are automatically "dropped" at the end of a transaction.
   * This call should only be made after all openSortScan()'s and openSort()'s
   * have been closed.
   * 
   * <p>
   * 
   * @param sortId
   *          The identifier of the sort to drop, as returned from createSort.
   * 
   * @exception StandardException
   *              From a lower-level exception.
   */
  public void dropSort(long sortId) throws StandardException {

    final int intSortId = (int)sortId;
    // should call close on the sort.
    final Sort sort;
    if (this.sorts != null && intSortId < this.sorts.size() && intSortId >= 0
        && (sort = this.sorts.get(intSortId)) != null) {
      sort.drop(this);
      this.sorts.set(intSortId, null);
      this.freeSortIds.add(intSortId);
    }
  }

  /**
   * @see TransactionController#getProperty
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public Serializable getProperty(String key) throws StandardException {
    return Misc.getMemStoreBooting().getProperty(key);
  }

  /**
   * @see TransactionController#getPropertyDefault
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public Serializable getPropertyDefault(String key) throws StandardException {
    return Misc.getMemStoreBooting().getPropertyDefault(key);
  }

  /**
   * @see TransactionController#setProperty
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void setProperty(String key, Serializable value, boolean dbOnlyProperty)
      throws StandardException {
    Misc.getMemStoreBooting().setProperty(key, value, dbOnlyProperty);
  }

  /**
   * @see TransactionController#setProperty
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void setPropertyDefault(String key, Serializable value)
      throws StandardException {
    Misc.getMemStoreBooting().setPropertyDefault(key, value);
  }

  /**
   * @see TransactionController#propertyDefaultIsVisible
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public boolean propertyDefaultIsVisible(String key) throws StandardException {
    return Misc.getMemStoreBooting().propertyDefaultIsVisible(key);
  }

  /**
   * @see TransactionController#getProperties
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public Properties getProperties() throws StandardException {
    return Misc.getMemStoreBooting().getProperties();
  }

  /**
   * @see TransactionController#openSort
   * 
   * @exception StandardException
   *              Standard error policy.
   */
  public final SortController openSort(final long id) throws StandardException {
    final Sort sort;
    // Find the sort in the sorts list, throw an error
    // if it doesn't exist.
    final ArrayList<Sort> sorts = this.sorts;
    if ((sorts == null) || (id >= sorts.size())
        || ((sort = (sorts.get((int)id))) == null)) {
      throw StandardException.newException(SQLState.AM_NO_SUCH_SORT,
          Long.valueOf(id));
    }

    // Open it.
    SortController sc = sort.open(this);
    // Keep track of it so we can release on close.
    if (this.sortControllers == null) {
      this.sortControllers = new ArrayList<SortController>(4);
    }
    this.sortControllers.add(sc);

    return sc;
  }

  /**
   * Return an open SortCostController.
   * 
   * <p>
   * Return an open SortCostController which can be used to ask about the
   * estimated costs of SortController() operations.
   * 
   * <p>
   * 
   * @return The open StoreCostController.
   * 
   * @exception StandardException
   *              Standard exception policy.
   * 
   * @see StoreCostController
   */
  public SortCostController openSortCostController(Properties implParameters)
      throws StandardException {
    // Get the implementation type from the parameters.
    // RESOLVE (mikem) need to figure out how to select sort implementation.
    String implementation = null;
    if (implementation == null) {
      implementation = AccessFactoryGlobals.SORT_EXTERNAL;
    }
    // Find the appropriate factory for the desired implementation.
    final MethodFactory mfactory = Misc.getMemStore().findMethodFactoryByImpl(
        implementation);
    if ((mfactory == null) || !(mfactory instanceof SortFactory)) {
      throw (StandardException.newException(
          SQLState.AM_NO_FACTORY_FOR_IMPLEMENTATION, implementation));
    }
    final SortFactory sfactory = (SortFactory)mfactory;
    // open sort cost controller
    return sfactory.openSortCostController();
  }

  /**
   * @see TransactionController#openSortScan
   * 
   * @exception StandardException
   *              Standard error policy.
   */
  public ScanController openSortScan(long id, boolean hold)
      throws StandardException {
    Sort sort;
    // Find the sort in the sorts list, throw an error
    // if it doesn't exist.
    if (this.sorts == null || id >= this.sorts.size()
        || (sort = this.sorts.get((int)id)) == null) {
      throw StandardException.newException(SQLState.AM_NO_SUCH_SORT,
          Long.valueOf(id));
    }
    // Open a scan on it.
    final ScanController sc = sort.openSortScan(this, hold);
    // Keep track of it so we can release on close.
    this.scanControllers.add((ScanManager)sc);

    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.scanControllerOpened(sc, null);
    }
    return sc;
  }

  /**
   * @see TransactionController#openSortRowSource
   * 
   * @exception StandardException
   *              Standard error policy.
   */
  public RowLocationRetRowSource openSortRowSource(long id)
      throws StandardException {
    Sort sort;
    // Find the sort in the sorts list, throw an error
    // if it doesn't exist.
    if (this.sorts == null || id >= this.sorts.size()
        || (sort = this.sorts.get((int)id)) == null) {
      throw StandardException.newException(SQLState.AM_NO_SUCH_SORT,
          Long.valueOf(id));
    }
    // Open a scan row source on it.
    ScanControllerRowSource scan = sort.openSortRowSource(this);
    // Keep track of it so we can release on close.
    this.scanControllers.add((ScanManager)scan);
    return scan;
  }

  /**
   * DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public LogInstant commit() throws StandardException {
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "GemFireTransaction: commit: attempting commit of transaction: "
              + toString());
    }

    final TXStateInterface tx = getTopTXState();
    // if we have already committed or closed the TX then ignore commit
    if (this.state != ACTIVE && tx == null) {
      return null;
    }

    this.closeControllers(false /* don't close held controllers */);
    return commit(COMMIT_SYNC, tx, TXManagerImpl.FULL_COMMIT, null);
  }

  /**
   * DOCUMENT ME!
   * 
   * @param commitflag
   *          DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  public LogInstant commitNoSync(int commitflag) throws StandardException {
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "GemFireTransaction: commit: attempting commitNoSync of transaction: "
              + toString());
    }

    // if we have already committed or closed the TX then ignore commit
    if (this.state != ACTIVE) {
      return null;
    }

    this.closeControllers(false /* don't close held controllers */);
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceTran) {
        int checkflag = Transaction.RELEASE_LOCKS | Transaction.KEEP_LOCKS;
        SanityManager.ASSERT((commitflag & checkflag) != 0,
            "commitNoSync must specify whether to keep or release locks");
        SanityManager.ASSERT((commitflag & checkflag) != checkflag,
            "cannot set both RELEASE and KEEP LOCKS flag");
        if ((commitflag
            & TransactionController.READONLY_TRANSACTION_INITIALIZATION) != 0) {
          SanityManager.ASSERT(this.state == IDLE || this.state == ACTIVE);
        }
      }
    }
    // Short circuit commit no sync if we are still initializing the
    // transaction. Before a new transaction object is returned to the
    // user, it is "commit'ed" many times using commitNoSync with
    // TransactionController.READONLY_TRANSACTION_INITIALIZATION flag to
    // release read locks and reset the transaction state back to Idle.
    // If nothing has actually happened to the transaction object, return
    // right away and avoid the cost of going thru the commit logic.
    if (this.state == IDLE && (commitflag
        & TransactionController.READONLY_TRANSACTION_INITIALIZATION) != 0) {
      releaseAllLocks(true, true);
      return null;
    }
    return commit(COMMIT_NO_SYNC | commitflag, null, TXManagerImpl.FULL_COMMIT,
        null);
  }

  public DatabaseInstant commitForSetIsolationLevel() throws StandardException {
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "GemFireTransaction: commit: attempting commit of transaction: "
              + toString());
    }

    // if we have already committed or closed the TX then ignore commit
    if (this.state != ACTIVE) {
      return null;
    }

    this.closeControllers(false /* don't close held controllers */);
    return commit(COMMIT_SYNC, null, TXManagerImpl.FULL_COMMIT, null);
  }

  /**
   * @exception StandardException
   *              Standard Derby exception policy
   * 
   * @see Transaction#commit
   */
  private LogInstant commit(final int commitflag, final TXStateInterface tx,
      final int commitPhase, TXManagerImpl.TXContext context)
      throws StandardException {
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "commiting TX " + toString());
    }
    if (commitPhase != TXManagerImpl.PHASE_TWO_COMMIT) {
      prepareCommit(commitflag);
    }

    // should do before GFE TX  commit since we need to wait for all replies
    // that the GfxdLockSet#waitForPendingRC() call below will do
    // but don't release the locks yet since it must be done after GFE commit
    waitForPendingRC();

    // don't commit the transaction started by gemfire.

    if (tx != null) {
      // should never happen on remote node
      final LanguageConnectionContext lcc = getLanguageConnectionContext();
      if (lcc == null || lcc.isConnectionForRemote()) {
        throw StandardException.newException(SQLState.XACT_COMMIT_EXCEPTION,
            new GemFireXDRuntimeException(
                "unexpected commit on remote node or from function execution"));
      }
      try {
        if (context == null) {
          context = TXManagerImpl.currentTXContext();
        }
        TXStateInterface gfTx = context != null
            ? context.getSnapshotTXState() : null;
        // commit if implicitely snapshot tx was started
        if ((tx != gfTx && !tx.isSnapshot()) || (tx.isSnapshot() && implicitSnapshotTxStarted)) {
          context = this.txManager.commit(tx, this.connectionID, commitPhase,
              context, false);

          if (tx.isSnapshot() && implicitSnapshotTxStarted) {
            implicitSnapshotTxStarted = false;
            context.setSnapshotTXState(null);
          }
        }
        if (commitPhase != TXManagerImpl.PHASE_ONE_COMMIT) {
          postComplete(commitflag, true);
          setTXState(null);
        }
        else {
          this.txContextFromPrepareStage = context;
        }

      } catch (Exception ex) {
        Throwable t = ex;
        Exception copy = ex;

        final StandardException stdEx;
        if (ex instanceof GemFireException
            && (stdEx = Misc.processGemFireException((GemFireException)ex, ex,
                "commit of " + toString(), true)) != null) {
          throw stdEx;
        }
        while (t != null) {
          if (t instanceof StandardException) {
            throw StandardException.newException(
                SQLState.GFXD_TRANSACTION_INDOUBT, t);
          }
          t = t.getCause();
        }
        throw StandardException.newException(SQLState.GFXD_TRANSACTION_INDOUBT,
            copy);
      }
    }
    else {
      postComplete(commitflag, true);
    }

    return null;
  }

  public final void suspendTransaction() {
    final TXStateInterface txSuspended = this.txManager.internalSuspend();
    final TXStateInterface myTX = this.txState;
    assert myTX == TXStateProxy.TX_NOT_SET
        || myTX == txSuspended: "unexpected mismatch of TX states, "
        + "expected: " + myTX + ", actual: " + txSuspended;

    this.txStateSuspended = txSuspended;
    this.isolationSuspended = this.isolationLevel;

    setTXState(null);
    this.isolationLevel = IsolationLevel.NONE;

    if (GemFireXDUtils.TraceTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: suspendTransaction: Gemfire Transaction "
              + "suspended. transaction is " + toString(),
          GemFireXDUtils.TraceTranVerbose ? new Throwable() : null);
    }
  }

  public final void resumeTransactionIfSuspended() {
    final TXStateInterface txSuspended = this.txStateSuspended;
    if (txSuspended != null) {
      this.txManager.masqueradeAs(txSuspended);

      setTXState(txSuspended);
    }
    this.isolationLevel = this.isolationSuspended;

    this.txStateSuspended = null;
    this.isolationSuspended = IsolationLevel.NONE;

    if (GemFireXDUtils.TraceTran && txSuspended != null) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: resumeTransactionState: GemFire TX resumed: "
              + toString(), GemFireXDUtils.TraceTranVerbose ? new Throwable()
              : null);
    }
  }

  /**
   * Do work of commit that is common to xa_prepare and commit.
   * 
   * <p>
   * Do all the work necessary as part of a commit up to and including writing
   * the commit log record. This routine is used by both prepare and commit. The
   * work post commit is done by completeCommit().
   * 
   * <p>
   * 
   * @param commitflag
   *          various flavors of commit.
   * 
   * @exception StandardException
   *              Standard exception policy.
   * 
   * @see Transaction#commit
   */
  private void prepareCommit(int commitflag) throws StandardException {
    if (this.logger != null && (commitflag & COMMIT_SYNC) == COMMIT_SYNC) {
      this.logger.flushAll();
      disableLogging();
      this.logger = null;
    }
  }

  /**
   * DOCUMENT ME!
   * 
   * @param commitflag
   *          DOCUMENT ME!
   * @param commitOrAbort
   *          DOCUMENT ME!
   * 
   * @throws StandardException
   *           DOCUMENT ME!
   */
  final void postComplete(final int commitflag, final boolean commitOrAbort)
      throws StandardException {
    try {
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "postComplete: flag=" + commitflag + ", commitOrAbort="
                + commitOrAbort + " for TX " + toString());
      }
      releaseRegionLock();
      if ((commitflag & Transaction.KEEP_LOCKS) == 0) {
        releaseAllLocksOnly(true, true);
        setIdleState();
      }
      else {
        SanityManager.ASSERT(commitOrAbort,
            "cannot keep locks around after an ABORT");
        // we have unreleased locks, the transaction has resource and
        // therefore is "active"
        setActiveState();
      }
    } finally {
      this.release();
    }
  }

  /**
   * @see TransactionController#abort()
   */
  public void abort() throws StandardException {
    // abort the parent transaction, if any, since the child is a special
    // transaction used only inside the product for segregation whose abort
    // should lead to failure of top-level TX
    abort(true);
  }

  public void abort(boolean abortParent) throws StandardException {
    try {
      doAbort(abortParent);
    } catch (CancelException ignored) {
    }
  }

  private void doAbort(boolean abortParent) throws StandardException {

    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "GemFireTransaction: abort: attempting abort of transaction "
              + toString(), new Throwable());
    }

    final TXStateInterface tx = getTopTXState();
    // if we have already committed or closed the TX then ignore abort
    if (this.state != ACTIVE && tx == null) {
      return;
    }

    this.closeControllers(true /* close all controllers */);
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
        || GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, "aborting TX "
          + toString());
    }
    if (this.logger != null) {
      this.logger.undo(this, null, null, null);
      this.logger.flushAll();
      disableLogging();
      this.logger = null;
    }

    // should do before GFE TX rollback since we need to wait for all replies
    // that the GfxdLockSet#waitForPendingRC() call below will do
    // but don't release the locks yet since it must be done after GFE rollback
    waitForPendingRC();

    try {
      if (tx != null) {
        // ignore if this is on remote node since abort will be triggered from
        // query node
        final LanguageConnectionContext lcc = getLanguageConnectionContext();
        if (lcc != null && !lcc.isConnectionForRemote()
            // also don't rollback when no connection ID i.e. nested connection
            && this.connectionID.longValue() >= 0) {
          // In case, the tx is started by gemfire layer for snapshot, it should be rollbacked by gemfire layer.
          TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
          TXStateInterface gfTx = context != null ? context.getSnapshotTXState() : null;
          if ((tx.isSnapshot() && implicitSnapshotTxStarted) || (tx != gfTx && !tx.isSnapshot())) {
            this.txManager.rollback(tx, this.connectionID, false);
            if (tx.isSnapshot() && implicitSnapshotTxStarted) {
              implicitSnapshotTxStarted = false;
              if (context != null) {
                context.setSnapshotTXState(null);
              }
            }
          }
          setTXState(null);
        }
      }

      if (abortParent && this.parentTran != null) {
        this.parentTran.abort(true);
      }
    } finally {
      // this releases our locks
      postComplete(0, false);
    }
  }

  public void internalCleanup() throws StandardException {
    try {
      doInternalCleanup();
    } catch (CancelException ignored) {
    }
  }

  private void doInternalCleanup() throws StandardException {

    // if we have already committed or closed the TX then ignore abort
    if (this.state != ACTIVE) {
      return;
    }

    this.closeControllers(true /* close all controllers */);
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
        || GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN, "cleaning TX "
          + toString());
    }
    if (this.logger != null) {
      this.logger.undo(this, null, null, null);
      this.logger.flushAll();
      disableLogging();
      this.logger = null;
    }
    waitForPendingRC();
    // this releases our locks for TX NONE
    if (!isTransactional()) {
      postComplete(0, false);
    }
  }

  public final List<?> getLogAndDoListForCommit() {
    final MemLogger logger = this.logger;
    if (logger != null) {
      return logger.getDoList(this);
    }
    else {
      return null;
    }
  }

  /**
   * Get the context manager that the transaction was created with.
   * 
   * <p>
   * 
   * @return The context manager that the transaction was created with.*
   */
  public ContextManager getContextManager() {
    return this.context != null ? this.context.getContextManager()
        : ContextService.getFactory().getCurrentContextManager();
  }

  /**
   * DOCUMENT ME!
   */
  public void destroy() {
    try {
      switch (this.state) {
        case CLOSED: {
          return;
        }
        case IDLE: {
          abort(false);
          this.state = CLOSED;
          break;
        }
        default: {
          throw StandardException
              .newException(SQLState.XACT_TRANSACTION_NOT_IDLE);
        }
      }
      clean();
      // If there's a context, pop it.
      if (this.context != null && !this.context.getContextManager().isEmpty()) {
        this.context.popMe();
      }
    } catch (StandardException e) {
      // ignored
    } finally {
      this.tempCongloms = null;
    }
  }

  private void reopen(ContextManager cm, String contextId, String transName,
      boolean skipLocks, boolean abortAll) {
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
        || GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: reopening TX " + toString());
    }
    this.transName = transName;
    this.skipLocks = skipLocks;
    this.context.reset(contextId, abortAll);
    cm.pushContext(this.context);
    setActiveState();
  }

  /**
   * Check to see if a database has been upgraded to the required level in order
   * to use a store feature.
   * 
   * @param requiredMajorVersion
   *          required database Engine major version
   * @param requiredMinorVersion
   *          required database Engine minor version
   * @param feature
   *          Non-null to throw an exception, null to return the state of the
   *          version match.
   * 
   * @return <code>true</code> if the database has been upgraded to the required
   *         level, <code>false</code> otherwise.
   * 
   * @exception StandardException
   *              if the database is not at the require version when
   *              <code>feature</code> feature is not <code>null</code> .
   */
  public boolean checkVersion(int requiredMajorVersion,
      int requiredMinorVersion, String feature) throws StandardException {
    return true;
  }

  /**
   * The ConglomerateController.close() method has been called on
   * "conglom_control".
   * 
   * <p>
   * Take whatever cleanup action is appropriate to a closed
   * conglomerateController. It is likely this routine will remove references to
   * the ConglomerateController object that it was maintaining for cleanup
   * purposes.
   */
  public final void closeMe(ConglomerateController cc) {
    // nothing to be done now since we reuse the MemConglomerateController
  }

  /**
   * The SortController.close() method has been called on "sort_control".
   * 
   * <p>
   * Take whatever cleanup action is appropriate to a closed sortController. It
   * is likely this routine will remove references to the SortController object
   * that it was maintaining for cleanup purposes.
   */
  public final void closeMe(SortController sc) {
    this.sortControllers.remove(sc);
  }

  /**
   * The ScanManager.close() method has been called on "scan".
   * 
   * <p>
   * Take whatever cleanup action is appropriate to a closed scan. It is likely
   * this routine will remove references to the scan object that it was
   * maintaining for cleanup purposes.
   */
  public final void closeMe(ScanManager scan) {
    // do not do anything for MemScanControllers since we will reuse them
    if (!(scan instanceof MemScanController)) {
      this.scanControllers.remove(scan);
    }
  }

  /**
   * Get reference to access factory which started this transaction.
   * 
   * <p>
   * 
   * @return The AccessFactory which started this transaction.
   */
  public GemFireStore getAccessManager() {
    return Misc.getMemStore();
  }

  /**
   * Get an Internal transaction.
   * 
   * <p>
   * Start an internal transaction. An internal transaction is a completely
   * separate transaction from the current user transaction. All work done in
   * the internal transaction must be physical (ie. it can be undone physically
   * by the rawstore at the page level, rather than logically undone like btree
   * insert/delete operations). The rawstore guarantee's that in the case of a
   * system failure all open Internal transactions are first undone in reverse
   * order, and then other transactions are undone in reverse order.
   * 
   * <p>
   * Internal transactions are meant to implement operations which, if
   * interupted before completion will cause logical operations like tree
   * searches to fail. This special undo order insures that the state of the
   * tree is restored to a consistent state before any logical undo operation
   * which may need to search the tree is performed.
   * 
   * <p>
   * 
   * @return The new internal transaction.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public TransactionManager getInternalTransaction() throws StandardException {
    // Get the context manager.
    final ContextManager cm = getContextManager();
    // Allocate a new transaction no matter what.
    // Create a transaction, make a context for it, and push the context.
    // Note this puts the raw store transaction context
    // above the access context, which is required for
    // error handling assumptions to be correct.
    return startCommonTransaction(cm, null, null, USER_CONTEXT_ID,
        USER_CONTEXT_ID, this.skipLocks, true, this.connectionID,
        false /* is compile time tx*/);
  }

  /**
   * Get a nested user transaction.
   * 
   * <p>
   * A nested user transaction can be used exactly as any other
   * TransactionController, except as follows. For this discussion let the
   * parent transaction be the transaction used to make the
   * getNestedUserTransaction(), and let the child transaction be the
   * transaction returned by the getNestedUserTransaction() call.
   * 
   * <p>
   * The nesting is limited to one level deep. An exception will be thrown if a
   * subsequent getNestedUserTransaction() is called on the child transaction.
   * 
   * <p>
   * The locks in the child transaction will be compatible with the locks of the
   * parent transaction.
   * 
   * <p>
   * A commit in the child transaction will release locks associated with the
   * child transaction only, work can continue in the parent transaction at this
   * point.
   * 
   * <p>
   * Any abort of the child transaction will result in an abort of both the
   * child transaction and parent transaction.
   * 
   * <p>
   * A TransactionController.destroy() call should be made on the child
   * transaction once all child work is done, and the caller wishes to continue
   * work in the parent transaction.
   * 
   * <p>
   * Nested internal transactions are meant to be used to implement system work
   * necessary to commit as part of implementing a user's request, but where
   * holding the lock for the duration of the user transaction is not
   * acceptable. 2 examples of this are system catalog read locks accumulated
   * while compiling a plan, and auto-increment.
   * 
   * <p>
   * 
   * @return The new nested user transaction.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public TransactionController startNestedUserTransaction(boolean readOnly)
      throws StandardException {
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "GemFireTransaction: startNestedUserTransaction for " + toString());
    }
    // Get the context manager.
    final ContextManager cm = getContextManager();
    // Create a transaction, make a context for it, and push the context.
    // Note this puts the raw store transaction context
    // above the access context, which is required for
    // error handling assumptions to be correct.
    if (readOnly) {
      if (this.childTran == null) {
        this.childTran = startCommonTransaction(cm, this, null,
            AccessFactoryGlobals.NESTED_READONLY_USER_TRANS,
            AccessFactoryGlobals.NESTED_READONLY_USER_TRANS, this.skipLocks,
            false, this.connectionID, false /* is execute tx*/);
      }
      else {
        this.childTran.reopen(cm,
            AccessFactoryGlobals.NESTED_READONLY_USER_TRANS,
            AccessFactoryGlobals.NESTED_READONLY_USER_TRANS, skipLocks, false);
      }
      this.childTran.lcc = this.lcc;
      return this.childTran;
    }
    else {
      if (this.childTranUpd == null) {
        this.childTranUpd = startCommonTransaction(cm, this, null,
            AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS,
            AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS, this.skipLocks,
            true, this.connectionID, false /* is execute tx*/);
      }
      else {
        this.childTranUpd.reopen(cm,
            AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS,
            AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS, skipLocks, true);
      }
      this.childTranUpd.lcc = this.lcc;
      return this.childTranUpd;
    }
  }

  /**
   * Do work necessary to maintain the current position in all the scans.
   * 
   * <p>
   * The latched page in the conglomerate "congomid" is changing, do whatever is
   * necessary to maintain the current position of all the scans open in this
   * transaction.
   * 
   * <p>
   * For some conglomerates this may be a no-op.
   * <p>
   * 
   * @param conglom
   *          Conglomerate being changed.
   * @param page
   *          Page in the conglomerate being changed.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public void saveScanPositions(Conglomerate conglom, Page page)
      throws StandardException {

    ScanManager sm;
    for (int index = 0; index < this.scanControllers.size(); ++index) {
      sm = this.scanControllers.get(index);
      sm.savePosition(conglom, page);
    }
  }

  /**
   * Return an object that when used as the compatibility space, <strong>
   * and</strong> the object returned when calling <code>getOwner()</code> on
   * that object is used as group for a lock request, guarantees that the lock
   * will be removed on a commit or an abort.
   */
  public final GfxdLockSet getLockSpace() {
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(this.lockSet != null,
          "cannot have a null lockSpace.");
    }
    return this.lockSet;
  }

  /**
   * Get string id of the transaction.
   * 
   * <p>
   * This transaction "name" will be the same id which is returned in the
   * TransactionInfo information, used by the lock and transaction vti's to
   * identify transactions.
   * 
   * <p>
   * Although implementation specific, the transaction id is usually a number
   * which is bumped every time a commit or abort is issued.
   * 
   * @return The a string which identifies the transaction.
   */
  public String getTransactionIdString() {
    final StringBuilder sb = new StringBuilder();
    return sb.append(this.myId).append('(').append(this.transName).append(')')
        .append('@').append(Integer.toHexString(System.identityHashCode(this)))
        .append(";txState=").append(this.txState).append(";supportsTX=")
        .append(this.txManager != null).toString();
  }

  /**
   * Get string id of the transaction that would be when the Transaction is IN
   * active state.
   */
  public String getActiveStateTxIdString() {
    return toString();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    final LanguageConnectionContext lcc = getLanguageConnectionContext();
    sb.append(this.myId).append('(').append(this.transName).append(')')
        .append('@').append(Integer.toHexString(System.identityHashCode(this)));
    if (this.lockSet != null) {
      sb.append(";owner=").append(this.lockSet.getOwner());
    }
    sb.append(";txState=").append(this.txState).append(";supportsTX=")
        .append(this.txManager != null).append(";needsLogging=")
        .append(this.needLogging);
    if (lcc != null) {
      sb.append(";streaming=").append(lcc.streamingEnabled());
    }
    return sb.append(";skipLocks=").append(this.skipLocks).append(";state=")
        .append(this.state).append(";remote=")
        .append(lcc != null && lcc.isConnectionForRemote()).toString();
  }

  private void releaseRegionLock() {
    RecoveryLock regionRecLock = this.regionRecoveryLock;
    if (regionRecLock != null) {
      regionRecLock.unlock();
      this.regionRecoveryLock = null;
    }
  }

  public void releaseAllLocks(boolean force, boolean removeRef) {
    releaseRegionLock();
    waitForPendingRC();
    releaseAllLocksOnly(force, removeRef);
  }

  private void waitForPendingRC() {
    if (this.lcc == null || !this.lcc.isConnectionForRemote()) {
      getLockSpace().waitForPendingRC();
    }
  }

  private void releaseAllLocksOnly(boolean force, boolean removeRef) {
    if (!this.skipLocks || Misc.initialDDLReplayInProgress()) {
      final GfxdLockSet lockSet = getLockSpace();
      if (GemFireXDUtils.TraceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GemFireTransaction: releasing all locks for TX " + toString());
      }
      if (lockSet.unlockAll(force, removeRef)) {
        // free any distributed lock resources after container drop
        lockSet.freeLockResources();
      }
    }
  }

  @Override
  public void beginTransaction(final IsolationLevel isolationLevel)
      throws StandardException {
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
        || GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: beginTransaction: "
              + " requested isolation "
              + isolationLevel);
    }
    beginTransaction(isolationLevel, null);
  }

  /**
   * Begin a transaction in GemFireCache.
   */
  public final void beginTransaction(final IsolationLevel isolationLevel,
      final TXId txId) throws StandardException {
    if (this.txManager != null) {
      final TXStateInterface tx = this.txState;
      final TXStateInterface gemfireTx = TXManagerImpl.getCurrentSnapshotTXState();

      if (tx != null && tx != TXStateProxy.TX_NOT_SET && gemfireTx != tx) {
        this.txManager.commit(tx, this.connectionID, TXManagerImpl.FULL_COMMIT,
            null, false);
      }
      // now start tx for every operation.
      if (isolationLevel != IsolationLevel.NONE /*|| isSnapshotEnabled()*/) {

        // clear old GemFire TXState in thread-local, if any
        final TXManagerImpl.TXContext context = TXManagerImpl
            .getOrCreateTXContext();
        context.clearTXState();
        final LanguageConnectionContext lcc = getLanguageConnectionContext();
        final EnumSet<TransactionFlag> txFlags;
        final ContextManager cm;
        if (lcc != null) {
          txFlags = lcc.getTXFlags();
          cm = lcc.getContextManager();
        }
        else {
          txFlags = null;
          cm = getContextManager();
        }

        final TXStateInterface newTX;
        final boolean beginTxn = (txId == null);

        if (beginTxn) {
          newTX = this.txManager
              .beginTX(context, isolationLevel, txFlags, txId);
        } else {
          newTX = this.txManager.resumeTX(context, isolationLevel, txFlags,
              txId);
        }
        setTXState(newTX);

        // set the TXId in EmbedConnection finalizer
        final EmbedConnection conn = EmbedConnectionContext
            .getEmbedConnection(cm);
        if (conn != null) {
          conn.setTXIdForFinalizer(newTX.getTransactionId());
        }
        if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
            || GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GemFireTransaction: beginTransaction: "
                  + "new TX state being assigned: " + newTX
                  + ", to transaction: " + toString() + " requested isolation "
                  + isolationLevel);
        }
      } else if (gemfireTx != null/*TXStateProxy.TX_NOT_SET*/) {
        final LanguageConnectionContext lcc = getLanguageConnectionContext();
        final ContextManager cm;
        if (lcc != null) {
          cm = lcc.getContextManager();
        } else {
          cm = getContextManager();
        }
        setTXState(gemfireTx);
        getTransactionManager().masqueradeAs(gemfireTx);
        //Don't setthe TXId in EmbedConnection finalizer the commit must be called
        // explicitely.
        /*final EmbedConnection conn = EmbedConnectionContext
            .getEmbedConnection(cm);
        if (conn != null) {
          conn.setTXIdForFinalizer(gemfireTx.getTransactionId());
        }*/
        if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
            || GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GemFireTransaction: beginTransaction: "
                  + "gemfire TX state being assigned: " + gemfireTx
                  + ", to transaction: " + toString() + " requested isolation "
                  + gemfireTx.getIsolationLevel());
        }
      } else {
        if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
            || GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GemFireTransaction: beginTransaction: "
                  + "changing isolation level to NONE with current TX "
                  + tx + " for " + toString());
        }
        if (tx != null) {
          setTXState(null);
        }
      }
      this.isolationLevel = isolationLevel;
      setActiveState();

      assert this.parentTran == null: "unexpected beginTransaction on "
          + "child transaction " + toString();
    }
  }

  public static GemFireTransaction findUserTransaction(ContextManager cm,
      String transName, long connectionID) throws StandardException {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceTran) {
        final ContextService contextFactory = ContextService.getFactory();
        SanityManager.ASSERT(cm == contextFactory.getCurrentContextManager(),
            "passed in context mgr not the same as current context mgr");
      }
    }
    final GemFireTransactionContext tc = (GemFireTransactionContext)cm
        .getContext(USER_CONTEXT_ID);
    if (GemFireXDUtils.TraceTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction#findUserTransaction: for '" + transName
              + "' found context " + tc);
    }
    if (tc == null) {
      final boolean skipLocks;
      final LanguageConnectionContext lcc = (LanguageConnectionContext)cm
          .getContext(LanguageConnectionContext.CONTEXT_ID);
      if (lcc != null) {
        skipLocks = lcc.skipLocks();
      }
      else {
        skipLocks = false;
      }
      final GemFireTransaction tran = startCommonTransaction(cm, null, null,
          USER_CONTEXT_ID, transName, skipLocks, true, connectionID,
          true /* is TxExecute*/);
      tran.lcc = lcc;
      return tran;
    }
    else {
      return tc.getTransaction();
    }
  }

  /**
   * Common work done to create local transactions.
   * 
   * @param cm
   *          the current context manager to associate the Transaction with
   * @param parentTran
   *          parent Transaction, if any, of the new Transaction
   * @param compatibilitySpace
   *          if null, use the transaction being created, else if non-null use
   *          this compatibilitySpace.
   * @param contextId
   *          the context ID to use for the new Transaction
   * @param skipLocks
   *          set to true to skip acquiring any locks in the new Transaction
   * @param abortAll
   *          if true, then any error will abort the whole transaction.
   *          Otherwise, let Context.cleanupOnError decide what to do
   * @param connectionID
   *          The id of EmbedConnection with which the transaction is associated
   *          with.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  private static GemFireTransaction startCommonTransaction(ContextManager cm,
      GemFireTransaction parentTran, GfxdLockSet compatibilitySpace,
      String contextId, String transName, boolean skipLocks, boolean abortAll,
      long connectionID, boolean isTxExecute) throws StandardException {
    final boolean logTran = GemFireXDUtils.TraceTran
        || GemFireXDUtils.TraceQuery || SanityManager.isFineEnabled
        || GemFireXDUtils.TraceNCJ;
    if (logTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: starting a new TX");
    }
    if (SanityManager.DEBUG) {
      if (logTran) {
        final ContextService contextFactory = ContextService.getFactory();
        SanityManager.ASSERT(cm == contextFactory.getCurrentContextManager());
      }
    }
    final GemFireTransaction tran = new GemFireTransaction(transName,
        parentTran, skipLocks, compatibilitySpace, connectionID, isTxExecute);
    pushTransactionContext(cm, contextId, tran, abortAll);
    tran.setActiveState();
    if (logTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: created new TX " + tran.toString());
    }
    return tran;
  }
  
  @Released(GEMFIRE_TRANSACTION_BYTE_SOURCE)
  public void release() {
    if (this.ownerlessAddressesToFree != null) {
      this.ownerlessAddressesToFree.release();
    }
    if(this.owners != null) {
      Iterator<OffHeapResourceHolder> iter = this.owners.iterator();          
      while (iter.hasNext()) {        
          OffHeapResourceHolder owner = iter.next();
          owner.release();
      }
      this.owners.clear();
    }
  }

  /**
   * Push the context for the given Transaction onto the current context
   * manager.
   * 
   * @param cm
   *          the current context manager to associate the Transaction with
   * @param contextName
   *          the name of the transaction context
   * @param tran
   *          the Transaction object
   * @param abortAll
   *          if true, then any error will abort the whole transaction.
   *          Otherwise, let XactContext.cleanupOnError decide what to do
   * 
   * @exception StandardException
   *              Standard Derby error policy
   */
  private static void pushTransactionContext(ContextManager cm,
      String contextName, GemFireTransaction tran, boolean abortAll)
      throws StandardException {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceTran) {
        if (cm.getContext(contextName) != null) {
          throw StandardException
              .newException(SQLState.XACT_TRANSACTION_ACTIVE);
        }
      }
    }
    tran.context = new GemFireTransactionContext(cm, contextName, tran,
        abortAll);
    if (GemFireXDUtils.TraceTran) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction: pushed new context " + tran.context
              + " for transaction " + tran.toString());
    }
  }

  /**
   * Get DataValueFactory.
   * 
   * <p>
   * Return a DataValueFactory that can be used to allocate objects. Used to
   * make calls to: DataValueFactory.getInstanceUsingFormatIdAndCollationType()
   * 
   * @return a booted data value factory.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public DataValueFactory getDataValueFactory() throws StandardException {
    return this.dataValueFactory;
  }

  /**
   * Get the Transaction from the Transaction manager.
   * 
   * <p>
   * Access methods often need direct access to the "Transaction" - ie. the raw
   * store transaction, so give access to it.
   * 
   * @return The raw store transaction.
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  public RawTransaction getRawStoreXact() throws StandardException {
    return this;
  }

  /**
   * Get the compatibility space of the transaction.
   * 
   * <p>
   * Returns an object that can be used with the lock manager to provide the
   * compatibility space of a transaction. 2 transactions with the same
   * compatibility space will not conflict in locks. The usual case is that each
   * transaction has it's own unique compatibility space.
   * 
   * <p>
   * 
   * @return The compatibility space of the transaction.
   */
  public final GfxdLockSet getCompatibilitySpace() {
    return getLockSpace();
  }

  public final void setIdleState() {
    if (this.state == IDLE) {
      return;
    }
    this.state = IDLE;
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "set idle state TX " + toString());
    }
  }

  public final void setActiveState() {
    if (this.state == ACTIVE) {
      return;
    }
    this.state = ACTIVE;
    if (GemFireXDUtils.TraceTranVerbose) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
          "set active state TX " + toString());
    }
  }

  private final void setActiveStateForTransaction() throws StandardException {
    if (this.txManager != null) {
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GemFireTransaction#setActiveStateForTransaction: for " + this
                + " starting GFE transaction with current thread-local "
                + "GFE TX " + TXManagerImpl.getCurrentTXState());
      }
      beginTransaction(this.isolationLevel);
    }
  }

  public static final void setActiveStateForTransaction(
      final LanguageConnectionContext lcc) throws SQLException {
    if (lcc != null) {
      // nothing to be done on a remote connection
      if (lcc.isConnectionForRemote()) {
        return;
      }
      final GemFireTransaction tran = (GemFireTransaction)lcc
          .getTransactionExecute();
      if (tran != null && ((TXManagerImpl.getCurrentSnapshotTXState() != null) || tran.state != ACTIVE)) {
        try {
          tran.setActiveStateForTransaction();
        } catch (StandardException se) {
          throw Util.generateCsSQLException(se);
        }
      }
    }
  }

  @Override
  public boolean isGlobal() {
    return false;
  }

  @Override
  public final boolean skipLocks() {
    return this.skipLocks;
  }

  @Override
  public final boolean skipLocks(Object warnForOwnerIfSkipLocksForConnection,
      GfxdLockable lockable) {
    if (!this.skipLocks) {
      return false;
    }
    if (this.skipLocksOnConnection
        && (warnForOwnerIfSkipLocksForConnection != null
            || (warnForOwnerIfSkipLocksForConnection = lockable) != null)) {
      // find current execution context, if any, and add warning
      // try to coalesce multiple skip-locks warnings into a single one
      LanguageConnectionContext lcc = getLanguageConnectionContext();
      if (lcc != null && lcc.getActivationCount() > 0) {
        // lets add to only one activation
        try {
          Activation a = lcc.getLastActivation();
          SQLWarning warning = a.getWarnings();
          if (warning == null) {
            a.addWarning(StandardException.newWarning(SQLState.LOCK_SKIPPED,
                warnForOwnerIfSkipLocksForConnection));
          }
          else {
            // check if warning has already been set
            SQLWarning newWarning = addOrUpdateSkipLocksWarning(warning,
                warnForOwnerIfSkipLocksForConnection, warning);
            if (newWarning != null) {
              a.clearWarnings();
              a.addWarning(newWarning);
            }
            else {
              a.addWarning(StandardException.newWarning(SQLState.LOCK_SKIPPED,
                  warnForOwnerIfSkipLocksForConnection));
            }
          }
        } catch (IndexOutOfBoundsException e) {
          // ignore for this case
        }
      }
    }
    return true;
  }

  private SQLWarning addOrUpdateSkipLocksWarning(SQLWarning warning,
      final Object lockObject, SQLWarning iter) {
    do {
      if (SQLState.LOCK_SKIPPED.equals(warning.getSQLState())) {
        String newMessage = warning.getMessage() + ',' + lockObject;
        // recreate entire warning chain (no in-place update possible)
        SQLWarning newWarning = null;
        while (iter != warning) {
          if (newWarning == null) {
            newWarning = new SQLWarning(iter.getMessage(), iter.getSQLState(),
                iter.getErrorCode(), iter.getCause());
          }
          else {
            newWarning.setNextWarning(new SQLWarning(iter.getMessage(), iter
                .getSQLState(), iter.getErrorCode(), iter.getCause()));
          }
          iter = iter.getNextWarning();
        }
        if (newWarning == null) {
          newWarning = new SQLWarning(newMessage, SQLState.LOCK_SKIPPED,
              ExceptionSeverity.STATEMENT_SEVERITY);
        }
        else {
          newWarning.setNextWarning(new SQLWarning(newMessage,
              SQLState.LOCK_SKIPPED, ExceptionSeverity.STATEMENT_SEVERITY));
        }
        if ((iter = iter.getNextWarning()) != null) {
          newWarning.setNextWarning(iter);
        }
        return newWarning;
      }
      warning = warning.getNextWarning();
    } while (warning != null);
    return null;
  }

  @Override
  public void setSkipLocks(boolean skip) {
    this.skipLocks = skip;
  }

  @Override
  public void setSkipLocksForConnection(boolean skip) {
    this.skipLocksOnConnection = skip;
    if (skip) {
      this.skipLocks = true;
    }
  }

  @Override
  public void enableLogging() {
    if (this.state == CLOSED) {
      throw new GemFireXDRuntimeException(
          "GemFireTransaction#enableLogging: unexpected call with state as "
              + "CLOSED [TX " + toString() + ']');
    }
    this.needLogging = true;
  }

  @Override
  public void disableLogging() {
    // assert !this.isTxExecute;
    if (this.state != CLOSED) {
      this.needLogging = false;
    }
  }

  @Override
  public boolean needLogging() {
    if (this.state == CLOSED) {
      throw new GemFireXDRuntimeException(
          "GemFireTransaction#needLogging: unexpected call with state as "
              + "CLOSED [TX " + toString() + ']');
    }
    return this.needLogging;
  }

  /** Clean the cached copy of self in the parent. */
  void clean() {
    if (this.parentTran != null) {
      if (this == this.parentTran.childTran) {
        this.parentTran.childTran = null;
      }
      else {
        this.parentTran.childTranUpd = null;
      }
    }
  }

  public void addDBSynchronizerOperation(AbstractDBSynchronizerMessage dbm) {
    if (GemFireXDUtils.TraceDBSynchronizer) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
          "GemFireTransaction: adding DB Synchronizer operation" + dbm
              + ".  GFT object is =" + this);
    }
    final GfxdTXStateProxy txProxy = this.txProxy;
    if (txProxy != null) {
      txProxy.addDBSynchronizerMessage(dbm);
    }
  }

  public final long getConnectionID() {
    return this.connectionID.longValue();
  }

  public final void setDDLId(long id) {
    this.ddlId = id;
  }

  public final long getDDLId() {
    return this.ddlId;
  }

  private final TXStateInterface getActiveTXState(final TXStateInterface myTX) {
    final IsolationLevel isolationLevel = this.isolationLevel;
    final TXStateInterface gfTx = TXManagerImpl.getCurrentSnapshotTXState();
    if (isolationLevel != IsolationLevel.NONE) {
      if (myTX != null && myTX != TXStateProxy.TX_NOT_SET) {
        return myTX;
      }
    }
    else if (myTX == null && gfTx == null) {
      return null;
    }

    final TXStateInterface tx = getTXState(myTX);
    if (tx != null) {
      return tx;
    }
    if (isolationLevel == IsolationLevel.NONE &&
        gfTx == null) {
      return tx;
    }

    // never start a new transaction at this point on a remote node
    final LanguageConnectionContext lcc = getLanguageConnectionContext();
    if (lcc == null || lcc.isConnectionForRemote()) {
      return tx;
    }
    // old TX committed or aborted so need to start new one
    try {
      setActiveStateForTransaction();
    } catch (StandardException se) {
      throw new GemFireXDRuntimeException(se);
    }
    return getTXState(this.txState);
  }

  private boolean checkTXStateAgainstThreadLocal(final TXStateInterface myTX) {
    if (myTX != TXStateProxy.TX_NOT_SET && this.txManager != null) {
      final TXStateInterface currentTX = TXManagerImpl.getCurrentTXState();
      return myTX == currentTX
          || (currentTX != null && !currentTX.isInProgress())
          || (myTX != null && !myTX.isInProgress());
    } else {
      return true;
    }
  }

  private String failTXStateMismatch(final TXStateInterface myTX) {
    return "unexpected change in TXState expected: " + myTX
        + ", thread-local: " + TXManagerImpl.getCurrentTXState();
  }

  private void logActiveTXState() {
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN_VERBOSE,
        "GemFireTransaction#getActiveTXState: for " + toString()
            + " getting active GFE TXState with thread-local TX "
            + TXManagerImpl.getCurrentTXState());
  }

  public final TXStateInterface getActiveTXState() {
    final TXStateInterface myTX = this.txState;

    assert checkTXStateAgainstThreadLocal(myTX): failTXStateMismatch(myTX);

    if (GemFireXDUtils.TraceTranVerbose) {
      logActiveTXState();
    }
    return getActiveTXState(myTX);
  }

  public final TXStateProxy getCurrentTXStateProxy() {
    if (getTXState(this.txState) != null) {
      return this.txProxy;
    }
    return null;
  }

  public final TXManagerImpl getTransactionManager() {
    return this.txManager;
  }

  private final TXStateInterface getTXState(TXStateInterface tx) {
    if (tx != TXStateProxy.TX_NOT_SET) {
      return tx;
    }
    tx = TXManagerImpl.getCurrentTXState();
    setTXState(tx);
    return tx;
  }

  /**
   * Return the active TXStateProxy or TXState depending on whether this node is
   * coordinator of the active transaction or not.
   */
  public final TXStateInterface getTopTXState() {
    final TXStateInterface tx = getTXState(this.txState);
    if (tx != null) {
      if (tx.isCoordinator()) {
        return this.txProxy;
      }
      return tx.getLocalTXState();
    }
    return null;
  }

  @Override
  public final boolean isTransactional() {
    return (this.isolationLevel != IsolationLevel.NONE) || implicitSnapshotTxStarted;
  }

  public final TXStateInterface getSuspendedTXState() {
    return this.txStateSuspended;
  }

  /**
   * Check if TXState on remote node may need to be set from thread-local one.
   * This is used only by {@link GfxdIndexManager} currently.
   */
  public final boolean checkTXOnRemoteNode() {
    // Remote TX may have TXStateProxy set and may go down to TXState.
    // Reverse means will take function execution route which will set the
    // TXStateProxy in GemFireTransaction in any case.
    final TXStateInterface tx = this.txState;
    return tx == TXStateProxy.TX_NOT_SET || tx == null || tx.getProxy() == tx;
  }

  public final void setActiveTXState(final TXStateInterface tx,
      final boolean setIsolation) {
    final TXStateInterface oldTX = this.txState;
    assert tx != null && this.txManager != null && (!setIsolation
        || oldTX == null || oldTX == TXStateProxy.TX_NOT_SET
        // apart from state check below it also is a volatile read for the
        // next TX in progress check
        || (!isClosed() && !oldTX.isInProgress())
        || tx.getProxy() == oldTX.getProxy()): "unexpected old "
          + oldTX + " when setting " + tx + " for " + toString();

    setTXState(tx);
    if (setIsolation) {
      // isolation level could change
      this.isolationLevel = tx != null ? tx.getIsolationLevel()
          : IsolationLevel.NONE;
    }
    setActiveState();
  }

  private final void setTXState(final TXStateInterface tx) {
    if (this.txState != tx) {
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
          || GemFireXDUtils.TraceNCJ) {
        if (tx != null && tx != TXStateProxy.TX_NOT_SET) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GemFireTransaction#setTXState: for " + toString()
                  + " setting cached GFE transaction to " + tx
                  + " with thread-local TX "
                  + TXManagerImpl.getCurrentTXState());
        }
        else if (GemFireXDUtils.TraceTranVerbose) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GemFireTransaction#setTXState: to " + tx + " for " + toString());
        }
      }

      this.txState = tx;
      if (tx != null && tx != TXStateProxy.TX_NOT_SET) {
        final GfxdTXStateProxy txp = (GfxdTXStateProxy)tx.getProxy();
        if (this.txProxy != txp) {
          txp.addGemFireTransaction(this);
          this.txProxy = txp;
        }
      }
      else {
        final GfxdTXStateProxy txp = this.txProxy;
        if (txp != null) {
          txp.clearGemFireTransaction(this);
          this.txProxy = null;
        }
      }
    }
  }

  public final void clearActiveTXState(final boolean clearGFETX,
      final boolean clearIsolationLevel) {
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction#clearActiveTXState: for " + toString()
              + " clearing cached GFE transaction = " + clearGFETX
              + " with thread-local TX " + TXManagerImpl.getCurrentTXState());
    }

    setTXState(null);
    if (clearGFETX) {
      TXManagerImpl.getOrCreateTXContext().clearTXState();
    }
    if (clearIsolationLevel) {
      this.isolationLevel = IsolationLevel.NONE;
    }
    setActiveState();
  }

  public final void resetActiveTXState(final boolean clearIsolationLevel) {
    assert this.txManager != null: "unexpected null TXManagerImpl";

    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction#resetActiveTXState: for " + toString()
              + " resetting local cached transaction with thread-local TX "
              + TXManagerImpl.getCurrentTXState());
    }
    setTXState(TXStateProxy.TX_NOT_SET);
    if (clearIsolationLevel) {
      this.isolationLevel = IsolationLevel.NONE;
    }
    // force a volatile write
    setActiveState();
  }

  public final void resetTXState() {
    final TXStateInterface tx = this.txState;
    if (tx != null && tx != TXStateProxy.TX_NOT_SET) {
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GemFireTransaction#resetTXState: for " + this.myId + '('
                + this.transName + ") resetting local cached transaction "
                + tx.getTransactionId().shortToString());
      }
      setTXState(TXStateProxy.TX_NOT_SET);
    }
  }

  public static final void reattachTransaction(
      final LanguageConnectionContext lcc, final boolean isOperation)
      throws SQLException {
    if (lcc != null) {
      // nothing to be done on a remote connection
      if (lcc.isConnectionForRemote()) {
        return;
      }
      final GemFireTransaction tran = (GemFireTransaction)lcc
          .getTransactionExecute();
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
          || GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GemFireTransaction#reattachTransaction: for " + tran
                + " attaching to GFE transaction with current thread-local "
                + "GFE TX " + TXManagerImpl.getCurrentTXState());
      }
      if (tran != null) {
        if (tran.txManager != null) {
          final TXManagerImpl.TXContext context;
          final TXStateInterface tx = tran.txState;
          // start a new transaction if state is not active immediately after a
          // previous commit/abort
          if (tx == null && tran.state != ACTIVE) {
            if (isOperation) {
              try {
                tran.beginTransaction(tran.isolationLevel);
              } catch (StandardException se) {
                throw Util.generateCsSQLException(se);
              }
              if (tran.txState == null) {
                TXManagerImpl.getOrCreateTXContext().clearTXState();
              }
            }
            else {
              TXStateInterface snapshotTXState = TXManagerImpl.getCurrentSnapshotTXState();
              if (snapshotTXState != null) {
                if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
                    || GemFireXDUtils.TraceNCJ) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
                      "GemFireTransaction#reattachTransaction: for " + tran
                          + " setting gfe tx to snapshot tx" + snapshotTXState);
                }
                //TXManagerImpl.getOrCreateTXContext().clearTXState();
                // don't mark TX as active yet
                return;
              } else {
                if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
                    || GemFireXDUtils.TraceNCJ) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
                      "GemFireTransaction#reattachTransaction: for " + tran
                          + " setting gfe tx to null");
                }
                TXManagerImpl.getOrCreateTXContext().clearTXState();
                // don't mark TX as active yet
                return;
              }
            }
          }
          else if (tx != TXStateProxy.TX_NOT_SET
              && tx != (context = TXManagerImpl.getOrCreateTXContext())
                  .getTXState()) {
            if (tx != null) {
              context.setTXState(tx);
            }
            else {
              context.clearTXState();
            }
          }
          tran.setActiveState();
        }
        return;
      }
    }
    final TXManagerImpl.TXContext context = TXManagerImpl
        .getOrCreateTXContext();
    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction#reattachTransaction: clearing GFE transaction "
              + "with current thread-local GFE TX " + context.getTXState());
    }
    context.clearTXState();
  }

  public static final void switchContextManager(final ContextManager nextCM) {
    final LanguageConnectionContext lcc = (LanguageConnectionContext)nextCM
        .getContext(LanguageConnectionContext.CONTEXT_ID);
    final GemFireTransaction tran;
    if (lcc != null
        && (tran = (GemFireTransaction)lcc.getTransactionExecute()) != null
        && tran.txManager != null) {
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
          || GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GemFireTransaction#switchContextManager: for " + tran
                + " attaching to GFE transaction with current thread-local "
                + "GFE TX " + TXManagerImpl.getCurrentTXState());
      }
      final TXManagerImpl.TXContext context;
      final TXStateInterface tx = tran.txState;
      // start a new transaction if state is not active immediately after a
      // previous commit/abort
      if (tx == null && tran.state != ACTIVE) {
        TXManagerImpl.getOrCreateTXContext().clearTXState();
      }
      else if (tx != TXStateProxy.TX_NOT_SET
          && tx != (context = TXManagerImpl.getOrCreateTXContext())
              .getTXState()) {
        if (tx != null) {
          context.setTXState(tx);
        }
        else {
          context.clearTXState();
        }
      }
    }
    else {
      final TXManagerImpl.TXContext context = TXManagerImpl
          .getOrCreateTXContext();
      if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GemFireTransaction#switchContextManager: clearing GFE transaction "
                + "with current thread-local GFE TX " + context.getTXState());
      }
      context.clearTXState();
    }
  }

  public void postTxCompletionOnDataStore(boolean rollback) {
    assert this.txManager != null: "unexpected null TXManagerImpl";

    if (GemFireXDUtils.TraceTran || GemFireXDUtils.TraceQuery
        || GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
          "GemFireTransaction:: postTxCompletionOnDataStore for " + toString());
    }
    this.txState = TXStateProxy.TX_NOT_SET;
    this.txProxy = null;
    this.txStateSuspended = null;
    try {
      closeControllers(rollback);
    } catch (StandardException se) {
      // log and move on
      SanityManager.DEBUG_PRINT("warning:" + GfxdConstants.TRACE_TRAN,
          "unexpected exception in closing controllers", se);
    }
    setIdleState();
  }

  public long addAndLoadStreamContainer(final long segmentId,
      final Properties tableProperties, final RowSource rowSource,
      final ByteBuffer rwBuffer) throws StandardException {

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(
          segmentId == StreamContainerHandle.TEMPORARY_SEGMENT,
          "GemFireTransaction: Must be only used for temporary file creations");
    }

    final long conglomId = getNextTempConglomId();

    if (this.tempCongloms == null) {
      this.tempCongloms = new TLongObjectHashMap();
    }
    this.tempCongloms.put(conglomId, new FileStreamInputOutput(conglomId, this,
        rowSource, rwBuffer));

    return conglomId;
  }

  public StreamContainerHandle openStreamContainer(long segmentId,
      long containerId, boolean hold) throws StandardException {

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(
          segmentId == StreamContainerHandle.TEMPORARY_SEGMENT,
          "GemFireTransaction: Must be only used for temporary file creations");
    }

    assert this.tempCongloms != null;

    final FileStreamInputOutput fc = (FileStreamInputOutput)this.tempCongloms
        .get(containerId);
    fc.flipToRead();
    return fc;
  }

  public void setImplicitSnapshotTxStarted(boolean implicitSnapshotTxStarted) {
    this.implicitSnapshotTxStarted = implicitSnapshotTxStarted;
  }

  public boolean getImplcitSnapshotTxStarted() {
    return this.implicitSnapshotTxStarted;
  }
  /**
   * Extension to {@link GfxdLocalLockService.DistributedLockOwner} that uses
   * the current ID of the {@link GemFireTransaction} instead of thread ID.
   * 
   * @author swale
   * @since 7.0
   */
  public static final class DistributedTXLockOwner extends
      GfxdLocalLockService.DistributedLockOwner implements LockOwner {

    /** for deserialization */
    public DistributedTXLockOwner() {
    }

    DistributedTXLockOwner(final long txId) {
      super(Misc.getGemFireCache().getMyId(), txId);
    }

    @Override
    public String toString() {
      return "DistributedTXLockOwner(member=" + getOwnerMember() + ",XID="
          + getOwnerThreadId() + ",ownerThread=" + getOwnerThreadName()
          + ",vmCreatorThread=" + getVMCreatorThread() + ')';
    }

    /**
     * @see GfxdDataSerializable#getGfxdID()
     */
    @Override
    public byte getGfxdID() {
      return DISTRIBUTED_TX_LOCK_OWNER;
    }

    /**
     * @see LockOwner#noWait()
     */
    @Override
    public boolean noWait() {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public void xa_commit(boolean onePhase) throws StandardException {
    //boolean successInSecondPhase = false;
    final TXStateInterface tx = getTopTXState();
    if (tx == null || !tx.isCoordinator()) {
      return;
    }
    try {
      if (onePhase) {
        commit(COMMIT_SYNC, tx, TXManagerImpl.FULL_COMMIT, null);
        // this.txState.commit(null);
      }
      else {
        // wait for second-phase commit to also complete for 2-phase commit
        final TXManagerImpl.TXContext context = TXManagerImpl
            .currentTXContext();
        if (context != null) {
          context.setWaitForPhase2Commit();
        }
        commit(COMMIT_SYNC, tx, TXManagerImpl.PHASE_TWO_COMMIT,
            this.txContextFromPrepareStage);
        //this.txState.commitPhase2(this.txContextFromPrepareStage, null);
        //successInSecondPhase = true;
      }
    } finally {
      this.prepared = false;
      this.txContextFromPrepareStage = null;
    }
  }

  @Override
  public int xa_prepare() throws StandardException {
    final TXStateInterface tx = getTopTXState();
    if (tx != null && tx.isCoordinator()) {
      if (tx.getProxy().isDirty()) {
        commit(COMMIT_SYNC, tx, TXManagerImpl.PHASE_ONE_COMMIT, null);
        this.prepared = true;
        return (Transaction.XA_OK);
      }
      else {
        return (Transaction.XA_RDONLY);
      }
    }
    return (Transaction.XA_RDONLY);
  }

  public final boolean isPrepared() {
    return this.prepared;
  }

  @Override
  public void xa_rollback() throws StandardException {
    try {
      abort();
    } finally {
      this.txContextFromPrepareStage = null;
      this.prepared = false;
    }
  }

  public Object createXATransactionFromLocalTransaction(int formatId,
      byte[] globalId, byte[] branchId) throws StandardException {
    GlobalXactId gid = new GlobalXactId(formatId, globalId, branchId);

    TransactionTable ttab = Misc.getMemStore().getTransactionTable();
    if (ttab.findTransactionContextByGlobalId(gid) != null) {
      throw StandardException.newException(SQLState.STORE_XA_XAER_DUPID);
    }

    this.myXactId = new XactId(this.myId);
    // TODO: TX: myId can be XactId ... see what best can be done
    // for integrating the requirement for TransactionId and long type myid
    // Internally XactId has a long id only.
    setTransactionId(gid, this.myXactId);

    // TODO: TX: look into myglobalid
    //if (SanityManager.DEBUG)
      //SanityManager.ASSERT(myGlobalId != null);
    return null;
  }

  public FileResource getFileHandler() {
    return Misc.getMemStoreBooting().getJarFileHandler();
  }

  public boolean isPristine() {
    return this.state == ACTIVE || this.state == IDLE;
  }
  
  public static long getNextTempConglomId() {
    return nextTempConglomId.getAndDecrement();
  }
  
  public void setRegionRecoveryLock(RecoveryLock lock) {
    this.regionRecoveryLock = lock;
  }
  
  public void dropStreamContainer(long segmentId, long containerId)
      throws StandardException {
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(
          segmentId == StreamContainerHandle.TEMPORARY_SEGMENT,
          "GemFireTransaction: Must be only used for temporary file creations");
    }

    final FileStreamInputOutput container = (FileStreamInputOutput)this.tempCongloms
        .remove(containerId);

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(container != null,
          "GemFireTransaction: temporary sorted conglomerate must have been present "
              + containerId);
    }

    container.close();
  }

  @Override
  public boolean optimizedForOffHeap() {
    return false;
  }

  public void setExecutionSeqInTXState(int execSeq) {
    if (this.txState != null) {
      this.txState.setExecutionSequence(execSeq);
    }
  }

  public void rollbackToPreviousState(int jdbcIsolationlevel, int savePoint,
      long memberId, int uniqueId) throws SQLException {
    // System.out.println("KN: good implicit save point received. Going to rollback");
    assert this.txManager != null
        : "Cache transaction manager expected to be non null";
    assert this.txState == null
        : "a non-null txstate not expected on the new failover server";

    jdbcIsolationlevel = getActualIsolationLevel(jdbcIsolationlevel);
    try {
      IsolationLevel level = GemFireXDUtils.getIsolationLevel(
          jdbcIsolationlevel, null);
      TXId txId = TXId.valueOf(memberId, uniqueId);
      beginTransaction(level, txId);
      assert this.txProxy.getTransactionId().equals(txId);
      this.txProxy.rollback(savePoint);
    } catch (StandardException se) {
      throw Util.generateCsSQLException(se);
    }
  }

  private int getActualIsolationLevel(int isolationlevel) {
    if (isolationlevel > EmbedConnection.TRANSACTION_NONE) {
      return isolationlevel;
    }
    return EmbedConnection.TRANSACTION_READ_COMMITTED;
  }

  public Checkpoint masqueradeAsTxn(TXId txid, int isolationLevel)
      throws SQLException {
    if (SanityManager.TraceClientHA || SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "GFT will masquerade as txid: " + txid + " isolation level = "
              + isolationLevel);
    }
    final GfxdTXStateProxy txp = this.txProxy;
    if (txp != null) {
      if (txp.getTransactionId().equals(txid)) {
        // TODO: KN log a warning and then return or throw an error???
        return this.txProxy.getACheckPoint();
      }
    }
    try {
      isolationLevel = getActualIsolationLevel(isolationLevel);
      IsolationLevel level = GemFireXDUtils.getIsolationLevel(isolationLevel,
          null);
      beginTransaction(level, txid);
      return this.txProxy.getACheckPoint();
    } catch (StandardException se) {
      throw Util.generateCsSQLException(se);
    }
  }

  public void addAffectedRegion(Bucket b) {
    if (SanityManager.TraceClientHA || SanityManager.TraceSingleHop) {
      SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
          "addAffected region adding region = " + b.getName()
              + " in txproxy = " + this.txProxy);
    }
    if (this.txProxy != null) {
      this.txProxy.addAffectedRegion(b);
    }
    else {
      // KN: TODO throw exception
      if (SanityManager.TraceClientHA || SanityManager.TraceSingleHop) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "unexpected state txproxy not found");
      }
    }
  }

  public TXId getNewTXId(boolean saveNewTxId) {
    if (this.txManager != null) {
      TXId txid = this.txManager.getNewTXId();
      if (saveNewTxId) {
        this.nextTxID = txid;
      }
      return txid;
    }
    return null;
  }
  // ------------------------ Methods below are not yet used in GemFireXD

  public boolean anyoneBlocked() {
    throw new UnsupportedOperationException();
  }

  public void addPostCommitWork(Serviceable work) {
    throw new UnsupportedOperationException();
  }

  public void setNoLockWait(boolean noWait) {
  }

  @Override
  public void addUpdateTransaction(int transactionStatus) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean blockBackup(boolean wait) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void checkpointInRollForwardRecovery(LogInstant cinstant,
      long redoLWM, long undoLWM) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public DataFactory getDataFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public LogInstant getFirstLogInstant() {
    throw new UnsupportedOperationException();
  }

  @Override
  public GlobalTransactionId getGlobalId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public TransactionId getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public LogInstant getLastLogInstant() {
    throw new UnsupportedOperationException();
  }

  @Override
  public LockFactory getLockFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public DynamicByteArrayOutputStream getLogBuffer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public LogFactory getLogFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean handlesPostTerminationWork() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean inAbort() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean inRollForwardRecovery() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBlockingBackup() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void logAndUndo(Compensation compensation, LogInstant undoInstant,
      LimitObjectInput in) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public RawContainerHandle openDroppedContainer(ContainerKey containerId,
      LockingPolicy locking) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void prepareTransaction() {
    throw new UnsupportedOperationException();
  }

  public long addContainer(long segmentId, long containerId, int mode,
      Properties tableProperties, int temporaryFlag) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reCreateContainerForRedoRecovery(long segmentId,
      long containerId, ByteArray containerInfo) throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void recoveryTransaction() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeUpdateTransaction() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reprepare() throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setFirstLogInstant(LogInstant instant) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setLastLogInstant(LogInstant instant) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setTransactionId(GlobalTransactionId extid, TransactionId localid) {
    /*
    if (SanityManager.DEBUG) {

      // SanityManager.ASSERT(myGlobalId == null, "my globalId is not null");
      if (!(state == IDLE || state == GemFireTransaction.ACTIVE || (state == CLOSED && justCreated))) {
        SanityManager.THROWASSERT("my state is not idle nor active " + state);
      }
    }

    myGlobalId = extid;
    myXactId = localid;

    if (SanityManager.DEBUG) {
      if (SanityManager.DEBUG_ON("XATrace") && extid != null) {
        SanityManager.DEBUG("XATrace", "setting xid: " + myId + " "
            + myGlobalId + " state " + state + " " + this);

        SanityManager.showTrace(new Throwable());
        // Thread.dumpStack();
      }
    }
    */
  }

  @Override
  public void setTransactionId(Loggable beginXact, TransactionId shortId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public RawTransaction startNestedTopTransaction() throws StandardException {
    throw new UnsupportedOperationException();
  }

  @Override
  protected int statusForBeginXactLog() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected int statusForEndXactLog() {
    throw new UnsupportedOperationException();
  }

  public void addPostTerminationWork(Serviceable work) {
    throw new UnsupportedOperationException();
  }

  public void close() throws StandardException {
    throw new UnsupportedOperationException();
  }

  public LockingPolicy getDefaultLockingPolicy() {
    throw new UnsupportedOperationException();
  }

  public LockingPolicy newLockingPolicy(int mode, int isolation,
      boolean stricterOk) {
    throw new UnsupportedOperationException();
  }

  public int rollbackToSavePoint(String name, Object kindOfSavepoint)
      throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void setDefaultLockingPolicy(LockingPolicy policy) {
    throw new UnsupportedOperationException();
  }

  public void setup(PersistentSet set) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public int releaseSavePoint(String name, Object kindOfSavepoint)
      throws StandardException {
    return 0;
  }

  public int rollbackToSavePoint(String name, boolean closeControllers,
      Object kindOfSavepoint) throws StandardException {
    return 0;
  }

  public int setSavePoint(String name, Object kindOfSavepoint)
      throws StandardException {
    return 0;
  }
  
  public void registerOffHeapResourceHolder(OffHeapResourceHolder owner) {    
    this.owners.add(owner);
  }

  @Override
  public void addByteSource(@Retained OffHeapByteSource byteSource) {
    if (byteSource != null) {
      this.ownerlessAddressesToFree.put(byteSource.getMemoryAddress());
    }/*else {
      //TODO:ASIF: Identify a cleaner way to avoid these blanks
     // this.ownerlessBytesSourceToFree.put(0);
    }*/
  }

  @Override
  public void registerWithGemFireTransaction(OffHeapResourceHolder owner) {
    throw new UnsupportedOperationException("This should not be invoked on GemFireTransaction");
     
  }

  @Override
  public void releaseByteSource(int position) {
    throw new UnsupportedOperationException("This should not be invoked on GemFireTransaction");
   }
  // ------------------------ End unsupported methods in GemFireXD

  /**
   * {@inheritDoc}
   */
}
