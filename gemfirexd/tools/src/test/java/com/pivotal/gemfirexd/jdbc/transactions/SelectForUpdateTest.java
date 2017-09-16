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
package com.pivotal.gemfirexd.jdbc.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Properties;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

@SuppressWarnings("serial")
public class SelectForUpdateTest extends JdbcTestBase {

  private volatile boolean callbackInvoked;

  private HashMap<Object, Object> keyMap;

  public SelectForUpdateTest(String name) {
    super(name);
  }

  public void testSelectForUpdate_whereCurrentOFUnsupported_fromClient() throws Exception {
    setupConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    runWhereCurrentOf(conn);
  }
  
  public void testSelectForUpdate_whereCurrentOFUnsupported() throws Exception {
    Connection conn = TestUtil.getConnection();
    runWhereCurrentOf(conn);
  }

  private void runWhereCurrentOf(Connection conn) throws SQLException {
    Statement stmtForTableAndInsert = conn.createStatement();
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");
    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");
    conn.setTransactionIsolation(getIsolationLevel());
    PreparedStatement ps = conn.prepareStatement("SELECT workdept, bonus "
        + "FROM EMPLOYEE FOR UPDATE of workdept");
    ps.setCursorName("C1");
    ps.executeQuery();
    try {
      conn
          .prepareStatement("update employee set workdept = ? where current of C1");
      fail("exception expected");
    } catch (SQLException e) {
      if (!"0A000".equals(e.getSQLState()) &&
          !"XJ083".equals(e.getSQLState())) {
        throw e;
      }
    }
  }
  
  public void testSelectForUpdateDirectDelete() throws Exception {
    Connection conn = TestUtil.getConnection();
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    Statement stmtForTableAndInsert = conn.createStatement();
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");
    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");
    Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    ResultSet uprs = stmt.executeQuery("SELECT workdept, bonus "
        + "FROM EMPLOYEE FOR UPDATE of BONUS");

    while (uprs.next()) {
      uprs.deleteRow();
    }
    
    SQLWarning w = uprs.getWarnings();
    assertTrue("0A000".equals(w.getSQLState()));
    uprs.close();
    Statement stmt2 = conn.createStatement();
    ResultSet rs = stmt2.executeQuery("select * from employee");
    w = rs.getWarnings();
    assertNull(w);

    rs = stmt2.executeQuery("select * from employee");
    assertFalse(rs.next());
    
    conn.setTransactionIsolation(getIsolationLevel());
    
    stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    
    stmt.execute("insert into employee values('asif', 'shahid', 'rnd', 1.0), ('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");
    
    uprs = stmt.executeQuery("SELECT * "
        + "FROM EMPLOYEE FOR UPDATE of BONUS");

    while (uprs.next()) {
      if (uprs.getString(1).equalsIgnoreCase("asif")) {
        uprs.deleteRow();
      }
    }

    w = uprs.getWarnings();
    assertNull(w);
    uprs.close();
    conn.commit();
    
    stmt2 = conn.createStatement();
    rs = stmt2.executeQuery("select * from employee");
    w = rs.getWarnings();
    assertNull(w);

    assertTrue(rs.next());
    assertTrue((rs.getString(1).equalsIgnoreCase("sum") || rs.getString(1)
        .equalsIgnoreCase("dada")));
    
    assertTrue(rs.next());
    
    assertTrue((rs.getString(1).equalsIgnoreCase("sum") || rs.getString(1)
        .equalsIgnoreCase("dada")));
    
    assertFalse(rs.next());
  }

  public void testSelectForUpdateWarningsFromClient() throws Exception {
    setupConnection();
    // start a network server
    Connection conn = startNetserverAndGetLocalNetConnection();
    // TODO: TX: currently we expect a "not implemented" exception due to #43188
    runSelectForUpdateWarnings(conn, true);
  }

  public void testSelectForUpdateWarnings() throws Exception {
    Connection conn = getConnection();
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    runSelectForUpdateWarnings(conn, false);
  }

  protected void runSelectForUpdateWarnings(final Connection conn,
      final boolean notImplemented) throws Exception {
    Statement stmtForTableAndInsert = conn.createStatement();
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");
    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");
    Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    ResultSet uprs = stmt.executeQuery("SELECT workdept, bonus "
        + "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS");
    while (uprs.next()) {
      uprs.getString("WORKDEPT");
      BigDecimal bonus = uprs.getBigDecimal("BONUS");
      if (notImplemented) {
        try {
          uprs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(250L)));
          uprs.updateRow();
          fail("expected not implemented exception");
        } catch (SQLException sqle) {
          if (!"0A000".equals(sqle.getSQLState()) &&
              !"XJ083".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
        return;
      }
      else {
        uprs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(250L)));
        uprs.updateRow();
      }
    }

    SQLWarning w = uprs.getWarnings();
    assertTrue("0A000".equals(w.getSQLState()));
    uprs.close();
    Statement stmt2 = conn.createStatement();
    ResultSet rs = stmt2.executeQuery("select * from employee");
    w = rs.getWarnings();
    assertNull(w);

    conn.commit();
    stmt.executeUpdate("update Employee set bonus = 1000 "
        + "where lastname <> 'kumar'");

    w = stmt.getWarnings();
    assertNull(rs.getWarnings());

    stmt2.executeUpdate("update Employee set bonus = bonus + 1000 "
        + "where lastname <> 'kumar'");
    w = stmt2.getWarnings();
    assertNull(w);

    // Prepared Statement

    PreparedStatement ps = conn.prepareStatement("SELECT workdept, bonus "
        + "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS",
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

    ps.execute();

    ResultSet prs = ps.getResultSet();

    while (prs.next()) {
      prs.getString("WORKDEPT");
      BigDecimal bonus = prs.getBigDecimal("BONUS");
      prs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(1250L)));
      prs.updateRow();
    }

    w = prs.getWarnings();
    assertTrue("0A000".equals(w.getSQLState()));

    // The above thing repeated but with transaction set so no warnings
    // expected
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    uprs = stmt.executeQuery("SELECT workdept, bonus "
        + "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS");
    while (uprs.next()) {
      uprs.getString("WORKDEPT");
      BigDecimal bonus = uprs.getBigDecimal("BONUS");
      uprs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(250L)));
      uprs.updateRow();
    }

    w = uprs.getWarnings();
    assertNull(w);

    uprs.close();
    stmt2 = conn.createStatement();
    rs = stmt2.executeQuery("select * from employee");
    w = rs.getWarnings();
    assertNull(w);

    conn.commit();
    stmt.executeUpdate("update Employee set bonus = 1000 "
        + "where lastname <> 'kumar'");

    w = stmt.getWarnings();
    assertNull(rs.getWarnings());

    stmt2.executeUpdate("update Employee set bonus = bonus + 1000 "
        + "where lastname <> 'kumar'");
    w = stmt2.getWarnings();
    assertNull(w);

    // Prepared Statement
    ps = conn.prepareStatement("SELECT workdept, bonus "
        + "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS",
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

    ps.execute();

    prs = ps.getResultSet();

    while (prs.next()) {
      prs.getString("WORKDEPT");
      BigDecimal bonus = prs.getBigDecimal("BONUS");
      prs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(1250L)));
      prs.updateRow();
    }

    w = prs.getWarnings();
    assertNull(w);

    rs = stmt2.executeQuery("select * from employee");

    while (rs.next()) {
      System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", "
          + rs.getObject(3) + ", " + rs.getObject(4));
    }
  }

  public void _testSelectForUpdateForDebugging() throws Exception {
    Connection conn = getConnection();
    Statement stmtForTableAndInsert = conn.createStatement();
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");
    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");
    conn.commit();
    // conn.setAutoCommit(false);
    // Create the statement with concurrency mode CONCUR_UPDATABLE
    // to allow result sets to be updatable
    conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    // Statement stmt = conn.createStatement();
    // Updatable statements have some requirements
    // for example, select must be on a single table
    // Only bonus can be updated
    // ResultSet uprs = stmt.executeQuery(
    // "SELECT WORKDEPT, BONUS /*, firstname, LastNaME*/ " +
    // "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS");

    // Only bonus can be updated
    ResultSet uprs = stmt.executeQuery("SELECT workdept, bonus "
        + "FROM EMPLOYEE where lastname = 'kumar' FOR UPDATE of BONUS");
    // ResultSet uprs = stmt.executeQuery(
    // "SELECT firstname, count(*) " +
    // "FROM EMPLOYEE group by firstname FOR UPDATE of BONUS"); // Only bonus
    // can be updated

    while (uprs.next()) {
      uprs.getString("WORKDEPT");
      BigDecimal bonus = uprs.getBigDecimal("BONUS");
      // if (workDept.equals(theDept)) {
      if (true) {
        // if the current row meets our criteria,
        // update the updatable column in the row
        uprs.updateBigDecimal("BONUS", bonus.add(BigDecimal.valueOf(250L)));
        // uprs.updateBigDecimal("BONUS", null);
        uprs.updateRow();
        // System.out.println("Updating bonus for employee:" +
        // firstnme +" "+ lastName);
      }
    }
    conn.commit(); // commit the transaction
    // close object
    uprs.close();
    ResultSet rs = stmt.executeQuery("select * from employee");
    while (rs.next()) {
      System.out.println(rs.getString(1) + ", " + rs.getString(2) + ", "
          + rs.getString(3) + ", " + rs.getBigDecimal(4));
    }
    conn.commit();
    stmt.close();
    // Close connection if the application does not need it any more
    conn.close();

  }

  public void testSelectForUpdateIndexScanFromClient() throws Exception {
    setupConnection();
    // start a network server
    Connection conn = startNetserverAndGetLocalNetConnection();
    runSelectForUpdateIndexScan(conn);
  }

  public void testSelectForUpdateIndexScan() throws Exception {
    Connection conn = getConnection();
    runSelectForUpdateIndexScan(conn);
  }

  protected void runSelectForUpdateIndexScan(final Connection conn)
      throws Exception {
    Statement stmtForTableAndInsert = conn.createStatement();

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname)) replicate");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
      assertNotNull(this.keyMap);
    } finally {
      this.callbackInvoked = false;
      if (this.keyMap != null) {
        assertEquals(1, this.keyMap.size());
        CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
            .keySet().iterator().next();
        DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
        ckey.getKeyColumns(dvds);
        assertEquals("neeraj", dvds[0].getString());
        assertEquals("kumar", dvds[1].getString());
        this.keyMap = null;
      }
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop index APP.LASTNAMEIDX");
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      assertEquals(1, this.keyMap.size());
      CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
          .keySet().iterator().next();
      DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
      ckey.getKeyColumns(dvds);
      assertEquals("neeraj", dvds[0].getString());
      assertEquals("kumar", dvds[1].getString());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop index lastNameIdx");
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT firstname, bonus FROM EMPLOYEE where "
          + "lastname <> 'kumar' FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
      assertNotNull(this.keyMap);
    } finally {
      this.callbackInvoked = false;
      if (this.keyMap != null) {
        assertEquals(3, this.keyMap.size());
        this.keyMap = null;
      }
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop index lastNameIdx");
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname)) replicate");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname <> 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
      assertNotNull(this.keyMap);
    } finally {
      this.callbackInvoked = false;
      if (this.keyMap != null) {
        assertEquals(3, this.keyMap.size());
        this.keyMap = null;
      }
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop index lastNameIdx");
      stmtForTableAndInsert.execute("drop table Employee");
    }
  }

  public void testSelectForUpdateHeapScanFromClient() throws Exception {
    setupConnection();
    // start a network server
    Connection conn = startNetserverAndGetLocalNetConnection();
    runSelectForUpdateHeapScan(conn);
  }

  public void testSelectForUpdateHeapScan() throws Exception {
    Connection conn = getConnection();
    runSelectForUpdateHeapScan(conn);
  }

  protected void runSelectForUpdateHeapScan(final Connection conn)
      throws Exception {
    Statement stmtForTableAndInsert = conn.createStatement();

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname)) replicate");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      assertEquals(1, this.keyMap.size());
      CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
          .keySet().iterator().next();
      DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
      ckey.getKeyColumns(dvds);
      assertEquals("neeraj", dvds[0].getString());
      assertEquals("kumar", dvds[1].getString());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      assertEquals(1, this.keyMap.size());
      CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
          .keySet().iterator().next();
      DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
      ckey.getKeyColumns(dvds);
      assertEquals("neeraj", dvds[0].getString());
      assertEquals("kumar", dvds[1].getString());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname <> 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      assertEquals(3, this.keyMap.size());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname)) replicate");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname <> 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmtForTableAndInsert.executeQuery(query);
      while (rs.next()) {

      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      assertEquals(3, this.keyMap.size());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }
  }

  public void testSelectForUpdateCasesForSelectQueryInfoState()
      throws Exception {
    Connection conn = getConnection();
    Statement stmtForTableAndInsert = conn.createStatement(
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE,
        ResultSet.CLOSE_CURSORS_AT_COMMIT);
    conn.setTransactionIsolation(getIsolationLevel());
    // when partitioning should be obtained from projection
    // replicate
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname)) replicate");

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      stmtForTableAndInsert.execute("drop table Employee");
      conn.commit();
    }
    // partition by primary key explicit single column key
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname)) partition by primary key");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }
    // partition by primary key explicit composite column key
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname, lastname)) partition by primary key");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }
    // default primary key partitioning single key
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname))");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }
    // default primary key partitioning composite key
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      stmtForTableAndInsert.execute("drop table Employee");
      conn.commit();
    }
    // partition by expression
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname, lastname)) "
        + "partition by column(lastname, firstname)");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertFalse(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      stmtForTableAndInsert.execute("drop table Employee");
      conn.commit();
    }
    // partition by range
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname, lastname)) "
        + "partition by list(lastname) (values('kumar', 'ji'))");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                sqi.getPartitioningColumnFromProjection();
              }
            }
          });
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }

    // when partitioning not at all in projection
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname)) "
        + "partition by list(lastname) (values('kumar', 'ji'))");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertFalse(sqi.needKeysForSelectForUpdate());
                assertTrue(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT firstname, workdept FROM EMPLOYEE where "
          + "lastname = 'kumar' FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      stmtForTableAndInsert.execute("drop table Employee");
      conn.commit();
    }
    // when partitioning partly in projection
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4)) "
        + "partition by column(lastname, firstname)");

    old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectForUpdateTest.this.callbackInvoked = true;
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isSelectForUpdateCase());
                assertTrue(sqi.needKeysForSelectForUpdate());
                assertTrue(sqi.isRoutingCalculationRequired());
                int[] cols = sqi.getPartitioningColumnFromProjection();
                assertNull(cols);
              }
            }
          });
      String query = "SELECT firstname, workdept, bonus FROM EMPLOYEE "
          + "where lastname = 'kumar' FOR UPDATE of BONUS";

      stmtForTableAndInsert.executeQuery(query);
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      conn.commit();
      stmtForTableAndInsert.execute("drop table Employee");
    }
    // trying to update primary key col should throw exception
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname)) "
        + "partition by list(lastname) (values('kumar', 'ji'))");

    String query = "SELECT firstname, workdept FROM EMPLOYEE where "
        + "lastname = 'kumar' FOR UPDATE of firstname";

    boolean gotexception = false;
    try {
      stmtForTableAndInsert.execute(query);
    } catch (SQLException e) {
      e.getSQLState().equals("0A000");
      gotexception = true;
      stmtForTableAndInsert.execute("drop table Employee");
    }

    assertTrue(gotexception);
    gotexception = false;
    // trying to update partitioning key column should throw exception
    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50) not null, bonus decimal(10,4), "
        + "primary key (firstname)) "
        + "partition by list(lastname) (values('kumar', 'ji'))");

    query = "SELECT firstname, workdept FROM EMPLOYEE where "
        + "lastname = 'kumar' FOR UPDATE of lastname";

    try {
      stmtForTableAndInsert.execute(query);
    } catch (SQLException e) {
      e.getSQLState().equals("0A000");
      gotexception = true;
      stmtForTableAndInsert.execute("drop table Employee");
    }
    conn.commit(); // commit the transaction

    stmtForTableAndInsert.close();
    // Close connection if the application does not need it any more
    conn.close();
  }

  public void testSelectForUpdateLockingFromClient() throws Exception {
    final Properties props = new Properties();
    props.setProperty("mcast-port", String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    setupConnection(props);
    // start a network server
    final int netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    startNetServer(netPort, null);
    final Connection conn1 = getNetConnection(netPort, null, null);
    final Connection conn2 = getNetConnection(netPort, null, null);
    runSelectForUpdateLocking(conn1, conn2);
  }

  public void testSelectForUpdateLocking() throws Exception {
    final Connection conn1 = getConnection();
    final Connection conn2 = getConnection();
    runSelectForUpdateLocking(conn1, conn2);
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  protected void runSelectForUpdateLocking(final Connection conn1,
      final Connection conn2) throws Exception {
    Statement stmtForTableAndInsert = conn1.createStatement();

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname)) replicate");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn1.commit();
    conn1.setTransactionIsolation(getIsolationLevel());
    conn1.setAutoCommit(false);
    Statement stmt = conn1.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where firstname = 'neeraj' "
          + "and lastname = 'kumar' FOR UPDATE of BONUS, workdept";

      ResultSet rs = stmt.executeQuery(query);
      while (rs.next()) {
      }
      assertTrue(this.callbackInvoked);

      // check that the row is locked correctly and causes conflict
      conn2.setTransactionIsolation(getIsolationLevel());
      conn2.setAutoCommit(false);
      Statement stmt2 = conn2.createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);
      try {
        stmt2.execute("update employee set bonus=1.0 where lastname='kumar'");
        fail("expected a conflict");
      } catch (SQLException sqle) {
        if (!"X0Z02".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      // no conflict after commit
      conn1.commit();
      stmt2.execute("update employee set bonus=5.0 where lastname='kumar'");
      conn2.commit();
    } finally {
      this.callbackInvoked = false;
      assertEquals(1, this.keyMap.size());
      CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
          .keySet().iterator().next();
      DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
      ckey.getKeyColumns(dvds);
      assertEquals("neeraj", dvds[0].getString());
      assertEquals("kumar", dvds[1].getString());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      stmtForTableAndInsert.execute("drop index APP.LASTNAMEIDX");
      stmtForTableAndInsert.execute("drop table Employee");
      conn1.commit();
    }

    stmtForTableAndInsert.execute("create table Employee"
        + "(firstname varchar(50) not null, lastname varchar(50) not null, "
        + "workdept varchar(50), bonus decimal(10,4), "
        + "primary key (firstname, lastname))");

    stmtForTableAndInsert
        .execute("create index lastNameIdx on Employee(lastname)");

    stmtForTableAndInsert
        .execute("insert into employee values('neeraj', 'kumar', 'rnd', 0.0), "
            + "('asif', 'shahid', 'rnd', 1.0), "
            + "('dada', 'ji', 'rnd', 2.0), ('sum', 'wale', 'rnd', 3.0)");

    conn1.commit();
    conn1.setTransactionIsolation(getIsolationLevel());
    stmt = conn1.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE, ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new SelectForUpdateObserver());
      String query = "SELECT * FROM EMPLOYEE where lastname = 'kumar' "
          + "FOR UPDATE of BONUS";

      ResultSet rs = stmt.executeQuery(query);
      while (rs.next()) {
      }
      assertTrue(this.callbackInvoked);

      // check that the row is locked correctly and causes conflict
      conn2.setTransactionIsolation(getIsolationLevel());
      Statement stmt2 = conn2.createStatement();
      try {
        stmt2.execute("update employee set bonus=1.0 where lastname='kumar'");
        fail("expected a conflict");
      } catch (SQLException sqle) {
        if (!"X0Z02".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      // straight updates in same TX should be fine
      stmt.execute("update employee set bonus=3.0 where lastname='kumar'");
      conn1.commit();
      rs = stmt2
          .executeQuery("select bonus from employee where lastname='kumar'");
      assertTrue(rs.next());
      assertEquals(3.0, rs.getDouble(1));
      assertFalse(rs.next());
      stmt2.execute("update employee set bonus=5.0 where lastname='kumar'");
      conn2.commit();
      rs = stmt
          .executeQuery("select bonus from employee where lastname='kumar'");
      assertTrue(rs.next());
      assertEquals(5.0, rs.getDouble(1));
      assertFalse(rs.next());
    } finally {
      this.callbackInvoked = false;
      assertEquals(1, this.keyMap.size());
      CompactCompositeRegionKey ckey = (CompactCompositeRegionKey)this.keyMap
          .keySet().iterator().next();
      DataValueDescriptor[] dvds = new DataValueDescriptor[ckey.nCols()];
      ckey.getKeyColumns(dvds);
      assertEquals("neeraj", dvds[0].getString());
      assertEquals("kumar", dvds[1].getString());
      this.keyMap = null;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  class SelectForUpdateObserver extends GemFireXDQueryObserverAdapter {

    @Override
    public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
        RegionEntry entry, boolean writeLock) {
      if (writeLock) {
        SelectForUpdateTest.this.callbackInvoked = true;
        if (SelectForUpdateTest.this.keyMap == null) {
          SelectForUpdateTest.this.keyMap = new HashMap<Object, Object>();
        }
        SelectForUpdateTest.this.keyMap.put(entry.getKeyCopy(), null);
      }
    }

    @Override
    public void attachingKeyInfoForUpdate(GemFireContainer container,
        RegionEntry entry) {
      fail("This method should not have been invoked");
    }
  }
}
