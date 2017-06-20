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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlStatements.TradePortfolioDMLStmt;
import sql.sqlTx.RangeForeignKey;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxBatchingFKBB;
import sql.sqlTx.SQLTxPartitionInfoBB;
import sql.sqlTx.SQLTxSecondBB;
import sql.sqlutil.DMLDistTxStmtsFactory;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import util.TestException;
import util.TestHelper;

public class TradePortfolioDMLDistTxStmt extends TradePortfolioDMLStmt
    implements DMLDistTxStmtIF {
  public static String cidPrefix = "_cid_";
  public static String sidPrefix = "_sid_";
  static final boolean  isConcUpdateTx = TestConfig.tab().booleanAt(SQLPrms.isConcUpdateTx, false);
  /*
  protected static String[] update = { 
     //availQty=availQty-? and qty = qty + ? type will be covered in trigger test
    "update trade.portfolio set subTotal=? * qty where cid = ? and sid = ? ",
    "update trade.portfolio set tid=? where cid > ? and cid<? and sid = ? ",
    }; 
  */
  
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

  @Override
  public void deleteDerby(Connection dConn, int index) {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean deleteGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return deleteGfxdOnly(gConn);
    }
   // TODO implement with derby
    return true;
  }
  
  @SuppressWarnings("unchecked")
  protected void addInsertToDerbyTx(int[] cid, int[] sid , int[] qty,
      BigDecimal[] sub, int[] updateCount, SQLException gfxdse){
    Object[] data = new Object[8];      
    data[0] = DMLDistTxStmtsFactory.TRADE_PORTFOLIO;
    data[1] = "insert";
    data[2] = cid;
    data[3] = sid;
    data[4] = qty;
    data[5] = sub;
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
      insertToDerbyTable(dConn, (int[])data[2], (int[])data[3], (int[])data[4], 
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
    int[] cid = new int[size];
    int[] sid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal[size];
    int[] updateCount = new int[size];
    boolean[] expectConflict = new boolean[1];
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    SQLException gfxdse = null;

    getExistingCidFromCustomers(nonTxConn, cid);
    getDataFromResultSet(nonTxConn, sid, qty, sub, price, size); //get the data
    int chance = 200;
    if (rand.nextInt(chance) == 0) cid[0] = 0;
    else if (rand.nextInt(chance) == 0) sid[0] = 0;

    HashMap<String, Integer> modifiedKeysByOp = new HashMap<String, Integer>();
    HashSet<String> parentKeysHold = new HashSet<String>();
    try {
      getKeysForInsert(nonTxConn, modifiedKeysByOp, cid[0], sid[0], expectConflict, parentKeysHold);
      /* using batching fk bb now
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

    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
        SQLDistTxTest.curTxModifiedKeys.get();

    for(int i=0; i< 10; i++) {
      try {
        Log.getLogWriter().info("RR: Inserting " + i + " times.");
        insertToGfxdTable(gConn, cid, sid, qty, sub, updateCount, size);
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
          if (expectConflict[0] && !se.getSQLState().equals("23505")
              && !se.getSQLState().equals("23503")) {
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
    if (withDerby) addInsertToDerbyTx(cid, sid, qty, sub, updateCount, gfxdse);

    modifiedKeysByTx.putAll(modifiedKeysByOp);
    SQLDistTxTest.curTxModifiedKeys.set(modifiedKeysByTx);

    return true;
  }

  protected int getDataFromResultSet(Connection regConn, int[] sid, int[] qty, 
      BigDecimal[] sub, BigDecimal[] price, int size) {
    if (testUniqueKeys) return super.getDataFromResultSet(regConn, sid, qty, sub, price, size);
    
    Connection conn = getAuthConn(regConn);
    ArrayList<Struct> list = null;
    int[] offset = new int[1];
    
    String sql = "select sec_id, price from trade.securities";
    try {
      ResultSet rs = conn.createStatement().executeQuery(sql);
      list = (ArrayList<Struct>)ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    } catch (SQLException se) {
      if (isHATest && SQLHelper.gotTXNodeFailureException(se) ) {
        Log.getLogWriter().info("got node failure exception during Tx without HA support, continue testing");  
     } else SQLHelper.handleSQLException(se);
    }
    
    if (list == null || list.size() == 0) return 0;
    
    int availSize = getAvailSize(list, size, offset);
    for (int i =0 ; i<availSize; i++) {
      sid[i] = ((Integer)((GFXDStructImpl)list.get(i+offset[0])).get("SEC_ID")).intValue();
      price[i] = (BigDecimal)((GFXDStructImpl)list.get(i+offset[0])).get("PRICE");
      qty[i] = rand.nextInt(1901)+100; //from 100 to 2000
      sub[i] = price[i].multiply(new BigDecimal(String.valueOf(qty[i])));       
    }
    if (rand.nextInt(100) == 1 && useTimeout) {
      sid[0] = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary);
      Log.getLogWriter().info("possibly test insert/insert parent/child cases");
    } //for #42915 -- only test when set time out 
    return availSize;
  }
  
  @SuppressWarnings("unchecked")
  private void getKeysForInsert(Connection conn, HashMap<String, Integer > keys,
     int cid, int sid, boolean[] expectConflict, HashSet<String> parentKeys) throws SQLException {
    String sql = null;
    ResultSet rs = null;
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    
    String database =  SQLHelper.isDerbyConn(conn) ? "Derby - " : "gemfirexd - TXID:" + txId + " " ;
    //check if foreign key if exists
    //test will add cid =0 or sid =0 to verify, tx could detect non existing fks
    sql = "select sec_id from trade.securities where sec_id =" + sid;
    Log.getLogWriter().info(database + sql);
    rs = conn.createStatement().executeQuery(sql); 
    if (!rs.next()) {
      Log.getLogWriter().info(database + "foreign key SECID:" + sid + " does not exist " +
          "in the securities table, should get foreign key constraint exception");
      //this key may not be able to held, due the the delete in parent table on cid,
      //so instead of fk constraint violation, we may see lock not held
    }
    rs.close();

    sql = "select cid from trade.customers where cid =" + cid;
    Log.getLogWriter().info(database + sql);
    rs = conn.createStatement().executeQuery(sql); 
    if (!rs.next()) {
      Log.getLogWriter().info(database + "foreign key CID:" + cid + " does not exist " +
          "in the customer table, should get foreign key constraint exception");
    }
    rs.close();

    //check if foreign key has been locked, if other tx hold the key for update etc, 
    //this insert should gets conflict
    //added to work around #42672, so only check when no global index being created case in test for now 
    //-- once #42672 is fixed, the following check should be commented out
    //because gfxd update could not update primary key, so no conflict will be seen
    
    boolean hasForeignKeyConflict = false; //not expect conflict for update on parent key
    
    if (!ticket42672fixed) {
      hasForeignKeyConflict = (isCustomersPartitionedOnPKOrReplicate() && hasForeignKeyConflict(TradeCustomersDMLDistTxStmt.
        getTableName() + "_" + cid , txId)) || (isSecuritiesPartitionedOnPKOrReplicate() && hasForeignKeyConflict(TradeSecuritiesDMLDistTxStmt.
        getTableName() + "_" + sid , txId));
    }
    
    //add parent keys needs to be hold
    parentKeys.add(TradeCustomersDMLDistTxStmt.getTableName() + "_" + cid );
    parentKeys.add(TradeSecuritiesDMLDistTxStmt.getTableName() + "_" + sid );
    
    Log.getLogWriter().info("parent keys added: " + TradeCustomersDMLDistTxStmt.getTableName() + "_" + cid);
    Log.getLogWriter().info("parent keys added: " + TradeSecuritiesDMLDistTxStmt.getTableName() + "_" + sid);
    
    //we need to modify the RangeForeignKey and check if the range foreign key has been taken, 
    //if not, subsequent delete in parent table could be blocked
    hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
    String keyCid = getTableName() + cidPrefix + cid;
    String keySid = getTableName() + sidPrefix + sid;
    RangeForeignKey cidRangeKey = (RangeForeignKey) rangeForeignKeys.get(keyCid);
    RangeForeignKey sidRangeKey = (RangeForeignKey) rangeForeignKeys.get(keySid);
    if (cidRangeKey == null) cidRangeKey = new RangeForeignKey(keyCid);
    if (sidRangeKey == null) sidRangeKey = new RangeForeignKey(keySid);
    
    boolean hasRangeForeignKeyConflict = cidRangeKey.hasConflictAddPartialRangeKey(txId) 
    || sidRangeKey.hasConflictAddPartialRangeKey(txId);
 
    rangeForeignKeys.put(getTableName() + cidPrefix + cid, cidRangeKey);
    rangeForeignKeys.put(getTableName() + sidPrefix + sid, sidRangeKey);
   
    ArrayList<String> curTxRangeFK = (ArrayList<String>)SQLDistTxTest.curTxFKHeld.get();
    curTxRangeFK.add(keyCid);
    curTxRangeFK.add(keySid);
    SQLDistTxTest.curTxFKHeld.set(curTxRangeFK);
   
    expectConflict[0] = hasForeignKeyConflict || hasRangeForeignKeyConflict;
    if (hasForeignKeyConflict) {
      Log.getLogWriter().info("should expect lock not held/conflict exception here" 
         // + " but due to non update of primary key in parent table, will relax a little here." +
         //" however the insert in parent should cause conflict exception here instead of " +
         //" foreign key constraint exception here"
         );
    }     
    //according to the discussion in #42672, the insert should be blocked/ expected to get
    //conflict exception. so add back the expected conflict
   
    //no matter there is conflict for foreign key or not, (when conflict detected, all keys
    //hold by this tx, or this op - needs to be removed.
    //key needs to be verified to see there is another insert for the same key
    keys.put(getTableName()+"_cid_"+cid+"_sid_"+sid, txId); 
    
    /* TODO may need to revert back depends on the resolution for #42672
     * if no blocking/conflict for update in parent, will restore the followings
    boolean hasRangeForeignKeyConflict = cidRangeKey.hasConflictAddPartialRangeKey(txId) 
       || sidRangeKey.hasConflictAddPartialRangeKey(txId);

    rangeForeignKeys.put(getTableName() + cidPrefix + cid, cidRangeKey);
    rangeForeignKeys.put(getTableName() + sidPrefix + sid, sidRangeKey);
    
    ArrayList<String> curTxRangeFK = (ArrayList<String>)SQLDistTxTest.curTxFKHeld.get();
    curTxRangeFK.add(keyCid);
    curTxRangeFK.add(keySid);
    SQLDistTxTest.curTxFKHeld.set(curTxRangeFK);
    

    expectConflict[0] = // hasForeignKeyConflict || 
        hasRangeForeignKeyConflict;
    if (hasForeignKeyConflict) {
      Log.getLogWriter().info("should expect lock not held/conflict exception here" +
         " but due to non update of primary key in parent table, will relax a little here." +
         " however the insert in parent should cause conflict exception here instead of " +
         " foreign key constraint exception here");
    }     //TODO -- may need discussion on whether this is acceptable
    //relax a little here as update in parent may be fine due to no primary key update
    
    //no foreign conflict, so the tx will hold the key for this operation
    //if another tx has the same key, it needs to be handled in verifying key
    keys.put(getTableName()+"_cid_"+cid+"_sid_"+sid, txId); 
    */
  }
  
  protected void insertToGfxdTable(Connection conn, int[] cid, int[] sid, 
      int[] qty, BigDecimal[] sub, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = null;
    if (SQLTest.isEdge && !isTicket48176Fixed && isHATest) stmt = getStmtThrowException(conn, insert);
    else stmt = conn.prepareStatement(insert);
    int tid = getMyTid();
    for (int i=0 ; i<size ; i++) {
      updateCount[i] = insertToTable(stmt, cid[i], sid[i], qty[i], sub[i], tid);
    }
  }
  
  protected void removePartialRangeForeignKeys(int[] cid, int[] sid) {
    int txId = (Integer) SQLDistTxTest.curTxId.get();
    hydra.blackboard.SharedMap rangeForeignKeys = SQLTxSecondBB.getBB().getSharedMap();
    RangeForeignKey cidRangeKey = (RangeForeignKey) rangeForeignKeys.get(getTableName() + cidPrefix + cid[0]);
    RangeForeignKey sidRangeKey = (RangeForeignKey) rangeForeignKeys.get(getTableName() + sidPrefix + sid[0]);

    
    if (cidRangeKey!=null) cidRangeKey.removePartialRangeKey(txId);
    if (sidRangeKey!=null) sidRangeKey.removePartialRangeKey(txId);
    Log.getLogWriter().info("removing the partial range foreign key for this txId");
  }
  
  protected boolean insertToDerbyTable(Connection conn, int[] cid, int[] sid, 
      int[] qty, BigDecimal[] sub, int[] updateCount, int size) throws SQLException {
    PreparedStatement stmt = getStmt(conn, insert);
    int tid = getMyTid();
    int count =-1;
    
    for (int i=0 ; i<size ; i++) {
      count = insertToTable(stmt, cid[i], sid[i], qty[i], sub[i], tid);
      if (count != updateCount[i]) {
        Log.getLogWriter().info("derby insert has different row count from that of gfxd " +
          "gfxd inserted " + updateCount[i] +
          " row, but derby inserted " + count + " row in " + getTableName());
      }
    }
    return true;
  }

  /* (non-Javadoc)
 * @see sql.dmlStatements.AbstractDMLStmt#query(java.sql.Connection, java.sql.Connection)
 */
  @Override
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = 6; //how many select statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not

    int qty = 1000;
    int avail = 500;
    int startPoint = 10000;
    int range = 100000; //used for querying subTotal

    BigDecimal subTotal1 = new BigDecimal(Integer.toString(rand.nextInt(startPoint)));
    BigDecimal subTotal2 = subTotal1.add(new BigDecimal(Integer.toString(rand.nextInt(range))));
    int queryQty = rand.nextInt(qty);
    int queryAvail = rand.nextInt(avail);
    int sid = 0;
    int cid = 0;
    for (int i = 0; i < 10; i++) {
      Log.getLogWriter().info("RR: executing query(getKey) " + i + "times");
      try {
        int[] key = getKey(gConn);
        if (key != null) {
          sid = key[0];
          cid = key[1];
        }
      } catch (TestException te) {
        if (te.getMessage().contains("Conflict detected in transaction operation and it will abort") && (i < 9)) {
          Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
          continue;
        } else throw te;
      }
      break;
    }

    int tid = getMyTid();
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    ResultSet discRS = null;
    ResultSet gfeRS = null;

    for(int i=0; i< 10; i++) {
      Log.getLogWriter().info("RR: executing query " + i + "times");
      if (dConn != null) {
        try {
          discRS = query(dConn, whichQuery, subTotal1, subTotal2, queryQty, queryAvail, sid, cid, tid);
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
          gfeRS = query(gConn, whichQuery, subTotal1, subTotal2, queryQty, queryAvail, sid, cid, tid);
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
          Log.getLogWriter().info("Not able to compare results due to derby server error");
        } //not able to compare results due to derby server error
      }// we can verify resultSet
      else {
        try {
          gfeRS = query(gConn, whichQuery, subTotal1, subTotal2, queryQty,
              queryAvail, sid, cid, tid);
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
  protected void addQueryToDerbyTx(int whichQuery, BigDecimal subTotal1, BigDecimal subTotal2, 
      int queryQty, int queryAvail, int sid, int cid, int tid, 
      List<Struct> gfxdList, SQLException gfxdse){
    Object[] data = new Object[12];      
    data[0] = DMLDistTxStmtsFactory.TRADE_PORTFOLIO;
    data[1] = "query";
    data[2] = whichQuery;
    data[3] = subTotal1;
    data[4] = subTotal2;
    data[5] = queryQty;
    data[6] = queryAvail;
    data[7] = sid;
    data[8] = cid;
    data[9] = tid;
    data[10] = gfxdList;
    data[11] = gfxdse;
    
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
    SQLException gfxdse = (SQLException) data[11];
    List<Struct> gfxdList = (List<Struct>) data[10];
    ResultSet derbyRS = null;
    
    try {
      //query(dConn, whichQuery, subTotal1, subTotal2, queryQty, queryAvail, sid, cid, tid); 
      derbyRS = query(dConn, (Integer)data[2], (BigDecimal)data[3], (BigDecimal)data[4],
         (Integer)data[5], (Integer)data[6], (Integer)data[7], (Integer)data[8], 
         (Integer)data[9]); 
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
    int numOfNonUniq = 6; //how many select statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not
    if (whichQuery <6) whichQuery += 6; //query on any rows
    if (whichQuery == 6) whichQuery++; //do not verify all rows of the table
    
    int qty = 1000;
    int avail = 500;
    int startPoint = 10000;
    int range = 100000; //used for querying subTotal

    BigDecimal subTotal1 = new BigDecimal(Integer.toString(rand.nextInt(startPoint)));
    BigDecimal subTotal2 = subTotal1.add(new BigDecimal(Integer.toString(rand.nextInt(range))));
    int queryQty = rand.nextInt(qty);
    int queryAvail = rand.nextInt(avail);
    int sid = 0;
    int cid = 0;
    
    Connection nonTxConn = (Connection)SQLDistTxTest.gfxdNoneTxConn.get();
    int[] key = getKey(nonTxConn);
    if (key !=null) {
      sid = key[0];
      cid = key[1];
    }
    int tid = getMyTid();
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    HashMap<String, Integer> modifiedKeysByTx = (HashMap<String, Integer>)
    SQLDistTxTest.curTxModifiedKeys.get();
    
    try {
      gfxdRS = query (gConn, whichQuery, subTotal1, subTotal2, 
          queryQty, queryAvail, sid, cid, tid);   
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
    
    addQueryToDerbyTx(whichQuery, subTotal1, subTotal2, 
          queryQty, queryAvail, sid, cid, tid, gfxdList, gfxdse);
      //only the first thread to commit the tx in this round could verify results
      //this is handled in the SQLDistTxTest doDMLOp

    return true;
  }

  @Override
  public void updateDerby(Connection dConn, int index) {
    // TODO Auto-generated method stub

  }

  @Override
  public boolean updateGfxd(Connection gConn, boolean withDerby) {
    if (!withDerby) {
      return updateGfxdOnly(gConn);
    }
   // TODO implement with derby
    return true;
  }
  
  public static String getTableName() {
    return "portfolio";
  }

  /* non derby case using trigger*/
  
  protected boolean insertGfxdOnly(Connection gConn){
    try {
      insert(null, gConn, 1); 
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
      } else throw te;
    }
  }
  
  public void populate(Connection dConn, Connection gConn) {
    try {
      super.populate(dConn, gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") && dConn == null) {
        Log.getLogWriter().info ("got expected conflict exception, continuing test");
      } else throw te;
    }
    
  }
  
  //need to be changed to be similar to networth table once the update is actually implemented
  //for now this is used to avoid the #43909 test issue
  protected int getWhichUpdate(int numOfNonUniq, int total) {
    int whichOne = super.getWhichOne(numOfNonUniq, total);
    if (isConcUpdateTx && (whichOne == 1 || whichOne == 6)) 
      //to avoid update qty column in concUpdateTx tx to test updatable result set 
        whichOne++;
      return whichOne;
  }
}
