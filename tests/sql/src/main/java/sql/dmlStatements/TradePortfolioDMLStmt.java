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
import java.util.ArrayList;
import java.util.List;
import java.lang.Math;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLHelper;
import sql.SQLTest;
import sql.SQLPrms;
import sql.security.SQLSecurityTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlutil.ResultSetHelper;
import sql.sqlutil.GFXDStructImpl;
import util.TestException;

/**
 * @author eshu
 *
 */
public class TradePortfolioDMLStmt extends AbstractDMLStmt {
  /*
   * trade.Portfolio table fields
   *   int cid;
   *   int sid;
   *   int qty;
   *   int availQty;
   *   BigDecimal subTotal;
   *   int tid; //for update or delete unique records to the thread
   */
  
  private static boolean reproduceTicket48840 = true;
  protected static String insert = "insert into trade.portfolio values (?,?,?,?,?, ?)";
  protected static String put = "put into trade.portfolio values (?,?,?,?,?, ?)";
  protected static String[] update = { //uniq
                                      "update trade.portfolio set availQty=availQty-100 where cid = ? and sid = ?  and tid= ?", 
                                      "update trade.portfolio set qty=qty-? where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfolio set subTotal = ? * qty  where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfolio set subTotal=? where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfolio set cid=? where cid = ? and sid = ?  and tid= ?", //cid in updateData is based on tid
                                      //no uniq
                                      "update trade.portfolio set availQty=availQty-100 where cid = ? and sid = ? ", 
                                      "update trade.portfolio set qty=qty-? where cid = ? and sid = ?  ",
                                      "update trade.portfolio set subTotal=? * qty where cid = ? and sid = ? ",
                                      "update trade.portfolio set subTotal=? where cid = ? and sid = ? ",
                                      "update trade.portfolio set cid=? where cid = ? and sid = ? ", 
                                      "update trade.portfolio set sid=? where cid = ? and sid = ? "
                                      }; 
  protected static String[] select = {"select * from trade.portfolio where tid = ?",
                                    "select sid, cid, subTotal from trade.portfolio where (subTotal >? and subTotal <= ?) and tid=? ",
                                    (reproduceTicket48840 ? "select CAST(count(distinct cid) as " +
                                        "integer) as num_distinct_cid from trade.portfolio where (subTotal<? or subTotal >=?) and tid =?"
                                        : "select CAST(count(cid) as integer) as num_cid from trade.portfolio where (subTotal<? or subTotal >=?) and tid =?"),
                                    "select distinct sid from trade.portfolio where (qty >=? and subTotal >= ?) and tid =?",
                                    "select sid, cid, qty from trade.portfolio  where (qty >=? and availQty<?) and tid =?",
                                    "select * from trade.portfolio where sid =? and cid=? and tid = ?",
                                    "select * from trade.portfolio ",
                                    "select sid, cid from trade.portfolio where subTotal >? and subTotal <= ? ",
                                    "select distinct cid from trade.portfolio where subTotal<? or subTotal >=?",
                                    "select distinct sid from trade.portfolio where qty >=? and subTotal >= ? ",
                                    "select sid, cid, qty from trade.portfolio  where (qty >=? and availQty<?) ",
                                    "select * from trade.portfolio where sid =? and cid=?"
                                    };
  protected static String[] delete = {/*"delete from trade.portfolio where qty = 0 and tid = ?", as of preview, no cascade delete is allowed, this batch op may cause inconsistence from derby results*/
                                     "delete from trade.portfolio where cid=? and sid=? and tid=?",
                                     //no uniq
                                     /*"delete from trade.portfolio where qty = 0",*/
                                     "delete from trade.portfolio where cid=? and sid=?",
                                      };

  protected static int maxNumOfTries = 1;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  public static boolean useFunction = TestConfig.tab().booleanAt(SQLPrms.populateTableUsingFunction, false);
  protected static ArrayList<String> partitionKeys = null;
  
  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#insert(java.sql.Connection, java.sql.Connection, int)
   */
  @Override
  public void insert(Connection dConn, Connection gConn, int size) {
    int cid=0;
    if(dConn !=null) {
      if (rand.nextInt(10) == 0)  cid = getCid(dConn);  
      else cid = getCid(gConn);
    }
    else cid = getCid(gConn);
    
    if (cid !=0)  //TODO comment out this line to reproduce bug      
      insert(dConn, gConn, size, cid);
  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#update(java.sql.Connection, java.sql.Connection, int)
   */
  @Override
  public void update(Connection dConn, Connection gConn, int size) {
    int numOfNonUniqUpdate = 6;  //how many update statement is for non unique keys
    int whichUpdate= getWhichUpdate(numOfNonUniqUpdate, update.length);
    
    int[] cid = new int[size];
    int[] sid = new int[size];
    int[] newCid = new int[size];
    int[] newSid = new int[size];
    int[] qty = new int[size];
    int[] availQty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();
    int availSize;
        
    
    if (isHATest && (whichUpdate == 0 || whichUpdate == 1 
        || whichUpdate == 5 || whichUpdate == 6 ) && !SQLTest.setTx) {
      Log.getLogWriter().info("avoid x=x+1 in HA test without txn, do not execute this update");
      return;
    } //avoid x=x+1 update in HA test for now  
    
    if (dConn != null) {
      //data got may not be enough for update
      //get data
      if (rand.nextInt(numGettingDataFromDerby) == 1) 
        availSize = getDataForUpdate(dConn, cid, sid, qty, availQty, sub, newCid, newSid, price, size); //get the data
      else 
        availSize = getDataForUpdate(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, size); //get the data;
      
      if (SQLTest.testSecurity && availSize == 0) {
      	Log.getLogWriter().info("does not have data to perform operation in security test");
      	return;
      }
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = updateDerbyTable(dConn, cid, sid, qty, availQty, sub, newCid, newSid, price, availSize, whichUpdate, exceptionList);  //insert to derby table  
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
        success = updateDerbyTable(dConn, cid, sid, qty, availQty, sub, newCid, newSid, price, availSize, whichUpdate, exceptionList); //retry
      }
      updateGFETable(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, availSize, whichUpdate, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      if (getDataForUpdate(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, size) >0) //get the random data, no availSize constraint
        updateGFETable(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, size, whichUpdate);
    } //no verification
  }

  /* (non-Javadoc)
   * @see sql.dmlStatements.AbstractDMLStmt#delete(java.sql.Connection, java.sql.Connection)
   */
  @Override
  public void delete(Connection dConn, Connection gConn) {
    int numOfNonUniqDelete = delete.length/2;  //how many delete statement is for non unique keys
    int whichDelete = getWhichOne(numOfNonUniqDelete, delete.length);
    int[] cid = new int[1]; //only delete one record
    int[] sid = new int[1];
    List<SQLException> exceptionList = new ArrayList<SQLException>(); //for compare exceptions got from two sources
    int availSize;
    if (dConn!=null) {
      if (rand.nextInt(numGettingDataFromDerby) == 1) availSize= getDataForDelete(dConn, cid ,sid, cid.length);
      else availSize= getDataForDelete(gConn, cid ,sid, cid.length);
    }
    else 
      availSize= getDataForDelete(gConn, cid ,sid, cid.length);
    
    if(availSize == 0) return; //did not get the results
    
    if (setCriticalHeap) resetCanceledFlag();
    
    //for verification both connections are needed
    if (dConn != null) {
      boolean success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, exceptionList);
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
        success = deleteFromDerbyTable(dConn, whichDelete, cid, sid, exceptionList); //retry
      }
      deleteFromGFETable(gConn, whichDelete, cid, sid, exceptionList);
      SQLHelper.handleMissedSQLException(exceptionList);
    } 
    else {
      deleteFromGFETable(gConn, whichDelete, cid, sid); //w/o verification
    }
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
    int[] key = getKey(gConn);
    if (key !=null) {
      sid = key[0];
      cid = key[1];
    }
    
    int tid = getMyTid();
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    
    if (dConn!=null) {
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
        gfeRS = query (gConn, whichQuery, subTotal1, subTotal2, queryQty, queryAvail, sid, cid, tid); 
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
        Log.getLogWriter().info("Not able to compare results due to derby server error");
      } //not able to compare results due to derby server error        
    }// we can verify resultSet
    else {
      try {
        gfeRS = query (gConn, whichQuery, subTotal1, subTotal2, queryQty, 
            queryAvail, sid, cid, tid);
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
    int numCid = rand.nextInt(60) + 1;
    int initSize = rand.nextInt(10)+1;
    if (!useFunction) {
      for (int i=0; i<numCid; i++) {
        for (int j=0; j<initSize; j++) {
          if (setCriticalHeap) resetCanceledFlag();
          insert(dConn, gConn, 1);
          commit(gConn);
          if (dConn != null) commit(dConn);
        }
      } //insert 10 times (but sometimes it may not be successful.)    
    } else {
      for (int i=0; i<numCid; i++) {
        insert(dConn, initSize);
        commit(dConn);
      } //only used when populate thru function assume that derby server has the data already
      
      /*
      while (SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.populatePortfolioUsingFunction)!=1) {
        MasterController.sleepForMs(rand.nextInt(1000));
      }
      */
      try {
        populateUsingFunction(gConn);
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
      //SQLBB.getBB().getSharedCounters().zero(SQLBB.populatePortfolioUsingFunction);
    }
  }
  
  //only insert into derby tables, used when gfe table populated using function
  public void insert(Connection dConn, int size) {
    int cid = getCid(dConn);      
    if (cid !=0)  //TODO comment out this line to reproduce bug      
      insert(dConn, size, cid);
  }
  
  public void insert(Connection dConn, int size, int cid) {
    int[] sid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal [size]; 
    List<SQLException> exList = new ArrayList<SQLException>();
    int availSize = 0;
    availSize = getDataFromResultSet(dConn, sid, qty, sub, price, size); //get the data
    boolean success = insertToDerbyTable(dConn, cid, sid, qty, sub, availSize, exList);
    int count = 0;
    while (!success) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finish the operation in derby, abort this operation");
      }
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      count++; 
      exList.clear();
      success = insertToDerbyTable(dConn, cid, sid, qty, sub, availSize, exList);
    }
  }
  
  //populate gfe/gfxd using function
  private void populateUsingFunction(Connection gConn) throws SQLException {
  
  String insertSQL = null;
    insertSQL = " insert into trade.portfolio select p.* from table (trade.funcPortf( " +
        + getMyTid() + ")) p";
  PreparedStatement stmt = getStmt(gConn, insertSQL);
  Log.getLogWriter().info("gemfirexd - inserting into trade.portfolio using function " + insertSQL);
  int rowCount = stmt.executeUpdate();
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    Log.getLogWriter().info("gemfirexd - inserted " + rowCount + " rows into trade.portfolio using function" + insertSQL);

  }
  //should be used in populate tables, additional insert should be done by trigger in the orders table
  public void insert(Connection dConn, Connection gConn, int size, int cid) {
    insert(dConn, gConn, size, cid, false);
  }
  //should be used in populate tables, additional insert should be done by trigger in the orders table
  public void insert(Connection dConn, Connection gConn, int size, int cid, boolean isPut) {
    int[] sid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    int availSize = 0;
    if (dConn!=null) {
      //get data
      if (rand.nextInt(numGettingDataFromDerby) == 1) availSize = getDataFromResultSet(dConn, sid, qty, sub, price, size); //get the data
      else availSize = getDataFromResultSet(gConn, sid, qty, sub, price, size); //get the data
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = insertToDerbyTable(dConn, cid, sid, qty, sub, availSize, exList);
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
        exList.clear();
        success = insertToDerbyTable(dConn, cid, sid, qty, sub, availSize, exList);
      }
      try {
        insertToGFETable(gConn, cid, sid, qty, sub, availSize, exList, isPut);
      } catch (TestException te) {
        if (te.getMessage().contains("Execute SQL statement failed with: 23505")
            && isHATest && SQLTest.isEdge) {
          //checkTicket49605(dConn, gConn, "portfolio");
          try {
            checkTicket49605(dConn, gConn, "customers", cid, sid[0], null, null);
          } catch (TestException e) {
            Log.getLogWriter().info("insert failed due to #49605 ", e);
            //deleteRow(dConn, gConn, "buyorders", cid, sid[0], null, null);
            Log.getLogWriter().info("retry this using put to work around #49605");
            insertToGFETable(gConn, cid, sid, qty, sub, availSize, exList, true);
            
            checkTicket49605(dConn, gConn, "customers", cid, sid[0], null, null);
          }
        } else throw te;
      }
      SQLHelper.handleMissedSQLException(exList);
    }    
    else {
      if (testUniqueKeys || testWanUniqueness) {
        availSize = getDataFromResultSet(gConn, sid, qty, sub, price, size); //get the data
          insertToGFETable(gConn, cid, sid, qty, sub, availSize, isPut);
      } else {
        if (getRandomData(gConn, sid, qty, sub, price, size)) //get the data
          insertToGFETable(gConn, cid, sid, qty, sub, size, isPut);
      }
    } //no verification
  }
  
  //*** insert related methods ***//
  
  //passed the resultset to get the sids insert/updated by this thread when test uniq key
  //get any avail sids if not test uniq keys
  protected int getDataFromResultSet(Connection regConn, int[] sid, int[] qty, 
      BigDecimal[] sub, BigDecimal[] price, int size) {
    Connection conn = getAuthConn(regConn);
    int availSize = size;
    int firstQuery = 0; //first query is select *
    ResultSet rs = null;
    int[] offset = new int[1];
       
    if (testUniqueKeys || testWanUniqueness) {     
      rs = TradeSecuritiesDMLStmt.getQuery(conn, firstQuery, 0, null, null, null, getMyTid());
        //get the resultSet of securities inserted by this thread
     
      if (rs==null) {
        Log.getLogWriter().info("Already retried, will skip this operation");
        return 0;
      } //query may terminated earlier to get sids if not able to obtain lock
      
      ArrayList<Struct> result = (ArrayList<Struct>) 
          ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));    
      
      SQLHelper.closeResultSet(rs, conn); 
      
      if (result==null) {
        if (!SQLHelper.isDerbyConn(conn) && isHATest)
          return 0;
        else if (setCriticalHeap) {      
          boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
          if (getCanceled[0] == true) {
            Log.getLogWriter().info("Not able to get data for due to query cancellation");
            return 0;  
          }
        }
        else  
          throw new TestException ("could not get list from resultset, and it is not HA test");
      }
        
      availSize = getAvailSize(result, size, offset);
     
      for (int i =0 ; i<availSize; i++) {
        sid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("SEC_ID")).intValue();
        price[i] = (BigDecimal)((GFXDStructImpl)result.get(i+offset[0])).get("PRICE");
        qty[i] = rand.nextInt(1901)+100; //from 100 to 2000
        sub[i] = price[i].multiply(new BigDecimal(String.valueOf(qty[i])));       
      }
    } //passed the resultset to get the securities insert/updated by this thread
    else {
      getRandomData(conn, sid, qty, sub, price, availSize);
    }    
    return availSize;
  }
  
  //find any sec_id and calculate subTotal, only to be used in populate tables in init tasks
  //otherwise price could be changed during insert.
  //in the trigger test, all insert (portfolio) should be caused by trigger in the buyOrders.
  protected boolean getRandomData(Connection regConn, int[] sid, int[] qty, BigDecimal[] sub, 
      BigDecimal[] price, int size) {
  	Connection conn = getAuthConn(regConn);
  	int fifthQuery = 4; // the fifth select query String of tradeSecuritiesDMLStmt is used here.
    ResultSet rs = null;
    try {
      for (int i = 0; i<size; i++) {
        sid[i] = getSid();
        qty[i] = getQty(); //from 100 to 2000
        rs = TradeSecuritiesDMLStmt.getQuery(conn, fifthQuery, sid[i], null, null, null, -1);        
        if (rs!=null && rs.next()) {
          price[i] = rs.getBigDecimal("price");  //should always return 1 record unless the record is deleted
          rs.close();
        }
        if (price[i] !=null)
          sub[i] = price[i].multiply(new BigDecimal(String.valueOf(qty[i])));
        else {          
          //doing nothing, as this insert with sid will violate the foreign key constraint
        }
      }
    } catch (SQLException se) {
      if (!SQLHelper.checkGFXDException(conn, se)) return false; //need retry or no op
      else SQLHelper.handleSQLException(se); //throws TestException.
    } 
    return true;
  }   
  
  //insert into Derby
  protected boolean insertToDerbyTable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, int size, List<SQLException> exceptions)  {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();
    int count =-1;
    
    for (int i=0 ; i<size ; i++) {
      try {
      verifyRowCount.put(tid+"_insert"+i, 0);
        count = insertToTable(stmt, cid, sid[i], qty[i], sub[i], tid);
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }    
    }
    return true;
  }
  
  //insert into GFE
  protected void insertToGFETable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, int size, List<SQLException> exceptions, boolean isPut)  {
    PreparedStatement stmt = getStmt(conn, isPut ? put  : insert);
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exceptions);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
    else if (SQLTest.setCriticalHeap && stmt == null) {
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
        count = insertToTable(stmt, cid, sid[i], qty[i], sub[i], tid, isPut);
        if (count != (verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd insert has different row count from that of derby " +
            "derby inserted " + (verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      }  catch (SQLException se) {
        /* disallowed put in portfolio table to avoid same cid, sid to be used
         * this will cause new insert fail in derby but succeed in gfxd using put
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
        */
        SQLHelper.handleGFGFXDException(se, exceptions);
      }    
    }
  }
  
  //insert into GFE w/o verification
  protected void insertToGFETable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, int size, boolean isPut)  {
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);
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
        insertToTable(stmt, cid, sid[i], qty[i], sub[i], tid, isPut);
      }  catch (SQLException se) {
        if (se.getSQLState().equals("23503"))
          Log.getLogWriter().info("detected foreign key constraint violation during " + (isPut ? "Put" : "Insert") + ", continuing test");
        
        else if (se.getSQLState().equals("23505"))
          Log.getLogWriter().info("detected primary key constraint violation during " + (isPut ? "Put" : "Insert") + ", continuing test");
        else if (se.getSQLState().equals("42500") && testSecurity) 
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        else if (alterTableDropColumn && se.getSQLState().equals("42802")) {
          Log.getLogWriter().info("Got expected column not found exception in insert, continuing test");
        } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } 
        else if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
          SQLHelper.handleSQLException(se);
      }  
    }
  }

  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int cid, int sid, 
      int qty, BigDecimal sub, int tid) throws SQLException {
    return insertToTable(stmt, cid, sid, qty, sub, tid, false);
  }
  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int cid, int sid, 
      int qty, BigDecimal sub, int tid, boolean isPut) throws SQLException {
    
    String txId = SQLDistTxTest.curTxId.get() == null ?"" : "TXID: " + (Integer) SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " +txId;
    
    Log.getLogWriter().info( database + (isPut ? "putting" : "inserting") + " into trade.portfolio with CID:" + cid +
        ",SID:"+ sid + ",QTY:" + qty + ":AVAILQTY:" + qty + ",SUBTOTAL:" + sub);
    
    stmt.setInt(1, cid);
    stmt.setInt(2, sid);
    stmt.setInt(3, qty);
    stmt.setInt(4, qty); //availQty is the same as qty during insert
    stmt.setBigDecimal(5, sub);   
    stmt.setInt(6, tid);
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info( database + (isPut ? "put " : "inserted ") + rowCount +  " rows into trade.portfolio CID:" + cid +
        ",SID:"+ sid + ",QTY:" + qty + ":AVAILQTY:" + qty + ",SUBTOTAL:" + sub);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    if ( database.contains("gemfirexd") && isPut && ! SQLTest.ticket49794fixed) {
    //manually update fulldataset table for above entry.
      String deleteStatement = "DELETE FROM TRADE.PORTFOLIO_FULLDATASET  WHERE CID= " + cid + " and SID = "  + sid ;
      String insertStatement = " INSERT INTO TRADE.PORTFOLIO_FULLDATASET  VALUES ( " + cid + " ,  " +   sid  + " ,  " + qty  + " ,  " +    qty + " ,  " + sub + " ,  " +  tid +  ")";
      Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence deleting  the  row  from TRADE.PORTFOLIO_FULLDATASET with data CID:" +  cid +  ",SID:" + sid );
      stmt.getConnection().createStatement().execute(deleteStatement);
      Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence inserting  the  row  into  TRADE.PORTFOLIO_FULLDATASET with data CID:" + cid +
        ",SID:"+ sid + ",QTY:" + qty + ":AVAILQTY:" + qty + ",SUBTOTAL:" + sub);
      stmt.getConnection().createStatement().execute(insertStatement);
    }
    //doing second put that may or may not successfull.
    if ( database.contains("gemfirexd") && isPut) {
      Log.getLogWriter().info( database + (isPut ? "putting" : "inserting") + " into trade.portfolio with CID:" + cid +
          ",SID:"+ sid + ",QTY:" + qty + ":AVAILQTY:" + qty + ",SUBTOTAL:" + sub);
      
     rowCount = stmt.executeUpdate();
     Log.getLogWriter().info( database + (isPut ? "put " : "inserted ") + rowCount +  " rows into trade.portfolio CID:" + cid +
         ",SID:"+ sid + ",QTY:" + qty + ":AVAILQTY:" + qty + ",SUBTOTAL:" + sub);
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
    }
    return rowCount;
  }

  
  //*** update related methods ***//
  //get data for update
  //for trigger tests, this table should be updated by security trigger and order triggers
  protected int getDataForUpdate (Connection regConn, int cid[], int[] sid, int[] qty, int[] availQty, 
      BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, int size) {
    Connection conn = getAuthConn(regConn);
    int availSize = getDataFromResultSet(conn, newSid, qty, sub, price, size);  //newSid got are also inserted by this thread before
    getCids(conn, newCid); //populate the newCid, newCid is 0 will got exception for violating fk constraint
    getCids(conn, cid);  
    for (int i = 0; i<availSize; i++) {          
      sid[i] = getSid(); //to be updated sid could be any. but it wouldn't be updated as it is not inserted by this thr
      availQty[i] = Math.abs(qty[i] - getQty());
    }
    return availSize;
  }
   
  //update records in Derby
  protected boolean updateDerbyTable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, 
      int size, int whichUpdate, List<SQLException> exceptions) {
    if (whichUpdate == 4 || whichUpdate ==9 || whichUpdate == 10 ) {
      Log.getLogWriter().info("update on gfxd primary key, do not execute update in derby as well");
      return true;
    } //update primary key case
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    boolean[] unsupported = new boolean[1];
    if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate, unsupported);
    else {
      stmt = getStmt(conn, update[whichUpdate]); //use only this after bug#39913 is fixed
    }
    
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
    
    for (int i=0 ; i<size ; i++) {
      try {
        if (stmt!=null) {
          verifyRowCount.put(tid+"_update"+i, 0);          
          count = updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i], newCid[i], newSid[i], price[i], tid, whichUpdate );
          verifyRowCount.put(tid+"_update"+i, new Integer(count));
          
        }
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
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
      partitionKeys= (ArrayList<String>)partitionMap.get("portfolioPartition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_portfolioPartition");
    }
    Log.getLogWriter().info("partition keys are " + partitionKeys);
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys, boolean[] unsupported){  
    PreparedStatement stmt = null;
    
    switch (whichUpdate) {    
    case 0: 
      //"update trade.portfolio set availQty=availQty-100 where cid = ? and sid = ?  and tid= ?"
      if (partitionKeys.contains("availQty")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      // "update trade.portfolio set qty=qty-? where cid = ? and sid = ?  and tid= ?",
      if (partitionKeys.contains("qty")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      //"update trade.portfolio set subTotal=subtotal*qty where cid = ? and sid = ?  and tid= ?",
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      // "update trade.portfolio set subTotal=? where cid = ? and sid = ?  and tid= ?", 
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 4: 
      //"update trade.portfolio set cid=? where cid = ? and sid = ?  and tid= ?"
      throw new TestException ("Test issue, this should not happen");
    case 5: 
      // "update trade.portfolio set availQty=availQty-100 where cid = ? and sid = ? ",
      if (partitionKeys.contains("availQty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 6: 
      // "update trade.portfolio set qty=qty-? where cid = ? and sid = ?  ",
      if (partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 7: 
      //"update trade.portfolio set subTotal=subtotal*qty where cid = ? and sid = ? ",
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 8: 
      // "update trade.portfolio set subTotal=? where cid = ? and sid = ? ", 
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 9: 
      //"update trade.portfolio set cid=? where cid = ? and sid = ? ",
      throw new TestException ("Test issue, this should not happen");
    case 10: 
      // "update trade.portfolio set sid=? where cid = ? and sid = ? "
      throw new TestException ("Test issue, this should not happen");
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  //update records in gemfirexd
  protected void updateGFETable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, 
      int size, int whichUpdate, List<SQLException> exceptions) {
    PreparedStatement stmt;
    int tid = getMyTid();
    int count = -1;
    
    if (whichUpdate == 4 || whichUpdate ==9 || whichUpdate == 10) {
      stmt = getUnsupportedStmt(conn, update[whichUpdate]);
      if (stmt == null) return;
      else throw new TestException("Test issue, should not happen\n" );
    } //update primary key case
    if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate, null);
    else {
      stmt = getStmt(conn, update[whichUpdate]); //use only this after bug#39913 is fixed
    } //not testPartitionBy cases
    
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exceptions);
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	}
    } //work around #43244
    else if (SQLTest.setCriticalHeap && stmt == null) {
      return; //prepare stmt may fail due to XCL54 now
    }
    
    for (int i=0 ; i<size ; i++) {
      try {
        if (stmt!=null) {
          count = updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i],  newCid[i], newSid[i], price[i], tid, whichUpdate );
          if (count != (verifyRowCount.get(tid+"_update"+i)).intValue()){
            String str = "Gfxd update has different row count from that of derby " +
                    "derby updated " + (verifyRowCount.get(tid+"_update"+i)).intValue() +
                    " but gfxd updated " + count;
            if (failAtUpdateCount && !isHATest) throw new TestException (str);
            else Log.getLogWriter().warning(str);
          }
        }
      }  catch (SQLException se) {
        SQLHelper.handleGFGFXDException(se, exceptions);
      }    
    }
  }
  
  //update records in gemfirexd, no verification
  protected void updateGFETable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, int size, int whichUpdate) {
    PreparedStatement stmt;
    int tid = getMyTid();
    
    
    if (whichUpdate == 4 || whichUpdate == 9 || whichUpdate ==10 ) {
      stmt = getUnsupportedStmt(conn, update[whichUpdate]);
      if (stmt == null) return;
      else throw new TestException("Test issue, should not happen%n" );
    } //update partion key case
    if (SQLTest.testPartitionBy)    stmt = getCorrectStmt(conn, whichUpdate, null);
    else {
      stmt = getStmt(conn, update[whichUpdate]); //use only this after bug#39913 is fixed
    } //not testPartitionBy cases
    if (stmt == null) {
      return;
    }
    for (int i=0 ; i<size ; i++) {
      try {
        updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i], newCid[i], newSid[i], price[i], tid, whichUpdate );
      }  catch (SQLException se) {
        if (se.getSQLState().equals("23503") && (whichUpdate == 4 || whichUpdate == 9 || whichUpdate == 10))
          Log.getLogWriter().info("detected foreign key constraint violation during update, continuing test");
        else if (se.getSQLState().equals("23513")){
          Log.getLogWriter().info("detected check constraint violation during update, continuing test");
        } else if (se.getSQLState().equals("42502") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
          " continuing tests");
        } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
          Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
        } else
          SQLHelper.handleSQLException(se);
      }    
    }
  }
  
  //update table based on the update statement
  protected int updateTable(PreparedStatement stmt, int cid, int sid, int qty,
      int availQty, BigDecimal sub,  int newCid, int newSid, BigDecimal price, 
      int tid, int whichUpdate) throws SQLException {
  //  Log.getLogWriter().info("update table with cid: " + cid + " sid: " + sid +
  //      " qty: " + qty + " availQty: " + availQty + " sub: " + sub);
    String txId = SQLDistTxTest.curTxId.get() == null ?"" : "TXID: " + (Integer) SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " + txId;
    String query = " QUERY: " + update[whichUpdate];
    int rowCount = 0;
    
    switch (whichUpdate) {
    case 0: 
      Log.getLogWriter().info(database + "updating trade.portfolio with AVAILQTY:(availQty-100)  where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      stmt.setInt(1, cid);
      stmt.setInt(2, newSid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio AVAILQTY:(availQty-100) where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      break;
    case 1: 
      Log.getLogWriter().info(database +  "updating trade.portfolio with QTY:qty - " +qty + " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio QTY:qty - " +qty + " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      break;      
    case 2: 
      Log.getLogWriter().info(database + "updaing trade.portfolio  with SUBTOTAL:price( " + price
          + " ) * qty where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      stmt.setBigDecimal(1, price);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio SUBTOTAL: price( " + price
          + " ) * qty where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query); 
   
      rowCount = stmt.executeUpdate();
      break;      
    case 3: //update name, since
      Log.getLogWriter().info(database +  "updating trade.portfolio with SUBTOTAL:" + sub + " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      stmt.setBigDecimal(1, sub);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio SUBTOTAL:" + sub + " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query); 
      break;
    case 4:
      Log.getLogWriter().info(database +  "updating trade.portfolio with CID:" + newCid +  " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);

      stmt.setInt(1, newCid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio CID:" + newCid +  " where CID:" + cid  + ",SID:" + newSid +
          ",TID:" + tid + query);
      //rowCount = stmt.executeUpdate();  //TODO need to uncomment out when update primary is ready
      break; 
    case 5:
      Log.getLogWriter().info(database +  "updating trade.portfolio with AVAILQTY:(availQty-100) where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setInt(1, cid);
      stmt.setInt(2, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio AVAILQTY:(availQty-100) where CID:" + cid  + ",SID:" + newSid + query);
      break;
    case 6: 
      Log.getLogWriter().info(database + "updating trade.portfolio QTY:qty -" +qty + " where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio QTY:qty -" +qty + " where CID:" + cid  + ",SID:" + newSid + query);
      break;
    case 7: 
      Log.getLogWriter().info(database + "updating trade.portfolio with SUBTOTAL:price( " + price
          + " ) * qty where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setBigDecimal(1, price);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio SUBTOTAL:price( " + price
          + " ) * qty where CID:" + cid  + ",SID:" + newSid + query);
      break;
    case 8: //update name, since
      Log.getLogWriter().info(database + "updating trade.portfolio with SUBTOTAL:" + sub + " where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setBigDecimal(1, sub);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio SUBTOTAL:" + sub + " where CID:" + cid  + ",SID:" + newSid + query);
      break;
    case 9:
      Log.getLogWriter().info(database + "updating trade.portfolio with CID:" + newCid +  " where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setInt(1, newCid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio CID:" + newCid +  " where CID:" + cid  + ",SID:" + newSid + query);
      break; //non uniq keys
    case 10:
      Log.getLogWriter().info(database + "updating trade.portfolio with NEWSID:" + newSid +  " where CID:" + cid  + ",SID:" + newSid + query);
      stmt.setInt(1, newSid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.portfolio NEWSID:" + newSid +  " where CID:" + cid  + ",SID:" + newSid + query);
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
  
  //*** for delete ***//
  //returns the num of actual size populated by this method
  //it tries to find the cids and sids (pks) inserted by this thread, but it could return
  //cid and sid inserted by other threads, so delete needs to make sure tid is in the query
  //string to avoid delete records inserted by other threads in unique keys tests
  protected int getDataForDelete(Connection regConn, int[] cid, int[] sid, int size) {
    Connection conn = getAuthConn(regConn);
    int availSize = size;
    int firstQuery = 0; //first query is select *
    ResultSet rs = null;
    int[] offset = new int[1];
    int tid = 0;
    if (testUniqueKeys || testWanUniqueness) tid = getMyTid();
    else tid = rand.nextInt(getMyTid() + 1);
       
    rs = getQuery(conn, firstQuery, null, null, 0,0,0,0, tid);
      //get the resultSet of portfolio inserted by this thread
   int count =0;
   while (rs == null && !setCriticalHeap) {
     if (count >= maxNumOfTries) {
       Log.getLogWriter().info("not able to get info, use random data instead");
       getCids(conn, cid); //get random cids
       for (int i =0 ; i<availSize; i++) {
         sid[i]= getSid(); //get random sids
       } 
       return availSize;
     }
     count++;
     rs = getQuery(conn, firstQuery, null, null, 0,0,0,0, getMyTid());
    }
    ArrayList<Struct> result = (ArrayList<Struct>) 
        ResultSetHelper.asList(rs, SQLHelper.isDerbyConn(conn));
    SQLHelper.closeResultSet(rs, conn);
    
    availSize = getAvailSize(result, size, offset);
   
    for (int i =0 ; i<availSize; i++) {
      cid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("CID")).intValue();
      sid[i] = ((Integer)((GFXDStructImpl)result.get(i+offset[0])).get("SID")).intValue();   
    } 
    return availSize;
  }
  
  //add the exception to expceptionList to be compared with gfe
  protected boolean deleteFromDerbyTable(Connection dConn, int whichDelete, 
      int[]cid, int []sid, List<SQLException> exList){
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]); 
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    
    try {
      for (int i=0; i<cid.length; i++) {
      verifyRowCount.put(tid+"_delete"+i, 0);
        count = deleteFromTable(stmt, cid[i], sid[i], tid, whichDelete);
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
      int []sid, List<SQLException> exList){
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]); 
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exList);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
    else if (SQLTest.setCriticalHeap && stmt == null) {
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
    int count =-1;
    
    try {
      for (int i=0; i<cid.length; i++) {
        count = deleteFromTable(stmt, cid[i], sid[i], tid, whichDelete);
        if (count != (verifyRowCount.get(tid+"_delete"+i)).intValue()){
          String str = "Gfxd delete (portfolio) has different row count from that of derby " +
              "derby deleted " + (verifyRowCount.get(tid+"_delete"+i)).intValue() +
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
  protected void deleteFromGFETable(Connection gConn, int whichDelete, int[]cid,
      int []sid){
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]); 
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	} else ; //need to find out why stmt is not obtained
    } //work around #43244
    else if (SQLTest.setCriticalHeap && stmt == null) {
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
        deleteFromTable(stmt, cid[i], sid[i], tid, whichDelete);
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("23503"))
        Log.getLogWriter().info("detected the foreign key constraint violation, continuing test");
      else if ((se.getSQLState().equals("42500") || se.getSQLState().equals("42502"))
          && testSecurity) {
        Log.getLogWriter().info("Got the expected exception for authorization," +
           " continuing tests");
      } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
        Log.getLogWriter().info("Got expected column not found exception in delete, continuing test");
      } else
        SQLHelper.handleSQLException(se); //handle the exception
    }
  }
  
  //delete from table based on whichDelete
  protected int deleteFromTable(PreparedStatement stmt, int cid, int sid, 
      int tid, int whichDelete) throws SQLException {
    String txId = SQLDistTxTest.curTxId.get() == null ?"" : "TXID: " + (Integer) SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " + txId ;
    String query = " QUERY: " + delete[whichDelete];
    
    int rowCount = 0;
    switch (whichDelete) {
    case 0:  
      //"delete from trade.portfolio where cid=? and sid=? and tid=?",
      Log.getLogWriter().info(database + "deleting trade.portfolio with CID:" + cid + ",SID:" +sid +",TID:" + tid + query);
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows from trade.portfolio CID:" + cid + ",SID:" +sid +",TID:" + tid + query);
      break;
    case 1:
      Log.getLogWriter().info(database + "deleting trade.portfolio with CID:" + cid + ",SID:" +sid + query);
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      Log.getLogWriter().info(database + "deleted " + rowCount + " rows from trade.portfolio  CID:" + cid + ",SID:" +sid + query);
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
  
  public static ResultSet getQuery(Connection conn, int whichQuery, BigDecimal sub1, BigDecimal sub2, 
      int qty, int availQty, int sid, int cid, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    try {
      rs = getQuery(conn, whichQuery, sub1, sub2, qty, availQty, sid, cid, tid, success);
      int count =0;
      while (!success[0]) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
          return null; 
        }
        count++;   
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        rs = getQuery(conn, whichQuery, sub1, sub2, qty, availQty, sid, cid, tid, success);
      }
    } catch (SQLException se){
      if (!SQLHelper.isAlterTableException(conn, se)) SQLHelper.handleSQLException(se);
      //allow alter table related exceptions.
    }
    return rs;
  }
  
  protected static ResultSet query (Connection conn, int whichQuery, BigDecimal sub1, BigDecimal sub2, 
      int qty, int availQty, int sid, int cid, int tid) throws SQLException {
    boolean[] success = new boolean[1];
    ResultSet rs = getQuery(conn, whichQuery, sub1, sub2, qty, availQty, sid, cid, tid, success);
    int count =0;
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
        return null; 
      }
      count++;   
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      rs = getQuery(conn, whichQuery, sub1, sub2, qty, availQty, sid, cid, tid, success);
    }
    return rs;
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, BigDecimal sub1, BigDecimal sub2, 
      int qty, int availQty, int sid, int cid, int tid, boolean[] success) throws SQLException{
    PreparedStatement stmt;   
    ResultSet rs = null;
    success[0] = true;
    String txId = SQLDistTxTest.curTxId.get() == null ?"" : "TXID: " + (Integer) SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - " +txId;
    String query = " QUERY: " + select[whichQuery];
    try {
       
      stmt = conn.prepareStatement(select[whichQuery]);
      
      /*
      stmt = getStmt(conn, select[whichQuery]);   
      if (SQLTest.setCriticalHeap && stmt == null) {
        return null;//prepare stmt may fail due to XCL54 now
      }
      */
      switch (whichQuery){
      case 0:
        //"select * from trade.portfolio where tid = ?",
        Log.getLogWriter().info(database + "querying trade.portfolio with" + " TID:" + tid + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select sid, cid from trade.portfolio where (subTotal >? and subTotal <= ?) and tid=? ",
        Log.getLogWriter().info(database + "querying trade.portfolio with SUBTOTAL1:" +sub1 + ",SUBTOTAL2:" + sub2 
            + ",TID:" + tid + query);
        stmt.setBigDecimal(1, sub1);
        stmt.setBigDecimal(2, sub2); 
        stmt.setInt(3, tid);
        break;
      case 2:
        //"select distinct cid where (subTotal<? or subTotal >=?) and tid =?",
        Log.getLogWriter().info(database + "querying trade.portfolio with SUBTOTAL1:" +sub1 + ",SUBTOTAL2:" + sub2  
            + ",TID:" + tid + query);     
        stmt.setBigDecimal(1, sub1);
        stmt.setBigDecimal(2, sub2); 
        stmt.setInt(3, tid);
        break;
      case 3:
        //"select distinct sid where (qty >=? and subTatal >= ?) and tid =?",
        Log.getLogWriter().info(database + "querying trade.portfolio with SUBTOTAL1:" +sub1 + "QTY:" + qty +  ",TID:" + tid + query);     
        stmt.setInt(1, qty);
        stmt.setBigDecimal(2, sub1);
        stmt.setInt(3, tid);
        
        break;
      case 4:
        //"select sid, cid, qty from trade.portfolio  where (qty >=? and availQty<?) and tid =?",
        Log.getLogWriter().info(database +  "querying trade.portfolio with QTY:" + qty + ",AVAILQTY:" + availQty +  ",TID:" + tid + query);     
        stmt.setInt(1, qty);
        stmt.setInt(2, availQty);
        stmt.setInt(3, tid);
        break;
      case 5: 
        //"select * from trade.portfolio where sid =? and cid=? and tid = ?",
        Log.getLogWriter().info(database +  "querying trade.portfolio with SID:" + sid + ",CID:" + cid +  ",TID:" + tid + query + query);
        stmt.setInt(1, sid);
        stmt.setInt(2, cid);
        stmt.setInt(3, tid);
        break;
      case 6:
        //"select * from trade.portfolio",      
        break;
      case 7: 
        //"select sid, cid from trade.portfolio where (subTotal >? and subTotal <= ?)",
        Log.getLogWriter().info(database +  "querying trade.portfolio with SUBTOTAL1:" +sub1 + ",SUBTOTAL2:" + sub2  + query);
        stmt.setBigDecimal(1, sub1);
        stmt.setBigDecimal(2, sub2); 
        break;
      case 8:
        //"select distinct cid where (subTotal<? or subTotal >=?)",
        Log.getLogWriter().info(database + "querying trade.portfolio with SUBTOTAL1:" +sub1 + ",SUBTOTAL2:" + sub2   + query);
        stmt.setBigDecimal(1, sub1);
        stmt.setBigDecimal(2, sub2); 
        break;
      case 9:
        //"select distinct sid where (qty >=? and subTatal >= ?)",
        Log.getLogWriter().info(database + "querying trade.portfolio with SUBTOTAL1:" +sub1 + ",QTY:" + qty + query);
        stmt.setInt(1, qty);
        stmt.setBigDecimal(2, sub1);
        break;
      case 10:
        //"select sid, cid, qty from trade.portfolio  where (qty >=? and availQty<?)",
        Log.getLogWriter().info(database +  "querying trade.portfolio with QTY:" + qty + ",AVAILQTY:" + availQty + query);
        stmt.setInt(1, qty);
        stmt.setInt(2, availQty);
        break;
      case 11: 
        Log.getLogWriter().info(database + "data will be used in the query SID:" + sid + ",CID:" + cid + query);
        stmt.setInt(1, sid);
        stmt.setInt(2, cid);
        break;
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471
      else throw se;
    }
    return rs;
  }
  
  public static int[] getKey(Connection regConn) {
    Connection conn = getAuthConn(regConn);
    int[] key = new int[2]; //for sid and cid
    ResultSet rs = null;

    try {
       rs = getQuery(conn, 0, null, null, 0, 0, 0, 0, getMyTid());     //get the first query results from gfe connection 
       if (rs !=null && rs.next()) {
         key[0] = rs.getInt("sid");
         key[1] = rs.getInt("cid");
       }
       if (rs !=null) rs.close();
    } catch (SQLException se) {
      if (!SQLHelper.checkGFXDException(conn, se)) return null; //did not get key
      else SQLHelper.handleSQLException(se); //throws TestException.
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return key;
  }
  
  protected int getWhichUpdate(int numOfNonUniq, int total) {
    return getWhichOne(numOfNonUniq, total);
  }
  
  @Override
  public void put(Connection dConn, Connection gConn, int size) {
    int cid=0;
    if(dConn !=null) {
      if (rand.nextInt(10) == 0)  cid = getCid(dConn);  
      else cid = getCid(gConn);
    }
    else cid = getCid(gConn);
    
    if (cid !=0)  //TODO comment out this line to reproduce bug   
      //insert(dConn, gConn, size, cid, true);
      insert(dConn, gConn, size, cid, false); 
      //disallow put in portfolio table to avoid issue when row with duplicate pk
      //failed in derby but succeed in gfxd cause inconsistency
  }
}
