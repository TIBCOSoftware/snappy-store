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
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.admin.AdminException;
import com.gemstone.gemfire.admin.jmx.Agent;
import com.gemstone.gemfire.admin.jmx.AgentConfig;
import com.gemstone.gemfire.admin.jmx.AgentFactory;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ClassPathLoader;
import com.gemstone.gemfire.internal.GemFireLevel;
import com.gemstone.gemfire.internal.HostStatSampler.StatsSamplerCallback;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.StatisticsTypeFactoryImpl;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TLongHashSet;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.NetworkInterface;
import com.pivotal.gemfirexd.internal.GemFireXDVersion;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryTimeStatistics;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.SigThreadDumpHandler;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.PropertyConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemOperation;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.db.IndexPersistenceStats;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.CallbackProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.diag.DiskStoreIDs;
import com.pivotal.gemfirexd.internal.engine.distributed.DistributedConnectionCloseExecutorFunction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction;
import com.pivotal.gemfirexd.internal.engine.distributed.message.PersistentStateInRecoveryMode;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl.NetworkInterfaceImpl;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.DefaultGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.procedure.DistributedProcedureCallFunction;
import com.pivotal.gemfirexd.internal.engine.sql.conn.ConnectionSignaller;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.DistributionObserver;
import com.pivotal.gemfirexd.internal.engine.sql.execute.IdentityValueManager;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.GFContainerLocking;
import com.pivotal.gemfirexd.internal.engine.ui.SnappyRegionStatsCollectorFunction;
import com.pivotal.gemfirexd.internal.hadoop.HadoopGfxdLonerConfig;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockFactory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PersistentSet;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyFactory;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactoryGlobals;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.MethodFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.services.stream.GfxdHeaderPrintWriterImpl;
import com.pivotal.gemfirexd.internal.impl.services.stream.SingleStream;
import com.pivotal.gemfirexd.internal.impl.store.raw.data.GfxdJarResource;
import com.pivotal.gemfirexd.internal.impl.store.raw.xact.TransactionTable;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;

import static com.gemstone.gemfire.distributed.internal.InternalLocator.FORCE_LOCATOR_DM_TYPE;

/**
 * The underlying store implementation that provides methods to create container
 * for tables, schemas while maintaining the mapping from container IDs to their
 * {@link MemConglomerate}s. Also provides for some other useful miscellaneous
 * methods.
 * 
 * @author Eric Zoerner
 * @author swale
 * @author rdubey
 */
@SuppressWarnings("deprecation")
public final class GemFireStore implements AccessFactory, ModuleControl,
    ModuleSupportable, PersistentSet {

  /** The module name for this Store */
  public static final String MODULE =
    "com.pivotal.gemfirexd.internal.engine.store.GemFireStore";

  /** Implementation ID as required by the "gemfirexd.access" property */
  public static final String IMPLEMENTATIONID = "memstore";

  /** Internal test object to add expected exceptions during startup. */
  public static Object[] EXPECTED_STARTUP_EXCEPTIONS;

  /**
   * Hidden region that is used for DDL string puts for DDL statement replay on
   * new servers.
   */
  public final static String DDL_STMTS_REGION = SystemProperties.DDL_STMTS_REGION;

  private static final Pattern ILLEGAL_DISKDIR_CHARS_PATTERN =
      Pattern.compile("[*?<>|;]");

  private static InternalDistributedMember selfMemId = null;

  /** Static singleton instance of GemFireStore that is currently booting. */
  private static GemFireStore bootingInstance = null;

  /** Static booted up singleton instance of GemFireStore. */
  private static GemFireStore bootedInstance = null;

  /** The current conglomerate ID. */
  private final AtomicLong nextConglomId;

  /** Map of container IDs to GemFireContainers */
  private final ConcurrentHashMap<ContainerKey, MemConglomerate> conglomerates;

  /** Hash table on primary implementation type. */
  private final ConcurrentHashMap<String, MethodFactory> implhash;

  /** The instance of GemFireCache used by this store */
  private GemFireCacheImpl gemFireCache;

  /**
   * if boot is stuck in bootRegion() for persistence sync, and then gets an
   * exception then stopping it will cause a hang in Cache.close() so skip
   * latter
   */
  private boolean skipCacheClose;

  private Agent gemFireAgent;

  /** The instance of FabricDatabase used by this store */
  private FabricDatabase database;

  /** The instance of the threshold listener */
  private GfxdHeapThresholdListener thresholdListener;

  /** The instance of the ObjectSizer */
  private ObjectSizer gfxdObjectSizer;

  /**
   * This protects against race in getting the first connection that leads to
   * creation of database with subsequent replay of DDL, and new GfxdMessages
   * trying a obtain a new connection and execute DDL. The first connection
   * takes a writer lock while other connections take a reader lock.
   */
  private final ReadWriteLock ddlReplayLock;

  /** Keep track of whether initial DDL replay is in progress. */
  private volatile boolean ddlReplayInProgress;

  /** Keep track of whether initial DDL replay has been completed. */
  private volatile boolean ddlReplayDone;

  /** Keep track of whether basic first part of DDL replay has been completed. */
  private volatile boolean ddlReplayPart1Done;

  /**
   * Indicates whether to DDL replay is waiting on DD read lock.
   */
  private volatile boolean ddlReplayWaiting;

  /** used to signal any waiting threads when DDL replay is complete */
  private final Object ddlReplaySync = new Object();

  /**
   * The set of IDs of DDL statements that have been processed so far. This is
   * maintained in order to detect any duplicates between initial DDL replay and
   * GfxdDDLMessage's.
   */
  private final TLongHashSet ddlIDsProcessedSet;

  /**
   * The {@link GfxdDDLRegionQueue} that holds the DDL puts in order. We assume
   * a single instance of GemFireStore, so this is a static.
   */
  private volatile GfxdDDLRegionQueue ddlStmtQueue;

  /**
   * The GemFireXD distributed reader-writer lock service used for protecting
   * the DataDictionary and synchronizing the DDLs/DMLs to make the DDL
   * executions atomic.
   */
  private GfxdDRWLockService ddlLockService;

  /**
   * Set of conglomerates whose regions were not initialized for DML ops during
   * initial DDL replay.
   */
  private final LinkedHashMap<ContainerKey, MemConglomerate>
      uninitializedConglomerates;

  /**
   * List of pending operations that need to be executed at the end of DDL
   * replay.
   */
  private final ArrayList<MemOperation> ddlReplayPendingOperations;

  /**
   * The {@link VMKind} of this VM.
   */
  private volatile VMKind myKind;

  /**
   * The DistributionAdvisee for GemFireXD used to propagate the GemFireXD
   * {@link VMKind} and server groups to other VMs.
   */
  private final StoreAdvisee advisee;

  /**
   * Service properties. These are supplied from ModuleControl.boot(), and
   * ultimately come from the service.properties file. By convention, these
   * properties are passed down to all modules booted by this one. If this
   * module needs to pass specific instructions to its sub-modules, it should
   * create a new Properties object with serviceProperties as its default (so
   * that the rest of the modules that are looking at it don't see the
   * properties that this module needs to add).
   */
  private final Properties serviceProperties;
  private final Properties servicePropertiesDefaults;

  /**
   * The object providing the properties like behaviour that is transactional.
   */
  private PropertyConglomerate xactProperties;

  /** PropertyFactory used for validation. */
  private PropertyFactory pf;

  /** The default disk-store used for application tables, queues etc. */
  private DiskStoreImpl gfxdDefaultDiskStore;

  /**
   * Set to true if the DataDictionary is persisted to disk.
   */
  private boolean persistingDD;

  private boolean persistIndexes = true;

  private String persistenceDir = null;
  
  private String hdfsRootDir = null;

  private volatile boolean tableDefaultPartitioned;

  private GfxdJarResource fileHandler;

  private TransactionTable ttab;
  
  private String dbLocaleStr;

  private volatile LocalRegion identityRegion;

  // Embedded GFXD loner instance which will be 
  // instantiated by MR jobs or PXF service. 
  private boolean isHadoopGfxdLonerMode = false;
  private HadoopGfxdLonerConfig hadoopGfxdLonerConfig = null;
  /**
   * This is enabled by the admin api to indicate
   * the vm is going down as part of shutdown-all
   * message instead of individual stop.
   */
  private volatile boolean isShutdownAll = false;

  private THashMap uuidToIdMap;

  private final StoreStatistics storeStats;
  
  private final IndexPersistenceStats indexPersistenceStats;

  private final long createTime;

  /**
   * Not keeping as volatile as the expectation is that this field
   * should be set as soon as the first embedded connection is created
   * and will not change ever.
   */
  private volatile ExternalCatalog externalCatalog;

  private volatile Future<?> externalCatalogInit;

  public static final ThreadLocal<Boolean> externalCatalogInitThread =
      new ThreadLocal<>();

  private Region<String, String> snappyMetadataCmdRgn;

  /**
   *************************************************************************
   * Public Methods implementing AccessFactory Interface
   *************************************************************************
   */

  public GemFireStore() {
    GemFireCacheImpl.FactoryStatics.init();
    this.nextConglomId = new AtomicLong(-1);
    this.conglomerates = new ConcurrentHashMap<ContainerKey, MemConglomerate>();
    this.implhash = new ConcurrentHashMap<String, MethodFactory>();
    this.ddlReplayLock = new ReentrantReadWriteLock();
    this.ddlReplayInProgress = false;
    this.ddlReplayDone = false;
    this.ddlIDsProcessedSet = new TLongHashSet();
    this.uninitializedConglomerates =
      new LinkedHashMap<ContainerKey, MemConglomerate>();
    this.ddlReplayPendingOperations = new ArrayList<MemOperation>();
    this.servicePropertiesDefaults = new Properties();
    this.serviceProperties = new Properties(this.servicePropertiesDefaults);
    this.advisee = new StoreAdvisee();
    this.storeStats = new StoreStatistics();
    this.indexLoadSync = new Object();
    this.indexLoadBegin = false;
    this.indexPersistenceStats = new IndexPersistenceStats();
    this.createTime = System.currentTimeMillis();
  }

  /**
   * Database creation finished. Tell RawStore.
   * 
   * @exception StandardException
   *              standard Derby error policy
   */
  public void createFinished() throws StandardException {
  }

  public long getCreateTime() {
    return this.createTime;
  }

  /**
   * Find an access method that implements an implementation type.
   * 
   * @see AccessFactory#findMethodFactoryByImpl
   */
  public MethodFactory findMethodFactoryByImpl(String impltype)
      throws StandardException {

    // See if there's an access method that supports the desired
    // implementation type as its primary implementation type.
    MethodFactory factory = this.implhash.get(impltype);
    if (factory != null) {
      return factory;
    }
    // No primary implementation. See if one of the access methods
    // supports the implementation type as a secondary.
    for (MethodFactory fact : this.implhash.values()) {
      if (fact.supportsImplementation(impltype)) {
        return fact;
      }
    }
    // try and load an implementation. a new properties object needs
    // to be created to hold the conglomerate type property, since
    // that value is specific to the conglomerate we want to boot, not
    // to the service as a whole
    Properties conglomProperties = new Properties(this.serviceProperties);
    conglomProperties.put(AccessFactoryGlobals.CONGLOM_PROP, impltype);
    try {
      factory = (MethodFactory)Monitor.bootServiceModule(false, this,
          MethodFactory.MODULE, impltype, conglomProperties);
    } catch (StandardException se) {
      if (!se.getMessageId().equals(SQLState.SERVICE_MISSING_IMPLEMENTATION)) {
        throw se;
      }
    }
    conglomProperties = null;
    if (factory != null) {
      registerAccessMethod(factory);
      return factory;
    }
    // No such implementation.
    return null;
  }

  public LockFactory getLockFactory() {
    // not used by GemFireXD
    return null;
  }

  public final TransactionController getTransaction(ContextManager cm,
      long connectionID) throws StandardException {
    return getAndNameTransaction(cm, AccessFactoryGlobals.USER_TRANS_NAME,
        connectionID);
  }

  public final TransactionController getTransaction(final ContextManager cm)
      throws StandardException {
    return getAndNameTransaction(cm, AccessFactoryGlobals.USER_TRANS_NAME);
  }

  public final TransactionController getAndNameTransaction(ContextManager cm,
      String transName, long connectionID) throws StandardException {
    assert cm != null;
    // Create or find a raw store transaction, make a context for it, and push
    // the context. Note this puts the raw store transaction context above the
    // access context, which is required for error handling assumptions to be
    // correct.
    final GemFireTransaction tran = GemFireTransaction.findUserTransaction(cm,
        transName,   connectionID);
    assert tran != null;
    return tran;
  }

  public void registerAccessMethod(MethodFactory factory) {
    // Put the access method's primary implementation type in
    // a hash table so we can find it quickly.
    this.implhash.put(factory.primaryImplementationType(), factory);
  }

  public boolean isReadOnly() {
    return false;
  }

  /*
   *************************************************************************
   * End Public Methods implementing AccessFactory Interface
   *************************************************************************
   */

  public static GemFireStore getBootingInstance() {
    return bootingInstance;
  }

  public final static GemFireStore getBootedInstance() {
    return bootedInstance;
  }

  public GemFireCacheImpl getGemFireCache() {
    return this.gemFireCache;
  }
  
  public Agent getGemFireAgent() {
    return this.gemFireAgent;
  }

  public ObjectSizer getObjectSizer() {
    return this.gfxdObjectSizer;
  }

  /**
   * Generate the next ID to be used for a new Conglomerate.
   */
  public long getNextConglomId() {
    return this.nextConglomId.incrementAndGet();
  }

  /** Find Conglomerate with the given ID. */
  public MemConglomerate findConglomerate(ContainerKey id) {
    final MemConglomerate conglom = this.conglomerates.get(id);
    if (GemFireXDUtils.TraceConglomRead) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
          "GemFireStore#findConglomerate: returning conglomerate ["
              + conglom + "] for ID [" + id + ']');
    }
    return conglom;
  }

  public void addConglomerate(ContainerKey id, MemConglomerate conglom)
      throws StandardException {
    if (GemFireXDUtils.TraceConglom) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM, String.format(
          "GemFireStore#addConglomerate: adding conglomerate [%s] "
              + "with ID [%s]", conglom, id));
    }
    final MemConglomerate oldConglom = this.conglomerates.putIfAbsent(id,
        conglom);
    if (oldConglom != null) {
      throw new GemFireXDRuntimeException(String.format(
          "GemFireStore#addConglomerate: unexpected existing container %s "
              + "for container [%s]", oldConglom.getGemFireContainer(), conglom
              .getGemFireContainer()));
    }
    if (initialDDLReplayInProgress()) {
      // add to uninitialized conglomerate list
      // no need to store local indexes
      final int conglomType = conglom.getType();
      if (conglomType != MemConglomerate.SORTEDMAP2INDEX
          && conglomType != MemConglomerate.HASH1INDEX) {
        if (GemFireXDUtils.TraceConglom) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM, String.format(
              "GemFireStore#addConglomerate: adding conglomerate [%s] "
                  + "with ID [%s] to uninitialized list", conglom, id));
        }
        synchronized (this.uninitializedConglomerates) {
          this.uninitializedConglomerates.put(id, conglom);
        }
      }
    }
    invalidateHiveMetaDataForAllTables();
  }

  public void invalidateHiveMetaDataForAllTables() {
    List<GemFireContainer> containers = getAllContainers();
    for (GemFireContainer container : containers) {
      container.invalidateHiveMetaData();
    }
  }

  public void dropConglomerate(Transaction xact, ContainerKey id)
      throws StandardException {
    if (GemFireXDUtils.TraceConglom) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM, String.format(
          "GemFireContainer#dropConglomerate: "
              + "dropping conglomerate with ID [%s]", id));
    }
    final MemConglomerate conglom = this.conglomerates.remove(id);
    final GemFireContainer container;
    if (conglom != null && (container = conglom.getGemFireContainer()) != null) {
      // container can be a region or a skiplist
      if (initialDDLReplayInProgress() && container.isGlobalIndex()
          && !container.isInitialized()) {
        // See #49705
        container.preInitializeRegion();
        container.initializeRegion();
      }
      container.drop((GemFireTransaction)xact);
    }
    if (initialDDLReplayInProgress()) {
      if (GemFireXDUtils.TraceConglom) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM, String.format(
            "GemFireContainer#dropConglomerate: removing conglomerate [%s] "
                + "with ID [%s] from uninitialized list", conglom, id));
      }
      synchronized (this.uninitializedConglomerates) {
        this.uninitializedConglomerates.remove(id);
      }
    }
    invalidateHiveMetaDataForAllTables();
  }

  public boolean addPendingOperation(MemOperation op, GemFireTransaction tran)
      throws StandardException, IOException {
    if (initialDDLReplayInProgress()) {
      synchronized (this.ddlReplayPendingOperations) {
        this.ddlReplayPendingOperations.add(op);
      }
      return true;
    }
    else {
      op.doMe(tran, null, null);
      return false;
    }
  }

  /**
   * Create a Schema in the store in given transaction context.
   * 
   * @param schemaName
   *          the name of the schema
   * @param tc
   *          the {@link TransactionManager} for this operation
   */
  public Region<?, ?> createSchemaRegion(String schemaName,
      TransactionController tc) throws StandardException {
    Region<?, ?> schemaRegion = null;
    boolean locked = false;
    final GemFireTransaction tran = (GemFireTransaction)tc;
    LockingPolicy locking = null;
    if (!tran.skipLocks(schemaName, null)) {
      locking = new GFContainerLocking(
          new DefaultGfxdLockable(schemaName, null), false, null);
      // take distributed write lock before create
      // the lock will be released when transaction ends
      locked = locking.lockContainer(tran, null, true, true);
      // check if someone else created the schema region in the meantime
      if ((schemaRegion = this.gemFireCache.getRegion(schemaName)) != null) {
        return schemaRegion;
      }
    }
    AttributesFactory<?, ?> af = new AttributesFactory<Object, Object>();
    af.setScope(Scope.DISTRIBUTED_NO_ACK);
    af.setDataPolicy(DataPolicy.EMPTY);
    af.setConcurrencyChecksEnabled(false);
    try {
      schemaRegion = this.gemFireCache.createRegion(schemaName, af.create());
    } catch (RegionExistsException ex) {
      // [sumedh] A genuine case should be caught at the derby level
      // while other case where it can arise is receiving a GfxdDDLMessage
      // for a schema creation that has already been replayed using
      // the hidden _DDL_STMTS_REGION.
      final LogWriterI18n logger = this.gemFireCache.getLoggerI18n();
      if (logger.finerEnabled()) {
        logger.finer("createSchemaRegion: region for schema '" + schemaName
            + "' already exists", ex);
      }
      if (locked) {
        locking.unlockContainer(tran, null);
      }
    }
    return schemaRegion;
  }

  /**
   * Delete a Schema in the store in given transaction context.
   * 
   * @param schemaName
   *          the name of the schema
   * @param tc
   *          the {@link TransactionManager} for this operation
   */
  public void dropSchemaRegion(String schemaName, TransactionController tc)
      throws StandardException {
    boolean locked = false;
    final GemFireTransaction tran = (GemFireTransaction)tc;
    LockingPolicy locking = null;
    if (!tran.skipLocks(schemaName, null)) {
      locking = new GFContainerLocking(
          new DefaultGfxdLockable(schemaName, null), false, null);
      // take distributed write lock before destroy
      // the lock will be released when transaction ends
      locked = locking.lockContainer(tran, null, true, true);
    }
    Region<?, ?> region = this.gemFireCache.getRegion(schemaName);
    if (region == null || region.isDestroyed()) {
      // [sumedh] Should we throw an exception here?
      // A genuine case should be caught at the derby level
      // while other case where it can arise is receiving a GfxdDDLMessage
      // for a region destruction that has already been replayed using
      // the hidden _DDL_STMTS_REGION.
      /*
      throw new RegionDestroyedException(
          "dropSchemaRegion: region for schema '" + schemaName
              + "' already destroyed", '/' + schemaName);
      */
      final LogWriterI18n logger = this.gemFireCache.getLoggerI18n();
      if (logger.fineEnabled()) {
        logger.fine("dropSchemaRegion: region [/" + schemaName
            + "] for schema '" + schemaName + "' already destroyed");
      }
      if (locked) {
        locking.unlockContainer(tran, null);
      }
    }
    else {
      // Do not distribute region destruction to other caches since the
      // distribution is already handled by GfxdDDLMessages.
      region.localDestroyRegion();
    }
  }

  /**
   * @return container with this key or null if not found
   */
  public GemFireContainer getContainer(ContainerKey id) {
    final MemConglomerate conglom = findConglomerate(id);
    if (conglom != null) {
      return conglom.getGemFireContainer();
    }
    return null;
  }

  public List<GemFireContainer> getAllContainers() {
    final List<GemFireContainer> containers = new ArrayList<GemFireContainer>();
    for (final MemConglomerate conglom : this.conglomerates.values()) {
      if (conglom.getGemFireContainer() != null) {
        containers.add(conglom.getGemFireContainer());
      }
    }
    return containers;
  }

  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="LG_LOST_LOGGER_DUE_TO_WEAK_REFERENCE")
  @Override
  public synchronized void boot(final boolean create,
      final Properties properties) throws StandardException {
    Properties dsProps = null;
    String serverGroupsCSV = null;
    boolean hostData = true;
    boolean isLocator = false;
    boolean isAgent = false;
    boolean isAdmin = false;
    int dumpTimeStatsFreq = -1;
    float criticalHeapPercent = -1.0f;
    float evictionHeapPercent = -1.0f;
    float criticalOffHeapPercent = -1.0f;
    float evictionOffHeapPercent = -1.0f;
   
    // install the GemFireXD specific thread dump signal (URG) handler
    try {
      SigThreadDumpHandler.install();
    } catch (Throwable t) {
      SanityManager.DEBUG_PRINT("fine:TRACE",
          "Failed to install thread dump signal handler: " + t.getCause());
    }

    // first clear any residual statics from a previous unclean run
    clearStatics(true);
    GfxdDataSerializable.clearTypes();
    ClientSharedUtils.setCommonRuntimeException(new GemFireXDRuntimeException());
    FinalizeObject.clearFinalizationQueues();

    // initialize the Database member
    this.database = (FabricDatabase)properties
        .get(FabricDatabase.PROPERTY_NAME);
    if (this.database == null) {
      throw new AssertionError("Expected non-null database object");
    }

    // set the currently booting instance
    bootingInstance = this;

    // set the flag to indicate a GemFireXD system
    GemFireCacheImpl.setGFXDSystem(true);

    // set the gemfirePropertyFile
    String propertyFileName = PropertyUtil.isSQLFire
        ? com.pivotal.gemfirexd.Property.SQLF_PROPERTIES_FILE
        : com.pivotal.gemfirexd.Property.PROPERTIES_FILE;
    DistributedSystem.PROPERTY_FILE = PropertyUtil.getSystemProperty(
        propertyFileName, PropertyUtil.getSystemProperty(
            "gemfirePropertyFile", propertyFileName));

    ResolverUtils.reset();
    if (PropertyUtil.getSystemBoolean(
        Property.GFXD_USE_PRE1302_HASHCODE, false)) {
      ResolverUtils.setUsePre1302Hashing(true);
    }

    // if this property is mentioned in the property file, promote it to System
    // for GFE to honor.
    final String disableSharedLibrary = PropertyUtil
        .getSystemProperty(com.pivotal.gemfirexd.Property.DISABLE_SHARED_LIBRARY);

    if (disableSharedLibrary != null) {
      System.setProperty(com.pivotal.gemfirexd.Property.DISABLE_SHARED_LIBRARY,
          disableSharedLibrary);
    }

    final String useDebugVersion = PropertyUtil
        .getSystemProperty(com.pivotal.gemfirexd.Property.SHARED_LIBRARY_DEBUG_VERSION);

    if (useDebugVersion != null) {
      System.setProperty(
          com.pivotal.gemfirexd.Property.SHARED_LIBRARY_DEBUG_VERSION,
          useDebugVersion);
    }

    final Properties finalGFXDBootProps;
    try {
      finalGFXDBootProps = FabricServiceUtils.preprocessProperties(properties,
          null, null, false);
    } catch (SQLException e) {
      this.database = null;
      bootingInstance = null;
      Throwable cause = e.getCause();
      if (cause != null && cause instanceof StandardException) {
        throw (StandardException)cause;
      }
      throw GemFireXDRuntimeException.newRuntimeException(
          "Error during gemfire store bootup", cause);
    }

    // first see if there is an existing instance; if so then pick up the
    // defaults from there; later we shall check to see if GemFireXD has
    // overridden any of those with an incompatible value
    InternalDistributedSystem dsys = InternalDistributedSystem.getConnectedInstance();
    boolean reconnected = dsys != null  &&  dsys.reconnected();
    if (reconnected) {
      Properties props = dsys.getProperties();
      for (Map.Entry<Object,Object> e: props.entrySet()) {
        finalGFXDBootProps.put(e.getKey(), e.getValue());
      }
      dsys.getLogWriter().info(
          "Booting data store with reconnected distributed system: " + dsys);
    }

    final Enumeration<?> propNames = finalGFXDBootProps.propertyNames();
    String propName, propValue;
    while (propNames.hasMoreElements()) {
      propName = (String)propNames.nextElement();
      propValue = finalGFXDBootProps.getProperty(propName);

      if (propName.equals(GfxdConstants.PROPERTY_BOOT_INDICATOR)) {
        if(propValue.equals(GfxdConstants.BT_INDIC.FABRICAGENT.toString())) {
          isAgent = true;
        }
      }
      else if (propName.equals(Property.PROPERTY_GEMFIREXD_ADMIN)) {
        isAdmin = true;
      }

      // remember these properties, so that modules that require one of
      // these can get them easily
      if (propValue != null) {
        this.serviceProperties.setProperty(propName, propValue);
      }
    }

    final Properties props = this.serviceProperties;
    hostData = PropertyUtil.getBooleanProperty(Attribute.GFXD_HOST_DATA,
        GfxdConstants.GFXD_HOST_DATA, props, true, null);
    serverGroupsCSV = PropertyUtil.findAndGetProperty(props,
        Attribute.SERVER_GROUPS, GfxdConstants.GFXD_SERVER_GROUPS);
    isLocator = PropertyUtil.getBooleanProperty(Attribute.STAND_ALONE_LOCATOR,
        GfxdConstants.GFXD_STAND_ALONE_LOCATOR, props, false, null);


    Map<String, String> gfeGridMappings = PropertyUtil.findAndGetPropertiesWithPrefix(properties,
        GemFireSparkConnectorCacheImpl.gfeGridNamePrefix);
    Map<String, String> gfeGridPoolProps = PropertyUtil.findAndGetPropertiesWithPrefix(properties,
        GemFireSparkConnectorCacheImpl.gfeGridPropsPrefix);

    propName = Attribute.DUMP_TIME_STATS_FREQ;
    propValue = PropertyUtil.findAndGetProperty(props,
        propName, GfxdConstants.GFXD_PREFIX + propName);
    if (propValue != null) {
      dumpTimeStatsFreq = readUnsignedIntegerProperty(propValue, propName);
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        Attribute.SYS_PERSISTENT_DIR, GfxdConstants.SYS_PERSISTENT_DIR_PROP);
    if (propValue != null) {
      // Also set this property in the SystemProperties so that GatewayImpl
      // can also access it
      // For time being. This needs to be removed
      System.setProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP, propValue);
      this.persistenceDir = propValue;
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        Attribute.SYS_HDFS_ROOT_DIR, GfxdConstants.SYS_HDFS_ROOT_DIR_PROP);
    if (propValue != null) {
      System.setProperty(GfxdConstants.SYS_HDFS_ROOT_DIR_PROP, propValue);
      this.hdfsRootDir = propValue;
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        LauncherBase.CRITICAL_HEAP_PERCENTAGE, GfxdConstants.GFXD_PREFIX
            + LauncherBase.CRITICAL_HEAP_PERCENTAGE);
    if (propValue != null) {
      criticalHeapPercent = Float.parseFloat(propValue);
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        LauncherBase.EVICTION_HEAP_PERCENTAGE, GfxdConstants.GFXD_PREFIX
            + LauncherBase.EVICTION_HEAP_PERCENTAGE);
    if (propValue != null) {
      evictionHeapPercent = Float.parseFloat(propValue);
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        LauncherBase.CRITICAL_OFF_HEAP_PERCENTAGE,
        GfxdConstants.GFXD_PREFIX
            + LauncherBase.CRITICAL_OFF_HEAP_PERCENTAGE);
    if (propValue != null) {
      criticalOffHeapPercent = Float.parseFloat(propValue);
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        LauncherBase.EVICTION_OFF_HEAP_PERCENTAGE,
        GfxdConstants.GFXD_PREFIX
            + LauncherBase.EVICTION_OFF_HEAP_PERCENTAGE);
    if (propValue != null) {
      evictionOffHeapPercent = Float.parseFloat(propValue);
    }
    propValue = PropertyUtil.findAndGetProperty(props,
        Property.HADOOP_IS_GFXD_LONER, Property.HADOOP_IS_GFXD_LONER);
    if (propValue != null) {
      this.isHadoopGfxdLonerMode = Boolean.parseBoolean(propValue);
      if (this.isHadoopGfxdLonerMode) {
        hadoopGfxdLonerConfig = new HadoopGfxdLonerConfig(finalGFXDBootProps,
            this);
        System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,
            "true");
      }
    }

    InternalDistributedSystem
        .setHadoopGfxdLonerMode(this.isHadoopGfxdLonerMode);

    // Set the default data policy for tables
    // first check in boot properties
    this.tableDefaultPartitioned = false;
    String defaultTablePolicy = PropertyUtil
        .getSystemProperty(GfxdConstants.TABLE_DEFAULT_PARTITIONED_SYSPROP);
    // then look in system properties / gemfirexd.properties if not found
    if (defaultTablePolicy == null) {
      defaultTablePolicy = this.serviceProperties
          .getProperty(Attribute.TABLE_DEFAULT_PARTITIONED);
    }
    if (defaultTablePolicy != null) {
      this.tableDefaultPartitioned = "true"
          .equalsIgnoreCase(defaultTablePolicy);
    }

    // setup PERSIST_INDEXES
    String persistIndexes = PropertyUtil.findAndGetProperty(finalGFXDBootProps,
        Attribute.PERSIST_INDEXES, GfxdConstants.GFXD_PERSIST_INDEXES);
    if (persistIndexes == null) {
      this.persistIndexes = true;
    }
    else {
      this.persistIndexes = Boolean.parseBoolean(persistIndexes);
    }
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          "GemFireStore.boot setting persist-indexes to " + persistIndexes);
    }

    if (isLocator) {
      this.myKind = VMKind.LOCATOR;
    }
    else if(isAdmin) {
      this.myKind = VMKind.ADMIN;
    }
    else if(isAgent) {
      this.myKind = VMKind.AGENT;
    }
    else if (!hostData) {
      this.myKind = VMKind.ACCESSOR;
      // fix for bug #48479 accessor hung trying to reconnect after network failure
      finalGFXDBootProps.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "true");
    }
    else {
      this.myKind = VMKind.DATASTORE;
    }

    try {
      dsProps = FabricServiceUtils.filterGemFireProperties(finalGFXDBootProps,
          "gemfirexd.log");

      // Set the GemFire log-file to that of GemFireXD one if set
      final String gfxdLogFile = SingleStream.getGFXDLogFile();
      if (gfxdLogFile != null) {
        System.setProperty(InternalDistributedSystem.APPEND_TO_LOG_FILE,
            "true");
        dsProps.put(DistributionConfig.LOG_FILE_NAME, gfxdLogFile);
      }

      // the special test object EXPECTED_STARTUP_EXCEPTIONS will be added
      // to log for any expected startup exceptions
      final Object[] expectedExceptions = EXPECTED_STARTUP_EXCEPTIONS;
      if (expectedExceptions != null) {
        for (Object exObj : expectedExceptions) {
          String exStr = GemFireXDUtils.getExpectedExceptionString(exObj, false);
          SanityManager.DEBUG_PRINT("ExpectedEx", exStr);
          System.out.println(exStr);
        }
      }

      GemFireXDVersion.loadProperties();

      // Register the GfxdSerializable implementations.
      GfxdDataSerializable.initTypes();

      // Register DVD deserialization helper
      DataType.init();

      // use given properties to connect to the
      // distributed system and create the cache and region

      GemFireXDUtils.dumpProperties(dsProps,
          "distributed member connection properties",
          GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          GemFireXDUtils.TraceFabricServiceBoot, null);

      // try to connect to the DistributedSystem; if there is an existing
      // system then this will automatically check if the given dsProps
      // are compatible with those of the existing system
      final LogWriter logger;
      if (this.myKind.isAgent()) {
        AgentConfig aConf = AgentFactory.defineAgent(dsProps);
        try {
          this.gemFireAgent = AgentFactory.getAgent(aConf);
          this.gemFireAgent.start();
          this.gemFireAgent.getLogWriter().info(
              "GemFire Agent successfully created");
          dsProps.clear();
        } catch (AdminException ade) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "Exception occurred while starting GemFireXD JXM Agent", ade);
        }
      }
      
      // persistingDD needs to be initialized here since we are dependent on it
      // in GfxdDistributionAdvisor
      String persistDD = getBootProperty(Attribute.GFXD_PERSIST_DD);
      if (persistDD == null) {
        if (this.myKind.isAccessor() || this.myKind.isAdmin()
            || this.myKind.isAgent()) {
          this.persistingDD = false;
        }
        else {
          this.persistingDD = true;
        }
      }
      else {
        this.persistingDD = Boolean.parseBoolean(persistDD);
      }
      // Yogesh persist-dd should be false on client - bugFix 42855
      if ((this.myKind.isAccessor() || this.myKind.isAdmin() || this.myKind
          .isAgent()) && this.persistingDD) {
        throw new GemFireXDRuntimeException(
            "persist-dd property should be false for clients");
      }

      try {
        CacheFactory c = null;
        if (!(gfeGridMappings.isEmpty() && gfeGridPoolProps.isEmpty())) {
          c = new GemFireSparkConnectorCacheFactory(dsProps, gfeGridMappings, gfeGridPoolProps);
        } else {
          c = new CacheFactory(dsProps);
        }
        if (this.persistingDD) {
          c.setPdxPersistent(true);
          c.setPdxDiskStore(GfxdConstants.GFXD_DD_DISKSTORE_NAME);
        }

        if (isLocator) {
          System.setProperty(FORCE_LOCATOR_DM_TYPE, "true");
        }

        this.gemFireCache = (GemFireCacheImpl)c.create();
        this.gemFireCache.getLogger().info(
            "GemFire Cache successfully created.");
        if (props.containsKey(GfxdConstants.SNAPPY_PREFIX + CacheServerLauncher.RECOVER)) {
          String recoveryMode = props.getProperty(GfxdConstants.SNAPPY_PREFIX + CacheServerLauncher.RECOVER);
          if (recoveryMode != null && recoveryMode.equals("true")) {
            this.gemFireCache.getLogger().info(
                "GemFire Cache has come up in recovery mode.");
            this.gemFireCache.setRecoverMode(true);
          }
        }
      } catch (CacheExistsException ex) {
        this.gemFireCache = GemFireCacheImpl.getExisting();
        this.gemFireCache.getLogger().info("Found existing GemFire Cache.");
      }
      this.skipCacheClose = false;

      if (criticalHeapPercent > 0.0f) {
        this.gemFireCache.getResourceManager().setCriticalHeapPercentage(
            criticalHeapPercent);
      }
      if (evictionHeapPercent > 0.0f) {
        this.gemFireCache.getResourceManager().setEvictionHeapPercentage(
            evictionHeapPercent);
      }

      if (criticalOffHeapPercent > 0.0f) {
        this.gemFireCache.getResourceManager().setCriticalOffHeapPercentage(
            criticalOffHeapPercent);
      }
      if (evictionOffHeapPercent > 0.0f) {
        this.gemFireCache.getResourceManager().setEvictionOffHeapPercentage(
            evictionOffHeapPercent);
      }

      dsys = this.gemFireCache.getDistributedSystem();
      logger = this.gemFireCache.getLogger();
      selfMemId = this.gemFireCache.getMyId();

      // initialize connection stats
      InternalDriver.activeDriver().initialize(this.gemFireCache);

      // print command-line if started using gfxd launcher
      CacheServerLauncher launcher = CacheServerLauncher.getCurrentInstance();
      if (launcher != null) {
        launcher.printCommandLine(this.gemFireCache.getLoggerI18n());
      }

      if (logger instanceof LogWriterImpl) {
        final LogWriterImpl loggerImpl = (LogWriterImpl)logger;
        final Logger javaUtilLogger = Logger
            .getLogger(ClientSharedUtils.LOGGER_NAME);
        javaUtilLogger.addHandler(logger.getHandler());
        final Level levelToBeSet = GemFireLevel.create(loggerImpl.getLevel());
        javaUtilLogger.setLevel(levelToBeSet);
        javaUtilLogger.setUseParentHandlers(false);
        ClientSharedUtils.setLogger(javaUtilLogger);
      }

      // Set the GemFireXD's logger to that of GemFire
      final PrintWriter gfxdLogger = SanityManager.GET_DEBUG_STREAM();
      if (gfxdLogger instanceof GfxdHeaderPrintWriterImpl) {
        ((GfxdHeaderPrintWriterImpl)gfxdLogger).setLogWriter(logger);
      }

      // initialize some constants in GemFireXDUtils
      GemFireXDUtils.initConstants(this);

      // Soubhik: Register GfxdMemoryListener
      this.thresholdListener = GfxdHeapThresholdListener
          .createInstance(this.gemFireCache);

      // Neeraj: Instantiate the objectsizer
      this.gfxdObjectSizer = new GfxdObjectSizer();

      // register the functions
      // [sumedh] TODO: At some point we should replace all by
      // GfxdFunctionMessages which is much better tested for GFXD environment
      // and has proper plan/stats/trace/log support
      FunctionService.registerFunction(
          new DistributedConnectionCloseExecutorFunction());
      FunctionService.registerFunction(new DistributedProcedureCallFunction());
      FunctionService.registerFunction(
          new GfxdPartitionResolver.GlobalIndexLookupFunction());
      FunctionService.registerFunction(
          new GfxdPartitionResolver.HdfsGlobalIndexLookupFunction());
      FunctionService.registerFunction(new GfxdCacheLoader.GetRowFunction());
      FunctionService.registerFunction(new QueryCancelFunction());
      FunctionService.registerFunction(new SnappyRegionStatsCollectorFunction());
      FunctionService.registerFunction(new DiskStoreIDs.DiskStoreIDFunction());

      final ConnectionSignaller signaller = ConnectionSignaller.getInstance();
      if (logger.fineEnabled()) {
        logger.fine(signaller.toString() + " started.");
      }

      // Initialize the wait times in GfxdLockSet and create the RW lock
      // service for DDL/DML locking.
      final int maxVMLockWait = GfxdLockSet.initConstants(this);
      this.ddlLockService = GfxdDRWLockService.create(
          GfxdConstants.DDL_LOCK_SERVICE, dsys, true /*distributed*/,
          true /*destroyOnDisconnect*/, false /*automateFreeResources*/,
          maxVMLockWait /* maxVMLockWaitTime */,
          GfxdReentrantReadWriteLock.createTemplate(true),
          GfxdDDLMessage.getMemberDepartedListener());

      StringPrintWriter spw = new StringPrintWriter();
      Properties appProperties = Monitor.getMonitor().getApplicationProperties();
      spw = (StringPrintWriter)GemFireXDUtils.dumpProperties(appProperties,
          "file properties", null, true, spw);
      
      if (this.isHadoopGfxdLonerMode) {
        // remove the hadoop properties as they are unnecessarily polluting the log file.
        Properties updatedProps = hadoopGfxdLonerConfig.removeHadoopProperties(properties);
        spw = (StringPrintWriter)GemFireXDUtils.dumpProperties(updatedProps,
            "boot connection properties", null, true, spw);
      }
      else
        spw = (StringPrintWriter)GemFireXDUtils.dumpProperties(properties,
            "boot connection properties", null, true, spw);

      if (logger.configEnabled()) {
        logger.config("GemFire Cache successfully booted with "
            + SanityManager.lineSeparator + spw.toString());
      }
      GemFireXDUtils.dumpProperties(finalGFXDBootProps,
          "final values of the gemfirexd boot properties",
          GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          GemFireXDUtils.TraceFabricServiceBoot, null);

      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          "GemFireStore: booted with persist-indexes=" + this.persistIndexes);

      // Mark this GemFireStore instance as the booted one by setting as the
      // static instance.
      bootedInstance = this;
      
      dbLocaleStr = this.database.getLocale().toString();

      // Initialize the StoreAdvisee to discover other GemFireXD VMs and their
      // kinds, server groups in the distributed system as well as publishing
      // own GfxdProfile.
      @SuppressWarnings("unchecked")
      final SortedSet<String> sortedGroups = SharedUtils.toSortedSet(
          serverGroupsCSV, false);


      this.advisee.start(sortedGroups, this.gemFireCache, logger);

      // set the VM ID region to use for persisting IDs generated by UUIDAdvisor
      if (this.persistingDD) {
        this.gemFireCache.setVMIDRegionPath(LocalRegion.SEPARATOR
            + DDL_STMTS_REGION);
      }

      // skip Cache close if there is an exception in bootRegions since that
      // is likely due to persistence sync failure (or revoke) and Cache.close()
      // hangs thereafter waiting for background tasks in DiskStoreImpl
      this.skipCacheClose = true;
      // Create all the meta-regions etc. required for GemFireXD.
      bootRegions(/*this.myKind*/);
      this.skipCacheClose = false;

      // enable query time statistics if required
      if (dumpTimeStatsFreq >= 0) {
        GemFireXDQueryObserverHolder
            .putInstance(new GemFireXDQueryTimeStatistics(dumpTimeStatsFreq));
      }

      // --------- Boot sequence for AccessFactory below

      // Note: we also boot this module here since we may start Derby
      // system from store access layer, as some of the unit test case,
      // not from JDBC layer.(See
      // /protocol/Database/Storage/Access/Interface/T_AccessFactory.java)
      // If this module has already been booted by the JDBC layer, this will
      // have no effect at all.
      Monitor.bootServiceModule(create, this,
          com.pivotal.gemfirexd.internal.iapi.reference.Module.PropertyFactory,
          properties);

      Monitor.bootServiceModule(create, this,
          com.pivotal.gemfirexd.internal.iapi.reference.Module.SparkServiceModule,
          finalGFXDBootProps);

      GemFireCacheImpl.FactoryStatics.initGFXDCallbacks(true);

      // Read in the conglomerate directory from the conglom conglom
      // Create the conglom conglom from within a separate system xact
      final ContextManager cm = ContextService.getFactory()
          .getCurrentContextManager();
      final TransactionController tc = getAndNameTransaction(cm,
          AccessFactoryGlobals.USER_TRANS_NAME);
      // set up the property validation
      this.pf = (PropertyFactory)Monitor.findServiceModule(this,
          com.pivotal.gemfirexd.internal.iapi.reference.Module.PropertyFactory);
      // set up the transaction properties. On J9, over NFS, runing on a
      // power PC coprossor, the directories were created fine, but create
      // db would fail when trying to create this first file in seg0.
      this.xactProperties = new PropertyConglomerate(tc, create,
          this.serviceProperties, this.servicePropertiesDefaults, this.pf);
      tc.commit();
      // Rahul:I think by protocol the commit should be followed by a
      // tc.destroy.

      if (expectedExceptions != null) {
        for (Object exObj : expectedExceptions) {
          String exStr = GemFireXDUtils.getExpectedExceptionString(exObj, true);
          SanityManager.DEBUG_PRINT("ExpectedEx", exStr);
          System.out.println(exStr);
        }
      }
      EXPECTED_STARTUP_EXCEPTIONS = null;

      this.storeStats.init(this.gemFireCache.getDistributedSystem());
      this.indexPersistenceStats.init(this.gemFireCache.getDistributedSystem());

      final FabricService service;
      // get server or locator instance as appropriate
      if (this.myKind.isLocator()) {
        service = FabricServiceManager.getFabricLocatorInstance();
      }
      else if (this.myKind.isAgent()) {
        service = FabricServiceManager.getFabricAgentInstance();
      }
      else {
        service = FabricServiceManager.getFabricServerInstance();
      }
      assert service instanceof FabricServiceImpl;

      this.isShutdownAll = false;

      startExecutor();
    } catch (RuntimeException ex) {
//      (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("GemFireStore caught unexpected exception", ex);
      if (GemFireXDUtils.TraceFabricServiceBoot) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
            "RuntimeExecption during database boot", ex);
      }
      stop();
      throw StandardException.newException(SQLState.BOOT_DATABASE_FAILED, ex,
          Attribute.GFXD_DBNAME);
    } catch (StandardException se) {
//      (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("GemFireStore caught unexpected exception", se);
      if (GemFireXDUtils.TraceFabricServiceBoot) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
            "Failed to boot database", se);
      }
      stop();
      throw se;
    }

    if (SanityManager.DEBUG) {
      // RESOLVE - (mikem) currently these constants need to be the
      // same, but for modularity reasons there are 2 sets. Probably
      // should only be one set. For now just make sure they are the
      // same value.
      SanityManager.ASSERT(TransactionController.OPENMODE_USE_UPDATE_LOCKS
          == ContainerHandle.MODE_USE_UPDATE_LOCKS);
      SanityManager.ASSERT(TransactionController.OPENMODE_SECONDARY_LOCKED
          == ContainerHandle.MODE_SECONDARY_LOCKED);
      SanityManager.ASSERT(TransactionController.OPENMODE_BASEROW_INSERT_LOCKED
          == ContainerHandle.MODE_BASEROW_INSERT_LOCKED);
      SanityManager.ASSERT(TransactionController.OPENMODE_FORUPDATE
          == ContainerHandle.MODE_FORUPDATE);
      SanityManager.ASSERT(TransactionController.OPENMODE_FOR_LOCK_ONLY
          == ContainerHandle.MODE_OPEN_FOR_LOCK_ONLY);
    }
  }




  /**
   * Start executor if any of the accessor is a driver.
   */
  private void startExecutor() {
    if (this.getMyVMKind() == VMKind.LOCATOR) {
      return;
    }
    Set<DistributedMember> servers = this.getDistributionAdvisor().
            adviseOperationNodes(CallbackFactoryProvider.getClusterCallbacks().
                    getLeaderGroup());
    for (DistributedMember server : servers) {
      final GfxdDistributionAdvisor.GfxdProfile other = GemFireXDUtils
          .getGfxdProfile(server);
      if (other.hasSparkURL() && !server.equals(getMyId())) {
        CallbackFactoryProvider.getClusterCallbacks().
            launchExecutor(other.getSparkDriverURL(), other.getDistributedMember());
      }
    }
  }

  private int readUnsignedIntegerProperty(String val, String propName)
      throws NumberFormatException {
    try {
      int intVal = Integer.parseInt(val);
      if (intVal >= 0) {
        return intVal;
      }
      else {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException ex) {
      throw new NumberFormatException("Boot property " + propName
          + " should be an integer >= 0 but found: " + val);
    }
  }

  public static Path createPersistentDir(String baseDir, String dirPath) {
    Path dir = generatePersistentDirName(baseDir, dirPath);
    try {
      return Files.createDirectories(dir).toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException ioe) {
      throw new DiskAccessException("Could not create directory for "
          + "system disk store: " + dir.toString(), ioe);
    }
  }

  public String generatePersistentDirName(String dirPath) {
    return generatePersistentDirName(this.persistenceDir, dirPath).toString();
  }

  private static Path generatePersistentDirName(String baseDir,
      String dirPath) {
    if (baseDir == null) {
      baseDir = ".";
    }
    Path dir;
    if (dirPath != null) {
      Path dirProvided = Paths.get(dirPath);
      // Is the directory path absolute?
      // For Windows this will check for drive letter. However, we want
      // to allow for no drive letter so prepend the drive.
      boolean isAbsolute = dirProvided.isAbsolute();
      if (!isAbsolute) {
        final String drivePrefix;
        // get the current drive for Windows and prepend
        if ((dirPath.charAt(0) == '/' || dirPath.charAt(0) == '\\')
            && (drivePrefix = GemFireXDUtils.getCurrentDrivePrefix()) != null) {
          isAbsolute = true;
          dirPath = drivePrefix + dirPath;
        }
      }
      if (isAbsolute) {
        dir = Paths.get(dirPath);
      } else {
        // relative path so resolve it relative to parent dir
        dir = Paths.get(baseDir, dirPath).toAbsolutePath();
      }
    } else {
      dir = Paths.get(baseDir).toAbsolutePath();
    }
    if (!isFilenameValid(dir.toString())) {
      throw new DiskAccessException("Directory name " + dirPath +
          " is not valid.", (Throwable)null);
    }
    return dir;
  }

  // Is this filename valid?
  public static boolean isFilenameValid(String file) {
    // Illegal characters are
    //  asterisk
    //  question mark
    //  greater-than/less-than
    //  pipe character
    //  semicolon
    // Some are legal on Linux, but trying to create DISKSTORE "*" crashes anyway
    // So make this more restrictive and same as Windows restrictions
    Matcher matcher = ILLEGAL_DISKDIR_CHARS_PATTERN.matcher(file);
    return !matcher.find();
  }

  /**
   * @param dirPath the home directory for a hdfs store
   * @return home directory prepended with cluster's hdfs rootDir
   */
  public String generateHdfsStoreDirName(String dirPath) {
    if (dirPath.startsWith("/")) {
      return dirPath;
    }
    
    String baseDir = this.hdfsRootDir;
    if (baseDir == null) {
      baseDir = GfxdConstants.SYS_HDFS_ROOT_DIR_DEF;
    }
    return baseDir + "/" + dirPath;
  }

  public static InternalDistributedMember getMyId() {
    return selfMemId;
  }

  public VMKind getMyVMKind() {
    return this.myKind;
  }

  /**
   * Create the initial regions needed at boot time
   * @throws StandardException 
   */
  private void bootRegions() throws StandardException {
    
    renameDiskStoresIfAny();

    AttributesFactory<?, ?> af = new AttributesFactory<Object, Object>();

    // Create the root regions for the built-in schemas, SYS and APP.
    // They are place-holders for subregions that will be replicated or
    // partitioned regions, so make them scope distributed no-ack
    af.setScope(Scope.LOCAL);
    af.setConcurrencyChecksEnabled(false);
    // SYS tables are now local; distribution is done via GfxdDDLMessage
    this.gemFireCache.createRegion(GfxdConstants.SYSTEM_SCHEMA_NAME, af
        .create());
    af.setScope(Scope.DISTRIBUTED_NO_ACK);
    af.setDataPolicy(DataPolicy.EMPTY);
    this.gemFireCache.createRegion(SchemaDescriptor.DEFAULT_USER_NAME,
        af.create());
    // ???:ezoerner:20080609 what do we do with TEMP tables...
    this.gemFireCache.createRegion(GfxdConstants.SESSION_SCHEMA_NAME,
        af.create());

    // Create the meta-region that is used for DDL puts to allow replaying of
    // existing DDL when a new server comes up. Using a RegionQueue to
    // preserve order.
    // Ignore DDL queue and region for stand-alone locators -- this will also
    // automatically avoid receiving DDL messages on this VM and consequently
    // all DMLs too severely restricting the use of this VM to execute only
    // some system procedures or VTIs as required for clients.

    // [sb] now we are creating the meta-region in locators, but avoid DDLs
    // to replay. instead we just play SystemProcedureMessages in locators.
    /* if (vmKind != VMKind.LOCATOR) */
    {
      // set gemfirexd default disk store when DD is being persisted
      // when DD is not being persisted then we no longer allow regions with
      // persistence to be created; however, if "sys-disk-dir" is explicitly
      // set then that can be used for overflow/gateway

      String serverGroup = this.getBootProperty("server-groups");
      boolean isLeadMember = serverGroup != null &&
          serverGroup.contains(ServerGroupUtils.LEADER_SERVERGROUP);

      if (this.persistingDD || this.persistenceDir != null || isLeadMember) {
        try {
          DiskStoreFactory dsf = this.gemFireCache.createDiskStoreFactory();
          Path dir = createPersistentDir(this.persistenceDir, null);

          final boolean isStore = this.myKind.isStore();
          if (!isStore) {
            // use small oplog files for other VM types
            if (DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE < 10) {
              dsf.setMaxOplogSize(DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE);
            }
            else {
              if (isLeadMember) {
                dsf.setMaxOplogSize(1);
              } else {
                dsf.setMaxOplogSize(10);
              }
            }
          }
          dsf.setDiskDirs(new File[] { dir.toFile() });
          this.gfxdDefaultDiskStore = (DiskStoreImpl)createDiskStore(
              dsf, GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME,
              getAdvisee().getCancelCriterion());

          // set the default disk store at GemFire layer
          GemFireCacheImpl.setDefaultDiskStoreName(
              GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME);

          if (isStore) {
            // create the SnappyData delta store
            dir = createPersistentDir(this.persistenceDir,
                GfxdConstants.SNAPPY_DELTA_SUBDIR);
            dsf = this.gemFireCache.createDiskStoreFactory();
            dsf.setMaxOplogSize(GfxdConstants.SNAPPY_DELTA_DISKSTORE_SIZEMB);
            dsf.setDiskDirs(new File[] { dir.toFile() });
            createDiskStore(dsf, GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE,
                getAdvisee().getCancelCriterion());
          }

        } catch (GemFireException e) {
          final LogWriter logger = this.gemFireCache.getLogger();
          logger.warning("Unable to create default disk stores.", e);
          throw e;
        }
      }
    }
    this.ddlStmtQueue = new GfxdDDLRegionQueue(DDL_STMTS_REGION,
        this.gemFireCache, this.persistingDD, this.persistenceDir, null);

    if (this.isHadoopGfxdLonerMode) {
      hadoopGfxdLonerConfig.loadDDLQueueWithDDLsFromHDFS(this.ddlStmtQueue);
    }

    final VMKind vmKind = this.advisee.getVMKind();
    if (vmKind.isAccessorOrStore()) {
      try {
        this.fileHandler = new GfxdJarResource(this.persistingDD,
            this.gemFireCache);
      } catch (Exception e) {
        SanityManager.DEBUG_PRINT("warning:"
            + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
            "Unable to create file handler for jar storage", e);
        throw GemFireXDRuntimeException.newRuntimeException(null, e);
      }
    }
    
    this.uuidToIdMap = new THashMap();
    
    this.ttab = new TransactionTable();
    if (vmKind.isAccessorOrStore() && !isHadoopGfxdLonerMode()) {
      final AttributesFactory<String, Long> afact =
          new AttributesFactory<String, Long>();
      final PartitionAttributesFactory<String, Long> pafact =
          new PartitionAttributesFactory<String, Long>();
      pafact.setTotalNumBuckets(17);
      pafact.setRedundantCopies(0);
      afact.setDataPolicy(DataPolicy.PARTITION);
      if (vmKind.isAccessor()) {
        pafact.setLocalMaxMemory(0);
      }
      afact.setPartitionAttributes(pafact.create());
      afact.setConcurrencyChecksEnabled(GfxdConstants
          .TABLE_DEFAULT_CONCURRENCY_CHECKS_ENABLED);
      RegionAttributes rattrs = afact.create();
      InternalRegionArguments iargs = new InternalRegionArguments()
          .setDestroyLockFlag(true).setRecreateFlag(false)
          .setKeyRequiresRegionContext(false).setIsUsedForMetaRegion(true);
      try {
        this.identityRegion = (LocalRegion)this.gemFireCache.createVMRegion(
            GfxdConstants.IDENTITY_REGION_NAME, rattrs, iargs);
        IdentityValueManager.GetIdentityValueMessage
            .installBucketListener((PartitionedRegion)this.identityRegion);
      }
      catch (IOException e) {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
      }
      catch (ClassNotFoundException e) {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
      }
    }
  }

  public static DiskStore createDiskStore(DiskStoreFactory dsf, String name,
      CancelCriterion cc) throws DiskAccessException {
    // try a bit harder to go through in case of transient disk exceptions
    DiskAccessException dae = null;
    for (int tries = 1; tries <= 10; tries++) {
      try {
        return dsf.create(name);
      } catch (DiskAccessException e) {
        final LogWriter logger = Misc.getGemFireCache().getLogger();
        logger.warning("unexpected exception in creating default "
            + "disk store " + name + ", retrying", e);
        if (dae == null) { // bug #48719 - retries may throw unclear exceptions
          dae = e;
        }
        // retry after sleep
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          cc.checkCancelInProgress(ie);
        }
      }
    }
    throw dae;
  }

  private void renameDiskStoresIfAny() {

    final String persistDirectory = generatePersistentDirName(this.persistenceDir);
    List<File> files = GemFireXDUtils.listFiles("SQLF-DD-DISKSTORE",
        Arrays.asList(persistDirectory + File.separatorChar
            + GfxdConstants.DEFAULT_PERSISTENT_DD_SUBDIR));
    if (files.size() > 0) {
      GemFireXDUtils.renameFiles(files);
    }
    
    files = GemFireXDUtils.listFiles("SQLF-DEFAULT-DISKSTORE",
        Arrays.asList(persistDirectory));
    if (files.size() > 0) {
      GemFireXDUtils.renameFiles(files);
    }
  }

  public TransactionTable getTransactionTable() {
    return this.ttab;
  }

  public GfxdJarResource getJarFileHandler() {
    return this.fileHandler;
  }
  
  public THashMap getUUID_IDMap() {
    return this.uuidToIdMap;
  }
  
  public String getBootProperty(String propName) {
    return this.serviceProperties.getProperty(propName);
  }

  public void setBootProperty(String propName, String propValue) {
    if (propValue != null) {
      this.serviceProperties.setProperty(propName, propValue);
    } else {
      this.serviceProperties.remove(propName);
    }
  }

  public Map<Object, Object> getBootProperties() {
    return Collections.unmodifiableMap(this.serviceProperties);
  }

  /**
   * Return true if initial DDL replay is in progress. It may happen that this
   * is false even when {@link #initialDDLReplayDone()} returns false since
   * there may be configuration SQL scripts running.
   */
  public final boolean initialDDLReplayInProgress() {
    return this.ddlReplayInProgress;
  }

  /** Set the initial DDL replay in progress flag to given value. */
  public final void setInitialDDLReplayInProgress(boolean replayInProgress) {
    this.ddlReplayInProgress = replayInProgress;
  }

  /** Return true if initial DDL replay has been completed. */
  public final boolean initialDDLReplayDone() {
    return this.ddlReplayDone;
  }

  /** Set the initial DDL replay complete flag to given value. */
  public final void setInitialDDLReplayDone(boolean replayDone) {
    this.ddlReplayDone = replayDone;
  }

  /**
   * Return true if basic first part of DDL replay has been completed (i.e. not
   * waiting for region initializations).
   */
  public final boolean initialDDLReplayPart1Done() {
    return this.ddlReplayPart1Done;
  }

  /** Set the basic first part DDL replay complete flag to given value. */
  public final void setInitialDDLReplayPart1Done(boolean replayDone) {
    this.ddlReplayPart1Done = replayDone;
  }

  /**
   * Return true if DataDictionary read lock should be skipped during initial
   * DDL replay (due to remote DDL message already having taken the write lock
   * and waiting on this JVM to finish DDL replay).
   */
  public final boolean initialDDLReplayWaiting() {
    return this.ddlReplayWaiting;
  }

  /** Set the {@link #ddlReplayWaiting} flag to given value. */
  public final void setInitialDDLReplayWaiting(boolean waiting) {
    this.ddlReplayWaiting = waiting;
  }

  /**
   * get the sync used to signal any waiting threads when DDL replay is complete
   */
  public final Object getInitialDDLReplaySync() {
    return this.ddlReplaySync;
  }

  /** Acquire the read/write lock for DDL replay. */
  public void acquireDDLReplayLock(boolean forWrite) {
    if (forWrite) {
      this.ddlReplayLock.writeLock().lock();
    }
    else {
      this.ddlReplayLock.readLock().lock();
    }
  }

  /** Release the read/write lock for DDL replay. */
  public void releaseDDLReplayLock(boolean forWrite) {
    if (forWrite) {
      this.ddlReplayLock.writeLock().unlock();
    }
    else {
      this.ddlReplayLock.readLock().unlock();
    }
  }

  /**
   * Region initializations that were skipped during DDL replay and need to be
   * done at the end e.g. startup redundancy recovery.
   * 
   * Also execute any other pending operations in
   * {@link #ddlReplayPendingOperations}.
   */
  public void postDDLReplayInitialization(TransactionController tc)
      throws Exception {
    // check that no buckets have been created on this node for skipped PRs
    GemFireContainer container;
    AbstractRegion region;
    PartitionedRegion pr;
    final ArrayList<MemConglomerate> uninitializedCongloms;
    synchronized (this.uninitializedConglomerates) {
      uninitializedCongloms = new ArrayList<MemConglomerate>(
          this.uninitializedConglomerates.values());
      this.uninitializedConglomerates.clear();
    }
    for (MemConglomerate conglom : uninitializedCongloms) {
      if ((container = conglom.getGemFireContainer()) != null
          && (region = container.getRegion()) != null) {
        final DataPolicy dp = region.getDataPolicy();
        final PartitionedRegionDataStore ds;
        if (dp.withPartitioning()
            && (ds = (pr = (PartitionedRegion)region).getDataStore()) != null) {
          final PartitionedRegion leaderRegion = ColocationHelper
              .getLeaderRegion(pr);
          boolean isLeaderPersistent = leaderRegion.getDataPolicy().withPersistence();
          boolean isShadowPRPersistent = false;
          
          List<PartitionedRegion> childRegions = ColocationHelper
              .getColocatedChildRegions(leaderRegion);
          for (PartitionedRegion childRegion : childRegions) {
            if (childRegion.isShadowPR()
                && childRegion.getDataPolicy().withPersistence()) {
              isShadowPRPersistent = true;
              break;
            }
          }
          // if bucket was persisted then it can be hosting else not
          for (final BucketRegion breg : ds.getAllLocalBucketRegions()) {
            if (!((isLeaderPersistent || isShadowPRPersistent) && !breg
                .getBucketAdvisor().isPrimary())) {
              GemFireContainer gfcontainer = (GemFireContainer)pr.getUserAttribute();
              if (!gfcontainer.isGlobalIndex()) {
                Assert.fail("unexpected bucket created or selected as primary "
                    + "during DDL replay: " + breg + ", leaderRegion: "
                    + leaderRegion);
              }
            }
          }
        }
      }
    }
    // update profiles for all bucket/replicated regions to initialized
    getDistributionAdvisor().distributeNodeStatus(true);
    // Update profiles of all DistributedRegions indicating member has
    // initialized and cache ops can be routed.
    // For PRs initiate startup redundancy recovery.
    for (MemConglomerate conglom : uninitializedCongloms) {
      if ((container = conglom.getGemFireContainer()) != null
          && (region = container.getRegion()) != null) {
        if (GemFireXDUtils.TraceConglom) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM, String.format(
              "GemFireContainer#postDDLReplayInitializeRegions: post "
                  + "initialization for conglomerate [%s] region [%s]",
              conglom, region));
        }
        final DataPolicy dp = region.getDataPolicy();
        if (dp.withReplication() || !dp.withStorage()) {
            ((LocalRegion)region).initialized();
        }
        else if (dp.withPartitioning()) {
          pr = (PartitionedRegion)region;
          // update the PRConfig for child colocated region; this was skipped
          // during PR creation to avoid breaking colocation due to uninitialized
          // node (bug #42089).
          finishInitialization(pr);
          
          //Look for the shadow PR for this PR, and update the PR config
          //for that shadow PR as well. The shadow PR isn't tracked in
          //the list of uninitializedConglomerates, but it also needs to
          //be told to finish initialization.
          List<PartitionedRegion> childRegions = ColocationHelper.getColocatedChildRegions(pr);
          for(PartitionedRegion childRegion : childRegions) {
            if(childRegion.isShadowPR()) {
              finishInitialization(childRegion);
            }
          }
        }
      }
    }
    // finally execute any pending operations during DDL replay
    final GemFireTransaction tran = (GemFireTransaction)tc;
    synchronized (this.ddlReplayPendingOperations) {
      for (MemOperation op : this.ddlReplayPendingOperations) {
        op.doMe(tran, null, null);
      }
      this.ddlReplayPendingOperations.clear();
    }
  }
  
  public void finishInitialization(PartitionedRegion pr) {
    PartitionRegionConfig prConfig;
    final Region<?, ?> prRoot = PartitionedRegionHelper
        .getPRRoot(this.gemFireCache);
    final LogWriterI18n logger = this.gemFireCache.getLoggerI18n();
    final PartitionedRegion.RegionLock lock = pr.getRegionLock();
    boolean lockAcquired = false;
    try {
      if (logger.fineEnabled()) {
        logger.fine("GemFireContainer#postDDLReplayInitializeRegions: "
            + "obtaining lock for region " + pr.getFullPath());
      }
      lock.lock();
      lockAcquired = true;
      pr.checkReadiness();
      prConfig = (PartitionRegionConfig)prRoot.get(pr
          .getRegionIdentifier());
      pr.updatePRConfig(prConfig, true);
    } catch (IllegalStateException ise) {
      if (logger.fineEnabled()) {
        logger.fine("GemFireContainer#postDDLReplayInitializeRegions: "
            + "unable to obtain lock for region " + pr);
      }
      pr.cleanupFailedInitialization();
      throw new PartitionedRegionException(LocalizedStrings
          .PartitionedRegion_CAN_NOT_CREATE_PARTITIONEDREGION_FAILED_TO_ACQUIRE_REGIONLOCK
              .toLocalizedString(), ise);
    } finally {
      if (lockAcquired) {
        lock.unlock();
        if (logger.fineEnabled()) {
          logger.fine("GemFireContainer#postDDLReplayInitializeRegions: "
              + "released lock for region " + pr.getFullPath());
        }
      }
    }
    
    if(pr.isDataStore()) {
      // if required schedule startup redundancy recovery that was skipped
      // after region creation
      pr.getRedundancyProvider().scheduleCreateMissingBuckets();
      pr.getRedundancyProvider().scheduleRedundancyRecovery(null);
    }
    
    if(pr.isShadowPR()) {
      //Wait for bucket recovery, which was deferred during the shadow PR
      //creation
      pr.shadowPRWaitForBucketRecovery();
    }
  }

  public final TLongHashSet getProcessedDDLIDs() {
    return this.ddlIDsProcessedSet;
  }

  // PersistentSet implementation

  // Note that xactProperties shares the same serviceProperties and
  // serviceProperyDefaults so those can be used interchangeably

  @Override
  public Serializable getProperty(String key) throws StandardException {
    return (Serializable)this.serviceProperties.get(key);
  }

  @Override
  public Serializable getPropertyDefault(String key) throws StandardException {
    return (Serializable)this.servicePropertiesDefaults.get(key);
  }

  @Override
  public void setProperty(String key, Serializable value, boolean dbOnlyProperty)
      throws StandardException {
    this.xactProperties.setProperty(null, key, value, dbOnlyProperty);
    if (key.contains("auth") || key.contains("security")) {
      // refresh the cached access-level in authorizers
      final GemFireXDUtils.Visitor<LanguageConnectionContext> refresh =
          new GemFireXDUtils.Visitor<LanguageConnectionContext>() {
            @Override
            public boolean visit(LanguageConnectionContext lcc) {
              Authorizer authorizer = lcc.getAuthorizer();
              if (authorizer != null) {
                try {
                  authorizer.refresh();
                } catch (StandardException se) {
                  // log a warning and move on
                  SanityManager.DEBUG_PRINT("warning:"
                          + GfxdConstants.TRACE_AUTHENTICATION,
                      "Exception in refreshing access-level", se);
                }
              }
              return true;
            }
          };
      GemFireXDUtils.forAllContexts(refresh);
    }
  }

  @Override
  public void setPropertyDefault(String key, Serializable value)
      throws StandardException {
    this.xactProperties.setPropertyDefault(null, key, value);
  }

  @Override
  public boolean propertyDefaultIsVisible(String key) throws StandardException {
    return !this.serviceProperties.containsKey(key);
  }

  @Override
  public Properties getProperties() {
    return (Properties)this.serviceProperties.clone();
  }

  // End PersistentSet implementation

  /**
   * Get the {@link GfxdDDLRegionQueue} instance that holds the DDL puts.
   */
  public final GfxdDDLRegionQueue getDDLStmtQueue() throws StandardException {
    final GfxdDDLRegionQueue ddlQueue = this.ddlStmtQueue;
    assert ddlQueue != null;
    if (this.advisee.getVMKind().isAccessorOrStore()) {
      return ddlQueue;
    }
    else {
      // this VM is a locator/agent/admin
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, null,
          "GemFireXD: cannot execute DDL statements on this JVM of type "
              + this.advisee.getVMKind().toString().toUpperCase());
    }
  }

  /**
   * Get the {@link GfxdDDLRegionQueue} instance that holds the DDL puts without
   * checking for null queue.
   */
  public final GfxdDDLRegionQueue getDDLQueueNoThrow() {
    return this.ddlStmtQueue;
  }

  /**
   * Return true if this VM has a valid full DDL statement queue, or a
   * restricted one for locators/admins/agents that will only replay
   * authentication and related DDLs.
   */
  public final boolean restrictedDDLStmtQueue() {
    return !this.advisee.getVMKind().isAccessorOrStore();
  }

  /**
   * Get the {@link GfxdDRWLockService} instance used for concurrent DDL and DML
   * execution locking.
   */
  public final GfxdDRWLockService getDDLLockService() {
    return this.ddlLockService;
  }

  public final FabricDatabase getDatabase() {
    return this.database;
  }

  public boolean canSupport(String identifier, Properties startParams) {

    if (startParams == null) {
      return true;
    }

    String impl = startParams.getProperty("gemfirexd.access");

    if (impl == null) {
      return false;
    }

    return supportsImplementation(impl);
  }

  /**
   * Return true if the given implementation ID is supported by the store i.e.
   * when implementation ID is equal to {@link #IMPLEMENTATIONID}
   */
  public boolean supportsImplementation(String implementationId) {
    return implementationId.equals(IMPLEMENTATIONID);
  }

  public synchronized void stop() {
    if (bootingInstance == null) {
      return;
    }

    final ExternalCatalog externalCatalog = this.externalCatalog;
    if (externalCatalog != null) {
      externalCatalog.close();
    }
    // stop spark executor if it is running
    CallbackFactoryProvider.getClusterCallbacks().stopExecutor();

    // stop the management service
    GfxdManagementService.handleEvent(GfxdResourceEvent.FABRIC_DB__STOP, this);

    // invoke any remaining finalizers
    FinalizeObject.getServerHolder().invokePendingFinalizers();
    FinalizeObject.clearFinalizationQueues();

    if (this.thresholdListener != null) {
      this.thresholdListener.stop();
    }
    final ConnectionSignaller signaller = ConnectionSignaller.signalStop();
    this.advisee.stop(this.gemFireCache);

    // clear the connections before closing cache

    // in some cases an execution thread may be blocked on something
    // while holding the sync on connection which will cause the connection
    // close to block, so spawn a new thread to do this and wait for a limited
    // time before stopping
    final Thread stopper = new Thread(new Runnable() {
      @Override
      public void run() {
        GfxdConnectionHolder.getHolder().clear();
      }
    });
    try {
      stopper.join(3000L);
      if (stopper.isAlive()) {
        // interrupt the stopper
        stopper.interrupt();
        stopper.join(2000L);
      }
    } catch (InterruptedException ie) {
      // ignore further interrupted exceptions here
    }

    //Assuming cache close will take care of the rest.
    // TODO: Handle it better. Nullifying after cache.close() isn't an option as of now.
    AuthenticationServiceBase.setIsShuttingDown(true);
    LogWriter logger = null;
    GemFireCacheImpl cache = this.gemFireCache;
    try {

    if (cache != null) {
      logger = cache.getLogger();
      if (signaller != null) {
        if (logger.fineEnabled()) {
          logger.fine(signaller.toString() + " stopped.");
        }
      }
      if (logger.infoEnabled()) {
        logger.info("Disconnecting GemFire distributed system and "
            + "stopping GemFireStore");
      }
      if (!cache.isClosed() && !this.skipCacheClose) {
        if (this.isShutdownAll) {
          cache.shutDownAll();
          //this.isShutdownAll = false;
        }
        else {
          if (!cache.forcedDisconnect()) {
            cache.close();
          }
        }
      }
      cache.setRecoverMode(false);
      this.gemFireCache = null;
      this.gfxdDefaultDiskStore = null;
      this.identityRegion = null;   
      this.ddlStmtQueue = null;
    }
    else {
      cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed() && !cache.forcedDisconnect()) {
        if (this.isShutdownAll) {
          cache.shutDownAll();
        }
        else {
          cache.close();
        }
      }
    }
    if (cache == null || !cache.forcedDisconnect()) {
      InternalDistributedSystem sys = InternalDistributedSystem
          .getAnyInstance(); // getConnectedInstance();
      if (sys != null && sys.isConnected()) {
        sys.getLogWriter()
            .info("Disconnecting GemFire distributed system.");
        sys.disconnect();
      }
    }

    } catch (Exception e) {
      // ignore exceptions during stop for shutdown to continue
      SanityManager.DEBUG_PRINT("error:"
          + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
          "GemFireStore exception in GemFireCache stop", e);
    }

    // If the cache close has happened properly then put a flag
    // in the data dictionary directory indicating proper close
    // putNoCrashIndicator();
    synchronized (this.uninitializedConglomerates) {
      this.uninitializedConglomerates.clear();
    }
    this.conglomerates.clear();
    
    // now its safe to cleanup the authentication service. 
    // We can't depend on Authentication or FabricDatabase stop(..)
    // to cleanup the auth service, as order of stopping the module
    // cannot be controlled.
    // Aparently GemFire background threads are still active and exchanging
    // information. So, here too we can't nullify the service.
    // We now added try/finally around cache.close() and use 
    // that as indicator to return 'true' always from the authenticator.
    // AuthenticationServiceBase.setPeerAuthenticationService(null);

    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
        "GemFireStore service stopped successfully, notifying status ... ");

    final FabricService service = FabricServiceManager
        .currentFabricServiceInstance();
    if (service != null) {
      assert service instanceof FabricServiceImpl;
      ((FabricServiceImpl)service).notifyStop(service.isReconnecting());
    }

    // clear any GemFireXDQueryObserver instance
    GemFireXDQueryObserverHolder.clearInstance();
    clearStatics(false);
    this.serviceProperties.clear();
    this.ddlIDsProcessedSet.clear();

    // also clean the static PrintWriter in SanityManager
    SanityManager.CLEAR_DEBUG_STREAM(null);

    ClientSharedUtils.clear();

    bootedInstance = null;
    bootingInstance = null;
  }

  public final LocalRegion getIdentityRegion() {
    return this.identityRegion;
  }

  public final String getLocale() {
    return this.dbLocaleStr;
  }

  /**
   * Get the default disk-store used for application tables, queues etc.
   */
  public final DiskStoreImpl getDefaultDiskStore() {
    return this.gfxdDefaultDiskStore;
  }

  /**
   * Returns true if the {@link DataDictionary} is being persisted to disk.
   */
  public final boolean isDataDictionaryPersistent() {
    return this.persistingDD;
  }

  /**
   * Returns true if the indexes need to persist if the base table is persistent 
   */
  public final boolean isPersistIndexes() {
    return this.persistIndexes;
  }

  /**
   * Returns true if the default data policy for tables is partitioned, else the
   * default is replicated.
   */
  public final boolean isTableDefaultPartitioned() {
    return this.tableDefaultPartitioned;
  }

  private void clearStatics(boolean forBoot) {
    // clear statics unless we're rebooting
    if (InternalDistributedSystem.getReconnectCount() <= 0) {
      if (forBoot) { // stop has already cleared GfxdConnectionHolder in stop()
        GfxdConnectionHolder.getHolder().clear();
      }
      GemFireXDUtils.reset(forBoot);
      // Neeraj: Not clearing the gemfirexd registered
      // classes here in order to avoid de-serialization
      // errors when the vm is going down.
      // DSFIDFactory.clearGemFireXDClasses();
      // Instead do this at the boot time itself
      CallbackProcedures.clearStatics();
      DistributionObserver.clearStatics();
      // remove flag to indicate GemFireXD product
      GemFireCacheImpl.setGFXDSystem(false);
      selfMemId = null;
      GlobalIndexCacheWithLocalRegion.setCacheToNull();
      this.externalCatalog = null;
      System.clearProperty(FORCE_LOCATOR_DM_TYPE);
    }
  }

  /**
   * Get the GemFireXD {@link DistributionAdvisee} (that uses
   * {@link GfxdDistributionAdvisor} to exchange GemFireXD profile) for this VM.
   */
  public final StoreAdvisee getAdvisee() {
    return this.advisee;
  }

  /**
   * Get the GemFireXD {@link DistributionAdvisor} that exchanges the GemFireXD
   * profile for this VM.
   */
  public final GfxdDistributionAdvisor getDistributionAdvisor() {
    return this.advisee.getDistributionAdvisor();
  }

  public boolean didIndexRecovery() {
    return true;
  }

  
  public boolean isHadoopGfxdLonerMode() {
    return this.isHadoopGfxdLonerMode;
  }
  
  // ------------------------ Methods below are not used for GemFireXD

  public TransactionInfo[] getTransactionInfo() {
    if (SanityManager.DEBUG)
      SanityManager.ASSERT(ttab != null, "transaction table is null");
    return ttab.getTransactionInfo();
  }

  public void freeze() throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void unfreeze() throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void backup(String backupDir, boolean wait) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void backupAndEnableLogArchiveMode(String backupDir,
      boolean deleteOnlineArchivedLogFiles, boolean wait)
      throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void disableLogArchiveMode(boolean deleteOnlineArchivedLogFiles)
      throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void checkpoint() throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void waitForPostCommitToFinishWork() {
    throw new UnsupportedOperationException();
  }

  public Object startXATransaction(ContextManager cm, int format_id,
      byte[] global_id, byte[] branch_id) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public Object getXAResourceManager() throws StandardException {
    return null;
  }

  public void startReplicationMaster(String dbmaster, String host, int port,
      String replicationMode) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void stopReplicationMaster() throws StandardException {
    throw new UnsupportedOperationException();
  }

  public void failover(String dbname) throws StandardException {
    throw new UnsupportedOperationException();
  }

  public MethodFactory findMethodFactoryByFormat(UUID format) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TransactionController getAndNameTransaction(ContextManager cm,
      String transName) throws StandardException {
    return this.getAndNameTransaction(cm, transName, -1);
  }

  public final GfxdHeapThresholdListener thresholdListener() {
    return thresholdListener;
  }

  /** flag for tests to record the list of containers recovered from disk */
  public static void setTestNewIndexFlag(boolean flag) {
    DiskStoreImpl.TEST_NEW_CONTAINER = flag;
  }

  public void setShutdownAllMode() {
    this.isShutdownAll = true;
  }

  public boolean isShutdownAll() {
    return isShutdownAll;
  }
  
  public StoreStatistics getStoreStatistics() {
    return storeStats;
  }

  public IndexPersistenceStats getIndexPersistenceStats() {
    return indexPersistenceStats;
  }

  // The first access of this will instantiate the snappy catalog
	public void initExternalCatalog() {
    if (this.externalCatalog == null) {
      synchronized (this) {
        if (this.externalCatalog == null) {
          // Instantiate using reflection
          try {
            this.externalCatalog = (ExternalCatalog)ClassPathLoader
                .getLatest().forName("io.snappydata.sql.catalog.impl.StoreHiveCatalog")
                .newInstance();
          } catch (InstantiationException | IllegalAccessException
              | ClassNotFoundException e) {
            throw new IllegalStateException(
                "could not instantiate the snappy catalog", e);
          }
        }
      }
    }
    if (this.externalCatalog == null) {
      throw new IllegalStateException("Could not instantiate snappy catalog");
    }
  }

  public void setExternalCatalogInit(Future<?> init) {
    this.externalCatalogInit = init;
  }

  public static boolean handleCatalogInit(Future<?> init) {
    try {
      init.get(60, TimeUnit.SECONDS);
      return true;
    } catch (java.util.concurrent.TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Get the {@link ExternalCatalog} or wait for it to be initialized, or else
   * throw a {@link TimeoutException} if wait failed unsuccessfully but never
   * return a null.
   */
  public ExternalCatalog getExistingExternalCatalog() {
    ExternalCatalog catalog;
    int cnt = 0;
    // retry catalog get after some sleep
    while ((catalog = getExternalCatalog()) == null && ++cnt < 500) {
      Throwable t = null;
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        t = ie;
      }
      // check for JVM going down
      Misc.checkIfCacheClosing(t);
    }
    if (catalog != null) {
      return catalog;
    } else {
      throw new TimeoutException(
          "The SnappyData catalog in hive meta-store is not accessible");
    }
  }

  public ExternalCatalog getExternalCatalog() {
    return getExternalCatalog(true);
  }

  /** fullInit = true is to wait for any catalog inconsistencies to be cleared */
  public ExternalCatalog getExternalCatalog(boolean fullInit) {
    final ExternalCatalog externalCatalog;
    if ((externalCatalog = this.externalCatalog) != null &&
        externalCatalog.waitForInitialization()) {
      if (fullInit) {
        final Future<?> init = this.externalCatalogInit;
        if (init != null && !Boolean.TRUE.equals(externalCatalogInitThread.get())
            && !handleCatalogInit(init)) {
          return null;
        }
      }
      return externalCatalog;
    } else {
      return null;
    }
  }

  public void setDBName(String dbname) {
    // set only once
    if (this.databaseName == null) {
      this.databaseName = dbname;
      if (this.databaseName.equalsIgnoreCase("snappydata")) {
        this.snappyStore = true;
        this.database.setdisableStatementOptimizationToGenericPlan();
        gemFireCache.DEFAULT_SNAPSHOT_ENABLED = true;
        gemFireCache.startOldEntryCleanerService();
      }
    }
    ClientSharedUtils.setThriftDefault(this.snappyStore);
  }

  private String databaseName;
  private boolean snappyStore;

  public boolean isSnappyStore() {
    return this.snappyStore;
  }

  public String getDatabaseName() {
    return this.databaseName;
  }

  public String getBasePersistenceDir() {
    return this.persistenceDir;
  }

  /**
   * Enumeration for the different kinds of GemFireXD VMs.
   */
  public static class VMKind implements InternalDistributedSystem.MemberKind {

    /** name of this VM kind */
    private final String name;

    /** ordinal for this instance */
    private final int ordinal;

    /** global array of the values indexed by the ordinals */
    private static final VMKind[] values = new VMKind[7];

    protected VMKind(final String name, final int ordinal) {
      if (values[ordinal] != null) {
        throw new IllegalStateException("ordinal " + ordinal
            + " already assigned");
      }
      this.name = name;
      this.ordinal = ordinal;
      values[ordinal] = this;
    }

    /** indicates that this VM is a GemFireXD datastore */
    public static final VMKind DATASTORE = new VMKind(SharedUtils.VM_DATASTORE,
        0);

    /** indicates that this VM is a GemFireXD accessor that holds no data */
    public static final VMKind ACCESSOR = new VMKind(SharedUtils.VM_ACCESSOR, 1);

    /**
     * indicates that this VM is a GemFireXD stand-alone locator started using
     * <code>GfxdDistributionLocator</code>
     */
    public static final VMKind LOCATOR = new VMKind(SharedUtils.VM_LOCATOR, 2);

    /**
     * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
     * jmx agent started using <code>AdminDistributedSystem</code>
     */
    public static final VMKind AGENT = new VMKind(SharedUtils.VM_AGENT, 3);
    
    /**
     * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
     * admin started using <code>GfxdSystemAdmin</code>
     */
    public static final VMKind ADMIN = new VMKind(SharedUtils.VM_ADMIN, 6);
    
    /** get a unique ordinal for this instance */
    public final int ordinal() {
      return this.ordinal;
    }

    /** get the instance from the given ordinal */
    public static VMKind fromOrdinal(final int ordinal) {
      return values[ordinal];
    }

    /** get the name of this VM kind */
    @Override
    public final String toString() {
      return this.name;
    }

    /**
     * @see InternalDistributedSystem.MemberKind#isAccessor()
     */
    @Override
    public final boolean isAccessor() {
      return this == ACCESSOR;
    }

    /**
     * @see InternalDistributedSystem.MemberKind#isLocator()
     */
    @Override
    public final boolean isLocator() {
      return this == LOCATOR;
    }

    /**
     * @see InternalDistributedSystem.MemberKind#isStore()
     */
    @Override
    public final boolean isStore() {
      return this == DATASTORE;
    }

    public final boolean isAccessorOrStore() {
      return this == ACCESSOR || this == DATASTORE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isAgent() {
      return this == AGENT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isAdmin() {
      return this == ADMIN;
    }
  }

  /**
   * {@link DistributionAdvisee} for GemFireXD that uses
   * {@link GfxdDistributionAdvisor} to exchange
   * {@link GfxdDistributionAdvisor.GfxdProfile}s containing GemFireXD
   * {@link VMKind} and server groups information.
   * 
   * @author swale
   */
  public static final class StoreAdvisee implements DistributionAdvisee {

    /**
     * Serial number used for {@link DistributionAdvisee#getSerialNumber()}.
     */
    private final int serialNumber = DistributionAdvisor.createSerialNumber();

    /**
     * The DistributionAdvisor for GemFireXD used to propagate the GemFireXD
     * {@link VMKind} and server groups to other VMs.
     */
    private volatile GfxdDistributionAdvisor advisor;

    /**
     * Volatile set of server groups of this VM that is COW.
     */
    private volatile SortedSet<String> serverGroups;

    private final CancelCriterion stopper = new CancelCriterion() {

      @Override
      public RuntimeException generateCancelledException(Throwable t) {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final RuntimeException ce;
        if (cache != null && (ce = cache.getCancelCriterion()
            .generateCancelledException(t)) != null) {
          return ce;
        }
        if (Monitor.inShutdown()) {
          return new CacheClosedException(MessageService.getCompleteMessage(
              SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null), t);
        }
        return new CacheClosedException(
            LocalizedStrings.CacheFactory_A_CACHE_HAS_NOT_YET_BEEN_CREATED
                .toLocalizedString(), t);
      }

      @Override
      public String cancelInProgress() {
        String cancel;
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache == null) {
          return LocalizedStrings.CacheFactory_A_CACHE_HAS_NOT_YET_BEEN_CREATED
              .toLocalizedString();
        }
        else if ((cancel = cache.getCancelCriterion()
            .cancelInProgress()) != null) {
          return cancel;
        }
        if (Monitor.inShutdown()) {
          return MessageService.getCompleteMessage(
              SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN, null);
        }
        return null;
      }
    };

    /** start the {@link DistributionAdvisor} in this VM */
    private synchronized void start(SortedSet<String> groups,
        GemFireCacheImpl cache, LogWriter logger) {
      this.serverGroups = groups;
      this.advisor = GfxdDistributionAdvisor.createGfxdDistributionAdvisor(
          this, cache);
      cache.setGfxdAdvisee(this);
      this.advisor.handshake(logger);
    }

    /** stop the {@link DistributionAdvisor} in this VM and clear up stuff */
    private synchronized void stop(GemFireCacheImpl cache) {
      if (this.advisor != null && this.advisor.isInitialized()) {
        this.advisor.close();
      }
      // remove self's reference as DistributionAdvisee from GemFireCache
      if (cache != null) {
        cache.setGfxdAdvisee(null);
      }
      this.advisor = null;
    }

    /**
     * Get the {@link VMKind} of this VM.
     */
    public final VMKind getVMKind() {
      final GemFireStore store = GemFireStore.getBootingInstance();
      if (store != null) {
        return store.myKind; // volatile read
      }
      throw new CacheClosedException("StoreAdvisee#getVMKind: no store found."
          + " GemFireXD not booted or closed down.");
    }

    /**
     * Set the {@link VMKind} for this VM.
     */
    final synchronized void setVMKind(VMKind newKind) {
      final GemFireStore store = GemFireStore.getBootingInstance();
      if (store != null) {
        store.myKind = newKind; // volatile write
      }
      throw new CacheClosedException("StoreAdvisee#setVMKind: no store found."
          + " GemFireXD not booted or closed down.");
    }

    /**
     * Get the server groups of this VM.
     */
    final SortedSet<String> getServerGroups() {
      return this.serverGroups; // volatile read
    }

    /**
     * Set the server groups of this VM.
     */
    final synchronized void setServerGroups(SortedSet<String> groups) {
      this.serverGroups = groups;
    }

    // ------------------------ DistributionAdvisee implementation begin

    @Override
    public void fillInProfile(Profile p) {
      assert p instanceof GfxdDistributionAdvisor.GfxdProfile;

      final GfxdDistributionAdvisor.GfxdProfile profile =
        (GfxdDistributionAdvisor.GfxdProfile)p;
      profile.setServerGroups(this.serverGroups); // volatile read
      profile.setVMKind(getVMKind()); // volatile read
      profile.setPersistentDD(Misc.getMemStoreBooting()
          .isDataDictionaryPersistent());
      profile.setLocale(Misc.getMemStoreBooting().getLocale());
      profile.serialNumber = getSerialNumber();
    }

    @Override
    public Profile getProfile() {
      return this.advisor.getMyProfile();
    }

    @Override
    public InternalDistributedSystem getSystem() {
      return Misc.getDistributedSystem();
    }

    @Override
    public CancelCriterion getCancelCriterion() {
      return this.stopper;
    }

    @Override
    public DM getDistributionManager() {
      return getSystem().getDistributionManager();
    }

    @Override
    public GfxdDistributionAdvisor getDistributionAdvisor() {
      return this.advisor;
    }

    @Override
    public final String getName() {
      return "GemFireXD.StoreAdvisee";
    }

    @Override
    public final String getFullPath() {
      return getName();
    }

    @Override
    public DistributionAdvisee getParentAdvisee() {
      return null;
    }

    @Override
    public int getSerialNumber() {
      return this.serialNumber;
    }

    // ------------------------ DistributionAdvisee implementation end
  } // StoreAdisee

  
  public static final class GfxdStatisticsSampleCollector implements StatsSamplerCallback {
    
    private static final GfxdStatisticsSampleCollector _theInstance = new GfxdStatisticsSampleCollector();
    
    public static final GfxdStatisticsSampleCollector getInstance() {
      return _theInstance;
    }

    @Override
    public void prepareSamples(boolean prepareOnly) {
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        assert service instanceof FabricServiceImpl;
        for (NetworkInterface nw : service.getAllNetworkServers()) {
          NetworkInterfaceImpl nwImpl = (NetworkInterfaceImpl)nw;
          nwImpl.collectStatisticsSample();
        }
      }
    }
  } // StatsSampleCollector

  public static final class StoreStatistics {
    
    private Statistics stats;
    
    private int[] memoryAnalytics;
    private int[] statementPlan;
    private int[] statementStats;
    private int[] queryCancellationStats;
    private int[] timeoutCancellationStats;
    
    public void init(StatisticsFactory factory) {

      try {
        final StatisticsTypeFactory tf = StatisticsTypeFactoryImpl.singleton();
        
        // memory analytics stats
        StatisticDescriptor maNumInvocations = tf.createIntCounter(
            "maNumInvocations", "Number of invocations to MemoryAnalytics",
            "executions");
        StatisticDescriptor maNumInternalInvocations = tf
            .createIntCounter(
                "maNumInternalInvocations",
                "Number of invocations to MemoryAnalytics triggered for internal purposes.",
                "executions");
        StatisticDescriptor maExecuteTime = tf.createLongCounter(
            "maExecuteTime",
            "Time taken to evaluate MemoryAnalytics size computation.",
            "nanoseconds");
        StatisticDescriptor maInternalExecuteTime = tf
            .createLongCounter(
                "maInternalExecuteTime",
                "Time taken on to evaluate MemoryAnalytics size computation during internal usage.",
                "nanoseconds");

        // statement plan / explain stats
        StatisticDescriptor spNumCollections = tf.createIntCounter(
            "spNumCollections",
            "Number of times explain or statement plan invocations happened.",
            "executions");
        StatisticDescriptor spCollectionsTime = tf
            .createLongCounter(
                "spCollectionsTime",
                "Time taken to collect execution plan triggered via explain or statement plan collection.",
                "nanoseconds");
        StatisticDescriptor spQueryTime = tf
            .createLongCounter(
                "spQueryTime",
                "Time taken to execute the query passed into explain or statement plan collection.",
                "nanoseconds");
        StatisticDescriptor spNumRemoteCollections = tf.createIntCounter(
            "spNumRemoteCollections",
            "Remote node equivalent of spNumCollections.", "executions");
        StatisticDescriptor spRemoteCollectionsTime = tf.createLongCounter(
            "spRemoteCollectionsTime",
            "Remote node equivalent of spCollectionsTime.", "nanoseconds");
        StatisticDescriptor spRemoteQueryTime = tf.createLongCounter(
            "spRemoteQueryTime", "Remote node equivalent of spQueryTime.",
            "nanoseconds");

        // statement statistics stats
        StatisticDescriptor ssNumCollections = tf.createIntCounter(
            "ssNumCollections", "Number of statement statistics collections.",
            "executions");
        StatisticDescriptor ssCollectionsTime = tf.createLongCounter(
            "ssCollectionsTime", "Time taken to collect statement statistics",
            "nanoseconds");

        
        // query cancellation stats
        StatisticDescriptor qcNumQueriesCancelled = tf.createIntCounter(
            "qcNumQueriesCancelled", "Number of queries cancelled due to CRITICAL_UP heap notification.",
            "executions");
        StatisticDescriptor qcMemoryUsageComputeTime = tf.createLongCounter(
            "qcMemoryUsageComputeTime", "Time taken to determine highest memory consuming active query & cancel it.",
            "nanoseconds");

        
        StatisticDescriptor qtoNumQueriesCancelled = tf.createIntCounter(
            "qtoNumQueriesTimedOut", "Number of queries cancelled due to QueryTimeOut settings during execution.",
            "operations");
        
        StatisticsType type = tf.createType(
            "storeStatistics",
            "GemFireXD Store level common set of statistics",
            new StatisticDescriptor[] {
                maNumInvocations, maNumInternalInvocations, maExecuteTime, maInternalExecuteTime, 
                spNumCollections, spCollectionsTime, spQueryTime, spNumRemoteCollections, spRemoteCollectionsTime, spRemoteQueryTime,
                ssNumCollections, ssCollectionsTime, qcNumQueriesCancelled, qcMemoryUsageComputeTime, qtoNumQueriesCancelled
            }
        );

        memoryAnalytics = new int[] { maNumInvocations.getId(),
            maExecuteTime.getId(), maNumInternalInvocations.getId(),
            maInternalExecuteTime.getId() };
        
        statementPlan = new int[] { spNumCollections.getId(),
            spCollectionsTime.getId(), spQueryTime.getId(),
            spNumRemoteCollections.getId(), spRemoteCollectionsTime.getId(),
            spRemoteQueryTime.getId() };
        
        statementStats = new int[] {ssNumCollections.getId(), ssCollectionsTime.getId()};

        queryCancellationStats = new int[] {qcNumQueriesCancelled.getId(), qcMemoryUsageComputeTime.getId()};
        
        timeoutCancellationStats = new int[] {qtoNumQueriesCancelled.getId()};
        
        stats = factory.createAtomicStatistics(type, "StoreStatistics");
        
      } catch (Exception e) {
        SanityManager.DEBUG_PRINT("warning:ClassCreate",
            "Got exception while loading class " + StoreStatistics.class.getName()
                + "  ex = " + e, e);
        throw new RuntimeException(e);
      }
    }

    public void collectMemoryAnalyticsStats(final long executeTime, final boolean isInternal) {
      if(!isInternal) {
         stats.incInt(memoryAnalytics[0], 1);
         stats.incLong(memoryAnalytics[1], executeTime);
      }
      else {
        stats.incInt(memoryAnalytics[2], 1);
        stats.incLong(memoryAnalytics[3], executeTime);
      }
    }
    
    public void collectStatementPlanStats(final long collectionTime, final boolean isRemote) {
      if(!isRemote) {
        stats.incInt(statementPlan[0], 1);
        stats.incLong(statementPlan[1], collectionTime);
      } else {
        stats.incInt(statementPlan[3], 1);
        stats.incLong(statementPlan[4], collectionTime);
      }
      
    }
    
    public void statementPlanQueryTime(final long queryTime, final boolean isRemote) {
      if(!isRemote) {
        stats.incLong(statementPlan[1], queryTime);
      }
      else {
        stats.incLong(statementPlan[5], queryTime);
      }
    }
    
    public void collectStatementStatisticsStats(final long collectionTime) {
      stats.incInt(statementStats[0], 1);
      stats.incLong(statementStats[1], collectionTime);
    }

    public void collectQueryCancelledStats(final long memoryEstimateComputeTime) {
      stats.incInt(queryCancellationStats[0], 1);
      stats.incLong(queryCancellationStats[1], memoryEstimateComputeTime);
    }
    
    public void collectQueryTimeOutStats() {
      stats.incInt(timeoutCancellationStats[0], 1);
    }
  }

  private final Object indexLoadSync;

  private boolean indexLoadBegin;

  public void markIndexLoadBegin() {
    synchronized (this.indexLoadSync) {
      this.indexLoadBegin = true;
      this.indexLoadSync.notifyAll();
    }
  }

  public boolean waitForIndexLoadBegin(long waitMillis) {
    synchronized (this.indexLoadSync) {
      if (!this.indexLoadBegin) {
        long endTime;
        if (waitMillis <= 0) {
          endTime = Long.MAX_VALUE;
        }
        else {
          long startTime = System.currentTimeMillis();
          endTime = startTime + waitMillis;
          if (endTime < startTime) {
            endTime = Long.MAX_VALUE;
          }
        }
        final long loopMillis = 500L;
        while (!this.indexLoadBegin && endTime > System.currentTimeMillis()) {
          Throwable t = null;
          try {
            this.indexLoadSync.wait(loopMillis);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            t = ie;
          }
          // check for JVM going down
          Misc.checkIfCacheClosing(t);
        }
        return this.indexLoadBegin;
      }
      else {
        return true;
      }
    }
  }

  public void setMetadataCmdRgn(Region gcr) {
    this.snappyMetadataCmdRgn = gcr;
  }

  public Region<String, String> getMetadataCmdRgn() {
    return this.snappyMetadataCmdRgn;
  }

  private boolean restrictTableCreation = Boolean.getBoolean(
      Property.SNAPPY_RESTRICT_TABLE_CREATE);

  private boolean rlsEnabled = Boolean.getBoolean(
      Property.SNAPPY_ENABLE_RLS);

  // TODO: this internal property is only for some unit tests and should be removed
  // by updating the tests to use LDAP server (see PolictyTestBase in SnappyData)
  public static boolean ALLOW_RLS_WITHOUT_SECURITY = false;

  public boolean tableCreationAllowed() {
    return !this.restrictTableCreation;
  }

  /** returns true if row-level security is enabled on the system */
  public boolean isRLSEnabled() {
    return rlsEnabled;
  }

  private volatile PersistentStateInRecoveryMode persistentStateMsg = null;

  public void setPersistentStateMsg(PersistentStateInRecoveryMode msg) {
    if (GemFireXDUtils.TraceDDLReplay) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
          "persistent state message is: " + msg);
    }
    this.persistentStateMsg = msg;
  }

  public PersistentStateInRecoveryMode getPersistentStateMsg() {
    return this.persistentStateMsg;
  }
}
