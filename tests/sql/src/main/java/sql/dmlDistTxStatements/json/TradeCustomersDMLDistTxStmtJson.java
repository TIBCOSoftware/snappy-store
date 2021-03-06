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
package sql.dmlDistTxStatements.json;

import hydra.Log;
import hydra.RemoteTestModule;
import hydra.TestConfig;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlDistTxStatements.DMLDistTxStmtIF;
import sql.dmlStatements.json.TradeCustomersDMLStmtJson;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxPartitionInfoBB;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

import com.gemstone.gemfire.cache.query.Struct;

public class TradeCustomersDMLDistTxStmtJson extends TradeCustomersDMLStmtJson
  implements DMLDistTxStmtIF {
  
  public static boolean isReplicate;
  protected static boolean reproduce50010 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50010, false);
  protected static boolean reproduce50001 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50001, true);
  
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
  
  protected static String[] update = {
    "update trade.customers set cid = ? where cid=? and tid = ? ", 
    "update trade.customers set cust_name = ? , addr = ? where cid=? and tid <>?", 
    "update trade.customers set cust_name = ? , addr = ? where cid=? and tid <=? ",
    "update trade.customers set cust_name = ?, since =? where cid=? and tid >? ",
    };
  
  
  protected static String[] updateJSON = {
    "update trade.customers set cid = ? , json_details = ? where cid=? and tid = ? ", 
    "update trade.customers set cust_name = ? , addr = ? , json_details = ? where json_evalpath(json_Details, '$..networth.cid')=? and tid <>?", 
    "update trade.customers set cust_name = ? , addr = ? , json_details = ? where json_evalpath(json_Details, '$..networth.cid')=? and tid <=? ",
    "update trade.customers set cust_name = ?, since =? , json_details = ? where cid=? and json_evalpath(json_Details, '$..networth.tid') >? ",
    };
  
  protected static String[] select = {
    "select * from trade.customers where tid = ?",
    "select cid, since, cust_name from trade.customers where tid<>? and cid >?",
    "select since, addr, cust_name from trade.customers where (tid<? or cid <=?) and since >? and tid <= ?",
    "select addr, since, cust_name from trade.customers where (cid >? or since <?) and tid > ?",
    };
  
  protected static String[] selectJSON = {  
    "select * from trade.customers where tid = ?",
    "select cid, since, cust_name from trade.customers where tid<>? and json_evalpath(json_Details, '$..networth.cid') >?",
    "select since, addr, json_evalpath(json_Details, '$..networth.cust_name') from trade.customers where (tid<? or cid <=?) and since >? and json_evalpath(json_Details, '$..networth.tid') <= ?",
    "select addr, since, json_evalpath(json_Details, '$..networth.cust_name')  from trade.customers where (json_evalpath(json_Details, '$..networth.cid') >? or since <?) and json_evalpath(json_Details, '$..networth.tid') > ?",
    };
  
  protected void updateGfxdTable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    Statement s = getStmt(conn);
    int tid = getMyTid();
    for (int i=0 ; i<size ; i++) {;
      
      if (whichUpdate[i] == 0) {
        Log.getLogWriter().info("Updating gemfirexd on primary key, expect exception thrown here with TID:"+tid + "QUERY:" + updateJSON[whichUpdate[i]]); 
        
        stmt = getUnsupportedStmt(conn, updateJSON[whichUpdate[i]]);
      }//need to comment out these code when update primary key is supported later
      else {
        if (SQLTest.testPartitionBy) { //will be removed after bug #39913 is fixed
          stmt = getCorrectTxStmt(conn, whichUpdate[i]);
        } 
        else {
          stmt = getStmt(conn, updateJSON[whichUpdate[i]]);
        }

        //stmt could be null if update on partition column, not expect txn rollback
        //stmt could be null if node failure condition hit.
        if (stmt!=null) {
          if (!SQLTest.testPartitionBy && rand.nextBoolean())
            updateCount[i] = updateTable(s, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
          else
            updateCount[i] = updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
          
          

        }  
      }
    }  
  }
  
  @Override
  protected int updateTable(Statement stmt, int newCid, int cid, String cust_name,
      Date since, String addr, int tid, int whichUpdate) throws SQLException {    
    int rowCount = 0;
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    String query , jsonString =""  , jsonLog ="";
    HashMap<String,Object> map = null;
    if (SQLHelper.isDerbyConn(stmt.getConnection()))      
         query= " QUERY: " + update[whichUpdate];
    else {
         map = (HashMap<String,Object>) getCurrentRowOfUpdate(stmt.getConnection() , cid);
         query= " QUERY: " + updateJSON[whichUpdate];
    }
    
    
    switch (whichUpdate) {
    case 0: 
      //Log.getLogWriter().info(database + "updating trade.customers with OLDCID:" + cid + ",NEWCID:" + newCid + query);
      /*
      rowCount = stmt.executeUpdate("update trade.customers" +
          " set cid =" + newCid +
          " where cid=" + cid +
          " and tid =" + tid);
      */ //uncomment this to produce bug 39313 or 39666
      break;
    case 1: 
      // "update trade.customers set cust_name = ? , addr =? where cid=? and tid =?", 
      if ( !SQLHelper.isDerbyConn(stmt.getConnection())) {
        jsonString = getJSON(cid, tid, (Date) map.get("since"), cust_name, addr);
        jsonLog = ",JSON_DETAILS: " + jsonString;
        }
      
      Log.getLogWriter().info(database + "updating trade.customers  with CUSTNAME:" + cust_name + 
          ",ADDR:" + jsonLog + "where CID:" + cid + ",TID" + tid + query); //use update count to see if update successful of not
      rowCount = stmt.executeUpdate("update trade.customers" +
          " set cust_name ='" + cust_name +
          "' , addr ='" + addr +
          "' json_details = '" + jsonString +
          "' where cid=" + cid +
          " and tid <>" + tid); //may or may not be successful, depends on the cid and tid       
      Log.getLogWriter().info(database + "updated " + rowCount + " rows trade.customers CUSTNAME:" + cust_name + 
          ",ADDR:" + ",CID:" + cid + ",TID" + tid + query);
      break;
    case 2: //update name, addr
      //"update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",
      if ( !SQLHelper.isDerbyConn(stmt.getConnection())) {
        jsonString = getJSON(cid, tid, (Date) map.get("since"), cust_name, addr);
        jsonLog = ",JSON_DETAILS: " + jsonString;
        }
      
        Log.getLogWriter().info(database + "updating trade.customers with CUSTNAME:" + cust_name + 
            ",ADDR:" + addr + jsonLog +  "where CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        rowCount = stmt.executeUpdate("update trade.customers" +
            " set cust_name ='" + cust_name +
            "' , addr ='" + addr +
            "' json_details = '" + jsonString +
            "' where cid=" + cid +
            " and tid <=" + tid);
        Log.getLogWriter().info(database + "updated " + rowCount + "rows in trade.customers with CUSTNAME:" + cust_name + 
            ",ADDR:" + addr +  ",CID:" + cid  + ",TID:" + tid + query);
      break;
    case 3: //update name, since
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid =? " 
      if ( !SQLHelper.isDerbyConn(stmt.getConnection())) {
        jsonString = getJSON(cid, tid, since, cust_name, (String)map.get("addr"));
        jsonLog = ",JSON_DETAILS: " + jsonString;
        }
        Log.getLogWriter().info(database + "updating trade.customers with CUSTNAME:" + cust_name + 
            ",SINCE:" + since +  ",CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        rowCount = stmt.executeUpdate("update trade.customers" +
            " set cust_name ='" + cust_name +
            "' , since ='" + since +
            "' json_details = '" + jsonString +
            "' where cid=" + cid +
            " and tid >" + tid);
        Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.customers CUSTNAME:" + cust_name + 
            ",SINCE:" + since +  ",CID:" + cid  + ",TID:" + tid + query);
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
  
  protected void updateDerbyTable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      if (whichUpdate[i]== 0) {
        Log.getLogWriter().info("this update primary key/partiton key case in gfe, do not update");
        return;
      } //nedd to comment out this portion of code when gemfirexd supports update primary key
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(conn, whichUpdate[i]);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      

      if (stmt!=null) {
        count = updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
        
        if (count != updateCount[i]){         
          Log.getLogWriter().info("Derby update has different row count from that of gfxd " +
                  "gfxd updated " + updateCount[i] +
                  " rows, but derby updated " + count + "rows");
          
        } else 
          Log.getLogWriter().info("Derby updates " + count + " rows");
      }
    } 
  }
  
  protected void updateGfxdOnlyTable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int size) {
    PreparedStatement stmt = null;
    Statement s = getStmt(conn);
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      
      if (whichUpdate[i] == 0) {
        Log.getLogWriter().info("update partition key statement is " + updateJSON[whichUpdate[i]]);
        stmt = getUnsupportedStmt(conn, updateJSON[whichUpdate[i]]);
      }
      else {
        if (SQLTest.testPartitionBy) { //will be removed after bug #39913 is fixed
          stmt = getCorrectTxStmt(conn, whichUpdate[i]);
        } 
        else {   
          stmt = getStmt(conn, updateJSON[whichUpdate[i]]);
        }
        
        try {
          if (stmt!=null)
            if (rand.nextBoolean())
              updateTable(s, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
            else
              updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
        } catch (SQLException se) {
          if (se.getSQLState().equals("42502") && testSecurity) {
            Log.getLogWriter().info("Got the expected exception for authorization," +
               " continuing tests");
          } else
            SQLHelper.handleSQLException(se);
        }    
      }
    }  
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public boolean insertGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
     return insertGfxdOnly(gConn);
    }
    
    //with derby case
    int chance= 10;
    boolean useBatchInsert = rand.nextInt(chance) == 1 ? true : false;
    
    int size = useBatchInsert? 5: 1;
    int[] cid = new int[size];
    String[] cust_name = new String[size];
    Date[] since = new Date[size];
    String[] addr = new String[size];
    SQLException gfxdse = null;
    int[] updateCount = new int[size];
    getDataForInsert(cid, cust_name,since,addr, size); //get the data
    if (rand.nextInt(100) == 1 && SQLDistTxTest.ticket43170fixed) --cid[0];  //add some insert/insert conflict
    
    if (useBatchInsert && rand.nextInt(100) == 1) {
      cid[size-1] = rand.nextInt((int) SQLBB.getBB().getSharedCounters().
          read(SQLBB.tradeCustomersPrimary)) + 1; 
      Log.getLogWriter().info("possibly use duplicate cid: " + cid[size-1]);
      //test batch insert with possible duplicate
    }
    
    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    for (int i=0; i<size; i++) {
      modifiedKeysByOp.put(getTableName()+"_"+cid[i], (Integer)SQLDistTxTest.curTxId.get());
    }
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      insertToGfxdTable(gConn, cid, cust_name,since, addr, updateCount, size);
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
        gfxdse = se;
        SQLDistTxTest.batchInsertToCustomersSucceeded.set(false);
      }
    } 
    
    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, null, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, null, hasSecondary, false);
    
    SQLDistTxTest.cidInserted.set(cid[0]);
    
    //add this operation for derby
    addInsertToDerbyTx(cid, cust_name, since, addr, updateCount, gfxdse);
    
    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addInsertToDerbyTx(int[] cid, String[] cust_name, 
      Date[] since, String[] addr, int[] updateCount, SQLException gfxdse){
    Object[] data = new Object[8];      
    data[0] = DMLDistTxStmtsFactory.TRADE_CUSTOMERS;
    data[1] = "insert";
    data[2] = cid;
    data[3] = cust_name;
    data[4] = since;
    data[5] = addr;
    data[6] = updateCount;
    data[7] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }

  public static String getTableName() {
    return "customers";
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public void insertDerby(Connection dConn, int index) {
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[7];
    
    try {
      //insertToDerbyTable(dConn, cid, cust_name,since, addr, count, size);
      insertToDerbyTable(dConn, (int[])data[2], (String[])data[3], (Date[])data[4], 
          (String[])data[5], (int[])data[6], ((int[])data[2]).length); 
    } catch (SQLException derbyse) {
      if (derbyse.getSQLState().equals("38000") ||
          derbyse.getSQLState().equals("XJ208")) {
        if (!SQLTest.isEdge) {
          //if gfxd using peer driver, it may not be wrapped to 38000
          //or XJ208 for batch update exception -- added to check batch insert failed on 23505
          derbyse = derbyse.getNextException();
          if (derbyse == null) {
            throw new TestException ("derby batch update exception does not have nested exception");
          }   
        } else {
          if (gfxdse.getSQLState().equals("XJ208")) {
            gfxdse = gfxdse.getNextException();
            derbyse = derbyse.getNextException();
          } else {
            if (reproduce50010) {
              Log.getLogWriter().info("derby excetpion:");
              SQLHelper.printSQLException(derbyse);
              Log.getLogWriter().info("gfxd excetpion:");
              SQLHelper.printSQLException(gfxdse);
              throw new TestException("gfxd does not get correct batch update exception");
            } else {
              Log.getLogWriter().info("ignore ticket 50010 for now");
              derbyse = derbyse.getNextException();
            }
          }
        }
      }
      SQLHelper.compareExceptions(derbyse, gfxdse);
      return;
    }
    
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  @SuppressWarnings("unchecked")
	public boolean updateGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return updateGfxdOnly(gConn);
    }
    int size =1;
    int[] cid = new int[size];
    int[] newCid = new int[size];
    String[] cust_name = new String[size];
    Date[] since = new Date[size];
    String[] addr = new String[size]; 
    
    int[] whichUpdate = new int[size];
    int[] updateCount = new int[size];
    SQLException gfxdse = null;
    boolean[] expectConflict = new boolean[1]; //handle #42672

    if (!ticket42672fixed && isCustomersPartitionedOnPKOrReplicate()) {
      //work around #49889 by not updating for now
      //need additional test development to allow conflict 
      Log.getLogWriter().info("not implemented due to #42672, abort this op for now");
      return true;
    }

    getDataForUpdate((Connection)SQLDistTxTest.gfxdNoneTxConn.get(), newCid, 
        cid, cust_name, since, addr, whichUpdate, size);
    getExistingCidFromCustomers((Connection)SQLDistTxTest.gfxdNoneTxConn.get(),
        cid); //get random cid
    
    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();
    
    /* needs to be handed in actual dml op later
    if (SQLTest.testPartitionBy) {
      PreparedStatement stmt = getCorrectTxStmt(gConn, whichUpdate[0]);
      if (stmt == null) {
        if (isHATest && (Boolean) SQLDistTxTest.failedToGetStmt.get()) {
          SQLDistTxTest.failedToGetStmt.set(false);
          return false; //due to node failure, assume txn rolled back        
        }
        else return true; //due to unsupported exception
      }
    }
    */
    try {
      getKeysForUpdate(modifiedKeysByOp, whichUpdate[0], cid[0], newCid[0], since[0]);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      Log.getLogWriter().warning("not able to get the keys, abort this insert op");
      return true;
    }
    
    try {
      updateGfxdTable(gConn, newCid, cid, cust_name, since, addr, whichUpdate, updateCount, size);

      //handles get stmt failure conditions -- node failure or unsupported update on partition field
      if (isHATest && (Boolean) SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
        SQLDistTxTest.failedToGetStmtNodeFailure.set(false); //reset flag
        return false; //due to node failure, assume txn rolled back        
      } 
      if ((Boolean) SQLDistTxTest.updateOnPartitionCol.get()) {
        SQLDistTxTest.updateOnPartitionCol.set(false); //reset flag
        return true; //assume 0A000 exception does not cause txn to rollback
      }
  
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
        //SQLHelper.handleSQLException(se);
        gfxdse = se;
      }
    }
    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, null, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, null, hasSecondary, false);
      
    //add this operation for derby
    addUpdateToDerbyTx(newCid, cid, cust_name, since, addr, whichUpdate, updateCount, gfxdse);
    
    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
    
  }
  
  protected void getKeysForUpdate(HashMap<String, Integer > keys, 
      int whichUpdate, int cid, int newCid, Date since) throws SQLException {
    int tid = getMyTid();
    int txId = (Integer)SQLDistTxTest.curTxId.get();
    String database ="gemfirexd - TXID:" + txId + " ";
    Connection conn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    String sql = null;
    ResultSet rs = null;
    switch (whichUpdate) {
    case 0: 
      //"update trade.customers set cid = ? where cid=? and tid = ? ", 
      break;
    case 1: 
      // "update trade.customers set cust_name = ? , addr = ? where cid=? and tid <>?", 
      sql = "select cid from trade.customers where cid="+cid +" and tid<>" + tid;
      rs = conn.createStatement().executeQuery( sql); 
      if (rs.next()) {
        Log.getLogWriter().info(database + "CID:" + cid + " exists for update");
        keys.put(getTableName()+"_"+rs.getInt(1), txId);
      } 
      rs.close();
      break;
    case 2: 
      // "update trade.customers set cust_name = ? , addr = ? where cid=? and tid <=? ",
      sql = "select cid from trade.customers where cid ="+cid 
        +" and tid<=" + tid;
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int availCid = rs.getInt(1);
        Log.getLogWriter().info(database + "CID:" + availCid + " exists for update");
        keys.put(getTableName()+"_"+availCid, txId);
      }       
      break; 
    case 3:
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid >?"
      sql = "select cid from trade.customers where cid="+cid 
        + " and tid>" +tid;
      rs = conn.createStatement().executeQuery(sql); 
      while (rs.next()) {
        int availCid = rs.getInt(1);
        Log.getLogWriter().info(database + "CID:" + availCid + " exists for update");
        keys.put(getTableName()+"_"+availCid, txId);
      }  
      break;
    default:
     throw new TestException ("Wrong update statement here");
    }  
  }
 
  
  public boolean deleteGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return deleteGfxdOnly(gConn);
    }
    
    //TODO with derby case is to be implemented
    return true;
  }
  @SuppressWarnings("unchecked")
  public boolean queryGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return queryGfxdOnly(gConn);
    }
    
    int whichQuery = rand.nextInt(select.length); //randomly select one query sql
    int cid = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary));
    //Date since = new Date ((rand.nextInt(10)+98),rand.nextInt(12), rand.nextInt(31));
    Date since = getSince();
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      gfxdRS = query (gConn, whichQuery, cid, since, getMyTid());   
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
      } else if (isHATest &&
          SQLHelper.gotTXNodeFailureException(se) ) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx without HA support, continue testing");
          return false;
      } else SQLHelper.handleSQLException(se);
          
      gfxdse = se;
    }
    
    //convert gfxd result set
    List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS, false);
    /*
    if (gfxdList == null && isHATest && (Boolean) SQLDistTxTest.convertTxnRSGotNodeFailure.get()) {
      Log.getLogWriter().info("Testing HA and did not get GFXD result set");
      SQLDistTxTest.convertTxnRSGotNodeFailure.set(false); //reset flag
      return false; //do not compare query results as gemfirexd does not get any due to node failure
    }*/
    if (gfxdList == null && isHATest) {
      Log.getLogWriter().info("Testing HA and did not get GFXD result set due to node failure");
      return false; //assume txn failure occur and txn rolled back by product, otherwise return true here 
    }
    
    addQueryToDerbyTx(whichQuery, cid, since, gfxdList, gfxdse);
    //only the first thread to commit the tx in this round could verify results
    //this is handled in the SQLDistTxTest doDMLOp
    
    return true;
  }  
  
  public static int getCid() {
    int maxCid = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    int newCids = 10 * numOfThreads > 100 ? 10 * numOfThreads: 100;
    if (maxCid>newCids) return rand.nextInt(maxCid-newCids)+1;
    else throw new TestException("test issue, not enough cid in the tests yet");
  }

  @SuppressWarnings("unchecked")
	protected void addQueryToDerbyTx(int whichQuery, int cid, 
      Date since, List<Struct> gfxdList, SQLException gfxdse){
    Object[] data = new Object[7];      
    data[0] = DMLDistTxStmtsFactory.TRADE_CUSTOMERS;
    data[1] = "query";
    data[2] = whichQuery;
    data[3] = cid;
    data[4] = since;
    data[5] = gfxdList;
    data[6] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  
  @SuppressWarnings("unchecked")
  public void queryDerby(Connection dConn, int index){
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[6];
    List<Struct> gfxdList = (List<Struct>) data[5];
    ResultSet derbyRS = null;
    
    try {
      //query (dConn, whichQuery, cid, since, getMyTid()); 
      derbyRS = query(dConn, (Integer)data[2], (Integer)data[3], (Date)data[4], getMyTid()); 
    }  catch (SQLException derbyse) {
      SQLHelper.compareExceptions(derbyse, gfxdse);
    }
    
    ResultSetHelper.compareResultSets(ResultSetHelper.asList(derbyRS, true), gfxdList);
  }
  
  @SuppressWarnings("unchecked")
	protected void addUpdateToDerbyTx(int[] newCid, int[] cid, String[] cust_name, 
      Date[] since, String[] addr, int[] whichUpdate, int[] updateCount, SQLException gfxdse){
    Object[] data = new Object[10];      
    data[0] = DMLDistTxStmtsFactory.TRADE_CUSTOMERS;
    data[1] = "update";
    data[2] = newCid;
    data[3] = cid;
    data[4] = cust_name;
    data[5] = since;
    data[6] = addr;
    data[7] = whichUpdate;
    data[8] = updateCount;
    data[9] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  
  @SuppressWarnings("unchecked")
  public void updateDerby(Connection dConn, int index){
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[9];
    
    try {
      //updateDerbyTable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      //Date[] since, String[] addr, int[] whichUpdate, int[] updateCount, int size) 
      updateDerbyTable(dConn, (int[])data[2], (int[])data[3], (String[])data[4], 
          (Date[])data[5], (String[])data[6], (int[])data[7], (int[])data[8], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  protected void insertToGfxdTable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] count, int size) throws SQLException {
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(conn, insertJSON);
    else stmt =  conn.prepareStatement(insertJSON);
    int tid = getMyTid();
    
    
    if (size > 1) {
      int[] temp  = insertBatchToGFETable(conn, cid, cust_name,
          since, addr, size);
      if (temp != null) {
        for (int i =0; i<temp.length; i++) {
          count[i] = temp[i];
        }
      } else {
        Log.getLogWriter().warning("batch insert failed, needs to be checked");
      }
    } else {
      for (int i=0 ; i<size ; i++) {
        count[i] = insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid); 
        Log.getLogWriter().info("gemfirexd -  inserts " + count[i] + " rows");
      }
    }
  }
  
  protected int[] insertBatchToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size) throws SQLException {
    return insertBatchToGFETable(conn, cid, cust_name, since, addr, size, false);
  }
  //for gemfirexd checking
  protected int[] insertBatchToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, boolean isPut) throws SQLException {
    Statement stmt = conn.createStatement();
    int tid = getMyTid();
    int[] counts = null;       
    
    for (int i=0 ; i<size ; i++) {
    try {
      String json = getJSON(cid[i], tid, since[i], cust_name[i], addr[i]);
      
      stmt.addBatch((isPut ? "put" : "insert")  + " into trade.customers values (" 
          + cid[i] + ", '" + cust_name[i] + "' , '" + since[i]
          + "' , '" + addr[i] + "'," + tid + ",'" + json + "' , null , null)");
        Log.getLogWriter().info("gemfirexd - TXID:" + (Integer)SQLDistTxTest.curTxId.get()+  " "+ (isPut ? "batch putting" : "batch inserting") + " into trade.customers CID:" + cid[i] + ",CUSTNAME:" + cust_name[i] +
              ",SINCE:" + since[i] + ",ADDR:" + addr[i] + ".TID:" + tid + ",JSON_DETAILS:" + json);
      }  catch (SQLException se) {
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
        throw se;    
      }  
    }
      
    try{
      counts = stmt.executeBatch();
      Log.getLogWriter().info("executed batch insert in gfxd");
      if (counts == null) throw new TestException("gemfirexd statement executebatch succeeded but not return an array of update counts");
      if (counts.length < 1) {
        throw new TestException("gemfirexd statement executebatch succeeded but returned zero length array of update counts");
      }  
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);    
      //if (se instanceof BatchUpdateException) counts=((BatchUpdateException)se).getUpdateCounts();
      //gfxd roll back the txn due to #43170
      throw se;
    }
 
    return counts;

  }
  
  protected void insertToDerbyTable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement(insert);
    int tid = getMyTid();
    

      
    if (size > 1) {
      int[] counts;
      counts = insertBatchToDerbyTable(conn, cid, cust_name,
          since, addr, size);
      boolean hasWrongUpdateCount = false; 
      for (int i=0 ; i<size ; i++) {  
        if (counts[i] != updateCount[i]) {
          Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
            "gfxd inserted " + updateCount[i] +
            " row, but derby inserted " + counts[i] + " row for " + (i+1) + "th element");
          hasWrongUpdateCount = true;
        }   
      }
      if (reproduce50001 && !isHATest && hasWrongUpdateCount)
        throw new TestException ("Derby batch update has different row count from that of gfxd");
    } else {
      int count = 0;
      for (int i=0 ; i<size ; i++) { 
        count = insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid); 
        if (count != updateCount[i]) {
          Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
            "gfxd inserted " + updateCount[i] +
            " row, but derby inserted " + count + " row");
        }
      }
    }
  }

  protected int[] insertBatchToDerbyTable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size) throws SQLException {
    Statement stmt = conn.createStatement();
    int tid = getMyTid();
    int counts[] = null;
    
    for (int i=0 ; i<size ; i++) { 
      stmt.addBatch("insert into trade.customers values (" 
        + cid[i] + ", '" + cust_name[i] + "','" + since[i]
        + "','" + addr[i] + "'," + tid + ")");
      Log.getLogWriter().info("Derby - batch inserting into trade.customers CID:" + cid[i] + ",CUSTNAME:" + cust_name[i] +
            ",SINCE:" + since[i] + ",ADDR:" + addr[i] + ",TID:" + tid);
    }
    
    counts = stmt.executeBatch(); 
    
    
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] != -3)
        Log.getLogWriter().info(
            "Derby - inserted into table trade.customers CID:" + cid[i]
                + ",CUSTNAME:" + cust_name[i] + ",SINCE:" + since[i]
                + ",ADDR:" + addr[i] + ",TID:" + tid);
      verifyRowCount.put(tid + "_insert" + i, 0);
      verifyRowCount.put(tid + "_insert" + i, new Integer(counts[i]));
      // Log.getLogWriter().info("Derby inserts " +
      // verifyRowCount.get(tid+"_insert" +i) + " rows");
    }
    return counts;
  }
  
  public void deleteDerby(Connection dConn, int index){
    
  }
  
  /* non derby case using trigger*/
  
  protected boolean insertGfxdOnly(Connection gConn){
    try {
      insert(null, gConn, 1, false); //use the one without inserting to networth
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02")) {
        if (nobatching || RemoteTestModule.getCurrentThread().getCurrentTask().getTaskTypeString().
            equalsIgnoreCase("INITTASK")) //populate table should be using sync-commit now
          throw new TestException ("Without batching, we should not get conflict " +
          		"exception " + TestHelper.getStackTrace(te) );
        else {
          Log.getLogWriter().info("got expected conflict exception");
          //please note use batch insert, we may get conflict
          return false;
        }
      } else if (gfxdtxHANotReady && isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  /* need to check whether batching is enabled even after HA is supported in gfxd 
   * gfxdtxHANotReady flag may have no use if a query results will still*/
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
  
  protected boolean queryGfxdOnly(Connection gConn){
    try {
      query(null, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") && batchingWithSecondaryData) {
        Log.getLogWriter().info("got expected conflict exception, continuing test");
        return false;
      } else if (isHATest && 
          SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      //  "update trade.customers set cid = ? where cid=? and tid = ? ",  
      //should not happen
      break;
    case 1: 
      // "update trade.customers set cust_name = ? where cid=? and tid =1", 
      if (partitionKeys.contains("cust_name")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateJSON[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else if (!SQLHelper.isDerbyConn(conn))
             stmt = getStmt(conn, updateJSON[whichUpdate]);
      else
             stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: //update name, addr
      //"update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",
      if (partitionKeys.contains("cust_name") || partitionKeys.contains("addr")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateJSON[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else if (!SQLHelper.isDerbyConn(conn))
             stmt = getStmt(conn, updateJSON[whichUpdate]);
      else
             stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: //update name, since
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid =? " 
      if (partitionKeys.contains("cust_name") || partitionKeys.contains("since")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, updateJSON[whichUpdate]);
        //if derbyConn, stmt is null so no update in derby as well
      } else if (!SQLHelper.isDerbyConn(conn))
             stmt = getStmt(conn, updateJSON[whichUpdate]);
      else
             stmt = getStmt(conn, update[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  protected PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectTxStmt(conn, whichUpdate, partitionKeys);
  }

}
