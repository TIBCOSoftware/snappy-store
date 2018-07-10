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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.impl.sql.catalog;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.pivotal.gemfirexd.callbacks.EventErrorFileToDBWriter;
import com.pivotal.gemfirexd.callbacks.impl.GatewayEventImpl;
import com.pivotal.gemfirexd.internal.catalog.AliasInfo;
import com.pivotal.gemfirexd.internal.catalog.TypeDescriptor;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdJarHandler;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.IndexInfo;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.OplogIndexReader;
import com.pivotal.gemfirexd.internal.engine.UpdateVTITemplate;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.CallbackProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.WanProcedures;
import com.pivotal.gemfirexd.internal.engine.diag.DiagProcedures;
import com.pivotal.gemfirexd.internal.engine.diag.DiskStoreIDs;
import com.pivotal.gemfirexd.internal.engine.diag.HdfsProcedures;
import com.pivotal.gemfirexd.internal.engine.diag.HiveTablesVTI;
import com.pivotal.gemfirexd.internal.engine.diag.JSONProcedures;
import com.pivotal.gemfirexd.internal.engine.diag.SnappyTableStatsVTI;
import com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdShutdownAllRequest;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.DefaultGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockFactory;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.AliasDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateAsyncEventListenerNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateGatewayReceiverNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateGatewaySenderNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropAsyncEventListenerNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropDiskStoreNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropGatewayReceiverNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropGatewaySenderNode;
import com.pivotal.gemfirexd.internal.impl.sql.execute.DDLConstantAction;

/**
 * This class implements the {@link DataDictionary} interface extending
 * {@link DataDictionaryImpl} to enable using custom locking instead of derby
 * locking. More specifically this class uses {@link GfxdLockSet} instead of
 * {@link LockFactory} for {@link DataDictionary#startReading} and
 * {@link DataDictionary#startWriting} methods.
 * 
 * @author Sumedh Wale
 * @since 6.0
 */
public final class GfxdDataDictionary extends DataDictionaryImpl {

  /** Set for the names of system schemas for more efficient lookup */
  private static final HashSet<String> sysSchemaSet;

  /**
   * Holds the map from the names of builtin SYSFUN functions to details +
   * AliasDescriptor for the function. This needs to be populated on every boot
   * since it contains the AliasDescriptor which in turn will have the handle to
   * current DataDictionary object etc.
   */
  private final HashMap<String, SYSFUNEntry> sysFunMap =
      new HashMap<String, GfxdDataDictionary.SYSFUNEntry>();

  /**
   * Represents an entry in the {@link GfxdDataDictionary#sysFunMap} containing
   * the details of the function as well as its {@link AliasDescriptor}.
   */
  private final static class SYSFUNEntry {

    private final String[] details;

    private AliasDescriptor ad;

    SYSFUNEntry(String[] details) {
      this.details = details;
      this.ad = null;
    }
  }

  /**
   * Some unique object used for locking the DataDictionary globally.
   * 
   * This is required to be a distributed write lock to avoid deadlocks caused
   * by DD-Read - Container-Read order by DML and Container-Write - DD-Write
   * (when DDL is remoted) order by DDL. In this case DDLs will get synchronized
   * with all DML compilations.
   */
  private final DefaultGfxdLockable ddLockObject = new DefaultGfxdLockable(
      "GfxdDataDictionary", GfxdConstants.TRACE_DDLOCK);

  /**
   * SYSFUN functions -- GemFireXD extensions. Table of functions that
   * automatically appear in the SYSFUN schema. These functions are resolved to
   * directly if no schema name is given, e.g.
   * 
   * <code>
   * SELECT * FROM SYS.MEMBERS m WHERE m.ID = DSID()
   * </code>
   * 
   * Adding a function here is suitable when the function defintion can have a
   * single return type and fixed number of input parameter types.
   * 
   * Functions that need to have a return type based upon the input type(s) are
   * not supported here. Typically those are added into the parser and methods
   * added into the DataValueDescriptor interface. Examples are character based
   * functions whose return type length is based upon the passed in type, e.g.
   * passed a CHAR(10) returns a CHAR(10).
   * 
   * This simple table assumes zero or a multiple fixed parameters and RETURNS
   * NULL ON NULL INPUT. The scheme could be expanded to handle other function
   * options such as other parameters if needed.
   * 
   * [0] = FUNCTION name
   * 
   * [1] = RETURNS type
   * 
   * [2] = Java class
   * 
   * [3] = method name and signature
   * 
   * [4] = parameter type or null for no parameters.
   * 
   * [5],[6],... = any more parameters
   */
  private static final String[][] GFXD_FUNCTIONS = {
      { "GROUPS", "VARCHAR",
          "com.pivotal.gemfirexd.internal.engine.diag.DiagProcedures",
          "getServerGroups()", null },
      { "DSID", "VARCHAR",
          "com.pivotal.gemfirexd.internal.engine.diag.DiagProcedures",
          "getDistributedMemberId()", null },
      { "GROUPSINTERSECTION", "VARCHAR",
          "com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures",
          "groupsIntersection(java.lang.String,java.lang.String)", "VARCHAR",
          "VARCHAR" },
      { "GROUPSINTERSECT", "BOOLEAN",
          "com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures",
          "groupsIntersect(java.lang.String,java.lang.String)", "VARCHAR",
          "VARCHAR" },
      { "GROUPSUNION", "VARCHAR",
          "com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures",
          "groupsUnion(java.lang.String,java.lang.String)", "VARCHAR",
          "VARCHAR" },
      { "COUNT_ESTIMATE", "BIGINT",
          "com.pivotal.gemfirexd.internal.engine.diag.HdfsProcedures",
          "countEstimate(java.lang.String)", "VARCHAR"},
      { "JSON_EVALPATH", "VARCHAR",
          "com.pivotal.gemfirexd.internal.engine.diag.JSONProcedures",
          "json_evalPath(com.pivotal.gemfirexd.internal.iapi.types.JSON, " +
          "java.lang.String)", "JSON", 
          "VARCHAR"},
//      { "JSON_EVALATTRIBUTE", "BOOLEAN",
//            "com.pivotal.gemfirexd.internal.engine.diag.JSONProcedures",
//            "JSON_evalAttribute(com.pivotal.gemfirexd.internal.iapi.types.JSON, " +
//            "java.lang.String)", "JSON", 
//            "VARCHAR"},
  };

  /**
   * Dummy parameter names for functions from SYSFUN_FUNCTIONS. Not more than 4
   * parameters expected.
   */
  private static final String[][] SYSFUN_PNAMES = { null, { "P1" },
      { "P1", "P2" }, { "P1", "P2", "P3" }, { "P1", "P2", "P3", "P4" } };

  /**
   * Default paramater mode (IN) for functions from SYSFUN_FUNCTIONS.
   */
  private static final int SYSFUN_MODE = JDBC30Translation.PARAMETER_MODE_IN;

  /**
   * Parameter mode (IN as required) for functions from SYSFUN_FUNCTIONS. Not
   * more that 4 parameters expected.
   */
  private static final int[][] SYSFUN_PMODES = { null, { SYSFUN_MODE },
      { SYSFUN_MODE, SYSFUN_MODE }, { SYSFUN_MODE, SYSFUN_MODE, SYSFUN_MODE },
      { SYSFUN_MODE, SYSFUN_MODE, SYSFUN_MODE, SYSFUN_MODE } };

  /**
   * This allows a thread to skip acquiring read lock on GfxdDataDictionary
   * and/or GemFireContainer. Used at the time of checking if a table is
   * columnar or not while setting its tabletype.
   */
  public static final ThreadLocal<Boolean> SKIP_LOCKS = new ThreadLocal<Boolean>() {
    public Boolean initialValue() {
      return Boolean.FALSE;
    }
  };

  /** static block */
  static {
    // populate the set of system schemas
    sysSchemaSet = new HashSet<String>(systemSchemaNames.length * 2);
    for (String sysSchema : systemSchemaNames) {
      sysSchemaSet.add(sysSchema);
    }
  }

  @Override
  public void boot(boolean create, Properties startParams)
      throws StandardException {
    super.boot(create, startParams);

    // populate the builtin functions in SYSFUN including both derby ones and
    // GemFireXD extensions
    this.sysFunMap.clear();
    for (String[] details : SYSFUN_FUNCTIONS) {
      this.sysFunMap.put(details[0], new SYSFUNEntry(details));
    }
    for (String[] details : GFXD_FUNCTIONS) {
      this.sysFunMap.put(details[0], new SYSFUNEntry(details));
    }
  }

  /**
   * @see DataDictionary#startReading
   * 
   * @exception StandardException
   *              Thrown on error
   */
  @Override
  public int startReading(LanguageConnectionContext lcc)
      throws StandardException {
    int bindCount = lcc.incrementBindCount();
    int localCacheMode;

    // "this" is used to synchronize
    // startReading/doneReading/startWriting/transactionFinished methods
    boolean readLockDD = false;
    synchronized (this) {
      localCacheMode = getCacheMode();
      // Keep track of how deeply nested this bind() operation is.
      // It's possible for nested binding to happen if the user
      // prepares SQL statements from within a static initializer
      // of a class, and calls a method on that class (or uses a
      // field in the class).
      //
      // If nested binding is happening, we only want to lock the
      // DataDictionary on the outermost nesting level.
      if (bindCount == 1) {
        readLockDD = true;
        if (localCacheMode != DataDictionary.COMPILE_ONLY_MODE) {
          ++readersInDDLMode;
        }
      }
    } // end synchronized
    if (ddLockObject.traceLock()) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLOCK,
          "GfxdDataDictionary#startReading: with bindCount=" + bindCount
              + " localCacheMode=" + localCacheMode
              + " acquiring read lock is " + readLockDD);
    }
    if (readLockDD) {
      // get the read lock for special DD object that will block
      // distributed write locks on the DD
      final TransactionController tc = lcc.getTransactionExecute();
      if (!lockForReadingNoThrow(tc, GfxdLockSet.MAX_LOCKWAIT_VAL)) {
        lcc.decrementBindCount();
        throw Misc.getMemStore().getDDLLockService()
            .getLockTimeoutException(this, tc.getLockSpace().getOwner(), true);
      }
    }
    return localCacheMode;
  }

  /**
   * @see DataDictionary#doneReading(int, LanguageConnectionContext)
   * 
   * @exception StandardException
   *              Thrown on error
   */
  @Override
  public void doneReading(int mode, LanguageConnectionContext lcc)
      throws StandardException {
    int bindCount = lcc.decrementBindCount();

    // Keep track of how deeply nested this bind() operation is.
    // It's possible for nested binding to happen if the user
    // prepares SQL statements from within a static initializer
    // of a class, and calls a method on that class (or uses a
    // field in the class).
    //
    // If nested binding is happening, we only want to unlock the
    // DataDictionary on the outermost nesting level.
    if (bindCount == 0) {
      // release the read lock that was acquired by the reader when
      // it invoked startReading()
      unlockAfterReading(lcc.getTransactionExecute());
      if (mode != DataDictionary.COMPILE_ONLY_MODE) {
        synchronized (this) {
          --readersInDDLMode;
          // We can only switch back to cached (COMPILE_ONLY)
          // mode if there aren't any readers that started in
          // DDL_MODE. Otherwise we could get a reader
          // in DDL_MODE that reads a cached object that
          // was brought in by a reader in COMPILE_ONLY_MODE.
          // If 2nd reader finished and releases it lock
          // on the cache there is nothing to pevent another
          // writer from coming along an deleting the cached
          // object.
          if (ddlUsers == 0 && readersInDDLMode == 0) {
            clearCaches();
            setCacheMode(DataDictionary.COMPILE_ONLY_MODE);
          }

          if (SanityManager.DEBUG) {
            SanityManager.ASSERT(readersInDDLMode >= 0,
                "readersInDDLMode is invalid -- should never be < 0");
          }
        }
      }
    }
    if (ddLockObject.traceLock()) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLOCK,
          "GfxdDataDictionary#endReading: with bindCount=" + bindCount
              + " mode=" + mode);
    }
  }

  /**
   * Take the read lock on DataDictionary (works in a distributed manner) before
   * reading. This variant throws a runtime {@link TimeoutException} rather than
   * a {@link StandardException}.
   */
  public final void lockForReadingRT(TransactionController tc)
      throws TimeoutException {
    final GemFireStore memStore = Misc.getMemStoreBooting();
    if (!memStore.initialDDLReplayDone()) {
      lockForReadingInDDLReplay(memStore);
      return;
    }
    if (!lockForReadingNoThrow(tc, GfxdLockSet.MAX_LOCKWAIT_VAL)) {
      final GfxdDRWLockService lockService = memStore.getDDLLockService();
      throw lockService.getLockTimeoutRuntimeException(this, null, true);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void lockForReading(TransactionController tc)
      throws StandardException {
    if (!lockForReadingNoThrow(tc, GfxdLockSet.MAX_LOCKWAIT_VAL)) {
      final GfxdDRWLockService lockService = Misc.getMemStoreBooting()
          .getDDLLockService();
      throw lockService.getLockTimeoutException(this, tc.getLockSpace()
          .getOwner(), true);
    }
  }

  /**
   * Take the read lock on DataDictionary (works in a distributed manner) before
   * reading.
   * 
   * @return true if the DD read lock was successfully acquired and false if the
   *         DDL read lock acquisition was unsuccessful
   */
  public final boolean lockForReadingNoThrow(TransactionController tc,
      long maxWaitMillis) {
    try {
      return SKIP_LOCKS.get() || GemFireXDUtils.lockObjectNoThrow(ddLockObject, null, false, false,
          tc, maxWaitMillis) != GfxdLockSet.LOCK_FAIL;
    } catch (StandardException se) {
      // unexpected
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception while acquire DataDictionary read lock", se);
    }
  }

  /**
   * Take the read lock on DataDictionary (works in a distributed manner) before
   * reading during DDL replay. This method sets the
   * {@link GemFireStore#setInitialDDLReplayWaiting(boolean)} flag and so
   * must only be invoked for DD locking during initial DDL replay.
   */
  public final void lockForReadingInDDLReplay(final GemFireStore memStore) {
    if (!lockForReadingInDDLReplayNoThrow(memStore, Long.MAX_VALUE/2, false)) {
      final GfxdDRWLockService lockService = memStore.getDDLLockService();
      throw lockService.getLockTimeoutRuntimeException(this, null, true);
    }
  }

  /**
   * Take the read lock on DataDictionary (works in a distributed manner) before
   * reading during DDL replay not throwing any exception. This method sets the
   * {@link GemFireStore#setInitialDDLReplayWaiting(boolean)} flag and so
   * must only be invoked for DD locking during initial DDL replay.
   */
  public final boolean lockForReadingInDDLReplayNoThrow(
      final GemFireStore store, long maxWaitMillis,
      final boolean checkForDDLReplayWaiting) {
    final long loopMillis = Math.min(500L, maxWaitMillis);
    if (lockForReadingNoThrow(null, loopMillis)) {
      return true;
    }
    else {
      // check if GfxdDDLMessage has already taken the write lock and has to
      // wait for replay to complete (e.g. a DROP/ALTER whose create has already
      // been replayed), so then DD read lock will fail and higher layer should
      // not expect it to succeed; without this handling it will result in a
      // deadlock (#47873)
      while ((maxWaitMillis -= loopMillis) > 0
          && !(checkForDDLReplayWaiting && store.initialDDLReplayWaiting())) {
        if (lockForReadingNoThrow(null, loopMillis)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Release the read lock on DataDictionary (works in a distributed manner)
   * after reading is done.
   */
  @Override
  public final boolean unlockAfterReading(TransactionController tc) {
    if (!SKIP_LOCKS.get()) {
      return GemFireXDUtils.unlockObject(ddLockObject, null, false, false, tc);
    } else {
      return false;
    }
  }

  /**
   * @see DataDictionary#startWriting(LanguageConnectionContext)
   * 
   * @exception StandardException
   *              Thrown on error
   */
  @Override
  public void startWriting(LanguageConnectionContext lcc)
      throws StandardException {
    startWriting(lcc, false);
  }

  /*
   * Checks if there are any tx changes yet to be committed or rolled back.
   * If so, throws an exception.
   * GemFireXD currently does not allow DDL statements to be executed 
   * in the middle of a transaction.
   */
  private void checkForPendingTxChanges(LanguageConnectionContext lcc)
      throws StandardException {
    ArrayList<Activation> activations = lcc.getAllActivations();
    Activation activation = null;
    if (activations != null && activations.size() > 0) {
      activation = activations.get(activations.size() - 1);
    }
    if (activation != null) {
      ConstantAction constantAction = activation.getConstantAction();
      if (constantAction != null && constantAction instanceof DDLConstantAction) {
        final GemFireTransaction parentTran = lcc
            .getParentOfNestedTransactionExecute();
        final TXStateInterface tx;
        if (parentTran != null
            && (tx = parentTran.getSuspendedTXState()) != null
            && tx.getProxy().isDirty()) {
          throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
              "Cannot execute DDL statements in the middle of transaction "
                  + "that has data changes "
                  + "(commit or rollback the transaction first) " + tx);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean startWriting(LanguageConnectionContext lcc, boolean localOnly)
      throws StandardException {

    checkForPendingTxChanges(lcc);
    // Don't allow DDL if we're binding a SQL statement.
    if (lcc.getBindCount() != 0) {
      throw StandardException.newException(SQLState.LANG_DDL_IN_BIND);
    }

    // Check whether we've already done a DDL statement in this
    // transaction. If so, we don't want to re-enter DDL mode, or
    // bump the DDL user count.
    int localCacheMode;
    boolean status;
    if (!lcc.dataDictionaryInWriteMode()) {
      // get the write lock for special DD object that will block all
      // other read and distributed write locks on the DD
      status = lockForWriting(lcc.getTransactionExecute(), localOnly);
      synchronized (this) {
        localCacheMode = getCacheMode();
        if (localCacheMode == DataDictionary.COMPILE_ONLY_MODE) {
          // Clear out all the caches
          clearCaches();
          // Switch the caching mode to DDL
          setCacheMode(DataDictionary.DDL_MODE);
        }
        // Keep track of the number of DDL users
        ++ddlUsers;
      } // end synchronized
      // Tell the connection the DD is in DDL mode, so it can take
      // it out of DDL mode when the transaction finishes.
      lcc.setDataDictionaryWriteMode();
    }
    else {
      // acquire the write lock in any case if not acquired
      // this may be the case when retrying for write locks (see #42348)
      status = lockForWriting(lcc.getTransactionExecute(), localOnly);
      if (SanityManager.DEBUG) {
        synchronized (this) {
          localCacheMode = getCacheMode();
        }
        if (localCacheMode != DataDictionary.DDL_MODE) {
          SanityManager.THROWASSERT("lcc.getDictionaryInWriteMode() "
              + "but DataDictionary is in COMPILE_MODE");
        }
      }
    }
    return status;
  }

  /**
   * @see DataDictionary#transactionFinished
   * 
   * @exception StandardException
   *              Thrown on error
   */
  @Override
  public void transactionFinished() throws StandardException {
    synchronized (this) {
      if (SanityManager.DEBUG) {
        SanityManager.ASSERT(ddlUsers > 0,
            "Number of DDL Users is <= 0 when finishing a transaction");

        SanityManager.ASSERT(getCacheMode() == DataDictionary.DDL_MODE,
            "transactionFinished called when not in DDL_MODE");
      }
      --ddlUsers;

      // We can only switch back to cached (COMPILE_ONLY) mode if there aren't
      // any readers that started in DDL_MODE. Otherwise we could get a reader
      // in DDL_MODE that reads a cached object that was brought in by a reader
      // in COMPILE_ONLY_MODE. If 2nd reader finished and releases it lock on
      // the cache there is nothing to prevent another writer from coming along
      // an deleting the cached object.
      if (ddlUsers == 0 && readersInDDLMode == 0) {
        clearCaches();
        setCacheMode(DataDictionary.COMPILE_ONLY_MODE);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean lockForWriting(TransactionController tc,
      boolean localOnly) throws StandardException {
    return SKIP_LOCKS.get() || GemFireXDUtils.lockObject(ddLockObject, null, true, localOnly, tc,
        GfxdLockSet.MAX_LOCKWAIT_VAL);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final GfxdLockSet.LockType getLockType(TransactionController tc) {
    if (tc != null) {
      return ((GemFireTransaction)tc).getLockSpace().getLockType(
          this.ddLockObject);
    }
    else {
      return null;
    }
  }

  /**
   * Get the {@link GfxdLockable} object used for locking of the DataDictionary.
   */
  public final GfxdLockable getLockObject() {
    return this.ddLockObject;
  }

  /**
   * Release the write lock on DataDictionary (works in a distributed manner)
   * after writing is done.
   */
  @Override
  public final void unlockAfterWriting(TransactionController tc,
      boolean localOnly) {
    if (!SKIP_LOCKS.get()) {
      GemFireXDUtils.unlockObject(ddLockObject, null, true, localOnly, tc);
    }
  }

  /**
   * Get the list of routines matching the schema and routine name. While we
   * only support a single alias for a given name,namespace just return a list
   * of zero or one item. If the schema is SYSFUN then do not use the system
   * catalogs, but instead look up the routines from the in-meomry table driven
   * by the contents of SYSFUN_FUNCTIONS.
   */
  @Override
  public ArrayList<Object> getRoutineList(String schemaID, String routineName,
      char nameSpace) throws StandardException {

    final ArrayList<Object> list = new ArrayList<Object>();
    // Special in-memory table lookup for SYSFUN
    if (SchemaDescriptor.SYSFUN_SCHEMA_UUID.equals(schemaID)
        && nameSpace == AliasInfo.ALIAS_NAME_SPACE_FUNCTION_AS_CHAR) {
      final SYSFUNEntry entry = sysFunMap.get(routineName);
      if (entry != null) {
        final String[] details = entry.details;
        if (entry.ad == null) {
          // details[1] Return type
          TypeDescriptor rt = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
              details[1]).getCatalogType();

          // details[4], details[5],... - zero or more argument types
          // details[4] can be null for zero arguments
          final int paramCount;
          final String[] paramNames;
          final int[] paramModes;
          final TypeDescriptor[] pt;
          String paramType = details[4];
          if (paramType != null) {
            paramCount = details.length - 4;
            paramNames = SYSFUN_PNAMES[paramCount];
            paramModes = SYSFUN_PMODES[paramCount];
            pt = new TypeDescriptor[paramCount];
            pt[0] = DataTypeDescriptor.getBuiltInDataTypeDescriptor(paramType)
                .getCatalogType();
            for (int index = 5; index < details.length; ++index) {
              paramType = details[index];
              pt[index - 4] = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                  paramType).getCatalogType();
            }
          }
          else {
            paramCount = 0;
            paramNames = null;
            paramModes = null;
            pt = null;
          }

          // details[3] = java method
          final RoutineAliasInfo ai = new RoutineAliasInfo(details[3],
              paramCount, paramNames, pt, paramModes, 0,
              RoutineAliasInfo.PS_JAVA, RoutineAliasInfo.NO_SQL, false, rt);

          // details[2] = class name
          entry.ad = new AliasDescriptor(this, uuidFactory.createUUID(),
              routineName, uuidFactory.recreateUUID(schemaID), details[2],
              AliasInfo.ALIAS_TYPE_FUNCTION_AS_CHAR,
              AliasInfo.ALIAS_NAME_SPACE_FUNCTION_AS_CHAR, true, ai, null);
        }
        list.add(entry.ad);
      }
      return list;
    }
    final AliasDescriptor ad = getAliasDescriptor(schemaID, routineName,
        nameSpace);
    if (ad != null) {
      list.add(ad);
    }
    return list;
  }

  /**
   * Determine whether a string is the name of the system schema.
   * 
   * @return true or false
   * 
   * @exception StandardException
   *              Thrown on failure
   */
  @Override
  public boolean isSystemSchemaName(String name) throws StandardException {
    return (name != null && sysSchemaSet.contains(name));
  }

  /**
   * Determine whether a string is the name of the system schema.
   */
  public static boolean isSystemSchema(String name) {
    return (name != null && sysSchemaSet.contains(name));
  }

  @Override
  protected final void debugGenerateInfo(StringBuilder strbuf,
      TransactionController tc, ConglomerateController heapCC, TabInfoImpl ti,
      int indexId) {
    SanityManager.DEBUG_PRINT("DataDictionary DEBUG", strbuf.toString());
    // also dump all GfxdDRWLockService locks
    GfxdDRWLockService ddlService = Misc.getMemStore().getDDLLockService();
    ddlService.dumpAllRWLocks("LOCK TABLE at the time of failure",
        true, false, true);
  }

  /**
   * see {@link Object#toString()}
   */
  @Override
  public String toString() {
    return ddLockObject.toString();
  }

  // Asif: Begin Code for Gfxd sys procedures for Gateways
  private final static String PROC_CLASSNAME =
    "com.pivotal.gemfirexd.internal.engine.ddl.wan.WanProcedures";

  private final static String CALLBACK_CLASSNAME =
    "com.pivotal.gemfirexd.internal.engine.ddl.callbacks.CallbackProcedures";

  private final static String DIAG_CLASSNAME =
    "com.pivotal.gemfirexd.internal.engine.diag.DiagProcedures";

  private final static String GFXD_SYS_PROC_CLASSNAME =
    "com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures";

  private final static String HDFS_PROC_CLASSNAME = 
    "com.pivotal.gemfirexd.internal.engine.diag.HdfsProcedures";

  @SuppressWarnings("rawtypes")
  @Override
  void createGfxdSchemaAndInbuiltProcedures(TransactionController tc,
      HashSet newlyCreatedRoutines) throws StandardException {
    CreateGatewaySenderNode.dummy();
    CreateGatewayReceiverNode.dummy();
    CreateAsyncEventListenerNode.dummy();
    DropGatewaySenderNode.dummy();
    DropAsyncEventListenerNode.dummy();
    DropGatewayReceiverNode.dummy();
    DropDiskStoreNode.dummy();
    WanProcedures.dummy();
    CallbackProcedures.dummy();
    DiagProcedures.dummy();
    HdfsProcedures.dummy();
    SortedCSVProcedures.dummy();
    GfxdJarHandler.dummy();
    IndexInfo.dummy();
    JSONProcedures.dummy();
    GfxdShutdownAllRequest.dummy();
    GatewayEventImpl.dummy();
    EventErrorFileToDBWriter.dummy();
    OplogIndexReader.init();
    this.createGfxdDiagTables(tc);
    this.createStartAsyncQueueProcedure(tc, newlyCreatedRoutines);
    this.createStopAsyncQueueProcedure(tc, newlyCreatedRoutines);
    this.createStartGatewaySenderProcedure(tc, newlyCreatedRoutines);
    this.createStopGatewaySendereProcedure(tc, newlyCreatedRoutines);
    this.createAddListenerProcedure(tc, newlyCreatedRoutines);
    this.createAddWriterProcedure(tc, newlyCreatedRoutines);
    this.createRemoveListenerProcedure(tc, newlyCreatedRoutines);
    this.createRemoveWriterProcedure(tc, newlyCreatedRoutines);
    this.createAddLoaderProcedure(tc, newlyCreatedRoutines);
    this.createRemoveLoaderProcedure(tc, newlyCreatedRoutines);
    this.createSetQueryStatsProcedure(tc, newlyCreatedRoutines);
    this.createAddGatewayConflictResolverProcedure(tc, newlyCreatedRoutines);
    this.createRemoveGatewayConflictResolverProcedure(tc, newlyCreatedRoutines);
    this.createAddGatewayEventErrorHandlerProcedure(tc, newlyCreatedRoutines);
    this.createRemoveGatewayEventErrorHandlerProcedure(tc, newlyCreatedRoutines);
    this.createHDFSProcedures(tc, newlyCreatedRoutines);
    this.createGfxdSystemProcedures(tc, newlyCreatedRoutines);
  }

  @SuppressWarnings("unchecked")
  private void createGfxdDiagTables(TransactionController tc)
      throws StandardException {
    final SchemaDescriptor sysSchema = getSystemSchemaDescriptor();
    // populate the Name and TableDescriptor maps
    for (String[] vtiEntry : VTI_TABLE_CLASSES) {
      String tableName = vtiEntry[0];
      String className = vtiEntry[1];
      this.diagVTINames.put(tableName, className);
      final TableDescriptor td = new TableDescriptor(this, tableName,
          sysSchema, TableDescriptor.VTI_TYPE,
          TableDescriptor.DEFAULT_LOCK_GRANULARITY);
      td.setUUID(getUUIDFactory().createUUID());
      long conglomId = Misc.getMemStore().getNextConglomId();
      ConglomerateDescriptor cd = getDataDescriptorGenerator()
          .newConglomerateDescriptor(conglomId, null, false, null, false, null,
              td.getUUID(), sysSchema.getUUID());
      td.getConglomerateDescriptorList().add(cd);

      // set the column descriptors to allow for updates
      try {
        Object obj = Class.forName(className).newInstance();
        if (obj instanceof UpdateVTITemplate) {
          ((UpdateVTITemplate)obj).setColumnDescriptorList(td);
        }
        td.setRouteQueryToAllNodes(
            !(obj instanceof GfxdVTITemplateNoAllNodesRoute));
      } catch (Exception ex) {
        throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND, ex,
            td.getQualifiedName());
      }
      this.diagVTIMap.put(tableName, td);
    }
  }

 
  private void createStartAsyncQueueProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID" };
    // procedure argument types
    TypeDescriptor[] arg_types = { DataTypeDescriptor.getCatalogType(
        Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName = "startAsyncQueue(java.lang.String)";
    String aliasName = "START_ASYNC_EVENT_LISTENER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, PROC_CLASSNAME, newlyCreatedRoutines, true);
  }


  private void createStopAsyncQueueProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID" };
    // procedure argument types
    TypeDescriptor[] arg_types = { DataTypeDescriptor.getCatalogType(
        Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName = "stopAsyncQueue(java.lang.String)";
    String aliasName = "STOP_ASYNC_EVENT_LISTENER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, PROC_CLASSNAME, newlyCreatedRoutines, true);
  }


  private void createStartGatewaySenderProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID" };
    // procedure argument types
    TypeDescriptor[] arg_types = { DataTypeDescriptor.getCatalogType(
        Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName = "startGatewaySender(java.lang.String)";
    String aliasName = "START_GATEWAYSENDER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, PROC_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createStopGatewaySendereProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID" };
    // procedure argument types
    TypeDescriptor[] arg_types = { DataTypeDescriptor.getCatalogType(
        Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName = "stopGatewaySender(java.lang.String)";
    String aliasName = "STOP_GATEWAYSENDER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, PROC_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createAddListenerProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID", "SCHEMA_NAME", "TABLE_NAME", "FUNCTION_STR",
        "INIT_INFO_STR", "SERVER_GROUPS" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH), //id
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH), //schema_name
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH), //table_name
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH), //function implementation
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),//init info string
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH) };
    String methodName = "addGfxdCacheListener(java.lang.String,java.lang.String,"
        + "java.lang.String,java.lang.String,java.lang.String,java.lang.String)";
    String aliasName = "ADD_LISTENER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createAddWriterProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "SCHEMA_NAME", "TABLE_NAME", "FUNCTION_STR",
        "INIT_INFO_STR", "SERVER_GROUPS" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH) };
    String methodName = "addGfxdCacheWriter(java.lang.String,"
        + "java.lang.String,java.lang.String,java.lang.String,"
        + "java.lang.String)";
    String aliasName = "ATTACH_WRITER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createRemoveListenerProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ID", "SCHEMA_NAME", "TABLE_NAME" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName = "removeGfxdCacheListener(java.lang.String,"
        + "java.lang.String,java.lang.String)";
    String aliasName = "REMOVE_LISTENER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createRemoveWriterProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "SCHEMA_NAME", "TABLE_NAME" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName =
      "removeGfxdCacheWriter(java.lang.String,java.lang.String)";
    String aliasName = "REMOVE_WRITER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createAddLoaderProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "SCHEMA_NAME", "TABLE_NAME", "FUNCTION_STR",
        "INIT_INFO_STR" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH) };
    String methodName = "addGfxdCacheLoader(java.lang.String, "
        + "java.lang.String, java.lang.String, java.lang.String)";
    String aliasName = "ATTACH_LOADER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createAddGatewayConflictResolverProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "FUNCTION_STR", "INIT_INFO_STR" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH) };
    String methodName = "addGfxdGatewayConflictResolver(java.lang.String, java.lang.String)";
    String aliasName = "ATTACH_GATEWAY_CONFLICT_RESOLVER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }  

  private void createAddGatewayEventErrorHandlerProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "FUNCTION_STR", "INIT_INFO_STR" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.DB2_VARCHAR_MAXWIDTH) };
    String methodName = "addGfxdGatewayEventErrorHandler(java.lang.String, java.lang.String)";
    String aliasName = "ATTACH_GATEWAY_EVENT_ERROR_HANDLER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }
  
  private void createRemoveGatewayConflictResolverProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = {};
    // procedure argument types
    TypeDescriptor[] arg_types = {};
    String methodName = "removeGfxdGatewayConflictResolver()";
    String aliasName = "REMOVE_GATEWAY_CONFLICT_RESOLVER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }  
  
  private void createRemoveGatewayEventErrorHandlerProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = {};
    // procedure argument types
    TypeDescriptor[] arg_types = {};
    String methodName = "removeGfxdGatewayEventErrorHandler()";
    String aliasName = "REMOVE_GATEWAY_EVENT_ERROR_HANDLER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }  
  
  private void createRemoveLoaderProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "SCHEMA_NAME", "TABLE_NAME" };
    // procedure argument types
    TypeDescriptor[] arg_types = {
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH),
        DataTypeDescriptor.getCatalogType(Types.VARCHAR,
            Limits.MAX_IDENTIFIER_LENGTH) };
    String methodName =
      "removeGfxdCacheLoader(java.lang.String, java.lang.String)";
    String aliasName = "REMOVE_LOADER";
    this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, CALLBACK_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createSetQueryStatsProcedure(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {
    // procedure argument names
    String[] arg_names = { "ENABLE" };
    // procedure argument types
    TypeDescriptor[] arg_types = { DataTypeDescriptor
        .getCatalogType(Types.BOOLEAN) };
    String methodName = "setQueryStats(java.lang.Boolean)";
    String aliasName = "SET_QUERYSTATS";
    createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
        arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null, tc,
        aliasName, DIAG_CLASSNAME, newlyCreatedRoutines, true);
  }

  private void createHDFSProcedures(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {

    {
      // void HDFS_FORCE_COMPACTION(String fullyQualifiedTableName, int maxWaitTime)
      String[] argNames = new String[] { "FQTN", "MAX_WAIT_TIME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      String methodName = "forceCompaction(java.lang.String, java.lang.Integer)";
      String aliasName = "HDFS_FORCE_COMPACTION";
      this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null, tc, aliasName,
          HDFS_PROC_CLASSNAME, newlyCreatedRoutines, true);
    }
    {
      // void HDFS_FLUSH_QUEUE(String fullyQualifiedTableName, int maxWaitTime)
      String[] argNames = new String[] { "FQTN", "MAX_WAIT_TIME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      String methodName = "flushQueue(java.lang.String, java.lang.Integer)";
      String aliasName = "HDFS_FLUSH_QUEUE";
      this.createGfxdProcedure(methodName, getSystemSchemaDescriptor().getUUID(),
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null, tc, aliasName,
          HDFS_PROC_CLASSNAME, newlyCreatedRoutines, true);
    }

    {
      // Date HDFS_LAST_MAJOR_COMPACTION(String fullyQualifiedTableName)
      String[] argNames = new String[] { "FQTN" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] { 
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
      String aliasName = "HDFS_LAST_MAJOR_COMPACTION";
      TypeDescriptor returnType = DataTypeDescriptor.getCatalogType(Types.TIMESTAMP);
      super.createSystemProcedureOrFunction(aliasName,
          getSystemSchemaDescriptor().getUUID(), argNames, argTypes, 0, 0,
          RoutineAliasInfo.CONTAINS_SQL, returnType, newlyCreatedRoutines, tc,
          HDFS_PROC_CLASSNAME, false);
    }
    
    {
      // HDFS_FORCE_WRITEONLY_FILEROLLOVER (String fullyQualifiedTableName, Integer waitTime) procedure 
      String[] argNames = new String[] {  "FQTN", "MIN_SIZE_FOR_ROLLOVER" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      
      super.createSystemProcedureOrFunction("HDFS_FORCE_WRITEONLY_FILEROLLOVER",
          getSystemSchemaDescriptor().getUUID(), argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, HDFS_PROC_CLASSNAME, false);
    }


  }

  /**
   * Any changes to the procedure name/arg would mean typically changing
   * {@link GfxdSystemProcedures} and
   * <code></code>GfxdSystemProcedureMessage</code> classes.
   */
  private void createGfxdSystemProcedures(TransactionController tc,
      HashSet<?> newlyCreatedRoutines) throws StandardException {

    UUID sysUUID = getSystemSchemaDescriptor().getUUID();
    UUID sqlJUUID = getSchemaDescriptor(SchemaDescriptor.STD_SQLJ_SCHEMA_NAME,
        tc, true).getUUID();

    {
      // void SYS.CREATE_USER(
      // varchar(128), varchar(Limits.DB2_VARCHAR_MAXWIDTH))

      // procedure argument names
      String[] arg_names = { "USER_ID", "PASSWORD" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          CATALOG_TYPE_SYSTEM_IDENTIFIER,
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      super.createSystemProcedureOrFunction("CREATE_USER", sysUUID, arg_names,
          arg_types, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA,
          (TypeDescriptor)null, newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void SYS.CHANGE_PASSWORD(
      // varchar(128), varchar(Limits.DB2_VARCHAR_MAXWIDTH), varchar(Limits.DB2_VARCHAR_MAXWIDTH))

      // procedure argument names
      String[] arg_names = { "USER_ID", "OLDPASSWORD", "NEWPASSWORD" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          CATALOG_TYPE_SYSTEM_IDENTIFIER,
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      UUID routineUUID = super.createSystemProcedureOrFunction(
          "CHANGE_PASSWORD", sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
      // we want to bypass normal authorization for CHANGE_PASSWORD and instead
      // do it explicitly in the procedure body itself (#48372)
      addBypassRoutine(routineUUID);
    }
    
    {
      // void SYS.DROP_USER(varchar(128))

      // procedure argument names
      String[] arg_names = { "USER_ID" };

      // procedure argument types
      TypeDescriptor[] arg_types = { CATALOG_TYPE_SYSTEM_IDENTIFIER };

      super.createSystemProcedureOrFunction("DROP_USER", sysUUID, arg_names,
          arg_types, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA,
          (TypeDescriptor)null, newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // out ResultSet SHOW_USERS()
      super.createSystemProcedureOrFunction("SHOW_USERS", sysUUID, null, null,
          0, 1, RoutineAliasInfo.NO_SQL, null, newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // CHECK_TABLE_EX

      // procedure argument names
      String[] arg_names = { "SCHEMA", "TABLE" };

      // procedure argument types
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR) };

      super.createSystemProcedureOrFunction("CHECK_TABLE_EX", sysUUID, arg_names,
          argTypes, 0, 0, RoutineAliasInfo.NO_SQL,
          DataTypeDescriptor.getCatalogType(Types.INTEGER), newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // void SET_CRITICAL_HEAP_PERCENTAGE(real)

      // procedure argument names
      String[] arg_names = { "HEAP_PERCENTAGE" };

      // procedure argument types
      TypeDescriptor[] arg_types = { DataTypeDescriptor
          .getCatalogType(Types.REAL) };

      super.createSystemProcedureOrFunction("SET_CRITICAL_HEAP_PERCENTAGE",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // procedure argument names
      String[] arg_names = { "OFFHEAP_PERCENTAGE" };

      // procedure argument types
      TypeDescriptor[] arg_types = { DataTypeDescriptor
          .getCatalogType(Types.REAL) };

      super.createSystemProcedureOrFunction("SET_CRITICAL_OFFHEAP_PERCENTAGE",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
      // void SET_CRITICAL_HEAP_PERCENTAGE(real, varchar(128))

      // procedure argument names
      String[] arg_names = { "HEAP_PERCENTAGE", "SERVER_GROUPS" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          DataTypeDescriptor.getCatalogType(Types.REAL),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      super.createSystemProcedureOrFunction("SET_CRITICAL_HEAP_PERCENTAGE_SG",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
      // procedure argument names
      String[] arg_names = { "OFFHEAP_PERCENTAGE", "SERVER_GROUPS" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          DataTypeDescriptor.getCatalogType(Types.REAL),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      super.createSystemProcedureOrFunction("SET_CRITICAL_OFFHEAP_PERCENTAGE_SG",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // float GET_CRITICAL_HEAP_PERCENTAGE()

      super.createSystemProcedureOrFunction("GET_CRITICAL_HEAP_PERCENTAGE",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.REAL), newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }
    
    {
      super.createSystemProcedureOrFunction("GET_CRITICAL_OFFHEAP_PERCENTAGE",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.REAL), newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // void SET_EVICTION_HEAP_PERCENTAGE(real)

      // procedure argument names
      String[] arg_names = { "HEAP_PERCENTAGE" };

      // procedure argument types
      TypeDescriptor[] arg_types = { DataTypeDescriptor
          .getCatalogType(Types.REAL) };

      super.createSystemProcedureOrFunction("SET_EVICTION_HEAP_PERCENTAGE",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
      // procedure argument names
      String[] arg_names = { "OFFHEAP_PERCENTAGE" };

      // procedure argument types
      TypeDescriptor[] arg_types = { DataTypeDescriptor
          .getCatalogType(Types.REAL) };

      super.createSystemProcedureOrFunction("SET_EVICTION_OFFHEAP_PERCENTAGE",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void SET_EVICTION_HEAP_PERCENTAGE(real, varchar(128))

      // procedure argument names
      String[] arg_names = { "HEAP_PERCENTAGE", "SERVER_GROUPS" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          DataTypeDescriptor.getCatalogType(Types.REAL),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      super.createSystemProcedureOrFunction("SET_EVICTION_HEAP_PERCENTAGE_SG",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // procedure argument names
      String[] arg_names = { "OFFHEAP_PERCENTAGE", "SERVER_GROUPS" };

      // procedure argument types
      TypeDescriptor[] arg_types = {
          DataTypeDescriptor.getCatalogType(Types.REAL),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.DB2_VARCHAR_MAXWIDTH) };

      super.createSystemProcedureOrFunction("SET_EVICTION_OFFHEAP_PERCENTAGE_SG",
          sysUUID, arg_names, arg_types, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, (TypeDescriptor)null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
      // float GET_EVICTION_HEAP_PERCENTAGE()

      super.createSystemProcedureOrFunction("GET_EVICTION_HEAP_PERCENTAGE",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.REAL), newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, false);
    }
    
    {
      super.createSystemProcedureOrFunction("GET_EVICTION_OFFHEAP_PERCENTAGE",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.REAL), newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // GET_ALLSERVERS_AND_PREFSERVER(String excludedServers,
      // String[] allNetServers, String[] prefServerName, int[] prefServerPort,
      // String[] allNetServers)
      String[] arg_names = new String[] { "EXCLUDED_SERVERS",
          "PREFERRED_SERVER_NAME", "PREFERRED_SERVER_PORT", "ALL_NETSERVERS" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.LONGVARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.INTEGER),
          DataTypeDescriptor.getCatalogType(Types.LONGVARCHAR) };
      super.createSystemProcedureOrFunction("GET_ALLSERVERS_AND_PREFSERVER",
          sysUUID, arg_names, arg_types, 3, 0, RoutineAliasInfo.READS_SQL_DATA,
          null, newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);

      // GET_ALLSERVERS_AND_PREFSERVER2(String excludedServers,
      // String[] allNetServers, String[] prefServerName, int[] prefServerPort,
      // Clob[] allNetServers)
      arg_names = new String[] { "EXCLUDED_SERVERS",
          "PREFERRED_SERVER_NAME", "PREFERRED_SERVER_PORT", "ALL_NETSERVERS" };
      arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.LONGVARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.INTEGER),
          DataTypeDescriptor.getCatalogType(Types.CLOB) };
      super.createSystemProcedureOrFunction("GET_ALLSERVERS_AND_PREFSERVER2",
          sysUUID, arg_names, arg_types, 3, 0, RoutineAliasInfo.READS_SQL_DATA,
          null, newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);

      // GET_PREFSERVER(String excludedServers, String[] preferredServerName,
      // int[] preferredServerPort)
      arg_names = new String[] { "EXCLUDED_SERVERS", "PREFERRED_SERVER_NAME",
          "PREFERRED_SERVER_PORT" };
      arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.LONGVARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      super.createSystemProcedureOrFunction("GET_PREFSERVER", sysUUID,
          arg_names, arg_types, 2, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // out ResultSet EXPORT_DDLS(Boolean exportAll)
      String[] argNames = new String[] { "EXPORT_ALL" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN) };
      super.createSystemProcedureOrFunction("EXPORT_DDLS", sysUUID, argNames,
          argTypes, 0, 1, RoutineAliasInfo.NO_SQL, null, newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void SET_TRACE_FLAG(String traceFlag, Boolean on)
      String[] argNames = new String[] { "TRACE_FLAG", "ON" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN) };
      super.createSystemProcedureOrFunction("SET_TRACE_FLAG", sysUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void SET_LOG_LEVEL(String logClass, String level)
      String[] argNames = new String[] { "LOGCLASS", "LEVEL" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
              DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                  Types.VARCHAR, false, 1024).getCatalogType(),
              DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                  Types.VARCHAR, false, 64).getCatalogType()};
      super.createSystemProcedureOrFunction("SET_LOG_LEVEL", sysUUID,
              argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
              newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void WAIT_FOR_SENDER_QUEUE_FLUSH(String id, Boolean isAsyncListener,
      //   Integer maxWaitTime)
      String[] argNames = new String[] { "ID", "IS_ASYNCLISTENER",
          "MAX_WAIT_TIME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 256),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      super.createSystemProcedureOrFunction("WAIT_FOR_SENDER_QUEUE_FLUSH",
          sysUUID, argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // void SET_GATEWAY_FK_CHECKS(Boolean flag)
      String[] argNames = new String[] { "ON"};
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN)};
      super.createSystemProcedureOrFunction("SET_GATEWAY_FK_CHECKS",
          sysUUID, argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
      // int GET_TABLE_VERSION(String schemaName, String tableName)
      String[] argNames = new String[] { "SCHEMANAME", "TABLENAME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.MAX_IDENTIFIER_LENGTH),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.MAX_IDENTIFIER_LENGTH) };
      super.createSystemProcedureOrFunction("GET_TABLE_VERSION", sysUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL,
          DataTypeDescriptor.getCatalogType(Types.INTEGER),
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // void INCREMENT_TABLE_VERSION(String schemaName, String tableName,
      //     Integer increment)
      String[] argNames = new String[] { "SCHEMANAME", "TABLENAME", "INCREMENT" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.MAX_IDENTIFIER_LENGTH),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR,
              Limits.MAX_IDENTIFIER_LENGTH),
          DataTypeDescriptor.getCatalogType(Types.INTEGER) };
      super.createSystemProcedureOrFunction("INCREMENT_TABLE_VERSION", sysUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void REFRESH_LDAP_GROUP(String groupName)
      String[] argNames = new String[] { "GROUPNAME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] { DataTypeDescriptor
          .getCatalogType(Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
      super.createSystemProcedureOrFunction("REFRESH_LDAP_GROUP",
          sysUUID, argNames, argTypes, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, null, newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void DISKSTORE_FSYNC(String diskStoreName)
      String[] argNames = new String[] { "DISKSTORENAME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] { DataTypeDescriptor
          .getCatalogType(Types.VARCHAR, Limits.MAX_IDENTIFIER_LENGTH) };
      super.createSystemProcedureOrFunction("DISKSTORE_FSYNC", sysUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // void SET_GLOBAL_STATEMENT_STATISTICS(Boolean enableStats,
      // Boolean enableTimeStats)
      String[] argNames = new String[] { "ENABLE_STATS", "ENABLE_TIMESTATS" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN) };
      super.createSystemProcedureOrFunction("SET_GLOBAL_STATEMENT_STATISTICS",
          sysUUID, argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // void DUMP_STACKS(Boolean all)
      String[] argNames = new String[] { "ALL" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN) };
      super.createSystemProcedureOrFunction("DUMP_STACKS", sysUUID, argNames,
          argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null, newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    
    {
        // void SET_BUCKETS_FOR_LOCAL_EXECUTION(TableName, buckets)
        String[] argNames = new String[] { "TABLE_NAME", "BUCKETS",
        "RELATION_DESTROY_VERSIONS"};
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getCatalogType(Types.VARCHAR),
            DataTypeDescriptor.getCatalogType(Types.VARCHAR),
            DataTypeDescriptor.getCatalogType(Types.INTEGER)
            };
        super.createSystemProcedureOrFunction("SET_BUCKETS_FOR_LOCAL_EXECUTION", sysUUID, argNames,
            argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null, newlyCreatedRoutines,
            tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // SQLJ.INSTALL_JAR_BYTES(Blob,String)
      String[] argNames = new String[] { "JAR_BYTES", "JAR_NAME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          CATALOG_TYPE_SYSTEM_IDENTIFIER };
      super.createSystemProcedureOrFunction("INSTALL_JAR_BYTES", sqlJUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // SQLJ.REPLACE_JAR_BYTES(Blob,String)
      String[] argNames = new String[] { "JAR_BYTES", "JAR_NAME" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          CATALOG_TYPE_SYSTEM_IDENTIFIER };
      super.createSystemProcedureOrFunction("REPLACE_JAR_BYTES", sqlJUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // REBALANCE_ALL_BUCKETS()
      super.createSystemProcedureOrFunction("REBALANCE_ALL_BUCKETS", sysUUID,
          null, null, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);

      // CREATE_ALL_BUCKETS(String tableName)
      String[] arg_names = new String[]{"TABLENAME"};
      TypeDescriptor[] arg_types = new TypeDescriptor[]{DataTypeDescriptor
          .getCatalogType(Types.LONGVARCHAR)};
      super.createSystemProcedureOrFunction("CREATE_ALL_BUCKETS", sysUUID,
          arg_names, arg_types, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      {
        String[] argNames = new String[] { "delayRollover", "txId" };
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.BOOLEAN, false).getCatalogType(),
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.VARCHAR, false).getCatalogType() };
        super.createSystemProcedureOrFunction("START_SNAPSHOT_TXID", sysUUID,
            argNames, argTypes, 1, 0, RoutineAliasInfo.NO_SQL, null,
            newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
      }
      {
        String[] argNames = new String[] { "txId", "rolloverTable" };
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.VARCHAR, false).getCatalogType(),
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.VARCHAR, false).getCatalogType() };
        super.createSystemProcedureOrFunction("COMMIT_SNAPSHOT_TXID", sysUUID,
            argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL,
            null, newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
      }
      {
        String[] argNames = new String[] { "txId" };
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.VARCHAR, false).getCatalogType() };
        super.createSystemProcedureOrFunction("ROLLBACK_SNAPSHOT_TXID", sysUUID,
            argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
            newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
      }
      {
        String[] argNames = new String[] { "txId" };
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.VARCHAR, false).getCatalogType() };
        super.createSystemProcedureOrFunction("USE_SNAPSHOT_TXID", sysUUID,
            argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
            newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
      }
      {
        String[] argNames = new String[] { "delayRollover" };
        TypeDescriptor[] argTypes = new TypeDescriptor[] {
            DataTypeDescriptor.getBuiltInDataTypeDescriptor(
                Types.BOOLEAN, false).getCatalogType() };
        super.createSystemProcedureOrFunction("GET_SNAPSHOT_TXID", sysUUID,
            argNames, argTypes, 0, 0, RoutineAliasInfo.READS_SQL_DATA,
            DataTypeDescriptor.getCatalogType(Types.VARCHAR), newlyCreatedRoutines,
            tc, GFXD_SYS_PROC_CLASSNAME, false);
      }
    }

    {
      // GET_TABLE_METADATA
      String[] arg_names = new String[] { "TABLE_NAME",
          "TABLE_OBJECT", "BUCKET_COUNT", "PARTITIONING_COLUMNS",
          "INDEX_COLUMNS", "BUCKET_TO_SERVER_MAPPING",
          "RELATION_DESTROY_VERSION", "PK_COLUMNS" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] { DataTypeDescriptor
          .getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          DataTypeDescriptor.getCatalogType(Types.INTEGER),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.CLOB),
          DataTypeDescriptor.getCatalogType(Types.INTEGER),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR)};
      super.createSystemProcedureOrFunction("GET_TABLE_METADATA",
          sysUUID, arg_names, arg_types, 7, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // CREATE_SNAPPY_TABLE
      String[] arg_names = new String[] { "TABLE_IDENT",
          "PROVIDER", "USER_SCHEMA", "SCHEMA_DDL", "MODE",
          "OPTIONS", "IS_BUILTIN"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN)};
      super.createSystemProcedureOrFunction("CREATE_SNAPPY_TABLE",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // DROP_SNAPPY_TABLE
      String[] arg_names = new String[] { "TABLE_IDENT", "IF_EXISTS", "IS_EXTERNAL" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN) };
      super.createSystemProcedureOrFunction("DROP_SNAPPY_TABLE",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // CREATE_SNAPPY_INDEX
      String[] arg_names = new String[] { "INDEX_IDENT",
          "TABLE_IDENT", "INDEX_COLUMNS", "OPTIONS"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BLOB),
          DataTypeDescriptor.getCatalogType(Types.BLOB)};
      super.createSystemProcedureOrFunction("CREATE_SNAPPY_INDEX",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // DROP_SNAPPY_INDEX
      String[] arg_names = new String[] { "INDEX_IDENT", "IF_EXISTS"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN)};
      super.createSystemProcedureOrFunction("DROP_SNAPPY_INDEX",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // CREATE_SNAPPY_UDF
      String[] arg_names = new String[] { "DB", "FUNCTION_NAME", "CLASS_NAME", "JAR_URI"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR)};
      super.createSystemProcedureOrFunction("CREATE_SNAPPY_UDF",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // DROP_SNAPPY_UDF
      String[] arg_names = new String[] { "DB", "FUNCTION_NAME"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR)};
      super.createSystemProcedureOrFunction("DROP_SNAPPY_UDF",
          sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // ALTER_SNAPPY_TABLE
      String[] arg_names = new String[] { "TABLE_IDENT", "IS_ADD_COL", "COL_NAME", "COL_DATATYPE", "COL_IS_NULLABLE"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
              DataTypeDescriptor.getCatalogType(Types.VARCHAR),
              DataTypeDescriptor.getCatalogType(Types.BOOLEAN),
              DataTypeDescriptor.getCatalogType(Types.VARCHAR),
              DataTypeDescriptor.getCatalogType(Types.VARCHAR),
              DataTypeDescriptor.getCatalogType(Types.BOOLEAN)};
      super.createSystemProcedureOrFunction("ALTER_SNAPPY_TABLE",
              sysUUID, arg_names, arg_types, 0, 0, RoutineAliasInfo.READS_SQL_DATA, null,
              newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // GET_SNAPPY_TABLE_STATS
      String[] arg_names = new String[] { "STATS_OBJECT"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BLOB)};
      super.createSystemProcedureOrFunction("GET_SNAPPY_TABLE_STATS",
          sysUUID, arg_names, arg_types, 1, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }


    {
      // GET_JARS -- Smart Connectors will pull all the jars
      String[] arg_names = new String[] { "JAR_PATHS"};
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR)};
      super.createSystemProcedureOrFunction("GET_DEPLOYED_JARS",
          sysUUID, arg_names, arg_types, 1, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // GET_BUCKET_TO_SERVER_MAPPING
      String[] arg_names = new String[] { "FQTN", "BKT_TO_SERVER_MAPPING" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] { DataTypeDescriptor
          .getCatalogType(Types.VARCHAR), DataTypeDescriptor
          .getCatalogType(Types.VARCHAR) };
      super.createSystemProcedureOrFunction("GET_BUCKET_TO_SERVER_MAPPING", sysUUID,
          arg_names, arg_types, 1, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // GET_BUCKET_TO_SERVER_MAPPING2
      String[] arg_names = new String[] { "FQTN", "BKT_TO_SERVER_MAPPING" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] { DataTypeDescriptor
          .getCatalogType(Types.VARCHAR), DataTypeDescriptor
          .getCatalogType(Types.CLOB) };
      super.createSystemProcedureOrFunction("GET_BUCKET_TO_SERVER_MAPPING2", sysUUID,
          arg_names, arg_types, 1, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }


    TypeDescriptor varchar32672Type = DataTypeDescriptor.getCatalogType(
        Types.VARCHAR, 32672);
    // used to put procedure into the SYSCS_UTIL schema
    UUID sysUtilUUID = getSystemUtilSchemaDescriptor().getUUID();
    {
      // Extended import function to skip locking or use multiple threads.
      // SYSCS_UTIL.IMPORT_TABLE_EX(IN SCHEMANAME VARCHAR(128),
      // IN TABLENAME VARCHAR(128), IN FILENAME VARCHAR(32762),
      // IN COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1),
      // IN CODESET VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT,
      // IN NUMTHREADS INTEGER, IN CASESENSITIVENAMES SMALLINT,
      // IN IMPORTCLASSNAME VARCHAR(32672), IN ERRORFILE VARCHAR(32762))

      // procedure argument names
      String[] argNames = { "schemaName", "tableName", "fileName",
          "columnDelimiter", "characterDelimiter", "codeset", "replace",
          "lockTable", "numThreads", "caseSensitiveNames", "importClassName",
          "errorFile" };

      // procedure argument types
      TypeDescriptor[] argTypes = { CATALOG_TYPE_SYSTEM_IDENTIFIER,
          CATALOG_TYPE_SYSTEM_IDENTIFIER, varchar32672Type,
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          CATALOG_TYPE_SYSTEM_IDENTIFIER, TypeDescriptor.SMALLINT,
          TypeDescriptor.SMALLINT, TypeDescriptor.INTEGER,
          TypeDescriptor.SMALLINT, varchar32672Type, varchar32672Type };

      super.createSystemProcedureOrFunction("IMPORT_TABLE_EX", sysUtilUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // SYSCS_UTIL.IMPORT_DATA_EX(IN SCHEMANAME VARCHAR(128),
      // IN TABLENAME VARCHAR(128), IN INSERTCOLUMNLIST VARCHAR(32762),
      // IN COLUMNINDEXES VARCHAR(32762), IN IN FILENAME VARCHAR(32762),
      // IN COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1),
      // IN CODESET VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT,
      // IN NUMTHREADS INTEGER, IN CASESENSITIVENAMES SMALLINT,
      // IN IMPORTCLASSNAME VARCHAR(32672), IN ERRORFILE VARCHAR(32762))

      // procedure argument names
      String[] argNames = { "schemaName", "tableName", "insertColumnList",
          "columnIndexes", "fileName", " columnDelimiter",
          "characterDelimiter", "codeset", "replace", "lockTable",
          "numThreads", "caseSensitiveNames", "importClassName", "errorFile" };

      // procedure argument types
      TypeDescriptor[] argTypes = { CATALOG_TYPE_SYSTEM_IDENTIFIER,
          CATALOG_TYPE_SYSTEM_IDENTIFIER, varchar32672Type, varchar32672Type,
          varchar32672Type, DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          CATALOG_TYPE_SYSTEM_IDENTIFIER, TypeDescriptor.SMALLINT,
          TypeDescriptor.SMALLINT, TypeDescriptor.INTEGER,
          TypeDescriptor.SMALLINT, varchar32672Type, varchar32672Type };

      super.createSystemProcedureOrFunction("IMPORT_DATA_EX", sysUtilUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // Extended import function to skip locking or use multiple threads.
      // SYSCS_UTIL.IMPORT_TABLE_LOBS_FROM_EXTFILE(IN SCHEMANAME
      // VARCHAR(128), IN TABLENAME VARCHAR(128), IN FILENAME VARCHAR(32762), IN
      // COLUMNDELIMITER CHAR(1),IN CHARACTERDELIMITER CHAR(1), IN CODESET
      // VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT, IN NUMTHREADS
      // INTEGER, IN CASESENSITIVENAMES SMALLINT,
      // IN IMPORTCLASSNAME VARCHAR(32672), IN ERRORFILE VARCHAR(32762))

      // procedure argument names
      String[] argNames = { "schemaName", "tableName", "fileName",
          "columnDelimiter", "characterDelimiter", "codeset", "replace",
          "lockTable", "numThreads", "caseSensitiveNames", "importClassName",
          "errorFile" };

      // procedure argument types
      TypeDescriptor[] argTypes = { CATALOG_TYPE_SYSTEM_IDENTIFIER,
          CATALOG_TYPE_SYSTEM_IDENTIFIER,
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 32672),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          CATALOG_TYPE_SYSTEM_IDENTIFIER, TypeDescriptor.SMALLINT,
          TypeDescriptor.SMALLINT, TypeDescriptor.INTEGER,
          TypeDescriptor.SMALLINT, varchar32672Type, varchar32672Type };

      super.createSystemProcedureOrFunction(
          "IMPORT_TABLE_LOBS_FROM_EXTFILE", sysUtilUUID, argNames, argTypes,
          0, 0, RoutineAliasInfo.MODIFIES_SQL_DATA, null, newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      // Extended import function to skip locking or use multiple threads.
      // SYSCS_UTIL.IMPORT_DATA_LOBS_FROM_EXTFILE(IN SCHEMANAME VARCHAR(128),
      // IN TABLENAME VARCHAR(128), IN INSERTCOLUMNLIST VARCHAR(32762),
      // IN COLUMNINDEXES VARCHAR(32762), IN IN FILENAME VARCHAR(32762),
      // IN COLUMNDELIMITER CHAR(1), IN CHARACTERDELIMITER CHAR(1),
      // IN CODESET VARCHAR(128), IN REPLACE SMALLINT, IN LOCKTABLE SMALLINT,
      // IN NUMTHREADS INTEGER, IN CASESENSITIVENAMES SMALLINT,
      // IN IMPORTCLASSNAME VARCHAR(32672), IN ERRORFILE VARCHAR(32762))

      // procedure argument names
      String[] argNames = { "schemaName", "tableName", "insertColumnList",
          "columnIndexes", "fileName", " columnDelimiter",
          "characterDelimiter", "codeset", "replace", "lockTable",
          "numThreads", "caseSensitiveNames", "importClassName", "errorFile" };

      // procedure argument types
      TypeDescriptor[] argTypes = { CATALOG_TYPE_SYSTEM_IDENTIFIER,
          CATALOG_TYPE_SYSTEM_IDENTIFIER,
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 32672),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 32672),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR, 32672),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          DataTypeDescriptor.getCatalogType(Types.CHAR, 1),
          CATALOG_TYPE_SYSTEM_IDENTIFIER, TypeDescriptor.SMALLINT,
          TypeDescriptor.SMALLINT, TypeDescriptor.INTEGER,
          TypeDescriptor.SMALLINT, varchar32672Type, varchar32672Type };

      super.createSystemProcedureOrFunction("IMPORT_DATA_LOBS_FROM_EXTFILE",
          sysUtilUUID, argNames, argTypes, 0, 0,
          RoutineAliasInfo.MODIFIES_SQL_DATA, null, newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      super.createSystemProcedureOrFunction("REPAIR_CATALOG", sysUUID,
          null, null, 0, 0, RoutineAliasInfo.READS_SQL_DATA,
          null, newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }

    {
      // SYS.CANCEL_STATEMENT(long statementId, long connectionId, long
      // executionId)
      
      // procedure argument names
      String[] argNames = new String[] { "STATEMENTUUID" };
      
      // procedure argument types
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR) };
      
      super.createSystemProcedureOrFunction("CANCEL_STATEMENT", sysUUID,
          argNames, argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    {
      String[] argNames = new String[] { "useNativeTimer", "nativeTimerType" };
      TypeDescriptor[] argTypes = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR)};
      super.createSystemProcedureOrFunction("SET_NANOTIMER_TYPE", sysUUID, argNames,
          argTypes, 0, 0, RoutineAliasInfo.NO_SQL, null, newlyCreatedRoutines,
          tc, GFXD_SYS_PROC_CLASSNAME, true);
    }
    {
      super.createSystemProcedureOrFunction("GET_IS_NATIVE_NANOTIMER",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.BOOLEAN), newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }

    {
      super.createSystemProcedureOrFunction("GET_NATIVE_NANOTIMER_TYPE",
          sysUUID, null, null, 0, 0, RoutineAliasInfo.CONTAINS_SQL,
          DataTypeDescriptor.getCatalogType(Types.VARCHAR), newlyCreatedRoutines, tc,
          GFXD_SYS_PROC_CLASSNAME, false);
    }
    {
      // GET_COLUMN_TABLE_SCHEMA(String,String,Clob[])
      String[] arg_names = new String[]{"SCHEMA", "TABLE", "SCHEMA_AS_JSON"};
      TypeDescriptor[] arg_types = new TypeDescriptor[]{
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.CLOB)
      };
      super.createSystemProcedureOrFunction("GET_COLUMN_TABLE_SCHEMA", sysUUID,
          arg_names, arg_types, 1, 0, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);

    }
    {
      // COLUMN_TABLE_SCAN(String,String,String,Blob,ResultSet[])
      String[] arg_names = new String[] { "TABLE", "PROJECTION", "FILTERS" };
      TypeDescriptor[] arg_types = new TypeDescriptor[] {
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.VARCHAR),
          DataTypeDescriptor.getCatalogType(Types.BLOB)
      };
      super.createSystemProcedureOrFunction("COLUMN_TABLE_SCAN", sysUUID,
          arg_names, arg_types, 0, 1, RoutineAliasInfo.READS_SQL_DATA, null,
          newlyCreatedRoutines, tc, GFXD_SYS_PROC_CLASSNAME, false);
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private final void createGfxdProcedure(String routineName, UUID schemaID,
      String[] argNames, TypeDescriptor[] argTypes, int numOutParams,
      int numResultSets, short routineSQLControl, TypeDescriptor return_type,
      TransactionController tc, String aliasName, String procClass,
      HashSet newlyCreatedRoutines, boolean isRestricted)
      throws StandardException {
    int num_args = 0;
    if (argNames != null)
      num_args = argNames.length;

    if (SanityManager.DEBUG) {
      if (num_args != 0) {
        SanityManager.ASSERT(argNames != null);
        SanityManager.ASSERT(argTypes != null);
        SanityManager.ASSERT(argNames.length == argTypes.length);
      }
    }

    // all args are only "in" arguments
    int[] arg_modes = null;
    if (num_args != 0) {
      arg_modes = new int[num_args];
      int num_in_param = num_args - numOutParams;
      for (int i = 0; i < num_in_param; i++)
        arg_modes[i] = JDBC30Translation.PARAMETER_MODE_IN;
      for (int i = 0; i < numOutParams; i++)
        arg_modes[num_in_param + i] = JDBC30Translation.PARAMETER_MODE_OUT;
    }

    RoutineAliasInfo routine_alias_info = new RoutineAliasInfo(
        routineName, // name of routine
        num_args, // number of params
        argNames, // names of params
        argTypes, // types of params
        arg_modes, // all "IN" params
        numResultSets, // number of result sets
        RoutineAliasInfo.PS_JAVA, // link to java routine
        routineSQLControl, // one of:
        // MODIFIES_SQL_DATA
        // READS_SQL_DATA
        // CONTAINS_SQL
        // NO_SQL
        true, // true - calledOnNullInput
        return_type);

    UUID routineID = getUUIDFactory().createUUID();
    AliasDescriptor ads = new AliasDescriptor(this, routineID, aliasName,
        schemaID, procClass, AliasInfo.ALIAS_TYPE_PROCEDURE_AS_CHAR,
        AliasInfo.ALIAS_NAME_SPACE_PROCEDURE_AS_CHAR, false,
        routine_alias_info, null);

    addDescriptor(ads, null, DataDictionary.SYSALIASES_CATALOG_NUM, false, tc);

    newlyCreatedRoutines.add(aliasName);
    if (isRestricted) {
      super.addRestrictedRoutine(routineID);
    }
  }

  public static final String DIAG_MEMBERS_TABLENAME = "MEMBERS";

  public static final String DIAG_QUERYSTATS_TABLENAME = "QUERYSTATS";

  public static final String DIAG_MEMORYANALYTICS_TABLENAME = "MEMORYANALYTICS";
  
  public static final String DIAG_STATEMENT_PLANS = "STATEMENTPLANS";
  
  public static final String JAR_INSTALL_TABLENAME = "JARS";
  
  public static final String INDEX_INFO_TABLENAME = "INDEXES";

  public static final String SESSIONS_TABLENAME = "SESSIONS";

  public static final String HIVETABLES_TABLENAME = "HIVETABLES";

  public static final String DISKSTOREIDS_TABLENAME = "DISKSTOREIDS";

  public static final String SNAPPY_TABLE_STATS = "TABLESTATS";

  private static final String[][] VTI_TABLE_CLASSES = {
      { DIAG_MEMBERS_TABLENAME,
          "com.pivotal.gemfirexd.internal.engine.diag.DistributedMembers" },
      { DIAG_QUERYSTATS_TABLENAME,
          "com.pivotal.gemfirexd.internal.engine.diag.QueryStatisticsVTI" },
      /*
      { DIAG_LOCK_TABLENAME,
      "com.pivotal.gemfirexd.internal.engine.diag.LockTable" }
      */
      { DIAG_MEMORYANALYTICS_TABLENAME,
          "com.pivotal.gemfirexd.internal.engine.diag.MemoryAnalyticsVTI" },
      { DIAG_STATEMENT_PLANS,
          "com.pivotal.gemfirexd.internal.engine.diag.StatementPlansVTI" },
      { JAR_INSTALL_TABLENAME,
          "com.pivotal.gemfirexd.internal.engine.GfxdJarHandler" },
      { INDEX_INFO_TABLENAME, 
          "com.pivotal.gemfirexd.internal.engine.IndexInfo" },
      { SESSIONS_TABLENAME,
          "com.pivotal.gemfirexd.internal.engine.diag.SessionsVTI" },
      { HIVETABLES_TABLENAME, HiveTablesVTI.class.getName() },
      { DISKSTOREIDS_TABLENAME, DiskStoreIDs.class.getName() },
      { SNAPPY_TABLE_STATS, SnappyTableStatsVTI.class.getName() },
  };

  private final HashMap<String, TableDescriptor> diagVTIMap =
    new HashMap<String, TableDescriptor>();

  private final HashMap<String, String> diagVTINames =
    new HashMap<String, String>();

  /**
   * @see DataDictionary#getBuiltinVTIClass(TableDescriptor, boolean)
   */
  @Override
  public String getBuiltinVTIClass(TableDescriptor td, boolean asTableFunction)
      throws StandardException {
    if (SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME.equals(td.getSchemaName())) {
      String className = this.diagVTINames.get(td.getDescriptorName());
      if (className != null) {
        return className;
      }
    }
    return super.getBuiltinVTIClass(td, asTableFunction);
  }

  /**
   * @see DataDictionary#getBuiltinVTIClass(String, String)
   */
  @Override
  public String getBuiltinVTIClass(String schemaName, String tableName)
      throws StandardException {
    if (SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME.equals(schemaName)) {
      String className = this.diagVTINames.get(tableName);
      if (className != null) {
        return className;
      }
    }
    return super.getBuiltinVTIClass(schemaName, tableName);
  }

  /**
   * @see DataDictionary#getVTIClass(TableDescriptor, boolean)
   */
  @Override
  public String getVTIClass(TableDescriptor td, boolean asTableFunction)
      throws StandardException {
    if (SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME.equals(td.getSchemaName())) {
      return getBuiltinVTIClass(td, asTableFunction);
    }
    else {
      return super.getVTIClass(td, asTableFunction);
    }
  }

  /**
   * Get the descriptor for the named table within the given schema. If the
   * schema parameter is NULL, it looks for the table in the current (default)
   * schema. Table descriptors include object ids, object types (table, view,
   * etc.)
   * 
   * @param tableName
   *          The name of the table to get the descriptor for
   * @param schema
   *          The descriptor for the schema the table lives in. If null, use the
   *          system schema.
   * @return The descriptor for the table, null if table does not exist.
   * 
   * @exception StandardException
   *              Thrown on failure
   */
  @Override
  public TableDescriptor getTableDescriptor(String tableName,
      SchemaDescriptor schema, TransactionController tc)
      throws StandardException {

    final SchemaDescriptor sd = (schema != null ? schema
        : getSystemSchemaDescriptor());
    if (SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME.equals(sd.getSchemaName())) {
      TableDescriptor td = this.diagVTIMap.get(tableName);
      if (td != null) {
        return td;
      }
    }
    return super.getTableDescriptor(tableName, schema, tc);
  }

  /**
   * Update all system schemas to have new authorizationId. This is needed while
   * upgrading pre-10.2 databases to 10.2 or later versions. From 10.2, all
   * system schemas would be owned by database owner's authorizationId.
   * 
   * @param aid
   *          AuthorizationID of Database Owner
   * @param tc
   *          TransactionController to use
   * 
   * @exception StandardException
   *              Thrown on failure
   */
  @Override
  public final void updateSystemSchemaAuthorization(String aid,
      TransactionController tc) throws StandardException {
    super.updateSystemSchemaAuthorization(aid, tc);
  }
}
