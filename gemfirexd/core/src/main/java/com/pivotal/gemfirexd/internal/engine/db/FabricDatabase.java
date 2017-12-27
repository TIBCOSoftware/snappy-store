
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

package com.pivotal.gemfirexd.internal.engine.db;

import java.io.IOException;
import java.io.Serializable;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.*;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.internal.ClassPathLoader;
import com.gemstone.gemfire.internal.GFToSlf4jBridge;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TLongHashSet;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.catalog.SystemProcedures;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndex;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.ReplayableConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLQueueEntry;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.AbstractGfxdReplayableMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.sql.execute.DistributionObserver;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.reference.EngineType;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.daemon.Serviceable;
import com.pivotal.gemfirexd.internal.iapi.services.io.FileUtil;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.loader.JarReader;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleFactory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyFactory;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertySetCallback;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.LanguageFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.FileInfoDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.GfxdDiskStoreDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.FileResource;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogFactory;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.util.DoubleProperties;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.impl.io.DirFile;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINTableDescriptor;
import com.pivotal.gemfirexd.internal.io.StorageFile;
import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;

/**
 * The Database interface provides control over the physical database (that is,
 * the stored data and the files the data are stored in), connections to the
 * database, operations on the database such as backup and recovery, and all
 * other things that are associated with the database itself.
 *
 * <p>The Database interface does not provide control over things that are part
 * of the Domain, such as users.
 *
 * <p>I'm not sure what this will hold in a real system, for now it simply
 * provides connection-creation for us. Perhaps when it boots, it creates the
 * datadictionary object for the database, which all users will then interact
 * with?
 *
 * @author Eric Zoerner
 */
public final class FabricDatabase implements ModuleControl,
                                             ModuleSupportable,
                                             PropertySetCallback,
                                             Database,
                                             JarReader {

  /** Property name to get the FabricDatabase instance during boot process */
  public final static String PROPERTY_NAME = GfxdConstants.GFXD_PREFIX
      + "database-object";

  private static final String TEMP_DIR_PREFIX = "gemfirexdtemp_";

  /** DOCUMENT ME! */
  private volatile boolean active;

  /** DOCUMENT ME! */
  private AuthenticationService authenticationService;

  private AuthenticationService peerAuthenticationService;

  /** The {@link GemFireStore} of booted database. */
  protected GemFireStore memStore;

  /** DOCUMENT ME! */
  protected PropertyFactory pf;

  /** classFactory but only set when per-database */
  protected volatile ClassFactory cfDB;

  /** DataDictionary for this database. */
  private GfxdDataDictionary dd;

  /** DOCUMENT ME! */
  protected LanguageConnectionFactory lcf;

  /** DOCUMENT ME! */
  protected LanguageFactory lf;

  /**
   * hold resourceAdapter in an Object instead of a ResourceAdapter so that XA
   * class use can be isolated to XA modules.
   */
  protected Object resourceAdapter;

  /** DOCUMENT ME! */
  private Locale databaseLocale;

  /** DOCUMENT ME! */
  private LogFactory logFactory;

  /** DOCUMENT ME! */
  private DataValueFactory dataValueFactory;

  /** DOCUMENT ME! */
  private DateFormat dateFormat;

  /** DOCUMENT ME! */
  private DateFormat timeFormat;

  /** DOCUMENT ME! */
  private DateFormat timestampFormat;

  /** DOCUMENT ME! */
  private UUID myUUID;

  /** is this class last to boot? */
  protected boolean lastToBoot;

  /** Database level setting to optimize statements to use a generic plan or not */
  private boolean disableStatementOptimization;

  /**
   * Database level setting to indicate whether new connections should be
   * enabled with runtime statistics.
   * <p>
   *
   * @link{ XPLAINFactory#apply(String, Serializable, Dictionary) XPLAINFactory}
   *        captures the level of statistics needed to capture.
   */
  private boolean runtimeStatisticsOn;

  private DirFile tempDir;

  // private final DefaultGfxdLockable hiveClientObject = new DefaultGfxdLockable(
  //    "HiveMetaStoreClient", GfxdConstants.TRACE_DDLOCK);

  /**
   * flag for tests to avoid precompiling SPS descriptors to reduce unit test
   * running times
   */
  public static boolean SKIP_SPS_PRECOMPILE = SystemProperties
      .getServerInstance().getBoolean("gemfirexd.SKIP_SPS_PRECOMPILE", false);

  /** to allow for initial DDL replay even with failures */
  private final boolean allowBootWithFailures = SystemProperties.getServerInstance().getBoolean(
      com.pivotal.gemfirexd.Property.DDLREPLAY_ALLOW_RESTART_WITH_ERRORS, false);

  /** to allow skipping of index sanity check on restart */
  public static boolean skipIndexCheck = SystemProperties.getServerInstance().getBoolean(
      com.pivotal.gemfirexd.Property.DDLREPLAY_NO_INDEX_CHECK, false);

  /**
   * Creates a new FabricDatabase object.
   */
  public FabricDatabase() {
    lastToBoot = true;
  }

  /*
   * ModuleControl interface
   */

  public boolean canSupport(String identifier, Properties startParams) {
    return Monitor.isDesiredCreateType(startParams, getEngineType());
  }

  public boolean allowBootWithFailures() {
    return this.allowBootWithFailures;
  }

  synchronized public void boot(boolean create, Properties startParams)
      throws StandardException {

    if (this.active) {
      return;
    }
    // We always use create=true for now
    // This will need to change when we deal with booting in a distributed
    // system. Ideally the user shouldn't have to set a create attribute,
    // but rather we should infer it by whether the metadata has been
    // initialized yet or not
    create = true;

    final boolean hadoopLoner = "true".equalsIgnoreCase(startParams
        .getProperty(Property.HADOOP_IS_GFXD_LONER));
    if (hadoopLoner) {
      ClassSize.setDummyCatalog();
    }
    InternalDistributedSystem.setHadoopGfxdLonerMode(hadoopLoner);

    ModuleFactory monitor = Monitor.getMonitor();

    // ???:ezoerner:20080211 do we need to store a locale in the distributed
    // system?
    /*if (create)
     {
     */
    if (startParams.getProperty(Property.CREATE_WITH_NO_LOG) == null) {
      startParams.put(Property.CREATE_WITH_NO_LOG, "true");
    }

    String localeID = startParams.getProperty(com.pivotal.gemfirexd.internal.iapi
                                              .reference.Attribute.TERRITORY);

    if (localeID == null) {
      localeID = Locale.getDefault().toString();
    }

    final Locale locale = monitor.setLocale(startParams, localeID);
    setLocale(locale);

    // boot the validation needed to do property validation, now property
    // validation is separated from AccessFactory, therefore from store
    bootValidation(create, startParams);

    // Add this to the properties so other modules can get easy access to
    // database handle during boot process
    startParams.put(PROPERTY_NAME, this);

    // boot the type factory before store to ensure any dynamically
    // registered types (DECIMAL) are there before logical undo recovery
    // might need them.
    this.dataValueFactory = (DataValueFactory)Monitor.bootServiceModule(
        create, this,
        com.pivotal.gemfirexd.internal.iapi.reference.ClassName.DataValueFactory,
        startParams);

    this.logFactory = (LogFactory)Monitor.bootServiceModule(create, this,
        LogFactory.MODULE, startParams);

    // may also want to set up a check that we are a singleton,
    // or that there isn't already a database object in the system
    // for the same database?

    //
    // We boot the authentication service. There should at least be one
    // per database (even if authentication is turned off) .
    //
    authenticationService = bootAuthenticationService(create, startParams);

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(authenticationService != null,
          "Failed to set the Authentication service for the database");
    }

    bootStore(create, startParams);

    assert pf != null && memStore != null;

    // create a database ID if one doesn't already exist
    myUUID = makeDatabaseID(create, startParams);

    // Add the database properties read from disk (not stored
    // in service.properties) into the set seen by booting modules.
    Properties allParams = new DoubleProperties(getAllDatabaseProperties(),
                                                startParams);

    pf.addPropertySetNotification(this);

    // Boot the ClassFactory, will be per-database or per-system.
    // reget the tc in case someone inadverdently destroyed it
    bootClassFactory(create, allParams);

    // setup GemFireXD class resolver
    ClassPathLoader.setLatestToDefaultWithCustomLoader(
        Boolean.getBoolean(ClassPathLoader.EXCLUDE_TCCL_PROPERTY),
        getClassFactory());

    this.dd = (GfxdDataDictionary)Monitor.bootServiceModule(create, this,
        DataDictionary.MODULE, allParams);

    lcf = (LanguageConnectionFactory) Monitor.bootServiceModule(
            create,
            this,
            LanguageConnectionFactory.MODULE,
            allParams);

    lf = (LanguageFactory) Monitor.bootServiceModule(create,
                                                     this,
                                                     LanguageFactory.MODULE,
                                                     allParams);

    bootResourceAdapter(create, allParams);

    // Lastly, let store knows that database creation is done and turn
    // on logging
    if (create && lastToBoot &&
        (startParams.getProperty(Property.CREATE_WITH_NO_LOG) != null)) {
      createFinished();
    }

    disableStatementOptimization = Boolean.parseBoolean(PropertyUtil
        .getSystemProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING));

    // populate and initialize the DDL queue
    // Initializing the queue later just before postCreateDDLReplay as read
    // lock needs to be taken on dd, see snap-585, for no new ddl during
    // restart and that place seems to be more suited so that on the ddl entries
    // don't sneak in or else we need to take extra precaution.
    /*
    if (this.memStore.restrictedDDLStmtQueue()) {
      this.memStore.getDDLQueueNoThrow().initializeQueue(this.dd);
    }
    else {
      this.memStore.getDDLStmtQueue().initializeQueue(this.dd);
    }
    */
    active = true;

    // Register GemFireXD Member MBean if management is not disabled
    GfxdManagementService.handleEvent(GfxdResourceEvent.FABRIC_DB__BOOT,
        this.memStore);
  }

  private void notifyRunning() {
    // notify FabricService
    final FabricService service = FabricServiceManager
        .currentFabricServiceInstance();
    if (service instanceof FabricServiceImpl) {
      ((FabricServiceImpl)service).notifyRunning();
    }
  }

  /**
   * Performs the initialization steps after creation of initial database,
   * including initialization of default disk stores in system tables, replay of
   * the initial DDLs received by GII from other nodes or recovered from disc,
   * executing the post initialization scripts
   */
  synchronized public void postCreate(
      com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection conn,
      Properties bootProps) throws StandardException {
    if (this.memStore.initialDDLReplayDone()) {
      notifyRunning();
      return;
    }

    try {
      final EmbedConnection embedConn = (EmbedConnection)conn;
      final GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
      final LogWriter logger = cache.getLogger();
      final LanguageConnectionContext lcc = embedConn.getLanguageConnection();
      final GemFireTransaction tc = (GemFireTransaction)lcc
          .getTransactionExecute();

      // Entry of default disk stores in sysdiskstore table
      UUIDFactory factory = dd.getUUIDFactory();
      addInternalDiskStore(cache.findDiskStore(
          GfxdConstants.GFXD_DD_DISKSTORE_NAME), factory);
      addInternalDiskStore(this.memStore.getDefaultDiskStore(), factory);
      addInternalDiskStore(cache.findDiskStore(
          GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE), factory);

      // Initialize ConnectionWrapperHolder with this embeded connection
      GfxdManagementService.handleEvent(
          GfxdResourceEvent.EMBEDCONNECTION__INIT, embedConn);

      boolean ddReadLockAcquired = false;
      try {
        // Acquire a read lock on data dictionary so that no new ddls can start
        // executing until this node has finished ddl replay
        int cnt = 0;
        while( !(ddReadLockAcquired = this.dd.lockForReadingNoThrow(
            null, Long.MAX_VALUE / 2))) {
          if (cnt >= 12) {
            throw StandardException.newException(
                SQLState.BOOT_DATABASE_FAILED,
                "Could not acquire readlock on datadictionary before ddl replay");
          }
          Thread.sleep(5000);
        }
        logger.info("acquired dd read lock during post create");
        // populate and initialize the DDL queue
        if (this.memStore.restrictedDDLStmtQueue()) {
          this.memStore.getDDLQueueNoThrow().initializeQueue(this.dd);
        }
        else {
          this.memStore.getDDLStmtQueue().initializeQueue(this.dd);
        }
        postCreateDDLReplay(embedConn, bootProps, lcc, tc, logger);
      } finally {
        if (ddReadLockAcquired) {
          this.dd.unlockAfterReading(null);
        }
      }

      notifyRunning();

      // Execute any provided post SQL scripts last.
      final String postScriptsPath = bootProps
          .getProperty(Attribute.INIT_SCRIPTS);
      if (postScriptsPath != null && postScriptsPath.length() > 0) {
        String[] postScriptPaths = postScriptsPath.split(",");
        GemFireXDUtils.executeSQLScripts(embedConn, postScriptPaths, false,
            logger, null, null, false);
      }

      // Initialize the catalog
      // Lead is always started with ServerGroup hence for lead LeadGroup will never be null.
      /**
       * In LeadImpl server group is always  set to using following code:
       * changeOrAppend(Constant * .STORE_PROPERTY_PREFIX +com.pivotal.gemfirexd.Attribute.
       * SERVER_GROUPS, LeadImpl.LEADER_SERVERGROUP)
       */
      HashSet<String> leadGroup = CallbackFactoryProvider.getClusterCallbacks().getLeaderGroup();
      final boolean isLead = this.memStore.isSnappyStore() && (leadGroup != null && leadGroup
          .size() > 0) && (ServerGroupUtils.isGroupMember(leadGroup)
          || Misc.getDistributedSystem().isLoner());
      Set<?> servers = GemFireXDUtils.getGfxdAdvisor().adviseDataStores(null);
      if (this.memStore.isSnappyStore() && (this.memStore.getMyVMKind() ==
          GemFireStore.VMKind.DATASTORE || (isLead && servers.size() > 0))) {
        // Take write lock on data dictionary. Because of this all the servers will will initiate their
        // hive client one by one. This is important as we have downgraded the ISOLATION LEVEL from
        // SERIALIZABLE to REPEATABLE READ
        final String hiveClientObject = "HiveMetaStoreClient";
        final DLockService lockService = memStore.getDDLLockService();
        final boolean writeLockTaken = lockService.lock(hiveClientObject,
            GfxdLockSet.MAX_LOCKWAIT_VAL, -1);
        try {
          //writeLockTaken = this.dd.lockForWriting(tc, false);
          // Changed from ddlLockObject
          this.memStore.initExternalCatalog();
        } finally {
          if (writeLockTaken) {
            //this.dd.unlockAfterWriting(tc, false);
            lockService.unlock(hiveClientObject);
          }
        }
      }

      if (isLead && servers.size() > 0) {
        checkSnappyCatalogConsistency(embedConn);
      }

      if (this.memStore.isSnappyStore() && (this.memStore.getMyVMKind() ==
          GemFireStore.VMKind.DATASTORE || Misc.getDistributedSystem().isLoner())) {
        CallbackFactoryProvider.getClusterCallbacks().publishColumnTableStats();
      }
    } catch (Throwable t) {
      try {
        LogWriter logger = Misc.getCacheLogWriter();
        if (logger != null) {
          logger.warning("got throwable: " + t.getMessage() + " calling shut down", t);
        }
        Monitor.getMonitor().shutdown();
      } catch (CancelException ce) {
        // ignore
      }
      if (GemFireXDUtils.TraceFabricServiceBoot) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
            "Failed to boot database", t);
      }
      Throwable checkEx = t;
      if (t instanceof GemFireXDRuntimeException) {
        checkEx = t.getCause();
      }
      if (checkEx instanceof SQLException) {
        SQLException sqle = (SQLException)checkEx;
        if (sqle.getSQLState() != null && sqle.getSQLState().startsWith("XBM")) {
          throw Misc.wrapSQLException(sqle, sqle);
        }
      }
      if (checkEx instanceof StandardException) {
        StandardException se = (StandardException)checkEx;
        if (se.getSQLState() != null && se.getSQLState().startsWith("XBM")) {
          throw se;
        }
      }
      throw StandardException.newException(SQLState.BOOT_DATABASE_FAILED, t,
          Attribute.GFXD_DBNAME);
    }
  }

  private void addInternalDiskStore(DiskStoreImpl ds, UUIDFactory factory)
      throws StandardException {
    if (ds != null) {
      UUID id = factory.recreateUUID(ds.getName());
      GfxdDiskStoreDescriptor dsd = new GfxdDiskStoreDescriptor(dd, id, ds,
          ds.getDiskDirs()[0].getAbsolutePath());
      dd.addDescriptor(dsd, null, DataDictionary.SYSDISKSTORES_CATALOG_NUM,
          false, dd.getTransactionExecute());
    }
  }

  /**
   * Detect catalog inconsistencies (between store DD and Hive MetaStore)
   * and remove those
   * @param embedConn
   * @throws StandardException
   * @throws SQLException
   */
  public static void checkSnappyCatalogConsistency(
      EmbedConnection embedConn)
      throws StandardException, SQLException {
    final LanguageConnectionContext lcc = embedConn.getLanguageConnection();
    final GemFireTransaction tc = (GemFireTransaction)lcc
        .getTransactionExecute();
    HashMap<String, List<String>> hiveDBTablesMap = null;
    HashMap<String, List<String>> gfDBTablesMap = null;

    try {
      lcc.getDataDictionary().lockForReading(tc);
      hiveDBTablesMap =
          Misc.getMemStoreBooting().getExternalCatalog().getAllStoreTablesInCatalog(true);
      gfDBTablesMap = getAllGFXDTables();
    } finally {
      lcc.getDataDictionary().unlockAfterReading(tc);
    }
//    SanityManager.DEBUG_PRINT("info", "hiveDBTablesMap = " + hiveDBTablesMap);

    // remove Hive store's own tables
    gfDBTablesMap.remove(
        Misc.getMemStoreBooting().getExternalCatalog().catalogSchemaName());
    // CachedBatch tables (earlier stored in SNAPPYSYS_INTERNAL)
    List<String> internalColumnTablesList = new LinkedList<>();
    List<String> internalColumnTablesListPerSchema = new LinkedList<>();
    for (Map.Entry<String, List<String>> e : gfDBTablesMap.entrySet()) {
      for (String t : e.getValue()) {
        if (CallbackFactoryProvider.getStoreCallbacks().isColumnTable(e.getKey() + "." + t)) {
            internalColumnTablesListPerSchema.add(t);
        }
      }
      e.getValue().removeAll(internalColumnTablesListPerSchema);
      internalColumnTablesList.addAll(internalColumnTablesListPerSchema);
      internalColumnTablesListPerSchema.clear();
    }
    // creating a set here just for lookup, will not consume too much
    // memory as size limited by no of tables
    Set<String> internalColumnTablesSet = new HashSet<>();
    if (internalColumnTablesList != null) {
      internalColumnTablesSet.addAll(internalColumnTablesList);
    }

//     SanityManager.DEBUG_PRINT("info", "tables in hive store = " + hiveDBTablesMap);
//     SanityManager.DEBUG_PRINT("info", "tables in DD  = " + gfDBTablesMap);
    removeInconsistentDDEntries(embedConn, hiveDBTablesMap,
        gfDBTablesMap, internalColumnTablesSet);
    removeInconsistentHiveEntries(hiveDBTablesMap, gfDBTablesMap);
  }

  /**
   * Removes any table entries that are just in DD but not in store.
   * Removes column table entries for which internal column buffer is
   * missing but row buffer exists
   * @param embedConn
   * @param hiveDBTablesMap  schema to tables map of hive metastore entries
   * @param gfDBTablesMap   schema to tables map of DD entries
   * @param internalColumnTablesSet internal column buffer tables
   * @throws SQLException
   */
  private static void removeInconsistentDDEntries(EmbedConnection embedConn,
      HashMap<String, List<String>> hiveDBTablesMap,
      HashMap<String, List<String>> gfDBTablesMap,
      Set<String> internalColumnTablesSet) throws SQLException {
    for (Map.Entry<String, List<String>> storeEntry : gfDBTablesMap.entrySet()) {
      List<String> hiveTableList = hiveDBTablesMap.get(storeEntry.getKey());
      List<String> storeTablesList = new LinkedList<>(storeEntry.getValue());

      // remove tables that are in datadictionary store but not in Hivestore
      if (!(hiveTableList == null || hiveTableList.isEmpty())) {
        storeTablesList.removeAll(hiveTableList);
      }
      if (!(storeTablesList == null || storeTablesList.isEmpty())) {
        SanityManager.DEBUG_PRINT("info",
            "Catalog inconsistency detected: following tables " +
                "in datadictionary are not in Hive metastore: " +
                "schema = " + storeEntry.getKey() + " tables = " + storeTablesList);
        dropTables(embedConn, storeEntry.getKey(), storeTablesList);
      }

      // DD contains row buffer but not the column buffer of the table
      List<String> tablesMissingColumnBuffer = new LinkedList<>();
      for (String storeTable : storeEntry.getValue()) {
        if (Misc.getMemStoreBooting().getExternalCatalog().
            isColumnTable(storeEntry.getKey(), storeTable, false)) {
          String columnBatchTable = com.gemstone.gemfire.
              internal.snappy.CallbackFactoryProvider.getStoreCallbacks().
              columnBatchTableName(storeEntry.getKey() + "." + storeTable);
          columnBatchTable = columnBatchTable.substring(columnBatchTable.indexOf(".") + 1);
//          SanityManager.DEBUG_PRINT("info", "columnBatchTable = " + columnBatchTable);
          if (!internalColumnTablesSet.contains(columnBatchTable)) {
            tablesMissingColumnBuffer.add(storeTable);
          }
        }
      }
      if (!tablesMissingColumnBuffer.isEmpty()) {
        SanityManager.DEBUG_PRINT("info",
            "Catalog inconsistency detected: following column tables " +
                "do not have column buffer: " +
                "schema = " + storeEntry.getKey() + " tables = " + tablesMissingColumnBuffer);
        dropTables(embedConn, storeEntry.getKey(), tablesMissingColumnBuffer);
        removeTableFromHivestore(storeEntry.getKey(), tablesMissingColumnBuffer);
      }
    }
  }

  /**
   * Remove Hive entries for which there is no DD entry
   * @param hiveDBTablesMap schema to tables map of hive metastore entries
   * @param gfDBTablesMap schema to tables map of DD entries
   */
  private static void removeInconsistentHiveEntries(
      HashMap<String, List<String>> hiveDBTablesMap,
      HashMap<String, List<String>> gfDBTablesMap) {
    // remove tables that are in Hive store but not in datadictionary
    for (Map.Entry<String, List<String>> hiveEntry : hiveDBTablesMap.entrySet()) {
      List<String> storeTableList = gfDBTablesMap.get(hiveEntry.getKey());
      List<String> hiveTableList = new LinkedList<>(hiveEntry.getValue());
      if (!(storeTableList == null || storeTableList.isEmpty())) {
        hiveTableList.removeAll(storeTableList);
      }

      if (!(hiveTableList == null || hiveTableList.isEmpty())) {
        SanityManager.DEBUG_PRINT("info",
            "Catalog inconsistency detected: following tables " +
                "in Hive metastore are not in datadictionary: " +
                "schema = " + hiveEntry.getKey() + " tables = " + hiveTableList);
        removeTableFromHivestore(hiveEntry.getKey(), hiveTableList);
      }
    }
  }

  private static final void removeTableFromHivestore(String schema, List<String> tables) {
    for (String table : tables) {
      SanityManager.DEBUG_PRINT("info", "Removing table " +
          schema + "." + table + " from Hive metastore");
      Misc.getMemStoreBooting().getExternalCatalog().removeTable(schema, table, false);
    }
  }

  private static final void dropTables(EmbedConnection embedConn,
      String schema, List<String> tables) throws SQLException {
    for (String table : tables) {
      try {
        String tableName = schema + "." + table;
        SanityManager.DEBUG_PRINT("info", "FabricDatabase.dropTables " +
            " processing " + tableName);

        // drop column batch table
        String columnBatchTableName = com.gemstone.gemfire.
            internal.snappy.CallbackFactoryProvider.getStoreCallbacks().
            columnBatchTableName(tableName);
        // set to true only if column batch table is present and could not be removed
        boolean columnBatchTableExists = false;
        final Region<?, ?> targetRegion =
            Misc.getRegionForTable(columnBatchTableName, false);
        if (targetRegion != null) {
          // make sure that corresponding row buffer also does not contain data
          final Region<?, ?> rowTableRegion =
              Misc.getRegionForTable(tableName, false);
          if (targetRegion.size() == 0 &&
              (rowTableRegion == null || rowTableRegion.size() == 0)) {
            SanityManager.DEBUG_PRINT("info", "Dropping table " +
                columnBatchTableName);
            embedConn.createStatement().execute(
                "DROP TABLE IF EXISTS " + columnBatchTableName);
          } else {
            columnBatchTableExists = true;
            SanityManager.DEBUG_PRINT("info", "Not dropping table " +
                columnBatchTableName + " as it is not empty");
          }
        }

        // drop row table

        // don't drop if corresponding column batch table could not removed
        if (!columnBatchTableExists) {
          final Region<?, ?> rowTableRegion =
              Misc.getRegionForTable(tableName, false);
          if (rowTableRegion != null) {
            if (rowTableRegion.size() == 0) {
              SanityManager.DEBUG_PRINT("info", "Dropping table " + tableName);
              embedConn.createStatement().execute(
                  "DROP TABLE IF EXISTS " + tableName);
            } else {
              SanityManager.DEBUG_PRINT("info", "Not dropping table " +
                  tableName + " as it is not empty");
            }
          }
        }
      } catch (SQLException se) {
        SanityManager.DEBUG_PRINT("info", "SQLException: ", se);
      }
    }
  }

  private static final HashMap<String, List<String>> getAllGFXDTables() {
    List<GemFireContainer> gfContainers = Misc.getMemStoreBooting().getAllContainers();
    HashMap<String, List<String>> gfDBTablesMap = new HashMap<>();
    for (GemFireContainer gc : gfContainers) {
      if (gc.isApplicationTable()) {
        List<String> tableList = gfDBTablesMap.get(gc.getSchemaName());
        if (tableList == null) {
          tableList = new LinkedList<>();
          gfDBTablesMap.put(gc.getSchemaName(), tableList);
        }
        tableList.add(gc.getTableName());
      }
    }
    return gfDBTablesMap;
  }

  /**
   * Replays the initial DDL received by GII from other nodes or recovered from
   * disc.
   */
  private void postCreateDDLReplay(final EmbedConnection embedConn,
      final Properties bootProps, final LanguageConnectionContext lcc,
      final GemFireTransaction tc, final LogWriter logger) throws Exception {

    // Replay the initial DDL statements, if any, after DB is created. We invoke
    // this in postCreate so as to ensure that the first connection required for
    // DDL statement prepare and execution has been fully initialized.
    final GfxdDDLRegionQueue ddlStmtQueue = this.memStore.getDDLQueueNoThrow();
    final String initSchema = lcc.getCurrentSchemaName();
    String lastCurrentSchema = initSchema != null ? initSchema
        : SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    // create system procedures first to avoid deadlocks later (#47362)
    lcc.setIsConnectionForRemote(true);
    lcc.setIsConnectionForRemoteDDL(false);
    lcc.setSkipLocks(true);
    lcc.setQueryRoutingFlag(false);
    tc.resetActiveTXState(false);
    // for admin VM types do not compile here
    final GemFireStore.VMKind vmKind = this.memStore.getMyVMKind();
    final boolean skipSPSPrecompile = SKIP_SPS_PRECOMPILE;
    if (skipSPSPrecompile) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          "Skipping precompilation of inbuilt procedures");
    }
    dd.createSystemSps(tc, vmKind.isAccessorOrStore() && !skipSPSPrecompile
        && !this.memStore.isHadoopGfxdLonerMode());

    // Execute any provided initial SQL scripts first.
    // remote the initial SQL commands
//    lcc.setIsConnectionForRemote(false);
//    lcc.setSkipLocks(false);
    /*
    String initScriptsPath = bootProps.getProperty(Attribute.CONFIG_SCRIPTS);
    if (initScriptsPath != null && initScriptsPath.length() > 0) {
      String[] initScriptPaths = initScriptsPath.split(",");
      GemFireXDUtils.executeSQLScripts(embedConn, initScriptPaths, false, logger,
          null, null, false);
    }
    */

    // Execute DDLs in GfxdDDLRegionQueue next.
    final Object sync = this.memStore.getInitialDDLReplaySync();
    synchronized (sync) {
      this.memStore.setInitialDDLReplayInProgress(true);
      // notify any waiters
      sync.notifyAll();
    }

    // Mark this node as uninitialized on all nodes, including self, to
    // avoid selecting it for any primaries etc.
    this.memStore.getDistributionAdvisor().distributeNodeStatus(false);
    // Do not remote the SQL commands that are part of initial DDL replay.
    lcc.setIsConnectionForRemote(true);
    lcc.setSkipLocks(true);
    // Since readlock on datadictionary is taken upfront so no
    // new ddls will arrive in the queue and multiple iterations won't be
    // required.
    //int maxIterations = 4;
    GfxdDDLQueueEntry qEntry = null;
    // The strategy of replay is thus. We get the initial batch of DDLs to
    // be executed from the DDL RegionQueue in a write lock. Any DDL
    // messages received in this duration will fall through with
    // successful reply expecting the initial replay to handle it. Since
    // new DDLs may have arrived by the time the initial batch was
    // processed, we repeat this process some number of times. We cannot
    // keep on doing this since in the worst case it is possible we get
    // stuck in an infinite loop where new DDLs are always received by the
    // time the current processing is done. So at some point we have to
    // stop and then block the GfxdDDLMessage processing for the last
    // iteration to avoid missing any new DDLs received while processing
    // was on. This is done by keeping the DD read lock for the last
    // iteration. However, this also means that it is possible for the
    // same DDL to be processed during intial replay in last iteration and
    // received as GfxdDDLMessage (which is blocked), so need to take care
    // of duplicates using DDL IDs.
    // The above sophisticated strategy to allow ddls while replay is going
    // on is being disabled as ddls are not frequent and several race condition
    // scenario will automatically go away making it simpler and more
    // maintainable. ( as part of snap-585 )
    /*
    boolean acquiredReplayLock = false;
    boolean ddReadLockAcquired = false;
    */
    int actualSize;
    List<GfxdDDLQueueEntry> currentQueue;
    final ArrayList<GemFireContainer> uninitializedContainers =
        new ArrayList<GemFireContainer>();
    final LinkedHashSet<GemFireContainer> uninitializedTables =
        new LinkedHashSet<GemFireContainer>();
    final Statement stmt = embedConn.createStatement();

    try {
      // commenting out for snap-585
      /*
      while (maxIterations-- > 0) {

        // For the last iteration take the DD read lock to force any
        // in progress DDLs to flush and avoid missing them.
        // This alongwith the DD read lock in GfxdDDLRegion#chunkEntries
        // ensures that all pending DDLs that have possibly not sent
        // the GfxdDDLMessage are flushed.

        ddReadLockAcquired = false;
        if (maxIterations == 0) {
          // pass TC as null to avoid check of DDL replay in progress in
          // the lock method
          // try to acquire DD lock in loop checking whether the skip lock
          // flag has been set
          ddReadLockAcquired = this.dd.lockForReadingInDDLReplayNoThrow(
              this.memStore, Long.MAX_VALUE / 2, true);
        }
        this.memStore.acquireDDLReplayLock(true);
        acquiredReplayLock = true;
        */
        final TLongHashSet processedIds = this.memStore.getProcessedDDLIDs();
        synchronized (processedIds) {
          // get all elements in the queue removing them from the queue
          // but not from the underlying region
          currentQueue = ddlStmtQueue.peekAndRemoveFromQueue(-1, -1);
          // mark all DDLs as executing first while holding the replay lock;
          // this is used for blocking by GfxdDDLMessage now in case the
          // incoming message is a DROP/ALTER and CREATE has already been
          // executed or has started execution
          for (GfxdDDLQueueEntry entry : currentQueue) {
            Object qVal = entry.getValue();
            if (qVal instanceof ReplayableConflatable) {
              ((ReplayableConflatable)qVal).markExecuting();
            }
          }
          // commenting out for snap-585
          /*
          if (maxIterations > 0) {
            // do not release the lock in the last iteration to block
            // GfxdDDLMessages and thus avoid missing any DDL messages
            this.memStore.releaseDDLReplayLock(true);
            acquiredReplayLock = false;
          }
          if ((actualSize = currentQueue.size()) == 0) {
            // we are good to end; force the next iteration to be the last
            // one which is still required to take locks etc. and ensure
            // flush of any pending DDLs/procedures
            if (maxIterations > 1) {
              maxIterations = 1;
            }
            continue;
          }
          */
          // add the DDL IDs to processed IDs in advance since this could
          // need to wait for GfxdDDLFinishMessage so don't block
          // GfxdDDLMessage else a deadlock will happen with this thread
          // waiting for finish message on DDLConflatable while the same
          // DDL's GfxdDDLMessage waiting for the replay lock to be
          // released
          Iterator<GfxdDDLQueueEntry> iter = currentQueue.iterator();
          while (iter.hasNext()) {
            GfxdDDLQueueEntry entry = iter.next();
            Long key = entry.getKey();
            // remove if this has been already processed by GfxdDDLMessage
            if (!processedIds.add(key)) {
              iter.remove();
            }
          }
          // commenting out for snap-585
          //actualSize = currentQueue.size();
        }
      // commenting out for snap-585
      /*
        if (logger.infoEnabled()) {
          logger.info("FabricDatabase: initial replay remaining iters "
              + maxIterations + " with remaining queue size " + actualSize);
        }
      */
        // First check if region intialization should be skipped for
        // any of the regions due to ALTER TABLE (#44280).
        // This map contains the current dependent ALTER TABLE DDL for a
        // CREATE TABLE for which the table initialization needs to be
        // delayed till after execution of the ALTER TABLE.
        final HashMap<DDLConflatable, DDLConflatable> skipRegionInit =
            new HashMap<DDLConflatable, DDLConflatable>();
        // map of table name to pre 1.1 product's schema version
        final TObjectIntHashMap pre11TableSchemaVer = new TObjectIntHashMap();
        final boolean traceConflation = GemFireXDUtils.TraceConflation
            | DistributionManager.VERBOSE | GemFireXDUtils.TraceIndex;
        List<GfxdDDLQueueEntry> preprocessedQueue = ddlStmtQueue
            .getPreprocessedDDLQueue(currentQueue, skipRegionInit,
                lastCurrentSchema, pre11TableSchemaVer, traceConflation);
        for (GfxdDDLQueueEntry entry : preprocessedQueue) {
          qEntry = entry;
          Object qVal = qEntry.getValue();
          if (logger.infoEnabled()) {
            logger.info("FabricDatabase: starting initial replay "
                + "for entry: " + qEntry);
          }
          // clear the initializing region first (JIRA: GEMXD-1)
          LocalRegion.clearInitializingRegion();

          // TODO: currently other messages are not executed on LOCATOR/AGENT
          // but in future we will need jar procedures to be executed everywhere
          // for user-defined authenticators
          if (qVal instanceof GfxdSystemProcedureMessage) {
            final GfxdSystemProcedureMessage msg =
                (GfxdSystemProcedureMessage)qVal;
            if (msg.getSysProcMethod().isOffHeapMethod()
                && this.memStore.getGemFireCache().getOffHeapStore() == null) {
              if (logger.severeEnabled()) {
                logger.severe("FabricDatabase: aborted initial replay "
                    + "for message " + msg + " method "
                    + msg.getSysProcMethod().name());
              }
              continue;
            }
            try {
              msg.execute();
            } catch (Exception ex) {
              if (logger.severeEnabled()) {
                logger.severe("FabricDatabase: failed initial replay "
                    + "for message " + msg + " due to exception", ex);
              }
              throwBootException(ex, embedConn);
              continue;
            }
          }
          else if (this.memStore.restrictedDDLStmtQueue()) {
            continue;
          }
          else if (qVal instanceof AbstractGfxdReplayableMessage) {
            final AbstractGfxdReplayableMessage msg =
                (AbstractGfxdReplayableMessage)qVal;
            try {
              msg.execute();
            } catch (Exception ex) {
              if (logger.severeEnabled()) {
                logger.severe("FabricDatabase: failed initial replay "
                    + "for message " + msg + " due to exception", ex);
              }
              throwBootException(ex, embedConn);
              continue;
            }
          }
          else {
            final DDLConflatable conflatable = (DDLConflatable)qVal;
            // check for any merged DDLs
            final String confTable = conflatable.getRegionToConflate();
            final boolean isCreateTable = conflatable.isCreateTable();
            /*
            DDLConflatable dependent = null;
            if (skipRegionInit.size() > 0) {
              dependent = skipRegionInit.get(conflatable);
              final String colocatedWith;
              // also check if this region is colocated with another whose
              // initialization has been delayed
              if (dependent == null && isCreateTable && (colocatedWith =
                  conflatable.getColocatedWithTable()) != null) {
                // search in the list of regions being skipped
                for (DDLConflatable oddl : skipRegionInit.keySet()) {
                  if (colocatedWith.equals(oddl.getRegionToConflate())) {
                    dependent = oddl;
                    if (traceConflation) {
                      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                          "FabricDatabase: delaying initializing ["
                              + conflatable + "] for: " + oddl);
                    }
                    break;
                  }
                }
                if (dependent != null) {
                  skipRegionInit.put(conflatable, dependent);
                }
              }
              if (dependent != null && logger.infoEnabled()) {
                logger.info("FabricDatabase: delaying initialization of "
                    + "entry with key=" + qEntry.getKey() + " due to: "
                    + dependent);
              }
            }
            */

            boolean skipInitialization = false;
            int pre11SchemaVer = 0;
            if (pre11TableSchemaVer.size() > 0
                && (isCreateTable || conflatable.isAlterTable())) {
              pre11SchemaVer = pre11TableSchemaVer.get(confTable);
            }
            /*
            if (dependent != null) {
              skipInitialization = true;
            }
            */
            if (isCreateTable || conflatable.isCreateIndex()
                || conflatable.isAlterTable()) {
              // always skip initialization now for the case in #47873
              skipInitialization = true;
            }
            // also skip initialization of region for old product version
            // recovery from disk so that appropriate RowFormatter can be
            // attached when schema matches that from the last version
            // recovered from disk
            String schema = executeDDL(conflatable, stmt, skipInitialization,
                embedConn, lastCurrentSchema, lcc, tc, logger);
            if (isCreateTable && skipInitialization) {
              uninitializedTables.add((GemFireContainer)Misc
                  .getRegionForTableByPath(confTable, true).getUserAttribute());
            }
            // set the current schema version as pre 1.1 recovery version
            if (pre11SchemaVer > 0) {
              final GemFireContainer container = (GemFireContainer)Misc
                  .getRegionForTableByPath(confTable, true).getUserAttribute();
              if (container != null
                  && container.getCurrentSchemaVersion() == pre11SchemaVer) {
                if (logger.infoEnabled()) {
                  logger.info("FabricDatabase: setting schema version for "
                      + "pre 1.1 data to " + pre11SchemaVer + " for table "
                      + confTable);
                }
                container.initPre11SchemaVersionOnRecovery(dd, lcc);
                pre11TableSchemaVer.remove(confTable);
              }
            }
            if (schema != null) {
              lastCurrentSchema = schema;
            }
            else {
              continue;
            }
          }
          if (logger.infoEnabled()) {
            logger.info("FabricDatabase: successfully replayed entry "
                + "having sequenceId=" + qEntry.getSequenceId());
          }
        }
        if (previousLevel != Integer.MAX_VALUE) {
          GFToSlf4jBridge bridgeLogger = ((GFToSlf4jBridge) logger);
          bridgeLogger.setLevel(previousLevel);
          bridgeLogger.info("Done hive meta-store initialization");
          previousLevel = Integer.MAX_VALUE;
        }
      // commenting out for snap-585
      /*}*/

      // before initializing regions and possibly waiting for other nodes, allow
      // any waiting GfxdDDLMessage to go through (#47873)
      this.memStore.setInitialDDLReplayPart1Done(true);

      // first populate with any other uninitialized containers (currently
      // global indexes)
      for (GemFireContainer container : this.memStore.getAllContainers()) {
        LocalRegion region = container.getRegion();
        if (region != null && !uninitializedTables.contains(container)
            && !region.isInitialized() && !region.isDestroyed()) {
          uninitializedContainers.add(container);
        }
      }
      for (GemFireContainer container : uninitializedTables) {
        LocalRegion lr = container.getRegion();
        if (lr != null && !lr.isDestroyed() && !lr.isInitialized()
            && this.memStore.findConglomerate(container.getId()) != null) {
          uninitializedContainers.add(container);
        }
      }

      // take DD lock to flush any on-the-wire DDLs at this point else a DROP
      // INDEX, for example, may keep on waiting for node to initialize (#47873)
      // commenting out for snap-585
      /*
      if (!uninitializedContainers.isEmpty()) {
        // release the replay lock at this point since we will have the DD lock
        this.memStore.releaseDDLReplayLock(true);
        acquiredReplayLock = false;
      }
      */

      // run the pre-initialization at this point before recovering indexes
      for (GemFireContainer container : uninitializedContainers) {
        container.preInitializeRegion();
      }

      final GemFireCacheImpl cache = Misc.getGemFireCache();
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      THashMap accountingMap = null;
      if (observer != null && observer.needIndexRecoveryAccounting()) {
        accountingMap = new THashMap();
        for (DiskStoreImpl dsi : cache.listDiskStores()) {
          dsi.TEST_INDEX_ACCOUNTING_MAP = accountingMap;
        }
        observer.setIndexRecoveryAccountingMap(accountingMap);
      }

      this.memStore.markIndexLoadBegin();

      for (DiskStoreImpl dsi : cache.listDiskStores()) {
        long start = 0;
        if (logger.infoEnabled()) {
          start = System.currentTimeMillis();
          logger.info("FabricDatabase: waiting for index loading from "
              + dsi.getName());
        }
        dsi.waitForIndexRecoveryEnd(-1);
        if (logger.infoEnabled()) {
          long end = System.currentTimeMillis();
          logger.info(MessageFormat.format(
              "FabricDatabase: Index loading completed for {0} in {1} ms",
              dsi.getName(), (end - start)));
        }
      }

      // By now the index recovery is done. Also the change owner is done in
      // pre-initialize so before fully initializing the container and hence the
      // underlying region let's do a sanity check on the index size and region size
      // for sorted indexes.
      if (!skipIndexCheck && this.memStore.isPersistIndexes() &&
          this.memStore.getMyVMKind() == GemFireStore.VMKind.DATASTORE) {
        try {
          checkRecoveredIndex(uninitializedContainers, logger, false);
        } catch (RuntimeException ex) {
          logger.info("Runtime exception while doing checkRecoveredIndex ex: " + ex.getMessage(), ex);
          throw ex;
        }
      }

      for (GemFireContainer container : uninitializedContainers) {
        if (logger.infoEnabled() &&
            !Misc.isSnappyHiveMetaTable(container.getSchemaName())) {
          logger.info("FabricDatabase: start initializing container: "
              + container);
        }
        container.initializeRegion();
        if (logger.infoEnabled() &&
            !Misc.isSnappyHiveMetaTable(container.getSchemaName())) {
          logger.info("FabricDatabase: end initializing container: "
              + container);
        }
      }
      
      ddlStmtQueue.clearQueue();
      String currentSchema = lcc.getCurrentSchemaName();
      if (currentSchema == null) {
        currentSchema = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
      }

      // update region initialization that was skipped during DDL replay
      // that also sends the updated profiles as required
      // also execute any other pending operations during replay
      this.memStore.postDDLReplayInitialization(tc);

      // initialize the number of rows in the container
      for (GemFireContainer container : uninitializedContainers) {
        if (GemFireXDUtils.TraceDDLReplay) {
          logger.info("FabricDatabase: start initializing numRows for "
              + container);
        }
        container.initNumRows(container.getRegion());
        if (GemFireXDUtils.TraceDDLReplay) {
          logger.info("FabricDatabase: end initializing numRows for "
              + container);
        }
      }

      if (!lastCurrentSchema.equals(currentSchema)) {
        // restore the default schema
        FabricDatabase.setupDefaultSchema(dd, lcc, tc, currentSchema, true);
      }
      if (!this.memStore.isHadoopGfxdLonerMode()) {
        SystemProcedures.SET_EXPLAIN_SCHEMA(lcc);
      }

      lcc.setIsConnectionForRemote(false);
      lcc.setSkipLocks(false);
      synchronized (sync) {
        this.memStore.setInitialDDLReplayInProgress(false);
        this.memStore.setInitialDDLReplayDone(true);
        // notify any waiters
        sync.notifyAll();
      }

      // release DD read lock only after marking DDL replay in progress as false
      // else an incoming GfxdDDLMessage may be skipped due to DDL replay in
      // progress flag (#44835)
      // commenting out for snap-585
      /*
      if (ddReadLockAcquired) {
        this.dd.unlockAfterReading(null);
        ddReadLockAcquired = false;
      }
      */
      if (logger.infoEnabled()) {
        logger.info("FabricDatabase: initial DDL replay completed.");
      }
      // re-validate self & refresh the auth service after everything is up.
      AuthenticationServiceBase.refreshAuthenticationServices(this, memStore,
          pf, bootProps);
      if (logger.infoEnabled()) {
        logger.info("FabricDatabase: Authentication recheck successful.");
      }

    } finally {
      // commenting out for snap-585
      /*
      if (ddReadLockAcquired) {
        this.dd.unlockAfterReading(null);
        ddReadLockAcquired = false;
      }
      if (acquiredReplayLock) {
        this.memStore.releaseDDLReplayLock(true);
      }
      */
      stmt.close();
      // Setting this to false so that the waiting compactor thread finishes
      this.memStore.setInitialDDLReplayInProgress(false);
    }

    // restore the original schema if required
    if (!ArrayUtils.objectEquals(initSchema, lcc.getCurrentSchemaName())) {
      FabricDatabase.setupDefaultSchema(dd, lcc, tc, initSchema, true);
    }
  }

  private void checkRecoveredIndex(ArrayList<GemFireContainer> uninitializedContainers,
      final LogWriter logger, boolean throwErrorOnMismatch) {
    for (GemFireContainer container : uninitializedContainers) {
      LocalRegion region = container.getRegion();
      DataPolicy dp = region.getDataPolicy();
      if (dp == DataPolicy.PERSISTENT_PARTITION || dp == DataPolicy.PERSISTENT_REPLICATE) {
        GfxdIndexManager gim = (GfxdIndexManager)region.getIndexUpdater();
        if (gim == null || container.isGlobalIndex()) continue;
        int localRegionSize = getAndDumpLocalRegionSize(region, dp, logger, false, throwErrorOnMismatch);
        List<GemFireContainer> allIndexes = gim.getAllIndexes();
        for (GemFireContainer c : allIndexes) {
          if (c.isLocalIndex()) {
            int indexSize = c.getIndexSize();
            if ( indexSize != localRegionSize) {
              if (!throwErrorOnMismatch) {
                logger.warning("checkRecoveredIndex: for table: " + region.getName() + " " +
                    "number of local entries = " + localRegionSize + " and number of " +
                    "index entries in the index: " + c.getName() + " = " + c.getIndexSize());
                localRegionSize = getRegionSizeByIterating(region, dp);
                if (indexSize != localRegionSize) {
                  logger.info("FabricDatabase: index and region out of sync even after iterating." +
                      " Recreating the indexes");
                  // First clear all the indexes
                  clearAllIndexes(uninitializedContainers);
                  recreateAllLocalIndexes(logger);
                  checkRecoveredIndex(uninitializedContainers, logger, true);
                }
              } else {
                localRegionSize = getRegionSizeByIterating(region, dp);
                if (indexSize != localRegionSize) {
                  logger.error("checkRecoveredIndex: for table: " + region.getName() + " " +
                      "number of local entries (after getRegionSizeByIterating) = " + localRegionSize + " and number of " +
                      "index entries in the index: " + c.getName() + " = " + c.getIndexSize());
                  dumpIndexAndRegion(region, dp, c, logger);
                  throw new IllegalStateException("Table data and indexes are not reconciling." +
                      " Probably need to revoke the disk store");
                }
              }
            } else {
              if (logger.fineEnabled()) {
                logger.fine("checkRecoveredIndex: local index: " + c.getName() +
                    " and table: " + region.getName() + " with size: " + localRegionSize);
              }
            }
          }
        }
      }
    }
  }

  private int getRegionSizeByIterating(LocalRegion region, DataPolicy dp) {
    int sz = 0;
    if (dp == DataPolicy.PERSISTENT_PARTITION) {
      DiskStoreImpl ds = region.getDiskStore();
      Collection<AbstractDiskRegion> diskRegions = ds.getAllDiskRegions().values();
      String regionPath = region.getFullPath();
      int prId = ((PartitionedRegion)region).getPRId();
      long regionUUId = region.getRegionUUID();
      for (AbstractDiskRegion diskReg : diskRegions) {
        long parentUUid = diskReg.getUUID();
        // check if pr id matches
        if (parentUUid == regionUUId) {
          // TODO: Better way to find disk regions of global index's buckets?
          if (diskReg.getName().contains("____")) continue;
          RegionMap rmap = diskReg.getRecoveredEntryMap();
          if (rmap != null) {
            Collection<RegionEntry> res = rmap != null ? rmap.regionEntriesInVM() : null;
            for (RegionEntry re : res) {
              if (re.getValueAsToken() != Token.TOMBSTONE) {
                sz++;
              }
            }
          }
        }
      }
    } else {
      //DiskRegion diskReg = region.getDiskRegion();
      RegionMap rmap = region.getRegionMap();
      if (rmap != null) {
        Collection<RegionEntry> res = rmap.regionEntriesInVM();
        for (RegionEntry re : res) {
          if (re.getValueAsToken() != Token.TOMBSTONE) {
            sz++;
          }
        }
      }
    }
    return sz;
  }

  private void dumpIndexAndRegion(LocalRegion region, DataPolicy dp, GemFireContainer index, LogWriter logger) {
    ((MemIndex)index.getConglomerate()).dumpIndex("Dumping all indexes");
    getAndDumpLocalRegionSize(region, dp, logger, true, false);
  }

  private void clearAllIndexes(ArrayList<GemFireContainer> uninitializedContainers) {
    for (GemFireContainer container : uninitializedContainers) {
      LocalRegion region = container.getRegion();
      DataPolicy dp = region.getDataPolicy();
      if (dp == DataPolicy.PERSISTENT_PARTITION || dp == DataPolicy.PERSISTENT_REPLICATE) {
        GfxdIndexManager gim = (GfxdIndexManager)region.getIndexUpdater();
        if (gim == null) continue;
        List<GemFireContainer> allIndexes = gim.getAllIndexes();
        for (GemFireContainer c : allIndexes) {
          if (c.isLocalIndex()) {
            c.getSkipListMap().clear();
            c.resetInitialAccounting();
          }
        }
      }
    }
  }

  private void recreateAllLocalIndexes(final LogWriter logger) {
    Collection<DiskStoreImpl> diskStores = Misc.getGemFireCache().listDiskStores();
    for (DiskStoreImpl ds : diskStores) {
      PersistentOplogSet oplogSet = ds.getPersistentOplogSet(null);
      ds.resetIndexRecoveryState();
      // delete all idx file of all oplogs, so second arg as true below
      ds.scheduleIndexRecovery(oplogSet.getSortedOplogs(), true);
      logger.info("FabricDatabase: recreateAllLocalIndexes " +
          "waiting for index re-creation for disk store: " + ds.getName());
      ds.waitForIndexRecoveryEnd(-1);
      logger.info("FabricDatabase: recreateAllLocalIndexes " +
          "index re-creation for disk store: " + ds.getName() + " ended");
    }
  }

  private int getAndDumpLocalRegionSize(LocalRegion region, DataPolicy dp,
      final LogWriter logger, boolean dump, boolean throwErrorOnMismatch) {
    int sz = 0;
    if (dp == DataPolicy.PERSISTENT_PARTITION) {
      DiskStoreImpl ds = region.getDiskStore();
      Collection<AbstractDiskRegion> diskRegions = ds.getAllDiskRegions().values();
      String regionPath = region.getFullPath();
      int prId = ((PartitionedRegion)region).getPRId();
      long regionUUId = region.getRegionUUID();
      for (AbstractDiskRegion diskReg : diskRegions) {
        long parentUUid = diskReg.getUUID();

        // check if pr id matches
        if (parentUUid == regionUUId) {
          // TODO: Better way to find disk regions of global index's buckets?
          if (diskReg.getName().contains("____")) continue;
          RegionMap rmap = diskReg.getRecoveredEntryMap();
          Collection<RegionEntry> res = rmap != null ? rmap.regionEntriesInVM() : null;
          if (!dump) {
            sz += diskReg.getRecoveredEntryCount();
            int invalidCnt = diskReg.getInvalidOrTombstoneEntryCount();
            sz -= invalidCnt;
          } else {
            logger.info("Dumping key value for region: " + region.getName());
            if (rmap != null) {
              //Collection<RegionEntry> res = rmap.regionEntriesInVM();
              for (RegionEntry re : res) {
                logger.info("reKey=" + re.getKey() + " value=" + re._getValue());
              }
            } else {
              logger.info("rmap is null");
            }
          }
        }
      }
    }
    else {
      DiskRegion diskReg = region.getDiskRegion();
      if (!dump) {
        sz = diskReg.getRecoveredEntryCount();
        sz -= diskReg.getInvalidOrTombstoneEntryCount();
      }
      else {
        logger.info("Dumping key value for region: " + region.getName());
        RegionMap rmap = diskReg.getRecoveredEntryMap();
        if (rmap != null) {
          Collection<RegionEntry> res =  rmap.regionEntriesInVM();
          for(RegionEntry re : res) {
            logger.info("reKey=" + re.getKey()+" value="+re._getValue());
          }
        }
        else {
          logger.info("rmap is null");
        }
      }
    }

    GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
    if (!throwErrorOnMismatch && (observer != null && observer.testIndexRecreate())) {
      // To check whether recreation is happening properly or not.
      logger.info("Returning a wrong size as TEST_INDEX_RECREATE flag is true ");
      return sz+10;
    }
    return sz;
  }

  @Override
  public void cleanupOnError(Throwable e) {
    AuthenticationServiceBase.cleanupOnError(this, memStore,
        pf);
  }
  int previousLevel = Integer.MAX_VALUE;

  public String executeDDL(final DDLConflatable conflatable,
      final Statement stmt, final boolean skipRegionInitialization,
      final EmbedConnection embedConn, String lastCurrentSchema,
      final LanguageConnectionContext lcc, final GemFireTransaction tc,
      final LogWriter logger) throws Exception {
    final String sqlText = conflatable.getValueToConflate();
    String currentSchema = conflatable.getCurrentSchema();
    if (currentSchema == null) {
      currentSchema = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    }
    if (!lastCurrentSchema.equals(currentSchema)) {
      // If the ddl replay for the hive meta tables is in progress
      // whatever may be the logging level, just log the warning messages.
      // This is because hive meta store table replay generates hundreds of
      // line of logs which are of no use. Once the hive meta tables are
      // done, restore the logging level.
      if (previousLevel == Integer.MAX_VALUE &&
          Misc.isSnappyHiveMetaTable(currentSchema))
      {
        GFToSlf4jBridge bridgeLogger = ((GFToSlf4jBridge)logger);
        bridgeLogger.info("Starting hive meta-store initialization");
        previousLevel = bridgeLogger.getLevel();
        bridgeLogger.setLevel(LogWriterImpl.WARNING_LEVEL);
      } else if (previousLevel != Integer.MAX_VALUE &&
            Misc.isSnappyHiveMetaTable(lastCurrentSchema)) {
          GFToSlf4jBridge bridgeLogger = ((GFToSlf4jBridge)logger);
          bridgeLogger.setLevel(previousLevel);
          bridgeLogger.info("Done hive meta-store initialization");
          previousLevel = Integer.MAX_VALUE;
      }
      // set the default schema masquerading as the user
      // temporarily for this DDL
      SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_DDLREPLAY,
          "Setting default schema to " + currentSchema);
      FabricDatabase.setupDefaultSchema(dd, lcc, tc,
          currentSchema, true);
      lastCurrentSchema = currentSchema;
    }
    if (GemFireXDUtils.TraceIndex) {
      if (conflatable.isCreateIndex() || conflatable.isCreateIndex()) {
        GfxdIndexManager.traceIndex("executeDDL::executing "
            + "sqlText=%s and skipRegionInitialization=%s", sqlText,
            skipRegionInitialization);
      }
    }
    try {
      try {
        lcc.setContextObject(conflatable.getAdditionalArgs());
        lcc.setSkipRegionInitialization(skipRegionInitialization);
        lcc.setDroppedFKConstraints(conflatable.getDroppedFKConstraints());
        lcc.setDefaultPersistent(conflatable.defaultPersistent());
        lcc.setPersistMetaStoreInDataDictionary(
            conflatable.persistMetaStoreInDataDictionary());
        tc.setDDLId(conflatable.getId());
        stmt.execute(sqlText);
        GfxdMessage.logWarnings(stmt, sqlText,
            "FabricDatabase: SQL warning in initial replay of DDL: ", logger);
      } finally {
        if (!embedConn.isClosed()) {
          embedConn.commit();
        }
        lcc.setSkipRegionInitialization(false);
        lcc.setContextObject(null);
        lcc.setDroppedFKConstraints(null);
        lcc.setDefaultPersistent(false);
        lcc.setPersistMetaStoreInDataDictionary(true);
        tc.setDDLId(0);
      }
    } catch (Exception ex) {
      boolean ignoreException = false;
      if (ex instanceof SQLException) {
        // #48232: ignore the exception, the schema may have been 
        // created already
        if (("X0Y68".equals(((SQLException)ex).getSQLState()) &&
        conflatable.isCreateSchemaText()) 
        ||
        //#50116: ignore drop FK constraint since we may  
        //not create it during DDL replay (since parent 
        //could also been dropped)
        ("42X86".equals(((SQLException)ex).getSQLState()) &&
        conflatable.isAlterTableDropFKConstraint())
        ) {
          ignoreException = true;
        }
      }
      
      if (ignoreException) {
        if (logger.fineEnabled()) {
          logger.fine("FabricDatabase: ignored exception "
              + "for DDL [" + sqlText + "]. Exception SQLState=" 
              + ((SQLException)ex).getSQLState());
        }
      } else {
        // TODO: use i18n message string
        if (logger.severeEnabled()) {
          logger.severe("FabricDatabase: failed initial replay "
              + "for DDL [" + sqlText + "] due to exception"
              + (ex instanceof SQLException ? " with severity="
                + ((SQLException)ex).getErrorCode() : ""), ex);
        }

        throwBootException(ex, embedConn);
        return null;

      }
    }
    return lastCurrentSchema;
  }

  private void throwBootException(Exception ex, EmbedConnection embedConn)
      throws Exception {
    // If this is a fatal exception then propagate it to fail
    // the boot of database
    // check for system property to allow for boot in this case
    if (!allowBootWithFailures) {
      if (embedConn.isClosed()) {
        throw ex;
      }
      // ignore if this just wraps an SQLException/StandardException that
      // has less than STATEMENT_SEVERITY
      boolean ignoreException = false;
      Throwable t = ex;
      while (t != null) {
        if (t instanceof DerbySQLException) {
          if (((SQLException)t).getErrorCode() >=
              ExceptionSeverity.STATEMENT_SEVERITY) {
            throw ex;
          }
          else {
            ignoreException = true;
          }
        }
        else if (t instanceof StandardException) {
          if (((StandardException)t).getErrorCode() >=
              ExceptionSeverity.STATEMENT_SEVERITY) {
            throw ex;
          }
          else {
            ignoreException = true;
          }
        }
        t = t.getCause();
      }
      if (!ignoreException) {
        throw ex;
      }
    }
  }

  synchronized public void stop() {
    // Clean up GemFireXD MBeans if management was not disabled
    //GfxdManagementService.handleEvent(GfxdResourceEvent.FABRIC_DB__STOP, this.memStore);
    active = false;
    tempDir.deleteAll();
    tempDir = null;
    runtimeStatisticsOn = false;
  }

  /*
   ** Methods related to  ModuleControl
   */

  /*
   * Database interface
   */

  /**
   * Return the engine type that this Database implementation supports. This
   * implementation supports the standard database.
   */
  public int getEngineType() {
    return EngineType.STANDALONE_DB;
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  public boolean isReadOnly() {

    //
    // Notice if no full users?
    // RESOLVE: (Make access factory check?)
    return memStore.isReadOnly();
  }

  public LanguageConnectionContext setupConnection(ContextManager cm,
                                                   String user,
                                                   String authToken,
                                                   String drdaID,
                                                   String dbname,
                                                   long connectionID,
                                                   boolean isRemote)
  throws StandardException {

    final TransactionController tc = getConnectionTransaction(cm, connectionID);
    cm.setLocaleFinder(this);
    pushDbContext(cm);

    // push a database shutdown context
    // we also need to push a language connection context.
    LanguageConnectionContext lctx = lcf.newLanguageConnectionContext(cm, tc,
        lf, this, user, authToken, drdaID, connectionID, isRemote, dbname);

    // push the context that defines our class factory
    pushClassFactoryContext(cm, lcf.getClassFactory());

    // we also need to push an execution context.
    ExecutionFactory ef = lcf.getExecutionFactory();

    ef.newExecutionContext(cm);

    // Initialize our language connection context. Note: This is
    // a bit of a hack. Unfortunately, we can't initialize this
    // when we push it. We first must push a few more contexts.
    lctx.initialize();

    SchemaDescriptor defaultSchema = lctx.getDefaultSchema();
    // check if the user schema is a proper one else create a proper schema for
    // the user on the fly and add to DataDictionary
    if (defaultSchema.getUUID() == null) {
      setupDefaultSchema(this.dd, lctx, tc, lctx.getAuthorizationId(), false);
    }

    // Need to commit this to release locks gotten in initialize.
    // Commit it but make sure transaction not have any updates.
    lctx.internalCommitNoSync(TransactionController.RELEASE_LOCKS
        | TransactionController.READONLY_TRANSACTION_INITIALIZATION);

    return lctx;
  }

  /**
   * Setup the default schema for the given "defaultSchema" creating it if necessary.
   */
  public static void setupDefaultSchema(DataDictionary dd,
      LanguageConnectionContext lcc, TransactionController tc, String in_defaultSchema,
      boolean doInitialLookup) throws StandardException {
    SchemaDescriptor defaultSchema = null;
    if (doInitialLookup) {
      defaultSchema = dd.getSchemaDescriptor(in_defaultSchema, tc, false);
    }
    if (defaultSchema == null) {
      defaultSchema = new SchemaDescriptor(dd, in_defaultSchema, in_defaultSchema, dd
          .getUUIDFactory().createUUID(), false);
      try {
        dd.addDescriptor(defaultSchema, null,
            DataDictionary.SYSSCHEMAS_CATALOG_NUM, false, tc);
      } catch (StandardException ex) {
        if ("X0Y68".equals(ex.getSQLState())) {
          // if someone else beat us in creating the schema, pick up
          // the existing one
          defaultSchema = dd.getSchemaDescriptor(in_defaultSchema, tc, true);
        }
        else {
          throw ex;
        }
      }
    }

    lcc.setDefaultSchema(defaultSchema);
  }

  /**
   * Return the DataDictionary for this database, set up at boot time.
   */
  public final GfxdDataDictionary getDataDictionary() {
    return dd;
  }

  public final DataValueFactory getDataValueFactory() {
    return this.dataValueFactory;
  }

  public final LogFactory getLogFactory() {
    return this.logFactory;
  }

  public final LanguageConnectionFactory getConnectionFactory() {
    return this.lcf;
  }

  public final ClassFactory getClassFactory() {
    return this.cfDB;
  }

  public void pushDbContext(ContextManager cm) {
    /* We cache the locale in the DatabaseContext
     * so that the Datatypes can get to it easily.
     */
    new DatabaseContextImpl(cm, this);
  }

  public final AuthenticationService getAuthenticationService() {

    // Expected to find one - Sanity check being done at
    // DB boot-up.

    // We should have a Authentication Service
    //
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(
        this.authenticationService != null,
        "Unexpected - There is no valid authentication service for the database!");
    }

    return this.authenticationService;
  }

  /**
   * @throws com.gemstone.gemfire.cache.CacheClosedException if store is null
   * @return
   */
  public static AuthenticationServiceBase getAuthenticationServiceBase() {
    return (AuthenticationServiceBase)Monitor.findServiceModule(
        Misc.getMemStoreBooting().getDatabase(), AuthenticationService.MODULE,
        GfxdConstants.AUTHENTICATION_SERVICE);
  }

  public final AuthenticationService getPeerAuthenticationService() {

    // Expected to find one - Sanity check being done at
    // DB boot-up.

    // We should have a Authentication Service
    //
    if (SanityManager.DEBUG) {
      SanityManager.ASSERT(
        this.peerAuthenticationService != null,
        "Unexpected - There is no valid peer authentication service for the database!");
    }

    return this.peerAuthenticationService;
  }

  /**
   * DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void freeze() throws SQLException {

    try {
      memStore.freeze();
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void unfreeze() throws SQLException {

    try {
      memStore.unfreeze();
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @param  backupDir  DOCUMENT ME!
   * @param  wait  DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void backup(String backupDir, boolean wait) throws SQLException {

    try {
      memStore.backup(backupDir, wait);
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @param  backupDir  DOCUMENT ME!
   * @param  deleteOnlineArchivedLogFiles  DOCUMENT ME!
   * @param  wait  DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void backupAndEnableLogArchiveMode(
    String backupDir,
    boolean deleteOnlineArchivedLogFiles,
    boolean wait) throws SQLException {

    try {
      memStore.backupAndEnableLogArchiveMode(backupDir,
                                       deleteOnlineArchivedLogFiles,
                                       wait);
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @param  deleteOnlineArchivedLogFiles  DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void disableLogArchiveMode(boolean deleteOnlineArchivedLogFiles)
  throws SQLException {

    try {
      memStore.disableLogArchiveMode(deleteOnlineArchivedLogFiles);
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @throws  SQLException  DOCUMENT ME!
   */
  public void checkpoint() throws SQLException {

    try {
      memStore.checkpoint();
    }
    catch (StandardException se) {
      throw PublicAPI.wrapStandardException(se);
    }
  }

  /**
   * Methods from com.pivotal.gemfirexd.internal.database.Database
   *
   * @return  DOCUMENT ME!
   */
  public final Locale getLocale() {
    return databaseLocale;
  }

  /**
   * Return the UUID of this database.
   */
  public final UUID getId() {
    return myUUID;
  }

  /* LocaleFinder methods */

  /**
   * @exception  StandardException  Thrown on error
   */
  public final Locale getCurrentLocale() throws StandardException {
    if (this.databaseLocale != null) {
      return this.databaseLocale;
    }
    throw noLocale();
  }

  /**
   * @exception  StandardException  Thrown on error
   */
  public final DateFormat getDateFormat() throws StandardException {
    if (this.dateFormat != null) {
      return this.dateFormat;
    }
    throw noLocale();
  }

  /**
   * @exception  StandardException  Thrown on error
   */
  public final DateFormat getTimeFormat() throws StandardException {
    if (this.timeFormat != null) {
      return this.timeFormat;
    }
    throw noLocale();
  }

  /**
   * @exception  StandardException  Thrown on error
   */
  public DateFormat getTimestampFormat() throws StandardException {
    if (this.timestampFormat != null) {
      return this.timestampFormat;
    }
    throw noLocale();
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  private static StandardException noLocale() {
    return StandardException.newException(SQLState.NO_LOCALE);
  }

  public final void setLocale(final Locale locale) {
    databaseLocale = locale;
    dateFormat = DateFormat.getDateInstance(DateFormat.LONG, databaseLocale);
    timeFormat = DateFormat.getTimeInstance(DateFormat.LONG, databaseLocale);
    timestampFormat = DateFormat.getDateTimeInstance(DateFormat.LONG,
        DateFormat.LONG, databaseLocale);
  }

  /**
   * Is the database active (open).
   */
  public final boolean isActive() {
    return active;
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  startParams  DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected UUID makeDatabaseID(boolean create, Properties startParams)
      throws StandardException {

    String upgradeID = null;
    UUID databaseID;
    if ((databaseID = (UUID)memStore.getProperty(DataDictionary.DATABASE_ID)) ==
        null) {
      // no property defined in the Transaction set
      // this could be an upgrade, see if it's stored in the service set

      UUIDFactory uuidFactory = Monitor.getMonitor().getUUIDFactory();

      upgradeID = startParams.getProperty(DataDictionary.DATABASE_ID);

      if (upgradeID == null) {

        // just create one
        databaseID = uuidFactory.createUUID();
      }
      else {
        databaseID = uuidFactory.recreateUUID(upgradeID);
      }

      memStore.setProperty(DataDictionary.DATABASE_ID, databaseID, true);
    }

    // Remove the database identifier from the service.properties
    // file only if we upgraded it to be stored in the transactional
    // property set.
    if (upgradeID != null) {
      startParams.remove(DataDictionary.DATABASE_ID);
    }

    return databaseID;
  }

  /*
   ** Return an Object instead of a ResourceAdapter
   ** so that XA classes are only used where needed;
   ** caller must cast to ResourceAdapter.
   */
  public Object getResourceAdapter() {
    return resourceAdapter;
  }

  /*
   ** Methods of PropertySetCallback
   */
  public void init(boolean dbOnly, Dictionary p) {
    // not called yet ...
  }

  /**
   * @see  PropertySetCallback#validate
   *
   * @exception  StandardException  Thrown on error.
   */
  public boolean validate(String key, Serializable value, Dictionary p)
  throws StandardException {

    //
    // Disallow setting static creation time only configuration properties
    if (key.equals(EngineType.PROPERTY)) {
      throw StandardException.newException(SQLState.PROPERTY_UNSUPPORTED_CHANGE,
                                           key,
                                           value);
    }

    if (Property.STATEMENT_EXPLAIN_MODE.equals(key)
        || Property.STATISTICS_SUMMARY_MODE.equals(key)
        || Property.STATEMENT_STATISTICS_MODE.equals(key)) {
      return true;
    }

    // only interested in the classpath
    if (!key.equals(Property.DATABASE_CLASSPATH)) {
      return false;
    }

    String newClasspath = (String) value;
    String[][] dbcp = null; // The parsed dbclasspath

    if (newClasspath != null) {

      // parse it when it is set to ensure only valid values
      // are written to the actual conglomerate.
      dbcp = IdUtil.parseDbClassPath(newClasspath);
    }

    //
    // Verify that all jar files on the database classpath are in the data
    // dictionary.
    if (dbcp != null) {

      for (int ix = 0; ix < dbcp.length; ix++) {
        SchemaDescriptor sd = dd.getSchemaDescriptor(
                                dbcp[ix][IdUtil.DBCP_SCHEMA_NAME],
                                null,
                                false);

        FileInfoDescriptor fid = null;

        if (sd != null) {
          fid = dd.getFileInfoDescriptor(sd,
                                         dbcp[ix][IdUtil.DBCP_SQL_JAR_NAME]);
        }

        if (fid == null) {
          throw StandardException.newException(
            SQLState.LANG_DB_CLASS_PATH_HAS_MISSING_JAR,
            IdUtil.mkQualifiedName(dbcp[ix]));
        }
      }
    }

    return true;
  }

  /**
   * @see  PropertySetCallback#apply
   *
   * @exception  StandardException  Thrown on error.
   */
  public Serviceable apply(String key, Serializable value, Dictionary p)
  throws StandardException {

    if (Property.STATEMENT_EXPLAIN_MODE.equals(key)
        || Property.STATISTICS_SUMMARY_MODE.equals(key)
        || Property.STATEMENT_STATISTICS_MODE.equals(key)) {
      if(value != null) {
        DistributionObserver.setObserver();
      }
      else {
        DistributionObserver.unsetObserver();
      }
      return null;
    }

    // only interested in the classpath
    if (!key.equals(Property.DATABASE_CLASSPATH)) {
      return null;
    }

    // only do the change dynamically if we are already
    // a per-database classapath.
    if (cfDB != null) {

      //
      // Invalidate stored plans.
      getDataDictionary().invalidateAllSPSPlans();

      String newClasspath = (String) value;

      if (newClasspath == null) {
        newClasspath = "";
      }

      cfDB.notifyModifyClasspath(newClasspath);
    }

    return null;
  }

  /**
   * @see  PropertySetCallback#map
   */
  public Serializable map(String key, Serializable value, Dictionary p) {
    return null;
  }

  /**
   * methods specific to this class
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected void createFinished() throws StandardException {

    // find the access factory and tell it that database creation has
    // finished
    memStore.createFinished();
  }

  /**
   * DOCUMENT ME!
   *
   * @param  startParams  DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  protected String getClasspath(Properties startParams) {
    String cp = PropertyUtil.getPropertyFromSet(startParams,
                                                Property.DATABASE_CLASSPATH);

    if (cp == null) {
      cp = PropertyUtil.getSystemProperty(Property.DATABASE_CLASSPATH, "");
    }

    return cp;
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  startParams  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected void bootClassFactory(boolean create, Properties startParams)
  throws StandardException {
    String classpath = getClasspath(startParams);

    // parse the class path and allow 2 part names.
    IdUtil.parseDbClassPath(classpath);

    startParams.put(Property.BOOT_DB_CLASSPATH, classpath);
    cfDB = (ClassFactory) Monitor.bootServiceModule(
             create,
             this,
             com.pivotal.gemfirexd.internal.iapi.reference.Module.ClassFactory,
             startParams);
  }

  /**
   * Get or create a new transaction associated with given connection ID.
   *
   * @param cm
   *          the current ContextManager
   */
  protected TransactionController getConnectionTransaction(ContextManager cm,
      long connectionID) throws StandardException {
    // start a local transaction
    return this.memStore.getTransaction(cm, connectionID);
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  props  DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected AuthenticationService bootAuthenticationService(boolean create,
                                                            Properties props)
  throws StandardException {

    peerAuthenticationService = (AuthenticationService)Monitor.bootServiceModule(create, this, AuthenticationService.MODULE,
        GfxdConstants.PEER_AUTHENTICATION_SERVICE, props);

    assert peerAuthenticationService instanceof AuthenticationServiceBase;

    AuthenticationServiceBase.setPeerAuthenticationService((AuthenticationServiceBase)peerAuthenticationService);

    return (AuthenticationService)Monitor.bootServiceModule(create, this,
        AuthenticationService.MODULE, GfxdConstants.AUTHENTICATION_SERVICE, props);
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  startParams  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected void bootValidation(boolean create, Properties startParams)
  throws StandardException {

    String tempDir = startParams.getProperty(Property.STORAGE_TEMP_DIRECTORY,
        PropertyUtil.getSystemProperty(Property.STORAGE_TEMP_DIRECTORY));

    if (tempDir == null) {
      tempDir = startParams.getProperty(Property.SYSTEM_HOME_PROPERTY,
          PropertyUtil.getSystemProperty(Property.SYSTEM_HOME_PROPERTY));

      if (tempDir == null) {
        tempDir = startParams.getProperty(Attribute.SYS_PERSISTENT_DIR,
            PropertyUtil
                .getSystemProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));
        if (tempDir == null) {
          tempDir = PropertyUtil.getSystemProperty("java.io.tmpdir");
        }
      }
    }

    try {

      final String tDir = tempDir;
      this.tempDir = java.security.AccessController
          .doPrivileged(new PrivilegedExceptionAction<DirFile>() {
            @Override
            public DirFile run() throws IOException {

              int retry = 0;
              do {
                final int rl = PartitionedRegion.rand
                    .nextInt(Integer.MAX_VALUE);
                final DirFile df = new DirFile(tDir, TEMP_DIR_PREFIX
                    + Integer.toString(rl) + ".d");
                df.deleteOnExit();
                if (df.mkdirs()) {
                  assert df.canWrite();
                  return df;
                }
              } while (retry++ < FileUtil.MAX_FILE_CREATE_RETRY);

              throw new IOException("Temp Directory couldn't be created on "
                  + tDir);
            }
          });
    } catch (java.security.PrivilegedActionException pae) {
      final Exception ioe = pae.getException();
      if (ioe instanceof IOException) {
        throw StandardException.newException(SQLState.LOG_SEGMENT_NOT_EXIST,
            ioe);
      }
      throw GemFireXDRuntimeException.newRuntimeException(
          "PrivilegedActionException", pae.getException());
    }
    this.tempDir.deleteOnExit();

    pf = (PropertyFactory) Monitor.bootServiceModule(
           create,
           this,
           com.pivotal.gemfirexd.internal.iapi.reference.Module.PropertyFactory,
           startParams);
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  startParams  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  protected void bootStore(boolean create, Properties startParams)
      throws StandardException {

    try {
      this.memStore = (GemFireStore)Monitor.bootServiceModule(create, this,
          AccessFactory.MODULE, startParams);
    }
    catch(StandardException se) {
      cleanupOnError(se);
      throw se;
    }
  }

  /**
   * Get the set of database properties from the set stored on disk outside of
   * service.properties.
   */
  protected Properties getAllDatabaseProperties() throws StandardException {
    return memStore.getProperties();
  }

  /**
   * DOCUMENT ME!
   *
   * @param  create  DOCUMENT ME!
   * @param  allParams  DOCUMENT ME!
   */
  protected void bootResourceAdapter(boolean create, Properties allParams) {

    // Boot resource adapter - only if we are running Java 2 or
    // beyondwith JDBC20 extension, JTA and JNDI classes in the classpath
    //
    // assume if it doesn't boot it was because the required
    // classes were missing, and continue without it.
    // Done this way to work around Chai's need to preload
    // classes.
    // Assume both of these classes are in the class path.
    // Assume we may need a ResourceAdapter since we don't know how
    // this database is going to be used.
    try {
      resourceAdapter = Monitor.bootServiceModule(
          create,
          this,
          com.pivotal.gemfirexd.internal.iapi.reference.Module.ResourceAdapter,
          allParams);
    }
    catch (StandardException mse) {
      // OK, resourceAdapter is an optional module
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @param  cm  DOCUMENT ME!
   * @param  cf  DOCUMENT ME!
   */
  protected void pushClassFactoryContext(ContextManager cm, ClassFactory cf) {
    new StoreClassFactoryContext(cm, cf, memStore, this);
  }

  /*
   ** Methods of JarReader
   */
  public StorageFile getJarFile(String schemaName, String sqlName)
  throws StandardException {

    SchemaDescriptor sd = dd.getSchemaDescriptor(schemaName, null, true);
    FileInfoDescriptor fid = dd.getFileInfoDescriptor(sd, sqlName);

    if (fid == null) {
      throw StandardException.newException(SQLState.LANG_FILE_DOES_NOT_EXIST,
                                           sqlName,
                                           schemaName);
    }

    long generationId = fid.getGenerationId();
    final FileResource fr = memStore.getJarFileHandler();

//    String externalName = JarUtil.mkExternalName(schemaName,
//                                                 sqlName,
//                                                 fr.getSeparatorChar());

    String externalName = schemaName + "." + sqlName;
    return fr.getAsFile(externalName, generationId);
  }

  // New methods added in Database interface in Derby 10.4.1.3
  /**
   * Start failover for the given database.
   *
   * @param dbname the replication database that is being failed over.
   *
   * @exception StandardException 1) If the failover succeeds, an exception
   *                                 is thrown to indicate that the master
   *                                 database was shutdown after a successful
   *                                 failover
   *                              2) If a failure occurs during network
   *                                 communication with slave.
   */
  public void failover(String dbname) throws StandardException {
   throw new UnsupportedOperationException();
  }

  /**
   * Used to indicated whether the database is in the replication
   * slave mode.
   *
   * @return true if this database is in replication slave mode,
   *         false otherwise.
   */
  public boolean isInSlaveMode() {
   throw new UnsupportedOperationException();
  }

  /**
   * Returns false if statement matching optimization is turned on (default)
   * else true.
   */
  public boolean disableStatementOptimizationToGenericPlan() {
    return disableStatementOptimization;
  }

  public void setdisableStatementOptimizationToGenericPlan() {
    this.disableStatementOptimization = true;
  }

  /**
   * Stop the replication slave role for the given database.
   *
   * @exception SQLException Thrown on error
   */
  public void stopReplicationSlave() throws SQLException {
   throw new UnsupportedOperationException();
  }

  /**
   * Start the replication master role for this database
   * @param dbmaster The master database that is being replicated.
   * @param host The hostname for the slave
   * @param port The port the slave is listening on
   * @param replicationMode The type of replication contract.
   * Currently only asynchronous replication is supported, but
   * 1-safe/2-safe/very-safe modes may be added later.
   * @exception SQLException Thrown on error
   */
  public void startReplicationMaster(String dbmaster,
                                     String host,
                                     int port,
                                     String replicationMode)
  throws SQLException {
   throw new UnsupportedOperationException();
  }

  /**
   * Stop the replication master role for the given database.
   *
   * @exception SQLException Thrown on error
   */
  public void stopReplicationMaster() throws SQLException {
   throw new UnsupportedOperationException();
  }

  @Override
  public LanguageConnectionContext setupConnection(ContextManager cm,
      String user, String drdaID, String dbname) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public final DirFile getTempDir() {
    return tempDir;
  }

  /*
  /**
   * Called by the XPLAINFactory once it determines what level of statistics
   * needs to be enabled.
   *
   *
  private synchronized void refreshExistingConnections() {

    if (runtimeStatisticsOn) {
      DistributionObserver.setObserver();
    }

    Iterator<ContextManager> contextIter = ContextService.getFactory()
        .getAllContexts().iterator();

    LanguageConnectionContext lcc = null;

    while (contextIter.hasNext()) {

      ContextManager cm = contextIter.next();

      lcc = (LanguageConnectionContext)cm.getContext(ContextId.LANG_CONNECTION);
      if (lcc == null) {
        continue;
      }

      lcc.setRunTimeStatisticsMode(runtimeStatisticsOn, false);
      if (runtimeStatisticsOn) {
        for (Iterator<XPLAINTableDescriptor> it = XPLAINTableDescriptor
            .getRegisteredDescriptors(); it.hasNext();) {
          XPLAINTableDescriptor t = it.next();
          lcc.setExplainStatement(t.getCatalogName(), t.getTableInsert());
        }
      }

    } // end of connection list.

    if (!runtimeStatisticsOn) {
      DistributionObserver.unsetObserver();
    }
  }
  */

  public final boolean getRuntimeStatistics() {
    return runtimeStatisticsOn;
  }

  public static final void __setRuntimeStatistics(LanguageConnectionContext lcc, boolean onOff) {
    if (onOff) {
      DistributionObserver.setObserver();
    }

    lcc.setRunTimeStatisticsMode(onOff, false);

    if (onOff) {
      for (Iterator<XPLAINTableDescriptor> it = XPLAINTableDescriptor
          .getRegisteredDescriptors(); it.hasNext();) {
        XPLAINTableDescriptor t = it.next();
        lcc.setExplainStatement(t.getCatalogName(), t.getTableInsert());
      }
    }

    if (!onOff) {
      DistributionObserver.unsetObserver();
    }
  }
}
