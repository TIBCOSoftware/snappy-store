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

import hydra.Log;
import hydra.MasterController;
import hydra.RemoteTestModule;
import hydra.TestConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.mbeans.MBeanTest;
import sql.mbeans.listener.CallBackListener;
import sql.security.SQLSecurityTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import util.TestException;
import util.TestHelper;

import com.gemstone.gemfire.cache.query.Struct;

/**
 * @author eshu
 *
 */
public class TradeSellOrdersDMLStmt extends AbstractDMLStmt {
  /*
   * trade.orders table fields
   *   int oid;
   *   int cid; //ref customers
   *   int sid; //ref securities sec_id
   *   int qty;
   *   BigDecimal ask;
   *   Timestamp order_time;
   *   String stauts;
   *   int tid; //for update or delete unique records to the thread
   */
  protected static String[] statuses = {                                       
    "cancelled", "open", "filled"                                       
  };
  protected static boolean reproduce51276 = TestConfig.tab().booleanAt(SQLPrms.toReproduce51276, false);
  protected static boolean reproduce51249 = TestConfig.tab().booleanAt(SQLPrms.toReproduce51249, false);
  protected static String insert = "insert into trade.sellorders  (oid, cid, sid, qty, ask, order_time, status, tid) values (?,?,?,?,?,?,?,?)";
  protected static String insertWithDefaultValue = "insert into trade.sellorders (oid, cid, sid, qty, ask, order_time, tid)" +
      " values (?,?,?,?,?,?,?)";
  protected static String insertWithGeneratedDefaultId = "insert into trade.sellorders values (default,?,?,?,?,?,?,?)";
  protected static String insertWithGeneratedDefaultIdAndDefaultValue = "insert into trade.sellorders (default, cid, sid, qty, ask, order_time, tid)" +
  " values (?,?,?,?,?,?,?)";
  protected static String put = "put into trade.sellorders values (?,?,?,?,?,?,?,?)";
  protected static String putWithDefaultValue = "put into trade.sellorders (oid, cid, sid, qty, ask, order_time, tid)" +
      " values (?,?,?,?,?,?,?)";
  protected static String putWithGeneratedDefaultId = "put into trade.sellorders values (default,?,?,?,?,?,?,?)";
  protected static String putWithGeneratedDefaultIdAndDefaultValue = "put into trade.sellorders (default, cid, sid, qty, ask, order_time, tid)" +
  " values (?,?,?,?,?,?,?)";
  public static String str_sid = SQLPrms.isSnappyMode()?"SID".toLowerCase():"SID";
  public static String str_cid = SQLPrms.isSnappyMode()?"CID".toLowerCase():"CID";

  protected static String[] update = { 
    //uniq
    "update trade.sellorders set status = 'filled'  where sid = ? and ask<? and status = 'open' and tid = ? ",  //for trigger test it could be a batch update
    "update trade.sellorders set status = 'cancelled' where order_time >? and status = 'open' and tid =? ",  //batch operation
    (!setCriticalHeap? //single row
        "update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? " :
        "update trade.sellorders set ask = ? where oid = (select min(oid) from trade.sellorders where cid = ? and sid= ? and status = 'open' and qty >? and tid =?) "),
    "update trade.sellorders set ask = ? , qty=? where cid = ? and sid= ? and ask <? and status = 'open' and tid =? ",
    //no uniq
    "update trade.sellorders set status = 'filled'  where sid = ? and ask<? and status = 'open' ",  //for trigger test it could be a batch update
    "update trade.sellorders set status = 'cancelled' where order_time <? and status = 'open'  ",  //batch operation
    "update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? ",
    "update trade.sellorders set ask = ? , qty=? where cid = ? and sid= ? and ask <? and status = 'open'  ",                                        
  }; 
  protected static String[] select = {
    //uniqkey queries
    "select * from trade.sellorders where status = 'open' and tid = ?",
    "select cid, cast(sum(ask)/count(distinct sid) as decimal(10,2)) as average from trade.sellorders where cid >? and tid = ? group by cid order by cid, average",
    "select oid, cid, ask, " +    
    (RemoteTestModule.getCurrentThread().getThreadId() %2 ==0 && reproduce51249? 
    " max(case when status='open' then sid else 0 end) OPENORDER, " +
    " max(case when status in ('filled', 'cancelled') then sid else 0 end) as CLOSEDORDER "    
       : 
    " max(case when status='filled' then sid else 0 end) as FILLED, " +
    " max(case when status='open' then sid else 0 end) OPENORDER, " +
    " max(case when status='cancelled' then sid else 0 end) as CANCELLED "
    )+
    " from trade.sellorders where (ask<? and tid=? ) or (ask >=? and tid =?) " +
    " group by oid, cid, ask",
    "select oid, sid, ask, status, cid from trade.sellorders  where status =? and cid =? and tid =?",
    "select oid, sid, ask, cid, status from trade.sellorders  " +
    "where ((status =? and ask <?) OR (status =? and ask >?))  and cid not IN (?, ?, ?, ?, ?) and tid =?", 
    "select oid, sid, ask, cid, " +
    (RemoteTestModule.getMyVmid()%2 ==0 || SQLPrms.isSnappyMode() ? "order_time": "second(order_time)" ) +
    ", status from trade.sellorders  " +
    "where (((order_time >? and ask <?) and (status =? and ask >?))  OR cid IN (?, ?, ?, ?, ?)) and tid =?", // avoid #39766
    (RemoteTestModule.getCurrentThread().getThreadId() %2 ==0 || !reproduce51276? 
     "select oid, cid, order_time, sid from trade.sellorders where (order_Time < ?) and tid =? "
        :
     "select oid, status, order_time, max(ask) as ask from trade.sellorders where (order_Time < ?) and tid =? " +     
     " group by status, order_time, oid " +
     " HAVING (MAX(CASE WHEN status = '" + statuses[0] + "'" +
     " THEN ask else null end) >45" + 
     " OR MAX(CASE WHEN status = '" + statuses[1] + "'" +
     " THEN ask else null end) >55" + 
     " OR MAX(CASE WHEN status = '" + statuses[2] + "'" +
     " THEN ask else null end) >65" + 
     ")"
    ),
    "select * from  trade.sellorders where oid IN (?, ?, ?, ?, ?) and tid =?",
    //non uniqkey queries
    "select * from trade.sellorders",
    "select cid, ask, sid from trade.sellorders where cid >? ",
    "select oid, sid from trade.sellorders where (ask<? or ask >=?) ",
    "select oid, sid, ask, status, cid from trade.sellorders  where status =? and cid =? ",
    "select oid, sid, ask, cid, status from trade.sellorders  " +
    "where ((status =? and ask <?) OR (status =? and ask >?))  and cid IN (?, ?, ?, ?, ?) ",
    //add the trim function to work around #46998
    "select oid, sid, ask, cid, " + 
    (RemoteTestModule.getMyVmid()%2 ==0  || SQLPrms.isSnappyMode()  ? "order_time": "trim(char(order_time))" ) +
    ", status from trade.sellorders  " +
    "where ((order_time >? and ask <?) and (status =? and ask >?))  OR cid IN (?, ?, ?, ?, ?)",
    "select oid, cid, order_time, sid from trade.sellorders where (order_time < ?)",
    "select * from  trade.sellorders where oid IN (?, ?, ?, ?, ?)"
  };
  protected static String[] delete = {
    //uniqkey                            
    "delete from trade.sellorders where cid=? and sid=? and tid=?", //could be batch delete, but no foreign key reference, this should work
    "delete from trade.sellorders where oid=? and tid=?",
    "delete from trade.sellorders where status IN ('cancelled', 'filled') and tid=?",
    "delete from trade.sellorders where tid=?",
    //non uniqkey
    "delete from trade.sellorders where cid=? and sid=?",
    "delete from trade.sellorders where oid=?",
    "delete from trade.sellorders where status IN ('cancelled', 'filled')",
    "delete from trade.sellorders"
  };


  
  protected static int maxNumOfTries = 1;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  protected static boolean useDefaultValue = TestConfig.tab().booleanAt(SQLPrms.useDefaultValue, false);
  protected static ArrayList<String> partitionKeys = null;
  protected boolean generateDefaultId = SQLTest.generateDefaultId;
  
  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#insert(java.sql.Connection, java.sql.Connection, int)
   */
  //in trigger tests, each insert/update will trigger update of portfolio availQty, if it violates avail_ck it should not exceed
  @Override
  public void insert(Connection dConn, Connection gConn, int size) {
    insert(dConn, gConn, size, false);
  }

  @Override
  public void put(Connection dConn, Connection gConn, int size) {
    insert(dConn, gConn, size, true);
  }
  
  public void insert(Connection dConn, Connection gConn, int size, boolean isPut) {
    int[] cid = new int[size];
    int[] sid = new int[size];
    getKeysFromPortfolio(gConn,cid, sid); //use gfxd connection to get data from portfolio
    
    //due to #49452, fk was taken out 
    if (hasHdfs) {
      for (int id: cid) {
        if (id == 0) {
          Log.getLogWriter().info("do not insert 0 for hdfs tests when fk was taken out due to #49452");
          return;
        }
      }
    }
    
    if (hasHdfs) {
      for (int id: sid) {
        if (id == 0) {
          Log.getLogWriter().info("do not insert 0 for hdfs tests when fk was taken out due to #49452");
          return;
        }
      }
    }
    
    insert(dConn, gConn, size, cid, sid, isPut);
  }

  protected void getKeysFromPortfolio(Connection regConn, int[] cids, int[] sids) {
    Connection conn = getAuthConn(regConn);
    int tid;
    if (testUniqueKeys || testWanUniqueness) tid = getMyTid();
    else  tid = rand.nextInt(getMyTid()+1); //use randomly choose tid
    
    int size = cids.length;
    ResultSet rs =null;
    boolean success = false;
    while (!success) {      
      try {
        rs = conn.createStatement().executeQuery("select * from trade.portfolio" +
            " where tid = " + tid);
        //use gfxd connection, should not get lock timeout exception
        success = true;
      } catch (SQLException se){
        if (isOfflineTest && (se.getSQLState().equals("X0Z09") || se.getSQLState().equals("X0Z08"))) {
          Log.getLogWriter().warning("Got expected Offline exception, continuing test");
          MasterController.sleepForMs(10000);
          if (SQLTest.syncHAForOfflineTest) {
            Log.getLogWriter().info("Does not get keys due to offline exceptions");
            return;
          }
        } else if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
          Log.getLogWriter().warning("memory runs low and get query cancellation exception");
          return;
        } else if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
          //see #49223, txn could not fail over yet, need to have new txn connection to retry
          Log.getLogWriter().warning("do not retry any more as no HA support for txn yet");
          return;
        }
        else if (SQLHelper.checkGFXDException(conn, se))  
          SQLHelper.handleSQLException(se);
        else  //false need to retry
          ; //retry
      }
    }
    ArrayList<Struct> rsList = (ArrayList<Struct>) ResultSetHelper.
      asList(rs, ResultSetHelper.getStructType(rs), SQLHelper.isDerbyConn(conn));
    SQLHelper.closeResultSet(rs, conn);
    
    if (rsList==null) return; //if not able to process the resultSet
    int rsSize = rsList.size();
    if (rsSize>=size) {
      int offset = rand.nextInt(rsSize - size +1); //start from a randomly chosen position
      for (int i=0; i<size; i++) {
        cids[i]=((Integer)((GFXDStructImpl) rsList.get(i+offset)).get("CID")).intValue();
        Log.getLogWriter().info("cid is " + cids[i]);
        sids[i]=((Integer)((GFXDStructImpl) rsList.get(i+offset)).get("SID")).intValue();
        Log.getLogWriter().info("sid is " + sids[i]);
      }
    } 
    else {
      for (int i=0; i<rsSize; i++) {
        cids[i]=((Integer)((GFXDStructImpl) rsList.get(i)).get("CID")).intValue();
        sids[i]=((Integer)((GFXDStructImpl) rsList.get(i)).get("SID")).intValue();
      }
      //remaining cid, sid using default of 0, and should failed due to fk constraint
    }     
  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#update(java.sql.Connection, java.sql.Connection, int)
   */
  @Override
  public void update(Connection dConn, Connection gConn, int size) {
    int[] sid = new int[size];
    BigDecimal[] ask = new BigDecimal[size];
    Timestamp[] orderTime = new Timestamp[size];
    int[] cid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] ask2 = new BigDecimal[size];
    
    int[]  whichUpdate = new int[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();

    if (dConn != null) {     
      //get the data
      if (rand.nextInt(numGettingDataFromDerby) == 1) {
        if (!getDataForUpdate(dConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size)) {
          Log.getLogWriter().info("not able to get the data for update, abort this operation"); 
          return;
        }
      } else getDataForUpdate(gConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size);
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = updateDerbyTable(dConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size, exceptionList);  //insert to derby table  
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not finish the update op in derby, will abort this operation in derby");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
          //expect gfxd fail with the same reason due to alter table
          else return;
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++; 
        exceptionList .clear();
        success = updateDerbyTable(dConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size, exceptionList);  //insert to derby table  
      } //retry only once.
      updateGFETable(gConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      if (getDataForUpdate(gConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size)) //get the da
        updateGFETable(gConn, cid, sid, qty, orderTime, ask, ask2, whichUpdate, size);
    } //no verification

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#delete(java.sql.Connection, java.sql.Connection)
   */
  @Override
  public void delete(Connection dConn, Connection gConn) {
    int numOfNonUniqDelete = delete.length/2;  //how many delete statement is for non unique keys
    int whichDelete = getWhichOne(numOfNonUniqDelete, delete.length);
    if (whichDelete == 3 && whichDelete != getMyTid()) whichDelete--; 
    
    if (SQLTest.syncHAForOfflineTest && whichDelete != 1) whichDelete = 1; //avoid #39605 see #49611
    
    int size = 1; //how many delete to be completed in this delete operation
    int[] cid = new int[size]; //only delete one record
    int[] sid = new int[size];
    int[] oid = new int[size];
    for (int i=0; i<size; i++) {
      oid[i] = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSellOrdersPrimary)); //random instead of uniq
    }
    List<SQLException> exceptionList = new ArrayList<SQLException>(); //for compare exceptions got from two sources
    int availSize;
    if (dConn!=null && rand.nextInt(numGettingDataFromDerby) == 1) 
        availSize = getDataForDelete(dConn, cid ,sid);
    else availSize = getDataForDelete(gConn, cid ,sid);
    
    if(availSize == 0) return; //did not get the results
    
    if (setCriticalHeap) {
      resetCanceledFlag();
      if (!hasTx) {
        whichDelete = 1; //avoid 39605
      }
    }
    
    //for verification both connections are needed
    if (dConn != null) {
      boolean success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, exceptionList);
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not finish the delete op in derby, will abort this operation in derby");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
          //expect gfxd fail with the same reason due to alter table
          else return;
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++; 
        exceptionList.clear();
        success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, exceptionList); //retry
      }
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid, exceptionList);
      SQLHelper.handleMissedSQLException(exceptionList);
    } 
    else {
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid); //w/o verification
    }
  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#query(java.sql.Connection, java.sql.Connection)
   */
  @Override
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = select.length/2; //how many query statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    
    String[] status = new String[2];
    BigDecimal[] ask = new BigDecimal[2];
    int[] cid = new int[5];  //test In for 5 
    int[] oid = new int[5];
    int tid = getMyTid();
    Timestamp orderTime = getRandTime();
    getStatus(status);
    getAsk(ask);
    if (dConn!=null) getCids(dConn, cid);
    else getCids(gConn, cid);
    getOids(oid);
    
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (dConn!=null) {
      try {
        discRS = query(dConn, whichQuery, status, ask, cid, oid, orderTime, tid);
        if (discRS == null) {
          Log.getLogWriter().info("could not get the derby result set after retry, abort this query");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) 
            ; //do nothing, expect gfxd fail with the same reason due to alter table
          else return;
        }
      } catch (SQLException se) {
        SQLHelper.handleDerbySQLException(se, exceptionList);
      }
      try {
        gfeRS = query (gConn,  whichQuery, status, ask, cid, oid, orderTime, tid); 
        if (gfeRS == null) {
          if (isHATest) {
            Log.getLogWriter().info("Testing HA and did not get GFXD result set after retry");
            return;
          } else if (setCriticalHeap) {
            Log.getLogWriter().info("got XCL54 and does not get query result");
            return; //prepare stmt may fail due to XCL54 now
          }
          else     
            throw new TestException("Not able to get gfe result set after retry");
        }
      } catch (SQLException se) {
        SQLHelper.handleGFGFXDException(se, exceptionList);
      }
      SQLHelper.handleMissedSQLException(exceptionList);
      if (discRS == null || gfeRS == null) return;
      
      boolean success = ResultSetHelper.compareResultSets(discRS, gfeRS); 
      if (!success) {
        Log.getLogWriter().info("Not able to compare results");
      } //not able to compare results due to derby server error     
    }// we can verify resultSet
    else {
      try {
        gfeRS = query (gConn, whichQuery, status, ask, cid, oid, orderTime, tid);
      } catch (SQLException se) {
        if (se.getSQLState().equals("42502") && SQLTest.testSecurity) {
          Log.getLogWriter().info("Got expected no SELECT permission, continuing test");
          return;
        } else if (alterTableDropColumn && se.getSQLState().equals("42X04")) {
          Log.getLogWriter().info("Got expected column not found exception, continuing test");
          return;
        } else SQLHelper.handleSQLException(se);
      }
      
      if (gfeRS != null)
        ResultSetHelper.asList(gfeRS, false);  
      else if (isHATest)
        Log.getLogWriter().info("could not get gfxd query results after retry due to HA");
      else if (setCriticalHeap)
        Log.getLogWriter().info("could not get gfxd query results after retry due to XCL54");
      else
        throw new TestException ("gfxd query returns null and not a HA test"); 
    }
    
    SQLHelper.closeResultSet(gfeRS, gConn);
  }
  
  //populate the table
  @Override
  public void populate (Connection dConn, Connection gConn) {
  //the table should be populated when populating customers table, 
    int initSize = rand.nextInt(10)+1; //howmany time a customer could insert sell orders
    int numCid;
    for (int i=0; i<initSize; i++) {
      numCid = rand.nextInt(30) + 1; 
      for (int j=0; j<numCid; j++) {
        if (setCriticalHeap) resetCanceledFlag();
        insert(dConn, gConn, 1);
        if (dConn != null) commit(dConn);
        commit(gConn);
      }
    }
  }
  
  //*** insert ***/
  public void insert(Connection dConn, Connection gConn, int size, int[] cid, int[] sid, boolean isPut) {
    int[] oid = new int[size];
    int[] qty = new int[size];
    String status = "open";
    Timestamp[] time = new Timestamp[size];
    BigDecimal[] ask = new BigDecimal[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();
    boolean success = false;
    if (dConn != null && rand.nextInt(numGettingDataFromDerby) == 1)
      getDataForInsert(dConn, oid, cid, sid, qty, time, ask, size); // get the
                                                                    // data
    else
      getDataForInsert(gConn, oid, cid, sid, qty, time, ask, size); // get the
                                                                    // data
    if (setCriticalHeap) resetCanceledFlag();
    
    if (dConn != null) {
      if (!generateDefaultId) {      
        success = insertToDerbyTable(dConn, oid, cid, sid, qty, status, time, ask, size, exceptionList);  //insert to derby table  
        int count = 0;
        while (!success) {
          if (count >= maxNumOfTries) {
            Log.getLogWriter().info("Could not finish the insert op in derby, will abort this operation in derby");
            if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
            //expect gfxd fail with the same reason due to alter table
            else return; 
          }
          MasterController.sleepForMs(rand.nextInt(retrySleepMs));
          count++; 
          exceptionList .clear(); //clear the exceptionList and retry
          success = insertToDerbyTable(dConn, oid, cid, sid, qty, status, time, ask, size, exceptionList); 
        }
        try {
          insertToGFETable(gConn, oid, cid, sid, qty, status, time, ask, size, exceptionList, isPut); 
        } catch (TestException te) {
          if (te.getMessage().contains("Execute SQL statement failed with: 23505")
              && isHATest && SQLTest.isEdge) {
            //checkTicket49605(dConn, gConn, "sellorders");
            try {
              checkTicket49605(dConn, gConn, "sellorders", oid[0], -1, null, null);
            } catch (TestException e) {
              Log.getLogWriter().info("insert failed due to #49605 ", e);
              //deleteRow(dConn, gConn, "buyorders", oid[0], -1, null, null);
              Log.getLogWriter().info("retry this using put to work around #49605");
              insertToGFETable(gConn, oid, cid, sid, qty, status, time, ask, size, exceptionList, true); 
              
              checkTicket49605(dConn, gConn, "sellorders", oid[0], -1, null, null);
            }
          } else throw te;
        }
        SQLHelper.handleMissedSQLException(exceptionList);
      } else {   
        SQLException gfxdEx = null;
        try {
          insertToGfxdTableGenerateId(gConn, cid, sid, qty, status, time, ask, size, isPut);
        } catch (SQLException se) {
          if (SQLTest.setTx && isHATest && SQLHelper.gotTXNodeFailureException(se)) {
            Log.getLogWriter().warning("Got node failure exception during inserting using generated id to gfxd " +
                " abort the op");
            return;
          } 
          
          SQLHelper.printSQLException(se);
          gfxdEx = se;
        }
        
        if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
          Log.getLogWriter().info("Could not insert into gfxd due to node failure exception, abort this op");
          return;
        }
        
        String sql = "select max(oid) id from trade.sellorders where tid = " + getMyTid();
        boolean gotId = getGeneratedOid(gConn, oid, sql);
        while (!gotId) {
          gotId = getGeneratedOid(gConn, oid, sql);
        }
        
        int count = 0;
        while (!success) {
          if (count>=maxNumOfTries) {
            Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
            boolean deleted = removeOidFromGfxd(gConn, oid);
            while (!deleted) removeOidFromGfxd(gConn, oid);
            return;
          }
          MasterController.sleepForMs(rand.nextInt(retrySleepMs));
          exceptionList.clear();
          
          success = insertToDerbyTable(dConn, oid, cid, sid, qty, status, time, ask, size, exceptionList); 
 
          count++;
        }
        SQLException derbyEx = null;
        if (exceptionList.size()>0) derbyEx = exceptionList.get(0);
        SQLHelper.compareExceptions(derbyEx, gfxdEx);
      }
    }
    else {
      try {
        if (generateDefaultId)
          insertToGfxdTableGenerateId(gConn, cid, sid, qty, status, time, ask, size, isPut);
        else 
          insertToGFETable(gConn, oid, cid, sid, qty, status, time, ask, size, isPut); 
      } catch (SQLException se) {
        if (SQLTest.setTx && isHATest && SQLHelper.gotTXNodeFailureException(se)) {
          Log.getLogWriter().warning("Got node failure exception during inserting using generated id to gfxd ");
        } else {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to gfxd sellorders failed\n" + TestHelper.getStackTrace(se));      
        }
      }
      
    } //no verification
  }
  
  protected boolean getGeneratedOid(Connection conn, int[] id, String sql) {
    ResultSet rs = null;
    try {
      Log.getLogWriter().info(sql);
      rs = conn.createStatement().executeQuery(sql);
      if (rs.next()) {
        id[0] = rs.getInt("ID");
        Log.getLogWriter().info("Generated id is " + id[0]);
      } 
      if (rs.next()) {
        throw new TestException("In init task, query result from the query " + sql + 
            " should get only one results but it gets more rows " + rs.getInt("ID"));
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01")) return false; //does not get id
      else SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return true;
  }
  
  protected boolean removeOidFromGfxd(Connection gConn, int[] ids) {
    String sql = "delete from trade.sellorders where oid=?";
    try {
      PreparedStatement stmt = gConn.prepareStatement(sql);
      for (int oid: ids) {  
        Log.getLogWriter().info("gemfirexd - , deleting trade.sellorders with OID:" + oid + sql); 
        stmt.setInt(1, oid);
        stmt.executeUpdate();
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01")) return false; //does not get id
      else SQLHelper.handleSQLException(se);
    }
    return true;
  }
  
  //populate arrays for insert
  protected void getDataForInsert(Connection conn, int[] oid, int[] cid, int[] sid, 
      int[] qty, Timestamp[] time, BigDecimal[] ask, int size) {
    getOids(oid, size); //populate oids    
    for (int i=0; i<size; i++) {
      qty[i] = getQty(initMaxQty/2);
      time[i] = new Timestamp (System.currentTimeMillis());
      ask[i] =  new BigDecimal (Double.toString((rand.nextInt(10000)+1) * .01));  //random ask price      
    } 
    if (size >0 && rand.nextInt(100) == 1) {
      sid[0] = getSid();  //replace the first sid to be a random number, due to foreign key constraint (protfolion), this record may not be inserted
      Log.getLogWriter().info("sid is replaced with a randomly chosen one: " + sid[0]);
    }
  }

  //populate int[] of oids
  protected void getOids(int[] oid, int size) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.tradeSellOrdersPrimary, size);
    for (int i=0; i<size; i++) {
      oid[i]= (key-size+1)+i;
    }
  }  
  
  //get appropriate sid to be used for insert, and only the sid inserted by this tid will be chosen
  //from portfolio table (fk constraint) or 0 (if sid is 0, this record would not be inserted as it 
  //violates the fk constraint -- there is no cid = 0 in the securities table and thus no cid =0 in 
  //the portfolio table
  protected int getSid(Connection regConn, int cid) {
    Connection conn = getAuthConn(regConn);
    int sid =0; 
    int max = 5; 
    int n = rand.nextInt(max);  //randomly choose a number from 0 to max-1
    if (testUniqueKeys || testWanUniqueness) {
      ResultSet rs =null;
      try {
        //String s = "select sid from trade.portfolio where cid = " + cid + " and tid = " +getMyTid() ; //tid is not needed in where clause
        //rs = dConn.createStatement().executeQuery(s);
        int whichQuery =0;
        rs = TradePortfolioDMLStmt.getQuery(conn, whichQuery, null, null, 0, 0, 0, cid, getMyTid());
        if (rs == null) return 0; //return 0 as sid
        int temp=0;
        while (rs.next()) {
          if (temp == 0) {
            sid = rs.getInt("SID");
          } //default is to select the first sid if there is one
          if (n==temp) {
            sid = rs.getInt("SID");
            break;
          }//it may return a random sid in the portfolio for this customer (cid)
          temp++;          
        }
        rs.close();
      } catch (SQLException se) {
        if (!SQLHelper.checkGFXDException(conn, se)) return sid; //need retry or no op
        else SQLHelper.handleSQLException(se); //throws TestException.
      }      
    } //dConn will not be null
    else
      sid = getSid(); //randomly
    
    //Log.getLogWriter().info("in getSid and sid is " + sid);
    return sid;  //could return sid =0 
  }
  
  //insert into Derby
  protected boolean insertToDerbyTable(Connection conn, int[] oid, int[] cid, int[] sid,
      int[] qty, String status, Timestamp[] time, BigDecimal[] ask, int size, 
      List<SQLException> exceptions)  {    
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      PreparedStatement stmt = useDefaultValue? getStmt(conn, insertWithDefaultValue) : getStmt(conn, insert);
      if (stmt == null) return false;
      try {
      verifyRowCount.put(tid+"_insert"+i, 0);
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], ask[i], tid);
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se))
          return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }    
    }
    return true;
  }
  
  //insert into gemfirexd
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid,
      int[] qty, String status, Timestamp[] time, BigDecimal[] ask, int size, 
      List<SQLException> exceptions) {
    insertToGFETable(conn, oid, cid, sid, qty, status, time, ask, size, exceptions, false);
  }
  //insert into gemfirexd
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid,
      int[] qty, String status, Timestamp[] time, BigDecimal[] ask, int size, 
      List<SQLException> exceptions, boolean isPut) {
 
    int tid = getMyTid();
    int count = -1;
    for (int i=0 ; i<size ; i++) {
      PreparedStatement stmt = useDefaultValue ? getStmt(conn, (isPut ? putWithDefaultValue : insertWithDefaultValue)) 
                                               : getStmt(conn, isPut ? put : insert);
      if (SQLTest.testSecurity && stmt == null) {
      	SQLHelper.handleGFGFXDException((SQLException)
      			SQLSecurityTest.prepareStmtException.get(), exceptions);
      	SQLSecurityTest.prepareStmtException.set(null);
      	return;
      } //work around #43244
      if (setCriticalHeap && stmt == null) {
        return; //prepare stmt may fail due to XCL54 now
      }
      if (stmt == null && alterTableDropColumn) {
        Log.getLogWriter().info("prepare stmt failed due to missing column");
        return; //prepare stmt may fail due to alter table now
      } 
      if (stmt == null && SQLTest.setTx && isHATest) {
        Log.getLogWriter().info("prepare stmt failed due to node failure");
        return; //prepare stmt may fail due to tx no HA support yet
      } 
      if (stmt == null) {
        throw new TestException("Does not expect statement to be null, but it is.");
      }
      
      try {
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], ask[i], tid, isPut);
        if (count != (verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd insert has different row count from that of derby " +
            "derby inserted " + (verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      } catch (SQLException se) {
        /* does not expect 0A000 with put with no unique key column. 
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
        */
        SQLHelper.handleGFGFXDException(se, exceptions);
      }
    } 
  }
  
  protected void insertToGfxdTableGenerateId(Connection conn, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] ask, int size) throws SQLException {
    insertToGfxdTableGenerateId(conn, cid, sid, qty, status, time, ask, size, false);
  }
  protected void insertToGfxdTableGenerateId(Connection conn, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] ask, int size, boolean isPut) throws SQLException {
    PreparedStatement stmt = useDefaultValue
        ? getStmt(conn, (isPut ? putWithGeneratedDefaultIdAndDefaultValue : insertWithGeneratedDefaultIdAndDefaultValue)) 
        : getStmt(conn, isPut ? putWithGeneratedDefaultId : insertWithGeneratedDefaultId);
    //PreparedStatement stmt = getStmt(conn, insert);
    if (setCriticalHeap && stmt == null) {
      return; //prepare stmt may fail due to XCL54 now
    }
    if (stmt == null && SQLTest.setTx && isHATest) {
      Log.getLogWriter().info("prepare stmt failed due to node failure");
      return; //prepare stmt may fail due to tx no HA support yet
    } 
    if (stmt == null) {
      throw new TestException("Does not expect statement to be null, but it is.");
    }
    
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      insertToTableWithDefaultId(stmt, cid[i], sid[i], qty[i], status, time[i], ask[i], tid, isPut);
    }      
  }
  
  protected int insertToTableWithDefaultId(PreparedStatement stmt, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal ask, int tid, boolean isPut) throws SQLException {
    
    Log.getLogWriter().info("gemfirexd - " + (isPut ? "Putting" : "inserting") + " into trade.sellorders with " +
    		"CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
        ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
    stmt.setInt(1, cid);
    stmt.setInt(2, sid);
    stmt.setInt(3, qty);
    stmt.setBigDecimal(4, ask);
    if (testworkaroundFor51519) stmt.setTimestamp(5, time, getCal());
    else stmt.setTimestamp(5, time);
    if (useDefaultValue) {
      stmt.setInt(6, tid); 
    } else {
      stmt.setString(6, status);
      stmt.setInt(7, tid);
    }
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info("gemfirexd - " + (isPut ? "Put " : "inserted ") + rowCount + " rows into trade.sellorders " +
        "CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
     ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }

  //insert into gemfirexd/gfe w/o verification
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] ask, int size, boolean isPut)  {
    PreparedStatement stmt = useDefaultValue
        ? getStmt(conn, isPut ? putWithDefaultValue : insertWithDefaultValue) 
            : getStmt(conn, isPut ? put : insert);
    //PreparedStatement stmt = getStmt(conn, insert);
    if (setCriticalHeap && stmt == null) {
      return; //prepare stmt may fail due to XCL54 now
    }
    if (stmt == null && alterTableDropColumn) {
      Log.getLogWriter().info("prepare stmt failed due to missing column");
      return; //prepare stmt may fail due to alter table now
    } 
    if (stmt == null && SQLTest.setTx && isHATest) {
      Log.getLogWriter().info("prepare stmt failed due to node failure");
      return; //prepare stmt may fail due to tx no HA support yet
    } 
    if (stmt == null) {
      throw new TestException("Does not expect statement to be null, but it is.");
    }
    
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      try {
        insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], ask[i], tid, isPut);
      } catch (SQLException se) {
        if (se.getSQLState().equals("23503"))  
          Log.getLogWriter().info("detected the foreign key constraint violation, continuing test");
        else if (se.getSQLState().equals("42500") && testSecurity) 
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        else if (alterTableDropColumn && se.getSQLState().equals("42802")) {
          Log.getLogWriter().info("Got expected column not found exception in insert, continuing test");
        } else if (se.getSQLState().equals("23505")
            && isHATest && SQLTest.isEdge) {
          Log.getLogWriter().info("detected pk constraint violation during insert -- relaxing due to #43571, continuing test");
        } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } 
        else if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
          SQLHelper.handleSQLException(se); //handle the exception
      }
    }    
  }

  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal ask, int tid) throws SQLException {
    return insertToTable(stmt, oid, cid, sid, qty, status, time, ask, tid, false);
  }
  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal ask, int tid, boolean isPut) throws SQLException {
    
    String txId = SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " + txId;
    
    Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.sellorders with OID:" + oid+
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
     ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
    String driverName = stmt.getConnection().getMetaData().getDriverName();
    stmt.setInt(1, oid);
    stmt.setInt(2, cid);
    stmt.setInt(3, sid);
    stmt.setInt(4, qty);
    stmt.setBigDecimal(5, ask);
    if (testworkaroundFor51519) stmt.setTimestamp(6, time, getCal());
    else stmt.setTimestamp(6, time);
    if (useDefaultValue) {
      stmt.setInt(7, tid); //uncommented now as #39727 is solved
      //stmt.setString(7, status);
      //stmt.setInt(8, tid);
    } else {
    stmt.setString(7, status);
    stmt.setInt(8, tid);
    }
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info(database + (isPut ? "Put " : "inserted ") + rowCount + " rows into trade.sellorders OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
     ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    
    if ( driverName.toLowerCase().contains("gemfirexd") && isPut) {
      if (! SQLTest.ticket49794fixed) {
        insertToSellordersFulldataset(stmt.getConnection() , oid, cid, sid, qty, ask, time, status, tid);
        }  
      
      Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.sellorders with OID:" + oid+
          ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
       ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
      
     rowCount = stmt.executeUpdate();
     
     Log.getLogWriter().info(database + (isPut ? "Put " : "inserted ") + rowCount + " rows into trade.sellorders OID:" + oid+
         ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
      ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
    }  
    
    return rowCount;
  }
  
  
  protected void insertToSellordersFulldataset (Connection conn, int oid, int cid, int sid, int qty, BigDecimal ask, Timestamp time, String status , int tid){

    //manually update fulldataset table for above entry.
     try{
      
       Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence deleting  the  row  from TRADE.SELLORDERS_FULLDATASET with data OID:" +  oid );
       conn.createStatement().execute("DELETE FROM TRADE.SELLORDERS_FULLDATASET  WHERE  oid = "  + oid);      

      PreparedStatement preparedInsertStmt = conn.prepareStatement("insert into trade.SELLORDERS_fulldataset values (?,?,?,?,?,?,?,?)");          
      
      preparedInsertStmt.setInt(1, oid);
      preparedInsertStmt.setInt(2, cid);
      preparedInsertStmt.setInt(3, sid);
      preparedInsertStmt.setInt(4, qty);
      preparedInsertStmt.setBigDecimal(5, ask);
      if (testworkaroundFor51519) preparedInsertStmt.setTimestamp(6, time, getCal());
      else preparedInsertStmt.setTimestamp(6, time);
      if (useDefaultValue) {
        preparedInsertStmt.setInt(7, tid); 
      } else {
        preparedInsertStmt.setString(7, status);
        preparedInsertStmt.setInt(8, tid);
      }
     
      Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence inserting  the  row  into  TRADE.SELLORDERS_FULLDATASET with CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
      ",TIME:"+ time + ",ASK:" + ask + ",TID:" + tid);
      preparedInsertStmt.executeUpdate();
     } catch (SQLException se) {
       Log.getLogWriter().info("Error while updating TRADE.SELLORDERS_FULLDATASET table. It may cause Data inconsistency " + se.getMessage() ); 
     }
  }
  
  //populate the status array
  protected void getStatus(String[] status) {
    for (int i=0; i<status.length; i++) {
      status[i]= statuses[rand.nextInt(statuses.length)];
    }
  }
  
  //populate the ask price array
  protected void getAsk(BigDecimal[] ask) {
    for (int i=0; i<ask.length; i++) {
      ask[i]= getPrice();
    }
  }
  
  //for query IN (?,?,?,?,?) primary keys for get all
  protected void getOids(int[] oid) {
    int startOid =  rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSellOrdersPrimary) + 1);
    for (int i=0; i<oid.length; i++) {
      oid[i]= startOid++;
    }
  }
  
  //return a Date used in query
  protected Timestamp getRandTime() {
    return new Timestamp(System.currentTimeMillis()-rand.nextInt(3*60*1000)); //randomly choose in the last 3 minutes
  }
  
  protected boolean getDataForUpdate(Connection regConn, int[] cid, int[] sid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] ask, BigDecimal[] ask2, int[] whichUpdate, int size) {
    Connection conn = getAuthConn(regConn);
    int numOfNonUniq = 4;
    if (!getDataFromResult(conn, cid, sid)) return false; //did not get data
    for (int i=0; i<size; i++) {
      qty[i] = getQty();
      orderTime[i] = getRandTime();
      ask[i] = getPrice();
      ask2[i] = getPrice();
      whichUpdate[i] = getWhichUpdate(numOfNonUniq, update.length);
    }
    return true;
  }
  
  protected int getWhichUpdate(int numOfNonUniq, int total) {
    int whichOne = getWhichOne(numOfNonUniq, total);
    if (setCriticalHeap && testUniqueKeys && !hasTx) {
      whichOne = 2; //to avoid #39605
    }
    
    return whichOne;
  }
  
  protected boolean getDataFromResult(Connection conn, int[] cid, int[] sid) {
    int tid = getMyTid(); 
    if (testUniqueKeys || testWanUniqueness) {
      ; //do nothing
    } else {
      if (tid == 0)  ; //do nothing
      else tid = rand.nextInt(tid);  //data inserted from any tid
    }     
      
    return getDataFromResult(conn, cid, sid, tid);
  }
  
  protected boolean getDataFromResult(Connection conn, int[] cid, int[] sid, int tid) {
    int defaultCid =0; 
    int defaultSid =0; 
    int size = cid.length;
    int num = 20; //used to randomly return the cid, sid pair
    int n = rand.nextInt(num);
    //Log.getLogWriter().info("n is " + n);
    ResultSet rs =null;

    try {  
      int whichQuery = 0;
      rs = getQuery(conn, whichQuery, null, null, null, null, null, tid); 
      if (rs == null) {
          return false; //already did retry in getQuery
      }

      int i=0;
      int temp = 0;
      while (rs.next() && i<size) {
        if (temp ==0 ) {
          cid[i] = rs.getInt(str_cid);
          sid[i] = rs.getInt(str_sid);
        } else if (n<=temp) {
          cid[i] = rs.getInt(str_cid);
          sid[i] = rs.getInt(str_sid);
          i++;
        }
        temp++;             
      } //update cids with result set
      rs.close();
      while (i<size) {
        cid[i] = defaultCid;  //cid is 0
        sid[i] = defaultSid; //sid is 0
        i++;
      } //continues to update cids with cid=0
    } catch (SQLException se) {
      if (!SQLHelper.checkGFXDException(conn, se)) return false; //need retry or no op
      if (SQLHelper.isAlterTableException(conn, se)) return false; //need retry or no op
      else SQLHelper.handleSQLException(se); //throws TestException.
    }
    return true;
  }
  

  
  protected boolean updateDerbyTable (Connection conn, int[] cid, int[] sid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] ask, BigDecimal[] ask2, int[] whichUpdate, 
      int size, List<SQLException> exList) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      boolean[] unsupported = new boolean[1];
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i], unsupported);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
      if (stmt == null) {
        if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true)
          return true; //do the same in gfxd to get alter table exception
        else if (unsupported[0]) return true; //do the same in gfxd to get unsupported exception
        else return false;
        /*
        try {
      		conn.prepareStatement(update[whichUpdate[i]]);
      	} catch (SQLException se) {
      	  if (se.getSQLState().equals("08006") || se.getSQLState().equals("08003"))
            return false;
        } 
        */
        //this test of connection is lost is necessary as stmt is null
        //could be caused by not allowing update on partitioned column. 
        //the test of connection lost could be removed after #39913 is fixed
        //just return false if stmt is null
      }
      
      try {
        if (stmt !=null) {
          verifyRowCount.put(tid+"_update"+i, 0);
          count = updateTable(stmt, cid[i], sid[i], qty[i], orderTime[i], ask[i], ask2[i], tid, whichUpdate[i] );
          verifyRowCount.put(tid+"_update"+i, new Integer(count));
          
        } 
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) { //handles the deadlock of aborting
          return false;
        } else
            SQLHelper.handleDerbySQLException(se, exList);
      }    
    }  
    return true;
  }
  
  
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate, boolean[] unsupported){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectStmt(conn, whichUpdate, partitionKeys, unsupported);
  }
  
  @SuppressWarnings("unchecked")
  protected void setPartitionKeys() {
    if (!isWanTest) {
      partitionKeys= (ArrayList<String>)partitionMap.get("sellordersPartition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_sellordersPartition");
    }
    Log.getLogWriter().info("partition keys are " + partitionKeys);
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys, boolean[] unsupported){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      // "update trade.sellorders set status = 'filled'  where sid = ? and ask<? and status = 'open' and tid = ? ", 
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      //"update trade.sellorders set status = 'cancelled' where order_time >? and status = 'open' and tid =? ",
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      //"update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? ",
      if (partitionKeys.contains("ask")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      //"update trade.sellorders set ask = ? , qty=? where cid = ? and sid= ? and ask <? and status = 'open' and tid =? ", 
      if (partitionKeys.contains("ask") || partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 4: 
      //"update trade.sellorders set status = 'filled'  where sid = ? and ask<? and status = 'open' ", 
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 5: 
      // "update trade.sellorders set status = 'cancelled' where order_time <? and status = 'open'  ", 
      if (partitionKeys.contains("status")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 6: 
      //  "update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? ", 
      if (partitionKeys.contains("ask")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 7: 
      //"update trade.sellorders set ask = ? , qty=? where cid = ? and sid= ? and ask <? and status = 'open'  ",
      if (partitionKeys.contains("ask") || partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  //check expected exceptions
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] ask, BigDecimal[] ask2, int[] whichUpdate, 
      int size, List<SQLException> exList) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i], null);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
      if (SQLTest.testSecurity && stmt == null) {
      	if (SQLSecurityTest.prepareStmtException.get() != null) {
      	  SQLHelper.handleGFGFXDException((SQLException)
      			SQLSecurityTest.prepareStmtException.get(), exList);
      	  SQLSecurityTest.prepareStmtException.set(null);
      	  return;
      	}
      } //work around #43244
      if (setCriticalHeap && stmt == null) {
        return; //prepare stmt may fail due to XCL54 now
      }
      
      try {
        if (stmt!=null) {
          count = updateTable(stmt, cid[i], sid[i], qty[i], orderTime[i], ask[i], ask2[i], tid, whichUpdate[i] );
          if (count != (verifyRowCount.get(tid+"_update"+i)).intValue()){
            String str = "Gfxd update has different row count from that of derby " +
                    "derby updated " + (verifyRowCount.get(tid+"_update"+i)).intValue() +
                    " but gfxd updated " + count;
            if (failAtUpdateCount && !isHATest) throw new TestException (str);
            else Log.getLogWriter().warning(str);
          }
        }
      } catch (SQLException se) {
         SQLHelper.handleGFGFXDException(se, exList);
      }    
    }  
  }
  
  //w/o verification
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] ask, BigDecimal[] ask2, int[] whichUpdate, int size) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i], null);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed

      try {
        if (stmt!=null)
          updateTable(stmt, cid[i], sid[i], qty[i], orderTime[i], ask[i], ask2[i], tid, whichUpdate[i] );
      } catch (SQLException se) {
        if (se.getSQLState().equals("42502") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
          " continuing tests");
        } else if (alterTableDropColumn && (se.getSQLState().equals("42X14") || se.getSQLState().equals("42X04"))) {
          //42X04 is possible when column in where clause is dropped
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } else SQLHelper.handleSQLException(se);
      }    
    }  
  }
  
  protected int updateTable (PreparedStatement stmt, int cid, int sid, int qty, 
      Timestamp orderTime, BigDecimal ask, BigDecimal ask2, int tid, int whichUpdate) throws SQLException {
    int rowCount = 0; 
    String txId = SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " +txId ;
    String query = " QUERY: " + update[whichUpdate];
    
    switch (whichUpdate) {
    case 0: 
      //"update trade.sellorders set status = 'filled'  where sid = ? and ask>? and status = 'open' and tid = ? ",  //for trigger test it could be a batch update
      Log.getLogWriter().info(database + "updating trade.sellorders  with SID:" + sid  + ",ASK:" + ask +
          " where ,STATUS:open,TID:" + tid + query);
      stmt.setInt(1, sid);
      stmt.setBigDecimal(2, ask);      
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders SID:" + sid  + ",ASK:" + ask +
          " where ,STATUS:open,TID:" + tid + ",STATUS:" + "open" + query);
      break;
    case 1: 
      //"update trade.sellorders set status = 'cancelled' where order_time <? and status = 'open' and tid =? ",  //batch operation
      Log.getLogWriter().info(database +  "updating sellorders  with STATUS:cancelled where  ORDERTIME:" + orderTime +  ",STATUS:" + "open"  + ",TID: " + tid + query);
      if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal());
      else stmt.setTimestamp(1, orderTime);
      stmt.setInt(2, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with STATUS:cancelled where  ORDERTIME:" + orderTime +  ",STATUS:" + "open"  + ",TID: " + tid + query);
      break;
    case 2: 
      //"update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? ",
      Log.getLogWriter().info(database + "updating tarade.sellorders  with ASK:" + ask + " and CID:" + cid 
          + ",STATUS:open,SID:" + sid + ",QTY:" + qty + ",TID:" + tid + query);
      stmt.setBigDecimal(1, ask);
      stmt.setInt(2, cid);
      stmt.setInt(3, sid);
      stmt.setInt(4, qty);
      stmt.setInt(5, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders ASK:" + ask + " where CID:" + cid 
          + ",STATUS:open,SID:" + sid + ",QTY:" + qty + ",TID:" + tid + query);
      break;
    case 3: 
      //"update trade.sellorders set ask = ? , qty=? where cid = ? and sid= ? and ask <? and status = 'open' and tid =? ",
      Log.getLogWriter().info(database + "updating trade.sellorders  with ASK:" + ask + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",ASK:" + ask + ",STATUS:open,TID:" + tid + query);
      stmt.setBigDecimal(1, ask);
      stmt.setInt(2, qty);
      stmt.setInt(3, cid);
      stmt.setInt(4, sid);
      stmt.setBigDecimal(5, ask2);
      stmt.setInt(6, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with ASK:" + ask + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",ASK:" + ask + ",STATUS:open,TID:" + tid + query);
      break;
    case 4:
      //"update trade.sellorders set status = 'filled'  where sid = ? and ask>? and status = 'open' ",  //for trigger test it could be a batch update
      Log.getLogWriter().info(database + "updating trade.sellorders  with STATUS:filled where SID:" + sid  + " ASK: " + ask + query);
      stmt.setInt(1, sid);
      stmt.setBigDecimal(2, ask);      
      rowCount=stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders  with STATUS:filled where SID:" + sid  + " ASK: " + ask + query);
      break;
    case 5: 
      //"update trade.sellorders set status = 'cancelled' where order_time <? and status = 'open'  ",  //batch operation
      Log.getLogWriter().info(database + "updating trade.sellorders  with STATUS:filled where ORDERTIME:" + orderTime +",STATUS:open" + query);
      if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal());
      else stmt.setTimestamp(1, orderTime);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with STATUS:filled where ORDERTIME:" + orderTime +",STATUS:open" + query);
      break;
    case 6: 
      //"update trade.sellorders set ask = ? where cid = ? and sid= ? and status = 'open' and qty >? ",
      Log.getLogWriter().info(database + "updating trade.sellorders  with ASK:" + ask + " where CID:" + cid 
          + ", sid: " + sid + ", qty: " + qty + query);
      stmt.setBigDecimal(1, ask);
      stmt.setInt(2, cid);
      stmt.setInt(3, sid);
      stmt.setInt(4, qty);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders  with ASK:" + ask + " where CID:" + cid 
          + ", sid: " + sid + ", qty: " + qty + query);
      break;
    case 7: 
      //"update trade.sellorders set ask = ? and qty=? where cid = ? and sid= ? and ask <? and status = 'open'  ",  
      Log.getLogWriter().info(database + "updating trade.sellorders with ASK: " + ask + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",ASK:" + ask2 + ",STATUS:open" + query);
      stmt.setBigDecimal(1, ask);
      stmt.setInt(2, qty);
      stmt.setInt(3, cid);
      stmt.setInt(4, sid);
      stmt.setBigDecimal(5, ask2);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with ASK: " + ask + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",ASK:" + ask2 + ",STATUS:open" + query);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }
  
  //will retry
  public static ResultSet getQuery(Connection conn, int whichQuery, String[] status, 
      BigDecimal[] ask, int[] cid, int[] oid, Timestamp orderTime, int tid) {
    boolean[] success = new boolean[1];
    int count = 0;
    ResultSet rs = null;
    try {
      rs = getQuery(conn, whichQuery, status, ask, cid, oid, orderTime, tid, success);
      while (!success[0]) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
          return null; 
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++;   
        MasterController.sleepForMs(100 * rand.nextInt(10)); //sleep from 0 - 900 ms
        rs = getQuery(conn, whichQuery, status, ask, cid, oid, orderTime, tid, success);
      } //retry 
    } catch (SQLException se) {
      if (!SQLHelper.isAlterTableException(conn, se)) SQLHelper.handleSQLException(se);
      //allow alter table related exceptions.
    }
    return rs;
  }
  
  protected static ResultSet query(Connection conn, int whichQuery, String[] status, 
      BigDecimal[] ask, int[] cid, int[] oid, Timestamp orderTime, int tid) 
      throws SQLException {
    boolean[] success = new boolean[1];
    int count = 0;
    ResultSet rs = getQuery(conn, whichQuery, status, ask, cid, oid, orderTime, tid, success);
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
        return null; 
      }
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      count++;   
      MasterController.sleepForMs(100 * rand.nextInt(10)); //sleep from 0 - 900 ms
      rs = getQuery(conn, whichQuery, status, ask, cid, oid, orderTime, tid, success);
    } //retry 
    return rs;
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, String[] status, 
      BigDecimal[] ask, int[] cid, int[] oid, Timestamp orderTime, int tid, 
      boolean[] success) throws SQLException {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    String txId = SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - " + txId;
    String query = " QUERY: " + select[whichQuery];
    
    boolean isTicket46930Fixed = true;    
    if (!isTicket46930Fixed && whichQuery == 15) --whichQuery;
    
    Log.getLogWriter().info(database + query);
    try {
      stmt = conn.prepareStatement(select[whichQuery]);
      
      /*
      stmt = getStmt(conn, select[whichQuery]);
      if (setCriticalHeap && stmt == null) {
        return null; //prepare stmt may fail due to XCL54 now
      }
      */
      
      switch (whichQuery){
      case 0:
        //"select * from trade.sellorders where tid = ?",
        Log.getLogWriter().info( database + "querying trade.sellorders with TID:" +tid + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select cid, ask, sid from trade.sellorders where cid >? and tid = ?",
        Log.getLogWriter().info( database + "querying trade.sellorders with CID:" + cid[0] + ",TID: " + tid  + query);   
        stmt.setInt(1, cid[0]);
        stmt.setInt(2, tid); 
        break;
      case 2:
        //"select oid, sid from trade.sellorders where (ask<? or ask >=?) and tid =?",
        Log.getLogWriter().info(database + "querying trade.sellorders with 1_ASK:" + ask[0] 
            + ",TID:" + tid + ",2_ASK:" + ask[1] + ",TID:" +tid + query);   
        stmt.setBigDecimal(1, ask[0]);
        stmt.setInt(2, tid);
        stmt.setBigDecimal(3, ask[1]);
        stmt.setInt(4, tid);
        break;
      case 3:
        // "select oid, sid, ask from trade.sellorders  where status =? and cid =? and tid =?",
        Log.getLogWriter().info(database + "querying trade.sellorders with STATUS:" + status[0] + ",CID:" + cid[0] 
            + ",TID:" +tid + query);   
        stmt.setString(1, status[0]);
        stmt.setInt(2, cid[0]);
        stmt.setInt(3, tid);
        break;
      case 4:
        //    "select oid, sid, ask from trade.sellorders  " +
        // "where ((status =? and ask <?) OR (status =? and ask >?))  and cid IN {?, ?, ?, ?, ?} and tid =?", 
       Log.getLogWriter().info(database + "querying trade.sellorders with 1_STATUS:" + status[0] + ",1_ASK:" 
            + ask[0] + ",2_STATUS:" + status[1] + ",2_ASK:"  + ask[1] + ",1_CID:" +
            cid[0] + ",2_CID:" + cid[1] + "3_CID:" + cid[2] + ",4_CID:" + cid[3] + "5_CID:" + cid[4]  + " ,TID:" +tid + query);
        stmt.setString(1, status[0]);
        stmt.setBigDecimal(2, ask[0]);
        stmt.setString(3, status[1]);
        stmt.setBigDecimal(4, ask[1]);
        stmt.setInt(5, cid[0]);
        stmt.setInt(6, cid[1]);
        stmt.setInt(7, cid[2]);
        stmt.setInt(8, cid[3]);
        stmt.setInt(9, cid[4]);
        stmt.setInt(10, tid);
        break;
      case 5:
       // "select oid, sid, ask from trade.sellorders  " +
       // "where ((order_time >? and ask <?) and (status =? and ask >?))  OR cid IN (?, ?, ?, ?, ?) and tid =?",
        Log.getLogWriter().info(database + "querying trade.sellorders with ORDERTIME:" + orderTime + ",1_ASK:" 
            + ask[0] + ",STATUS:" + status[1] + "2_ASK:"  + ask[1] + ",1_CID:" +
            cid[0] + ",2_CID: " + cid[1] + "3_CID:" + cid[2] + ",4_CID:" + cid[3] + ",5_CID:" + cid[4]  + ",TID:" +tid + query);
        if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal());
        else stmt.setTimestamp(1, orderTime);
        stmt.setBigDecimal(2, ask[0]);
        stmt.setString(3, status[1]);
        stmt.setBigDecimal(4, ask[1]);
        stmt.setInt(5, cid[0]);
        stmt.setInt(6, cid[1]);
        stmt.setInt(7, cid[2]);
        stmt.setInt(8, cid[3]);
        stmt.setInt(9, cid[4]);
        stmt.setInt(10, tid);
        break;
      case 6: 
        //"select oid, cid, order_time, sid from trade.sellorders where (order_time > ?) and tid =? ",
        Log.getLogWriter().info(database + "querying trade.sellorders with ORDERTIME:" + orderTime + ",TID:" +tid + query);   
        if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal()); 
        else stmt.setTimestamp(1, orderTime);
        stmt.setInt(2, tid);
        break;
      case 7:
        //"select * from trade.sellorders where oid IN (?, ?, ?, ?, ?)  and tid =?  ",
        Log.getLogWriter().info(database + "querying trade.sellorders with 1_OID:"+
            oid[0] + ",2_OID:" + oid[1] + ",3_OID:" + oid[2] + ",4_OID:" + oid[3] + ",5_OID:" + oid[4] + query);   
        for (int i=1; i<6; i++)
          stmt.setInt(i, oid[i-1]); 
        
        stmt.setInt(6, tid);
        break;
      case 8:
        //"select * from trade.sellorders",
        Log.getLogWriter().info(database + "querying trade.sellorders with no parameter");
        break;
      case 9: 
        //"select cid, ask, sid from trade.sellorders where cid >? ",
        Log.getLogWriter().info(database + "querying trade.sellorders with CID:" + cid[0] + query);   
        stmt.setInt(1, cid[0]);
        break;
      case 10:
        //"select oid, sid from trade.sellorders where (ask<? or ask >=?) ",
        Log.getLogWriter().info(database + "querying trade.sellorders with 1_ASK:" + ask[0] 
            + ",2_ASK:" + ask[1] + query);   
        stmt.setBigDecimal(1, ask[0]);
        stmt.setBigDecimal(2, ask[1]);
        break;
      case 11:
        // "select oid, sid, ask from trade.sellorders  where status =? and cid =? ",
        Log.getLogWriter().info(database + "querying trade.sellorders with STATUS:" + status[0] + ",CID:" + cid[0] + query);   
        stmt.setString(1, status[0]);
        stmt.setInt(2, cid[0]);
        break;
      case 12:
        //    "select oid, sid, ask from trade.sellorders  " +
        // "where ((status =? and ask <?) OR (status =? and ask >?))  and cid IN {?, ?, ?, ?, ?}", 
        Log.getLogWriter().info(database + "querying trade.sellorders with 1_STATUS:" + status[0] + ",1_ASK:" 
            + ask[0] + ":2_STATUS:" + status[1] + ",2_ASK:"  + ask[1] + ",1_CID:" +
            cid[0] + ",2_CID:" + cid[1] + ",3_CID:" + cid[2] + ",4_CID:" + cid[3] + ",5_CID:" + cid[4] + query);
        stmt.setString(1, status[0]);
        stmt.setBigDecimal(2, ask[0]);
        stmt.setString(3, status[1]);
        stmt.setBigDecimal(4, ask[1]);
        stmt.setInt(5, cid[0]);
        stmt.setInt(6, cid[1]);
        stmt.setInt(7, cid[2]);
        stmt.setInt(8, cid[3]);
        stmt.setInt(9, cid[4]);
        break;
      case 13:
        // "select oid, sid, ask from trade.sellorders  " +
        // "where ((order_time >? and ask <?) and (status =? and ask >?))  OR cid IN (?, ?, ?, ?, ?)",
         Log.getLogWriter().info(database + "querying trade.sellorders with ORDERTIME:" + orderTime + ",1_ASK:" 
             + ask[0] + ",STATUS:" + status[1] + ",2_ASK:"  + ask[1] + ",1_CID:" +
             cid[0] + ",2_CID:" + cid[1] + ",3_CID:" + cid[2] + ",4_CID:" + cid[3] + ",5_CID:" + cid[4] + query );
         if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal());
         else stmt.setTimestamp(1, orderTime);
         stmt.setBigDecimal(2, ask[0]);
         stmt.setString(3, status[1]);
         stmt.setBigDecimal(4, ask[1]);
         stmt.setInt(5, cid[0]);
         stmt.setInt(6, cid[1]);
         stmt.setInt(7, cid[2]);
         stmt.setInt(8, cid[3]);
         stmt.setInt(9, cid[4]);
         break;
       case 14: 
        //"select oid, cid, order_time, sid from trade.sellorders where (order_time > ?) ",
        Log.getLogWriter().info(database + "querying trade.sellorders with ORDERTIME:" + orderTime + query);   
        if (testworkaroundFor51519) stmt.setTimestamp(1, orderTime, getCal()); 
        else stmt.setTimestamp(1, orderTime);
        break;
       case 15: 
         //""select * from trade.sellorders where oid IN (?, ?, ?, ?, ?) "
         Log.getLogWriter().info(database + "querying trade.sellorders with 1_OID:" +
             oid[0] + ",2_OID:" + oid[1] + ",3_OID:" + oid[2] + ",4_OID:" + oid[3] + ",5_OID:" + oid[4] + query);   
         for (int i=1; i<6; i++)
           stmt.setInt(i, oid[i-1]); 
         break;
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire lokc or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471
      else throw se;
    }
    return rs;
  } 
  
  //*** for delete ***//
  protected int getDataForDelete(Connection conn, int[] cid, int[] sid) {
    int tid = getMyTid();  //for test unique key case
    if (testUniqueKeys || testWanUniqueness) {
      ; //do nothing
    } else {
      if (tid == 0)  ; //do nothing
      else tid = rand.nextInt(tid);  //data inserted from any tid
    }     
      
    return getDataForDelete(conn, cid, sid, tid);    
  }
  
  protected int getDataForDelete(Connection regConn, int[] cid, int[] sid, int tid) {
    Connection conn = getAuthConn(regConn);
    int availSize = cid.length;
    int firstQuery = 0; //first query is select *
    ResultSet rs = null;
    int[] offset = new int[1];      

    rs = getQuery(conn, firstQuery, null, null, null, null, null, tid);
      //get the resultSet of sellorders inserted by this thread
   
    if (rs==null && SQLHelper.isDerbyConn(conn)) {
      Log.getLogWriter().info("Not able to get data for delete");
      return 0;
    } //query may terminated earlier to get sids if not able to obtain lock
    
    ArrayList<Struct> result = (ArrayList<Struct>) ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    if (result == null) {
      Log.getLogWriter().info("did not get result set");
      return 0;
    } else {
      availSize = getAvailSize(result, cid.length, offset);
    }
   
    for (int i =0 ; i<availSize; i++) {
      cid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("CID")).intValue();
      sid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("SID")).intValue();   
    }
   
    return availSize;
  }
  
  //add the exception to expceptionList to be compared with gfe
  protected boolean deleteFromDerbyTable(Connection dConn, int whichDelete, 
      int[]cid, int []sid, int[] oid, List<SQLException> exList){    
    int tid = getMyTid();
    int count = -1;
    
    try {
      PreparedStatement stmt = dConn.prepareStatement(delete[whichDelete]); 
      if (stmt == null) return false;
      for (int i=0; i<cid.length; i++) {
      verifyRowCount.put(tid+"_delete_"+i, 0);
        count = deleteFromTable(stmt, cid[i], sid[i], oid[i], tid, whichDelete);
        verifyRowCount.put(tid+"_delete_"+i, new Integer(count));
        
      } 
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(dConn, se))
        return false;
      else SQLHelper.handleDerbySQLException(se, exList); //handle the exception
    }
    return true;
  }
  
  //compare whether the exceptions got are same as those from derby
  protected void deleteFromGFETable(Connection gConn, int whichDelete, int[]cid,
      int []sid, int[] oid, List<SQLException> exList){
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]); 
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exList);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
    if (setCriticalHeap && stmt == null) {
      return; //prepare stmt may fail due to XCL54 now
    }
    if (stmt == null && alterTableDropColumn) {
      Log.getLogWriter().info("prepare stmt failed due to missing column");
      return; //prepare stmt may fail due to alter table now
    } 
    if (stmt == null && SQLTest.setTx && isHATest) {
      Log.getLogWriter().info("prepare stmt failed due to node failure");
      return; //prepare stmt may fail due to tx no HA support yet
    } 
    if (stmt == null) {
      throw new TestException("Does not expect statement to be null, but it is.");
    }
    
    int tid = getMyTid();
    int count = -1;
    
    try {
      for (int i=0; i<cid.length; i++) {
        count = deleteFromTable(stmt, cid[i], sid[i], oid[i], tid, whichDelete);
        if (count != (verifyRowCount.get(tid+"_delete_"+i)).intValue()){
          String str = "Gfxd delete (sellorders) has different row count from that of derby " +
                  "derby deleted " + (verifyRowCount.get(tid+"_delete_"+i)).intValue() +
                  " but gfxd deleted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleGFGFXDException(se, exList); //handle the exception
    }
  }
  
  //no verification
  protected void deleteFromGFETable(Connection gConn, int whichDelete, int[] cid,
      int[] sid, int[] oid){
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]);     
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	} else Log.getLogWriter().warning("does not get stmt"); //need to find out why stmt is not obtained
    } //work around #43244 
    if (setCriticalHeap && stmt == null) {
      return; //prepare stmt may fail due to XCL54 now
    }
    if (stmt == null && alterTableDropColumn) {
      Log.getLogWriter().info("prepare stmt failed due to missing column");
      return; //prepare stmt may fail due to alter table now
    } 
    if (stmt == null && SQLTest.setTx && isHATest) {
      Log.getLogWriter().info("prepare stmt failed due to node failure");
      return; //prepare stmt may fail due to tx no HA support yet
    } 
    if (stmt == null) {
      throw new TestException("Does not expect statement to be null, but it is.");
    }
    
    int tid = getMyTid();
    
    try {
      for (int i=0; i<cid.length; i++) {
        deleteFromTable(stmt, cid[i], sid[i], oid[i], tid, whichDelete);
      }
    } catch (SQLException se) {
      if ((se.getSQLState().equals("42500") || se.getSQLState().equals("42502"))
          && testSecurity) {
        Log.getLogWriter().info("Got the expected exception for authorization," +
           " continuing tests");
      } else if (alterTableDropColumn && (se.getSQLState().equals("42X14") || se.getSQLState().equals("42X04"))) {
        //42X04 is possible when column in where clause is dropped
        Log.getLogWriter().info("Got expected column not found exception in delete, continuing test");
      } else SQLHelper.handleSQLException(se); //handle the exception
    }
  }
  
  //delete from table based on whichDelete
  protected int deleteFromTable(PreparedStatement stmt, int cid, int sid, 
      int oid, int tid, int whichDelete) throws SQLException {
    String txId = SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " + txId ;
    String query = " QUERY: " + delete[whichDelete];
    
    int rowCount = 0;
    switch (whichDelete) {
    case 0:   
      //"delete from trade.sellorders where cid=? and sid=? and tid=?", //could be batch delete, but no foreign key reference, this should work    
      Log.getLogWriter().info(database + "deleting trade.sellorders with CID:" + cid + ",SID:" + sid 
                + ",TID:" + tid  + query);  
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with CID:" + cid + ",SID:" + sid 
                + ",TID:" + tid  + query); 
      break;
    case 1:
      //"delete from trade.sellorders where oid=? and tid=?",
      Log.getLogWriter().info(database + "deleting trade.sellorders with OID:" + oid 
                + ",TID:" + tid + query);  
      stmt.setInt(1, oid);
      stmt.setInt(2, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with OID:" + oid 
                + ",TID:" + tid + query);
      break;
    case 2:   
      //"delete from trade.sellorders where status IN ('cancelled', 'filled') and tid=?",
      Log.getLogWriter().info(database + "deleting trade.sellorders with TID:" + tid + query);  
      stmt.setInt(1, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with TID:" + tid + query);
      break;
    case 3:   
      //"delete from trade.sellorders where tid=?",
      Log.getLogWriter().info(database + "deleting trade.sellorders with TID:" + tid + query );  
      stmt.setInt(1, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with TID:" + tid + query);
      break;
    case 4:   
      //"delete from trade.sellorders where cid=? and sid=?",
      Log.getLogWriter().info(database + "deleting trade.sellorders with CID:" + cid + ",SID:" + sid + query );  
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with CID:" + cid + ",SID:" + sid + query );  
      break;
    case 5:
      //"delete from trade.sellorders where oid=?",
      Log.getLogWriter().info(database + "deleting trade.sellorders with OID:" + oid + query);  
      stmt.setInt(1, oid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with OID:" + oid + query);
      break;
    case 6:   
      //"delete from trade.sellorders where status IN ('cancelled', 'filled')",
      Log.getLogWriter().info(database + "deleting trade.sellorders with no data " +query  );  
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with no data " +query  ); 
      break;
    case 7:   
      //"delete from trade.sellorders",
      Log.getLogWriter().info(database +  "deleting trade.sellorders with no data " + query);  
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " with no data " +query  );
      break;
    default:
      throw new TestException(database + "incorrect delete statement, should not happen");
    }  
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }
}
