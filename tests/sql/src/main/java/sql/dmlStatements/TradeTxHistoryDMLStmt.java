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
import hydra.TestConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLTest;
import sql.security.SQLSecurityTest;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import util.TestException;

/**
 * @author eshu
 *
 */
public class TradeTxHistoryDMLStmt extends AbstractDMLStmt {
//cid int, oid int, sid int, qty int, price decimal (30, 20), ordertime timestamp, 
  //type varchar(10), tid int,  constraint type_ch check (type in ('buy', 'sell'))
  
  //does not have primary key set in the table
  protected static String insert = "insert into trade.txhistory values (?,?,?,?,?,?,?,?)";
  protected static String put = "put into trade.txhistory values (?,?,?,?,?,?,?,?)";
  protected static String[] update = { //uniq
    "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=? and tid = ? ",  
    "update trade.txhistory set qty = ?, cid=?  where  oid=? and tid =? ",  
    //no uniq
    "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=?  ",  
    "update trade.txhistory set qty = ?, cid=?  where  oid=?  ",  
    }; 
  protected static String[] select = {//uniqkey queries
    "select * from trade.txhistory where tid = ?",
    "select oid, cid, sid, type, tid from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? and tid = ?",
    "select sid, CAST(count(*) as integer) as numRows from trade.txhistory  where cid>? and sid<? and tid =? GROUP BY sid HAVING count(*) >=1",
    "select cid, max(price) as maxprice from trade.txhistory  where tid =? GROUP BY cid, type",
    "select cid, sum(qty*price) as amount from trade.txhistory  where sid<? and tid =? GROUP BY cid, type ORDER BY amount",
    //no uniqkey queries
    "select * from trade.txhistory ",
    "select oid, cid, sid, type, tid from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? ",
    "select sid, CAST(count(*) as integer) as numRows from trade.txhistory  where cid>? and sid<? GROUP BY sid HAVING count(*) >=1",
    "select cid, count(sid) from trade.txhistory GROUP BY cid, type",  
    "select cid, sum(qty*price) as amount from trade.txhistory  where sid<? GROUP BY cid, type ORDER BY amount"
    };
  protected static String[] selectwoaggreate = {
    //uniqkey queries
    "select * from trade.txhistory where tid = ?",
    "select oid, cid, sid, type, tid from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? and tid = ?",
    "select * from trade.txhistory  where cid>? and sid<? and tid =? order by sid",  
    "select * from trade.txhistory  where tid =? order by cid, type",  
    "select * from trade.txhistory  where sid<? and tid =? order BY cid",
    //no uniqkey queries
    "select * from trade.txhistory ",
    "select oid, cid, sid, type, tid from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? ",
    "select sid, count(*) from trade.txhistory  where cid>? and sid<? GROUP BY sid HAVING count(*) >=1",  
    "select cid, count(sid) from trade.txhistory GROUP BY cid, type",  
    "select cid, sum(qty*price) as amount from trade.txhistory  where sid<? GROUP BY cid, type ORDER BY amount"
  };
  protected static String[] delete = {
    //uniqkey                            
    "delete from trade.txhistory where cid=? and sid=? and tid=?", //could be batch delete, but no foreign key reference, this should work
    "delete from trade.txhistory where oid=? and (price <? or qty>?) and type = 'buy' and tid=?",
    "delete from trade.txhistory where oid=? and (price >? or qty<?) and cid >=? and ordertime <? and tid=?",
    "delete from trade.txhistory where (type ='buy' or qty<?) and price>? and tid=?",
    "delete from trade.txhistory where (type ='sell' or ordertime>?) and sid=? and cid <? and tid=?",
    //non uniqkey
    "delete from trade.txhistory where cid=? and sid=? ",
    "delete from trade.txhistory where oid=? and (price <? or qty>?) and type = 'buy' ",
    "delete from trade.txhistory where oid=? and (price >? or qty<?) and cid >=? and ordertime <? ",
    "delete from trade.txhistory where (type ='buy' or qty<?) and price>? ",
    "delete from trade.txhistory where (type ='sell' or ordertime>?) and sid=? and cid <?",
  };
  
  public static int maxNumOfTries = 2;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  protected static ArrayList<String> partitionKeys = null;

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#insert(java.sql.Connection, java.sql.Connection, int)
   */
  public void insert(Connection dConn, Connection gConn, int size) {
    insert(dConn, gConn, size, false);
  }
  private void insert(Connection dConn, Connection gConn, int size, boolean isPut) {
    //in trigger test, this will be populated in buy/sell orders
    //in other tests, it could have multiple records as this does not have primary key setting
    int[] cid = new int[size];
    int[] oid = new int[size];
    int[] sid = new int[size];
    int[] qty = new int[size];
    String[] type = new String[size];
    Timestamp[] time = new Timestamp[size];
    BigDecimal[] price = new BigDecimal[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();

    if (dConn != null) {
      //get data when possible
      if (rand.nextInt(numGettingDataFromDerby) == 1 ) {
        if (!getDataForInsert(dConn, oid, cid, sid, qty, type, time, price, size)) { 
          Log.getLogWriter().info("Not able to get data for insert");
          return; //if not able to get the data, skip this operation
        }
      } else {
        if (setCriticalHeap && !getDataForInsert(gConn, oid, cid, sid, qty, type, time, price, size)) {
          Log.getLogWriter().info("Not able to get data for insert");
          return; //if not able to get the data, skip this operation
        }
        else  
          getDataForInsert(gConn, oid, cid, sid, qty, type, time, price, size);
      }
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = insertToDerbyTable(dConn, oid, cid, sid, qty, type, time, price, size, exceptionList);  //insert to derby table  
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finish the operation in derby, abort this operation");
          return; 
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++; 
        exceptionList .clear(); //clear the exceptionList and retry
        success = insertToDerbyTable(dConn, oid, cid, sid, qty, type, time, price, size, exceptionList); 
      }
      insertToGFETable(gConn, oid, cid, sid, qty, type, time, price, size, exceptionList, isPut); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      if (getDataForInsert(gConn, oid, cid, sid, qty, type, time, price, size))//get the data
        insertToGFETable(gConn, oid, cid, sid, qty, type, time, price, size, isPut);
    } //no verification

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#update(java.sql.Connection, java.sql.Connection, int)
   */
  public void update(Connection dConn, Connection gConn, int size) {
    //in trigger test, there should be no update in this table
    //we test update for a table with no primary key set
    //     "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=? and tid = ? ",  
    // "update trade.txhistory set qty = ?, cid=?  where  oid=? and tid =? ",  
    int[] sid = new int[size];
    int[] cid = new int[size];
    int[] oid = new int[size];
    String[] type = new String[size];
    int[] qty = new int[size];
    
    int[]  whichUpdate = new int[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (setCriticalHeap && !hasTx) {
      //could not find single row to be deleted (see #39605)
      return;
    }

    if (dConn != null) {     
      boolean getData = false; 
      if (rand.nextInt(numGettingDataFromDerby) == 1) getData = getDataForUpdate(dConn, cid, sid, oid, qty, type, whichUpdate, size); //get the data
      else getData = getDataForUpdate(gConn, cid, sid, oid, qty, type, whichUpdate, size); //get the data
      
      //due to #49452, fk was taken out 
      //only cid is being updated
      if (hasHdfs) {
        for (int id: cid) {
          if (id == 0) {
            Log.getLogWriter().info("do not update 0 for hdfs tests when fk was taken out due to #49452");
            return;
          }
        }
      }
      
      if(!getData) {
        Log.getLogWriter().info("Not able to get data for update");
        return;
      }
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = updateDerbyTable(dConn, cid, sid, oid, qty, type, whichUpdate, size, exceptionList);  //insert to derby table  
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finish the operation in derby, abort this operation");
          return; 
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++; 
        exceptionList .clear();
        success = updateDerbyTable(dConn, cid, sid, oid, qty, type, whichUpdate, size, exceptionList);  //insert to derby table  
      } //retry until this update will be executed.
      updateGFETable(gConn, cid, sid, oid, qty, type, whichUpdate, size, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      getDataForUpdate(gConn, cid, sid, oid, qty, type, whichUpdate, size); //get the da
      updateGFETable(gConn, cid, sid, oid, qty, type, whichUpdate, size);
    } //no verification

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#delete(java.sql.Connection, java.sql.Connection)
   */
  public void delete(Connection dConn, Connection gConn) {
    int numOfNonUniqDelete = delete.length/2;  //how many delete statement is for non unique keys
    int whichDelete = getWhichOne(numOfNonUniqDelete, delete.length);
    
    if (setCriticalHeap && !hasTx) {
      //could not find single row to be deleted (see #39605)
      return;
    }
    
    int size = 1; //how many delete to be completed in this delete operation
    int[] cid = new int[size]; //only delete one record
    int[] sid = new int[size];
    int[] oid = new int[size];
    int[] qty = new int[size];
    Timestamp[] time = new Timestamp[size]; 
    BigDecimal[] price = new BigDecimal[size];
    for (int i=0; i<size; i++) {
      oid[i] = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeBuyOrdersPrimary)); //random instead of uniq
      qty[i] = getQty(); //the qty
      price[i] = getPrice(); //the price
      time[i] = new Timestamp(System.currentTimeMillis()-rand.nextInt(10*60*1000));       
    }
    
    List<SQLException> exceptionList = new ArrayList<SQLException>(); //for compare exceptions got from two sources
    int availSize = getDataForDelete(gConn, cid ,sid);
    
    if(availSize == 0 || availSize == -1) return; //did not get the results
    
    if (setCriticalHeap) resetCanceledFlag();
    
    //for verification both connections are needed
    if (dConn != null) {
      boolean success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, price, qty, time, exceptionList);
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finish the operation in derby, abort this operation");
          return; 
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++; 
        exceptionList.clear();
        success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, price, qty, time, exceptionList); //retry
      }
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid, price, qty, time, exceptionList);
      SQLHelper.handleMissedSQLException(exceptionList);
    } 
    else {
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid, price, qty, time); //w/o verification
    }

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#query(java.sql.Connection, java.sql.Connection)
   */
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = select.length/2; //how many query statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not

    int qty = getQty();
    int cid = getCid();  //anuy cid could be used in query string as we query records insert by this tid only
    int sid = getSid();  //anuy cid could be used in query string
    int tid = getMyTid();
    Timestamp time = getRandTime();
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (dConn!=null) {
      try {
        discRS = query(dConn, whichQuery, qty, cid, sid, time, tid);
        if (discRS == null) {
          Log.getLogWriter().info("could not get the derby result set after retry, abort this query");
          return;
        }
      } catch (SQLException se) {
        SQLHelper.handleDerbySQLException(se, exceptionList);
      }
      try {
        gfeRS = query (gConn, whichQuery, qty, cid, sid, time, tid); 
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
        return;
      }
      SQLHelper.handleMissedSQLException(exceptionList);
      if (discRS == null || gfeRS == null) return;
      
      boolean success = true;
      try {
        if (useWriterForWriteThrough) success = ResultSetHelper.compareDuplicateResultSets(discRS, gfeRS);
        else if (isHATest && SQLHelper.isThinClient(gConn)) {
          Log.getLogWriter().info("could not compare results for table " +
          "without primary key in HA test when using thin client driver " +
          "due to the known issue of #44929"); //work around #44929
          return;
        }
        else success = ResultSetHelper.compareResultSets(discRS, gfeRS); 
        
        if (!success) {
          Log.getLogWriter().info("Not able to compare results due to derby server error or #41471");
        } //not able to compare results due to derby server error    
      } catch (TestException te) {
        if ((te.getMessage().contains("elements were missing") 
            || te.getMessage().contains("unexpected elements")) 
            && whichQuery>=2 && whichQuery<=4) {
          
          dumpNoAggregateRs.set(true);
          //This is best effort to dump aggregate results
          //either db dumped results should help to analyze the data mismatch issue
          
          //dump derby result
          try {
            discRS = query(dConn, whichQuery, qty, cid, sid, time, tid);
            List<Struct> derbyList = ResultSetHelper.asList(discRS, true);
            if (derbyList != null)
              Log.getLogWriter().info("dump non-aggregate result from derby due to data mismatch:" +
                ResultSetHelper.listToString(derbyList));
            else Log.getLogWriter().info("not able to get derby results for dump");
          } catch (SQLException se) {
            Log.getLogWriter().info("not able to get derby results for dump");
          }
          
          //dump gfxd result
          try {
            gfeRS = query (gConn, whichQuery, qty, cid, sid, time, tid);
            List<Struct> gfxdList = ResultSetHelper.asList(gfeRS, false);
            if (gfxdList != null)
              Log.getLogWriter().info("dump non-aggregate result from gfxd due to data mismatch:" +
                ResultSetHelper.listToString(gfxdList));
            else Log.getLogWriter().info("not able to get gfxd results for dump");
          } catch (SQLException se) {
            Log.getLogWriter().info("not able to get gfxd results for dump");
          }
          
          //dump query plan
          dumpNoAggregateRs.set(false);
          dumpQueryPlanRs.set(true);
          try {
            gfeRS = query(gConn, whichQuery, qty, cid, sid, time, tid);
            StringBuilder plan = new StringBuilder();
            while (gfeRS.next()) {
              plan.append(gfeRS.getString(1)).append('\n');
            }
            if (plan.length() > 0) {
              Log.getLogWriter().info(
                  "dump query plan in gfxd due to data mismatch:" + plan);
            }
            else {
              Log.getLogWriter().info("no query plan in gfxd found");
            }
          } catch (SQLException se) {
            Log.getLogWriter().info(
                "not able to get gfxd queryplan due to exception "
                    + se.getSQLState(), se);
          }

          dumpQueryPlanRs.set(false);
        } 
        
        throw te; 
      } 
    }// we can verify resultSet
    else {
      try {
        gfeRS = query (gConn,  whichQuery,qty, cid, sid, time, tid);  
      } catch (SQLException se) {
        if (se.getSQLState().equals("42502") && SQLTest.testSecurity) {
          Log.getLogWriter().info("Got expected no SELECT permission, continuing test");
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
  
  //--- insert ---//
  //populate arrays for insert
  protected boolean getDataForInsert(Connection regConn, int[] oid, int[] cid, int[] sid, int[] qty, String[] type, 
      Timestamp[] time, BigDecimal[] price, int size) {
    Connection conn = getAuthConn(regConn);
    int whichQuery =0;
    //boolean[] success = new boolean[1];
    ResultSet bors = TradeBuyOrdersDMLStmt.getQuery(conn, whichQuery, null, null, 0, 0, 0, null, getMyTid());
    //if (!success[0]) return false; //avoid resultSet not open error
    ArrayList<Struct> bolist = (ArrayList<Struct>) 
        ResultSetHelper.asList(bors, SQLHelper.isDerbyConn(conn));
    
    ResultSet sors = TradeSellOrdersDMLStmt.getQuery(conn, whichQuery, null, null, null, null, null, getMyTid());
    //if (!success[0]) return false; //avoid resultSet not open error    
    ArrayList<Struct> solist = (ArrayList<Struct>) 
        ResultSetHelper.asList(sors, SQLHelper.isDerbyConn(conn));
    
    SQLHelper.closeResultSet(bors, conn); 
    SQLHelper.closeResultSet(sors, conn); 
    
    if (setCriticalHeap) {      
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled[0] == true) {
        Log.getLogWriter().info("memory runs low -- no data will be return");
        return false;  //do not do insert if getCanceled flag is on
      }
    }
    
    if (bolist != null && solist != null) {
      if (solist.size()!=0 && bolist.size()!=0) {
        if (rand.nextInt()%2 == 0)  getDataForInsertFromSO(solist, oid, cid, sid, qty, type, time, price, size);
        else getDataForInsertFromBO(bolist, oid, cid, sid, qty, type, time, price, size);
      } //randomly populate data from buyorders or sellorders
      else if (solist.size()!= 0) getDataForInsertFromSO(solist, oid, cid, sid, qty, type, time, price, size);
      else if (bolist.size()!= 0) getDataForInsertFromBO(bolist, oid, cid, sid, qty, type, time, price, size);
      else return false;
    }
    else if (bolist == null && solist != null) {
      if (solist.size()!= 0) getDataForInsertFromSO(solist, oid, cid, sid, qty, type, time, price, size);
      else return false;
    }
    else if (solist ==null && bolist != null){
      if (bolist.size()!= 0) getDataForInsertFromBO(bolist, oid, cid, sid, qty, type, time, price, size);
      else return false;
    }
    else {
      return false;
    }
    
    return true;
  }
  
  //get data from query results of buyorders
  protected void getDataForInsertFromBO(ArrayList<Struct> bolist, 
      int[] oid, int[] cid, int[] sid, int[] qty, String[] type, 
      Timestamp[] time, BigDecimal[] price, int size) {
    for (int i=0; i<size; i++) {
      type[i] = "buy";
      
      int index = rand.nextInt(bolist.size());
      GFXDStructImpl aRow = (GFXDStructImpl)bolist.get(index);
      String[] fieldNames = aRow.getFieldNames();
      for (String field: fieldNames) {
        if (field.equalsIgnoreCase("CID"))
          cid[i] = ((Integer)aRow.get("CID")).intValue();
        else if (field.equalsIgnoreCase("OID"))
          oid[i] = ((Integer)aRow.get("OID")).intValue();
        else if (field.equalsIgnoreCase("SID"))
          sid[i] = ((Integer)aRow.get("SID")).intValue();
        else if (field.equalsIgnoreCase("QTY"))
          qty[i] = ((Integer)aRow.get("QTY")).intValue();
        else if (field.equalsIgnoreCase("ORDERTIME")) {
          if (rand.nextInt(10) != 1) time[i] = (Timestamp)aRow.get("ORDERTIME");
          //testing null value in some cases.           
        }
        else if (field.equalsIgnoreCase("BID"))
          price[i] = (BigDecimal)aRow.get("BID");
      }
     
      /*
      cid[i] = ((Integer)((GFXDStructImpl)(bolist.get(index))).get("CID")).intValue();
      oid[i] = ((Integer)((GFXDStructImpl)(bolist.get(index))).get("OID")).intValue();
      sid[i] = ((Integer)((GFXDStructImpl)(bolist.get(index))).get("SID")).intValue();
      qty[i] = ((Integer)((GFXDStructImpl)(bolist.get(index))).get("QTY")).intValue();
      time[i] = (Timestamp)((GFXDStructImpl)(bolist.get(index))).get("ORDERTIME");
      price[i] = (BigDecimal)((GFXDStructImpl)(bolist.get(index))).get("BID");
      */
    }
  }
  
//get data from query results of sellorders
  protected void getDataForInsertFromSO(ArrayList<Struct> solist, 
      int[] oid, int[] cid, int[] sid, int[] qty, String[] type, 
      Timestamp[] time, BigDecimal[] price, int size) {
    for (int i=0; i<size; i++) {
      type[i] = "sell";
      
      int index = rand.nextInt(solist.size());
      GFXDStructImpl aRow = (GFXDStructImpl)solist.get(index);
      String[] fieldNames = aRow.getFieldNames();
      for (String field: fieldNames) {
        if (field.equalsIgnoreCase("CID"))
          cid[i] = ((Integer)aRow.get("CID")).intValue();
        else if (field.equalsIgnoreCase("OID"))
          oid[i] = ((Integer)aRow.get("OID")).intValue();
        else if (field.equalsIgnoreCase("SID"))
          sid[i] = ((Integer)aRow.get("SID")).intValue();
        else if (field.equalsIgnoreCase("QTY"))
          qty[i] = ((Integer)aRow.get("QTY")).intValue();
        else if (field.equalsIgnoreCase("ORDER_TIME")) {
          if (rand.nextInt(10) != 1) time[i] = (Timestamp)aRow.get("ORDER_TIME");
          //testing null value in some cases. 
        }
        else if (field.equalsIgnoreCase("ASK"))
          price[i] = (BigDecimal)aRow.get("ASK");
      } //due to alter table drop column, some field could be dropped

      
      /*
      cid[i] = ((Integer)((GFXDStructImpl)(solist.get(index))).get("CID")).intValue();
      oid[i] = ((Integer)((GFXDStructImpl)(solist.get(index))).get("OID")).intValue();
      sid[i] = ((Integer)((GFXDStructImpl)(solist.get(index))).get("SID")).intValue();
      qty[i] = ((Integer)((GFXDStructImpl)(solist.get(index))).get("QTY")).intValue();
      time[i] = (Timestamp)((GFXDStructImpl)(solist.get(index))).get("ORDER_TIME");
      price[i] = (BigDecimal)((GFXDStructImpl)(solist.get(index))).get("ASK");
      */
    }
  }

  //insert into Derby
  protected boolean insertToDerbyTable(Connection conn, int[] oid, int[] cid, int[] sid, 
      int[] qty, String[] type, Timestamp[] time, BigDecimal[] price, int size,
      List<SQLException> exceptions)  {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      try {
      verifyRowCount.put(tid+"_insert"+i, 0);
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], type[i], time[i], price[i], tid);
        verifyRowCount.put(tid+"_insert"+i, count);
        
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
      int[] qty, String[] type, Timestamp[] time, BigDecimal[] price, int size, 
      List<SQLException> exceptions, boolean isPut) {
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exceptions);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
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
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      try {
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], type[i], time[i], 
            price[i], tid, isPut);
        if (count != ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd insert has different row count from that of derby " +
            "derby inserted " + ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      } catch (SQLException se) {
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
        SQLHelper.handleGFGFXDException(se, exceptions);
      }
    } 
  }

  //insert into gemfirexd/gfe w/o verification
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String[] type, Timestamp[] time, BigDecimal[] price, int size)  {
    insertToGFETable(conn, oid, cid, sid, qty, type, time, price, size, false);
  }
  //insert into gemfirexd/gfe w/o verification
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String[] type, Timestamp[] time, BigDecimal[] price, int size, boolean isPut)  {
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);
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
      try {
        insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], type[i], time[i], price[i], tid, isPut);
      } catch (SQLException se) {
        if (se.getSQLState().equals("42500") && testSecurity) 
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
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
      String type, Timestamp time, BigDecimal price, int tid) throws SQLException {
    return insertToTable(stmt, oid, cid, sid, qty, type, time, price, tid, false);
  }
  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String type, Timestamp time, BigDecimal price, int tid, boolean isPut) throws SQLException {
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " ;
    
    Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.txhistory with OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",TYPE:" + type +
        ",TIME:"+ time + ",PRICE:" + price + ",TID:" + tid);
    String driverName = stmt.getConnection().getMetaData().getDriverName();
    stmt.setInt(1, cid);
    stmt.setInt(2, oid);
    stmt.setInt(3, sid);
    stmt.setInt(4, qty);
    stmt.setBigDecimal(5, price);
    if (time == null && alterTableDropColumn) stmt.setNull(6, Types.TIMESTAMP);
    else {
      if (testworkaroundFor51519 && time != null) stmt.setTimestamp(6, time, getCal());
      else {
        if(time ==null)
          stmt.setNull(6, Types.TIMESTAMP);
        else
          stmt.setTimestamp(6, time);
      }
    }
    stmt.setString(7, type);       
    stmt.setInt(8, tid);
    int rowCount = stmt.executeUpdate();
    
    Log.getLogWriter().info(database + (isPut ? "put " : "inserted ") + rowCount  + " rows into trade.txhistory OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",TYPE:" + type +
        ",TIME:"+ time + ",PRICE:" + price + ",TID:" + tid);
    
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    
    
    if ( driverName.toLowerCase().contains("gemfirexd") && isPut && ! SQLTest.ticket49794fixed) {
      insertToTxhistoryFulldataset(stmt.getConnection() , cid,oid,sid,qty,time,price,type,tid);
      }
    
    /* do not execute put again as this will cause additional row inserted in gfxd due to non primary key in the table
    if ( driverName.toLowerCase().contains("gemfirexd") && isPut) {
      Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.txhistory with OID:" + oid +
          ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",TYPE:" + type +
          ",TIME:"+ time + ",PRICE:" + price + ",TID:" + tid);
      
     rowCount = stmt.executeUpdate();        
     
     Log.getLogWriter().info(database + (isPut ? "put " : "inserted ") + rowCount  + " rows into trade.txhistory OID:" + oid +
         ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",TYPE:" + type +
         ",TIME:"+ time + ",PRICE:" + price + ",TID:" + tid);
     
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
     if ( driverName.toLowerCase().contains("gemfirexd") && isPut && ! SQLTest.ticket49794fixed) {
       insertToTxhistoryFulldataset(stmt.getConnection() , cid,oid,sid,qty,time,price,type,tid);
       }     
    }
    */
    return rowCount;
  }
  
  protected void insertToTxhistoryFulldataset(Connection conn, int cid ,  int oid , int sid , int qty ,  Timestamp time, BigDecimal price, String type, int tid ){
    
    try{
    //manually update fulldataset table for above entry.
    String selectStatement = "select count(*) FROM TRADE.TXHISTORY_FULLDATASET  WHERE cid = ?" +
    " and oid = ? " +
    " and sid = ?"  +
    " and qty = ?"  + 
    " and price = ?" +          
    " and  type = ?" +
    " and tid = ?" +
    ( time == null ? " and ordertime is null ":  " and ordertime = ?" ) ;
    
    String insertStatement = "insert into trade.txhistory_FULLDATASET values (?,?,?,?,?,?,?,?)";                     
            
    PreparedStatement preparedSelectStmt = conn.prepareStatement(selectStatement);          
    
    preparedSelectStmt.setInt(1, cid);
    preparedSelectStmt.setInt(2, oid);
    preparedSelectStmt.setInt(3, sid);
    preparedSelectStmt.setInt(4, qty);
    preparedSelectStmt.setBigDecimal(5, price);        
    preparedSelectStmt.setString(6, type);       
    preparedSelectStmt.setInt(7, tid);
    if (time != null ) {
      if (testworkaroundFor51519) preparedSelectStmt.setTimestamp(8, time, getCal());
      else preparedSelectStmt.setTimestamp(8, time);
    }
                   
     if (preparedSelectStmt.executeQuery().next()){
       Log.getLogWriter().info(" Trigger behaviour is not defined for putDML (bug #49794 ) hence inserting  the  row  into  TRADE.TXHISTORY_FULLDATASET with data CID:" +  cid +  ",SID:" + sid  + ",QTY:" +  qty + ",ORDERTIME:" + time + ",OID:" + oid + ",TYPE: " + type + ",PRICE:" + price);           
       PreparedStatement ps = conn.prepareStatement(insertStatement);           
       ps.setInt(1, cid);
       ps.setInt(2, oid);
       ps.setInt(3, sid);
       ps.setInt(4, qty);
       ps.setBigDecimal(5, price);
       if (testworkaroundFor51519 && time != null) ps.setTimestamp(6, time, getCal());
       else ps.setTimestamp(6, time);
       ps.setString(7, type);       
       ps.setInt(8, tid);            
       ps.executeUpdate();
    }    
    }catch (SQLException  se){
      Log.getLogWriter().info("Error while updating TXHISTORY_FULLDATASET table. It may cause Data inconsistency " + se.getMessage() ); 
    }
  }
  //return a Time used in query
  protected Timestamp getRandTime() {
    return new Timestamp(System.currentTimeMillis()-rand.nextInt(10*60*1000)); //randomly choose in the last 3 minutes
  }
  
  //--- update related ---//
  protected boolean getDataForUpdate(Connection regConn, int[] cid, int[] sid, int[] oid, int[] qty, 
     String[] type, int[] whichUpdate, int size) {
    Connection conn = getAuthConn(regConn);
    int numOfNonUniq = update.length/2;
    boolean success = getDataFromResult(conn, cid, sid, oid);
    if (!success) return false;
    
    int bo = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeBuyOrdersPrimary);
    int so = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSellOrdersPrimary);
    for (int i=0; i<size; i++) {
      qty[i] = getQty();
      type[i] = (rand.nextInt()%2==0) ? "buy": "sell";
      whichUpdate[i] = getWhichOne(numOfNonUniq, update.length);
      if (setCriticalHeap && testUniqueKeys) whichUpdate[i] = 1; //avoid bulk op due to #39605
      if (whichUpdate[i]%2 == 1) {
        if (i!=0) oid[i] = oid[i-1]; //used the last oid, possible the one just being updated to
        else oid[i] = (type[i].equals("buy"))? rand.nextInt(bo): rand.nextInt(so); //use random oid if first
      } else {
      oid[i] = (type[i].equals("buy"))? rand.nextInt(bo): rand.nextInt(so);
      } 
    }
    return true;
  }
  
  //get data from resultSet of this table
  protected boolean getDataFromResult(Connection conn, int[] cid, int[] sid, int[] oid) {
    int tid = getMyTid();  //for test unique key case
    if (!testUniqueKeys) {
      if (tid == 0)  ; //do nothing
      else tid = rand.nextInt(tid);  //data inserted from any tid
    }     
      
    return getDataFromResult(conn, cid, sid, oid, tid);
  }
  
  protected boolean getDataFromResult(Connection regConn, int[] cid, int[] sid, int[] oid, int tid) {
    Connection conn = getAuthConn(regConn);
    int whichQuery =0;
    ResultSet rs = getQuery(conn, whichQuery, 0, 0, 0, null, tid);
    if (rs == null) return false;
    ArrayList<Struct> rslist = (ArrayList<Struct>) 
        ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    
    SQLHelper.closeResultSet(rs, conn); 
    
    if (rslist ==null || rslist.size() == 0) return false;
    else {
      getDataForUpdateFromRS(rslist, cid, sid, oid, cid.length);
    }      
    return true;
  }
  
  //populate arrays used for update
  protected void getDataForUpdateFromRS(ArrayList<Struct> rslist, 
      int[] cid, int[] sid, int[]oid, int size){
    for (int i=0; i<size; i++) {
      int index = rand.nextInt(rslist.size());
      cid[i] = ((Integer)((GFXDStructImpl)(rslist.get(index))).get("CID")).intValue();
      oid[i] = ((Integer)((GFXDStructImpl)(rslist.get(index))).get("OID")).intValue();
    }
  }
  
  protected boolean updateDerbyTable (Connection conn, int[] cid, int[] sid, int[] oid, int[] qty, 
      String[] type, int[] whichUpdate, int size, List<SQLException> exList) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i]);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed

      if (stmt == null) {
      	try {
      		conn.prepareStatement(update[whichUpdate[i]]);
      	} catch (SQLException se) {
      		if (se.getSQLState().equals("08006") || se.getSQLState().equals("08003"))
      			return false;
      	} 
      	//this test of connection is lost is necessary as stmt is null
      	//could be caused by not allowing update on partitioned column. 
      	//the test of connection lost could be removed after #39913 is fixed
      	//just return false if stmt is null
      }
      
      try {
        if (stmt!=null) {
          verifyRowCount.put(tid+"_update"+i, 0);
          count = updateTable(stmt, cid[i], sid[i],oid[i], qty[i], type[i], tid, whichUpdate[i] );
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
  
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate){
    if (partitionKeys == null) setPartitionKeys();

    return getCorrectStmt(conn, whichUpdate, partitionKeys);
  }
  
  @SuppressWarnings("unchecked")
  protected void setPartitionKeys() {
    if (!isWanTest) {
      partitionKeys= (ArrayList<String>)partitionMap.get("txhistoryPartition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_txhistoryPartition");
    }
    Log.getLogWriter().info("partition keys are " + partitionKeys);
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys){   
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      // "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=? and tid = ? ",
      if (partitionKeys.contains("type")||partitionKeys.contains("oid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else ;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      //"update trade.txhistory set qty = ?, cid=?  where  oid=? and tid =? ",  
      if (partitionKeys.contains("qty")||partitionKeys.contains("cid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else ;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      // "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=?  ",  
      if (partitionKeys.contains("type")||partitionKeys.contains("oid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else ;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      //"update trade.txhistory set qty = ?, cid=?  where  oid=?  ",   
      if (partitionKeys.contains("qty")||partitionKeys.contains("cid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else ;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
    
  //check expected exceptions
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] oid, int[] qty, 
      String[] type, int[] whichUpdate, int size, List<SQLException> exList) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i]);
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
          count = updateTable(stmt, cid[i], sid[i],oid[i], qty[i], type[i], tid, whichUpdate[i] );
          if (count != ((Integer)verifyRowCount.get(tid+"_update"+i)).intValue()){
            String str = "Gfxd update has different row count from that of derby " +
                    "derby updated " + ((Integer)verifyRowCount.get(tid+"_update"+i)).intValue() +
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
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] oid, int[] qty, 
      String[] type, int[] whichUpdate, int size) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i]);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
      try {
        if (stmt!=null)
          updateTable(stmt, cid[i], sid[i],oid[i], qty[i], type[i], tid, whichUpdate[i] );
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
  
  protected int updateTable (PreparedStatement stmt, int cid, int sid, int oid, int qty, 
      String type, int tid, int whichUpdate) throws SQLException {
    int rowCount = 0;
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " ;
    String query = " QUERY: " + update[whichUpdate];
    
    switch (whichUpdate) {
    case 0: 
      //    "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=? and tid = ? ",  
      Log.getLogWriter().info(database + "updating trade.txhistory with TYPE:" + type  + ",OID:" + oid +
          " where SID:" + sid + ",CID:" + cid + ",TID:" + tid + query);
      stmt.setString(1, type);
      stmt.setInt(2, oid);      
      stmt.setInt(3, sid); 
      stmt.setInt(4, cid); 
      stmt.setInt(5, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.txhistory TYPE:" + type  + ",OID:" + oid +
          " where SID:" + sid + ",CID:" + cid + ",TID:" + tid + query);
      break;
    case 1:     
      //"update trade.txhistory set qty = ?, cid=?  where  oid=? and tid =? ",  
      Log.getLogWriter().info(database + "updating trade.txhistory with QTY:" + qty  + ",CID:" + cid +
          " where OID:" + oid + ",TID:" + tid + query);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, oid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();    
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.txhistory QTY:" + qty  + ",CID:" + cid +
          " where OID:" + oid + ",TID:" + tid + query);
      break;
    case 2: 
      //    "update trade.txhistory set type = ?, oid=?  where sid = ? and cid=? ",  
      Log.getLogWriter().info(database + "updating trade.txhistory with TYPE:" + type  + ",OID:" + oid +
          " where SID:" + sid + ",CID:" + cid + ",TID:" + tid + query);
      stmt.setString(1, type);
      stmt.setInt(2, oid);      
      stmt.setInt(3, sid); 
      stmt.setInt(4, cid); 
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.txhistory with TYPE:" + type  + ",OID:" + oid +
          " where SID:" + sid + ",CID:" + cid + ",TID:" + tid + query);
      break;
    case 3:     
      //"update trade.txhistory set qty = ?, cid=?  where  oid=?  ",  
      Log.getLogWriter().info(database + "updating trade.txhistory with QTY:" + qty + ",CID:" + cid +
          " where OID:" + oid + ",TID:" + tid);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, oid);
      rowCount = stmt.executeUpdate();    
      Log.getLogWriter().info(database + "updated  " +  rowCount + " rows in trade.txhistory with QTY:" + qty + ",CID:" + cid +
          " where OID:" + oid + ",TID:" + tid);
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
  //-- query methods --//
  //will retry until rs is available
  public static ResultSet getQuery(Connection conn, int whichQuery, int qty, int cid, int sid, 
      Timestamp orderTime, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    try {
      rs = getQuery(conn, whichQuery, qty, cid, sid, orderTime, tid, success);
      int count = 0;
      while (!success[0]) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
          return null; 
        }
        count++;   
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        rs = getQuery(conn, whichQuery, qty, cid, sid, orderTime, tid, success);
      } //retry 
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    return rs;
  }
  
  protected static ResultSet query(Connection conn, int whichQuery, int qty, int cid, int sid, 
      Timestamp orderTime, int tid) throws SQLException {
    boolean[] success = new boolean[1];
    ResultSet rs = getQuery(conn, whichQuery, qty, cid, sid, orderTime, tid, success);
    int count = 0;
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
        return null; 
      }
      count++;   
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      rs = getQuery(conn, whichQuery, qty, cid, sid, orderTime, tid, success);
    } //retry 
    return rs;
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, int qty, int cid, int sid, 
      Timestamp orderTime, int tid, boolean[] success) throws SQLException {
    Boolean dumpResult = (Boolean) dumpNoAggregateRs.get();
    final Boolean dumpQueryPlan = (Boolean) dumpQueryPlanRs.get();
    String sql = dumpResult != null && dumpResult.booleanValue() ? selectwoaggreate[whichQuery]
        : (dumpQueryPlan != null && dumpQueryPlan.booleanValue() ? "explain "
            + select[whichQuery] : select[whichQuery]);
    return getQuery(conn, whichQuery, qty, cid, sid, orderTime, tid, success, sql);
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, int qty, int cid, int sid, 
      Timestamp orderTime, int tid, boolean[] success, String sql) throws SQLException {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - " ;
    String query = " QUERY: " + select[whichQuery];
    
    try {
      stmt = conn.prepareStatement(sql);
      /*
      stmt = getStmt(conn, select[whichQuery]);   
      if (setCriticalHeap && stmt == null) {
        return null; //prepare stmt may fail due to XCL54 now
      }
      */
      
      switch (whichQuery){
      case 0:
        //"select * from trade.txhistory where tid = ?",       
        Log.getLogWriter().info(database + "querying trade.txhistory with TID:" +tid + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select oid, cid, sid, type from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? and tid = ?",       
        Log.getLogWriter().info(database + "querying trade.txhistory with CID:" + cid + ",SID:" + sid 
            +",QTY:" + qty + ",ORDERTIME:" + orderTime + ",TID:" + tid + query);   
        stmt.setInt(1, cid);
        stmt.setInt(2, sid);
        stmt.setInt(3, qty);
        if (testworkaroundFor51519 && orderTime != null) stmt.setTimestamp(4, orderTime, getCal());
        else stmt.setTimestamp(4, orderTime);
        stmt.setInt(5, tid); 
        break;
      case 2:
        //"select sid, count(*) from trade.txhistory  where cid>? and sid<? and tid =? GROUP BY sid HAVING count(*) >=1",  
        Log.getLogWriter().info(database + "querying trade.txhistory with CID:" + cid  + 
            ",SID:" + sid + ",TID:" +tid + query);   
        stmt.setInt(1, cid);
        stmt.setInt(2,sid);
        stmt.setInt(3, tid);
        break;
      case 3:
        // "select cid, count(sid) from trade.txhistory  where tid =? GROUP BY cid, type",          
        Log.getLogWriter().info(database + "querying trade.txhistory with TID: " +tid + query);   
        stmt.setInt(1, tid);
        break;
      case 4:
        //"select cid, sum(qty*price) as amount from trade.txhistory  where sid<? and tid =? GROUP BY cid, type ORDER BY amount",
        Log.getLogWriter().info(database + "querying trade.txhistory with SID:" + sid  + ",TID:" +tid + query);   
        stmt.setInt(1, sid);
        stmt.setInt(2, tid);
        break;
      case 5:
        //       "select * from trade.txhistory ",       
        Log.getLogWriter().info(database + "not executing any query in trade.txhistory" );   
        break;
      case 6: 
        //"select oid, cid, sid, type from trade.txhistory where cid >? and sid <? and qty >? and orderTime<? ",
        Log.getLogWriter().info(database + "querying trade.txhistory with CID:" + cid + ",SID:" + sid 
            +",QTY:" + qty + ",ORDERTIME:" + orderTime + query);   
        stmt.setInt(1, cid);
        stmt.setInt(2, sid);
        stmt.setInt(3, qty);
        if (testworkaroundFor51519 && orderTime != null) stmt.setTimestamp(4, orderTime, getCal());
        else stmt.setTimestamp(4, orderTime);
        break;
      case 7:
        //"select sid, count(*) from trade.txhistory  where cid>? and sid<? GROUP BY sid HAVING count(*) >=1",  
        Log.getLogWriter().info(database + "querying trade.txhistory with CID:" + cid  +  ",SID:" + sid + query);   
        stmt.setInt(1, cid);
        stmt.setInt(2,sid);
        break;
      case 8:
        // "select cid, count(sid) from trade.txhistory  GROUP BY cid, type",        
        Log.getLogWriter().info(database + "not executing any query in trade.txhistory" );   
        break;
      case 9:
        //"select cid, sum(qty*price) as amount from trade.txhistory  where sid<? GROUP BY cid, type ORDER BY amount",     
        Log.getLogWriter().info(database + "querying trade.txhistory with  SID:" + sid  + ",TID:" +tid + query);   
        stmt.setInt(1, sid);
        break;
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471
      else throw se;  //security related SQLExceptions
    }
    return rs;
  } 
  
  //*** delete methods ***//

  /**
   * get data inserted by this thread or some random data 
   * @param dConn -- connection used to get the record inserted by this thread, when testUniqueKey is true. Otherwise, this is not used and use random data
   * @param cid -- array of cid to be populated
   * @param sid -- array lf sid to be populated
   * @return the number of array elements populated or -1 is none is available
   */
  protected int getDataForDelete(Connection regConn, int[] cid, int[] sid) {
    Connection conn = getAuthConn(regConn);
    int availSize = cid.length;
    int firstQuery = 0; //first query is select *
    ResultSet rs = null;
    int[] offset = new int[1];
       
    if (testUniqueKeys) {     
      rs = getQuery(conn, firstQuery, 0, 0, 0, null, getMyTid());
        //get the resultSet in the table inserted by this thread
     
      int count = 0;
      while (rs==null) {
        if (count >= maxNumOfTries) {
          break;
        }
        MasterController.sleepForMs(30*rand.nextInt(10));
        rs = getQuery(conn, firstQuery, 0, 0, 0, null, getMyTid());
        count++;
      } //query may terminated earlier if not able to obtain lock
      
      ArrayList<Struct> result = (ArrayList<Struct>) 
          ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
      
      SQLHelper.closeResultSet(rs, conn); 
      
      if (result == null) {
        Log.getLogWriter().info("Could not get result. abort this operations");
        return -1;
      }
      availSize = getAvailSize(result, cid.length, offset);
     
      for (int i =0 ; i<availSize; i++) {
        cid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("CID")).intValue();
        sid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("SID")).intValue();   
      }
    } //passed the resultset to get an entry insert/updated by this thread
    else {
      getCids(conn, cid); //get random cids
      for (int i =0 ; i<availSize; i++) {
        sid[i]= getSid(); //get random sids
      }
    }    
    return availSize;
  }
  
  //add the exception to expceptionList to be compared with gfe
  protected boolean deleteFromDerbyTable(Connection dConn, int whichDelete, 
      int[]cid, int []sid, int[] oid, BigDecimal[] price, int[] qty, 
      Timestamp[] time, List<SQLException> exList){
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]); 
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    
    try {
      for (int i=0; i<cid.length; i++) {
      verifyRowCount.put(tid+"_delete"+i, 0);
        count = deleteFromTable(stmt, cid[i], sid[i], oid[i], price[i], qty[i], time[i], tid, whichDelete);
        verifyRowCount.put(tid+"_delete"+i, new Integer(count));
        
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
      int []sid, int[] oid, BigDecimal[] price, int[] qty, Timestamp[] time, 
      List<SQLException> exList){
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
        count = deleteFromTable(stmt, cid[i], sid[i], oid[i], price[i], qty[i], time[i], tid, whichDelete);
        if (count != ((Integer)verifyRowCount.get(tid+"_delete"+i)).intValue()){
          String str = "Gfxd delete (txhistory) has different row count from that of derby " +
                  "derby deleted " + ((Integer)verifyRowCount.get(tid+"_delete"+i)).intValue() +
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
      int[] sid, int[] oid, BigDecimal[] price, int[] qty, Timestamp[] time){
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]); 
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	} else ; //need to find out why stmt is not obtained
    } //work around #43244
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
    
    try {
      for (int i=0; i<cid.length; i++) {
        deleteFromTable(stmt, cid[i], sid[i], oid[i], price[i], qty[i], time[i], tid, whichDelete);
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
      int oid, BigDecimal price, int qty, Timestamp time, int tid, int whichDelete) throws SQLException {
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " ;
    String query = " QUERY: " + delete[whichDelete];
                                       
    int rowCount =0;
    switch (whichDelete) {
    case 0:   
      //"delete from trade.txhistory where cid=? and sid=? and tid=?"
      Log.getLogWriter().info(database + "deleting trade.txhistory with CID:" + cid + ",SID:" + sid 
          + ",TID:" + tid + query);  
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + "rows in trade.txhistory CID:" + cid + ",SID:" + sid 
          + ",TID:" + tid + query);  
      break;
    case 1:
      //"delete from trade.txhistory where oid=? and (price <? or qty>?) and type = 'buy' and tid=?",
      Log.getLogWriter().info(database + "deleting trade.txhistory with OID:" + oid + ",PRICE:" + price 
          + ",QTY:" + qty + ",TID:" + tid + query);  
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, price);
      stmt.setInt(3, qty);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.txhistory OID:" + oid + ",PRICE:" + price 
          + ",QTY:" + qty + ",TID:" + tid + query);  
      break;
    case 2:   
      //"delete from trade.txhistory where oid=? and (price >? or qty<?) and cid >=? and ordertime <? and tid=?",
      Log.getLogWriter().info(database + "deleting trade.txhistory with OID:" + oid + ",QTY:" 
          + qty + ",PRICE:" + price + ",CID:" + cid + ",ORDERTIME:" + time + ",TID:" + tid + query);  
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, price);
      stmt.setInt(3, qty);
      stmt.setInt(4, cid);
      if (testworkaroundFor51519 && time != null) stmt.setTimestamp(5, time, getCal());
      else stmt.setTimestamp(5, time);
      stmt.setInt(6, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + "rows  trade.txhistory OID:" + oid + ",QTY:" 
          + qty + ",PRICE:" + price + ",CID:" + cid + ",ORDERTIME:" + time + ",TID:" + tid + query);
      break;
    case 3:   
      //"delete from trade.txhistory where (type ='buy' or qty<?) and price>? and tid=?",
      Log.getLogWriter().info(database + "deleting trade.txhistory with QTY:" + qty + ",PRICE:" + price
          + ",TID:" + tid + query); 
      stmt.setInt(1, qty);
      stmt.setBigDecimal(2, price);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + "rows trade.txhistory  QTY:" + qty + ",PRICE:" + price
          + ",TID:" + tid + query); 
      break;
    case 4:
      //"delete from trade.txhistory where (type ='sell' or ordertime>?) and sid=? and cid <? and tid=?",,
      Log.getLogWriter().info(database + "deleting trade.txhistory with ORDERTIME:" + time + ",SID:" + sid 
          + ",CID:" + cid +  ",TID:" + tid + query);
      if (testworkaroundFor51519 && time != null) stmt.setTimestamp(1, time, getCal());
      else stmt.setTimestamp(1, time);
      stmt.setInt(2, sid);
      stmt.setInt(3, cid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted "+  rowCount + " rows trade.txhistory ORDERTIME:" + time + ",SID:" + sid
          + ",CID:" + cid +  ",TID:" + tid + query);
      break;
    case 5:   
      //"delete from trade.txhistory where cid=? and sid=?"
      Log.getLogWriter().info(database + "deleting trade.txhistory with CID:" + cid + ",SID:" + sid + query);  
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows trade.txhistory CID:" + cid + ",SID:" + sid + query);
      break;
    case 6:
      //"delete from trade.txhistory where oid=? and (price <? or qty>?) and type = 'buy'",
      Log.getLogWriter().info(database + "deleting trade.txhistory with OID:" + oid + ",PRICE:" + price 
          + ",QTY:" + qty  + query);  
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, price);
      stmt.setInt(3, qty);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows  trade.txhistory OID:" + oid + ",PRICE:" + price
          + ",QTY:" + qty  + query);  
      break;
    case 7:   
      //"delete from trade.txhistory where oid=? and (price >? or qty<?) and cid >? and ordertime <?",
      Log.getLogWriter().info(database + "deleting trade.txhistory with QTY:" + qty + ",PRICE:" + price 
          + ",CID:" + cid + ",ORDERTIME:" + time + query);  
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, price);
      stmt.setInt(3, qty);
      stmt.setInt(4, cid);
      if (testworkaroundFor51519 && time != null) stmt.setTimestamp(5, time, getCal());
      else stmt.setTimestamp(5, time);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleting trade.txhistory with QTY:" + qty + ",PRICE:" + price 
          + ",CID:" + cid + ",ORDERTIME:" + time + query);
      break;
    case 8:   
      //"delete from trade.txhistory where (type ='buy' or qty<?) and price>? ",
      Log.getLogWriter().info(database + "deleting trade.txhistory with QTY:" + qty + ",PRICE:" + price + query); 
      stmt.setInt(1, qty);
      stmt.setBigDecimal(2, price);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows trade.txhistory  QTY:" + qty + ",PRICE:" + price + query);
      break;
    case 9:
      //"delete from trade.txhistory where (type ='sell' or ordertime>?) and sid=? and cid <?",,
      Log.getLogWriter().info(database + "deleting trade.txhistory with ORDERTIME:" + time + ",SID:" + sid 
          + ",CID:" + cid + query); 
      if (testworkaroundFor51519 && time != null) stmt.setTimestamp(1, time, getCal());
      else stmt.setTimestamp(1, time);
      stmt.setInt(2, sid);
      stmt.setInt(3, cid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + "rows trade.txhistory ORDERTIME:" + time + ",SID:" + sid 
          + ",CID:" + cid + query); 
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
  
  public void populate (Connection dConn, Connection gConn) {
    boolean persistTable = TestConfig.tab().booleanAt(hydra.gemfirexd.FabricServerPrms.persistTables, false);
    if (persistTable) {
      int initSize = 30;
      populate (dConn, gConn, initSize);
    } else super.populate(dConn, gConn);
  }

  @Override
  public void put(Connection dConn, Connection gConn, int size) {
    insert(dConn, gConn, size, true);
    
  }
}
