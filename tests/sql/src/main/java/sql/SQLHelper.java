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
package sql;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.*;
import java.sql.Connection;

import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxPrms;
import sql.dmlStatements.AbstractDMLStmt;
import sql.hadoopHA.HadoopHAPrms;

import com.gemstone.gemfire.cache.hdfs.HDFSIOException;

import hdfs.HDFSUtilBB;
import util.TestException;
import util.TestHelper;
import hydra.*;

/**
 * @author eshu
 *
 */
public class SQLHelper {
  static boolean isHATest = TestConfig.tab().longAt(util.StopStartPrms.numVMsToStop, 0) > 0 ? true : false || TestConfig.tab().booleanAt(SQLPrms.rebalanceBuckets, false);
  protected static boolean useWriterForWriteThrough = TestConfig.tab().booleanAt(SQLPrms.useWriterForWriteThrough,
      false);
  public static String lockIssueSQLState = "LOCKISSUE";	
  static boolean isOfflineTest = TestConfig.tab().booleanAt(SQLPrms.isOfflineTest, false); 
  static boolean setCriticalHeap = SQLTest.setCriticalHeap;
  static boolean batchingWithSecondaryData = !TestConfig.tab().booleanAt(SQLTxPrms.nobatching, true);
  public static boolean supportHAInTxn = false;
  
  public static void printSQLException(SQLException e) {
    while (e != null) {
      Log.getLogWriter().info("SQLException: State: " + e.getSQLState());
      Log.getLogWriter().info("severity: " + e.getErrorCode());
      Log.getLogWriter().info(e.getMessage());

      e = e.getNextException();
    }
  }

  public static void printSQLWarning(SQLWarning w) {
    while (w != null) {
      Log.getLogWriter().info("SQLWarning: State: " + w.getSQLState());
      Log.getLogWriter().info("errorCode: " + w.getErrorCode());
      Log.getLogWriter().info(w.getMessage());

      w = w.getNextWarning();
    }
  }

  public static boolean isSameRootSQLException(String derbySQLState, SQLException gfxdSe) {
    while (gfxdSe != null) {
      if (gfxdSe.getSQLState().equalsIgnoreCase(derbySQLState)
      // workaround #41986
          || gfxdSe.getMessage().contains(derbySQLState))
        return true;

      gfxdSe = gfxdSe.getNextException();
    }
    return false;
  }

  public static void handleSQLException(SQLException se) {
    printSQLException(se);
    Throwable cause = TestHelper.findCause(se, HDFSIOException.class);
    // don't pollute the logs for timeout exceptions since these will
    // again likely throw timeout exceptions
    if (useWriterForWriteThrough && se.getSQLState().equals(lockIssueSQLState)) {
      Log.getLogWriter().info("Writer got lock timeout trying write to back_end DB, abort this operation");
    }/*
    else if (isOfflineTest && (se.getSQLState().equals("X0Z09") || se.getSQLState().equals("X0Z08"))) {
      throw new TestException("got expected Offline exception: " + se.getSQLState() + " "
          + TestHelper.getStackTrace(se));
    } */
    else if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
      Log.getLogWriter().warning("memory runs low and get query cancellation exception");
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled == null) getCanceled = new boolean[1];
      getCanceled[0] = true;
      SQLTest.getCanceled.set(getCanceled);
      return;  //do not check exception list if gfxd gets such exception
    } else if (!SQLTest.hasTx && SQLTest.setTx && !SQLTest.testUniqueKeys && se.getSQLState().equals("X0Z02")) {
      Log.getLogWriter().warning("Got expected conflict exception X0Z02 for non unique keys testings for original tests");
      return;
    } else if (!SQLTest.hasTx && SQLTest.setTx && isHATest && gotTXNodeFailureException(se)) {
      //Log.getLogWriter().info("SQLTest.hasDerbyServer is " + SQLTest.hasDerbyServer);
      if (!SQLTest.hasDerbyServer) {     
        Log.getLogWriter().warning("Got expected node failure exception for non unique keys testings for original tests");
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure == null) getNodeFailure = new boolean[1];
        getNodeFailure[0] = true;
        SQLTest.getNodeFailure.set(getNodeFailure);
      } else {
        Log.getLogWriter().warning("Got expected node failure exception when testings for original tests");
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure == null) getNodeFailure = new boolean[1];
        getNodeFailure[0] = true;
        SQLTest.getNodeFailure.set(getNodeFailure);  
      }
      //Log.getLogWriter().info("getNodeFailure is set to true");
      return;
    } else if (se.getSQLState().equals("X0Z30") && cause != null && (HDFSUtilBB.getBB().getSharedCounters().read(HDFSUtilBB.recycleInProgress) > 0)) {   
        // used with hadoopHA tests: allow X0Z30 Caused by HDFSIOException
        Log.getLogWriter().info("handleSQLException caught SQLException(X0Z30) Caused by: HDFSIOException.  Expected, continuing test");
        throw new TestException("Execute SQL statement failed with: " + se.getSQLState(), se);
    } else
      throw new TestException("Execute SQL statement failed with: " + se.getSQLState() + " " 
          + TestHelper.getStackTrace(se));
  }

  // log the exception and add the exception to the list to be used for comparison
  public static void handleDerbySQLException(SQLException se, List<SQLException> exceptionList) {
    printSQLException(se);
    exceptionList.add(se);
  }

  public static void handleSQLException(DBType dbType, SQLException se, List<SQLException> exceptionList ){
	  switch(dbType){
	  case DERBY:
		  handleDerbySQLException(se, exceptionList);
		  break;
	  case GFXD:
		  handleGFGFXDException(se, exceptionList);
		  break;
	  }
  }
  
  // remove the first exception from the list if they are same
  public static void handleGFGFXDException(SQLException se, List<SQLException> exceptionList) {
    Throwable cause;
    SQLException seCause = null;
    if (isOfflineTest && (se.getSQLState().equals("X0Z09")
        || se.getSQLState().equals("X0Z08")
        // also check in case of FK violation (#43104)
        || (se.getSQLState().equals("23503") && (cause = se.getCause()) != null
            && (cause instanceof SQLException)
            && ((seCause = (SQLException)cause).getSQLState().equals("X0Z09")
                || seCause.getSQLState().equals("X0Z08"))))) {
      printSQLException(se);
      if (seCause != null) {
        throw new TestException("got expected exception: "
            + (se.getSQLState() + ':' + seCause.getSQLState()));
      }
      else {
        throw new TestException("got unexpected exception: " + se.getSQLState());
      }
    } 
    
    if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
      Log.getLogWriter().warning("memory runs low and get query cancellation exception");
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      getCanceled[0] = true;
      SQLTest.getCanceled.set(getCanceled);
      return;  //do not check exception list if gfxd gets such exception
    }
    
    //txn may be used now for original non-txn test
    if (SQLTest.setTx) {
      //when Txn HA is not yet supported
      if (isHATest && gotTXNodeFailureException(se)) {
        Log.getLogWriter().warning("possibly got node failure exception or bucket moved exception");
      
        boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
        if (getNodeFailure == null) getNodeFailure = new boolean[1];
        getNodeFailure[0] = true;
        SQLTest.getNodeFailure.set(getNodeFailure);
        return;  //do not check exception list if gfxd gets such exception
      }
      
      //work around #51582 for now
      else if (SQLTest.testEviction && se.getSQLState().equals("X0Z02") && SQLTest.workaround51582) {
        Log.getLogWriter().info("possibly got #51582");
        
        boolean[] getEvictionConflict = (boolean[]) SQLTest.getEvictionConflict.get();
        if (getEvictionConflict == null) getEvictionConflict = new boolean[1];
        getEvictionConflict[0] = true;
        SQLTest.getEvictionConflict.set(getEvictionConflict);
        return;  //do not check exception list if gfxd gets such exception
      }
    }
    
    if (exceptionList.size() == 0) {
      if(SQLPrms.isSnappyMode() && se.getSQLState()==null) {
        Log.getLogWriter().info("Exception from Snappy...", se);
        while (se != null) {
          se = se.getNextException();
          Log.getLogWriter().info("Caused by: ", se);
        }
      }
      handleSQLException(se);
    } // this is GFE exception, which derby does not have. (possible that never runs on derby)

    printSQLException(se);
    int firstIndex = 0;
    SQLException derbySe = exceptionList.get(firstIndex);

    if (derbySe.getSQLState().equalsIgnoreCase(se.getSQLState())
    // && derbySe.getErrorCode() == se.getErrorCode() /*temp work around for 
    // avoiding different severity thrown */
    ) {
      if (derbySe.getSQLState().equals("38000")) {
        if (derbySe.getNextException().getSQLState().equals(se.getNextException().getSQLState())) {
          exceptionList.remove(firstIndex);
        } else {
          throw new TestException("Not the expected SQLException, might be a miss or an extra exception encountered: \n"
              + "the expected deby SQLException: State: " + derbySe.getSQLState() + "\nseverity: " + derbySe.getErrorCode()
              + "\nmessage: " + derbySe.getMessage() + "\ngemfirexd SQLException: State: " + se.getSQLState()
              + "\nseverity: " + se.getErrorCode() + "\nmessage: " + se.getMessage() + TestHelper.getStackTrace(se));
        }
      } else exceptionList.remove(firstIndex);
    }
    else {
      if (derbySe.getSQLState().equals("38000") || derbySe.getSQLState().equals("XJ208")) {
        //if gfxd using peer driver, it may not be wrapped to 38000
        //or XJ208 for batch update exception -- this may work for 1 row insert/update only
        derbySe = derbySe.getNextException();
        if (derbySe != null) {
          if (derbySe.getSQLState().equalsIgnoreCase(se.getSQLState())) {
            exceptionList.remove(firstIndex);
          } else 
            throw new TestException("Not the expected SQLException, might be a miss or an extra exception encountered: \n"
                + "the expected deby SQLException: State: " + derbySe.getSQLState() + "\nseverity: " + derbySe.getErrorCode()
                + "\nmessage: " + derbySe.getMessage() + "\ngemfirexd SQLException: State: " + se.getSQLState()
                + "\nseverity: " + se.getErrorCode() + "\nmessage: " + se.getMessage() + TestHelper.getStackTrace(se));
        }    
      }
      else 
        throw new TestException("Not the expected SQLException, might be a miss or an extra exception encountered: \n"
          + "the expected deby SQLException: State: " + derbySe.getSQLState() + "\nseverity: " + derbySe.getErrorCode()
          + "\nmessage: " + derbySe.getMessage() + "\n" + TestHelper.getStackTrace(derbySe) 
          + "\ngemfirexd SQLException: State: " + se.getSQLState()
          + "\nseverity: " + se.getErrorCode() + "\nmessage: " + se.getMessage() + TestHelper.getStackTrace(se));
    }
  }

  // remove the first exception from the list if they are same
  public static void handleGFETxSQLException(SQLException se, List<SQLException> exceptionList) {
    printSQLException(se);
    exceptionList.add(se);
  }

  public static void handleDerbyTxSQLException(SQLException se, List<SQLException> exceptionList) {
    printSQLException(se);
    exceptionList.add(se);
  }

  public static void handleMissedSQLException(List<SQLException> exceptionList) {
    //add the check to see if query cancellation exception or low memory exception thrown
    if (setCriticalHeap) {      
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled == null) {
        SQLTest.getCanceled.set(new boolean[1]);
      } else if (getCanceled[0] == true) {
        Log.getLogWriter().info("memory runs low -- avoiding the comparison of expected exceptions");
        return;  //do not check exception list if gfxd gets such exception
      }
    }
    
    if (SQLTest.setTx && isHATest) {
      boolean[] getNodeFailure = (boolean[]) SQLTest.getNodeFailure.get();
      if (getNodeFailure != null && getNodeFailure[0]) {
        Log.getLogWriter().info("getNodeFailure -- avoiding the comparison of expected exceptions");
        return; //do not check exception list      
      }
    }
    
    if (SQLTest.setTx && SQLTest.testEviction && SQLTest.workaround51582) {
      boolean[] getEvictionConflict = (boolean[]) SQLTest.getEvictionConflict.get();
      if (getEvictionConflict != null && getEvictionConflict[0]) {
        Log.getLogWriter().info("get conflict excpetion with eviction -- avoiding the comparison of expected exceptions");
        return; //do not check exception list      
      }
    }
        
    if (exceptionList.size() > 0) {
      int firstIndex = 0;
      SQLException derbySe = exceptionList.get(firstIndex);

      Log.getLogWriter().info("Here are additional derby exceptions that are missed by GFE");
      for (int i = 0; i < exceptionList.size(); i++) {
        printSQLException(exceptionList.get(i));
      }
      //Throwable sme = ResultSetHelper.sendResultMessage();
      //Throwable sbme = ResultSetHelper.sendBucketDumpMessage();
      throw new TestException("GemFireXD missed the expected SQL exceptions thrown by derby: "
          + "the expected deby SQLException: State: " + derbySe.getSQLState() + "\nseverity: " + derbySe.getErrorCode()
          + "\nmessage: " + derbySe.getMessage() + TestHelper.getStackTrace(exceptionList.get(0)) 
          /*+ getStack(sme) + getStack(sbme)*/);
    }
  }
  
  public static void handleMissedSQLException(SQLException se) {
    Log.getLogWriter().info("Here is the exception that is missed");
    printSQLException(se);
    //Throwable sme = ResultSetHelper.sendResultMessage();
    //Throwable sbme = ResultSetHelper.sendBucketDumpMessage();
    throw new TestException("The missed SQL exception is: "
        + "State: " + se.getSQLState() + "\nseverity: " + se.getErrorCode()
        + "\nmessage: " + se.getMessage() + TestHelper.getStackTrace(se) 
        /* + getStack(sme) + getStack(sbme) */);
  }

  private static String getStack(Throwable te) {
    if (te != null)
      return TestHelper.getStackTrace(te);
    else
      return "";
  }

  /**
   * To check if the derby exception is expected and whether need to retry
   *
   * @param conn -- to derby, used to rollback some transactions
   * @param se -- to check if the exception need to retry or not
   * @return false if need to retry
   */
  public static boolean checkDerbyException(Connection conn, SQLException se) {
    printSQLException(se);
    if (!isDerbyConn(conn, se)) {
      Log.getLogWriter().info("Not derby connection, do not retry");
      return true;
    }
    if (se.getSQLState().equals("40001")
        || (se.getSQLState().equals("38000") && 
            (se.getMessage().contains("lock could not be obtained ")
                || se.getNextException().getSQLState().equals("40001")))
        || (se.getSQLState().equals("XJ208") && 
            (se.getMessage().contains("lock could not be obtained ")
                || se.getNextException().getSQLState().equals("40001")))) { 
      // handles the deadlock of aborting
      Log.getLogWriter().info("detected the deadlock, may try it again");
      return false;
    } else if (se.getSQLState().equals("40XL1")
        || (se.getSQLState().equals("XJ208") && se.getNextException().getSQLState().equals("40XL1"))
        || (se.getSQLState().equals("38000") && se.getNextException().getSQLState().equals("40XL1"))) { 
      // handles the "could not obtain lock"
      Log.getLogWriter().info("detected could not obtain lock, may try it again");
      try {
        conn.rollback(); // to retry all
      } catch (SQLException e) {
        SQLHelper.handleSQLException(e); // throws test exception
      }
      return false; // to retry
    } else if (se.getSQLState().equals("08006")) { 
      Log.getLogWriter().info("detected Read timed out, need to get a new connection");
      SQLTest.resetDerbyConnection.set(true);
      return false; // retry will fail with
    } else if (se.getSQLState().equals("08003")) { 
      Log.getLogWriter().info("detected current connection is lost, possibly due to reade time out, " +
      		" need to abort this op and acquire a new connection");
      SQLTest.resetDerbyConnection.set(true);
      return false; //after a few retry, operation will be aborted
    } 
    return true;
  }

  /**
   * To check if the gfxd exception is expected and whether need to retry
   * This should only occur at get query time
   *
   * @param conn
   *          -- only retries if it is gfxd connection
   * @param se
   *          -- to check if the exception need to retry query or not
   * @return false if need to retry
   */
  public static boolean checkGFXDException(Connection conn, SQLException se) {
    if (isDerbyConn(conn, se)) {
      return checkGFXDException(true, se);  
    } else return checkGFXDException(false, se); 
  }
  
  public static boolean checkGFXDException(boolean isDerbyConn, SQLException se) {
    if (isDerbyConn) {
      Log.getLogWriter().info("Not GFXD connection and not X0Z01, do not retry");
      return true;
    }
    if (se.getSQLState().equals("X0Z01") && isHATest) { // handles HA issue for #41471
      Log.getLogWriter().warning("GFXD_NODE_SHUTDOWN happened and need to retry the query");
      return false;
    }
    if (isOfflineTest && (se.getSQLState().equals("X0Z09") || se.getSQLState().equals("X0Z08"))) { // handles OfflineException for #42443
      Log.getLogWriter().warning("Got expected Offline exception, continuing test");
      return false;
    } 
    if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
      Log.getLogWriter().warning("memory runs low and get query cancellation exception");
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      getCanceled[0] = true;
      SQLTest.getCanceled.set(getCanceled);
      return false;  
    } //canceled query could be retried as well.
    
    //should not handle txn node failure exception here, instead it should be handled in the code
    //executing the dml ops in each DistTxDMLStmt
    return true;
  }
  
  public static boolean isAlterTableException(Connection conn, SQLException se) {
    if ((se.getSQLState().equals("42X04") || se.getSQLState().equals("42X14") || se.getSQLState().equals("42802"))
        && SQLTest.alterTableDropColumn) { // handles HA issue for #41471
      Log.getLogWriter().info("got expected missing column issue in alter table");
      return true;
    }
    return false;
  }

  public static boolean isDerbyConn(Connection conn) {
    if (conn == null) {
      throw new TestException("Test issue, connection not set");
    }
    String url = null;
    try {
      url = conn.getMetaData().getURL();
    } catch (SQLException se) {
      // if connection has gotten invalid, check by a simple type check
      if (conn.getClass().getName().startsWith("org.apache.derby")) {
        return true;
      }
      if (conn.getClass().getName().startsWith("com.pivotal.gemfirexd")) {
        return false;
      }
      throw new TestException("Not able to get url" + TestHelper.getStackTrace(se));
    }
    if (url.contains(DiscDBManager.getUrl()) || url.contains(ClientDiscDBManager.getUrl()))
      return true;
    else
      return false;
  }

  /**
   * Connection may no longer be valid so this method also takes the original exception adding it as the cause of thrown
   * exception in case metadata cannot be gotten from the connection.
   */
  public static boolean isDerbyConn(Connection conn, SQLException origEx) {
    String url = null;
    try {
      url = conn.getMetaData().getURL();
    } catch (SQLException se) {
      // if connection has gotten invalid, check by a simple type check
      if (conn.getClass().getName().startsWith("org.apache.derby")) {
        return true;
      }
      if (conn.getClass().getName().startsWith("com.pivotal.gemfirexd")) {
        return false;
      }
      throw new TestException("Not able to get url " + TestHelper.getStackTrace(se), origEx);
    }
    return (url.contains(DiscDBManager.getUrl()) || url.contains(ClientDiscDBManager.getUrl()));
  }

  /**
   * remove from exceptions in the first list if they are also in second list
   *
   * @param se1 -- List1 of SQLException
   * @param se2 -- List2 of SQLException
   */
  public static void removeSameExceptions(List<SQLException> se1, List<SQLException> se2) {
    ArrayList<SQLException> se2Copies = new ArrayList<SQLException>(se2);
    Iterator<SQLException> se1Iter = se1.iterator();
    while (se1Iter.hasNext()) {
      SQLException se = se1Iter.next();
      Iterator<SQLException> se2Iter = se2Copies.iterator();
      while (se2Iter.hasNext()) {
        SQLException se2Copy = se2Iter.next();
        if (se.getSQLState().equalsIgnoreCase(se2Copy.getSQLState())) {
          se1Iter.remove();
          se2Iter.remove();
          break;
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static void compareExceptions(SQLException dse, SQLException sse) {
    if (dse == null && sse == null)
      return;
    else if (dse != null && sse != null) {
      if (!dse.getSQLState().equalsIgnoreCase(sse.getSQLState())) {    
        Log.getLogWriter().warning("compare exceptions failed");
        Log.getLogWriter().info("derby excetpion:");
        printSQLException(dse);
        Log.getLogWriter().info("gfxd excetpion:");
        printSQLException(sse);
        throw new TestException("Exceptions are not same -- for derby: " + TestHelper.getStackTrace(dse)
            + "\n for gfxd: " + TestHelper.getStackTrace(sse));
      } else {
        Log.getLogWriter().info("got the same exceptions from derby and gfxd");
        // printSQLException(dse);
        // printSQLException(sse);
      }
    } else if (dse != null) {      
      printSQLException(dse);
      if (batchingWithSecondaryData) {
        Log.getLogWriter().warning("derby got the exception but gfxd did not, " +
        		"will check if commit failed when batching is enabled");
        ArrayList<SQLException> exs = (ArrayList<SQLException>) sql.sqlTx.SQLDistTxTest.derbyExceptionsWithBatching.get();
        exs.add(dse);
        sql.sqlTx.SQLDistTxTest.derbyExceptionsWithBatching.set(exs);
      } else {
        Log.getLogWriter().warning("compare exceptions failed");
        throw new TestException("derby got the exception, but gfxd did not\n" + TestHelper.getStackTrace(dse));
      }
    } else {
      Log.getLogWriter().warning("compare exceptions failed");
      printSQLException(sse);
      throw new TestException("gfxd got the exception, but derby did not\n" + TestHelper.getStackTrace(sse));
    }

  }

  // only used in tx comparison
  /*
   * take care for the following scenario in networth table
   * t1: thr_1 update in gfxd could cause constraint check violation
   * t2: thr_2 delete the record
   * t3: thr_2 commit
   * t4: thr_1 update to derby will not get the violation, (it won't get commit conflict exception as t1 (thr_1) is a no op)
   */
  public static void compareExceptionLists(List<SQLException> dEx, List<SQLException> sEx) {
    if (sEx.size() != dEx.size())
      throw new TestException("gfxd and derby does not perform same operations");
    for (int i = 0; i < sEx.size(); i++) {
      if (sEx.get(i) != null && sEx.get(i).getSQLState().equals("23513") && dEx.get(i) == null) {
        Log.getLogWriter().info(
            "Got additional check straint violation, which is expected "
                + "as delete could occur earlier for derby and derby will miss the check violation");
      } else {
        compareExceptions(dEx.get(i), sEx.get(i));
      }

    }
  }

  /*
   * Execute an SQL command
   */
  public static void executeSQL(Connection conn, String sqlCommand, boolean doLog) 
  throws SQLException{
    Statement stmt = conn.createStatement();
    if (doLog) {
      Log.getLogWriter().info("sql command: " + sqlCommand);
    }
    stmt.executeUpdate(sqlCommand);
    if (doLog) {
      Log.getLogWriter().info("completed sql command: " + sqlCommand);
    }
  }

  /*
   * Run a sqlScript on a database connection, with a default timeout
   *
   * @return returnStatus
   */
  public static int runSQLScript(Connection conn, String sqlFilePath, boolean failOnError) {
    return runSQLScript(conn, sqlFilePath, 180, failOnError, true);
  }

  /*
   * Run a sqlScript on a database connection, with a specified timeout
   *
   * @return returnStatus
   */
  public static int runSQLScript(Connection conn, String sqlFilePath, int maxSecs, boolean failOnError, boolean logOutput) {
    InputStream sqlScriptStream = ClassLoader.getSystemResourceAsStream(sqlFilePath);
    if (sqlScriptStream == null) {
      throw new TestException("Could not find " + sqlFilePath + " under classpath "
          + System.getProperty("java.class.path"));
    }
    ByteArrayOutputStream sqlOutStream = new ByteArrayOutputStream(20 * 1024);
    int returnStatus = 0;
    try {
      Log.getLogWriter().info("about to run " + sqlFilePath + " on connection " + conn.toString());
      returnStatus = com.pivotal.gemfirexd.internal.tools.ij.runScript(conn, sqlScriptStream, "US-ASCII",
          sqlOutStream, "US-ASCII");
      Log.getLogWriter().info("done running " + sqlFilePath + " with returnStatus=" + returnStatus);
      if (!conn.isClosed() && !conn.getAutoCommit())
        conn.commit();

      sqlScriptStream.close();
    } catch (UnsupportedEncodingException uee) {
      throw new TestException("Test Exception:", uee);
    } catch (IOException ioe) {
      throw new TestException("Test Exception:", ioe);
    } catch (SQLException sqle) {
      throw new TestException("SQL Exception in " + sqlFilePath + ":" + sqlOutStream.toString(), sqle);
    }
    if (logOutput || returnStatus != 0) {
      Log.getLogWriter().info("sql output: " + sqlOutStream.toString());
    }
    if (returnStatus != 0 && failOnError) {
      throw new TestException("SQL Exception on " + sqlFilePath + " " + sqlOutStream.toString());
    }
    return returnStatus;
  }
  
  public static int runSameSQLScript(Connection conn, InputStream sqlScriptStream, boolean failOnError, boolean logOutput) {    
    return runSameSQLScript(conn, sqlScriptStream, 180, failOnError, logOutput);
  }
  
  public static int runSameSQLScript(Connection conn, InputStream sqlScriptStream, int maxSecs, boolean failOnError, boolean logOutput) {
    ByteArrayOutputStream sqlOutStream = new ByteArrayOutputStream(20 * 1024);
    int returnStatus = 0;
    try {
      if (Log.getLogWriter().fineEnabled()) Log.getLogWriter().info("about to run script on connection " + conn.toString());
      returnStatus = com.pivotal.gemfirexd.internal.tools.ij.runScript(conn, sqlScriptStream, "US-ASCII",
          sqlOutStream, "US-ASCII");
      if (Log.getLogWriter().fineEnabled()) Log.getLogWriter().info("done running script with returnStatus=" + returnStatus);
      if (!conn.isClosed() && !conn.getAutoCommit())
        conn.commit();
      
      sqlScriptStream.close();
    } catch (UnsupportedEncodingException uee) {
      throw new TestException("Test Exception:", uee);
    } catch (IOException ioe) {
      throw new TestException("Test Exception:", ioe);
    } catch (SQLException sqle) {
      throw new TestException("SQL Exception in running script" + sqlOutStream.toString(), sqle);
    }
    if (logOutput || returnStatus != 0) {
      Log.getLogWriter().info("sql output: " + sqlOutStream.toString());
    }
    if (returnStatus != 0 && failOnError) {
      throw new TestException("SQL Exception on running script " + sqlOutStream.toString());
    }
    return returnStatus;
  }
 
  public static int runDerbySQLScript(Connection conn, String sqlFilePath, boolean failOnError) {
    return runDerbySQLScript(conn, sqlFilePath, 180, failOnError, true);
  }
  
  public static int runDerbySQLScript(Connection conn, String sqlFilePath, int maxSecs, boolean failOnError, boolean logOutput) {
    InputStream sqlScriptStream = ClassLoader.getSystemResourceAsStream(sqlFilePath);
    if (sqlScriptStream == null) {
      throw new TestException("Could not find " + sqlFilePath + " under classpath "
          + System.getProperty("java.class.path"));
    }
    ByteArrayOutputStream sqlOutStream = new ByteArrayOutputStream(20 * 1024);
    int returnStatus = 0;
    try {
      Log.getLogWriter().info("about to run " + sqlFilePath + " on connection " + conn.toString());
      returnStatus = org.apache.derby.tools.ij.runScript(conn, sqlScriptStream, "US-ASCII",
          sqlOutStream, "US-ASCII");
      Log.getLogWriter().info("done running " + sqlFilePath + " with returnStatus=" + returnStatus);
      if (!conn.isClosed() && !conn.getAutoCommit())
        conn.commit();

      sqlScriptStream.close();
    } catch (UnsupportedEncodingException uee) {
      throw new TestException("Test Exception:", uee);
    } catch (IOException ioe) {
      throw new TestException("Test Exception:", ioe);
    } catch (SQLException sqle) {
      throw new TestException("SQL Exception in " + sqlFilePath + ":" + sqlOutStream.toString(), sqle);
    }
    if (logOutput || returnStatus != 0) {
      Log.getLogWriter().info("sql output: " + sqlOutStream.toString());
    }
    if (returnStatus != 0 && failOnError) {
      throw new TestException("SQL Exception on " + sqlFilePath + " " + sqlOutStream.toString());
    }
    return returnStatus;
  }
  
  public static void compareTxSQLException(SQLException gfxdse, SQLException derbyse) {
    if (gfxdse != null) {
      Log.getLogWriter().info("gfxd exception");
      printSQLException(gfxdse);
    }
    
    if (derbyse != null) {
      Log.getLogWriter().info("derby exception");
      printSQLException(derbyse);
    }
    if (gfxdse != null && derbyse != null && (derbyse.getSQLState().equals("40XL1") && gfxdse.getSQLState().equals("X0Z02"))) {
      Log.getLogWriter().info("derby and gfxd both get lock not held exception");
    } else {
      compareExceptions(derbyse, gfxdse);
    }
  }
  
  public static boolean isThinClient(Connection gConn) {
    try {
      String driverName = gConn.getMetaData().getDriverName();
      if (driverName.contains("Client JDBC Driver")) return true;
      else if (driverName.contains("Embedded JDBC Driver")) return false;
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    throw new TestException("not able to get correct driver name");
  }
  
  //check if the exception is caused by HA
  public static boolean gotTXNodeFailureException(SQLException se) {
    boolean isTicket48176Fixed = true; //turn it on as this is fixed now
    //need to revisit this once txn HA is supported. 
    if (se.getSQLState().equalsIgnoreCase("X0Z05")
        || se.getSQLState().equalsIgnoreCase("X0Z16")
        || se.getSQLState().equalsIgnoreCase("40XD2")
        || se.getSQLState().equalsIgnoreCase("40XD0")
        || (se.getSQLState().equalsIgnoreCase("X0Z01") && !isTicket48176Fixed)) {
      if (SQLTest.hasTx) {
        SQLDistTxTest.needNewConnAfterNodeFailure.set(true);
        Log.getLogWriter().info("needNewConnAfterNodeFailure is set to true");
      }      
      return true;
    }
    else return false;
  }
  
  //check if the exception is caused by HA
  public static boolean gotTXNodeFailureTestException(TestException te) {
    boolean isTicket48176Fixed = true; //turn it on as this is fixed now
    if (te.getMessage().contains("X0Z05")
        || te.getMessage().contains("X0Z16")
        || te.getMessage().contains("40XD2")
        || te.getMessage().contains("40XD0")
        || (te.getMessage().contains("X0Z01") && !isTicket48176Fixed)) {
      if (SQLTest.hasTx) {
        SQLDistTxTest.needNewConnAfterNodeFailure.set(true);
        Log.getLogWriter().info("needNewConnAfterNodeFailure is set to true");
      }    
      return true;
    }
    else return false;
  }
  
  public static void closeResultSet(ResultSet rs, Connection conn) {
    boolean rsclosed = true;
    if (AbstractDMLStmt.isSingleHop && rs != null) {
      try {
        rs.close();
      } catch (SQLException se) {
        if (SQLHelper.isDerbyConn(conn) && !SQLHelper.checkDerbyException(conn, se)) rsclosed = false;
        else if (!SQLHelper.isDerbyConn(conn) && !SQLHelper.checkGFXDException(conn, se)) rsclosed = false; 
        else if (!SQLHelper.isDerbyConn(conn) &&  AbstractDMLStmt.gfxdtxHANotReady && isHATest &&
            SQLTest.hasTx && SQLHelper.gotTXNodeFailureException(se)) rsclosed = false; 
        else {
          Log.getLogWriter().info("close resultset failed with: ");
          SQLHelper.printSQLException(se);
        }
      }
    }    
    if (!rsclosed) {
      Log.getLogWriter().info("Could not close resultset");
    }
  }
}
