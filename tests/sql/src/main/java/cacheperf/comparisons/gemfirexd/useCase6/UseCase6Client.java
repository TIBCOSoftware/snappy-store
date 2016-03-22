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
package cacheperf.comparisons.gemfirexd.useCase6;

import cacheperf.CachePerfPrms;
import cacheperf.comparisons.gemfirexd.*;
import cacheperf.comparisons.gemfirexd.useCase6.UseCase6Stats.Stmt;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import hydra.*;
import hydra.gemfirexd.*;
import java.io.*;
import java.rmi.RemoteException;
import java.sql.*;
import java.util.*;
import objects.query.QueryPrms;
import org.apache.hadoop.classification.InterfaceAudience;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.datagen.*;
import sql.generic.SqlUtilityHelper;
import sql.sqlutil.ResultSetHelper;
import util.TestException;

public class UseCase6Client extends QueryPerfClient {

  protected static final String CONFLICT_STATE = "X0Z02";
  protected static final String DEADLOCK_STATE = "ORA-00060"; // oracle table lock
  protected static final String DUPLICATE_STR = "The statement was aborted because it would have caused a duplicate key value";

  protected static final int numBuckets = UseCase6Prms.getNumBuckets();
  protected static final boolean timeStmts = UseCase6Prms.timeStmts();

  // trim intervals
  protected static final int TRANSACTIONS = 1580017;

  protected static final String TRANSACTIONS_NAME = "updatetransactions";

  // hydra thread locals
  protected static HydraThreadLocal localuseCase6stats = new HydraThreadLocal();

  private static final String selstm = "SELECT * FROM OLTP_PNP_Subscriptions WHERE msisdn = ? AND walletname = ? ORDER BY expirydate desc fetch first 1 rows only";
  private static final String updstm = "UPDATE OLTP_PNP_Subscriptions SET expirydate = ? WHERE id = ?";

  private PreparedStatement selstmPS = null;
  private PreparedStatement updstmPS = null;

  public UseCase6Stats useCase6stats; // statistics

  protected boolean logQueries;
  protected boolean logQueryResults;

  protected ResultSet rs = null;
  protected int result = 0;
  protected static final ArrayList<String>[] paramValues = new ArrayList[2];

//------------------------------------------------------------------------------
// statistics task
//------------------------------------------------------------------------------

  /**
   *  TASK to register the useCase6 performance statistics object.
   */
  public static void openStatisticsTask() {
    UseCase6Client c = new UseCase6Client();
    c.openStatistics();
  }

  private void openStatistics() {
    this.useCase6stats = getUseCase6Stats();
    if (this.useCase6stats == null) {
      this.useCase6stats = UseCase6Stats.getInstance();
      RemoteTestModule.openClockSkewStatistics();
    }
    setUseCase6Stats(this.useCase6stats);
  }
  
  /** 
   *  TASK to unregister the performance statistics object.
   */
  public static void closeStatisticsTask() {
    UseCase6Client c = new UseCase6Client();
    c.initHydraThreadLocals();
    c.closeStatistics();
    c.updateHydraThreadLocals();
  }
  
  protected void closeStatistics() {
    MasterController.sleepForMs(2000);
    if (this.useCase6stats != null) {
      RemoteTestModule.closeClockSkewStatistics();
      this.useCase6stats.close();
    }
  }

//------------------------------------------------------------------------------
// ddl
//------------------------------------------------------------------------------

  public static void executeDDLTask()
  throws FileNotFoundException, IOException, SQLException {
    UseCase6Client c = new UseCase6Client();
    c.initialize();
    if (c.sttgid == 0) {
      c.executeDDL();
    }
  }
  private void executeDDL()
  throws FileNotFoundException, IOException, SQLException {
    String fn = getDDLFile(UseCase6Prms.getDDLFile());
    List<String> stmts = getDDLStatements(fn);
    for (String stmt : stmts) {
      if (this.queryAPI == QueryPrms.GFXD) {
        if (stmt.contains("partition")) {
          stmt += " buckets " + this.numBuckets;
        }
      }
      Log.getLogWriter().info("Executing DDL: " + stmt);
      try {
        execute(stmt, this.connection);
      } catch (SQLException e) {
        if (stmt.contains("DROP") &&
            e.getMessage().indexOf("does not exist") == -1 && // GFXD/MYSQL
            e.getMessage().indexOf("Unknown table ") == -1) { // ORACLE
          throw e;
        }
      }
      commitDDL();
    }
  }

  private ResultSet execute(String stmt, Connection conn)
          throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Executing: " + stmt + " on: " + conn);
    }
    ResultSet rs = null;
    Statement s = conn.createStatement();
    boolean result = s.execute(stmt);
    if (result == true) {
      rs = s.getResultSet();
    }
    if (this.logQueries) {
      Log.getLogWriter().info("Executed: " + stmt + " on: " + conn);
    }
    s.close();
    return rs;
  }
  private ResultSet executequery(String stmt, Connection conn) throws SQLException {
    ResultSet sel = null;
    PreparedStatement select = null;
    select = conn.prepareStatement(stmt);
    sel = select.executeQuery();
    int[] rowCounts = UseCase6Prms.getInitialRowCountToPopulateTable();
    if (sel.next()) {
      int count = sel.getInt(1);
      if(count == rowCounts[0])
        Log.getLogWriter().info("Found result count for query ( " + stmt + ") " + count);
      else
        throw new TestException("Expected result count for (" + stmt + ") is " + rowCounts[0] + " but found " + count);
    }
    sel.close();
    sel = null;
    return sel;
  }
  private String getDDLFile(String fname) {
    String fn = "$JTESTS/cacheperf/comparisons/gemfirexd/useCase6/ddl/" + fname;
    String newfn = EnvHelper.expandEnvVars(fn);
    Log.getLogWriter().info("DDL file: " + newfn);
    return newfn;
  }

  private List<String> getDDLStatements(String fn)
  throws FileNotFoundException, IOException {
    Log.getLogWriter().info("Reading statements from " + fn);
    String text = FileUtil.getText(fn).trim();
    StringTokenizer tokenizer = new StringTokenizer(text, ";", false);
    List<String> stmts = new ArrayList();
    while (tokenizer.hasMoreTokens()) {
      String stmt = tokenizer.nextToken().trim();
      stmts.add(stmt);
    }
    Log.getLogWriter().info("Read statements: " + stmts);
    return stmts;
  }

  private void commitDDL() {
    if (this.queryAPI != QueryPrms.GFXD) {
      try {
        this.connection.commit();
      } catch (SQLException e) {
        throw new QueryPerfException("Commit failed: " + e);
      }
    } // GFXD does not need to commit DDL
  }

//------------------------------------------------------------------------------
// support
//------------------------------------------------------------------------------

  public static void dumpBucketsHook() {
    String clientName = RemoteTestModule.getMyClientName();
    // @todo rework this to determine if this is a datahost
    if (clientName.contains("server")
        || clientName.contains("sender") || clientName.contains("receiver")
        || clientName.contains("dbsync") || clientName.contains("prdata")) {
      Log.getLogWriter().info("Dumping local buckets");
      ResultSetHelper.dumpLocalBucket();
      Log.getLogWriter().info("Dumped local buckets");
    }
  }

  public static void dumpBucketsTask() {
    UseCase6Client c = new UseCase6Client();
    c.initialize();
    if (c.jid == 0) {
      Log.getLogWriter().info("Dumping local buckets");
      ResultSetHelper.dumpLocalBucket();
      Log.getLogWriter().info("Dumped local buckets");
    }
  }

//------------------------------------------------------------------------------
// hydra thread locals
//------------------------------------------------------------------------------

  protected void initHydraThreadLocals() {
    super.initHydraThreadLocals();
    this.useCase6stats = getUseCase6Stats();
  }

  protected void updateHydraThreadLocals() {
    super.updateHydraThreadLocals();
    setUseCase6Stats(this.useCase6stats);
  }

  /**
   * Gets the per-thread UseCase6Stats wrapper instance.
   */
  protected UseCase6Stats getUseCase6Stats() {
    useCase6stats = (UseCase6Stats)localuseCase6stats.get();
    return useCase6stats;
  }

  /**
   * Sets the per-thread UseCase6Stats wrapper instance.
   */
  protected void setUseCase6Stats(UseCase6Stats useCase6stats) {
    localuseCase6stats.set(useCase6stats);
  }

//------------------------------------------------------------------------------
// OVERRIDDEN METHODS
//------------------------------------------------------------------------------

  protected void initLocalParameters() {
    super.initLocalParameters();

    this.logQueries = QueryPrms.logQueries();
    this.logQueryResults = QueryPrms.logQueryResults();
  }

  protected String nameFor(int name) {
    switch (name) {
      case TRANSACTIONS:
        return TRANSACTIONS_NAME;
    }
    return super.nameFor(name);
  }

  protected boolean getLogQueries() {
    return this.logQueries;
  }

  protected void setLogQueries(boolean b) {
    this.logQueries = b;
  }


  public static void generateAndLoadDataTask() throws SQLException {
    UseCase6Client c = new UseCase6Client();
    c.initialize();
    if (c.ttgid == 0) {
      c.generateAndLoadData();
    }
  }
  private String getMapperFileAbsolutePath() {
    String mPath = System.getProperty("JTESTS") + "/"
            + TestConfig.tab().stringAt(UseCase6Prms.mapperFile, null);
    return mPath;
  }

  public void generateAndLoadData() throws SQLException{
    // this will init DataGenerator, create CSVs for populate and create rows for inserts
    String[] tableNames = UseCase6Prms.getTableNames();;
    int[] rowCounts = UseCase6Prms.getInitialRowCountToPopulateTable();
    String mapper = getMapperFileAbsolutePath();
    DataGeneratorHelper.initDataGenerator(mapper, tableNames, rowCounts, this.connection);
    populateTables(this.connection);
    //executequery("SELECT count(*) FROM OLTP_PNP_Subscriptions", this.connection);
    //executequery("SELECT * FROM OLTP_PNP_Subscriptions", this.connection);
  }
  public void populateTables(Connection gConn){
    //populate gfxd tables from CSVs
    int totalThreads = SqlUtilityHelper.totalTaskThreads();
    int ttgid = SqlUtilityHelper.ttgid();
    String[] tableNames = UseCase6Prms.getTableNames();
    for(int i=0; i < tableNames.length ; i++){
     if ((ttgid % totalThreads == totalThreads - 1) && SqlUtilityHelper.getRowsInTable(tableNames[i], gConn) <= 0  ) {       // thus make is single threaded and should not import if data is already in table
        populateTables( tableNames[i], gConn);
      }
    }
  }
  private void populateTables(final String fullTableName, final Connection gConn) {
    String[] names = fullTableName.trim().split("\\.");
    String schemaName = names[0];
    String tableName = names[1];
    String csvFilePath = DataGeneratorBB.getCSVFileName(fullTableName);

    importTablesToGfxd(gConn, schemaName, tableName,csvFilePath);
    commitDDL();
  }
  private void importTablesToGfxd(Connection conn, String schema,
                                    String table , String csvPath ){

    Log.getLogWriter().info("Gfxd - Data Population Started for " + schema + "." + table + " using " + csvPath );
    String delimitor = ",";
    String procedure = "sql.datagen.ImportOraDG";
    String importTable = "CALL SYSCS_UTIL.IMPORT_DATA_EX(?, ?, null,null, ?, ?, NULL, NULL, 0, 0, 10, 0, ?, null)";
    try {
      CallableStatement cs = conn.prepareCall(importTable);
      cs.setString(1, schema.toUpperCase());
      cs.setString(2, table.toUpperCase());
      cs.setString(3, csvPath);
      cs.setString(4, delimitor);
      cs.setString(5, procedure);
      cs.execute();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    Log.getLogWriter().info("Gfxd - Data Population Completed for " + schema + "." + table );

  }

  public static void storeUniqueDataFromTableTask() throws SQLException {
    UseCase6Client c = new UseCase6Client();
    c.initialize();
    c.storeUniqueDataFromTable();
  }
  private void storeUniqueDataFromTable() throws SQLException {
    paramValues[0] = new ArrayList<>();
    paramValues[1] = new ArrayList<>();
    final PreparedStatement ps = this.connection.prepareStatement("select distinct msisdn, walletname from OLTP_PNP_Subscriptions");
    final ResultSet paramRS = ps.executeQuery();
    Log.getLogWriter().info("populating msisdn and walletnames");
    int counter = 0;
    while (paramRS.next()) {
      paramValues[0].add(paramRS.getString(1)); // msisdn
      paramValues[1].add(paramRS.getString(2)); // walletname
      counter++;
      if (counter % 50000 == 0)
        Log.getLogWriter().info("done loading " + counter + " items");
    }

    paramRS.close();
    ps.close();
  }
  public static void selectAndUpdateTask() throws SQLException {
    UseCase6Client c = new UseCase6Client();
    //c.initialize();
    c.initialize(TRANSACTIONS);
    //c.useCase6SelectAndUpdateTask();
    c.useCase6SelectAndUpdate();
  }
  private void useCase6SelectAndUpdateTask() throws SQLException {
//    useCase6SelectAndUpdate();
    Random rand = new Random();
    do {
      executeTaskTerminator(); // does not commit
      executeWarmupTerminator(); // does not commit
      //useCase6SelectAndUpdate();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator()); // does not commit
  }
  private void useCase6SelectAndUpdate() throws SQLException {
    PreparedStatement update = null;
    PreparedStatement select = null;
    //Random rand = new Random();
    //int val = rand.nextInt(paramValues[0].size() - 1);
    //Log.getLogWriter().info("useCase6SelectAndUpdateTask - done loading " + paramValues[0] + " items");
    for (int iter = 0; iter < paramValues[0].size() ; iter++) {
      long start = this.useCase6stats.startTransaction();
      select = this.connection.prepareStatement(selstm);
      update = this.connection.prepareStatement(updstm);
      select.setString(1, paramValues[0].get(iter));
      select.setString(2, paramValues[1].get(iter));
      ResultSet sel = select.executeQuery();
      sel.next();
      final String id = sel.getString(1);
      sel.close();
      update.clearParameters();
      final long tpsBegin = System.currentTimeMillis();
      final Timestamp ts = new Timestamp(tpsBegin);
      update.setTimestamp(1, ts);
      update.setString(2, id);
      update.executeUpdate();
      //Log.getLogWriter().info("Updated table with id " + id);
      this.connection.commit();
      this.useCase6stats.endTransaction(start, 0);

      if (select != null) {
        select.close();
        select = null;
      }
      if (update != null) {
        update.close();
        update = null;
      }
    }

  }

}
