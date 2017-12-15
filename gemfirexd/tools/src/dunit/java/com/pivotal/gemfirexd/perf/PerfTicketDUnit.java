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

package com.pivotal.gemfirexd.perf;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gnu.trove.TIntIntHashMap;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;

/**
 * These lists out the performance related tickets that 
 * can be uncommented as required to run and recreate
 * the issue.
 *  
 * @author soubhikc
 */
@SuppressWarnings("serial")
public class PerfTicketDUnit extends DistributedSQLTestBase {

  int netPort;

  public PerfTicketDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }

  public void testDummy() {
  }

  public void __testBug44550() throws Exception {
    Properties props = new Properties();
    props.setProperty("log-level", "config");

    System.setProperty("gemfire.statsDisabled", "true");

    startVMs(1, 1, 0, null, props);
    netPort = startNetworkServer(1, null, null);

    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();

    stmt.execute("create table customer (" +
        "c_w_id         integer        not null," +
        "c_d_id         integer        not null," +
        "c_id           integer        not null," +
        "c_discount     decimal(4,4)," +
        "c_credit       char(2)," +
        "c_last         varchar(16)," +
        "c_first        varchar(16)," +
        "c_credit_lim   decimal(12,2)," +
        "c_balance      decimal(12,2)," +
        "c_ytd_payment  float," +
        "c_payment_cnt  integer," +
        "c_delivery_cnt integer," +
        "c_street_1     varchar(20)," +
        "c_street_2     varchar(20)," +
        "c_city         varchar(20)," +
        "c_state        char(2)," +
        "c_zip          char(9)," +
        "c_phone        char(16)," +
        "c_since        timestamp," +
        "c_middle       char(2)," +
        "c_data         varchar(500)" +
        ") partition by (c_w_id) redundancy 1");

    stmt.execute("create table new_order (" +
        "no_w_id  integer   not null," +
        "no_d_id  integer   not null," +
        "no_o_id  integer   not null" +
        ") partition by (no_w_id) colocate with (customer) redundancy 1");

    stmt.execute("alter table customer add constraint pk_customer " +
        "primary key (c_w_id, c_d_id, c_id)");

    stmt.execute("create index ndx_customer_name " +
        "on customer (c_w_id, c_d_id, c_last)");

    stmt.execute("alter table new_order add constraint pk_new_order " +
        "primary key (no_w_id, no_d_id, no_o_id)");

    stmt.execute("create index ndx_neworder_w_id_d_id " +
        "on new_order (no_w_id, no_d_id)");

    stmt.execute("create index ndx_neworder_w_id_d_id_o_id " +
        "on new_order (no_w_id, no_d_id, no_o_id)");

    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

    final int numRows = 10000;
    PreparedStatement pstmt = conn
        .prepareStatement("insert into new_order values (?, ?, ?)");
    for (int id = 1; id <= numRows; id++) {
      pstmt.setInt(1, id % 98);
      pstmt.setInt(2, id % 98);
      pstmt.setInt(3, id);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
    conn.commit();

    pstmt = conn.prepareStatement("SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = ? AND no_w_id = ? ORDER BY no_o_id ASC");
    ResultSet rs;

    final int numRuns = 50000;
    // warmup for the selects
    for (int i = 1; i <= numRuns; i++) {
      pstmt.setInt(1, i % 98);
      pstmt.setInt(2, i % 98);
      rs = pstmt.executeQuery();
      while (rs.next()) {
        rs.getInt(1);
      }
    }
    conn.commit();

    // timed runs
    long start, end;
    /*
    props = new Properties();
    props.setProperty(Attribute.ENABLE_STATS, "true");
    props.setProperty(Attribute.ENABLE_TIMESTATS, "true");
    conn = TestUtil.getConnection(props);
    stmt = conn.createStatement();
    stmt.execute("call syscs_util.set_statement_statistics(1)");
    stmt.execute("call syscs_util.set_statistics_timing(1)");
    pstmt = conn.prepareStatement("SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = ? AND no_w_id = ? ORDER BY no_o_id asc");
    */
    getLogWriter().info("Starting sleep...");
    Thread.sleep(60000);
    getLogWriter().info("Waking up.");
    conn = TestUtil.getNetConnection(netPort, null, null);
    pstmt = conn.prepareStatement("SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = ? AND no_w_id = ? ORDER BY no_o_id ASC");
    start = System.nanoTime();
    for (int i = 1; i <= numRuns; i++) {
      pstmt.setInt(1, i % 98);
      pstmt.setInt(2, i % 98);
      rs = pstmt.executeQuery();
      while (rs.next()) {
        rs.getInt(1);
      }
    }
    end = System.nanoTime();
    getLogWriter().info("Time taken: " + (end - start) + "ns");
    conn.commit();

    /*
    rs = stmt.executeQuery("explain SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = " + (19898 % 98) + " AND no_w_id = "
        + (19898 % 98) + " ORDER BY no_o_id asc");
    while (rs.next()) {
      getLogWriter().info("Plan: " + rs.getString(1));
    }
    rs.close();

    stmt.execute("call syscs_util.set_statement_statistics(0)");
    stmt.execute("call syscs_util.set_statistics_timing(0)");

    ResultSet r = stmt
        .executeQuery("select STMT_ID, STMT_TEXT from SYS.STATEMENTPLANS");
    getLogWriter().info("Extracting query plans");
    while (r.next()) {
      final String stmt_id = r.getString("STMT_ID");
      String stmtInfo = "stmt_id = " + stmt_id + " statement = "
          + r.getString("STMT_TEXT");

      ExecutionPlanUtils plan = new ExecutionPlanUtils(conn, stmt_id, true);
      getLogWriter().info(
          "Query plan...\n" + stmtInfo + "\n"
              + String.valueOf(plan.getPlanAsText(null)).trim());
    }
    */
  }

  public void __testBug41443Or41444Or41445() throws Exception {
    // start a network server
    netPort = startNetworkServer(1, null, null);

    final Connection conn = TestUtil.getNetConnection(netPort, null, null);
    try {
        Statement s = conn.createStatement();
        s.execute("Create Table TEST_TABLE(idx numeric(12)," +
                        "AccountID varchar(10)," +
                        "OrderNo varchar(20)," +
    //                  "OrderRev varchar(3)," +
    //                  "Product varchar(20)," +
    //                  "OrderType varchar(10)," +
    //                  "LimitPrice varchar(10)," +
    //                  "OrderDateTime varchar(20)," +
    //                  "OrderSize varchar(10)," +
                        "primary key(idx)" +
                        ")" +
                        "PARTITION BY COLUMN ( AccountID ) REDUNDANCY 1");
        
        s.execute("CREATE INDEX idx_AccountID ON test_Table (AccountID ASC)");
    
        Thread execs[] = new Thread[400];
        int mid = (execs.length/2)+1;
        
//        for(int i = 1; i < 2; i++)
        for(int i = 1; i < mid; i++)
          execs[i-1] = createInsertThreadAndExecute(i);
        
//        for(int i = 1; i < 2; i++)
        for(int i = 1; i < mid; i++)
          execs[i+(mid-2)] = createSelectThreadAndExecute(i);
    
        for(int i = 1; i < mid; i++)
        {
          TestUtil.getLogger().info("waiting for "+ execs[i-1].getName());
          execs[i-1].join();
        }

        allInsertsDone = true;
        
        for(int i = 1; i < mid; i++)
        {
          TestUtil.getLogger().info("waiting for "+ execs[i+(mid-2)].getName());
          execs[i+(mid-2)].join();
        }
    } 
    finally {
      stopNetworkServer(1);
    }
  }

  private volatile boolean allInsertsDone = false;
  private Thread createSelectThreadAndExecute(final int tid) {
    Thread t = new Thread(new Runnable() {
      private int maxRowsFetched = 0;
      private int totalRuns=0;
      private int successRuns=0;
      public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, null);
            PreparedStatement selps  = conn.prepareStatement("select * from test_table where accountid=?");
            while(!allInsertsDone) {
              selps.setString(1, String.valueOf(tid-1));
              ResultSet rs = selps.executeQuery();
              totalRuns++;
              int rows = 0;
              while(rs.next()) { rows++; }
              if(rows == 0) {
                TestUtil.getLogger().info("Got " + (tid-1) + " rows="+ rows);
                try {
                  Thread.sleep(500);
                }
                catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
              else if( rows > maxRowsFetched ) {
                maxRowsFetched = rows;
                successRuns++;
              }
              else {
                successRuns++;
              }
              
              if(successRuns % 1000 == 0) {
                TestUtil.getLogger().info("1000 queries succeeded maxRows uptil now " + maxRowsFetched);
              }
            }
          }
          catch (SQLException e) {
            e.printStackTrace();
            return;
          } 
          finally {
            TestUtil.getLogger().info("totalRuns="+totalRuns+" successRuns="+successRuns+" maxRowsFetched="+maxRowsFetched+" Done " + Thread.currentThread().getName());
          }
        
      }
      
    }, "SELECT_T"+tid);
    
    t.start();
    return t;
  }
  
  private Thread createInsertThreadAndExecute(final int tid) {
    Thread t = new Thread(new Runnable() {
      public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, null);
            PreparedStatement insps  = conn.prepareStatement("insert into test_table values(?,?,?)");
//            Random r = Random.class.newInstance();
            int base = tid * 10000;
            for(int i = 0; i < 5000; i++) {
              insps.setInt(1, base+i);
              insps.setString(2, String.valueOf(i%tid));
              insps.setString(3, String.valueOf(i));
              insps.executeUpdate();
              if(i%1000 == 0) {
                TestUtil.getLogger().info("Inserted uptil " +i);
              }
            }
          }
          catch (SQLException e) {
            e.printStackTrace();
            return;
          }
          finally {
            TestUtil.getLogger().info("Done " + Thread.currentThread().getName());
          }
        
      }
      
    }, "INSERT_T"+tid);
    
    t.start();
    return t;
  }

  private final Random random = new Random();

  public void __testuseCase4_1server() throws Exception {
    getLogWriter().info("Testing with 1 server ............");
    // start a locator
    final Properties locProps = new Properties();
    setCommonTestProperties(locProps);
    setCommonProperties(locProps, 0, null, null);
    locProps.remove("locators");
    netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    // also start a network server
    int locPort = TestUtil.startLocator("localhost", netPort, locProps);

    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", "localhost[" + locPort + ']');
    setCommonTestProperties(serverProps);
    startServerVMs(1, 0, null, serverProps);
    startNetworkServer(1, null, null);
    Connection conn = TestUtil.getNetConnection(netPort, null, null);

    profileuseCase4Queries(conn);
    
    conn.close();
    getLogWriter().info("Done with 1 server ............");
  }
  
  public void __testuseCase4_2peerServer() throws Exception {
    getLogWriter().info("Testing with peer connection with 2 server ............");
    
    final Properties props = new Properties();
    setCommonTestProperties(props);
    startVMs(1, 2, 0, null, props);
    
    Connection conn = TestUtil.getConnection();
    
    profileuseCase4Queries(conn);
    
    conn.close();
    getLogWriter().info("Done with peer with 2 server evaluation ............");
  }
  
  public void __testuseCase4_3peerServer() throws Exception {
    getLogWriter().info("Testing with peer connection with 3 server ............");
    
    final Properties props = new Properties();
    setCommonTestProperties(props);
    startVMs(1, 3, 0, null, props);
    
    Connection conn = TestUtil.getConnection();
    
    Thread.sleep(2000);
    profileuseCase4Queries(conn);
    
    conn.close();
    getLogWriter().info("Done with peer with 3 server evaluation ............");
  }
  
  public void __testuseCase4_4peerServer() throws Exception {
    getLogWriter().info("Testing with peer connection with 4 server ............");
    
    final Properties props = new Properties();
    setCommonTestProperties(props);
    startVMs(1, 4, 0, null, props);
    
    Connection conn = TestUtil.getConnection();
    
    Thread.sleep(2000);
    profileuseCase4Queries(conn);
    
    conn.close();
    getLogWriter().info("Done with peer with 4 server evaluation ............");
  }
  
  public void __testuseCase4_2networkServer() throws Exception {
    getLogWriter().info("Testing with 2 n/w server ............");
    
    // start a locator
    final Properties locProps = new Properties();
    setCommonTestProperties(locProps);
    setCommonProperties(locProps, 0, null, null);
    locProps.remove("locators");
    netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    // also start a network server
    int locPort = TestUtil.startLocator("localhost", netPort, locProps);

    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", "localhost[" + locPort + ']');
    setCommonTestProperties(serverProps);
    startServerVMs(2, 0, null, serverProps);
    startNetworkServer(2, null, null);
    startNetworkServer(1, null, null);
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    
    profileuseCase4Queries(conn);
    
    conn.close();
    
    getLogWriter().info("Done with 2 n/w server ............");
  }
  
  public void __testuseCase4_3networkServer() throws Exception {
    getLogWriter().info("Testing with 3 n/w server ............");
    
    // start a locator
    final Properties locProps = new Properties();
    setCommonTestProperties(locProps);
    setCommonProperties(locProps, 0, null, null);
    locProps.remove("locators");
    netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    // also start a network server
    int locPort = TestUtil.startLocator("localhost", netPort, locProps);

    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", "localhost[" + locPort + ']');
    setCommonTestProperties(serverProps);
    startServerVMs(3, 0, null, serverProps);
    startNetworkServer(3, null, null);
    startNetworkServer(2, null, null);
    startNetworkServer(1, null, null);
    
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    
    Thread.sleep(2000);
    profileuseCase4Queries(conn);
    
    conn.close();
    getLogWriter().info("Done with 3 n/w server ............");
  }

  public void __testBug46727() throws Exception {
    startVMs(1, 2);
    final int netPort = startNetworkServer(1, null, null);

    // load the schema and data using an embedded connection
    Connection conn = TestUtil.jdbcConn;
    String scriptsDir = TestUtil.getResourcesDir() + "/lib/";
    GemFireXDUtils.executeSQLScripts(conn, new String[] { scriptsDir
        + "rsa_schema.sql" }, false, getLogWriter(), null, null, false);
    getLogWriter().info("Loading data ...");
    conn.createStatement().execute(
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'DEVICE', '" + scriptsDir
            + "rsa_data.dat', ',', NULL, NULL, 0, 0, 6, 0, NULL, NULL)");
    getLogWriter().info("Done data load.");

    // query using a client connection
    Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    /*
    PreparedStatement pstmt1 = netConn.prepareStatement("SELECT risk_rating,"
        + "count(risk_rating) from app.device group by risk_rating");
    */
    PreparedStatement pstmt1 = netConn.prepareStatement("SELECT risk_rating, "
        + "count(risk_rating) from app.device -- GEMFIREXD-PROPERTIES "
        + "index=DEVICE_RISK_RATING\n group by risk_rating");
    PreparedStatement pstmt2 = netConn.prepareStatement("SELECT risk_rating, "
        + "count(risk_rating) from app.device -- GEMFIREXD-PROPERTIES "
        + "index=DEVICE_RISK_RATING\n group by risk_rating");
    /*
    PreparedStatement pstmt12 = conn.prepareStatement("SELECT risk_rating,"
        + "count(risk_rating) from app.device group by risk_rating");
    */
    PreparedStatement pstmt12 = conn.prepareStatement("SELECT risk_rating, "
        + "count(risk_rating) from app.device -- GEMFIREXD-PROPERTIES "
        + "index=DEVICE_RISK_RATING\n group by risk_rating");
    PreparedStatement pstmt22 = conn.prepareStatement("SELECT risk_rating, "
        + "count(risk_rating) from app.device -- GEMFIREXD-PROPERTIES "
        + "index=DEVICE_RISK_RATING\n group by risk_rating");

    // some warmup runs
    ResultSet rs;
    final TIntIntHashMap expectedResults = new TIntIntHashMap();
    TIntIntHashMap gotResults = new TIntIntHashMap();
    for (int i = 1; i <= 5; i++) {
      rs = pstmt1.executeQuery();
      if (expectedResults.isEmpty()) {
        // fillup expected results the first time
        while (rs.next()) {
          int risk = rs.getInt(1);
          if (expectedResults.containsKey(risk)) {
            throw new RuntimeException("duplicate results for risk=" + risk);
          }
          expectedResults.put(risk, rs.getInt(2));
        }
      }
      else {
        while (rs.next()) {
          int risk = rs.getInt(1);
          if (gotResults.containsKey(risk)) {
            throw new RuntimeException("duplicate results for risk=" + risk);
          }
          gotResults.put(risk, rs.getInt(2));
        }
        assertEquals(expectedResults, gotResults);
        gotResults.clear();
      }

      rs = pstmt2.executeQuery();
      while (rs.next()) {
        int risk = rs.getInt(1);
        if (gotResults.containsKey(risk)) {
          throw new RuntimeException("duplicate results for risk=" + risk);
        }
        gotResults.put(risk, rs.getInt(2));
      }
      assertEquals(expectedResults, gotResults);
      gotResults.clear();

      rs = pstmt12.executeQuery();
      while (rs.next()) {
        int risk = rs.getInt(1);
        if (gotResults.containsKey(risk)) {
          throw new RuntimeException("duplicate results for risk=" + risk);
        }
        gotResults.put(risk, rs.getInt(2));
      }
      assertEquals(expectedResults, gotResults);
      gotResults.clear();

      rs = pstmt22.executeQuery();
      while (rs.next()) {
        int risk = rs.getInt(1);
        if (gotResults.containsKey(risk)) {
          throw new RuntimeException("duplicate results for risk=" + risk);
        }
        gotResults.put(risk, rs.getInt(2));
      }
      assertEquals(expectedResults, gotResults);
      gotResults.clear();
    }

    // timed runs
    long start, end;
    int numResults;
    for (int i = 1; i <= 10; i++) {
      numResults = 0;
      start = System.nanoTime();
      rs = pstmt1.executeQuery();
      while (rs.next()) {
        numResults++;
      }
      end = System.nanoTime();
      assertEquals(expectedResults.size(), numResults);
      getLogWriter().info("Time taken for pstmt1: " + (end - start) + "ns");

      numResults = 0;
      start = System.nanoTime();
      rs = pstmt2.executeQuery();
      while (rs.next()) {
        numResults++;
      }
      end = System.nanoTime();
      assertEquals(expectedResults.size(), numResults);
      getLogWriter().info("Time taken for pstmt2: " + (end - start) + "ns");

      numResults = 0;
      start = System.nanoTime();
      rs = pstmt12.executeQuery();
      while (rs.next()) {
        numResults++;
      }
      end = System.nanoTime();
      assertEquals(expectedResults.size(), numResults);
      getLogWriter().info("Time taken for pstmt12: " + (end - start) + "ns");

      numResults = 0;
      start = System.nanoTime();
      rs = pstmt22.executeQuery();
      while (rs.next()) {
        numResults++;
      }
      end = System.nanoTime();
      assertEquals(expectedResults.size(), numResults);
      getLogWriter().info("Time taken for pstmt22: " + (end - start) + "ns");
    }
  }

  private void setCommonTestProperties(Properties props) {
    props.setProperty(com.pivotal.gemfirexd.Attribute.TABLE_DEFAULT_PARTITIONED, 
        Boolean.toString(GfxdConstants.TABLE_DEFAULT_PARTITIONED_DEFAULT));
    props.setProperty("log-level", "config");
    props.setProperty("conserve-sockets", "false");
  }

  private void profileuseCase4Queries(Connection conn) throws Exception {
    
    getLogWriter().info("About to create schema objects ");
    
    String useCase4Script = TestUtil.getResourcesDir()
        + "/lib/useCase4/schema.sql";
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4Script }, false,
        getLogWriter(), null, null, false);
    
    getLogWriter().info("Schema creation done.. about to import data.");
    
    String baseImportPath = TestUtil.getResourcesDir()
        + "/lib/useCase4/";
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { baseImportPath + "import-10k.sql" }, false,
        getLogWriter(), "<path>", TestUtil.getResourcesDir(), true);
    
    getLogWriter().info("10k Data import done.. about to determine holding's no. of rows.");
    
    runQueries(conn);
    
    getLogWriter().info("About to zap all the data");
    
    String useCase4DropScript = TestUtil.getResourcesDir()
        + "/lib/useCase4/drop.sql";
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4DropScript }, false,
        getLogWriter(), null, null, true);
    
    getLogWriter().info("All artifacts dropped successfully. Waiting for couple of seconds .. ");
    /*
    Thread.sleep(2000);
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4Script }, false,
        getLogWriter(), null, null, false);
    
    getLogWriter().info("Schema creation done.. about to import data.");
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4DropScript }, false,
        getLogWriter(), null, null, false);
    
    getLogWriter().info("All artifacts dropped successfully. Waiting for couple of seconds .. ");
    Thread.sleep(2000);
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4Script }, false,
        getLogWriter(), null, null, false);
    
    getLogWriter().info("Schema creation done.. about to import data.");
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { baseImportPath + "import-100k.sql" }, false,
        getLogWriter(), "<path>", TestUtil.getResourcesDir(), true);
    
    getLogWriter().info("100k Data import done.. about to determine holding's no. of rows.");
    
    runQueries(conn);
    
    GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase4DropScript }, false,
        getLogWriter(), null, null, true);
    */
    
    getLogWriter().info("All artifacts dropped successfully. Waiting for couple of seconds .. ");
    Thread.sleep(2000);
  }
  
  
  private void runQueries(Connection conn) throws Exception {
    
    ResultSet r = conn.createStatement().executeQuery("select count(*) from app.holding");
    r.next();
    final int totalAccounts = r.getInt(1);
    r.close();
    
    final int[] listOfAccounts = new int[totalAccounts];
    
    getLogWriter().info(
        "Caching " + totalAccounts
            + " APP.HOLDING.ACCOUNT_ACCOUNTID information.");
    r = conn.createStatement().executeQuery("select distinct account_accountid from app.holding");
    for(int i=0; i < listOfAccounts.length && r.next(); i++) {
      listOfAccounts[i] = r.getInt(1);
    }
    r.close();
    
//    // warmup
//    executeTxn(conn, listOfAccounts, 50, false);
//
//    // actual profiling
//    executeTxn(conn, listOfAccounts, 20, true);

    ResultSet plan = conn
        .createStatement()
        .executeQuery(
            "explain SELECT h.quote_symbol, sum(q.price * h.quantity) - SUM(h.purchaseprice * h.quantity) as gain "
                + "FROM app.Holding h, app.Quote q "
                + "Where h.account_accountid = "
                +  listOfAccounts[random.nextInt(totalAccounts-1)]
                + " and h.quote_symbol=q.symbol "
                + "GROUP BY  h.quote_symbol HAVING  SUM(q.price * h.quantity) - SUM(h.purchaseprice * h.quantity) > 0 "
                + "ORDER BY gain desc");

    assertTrue(plan.next());
    getLogWriter().info("Plan for holdingAgg:\n" + getPlanAsText(plan.getClob(1)));
    while(plan.next()) {
      getLogWriter().info(getPlanAsText(plan.getClob(1)));
    }

    plan = conn
        .createStatement()
        .executeQuery(
            "explain SELECT SUM(h.purchaseprice * h.quantity) as purchaseBasis, sum(q.price * h.quantity) as marketValue, count(*) "
                + "FROM app.Holding h, app.Quote q "
                + "Where h.account_accountid ="
                + listOfAccounts[random.nextInt(totalAccounts-1)]
                + " and h.quote_symbol=q.symbol " + "ORDER BY marketValue desc");
    assertTrue(plan.next());
    getLogWriter().info("Plan for PortSummary:\n" + getPlanAsText(plan.getClob(1)));
    while(plan.next()) {
      getLogWriter().info(getPlanAsText(plan.getClob(1)));
    }

    plan = conn.createStatement().executeQuery(
        "explain SELECT SUM(q.price)/count(*) as tradeStockIndexAverage, "
            + "SUM(q.open1)/count(*) as tradeStockIndexOpenAverage, "
            + "SUM(q.volume) as tradeStockIndexVolume, " + "COUNT(*) as cnt , "
            + "SUM(q.change1) " + "FROM app.Quote q");
    assertTrue(plan.next());
    getLogWriter().info("Plan for mktSummary:\n" + getPlanAsText(plan.getClob(1)));
    while(plan.next()) {
      getLogWriter().info(getPlanAsText(plan.getClob(1)));
    }

    ResultSet uptableRow = conn.createStatement().executeQuery("select orderid, account_accountid from app.orders where orderstatus = 'closed' ");
    int accId = -1;
    if(uptableRow.next()) {
       accId = uptableRow.getInt(2);
    }
    uptableRow.close();
    
    getLogWriter().info("Profiling update with account id " + accId);
    plan = conn
        .createStatement()
        .executeQuery(
            "explain UPDATE app.Orders o SET o.orderstatus = 'completed' WHERE o.account_accountid  = "
                + accId
                + " AND o.orderid "
                + "IN (SELECT o2.orderid FROM app.Orders o2 WHERE o2.orderstatus = 'closed' AND o2.account_accountid = "
                + accId + ")");
    assertTrue(plan.next());
    getLogWriter().info("Plan for update orderstatus completed :\n" + getPlanAsText(plan.getClob(1)));
    while(plan.next()) {
      getLogWriter().info(getPlanAsText(plan.getClob(1)));
    }
    
  }
  
  private void executeTxn(final Connection conn, final int[] listOfAccounts,
      final int iterations, final boolean profile) throws Exception {
    
    final int totalAccounts = listOfAccounts.length;
    
    PreparedStatement psHoldingAgg = conn.prepareStatement(
        "SELECT h.quote_symbol, sum(q.price * h.quantity) - SUM(h.purchaseprice * h.quantity) as gain "
            + "FROM app.Holding h, app.Quote q "
            + "Where h.account_accountid = ? and h.quote_symbol=q.symbol "
            + "GROUP BY  h.quote_symbol HAVING  SUM(q.price * h.quantity) - SUM(h.purchaseprice * h.quantity) > 0 "
            + "ORDER BY gain desc" );
    
    PreparedStatement psPortSumm = conn
        .prepareStatement("SELECT SUM(h.purchaseprice * h.quantity) as purchaseBasis, sum(q.price * h.quantity) as marketValue, count(*) "
            + "FROM app.Holding h, app.Quote q "
            + "Where h.account_accountid =? and h.quote_symbol=q.symbol "
            + "ORDER BY marketValue desc");
    
    PreparedStatement psMktSumm = conn
        .prepareStatement("SELECT SUM(q.price)/count(*) as tradeStockIndexAverage, "
            + "SUM(q.open1)/count(*) as tradeStockIndexOpenAverage, "
            + "SUM(q.volume) as tradeStockIndexVolume, "
            + "COUNT(*) as cnt , "
            + "SUM(q.change1) " + "FROM app.Quote q");
    
    PreparedStatement psHoldingCount = conn
        .prepareStatement("SELECT count(*) FROM app.Holding h WHERE h.account_Accountid = ?");
    
    
    PreparedStatement psUptClosedOrder = conn
        .prepareStatement("UPDATE app.Orders o SET o.orderstatus = 'completed' WHERE o.account_accountid  = ? AND o.orderid " +
        		"IN (SELECT o2.orderid FROM app.Orders o2 WHERE o2.orderstatus = 'closed' AND o2.account_accountid = ?)");

    PreparedStatement psFindOrderByStatus = conn
        .prepareStatement("SELECT o.* FROM app.Orders o WHERE o.orderstatus = ? AND o.account_accountid = ? order by orderid DESC");

    PreparedStatement psFindOrderByAccAccId = conn
        .prepareStatement("SELECT o.* FROM app.Orders o WHERE o.account_accountid  = ? order by orderid DESC");

    PreparedStatement psFindOrderIdAndAccAccId = conn
        .prepareStatement("SELECT o.* FROM app.Orders o WHERE o.orderid = ? AND o.account_accountid  = ?");

    PreparedStatement psFindOrderCntAccAccId = conn
        .prepareStatement("SELECT count(*) FROM app.Orders o WHERE o.account_accountid  = ?");

    PreparedStatement psFindOrderCntAccAccIdAndStatus = conn
        .prepareStatement("SELECT count(*) FROM app.Orders o WHERE o.account_accountid  = ? and o.orderstatus = ?");    
    
    int mktSummFreqMod = iterations; // (int)(warmupIterations * 0.05); // 20% time mkt summary to execute
    getLogWriter().info(
        (profile ? "Profiling for " : "Warming up for ") + iterations
            + " iterations with every " + mktSummFreqMod
            + " iteration having market Summary Report. ");
    
    long totHoldingExecTime = 0, totMktSummExecTime = 0, totPortSummExecTime = 0, totHoldingCntExecTime = 0,
        totUptClosedOrderExecTime = 0, totFindOrderByStatusExecTime = 0, totFindOrderByAccAccIdExecTime = 0,
        totFindOrderIdAndAccAccIdExecTime = 0, totFindOrderCntAccAccIdExecTime = 0, totFindOrderCntAccAccIdAndStatusExecTime = 0;
    int numFetchedHolding = 0, numFetchedPortSumm = 0;
    int numTimesMktSummExecuted = 0;

    TIntIntHashMap orderIdAccId = new TIntIntHashMap();
    
    ResultSet r = conn.createStatement().executeQuery("select orderid, account_accountid from app.orders");
    while(r.next()) {
      orderIdAccId.put(r.getInt(1), r.getInt(2));
    }
    r.close();
    
    int[] listOfOrderIds = orderIdAccId.keys();
    int[] listOfOrderAccAccId = orderIdAccId.getValues();
    
    for (int idx = 0; idx < iterations; idx++) {
      boolean mktSummExecuted = false;
      long beginProfile = System.nanoTime();
      int acc = listOfAccounts[random.nextInt(totalAccounts-1)];
      psHoldingAgg.setInt(1, acc);
      ResultSet holdingAggRepo = psHoldingAgg.executeQuery(); 

      if (holdingAggRepo.next()) {
        numFetchedHolding++;
        holdingAggRepo.getString(1);
        holdingAggRepo.getFloat(2);
      }
      while (holdingAggRepo.next()) {
        holdingAggRepo.getString(1);
        holdingAggRepo.getFloat(2);
      }
      
      long holdingExecDone = System.nanoTime();
      
      if (idx != 0 && (mktSummFreqMod == iterations || (idx % mktSummFreqMod ) == 0) ) {
        mktSummExecuted = true;
        numTimesMktSummExecuted++;
        
        ResultSet mktSummRepo = psMktSumm.executeQuery();
        
        if (mktSummRepo.next()) {
          mktSummRepo.getFloat(1);
          mktSummRepo.getFloat(2);
          mktSummRepo.getFloat(3);
          mktSummRepo.getInt(4);
          mktSummRepo.getFloat(5);
        }
        assertFalse(mktSummRepo.next());
      }

      long mktSummRepoExecDone = System.nanoTime();

      acc = listOfAccounts[random.nextInt(totalAccounts-1)];
      psPortSumm.setInt(1, acc);
      ResultSet portSummRepo = psPortSumm.executeQuery();

      if (portSummRepo.next()) {
        numFetchedPortSumm++;
        portSummRepo.getFloat(1);
        portSummRepo.getFloat(2);
        portSummRepo.getInt(3);
      }
      while (portSummRepo.next()) {
        portSummRepo.getFloat(1);
        portSummRepo.getFloat(2);
        portSummRepo.getInt(3);
      }
      
      long portSummRepoExecDone = System.nanoTime();
      
      acc = listOfAccounts[random.nextInt(totalAccounts-1)];
      psHoldingCount.setInt(1, acc);
      ResultSet holdingCntRepo = psHoldingCount.executeQuery();
      
      while(holdingCntRepo.next()) {
        holdingCntRepo.getInt(1);
      }
      
      long holdingCntRepoExecDone = System.nanoTime();
      
      psUptClosedOrder.setInt(1, acc);
      psUptClosedOrder.setInt(2, acc);
      psUptClosedOrder.execute();
      
      long uptClosedOrderExecDone = System.nanoTime();

      psFindOrderByStatus.setString(1, "open");
      psFindOrderByStatus.setInt(2, acc);
      psFindOrderByStatus.executeQuery();

      long findOrderByStatusExecDone = System.nanoTime();
      
      psFindOrderByAccAccId.setInt(1, acc);
      psFindOrderByAccAccId.executeQuery();
      
      long findOrderByAccAccIdExecDone = System.nanoTime();

      final int qOrderIdx = random.nextInt(listOfOrderIds.length-1);
      psFindOrderIdAndAccAccId.setInt(1, listOfOrderIds[qOrderIdx]);
      psFindOrderIdAndAccAccId.setInt(2, listOfOrderAccAccId[qOrderIdx]);
      psFindOrderIdAndAccAccId.executeQuery();
      
      long findOrderIdAndAccAccIdExecDone = System.nanoTime();

      psFindOrderCntAccAccId.setInt(1, acc);
      psFindOrderCntAccAccId.executeQuery();

      long findOrderCntAccAccIdExecDone = System.nanoTime();
      
      psFindOrderCntAccAccIdAndStatus.setInt(1, acc);
      psFindOrderCntAccAccIdAndStatus.setString(2, "open");
      psFindOrderCntAccAccIdAndStatus.executeQuery();
      
      long findOrderCntAccAccIdAndStatusExecDone = System.nanoTime();
      
      // accumulate timings now.
      totHoldingExecTime += (holdingExecDone - beginProfile);
      if (mktSummExecuted) {
        totMktSummExecTime += (mktSummRepoExecDone - holdingExecDone);
      }
      totPortSummExecTime += (portSummRepoExecDone - mktSummRepoExecDone);
      totHoldingCntExecTime += (holdingCntRepoExecDone - portSummRepoExecDone);
      totUptClosedOrderExecTime += (uptClosedOrderExecDone - holdingCntRepoExecDone);
      totFindOrderByStatusExecTime += (findOrderByStatusExecDone - uptClosedOrderExecDone);
      totFindOrderByAccAccIdExecTime += (findOrderByAccAccIdExecDone - findOrderByStatusExecDone);
      totFindOrderIdAndAccAccIdExecTime += (findOrderIdAndAccAccIdExecDone - findOrderByAccAccIdExecDone);
      totFindOrderCntAccAccIdExecTime += (findOrderCntAccAccIdExecDone - findOrderIdAndAccAccIdExecDone);
      totFindOrderCntAccAccIdAndStatusExecTime += (findOrderCntAccAccIdAndStatusExecDone - findOrderCntAccAccIdExecDone);
      
      if( idx != 0 && (idx % mktSummFreqMod) == 0) {
        getLogWriter().info(idx + " iterations " + (profile ? "profiling" : "warmup")+ " done.");
      }
    }
  
    if(profile) {
      StringBuilder sb = new StringBuilder("Profile Summary:\n");
      int prevLen = sb.length();
      sb = appendInfo(sb.append("holdingAggRepo"), totHoldingExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("mktSummRepo"), totMktSummExecTime, numTimesMktSummExecuted, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("portSummaryReport"), totPortSummExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("holdingCountReport"), totHoldingCntExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("uptClosedOrder"), totUptClosedOrderExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("findOrderByStatus"), totFindOrderByStatusExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("findOrderByAccAccId"), totFindOrderByAccAccIdExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("findOrderByOrderIdAndAccAccId"), totFindOrderIdAndAccAccIdExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("findOrderCntAccAccId"), totFindOrderCntAccAccIdExecTime, iterations, sb.length() - prevLen);
      prevLen = sb.length();
      sb = appendInfo(sb.append("findOrderCntAccAccIdAndStatus"), totFindOrderCntAccAccIdAndStatusExecTime, iterations, sb.length() - prevLen);
      
      getLogWriter().info(sb.toString());
    }
    else {
      getLogWriter().info(
          "Warm up done, found \n" + numFetchedHolding //listOfHoldingValidAccounts.size()
          + " valid holding accounts & \n" + numFetchedPortSumm //listOfPortSummValidAccounts.size()
          + " valid portfolio summary accounts.\n Profiling for "
          + iterations + " iterations with every " + mktSummFreqMod
          + " iteration having market Summary Report. ");
    }
    
  }
  
  StringBuilder appendInfo(StringBuilder sb, long totExecTime, int iterations, int lableLength) {
    
    for(int align = (40 - lableLength); align >=0; align--) {
      sb.append(' ');
    }
    sb.append("avgExecTime=").append((totExecTime/iterations)).append('\n');
    
    return sb;
  }
  String getPlanAsText(java.sql.Clob c) throws SQLException, IOException {
    BufferedReader reader = new BufferedReader(c.getCharacterStream());
    int sz = (int)c.length();
    char[] charArray = new char[sz];
    reader.read(charArray, 0, sz);
    return new String(charArray);
  }
}
