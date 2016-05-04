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

import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeapScanController;
import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.MasterController;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.SharedMap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.transaction.TransactionRolledbackException;

import sql.GFEDBManager;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlDistTxRRStatements.TradeNetworthDMLDistTxRRStmt;
import sql.dmlDistTxStatements.DMLDistTxStmtIF;
import sql.dmlDistTxStatements.TradeSellOrdersDMLDistTxStmt;
import sql.dmlStatements.AbstractDMLStmt;
import sql.sqlutil.ResultSetHelper;
import util.PRObserver;
import util.TestException;
import util.TestHelper;
import com.gemstone.gemfire.cache.query.Struct;


public class SQLDistRRTxTest extends SQLDistTxTest {
  protected static SQLDistRRTxTest rrTest;
  public static HydraThreadLocal curTxRRReadKeys = new HydraThreadLocal(); //read lock held by this RR tx
  public static boolean reproduce49935 = true; //false;


  public static void HydraTask_initConnections() throws SQLException {
    rrTest.initThreadLocalConnection();
  }

  public static void HydraTask_initThreadLocals() {
    rrTest.initThreadLocals();
  }

  @Override
  protected void initThreadLocals() {
    super.initThreadLocals();

  }

  public static synchronized void HydraTask_initialize() {
    if (rrTest == null) {
      rrTest = new SQLDistRRTxTest();
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());

      rrTest.initialize();
    }
    rrTest.initThreadLocals();
  }

  public static void HydraTask_createGfxdLocatorTask() {
    SQLTest.HydraTask_createGfxdLocatorTask();
  }

  public static void HydraTask_startGfxdLocatorTask() {
    SQLTest.HydraTask_startGfxdLocatorTask();
  }

  public synchronized static void HydraTask_startFabricServer() {
    rrTest.startFabricServer();
  }

  public synchronized static void HydraTask_stopFabricServer() {
    rrTest.stopFabricServer();
  }

  public synchronized static void HydraTask_startFabricServerSG() {
    rrTest.startFabricServerSG();
  }

  public synchronized static void  HydraTask_startFabricServerSGDBSynchronizer() {
    rrTest.startFabricServerSGDBSynchronizer();
  }

  public static synchronized void HydraTask_createDiscDB() {
    rrTest.createDiscDB();
  }

  public static synchronized void HydraTask_createDiscSchemas() {
    rrTest.createDiscSchemas();
  }

  public static synchronized void HydraTask_createDiscTables(){
    rrTest.createDiscTables();
  }

  public static void HydraTask_createGFESchemas() {
    rrTest.createGFESchemas();
  }

  public static void HydraTask_createGFETables(){
    rrTest.createGFETables();
  }

  public static void HydraTask_populateTxTables(){
    rrTest.populateTxTables();
  }

  public static void HydraTask_populateTxTablesDBSynchronizer(){
    rrTest.populateTxTablesDBSynchronizer();
  }

  public static void HydraTask_setTableCols() {
    rrTest.setTableCols();
  }

  //only ddl thread performing createIndex now,
  //so dml operation needs to be considered how many workers in the tasks for serialization
  public static void HydraTask_createIndex() {
    rrTest.createIndex();
  }

  public static void HydraTask_verifyResultSets() {
    rrTest.verifyResultSets();
  }

  protected void verifyResultSets() {
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verification of query results "
          + "due to manageDerbyServer as false, myTid=" + getMyTid());
      cleanConnection(getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ));
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);

    verifyResultSets(dConn, gConn);
  }

  public static void HydraTask_initForServerGroup() {
    rrTest.initForServerGroup();
  }

  public static void HydraTask_createDBSynchronizer() {
    rrTest.createDBSynchronizer();
  }

  public static void HydraTask_createDiskStores() {
    rrTest.createDiskStores();
  }

  @Override
  protected void createIndex() {
    //Connection gConn = getGFEConnection();
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    createRRTxIndex(gConn);
    closeGFEConnection(gConn);
  }

  protected void createRRTxIndex(Connection gConn) {
    try {
      createIndex(gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("40XL1")) {
        //work around #44474
        Log.getLogWriter().info("get expected lock not held exception in ddl op");
      } else throw te;
    }
    commitRRGfxdOnly(gConn);
  }

  @Override
  protected void createDiskStores() {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    createDiskStores(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  @Override
  protected void createDBSynchronizer() {
    Connection conn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    createNewDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  @Override
  protected void populateTxTables() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    } //only verification case need to populate derby tables
    //using REPEATABLE_READ connection
    //Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);

    //work around issue mentioned in #48712, commit may return earlier

    Properties info = new Properties();
    info.setProperty(syncCommits, "true");
    Connection gConn = getGFXDRRTxConnection(info);

    populateTables(dConn, gConn);
    if (dConn !=null)    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  @Override
  protected void populateTxTablesDBSynchronizer() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    populateTables(null, gConn); //pass dConn as null for DBSynchronizer
    closeGFEConnection(gConn);
  }

  public static void HydraTask_putLastKeyDBSynchronizer() {
    rrTest.putLastKeyDBSynchronizer();
  }

  @Override
  protected void putLastKeyDBSynchronizer() {
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    putLastKeyDBSynchronizer(dConn, gConn);
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_verifyResultSetsDBSynchronizer() {
    rrTest.verifyResultSetsDBSynchronizer();
  }

  public static void HydraTask_doTxDMLOpDBSynchronizer() {
    rrTest.doTxDMLOpDBSynchronizer();
  }

  @Override
  protected void doTxDMLOpDBSynchronizer() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    doDMLOp(null, gConn); //do not operation on derby directly
  }

  public static void HydraTask_doDMLOp() {
    rrTest.doDMLOp();
  }

  @Override
  protected void doDMLOp() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }

    Connection gConn = null;
    if (mixRR_RC && random.nextBoolean()) {
      gConn = getGFXDTxConnection(GFEDBManager.Isolation.READ_COMMITTED);
      doDMLOp(dConn, gConn, false);
    } else {
      gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
      doDMLOp(dConn, gConn, true);
    }

    if (dConn!=null) {
      closeDiscConnection(dConn);
      //Log.getLogWriter().info("closed the disc connection");
    }
    closeGFEConnection(gConn);
    Log.getLogWriter().info("done dmlOp");
  }

  protected void doDMLOp(Connection dConn, Connection gConn, boolean isRR) {
    if (doOpByOne && dConn != null) {
      if (!isRR) doDMLOpByOne(dConn, gConn);
      else doDMLOpRRByOne(dConn, gConn);
    }
    else doDMLGfxdOnlyOps(gConn);
  }

  protected void doDMLGfxdOnlyOps(Connection gConn) {
    int txId = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(SQLTxBB.txId);
    curTxId.set(txId);
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid() + " and txId is " + txId);
    boolean withDerby = false;
    int total = 20;
    int numOfIter = random.nextInt(total) + 1;
    for (int i=0; i<numOfIter; i++) {
      gConn = doDMLGfxdOnlyOp(gConn, 1, withDerby);
    }
    commitOrRollbackGfxdOnly(gConn);
  }

  @SuppressWarnings("static-access")
  protected void doOneGfxdOnlyDMLOp(Connection gConn, boolean withDerby) {
    boolean succeed = true;
    int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
    DMLDistTxStmtIF dmlStmt= dmlDistTxFactory.createDMLDistTxRRStmt(table); //dmlStmt of a table

    if (table != dmlTxFactory.TRADE_CUSTOMERS && table != dmlTxFactory.TRADE_NETWORTH
      && table != dmlTxFactory.TRADE_SECURITIES && table != dmlTxFactory.TRADE_PORTFOLIO
      && table != dmlTxFactory.TRADE_SELLORDERS) return;
    //perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
      succeed = dmlStmt.insertGfxd(gConn, withDerby);
    }
    else if (operation.equals("update")) {
      succeed = dmlStmt.updateGfxd(gConn, withDerby);
    }
    else if (operation.equals("delete"))
      succeed = dmlStmt.deleteGfxd(gConn, withDerby);
    else if (operation.equals("query"))
      succeed = dmlStmt.queryGfxd(gConn, withDerby);
    else
      throw new TestException("Unknown dml operation: " + operation);

    //insert into networth table() -- should use trigger to insert the row
    if (table == dmlTxFactory.TRADE_CUSTOMERS && operation.equals("insert") && succeed) {
      Log.getLogWriter().info("inserting into networth table");
      TradeNetworthDMLDistTxRRStmt networth = new TradeNetworthDMLDistTxRRStmt();
      succeed = networth.insertGfxd(gConn, withDerby, (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary)); //random
      //using new cid to avoid check RR key method failed with additional key being inserted
    }

    if (!succeed) {
      Log.getLogWriter().info("this tx failed with exception (conflict or node failure)");
    }

  }

  protected void commitOrRollbackGfxdOnly(Connection gConn) {
    int chanceToRollback = 20;
    if (random.nextInt(chanceToRollback) == 1 || (Boolean)rollbackGfxdTx.get()) {
      Log.getLogWriter().info("roll back the tx");
      rollback(gConn);
    } else {
      Log.getLogWriter().info("commit the tx");
      commitRRGfxdOnly(gConn);
    }
  }

  protected void commitRRGfxdOnly(Connection conn) {
    if (conn == null) return;
    try {
      Log.getLogWriter().info("committing the ops for gfxd");
      conn.commit();
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
        Log.getLogWriter().info("detected expected conflict during commit in RR, continuing test");
        return;
      } else if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        log().info("commit failed in gfxd RR tx due to node failure, continuing test");

        return;
      }
      else
        SQLHelper.handleSQLException(se);
    }
  }

  protected void doDMLOpRRByOne(Connection dConn, Connection gConn) {
    Log.getLogWriter().info("using RR isolation");
    //setting current txId
    boolean firstInThisRound = false;
    if (SQLBB.getBB().getSharedCounters().incrementAndRead
        (SQLBB.firstInRound) == 1) {
      firstInThisRound = true;
      Log.getLogWriter().info("I am the first to commit in this round");
      commitEarly.set(true);
    } else {
      //commitEarly.set(random.nextBoolean());  //TODO, use this one instead below setting
      if (random.nextInt(10) == 1) commitEarly.set(true);
      else commitEarly.set(false);
    }

    boolean ticket42651fixed = true;

    int txId = (int) SQLTxBB.getBB().getSharedCounters().incrementAndRead(SQLTxBB.txId);
    curTxId.set(txId);
    boolean withDerby = false;
    if (dConn != null) withDerby = true;
    Log.getLogWriter().info("performing dmlOp using RR, myTid is " + getMyTid() + " and txId is " + txId);
    HashMap<String, Integer> modifiedKeys = new HashMap<String, Integer>();
    curTxModifiedKeys.set(modifiedKeys);
    HashMap<String, Integer> readLockedKeys = new HashMap<String, Integer>();
    curTxRRReadKeys.set(readLockedKeys);


    int total = 4;
    int numOfIter = random.nextInt(total) + 1;
    int maxOps = 5;
    int numOps = random.nextInt(maxOps) + 1;
    for (int i=0; i<numOfIter-1; i++) {
      getLock(); //read write conflict detected at commit time, and write write detect at operation time
      //doGfxdDMLOpByOne(gConn, numOps, withDerby);
      doGfxdDMLOpRRByOne(dConn, gConn, numOps, withDerby); //work around #43170
      releaseLock(); //read write conflict detected at commit time, and write write detect at operation time

      //bring down data nodes or ddl ops will be done by ddl thread
    }
    getLock(); //read write conflict detected at commit time, and write write detect at operation time
    //doGfxdDMLOpByOne(gConn, numOps, withDerby);
    doGfxdDMLOpRRByOne(dConn, gConn, numOps, withDerby); //work around #43170
    releaseLock(); //read write conflict detected at commit time, and write write detect at operation time
    waitForBarrier();

    if (firstInThisRound) {
      SQLBB.getBB().getSharedCounters().zero(SQLBB.firstInRound); //for next round
      /*
      if (ticket42651fixed)
        processDerbyOps(dConn, true);
      else
        processDerbyOps(dConn, false);
      */
      //first in this round could compare the query results
      //handled in the commitorrollback method now
      commitOrRollback(dConn, gConn, firstInThisRound);

      removeHoldKeysByThisTx(); //first to clean up the keys
      closeSelectForUpdateRS(); //first to close any select for update rs.

    }
    waitForBarrier();

    /*
    //wait for barrier for commit in rc if mix rr and rc
    if (mixRR_RC) {
      Log.getLogWriter().info("earlier commit in RC, do nothing for RRs");
      waitForBarrier();
      Log.getLogWriter().info("commit in RC, do nothing for RRs");
      waitForBarrier();
    }
    */

    if (!firstInThisRound && (Boolean)commitEarly.get()) {
      getLock();
      //need to check conflict with RRs here
      commitOrRollback(dConn, gConn, firstInThisRound);

      //release lock held
      removeHoldKeysByThisTx(); //everyone clean up the keys
      closeSelectForUpdateRS(); //close any select for update rs.
      releaseLock();
    } //commit earlier
    waitForBarrier();

    if (!firstInThisRound && !(Boolean)commitEarly.get()) {
      getLock();
      //need to check conflict with RRs here

      commitOrRollback(dConn, gConn, firstInThisRound);

      //release lock held
      removeHoldKeysByThisTx(); //everyone clean up the keys
      closeSelectForUpdateRS(); //close any select for update rs.
      releaseLock();
    } //commit later


    waitForBarrier();
  }

  protected void doGfxdDMLOpRRByOne(Connection dConn, Connection gConn, int size,
      boolean withDerby) {
    for (int i=0; i<size; i++) {
      doOneGfxdDMLOpRRByOne(dConn, gConn, withDerby);
    }
  }

  @SuppressWarnings("unchecked")
  protected void doOneGfxdDMLOpRRByOne(Connection dConn, Connection gConn,
      boolean withDerby) {
    doOneGfxdDMLOpRRByOne(gConn, withDerby); //operates in gfxd
    if (!ticket43170fixed && withDerby) {
      //check if there is an constraint violation
      ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
      if (derbyOps != null && derbyOps.size()>0) { //no ops as some table operations are not implemented yet
        Object[] data = derbyOps.get(derbyOps.size() -1);
        SQLException gfxdse = (SQLException) data[data.length-1];
        if (gfxdse != null && (gfxdse.getSQLState().equals("23503") ||
            gfxdse.getSQLState().equals("23505") || gfxdse.getSQLState().equals("23513"))) {
          processDerbyOps(dConn, true); //see if indeed the constraint occurs

          //rollback(gConn); //not needed due to #43170
          removeHoldKeysByThisTx(); //due to #43170
          closeSelectForUpdateRS(); //close any select for update rs.
          rollback(dConn); //roll back the derby ops
          resetDerbyOps();
        }
      }
    }
  }

  @SuppressWarnings("static-access")
  protected void doOneGfxdDMLOpRRByOne(Connection gConn, boolean withDerby) {
    boolean succeed = true;
    int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
    DMLDistTxStmtIF dmlStmt= dmlDistTxFactory.createDMLDistTxRRStmt(table); //dmlStmt of a table
    boolean reproduce48167 = true;

    if (!reproduce48167) {
      if (table != dmlTxFactory.TRADE_CUSTOMERS  && table != dmlTxFactory.TRADE_NETWORTH
         && table != dmlTxFactory.TRADE_SECURITIES ) /*&& table != dmlTxFactory.TRADE_PORTFOLIO)
        /* && table != dmlTxFactory.TRADE_SELLORDERS) avoid #42672 in rr for now*/ return;
    } else {
      if (table != dmlTxFactory.TRADE_CUSTOMERS  && table != dmlTxFactory.TRADE_NETWORTH
          && table != dmlTxFactory.TRADE_SECURITIES && table != dmlTxFactory.TRADE_PORTFOLIO
         && table != dmlTxFactory.TRADE_SELLORDERS) return;
      //adding two child tables in the RR tx testing.
    }


    //perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
      if (!(Boolean)commitEarly.get())
        succeed = dmlStmt.insertGfxd(gConn, withDerby);
      else
        return;
        //do not do insert if they are going to be committed earlier
    }
    else if (operation.equals("update")) {
      succeed = dmlStmt.updateGfxd(gConn, withDerby);
    }
    else if (operation.equals("delete"))
      succeed = dmlStmt.deleteGfxd(gConn, withDerby);
    else if (operation.equals("query"))
      succeed = dmlStmt.queryGfxd(gConn, withDerby);
    else
      throw new TestException("Unknown entry operation: " + operation);

    //insert into networth table() -- should use trigger to insert the row
    if (table == dmlTxFactory.TRADE_CUSTOMERS && operation.equals("insert") && succeed) {
      if ((Boolean)batchInsertToCustomersSucceeded.get()) {
        Log.getLogWriter().info("inserting into networth table");
        TradeNetworthDMLDistTxRRStmt networth = new TradeNetworthDMLDistTxRRStmt();
        succeed = networth.insertGfxd(gConn, withDerby, (Integer) cidInserted.get());
      } else {
        Log.getLogWriter().info("insert into customers using batch failed, do" +
          " not insert to networth table due to #43170. as the sqlf fails the whole batch");
        batchInsertToCustomersSucceeded.set(true);
      }
    }

    /* comment out the followings once #42703 is supported
     * */
    if (!succeed) {
      rollback(gConn); //work around #42703
      removeHoldKeysByThisTx();
      closeSelectForUpdateRS(); //close any select for update rs.
      resetDerbyOps();
    } //lock not held and transaction rolled back
  }

  @Override
  protected void removeHoldKeysByThisTx() {
    removeRRWriteKeysByThisTx();

    removeRRReadKeysByThisTx();

    //also remove range foreign key held by this tx as well
    removeAllRangeFKsByThisTx();
  }

  @SuppressWarnings("unchecked")
  protected void removeRRWriteKeysByThisTx() {
    SharedMap modifiedKeysByRRTx = SQLTxRRWriteBB.getBB().getSharedMap();
    //write locked keys
    Set<String> alreadyHeldKeysThisTx = ((HashMap<String, Integer>)
        curTxModifiedKeys.get()).keySet();
    Log.getLogWriter().info("removing the RR write keys from the Map that were held by this tx");
    for (String key: alreadyHeldKeysThisTx) {
      Log.getLogWriter().info("key to be removed is " + key);
      modifiedKeysByRRTx.remove(key);
    }
    curTxModifiedKeys.set(new HashMap<String, Integer>()); //new map is set
  }

  @SuppressWarnings("unchecked")
  protected void removeRRReadKeysByThisTx() {
    //read locked keys
    int txId = (Integer) curTxId.get();
    SharedMap readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();
    Set<String> readLokedKeysByThisTx = ((HashMap<String, Integer>)
        curTxRRReadKeys.get()).keySet();
    Log.getLogWriter().info("removing the RR read keys from the Map that were " +
    		"held by this txId: " + txId);
    for (String key: readLokedKeysByThisTx) {
      Log.getLogWriter().info("RR read key to be removed is " + key);
      ReadLockedKey readKey = (ReadLockedKey) readLockedKeysByRRTx.get(key);
      readKey.removeAllReadLockedKeyByCurTx(txId);
      readLockedKeysByRRTx.put(key, readKey);
    }
  }

  protected void commitOrRollback(Connection dConn, Connection gConn, boolean firstCommit) {
    //getLock();  //no need as RR commit requires the lock first for checking read locked keys
    processDerbyOps(dConn, firstCommit);
    //releaseLock();

    int chanceToRollback = firstCommit? 5: 20;
    if (random.nextInt(chanceToRollback) == 1 || (Boolean)rollbackGfxdTx.get()) {
      Log.getLogWriter().info("roll back the tx");
      rollbackGfxdTx.set(false); //reset for next round
      rollback(dConn);
      rollback(gConn);
    } else {
      commitRR(dConn, gConn);
    }
  }

  protected void commitRR(Connection dConn, Connection gConn) {
    Log.getLogWriter().info("commit the tx");
    try {
      Log.getLogWriter().info("committing the ops for gfxd");
      gConn.commit();
      Log.getLogWriter().info("tx is committed for gfxd");
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z02")) {
        verifyConflictForRR(true, se);
        Log.getLogWriter().info("got expected conflict during commit for RR");

        rollback(dConn);
        Log.getLogWriter().info("rollback the ops in derby");
        return;
      } else if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
        log().info("commit failed in gfxd due to node failure");

        rollback(dConn);
        Log.getLogWriter().info("rollback the ops in derby");
        return;
      } else {
        SQLHelper.handleSQLException(se);
      }
    }

    verifyConflictForRR(false, null);
    commit(dConn); //no conflict expected
  }

  @SuppressWarnings("unchecked")
  protected void verifyConflictForRR(boolean getConflicts, SQLException se) {
    //for keys read locked by RR
    SharedMap readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();
    int beforeSize =  readLockedKeysByRRTx.size();
    Map readLockedKeysByOtherRR = readLockedKeysByRRTx.getMap();

    HashMap<String, Integer> modifiedKeysByThisTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();

    Log.getLogWriter().info("at the commit, the read locked map size is " + beforeSize);

    /* for debug
    if (beforeSize !=0) {
      Collection <ReadLockedKey> readKeys = (Collection<ReadLockedKey>) readLockedKeysByOtherRR.values();
      for (ReadLockedKey readKey: readKeys) {
        readKey.logCurrTxIds();
      }
    }
    */

    ArrayList<Integer>otherTxIds = new  ArrayList<Integer>();
    int myTxId = (Integer) SQLDistTxTest.curTxId.get();
    for (String key: modifiedKeysByThisTx.keySet()) {
      Log.getLogWriter().info("modified keys by this tx contains: " + key);
      ReadLockedKey rlkey = (ReadLockedKey) readLockedKeysByOtherRR.get(key);
      if (rlkey != null) {
        rlkey.findOtherTxId(myTxId, otherTxIds);
        if (otherTxIds.size() != 0) {
          if (getConflicts) return;
          //got the expected conflict as other tx hold the read lock for the modified key
          else {
            StringBuffer str= new StringBuffer();
            for (int otherTxId: otherTxIds) str.append(otherTxId + " ");

            throw new TestException("Did not get expected conflict exception during commit " +
            		"as following txIds: " + str.toString() +
                " hold the read lock for " +
            		"the key: " + key + " to be modified by this tx");
          }
        }
      }
    }

    //if there is a genuine conflict being detected, the following code will not be reached
    if (getConflicts) throw new TestException("get conflict but there are no read " +
          "locks held for the committed keys by other txs\n" + TestHelper.getStackTrace(se));

  }

  public static void HydraTask_checkConstraints() {
    rrTest.checkConstraints();
  }

  protected void checkConstraints() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);

    checkUniqConstraints(gConn);
    checkFKConstraints(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  public static void HydraTask_checkRRKeys() {
    rrTest.checkRRKeys();
  }

  protected void checkRRKeys() {
    if (getMyTid()%3 != 0 && random.nextInt(20)!=1) {
      Log.getLogWriter().info("do dml ops instead of checking repeatable read keys");
      rrTest.doDMLOp();
      return;
    }

    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
    checkRRKeys(gConn);
    commitRRGfxdOnly(gConn);
    closeGFEConnection(gConn);
  }

  protected void checkRRKeys(Connection conn) {
    int whichOne = random.nextInt(5);
    switch (whichOne) {
    case 0: checkTradeSecruitiesKeys(conn);
      break;
    case 1: checkTradeNetworthKeys(conn);
      break;
    case 2: checkTradeCustomersKeys(conn);
      break;
    case 3: checkTradeSellordersKeys(conn);
      break;
    default: Log.getLogWriter().info("do nothing");
    }
  }

  protected void checkTradeSecruitiesKeys(Connection conn) {
    String sql = "select sec_id, symbol, exchange, price from trade.securities where sec_id>? and sec_id < ?";
    int sid = AbstractDMLStmt.getExistingSid();
    int sid1 = sid + 10;

    verifyRRKeys(conn, sql, sid, sid1);
  }

  protected void checkTradeNetworthKeys(Connection conn) {
    String sql = "select cid, cash, securities, loanLimit, availLoan" +
    		" from trade.networth where cid>? and cid < ?";
    int cid = AbstractDMLStmt.getExistingCid();
    int cid1 = cid + 10;

    verifyRRKeys(conn, sql, cid, cid1);
  }

  protected void checkTradeCustomersKeys(Connection conn) {
    String sql = "select cid, cust_name, since, addr" +
        " from trade.customers where cid>? and cid < ?";

    int cid = AbstractDMLStmt.getExistingCid();
    int cid1 = cid + 10;

    verifyRRKeys(conn, sql, cid, cid1);
  }

  protected void checkTradeSellordersKeys(Connection conn) {
    String sql = "select oid, status, ask " +
        " from trade.sellorders where oid>? and oid < ?";

    int id = TradeSellOrdersDMLDistTxStmt.getExistingOid();
    int id1 = id + 20;

    verifyRRKeys(conn, sql, id, id1);
  }

  protected void verifyRRKeys(Connection conn, String sql, int id, int id1) {
    List<Struct> rsList = null;
    List<Struct> rsRepeatList = null;
    for (int i = 0; i < 10; i++) {
      try {
        Log.getLogWriter().info("RR: executing query(verifyRRKeys) " + i + " times");
        MasterController.sleepForMs(100);
        PreparedStatement stmt = conn.prepareStatement(sql);
        Log.getLogWriter().info(sql + " -- id> " + id + " and id< " + id1);
        stmt.setInt(1, id);
        stmt.setInt(2, id1);
        ResultSet rs = stmt.executeQuery();
        rsList = ResultSetHelper.asList(rs, false);
      } catch (SQLException se) {
        if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
          log().info("op failed in gfxd RR tx due to node failure, continuing test");
        } else if (se.getSQLState().equalsIgnoreCase("X0Z02") && !reproduce49935) {
          Log.getLogWriter().info("hit #49935, continue for now");
          return;
        } else if (se.getSQLState().equalsIgnoreCase("X0Z02") && (i<9)) {
          Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
          continue;
        }
        else SQLHelper.handleSQLException(se);
      } catch (TestException te) {
        if (isHATest &&
            (te.getMessage().contains("40XD0") || te.getMessage().contains("40XD2"))) {
          Log.getLogWriter().info("got expected node failure exception, continuing test");
        } else if (te.getMessage().contains(" Conflict detected in transaction operation and it will abort") && (i < 9)) {
          Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
          continue;
        } else throw te;
      }
      break;
    }
    if (rsList == null) {
      if (isHATest) {
        Log.getLogWriter().info("could not get result in HA tests");
        return;
      } else {
        throw new TestException("Could not get resultset using RR and it is not HA test");
      }
    }

    //wait for checking the RR keys
    int sleepMS = 20000;
    Log.getLogWriter().info("sleep for " + sleepMS / 1000 + " sec to check repeatable read keys");
    MasterController.sleepForMs(sleepMS);


    for (int i = 0; i < 10; i++) {
      try {
        // Wait for 100 ms before retrying
        MasterController.sleepForMs(100);
        Log.getLogWriter().info("RR: executing query " + i + " times");
        PreparedStatement stmt = conn.prepareStatement(sql);
        Log.getLogWriter().info(sql + " id> " + id + " and id< " + id1);
        stmt.setInt(1, id);
        stmt.setInt(2, id1);
        ResultSet rs = stmt.executeQuery();
        rsRepeatList = ResultSetHelper.asList(rs, false);
      } catch (SQLException se) {
        if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
          log().info("op failed in gfxd RR tx due to node failure, will try the op, continuing test");
        } else if (se.getSQLState().equalsIgnoreCase("X0Z02") && !reproduce49935) {
          Log.getLogWriter().info("hit #49935, continue for now");
          return;
        } else if (se instanceof SQLTransactionRollbackException && (i<9)) {
          Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
          continue;
        } else SQLHelper.handleSQLException(se);
      } catch (TestException te) {
        if (isHATest &&
            (te.getMessage().contains("40XD0") || te.getMessage().contains("40XD2"))) {
          Log.getLogWriter().info("got expected node failure exception, continuing test");
        } else if (te.getMessage().contains(" Conflict detected in transaction operation and it will abort") && (i <9)) {
          Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
          continue;
        } else throw te;
      }
      break;
    }

    while (rsRepeatList == null && isHATest) {
      for (int i = 0; i < 10; i++) {
        try {
          PreparedStatement stmt = conn.prepareStatement(sql);
          Log.getLogWriter().info(sql + " id> " + id + " and id< " + id1);
          stmt.setInt(1, id);
          stmt.setInt(2, id1);
          Log.getLogWriter().info("RR: executing query " + i + " times");
          ResultSet rs = stmt.executeQuery();
          rsRepeatList = ResultSetHelper.asList(rs, false);
        } catch (SQLException se) {
          if (isHATest && SQLHelper.gotTXNodeFailureException(se)) {
            //log().info("op failed in gfxd RR tx due to node failure, will try the op, continuing test");
            log().info("could not retry as no HA support for txn yet");
            return;
          } else if (se instanceof SQLTransactionRollbackException && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
            continue;
          } else SQLHelper.handleSQLException(se);
        } catch (TestException te) {
          if (isHATest &&
              (te.getMessage().contains("40XD0") || te.getMessage().contains("40XD2"))) {
            //Log.getLogWriter().info ("got expected node failure exception, continuing test");
            log().info("could not retry as no HA support for txn yet");
            return;
          } else if (te.getMessage().contains(" Conflict detected in transaction operation and it will abort") && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying as got Conflict in executing query.");
            continue;
          } else throw te;
        }
        break;
      }
    }
    
    ResultSetHelper.compareResultSets(rsList, rsRepeatList, 
        "original RR result", "retry RR result");
  }
  
  
  public static void HydraTask_checkModifiedRRKeys() {
    //rrTest.checkDerbyModifiedRRKeys();
    rrTest.checkGfxdModifiedRRKeys();
  }
  
  protected void checkGfxdModifiedRRKeys() {
    Connection gConn = getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ); 
    printIsolationLevel(gConn);
    
    checkModifiedRRKeys(gConn);
  }
  
  protected void checkDerbyModifiedRRKeys() {
    Connection conn = getDiscConnection();
    try {
      conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      printIsolationLevel(conn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    checkModifiedRRKeys(conn);
  }

  protected void checkModifiedRRKeys(Connection conn) {    
    String sql = "select oid, status, ask, tid " +
    " from trade.sellorders where oid>? and oid < ?";

    int id = TradeSellOrdersDMLDistTxStmt.getExistingOid();
    int id1 = id + 20;
    
    //verifyRRKeys(conn, sql, id, id1);
    
    List<Struct> rsList = null;
    List<Struct> rsRepeatList = null;
    try {
      PreparedStatement stmt =conn.prepareStatement(sql);
      Log.getLogWriter().info(sql + " -- id> " + id + " and id< " + id1 );
      stmt.setInt(1, id);
      stmt.setInt(2, id1);
      ResultSet rs = stmt.executeQuery();
      rsList = ResultSetHelper.asList(rs, true);
      Log.getLogWriter().info("original result set is " + ResultSetHelper.listToString(rsList));
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    //wait for checking the RR keys 
    int sleepMS = 20000;
    Log.getLogWriter().info("sleep for " + sleepMS/1000 + " sec to check repeatable read keys");
    MasterController.sleepForMs(sleepMS);
    

    try {
      int updateCount = conn.createStatement().executeUpdate("update trade.sellorders set tid = " + 
          1000000 + " where oid = " + (id +1));
      Log.getLogWriter().info("in check sellorders RR key, update count is " + updateCount);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
    } //add update

    
    try {
      PreparedStatement stmt =conn.prepareStatement(sql);
      Log.getLogWriter().info(sql + " id> " + id + " and id< " + id1 );
      stmt.setInt(1, id);
      stmt.setInt(2, id1);
      ResultSet rs = stmt.executeQuery();
      rsRepeatList = ResultSetHelper.asList(rs, false);
      Log.getLogWriter().info("original result set is " + ResultSetHelper.listToString(rsRepeatList));
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    while (rsRepeatList == null && isHATest) {
      try {
        PreparedStatement stmt =conn.prepareStatement(sql);
        Log.getLogWriter().info(sql + " cid> " + id + " and cid< " + id1 );
        stmt.setInt(1, id);
        stmt.setInt(2, id1);
        ResultSet rs = stmt.executeQuery();
        rsRepeatList = ResultSetHelper.asList(rs, false);
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      } 
    } 
    
    ResultSetHelper.compareResultSets(rsList, rsRepeatList, 
        "original RR result", "retry RR result");
    
    commit(conn);
    
    List<Struct> rsaftercommitList = null;
    
    try {
      PreparedStatement stmt =conn.prepareStatement(sql);
      Log.getLogWriter().info(sql + " id> " + id + " and id< " + id1 );
      stmt.setInt(1, id);
      stmt.setInt(2, id1);
      ResultSet rs = stmt.executeQuery();
      rsaftercommitList = ResultSetHelper.asList(rs, false);
      Log.getLogWriter().info("original result set is " + ResultSetHelper.listToString(rsaftercommitList));
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    ResultSetHelper.compareResultSets(rsList, rsaftercommitList, 
        "original RR result", "RR result after commit");
      
  }
  
  public static void HydraTask_cycleStoreVms() {
    if (cycleVms) rrTest.cycleStoreVms();
  }
  
  protected Connection getGFXDRRTxConnection(Properties info) {
    Connection conn = null;
    try {
      conn = GFEDBManager.getRRTxConnection(info);
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get connection " + TestHelper.getStackTrace(e));
    }
    return conn;
  }

  @Override
  protected Connection getNewGfxdConnection() {
    return getGFXDTxConnection(GFEDBManager.Isolation.REPEATABLE_READ);
  }
}
