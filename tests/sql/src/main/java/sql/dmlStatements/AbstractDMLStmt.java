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
package sql.dmlStatements;

import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.MasterController;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.SharedMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

import javax.sql.rowset.serial.SerialClob;

import sql.GFEDBClientManager;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.security.SQLSecurityTest;
import sql.sqlTx.ForeignKeyLocked;
import sql.sqlTx.SQLDistRRTxTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxBB;
import sql.sqlTx.SQLTxBatchingBB;
import sql.sqlTx.SQLTxBatchingDeleteHoldKeysBB;
import sql.sqlTx.SQLTxBatchingFKBB;
import sql.sqlTx.SQLTxBatchingNonDeleteHoldKeysBB;
import sql.sqlTx.SQLTxDeleteHoldKeysBlockingChildBB;
import sql.sqlTx.SQLTxHoldKeysBlockingChildBB;
import sql.sqlTx.SQLTxHoldForeignKeysBB;
import sql.sqlTx.SQLTxHoldNewForeignKeysBB;
import sql.sqlTx.SQLTxPrms;
import sql.sqlTx.SQLTxRRReadBB;
import sql.sqlTx.SQLTxRRWriteBB;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import sql.wan.SQLWanBB;
import sql.wan.SQLWanPrms;
import util.TestException;
import util.TestHelper;

import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;

/**
 * @author eshu
 *
 */
public abstract class AbstractDMLStmt implements DMLStmtIF {  
  protected static final Random rand = SQLTest.random;
  protected static boolean testUniqueKeys = SQLTest.testUniqueKeys;
  protected static boolean isSerial = SQLTest.isSerial;
  protected static boolean randomData = SQLTest.randomData;
  protected static boolean queryAnyTime = SQLTest.queryAnyTime;
  protected static boolean hasNetworth = SQLTest.hasNetworth;
  protected static HashMap<Integer, ArrayList<Integer>> cidsByThread = 
    new HashMap<Integer, ArrayList<Integer>>(); //used to track the cid inserts/deleted by a thread
  protected static SharedMap partitionMap = SQLBB.getBB().getSharedMap(); //shared data in bb to get partitionKey,
  protected static SharedMap wanPartitionMap = SQLWanBB.getBB().getSharedMap(); //
  protected static boolean isHATest = TestConfig.tab().longAt(util.StopStartPrms.numVMsToStop, 0) > 0? true: false || TestConfig.tab().booleanAt(SQLPrms.rebalanceBuckets, false);
  protected static boolean usingTrigger = TestConfig.tab().booleanAt(SQLPrms.usingTrigger, false);
  
  /*** followings settings are for sqlt tx ***/
  public static final String TXLOCK = "lockToPerformTx";
  public static final String LOCK_SERVICE_NAME = "MyLockService";
  protected static DistributedLockService dls;  //use shared lock for bridge
  protected static hydra.blackboard.SharedLock lock;
  public static boolean byCidRange = TestConfig.tab().booleanAt(SQLPrms.byCidRange, false);
  public static boolean byTidList = TestConfig.tab().booleanAt(SQLPrms.byTidList, false);
  public static boolean singleRowTx = TestConfig.tab().booleanAt(SQLPrms.singleRowTx, true);
  protected static String thisTxId = "thisTxId"; 
  public static String cid_txId = "cid_txId_";
  public static int cidRangeForTxOp = 5;
  public static boolean isWanTest = TestConfig.tab().booleanAt(SQLWanPrms.isWanTest, false);
  public static boolean testLoaderCreateRandomRow = TestConfig.tab().booleanAt(SQLPrms.testLoaderCreateRandomRow, false);
  protected static boolean useWriterForWriteThrough = TestConfig.tab().booleanAt(SQLPrms.useWriterForWriteThrough, false);
  protected static int retrySleepMs = 100;
  protected static int numOfThreads = TestConfig.tab().intAt(SQLPrms.numOfWorkers, 6);
  protected static int numGettingDataFromDerby = (numOfThreads > 6) ? 20: numOfThreads; 
  protected static boolean useGfxdConfig = TestConfig.tab().booleanAt(SQLPrms.useGfxdConfig, false);
  protected static boolean testSecurity = SQLTest.testSecurity;
  public static boolean isOfflineTest = TestConfig.tab().booleanAt(SQLPrms.isOfflineTest, false);
  protected static boolean useTimeout = SQLDistTxTest.useTimeout;
  protected static boolean mixRR_RC = SQLDistRRTxTest.mixRR_RC;
  protected static boolean setCriticalHeap = SQLTest.setCriticalHeap;
  protected static boolean nobatching = TestConfig.tab().booleanAt(SQLTxPrms.nobatching, true); //test default to true -- 
    //when it is set to true -- assume either no batching or batching without secondary copy or replicate
    //when set to false -- it means need to test batching with secondary data.
  protected static boolean batchingWithSecondaryData = !nobatching; //default is false
  protected static boolean testWanUniqueness = SQLTest.testWanUniqueness; 
  protected boolean generateIdAlways = SQLTest.generateIdAlways;
  protected boolean generateDefaultId = SQLTest.generateDefaultId;
  protected static boolean hasCompanies = SQLTest.hasCompanies;
  protected static boolean failAtUpdateCount = SQLTest.failAtUpdateCount;
  protected boolean ticket46799fixed = TestConfig.tab().booleanAt(SQLPrms.ticket46799fixed, false);
  protected boolean noNullClob = TestConfig.tab().booleanAt(SQLPrms.noNullClob, false);
  protected static boolean alterTableDropColumn = SQLTest.alterTableDropColumn;
  protected static short[] digits = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  public static boolean gfxdtxHANotReady = true;
  public boolean testPartionBy = SQLTest.testPartitionBy;
  protected static boolean isTicket48176Fixed = true;
  protected static boolean isTicket49338Fixed = false;
  protected static boolean allowUpdateOnPartitionColumn = TestConfig.tab().booleanAt(SQLPrms.allowUpdateOnPartitionColumn, false);
  public static HydraThreadLocal dumpNoAggregateRs = new HydraThreadLocal();
  public static HydraThreadLocal dumpQueryPlanRs = new HydraThreadLocal();
  protected static boolean hasHdfs = SQLTest.hasHdfs;
  protected static boolean ticket42672fixed = false;
  public static boolean reproduce49935 = SQLDistRRTxTest.reproduce49935;
  protected static boolean useMD5Checksum = ResultSetHelper.useMD5Checksum;
  public static boolean queryOpTimeNewTables = SQLDistTxTest.queryOpTimeNewTables; 
  public static String parentKeyHeldTxid = "parentKeyHeldTxid";
  public static boolean isSingleHop = TestConfig.tab().booleanAt(SQLPrms.isSingleHop, false);
  public static boolean hasTx = TestConfig.tab().booleanAt(SQLPrms.hasTx, false);
  public static ThreadLocal<Calendar> myCal; 
  public static boolean testworkaroundFor51519 = TestConfig.tab().booleanAt(sql.SQLPrms.testworkaroundFor51519,
      true) && !SQLTest.hasDerbyServer;

  static {
    if (SQLTest.isSnappyTest || SQLPrms.isSnappyMode()) {
      //do nothing
    } else if (SQLTest.isEdge) lock = SQLBB.getBB().getSharedLock();
    else dls = getLockService();
  }
  
  static 
  protected void getLock() {
    //get the lock
    if (!SQLTest.isEdge)
      dls.lock(TXLOCK, -1, -1); //for distributed service
    else
      lock.lock();
  }
  
  protected void releaseLock() {
    if (!SQLTest.isEdge)
      dls.unlock(TXLOCK);
    else
      lock.unlock();
  }
  /*** end for gfxd tx***/
  

  public abstract void insert(Connection dConn, Connection gConn, int size); 
  public abstract void update(Connection dConn, Connection gConn, int size); 
  public abstract void delete(Connection dConn, Connection gConn); 
  public abstract void query(Connection dConn, Connection gConn); 
  
  //populate the table
  public void populate (Connection dConn, Connection gConn) {
    int initSize = 10;
    populate (dConn, gConn, initSize);
  }
  
  //populate the table
  public void populate (Connection dConn, Connection gConn, int initSize) {
    for (int i=0; i<initSize; i++) {
      if (setCriticalHeap) resetCanceledFlag();
      insert(dConn, gConn, 1);
      commit(gConn); 
      Log.getLogWriter().info("gfxd committed");
      if (dConn != null) {
        commit(dConn);
        Log.getLogWriter().info("derby committed");
      }
    }
  }

  private static DistributedLockService getLockService() {
    DistributedLockService dls = DistributedLockService.getServiceNamed(LOCK_SERVICE_NAME);
    if (dls == null) {
      DistributedSystem ds = InternalDistributedSystem.getConnectedInstance();
      if (ds == null)
        throw new TestException("DistributedSystem is " + ds);
      dls = DistributedLockService.create(LOCK_SERVICE_NAME, ds);
    } else {

    }
    return dls;
  }
  
  protected int getMyWanSite() {
    if (isWanTest) {
      return sql.wan.WanTest.myWanSite;
    } else return -1;
  }
  
  //--- added methods used for subclasses ---//
  
  /**
   * to get my ThreadID used for update and delete record, so only particular thread can
   * update or delete a record it has inserted.
   * 
   * when testWanUniqueness is set to true, this will return the wan site id instead
   * 
   * @return The threadId of the current hydra Thread.
   */
  public static int getMyTid() {
    if (testWanUniqueness) {
      return sql.wan.WanTest.myWanSite; //return wan site id as tid, use tid to confine each site op on its set of keys
    }
    
    int myTid = RemoteTestModule.getCurrentThread().getThreadId();
    return myTid;
  }
  
  public static Calendar getCal() {
    return (Calendar.getInstance(TimeZone.getTimeZone(ResultSetHelper.defaultTimeZone)));
  }
  
  /**
   * to get a random ThreadID used in the test
   */
  protected int getRandomTid() {
    if (testWanUniqueness) {
      return sql.wan.WanTest.myWanSite; //return wan site id as tid, use tid to confine each site op on its set of keys
    }
    
    int myTid = getMyTid();    
    int offset = rand.nextInt(5);
    return myTid < 5 ? myTid+offset : myTid-offset;
  }
  
  protected static PreparedStatement getStmt(Connection conn, String sql) {
    PreparedStatement stmt = null;
    try {
      stmt = getStmtThrowException(conn, sql);
    } catch (SQLException se) {
      if (gfxdtxHANotReady && isHATest && !SQLTest.hasTx && SQLTest.setTx &&
        SQLHelper.gotTXNodeFailureException(se) && SQLTest.hasDerbyServer) {
        //used for original non txn tests
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("prepare statement got node failure exception during Tx without HA support, continue testing");   
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure == null) getNodeFailure = new boolean[1];
        getNodeFailure[0] = true;
        SQLTest.getNodeFailure.set(getNodeFailure);
      } else if (gfxdtxHANotReady && isHATest && SQLTest.hasTx &&
        SQLHelper.gotTXNodeFailureException(se) && SQLTest.hasDerbyServer) {
        //used for txn tests
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("prepare statement got node failure exception during Tx without HA support, continue testing");   
        SQLDistTxTest.failedToGetStmtNodeFailure.set(true);
      } else SQLHelper.handleSQLException(se);  
    }  
    return stmt;
  }  
  
  protected static PreparedStatement getStmtThrowException(Connection conn, String sql) throws SQLException {
    PreparedStatement stmt = null;
    try {
      stmt = conn.prepareStatement(sql);
    } catch (SQLException se) {
      if (SQLTest.testSecurity && (se.getSQLState().equals("42500")
          || se.getSQLState().equals("42502") || se.getSQLState().equals("25502"))) {
        Log.getLogWriter().info("sql is " + sql);
        SQLHelper.printSQLException(se);
        SQLSecurityTest.prepareStmtException.set(se);
      } else if (!SQLHelper.checkDerbyException(conn, se)) { //handles the read time out
        if (hasTx) {
          SQLDistTxTest.rollbackGfxdTx.set(true); 
          Log.getLogWriter().info("force gfxd to rollback operations as well");
          //force gfxd rollback as one operation could not be performed in derby
          //such as read time out 
        }
        else {
          Log.getLogWriter().info("get stmt failed due to lock issue or read time out"); //possibly gets read timeout in derby -- return null stmt
        }
      } else if ((se.getSQLState().equals("42X14") || se.getSQLState().equals("42802") || se.getSQLState().equals("42X04")) 
          && alterTableDropColumn) {
        SQLHelper.printSQLException(se);
        if (SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("alterTableException is set to true");
          SQLTest.alterTableException.set(true);
        }
        else {
          if (SQLTest.hasDerbyServer) {
            if (SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) {
              SQLTest.alterTableException.set(false); //got same alter table exception, cleared flag for next op
              Log.getLogWriter().info("alterTableException is set to false");
            } else
              throw new TestException("Derby does not get alter table exception but gfxd does" + TestHelper.getStackTrace(se));            
          }
        }
        Log.getLogWriter().info("got expected missing column due to alter table drop column");
        //derby will not get stmt, gfxd should not either to perform the dml op
      } /* this path is handled when exception is thrown to the caller
      else if (se.getSQLState().equals("X0Z05") 
          && SQLTest.hasTx && isHATest) { 
        //handles the gfxd tx node failure
        if (gfxdtxHANotReady) {
          //the gfxd operation failed, need to check whether tx is rolled back due to #43170
          //TODO ***

        }
        else {
          Log.getLogWriter().info("get stmt failed due to lock issue or read time out"); //possibly gets read timeout in derby -- return null stmt
        }
      } */
      else
        throw se;  
    }  
    return stmt;
  }  
  
  protected Statement getStmt(Connection conn) {
    Statement stmt;
    try {
      stmt = conn.createStatement();
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      throw new TestException("could not get Statement\n" + TestHelper.getStackTrace(se));
    }  
    return stmt;
  }  
  
  //test for bug #39913
  protected PreparedStatement getUnsupportedStmt(Connection conn, String sql) {
    if (!allowUpdateOnPartitionColumn) {
      if (hasTx) SQLDistTxTest.updateOnPartitionCol.set(true); 
      //the update op on partition columns in txn should abort without update the keys held.
      return null; //do not prepare statement for partition column 
    }
    //unless it is specifically set to test this.
    
    try {
      conn.prepareStatement(sql);
    } catch (SQLException se) {
      if (SQLTest.testSecurity && (se.getSQLState().equals("42500")
          || se.getSQLState().equals("42502"))) {
        SQLHelper.printSQLException(se);
        SQLSecurityTest.prepareStmtException.set(se);
      } else if (se.getSQLState().equals("0A000")) {
        if (SQLTest.hasTx) SQLDistTxTest.updateOnPartitionCol.set(true); //used by gfxd tx testing
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("Got the expected Exception, continuing test");
        return null;
      } else if (se.getSQLState().equals("42Z23") && (generateIdAlways || generateDefaultId)) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("Got the expected Exception, continuing test");
        return null;
      } else if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
        Log.getLogWriter().warning("memory runs low and get query cancellation exception");
        return null;  //was expect null stmt as this is unsupported, derby will not execute the stmt. otherwise, 
                      //need to set flag for derby to rollback the op.
      } else if ((se.getSQLState().equals("42X14") || se.getSQLState().equals("42802") || se.getSQLState().equals("42X04")) 
          && alterTableDropColumn) {
        SQLHelper.printSQLException(se);
        if (SQLHelper.isDerbyConn(conn)) {
          throw new TestException("test issue, derby connection should not execute this" + TestHelper.getStackTrace(se));
        }
        else {
          if (SQLTest.hasDerbyServer) {
            //derby expects unsupported exception, but as the column is dropped,
            //this is ok as derby will not perform the dml op as well
          }
        }
        Log.getLogWriter().info("got expected missing column due to alter table drop column");
        //derby will not get stmt, gfxd should not either to perform the dml op
        return null;
      } else if (gfxdtxHANotReady && isHATest &&
        SQLHelper.gotTXNodeFailureException(se) && SQLTest.hasDerbyServer) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got node failure exception during Tx without HA support, continue testing");   
        SQLDistTxTest.failedToGetStmtNodeFailure.set(true);
        return null;
      } else {
        throw new TestException("Not the expected  Feature not implemented \n" + TestHelper.getStackTrace(se));
      }
    }  
    throw new TestException("Did not get the expected  'Feature not implemented' exception\n" );
  }  
  
  /**
   * dml statements (update, delete, query) -- sql String[] need to be arranged so that first batch is 
   * for testUniqKeys (based on tid), others should be put at the end so that dml statements could be 
   * chosen accordingly. 
   * @param numOfNonUniq -- second part not based on tid
   * @param total -- all dml statements 
   * @return a suitable number to determine which array element to be used in the test
   */
  protected int getWhichOne(int numOfNonUniq, int total) {
    int whichOne;
    if (testUniqueKeys) {
      whichOne = rand.nextInt(total-numOfNonUniq); //unique dml are in front
    } else if (testWanUniqueness) {
      whichOne = rand.nextInt(total-numOfNonUniq); //reuse tid as wan site id
    } else {
      whichOne = rand.nextInt(total); //any dml
    } //get which one based on whether test uniquekeys
    
    return whichOne;
  }
  
  
  /**
   * used to see if resultSet size is large enough. 
   * return actual size available based on resultset if not enough 
   * @param result ArrayList representation of the resultSet
   * @param size where the resultSet has enough records
   * @param offset used to randomly select a start record instead of selecting first record
   * @return actual available size of the resultSet
   */
  protected int getAvailSize(ArrayList<Struct> result, int size, int[] offset) {
    int availSize = size;  
    if (result == null) return 0; //does not get result set 
    else if (size >= result.size()) {
      availSize = result.size(); 
    } else {
      offset[0] = rand.nextInt(result.size() - size);     
    } 
    return availSize;
  }
  
  /**
   * To check if the derby exception is expected and whether need to retry
   * @param conn -- to derby, used to rollback some transactions
   * @param se -- to check if the exception need to retry or not
   * @return false if need to retry
   */
  protected static boolean checkDerbyException(Connection conn, SQLException se) {
    if (se.getSQLState().equals("40001")) { //handles the deadlock of aborting
      Log.getLogWriter().info("detected the deadlock, will try it again");
      return false;
    } else if (se.getSQLState().equals("40XL1")) { //handles the "could not obtain lock"
      Log.getLogWriter().info("detected could not obtain lock, will try it again");
      try {
        conn.rollback(); //to retry all
      } catch (SQLException e) {
        SQLHelper.handleSQLException(e); //throws test exception
      }
      return false; //to retry
    }
    return true;
  }
  
  //get appropriate cid to be used for insert, when testUniqKeys only the cid inserted by this tid will be chosen
  public static int getCid(Connection conn) {
    int cid =0; 
    if (testUniqueKeys) {
      cid = getCidFromQuery(conn);
    } else if (testWanUniqueness) {
      cid = getCidFromQuery(conn);
    } 
    else
      cid = getRandomCid(conn); //randomly
    
    return cid;
  }
  
  //find a cid may inserted by any threads
  private static int getRandomCid(Connection conn) {
    if (rand.nextBoolean()) return getCid();
    else {
      int tid = getMyTid();
      return getCidFromQuery(conn, tid == 0 ? ++tid : --tid );
    }
  }
  
  public static int getCidFromQuery(Connection regConn) {
    return getCidFromQuery(regConn, getMyTid());
  }
  
  public static int getCidFromQuery(Connection regConn, int tid) {
    
    Connection conn = getAuthConn(regConn);
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
    Log.getLogWriter().info(database + "get cid from query");
    int cid =0; 
    boolean[] success = new boolean[1];

    int whichQuery = 0;
    ResultSet rs = TradeCustomersDMLStmt.getQuery(conn, whichQuery, 0, null, tid);
    if (rs == null) {
      success[0]= false;
    }
    else {
      cid = getCid(rs, success, conn);
    }
    int count = 0;
    //avoid retry when criticalHeap is set to avoid canceled query issue.
    int maxNumOfTries = setCriticalHeap ? 0 : (SQLHelper.isDerbyConn(conn)? 1 : 3);
    while (!success[0]) {
      if (count>=maxNumOfTries) {
        Log.getLogWriter().info(database + "Could not get the cid, use cid as 0 instead");
        return cid;
      }
      rs = TradeCustomersDMLStmt.getQuery(conn, whichQuery, 0, null, tid);
      if (rs == null) success[0]= false;
      else cid = getCid(rs, success, conn);
      if (cid == -1) {
        cid = 0;
        return cid;
      } //if derby server processing query issue occurred
      count++;
    }    

    return cid;
  }
  
  public static long getNewTypeCidFromQuery(Connection regConn, String tableName, int tid) {   
    Connection conn = getAuthConn(regConn);
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
    Log.getLogWriter().info(database + "get cid from query");
    long cid =0; 
    boolean[] success = new boolean[1];

    ResultSet rs = getQueryResults(conn, tableName, tid);
    if (rs == null) {
      success[0]= false;
    }
    else {
      cid = getNewTypeCid(rs, success, conn);
    }
    int count = 0;
    int maxNumOfTries = setCriticalHeap ? 1 : (SQLHelper.isDerbyConn(conn)? 1 : 3);
    while (!success[0]) {
      if (count>=maxNumOfTries) {
        Log.getLogWriter().info(database + "Could not get the cid, use cid as 0 instead");
        return cid;
      }
      rs = getQueryResults(conn, tableName, tid);
      if (rs == null) success[0]= false;
      else cid = getNewTypeCid(rs, success, conn);
      if (cid == -1) {
        cid = 0;
        return cid;
      } //if derby server processing query issue occurred
      count++;
    }    

    return cid;
  }
  
  private static ResultSet getQueryResults(Connection conn, String tableName, int tid) {
    String sql = "select * from trade." + tableName;
    Log.getLogWriter().info(sql);
    ResultSet rs = null;
    try {
      rs = conn.createStatement().executeQuery(sql);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (!SQLHelper.checkDerbyException(conn, se) || !SQLHelper.checkGFXDException(conn, se)) return rs; 
      else SQLHelper.handleSQLException(se);
    }    
    return rs;
  }
  
  protected static Connection getAuthConn(Connection regConn){
    if (testSecurity) {
      Connection conn;
      conn = SQLHelper.isDerbyConn(regConn)? 
        (Connection) SQLSecurityTest.derbySuperUserConn.get():
        ((SQLSecurityTest.bootedAsSuperUser)?
          (Connection)SQLSecurityTest.gfxdSuperUserConn.get():
          (Connection)SQLSecurityTest.gfxdSystemUserConn.get());
      return conn;
    } else return regConn;
  }

  //put a List of cids inserted by this thread to the HashMap
  //this should be invoked after populating the customers table 
  //and each insert/delete/(update primary key) operation in customers table
  protected boolean trackCids(Connection conn) {
    int whichQuery = 0;
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
    ResultSet rs = TradeCustomersDMLStmt.getQuery(conn, whichQuery, 0, null, getMyTid());
    if (rs==null) {
      Log.getLogWriter().info(database + "Not able to get results set from derby for thread " + getMyTid());
      return false;
    }
    ArrayList<Integer> rl = (ArrayList<Integer>) ResultSetHelper.getCidsAsList(conn, rs);
    if (rl == null) {
      Log.getLogWriter().info(database + "Not able to put cids by this thread " + getMyTid() 
          + "need to analyze the log.");
      return false;
    }
    else {
      cidsByThread.put(new Integer(getMyTid()), rl);  //put the list to the map for future use
      return true;
    }    
  }
  
  /**
   * To process the resultSet and get appropriate cid inserted by this thread before
   * @param rs -- the resultSet of cids inserted before
   * @param success -- whether we could process the resultSet or not
   * @return a cid inserted by this thread or 0 if no cid has been inserted by this thread exists
   */
  private static int getCid(ResultSet rs, boolean[] success, Connection conn) {
    int cid = 0;
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
    Log.getLogWriter().info(database + "processing resultset");
    List<Struct> customers = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    if (customers != null) {
      Log.getLogWriter().info(database + "customer size is " +  customers.size());
      success[0] = true;
      cid =  customers.size() > 0? 
          (Integer) customers.get(rand.nextInt(customers.size())).get("CID") : 0;
    } else {
      success[0] = false;
    }
    /*
    int n =0;
    success[0] = true;
    if (useGfxdConfig) 
      n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary)/numOfThreads);
    else
      n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary)/TestHelper.getNumThreads());
    int temp=0;
    try {
      while (rs.next()) {
        if (n==temp) {
          cid = rs.getInt("CID");
          //Log.getLogWriter().info("in query result cid is "+ cid);
          break;
        }
        cid = rs.getInt("CID");
        temp++;
      }
      rs.close();
    } catch (SQLException se) {      
      if (se.getSQLState().equals("40XL1")) { //handles the "could not obtain lock"
        Log.getLogWriter().info("detected could not obtain lock, will try it again");
        success[0]= false;
      } else if (se.getSQLState().equals("XN008")) { //handles the query server issue
        Log.getLogWriter().info("Query processing has been terminated due to an error on the server");
        cid = -1;  //to indicate derby process problem
      } else if (se.getSQLState().equals("X0Z01") && isHATest){
        Log.getLogWriter().warning("GFXD_NODE_SHUTDOWN happened and need to retry this query");
        success[0] = false;
      } else      SQLHelper.handleSQLException(se);
    }
    */
    return cid;
  }
  
  protected static long getNewTypeCid(ResultSet rs, boolean[] success, Connection conn) {
    long cid = 0;
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
    Log.getLogWriter().info(database + "processing resultset");
    List<Struct> customers = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    if (customers != null) {
      Log.getLogWriter().info(database + "customer size is " +  customers.size());
      success[0] = true;
      cid =  customers.size() > 0? 
          (Long) customers.get(rand.nextInt(customers.size())).get("CID") : 0;
    } else {
      success[0] = false;
    }

    return cid;
  }
  
  //not for uniqkey
  public static int getCid() {
    return rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary))+1;
  }
  
  public static int getExistingCid() {
    int maxCid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    int newCids = 10 * numOfThreads > 100 ? 10 * numOfThreads: 100;
    if (maxCid>newCids) return rand.nextInt(maxCid-newCids)+1;
    else throw new TestException("test issue, not enough cid in the tests yet");
  }
  
  public static int getExistingSid() {
    int maxSid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary);
    int newSids = 10 * numOfThreads > 100 ? 10 * numOfThreads: 100;
    if (maxSid>newSids) return rand.nextInt(maxSid-newSids)+1;
    else throw new TestException("test issue, not enough sid in the tests yet");
  }
  
  //get appropriate cids to be used for insert/update, when testUniqKeys, 
  //conn will be used to find the cid inserted by this tid 
  //if arraySize is greater than avail cid inserted by this thread, it could retrun cid[i] as 0
  protected void getCids(Connection regConn, int[] cids) {
    Connection conn = getAuthConn(regConn);
    int cid =0; 
    int size = cids.length;
    if (testUniqueKeys || testWanUniqueness) { 
      //int n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary)/TestHelper.getNumThreads());
      //Log.getLogWriter().info("n is " + n);
      ResultSet rs = null;
      int whichQuery = 0;
      try {
        rs = TradeCustomersDMLStmt.getQuery(conn, whichQuery, 0, null, getMyTid());
        int count = 0;
        while (rs == null) {
          if (count >= 2) {
            Log.getLogWriter().info("could not get the cids, cids will be 0");
            for (int i=0; i<size; i++) {
              cids[i] = cid; //cid is 0
            }
            return;
          }
          count ++;
          rs = TradeCustomersDMLStmt.getQuery(conn, whichQuery, 0, null, getMyTid());
        }
      } catch (TestException te) {
        if (gfxdtxHANotReady && isHATest && 
            SQLHelper.gotTXNodeFailureTestException(te)) {
          Log.getLogWriter().info ("got expected node failure exception, cids will be 0");
        } else throw te;
      }
      if (rs==null) return; //could not get rs due to HA
      ArrayList<Struct> rsList = (ArrayList<Struct>) ResultSetHelper.
        asList(rs, ResultSetHelper.getStructType(rs), SQLHelper.isDerbyConn(conn));

      SQLHelper.closeResultSet(rs, conn); //for single hop, result set needs to be closed instead of fully consumed
      
      if (rsList==null) return; //if not able to process the resultSet
      int rsSize = rsList.size();
      if (rsSize>=size) {
        int offset = rand.nextInt(rsSize - size +1); //start from a randomly chosen position
        for (int i=0; i<size; i++) {
          cids[i]=((Integer)((GFXDStructImpl) rsList.get(i+offset)).get("CID")).intValue();
        }
      } 
      else {
        for (int i=0; i<rsSize; i++) {
          cids[i]=((Integer)((GFXDStructImpl) rsList.get(i)).get("CID")).intValue();
        }
        for (int i=rsSize; i<size; i++) {
          cids[i] = cid; //cid is 0
        }
      }      
    } 
    else {
      for (int i=0; i<size; i++)
        cids[i] = getCid(); //randomly
    }   
  }
  
  //get appropriate sid to be used for insert, when testUniqKeys
  //only the sid inserted by this tid will be chosen
  //could return sid=0 (non existing in securities table) or any sid inserted by this thread
  protected int getSid(Connection regConn) {
    Connection conn = getAuthConn(regConn);
    int sid =0; 
    int n =0;
    if (testUniqueKeys || isSerial) {
      if (useGfxdConfig) 
        n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary)/numOfThreads);
      else
        n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary)/TestHelper.getNumThreads());
      ResultSet rs;
      String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - ";
      try {
        String s = "select sec_id from trade.securities where tid = " 
          + (isSerial? getRandomTid() : getMyTid());
        rs = conn.createStatement().executeQuery(s);
        Log.getLogWriter().info("executed " + s + " from " + database);
        int temp=0;
        while (rs.next()) {
          if (temp == 0 && rand.nextInt(1000) != 1) sid = rs.getInt("SEC_ID"); 
          
          if (n==temp) {
            sid = rs.getInt("SEC_ID");
            Log.getLogWriter().info("sec_id to be returned is " + sid);
            break;
          }
          temp++;          
        }
        rs.close();
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn,se)) { //handles the "could not obtain lock"
          Log.getLogWriter().info("use sid as 0");
        } else if (se.getSQLState().equals("XN008")) { //handles the query server issue
          Log.getLogWriter().info("Query processing has been terminated due to an error on the server");
        } else if (!SQLHelper.checkGFXDException(conn, se)) {
          Log.getLogWriter().info("use sid as 0");
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          Log.getLogWriter().info("got node failure exception during Tx with HA support, will use sid as 0");
        } else SQLHelper.handleSQLException(se); //throws TestException.

      }      
    } 
    else
      sid = getSid(); //randomly
    
    return sid;  //could return sid =0 
  }
  
  //not for uniqkey
  protected int getSid() {
    return rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary))+1;
  }
  
  //used for update or query needs
  protected int initMaxQty = 2000;
  private Connection dConn;
  private Connection gConn;
  protected int getQty() {
    return rand.nextInt(initMaxQty);
  }  
  
  protected int getQty(int initQty){
    return rand.nextInt(initQty);
  }
  
  //get a price between .01 to 100.00
  protected BigDecimal getPrice() {
    return new BigDecimal (Double.toString((rand.nextInt(10000)+1) * .01));
    //return new BigDecimal (((rand.nextInt(10000)+1) * .01));  //to reproduce bug 39418
  }

  protected String getRandVarChar(int length) {
    int aVal = 'a';
    int sp = ' ';
    int chance = 10;
    int symbolLength = rand.nextInt(length) + 1;
    char[] charArray = new char[symbolLength];
    for (int j = 0; j<symbolLength; j++) {
      if (rand.nextInt(chance) == 1) charArray[j] = (char) sp; //add space in the testing
      else charArray[j] = (char) (rand.nextInt(26) + aVal); //one of the char in a-z
    }
    String randChars = new String(charArray);
    return randChars;
  }

  public static Date getSince() {
    //return new Date ((rand.nextInt(10)+98),rand.nextInt(12), rand.nextInt(31));
    final int month = rand.nextInt(12) + 1;
    final int day;
    // adjust day to be 1-28 for Feb and 1-30 for others
    if (month != 2) {
      day = rand.nextInt(30) + 1;
    }
    else {
      day = rand.nextInt(28) + 1;
    }
    String monthStr = Integer.toString(month);
    String dayStr = Integer.toString(day);
    if (month < 10) {
      monthStr = '0' + monthStr;
    }
    if (day < 10) {
      dayStr = '0' + dayStr;
    }
    return Date.valueOf(Integer.toString(1998 + rand.nextInt(10)) + '-'
        + monthStr + '-' + dayStr);
  }

  public static int getEid(Connection conn) {
    int eid =0; 
    int maxNumOfTries = 1;
    boolean success = false;
    List<Struct> eids = null;
    if (testUniqueKeys || testWanUniqueness) {      
      int count = 0;
      while (!success) {
        try {
          ResultSet rs  = conn.createStatement().executeQuery("select eid from " +
              "emp.employees where tid = " + getMyTid());
          eids = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
          success = true;
        } catch (SQLException se) {
          if (!SQLHelper.checkDerbyException(conn, se)) success = false; //handle lock could not acquire or deadlock
          else if (!SQLHelper.checkGFXDException(conn, se)) success = false; //hand X0Z01 and #41471
          else      SQLHelper.handleSQLException(se);
        } finally {          
          if (count++ >= maxNumOfTries) success =true;
        }        
      }  
      eid = process(eids);
    } 
    else
      eid = getEid(); //randomly
    
    return eid;
  }

  public static int getEid() {
    return rand.nextInt((int) SQLBB.getBB().getSharedCounters().
        read(SQLBB.empEmployeesPrimary))+1;
  }

  protected static int process(List<Struct> eids) {
    if (eids == null || eids.size() ==0) return 0;
    int n = rand.nextInt(eids.size());
    Struct aStruct = eids.get(n);
    return (Integer)aStruct.get("EID");
  }

  protected void rollback(Connection conn) {
    try {
      conn.rollback();
      String db = SQLHelper.isDerbyConn(conn)? "Derby - " : "gemfirexd - " ;
      Log.getLogWriter().info(db + "rollback this operation ");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  protected void commit(Connection conn) {
    try {
      //add the check to see if query cancellation exception or low memory exception thrown
      if (setCriticalHeap) {      
        boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
        if (getCanceled[0] == true && SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("memory runs low in gfxd, rollback the corresponding" +
              " derby ops");
          conn.rollback();
          return;  //do not check exception list if gfxd gets such exception
        }
      }
      
      if (SQLTest.setTx && isHATest) {
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure != null && getNodeFailure[0] && SQLHelper.isDerbyConn(conn)) {
          Log.getLogWriter().info("got node failure exception in gfxd, rollback the corresponding" +
          " derby ops");
          conn.rollback();
          return;  //do not check exception list if gfxd gets such exception
        }
      }
      
      String name = (SQLHelper.isDerbyConn(conn))? "Derby - " : "gemfirexd - ";
      Log.getLogWriter().info(name + "committing the ops");
      conn.commit();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se) 
          && se.getSQLState().equalsIgnoreCase("08003")) {
        Log.getLogWriter().info("detected current connection is lost, possibly due to reade time out");
        return; //add the case when connection is lost due to read timeout
      }
      else
        SQLHelper.handleSQLException(se);
    }
  }
  
  //----- the followings are used for new tx model
  /**
   * find out if the keys modified by this op has confict with other tx
   * @return true if non conflict
   *         false if there is conflict
   */
  @SuppressWarnings("unchecked")
  protected boolean verifyConflict(HashMap<String, Integer> modifiedKeysByOp, 
      HashMap<String, Integer>modifiedKeysByThisTx, SQLException gfxdse,
      boolean getsConflict) {
    //find the keys has been hold by others
    hydra.blackboard.SharedMap modifiedKeysByOtherTx = SQLTxBB.getBB().getSharedMap();    
    SharedMap writeLockedKeysByRRTx = null;
    Map writeLockedKeysByOtherRR = null;
    
    SharedMap readLockedKeysByRRTx = null;
    Map readLockedKeysByOtherRR = null;
    
    int beforeSize = modifiedKeysByOtherTx.size();
    Log.getLogWriter().info("before the op, the tx bb map size is " + beforeSize);
    
    //remove those keys are already held by this tx
    for (String key: modifiedKeysByThisTx.keySet())
      modifiedKeysByOp.remove(key); //only compare the newly acquired keys
    if (modifiedKeysByOp.size() == 0) {     
      if (!getsConflict) {
        Log.getLogWriter().info("this op does not have newly modified keys");
        return true;
      } else {
        throw new TestException("this op does not have newly modified keys, " +
            "but got conflict exception\n" 
            + (gfxdse == null? "" :TestHelper.getStackTrace(gfxdse)));
      }
    }
    
    //verify the conflict
    int mapSize = modifiedKeysByOp.size();
    Log.getLogWriter().info("new modified key map size is " + mapSize);
    HashMap<String, Integer> verifyMap = new HashMap<String, Integer>();
    verifyMap.putAll(modifiedKeysByOp); //duplicate copy of modifiedKeys    
    Map modifiedKeysByOthers = modifiedKeysByOtherTx.getMap();
    verifyMap.putAll(modifiedKeysByOthers); 
    
    //add verify with RR map
    if (mixRR_RC) {
      //for keys write locked by RR 
      writeLockedKeysByRRTx = SQLTxRRWriteBB.getBB().getSharedMap();
      beforeSize +=  writeLockedKeysByRRTx.size();
      writeLockedKeysByOtherRR = writeLockedKeysByRRTx.getMap();
      verifyMap.putAll(writeLockedKeysByOtherRR);
      
      //for keys read locked by RR 
      readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();
      beforeSize +=  readLockedKeysByRRTx.size();
      readLockedKeysByOtherRR = readLockedKeysByRRTx.getMap();
      verifyMap.putAll(readLockedKeysByOtherRR);
    }
    
    int afterSize = verifyMap.size();
    Log.getLogWriter().info("tx bb map size is " + afterSize);
    if (beforeSize + mapSize == afterSize) {
      Log.getLogWriter().info("there is no conflict");
      if (getsConflict) {
        throw new TestException("no conflict detected, but got conflict exception\n" 
            + (gfxdse == null? "" :TestHelper.getStackTrace(gfxdse)));
      } else {
        if (gfxdse == null) {
          modifiedKeysByOtherTx.putAll(modifiedKeysByOp);  
        //add newly modified keys to the held keys, if there is no gemfirexd exception thrown
        }
      }
      return true;
    }
    else {
      /*
      //check for txhistory table (non primary key case)
      for (String key: modifiedKeysByOp.keySet()) {
        if (modifiedKeysByOtherTx.containsKey(key)) {
          if (modifiedKeysByOp.get(key).intValue() == TradeTxHistoryDMLTxStmt.TXHISTORYINSERT
              &&  ((Integer)modifiedKeysByOtherTx.get(key)).intValue() == 
                TradeTxHistoryDMLTxStmt.TXHISTORYINSERT){
            //both are insert for the key, is accepted and not expect to fail  
            Log.getLogWriter().info("Got two tx to insert same in txHisotry, this is " +
                " acceptable for non primary key table, this key is " + key);
          } 
       */
      
      if (!getsConflict) {
        /*
        //TODO -- not sure if we get unique key instead of conflict exception is acceptable
        //for example delete a row, it should hold the unique key, however, another insert/update
        //tries to get the same unique key. is it OK to get 23505 unique constraint?
        //if so, we need to uncomment out the following code

        if (gfxdse != null && gfxdse.getSQLState().equals("23505")) {
          Log.getLogWriter().info("if we get unique key constraint here, but did not " +
             "get conflict exception. this is OK ");
        } */
        //allow check constraint here instead of conflict exception
        //product lock SH lock first, it will promote to EX_SH if check constraint passes
        //only one tx could get a EX_SH for a certain row
        if (gfxdse != null && gfxdse.getSQLState().equals("23513")) {
          Log.getLogWriter().info("if we get check constraint here, but did not " +
          "get conflict exception. this is OK -- continuing test");
          return true;
        } /* whether test needs to handle #49589
          else if (gfxdse != null && gfxdse.getSQLState().equals("23505")) {
          Log.getLogWriter().info("if we get duplicate key constraint here, but did not " +
          "get conflict exception. this is OK -- continuing test");
          //this could occur on update on trade securities unique key columns
          return true;
        } */ else  {
          Log.getLogWriter().info("did not get expected conflict exception",
              gfxdse);
          logConflict(modifiedKeysByOp, modifiedKeysByOthers, writeLockedKeysByOtherRR,
              readLockedKeysByOtherRR);//throws exception
        }
      }
      else Log.getLogWriter().info("got expected conflict by this op");
      return false; //expect conflict and gets the conflict, so return false
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void logConflict(HashMap<String, Integer> modifiedKeys, Map modifiedKeysByOtherTx,
      Map writeLockedKeysByOtherRR, Map readLockedKeysByOtherRR){
    StringBuffer str = new StringBuffer();

    str.append("This tx " + SQLDistTxTest.curTxId.get() + " has modified the following keys " +
        "which are modified by other tx:\n" );
    for (String key: modifiedKeys.keySet()) {
      if (modifiedKeysByOtherTx.containsKey(key)) {
        str.append(key + " is already locked by txId: " + modifiedKeysByOtherTx.get(key) + "\n");
      }
      if (writeLockedKeysByOtherRR != null && writeLockedKeysByOtherRR.containsKey(key)) {
        str.append(key + " is already RR write locked by txId: " + modifiedKeysByOtherTx.get(key) + "\n");
      }
      if (readLockedKeysByOtherRR!= null && readLockedKeysByOtherRR.containsKey(key)) {
        str.append(key + " is already RR read locked by txId: " + modifiedKeysByOtherTx.get(key) + "\n");
      }
    }
    throw new TestException ("Did not get expected conflict exception: " + str.toString());
  }
  
  @SuppressWarnings("unchecked")
  protected String logMissingConflict(HashMap<String, Integer> modifiedKeys, Map modifiedKeysByOtherTx){
    StringBuffer str = new StringBuffer();

    str.append("This tx " + SQLDistTxTest.curTxId.get() + " has modified the following keys " +
        "which are modified by other tx:\n" );
    for (String key: modifiedKeys.keySet()) {
      if (modifiedKeysByOtherTx.containsKey(key)) {
        str.append(key + " is already locked by txId: " + modifiedKeysByOtherTx.get(key) + "\n");
      }
    }
    Log.getLogWriter().info(str.toString());
    return str.toString();
  }
  
  @SuppressWarnings("unchecked")
  protected void logMissingConflictThrowException(HashMap<String, Integer> modifiedKeys, Map modifiedKeysByOtherTx){
    String str = logMissingConflict(modifiedKeys, modifiedKeysByOtherTx);
    throw new TestException ("Did not get expected conflict exception: " + str);
  }

  
  @SuppressWarnings("unchecked")
  protected boolean hasForeignKeyConflict(String key, int txId) {
    if (!batchingWithSecondaryData) {
      hydra.blackboard.SharedMap modifiedKeysByRCTx = SQLTxBB.getBB().getSharedMap();
      //add checking that were modified using RR as well
      hydra.blackboard.SharedMap modifiedKeysByRRTx =SQLTxRRWriteBB.getBB().getSharedMap();
      
      Map modifiedKeysByAllTx = modifiedKeysByRCTx.getMap();
      modifiedKeysByAllTx.putAll(modifiedKeysByRRTx.getMap()); //should not have same key in two maps if mixed RC and RR
      
      Integer holdingTxId = (Integer) modifiedKeysByAllTx.get(key);
      if (holdingTxId == null || holdingTxId == txId) return false; //no one holds the key or current tx holds the key
      else {
        Log.getLogWriter().info("the key " + key + " is being held by the txId: " + holdingTxId);
        return true;
      }
    } else {
      //TODO need to add RR map as well for RR test later
      hydra.blackboard.SharedMap modifiedKeysByRCTx = SQLTxBatchingBB.getBB().getSharedMap();
      
      Map modifiedKeysByAllTx = modifiedKeysByRCTx.getMap();
      
      ArrayList<Integer> holdingTxIds = (ArrayList<Integer>)modifiedKeysByAllTx.get(key);
      if (holdingTxIds == null || (holdingTxIds.size() == 1 && holdingTxIds.contains(txId))) return false; //no one holds the key or current tx holds the key
      else {
        Log.getLogWriter().info("the key " + key + " is being held by the txId: " + holdingTxIds.toString());
        return true;
      }
    }
  }
  
  
  //could get 0 as sec_id, used in tx
  protected void getExistingSidFromSecurities(Connection conn, int[] sec_id) {    
    if (testUniqueKeys)  {
      for (int i=0; i<sec_id.length; i++) sec_id[i] = getSid(conn);
      return;
    }
    
    List<Struct> list = null;
    String sql = "select sec_id from trade.securities";
    try {
      ResultSet rs = conn.createStatement().executeQuery(sql);
      list = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    if (list == null || list.size() == 0) {
      sec_id[0] = 0;
      Log.getLogWriter().info("could not get valid sec_id, using 0 instead");
    }
    else sec_id[0] =  (Integer)(list.get(rand.nextInt(list.size()))).get("SEC_ID");

  }
  

  //could get 0 as cid, used in tx
  protected void getExistingCidFromCustomers(Connection conn, int[] cid) {
    if (testUniqueKeys)  {
      getCids(conn, cid);
      return;
    }
    
    List<Struct> list = null;
    String sql = "select cid from trade.customers";
    try {
      ResultSet rs = conn.createStatement().executeQuery(sql);
      list = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    if (list == null || list.size() == 0) {
      cid[0] = 0;
      Log.getLogWriter().info("could not get valid cid, using 0 instead");
    }
    else cid[0] =  (Integer)(list.get(rand.nextInt(list.size()))).get("CID");

  }
  
  @SuppressWarnings("unchecked")
  protected boolean verifyConflictForRR(HashMap<String, Integer> modifiedKeysByOp, 
      HashMap<String, Integer>modifiedKeysByThisTx, SQLException gfxdse,
      boolean getsConflict) {
    //for keys write locked by RR 
    SharedMap writeLockedKeysByRRTx = SQLTxRRWriteBB.getBB().getSharedMap();
    Map writeLockedKeysByOtherRR = null;
    
    //find the keys has been hold by RC
    hydra.blackboard.SharedMap modifiedKeysByOtherRCTx = null;    
    Map modifiedKeysByOtherRC = null;
  
    
    int beforeSize = writeLockedKeysByRRTx.size();
    Log.getLogWriter().info("before the op, the tx bb map size for RR write is " + beforeSize);
    
    //remove those keys are already held by this tx
    for (String key: modifiedKeysByThisTx.keySet())
      modifiedKeysByOp.remove(key); //only compare the newly acquired keys
    
    if (modifiedKeysByOp.size() == 0) {
      Log.getLogWriter().info("this op does not have newly modified keys");
      return true;
    }
    
    //verify the conflict
    int mapSize = modifiedKeysByOp.size();
    Log.getLogWriter().info("newly modified key map size is " + mapSize);
    HashMap<String, Integer> verifyMap = new HashMap<String, Integer>();
    verifyMap.putAll(modifiedKeysByOp); //duplicate copy of modifiedKeys    
    writeLockedKeysByOtherRR = writeLockedKeysByRRTx.getMap();
    verifyMap.putAll(writeLockedKeysByOtherRR); 
    
    //add verify with RC map
    if (mixRR_RC) {
      //find the keys has been hold by others
      modifiedKeysByOtherRCTx = SQLTxBB.getBB().getSharedMap();  
      
      beforeSize +=  modifiedKeysByOtherRCTx.size();
      modifiedKeysByOtherRC = modifiedKeysByOtherRCTx.getMap();
      verifyMap.putAll(modifiedKeysByOtherRC);
    }
    
    int afterSize = verifyMap.size();
    Log.getLogWriter().info("tx bb map size is " + afterSize);
    if (beforeSize + mapSize == afterSize) {
      Log.getLogWriter().info("there is no conflict");
      if (getsConflict) {
        throw new TestException("no conflict detected, but got conflict exception\n" 
            + (gfxdse == null? "" :TestHelper.getStackTrace(gfxdse)));
      } else {
        if (gfxdse == null) {
          writeLockedKeysByRRTx.putAll(modifiedKeysByOp);  
        //add newly modified keys to the held keys, if there is no gemfirexd exception thrown
        }
      }
      return true;
    }
    else {
      /*
      //check for txhistory table (non primary key case)
      for (String key: modifiedKeysByOp.keySet()) {
        if (modifiedKeysByOtherTx.containsKey(key)) {
          if (modifiedKeysByOp.get(key).intValue() == TradeTxHistoryDMLTxStmt.TXHISTORYINSERT
              &&  ((Integer)modifiedKeysByOtherTx.get(key)).intValue() == 
                TradeTxHistoryDMLTxStmt.TXHISTORYINSERT){
            //both are insert for the key, is accepted and not expect to fail  
            Log.getLogWriter().info("Got two tx to insert same in txHisotry, this is " +
                " acceptable for non primary key table, this key is " + key);
          } 
       */
      
      if (!getsConflict) {
        /*
        //TODO -- not sure if we get unique key instead of conflict exception is acceptable
        //for example delete a row, it should hold the unique key, however, another insert/update
        //tries to get the same unique key. is it OK to get 23505 unique constraint?
        //if so, we need to uncomment out the following code

        if (gfxdse != null && gfxdse.getSQLState().equals("23505")) {
          Log.getLogWriter().info("if we get unique key constraint here, but did not " +
             "get conflict exception. this is OK ");
        } */
        //allow check constraint here instead of conflict exception
        //product lock SH lock first, it will promote to EX_SH if check constraint passes
        //only one tx could get a EX_SH for a certain row
        if (gfxdse != null && gfxdse.getSQLState().equals("23513")) {
          Log.getLogWriter().info("if we get check constraint here, but did not " +
          "get conflict exception. this is OK -- continuing test");
          return true;
        } else  {
          Log.getLogWriter().info("did not get expected conflict exception",
              gfxdse);
          logConflictForRR(modifiedKeysByOp, writeLockedKeysByOtherRR, 
              modifiedKeysByOtherRC);//throws exception
        }
      }
      else Log.getLogWriter().info("got expected conflict by this op");
      return false; //expect conflict and gets the conflict, so return false
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void logConflictForRR(HashMap<String, Integer> modifiedKeys, Map writeLockedKeysByOtherRR,
      Map modifiedKeysByOtherRC){
    StringBuffer str = new StringBuffer();

    str.append("This tx " + SQLDistTxTest.curTxId.get() + " has modified the following keys " +
        "which are modified by other tx:\n" );
    for (String key: modifiedKeys.keySet()) {
      if (writeLockedKeysByOtherRR.containsKey(key)) {
        str.append(key + " is already locked by txId: " + writeLockedKeysByOtherRR.get(key) + "\n");
      }
      if (modifiedKeysByOtherRC != null && modifiedKeysByOtherRC.containsKey(key)) {
        str.append(key + " is already RR write locked by txId: " + modifiedKeysByOtherRC.get(key) + "\n");
      }
    }
    throw new TestException ("Did not get expected conflict exception: " + str.toString());
  }
  
  @SuppressWarnings("unchecked")
  protected void verifyConflictWithBatching(HashMap<String, Integer> modifiedKeysByOp, 
      HashMap<String, Integer>modifiedKeysByThisTx, SQLException gfxdse,
      boolean hasSecondary, boolean getsConflict) {
    //find the keys has been hold by others
    long startTime = System.currentTimeMillis();
    Log.getLogWriter().info("start time" + startTime);
    hydra.blackboard.SharedMap modifiedKeysByOtherTx = SQLTxBatchingBB.getBB().getSharedMap();    
    SharedMap writeLockedKeysByRRTx = null;
    Map writeLockedKeysByOtherRR = null;
    
    SharedMap readLockedKeysByRRTx = null;
    Map readLockedKeysByOtherRR = null;
    
    int beforeSize = modifiedKeysByOtherTx.size();
    Log.getLogWriter().info("before the op, the tx bb map size is " + beforeSize);
    
    //remove those keys are already held by this tx
    for (String key: modifiedKeysByThisTx.keySet())
      modifiedKeysByOp.remove(key); //only compare the newly acquired keys
    
    Log.getLogWriter().info("key loop takes " + (System.currentTimeMillis() - startTime));
     
    if (modifiedKeysByOp.size() == 0) {     
      if (!getsConflict) {
        Log.getLogWriter().info("this op does not have newly modified keys");
        logTime(startTime);
        return;
      } else if (expectBatchingConflict(modifiedKeysByOtherTx, modifiedKeysByThisTx)) {        
        Log.getLogWriter().info("get expected conflict due to batching");
        logTime(startTime);
        return;
      } else if (verifyBatchingFKConflict(modifiedKeysByOtherTx.getMap())) {
        Log.getLogWriter().info("get expected the conflict due to foreign key hold conflcit");
        logTime(startTime); //conflict could be caused by foreign key constraint
        return;
      } else {
        throw new TestException("this op does not have newly modified keys, " +
            "but got conflict exception\n" 
            + (gfxdse == null? "" :TestHelper.getStackTrace(gfxdse)));
      }
    }

    
    //verify the conflict
    int mapSize = modifiedKeysByOp.size();
    Log.getLogWriter().info("new modified key map size is " + mapSize);
    HashMap<String, Integer> verifyMap = new HashMap<String, Integer>();
    verifyMap.putAll(modifiedKeysByOp); //duplicate copy of modifiedKeys    
    Map modifiedKeysByOthers = modifiedKeysByOtherTx.getMap();
    verifyMap.putAll(modifiedKeysByOthers); 
    
    //add verify with RR map
    if (mixRR_RC) {
      //for keys write locked by RR 
      writeLockedKeysByRRTx = SQLTxRRWriteBB.getBB().getSharedMap();
      beforeSize +=  writeLockedKeysByRRTx.size();
      writeLockedKeysByOtherRR = writeLockedKeysByRRTx.getMap();
      verifyMap.putAll(writeLockedKeysByOtherRR);
      
      //for keys read locked by RR 
      readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();
      beforeSize +=  readLockedKeysByRRTx.size();
      readLockedKeysByOtherRR = readLockedKeysByRRTx.getMap();
      verifyMap.putAll(readLockedKeysByOtherRR);
    }

    int afterSize = verifyMap.size();
    Log.getLogWriter().info("tx bb map size is " + afterSize);
    if (beforeSize + mapSize == afterSize) {
      Log.getLogWriter().info("there is no conflict");
      if (getsConflict) {
        if (verifyBatchingFKConflict(modifiedKeysByOthers)) {
          Log.getLogWriter().info("get expected the conflict due to foreign key hold conflcit");
          return;
        } else if (expectBatchingConflict(modifiedKeysByOtherTx, modifiedKeysByThisTx)) {
          Log.getLogWriter().info("get expected conflict due to batching");
          return;
        } else {
          throw new TestException("no conflict detected, but got conflict exception\n" 
              + (gfxdse == null? "" :TestHelper.getStackTrace(gfxdse)));
        }
      } else {
        if (gfxdse == null) {
          for (String key: modifiedKeysByOp.keySet()) {
            ArrayList<Integer> list = null;
            if (modifiedKeysByOtherTx.containsKey(key)) {      
              list = (ArrayList<Integer>) modifiedKeysByOtherTx.get(key);
              if (list.size() != 0) {
                Log.getLogWriter().info("for key: " + key + ", there are following other txId hold the" +
                    " lock locally: " + list.toString());
              }
            } else {
              list = new ArrayList<Integer>();
            }
            Log.getLogWriter().info("this tx holds the following key " + key);
            list.add(modifiedKeysByOp.get(key));
            
            modifiedKeysByOtherTx.put(key, list);  
            //add newly modified keys to the held keys, if there is no gemfirexd exception thrown
          }
        }
      }
      //return true;
    }
    else {
      /*
      //check for txhistory table (non primary key case)
      for (String key: modifiedKeysByOp.keySet()) {
        if (modifiedKeysByOtherTx.containsKey(key)) {
          if (modifiedKeysByOp.get(key).intValue() == TradeTxHistoryDMLTxStmt.TXHISTORYINSERT
              &&  ((Integer)modifiedKeysByOtherTx.get(key)).intValue() == 
                TradeTxHistoryDMLTxStmt.TXHISTORYINSERT){
            //both are insert for the key, is accepted and not expect to fail  
            Log.getLogWriter().info("Got two tx to insert same in txHisotry, this is " +
                " acceptable for non primary key table, this key is " + key);
          } 
       */
      
      if (!getsConflict) {
        /*
        //TODO -- not sure if we get unique key instead of conflict exception is acceptable
        //for example delete a row, it should hold the unique key, however, another insert/update
        //tries to get the same unique key. is it OK to get 23505 unique constraint?
        //if so, we need to uncomment out the following code

        if (gfxdse != null && gfxdse.getSQLState().equals("23505")) {
          Log.getLogWriter().info("if we get unique key constraint here, but did not " +
             "get conflict exception. this is OK ");
        } */
        //allow check constraint here instead of conflict exception
        //product lock SH lock first, it will promote to EX_SH if check constraint passes
        //only one tx could get a EX_SH for a certain row
        if (gfxdse != null) {
          if (gfxdse.getSQLState().equals("23513") || gfxdse.getSQLState().equals("23505")
              || gfxdse.getSQLState().equals("23503")) {
            Log.getLogWriter().info("if we get constraint violation here, but did not " +
            "get conflict exception. this is OK -- " +
            "as conflict exception may not be thrown due to batching");
          } else SQLHelper.handleSQLException(gfxdse);
        } else  {
          Log.getLogWriter().info("did not get expected conflict exception",
              gfxdse);
          for (String key: modifiedKeysByOp.keySet()) {
            ArrayList<Integer> list = null;
            if (modifiedKeysByOtherTx.containsKey(key)) {
              list = (ArrayList<Integer>) modifiedKeysByOtherTx.get(key);
              Log.getLogWriter().info("for key: " + key + ", there are following other txId hold the" +
                  " lock locally: " + list.toString());
              if (!hasSecondary && list.size() > 0) throw new TestException("does not detect conflict " +
              		"exception for a table without secondary data, the lock for " +
              		"the key:" + key + " was hold by following txIds " + list.toString());
            } else {
              list = new ArrayList<Integer>();
            }
            list.add(modifiedKeysByOp.get(key));
            modifiedKeysByOtherTx.put(key, list);  
            //add newly modified keys to the held keys, if there is no gemfirexd exception thrown
          }
          
        }
        //return true;
      }
      else Log.getLogWriter().info("got expected conflict by this op");
      //return false; //expect conflict and gets the conflict, so return false
    }
    
    logTime(startTime);
  }
  
  protected void logTime(long startTime) {
    Log.getLogWriter().info("verification takes " + (System.currentTimeMillis() - startTime));
  }
  
  //test to see if there is another tx possibly hold a key which may cause conflict during batching
  @SuppressWarnings("unchecked")
  protected boolean expectBatchingConflict(SharedMap modifiedKeysByAllTx, HashMap<String, Integer> modifiedKeysByThisTx ) {        
    Integer myTxId = (Integer) SQLDistTxTest.curTxId.get(); 
    
    for (String key: modifiedKeysByThisTx.keySet()) {
      ArrayList<Integer> txIdsForTheKey = ( ArrayList<Integer>) modifiedKeysByAllTx.get(key);
      if (txIdsForTheKey.contains(myTxId) && txIdsForTheKey.size() > 1) {
        return true;
      }
    }
    return false;
  }
  
  //test to see if there is another tx possibly hold a key which may cause 
  //foreign key conflict during batching
  @SuppressWarnings("unchecked")
  protected boolean verifyBatchingFKConflict(Map modifiedKeysByAllTx) {         
    //check all child rows inserted by this txn whether will cause foreign key conflict
    Integer myTxId = (Integer) SQLDistTxTest.curTxId.get(); 
    HashSet<String> holdFKsByThisTx = (HashSet<String>) SQLDistTxTest.foreignKeyHeldWithBatching.get();
    
    for (String key: holdFKsByThisTx) {
      ArrayList<Integer> txIdsForTheKey = ( ArrayList<Integer>) modifiedKeysByAllTx.get(key);
      if (txIdsForTheKey != null) {
        if ((!txIdsForTheKey.contains(myTxId) && txIdsForTheKey.size() == 1) || txIdsForTheKey.size() > 1) {      
          Log.getLogWriter().info("could get conflict for foreign key " + key 
              + " with txIds: " + txIdsForTheKey);
          return true;
        }
      }
    }
    
    //check whether all parent row lock hold by this tx will cause foreign key conflict hold by other txIds in batching
    hydra.blackboard.SharedMap holdingFKTxIds = SQLTxBatchingFKBB.getBB().getSharedMap();
    HashSet<String> holdParentKeyByThisTx = (HashSet<String>) SQLDistTxTest.parentKeyHeldWithBatching.get();
    for (String key: holdParentKeyByThisTx) {
      HashSet<Integer> txIds = (HashSet<Integer>) holdingFKTxIds.get(key);
      if (txIds != null) {
        if (txIds.size() > 1 || (txIds.size() == 1 && !txIds.contains(myTxId))){
          Log.getLogWriter().info("could get conflict for foreign key " + key 
              + " with txIds: " + txIds);
          return true;
        }  
      }
    }   
    return false;
  }
  
  public Clob[] getClob(int size ) {
    boolean isTicket47987Fixed = false; //make regression still has the necessary coverage
    Clob[] profile = new Clob[size];
    //int maxClobSize = ticket46799fixed? 10000: 100;
    int maxClobSize = 10000;
    int specialClobSize = 2000000;
    try {
      for (int i = 0 ; i <size ; i++) { 
        if (rand.nextBoolean() || noNullClob) {  
          if (ticket46799fixed) {
            char[] chars = (rand.nextInt(10) != 0) ? 
                getAsciiCharArray(rand.nextInt(maxClobSize) + 1) :
                getCharArray(rand.nextInt(maxClobSize) + 1);
            
            //get a large sized clob 
            if (rand.nextInt(100) == 1) {
              Log.getLogWriter().info("clob length is " + specialClobSize);
              chars = getCharArray(specialClobSize);    
            }
               
            profile[i] = new SerialClob(chars); 
          } else {
            if (rand.nextInt(200) == 1 && isTicket49338Fixed) {
              Log.getLogWriter().info("clob length is " + specialClobSize);
              profile[i] = new SerialClob(getRandVarChar(specialClobSize).toCharArray()); 
            } else
            profile[i] = new SerialClob(getRandVarChar(rand.nextInt(maxClobSize) + 1).toCharArray());
          }
        } else if (rand.nextInt(10) == 0 && isTicket47987Fixed){
           char[] chars =  new char[0];
           profile[i] = new SerialClob(chars); 
        } //remaining are null profile 
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }  
    return profile;
  } 
  
  protected char[] getCharArray(int length) {
    /*
    int cp1 = 56558;
    char c1 = (char)cp1;
    char ch[];
    ch = Character.toChars(cp1);
    
    c1 = '\u01DB';
    char c2 = '\u0908';

    Log.getLogWriter().info("c1's UTF-16 representation is is " + c1);
    Log.getLogWriter().info("c2's UTF-16 representation is is " + c2); 
    Log.getLogWriter().info(cp1 + "'s UTF-16 representation is is " + ch[0]); 
    */
    int arrayLength = rand.nextInt(length) + 1;
    char[] charArray = new char[arrayLength];
    for (int j = 0; j<arrayLength; j++) {      
      charArray[j] = getValidChar(); 
    }
    return charArray;
  }
  
  protected char[] getAsciiCharArray(int length) {
    int arrayLength = rand.nextInt(length) + 1;
    char[] charArray = new char[arrayLength];
    for (int j = 0; j<arrayLength; j++) {      
      charArray[j] = (char)(rand.nextInt(128)); 
    }
    return charArray;
  }
  
  protected byte[] getByteArray(int maxLength) {
    int arrayLength = rand.nextInt(maxLength) + 1;
    byte[] byteArray = new byte[arrayLength];
    for (int j = 0; j<arrayLength; j++) {
      byteArray[j] = (byte) (rand.nextInt(256)); 
    }
    return byteArray;
  }
  
  protected byte[] getMaxByteArray(int maxLength) {
    Log.getLogWriter().info("max length byte array has length: " + maxLength);
    int arrayLength = maxLength;
    byte[] byteArray = new byte[arrayLength];
    for (int j = 0; j<arrayLength; j++) {
      byteArray[j] = (byte) (rand.nextInt(256)); 
    }
    return byteArray;
  }
  
  protected String getStringFromClob(Clob profile) throws SQLException{
    String clob = null;
    if (profile != null) {
      if (profile.length() == 0) clob = "empty";
      else {
        BufferedReader reader = new BufferedReader(profile.getCharacterStream());
        clob = ResultSetHelper.convertCharArrayToString(
            reader, (int)profile.length());
        try {
          reader.close();
        } catch (IOException e) {
          throw new TestException("could not close the BufferedReader" + 
              TestHelper.getStackTrace(e));
        }
      }          
    }
    return clob;
  }
  
  protected char getValidChar() {

    //TODO, to add other valid unicode characters
    return (char)(rand.nextInt('\u0527'));
  }
  
  //
  protected void checkTicket49605(Connection dConn, Connection gConn, String tableName) {
    try {
      String select = "select * from trade." + tableName + " where tid = " + getMyTid();
      //use base table scan to check the row is in the table or not.
      Log.getLogWriter().info("Checking ticket #49605 by table scan, derby executing query: " + select);
      ResultSet derbyrs = dConn.createStatement().executeQuery(select);
      Log.getLogWriter().info("Checking ticket #49605 by table scan, gfxd executing query: " + select);
      ResultSet gfxdrs = gConn.createStatement().executeQuery(select);
      ResultSetHelper.compareResultSets(derbyrs, gfxdrs);
      Log.getLogWriter().info("does not get result set mismatch -- comparison could be aborted," +
          " continue testing");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }   
  }
  
  protected void checkTicket49605(Connection dConn, Connection gConn, String tableName, int pk,
      int pk2, String symbol, String exchange) {
    try {
      String sql = null;
      if (tableName.contains("orders")) {
        sql = "select * from trade." + tableName + " where oid = " + pk;
      } else if (tableName.contains("customers") || tableName.contains("networth")){
        sql = "select * from trade." + tableName + " where cid = " + pk;
      } else if (tableName.contains("portfolio")){
        sql = "select * from trade." + tableName + " where cid = " + pk + " and sid = " + pk2 ;
      } else if (tableName.contains("companies")){
        sql = "select * from trade." + tableName + " where symbol = '" + symbol + "' and exchange = '" + exchange + "'";
      } else if (tableName.contains("securities")){
        sql = "select * from trade." + tableName + " where sec_id = " + pk;
      }
      ResultSet rs = gConn.createStatement().executeQuery(sql);
      Log.getLogWriter().info(sql);
      Log.getLogWriter().info("got results from get convertable: " + 
          ResultSetHelper.listToString(ResultSetHelper.asList(rs, false)));
      
      String select = "select * from trade." + tableName + " where tid = " + getMyTid();
      //use base table scan to check the row is in the table or not.
      Log.getLogWriter().info("Checking ticket #49605 by table scan, derby executing query: " + select);
      ResultSet derbyrs = dConn.createStatement().executeQuery(select);
      Log.getLogWriter().info("Checking ticket #49605 by table scan, gfxd executing query: " + select);
      ResultSet gfxdrs = gConn.createStatement().executeQuery(select);
      ResultSetHelper.compareResultSets(derbyrs, gfxdrs);
      Log.getLogWriter().info("does not get result set mismatch -- comparison could be aborted," +
          " continue testing");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }   
  }
  
  protected void deleteRow(Connection dConn, Connection gConn, String tableName, int pk, 
      int pk2, String symbol, String exchange) {
    try {
      String sql = null;
      if (tableName.contains("orders")) {
        sql = "delete from trade." + tableName + " where oid = " + pk;
      } else if (tableName.contains("customers") || tableName.contains("networth")){
        sql = "delete from trade." + tableName + " where cid = " + pk;
      } else if (tableName.contains("portfolio")){
        sql = "delete from trade." + tableName + " where cid = " + pk + " and sid = " + pk2 ;
      } else if (tableName.contains("companies")){
        sql = "delete from trade." + tableName + "where symbol = '" + symbol + "' and exchange = '" + exchange + "'";
      } else if (tableName.contains("securities")){
        sql = "delete from trade." + tableName + " where sec_id = " + pk;
      }
      int dcount = dConn.createStatement().executeUpdate(sql);
      Log.getLogWriter().info("derby executed " + sql);
      
      int gcount = gConn.createStatement().executeUpdate(sql);
      Log.getLogWriter().info("gfxd executed " + sql);
      
      if (dcount != gcount) {
        Log.getLogWriter().info("derby deleted row but gfxd does not");
        checkTicket49605(dConn, gConn, tableName, pk, pk2, symbol, exchange);
      }

    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }   
  }
  
  @SuppressWarnings("unchecked")
  protected boolean isCustomersPartitionedOnPKOrReplicate() {
    if (!testPartionBy) return true; //default partitioned on pk in tx, no global index created
    else {
      ArrayList<String> partitionKey = (ArrayList<String>)partitionMap.get("customersPartition");
      return ((partitionKey.size() == 1 && partitionKey.contains("cid")) || partitionKey.size() == 0);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected boolean isSecuritiesPartitionedOnPKOrReplicate() {
    if (!testPartionBy) return true; //default partitioned on pk in tx, no global index created
    else {
      ArrayList<String> partitionKey = (ArrayList<String>)partitionMap.get("securitiesPartition");
      return ((partitionKey.size() == 1 && partitionKey.contains("sec_id")) || partitionKey.size() == 0);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void removeTxIdFromBatchingFKs(HashSet<String>fks) {
    Integer myTxId = (Integer) SQLDistTxTest.curTxId.get(); 
    hydra.blackboard.SharedMap holdingFKTxIds = SQLTxBatchingFKBB.getBB().getSharedMap();
    for (String fk: fks) {
      HashSet<Integer> txIds = (HashSet<Integer>) holdingFKTxIds.get(fk);
      Log.getLogWriter().info("holdingFKTxIds removed myTxId: " + myTxId +
          " for the this foreing key: " + fk);
      txIds.remove(myTxId);
      holdingFKTxIds.put(fk, txIds);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void cleanUpFKHolds() {
    //foreign key holds
    HashSet<String> fks = (HashSet<String>)SQLDistTxTest.foreignKeyHeldWithBatching.get();
    removeTxIdFromBatchingFKs(fks); //clean up the holding txId from the batchingFKBB
    fks.clear();
    SQLDistTxTest.foreignKeyHeldWithBatching.set(fks); //reset the fk hold set
    
    //parent key holds
    HashSet<String> parentKeys = (HashSet<String>)SQLDistTxTest.parentKeyHeldWithBatching.get();
    parentKeys.clear();
    SQLDistTxTest.parentKeyHeldWithBatching.set(parentKeys);
  }
  
  //----- the followings are used for new tx model
  /**
   * find out if the keys modified by this op has confict with other tx
   * @return true if verification succeed 
   *         false if verfication failed
   */
  protected boolean verifyConflictNewTables(HashMap<String, Integer> modifiedKeysByOp, 
      HashMap<String, Integer>modifiedKeysByThisTx, 
      HashMap<String, Integer> holdNonDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdNonDeleteBlockingKeysByThisTx,
      HashMap<String, Integer> holdDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdDeleteBlockingKeysByThisTx,
      HashMap<String, Integer> holdForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdForeignKeysByThisTx, 
      HashMap<String, Integer> holdNewForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdNewForeignKeysByThisTx, 
      SQLException gfxdse,
      boolean getsConflict) {
    
    //find the keys has been hold by others
    hydra.blackboard.SharedMap modifiedKeysByOtherTx = SQLTxBB.getBB().getSharedMap(); 
    hydra.blackboard.SharedMap holdBlockingKeysByOtherTx = SQLTxHoldKeysBlockingChildBB.getBB().getSharedMap(); 
    //due to #42672, some of the updates on parent could cause conflict with child table dml ops
    hydra.blackboard.SharedMap holdDeleteBlockingKeysByOtherTx = SQLTxDeleteHoldKeysBlockingChildBB.getBB().getSharedMap(); 
    //due to #50560, delete will gets conflict on current held rows
    hydra.blackboard.SharedMap holdForeignKeysByOtherTx = SQLTxHoldForeignKeysBB.getBB().getSharedMap(); 
    //for RC txn only, for RR txn use RRReadBB instead
    hydra.blackboard.SharedMap holdNewForeignKeysByOtherTx = SQLTxHoldNewForeignKeysBB.getBB().getSharedMap(); 
    //seperate newly acquried foreign vs existing fk of the row -- see #50560
    
    /* based on #49771, we need to discuss if mixed RR and RC will be coverred
     * a few issues like foreign key hold treat as read locked key, but it fails
     * at op time when using RC isolation, how this affects
     * verification of the locks? In any case, this will be moved to a new method 
     * to handle mix RC and RR
    SharedMap writeLockedKeysByRRTx = null;
    Map writeLockedKeysByOtherRR = null;
    
    SharedMap readLockedKeysByRRTx = null;
    Map readLockedKeysByOtherRR = null;
    */
    
    if (getsConflict) {
      //first check if conflict could be caused by primary/unique key held.
      if (verifyModifiedKeysConflict(modifiedKeysByOp,
        modifiedKeysByThisTx, modifiedKeysByOtherTx, gfxdse, getsConflict)) {
        //no need to add this op into total tx holding keys as the op failed due to conflict exception
        return true;
      }
      else {
        if (expectForeignKeysConflict(holdNonDeleteBlockingKeysByOp, 
            holdNonDeleteBlockingKeysByThisTx, holdBlockingKeysByOtherTx,
            holdDeleteBlockingKeysByOp, 
            holdDeleteBlockingKeysByThisTx, holdDeleteBlockingKeysByOtherTx,
            holdForeignKeysByOp, holdForeignKeysByThisTx, holdForeignKeysByOtherTx, 
            holdNewForeignKeysByOp, holdNewForeignKeysByThisTx, holdNewForeignKeysByOtherTx,
            gfxdse, getsConflict)) {
          //no need to add this op into total tx holding keys as the op failed due to conflict exception
          return true;
        }
        else {
          throw new TestException("Does not expect conflict exception but gets it." +
              TestHelper.getStackTrace(gfxdse));       
        }
      } 
    } else {
    //allow check constraint here instead of conflict exception
      //product lock SH lock first, it will promote to EX_SH if check constraint passes
      //only one tx could get a EX_SH for a certain row
      if (gfxdse != null && gfxdse.getSQLState().equals("23513")) {
        //no need to add this op into total tx holding keys as the op failed due to check constraint exception
        Log.getLogWriter().info("if we get check constraint here, but did not " +
        "get conflict exception. this is OK -- continuing test");
        return true;
      } else {
        if (verifyModifiedKeysConflict(modifiedKeysByOp,
            modifiedKeysByThisTx, modifiedKeysByOtherTx, gfxdse, getsConflict)) {          
          if (!expectForeignKeysConflict(holdNonDeleteBlockingKeysByOp, holdNonDeleteBlockingKeysByThisTx, 
              holdBlockingKeysByOtherTx, holdDeleteBlockingKeysByOp, 
              holdDeleteBlockingKeysByThisTx, holdDeleteBlockingKeysByOtherTx,
              holdForeignKeysByOp, holdForeignKeysByThisTx, holdForeignKeysByOtherTx, 
              holdNewForeignKeysByOp, holdNewForeignKeysByThisTx, holdNewForeignKeysByOtherTx,
              gfxdse, getsConflict)) {

            addLockingKeysToBB(modifiedKeysByOp,
                modifiedKeysByThisTx, 
                modifiedKeysByOtherTx,
                holdNonDeleteBlockingKeysByOp, 
                holdNonDeleteBlockingKeysByThisTx, 
                holdBlockingKeysByOtherTx,
                holdDeleteBlockingKeysByOp, 
                holdDeleteBlockingKeysByThisTx, 
                holdDeleteBlockingKeysByOtherTx,
                holdForeignKeysByOp, 
                holdForeignKeysByThisTx, 
                holdForeignKeysByOtherTx, 
                holdNewForeignKeysByOp, 
                holdNewForeignKeysByThisTx, 
                holdNewForeignKeysByOtherTx);
            return true;
          } else {
            if (gfxdse == null) throw new TestException ("Expected conflict, but does not get it");
            else {
              if (gfxdse.getSQLState().equalsIgnoreCase("23503")) {
                Log.getLogWriter().info("Got foreign key constraint violation 23503" +
                		" instead of conflict exception, this is possible if delete" +
                		" deletes more than one rows and deleting some of rows could get 23503");
                return false;
              } else {
                Log.getLogWriter().warning("need to check if the exception is allowed here instead of conflict exception");
                throw new TestException ("Expected conflict, but get other exception: " + TestHelper.getStackTrace(gfxdse));
              }
            }
          }
        } else {
          if (gfxdse == null) throw new TestException ("Expected conflict, but does not get it");
          else if (gfxdse.getSQLState().equalsIgnoreCase("23503")) {
            Log.getLogWriter().info("Got foreign key constraint violation 23503" +
                " instead of conflict exception, this is possible if delete" +
                " deletes more than one rows and deleting some of rows could get 23503");
            //no need to add this op into total tx holding keys as the op failed due to fk constraint exception
            return false;
          } else
          throw new TestException ("Expected conflict, but does not get it"); 
        }
      }
      
    }
  }
  
  //----- the followings are used for new tx model
  /**
   * find out if the keys modified by this op has confict with other tx
   * @return true if verification succeed 
   *         false if verfication failed
   */
  @SuppressWarnings("unchecked")
  protected boolean verifyConflictNewTables(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> holdNonDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdForeignKeysByOp,  
      HashMap<String, Integer> holdNewForeignKeysByOp,  
      SQLException gfxdse,
      boolean getsConflict) {
    
    /* based on #49771, we need to discuss if mixed RR and RC will be coverred
     * a few issues like foreign key hold treat as read locked key, but it fails
     * at op time when using RC isolation, how this affects
     * verification of the locks? In any case, this will be moved to a new method 
     * to handle mix RC and RR
    SharedMap writeLockedKeysByRRTx = null;
    Map writeLockedKeysByOtherRR = null;
    
    SharedMap readLockedKeysByRRTx = null;
    Map readLockedKeysByOtherRR = null;
    */
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
    
    //find the keys has been hold by others
    hydra.blackboard.SharedMap modifiedKeysByOtherTx = SQLTxBB.getBB().getSharedMap(); 
    hydra.blackboard.SharedMap holdBlockingKeysByOtherTx = SQLTxHoldKeysBlockingChildBB.getBB().getSharedMap(); 
    //due to #42672, some of the updates on parent could cause conflict with child table dml ops
    hydra.blackboard.SharedMap holdDeleteBlockingKeysByOtherTx = SQLTxDeleteHoldKeysBlockingChildBB.getBB().getSharedMap(); 
    //due to #50560, delete will gets conflict on current held rows
    hydra.blackboard.SharedMap holdForeignKeysByOtherTx = SQLTxHoldForeignKeysBB.getBB().getSharedMap(); 
    //for RC txn only, for RR txn use RRReadBB instead
    hydra.blackboard.SharedMap holdNewForeignKeysByOtherTx = SQLTxHoldNewForeignKeysBB.getBB().getSharedMap(); 
    //seperate newly acquried foreign vs existing fk of the row -- see #50560
    
    if (getsConflict) {
      //first check if conflict could be caused by primary/unique key held.
      if (verifyModifiedKeysConflict(modifiedKeysByOp,
        modifiedKeysByThisTx, modifiedKeysByOtherTx, gfxdse, getsConflict)) {
        //no need to add this op into total tx holding keys as the op failed due to conflict exception
        return true;
      }
      else {
        if (expectForeignKeysConflict(holdNonDeleteBlockingKeysByOp, 
            holdNonDeleteKeysByThisTx, holdBlockingKeysByOtherTx,
            holdDeleteBlockingKeysByOp, 
            holdDeleteKeysByThisTx, holdDeleteBlockingKeysByOtherTx,
            holdForeignKeysByOp, holdForeignKeysByThisTx, holdForeignKeysByOtherTx, 
            holdNewForeignKeysByOp, holdNewForeignKeysByThisTx, holdNewForeignKeysByOtherTx,
            gfxdse, getsConflict)) {
          //no need to add this op into total tx holding keys as the op failed due to conflict exception
          return true;
        }
        else {
          throw new TestException("Does not expect conflict exception but gets it." +
              TestHelper.getStackTrace(gfxdse));       
        }
      } 
    } else {
    //allow check constraint here instead of conflict exception
      //product lock SH lock first, it will promote to EX_SH if check constraint passes
      //only one tx could get a EX_SH for a certain row
      if (gfxdse != null && gfxdse.getSQLState().equals("23513")) {
        //no need to add this op into total tx holding keys as the op failed due to check constraint exception
        Log.getLogWriter().info("if we get check constraint here, but did not " +
        "get conflict exception. this is OK -- continuing test");
        return true;
      } else {
        if (verifyModifiedKeysConflict(modifiedKeysByOp,
            modifiedKeysByThisTx, modifiedKeysByOtherTx, gfxdse, getsConflict)) {          
          if (!expectForeignKeysConflict(holdNonDeleteBlockingKeysByOp, holdNonDeleteKeysByThisTx, 
              holdBlockingKeysByOtherTx, holdDeleteBlockingKeysByOp, 
              holdDeleteKeysByThisTx, holdDeleteBlockingKeysByOtherTx,
              holdForeignKeysByOp, holdForeignKeysByThisTx, holdForeignKeysByOtherTx, 
              holdNewForeignKeysByOp, holdNewForeignKeysByThisTx, holdNewForeignKeysByOtherTx,
              gfxdse, getsConflict)) {
            //add the locking keys to BB and threadLocal
            addLockingKeysToBB(modifiedKeysByOp,
                modifiedKeysByThisTx, 
                modifiedKeysByOtherTx,
                holdNonDeleteBlockingKeysByOp, 
                holdNonDeleteKeysByThisTx, 
                holdBlockingKeysByOtherTx,
                holdDeleteBlockingKeysByOp, 
                holdDeleteKeysByThisTx, 
                holdDeleteBlockingKeysByOtherTx,
                holdForeignKeysByOp, 
                holdForeignKeysByThisTx, 
                holdForeignKeysByOtherTx, 
                holdNewForeignKeysByOp, 
                holdNewForeignKeysByThisTx, 
                holdNewForeignKeysByOtherTx);
            return true;
          } else {
            if (gfxdse == null) throw new TestException ("Expected conflict, but does not get it");
            else {
              if (gfxdse.getSQLState().equalsIgnoreCase("23503")) {
                Log.getLogWriter().info("Got foreign key constraint violation 23503" +
                    " instead of conflict exception, this is possible if delete" +
                    " deletes more than one rows and deleting some of rows could get 23503");
                return false;
              } else {
                Log.getLogWriter().warning("need to check if the exception is allowed here instead of conflict exception");
                throw new TestException ("Expected conflict, but get other exception: " + TestHelper.getStackTrace(gfxdse));
              }
            }
          }
        } else {
          if (gfxdse == null) throw new TestException ("Expected conflict, but does not get it");
          else if (gfxdse.getSQLState().equalsIgnoreCase("23503")) {
            Log.getLogWriter().info("Got foreign key constraint violation 23503" +
                " instead of conflict exception, this is possible if delete" +
                " deletes more than one rows and deleting some of rows could get 23503");
            //no need to add this op into total tx holding keys as the op failed due to fk constraint exception
            return false;
          } else
          throw new TestException ("Expected conflict, but does not get it"); 
        }
      }
      
    }
  }
  
  /**
   * find out if the keys modified by this op has confict with other tx when
   * batching txn is enabled
   * @return true if verification succeed 
   *         false (no false will be returned for batching op)
   */
  @SuppressWarnings("unchecked")
  protected boolean verifyConflictNewTablesWithBatching(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> holdNonDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdForeignKeysByOp,  
      HashMap<String, Integer> holdNewForeignKeysByOp,  
      SQLException gfxdse,
      boolean getsConflict) {
    
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
    hydra.blackboard.SharedMap modifiedKeysByOtherTx = SQLTxBatchingBB.getBB().getSharedMap();
    hydra.blackboard.SharedMap holdBlockingKeysByOtherTx = SQLTxBatchingNonDeleteHoldKeysBB.getBB().getSharedMap(); 
    //due to #42672, some of the updates on parent could cause conflict with child table dml ops
    hydra.blackboard.SharedMap holdDeleteBlockingKeysByOtherTx = SQLTxBatchingDeleteHoldKeysBB.getBB().getSharedMap(); 
    //due to #50560, delete will gets conflict on current held rows
    hydra.blackboard.SharedMap holdForeignKeysByOtherTx = SQLTxHoldForeignKeysBB.getBB().getSharedMap(); 
    //for RC txn only, for RR txn use RRReadBB instead
    hydra.blackboard.SharedMap holdNewForeignKeysByOtherTx = SQLTxHoldNewForeignKeysBB.getBB().getSharedMap(); 
    //seperate newly acquried foreign vs existing fk of the row -- see #50560
    
    //batching will compare cur txn holding keys with all txns
    //this is fine as long as #43170 is not yet fixed
    //will revisit this once #43170 is fixed and its impact on txn batching.
    addLockingKeysToBatchingBB(modifiedKeysByOp,
        modifiedKeysByThisTx, 
        modifiedKeysByOtherTx,
        holdNonDeleteBlockingKeysByOp, 
        holdNonDeleteKeysByThisTx, 
        holdBlockingKeysByOtherTx,
        holdDeleteBlockingKeysByOp, 
        holdDeleteKeysByThisTx, 
        holdDeleteBlockingKeysByOtherTx,
        holdForeignKeysByOp, 
        holdForeignKeysByThisTx, 
        holdForeignKeysByOtherTx, 
        holdNewForeignKeysByOp, 
        holdNewForeignKeysByThisTx, 
        holdNewForeignKeysByOtherTx);

    //needs to check if conflict should be thrown by the product
    if (getsConflict) {
      //first check if conflict could be caused by primary/unique key held.
      if (expectModifiedKeysConflictWithBatching(modifiedKeysByOp,
        modifiedKeysByThisTx, modifiedKeysByOtherTx, gfxdse, getsConflict)) {
        return true;
      }
      else {
        if (expectForeignKeysConflictWithBatching(holdNonDeleteBlockingKeysByOp, 
            holdNonDeleteKeysByThisTx, holdBlockingKeysByOtherTx,
            holdDeleteBlockingKeysByOp, 
            holdDeleteKeysByThisTx, holdDeleteBlockingKeysByOtherTx,
            holdForeignKeysByOp, holdForeignKeysByThisTx, holdForeignKeysByOtherTx, 
            holdNewForeignKeysByOp, holdNewForeignKeysByThisTx, holdNewForeignKeysByOtherTx,
            gfxdse, getsConflict)) {
          //no need to add this op into total tx holding keys as the op failed due to conflict exception
          return true;
        }
        else {
          throw new TestException("Does not expect conflict exception but gets it." +
              TestHelper.getStackTrace(gfxdse));       
        }
      } 
    } 
    //with batching this is OK even though conflict exception should be thrown 
    else {
      return true;
      
    }
  }
  
  protected void addLockingKeysToBB(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> modifiedKeysByThisTx, 
      SharedMap modifiedKeysByOtherTx,
      HashMap<String, Integer>holdNonDeleteKeysByOp, 
      HashMap<String, Integer> holdNonDeleteKeysByThisTx, 
      SharedMap holdNonDeleteKeysByOtherTx,
      HashMap<String, Integer>holdDeleteKeysByOp, 
      HashMap<String, Integer> holdDeleteKeysByThisTx, 
      SharedMap holdDeleteKeysByOtherTx,
      HashMap<String, Integer> holdForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdForeignKeysByThisTx, 
      SharedMap holdForeignKeysByOtherTx, 
      HashMap<String, Integer> holdNewForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdNewForeignKeysByThisTx, 
      SharedMap holdNewForeignKeysByOtherTx) {
    
    //update bbs for all txns
    if (modifiedKeysByOp != null) {
      modifiedKeysByThisTx.putAll(modifiedKeysByOp);
      modifiedKeysByOtherTx.putAll(modifiedKeysByOp);
    }
    
    if (holdNonDeleteKeysByOp != null) {
      holdNonDeleteKeysByThisTx.putAll(holdNonDeleteKeysByOp); //parent key in the foreign key detection
      holdNonDeleteKeysByOtherTx.putAll(holdNonDeleteKeysByOp); //update bb
    }
    
    if (holdDeleteKeysByOp != null) {
      holdDeleteKeysByThisTx.putAll(holdDeleteKeysByOp); //parent key in the foreign key detection using delete op
      holdDeleteKeysByOtherTx.putAll(holdDeleteKeysByOp);
    }
    
    if (holdForeignKeysByOp != null) {
      for (String key: holdForeignKeysByOp.keySet()) {
        ForeignKeyLocked fkLockedByTx = (ForeignKeyLocked)holdForeignKeysByThisTx.get(key);
        if (fkLockedByTx == null) fkLockedByTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding exsiting fk locked in current txn");
        fkLockedByTx.addKeyByCurTx(holdForeignKeysByOp.get(key));
        holdForeignKeysByThisTx.put(key, fkLockedByTx);
        
        ForeignKeyLocked fkLockedByOtherTx = (ForeignKeyLocked)holdForeignKeysByOtherTx.get(key);
        if (fkLockedByOtherTx == null) fkLockedByOtherTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding exsiting fk locked in current txn");
        fkLockedByOtherTx.addKeyByCurTx(holdForeignKeysByOp.get(key));
        holdForeignKeysByOtherTx.put(key, fkLockedByOtherTx);
      }
    }
    
    //for new fk
    if (holdNewForeignKeysByOp != null) {
      for (String key: holdNewForeignKeysByOp.keySet()) {
        ForeignKeyLocked fkLockedByTx = (ForeignKeyLocked)holdNewForeignKeysByThisTx.get(key);
        if (fkLockedByTx == null) fkLockedByTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding new fk locked in current txn");
        fkLockedByTx.addKeyByCurTx(holdNewForeignKeysByOp.get(key));
        holdNewForeignKeysByThisTx.put(key, fkLockedByTx);
        
        ForeignKeyLocked fkLockedByOtherTx = (ForeignKeyLocked)holdNewForeignKeysByOtherTx.get(key);
        if (fkLockedByOtherTx == null) fkLockedByOtherTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding new fk locked in all txns");
        fkLockedByOtherTx.addKeyByCurTx(holdNewForeignKeysByOp.get(key));
        holdNewForeignKeysByOtherTx.put(key, fkLockedByOtherTx);
      }
    }
    
    //update thread local for current txn
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByThisTx);
    SQLDistTxTest.curTxNonDeleteHoldKeys.set(holdNonDeleteKeysByThisTx);
    SQLDistTxTest.curTxDeleteHoldKeys.set(holdDeleteKeysByThisTx); 
    SQLDistTxTest.curTxHoldParentKeys.set(holdForeignKeysByThisTx); 
    SQLDistTxTest.curTxNewHoldParentKeys.set(holdNewForeignKeysByThisTx);

  }
  
  @SuppressWarnings("unchecked")
  protected void addLockingKeysToBatchingBB(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> modifiedKeysByThisTx, 
      SharedMap modifiedKeysByAllTx,
      HashMap<String, Integer>holdNonDeleteKeysByOp, 
      HashMap<String, Integer> holdNonDeleteKeysByThisTx, 
      SharedMap holdNonDeleteKeysByAllTx,
      HashMap<String, Integer>holdDeleteKeysByOp, 
      HashMap<String, Integer> holdDeleteKeysByThisTx, 
      SharedMap holdDeleteKeysByAllTx,
      HashMap<String, Integer> holdForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdForeignKeysByThisTx, 
      SharedMap holdForeignKeysByAllTx, 
      HashMap<String, Integer> holdNewForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdNewForeignKeysByThisTx, 
      SharedMap holdNewForeignKeysByAllTx) {

    //update bbs for all txns
    if (modifiedKeysByOp != null) {
      modifiedKeysByThisTx.putAll(modifiedKeysByOp);
      for (String key: modifiedKeysByOp.keySet()) {
        ArrayList<Integer> list = null;
        if (modifiedKeysByAllTx.containsKey(key)) {      
          list = (ArrayList<Integer>) modifiedKeysByAllTx.get(key);
          if (list.size() != 0) {
            Log.getLogWriter().info("for key: " + key + ", there are following other txId hold the" +
                " lock locally: " + list.toString());
          }
        } else {
          list = new ArrayList<Integer>();
        }
        Log.getLogWriter().info("this tx holds the following key " + key);
        Integer myTxId = modifiedKeysByOp.get(key);
        if (!list.contains(myTxId)) list.add(myTxId);
        
        modifiedKeysByAllTx.put(key, list);  
      }
    }
    
    if (holdNonDeleteKeysByOp != null) {
      holdNonDeleteKeysByThisTx.putAll(holdNonDeleteKeysByOp); //parent key in the foreign key detection
      for (String key: holdNonDeleteKeysByOp.keySet()) {
        ArrayList<Integer> list = null;
        if (holdNonDeleteKeysByAllTx.containsKey(key)) {      
          list = (ArrayList<Integer>) holdNonDeleteKeysByAllTx.get(key);
          if (list.size() != 0) {
            Log.getLogWriter().info("for key: " + key + ", there are following txId hold the" +
                " non delete key lock locally: " + list.toString());
          }
        } else {
          list = new ArrayList<Integer>();
        }
        Log.getLogWriter().info("this tx holds the following non delete key " + key);
        Integer myTxId = holdNonDeleteKeysByOp.get(key);
        if (!list.contains(myTxId)) list.add(myTxId);
        
        holdNonDeleteKeysByAllTx.put(key, list);  
      }
    }
    
    if (holdDeleteKeysByOp != null) {
      holdDeleteKeysByThisTx.putAll(holdDeleteKeysByOp); //parent key in the foreign key detection using delete op
      for (String key: holdDeleteKeysByOp.keySet()) {
        ArrayList<Integer> list = null;
        if (holdDeleteKeysByAllTx.containsKey(key)) {      
          list = (ArrayList<Integer>) holdDeleteKeysByAllTx.get(key);
          if (list.size() != 0) {
            Log.getLogWriter().info("for key: " + key + ", there are following txId hold the" +
                " delete key lock locally: " + list.toString());
          }
        } else {
          list = new ArrayList<Integer>();
        }
        Log.getLogWriter().info("this tx holds the following delete key " + key);
        Integer myTxId = holdDeleteKeysByOp.get(key);
        if (!list.contains(myTxId)) list.add(myTxId);
        
        holdDeleteKeysByAllTx.put(key, list);  
      }
      
    }
    
    if (holdForeignKeysByOp != null) {
      for (String key: holdForeignKeysByOp.keySet()) {
        ForeignKeyLocked fkLockedByTx = (ForeignKeyLocked)holdForeignKeysByThisTx.get(key);
        if (fkLockedByTx == null) fkLockedByTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding exsiting fk locked in current txn");
        fkLockedByTx.addKeyByCurTx(holdForeignKeysByOp.get(key));
        holdForeignKeysByThisTx.put(key, fkLockedByTx);
        
        ForeignKeyLocked fkLockedByAllTx = (ForeignKeyLocked)holdForeignKeysByAllTx.get(key);
        if (fkLockedByAllTx == null) fkLockedByAllTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding exsiting fk locked in current txn");
        fkLockedByAllTx.addKeyByCurTx(holdForeignKeysByOp.get(key));
        holdForeignKeysByAllTx.put(key, fkLockedByAllTx);
      }
    }
    
    //for new fk
    if (holdNewForeignKeysByOp != null) {
      for (String key: holdNewForeignKeysByOp.keySet()) {
        ForeignKeyLocked fkLockedByTx = (ForeignKeyLocked)holdNewForeignKeysByThisTx.get(key);
        if (fkLockedByTx == null) fkLockedByTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding new fk locked in current txn");
        fkLockedByTx.addKeyByCurTx(holdNewForeignKeysByOp.get(key));
        holdNewForeignKeysByThisTx.put(key, fkLockedByTx);
        
        ForeignKeyLocked fkLockedByAllTx = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
        if (fkLockedByAllTx == null) fkLockedByAllTx = (new ForeignKeyLocked(key));
        Log.getLogWriter().info("Adding new fk locked in all txns");
        fkLockedByAllTx.addKeyByCurTx(holdNewForeignKeysByOp.get(key));
        holdNewForeignKeysByAllTx.put(key, fkLockedByAllTx);
      }
    }
    
    //update thread local for current txn
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByThisTx);
    SQLDistTxTest.curTxNonDeleteHoldKeys.set(holdNonDeleteKeysByThisTx);
    SQLDistTxTest.curTxDeleteHoldKeys.set(holdDeleteKeysByThisTx); 
    SQLDistTxTest.curTxHoldParentKeys.set(holdForeignKeysByThisTx); 
    SQLDistTxTest.curTxNewHoldParentKeys.set(holdNewForeignKeysByThisTx);

  }
  
  /**
   * Verifies whether the op should gets conflict or not
   * @param modifiedKeysByOp 
   * @param modifiedKeysByThisTx
   * @param modifiedKeysByOtherTx
   * @param gfxdse        SQLException encountered by the op in the txn
   * @param getsConflict  whether the op gets the conflict
   * @return              true if gets expected conflict or does not get unexpected conflict  
   *                      false if gets unexpected conflict 
   *                      throws TestException if does not get expected conflict   
   */
  @SuppressWarnings("unchecked")
  protected boolean verifyModifiedKeysConflict(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> modifiedKeysByThisTx, 
      SharedMap modifiedKeysByOtherTx, SQLException gfxdse, boolean getsConflict) {

    int beforeSize = modifiedKeysByOtherTx.size();
    Log.getLogWriter().info("before the op, the tx bb map size is " + beforeSize);
    
    //remove those keys are already held by this tx
    for (String key: modifiedKeysByThisTx.keySet())
      modifiedKeysByOp.remove(key); //only compare the newly acquired keys
    if (modifiedKeysByOp.size() == 0) {     
      if (!getsConflict) {
        Log.getLogWriter().info("this op does not have newly modified keys");
        return true;
      } else {
        Log.getLogWriter().info("there is no conflict detected based on modified keys");
        return false;
      }
    }
    
    //verify the conflict based on primary/unique keys
    int mapSize = modifiedKeysByOp.size();
    Log.getLogWriter().info("newly modified key map size is " + mapSize);
    HashMap<String, Integer> verifyMap = new HashMap<String, Integer>();
    verifyMap.putAll(modifiedKeysByOp); //duplicate copy of modifiedKeys    
    Map modifiedKeysByOthers = modifiedKeysByOtherTx.getMap();
    verifyMap.putAll(modifiedKeysByOthers); 
    
    int afterSize = verifyMap.size();
    Log.getLogWriter().info("tx bb map size is " + afterSize);
    if (beforeSize + mapSize == afterSize) {   
      if (getsConflict) {
        Log.getLogWriter().info("No conflict detected due to row locks, " +
        "need to check foreign key conflict");
        return false;
      } else {
        Log.getLogWriter().info("there is no conflict detected based on modified keys");
        return true;
      }    
    } else {
      if (getsConflict) {
        Log.getLogWriter().info("Gets expected conflict due to row locks.");
        return true;
      } else { 
        if (gfxdse == null) logMissingConflictThrowException(modifiedKeysByOp, modifiedKeysByOthers); //throw TestException
        else logMissingConflict(modifiedKeysByOp, modifiedKeysByOthers); 
        return false; 
      }
    }

  }
  
  /**
   * Verifies whether the op should gets conflict or not when batching is on
   * @param modifiedKeysByOp 
   * @param modifiedKeysByThisTx
   * @param modifiedKeysByOtherTx
   * @param gfxdse        SQLException encountered by the op in the txn
   * @param getsConflict  whether the op gets the conflict
   * @return              true if gets expected conflict 
   *                      false if should not get conflict   
   */
  @SuppressWarnings("unchecked")
  protected boolean expectModifiedKeysConflictWithBatching(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer> modifiedKeysByThisTx, 
      SharedMap modifiedKeysByAllTx, SQLException gfxdse, boolean getsConflict) {

    if (modifiedKeysByThisTx != null) {
      for (String key: modifiedKeysByThisTx.keySet()) {
        ArrayList<Integer> list = (ArrayList<Integer>) modifiedKeysByAllTx.get(key);
        if (list.size() > 1) {
          //more than 1 txn holding the same key during batching
          return true;
        } 
        //keys in this txn (including in this op) has been added into all txn batching bb
      }
    }
    return false;
  }
  
  /**
   * 
   * @return true if a conflict should be expected due to foreing key relationship
   *         false if no conflict should be expected due to fk relationship
   */
  protected boolean expectForeignKeysConflictWithBatching(HashMap<String, Integer>holdBlockingKeysByOp, 
      HashMap<String, Integer> holdBlockingKeysByThisTx, 
      SharedMap holdBlockingKeysByAllTx,
      HashMap<String, Integer>holdDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdDeleteBlockingKeysByThisTx, 
      SharedMap holdDeleteBlockingKeysByAllTx,
      HashMap<String, Integer> holdForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdForeignKeysByThisTx, 
      SharedMap holdForeignKeysByAllTx, 
      HashMap<String, Integer> holdNewForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdNewForeignKeysByThisTx, 
      SharedMap holdNewForeignKeysByAllTx, 
      SQLException gfxdse, boolean getsConflict) {
    boolean getBlockingKeyConflict = false;
    boolean getDeleteBlockingKeyConflict = false;
    boolean getForeignKeyConflict = false;
    boolean getNewFkConflict = false;
    boolean getNewFkDeleteConflict = false;
    boolean getFkDeleteConflict = false;

    if (holdBlockingKeysByThisTx !=null ) {
      getBlockingKeyConflict = checkBlockingKeysWithBatching(holdBlockingKeysByThisTx, holdNewForeignKeysByAllTx);
    }
    
    if (holdDeleteBlockingKeysByThisTx !=null) {
      getDeleteBlockingKeyConflict = checkDeleteBlockingKeysWithBatching(holdDeleteBlockingKeysByThisTx, 
          holdForeignKeysByAllTx, holdNewForeignKeysByAllTx);
    }
    
    if (holdForeignKeysByThisTx!= null) {
      getFkDeleteConflict = checkForeignKeysWithBatching(holdForeignKeysByThisTx, holdDeleteBlockingKeysByAllTx);
    }
    
    if (holdNewForeignKeysByThisTx!= null) {
      getNewFkConflict = checkForeignKeysWithBatching(holdNewForeignKeysByThisTx, holdBlockingKeysByAllTx);
      getNewFkDeleteConflict = checkForeignKeysWithBatching(holdNewForeignKeysByThisTx, holdDeleteBlockingKeysByAllTx);
    }
    
    getForeignKeyConflict = getNewFkConflict  || getNewFkDeleteConflict || getFkDeleteConflict;
    
    if (!getBlockingKeyConflict && !getDeleteBlockingKeyConflict && 
        !getForeignKeyConflict) return false;
    else return true;
  }
  
  /**
   * 
   * @return true if a conflict should be expected due to foreing key relationship
   *         false if no conflict should be expected due to fk relationship
   */
  protected boolean expectForeignKeysConflict(HashMap<String, Integer>holdBlockingKeysByOp, 
      HashMap<String, Integer> holdBlockingKeysByThisTx, 
      SharedMap holdBlockingKeysByOtherTx,
      HashMap<String, Integer>holdDeleteBlockingKeysByOp, 
      HashMap<String, Integer> holdDeleteBlockingKeysByThisTx, 
      SharedMap holdDeleteBlockingKeysByOtherTx,
      HashMap<String, Integer> holdForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdParentKeysByThisTx, 
      SharedMap holdForeignKeysByOtherTx, 
      HashMap<String, Integer> holdNewForeignKeysByOp, 
      HashMap<String, ForeignKeyLocked> holdNewParentKeysByThisTx, 
      SharedMap holdNewForeignKeysByOtherTx, 
      SQLException gfxdse, boolean getsConflict) {
    boolean getBlockingKeyConflict = false;
    boolean getForeignKeyConflict = false;
    boolean getNewFkConflict = false;
    boolean getNewFkDeleteConflict = false;
    boolean getFkDeleteConflict = false;

    //check if there is child (insert/update) holds the blocking key
    if (holdBlockingKeysByOp ==null || (holdBlockingKeysByOp !=null && holdBlockingKeysByOp.size() == 0)) { 
      //the blocking keys due to delete op
      if (holdDeleteBlockingKeysByOp == null || (holdDeleteBlockingKeysByOp !=null 
          && holdDeleteBlockingKeysByOp.size() == 0)) { 
        Log.getLogWriter().info("this op does not have newly modified keys");
        getBlockingKeyConflict = false;
      } else {
        getBlockingKeyConflict = checkDeleteBlockingKeys(holdDeleteBlockingKeysByOp, 
            holdForeignKeysByOtherTx, holdNewForeignKeysByOtherTx);
      }
    } else {
      //delete & non delete ops are mutual exclusive
      getBlockingKeyConflict = checkBlockingKeys(holdBlockingKeysByOp, holdNewForeignKeysByOtherTx);
    } 

    //check if the keys are hold already by other txns blocking child to insert or update
    if (holdForeignKeysByOp==null && (holdForeignKeysByOp!= null 
        && holdForeignKeysByOp.size() == 0)) {     
      Log.getLogWriter().info("this op does not hold exiting foreign keys in child tables");
      getForeignKeyConflict = false;
    } else {
      getFkDeleteConflict = checkForeignKeys(holdForeignKeysByOp, holdDeleteBlockingKeysByOtherTx);
      if (getFkDeleteConflict) throw new TestException("delete op on parent should fail with 23503 or conflict exception, " +
       		"we should not see conflict during op on child"); //batching op will be different.
    }   
    
    //check if the keys are hold already by other txns blocking child to insert or update
    if (holdNewForeignKeysByOp==null && (holdNewForeignKeysByOp!= null 
        && holdNewForeignKeysByOp.size() == 0)) {     
      Log.getLogWriter().info("this op does not hold new foreign keys in child tables");
      getForeignKeyConflict = false;
    } else {
       getNewFkConflict = checkForeignKeys(holdNewForeignKeysByOp, holdBlockingKeysByOtherTx);
       getNewFkDeleteConflict = checkForeignKeys(holdNewForeignKeysByOp, holdDeleteBlockingKeysByOtherTx);    
    }  
    
    getForeignKeyConflict = getNewFkConflict  || getNewFkDeleteConflict || getFkDeleteConflict;
    
    if (!getBlockingKeyConflict && !getForeignKeyConflict) return false;
    else return true;
  }
  
  //returns true if this op should cause a conflict due to fk relationship for non delete op
  protected boolean checkBlockingKeys(HashMap<String, Integer>holdBlockingKeysByOp, 
      SharedMap holdNewForeignKeysByOtherTx) {
    Set<String> keys = holdBlockingKeysByOp.keySet();
    for (String key: keys) {
      int txId = holdBlockingKeysByOp.get(key);
      ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdNewForeignKeysByOtherTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
    }
    Log.getLogWriter().info("This op does not hold foreign keys causing conflict " +
    		"for existing child table ops");
    return false;    
  }
  
  //returns true if this op should cause a conflict due to fk relationship for non delete op in cur txn
  protected boolean checkBlockingKeysWithBatching(HashMap<String, Integer>holdBlockingKeysByThisTx, 
      SharedMap holdNewForeignKeysByAllTx) {
    Set<String> keys = holdBlockingKeysByThisTx.keySet();
    for (String key: keys) {
      int txId = holdBlockingKeysByThisTx.get(key);
      ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
    }
    Log.getLogWriter().info("This txn does not hold foreign keys causing conflict " +
        "for existing child table ops");
    return false;    
  }

  //returns true if this op should cause a conflict due to fk relationship for delete op
  protected boolean checkDeleteBlockingKeys(HashMap<String, Integer>holdDeleteBlockingKeysByOp, 
      SharedMap holdForeignKeysByAllTx, SharedMap holdNewForeignKeysByAllTx) {
    Set<String> keys = holdDeleteBlockingKeysByOp.keySet();
    for (String key: keys) {
      int txId = holdDeleteBlockingKeysByOp.get(key);
      ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdForeignKeysByAllTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
      
      fkLocked = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
    }
    Log.getLogWriter().info("This op does not hold foreign keys causing conflict " +
        "for existing child table ops");
    return false;    
  }
  
  //returns true if this op should cause a conflict due to fk relationship for delete op in cur txn
  protected boolean checkDeleteBlockingKeysWithBatching(HashMap<String, Integer>holdDeleteBlockingKeysByThisTx, 
      SharedMap holdForeignKeysByAllTx, SharedMap holdNewForeignKeysByAllTx) {
    Set<String> keys = holdDeleteBlockingKeysByThisTx.keySet();
    for (String key: keys) {
      int txId = holdDeleteBlockingKeysByThisTx.get(key);
      ForeignKeyLocked fkLocked = (ForeignKeyLocked)holdForeignKeysByAllTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
      
      fkLocked = (ForeignKeyLocked)holdNewForeignKeysByAllTx.get(key);
      if (fkLocked != null && fkLocked.detectOtherTxIdConflict(txId)) return true;
    }
    Log.getLogWriter().info("This txn does not have delete op holding foreign keys causing conflict " +
        "for existing child table ops");
    return false;    
  }
  
  //returns true if this op should cause a conflict due to fk relationship
  protected boolean checkForeignKeys(HashMap<String, Integer>holdForeignKeysByOp, 
      SharedMap holdBlockingKeysByOtherTx) {
    if (holdForeignKeysByOp == null) return false;
    Set<String> keys = holdForeignKeysByOp.keySet();
    for (String key: keys) {
      int txId = holdForeignKeysByOp.get(key);
      Integer blockingTxId = (Integer)holdBlockingKeysByOtherTx.get(key);
      if (blockingTxId !=null && txId != blockingTxId) {
        Log.getLogWriter().info("Another txId: " + blockingTxId + " holds " +
        		"the foreign key needs for op in this txn: " + txId);
        return true;
      }
    }
    Log.getLogWriter().info("This op does not hold foreign keys causing conflict for existing " +
    		"parent table ops");
    return false;    
  }
  
  //returns true if this op should cause a conflict due to fk relationship
  @SuppressWarnings("unchecked")
  protected boolean checkForeignKeysWithBatching(HashMap<String, ForeignKeyLocked>holdForeignKeysByThisTx, 
      SharedMap holdBlockingKeysByAllTx) {
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
              Log.getLogWriter().info("Another txId: " + txId + " holds " +
                  "the foreign key needs for op in this txn: " + myTxId);
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
  
  protected void resetCanceledFlag() {
    //to reset getCanceled flag     
    boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
    if (getCanceled != null && getCanceled[0]) {
      getCanceled[0] = false;
      SQLTest.getCanceled.set(getCanceled);
      Log.getLogWriter().info("getCanceled is reset for new ops");
    }
  }
  
  protected boolean getNodeFailureFlag() {
    boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
    if (getNodeFailure != null && getNodeFailure[0]) {
      return true;
    }
    else {   
      return false;
    }
  }
  
}
