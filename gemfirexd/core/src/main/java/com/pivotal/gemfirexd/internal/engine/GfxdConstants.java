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

package com.pivotal.gemfirexd.internal.engine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import io.snappydata.thrift.snappydataConstants;

/**
 * A collection of constants used in GemFireXD.
 * 
 * Note that this file should not have any imports other than {@link Property}
 * and {@link Attribute} since it is also used by tools etc. which will pull in
 * everything unnecessarily. That will also cause problems of double class
 * loading etc. with tools jar causing unexpected behaviour that may not be
 * caught in regular test runs.
 * 
 * Note that when adding a new GemFireXD boot property, also add to the list in
 * {@link #validExtraGFXDProperties}.
 * 
 * @author Eric Zoerner
 * @author swale
 */
public interface GfxdConstants {

  /** The GemFireXD product name. */
  final String GEMFIREXD_PRODUCT_NAME = "GemFireXD";

  final String GFXD_PREFIX = PropertyUtil.isSQLFire ? Attribute.SQLF_PREFIX
      : Attribute.GFXD_PREFIX;

  String SNAPPY_PREFIX = "snappydata.";

  /** property name for schema name */
  final String PROPERTY_SCHEMA_NAME = GFXD_PREFIX + "schema-name";

  /** property name for table name */
  final String PROPERTY_TABLE_NAME = GFXD_PREFIX + "table-name";

  /** name of the meta region in which app jars will be installed */
  final String APP_JARS_REGION_NAME = "GFXD_APP_JARS";

  /** property indexed column positions */
  final String PROPERTY_INDEX_BASE_COL_POS = GFXD_PREFIX
      + "index-column-positions";

  /** property to pass the base table's descriptor for an index */
  final String PROPERTY_INDEX_BASE_TABLE_DESC = GFXD_PREFIX + "index-base-td";

  /* no longer supported
  /** property byte array store *
  final String PROPERTY_BYTEARRAY_STORE = GFXD_PREFIX + "bytearray-store";

  final String DEFAULT_BYTEARRAY_STORE = "true";
  */

  final String TABLE_DEFAULT_PARTITIONED_SYSPROP = GFXD_PREFIX
      + Attribute.TABLE_DEFAULT_PARTITIONED;
  final boolean TABLE_DEFAULT_PARTITIONED_DEFAULT = false;

  final String TEST_FLAG_ENABLE_CONCURRENCY_CHECKS =
      "gemfirexd.TEST_FLAG_ENABLE_CONCURRENCY_CHECKS";

  /** the default for "concurrency-checks-enabled" */
  //final boolean TABLE_DEFAULT_CONCURRENCY_CHECKS_ENABLED = false;

  // [sjigyasu] Default takes the value of the system property if set, else false
  final boolean TABLE_DEFAULT_CONCURRENCY_CHECKS_ENABLED =
      Boolean.getBoolean(TEST_FLAG_ENABLE_CONCURRENCY_CHECKS);

  final String PROPERTY_CONSTRAINT_TYPE = GFXD_PREFIX + "constraint-type";

  final String PROPERTY_BOOT_INDICATOR = Property.PROPERTY_RUNTIME_PREFIX
      + "fabricapi";

  enum BT_INDIC {
    FABRICAPI,
    FABRICAGENT,
    FIRSTCONNECTION;
  }
  
  final String AUTHENTICATION_SERVICE = "authentication";

  final String PEER_AUTHENTICATION_SERVICE = "peer.authentication";

  /** schema name for temp tables */
  final String SESSION_SCHEMA_NAME = "SESSION";

  /** the system catalog schema name */
  final String SYSTEM_SCHEMA_NAME = "SYS";

  /** table name for PropertyConglomerates */
  // {id} gets substituted with the conglomerate id
  final String PROP_CONGLOM_TABLE_NAME = "Properties_{id}";

  /** the property name for passing table template row during creation */
  final String PROP_TEMPLATE_ROW = "GEMFIRE_TEMPLATE_ROW";

  /** table name for temporary tables */
  // {id} gets substituted with the conglomerate id
  final String TEMP_TABLE_NAME = "temp_{id}";

  /** debug string */
  final String NOT_YET_IMPLEMENTED = "Not yet implemented";

  /** the property name for passing Gemfire region attributes */
  final String REGION_ATTRIBUTES_KEY = "GEMFIRE_REGION_ATTRIBUTES";

  /**
   * the property name for passing initial size of GemFire region as specified
   * by INITSIZE directive of CREATE TABLE
   */
  final String REGION_INITSIZE_KEY = "GEMFIRE_REGION_INITSIZE";

  String TABLE_ROW_ENCODER_CLASS_KEY = "TABLE_ROW_ENCODER_CLASS";

  /**
   * this string is used to pass the DistributionDescriptor between the
   * CreateTableNode and CreateTableConstantAction. It could be better just
   * modify the interface of generating CreateTableConstantAction
   */
  final String DISTRIBUTION_DESCRIPTOR_KEY = "GEMFIREXD_DISTRIBUTION_DESCRIPTOR";

  final String GFXD_SEC_PREFIX = "security.";

  final String GFXD_DISABLE_STATEMENT_MATCHING = GFXD_PREFIX
      + "no-statement-matching";
  
  final String GFXD_DISABLE_COLUMN_ELIMINATION = GFXD_PREFIX
      + "no-column-elimination";

  /**
   * The system property for {@link Attribute#SYS_PERSISTENT_DIR}.
   */
  public static final String SYS_PERSISTENT_DIR_PROP = GFXD_PREFIX
      + Attribute.SYS_PERSISTENT_DIR;

  /**
   * The system property for {@link Attribute#SYS_HDFS_ROOT_DIR}.
   */
  public static final String SYS_HDFS_ROOT_DIR_PROP = GFXD_PREFIX
      + Attribute.SYS_HDFS_ROOT_DIR;
  public static final String SYS_HDFS_ROOT_DIR_DEF = ".";

  /**
   * The default number of retries during failover before giving up (not
   * counting retries by GFE function execution or other GFE layer operations).
   */
  final short HA_NUM_RETRIES = 10000;

  /**
   * System property to enable collection of statistics at statement level.
   */
  final String GFXD_ENABLE_STATS = GFXD_PREFIX + Attribute.ENABLE_STATS;

  /**
   * System property to enable collection of time statistics at statement level.
   */
  final String GFXD_ENABLE_TIMESTATS = GFXD_PREFIX + Attribute.ENABLE_TIMESTATS;

  /**
   * The system property used to specify the derby's gfxd log file
   * <code>SingleStream.createDefaultStream</code>.
   */
  final String GFXD_LOG_FILE = GFXD_PREFIX + Attribute.LOG_FILE;

  /**
   * @see Attribute#GFXD_HOST_DATA
   */
  final String GFXD_HOST_DATA = GFXD_PREFIX + Attribute.GFXD_HOST_DATA;

  /**
   * @see Attribute#SERVER_GROUPS
   */
  final String GFXD_SERVER_GROUPS = GFXD_PREFIX + Attribute.SERVER_GROUPS;

  /**
   * @see Attribute#STAND_ALONE_LOCATOR
   */
  final String GFXD_STAND_ALONE_LOCATOR = GFXD_PREFIX +
      Attribute.STAND_ALONE_LOCATOR;

  /**
   * The prefix for client driver system properties.
   */
  final String GFXD_CLIENT_PREFIX = Attribute.CLIENT_JVM_PROPERTY_PREFIX;

  /**
   * The system property used to specify the client log file.
   */
  final String GFXD_CLIENT_LOG_FILE = GFXD_CLIENT_PREFIX + Attribute.LOG_FILE;

  /**
   * The indexType string for a GLOBAL HASH index.
   */
  final String GLOBAL_HASH_INDEX_TYPE = "GLOBALHASH";

  /*
   *The values of indexType
   *@author yjing
   */
  final String LOCAL_SORTEDMAP_INDEX_TYPE = "LOCALSORTEDMAP";

  final String LOCAL_HASH1_INDEX_TYPE = "LOCALHASH1";

  final String GFXD_DTDS = "GFXD_DATATYPEDESCRIPTORS";

  // end index types

  /**
   * Used as delimiter while creating region name for Hash Index.
   */
  final String INDEX_NAME_DELIMITER = "__";

  /**
   * The hint used in index/constraint creation to indicate whether
   * case-sensitive (default) or case-insensitive searches have to be used.
   */
  final String INDEX_CASE_SENSITIVE_PROP = "caseSensitive";

  /**
   * Protocol string used for obtaining connection
   */
  final String PROTOCOL = Attribute.PROTOCOL;

  /**
   * A random date selected to represent infinity value
   */
  final String INFINITY_DATE_STR = "1900-01-01";

  /**
   * A random time selected to represent infinity value
   */
  final String INFINITY_TIME_STR = "00:00:00";

  /**
   * A random timestamp selected to represent infinity value
   */
  final String INFINITY_TIMESTAMP_STR = "1900-01-01 00:00:00";

  // lock related properties

  final String DDL_LOCK_SERVICE = "gfxd-ddl-lock-service";

  /** Default value of {@link #MAX_LOCKWAIT}. */
  final int MAX_LOCKWAIT_DEFAULT = 300000;

  /**
   * An int representing max wait time for distributed lock in milliseconds.
   */
  final String MAX_LOCKWAIT = GFXD_PREFIX + "max-lock-wait";

  // end lock related properties

  /** property to set max size of chunks in DML operations */
  final String DML_MAX_CHUNK_SIZE_PROP = GFXD_PREFIX + "dml-max-chunk-size";

  /** default max size of chunks in DML operations or query results */
  final long DML_MAX_CHUNK_SIZE_DEFAULT = 4L * 1024L * 1024L;

  /**
   * property to set min size of results for which streaming or throttling is
   * considered in DML operations
   */
  final String DML_MIN_SIZE_FOR_STREAMING_PROP = GFXD_PREFIX
      + "dml-min-streaming-size";

  final long DML_MIN_SIZE_FOR_STREAMING_DEFAULT = 524288L;

  /**
   * property to set max number of millis to wait before sending back a chunk in
   * DML operations
   */
  final String DML_MAX_CHUNK_MILLIS_PROP = GFXD_PREFIX + "dml-max-chunk-millis";

  /**
   * default max number of millis to wait before sending back a chunk in DML
   * operations
   */
  final int DML_MAX_CHUNK_MILLIS_DEFAULT = 8;

  /**
   * property to set number of iterations after which a sample of ResultHolder
   * timings is taken
   */
  final String DML_SAMPLE_INTERVAL_PROP = GFXD_PREFIX + "dml-sample-interval";

  /**
   * default number of iterations after which a sample of ResultHolder timings
   * is taken
   */
  final int DML_SAMPLE_INTERVAL_DEFAULT = 15;

  /**
   * property to set whether DAP should send results in order from execution
   * nodes
   */
  final String PROCEDURE_ORDER_RESULTS_PROP = GFXD_PREFIX
      + "procedure-order-results";

  /** default sub-directory to use for DataDictionary persistence */
  final String DEFAULT_PERSISTENT_DD_SUBDIR = "datadictionary";

  /** default global index sub-directory to use for caching global indexes */
  final String DEFAULT_GLOBALINDEX_CACHE_SUBDIR = "globalIndex";
  
  /** Name of default disk store */
  final String GFXD_DEFAULT_DISKSTORE_NAME = "GFXD-DEFAULT-DISKSTORE";

  /** Name of disk store used by data dictionary */ 
  final String GFXD_DD_DISKSTORE_NAME ="GFXD-DD-DISKSTORE";

  /** Name of disk store used by global indexes */ 
  final String GFXD_GLOBALINDEX_DISKSTORE_NAME ="GFXD-GLOBALINDEX-DISKSTORE";

  /**
   * Name of default disk store used by snappydata's delta regions.
   */
  final String SNAPPY_DEFAULT_DELTA_DISKSTORE = "SNAPPY-INTERNAL-DELTA";

  /**
   * Suffix of disk store used by snappydata's delta regions
   * (appended to main diskstore name except for default diskstore).
   */
  final String SNAPPY_DELTA_DISKSTORE_SUFFIX = "-SNAPPY-DELTA";

  /**
   * default sub-directory to use for delta store
   */
  final String SNAPPY_DELTA_SUBDIR = "snappy-internal-delta";

  /**
   * maximum size of each oplog file used for delta disk stores
   */
  final int SNAPPY_DELTA_DISKSTORE_SIZEMB = 50;

  /** Name of meta-region used to store the max identity column value */ 
  final String IDENTITY_REGION_NAME ="__IDENTITYREGION2";

  /**
   * Database property to store schema version when recovering from pre 1.1
   * product data dictionary.
   */
  final String PRE11_RECOVERY_SCHEMA_VERSION = "PRE11_RECOVERY_SCHEMA_VERSION_";

  /**
   * A VM level property in milliseconds representing the time gap between
   * cancellation of memory intensive queries when memory limit reaches
   * CRITICAL_UP. If in between this time gap a CRITICAL_DOWN event is received
   * queries are not canceled anymore.
   */
  final String QUERY_CANCELLATION_TIME_INTERVAL = GFXD_PREFIX
      + "query-cancellation-interval";

  /**
   * Default for {@link #QUERY_CANCELLATION_TIME_INTERVAL}.
   */
  final int DEFAULT_QUERYCANCELLATION_INTERVAL = 100;

  final String DEFAULT_RECOVERY_DELAY_SYSPROP = GFXD_PREFIX
      + Attribute.DEFAULT_RECOVERY_DELAY_PROP;
  
  /**
   * A VM level property to specify the default startup recovery delay to be
   * used (
   * <code>PartitionAttributesFactory.setStartupRecoveryDelay(long)</code>. Can
   * be overridden for an individual table using the RECOVERYDELAY clause in
   * CREATE TABLE DDL. If not specified then the default recovery delay is
   * <code>PartitionAttributesFactory.RECOVERY_DELAY_DEFAULT</code>.
   */
  final String DEFAULT_STARTUP_RECOVERY_DELAY_PROP = "default-startup-recovery-delay";
  final String DEFAULT_STARTUP_RECOVERY_DELAY_SYSPROP = GFXD_PREFIX
      + DEFAULT_STARTUP_RECOVERY_DELAY_PROP;

  /**
   * The default initial capacity used for underlying GFE regions on datastores.
   */
  final int DEFAULT_INITIAL_CAPACITY = 10000;

  final String DEFAULT_INITIAL_CAPACITY_SYSPROP = GFXD_PREFIX
      + Attribute.DEFAULT_INITIAL_CAPACITY_PROP;

  /**
   * When opening a <code>MemHeapScanController</code> indicate that it is being
   * done for a <code>GlobalHashIndex</code> as the base table scan for index.
   * This will also open the scan on all entries in the region including remote
   * entries.
   */
  final int SCAN_OPENMODE_FOR_GLOBALINDEX = 0x00100000;

  /**
   * When opening a <code>MemHeapScanController</code> indicate that a full scan
   * on the table is required including both primary and secondary entries.
   */
  final int SCAN_OPENMODE_FOR_FULLSCAN = 0x00200000;

  /**
   * When opening a <code>MemIndexScanController</code> indicate that a full
   * scan on the index is required for foreign key checking (in ALTER TABLE).
   * This will allow us to first open the scan over all entries and then lookup
   * one key at a time.
   */
  final int SCAN_OPENMODE_FOR_REFERENCED_PK = 0x00400000;

  /**
   * When opening a <code>ScanController</code> indicate that a
   * <code>LockMode.READ_ONLY</code> lock should be taken on the qualified rows.
   */
  final int SCAN_OPENMODE_FOR_READONLY_LOCK = 0x00800000;

  final String PLAN_SCHEMA = SchemaDescriptor.IBM_SYSTEM_STAT_SCHEMA_NAME;
  
  final int MAX_STATS_DESCRIPTION_LENGTH = 190; 

  /**
   * This is the database property name that holds the private key used to
   * encrypt user passwords that would otherwise be stored in plain text (e.g.
   * external DB password for DBSynchronizer or the P2P user/password used to
   * boot a peer).
   */
  final String PASSWORD_PRIVATE_KEY_DB_PROP_PREFIX = "_GFXD_INTERNAL_PVT_KEY";

  /**
   * default algorithm used for generating DS-wide private key for encrypting
   * sensitive external information like passwords in DBSynchronizer
   */
  final String PASSWORD_PRIVATE_KEY_ALGO_DEFAULT = "AES";

  /** default key size for {@link #PASSWORD_PRIVATE_KEY_ALGO_DEFAULT} */
  final int PASSWORD_PRIVATE_KEY_SIZE_DEFAULT = 128;

  final String SYS_TABLENAME_STRING = "SYSTABLES";

  /**
   * List of extra GemFireXD properties not prefixed with {@link #GFXD_PREFIX}.
   * Add to this list if such a new property is added to GemFireXD.
   */
  final Set<String> validExtraGFXDProperties = new HashSet<String>(
      Arrays.asList(new String[] {
          Attribute.DEFAULT_INITIAL_CAPACITY_PROP,
          Attribute.DEFAULT_RECOVERY_DELAY_PROP,
          DEFAULT_STARTUP_RECOVERY_DELAY_PROP,
          Attribute.DUMP_TIME_STATS_FREQ,
          Attribute.ENABLE_STATS,
          Attribute.ENABLE_TIMESTATS,
          Attribute.INIT_SCRIPTS,
          Attribute.SERVER_GROUPS,
          Attribute.GFXD_HOST_DATA,
          Attribute.GFXD_PERSIST_DD,
          Attribute.READ_TIMEOUT,
          Attribute.KEEPALIVE_IDLE,
          Attribute.KEEPALIVE_INTVL,
          Attribute.KEEPALIVE_CNT,
          Attribute.STAND_ALONE_LOCATOR,
          Attribute.SYS_PERSISTENT_DIR,
          Attribute.SYS_HDFS_ROOT_DIR,
          Attribute.TABLE_DEFAULT_PARTITIONED,
          com.pivotal.gemfirexd.internal.iapi.reference.Attribute.COLLATE,
          Attribute.INTERNAL_CONNECTION,
          Attribute.COLLATION,
          Attribute.CREATE_ATTR,
          Attribute.DISABLE_STREAMING,
          Attribute.DRDA_SECMEC,
          Attribute.DRDA_SECTKN_IN,
          Attribute.DRDA_SECTKN_OUT,
          Attribute.DRDAID_ATTR,
          Attribute.ENABLE_TX_WAIT_MODE,
          Attribute.TX_SYNC_COMMITS,
          Attribute.PASSWORD_ATTR,
          Attribute.SHUTDOWN_ATTR,
          Attribute.SKIP_LISTENERS,
          Attribute.SKIP_LOCKS,
          Attribute.DEFAULT_SCHEMA,
          Attribute.CLIENT_SECURITY_MECHANISM,
          com.pivotal.gemfirexd.internal.iapi.reference.Attribute.SOFT_UPGRADE_NO_FEATURE_CHECK,
          com.pivotal.gemfirexd.internal.iapi.reference.Attribute.TERRITORY,
          Attribute.USERNAME_ATTR,
          Attribute.USERNAME_ALT_ATTR,
          Attribute.AUTH_PROVIDER,
          Attribute.SERVER_AUTH_PROVIDER,
          Attribute.PERSIST_INDEXES,
          Attribute.DEFAULT_PERSISTENT,
          Property.HADOOP_IS_GFXD_LONER,
          Property.GFXD_HD_HOMEDIR,
          Property.GFXD_HD_NAMENODEURL,
          LauncherBase.CRITICAL_HEAP_PERCENTAGE,
          LauncherBase.EVICTION_HEAP_PERCENTAGE,
          LauncherBase.CRITICAL_OFF_HEAP_PERCENTAGE,
          LauncherBase.EVICTION_OFF_HEAP_PERCENTAGE,
          Attribute.THRIFT_USE_BINARY_PROTOCOL,
          Attribute.THRIFT_USE_FRAMED_TRANSPORT,
          Attribute.THRIFT_USE_SSL,
          Attribute.THRIFT_SSL_PROPERTIES,
          Attribute.PREFER_NETSERVER_IP_ADDRESS,
          Attribute.HOSTNAME_FOR_CLIENTS,
          Attribute.QUERY_HDFS,
          Attribute.NCJ_BATCH_SIZE,
          Attribute.NCJ_CACHE_SIZE,
          Attribute.ENABLE_BULK_FK_CHECKS,
          Attribute.SKIP_CONSTRAINT_CHECKS,
          Attribute.ROUTE_QUERY,
          Attribute.AUTHZ_FULL_ACCESS_USERS,
          Attribute.AUTHZ_READ_ONLY_ACCESS_USERS
        }));

  // --------------------- Flags for SanityManager debug trace below

  /**
   * Debug flag to turn on loggging using <code>SanityManager</code> of
   * lock/unlock for GFXD locks for specified <code>GemFireContainer</code>s or
   * <code>DataDictionary</code>, or using the special "*" token for all GFXD
   * locks ( {@link #TRACE_LOCK}. Use "TraceLock_{tablename}" to trace locks for
   * a specific table and {@link #TRACE_DDLOCK} for <code>DataDictionary</code>.
   */
  final String TRACE_LOCK_PREFIX = "TraceLock_";

  /**
   * Debug flag to turn on logging using <code>SanityManager</code> of
   * lock/unlock for all GFXD locks.
   */
  final String TRACE_LOCK = TRACE_LOCK_PREFIX + '*';

  /** Flag to turn on trace for <code>GfxdDataDictionary</code> lock/unlock. */
  final String TRACE_DDLOCK = TRACE_LOCK_PREFIX + "DD";

  /**
   * Flag to turn on trace for events in the <code>GfxdDDLRegionQueue</code>.
   */
  final String TRACE_DDLQUEUE = "TraceDDLQueue";

  /**
   * Flag to turn on trace for DDL replay events.
   */
  final String TRACE_DDLREPLAY = "TraceDDLReplay";

  /**
   * Flag to turn on trace for conflation events in the
   * <code>GfxdDDLRegionQueue</code> using the
   * <code>GfxdOpConflationHandler</code>.
   */
  final String TRACE_CONFLATION = "TraceConflation";

  /** Flag to trace distribution of queries to remote nodes from query node. */
  final String TRACE_QUERYDISTRIB = "QueryDistribution";

  /**
   * flag for some minimal tracing of every DDL/DML/Query strings+parameters
   * received for execution
   */
  final String TRACE_EXECUTION = "TraceExecution";

  /** Flag to enable detailed index tracing. */
  final String TRACE_INDEX = "TraceIndex";

  /** Trace Transaction operations. */
  final String TRACE_TRAN = "TraceTran";
  final String TRACE_TRAN_VERBOSE = "TraceTranVerbose";

  /** Flag to enable tracing each iteration of ResultSet. */
  final String TRACE_RSITER = "TraceRSIteration";

  /**
   * Flag to enable tracing each step while aggregating in a "GROUP BY" clause.
   */
  final String TRACE_GROUPBYITER = "TraceGroupByRSIteration";

  /** Flag to enable tracing aggregation operations. */
  final String TRACE_AGGREG = "TraceAggregation";

  /** Flag to enable tracing <code>QueryInfo</code>s for a "GROUP BY" clause. */
  final String TRACE_GROUPBYQI = "TraceGroupByQI";

  /** Flag to enable logging of container create/drop */
  final String TRACE_CONGLOM = "TraceConglom";

  /** Flag to enable logging of container update operations */
  final String TRACE_CONGLOM_UPDATE = "TraceConglomUpdate";

  /** Flag to enable logging of container read operations */
  final String TRACE_CONGLOM_READ = "TraceConglomRead";

  /** Flag to enable logging of activation creation. */
  final String TRACE_ACTIVATION = "TraceActivation";

  /** Flag to enable tracing authentication calls and processing. */
  final String TRACE_AUTHENTICATION = "TraceAuthentication";

  /** Flag to enable tracing <code>GfxdHeapThresholdListener</code>. */
  final String TRACE_HEAPTHRESH = "TraceHeapThreshold";

  /**
   * Flag to enable tracing memory estimation used by
   * <code>GfxdHeapThresholdListener</code>.
   */
  final String TRACE_ESTIMATION = "TraceMemoryEstimation";

  /**
   * Flag to enable tracing of DBSynchronizer distribution operations
   */
  final String TRACE_DB_SYNCHRONIZER = "TraceDBSynchronizer";

  /**
   * Flag to enable tracing of DBSynchronizer distribution HA operations
   */
  final String TRACE_DB_SYNCHRONIZER_HA = "TraceDBSynchronizerHA";
  
  /**
   * Flag to enable more detailed tracing of exceptions in function execution
   */
  final String TRACE_FUNCTION_EX = "TraceFunctionException";

  /**
   * Flag to enable tracing of data-aware procedure execution.
   */
  final String TRACE_PROCEDURE_EXEC = "TraceProcedureExecution";

  /**
   * Flag to enable tracing <code>DistributedMember<code>s when using the
   * SYS.MEMBERS virtual table.
   */
  final String TRACE_MEMBERS = "TraceMembers";

  /**
   * Flag to trace system procedure executions.
   */
  final String TRACE_SYS_PROCEDURES = "TraceSystemProcedures";
  
  /**
   * Flag to trace FabricServer boot sequence.
   */
  final String TRACE_FABRIC_SERVICE_BOOT = "TraceFabricServiceBoot";

  /**
   * Flag to gemfirexd.debug.true for tracing GenericStatement Constants
   * optimization.
   */
  final String TRACE_STATEMENT_MATCHING = "StatementMatching";

  /**
   * Flag to trace <code>ConnectionSignaller</code> operations.
   */
  final String TRACE_CONNECTION_SIGNALLER = "TraceConnectionSignaller";

  /**
   * Flag to trace trigger invocations and related operations.
   */
  final String TRACE_TRIGGER = "TraceTrigger";

  /**
   * Flag to trace preparations for byte level comparisons instead of creating
   * DVDs.
   */
  final String TRACE_BYTE_COMPARE_OPTIMIZATION = "TraceByteCompareOptimization";

  /**
   * Flag to track query plan generation activities.
   */
  final String TRACE_PLAN_GENERATION = "TracePlanGeneration";
  
  /**
   * Flag to track query plan generation activities.
   */
  final String TRACE_PLAN_ASSERTION = "TracePlanAssertion";
  
  /**
   * Flag to track query statistics collection activities.
   */
  final String TRACE_STATS_GENERATION = "TraceStatsGeneration";
  
  /**
   * Flag to track extraction of each column from byte[] or byte[][] in
   * RowFormatter. This is likely to create huge logs so use with care.
   */
  final String TRACE_ROW_FORMATTER = "TraceRowFormatter";

  /**
   * Flag to track setup and release of ContextManager.
   */
  final String TRACE_CONTEXT_MANAGER = "TraceCM";

  /**
   * Flag to track temporary file read/write during sort overflow.
   * 
   */
  final String TRACE_TEMP_FILE_IO = "TraceTempFileIO";
  
  /**
   * Flag to enable tracing of app jars install/remove/replace etc
   */
  final String TRACE_APP_JARS = "TraceJars";
  
  /**
   * Flag to enable tracing of app jars install/remove/replace etc
   */
  final String TRACE_OUTERJOIN_MERGING = "TraceOuterJoinMerge";

  final String TRACE_SORT_TUNING = "SortTuning";

  /**
   * Flag to emit details logging for the IMPORT_* procedures.
   */
  final String TRACE_IMPORT = "TraceImport";

  /** Flag to trace distribution of Non-Collocated Join queries 
   * to remote nodes from query node. 
   */
  final String TRACE_NON_COLLOCATED_JOIN = "TraceNCJ";
  
  /** Flag for Detailed trace of Non-Collocated Join queries 
   * while doing resultset iteration. 
   */
  final String TRACE_NCJ_ITER = "TraceNCJIter";
  
  /** Flag for Detailed trace of Non-Collocated Join queries 
   * for some medium level dumping. 
   * We can have another flag for heavy dumping 
   */
  final String TRACE_NCJ_DUMP = "TraceNCJDump";
  
  /** Flag to trace index persistence and recovery. 
   */
  final String TRACE_PERSIST_INDEX = "TracePersistIndex";
  
  /** Flag to trace index persistence and recovery. 
   */
  final String TRACE_PERSIST_INDEX_FINER = "TracePersistIndexFiner";
  
  /** Flag to trace index persistence and recovery. 
   */
  final String TRACE_PERSIST_INDEX_FINEST = "TracePersistIndexFinest";

  /**
   * For Thrift API logging.
   */
  final String TRACE_THRIFT_API = "TraceThriftAPI";

  // --------------------- End flags for SanityManager debug trace

  /**
   * System property to set {@link Attribute#DISABLE_STREAMING} for all
   * connections.
   */
  final String GFXD_DISABLE_STREAMING = GFXD_PREFIX
      + Attribute.DISABLE_STREAMING;

  final String GFXD_QUERY_HDFS = GFXD_PREFIX + Attribute.QUERY_HDFS;

  final String GFXD_ROUTE_QUERY = GFXD_PREFIX + Attribute.ROUTE_QUERY;

  final String INTERNAL_CONNECTION = GFXD_PREFIX + Attribute.INTERNAL_CONNECTION;
  /*
   * @see Attribute.NCJ_BATCH_SIZE
   */
  final String GFXD_NCJ_BATCH_SIZE = GFXD_PREFIX + Attribute.NCJ_BATCH_SIZE;
  
  /*
   * @see Attribute.NCJ_CACHE_SIZE
   */
  final String GFXD_NCJ_CACHE_SIZE = GFXD_PREFIX + Attribute.NCJ_CACHE_SIZE;

  /**
   * Property to enable bulk foreign keys checks for put all.
   */
  final String GFXD_ENABLE_BULK_FK_CHECKS = GFXD_PREFIX
      + Attribute.ENABLE_BULK_FK_CHECKS;

  /**
   * Property to enable metadata query prepare for every new connection(s).
   */
  final String GFXD_ENABLE_METADATA_PREPARE = GFXD_PREFIX
      + Attribute.ENABLE_METADATA_PREPARE;
  
  /**
   * System property equivalent of {@link Attribute#PREFER_NETSERVER_IP_ADDRESS}
   */
  final String GFXD_PREFER_NETSERVER_IP_ADDRESS = GFXD_PREFIX
      + Attribute.PREFER_NETSERVER_IP_ADDRESS;

  /**
   * System property to set {@link Attribute#ENABLE_TX_WAIT_MODE} for all
   * connections.
   */
  final String GFXD_ENABLE_TX_WAIT_MODE = GFXD_PREFIX
      + Attribute.ENABLE_TX_WAIT_MODE;

  /**
   * System property to set index persistence. By default for now it will be
   * true.
   */
  final String GFXD_PERSIST_INDEXES = GFXD_PREFIX + Attribute.PERSIST_INDEXES;

  final String GFXD_QUERY_TIMEOUT = GFXD_PREFIX + Attribute.QUERY_TIMEOUT;

  final String OPTIMIZE_NON_COLOCATED_JOIN = GFXD_PREFIX + "optimize-non-colocated-join";
  
  final String GFXD_DISABLE_GET_CONVERTIBLE = GFXD_PREFIX
      + Attribute.DISABLE_GET_CONVERTIBLE;

  final String GFXD_DISABLE_GETALL_LOCALINDEX = GFXD_PREFIX
      + Attribute.DISABLE_GETALL_LOCALINDEX;

  final String GFXD_ENABLE_GETALL_LOCALINDEX_EMBED_GFE = GFXD_PREFIX
      + Attribute.ENABLE_GETALL_LOCALINDEX_EMBED_GFE;

  /**
   * System property to certain store queries to spark for better performance
   */

  final String GFXD_ROUTE_SELECTED_STORE_QUERIES_TO_SPARK =
      GFXD_PREFIX + "enable-routing-arbiter";

//  public static final String GFXD_COST_OPTIMIZED_ROUTING_THRESHOLD =
//      GFXD_PREFIX +"cost-optimized-routing-threshold";

  // --------------------- Defaults for GFXD connection/transaction props

  /** Default for autocommit in GFXD is false. */
  final boolean GFXD_AUTOCOMMIT_DEFAULT =
      snappydataConstants.DEFAULT_AUTOCOMMIT;

  /** Default for {@link #GFXD_DISABLE_STREAMING} is false. */
  final boolean GFXD_STREAMING_DEFAULT = true;

  /** Default for {@link Attribute#ENABLE_TX_WAIT_MODE} is false. */
  final boolean GFXD_TX_WAIT_MODE_DEFAULT = false;

  /** Default for {@link Attribute#DISABLE_TX_BATCHING} is false. */
  final boolean GFXD_TX_BATCHING_DEFAULT = true;

  /** Default for {@link Attribute#TX_SYNC_COMMITS} is false. */
  final boolean GFXD_TX_SYNC_COMMITS_DEFAULT = false;

  // --------------------- End defaults for GFXD connection/transaction props
}
