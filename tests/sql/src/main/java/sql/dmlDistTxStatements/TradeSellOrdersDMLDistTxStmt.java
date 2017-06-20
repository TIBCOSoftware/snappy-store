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
import hydra.TestConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.internal.Assert;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlStatements.TradeSellOrdersDMLStmt;
import sql.sqlTx.RangeForeignKey;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxBatchingFKBB;
import sql.sqlTx.SQLTxPartitionInfoBB;
import sql.sqlTx.SQLTxSecondBB;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

public class TradeSellOrdersDMLDistTxStmt extends TradeSellOrdersDMLStmt
    implements DMLDistTxStmtIF {

  public static String cidPrefix = "_cid_";
  public static String sidPrefix = "_sid_";
  static final boolean  isConcUpdateTx = TestConfig.tab().booleanAt(SQLPrms.isConcUpdateTx, false);
  
  protected static String[] selectForUpdate = { 
    "select oid, status from trade.sellorders where sid = ? and ask>? for update of status ",  
    "select * from trade.sellorders where cid >= ? and cid <? for update of qty, status ",  
    "select * from  trade.sellorders where cid = ? and sid= ? for update ",
  }; 
  
  protected static String[] updateByPK = { 
    "update trade.sellorders set status = ?  where oid = ? ",  
    "update trade.sellorders set qty = ?,  status = ? where oid = ?  ",  
    "update trade.sellorders set order_time = ? where oid = ? ",
  }; 
  
  protected static String[] delete = {                           
    "delete from trade.sellorders where cid=? and sid=? and oid <?",
    "delete from trade.sellorders where oid=? ",
    "delete from trade.sellorders where cid>? and cid <? and oid <?",
    "delete from trade.sellorders where cid<? and sid = ? and oid < ?",
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
  @Override
  public void deleteDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[8];
    
    try {
      //Connection dConn, int cid, int cid1, int sid, int oid, int whichDelete, int updateCount
      deleteFromDerbyTable(dConn, (Integer)data[2], (Integer)data[3], (Integer)data[4], 
          (Integer)data[5], (Integer)data[6], (Integer)data[7]); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
    }  

  }
  
  @SuppressWarnings("unchecked")
  protected void addDeleteToDerbyTx(int cid, int cid1, int sid, int oid, int whichDelete, 
      int updateCount, SQLException gfxdse){
    Object[] data = new Object[9];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SELLORDERS;
    data[1] = "delete";
    data[2] = cid;
    data[3] = cid1;
    data[4] = sid;
    data[5] = oid;
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
  public boolean deleteGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return deleteGfxdOnly(gConn);
    }
    
    int whichDelete = rand.nextInt(delete.length);
    
    int cid = getExistingCid();
    int cid1 = cid + 3;
    int sid = getExistingSid();
    int oid = getExistingOid();
    int[] updateCount = new int[1];
    
    SQLException gfxdse = null; 
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    
    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get(); 
    
    try {
      getKeysForDelete(nonTxConn, modifiedKeysByOp, whichDelete, cid, cid1, sid, oid);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this insert op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else if (gfxdtxHANotReady && isHATest &&
        SQLHelper.gotTXNodeFailureException(se) ) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
        return false;
      } else SQLHelper.handleSQLException(se);
    }
    
    try {
      deleteFromGfxdTable(gConn, cid, cid1, sid, oid, whichDelete, updateCount);
      //the gfxd tx needs to handle prepareStatement failed due to node failure here
      //does not expect critical heap exception etc in current tx testing
      //once these coverage are added, similar handling of exceptions seen in getStmt() 
      //need to be added here.
      
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getSQLState().equalsIgnoreCase("X0Z02") ) { 
        if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
        else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
        return false;
      } else if (gfxdtxHANotReady && isHATest &&
        SQLHelper.gotTXNodeFailureException(se) ) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
        return false;
      } else {
        gfxdse = se; //security testing may get exception
      }
    }
    
    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);
    
    //add this operation for derby
    addDeleteToDerbyTx(cid, cid1, sid, oid, whichDelete, updateCount[0], gfxdse);
    
    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
    
  }
  
  public static int getExistingOid() {
    int maxSid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSellOrdersPrimary);
    int newSids = 10 * numOfThreads > 100 ? 10 * numOfThreads: 100;
    if (maxSid>newSids) return rand.nextInt(maxSid-newSids)+1;
    else throw new TestException("test issue, not enough sid in the tests yet");
  }
  
  private void getKeysForDelete(Connection conn, HashMap<String, Integer > keys, 
      int whichDelete, int cid, int cid1, int sid, int oid) throws SQLException {
     String sql = null;
     int txId = (Integer) SQLDistTxTest.curTxId.get();
     String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
     ResultSet rs = null;
     PreparedStatement stmt = null;
     List<Struct>resultsList = null;
     ArrayList<Integer> ids = new ArrayList<Integer>();
     switch (whichDelete){
     case 0:
       //"delete from trade.sellorders where cid=? and sid=? and oid <?",
       sql = "select oid from trade.sellorders where cid= " + cid + " and sid =" + sid
         + " and oid <" + oid;
       Log.getLogWriter().info(database + sql);
       rs = conn.createStatement().executeQuery(sql);
       resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer oidKey = (Integer)row.get("OID");
           ids.add(oidKey);
           Log.getLogWriter().info(database + "To be deleted key is: " + oidKey);
         }
       }     
       break;
     case 1:
       //"delete from trade.sellorders where oid=? ",
       sql = "select oid from trade.sellorders where oid= ?";
       Log.getLogWriter().info(database + sql);
       stmt = conn.prepareStatement(sql);
       stmt.setInt(1, oid);
       rs = stmt.executeQuery();
       resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer oidKey = (Integer)row.get("OID");
           ids.add(oidKey);
           Log.getLogWriter().info(database + "To be deleted key is: " + oidKey);
         }
       }
       break;
     case 2: 
       //"delete from trade.sellorders where cid>? and cid <? and oid <?",
       sql = "select oid from trade.sellorders where cid>? and cid <? and oid <?";
       Log.getLogWriter().info(database + sql);
       stmt = conn.prepareStatement(sql);
       stmt.setInt(1, cid);
       stmt.setInt(2, cid1);  
       stmt.setInt(3, oid);
       rs = stmt.executeQuery();
       resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer oidKey = (Integer)row.get("OID");
           ids.add(oidKey);
           Log.getLogWriter().info(database  + "To be deleted key is: " + oidKey);
         }
       }
       break;
     case 3:
       //"delete from trade.sellorders where cid<? and sid = ? and oid <?",
       sql = "select oid from trade.sellorders where cid<? and sid = ? and oid <?";
       Log.getLogWriter().info(database + sql);
       stmt = conn.prepareStatement(sql);
       stmt.setInt(1, cid);
       stmt.setInt(2, sid);
       stmt.setInt(3, oid);
       rs = stmt.executeQuery();
       resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer oidKey = (Integer)row.get("OID");
           ids.add(oidKey);
           Log.getLogWriter().info(database + "To be deleted key is: " + oidKey);
         }
       }
       break;
     default:
       throw new TestException("wrong index here");
     }
     
     for (Integer key: ids) {
       keys.put(getTableName() + "_" + key, txId); 
       //keys operated by this op will be added in verify conflict method
     }
   }
  
  protected void deleteFromGfxdTable(Connection gConn, int cid, int cid1, 
      int sid, int oid, int whichDelete, int[]updateCount) throws SQLException{
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(gConn, delete[whichDelete]);
    else stmt = gConn.prepareStatement(delete[whichDelete]); 
    
    updateCount[0] = deleteFromTableTx(stmt, cid, cid1, sid, oid, whichDelete);
    
  }
  
  protected void deleteFromDerbyTable(Connection dConn, int cid, int cid1, 
      int sid, int oid, int whichDelete, int updateCount) throws SQLException{
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]);
    int count = deleteFromTableTx(stmt, cid, cid1, sid, oid, whichDelete);
    
    if (count != updateCount) {
      Log.getLogWriter().info("derby delete has different row count from that of gfxd " +
        "gfxd deleted " +  updateCount + " rows, but derby deleted " + count + " rows in "
        + getTableName());
    }
  }
  
  //delete from table based on whichDelete
  protected int deleteFromTableTx(PreparedStatement stmt, int cid, int cid1,
      int sid, int oid, int whichDelete) throws SQLException {
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    String query = " QUERY: " + delete[whichDelete];
    String successString = "";
    switch (whichDelete) {
    case 0:   
      //"delete from trade.sellorders where cid=? and sid=? and oid <?",   
      Log.getLogWriter().info(database + "deleting trade.sellorders with CID:" + cid
          + ",SID:" + sid + ",OID:" + oid + query);  
      stmt.setInt(1, cid); 
      stmt.setInt(2, sid);
      stmt.setInt(3, oid);
      successString="from trade.sellorders with CID:" + cid
        + ",SID:" + sid + ",OID:" + oid + query;
      break;
    case 1:
      //"delete from trade.sellorders where oid=? ", 
      Log.getLogWriter().info(database + "deleting trade.sellorders with OID:" + oid + query);  
      stmt.setInt(1, oid);
      successString ="trade.sellorders with OID:" + oid + query;
      break;
    case 2:
      //"delete from trade.sellorders where cid>? and cid <? and oid <?", 
      Log.getLogWriter().info(database + "deleting trade.sellorders with 1_CID:" + cid
           + ",2_CID:" + cid1 + ",OID:" + oid + query);  
      stmt.setInt(1, cid);
      stmt.setInt(2, cid1);
      stmt.setInt(3, oid);
      successString ="trade.sellorders with 1_CID:" + cid
        + ",2_CID:" + cid1 + ",OID:" + oid + query;
      break;
    case 3:
      //"delete from trade.sellorders where cid<? and sid = ?", 
      Log.getLogWriter().info(database + "deleting trade.sellorders with CID:" + cid 
          + ",SID:" + sid + ",OID:" + oid + query);
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, oid);
      successString ="trade.sellorders with CID:" + cid 
          + ",SID:" + sid + ",OID:" + oid + query;
      break;
    default:
      throw new TestException("incorrect delete statement, should not happen");
    }  
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info(database + "deleted " + rowCount + " rows from" + successString);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }  
  
  protected boolean deleteGfxdOnly(Connection gConn){
    try {
      delete(null, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info("got expected conflict exception, continuing test");
        return false;
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addInsertToDerbyTx(int[] oid, int[] cid, int[] sid, int[] qty,
      String[] status, Timestamp[] time, BigDecimal[] ask, int[] updateCount, 
      SQLException gfxdse){
    Object[] data = new Object[11];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SELLORDERS;
    data[1] = "insert";
    data[2] = oid;
    data[3] = cid;
    data[4] = sid;
    data[5] = qty;
    data[6] = status;
    data[7] = time;
    data[8] = ask;
    data[9] = updateCount;
    data[10] = gfxdse;
    
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
    SQLException gfxdse = (SQLException) data[10];
    
    try {
      //insertToDerbyTable(dConn, cid, cust_name,since, addr, count, size);
      insertToDerbyTable(dConn, (int[])data[2], (int[])data[3], (int[])data[4], 
          (int[])data[5], (String[])data[6], (Timestamp[])data[7], 
          (BigDecimal[])data[8], (int[])data[9], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  protected boolean insertToDerbyTable(Connection conn, int[] oid, int[] cid, int[] sid, 
      int[] qty, String[] status, Timestamp[] time, BigDecimal[] ask, int[] updateCount, 
      int size) throws SQLException {
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    int count =-1;
    
    for (int i=0 ; i<size ; i++) {
      count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status[i], time[i], ask[i], tid);
      if (count != updateCount[i]) {
        Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
          "gfxd inserted " + updateCount[i] +
          " row, but derby inserted " + count + " row in " + getTableName());
      }
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean insertGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return insertGfxdOnly(gConn);
    }
    int size = 1;
    int[] cid = new int[size];
    int[] sid = new int[size];
    int[] oid = new int[size];
    int[] qty = new int[size];
    String[] status = new String[size];
    Timestamp[] time = new Timestamp[size];
    BigDecimal[] ask = new BigDecimal[size];
    int[] updateCount = new int[size];
    boolean[] expectConflict = new boolean[1];
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    SQLException gfxdse = null;

    getKeysFromPortfolio(nonTxConn, cid, sid);
    getDataForInsert(nonTxConn, oid, cid, sid, qty, time, ask, size); //get the data
    for (int i = 0; i< status.length; i++) {
      status[i] = "open";
    }

    int chance = 200;
    if (rand.nextInt(chance) == 0) cid[0] = 0;
    else if (rand.nextInt(chance) == 0) sid[0] = 0;

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    modifiedKeysByOp.put(getTableName()+"_"+oid[0], (Integer)SQLDistTxTest.curTxId.get());
    HashSet<String> parentKeysHold = new HashSet<String>();

    try {
      getKeysForInsert(nonTxConn, cid[0], sid[0], expectConflict, parentKeysHold);

      /* check through batching fk bb now
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


    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    if (batchingWithSecondaryData) {
      //add to fk bb for the fk key hold due to insert into child table
      HashSet<String> holdFKsByThisTx = (HashSet<String>) SQLDistTxTest.foreignKeyHeldWithBatching.get();
      holdFKsByThisTx.addAll(parentKeysHold);
      SQLDistTxTest.foreignKeyHeldWithBatching.set(holdFKsByThisTx);

      hydra.blackboard.SharedMap holdingFKTxIds = SQLTxBatchingFKBB.getBB().getSharedMap();
      Integer myTxId = (Integer) SQLDistTxTest.curTxId.get();
      for (String key: parentKeysHold) {
        HashSet<Integer> txIds = (HashSet<Integer>) holdingFKTxIds.get(key);
        if (txIds == null) txIds = new HashSet<Integer>();
        txIds.add(myTxId);
        holdingFKTxIds.put(key, txIds);
      }
    }

    for(int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Inserting " + i + " times.");
        insertToGfxdTable(gConn, oid, cid, sid, qty, status, time, ask, updateCount, size);
        //the gfxd tx needs to handle prepareStatement failed due to node failure here
        //does not expect critical heap exception etc in current tx testing
        //once these coverage are added, similar handling of exceptions seen in getStmt()
        //need to be added here.
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (expectConflict[0]) {
              ; //if conflict caused by foreign key
            } else {
              if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
              else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
              //check if conflict caused by multiple inserts on the same keys
            }
          } catch (TestException te) {
            if (te.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            } else throw te;
          }

          if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
          removePartialRangeForeignKeys(cid, sid);
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");

          if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
          removePartialRangeForeignKeys(cid, sid); //operation not successful, remove the fk constraint keys

          return false; //not able to handle node failure yet, needs to rollback ops
          // to be confirmed if select query could cause lock to be released
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
          if (batchingWithSecondaryData) cleanUpFKHolds(); //got the exception, ops are rolled back due to #43170
          removePartialRangeForeignKeys(cid, sid); //operation not successful, remove the fk constraint keys
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

    //add this operation also for derby
    if (withDerby) addInsertToDerbyTx(oid, cid, sid, qty, status, time,
        ask, updateCount, gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);

    return true;
  }
  
  protected boolean insertGfxdOnly(Connection gConn){ 
    try {
      insert(null, gConn, 1); 
    }  catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
        return false;
        //delete/update a parent key in another tx could get conflict
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void getKeysForInsert(Connection conn, int cid, int sid, boolean[] expectConflict, HashSet<String> parentKeys)
    throws SQLException {
     String sql = null;
     ResultSet rs = null;
     int txId = (Integer) SQLDistTxTest.curTxId.get();
     String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
     //check if foreign key exists
     //test will add cid =0 or sid =0 to verify, tx could detect non existing fks
     sql = "select * from trade.portfolio where cid = " + cid + " and sid =" + sid;
     Log.getLogWriter().info(database + sql);
     rs = conn.createStatement().executeQuery(sql); 
     if (!rs.next()) {
       Log.getLogWriter().info(database + "foreign key cid " + cid + ", sid " + sid + " does not exist " +
           "in the parent table, should get foreign key constraint exception");
       //this key may not be able to held, due the the delete in parent table,
       //so instead of fk constraint violation, we may see lock not held
     }
     rs.close();
     //check if foreign key has been locked, if other tx hold the key for update etc, 
     //this insert should gets conflict
     boolean hasForeignKeyConflict = hasForeignKeyConflict(TradePortfolioDMLDistTxStmt.
         getTableName() + cidPrefix + cid + sidPrefix + sid, txId);
     
     //track the parentKey hold
     parentKeys.add(TradePortfolioDMLDistTxStmt.getTableName() + cidPrefix + cid + sidPrefix + sid);
     
     //we need to modify the RangeForeignKey and check if the range foreign key has been taken, 
     //if not, subsequent delete in parent table could be blocked
     hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
     String key = getTableName() + cidPrefix + cid + sidPrefix + sid;
     RangeForeignKey cid_sidRangeKey = (RangeForeignKey) rangeForeignKeys.get(key);

     if (cid_sidRangeKey == null) cid_sidRangeKey = new RangeForeignKey(key);
     
     boolean hasRangeForeignKeyConflict = cid_sidRangeKey.hasConflictAddPartialRangeKey(txId);
  
     rangeForeignKeys.put(getTableName() + cidPrefix + cid + sidPrefix + sid, cid_sidRangeKey);
    
     ArrayList<String> curTxRangeFK = (ArrayList<String>)SQLDistTxTest.curTxFKHeld.get();
     curTxRangeFK.add(key);
     SQLDistTxTest.curTxFKHeld.set(curTxRangeFK);
    
     expectConflict[0] = hasForeignKeyConflict || hasRangeForeignKeyConflict;
     if (hasForeignKeyConflict) {
       Log.getLogWriter().info(database + "should expect lock not held/conflict exception here" 
          // + " but due to non update of primary key in parent table, will relax a little here." +
          //" however the insert in parent should cause conflict exception here instead of " +
          //" foreign key constraint exception here"
          );
     }     
   }
  
  protected void removePartialRangeForeignKeys(int[] cid, int[] sid) {
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
    RangeForeignKey cid_sidRangeKey = (RangeForeignKey) rangeForeignKeys.get(getTableName() 
        + cidPrefix + cid[0] + sidPrefix + sid[0]);    
  
    if (cid_sidRangeKey!=null) cid_sidRangeKey.removePartialRangeKey(txId);
    Log.getLogWriter().info("removing the partial range foreign key for this TXID:" + txId);
  }
  
  protected void insertToGfxdTable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String[] status, Timestamp[] time, BigDecimal[] ask, int[] updateCount,int size) 
      throws SQLException {
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) {
      stmt = useDefaultValue? getStmtThrowException(conn, insertWithDefaultValue) 
          : getStmtThrowException(conn, insert);
    }
    else stmt = useDefaultValue? conn.prepareStatement(insertWithDefaultValue) : 
      conn.prepareStatement(insert);
    //PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {      
      updateCount[i] = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status[i], time[i], ask[i], tid);
      
    }    
  }
  
  public static String getTableName() {
    return "sellorders";
  }

  @SuppressWarnings("unchecked")
  @Override
  public void queryDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[10];
    List<Struct> gfxdList = (List<Struct>) data[9];
    ResultSet derbyRS = null;
    
    try {
      //query(dConn, whichQuery, status, ask, cid, oid, orderTime, tid); 
      derbyRS = query(dConn, (Integer)data[2], (String[])data[3], (BigDecimal[])data[4],          
         (int[])data[5], (int[])data[6], (Timestamp)data[7], (Integer)data[8]); 
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
    int numOfNonUniq = select.length/2; //how many query statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    String[] status = new String[2];
    BigDecimal[] ask = new BigDecimal[2];
    int[] cid = new int[5];  //test In for 5 
    int[] oid = new int[5];
    int tid = getMyTid();
    Timestamp orderTime = getRandTime();
    getStatus(status);
    getAsk(ask);
    getCids(nonTxConn, cid);
    getOids(oid);
    
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      gfxdRS = query (gConn, whichQuery, status, ask, cid, oid, orderTime, tid);   
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
        Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
        return false; //not able to handle node failure yet, needs to rollback ops
                    // to be confirmed if select query could cause lock to be released
      }
      
      gfxdse = se;
    }
    List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS, false);
    /*
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
    
    addQueryToDerbyTx(whichQuery, status, ask, cid, oid, orderTime, tid, gfxdList, gfxdse);

    return true;
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
    else {
      for (int i = 0; i < 10; i++) {
        Log.getLogWriter().info("RR: executing query(getCids) " + i + "times");
        try {
          getCids(gConn, cid);
        } catch (TestException te) {
          if (te.getMessage().contains("Conflict detected in transaction operation and it will abort") && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          } else throw te;
        }
        break;
      }
    }
    getOids(oid);

    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();

    for (int i = 0; i < 10; i++) {
      Log.getLogWriter().info("RR: executing query " + i + " times.");
      if (dConn != null) {
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
          gfeRS = query(gConn, whichQuery, status, ask, cid, oid, orderTime, tid);
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
          if(se.getSQLState().equals("X0Z02") && (i < 9)){
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          }
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
          gfeRS = query(gConn, whichQuery, status, ask, cid, oid, orderTime, tid);
        } catch (SQLException se) {
          if (se.getSQLState().equals("42502") && SQLTest.testSecurity) {
            Log.getLogWriter().info("Got expected no SELECT permission, continuing test");
            return;
          } else if (alterTableDropColumn && se.getSQLState().equals("42X04")) {
            Log.getLogWriter().info("Got expected column not found exception, continuing test");
            return;
          } else if(se.getSQLState().equals("X0Z02") && (i < 9)){
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          }
          else SQLHelper.handleSQLException(se);
        }
        try {
          if (gfeRS != null)
            ResultSetHelper.asList(gfeRS, false);
          else if (isHATest)
            Log.getLogWriter().info("could not get gfxd query results after retry due to HA");
          else if (setCriticalHeap)
            Log.getLogWriter().info("could not get gfxd query results after retry due to XCL54");
          else
            throw new TestException("gfxd query returns null and not a HA test");
        } catch (TestException te) {
          if (te.getMessage().contains("Conflict detected in transaction operation and it will abort") && (i < 9)) {
            Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
            continue;
          } else throw te;
        }
      }
      break;
    }
    SQLHelper.closeResultSet(gfeRS, gConn);
  }

  @SuppressWarnings("unchecked")
  protected void addQueryToDerbyTx(int whichQuery, String[] status, 
      BigDecimal[] ask, int[] cid, int[] oid, Timestamp orderTime, int tid, 
      List<Struct> gfxdList, SQLException gfxdse){
    Object[] data = new Object[11];      
    data[0] = DMLDistTxStmtsFactory.TRADE_SELLORDERS;
    data[1] = "query";
    data[2] = whichQuery;
    data[3] = status;
    data[4] = ask;
    data[5] = cid;
    data[6] = oid;
    data[7] = orderTime;
    data[8] = tid;
    data[9] = gfxdList;
    data[10] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
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
  

  @SuppressWarnings("unchecked")
  @Override
  public void updateDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[12];
    
    try {
      //updateDerbyTable(Connection conn, int[] cid, int[] cid2, 
      //int[] sid, BigDecimal[] ask, int[] qty, Timestamp[] orderTime, 
      //String status, ArrayLit<Integer> oids, int[] whichUpdate, int[] updateCount, 
      //int size)
      updateDerbyTable(dConn, (int[])data[2], (int[])data[3], 
          (int[])data[4], (BigDecimal[])data[5], (int[])data[6], 
          (Timestamp[])data[7], (String)data[8], (ArrayList<Integer>)data[9],
          (int[])data[10], (int[])data[11], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }

  }
  
  protected void updateDerbyTable(Connection conn, int[] cid, int[] cid2, 
      int[] sid, BigDecimal[] ask, int[] qty, Timestamp[] orderTime, 
      String status, ArrayList<Integer> oids, int[] whichUpdate, int[] gfxdupdateCount, 
      int size) throws SQLException {
    PreparedStatement pstmt = null;
    PreparedStatement ursstmt = null;
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("select for update in derby, myTid is " + tid);
    //first oid case
    for (int i=0 ; i < size ; i++) {
      String sql = selectForUpdate[whichUpdate[i]];
      Log.getLogWriter().info("select for update statement is " + sql);
      if (SQLTest.testPartitionBy)    pstmt = getCorrectTxStmt(conn, whichUpdate[i]);
      else pstmt = getStmt(conn, updateByPK[whichUpdate[i]]); //use only this after bug#39913 is fixed

      if (pstmt!=null) {
        try {
          ursstmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_UPDATABLE);
        } catch (SQLException se) {
          SQLHelper.handleSQLException(se);
        }
        
        ResultSet rs = getSelectForUpdateRS(ursstmt, cid[i], cid2[i], sid[i], 
            ask[i], orderTime[i], status, whichUpdate[i], size);
        
        Log.getLogWriter().info("oids size is " + oids.size());
        int[] derbyUpdateCount = new int [oids.size()];
        updateTable(null, rs, qty[i], status, orderTime[i], oids, whichUpdate[i], 
            derbyUpdateCount, size, false); //using updateable result set only to work around #43988
        
        for (int j=0; j<oids.size(); j++) {         
          if (derbyUpdateCount[j] != gfxdupdateCount[j]){
            Log.getLogWriter().info("Derby update has different row count from that of gfxd, " +
                  "gfxd updated " + gfxdupdateCount[j] +
                  " rows, but derby updated " + count + " rows");
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean updateGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return updateGfxdOnly(gConn);
    }
    if (!SQLDistTxTest.isTicket43188fiFixed && SQLDistTxTest.useThinClientDriverInTx)
      return true; //workaround #43188 Updatable resultset is not supported yet using thin client driver

    if (partitionKeys == null) setPartitionKeys();
    int size =1;
    int[] sid = new int[size];
    BigDecimal[] ask = new BigDecimal[size];
    Timestamp[] orderTime = new Timestamp[size];
    int[] cid = new int[size];
    int[] cid2 = new int[size];
    int[] qty = new int[size];
    ArrayList<Integer> oids = new ArrayList<Integer>();
    String status = statuses[rand.nextInt(statuses.length)];

    int[] whichUpdate = new int[size];

    SQLException gfxdse = null;

    boolean success = getDataForUpdate((Connection)SQLDistTxTest.gfxdNoneTxConn.get(), cid, cid2,
        sid, qty, orderTime, ask, whichUpdate, size);
    if (!success) return true; //did not get data or not commit early txs, it is a no op

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    try {
      getKeysForUpdate((Connection)SQLDistTxTest.gfxdNoneTxConn.get(), modifiedKeysByOp,
          whichUpdate[0], cid[0], cid2[0], sid[0], ask[0], orderTime[0], oids);
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this update op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else SQLHelper.handleSQLException(se); //else gfxdse = se;
    }

    //Log.getLogWriter().info("oids size after get keys is " + oids.size());
    int[] updateCount = new int [oids.size()];

    for (int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Updating " + i + " times.");
        success = updateGfxdTable(gConn, cid, cid2, sid,
            ask, qty, orderTime, status, oids, whichUpdate, updateCount, size);
        if (!success) {
        /*
        if (SQLTest.isEdge && isHATest && !isTicket48176Fixed &&
            batchingWithSecondaryData &&(Boolean) SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
          SQLDistTxTest.failedToGetStmtNodeFailure.set(false);
          return false; //due to node failure, need to rollback tx
        }
        else return true; //due to unsupported exception
        */

          //handles get stmt failure conditions -- node failure or unsupported update on partition field
          if (isHATest && (Boolean)SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
            SQLDistTxTest.failedToGetStmtNodeFailure.set(false); //reset flag
            return false; //due to node failure, assume txn rolled back
          }
          if ((Boolean)SQLDistTxTest.updateOnPartitionCol.get()) {
            SQLDistTxTest.updateOnPartitionCol.set(false); //reset flag
            return true; //assume 0A000 exception does not cause txn to rollback
          }
        }
        //partitioned on partitoned key, needs to check if using URS will rollback
        //the tx, if so test needs to be modified. which may needs to separate update
        //by PK and URS (no of column case) and return accordingly here
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
            else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
          } catch (TestException te) {
            if (te.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            } else throw te;
          }
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          SQLHelper.handleSQLException(se);
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addUpdateToDerbyTx(cid, cid2, sid, ask, qty, orderTime, status, oids,
        whichUpdate, updateCount, gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addUpdateToDerbyTx(int[] cid, int[] cid2, int[] sid,
      BigDecimal[] ask, int[] qty, Timestamp[] orderTime, String status,
      ArrayList<Integer> oids, int[] whichUpdate, int[] updateCount, SQLException gfxdse){
     Object[] data = new Object[13];      
     data[0] = DMLDistTxStmtsFactory.TRADE_SELLORDERS;
     data[1] = "update";
     data[2] = cid;
     data[3] = cid2;
     data[4] = sid;
     data[5] = ask;
     data[6] = qty;
     data[7] = orderTime;
     data[8] = status;
     data[9] = oids;  
     data[10] = whichUpdate;
     data[11] = updateCount;
     data[12] = gfxdse;
     
     ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
     if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
     derbyOps.add(data);
     SQLDistTxTest.derbyOps.set(derbyOps);
   }
  
  protected boolean getDataForUpdate(Connection regConn, int[] cid, int[] cid2, 
      int[] sid, int[] qty, Timestamp[] orderTime, BigDecimal[] ask, 
      int[] whichUpdate, int size) {
    Connection conn = getAuthConn(regConn);
    int cidRange = 3;
    if (!(Boolean)SQLDistTxTest.commitEarly.get()) 
      return false; //only committed early could update these select for update statement
    
    int[] cids = new int[size*2];
    int[] sids = new int[size*2];
    if (!getDataFromResult(conn, cid, sid)) return false; //did not get data
    for (int i=0; i<size; i++) {
      qty[i] = getQty();
      orderTime[i] = getRandTime();
      ask[i] = getPrice();
      whichUpdate[i] = rand.nextInt(selectForUpdate.length);
      cid[i] = cids[i];
      cid2[i] = cid[i] + cidRange;
      sid[i] = sids[i];
    }
    return true;
  }
  
  protected boolean updateGfxdOnly(Connection gConn){
    try {
      update(null, gConn, 1);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info("got expected conflict exception, continuing test");
        return false;
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  //need to be changed to be similar to networth table once the update is actually implemented
  //for now this is used to avoid the #43909 test issue
  protected int getWhichUpdate(int numOfNonUniq, int total) {
    int whichOne = super.getWhichOne(numOfNonUniq, total);
    if (isConcUpdateTx){
      //to avoid update ask column in concUpdateTx tx to test conc update in tx
      if (whichOne == 2 || whichOne == 6)
        whichOne--;
      else if (whichOne == 3 || whichOne == 7)
        whichOne = whichOne-2;
    }
    return whichOne;
  }
  
  protected void getKeysForUpdate(Connection conn, HashMap<String, Integer > keys, 
      int whichUpdate, int cid, int cid2, int sid, BigDecimal ask, 
      Timestamp orderTime, ArrayList<Integer> oids) throws SQLException {
    String sql = null;
    ResultSet rs = null;
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    switch (whichUpdate) {
    case 0:
      // "select oid, status from trade.sellorders where sid = ? and ask>? for update of status ",  
      sql = "select oid from trade.sellorders where sid=" + sid + " and ask > " + ask;
      Log.getLogWriter().info(database + "executing " + sql);
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int oid = rs.getInt(1);
        Log.getLogWriter().info(database + "OID: " + oid + " exists to be locked by select for update");
        if (oids.size() == 0) oids.add(oid);
        else {
          if (rand.nextBoolean()) oids.add(oid);
          else Log.getLogWriter().info(database + "OID: " + oid + " will not be modified by update");
        }        
        keys.put(getTableName()+"_"+oid, txId); //holding the keys
      }    
      rs.close();  
      break;
    case 1:
    //"select * from trade.sellorders where cid >= ? and cid <? for update of qty, status ",  
      sql = "select oid from trade.sellorders where cid>="+cid + " and cid <" + cid2;
      Log.getLogWriter().info(database + "executing " + sql);
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int oid = rs.getInt(1);
        Log.getLogWriter().info(database + "OID: " + oid + " exists to be locked by select for update");
        if (oids.size() == 0) oids.add(oid);
        else {
          if (rand.nextBoolean()) oids.add(oid);
          else Log.getLogWriter().info(database + "OID: " + oid + " will not be modified by update");
        }        
        keys.put(getTableName()+"_"+oid, txId); //holding the keys
      }     
      rs.close(); 
      break;
    
    case 2:
    //"select * from  trade.sellorders where cid = ? and sid= ? for update
      sql = "select oid from trade.sellorders where cid="+cid + " and sid =" + sid;
      Log.getLogWriter().info(database + "executing " + sql);
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int oid = rs.getInt(1);
        Log.getLogWriter().info(database + "OID: " + oid + " exists to be locked by select for update");
        if (oids.size() == 0) oids.add(oid);
        else {
          if (rand.nextBoolean()) oids.add(oid);
          else Log.getLogWriter().info(database + "OID: " + oid + " will not be modified by update");
        }        
        keys.put(getTableName()+"_"+oid, txId); //holding the keys
      }      
      rs.close();   
      break;
    default:
     throw new TestException ("Wrong update statement here");
    }  
  }
  
  //this method returns false if update on partitioned key
  @SuppressWarnings("unchecked")
  protected boolean updateGfxdTable(Connection conn, int[] cid, int[] cid2, 
      int[] sid, BigDecimal[] ask, int[] qty, Timestamp[] orderTime, 
      String status, ArrayList<Integer> oids, int[] whichUpdate, int[] updateCount, 
      int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    Log.getLogWriter().info(database + "select for update in gemfirexd, myTid is " + tid);
    for (int i=0 ; i<size ; i++) {
      boolean usePK = false; //to work around issue #43988
      String sql = selectForUpdate[whichUpdate[i]];
      Log.getLogWriter().info(database + "select for update statement is " + sql);
      try {
        stmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_UPDATABLE);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equals("0A000")) {
          if (whichUpdate[i] == 0 && partitionKeys.contains("status")) {
            SQLDistTxTest.updateOnPartitionCol.set(true);
            return false;
          }
          else if (whichUpdate[i] == 1 && 
              (partitionKeys.contains("qty") || partitionKeys.contains("status"))) {
            SQLDistTxTest.updateOnPartitionCol.set(true);
            return false;
          }
          else SQLHelper.handleSQLException(se);
        } else throw se; //let caller handle node failure issue
      }
      
      ResultSet rs = getSelectForUpdateRS(stmt, cid[i], cid2[i], sid[i], 
          ask[i], orderTime[i], status, whichUpdate[i], size);
      ArrayList<ResultSet>selectForUpdateRSList = (ArrayList<ResultSet>)
        SQLDistTxTest.selectForUpdateRS.get();
      selectForUpdateRSList.add(rs);
      SQLDistTxTest.selectForUpdateRS.set(selectForUpdateRSList);
      
      if (usePK) {
        if (SQLTest.testPartitionBy)  stmt = getCorrectTxStmt(conn, whichUpdate[i]);
        else stmt = getStmt(conn, updateByPK[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
        if (stmt == null) return false;
        else {
          boolean success = updateTable(stmt, rs, qty[i], status, orderTime[i], oids, 
              whichUpdate[i], updateCount, size, usePK);  
          if (!success) return success;
        }
      } else {
        //Log.getLogWriter().info("gfxd update table oids size is " + oids.size());
        boolean success = updateTable(stmt, rs, qty[i], status, orderTime[i], 
            oids, whichUpdate[i], updateCount, size, usePK);  
        if (!success) return success;
     }
         
    } 
    return true;
  }
  
  protected ResultSet getSelectForUpdateRS(PreparedStatement stmt, int cid, int cid2, 
      int sid, BigDecimal ask, Timestamp orderTime, String status, 
      int whichUpdate, int size) throws SQLException {
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    switch (whichUpdate) {
    case 0: 
      //"select oid, status from trade.sellorders where sid = ? and ask>? for update of status ",
      stmt.setInt(1, sid);
      stmt.setBigDecimal(2, ask);
      Log.getLogWriter().info(database + " selecting for update from trade.sellorders with SID:" + sid + ",ASK:" + ask + " QUERY: " +selectForUpdate[whichUpdate]);
      break;
    case 1: 
      // "select * from trade.sellorders where cid >= ? and cid <? for update of qty, status ",
      stmt.setInt(1, cid);
      stmt.setInt(2, cid2);
      Log.getLogWriter().info(database  + "selecting for update from trade.sellorders with 1_CID:" + cid + ",2_CID:" + cid2 +  " QUERY: " +selectForUpdate[whichUpdate]);
      break;
    case 2: //"select * from  trade.sellorders where cid = ? and sid= ? for update ",
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      Log.getLogWriter().info(database + "selecting for update from trade.sellorders with CID:" + cid + ",SID:" + sid + " QUERY: " + selectForUpdate[whichUpdate]);
      break;
    default:
      throw new TestException (database + "Wrong select for update sql string here"); 
    }
    return stmt.executeQuery();
  }
  
  //if updatable resultset gets unsupported exception due to update on 
  //partitioned key, this method return false
  //otherwise it returns true as update stmt has been checked already
  protected boolean updateTable (PreparedStatement stmt, ResultSet rs, 
      int qty, String status, Timestamp orderTime, ArrayList<Integer> oids, int whichUpdate, 
      int[] updateCount, int size, boolean usePK) throws SQLException {
    boolean[] success = new boolean[1];
           
    String txid =  SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = "Derby - ";
    if (stmt != null ){
      int txId = (Integer) SQLDistTxTest.curTxId.get();
      database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    }
       
    if (usePK) { //use pk based update statement
      for (int i=0; i<oids.size(); i++) {
        updateCount[i] = updateTableUsngPKStmt(stmt, qty,  
            status, orderTime, oids.get(i), whichUpdate);
      }      
    } else { //use updatable statement available
      Log.getLogWriter().info(database + "update using resultset updateRow");
      while (rs.next()) {
        int oid = rs.getInt("OID");
        for (int i=0; i<oids.size(); i++) {
          if ( oid == oids.get(i)) {
            updateCount[i] = updateTableUsingURS(rs, qty,  
                status, orderTime, oids.get(i), whichUpdate, success);
            if (success[0] == false) return false;
          }
        }
      }
    } 
    return true;
  }
  
  protected int updateTableUsngPKStmt(PreparedStatement stmt, int qty,  
      String status, Timestamp orderTime, int oid, int whichUpdate) throws SQLException {
    int rowCount = 0; 
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    switch (whichUpdate) {
    case 0: 
      //"update trade.sellorders set status = ?  where oid = ? ",       
      Log.getLogWriter().info(database + "updating trade.sellorders with STATUS:" + status  + 
          "where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      stmt.setString(1, status);
      stmt.setInt(2, oid);      
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with STATUS:" + status  + 
          "where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      break;
    case 1: 
      //"update trade.sellorders set qty = ?,  status = ? where where oid = ?  ",
      Log.getLogWriter().info(database + "updating trade.sellorders with QTY:" + qty +
          ",STATUS:" + status + "where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      stmt.setInt(1, qty);
      stmt.setString(2, status);
      stmt.setInt(3, oid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with QTY:" + qty +
          ",STATUS:" + status + "where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      break;
    case 2: 
      //update trade.sellorders set order_time = ? where oid = ? ,
      Log.getLogWriter().info(database + "updating trade.sellorders with ORDERTIME:" + orderTime 
          + " where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      stmt.setTimestamp(1, orderTime);
      stmt.setInt(2, oid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.sellorders with ORDERTIME:" + orderTime 
          + " where OID:" + oid + " QUERY: " + updateByPK[whichUpdate]);
      break;
    default:
     throw new TestException (database + "Wrong update sql string here");
    }
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }
  
  protected int updateTableUsingURS(ResultSet rs, int qty, String status, 
      Timestamp orderTime, int oid, int whichUpdate, boolean[] success) 
      throws SQLException {
    
    int rowCount = 1; 
    
    String txid =  "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
       
    switch (whichUpdate) {
    case 0: 
      //"update  status = ?        
      Log.getLogWriter().info(txid + "updating tarde.sellorders table using URS with STATUS: " + status  + 
          "where OID:" + oid + " QUERY: " + "update  status = ? where oid = ?");
      //select for update of column (status) has checked already 
      //whether updating on partition column
      rs.updateString("STATUS", status);    
      rs.updateRow();

      break;
    case 1: 
      //"update trade.sellorders set qty = ?,  status = ? where where oid = ?  ",
      Log.getLogWriter().info(txid + "updating trade.sellorders table using URS with QTY:" + qty + ", " +
          "STATUS:" + status + " where OID:" + oid + " QUERY: " + "update trade.sellorders set qty = ?,  status = ? where where oid = ?  ");
      //select for update of column (status and qty) has checked already 
      //whether updating on partition column
      rs.updateInt("QTY", qty);
      rs.updateString("STATUS", status);
      rs.updateRow();
      break;
    case 2: 
      //"update trade.sellorders set order_time = ? where where oid = ? ",
      Log.getLogWriter().info(txid + "updating trade.sellorders table using URS with ORDERTIME:"
          + orderTime + " where OID:" + oid + " QUERY: " + "update trade.sellorders set order_time = ? where where oid = ? ");
      try {
        rs.updateTimestamp("ORDER_TIME", orderTime);
        rs.updateRow();
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equals("0A000") && 
           partitionKeys.contains("order_time")) {
          rowCount = 0;
          success[0] = false;
          return rowCount;
        } else throw se;
      }      
      break;
    default:
     throw new TestException ("Wrong updatable resultset used here");
    }
    success[0] = true;
    return rowCount;
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  private PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      //  "select oid, status from trade.sellorders where sid = ? and ask>? and status = 'open' for update of status ",  
      // update status
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateByPK[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, updateByPK[whichUpdate]);
      break;
    case 1: 
      // "select * from trade.sellorders where cid >= ? and cid <? order_time >? and status = 'open' for update of qty, status ",  
      // update qty, status
      if (partitionKeys.contains("qty") || partitionKeys.contains("status") ) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateByPK[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, updateByPK[whichUpdate]);
      break;
    case 2: //update cid, sid
      // "select * from  trade.sellorders where cid = ? and sid= ? for update ",  
      if (partitionKeys.contains("order_time")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateByPK[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, updateByPK[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  private PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectTxStmt(conn, whichUpdate, partitionKeys);
  }


}
