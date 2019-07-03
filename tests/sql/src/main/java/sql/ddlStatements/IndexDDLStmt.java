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
package sql.ddlStatements;

import hydra.RemoteTestModule;

import java.sql.*;
import java.util.*;

import hydra.Log;
import hydra.TestConfig;

import sql.*;
import sql.mbeans.MBeanTest;
import sql.mbeans.listener.CallBackListener;

/**
 * @author eshu
 *
 */
public class IndexDDLStmt implements DDLStmtIF {
  protected static final Random rand = SQLTest.random;
  protected static String[] sort = {"asc ", "desc ", " "};
  protected static String[] types = {/*"unique ", will be removed from doc*/ "global hash " };  //different type
  protected static HashMap<String, List<String>> tableCols = new HashMap<String, List<String>>(); //get column names for each table
  protected static boolean[] setTableCols = {false}; // to be set after get table column info
  protected static String[] tables = SQLPrms.getTableNames(); //which tables will be used for creating index
  protected static ArrayList<String> indexList = new ArrayList<String>(); //used for create and drop index by names
  protected static boolean dropIndex = TestConfig.tab().booleanAt(SQLPrms.dropIndex, false);
  protected static int minNumIndex = 8;
  protected static int maxNumIndex = 12;
  protected static IndexDDLStmt instance = new IndexDDLStmt();
  protected static boolean renameIndex = TestConfig.tab().booleanAt(SQLPrms.renameIndex, false);
  protected static boolean isOfflineTest = TestConfig.tab().booleanAt(SQLPrms.isOfflineTest, false);
  protected static boolean testUniqIndex = SQLTest.testUniqIndex;
  private static long lastDDLOpTime = 0;
  private static int waitPeriod = 3; //3 minutes
  private static String lastIndexOpTime = "lastIndexOpTime";
  private static boolean portfoliov1IsReady = false;
  private static String portfoliov1 = "portfoliov1";
  private static boolean hasPortfolioV1 = SQLTest.hasPortfolioV1;
  /* (non-Javadoc)
   * @see sql.ddlStatements.DDLStmtIF#doDDLOp(java.sql.Connection, java.sql.Connection)
   */
  private List<CallBackListener> listeners = new ArrayList<CallBackListener>();
  
  public void doDDLOp(Connection dConn, Connection gConn) {
    //logic wheter allow concurrent ddl ops in test and whether limit 
    //concurrent ddl ops in the test run
    if (!RemoteTestModule.getCurrentThread().getCurrentTask().getTaskTypeString().
        equalsIgnoreCase("INITTASK")) {
      if (!SQLTest.allowConcDDLDMLOps) {
        Log.getLogWriter().info("This test does not run with concurrent ddl with dml ops, " +
        		"abort the op");
        return;
      } else {
        if (SQLTest.limitConcDDLOps) {
          //this is test default setting, only one concurrent index op in 3 minutes
          if (!perfDDLOp()) {
            Log.getLogWriter().info("Does not meet criteria to perform concurrent ddl right now, " +
            "abort the op");
            return;
          } else {
            Long lastUpdateTime = (Long) SQLBB.getBB().getSharedMap().get(lastIndexOpTime);
            if (lastUpdateTime != null) {
              long now = System.currentTimeMillis();
              lastDDLOpTime = lastUpdateTime;
              Log.getLogWriter().info(lastIndexOpTime + " currently is set to " + lastDDLOpTime/60000);
              //avoid synchronization in the method, so need to check one more time
              if (now - lastDDLOpTime < waitPeriod * 60 * 1000)  {
                SQLBB.getBB().getSharedCounters().zero(SQLBB.perfLimitedIndexDDL);
                //Log.getLogWriter().info("Does not meet criteria to perform concurrent ddl abort");
                return;
              } 
            }
          }         
        } else {
          //without limiting concurrent DDL ops
          //this needs to be specifically set to run from now on
        }
        Log.getLogWriter().info("performing conuccrent index op in main task");
      }
      
    }
    
    synchronized (setTableCols) {
      if (!setTableCols[0]) {
        getTableColsFromBB();
      }
    }
    //int maxNumPerOp = 1; 
    int numIndex = 1; //no need to create index more than 1 at a time
    
    //concurrent index operation is handled with limitConcDDLOps now
    if (dropIndex && indexList.size() >= minNumIndex) {
      dropIndex(gConn); //only drop index when there are certain number of indexes
    } else if (renameIndex && SQLTest.random.nextBoolean()){
    	renameIndex(gConn);
    } else {    	
    	createIndex(gConn, numIndex);
    }
    
    //reset flag/timer for next ddl op
    if (!RemoteTestModule.getCurrentThread().getCurrentTask().getTaskTypeString().
        equalsIgnoreCase("INITTASK")) {
      if (SQLTest.limitConcDDLOps) {
        long now = System.currentTimeMillis();
        SQLBB.getBB().getSharedMap().put(lastIndexOpTime, now);
        //Log.getLogWriter().info(lastIndexOpTime + " is set to " + now/60000);
        lastDDLOpTime = now;
        SQLBB.getBB().getSharedCounters().zero(SQLBB.perfLimitedIndexDDL);
      }
    }
  }
  
  private boolean perfDDLOp() {
    if (lastDDLOpTime == 0) {
      Long lastUpdateTime = (Long) SQLBB.getBB().getSharedMap().get(lastIndexOpTime);
      if (lastUpdateTime == null) return checkBBForDDLOp();
      else lastDDLOpTime = lastUpdateTime;
    }
    
    long now = System.currentTimeMillis();
    if (now - lastDDLOpTime < waitPeriod * 60 * 1000)  {
      return false;
    } else {
      lastDDLOpTime =  (Long) SQLBB.getBB().getSharedMap().get(lastIndexOpTime);
      //avoid synchronization in the method, so need to check one more time
      if (now - lastDDLOpTime < waitPeriod * 60 * 1000)  {
        return false;
      } else return checkBBForDDLOp();
    }   
  }
  
  private boolean checkBBForDDLOp() {
    int perfLimitedConcDDL = (int)SQLBB.getBB().getSharedCounters().incrementAndRead(
        SQLBB.perfLimitedIndexDDL);
    return perfLimitedConcDDL == 1;
  }
   
  //only being used to determine derby behavior of create same index using different index name
  public static void doDerbyIndexOp(Connection dConn) {
    String createIndexStmt1 =  "create index indexName1 on trade.customers (cust_name) ";
    String createIndexStmt2 =  "create index indexName2 on trade.customers (cust_name) ";
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("creating index statement is " + createIndexStmt1);
      stmt.executeUpdate(createIndexStmt1);
      SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning);
      } 
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("creating index statement is " + createIndexStmt2);
      stmt.executeUpdate(createIndexStmt2);
      SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning);
      } 
    }catch (SQLException se) {
        SQLHelper.handleSQLException(se);
    }
    
    String dropIndexStmt = "drop index indexName2";
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("removing index statement is " + dropIndexStmt);
      stmt.executeUpdate(dropIndexStmt);
    } catch (SQLException se) {
      if (se.getSQLState().equals("42X65"))
        Log.getLogWriter().info("got index name does not exist exception");
      else
        SQLHelper.handleSQLException(se);
    }
    
    dropIndexStmt = "drop index indexName1";
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("removing index statement is " + dropIndexStmt);
      stmt.executeUpdate(dropIndexStmt);
    } catch (SQLException se) {
      if (se.getSQLState().equals("42X65"))
        Log.getLogWriter().info("got index name does not exist exception");
      else
        SQLHelper.handleSQLException(se);
    }

    dropIndexStmt = "drop index trade.indexName2";
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("removing index statement is " + dropIndexStmt);
      stmt.executeUpdate(dropIndexStmt);
    } catch (SQLException se) {
      if (se.getSQLState().equals("42X65"))
        Log.getLogWriter().info("got index name does not exist exception");
      else
        SQLHelper.handleSQLException(se);
    }
    
    dropIndexStmt = "drop index trade.indexName1";
    try {
      Statement stmt = dConn.createStatement();
      Log.getLogWriter().info("removing index statement is " + dropIndexStmt);
      stmt.executeUpdate(dropIndexStmt);
    } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
    }
  }
  
  //uses the gfe connection
  /*
  public static synchronized void setTableCols(Connection conn) {
    try {
      for (int i=0; i<tables.length; i++) {  
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from " + tables[i]);

        //get result set metadata
        ResultSetMetaData rsmd = rs.getMetaData();
        int numCols = rsmd.getColumnCount();
        String[] colNames = new String[numCols];
        rs.close();

        for (int j=0; j<numCols; j++) {
          colNames[j] = rsmd.getColumnName(j+1); //get each column names
          //Log.getLogWriter().info("colNames[" + j +"] is " + colNames[j]);
        } //starts from column 1
        tableCols.put(tables[i], colNames); //put into the map 
      }    
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    SQLBB.getBB().getSharedMap().put("tableCols", tableCols); //put it into the sharedMap
    Log.getLogWriter().info("sets up table columns infomation for index creation");
    setTableCols[0] = true;
  }
  */
  
  //if tableCols is not set, we need to get it from the BB
  @SuppressWarnings("unchecked")
  protected static synchronized void getTableColsFromBB() {
    tableCols.putAll((Map<String, List<String>>)SQLBB.getBB().getSharedMap().get("tableCols"));
    setTableCols[0] = true;
  }
  
  public static void createUniqIndex(Connection conn) {
    if (!testUniqIndex || SQLTest.hasCompanies) {
      Log.getLogWriter().info("unique index will not be created");
      return;
    }
    /* no need to syn the table columns as unique index only created on securities table
    synchronized (setTableCols) {
      if (!setTableCols[0]) {
        getTableColsFromBB();
      }
    }
    */
    String createIndexStmt = "create unique index uniqIndex_" 
      + RemoteTestModule.getCurrentThread().getThreadId() + 
      " on trade.securities (symbol asc, exchange desc)";
    try {
      Statement stmt = conn.createStatement();
      Log.getLogWriter().info("creating index statement is " + createIndexStmt);
      stmt.executeUpdate(createIndexStmt);
      SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning);
      } 
    } catch (SQLException se) {
      /* create unique index on security before populating data should not get unique key exception
      if (se.getSQLState().equalsIgnoreCase("23505")) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("Got the expected exception creating unique index, continuing test");
      } else  
      */
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void createIndex(Connection conn, int num) {
    for (int i=0; i<num; i++) {
      createIndex(conn);
    }
  }
  
  protected void createIndex(Connection conn) {
    //boolean useType = false;
    if (hasPortfolioV1 && !portfoliov1IsReady) {
      Boolean value = (Boolean) SQLBB.getBB().getSharedMap().get(SQLTest.PORTFOLIOV1READY);
      if (value != null) {
        portfoliov1IsReady = value;
        getTableColsFromBB(); //reset table cols for index creation after portfoliov1 is ready
      }
    }
        
    
    int maxCol = 2; //up to create index on 2 columns in a create index statement
    Set<String> tableNames = tableCols.keySet();
    
    String tableName = (String) tableNames.toArray()[rand.nextInt(tableNames.size())];
    if (hasPortfolioV1 && tableName.toLowerCase().contains(portfoliov1) && !portfoliov1IsReady) {
      tableName = "portfolio";
    }
    
    Log.getLogWriter().info("portfoliov1IsReady is " + portfoliov1IsReady);
    Log.getLogWriter().info("table name  is " + tableName);
    List<String> colNames = (List<String>)tableCols.get(tableName);
    int whichCol = rand.nextInt(colNames.size());
    int whichCol2 = (whichCol+1)%colNames.size(); //will be different from the first one
    int numOfCols = 1; //how many columns index will be created on the table
    int createIndexOnMultiCol = 10; //1 in 10 chances
    if (rand.nextInt(createIndexOnMultiCol) == 0) numOfCols = maxCol; 
    
    synchronized (indexList) {
      if (indexList.size()>maxNumIndex) {
        Log.getLogWriter().info("index created in this vm is " + indexList.size() +
            " which is more than " + maxNumIndex + " now, will not create index this time.");
        return;
      }
    }
    
    String type = " ";
    long count = SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.indexCount);
    String indexName = "index" /*+ RemoteTestModule.getCurrentThread().getThreadId() */ + "_" + count;
    if(SQLPrms.isSnappyMode()) indexName = "trade."  + indexName;
    int createTypeIndex = 10; //1 in 10 chances
    if (rand.nextInt(createTypeIndex) == 0) {
      type = types[rand.nextInt(types.length)];
      numOfCols = rand.nextInt(maxCol);  //create type index on multi col should throw exception
      //useType = true;
    }
     
    String cols = colNames.get(whichCol) + " " + sort[rand.nextInt(sort.length)];
    if (numOfCols == 2) 
      cols += " ," +  colNames.get(whichCol2) + " " + sort[rand.nextInt(sort.length)];  //add the second column
    
    String createIndexStmt = "create " /* + type */ + " index " + indexName + " on " + tableName + 
    " ( " + cols + " ) ";  //need to uncomment out type after bug# 39777
    
    try {
      Statement stmt = conn.createStatement();
      Log.getLogWriter().info("creating index statement is " + createIndexStmt);
      stmt.executeUpdate(createIndexStmt);
      SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning); 
        //TODO add method try to drop the index and should got the expected exception
      }
      else {
        synchronized (indexList) {
          indexList.add(indexName); //used for track indexName created
          executeListeners(MBeanTest.CREATE_INDEX, indexName, tableName);
        }
        Log.getLogWriter().info("index created for the statement: " + createIndexStmt);
      }
      conn.commit();
      executeListeners(MBeanTest.CONN_COMMIT_TAG);
    } catch (SQLException se) {
      SQLHelper.printSQLException(se);
      if (se.getSQLState().equalsIgnoreCase("23505") && type.equalsIgnoreCase("unique ")) {
        Log.getLogWriter().info("Got the expected exception creating unique index, continuing test");
      } else if (se.getSQLState().equalsIgnoreCase("XSAS3") && type.equalsIgnoreCase("global hash ") 
          && !sort.equals(" ")) {
        Log.getLogWriter().info("Got the expected exception creating global hash index with sort" +
                        ", continuing test");
      } else if ((se.getSQLState().equalsIgnoreCase("X0Z08") || se.getSQLState().equalsIgnoreCase("X0Z01")) 
          && isOfflineTest) {
        //handle expected exception raised in #51700
        Log.getLogWriter().info("Got the expected exception during creating index when no nodes are available" +
                        ", continuing test");
      } else if (se.getSQLState().equalsIgnoreCase("X0X67")) {
        Log.getLogWriter().info("Got the expected exception during creating index on BLOB field" +
        ", continuing test");
      } else if (se.getSQLState().equalsIgnoreCase("42Y62") && hasPortfolioV1 && portfoliov1IsReady) {
        Log.getLogWriter().info("Got the expected exception not able to create index on a view" +
        ", continuing test");
      } else if (SQLTest.setTx && SQLHelper.gotTXNodeFailureException(se)) {
        Log.getLogWriter().info("Got the expected node failure exception with txn" +
        ", continuing test");
      } else  SQLHelper.handleSQLException(se);
    }
  }
  
  private void executeListeners(String listenerName, Object... params) {
    for(CallBackListener listener : listeners) {
      if(listenerName.equals(listener.getName())) {
        listener.execute(params);
      }
    }
  }

  //drop one of the index using the index name in the index list
  protected void dropIndex(Connection conn) {
    String indexName;
    synchronized (indexList) {
      if (indexList.size() == 0) return; //no indexes available to drop
      indexName = (String) indexList.remove(rand.nextInt(indexList.size()));
      if (!indexName.startsWith("trade."))
        indexName = "trade." + indexName; //remove on of index name of the list
    }
    String dropIndexStmt = "drop index " + indexName;
    try {
      Statement stmt = conn.createStatement();
      Log.getLogWriter().info("removing index statement is " + dropIndexStmt);
      stmt.executeUpdate(dropIndexStmt);
      Log.getLogWriter().info("index removed for the statement: " + dropIndexStmt);
      executeListeners(MBeanTest.DROP_INDEX, indexName);
    } catch (SQLException se) {
      if ((se.getSQLState().equalsIgnoreCase("X0Z08") || se.getSQLState().equalsIgnoreCase("X0Z01"))
          && isOfflineTest) {
        //handle expected exception (#51700)
        Log.getLogWriter().info("Got the expected exception during dropping index when no nodes are available" +
                        ", continuing test");
      } else SQLHelper.handleSQLException(se);
    }
  }
  
  //drop one of the index using the index name in the index list
  protected void renameIndex(Connection conn) {
    String indexName;
    String newIndexName;
    synchronized (indexList) {
      if (indexList.size() == 0) return; //no indexes available to drop
      indexName = (String) indexList.remove(rand.nextInt(indexList.size())); //remove one of index name of the list
      newIndexName = indexName + "a";
    }
    String renameIndexStmt = "rename index trade." + indexName + " to " + newIndexName;
    try {
      Statement stmt = conn.createStatement();
      Log.getLogWriter().info("rename index statement is " + renameIndexStmt);
      stmt.executeUpdate(renameIndexStmt);
      Log.getLogWriter().info("index renamed for the statement: " + renameIndexStmt);
      SQLWarning warning = stmt.getWarnings(); //test to see there is a warning
      if (warning != null) {
        SQLHelper.printSQLWarning(warning); 
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    synchronized (indexList) {
      indexList.add(newIndexName); //add the renamed index back to the list
      executeListeners(MBeanTest.RENAME_INDEX, indexName);
    }
  }

  // create index does not modify sql data
  public void createDDLs(Connection dConn, Connection gConn) {
    doDDLOp(dConn, gConn);
  }

  public ArrayList<String> getIndexList() {
    return indexList;
  }

  public void addListener(CallBackListener callBackListener) {
    listeners.add(callBackListener);
    
  }
}
