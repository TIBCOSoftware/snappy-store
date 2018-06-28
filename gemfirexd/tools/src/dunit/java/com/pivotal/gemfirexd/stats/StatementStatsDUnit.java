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
package com.pivotal.gemfirexd.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;

/**
 * Test class for Statement stats. Added tests for checking enable/disable of
 * stats on global and per connection basis, as also checks for stats in remote
 * VMs.
 * 
 * @author rdubey
 * @author swale
 */
@SuppressWarnings("serial")
public class StatementStatsDUnit extends DistributedSQLTestBase {

  public StatementStatsDUnit(String name) {
    super(name);
  }

  /**
   * test for all basic statement stats gathered during dml execution.
   * @see com.pivotal.gemfirexd.internal.impl.sql.StatementStats
   * @throws Exception on failure.
   */
  public void testStatementStats() throws Exception {
    try {
      Properties serverInfo = new Properties();
      serverInfo.setProperty("gemfire.enable-time-statistics", "true");
      serverInfo.setProperty("statistic-sample-rate", "100");
      serverInfo.setProperty("statistic-sampling-enabled", "true");

      startServerVMs(1, 0, null, serverInfo);

      Properties info = new Properties();
      info.setProperty("host-data", "false");
      info.setProperty("gemfire.enable-time-statistics", "true");
      
      // start a client, register the driver.
      startClientVMs(1, 0, null, info);

      // enable StatementStats for all connections in this VM
      System.setProperty(GfxdConstants.GFXD_ENABLE_STATS, "true");

      // check that stats are enabled with System property set
      Connection conn = TestUtil.getConnection(info);
      runAndCheckStats(conn, true, 1);
      conn.close();

      // now disable stats for a connection and try again
      System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
      info.setProperty(Attribute.ENABLE_STATS, "false");
      conn = TestUtil.getConnection(info);
      runAndCheckStats(conn, false, 1);
      conn.close();

      // now disable global flag, enable stats for this particular connection
      // and try again
      System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
      info.setProperty(Attribute.ENABLE_STATS, "true");
      conn = TestUtil.getConnection(info);
      runAndCheckStats(conn, true, 2);
      conn.close();

      // now enable global flag again, enable stats for one connection, disable
      // for another and ensure sys property overrides.
      System.setProperty(GfxdConstants.GFXD_ENABLE_STATS, "false");
      conn = TestUtil.getConnection(info);
      runAndCheckStats(conn, false, 2);
      conn.close();
      info.setProperty(Attribute.ENABLE_STATS, "true");
      Connection conn2 = TestUtil.getConnection(info);
      runAndCheckStats(conn2, false, 2);
      conn2.close();

      // now clear system property and check default behaviour is no stats
      System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
      info.remove(Attribute.ENABLE_STATS);
      conn = TestUtil.getConnection(info);
      // no stats by default
      runAndCheckStats(conn, false, 2);
      conn.close();
      
      // Now specially run with Time Stats
      System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
      Properties info2 = new Properties();
      info2.setProperty(Attribute.ENABLE_STATS, "true");
      info2.setProperty(Attribute.ENABLE_TIMESTATS, "true");
      info2.setProperty("gemfire.enable-time-statistics", "true");
      info2.setProperty("statistic-sample-rate", "100");
      info2.setProperty("statistic-sampling-enabled", "true");
      conn = TestUtil.getConnection(info2);
      runAndCheckUpdateSelectStats(conn, true, true);
      info2.remove(Attribute.ENABLE_TIMESTATS);
      info2.remove(Attribute.ENABLE_STATS);
      conn.close();

      stopVMNums(1, -1);
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
      System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
    }
  }

  private static StatementStatsObserver ob1;

  private static StatementStatsObserver ob2;

  private void runAndCheckStats(Connection conn, final boolean enableStats,
      final int numTimesSampled) throws Exception {
    
    final VM serverVM = this.serverVMs.get(0);
    checkUniqueStatistics();
    serverVM.invoke(this.getClass(), "checkUniqueStatistics"); 

    final Statement stmt = conn.createStatement();
    SerializableRunnable setObserver1 = new SerializableRunnable(
        "set a stats observer1") {
      @Override
      public void run() throws CacheException {
        ob1 = new StatementStatsObserver();
        GemFireXDQueryObserverHolder.setInstance(ob1);
      }
    };

    // set observers on local and remote node
    setObserver1.run();
    serverExecute(1, setObserver1);

    stmt.execute("create schema trade");

    SerializableRunnable checkStats1 = new SerializableRunnable(
        "check observer1") {
      @Override
      public void run() throws CacheException {
          // schema does not get invalidated so will be optimized only once
          checkDDLStatistics(ob1, numTimesSampled, enableStats);
      }
    };
    // check on local VM and remote node
    checkStats1.run();
    serverExecute(1, checkStats1);

    // set the second observer
    SerializableRunnable setObserver2 = new SerializableRunnable(
        "set a stats observer2") {
      @Override
      public void run() throws CacheException {
        ob2 = new StatementStatsObserver();
        GemFireXDQueryObserverHolder.setInstance(ob2);
      }
    };
    setObserver2.run();
    serverExecute(1, setObserver2);

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), tid int, "
        + "primary key (cid))");

    SerializableRunnable checkStats2 = new SerializableRunnable(
        "check observer2") {
      @Override
      public void run() throws CacheException {
        final Statistics s1 = ob1.getStatistics();
        final Statistics s2 = ob2.getStatistics();
          if (enableStats) {
            assertNotSame("Statistics object from different execution cannot "
                + "be same", s1, s2);
          }
          checkDDLStatistics(ob2, numTimesSampled, enableStats);
          GemFireXDQueryObserverHolder.clearInstance();
      }
    };

    // check on local VM and remote node
    checkStats2.run();
    serverExecute(1, checkStats2);

    java.sql.Date since = new java.sql.Date(System.currentTimeMillis());
    PreparedStatement psInsertCust = conn.prepareStatement("insert into "
        + "trade.customers values (?,?,?,?,?)");
    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    final StatisticsType st = dsys.findType(StatementStats.name);
    Statistics[] stats = dsys.findStatisticsByType(st);
    Statistics psInsertStat = null;

    //its the display in VSD that strips the spaces. here the text should be as is provided.
    String search = "";
    if (enableStats) {
      search =  ((EmbedStatement)psInsertCust).getStatementStats().getStatsId();
      assertTrue(stats.length >= 1);
      for(Statistics s : stats) {
        if(search.equals(s.getTextId())) {
          psInsertStat = s;
          getLogWriter().info("Got statistics for " + psInsertStat.getTextId());
          break;
        }
      }
    }

    // Insert 0-10.
    for (int i = 0; i < 10; i++) {
      psInsertCust.setInt(1, i);
      psInsertCust.setString(2, "XXXX" + i);
      since = new java.sql.Date(System.currentTimeMillis());
      psInsertCust.setDate(3, since);
      psInsertCust.setString(4, "XXXX" + i);
      psInsertCust.setInt(5, i);
      psInsertCust.executeUpdate();
      if (enableStats) {
        stats = dsys.findStatisticsByType(st);
        assertNotNull(stats);
        checkInsertUpdateStatistics(psInsertStat, (i + 1), numTimesSampled, true, search, 10);
      }
      
      // Insert with different prepared statement 5-9.
      // As we drop the table at last re-compilation happens and need to ensure
      // numCompiled is not incrementing across multiple prepares.
      if (i == 5) {
        psInsertCust = conn.prepareStatement("insert into "
            + "trade.customers values (?,?,?,?,?)");
      }
    }
           
    final PreparedStatement psSelectCust = conn.prepareStatement("select * "
        + "from trade.customers where cust_name = ?");

    Statistics psSelectStats = null;
    // check on local VM
    if (enableStats) {
      search = ((EmbedStatement)psSelectCust).getStatementStats().getStatsId();
      stats = dsys.findStatisticsByType(st);
      assertTrue(stats.length >= 1);
      for(Statistics s : stats) {
        if(search.equals(s.getTextId())) {
          psSelectStats = s;
          getLogWriter().info("Got statistics for " + psSelectStats.getTextId());
          break;
        }
      }
      assertNotSame(psInsertStat, psSelectStats);
    }

    for (int i = 0; i < 10; i++) {
      psSelectCust.setString(1, "XXXX" + i);
      ResultSet rs = psSelectCust.executeQuery();
      assertTrue("Should return one row", rs.next());
      assertFalse("Should not return more than one row", rs.next());
      rs.close();
      if (enableStats) {
        checkSelectStatistics(psSelectStats, i + 1, false, true, numTimesSampled, search, 10);
        final String query = search;
        serverVM.invoke(this.getClass(), "checkSelectStatistics",
            new Object[] { null, Integer.valueOf(i + 1), Boolean.FALSE,
                Boolean.FALSE, Integer.valueOf(numTimesSampled), query,  Integer.valueOf(10)});
      }
    }

    Statistics psSelect2Stats = null;
    final PreparedStatement psSelectCust2 = conn.prepareStatement("select * "
        + "from trade.customers where cid = ?");
    // check on local VM only since remote node does not handle stats for get
    // convertible PK based queries
    if (enableStats) {
      search = ((EmbedStatement)psSelectCust2).getStatementStats().getStatsId();
      stats = dsys.findStatisticsByType(st);
      assertTrue(stats.length >= 1);
      for(Statistics s : stats) {
        if(search.equals(s.getTextId())) {
          psSelect2Stats = s;
          getLogWriter().info("Got statistics for " + psSelect2Stats.getTextId());
          break;
        }
      }
      assertNotSame(psSelectStats, psSelect2Stats);
    }

    for (int i = 0; i < 10; i++) {
      psSelectCust2.setInt(1, i);
      ResultSet rs = psSelectCust2.executeQuery();
      assertTrue("Should return one row", rs.next());
      assertFalse("Should not return more than one row", rs.next());
      rs.close();
      if (enableStats) {
        checkSelectStatistics(psSelect2Stats, i + 1, true, true, numTimesSampled, search, 10);
      }
    }

    psSelectCust.close();
    psInsertCust.close();
    psSelectCust2.close();
    
    { // Delete last inserted row to verify row-modified stats
      PreparedStatement psDeleteCust = conn.prepareStatement("delete from "
          + "trade.customers where cid > ?");
      psDeleteCust.setInt(1, 8);
      psDeleteCust.executeUpdate();
      Statistics psDeleteStats = null;
      if (enableStats) {
        stats = dsys.findStatisticsByType(st);
        assertNotNull(stats);
        assertTrue(stats.length >= 1);
        search = ((EmbedStatement)psDeleteCust).getStatementStats()
            .getStatsId();
        for (Statistics s : stats) {
          if (search.equals(s.getTextId())) {
            psDeleteStats = s;
            getLogWriter().info(
                "Got statistics for " + psDeleteStats.getTextId());
            break;
          }
        }
        final String query = search;
        checkInsertUpdateStatistics(psDeleteStats, 1, numTimesSampled, true, query, 1);
        checkDmlWriteStatistics(psDeleteStats, true, query, false);
        serverVM.invoke(this.getClass(), "checkDmlWriteStatistics",
            new Object[] { null, Boolean.FALSE /*Is QN*/, query,
                Boolean.FALSE /*debug*/});
      }
      psDeleteCust.close();
    }
    
    { // count(*)
      Statistics sSelect3Stats = null;
      final Statement sSelectCust3 = conn.createStatement();
      for (int i = 0; i < 10; i++) {
        ResultSet rs = sSelectCust3
            .executeQuery("select count(*) from trade.customers");
        assertTrue("Should return one row", rs.next());
        assertFalse("Should not return more than one row", rs.next());
        if (enableStats) {
          String query = ((EmbedStatement)rs.getStatement())
              .getStatementStats().getStatsId();
          stats = dsys.findStatisticsByType(st);
          assertTrue(stats.length >= 1);
          for (Statistics s : stats) {
            if (query.equals(s.getTextId())) {
              sSelect3Stats = s;
              getLogWriter().info(
                  "Got statistics for " + sSelect3Stats.getTextId());
              break;
            }
          }
          checkSelectCountStarStatistics(sSelect3Stats);
        }
        rs.close();
      }
      sSelectCust3.close();
    }
        
    // clean up table and schema
    stmt.execute("drop table trade.customers");
    stmt.execute("drop schema trade restrict");
  }
  
  
  private void runAndCheckUpdateSelectStats(Connection conn, final boolean enableStats,
      final boolean debugSubQuery) throws Exception {
    final VM serverVM = this.serverVMs.get(0);
    final Statement stmt = conn.createStatement();
    stmt.execute("create schema utrade");
    stmt.execute("create table utrade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), tid int, "
        + "primary key (cid))");
    PreparedStatement psInsertCust = conn.prepareStatement("insert into "
        + "utrade.customers values (?,?,?,?,?)");
    // Insert 0-10.
    for (int i = 0; i < 10; i++) {
      psInsertCust.setInt(1, i);
      psInsertCust.setString(2, "XXXX" + i);
      psInsertCust.setDate(3, new java.sql.Date(System.currentTimeMillis()));
      psInsertCust.setString(4, "XXXX" + i);
      psInsertCust.setInt(5, i);
      psInsertCust.executeUpdate();
    }
    
    final Statement sSelectCust4 = conn.createStatement();
    sSelectCust4.execute("create table utrade.customers2 (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), tid int, "
        + "foreign key (cid) references utrade.customers(cid))");

    PreparedStatement psInsertCust4 = conn.prepareStatement("insert into "
        + "utrade.customers2 values (?,?,?,?,?)");
    for (int i = 0; i < 9; i++) {
      psInsertCust4.setInt(1, i);
      psInsertCust4.setString(2, "XXXX" + i);
      psInsertCust4.setDate(3, new java.sql.Date(System.currentTimeMillis()));
      psInsertCust4.setString(4, "XXXX" + i);
      psInsertCust4.setInt(5, i);
      psInsertCust4.executeUpdate();
    }
    psInsertCust4.close();
    PreparedStatement psUpdateCust4 = conn
        .prepareStatement("update utrade.customers2 set tid = 5 where cid in (select tid from utrade.customers where cid > ?)");
    psUpdateCust4.setInt(1, 1);
    psUpdateCust4.executeUpdate();

    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    final StatisticsType st = dsys.findType(StatementStats.name);
    Statistics[] stats = dsys.findStatisticsByType(st);
    String search = null;
    if (enableStats) {
      stats = dsys.findStatisticsByType(st);
      assertNotNull(stats);
      search = ((EmbedStatement)psUpdateCust4).getStatementStats()
          .getStatsId();
      assertTrue(stats.length >= 1);
      Statistics psUpdate4Stats = null;
      for (Statistics s : stats) {
        if (search.equals(s.getTextId())) {
          psUpdate4Stats = s;
          getLogWriter().info(
              "Got statistics for " + psUpdate4Stats.getTextId());
          break;
        }
      }
      final String query = search;

      checkDmlWriteStatistics(psUpdate4Stats, true, query, debugSubQuery);
      serverVM.invoke(this.getClass(), "checkDmlWriteStatistics",
          new Object[] { null, Boolean.FALSE /*Is QN*/, query,
        (debugSubQuery ? Boolean.TRUE : Boolean.FALSE) /*debug*/});
    }
    psUpdateCust4.close();
    
    if (enableStats) {
      search = "select_tid__UTRADE.CUSTOMERS/";
      serverVM.invoke(this.getClass(), "checkSubQueryStatistics",
          new Object[] { null, Boolean.FALSE /*Is QN*/, search,
              (debugSubQuery ? Boolean.TRUE : Boolean.FALSE) /*debug*/});
    } 
    Thread.sleep(1000);
    // clean up table and schema
    stmt.execute("drop table utrade.customers2");
    stmt.execute("drop table utrade.customers");
    stmt.execute("drop schema utrade restrict");
  }
  
  public static class StatementStatsObserver extends
      GemFireXDQueryObserverAdapter {

    private Statistics statistics = null;

    private long numTimesOptimize;

    private long executeTime;

    private long openTimes;

    private long numExecutes;

    private long numExecutesEnded;
    
    public Statistics getStatistics() {
      return this.statistics;
    }

    @Override
    public void statementStatsBeforeExecutingStatement(StatementStats s) {
      if (s == null) {
        this.statistics = null;
        return;
      }
      this.statistics = s.getStatistics();
      this.numTimesOptimize = this.statistics.getLong("QNNumTimesCompiled");
      this.executeTime = this.statistics.getLong("QNExecuteTime");
//      this.openTimes = this.statistics.getLong("OpenTime");
    }

    @Override
    public boolean afterQueryExecution(CallbackStatement stmt, SQLException sqle) {
      if (this.statistics == null) {
        assertFalse(((EmbedStatement)stmt).getEmbedConnection()
            .getLanguageConnection().statsEnabled());
        return false;
      }
      this.numExecutesEnded = this.statistics
          .getLong("QNNumExecutionsInProgress");
      this.numExecutes = this.statistics.getLong("QNNumExecutions");
      return false;
    }

    public boolean statsEnabled() {
      return (this.statistics != null);
    }

    public long getNumTimesCompiled() {
      return this.numTimesOptimize;
    }

    public long getExecuteTime() {
      return this.executeTime;
    }

    public long getOpenTimes() {
      return this.openTimes;
    }

    public long getNumExecutes() {
      return this.numExecutes;
    }

    public long getNumExecutesInProgress() {
      return this.numExecutesEnded;
    }
  }

  /** For debugging, useCase6 with statistics. No intended
   * to be run as part of precheckin.
   * @throws Exception
   */
  public void DISABLED_testUseCase6() throws Exception {
    Properties serverInfo = new Properties();
    //serverInfo.setProperty("host-data", "false");
    serverInfo.setProperty("gemfire.enable-time-statistics","true");
    serverInfo.setProperty("statistic-sample-rate", "100");
    serverInfo.setProperty("statistic-sampling-enabled", "true");
        
    startServerVMs(1, 0, null, serverInfo);
    
    Properties info = new Properties();
    info.setProperty("host-data", "false");
    info.setProperty("gemfire.enable-time-statistics","true");
    //startServerVMs(2, 0, null, info);

    // enable StatementStats for all connections in this VM
    System.setProperty(GfxdConstants.GFXD_ENABLE_STATS, "true");
    // start a client, register the driver.
    startClientVMs(1, 0, null, info);
    // get a new connection.
    Connection conn = TestUtil.getConnection(info);
    Statement stmt = conn.createStatement();

    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, " +
        "cust_name varchar(100), since date, addr varchar(100), tid int " +
        ") replicate");
    java.sql.Date since = new java.sql.Date(System.currentTimeMillis());
    //Connection conn = TestUtil.jdbcConn;
    PreparedStatement psInsertCust = conn.prepareStatement("insert into " +
        "trade.customers values (?,?,?,?,?)");
    // Insert 0-10.
    int cid = 10;
    for (int i = 0 ; i < 10; i++) {
      if (i == 5) {
        cid = 15;
      }
      psInsertCust.setInt(1,cid );
      psInsertCust.setString(2, "XXXX"+i);
      since = new java.sql.Date(System.currentTimeMillis());
      psInsertCust.setDate(3, since);
      psInsertCust.setString(4, "XXXX"+i);
      psInsertCust.setInt(5,i);
      psInsertCust.executeUpdate();
    }
    final PreparedStatement psSelect  = conn.prepareStatement(
        "select * from trade.customers where cid = ? order by cid");
    for (int j = 0; j < 1; j++) {
      psSelect.setInt(1, 10);
      ResultSet rs = psSelect.executeQuery();
      int k = 0;
      while (rs.next()) {
        k++;
      }
      Thread.sleep(3000);
      assertEquals("Should have 5 rows", 5, k);
    }
    System.clearProperty(GfxdConstants.GFXD_ENABLE_STATS);
  }

  /**
   * Check relevant statistics for a DDL statement.
   * @param enableStats TODO
   */
  private void checkDDLStatistics(StatementStatsObserver ob,
      int numTimesSampled, boolean enableStats) {
    assertTrue(ob.statsEnabled());
    long numTimesOptimize = ob.getNumTimesCompiled();
    //create schema executes once but create table twice (DDL replay I suppose).
    assertEquals("DDLs should only be compiled once", ob == ob2 ? numTimesSampled : 1,
        numTimesOptimize);
    long executeTime = ob.getExecuteTime();
    
    if (enableStats && executeTime < 0) {
      throw new TestException("Execution time cannot be less than zero");
    }
    if (!enableStats && executeTime != 0) {
      throw new TestException("Execution time shouldn't have been incremented");
    }
    
    long openTimes = ob.getOpenTimes();
    if (enableStats && openTimes < 0) {
      throw new TestException("Open time cannot be less than zero");
    }
    if (!enableStats && openTimes != 0) {
      throw new TestException("Open time shouldn't have been incremented");
    }
    
    long numExecutesStarted = ob.getNumExecutes();
    assertEquals("Number of execution started does match the expected "
        + " number of exections with stats enabled ", numTimesSampled,
        numExecutesStarted);

    long numExecutesInProgress = ob.getNumExecutesInProgress();
    assertEquals("Number of executions started should always be equal to "
        + "number of executions ended ", 0, numExecutesInProgress);
  }

  /**
   * Check relevant statistics for and insert or update statement.
   * @param s Statement Statistics
   * @param numEx number of executions.
   * @param numTimesSampled TODO
   */
  public static void checkInsertUpdateStatistics(Statistics s, int numEx,
      int numTimesSampled, boolean isQueryNode, String query, final int maxCount) {
      long bindTime =s.getLong("QNBindTime");
      if(bindTime < 0) {
        throw new TestException("Bind time cannot be lest than or equal" +
        "to zero");
      }
      long optimizeTime = s.getLong("QNOptimizeTime");
      if (optimizeTime < 0) {
        // it can be zero for inserts etc.
        throw new TestException("Time to optimize dml should never be less " +
        "than zero");
      }
      long routingInfoTime = s.getLong("QNRoutingInfoTime");
      if (routingInfoTime < 0) {
        // it can be zero for inserts etc.
        throw new TestException("Time to compute routing info of the dml should never be less " +
        "than zero");
      }
      long numTimesCompiled = s.getLong("QNNumTimesCompiled");
      assertEquals(
          "The statment should only be compiled as many times it got executed statistics enabled ",
          numTimesSampled, numTimesCompiled);
      long executeTime= s.getLong("QNExecuteTime");
      if (executeTime < 0) {
        throw new TestException("Execution time cannot be zero");
      }
      //    long openTimes = s.getLong("OpenTime");
      //    if(openTimes < 0) {
      //      throw new TestException("Time taken to open the resultSet cannot be zero");
      //    }
      long numExecutes = s.getLong("QNNumExecutions");
      assertEquals("Number of executions ", 
          numEx + (maxCount * (numTimesSampled-1)), numExecutes);
      long numExecutesInProgress = s.getLong("QNNumExecutionsInProgress");
      assertEquals("Number of executions in progress cannnot be greater than or less than zero ",
          0, numExecutesInProgress);
      long totalexecuteTime= s.getLong("QNTotalExecutionTime");
      if (totalexecuteTime < 0) {
        throw new TestException("Execution time cannot be zero");
      }
      long rowsModificationNum =s.getLong("QNNumRowsModified");
      if(rowsModificationNum < 0) {
        throw new TestException("Rows Modification number cannot be less than or equal " +
        "to zero");
      }
      long rowsModificationTime =s.getLong("QNRowsModificationTime");
      if(rowsModificationTime < 0) {
        throw new TestException("Rows Modification time cannot be less than or equal " +
        "to zero");
      }
      long rowsSeenCount =s.getLong("QNNumRowsSeen");
      if(rowsSeenCount < 0) {
        throw new TestException("Rows handled by GemFireResultSet i.e. converted to Put " +
        "cannot be less than or equal to zero");
      }
  }

  /**
   * Check relevant statistics for a select statement.
   * @param s Statement Statistics.
   * @param numEx number of executions.
   * @param isQueryNode indicates whether its a query node stats or data node stats.
   * @param numTimesSampled times this query is getting executed. As now only stats gets created across enable/disable.
   * @param query The query string to look after.
   */
  public static void checkSelectStatistics(Statistics s, int numEx,
      boolean isLocalGet, boolean isQueryNode, int numTimesSampled,
      String query, final int maxCount) {

    // happens on remote node.
    if (s == null) {
      assertNotNull(query);
      final InternalDistributedSystem dsys = Misc.getDistributedSystem();
      final StatisticsType st = dsys.findType(StatementStats.name);
      final Statistics[] stats = dsys.findStatisticsByType(st);
      assertTrue(stats.length >= 1);
      for(Statistics _s : stats) {
        getGlobalLogger().info("Searching statistics for " + query
            + " and looking into " + _s.getTextId());
        if(query.equals(_s.getTextId())) {
          s = _s;
          getGlobalLogger().info("Got statistics for " + s.getTextId());
          break;
        }
      }
    }

    String prefix = isQueryNode ? "QN" : "DN";

    long bindTime = s.getLong(prefix+"BindTime");
    if (bindTime < 0) {
      throw new TestException("Bind time cannot be lest than or equal"
          + "to zero");
    }
    long optimizeTime = s.getLong(prefix+"OptimizeTime");
    if (optimizeTime < 0) {
      // it can be zero for inserts etc.
      throw new TestException("Time to optimize dml should never be less "
          + "than zero");
    }
    long numTimesCompiled = s.getLong(prefix+"NumTimesCompiled");
    assertEquals(
        "The statment should only be compiled as many times it got executed statistics enabled. " + prefix+"NumTimesCompiled",
        numTimesSampled, numTimesCompiled);
    long computeQueryInfoTime = s.getLong(prefix+"RoutingInfoTime");
    if (computeQueryInfoTime < 0) {
      throw new TestException("Time taken to compute query info"
          + " cannot be zero");
    }
    long executeTime = s.getLong(prefix+"ExecuteTime");
    if (executeTime < 0) {
      throw new TestException("Execution time cannot be less than zero");
    }
//    long openTimes = s.getLong("OpenTime");
//    if (openTimes < 0) {
//      throw new TestException("Time taken to open the resultSet cannot be "
//          + "less than zero");
//    }
    long numExecutions = s.getLong(prefix+"NumExecutions");
    assertEquals("Number of select executions ", numEx
        + (maxCount * (numTimesSampled - 1)), numExecutions);
    long numExecutesInProgress = s.getLong(prefix+"NumExecutionsInProgress");
    assertEquals("Number of execution not equal to expected", 0,
        numExecutesInProgress);

    long totExecTime = s.getLong(prefix + "TotalExecutionTime");
    // TotalExecutionTime notes in wall clock millis while ExecuteTime notes
    // in nanoTime, so the two can be different by a few milliseconds
    if ((executeTime - totExecTime) > 5000000L) {
      throw new TestException("Total Execution time " + totExecTime +
          " cannot be less than execute time " + executeTime);
    }
    
    // DVD type
    /* TODO:sb:QP: to be enabled next.
    long getNextRowCoreDVDTime = s.getLong("GetNextRowCoreDVDTime");
    if (getNextRowCoreDVDTime < 0) {
      throw new TestException("Time spent in get cannot be less than zero");
    }

    long numGetsDvdInProgress = s.getLong("NumGetsDvdInProgress");
    assertEquals("Number of gets started in dvd format is not equal"
        + " to number of expected query executions in dvd format", 0,
        numGetsDvdInProgress);
    long numGetsDvdEnded = s.getLong("NumGetsDvdEnded");
    assertEquals("Number of gets ended in dvd format is not equal to number "
        + "of expected query execution in dvd format", 0, numGetsDvdEnded);
    // Byte type
    // only recorded for local VM for get convertible queries
    if (isLocalGet) {
      long numGetsByteInProgress = s.getLong("NumGetsByteInProgress");
      assertEquals("Number of gets in progress for this query doesnt match "
          + "number of query executions ", numEx, numGetsByteInProgress);

      long numGetsByteEnded = s.getLong("NumGetsByteEnded");
      assertEquals("Number of gets ended for this query doesnt match number "
          + "of query executions ", numEx, numGetsByteEnded);
      long nextCoreByteTime = s.getLong("GetNextRowCoreByteTime");
      if (nextCoreByteTime < 0) {
        throw new TestException("Get time cannot be less than zero");
      }
    }
*/
  }
  
  /**
   * Check relevant statistics for a select statement.
   * @param s Statement Statistics.
   * @param isQueryNode indicates whether its a query node stats or data node stats.
   * @param query The query string to look after.
   */
  public static void checkDmlWriteStatistics(Statistics s,
      boolean isQueryNode, String query, boolean debug) {
    // happens on remote node.
    if (s == null) {
      assertNotNull(query);
      final InternalDistributedSystem dsys = Misc.getDistributedSystem();
      final StatisticsType st = dsys.findType(StatementStats.name);
      final Statistics[] stats = dsys.findStatisticsByType(st);
      assertTrue(stats.length >= 1);
      for(Statistics _s : stats) {
        if(query.equals(_s.getTextId())) {
          s = _s;
          getGlobalLogger().info("Got statistics for " + s.getTextId());
          break;
        }
      }
    }
    
    if (isQueryNode) {
      long totalExecuteTime = s.getLong("QNTotalExecutionTime");
      if (totalExecuteTime < 0) {
        throw new TestException("Execution time cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteL QNTotalExecutionTime " + totalExecuteTime
            + " Query " + query);
      }
      long executeTime = s.getLong("QNExecuteTime");
      if (executeTime < 0) {
        throw new TestException("Execution time cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteL QNExecuteTime " + executeTime
            + " Query " + query);
      }
      long rowsModificationNum =s.getLong("QNNumRowsModified");
      if(rowsModificationNum < 0) {
        throw new TestException("Rows Modification number cannot be less than or equal " +
        "to zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteL QNNumRowsModified " + rowsModificationNum
            + " Query " + query);
      }
      long rowsModificationTime =s.getLong("QNRowsModificationTime");
      if(rowsModificationTime < 0) {
        throw new TestException("Rows Modification time cannot be less than or equal " +
        "to zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteL QNRowsModificationTime " + rowsModificationTime
            + " Query " + query);
      }
      long rowsSeenCount =s.getLong("QNNumRowsSeen");
      if(rowsSeenCount < 0) {
        throw new TestException("Rows handled by GemFireResultSet i.e. converted to Put " +
        "cannot be less than or equal to zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteL QNNumRowsSeen " + rowsSeenCount
            + " Query " + query);
      }
    }
    else {
      long totalExecuteTime = s.getLong("DNTotalExecutionTime");
      if (totalExecuteTime < 0) {
        throw new TestException("Execution time cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNTotalExecutionTime " + totalExecuteTime
            + " Query " + query);
      }
      long executeTime = s.getLong("DNExecuteTime");
      if (executeTime < 0) {
        throw new TestException("Execution time cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNExecuteTime " + executeTime
            + " Query " + query);
      }
      long rowsModificationNum = s.getLong("DNNumRowsModified");
      if (rowsModificationNum < 0) {
        throw new TestException(
            "Rows Modification number cannot be lest than or equal" + "to zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNNumRowsModified " + rowsModificationNum
            + " Query " + query);
      }
      long rowsModificationTime = s.getLong("DNRowsModificationTime");
      if (rowsModificationTime < 0) {
        throw new TestException(
            "Rows Modification time cannot be lest than or equal" + "to zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNRowsModificationTime " + rowsModificationTime
            + " Query " + query);
      }
      long numRowsSeenInSubQuery = s.getLong("DNSubQueryNumRowsSeen");
      if (numRowsSeenInSubQuery < 0) {
        throw new TestException("Number of rows cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNSubQueryNumRowsSeen " + numRowsSeenInSubQuery
            + " Query " + query);
      }
      long subQueryExecutionTime = s.getLong("DNSubQueryExecutionTime");
      if (subQueryExecutionTime < 0) {
        throw new TestException("Execution time cannot be less than zero");
      }
      else if (debug) {
        SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
            " DmlWriteR DNSubQueryExecutionTime " + subQueryExecutionTime
            + " Query " + query);
      }
    }
  }
  
  /**
   * Check relevant statistics for a select statement.
   * @param s Statement Statistics.
   * @param isQueryNode indicates whether its a query node stats or data node stats.
   * @param query The query string to look after.
   */
  public static void checkSubQueryStatistics(Statistics s,
      boolean isQueryNode, String query, boolean debug) {

    assert(isQueryNode == false);
    
    // happens on remote node.
    if (s == null) {
      assertNotNull(query);
      final InternalDistributedSystem dsys = Misc.getDistributedSystem();
      final StatisticsType st = dsys.findType(StatementStats.name);
      final Statistics[] stats = dsys.findStatisticsByType(st);
      assertTrue(stats.length >= 1);
      for(Statistics _s : stats) {
        if(_s.getTextId().startsWith(query)) {
          s = _s;
          getGlobalLogger().info("Got statistics for " + s.getTextId());
          break;
        }
      }
    }

    long totalExecuteTime = s.getLong("DNTotalExecutionTime");
    if (totalExecuteTime < 0) {
      throw new TestException("Execution time cannot be less than zero");
    }
    else if (debug) {
      SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
          " SubQueryR DNTotalExecutionTime " + totalExecuteTime + " Query "
              + query);
    }
    long executeTime = s.getLong("DNExecuteTime");
    if (executeTime < 0) {
      throw new TestException("Execution time cannot be less than zero");
    }
    else if (debug) {
      SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
          " SubQueryR DNExecuteTime " + executeTime + " Query " + query);
    }
    long numRowsProjected = s.getLong("DNNumProjectedRows");
    if (numRowsProjected < 0) {
      throw new TestException("Number of rows cannot be less than zero");
    }
    else if (debug) {
      SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
          " SubQueryR DNNumProjectedRows " + numRowsProjected
              + " Query " + query);
    }
    long numTableRowsScanned = s.getLong("DNNumTableRowsScanned");
    if (numTableRowsScanned < 0) {
      throw new TestException("Execution time cannot be less than zero");
    }
    else if (debug) {
      SanityManager.DEBUG_PRINT("Dunit" + GfxdConstants.TRACE_STATS_GENERATION,
          " SubQueryR DNNumTableRowsScanned " + numTableRowsScanned
              + " Query " + query);
    }
  }
  
  /**
   * Check relevant statistics for a "select Count(*)" statement.
   * @param s Statement Statistics.
   */
  private static void checkSelectCountStarStatistics(Statistics s) {
    long selectExecuteTime =s.getLong("QNExecuteTime");
    if(selectExecuteTime < 0) {
      throw new TestException("Time to handle rows by GemFireRegionSizeResultSet " +
      "cannot be less than or equal to zero");
    }
  }
  
  public static void checkUniqueStatistics() {
    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    final StatisticsType st = dsys.findType(StatementStats.name);
    Statistics[] stats = dsys.findStatisticsByType(st);
    final ArrayList<String> statList = new ArrayList<String>();
    for(Statistics s : stats) {
      assertFalse("Non-unique stat item: " + s.getTextId(),
          statList.contains(s.getTextId()));
      statList.add(s.getTextId());
    }
  }

  @Override
  public void tearDown2() throws Exception {
    ob1 = null;
    ob2 = null;
    super.tearDown2();
  }
  
}
