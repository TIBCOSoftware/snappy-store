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
package sql.sqlTx;

import hydra.ClientVmInfo;
import hydra.HydraSubthread;
import hydra.HydraThreadLocal;
import hydra.HydraVector;
import hydra.Log;
import hydra.MasterController;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.AnyCyclicBarrier;
import hydra.blackboard.SharedMap;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;

import sql.GFEDBManager;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlDistTxStatements.DMLDistTxStmtIF;
import sql.dmlDistTxStatements.TradeNetworthDMLDistTxStmt;
import sql.dmlDistTxStatements.TradeNetworthV1DMLDistTxStmt;
import sql.dmlDistTxStatements.json.TradeNetworthDMLDistTxStmtJson;
import sql.dmlStatements.AbstractDMLStmt;
import sql.sqlTx.thinClient.SQLDistTxClientTest;
import sql.sqlTx.txTrigger.TxTriggerProcedure;
import sql.sqlTx.txTrigger.TxTriggers;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.PooledConnectionC3P0;
import sql.sqlutil.PooledConnectionDBCP;
import sql.sqlutil.PooledConnectionTomcat;
import sql.sqlutil.ResultSetHelper;
import util.PRObserver;
import util.StopStartPrms;
import util.StopStartVMs;
import util.TestException;
import util.TestHelper;

import com.gemstone.gemfire.cache.query.Struct;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeapScanController;

public class SQLDistTxTest extends SQLTxTest {
  protected static SQLDistTxTest sqlNewTxTest;

  public static HydraThreadLocal gfxdNoneTxConn = new HydraThreadLocal();
  public static HydraThreadLocal cidInserted = new HydraThreadLocal();
  public static HydraThreadLocal curTxId = new HydraThreadLocal();
  public static HydraThreadLocal curTxModifiedKeys = new HydraThreadLocal(); 
  // lock held by this tx
  public static HydraThreadLocal curTxNonDeleteHoldKeys = new HydraThreadLocal();
  // once the key is hold, child insert and some update will be blocked (added
  // due to #42672)
  public static HydraThreadLocal curTxDeleteHoldKeys = new HydraThreadLocal();
  // which checks a current child row holds fk
  public static HydraThreadLocal curTxNewHoldParentKeys = new HydraThreadLocal(); 
  // new foreign key lock held due to insert/update by this tx
  // for insert of child or update to change the fk field, the parent key needs
  // to be held
  // this will be used to compare with HoldKeysBlockingChildOp
  public static HydraThreadLocal curTxHoldParentKeys = new HydraThreadLocal(); 
  // foreign key lock held by this tx
  public static HydraThreadLocal derbyOps = new HydraThreadLocal();
  public static HydraThreadLocal commitEarly = new HydraThreadLocal();
  public static HydraThreadLocal curTxFKHeld = new HydraThreadLocal(); 
  // all foreign keys checked by this tx
  public static HydraThreadLocal rollbackGfxdTx = new HydraThreadLocal();
  public static HydraThreadLocal iteration = new HydraThreadLocal();
  public static HydraThreadLocal selectForUpdateRS = new HydraThreadLocal();
  public static HydraThreadLocal foreignKeyHeldWithBatching = new HydraThreadLocal();
  // delayed fk check at commit time, the foreignKeys held due to insert of
  // child table
  public static HydraThreadLocal parentKeyHeldWithBatching = new HydraThreadLocal();
  // delayed fk check at commit time, the parent keys held due to update
  // (#42672) or delete and insert

  public static HydraThreadLocal derbyExceptionsWithBatching = new HydraThreadLocal();
  public static HydraThreadLocal failedToGetStmtNodeFailure = new HydraThreadLocal();
  // public static HydraThreadLocal convertTxnRSGotNodeFailure = new
  // HydraThreadLocal(); //no longer needed per comment in #46968
  public static HydraThreadLocal updateOnPartitionCol = new HydraThreadLocal();
  public static HydraThreadLocal needNewConnAfterNodeFailure = new HydraThreadLocal();
  public static HydraThreadLocal batchInsertToCustomersSucceeded = new HydraThreadLocal();

  public static boolean ticket43170fixed = false; // TODO, change to true if it
                                                  // is fixed
  CountDownLatch latch = null;

  protected static boolean doOpByOne = TestConfig.tab().booleanAt(
      SQLTxPrms.doOpByOne, false);
  protected static boolean useOriginalNonTx = TestConfig.tab().booleanAt(
      SQLTxPrms.useOriginalNonTx, false);
  protected static DMLDistTxStmtsFactory dmlDistTxFactory = new DMLDistTxStmtsFactory();
  public static boolean useTimeout = TestConfig.tab().booleanAt(
      SQLTxPrms.useTimeout, false);
  boolean is43591fixed = TestConfig.tab().booleanAt(SQLTxPrms.is43591fixed,
      false);
  String[] concUpdateStatements = {
      "update trade.portfolio set qty = qty + ? where cid>? and cid <= ? ",
      "update trade.sellorders set ask = ask + ? where cid>? and cid <= ? " };
  protected static int concUpdateTxMaxCid = 0;
  protected static boolean isTicket41738Fixed = false;
  protected static boolean isTicket43935Fixed = false;
  protected static boolean isTicket43932Fixed = true; // to reproduce #43932,
                                                      // this needs to set to
                                                      // true
  protected static boolean isTicket43909fixed = true;
  protected static boolean isTicket45071fixed = TestConfig.tab().booleanAt(
      SQLPrms.isTicket45071fixed, false);
  public static boolean isTicket43188fiFixed = false;
  public static boolean useThinClientDriverInTx = TestConfig.tab().booleanAt(
      SQLTxPrms.useThinClientDriverInTx, false);
  public static boolean mixRR_RC = TestConfig.tab().booleanAt(
      SQLTxPrms.mixRR_RC, false);
  // see comments in #49771, mixRR and RC has issues. If we do not document this
  // issue,
  // QA needs to add the coverage.

  protected boolean nobatching = TestConfig.tab().booleanAt(
      SQLTxPrms.nobatching, true); // test default to true --
  // when it is set to true -- assume either no batching or batching without
  // secondary copy or replicate
  // when set to false -- it means need to test batching with secondary data.
  protected boolean batchingWithSecondaryData = !nobatching; // default is false

  public static String syncCommits = Attribute.TX_SYNC_COMMITS;

  public static Object lock = new Object();
  public static int concUpdateCount = 0;
  public static boolean addGenIdColInCustomersv1 = false; 
  // no longer used as the issue was worked around, should always set to false
  public static final String CUSTOMERSV1TXREADY = "CUSTOMERSV1TXREADY";
  public static boolean hasSecondaryTables = TestConfig.tab().booleanAt(
      SQLPrms.hasSecondaryTables, true);
  public static boolean queryOpTimeNewTables = true;

  public static void HydraTask_initConnections() throws SQLException {
    sqlNewTxTest.initThreadLocalConnection();
  }

  /**
   * ` Sets the per-thread Connection with isolation none for certain query.
   */
  protected void initThreadLocalConnection() throws SQLException {
    Connection gfxdConn = getGFEConnection();
    //use none txn isolation when setTx is false
    log().info("gfxdNoneTxConn isolation is set to " + Connection.TRANSACTION_NONE
        + " and getTransactionIsolation() is "+ gfxdConn.getTransactionIsolation());
    gfxdNoneTxConn.set(gfxdConn);
  }

  public static void HydraTask_initThreadLocals() {
    sqlNewTxTest.initThreadLocals();
  }

  protected void initThreadLocals() {
    ArrayList<String> curTxRangeFK = new ArrayList<String>();
    curTxFKHeld.set(curTxRangeFK);
    rollbackGfxdTx.set(false);
    ArrayList<ResultSet> selectForUpdateRSList = new ArrayList<ResultSet>();
    selectForUpdateRS.set(selectForUpdateRSList);
    if (useNewTables)
      iteration.set(1);
    if (batchingWithSecondaryData) {
      HashSet<String> fkHold = new HashSet<String>();
      foreignKeyHeldWithBatching.set(fkHold);
      HashSet<String> pkHold = new HashSet<String>();
      parentKeyHeldWithBatching.set(pkHold);

      derbyExceptionsWithBatching.set(new ArrayList<SQLException>());
    }
    failedToGetStmtNodeFailure.set(false); // not able to get convertTxnRSGotNodeFailure.set(false);
    updateOnPartitionCol.set(false);
    needNewConnAfterNodeFailure.set(false); // only needs the new connection after node failure
    batchInsertToCustomersSucceeded.set(true); // only set to false when batch insert failed
  }

  public static synchronized void HydraTask_initialize() {
    if (sqlNewTxTest == null) {
      sqlNewTxTest = new SQLDistTxTest();
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());

      sqlNewTxTest.initialize();
    }
    sqlNewTxTest.initThreadLocals();
  }

  @Override
  protected void initialize() {
    super.initialize();
    // hasTx = true; //need to be set in the conf now
  }

  public static void HydraTask_createGfxdLocatorTask() {
    SQLTest.HydraTask_createGfxdLocatorTask();
  }

  public static void HydraTask_startGfxdLocatorTask() {
    SQLTest.HydraTask_startGfxdLocatorTask();
  }

  public synchronized static void HydraTask_startFabricServer() {
    sqlNewTxTest.startFabricServer();
  }

  public synchronized static void HydraTask_stopFabricServer() {
    if (sqlNewTxTest == null) {
      sqlNewTxTest = new SQLDistTxTest();
    }
    sqlNewTxTest.stopFabricServer();
  }

  public synchronized static void HydraTask_startFabricServerSG() {
    sqlNewTxTest.startFabricServerSG();
  }

  public synchronized static void HydraTask_startFabricServerSGDBSynchronizer() {
    sqlNewTxTest.startFabricServerSGDBSynchronizer();
  }

  public static synchronized void HydraTask_createDiscDB() {
    sqlNewTxTest.createDiscDB();
  }

  public static synchronized void HydraTask_createDiscSchemas() {
    sqlNewTxTest.createDiscSchemas();
  }

  public static synchronized void HydraTask_createDiscTables() {
    sqlNewTxTest.createDiscTables();
  }

  public static void HydraTask_createGFESchemas() {
    sqlNewTxTest.createGFESchemas();
  }

  public static void HydraTask_createGFETables() {
    sqlNewTxTest.createGFETables();
  }

  public static synchronized void HydraTask_dropAllTables() {
    sqlNewTxTest.dropAllTables();
  }

  public static void HydraTask_populateTxTables() {
    sqlNewTxTest.populateTxTables();
  }

  public static void HydraTask_populateTxTablesDBSynchronizer() {
    sqlNewTxTest.populateTxTablesDBSynchronizer();
  }

  public static void HydraTask_setTableCols() {
    sqlNewTxTest.setTableCols();
  }

  public static void HydraTask_alterPortfolioAddTimestamp() {
    sqlNewTxTest.alterPortfolioAddTimestamp();
  }

  public static void HydraTask_setTraceFlagsTask() {
    sqlNewTxTest.setTraceFlags(true);
  }

  public static void HydraTask_unsetTraceFlagsTask() {
    sqlNewTxTest.setTraceFlags(false);
  }

  protected void alterPortfolioAddTimestamp() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    alterPortfolioAddTimestamp(dConn, gConn);
    commit(dConn);
    commit(gConn);
    if (dConn != null)
      closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterPortfolioAddTimestamp(Connection dConn, Connection gConn) {

  }

  public static void HydraTask_createIndex() {
    if (RemoteTestModule.getCurrentThread().getCurrentTask()
        .getTaskTypeString().equalsIgnoreCase("INITTASK")) {
      // TODO temporally comment this out to avoid
      // ArrayIndexOutOfBoundsException
      // in new tables. will restore once the known issues are fixed.
      // sqlNewTxTest.createIndex();
    } else {
      if (random.nextInt(numOfWorkers) == 1)
        sqlNewTxTest.createIndex();
      else
        Log.getLogWriter().info(
            "This is no op to avoid too many ddl in the test");
    }
  }

  public static void HydraTask_verifyResultSets() {
    sqlNewTxTest.verifyResultSets();
  }

  protected void verifyResultSets() {
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verification of query results "
              + "due to manageDerbyServer as false, myTid=" + getMyTid());
      cleanConnection(getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED));
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = null;
    if (hasHdfs) {
      // set connection property queryHDFS=true
      Properties info = new Properties();
      info.setProperty("query-HDFS", "true");
      gConn = getGFXDTxConnection(info);
      Log.getLogWriter().info("Get gfxd connection with properties=" + info + 
          ", connection=" + gConn);
    } else {
      gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    }
    verifyResultSets(dConn, gConn);
  }

  public static void HydraTask_initForServerGroup() {
    sqlNewTxTest.initForServerGroup();
  }

  public static void HydraTask_createDBSynchronizer() {
    sqlNewTxTest.createDBSynchronizer();
  }

  public static void HydraTask_createDiskStores() {
    sqlNewTxTest.createDiskStores();
  }

  protected void createDiskStores() {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    createDiskStores(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  protected void createDBSynchronizer() {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    createNewDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  public static void HydraTask_startDBSynchronizer() {
    sqlNewTxTest.startDBSynchronizer();
  }

  protected void startDBSynchronizer() {
    boolean useNewApi = true;
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    if (useNewApi)
      startNewDBSynchronizer(conn);
    else
      startDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  @Override
  protected void createIndex() {
    // Connection gConn = getGFEConnection();
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    createTxIndex(gConn);
    closeGFEConnection(gConn);
  }

  protected void createTxIndex(Connection gConn) {
    try {
      createIndex(gConn);
    } catch (TestException te) {
      if (isHATest
          && (te.getMessage().contains("40XD0") || te.getMessage().contains(
              "40XD2"))) {
        Log.getLogWriter().info("got expected node failure exception, " +
        		"continuing test");
      } else
        throw te;
    }

    try {
      commit(gConn); // ddl statement is auto_committed, but added here anyway
    } catch (TestException te) {
      if (isHATest
          && (te.getMessage().contains("40XD0") || te.getMessage().contains(
              "40XD2"))) {
        Log.getLogWriter().info("got expected node failure exception, " +
        		"continuing test");
      } else
        throw te;
    }
  }

  @Override
  protected void populateTxTables() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // only verification case need to populate derby tables
    // using read_committed connection
    // work around issue mentioned in #48712, commit may return earlier
    // Connection gConn =
    // getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    Properties info = new Properties();
    info.setProperty(syncCommits, "true");
    Connection gConn = getGFXDTxConnection(info); // defaults to
                                                  // Isolation.READ_COMMITTED

    populateTables(dConn, gConn);
    if (dConn != null)
      closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void populateTxTablesDBSynchronizer() {
    // work around issue mentioned in #48712, commit may return earlier
    Properties info = new Properties();
    info.setProperty(syncCommits, "true");
    Connection gConn = getGFXDTxConnection(info); // defaults to
                                                  // Isolation.READ_COMMITTED

    populateTables(null, gConn); // pass dConn as null for DBSynchronizer
    closeGFEConnection(gConn);
  }

  @Override
  protected void populateTables(Connection dConn, Connection gConn) {
    // populate table without txId
    if (!useOriginalNonTx) {
      for (int i = 0; i < dmlTables.length; i++) {
        // reuse the dmlStmt, as currently each insert is being committed
        DMLDistTxStmtIF dmlStmt = dmlDistTxFactory
            .createDMLDistTxStmt(dmlTables[i]);
        if (dmlStmt != null) {
          dmlStmt.populate(dConn, gConn);
          if (dConn != null)
            waitForBarrier(); // added to avoid unnecessary foreign key reference
                              // error
        }
      }
    } else super.populateTables(dConn, gConn);
  }

  public static void HydraTask_setCriticalHeapPercentage() {
    sqlNewTxTest.setCriticalHeapPercentage();
  }

  protected void setCriticalHeapPercentage() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    setCriticalHeapPercentage(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  protected Connection getGFXDTxConnection(GFEDBManager.Isolation isolation) {
    Connection conn = null;
    boolean retry = false;
    do {
      try {
        if (hasHdfs) {
          Properties info = new Properties();
          info.setProperty("query-HDFS", "true");
          conn = GFEDBManager.getTxConnection(isolation, info);
          Log.getLogWriter().info("Get gfxd connection with properties=" + info + 
              ", connection=" + conn);
        } else {
          if (isEdge) {
            if (useC3P0 && useGfxdConfig) {
              if (isolation == GFEDBManager.Isolation.READ_COMMITTED)
                conn = PooledConnectionC3P0.getRCConnection();
              if (isolation == GFEDBManager.Isolation.REPEATABLE_READ)
                conn = PooledConnectionC3P0.getRRConnection();
            }
            else if (useDBCP && useGfxdConfig) {
              if (isolation == GFEDBManager.Isolation.READ_COMMITTED)
                conn = PooledConnectionDBCP.getRCConnection();
              if (isolation == GFEDBManager.Isolation.REPEATABLE_READ)
                conn = PooledConnectionDBCP.getRRConnection();
            }
            else if (useTomcatConnPool && useGfxdConfig) {
              if (isolation == GFEDBManager.Isolation.READ_COMMITTED)
                conn = PooledConnectionTomcat.getRCConnection();
              if (isolation == GFEDBManager.Isolation.REPEATABLE_READ)
                conn = PooledConnectionTomcat.getRRConnection();
            }
            else
              conn = sql.GFEDBClientManager.getTxConnection(isolation);
          } else conn = GFEDBManager.getTxConnection(isolation);
        }
        retry = false;
        if (needNewConnection()) {
          resetNeedNewConnFlag();
        }
      } catch (SQLException e) {
        SQLHelper.printSQLException(e);
        if (AbstractDMLStmt.gfxdtxHANotReady && isHATest
            && SQLHelper.gotTXNodeFailureException(e)) {
          retry = true;
          Log.getLogWriter().info("Could not get connection due to Node failure and " +
          		"will retry, continue testing");
        } else
          throw new TestException("Not able to get connection "
              + TestHelper.getStackTrace(e));
      }
    } while (retry);
    return conn;
  }

  public static void HydraTask_putLastKeyDBSynchronizer() {
    sqlNewTxTest.putLastKeyDBSynchronizer();
  }

  protected void putLastKeyDBSynchronizer() {
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    putLastKeyDBSynchronizer(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_verifyResultSetsDBSynchronizer() {
    sqlNewTxTest.verifyResultSetsDBSynchronizer();
  }

  public static void HydraTask_doTxDMLOpDBSynchronizer() {
    sqlNewTxTest.doTxDMLOpDBSynchronizer();
  }

  protected void doTxDMLOpDBSynchronizer() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    doDMLOp(null, gConn); // do not operation on derby directly
  }

  public static void HydraTask_doDMLOps() { 
    int numOfOps = 20; 
    for (int i=0; i< numOfOps; i++) 
      sqlNewTxTest.doDMLOp(); 
  } 
  
  public static void HydraTask_doDMLOp() {
    sqlNewTxTest.doDMLOp();
  }

  @Override
  protected void doDMLOp() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);

    doDMLOp(dConn, gConn);
    if (dConn != null) {
      closeDiscConnection(dConn);
      // Log.getLogWriter().info("closed the disc connection");
    }
    
    //work around an issue of no connection fail over and new connection is required case
    try {
      Log.getLogWriter().info("commit the gfxd txn again");
      gConn.commit();
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
      } else if (isHATest && se.getSQLState().equals("08003")) {
        Log.getLogWriter().info("connection has been closed -- this could happen when txn HA support" +
        " is not ready and a new connection has been acquired, continue testing");
        Log.getLogWriter().info("done dmlOp");
        return;
      } else SQLHelper.handleSQLException(se);
    }
    
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done dmlOp");
  }

  @Override
  protected void doDMLOp(Connection dConn, Connection gConn) {
    if (dConn != null) {
      if (!useOriginalNonTx) {
        if (useNewTables)
          doDMLOpNewTablesByOne(dConn, gConn);
        else
          doDMLOpByOne(dConn, gConn);
      } else super.doDMLOp(dConn, gConn);
    } else
      doDMLGfxdOnlyOps(gConn);
  }

  // use added columns for additional fields used in dml op so no need for early
  // commit
  // with certain predicate
  protected void doDMLOpNewTablesByOne(Connection dConn, Connection gConn) {
    int num = (Integer) iteration.get();
    Log.getLogWriter().info("this is iteration " + num);
    int numIt = (int) SQLTxBB.getBB().getSharedCounters().read(
        SQLTxBB.iterations);
    if (numIt != num) {
      throw new TestException(
          "this thread does not have the same iteration as others,"
              + "mine is " + num + ", but others have " + numIt
              + " need to check logs");
    }

    Log.getLogWriter().info("using RC isolation");
    // setting current txId
    boolean firstInThisRound = false;
    if (SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.firstInRound) == 1) {
      firstInThisRound = true;
      Log.getLogWriter().info("I am the first to commit in this round");
      // verifyResultSets(dConn, gConn);
      // need to add handle node failure exception in the method as it is
      // executed in the main task now.
      MasterController.sleepForMs(100);
      try {
        verifyResultSets(dConn, gConn, "trade", "customersv1");
        verifyResultSets(dConn, gConn, "trade", "networthv1");
      } catch (TestException te) {
        if (isHATest && SQLHelper.gotTXNodeFailureTestException(te)) {
          Log.getLogWriter().info(
              "got expected node failure exception, continuing test");
          // reacquire a new txn connection after getting node failure exception
          gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
        } else
          throw te;
      }
    }

    int txId = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(
        SQLTxBB.txId);
    // Log.getLogWriter().info("setting hydra thread local curTxId to " + txId);
    curTxId.set(txId);
    boolean withDerby = false;
    if (dConn != null)
      withDerby = true;
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid() + 
        " and txId is " + txId);

    // resets a new txn
    curTxModifiedKeys.set(new HashMap<String, Integer>());
    curTxDeleteHoldKeys.set(new HashMap<String, Integer>());
    curTxNonDeleteHoldKeys.set(new HashMap<String, Integer>());
    curTxHoldParentKeys.set(new HashMap<String, ForeignKeyLocked>());
    curTxNewHoldParentKeys.set(new HashMap<String, ForeignKeyLocked>());

    int total = 4;
    int numOfIter = random.nextInt(total) + 1;
    int maxOps = 5;
    int numOps = random.nextInt(maxOps) + 1;
    for (int i = 0; i < numOfIter - 1; i++) {
      getLock();
      doGfxdDMLOpNewTablesByOne(dConn, gConn, numOps, withDerby); // work around #43170
      releaseLock();
      
      if (needNewConnection()) {
        gConn = getNewGfxdConnection(); // use new connection
        resetNeedNewConnFlag(); //reset flag
      }
      /*
       * HA stop start vm will be performed by DDLthread and no longer in the
       * working thread pool in the barrier index will be created thru create
       * index op and be turned on once the max gemfirexd.max-lock-wait property
       * is set, and lock time out on (index) ddl is being handled in the test
       * //bring down stores
       */

    }
    getLock();
    doGfxdDMLOpNewTablesByOne(dConn, gConn, numOps, withDerby); // work around
    releaseLock();
    
    if (needNewConnection()) {
      gConn = getNewGfxdConnection(); // use new connection 
      resetNeedNewConnFlag(); //reset flag 
    }
    
    waitForBarrier();
    // commit for RCs

    if (firstInThisRound) {
      SQLBB.getBB().getSharedCounters().zero(SQLBB.firstInRound); // for next
                                                                  // round

      commitOrRollback(dConn, gConn, firstInThisRound);

      removeNewTablesHoldKeysByThisTx(); // everyone clean up the keys
      /*
      if (batchingWithSecondaryData)
        cleanUpFKHolds();
      // no longer needed for new tables as additional batchingBB is used to track the fks hold
      */
      closeSelectForUpdateRS(); // close any select for update rs

      // Log.getLogWriter().info("finishing this round of iteration " + num);
      // iteration.set(++num); //set for next round
      int nextIt = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(
          SQLTxBB.iterations);
      Log.getLogWriter().info("next round of iterations is " + nextIt);

    }
    waitForBarrier();

    if (!firstInThisRound) {
      if (batchingWithSecondaryData)
        getLock();
      commitOrRollback(dConn, gConn, firstInThisRound);
      /*
      if (batchingWithSecondaryData)
        cleanUpFKHolds();
      */
      
      if (!batchingWithSecondaryData)
        getLock(); // for batching test, lock acquired before commit
      removeNewTablesHoldKeysByThisTx(); // everyone clean up the keys
      if (!batchingWithSecondaryData)
        releaseLock(); // for batching test, lock acquired before commit
      closeSelectForUpdateRS(); // close any select for update rs.
      if (batchingWithSecondaryData)
        releaseLock();

    }
    waitForBarrier();

  }
  
  protected void resetNeedNewConnFlag() {
    // to reset getCanceled flag
    Boolean needNewConn = (Boolean) needNewConnAfterNodeFailure.get();
    if (needNewConn != null && needNewConn) {
      needNewConnAfterNodeFailure.set(false);
      Log.getLogWriter().info("needNewConnAfterNodeFailure is reset to false for new ops");
    }
  }
  
  protected boolean needNewConnection() {
    Boolean needNewConn = (Boolean) needNewConnAfterNodeFailure.get();
    if (needNewConn != null && needNewConn) {
      return true;
    } else return false;
  }

  protected void doGfxdDMLOpNewTablesByOne(Connection dConn, Connection gConn,
      int size, boolean withDerby) {
    for (int i = 0; i < size; i++) {
      doOneGfxdDMLOpNewTablesByOne(dConn, gConn, withDerby);
      if (needNewConnection()) {
        return; //need new connection to continue ops
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected void doOneGfxdDMLOpNewTablesByOne(Connection dConn,
      Connection gConn, boolean withDerby) {
    doOneGfxdDMLOpNewTablesByOne(gConn, withDerby); // operates in gfxd
    if (!ticket43170fixed && withDerby) {
      // check if there is an constraint violation
      ArrayList<Object[]> derbyOps = (ArrayList<Object[]>) SQLDistTxTest.derbyOps
          .get();
      if (derbyOps != null && derbyOps.size() > 0) { // no ops as some table
                                                     // operations are not
                                                     // implemented yet
        int lastIndex = derbyOps.size() - 1;
        Object[] data = derbyOps.get(lastIndex);
        SQLException gfxdse = (SQLException) data[data.length - 1];
        if (gfxdse != null) {
          if (gfxdse.getSQLState().equals("23503")
              || gfxdse.getSQLState().equals("23505")
              || gfxdse.getSQLState().equals("23513")) {
            processDerbyOps(dConn, true); // see if indeed the constraint occurs

            // rollback(gConn); //not needed due to #43170
            removeNewTablesHoldKeysByThisTx(); // due to #43170
            closeSelectForUpdateRS(); // close any select for update rs.
            rollback(dConn); // roll back the derby ops
            resetDerbyOps();
            return;
          } else {
            SQLHelper.handleSQLException(gfxdse);
            // do not expect any other exceptions
            // will handle expected exceptions for cancellation, securities etc
            // once the coverage is added.
          }
        }

        if (queryOpTimeNewTables) {
          String operation = (String) data[1];
          if (operation.equals("query"))
            processDerbyQuery(dConn);
        }
      }
    }
  }

  //return true when operation does not fail
  @SuppressWarnings("static-access")
  protected void doOneGfxdDMLOpNewTablesByOne(Connection gConn,
      boolean withDerby) {
    boolean succeed = true;
    int table = dmlTables[random.nextInt(dmlTables.length)]; // get random table
                                                             // to perform dml
    DMLDistTxStmtIF dmlStmt = dmlDistTxFactory
        .createNewTablesDMLDistTxStmt(table); // dmlStmt of a table

    if (table != dmlTxFactory.TRADE_CUSTOMERS
        && table != dmlTxFactory.TRADE_NETWORTH)
      /*
       * && table != dmlTxFactory.TRADE_SECURITIES && table !=
       * dmlTxFactory.TRADE_PORTFOLIO && table != dmlTxFactory.TRADE_SELLORDERS)
       */return;
    // perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
      succeed = dmlStmt.insertGfxd(gConn, withDerby);
    } else if (operation.equals("update")) {
      succeed = dmlStmt.updateGfxd(gConn, withDerby);
    } else if (operation.equals("delete"))
      succeed = dmlStmt.deleteGfxd(gConn, withDerby);
    else if (operation.equals("query"))
      succeed = dmlStmt.queryGfxd(gConn, withDerby);
    else
      throw new TestException("Unknown entry operation: " + operation);

    // insert into networth table() -- should use trigger to insert the row
    if (table == dmlTxFactory.TRADE_CUSTOMERS && operation.equals("insert")
        && succeed) {
      // only two thirds will have child rows for delete to succeed
      int chance = 3;
      if (random.nextInt(chance) == 1) {
        Log.getLogWriter().info("Does not have child row in networthv1 table");
        return;
      }
      Log.getLogWriter().info("inserting into networthv1 table");
      /*
       * TradeNetworthDMLDistTxStmt networth = new TradeNetworthDMLDistTxStmt();
       * TradeNetworthV1DMLDistTxStmt networthv1 = new
       * TradeNetworthV1DMLDistTxStmt();
       * 
       * if (useNewTables && isCustomerv1Ready) { succeed =
       * networthv1.insertGfxd(gConn, withDerby, (Long) cidInserted.get()); }
       * else succeed = networth.insertGfxd(gConn, withDerby, (Integer)
       * cidInserted.get());
       */
      TradeNetworthV1DMLDistTxStmt networthv1 = new TradeNetworthV1DMLDistTxStmt();
      succeed = networthv1.insertGfxd(gConn, withDerby, (Long) cidInserted
          .get());

    }

    /*
     * comment out the followings once #42703 is supported
     */
    if (!succeed || needNewConnection()) {
      // rollback(gConn); //work around #42703
      // do not rollback the txn as it is supposedly rolled back by product
      // based on #42703

      // if (batchingWithSecondaryData) cleanUpFKHolds(); //clean up foreign key
      // holds is being handled in removeHoldKeysByThisTx()
      removeNewTablesHoldKeysByThisTx();
      closeSelectForUpdateRS(); // close any select for update rs.
      resetDerbyOps();
    } // lock not held and transaction rolled back    
  }

  @SuppressWarnings("unchecked")
  protected void removeNewTablesHoldKeysByThisTx() {
    if (!batchingWithSecondaryData) {
      // remove primary/unique key
      SharedMap modifiedKeysByAllTx = SQLTxBB.getBB().getSharedMap();
      Set<String> alreadyHeldKeysThisTx = ((HashMap<String, Integer>) curTxModifiedKeys
          .get()).keySet();
      Log.getLogWriter().info("removing the keys from the key Map that were held " +
      		"by this txn");
      for (String key : alreadyHeldKeysThisTx) {
        Log.getLogWriter().info("key to be removed is " + key);
        modifiedKeysByAllTx.remove(key);
      }

      // additional bb are used to track these
      // remove parent key hold blocking child table ops
      hydra.blackboard.SharedMap holdDeleteBlockingKeysByAllTx = SQLTxDeleteHoldKeysBlockingChildBB
          .getBB().getSharedMap();
      HashMap<String, Integer> holdDeleteKeysByTx = 
        (HashMap<String, Integer>) SQLDistTxTest.curTxDeleteHoldKeys.get();
      Log.getLogWriter().info("removing the keys from the parent key Map that were " +
      		"held by this txn by delete op");
      for (String key : holdDeleteKeysByTx.keySet()) {
        Log.getLogWriter().info("parent key to be removed is " + key);
        holdDeleteBlockingKeysByAllTx.remove(key);
      }

      hydra.blackboard.SharedMap holdBlockingKeysByAllTx = SQLTxHoldKeysBlockingChildBB
          .getBB().getSharedMap();
      HashMap<String, Integer> holdNonDeleteKeysByTx = 
        (HashMap<String, Integer>) SQLDistTxTest.curTxNonDeleteHoldKeys.get();
      Log.getLogWriter().info("removing the keys from the parent key Map that were " +
      		"held by this txn by non delete op");
      for (String key : holdNonDeleteKeysByTx.keySet()) {
        Log.getLogWriter().info("parent key to be removed is " + key);
        holdBlockingKeysByAllTx.remove(key);
      }
    } else {
      Integer myTxId = (Integer) curTxId.get();
      // remove primary/unique key in batching bb
      SharedMap modifiedKeysByAllTx = SQLTxBatchingBB.getBB().getSharedMap();
      Set<String> alreadyHeldKeysThisTx = ((HashMap<String, Integer>) curTxModifiedKeys
          .get()).keySet();
      Log.getLogWriter().info(
          "removing the keys from the key Map that were held by this txn");
      for (String key : alreadyHeldKeysThisTx) {
        Log.getLogWriter().info("key to be removed is " + key);
        ArrayList<Integer> txIdsForKey = (ArrayList<Integer>)
        modifiedKeysByAllTx.get(key);
        if (txIdsForKey != null) { 
          boolean contains = txIdsForKey.remove(myTxId);
          if (!contains)
          Log.getLogWriter().info("this txId does not hold the local lock yet");
        }
        if (txIdsForKey != null) {
          modifiedKeysByAllTx.put(key, txIdsForKey);
        }
        //put back as other txid could still hold the lock
      }

      // remove parent key hold blocking child table ops
      hydra.blackboard.SharedMap holdBlockingKeysByAllTx = SQLTxBatchingNonDeleteHoldKeysBB.getBB().getSharedMap(); 
      HashMap<String, Integer> holdNonDeleteKeysByTx = (HashMap<String, Integer>) SQLDistTxTest.curTxNonDeleteHoldKeys
          .get();
      Log.getLogWriter().info("removing the keys from the parent key Map that were held " +
      		"by this txn by non_delete op");
      for (String key : holdNonDeleteKeysByTx.keySet()) {
        Log.getLogWriter().info("key to be removed is " + key);
        ArrayList<Integer> txIdsForKey = (ArrayList<Integer>)
        holdBlockingKeysByAllTx.get(key);
        if (txIdsForKey != null) { 
          boolean contains = txIdsForKey.remove(myTxId);
          if (!contains)
          Log.getLogWriter().info("this txId does not hold the local lock yet");
        }
        if (txIdsForKey != null) {
          holdBlockingKeysByAllTx.put(key, txIdsForKey);
        }
        //put back as other txid could still hold the lock
      }

      hydra.blackboard.SharedMap holdDeleteBlockingKeysByAllTx = SQLTxBatchingDeleteHoldKeysBB.
          getBB().getSharedMap(); 
      HashMap<String, Integer> holdDeleteKeysByTx = (HashMap<String, Integer>) SQLDistTxTest.
          curTxDeleteHoldKeys.get();
      Log.getLogWriter().info("removing the keys from the parent key Map that were held by " +
      		"this txn by delete op");
      for (String key : holdDeleteKeysByTx.keySet()) {
        Log.getLogWriter().info("key to be removed is " + key);
        ArrayList<Integer> txIdsForKey = (ArrayList<Integer>)
        holdDeleteBlockingKeysByAllTx.get(key);
        if (txIdsForKey != null) { 
          boolean contains = txIdsForKey.remove(myTxId);
          if (!contains)
          Log.getLogWriter().info("this txId does not hold the local lock yet");
        }
        if (txIdsForKey != null) {
          holdDeleteBlockingKeysByAllTx.put(key, txIdsForKey);
        }
        //put back as other txid could still hold the lock
      }
    }
    
    // remove parent key hold by txn blocking parent table ops
    hydra.blackboard.SharedMap holdForeignKeysByAllTx = SQLTxHoldForeignKeysBB
        .getBB().getSharedMap();
    HashMap<String, ForeignKeyLocked> holdParentKeysByTx = 
      (HashMap<String, ForeignKeyLocked>) SQLDistTxTest.curTxHoldParentKeys.get();
    Log.getLogWriter().info("removing the keys from the foreing key Map that were " +
        "held by this txn");
    for (String key : holdParentKeysByTx.keySet()) {
      ForeignKeyLocked fkey = (ForeignKeyLocked) holdForeignKeysByAllTx
          .get(key);
      int txId = (Integer) curTxId.get();
      fkey.removeAllLockedKeyByCurTx(txId);
      Log.getLogWriter().info("foreign key to be removed is " + key + 
          " for txId: " + txId);
      holdForeignKeysByAllTx.put(key, fkey);
    }

    // remove parent key hold by txn blocking parent table ops
    hydra.blackboard.SharedMap holdNewForeignKeysByAllTx = SQLTxHoldNewForeignKeysBB
        .getBB().getSharedMap();
    HashMap<String, ForeignKeyLocked> holdNewParentKeysByTx = 
      (HashMap<String, ForeignKeyLocked>) SQLDistTxTest.curTxNewHoldParentKeys.get();
    Log.getLogWriter().info("removing the keys from the foreing key Map that were " +
        "held by this txn");
    for (String key : holdNewParentKeysByTx.keySet()) {
      ForeignKeyLocked fkey = (ForeignKeyLocked) holdNewForeignKeysByAllTx
          .get(key);
      int txId = (Integer) curTxId.get();
      fkey.removeAllLockedKeyByCurTx(txId);
      Log.getLogWriter().info("Newly acquired foreign key to be removed is " + key
              + " for txId: " + txId);
      holdNewForeignKeysByAllTx.put(key, fkey);
    }

    // reset keys hold in current txn.
    curTxModifiedKeys.set(new HashMap<String, Integer>());
    curTxDeleteHoldKeys.set(new HashMap<String, Integer>());
    curTxNonDeleteHoldKeys.set(new HashMap<String, Integer>());
    curTxHoldParentKeys.set(new HashMap<String, ForeignKeyLocked>());
    curTxNewHoldParentKeys.set(new HashMap<String, ForeignKeyLocked>());

  }

  protected void doDMLOpByOne(Connection dConn, Connection gConn) {
    /*
     * int num = (Integer) iteration.get();
     * Log.getLogWriter().info("this is iteration " + num); int numIt = (int)
     * SQLTxBB.getBB().getSharedCounters().read(SQLTxBB.iterations); if (numIt
     * != num) { throw new TestException
     * ("this thread does not have the same iteration as others," + "mine is " +
     * num + ", but others have " + numIt + " need to check logs"); }
     */

    Log.getLogWriter().info("using RC isolation");
    // setting current txId
    boolean firstInThisRound = false;
    if (SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.firstInRound) == 1) {
      firstInThisRound = true;
      Log.getLogWriter().info("I am the first to commit in this round");
      commitEarly.set(true);
    } else {
      // commitEarly.set(random.nextBoolean()); //TODO, use this one instead
      // below setting
      if (random.nextInt(10) == 1)
        commitEarly.set(true);
      else
        commitEarly.set(false);
    }
    // boolean ticket42651fixed = false; //TODO, to set to true after #42651 is
    // fixed
    boolean ticket42651fixed = true;

    int txId = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(
        SQLTxBB.txId);
    // Log.getLogWriter().info("setting hydra thread local curTxId to " + txId);
    curTxId.set(txId);
    boolean withDerby = false;
    if (dConn != null)
      withDerby = true;
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid() + 
        " and txId is " + txId);
    HashMap<String, Integer> modifiedKeys = new HashMap<String, Integer>();
    curTxModifiedKeys.set(modifiedKeys);

    int total = 4;
    int numOfIter = random.nextInt(total) + 1;
    int maxOps = 5;
    int numOps = random.nextInt(maxOps) + 1;
    for (int i = 0; i < numOfIter - 1; i++) {
      getLock();
      // doGfxdDMLOpByOne(gConn, numOps, withDerby);
      doGfxdDMLOpByOne(dConn, gConn, numOps, withDerby); // work around #43170
      releaseLock();

      /*
       * HA stop start vm will be performed by DDLthread and no longer in the
       * working thread pool in the barrier index will be created thru create
       * index op and be turned on once the max gemfirexd.max-lock-wait property
       * is set, and lock time out on (index) ddl is being handled in the test
       * //bring down stores int num = random.nextInt(numOfWorkers * numOfIter);
       * 
       * if (isHATest && num== 1) {//one out of num of workers
       * cycleStoreVmsAsync("store"); } else if (createIndex && num == 2 &&
       * is43591fixed) { doIndexOp(); }
       */

    }
    getLock();
    // doGfxdDMLOpByOne(gConn, numOps, withDerby);
    doGfxdDMLOpByOne(dConn, gConn, numOps, withDerby); // work around #43170
    releaseLock();
    waitForBarrier();
    // commit for RCs

    if (firstInThisRound) {
      SQLBB.getBB().getSharedCounters().zero(SQLBB.firstInRound); // for next
                                                                  // round

      /*
       * being handled in commitOrRollback if (ticket42651fixed)
       * processDerbyOps(dConn, true); else processDerbyOps(dConn, false);
       */
      // first in this round could compare the query results

      commitOrRollback(dConn, gConn, firstInThisRound);

      removeHoldKeysByThisTx(); // everyone clean up the keys
      if (batchingWithSecondaryData)
        cleanUpFKHolds();
      closeSelectForUpdateRS(); // close any select for update rs

      /*
       * Log.getLogWriter().info("finishing this round of iteration " + num);
       * iteration.set(++num); //set for next round int nextIt = (int)
       * SQLTxBB.getBB
       * ().getSharedCounters().incrementAndRead(SQLTxBB.iterations);
       * Log.getLogWriter().info("next round of iterations is " + nextIt);
       */
    }
    waitForBarrier();

    if (!firstInThisRound && (Boolean) commitEarly.get()) {
      if (batchingWithSecondaryData)
        getLock();
      commitOrRollback(dConn, gConn, firstInThisRound);
      if (batchingWithSecondaryData)
        cleanUpFKHolds();

      if (!batchingWithSecondaryData)
        getLock(); // for batching test, lock acquired before commit
      removeHoldKeysByThisTx(); // everyone clean up the keys
      if (!batchingWithSecondaryData)
        releaseLock(); // for batching test, lock acquired before commit
      closeSelectForUpdateRS(); // close any select for update rs.
      if (batchingWithSecondaryData)
        releaseLock();

    } // commit earlier
    waitForBarrier();
    /*
     * //commit earlier for RRs if (mixRR_RC) {
     * Log.getLogWriter().info("earlier commit in RR, do nothing for RCs");
     * waitForBarrier(); }
     */
    if (!firstInThisRound && !(Boolean) commitEarly.get()) {
      if (batchingWithSecondaryData)
        getLock();
      commitOrRollback(dConn, gConn, firstInThisRound);
      if (batchingWithSecondaryData)
        cleanUpFKHolds();

      if (!batchingWithSecondaryData)
        getLock();
      removeHoldKeysByThisTx(); // everyone clean up the keys
      if (!batchingWithSecondaryData)
        releaseLock();
      closeSelectForUpdateRS(); // close any select for update rs.
      if (batchingWithSecondaryData)
        releaseLock();

    } // commit later

    waitForBarrier();
    /*
     * //commit for RRs if (mixRR_RC) {
     * Log.getLogWriter().info("commit in RR, do nothing for RCs");
     * waitForBarrier(); }
     */

  }

  protected void waitForBarrier() {
    // move the ddl thread for HA out of the working threads.
    int numWaitingWorker = numOfWorkers;
    if (isHATest)
      numWaitingWorker = numOfWorkers - 1;
    AnyCyclicBarrier barrier = AnyCyclicBarrier.lookup(numWaitingWorker,
        "barrier");
    Log.getLogWriter().info("Waiting for " + numWaitingWorker + 
        " to meet at barrier");
    barrier.await();
  }

  public static void HydraTask_cycleStoreVms() {
    if (cycleVms)
      sqlNewTxTest.cycleStoreVms();
  }

  protected void commitOrRollbackGfxdOnly(Connection gConn) {
    int chanceToRollback = 20;
    if (random.nextInt(chanceToRollback) == 1 || (Boolean) rollbackGfxdTx.get()) {
      Log.getLogWriter().info("roll back the tx");
      rollbackGfxdTx.set(false); // reset for next round
      rollback(gConn);
    } else {
      Log.getLogWriter().info("commit the tx");
      commitGfxdOnly(gConn);
    }
    /*
     * int num = (Integer) iteration.get();
     * Log.getLogWriter().info("finishing this round of iteration " + num);
     * iteration.set(++num); //set for next round
     */
  }

  // return true when commit successfully
  protected boolean commitGfxdOnly(Connection gConn) {
    try {
      commit(gConn);
    } catch (TestException te) {
      if (isHATest
          && (te.getMessage().contains("X0Z16")
              || te.getMessage().contains("40XD0") || te.getMessage().contains(
              "40XD2"))) {
        Log.getLogWriter().info("got expected node failure exception, " +
        		"continuing test");
        return false;
      } else
        throw te;
    }

    return true;
  }

  protected void commitOrRollback(Connection dConn, Connection gConn,
      boolean firstCommit) {
    if (dConn != null) {
      if (!batchingWithSecondaryData) {
        getLock(); // only no batching need lock, batching lock has been hold
                   // before this commit
        processDerbyOps(dConn, firstCommit); // only first commit could verify
                                             // query results
        releaseLock();
      } else {
        processDerbyOpsWithBatching(dConn, firstCommit);
        // allow gfxd commit fail with sqlException, as ops may be batched until
        // commit time
      }
    }

    int chanceToRollback = firstCommit ? 3 : 20;
    if (random.nextInt(chanceToRollback) == 1 || (Boolean) rollbackGfxdTx.get()) {
      Log.getLogWriter().info("roll back the tx");
      rollbackGfxdTx.set(false); // reset for next round
      rollback(dConn);
      rollback(gConn);
    } else {
      doCommit(dConn, gConn, firstCommit);
    }

    if (useNewTables) {
      int num = (Integer) iteration.get();
      Log.getLogWriter().info("finishing this round of iteration " + num);
      iteration.set(++num); // set for next round
    }

    // after commit or rollback, clean up the derby exceptions list
    if (batchingWithSecondaryData) {
      Log.getLogWriter().info("clean up derby exceptins");
      SQLDistTxTest.derbyExceptionsWithBatching
          .set(new ArrayList<SQLException>());
    }
  }

  protected void doCommit(Connection dConn, Connection gConn,
      boolean firstCommit) {
    Log.getLogWriter().info("commit the tx");
    boolean success = true;
    if (!batchingWithSecondaryData) {
      if (isHATest) { // commit might fail due to HA, provide this to handle the
                      // exception
        success = commitWithHA(gConn);
        if (success)
          commit(dConn);
        else
          rollback(dConn);
      } else {
        commit(gConn);
        commit(dConn);
      }
    } else {
      success = commitBatching(gConn, firstCommit);
      if (success)
        commit(dConn);
      else
        rollback(dConn);
    }
  }

  protected void doGfxdDMLOpByOne(Connection gConn, int size, boolean withDerby) {
    for (int i = 0; i < size; i++) {
      doOneGfxdDMLOpByOne(gConn, withDerby);
    }
  }

  @SuppressWarnings("static-access")
  protected void doOneGfxdDMLOpByOne(Connection gConn, boolean withDerby) {
    boolean succeed = true;
    int table = dmlTables[random.nextInt(dmlTables.length)]; 
    // get random table to perform dml
    DMLDistTxStmtIF dmlStmt = dmlDistTxFactory.createDMLDistTxStmt(table); 
    // dmlStmt of a table

    if (table != dmlTxFactory.TRADE_CUSTOMERS
        && table != dmlTxFactory.TRADE_NETWORTH
        && table != dmlTxFactory.TRADE_SECURITIES
        && table != dmlTxFactory.TRADE_PORTFOLIO
        && table != dmlTxFactory.TRADE_SELLORDERS)
      return;
    // perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
      if (!(Boolean) commitEarly.get())
        succeed = dmlStmt.insertGfxd(gConn, withDerby);
      else
        return;
      // do not do insert if they are going to be committed earlier
    } else if (operation.equals("update")) {
      succeed = dmlStmt.updateGfxd(gConn, withDerby);
    } else if (operation.equals("delete"))
      succeed = dmlStmt.deleteGfxd(gConn, withDerby);
    else if (operation.equals("query"))
      succeed = dmlStmt.queryGfxd(gConn, withDerby);
    else
      throw new TestException("Unknown entry operation: " + operation);

    // insert into networth table() -- should use trigger to insert the row
    if (table == dmlTxFactory.TRADE_CUSTOMERS && operation.equals("insert")
        && succeed) {
      if ((Boolean) batchInsertToCustomersSucceeded.get()) {
        Log.getLogWriter().info("inserting into networth table");
        if (SQLTest.hasJSON) {
          TradeNetworthDMLDistTxStmtJson networth = new TradeNetworthDMLDistTxStmtJson();
          succeed = networth.insertGfxd(gConn, withDerby, (Integer)cidInserted
              .get());
        }
        else {
          TradeNetworthDMLDistTxStmt networth = new TradeNetworthDMLDistTxStmt();
          succeed = networth.insertGfxd(gConn, withDerby, (Integer)cidInserted
              .get());
        }
      } else {
        Log
            .getLogWriter()
            .info(
                "insert into customers using batch failed, do"
                    + " not insert to networth table due to #43170. as the sqlf fails the whole batch");
        batchInsertToCustomersSucceeded.set(true);
      }
    }

    /*
     * comment out the followings once #42703 is supported
     */
    if (!succeed || needNewConnection()) {
      rollback(gConn); // work around #42703
      if (batchingWithSecondaryData)
        cleanUpFKHolds(); // clean up foreign key holds
      removeHoldKeysByThisTx();
      closeSelectForUpdateRS(); // close any select for update rs.
      resetDerbyOps();
    } // lock not held and transaction rolled back
  }

  protected void doGfxdDMLOpByOne(Connection dConn, Connection gConn, int size,
      boolean withDerby) {
    for (int i = 0; i < size; i++) {
      doOneGfxdDMLOpByOne(dConn, gConn, withDerby);
    }
  }

  @SuppressWarnings("unchecked")
  protected void doOneGfxdDMLOpByOne(Connection dConn, Connection gConn,
      boolean withDerby) {
    doOneGfxdDMLOpByOne(gConn, withDerby); // operates in gfxd
    if (!ticket43170fixed && withDerby) {
      // check if there is an constraint violation
      ArrayList<Object[]> derbyOps = (ArrayList<Object[]>) SQLDistTxTest.derbyOps
          .get();
      if (derbyOps != null && derbyOps.size() > 0) { // no ops as some table
                                                     // operations are not
                                                     // implemented yet
        Object[] data = derbyOps.get(derbyOps.size() - 1);
        SQLException gfxdse = (SQLException) data[data.length - 1];
        if (gfxdse != null) {
          if (gfxdse.getSQLState().equals("23503")
              || gfxdse.getSQLState().equals("23505")
              || gfxdse.getSQLState().equals("23513")) {
            processDerbyOps(dConn, true); // see if indeed the constraint occurs

            // rollback(gConn); //not needed due to #43170
            removeHoldKeysByThisTx(); // due to #43170
            closeSelectForUpdateRS(); // close any select for update rs.
            rollback(dConn); // roll back the derby ops
            resetDerbyOps();
          } else {
            SQLHelper.handleSQLException(gfxdse);
            // do not expect any other exceptions
            // will handle expected exceptions for cancellation, securities etc
            // once the coverage is added.
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected void processDerbyOps(Connection dConn, boolean compareQuery) {
    if (dConn == null)
      return;
    ArrayList<Object[]> ops = (ArrayList<Object[]>) derbyOps.get();
    if (ops == null)
      Log.getLogWriter().info("derby ops is not set");
    else {
      for (int i = 0; i < ops.size(); i++) {
        Object[] derbyOp = ops.get(i);
        DMLDistTxStmtIF dmlStmt = null;
        if (useNewTables)
          dmlStmt = dmlDistTxFactory
              .createNewTablesDMLDistTxStmt((Integer) derbyOp[0]);
        else
          dmlStmt = dmlDistTxFactory.createDMLDistTxStmt((Integer) derbyOp[0]);
        String operation = (String) derbyOp[1];

        if (operation.equals("insert")) {
          dmlStmt.insertDerby(dConn, i);
        } else if (operation.equals("update")) {
          dmlStmt.updateDerby(dConn, i);
        } else if (operation.equals("delete"))
          dmlStmt.deleteDerby(dConn, i);
        else if (operation.equals("query")) {
          if (useNewTables) {
            if (!queryOpTimeNewTables && compareQuery)
              dmlStmt.queryDerby(dConn, i);
            // query processed at the op time instead of waiting to end of txn
          }
        } else
          throw new TestException("Unknown entry operation: " + operation);
      }
      resetDerbyOps();
    }
  }

  @SuppressWarnings("unchecked")
  protected void processDerbyQuery(Connection dConn) {
    if (dConn == null)
      return;
    ArrayList<Object[]> ops = (ArrayList<Object[]>) derbyOps.get();
    int lastIndex = ops.size() - 1;
    Object[] derbyOp = ops.get(lastIndex);
    DMLDistTxStmtIF dmlStmt = null;
    if (useNewTables)
      dmlStmt = dmlDistTxFactory
          .createNewTablesDMLDistTxStmt((Integer) derbyOp[0]);
    else
      throw new TestException(
          "only new tables in txn should invoke this method");

    dmlStmt.queryDerby(dConn, lastIndex);
  }

  @SuppressWarnings("unchecked")
  protected void processDerbyOpsWithBatching(Connection dConn,
      boolean compareQuery) {
    if (dConn == null)
      return;
    ArrayList<Object[]> ops = (ArrayList<Object[]>) derbyOps.get();
    if (ops == null)
      Log.getLogWriter().info("derby ops is not set");
    else {
      for (int i = 0; i < ops.size(); i++) {
        Object[] derbyOp = ops.get(i);
        DMLDistTxStmtIF dmlStmt = null;
        if (useNewTables)
          dmlStmt = dmlDistTxFactory
              .createNewTablesDMLDistTxStmt((Integer) derbyOp[0]);
        else
          dmlStmt = dmlDistTxFactory.createDMLDistTxStmt((Integer) derbyOp[0]);

        String operation = (String) derbyOp[1];

        if (operation.equals("insert")) {
          dmlStmt.insertDerby(dConn, i);
        } else if (operation.equals("update")) {
          dmlStmt.updateDerby(dConn, i);
        } else if (operation.equals("delete"))
          dmlStmt.deleteDerby(dConn, i);
        else if (operation.equals("query")) {
          if (compareQuery)
            dmlStmt.queryDerby(dConn, i);
        } else
          throw new TestException("Unknown entry operation: " + operation);
      }
      resetDerbyOps();
    }
  }

  protected void resetDerbyOps() {
    derbyOps.set(new ArrayList<Object[]>());
    Log.getLogWriter().info("derby ops has been reset");
  }

  protected void rollback(Connection conn) {
    boolean isTicket48177fixed = false;
    if (conn == null)
      return;
    try {
      conn.rollback();
    } catch (SQLException se) {
      if (isEdge && isHATest && !isTicket48177fixed
          && SQLHelper.gotTXNodeFailureException(se)) {
        Log
            .getLogWriter()
            .info(
                "got node failure exception during Tx due to #48177, continue the testing");
      } else
        SQLHelper.handleSQLException(se);
    }
  }

  @SuppressWarnings("unchecked")
  protected void removeHoldKeysByThisTx() {
    if (!batchingWithSecondaryData) {
      // remove primary/unique key
      SharedMap modifiedKeysByAllTx = SQLTxBB.getBB().getSharedMap();
      Set<String> alreadyHeldKeysThisTx = ((HashMap<String, Integer>) curTxModifiedKeys
          .get()).keySet();
      Log.getLogWriter().info(
          "removing the keys from the key Map that were held by this txn");
      for (String key : alreadyHeldKeysThisTx) {
        Log.getLogWriter().info("key to be removed is " + key);
        modifiedKeysByAllTx.remove(key);
      }
    } else {
      Integer myTxId = (Integer) curTxId.get();
      SharedMap modifiedKeysByAllTx = SQLTxBatchingBB.getBB().getSharedMap();
      Set<String> alreadyHeldKeysThisTx = ((HashMap<String, Integer>) curTxModifiedKeys
          .get()).keySet();
      Log.getLogWriter().info("removing the keys from the batchingBB Map that " +
      		"were held by this tx");
      for (String key : alreadyHeldKeysThisTx) {
        Log.getLogWriter().info("key to be removed is " + key);
        ArrayList<Integer> txIdsForKey = (ArrayList<Integer>) modifiedKeysByAllTx
            .get(key);

        if (txIdsForKey != null) {
          boolean contains = txIdsForKey.remove(myTxId);
          if (!contains)
            Log.getLogWriter().info("this txId does not hold the local lock yet");

          if (txIdsForKey.size() > 0)
            modifiedKeysByAllTx.put(key, txIdsForKey); // put back as other txid
                                                       // could still hold the
                                                       // lock
          else
            modifiedKeysByAllTx.remove(key);
        }
      }
    }

    // reset keys hold in current txn.
    curTxModifiedKeys.set(new HashMap<String, Integer>());

    // handle batching enabled case
    if (batchingWithSecondaryData) {
      cleanUpFKHolds();
    }

    // also remove range foreign key held by this tx as well
    removeAllRangeFKsByThisTx();
  }

  @SuppressWarnings("unchecked")
  protected void removeTxIdFromBatchingFKs(HashSet<String> fks) {
    Integer myTxId = (Integer) SQLDistTxTest.curTxId.get();
    hydra.blackboard.SharedMap holdingFKTxIds = SQLTxBatchingFKBB.getBB()
        .getSharedMap();
    for (String fk : fks) {
      HashSet<Integer> txIds = (HashSet<Integer>) holdingFKTxIds.get(fk);
      Log.getLogWriter().info("holdingFKTxIds removed myTxId: " + myTxId
              + " for the this foreing key: " + fk);
      txIds.remove(myTxId);
      holdingFKTxIds.put(fk, txIds);
    }
  }

  @SuppressWarnings("unchecked")
  protected void cleanUpFKHolds() {
    // foreign key holds
    HashSet<String> fks = (HashSet<String>) foreignKeyHeldWithBatching.get();
    removeTxIdFromBatchingFKs(fks); // clean up the holding txId from the
                                    // batchingFKBB
    fks.clear();
    foreignKeyHeldWithBatching.set(fks); // reset the fk hold set

    // parent key holds
    HashSet<String> parentKeys = (HashSet<String>) parentKeyHeldWithBatching
        .get();
    parentKeys.clear();
    parentKeyHeldWithBatching.set(parentKeys);
  }

  @SuppressWarnings("unchecked")
  protected void removeAllRangeFKsByThisTx() {
    int txId = (Integer) curTxId.get();
    hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB()
        .getSharedMap();
    ArrayList<String> fkHeld = (ArrayList<String>) curTxFKHeld.get();
    for (String fk : fkHeld) {
      Log.getLogWriter().info("foreign key hold is " + fk);
      RangeForeignKey rangeKey = (RangeForeignKey) rangeForeignKeys.get(fk);
      rangeKey.removeAllPartialRangeKeyByCurTx(txId);
      rangeKey.removeWholeRangeKey(txId);
      if (rangeKey.getWholeRangeKeyHeldByTxId() == txId) {
        Log.getLogWriter().info("rangeKey.getWholeRangeKeyAlreadyHeld() is "
           + rangeKey.getWholeRangeKeyAlreadyHeld());
        Log.getLogWriter().info("rangeKey.getWholeRangeKeyHeldByTxId() is "
                + rangeKey.getWholeRangeKeyHeldByTxId());

        throw new TestException(
            "Range key issue, whole range key is not removed for the tx");
      }

      rangeForeignKeys.put(fk, rangeKey); // put over to shared map

      RangeForeignKey rk = (RangeForeignKey) rangeForeignKeys.get(fk);
      if (rk.getWholeRangeKeyHeldByTxId() == txId) {
        throw new TestException("Not able to put the range key over to the BB");
      }
    }
    curTxFKHeld.set(new ArrayList<String>()); // no more fk held by this tx
  }

  @SuppressWarnings("unchecked")
  protected void closeSelectForUpdateRS() {
    ArrayList<ResultSet> selectForUpdateRSList = (ArrayList<ResultSet>) selectForUpdateRS
        .get();
    for (ResultSet rs : selectForUpdateRSList) {
      try {
        rs.close();
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }
    for (int i = 0; i < selectForUpdateRSList.size(); i++) {
      selectForUpdateRSList.remove(i);
    }
    selectForUpdateRS.set(selectForUpdateRSList);
  }

  public static void HydraTask_createMonitoringTable() {
    sqlNewTxTest.createMonitoringTable();
  }

  protected void createMonitoringTable() {
    Connection gConn = getGFEConnection();
    String sql = "create table trade.monitor "
        + "(tname varchar(20) not null, pk1 int not null, pk2 int ,"
        + "insertCount int, updateCount int, "
        + "deleteCount int, constraint monitor_pk primary key (tname, pk1, pk2))"
        + getPartitionByClause();
    try {
      gConn.createStatement().execute(sql);
      Log.getLogWriter().info("successfully " + sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  protected String getPartitionByClause() {
    String partition;
    int whichPartition = random.nextInt(5);
    switch (whichPartition) {
    case 0:
      partition = " partition by List (tname) values ('customers', 'securities', 'networth'"
          + "'portfolio', 'buyorders', 'sellorders')";
    case 1:
      partition = " partition by column (tname) ";
      break;
    case 2:
      partition = " partition by column (pk1) ";
      break;
    case 3:
      partition = " ";
      break;
    case 4:
      return " replicate ";
      // no change for PR as it is replicate
    default:
      throw new TestException("should not happen");
    }
    SQLBB.getBB().getSharedCounters().increment(SQLBB.numOfPRs);
    return partition + SQLPrms.getRedundancyClause(0) + getPersistence();
    // same redundancy as others for HA
  }

  @SuppressWarnings("unchecked")
  private static String getPersistence() {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.gfePersistExtension,
        new HydraVector());
    if (statements.size() == 0)
      return " ";
    else
      return " PERSISTENT ";

  }

  protected void doDMLGfxdOnlyOps(Connection gConn) {
    int txId = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(
        SQLTxBB.txId);
    curTxId.set(txId);
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid() + 
        " and txId is " + txId);
    boolean withDerby = false;
    int total = 20;
    int numOfIter = random.nextInt(total) + 1;
    for (int i = 0; i < numOfIter; i++) {
      gConn = doDMLGfxdOnlyOp(gConn, 1, withDerby);
    }
    commitOrRollbackGfxdOnly(gConn);
  }

  protected Connection doDMLGfxdOnlyOp(Connection gConn, int size,
      boolean withDerby) {
    for (int i = 0; i < size; i++) {
      if (isHATest && (Boolean) needNewConnAfterNodeFailure.get()) {
        closeConnectionIgnoreFailure(gConn);
        gConn = getNewGfxdConnection(); // use new connection
        needNewConnAfterNodeFailure.set(false);
      }
      doOneGfxdOnlyDMLOp(gConn, withDerby);
    }
    return gConn;
  }
  
  

  @SuppressWarnings("static-access")
  protected void doOneGfxdOnlyDMLOp(Connection gConn, boolean withDerby) {
    boolean succeed = true;
    int table = dmlTables[random.nextInt(dmlTables.length)]; // get random table
                                                             // to perform dml
    DMLDistTxStmtIF dmlStmt = dmlDistTxFactory.createDMLDistTxStmt(table); // dmlStmt
                                                                           // of
                                                                           // a
                                                                           // table

    if (table != dmlTxFactory.TRADE_CUSTOMERS
        && table != dmlTxFactory.TRADE_NETWORTH
        && table != dmlTxFactory.TRADE_SECURITIES
        && table != dmlTxFactory.TRADE_PORTFOLIO
        && table != dmlTxFactory.TRADE_SELLORDERS)
      return;
    // perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
      succeed = dmlStmt.insertGfxd(gConn, withDerby);
    } else if (operation.equals("update")) {
      succeed = dmlStmt.updateGfxd(gConn, withDerby);
    } else if (operation.equals("delete"))
      succeed = dmlStmt.deleteGfxd(gConn, withDerby);
    else if (operation.equals("query"))
      succeed = dmlStmt.queryGfxd(gConn, withDerby);
    else
      throw new TestException("Unknown dml operation: " + operation);

    // insert into networth table() -- should use trigger to insert the row
    if (table == dmlTxFactory.TRADE_CUSTOMERS && operation.equals("insert")
        && succeed) {
      Log.getLogWriter().info("inserting into networth table");
      TradeNetworthDMLDistTxStmt networth = new TradeNetworthDMLDistTxStmt();
      if (testUniqueKeys)
        succeed = networth.insertGfxd(gConn, withDerby, AbstractDMLStmt
            .getCid(gConn)); // random
      else
        succeed = networth.insertGfxd(gConn, withDerby, AbstractDMLStmt
            .getCid()); // random
    }

    if (!succeed) {
      Log.getLogWriter().info("this tx failed");
    }

  }

  public static void HydraTask_createMonitorTriggers() {
    sqlNewTxTest.createMonitorTriggers();
  }

  protected void createMonitorTriggers() {
    Connection gConn = getGFEConnection();
    TxTriggers.createMonitorTriggers(gConn);
  }

  public static void HydraTask_createTriggerProcedures() {
    sqlNewTxTest.createTriggerProcedures();
  }

  protected void createTriggerProcedures() {
    Connection gConn = getGFEConnection();
    TxTriggerProcedure.createTriggerProcedures(gConn);
  }

  public static void HydraTask_setTableFor42084() throws SQLException {
    sqlNewTxTest.setTableFor42084();
  }

  protected void setTableFor42084() throws SQLException {
    Connection gConn = getGFEConnection();
    String sql = "create table simpleTable "
        + "(id int not null, color varchar(10) not null, "
        + "condition int not null, constraint st_pk primary key (id))";
    if (SQLTest.isOffheap) {
      sql = sql + " OFFHEAP";
    }
    gConn.createStatement().execute(sql);
    Log.getLogWriter().info("done " + sql);

    String insert = "insert into simpleTable values (?, ?, ?)";
    PreparedStatement ps = gConn.prepareStatement(insert);

    for (int i = 1; i < 101; i++) {
      ps.setInt(1, i);
      ps.setInt(3, i);

      if (i < 31)
        ps.setString(2, "green");
      else if (i < 71)
        ps.setString(2, "red");
      else
        ps.setString(2, "yellow");
      ps.execute();
    }

    Log.getLogWriter().info("inserted initial data ");
  }

  public static void HydraTask_doDML42084() {
    sqlNewTxTest.doDML42084();
  }

  protected void doDML42084() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    doDML42084(gConn);

    closeGFEConnection(gConn);
    Log.getLogWriter().info("done dmlOp");
  }

  protected void doDML42084(Connection gConn) {
    boolean firstInThisRound = false;
    boolean secondInThisRound = false;
    boolean verifyGreen = true;
    boolean verifyYellow = true;
    String sql;
    int num = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(
        SQLBB.firstInRound);
    if (num == 1) {
      latch = new CountDownLatch(numOfWorkers - 2);
      // num of threads should perform the work before the two verifying threads
      // resumes
    }
    waitForBarrier();

    if (num == 1) {
      firstInThisRound = true;
      Log.getLogWriter().info("I am the first in this round");
      sql = "update simpleTable set color = 'green' where condition <=30";
      Log.getLogWriter().info(sql);
      MemHeapScanController.setWaitForLatchForTEST(10);
      MemHeapScanController.setWaitObjectAfterFirstQualifyForTEST(latch);
      for(int i=0; i< 10; i++) {
        try {
          Log.getLogWriter().info("RR: doDML42084 " + i + " times");
          int count = gConn.createStatement().executeUpdate(sql);
          if (count == 0)
            verifyGreen = false; // avoid the case no qualified row in the gfxd
          break;
        } catch (SQLException se) {
          if (se.getMessage().contains("Conflict detected in transaction operation and it will abort") && i < 9) {
            Log.getLogWriter().info("RR: detected conflict , retrying");
            continue;
          }
          dumpResults();
          SQLHelper.handleSQLException(se);
        }
      }
    } else if (num == 2) {
      secondInThisRound = true;
      Log.getLogWriter().info("I am the second in this round");
      sql = "update simpleTable set color = 'yellow' where condition >70";
      Log.getLogWriter().info(sql);
      MemHeapScanController.setWaitForLatchForTEST(10);
      MemHeapScanController.setWaitObjectAfterFirstQualifyForTEST(latch);
      for(int i=0; i< 10; i++) {
        try {
          Log.getLogWriter().info("RR: doDML42084 " + i + " times");
          int count = gConn.createStatement().executeUpdate(sql);
          if (count == 0)
            verifyYellow = false; // avoid the case no qualified row in the gfxd
          break;
        } catch (SQLException se) {
          if (se.getMessage().contains("Conflict detected in transaction operation and it will abort") && i < 9) {
            Log.getLogWriter().info("RR: detected conflict , retrying");
            continue;
          }
          dumpResults();
          SQLHelper.handleSQLException(se);
        }
      }
    } else {
      sql = random.nextBoolean() ? "update simpleTable set condition = "
          + (random.nextInt(100) + 1) + " where id = "
          + (random.nextInt(100) + 1) : "update simpleTable set condition = "
          + (random.nextInt(100) + 1) + " where condition = "
          + (random.nextInt(100) + 1); // this may fail to find qualified row
      Log.getLogWriter().info(sql);
      MemHeapScanController.setWaitForLatchForTEST(0);
      try {
        gConn.createStatement().executeUpdate(sql);
      } catch (SQLException se) {
        if (se.getSQLState().equals("X0Z02")) {
          Log.getLogWriter().info("got expected conflict exception");
        } else {
          SQLHelper.handleSQLException(se);
        }
      } finally {
        commit(gConn);
        latch.countDown();
        Log.getLogWriter().info("latch count is: " + latch.getCount());
      }
    }

    // finishing
    if (firstInThisRound) {
      SQLBB.getBB().getSharedCounters().zero(SQLBB.firstInRound); // for next
      // round
      int curLatchCount = (int) latch.getCount();
      Log.getLogWriter().info("latch count is: " + curLatchCount);
      if (curLatchCount != 0) {
        throw new TestException("the update statement finishes and does not "
            + "waitObjectAfterFirstQualify until the "
            + "latch count to become zero");
      }
      commit(gConn);
      if (verifyGreen)
        verifyGreen();
    } else if (secondInThisRound) {
      Log.getLogWriter().info("latch count is: " + latch.getCount());
      commit(gConn);
      if (verifyYellow)
        verifyYellow();
    }
    waitForBarrier();
  }

  protected void verifyGreen() {
    String sql = "select distinct color from simpleTable where condition <31";
    Connection gConn = getGFEConnection();
    try {
      Log.getLogWriter().info(sql);
      ResultSet rs = gConn.createStatement().executeQuery(sql);
      Log.getLogWriter().info("finished select query");
      if (rs.next()) {
        if (!rs.getString(1).equalsIgnoreCase("green")) {
          dumpResults();
          throw new TestException("found color is not green for condition < 31");
        }
      }
      if (rs.next()) {
        ResultSetHelper.sendBucketDumpMessage();
        ResultSetHelper.sendResultMessage();
        throw new TestException(
            "found more than one color for condition < 31 "
                + "while this update awaits other possible conflict update to complete");
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  protected void verifyYellow() {
    String sql = "select distinct color from simpleTable where condition >70";
    Connection gConn = getGFEConnection();
    try {
      Log.getLogWriter().info(sql);
      ResultSet rs = gConn.createStatement().executeQuery(sql);
      Log.getLogWriter().info("finished select query");
      if (rs.next()) {
        if (!rs.getString(1).equalsIgnoreCase("yellow")) {
          ResultSetHelper.sendBucketDumpMessage();
          ResultSetHelper.sendResultMessage();
          throw new TestException(
              "found color is not yellow for condition > 70");
        }
      }
      if (rs.next()) {
        dumpResults();
        throw new TestException(
            "found more than one color for condition > 70 "
                + "while this update awaits other possible conflict update to complete");
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void dumpResults() {
    SQLTest.dumpResults(); // use local dump now
    // ResultSetHelper.sendBucketDumpMessage();
    // ResultSetHelper.sendResultMessage();
  }

  @SuppressWarnings("unchecked")
  protected void cycleStoreVmsAsync(String target) {
    int numToKill = TestConfig.tab().intAt(StopStartPrms.numVMsToStop, 1);
    int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
    if (SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.stopStartVms) == 1) {
      int sleepMS = 1000;
      Log.getLogWriter().info("allow  " + sleepMS / 1000 + 
          " seconds before killing others");
      MasterController.sleepForMs(sleepMS);

      Object[] tmpArr = StopStartVMs.getOtherVMs(numToKill, target);
      // get the VMs to stop; vmList and stopModeList are parallel lists
      List<ClientVmInfo> vmList = (List<ClientVmInfo>) (tmpArr[0]);
      List<String> stopModeList = (List<String>) (tmpArr[1]);
      for (ClientVmInfo client : vmList) {
        PRObserver.initialize(client.getVmid());
      } // clear bb info for the vms to be stopped/started
      List<HydraSubthread> threadList = StopStartVMs.stopStartAsync(vmList,
          stopModeList);

      finishRestartVMs(vmList, threadList, numOfPRs);
    }
  }

  protected void finishRestartVMs(final List<ClientVmInfo> vmList,
      final List<HydraSubthread> threadList, final int numOfPRs) {
    Thread handleRestart = new Thread(new Runnable() {
      public void run() {
        joinThreads(vmList, threadList, numOfPRs);
      }
    });
    handleRestart.start();
  }

  protected void joinThreads(List<ClientVmInfo> vmList,
      List<HydraSubthread> threadList, int numOfPRs) {
    StopStartVMs.joinStopStart(vmList, threadList);
    Log.getLogWriter().info("Total number of PR is " + numOfPRs);
    PRObserver.waitForRebalRecov(vmList, 1, numOfPRs, null, null, false);

    SQLBB.getBB().getSharedMap().put("vmCycled", "true");
    SQLBB.getBB().getSharedCounters().zero(SQLBB.stopStartVms);
  }

  protected void doIndexOp() {
    Log.getLogWriter().info("do index op");
    Thread doIndexOp = new Thread(new Runnable() {
      public void run() {
        createIndex();
      }
    });
    doIndexOp.start();
  }

  public static void HydraTask_setInitialData() {
    sqlNewTxTest.setInitialData();
  }

  // update the column value that going to be updated and verified during the
  // test
  protected void setInitialData() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    setInitialData(gConn);
    int maxCid = (int) SQLBB.getBB().getSharedCounters().read(
        SQLBB.tradeCustomersPrimary);
    Log.getLogWriter().info("maxCid in the test is " + maxCid);
    concUpdateTxMaxCid = (int) SQLTxBB.getBB().getSharedCounters().add(
        SQLTxBB.concUpdateTxMaxCid, maxCid);
    closeGFEConnection(gConn);
  }

  protected void setInitialData(Connection conn) {
    try {
      Statement s = conn.createStatement();
      String[] setInitData = {
          "update trade.portfolio set qty = 0, availQty = 0",
          "update trade.sellorders set ask = 0" };

      for (String sql : setInitData) {
        Log.getLogWriter().info(sql);
        s.executeUpdate(sql);
      }
      commit(conn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_concUpdateTx() {
    sqlNewTxTest.concUpdateTx();
  }

  protected void concUpdateTx() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    int num = 2; // reduce the number of the thread concurrently performing this
                 // tasks
    int count = 0;
    synchronized (lock) {
      count = ++concUpdateCount;
    }

    if (count < num) {
      gConn = concUpdateTx(gConn);
      synchronized (lock) {
        --concUpdateCount;
      }
    } else {
      synchronized (lock) {
        --concUpdateCount;
      }
      doDMLGfxdOnlyOps(gConn);
    }

    closeGFEConnection(gConn);
  }

  protected Connection concUpdateTx(Connection conn) {
    int whichUpdate = random.nextInt(concUpdateStatements.length);
    PreparedStatement stmt = getConcUpdateStmt(conn, whichUpdate);
    while (stmt == null && isHATest) {
      closeConnectionIgnoreFailure(conn);
      conn = getNewGfxdConnection();
      stmt = getConcUpdateStmt(conn, whichUpdate);
      int msToSleep = 3000;
      MasterController.sleepForMs(msToSleep);
    }

    if (concUpdateTxMaxCid == 0) {
      concUpdateTxMaxCid = (int) SQLTxBB.getBB().getSharedCounters().read(
          SQLTxBB.concUpdateTxMaxCid);
    }
    int maxCid = concUpdateTxMaxCid;
    int tid = getMyTid() + 1;
    int numOfRows = random.nextInt(10) + 8; // limit how many rows to be updated
                                            // in one tx
    int numIter = maxCid / numOfRows;
    if (maxCid < numIter)
      throw new TestException("test issue -- not enough cid being inserted");
    int interval = maxCid / numIter;
    for (int i = 0; i <= numIter; i++) {
      boolean success = false;
      int lowCid = i * interval;
      int highCid = (i == numIter) ? maxCid : (i + 1) * interval;
      boolean[] needNewConn = new boolean[1];
      do {
        // perform update
        success = updateGfxdTx(stmt, tid, lowCid, highCid, whichUpdate,
            needNewConn);
        if (!success) {
          if (needNewConn[0]) {
            closeConnectionIgnoreFailure(conn);
            conn = getNewGfxdConnection();
            stmt = getConcUpdateStmt(conn, whichUpdate);
          }
          continue;
        }
        // commit
        success = commitGfxdOnly(conn);
        if (!success) {
          closeConnectionIgnoreFailure(conn);
          conn = getNewGfxdConnection();
          stmt = getConcUpdateStmt(conn, whichUpdate);
        }
      } while (!success);
    }
    // qty is being addded by tid
    if (whichUpdate == 0)
      SQLTxBB.getBB().getSharedCounters().add(SQLTxBB.concUpdateTxPortfolioQty,
          tid);

    return conn;
  }

  protected PreparedStatement getConcUpdateStmt(Connection conn, int whichUpdate) {
    PreparedStatement stmt = null;
    try {
      stmt = conn.prepareStatement(concUpdateStatements[whichUpdate]);
      Log.getLogWriter().info(concUpdateStatements[whichUpdate]);
    } catch (SQLException se) {
      if (SQLHelper.gotTXNodeFailureException(se) && isHATest) {
        Log
            .getLogWriter()
            .info(
                "Got node failure exception during preparing statement, continue tests");
      } else
        SQLHelper.handleSQLException(se);
    }
    return stmt;
  }

  // Read_committed isolation only
  protected Connection getNewGfxdConnection() {
    if (isEdge)
      return new SQLDistTxClientTest().getGFXDClientTxConnection();
    else
      return getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
  }

  // used for update gfxd only (with read_committed)
  protected boolean updateGfxdTx(PreparedStatement stmt, int tid, int lowCid,
      int highCid, int whichUpdate, boolean[] needNewConn) {
    boolean success = false;
    if (stmt == null) {
      if (isHATest) {
        needNewConn[0] = true;
        return false;
      } else {
        throw new TestException("In non HA test, PreparedStatement is " + stmt);
      }
    }
    while (!success) {
      try {
        switch (whichUpdate) {
        case 0:
          stmt.setInt(1, tid);
          Log.getLogWriter().info("added qty is " + tid + " lowCid is " + lowCid + 
              " highCid is " + highCid + " ");
          break;
        case 1:
          stmt.setBigDecimal(1, new BigDecimal(Double
              .toString((double) tid / 10)));
          Log.getLogWriter().info("added ask is " + (double) tid / 10 + 
              " lowCid is " + lowCid + " highCid is " + highCid + " ");
          break;
        default:
          throw new TestException(
              "test issue -- incorrect update statment is chosen");
        }
        stmt.setInt(2, lowCid);
        stmt.setInt(3, highCid);
        stmt.executeUpdate();
        success = true;
      } catch (SQLException se) {
        if (se.getSQLState().equals("X0Z02")) {
          Log.getLogWriter().info("Got expected conflict exception, " +
          		"will retry the tx");
          int msToSleep = 3000;
          MasterController.sleepForMs(random.nextInt(msToSleep) + getMyTid()
              * 100);
          needNewConn[0] = false;
          // rollback(conn);
          return false;
        } else if (AbstractDMLStmt.gfxdtxHANotReady && isHATest
            && SQLHelper.gotTXNodeFailureException(se)) {
          Log
              .getLogWriter()
              .info(
                  "got node failure exception during Tx with HA support, will retry the tx");
          int msToSleep = 3000;
          MasterController.sleepForMs(random.nextInt(msToSleep));
          needNewConn[0] = true;
          return false;
        } else
          SQLHelper.handleSQLException(se);
      }
    }
    return true;
  }

  public static void HydraTask_verifyConcUpdateTx() {
    sqlNewTxTest.verifyConcUpdateTx();
  }

  protected void verifyConcUpdateTx() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    if (isTicket43909fixed)
      verifyConcUpdateTx(gConn);
    closeGFEConnection(gConn);
  }

  protected void verifyConcUpdateTx(Connection conn) {
    try {
      Statement s = conn.createStatement();
      String selectPortfolio = "select cid, sid, qty from trade.portfolio "
          + "where cid <= " + concUpdateTxMaxCid + " order by cid";
      String selectSellorders = "select oid, cid, ask from trade.sellorders "
          + "where cid <= " + concUpdateTxMaxCid;
      /*
       * "select distinct qty from trade.portfolio",
       * "select distinct ask from trade.sellorders"
       */

      ResultSet rs = s.executeQuery(selectPortfolio);
      int qty = (int) SQLTxBB.getBB().getSharedCounters().read(
          SQLTxBB.concUpdateTxPortfolioQty);
      Log.getLogWriter().info("updated qty should be " + qty);
      List<Struct> list = ResultSetHelper.asList(rs, false);
      if (list != null && list.size() > 0) {
        for (Struct col : list) {
          if ((Integer) col.get("QTY") != qty) {
            throw new TestException(selectPortfolio
                + " should return same value " + qty
                + " for qty, but it does not for" + " cid: " + col.get("CID")
                + " sid: " + col.get("SID") + "\n"
                + ResultSetHelper.listToString(list));
          }
        }
        rs.close();
      }
      Log.getLogWriter().info("verified qty in trade.portfolio table is correct.");

      rs = s.executeQuery(selectSellorders);
      list = null;
      BigDecimal ask = new BigDecimal(0);
      list = ResultSetHelper.asList(rs, false);
      if (list != null && list.size() > 0) {
        ask = (BigDecimal) list.get(0).get("ASK");
        for (Struct col : list) {
          if (ask.compareTo((BigDecimal) col.get("ASK")) != 0)
            throw new TestException(
                selectSellorders
                    + " should return same value for ask, but it does not for oid: "
                    + col.get("OID") + "\n"
                    + ResultSetHelper.listToString(list));
        }
        rs.close();
      }
      Log.getLogWriter().info("verified qty in trade.sellorder table is correct.");

      commit(conn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_useUpdatableResultSetForInit() {
    sqlNewTxTest.useUpdatableResultSet(true);
  }

  public static void HydraTask_useUpdatableResultSet() {
    sqlNewTxTest.useUpdatableResultSet(false);
  }

  // update the column value that going to be updated and verified during the
  // test
  protected void useUpdatableResultSet(boolean isInitTask) {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    if (isTicket43935Fixed)
      useUpdatableResultSet(gConn, isInitTask);
    closeGFEConnection(gConn);
  }

  protected void useUpdatableResultSet(Connection conn, boolean isInitTask) {
    if (isHATest)
      throw new TestException("need to handle tx node failure condition "
          + "in the test, as #43935 is fixed");

    int cid1 = (concUpdateTxMaxCid == 0) ? random.nextInt(100) : random
        .nextInt(concUpdateTxMaxCid);
    int cid2 = cid1 + 10;
    try {
      Statement s = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_UPDATABLE);
      String[] updatableRs = { "select * from trade.customers where cid > "
          + cid1 + " and cid < " + cid2, };

      for (String sql : updatableRs) {
        ResultSet rs = s.executeQuery(sql);

        int prevCid = 0;
        boolean checkPrevRowLockNotHeld = false;
        while (rs.next()) {
          int cid = rs.getInt("CID");
          Log.getLogWriter().info("this row's cid is " + cid);
          if (random.nextBoolean()) {
            rs.updateString("CUST_NAME", "updated_custname");
            rs.updateRow();
            checkLockHeldForThisRow(cid);
            // according to comments in #43917 & #43937, normal updatable
            // resultset will
            // hold lock only after actually update the row.
            checkPrevRowLockNotHeld = false;
          } else {
            checkPrevRowLockNotHeld = true;
          }
          if (isInitTask & checkPrevRowLockNotHeld)
            checkLockNotHeldForPreviousRow(prevCid);

          rs.getString("ADDR");
          prevCid = cid;
        }
        rs.close();
      }
      commit(conn);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        if (isInitTask)
          throw new TestException(
              "only one ddl thread in this init task, we should not "
                  + "see the conflict exception" + TestHelper.getStackTrace(se));
        else
          ; // expected updatable result set
      } else
        SQLHelper.handleSQLException(se);
    }
  }

  // a lock of the current row should be held by the tx once the update is fire,
  // so another tx access to the row should gets conflict exception
  protected void checkLockHeldForThisRow(int cid) {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    try {
      Statement s = conn.createStatement();
      s.executeUpdate("update trade.customers set cust_name = 'custname' "
          + "where cid = " + cid);
      throw new TestException(
          "Updatable result set cursor does not obtain the lock on the current row");
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        // this is expected
        ;
      } else
        SQLHelper.handleSQLException(se);
    }
    try {
      conn.commit();
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        // TODO need to find out whether commit should throw conflict exception
        throw new TestException("commit got conflict exception");
      } else
        SQLHelper.handleSQLException(se);
    }
    closeGFEConnection(conn);
  }

  // a lock of the previous row should not be hold, if update is not actually
  // fired
  protected void checkLockNotHeldForPreviousRow(int cid) {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    try {
      Statement s = conn.createStatement();
      String name = "custname" + random.nextInt(1000);
      s.executeUpdate("update trade.customers set cust_name = '" + name
          + "' where cid = " + cid);

    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        throw new TestException("Updatable result set cursor does "
            + "not release the lock on the previous row");
      } else
        SQLHelper.handleSQLException(se);
    }
    commit(conn);
    closeGFEConnection(conn);
  }

  public static void HydraTask_useScrollableUpdatableResultSet() {
    sqlNewTxTest.useScrollableUpdatableResultSet();
  }

  protected void useScrollableUpdatableResultSet() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    if (isTicket43932Fixed && isTicket45071fixed)
      useScrollableUpdatableResultSet(gConn);
    closeGFEConnection(gConn);
  }

  protected void useScrollableUpdatableResultSet(Connection conn) {
    if (isHATest)
      throw new TestException("need to handle tx node failure condition "
          + "in the test, as #43932 and #45071 are fixed");

    if (concUpdateTxMaxCid == 0) {
      concUpdateTxMaxCid = (int) SQLTxBB.getBB().getSharedCounters().read(
          SQLTxBB.concUpdateTxMaxCid);
    }
    int tid = getMyTid() + 1;
    if (tid % 3 != 0 && random.nextInt(10) != 1) {
      Log
          .getLogWriter()
          .info(
              "will not exectue this method to reduce the concurrent updates in the table");
      return;
    }
    int maxCid = concUpdateTxMaxCid;
    int interval = 10;
    int numIter = maxCid / interval;
    boolean success = false;
    for (int i = 0; i <= numIter; i++) {
      int lowCid = i * interval;
      int highCid = (i == numIter) ? maxCid : (i + 1) * interval;
      success = updateScrollableRsTx(conn, tid, lowCid, highCid);
      while (!success) {
        success = updateScrollableRsTx(conn, tid, lowCid, highCid);
      }
      commit(conn);
    }

  }

  protected boolean updateScrollableRsTx(Connection conn, int tid, int lowCid,
      int highCid) {
    try {
      Statement s = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
          ResultSet.CONCUR_UPDATABLE);
      String sql = "select * from trade.networth where cid > " + lowCid
          + " and cid <= " + highCid;
      if (!isTicket43935Fixed)
        sql += " for update of securities";

      Log.getLogWriter().info(sql);
      ResultSet updatableRs = s.executeQuery(sql);
      int totalRows = 0;
      while (updatableRs.next())
        totalRows++;

      Log.getLogWriter().info("total rows are " + totalRows);
      boolean success = updateScrollableRsTx(updatableRs, tid, totalRows);
      updatableRs.close();
      return success;
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se); // should not get conflict for the query
    }
    return true;
  }

  protected boolean updateScrollableRsTx(ResultSet rs, int tid, int totalRows) {
    try {
      boolean success;
      if (totalRows == 0) {
        return true;
      }
      if (totalRows == 1) {
        rs.first();
        return updateURSRowTx(rs, tid);
      } else {
        int firstHalf = totalRows / 2;

        // second half rows to be updated
        rs.absolute(-(totalRows - firstHalf));
        success = updateURSRowTx(rs, tid);
        if (!success)
          return success;
        // if any one operation failed,
        // need to restart the tx as product automatically rollback the tx
        while (rs.next()) {
          success = updateURSRowTx(rs, tid);
          if (!success)
            return success;
        }

        // first half rows to be updated
        rs.absolute(firstHalf);
        success = updateURSRowTx(rs, tid);
        if (!success)
          return success;
        while (rs.previous()) {
          success = updateURSRowTx(rs, tid);
          if (!success)
            return success;
        }

        rs.last();
        if (random.nextInt(100) == 0) {
          if (rs.relative(-1))
            success = deleteURSRowTx(rs);
          if (!success)
            return success;
        }

      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
      // should not get conflict for moving position
      // as it acquires only update read lock
    }

    return true;
  }

  protected boolean updateURSRowTx(ResultSet updatableRs, int tid) {
    try {
      int cid = updatableRs.getInt("CID");
      BigDecimal sec = updatableRs.getBigDecimal("SECURITIES").add(
          new BigDecimal(tid));
      updatableRs.updateBigDecimal("SECURITIES", sec);
      updatableRs.updateRow();
      Log.getLogWriter().info("update trade.networth set securities to be " + sec 
          + " for cid: " + cid);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        SQLHelper.printSQLException(se);
        return false; // expected updatable result set
      } else
        SQLHelper.handleSQLException(se);
    }
    return true;
  }

  protected boolean deleteURSRowTx(ResultSet updatableRs) {
    try {
      int cid = updatableRs.getInt("CID");
      updatableRs.deleteRow();
      Log.getLogWriter().info("delete from trade.networth for cid: " + cid);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        SQLHelper.printSQLException(se);
        return false; // expected updatable result set
      } else
        SQLHelper.handleSQLException(se);
    }
    return true;
  }

  public static void HydraTask_useNonScrollableUpdatableResultSet() {
    sqlNewTxTest.useNonScrollableUpdatableResultSet();
  }

  protected void useNonScrollableUpdatableResultSet() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    // Connection gConn = getGFEConnection();
    if (isTicket43935Fixed)
      useNonScrollableUpdatableResultSet(gConn);
    closeGFEConnection(gConn);
  }

  protected void useNonScrollableUpdatableResultSet(Connection conn) {
    if (isHATest)
      throw new TestException("need to handle tx node failure condition "
          + "in the test, as #43935 is fixed");

    if (concUpdateTxMaxCid == 0) {
      concUpdateTxMaxCid = (int) SQLTxBB.getBB().getSharedCounters().read(
          SQLTxBB.concUpdateTxMaxCid);
    }
    int tid = getMyTid() + 1;
    int maxCid = concUpdateTxMaxCid;
    int interval = 10;
    int numIter = maxCid / interval;

    for (int i = 0; i <= numIter; i++) {
      int lowCid = i * interval;
      int highCid = (i == numIter) ? maxCid : (i + 1) * interval;
      boolean success = updateNonScrollableRsTx(conn, tid, lowCid, highCid);
      while (!success) {
        success = updateNonScrollableRsTx(conn, tid, lowCid, highCid);
      }
      commit(conn); // only one thread could work on this
      // Log.getLogWriter().info("tx is committed");
    }

  }

  protected boolean updateNonScrollableRsTx(Connection conn, int tid,
      int lowCid, int highCid) {
    try {
      Statement s = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_UPDATABLE);
      String sql = "select * from trade.networth where cid > " + lowCid
          + " and cid <= " + highCid;

      ResultSet updatableRs = s.executeQuery(sql);
      boolean success = updateNonScrollableRsTx(updatableRs, tid);
      updatableRs.close();
      return success;

    } catch (SQLException se) {
      SQLHelper.handleSQLException(se); // should not get conflict for the query
    }
    return true;
  }

  protected boolean updateNonScrollableRsTx(ResultSet rs, int tid) {
    try {
      boolean success;
      while (rs.next()) {
        success = updateURSRowTx(rs, tid);
        if (!success)
          return success;
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
      // should not get conflict for moving position forward
      // as it acquires only update read lock
    }

    return true;
  }

  public static void HydraTask_verifyUpdatbleRsTx() {
    sqlNewTxTest.verifyUpdatbleRsTx();
  }

  protected void verifyUpdatbleRsTx() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    verifyUpdatbleRsTx(gConn);
    closeGFEConnection(gConn);
  }

  protected void verifyUpdatbleRsTx(Connection conn) {
    try {
      Statement s = conn.createStatement();
      String selectNetworth = "select cid, securities from trade.networth "
          + "where cid <= " + concUpdateTxMaxCid + " order by cid";

      ResultSet rs = s.executeQuery(selectNetworth);
      List<Struct> list = ResultSetHelper.asList(rs, false);
      if (list != null) {
        BigDecimal sec = (BigDecimal) list.get(0).get("SECURITIES");
        for (Struct col : list) {
          if (!((BigDecimal) col.get("SECURITIES")).equals(sec)) {
            throw new TestException(selectNetworth
                + " should return same value " + sec
                + " for securities, but it does not for" + " cid: "
                + col.get("CID") + "\n" + ResultSetHelper.listToString(list));
          }
        }
        rs.close();
        Log.getLogWriter().info(
            "verified that networth table has the same value "
                + "for securities column of " + sec);
      }
      commit(conn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_useSelectForUpdateTx() {
    sqlNewTxTest.useSelectForUpdateTx();
  }

  protected void useSelectForUpdateTx() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
    useSelectForUpdateTx(gConn);
    closeGFEConnection(gConn);
  }

  protected void useSelectForUpdateTx(Connection conn) {
    if (concUpdateTxMaxCid == 0) {
      concUpdateTxMaxCid = (int) SQLTxBB.getBB().getSharedCounters().read(
          SQLTxBB.concUpdateTxMaxCid);
    }
    int tid = getMyTid() + 1;
    int maxCid = concUpdateTxMaxCid;
    int interval = 10;
    int numIter = maxCid / interval;
    boolean success = false;
    for (int i = 0; i <= numIter; i++) {
      int lowCid = i * interval;
      int highCid = (i == numIter) ? maxCid : (i + 1) * interval;
      success = updateSelectForUpdateTx(conn, tid, lowCid, highCid);
      while (!success) {
        MasterController.sleepForMs(tid * 113);
        success = updateSelectForUpdateTx(conn, tid, lowCid, highCid);
      }
      commit(conn);
    }

  }

  protected boolean updateSelectForUpdateTx(Connection conn, int tid,
      int lowCid, int highCid) {

    try {
      Statement s = isTicket41738Fixed ? conn.createStatement() : conn
          .createStatement(ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_UPDATABLE);
      s.setCursorName("updateCursor");
      String sql = "select * from trade.networth where cid > " + lowCid
          + " and cid <= " + highCid + " FOR UPDATE OF securities";
      Log.getLogWriter().info(sql);
      ResultSet updatableRs = s.executeQuery(sql);
      PreparedStatement psCurrentOf = null;
      PreparedStatement psPK = null;
      if (isTicket41738Fixed)
        psCurrentOf = conn
            .prepareStatement("update trade.networth set securities = securities + ? "
                + " where current of updateCursor");

      psPK = conn
          .prepareStatement("update trade.networth set securities = securities + ? "
              + " where cid = ? ");
      if (isTicket41738Fixed && random.nextBoolean()) {
        boolean success = updateSelectForUpdateTx(updatableRs, psCurrentOf, tid);
        updatableRs.close();
        return success;
      } else {
        if (random.nextBoolean()) {
          boolean success = updateSelectForUpdatePKTx(updatableRs, psPK, tid);
          updatableRs.close();
          return success;
        } else {
          boolean success = updateSelectForUpdateRsTx(updatableRs, tid);
          updatableRs.close();
          return success;
        }
      }

    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        SQLHelper.printSQLException(se);
        log().info("got expected conflict exception, needs to retry the op");
        return false; // expected conflict issue during query execution for
                      // acquiring the locks
      } else if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        log().info("got node failure exception, needs to retry the op");
        return false;
      } else
        SQLHelper.handleSQLException(se);
    }
    return true;
  }

  protected boolean updateSelectForUpdateTx(ResultSet rs, PreparedStatement ps,
      int tid) {
    if (isHATest)
      throw new TestException("need to handle tx node failure condition "
          + "in the test, as #43932 and #41738 are fixed");
    try {
      boolean success;
      while (rs.next()) {
        success = updateSFURowTx(rs, ps, tid);
        if (!success)
          return success;
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
      // should not get conflict for moving position forward
      // as select for update acquired locks
    }

    return true;
  }

  protected boolean updateSelectForUpdatePKTx(ResultSet rs,
      PreparedStatement ps, int tid) {
    try {
      boolean success;
      while (rs.next()) {
        success = updateRowSFU_PKTx(rs, ps, tid);
        if (!success)
          return success;
      }
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info(
            "got node failure exception, needs to retry the op");
        return false;
      }

      SQLHelper.handleSQLException(se);
      // should not get conflict for moving position forward
      // as select for update acquired locks
    }

    return true;
  }

  // select for update should be able to use where current of cursor,
  // however, due to #41738, we require user to use updatable rs to update row
  // as a work around or PK based if pk exists. This needs to be documented.
  protected boolean updateSelectForUpdateRsTx(ResultSet rs, int tid) {
    try {
      boolean success;
      while (rs.next()) {
        success = updateRowSFU_URSTx(rs, tid);
        if (!success)
          return success;

        if (random.nextInt(concUpdateTxMaxCid) == 0)
          success = deleteRowSFU_URSTx(rs); // delete row
      }
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info(
            "got node failure exception, needs to retry the op");
        return false;
      }

      SQLHelper.handleSQLException(se);
      // should not get conflict for moving position forward
      // as select for update acquired locks
    }

    return true;
  }

  // updatable resultset used after select for update
  protected boolean updateRowSFU_URSTx(ResultSet updatableRs, int tid) {
    try {
      int cid = updatableRs.getInt("CID");
      BigDecimal sec = updatableRs.getBigDecimal("SECURITIES").add(
          new BigDecimal(tid));
      updatableRs.updateBigDecimal("SECURITIES", sec);
      updatableRs.updateRow();
      Log.getLogWriter().info(
          "update trade.networth set securities to be " + sec + " for cid: "
              + cid);
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info(
            "got node failure exception, needs to retry the op");
        return false;
      }

      SQLHelper.handleSQLException(se);
    }
    return true;
  }

  protected boolean deleteRowSFU_URSTx(ResultSet updatableRs) {
    try {
      int cid = updatableRs.getInt("CID");
      updatableRs.deleteRow();
      Log.getLogWriter().info("delete from trade.networth for cid: " + cid);
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info(
            "got node failure exception, needs to retry the op");
        return false;
      }
      SQLHelper.handleSQLException(se); // should not see exception as the row
                                        // should be locked
    }
    return true;
  }

  protected boolean updateRowSFU_PKTx(ResultSet updatableRs,
      PreparedStatement ps, int tid) {
    try {
      int cid = updatableRs.getInt("CID");
      ps.setBigDecimal(1, new BigDecimal(tid));
      ps.setInt(2, cid);
      ps.executeUpdate();
      Log.getLogWriter().info(
          "update trade.networth set securities = securities + " + tid
              + " for cid: " + cid);
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        log().info("got node failure exception, needs to retry the op");
        return false;
      }
      SQLHelper.handleSQLException(se); // no conflict as select for update
                                        // should have acquired locks
    }
    return true;
  }

  protected boolean updateSFURowTx(ResultSet updatableRs, PreparedStatement ps,
      int tid) {
    try {
      int cid = updatableRs.getInt("CID");

      ps.setBigDecimal(1, new BigDecimal(tid));
      int num = ps.executeUpdate();
      if (num == 1)
        Log.getLogWriter().info(
            "update trade.networth set securities + " + tid + " for cid: "
                + cid);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        SQLHelper.printSQLException(se);
        return false; // expected updatable result set
      } else
        SQLHelper.handleSQLException(se);
    }
    return true;
  }

  public static void HydraTask_checkConstraints() {
    sqlNewTxTest.checkConstraints();
  }

  protected void checkConstraints() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);

    checkUniqConstraints(gConn);
    checkFKConstraints(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  // return true if no conflict exception during commit
  @SuppressWarnings("unchecked")
  protected boolean commitBatching(Connection conn, boolean firstCommit) {
    try {
      Log.getLogWriter().info("committing the ops for gfxd");
      conn.commit();
    } catch (SQLException se) {
      ArrayList<SQLException> derbyExceptions = (ArrayList<SQLException>) SQLDistTxTest.derbyExceptionsWithBatching
          .get();
      if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
        if (useNewTables)
          return verifyNewTablesBatchingConflictAtCommit(se, true);
        else
          return verifyBatchingConflictAtCommit(firstCommit, se, true);
      } else if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        return false; // to be used when HA is introduced in thin client testing
      } else if (derbyExceptions.contains(se.getSQLState())) {
        Log.getLogWriter().info("gfxd commit failed due to batching for " + se);
        return false; // exception may be thrown at commit, so compare them here
      } else
        SQLHelper.handleSQLException(se);
    }

    if (useNewTables)
      return verifyNewTablesBatchingConflictAtCommit(null, false);
    else
      return verifyBatchingConflictAtCommit(firstCommit, null, false);

  }

  // return ture if no exception occurs due to HA
  protected boolean commitWithHA(Connection conn) {
    try {
      Log.getLogWriter().info("committing the ops for gfxd");
      conn.commit();
      Log.getLogWriter().info("tx is committed for gfxd");
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (SQLHelper.gotTXNodeFailureException(se)) {
        log().info("commit failed in gfxd due to node failure");
        return false;
      } else
        SQLHelper.handleSQLException(se);
    }

    return true; // no exception, commit successful
  }

  @SuppressWarnings("unchecked")
  /*
   * returns true: when no conflict expected and commit does not gets conflict
   * exception retruns false: when conflict is expected and commit correctly
   * failed with conflict exception
   */
  protected boolean verifyBatchingConflictAtCommit(boolean firstCommit,
      SQLException se, boolean getsConflict) {
    // find the keys has been hold by others
    hydra.blackboard.SharedMap modifiedKeysByAllTx = SQLTxBatchingBB.getBB()
        .getSharedMap();

    HashMap<String, Integer> modifiedKeysByThisTx = (HashMap<String, Integer>) SQLDistTxTest.curTxModifiedKeys
        .get();

    Integer myTxId = (Integer) curTxId.get();

    // will be used for mix RC and RR case
    /*
     * SharedMap writeLockedKeysByRRTx = null; Map writeLockedKeysByOtherRR =
     * null;
     * 
     * SharedMap readLockedKeysByRRTx = null; Map readLockedKeysByOtherRR =
     * null;
     */

    boolean expectFKConflict = expectBatchingFKConflict(modifiedKeysByAllTx
        .getMap());

    if (!getsConflict) {
      if (firstCommit && expectFKConflict) {
        throw new TestException(
            "commit with batching "
                + "should get conflict exception but not, need to check log for foreing key constraints");
      }
    }

    // remove those keys are already held by this tx
    for (String key : modifiedKeysByThisTx.keySet()) {
      // log().info("this tx hold the key: " + key);
      ArrayList<Integer> txIdsForTheKey = (ArrayList<Integer>) modifiedKeysByAllTx
          .get(key);
      boolean contains = txIdsForTheKey.remove(myTxId);
      if (!contains)
        throw new TestException(
            "test issue, a key is not added in the batchingBB map");

      if (txIdsForTheKey.size() > 0) {
        if (!getsConflict)
          throw new TestException("commit with batching "
              + "should get conflict exception but not, for key: " + key
              + ", there are following other txId hold the" + " lock locally: "
              + txIdsForTheKey.toString());

        else {
          Log.getLogWriter().info("get expected conflict exception during commit");
          return false;
        }
      }
    }

    // no other txId has the same key could cause conflict
    // txIdsForTheKey.size()==0 for all holding keys
    if (getsConflict) {
      if (expectFKConflict) {
        Log.getLogWriter().info("get expected conflict exception during commit");
        return false;
      } else {
        throw new TestException(
            "does not expect a conflict during commit, but gets "
                + TestHelper.getStackTrace(se));
        // TODO this needs to be revisited depends on how #48195 is being
        // handled by product
        // if the first commit should fail, need to find out a way to
        // check a later op causes the foreign key conflict
        // a possible way is to have every op could cause foreign key conflict
        // writes
        // the conflicting txId to BB, so the conflict could be checked here.
        // (when batching is on)
        // and BB needs to be cleaned after conflict exception is thrown,

      }
    } else {
      if (!expectFKConflict)
        return true;
      else {
        throw new TestException(
            "should get a conflict during commit, but does not. Need to check logs for more info");
      }
    }
  }
  
  /**
   * @return              true if does not get unexpected conflict 
   *                      false if gets expected conflict   
   */
  protected boolean verifyNewTablesBatchingConflictAtCommit(SQLException se, boolean getsConflict) {   
    boolean expectConflictAtCommit = false;
    StringBuilder conflictMsg = new StringBuilder();
    expectConflictAtCommit  = expectConflictAtCommit(conflictMsg);
    //needs to check if conflict should be thrown by the product
    if (getsConflict) {
      if (expectConflictAtCommit) {
        Log.getLogWriter().info("Got expected conflict exception during commit when batching is on");
        return false; //got expected conflict exception
      }
      else throw new TestException(
      "Got unexpected conflict during commit for batching txn." + TestHelper.getStackTrace(se));
    } 
    else {
      if (expectConflictAtCommit) {
        throw new TestException ("Does not get expected conflict exception, " +
        		"but " + conflictMsg.toString());
      } else {        
        return true;
      }
    }
  }
  
  @SuppressWarnings("unchecked")
  protected boolean expectConflictAtCommit(StringBuilder conflictMsg) {
    //find keys locked by current txn
    HashMap<String, Integer> modifiedKeysByThisTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();
    HashMap<String, Integer> holdNonDeleteKeysByThisTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxNonDeleteHoldKeys.get();
    HashMap<String, Integer> holdDeleteKeysByThisTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxDeleteHoldKeys.get();
    HashMap<String, ForeignKeyLocked> holdForeignKeysByThisTx = (HashMap<String, ForeignKeyLocked>)
        SQLDistTxTest.curTxHoldParentKeys.get();
    HashMap<String, ForeignKeyLocked> holdNewForeignKeysByThisTx = (HashMap<String, ForeignKeyLocked>)
        SQLDistTxTest.curTxNewHoldParentKeys.get();
    
    //find the keys has been hold by all txns
    hydra.blackboard.SharedMap modifiedKeysByAllTx = SQLTxBatchingBB.getBB().getSharedMap();
    hydra.blackboard.SharedMap holdBlockingKeysByAllTx = SQLTxBatchingNonDeleteHoldKeysBB.getBB().getSharedMap(); 
    //due to #42672, some of the updates on parent could cause conflict with child table dml ops
    hydra.blackboard.SharedMap holdDeleteBlockingKeysByAllTx = SQLTxBatchingDeleteHoldKeysBB.getBB().getSharedMap(); 
    //due to #50560, delete will gets conflict on current held rows
    hydra.blackboard.SharedMap holdForeignKeysByAllTx = SQLTxHoldForeignKeysBB.getBB().getSharedMap(); 
    //for RC txn only, for RR txn use RRReadBB instead
    hydra.blackboard.SharedMap holdNewForeignKeysByAllTx = SQLTxHoldNewForeignKeysBB.getBB().getSharedMap(); 
    //seperate newly acquried foreign vs existing fk of the row -- see #50560
    
    //check modified keys conflict
    if (modifiedKeysByThisTx != null) {
      for (String key: modifiedKeysByThisTx.keySet()) {
        ArrayList<Integer> list = (ArrayList<Integer>) modifiedKeysByAllTx.get(key);
        if (list.size() > 1) {
          conflictMsg.append("This txId: " + curTxId.get() + " should get conflict " +
          		"exception as the following txIds: " + list + " hold the local " +
          		"lock for the key: " + key  ); 
          return true;
        } 
        //keys in this txn (including in this op) has been added into all txn batching bb
      }
    }
    
    //check non delete holding keys conflict
    if (holdNonDeleteKeysByThisTx!= null) {
      Set<String> keys = holdNonDeleteKeysByThisTx.keySet();
      for (String key: keys) {
        int txId = holdNonDeleteKeysByThisTx.get(key);
        ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
        if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId, conflictMsg)) return true;
      }
    }
    
    //check delete holding keys conflict
    if (holdDeleteKeysByThisTx != null) {
      Set<String> keys = holdDeleteKeysByThisTx.keySet();
      for (String key: keys) {
        int txId = holdDeleteKeysByThisTx.get(key);
        ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdForeignKeysByAllTx.get(key);
        if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId, conflictMsg)) return true;
        
        fkLocked = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
        if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId, conflictMsg)) return true;
      }
    }
    
    //check foreign key hold by the cur txn
    if (holdForeignKeysByThisTx!= null) {
     if (checkForeignKeysWithBatching(holdForeignKeysByThisTx, 
         holdDeleteBlockingKeysByAllTx, conflictMsg)) return true;
    }
    
    //check new foreign key hold by the cur txn
    if (holdNewForeignKeysByThisTx!= null) {
      if (checkForeignKeysWithBatching(holdNewForeignKeysByThisTx, 
          holdBlockingKeysByAllTx, conflictMsg)) return true;
      if (checkForeignKeysWithBatching(holdNewForeignKeysByThisTx, 
          holdDeleteBlockingKeysByAllTx, conflictMsg)) return true;
    }
    
    return false;
  }
  
  @SuppressWarnings("unchecked")
  protected boolean checkForeignKeysWithBatching(
      HashMap<String, ForeignKeyLocked>holdForeignKeysByThisTx, 
      SharedMap holdBlockingKeysByAllTx, StringBuilder str) {
    if (holdForeignKeysByThisTx == null) return false;
    Set<String> keys = holdForeignKeysByThisTx.keySet();
    for (String key: keys) {
      ForeignKeyLocked fks = holdForeignKeysByThisTx.get(key);
      Set<Integer> txIds = fks.getKeysHeldByTxIds().keySet();
      if (txIds.size() > 1) {
        throw new TestException("Test issue, current txn should only hold one txId. But it " +
            "has follwing txIds " + txIds.toArray());
      }
      int myTxId = txIds.iterator().next(); //should have only one key, otherwise need to check test code logic
      int holdTimes = fks.getKeysHeldByTxIds().get(myTxId);
      //holdTimes ==0 should not occur in current code base,
      //it may occur only if #43170 is fixed and when current op is rolled back
      if (holdTimes > 0) {
        ArrayList<Integer> blockingTxIds = (ArrayList<Integer>)holdBlockingKeysByAllTx.get(key);
        if (blockingTxIds !=null) {
          for (int txId: blockingTxIds) {
            if (txId != myTxId) {
              String msg = "Another txId: " + txId + " holds " +
              "the foreign key: " + key + " needs for op in this txn: " + myTxId;
              str.append(msg);
              Log.getLogWriter().info(msg);
              return true;
            }
          }
          
        }
      }
    }
    Log.getLogWriter().info("This op does not hold foreign keys causing conflict for existing " +
        "parent table ops");
    return false;    
  }

  // test to see if there is another tx possibly hold a key which may cause
  // foreign key conflict during batching
  @SuppressWarnings("unchecked")
  protected boolean expectBatchingFKConflict(Map modifiedKeysByAllTx) {
    // check all child rows inserted by this row will cause foreign key conflict
    Integer myTxId = (Integer) SQLDistTxTest.curTxId.get();
    HashSet<String> holdFKsByThisTx = (HashSet<String>) foreignKeyHeldWithBatching
        .get();

    for (String key : holdFKsByThisTx) {
      ArrayList<Integer> txIdsForTheKey = (ArrayList<Integer>) modifiedKeysByAllTx
          .get(key);
      if (txIdsForTheKey != null) {
        if (txIdsForTheKey.size() > 1
            || (txIdsForTheKey.size() == 1 && !txIdsForTheKey.contains(myTxId))) {
          // work around #42672, #50070, in some cases update on parent does not
          // hold the key
          for (int txId : txIdsForTheKey) {
            HashSet<String> holdParentKeys = (HashSet<String>) SQLBB.getBB()
                .getSharedMap().get(AbstractDMLStmt.parentKeyHeldTxid + txId);

            if (holdParentKeys != null) {
              for (String holdParentKey : holdParentKeys) {
                if (holdParentKey.equals(key)) {
                  Log.getLogWriter().info(
                      "should get conflict for foreign key " + key
                          + " with following txIds: " + txIdsForTheKey);
                  return true;
                }
              }
            }
          }
        }
      }
    }

    // check whether all parent row lock hold by this tx will cause foreign key
    // conflict hold by other txIds in batching
    hydra.blackboard.SharedMap holdingFKTxIds = SQLTxBatchingFKBB.getBB()
        .getSharedMap();
    HashSet<String> holdParentKeyByThisTx = (HashSet<String>) parentKeyHeldWithBatching
        .get();
    for (String key : holdParentKeyByThisTx) {
      HashSet<Integer> txIds = (HashSet<Integer>) holdingFKTxIds.get(key);
      if (txIds != null) {
        if (txIds.size() > 1 || (txIds.size() == 1 && !txIds.contains(myTxId))) {
          Log.getLogWriter().info("should get conflict for foreign key " + key + 
              " with txIds: " + txIds);
          return true;
        }
      }
    }
    return false;
  }

  protected void closeConnectionIgnoreFailure(Connection conn) {
    try {
      conn.rollback();
      conn.close();
    } catch (SQLException sqle) {
      Log.getLogWriter().info("Ignoring exception in connection close " 
          + sqle.getSQLState());
    }
  }

  public static void HydraTask_clearTables() {
    sqlNewTxTest.clearTablesInOrder();
  }

  protected void clearTablesInOrder() {
    if (!hasDerbyServer)
      return;
    Connection dConn = getDiscConnection();
    Connection gConn = getGFEConnection();
    clearTablesInOrder(dConn, gConn);
    commit(dConn);
    commit(gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_alterCustomersTableGenerateAlways() {
    sqlNewTxTest.alterCustomersTableGenerateAlways();
  }

  protected void alterCustomersTableGenerateAlways() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    try {
      if (addGenIdColInCustomersv1) {
        alterCustomersAddGenId(dConn, gConn);
        updateGenIdCol(dConn, gConn);
      }
      alterCustomersTableGenerateAlways(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    closeGFEConnection(gConn);
  }

  protected void alterCustomersAddGenId(Connection dConn, Connection gConn)
      throws SQLException {
    String sqlDerby = "alter table trade.customersv1 add column genId bigint with default 1 not null";
    String sql = "alter table trade.customersv1 add column genId bigint GENERATED BY DEFAULT AS IDENTITY";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + sqlDerby);
      executeStatement(dConn, sqlDerby);
      Log.getLogWriter().info("executed in derby: " + sqlDerby);
      commit(dConn);
    }
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }

  protected void updateGenIdCol(Connection dConn, Connection gConn)
      throws SQLException {
    String sql = "update customersv1 set genId = cid";
    String setSchema = random.nextBoolean() ? "set schema trade"
        : "set current schema = trade";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + setSchema);
      executeStatement(dConn, setSchema);
      Log.getLogWriter().info("executed in derby: " + setSchema);
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      commit(dConn);
    }
    Log.getLogWriter().info("executing in gfxd: " + setSchema);
    executeStatement(gConn, setSchema);
    Log.getLogWriter().info("executed in gfxd: " + setSchema);
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }

  protected void alterCustomersTableGenerateAlways(Connection conn) {
    String alterTable = "";
    if (addGenIdColInCustomersv1)
      alterTable = "alter table trade.customersv1 ALTER COLUMN genId SET GENERATED ALWAYS AS IDENTITY";
    else
      alterTable = "alter table trade.customersv1 ALTER COLUMN cid SET GENERATED ALWAYS AS IDENTITY";
    try {
      Log.getLogWriter().info(
          (SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - ")
              + "executing " + alterTable);
      conn.createStatement().execute(alterTable);
      Log.getLogWriter().info(
          (SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - ")
              + "executed " + alterTable);
      commit(conn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  public static void HydraTask_createNewTables() {
    sqlNewTxTest.createNewTables();
  }

  protected void createNewTables() {
    sqlNewTxTest.addCustomerV1Table();
    sqlNewTxTest.addNetworthV1Table();
    // set bb for first iteration
    SQLTxBB.getBB().getSharedCounters().increment(SQLTxBB.iterations);
  }

  protected void addCustomerV1Table() {
    boolean reproduce50116 = TestConfig.tab().booleanAt(
        SQLPrms.toReproduce50116, true);
    try {
      if (reproduce50116) {

        createCustomersV1Table();

        selectIntoTable("customers", "customersv1");

        alterTableAddIterationCol("customersv1");

        // dropTable("customers"); //Only execute this once child tables are

        // createCustomersView();
      } else {
        // create portfoliov1 of portfolio table
        createCustomersV1Table();
        // select into table
        selectIntoTable("customers", "customersv1");
        // alter table add blob column
        alterTableAddIterationCol("customersv1");
      }

      SQLBB.getBB().getSharedMap().put(CUSTOMERSV1TXREADY, true);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    int numOfPRs = (Integer) SQLBB.getBB().getSharedMap().get(CUSTNUMOFPRS);
    // add numOfPRs for HA
    SQLBB.getBB().getSharedCounters().add(SQLBB.numOfPRs, numOfPRs);
    Log.getLogWriter().info(
        "numOfPRs now is "
            + SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));

  }

  protected void addNetworthV1Table() {
    boolean reproduce50547 = TestConfig.tab().booleanAt(
        SQLPrms.toReproduce50547, false);
    boolean reproduce50550 = TestConfig.tab().booleanAt(
        SQLPrms.toReproduce50550, false);
    try {
      // create portfoliov1 of portfolio table
      createNetworthV1Table();
      // select into table
      selectIntoTable("networth", "networthv1");
      // alter table add round column
      alterTableAddIterationCol("networthv1");
      // alter table add foreing key constraint column (as primary could not be
      // updated)
      // used to show how to handle update on a columns involving a foreing key

      if (reproduce50547) {
        alterTableAddNetworthFKColConstraint();
        // update to use different fk instead of default 1
        updateNetworthFKCol();
      } else {
        alterTableAddNetworthFKCol();
        // update to use different fk instead of default 1
        if (reproduce50550) {
          alterTableAddNetworthFKTableConstraint();
          updateNetworthFKCol();
        } else {
          updateNetworthFKCol();
          alterTableAddNetworthFKTableConstraint();
        }
      }
      // remove original fk constraint
      alterNetworthv1DropFKConstraint();
            
      SQLBB.getBB().getSharedMap().put(CUSTOMERSV1TXREADY, true);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    int numOfPRs = (Integer) SQLBB.getBB().getSharedMap().get(NETWORTHNUMOFPRS);
    // add numOfPRs for HA
    //TODO once all new table are added, we need to drop the original tables and 
    //comment out the following, so that num of PRs are correct in HA test
    SQLBB.getBB().getSharedCounters().add(SQLBB.numOfPRs, numOfPRs);
    Log.getLogWriter().info("numOfPRs now is "
            + SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
  }

  protected void createCustomersV1Table() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    createCustomersV1Table(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void createCustomersV1Table(Connection dConn, Connection gConn)
      throws SQLException {
    if (!tableColsSet)
      setTableCols(gConn); // set table col if not set yet
    boolean reproduce50118 = TestConfig.tab().booleanAt(
        SQLPrms.toReproduce50118, false);

    String str = null;
    for (String ddl : cachedGfeDDLs) {
      if (ddl.toLowerCase().contains("create table trade.customers "))
        str = ddl;
    }

    if (str == null)
      throw new TestException("gfeDDL does not contain customers table");

    if (!reproduce50118) {
      str = str.replace("trade.customers", "trade.customersv1");
      if (!addGenIdColInCustomersv1)
        str = str.replace("cid int", "cid bigint");
    }

    String createCustomersv1 = "create table trade.customersv1 (cid "
        + (addGenIdColInCustomersv1 ? "int" : "bigint")
        + " not null, "
        + "cust_name varchar(100), since date, addr varchar(100), tid int, primary key (cid))";

    String createCustomersv1UseSelect = reproduce50118 ? "create table trade.customersv1 as select * from trade.customers with no data"
        : str;
    String createDerbyIndexTid = "create index indexcustomerv1tid"
        + " on trade.customersv1 (tid)";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + createCustomersv1);
      executeStatement(dConn, createCustomersv1);
      Log.getLogWriter().info("executed in derby: " + createCustomersv1);
      Log.getLogWriter().info("executing in derby: " + createDerbyIndexTid);
      executeStatement(dConn, createDerbyIndexTid);
      Log.getLogWriter().info("executed in derby: " + createDerbyIndexTid);
      commit(dConn);
    }
    Log.getLogWriter().info("executing in gfxd: " + createCustomersv1UseSelect);
    executeStatement(gConn, createCustomersv1UseSelect);
    Log.getLogWriter().info("executed in gfxd: " + createCustomersv1UseSelect);
    if (reproduce50118) {
      String addPrimaryKey = "alter table trade.customersv1 add constraint customer_pk_v1 primary key (cid)";
      Log.getLogWriter().info("executing in gfxd: " + addPrimaryKey);
      executeStatement(gConn, addPrimaryKey);
      Log.getLogWriter().info("executed in gfxd: " + addPrimaryKey);
    }
    // set table cols for index creation
    setTableCols(gConn, "trade.customersv1");
    commit(gConn);
  }

  protected void createNetworthV1Table() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    createNetworthV1Table(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void createNetworthV1Table(Connection dConn, Connection gConn)
      throws SQLException {
    if (!tableColsSet)
      setTableCols(gConn); // set table col if not set yet

    String str = null;
    String dropAvailloanCheckConstraint = "alter table trade.networthv1 drop CHECK availloan_ck_v1";
    
    boolean dropAvailloanCheck = random.nextInt(5) != 1;
    // keep other two check constraints
    //avoid too many check constraint violation causing txn rolled back in some test runs
    
    for (String ddl : cachedGfeDDLs) {
      if (ddl.toLowerCase().contains("create table trade.networth "))
        str = ddl;
    }

    if (str == null)
      throw new TestException("gfeDDL does not contain customers table");

    str = str.replace("trade.networth ", "trade.networthv1 ");
    str = str.replace("trade.customers", "trade.customersv1");
    str = str.replace("cid int", "cid bigint");
    str = str.replace("_pk ", "_pk_v1 ");
    str = str.replace("_fk ", "_fk_v1 ");
    str = str.replace("_ch ", "_ch_v1 ");
    str = str.replace("_ck ", "_ck_v1 ");

    // create table trade.networth (cid int not null, cash decimal (30, 1),
    // securities decimal (30, 1), loanlimit int, availloan decimal (30, 1), tid
    // int, constraint netw_pk primary key (cid), constraint cust_newt_fk
    // foreign key (cid) references trade.customers (cid) on delete restrict,
    // constraint cash_ch check (cash>=0), constraint sec_ch check (securities
    // >=0), constraint availloan_ck check (loanlimit>=availloan and availloan
    // >=0))
    String createNetworthv1 = "create table trade.networthv1 (cid bigint not null, "
        + "cash decimal (30, 1), securities decimal (30, 1), loanlimit int, "
        + "availloan decimal (30, 1),  tid int, constraint netw_pk_v1 primary key (cid), "
        + "constraint cust_newt_fk_v1 foreign key (cid) references trade.customersv1 (cid) on delete restrict,"
        + " constraint cash_ch_v1 check (cash>=0), constraint sec_ch_v1 check (securities >=0), "
        + "constraint availloan_ck_v1 check (loanlimit>=availloan and availloan >=0))";

    String createDerbyIndexTid = "create index indexnetworthv1tid"
        + " on trade.networthv1 (tid)";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + createNetworthv1);
      executeStatement(dConn, createNetworthv1);
      Log.getLogWriter().info("executed in derby: " + createNetworthv1);
      Log.getLogWriter().info("executing in derby: " + createDerbyIndexTid);
      executeStatement(dConn, createDerbyIndexTid);
      Log.getLogWriter().info("executed in derby: " + createDerbyIndexTid);
      commit(dConn);
      if (dropAvailloanCheck) {
        Log.getLogWriter().info(
            "executing in derby: " + dropAvailloanCheckConstraint);
        executeStatement(dConn, dropAvailloanCheckConstraint);
        Log.getLogWriter().info(
            "executed in derby: " + dropAvailloanCheckConstraint);
        commit(dConn);
      }
    }
    Log.getLogWriter().info("executing in gfxd: " + str);
    executeStatement(gConn, str);
    Log.getLogWriter().info("executed in gfxd: " + str);

    // set table cols for index creation
    setTableCols(gConn, "trade.networthv1");
    commit(gConn);
    if (dropAvailloanCheck) {
      Log.getLogWriter().info(
          "executing in gfxd: " + dropAvailloanCheckConstraint);
      executeStatement(gConn, dropAvailloanCheckConstraint);
      Log.getLogWriter()
          .info("executed in gfxd: " + dropAvailloanCheckConstraint);
      commit(gConn);
    }
  }

  protected void selectIntoTable(String tableName, String newTableName)
      throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    selectIntoTable(dConn, gConn, tableName, newTableName);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void selectIntoTable(Connection dConn, Connection gConn,
      String tableName, String newTableName) throws SQLException {
    String sql = "insert into " + newTableName + " (select * from " + tableName
        + ")";
    String setSchema = random.nextBoolean() ? "set schema trade"
        : "set current schema = trade";
    if (dConn != null) {
      Log.getLogWriter().info("executing in derby: " + setSchema);
      executeStatement(dConn, setSchema);
      Log.getLogWriter().info("executed in derby: " + setSchema);
      Log.getLogWriter().info("executing in derby: " + sql);
      executeStatement(dConn, sql);
      Log.getLogWriter().info("executed in derby: " + sql);
      commit(dConn);
    }
    Log.getLogWriter().info("executing in gfxd: " + setSchema);
    executeStatement(gConn, setSchema);
    Log.getLogWriter().info("executed in gfxd: " + setSchema);
    Log.getLogWriter().info("executing in gfxd: " + sql);
    executeStatement(gConn, sql);
    Log.getLogWriter().info("executed in gfxd: " + sql);
    commit(gConn);
  }

  protected void alterTableAddIterationCol(String tableName)
      throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    alterTableAddIterationCol(dConn, gConn, tableName);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterTableAddIterationCol(Connection dConn, Connection gConn,
      String tableName) throws SQLException {
    String sql = "alter table trade." + tableName + " add column round int "
        + (random.nextBoolean() ? "" : "with ") + "DEFAULT " + 0;
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void alterTableAddNetworthFKColConstraint() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    alterTableAddNetworthFKColConstraint(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterTableAddNetworthFKColConstraint(Connection dConn,
      Connection gConn) throws SQLException {
    String sql = "alter table trade.networthv1 add column c_cid bigint "
        + "constraint cust_newt_fk_v1_2 references trade.customersv1 (cid) on delete restrict "
        + (random.nextBoolean() ? "" : "with ") + "DEFAULT " + 1;
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void executeSQLCommand(Connection dConn, Connection gConn,
      String sql) throws SQLException {
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

  protected void alterTableAddNetworthFKCol() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    alterTableAddNetworthFKCol(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterTableAddNetworthFKCol(Connection dConn, Connection gConn)
      throws SQLException {
    String sql = "alter table trade.networthv1 add column c_cid bigint "
        + (random.nextBoolean() ? "" : "with ") + "DEFAULT " + 1;
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void alterTableAddNetworthFKTableConstraint() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    alterTableAddNetworthFKTableConstraint(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterTableAddNetworthFKTableConstraint(Connection dConn,
      Connection gConn) throws SQLException {
    String sql = "alter table trade.networthv1 add "
        + "constraint cust_newt_fk_v1_2 FOREIGN KEY (c_cid) "
        + "references trade.customersv1 (cid) on delete restrict ";
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void alterNetworthv1DropFKConstraint() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }
    Connection gConn = getGFEConnection();
    alterNetworthv1DropFKConstraint(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void alterNetworthv1DropFKConstraint(Connection dConn,
      Connection gConn) throws SQLException {
    String sql = "alter table trade.networthv1 drop "
        + (random.nextBoolean() ? "FOREIGN KEY" : "constraint")
        + " cust_newt_fk_v1";
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void updateNetworthFKCol() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    updateNetworthFKCol(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void updateNetworthFKCol(Connection dConn, Connection gConn)
      throws SQLException {
    String sql = "update trade.networthv1 set c_cid = cid";
    executeSQLCommand(dConn, gConn, sql);
  }

  protected void dropTable(String tableName) throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    dropTable(dConn, gConn, tableName);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void dropTable(Connection dConn, Connection gConn, String tableName)
      throws SQLException {
    String derbySql = "drop table trade." + tableName;
    String sql = "drop table " + (random.nextBoolean() ? "if exists " : "")
        + "trade." + tableName;
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

  protected void createCustomersView() throws SQLException {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } // when not test uniqueKeys and not in serial execution, only connection
      // to gfe is provided.
    Connection gConn = getGFEConnection();
    createCustomersView(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  protected void createCustomersView(Connection dConn, Connection gConn)
      throws SQLException {
    String sql = "create view trade.customers as select cid, cust_name, since, addr, tid from trade.customersv1";
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

  protected void addPortfolioV1Table() {
    boolean reproduce50116 = TestConfig.tab().booleanAt(
        SQLPrms.toReproduce50116, true);
    try {
      if (reproduce50116) {
        // create portfoliov1 of portfolio table
        createPortfolioV1Table();
        // select into table
        selectIntoPortfolioV1Table();
        // alter table add blob column
        alterTableAddDataCol();
        // alter table change the child table fk constraint
        alterTableAlterConstraint();
        // drop portfolio table
        dropPortfolioTable();
        // create portfolio view
        createPortfolioView();
      } else {
        // create portfoliov1 of portfolio table
        createPortfolioV1Table();
        // select into table
        selectIntoPortfolioV1Table();
        // alter table add blob column
        alterTableAddDataCol();
        // alter table change the child table fk constraint
        alterTableAlterConstraint();
        // drop portfolio table
        // dropPortfolioTable();
        // create portfolio view
        // createPortfolioView();
      }

      SQLBB.getBB().getSharedMap().put(PORTFOLIOV1READY, true);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
}
