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
package sql.dmlDistTxStatements;

import hydra.Log;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLTest;
import sql.dmlStatements.TradeSecuritiesDMLStmt;
import sql.sqlTx.RangeForeignKey;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxPartitionInfoBB;
import sql.sqlTx.SQLTxSecondBB;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

public class TradeSecuritiesDMLDistTxStmt extends TradeSecuritiesDMLStmt
    implements DMLDistTxStmtIF {

  protected static String[] update = {
    "update trade.securities set price = ? where sec_id = ? ", 
    "update trade.securities set tid =? where sec_id = ? ",
    "update trade.securities set symbol = ?, exchange =? where sec_id = ?",
    
    "update trade.securities set price = ? where symbol Between ? AND ? and sec_id < ?" //commit early
    };
  
  protected static String[] delete = {
    "delete from trade.securities where sec_id=?",
    
    "delete from trade.securities where (symbol like ? and exchange = ? ) and tid=?", //commit early
     };
  
  public static boolean isReplicate;
  static {
    try {
      String partition = (String)SQLTxPartitionInfoBB.getBB().getSharedMap().get("trade." + getTableName());
      if (partition.equalsIgnoreCase("replicate")) isReplicate = true;
      else isReplicate = false;
    } catch (NullPointerException npe) {
      isReplicate = false;
    }
  }
  static {
    Log.getLogWriter().info("isReplicate is " + isReplicate);
  }
  protected boolean hasSecondary = isReplicate || 
     (batchingWithSecondaryData && (Boolean) SQLBB.getBB().getSharedMap().get(SQLTest.hasRedundancy)); 
  
  @SuppressWarnings("unchecked")
  //sec_id, symbol, exchange, tid, whichDelete, updateCount, gfxdse
  protected void addDeleteToDerbyTx(int sec_id, String symbol , String exchange,
      int tid, int whichDelete, int updateCount, SQLException gfxdse){
    Object[] data = new Object[9];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SECURITIES;
    data[1] = "delete";
    data[2] = sec_id;
    data[3] = symbol;
    data[4] = exchange;
    data[5] = tid;
    data[6] = whichDelete;
    data[7] = updateCount;
    data[8] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  @SuppressWarnings("unchecked")
  @Override
  public void deleteDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[8];
    
    try {
      //Connection dConn, int sec_id, String symbol, 
      //String exchange, int tid, int whichDelete, int updateCount
      deleteFromDerbyTable(dConn, (Integer)data[2], (String)data[3], (String)data[4], 
          (Integer)data[5], (Integer)data[6], (Integer) data[7]); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean deleteGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return deleteGfxdOnly(gConn);
    }
    int whichDelete = rand.nextInt(delete.length);
    whichDelete = getWhichDelete(whichDelete);
    int minLen = 2;
    int maxLen = 3;
    String symbol = getSymbol(minLen, maxLen) + '%';
    String exchange = getExchange();
    int[] sec_id = new int[1];
    int[] updateCount = new int[1];
    boolean[] expectConflict = new boolean[1];
    int tid = getMyTid();
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    SQLException gfxdse = null;
    getExistingSidFromSecurities(nonTxConn, sec_id);

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    try {
      getKeysForDelete(nonTxConn, modifiedKeysByOp, whichDelete, sec_id[0], symbol,
          exchange, tid, expectConflict); //track foreign key constraints and key conflict

      /* no longer needed as foreign key lock check will be check at
       * the commit time or a later op time when batching is enabled
      if (batchingWithSecondaryData && expectConflict[0] == true) {
        SQLDistTxTest.expectForeignKeyConflictWithBatching.set(expectConflict[0]);
        //TODO need to think a better way when #43170 is fixed -- which foreign keys (range keys) are held
        //and by which threads need to be tracked and verified.
      }
      */
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this insert op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else SQLHelper.handleSQLException(se);
    }

    //add modified parent keys to threadlocal
    if (batchingWithSecondaryData) {
      HashSet<String> holdParentKeyByThisTx = (HashSet<String>) SQLDistTxTest.parentKeyHeldWithBatching.get();
      holdParentKeyByThisTx.addAll(modifiedKeysByOp.keySet());
      SQLDistTxTest.parentKeyHeldWithBatching.set(holdParentKeyByThisTx);

      int txId = (Integer)SQLDistTxTest.curTxId.get();
      SQLBB.getBB().getSharedMap().put(parentKeyHeldTxid + txId, holdParentKeyByThisTx);
      //used to track actual parent keys hold by delete/update, work around #42672 & #50070
    }

    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    for( int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Deleting " + i + " times");
        deleteFromGfxdTable(gConn, sec_id[0], symbol, exchange, tid, whichDelete, updateCount);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          if (expectConflict[0]) {
            Log.getLogWriter().info("got expected conflict exception due to foreign key constraint, continuing testing");  //if conflict caused by foreign key
          } else {
            try {
              if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
              else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
            } catch (TestException te) {
              if (te.getMessage().contains("but got conflict exception") && i < 9) {
                Log.getLogWriter().info("Got conflict exception, retrying the op");
                continue;
              } else throw te;
            }
            //check if conflict caused by multiple inserts on the same keys
          }
          for (String key : modifiedKeysByOp.keySet()) {
            Log.getLogWriter().info("key is " + key);
            if (!key.contains("unique")) {
              int sid = Integer.parseInt(key.substring(key.indexOf("_") + 1));
              removeWholeRangeForeignKeys(sid);
              if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
            }
          }
          return false;
        } else if (SQLHelper.gotTXNodeFailureException(se)) {
          if (gfxdtxHANotReady && isHATest) {
            SQLHelper.printSQLException(se);
            Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");

            for (String key : modifiedKeysByOp.keySet()) {
              Log.getLogWriter().info("key is " + key);
              if (!key.contains("unique")) {
                int sid = Integer.parseInt(key.substring(key.indexOf("_") + 1));
                removeWholeRangeForeignKeys(sid);
                if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
              }
            }

            return false;
          } else throw new TestException("got node failure exception in a non HA test" + TestHelper.getStackTrace(se));
        } else {
          if (expectConflict[0] && !se.getSQLState().equals("23503")) {
            if (!batchingWithSecondaryData)
              throw new TestException("expect conflict exceptions, but did not get it" +
                  TestHelper.getStackTrace(se));
            else {
              //do nothing, as foreign key check may only be done on local node, conflict could be detected at commit time
              ;
            }
          }
          gfxdse = se;
          for (String key : modifiedKeysByOp.keySet()) {
            if (key != null && !key.contains("_unique_")) {
              String s = key.substring(key.indexOf("_") + 1);
              int sid = Integer.parseInt(s);
              removeWholeRangeForeignKeys(sid); //operation failed, does not hold foreign key
              if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
            }
          }
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    if (expectConflict[0] && gfxdse == null) {
      if (!batchingWithSecondaryData)
        throw new TestException("Did not get conflict exception for foreign key check. " +
            "Please check for logs");
      else {
        //do nothing, as foreign key check may only be done on local node, conflict could be detected at commit time
        ;
      }
    }
    //add this operation for derby
    addDeleteToDerbyTx(sec_id[0], symbol, exchange, tid,
        whichDelete, updateCount[0], gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
  }

  @SuppressWarnings("unchecked")
  protected void addInsertToDerbyTx(int[] sec_id, String[] symbol , String[] exchange,
      BigDecimal[] price, int[] updateCount, SQLException gfxdse){
    Object[] data = new Object[8];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SECURITIES;
    data[1] = "insert";
    data[2] = sec_id;
    data[3] = symbol;
    data[4] = exchange;
    data[5] = price;
    data[6] = updateCount;
    data[7] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public void insertDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[7];
    
    try {
      //insertToDerbyTable(dConn, cid, cust_name,since, addr, count, size);
      insertToDerbyTable(dConn, (int[])data[2], (String[])data[3], (String[])data[4], 
          (BigDecimal[])data[5], (int[])data[6], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean insertGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return insertGfxdOnly(gConn);
    }
    int size = 1;
    int[] sec_id = new int[size];
    String[] symbol = new String[size];
    String[] exchange = new String[size];
    BigDecimal[] price = new BigDecimal[size];
    int[] updateCount = new int[size];
    SQLException gfxdse = null;

    getDataForInsert(sec_id, symbol, exchange, price, size); //get the data
    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    modifiedKeysByOp.put(getTableName()+"_"+sec_id[0], (Integer)SQLDistTxTest.curTxId.get());

    //when two tx insert/update could lead to unique key constraint violation,
    //only one tx could hold the unique key lock

    //if unique key already exists, committed prior to this round, it should get
    //unique key constraint violation, not conflict or lock not held exception
    modifiedKeysByOp.put(getTableName()+"_unique_"+symbol[0] + "_" + exchange[0],
        (Integer)SQLDistTxTest.curTxId.get());
    Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get()+ " need to hold the unique key "
        + getTableName()+"_unique_"+symbol[0] + "_" + exchange[0]);

    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get(); //no need to check fk, as securities is a parent table

    for( int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Inserting " + i + " times.");
        insertToGfxdTable(gConn, sec_id, symbol, exchange, price, updateCount, size);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
            else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
          }
          catch(TestException te){
            if (te.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            }
            else throw te;
          }
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          gfxdse = se;
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addInsertToDerbyTx(sec_id, symbol, exchange, price, updateCount, gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
  }
  
  public static String getTableName() {
    return "securities";
  }
  
  protected void insertToGfxdTable(Connection conn, int[] sec_id, 
      String[] symbol, String[] exchange, BigDecimal[] price, int[] updateCount,
      int size) throws SQLException {
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(conn, insert);
    else stmt = conn.prepareStatement(insert);
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      updateCount[i] = insertToTable(stmt, sec_id[i], symbol[i], exchange[i], price[i], tid);
      
    }
  }
  
  protected void insertToDerbyTable(Connection conn, int[] sec_id, 
      String[] symbol, String[] exchange, BigDecimal[] price, int[] updateCount, 
      int size) throws SQLException  {
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    int count = -1;
    
    //please note the following configuration,
    //se.getSQLState().equals("23505") && isWanTest && !isSingleSitePublisher
    //derby should not perform the operation, this should be handled in the insertToGfxdTable
    //for such configuration
    for (int i=0 ; i<size ; i++) {
      count = insertToTable(stmt, sec_id[i], symbol[i], exchange[i], price[i], tid);
      if (count != updateCount[i]) {
        Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
          "gfxd inserted " + updateCount[i] +
          " row, but derby inserted " + count + " row in " + getTableName());
      }
    }
  }

  //query database using a randomly chosen select statement
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = 4; //how many select statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not

    int sec_id = rand.nextInt((int)SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary));
    String symbol = getSymbol();
    BigDecimal price = getPrice();
    String exchange = getExchange();
    int tid = getMyTid();
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    for (int i = 0; i < 10; i++) {
      Log.getLogWriter().info("RR: executing query " + i + "times");
      if (dConn != null) {
        try {
          discRS = query(dConn, whichQuery, sec_id, symbol, price, exchange, tid);
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
          gfeRS = query(gConn, whichQuery, sec_id, symbol, price, exchange, tid);
          if (gfeRS == null) {
            if (isHATest) {
              Log.getLogWriter().info("Testing HA and did not get GFXD result set after retry");
              return;
            } else if (setCriticalHeap) {
              Log.getLogWriter().info("got XCL54 and does not get query result");
              return; //prepare stmt may fail due to XCL54 now
            } else
              throw new TestException("Not able to get gfe result set after retry");
          }
        } catch (SQLException se) {
          if (se.getSQLState().equals("X0Z02") && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          }
          SQLHelper.handleGFGFXDException(se, exceptionList);
        }
        SQLHelper.handleMissedSQLException(exceptionList);
        if (discRS == null || gfeRS == null) return;

        boolean success = ResultSetHelper.compareResultSets(discRS, gfeRS);
        if (!success) {
          Log.getLogWriter().info("Not able to compare results, continuing test");
        } //not able to compare results due to derby server error

      }// we can verify resultSet
      else {
        try {
          Log.getLogWriter().info("RR: executing query " + i + " times.");
          gfeRS = query(gConn, whichQuery, sec_id, symbol, price, exchange, tid);
        } catch (SQLException se) {
          if (se.getSQLState().equals("42502") && SQLTest.testSecurity) {
            Log.getLogWriter().info("Got expected no SELECT permission, continuing test");
            return;
          } else if (alterTableDropColumn && se.getSQLState().equals("42X04")) {
            Log.getLogWriter().info("Got expected column not found exception, continuing test");
            return;
          } else if (se.getSQLState().equals("X0Z02") && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          }
          else SQLHelper.handleSQLException(se);
        }

        if (gfeRS != null) {
          try {
            ResultSetHelper.asList(gfeRS, false);
          } catch (TestException te) {
            if (te.getMessage().contains("Conflict detected in transaction operation and it will abort") && (i <9)) {
              Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
              continue;
            } else throw te;
          }
        } else if (isHATest)
          Log.getLogWriter().info("could not get gfxd query results after retry due to HA");
        else if (setCriticalHeap)
          Log.getLogWriter().info("could not get gfxd query results after retry due to XCL54");
        else
          throw new TestException("gfxd query returns null and not a HA test");
      }
      break;
    }

    SQLHelper.closeResultSet(gfeRS, gConn);
  }

  @SuppressWarnings("unchecked")
  protected void addQueryToDerbyTx(int whichQuery, int sec_id, String symbol,
      BigDecimal price, String exchange, int tid, 
      List<Struct> gfxdList, SQLException gfxdse){
    Object[] data = new Object[10];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SECURITIES;
    data[1] = "query";
    data[2] = whichQuery;
    data[3] = sec_id;
    data[4] = symbol;
    data[5] = price;
    data[6] = exchange;
    data[7] = tid;
    data[8] = gfxdList;
    data[9] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public void queryDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[9];
    List<Struct> gfxdList = (List<Struct>) data[8];
    ResultSet derbyRS = null;
    
    try {
      //query(dConn, whichQuery, queryCash, querySec, loanLimit, loanAmount, tid); 
      derbyRS = query(dConn, (Integer)data[2], (Integer)data[3], (String)data[4],
         (BigDecimal)data[5], (String)data[6], (Integer)data[7]); 
    }  catch (SQLException derbyse) {
      SQLHelper.compareExceptions(derbyse, gfxdse);
    }
    
    ResultSetHelper.compareResultSets(ResultSetHelper.asList(derbyRS, true), gfxdList);
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean queryGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return queryGfxdOnly(gConn);
    }
    int numOfNonUniq = 4; //how many select statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    if (whichQuery <4) whichQuery += 4; //query on any rows
    
    int sec_id = rand.nextInt((int)SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary));
    String symbol = getSymbol();
    BigDecimal price = getPrice();
    String exchange = getExchange();
    int tid = getMyTid();
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      gfxdRS = query (gConn, whichQuery, sec_id, symbol, price, exchange, tid);   
      if (gfxdRS == null) {
        /* has specific node failure exception for txn being thrown by 
         * select query, this is handled in catch block
        if (isHATest) {
          Log.getLogWriter().info("Testing HA and did not get GFXD result set");
          return true;
        }
        else  
        */   
          throw new TestException("Not able to get gfxd result set");
      }
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      
      if (se.getSQLState().equalsIgnoreCase("X0Z02") ) { 
        if (batchingWithSecondaryData) {
          verifyConflictWithBatching(new HashMap<String, Integer>(), modifiedKeysByTx, se, hasSecondary, true);
          return false;
        }      
      }
      
      //handle node failure condition
      if (isHATest &&
        SQLHelper.gotTXNodeFailureException(se) ) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got node failure exception during Tx without HA support, continue testing");
        return false; //not able to handle node failure yet, needs to rollback ops
                    // to be confirmed if select query could cause lock to be released
      }

      gfxdse = se;
    } 
    
    List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS, false);
    /* taken out as processing result set gets X0Z01 
    if (gfxdList == null && isHATest && (Boolean) SQLDistTxTest.convertTxnRSGotNodeFailure.get()) {
      Log.getLogWriter().info("Testing HA and did not get GFXD result set due to node failure");
      SQLDistTxTest.convertTxnRSGotNodeFailure.set(false); //reset flag
      return false; //do not compare query results as gemfirexd does not get any due to node failure
    }
    */
    if (gfxdList == null && isHATest) {
      Log.getLogWriter().info("Testing HA and did not get GFXD result set due to node failure");
      return false; //assume txn failure occur and txn rolled back by product, otherwise return true here 
    }

    addQueryToDerbyTx(whichQuery, sec_id, symbol, price, exchange, 
         tid, gfxdList, gfxdse);

    return true;
  }
 
  

  @SuppressWarnings("unchecked")
  protected void addUpdateToDerbyTx(int[] sec_id, String[] symbol, 
      String[] exchange, BigDecimal[] price, String[] lowEnd, String[] highEnd,
      int whichUpdate, int[] updateCount, SQLException gfxdse){
     Object[] data = new Object[11];      
     data[0] = DMLDistTxStmtsFactory.TRADE_SECURITIES;
     data[1] = "update";
     data[2] = sec_id;
     data[3] = symbol;
     data[4] = exchange;
     data[5] = price;
     data[6] = lowEnd;
     data[7] = highEnd;
     data[8] = whichUpdate;
     data[9] = updateCount;
     data[10] = gfxdse;
     
     ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
     if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
     derbyOps.add(data);
     SQLDistTxTest.derbyOps.set(derbyOps);
   }
  
  @SuppressWarnings("unchecked")
  @Override
  public void updateDerby(Connection dConn, int index){
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[10];
    
    try {
      //updateDerbyTable(Connection gConn, int[]sec_id, String[]symbol, 
      //String[]exchange, BigDecimal[]price, String[] lowEnd, String[] highEnd, 
      //int whichUpdate, int[] updateCount, int size)
      updateDerbyTable(dConn, (int[])data[2], (String[])data[3], (String[])data[4], 
          (BigDecimal[])data[5], (String[])data[6], (String[])data[7], (Integer)data[8],
           (int[])data[9], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  protected int getWhichUpdate(int index) {
    //separate which one could update certain statement
    if (index == 2 && (Boolean)SQLDistTxTest.commitEarly.get()) {
      index = 3; 
    } else if (index == 3 && !(Boolean)SQLDistTxTest.commitEarly.get()) {
      index = 2; 
    }
    
    boolean ticket42651fixed = true; //TODO, change to true when #42651 is fixed
    if (index == 2 && !ticket42651fixed) index = 1; 
    return index;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean updateGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return updateGfxdOnly(gConn);
    }
    int whichUpdate= getWhichUpdate(rand.nextInt(update.length));
    int size = 1;
    int[] sec_id = new int[size];
    String[] symbol = new String[size];
    String[] exchange = new String[size];
    BigDecimal[] price = new BigDecimal[size];
    String[] lowEnd = new String[size];
    String[] highEnd = new String[size];
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();

    boolean[] expectConflict = new boolean[1];

    getDataForUpdate(gConn, sec_id, symbol, exchange, price, size); //get the data
    for (int i = 0 ; i <size ; i++) {
      sec_id[i]= getExistingSid(); //to avoid phantom read
    }
    getSidForTx(nonTxConn, sec_id);
    getAdditionalUpdateData(lowEnd, highEnd);

    getExistingSidFromSecurities(nonTxConn, sec_id); //any sec_id already committed
    if (isHATest && sec_id[0] == 0) {
      Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get()  + "could not get valid sec_id, abort this op");
      return true;
    }
    int[] updateCount = new int[size];
    SQLException gfxdse = null;

    int txId = (Integer)SQLDistTxTest.curTxId.get();

    //TODO, need to consider the following configuration: isWanTest && !isSingleSitePublisher
    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    /* handled in actual dml op instead
    if (SQLTest.testPartitionBy) {
      PreparedStatement stmt = getCorrectTxStmt(gConn, whichUpdate);
      if (stmt == null) {
        if (SQLTest.isEdge && isHATest && !isTicket48176Fixed &&
            batchingWithSecondaryData &&(Boolean) SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
          SQLDistTxTest.failedToGetStmtNodeFailure.set(false);
          return false; //due to node failure, need to rollback tx
        }
        else return true; //due to unsupported exception
      }
    }
    */

    try {
      getKeysForUpdate(nonTxConn, modifiedKeysByOp, whichUpdate,
          sec_id[0], lowEnd[0], highEnd[0], expectConflict);

      /* tracked in batching fk bb now
      if (batchingWithSecondaryData && expectConflict[0] == true) {
        SQLDistTxTest.expectForeignKeyConflictWithBatching.set(expectConflict[0]);
        //TODO need to think a better way when #43170 is fixed -- which foreign keys (range keys) are held
        //and by which threads need to be tracked and verified.
      }
      */
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this insert op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else SQLHelper.handleSQLException(se);
    }
    //when two tx insert/update could lead to unique key constraint violation,
    //only one tx could hold the unique key lock

    //if unique key already exists, committed prior to this round, it should get
    //unique key constraint violation, not conflict or lock not held exception
    if (whichUpdate == 2) {
      //"update trade.securities set symbol = ?, exchange =? where sec_id = ?",
      //add the unique key going to be held
      modifiedKeysByOp.put(getTableName()+"_unique_"+symbol[0] + "_" + exchange[0],
          txId);
      Log.getLogWriter().info("need to hold the unique key "
          + getTableName()+"_unique_"+symbol[0] + "_" + exchange[0]);
      //should also hold the lock for the current unique key as well.
      //using the current gConn, so the read will reflect the modification within tx as well

      //TODO may need to reconsider this for RR case after #43170 is fixed -- it is
      //possible an update failed due to unique key violation and does not rollback
      //the previous operations and the RR read lock on the key may cause commit issue
      //may need to add to the RR read locked keys once #43170 is fixed.
      try {
        String sql = "select symbol, exchange from trade.securities where sec_id = " + sec_id[0];
        ResultSet rs = nonTxConn.createStatement().executeQuery(sql);
        if (rs.next()) {
          String symbolKey = rs.getString("SYMBOL");
          String exchangeKey = rs.getString("EXCHANGE");
          Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get()+ " should hold the unique key "
              + getTableName()+"_unique_"+symbolKey+"_"+exchangeKey);
          modifiedKeysByOp.put(getTableName()+"_unique_"+symbolKey+"_"+exchangeKey, txId);
        }
        rs.close();
      } catch (SQLException se) {
        if (!SQLHelper.checkGFXDException(gConn, se)) {
          Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get()+ "node failure in HA test, abort this operation");
          return false;
        } else SQLHelper.handleSQLException(se);
      }
    }

    //update will hold the keys to track foreign key constraint conflict
    if (batchingWithSecondaryData && !ticket42672fixed && isSecuritiesPartitionedOnPKOrReplicate()) {
      HashSet<String> holdParentKeyByThisTx = (HashSet<String>) SQLDistTxTest.parentKeyHeldWithBatching.get();
      holdParentKeyByThisTx.addAll(modifiedKeysByOp.keySet());
      SQLDistTxTest.parentKeyHeldWithBatching.set(holdParentKeyByThisTx);

      SQLBB.getBB().getSharedMap().put(parentKeyHeldTxid + txId, holdParentKeyByThisTx);
      //used to track actual parent keys hold by update, work around #42672 & #50070
    }

    for (int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Updating " + i + " times.");
        updateGfxdTable(gConn, sec_id, symbol, exchange, price,
            lowEnd, highEnd, whichUpdate, updateCount, size);

        //handles get stmt failure conditions -- node failure or unsupported update on partition field
        if (isHATest && (Boolean)SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
          SQLDistTxTest.failedToGetStmtNodeFailure.set(false); //reset flag
          return false; //due to node failure, assume txn rolled back
        }
        if ((Boolean)SQLDistTxTest.updateOnPartitionCol.get()) {
          SQLDistTxTest.updateOnPartitionCol.set(false); //reset flag
          return true; //assume 0A000 exception does not cause txn to rollback,
        }

        if (expectConflict[0] && !batchingWithSecondaryData) {
          throw new TestException("expected to get conflict exception due to foreign key constraint, but does not." +
              " Please check the logs for more information");
        }
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          if (expectConflict[0]) {
            Log.getLogWriter().info("got conflict exception due to #42672, continuing testing"); //if conflict caused by foreign key, this is a workaround due to #42672
          } else {
            try {
              if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
              else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
            }
            catch(TestException te){
              if (te.getMessage().contains("but got conflict exception") && i < 9) {
                Log.getLogWriter().info("RR: got conflict, retrying the operations ");
                continue;
              }
              else throw te;
            }
          }
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get() + "got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          gfxdse = se;
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addUpdateToDerbyTx(sec_id, symbol, exchange, price,
        lowEnd, highEnd, whichUpdate, updateCount, gfxdse);

    if (gfxdse == null) {
      modifiedKeysByTx.putAll(modifiedKeysByOp);
      SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    } //add the keys if no SQLException is thrown during the operation
    return true;
  }
  
  //returns like "ka" and "kc" 
  protected void getAdditionalUpdateData(String[] lowEnd, String[] highEnd) {
    String firstChar = getSymbol(1, 1);
    String secondChar = getSymbol(1, 1);
    char c = secondChar.charAt(0);
    
    if (c == 'z' || c == 'y') {
      lowEnd[0] = firstChar + (char)(c - 2);
      highEnd[0] = firstChar + secondChar;
    } else {
      lowEnd[0] = firstChar + secondChar;
      highEnd[0] = firstChar + (char)(c + 2);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void getKeysForUpdate(Connection conn, HashMap<String, Integer > keys,
      int whichUpdate, int sec_id, String lowEnd, String highEnd, boolean[] expectConflict) throws SQLException {
    String sql = null;
    ResultSet rs = null;
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    //check if foreign key if exists
    //test will add cid =0 or sid =0 to verify, tx could detect non existing fks
    ArrayList<Integer> ids = new ArrayList<Integer>();
       
    switch (whichUpdate) {
    case 0: //fall through
    case 1://fall through
    case 2://fall through
      sql = "select sec_id from trade.securities where sec_id =" + sec_id;
      Log.getLogWriter().info(database + sql);
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int availSid = rs.getInt(1);
        Log.getLogWriter().info(database + "sec_id: " + availSid + " exists for update");
        keys.put(getTableName()+"_"+sec_id, txId);
        
        ids.add(availSid);
      }          
      break;
    case 3: 
      //update trade.securities set price = ? where symbol Between ? AND ? and  sec_id < ?
      sql = "select sec_id from trade.securities where symbol between '" + lowEnd 
        + "' AND '" + highEnd + "' and sec_id< " + sec_id;
      Log.getLogWriter().info(database + sql);
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int availSid = rs.getInt(1);
        Log.getLogWriter().info(database + "sec_id: " + availSid + " exists for update");
        keys.put(getTableName()+"_"+availSid, txId);
        
        ids.add(availSid);
      }          
      break; 
    default:
     throw new TestException ("Wrong update statement here");
    } 
    
    // needs refactoring, copied here for fast test coverage
    
    //there are two foreign tables refers to securities, portfolio and buyorders
    
    //we need to modify the RangeForeignKey and check if the range foreign key has been taken in the child, 
    //work around #49889, no whole range key is locked by update on parent
    if (!ticket42672fixed && isSecuritiesPartitionedOnPKOrReplicate()) {
      hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
      for (Integer key: ids) {
        String portfolioKey = TradePortfolioDMLDistTxStmt.getTableName() + 
            TradePortfolioDMLDistTxStmt.sidPrefix + key;
        RangeForeignKey portfolioRangeKey = (RangeForeignKey) rangeForeignKeys.get(portfolioKey);
        if (portfolioRangeKey == null) portfolioRangeKey = new RangeForeignKey(portfolioKey);
        rangeForeignKeys.put(portfolioKey, portfolioRangeKey);
        
        //String buyordersKey = TradeBuyOrdersDMLDistTxStmt.getTableName() + "_" + key;
        String buyordersKey = "buyorders" + "_sid_" + key;
        RangeForeignKey buyordersRangeKey = (RangeForeignKey) rangeForeignKeys.get(buyordersKey);
        if (buyordersRangeKey == null) buyordersRangeKey = new RangeForeignKey(buyordersKey);
        rangeForeignKeys.put(buyordersKey, buyordersRangeKey);
        
        boolean hasRangeForeignKeyConflict = portfolioRangeKey.hasConflictAddWholeRangeKey(txId) 
        || buyordersRangeKey.hasConflictAddWholeRangeKey(txId);
        

        rangeForeignKeys.put(portfolioKey, portfolioRangeKey);
        rangeForeignKeys.put(buyordersKey, buyordersRangeKey);
        
        keys.put(getTableName() + "_" + key, txId); 
        //keys operated by this op will be added in verify conflict method
        
        ArrayList<String> curTxRangeFK = (ArrayList<String>)SQLDistTxTest.curTxFKHeld.get();
        curTxRangeFK.add(buyordersKey);
        curTxRangeFK.add(portfolioKey);
        SQLDistTxTest.curTxFKHeld.set(curTxRangeFK);
        
        
        //update should not get conflict, until product fixes 42672, will work around this in the test
        if (hasRangeForeignKeyConflict) { 
          expectConflict[0] = true;
          Log.getLogWriter().info("expect to gets the conflict due to foregin key constraint");
          break;
        }
      }
    }
  }
  
  protected void updateGfxdTable(Connection gConn, int[]sec_id, String[]symbol,
      String[]exchange, BigDecimal[]price, String[] lowEnd, String[] highEnd, 
      int whichUpdate, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(gConn, whichUpdate);
      else stmt = getStmt(gConn, update[whichUpdate]); //use only this after bug#39913 is fixed
      
      if (stmt!=null) {
        updateCount[i] = updateTable(stmt, sec_id[i], symbol[i], exchange[i], 
            price[i], tid, lowEnd[i], highEnd[i], whichUpdate);
        
      }
    } 
  }
  
  private void updateDerbyTable(Connection dConn, int[]sec_id, String[]symbol, 
      String[]exchange, BigDecimal[]price, String[] lowEnd, String[] highEnd, 
      int whichUpdate, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = 0;
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(dConn, whichUpdate);
      else stmt = getStmt(dConn, update[whichUpdate]); //use only this after bug#39913 is fixed

      if (stmt!=null) {
        count = updateTable(stmt, sec_id[i], symbol[i], exchange[i], 
            price[i], tid, lowEnd[i], highEnd[i], whichUpdate);
        if (count != updateCount[i]){
          Log.getLogWriter().info("Derby update has different row count from that of gfxd, " +
                  "gfxd updated " + updateCount[i] +
                  " rows, but derby updated " + count + " rows");
        }
      }
    } 
  }
  
  protected int updateTable(PreparedStatement stmt, int sec_id, String symbol,
      String exchange, BigDecimal price, int tid, String lowEnd, String highEnd,
      int whichUpdate) throws SQLException {
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    String query = " QUERY: " + update[whichUpdate];
    String successString ="";
    switch (whichUpdate) {
    case 0: 
      //"update trade.securities set price = ? where sec_id = ? ", 
      Log.getLogWriter().info(database + "updating trade.securities  with SECID:" + sec_id  
          + " where PRICE:" + price.doubleValue() + query);
      stmt.setBigDecimal(1, price); 
      stmt.setInt(2, sec_id);
      successString = "trade.securities table with SECID:" + sec_id  
          + " where PRICE:" + price.doubleValue() + query;
      break;
    case 1: 
      //"update trade.securities set tid =? where sec_id = ? ",
      Log.getLogWriter().info(database + "updating trade.securities with SECID:" + sec_id + " where TID:" + tid + query);
      stmt.setInt(1, tid);
      stmt.setInt(2, sec_id);
      successString = "trade.securities with SECID:" + sec_id + " where TID:" + tid + query;
      break;
    case 2: 
      //"update trade.securities set symbol = ?, exchange =? where sec_id = ?",
      Log.getLogWriter().info(database + "updating trade.securities where  SEC_ID:" + sec_id + ",SYMBOL:" + symbol + 
          ",EXCHANGE:" + exchange + query);
      stmt.setString(1, symbol);
      stmt.setString(2, exchange);
      stmt.setInt(3, sec_id);
      successString="trade.securities where  SEC_ID:" + sec_id + ",SYMBOL:" + symbol + 
        ",EXCHANGE:" + exchange + query;
      break;
    case 3:
      //"update trade.securities set price = ? where symbol Between ? AND ? and sec_id < ?"
      Log.getLogWriter().info(database + "updating trade.securities with PRICE:" + price.doubleValue() + 
          " where 1_SYMBOL:" + lowEnd + ",2_SYMBOL:" + highEnd + ",SEC_ID:" + sec_id + query);
      stmt.setBigDecimal(1, price);
      stmt.setString(2, lowEnd);
      stmt.setString(3, highEnd);
      stmt.setInt(4, sec_id);
      successString="trade.securities with PRICE:" + price.doubleValue() + 
        " where 1_SYMBOL:" + lowEnd + ",2_SYMBOL:" + highEnd + ",SEC_ID:" + sec_id + query;
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info(database + "updated " + rowCount + " rows in" + successString) ;
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      //"update trade.securities set price = ? where sec_id = ? ", 
      if (partitionKeys.contains("price")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      //"update trade.securities set tid =? where sec_id = ? ",
      if (partitionKeys.contains("tid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      //"update trade.securities set symbol = ?, exchange =? where sec_id = ?",
      if (partitionKeys.contains("symbol") || partitionKeys.contains("exchange")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      //"update trade.securities set price = ? where symbol Between ? AND ?" 
      if (partitionKeys.contains("price")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  protected int getWhichDelete(int index) {
    if (index == 1 && !(Boolean)SQLDistTxTest.commitEarly.get()) {
      index = 0; 
    } 
    return index;
  }
  //gConn, modifiedKeysByOp, sec_id, symbol, exchange, expectConflict
  @SuppressWarnings("unchecked")
  protected void getKeysForDelete(Connection conn, HashMap<String, Integer > keys,
      int whichDelete, int sec_id, String symbol, String exchange, 
      int tid, boolean[] expectConflict) throws SQLException {
     String sql = null;
     ResultSet rs = null;
     int txId = (Integer) SQLDistTxTest.curTxId.get();
     String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
     
     //check if foreign key if exists
     //test will add cid =0 or sid =0 to verify, tx could detect non existing fks
     ArrayList<Integer> ids = new ArrayList<Integer>();
     switch (whichDelete){
     case 0:
       sql = "select symbol, exchange from trade.securities where sec_id = " + sec_id;
       Log.getLogWriter().info(database + sql);
       rs = conn.createStatement().executeQuery(sql);
       if (rs.next()) {
         String symbolKey = rs.getString("SYMBOL");
         String exchangeKey = rs.getString("exchange");
         Log.getLogWriter().info(database + "should hold the unique key " 
             + getTableName()+"_unique_"+symbolKey+"_"+exchangeKey);
         keys.put(getTableName()+"_unique_"+symbolKey+"_"+exchangeKey, txId);
         // no other tx could insert or update to this unique key 
         // until this current tx is committed
         ids.add(sec_id);
       }
       rs.close();      
       break;
     case 1: //use tx connection, so that the symbols update within the tx could be checked as well
       sql = "select sec_id, symbol, exchange from trade.securities " +
           "where (symbol like ? and exchange = ? ) and tid=?";
       Log.getLogWriter().info(database + sql);
       PreparedStatement stmt = conn.prepareStatement(sql);
       stmt.setString(1, symbol);
       stmt.setString(2, exchange);
       stmt.setInt(3, tid);
       rs = stmt.executeQuery();
       List<Struct>resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer sid = (Integer)row.get("SEC_ID");
           String symbolKey = (String)row.get("SYMBOL");
           String exchangeKey = (String)row.get("EXCHANGE");
           Log.getLogWriter().info(database + "sec_id: " + sid + " exists for delete");
           keys.put(getTableName()+"_unique_"+symbolKey+"_"+exchangeKey, txId);
           // no other tx could insert or update to this new unique key 
           // until this current tx is committed
           Log.getLogWriter().info(database + "should hold the unique key " 
               + getTableName()+"_unique_"+symbolKey+"_"+exchangeKey);
           ids.add(sid);
         }
       }
       break;
     default:
       throw new TestException("wrong index here");
     }
     
     //there are two foreign tables refers to securities, portfolio and buyorders
     
     //we need to modify the RangeForeignKey and check if the range foreign key has been taken in the child, 
     hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
     for (Integer key: ids) {
       String portfolioKey = TradePortfolioDMLDistTxStmt.getTableName() + 
           TradePortfolioDMLDistTxStmt.sidPrefix + key;
       RangeForeignKey portfolioRangeKey = (RangeForeignKey) rangeForeignKeys.get(portfolioKey);
       if (portfolioRangeKey == null) portfolioRangeKey = new RangeForeignKey(portfolioKey);
       
       //String buyordersKey = TradeBuyOrdersDMLDistTxStmt.getTableName() + "_" + key;
       String buyordersKey = "buyorders" + "_sid_" + key;
       RangeForeignKey buyordersRangeKey = (RangeForeignKey) rangeForeignKeys.get(buyordersKey);
       if (buyordersRangeKey == null) buyordersRangeKey = new RangeForeignKey(buyordersKey);
       
       boolean hasRangeForeignKeyConflict = portfolioRangeKey.hasConflictAddWholeRangeKey(txId) 
       || buyordersRangeKey.hasConflictAddWholeRangeKey(txId);
       
       rangeForeignKeys.put(portfolioKey, portfolioRangeKey);
       rangeForeignKeys.put(buyordersKey, buyordersRangeKey);
       keys.put(getTableName() + "_" + key, txId); 
       //keys operated by this op will be added in verify conflict method
       
       ArrayList<String> curTxRangeFK = (ArrayList<String>)SQLDistTxTest.curTxFKHeld.get();
       curTxRangeFK.add(buyordersKey);
       curTxRangeFK.add(portfolioKey);
       SQLDistTxTest.curTxFKHeld.set(curTxRangeFK);
       
       if (hasRangeForeignKeyConflict) {
         expectConflict[0] = true;
         break;
       }
     }
   }
  
  @SuppressWarnings("unchecked")
  protected void removeWholeRangeForeignKeys(int sid) {
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
    String portfolioFK =  TradePortfolioDMLDistTxStmt.getTableName() + 
        TradePortfolioDMLDistTxStmt.sidPrefix + sid;
    RangeForeignKey portfolioKey = (RangeForeignKey) rangeForeignKeys.get(portfolioFK);
    
    String buyordersFK =  "buyorders" + "_sid_" + sid;
    //RangeForeignKey buyordersKey = (RangeForeignKey) rangeForeignKeys.get(
    //   TradeBuyOrdersDMLDistTxStmt.getTableName() + "_" + sid);
    RangeForeignKey buyordersKey = (RangeForeignKey) rangeForeignKeys.get(buyordersFK );

    ArrayList<String> fkHeld = (ArrayList<String>) SQLDistTxTest.curTxFKHeld.get();
    
    if (portfolioKey!=null) {
      portfolioKey.removeWholeRangeKey(txId);
      fkHeld.remove(portfolioFK);
    }
    
    if (buyordersKey!=null) {
      buyordersKey.removeWholeRangeKey(txId);
      fkHeld.remove(buyordersFK);
    }
    
    rangeForeignKeys.put(portfolioFK, portfolioKey); //put to shared map
    rangeForeignKeys.put(buyordersFK, buyordersKey);
    
    SQLDistTxTest.curTxFKHeld.set(fkHeld); //remove no longer held fks
  }
  
  //gConn, sec_id, symbol, exchange, tid, whichDelete, updateCount
  protected void deleteFromGfxdTable(Connection gConn, int sec_id, String symbol, 
      String exchange, int tid, int whichDelete, int[]updateCount) throws SQLException{
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(gConn, delete[whichDelete]);
    else stmt = gConn.prepareStatement(delete[whichDelete]); 
    
    updateCount[0] = deleteFromTable(stmt, sec_id, symbol, exchange, tid, whichDelete);
    
  }
  
  protected void deleteFromDerbyTable(Connection dConn, int sec_id, String symbol, 
      String exchange, int tid, int whichDelete, int updateCount) throws SQLException{
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]);
    Log.getLogWriter().info("Delete in derby, myTid is " + tid);
    
    int count = deleteFromTable(stmt, sec_id, symbol, exchange, tid, whichDelete);
    
    if (count != updateCount) {
      Log.getLogWriter().info("derby delete has different row count from that of gfxd " +
        "gfxd deleted " +  updateCount + " rows, but derby deleted " + count + " rows in "
        + getTableName());
    }
  }
  
  protected int deleteFromTable(PreparedStatement stmt, int sec_id, String symbol, 
      String exchange, int tid, int whichDelete) throws SQLException {
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    String query = " QUERY: " + delete[whichDelete];
    
    int rowCount=0;
    switch (whichDelete) {
    case 0:   
      //delete from trade.securities where sec_id=?",
      Log.getLogWriter().info(database + "deleteing from trade.securities with SECID:" +sec_id + query);
      stmt.setInt(1, sec_id);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows from trade.securities with SECID:" +sec_id + query);
      break;
    case 1:
      //"delete from trade.securities where (symbol like ? and exchange = ? ) and tid=?"
      Log.getLogWriter().info(database + "deleteing from trade.securities with  SYMBOL:" + symbol +",EXCHANGE:" + exchange + ",TID:" + tid + query);
      stmt.setString(1, symbol);
      stmt.setString(2, exchange);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows from trade.securities with  SYMBOL:" + symbol +",EXCHANGE:" + exchange + ",TID:" + tid + query);
      break;
    default:
      throw new TestException("incorrect delete statement, should not happen");
    }  
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }   
  
  protected BigDecimal getPrice() {
    double d = (rand.nextInt(10000)+1) * .01;
    //Log.getLogWriter().info(" d is " + d);
    String sd = Double.toString(d);
    String p = sd.substring(0, sd.indexOf(".") + 2);
    BigDecimal price = new BigDecimal (p);
    //Log.getLogWriter().info(" price is " + price); //work around #42669
    return price;
  }
  
  /* non derby case using trigger*/
  
  protected boolean insertGfxdOnly(Connection gConn){
    try {
      insert(null, gConn, 1); 
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
        //unique key conflict also throw conflict exception now instead of constraint violation
        return false;
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  protected boolean updateGfxdOnly(Connection gConn){
    try {
      update(null, gConn, 1);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
        return false;
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  protected boolean deleteGfxdOnly(Connection gConn){
    try {
      delete(null, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
        return false;
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  protected boolean queryGfxdOnly(Connection gConn){
    try {
      query(null, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") && batchingWithSecondaryData) {
        Log.getLogWriter().info("got expected conflict exception, continuing test");
        return false;
      } else if (isHATest && SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  public void populate(Connection dConn, Connection gConn) {
    try {
      super.populate(dConn, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") && dConn == null) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
        //unique key also throw conflict exception now
      } else throw te;
    }
    
  }
  
  protected PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectTxStmt(conn, whichUpdate, partitionKeys);
  }
  
  protected void getSidForTx(Connection regConn, int[] sids) {
    if (sids.length != 1) throw new TestException("this method only handles for size of 1 case");
    Connection conn = getAuthConn(regConn);
    int n =0;
    if (useGfxdConfig) 
      n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary)/numOfThreads);
    else
      n = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary)/TestHelper.getNumThreads());
    ResultSet rs;
    try {
      String s = "select sec_id from trade.securities where tid = " +getMyTid();
      rs = conn.createStatement().executeQuery(s);
      int temp=0;
      while (rs.next()) {
        if (n==temp) {
          sids[0] = rs.getInt("SEC_ID");
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
      } else SQLHelper.handleSQLException(se); //throws TestException.
    }      
    
  }
}
