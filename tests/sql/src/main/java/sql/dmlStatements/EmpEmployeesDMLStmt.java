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

import java.sql.Blob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.rowset.serial.SerialBlob;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

public class EmpEmployeesDMLStmt extends AbstractDMLStmt {
  /*
   * table fields
   * eid int, 
   * emp_name varchar(100), 
   * deptno int, 
   * since date, 
   * addr varchar(100), 
   * picture blob, 
   * ssn varchar(9), 
   * tid int
   */
  
  protected static String insert = "insert into emp.employees values (?,?,?,?,?,?,?,?)";
  protected static String put = "put into emp.employees values (?,?,?,?,?,?,?,?)";
  protected static String[] update = {
    //uniq
    "update emp.employees set deptno = ? where eid=? and tid = ? ", 
    "update emp.employees set emp_name = ? , addr = ? where eid=? and tid =?", 
    "update emp.employees set picture = ? , addr = ? where eid=? and tid =? ",
    "update emp.employees set picture = ?, since =? where eid=? and tid =? " ,
    //non uniq
    "update emp.employees set deptno = ? where eid=? ", 
    "update emp.employees set emp_name = ? , addr = ? where eid=? ", 
    "update emp.employees set picture = ? , addr = ? where eid=? ",
    "update emp.employees set picture = ?, since =? where eid=? "
    };
  protected static String[] select = {
    //uniq
    "select picture from emp.employees where tid = ?",
    "select eid, since, picture, emp_name from emp.employees where tid=? and eid >?",
    //"select since, picture, addr, eid from emp.employees where (eid <=? or addr >?) and picture IS NOT NULL and tid = ?",
    "select since, picture, addr, eid from emp.employees where (eid <=? or addr =?) and picture IS NOT NULL and tid = ?",
    //"select max(eid) from emp.employees where (emp_name >? or since <?) and tid = ?",
    "select max(eid) from emp.employees where (emp_name =? or since <?) and tid = ?",
    //non uniq
    "select * from emp.employees where tid IN (?, ?) ",
    "select eid, since, picture, emp_name from emp.employees where eid >?",
    "select since, picture, addr, eid from emp.employees where (eid <=? or addr >?) and picture IS NOT NULL",
    "select max(eid) from emp.employees where (emp_name >? or since <?)",
    };
  protected static String[] delete = {
    //uniq
    "delete from emp.employees where (emp_name = ? or eid = ? ) and tid = ?",
    "delete from emp.employees where eid=? and tid=?",
    //non uniq
    "delete from emp.employees where (emp_name = ? or eid = ? ) ",
    "delete from emp.employees where eid=? ",
    }; //used in concTest without verification 
  protected static int maxNumOfTries = 2;
  protected static ConcurrentHashMap<String, Integer> verifyRowCount = new ConcurrentHashMap<String, Integer>();
  protected static ArrayList<String> partitionKeys = null;
  @Override
  public void delete(Connection dConn, Connection gConn) {
    // TODO Auto-generated method stub

  }

  @Override
  public void insert(Connection dConn, Connection gConn, int size) {
    int[] eid = new int[size];
    String[] emp_name = new String[size];
    int[] deptid = new int[size];
    Date[] since = new Date[size];
    String[] addr = new String[size];
    Blob[] picture = new Blob[size];
    String[] ssn = new String[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    boolean success = false;
    getDataForInsert(eid, emp_name,since,addr, deptid, picture, ssn, size); //get the data
    //boolean executeBatch = rand.nextBoolean();

    if (dConn != null && !useWriterForWriteThrough) {
      try {
        success = insertToDerbyTable(dConn, eid, emp_name, deptid, since, addr, 
            picture, ssn, size, exList);  //insert to derby table 
        int count = 0;
        while (!success) {
          if (count>=maxNumOfTries) {
            Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
            return;
          }
          MasterController.sleepForMs(rand.nextInt(retrySleepMs));
          exList .clear();
          success = insertToDerbyTable(dConn, eid, emp_name, deptid, since, addr, 
              picture, ssn, size, exList);  //retry insert to derby table                  
          count++;
        }
        insertToGFXDTable(gConn, eid, emp_name, deptid, since, addr, 
            picture, ssn, size, exList);    //insert to gfxd table 
        
        commit(dConn);
        commit(gConn);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("insert to emp.employees fails\n" + TestHelper.getStackTrace(se));
      }  //for verification

    }
    else {
      try {
        insertToGFXDTable(gConn, eid, emp_name, deptid, since, addr, picture, ssn, size, false);    //insert to gfxd table 
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("insert to gfxd emp.emploees fails\n" + TestHelper.getStackTrace(se));
      }
    } 

  }

  @Override
  public void query(Connection dConn, Connection gConn) {
    int numOfNonUniq = select.length/2; //how many query statement is for non unique keys, 
    int whichQuery = getWhichOne(numOfNonUniq, select.length); //randomly select one query sql based on test uniq or not

    int eid = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.empEmployeesPrimary));
    //Date since = new Date ((rand.nextInt(10)+98),rand.nextInt(12), rand.nextInt(31));
    Date since = getSince();
    String addr = getAddress();
    String empName = getName();
    
    if (dConn!=null) {
      ResultSet discRS = getQuery(dConn, whichQuery, eid, since, addr, empName);
      if (discRS == null) {
        Log.getLogWriter().info("could not get the derby result set after retry, abort this query");
        return;
      }
      ResultSet gfxdRS = getQuery (gConn, whichQuery, eid, since, addr, empName);   

      if (gfxdRS == null) {
        if (isHATest) {
          Log.getLogWriter().info("Testing HA and did not get GFXD result set");
          return;
        }
        else     
          throw new TestException("Not able to get gfxd result set after retry");
      }
      
      boolean success = ResultSetHelper.compareResultSets(discRS, gfxdRS);   
      if (!success) {
        Log.getLogWriter().info("Not able to compare results due to derby server error");
      } //not able to compare results due to derby server error
    }// we can verify resultSet
    else {
      ResultSet gfxdRS = getQuery (gConn, whichQuery, eid, since, addr, empName);   
      if (gfxdRS != null)
        ResultSetHelper.asList(gfxdRS, false);  
      else if (isHATest)
        Log.getLogWriter().info("could not get gfxd query results after retry due to HA");
      else
        throw new TestException ("gfxd query returns null and not a HA test");     
    }   

  }

  @Override
  public void update(Connection dConn, Connection gConn, int size) {
    // TODO Auto-generated method stub

  }  
  
  //data to be insert into Tables
  protected void getDataForInsert(int[]eid, String[] emp_name, Date[] since, 
      String[] addr, int[] deptid, Blob[] picture, String[] ssn, int size ) {
    int key = (int) SQLBB.getBB().getSharedCounters().add(SQLBB.empEmployeesPrimary, size);
    int counter;
    int regBlobSize = 10000;
    int maxBlobSize = 2000000;
    try {
      for (int i = 0 ; i <size ; i++) {
        counter = key - i;
        eid[i]= counter;
        emp_name[i] = getName();   
        addr[i] = getAddress();
        since[i] = getSince();
        deptid[i] = rand.nextInt(EmpDepartmentDMLStmt.names.length) + 1;        
        if (rand.nextBoolean()) {     
          //have some empty blob as well
          //String value = (rand.nextInt(100) != 0) ? getRandVarChar(rand.nextInt(maxBlobSize)) : "";
          byte[] bytes = (rand.nextInt(10) != 0) ? 
              getByteArray(regBlobSize) : (rand.nextInt(100) == 0 ? getByteArray(maxBlobSize): new byte[0]);
          picture[i] = new SerialBlob(bytes); 
        } 
        ssn[i] = getSSN(9);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }  
  } 
  
  private String getName() {
    int maxNameLength = 25;
    return getRandVarChar(rand.nextInt(maxNameLength)+1);
  }
  
  private String getAddress() {
    int maxAddrLength = 55;
    return getRandVarChar(rand.nextInt(maxAddrLength)+1);
  }
  
  private static final char[] numbers = new char[10];
  static {
    for (int i=0; i<10; i++) {
      numbers[i] = (char) ('0' + i);
    }
  }
  private String getSSN(int length) {
    char[] ssn = new char[length];
    for (int i =0; i<length; i++) 
      ssn[i] = numbers[rand.nextInt(numbers.length)];    
    return new String(ssn);
  }
  
  protected boolean insertToDerbyTable(Connection conn, int[] eid, String[] emp_name,
      int[] deptid, Date[] since, String[] addr, Blob[] picture, String[] ssn,
      int size, List<SQLException> exceptions) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement(insert);
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) { 
      try {
        count = insertToTable(stmt, eid[i], emp_name[i], deptid[i], since[i], addr[i], 
            picture[i], ssn[i], tid, false); 
        verifyRowCount.put(tid+"_insert"+i, new Integer(count));
        
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        if (!SQLHelper.checkDerbyException(conn, se)) return false;  //for retry
        else SQLHelper.handleDerbySQLException(se, exceptions);      
      }
    }
    return true;
  }

  protected void insertToGFXDTable(Connection conn, int[] eid, String[] emp_name,
      int[] deptid, Date[] since, String[] addr, Blob[] picture, String[] ssn,
      int size, List<SQLException> exceptions) throws SQLException {
    insertToGFXDTable(conn, eid, emp_name, deptid, since, addr, picture, ssn, size, exceptions, false);
  }
  protected void insertToGFXDTable(Connection conn, int[] eid, String[] emp_name,
      int[] deptid, Date[] since, String[] addr, Blob[] picture, String[] ssn,
      int size, List<SQLException> exceptions, boolean isPut) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement(isPut ? put : insert);
    int tid = getMyTid();
    int count = -1;
    
    for (int i=0 ; i<size ; i++) {
      try {
        count = insertToTable(stmt, eid[i], emp_name[i], deptid[i], since[i], addr[i], 
            picture[i], ssn[i], tid, isPut); 
        if (count != ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue()) {
          Log.getLogWriter().info("Gfxd insert has different row count from that of derby " +
            "derby inserted " + ((Integer)verifyRowCount.get(tid+"_insert"+i)).intValue() +
            " but gfxd inserted " + count);
        }
      } catch (SQLException se) {
        SQLHelper.handleGFGFXDException(se, exceptions);  
      }
    }
  }

  //for gemfirexd
  protected void insertToGFXDTable(Connection conn, int[] eid, String[] emp_name,
      int[] deptid, Date[] since, String[] addr, Blob[] picture, String[] ssn,
      int size, boolean isPut) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement(isPut ? put : insert);
    int tid = getMyTid();
    
    for (int i=0 ; i<size ; i++) {
      try {
        insertToTable(stmt, eid[i], emp_name[i], deptid[i], since[i], addr[i], 
            picture[i], ssn[i], tid, isPut); 
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);      
      }
    }
  }
  
  //insert the record into database
  protected int insertToTable(PreparedStatement stmt, int eid, String emp_name,
      int deptid, Date since, String addr, Blob picture, String ssn, 
      int tid, boolean isPut) throws SQLException {  
    String blob = null;
    if (picture != null) {
      if (picture.length() == 0) {
        blob = "empty picture";
        if(SQLPrms.isSnappyMode())
          picture = new SerialBlob(blob.getBytes());
      } else blob = ResultSetHelper.convertByteArrayToString(picture.getBytes(1, 
          (int)picture.length()));
    }
    
    String database =  SQLHelper.isDerbyConn(stmt.getConnection())?"Derby - " :"gemfirexd - ";
    
    Log.getLogWriter().info(database + (isPut ? "putting" : "inserting") + " into emp.employees with EID:" + eid + 
        ",EMPNAME:" + emp_name + ",DEPTID:" + deptid +
       ",SINCE:" + since + ",ADDR:" + addr + ",PICTURE:" + blob +
       ",SSN:" + ssn + ",TID:" + tid);
    
    stmt.setInt(1, eid);
    stmt.setString(2, emp_name);
    stmt.setInt(3, deptid);
    stmt.setDate(4, since);
    stmt.setString(5, addr);  
    if (picture == null)
      stmt.setNull(6, Types.BLOB);
    else
      stmt.setBlob(6, picture);
    stmt.setString(7, ssn);
    stmt.setInt(8, tid);      
    int rowCount = stmt.executeUpdate();
    SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
    if (warning != null) {
      SQLHelper.printSQLWarning(warning);
    } 
    
    Log.getLogWriter().info(database + (isPut ? "put " : "inserted ") + rowCount + " rows into emp.employees EID:" + eid + 
        ",EMPNAME:" + emp_name + ",DEPTID:" + deptid +
       ",SINCE:" + since + ",ADDR:" + addr + ",PICTURE:" + blob +
       ",SSN:" + ssn + ",TID:" + tid);
    return rowCount;
  }  
  
  public ResultSet getQuery(Connection conn, int whichQuery, int eid, Date since,
      String addr, String empName) {
    int tid = getMyTid();
    int tid2 = (tid == 0)? tid +1 : tid -1;
    return getQuery(conn, whichQuery, eid, since, addr, empName, tid, tid2);
  }
  
  //retries when lock could not obtained
  public static ResultSet getQuery(Connection conn, int whichQuery, int eid, Date since, 
      String addr, String empName, int tid, int tid2) {
    boolean[] success = new boolean[1];
    ResultSet rs = getQuery(conn, whichQuery, eid, since, addr, 
        empName, tid, tid2, success);
    int count = 0;
    while (!success[0]) {
      if (count >= maxNumOfTries) {
        if (SQLHelper.isDerbyConn(conn))
          Log.getLogWriter().info("Could not get the lock to finisht the op in derby, " +
              "abort this operation");
        return null; 
      };
      count++;        
      MasterController.sleepForMs(rand.nextInt(retrySleepMs));
      rs = getQuery(conn, whichQuery, eid, since, addr, empName, tid, tid2, success);
    }
    return rs;
  }
  
  public static ResultSet getQuery(Connection conn, int whichQuery, int eid, Date since, 
      String addr, String empName, int tid, int tid2, boolean[] success) {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    

    
    try {
      String database =  SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
      String query = " QUERY: " + select[whichQuery];
      stmt = conn.prepareStatement(select[whichQuery]);      
      switch (whichQuery){
      case 0:
        // "select * from emp.employees where tid = ?",
        Log.getLogWriter().info(database + "querying emp.employees with TID:"+ tid + query);
        stmt.setInt(1, tid);
        break;
      case 1: 
        //"select eid, since, picture, emp_name from emp.employees where tid=? and eid >?",
        Log.getLogWriter().info(database + "querying emp.employees with EID:" + eid + ",TID:"+ tid + query);
        stmt.setInt(1, tid);
        stmt.setInt(2, eid); 
        break;
      case 2:
        //"select since, picture, addr, eid from emp.employees where (eid <=? or addr >?)
        // and picture IS NOT NULL and tid = ?",    
        Log.getLogWriter().info(database + "querying emp.employees with EID:" + eid + 
            ",ADDR:" + addr + ",TID:"+ tid + query);
        stmt.setInt(1, eid);
        stmt.setString(2, addr); 
        stmt.setInt(3, tid);
        break;
      case 3:
        //"select max(eid) from emp.employees where (emp_name >? or since <?) and tid = ?",
        Log.getLogWriter().info(database + "querying emp.employees with EMPNAME:" + empName +
            ",SINCE:"+ since + ",TID:"+ tid + query);        
        stmt.setString(1, empName); 
        stmt.setDate(2, since); 
        stmt.setInt(3, tid);
        break;
      case 4:
        //"select * from emp.employees where tid IN (?, ?) ",
        Log.getLogWriter().info(database + "querying emp.employees with TID:"+ tid + ",2_TID:" + tid2 + query);
        stmt.setInt(1, tid);
        stmt.setInt(2, tid2);
        break;
      case 5: 
        //"select eid, since, picture, emp_name from emp.employees where eid >?",
        Log.getLogWriter().info(database + "querying emp.employees with EID:" + eid + query);
        stmt.setInt(1, eid); 
        break;
      case 6:
        //""select since, picture, addr, eid from emp.employees where (eid <=? or addr >?) 
        // and picture IS NOT NULL",    
        Log.getLogWriter().info(database + "querying emp.employees with EID: " + eid + 
            ",ADDR:" + addr + query);
        stmt.setInt(1, eid);
        stmt.setString(2, addr); 
        break;
      case 7:
        //"select max(eid) from emp.employees where (emp_name >? or since <?)",
        Log.getLogWriter().info(database + "querying emp.employees with EMPNAME:" + empName +
            ",SINCE:"+ since + query);        
        stmt.setString(1, empName); 
        stmt.setDate(2, since); 
        break;   
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (!SQLHelper.checkGFXDException(conn, se)) success[0] = false; //hand X0Z01 and #41471
      else      SQLHelper.handleSQLException(se);
    }
    return rs;
  }
  
  @Override
  public void put(Connection dConn, Connection gConn, int size) {
    int[] eid = new int[size];
    String[] emp_name = new String[size];
    int[] deptid = new int[size];
    Date[] since = new Date[size];
    String[] addr = new String[size];
    Blob[] picture = new Blob[size];
    String[] ssn = new String[size];
    List<SQLException> exList = new ArrayList<SQLException>();
    boolean success = false;
    getDataForInsert(eid, emp_name,since,addr, deptid, picture, ssn, size); //get the data
    //boolean executeBatch = rand.nextBoolean();

    if (dConn != null && !useWriterForWriteThrough) {
      try {
        success = insertToDerbyTable(dConn, eid, emp_name, deptid, since, addr, 
            picture, ssn, size, exList);  //insert to derby table 
        int count = 0;
        while (!success) {
          if (count>=maxNumOfTries) {
            Log.getLogWriter().info("Could not get the lock to finish insert into derby, abort this operation");
            return;
          }
          MasterController.sleepForMs(rand.nextInt(retrySleepMs));
          exList .clear();
          success = insertToDerbyTable(dConn, eid, emp_name, deptid, since, addr, 
              picture, ssn, size, exList);  //retry insert to derby table                  
          count++;
        }
        insertToGFXDTable(gConn, eid, emp_name, deptid, since, addr, 
            picture, ssn, size, exList, true);    //insert to gfxd table 
        
        commit(dConn);
        commit(gConn);
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("insert to emp.employees fails\n" + TestHelper.getStackTrace(se));
      }  //for verification

    }
    else {
      try {
        insertToGFXDTable(gConn, eid, emp_name, deptid, since, addr, picture, ssn, size, true);    //insert to gfxd table 
      } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("put to gfxd emp.emploees fails\n" + TestHelper.getStackTrace(se));
      }
    } 

  }

}
