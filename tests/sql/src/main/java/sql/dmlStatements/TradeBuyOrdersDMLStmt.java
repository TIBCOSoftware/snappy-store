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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import java.util.concurrent.*;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.security.SQLSecurityTest;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import util.TestException;

/**
 * @author eshu
 *
 */
public class TradeBuyOrdersDMLStmt extends AbstractDMLStmt {
  /*
   * trade.orders table fields
   *   int oid;
   *   int cid; //ref customers
   *   int sid; //ref securities sec_id
   *   int qty;
   *   BigDecimal bid;
   *   long ordertime;
   *   String stauts;
   *   int tid; //for update or delete unique records to the thread
   */

  protected static String insert = "insert into trade.buyorders values (?,?,?,?,?,?,?,?)";
  protected static String put = "put into trade.buyorders values (?,?,?,?,?,?,?,?)";
  protected static String[] update = { 
    //uniq
    "update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' and tid = ? ",  //for trigger test it could be a batch update
    "update trade.buyorders set status = 'cancelled' where (ordertime >? or sid=?) and status = 'open' and tid =? ",  //batch operation
    "update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? ",
    "update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open' and tid =? ",
    "update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open' and tid =? ",
    //no uniq
    "update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' ",  //for trigger test it could be a batch update
    "update trade.buyorders set status = 'cancelled' where (ordertime <? or sid=?) and status = 'open'  ",  //batch operation
    "update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? ",
    "update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open'  " ,  
    "update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open'  "
  }; 
  protected static String[] select = {
    //uniqkey queries
    "select * from trade.buyorders where status = 'open' and tid = ?",
    "select cid, bid, cid, sid from trade.buyorders where cid >? and sid <? and qty >? and orderTime<? and tid = ?",
    "select sid, CAST(count(*) as Integer) as COUNT from trade.buyorders  where status =? and tid =? GROUP BY sid HAVING count(*) >=1",
    "select cid, min(qty*bid) as smallest_order from trade.buyorders  where status =? and tid =? GROUP BY cid",  
    "select cid, cast (avg(qty*bid) as decimal (30, 20)) as amount from trade.buyorders  where status =? and tid =? GROUP BY cid ORDER BY amount",
    "select cid, max(qty*bid) as largest_order from trade.buyorders  where status =? and tid =?  GROUP BY cid HAVING max(qty*bid) > 2000 ORDER BY largest_order DESC, cid DESC ",
    "select b1.oid, b1.cid, b1.sid, b1.qty, b1.tid, b2.oid, b2.cid, b2.sid, b2.qty, b2.tid from trade.buyorders b1, trade.buyorders b2 " +
        "where b1.cid = b2.cid and b1.tid = ? and b1.sid = b2.sid and b1.qty < b2.qty and b1.oid < b2.oid", //add self join coverage
    //no uniqkey queries
    "select * from trade.buyorders",
    "select cid, bid, cid, sid from trade.buyorders where cid >?  and sid <? and qty >? and orderTime<?  ",
    "select sid, count(*) as COUNT from trade.buyorders  where status =? GROUP BY sid HAVING count(*) >=1",
    "select cid, count(distinct sid) as DIST_SID from trade.buyorders  where status =? GROUP BY cid",
    "select cid, cast (avg(qty*bid) as decimal (30, 20)) as amount from trade.buyorders  where status =? GROUP BY cid ORDER BY amount",
    "select cid, max(qty*bid) as largest_order from trade.buyorders  where status =? GROUP BY cid HAVING max(qty*bid) > 20000 ORDER BY largest_order, cid DESC ",
    "select b1.oid, b1.cid, b1.sid, b1.qty, b1.tid, b2.oid, b2.cid, b2.sid, b2.qty, b2.tid from trade.buyorders b1, trade.buyorders b2 " +
    "where b1.cid = b2.cid and b1.sid = b2.sid and b1.qty < b2.qty and b1.oid < b2.oid",
  };
  protected static String[] selectwoaggreate = {
    //uniqkey queries
    "select * from trade.buyorders where status = 'open' and tid = ?",
    "select cid, bid, cid, sid from trade.buyorders where cid >? and sid <? and qty >? and orderTime<? and tid = ?",
    "select * from trade.buyorders  where status =? and tid =? order by sid",  
    "select * from trade.buyorders  where status =? and tid =? order by cid",  
    "select cid , (qty*bid) as totalAmt, count(*) as numRows from trade.buyorders  where status =? and tid =? order by cid, oid, sid",
    "select * from trade.buyorders  where status =? and tid =? order by cid",
    "select b1.oid, b1.cid, b1.sid, b1.qty, b1.tid, b2.oid, b2.cid, b2.sid, b2.qty, b2.tid from trade.buyorders b1, trade.buyorders b2 " +
        "where b1.cid = b2.cid and b1.tid = ? and b1.sid = b2.sid and b1.qty < b2.qty and b1.oid < b2.oid", //add self join coverage
    //no uniqkey queries
    "select * from trade.buyorders",
    "select cid, bid, cid, sid from trade.buyorders where cid >?  and sid <? and qty >? and orderTime<?  ",
    "select sid, count(*) from trade.buyorders  where status =? GROUP BY sid HAVING count(*) >=1", 
    "select cid,count(sid) from trade.buyorders  where status =? GROUP BY cid",    
    "select cid, cast (avg(qty*bid) as decimal (30, 20)) as amount from trade.buyorders  where status =? GROUP BY cid ORDER BY amount",
    "select cid, max(qty*bid) as largest_order from trade.buyorders  where status =? GROUP BY cid HAVING max(qty*bid) > 20000 ORDER BY max(qty*bid), cid DESC ",
    "select b1.oid, b1.cid, b1.sid, b1.qty, b1.tid, b2.oid, b2.cid, b2.sid, b2.qty, b2.tid from trade.buyorders b1, trade.buyorders b2 " +
    "where b1.cid = b2.cid and b1.sid = b2.sid and b1.qty < b2.qty and b1.oid < b2.oid",
  };
  protected static String[] delete = {
    //uniqkey                            
    "delete from trade.buyorders where cid=? and sid=? and tid=?", //could be batch delete, but no foreign key reference, this should work
    "delete from trade.buyorders where oid=? and (bid <? or qty>?) and tid=?",
    "delete from trade.buyorders where (status ='cancelled' or status = 'filled' or qty<?) and bid>? and tid=?",
    //non uniqkey
    "delete from trade.buyorders where cid=? and sid=?",
    "delete from trade.buyorders where oid=? and (bid <? or qty>?) ",
    "delete from trade.buyorders where (status ='cancelled' or status = 'filled' or qty<?) and bid>?",
  };

  protected static String[] statuses = {                                        
    "cancelled", "open", "filled"                                        
  };
  protected static int maxNumOfTries = 1;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  protected static ArrayList<String> partitionKeys = null;
  protected static boolean reproduce47943 = true;
  protected static boolean isTicket49116Fixed = true;
  
  @Override
  public void insert(Connection dConn, Connection gConn, int size) {
    if (testLoaderCreateRandomRow) {
      insertThruLoader(gConn, size);
      return;
    } //insert through loader of rows randomly created -- not from back_end db
    
    int[] cid = new int[size];
    if (gConn == null) getCids(dConn,cid); //populate thru loader case, gConn could be set to null
    else if (dConn !=null && rand.nextBoolean()) getCids(dConn,cid); //populate cids   
    else getCids(gConn,cid);
    
    //due to #49452, fk was taken out 
    if (hasHdfs) {
      for (int id: cid) {
        if (id == 0) {
          Log.getLogWriter().info("do not insert 0 for hdfs tests when fk was taken out due to #49452");
          return;
        }
      }
    }
    
    insert(dConn, gConn, size, cid);
  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#update(java.sql.Connection, java.sql.Connection, int)
   */
  @Override
  public void update(Connection dConn, Connection gConn, int size) {   
    int[] sid = new int[size];
    int[] newSid = new int[size];
    BigDecimal[] bid = new BigDecimal[size];
    Timestamp[] orderTime = new Timestamp[size];
    int[] cid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] bid2 = new BigDecimal[size];
    
    int[]  whichUpdate = new int[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();

    if (dConn != null) {   
      if (rand.nextInt(numGettingDataFromDerby) == 1 ) {
        if (!getDataForUpdate(dConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size)) {
          Log.getLogWriter().info("could not get the data for update, abort this operation");
          return; //not able to get the data from derby, abort this operation
        }
      } else getDataForUpdate(gConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size);
      
      
      //due to #49452, fk was taken out 
      //only sid is updated
      if (hasHdfs) {
        for (int id: sid) {
          if (id == 0) {
            Log.getLogWriter().info("do not update 0 for hdfs tests when fk was taken out due to #49452");
            return;
          }
        }
      }
      if (setCriticalHeap) {
        resetCanceledFlag();
        if (!hasTx) return; //abvoid bulk update due to #39605
      }
      
      boolean success = updateDerbyTable(dConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size, exceptionList);  //insert to derby table  
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
        success = updateDerbyTable(dConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size, exceptionList);  //insert to derby table  
      } //retry until this update will be executed.
      updateGFETable(gConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      getDataForUpdate(gConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size); //get the da
      updateGFETable(gConn, cid, sid, newSid, qty, orderTime, bid, bid2, whichUpdate, size);
    } //no verification

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#delete(java.sql.Connection, java.sql.Connection)
   */
  @Override
  public void delete(Connection dConn, Connection gConn) {
    int numOfNonUniqDelete = delete.length/2;  //how many delete statement is for non unique keys
    int whichDelete = getWhichOne(numOfNonUniqDelete, delete.length);
    if (SQLTest.syncHAForOfflineTest && (whichDelete !=1)) whichDelete = 1; 
      //avoid #39605 see #49611
    
    int size = 1; //how many delete to be completed in this delete operation
    int[] cid = new int[size]; //only delete one record
    int[] sid = new int[size];
    int[] oid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] bid = new BigDecimal[size];
    for (int i=0; i<size; i++) {
      oid[i] = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeBuyOrdersPrimary)); //random instead of uniq
      qty[i] = getQty(); //the qty
    }
    getBid(bid); //the bids
    List<SQLException> exceptionList = new ArrayList<SQLException>(); //for compare exceptions got from two sources
    int availSize;
    if (dConn!=null && rand.nextBoolean()) availSize = getDataForDelete(dConn, cid ,sid);
    else availSize = getDataForDelete(gConn, cid ,sid);
    
    if(availSize == 0 || availSize == -1) return; //did not get the results
    
    if (setCriticalHeap) {
      resetCanceledFlag();
      if (!hasTx) {
        whichDelete = 1; //avoid 39605
      }
    }
    
    //for verification both connections are needed
    if (dConn != null) {
      boolean success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, bid, qty, exceptionList);
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
        success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, oid, bid, qty, exceptionList); //retry
      }
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid, bid, qty, exceptionList);
      SQLHelper.handleMissedSQLException(exceptionList);
    } 
    else {
      deleteFromGFETable(gConn, whichDelete, cid, sid, oid, bid, qty); //w/o verification
    }

  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#query(java.sql.Connection, java.sql.Connection)
   */
  @SuppressWarnings("unchecked")
  @Override
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = select.length/2; //how many query statement is for non unique keys, non uniq query must be at the end
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not

    //self join should only work when it is partitioned on join key (colocated join) or is replicated
    if (whichQuery == 6 || whichQuery == 13) {
      if (!SQLTest.testPartitionBy) whichQuery--; 
      else {
        ArrayList<String> partitionKey = (ArrayList<String>) SQLBB.getBB().getSharedMap().get("buyordersPartition");
        if (partitionKey.size() == 1 && isTicket49116Fixed  &&
            (partitionKey.contains("cid") || partitionKey.contains("sid"))) {
          //allow self join when partitioned on cid or sid field for the join
        } else if (partitionKey.size() == 0) {
          //allow self join for replicated table
        } else whichQuery--; 
      }
    }
    
    String status = statuses[rand.nextInt(statuses.length)];
    BigDecimal bid = getPrice();
    int qty = getQty();
    int cid = getCid();  //anuy cid could be used in query string as we query records insert by this tid only
    int sid = getSid();  //anuy cid could be used in query string
    int tid = getMyTid();
    Timestamp orderTime = getRandTime();
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (dConn!=null) {
      try {
        discRS = query(dConn, whichQuery, status, bid, qty, cid, sid, orderTime, tid);
        if (discRS == null) {
          Log.getLogWriter().info("could not get the derby result set after retry, abort this query");
          Log.getLogWriter().info("Could not finish the op in derby, will abort this operation in derby");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) 
            ; //do nothing and expect gfxd fail with the same reason due to alter table
          else return;
        }
      } catch (SQLException se) {
        SQLHelper.handleDerbySQLException(se, exceptionList);
      }
      try {
        gfeRS = query (gConn, whichQuery, status, bid, qty, cid, sid, orderTime, tid); 
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
      if (discRS == null || gfeRS == null) {
        SQLHelper.closeResultSet(gfeRS, gConn); //for single hop, result set needs to be closed instead of fully consumed
        return;
      }
      
      boolean success = false;

      try {
        if (whichQuery == 5) {
          success =  ResultSetHelper.compareSortedResultSets(discRS, gfeRS);
          if (!reproduce47943) return;

          boolean logOrderByResult = true;
          if (logOrderByResult) {
            try {
              PreparedStatement sps = gConn.prepareStatement(select[5]);
              sps.setString(1, status);
              sps.setInt(2, tid);
              gfeRS = sps.executeQuery();             
            } catch (SQLException se) {
              if (!SQLHelper.checkGFXDException(gConn, se)) 
                Log.getLogWriter().info("Not able to dump result due to node failure");
            }
            List <Struct> gfxdList = ResultSetHelper.asList(gfeRS, false);
            if (gfxdList != null) Log.getLogWriter().info( "gemfirexd returns " + ResultSetHelper.listToString(gfxdList) + " QUERY : " + select[5] );
            else Log.getLogWriter().info("Not able to dump result due to node failure");
          }
          SQLHelper.closeResultSet(gfeRS, gConn); //for single hop, result set needs to be closed instead of fully consumed
          
          String offsetClause = " OFFSET 3 ROWS FETCH NEXT 2 ROWS ONLY";
          String sql = select[5];
          if(!SQLPrms.isSnappyMode()) {
            sql = sql + offsetClause;
          }
          
          try {
            String derbysql =sql;
            if(SQLPrms.isSnappyMode())
              derbysql = sql + "FETCH FIRST 5 ROWS ONLY";
            Log.getLogWriter().info("Derby - querying trade.buyorders QUERY: " + derbysql );
            PreparedStatement dps = dConn.prepareStatement(derbysql);
            dps.setString(1, status);
            dps.setInt(2, tid);
            discRS = dps.executeQuery();
          } catch (SQLException se) {
            if (!SQLHelper.checkDerbyException(dConn, se)) {
              Log.getLogWriter().info("Not able to get results due to node failure");
              return;
            } else {
              SQLHelper.handleSQLException(se);
            }         
          } 
          
          try {
            if(SQLPrms.isSnappyMode())
              sql = sql + " LIMIT 5";
            Log.getLogWriter().info("gemfirexd - querying trade.buyorders QUERY: " + sql );
            PreparedStatement sps = gConn.prepareStatement(sql);
            sps.setString(1, status);
            sps.setInt(2, tid);
            gfeRS = sps.executeQuery();
          } catch (SQLException se) {
            if (!SQLHelper.checkGFXDException(gConn, se)) {
              Log.getLogWriter().info("Not able to get gfxd results due to node failure");
              return;
            } else SQLHelper.handleSQLException(se); //do not expect other exceptions.
          }
          
          List <Struct> derbyList = ResultSetHelper.asList(discRS, true);
          List <Struct> gfxdList = ResultSetHelper.asList(gfeRS, false);
          if (derbyList != null && gfxdList != null)
            ResultSetHelper.compareResultSets(derbyList, gfxdList);
        } else success = ResultSetHelper.compareResultSets(discRS, gfeRS); 
        
        
        if (!success) {
          Log.getLogWriter().info("Not able to compare results due to derby server error");
        } //not able to compare results due to derby server error   
      } catch (TestException te) {
        if ((te.getMessage().contains("elements were missing") 
            || te.getMessage().contains("unexpected elements")) 
            && whichQuery>=2 && whichQuery<=5) {
          
          dumpNoAggregateRs.set(true);
          //This is best effort to dump aggregate results
          //either db dumped results should help to analyze the data mismatch issue
          
          //dump derby result
          try {
            discRS = query(dConn, whichQuery, status, bid, qty, cid, sid, orderTime, tid);
            List derbyList = ResultSetHelper.asList(discRS, true);
            if (derbyList != null)
              Log.getLogWriter().info("dump non-aggregate result from derby due to data mismatch:" +
                ResultSetHelper.listToString(derbyList));
            else Log.getLogWriter().info("not able to get derby results for dump");
          } catch (SQLException se) {
            Log.getLogWriter().info("not able to get derby results for dump");
          }
          
          //dump gfxd result
          try {
            gfeRS = query(gConn, whichQuery, status, bid, qty, cid, sid, orderTime, tid);
            List gfxdList = ResultSetHelper.asList(gfeRS, false);
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
            gfeRS = query(gConn, whichQuery, status, bid, qty, cid, sid,
                orderTime, tid);
            StringBuilder plan = new StringBuilder();
            while (gfeRS.next()) {
              plan.append(gfeRS.getString(1)).append('\n');
            }
            if (plan.length() > 0) {
              Log.getLogWriter().info(
                  "dump query plan in gfxd due to data mismatch:"
                      + plan);
            }
            else {
              Log.getLogWriter().info(
                  "no query plan in gfxd found");
            }
          } catch (SQLException se) {
            Log.getLogWriter().info(
                "not able to get gfxd queryplan due to exception "
                    + se.getSQLState(), se);
          }
          
          dumpQueryPlanRs.set(false);          
        } 
        
        throw te; 
      } finally {
        SQLHelper.closeResultSet(gfeRS, gConn); //for single hop, result set needs to be closed instead of fully consumed
      }

    }// we can verify resultSet
    else {
      try {
        gfeRS = query (gConn,  whichQuery, status, bid, qty, cid, sid, orderTime, tid);  
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
      
      SQLHelper.closeResultSet(gfeRS, gConn); //for single hop, result set needs to be closed instead of fully consumed
    }
  }
  
  //populate the table
  @Override
  public void populate (Connection dConn, Connection gConn) {
    if (testLoaderCreateRandomRow) {
      insertThruLoader(gConn);
      return;
    }
    
    int initSize = rand.nextInt(10)+1; //how many time a customer could insert buy orders
    int numCid;
    for (int i=0; i<initSize; i++) {
      numCid = rand.nextInt(30); 
      if (dConn!=null && SQLTest.testPartitionBy && SQLTest.populateThruLoader) {
        for (int j=0; j<numCid; j++) {
          insert(dConn, null, 1); //for loader test
          commit(dConn);
        }
      } else {
        for (int j=0; j<numCid; j++) {
          if (setCriticalHeap) resetCanceledFlag();
          insert(dConn, gConn, 1);
          if (dConn != null) commit(dConn);
          commit(gConn);
        }
      }
    }
  }
  
  protected void insertThruLoader(Connection gConn) {
    int size = rand.nextInt(300)+1; 
    insertThruLoader(gConn, size);
  }
  
  protected void insertThruLoader(Connection gConn, int size) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.tradeBuyOrdersPrimary, size);
    int startPoint = key == size? 1: key - size + 1; 
    //adding some might be loaded by other thread  
    
    if (rand.nextBoolean()) loadEachRow(gConn, key, startPoint);
    else loadMultiRows(gConn, key, startPoint);
  }
  
  protected void loadEachRow(Connection gConn, int key, int startPoint) {
    try {
      String sql = "select * from trade.buyorders where oid = ?";
      PreparedStatement pStmt = gConn.prepareStatement(sql);
      for (int i=startPoint; i<=key; i++) {
        Log.getLogWriter().info("gemfirexd - querying trade.buyorders with OID:" + i + " QUERY: " + sql);
        pStmt.setInt(1, i);
        ResultSet rs = pStmt.executeQuery();
        if (rs.next())  ; //should have only one result, no need to close the rs
        else throw new TestException ( "loader does not load the data for oid: " + i); //TODO need to be commented out once #42171 is fixed
      }       
    } catch (SQLException se) {
      if (se.getSQLState().equals("23503")) {
        Log.getLogWriter().info("loader could not load the data due to #42171"); //TODO need to change once #42171 is fixed
      }
      else SQLHelper.handleSQLException(se);
    }
  }
  
  protected void loadMultiRows(Connection gConn, int key, int startPoint) {
    String sql = "select * from trade.buyorders where oid IN (";
    for (int i=startPoint; i<key; i++) {
      sql += i + ", ";
    }
    sql += key + ")";
    Log.getLogWriter().info("gemfirexd - querying trade.buyorders QUERY: " + sql);
    try {
      Statement stmt = gConn.createStatement();
      ResultSet rs = stmt.executeQuery(sql);
      ResultSetHelper.asList(rs, false);
    } catch (SQLException se) {
      if (se.getSQLState().equals("23503")) ; //TODO need to change once #42171 is fixed
      else SQLHelper.handleSQLException(se);
    }
  }
  
//*** insert ***/
  public void insert(Connection dConn, Connection gConn, int size, int[] cids) {
    insert(dConn, gConn, size, cids, false);
    
  }
  //*** insert ***/
  public void insert(Connection dConn, Connection gConn, int size, int[] cids, boolean isPut) {
    //add batch insert for multiple rows
    int chance = 10;
    int maxSize = 10;
    boolean usebatch = false;
    if (cids[0]!=0 && rand.nextInt(chance) == 1 && dConn!=null && !alterTableDropColumn
        && !testSecurity && !setCriticalHeap /*&& !isOfflineTest */) { 
      //batch insert could hit cancellation exception and X0Z09 (to avoid #39605)
      //still allow batch insert in offline test to reproduce #49563
      usebatch = true;
      size = rand.nextInt(maxSize) + 1;
    }
    
    if (SQLTest.syncHAForOfflineTest) {
      usebatch = false;
      size = 1;
    } //avoid #39605 due to X0Z09
    
    //usebatch = false;
    //size = 1;
    
    int[] cid = new int[size];
    for (int i=0; i<size; i++){
      cid[i] = cids[0];
    }
    int[] oid = new int[size];
    int[] sid = new int[size];
    int[] qty = new int[size];
    String status = "open";
    Timestamp[] time = new Timestamp[size];
    BigDecimal[] bid = new BigDecimal[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (dConn != null) {
      //get the data
      if (gConn==null || rand.nextInt(numGettingDataFromDerby) == 1) 
        getDataForInsert(dConn, oid, cid, sid, qty, time, bid, size); //populate thru loader, gConn could be null during insert
      else  
        getDataForInsert(gConn, oid, cid, sid, qty, time, bid, size);
      
      //due to #49452, fk was taken out 
      if (hasHdfs) {
        for (int id: sid) {
          if (id == 0) {
            Log.getLogWriter().info("do not insert 0 for hdfs tests when fk was taken out due to #49452");
            return;
          }
        }
      }
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success;
      if (usebatch) {
        for (int id: sid) {
          if (id == 0) {
            Log.getLogWriter().info("could not get enough valid sid for " +
            		"batching insert"); //avoid #39065 in the test
            return;
          }
        } 
        success = insertToDerbyTableUsingBatch(dConn, oid, cid, sid, qty, status, time, bid, size, exceptionList); 
      }
      else success = insertToDerbyTable(dConn, oid, cid, sid, qty, status, time, bid, size, exceptionList);  //insert to derby table  
      if (!success) {
        Log.getLogWriter().info("Could not finish the op in derby, will abort this operation in derby");
        rollback(dConn); 
        if (alterTableDropColumn && SQLTest.alterTableException.get() != null
            && (Boolean)SQLTest.alterTableException.get() == true) 
          ; //do nothing, expect gfxd fail with the same reason due to alter table
        else return; 
      }
      if (gConn == null) {
        Log.getLogWriter().info("connection is null, must be a load test");
        return;
      }
      try {
        if (usebatch) insertToGfxdTableUsingBatch(gConn, oid, cid, sid, qty, status, time, bid, size, exceptionList, isPut); 
        else insertToGFETable(gConn, oid, cid, sid, qty, status, time, bid, size, exceptionList, isPut); 
      } catch (TestException te) {
        if (te.getMessage().contains("Execute SQL statement failed with: 23505")
            && isHATest && SQLTest.isEdge) {
          //checkTicket49605(dConn, gConn, "buyorders");
          try {
            checkTicket49605(dConn, gConn, "buyorders");
          } catch (TestException e) {
            Log.getLogWriter().info("insert failed due to #49605", e);
            //deleteRow(dConn, gConn, "buyorders", oid[0], -1, null, null);
            //use put to work around #43751/#49605 issue of region map missing insert
            Log.getLogWriter().info("retry this using put to work around #49605");
            if (usebatch) insertToGfxdTableUsingBatch(gConn, oid, cid, sid, qty, status, time, bid, size, exceptionList, true); 
            else insertToGFETable(gConn, oid, cid, sid, qty, status, time, bid, size, exceptionList, true); 
            
            checkTicket49605(dConn, gConn, "buyorders");
          }
        } else throw te;
      }
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      getDataForInsert(gConn, oid, cid, sid, qty, time, bid, size); //get the data
      //due to #49452, fk was taken out 
      if (hasHdfs) {
        for (int id: sid) {
          if (id == 0) {
            Log.getLogWriter().info("do not insert 0 for hdfs tests when fk was taken out due to #49452");
            return;
          }
        }
      }
      insertToGFETable(gConn, oid, cid, sid, qty, status, time, bid, size, isPut);
    } //no verification
  }
  
  //populate arrays for insert
  protected void getDataForInsert(Connection regConn, int[] oid, int[] cid, int[] sid, int[] qty, Timestamp[] time, 
      BigDecimal[] bid, int size) {
    Connection conn = getAuthConn(regConn);
    getOids(oid, size); //populate oids    
    for (int i=0; i<size; i++) {
      sid[i] = getSid(conn);
      qty[i] = getQty(initMaxQty/2);
      time[i] = new Timestamp(System.currentTimeMillis());
      bid[i] =   new BigDecimal (Double.toString((rand.nextInt(10000)+1) * .01));  //random bid price      
    } 
  }
  
  //populate int[] of oids
  protected void getOids(int[] oid, int size) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.tradeBuyOrdersPrimary, size);
    for (int i=0; i<size; i++) {
      oid[i]= (key-size+1)+i;
    }
  }  
  
  //insert intogemfirexd
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] bid, int size, List<SQLException> exceptions, boolean isPut) {
    
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
    for (int i=0 ; i<size ; i++) {
      try {
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], bid[i], tid, isPut);
        if (count != (verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd insert to buyorders has different row count from that of derby " +
            "derby inserted " + (verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      } catch (SQLException se) {
        /* does not expect 0A000 with put when table without unique key column.
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test");  
        } else  
        */ 
        SQLHelper.handleGFGFXDException(se, exceptions);
      }
    } 
  }
  
  //insert intogemfirexd/gfe w/o verification
  protected void insertToGFETable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] bid, int size, boolean isPut)  {
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);

    
    
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	} else ; //need to find out why stmt is not obtained
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
    
    for (int i=0 ; i<size ; i++) {
      try {
        insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], bid[i], tid, isPut);
      } catch (SQLException se) {
        if (se.getSQLState().equals("23503"))  
          Log.getLogWriter().info("detected the foreign key constraint violation, continuing test");
        else if (se.getSQLState().equals("42500") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else if (alterTableDropColumn && se.getSQLState().equals("42802")) {
          Log.getLogWriter().info("Got expected column not found exception in insert, continuing test");
        } else if (se.getSQLState().equals("23505")
            && isHATest && SQLTest.isEdge) {
          Log.getLogWriter().info("detected pk constraint violation during insert -- relaxing due to #43571, continuing test");
        } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } else if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else   
          SQLHelper.handleSQLException(se); //handle the exception
      }
    }    
  }

  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal bid, int tid) throws SQLException {
    return insertToTable(stmt, oid, cid, sid, qty, status, time, bid, tid, false);
  }
  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal bid, int tid, boolean isPut) throws SQLException {
        
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " +  " ";
    
    
    Log.getLogWriter().info( database + (isPut ? "putting" : "inserting" ) + " into trade.buyorders with OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
        ",TIME:"+ time + ",BID:" + bid + ",TID:" + tid);
    
    stmt.setInt(1, oid);
    stmt.setInt(2, cid);
    stmt.setInt(3, sid);
    stmt.setInt(4, qty);
    stmt.setBigDecimal(5, bid);
    if (testworkaroundFor51519)  stmt.setTimestamp(6, time, getCal());
    else stmt.setTimestamp(6, time);
    stmt.setString(7, status);       
    stmt.setInt(8, tid);
    int rowCount = stmt.executeUpdate();

    Log.getLogWriter().info( database + (isPut ? "put " : "inserted " ) + rowCount + " rows in trade.buyorders with OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
        ",TIME:"+ time + ",BID:" + bid + ",TID:" + tid);
    
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 

    if ( database.contains("gemfirexd") && isPut) {
      if (! SQLTest.ticket49794fixed) {
        insertToBuyordersFulldataset(stmt.getConnection() , oid, cid, sid, qty, bid, time, status, tid);
        }      
      Log.getLogWriter().info( database +  (isPut ? "putting" : "inserting" ) + " into trade.buyorders with OID:" + oid +
          ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
          ",TIME:"+ time + ",BID:" + bid + ",TID:" + tid);    
      
     rowCount = stmt.executeUpdate();
     
     Log.getLogWriter().info( database + (isPut ? "put" : "inserted" ) + rowCount + " rows in trade.buyorders with OID:" + oid +
         ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
         ",TIME:"+ time + ",BID:" + bid + ",TID:" + tid);   
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
 }    
    return rowCount;
  }
  
  
  
  protected void insertToBuyordersFulldataset (Connection conn, int oid, int cid, int sid, int qty, BigDecimal bid, Timestamp time, String status , int tid){

    //manually update fulldataset table for above entry.
     try{
      
       Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence deleting  the  row  from TRADE.BUYORDERS_FULLDATASET with data OID:" +  oid );
       conn.createStatement().execute("DELETE FROM TRADE.BUYORDERS_FULLDATASET  WHERE OID:"  + oid);      

      PreparedStatement preparedInsertStmt = conn.prepareStatement("insert into trade.buyorders_fulldataset values (?,?,?,?,?,?,?,?)");          
      
      preparedInsertStmt.setInt(1, oid);
      preparedInsertStmt.setInt(2, cid);
      preparedInsertStmt.setInt(3, sid);
      preparedInsertStmt.setInt(4, qty);
      preparedInsertStmt.setBigDecimal(5, bid);
      if (testworkaroundFor51519)  preparedInsertStmt.setTimestamp(6, time, getCal());
      else preparedInsertStmt.setTimestamp(6, time);
      preparedInsertStmt.setString(7, status);       
      preparedInsertStmt.setInt(8, tid);
     
      Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence inserting  the  row  into TRADE.BUYORDERS_FULLDATASET OID:" +  oid +  ",CID:" + cid  + ",SID:" +  sid + ",QTY:" + qty + ",BID:"  + bid + ",TID:" + tid);
      preparedInsertStmt.executeUpdate();
     } catch (SQLException se) {
       Log.getLogWriter().info("Error while updating TRADE.BUYORDES_FULLDATASET table. It may cause Data inconsistency " + se.getMessage() ); 
     }
  }
  protected void addBatchInsert(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal bid, int tid) throws SQLException {
    addBatchInsert(stmt, oid, cid, sid, qty, status, time, bid, tid, false);
  }
  protected void addBatchInsert(PreparedStatement stmt, int oid, int cid, int sid, int qty,
      String status, Timestamp time, BigDecimal bid, int tid, boolean isPut) throws SQLException {
    
    Log.getLogWriter().info( (SQLHelper.isDerbyConn(stmt.getConnection())? "Derby - " :"gemfirexd - "  ) +  (isPut ? "putting " : "inserting ") + " into trade.buyorders with data OID:" + oid +
        ",CID:"+ cid + ",SID:" + sid + ",QTY:" + qty + ",STATUS:" + status +
        ",TIME:"+ time + ",BID:" + bid + ",TID:" + tid);
    
    stmt.setInt(1, oid);
    stmt.setInt(2, cid);
    stmt.setInt(3, sid);
    stmt.setInt(4, qty);
    stmt.setBigDecimal(5, bid);
    if (testworkaroundFor51519)  stmt.setTimestamp(6, time, getCal());
    else stmt.setTimestamp(6, time);
    stmt.setString(7, status);       
    stmt.setInt(8, tid);
    stmt.addBatch();
  }
  
  protected void insertToGfxdTableUsingBatch(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] bid, int size, List<SQLException> exceptions, boolean isPut) {
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
    
    if (stmt == null) {
      throw new TestException("Does not expect stmt to be null, need to check logs to confirm this issue.");
    }
    
    int[] counts = null;
    for (int i=0 ; i<size ; i++) {
      try {
        
        addBatchInsert(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], bid[i], tid, isPut);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        SQLHelper.handleGFGFXDException(se, exceptions); //should not see any exceptions here        
      }   
    }
    
    try {
      counts = stmt.executeBatch(); 
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      SQLHelper.handleGFGFXDException(se, exceptions); //possibly node failure exceptions if setTx  
      
    }   
    
    if (counts == null) {
      if (setCriticalHeap) {
        boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
        if (getCanceled != null && getCanceled[0] == true) {
          Log.getLogWriter().info("insert batching into gfxd failed due to low memory exception");
          return;
        }
        else throw new TestException("Does not expect execute batch to fail, " +
        "need to check logs.");
      } else if (SQLTest.setTx && isHATest) {
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure != null && getNodeFailure[0]) {
          Log.getLogWriter().info("insert batching into gfxd failed due to node failure exception");
          return;
        }
        else throw new TestException("Does not expect execute batch to fail, " +
        "need to check logs.");
      }
      else throw new TestException("Does not expect execute batch to fail, " +
      		"need to check logs to confirm this issue.");
    }
    for (int i =0; i<counts.length; i++) {  
      if (counts[i] != -3) {       
        Log.getLogWriter().info("gemfirexd - inserted" + counts[i] + " rows in trade.buyorders OID:" + oid[i] +
                    ",CID:"+ cid[i] + ",SID:" + sid[i] + ",QTY:" + qty[i] + ",STATUS:" + status +
                    ",TIME:"+ time[i] + ",BID:" + bid[i] + ",TID:" + tid);
        if (counts[i] != verifyRowCount.get(tid+"_insert"+i)) {
          String str = "gemfirexd insert to buyorders has different row count from that of derby " +
            "derby inserted " + verifyRowCount.get(tid+"_insert"+i) +
            " but gfxd inserted " + counts[i];
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
        
      } else {
        Log.getLogWriter().warning("gfxd failed to update in batch update");
      }
    } 
    
  }
  
  //insert into Derby using batch
  protected boolean insertToDerbyTable(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] bid, int size, List<SQLException> exceptions)  {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    for (int i=0 ; i<size ; i++) {
      try {
        verifyRowCount.put(tid+"_insert"+i, 0);
        count = insertToTable(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], bid[i], tid);
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        Log.getLogWriter().info("Derby inserts " + verifyRowCount.get(tid+"_insert"+i) + " rows");
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se))
          return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }    
    }
    return true;
  }
  
  //insert into Derby using batch
  protected boolean insertToDerbyTableUsingBatch(Connection conn, int[] oid, int[] cid, int[] sid, int[] qty,
      String status, Timestamp[] time, BigDecimal[] bid, int size, List<SQLException> exceptions)  {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();        
    int[] counts = null;
    for (int i=0 ; i<size ; i++) {
      try {
        addBatchInsert(stmt, oid[i], cid[i], sid[i], qty[i], status, time[i], bid[i], tid);
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se))
          return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }   
    }
    
    try {
      counts = stmt.executeBatch(); 
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
      else SQLHelper.handleDerbySQLException(se, exceptions); //not expect any exception on insert      
    }   
    
    //TODO to be revisited once #43754 is addressed.
    if (counts == null && isOfflineTest && !SQLTest.syncHAForOfflineTest) {
      Log.getLogWriter().info("Hit bug #43754, and will be ignored");
      return false;
    }
    
    if (counts == null) {
      throw new TestException("Does not expect failure while inserting into derby using batch, " +
      		"need to check logs to find out cause for this");
    }
 
    for (int i =0; i<counts.length; i++) {  
      if (counts[i] != -3) {
        verifyRowCount.put(tid+"_insert"+i, 0);
        verifyRowCount.put(tid+"_insert"+i, new Integer(counts[i]));
        Log.getLogWriter().info("Derby - inserted " + counts[i] + " rowS in trade.buyorders OID:" + oid[i] +
        ",CID:"+ cid[i] + ",SID:" + sid[i] + ",QTY:" + qty[i] + ",STATUS:" + status +
        ",TIME:"+ time[i] + ",BID:" + bid[i] + ",TID:" + tid);
      } else throw new TestException("derby failed to insert rows in batch insert");
    }


    return true;
  }

  //populate the status array
  protected void getStatus(String[] status) {
    for (int i=0; i<status.length; i++) {
      status[i]= statuses[rand.nextInt(statuses.length)];
    }
  }
  
  //return a Date used in query
  protected Timestamp getRandTime() {
    return new Timestamp(System.currentTimeMillis()-rand.nextInt(10*60*1000)); //randomly choose in the last 10 minutes
  }
  
  //populate the bid price array
  protected void getBid(BigDecimal[] bid) {
    for (int i=0; i<bid.length; i++) {
      bid[i]= getPrice();
    }
  }
  
  //--- update related ---//
  protected boolean getDataForUpdate(Connection regConn, int[] cid, int[] sid, int[] newSid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] ask, BigDecimal[] ask2, int[] whichUpdate, int size) {
    Connection conn = getAuthConn(regConn);
    int numOfNonUniq = update.length/2;
    boolean success = getDataFromResult(conn, cid, sid); //could not get the data
    if (!success) return false;
    for (int i=0; i<size; i++) {
      newSid[i] = getSid(conn);  //the newSid could be any Sid inserted by this thread
      qty[i] = getQty();
      orderTime[i] = getRandTime();
      ask[i] = getPrice();
      ask2[i] = getPrice();
      whichUpdate[i] = getWhichOne(numOfNonUniq, update.length);
    }
    
    return true;
  }
  
  protected boolean getDataFromResult(Connection regConn, int[] cid, int[] sid) {
    Connection conn = getAuthConn(regConn);
    int tid = getMyTid();  //for test unique key case
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
    boolean[] success = new boolean[1];
    int num = 20; //used to randomly return the cid, sid pair
    int n = rand.nextInt(num);
    ResultSet rs = null;

    try {  
      int whichQuery = 0;
      rs = getQuery(conn, whichQuery, null, null, 0, 0, 0, null, tid, success); //first query select *
      if (!success[0] || rs == null) return false; //we won't retry if could not get data from resultSet
      
      int i=0;
      int temp = 0;
      while (rs.next() && i<size) {
        if (temp ==0 ) {
          cid[i] = rs.getInt("CID");
          sid[i] = rs.getInt("SID");
        } else if (n>=temp) {
          cid[i] = rs.getInt("CID");
          sid[i] = rs.getInt("SID");
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
  
  protected boolean updateDerbyTable (Connection conn, int[] cid, int[] sid, int[] newSid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] bid, BigDecimal[] bid2, int[] whichUpdate, int size, List<SQLException> exList) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    boolean[] unsupported = new boolean[1];
    for (int i=0 ; i<size ; i++) {
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
        if (stmt!=null) {
          verifyRowCount.put(tid+"_update"+i, 0);
          count = updateTable(stmt, cid[i], sid[i], newSid[i], qty[i], orderTime[i], bid[i], bid2[i], tid, whichUpdate[i] );
          verifyRowCount.put(tid+"_update"+i, new Integer(count));
          Log.getLogWriter().info("Derby updates " + count + " rows");
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
      partitionKeys= (ArrayList<String>)partitionMap.get("buyordersPartition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_buyordersPartition");
    }
    Log.getLogWriter().info("partition keys are " + partitionKeys);
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys, boolean[] unsupported){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      //  "update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' and tid = ? ",  //for trigger test it could be a batch update
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      //"update trade.buyorders set status = 'cancelled' where (ordertime >? or sid=?) and status = 'open' and tid =? ",  //batch operation
      if (partitionKeys.contains("status")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      //"update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? ",
      if (partitionKeys.contains("bid")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      //"update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open' and tid =? ",
      if (partitionKeys.contains("bid") || partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 4: 
      //"update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open' and tid =? ",
      if (partitionKeys.contains("sid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 5: 
      // "update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' ",  //for trigger test it could be a batch update
      if (partitionKeys.contains("status")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 6: 
      // "update trade.buyorders set status = 'cancelled' where (ordertime <? or sid=?) and status = 'open'  ",  //batch operation
      if (partitionKeys.contains("status")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 7: 
      //"update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? ",
      if (partitionKeys.contains("bid")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 8: 
      //"update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open'  " ,  
      if (partitionKeys.contains("bid") || partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 9: 
      //"update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open'  "
      if (partitionKeys.contains("sid")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
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
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] newSid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] bid, BigDecimal[] bid2, int[] whichUpdate, int size,
      List<SQLException> exList) {
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
          count = updateTable(stmt, cid[i], sid[i], newSid[i], qty[i], orderTime[i], bid[i], bid2[i], tid, whichUpdate[i] );
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
  protected void updateGFETable (Connection conn, int[] cid, int[] sid, int[] newSid, int[] qty, 
      Timestamp[] orderTime, BigDecimal[] bid, BigDecimal[] bid2, int[] whichUpdate, int size) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    for (int i=0 ; i<size ; i++) {
      if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate[i], null);
      else stmt = getStmt(conn, update[whichUpdate[i]]); //use only this after bug#39913 is fixed
      
      try {
        if (stmt!=null)
          updateTable(stmt, cid[i], sid[i], newSid[i], qty[i], orderTime[i], bid[i], bid2[i], tid, whichUpdate[i] );
      } catch (SQLException se) {
        if (se.getSQLState().equals("23503") && (whichUpdate[i] == 4 || whichUpdate[i] == 9 )) 
          //check fk violation happens when updating on foreign keys or not
          Log.getLogWriter().info("detected foreign key constraint violation during update, continuing test");
        else if (se.getSQLState().equals("42502") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else if (alterTableDropColumn && (se.getSQLState().equals("42X14") || se.getSQLState().equals("42X04"))) {
          //42X04 is possible when column in where clause is dropped
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } else
          SQLHelper.handleSQLException(se);
      }
    }    
  }
  
  protected int updateTable (PreparedStatement stmt, int cid, int sid, int newSid, int qty, 
      Timestamp orderTime, BigDecimal bid, BigDecimal bid2, int tid, int whichUpdate) throws SQLException {
    int rowCount =0;
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";    
    String query = " QUERY: " + update[whichUpdate];
    switch (whichUpdate) {
    case 0: 
      //"update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' and tid = ? ",  //for trigger test it could be a batch update
      Log.getLogWriter().info(database + "updating trade.buyorders with STATUS:filled where SID:" + sid  + ",BID:" + bid + ",STATUS:open,TID:" + tid + query );
      stmt.setInt(1, sid);
      stmt.setBigDecimal(2, bid);      
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with STATUS:filled where SID:" + sid  + ",BID:" + bid + ",STATUS:open,TID:" + tid + query );
      break;
    case 1:     
      //"update trade.buyorders set status = 'cancelled' where ordertime >? or sid=? and status = 'open' and tid =? ",  //batch operation
      Log.getLogWriter().info(database + "updating trade.buyorders with STATUS:cancelled where ORDERTIME:" + orderTime + ",SID:" + sid + ",TID:" + tid + query);
      if (testworkaroundFor51519)  stmt.setTimestamp(1, orderTime, getCal());
      else stmt.setTimestamp(1, orderTime);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();    
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders STATUS:cancelled where ORDERTIME:" + orderTime + ",SID:" + sid + ",TID:" + tid + query);
      break;
    case 2: 
      //"update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? and tid =? ",
      Log.getLogWriter().info(database + "updating trade.buyorders with BID:" + bid + " where CID:" + cid 
          + ",SID:" + sid + ",STATUS:open,QTY:" + qty + ",TID:" + tid + query);
      stmt.setBigDecimal(1, bid);
      stmt.setInt(2, cid);
      stmt.setInt(3, sid);
      stmt.setInt(4, qty);
      stmt.setInt(5, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with BID:" + bid + " where CID:" + cid 
          + ",SID:" + sid + ",STATUS:open,QTY:" + qty + ",TID:" + tid + query);
      
      break;
    case 3: 
      //"update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open' and tid =? ",
      Log.getLogWriter().info(database + "updating trade.buyorders with BID:" + bid + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",BID:" + bid2 + ",STATUS:open,TID:" + tid + query);
      stmt.setBigDecimal(1, bid);
      stmt.setInt(2, qty);
      stmt.setInt(3, cid);
      stmt.setInt(4, sid);
      stmt.setBigDecimal(5, bid2);
      stmt.setInt(6, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with BID:" + bid + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",BID:" + bid2 + ",STATUS:open,TID:" + tid + query);
      break;
    case 4:
      //"update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open' and tid =? ",
      Log.getLogWriter().info(database + "updating trade.buyorders with SID:" + newSid  
          + " where CID:" + cid  + ",SID:" + sid + ",STATUS:open,TID: " + tid + query);
      stmt.setInt(1, newSid);
      stmt.setInt(2,cid);
      stmt.setInt(3,sid);
      stmt.setInt(4,tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with SID:" + newSid  
          + " where CID:" + cid  + ",SID:" + sid + ",STATUS:open,TID: " + tid + query);
      break;
    case 5:
      //"update trade.buyorders set status = 'filled'  where sid = ? and bid>? and status = 'open' ",  //for trigger test it could be a batch update
      Log.getLogWriter().info(database + "updating trade.buyorders with STATUS:filled where SID:" + sid  + ",BID:" + bid + ",STATUS:open" + query);
      stmt.setInt(1, sid);
      stmt.setBigDecimal(2,bid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with STATUS:filled where SID:" + sid  + ",BID:" + bid + ",STATUS:open" + query);
      break;
    case 6: 
      //"update trade.buyorders set status = 'cancelled' where ordertime <? or sid=? and status = 'open'  ",  //batch operation
      Log.getLogWriter().info(database + "updating trade.buyorders with STATUS:cancelled where ORDERTIME:" + orderTime+ ",SID:" + sid + ",STATUS:open" + query);
      if (testworkaroundFor51519)  stmt.setTimestamp(1, orderTime, getCal());
      else stmt.setTimestamp(1, orderTime);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders  with STATUS:cancelled where ORDERTIME:" + orderTime+ ",SID:" + sid + ",STATUS:open" + query);
      break;
    case 7: 
      //"update trade.buyorders set bid = ? where cid = ? and sid= ? and status = 'open' and qty >? ",
      Log.getLogWriter().info(database + "updating on trade.buyorders with BID:" + bid + " where CID:" + cid 
          + ",SID:" + sid + ",STATUS:open,QTY:" + qty + query);
      stmt.setBigDecimal(1, bid);
      stmt.setInt(2, cid);
      stmt.setInt(3, sid);
      stmt.setInt(4, qty);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with BID:" + bid + " where CID:" + cid 
          + ",SID:" + sid + ",STATUS:open,QTY:" + qty + query);
      break;
    case 8: 
      //"update trade.buyorders set bid = ? , qty=? where cid = ? and sid= ? and bid <? and status = 'open'  ",  
      Log.getLogWriter().info(database + "updating trade.buyorders with BID:" + bid + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",BID:" + bid2 + ",STATUS:open" + query);
      stmt.setBigDecimal(1, bid);
      stmt.setInt(2, qty);
      stmt.setInt(3, cid);
      stmt.setInt(4, sid);
      stmt.setBigDecimal(5, bid2);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders with BID:" + bid + ",QTY:" + qty + 
          " where CID:" + cid  + ",SID:" + sid + ",BID:" + bid2 + ",STATUS:open" + query);
      break;
    case 9:
      //"update trade.buyorders set sid = ? where cid = ? and sid= ? and status = 'open'",
      Log.getLogWriter().info(database + "updating trade.buyorders with SID:" + newSid + " where CID:" + cid  + ",SID:" + sid + ",STATUS:open"  + query);
      stmt.setInt(1, newSid);
      stmt.setInt(2,cid);
      stmt.setInt(3,sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.buyorders  with SID:" + newSid + " where CID:" + cid  + ",SID:" + sid + ",STATUS:open"  + query);
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
  public static ResultSet getQuery(Connection conn, int whichQuery, String status, 
      BigDecimal bid, int qty, int cid, int sid, Timestamp orderTime, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    try {
      rs = getQuery(conn, whichQuery, status, bid, qty, cid, sid, orderTime, tid, success);
      int count = 0;
      while (!success[0]) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
          return null; 
        }
        count++;   
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));    
        rs = getQuery(conn, whichQuery, status, bid, qty, cid, sid, orderTime, tid, success);
      } //retry 
    } catch (SQLException se) {
      if (!SQLHelper.isAlterTableException(conn, se)) SQLHelper.handleSQLException(se);
      //allow alter table related exceptions.
    }
    return rs;
  } // whichQuery, status, bid, qty, cid, sid, orderTime, tid
  
  protected static ResultSet query(Connection conn, int whichQuery, String status, 
      BigDecimal bid, int qty, int cid, int sid, Timestamp orderTime, int tid) 
      throws SQLException {
    boolean[] success = new boolean[1];
    ResultSet rs = getQuery(conn, whichQuery, status, bid, qty, cid, sid, orderTime, tid, success);
    int count = 0;
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
        return null; 
      }
      count++;   
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));    
      rs = getQuery(conn, whichQuery, status, bid, qty, cid, sid, orderTime, tid, success);
    } //retry 
    return rs;
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, String status, 
      BigDecimal bid, int qty, int cid, int sid, Timestamp orderTime, int tid, 
      boolean[] success) throws SQLException {
    Boolean dumpResult = (Boolean) dumpNoAggregateRs.get();
    final Boolean dumpQueryPlan = (Boolean) dumpQueryPlanRs.get();
    String sql = dumpResult != null && dumpResult.booleanValue() ? selectwoaggreate[whichQuery]
        : (dumpQueryPlan != null && dumpQueryPlan.booleanValue() ? "explain "
            + select[whichQuery] : select[whichQuery]);
    return getQuery(conn, whichQuery, status, bid, qty, cid, sid, orderTime, tid, success, sql);
  }
  protected static ResultSet getQuery(Connection conn, int whichQuery, String status, 
      BigDecimal bid, int qty, int cid, int sid, Timestamp orderTime, int tid, 
      boolean[] success, String sql) throws SQLException {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";    
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
        //"select * from trade.buyorders where status = 'open' and tid = ?",
        Log.getLogWriter().info(database + "querying trade.buyorders with TID:" +tid  + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select cid, bid, sid from trade.buyorders where cid >? and sid <? and qty >? and orderTime<? and tid = ?",        
        Log.getLogWriter().info(database + "querying trade.buyorders with  CID:" + cid + ",SID:" + sid 
            +",QTY:" + qty + ",ORDERTIME:" + orderTime + ",TID:" + tid +  query);   
        stmt.setInt(1, cid);
        stmt.setInt(2, sid);
        stmt.setInt(3, qty);
        if (testworkaroundFor51519)  stmt.setTimestamp(4, orderTime, getCal());
        else stmt.setTimestamp(4, orderTime);
        stmt.setInt(5, tid); 
        break;
      case 2:
        //"select sid, count(*) from trade.buyorders  where status =? and tid =? GROUP BY sid HAVING count(*) >2",  
        Log.getLogWriter().info(database + "querying trade.buyorders with STATUS:" + status  + ",TID:" +tid + query);   
        stmt.setString(1, status);
        stmt.setInt(2, tid);
        break;
      case 3:
        //"select cid, count(sid) from trade.buyorders  where status =? and tid =? GROUP BY cid",  
        Log.getLogWriter().info(database + "querying trade.buyorders with STATUS:" + status  + ",TID:" +tid + query);   
        stmt.setString(1, status);
        stmt.setInt(2, tid);
        break;
      case 4:
        //"select cid, sum(qty*bid) as amount from trade.buyorders  where status =? and tid =? GROUP BY cid ORDER BY amount",
        Log.getLogWriter().info( database + "querying trade.buyorders with STATUS:" + status  + ",TID:" +tid + query);   
        stmt.setString(1, status);
        stmt.setInt(2, tid);
        break;
      case 5:
        //"select cid, max(qty*bid) as 'largest order' from trade.buyorders  where status =? and tid =? GROUP BY cid ORDER BY max(qty*bid) DESC HAVING max(qty*bid) > 20000",
        Log.getLogWriter().info(database + "querying trade.buyorders with STATUS:" + status  + ",TID:" +tid + query);   
        stmt.setString(1, status);
        stmt.setInt(2, tid);
        break;
      case 6:
        //self join
        Log.getLogWriter().info(database + "querying trade.buyorders with TID:" +tid + query);   
        stmt.setInt(1, tid);
        break;
      case 7: 
        //"select * from trade.buyorders",
        Log.getLogWriter().info(database + "querying trade.buyorders with no data" + query);
        break;
      case 8: 
        //"select cid, bid, sid from trade.buyorders where cid >?  and sid <? and qty >? and orderTime<?  ",        
        Log.getLogWriter().info(database + "querying trade.buyorders with CID:" + cid + ",SID:" + sid 
            +",QTY:" + qty + ",ORDERTIME:" + orderTime + ",TID:" + tid + query);   
        stmt.setInt(1, cid);
        stmt.setInt(2, sid);
        stmt.setInt(3, qty);
        if (testworkaroundFor51519)  stmt.setTimestamp(4, orderTime, getCal());
        else stmt.setTimestamp(4, orderTime);
        break;
      case 9:
        //"select sid, count(*) from trade.buyorders  where status =? GROUP BY sid HAVING count(*) >2", 
        Log.getLogWriter().info(database + "querying trade.buyorders with STATUS:" + status + query);   
        stmt.setString(1, status);
        break;
      case 10:
        //"select cid, count(sid) from trade.buyorders  where status =? GROUP BY cid",  
        Log.getLogWriter().info( database + "querying trade.buyorders with STATUS:" + status + query);   
        stmt.setString(1, status);
        break;
      case 11:
        //"select cid, sum(qty*bid) as amount from trade.buyorders  where status =? GROUP BY cid ORDER BY amount",        
        Log.getLogWriter().info( database + "querying trade.buyorders with STATUS:" + status + query);   
        stmt.setString(1, status);
        break;
      case 12:
        //"select cid, max(qty*bid) as 'largest order' from trade.buyorders  where status =? GROUP BY cid ORDER BY max(qty*bid) DESC HAVING max(qty*bid) > 20000"
        Log.getLogWriter().info(database + "querying trade.buyorders with STATUS:" + status + query);   
        stmt.setString(1, status);
        break;
      case 13:
        //self join
        Log.getLogWriter().info(database + "querying trade.buyorders with no data"  + query);   
        break;
      default:
        throw new TestException("incorrect select statement, should not happen" );
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471
      else throw se;
    }
    return rs;
  } 
  
  //*** for delete ***//  
  /**
   * get data inserted by this thread or some random data 
   * @param regConn -- connection used to find the record inserted by this thread, when
   *              testUniqueKey is true.
   * @param cid -- array of cid to be populated
   * @param sid -- array lf sid to be populated
   * @return the number of array elements populated or -1 is none is available
   */
  protected int getDataForDelete(Connection regConn, int[] cid, int[] sid) {
    Connection conn = getAuthConn(regConn);
    int tid = getMyTid();  //for test unique key case
    if (testUniqueKeys || testWanUniqueness) {
      ; //do nothing
    } else {
      if (tid == 0)  ; //do nothing
      else tid = rand.nextInt(tid);  //data inserted from any tid
    }     
      
    return getDataForDelete(conn, cid, sid, tid);        
  }
  
  protected int getDataForDelete(Connection conn, int[] cid, int[] sid, int tid) {
    int availSize = cid.length;
    int firstQuery = 0; //first query is select *
    ResultSet rs = null;
    int[] offset = new int[1];
          
    rs = getQuery(conn, firstQuery, null, null, 0, 0, 0, null, tid);
      //get the resultSet in the table inserted by this thread
   
    int count =0;
    while (rs==null && !setCriticalHeap) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get result. abort this operations");
        return -1;
      }
      count++;
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      rs = getQuery(conn, firstQuery, null, null, 0, 0, 0, null, getMyTid());
    } //query may terminated earlier to get sids if not able to obtain lock
    
    ArrayList<Struct> result = (ArrayList<Struct>) ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    
    SQLHelper.closeResultSet(rs, conn); //for single hop, result set needs to be closed instead of fully consumed
        
    if (result == null) {
      Log.getLogWriter().info("Could not get result. abort this operations");
      return -1;
    }
    availSize = getAvailSize(result, cid.length, offset);
   
    for (int i =0 ; i<availSize; i++) {
      cid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("CID")).intValue();
      sid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("SID")).intValue();   
    }
 
    return availSize;
  }
  
  //add the exception to expceptionList to be compared with gfe
  protected boolean deleteFromDerbyTable(Connection dConn, int whichDelete, 
      int[]cid, int []sid, int[] oid, BigDecimal[] bid, int[] qty,
      List<SQLException> exList){
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]); 
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    
    try {
      for (int i=0; i<cid.length; i++) {
      verifyRowCount.put(tid+"_delete_" +i, 0); //default to 0 update
        count= deleteFromTable(stmt, cid[i], sid[i], oid[i], bid[i], qty[i], tid, whichDelete);
        verifyRowCount.put(tid+"_delete_" +i, new Integer(count));        
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
      int []sid, int[] oid, BigDecimal[] bid, int[] qty, List<SQLException> exList){
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
        count = deleteFromTable(stmt, cid[i], sid[i], oid[i], bid[i], qty[i], tid, whichDelete);
        if (count != (verifyRowCount.get(tid+"_delete_" +i)).intValue()){
          String str = "Gfxd delete (buyorders) has different row count from that of derby " +
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
      int[] sid, int[] oid, BigDecimal[] bid, int[] qty){
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
        deleteFromTable(stmt, cid[i], sid[i], oid[i], bid[i], qty[i], tid, whichDelete);
      }
    } catch (SQLException se) {
      if ((se.getSQLState().equals("42500") || se.getSQLState().equals("42502"))
          && testSecurity) {
        Log.getLogWriter().info("Got the expected exception for authorization," +
           " continuing tests");
      } else if (alterTableDropColumn && (se.getSQLState().equals("42X14") || se.getSQLState().equals("42X04"))) {
        //42X04 is possible when column in where clause is dropped
        Log.getLogWriter().info("Got expected column not found exception in delete, continuing test");
      } else 
        SQLHelper.handleSQLException(se); //handle the exception
    }
  }
  
  //delete from table based on whichDelete
  protected int deleteFromTable(PreparedStatement stmt, int cid, int sid, 
      int oid, BigDecimal bid, int qty, int tid, int whichDelete) throws SQLException {
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";    
    String query = " QUERY: " + delete[whichDelete];
    int rowCount = 0;
    
    switch (whichDelete) {
    case 0:   
      //"delete from trade.buyorders where cid=? and sid=? and tid=?", //could be batch delete, but no foreign key reference, this should work
      Log.getLogWriter().info(database + "deleting trade.buyorders with CID:" + cid + ",SID:" + sid 
          + ",TID:" + tid + query);  
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders CID:" + cid + ",SID:" + sid 
          + ",TID:" + tid + query);  
      break;
    case 1:
      //"delete from trade.buyorders where oid=? and (bid <? or qty>?) and tid=?",
      Log.getLogWriter().info(database +  "deleting trade.buyorders with OID:" + oid + ",BID:" + bid 
          + ",QTY:" + qty + ",TID:" + tid + query );  
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, bid);
      stmt.setInt(3, qty);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders OID:" + oid + ",BID:" + bid 
          + ",QTY:" + qty + ",TID:" + tid + query );  
      break;
    case 2:   
      //    "delete from trade.buyorders where (status ='cancelled' or status = 'filled' or qty<?) and bid>? and tid=?",
      Log.getLogWriter().info(database +  "deleting trade.buyorders with QTY:" + qty + ",BID:" + bid 
          + ",TID:" + tid + query);  
      stmt.setInt(1, qty);
      stmt.setBigDecimal(2, bid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders QTY:" + qty + ",BID:" + bid 
          + ",TID:" + tid + query);  
      break;
    case 3:   
      //"delete from trade.buyorders where cid=? and sid=? "
      Log.getLogWriter().info(database + "deleting trade.buyorders with CID:" + cid + ",SID:" + sid + query); 
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders CID:" + cid + ",SID:" + sid + query); 
      break;
    case 4:
      //"delete from trade.buyorders where oid=? and (bid <? or qty>?)  ",
      Log.getLogWriter().info(database + "deleting trade.buyorders with OID:" + oid + ",BID:" + bid 
          + ",QTY:" + qty + query ); 
      stmt.setInt(1, oid);
      stmt.setBigDecimal(2, bid);
      stmt.setInt(3, qty);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders OID:" + oid + ",BID:" + bid 
          + ",QTY:" + qty + query ); 
      break;
    case 5:   
      //    "delete from trade.buyorders where (status ='cancelled' or status = 'filled' or qty<?) and bid>?",
      Log.getLogWriter().info(database + "deleting trade.buyorders with QTY:" + qty + ",BID:" + bid + query);  
      stmt.setInt(1, qty);
      stmt.setBigDecimal(2, bid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows in trade.buyorders QTY:" + qty + ",BID:" + bid + query); 
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

  @Override
  public void put(Connection dConn, Connection gConn, int size) {
    if (testLoaderCreateRandomRow) {
      insertThruLoader(gConn, size);
      return;
    } //insert through loader of rows randomly created -- not from back_end db
    
    int[] cid = new int[size];
    if (gConn == null) getCids(dConn,cid); //populate thru loader case, gConn could be set to null
    else if (dConn !=null && rand.nextBoolean()) getCids(dConn,cid); //populate cids   
    else getCids(gConn,cid);
    
    insert(dConn, gConn, size, cid , true);
  }

}
