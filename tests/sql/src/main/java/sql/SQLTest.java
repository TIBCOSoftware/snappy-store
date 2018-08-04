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
/**
 *
 */
package sql;

import hydra.ClientVmInfo;
import hydra.DerbyServerHelper;
import hydra.EnvHelper;
import hydra.GemFireDescription;
import hydra.GemFirePrms;
import hydra.HostDescription;
import hydra.HostHelper;
import hydra.HydraRuntimeException;
import hydra.HydraThreadLocal;
import hydra.HydraVector;
import hydra.Log;
import hydra.MasterController;
import hydra.Prms;
import hydra.ProcessMgr;
import hydra.RemoteTestModule;
import hydra.StopSchedulingTaskOnClientOrder;
import hydra.TestConfig;
import hydra.blackboard.AnyCyclicBarrier;
import hydra.blackboard.SharedCounters;
import hydra.blackboard.SharedMap;
import hydra.gemfirexd.FabricServerHelper;
import hydra.gemfirexd.FabricServerPrms;
import hydra.gemfirexd.GfxdConfigPrms;
import hydra.gemfirexd.GfxdHelper;
import hydra.gemfirexd.GfxdHelperPrms;
import hydra.HostHelper.OSType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import splitBrain.SplitBrainBB;
import splitBrain.SplitBrainPrms;
import sql.ddlStatements.DDLStmtIF;
import sql.ddlStatements.FunctionDDLStmt;
import sql.ddlStatements.IndexDDLStmt;
import sql.ddlStatements.Procedures;
import sql.dmlStatements.TradeCustomersDMLStmt;
import sql.dmlStatements.DMLStmtIF;
import sql.generic.SQLOldTest;
import sql.hdfs.HDFSSqlTest;
import sql.hdfs.HDFSTestPrms;
import sql.hdfs.TriggerQueryObserver;
import sql.mbeans.listener.CallBackListener;
import sql.rollingUpgrade.SQLRollingUpgradeBB;
import sql.rollingUpgrade.SQLRollingUpgradePrms;
import sql.snappy.SnappyTest;
import sql.sqlutil.DDLStmtsFactory;
import sql.sqlutil.DMLStmtsFactory;
import sql.sqlutil.JoinTableStmtsFactory;
import sql.sqlutil.PooledConnectionC3P0;
import sql.sqlutil.PooledConnectionDBCP;
import sql.sqlutil.PooledConnectionTomcat;
import sql.sqlutil.ResultSetHelper;
import sql.wan.SQLWanBB;
import sql.wan.SQLWanPrms;
import util.PRObserver;
import util.StopStartPrms;
import util.StopStartVMs;
import util.TestException;
import util.TestHelper;
import cacheperf.comparisons.gemfirexd.QueryUtil;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelGatewaySenderImpl;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderImpl;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;



/**
 * @author eshu
 *
 */
public class SQLTest {
  public static String dbSynchStore = "DBSynchStore";
  public static final Random random = new Random(SQLPrms.getRandSeed());
  protected static SQLTest sqlTest;
  protected static HDFSSqlTest hdfsSqlTest;
  protected static String insertCustomersSQL = "INSERT INTO trade.customers " +
                "(cid, cust_name, since, addr) VALUES (?,?,?,?)";
  protected static Connection discConn=null;
  //protected static boolean createDiscSchemas = true; //only one thread to perform ddl statement on derby disc
  //protected static boolean createDiscTables = true;
  public static boolean hasDerbyServer = false;
  public static boolean testUniqueKeys = TestConfig.tab().booleanAt(SQLPrms.testUniqueKeys, true);  //default to true, a record is manipulated by the thread creates it
  public static boolean randomData = false;
  public static boolean hasNetworth = false;
  public static AtomicBoolean fabricServerStarted = new AtomicBoolean(false);
  public static boolean isSerial = TestConfig.tab().booleanAt(hydra.Prms.serialExecution, false);
  public static boolean isSingleDMLThread = false;
  public static boolean usingTrigger = false; //default to false
  public static boolean queryAnyTime = true; //default to true
  protected static long killInterval = TestConfig.tab().longAt(SQLPrms.killInterval, 120000); //default to 2 minutes
  protected static DMLStmtsFactory dmlFactory = new DMLStmtsFactory();
  protected static DDLStmtsFactory ddlFactory = new DDLStmtsFactory();
  protected static JoinTableStmtsFactory joinFactory = new JoinTableStmtsFactory();
  //protected static boolean hasRedundancy = TestConfig.tab().booleanAt(SQLPrms.hasRedundancy, false);
  public static boolean createIndex = TestConfig.tab().booleanAt(SQLPrms.createIndex,false);
  public static boolean testPartitionBy = TestConfig.tab().booleanAt(SQLPrms.testPartitionBy,false);
  public static boolean testServerGroups = TestConfig.tab().booleanAt(SQLPrms.testServerGroups, false);
  public static boolean testServerGroupsInheritence = TestConfig.tab().booleanAt(SQLPrms.testServerGroupsInheritence,false);
  protected static int numOfReplay =0;
  public static boolean testUniqIndex = TestConfig.tab().booleanAt(SQLPrms.testUniqIndex,false);
  protected static int[] dmlTables = SQLPrms.getTables();
  protected static int[] ddls = SQLPrms.getDDLs();
  public static boolean populateThruLoader = TestConfig.tab().booleanAt(SQLPrms.populateThruLoader, false);
  public static boolean testSecurity = TestConfig.tab().booleanAt(Prms.testSecurity, false);
  protected static String[] tables = SQLPrms.getTableNames(); //which tables will be used
  protected static HashMap<String, List<String>> tableCols = new HashMap<String, List<String>>(); //get column names for each table
  protected static boolean tableColsSet = false;
  protected static String roles;
  protected static boolean testInitDDLReplay = TestConfig.tab().booleanAt(SQLPrms.testInitDDLReplay, false);
  public static boolean multiThreadsCreateTables = TestConfig.tab().booleanAt(SQLPrms.multiThreadsCreateTables, false);
  public static String sgDBSync = "sgDBSync";
  public static String asyncDBSyncId = "asyncDBSync";
  public static boolean hasAsyncDBSync = TestConfig.tab().booleanAt(SQLPrms.hasAsyncDBSync, false);
  public static boolean hasAsyncEventListener = TestConfig.tab().booleanAt(SQLPrms.hasAsyncEventListener, false);
  public static boolean hasHdfs = TestConfig.tab().booleanAt(SQLPrms.hasHDFS, false);
  public static boolean hasJSON = TestConfig.tab().booleanAt(SQLPrms.hasJSON, false);
  public static boolean updateWriteOnlyMr = TestConfig.tab().booleanAt(SQLPrms.updateWriteOnlyMr, false); 
  public static boolean hdfsMrJob = TestConfig.tab().booleanAt(SQLPrms.hdfsMrJob, false);
  public static boolean supportDuplicateTables = TestConfig.tab().booleanAt(SQLPrms.supportDuplicateTables, false);
  public static boolean rebalanceBuckets = TestConfig.tab().booleanAt(SQLPrms.rebalanceBuckets, false);
  public static boolean isHATest = TestConfig.tab().longAt(util.StopStartPrms.numVMsToStop, 0) > 0? true: false || rebalanceBuckets;
  public static boolean isEdge = false;
  public static int numOfStores = (int) TestConfig.tab().longAt(SQLPrms.numOfStores, 3);
  public static int numOfWorkers = (int) TestConfig.tab().longAt(SQLPrms.numOfWorkers, 6);
  public static boolean hasPersistentTables = TestConfig.tab().booleanAt(GfxdHelperPrms.persistTables, false);
  public static boolean isWanTest = TestConfig.tab().booleanAt(SQLWanPrms.isWanTest, false);
  public static boolean isSnappyTest = TestConfig.tab().booleanAt(SQLPrms.isSnappyTest, false);
  public static boolean isSnappyMode = false;
  protected static boolean useWriterForWriteThrough = TestConfig.tab().booleanAt(SQLPrms.useWriterForWriteThrough, false);
  protected static boolean testLoaderCreateRandomRow = TestConfig.tab().booleanAt(SQLPrms.testLoaderCreateRandomRow, false);  
  public static boolean hasTx = TestConfig.tab().booleanAt(SQLPrms.hasTx, false);
  public static boolean isOfflineTest = TestConfig.tab().booleanAt(SQLPrms.isOfflineTest, false);
  public static String tradeSchemaSG = "tradeSchemaSG";
  public static boolean testEviction = TestConfig.tab().booleanAt(SQLPrms.testEviction, false);
  //use eviction heap percentage
  public static boolean useHeapPercentage = TestConfig.tab().booleanAt(SQLPrms.useHeapPercentage, false); //default to false
  //set ciritical heap or not
  public static boolean setCriticalHeap = TestConfig.tab().booleanAt(SQLPrms.setCriticalHeap, false); //default to false
  public static HydraThreadLocal getCanceled = new HydraThreadLocal();
  public static HydraThreadLocal getNodeFailure = new HydraThreadLocal();
  public static HydraThreadLocal getEvictionConflict = new HydraThreadLocal();
  public static boolean useMultipleAsyncEventListener = TestConfig.tab().
  booleanAt(SQLPrms.useMultipleAsyncEventListener, true);
  public static boolean withReplicatedTables = TestConfig.tab().booleanAt(SQLPrms.withReplicatedTables, false);
  protected static int maxResultWaitSec = TestConfig.tab().intAt(Prms.maxResultWaitSec, 300);
  public static boolean addListenerUsingAlterTable = TestConfig.tab().
  booleanAt(SQLPrms.addListenerUsingAlterTable, false);
  protected static boolean cycleVms = TestConfig.tab().booleanAt(SQLPrms.
      cycleVms, true); //to decide whether bring down nodes in HA test runs -- could be used for testing redundant copies
  public static boolean generateIdAlways = TestConfig.tab().booleanAt(SQLPrms.generateIdAlways, false);
  public static boolean generateDefaultId = TestConfig.tab().booleanAt(SQLPrms.generateDefaultId, false);
  public static boolean createTableWithGeneratedId = TestConfig.tab().booleanAt(SQLPrms.createTableWithGeneratedId, false);
  public static boolean dropProc = TestConfig.tab().booleanAt(SQLPrms.dropProc, true);  
  public static boolean dropFunc = TestConfig.tab().booleanAt(SQLPrms.dropFunc, false);  
  public static int ddlThread = -1;
  public static final String CUSTNUMOFPRS = "custNumOfPRs";
  public static final String NETWORTHNUMOFPRS = "networthNumOfPRs";
  public static final String SECNUMOFPRS = "secNumOfPRs";
  public static final String PORTNUMOFPRS = "portNumOfPRs";
  public static final String SONUMOFPRS = "soNumOfPRs";
  public static final String BONUMOFPRS = "boNumOfPRs";
  public static final String TXHISTORYNUMOFPRS = "txhistoryNumOfPRs";
  public static boolean ticket45938fixed = TestConfig.tab().booleanAt(SQLPrms.ticket45938fixed, true);
  public static boolean disableUpdateStatement48248 = TestConfig.tab().booleanAt(SQLPrms.disableUpdateStatement48248, false);
  public static boolean testMultipleUniqueIndex = TestConfig.tab().booleanAt(SQLPrms.testMultipleUniqueIndex, false); 
  public static HydraThreadLocal derbyConnection = new HydraThreadLocal();
  public static HydraThreadLocal resetDerbyConnection = new HydraThreadLocal(); //whether needs to reset the derby connection
  public static boolean alterTableDropColumn = TestConfig.tab().booleanAt(SQLPrms.alterTableDropColumn, false);
  public static boolean hasCompanies = TestConfig.tab().booleanAt(SQLPrms.hasCompanies, false);
  public static boolean ticket46803fixed = TestConfig.tab().booleanAt(SQLPrms.ticket46803fixed, true);
  public static boolean reproduceTicket46689 = false; //should remain unchanged after 46689 is addressed and fixed
  public static HydraThreadLocal alterTableException = new HydraThreadLocal();
  public static boolean networkPartitionDetectionEnabled = false;
  public static Cache gemfireCache = null;
  public static String losingSideHost = null;
  public static boolean populateWithbatch =  TestConfig.tab().booleanAt(SQLPrms.populateWithbatch, false);
  //public static DistributedSystem oldSystem;
  public static FabricServer oldFabricServer;
  public static boolean isLocator = false;
  public static String companyTable = "create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange), constraint comp_fk foreign key (symbol, exchange) references trade.securities (symbol, exchange) on delete restrict)" ;
  public static String hasRedundancy = "hasRedundancy"; 
  public static String cycleVMTarget = TestConfig.tab().stringAt(SQLPrms.cycleVMTarget, "store");
  public static int waitTimeBeforeNextCycleVM = TestConfig.tab().intAt(SQLPrms.waitTimeBeforeNextCycleVM, 20); //secs
  public static long lastCycledTime = 0;
  public static final String LASTCYCLEDTIME = "lastCycledTime"; //used in SQLBB
  public static boolean testWanUniqueness = TestConfig.tab().booleanAt(SQLWanPrms.testWanUniqueKeys, false); 
  public static boolean setIdentityColumn = TestConfig.tab().booleanAt(SQLPrms.setIdentityColumn, false);
  static boolean useDefaultDiskStoreForOverflow = TestConfig.tab().booleanAt(SQLPrms.useDefaultDiskStoreForOverflow, false);
  public static double percentage = TestConfig.tab().doubleAt(SQLPrms.evictionHeapPercentage, 0);
  public static boolean ticket49794fixed = TestConfig.tab().booleanAt(SQLPrms.ticket49794fixed, true);
  public static boolean useC3P0 = TestConfig.tab().booleanAt(SQLPrms.useC3P0ConnectionPool, false);
  public static boolean useDBCP = TestConfig.tab().booleanAt(SQLPrms.useDBCPConnectionPool, false);
  public static boolean useTomcatConnPool = TestConfig.tab().booleanAt(SQLPrms.useTomcatConnectionPool, false);
  protected static boolean useGfxdConfig = TestConfig.tab().booleanAt(SQLPrms.
      useGfxdConfig, false);
  public static final String PORTFOLIOV1READY = "portfoliov1IsReady";
  public static volatile boolean portfoliov1IsReady = false;
  public static boolean hasPortfolioV1 = TestConfig.tab().booleanAt(SQLPrms.hasPortfolioV1, false);
  public static boolean hasCustomersDup = TestConfig.tab().booleanAt(SQLPrms.hasCustomersDup, false);
  private static String portfoliov1 = "portfoliov1";
  public static boolean failAtUpdateCount = TestConfig.tab().booleanAt(SQLPrms.failAtUpdateCountDiff, false);
  protected static boolean workAround49565 = TestConfig.tab().booleanAt(SQLPrms.workAround49565, false);
  public static final String SOCKETLEASETIME = "socket-lease-time";
  public static final String HOSTDATA = "host-data";
  public static final String PERSISTDD = "persist-dd";
  public static final String SERVERGROUP = "server-groups";
  public static final String XCL54 = "XCL54"; //low memory exception
  public static final String X0Z01 = "X0Z01"; //node failure exception during select query
  private final static String FS = File.separator;
  public static boolean firstEntry = true;
  private static String diskCompatibilityDiskStorePath = TestConfig.tab().pathAt(SQLPrms.sqlfireDiskStorePath, null);
  public static final boolean useRandomConfHdfsStore = HDFSTestPrms.useRandomConfig();
  public static String createTablePortfolioStmt;
  public static String createTableCustomersStmt;
  public static boolean useNewTables = TestConfig.tab().booleanAt(SQLPrms.useNewTables, false);
  public static String[] cachedGfeDDLs;
  public static boolean waitForLocatorUpgrade = false;
  public static boolean locatorUpgradeCompleted = false;
  public static boolean useGenericSQL = TestConfig.tab().booleanAt(SQLPrms.useGenericSQLModel, false);
  public static SQLOldTest sqlGen = null;
  public static final int THOUSAND = 1000;
  public static final String REBALANCE = "rebalance"; 
  public static final String NEEDMOREDATANODES = "needMoreDataNodes";
  public static final String LRUMEMSIZE = "LRUMEMSIZE";
  public static boolean setTx = TestConfig.tab().booleanAt(SQLPrms.setTx, false);
  //for existing tests, both default product isolation read committed and isolation none will be tested for now
  //when setTx is set to true, connection acquired are using product default
  //when setTx is set to false, the connections in the tests is specifically set to isolation none
  //to run the original tests with new default, sql.SQLPrms-setTx = false needs to be included in the local.conf
  public static boolean verifyUsingOrderBy = TestConfig.tab().booleanAt(SQLPrms.verifyUsingOrderBy, false);
  public static double initEvictionHeap = TestConfig.tab().doubleAt(SQLPrms.initEvictionHeapPercent, 60);
  public static boolean workaround51582 = TestConfig.tab().booleanAt(SQLPrms.workaround51582, true);
  public static boolean accessorUpgradeCompleted = false;
  public static boolean storeUpgradeCompleted = false;
  public static boolean verifyByTid = TestConfig.tab().booleanAt(SQLPrms.verifyByTid, false);
  private static final String restartedStoreVmIDsKey = "restartedStoreVmIDs";
  
  static {
    //TODO for cheetah GA, the decision is not to test concurrent update on the same row
    //the following needs to be removed 
    if (testUniqueKeys) testWanUniqueness = false;  
    //TODO to remove the above line
    
    if (testUniqueKeys && testWanUniqueness) {
      throw new TestException("test issue, should not set both testUniqueKeys and testWanUniqueness to true");
    }
    if (useGenericSQL){
      sqlGen = new SQLOldTest();
    }
  }
  public static String oraclePassword = System.getProperty("user.name");
  public static String DUPLICATE_TABLE_SUFFIX = "_fulldataset";
  public static String MR_TABLE_SUFFIX = "_HDFS";
  public static boolean isOffheap = TestConfig.tab().booleanAt(SQLPrms.isOffheap, false); // whether offheap is enabled for tables
  public static boolean randomizeOffHeap = TestConfig.tab().booleanAt(SQLPrms.randomizeOffHeap, false);
  public static final String OFFHEAPCLAUSE = " OFFHEAP ";
  public static boolean allowConcDDLDMLOps = TestConfig.tab().booleanAt(SQLPrms.allowConcDDLDMLOps, false);
  public static boolean limitConcDDLOps = TestConfig.tab().booleanAt(SQLPrms.limitConcDDLOps, true);
  public static boolean forceCompaction = TestConfig.tab().booleanAt(SQLPrms.forceCompaction, false);
  public static boolean syncHAForOfflineTest = TestConfig.tab().booleanAt(SQLPrms.syncHAForOfflineTest, false);
  private static List <ClientVmInfo> vmListForOfflineTest;
  public static boolean dumpThreads = TestConfig.tab().booleanAt(SQLPrms.dumpThreads, false);
  public static int dumpThreadsInterval = (int) TestConfig.tab().longAt(SQLPrms.dumpThreadsInterval, 10000);
  public static int dumpThreadsTotalTime = (int) TestConfig.tab().longAt(SQLPrms.dumpThreadsTotalTime, 600); //maxResultWaitSec
  //public static boolean enableConcurrencyCheck = isHATest ? true : (random.nextBoolean() &&  testUniqueKeys? false: true);
  //enableConcurrecyChecks : needs to true in case of snapshotIsolation is enabled.
  //Was set to false earlier (Not covered in this 1.0 cheetah release, and removed from docs)
  public static boolean enableConcurrencyCheck = TestConfig.tab().booleanAt(SQLPrms
      .isSnapshotEnabled,true);
  public static String ENABLECONCURRENCYCHECKS = " ENABLE CONCURRENCY CHECKS ";
  private static boolean isTicket51584Fixed = false;
  public static boolean reproduceTicket51628 = false;
  
  private static List<CallBackListener> listeners = new ArrayList<CallBackListener>();
  /**
   * Creates a gemfirexd locator.
   */
  public static void HydraTask_createGfxdLocatorTask() {
    isLocator = true;
    FabricServerHelper.createLocator();
  }
  
  /**
   * Connects a locator gemfirexd.
   */
  public static void HydraTask_startGfxdLocatorTask() {
    String networkServerConfig = GfxdConfigPrms.getNetworkServerConfig();
    FabricServerHelper.startLocator(networkServerConfig);
  }

  public static void HydraTask_setTraceFlagsTask() {
    new SQLTest().setTraceFlags(true);
  }
  
  public static void HydraTask_unsetTraceFlagsTask() {
    new SQLTest().setTraceFlags(false);
  }
  
  /**
   * Stops a gfxd locator.
   */
  public static void HydraTask_stopGfxdLocatorTask() {
    FabricServerHelper.stopLocator();
  }
  
  public static void HydraTask_reStartGfxdTask() {
    if (isLocator) HydraTask_startGfxdLocatorTask();
    else {
      MasterController.sleepForMs(30000);
      sqlTest.startFabricServer();
    }
  }
   
  public static synchronized void HydraTask_initialize() {
  /* comment out due to recent changes in product that start GFE first
   * is not long supported.
   * see comments for ticket #42459
  if (sqlTest == null) {
    sqlTest = new SQLTest();
    PRObserver.installObserverHook();
    PRObserver.initialize(RemoteTestModule.getMyVmid());
      // create cache
      String cacheConfig = ConfigPrms.getBridgeConfig();
      if (cacheConfig == null)   cacheConfig = "defaultCache";
      CacheHelper.createCache(cacheConfig);

      sqlTest.initialize();
    }
    */
    HydraTask_initializeGFXD();
  }
  
  public static synchronized void HydraTask_initializeForSnappy(){
    if (sqlTest == null) {
      sqlTest = new SQLTest();
    }
    isSnappyMode = SQLPrms.isSnappyMode();
    sqlTest.initialize();
  }

  public static synchronized void HydraTask_initializeGFXD() {
    if (sqlTest == null) {
      sqlTest = new SQLTest();
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());

      String gemfireName = System.getProperty( GemFirePrms.GEMFIRE_NAME_PROPERTY );
      GemFireDescription gfd = TestConfig.getInstance().getGemFireDescription( gemfireName );
      roles = gfd.getRoles();
    
    }
    sqlTest.initialize();
  }
  
  public static synchronized void HydraTask_initializeFabricServer() {
    if (sqlTest == null) {
      sqlTest = new SQLTest();
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());    
    }
    sqlTest.initialize();
  }

  protected void initialize() {
    hasDerbyServer = TestConfig.tab().booleanAt(Prms.manageDerbyServer, false);
    testUniqueKeys = TestConfig.tab().booleanAt(SQLPrms.testUniqueKeys, true);
    randomData =  TestConfig.tab().booleanAt(SQLPrms.randomData, false);
    isSerial =  TestConfig.tab().booleanAt(Prms.serialExecution, false); 
    waitForLocatorUpgrade = TestConfig.tab().booleanAt(SQLRollingUpgradePrms.waitForLocatorUpgrade, false);
    if (isSerial){
      testUniqueKeys  = false;
      //for serical testing -- ignore the testUnqiueKeys setting in local.conf for cheetach GA
      //could be taken out once concurrent op/update on the same row is allowed again after GA 
      log().info("For serial testing, testUniquekeys is set to " + testUniqueKeys);    
    }
    isSingleDMLThread = TestConfig.tab().booleanAt(SQLPrms.isSingleDMLThread, false);
    usingTrigger = TestConfig.tab().booleanAt(SQLPrms.usingTrigger, false);
    queryAnyTime = TestConfig.tab().booleanAt(SQLPrms.queryAnyTime, true);
    hasNetworth =  TestConfig.tab().booleanAt(SQLPrms.hasNetworth, true);
    if (setCriticalHeap) {
      boolean[] opCanceled = new boolean[1];
      opCanceled[0] = false;
      getCanceled.set(opCanceled);
    }
    if(hasHdfs) hdfsSqlTest = new HDFSSqlTest();
  }
  
  protected String getOraclePassword() {
    int aVal = 'a';
    int length = 8;
    char[] charArray = new char[length];
    for (int j = 0; j<length; j++) {
      charArray[j] = (char) (random.nextInt(26) + aVal); //one of the char in a-z
    }
    String password = new String(charArray);
    log().info("oracle password for trade is " + password);
    return password;   
  }

  public static void HydraTask_initialize_replay() {
    // create cache 
    int sleepMS = 30000;
    if (numOfReplay == 0) {
      Log.getLogWriter().info("sleep for " + sleepMS/1000 + " sec and wait for other ddl message");
      MasterController.sleepForMs(sleepMS);

      //String cacheConfig = ConfigPrms.getBridgeConfig();
      //if (cacheConfig == null)   cacheConfig = "defaultCache";
      //CacheHelper.createCache(cacheConfig);
      
      if (sqlTest == null) {
        sqlTest = new SQLTest();
            //sqlTest.createGFEDB();
        PRObserver.installObserverHook();
        PRObserver.initialize(RemoteTestModule.getMyVmid());
        sqlTest.createGFXDDB();
      }
      numOfReplay++;
    } //only execute once to test ddl replay
    MasterController.sleepForMs(sleepMS);
  }

  public static void HydraTask_initialize_replayHA() throws Exception {
    Object initDelayed = SQLBB.getBB().getSharedMap().get("initDelayed_" + RemoteTestModule.getMyVmid());
    if (initDelayed == null) {
      SQLBB.getBB().getSharedMap().put("initDelayed_" + RemoteTestModule.getMyVmid(), "true");
      Log.getLogWriter().info("first time for init DDL replay, do nothing");
      return;
    }
    if (sqlTest == null) {
      sqlTest = new SQLTest();

      int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());
      sqlTest.createGFXDDB();
      List<ClientVmInfo>vms = new ArrayList<ClientVmInfo>();
      vms.add(new ClientVmInfo(RemoteTestModule.getMyVmid()));
      PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
      StopStartVMs.StopStart_initTask();
    }
    int sleepMS = 30000;
    Log.getLogWriter().info("sleep for " + sleepMS/1000 + " sec");
    MasterController.sleepForMs(sleepMS);

  }
  
  public static void HydraTask_increaseHeapNodeStart() {
    int sleepMS = 30000;
   
    if (numOfReplay == 0) {
      int addNode = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.addNewDataNodes);
      if (addNode == 1) {
        Boolean needToStartNow = (Boolean) SQLBB.getBB().getSharedMap().get(NEEDMOREDATANODES);
        if (needToStartNow != null && needToStartNow) {
          if (sqlTest == null) {
            sqlTest = new SQLTest();

            sqlTest.startFabricServerWithRebalance();
            numOfReplay++;

            PRObserver.installObserverHook();
            PRObserver.initialize(RemoteTestModule.getMyVmid());
   
            MasterController.sleepForMs(sleepMS);
            sqlTest.setNeedMoreDataNodes(false);
          }
        }

        SQLBB.getBB().getSharedCounters().zero(SQLBB.addNewDataNodes);
      }
    } // only execute once
    MasterController.sleepForMs(sleepMS);
  }
 
  public static void HydraTask_startFabricServerWithRebalance() {
    if (numOfReplay == 0) {
      if (sqlTest == null) {
            sqlTest = new SQLTest();
      }
      sqlTest.startFabricServerWithRebalance();
      numOfReplay++;

    }   
  }

  protected void startFabricServerWithRebalance() {
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();
    if (p != null
        && "false".equalsIgnoreCase(p.getProperty(Attribute.GFXD_HOST_DATA))
        && "true".equalsIgnoreCase(p.getProperty(Attribute.GFXD_PERSIST_DD))) {
      p.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    }

    if (p != null && isTicket51584Fixed) {
      Log.getLogWriter().info("setting " + REBALANCE + " to true");
      p.setProperty(REBALANCE, "true");
    }
    startFabricServer(p);

    if (!isTicket51584Fixed) callRebalanceBucket();
  }

  protected void setNeedMoreDataNodes(boolean moreNode) {
    getLock();
    SQLBB.getBB().getSharedMap().put(NEEDMOREDATANODES, moreNode);
    Log.getLogWriter().info(NEEDMOREDATANODES + " is set to " + moreNode);
    releaseLock();
  }

  protected void callRebalanceBucket() {
    try {
      Connection conn = getGFEConnection();
      String sql = "{call sys.rebalance_all_buckets()}";
      log().info("executing " + sql);
      CallableStatement cs = conn.prepareCall(sql);
      cs.execute();
      log().info("executed " + sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  //write to bb for data stores to randomly chose from
  public static void HydraTask_initForServerGroup() {
    sqlTest.initForServerGroup();
  }
  
  protected void initForServerGroup(){
    int numOfHosts = TestConfig.tab().intAt(SQLPrms.storeHosts, 6);
    //int numOfServerGroups = 4; //SG1, SG2, SG3, SG4
    String[] group1 = {"SG1","SG1,SG2","SG1,SG3" ,"SG1,SG4", "SG1,SG2,SG3","SG1,SG2,SG4","SG1,SG3,SG4","default"};
    String[] group2 = {"SG2","SG1,SG2","SG2,SG3" ,"SG2,SG4", "SG2,SG3,SG4","SG1,SG2,SG3","SG1,SG2,SG4","default"};
    String[] group3 = {"SG3","SG2,SG3","SG1,SG3" ,"SG3,SG4", "SG1,SG3,SG4","SG1,SG2,SG3","SG2,SG3,SG4","default"};
    String[] group4 = {"SG4","SG2,SG4","SG3,SG4" ,"SG1,SG4", "SG1,SG2,SG4","SG1,SG3,SG4","SG2,SG3,SG4","default"};
    ArrayList<String[]> groups = new ArrayList<String[]>();
    groups.add(0, group1);
    groups.add(1, group2);
    groups.add(2, group3);
    groups.add(3, group4);
    SQLBB.getBB().getSharedMap().put("serverGroup_tables", groups); //used in ddl table creation

    if (numOfHosts < groups.size()) {
      throw new TestException("Test issues, not enough data hosts to host the necessary server groups");
    }
    ArrayList<String> sgs = new ArrayList<String>(); //ArrayList to be write onto the bb
    for (int i=0; i<numOfHosts; i++) {
      if (i<groups.size()) {
        String[] group = groups.get(i);
        int whichOne = random.nextInt(group.length-1);  //data store in default SG only holds tables created to default
        sgs.add(i, group[whichOne]);
        Log.getLogWriter().info("group["+i+"] is " + group[whichOne]);
      } //at least 1 host will devote to one of the four sever groups
      else {
        String[] group = groups.get(random.nextInt(groups.size())); //random of the 4 groups
        sgs.add(i, group[random.nextInt(group.length)] );
      } //rest of the host could be in any groups 
    }

    SQLBB.getBB().getSharedMap().put("serverGroups", sgs); //used for servers
  }

  public static synchronized void HydraTask_createDiscDB() {
    if (sqlTest == null)  sqlTest = new SQLTest();
    
    sqlTest.createDiscDB();
  }

  protected void createDiscDB() {
    if (hasDerbyServer && discConn == null) {
      while (true) {
        try {
          discConn = getFirstDiscConnection();
          break;
        } catch (SQLException se) {
          Log.getLogWriter().info("Not able to connect to Derby server yet, Derby server may not be ready.");
          SQLHelper.printSQLException(se);
          int sleepMS = 10000;
          MasterController.sleepForMs(sleepMS); //sleep 10 sec to wait for Derby server to be ready.
        }
      }
    }
  }

  public static synchronized void HydraTask_createDiscSchemas() {
    sqlTest.createDiscSchemas();
  }

  public static void HydraTask_createDuplicateDiscSchemas() {
    sqlTest.createDuplicateDiscSchemas();
  }

  //use derby to create duplicate schemas.
  protected void createDuplicateDiscSchemas() {
    if (!hasDerbyServer) {
      return;
    }
    Connection conn = getDiscConnection();
    try {
      int isolation = conn.getTransactionIsolation();
      String isoLevel;
      switch (isolation) {
      case Connection.TRANSACTION_NONE:
        isoLevel = "TRANSACTION_NONE";
        break;
      case Connection.TRANSACTION_READ_COMMITTED:
        isoLevel = "TRANSACTION_READ_COMMITTED";
        break;
      case Connection.TRANSACTION_REPEATABLE_READ:
        isoLevel = "TRANSACTION_REPEATABLE_READ";
        break;
      case Connection.TRANSACTION_SERIALIZABLE:
        isoLevel = "TRANSACTION_SERIALIZABLE";
        break;
       default:
          isoLevel = "unknown";
      }

      Log.getLogWriter().info("Derby default cconnection isolation level is " + isoLevel);
    } catch (SQLException se) {

    }
    Log.getLogWriter().info("creating duplicate schemas on disc.");
    createDuplicateSchemas(conn);
    Log.getLogWriter().info("done creating duplicate schemas on disc with proper exception.");
    closeDiscConnection(conn);
  }
  
  

  protected void createDiscSchemas() {
    if (!hasDerbyServer) {
      return;
    }
    Connection conn = getDiscConnection("superUser");
    Log.getLogWriter().info("creating schemas on disc.");
    createSchemas(conn);
    Log.getLogWriter().info("done creating schemas on disc.");
    closeDiscConnection(conn);
  }

  public static void HydraTask_createGFESchemas() {
    if(useGenericSQL){
      sqlGen.createGFESchemas();
    }else{
      sqlTest.createGFESchemas();  
    }
  }

  public static void HydraTask_createDuplicateGFESchemas() {
    sqlTest.createDuplicateGFESchemas();
  }
  //use gfe conn to create schemas
  protected void createGFESchemas() {
    ddlThread = getMyTid();
    Connection conn = getGFEConnection();

    Log.getLogWriter().info("testServerGroupsInheritence is set to " + testServerGroupsInheritence);
    Log.getLogWriter().info("creating schemas in gfe.");
    if (!testServerGroupsInheritence) createSchemas(conn);
    else {
      String[] schemas = SQLPrms.getGFESchemas(); //with server group
      createSchemas(conn, schemas);
    }
    Log.getLogWriter().info("done creating schemas in gfe.");
    closeGFEConnection(conn);
  }

  //use gfe conn to create duplicate schemas
  protected void createDuplicateGFESchemas(){
    Connection conn = getGFEConnection();
    Log.getLogWriter().info("creating duplicate schemas in gfe.");
    createDuplicateSchemas(conn);
    Log.getLogWriter().info("done creating duplicate schemas in gfe with expected exception.");
  }

  /**
   * To create schemas from the conf file.
   * @param conn -- to determine create on disc or in gfe
   */
  protected void createDuplicateSchemas(Connection conn){
    String[] schemas = SQLPrms.getSchemas();
    boolean gotExpectedException = false;

    try {
      Statement s = conn.createStatement();
      for (int i =0; i<schemas.length; i++) {
        try {
          s.execute(schemas[i]);
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          if ("X0Y68".equals(se.getSQLState())) {
            Log.getLogWriter().info("Got expected schema existing SQLException. \n" );
            gotExpectedException = true;
          }
        }
      }
      s.close();
      commit(conn);
    } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("Not able to create schemas\n"
            + TestHelper.getStackTrace(se));
    }

    if (!gotExpectedException) {
      throw new TestException ("Did not get the expected SQLException as we created trade schema twice." +
                "\n" );
    }
    StringBuffer aStr = new StringBuffer("Created schemas \n");
    for (int i = 0; i < schemas.length; i++) {
      Object o = schemas[i];
      aStr.append(o.toString() + "\n");
    }
    Log.getLogWriter().info(aStr.toString());
  }

  /**
   * To create schemas from the conf file.
   * @param conn -- to determine create on disc or in gfe
   */
  protected void createSchemas(Connection conn) {
    String[] schemas = SQLPrms.getSchemas();
    createSchemas(conn, schemas);
  }

  /**
   * To create schemas based on schemas passed in
   * @param conn -- to determine create on disc or in gfe
   * @param schemas -- which schemas to be created
   */
  protected void createSchemas(Connection conn, String[] schemas){
    String ddlThread = "thr_" + getMyTid();
    try {
      Statement s = conn.createStatement();
      for (int i =0; i<schemas.length; i++) {
      if (testSecurity) {
        s.execute(schemas[i] + " AUTHORIZATION " + ddlThread); //owner of the schemas
      }
      else
          s.execute(schemas[i]);
      }
      s.close();
      commit(conn);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Y68")) {
        Log.getLogWriter().info("got schema existing exception if multiple threads" +
            " try to create schema, continuing tests");
      } else 
        SQLHelper.handleSQLException(se);
    }

    StringBuffer aStr = new StringBuffer("Created schemas \n");
    for (int i = 0; i < schemas.length; i++) {
      Object o = schemas[i];
      aStr.append(o.toString() + "\n");
    }
    Log.getLogWriter().info(aStr.toString());
  }

  //same DDL for create schema
  protected void createTradeSchema(Connection conn) {
    try {
      Statement s = conn.createStatement();
      s.execute("create schema trade" );
      s.close();
      commit(conn);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException ("Not able to create trade schema on disc "
          + TestHelper.getStackTrace(se));
    }
  }

  //create tables on both derby and gemfirexd
  public static synchronized void HydraTask_createTables(){
    sqlTest.createFuncMonth();
    sqlTest.createBothTables();
  }

  protected void createBothTables() {
    if (!hasDerbyServer) {
      Connection gConn = getGFEConnection();
      createTables(gConn);
      closeGFEConnection(gConn);
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();
    createTables(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  public static synchronized void HydraTask_createDiscTables(){
    sqlTest.createDiscTables();
  }

  protected void createDiscTables() {
    if (!hasDerbyServer) {
      return;
    }
    Connection conn = getDiscConnection();
    log().info("creating tables in derby db.");
    createTables(conn);
    log().info("done creating tables in derby db.");
    
    closeDiscConnection(conn);
  }

  public static void HydraTask_createGFETables(){
    if(!SQLPrms.isSnappyMode())
      sqlTest.createFuncMonth();  //partition by expression
    if(useGenericSQL) sqlGen.createGFETables();
    else sqlTest.createGFETables();
    if (supportDuplicateTables) {
      Boolean createOnlyTrigger = SQLBB.getBB().getSharedMap().get(SQLPrms.CREATE_ONLY_TRIGGERS) == null ? false : true;
      if (createOnlyTrigger) {
        sqlTest.createTriggers();
        SQLBB.getBB().getSharedMap().put(SQLPrms.CREATE_ONLY_TRIGGERS, false);
      }
      else {
        sqlTest.createDuplicateTables();
        sqlTest.createTriggers();
      }
    }
  }

  protected void createGFETables() {
    Connection conn = getGFEConnection();
    Log.getLogWriter().info("dropping tables in gfe.");
    dropTables(conn); //drop table before creating it -- impact for ddl replay
    executeListener("CLOSE", "PRINT_CONN");
    Log.getLogWriter().info("done dropping tables in gfe");
    Log.getLogWriter().info("creating tables in gfe.");
    createTables(conn);
    Log.getLogWriter().info("done creating tables in gfe.");
    closeGFEConnection(conn);
    
    if(hasHdfs){
      SharedCounters counter = SQLBB.getBB().getSharedCounters();
      counter.increment(SQLBB.createTableDone);  
    }
  }
  public static synchronized void HydraTask_dropAllTables() {
    
    if (SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_TRIGGER) != null && supportDuplicateTables){
      sqlTest.dropTriggers();
      sqlTest.truncateDuplicateTables(DUPLICATE_TABLE_SUFFIX);
      SQLBB.getBB().getSharedMap().put(SQLPrms.CREATE_ONLY_TRIGGERS , true) ;      
      }
    
     sqlTest.dropAllTables();
  }

  protected void dropAllTables() {
    Log.getLogWriter().info("dropping all tables in gfe.");
    Connection conn = getGFEConnection();
    String tableName = "";
    String[] createTablestmt = SQLPrms.getCreateTablesStatements(true);
    String dropStatement = "DROP  TABLE ";
    try {
      Statement s = conn.createStatement();

      for (int i = createTablestmt.length - 1; i >= 0; i--) {
        int tableNameStartIndex = createTablestmt[i].toUpperCase().indexOf(
            "TABLE");
        int tableNameEndIndex = createTablestmt[i].indexOf("(",
            tableNameStartIndex);
        tableName = createTablestmt[i].substring(tableNameStartIndex + 6,
            tableNameEndIndex).trim().toUpperCase();
        log().info("executing statement : " + dropStatement + tableName);
        s.execute(dropStatement + tableName);
        log().info("executing statement : " + dropStatement + tableName + " completed");
      }

      s.execute("drop function if exists month");

      s.close();
      closeGFEConnection(conn);

    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to drop table: " + tableName
          + TestHelper.getStackTrace(se));

    }

    Log.getLogWriter().info("done dropping all tables in gfe.");

  }
  
  public static synchronized void HydraTask_truncateDuplicateTables() {
    sqlTest.truncateDuplicateTables(DUPLICATE_TABLE_SUFFIX);
  }
  
  public static synchronized void HydraTask_truncateMrTables() {
    sqlTest.truncateDuplicateTables(MR_TABLE_SUFFIX);
  }
  
  protected void truncateDuplicateTables(String suffix){
    Log.getLogWriter().info("Truncating all tables from gfe.");
    Connection conn = getGFEConnection();
    String tableName = "";
    String[] createTablestmt = SQLPrms.getCreateTablesStatements(true);
    String truncateStatement = "TRUNCATE  TABLE ";
    try {
      Statement s = conn.createStatement();

      for (int i = 0; i < createTablestmt.length; i++) {
        int tableNameStartIndex = createTablestmt[i].toUpperCase().indexOf(
            "TABLE");
        int tableNameEndIndex = createTablestmt[i].indexOf("(",
            tableNameStartIndex);
        tableName = createTablestmt[i].substring(tableNameStartIndex + 6,
            tableNameEndIndex).trim().toUpperCase();
        tableName=tableName + suffix;
        log().info("executing statement : " + truncateStatement + tableName );        
        s.execute(truncateStatement + tableName);
        log().info("executing statement : " + truncateStatement + tableName + " completed");        
      }

      s.close();
      closeGFEConnection(conn);

    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to Truncate table: " + tableName
          + TestHelper.getStackTrace(se));

    }

    Log.getLogWriter().info("done truncating all tables from gfe.");    
  }
  
  protected void dropTables(Connection conn) {
    String sql = null;
    ArrayList <String>tables = new ArrayList<String>();
    tables.add("trade.txhistory");
    tables.add("trade.buyorders");
    tables.add("trade.sellorders");
    tables.add("trade.portfolio");
    tables.add("trade.networth");
    tables.add("trade.customers");
    tables.add("trade.securities");

    boolean testDropTableIfExists = SQLTest.random.nextBoolean();
    if (testDropTableIfExists)
      sql = "drop table if exists ";
    else
      sql = "drop table ";

    try {
      for (String table: tables) {
        Statement s = conn.createStatement();
        s.execute(sql + table);
      }
    } catch (SQLException se) {
      if ((se.getSQLState().equalsIgnoreCase("42Y55") || se.getSQLState().equalsIgnoreCase
          ("42000")) && !testDropTableIfExists) {
        Log.getLogWriter().info("Got expected table not exists exception, continuing tests");
      } else {
        SQLHelper.handleSQLException(se);
      }
    }
  }
  
  public static synchronized void HydraTask_dropMrTables() {
    sqlTest.dropMrTables();
  }
  
  protected void dropMrTables() {
    Connection conn = getGFEConnection();
    String sql = null;
    ArrayList <String>tables = new ArrayList<String>();
    tables.add("trade.txhistory_hdfs");
    tables.add("trade.buyorders_hdfs");
    tables.add("trade.sellorders_hdfs");
    tables.add("trade.portfolio_hdfs");
    tables.add("trade.networth_hdfs");
    tables.add("trade.customers_hdfs");
    tables.add("trade.securities_hdfs");
    tables.add("emp.employees_hdfs");
    tables.add("trade.trades_hdfs");
    
    sql = "drop table if exists ";
    try {
      for (String table: tables) {
        Statement s = conn.createStatement();
        s.execute(sql + table);
      }
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("42Y55") ) {
        Log.getLogWriter().info("Got expected table not exists exception, continuing tests");
      } else {
        SQLHelper.handleSQLException(se);
      }
    }
  }

  /**
   * To create tables based on the connection -- gfe tables need gfe DDL
   * @param conn --  to determine create on disc or in gfe
   */
  protected void createTables(Connection conn) {
    String driver;
    //gfe and derby use same drivers, it could be used when client server driver is used in gfe.
    String url;

    try {
      driver = conn.getMetaData().getDriverName();
      url = conn.getMetaData().getURL();
      Log.getLogWriter().info("Driver name is " + driver + " url is " + url);
    } catch (SQLException se) {
      throw new TestException("Not able to get driver name" + TestHelper.getStackTrace(se));
    }

    //to get creat table statements from config file
    String[] derbyTables = SQLPrms.getCreateTablesStatements(true);
    String[] tableNames = SQLPrms.getTableNames();
    String[] gfeDDL = null;

    try {
      Statement s = conn.createStatement();
      if (url.equals(DiscDBManager.getUrl()) || url.equals(ClientDiscDBManager.getUrl())) {
        for (int i =0; i<derbyTables.length; i++) {
          Log.getLogWriter().info("about to create table " + derbyTables[i]);
          s.execute(derbyTables[i]);
        }

      } else if (url.equals(GFEDBManager.getUrl())
          || url.startsWith(GFEDBClientManager.getProtocol())
          || url.startsWith(GFEDBClientManager.getSnappyThriftProtocol())
          || url.startsWith(GFEDBClientManager.getDRDAProtocol())) {

        if (hasHdfs) {
          SQLPrms.setHdfsDDL(SQLPrms.getCreateTablesStatements(false));
        } 
        if (!testPartitionBy) gfeDDL = SQLPrms.getGFEDDL(); //use config
        else gfeDDL=getGFEDDLPartition(); //get gfeDDL
        
        // create hdfs
        if (hasHdfs) { 
          Log.getLogWriter().info("creating hdfs extn...");
          gfeDDL = SQLPrms.getHdfsDDL(gfeDDL);
        }
        if(!isSnappyMode) {
          // enable offheap
          if (isOffheap && randomizeOffHeap) {
            throw new TestException("SqlPrms.isOffheap and SqlPrms.randomizeOffHeap are both set to true");
          }
          if (isOffheap) {
            Log.getLogWriter().info("enabling offheap.");
            for (int i = 0; i < gfeDDL.length; i++) {
              if (gfeDDL[i].toLowerCase().indexOf(SQLTest.OFFHEAPCLAUSE.toLowerCase()) < 0) { // don't add twice
                gfeDDL[i] += OFFHEAPCLAUSE;
              }
            }
          }
          if (randomizeOffHeap) {
            Log.getLogWriter().info("Randomizing off-heap in some tables but not others");
            for (int i = 0; i < gfeDDL.length; i++) {
              if (gfeDDL[i].toLowerCase().indexOf(SQLTest.OFFHEAPCLAUSE.toLowerCase()) < 0) { // don't add twice
                if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) {
                  gfeDDL[i] += OFFHEAPCLAUSE;
                }
              }
            }
          }

          if (enableConcurrencyCheck) {
            for (int i = 0; i < gfeDDL.length; i++) {
              gfeDDL[i] += ENABLECONCURRENCYCHECKS;
            }
          }
        }

        for (int i =0; i<gfeDDL.length; i++) {
          if (hasAsyncDBSync) {
            String ddl = gfeDDL[i].toUpperCase();
            if (ddl.contains("REPLICATE")) {
              if (ddl.contains("SERVER GROUPS") && !ddl.contains(sgDBSync)) {
                int index = ddl.indexOf("SERVER GROUPS");
                StringBuffer start = new StringBuffer((gfeDDL[i].substring(0, index-1)));
                StringBuffer sb = new StringBuffer(gfeDDL[i].substring(index));
                sb.insert(sb.indexOf("SG"), sgDBSync + ",");
                gfeDDL[i] = (start.append(sb)).toString();
              }

              if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
                  && !((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
                  equalsIgnoreCase("default")) {
                gfeDDL[i] += "SERVER GROUPS (" + sgDBSync + "," +
                  SQLBB.getBB().getSharedMap().get(tradeSchemaSG) + ")";
              }  else if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
                  && ((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
                  equalsIgnoreCase("default")) {
                gfeDDL[i] += "SERVER GROUPS (" + sgDBSync + ",SG1,SG2,SG3,SG4)";
              }//inherit from schema server group
            } //handle replicate tables for sg
            if(hasAsyncEventListener){
              if (!useMultipleAsyncEventListener) {
                if (!addListenerUsingAlterTable) {
                  String basicListener = "BasicAsyncListener";
                  gfeDDL[i] += "AsyncEventListener(" + basicListener + ")";
                } 
              } else {
              	if(i < tableNames.length){
              	  if (!tableNames[i].toLowerCase().contains("v1")) {
              	  	String[] str = tableNames[i].split("\\.");
              	  	String tableName = str[1];
              	  	String tableListener = tableName.substring(0, 1).toUpperCase() + tableName.substring(1) + "AsyncListener";
              	  	gfeDDL[i] += "AsyncEventListener(" + tableListener + ")";
              	  } //do not create portfoliov1 listener
              	} else if (gfeDDL[i].contains("default1")) {
              	  gfeDDL[i] += "AsyncEventListener(EmployeesAsyncListener)";
              	}
              }
            } else{
            	gfeDDL[i]+= "AsyncEventListener(" + asyncDBSyncId + ")" ;
            }
          }
          
          if (useNewTables) {
            cachedGfeDDLs = gfeDDL;
          }

          if (hasPortfolioV1 && gfeDDL[i].contains("create table trade.portfolio ")) {
            createTablePortfolioStmt = gfeDDL[i];
          } 
          if (hasCustomersDup && gfeDDL[i].contains("create table trade.customers ")) {
            createTableCustomersStmt = gfeDDL[i];
          }
          
          executeListener("CLOSE", "PRINT_CONN");
          Log.getLogWriter().info("About to create table " + gfeDDL[i]);
          try {
              s.execute(gfeDDL[i]);
          } catch (SQLException se) {
            if (multiThreadsCreateTables && se.getSQLState().equalsIgnoreCase("X0Y32")) 
              Log.getLogWriter().info("Got the expected exception, continuing tests");
            else if (se.getSQLState().equalsIgnoreCase("X0Y99")){
              SQLHelper.printSQLException(se); //for expiration
            	Log.getLogWriter().info("Got the expected exception X0Y99, continuing tests");
            	String sql = "drop table trade.securities";
            	s.execute(sql);
            	Log.getLogWriter().info(sql);
            	s.execute(derbyTables[0]); //using default partition
            	Log.getLogWriter().info(derbyTables[0]);
            	Log.getLogWriter().info("about to recreate table " + gfeDDL[i]);
            	s.execute(gfeDDL[i]);
            }
            else {
              SQLHelper.printSQLException(se);
              throw new TestException ("Not able to create tables\n"
                  + TestHelper.getStackTrace(se));
            }

          }
        }
      } else {
        throw new TestException("Got incorrect url or setting.");
      }
      executeListener("CLOSE", "PRINT_CONN");
      s.close();
      commit(conn);
      executeListener("CLOSE", "PRINT_CONN");
    } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("Not able to create tables\n"
            + TestHelper.getStackTrace(se));
    }

    StringBuffer aStr = new StringBuffer("Created tables \n");
    if (url.equals(DiscDBManager.getUrl())|| url.equals(ClientDiscDBManager.getUrl())){
      for (int i = 0; i < tables.length; i++) {
        aStr.append(tables[i] + "\n");
      }
    } else if (url.equals(GFEDBManager.getUrl())
        || url.startsWith(GFEDBClientManager.getProtocol())
        || url.startsWith(GFEDBClientManager.getSnappyThriftProtocol())
        || url.startsWith(GFEDBClientManager.getDRDAProtocol())) {
      for (int i = 0; i < gfeDDL.length; i++) {
        aStr.append(gfeDDL[i] + "\n");
      }
    }
    Log.getLogWriter().info(aStr.toString());
    
    //create index on derby table
    if (url.equals(DiscDBManager.getUrl()) || url.equals(ClientDiscDBManager.getUrl())) {
      createDerbyIndex(conn);
    }

  }

  protected void setNumOfPRs() {
    Properties p = FabricServerHelper.getBootProperties();
    if (p != null && "true".equalsIgnoreCase(p.getProperty(Attribute.GFXD_HOST_DATA))) {
      new Thread (new Runnable() {
        public void run() {
          SharedCounters counter = SQLBB.getBB().getSharedCounters();
          TestHelper.waitForCounter(SQLBB.getBB(), "SQLBB.createTableDone", 
              SQLBB.createTableDone, 1, false, -1, 2000);
          if (counter.incrementAndRead(SQLBB.calculatedNumOfPPs) == 1) {
            GemFireCacheImpl cache = (GemFireCacheImpl) Misc.getGemFireCache();
            Set<PartitionedRegion> prSet = cache.getPartitionedRegions();
            int numOfPRs = 0;
            if (prSet != null) {
              numOfPRs = prSet.size();            
              
              Set prNames = new HashSet<String>();
              for (PartitionedRegion pr: prSet) {
                if (pr.getRedundantCopies() == 0 ) {
                  --numOfPRs;
                  log().info("remove " + pr.getFullPath() + " from numOfPR calculation as there is no redundancy/recovery for this region");
                }else{
                  prNames.add(pr.getFullPath());
                }
              }
              
              counter.zero(SQLBB.numOfPRs);
              counter.add(SQLBB.numOfPRs, numOfPRs);
              log().info("setting numOfPRs in cluster to " + numOfPRs    + ", PRs = " + prNames);
            }
          }
        }
      }).start();
    } else {
      log().info("Not a datastore node");
    }    
  } 
  
  /**
   * To create tables based on the connection -- gfe tables need gfe DDL
   * @param dConn -- Connection to derby disc
   * @param gConn -- Connection to gemfirexd
   */
  protected void createTables(Connection dConn, Connection gConn) {
    //to get creat table statements from config file
    String[] derbyTables = SQLPrms.getCreateTablesStatements(true);
    String[] gfeDDL = SQLPrms.getGFEDDL();
    List<SQLException> exList = new ArrayList<SQLException>(); //excpetion list used to compare derby and gemfirexd

    try {
      Statement d = dConn.createStatement();
      Log.getLogWriter().info("Creating tables in derby");
      for (int i =0; i<derbyTables.length; i++) {
        try {
          d.execute(derbyTables[i]);
          Log.getLogWriter().info("Created table: " + derbyTables[i]);
        } catch (SQLException se) {
          SQLHelper.handleDerbySQLException(se, exList);
        }
      }
      d.close();
      commit(dConn);
    } catch (SQLException se) {
      SQLHelper.handleDerbySQLException(se, exList);
    }

    try {
      Statement g = gConn.createStatement();
      Log.getLogWriter().info("Creating tables in gemfirexd");
      for (int i =0; i<derbyTables.length; i++) {
        try {
          g.execute(gfeDDL[i]);
          Log.getLogWriter().info("Created table: " + gfeDDL[i]);
        } catch (SQLException se) {
          SQLHelper.handleGFGFXDException(se, exList);
        }
      }
      g.close();
      commit(gConn);
    } catch (SQLException se) {
      SQLHelper.handleGFGFXDException(se, exList);
    }

    SQLHelper.handleMissedSQLException(exList);

    Log.getLogWriter().info("successfully created tables on both derby and gemfirexd");
  }

  protected void createDiscTradeTables() {
    if (!hasDerbyServer) {
      return;
    }
    Connection conn = getDiscConnection();
    try {
      createDiscTradeCustomers(conn);

      commit(conn);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException ("Not able to create Trade tables on disc "
          + TestHelper.getStackTrace(se));
    }
  }

  protected void createDiscTradeCustomers(Connection conn) throws SQLException {
      Statement s = conn.createStatement();
      // We create a table...
      s.execute("create table trade.customers " +
                "(cid int, cust_name varchar(100), " +
                "since date, addr varchar(100))");
      s.close();
  }

  protected Connection getFirstDiscConnection() throws SQLException {
    Connection conn = null;
    if (testSecurity)
      conn = ClientDiscDBManager.getAuthConnection(getMyTid());  //user, password requried
    else
      conn = ClientDiscDBManager.getConnection();
    return conn;
  }

  protected Connection getDiscConnection() { 
    Connection conn = (Connection) derbyConnection.get();
    
    if (conn == null || (Boolean) resetDerbyConnection.get()) { 
      Log.getLogWriter().info("derbyConnection is not set yet");
      try {
        //to determine which derby connection
        if (testSecurity)
          conn = ClientDiscDBManager.getAuthConnection(getMyTid());  //user, password requried
        else
          conn = ClientDiscDBManager.getConnection();
      } catch (SQLException e) {
        SQLHelper.printSQLException(e);
        throw new TestException ("Not able to get Derby Connection:\n " + TestHelper.getStackTrace(e));
      }
      derbyConnection.set(conn);
      resetDerbyConnection.set(false);
    }
    return conn;
  }

  protected Connection getDiscConnection(String user) {
    Connection conn = null;
    try {
      //to determine which derby connection
      if (testSecurity)
        conn = ClientDiscDBManager.getAuthConnection(user);  //user, password requried
      else
        conn = ClientDiscDBManager.getConnection();
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get Derby Connection:\n " + TestHelper.getStackTrace(e));
    }
    return conn;
  }

  protected void closeDiscConnection(Connection conn) {
    closeDiscConnection(conn, false);
  }
  
  protected void closeDiscConnection(Connection conn, boolean end) {
    //close the connection at end of the test
    if (end) {
      try {
        conn.close();
        Log.getLogWriter().info("closing the connection");
      } catch (SQLException e) {
        SQLHelper.printSQLException(e);
        throw new TestException ("Not able to release the connection " + TestHelper.getStackTrace(e));
      }
    }
  }
  
  protected void createDerbyIndex(Connection dConn) {
    if (!hasDerbyServer) {
      log().info("does not have derby db in the test run, no derby index created");
      return;
    }

    String[] tableNames = SQLPrms.getTableNames();
    int i=0;
    for (String table: tableNames) {
      if (!table.equalsIgnoreCase("trade.companies") && !table.equalsIgnoreCase("trade.portfoliov1")) {
        String sql = "create index indextid" + i + " on " + table + " (tid)";
        try {
          dConn.createStatement().execute(sql);
          log().info(sql);
        } catch (SQLException se) {
          SQLHelper.handleSQLException(se);
        }        
        ++i;
      } 
    } 
    commit(dConn);
  }
  
  protected void createDerbyIndexOnCompanies(Connection dConn) {
    if (!hasDerbyServer) {
      log().info("does not have derby db in the test run, no derby index created");
      return;
    }

    String sql = "create index trade.indexcompaniestid on trade.companies (tid)";
    try {
      log().info(sql);
      dConn.createStatement().execute(sql);
      log().info("executed " + sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }        

    commit(dConn);
    
  }
  
  protected void listIndexCreated(Connection conn, String schema, String table) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    ResultSet rs = metaData.getIndexInfo(null, schema.toUpperCase(), table.toUpperCase(), false, true);

    Log.getLogWriter().info("From jdbc call, index names for " + schema + "." + table + 
        " in " + (SQLHelper.isDerbyConn(conn)? "derby: " : "gfxd: " ) + 
        ResultSetHelper.listToString(ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn))));
    rs.close();
    
    if (!SQLHelper.isDerbyConn(conn)) {
      //query indexes from sys table in gfxd
      rs = conn.createStatement().executeQuery("select * from sys.indexes where " +
      		"schemaname='" + schema.toUpperCase() + "' and tablename ='" + 
      		table.toUpperCase() + "'");
      
      Log.getLogWriter().info("From system table, index names for " + schema + "." + table + 
          " in " + (SQLHelper.isDerbyConn(conn)? "derby: " : "gfxd: " ) + 
          ResultSetHelper.listToString(ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn))));
      rs.close();
    }    
  }
  
  public synchronized static void HydraTask_startFabricServer() {
    sqlTest.startFabricServer();
  }
  
  public synchronized static void HydraTask_startFabricServer_Once() {
    if (fabricServerStarted.get() == false) {
      fabricServerStarted.set(true);
      sqlTest.startFabricServer();
    }
  }
  
  public void startFabricServer() {
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();
    if (p != null
        && "false".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_HOST_DATA))
        && "true".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_PERSIST_DD))) {
      p.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    }
    
    if (p != null && setTx) p.setProperty(Attribute.TX_SYNC_COMMITS, "true");
    //this is caused by hydra thread using a new physical thread for each task -- 
    //even for testUniqueKey cases, it is possible the new thread does not see the committed 
    //if this property is not set.
    
    startFabricServer(p);
    if(hasHdfs){
      setNumOfPRs();  
    }
  }
  
  public void startFabricServer(Properties p) {
    boolean started = false;
    while (!started) {
      try {
        FabricServerHelper.startFabricServer(p);
        started = true;
      } catch (HydraRuntimeException hre) {
        if (hre.getCause() != null && hre.getCause() instanceof SQLException &&
            ((SQLException) hre.getCause()).getSQLState().equalsIgnoreCase("XBM09") && isHATest) {
          int msToSleep = 20000;
          log().info("not able to restart the server due to XBM09, will try to restart after " +
          		+ msToSleep/1000 + " seconds");
          clearPRObserverCount();
          
          MasterController.sleepForMs(msToSleep);
        } else throw hre;
      }
    }
  }
  
  protected void startFabricServerSG() {
    String sg = getSGForNode();
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();
    if (p != null
        && "false".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_HOST_DATA))) {
      throw new TestException("test configure issue: " +
      		"fabric server sg must be a data node");
    }
    
    /* we do not need to set up the server group properties if
     * this server is in default/all server group
     */
    if (!sg.equals("default")) p.setProperty(SERVERGROUP, sg);
    
    startFabricServer(p);
  }
  
  protected void clearPRObserverCount() {
    PRObserver.installObserverHook();
    PRObserver.initialize(RemoteTestModule.getMyVmid());    
  }
  
  protected void startFabricServerSGDBSynchronizer() {
    setClientVmInfoForDBSynchronizerNode();
        
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();   
    if (p != null
        && "false".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_HOST_DATA))) {
      throw new TestException("test configure issue: " +
          "fabric server sg must be a data node");
    }
    p.setProperty(SERVERGROUP, sgDBSync);
    Log.getLogWriter().info("This server is in " + sgDBSync);
    
    startFabricServer(p);
  }
  
  public synchronized static void HydraTask_stopFabricServer() {
    sqlTest.stopFabricServer();
  } 
  
  protected void stopFabricServer() {
    FabricServerHelper.stopFabricServer();
  }
  
  //create gfxd database (through connection) thru existing ds
  public static synchronized void HydraTask_createGFEDB() {
    sqlTest.createGFEDB();
  }

  public static synchronized void HydraTask_createGFEDBForAccessors() {
    sqlTest.createGFEDBForAccessors();
  }

  //create gfxd database on data store through connection -- used to test server groups
  public static void HydraTask_createGFEDataStore() {
    sqlTest.createGFEDataStore();
  }

  //create gfxd database (through connection) without ds
  public static synchronized void HydraTask_createGFXDDB() {
    sqlTest.createGFXDDB();
  }

  public static synchronized void HydraTask_createGFXDDBForAccessors() {
    sqlTest.createGFXDDBForAccessors();
  }

  public static synchronized void HydraTask_createGFXDDBSG() {
    sqlTest.createGFXDDBSG();
  }
  
  public static synchronized void HydraTask_startFabricServerSG() {
    sqlTest.startFabricServerSG();
  }
  
  public static synchronized void HydraTask_createGFXDDBSGDBSynchronizer() {
    sqlTest.createGFXDDBSGDBSynchronizer();
  }
  
  public static synchronized void HydraTask_startFabricServerSGDBSynchronizer() {
    sqlTest.startFabricServerSGDBSynchronizer();
  }
  
  public static synchronized void HydraTask_createDBSynchronizer() {
    if (sqlTest == null)
      sqlTest = new SQLTest();
    sqlTest.createDBSynchronizer();
  }

  public static synchronized void HydraTask_createDiskStores() {
    sqlTest.createDiskStores();
  }

  protected void createGFXDDB() {
    /*
    if (random.nextBoolean())
      GfxdHelperPrms.tasktab().put(GfxdHelperPrms.persistDD, Boolean.TRUE);
    else
      GfxdHelperPrms.tasktab().put(GfxdHelperPrms.persistDD, Boolean.FALSE); 
    */
    Properties info = getGemFireProperties();
    info.setProperty(HOSTDATA, "true");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    if (setTx) info.setProperty(Attribute.TX_SYNC_COMMITS, "true");
    //this is caused by hydra thread using a new physical thread for each task -- 
    //even for testUniqueKey cases, it is possible the new thread does not see the committed 
    //if this property is not set.
    Log.getLogWriter().info("Connecting with properties: " + info);
        
    startGFXDDB(info);
         
  }
  
  protected void startGFXDDB(Properties info) {
    boolean started = false;
    Connection conn = null;
    while (!started) {
      try {
        conn = getGFEConnection(info);
        started = true;
      } catch (SQLException se) {
        if (se.getSQLState().equalsIgnoreCase("XBM09") && isHATest) {
          int msToSleep = 20000;
          log().info("not able to restart the server due to XBM09, will try to restart after " +
              + msToSleep/1000 + " seconds");
          clearPRObserverCount();          
          MasterController.sleepForMs(msToSleep);
        } else SQLHelper.handleSQLException(se);
      }
    }
    closeGFEConnection(conn);    
  }

  protected void createGFXDDBForAccessors() {
    Properties info = getGemFireProperties();
    info.setProperty(HOSTDATA, "false");
    info.setProperty(PERSISTDD, "false");  //due to #42855
    if (setTx) info.setProperty(Attribute.TX_SYNC_COMMITS, "true");
    
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    Log.getLogWriter().info("Connecting with properties: " + info);
    Connection conn = null;
    try {
      conn = getGFEConnection(info);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
      //accessors are not being cycled, it should not get XBM09 exception
    }
    closeGFEConnection(conn);

  }

  public static synchronized void HydraTask_reCreateGFXDDB() throws SQLException {
    sqlTest.reCreateGFXDDB();
  }

  public static synchronized void HydraTask_reCreateGFXDDBForAccessors() throws SQLException {
    sqlTest.reCreateGFXDDBForAccessors();
  }

  protected void reCreateGFXDDB() throws SQLException {
    Properties info = getGemFireProperties();
    info.setProperty(HOSTDATA, "true");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    Log.getLogWriter().info("Connecting with properties: " + info);
    Connection conn = GFEDBManager.getRestartConnection(info);
    closeGFEConnection(conn);
  }

  protected void reCreateGFXDDBForAccessors() throws SQLException {
    Properties info = getGemFireProperties();
    info.setProperty(HOSTDATA, "false");
    info.setProperty(PERSISTDD, "false");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    Log.getLogWriter().info("Connecting with properties: " + info);
    Connection conn = GFEDBManager.getRestartConnection(info);
    closeGFEConnection(conn);
  }

  protected void createGFXDDBSG() {
    Properties info = getGemFireProperties();

    String sg = getSGForNode(); //get the item

    /* we do not need to set up the server group properties if
     * this server is in default/all server group
     */
    if (!sg.equals("default")) info.setProperty(SERVERGROUP, sg);

    info.setProperty(HOSTDATA, "true");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    Log.getLogWriter().info("Connecting with properties: " + info);
    
    startGFXDDB(info);
  }
  
  
  @SuppressWarnings("unchecked")
  protected String getSGForNode() {
    HydraVector vec = TestConfig.tab().vecAt(SQLPrms.serverGroups);
    int whichOne = (int)SQLBB.getBB().getSharedCounters().incrementAndRead(
        SQLBB.dataStoreCount); //need to start from 0
    if (whichOne <= vec.size()) {
      whichOne--;
    } else {
      whichOne=vec.size()-1; //default to last element, if vms are more than configured
    }

    String sg = (String)((HydraVector)vec.elementAt(whichOne)).elementAt(0); //get the item

    if (sg.startsWith("random")) {
      ArrayList<String> serverGroups = (ArrayList<String>) SQLBB.getBB().getSharedMap()
        .get("serverGroups");
      if(whichOne>=serverGroups.size()) {
        whichOne = random.nextInt(serverGroups.size()); //randomly select one
      }
      sg = serverGroups.get(whichOne); //a random server group except for the first 4 data stores
    }
    Log.getLogWriter().info("This data store is in " + sg);
    
    return sg;
  }

  //only two vms should execute this method for testing DBSynchronizer is stopped
  protected void createGFXDDBSGDBSynchronizer() {
    Properties info = getGemFireProperties();
    //set ClientVmInfo for stopping
    setClientVmInfoForDBSynchronizerNode();

    Log.getLogWriter().info("This data store is in " + sgDBSync);
    info.setProperty(SERVERGROUP, sgDBSync);
    info.setProperty(HOSTDATA, "true");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    Log.getLogWriter().info("Connecting with properties: " + info);
    
    startGFXDDB(info);
  }
  
  protected void setClientVmInfoForDBSynchronizerNode() {
    ClientVmInfo target = new ClientVmInfo(RemoteTestModule.getMyVmid());
    int num = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.asynchDBTargetVm);
    if (num == 1) {
      SQLBB.getBB().getSharedMap().put("asyncDBTarget1", target);
      Log.getLogWriter().info("asyncDBTarget1: client vmID is " + target.getVmid());
    }
    else {
      ClientVmInfo target1 = (ClientVmInfo) SQLBB.getBB().getSharedMap().get("asyncDBTarget1");
      if (target1 != null && target1.getVmid() == RemoteTestModule.getMyVmid()) {
        //restarted vm will have the same logic vmId.
        SQLBB.getBB().getSharedMap().put("asyncDBTarget1", target);
        Log.getLogWriter().info("asyncDBTarget1: client vmID is " + target.getVmid());
      }
      else {
        SQLBB.getBB().getSharedMap().put("asyncDBTarget2", target);
        Log.getLogWriter().info("asyncDBTarget2: client vmID is " + target.getVmid());
      }
    }
  }

  protected void createDiskStores() {
    Connection conn = getGFEConnection();
    createDiskStores(conn);
    commit(conn);
    closeGFEConnection(conn);
  }
  
  protected void createDiskStores(Connection conn) {
    try {
    Statement stmt = conn.createStatement();
    String [] diskStores = SQLPrms.getDiskStoreDDL();

    if (diskStores == null) {
      Log.getLogWriter().info("No disk store setting in this test");
      return;
    }
    String maxlogsize = " maxlogsize 2"; //work around run out of disk space on Windows run
    for (int i =0; i<diskStores.length; i++) {
      Log.getLogWriter().info("about to create diskstore " + diskStores[i] + maxlogsize);
      stmt.execute(diskStores[i] + maxlogsize);
    }
    }catch(SQLException sqle) {
      SQLHelper.handleSQLException(sqle);
    }
  }

  protected void createDBSynchronizer() {
    Connection conn = getGFEConnection();
    createNewDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }
  
  protected void createNewDBSynchronizer(Connection conn) {
    String INIT_PARAM_STR = "org.apache.derby.jdbc.ClientDriver," +
    "jdbc:derby://" + DerbyServerHelper.getEndpoint().getHost() +
    ":" + DerbyServerHelper.getEndpoint().getPort() + "/" + ClientDiscDBManager.getDBName() +
    ";create=true";
    
    boolean enablePersistence = TestConfig.tab().booleanAt(SQLPrms.enableQueuePersistence, false);
    
    createNewDBSynchronizer(conn, INIT_PARAM_STR, enablePersistence);
  }

  protected void createNewDBSynchronizer(Connection conn, String INIT_PARAM_STR, boolean enablePersistence) {
    //TODO test the corner cases for available setting
    String className = "com.pivotal.gemfirexd.callbacks.DBSynchronizer";
    int batchSize = random.nextInt(1000) + 100;   
    int batchTimeInterval = random.nextInt(1000) + 1000;
    boolean enableBatchConflation = TestConfig.tab().booleanAt(SQLPrms.enableQueueConflation, false);
    boolean isParallelDBSynchronizer = TestConfig.tab().booleanAt(SQLPrms.isParallelDBSynchronizer, false);
    int maxQueueMem = random.nextInt(100) + 100;    
    int alert_Threshold = random.nextInt(1000) + 1000;
    boolean manualStart = random.nextBoolean();
    boolean diskSynchronous = random.nextBoolean();
    
    StringBuilder str = new StringBuilder();
    str.append("CREATE asyncEventListener ");
    str.append(asyncDBSyncId);  //id name
    str.append(" ( listenerclass '");
    str.append(className);
    /*
    if (random.nextBoolean()) {
      str.append("' driverclass '" + driverClass);
      str.append("' dburl '" + dbUrl + "'");
    } else {
      str.append("' initparams '" + INIT_PARAM_STR + "'");
    }
    */
    str.append("' initparams '" + INIT_PARAM_STR + "'");
    
    
    if (random.nextBoolean()) {
      if (manualStart) {
        str.append(" MANUALSTART " + manualStart);      
        Log.getLogWriter().info("DBSynchronizer MANUALSTART is " + manualStart);
      } else {
        Log.getLogWriter().info("DBSynchronizer MANUALSTART uses default");
      }
      
      if (random.nextBoolean()) {
        str.append(" ENABLEBATCHCONFLATION " + enableBatchConflation);
        Log.getLogWriter().info("DBSynchronizer ENABLEBATCHCONFLATION is " 
            + enableBatchConflation);
      } else {
        Log.getLogWriter().info("DBSynchronizer ENABLEBATCHCONFLATION uses default");
      }
      if (random.nextBoolean()) {
        str.append(" BATCHSIZE " + batchSize);
        Log.getLogWriter().info("DBSynchronizer BATCHSIZE is " 
            + batchSize);
      } else {
        Log.getLogWriter().info("DBSynchronizer BATCHSIZE uses default");
      }
      if (random.nextBoolean()) {
        str.append(" BATCHTIMEINTERVAL " + batchTimeInterval);
        Log.getLogWriter().info("DBSynchronizer BATCHTIMEINTERVAL is " 
            + batchTimeInterval);
      } else {
        Log.getLogWriter().info("DBSynchronizer BATCHTIMEINTERVAL uses default");
      }
      if (random.nextBoolean()) {
        str.append(" ENABLEPERSISTENCE " + enablePersistence);
        Log.getLogWriter().info("DBSynchronizer ENABLEPERSISTENCE is " 
            + enablePersistence);
        if (enablePersistence) {
          if (random.nextBoolean()) {
            str.append(" DISKSYNCHRONOUS " + diskSynchronous);
            Log.getLogWriter().info("DBSynchronizer DISKSYNCHRONOUS is " 
                + diskSynchronous);
          } else {
            Log.getLogWriter().info("DBSynchronizer DISKSYNCHRONOUS uses default");
          }
        }
      } else {
        Log.getLogWriter().info("DBSynchronizer ENABLEPERSISTENCE uses default");
      }
      if (random.nextBoolean()) {
        str.append(" DISKSTORENAME " + dbSynchStore);
        Log.getLogWriter().info("DBSynchronizer DISKSTORENAME is " 
            + dbSynchStore);
      } else {
        Log.getLogWriter().info("DBSynchronizer DISKSTORENAME  uses default");
      }
      if (random.nextBoolean()) {
        str.append(" MAXQUEUEMEMORY " + maxQueueMem);
        Log.getLogWriter().info("DBSynchronizer MAXQUEUEMEMORY is " 
            + maxQueueMem);
      } else {
        Log.getLogWriter().info("DBSynchronizer MAXQUEUEMEMORY uses default");
      }
      if (random.nextBoolean()) {
        str.append(" ALERTTHRESHOLD " + alert_Threshold );
        Log.getLogWriter().info("DBSynchronizer ALERTTHRESHOLD is " 
            + alert_Threshold);
      } else {
        Log.getLogWriter().info("DBSynchronizer ALERTTHRESHOLD uses default");
      }
    } 
    
    if (isParallelDBSynchronizer) {
      str.append(" ISPARALLEL " + isParallelDBSynchronizer);
    }
    
    str.append( " ) ");
    Log.getLogWriter().info("DBSynchronizer uses default settings");
    
    str.append("SERVER GROUPS ( " + sgDBSync + " )");

    try {
      Statement s = conn.createStatement();
      s.execute(str.toString());
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    Log.getLogWriter().info(str.toString());
  }

  public static void HydraTask_startDBSynchronizer() {
    sqlTest.startDBSynchronizer();
  }

  protected void startDBSynchronizer(){
    boolean useNewApi = true;
    Connection conn = getGFEConnection();
    if (useNewApi) startNewDBSynchronizer(conn);
    else startDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }


  protected void startDBSynchronizer(Connection conn) {
    try {
      String startDBSynchronizer = "call SYS.START_ASYNC_EVENT_LISTENER( ? )";
      CallableStatement cs = conn.prepareCall(startDBSynchronizer);      
      cs.setString(1, asyncDBSyncId);
      cs.execute();
      Log.getLogWriter().info(startDBSynchronizer + " for " + asyncDBSyncId);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void startNewDBSynchronizer(Connection conn) {
    try {
      String startDBSynchronizer = "call SYS.START_ASYNC_EVENT_LISTENER( ? )";
      CallableStatement cs = conn.prepareCall(startDBSynchronizer);      
      cs.setString(1, asyncDBSyncId.toUpperCase());
      cs.execute();
      Log.getLogWriter().info(startDBSynchronizer + " for " + asyncDBSyncId.toUpperCase());
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_populateTablesDBSynchronizer() {
    sqlTest.populateTablesDBSynchronizer();
  }

  protected void populateTablesDBSynchronizer() {
    Connection gConn = getGFEConnection();
    populateTables(null, gConn); //pass dConn as null for DBSynchronizer
    closeGFEConnection(gConn);
  }

  public static void HydraTask_doDMLOpDBSynchronizer() {
    sqlTest.doDMLOpDBSynchronizer();    
  }

  protected void doDMLOpDBSynchronizer() {
    Connection gConn = getGFEConnection();
    
    if (setCriticalHeap) resetCanceledFlag();
    if (setTx && isHATest) resetNodeFailureFlag();
    if (setTx && testEviction) resetEvictionConflictFlag();
    
    doDMLOp(null, gConn); //do not operation on derby directly
    
    commit(gConn);
  }
  
  public static void HydraTask_checkGatewayQueueEmpty() {
    sqlTest.checkGatewayQueueEmpty();
  }
  
  protected void checkGatewayQueueEmpty() {
    int sleepTime = 10000;
    GemFireCacheImpl cache = (GemFireCacheImpl)Misc.getGemFireCache();
    Set<GatewaySender> senders = cache.getGatewaySenders();
    for (GatewaySender sender: senders) {      
      while (((AbstractGatewaySender) sender).getQueue().size() != 0)  {         
        Log.getLogWriter().info("region queue for sender " + sender.getId() + " is not drained yet," +
        		" wait for " + sleepTime/1000 + " sec");
        try {
          Thread.sleep(sleepTime);
        } catch (InterruptedException ie) {
          //ignored
        }
      }
    }
  }
  
  public static void HydraTask_checkAsyncEventQueueEmpty() {
    sqlTest.checkAsyncEventQueueEmpty();
  }
  
  protected void checkAsyncEventQueueEmpty() {
    int sleepTime = 10000;
    GemFireCacheImpl cache = (GemFireCacheImpl)Misc.getGemFireCache();
    Set<AsyncEventQueue> queues = cache.getAsyncEventQueues();
    for (AsyncEventQueue queue: queues) {
      AsyncEventQueueImpl queueImpl = (AsyncEventQueueImpl)queue;
      SerialGatewaySenderImpl sender = (SerialGatewaySenderImpl)queueImpl.getSender();
      String regionQueueName = sender.getQueue().getRegion().getName();
      while (sender.getQueue().size() != 0)  {         
        Log.getLogWriter().info("region queue for " + regionQueueName + " is not drained yet," +
                        " wait for " + sleepTime/1000 + " sec");
        try {
          Thread.sleep(sleepTime);
        } catch (InterruptedException ie) {
          //ignored
        }
      }
    }
  }
  
  public static void HydraTask_checkAsyncEventQueueEmptyForParallel() {
    sqlTest.checkAsyncEventQueueEmptyForParallel();
  }
  
  protected void checkAsyncEventQueueEmptyForParallel() {
    int sleepTime = 10000;
    GemFireCacheImpl cache = (GemFireCacheImpl)Misc.getGemFireCache();
    Set<AsyncEventQueue> queues = cache.getAsyncEventQueues();
    for (AsyncEventQueue queue: queues) {
      AsyncEventQueueImpl queueImpl = (AsyncEventQueueImpl)queue;
      if(queueImpl.getSender() instanceof ParallelGatewaySenderImpl){
      ParallelGatewaySenderImpl sender = (ParallelGatewaySenderImpl)queueImpl.getSender();
      //String regionQueueName = sender.getQueue().getRegion().getName();
      while (sender.getQueue().size() != 0)  {         
        Log.getLogWriter().info("region queue is not drained yet. Queue Size is " + sender.getQueue().size() +
                        " wait for " + sleepTime/1000 + " sec");
        try {
          Thread.sleep(sleepTime);
        } catch (InterruptedException ie) {
          //ignored
        }
      }
     } else {
       Log.getLogWriter().info("region queue are not parallel sender queues");
     }
    }
  }

  public static void HydraTask_putLastKeyDBSynchronizer() {
    sqlTest.putLastKeyDBSynchronizer();
  }
  
  public static void HydraTask_putLastKeyAsyncEventListener() {
    sqlTest.putLastKeyDBSynchronizer();
  }


  protected void putLastKeyDBSynchronizer() {
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();
    putLastKeyDBSynchronizer(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_putLastKeyOracleDBSynchronizer() throws SQLException {
    sqlTest.putLastKeyOracleDBSynchronizer();
  }
  
  protected void putLastKeyOracleDBSynchronizer() throws SQLException {
    Connection oraConn = getOracleConnection();
    Connection gConn = getGFEConnection();
    putLastKeyDBSynchronizer(oraConn, gConn);
    closeDiscConnection(oraConn);
    closeGFEConnection(gConn);
  }

  protected void putLastKeyDBSynchronizer(Connection dConn, Connection gConn) {
    boolean isTicket44009Fixed = true;
    int last_key = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.defaultEmployeesPrimary);
    try {
      String insert_last_key = "insert into default1.employees values (" + last_key + ", null, null, null, null)";
      Log.getLogWriter().info("last_key is " + last_key);
      Statement stmt = gConn.createStatement();
      stmt.execute(insert_last_key);
      commit(gConn);
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    int i=0;
    while (true) {
      try {
        int sleepMs = 30000;
        MasterController.sleepForMs(sleepMs);
        Log.getLogWriter().info("waiting for " + sleepMs/1000 +
            " seconds, before verifying the last_key");
        
        //added following to see if Derby gets any dml operations.
        if (!isTicket44009Fixed) {
          ++i;
          if (i>5) {
           Log.getLogWriter().info("start to verfiy before the last key is put into derby");
           verifyResultSetsDBSynchronizer();
          }
        }
            
        String getLastKey = "select max (eid) as MAXEID from default1.employees";
        Statement stmt = dConn.createStatement();
        ResultSet rs = stmt.executeQuery(getLastKey);
        if (rs.next()) {
          if ( rs.getInt("MAXEID") == last_key) {
            rs.close();
          	commit(dConn);
            break;
          }
        }
        Log.getLogWriter().info("last_key not ready yet");
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(dConn, se)) { //handles the deadlock of aborting
          Log.getLogWriter().info("detected the deadlock, will try it again");
        } else {
          SQLHelper.handleSQLException(se);
        }
      }

    }
  }

  public static void HydraTask_verifyResultSetsDBSynchronizer() {
    sqlTest.verifyResultSetsDBSynchronizer();
  }

  protected void verifyResultSetsDBSynchronizer() {
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();
    verifyResultSets(dConn, gConn);
  }

  protected Properties getGemFireProperties() {
    Properties p = GfxdHelper.getConnectionProperties();
    p.setProperty("table-default-partitioned", "true");
    return p;
  }

  //see HydraTask_createGFEDB()
  protected void createGFEDB() {
    createGFXDDB();
  }

  //see HydraTask_createGFEDB()
  protected void createGFEDBForAccessors() {
    createGFXDDBForAccessors();
  }

  ////see HydraTask_createGFEDataStore()
  @SuppressWarnings("unchecked")
  protected void createGFEDataStore() {
    HydraVector vec = TestConfig.tab().vecAt(SQLPrms.serverGroups);
    int whichOne = (int)SQLBB.getBB().getSharedCounters().incrementAndRead(
        SQLBB.dataStoreCount); //need to start from 0
    if (whichOne <= vec.size()) {
      whichOne--;
    } else {
      whichOne=vec.size()-1; //default to last element, if vms are more than configured
    }

    String sg = (String)((HydraVector)vec.elementAt(whichOne)).elementAt(0); //get the item

    if (sg.startsWith("random")) {
      ArrayList<String> serverGroups = (ArrayList<String>) SQLBB.getBB().getSharedMap()
        .get("serverGroups");
      if(whichOne>=serverGroups.size()) {
        whichOne = random.nextInt(serverGroups.size()); //randomly select one
      }
      sg = serverGroups.get(whichOne); //a random server group except for the first 4 data stores
    }
    Properties info = getGemFireProperties();
    Log.getLogWriter().info("This data store is in " + sg);

    /* we do not need to set up the server group properties if
     * this server is in default/all server group
     */
    if (!sg.equals("default")) info.setProperty(SERVERGROUP, sg);
    info.setProperty(HOSTDATA, "true");
    if (workAround49565) info.setProperty(SOCKETLEASETIME, "10000");
    startGFXDDB(info);
  }

  //need to create table based on gfe DDL
  protected void createGFETradeCustomers(Connection conn) throws SQLException {

    Statement s = conn.createStatement();
      // We create a table...
      s.execute("create table customers " +
                "(id int, cust_name varchar(100), vol int, " +
                "since date, addr varchar(100))");
      s.close();

  }

  public static void HydraTask_initializeNetworkPartitionSettings() {
    // Store the losingPartition hostName for use in processing
    // Shutdown and CacheClosedExceptions after Network Partition
    
    networkPartitionDetectionEnabled = TestConfig.tab().booleanAt(
        FabricServerPrms.enableNetworkPartitionDetection, true);
    synchronized (SQLTest.class) {
      if (gemfireCache == null) {
        gemfireCache = Misc.getGemFireCache();
      }
    }
    String losingPartition = TestConfig.tab().stringAt(
        SplitBrainPrms.losingPartition);
    losingSideHost = TestConfig.getInstance()
        .getHostDescription(losingPartition).getHostName();
    SplitBrainBB.putLosingSideHost(losingSideHost);
    oldFabricServer = FabricServerHelper.getFabricServer(); 
    Log.getLogWriter().info(
        "Partition on " + losingPartition + " running on " + losingSideHost
            + " is not expected to survive the network Partition");

    if (RemoteTestModule.getMyHost().equalsIgnoreCase(losingSideHost)) {
      SplitBrainBB.addExpectForcedDisconnect(RemoteTestModule.getMyVmid());
      if (!(RemoteTestModule.getMyClientName().startsWith("accessor") || 
          RemoteTestModule.getMyClientName().startsWith("peerClient") ||
          RemoteTestModule.getMyClientName().startsWith("client")) ) {
        SplitBrainBB.addExpectedReconnectedClient(RemoteTestModule.getMyVmid());
      }
    }
  }

  //provide connection to gfxd/GFE
  public Connection getGFEConnection() {
    Connection conn = null;
    if(SQLPrms.isSnappyMode()) {
      try {
        conn = getSnappyConnection();
        return conn;
      } catch (SQLException se) {
        throw new TestException("Got exception while getting snappy data connection.", se);
      }
    }
    if (isEdge) {
      conn = getGFXDClientConnection();
      return conn;
    }
    else {
      if (!hasTx && setTx) {
        //TODO to be modified once default isolation changed to Connection.TRANSACTION_READ_COMMITTED
        //and set Connection.TRANSACTION_NONE explicitly for the original non txn testing
        Properties p = new Properties();
        if (!reproduceTicket51628) {
          p.put(Attribute.TX_SYNC_COMMITS, "true");
          log().info("using connection property : " + Attribute.TX_SYNC_COMMITS
              + " set to true");
        }
        try {
          conn = GFEDBManager.getConnection(p);
          executeListener("CREATE", "NETWORK_STATE");

          //conn.setAutoCommit(false);
          //conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          //after r50570 product has default Connection.TRANSACTION_READ_COMMITTED
          Log.getLogWriter().info("using product default isolation Connection.TRANSACTION_READ_COMMITTED ");
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException("Not able to get connection " + TestHelper.getStackTrace(se));
        }
      } else {
        try {
          conn = GFEDBManager.getConnection();

          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          //use none txn isolation when setTx is false
          log().info("Connection isolation is set to " + Connection.TRANSACTION_NONE
              + " and getTransactionIsolation() is " + conn.getTransactionIsolation());

          executeListener("CREATE", "NETWORK_STATE");
        } catch (SQLException e) {
          SQLHelper.printSQLException(e);
          throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
      }
    }
    return conn;
  }

  /**
   * Gets Client connection.
   */
  public static Connection getSnappyConnection() throws SQLException {
    Connection conn = null;
    conn = SnappyTest.getLocatorConnection();
    return conn;
  }


  //provide connection to gfxd/GFE -- used to set up dataStore in server groups
  //TODO may need to provide both non_transactional vs default read committed isolation level support
  protected Connection getGFEConnection(Properties info) throws SQLException {
    Connection conn = null;
    try {
      if (isEdge) conn = getGFXDClientConnection(info);
      else {
        conn = GFEDBManager.getConnection(info);
        executeListener("CREATE", "NETWORK_STATE");
      }
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      if (e.getSQLState().equalsIgnoreCase("XBM09")) throw e;
      else throw new TestException ("Not able to get connection " + TestHelper.getStackTrace(e));
    }
    return conn;
  }
  
  protected Connection getGFXDClientConnection() {
    Connection conn = null;
    
    try {
      if (useC3P0 && useGfxdConfig)
        conn = PooledConnectionC3P0.getConnection();
      else if (useDBCP && useGfxdConfig)
        conn = PooledConnectionDBCP.getConnection();
      else if (useTomcatConnPool & useGfxdConfig)
        conn = PooledConnectionTomcat.getConnection();
      else {
        if (!hasTx && setTx) {
          Properties p = new Properties();
          if (!reproduceTicket51628) {
            p.put(Attribute.TX_SYNC_COMMITS, "true");
            log().info("using connection property : " + Attribute.TX_SYNC_COMMITS 
                + " set to true");
          }
          //needs to add sync-commit due to a hydra thread is a logical thread can use different user threads 
          //so sync-commit is needed for even the default isolation
          
          
          boolean gotTxnConn = false;
          //handle txn no HA support yet
          while (!gotTxnConn) {
            try {
              conn = sql.GFEDBClientManager.getConnection(p);
              
              gotTxnConn = true;   
            } catch (SQLException se) {
              if (SQLHelper.gotTXNodeFailureException(se) && isHATest) {
                Log.getLogWriter().info("failed to get connection due to node failure, need to retry");                
              } else {
                SQLHelper.handleSQLException(se);
              }
            }
          }
          
          Log.getLogWriter().info("using product default isolation Connection.TRANSACTION_READ_COMMITTED " );
        } else {
          boolean gotTxnNoneConn = false;
          //work around #51882
          while (!gotTxnNoneConn) {
            try {
              conn = sql.GFEDBClientManager.getConnection();
              
              conn.setTransactionIsolation(Connection.TRANSACTION_NONE); 
              //use none txn isolation when setTx is false

              gotTxnNoneConn = true;
            } catch (SQLException se) {
              if (SQLHelper.gotTXNodeFailureException(se) && isHATest) {
                Log.getLogWriter().info("set isolation failed on default txn isolation connection, need to retry");                
              } else {
                SQLHelper.handleSQLException(se);
              }
            }
          }
          log().info("Connection isolation is set to " + Connection.TRANSACTION_NONE
              + " and getTransactionIsolation() is "+ conn.getTransactionIsolation());
        }
      }
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get the connection " + TestHelper.getStackTrace(e));
    }
    return conn;
  }
  
  //TODO may need to provide both non_transactional vs default read committed isolation level support
  protected Connection getGFXDClientConnection(Properties info) throws SQLException {
    Connection conn = null;
    try {
      conn = sql.GFEDBClientManager.getConnection(info);
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get the connection " + TestHelper.getStackTrace(e));
    }
    return conn;
  }
  

  public void closeGFEConnection(Connection conn) {
    try {
      conn.close();
      executeListener("CLOSE", "NETWORK_STATE");
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to release the connection " + TestHelper.getStackTrace(e));
    }
  }

  //create the function to be used in populating table
  public static void HydraTask_createFunctionToPopulate() {
    sqlTest.createFunctionToPopulate();
  }

  protected void createFunctionToPopulate() {
    Connection gConn = getHdfsQueryConnection();
    try {
      FunctionDDLStmt.createFuncPortf(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_populateTables(){
    sqlTest.populateTables();
  }

  //when no verification is needed, pass dConn as null
  protected void populateTables() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } //only verification case need to populate derby tables
    Connection gConn = getGFEConnection();
    
    if(useGenericSQL){
      log().info("Calling generic populate table as  useGenericSQL=" + useGenericSQL);
      sqlGen.populateTables(dConn, gConn);
    }else{
      populateTables(dConn, gConn);  
    }
    if (dConn !=null)    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_populateCustomersTable(){
    sqlTest.populateCustomersTable();
  }

  protected void populateCustomersTable() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } //only verification case need to populate derby tables
    Connection gConn = getGFEConnection();
   
    populateCustomersTable(dConn, gConn);

    if (dConn !=null)    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void populateCustomersTable(Connection dConn, Connection gConn) {
    List<SQLException> exList = new ArrayList<SQLException>(); //excpetion list used to compare derby and gemfirexd

    new TradeCustomersDMLStmt().populate(dConn, gConn);
    commit(dConn);
    commit(gConn);
    executeListener("CLOSE", "PRINT_CONN");

    SQLHelper.handleMissedSQLException(exList);
  }


  //if dConn is not null, insert sames records to both derby and gemfirexd
  protected void populateTables(Connection dConn, Connection gConn) {
    //int initSize = random.nextInt(10)+1;
    //int initSize = 10;
   
    List<SQLException> exList = new ArrayList<SQLException>(); //excpetion list used to compare derby and gemfirexd

    for (int i=0; i<dmlTables.length; i++) { 
      if (dmlTables[i] != DMLStmtsFactory.TRADE_PORTFOLIOV1) {        
        DMLStmtIF dmlStmt= dmlFactory.createDMLStmt(dmlTables[i]);
        dmlStmt.populate(dConn, gConn);
        commit(dConn);
        commit(gConn);
        executeListener("CLOSE", "PRINT_CONN");
      } 

      //only wait when in serial testing in populating table in non-tx test, when non uniq keys
      //are used in the test with derby database, need to set up the number of workers.
      //this is needed to avoid foreign key reference misses
      if (isSerial && dConn !=null ) waitForBarrier();
    }

    SQLHelper.handleMissedSQLException(exList);
  }


  //perform both dml and ddl operations
  //currently comment out as no support for concurrent dml and ddl ops in the preview
  public static void HydraTask_doOp() {
    sqlTest.doOps();
  }

  protected void doOps() {
    int num = 10;
    boolean dmlOp = (random.nextInt(num)==0)? false: true; //1 out of num chance to do ddl operation
    if (dmlOp) doDMLOp();
    else doDDLOp();
  }

  //perform dml statement
  public static void HydraTask_doDMLOp() {
    sqlTest.doDMLOp();
  }

  //perform ddl statement
  public static void HydraTask_doDDLOp() {
    sqlTest.doDDLOp();
  }

  //perform dml statement in inittask
  public static void HydraTask_doDMLOpInInit() {
    sqlTest.doDMLOpInInit();
  }
  
  
  protected void doDMLOpInInit() {    
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    
    int rep = 20;
    
    for (int i = 0; i<rep; i++) {
      doDMLOp(dConn, gConn);
    }

    if (dConn!=null) {
      closeDiscConnection(dConn);
      //Log.getLogWriter().info("closed the disc connection");
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done dmlOp");
  }
  
  //perform query on join tables
  public static void HydraTask_queryOnJoinOp() {
    sqlTest.queryOnJoinOp();
  }

  protected void doDMLOp() {     
    if (networkPartitionDetectionEnabled) {
      doDMLOp_HandlePartition();
    } else {
      Connection dConn =null;
      if (hasDerbyServer) {
        dConn = getDiscConnection();
      }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
      Connection gConn = getHdfsQueryConnection();
      if(useGenericSQL){
        sqlGen.doDMLOp(dConn, gConn); 
      }else{
        doDMLOp(dConn, gConn);   
      }          
      if (dConn!=null) {
        closeDiscConnection(dConn);
        //Log.getLogWriter().info("closed the disc connection");
      }
      closeGFEConnection(gConn);
    }
    Log.getLogWriter().info("done dmlOp");
    
  }
  
  protected void resetCanceledFlag() {
    // to reset getCanceled flag
    boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
    if (getCanceled != null && getCanceled[0]) {
      getCanceled[0] = false;
      SQLTest.getCanceled.set(getCanceled);
      Log.getLogWriter().info("getCanceled is reset for new ops");

      Boolean needMoreNode = (Boolean) SQLBB.getBB().getSharedMap().get(NEEDMOREDATANODES);
      if (needMoreNode == null || (needMoreNode!= null && !needMoreNode)) setNeedMoreDataNodes(true);
    }
  }
  
  protected void resetNodeFailureFlag() {
    // to reset getCanceled flag
    boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
    if (getNodeFailure != null && getNodeFailure[0]) {
      getNodeFailure[0] = false;
      SQLTest.getNodeFailure.set(getNodeFailure);
      Log.getLogWriter().info("getNodeFailure is reset for new ops");
    }
  }
  
  protected void resetEvictionConflictFlag() {
    // to reset getCanceled flag
    boolean[] getEvictionConflict = (boolean[]) SQLTest.getEvictionConflict.get();
    if (getEvictionConflict != null && getEvictionConflict[0]) {
      getEvictionConflict[0] = false;
      SQLTest.getEvictionConflict.set(getEvictionConflict);
      Log.getLogWriter().info("getEvictionConflict is reset for new ops");
    }
  }


  //randomly select a table to perform dml
  //randomly select an operation to perform based on the dmlStmt (insert, update,delete, select)
  public void doDMLOp_HandlePartition() {
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid());
    int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
    DMLStmtIF dmlStmt= dmlFactory.createDMLStmt(table); //dmlStmt of a table
    int numOfOp = random.nextInt(5)+1;    
    int size = 1;
    Connection dConn =null;
    try {
      if (hasDerbyServer) {
        dConn = getDiscConnection();
      }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
      Connection gConn = getHdfsQueryConnection();
      //perform the opeartions
      String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
      if (operation.equals("insert")) {
        for (int i=0; i<numOfOp; i++) {
          if (setCriticalHeap) resetCanceledFlag();
          if (setTx && isHATest) resetNodeFailureFlag();
          if (setTx && testEviction) resetEvictionConflictFlag();
          dmlStmt.insert(dConn, gConn, size);
          commit(dConn);
          commit(gConn);
        }
      }
      else if (operation.equals("put")) {
        for (int i = 0; i < numOfOp; i++) {
          if (setCriticalHeap) {
            resetCanceledFlag();
          }
          if (setTx && isHATest) resetNodeFailureFlag();
          if (setTx && testEviction) resetEvictionConflictFlag();
          dmlStmt.put(dConn, gConn, size);
          commit(dConn);
          commit(gConn);
        }
      }
      else if (operation.equals("update")) {
        for (int i=0; i<numOfOp; i++) {
          if (setCriticalHeap) resetCanceledFlag();
          if (setTx && isHATest) resetNodeFailureFlag();
          if (setTx && testEviction) resetEvictionConflictFlag();
          dmlStmt.update(dConn, gConn, size);
          commit(dConn);
          commit(gConn);
        }
      }
      else if (operation.equals("delete")) {
        if (setCriticalHeap) resetCanceledFlag();
        if (setTx && isHATest) resetNodeFailureFlag();
        if (setTx && testEviction) resetEvictionConflictFlag();
        dmlStmt.delete(dConn, gConn);
      }
      else if (operation.equals("query")) {
        if (setCriticalHeap) resetCanceledFlag();
        if (setTx && isHATest) resetNodeFailureFlag();
        if (setTx && testEviction) resetEvictionConflictFlag();
        dmlStmt.query(dConn, gConn);
      }
      else
        throw new TestException("Unknown entry operation: " + operation);
      
      commit(dConn); //derby connection is not null;
      commit(gConn);
      //oldSystem = Misc.getDistributedSystem();
      
      if (dConn!=null) {
        closeDiscConnection(dConn);
        //Log.getLogWriter().info("closed the disc connection");
      }
      closeGFEConnection(gConn);
    } catch (Throwable t) {
      boolean forcedDisconnect = ((GemFireCacheImpl)gemfireCache).forcedDisconnect();
      Throwable disconnectCause = ((GemFireCacheImpl)gemfireCache).getDisconnectCause();
      String disconnectException = "com.gemstone.gemfire.ForcedDisconnectException";
      Log.getLogWriter().info("ForcedDisconnect:" + forcedDisconnect);
      Log.getLogWriter().info("DisconnectCause: "+ ((GemFireCacheImpl)gemfireCache).getDisconnectCause());
      boolean networkPartitionHappened =  t.toString().indexOf("has declared that a network partition has occurred") != -1 ;
      if (((forcedDisconnect && disconnectCause != null)
          && (disconnectCause.toString().indexOf(disconnectException) >= 0)) || networkPartitionHappened) {
        SplitBrainBB.addDisconnectedClient(RemoteTestModule.getMyVmid());
      } else {
        Log.getLogWriter().info("My clientName= "+ RemoteTestModule.getMyClientName());
        if (RemoteTestModule.getMyClientName().contains("accessor")) {
          Log.getLogWriter().info("This is accessor node. Check if the No DatastoreException is expected");
          boolean expectNoDatastoreExceptionOnAccessor = TestConfig.tab().booleanAt(
              SplitBrainPrms.expectNoDatastoreExceptionOnAccessor, false);
          if (expectNoDatastoreExceptionOnAccessor) {
            Log.getLogWriter().info("Checking if the exception is caused by no datastore found.");
            if (t.toString().contains("No Datastore found in the " +
            		"Distributed System")) {
              Log.getLogWriter().info("the exception is caused by no datastore found.");
              Log.getLogWriter().info("Hit the expected exception" +
              		" No Datastore found in the Distributed System");
              throw new StopSchedulingTaskOnClientOrder(
                  "Network partition has happened and accessor has Hit the " +
                  "expected exception No Datastore found in the Distributed System");
            }
          }
        }
        throw new TestException("Following error occurred while executing " + t.toString());        
      }
      if (!RemoteTestModule.getMyHost().equalsIgnoreCase(losingSideHost)) {
        throw new TestException("This VM is not supposed to get forced disconnect exception");
      }
      throw new StopSchedulingTaskOnClientOrder("Network partition has happened");
    }
  }

  public static void HydraTask_checkForceDisconnect() {
    boolean forcedDisconnect = ((GemFireCacheImpl)gemfireCache).forcedDisconnect();
    Throwable disconnectCause = ((GemFireCacheImpl)gemfireCache).getDisconnectCause();
    if (forcedDisconnect || disconnectCause != null) {
      SplitBrainBB.addDisconnectedClient(RemoteTestModule.getMyVmid());
      throw new StopSchedulingTaskOnClientOrder("Network partition has happened");
    }
    MasterController.sleepForMs(5000);
  }
  
  /** 
   * ENDTASK to verify that all clients in the losing partition posted to
   * the forcedDisconnectList on the blackboard
   *
   * Also verifies proper receipt of SystemMembershipListener memberCrashed events.
   */
  public synchronized static void HydraTask_verifyLosingPartition() {
     Set disconnectedClients = (Set)SplitBrainBB.getForcedDisconnectList();
     Set expectedDisconnectList = (Set)SplitBrainBB.getExpectForcedDisconnects();
     StringBuffer aStr = new StringBuffer();

     if (!disconnectedClients.equals(expectedDisconnectList)) {
       aStr.append("Expected forcedDisconnects in clientVms [ " + expectedDisconnectList + " ] but the following clientVms had forcedDisconnects [" + disconnectedClients + "]\n");
     } else {
       Log.getLogWriter().info("All expected clientVms in losing partition were forcefully disconnected [ " + disconnectedClients + " ].  No other vms were forcibly disconnected\n");
     }
     
  /* Commenting out this part as we are not having any Gemfire admin members or events.
     // Verify we received the proper number of memberCrashed events 
     Set memberList = (Set)SplitBrainBB.getMembers();
     for (Iterator it=memberList.iterator(); it.hasNext(); ) {
        CrashEventMemberInfo m = (CrashEventMemberInfo)it.next();
        try {
           m.validate();
        } catch (TestException te) {
           aStr.append(te);
        }
     }*/
     
     // Finally, look for any Exceptions posted to the BB
     try {
        TestHelper.checkForEventError(SplitBrainBB.getBB());
     } catch (TestException te) {
        aStr.append("Listener encountered exceptions, first Exception = \n" + te);
     }
     
     if (aStr.length() > 0 ) {
        throw new TestException(aStr.toString());
     }
  }
  

  protected boolean waitForReconnect() {
    boolean reconnected = false;
    //TODO: Make it configurable
    for (int i = 0; i < 3; i++) {
      if (oldFabricServer.isReconnecting()) {
        Log.getLogWriter().info("Fabric server is reconnecting");
        try {
          // TODO this should be configurable
          reconnected = oldFabricServer.waitUntilReconnected(4L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
          return false;
        }
      } else {
        if (i + 1 < 3) {
          Log.getLogWriter().info("Fabric server was not in a reconnecting state");
          Log.getLogWriter().info("Looping and waiting for 30 secs to see if it goes into reconnecting state.");
          try {
            //TODO: Make configurable
            Thread.sleep(30000);
          } catch (InterruptedException e) {
            throw new TestException(e.toString());
          }
        } else {
          Log.getLogWriter().info("Fabric server never went into reconnecting state");
          Log.getLogWriter().info("Continuing test with assumption that it already got reconnected.");
          reconnected = true;
        }
      }
    }
    //return reconnected;
    return oldFabricServer.status().compareTo(FabricService.State.RUNNING) == 0;
  }
  
  public synchronized static void HydraTask_waitForReconnect() {
    // wait to be reconnected
    boolean reconnected = sqlTest.waitForReconnect();
    if (reconnected) {
      SplitBrainBB.addReconnectedClient(RemoteTestModule.getMyVmid());
    } else {
      if (!(RemoteTestModule.getMyClientName().startsWith("accessor") || 
          RemoteTestModule.getMyClientName().startsWith("peerClient") ||
          RemoteTestModule.getMyClientName().startsWith("client")) ) {
        throw new TestException("Wait for reconnect returned false");  
      }
    }
  }  
  
  public synchronized static void HydraTask_verifyReconnect() {
    Set expectedReconnectList = (Set) SplitBrainBB
        .getExpectedReconnectList();
    Set reconnectedClients = (Set) SplitBrainBB.getReconnectedList();
    StringBuffer aStr = new StringBuffer();
    // Verify that all clients reconnected
    if (!expectedReconnectList.equals(reconnectedClients)) {
      aStr.append("Expected all clients to reconnect but only these did: "
          + reconnectedClients + ".   expected list is "
          + expectedReconnectList + "\n");
    }
    if (aStr.length() > 0) {
      throw new TestException(aStr.toString());
    }
    sqlTest.reconnected();
  }
  
  public static void HydraTask_verifyDMLExecution() {
    HydraTask_doDMLOp();
  }
  
  protected void reconnected() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getHdfsQueryConnection();
    if (gConn == null || (hasDerbyServer && dConn == null)) {
      throw new TestException(
          "After reconnect connections were found to be null. dConn =  "
              + dConn + " gConn = " + gConn);
    }
  }
  
  protected void doDMLOp(Connection dConn, Connection gConn) {
    //perform the opeartions
    //randomly select a table to perform dml
    //randomly select an operation to perform based on the dmlStmt (insert, update, delete, select)
    Log.getLogWriter().info("doDMLOp-performing dmlOp, myTid is " + getMyTid());
    int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
    DMLStmtIF dmlStmt = dmlFactory.createDMLStmt(table); //dmlStmt of a table
    int numOfOp = random.nextInt(5) + 1;
    int size = 1;
   
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    Log.getLogWriter().info("doDMLOp-operation=" + operation + "  numOfOp=" + numOfOp);
    
    if (hasPortfolioV1) {
      if (!portfoliov1IsReady) {
        Boolean value = (Boolean) SQLBB.getBB().getSharedMap().get(PORTFOLIOV1READY);
        if (value != null) {
          portfoliov1IsReady = value; //reset value
          Log.getLogWriter().info("portfoliov1IsReady is set to " + portfoliov1IsReady);
        }
      }  
      
      if (!portfoliov1IsReady && table == DMLStmtsFactory.TRADE_PORTFOLIOV1) {
        Log.getLogWriter().info("Trade.portfoliov1 is not ready yet, abort this op");
        return;
      }
            
      if (portfoliov1IsReady && table == DMLStmtsFactory.TRADE_PORTFOLIO &&
          (operation.equals("insert") || operation.equals("put")
          || operation.equals("update") || operation.equals("delete"))) {
        Log.getLogWriter().info("Table 'TRADE.PORTFOLIO' has been dropped and " +
            "View 'TRADE.PORTFOLIO' is not updatable");
        return;
      }
    }
    
    if (operation.equals("insert")) {
      for (int i = 0;i < numOfOp;i++) {
        if (setCriticalHeap) {
          resetCanceledFlag();
        }
        if (setTx && isHATest) resetNodeFailureFlag();
        if (setTx && testEviction) resetEvictionConflictFlag();
        dmlStmt.insert(dConn, gConn, size);
        if (setTx && isHATest) {
          commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
          commit(dConn);
          return;
        }
        commit(dConn);
        commit(gConn);
      } 
    } else if (operation.equals("put")) {
        for (int i = 0; i < numOfOp; i++) {
          if (setCriticalHeap) {
            resetCanceledFlag();
          }
          if (setTx && isHATest) resetNodeFailureFlag();
          if (setTx && testEviction) resetEvictionConflictFlag();
          dmlStmt.put(dConn, gConn, size);
          if (setTx && isHATest) {
            commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
            commit(dConn);
            return;
          }
          commit(dConn);
          commit(gConn);
        }
    } else if (operation.equals("update")) {
      for (int i = 0;i < numOfOp;i++) {
        if (setCriticalHeap) {
          resetCanceledFlag();
        }
        if (setTx && isHATest) resetNodeFailureFlag();
        if (setTx && testEviction) resetEvictionConflictFlag();
        dmlStmt.update(dConn, gConn, size);
        if (setTx && isHATest) {
          commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
          commit(dConn);
          return;
        }
        commit(dConn);
        commit(gConn);
      }
    } else if (operation.equals("delete")) {
      if (setCriticalHeap) {
        resetCanceledFlag();
      }
      if (setTx && isHATest) resetNodeFailureFlag();
      if (setTx && testEviction) resetEvictionConflictFlag();
      dmlStmt.delete(dConn, gConn);
      if (setTx && isHATest) {
        commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
        commit(dConn);
        return;
      }
    } else if (operation.equals("query")) {
      if (setCriticalHeap) {
        resetCanceledFlag();
      }
      if (setTx && isHATest) resetNodeFailureFlag();
      if (setTx && testEviction) resetEvictionConflictFlag();
      dmlStmt.query(dConn, gConn);
      if (setTx && isHATest) {
        commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
        commit(dConn);
        return;
      }
    } else {
      throw new TestException("Unknown entry operation: " + operation);
    }

    commit(dConn); //derby connection is not null;
    commit(gConn);
  }

  //perform ddl statement
  public static void HydraTask_createIndex() {
    sqlTest.createIndex();
  }

  protected void createIndex() {
    Connection gConn = getGFEConnection();
    createIndex(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  protected void createIndex(Connection conn) {
    //createIndex will be independent
    if (createIndex) {
      if(SQLPrms.isSnappyMode()) {
        try {
          conn.createStatement().execute("set schema TRADE");
        } catch(SQLException se){
          Log.getLogWriter().info("Got exception while setting trade schema");
        }
      }
      sql.ddlStatements.IndexDDLStmt indexStmt = new sql.ddlStatements.IndexDDLStmt();
      indexStmt.doDDLOp(null, conn);
    }
  }

  //for concurrent ddl operations mixed with dml, hydra tests will only verify
  //the ddls that do not modify sql data
  protected void doDDLOp() {
    //TODO differentiate which ddl statements to be performed
    Log.getLogWriter().info("performing ddlOp, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    
    if (setCriticalHeap) resetCanceledFlag();
    if (setTx && isHATest) resetNodeFailureFlag();
    if (setTx && testEviction) resetEvictionConflictFlag();
    
    doDDLOp(dConn, gConn);

    if (dConn!=null) {
      closeDiscConnection(dConn);
      //Log.getLogWriter().info("closed the disc connection");
    }
    closeGFEConnection(gConn);

    Log.getLogWriter().info("done ddlOp");
    /*
    //TODO move createIndex into doDDLOp and modify the existing test
    if (createIndex) {
      createIndex();
    }
    */
  }

  protected void doDDLOp(Connection dConn, Connection gConn) {
    //perform the opeartions
    int ddl = ddls[random.nextInt(ddls.length)];
    DDLStmtIF ddlStmt= ddlFactory.createDDLStmt(ddl); //dmlStmt of a table

    ddlStmt.doDDLOp(dConn, gConn);
    
    commit(dConn); 
    commit(gConn);

  }

  //randomly select a join to get query result
  protected void queryOnJoinOp() {
    Log.getLogWriter().info("performing queryOnJoin, myTid is " + getMyTid());

    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();

    if (setCriticalHeap) resetCanceledFlag();
    if (setTx && isHATest) resetNodeFailureFlag();
    if (setTx && testEviction) resetEvictionConflictFlag();
    if (SQLPrms.isSnappyMode()) {
      try {
        gConn.createStatement().execute("set spark.sql.crossJoin.enabled=true");
      } catch(SQLException se) {
        throw new TestException("Got exception while enabling crossJoin in snappy",se);
      }
    }
    //perform the opeartions
    queryOnJoinOp(dConn, gConn);

    if (dConn!=null) {
      closeDiscConnection(dConn);
      //Log.getLogWriter().info("closed the disc connection");
    }

    closeGFEConnection(gConn);


    Log.getLogWriter().info("done dmlOp");
  }
  
  protected void queryOnJoinOp(Connection dConn, Connection gConn) {

    int[] joinTables = SQLPrms.getJoinTables();
    int join = joinTables[random.nextInt(joinTables.length)]; //get random table to perform dml
    sql.joinStatements.JoinTableStmtIF joinQueryStmt= joinFactory.createQueryStmt(join); //dmlStmt of a table
    joinQueryStmt.query(dConn, gConn);

    commit(dConn); 
    commit(gConn);
  }
  
  public static void HydraTask_simpleTest() {
    sqlTest.simpleTest();
  }
  
  protected void simpleTest() {
    Connection conn = getGFEConnection();
    ResultSet rs = getQuery(conn);
    SQLHelper.closeResultSet(rs, conn);
    closeGFEConnection(conn);
  }

  protected ResultSet getQuery(Connection conn) {
    PreparedStatement query1;
    ResultSet rs1 = null;
    String queryString = "select * from trade.customers where (cid >= ?)";
    try {
      query1 = conn.prepareStatement(queryString);
      query1.setInt(1, 0);
      rs1 = query1.executeQuery();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    return rs1;
  }


  protected void getCountQueryResult(Connection conn, String query, String tableName) {
    try {
      ResultSet rs = conn.createStatement().executeQuery(query);
      while (rs.next()) {
        Log.getLogWriter().info("Query:: " + query + "\nResult in GemFireXD:: " + rs.getLong(1));
        SQLBB.getBB().getSharedMap().put(tableName, rs.getLong(1));
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  //verify two results from derby and gemfirexd are same
  protected boolean verifyResultSets(ResultSet rs1, ResultSet rs2) {
    return ResultSetHelper.compareResultSets(rs1, rs2);
  }

  protected void cleanConnection(Connection gConn) {
    commit(gConn);
    closeGFEConnection(gConn);
  }

  protected void verifyResultSets() {

   // if (hasJSON ) {
    //  jsonVerification(getHdfsQueryConnection());
      //verify json column aginst the remaining column of the table
   // }
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verification of query results "
          + "due to manageDerbyServer as false, myTid=" + getMyTid());
      cleanConnection(getGFEConnection());
      return;
    }
  
    Connection gConn = null;
    gConn=getHdfsQueryConnection();
    if (setTx && isHATest) resetNodeFailureFlag();
    
    Connection dConn = getDiscConnection();  
    verifyResultSets(dConn, gConn);
    
    if (verifyUsingOrderBy) dumpHeap();
    
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void writeCountQueryResultsToBB() {
    Connection gConn = null;
    gConn = getGFEConnection();
    String selectQuery = "select count(*) from ";
    ArrayList<String[]> tables = getTableNames(gConn);
    SQLBB.getBB().getSharedMap().put("tableNames", tables);
    for (String[] table : tables) {
      String schemaTableName = table[0] + "." + table[1];
      String query = selectQuery + schemaTableName.toLowerCase();
      getCountQueryResult(gConn, query, schemaTableName);
    }
  }
  
  public static void HydraTask_VerifyResultSetsStandalone(){
    sqlTest.verifyResultSetsStandalone();
  }
  
  public void verifyResultSetsStandalone(){
    boolean throwException = false;
    String hostName = TestConfig.tab().stringAt(SQLPrms.host);
    String port = TestConfig.tab().stringAt(SQLPrms.port);
    StringBuffer str = new StringBuffer();
    String schemaTableName = null;
    String selectQuery = "select * from ";
    try{
    Connection gConn = DriverManager.getConnection("jdbc:gemfirexd://"+hostName+":"+port+"/");
    String query1 = 
        "select tableschemaname, tablename "
            + "from sys.systables where tabletype = 'T' and tableschemaname != '"
            + GfxdConstants.PLAN_SCHEMA + "'" + " and tablename like '%_TMP'";
    String query2 = 
        "select tableschemaname, tablename "
            + "from sys.systables where tabletype = 'T' and tableschemaname != '"
            + GfxdConstants.PLAN_SCHEMA + "'" + " and tablename not like '%_TMP'";
    
    ArrayList<String[]> tables = getTableNames(gConn, query1);
    for (String[] table: tables) {
      try {
        Log.getLogWriter().info("verifyResultSets-verifyResultSets-schema " + table[0] + " and table " + table[1]);
        schemaTableName = table[0]+"."+table[1];
        compareResults(gConn, selectQuery + schemaTableName, selectQuery + schemaTableName.replace("_TMP", ""), schemaTableName, schemaTableName.replace("_TMP", ""));
      }catch (TestException te) {
        Log.getLogWriter().info("verifyResultSets-do not throw Exception yet, until all tables are verified");
        throwException = true;
        str.append(te.getMessage() + "\n");
      }
    }
    if (throwException) {
      throw new TestException ("verifyResultSets-verify results failed: " + str);
    }
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get connection " + TestHelper.getStackTrace(e));
    }
  }

  protected void verifyResultSets(Connection dConn, Connection gConn) {
    if (dConn == null) {
      Log.getLogWriter().info("Connection to disc db is null, could not verify results");
      return;
    }
    boolean throwException = false;
    StringBuffer str = new StringBuffer();
    ArrayList<String[]> tables = getTableNames(gConn);
    for (String[] table: tables) {
      try {
        Log.getLogWriter().info("verifyResultSets-verifyResultSets-schema " + table[0] + " and table " + table[1]);
        //if (SQLPrms.isSnappyMode() && !(table[0].equalsIgnoreCase("SNAPPY_HIVE_METASTORE")))
          verifyResultSets(dConn, gConn, table[0], table[1]);
      }catch (TestException te) {
        if (verifyUsingOrderBy) throw te; //avoid OOME on accessor due to failure with large resultset 
        
        Log.getLogWriter().info("verifyResultSets-do not throw Exception yet, until all tables are verified");
        throwException = true;
        //Log.getLogWriter().info("Logged test failure:\n" + te.getMessage());
        str.append(te.getMessage() + "\n");
      } //temporary
    }
    if (throwException) {
      throw new TestException ("verifyResultSets-verify results failed: " + str);
    }

  }
  
  
  protected void jsonVerification(Connection gConn){
    boolean throwException = false;
    StringBuffer str = new StringBuffer();
    ResultSet jsonRs , tableRs;
    String[] tables = SQLPrms.getTableNames();
    int i =0;
    for (String table: tables) {
      try {
        Log.getLogWriter().info("verifyResultSets-verify JSON Column with remaining column Data for  " + table);
        String jsonSelect = "select " + getAllColumnsFromJson(gConn, table ) + " from " + tables;
        String tableSelect = "select " +getAllColumnsExceptJson(gConn, table) + " from " +table;
        Log.getLogWriter().info("json select is " +  jsonSelect + " and table select is " + tableSelect);
        try{
         jsonRs = gConn.createStatement().executeQuery(jsonSelect);
         tableRs = gConn.createStatement().executeQuery(tableSelect);
        } catch (SQLException se ){
           throw new TestException ( TestHelper.getStackTrace(se));
        }
        ResultSetHelper.compareResultSets(ResultSetHelper.asList(jsonRs, false) , ResultSetHelper.asList(tableRs, false), "JSON columns ", (table + " columns")  );
      }catch (TestException te) {
        Log.getLogWriter().info("verifyResultSets-do not throw Exception yet, until all tables are verified");
        throwException = true;
        //Log.getLogWriter().info("Logged test failure:\n" + te.getMessage());
        str.append(te.getMessage() + "\n");
      } //temporary
      i++;
    }
    if (throwException) {
      throw new TestException ("verifyResultSets-verify results failed: " + str);
    }
  }
  
  protected Connection getHdfsQueryConnection(){
    Connection gConn;
    if (hasHdfs){
      try {
        // set connection property query-HDFS=true
        Properties info = new Properties();
        info.setProperty("query-HDFS", "true");
        gConn = getGFEConnection(info);
        Log.getLogWriter().info("Get gfxd connection with properties=" + info + ", connection=" + gConn);
      }catch (SQLException e) {
        throw new TestException ("Not able to get connection with query-HDFS=true connection property " + TestHelper.getStackTrace(e));
      }  
      
    } else {
      gConn = getGFEConnection();
    }
        
    return gConn;
    
  }
    
  
  public static void HydraTask_verifyNonEvictDestroyTablesResultSets() {
    sqlTest.verifyNonEvictDestroyTablesResultSets();
  }
  
  protected void verifyNonEvictDestroyTablesResultSets() {
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verification of query results "
          + "due to manageDerbyServer as false, myTid=" + getMyTid());
      cleanConnection(getGFEConnection());
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();

    verifyNonEvictDestroyTablesResultSets(dConn, gConn);
  }
  
  protected void verifyNonEvictDestroyTablesResultSets(Connection dConn, Connection gConn) {
    if (dConn == null) {
      Log.getLogWriter().info("Connection to disc db is null, could not verify results");
      return;
    }
    boolean throwException = false;
    StringBuffer str = new StringBuffer();
    ArrayList<String[]> tables = getTableNames(gConn);
    for (String[] table: tables) {
      try {
        Log.getLogWriter().info("schema " + table[0] + " and table " + table[1]);
        if (table[1].equalsIgnoreCase("customers") || table[1].equalsIgnoreCase("securities") 
            || table[1].equalsIgnoreCase("portfolio"))
        verifyResultSets(dConn, gConn, table[0], table[1]);
      }catch (TestException te) {
        Log.getLogWriter().info("do not throw Exception yet, until all tables are verified");
        throwException = true;
        str.append(te.getMessage() + "\n");
      } //temporary
    }
    if (throwException) {
      throw new TestException ("verify results failed: " + str);
    }
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void verifyResultSets(Connection dConn, Connection gConn, String schema, String table) {

    String select = null , finalSelectQuery = null;
    
    if (alterTableDropColumn)
      select = getSelectWithDroppedColumns(gConn, schema, table);  
    else {
      if (!table.equalsIgnoreCase("companies")){
        if(setIdentityColumn){
         if (table.equalsIgnoreCase("securities")){
               select = "select sec_id, symbol, price, exchange, tid from " + schema + "." + table;
             } else {
               if (SQLTest.hasJSON){
                 select = "select " + getAllColumnsExceptJson(gConn, schema + "." + table) + "  from " + schema + "." + table;
               } else
                   select =  "select * from " + schema + "." + table;
             }
         } else {   
          if (SQLTest.hasJSON){
            select = "select " + getAllColumnsExceptJson (gConn, schema  + "." +  table) + "  from " + schema + "." + table;
          } else
          select =  "select * from " + schema + "." + table;
        }
      } else 
        select = "select symbol, exchange, companytype, " + 
            " uid, uuid, companyname, " + "companyinfo, " + 
        		 "note, " + "histPrice, asset, logo, tid from " + schema + "." + table;           
    }
    
    if (table.equalsIgnoreCase("companies")) {
      int base = 5; //avoid OOM due to large clobs and string during verification
      int maxiumWanSites = 5;
      if (numOfWorkers > base) {
        String sql = null;
        for (int i =0; i< (isWanTest? maxiumWanSites*numOfWorkers:numOfWorkers)/base; i++) {
          sql = select + " where tid >= " + base * i + " and tid < " + base * (i+1);
          verifyResultSets(dConn, gConn, schema, table, sql,hasHdfs);         
        }
        sql = select + " where tid >= " + base * (numOfWorkers/base);
        verifyResultSets(dConn, gConn, schema, table, sql, hasHdfs);
      } else {
        throw new TestException ("Test needs to have more than " + base + " of workers");
      }
      
    } else {            
      if (verifyByTid) {
        finalSelectQuery = select;
        select = select + " where tid = " + getMyTid();
      }
      verifyResultSets(dConn, gConn, schema, table, select, hasHdfs);
    }
    
    boolean verifyIndex = TestConfig.tab().booleanAt(SQLPrms.verifyIndex, false);
    if(verifyIndex && !table.equalsIgnoreCase("companies")){
      if (verifyByTid) {
        select = finalSelectQuery;
      }
      Log.getLogWriter().info("Verify data using index for " + schema + "." + table);
      if ( (verifyByTid && getMyTid() == 0 ) || (!verifyByTid) ) 
      verifyIndexResultSet(dConn, gConn, schema, table, select);
    }
    
    if (!ticket45938fixed && hasTx) {
      Log.getLogWriter().info("avoid hitting #45938, continue testing");
      return;
    }
      
    select = "select CAST(count (*) as integer) as numRows from " + schema + "." + table;
    if ( (verifyByTid && getMyTid() == 0 ) || (!verifyByTid) ) 
    verifyResultSets(dConn, gConn, schema, table, select, hasHdfs);
    
    
    //TODO temp work around large number of rows returned using heap dump to clear heap
    if (verifyUsingOrderBy && (table.contains("customers".toUpperCase()) 
        || table.contains("networth".toUpperCase()))) {
      dumpHeap();
    }
  }
  
  //this method dumps heap to clear the garbage on accessor nodes due to large resultset
  protected void dumpHeap() {
    //delete the existing file so that the dump command could be executed again
    String fn = null;
    String dir = TestConfig.getInstance().getClientDescription(RemoteTestModule.getMyClientName())
      .getVmDescription().getHostDescription().getUserDir();
    int pid = RemoteTestModule.getMyPid();
    if (HostHelper.getLocalHostOS() == OSType.unix) {
      fn = dir + "/java_" + HostHelper.getLocalHost()
      + "_" + pid + ".hprof";
    } else {
      fn = dir + "\\java_" + HostHelper.getLocalHost()
      + "_" + pid + ".hprof";
    }
    
    //delete
    //when already created heap dump file is not deleted, no new heap dump
    Log.getLogWriter().info("fn is " + fn);
    File f = new File(fn);
    if (f.exists()) Log.getLogWriter().info("Deleting " + fn);
    if (f.delete()) Log.getLogWriter().info("Deleted " + fn);
   
    ProcessMgr.dumpHeap(HostHelper.getIPAddress().getHostName(), pid, dir, null);
  }
  
  private String getAllColumnsExceptJson(Connection conn ,  String tableName ){
    String columnNameList = "";
    List<String[]> columnList = getColumnNameAndType(conn, tableName.split("\\.")[0], tableName.split("\\.")[1]);
    for (String[] columnDetails : columnList ){
      if ( ! columnDetails[0].toLowerCase().contains("json"))
      columnNameList += columnDetails[0] + " " ;
    }
    
    columnNameList=columnNameList.trim().replace(" " , ",");    
    return columnNameList;
    
  }
  
  private String getAllColumnsFromJson(Connection conn , String tableName ){
    String columnNameList = "";
    List<String[]> columnList = getColumnNameAndType(conn, tableName.split("\\.")[0], tableName.split("\\.")[1]);
    for (String[] columnDetails : columnList ){
      if ( ! columnDetails[0].equalsIgnoreCase("json_details"))
      columnNameList += "cast(json_fetchAttribute(json_details, '"+columnDetails[0] + "') as " + columnDetails[1] + ") as " + columnDetails[0] +"#" ;
    }
    
    columnNameList=columnNameList.trim().replace("#" , ",");    
    return columnNameList;
    
  }
  
  protected void verifyResultSets(Connection dConn, Connection gConn, String schema, String table, String select , boolean hasHdfs){
    if (hasHdfs ) {
      //verify operational data
      Map<String, String> hdfsExtnMap = (Map<String, String>)SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_EXTN_PARAMS); 
      String key = schema.toUpperCase()+"."+table.toUpperCase()+HDFSSqlTest.EVICTION_CRITERIA;
      String selectOperational = select;
      String selectNonOperational = select ;
      if (hdfsExtnMap.get(key ) != null) {
        if (SQLTest.hasJSON && ( select.toLowerCase().contains("securities")  || select.toLowerCase().contains("buyorder")  )){
          selectOperational = select + " where 1 =2 ";
        }
        else {
       selectOperational = select + " where " + hdfsSqlTest.getReversedSqlPredicate(hdfsExtnMap.get(key));
       selectNonOperational = select + " where " + hdfsExtnMap.get(key);
        }
      log().info("verifyResultSets-Verifying Operational Data with " + selectOperational);
      verifyResultSets(dConn,gConn,schema,table,selectOperational);            
      log().info("verifyResultSets-Verifying Non Operational Data with " + selectNonOperational);
      verifyResultSets(dConn,gConn,schema,table,selectNonOperational);
      } else {
        boolean queryHDFS = TestConfig.tab().booleanAt(SQLPrms.queryHDFSWhileVerifyingResults, true);
        if (!queryHDFS) {
          select = select + "  -- GEMFIREXD-PROPERTIES queryHDFS=false";
        }
        verifyResultSets(dConn,gConn,schema,table,select);
      }
    } else{
      if (verifyUsingOrderBy && !select.contains("select count")) {
        select += getOrderByClause(gConn, schema, table);    
      }
      
      verifyResultSets(dConn,gConn,schema,table,select);
    }
    
  }
  protected void verifyResultSets(Connection dConn, Connection gConn, String schema, String table, String select ) {
    ResultSet gRS = null;
    try {
      log().info("verifyResultSets-executing " + select + " in derby");
      ResultSet dRS = dConn.createStatement().executeQuery(select); 
      log().info("verifyResultSets-executed " + select + " in derby");
      
      try {
        log().info("verifyResultSets-executing " + select + " in gfxd");
        gRS = gConn.createStatement().executeQuery(select);
        log().info("verifyResultSets-executed " + select + " in gfxd");
        //add handling of HA in the test code as this could be executed in main task in new txn testing
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (!SQLHelper.checkGFXDException(gConn, se)) {
          Log.getLogWriter().info("could not get resultset due to HA");
          dRS.close(); //close the derby result set
          return;
        } else SQLHelper.handleSQLException(se);
      } 
      
      if (useWriterForWriteThrough && table.equalsIgnoreCase("txhistory"))
        //for posDup cases on a table without primary key contraint with writer invocation
        ResultSetHelper.compareDuplicateResultSets(dRS, gRS);
      else if (isHATest && !setTx && table.equalsIgnoreCase("txhistory") && SQLHelper.isThinClient(gConn)) 
        Log.getLogWriter().info("will not compare results for table " +
            "without primary key in HA test when using thin client driver to avoid #44929"); //work around #44929
      else if (isHATest && table.equalsIgnoreCase("txhistory") && SQLTest.hasAsyncDBSync) 
        Log.getLogWriter().info("will not compare results for the table " +
            "without primary key in HA test when using dbsync to avoid #45696"); //work around #45696
      else if (verifyUsingOrderBy) {
        ResultSetHelper.compareSortedResultSets(dRS, gRS);
      }
      else {
        if (alterTableDropColumn) ResultSetHelper.compareResultSets(dRS, gRS);
        else if (hasTx && needNewConnection() && gRS == null) {
          Log.getLogWriter().info("will not compare results -- failed to get results in gfxd due to node failure");
        }
        else ResultSetHelper.compareResultSets(dRS, gRS);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(gRS, gConn);
      commit(dConn);
      commit(gConn);
    }
  }
  

  protected void verifyIndexResultSet(Connection dConn, Connection gConn, String schema, String table, String select){
    String sql = null;
    List<String[]> colInfo = getColumnNameAndType(gConn, schema, table);
    if (colInfo.size() == 0 && setCriticalHeap ) {
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled != null && getCanceled[0]) {
        log().info("could not get column info from the table due to critical heap cancelled event, abort this op");
        return;
      } else 
        throw new TestException("could not get column info from metadata");
    }
    int whichCol1 = random.nextInt(colInfo.size());
    int whichCol2 = (whichCol1+1)%colInfo.size(); 
    int whichCol3 =  random.nextInt(colInfo.size());
    
    String colName1 = colInfo.get(whichCol1)[0];
    String colName2 = colInfo.get(whichCol2)[0];
    String colName3 = colInfo.get(whichCol3)[0];
    
    int colType1 = Integer.valueOf(colInfo.get(whichCol1)[1]).intValue();
    int colType2 = Integer.valueOf(colInfo.get(whichCol2)[1]).intValue();
        
    String crtria1 = colName1 + getCriteriaForType(colType1);
    String crtria2 = colName2 + getCriteriaForType(colType2);
    
    if ( verifyByTid) 
      sql = "select " + colName1 +"," + colName2 +"," + colName3 + " from  "  + schema + "." + table + " where " + crtria1  + " and " + crtria2 ;
    else 
      sql = select + " where " + crtria1  + " or " + crtria2 ;
    
    if (verifyUsingOrderBy && !sql.contains("select count")) sql += getOrderByClause(gConn, schema, table);
    
    verifyResultSets(dConn, gConn, schema, table, sql);
  }
  
  protected String getSelectWithDroppedColumns(Connection conn, String schema, String table) {
    String sql =  "select * from " + schema + "." + table;
    StringBuilder select = new StringBuilder();
    select.append("select ");
    ResultSet rs = null;
    try {
      rs = conn.createStatement().executeQuery(sql);
      
      ResultSetMetaData rsmd = rs.getMetaData();
      int numColumns = rsmd.getColumnCount();
      for (int i=1; i<numColumns+1; i++) {
        if (i<numColumns)
          select.append(rsmd.getColumnName(i) + ", ");
        else
          select.append(rsmd.getColumnName(i) + " ");
      }    
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    select.append("from " + schema + "." + table);
    return select.toString();
  }
  
  protected String getOrderByClause(Connection conn, String schema, String table) {
    StringBuilder orderby = new StringBuilder();
    orderby.append(" order by ");
    ResultSet rs = null;
    try {
      //table does not have primary keys
      if (table.equalsIgnoreCase("txhistory")) {
        String sql =  "select * from " + schema + "." + table;
  
        rs = conn.createStatement().executeQuery(sql);
        
        ResultSetMetaData rsmd = rs.getMetaData();
   
        int numColumns = rsmd.getColumnCount();
        for (int i=1; i<numColumns+1; i++) {
          orderby.append(rsmd.getColumnName(i) + ", ");
        } 
      } else {
        DatabaseMetaData dbmd = conn.getMetaData();
        
        rs = dbmd.getPrimaryKeys(null, schema, table);
    
        ArrayList<String> primaryKeyColumns = new ArrayList<String>(); 
        while(rs.next()){
          String columnName = rs.getString(4);
          orderby.append(columnName + ", ");  
          primaryKeyColumns.add(columnName);
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }

    orderby.deleteCharAt(orderby.lastIndexOf(","));
    return orderby.toString();
  }

  protected List<String[]> getColumnNameAndType(Connection conn, String schema, String table) {
    List<String[]> colList = new ArrayList<String[]>(); 
    
    String sql =  "select * from " + schema + "." + table + " where 1=0";
    ResultSet rs = null;
    try {      
      rs = conn.createStatement().executeQuery(sql);
      
      ResultSetMetaData rsmd = rs.getMetaData();
      int numColumns = rsmd.getColumnCount();
      for (int i=1; i<numColumns+1; i++) {
        String[] c = new String[2];
        c[0] = rsmd.getColumnName(i);
        c[1] = rsmd.getColumnType(i) + "";
        colList.add(c);
      }    
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return colList;
  }
  
  protected String getCriteriaForType(int type){
    switch (type){
      case java.sql.Types.INTEGER:
        return " >= 0 ";
      case java.sql.Types.DECIMAL:
        return " >= 0.0 ";        
      case java.sql.Types.VARCHAR:
        return " != 'abcxyx' ";   
      case java.sql.Types.CHAR:
        return " != 'abcxyx' ";  
      case java.sql.Types.DATE:
        return " <= CURRENT_DATE ";
      case java.sql.Types.TIME:
        return " <= CURRENT_TIME ";
      case java.sql.Types.TIMESTAMP:
        return " <= CURRENT_TIMESTAMP ";
      default:
        return " >= 0 ";
    }       
  }
  
  public static void HydraTask_verifyResultSets() {
    sqlTest.verifyResultSets();
  }

  public static void HydraTask_writeCountQueryResultsToSQLBB() {
    sqlTest.writeCountQueryResultsToBB();
  }

  public static void HydraTask_clearTables() {
    sqlTest.clearTables();
  }

  
  public static void HydraTask_clearTablesInOrder() {
    sqlTest.clearTablesInOrder();
  }
  
  protected void clearTables() {
    if (!hasDerbyServer) return;
    //else
    Connection dConn=getDiscConnection();
    Connection gConn = getGFEConnection();
    if (random.nextBoolean() && hasNetworth) clearTablesInOrder(dConn, gConn);
    else clearTables(dConn, gConn);
    commit(dConn);
    commit(gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void clearTablesInOrder() {
    if (!hasDerbyServer) return;
    //else
    Connection dConn=getDiscConnection();
    Connection gConn = getGFEConnection();
    clearTablesInOrder(dConn, gConn);
    commit(dConn);
    commit(gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void clearTablesInOrder(Connection dConn, Connection gConn) {
    clearTables(dConn, gConn, "trade", "buyorders");
    clearTables(dConn, gConn, "trade", "txhistory");
    clearTables(dConn, gConn, "trade", "sellorders");
    clearTables(dConn, gConn, "trade", "portfolio");
    clearTables(dConn, gConn, "trade", "networth");
    clearTables(dConn, gConn, "trade", "customers");
    clearTables(dConn, gConn, "trade", "securities");
  }

  //delete all records in the tables
  protected void  clearTables(Connection dConn, Connection gConn) {
    if (dConn==null) return;
    ResultSet rs = null;

    try {
      //Connection newConn = getGFEConnection(); //work around #41711
      //ResultSet rs = newConn.createStatement().executeQuery("select tableschemaname, tablename "
      //    + "from sys.systables where tabletype = 'T' ");
      rs = gConn
          .createStatement()
          .executeQuery(
              "select tableschemaname, tablename "
                  + "from sys.systables where tabletype = 'T' and tableschemaname != '"
                  + GfxdConstants.PLAN_SCHEMA + "' "
                  + (SQLPrms.isSnappyMode()?" and tableschemaname != 'SNAPPY_HIVE_METASTORE'": ""));
      
      if (!setTx) {
        while (rs.next()) {
          String schemaName = rs.getString(1);
          String tableName = rs.getString(2);
          
          clearTables(dConn, gConn, schemaName, tableName);
        }
      } else {
        //with txn, current implementation automatcially rollback txn once exception occur.
        //TODO, once savepoint is supported, the following could be changed to add a save point 
        //before the clearTables call.
        
        List<Struct> rsList = ResultSetHelper.asList(rs, false);
        
        for (Struct info: rsList) {
          clearTables(dConn, gConn, (String)info.get("tableschemaname".toUpperCase()), 
              (String) info.get("tableName".toUpperCase()));
        }
        
      }
      
      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
    }

  }

  protected void clearTables(Connection dConn, Connection gConn, String schema, String table) {
    int dCount= 0;
    int gCount = 0;
    SQLException dse = null;
    SQLException sse = null;
    /*
    String delete = (RemoteTestModule.getCurrentThread().getThreadId() != 0)
        ? "delete from " + schema + "." + table
        : "truncate table " + schema + "." + table;
    */
    String delete = "delete from " + schema + "." + table;
    Log.getLogWriter().info(delete);
    try {
      if (dConn !=null)  {
        dCount = dConn.createStatement().executeUpdate(delete);
        Log.getLogWriter().info("derby deleted " + dCount + " rows.");
      }
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(dConn, se)) {
        return; //other threads hold the lock and should delete the records
      } else if (se.getSQLState().equalsIgnoreCase("23503")) {
        Log.getLogWriter().info("could not delete due to delete restrict in derby");
      } else if (se.getSQLState().equalsIgnoreCase("XCL48")) {
        Log.getLogWriter().info("could not truncate due to foreign key reference in derby");
      }
      else dse = se;
    }
    try {
      gCount = gConn.createStatement().executeUpdate(delete);
      Log.getLogWriter().info("gemfirexd deleted " + gCount + " rows.");
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("23503")) {
        Log.getLogWriter().info("could not delete due to delete restrict in gfxd");
      } else if (se.getSQLState().equalsIgnoreCase("XCL48")) {
        Log.getLogWriter().info("could not truncate due to foreign key reference in gfxd");
      } else if (setTx && se.getSQLState().equalsIgnoreCase("X0Z02")) {
        Log.getLogWriter().info("Got expected conflict exception using txn");
        //TODO do not compare exception here -- 
        //derby may delete the rows but gfxd not
        //may need to add rollback derby op here.
        return;
      } else
        sse = se;
    }
    SQLHelper.compareExceptions(dse, sse);
    commit(dConn);
    commit(gConn);

  }

  //drop all the tables
  protected void  dropTables(Connection dConn, Connection gConn) {
    //TODO. for close task
  }

  public void commit(Connection conn) {
    if (conn == null) return;    
    try {
      //add the check to see if query cancellation exception or low memory exception thrown
      if (setCriticalHeap) {      
        boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
        if (getCanceled != null && getCanceled[0] == true && SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("memory runs low in gfxd, rollback the corresponding" +
              " derby ops");
          conn.rollback();
          return;  //do not check exception list if gfxd gets such exception
        }
      }
      
      if (!hasTx && setTx && isHATest) {
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure != null && getNodeFailure[0] && SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("got node failure exception in gfxd, rollback the corresponding" +
          " derby ops");
          conn.rollback();
          return;  //do not check exception list if gfxd gets such exception
        }
      }
      
      //work around #51582
      if (!hasTx && setTx && testEviction && SQLTest.workaround51582) {
        boolean[] getEvictionConflict = (boolean[]) SQLTest.getEvictionConflict.get();
        if (getEvictionConflict != null && getEvictionConflict[0] && SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("got conflict exception in gfxd, rollback the corresponding" +
          " derby ops");
          conn.rollback();
          return;  //do not check exception list if gfxd gets such exception
        }
      }
      
      String name = (SQLHelper.isDerbyConn(conn))? "derby " : "gfxd ";
      Log.getLogWriter().info("committing the ops for " + name);
      conn.commit();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se) 
          && se.getSQLState().equalsIgnoreCase("08003")) {
      	Log.getLogWriter().info("detected current connection is lost, possibly due to reade time out");
        return; //add the case when connection is lost due to read timeout
      } else if (!hasTx && SQLTest.setTx && isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        if (SQLTest.setTx) {
          //when Txn HA is not yet supported
          Log.getLogWriter().warning("got node failure exception or bucket moved exception during commit");
        
          boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
          if (getNodeFailure == null) getNodeFailure = new boolean[1];
          getNodeFailure[0] = true;
          SQLTest.getNodeFailure.set(getNodeFailure);
          return;  //commit failed in gfxd, roll back the corresponding op in derby
        }
      } else if (!hasTx && SQLTest.setTx && isOfflineTest &&
        se.getSQLState().equals("X0Z03") && se.getCause() != null
        && (se.getCause().getMessage().contains("X0Z09") || se.getCause().getMessage().contains("X0Z08"))) {

        Log.getLogWriter().warning("ready-only txn could not commit as dml op hit offline exception");
        
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure == null) getNodeFailure = new boolean[1];
        getNodeFailure[0] = true;
        SQLTest.getNodeFailure.set(getNodeFailure);
        return;  //commit failed in gfxd, roll back the corresponding op in derby
      }
      else
        SQLHelper.handleSQLException(se);
    }
  }

  protected boolean needNewConnection() {
    if (!hasTx) return false;
    Boolean needNewConn = (Boolean) sql.sqlTx.SQLDistTxTest.needNewConnAfterNodeFailure.get();
    if (needNewConn != null && needNewConn) {
      return true;
    } else return false;
  }
  /**
   * to get my ThreadID used for update and delete record, so only particular thread can
   * update or delete a record it has inserted.
   * @return The threadId of the current hydra Thread.
   */
  protected int getMyTid() {
    if (testWanUniqueness) {
      return sql.wan.WanTest.myWanSite; //return wan site id as tid, use tid to confine each site op on its set of keys
    }
    
    int myTid = RemoteTestModule.getCurrentThread().getThreadId();
    return myTid;
  }

  //only one thread should perform this task by test, as the method does not check if
  //other threads perform the same concurrently
  public static void HydraTask_stopVms() {
    sqlTest.stopVms();
  }
  
  protected void stopVms() { 
    int numToKill = TestConfig.tab().intAt(StopStartPrms.numVMsToStop);
    List<ClientVmInfo> vms = stopVMs(numToKill, "store");
    vmListForOfflineTest = vms;
  }
  
  @SuppressWarnings("unchecked")
  protected List <ClientVmInfo> stopVMs(int numToKill, String target) {
    Object[] tmpArr = StopStartVMs.getOtherVMs(numToKill, target);
    // get the VMs to stop; vmList and stopModeList are parallel lists
    List <ClientVmInfo> vmList;
    List<String> stopModeList;
    
    vmList = (List<ClientVmInfo>)(tmpArr[0]);
    stopModeList = (List<String>)(tmpArr[1]);
    for (ClientVmInfo client : vmList) {
      PRObserver.initialize(client.getVmid());
    } //clear bb info for the vms to be stopped/started

    if (vmList.size() != 0) StopStartVMs.stopVMs(vmList, stopModeList);
    
    return vmList;
  }
  
  public static void HydraTask_startVms() {
    sqlTest.startVms();
  }
  
  protected void startVms() {
    int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
    startVms(numOfPRs);
  }
  
  protected void startVms(int numOfPRs) {   
    List<ClientVmInfo> vms = vmListForOfflineTest;
    
    if (vms == null) throw new TestException("vmListForOfflineTest is set to " + vmListForOfflineTest);
    
    StopStartVMs.startVMs(vms);
    
    Log.getLogWriter().info("Total number of PR is " + numOfPRs);
    PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
  }
  
  public static void HydraTask_cycleRemainingStoreVms() {
    int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
    storeUpgradeCompleted = SQLRollingUpgradeBB.getBB().getSharedCounters()
        .read(SQLRollingUpgradeBB.storeUpgradeComplete) == 1;
    if (storeUpgradeCompleted) {
      return;
    }
    Set<Integer> restartedStoreVms = null;
    restartedStoreVms = (Set<Integer>) (SQLRollingUpgradeBB.getBB()
        .getSharedMap().get(restartedStoreVmIDsKey));
    if (restartedStoreVms == null) {
      // None of the store Vms have been restarted yet
      MasterController.sleepForMs(5000);
      return;
    }
    boolean accessorUpgradeWaiting = SQLRollingUpgradeBB.getBB().getSharedCounters().read(SQLRollingUpgradeBB.accessorUpgradeWaiting) == 1;
    if (accessorUpgradeWaiting) {
      MasterController.sleepForMs(2000);
      return;
    }
    List vmInfoList = StopStartVMs.getAllVMs();
    long recycling = SQLRollingUpgradeBB.getBB().getSharedCounters().incrementAndRead(SQLRollingUpgradeBB.recycling);
    if (recycling != 1) {
      SQLRollingUpgradeBB.getBB().getSharedCounters().decrement(SQLRollingUpgradeBB.recycling);
      MasterController.sleepForMs(5000);
      SQLRollingUpgradeBB.getBB().getSharedCounters().setIfLarger(SQLRollingUpgradeBB.remainingStoreUpgradeWaiting, 1);
      return;
    }
    vmInfoList = StopStartVMs.getMatchVMs(vmInfoList, "store");
    List<ClientVmInfo> vmsToRestart = new ArrayList<ClientVmInfo>();
    List<String> stopModes = new ArrayList<String>();
    while (vmInfoList.size() != 0) {
      ClientVmInfo vmInfo = null;
      if (vmInfoList.size() != 0) {
        vmInfo = (ClientVmInfo) vmInfoList.get(0);
        vmInfoList.remove(0);
      }
      if (!restartedStoreVms.contains(vmInfo.getVmid())) {
        vmsToRestart = new ArrayList<ClientVmInfo>();
        vmsToRestart.add(vmInfo);
        //stopModes.add("nice_exit");
        PRObserver.initialize(vmInfo.getVmid());
        StopStartVMs.stopVM(vmInfo, "nice_exit");
        Log.getLogWriter().info(
            "Sleeping for " + 2 + " seconds to allow ops to run...");
        MasterController.sleepForMs(2 * 1000);
        StopStartVMs.startVM(vmInfo);
        PRObserver.waitForRebalRecov(vmsToRestart, 1, numOfPRs, null, null, false);
      }
      Log.getLogWriter().info("Store VMs size: " + vmInfoList.size());
    }
//    StopStartVMs.stopStartVMs(vmsToRestart, stopModes);
    
    SQLRollingUpgradeBB.getBB().getSharedCounters().zero(SQLRollingUpgradeBB.recycling);
    SQLRollingUpgradeBB.getBB().getSharedCounters().zero(SQLRollingUpgradeBB.remainingStoreUpgradeWaiting);
    SQLRollingUpgradeBB.getBB().getSharedCounters().increment(SQLRollingUpgradeBB.storeUpgradeComplete);
  }
  
  private boolean waitForOtherControllers() {
    locatorUpgradeCompleted = (SQLRollingUpgradeBB.getBB().getSharedCounters().read(SQLRollingUpgradeBB.locatorUpgradeComplete)) == 1;
    if (!locatorUpgradeCompleted) {
      TestHelper.waitForCounter(SQLRollingUpgradeBB.getBB(), "SQLRollingUpgradeBB.locatorUpgradeComplete", 
          SQLRollingUpgradeBB.locatorUpgradeComplete, 1, true, -1, 2000);
      locatorUpgradeCompleted = true; // For future reference
    }
    boolean accessorUpgradeWaiting = SQLRollingUpgradeBB.getBB().getSharedCounters().read(SQLRollingUpgradeBB.accessorUpgradeWaiting) == 1;
    if (accessorUpgradeWaiting) {
      MasterController.sleepForMs(2000);
      return true;
    }
    boolean remainingStoreUpgradeWaiting = SQLRollingUpgradeBB.getBB()
        .getSharedCounters()
        .read(SQLRollingUpgradeBB.remainingStoreUpgradeWaiting) == 1;
    if (remainingStoreUpgradeWaiting) {
      MasterController.sleepForMs(2000);
      return true;
    }
    
    long recycling = SQLRollingUpgradeBB.getBB().getSharedCounters().incrementAndRead(SQLRollingUpgradeBB.recycling);
    if (recycling != 1) {
      SQLRollingUpgradeBB.getBB().getSharedCounters().decrement(SQLRollingUpgradeBB.recycling);
      MasterController.sleepForMs(5000);
      return true;
    }
    return false;
  }
  
  public static void HydraTask_cycleStoreVms() {
    if (cycleVms) sqlTest.cycleStoreVms();
  }

  protected void cycleStoreVms() {
    if (!cycleVms) {
      Log.getLogWriter().warning("cycleVms sets to false, no node will be brought down in the test run");
      return;
    }
    if (waitForLocatorUpgrade) {
      if(waitForOtherControllers()) {
        return;
      }
    }
    int numToKill = TestConfig.tab().intAt(StopStartPrms.numVMsToStop, 1);
    int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
    List<ClientVmInfo> vms = null;
    if (SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.stopStartVms) == 1) {
      Object vmCycled = SQLBB.getBB().getSharedMap().get("vmCycled");
      if (testInitDDLReplay && vmCycled == null) {
        Object waitedForInitDDLReplay = SQLBB.getBB().getSharedMap().get("waitedForInitDDLReplay");
        if (waitedForInitDDLReplay == null) {
          long startTime = System.currentTimeMillis();
          SQLBB.getBB().getSharedMap().put("cycleInitDDLReplayVMStartTime", startTime);
          int sleepMS = 120000;
          Log.getLogWriter().info("allow  " + sleepMS/1000 + " seconds" +
            " for initial DDL replay vms to start up");
          MasterController.sleepForMs(sleepMS);
          SQLBB.getBB().getSharedMap().put("waitedForInitDDLReplay", "true");
          try {
            vms = stopStartVMs(numToKill);
          } catch (TestException te) {
            long now = System.currentTimeMillis();
            Log.getLogWriter().warning("Initial DDL replay was not ready for " + (now - startTime) / 1000 +
                " seconds ");

            SQLBB.getBB().getSharedCounters().zero(SQLBB.stopStartVms);
            return;
          }
        } else {
          while (true) {
            try {
              vms = stopStartVMs(numToKill);
              break;
            } catch (TestException te) {
              long startTime = (Long) SQLBB.getBB().getSharedMap().get("cycleInitDDLReplayVMStartTime");
              long now = System.currentTimeMillis();
              Log.getLogWriter().warning("VMs for initial DDL replay was not ready for " + (now - startTime) / 1000 +
                  " seconds ");
              int sleepMS = 30000;
              MasterController.sleepForMs(sleepMS);
              Log.getLogWriter().info("allow another " + sleepMS/1000 + " seconds" +
              " for initial DDL replay vms to start up");
            }
          }
        }
      } //first time
      else {
        //relaxing a little for HA tests
        //using the BB to track when to kill the next set of vms
        
        Long lastCycledTimeFromBB = (Long) SQLBB.getBB().getSharedMap().get(LASTCYCLEDTIME);
        if (lastCycledTimeFromBB == null) {
          int sleepMS = 20000;
          Log.getLogWriter().info("allow  " + sleepMS/1000 + " seconds before killing others");
          MasterController.sleepForMs(sleepMS); //no vms has been cycled before
        } else if (lastCycledTimeFromBB > lastCycledTime){
          lastCycledTime = lastCycledTimeFromBB;
          log().info("update last cycled vms is set to " + lastCycledTime);
        }
        
        if (lastCycledTime != 0) {
          long currentTime = System.currentTimeMillis();
          if (currentTime - lastCycledTime < waitTimeBeforeNextCycleVM * THOUSAND) {
            SQLBB.getBB().getSharedCounters().zero(SQLBB.stopStartVms);
            return;
          } else {
            log().info("cycle vms starts at: " + currentTime);
          }
        }

        vms = stopStartVMs(numToKill);
      }
      
      if (vms== null || vms.size() == 0) {
        Log.getLogWriter().info("no vm being chosen to be stopped"); //choose the same vm has dbsynchronizer
        SQLBB.getBB().getSharedCounters().zero(SQLBB.stopStartVms); 
        return;
      } else {
        if (waitForLocatorUpgrade) {
          Set<Integer> restartedStoreVms = null;
          restartedStoreVms = (Set<Integer>)(SQLRollingUpgradeBB.getBB().getSharedMap().get(restartedStoreVmIDsKey));
          if (restartedStoreVms == null) {
            restartedStoreVms = new HashSet<Integer>();
          }
          for (ClientVmInfo info : vms) {
            if (!restartedStoreVms.contains(info.getVmid())) {
              restartedStoreVms.add(info.getVmid());
            }
          }
          SQLRollingUpgradeBB.getBB().getSharedMap().put(restartedStoreVmIDsKey, restartedStoreVms);
        }
      }
      Log.getLogWriter().info("Total number of PR is " + numOfPRs);
      if (numOfPRs > 0)
      PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);

      long currentTime = System.currentTimeMillis();
      log().info("cycle vms finishes at: " + currentTime);
      
      SQLBB.getBB().getSharedMap().put(LASTCYCLEDTIME, currentTime);
      SQLBB.getBB().getSharedMap().put("vmCycled", "true");
      SQLBB.getBB().getSharedCounters().zero(SQLBB.stopStartVms);
    }
    if (waitForLocatorUpgrade) {
      SQLRollingUpgradeBB.getBB().getSharedCounters().zero(SQLRollingUpgradeBB.recycling);
    }
  }

  public static void HydraTask_cycleAccessorVms() {
    waitForLocatorUpgrade = TestConfig.tab().booleanAt(SQLRollingUpgradePrms.waitForLocatorUpgrade, false);
    if (!cycleVms) {
      Log.getLogWriter()
          .warning(
              "cycleVms sets to false, no node will be brought down in the test run");
      return;
    }
    if (waitForLocatorUpgrade) {
      locatorUpgradeCompleted = (SQLRollingUpgradeBB.getBB().getSharedCounters().read(SQLRollingUpgradeBB.locatorUpgradeComplete)) == 1;
      if (!locatorUpgradeCompleted) {
        return;
      }
    }
    accessorUpgradeCompleted = SQLRollingUpgradeBB.getBB().getSharedCounters()
        .read(SQLRollingUpgradeBB.accessorUpgradeComplete) == 1;
    if (accessorUpgradeCompleted) {
      return;
    }
    
    long recycling = SQLRollingUpgradeBB.getBB().getSharedCounters().incrementAndRead(SQLRollingUpgradeBB.recycling);
    if (recycling != 1) {
      SQLRollingUpgradeBB.getBB().getSharedCounters().decrement(SQLRollingUpgradeBB.recycling);
      SQLRollingUpgradeBB.getBB().getSharedCounters().setIfLarger(SQLRollingUpgradeBB.accessorUpgradeWaiting, 1);
      TestHelper.waitForCounter(SQLRollingUpgradeBB.getBB(), "SQLRollingUpgradeBB.recycling", 
          SQLRollingUpgradeBB.recycling, 0, true, -1, 1000);      
      //return;
    }
    
    List<ClientVmInfo> vms = null;
    // relaxing a little for HA tests
    int sleepMS = 20000;
    Log.getLogWriter().info(
        "allow  " + sleepMS / 1000 + " seconds before killing others");
    MasterController.sleepForMs(sleepMS);
    // vms = stopStartVMs(numToKill, "accessor");

    vms = (List<ClientVmInfo>) StopStartVMs.getMatchVMs(
        StopStartVMs.getAllVMs(), "accessor");
    // get the VMs to stop; vmList and stopModeList are parallel lists

    while (vms.size() != 0) {
      ClientVmInfo vmInfo = null;
      if (vms.size() != 0) {
        vmInfo = vms.get(0);
        vms.remove(0);
      }
      MasterController.sleepForMs(10000);
      StopStartVMs.stopVM(vmInfo, "nice_exit");
      StopStartVMs.startVM(vmInfo);
      Log.getLogWriter().info("Accessor VMs size: " + vms.size());
    }

    SQLRollingUpgradeBB.getBB().getSharedCounters()
        .increment(SQLRollingUpgradeBB.accessorUpgradeComplete);
    SQLRollingUpgradeBB.getBB().getSharedCounters().zero(SQLRollingUpgradeBB.recycling);
    SQLRollingUpgradeBB.getBB().getSharedCounters().zero(SQLRollingUpgradeBB.accessorUpgradeWaiting);
  }

  protected List<ClientVmInfo> stopStartVMs(int numToKill) {
    return stopStartVMs(numToKill, cycleVMTarget);
  }

  @SuppressWarnings("unchecked")
  protected List <ClientVmInfo> stopStartVMs(int numToKill, String target) {
    Object[] tmpArr = StopStartVMs.getOtherVMs(numToKill, target);
    // get the VMs to stop; vmList and stopModeList are parallel lists
    
    Object vm1 = SQLBB.getBB().getSharedMap().get("asyncDBTarget1");
    Object vm2 = SQLBB.getBB().getSharedMap().get("asyncDBTarget2");
    List <ClientVmInfo> vmList;
    List<String> stopModeList;
    
    if (vm1 == null && vm2 == null) {      
      vmList = (List<ClientVmInfo>)(tmpArr[0]);
      stopModeList = (List<String>)(tmpArr[1]);
      for (ClientVmInfo client : vmList) {
        PRObserver.initialize(client.getVmid());
      } //clear bb info for the vms to be stopped/started
    } else {
      vmList = (List<ClientVmInfo>)(tmpArr[0]);
      stopModeList = (List<String>)(tmpArr[1]);
      for (int i=0; i<vmList.size(); i++) {
        if (vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm1).getVmid().intValue()
            || vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm2).getVmid().intValue()) {
          Log.getLogWriter().info("remove the vm " + vmList.get(i).getVmid() + " from the stop list");
          vmList.remove(i);
        } else PRObserver.initialize(vmList.get(i).getVmid());
      }//clear bb info for the vms to be stopped/started
    } //with DBSynchronizer case

    if (vmList.size() != 0) StopStartVMs.stopStartVMs(vmList, stopModeList);
    return vmList;
  }



  public static void HydraTask_cycleAsynchDBVm() {
    sqlTest.cycleAsynchDBVm();
  }

  //only bring down one vm as there are two listeners installed in the test at a time
  //only one thread should perform this task
  protected void cycleAsynchDBVm() {
    //int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
    int sleepMS = 20000;
    Log.getLogWriter().info("allow  " + sleepMS/1000 + " seconds before killing others");
    MasterController.sleepForMs(sleepMS);

    ClientVmInfo target = random.nextBoolean() ?
        (ClientVmInfo) SQLBB.getBB().getSharedMap().get("asyncDBTarget1"):
        (ClientVmInfo) SQLBB.getBB().getSharedMap().get("asyncDBTarget2");
    String stopMode = TestConfig.tab().stringAt(StopStartPrms.stopModes);
    // get the VMs to stop; vmList and stopModeList are parallel lists
    List <ClientVmInfo> vmList = new ArrayList <ClientVmInfo>();
    vmList.add(target);
    List<String> stopModeList = new ArrayList<String>();
    stopModeList.add(stopMode);

    cycleAsynchDBVm(vmList, stopModeList);
    /* no PR on sgDBSync
    Log.getLogWriter().info("Total number of PR is " + numOfPRs);
    PRObserver.waitForRebalRecov(vmList, 1, numOfPRs, null, null, false);
    */
  }

  protected void cycleAsynchDBVm(List<ClientVmInfo> vmList, List<String>stopModeList) {
    for (ClientVmInfo client : vmList) {
      PRObserver.initialize(client.getVmid());
    } //clear bb info for the vms to be stopped/started
    StopStartVMs.stopStartVMs(vmList, stopModeList);
  }
  
  //this should be run after all vms executed FabricServer.stop() call
  //
  @SuppressWarnings("unchecked")
  public static void HydraTask_bounceAllVMs() {
    List vmInfoList = StopStartVMs.getAllVMs();
    
    List<String> stopModeList = new ArrayList<String>();
    for (int i=0; i<vmInfoList.size(); i++) stopModeList.add("MEAN_EXIT"); 

    if (vmInfoList.size() != 0) StopStartVMs.stopStartVMs(vmInfoList, stopModeList);
    
  }

  public static void HydraTask_bounceAllVMsExcludeLocators() {
    bounceAllVMsExcludeTargets("locator");
  }
  
  public static void HydraTask_bounceAllVMsExcludeClients() {
    bounceAllVMsExcludeTargets("client");
  }
  
  @SuppressWarnings("unchecked")
  protected static void bounceAllVMsExcludeTargets(String targets) {
    List<ClientVmInfo> vmInfoList = StopStartVMs.getAllVMs();
    Stack<Integer> excludedVMs = new Stack<Integer>();  
    
    int index =0;    
    for (ClientVmInfo info: vmInfoList) {
      if (info.getClientName().indexOf(targets) >= 0) {
        excludedVMs.push(index);
      }
      index++;
    }
    
    while (excludedVMs.size() > 0) {
      index = excludedVMs.pop();
      Log.getLogWriter().info("removing from vmInfoList: " + vmInfoList.get(index).getClientName());
      vmInfoList.remove(index); //exclude the not targeted vms
    }
    
    List<String> stopModeList = new ArrayList<String>();
    for (int i=0; i<vmInfoList.size(); i++) {
      Log.getLogWriter().info(vmInfoList.get(i).getClientName());
      stopModeList.add("MEAN_EXIT"); 
    }
    
    if (vmInfoList.size() != 0) StopStartVMs.stopStartVMs(vmInfoList, stopModeList);
  }



  //only being used to determine derby behavior of create same index using different index name
  public static void HydraTask_doDerbyIndexOp() {
    sqlTest.doDerbyIndexOp();
  }

  protected void doDerbyIndexOp() {
    if (hasDerbyServer) {
      Connection dConn = getDiscConnection();
      IndexDDLStmt.doDerbyIndexOp(dConn);
    }
  }

  //create unique index on security table
  public static void HydraTask_createUniqIndex() {
    sqlTest.createUniqIndex();
  }

  protected void createUniqIndex() {
    Connection gConn = getGFEConnection();
    if (testUniqIndex && hasCompanies) addSecuritiesUniqConstraint(gConn); //companies refers to securites unique constraint
    IndexDDLStmt.createUniqIndex(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  //to create function used in procedure
  public static void HydraTask_createFuncForProcedures() {
    sqlTest.createFuncForProcedures();
  }

  protected void createFuncForProcedures() {
    Log.getLogWriter().info("performing create function multiply Op, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    
    Connection  gConn= getHdfsQueryConnection();

    try {
      FunctionDDLStmt.createFuncMultiply(dConn);
      FunctionDDLStmt.createFuncMultiply(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    if (dConn!= null) {
      commit(dConn);
      closeDiscConnection(dConn);
    }

    commit(gConn);
    closeGFEConnection(gConn);
  }

//to create function used in procedure
  public static void HydraTask_createFuncForSubquery() {
    sqlTest.createFuncForSubquery();
  }

  protected void createFuncForSubquery() {
    Log.getLogWriter().info("performing create function maxCid Op, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getHdfsQueryConnection();

    try {
      FunctionDDLStmt.createFuncMaxCid(dConn);
      FunctionDDLStmt.createFuncMaxCid(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    if (dConn!= null) {
      commit(dConn);
      closeDiscConnection(dConn);
    }

    commit(gConn);
    closeGFEConnection(gConn);
  }

  //to create procedures on both derby and gemfirexd/gfe
  public static void HydraTask_createProcedures() {
    sqlTest.createProcedures();
  }

  protected void createProcedures() {
    Log.getLogWriter().info("performing create procedure Op, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    createProcedures(dConn, gConn);
    
    if (dConn!= null) {
      closeDiscConnection(dConn);
    }
    closeGFEConnection(gConn);
  }
  
  
  public static void HydraTask_createJSONProcedures() {
    sqlTest.createJSONProcedures();
  }
  
  protected void createJSONProcedures() {
    Log.getLogWriter().info("performing create procedure Op, myTid is " + getMyTid());
    sql.ddlStatements.ProcedureDDLStmt procStmt = new sql.ddlStatements.ProcedureDDLStmt();
    Connection gConn = getGFEConnection();
    procStmt.createJSONDDL(gConn);    
    closeGFEConnection(gConn);
  }
  
  protected void createProcedures(Connection dConn, Connection gConn) {
    sql.ddlStatements.ProcedureDDLStmt procStmt = new sql.ddlStatements.ProcedureDDLStmt();
    procStmt.createDDLs(dConn, gConn);

    commit(dConn);
    commit(gConn);
  }

  //call procedures -- some may verify query results, some may update sql data
  public static void HydraTask_callProcedures() {
    
    sqlTest.callProcedures();
  }

  
  public static void HydraTask_callJSONProcedures() {
    
    sqlTest.callJSONProcedures();
  }
  
  protected void callJSONProcedures() {
    Log.getLogWriter().info("call procedures, myTid is " + getMyTid());   
    Connection gConn = getGFEConnection();
    callProcedures( gConn);
    closeGFEConnection(gConn);
  }
  
  //get results set using CallableStatmenet
  protected void callProcedures() {
    Log.getLogWriter().info("call procedures, myTid is " + getMyTid());
    Connection dConn = null;
    if (hasDerbyServer){
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    callProcedures(dConn, gConn);

    if (hasDerbyServer){
      closeDiscConnection(dConn);
    }
    closeGFEConnection(gConn);
  }
  
  
  protected void callProcedures(Connection gConn) {
    try{
      Procedures.callJsonProcedure(gConn,getMyTid());
    commit(gConn);
    }catch (SQLException se){
      throw new TestException(TestHelper.getStackTrace(se));
    }
  }

  
  protected void callProcedures(Connection dConn, Connection gConn) {
    Procedures.callProcedures(dConn, gConn);
    commit(dConn);
    commit(gConn);
  }

  public static void HydraTask_verifyFuncSubquery() {
    sqlTest.verifyFuncSubquery();
  }

  protected void verifyFuncSubquery() {
  //String subquery = "select * from trade.customers c where c.cid = (select max(cid) from trade.networth where tid = ?)";
  String subquery = "select * from trade.customers c where c.cid = trade.maxCid(" + getMyTid() + " )" ;
  Log.getLogWriter().info("verifying query results using function in subquery, myTid is " + getMyTid());
    Connection dConn =null;
    ResultSet derbyRS =null;
  if (hasDerbyServer) dConn = getDiscConnection();
    Connection gConn = getGFEConnection();
    try {
      if (dConn!=null) {
      Log.getLogWriter().info("in Derby, executing " + subquery);
        PreparedStatement psDerby = dConn.prepareStatement(subquery);
        //psDerby.setInt(1, getMyTid());
        psDerby.execute();
        derbyRS = psDerby.getResultSet();
        Log.getLogWriter().info("Got result set from derby");
        /* to check if derby indeed get the correct result*/
        /*
        if (derbyRS.next()) {
          Log.getLogWriter().info("cid is " + derbyRS.getInt("CID"));
        }
        */
      }
      Log.getLogWriter().info("in gemfirexd, executing " + subquery);
      PreparedStatement psGFE = gConn.prepareCall(subquery);
      //psGFE.setInt(1, getMyTid());
      psGFE.execute();
      ResultSet gfeRS = psGFE.getResultSet();
      Log.getLogWriter().info("Got result set from gemfirexd");

      boolean success = false;
      if (dConn != null)  {
        success = verifyResultSets(derbyRS, gfeRS);
        while (!success) {
          Log.getLogWriter().info("Not able to verify subquery (with function) results");
          success = verifyResultSets(derbyRS, gfeRS);
        }
      }
      SQLHelper.closeResultSet(gfeRS, gConn);
      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    commit(dConn);
    commit(gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  //to verify query results using procedures
  public static void HydraTask_verifyProcedures() {
    sqlTest.verifyProcedures();
  }

  //get results set using CallableStatmenet
  protected void verifyProcedures() {
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verify of query results through "
          + "created procedure call due to manageDerbyServer as false, myTid="
          + getMyTid());
      cleanConnection(getGFEConnection());
      return;
    }
    Log.getLogWriter().info("verifying query results through created procedure call, myTid is " + getMyTid());
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();

    verifyProcedures(dConn, gConn);

    commit(dConn);
    commit(gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void verifyProcedures(Connection dConn, Connection gConn) {
    try {
      /* test if the procedure exists in the database
      ResultSet rs = dConn.getMetaData().getProcedures(null, "trade", "show_customers");
      if (rs != null) {
        Log.getLogWriter().info("show_customer is not null");
      } else {
        Log.getLogWriter().info("show_customer is null");
      }
      */

      CallableStatement csDerby = dConn.prepareCall("{call trade.show_customers(?)}");
      csDerby.setInt(1, getMyTid());
      csDerby.execute();

      ResultSet derbyRS = csDerby.getResultSet();
      Log.getLogWriter().info("Got result set from derby");


      CallableStatement csGFE = gConn.prepareCall("{call trade.show_customers(?)}");
      csGFE.setInt(1, getMyTid());
      csGFE.execute();

      ResultSet gfeRS = csGFE.getResultSet();
      Log.getLogWriter().info("Got result set from gemfirexd");

      if (gfeRS == null) Log.getLogWriter().info("Gfe result is null");
      if (derbyRS == null) throw new TestException(" Derby result is null");

      boolean success = verifyResultSets(derbyRS, gfeRS);
      while (!success) {
        Log.getLogWriter().info("Not able to verify procedure results");
        success = verifyResultSets(derbyRS, gfeRS);
      }
      SQLHelper.closeResultSet(gfeRS, gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  //initialize the map contains column information for each table
  public static void HydraTask_setTableCols() {
    sqlTest.setTableCols();
  }

  //can be called only after table has been created
  protected void setTableCols() {
    Connection gConn = getGFEConnection();
    setTableCols(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  //uses the gfe connection
  public static synchronized void setTableCols(Connection conn) {
    if (tableColsSet) {
      Log.getLogWriter().info("table columns are already set");
      return;
    }
    try {
      for (int i=0; i<tables.length; i++) {
        if (tables[i].toLowerCase().contains(portfoliov1)) continue;
        
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from " + tables[i]);

        //get result set metadata
        ResultSetMetaData rsmd = rs.getMetaData();
        int numCols = rsmd.getColumnCount();
        List<String> colNames = new ArrayList<String>();
        rs.close();

        Log.getLogWriter().info("setting columnNames for table " + tables[i]);
        for (int j=0; j<numCols; j++) {
          if (tables[i].contains("companies") 
              && (rsmd.getColumnName(j+1).equalsIgnoreCase("histprice")
                  || rsmd.getColumnName(j+1).equalsIgnoreCase("uuid")
                  || rsmd.getColumnName(j+1).equalsIgnoreCase("companyinfo"))) continue; //UDT type could not be indexed
          
          String columnName = rsmd.getColumnName(j+1);
          colNames.add(columnName); //get each column names
          Log.getLogWriter().info("colNames[" + j +"] is " + columnName);
        } //starts from column 1
        tableCols.put(tables[i], colNames); //put into the map
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    SQLBB.getBB().getSharedMap().put("tableCols", tableCols); //put it into the sharedMap
    Log.getLogWriter().info("sets up table columns information for index creation");
    tableColsSet = true;
  }
  
  @SuppressWarnings("unchecked")
  protected static void setTableCols(Connection conn, String tableName) throws SQLException {
    if (!tableColsSet) throw new TestException("Test issue, needs to run setTableCols(Connection) first ");
    tableCols = (HashMap<String, List<String>>) SQLBB.getBB().getSharedMap().get("tableCols"); 
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("select * from " + tableName);

    //get result set metadata
    ResultSetMetaData rsmd = rs.getMetaData();
    int numCols = rsmd.getColumnCount();
    List<String> colNames = new ArrayList<String>();
    rs.close();

    Log.getLogWriter().info("setting columnNames for table " + tableName);
    for (int j=0; j<numCols; j++) {
      if (tableName.contains("companies") 
          && (rsmd.getColumnName(j+1).equalsIgnoreCase("histprice")
              || rsmd.getColumnName(j+1).equalsIgnoreCase("uuid")
              || rsmd.getColumnName(j+1).equalsIgnoreCase("companyinfo"))) continue; //UDT type could not be indexed
      
      String columnName = rsmd.getColumnName(j+1);
      colNames.add(columnName); //get each column names
      Log.getLogWriter().info("colNames[" + j +"] is " + columnName);
    } //starts from column 1
    tableCols.put(tableName, colNames); //put into the map
    SQLBB.getBB().getSharedMap().put("tableCols", tableCols); //put it into the sharedMap
    Log.getLogWriter().info("sets up table columns information for index creation");
  }

  @SuppressWarnings("unchecked")
  protected String[] getGFEDDLPartition() {
    Log.getLogWriter().info("in SQLTest.getGFEDDLPartition()");
    String[] tables = SQLPrms.getCreateTablesStatements(false);
    Vector<String> statements = TestConfig.tab().
      vecAt(SQLPrms.gfeDDLExtension, new HydraVector());
    Vector<String> redundancyClause = TestConfig.tab().
      vecAt(SQLPrms.redundancyClause, new HydraVector());
    if (statements.size() == 0)
      return tables;
    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      if(SQLPrms.isSnappyMode() && !statements.elementAt(i).contains("USING ROW")) {
        throw new TestException("GFXD syntax for create table is not supported, please use snappy" +
            " syntax");
      }
      if (testUniqIndex) {
        tables[0] = "create table trade.securities (sec_id int not null, " +
                        "symbol varchar(10) not null, price decimal (30, 20), " +
                        "exchange varchar(10) not null, tid int, " +
                        "constraint sec_pk primary key (sec_id), " +
                        "constraint exc_ch check (exchange in " +
                        "('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))";
      }
      
      String currentTable = tables[i].substring(0,tables[i].indexOf("(")).toLowerCase();
      if (SQLTest.hasJSON &&
          ( currentTable.contains("trade.securities")
              || currentTable.contains("trade.buyorders")
              || currentTable.contains("trade.networth")
              || currentTable.contains("trade.customers") )) {
      tables[i]=tables[i].trim().substring(0,tables[i].trim().length() - 1);
      //customer table is used for multiple json objects in table testing and json with array and json with-in json testing
      if ( currentTable.contains("trade.customers"))
            tables[i]+=" , json_details json , networth_json json , buyorder_json json )";
      else
            tables[i]+=" , json_details json )";
      }
       
      // table[0] is securities table and remove the sec_uq to test the sec_uq
      // when alter table is ready, this can be conver to alter table remove constraint
      String tableName = statements.elementAt(i);
      String partition = getTablePartition(tableName);
      String redundClause = "";
      if (redundancyClause.size()>0) {
        redundClause = getRedundancyClause(partition, redundancyClause.elementAt(i));
        //following setting is used for gfxd tx with batching test case
        //assume all tables will be using redundancy
        SQLBB.getBB().getSharedMap().put(hasRedundancy, true);
      }
      else {
        SQLBB.getBB().getSharedMap().put(hasRedundancy, false);
      }


/*
      if (hasDerbyServer && populateThruLoader && i==5) {
        strArr[i] += " LOADER (sql.loader.BuyOrdersLoader.createRowLoader) ";
      } // table[5] is buyorder and for loader test, this can be removed when loader, writer trigger tests are ready
*/

    //add persistent table clause
      String persistClause = getPersistence(i);
      //persistClause =  /*getLoader(i) + */ getPersistence(i) ;
      String evictionClause =
        (useHeapPercentage? 
            (alterTableDropColumn? getEvictionHeapPercentageOverflowForAlterTable() : getEvictionHeapPercentageOverflow() )
            : getEvictionOverflow(tableName));  //loader is being taken out from the ddl

      if (SQLPrms.isSnappyMode()) {
        if(partition.trim().length()>0 && redundClause.trim().length()>0)
          partition = partition + ", " ;
        partition = partition + redundClause;
        if (partition.trim().length()>0 && persistClause.trim().length()>0)
          partition = partition + ", ";
        partition = partition + persistClause;
        if (partition.trim().length()>0 && evictionClause.trim().length()>0)
          partition = partition + ", ";
        partition = partition + evictionClause;
        partition = tableName.replace
            (tableName.substring(tableName.indexOf("(") + 1, tableName.indexOf(")")), partition);
      }
      else
        partition = partition + " " + redundClause + " " + persistClause + " " + evictionClause;
      strArr[i] = tables[i] + " " + partition;
    }

    return strArr;
  }

  @SuppressWarnings("unchecked")
  private static String getPersistence(int i) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.gfePersistExtension, new HydraVector());
    if (statements.size() == 0)
      return " ";
    else if (i>statements.size())
      throw new util.TestException("incorrect persist tables in the test config");

    else
      return (String)statements.elementAt(i);

  }
  
  public static String getEvictionOverflow(String tableName) {
    String eviction = getEvictionOverflow();
    String megabyte = "";
    if (eviction.contains(LRUMEMSIZE)) {
      for (int i = 0; i < eviction.length(); i++) {
        Character chars = eviction.charAt(i);
        if (Character.isDigit(chars)) {
          megabyte += chars;
        }
      }
    }

    if (megabyte.length() > 0)
      SQLBB.getBB().getSharedMap().put(tableName + LRUMEMSIZE,
          Integer.parseInt(megabyte));
    // to be used to verify eviction occurs during populating table.

    return eviction;
  }
  
  public static String getEvictionOverflow() {
    if (!testEviction) return " ";
    else {
      StringBuilder sb = new StringBuilder();
      int a = random.nextInt(2);
      switch (a) {
      case 0: 
        int maxMb = 2;
        sb.append( " eviction by LRUMEMSIZE " + (random.nextInt(maxMb) + 1) + " ");
        break;
      case 1:
        sb.append(" eviction by LRUCOUNT " + (random.nextInt(100) + 500 ) + " ");
        break;
      default: throw new TestException ("this should not happen");
      }
      sb.append("evictaction overflow " + 
          getSynchronous() +
          (useDefaultDiskStoreForOverflow? "":"'OverflowDiskStore'"));
      return sb.toString();
    }
  }
  
  public static String getEvictionHeapPercentageOverflow() {
    if (!testEviction) return " ";
    else {
      StringBuilder sb = new StringBuilder();
      sb.append(" eviction by LRUHEAPPERCENT ");

      sb.append("evictaction overflow " + 
          getSynchronous() +
          (useDefaultDiskStoreForOverflow? "":"'OverflowDiskStore'"));
      return sb.toString();
    }
  }
  
  public static String getEvictionHeapPercentageOverflowForAlterTable() {
    if (!testEviction) return " ";
    else {
      StringBuilder sb = new StringBuilder();
      sb.append(" eviction by LRUHEAPPERCENT ");

      sb.append("evictaction overflow ");
      return sb.toString();
    }
  }
  
  public static void HydraTask_setHeapPercentage() {
    if (useHeapPercentage) {
      if (alterTableDropColumn) {
        double percentage = (random.nextInt(5) + 20) * 1.1;
        sqlTest.setHeapPercentage(percentage);
      } else sqlTest.setHeapPercentage();
    } else Log.getLogWriter().info("eviction heap percentage is not set");
  }
  
  protected void setHeapPercentage() {
    if (percentage == 0) percentage = (random.nextInt(5) + initEvictionHeap) * 1.1;
    setHeapPercentage(percentage);
  }
  
  protected void setHeapPercentage(double percentage) {
    Connection gConn = getGFEConnection();
    setHeapPercentage(gConn, percentage, null);
    closeGFEConnection(gConn);
  }
  
  protected void setHeapPercentage(Connection gConn, double percentage, String serverGroup) {
    String sql = "CALL SYS.SET_EVICTION_HEAP_PERCENTAGE_SG(?, ?)";
    setHeapPercentage(gConn, percentage, null, sql);
    if (SQLTest.isOffheap){
      sql = "CALL SYS.SET_EVICTION_OFFHEAP_PERCENTAGE_SG(?, ?)";
      setHeapPercentage(gConn, percentage, null, sql);
    }
  }
  
  protected void setHeapPercentage(Connection gConn, double percentage, String serverGroup, String sql) {
    try {
      CallableStatement cs = gConn.prepareCall(sql);
      Log.getLogWriter().info(sql + " with args " + percentage + ", " + serverGroup);
      cs.setDouble(1, percentage);
      if (serverGroup == null) cs.setNull(2, Types.VARCHAR);
      else cs.setString(2, serverGroup);
      cs.executeUpdate();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_setCriticalHeapPercentage() {
    sqlTest.setCriticalHeapPercentage();
  }
  
  protected void setCriticalHeapPercentage() {
    Connection gConn = getGFEConnection();
    if (setCriticalHeap)
      setCriticalHeapPercentage(gConn);
    else
      Log.getLogWriter().info("No critical heap is set");
    closeGFEConnection(gConn);
  }
  
  protected void setCriticalHeapPercentage(Connection gConn) {
    double percentage = (random.nextInt(5) + (initEvictionHeap + 10)) * 1.05;
    setCriticalHeapPercentage(gConn, percentage, null);
  }
  
  protected void setCriticalHeapPercentage(Connection gConn, double percentage, 
      String serverGroup) {
    String sql = "CALL SYS.SET_CRITICAL_HEAP_PERCENTAGE_SG(?, ?)";
    setCriticalHeapPercentage(gConn, percentage, serverGroup, sql); //should test both on heap and offheap setting in cluster
    if (SQLTest.isOffheap) {
      sql = "CALL SYS.SET_CRITICAL_OFFHEAP_PERCENTAGE_SG(?, ?)";
      setCriticalHeapPercentage(gConn, percentage, serverGroup, sql);
    }
    
  }
  
  protected void setCriticalHeapPercentage(Connection gConn, double percentage, 
      String serverGroup, String sql) {
    try { 
      Log.getLogWriter().info(sql + " with args " + percentage + ", " + serverGroup);
      CallableStatement cs = gConn.prepareCall(sql);
      cs.setDouble(1, percentage);
      if (serverGroup == null) cs.setNull(2, Types.VARCHAR);
      else cs.setString(2, serverGroup);
      cs.executeUpdate();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected Connection getConnectionSetTrace() throws SQLException {
    if (testSecurity) {
      if (!isEdge) return GFEDBManager.getSuperUserConnection();
      else return GFEDBClientManager.getSuperUserConnection();
    } else return getGFEConnection();
  }
  
  private static String getSynchronous() {
    return random.nextBoolean()? "asynchronous " : "synchronous " ;
  }

  @SuppressWarnings("unchecked")
  private static String getLoader(int i) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.loaderDDLExtension, new HydraVector());
    if (statements.size() == 0)
      return " ";
    else if (i>statements.size())
      throw new util.TestException("incorrect loader setting in the test config");

    else
      return (String)statements.elementAt(i);
  }

  protected String getTablePartition(String tableName) {
    Log.getLogWriter().info("tableName is " + tableName);
    return PartitionClause.getPartitionClause(tableName);
  }

  protected String getRedundancyClause(String tablePartition, String redundancyClause) {
    if (tablePartition.contains("replicate") || redundancyClause == null)
      return " "; //do not apply redundancy
    else
      return redundancyClause;
  }

  public static void HydraTask_addLoader() {
    sqlTest.addLoader();
  }
  
  protected void addLoader() {
    if (!populateThruLoader && !testLoaderCreateRandomRow) {
      Log.getLogWriter().info("no loader is attached");
      return;
    }
    Connection conn = getGFEConnection();
    //TODO to add multiple loaders for more tables
    /*
    String[] tables = SQLPrms.getCreateTablesStatements();

    String[] strArr = new String[tables.length];
    for (int i = 0; i < tables.length; i++) {
      //add loader for suitable tables
    }
    */
    String schemaName = "trade";
    String tableName = "buyorders";
    String loader = "sql.loader.BuyOrdersLoader";
    String initStr = testLoaderCreateRandomRow? "createRandomRow": null;
    try {
      addLoader(conn, schemaName, tableName, loader, initStr);
      commit(conn); //no effect as ddl is autocommit
    
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void addLoader(Connection conn, String schemaName, String tableName,
      String functionStr, String initInfoStr) throws SQLException {
    CallableStatement cs = conn
        .prepareCall("CALL SYS.ATTACH_LOADER(?,?,?,?)");
    Log.getLogWriter().info("CALL SYS.ATTACH_LOADER(" + schemaName + ", "
        + tableName + ", " + functionStr + ", " + initInfoStr + ")");
    cs.setString(1, schemaName);
    cs.setString(2, tableName);
    cs.setString(3, functionStr);
    cs.setString(4, initInfoStr);
    cs.execute();
  }
  
  public static void HydraTask_populateThruLoader() {
    if (setTx) {
      sqlTest.populateThruLoader(); 
      return;
    }
    
    int number = (int)SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.populateThruLoader);
    int oneOutOfHowMany = 6; //about one out of how many
    if (number == 1 || random.nextInt(oneOutOfHowMany)== 1)
      sqlTest.populateThruLoader();
  }

  //populate if Loader is set and no inserts to gfxd
  protected void populateThruLoader() {
    //allow 
    Connection gConn = getGFEConnection();
    populateThruLoader(gConn);
    
    //TODO commit needed for setTx?
    if (setTx) {
      commit(gConn);
    }
  }
  
  protected void populateThruLoader(Connection gConn) {
    if (!populateThruLoader) {
      Log.getLogWriter().info("data were inserted instead of being loaded");
      return;
    }
    try {
      int maxOidInserted = (int)SQLBB.getBB().getSharedCounters().read(SQLBB.tradeBuyOrdersPrimary);
      String get = "select * from trade.buyorders where oid =? ";
      PreparedStatement ps = gConn.prepareStatement(get);
      if (!setTx) {
        for (int i=-1; i<= maxOidInserted+1; i++) {    //add coverage on non existing oid
          loadThruGet(ps, i);
        }
      } else {
        ResultSet rs = getDiscConnection().createStatement().executeQuery("select oid from trade.buyorders where tid = " + getMyTid());
        List<Struct> oidlist = ResultSetHelper.asList(rs, true);
        for (Struct oid: oidlist) 
          loadThruGet(ps, (Integer) oid.get("OID"));
      }    
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("42X05")) {
      Log.getLogWriter().info("buyorders does not exists in this test");
      return; //does not have buyorder tables.
      }
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void loadThruGet(PreparedStatement ps, int i) throws SQLException{
    ResultSet rs;
    ps.setInt(1, i);
    rs = ps.executeQuery();
    if (testPartitionBy){
      List<com.gemstone.gemfire.cache.query.Struct> resultList = ResultSetHelper.asList(rs, false);
      if (resultList.size()>0) {
        if (i<1) {
          Log.getLogWriter().info("row being loaded is " +
              ResultSetHelper.listToString(resultList));
          throw new TestException("loader loaded a non existing row from the back_end database: "
              + ResultSetHelper.listToString(resultList));
        } else {
          Log.getLogWriter().info("data have been loaded for oid: " + i);
        }
      } else {
        Log.getLogWriter().info("data may not have been loaded for oid: " + i);
      }
    }
    rs.close();
  }

   
  
  public static void HydraTask_createRandomRowsThruLoader() {
    sqlTest.createRandomRowsThruLoader();
  }
  protected void createRandomRowsThruLoader() {
    if (!testLoaderCreateRandomRow) {
      Log.getLogWriter().info("not a load test");
      return;
    }
    try {
      Connection gConn = getGFEConnection();
      int maxOidInserted = (int)SQLBB.getBB().getSharedCounters().read(SQLBB.tradeBuyOrdersPrimary);
      int numOfKeys = 100;
      int newMaxOid = (int)SQLBB.getBB().getSharedCounters().add(SQLBB.tradeBuyOrdersPrimary, numOfKeys);
      String get = "select * from trade.buyorders where oid =? ";
      PreparedStatement ps = gConn.prepareStatement(get);
      ResultSet rs;
      for (int i=maxOidInserted; i<= newMaxOid+1; i++) {
      ps.setInt(1, i);
      rs = ps.executeQuery();
      rs.close();
      }
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("42X05")) {
      Log.getLogWriter().info("buyorders does not exists in this test");
      return; //does not have buyorder tables.
      }
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_shutDownDB() {
  sqlTest.shutDownDB();
  }

  protected void shutDownDB() {
  if (hasDerbyServer && !hasPersistentTables) {
    ClientDiscDBManager.shutDownDB();
  }
  GFEDBManager.shutDownDB();
  }

  public static void HydraTask_shutDownAllFabricServers(){
    sqlTest.shutDownAllFabricServers();
  }

 protected void shutDownAllFabricServers() {
   Log.getLogWriter().info("shuting down all FabricServers");
   FabricServerHelper.shutDownAllFabricServers(300);

   // wait for shutdown
   while (true) {
     if (!FabricServerHelper.isFabricServerStopped()) {
       Log.getLogWriter().info("Waiting for " + 5 + " sec as for shutdown of FabricServer");
       MasterController.sleepForMs(5000);
     } else {
       Log.getLogWriter().info("Completed shutdown of FabricServer...");
       return;
     }
   }
  }
  
  //only used in serial testing in populating table in non-tx test, when non uniq keys
  //are used in the test with derby database, need to set up the number of workers
  protected void waitForBarrier() {
    AnyCyclicBarrier barrier = AnyCyclicBarrier.lookup(numOfWorkers, "barrier");
    Log.getLogWriter().info("Waiting for " + numOfWorkers + " to meet at barrier");
    barrier.await();
  }

  protected void createFuncMonth(){
    Connection conn = getGFEConnection();
    createFuncMonth(conn);
  }
  
  protected void createFuncMonth(Connection conn){
    String sql = "create function month(DP1 Date) " + // comment out (30, 20)
    "RETURNS INTEGER " + //comment out scale and precision (30, 20) to reproduce the bug
    "PARAMETER STYLE JAVA " +
    "LANGUAGE JAVA " +
    "NO SQL " +
    "EXTERNAL NAME 'sql.FunctionTest.month'";

    try {
      Statement stmt = conn.createStatement();
      stmt.executeUpdate(sql);
    } catch (SQLException se) {
      if (multiThreadsCreateTables && se.getSQLState().equalsIgnoreCase("X0Y68"))
        Log.getLogWriter().info("Got the expected exception, continuing tests");
      else
        SQLHelper.handleSQLException(se);
    }
    Log.getLogWriter().info("created function month for partition by expression case");
  }

  /**
   * return all application tables of schema name, table name pair
   * @param conn Connection used to find existing tables
   * @return ArrayList of all tables with two elements of String[] -- schema name and table name
   */
  protected ArrayList<String[]> getTableNames(Connection conn) {
    ArrayList<String[]> tables = new ArrayList<String[]>();
    ResultSet rs = null;
    try {
      rs = conn.createStatement().executeQuery("select tableschemaname, tablename "
                  + "from sys.systables where tabletype = 'T' and tableschemaname != '"
                  + GfxdConstants.PLAN_SCHEMA + "'"
                  + (SQLPrms.isSnappyMode()?" and tableschemaname != 'SNAPPY_HIVE_METASTORE'":""));
      while (rs.next()) {
        String[] str = new String[2];
        str[0] = rs.getString(1);
        str[1] = rs.getString(2);
        tables.add(str);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return tables;
  }
  
  protected void compareResults(Connection gConn, String query1, String query2, String table1 , String table2) throws TestException {
    List<Struct> list1 = getResultSetTest(gConn, query1);
    List<Struct> list2 = getResultSetTest(gConn, query2);
    ResultSetHelper.compareResultSets(list1, list2, table1 , table2);
  }
  
  /**
   * return all application tables of schema name, table name pair
   * @param conn Connection used to find existing tables
   * @param query Query string
   * @return ArrayList of all tables with two elements of String[] -- schema name and table name
   */
  protected ArrayList<String[]> getTableNames(Connection conn, String query) {
    ArrayList<String[]> tables = new ArrayList<String[]>();
    ResultSet rs = null;
    try {
      rs = conn.createStatement().executeQuery(query);
      while (rs.next()) {
        String[] str = new String[2];
        str[0] = rs.getString(1);
        str[1] = rs.getString(2);
        tables.add(str);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return tables;
  }
  
  protected static hydra.blackboard.SharedLock lock;
  //used in wan tests, when 
  protected void getLock() {
    if (lock == null) 
      lock = SQLBB.getBB().getSharedLock();
    lock.lock();    
  }
  
  protected void releaseLock() {
    lock.unlock();
  }
 
  public static void HydraTask_runSQLScript() {
    if (sqlTest == null) sqlTest = new SQLTest();
    sqlTest.runSQLScript(true);
  }
  public static void HydraTask_runSQLScriptContinueOnError() {
    sqlTest.runSQLScript(false);
  }

  protected void runSQLScript(boolean failOnError) {
    String sqlFilePath = SQLPrms.getSqlFilePath();
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
      Log.getLogWriter().info("running sql script " + sqlFilePath + " on derby");
      SQLHelper.runSQLScript(dConn, sqlFilePath, failOnError);
    }
    Connection gConn = getGFEConnection();
    Log.getLogWriter().info("running sql script " + sqlFilePath + " on gfe");
    SQLHelper.runSQLScript(gConn, sqlFilePath, failOnError);
  }

  protected Connection getConnection(DBType dbType){		
	  switch(dbType){
	    case DERBY: 	  
	    	return getDiscConnection();
	    case GFXD:
	    	return getGFEConnection();
		default: 
			throw new IllegalArgumentException("Invalid DB Type " + dbType);
	  }
  }
  
  protected void closeConnection(Connection conn, DBType dbType){
	  switch(dbType){
	    case DERBY:
	    	closeDiscConnection(conn);
	    	break;
	    case GFXD:
	    	closeGFEConnection(conn);
	    	break;
	  }
  }
  
  public static void HydraTask_createMaxTimestamp() {
    sqlTest.createMaxTimestamp();
  }

  protected void createMaxTimestamp() {
    Connection gConn = getGFEConnection();
    try {
      String createTableStmt = "create table trade.sample (id int not null constraint sample_pk primary key, " +
          "stime timestamp, num int)";
      if (SQLTest.isOffheap) {
        createTableStmt = createTableStmt + OFFHEAPCLAUSE;
      }
      gConn.createStatement().execute(createTableStmt);
      Statement stmt = gConn.createStatement();
      String createIndexStmt = "create index sampleIndex on trade.sample (stime)";
      String createIndexNumStmt = "create index sampleNumIndex on trade.sample (num)";
      stmt.executeUpdate(createIndexStmt);
      stmt.executeUpdate(createIndexNumStmt);
      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } 
  }
  
  public static void HydraTask_insertMaxTimestamp() {
    sqlTest.insertMaxTimestamp();
  }

  protected void insertMaxTimestamp() {
    String insert = "insert into trade.sample values (?,?,?)";
    Connection gConn = getGFEConnection();
    try {
      PreparedStatement stmt = gConn.prepareStatement(insert);
      int size = 10000;
      for (int i=0; i< size; i++) {
        int key = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.tradeCustomersPrimary);
        int num = random.nextInt();
        java.sql.Timestamp time = new Timestamp(System.currentTimeMillis());
        stmt.setInt(1, key);
        stmt.setTimestamp(2, time);
        stmt.setInt(3, num);
        stmt.executeUpdate();
      }
   
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_queryMaxTimestamp() {
    sqlTest.queryMaxTimestamp();
  }

  protected void queryMaxTimestamp() {
  	String query;
  	Connection gConn = getGFEConnection();
    
    query = "select max(num) from trade.sample";    
    try {
    	Log.getLogWriter().info(query);
      PreparedStatement stmt = gConn.prepareStatement(query);
      ResultSet rs =   stmt.executeQuery();
      if (rs.next()) {
        int num = rs.getInt(1);
        Log.getLogWriter().info("max num is " + num);
      }      
      else {
      	ResultSetHelper.sendBucketDumpMessage();
      	throw new TestException(query + " after index created fails to get data");
      }
      rs.close();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    query = "select max(stime) from trade.sample";
    try {
    	Log.getLogWriter().info(query);
      PreparedStatement stmt = gConn.prepareStatement(query);
      ResultSet rs =   stmt.executeQuery();
      if (rs.next()) {
      	Timestamp time = rs.getTimestamp(1);
        Log.getLogWriter().info("max stime is " + time);
      } 
      else {
      	throw new TestException(query + " after index created fails to get data");
      }
      rs.close();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    query = "select avg(num) from trade.sample";
    try {
    	Log.getLogWriter().info(query);
      PreparedStatement stmt = gConn.prepareStatement(query);
      ResultSet rs =   stmt.executeQuery();
      if (rs.next()) {
        Object num = rs.getObject(1);
        Log.getLogWriter().info("avg num is " + num);
      }      
      else {
      	ResultSetHelper.sendBucketDumpMessage();
      	throw new TestException(query + " after index created fail to get data");
      }
      rs.close();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
  }
  
  public static void HydraTask_checkFKConstraints() {
    if (!withReplicatedTables) sqlTest.checkFKConstraints();
  }
  
  protected void checkFKConstraints() {
    Connection gConn = getGFEConnection();
    checkFKConstraints(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void checkFKConstraints(Connection gConn) {
    if (!hasNetworth) return;
    
    List<com.gemstone.gemfire.cache.query.Struct> buyorders = null;
    List<com.gemstone.gemfire.cache.query.Struct> sellorders = null;
    List<com.gemstone.gemfire.cache.query.Struct> portfolio = null;
    List<com.gemstone.gemfire.cache.query.Struct> networth = null;
    List<com.gemstone.gemfire.cache.query.Struct> customers = null;
    List<com.gemstone.gemfire.cache.query.Struct> securities = null;
    
    List<Integer>buyordersCid = null;
    List<Integer>buyordersSid = null;
    List<Integer>sellordersCid = null;
    List<Integer>sellordersSid = null;
    List<String>sellordersCidSid = null;
    List<Integer>portfolioCid = null;
    List<Integer>portfolioSid = null;
    List<String>portfolioCidSid = null;
    List<Integer>networthCid = null;
    List<Integer>customersCid = null;
    List<Integer>securitiesSid = null;
        
    try {
      String sql = "select tableschemaname, tablename "
        + "from sys.systables where tabletype = 'T' and tableschemaname not like 'SYS%'";
       ResultSet rs = gConn.createStatement().executeQuery(sql);
       Log.getLogWriter().info(sql);
       List<Struct> list = ResultSetHelper.asList(rs, false);   
       SQLHelper.closeResultSet(rs, gConn);
       Log.getLogWriter().info(ResultSetHelper.listToString(list));
       for (Struct e: list) {
         Object[] table = e.getFieldValues();
         Log.getLogWriter().info("table name is " + table[1]);
         if ("buyorders".equalsIgnoreCase((String)table[1])) 
           buyorders = getResultSet(gConn, (String)table[0], (String)table[1]);
         else if ("sellorders".equalsIgnoreCase((String)table[1])) 
           sellorders = getResultSet(gConn, (String)table[0], (String)table[1]); 
         else if ("portfolio".equalsIgnoreCase((String)table[1])) 
           portfolio = getResultSet(gConn, (String)table[0], (String)table[1]);
         else if ("networth".equalsIgnoreCase((String)table[1]))
           networth = getResultSet(gConn, (String)table[0], (String)table[1]);
         else if ("customers".equalsIgnoreCase((String)table[1])) 
           customers = getResultSet(gConn, (String)table[0], (String)table[1]);
         else if ("securities".equalsIgnoreCase((String)table[1])) 
           securities = getResultSet(gConn, (String)table[0], (String)table[1]);       
       }             
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } 
    
    if (buyorders !=null) {
      buyordersCid = getCidFromRS(buyorders);
      buyordersSid = getSidFromRS(buyorders);
    } 
    if (sellorders !=null) {
      sellordersCid = getCidFromRS(sellorders);
      sellordersSid = getSidFromRS(sellorders);
      sellordersCidSid = getCidSidFromRS(sellorders);
    } 
    if (portfolio !=null) {
      portfolioCid = getCidFromRS(portfolio);
      portfolioSid = getSidFromRS(portfolio);
      portfolioCidSid = getCidSidFromRS(portfolio);
    } 
    
    if (networth !=null) {
      networthCid = getCidFromRS(networth);
    } 

    if (customers !=null) {
      customersCid = getCidFromRS(customers);
    } 
    
    if (securities !=null) {
      securitiesSid = getSecidFromRS(securities);
    } 
    
    StringBuilder str = new StringBuilder();
    Log.getLogWriter().info("verify networth cid\n");
    StringBuilder violation = checkFKConstraint(customersCid, networthCid);
    if (violation.length() != 0) {
      str.append("networth has following cids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify portfolio cid\n");
    violation = checkFKConstraint(customersCid, portfolioCid);
    if (violation.length() != 0) {
      str.append("\nportfolio has following cids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify sellorders cid\n");
    violation = checkFKConstraint(customersCid, sellordersCid);
    if (violation.length() != 0) {
      str.append("\nsellorders has following cids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify buyorders cid\n");
    violation = checkFKConstraint(customersCid, buyordersCid);
    if (violation.length() != 0) {
      str.append("\nbuyorders has following cids which do not have a parent: ");
      str.append(violation);     
    }
    
    Log.getLogWriter().info("verify portfolio sid\n");
    violation = checkFKConstraint(securitiesSid, portfolioSid);
    if (violation.length() != 0) {
      str.append("\nportfolio has following sids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify sellorders sid\n");
    violation = checkFKConstraint(securitiesSid, sellordersSid);
    if (violation.length() != 0) {
      str.append("\nsellorders has following sids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify buyorders sid\n");
    violation = checkFKConstraint(securitiesSid, buyordersSid);
    if (violation.length() != 0) {
      str.append("\nbuyorders has following sids which do not have a parent: ");
      str.append(violation);
    }
    
    Log.getLogWriter().info("verify sellorders cid_sid\n");
    violation = checkCompositeFKConstraint(portfolioCidSid, sellordersCidSid);
    if (violation.length() != 0) {
      str.append("\nsellorders has following cid_sid pair which do not have a parent: ");
      str.append(violation);
    }
    
    if (str.length() != 0) {
      throw new TestException("the foreign key constraint violation detected: " +
      		str.toString());
    }
  }
  
  protected List<com.gemstone.gemfire.cache.query.Struct> getResultSet(Connection gConn,
      String schema, String table) {
    String select = "select * from " + schema + "." + table;
    return getResultSet(gConn, select);    
  }  
  
  protected List<com.gemstone.gemfire.cache.query.Struct> getResultSet(Connection gConn, String sqlQuery) {
    ResultSet rs = null;
    try {      
      Log.getLogWriter().info(sqlQuery);
      rs = gConn.createStatement().executeQuery(sqlQuery);
      return ResultSetHelper.asList(rs, false);      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
      commit(gConn);
    }
    return null;
  }  
  
  protected List<Integer> getCidFromRS(List<com.gemstone.gemfire.cache.query.Struct> alist) {
    List<Integer> cids = new ArrayList<Integer>();
    for (Struct row: alist) {
      cids.add((Integer)row.get("CID"));
    }
    return cids;
  }
  
  protected List<Integer> getSidFromRS(List<com.gemstone.gemfire.cache.query.Struct> alist) {
    List<Integer> cids = new ArrayList<Integer>();
    for (Struct row: alist) {
      cids.add((Integer)row.get("SID"));
    }
    return cids;
  }
  
  protected List<Integer> getSecidFromRS(List<com.gemstone.gemfire.cache.query.Struct> alist) {
    List<Integer> cids = new ArrayList<Integer>();
    for (Struct row: alist) {
      cids.add((Integer)row.get("SEC_ID"));
    }
    return cids;
  }
  
  protected List<String> getCidSidFromRS(List<com.gemstone.gemfire.cache.query.Struct> alist) {
    List<String> cidsids = new ArrayList<String>();
    for (Struct row: alist) {
      int cid = (Integer)row.get("CID");
      int sid = (Integer)row.get("SID");
      cidsids.add(cid+ "_" + sid);
    }
    return cidsids;
  }
  
  protected StringBuilder checkFKConstraint(List<Integer>parent, List<Integer>child) {
    StringBuilder str= new StringBuilder();
    for (int c: child) {
      if (!parent.contains(c)) str.append(c + ", ");
    }
    return str;
  }
  
  protected StringBuilder checkCompositeFKConstraint(List<String>parent, List<String>child) {
    StringBuilder str= new StringBuilder();
    for (String c: child) {
      if (!parent.contains(c)) str.append(c + ", ");
    }
    return str;
  }
  
  public static void HydraTask_checkUniqConstraints() {
    if (!withReplicatedTables) sqlTest.checkUniqConstraints();
  }
  
  protected void checkUniqConstraints() {
    Connection gConn = getGFEConnection();
    checkUniqConstraints(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void checkUniqConstraints(Connection gConn) {
    List<com.gemstone.gemfire.cache.query.Struct> uniqRS = getUniqKeys(gConn);
    List<com.gemstone.gemfire.cache.query.Struct> uniqDistinictRS = getUniqDistinctKeys(gConn);
    ResultSetHelper.compareResultSets(uniqRS, uniqDistinictRS, 
        "unique fields" , "unique fields using distinct");
    Log.getLogWriter().info("verified the unique key constraints");
  }
  
  protected List<com.gemstone.gemfire.cache.query.Struct> getUniqKeys(Connection gConn) {
    ResultSet rs = null;
    try {
      String select = "select symbol, exchange from trade.securities";
      Log.getLogWriter().info(select);
      rs = gConn.createStatement().executeQuery(select);
      return ResultSetHelper.asList(rs, false); 
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
      commit(gConn);
    }
    return null;
  }
  
  protected List<com.gemstone.gemfire.cache.query.Struct> getUniqDistinctKeys(Connection gConn) {
    ResultSet rs = null;
    try {
      String select = "select distinct symbol, exchange from trade.securities";
      Log.getLogWriter().info(select);
      rs = gConn.createStatement().executeQuery(select);
      return ResultSetHelper.asList(rs, false); 
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
      commit(gConn);
    }
    return null;
  }
  
  public static void HydraTask_checkConstraints() {
    if (!withReplicatedTables) sqlTest.checkConstraints();
  }
  
  protected void checkConstraints() {
    Connection gConn = getGFEConnection();
       
    checkUniqConstraints(gConn);
    checkFKConstraints(gConn);
    
    closeGFEConnection(gConn);
  }
  
  public static void waitBeforeShutdown() {
    int sleepSec = TestConfig.tab().intAt(SQLPrms.sleepSecondBeforeShutdown, 300);
    Log.getLogWriter().info("sleep " + (sleepSec) + " seconds before shut down");
    MasterController.sleepForMs(sleepSec * 1000);
  }
  
  public static void dumpResults() { 
    try {
      ResultSetHelper.dumpLocalBucket();
    } catch (Exception e) {
      Log.getLogWriter().info(TestHelper.getStackTrace(e));
    }
    
    try {
      ResultSetHelper.sendResultMessage();
    } catch (Exception e) {
      Log.getLogWriter().info(TestHelper.getStackTrace(e));
    }
    
    if (hasDerbyServer && RemoteTestModule.getMyVmid() == 1) {
      dumpDerbyResults();
    }
  }
  
  public static void dumpDerbyResults() {
    new SQLTest().dumpDerbyTables();
  }
  
  private Connection getDerbyConnForDump() throws SQLException{
    if (testSecurity)
      return ClientDiscDBManager.getSuperUserConnection();  //user, password requried
    else
      return ClientDiscDBManager.getConnection();
  }
  
  private void dumpDerbyTables() {
    Connection dConn = null;
    List<Struct> myList = null;
    boolean success = true;
    try {
      dConn = getDerbyConnForDump();
      ResultSet rs = dConn.createStatement().executeQuery("select SCHEMANAME, tablename "
          + "from sys.systables t, sys.sysschemas s where t.schemaid = s.schemaid and " +
          	"tabletype = 'T' and schemaname not like 'SYS%'");
      List<Struct> rsList = ResultSetHelper.asList(rs, true);
      for (Struct info: rsList) {
        String schemaName = (String) info.get("SCHEMANAME");
        String tableName = (String) info.get("TABLENAME");
        int count = 0;
        do { 
          try {
            count++;
            dConn = getDerbyConnForDump();
            myList = getResultSet(dConn, schemaName, tableName);
            Log.getLogWriter().info("dump derby result set for table " + schemaName+"."+tableName);
            Log.getLogWriter().info(ResultSetHelper.listToString(myList));
            success = true;
          } catch (TestException te) {
            Log.getLogWriter().info("dump derby table failed\n" + TestHelper.getStackTrace(te));
            success = false;
            if (te.getMessage().contains("Error connecting to server")) {
              Log.getLogWriter().info("derby has been shut down by master, there are no more dumps");
              return;
            }
            MasterController.sleepForMs(1000);
          } 
        } while (!success && count < 2);
      } //this is best effort, so some tables may not be able to be dumped due to locking issue
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void dumpThreads() {
    long start = System.currentTimeMillis();
    long now;
    int time = dumpThreadsTotalTime * 1000; 
    
    do {
     Log.getLogWriter().info("waiting for " + dumpThreadsInterval/1000 + "seconds");
     MasterController.sleepForMs(dumpThreadsInterval);
     
     runDumpRunScript();
     
     now = System.currentTimeMillis();
     
    } while (now - start < time);
     
  }
  
  protected void runDumpRunScript() {
    File currDir = new File(System.getProperty("user.dir"));
    File[] files = currDir.listFiles();
    for (File aFile: files) {
      if (aFile.getName().equals("dumprun.sh") || aFile.getName().equals("dumprun.bat")) { // run the dumprun script
        try {
          String cmd = aFile.getCanonicalPath();
          try {
            Log.getLogWriter().info("Running dumprun scripts");
            String cmdResult = ProcessMgr.fgexec(cmd, 0);
            Log.getLogWriter().info("Result is " + cmdResult);
          } catch (HydraRuntimeException e) {
            log().info("dumprun cmd " + cmd + " failed ", e);
          }
        } catch (IOException e) {
          throw new TestException(TestHelper.getStackTrace(e));
        }
      }
    }    
  }
  public static void HydraTask_writePersistToBB() {
    sqlTest.writePersistToBB();
  }

  //need to configured in the conf that only one site will run this
  protected void writePersistToBB() {
    Connection gConn = getGFEConnection(); 
    writePersistToBB(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void writePersistToBB(Connection gConn) {
    List<Struct> list = null;
    ResultSet rs = null;
    
    try {
      rs = gConn.createStatement().executeQuery("select tableschemaname, tablename "
          + "from sys.systables where tabletype = 'T' "
          + (SQLPrms.isSnappyMode()?" and tableschemaname != 'SNAPPY_HIVE_METASTORE'":""));
      while (rs.next()) {
        String schemaName = rs.getString(1);
        String tableName = rs.getString(2);
        list = getResultSet(gConn, schemaName, tableName);
        //Log.getLogWriter().info("before write to bb: " + ResultSetHelper.listToString(list));
        PersistBB.getBB().getSharedMap().put(schemaName+"_"+tableName, list);         
        //list = (List<Struct>)wanBB.getSharedMap().get(tableName);
        //Log.getLogWriter().info("now gets the list from the bb, size is " + list.size());
        //Log.getLogWriter().info("after write to bb: " + ResultSetHelper.listToString(list));
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
    }
  }
  
  public static void HydraTask_verifyPersistFromBB() {
    sqlTest.verifyPersistFromBB();
  }
  
  protected void verifyPersistFromBB() {
    Connection gConn = getGFEConnection(); 
    verifyPersistFromBB(gConn);
    closeGFEConnection(gConn);
  }
  
  @SuppressWarnings("unchecked")
  protected void verifyPersistFromBB(Connection gConn) {
    //verifyResult after the last key is arrived       
    List<Struct> myList = null;
    List<Struct> persistList = null;
    boolean throwException = false;
    StringBuffer str = new StringBuffer();
    try {
      ResultSet rs = gConn.createStatement().executeQuery("select tableschemaname, tablename "
          + "from sys.systables where tabletype = 'T' and tableschemaname not like 'SYS%'"
          + (SQLPrms.isSnappyMode()?" and tableschemaname != 'SNAPPY_HIVE_METASTORE'":""));
      while (rs.next()) {
        String schemaName = rs.getString(1);
        String tableName = rs.getString(2);
        myList = getResultSet(gConn, schemaName, tableName);
        persistList = (List<Struct>)PersistBB.getBB().getSharedMap().get(schemaName+"_"+tableName);
        
        try {
          Log.getLogWriter().info("comparing result set for table " + schemaName+"."+tableName);
          ResultSetHelper.compareResultSets(persistList, myList, "previous", "current");
        }catch (TestException te) {
          Log.getLogWriter().info("do not throw Exception yet, until all tables are verified");
          throwException = true;
          str.append(te.getMessage() + "\n");
          
        } //temporary
      }
      if (throwException) {
        throw new TestException ("verify results failed: " + str);
      }   
      rs.close();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } 
  }
  
  public static void HydraTask_verifyScrollableResultSet() {
    int chance = 5;
    if (random.nextInt(chance) == 1)
      sqlTest.verifyScrollableResultSet();
    else 
      sqlTest.doDMLOp();
  }
  
  protected void verifyScrollableResultSet() {
    if (!hasDerbyServer) {
      Connection gConn = getGFEConnection(); 
      verifyScrollableResultSet(gConn);
      commit(gConn);      
      closeGFEConnection(gConn);
      return;
    }
    else if (!testUniqueKeys) {
      Log.getLogWriter().info("Could not verify results");
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection(); 
    verifyScrollableResultSet(dConn, gConn);
    
    commit(dConn);
    commit(gConn);
    
    closeGFEConnection(gConn);
    closeDiscConnection(dConn);
  }
  
  protected void verifyScrollableResultSet(Connection dConn, Connection gConn) {
    int maxCid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    int cid = random.nextInt(maxCid);
    int tid = getMyTid();
    String[] sql = {
        "select * from trade.networth where cid < " 
        + cid + " and tid= " + tid + " order by cid",
        "select * from trade.customers where cid < " 
        + cid + " and tid= " + tid + " order by cid",
        "select * from trade.portfolio where cid < " 
        + cid + " and tid= " + tid + " order by cid, sid",
        "select * from trade.sellorders where cid < " 
        + cid + " and tid= " + tid + " order by oid",
        "select * from trade.buyorders where cid < " 
        + cid + " and tid= " + tid + " order by oid",
    };
    
    int whichOne = random.nextInt(sql.length);
    Statement d_stat = null;
    Statement s_stat = null;
    ResultSet derbyRs = null;
    ResultSet gfxdRs = null;
    try {
      d_stat = dConn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, 
          ResultSet.CONCUR_READ_ONLY);
      s_stat = gConn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, 
          ResultSet.CONCUR_READ_ONLY);
    
      Log.getLogWriter().info(sql[whichOne]);
      derbyRs = d_stat.executeQuery(sql[whichOne]);
      gfxdRs = s_stat.executeQuery(sql[whichOne]);
      int totalRows = 0;
      while (gfxdRs.next()) totalRows++;
      gfxdRs.first();
      
      Log.getLogWriter().info("gfxd total rows are " + totalRows);
      boolean success = verifyScrollableRs(derbyRs, gfxdRs, tid, totalRows);  
      if (!success) {
        derbyRs.first();
        gfxdRs.first();
        ResultSetHelper.compareSortedResultSets(derbyRs, gfxdRs);
      }
      d_stat.close();
      s_stat.close();

    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(dConn, se)) { //handles the deadlock of aborting
        Log.getLogWriter().info("gets derby lock issue, abort this operation");
        return;
      }
      if (!SQLHelper.checkGFXDException(gConn, se)) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("could not process scrollable rs due to node failure");//hand X0Z01 and #41471 
        return;
      }
      else SQLHelper.handleSQLException(se); 
    } finally {
      try {
        if (d_stat != null) d_stat.close();
        if (derbyRs !=null) derbyRs.close();
        if (s_stat != null) s_stat.close();
        if (gfxdRs !=null) gfxdRs.close();
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }
  }
  
  protected boolean verifyScrollableRs(ResultSet derbyRs, ResultSet gfxdRs, int tid, int totalRows) {
    try {
      boolean success;
      if (totalRows == 0) {
        if (derbyRs.first()) {
          Log.getLogWriter().warning("gfxd returns 0 rows, but derby returns rows");
          ResultSetHelper.compareSortedResultSets(derbyRs, gfxdRs);
        }
        return true;
      }
      if (totalRows == 1) {
        derbyRs.first();
        gfxdRs.first();
        return verifyRow(derbyRs, gfxdRs);
      } else {
        int firstHalf = totalRows/2;
        
        //second half rows to be updated
        derbyRs.absolute(-(totalRows-firstHalf));
        gfxdRs.absolute(-(totalRows-firstHalf));
        success = verifyRow(derbyRs, gfxdRs);
        if (!success) return success; 
        //if any one operation failed, 
        //need to restart the tx as product automatically rollback the tx 
        while(derbyRs.next()) {
          gfxdRs.next();
          success = verifyRow(derbyRs, gfxdRs);
          if (!success) return success;
        }
        
        //first half rows to be updated
        derbyRs.absolute(firstHalf);
        gfxdRs.absolute(firstHalf);
        success = verifyRow(derbyRs, gfxdRs);
        if (!success) return success;
        while (derbyRs.previous()) {
          gfxdRs.previous();
          success = verifyRow(derbyRs, gfxdRs);
          if (!success) return success;
        }
        
        derbyRs.last();
        gfxdRs.last();
        success = verifyRow(derbyRs, gfxdRs);
        if (!success) return success;
        
        derbyRs.relative(-firstHalf);
        gfxdRs.relative(-firstHalf);
        success = verifyRow(derbyRs, gfxdRs);
        if (!success) return success;
      }
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("X0Z01") && isHATest) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("could not process scrollable rs due to node failure");//hand X0Z01 and #41471 
        return false;
      }
      else SQLHelper.handleSQLException(se); 
    } finally {
      
    }
    
    return true;
  }
  
  protected boolean verifyRow(ResultSet derbyRs, ResultSet gfxdRs) {
    try {
      int derbyCid = derbyRs.getInt("CID");
      int gfxdCid = gfxdRs.getInt("CID");
      if (derbyCid != gfxdCid) {
        Log.getLogWriter().warning("Scrollable resultsets in gfxd gets different result sets:" +
        		" derbyRs gets " + derbyCid + " but gfxdRs gets " + gfxdCid);
      }
      return derbyCid == gfxdCid;
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    return false;
  }
  
  protected void verifyScrollableResultSet(Connection gConn) {
    int maxCid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    int cid = random.nextInt(maxCid);
    int tid = getMyTid();
    String[] sql = {
        "select * from trade.networth where cid > " 
        + cid,
        "select * from trade.customers where cid > " 
        + cid + " order by cid",
        "select * from trade.portfolio where cid > " 
        + cid + " order by cid, sid",
        "select * from trade.sellorders where cid > " 
        + cid,
        "select * from trade.buyorders where cid > " 
        + cid + " order by oid",
    };
    
    int whichOne = random.nextInt(sql.length);
      
    try {
      Statement s_stat = gConn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, 
          ResultSet.CONCUR_READ_ONLY);
    
      Log.getLogWriter().info(sql[whichOne]);
      ResultSet gfxdRs = s_stat.executeQuery(sql[whichOne]);
      int totalRows = 0;
      while (gfxdRs.next()) totalRows++;
      
      Log.getLogWriter().info("gfxd total rows are " + totalRows);
      if (random.nextBoolean())
        processScrollableRs(gfxdRs, tid, totalRows);  
      else 
        verifyScrollableRs(gfxdRs, totalRows);
      s_stat.close(); //should close the rs as well

    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01")) {
        Log.getLogWriter().info("could not process the row due to node failure, continuing the test");
      } else SQLHelper.handleSQLException(se); //should not get conflict for the query
    }

  }
  
  protected void verifyScrollableRs(ResultSet gfxdRs, int size) {
    try {
      gfxdRs.beforeFirst();
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) {
        Log.getLogWriter().info("could not process resultset due to node failure");
        return;
      } else SQLHelper.handleSQLException(se);
    }
    List<Struct> list1 = ResultSetHelper.asList(gfxdRs, false);
    if (list1 == null) {
      Log.getLogWriter().info("could not process resultset due to node failure");
      return;
    }
    
    try {
      if (size > 1) {
        gfxdRs.absolute(random.nextInt(size));
        int sleepMs = 2000;
        MasterController.sleepForMs(sleepMs);
        gfxdRs.beforeFirst();
      } else 
        gfxdRs.beforeFirst();
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) {
        Log.getLogWriter().info("could not process resultset due to node failure");
        return;
      } else SQLHelper.handleSQLException(se);
    }
    List<Struct> list2 = ResultSetHelper.asList(gfxdRs, false);
    if (list2 == null) {
      Log.getLogWriter().info("could not process resultset due to node failure");
      return;
    }
    ResultSetHelper.compareResultSets(list1, list2, "firstScrolllableRs", "secondScrollableRs");
  }
  
  protected boolean processScrollableRs(ResultSet gfxdRs, int tid, int totalRows) {
    try {
      boolean success;
      if (totalRows == 0) {
        return true;
      }
      if (totalRows == 1) {
        gfxdRs.first();
        return processRow(gfxdRs);
      } else {
        int firstHalf = totalRows/2;
        
        //second half rows to be updated
        gfxdRs.absolute(-(totalRows-firstHalf));
        success = processRow(gfxdRs);
        if (!success) return success; 
        //if any one operation failed, 
        //need to restart the tx as product automatically rollback the tx 
        while(gfxdRs.next()) {
          success = processRow(gfxdRs);
          if (!success) return success;
        }
        
        //first half rows to be updated
        gfxdRs.absolute(firstHalf);
        success = processRow(gfxdRs);
        if (!success) return success;
        while (gfxdRs.previous()) {
          success = processRow(gfxdRs);
          if (!success) return success;
        }
        
        gfxdRs.last();
        success = processRow(gfxdRs);
        if (!success) return success;
        
        gfxdRs.first();
        success = processRow(gfxdRs);
        if (!success) return success;
      }
    } catch (SQLException se) {
      if (!SQLHelper.checkGFXDException(false, se)) return true; //got expected exceptions
      else SQLHelper.handleSQLException(se); 
      //should not get conflict for moving position 
      //as it acquires only update read lock
    }
    
    return true;
  }
  
  protected boolean processRow(ResultSet gfxdRs) {
    try {     
      if (alterTableDropColumn) {
        ResultSetMetaData rsmd = gfxdRs.getMetaData();
        int numOfColumns = rsmd.getColumnCount();
        for (int i=0; i<numOfColumns; i++) {
          gfxdRs.getObject(i+1);
        }          
      } else {
        int cid = gfxdRs.getInt("CID");
        //Log.getLogWriter().info("cid is " + cid);
        gfxdRs.getObject(3);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    return true;
  }
  
  public static void HydraTask_alterTableAddColumn() {
    sqlTest.alterTableAddColumn();
  }
  
  protected void alterTableAddColumn() {
    Connection gConn = getGFEConnection();
    addColumn(gConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_alterCustomersTableAddColumn() throws SQLException{
    sqlTest.alterCustomersTableAddColumn();
  }
 
  protected void alterCustomersTableAddColumn() throws SQLException{
    if (!hasDerbyServer) {
      Connection gConn = getGFEConnection();
      addColumnCustomersTable(gConn);
      commit(gConn);
      closeGFEConnection(gConn);
      return;
    }
      Connection dConn = getDiscConnection();
      addColumnCustomersTable(dConn);
      commit(dConn);
      closeDiscConnection(dConn);

      Connection gConn = getGFEConnection();
      addColumnCustomersTable(gConn);
      commit(gConn);
      closeGFEConnection(gConn);

  }

  protected void addColumnCustomersTable(Connection conn) throws SQLException{
    String addColumn = "alter table trade.customers" 
      + " add column cid_1 bigint";
    try {
      conn.createStatement().execute(addColumn);
    } catch (SQLException se){
      SQLHelper.handleSQLException(se);
    } 
    Log.getLogWriter().info("executed " + addColumn + " in " + 
      (SQLHelper.isDerbyConn(conn)? "derby " : "gfxd "));
  }

  public static void HydraTask_createIndexOnAddedColumn() {
    sqlTest.createIndexOnAddedColumn();
  }

  protected void createIndexOnAddedColumn() {
    Connection gConn = getGFEConnection();
    createIndexOnAddedColumn(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  protected void createIndexOnAddedColumn(Connection gConn) {
    String sql = "create index indexcid_1 on trade.customers (cid_1)";
    try {
      log().info(sql);
      executeStatement(gConn, sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void addColumn(Connection conn) {
    try {
      ResultSet rs = conn.createStatement().executeQuery("select tablename "
          + "from sys.systables where tabletype = 'T' and tableschemaname='TRADE'");
      while (rs.next()) {
        String tableName = rs.getString(1);
        if (!tableName.equalsIgnoreCase("txhistory"))
          addColumn(conn, tableName);
      }  
      rs.close();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }    
  }
  
  protected void addColumn(Connection conn, String tableName) throws SQLException{
    String columnDef = (String) SQLBB.getBB().getSharedMap().get("dropColumn"+tableName);
    String addColumn = "alter table trade." + tableName 
      + " add column " + columnDef;
    try {
      conn.createStatement().execute(addColumn);
    } catch (SQLException se){
      SQLHelper.handleSQLException(se);
    }  
    Log.getLogWriter().info("added the column " + columnDef + " to the table " + tableName);
  }
  
  protected void addColumn(Connection conn, String schema, String tableName, String columnName, 
      String dataType) {
    String sql = "alter table trade." + tableName 
    + " add column " + columnName + " " + dataType + " not null";
    addColumn(conn, sql, true);
    
    sql = "alter table trade." + tableName 
      + " add column " + columnName + " " + dataType +
      (dataType.toUpperCase().startsWith("VARCHAR") ? " default 'a' not null" : " ");
    addColumn(conn, sql, false); 
    Log.getLogWriter().info("added the column " + columnName + " to the table " + tableName);
  }
  
  protected void addColumn(Connection conn, String sql, boolean expectNotNullFailure) {
    try {
      conn.createStatement().execute(sql);
    } catch (SQLException se){
      if (se.getSQLState().equals("42601") && expectNotNullFailure) {
        log().info("got expected not null column with default value failure");
      } else if (reproduceTicket46689) {
        if (se.getSQLState().equals("X0X95")) {
          log().info("got expected exception, continuing testing");
        } else {
          throw new TestException("got #46889, alter table does not get expected X0X95 when result set is not closed");
        }
      }else SQLHelper.handleSQLException(se);
    }  
    
  }
  
  
  public static void HydraTask_alterTableDropColumn() {
    if(useGenericSQL)
      sqlGen.alterTable();
    else
      sqlTest.alterTableDropColumn();
  }
  
  protected void alterTableDropColumn() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } 
    Connection gConn = getGFEConnection();    
    dropColumn(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void dropColumn(Connection dConn, Connection gConn) {
    try {
      ResultSet rs = gConn.createStatement().executeQuery("select tablename "
          + "from sys.systables where tabletype = 'T' and tableschemaname='TRADE'");
      while (rs.next()) {
        String tableName = rs.getString(1);
        if (!tableName.equalsIgnoreCase("txhistory") && !tableName.equalsIgnoreCase("TRADES")
            /*&& !tableName.equalsIgnoreCase("companies")*/) {
          dropColumn(dConn, gConn, tableName);
          commit(dConn);
          commit(gConn);
        }
      }  
      rs.close();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }    
  }
  
  //do not handle constraints on a column,
  //these will be handled in the other alter table methods
  protected void dropColumn(Connection dConn, Connection gConn, String tableName) throws SQLException{
    ResultSet columns = null;
    DatabaseMetaData meta = gConn.getMetaData();
    SQLException derbyException = null;
    SQLException gfxdException = null;
    columns = meta.getColumns(null, "TRADE", tableName, null);
    List<Struct>columnList = ResultSetHelper.asList(columns, false);
    SQLHelper.closeResultSet(columns, gConn);
    log().info("columns are " + ResultSetHelper.listToString(columnList));
    String columnType = null;
    String columnName = null;
    while (true) {
      Struct aColumn = columnList.get(random.nextInt(columnList.size()));
      columnType = (String) aColumn.get("TYPE_NAME");
      columnName = (String) aColumn.get("COLUMN_NAME");
      if (!columnName.equals("TID")) break;
    }
    
    if (hasDerbyServer) {
      try {
        dropColumn(dConn, tableName, columnName);
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(dConn, se)) {
          dConn.rollback(); //gfxd failed partitioned column could not be dropped  
          log().info("could not finish op, roll back the operation in derby");
          return; 
        }
        derbyException = se;
        SQLHelper.printSQLException(se);
      }
      
      try {
        dropColumn(gConn, tableName, columnName);
      } catch (SQLException se) {
        gfxdException = se;
        SQLHelper.printSQLException(se);
      }
      
      if (gfxdException != null) {
         if (gfxdException.getSQLState().equals("X0Z01") && isHATest) {
           dConn.rollback(); //gfxd failed operation due to node failure 
           return;
         } else if (gfxdException.getSQLState().equals("0A000") && isPartitionedColumn(tableName, columnName)) {
           dConn.rollback(); //gfxd failed partitioned column could not be dropped  
           log().info("roll back the operations in derby");
           return;
         } else if (gfxdException.getSQLState().equals("0A000") && isPrimaryKeyColumn(tableName, columnName)) {
           dConn.rollback(); //gfxd failed partitioned column could not be dropped   
           log().info("roll back the operations in derby");
           return;
         } 
      }
      
      SQLHelper.compareExceptions(derbyException, gfxdException);
      SQLBB.getBB().getSharedMap().put("dropColumn"+tableName, columnName+ " " + columnType);
      
    } else {
      try {
        dropColumn(gConn, tableName, columnName);
      } catch (SQLException se) {
        gfxdException = se;
        SQLHelper.printSQLException(se);
      }
      if (gfxdException != null) {
        if (gfxdException.getSQLState().equals("X0Z01") && isHATest) {
          return;
        } else if (gfxdException.getSQLState().equals("0A000") && isPartitionedColumn(tableName, columnName)) { 
          return;
        } else if (gfxdException.getSQLState().equals("0A000") && isPrimaryKeyColumn(tableName, columnName)) {
          return;
        } else if (gfxdException.getSQLState().equals("X0Y25")) {
          log().info("drop column with data got expected excetpion, continuing tests");
        }
     }
      
    }  
  }
  
  @SuppressWarnings("unchecked")
  protected boolean isPartitionedColumn(String tableName, String columnName) {
    ArrayList<String> partitionKeys = new ArrayList<String>();
    SharedMap partitionMap = SQLBB.getBB().getSharedMap();
    SharedMap wanPartitionMap = SQLWanBB.getBB().getSharedMap();
    if (!isWanTest) {
      partitionKeys= (ArrayList<String>)partitionMap.get(tableName.toLowerCase() + "Partition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_" + tableName.toLowerCase() + "Partition");
    }
    String partitionField = columnName.toLowerCase();
    if (partitionField.equals("subtotal")) partitionField = "subTotal";
    if (partitionField.equals("loanlimit")) partitionField = "loanLimit";
    if (partitionField.equals("uid")) partitionField = "_uid";
    if (partitionField.equals("availqty")) partitionField = "availQty";
    
    if (partitionKeys.contains(partitionField)) {
      log().info(columnName + " is a partitioned column of " + tableName);
      return true;
    } else {
      log().info(columnName + " is not a partitioned column of " + tableName);
      return false;
    }
  }
  
  protected boolean isPrimaryKeyColumn(String tableName, String columnName) {
    if (tableName.equalsIgnoreCase("customers") && columnName.equalsIgnoreCase("CID"))
      return true;
    else if (tableName.equalsIgnoreCase("securities") && columnName.equalsIgnoreCase("SEC_ID"))
      return true;
    else if (tableName.equalsIgnoreCase("networth") && columnName.equalsIgnoreCase("CID"))
      return true;
    else if (tableName.equalsIgnoreCase("portfolio") && 
        (columnName.equalsIgnoreCase("CID") || columnName.equalsIgnoreCase("SID")))
      return true;
    else if (tableName.equalsIgnoreCase("sellorders") && columnName.equalsIgnoreCase("OID"))
      return true;
    else if (tableName.equalsIgnoreCase("buyorders") && columnName.equalsIgnoreCase("OID"))
      return true;
    else if (tableName.equalsIgnoreCase("companies") && 
        (columnName.equalsIgnoreCase("SYMBOL") || columnName.equalsIgnoreCase("Exchange")))
      return true;
    else if (tableName.equalsIgnoreCase("customerprofile") && columnName.equalsIgnoreCase("CID"))
      return true;
    else if (tableName.equalsIgnoreCase("sellordersdup") && columnName.equalsIgnoreCase("OID"))
      return true;
    
        
    return false;

  }
  
  protected void dropColumn(Connection conn, String tableName, String columnName) 
  throws SQLException{
    String dropColumn = "alter table trade." + tableName 
    + " drop" + (random.nextBoolean()? " column ": " ") + columnName + " RESTRICT ";
    Log.getLogWriter().info("in " +
        (SQLHelper.isDerbyConn(conn)? "derby " : "gfxd ") +
        "dropping the column " + columnName + " in table " + tableName);
    //try {
      conn.createStatement().execute(dropColumn);
    /*
    } catch (SQLException se){
      //if (se.getSQLState().equals("0A000")) {
      //partitioned key and primary key field could not be dropped
      //unique key field (composite unique key) should not be dropped
      //check constrain field may need to be handled as well
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("could not drop the column " + columnName + " due to exception");
        
      //}
      return false;
    }
    
    return true;
    */
  }
  
  public static void HydraTask_updateAddedColumn() {
    sqlTest.updateAddedColumn();
  }
  
  protected void updateAddedColumn() {
    Connection gConn = getGFEConnection();
    updateColumn(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void updateColumn(Connection conn) {
    try {
      ResultSet rs = conn.createStatement().executeQuery("select tablename "
          + "from sys.systables where tabletype = 'T' and tableschemaname='TRADE'");
      while (rs.next()) {
        String tableName = rs.getString(1);
        if (!tableName.equalsIgnoreCase("txhistory"))
          updateColumn(conn, tableName);
      }  
      rs.close();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }    
  }
  
  protected void updateColumn(Connection conn, String tableName) throws SQLException{
    String columnDef = (String) SQLBB.getBB().getSharedMap().get("dropColumn"+tableName);
    String[] column = columnDef.split(" ");
    updateColumn(conn, tableName, tableName, column[1]);  
  }
  
  protected void updateColumn(Connection conn, String tableName, String columnName, String type) throws SQLException {
    String sql = null;
    ResultSet rs = null;
    String update = null;
    PreparedStatement ps = null;
    if (tableName.equalsIgnoreCase("customers")) {
      sql = "select cid, " + columnName + " from trade." + tableName;
      update = "update trade.customers set " + columnName + " =? where cid = ?";
      ps = conn.prepareStatement(update);
      rs = conn.createStatement().executeQuery(sql);
      while (rs.next()) {
        ps.setObject(1, rs.getObject(2)); //set column value
        ps.setInt(2, rs.getInt(1)); //set cid
        ps.execute();
      }
      rs.close();
    }    
    //to add remaining tables    
  } 
  
  public static void HydraTask_verifyDropColumn() {
    sqlTest.verifyDropColumn();
  }
  
  protected void verifyDropColumn() {
    Connection gConn = getGFEConnection();
    verifyDropColumn(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void verifyDropColumn(Connection conn) {
    try {
      ResultSet rs = conn.createStatement().executeQuery("select tablename "
          + "from sys.systables where tabletype = 'T' and tableschemaname='TRADE'");
      while (rs.next()) {
        String tableName = rs.getString(1);
        if (!tableName.equalsIgnoreCase("txhistory"))
          verifyDropColumn(conn, tableName);
      }  
      rs.close();
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }    
  }
  
  protected void verifyDropColumn(Connection conn, String tableName) throws SQLException{
    String columnDef = (String) SQLBB.getBB().getSharedMap().get("dropColumn"+tableName);
    String[] column = columnDef.split(" ");
    String columnName = column[0];
    String select = "select " + columnName + " from trade." + tableName;
    try {
      conn.createStatement().execute(select);
    } catch (SQLException se){
      SQLHelper.printSQLException(se);
      if (se.getCause().equals("42X04") && alterTableDropColumn) {
        Log.getLogWriter().info("get the expected exception after drop column, continuing tests");
        return;
      }
    }  
    throw new TestException(select + " does not get exception after the column is dropped");
  }
  
  public static void HydraTask_alterTableGenerateIdAlways() {
    sqlTest.alterTableGenerateIdAlways();
  }
  
  protected void alterTableGenerateIdAlways() {
    Connection gConn = getGFEConnection();
    alterTableGenerateIdAlways(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void alterTableGenerateIdAlways(Connection conn) {
    try {
      String sql = "alter table trade.customers "
        + "ALTER" + (random.nextBoolean()? " column ": " ")  
        + "cid SET GENERATED ALWAYS AS IDENTITY ";
      Log.getLogWriter().info(sql);
      conn.createStatement().execute(sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_dropCompositUniqueKeyColumns() {
    sqlTest.dropCompositUniqueKeyColumns();
  }
  
  protected void dropCompositUniqueKeyColumns() {
    Connection gConn = getGFEConnection();
    boolean ableToDropSymbolCol = dropCompositUniqueKeyColumn(gConn, false);
    if (!ableToDropSymbolCol) return; //could not drop the column due to partition key
    removeUniqueKeyContraint(gConn);
    dropCompositUniqueKeyColumn(gConn, true);
    verifyColumnDropped(gConn, "trade", "securities", "symbol");
    addColumn(gConn, "trade", "securities", "symbol", "varchar (10)");
    getDataForDroppedColumn(gConn, "trade", "securities", "symbol");
    addBackUQConstraint(gConn, "trade", "securities");
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_dropCompositUniqueKeyConstraint() {
    sqlTest.dropCompositUniqueKeyConstraint();
  }
  
  protected void dropCompositUniqueKeyConstraint() {
    Connection gConn = getGFEConnection();
    removeUniqueKeyContraint(gConn);
    addBackUQConstraint(gConn, "trade", "securities");
    closeGFEConnection(gConn);
  }
  
  protected void addBackUQConstraint(Connection conn, String schema, String tableName) {
    String sql = "alter table trade." + tableName 
      + " add constraint sec_uq unique (symbol, exchange)";
    try {
      conn.createStatement().execute(sql);
      log().info(sql);
    } catch (SQLException se){
      SQLHelper.handleSQLException(se);
    }  
    
  }
  
  protected void getDataForDroppedColumn(Connection gConn, String schema, String table, String droppedColumnName) {
    String sql = null;
    ResultSet rs = null;
    List<Struct> data = null;
    String[] primaryKey = new String[2];
    List<Struct> keyList = null;
    DatabaseMetaData meta = null;
    try {
      meta = gConn.getMetaData();
      rs = meta.getPrimaryKeys(null, schema, table.toUpperCase());
      keyList = ResultSetHelper.asList(rs, false);
      rs.close();
      //if (keyList.size() == 0 ) throw new TestException("could not get primary key for the table");
      
      if (hasDerbyServer) {
        Connection dConn = getDiscConnection();
        meta = dConn.getMetaData(); //from derby   
        rs = meta.getPrimaryKeys(null, null, table.toUpperCase());
      } //work around #46697
      
      keyList = ResultSetHelper.asList(rs, true);
      SQLHelper.closeResultSet(rs, gConn);
      int i =0;
      for (Struct key: keyList) {
        primaryKey[i] = (String) key.get("COLUMN_NAME");
        ++i;
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    if (hasDerbyServer) {
      try { 
        sql = "select " + primaryKey[0] + ", " + 
          (keyList.size() == 2? primaryKey[1] + ", " : "") +
          droppedColumnName + " from "+ schema + "." + table;
        rs = getDiscConnection().createStatement().executeQuery(sql);
        
        data = ResultSetHelper.asList(rs, true);
        if (data == null) throw new TestException("test issue, make sure derby result could be gotten");
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    } else {
      //TODO data = getFromSavedData(schema, table, droppedcolumnName);
    }
    
    try {
      if (keyList.size() == 1) {
        sql = "update " + schema + "." + table + " set " + 
        droppedColumnName + " = ? where " + primaryKey[0] + " = ?" ;
      } else if (keyList.size() == 2) {
        sql = "update " + schema + "." + table + " set " + 
        droppedColumnName + " = ? where " + primaryKey[0] + " = ? and "  + primaryKey[1] + " = ?";
      } else throw new TestException("primary key list has wrong size " + keyList.size());
      PreparedStatement ps = gConn.prepareStatement(sql);
      log().info(sql);
      
      for (Struct row: data) {
        ps.setObject(1, row.get(droppedColumnName.toUpperCase()));
        ps.setObject(2, row.get((String)primaryKey[0]));
        if (keyList.size() == 2) ps.setObject(3, row.get((String)primaryKey[1]));
        ps.addBatch();       
      }
      ps.executeBatch();
      ps.close();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void verifyColumnDropped(Connection gConn, String schema, String table, String droppedcolumnName) {
    String sql = "select " + droppedcolumnName + " from " + schema + "." + table;
    ResultSet rs = null;
    try {
      rs = gConn.createStatement().executeQuery(sql);
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("42X04") && alterTableDropColumn)
        log().info("Got expected exception for dropped column");
      else
        SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
    }
    
    if (hasDerbyServer) {
      try {
        rs = gConn.createStatement().executeQuery("select * from "+ schema + "." + table);
        ResultSetMetaData rmd = rs.getMetaData();
        int columnSize = rmd.getColumnCount();
        StringBuilder columnNames = new StringBuilder();
        for (int i=1; i<=columnSize; i++) {
          if (i<columnSize)
            columnNames.append(rmd.getColumnName(i) + ", ");
          else
            columnNames.append(rmd.getColumnName(i));
        }
        
        sql = "select " + columnNames.toString() + " from " + schema + "." + table;
        log().info(sql);
        ResultSet derbyRs = getDiscConnection().createStatement().executeQuery(sql);
        ResultSetHelper.compareResultSets(derbyRs, rs);
        if (!reproduceTicket46689) {
          rs.close();
          derbyRs.close();
        }
      } catch (SQLException se) {
         SQLHelper.handleSQLException(se);
      }
    } 
  }
  
  /**
   * where the column dropped or not due to partitioned column
   * @param conn
   * @param constraintDropped whether the unique constraint has been dropped
   * @return true if not partitioned on the column 
   */
  protected boolean dropCompositUniqueKeyColumn(Connection conn, boolean constraintDropped) {
    try {
      boolean withRestrict = random.nextBoolean();
      withRestrict = true; //to avoid #46679
      String sql = "alter table trade.securities "
        + "drop" + (random.nextBoolean()? " column ": " ")  
        + "symbol " + (withRestrict? " restrict ": " ");
      Log.getLogWriter().info(sql);
      conn.createStatement().execute(sql);
      if (!constraintDropped) 
        throw new TestException ("does not get expected exception for unique constraint when dropping a unique key column");
      
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("X0Y25") ) {
        if (!constraintDropped)
          Log.getLogWriter().info("got expected exception when dropping column with constraint");
        else
          throw new TestException("got unexpected exception dropping column after constraint has" +
          		" been dropped" + TestHelper.getStackTrace(se));        
      } else if (se.getSQLState().equals("0A000") && partitionedOnSymbol()) {
        Log.getLogWriter().info("could not drop column due to paritioned key");
        return false;
      }
      else SQLHelper.handleSQLException(se);
    }
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected boolean partitionedOnSymbol() {
    ArrayList<String>  partitionKey = (ArrayList<String>) SQLBB.getBB().getSharedMap().get("securitiesPartition");
    for (String column: partitionKey) {
      if (column.equalsIgnoreCase("symbol")) return true;
    }
    return false;
  }
  
  protected void removeUniqueKeyContraint(Connection conn) {
    try {
      String sql = "alter table trade.securities "
        + "drop unique SEC_UQ ";
      Log.getLogWriter().info(sql);
      conn.createStatement().execute(sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_exportTables() {
    sqlTest.exportTables();
  }
  
  protected void exportTables(){
    Connection gConn = getGFEConnection();
    exportTables(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void exportTables(Connection conn) {
    ArrayList<String[]> tables = getTableNames(conn);
    for (String[] table: tables) 
      exportTable(conn, table[0], table[1]);
  }
  
  protected void exportTable (Connection conn, String schema, 
      String table) {
    String exportTable = "CALL SYSCS_UTIL.EXPORT_TABLE (?, ?, ?, null, null, null)";
    try {
      CallableStatement cs = conn.prepareCall(exportTable);
      cs.setString(1, schema);
      cs.setString(2, table);
      cs.setString(3, FabricServerHelper.getFabricServerDescription().getSysDir() + "/" + table.toUpperCase() + ".file");
      cs.execute();
    } catch (SQLException se) {
       SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_truncateTables() {
    if (supportDuplicateTables){
      sqlTest.dropTriggers();
      sqlTest.truncateDuplicateTables(DUPLICATE_TABLE_SUFFIX);   
    }
    sqlTest.truncateTables();
    
    if (supportDuplicateTables){
      sqlTest.createTriggers(); 
    }    
  }
  
  
  //unordered truncate table by ddl thread, 
  //other concurrent dml operations are performed
  protected void truncateTables(){
    Connection gConn = getGFEConnection();
    if (getMyTid() == ddlThread)
      truncateTables(gConn);
    else
      performConcurrentDMLOps(gConn);
    closeGFEConnection(gConn);
  }
  

  protected void truncateTables(Connection conn) {
    ArrayList<String[]> tables = getTableNames(conn);
    for (String[] table: tables) 
      truncateTable(conn, table[0], table[1], true);
  }
  
  protected void performConcurrentDMLOps(Connection conn) {
    int numOfOps = 20;
    for (int i = 0; i<numOfOps; i++)
      doDMLOp(null, conn);
  }
  
  public static void HydraTask_truncateTablesInOrder() {
    if (supportDuplicateTables){
      sqlTest.dropTriggers(); 
    }
    sqlTest.truncateTablesInOrder();
    
    if (supportDuplicateTables){
      sqlTest.createTriggers(); 
    }   
  }
  
  

  protected void truncateTablesInOrder(){
    Connection gConn = getGFEConnection();
    truncateTablesInOrder(gConn);
    closeGFEConnection(gConn);
  }
  

  protected void truncateTablesInOrder(Connection conn) {
    String schema = "trade";
    String[] tables = {"txhistory", "sellorders", "buyorders", "portfolio", 
        "networth", "customers", "securities" };
    for (String table: tables) truncateTable(conn, schema, table, false);
  }
  
  protected void truncateTable (Connection conn, String schema, 
      String table, boolean expectException) {
    String truncate = "truncate table " + schema + "." + table;
    //String deleteAll = "delete from " + schema + "." + table;
    try {
      Log.getLogWriter().info("executing " + truncate);
      conn.createStatement().executeUpdate(truncate);
      Log.getLogWriter().info("finished " + truncate);
    } catch (SQLException se) {
      if (expectException && se.getSQLState().equals("XCL48")){
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("failed to execute " + truncate);
      } else SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_verifyTruncatedTables() {
    sqlTest.verifyTruncatedTables();
  }
  
  protected void verifyTruncatedTables(){
    Connection gConn = getGFEConnection();
    verifyTruncatedTables(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void verifyTruncatedTables(Connection conn) {
    ArrayList<String[]> tables = getTableNames(conn);
    for (String[] table: tables) 
      verifyTruncatedTable(conn, table[0], table[1], false);
  }
  
  protected void verifyTruncatedTable(Connection conn, String schema, 
      String table, boolean expectException) {
    String sql = "select * from " + schema + "." + table;
    try {
      ResultSet rs = conn.createStatement().executeQuery(sql);
      Log.getLogWriter().info(sql);
      List<Struct> list = ResultSetHelper.asList(rs, false);
      SQLHelper.closeResultSet(rs, conn);
      if (list.size() >0) {        
        throw new TestException("after truncate table, the following rows " +
        		"are still in the table:\n" + ResultSetHelper.listToString(list));
       
        //Log.getLogWriter().info(table + "\n" + ResultSetHelper.listToString(list));
      } else 
        Log.getLogWriter().info("verified that the table " + table + " has been truncated");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  
  public static void HydraTask_alterTableGenerateDefaultId() {
    sqlTest.alterTableGenerateDefaultId();
  }
  
  protected void alterTableGenerateDefaultId() {
    Connection gConn = getGFEConnection();
    alterTableGenerateDefaultId(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void alterTableGenerateDefaultId(Connection conn) {
    int startKey = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    boolean setIncrement = random.nextBoolean();
    try {
      String increment = null;
      if (setIncrement)
      increment= "alter table trade.customers "
        + "ALTER" + (random.nextBoolean()? " column ": " ") + "cid"  
        + " SET INCREMENT BY 1 ";

      if (increment != null) {
        Log.getLogWriter().info(increment);
        conn.createStatement().execute(increment);
      }
      
      //restart
      String restart = null;
      if (startKey != 0 || !setIncrement || random.nextBoolean())
        restart = "alter table trade.customers "
          + "ALTER" + (random.nextBoolean()? " column ": " ") + "cid"  
          + " RESTART WITH " +  (++startKey);
      
      if (restart != null) {
        Log.getLogWriter().info(restart);
        conn.createStatement().execute(restart);
      }
      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_repopulateAfterTruncateTable() {
    sqlTest.repopulateAfterTruncateTable();
  }
  
  protected void repopulateAfterTruncateTable(){
    Connection gConn = getGFEConnection();
    repopulateAfterTruncateTable(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void repopulateAfterTruncateTable(Connection conn) {
    String schema = "trade";
    String[] tables = { "customers", "securities", "networth", "portfolio" ,
        "buyorders", "sellorders", "txhistory"};
    for (String table: tables) {
      if (random.nextBoolean()) importTable(conn, schema, table); 
      else importTableEx(conn, schema, table);
      
      //importTable(conn, schema, table);
    }
  }
  
  protected void importTable(Connection conn, String schema, 
      String table) {
    String importTable = "CALL SYSCS_UTIL.IMPORT_TABLE (?, ?, ?, null, null, null, 0)";
    Log.getLogWriter().info(importTable + " with table " + table);
    try {
      CallableStatement cs = conn.prepareCall(importTable);
      cs.setString(1, schema);
      cs.setString(2, table);
      cs.setString(3, FabricServerHelper.getFabricServerDescription().getSysDir() + "/" + table.toUpperCase() + ".file");
      cs.execute();
    } catch (SQLException se) {
       SQLHelper.handleSQLException(se);
    }
  }
  
  protected void importTableEx(Connection conn, String schema, 
      String table) {
    String importTable = "CALL SYSCS_UTIL.IMPORT_TABLE_EX (?, ?, ?, null, "
        + "null, null, 0, 0, 6, 0, null, null)";
    Log.getLogWriter().info(importTable + " with table " + table);
    try {
      CallableStatement cs = conn.prepareCall(importTable);
      cs.setString(1, schema);
      cs.setString(2, table);
      cs.setString(3, FabricServerHelper.getFabricServerDescription().getSysDir() + "/" + table.toUpperCase() + ".file");
      cs.execute();
    } catch (SQLException se) {
       SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_verifyTicket46046() {
    sqlTest.verifyTicket46046();    
  }
  
  protected void verifyTicket46046() {    
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    verifyTicket46046(dConn, gConn);

    if (dConn!=null) {
      closeDiscConnection(dConn);
      //Log.getLogWriter().info("closed the disc connection");
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done dmlOp");
  }
  
  protected void verifyTicket46046(Connection dConn, Connection gConn) {
    Log.getLogWriter().info("verifying ticket #46046, myTid is " + getMyTid());
    
    try {
      String select = "select cust_name from trade.customers";
      ResultSet rs = gConn.createStatement().executeQuery(select);
      String name = null;
      
      if (rs.next()) {
        name = rs.getString(1); 
        rs.close();
        Log.getLogWriter().info("cust_name is: " + name + ".");
      }
      else throw new TestException("no rows in customers table yet");
      
      select = "select * from trade.customers where cust_name = '" + name.trim() + "'";
      
      Log.getLogWriter().info(select);
      
      ResultSet dRS = dConn.createStatement().executeQuery(select);
      ResultSet sRS = gConn.createStatement().executeQuery(select);
      List<Struct> dList = ResultSetHelper.asList(dRS, true);
      //Log.getLogWriter().info("derby rs is " + ResultSetHelper.listToString(dList));
      List<Struct> sList = ResultSetHelper.asList(sRS, true);
      //Log.getLogWriter().info("gfxd rs is " + ResultSetHelper.listToString(sList));
      ResultSetHelper.compareResultSets(dList, sList);
      SQLHelper.closeResultSet(sRS, gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      commit(dConn);
      commit(gConn);
    } 
  }
  
  protected LogWriter log() {
    return Log.getLogWriter();
  }
  
  public static void HydraTask_createUDTPriceType() {
    sqlTest.createUDTPriceType();
  }
  
  protected void createUDTPriceType() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
      createUDTPriceType(dConn);
    } 
    Connection gConn = getGFEConnection();
    createUDTPriceType(gConn);
    commit(dConn);
    commit(gConn);
  }
  
  protected void createUDTPriceType(Connection conn) {
    String sql = "CREATE TYPE trade.UDTPrice " +
    		"EXTERNAL NAME 'sql.sqlutil.UDTPrice' " +
    		"LANGUAGE JAVA";
    log().info("executing " + sql);
    try {
      conn.createStatement().execute(sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_createUUIDType() {
    sqlTest.createUUIDType();
  }
  
  protected void createUUIDType() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
      createUUIDType(dConn);
    } 
    Connection gConn = getGFEConnection();
    createUUIDType(gConn);
    commit(dConn);
    commit(gConn);
  }
  
  protected void createUUIDType(Connection conn) {
    String sql = "CREATE TYPE trade.UUID " +
        "EXTERNAL NAME 'java.util.UUID' " +
        "LANGUAGE JAVA";
    log().info("executing " + sql);
    try {
      conn.createStatement().execute(sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_dropCompaniesFK() {
    sqlTest.dropCompaniesFK();
  }
  
  protected void dropCompaniesFK() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    dropCompaniesFK(dConn, gConn);
    commit(dConn);
    commit(gConn);
    
    if (dConn!=null) {
      closeDiscConnection(dConn); //derby connection is reused now
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done drop fk Op"); 
  }
  
  protected void dropCompaniesFK(Connection dConn, Connection gConn) {
    if (dConn != null) {
      log().info("derby executing " );
      dropCompaniesFK(dConn);       
    }
    log().info("gfxd executing ");
    dropCompaniesFK(gConn);      
  }
  
  protected void dropCompaniesFK(Connection conn) { 
    String sql = "alter table trade.companies " +
    "drop " + (random.nextBoolean()? "foreign key" : "constraint") + " comp_fk ";
    try {
      log().info("executing " + sql);
      conn.createStatement().execute(sql);      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_addCompaniesFK() {
    if (ticket46803fixed) sqlTest.addCompaniesFK();
  }
  
  protected void addCompaniesFK() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    addCompaniesFK(dConn, gConn);
    commit(dConn);
    commit(gConn);
    
    if (dConn!=null) {
      closeDiscConnection(dConn); //derby connection is reused now
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done add fk Op"); 
  }
  
  protected void addCompaniesFK(Connection dConn, Connection gConn) {
    String sql = "alter table trade.companies " +
    "add constraint comp_fk foreign key (symbol, exchange) references trade.securities (symbol, exchange) on delete restrict";
   
    boolean rollback = false;
    try {
      if (dConn != null) {
        log().info("derby executing " + sql);
        dConn.createStatement().execute(sql);
      }
      log().info("gfxd executing " + sql);
      gConn.createStatement().execute(sql);
    } catch (SQLException se) {
      if ((se.getSQLState().equalsIgnoreCase("42ZB6") || se.getSQLState().equalsIgnoreCase("0A000")) 
          && isPartitionedOnSubsetOfSecuritiesUniqueKey()) {
        log().info("could not add foreign key constraint when parent is " +
        		"partitioned on the subset of unique key columns, roll back this op");
        rollback = true;
      } else if (se.getSQLState().equalsIgnoreCase("X0Y45") && setTx && dConn == null) {
        log().info("could not add fk constarint using txn without derby server");
        //this caused by insert into parent row failed due to conflict exception
        //but insert into child row (companies table) succeeded, 
        //and cause the adding back fk to fail
        //with derby we setUniqueKeys to true and avoids this conflict exception
        //when eviction or critical heap is used, we will handle it again.
        return;
      } else SQLHelper.handleSQLException(se);
    }
    if (rollback) {
      try {
        if (dConn != null) {
          log().info("derby rollback " + sql);
          dConn.rollback();
        }
        //log().info("gfxd rollback " + sql);
        //gConn.rollback(); //not needed as the op was failed.
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }   
  }
  
  @SuppressWarnings("unchecked")
  protected boolean isPartitionedOnSubsetOfSecuritiesUniqueKey(){
    ArrayList<String>  secPartition = (ArrayList<String>) SQLBB.getBB().
      getSharedMap().get("securitiesPartition");
    if (secPartition.contains("symbol") || secPartition.contains("exchange")) return true;
    else return false;    
  }
  
  public static void hydraTask_createUDTPriceFunctions() {
    sqlTest.createUDTPriceFunctions();  
  }
  
  protected void createUDTPriceFunctions() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    createUDTPriceFunction(dConn, gConn);
    commit(dConn);
    commit(gConn);
    
    if (dConn!=null) {
      closeDiscConnection(dConn); //derby connection is reused now
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done create function for UDTPrice"); 
  }
  
  protected void createUDTPriceFunction(Connection dConn, Connection gConn) {  
    if (dConn != null) {
      log().info("derby executing");
      createUDTPriceFunction(dConn);       
    }
    log().info("gfxd executing");
    createUDTPriceFunction(gConn);
  }
  
  protected void createUDTPriceFunction(Connection conn) {

    String getLowPrice = "create function trade.getLowPrice(DP1 trade.UDTPrice) " +
    "RETURNS NUMERIC " +
    "PARAMETER STYLE JAVA " +
    "LANGUAGE JAVA " +
    "NO SQL " +
    "EXTERNAL NAME 'sql.sqlutil.UDTPrice.getLowPrice'";
    
    String getHighPrice = "create function trade.getHighPrice(DP1 trade.UDTPrice) " +
    "RETURNS NUMERIC " +
    "PARAMETER STYLE JAVA " +
    "LANGUAGE JAVA " +
    "NO SQL " +
    "EXTERNAL NAME 'sql.sqlutil.UDTPrice.getHighPrice'";

    try {
      log().info("xecuting " + getLowPrice);
      executeStatement(conn, getLowPrice);          
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    try {
      log().info("executing " + getHighPrice);
      executeStatement(conn, getHighPrice);      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void executeStatement(Connection conn, String sql) throws SQLException {
    conn.createStatement().execute(sql);
  }
  
  public static void HydraTask_createIndexOnCompanies() {
    sqlTest.createIndexOnCompanies();
  }

  protected void createIndexOnCompanies() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    createIndexOnCompanies(dConn, gConn);
    commit(dConn);
    commit(gConn);
    
    if (dConn != null) {
      try {
        listIndexCreated(dConn, "trade", "companies");
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }
    
    try {
      listIndexCreated(gConn, "trade", "companies");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    commit(dConn);
    commit(gConn);
    
    closeGFEConnection(gConn);
    closeDiscConnection(dConn);
  }

  protected void createIndexOnCompanies(Connection dConn, Connection gConn) {
    String sql = "create index trade.indexcompaniestid on trade.companies (tid)";
    try {
      log().info(sql);
      if (dConn != null) executeStatement(dConn, sql);
      executeStatement(gConn, sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_createCompaniesTable() {
    sqlTest.createCompaniesTable();
  }
  
  protected void createCompaniesTable() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    createCompaniesTable(dConn, gConn);
    commit(dConn);
    commit(gConn);
    closeGFEConnection(gConn);
    closeDiscConnection(dConn);
  }
  
  protected void createCompaniesTable(Connection dConn, Connection gConn) {
    String sql = "create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange), constraint comp_fk foreign key (symbol, exchange) references trade.securities (symbol, exchange) on delete restrict)" ;
    try {
      log().info(sql);
      if (dConn != null) executeStatement(dConn, sql);      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    boolean reproduce51726 = false;
    //temp avoid this so that tests with companies table can be run
    if (reproduce51726) {
      String wrongPartition = " partition by range (symbol) (VALUES BETWEEN 'a' AND 'd', " +
      		"VALUES BETWEEN 'd' AND 'i', VALUES BETWEEN 'i' AND 'k', VALUES BETWEEN 'k' AND 'o', " +
      		"VALUES BETWEEN 'n' AND 'r', VALUES BETWEEN 'r' AND 'u', " +
      		"VALUES BETWEEN 'u' AND'zzzzzzz')";
      try {
        log().info("in gfxd executing " + sql + wrongPartition);
        executeStatement(gConn, sql + wrongPartition);
      } catch (SQLException se) {
        if (se.getSQLState().equals("22003")) {
          log().info("got expected exception for range partitioning");
        } else SQLHelper.handleSQLException(se);
      }
    }
    
    String companiesPartition = " ";
    if (testPartitionBy) {
      companiesPartition = getTablePartition(TestConfig.tab().stringAt(SQLPrms.companiesTableDDLExtension, "trade.companies:random"));
    }
    
    String companiesDDLExtension = companiesPartition + 
      getRedundancyClause(companiesPartition, TestConfig.tab().stringAt(SQLPrms.companiesTableRedundancy, " ")) +
          getCompaniesPersistence();
    
    if (enableConcurrencyCheck) {
      companiesDDLExtension += ENABLECONCURRENCYCHECKS;  
    }
      
    sql += hasAsyncDBSync? getCompaniesAsyncEventListenerExtension(companiesDDLExtension) : companiesDDLExtension; 
    
    try {
      log().info("in gfxd executing " + sql);
      executeStatement(gConn, sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    if (dConn != null) createDerbyIndexOnCompanies(dConn);
  }
  
  protected String getCompaniesAsyncEventListenerExtension(String gfeDDL) {
    if (hasAsyncDBSync) {
      String ddl = gfeDDL.toUpperCase();
      if (ddl.contains("REPLICATE")) {
        if (ddl.contains("SERVER GROUPS") && !ddl.contains(sgDBSync)) {
          int index = ddl.indexOf("SERVER GROUPS");
          StringBuffer start = new StringBuffer((gfeDDL.substring(0, index-1)));
          StringBuffer sb = new StringBuffer(gfeDDL.substring(index));
          sb.insert(sb.indexOf("SG"), sgDBSync + ",");
          gfeDDL = (start.append(sb)).toString();
        }

        if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
            && !((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
            equalsIgnoreCase("default")) {
          gfeDDL += "SERVER GROUPS (" + sgDBSync + "," +
            SQLBB.getBB().getSharedMap().get(tradeSchemaSG) + ")";
        }  else if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
            && ((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
            equalsIgnoreCase("default")) {
          gfeDDL += "SERVER GROUPS (" + sgDBSync + ",SG1,SG2,SG3,SG4)";
        }//inherit from schema server group
      } //handle replicate tables for sg
      if (hasAsyncEventListener) {
        throw new TestException("Test issue: Trade.Companies does not implement AsyncEventListen yet");
      } else {
        gfeDDL += "AsyncEventListener(" + asyncDBSyncId + ")" ;
      }
    }
    return gfeDDL;
  }
  
  protected static String getCompaniesPersistence() {
    String persistCompanies = TestConfig.tab().stringAt(SQLPrms.gfeCompaniesPersistExtension, "");
    return persistCompanies;
  }
  
  protected void addSecuritiesUniqConstraint(Connection gConn) {
    String sql = "alter table trade.securities add CONSTRAINT sec_uq unique (symbol, exchange)";
    try {
      log().info(sql);
      executeStatement(gConn, sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected int getMyWanSite() {
    if (isWanTest) {
      return sql.wan.WanTest.myWanSite;
    } else return -1;
  }
  
  public static synchronized void HydraTask_createOracleDBSynchronizer() throws SQLException {
    sqlTest.createOracleDBSynchronizer();
  }
  
  protected void createOracleDBSynchronizer() throws SQLException {
    Connection conn = getGFEConnection();;
    createOracleDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }
    
  protected void createOracleDBSynchronizer(Connection conn) {
    String oracle_init = "oracle.jdbc.OracleDriver,jdbc:oracle:thin:trade/"
    +oraclePassword+"@w1-gst-dev29.gemstone.com:1521:wdc11";
    boolean enablePersistence = true; 
    createNewDBSynchronizer(conn, oracle_init, enablePersistence);
  }
  
  protected Connection getSystemOracleConnection() throws SQLException {
    System.setProperty("java.security.egd", "file:///dev/urandom");
    QueryUtil.loadDriver(QueryUtil.oracleDriver());
    String url = "jdbc:oracle:thin:"
      + "system/apple@" 
      + getDBServer()
      + ":1521:" + getDBName();
    Log.getLogWriter().info("Connecting to " + url);
    Connection conn = DriverManager.getConnection(url);
    conn.setAutoCommit(false);
    return conn;
  }
  
  protected Connection getOracleConnection() throws SQLException {
    System.setProperty("java.security.egd", "file:///dev/urandom");
    QueryUtil.loadDriver(QueryUtil.oracleDriver());
    String url = "jdbc:oracle:thin:"
      + "trade/"+oraclePassword+"@"
      + getDBServer()
      + ":1521:" + getDBName();
    Log.getLogWriter().info("Connecting to " + url);
    Connection conn = DriverManager.getConnection(url);
    conn.setAutoCommit(false);
    return conn;
  }
  
  protected String getDBServer() {
    //only one instance for now
    return "w2-gst-dev29.gemstone.com";
  }
  
  protected String getDBName() {
    return "wdc11";
  }
  
  public static void HydraTask_cleanupOracleRun() throws SQLException {
    if (sqlTest == null) {
      sqlTest = new SQLTest();
    }
    sqlTest.cleanupOracleRun();
  }
  
  protected void cleanupOracleRun() throws SQLException {
    Connection oracleConn = getSystemOracleConnection();
    cleanupOracleRun(oracleConn);
    closeConnection(oracleConn);
  }
  
  protected void closeConnection(Connection conn) {
    try {
      conn.close();
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to release the connection " + TestHelper.getStackTrace(e));
    }
  }
  
  protected void cleanupOracleRun(Connection conn) throws SQLException {
    Connection tradeconn = getOracleConnection(); 
    //to get connection using the specific password
    //if no exception thrown it means there should not be other concurrent tests running using the same user/schema   
    closeConnection(tradeconn);
    
    Statement st = conn.createStatement();    
    String sql = "select sid, serial# from v$session where username ='trade'";
    log().info(sql);
    ResultSet rs = st.executeQuery(sql);
    
    List<Struct> rsList = ResultSetHelper.asList(rs, false);   
    Object[] values;
    String session = null;
    for (Struct row: rsList) {
      values = row.getFieldValues();
      session = values[0].toString() + "," +values[1].toString();     
      log().info("session is " + session);
      if (session !=null) {
        sql = "alter system kill session '" + session + "'";
        log().info(sql);
        st.execute(sql);
      }
    }   

    sql = "drop user trade cascade";
    log().info(sql);
    st.execute(sql);
    
    sql = "drop user emp cascade";
    log().info(sql);
    st.execute(sql);
    
    sql = "drop user default1 cascade";
    log().info(sql);
    st.execute(sql);    
  }  
  
  protected void dropOracleUsers(Connection conn) throws SQLException {
    try {
      Connection tradeconn = getOracleConnection(); 
      //to get connection using the specific password
      //if no exception thrown it means there should not be other concurrent tests running using the same user/schema   
      closeConnection(tradeconn);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getMessage().contains("ORA-01017")) {
        Log.getLogWriter().info("invalid user/password," +
        		" either others are running the test, or the previous run" +
        		" was successful. continue testing");
        return;
      }
    }
    
    String sql;
    try {
      Statement st = conn.createStatement();    
      sql = "drop user trade cascade";
      log().info(sql);
      st.execute(sql);
      
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getMessage().contains("ORA-01918")) {
        Log.getLogWriter().info("user trade has been dropped, continue testing");
      }
    }
    
    try { 
      Statement st = conn.createStatement();  
      sql = "drop user emp cascade";
      log().info(sql);
      st.execute(sql);

    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getMessage().contains("ORA-01918")) {
        Log.getLogWriter().info("user trade has been dropped, continue testing");
      }
    }
    
    try {    
      Statement st = conn.createStatement();  
      sql = "drop user default1 cascade";
      log().info(sql);
      st.execute(sql);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getMessage().contains("ORA-01918")) {
        Log.getLogWriter().info("user trade has been dropped, continue testing");
      }
    }
  }
  
  public static void HydraTask_dropOracleUsers() throws SQLException {
    if (sqlTest == null) {
      sqlTest = new SQLTest();
    }
    sqlTest.dropOracleUsers();
  }
  
  protected void dropOracleUsers() throws SQLException {
    Connection oracleConn = getSystemOracleConnection();
    dropOracleUsers(oracleConn);
    closeConnection(oracleConn);
  }
  
  public static void HydraTask_createOracleUsers() throws SQLException{
    sqlTest.createOracleUsers();
  }
  
  protected void createOracleUsers() throws SQLException {
    Connection conn = getSystemOracleConnection(); 
    createOracleUsers(conn);
    closeConnection(conn);
  }
  
  protected void createOracleUsers(Connection conn) throws SQLException {
    Statement st = conn.createStatement(); 
    
    String sql = "CREATE USER trade IDENTIFIED BY " + oraclePassword;
    log().info(sql);
    st.execute(sql);
    
    sql = "CREATE USER emp IDENTIFIED BY " + oraclePassword;
    log().info(sql);
    st.execute(sql);
    
    sql = "CREATE USER default1 IDENTIFIED BY " + oraclePassword;
    log().info(sql);
    st.execute(sql);
    
    sql = "GRANT UNLIMITED TABLESPACE TO trade";
    log().info(sql);
    st.execute(sql);
    
    sql = "GRANT UNLIMITED TABLESPACE TO emp";
    log().info(sql);
    st.execute(sql);
    
    sql = "GRANT UNLIMITED TABLESPACE TO default1";
    log().info(sql);
    st.execute(sql);
    
    sql = "GRANT all privileges TO trade";
    log().info(sql);
    st.execute(sql);
    
    conn.commit();
    
  }
  
  public static void HydraTask_createOracleTables() throws SQLException {
    sqlTest.createOracleTables();
  }
  
  protected void createOracleTables() throws SQLException {
    Connection conn = getSystemOracleConnection(); 
    createOracleTables(conn);
    closeConnection(conn);
  }
  
  protected void createOracleTables(Connection conn) throws SQLException {
    Statement s = conn.createStatement(); 
    
    String[] tables = SQLPrms.getCreateTablesStatements(true);
    String sql;
    
    for (int i =0; i<tables.length; i++) {
      sql = tables[i];
      sql = sql.replaceAll("on delete restrict", "");
      Log.getLogWriter().info("about to create table in oracle: " + sql);
      s.execute(sql);
      
      if (sql.contains("create table emp.department")) {
        sql = "GRANT all on emp.department TO trade";
        log().info(sql);
        s.execute(sql);
      } else if (sql.contains("create table emp.employees")) {
        sql = "GRANT all on emp.employees TO trade";
        log().info(sql);
        s.execute(sql);
      } else if (sql.contains("create table default1.employees")) {
        sql = "GRANT all on default1.employees TO trade";
        log().info(sql);
        s.execute(sql);
      } 
      
    }

    conn.commit();   
  }
  
  public static void HydraTask_verifyResultSetsOracleDBSynchronizer() throws SQLException{
    sqlTest.verifyResultSetsOracleDBSynchronizer();
  }

  protected void verifyResultSetsOracleDBSynchronizer() throws SQLException {
    Connection oraConn = getOracleConnection();
    Connection gConn = getGFEConnection();
    verifyResultSets(oraConn, gConn);
    closeConnection(oraConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_stopOracleDBSynchronizer() throws SQLException{
    if (sqlTest == null) {
      sqlTest = new SQLTest();
    }
    sqlTest.stopOracleDBSynchronizer();
  }

  protected void stopOracleDBSynchronizer() throws SQLException {
    Connection gConn = getGFEConnection();
    stopOracleDBSynchronizer(gConn);
    closeGFEConnection(gConn);
  }
  
  protected void stopOracleDBSynchronizer(Connection conn) throws SQLException {
    String stopDBSynchronizer = "call SYS.STOP_ASYNC_EVENT_LISTENER( ? )";
    CallableStatement cs = conn.prepareCall(stopDBSynchronizer);
    cs.setString(1, asyncDBSyncId.toUpperCase());
    cs.execute();
    Log.getLogWriter().info(stopDBSynchronizer + " for " + asyncDBSyncId.toUpperCase());
  }
  
  public static synchronized void HydraTask_createDuplicateTables() {
      sqlTest.createDuplicateTables();
      SQLPrms.setTriggerStmtInMap();
      sqlTest.createTriggers();
  }
  
  protected void createDuplicateTables() {
    Connection gConn = getGFEConnection();
    String[] fullsetTablecreateStmt = SQLPrms.prepareFullSetTableCreateStmt();
    SQLPrms.setTriggerStmtInMap();
    try {
      Statement s = gConn.createStatement();
      for (int i = 0; i < fullsetTablecreateStmt.length; i++) {
        Log.getLogWriter().info("Creating full data set duplicate table :" + fullsetTablecreateStmt[i]);
        s.execute(fullsetTablecreateStmt[i]);
      }
      s.close();
      commit(gConn);
    }
    catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to create Duplicate tables\n" + TestHelper.getStackTrace(se));
    }
    closeGFEConnection(gConn);
  }
    
  public static synchronized void HydraTask_createMRTables() {
    sqlTest.createMRTables();
  }
  
  protected void createMRTables() {
    Connection gConn = getGFEConnection();
    String[] fullsetTablecreateStmt = SQLPrms.prepareFullSetTableCreateStmt();
    try {
      Statement s = gConn.createStatement();
      for (int i = 0; i < fullsetTablecreateStmt.length; i++) {        
        //replace fulldataset with HDFS
        fullsetTablecreateStmt[i] = fullsetTablecreateStmt[i].replace("fulldataset", "HDFS");   
        //remove hdfsstore from mr table
        fullsetTablecreateStmt[i] = fullsetTablecreateStmt[i].toUpperCase().replaceAll(" HDFSSTORE *" , " ");
        Log.getLogWriter().info("Creating MR  table :" + fullsetTablecreateStmt[i]);
        s.execute(fullsetTablecreateStmt[i]);
      }
      s.close();
      commit(gConn);
    }
    catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to create Duplicate tables\n" + TestHelper.getStackTrace(se));
    }
    closeGFEConnection(gConn);
  }
  public static synchronized void HydraTask_createTriggerOnTables() {
    sqlTest.createTriggers();
  }
  
  protected void createTriggers() {
    Connection gConn = getGFEConnection();    
    String[] triggerCreateStmt = SQLPrms.prepareTriggerStmt();
    try {
      Statement s = gConn.createStatement();
      for (int i = 0; i < triggerCreateStmt.length; i++) {
        Log.getLogWriter().info("Executing... " + triggerCreateStmt[i]);
        s.execute(triggerCreateStmt[i]);
      }
      s.close();
      commit(gConn);      
    }
    catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to create trigger " +  TestHelper.getStackTrace(se));
    }
    closeGFEConnection(gConn);
  }
  
  public static synchronized void HydraTask_setTriggerObserver() {
    
    GemFireXDQueryObserverHolder.setInstance(new TriggerQueryObserver());
    
  }
  protected  void dropTriggers() {
    Log.getLogWriter().info("function dropTriggers Started" );
    ArrayList<String> triggerNames = (ArrayList<String>)SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_TRIGGER);
    
    Connection gConn = getGFEConnection();
    Iterator triggerNameiterator = triggerNames.iterator();
    
    try {
      Statement s = gConn.createStatement();
      while (triggerNameiterator.hasNext()) {
        String stmt = "DROP TRIGGER  IF EXISTS " + triggerNameiterator.next();
        Log.getLogWriter().info("Executing... " + stmt);
        s.execute(stmt);
      }
      s.close();
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("Not able to drop trigger " + TestHelper.getStackTrace(se));
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("function dropTriggers Completed" );
  }

  public static void HydraTask_verifyTotalRowsinTables() {
    hdfsSqlTest.verifyTotalRowsinTables();
  }

  public static void HydraTask_executeSubSelectInPutOnEmptyTable() {
    
    sqlTest.dropTriggers();
    sqlTest.truncateDuplicateTables(DUPLICATE_TABLE_SUFFIX);    
    sqlTest.executeSubSelectInPut(true);    
    Log.getLogWriter().info("executeSubSelectInPut completed on Empty tables. " );       
  }
  
  
  public static void HydraTask_executeSubSelectInPutOnTableWithData() {
    sqlTest.dropConstraint();    
    sqlTest.executeSubSelectInPut(false);
    Log.getLogWriter().info("executeSubSelectInPut completed on tables with Data. " );       
  }
  
  
 protected  void executeSubSelectInPut(boolean onEmptyTable){
    String[] tableNames = SQLPrms.getTableNames();
        
    for (String tableName : tableNames  ){
      // data will be duplicated in the txhistory as it does not contain any PK, if this table is not empty
      if (!tableName.equalsIgnoreCase("trade.txhistory" ) || (tableName.equalsIgnoreCase("trade.txhistory" ) && onEmptyTable)){
      executePutOnTable(tableName);
      }
    }
  }  
 
 
 protected void executePutOnTable(String tableName){
   Connection gConn = getGFEConnection();
   String putUpdate =" ";
   try{
     Statement s = gConn.createStatement();
     putUpdate =  "PUT INTO " + tableName + DUPLICATE_TABLE_SUFFIX + " SELECT * FROM " + tableName  + "-- GEMFIREXD-PROPERTIES queryHDFS=true ";
     Log.getLogWriter().info("Executing ...." + putUpdate);
     s.executeUpdate(putUpdate);
     Log.getLogWriter().info("Execution  completed on table " +  tableName);
     
   }catch (SQLException se) {
     SQLHelper.printSQLException(se);
     throw new TestException("Not able to execute query : " +  putUpdate + "\n Exception : " + TestHelper.getStackTrace(se));
     }   
   closeGFEConnection(gConn);
 }
 
 
 protected void dropConstraint(){
   Connection gConn = getGFEConnection();
   try{
     String constraintDropCmd =  " select 'alter table ' ||  TABLESCHEMANAME || '.' || TABLENAME  || ' drop constraint ' || CONSTRAINTNAME  as constraintToDrop from sys.systables t , sys.SYSCONSTRAINTS c where t.TABLEID = c.tableID AND (C.TYPE ='U' OR C.TYPE ='F')";
     Statement s = gConn.createStatement();
     ResultSet rs = s.executeQuery(constraintDropCmd);     
     while( rs.next() ){
       try{
       Log.getLogWriter().info ("Executing..." + rs.getString("constraintToDrop"));
       Statement executeStmt = gConn.createStatement();
       executeStmt.execute(rs.getString("constraintToDrop"));
     } catch (SQLException se) {
          SQLHelper.printSQLException(se);
       }
     }
     SQLHelper.closeResultSet(rs, gConn);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
    }

  }
 
  protected void setTraceFlags(boolean on) {
    if (ddlThread != RemoteTestModule.getCurrentThread().getThreadId()) return; 
    //only tests initialized ddlThread will run this method
    try {
      String traceFlags = SQLPrms.getTraceFlags();
      Connection conn = getConnectionSetTrace();
      PreparedStatement ps = conn.prepareStatement("call sys.SET_TRACE_FLAG(?,?)");
      String[] traces = traceFlags.split(",");
      for (String trace : traces) {
        ps.setString(1, trace);
        ps.setBoolean(2, on ? Boolean.TRUE : Boolean.FALSE);
        ps.execute();
        Log.getLogWriter().info( (on ? "Enabled" : "Disabled") + trace + " trace");
      }
      
    } catch(Throwable t) {
      //throw new HydraRuntimeException("HydraTask_setTraceFlagsTask exception:", t);
    }
  }

  protected static List<String> getHdfsTables() {
    Map<String, String> hdfsExtnMap = (Map<String, String>) SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_EXTN_PARAMS);
    List<String> hdfsTables = new ArrayList();
    for (int i = 0; i < tables.length; i++) {
      String table = tables[i];
      String hdfsSchemaTable = table.toUpperCase();
      if (hdfsExtnMap.get(hdfsSchemaTable + HDFSSqlTest.STORENAME) == null) {
        Log.getLogWriter().info("skipping " + table + " since it is not configured with HDFS");
      } else { 
        hdfsTables.add(table);
      }
    }
    return hdfsTables;
  }

  public static void HydraTask_flushQueuesHDFS() {
    if ( useRandomConfHdfsStore ) 
      return;
    List<String> hdfsTables = getHdfsTables();
    List<Thread> flushThreads = new ArrayList();
    for (String table : hdfsTables) {
      flushThreads.add(sqlTest.flushQueuesHDFS(table));
    }
    // join flush threads (one per table) 
    for (Thread fthread : flushThreads) {
      try {
        fthread.join(maxResultWaitSec-60);
      } catch (InterruptedException e) {
        throw new TestException("flushQueuesHFDS caught " + e + " " + TestHelper.getStackTrace(e));
      }
    }
    Log.getLogWriter().info("all threads have completed flushQueuesHDFS");
    // verify that the queues are empty at this point
    // note that we should not do this for TASKS (since other work may be going on and filling the queue) 
    String taskType = RemoteTestModule.getCurrentThread().getCurrentTask().getTaskTypeString();
    if (!taskType.equalsIgnoreCase("TASK")) {
      try {
        GemFireCacheImpl cache = (GemFireCacheImpl)Misc.getGemFireCache();
        Set<AsyncEventQueue> queues = cache.getAsyncEventQueues();
        for (AsyncEventQueue queue: queues) {
          AsyncEventQueueImpl queueImpl = (AsyncEventQueueImpl)queue;
          SerialGatewaySenderImpl sender = (SerialGatewaySenderImpl)queueImpl.getSender();
          String regionQueueName = sender.getQueue().getRegion().getName();
          if (sender.getQueue().size() != 0)  {
            throw new TestException("Expected HDFS AEQ to be empty for " + regionQueueName + " but found " + sender.getQueue().size() + " entries");
          }
        }
      } catch (CacheClosedException cce) {
        Log.getLogWriter().info("thin clients cannot verify AEQ sizes, continuing ...");
      }
    }
  }

  public Thread flushQueuesHDFS(final String tableName) {
    final Connection conn = getGFEConnection();
    String threadName = "HDFS Queue Flush for " + tableName;
    Thread thr = new Thread(new Runnable() {
      public void run() {
        long startTime;
        try {
          // spawn a worker thread for each table
          int timeToWait = maxResultWaitSec - 60;
          PreparedStatement ps = conn.prepareStatement("call sys.HDFS_FLUSH_QUEUE(?,?)");
          ps.setString(1, tableName);
          ps.setInt(2, timeToWait);
          Log.getLogWriter().info("Invoking HDFS_FLUSH_QUEUE for " + tableName);
          startTime = System.currentTimeMillis();
          ps.execute();
        } catch(Throwable t) {
          throw new TestException("Unexpected Exception thrown while invoking HDFS_FLUSH_QUEUE for " + tableName + ", " + t);
        }
        long timeToRun = System.currentTimeMillis() - startTime;
        Log.getLogWriter().info("Completed HDFS_FLUSH_QUEUE for " + tableName + ", flush took " + timeToRun  + "ms");
      }
    }, threadName);
    thr.start();
    return thr;
  }

  public static void HydraTask_forceCompactionHDFS() {
    List<String> hdfsTables = getHdfsTables();
    List<Thread> compactionThreads = new ArrayList();
    for (String table : hdfsTables) {
      compactionThreads.add(sqlTest.forceCompactionHDFS(table));
    }
    // join all threads (for all tables) before returning
    for (Thread cthread : compactionThreads) {
      try {
        cthread.join(maxResultWaitSec-60);
      } catch (InterruptedException e) {
        throw new TestException("forceCompactionHDFS caught " + e + " " + TestHelper.getStackTrace(e));
      }
    }
    Log.getLogWriter().info("all threads have completed forceCompactionsHDFS");
  }

  public Thread forceCompactionHDFS(final String tableName) {
    String threadName = "HDFS Force Compaction Thread for " + tableName;
    final Connection conn = getGFEConnection();
    Thread thr = new Thread(new Runnable() {
      public void run() {
        long startTime;
        try {
          // spawn a worker thread for each table
          int timeToWait = maxResultWaitSec - 60;
          PreparedStatement ps = conn.prepareStatement("call sys.HDFS_FORCE_COMPACTION(?,?)");
          ps.setString(1, tableName);
          ps.setInt(2, timeToWait);
          Log.getLogWriter().info("Invoking HDFS_FORCE_COMPACTION for " + tableName);
          startTime = System.currentTimeMillis();
          ps.execute();
        } catch(Throwable t) {
          throw new TestException("Unexpected Exception thrown while invoking HDFS_FORCE_COMPACTION for " + tableName + ", " + t);
        }
        long timeToRun = System.currentTimeMillis() - startTime;
        Log.getLogWriter().info("Completed HDFS_FORCE_COMPACTION for " + tableName + " major compaction took " + timeToRun + "ms");
      }
    }, threadName);
    thr.start();
    return thr;
  }

  /** Invoke sys.HDFS_FORCE_WRITEONLY_ROLLOVER to make WriteOnly hdfs files 
   *  available to Map/Reduce.
   */
  public static void HydraTask_forceWriteOnlyFileRollover() {
    List<String> hdfsTables = getHdfsTables();
    forceWriteOnlyFileRollover(hdfsTables);
  }
  
  public static void forceWriteOnlyFileRollover(List<String> hdfsTables) {
    forceWriteOnlyFileRollover(hdfsTables, -1);
  }

  public static void forceWriteOnlyFileRollover(List<String> hdfsTables, int minSizeForRollover) {
    List<Thread> rolloverThreads = new ArrayList();
    for (String table : hdfsTables) {
      rolloverThreads.add(sqlTest.forceWriteOnlyFileRollover(table, minSizeForRollover));
    }
    // join rollover threads (one per table) 
    for (Thread rolloverThread : rolloverThreads) {
      try {
        rolloverThread.join(maxResultWaitSec-60);
      } catch (InterruptedException e) {
        throw new TestException("forceWriteOnlyFileRollover caught " + e + " " + TestHelper.getStackTrace(e));
      }
    }
    Log.getLogWriter().info("all threads have completed HDFS_FORCE_WRITEONLY_FILEROLLOVER");
  }

  public Thread forceWriteOnlyFileRollover(final String tableName, final int minSizeForRollover) {
    final Connection conn = getGFXDClientConnection();
    String threadName = "HDFS_FORCE_WRITEONLY_FILEROLLOVER " + tableName;
    Thread thr = new Thread(new Runnable() {
      public void run() {
        long startTime;
        try {
          // spawn a worker thread for each table
          PreparedStatement ps = conn.prepareStatement("call sys.HDFS_FORCE_WRITEONLY_FILEROLLOVER(?,?)");
          ps.setString(1, tableName);
          ps.setInt(2, minSizeForRollover);
          Log.getLogWriter().info("Invoking HDFS_FORCE_WRITEONLY_ROLLOVER for " + tableName);
          startTime = System.currentTimeMillis();
          ps.execute();
        } catch(Throwable t) {
          throw new TestException("Unexpected Exception thrown while invoking HDFS_FORCE_WRITEONLY_FILEROLLOVER for " + tableName + ", " + t);
        }
        long timeToRun = System.currentTimeMillis() - startTime;
        Log.getLogWriter().info("Completed HDFS_FORCE_WRITEONLY_FILEROLLOVER for " + tableName + ", rollover took " + timeToRun  + "ms");
      }
    }, threadName);
    thr.start();
    return thr;
  }

  public static void HydraTask_createHDFSSTORE() {
        hdfsSqlTest.createHdfsStore();
  }

  public static void HydraTask_dropHDFSSTORE(){
    hdfsSqlTest.dropHdfsStore();
  }  

  public static void HydraTask_verifyHdfsOperationalData(){
    if (SQLBB.getBB().getSharedCounters().read(SQLBB.triggerInvocationCounter) > 0 ) {
        Log.getLogWriter().info( "trigger Invoked but not completed " + SQLBB.getBB().getSharedCounters().read(SQLBB.triggerInvocationCounter) + " Times"  );
    }
    
    hdfsSqlTest.verifyHdfsOperationData();
  }  
  
  public static void HydraTask_verifyHdfsNonOperationData(){
    hdfsSqlTest.verifyHdfsNonOperationData();
  }
  
  public static void HydraTask_setHDFSEvictionObserver(){
    hdfsSqlTest.setHDFSEvictionObserver();
  }
  
  public static void HydraTask_executeMR(){
    hdfsSqlTest.setMapReduceClassName();
    hdfsSqlTest.executeMR();
  }  

  public static void HydraTask_verifyHdfsDataUsingMR(){
    hdfsSqlTest.verifyHdfsDataUsingMR();
  }
  
  public static void HydraTask_forceCompaction(){
    if ( useRandomConfHdfsStore ) 
              return;
             

    sqlTest.forceCompaction();
  }
  
  public static void HydraTask_alterEvictionFrequency(){
    hdfsSqlTest.alterEvictionFrequency();
  }
  protected synchronized void forceCompaction(){
    if(!forceCompaction) return;
    Log.getLogWriter().info("inside force compaction" );
    GemFireCacheImpl cacheImpl = (GemFireCacheImpl) Misc.getGemFireCache();
    if (cacheImpl != null && !cacheImpl.isClosed()) {
      for (DiskStoreImpl store : cacheImpl.listDiskStoresIncludingRegionOwned()) {
        Log.getLogWriter().info("starting force compaction for " + store );
        boolean status = store.forceCompaction();
        Log.getLogWriter().info("force compaction for " + store + (status ? " done " : " not executed ") );
      }
    }
  }
  
  public void executeListener(String event, String listenerName) {
    Log.getLogWriter().info("Listeners are : "  + listeners  + " ListenerName : " + listenerName);
    for(CallBackListener listener : listeners) {
      if(listenerName.equals(listener.getName())) {
        listener.execute(event);
      }
    }
  }
  
  protected void addListener(CallBackListener callBackListener) {
    if(SQLPrms.isMBeanTest()) {
      Log.getLogWriter().info("Adding Listener : " + callBackListener);
      listeners.add(callBackListener);
    }
  }
  
  public static void HydraTask_dumpHDFSTable() {
    String wanId = "";
    if (isWanTest) {
      if (hdfsSqlTest != null) {
        wanId = hdfsSqlTest.getMyWanSite() + "";
      }
      else {
        Log.getLogWriter().info("HydraTask_dumpHDFSTable: hdfsSqlTest is null");
        return;
      }
    }
    Log.getLogWriter().info(
        "HydraTask_dumpHDFSTable: will only be executed once "
            + "i.e. from \"client1\" or \"accessor1\". Currently called from: "
            + RemoteTestModule.getMyClientName());
    if (RemoteTestModule.getMyClientName().contains("client1")
        || RemoteTestModule.getMyClientName().contains("accessor1")) {
      HDFSSqlTest.dumpHDFSTable(wanId);
    }
  }
  
  public static void HydraTask_verifyUniqeIndexData() {
    if (!testMultipleUniqueIndex) {
      return;
    }
    sqlTest.verifyUniqeIndexData();
  }
            
  // we need to select data on uniq index fields with hdfs query hint and without hdfs query hint    
  protected synchronized void verifyUniqeIndexData(){          
    Connection gConn = getGFEConnection(); 
    Connection dConn = getDiscConnection(); 
    
    List<com.gemstone.gemfire.cache.query.Struct> gfxdResult,derbyResult; 
     
    gfxdResult= getResultSet(gConn,"select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=true \n where oid > 0 and cid > 0  ");       
    derbyResult= getResultSet(dConn,"select * from trade.txhistory  where oid > 0 and cid > 0"); 
    Log.getLogWriter().info("Comparing Derby and sqlf Data for query - select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=true \n where oid > 0 and cid >0 " ); 
    ResultSetHelper.compareResultSets(derbyResult, gfxdResult);      
     
    gfxdResult=getResultSet(gConn,"select * from trade.txhistory  -- SQLFIRE-PROPERTIES queryHDFS=false \n where oid > 0 and cid > 0 "); 
    derbyResult= getResultSet(dConn,"select * from trade.txhistory  where oid > 0 and cid  > 0 "); 
    Log.getLogWriter().info("Comparing Derby and sqlf Data for query - select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=false \n where oid > 0 and cid > 0 " ); 
    ResultSetHelper.compareResultSets(derbyResult, gfxdResult); 
     
    gfxdResult=getResultSet(gConn,"select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=true \n where cid > 0  and sid > 0");       
    derbyResult= getResultSet(dConn,"select * from trade.txhistory  where cid > 0 and sid >0 "); 
    Log.getLogWriter().info("Comparing Derby and sqlf Data for query - select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=true \n where cid > 0 and sid >0 " ); 
    ResultSetHelper.compareResultSets(derbyResult, gfxdResult); 
     
     
    gfxdResult= getResultSet(gConn,"select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=false \n where cid > 0 and sid >0 ");   
    derbyResult= getResultSet(dConn, "select * from trade.txhistory  where cid > 0 and sid >0 "); 
    Log.getLogWriter().info("Comparing Derby and sqlf Data for query - select * from trade.txhistory -- SQLFIRE-PROPERTIES queryHDFS=false \n where cid > 0 and sid >0 " ); 
    ResultSetHelper.compareResultSets(derbyResult, gfxdResult);      
  } 
  
  public static void HydraTask_alterTableSetHdfsStore (){
       sqlTest.alterTableSetHdfsStore("sqlhdfsstore"); 
  }
  
  
  public static void HydraTask_detachHdfsStore (){
    sqlTest.alterTableSetHdfsStore(" "); 
}
  
  protected synchronized void alterTableSetHdfsStore(String storeName){   
    Connection gConn = getGFEConnection();
    ArrayList<String[]> tables = getTableNames(gConn);    
    try {
    for ( String[] table : tables){
        String stmt = "ALTER TABLE " + table[0] + "." + table[1] + " SET HDFSSTORE ("  + storeName + ")" ;  
        Log.getLogWriter().info("Executing  statement " + stmt);
        gConn.createStatement().executeUpdate(stmt);
       }
      } catch (SQLException e) {
        throw new TestException(e.getMessage());      
    }
    
  }
  
  public static void HydraTask_dropColocatedChildTables(){
    sqlTest.dropColocatedChildTables();
  }
  
  protected void dropColocatedChildTables() {
    Connection conn= getGFEConnection();
    String sql = null;
    ArrayList <String>tables = new ArrayList<String>();
    tables.add("trade.txhistory");
    tables.add("trade.buyorders");
    tables.add("trade.sellorders");
    tables.add("trade.portfolio");
    tables.add("trade.networth");
    
    boolean testDropTableIfExists = SQLTest.random.nextBoolean();
    if (testDropTableIfExists)
      sql = "drop table if exists ";
    else
      sql = "drop table ";
    try {
      for (String table: tables) {
        Statement s = conn.createStatement();
        s.execute(sql + table);
      }
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("42Y55") && !testDropTableIfExists) {
        Log.getLogWriter().info("Got expected table not exists exception, continuing tests");
      } else {
        SQLHelper.handleSQLException(se);
      }
    }
  }
  
  public static void HydraTask_addPortfolioV1Table(){
    sqlTest.addPortfolioV1Table();
  }
  
  protected void addPortfolioV1Table() {
    boolean reproduce50116 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50116, true);
    try {
      if (reproduce50116) {
        //create portfoliov1 of portfolio table
        createPortfolioV1Table();
        //select into table
        selectIntoPortfolioV1Table();
        //alter table add blob column
        alterTableAddDataCol();
        //alter table change the child table fk constraint
        alterTableAlterConstraint();
        //drop portfolio table
        dropPortfolioTable();
        //create portfolio view
        createPortfolioView();
      } else {
        //create portfoliov1 of portfolio table
        createPortfolioV1Table();
        //select into table
        selectIntoPortfolioV1Table();
        //alter table add blob column
        alterTableAddDataCol();
        //alter table change the child table fk constraint
        alterTableAlterConstraint();
        //drop portfolio table
        //dropPortfolioTable();
        //create portfolio view
        //createPortfolioView();
      } 
      
      SQLBB.getBB().getSharedMap().put(PORTFOLIOV1READY, true);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
  }
  
  protected void createPortfolioV1Table() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    createPortfolioV1Table(dConn, gConn); 
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void createPortfolioV1Table(Connection dConn, Connection gConn) throws SQLException {
    if (!tableColsSet) setTableCols(gConn); //set table col if not set yet
    boolean reproduce50118 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50118, false);
    //50118 won't be fixed as user could add the table extension explicitly
    
    String str = null;
    if (!reproduce50118) {
      str = createTablePortfolioStmt.replace("trade.portfolio", "trade.portfoliov1") ;
      str = str.replace("portf_pk", "portf_pk_v1");
      str = str.replace("cust_fk", "cust_fk_v1");
      str = str.replace("sec_fk", "sec_fk_v1");
      str = str.replace("qty_ck", "qty_ck_v1");
      str = str.replace("avail_ch", "avail_ch_v1");
    }
    
    String createPortfoliov1Stmt = "create table trade.portfoliov1 (cid int not null, sid int not null, qty int not null, availQty int not null, subTotal decimal(30,20), tid int, constraint portf_pk_v1 primary key (cid, sid), constraint cust_fk_v1 foreign key (cid) references trade.customers (cid), constraint sec_fk_v1 foreign key (sid) references trade.securities (sec_id), constraint qty_ck_v1 check (qty>=0), constraint avail_ch_v1 check (availQty>=0 and availQty<=qty))" ;
    String createPortfoliov1 = TestConfig.tab().stringAt(SQLPrms.portfoliov1Statement, createPortfoliov1Stmt);
    String createPortfoliov1UseSelect  = reproduce50118? 
        "create table trade.portfoliov1 as select * from trade.portfolio with no data":
        str ;
    String createDerbyIndexTid = "create index indexportfoliov1tid" + " on trade.portfoliov1 (tid)";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + createPortfoliov1);
      executeStatement(dConn, createPortfoliov1);
      Log.getLogWriter().info("executed in derby: " + createPortfoliov1);
      Log.getLogWriter().info("executing in derby: " + createDerbyIndexTid);
      executeStatement(dConn, createDerbyIndexTid);
      Log.getLogWriter().info("executed in derby: " + createDerbyIndexTid);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + createPortfoliov1UseSelect);
    executeStatement(gConn, createPortfoliov1UseSelect);
    Log.getLogWriter().info("executed in gfxd: " + createPortfoliov1UseSelect);
    if (reproduce50118) {
      String addPrimaryKey = "alter table trade.portfoliov1 add constraint portf_pk_v1 primary key (cid, sid)";
      Log.getLogWriter().info("executing in gfxd: " + addPrimaryKey);
      executeStatement(gConn, addPrimaryKey);
      Log.getLogWriter().info("executed in gfxd: " + addPrimaryKey);
      String addCustFKey = "alter table trade.portfoliov1 add constraint cust_fk_v1 foreign key (cid) references trade.customers (cid)";
      Log.getLogWriter().info("executing in gfxd: " + addCustFKey);
      executeStatement(gConn, addCustFKey);
      Log.getLogWriter().info("executed in gfxd: " + addCustFKey);
      String addSecFKey = "alter table trade.portfoliov1 add constraint sec_fk_v1 foreign key (sid) references trade.securities (sec_id)";
      Log.getLogWriter().info("executing in gfxd: " + addSecFKey);
      executeStatement(gConn, addSecFKey);
      Log.getLogWriter().info("executed in gfxd: " + addSecFKey);
      String addQtyCheck = "alter table trade.portfoliov1 add constraint qty_ck_v1 check (qty>=0)";
      Log.getLogWriter().info("executing in gfxd: " + addQtyCheck);
      executeStatement(gConn, addQtyCheck);
      Log.getLogWriter().info("executed in gfxd: " + addQtyCheck);
      String addAvailQtyCheck = "alter table trade.portfoliov1 add constraint avail_ch_v1 check (availQty>=0 and availQty<=qty)";
      Log.getLogWriter().info("executing in gfxd: " + addAvailQtyCheck);
      executeStatement(gConn, addAvailQtyCheck);
      Log.getLogWriter().info("executed in gfxd: " + addAvailQtyCheck);
    }
    //set table cols for index creation
    setTableCols(gConn, "trade.portfoliov1");
    commit(gConn);
  }
  
  protected void selectIntoPortfolioV1Table() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    selectIntoPortfolioV1Table(dConn, gConn); 
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void selectIntoPortfolioV1Table(Connection dConn, Connection gConn) throws SQLException {
    String sql = "insert into trade.portfoliov1 (select * from trade.portfolio)";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }
  
  
  protected void alterTableAddDataCol() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    alterTableAddDataCol(dConn, gConn); 
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void alterTableAddDataCol(Connection dConn, Connection gConn) throws SQLException {
    String sql = "alter table trade.portfoliov1 add column data blob";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }
  
  protected void alterTableAlterConstraint() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    alterTableAlterConstraint(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void alterTableAlterConstraint(Connection dConn, Connection gConn) throws SQLException {
    String sql = "alter table trade.sellorders drop FOREIGN KEY portf_fk";
    
    String addConstraint =  "alter table trade.sellorders add constraint portf_fk foreign key (cid, sid) references trade.portfoliov1 (cid, sid) on delete restrict";
         
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      Log.getLogWriter().info("executing in derby: " + addConstraint);
      executeStatement(dConn, addConstraint);
      Log.getLogWriter().info("executed in derby: " + addConstraint);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    Log.getLogWriter().info("executing in gfxd: " + addConstraint);
    executeStatement(gConn, addConstraint);
    Log.getLogWriter().info("executed in gfxd: " + addConstraint);
    commit(gConn);
  }
  
  protected void dropPortfolioTable() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    dropPortfolioTable(dConn, gConn); 
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void dropPortfolioTable(Connection dConn, Connection gConn) throws SQLException {
    String derbySql = "drop table trade.portfolio";
    String sql = "drop table " + (random.nextBoolean()? "if exists " :"" ) + "trade.portfolio" ;
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + derbySql);
      executeStatement(dConn, derbySql);
      Log.getLogWriter().info("executed in derby: " + derbySql);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }
  
  protected void createPortfolioView() throws SQLException {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    createPortfolioView(dConn, gConn); 
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void createPortfolioView(Connection dConn, Connection gConn) throws SQLException {
    String sql = "create view trade.portfolio as select cid, sid, qty, availQty, subTotal, tid from trade.portfoliov1" ;
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      commit(dConn);
    } 
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }
  
  /**
   * Copy diskstore directory sourcePath into directory destPath
   *  sourcePath Directory to copy
   *  destPath Directory to copy into
   * @throws IOException If copying failed.
   */
  public static void HydraTask_copyDiskstore() throws IOException {
    String sourcePath = diskCompatibilityDiskStorePath;
    String destPath = TestConfig.getInstance().getClientDescription(RemoteTestModule.getMyClientName())
        .getVmDescription().getHostDescription().getResourceDir();
    Log.getLogWriter().info("copyDir from " + sourcePath + " to " + destPath);
    copyFiles(sourcePath, destPath);
  }
  
 /** Copy files
  * @param srcPath Directory or file to copy from
  * @param destPath Directory or file to copy to
  * @throws IOException If copying failed.
  */
  protected static void copyFiles(String srcPath, String destPath) throws IOException {
    Log.getLogWriter().info("copyFile Start ");
      File src = new File(srcPath);
      File dest = new File(destPath);
      
      if (src.isDirectory()) {
      if(!firstEntry) {
          dest = new File(replaceHostNameFromDestination(dest.getAbsolutePath()));
          dest.mkdirs();
      } else {
        dest.mkdirs();
        firstEntry = false;
      }
          String list[] = src.list();
          for(String strtemp:list)
            Log.getLogWriter().info(strtemp+" - ");
          for (int i = 0; i < list.length; i++) {
              String srcFile = src.getAbsolutePath() + FS + list[i];
              String destFile = dest.getAbsolutePath() + FS + list[i];
              copyFiles(srcFile , destFile);
          }
      } else {  
          copy(src, dest);
     }
  }
  
  private static void copy(File source, File dest) throws IOException {
          FileInputStream src = new FileInputStream(source);
          FileOutputStream dst = new FileOutputStream(dest);
          int c;
          while ((c = src.read()) >= 0)
              dst.write(c);
          src.close();
          dst.close();
  }
  
  private static String replaceHostNameFromDestination(String dest){
    String finalDestHostName = null;
    String finalDestString = null;
    String[] destHostName = null;
    String[] destString = dest.split("/");
    
    if(destString[destString.length - 1].contains("csv"))
      return dest;
    
    if(!destString[destString.length - 1].contains("_disk"))
      destHostName = destString[destString.length - 2].split("_");
    else
      destHostName = destString[destString.length - 1].split("_");
    
    destHostName[destHostName.length - 2] = HostHelper.getIPAddress().getHostName();
    
    for(int i=0; i < destHostName.length; i++) {
      if(i == 0)
        finalDestHostName = destHostName[i];
      else
        finalDestHostName = finalDestHostName + "_" + destHostName[i];
    }
    
    if(!destString[destString.length - 1].contains("_disk")) 
      destString[destString.length -2] = finalDestHostName;
    else
        destString[destString.length -1] = finalDestHostName;
    
    for(int j=0; j < destString.length; j++) {
      if(j == 0)
        finalDestString = destString[j];
      else
        finalDestString = finalDestString + FS + destString[j];
    }
    return finalDestString;
  }
  
  public static void HydraTask_upgradeDiskStore() throws Exception {
    String sysDirName = FabricServerHelper.getFabricServerDescription().getSysDiskDir();
    Log.getLogWriter().info("GemfireDescription SysDirName: " + sysDirName);
    
    runUpgradeDiskStore("SQLF-DEFAULT-DISKSTORE",sysDirName);
    runUpgradeDiskStore("SQLF-DD-DISKSTORE",sysDirName + "/datadictionary");
    if(!sysDirName.contains("_locator")){
        runUpgradeDiskStore("PERSISTCUST",sysDirName + "/persistCust");
        runUpgradeDiskStore("PERSISTPORTF",sysDirName + "/persistPortf");
        runUpgradeDiskStore("PERSISTSELLORDERS",sysDirName + "/persistSellorders");
        runUpgradeDiskStore("PERSISTBUYORDERS",sysDirName + "/persistBuyorders");
        runUpgradeDiskStore("PERSISTNETWORTH",sysDirName + "/persistNetworth");
        runUpgradeDiskStore("PERSISTSECU", sysDirName + "/persistSecu");
        runUpgradeDiskStore("PERSISTTXHISTORY", sysDirName + "/persistTxHistory");
    }
  }
  
  private static void runUpgradeDiskStore(String storeName, String dir) throws Exception {
      Log.getLogWriter().info("Upgrading diskstore " + storeName + " from " + dir );
      HostDescription hd = TestConfig.getInstance()
          .getClientDescription(RemoteTestModule.getMyClientName())
          .getVmDescription().getHostDescription();
      String path = "$GEMFIRE" + File.separator + ".." + File.separator
                  + "product-gfxd" + File.separator + "bin" + File.separator
                  + "gfxd";;
      String cmd = EnvHelper.expandEnvVars(path, hd)
                 + " upgrade-disk-store " + storeName + " " + dir;
      ProcessMgr.fgexec(cmd, 0);
      Log.getLogWriter().info("Issued upgrade-disk-store command on " + storeName); 
  }
  
  public static void HydraTask_importTablesToDerby() {
    sqlTest.importTablesToDerby();
  }
  
  protected void importTablesToDerby(){
    Connection dConn = getDiscConnection();
    importTablesToDerby(dConn);
    closeDiscConnection(dConn);
  }
  
  protected void importTablesToDerby(Connection conn) {
    String schema = "trade";
    String[] tables = { "customers", "securities", "networth", "portfolio" ,
        "buyorders", "sellorders", "txhistory"};
       for (String table: tables){
        importTablesToDerby(conn, schema, table);
       }
        importTablesToDerby(conn, "EMP", "EMPLOYEES");
  }
  
  protected void importTablesToDerby(Connection conn, String schema, 
      String table) {
    String importTable = "CALL SYSCS_UTIL.SYSCS_IMPORT_TABLE (?, ?, ?, null, null, null, 0)";
    final String importFile = FabricServerHelper.getFabricServerDescription()
        .getSysDiskDir() + File.separator + ".." + File.separator + "csv" + File.separator + table.toUpperCase() + ".file";
    Log.getLogWriter().info(
        "schema "
            + schema
            + "  table "
            + table
            + " importFile " + importFile);
    try {
      CallableStatement cs = conn.prepareCall(importTable);
      cs.setString(1, schema.toUpperCase());
      cs.setString(2, table.toUpperCase());
      cs.setString(3, importFile);
      cs.execute();
    } catch (SQLException se) {
       SQLHelper.handleSQLException(se);
    }
  } 
  
  protected static List<com.gemstone.gemfire.cache.query.Struct> getResultSetTest(Connection gConn, String sqlQuery) {
    ResultSet rs = null;
    try {      
      System.out.println(sqlQuery);
      rs = gConn.createStatement().executeQuery(sqlQuery);
      return ResultSetHelper.asList(rs, false);      
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, gConn);
      commitTest(gConn);
    }
    return null;
  }
  
  protected static void commitTest(Connection conn) {
    if (conn == null) return;    
    try {
      String name = "gfxd ";
      System.out.println("committing the ops for " + name);
      conn.commit();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se) 
          && se.getSQLState().equalsIgnoreCase("08003")) {
        System.out.println("detected current connection is lost, possibly due to reade time out");
        return; //add the case when connection is lost due to read timeout
      }
      else
        SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_waitForStartupRecovery() {
	      int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
	      List<ClientVmInfo> vms = new ArrayList<ClientVmInfo>();
	      vms.addAll(StopStartVMs.getMatchVMs(StopStartVMs.getAllVMs(), "server"));
	      PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
  }
}
