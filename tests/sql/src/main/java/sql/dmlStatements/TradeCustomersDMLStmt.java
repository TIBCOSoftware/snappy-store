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
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.security.SQLSecurityTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

/**
 * @author eshu
 *
 */
public class TradeCustomersDMLStmt extends AbstractDMLStmt {
  /*
   * trade.customers table fields
   *   private int cid;
   *   String cust_name;
   *   Date since;
   *   String addr;
   *   int tid; //for update or delete unique records
   */
  
  protected static String insert = "insert into trade.customers (cid, cust_name, since, addr, tid) values (?,?,?,?,?)";
  protected static String put = "put into trade.customers values (?,?,?,?,?)";
  protected static String s_insert = "insert into trade.customers values ";
  protected static String s_put = "put into trade.customers values ";
  protected static String insertWithDefault = "insert into trade.customers " +
  		"(cust_name, since, addr, tid) values (?,?,?,?)";
  protected static String putWithDefault = "put into trade.customers " +
      "(cust_name, since, addr, tid) values (?,?,?,?)";
  protected static String insertWithNullDefault = "insert into trade.customers " +
  "(cid, cust_name, since, tid) values (?,?,?,?)";
  protected static String putWithNullDefault = "put into trade.customers " +
      "(cid, cust_name, since, tid) values (?,?,?,?)";  
  protected static String[] update = {
    "update trade.customers set cid = ? where cid=? and tid = ? ", 
    "update trade.customers set cust_name = ? , addr = ? where cid=? and tid =?", 
    "update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",
    "update trade.customers set cust_name = ?, since =? where cid=? and tid =? "
    };

  protected static String[] select = {
    "select * from trade.customers where tid = ? " + queryLimitedNumberOfRows(),
    "select cid, since, cust_name from trade.customers where tid=? and cid >?" + queryLimitedNumberOfRows(),
    "select cid, since, addr, cust_name from trade.customers where (tid<? or cid <=?) and since >? and tid = ?" + queryLimitedNumberOfRows(),
    "select cid, addr, since, cust_name from trade.customers where (cid >? or since <?) and tid = ?" + queryLimitedNumberOfRows(),
    "select max(rtrim(addr)) as addr from trade.customers where addr < 'kj' and tid=? ",
    "select cid, since, cust_name from trade.customers where tid =? and cust_name like ? " +
    "union " +
    "select cid, since, cust_name from trade.customers where tid =? and cust_name like ? "  
  };

  protected static String[] delete = {
    //uniq
    "delete from trade.customers where (cust_name = ? or cid = ? ) and tid = ?",
    "delete from trade.customers where cid=?",
    //non uniq
    "delete from trade.customers where cid=?",
    }; //used in concTest without verification 
  protected static int maxNumOfTries = 1;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  protected static ArrayList<String> partitionKeys = null;
  protected boolean generateIdAlways = SQLTest.generateIdAlways;
  protected boolean generateDefaultId = SQLTest.generateDefaultId;
  protected boolean batchPreparedStmt = TestConfig.tab().booleanAt(SQLPrms.batchPreparedStmt, true);
  protected static boolean disableUpdateStatement48248 = SQLTest.disableUpdateStatement48248;
  
  //limit the number of rows to compare for large data set inserted
  protected static String queryLimitedNumberOfRows() {
    return SQLTest.populateWithbatch? " order by cid OFFSET " + 10000 / (RemoteTestModule.getMyVmid() + 1)  
        + " ROWS fetch next 1000 rows only" : "" ;    
  }
  
  public TradeCustomersDMLStmt(){
    
  }
  
  //---- implementations of interface declared methods ----//

  /**
   * The method got specific key from BB and insert a new record
   */
  public void insert(Connection dConn, Connection gConn, int size) {
    if (hasNetworth)
      insert(dConn, gConn, size, true);
    else
      insert(dConn, gConn, size, false);
  }

  protected void insert(Connection dConn, Connection gConn, int size, boolean insertNetworth) {
    int[] cid = new int[size];
    String[] cust_name = new String[size];
    Date[] since = new Date[size];
    String[] addr = new String[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    boolean success = false;
    getDataForInsert(cid, cust_name,since,addr, size); //get the data
    boolean executeBatch = ( !testSecurity && rand.nextBoolean() ) || SQLTest.populateWithbatch ;  
    //boolean executeBatch = false;
    boolean usePreparedStatementForBatch = batchPreparedStmt && rand.nextBoolean();
      
    if (setCriticalHeap) resetCanceledFlag();
    
    if (dConn != null && !useWriterForWriteThrough) {
      if (!generateDefaultId && !generateIdAlways) {
        try {
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not finish insert into derby, abort this operation");
              rollback(dConn); 
              if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
              //expect gfxd fail with the same reason due to alter table
              else return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList .clear();
            if (executeBatch) {
              if (usePreparedStatementForBatch)
                success = insertPreparedBatchToDerbyTable(dConn, cid, cust_name,since, size, exList);
              else 
                success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            } else {
                success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            }
            count++;
          }
  
          try {
            if (executeBatch) {
              if (usePreparedStatementForBatch)
                insertPreparedBatchToGFETable(gConn, cid, cust_name,since, size, exList);
              else
                insertBatchToGFETable(gConn, cid, cust_name,since, addr, size, exList);
            } else insertToGFETable(gConn, cid, cust_name,since, addr, size, exList);    //insert to gfe table 
          } catch (TestException te) {
            if (te.getMessage().contains("Execute SQL statement failed with: 23505")
                && isHATest && SQLTest.isEdge) {
              //checkTicket49605(dConn, gConn, "customers");
              try {
                checkTicket49605(dConn, gConn, "customers", cid[0], -1, null, null);
              } catch (TestException e) {
                Log.getLogWriter().info("insert failed due to #49605 ", e);
                //deleteRow(dConn, gConn, "buyorders", cid[0], -1, null, null);
                //use put to work around #49605
                Log.getLogWriter().info("retry this using put to work around #49605");
                if (executeBatch) {
                  if (usePreparedStatementForBatch)
                    insertPreparedBatchToGFETable(gConn, cid, cust_name,since, size, exList, true);
                  else
                    insertBatchToGFETable(gConn, cid, cust_name,since, addr, size, exList, true);
                } else insertToGFETable(gConn, cid, cust_name,since, addr, size, exList, true);
                
                checkTicket49605(dConn, gConn, "customers", cid[0], -1, null, null);
              }
            } else throw te;
          }
          SQLHelper.handleMissedSQLException(exList);
          if (SQLTest.setTx && isHATest) {
            commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
            if (dConn !=null) commit(dConn);
          } else {    
            if (dConn !=null) commit(dConn); //to commit and avoid rollback the successful operations
            commit(gConn);
          }
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to trade.customers fails\n" + TestHelper.getStackTrace(se));
        }  //for verification
      } else if (generateIdAlways){
        try {
          insertToGfxdTableGenerateId(gConn, cid, cust_name, since, addr, size);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            Log.getLogWriter().info("Could not insert into gfxd due to node failure exception, abort this op");
            return;
          }
                    
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
              boolean deleted = removeCidFromGfxd(gConn, cid);
              while (!deleted) {
                deleted = removeCidFromGfxd(gConn, cid);
                
                if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
                  //txn without HA/fail over support, when querying
                  Log.getLogWriter().info("Could not delete the newly inserted row due to node failure " +
                      "exception, but with txn, the insert would fail due to rollback, abort this op");
                  //rollback(gConn);
                  //no need for rollback -- currently txn failure will rollback whole txn automatically
                  return;
                }
              }
              return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList .clear();
            if (executeBatch)
              success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            else
              success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            
            count++;
          }
          SQLHelper.handleMissedSQLException(exList);
          commit(gConn);
          if (dConn !=null) commit(dConn); //to commit and avoid rollback the successful operations    
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to trade.customers fails\n" + TestHelper.getStackTrace(se));
        }
      } else{
        try {
          insertToGfxdTableGenerateId(gConn, cust_name, since, addr, size);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            Log.getLogWriter().info("Could not insert into gfxd due to node failure exception, abort this op");
            return;
          }
          
          String sql = "select max(cid) cid from trade.customers where tid = " + getMyTid();
          boolean gotCid = getGeneratedCid(gConn, cid, sql);
          while (!gotCid) {
            gotCid = getGeneratedCid(gConn, cid, sql);
            
            if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
              //txn without HA/fail over support, when querying
              Log.getLogWriter().info("Could not get generated cid due to node failure exception, abort this op");
              //rollback(gConn);
              //no need for rollback -- currently txn failure will rollback whole txn automatically
              return;
            }
          }
          
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
              boolean deleted = removeCidFromGfxd(gConn, cid);
              while (!deleted) {
                removeCidFromGfxd(gConn, cid);
                if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
                  //txn without HA/fail over support, when querying
                  Log.getLogWriter().info("Could not get generated cid due to node failure exception, abort this op");
                  //rollback(gConn);
                  //no need for rollback -- currently txn failure will rollback whole txn automatically
                  return;
                }
              }
              return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList.clear();
            if (executeBatch)
              success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            else
              success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            
            count++;
          }
          
          SQLHelper.handleMissedSQLException(exList);
          commit(gConn);
          if (dConn !=null) commit(dConn); //to commit and avoid rollback the successful operations    
        } catch (SQLException se) {
          if (SQLTest.setTx && isHATest && SQLHelper.gotTXNodeFailureException(se)) {
            Log.getLogWriter().warning("Got node failure exception during inserting using generated id to gfxd " +
            		"-- derby should not insert the row yet");
            return;
          } else {
            SQLHelper.printSQLException(se);
            throw new TestException ("insert to trade.customers fails\n" + TestHelper.getStackTrace(se));
          }
        }
      }  
    }
    else {
      try {
        if (generateIdAlways) {
          insertToGfxdTableGenerateId(gConn, cid, cust_name, since, addr, size);
          Log.getLogWriter().info("cid[0] is " + cid[0] );
        }
        else if (generateDefaultId)
          insertToGfxdTableGenerateId(gConn, cust_name, since, addr, size);
        else 
          insertToGFETable(gConn, cid, cust_name,since, addr, size);    //insert to gfe table 
      } catch (SQLException se) {
        if (SQLTest.setTx && isHATest && SQLHelper.gotTXNodeFailureException(se)) {
          Log.getLogWriter().warning("Got node failure exception during inserting using generated id to gfxd ");
        } else {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to gfe trade.customers fails\n" + TestHelper.getStackTrace(se));
        }
      }
    } //no verification for gfxd only case or user writer -- no writer invocation when exception is thrown
    //after bug fixed for existing tests, we may change this to apply for all tests 
    if (insertNetworth) {
      //also insert into networth table
      
      TradeNetworthDMLStmt networth = new TradeNetworthDMLStmt();
      
      //get correct cid for networth when default keys are used
      String sql = null; 
      if (generateIdAlways) {
        //cid should be generated already,
        
        sql = "select cid from trade.customers where tid = " + getMyTid() + 
           " and cid not in (select cid from trade.networth where tid = " + getMyTid() + ")";
        boolean gotCid = getGeneratedCid(gConn, cid, sql);
        while (!gotCid) {
          gotCid = getGeneratedCid(gConn, cid, sql);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            //txn without HA/fail over support, when querying
            Log.getLogWriter().info("Could not get generated cid due to node failure exception, abort this op");
            //rollback(gConn);
            //no need for rollback -- currently txn failure will rollback whole txn automatically
            return;
          }
        }
      }
      else if (generateDefaultId) {
        sql = "select max(cid) cid from trade.customers where tid = " + getMyTid();
        boolean gotCid = getGeneratedCid(gConn, cid, sql);
        while (!gotCid) {
          gotCid = getGeneratedCid(gConn, cid, sql);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            //txn without HA/fail over support, when querying
            Log.getLogWriter().info("Could not get generated cid due to node failure exception, abort this op");
            //rollback(gConn);
            //no need for rollback -- currently txn failure will rollback whole txn automatically
            return;
          }
        }
      }
      
      networth.insert(dConn, gConn, size, cid);
      
      if (SQLTest.setTx && isHATest) {
        commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
        if (dConn!=null) commit(dConn);
      } else {     
        if (dConn!=null) commit(dConn); //to commit and avoid rollback the successful operations
        commit(gConn);
      }
    }    
  }
  
  private boolean insertPreparedBatchToDerbyTable(Connection conn, int[] cid,
      String[] cust_name, Date[] since, int size,
      List<SQLException> exList) throws SQLException {
    PreparedStatement stmt = getStmt(conn, insertWithNullDefault);
    if (stmt == null) return false; //possibly due to alter table 
    
    int tid = getMyTid();
    int counts[] = null;
    
    for (int i=0 ; i<size ; i++) { 
      try {
        stmt.setInt(1, cid[i]);
        stmt.setString(2, cust_name[i]);
        if (testworkaroundFor51519) stmt.setDate(3, since[i], getCal());
        else stmt.setDate(3, since[i]);
        stmt.setInt(4, tid);
        stmt.addBatch();
        Log.getLogWriter().info("Derby - batch insert into trade.customers with CID:" + cid[i] + ",CUST_NAME:" + cust_name[i] +
              ",SINCE:" + since[i] + ",ADDR:NULL" + ",TID:" + tid);
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exList);      
      }
    }

    try {
      counts = stmt.executeBatch(); 
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se,exList);      
        if (se instanceof BatchUpdateException) counts=((BatchUpdateException)se).getUpdateCounts();
    } 
    
    for (int i =0; i<counts.length; i++) {  
      if (counts[i] != -3)
         Log.getLogWriter().info("Derby - batch inserted into trade.customers CID:" + cid[i] + ",CUST_NAME:" + cust_name[i] +
                 ",SINCE:" + since[i] + ",ADDR:NULL" + ",TID:" + tid);  
      verifyRowCount.put(tid+"_insert"+i, 0);
      verifyRowCount.put(tid+"_insert"+i, new Integer(counts[i]));
      //Log.getLogWriter().info("Derby inserts " + verifyRowCount.get(tid+"_insert" +i) + " rows");
    }
    return true;
  }

  private void insertPreparedBatchToGFETable(Connection conn, int[] cid, String[] cust_name, Date[] since, int size, List<SQLException> exList) 
      throws SQLException {
    insertPreparedBatchToGFETable(conn, cid, cust_name, since, size, exList, false);
  }
  private void insertPreparedBatchToGFETable(Connection conn, int[] cid, String[] cust_name, Date[] since, int size, List<SQLException> exList, 
      boolean isPut) 
      throws SQLException {
    PreparedStatement stmt = getStmt(conn, isPut ? putWithNullDefault : insertWithNullDefault);
    
    if (SQLTest.testSecurity && stmt == null) {
      SQLHelper.handleGFGFXDException((SQLException)
          SQLSecurityTest.prepareStmtException.get(), exList);
      SQLSecurityTest.prepareStmtException.set(null);
      return;
    } //work around #43244
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
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
    int counts[] = null;
    
    for (int i=0 ; i<size ; i++) { 
      try {
        stmt.setInt(1, cid[i]);
        stmt.setString(2, cust_name[i]);
        if (testworkaroundFor51519) stmt.setDate(3, since[i], getCal());
        else stmt.setDate(3, since[i]);
        stmt.setInt(4, tid);
        stmt.addBatch();
        Log.getLogWriter().info("gemfirexd - batch " + (isPut ? "putting" : "inserting") + " into trade.customers CID:" + cid[i] + ",CUST_NAME:" + cust_name[i] +
              ",SINCE:" + since[i] + ",ADDR:NULL" + ",TID:" + tid);
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }

    try {
      counts = stmt.executeBatch(); 
      if (counts == null) throw new TestException("gemfirexd -  execute batch succeeded but not return an array of update counts");
      if (counts.length < 1) {
        throw new TestException("gemfirexd - execute batch succeeded but returned zero length array of update counts");
      }      
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      SQLHelper.handleGFGFXDException(se, exList);     
        if (se instanceof BatchUpdateException) counts=((BatchUpdateException)se).getUpdateCounts();
    } 
    
    if (setCriticalHeap) {
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled != null && getCanceled[0]) {
        Log.getLogWriter().info("avoid verify gfxd batching results due to cancelled query op");
        return;
      }
    }
    
    verifyAndLog(cid, cust_name, since, isPut, tid, counts); 
  }

  protected boolean removeCidFromGfxd(Connection gConn, int[] cids) {
    String sql = "delete from trade.customers where cid=?";
    try {
      PreparedStatement stmt = gConn.prepareStatement(sql);
      for (int cid: cids) {  
        Log.getLogWriter().info("gemfirexd - deleting trade.customer with CID:" + cid); 
        stmt.setInt(1, cid);
        int rowCount = stmt.executeUpdate();
        Log.getLogWriter().info("gemfirexd - deleted " + rowCount + " from trade.customer with CID:" + cid);
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01")) return false; //does not get id
      else SQLHelper.handleSQLException(se);
    }
    return true;
  }
  
  protected boolean getGeneratedCid(Connection conn, int[] cid, String sql) {
    ResultSet rs = null;
    
    if (generateIdAlways) {
      Log.getLogWriter().info("Generated id from insert statement is " + cid[0]);
    }
    
    try {
      Log.getLogWriter().info(sql);
      rs = conn.createStatement().executeQuery(sql);
      if (rs.next()) {
        int resultCid = rs.getInt("CID");
        Log.getLogWriter().info("Query results shows newly generated cid should be: " + resultCid);
        
        if (!generateIdAlways) {
          cid[0] = resultCid;
        }
      } 
      if (rs.next()) {
        if (RemoteTestModule.getCurrentThread().getCurrentTask().getTaskTypeString().
            equalsIgnoreCase("INITTASK") || (generateDefaultId && !generateIdAlways))
          throw new TestException("In init task, query result from the query " + sql + 
            " should get only one result but it gets more rows -- another row is " + 
            rs.getInt("CID"));
        else {
          //this is OK as some of the networth row might be deleted already
          //this may insert a row that has been deleted instead of the one that
          //just generated by gfxd (if use generate id always)
        }
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("X0Z01")) return false; //does not get id
      else SQLHelper.handleSQLException(se);
    } finally {
      SQLHelper.closeResultSet(rs, conn);
    }
    return true;
  }
  
  public void update(Connection dConn, Connection gConn, int size){   
    int[] cid = new int[size];
    int[] newCid = new int[size];
    String[] cust_name = new String[size];
    Date[] since = new Date[size];
    String[] addr = new String[size]; 
    
    int[]  whichUpdate = new int[size];
    List<SQLException>  exceptionList = new ArrayList<SQLException>();
    
    if (dConn != null && !useWriterForWriteThrough) {     
      if (rand.nextInt(numGettingDataFromDerby) == 1) 
        getDataForUpdate(dConn, newCid, cid, cust_name, since, addr, whichUpdate, size); //get the data
      else 
        getDataForUpdate(gConn, newCid, cid, cust_name, since, addr, whichUpdate, size);
      
      if (setCriticalHeap) resetCanceledFlag();
      
      int count =0;
      boolean success = updateDerbyTable(dConn, newCid, cid, cust_name, since, addr, whichUpdate, size, exceptionList);  //insert to derby table  
      while (!success) {
        if (count>=maxNumOfTries) {
          Log.getLogWriter().info("Could not get the lock to finish update in derby, abort this operation");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
          //expect gfxd fail with the same reason due to alter table
          else return;
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        exceptionList .clear();
        success = updateDerbyTable(dConn, newCid, cid, cust_name, since, addr, whichUpdate, size, exceptionList);  //insert to derby table  
        count++;
      } //retry until this update will be executed.
      updateGFETable(gConn, newCid, cid, cust_name, since, addr, whichUpdate, size, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      getDataForUpdate(gConn, newCid, cid, cust_name, since, addr, whichUpdate, size); //get the da
      updateGFETable(gConn, newCid,  cid, cust_name, since, addr, whichUpdate, size);
    } //no verification for gfxd only or user writer case
  
  }
  
  //implement the method for delete
  public void delete(Connection dConn, Connection gConn){
    String cust_name = "name" + rand.nextInt((int)SQLBB.getBB().
        getSharedCounters().read(SQLBB.tradeCustomersPrimary));
    
    int cid = (dConn !=null && !useWriterForWriteThrough && 
        rand.nextInt(numGettingDataFromDerby) == 1) ? 
            getCid(dConn) : getCid(gConn); //if test uniq, cid will be either 0 or one inserted by this thread
    List<SQLException> exList = new ArrayList<SQLException>(); //exception list to be compared.
    int numOfNonUniqDelete = 1;  //how many delete statement is for non unique keys    
    int whichDelete = getWhichOne(numOfNonUniqDelete, delete.length);
    
    if (SQLTest.syncHAForOfflineTest && whichDelete == 0) whichDelete = 1; //avoid #39605 
    
    if (setCriticalHeap) resetCanceledFlag();
    
    if (dConn!=null && !useWriterForWriteThrough) {
      //for verification both connections are needed
      boolean success = deleteFromDerbyTable(dConn, cid, cust_name, whichDelete, exList);
      int count = 0;
      while (!success) {
        if (count >= maxNumOfTries) {
          Log.getLogWriter().info("Could not finish the op in derby, will abort this operation in derby");
          if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
          //expect gfxd fail with the same reason due to alter table
          else return;
        }
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        count++;    
        exList.clear();
        success = deleteFromDerbyTable(dConn, cid, cust_name, whichDelete, exList);
      }
      deleteFromGFETable(gConn, cid, cust_name, whichDelete, exList);
      SQLHelper.handleMissedSQLException(exList);      
    } else {
      //no verification
      deleteFromGFETable(gConn, cid, cust_name, whichDelete);
    }
  }
  
  public void query(Connection dConn, Connection gConn) {
    //  for testUniqueKeys both connections are needed
    int whichQuery = rand.nextInt(select.length); //randomly select one query sql
    int cid = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary));
    //Date since = new Date ((rand.nextInt(10)+98),rand.nextInt(12), rand.nextInt(31));
    Date since = getSince();
    ResultSet discRS = null;
    ResultSet gfeRS = null;
    ArrayList<SQLException> exceptionList = new ArrayList<SQLException>();
    
    if (dConn!=null) {
      try {
        discRS = query(dConn, whichQuery, cid, since);
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
        gfeRS = query (gConn, whichQuery, cid, since);   
        if (gfeRS == null) {
          if (isHATest) {
            Log.getLogWriter().info("Testing HA and did not get GFXD result set");
            return;
          } else if (setCriticalHeap) {
            Log.getLogWriter().info("got XCL54 and does not get query result");
            return; //prepare stmt may fail due to XCL54 now
          } /*if (alterTableDropColumn) {
            Log.getLogWriter().info("prepare stmt failed due to missing column");
            return; //prepare stmt may fail due to alter table now
          } */ else     
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
        gfeRS = query (gConn, whichQuery, cid, since);   //could not varify results.
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
  public void populate (Connection dConn, Connection gConn) {
    int size = TestConfig.tab().intAt(SQLPrms.initCustomersSizePerThread, 100);
    
    int batchSize = SQLTest.populateWithbatch ? 5000 : 1;
    if (!generateDefaultId && !generateIdAlways && rand.nextBoolean()) populate (dConn, gConn, size); //batch insert could be executed
    else {
      for (int i =0; i<size; i+=batchSize) {
        Log.getLogWriter().info("executing batch insert for " + i );
        insert(dConn, gConn, batchSize);
        Log.getLogWriter().info("executed batch insert for " + i );
      }
    }    
  }
  
  public void populate (Connection dConn, Connection gConn, int initSize) {
    if (setCriticalHeap) resetCanceledFlag();
    insert(dConn, gConn, initSize);
    commit(gConn); 
    Log.getLogWriter().info("gemfirexd committed");
    if (dConn != null) {
      commit(dConn);
      Log.getLogWriter().info("derby committed");
    }
  }
  
  //---- other methods ----//
  
  //randomly populate the cid[] from the existing keys -- some keys might be deleted from delete statement
  protected void getRandomExistingPrimaryKeys(int[] cid, int size) {
    int key = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary);
    if (key < size) {
      size = key;
    }
    for (int i=0; i<size; i++) {
      cid[i] = rand.nextInt(key) + 1; 
      //duplicate primary keys can be returned, a record may be updated multi times in one op
    }  
  }
  
  protected boolean updateDerbyTable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int size, List<SQLException> exceptions) {
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    
    boolean[] unsupported = new boolean[1];
    for (int i=0 ; i<size ; i++) {
      
      if (whichUpdate[i]== 0) {
        Log.getLogWriter().info("this update primary key/partiton key case in gfe, do not update");
      } //nedd to comment out this portion of code when gemfirexd supports update primary key
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
          count = updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
          verifyRowCount.put(tid+"_update"+i, new Integer(count));
          
        }
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) { //handles the deadlock of aborting
          Log.getLogWriter().info("detected the deadlock, will try it again");
          return false;
        } else
            SQLHelper.handleDerbySQLException(se, exceptions);
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
      partitionKeys= (ArrayList<String>)partitionMap.get("customersPartition");
    }
    else {
      int myWanSite = getMyWanSite();
      partitionKeys = (ArrayList<String>)wanPartitionMap.
        get(myWanSite+"_customersPartition");
    }
    Log.getLogWriter().info("partition keys are " + partitionKeys);
  }
  
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys, boolean[] unsupported){
    PreparedStatement stmt = null;
    switch (whichUpdate) {
    case 0: 
      //    "update trade.customers set cid = ? where cid=? ", 
      //should not happen
      break;
    case 1: 
      // "update trade.customers set cust_name = ? where cid=? and tid =1", 
      if (partitionKeys.contains("cust_name")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: //update name, addr
      //"update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",
      if (partitionKeys.contains("cust_name") || partitionKeys.contains("addr")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: //update name, since
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid =? " 
      if (partitionKeys.contains("cust_name") || partitionKeys.contains("since")) {
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
  
  //checking exception list
  protected void updateGFETable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int size, List<SQLException> exceptions) {
    PreparedStatement stmt = null;
    Statement s = getStmt(conn);
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      
      
      if (whichUpdate[i] == 0) {
        Log.getLogWriter().info("Updating gemfirexd on primary key, expect exception thrown here");
        Log.getLogWriter().info("update partition key statement is " + update[whichUpdate[i]]);
        stmt = getUnsupportedStmt(conn, update[whichUpdate[i]]);
        
        if (SQLTest.testSecurity && stmt == null) {
        	if (SQLSecurityTest.prepareStmtException.get() != null) {
        	  //relax here as 0A000 unsupported or 42502 authorization failure
        		//has the same effect -- no update will be successful
        	  return;
        	}
        } //work around #43244
        
      }//need to comment out these code when update primary key is supported later
      else {
        if (SQLTest.testPartitionBy) { //will be removed after bug #39913 is fixed
          stmt = getCorrectStmt(conn, whichUpdate[i], null);
        } 
        else {
          stmt = getStmt(conn, update[whichUpdate[i]]);
        }
        
        if (SQLTest.testSecurity && stmt == null) {
        	if (SQLSecurityTest.prepareStmtException.get() != null) {
        	  SQLHelper.handleGFGFXDException((SQLException)
        			SQLSecurityTest.prepareStmtException.get(), exceptions);
        	  SQLSecurityTest.prepareStmtException.set(null);
        	  return;
        	}
        } //work around #43244
        if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
          Log.getLogWriter().info("prepare stmt failed due to XCL54");
          return; //prepare stmt may fail due to XCL54 now
        }
        
        try {
          if (stmt!=null) {
            if (rand.nextBoolean())
              count = updateTable(s, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
            else
              count = updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
            if (count != ((Integer)verifyRowCount.get(tid+"_update"+i)).intValue()){
              String str = "Gfxd update has different row count from that of derby " +
                      "derby updated " + ((Integer)verifyRowCount.get(tid+"_update"+i)).intValue() +
                      " but gfxd updated " + count;
              if (failAtUpdateCount && !isHATest) throw new TestException (str);
              else Log.getLogWriter().warning(str);
            }
          }
        } catch (SQLException se) {
          SQLHelper.handleGFGFXDException(se, exceptions);  
        }    
      }
    }  
  }
  
  //no checking exception list
  protected void updateGFETable(Connection conn, int[] newCid, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int[] whichUpdate, int size) {
    PreparedStatement stmt = null;
    Statement s = getStmt(conn);
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      
      if (whichUpdate[i] == 0) {
        
        stmt = getUnsupportedStmt(conn, update[whichUpdate[i]]);
      }
      else {
        if (SQLTest.testPartitionBy) { //will be removed after bug #39913 is fixed
          stmt = getCorrectStmt(conn, whichUpdate[i], null);
        } 
        else {   
          stmt = getStmt(conn, update[whichUpdate[i]]);
        }
        
        try {
          if (stmt!=null)
            if (rand.nextBoolean() && !disableUpdateStatement48248)
              updateTable(s, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
            else
              updateTable(stmt, newCid[i], cid[i], cust_name[i], since[i], addr[i], tid, whichUpdate[i]);
        } catch (SQLException se) {
          if (se.getSQLState().equals("42502") && testSecurity) {
            Log.getLogWriter().info("Got the expected exception for authorization," +
               " continuing tests");
          } else if (alterTableDropColumn && se.getSQLState().equals("42X14")) {
            Log.getLogWriter().info("Got expected column not found exception in update, continuing test");
          } else
            SQLHelper.handleSQLException(se);
        }    
      }
    }  
  }
  
  
  //update the record into database
  protected int updateTable(PreparedStatement stmt, int newCid, int cid, String cust_name,
      Date since, String addr, int tid, int whichUpdate) throws SQLException {    
    int rowCount = 0;
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";  
    String query = " QUERY: " + update[whichUpdate];
    
    switch (whichUpdate) {
    case 0: 
      //    "update trade.customers set cid = ? where cid=? ", 
      Log.getLogWriter().info(database + "updating trade.customers with CID:" + newCid + " where CID:" + cid + query);
      stmt.setInt(1, newCid);
      stmt.setInt(2, cid);  //the cid got was inserted by this thread before
      stmt.setInt(3, RemoteTestModule.getCurrentThread().getThreadId());
      // stmt.executeUpdate();    //uncomment this to produce bug 39313 or 39666
      break;
    case 1: 
      // "update trade.customers set cust_name = ? , addr =? where cid=? and tid =?", 
      Log.getLogWriter().info(database + "updating trade.customers with CUSTNAME:" + cust_name + 
          ",ADDR:" + addr + " where CID:" + cid + ",TID:" + tid + query); //use update count to see if update successful of not
      stmt.setString(1, cust_name);
      stmt.setString(2, addr);
      stmt.setInt(3, cid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();   //may or may not be successful, depends on the cid and tid
      Log.getLogWriter().info(database + "updated " + rowCount + " in  trade.customers CUSTNAME:" + cust_name + 
          ",ADDR:" + addr + " where CID:" + cid + ",TID:" + tid + query);
      break;
    case 2: //update name, addr
      //"update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",

        Log.getLogWriter().info(database + "updating trade.customers with CUSTNAME:" + cust_name + 
            ",ADDR:" + addr +  " where CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        stmt.setString(1, cust_name);
        stmt.setString(2, addr);
        stmt.setInt(3, cid);
        stmt.setInt(4, tid);
        rowCount = stmt.executeUpdate();
        Log.getLogWriter().info(database + "updated " + rowCount + " in  trade.customers with CUSTNAME:" + cust_name + 
            ",ADDR:" + addr +  " where CID:" + cid  + ",TID:" + tid + query);
      break;
    case 3: //update name, since
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid =? " 
        Log.getLogWriter().info(database + "updating trade.customers with CUSTNAME:" + cust_name + 
            ",SINCE:" + since +  " where CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        stmt.setString(1, cust_name);
        if (testworkaroundFor51519) stmt.setDate(2, since, getCal());
        else stmt.setDate(2, since);
        stmt.setInt(3, cid);
        stmt.setInt(4, tid);  
        rowCount = stmt.executeUpdate();
        Log.getLogWriter().info(database + "updated " + rowCount + " in  trade.customers CUSTNAME:" + cust_name + 
            ",SINCE:" + since +  " where CID:" + cid  + ",TID:" + tid + query);
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

  //update the record into database
  protected int updateTable(Statement stmt, int newCid, int cid, String cust_name,
      Date since, String addr, int tid, int whichUpdate) throws SQLException {    
    int rowCount = 0;
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";  
    String query = " QUERY: " + update[whichUpdate];
    switch (whichUpdate) {
    case 0:       
      /*
      rowCount = stmt.executeUpdate("update trade.customers" +
          " set cid =" + newCid +
          " where cid=" + cid +
          " and tid =" + tid);
      */ //uncomment this to produce bug 39313 or 39666
      break;
    case 1: 
      // "update trade.customers set cust_name = ? , addr =? where cid=? and tid =?", 
      Log.getLogWriter().info(database + "updating trade.customers with CUST_NAME:" + cust_name + 
          ",ADDR:" + addr + "where CID:" + cid + ",TID:" + tid + query); //use update count to see if update successful of not
      rowCount = stmt.executeUpdate("update trade.customers" +
          " set cust_name ='" + cust_name +
          "' , addr ='" + addr +
          "' where cid=" + cid +
          " and tid =" + tid); //may or may not be successful, depends on the cid and tid     
      Log.getLogWriter().info(database + "updated " + rowCount + " in trade.customers CUST_NAME:" + cust_name + 
          ",ADDR:" + addr + "where CID:" + cid + ",TID:" + tid + query);
      break;
    case 2: //update name, addr
      //"update trade.customers set cust_name = ? , addr = ? where cid=? and tid =? ",
        Log.getLogWriter().info(database + "updating trade.customers with CUST_NAME:" + cust_name + 
            ",ADDR:" + addr +  " where CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        rowCount = stmt.executeUpdate("update trade.customers" +
            " set cust_name ='" + cust_name +
            "' , addr ='" + addr +
            "' where cid=" + cid +
            " and tid =" + tid);
        Log.getLogWriter().info(database + "updated " + rowCount + "in trade.customers  CUST_NAME:" + cust_name + 
            ",ADDR:" + addr +  " where CID:" + cid  + ",TID:" + tid + query);
      break;
    case 3: //update name, since
      //"update trade.customers set cust_name = ?, since =? where cid=? and tid =? " 
        Log.getLogWriter().info(database + "updating trade.customers with CUST_NAME:" + cust_name + 
            ",SINCE:" + since +  " where CID:" + cid  + ",TID:" + tid + query); //use update count to see if update successful of not
        rowCount = stmt.executeUpdate("update trade.customers" +
            " set cust_name ='" + cust_name +
            "' , since ='" + since +
            "' where cid=" + cid +
            " and tid =" + tid);
        Log.getLogWriter().info(database + "updated " + rowCount + " rows in trade.customers CUST_NAME:" + cust_name + 
            ",SINCE:" + since +  " where CID:" + cid  + ",TID:" + tid + query);
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

  /*
  //get data from the resultSet
  protected int getDataFromResultSet(ResultSet rs, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size){
    int[] offset = new int[1];
    ArrayList result = (ArrayList) ResultSetHelper.asList(rs, true);
    if (result == null) {
      return 0;
    }
    int availSize = getAvailSize(result, size, offset);    
    
    for (int i=0 ; i< availSize; i++) {
      cid[i]= ((Integer)((StructImpl)result.get(i+offset[0])).get("CID")).intValue();
      cust_name[i] = ((String)((StructImpl)result.get(i+offset[0])).get("CUST_NAME"));
      addr[i] = ((String)((StructImpl)result.get(i+offset[0])).get("ADDR"));
      since[i] = ((Date)((StructImpl)result.get(i+offset[0])).get("SINCE"));
    } //populate the data to be used for update
    return availSize;
  }
  */
  
  protected void getDataForUpdate(Connection conn, int[ ] newCid, int[] cid, String[] cust_name, 
      Date[] since, String[] addr, int[] whichUpdate, int size){
    getPrimaryKeysForUpdate(newCid, size);
    int maxVarCharLength = 100;
    int data = 100; //to allow duplicate 
    for (int i=0; i<size; i++) {
      if (whichUpdate[i] == 0)  cid[i] = getCid(conn);  //make sure the cid is inserted by this thread if test Uniqkey
      else {
        cid[i] = (i%2 == 0)? getCid(conn): getCid();
      } //any cid
      cust_name[i] = (i%3==0)? "name" + rand.nextInt(data) : getRandVarChar(maxVarCharLength-20);
      addr[i] = (i%2==0)? "address is " + cust_name[i] : getRandVarChar(maxVarCharLength);    
      since[i] =  getSince(); 
      whichUpdate[i] = rand.nextInt(update.length); //randomly select one update sql
    }//random data for update
  }
  
  //data for update
  protected void getDataForUpdate(String[] cust_name,
      Date[] since, String[] addr, int size) {
 
    int data = 1000;
    String temp;
    if (!randomData) {
      //update since using the same date if not random
      for (int i=0; i<size; i++) {
        temp = cust_name[i];
        //Log.getLogWriter().info("temp is " + temp);
        if (temp.indexOf('_') != -1) {
          cust_name[i] = temp.substring(0, temp.indexOf('_')+1) +(Integer.parseInt(temp.substring(temp.indexOf('_')+1))+1);
        } else {
          cust_name[i] += "_1";
        }//update of name
        
        temp = addr[i];
        if (temp.indexOf('_') != -1) {
          addr[i] = temp.substring(0, temp.indexOf('_')+1) + (Integer.parseInt(temp.substring(temp.indexOf('_')+1))+1);
        } else {
          addr[i] += "_1";
        }
      } //update of address
    } else {
      for (int i=0; i<size; i++) {
        cust_name[i] = "name" + rand.nextInt(data);
        addr[i] = "address is " + cust_name[i];    
        since[i] = getSince();  
      }//random data for update
    }    
  }
  
  //cid for update primary keys
  protected void getPrimaryKeysForUpdate(int[]cid, int size) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.tradeCustomersPrimary, size);
    for (int i=0; i<size; i++) {
      cid[i] = key -i;
    }    
  }
  
  //add to exception list if any
  protected boolean deleteFromDerbyTable(Connection dConn, int cid, String cust_name, 
      int whichDelete, List<SQLException> exList) {
    int tid = getMyTid();
    int count = -1;
    
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]);
    if (stmt == null) return false;
    try {
      verifyRowCount.put(tid+"_delete", 0); //to avoid NPE in varifying stage if gemfirexd does not get the same exception
      count = deleteFromTable(stmt, cid, cust_name, tid, whichDelete); 
      verifyRowCount.put(tid+"_delete", new Integer(count));
      
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(dConn, se))
        return false;
      else {
      SQLHelper.handleDerbySQLException(se, exList); //handle the exception
      }
    }
    return true;
  }
  
  //compared to exception list got from executing derby dml statement 
  protected void deleteFromGFETable(Connection gConn, int cid, String cust_name, int whichDelete, List<SQLException> exList) {
    int tid = getMyTid();
    int count = -1;
    
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]);
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exList);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt for delete failed due to XCL54");
      //throw new TestException("prepare stmt for delete failed due to XCL54"); //ticket #45995
      return;
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
      count = deleteFromTable(stmt, cid, cust_name, tid, whichDelete); 
      if (count != ((Integer)verifyRowCount.get(tid+"_delete")).intValue()){
        String str ="Gfxd delete (customers) has different row count from that of derby " +
                "derby deleted " + ((Integer)verifyRowCount.get(tid+"_delete")).intValue() +
                " but gfxd deleted " + count;
        if (failAtUpdateCount && !isHATest) throw new TestException (str);
        else Log.getLogWriter().warning(str);
      }
    } catch (SQLException se) {
      SQLHelper.handleGFGFXDException(se, exList); //handle the exception
    }
  }
  
  //w/o verification, no exception list available
  protected void deleteFromGFETable(Connection gConn, int cid, String cust_name, int whichDelete) {
    int tid = getMyTid();
    
    PreparedStatement stmt = getStmt(gConn, delete[whichDelete]);
    if (SQLTest.testSecurity && stmt == null) {
    	if (SQLSecurityTest.prepareStmtException.get() != null) {
    	  SQLSecurityTest.prepareStmtException.set(null);
    	  return;
    	} else ; //need to find out why stmt is not obtained
    } //work around #43244
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
      //throw new TestException("prepare stmt for delete failed with XCL54"); //avoid #45995
      return;
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
      deleteFromTable(stmt, cid, cust_name, tid, whichDelete); 
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
  
  
  //delete from table based on statement
  protected int deleteFromTable(PreparedStatement stmt, int cid, String cust_name, int tid, 
      int whichDelete) throws SQLException {
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";  
    String query = " QUERY: " + delete[whichDelete];
    String successString = "";
    switch (whichDelete) {
    case 0:   
      Log.getLogWriter().info( database + "deleting trade.customers with CUST_NAME:" + cust_name+ ",CID:" + cid + ",TID:" + tid + query);
      stmt.setString(1, cust_name);
      stmt.setInt(2, cid);
      stmt.setInt(3,tid);
      successString = " rows in trade.customers CUST_NAME:" + cust_name+ ",CID:" + cid + ",TID:" + tid + query;
      //stmt.executeUpdate();
      break;
    case 1:
      Log.getLogWriter().info(database + "deleting trade.customers with CID:" + cid  +",TID:" + tid + query); 
      stmt.setInt(1, cid);
      successString = " rows in trade.customers CID:" + cid + ",TID:" + tid + query;
      //stmt.executeUpdate();
      break;
    case 2:
      Log.getLogWriter().info(database + "deleting trade.customers with CID:" + cid + query); 
      stmt.setInt(1, cid);
      successString = " rows in trade.customers CID:" + cid  + query;
      //stmt.executeUpdate();
      break;
    default:
      throw new TestException("incorrect delete statement, should not happen");
    }
    int rowCount = stmt.executeUpdate();
    Log.getLogWriter().info(database + "deleted " + rowCount + successString );
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    return rowCount;
  }
  
  //for derby checking
  protected boolean insertToDerbyTable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, List<SQLException> exceptions) {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) { 
      try {
        count = insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid); 
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exceptions);      
      }
    }
    return true;
  }
  
  protected boolean insertBatchToDerbyTable(Connection conn, int[] cid, String[] cust_name,
        Date[] since, String[] addr, int size, List<SQLException> exceptions) throws SQLException {
    Statement stmt = conn.createStatement();
    int tid = getMyTid();
    int counts[] = null;
    
    for (int i=0 ; i<size ; i++) { 
      try {
        stmt.addBatch("insert into trade.customers (cid, cust_name, since, addr, tid) values (" 
          + cid[i] + ", '" + cust_name[i] + "','" + since[i]
          + "','" + addr[i] + "'," + tid + ")");
        Log.getLogWriter().info("Derby - batch inserting into trade.customers with CID:" + cid[i] + ",CUSTNAME:" + cust_name[i] +
              ",SINCE:" + since[i] + ",ADDR:" + addr[i] + ",TID:" + tid);
      } catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exceptions); 
        throw new TestException(TestHelper.getStackTrace(se));
      }
    }
    /*
    if (size>0) {
      try {
        stmt.addBatch("insert into trade.customers values (" 
          + cid[0] + ", '" + cust_name[0] + "','" + since[0]
          + "','" + addr[0] + "'," + tid + ")");
        Log.getLogWriter().info("Derby inserting into table trade.customers cid is " + cid[0] + " cust_name: " + cust_name[0] +
              " since: " + since[0] + " addr: " + addr[0] + " tid: " + tid);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exceptions);      
      }
    } //add primary key violation in batch execution
    */
    
    try {
      counts = stmt.executeBatch(); 
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exceptions);      
        if (se instanceof BatchUpdateException) {
          counts=((BatchUpdateException)se).getUpdateCounts();     
        } else throw new TestException(TestHelper.getStackTrace(se));
        //there are expected duplicate key in view test, modified to accommodate this
    } 
    
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] != -3)
        Log.getLogWriter().info("Derby - batch inserted into trade.customers with CID:" + cid[i] + ",CUSTNAME:" + cust_name[i] +
            ",SINCE:" + since[i] + ",ADDR:" + addr[i] + ",TID:" + tid);
      verifyRowCount.put(tid + "_insert" + i, 0);
      verifyRowCount.put(tid + "_insert" + i, new Integer(counts[i]));
      // Log.getLogWriter().info("Derby inserts " +
      // verifyRowCount.get(tid+"_insert" +i) + " rows");
    }
    return true;
  }
  
  protected void insertToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, List<SQLException> exceptions) throws SQLException {
    insertToGFETable(conn, cid, cust_name, since, addr, size, exceptions, false);
  }
  //for gemfirexd checking
  protected void insertToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, List<SQLException> exceptions, boolean isPut) throws SQLException {
    int tid = getMyTid();
    
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);
    if (SQLTest.testSecurity && stmt == null) {
    	SQLHelper.handleGFGFXDException((SQLException)
    			SQLSecurityTest.prepareStmtException.get(), exceptions);
    	SQLSecurityTest.prepareStmtException.set(null);
    	return;
    } //work around #43244
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
      return; //prepare stmt may fail due to XCL54 now
    }
    if (stmt == null && alterTableDropColumn) {
      Log.getLogWriter().info("prepare stmt failed due to missing column");
      return; //prepare stmt may fail due to alter table now
    } 
    
    if (stmt == null && SQLTest.setTx && isHATest && getNodeFailureFlag() ) {
      Log.getLogWriter().info("prepare stmt failed due to node failure while no HA support yet");
      return; //prepare stmt may fail due to alter table now
    } 
    
    int count = -1;
    for (int i=0 ; i<size ; i++) {
      try {
        if (isPut)
          count = insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid, isPut);
        else 
          count = insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid);
        
        if (count != ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd " + (isPut ? "put" : "insert") + " has different row count from that of derby " +
            "derby inserted " + ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd " + (isPut ? "put" : "inserted") + "  " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      } catch (SQLException se) {
        /* not expected when there is no unique key column, 
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else 
        */ 
        SQLHelper.handleGFGFXDException(se, exceptions);  
      }
    }
  }
  
  protected void insertBatchToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, List<SQLException> exceptions) throws SQLException {
    insertBatchToGFETable(conn, cid, cust_name, since, addr, size, exceptions, false);
  }
  //for gemfirexd checking
  protected void insertBatchToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, List<SQLException> exceptions, boolean isPut) throws SQLException {
    Statement stmt = conn.createStatement();
    int tid = getMyTid();
    int[] counts = null;
    
    for (int i=0 ; i<size ; i++) {
    try {
      stmt.addBatch((isPut ? "put" : "insert")  + " into trade.customers (cid, cust_name, since, addr, tid) values (" 
          + cid[i] + ", '" + cust_name[i] + "' , '" + since[i]
          + "' , '" + addr[i] + "'," + tid + ")");
        Log.getLogWriter().info("gemfire - batch " + (isPut ? "Putting" : "Inserting") + " into trade.customers CID:" + cid[i] + ",CUST_NAME:" + cust_name[i] +
              ",SINCE:" + since[i] + ",ADDR:" + addr[i] + ",TID:" + tid);
      }  catch (SQLException se) {
        /*
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  */
        SQLHelper.handleGFGFXDException(se, exceptions);      
      }  
    }
    
    /*
    if (size>0) {
      try {
        stmt.addBatch("insert into trade.customers values (" 
          + cid[0] + ", '" + cust_name[0] + "','" + since[0]
          + "','" + addr[0] + "'," + tid + ")");
        Log.getLogWriter().info("gfxd inserting into table trade.customers cid is " + cid[0] + " cust_name: " + cust_name[0] +
              " since: " + since[0] + " addr: " + addr[0] + " tid: " + tid);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        SQLHelper.handleGFGFXDException(se, exceptions);      
      }
    } //add primary key violation in batch execution
   */
      
    try {
      counts = stmt.executeBatch();
      if (counts == null) throw new TestException("gfxd statement executebatch succeeded but not return an array of update counts");
      if (counts.length < 1) {
        throw new TestException("gfxd statement executebatch succeeded but returned zeor length array of update counts");
      }  
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      SQLHelper.handleGFGFXDException(se, exceptions);      
      if (se instanceof BatchUpdateException) counts=((BatchUpdateException)se).getUpdateCounts();
    }
 
    if (counts == null) {
      if (setCriticalHeap) {
        boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
        if (getCanceled != null && getCanceled[0] == true) {
          Log.getLogWriter().info("avoid verify gfxd batching results due to cancelled query op");
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
      
    verifyAndLog(cid, cust_name, since, isPut, tid, counts);
  }

  private void verifyAndLog(int[] cid, String[] cust_name, Date[] since,
      boolean isPut, int tid, int[] counts) {
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] != -3) {
        Log.getLogWriter().info(
            "gemfirexd - batch " + (isPut ? "put" : "inserted ")
                + " into trade.customers CID:" + cid[i]
                + ",CUST_NAME:" + cust_name[i] + ",SINCE:" + since[i]
                + ",ADDR:null" + ",TID:" + tid);
      }
      if (counts[i] != ((Integer) verifyRowCount.get(tid + "_insert" + i))
          .intValue()) {
        String str = 
            "gemfirexd -  "
                + (isPut ? "put" : "insert")
                + " has different row count from that of derby "
                + "derby inserted "
                + ((Integer) verifyRowCount.get(tid + "_insert" + i))
                    .intValue() + " but gfxd " + (isPut ? "put" : "inserted")
                + " " + counts[i];
        if (failAtUpdateCount && !isHATest) throw new TestException (str);
        else Log.getLogWriter().warning(str);
      }
    }
  }
  
  protected void insertToGfxdTableGenerateId(Connection conn, String[] cust_name,
      Date[] since, String[] addr, int size) throws SQLException {
    insertToGfxdTableGenerateId(conn, cust_name, since, addr, size, false);
  }
  protected void insertToGfxdTableGenerateId(Connection conn, String[] cust_name,
      Date[] since, String[] addr, int size, boolean isPut) throws SQLException {
    PreparedStatement stmt = getStmt(conn, isPut ? putWithDefault : insertWithDefault);
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
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
        insertToTable(stmt, cust_name[i],since[i], addr[i], tid); 
      } catch (SQLException se) {
        if (se.getSQLState().equals("42500") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else
          throw se;      
      }
    }
  }
  
  protected int insertToTable(PreparedStatement stmt, String cust_name, Date since, String addr, int tid) throws SQLException {
    return insertToTable(stmt, cust_name, since, addr, tid, false);
  }
  //insert the record into database
  protected int insertToTable(PreparedStatement stmt, String cust_name, Date since, String addr, int tid, boolean isPut) throws SQLException {  
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";  
    
    Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.customers with CUSTNAME:" 
        + cust_name 
        + ",SINCE:" + since 
        + ",ADDR:" + addr 
        + ",TID:" + tid);
    
    stmt.setString(1, cust_name);
    if (testworkaroundFor51519) stmt.setDate(2, since, getCal());
    else stmt.setDate(2, since);
    stmt.setString(3, addr);       
    stmt.setInt(4, tid);      
    int rowCount = stmt.executeUpdate();


    Log.getLogWriter().info(database + (isPut ? "put" : "inserted") + rowCount + " rows into trade.customers CUSTNAME:" 
        + cust_name 
        + ",SINCE:" + since 
        + ",ADDR:" + addr 
        + ",TID:" + tid);
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    }
    if ( database.contains("gemfirexd") && isPut) {
      Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.customers with CUSTNAME:" 
          + cust_name 
          + ",SINCE:" + since 
          + ",ADDR:" + addr 
          + ",TID:" + tid);
      
      rowCount = stmt.executeUpdate();
      
      Log.getLogWriter().info(database + (isPut ? "put" : "inserted") + rowCount + " rows into trade.customers CUSTNAME:" 
          + cust_name 
          + ",SINCE:" + since 
          + ",ADDR:" + addr 
          + ",TID:" + tid);
      warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning);
      } 
    }
 
    return rowCount;
  }
  
  protected void insertToGfxdTableGenerateId(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size) throws SQLException {
    insertToGfxdTableGenerateId(conn, cid, cust_name, since, addr, size, false);
  }
  protected void insertToGfxdTableGenerateId(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, boolean isPut) throws SQLException {
    PreparedStatement stmt = getGeneratedKeysStmt(conn, isPut ? putWithDefault : insertWithDefault);
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
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
        int key = insertToTableGeneratedIdAlways(stmt, cust_name[i],since[i], addr[i], tid, isPut); 
        cid[i] = key; //assign the generated key to be used for derby
      } catch (SQLException se) {
        if (se.getSQLState().equals("42500") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else
          SQLHelper.handleSQLException(se);      
      }
    }
  }
  
  protected static PreparedStatement getGeneratedKeysStmt(Connection conn, String sql) {
    PreparedStatement stmt = null;
    try {
      stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    } catch (SQLException se) {
      if (SQLTest.testSecurity && (se.getSQLState().equals("42500")
          || se.getSQLState().equals("42502") || se.getSQLState().equals("25502"))) {        
        SQLHelper.printSQLException(se);
        SQLSecurityTest.prepareStmtException.set(se);
      } else if (!SQLHelper.checkDerbyException(conn, se)) { //handles the read time out
        if (SQLTest.hasTx) {
          SQLDistTxTest.rollbackGfxdTx.set(true); 
          Log.getLogWriter().info("force gfxd to rollback operations as well");
          //force gfxd rollback as one operation could not be performed in derby
          //such as read time out 
        }
        else
          ; //possibly gets read timeout in derby -- return null stmt
      } 
      else
        SQLHelper.handleSQLException(se);  
    }  
    return stmt;
  } 
  
  
  protected int insertToTableGeneratedIdAlways(PreparedStatement stmt, String cust_name,
      Date since, String addr, int tid) throws SQLException {
    return insertToTableGeneratedIdAlways(stmt, cust_name, since, addr, tid, false);
  }
  //insert the record into database, and return generated key
  protected int insertToTableGeneratedIdAlways(PreparedStatement stmt, String cust_name,
      Date since, String addr, int tid, boolean isPut) throws SQLException {   
    
    String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";  
    
    Log.getLogWriter().info( database + (isPut ?  "putting" : "inserting") + " into table trade.customers with GENERATEDID:NA" + ",CUSTNAME:" + cust_name +
        ",SINCE:" + since + ",ADDR:" + addr + ",TID:" + tid);        
    int generatedId = -1;
    stmt.setString(1, cust_name);
    if (testworkaroundFor51519) stmt.setDate(2, since, getCal());
    else stmt.setDate(2, since);
    stmt.setString(3, addr);       
    stmt.setInt(4, tid);      
    int rowCount = stmt.executeUpdate();
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    
    if (rowCount == 1) {
      ResultSet rs = stmt.getGeneratedKeys();
      if (rs.next()) {
        generatedId = rs.getInt(1);        
        Log.getLogWriter().info( database + (isPut ? "put" : "inserted" ) + rowCount + " rows into table trade.customers with GENERATEDID:" +generatedId 
            + ",CUSTNAME:" + cust_name +
            ",SINCE:" + since + ",ADDR:" + addr + ",TID:" + tid);        
      } else {
        throw new TestException("does not get generated key in the test");
      }
      rs.close();
    } else {
      throw new TestException("does not " + (isPut ? "put" : "insert" ) +" into table using generated id always" +
      		" in the test");
    }
        
    return generatedId; 
  }
  
  protected void insertToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size) throws SQLException {
    insertToGFETable(conn, cid, cust_name, since, addr, size, false);
  }
  //for gemfirexd
  protected void insertToGFETable(Connection conn, int[] cid, String[] cust_name,
      Date[] since, String[] addr, int size, boolean isPut) throws SQLException {
    PreparedStatement stmt = getStmt(conn, isPut ? put : insert);
    if (setCriticalHeap && stmt == null && ((boolean[]) SQLTest.getCanceled.get())[0]) {
      Log.getLogWriter().info("prepare stmt failed due to XCL54");
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
        insertToTable(stmt, cid[i], cust_name[i],since[i], addr[i], tid, isPut); 
      } catch (SQLException se) {
        if (se.getSQLState().equals("42500") && testSecurity) {
          Log.getLogWriter().info("Got the expected exception for authorization," +
             " continuing tests");
        } else if (alterTableDropColumn && se.getSQLState().equals("42802")) {
          Log.getLogWriter().info("Got expected column not found exception in " + (isPut ? "put" : "insert") + ", continuing test");
        } else if (se.getSQLState().equals("23505")
            && isHATest && SQLTest.isEdge) {
          Log.getLogWriter().info("detected pk constraint violation during " + (isPut ? "put" : "insert") + " -- relaxing due to #43571, continuing test");
        } else if (se.getSQLState().equals("42X14") && alterTableDropColumn) {
            SQLHelper.printSQLException(se);
            Log.getLogWriter().info("got expected alter table exception, continuing test");
        } else if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
          throw se;      
      }
    }
  }
  
  protected int insertToTable(PreparedStatement stmt, int cid, String cust_name, Date since, String addr, int tid) throws SQLException {   
    return insertToTable(stmt, cid, cust_name, since, addr, tid, false);
  }
  //insert the record into database
  protected int insertToTable(PreparedStatement stmt, int cid, String cust_name,
      Date since, String addr, int tid, boolean isPut) throws SQLException {   
     String transactionId = SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
     String database = SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - " + transactionId + " " ;  
     
     Log.getLogWriter().info(isPut ? put : insert);
     
     Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.customers with " 
          + "CID:" + cid 
      		+ ",CUSTNAME:" + ((cid > 100 && cid < 120 && !generateDefaultId)   ? "null" : cust_name) 
          + ",SINCE:" + since 
          + ",ADDR:" + addr 
          + ",TID:" + tid);
    stmt.setInt(1, cid);
    if (cid > 100 && cid < 120 && !generateDefaultId) stmt.setString(2, null);
    else stmt.setString(2, cust_name);
    if (testworkaroundFor51519) stmt.setDate(3, since, getCal());
    else stmt.setDate(3, since);
    stmt.setString(4, addr);       
    stmt.setInt(5, tid);      
    int rowCount = stmt.executeUpdate();
    
    Log.getLogWriter().info(database + (isPut ? "put " : "inserted ") + rowCount + " rows into trade.customers " 
        + "CID:" + cid 
              + ",CUSTNAME:" + ((cid > 100 && cid < 120 && !generateDefaultId)   ? "null" : cust_name) 
        + ",SINCE:" + since 
        + ",ADDR:" + addr 
        + ",TID:" + tid);
    
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    String driverName = stmt.getConnection().getMetaData().getDriverName();
    if ( driverName.toLowerCase().contains("gemfirexd") && isPut) {
      if (! SQLTest.ticket49794fixed) {
        //manually update fulldataset table for above entry.
          insertToCustomersFulldataset(stmt.getConnection() , cid, cust_name, since, addr, tid);
        }

      Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into trade.customers with " 
          + "CID:" + cid 
          + ",CUSTNAME:" + ((cid > 100 && cid < 120 && !generateDefaultId)   ? "null" : cust_name) 
          + ",SINCE:" + since 
          + ",ADDR:" + addr 
          + ",TID:" + tid);
          
     rowCount = stmt.executeUpdate();
     
     
     Log.getLogWriter().info(database + (isPut ? "put " : "inserted ") + rowCount + " rows into trade.customers " 
         + "CID:" + cid 
         + ",CUSTNAME:" + ((cid > 100 && cid < 120 && !generateDefaultId)   ? "null" : cust_name) 
         + ",SINCE:" + since 
         + ",ADDR:" + addr 
         + ",TID:" + tid);
     
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
    }     
    return rowCount;
  }  
  
  protected void insertToCustomersFulldataset (Connection conn, int cid, String cust_name, Date since, String addr, int tid){

    //manually update fulldataset table for above entry.
     try{
      
       Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence deleting  the  row  from TRADE.CUSTOMERS_FULLDATASET with data CID:" +  cid );
       conn.createStatement().execute("DELETE FROM TRADE.CUSTOMERS_FULLDATASET  WHERE  cid = "  + cid);      

      PreparedStatement preparedInsertStmt = conn.prepareStatement("insert into trade.CUSTOMERS_fulldataset values (?,?,?,?,?)");          
      
      preparedInsertStmt.setInt(1, cid);
      if (cid > 100 && cid < 120 && !generateDefaultId) preparedInsertStmt.setString(2, null);
      else preparedInsertStmt.setString(2, cust_name);
      if (testworkaroundFor51519) preparedInsertStmt.setDate(3, since, getCal());
      else preparedInsertStmt.setDate(3, since);
      preparedInsertStmt.setString(4, addr);       
      preparedInsertStmt.setInt(5, tid);   
     
      Log.getLogWriter().info(" Trigger behaviour is not defined for putDML hence inserting  the  row  into  TRADE.CUSTOMERS_FULLDATASET with data CID:" +  cid +  ",CUSTNAME:"
          + cust_name  + ",SINCE:" +  since + ",ADDR:" + addr + ",TID:" + tid );
      preparedInsertStmt.executeUpdate();
     } catch (SQLException se) {
       Log.getLogWriter().info("Error while updating TRADE.CUSTOMERS_FULLDATASET table. It may cause Data inconsistency " + se.getMessage() ); 
     }
  }
  
  //data to be insert into Tables
  protected void getDataForInsert(int[]cid, String[] cust_name,
      Date[] since, String[] addr, int size ) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.tradeCustomersPrimary, size);
    int counter;
    int data = 1000; //to make duplication filed
    for (int i = 0 ; i <size ; i++) {
      counter = key - i;
      cid[i]= counter;
      if (!randomData) {
        cust_name[i] = "name" + counter;   
        addr[i] = "address is " + cust_name[i];
        since[i] = getSince();
      } //to debug data easily, field has different values except for date field
      else {
        cust_name[i] = "name" + rand.nextInt(data);
        addr[i] = "address is " + cust_name[i];         
        since[i] = getSince();  
      } //random field data     
    }    
  }   
  
  protected ResultSet getResultSetForUniqKeys(Connection conn) {
    ResultSet rs;
    int cid = 0; //not used
    Date since = getSince(); //not used in the query
    rs = getQuery(conn, 0, cid, since); //first query to get results using unique Keys
    return rs;
  }
  
  public ResultSet getQuery(Connection conn, int whichQuery, int cid, Date since) {
    int tid = getMyTid();
    return getQuery(conn, whichQuery, cid, since, tid);
  }
  
  private ResultSet query(Connection conn, int whichQuery, int cid, Date since)
      throws SQLException {
    int tid = getMyTid();
    return query (conn, whichQuery, cid, since, tid);
  }
  
  //retries when lock could not obtained
  public static ResultSet getQuery(Connection conn, int whichQuery, int cid, Date 
      since, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    try {
      rs = getQuery(conn, whichQuery, cid, since, tid, success);
      int count = 0;
      while (!success[0]) {
        if (count >= maxNumOfTries) {
          if (SQLHelper.isDerbyConn(conn))
            Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
          return null; 
        };
        count++;        
        MasterController.sleepForMs(rand.nextInt(retrySleepMs));
        rs = getQuery(conn, whichQuery, cid, since, tid, success);
      }
    } catch (SQLException se) {
      if (!SQLHelper.isAlterTableException(conn, se)) SQLHelper.handleSQLException(se);
      //allow alter table related exceptions.
    }
    return rs;
  }
  
  protected static ResultSet query (Connection conn, int whichQuery, int cid, 
      Date since, int tid) throws SQLException {
    boolean[] success = new boolean[1];
    ResultSet rs = getQuery(conn, whichQuery, cid, since, tid, success);
    Log.getLogWriter().info("success is " + success[0]);
    int count = 0;
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        if (SQLHelper.isDerbyConn(conn))
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, abort this operation");
        return null; 
      };
      count++;        
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      rs = getQuery(conn, whichQuery, cid, since, tid, success);
    }
    return rs;
  }

  protected static String selectQuery (int whichQuery){
    return select[whichQuery];
  }
  
  protected static ResultSet getQuery(Connection conn, int whichQuery, int cid, Date since, 
      int tid, boolean[] success) throws SQLException{
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    String transactionId = (Integer)SQLDistTxTest.curTxId.get() == null ? "" : "TXID:" + (Integer)SQLDistTxTest.curTxId.get() + " ";
    String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - " + transactionId + " " ;
    
    String query = " QUERY: " + select[whichQuery];
    Log.getLogWriter().info(query);
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
        Log.getLogWriter().info(database + "querying trade.customers with TID:"+ tid + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select cid, since, cust_name from trade.customers where tid=? and cid >?",
        Log.getLogWriter().info(database + "querying trade.customers with CID:" + cid + ",TID:"+ tid + query);
        stmt.setInt(1, tid);
        stmt.setInt(2, cid); //set cid>?
        break;
      case 2:
        //"select since, addr, cust_name from trade.customers where (tid<? or cid <=?) and since >? and tid = ?",
        Log.getLogWriter().info(database + "querying trade.customers with CID:" + cid + ",SINCE: "+ since + ",TID:"+ tid + query);
        stmt.setInt(1, tid);
        stmt.setInt(2, cid); //set cid<=?
        if (testworkaroundFor51519) stmt.setDate(3, since, getCal()); //since >?
        else stmt.setDate(3, since);
        stmt.setInt(4, tid);
        break;
      case 3:
        //"select addr, since, cust_name from trade.customers where (cid >? or since <?) and tid = ?"};
        Log.getLogWriter().info(database + "querying trade.customers with CID:" + cid + "SINCE:"+ since + ",TID:"+ tid + query);
        stmt.setInt(1, cid); //set cid>?
        if (testworkaroundFor51519) stmt.setDate(2, since, getCal()); //since <?
        else stmt.setDate(2, since);
        stmt.setInt(3, tid);
        break;
      case 4:
        //"select max(addr) from customers where tid=?",
        Log.getLogWriter().info(database + "querying trade.customers with TID:"+ tid + query);
        stmt.setInt(1, tid);
        break;
      case 5:
        //    "select cid, since, cust_name from trade.customers where tid =? and cust_name like ? " +
        //"union " +
        //"select cid, since, cust_name from trade.customers where tid =? and cust_name like ? "      
        String custName = getUnionCustNameWildcard(cid);
        String custName2 = getUnionCustNameWildcard(cid + 2);
        stmt.setInt(1, tid);
        stmt.setString(2, custName);
        stmt.setInt(3, tid);
        stmt.setString(4, custName2);
        Log.getLogWriter().info(database + "querying trade.customers with TID:"+ tid + 
            ",1_CUSTNAME:" + custName + ",2_CUSTNAME:" + custName2  + query);
        break;
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471 and #canceled query
      else throw se;
    }
    return rs;
  }
  
  protected static String getUnionCustNameWildcard(int i) {
    return "%me%" + (SQLTest.populateWithbatch? i % 1000 : digits[i % 10])  + "%";
  }


  public void put(Connection dConn, Connection gConn, int size) {
    if (hasNetworth)
      put(dConn, gConn, size, true);
    else
      put(dConn, gConn, size, false);
  }

  protected void put(Connection dConn, Connection gConn, int size, boolean insertNetworth) {
    int[] cid = new int[size];
    String[] cust_name = new String[size];
    Date[] since = new Date[size];
    String[] addr = new String[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    boolean success = false;
    getDataForInsert(cid, cust_name,since,addr, size); //get the data
    boolean executeBatch = !testSecurity && rand.nextBoolean();  
    //boolean executeBatch = false;
    boolean usePreparedStatementForBatch = batchPreparedStmt && rand.nextBoolean();
      
    if (dConn != null && !useWriterForWriteThrough) {
      if (!generateDefaultId && !generateIdAlways) {
        try {
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not finish insert into derby, abort this operation");
              rollback(dConn); 
              if (alterTableDropColumn && SQLTest.alterTableException.get() != null && (Boolean)SQLTest.alterTableException.get() == true) break; 
              //expect gfxd fail with the same reason due to alter table
              else return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList .clear();
            if (executeBatch) {
              if (usePreparedStatementForBatch)
                success = insertPreparedBatchToDerbyTable(dConn, cid, cust_name,since, size, exList);
              else 
                success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            } else {
                success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            }
            count++;
          }
  
          try {
            if (executeBatch) {
              if (usePreparedStatementForBatch)
                insertPreparedBatchToGFETable(gConn, cid, cust_name,since, size, exList, true);
              else
                insertBatchToGFETable(gConn, cid, cust_name,since, addr, size, exList, true);
            } else insertToGFETable(gConn, cid, cust_name,since, addr, size, exList, true);    //insert to gfe table 
          } catch (TestException te) {
            if (te.getMessage().contains("Execute SQL statement failed with: 23505")
                && isHATest && SQLTest.isEdge && !SQLTest.setTx) {
              //checkTicket49605(dConn, gConn, "customers");
              try {
                checkTicket49605(dConn, gConn, "customers", cid[0], -1, null, null);
              } catch (TestException e) {
                Log.getLogWriter().info("insert failed due to #49605", te);
                deleteRow(dConn, gConn, "buyorders", cid[0], -1, null, null);
              }
            } else throw te;
          }
          SQLHelper.handleMissedSQLException(exList);
          commit(gConn);
          commit(dConn); //to commit and avoid rollback the successful operations
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException ("put to trade.customers fails\n" + TestHelper.getStackTrace(se));
        }  //for verification
      } else if (generateIdAlways){
        try {
          insertToGfxdTableGenerateId(gConn, cid, cust_name, since, addr, size, true);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            Log.getLogWriter().info("Could not insert into gfxd due to node failure exception, abort this op");
            return;
          }
                    
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
              boolean deleted = removeCidFromGfxd(gConn, cid);
              while (!deleted) {
                deleted = removeCidFromGfxd(gConn, cid);
                
                if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
                  //txn without HA/fail over support, when querying
                  Log.getLogWriter().info("Could not delete the newly inserted row due to node failure " +
                      "exception, but with txn, the insert would fail due to rollback, abort this op");
                  //rollback(gConn);
                  //no need for rollback -- currently txn failure will rollback whole txn automatically
                  return;
                }
              }
              return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList .clear();
            if (executeBatch)
              success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            else
              success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            
            count++;
          }
          SQLHelper.handleMissedSQLException(exList);
          commit(gConn);
          commit(dConn); //to commit and avoid rollback the successful operations       
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to trade.customers fails\n" + TestHelper.getStackTrace(se));
        }
      } else{
        try {
          insertToGfxdTableGenerateId(gConn, cust_name, since, addr, size, true);
          
          if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
            Log.getLogWriter().info("Could not insert into gfxd due to node failure exception, abort this op");
            return;
          }
          
          String sql = "select max(cid) cid from trade.customers where tid = " + getMyTid();
          boolean gotCid = getGeneratedCid(gConn, cid, sql);
          while (!gotCid) {
            gotCid = getGeneratedCid(gConn, cid, sql);
           
            if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
              //txn without HA/fail over support, when querying
              Log.getLogWriter().info("Could not get generated cid due to node failure exception, abort this op");
              //rollback(gConn);
              //no need for rollback -- currently txn failure will rollback whole txn automatically
              return;
            }
          }
          
          int count = 0;
          while (!success) {
            if (count>=maxNumOfTries) {
              Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
              boolean deleted = removeCidFromGfxd(gConn, cid);
              while (!deleted) {
                deleted = removeCidFromGfxd(gConn, cid);
                if (SQLTest.setTx && isHATest && getNodeFailureFlag()) {
                  //txn without HA/fail over support, when querying
                  Log.getLogWriter().info("Could not delete the newly inserted row due to node failure " +
                  		"exception, but with txn, the insert would fail due to rollback, abort this op");
                  //rollback(gConn);
                  //no need for rollback -- currently txn failure will rollback whole txn automatically
                  return;
                }
              }
              return;
            }
            MasterController.sleepForMs(rand.nextInt(retrySleepMs));
            exList .clear();
            if (executeBatch)
              success = insertBatchToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);
            else
              success = insertToDerbyTable(dConn, cid, cust_name,since, addr, size, exList);  //retry insert to derby table         
            
            count++;
          }
          SQLHelper.handleMissedSQLException(exList);
          commit(gConn);
          commit(dConn); //to commit and avoid rollback the successful operations    
        } catch (SQLException se) {
          SQLHelper.printSQLException(se);
          throw new TestException ("insert to trade.customers fails\n" + TestHelper.getStackTrace(se));
        }
      }  
    }
    else {
      try {
        if (generateIdAlways || generateDefaultId)
          insertToGfxdTableGenerateId(gConn, cust_name, since, addr, size, true);
        else 
          insertToGFETable(gConn, cid, cust_name,since, addr, size, true);    //insert to gfe table 
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("insert to gfe trade.customers fails\n" + TestHelper.getStackTrace(se));
      }
    } //no verification for gfxd only case or user writer -- no writer invocation when exception is thrown
    //after bug fixed for existing tests, we may change this to apply for all tests 
    if (insertNetworth) {
      //also insert into networth table
      TradeNetworthDMLStmt networth = new TradeNetworthDMLStmt();
      
      //get correct cid for networth when default keys are used
      String sql = null; 
      if (generateIdAlways) {
        sql = "select cid from trade.customers where tid = " + getMyTid() + 
           " and cid not in (select cid from trade.networth where tid = " + getMyTid() + ")";
        boolean gotCid = getGeneratedCid(gConn, cid, sql);
        while (!gotCid) {
          gotCid = getGeneratedCid(gConn, cid, sql);
        }
      }
      else if (generateDefaultId) {
        sql = "select max(cid) cid from trade.customers where tid = " + getMyTid();
        boolean gotCid = getGeneratedCid(gConn, cid, sql);
        while (!gotCid) {
          gotCid = getGeneratedCid(gConn, cid, sql);
        }
      }
       
      networth.insert(dConn, gConn, size, cid);
           
      if (dConn!=null) commit(dConn); //to commit and avoid rollback the successful operations
      commit(gConn);
    }    
  }

}
