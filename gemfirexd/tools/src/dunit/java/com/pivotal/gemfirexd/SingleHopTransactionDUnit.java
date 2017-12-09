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

package com.pivotal.gemfirexd;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.derby.drda.NetworkServerControl;

import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase;
import com.pivotal.gemfirexd.ddl.IndexPersistenceDUnit;

import io.snappydata.test.dunit.VM;

/**
 * @author kneeraj
 * 
 */
@SuppressWarnings("serial")
public class SingleHopTransactionDUnit extends DBSynchronizerTestBase {

  public SingleHopTransactionDUnit(String name) {
    super(name);
  }

  public void testDummy() {
  }

  public void DISABLED_testDMLs() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, false, false, false, false, false, false, true);
      runTest(connSHOP);
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  public void DISABLED_testDMLsPkNotPartKey() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, false, false, false, false, false, false, false);
      runTest(connSHOP);
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  public void DISABLED_testWithPersistence() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, false, true, false, false, false, false, false);
      runTest(connSHOP);
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  public void DISABLED_testWithEviction() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, false, false, true, false, false, false, true);
      runTest(connSHOP);
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  public void DISABLED_testGlobalIndexes() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, false, false, false, false, true, false, false);
      runTest(connSHOP);
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  public void DISABLED_testTriggers() throws Exception {
    if(isTransactional) {
      return;
    }
    try {
      Connection connSHOP = initialSetup(getIsolationLevel());
      createTable(connSHOP, true, false, false, false, false, false, true);
      runTest(connSHOP);

      // verify the trigger table
      for (VM vm : serverVMs) {
        vm.invoke(SingleHopTransactionDUnit.class, "verifyShadowTable",
            new Object[] { Integer.valueOf(20), Integer.valueOf(0) });
      }
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  // TODO Enable after fixing suspect strings
  public void _testDbsynchronizer() throws Exception {
    try {
      // start some servers
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "true" });
      startVMs(0, 2, 0, "SG1", null);
      // Start network server on the VMs

      final int netPort = startNetworkServer(1, "SG1", null);
      startNetworkServer(2, "SG1", null);

      // Use this VM as the network client
      TestUtil.loadNetDriver();

      Connection connSHOP;
      final Properties connSHOPProps = new Properties();
      connSHOPProps.setProperty("single-hop-enabled", "true");
      connSHOPProps.setProperty("single-hop-max-connections", "5");
      connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

      String url = TestUtil.getNetProtocol("localhost", netPort);
      connSHOP = DriverManager.getConnection(url,
          TestUtil.getNetProperties(connSHOPProps));
      connSHOP.setTransactionIsolation(getIsolationLevel());

      Statement derbyStmt = null;
      Connection derbyConn = null;
      NetworkServerControl server = null;
      try {
        String derbyDbUrl = getDerbyURL(this.netPort);
        server = startNetworkServer();
        createDerbyValidationArtefacts();
        derbyConn = DriverManager.getConnection(derbyDbUrl);
        derbyStmt = derbyConn.createStatement();
        getLogWriter().info("Started derby network server");

        Statement st = connSHOP.createStatement();
        st.execute("create table TESTTABLE (ID int not null primary key , "
            + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int )"
            + " AsyncEventListener (WBCL1) ");

        Runnable createWBCLConfig = createAsyncQueueConfigurationForBasicTests(derbyDbUrl);
        VM vm = this.serverVMs.get(1);
        vm.invoke(createWBCLConfig);
        Runnable startWBCL = startAsyncEventListener("WBCL1");
        vm.invoke(startWBCL);
        getLogWriter().info("Created and started AsyncEventListener WBCL1");

        // Do an insert in sql fabric ... so that buckets get created
        connSHOP.createStatement().execute(
            "Insert into TESTTABLE values(1,'desc1','Add1',1)");
        connSHOP.createStatement().execute(
            "Insert into TESTTABLE values(2,'desc2','Add2',2)");
        connSHOP.commit();
        connSHOP.createStatement().execute("delete from TESTTABLE");

        PreparedStatement pInsert = connSHOP
            .prepareStatement("insert into TESTTABLE values(?, ?, ?, ?)");
        pInsert.setInt(1, 114);
        pInsert.setString(2, "higher1");
        pInsert.setString(3, "higher2");
        pInsert.setInt(4, 114);
        assertEquals(1, pInsert.executeUpdate());

        pInsert.setInt(1, 115);
        pInsert.setString(2, "higher1");
        pInsert.setString(2, "higher2");
        pInsert.setInt(4, 115);
        assertEquals(1, pInsert.executeUpdate());
        connSHOP.commit();
        // check that queue is empty.
        serverSQLExecute(1,
            "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('WBCL1', 1, 30)");

        ResultSet rs = derbyStmt.executeQuery("select * from testtable");
        while (rs.next()) {
          getLogWriter().info(
              "KN: rows in derby = " + rs.getObject(1) + ", " + rs.getObject(3)
                  + ", " + rs.getObject(3) + ", " + rs.getObject(4));
        }
      } finally {
        derbyCleanup(derbyStmt, derbyConn, server);
      }
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfirexd.send-tx-id-on-commit", "false" });
    }
  }

  // TODO: KN add a test for this
  public void DISABLED_testPooling() throws Exception {
    // To check that single hop works with pooling
  }

  // TODO: KN add a test for this
  public void DISABLED_testWithClobsBlobsInSchema() throws Exception {

  }

  private void runTest(Connection connSHOP) throws Exception {
    Statement st = connSHOP.createStatement();
    ResultSet rs;
    com.pivotal.gemfirexd.internal.client.am.Connection thinconnobj = (com.pivotal.gemfirexd.internal.client.am.Connection)connSHOP;
    // first do some inserts so that the buckets get created and actually
    // single hop happens when we fire prepared statements
    insertValues(st, 10, false);
    assertTrue(thinconnobj.executionSequence >= 10);
    getLogWriter().info(
        "1 ... execution sequence = " + thinconnobj.executionSequence);
    getLogWriter().info("Issuing commit 1");
    connSHOP.commit();

    assertEquals(0, thinconnobj.executionSequence);

    int cnt = 0;
    rs = st.executeQuery("select * from test.example");
    while (rs.next()) {
      getLogWriter().info(
          rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
              + ", " + rs.getObject(4));
      cnt++;
    }
    assertEquals(10, cnt);

    // Now inserting from 0 to 10 should do single hop
    PreparedStatement pInsert = connSHOP
        .prepareStatement("insert into test.example values (?, ?, ?, ?)");
    for (int i = 0; i < 10; i++) {
      pInsert.setInt(1, i);
      pInsert.setInt(2, i);
      pInsert.setString(3, "CO");
      pInsert.setString(4, "var" + i);
      cnt = pInsert.executeUpdate();
      assertEquals(1, cnt);
    }

    assertTrue(thinconnobj.executionSequence >= 10);
    getLogWriter().info(
        "2 ... execution sequence = " + thinconnobj.executionSequence);
    getLogWriter().info("Issuing commit 2");
    Thread.sleep(1000);
    connSHOP.commit();
    assertEquals(0, thinconnobj.executionSequence);
    st.execute("select * from test.example");
    rs = st.getResultSet();
    cnt = 0;
    getLogWriter().info("expecting 20 results ... 10 lower and 10 higher");
    while (rs.next()) {
      getLogWriter().info(
          rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
              + ", " + rs.getObject(4));
      cnt++;
    }
    assertEquals(20, cnt);
    assertFalse(rs.next());

    assertTrue(thinconnobj.executionSequence >= 1);
    getLogWriter().info(
        "3 ... execution sequence = " + thinconnobj.executionSequence);

    // Now do selects
    PreparedStatement pSelect = connSHOP
        .prepareStatement("select * from test.example where c1 = ?");
    pSelect.setInt(1, 5);
    rs = pSelect.executeQuery();
    assertTrue(rs.next());
    assertEquals(5, rs.getInt(1));
    assertFalse(rs.next());

    // Now do few updates
    PreparedStatement pUpdate = connSHOP
        .prepareStatement("update test.example set c4 = ? where c1 = ?");
    for (int i = 113; i < (113 + 10); i++) {
      pUpdate.setString(1, "NEWSTRING");
      pUpdate.setInt(2, i);
      assertEquals(1, pUpdate.executeUpdate());
    }
    assertTrue(thinconnobj.executionSequence >= 10);
    getLogWriter().info(
        "4 ... execution sequence = " + thinconnobj.executionSequence);
    getLogWriter().info("Issuing commit 3");
    connSHOP.commit();

    assertEquals(0, thinconnobj.executionSequence);

    // Now verify with simple select
    st.execute("select * from test.example where c4 = 'NEWSTRING'");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(10, cnt);

    // Now few deletes
    PreparedStatement pDelete = connSHOP
        .prepareStatement("delete from test.example where c1 = ?");
    for (int i = 0; i < 10; i++) {
      int j = i;
      if (i % 2 == 0) {
        j = 113 + i;
      }
      pDelete.setInt(1, j);
      assertEquals(1, pDelete.executeUpdate());
    }

    assertTrue(thinconnobj.executionSequence >= 10);
    getLogWriter().info(
        "5 ... execution sequence = " + thinconnobj.executionSequence);

    connSHOP.commit();

    assertEquals(0, thinconnobj.executionSequence);

    // wait for any pending commits before verification
    for (VM vm : serverVMs) {
      vm.invoke(TXManagerImpl.class, "waitForPendingCommitsForTest");
    }

    // Now check from all the server vms that all data is there
    for (VM vm : serverVMs) {
      vm.invoke(SingleHopTransactionDUnit.class, "verifyResults", new Object[] {
          Integer.valueOf(10), Integer.valueOf(5), Boolean.TRUE });
    }
  }

  private void createTable(Connection conn, boolean addTrigger,
      boolean persistence, boolean overflow, boolean addDBSync,
      boolean globalIndex, boolean noPk, boolean partitionBypk)
      throws Exception {
    // If pk is there then we will keep c1 as the primary key
    // If partition by pk is not there then we will have partition by column on
    // c2
    int redundancy = 0;
    long currMillis = System.currentTimeMillis();
    if (currMillis % 5 == 0) {
      redundancy = 0;
    }
    else if (currMillis % 2 == 0) {
      redundancy = 1;
    }
    else {
      redundancy = 2;
    }
    Statement s = conn.createStatement();
    StringBuilder tableDdl = new StringBuilder();
    tableDdl.append("create table test.example");
    tableDdl.append(" (");
    tableDdl.append("c1 int not null");
    if (noPk) {
      tableDdl.append(", ");
    }
    else {
      tableDdl.append(" primary key, ");
    }
    // add rest of the columns 1 more int, 1 char and 1 varchar
    tableDdl.append("c2 int not null, ");
    tableDdl.append("c3 char(2) not null, ");
    tableDdl.append("c4 varchar(10) not null");
    tableDdl.append(") ");
    // partitioning clause
    if (globalIndex) {
      assertFalse(partitionBypk);
      tableDdl.append("partition by column(c2) redundancy ");
      tableDdl.append(redundancy);
    }
    else if (partitionBypk) {
      tableDdl.append("partition by column(c1) redundancy ");
      tableDdl.append(redundancy);
    }
    else {
      tableDdl.append(" redundancy ");
      tableDdl.append(redundancy);
    }
    if (persistence) {
      tableDdl.append(" persistent ");
    }
    if (overflow) {
      tableDdl
          .append(" EVICTION BY LRUCOUNT 10 EVICTACTION overflow asynchronous ");
    }
    getLogWriter().info("table ddl is: " + tableDdl.toString());
    s.execute(tableDdl.toString());

    if (addTrigger) {
      // create the table in which the after insert will go
      s.execute("create table test.shadow(c1 int not null, c2 int not null, c3 char(2) not null, c4 varchar(10) not null) replicate");
      // create an after insert trigger
      String triggerStmnt = "create trigger insertTrigger "
          + "after insert on test.example " + "REFERENCING NEW AS NEWROW "
          + "FOR EACH ROW MODE DB2SQL " + "INSERT INTO test.shadow VALUES "
          + "(NEWROW.c1, NEWROW.c2, NEWROW.c3, NEWROW.c4)";
      s.execute(triggerStmnt);
    }
  }

  private Connection initialSetup(int isolationLevel) throws Exception {
    // start some servers
    invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
        new Object[] { "gemfirexd.send-tx-id-on-commit", "true" });
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);
    startNetworkServer(3, null, null);
    startNetworkServer(4, null, null);
    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

    String url = TestUtil.getNetProtocol("localhost", netPort);
    connSHOP = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connSHOPProps));
    connSHOP.setTransactionIsolation(isolationLevel);
    return connSHOP;
  }

  private void insertValues(Statement st, int num, boolean lowVals)
      throws SQLException {
    int base = 0;
    if (lowVals) {
      base = 0;
    }
    else {
      base = 113;
    }
    for (int i = 0; i < num; i++) {
      int c1, c2;
      c1 = c2 = base + i;
      String c3 = "CO";
      String c4 = "var" + c1;
      st.execute("insert into test.example values(" + c1 + ", " + c2 + ", '"
          + c3 + "', '" + c4 + "')");
    }
  }

  public int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  public static void verifyResults(Integer totalRecords,
      Integer newStringRecords, Boolean newStringInHigher) throws SQLException {
    Connection conn = TestUtil.getConnection();
    ResultSet rs = conn.createStatement().executeQuery(
        "select * from test.example");
    int cnt = 0;
    int newStrCnt = 0;
    while (rs.next()) {
      cnt++;
      if (rs.getString(4).equalsIgnoreCase("NEWSTRING")) {
        newStrCnt++;
        if (newStringInHigher) {
          assertTrue(rs.getInt(1) >= 113);
        }
      }
    }
    assertEquals(totalRecords.intValue(), cnt);
    assertEquals(newStringRecords.intValue(), newStrCnt);
  }

  public static void verifyShadowTable(Integer totalInserted,
      Integer newStringRecords) throws SQLException {
    Connection conn = TestUtil.getConnection();
    ResultSet rs = conn.createStatement().executeQuery(
        "select * from test.shadow");
    int cnt = 0;
    int newStrCnt = 0;
    while (rs.next()) {
      cnt++;
      if (rs.getString(4).equalsIgnoreCase("NEWSTRING")) {
        newStrCnt++;
      }
    }
    assertEquals(totalInserted.intValue(), cnt);
    assertEquals(newStringRecords.intValue(), newStrCnt);
  }

  protected Runnable createAsyncQueueConfigurationForBasicTests(String derbyUrl) {
    return getExecutorForWBCLConfiguration("SG1", "WBCL1",
        "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyUrl, true,
        Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
        "org.apache.derby.jdbc.ClientDriver," + derbyUrl, false);
  }
}
