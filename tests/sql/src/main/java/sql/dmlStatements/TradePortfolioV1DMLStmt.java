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
package sql.dmlStatements;

import hydra.Log;
import hydra.MasterController;
import hydra.TestConfig;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

import javax.sql.rowset.serial.SerialBlob;

import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.security.SQLSecurityTest;
import sql.sqlutil.ResultSetHelper;
import util.TestException;

public class TradePortfolioV1DMLStmt extends TradePortfolioDMLStmt {
  /*
   * trade.PortfolioV1 table fields
   *   int cid;
   *   int sid;
   *   int qty;
   *   int availQty;
   *   BigDecimal subTotal;
   *   int tid; //for update or delete unique records to the thread
   *   Blob data; //added in test coverage
   */

  private static boolean reproduceTicket48840 = true;
  protected static String insert = "insert into trade.portfoliov1 values (?,?,?,?,?, ?, ?)";
  protected static String put = "put into trade.portfoliov1 values (?,?,?,?,?, ?, ?)";
  protected static String[] update = { //uniq
                                      "update trade.portfoliov1 set availQty=availQty-100 where cid = ? and sid = ?  and tid= ?", 
                                      "update trade.portfoliov1 set qty=qty-? where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfoliov1 set subTotal = ? * qty  where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfoliov1 set subTotal=? where cid = ? and sid = ?  and tid= ?",
                                      "update trade.portfoliov1 set cid=? where cid = ? and sid = ?  and tid= ?", //cid in updateData is based on tid
                                      "update trade.portfoliov1 set data=? where cid = ? and sid = ?  and tid= ?",
                                      //"update trade.portfoliov1 set data=substr(data, 1, length(data) - 2)  where cid = ? and sid = ? and tid= ? and data is not null and length(data) > 100 " ,
                                      "update trade.portfoliov1 set data=?  where cid = ? and sid = ? and tid= ? and data is not null and length(data) > 100 " ,
                                      //no uniq
                                      "update trade.portfoliov1 set availQty=availQty-100 where cid = ? and sid = ? ", 
                                      "update trade.portfoliov1 set qty=qty-? where cid = ? and sid = ?  ",
                                      "update trade.portfoliov1 set subTotal=? * qty where cid = ? and sid = ? ",
                                      "update trade.portfoliov1 set subTotal=? where cid = ? and sid = ? ",
                                      "update trade.portfoliov1 set cid=? where cid = ? and sid = ? ", 
                                      "update trade.portfoliov1 set sid=? where cid = ? and sid = ? ",
                                      "update trade.portfoliov1 set data=? where cid = ? and sid < ? ",
                                      "update trade.portfoliov1 set data=? where cid < ? and sid = ? and data is not null and length(data) > 100 " ,
                                      }; 
  protected static String[] select = {"select * from trade.portfoliov1 where tid = ?",
                                    "select sid, cid, subTotal from trade.portfoliov1 where (subTotal >? and subTotal <= ?) and tid=? ",
                                    (reproduceTicket48840 ? "select count(distinct cid) as num_distinct_cid from trade.portfoliov1 where (subTotal<? or subTotal >=?) and tid =?"
                                        : "select count (cid) as num_cid from trade.portfoliov1 where (subTotal<? or subTotal >=?) and tid =?"),
                                    "select distinct sid from trade.portfoliov1 where (qty >=? and subTotal >= ?) and tid =?",
                                    "select sid, cid, qty from trade.portfoliov1  where (qty >=? and availQty<?) and tid =?",
                                    "select * from trade.portfoliov1 where sid =? and cid=? and tid = ?",
                                    "select * from trade.portfoliov1 ",
                                    "select sid, cid from trade.portfoliov1 where subTotal >? and subTotal <= ? ",
                                    "select distinct cid from trade.portfoliov1 where subTotal<? or subTotal >=?",
                                    "select distinct sid from trade.portfoliov1 where qty >=? and subTotal >= ? ",
                                    "select sid, cid, qty from trade.portfoliov1  where (qty >=? and availQty<?) ",
                                    "select * from trade.portfoliov1 where sid =? and cid=?"
                                    };
  protected static String[] delete = {/*"delete from trade.portfoliov1 where qty = 0 and tid = ?", as of preview, no cascade delete is allowed, this batch op may cause inconsistence from derby results*/
                                     "delete from trade.portfoliov1 where cid=? and sid=? and tid=?",
                                     "delete from trade.portfoliov1 where sid=? and tid=? and cid = (select max(cid) from trade.portfoliov1 where sid =? and tid= ? and data is not null and length(data) > 1000 and length(data) < 2000)",
                                     //no uniq
                                     /*"delete from trade.portfoliov1 where qty = 0",*/
                                     "delete from trade.portfoliov1 where cid=? and sid=?",
                                     "delete from trade.portfoliov1 where cid<? and sid=? and data is not null and length(data) > 1000 and length(data) < 2000",
                                      };
  
  private static boolean reproduceTicket50115 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50115, true);
  
  @Override
  public void insert(Connection dConn, Connection gConn, int size, int cid, boolean isPut) {
    int[] sid = new int[size];
    int[] qty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal[size];
    Blob[] data = new Blob[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    int availSize = 0;
    
    getBlobData(size, data);
    
    if (dConn!=null) {
      //get data
      if (rand.nextInt(numGettingDataFromDerby) == 1) availSize = getDataFromResultSet(dConn, sid, qty, sub, price, size); //get the data
      else availSize = getDataFromResultSet(gConn, sid, qty, sub, price, size); //get the data
      
      if (setCriticalHeap) resetCanceledFlag();
      
      boolean success = insertToDerbyTable(dConn, cid, sid, qty, sub, data, availSize, exList);
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
        success = insertToDerbyTable(dConn, cid, sid, qty, sub, data, availSize, exList);
      }
            
      try {
        insertToGFETable(gConn, cid, sid, qty, sub, data, availSize, exList, isPut);
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
          insertToGFETable(gConn, cid, sid, qty, sub, data, availSize, isPut);
      } else {
        if (getRandomData(gConn, sid, qty, sub, price, size)) //get the data
          insertToGFETable(gConn, cid, sid, qty, sub, data, size, isPut);
      }
    } //no verification
  }
  
  protected void getBlobData(int size, Blob[] data) {
    int regBlobSize = 10000;
    int maxBlobSize = useMD5Checksum ? 2000000 : regBlobSize;
    try {
      for (int i=0; i<size; i++) {
        if (rand.nextBoolean()) {     
          byte[] bytes = (rand.nextInt(10) != 0) ? 
              getByteArray(regBlobSize) : (rand.nextInt(100) == 0 ? getMaxByteArray(maxBlobSize): new byte[0]);
          data[i] = new SerialBlob(bytes); 
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }      
  }
  
  protected boolean insertToDerbyTable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, Blob[] data, int size, List<SQLException> exceptions)  {
    PreparedStatement stmt = getStmt(conn, insert);
    if (stmt == null) return false;
    int tid = getMyTid();
    int count =-1;
    Log.getLogWriter().info("Insert into derby, myTid is " + tid);
    for (int i=0 ; i<size ; i++) {
      try {
      verifyRowCount.put(tid+"_insert"+i, 0);
        count = insertToTable(stmt, cid, sid[i], qty[i], sub[i], data[i], tid);
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        Log.getLogWriter().info("Derby inserts " + verifyRowCount.get(tid+"_insert"+i) + " rows");
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }    
    }
    return true;
  }
  
  protected void insertToGFETable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, Blob[] data, int size, 
      List<SQLException> exceptions, boolean isPut)  {
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
    Log.getLogWriter().info((isPut ? "Put" : "Insert") + " into gemfirexd, myTid is " + tid);
    for (int i=0 ; i<size ; i++) {
      try {
        count = insertToTable(stmt, cid, sid[i], qty[i], sub[i], data[i], tid, isPut);
        if (count != (verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          String str = "Gfxd insert has different row count from that of derby " +
            "derby inserted " + (verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count;
          if (failAtUpdateCount && !isHATest) throw new TestException (str);
          else Log.getLogWriter().warning(str);
        }
      }  catch (SQLException se) {
        if (isPut && se.getSQLState().equals("0A000")) {
          Log.getLogWriter().info("Got expected Feature not Supported Exception during put, continuing test"); 
        } else  
        SQLHelper.handleGFGFXDException(se, exceptions);
      }    
    }
  }
  
  //insert into GFE w/o verification
  protected void insertToGFETable(Connection conn, int cid, int[] sid, 
      int[] qty, BigDecimal[] sub, Blob[] data, int size, boolean isPut)  {
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
    Log.getLogWriter().info((isPut ? "Put" : "Insert") + " into gemfirexd, myTid is " + tid);
    for (int i=0 ; i<size ; i++) {
      try {
        insertToTable(stmt, cid, sid[i], qty[i], sub[i], data[i], tid, isPut);
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
      int qty, BigDecimal sub, Blob data, int tid) throws SQLException {
    return insertToTable(stmt, cid, sid, qty, sub, data, tid, false);
  }
  //insert a record into the table
  protected int insertToTable(PreparedStatement stmt, int cid, int sid, 
      int qty, BigDecimal sub, Blob data, int tid, boolean isPut) throws SQLException {
    String blob = null;
    if (data != null) {
      if (data.length() == 0) {
        blob = "empty data";
        if (SQLPrms.isSnappyMode())
          data = new SerialBlob(blob.getBytes());
      }
      else if (useMD5Checksum) blob = ResultSetHelper.convertByteArrayToChecksum(data.getBytes(1, 
          (int)data.length()), data.length());
      else blob = ResultSetHelper.convertByteArrayToString(data.getBytes(1, 
          (int)data.length()));
    }
    
    Log.getLogWriter().info((isPut ? "putting" : "inserting") + " into table trade.portfoliov1 cid is " + cid +
        " sid is "+ sid + " qty is " + qty + " availQty is " + qty + " subTotal is " + sub
        + " data is " + blob);
    String driverName = stmt.getConnection().getMetaData().getDriverName();
    stmt.setInt(1, cid);
    stmt.setInt(2, sid);
    stmt.setInt(3, qty);
    stmt.setInt(4, qty); //availQty is the same as qty during insert
    stmt.setBigDecimal(5, sub);   
    stmt.setInt(6, tid);
    stmt.setBlob(7, data);
    int rowCount = stmt.executeUpdate();
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 

    //doing second put that may or may not successfull.
    if ( driverName.toLowerCase().contains("gemfirexd") && isPut) {
      Log.getLogWriter().info((isPut ? "putting" : "inserting") + " into table trade.portfoliov1 cid is " + cid +
          " sid is "+ sid + " qty is " + qty + " availQty is " + qty + " subTotal is " + sub + 
          " data is " + blob + " stmt " + stmt.toString());
      
     rowCount = stmt.executeUpdate();
     warning = stmt.getWarnings(); //test to see there is a warning   
     if (warning != null) {
       SQLHelper.printSQLWarning(warning);
     } 
    }
    return rowCount;
  }
  
  @Override
  public void update(Connection dConn, Connection gConn, int size) {
    int numOfNonUniqUpdate = 8;  //how many update statement is for non unique keys
    int whichUpdate= getWhichUpdate(numOfNonUniqUpdate, update.length);
    
    int[] cid = new int[size];
    int[] sid = new int[size];
    int[] newCid = new int[size];
    int[] newSid = new int[size];
    int[] qty = new int[size];
    int[] availQty = new int[size];
    BigDecimal[] sub = new BigDecimal[size];
    BigDecimal[] price = new BigDecimal[size];
    Blob[] data = new Blob[size];
    List<SQLException> exceptionList = new ArrayList<SQLException>();
    int availSize;
    
    getBlobData(size, data);
    
    Log.getLogWriter().info("update statement is " + update[whichUpdate]);
    
    if (isHATest && (whichUpdate == 0 || whichUpdate == 1 
        || whichUpdate == 7 || whichUpdate == 8 )) {
      Log.getLogWriter().info("avoid x=x+1 in HA test for now, do not execute this update");
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
      
      boolean success = updateDerbyTable(dConn, cid, sid, qty, availQty, sub, newCid, newSid, price, data, availSize, whichUpdate, exceptionList);  //insert to derby table  
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
        success = updateDerbyTable(dConn, cid, sid, qty, availQty, sub, newCid, newSid, price, data, availSize, whichUpdate, exceptionList); //retry
      }
      updateGFETable(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, data, availSize, whichUpdate, exceptionList); 
      SQLHelper.handleMissedSQLException(exceptionList);
    }
    else {
      if (getDataForUpdate(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, size) >0) //get the random data, no availSize constraint
        updateGFETable(gConn, cid, sid, qty, availQty, sub, newCid, newSid, price, data, size, whichUpdate);
    } //no verification
  }
  
  //update records in Derby
  protected boolean updateDerbyTable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, 
      Blob[] data, int size, int whichUpdate, List<SQLException> exceptions) {
    if (whichUpdate == 4 || whichUpdate ==11 || whichUpdate == 12 ) {
      Log.getLogWriter().info("update on gfxd primary key, do not execute update in derby as well");
      return true;
    } //update primary key case
    PreparedStatement stmt = null;
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("Update derby, myTid is " + tid);
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
          count = updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i], newCid[i], newSid[i], price[i], data[i], tid, whichUpdate );
          verifyRowCount.put(tid+"_update"+i, new Integer(count));
          Log.getLogWriter().info("Derby updates " + count + " rows");
        }
      }  catch (SQLException se) {
        if (!SQLHelper.checkDerbyException(conn, se)) return false;
        else SQLHelper.handleDerbySQLException(se, exceptions);
      }    
    }
    return true;
  }
  
  //update table based on the update statement
  protected int updateTable(PreparedStatement stmt, int cid, int sid, int qty,
      int availQty, BigDecimal sub,  int newCid, int newSid, BigDecimal price, 
      Blob data, int tid, int whichUpdate) throws SQLException {
  //  Log.getLogWriter().info("update table with cid: " + cid + " sid: " + sid +
  //      " qty: " + qty + " availQty: " + availQty + " sub: " + sub);
    int rowCount = 0;
    switch (whichUpdate) {
    case 0: 
      Log.getLogWriter().info("updating portfoliov1 table availQty (availQty-100) for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);
      stmt.setInt(1, cid);
      stmt.setInt(2, newSid);
      stmt.setInt(3, tid);
      rowCount = stmt.executeUpdate();
      break;
    case 1: 
      Log.getLogWriter().info("updating portfoliov1 table qty = qty - " +qty + " for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      break;
    case 2: 
      Log.getLogWriter().info("updaing portfoliov1 table with subTotal = price( " + price
          + " ) * qty for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);
      stmt.setBigDecimal(1, price);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
   
      rowCount = stmt.executeUpdate();
      break;
    case 3: //update name, since
      Log.getLogWriter().info("updating portfoliov1 table with subTotal = " + sub + " for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);
      stmt.setBigDecimal(1, sub);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      break;
    case 4:
      Log.getLogWriter().info("updating portfoliov1 table with newCid = " + newCid +  " for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);

      stmt.setInt(1, newCid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);
      //rowCount = stmt.executeUpdate();  //TODO need to uncomment out when update primary is ready
      break; 
    case 5:
      // "update trade.portfoliov1 set data=? where cid = ? and sid = ?  and tid= ?",
      if(SQLPrms.isSnappyMode() && data!=null && data.length()==0) {
        String blob = "empty";
        data = new SerialBlob(blob.getBytes());
      }
      Log.getLogWriter().info("updating portfoliov1 table with data = " + 
         (data !=null? (data.length() > 0 ? ResultSetHelper.convertByteArrayToChecksum(data.getBytes(1, (int)data.length()), data.length()) : "empty") : "null")
          +  " for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid);

      stmt.setBlob(1, data);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);  
      rowCount = stmt.executeUpdate();
      break;
      
    case 6:
      // "update trade.portfoliov1 set data=?  where cid = ? and sid = ? and tid= ? and data is not null and length(data) > 100 " ,
      if(SQLPrms.isSnappyMode() && data!=null && data.length()==0) {
        String blob = "empty";
        data = new SerialBlob(blob.getBytes());
      }
      Log.getLogWriter().info("updating portfoliov1 table with data = " + 
          (data !=null? (data.length() > 0 ? ResultSetHelper.convertByteArrayToChecksum(data.getBytes(1, (int)data.length()), data.length()) : "empty") : "null") + 
          " for cid: " + cid  + " sid: " + newSid +
          " tid: " + tid + " and data is not null and length(data) > 100 ");
      stmt.setBlob(1, data);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      stmt.setInt(4, tid);  
      rowCount = stmt.executeUpdate();
      break;
      
    case 7:
      Log.getLogWriter().info("updating portfoliov1 table availQty (availQty-100) for cid: " + cid  + " sid: " + newSid);
      stmt.setInt(1, cid);
      stmt.setInt(2, newSid);
      rowCount = stmt.executeUpdate();
      break;
    case 8: 
      Log.getLogWriter().info("updating portfoliov1 table qty = qty - " +qty + " for cid: " + cid  + " sid: " + newSid);
      stmt.setInt(1, qty);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      break;
    case 9: 
      Log.getLogWriter().info("updating portfoliov1 table with subTotal = price( " + price
          + " ) * qty for cid: " + cid  + " sid: " + newSid);
      stmt.setBigDecimal(1, price);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      
      rowCount = stmt.executeUpdate();
      break;
    case 10: //update name, since
      Log.getLogWriter().info("updating portfoliov1 table with subTotal = " + sub + " for cid: " + cid  + " sid: " + newSid);
      stmt.setBigDecimal(1, sub);
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      break;
    case 11:
      Log.getLogWriter().info("updating portfoliov1 table with newCid = " + newCid +  " for cid: " + cid  + " sid: " + newSid);
      stmt.setInt(1, newCid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      break; //non uniq keys
    case 12:
      Log.getLogWriter().info("updating portfoliov1 table with newSid = " + newSid +  " for cid: " + cid  + " sid: " + newSid);
      stmt.setInt(1, newSid);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      break;
    case 13:
      //"update trade.portfoliov1 set data=? where cid = ? and sid < ? ",
      if(SQLPrms.isSnappyMode() && data!=null && data.length()==0) {
        String blob = "empty";
        data = new SerialBlob(blob.getBytes());
      }
      Log.getLogWriter().info("updating portfoliov1 table with data = " + 
          (data !=null? (data.length() > 0 ? ResultSetHelper.convertByteArrayToChecksum(data.getBytes(1, (int)data.length()), data.length()) : "empty") : "null")
          +  " for cid: " + cid  + " sid: " + newSid);

      stmt.setBlob(1, data);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid);
      rowCount = stmt.executeUpdate();
      break;
      
    case 14:
      // "update trade.portfoliov1 set data=?  where cid < ? and sid = ? and data is not null and length(data) > 100 " ,
      if(SQLPrms.isSnappyMode() && data!=null && data.length()==0) {
        String blob = "empty";
        data = new SerialBlob(blob.getBytes());
      }
      Log.getLogWriter().info("updating portfoliov1 table with data = " + 
          (data !=null? (data.length() > 0 ? ResultSetHelper.convertByteArrayToChecksum(data.getBytes(1, (int)data.length()), data.length()) : "empty") : "null") + 
          " for cid: " + cid  + " sid: " + newSid +
          " and data is not null and length(data) > 100 ");
      stmt.setBlob(1, data);  
      stmt.setInt(2, cid);
      stmt.setInt(3, newSid); 
      rowCount = stmt.executeUpdate();
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
  
  //update records in gemfirexd
  protected void updateGFETable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price, 
      Blob[] data, int size, int whichUpdate, List<SQLException> exceptions) {
    PreparedStatement stmt;
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("Update gemfirexd, myTid is " + tid);
    if (whichUpdate == 4 || whichUpdate ==11 || whichUpdate == 12) {
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
          count = updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i],  
              newCid[i], newSid[i], price[i], data[i], tid, whichUpdate );
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
  
  protected void updateGFETable(Connection conn, int[] cid, int[] sid, int[] qty, 
      int[] availQty, BigDecimal[] sub, int[] newCid, int[] newSid, BigDecimal[] price,
      Blob[] data, int size, int whichUpdate) {
    PreparedStatement stmt;
    int tid = getMyTid();
    Log.getLogWriter().info("Update gemfirexd, myTid is " + tid);
    if (whichUpdate == 4 || whichUpdate == 11 || whichUpdate ==12 ) {
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
        updateTable(stmt, cid[i], sid[i], qty[i], availQty[i], sub[i], newCid[i], newSid[i], 
            price[i], data[i], tid, whichUpdate );
      }  catch (SQLException se) {
        if (se.getSQLState().equals("23503") && (whichUpdate == 4 || whichUpdate == 11 || whichUpdate == 12))
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
  
  @Override
  //used to parse the partitionKey and test unsupported update on partitionKey, no need after bug #39913 is fixed
  protected PreparedStatement getCorrectStmt(Connection conn, int whichUpdate,
      ArrayList<String> partitionKeys, boolean[] unsupported){  
    PreparedStatement stmt = null;
    Log.getLogWriter().info("update statement is "  + update[whichUpdate]);
    switch (whichUpdate) {    
    case 0: 
      //"update trade.portfoliov1 set availQty=availQty-100 where cid = ? and sid = ?  and tid= ?"
      if (partitionKeys.contains("availQty")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 1: 
      // "update trade.portfoliov1 set qty=qty-? where cid = ? and sid = ?  and tid= ?",
      if (partitionKeys.contains("qty")) {
        Log.getLogWriter().info("Will update gemfirexd on partition key");
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 2: 
      //"update trade.portfoliov1 set subTotal=subtotal*qty where cid = ? and sid = ?  and tid= ?",
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 3: 
      // "update trade.portfoliov1 set subTotal=? where cid = ? and sid = ?  and tid= ?", 
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 4: 
      //"update trade.portfoliov1 set cid=? where cid = ? and sid = ?  and tid= ?"
      throw new TestException ("Test issue, this should not happen");
    case 5:
      //update on data (Blob) not a partitioned column 
      stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 6:
      //update on data (Blob) not a partitioned column 
      stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 7: 
      // "update trade.portfoliov1 set availQty=availQty-100 where cid = ? and sid = ? ",
      if (partitionKeys.contains("availQty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 8: 
      // "update trade.portfoliov1 set qty=qty-? where cid = ? and sid = ?  ",
      if (partitionKeys.contains("qty")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true;//if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 9: 
      //"update trade.portfoliov1 set subTotal=subtotal*qty where cid = ? and sid = ? ",
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 10: 
      // "update trade.portfoliov1 set subTotal=? where cid = ? and sid = ? ", 
      if (partitionKeys.contains("subTotal")) {
        if (!SQLHelper.isDerbyConn(conn))
          stmt = getUnsupportedStmt(conn, update[whichUpdate]);
        else unsupported[0] = true; //if derbyConn, stmt is null so no update in derby as well
      } else stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 11: 
      //"update trade.portfoliov1 set cid=? where cid = ? and sid = ? ",
      throw new TestException ("Test issue, this should not happen");
    case 12: 
      // "update trade.portfoliov1 set sid=? where cid = ? and sid = ? "
      throw new TestException ("Test issue, this should not happen");
    case 13:
      //update on data (Blob) not a partitioned column 
      stmt = getStmt(conn, update[whichUpdate]);
      break;
    case 14:
      //update on data (Blob) not a partitioned column 
      stmt = getStmt(conn, update[whichUpdate]);
      break;
    default:
     throw new TestException ("Wrong update sql string here");
    }
   
    return stmt;
  }
  
  @Override
  public void delete(Connection dConn, Connection gConn) {
    if (!reproduceTicket50115) {
      Log.getLogWriter().info("Do not execute delete op to avoid #50115");
      return;
    }
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
  
  //add the exception to expceptionList to be compared with gfe
  protected boolean deleteFromDerbyTable(Connection dConn, int whichDelete, 
      int[]cid, int []sid, List<SQLException> exList){
    PreparedStatement stmt = getStmt(dConn, delete[whichDelete]); 
    if (stmt == null) return false;
    int tid = getMyTid();
    int count = -1;
    Log.getLogWriter().info("delete from derby, myTid is " + tid);
    try {
      for (int i=0; i<cid.length; i++) {
      verifyRowCount.put(tid+"_delete"+i, 0);
        count = deleteFromTable(stmt, cid[i], sid[i], tid, whichDelete);
        verifyRowCount.put(tid+"_delete"+i, new Integer(count));
        Log.getLogWriter().info("Derby deletes " + count + " rows");
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
    Log.getLogWriter().info("delete from gemfirexd, myTid is " + tid);
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
    Log.getLogWriter().info("delete from gemfirexd, myTid is " + tid);
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
    Log.getLogWriter().info("delete statement is " + delete[whichDelete]);
    int rowCount = 0;
    switch (whichDelete) {
    case 0:  
      //"delete from trade.portfoliov1 where cid=? and sid=? and tid=?",
      Log.getLogWriter().info("deleting record in portfoliov1 where cid is " + cid + " and sid is " +sid +" and tid is " + tid);
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      stmt.setInt(3, tid);
      if (reproduceTicket50115) rowCount = stmt.executeUpdate();
      break;
    case 1:
      // "delete from trade.portfoliov1 where sid=? and tid=? and cid = (select max(cid) from trade.portfoliov1 where sid =? and tid= ? and data is not null and length(data) > 1000 and length(data) < 2000)",
      Log.getLogWriter().info("deleting record in portfoliov1 where sid = " + sid + " and tid = " +tid +" and sid is " + sid
          + " and tid = " + tid + " and length(data) > 1000 and length(data) < 2000");
      stmt.setInt(1, sid);
      stmt.setInt(2, tid);
      stmt.setInt(3, sid);
      stmt.setInt(4, tid);
      rowCount = stmt.executeUpdate();
      break;
    case 2:
      Log.getLogWriter().info("deleting record in portfoliov1 where cid is " + cid + " and sid is " +sid);
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
      break;
    case 3:
      // "delete from trade.portfoliov1 where cid<? and sid=? and data is not null and length(data) > 1000 and length(data) < 2000",
      Log.getLogWriter().info("deleting record in portfoliov1 where cid < " + cid + " and sid = " +sid 
         + " and length(data) > 1000 and length(data) < 2000");
      stmt.setInt(1, cid);
      stmt.setInt(2, sid);
      rowCount = stmt.executeUpdate();
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
  
}
