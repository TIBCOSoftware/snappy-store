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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlStatements.TradeNetworthDMLStmt;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxPartitionInfoBB;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.ResultSetHelper;
import util.TestException;

public class TradeNetworthDMLDistTxStmt extends TradeNetworthDMLStmt implements
    DMLDistTxStmtIF {
  /*
  protected static String[] update = { 
    //uniqs
    "update trade.networth set availloan=availloan-? where cid = ? and tid <> ?", 
    "update trade.networth set securities=? where cid = ?  and tid<> ?",
    "update trade.networth set cash=cash-? where cid = ? and tid<> ?",
    "update trade.networth set loanLimit=? where  tid= ? and securities > ?",  //bulk update
    //no uniqs
    "update trade.networth set availloan=availloan-? where cid = ? ", 
    "update trade.networth set securities=? where cid = ?  ",
    "update trade.networth set cash=cash-? where cid = ? ",
    "update trade.networth set loanLimit=? where securities > ? "
  };
  */
  
  protected static String[] delete = {
    //uniq key
    "delete from trade.networth where cid = ?", 
    "delete from trade.networth where cid in (?,?)"
  }; 

  static final boolean  isConcUpdateTx = TestConfig.tab().booleanAt(SQLPrms.isConcUpdateTx, false);
  
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
  protected static boolean ticket42669fixed = TestConfig.tab().booleanAt(SQLPrms.isTicket42669fixed, false);
  
  protected void insertToDerbyTable(Connection conn, int cid, BigDecimal[] cash, 
      BigDecimal securities, int[] loanLimit, BigDecimal[] availLoan, 
      int[] updateCount, int size) 
      throws SQLException{
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("Insert into derby, myTid is " + tid);
    for (int i = 0; i < size; i++) {
      count = insertToTable(stmt, cid, cash[i], securities, loanLimit[i], availLoan[i], tid);
      if (count != updateCount[i]) {
        Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
          "gfxd inserted " + updateCount[i] +
          " row, but derby inserted " + count + " row");
      }
    }
  }
  
  protected void insertToGfxdTable (Connection conn, int cid, BigDecimal[] cash, 
      BigDecimal securities, int[] loanLimit, BigDecimal[] availLoan, int[] updateCount,
      int size) throws SQLException {
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(conn, insert);
    else stmt = conn.prepareStatement(insert);
    int tid = getMyTid();
    
    for (int i = 0; i < size; i++) {
      updateCount[i] = insertToTable(stmt, cid, cash[i], securities, loanLimit[i], availLoan[i], tid);
      
    }
  }
  protected void insertToGfxdOnlyTable (Connection conn, int cid, BigDecimal[] cash, 
      BigDecimal securities, int[] loanLimit, BigDecimal[] availLoan, int size){
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    
    for (int i = 0; i < size; i++) {
      try {
        insertToTable(stmt, cid, cash[i], securities, loanLimit[i], availLoan[i], tid);
      }  catch (SQLException se) {
        if (se.getSQLState().equals("23503"))
          Log.getLogWriter().info("detected foreign key constraint violation during insert, continuing test");
        if (se.getSQLState().equals("23505"))
          Log.getLogWriter().info("detected duplicate primary key constraint violation during insert, continuing test");
        else if (se.getSQLState().equals("42500") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else
          SQLHelper.handleSQLException(se);
      }  
    }
  }
  
  protected void updateGfxdTable(Connection conn, int[] cid, BigDecimal[] availLoanDelta, 
      BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate, 
      int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    for (int i=0 ; i<size ; i++) {
      /* should work with tx
      if (isHATest && (whichUpdate[i] == 0 || whichUpdate[i] == 2)) {
      continue;
      } //avoid x=x+1 update in HA test for now
      */
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(conn, whichUpdate[i], null);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed

      if (stmt!=null) {
        updateCount[i] = updateTable(stmt, cid[i],availLoanDelta[i], sec[i], cashDelta[i], newLoanLimit[i], tid, whichUpdate[i] );
        
      }
    }   
  }
  
  protected void updateDerbyTable(Connection conn, int[] cid, BigDecimal[] availLoanDelta, 
      BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate, 
      int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      /*
      if (isHATest && (whichUpdate[i] == 0 || whichUpdate[i] == 2)) {
        continue;
      } //avoid x=x+1 update in HA test for now
      */
      
      boolean[] unsupported = new boolean[1];
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(conn, whichUpdate[i], unsupported);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed

      if (stmt!=null) {
        count = updateTable(stmt, cid[i], availLoanDelta[i], sec[i], cashDelta[i], newLoanLimit[i], tid, whichUpdate[i] );        
        if (count != updateCount[i]){
          Log.getLogWriter().info("Derby update has different row count from that of gfxd, " +
                  "gfxd updated " + updateCount[i] +
                  " rows, but derby updated " + count + " rows");
        }
      }
    }
  }
  
  protected void updateGfxdOnlyTable(Connection conn, int[] cid, BigDecimal[] availLoanDelta, 
      BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate, int size){
    PreparedStatement stmt = null;
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectTxStmt(conn, whichUpdate[i], null);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
      try {
        if (stmt !=null)
          updateTable(stmt, cid[i],availLoanDelta[i], sec[i], cashDelta[i], newLoanLimit[i], tid, whichUpdate[i] );
      }  catch (SQLException se) {
        if (se.getSQLState().equals("23513"))  
          Log.getLogWriter().info("detected the constraint check violation, continuing test");
        else if (se.getSQLState().equals("42502") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else
          SQLHelper.handleSQLException(se);
      }    
    }   
  }
  
  public boolean insertGfxd(Connection gConn, boolean withDerby) {
    return true;
    //actual insert is being called with cid when insert into customer table
  }
  @SuppressWarnings("unchecked")
  public boolean insertGfxd(Connection gConn, boolean withDerby, int cid){
    if (!withDerby) {
      return insertGfxdOnly(gConn, cid);
    }
    int size = 1;
    BigDecimal[] cash = new BigDecimal[size];
    int[] loanLimit = new int[size];
    BigDecimal[] availLoan = new BigDecimal[size];
    int[] updateCount = new int[size];
    getDataForInsert(cash, loanLimit, availLoan, size);
    BigDecimal securities = new BigDecimal(Integer.toString(0));
    SQLException gfxdse = null;

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    modifiedKeysByOp.put(getTableName()+"_"+cid, (Integer)SQLDistTxTest.curTxId.get());
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();
    for(int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Inserting " + i + " times.");
        insertToGfxdTable(gConn, cid, cash, securities, loanLimit, availLoan, updateCount, size);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
            else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
          } catch (TestException t) {
            if (t.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            }
            else throw t;
          }
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          gfxdse = se; //added the test case for duplicate cid
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addInsertToDerbyTx(cid, cash, loanLimit, availLoan, securities, updateCount, gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;
  }
  
  /*
  private void insertToGFXDTable (Connection conn, int cid, BigDecimal cash, 
      BigDecimal securities, int loanLimit, BigDecimal availLoan) throws SQLException {
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("Insert into gemfirexd, myTid is " + tid);
    count = insertToTable(stmt, cid, cash, securities, loanLimit, availLoan, tid);
    Log.getLogWriter().info("gfxd inserts " + count + " record");
  }
  */
  
  @SuppressWarnings("unchecked")
  protected void addInsertToDerbyTx(int cid, BigDecimal[] cash, int[] loanLimit,
      BigDecimal[] availLoan, BigDecimal securities, int[] updateCount, SQLException gfxdse){
    Object[] data = new Object[9];      
    data[0] = DMLDistTxStmtsFactory.TRADE_NETWORTH;
    data[1] = "insert";
    data[2] = cid;
    data[3] = cash;
    data[4] = loanLimit;
    data[5] = availLoan;
    data[6] = securities;
    data[7] = updateCount;
    data[8] = gfxdse;
    
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
    SQLException gfxdse = (SQLException) data[8];
    
    try {
      //insertToDerbyTable(dConn, cid, cash, securities, loanLimit, availLoan, size);
      insertToDerbyTable(dConn, (Integer)data[2], (BigDecimal[])data[3], 
          (BigDecimal)data[6], (int[])data[4], (BigDecimal [])data[5], 
          (int[])data[7], 1); 
    } catch (SQLException derbyse) {
      SQLHelper.compareExceptions(derbyse, gfxdse); //add the duplicate primary key case
      return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  public static String getTableName() {
    return "networth";
  }

  @SuppressWarnings("unchecked")
  public boolean updateGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return updateGfxdOnly(gConn);
    }
    int size =1;
    int[] whichUpdate = new int[size];
    int[] cid = new int[size];
    BigDecimal[] availLoanDelta = new BigDecimal[size];
    BigDecimal[] sec = new BigDecimal[size];
    BigDecimal[] cashDelta = new BigDecimal[size];
    int[] newLoanLimit = new int[size];
    int[] updateCount = new int[size];
    SQLException gfxdse = null;

    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    getDataForUpdate(nonTxConn, cid,
        availLoanDelta, sec, cashDelta, newLoanLimit, whichUpdate, size);
    //no update on a newly inserted cid in the same round of tx
    int tid = rand.nextInt(SQLDistTxTest.numOfWorkers);
    cid[0] = getCidFromQuery((Connection)SQLDistTxTest.gfxdNoneTxConn.get(),
        (rand.nextBoolean()? getMyTid() : tid)); //get random cid

    //Separate update to two groups, commit earlier could update certain sql.
    whichUpdate[0] = getWhichUpdate(whichUpdate[0]);
    /* needs to be handed in actual dml op later
    if (SQLTest.testPartitionBy) {
      PreparedStatement stmt = getCorrectTxStmt(gConn, whichUpdate[0], null);
      if (stmt == null) {
        if (SQLTest.isEdge && isHATest && !isTicket48176Fixed &&
            batchingWithSecondaryData &&(Boolean) SQLDistTxTest.failedToGetStmt.get()) {
          SQLDistTxTest.failedToGetStmt.set(false);
          return false; //due to node failure, need to rollback tx
        }
        else return true; //due to unsupported exception
      }
    } */

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    try {
      getKeysForUpdate(nonTxConn, modifiedKeysByOp, whichUpdate[0], cid[0], sec[0]);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this insert op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else SQLHelper.handleSQLException(se);
    }

    for( int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Updating " + i + " times.");
        updateGfxdTable(gConn, cid, availLoanDelta, sec, cashDelta,
            newLoanLimit, whichUpdate, updateCount, size);

        if (isHATest && (Boolean)SQLDistTxTest.failedToGetStmtNodeFailure.get()) {
          SQLDistTxTest.failedToGetStmtNodeFailure.set(false); //reset flag
          return false; //due to node failure, assume txn rolled back
        }

        //will not update on partition column in txn unless is specifically set
        if ((Boolean)SQLDistTxTest.updateOnPartitionCol.get()) {
          SQLDistTxTest.updateOnPartitionCol.set(false); //reset flag
          return true;
          //assume 0A000 exception does not cause txn to rollback when allowUpdateOnPartitionColumn set to true.
          //
          //when allowUpdateOnPartitionColumn set to  false, the get stmt for update is not executed
          //needs to return true here.
        }
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
            else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
          } catch (TestException t) {
            if (t.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            }
            else throw t;
          }
          return false;

        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          //SQLHelper.handleSQLException(se);
          gfxdse = se;
        }
      }
      break;
    }
    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addUpdateToDerbyTx(cid, availLoanDelta, sec, cashDelta,
        newLoanLimit, whichUpdate, updateCount, gfxdse);

    if (gfxdse == null) {
      modifiedKeysByTx.putAll(modifiedKeysByOp);
      SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    } //add the keys if no SQLException is thrown during the operation
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addUpdateToDerbyTx(int[] cid, BigDecimal[] availLoanDelta, 
      BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate,
      int[] updateCount, SQLException gfxdse){
     Object[] data = new Object[10];      
     data[0] = DMLDistTxStmtsFactory.TRADE_NETWORTH;
     data[1] = "update";
     data[2] = cid;
     data[3] = availLoanDelta;
     data[4] = sec;
     data[5] = cashDelta;
     data[6] = newLoanLimit;
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
      //updateDerbyTableConnection conn, int[] cid, BigDecimal[] availLoanDelta, 
      //BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate, 
      //int[] updateCount, int size)
      updateDerbyTable(dConn, (int[])data[2], (BigDecimal[])data[3], (BigDecimal[])data[4], 
          (BigDecimal[])data[5], (int[])data[6], (int[])data[7], (int[])data[8], 1); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
       return;
    }
    if (gfxdse != null) {
      SQLHelper.handleMissedSQLException(gfxdse);
    }
  }
  
  private int getWhichUpdate(int index) {
    //only perform update statement for non unique keys
    if (index < update.length/2) index += update.length/2;
    
    //separate which one could update certain statement
    if (index == 5 && (Boolean)SQLDistTxTest.commitEarly.get()) {//"update trade.networth set securities=? where cid = ?  "
      index = 7; //"update trade.networth set loanLimit=? where securities > ? "
    } else if (index == 7 && !(Boolean)SQLDistTxTest.commitEarly.get()) {
      index = 5; 
    }
    
    if (index == 7) index = 5; //TODO, to comment this line out, once #42642 & #42645 are fixed
    return index;
  }
  
  private void getKeysForUpdate(Connection conn, HashMap<String, Integer > keys,
      int whichUpdate, int cid, BigDecimal securities) throws SQLException {
    String sql = null;
    ResultSet rs = null;
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    switch (whichUpdate) {
    case 0: //fall through
    case 1://fall through
    case 2://fall through
    case 3: throw new TestException("should use only non unique key case");
    case 4: 
      // "update trade.networth set availloan=availloan-? where cid = ? ", 
    case 5: 
      //"update trade.networth set securities=? where cid = ?  ",
    case 6:
      //"update trade.networth set cash=cash-? where cid = ? ",
      sql = "select cid from trade.networth where cid="+cid;
      rs = conn.createStatement().executeQuery(sql); 
      if (rs.next()) {
        Log.getLogWriter().info(database + "CID:" + cid + " exists for update");
        keys.put(getTableName()+"_"+rs.getInt(1), txId);
      }    
      rs.close();
      break;
    case 7: 
      //"update trade.networth set loanLimit=? where securities > ? " 
      sql = "select cid from trade.networth where securities >" +securities;
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

  @SuppressWarnings("unchecked")
  public boolean deleteGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return deleteGfxdOnly(gConn);
    }

    int whichDelete = rand.nextInt(delete.length);

    int cid = getExistingCid();
    int cid1 = getExistingCid();
    int[] updateCount = new int[1];
    SQLException gfxdse = null;

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();

    try {
      getKeysForDelete(nonTxConn, modifiedKeysByOp, whichDelete, cid, cid1);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
        Log.getLogWriter().warning("Not able to process the keys for this op due to HA, this insert op does not proceed");
        return true; //not able to process the keys due to HA, it is a no op
      } else SQLHelper.handleSQLException(se);
    }

    for(int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: deleting "+ i + " times");
        deleteFromGfxdTable(gConn, cid, cid1, whichDelete, updateCount);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (se.getSQLState().equalsIgnoreCase("X0Z02")) {
          try {
            if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, se, true);
            else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, se, hasSecondary, true);
          } catch (TestException t) {
            if (t.getMessage().contains("but got conflict exception") && i < 9) {
              Log.getLogWriter().info("RR: got conflict, retrying the operations ");
              continue;
            }
            else throw t;
          }
          return false;
        } else if (gfxdtxHANotReady && isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
          return false;
        } else {
          gfxdse = se; //security testing may get exception
        }
      }
      break;
    }

    if (!batchingWithSecondaryData) verifyConflict(modifiedKeysByOp, modifiedKeysByTx, gfxdse, false);
    else verifyConflictWithBatching(modifiedKeysByOp, modifiedKeysByTx, gfxdse, hasSecondary, false);

    //add this operation for derby
    addDeleteToDerbyTx(cid, cid1, whichDelete, updateCount[0], gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);
    return true;

  }
  
  @SuppressWarnings("unchecked")
  protected void addDeleteToDerbyTx(int cid, int cid1, int whichDelete, 
      int updateCount, SQLException gfxdse){
    Object[] data = new Object[7];      
    data[0] = DMLDistTxStmtsFactory.TRADE_NETWORTH;
    data[1] = "delete";
    data[2] = cid;
    data[3] = cid1;
    data[4] = whichDelete;
    data[5] = updateCount;
    data[6] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }
  
  protected void deleteFromGfxdTable(Connection gConn, int cid, int cid1, 
      int whichDelete, int[]updateCount) throws SQLException{
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(gConn, delete[whichDelete]);
    else stmt = gConn.prepareStatement(delete[whichDelete]); 
    
    updateCount[0] = deleteFromTableTx(stmt, cid, cid1, whichDelete);
    
  }
  
  protected void deleteFromDerbyTable(Connection dConn, int cid, int cid1, 
      int whichDelete, int updateCount) throws SQLException{
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]);
    
    int count = deleteFromTableTx(stmt, cid, cid1, whichDelete);
    
    if (count != updateCount) {
      Log.getLogWriter().info("derby delete has different row count from that of gfxd " +
        "gfxd deleted " +  updateCount + " rows, but derby deleted " + count + " rows in "
        + getTableName());
    }
  }
  
  //delete from table based on whichDelete
  protected int deleteFromTableTx(PreparedStatement stmt, int cid, int cid1,
      int whichDelete) throws SQLException {
    
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    String database =  SQLHelper.isDerbyConn(stmt.getConnection()) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    String query = " QUERY: " + delete[whichDelete];
    String completionMessage="";
    
    
    switch (whichDelete) {
    case 0:   
      Log.getLogWriter().info(database + "deleting from trade.networth with CID: " + cid + query);  
      stmt.setInt(1, cid);       
      completionMessage =  " rows from trade.networth with CID: " + cid + query;
      break;
    case 1: 
      Log.getLogWriter().info(database + "deleting from trade.networth with 1_CID: " + cid + ",2_CID:" + cid1 +  query);
     
      stmt.setInt(1, cid);
      stmt.setInt(2, cid1);
      completionMessage =  " rows from trade.networth with 1_CID: " + cid + ",2_CID:" + cid1 +  query;
      break;
    default:
      throw new TestException("incorrect delete statement, should not happen");
    }  
     int rowCount = stmt.executeUpdate();
     Log.getLogWriter().info(database + " deleted " + rowCount + completionMessage);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }     
  
  protected void getKeysForDelete(Connection conn, HashMap<String, Integer > keys,
      int whichDelete, int cid, int cid1) throws SQLException {
     String sql = null;
     int txId = (Integer) SQLDistTxTest.curTxId.get();
     String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
     ResultSet rs = null;
     ArrayList<Integer> ids = new ArrayList<Integer>();
     switch (whichDelete){
     case 0:
       sql = "select cid from trade.networth where cid = " + cid;
       Log.getLogWriter().info(database + sql);
       rs = conn.createStatement().executeQuery(sql);
       if (rs.next()) {
         int cidKey = rs.getInt("CID");
         ids.add(cidKey);
       }
       rs.close();       
       break;
     case 1: 
       sql = "select cid from trade.networth " +
           "where cid in (?, ? )";
       Log.getLogWriter().info(database + sql);
       PreparedStatement stmt = conn.prepareStatement(sql);
       stmt.setInt(1, cid);
       stmt.setInt(2, cid1);
       rs = stmt.executeQuery();
       List<Struct>resultsList = ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
       if (resultsList != null) {
         Iterator<Struct> it = resultsList.iterator();
         while (it.hasNext()) {
           Struct row = (Struct)it.next();
           Integer cidKey = (Integer)row.get("CID");
           ids.add(cidKey);
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
  
  @SuppressWarnings("unchecked")
  public void deleteDerby(Connection dConn, int index){
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[6];
    
    try {
      //Connection dConn, int cid, int cid1, int whichDelete, int updateCount
      deleteFromDerbyTable(dConn, (Integer)data[2], (Integer)data[3], (Integer)data[4], 
          (Integer)data[5]); 
    }  catch (SQLException derbyse) {
       SQLHelper.compareExceptions(derbyse, gfxdse);
    }   
  }
  
  @SuppressWarnings("unchecked")
  public boolean queryGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return queryGfxdOnly(gConn);
    }
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    int cash = 100000;
    int sec = 100000;
    int tid = getMyTid();
    int loanLimit = loanLimits[rand.nextInt(loanLimits.length)];
    BigDecimal loanAmount = new BigDecimal (Integer.toString(rand.nextInt(loanLimit)));
    BigDecimal queryCash = new BigDecimal (Integer.toString(rand.nextInt(cash)));
    BigDecimal querySec= new BigDecimal (Integer.toString(rand.nextInt(sec)));
    
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      gfxdRS = query (gConn,  whichQuery, queryCash, querySec, loanLimit, loanAmount, tid);   
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

    addQueryToDerbyTx(whichQuery, queryCash, querySec, loanLimit, loanAmount, 
         tid, gfxdList, gfxdse);
      //only the first thread to commit the tx in this round could verify results
      //this is handled in the SQLDistTxTest doDMLOp
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addQueryToDerbyTx(int whichQuery, BigDecimal queryCash, 
      BigDecimal querySec, int loanLimit, BigDecimal loanAmount, int tid, 
      List<Struct> gfxdList, SQLException gfxdse){
    Object[] data = new Object[10];      
    data[0] = DMLDistTxStmtsFactory.TRADE_NETWORTH;
    data[1] = "query";
    data[2] = whichQuery;
    data[3] = queryCash;
    data[4] = querySec;
    data[5] = loanLimit;
    data[6] = loanAmount;
    data[7] = tid;
    data[8] = gfxdList;
    data[9] = gfxdse;
    
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    if (derbyOps == null) derbyOps = new ArrayList<Object[]>();
    derbyOps.add(data);
    SQLDistTxTest.derbyOps.set(derbyOps);
  }

  /* (non-Javadoc)
  * @see sql.dmlStatements.AbstractDMLStmt#query(java.sql.Connection, java.sql.Connection)
  */
  @Override
  public void query(Connection dConn, Connection gConn) {
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    int cash = 100000;
    int sec = 100000;
    int tid = getMyTid();
    int loanLimit = loanLimits[rand.nextInt(loanLimits.length)];
    BigDecimal loanAmount = new BigDecimal (Integer.toString(rand.nextInt(loanLimit)));
    BigDecimal queryCash = new BigDecimal (Integer.toString(rand.nextInt(cash)));
    BigDecimal querySec= new BigDecimal (Integer.toString(rand.nextInt(sec)));

    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();

    for( int i=0; i< 10; i++) {
      Log.getLogWriter().info("RR: executing query " + i + "times");
      if (dConn != null) {
        try {
          discRS = query(dConn, whichQuery, queryCash, querySec, loanLimit, loanAmount, tid);
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
          gfeRS = query(gConn, whichQuery, queryCash, querySec, loanLimit, loanAmount, tid);
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

        boolean success = false;
        if (whichQuery == 1) {
          success = ResultSetHelper.compareSortedResultSets(discRS, gfeRS);  //order by case
        } else {
          success = ResultSetHelper.compareResultSets(discRS, gfeRS); //no order by cases
        }
        if (!success) {
          Log.getLogWriter().info("Not able to compare results due to derby server error");
        } //not able to compare results due to derby server error
      }// we can verify resultSet
      else {
        try {
          gfeRS = query(gConn, whichQuery, queryCash, querySec, loanLimit, loanAmount, tid);
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
          } else SQLHelper.handleSQLException(se);
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
  public void queryDerby(Connection dConn, int index){
    ArrayList<Object[]> derbyOps = (ArrayList<Object[]>)SQLDistTxTest.derbyOps.get();
    Object[] data = derbyOps.get(index);
    SQLException gfxdse = (SQLException) data[9];
    List<Struct> gfxdList = (List<Struct>) data[8];
    ResultSet derbyRS = null;
    
    try {
      //query(dConn, whichQuery, queryCash, querySec, loanLimit, loanAmount, tid); 
      derbyRS = query(dConn, (Integer)data[2], (BigDecimal)data[3], (BigDecimal)data[4],
         (Integer)data[5], (BigDecimal)data[6], (Integer)data[7]); 
    }  catch (SQLException derbyse) {
      SQLHelper.compareExceptions(derbyse, gfxdse);
    }
    
    ResultSetHelper.compareResultSets(ResultSetHelper.asList(derbyRS, true), gfxdList);
  }
  
  protected void getDataForUpdate(Connection conn, int[] cid, BigDecimal[] availLoanDelta, 
      BigDecimal[] sec, BigDecimal[] cashDelta, int[] newLoanLimit, int[] whichUpdate, int size) {
    int maxAvailLimitDelta = 10000;
    int maxCashDelta = 10000;
    int maxSec = 1000000;
    for (int i = 0; i<size; i++) {
      if (testUniqueKeys) {
        cid[i] =  getCid(conn);  //to get the cid and could be updated by this thread for uniq
        //cid could be 0 and the update will fail as the record for cid is 0 (in customer and networth) does not exist
      } else {
        cid[i] = getExistingCid();
      } //non uniq
      availLoanDelta[i] = getBigDecimal(Double.toString(rand.nextInt(maxAvailLimitDelta * 100 +1)*0.01));
      sec[i] = getBigDecimal(Double.toString(rand.nextInt(maxSec * 100 +1)*0.01)); //will be updated in portfolio in trigger test
      cashDelta[i] =  getBigDecimal(Double.toString(rand.nextInt(maxCashDelta * 100 +1)*0.01));
      newLoanLimit[i] = loanLimits[rand.nextInt(loanLimits.length)];
      whichUpdate[i] = isConcUpdateTx ? 
          getWhichConcUpdate(numOfNonUniqUpdate, update.length):
          getWhichOne(numOfNonUniqUpdate, update.length);
    }
  }

  protected BigDecimal getBigDecimal(String s) {
    String p = s.substring(0, s.indexOf(".") + 2); //work around #42669 --to xxxxx.x type
    if (!ticket42669fixed) return new BigDecimal(p);
    else return new BigDecimal(s);
  }
  
  /* non derby case using trigger*/
  protected boolean insertGfxdOnly(Connection gConn, int cid){
    try {
      insert(null, gConn, 1, new int[]{cid}); 
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") ) {
        Log.getLogWriter().info("got expected conflict exception, continuing test");
        return false;
      } else if (te.getMessage().contains("23505")) { //only try in this case
        Log.getLogWriter().info("detected duplicate primary key constraint violation during insert, continuing test");
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
  
  protected int getWhichConcUpdate(int numOfNonUniq, int total) {
    int whichOne = super.getWhichOne(numOfNonUniq, total);
    if (isConcUpdateTx && (whichOne == 1 || whichOne == 5)) 
    //to avoid update securities column in concUpdateTx tx to test updatable result set 
      whichOne++;
    return whichOne;
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
      } else if (isHATest && SQLHelper.gotTXNodeFailureTestException(te)) {
        Log.getLogWriter().info ("got expected node failure exception, continuing test");
        return false;
      } else throw te;
    }
    return true;
  }
  
  //for populate table using gfxd only
  public void insert(Connection dConn, Connection gConn, int size) {
    try {
      super.insert(dConn, gConn, size);
    } catch (TestException te) {
      if (dConn == null & te.getMessage().contains("X0Z02")) {
        Log.getLogWriter().info("concurrent insert could cause expected conflict exception");
        //in networth tx gfxd only tests, cid is randomly selected.
      } else throw te;
    }
  }
  
  protected PreparedStatement getCorrectTxStmt(Connection conn, int whichUpdate, boolean[] unsupported){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectStmt(conn, whichUpdate, partitionKeys, unsupported);  //same update as non tx update
  }
}
