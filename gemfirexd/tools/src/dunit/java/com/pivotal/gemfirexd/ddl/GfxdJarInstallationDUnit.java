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
package com.pivotal.gemfirexd.ddl;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;

import org.apache.derbyTesting.junit.JDBC;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.GfxdGatewayEventListener;
import com.pivotal.gemfirexd.jdbc.GfxdJarInstallationTest;
import com.pivotal.gemfirexd.tools.internal.JarTools;

import io.snappydata.test.dunit.VM;

@SuppressWarnings("serial")
public class GfxdJarInstallationDUnit extends DistributedSQLTestBase {

  private final String myjar = TestUtil.getResourcesDir()
      + "/lib/myjar.jar";

  private final String myfalsejar = TestUtil.getResourcesDir()
      + "/lib/myfalsejar.jar";

  private final String callbackjar = TestUtil.getResourcesDir()
  + "/lib/callback.jar";
  
  private final String udtjar = TestUtil.getResourcesDir()
  + "/lib/udt.jar";

  private final String dbsyncjar = TestUtil.getResourcesDir()
      + "/lib/sectdbsync.jar";

  public GfxdJarInstallationDUnit(String name) {
    super(name);
  }
  
  public void testBug44223() throws Exception {
    final Properties locatorProps = new Properties();
    setMasterCommonProperties(locatorProps);
    String locatorBindAddress = "localhost";
    int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    _startNewLocator(this.getClass().getName(), getName(), locatorBindAddress,
        locatorPort, null, locatorProps);
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    TestUtil.startNetServer(netPort, null);

    // Start network server on the VMs
    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", locatorBindAddress + '[' + locatorPort
        + ']');
    startVMs(0, 1, 0, null, serverProps);
    startNetworkServer(1, null, null);
    Connection conn;

    String url = TestUtil.getNetProtocol(locatorBindAddress, netPort);
    conn = DriverManager.getConnection(url);

    Statement st = conn.createStatement();

    st.execute("call sqlj.install_jar('" + udtjar + "', 'app.udtjar', 0)");
  }

  public void testBug44224() throws Exception {
    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);

    Statement st = conn.createStatement();

    st.execute("call sqlj.install_jar('" + udtjar + "', 'app.udtjar', 0)");
    
    st.execute("create type Price " +
    		"external name 'org.apache.derbyTesting.functionTests.tests.lang.Price' " +
    		"language java");
    
    st.execute("create table orders( " +
    		"orderID int generated always as identity, " +
    		"customerID int, " +
    		"totalPrice price)");
    
    st.execute("create function makePrice( " +
    		"currencyCode char( 3 ), " +
    		"amount decimal( 31, 5 ), " +
    		"timeInstant Timestamp ) " +
    		"returns Price " +
    		"language java " +
    		"parameter style java no sql " +
    		"external name 'org.apache.derbyTesting.functionTests.tests.lang.Price.makePrice'");
    
    st.execute("create function getCurrencyCode( price Price ) " +
    		"returns char( 3 ) " +
    		"language java " +
    		"parameter style java no sql " +
    		"external name 'org.apache.derbyTesting.functionTests.tests.lang.Price.getCurrencyCode'");
    
    st.execute("create function getTimeInstant( price Price ) " +
    		"returns timestamp " +
    		"language java " +
    		"parameter style java no sql " +
    		"external name 'org.apache.derbyTesting.functionTests.tests.lang.Price.getTimeInstant'");
    
    st.execute("create function getAmount( price Price ) " +
    		"returns decimal( 31, 5 ) " +
    		"language java " +
    		"parameter style java no sql " +
    		"external name 'org.apache.derbyTesting.functionTests.tests.lang.Price.getAmount'");
    
    st.execute("insert into orders( customerID, totalPrice ) " +
    		"values ( 12345, " +
    		"makePrice( 'USD', cast( 9.99 as decimal( 31, 5 ) ), timestamp('2009-10-16 14:24:43') ) )");
    
    st.execute("select getCurrencyCode( totalPrice ) from orders");
    
    st.execute("select getCurrencyCode( totalPrice ), getAmount(totalPrice) from orders");
    
    st.execute("select getTimeInstant( totalPrice ) from orders");
    
    st.execute("select getAmount(totalPrice) from orders");
  }
  
  public void testBug44517() throws Exception {

    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);
    Statement st = conn.createStatement();
    st.execute("call sqlj.install_jar('" + callbackjar + "', 'app.rowloaderjar', 0)");
    st.execute("create table APP.CallbackTest (data varchar(40))");
    st.execute("call sys.ATTACH_LOADER('APP','CallbackTest','gemfirexd.callbacktests.GfxdTestRowLoader','RowLoader Init')");
  }

  public void testBug44501() throws Exception {

    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);
    Statement st = conn.createStatement();
    st.execute("call sqlj.install_jar('" + callbackjar + "', 'app.writerjar', 0)");
    st.execute("create table APP.WriterTest (data varchar(40))");
    st.execute("call sys.attach_writer('APP','WriterTest','gemfirexd.callbacktests.GfxdTestWriter', 'in-writer', null)");
  }

  public void testBug44492() throws Exception {

    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);
    Statement st = conn.createStatement();
    st.execute("call sqlj.install_jar('" + callbackjar + "', 'app.listenerjar', 0)");
    st.execute("create table APP.CBTEST (data varchar(40))");
    st.execute("call SYS.ADD_LISTENER('callbacktest', 'APP', 'CBTEST', 'gemfirexd.callbacktests.GfxdTestListener', 'initialtest', null)");
  }

  public void testJarInstallRemoveReplace_novti() throws Exception {
    startClientVMs(1, 0, null);
    startServerVMs(1, 0, null);
    
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("call sqlj.install_jar('" + myjar + "', 'app.sample2', 0)");

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    stmt.execute(ddl);

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4')");

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR 'myexamples.MergeSortProcessor'");

    String sql = "CALL MergeSort() " + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sql);
    cs.execute();

    cs.getResultSet();
    
    startServerVMs(1, 0, null);
    stopVMNum(-1);
    
    cs = conn.prepareCall(sql);
    cs.execute();
  }

  public void testJarRemove() throws Exception {
    startClientVMs(1, 0, null);
    startServerVMs(2, 0, null);
    
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("call sqlj.install_jar('" + myjar + "', 'app.sample2', 0)");

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    stmt.execute(ddl);

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4')");

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR 'myexamples.MergeSortProcessor'");

    String sql = "CALL MergeSort() " + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sql);
    cs.execute();

    cs.getResultSet();
    
    stmt.execute("call sqlj.remove_jar('app.sample2', 0)");

    try {
      stmt.execute("call sqlj.remove_jar('app.sample3', 0)");
      fail("expected an exception for non-existent jar 'app.sample3'");
    } catch (SQLException sqle) {
      if (!"X0X13".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }
  
  public void test52526() throws Exception {
    startVMs(1, 2);
    String localHostName = "localhost";
    int netPort = startNetworkServer(1, null, null);

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create schema fees");

    JarTools.main(new String[] { "install-jar", "-file=" + myjar,
        "-name=fees.testjar", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName });

    try {
      stmt.execute("drop schema fees restrict");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("X0Y54"))
        throw se;
    }

    JarTools.main(new String[] { "remove-jar", "-name=fees.testjar",
        "-client-port=" + netPort, "-client-bind-address=" + localHostName });

    stmt.execute("drop schema fees restrict");
    stmt.execute("create schema fees");

    JarTools.main(new String[] { "install-jar", "-file=" + myjar,
        "-name=fees.testjar", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName });

  }


  public void testJarInstallRemoveReplace_vti() throws Exception {
    startVMs(1, 2);
    final String localHostName = "localhost";
    final int netPort = startNetworkServer(1, null, null);

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    String sql;
    CallableStatement mergeCS = null;
    // try with both procedures and "gfxd install-jar/replace-jar" tool
    for (int i = 1; i <= 4; i++) {
      if (i == 1) {
        sql = "call SQLJ.INSTALL_JAR_BYTES(?, ?)";
        CallableStatement cs = conn.prepareCall(sql);
        cs.setBinaryStream(1, new FileInputStream(myjar));
        cs.setString(2, "app.sample1");
        cs.executeUpdate();
      }
      else if (i == 2) {
        JarTools.main(new String[] { "install-jar", "-file=" + myjar,
            "-name=app.sample1", "-client-port=" + netPort,
            "-client-bind-address=" + localHostName });
      }
      else if (i == 3) {
        sql = "call SQLJ.REPLACE_JAR_BYTES(?, ?)";
        CallableStatement cs = conn.prepareCall(sql);
        cs.setBytes(1, GfxdJarInstallationTest.getJarBytes(myfalsejar));
        cs.setString(2, "app.sample1");
        cs.executeUpdate();
      }
      else {
        JarTools.main(new String[] { "replace-jar", "-file=" + myjar,
            "-name=app.sample1", "-client-port=" + netPort,
            "-client-bind-address=" + localHostName });
      }

      String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
          + " SECONDID int not null, THIRDID varchar(10) not null,"
          + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

      stmt.execute(ddl);

      stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
      stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");
      stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4')");

      stmt.execute("CREATE PROCEDURE MergeSort () "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA "
          + "READS SQL DATA DYNAMIC RESULT SETS 1 "
          + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

      stmt.execute("CREATE ALIAS MergeSortProcessor FOR "
          + "'myexamples.MergeSortProcessor'");

      sql = "CALL MergeSort() WITH RESULT PROCESSOR MergeSortProcessor "
          + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

      try {
        if (mergeCS == null) {
          mergeCS = conn.prepareCall(sql);
        }
        mergeCS.execute();

        checkMergeResults(mergeCS);
        if (i == 3) {
          fail("expected exception with incorrect jar");
        }
      } catch (SQLException sqle) {
        if (i != 3 || !"42X51".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      if (i != 4) {
        stmt.execute("drop alias MergeSortProcessor");
        stmt.execute("drop procedure MergeSort");
        stmt.execute("drop table EMP.PARTITIONTESTTABLE");
        if (i != 2 && i != 3) {
          JarTools.main(new String[] { "remove-jar",
              "-name=APP.sample1", "-client-port=" + netPort,
              "-client-bind-address=" + localHostName });
        }
      }
    }

    // test GII with replicated table (partitioned table will not have all data
    // replicated on the new server)
    String ddl = "create table EMP.REPLICATETESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) REPLICATE";
    stmt.execute(ddl);

    stmt.execute("INSERT INTO EMP.REPLICATETESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.REPLICATETESTTABLE VALUES (3, 3, '3')");
    stmt.execute("INSERT INTO EMP.REPLICATETESTTABLE VALUES (2, 2, '4')");

    startServerVMs(1, 0, null);
    stopVMNums(-1, -2);

    sql = "CALL MergeSort() WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.REPLICATETESTTABLE WHERE 1=1";
    mergeCS = conn.prepareCall(sql);
    mergeCS.execute();

    checkMergeResults(mergeCS);
  }

  private void checkMergeResults(CallableStatement cs) throws SQLException {
    ResultSet rs = cs.getResultSet();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertEquals(3, rs.getInt(2));
    assertFalse(rs.next());
  }

  public void testUDTINJarFromClient() throws Exception {
    // start a client and few servers
    startVMs(1, 3);

    // start a network server
    int port = startNetworkServer(1, null, null);
    Connection netConn = TestUtil.getNetConnection(port, null, null);
    GfxdJarInstallationTest
        .udtTypeTest(netConn, true, false, true, true, false);
    GfxdJarInstallationTest.udtTypeTest(netConn, false, false, true, true,
        false);

    // now with embedded connection
    Connection conn = TestUtil.getConnection();
    GfxdJarInstallationTest.udtTypeTest(conn, false, false, true, true, false);
    GfxdJarInstallationTest.udtTypeTest(conn, true, false, true, true, false);
  }

  public void testUDTINJarFromClientWithLocator() throws Exception {
    // Allow test when bug #51551 is fixed
    if (isTransactional) {
      return;
    }
    // start a locator
    final InetAddress localHost = InetAddress.getByName("localhost");
    FabricLocator fabapi = FabricServiceManager.getFabricLocatorInstance();
    final Properties locProps = new Properties();
    setCommonProperties(locProps, 0, null, null);
    locProps.remove("locators");
    final int netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    // also start a network server
    int locPort = TestUtil.startLocator(localHost.getHostAddress(), netPort,
        locProps);

    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", localHost.getHostName() + '[' + locPort
        + ']');
    startServerVMs(3, 0, null, serverProps);
    startNetworkServer(2, null, null);
    startNetworkServer(1, null, null);

    Connection netConn = TestUtil.getNetConnection(netPort, null, null);

    // check with persistence and all servers restart including locator
    GfxdJarInstallationTest.udtTypeTest(netConn, false, false, true, false,
        true);
    stopVMNums(-1, -2, -3);
    fabapi.stop(null);
    // restart locator and servers
    locPort = TestUtil.startLocator(localHost.getHostAddress(), netPort,
        locProps);
    serverProps.clear();
    serverProps.setProperty("locators", localHost.getHostName() + '[' + locPort
        + ']');
    restartServerVMNums(new int[] { 1, 2, 3 }, 0, null, serverProps);
    startNetworkServer(1, null, null);
    startNetworkServer(3, null, null);

    GfxdJarInstallationTest.udtTypeTest(netConn, false, false, false, true,
        true);

    // keep the expectedClassLoaderEx check the last since once loaded
    // class will remain until the ClassLoader is GCed (test tries to do
    //   explicit GC etc. to force that but there is no guarantee)
    GfxdJarInstallationTest
        .udtTypeTest(netConn, true, false, true, true, false);
    GfxdJarInstallationTest
        .udtTypeTest(netConn, false, true, true, true, false);

    stopVMNums(-1, -2, -3);
    fabapi.stop(null);
  }

  public void testBug47783_47824() throws Exception {
    Properties props = new Properties();
    // don't skip SPS pre-compilation for this test since it tests for
    // deadlock which is fixed precisely by SPS precompilation (#47824)
    props.setProperty("SKIP_SPS_PRECOMPILE", "false");
    startClientVMs(1, 0, null, props);
    startServerVMs(2, 0, "DBSYNC", props);
    final int netPort1 = startNetworkServer(2, null, props);

    // another server to sync data
    props.setProperty("mcast-port", "0");
    startServerVMs(1, 0, null, props);
    int netPort = startNetworkServer(3, null, props);

    Connection conn = TestUtil.getConnection();
    Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();
    Statement netStmt = netConn.createStatement();
    stmt.execute("call sqlj.install_jar('" + dbsyncjar + "', 'app.dbsync', 0)");

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, CB CLOB, "
        + "DT BLOB, PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    netStmt.execute(ddl);

    // create a custom DBSynchronizer
    stmt.execute("create asynceventlistener SECTSYNC("
        + "listenerclass 'com.jpmorgan.tss.securitas.strategic.logsynctable."
        + "db.gemfirexd.callbacks.SectDBSynchronizer' initparams "
        + "'com.pivotal.gemfirexd.jdbc.ClientDriver,jdbc:gemfirexd://localhost:"
        + netPort + "' ENABLEPERSISTENCE true MANUALSTART false "
        + "ALERTTHRESHOLD 5000) SERVER GROUPS(DBSYNC)");

    stmt.execute("ALTER TABLE EMP.PARTITIONTESTTABLE SET ASYNCEVENTLISTENER("
        + "SECTSYNC)");

    stmt.execute("call sqlj.install_jar('" + myjar + "', 'app.sample2', 0)");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3', "
        + "'CLOB1', X'0a0a0a')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3', "
        + "'CLOB2', X'0b0b0b')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4', "
        + "'CLOB3', X'0c0c0c')");

    final Object[][] expectedOutput = new Object[][] {
        new Object[] { 2, 2, "4", "CLOB3", "0c0c0c" },
        new Object[] { 3, 3, "3", "CLOB2", "0b0b0b" },
        new Object[] { 2, 2, "3", "CLOB1", "0a0a0a" } };

    // try concurrent reads with CLOB columns that will lead to recompile of SPS
    // leading to hangs (#45609, #47336)
    final int numThreads = 20;
    Thread[] threads = new Thread[numThreads];
    final Exception[] ex = new Exception[1];
    final CyclicBarrier barrier = new CyclicBarrier(numThreads);
    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(new Runnable() {
        final Connection netConn = TestUtil.getNetConnection(netPort1, null,
            null);
        final PreparedStatement pstmt = netConn
            .prepareStatement("select * from EMP.PARTITIONTESTTABLE");

        @Override
        public void run() {
          try {
            ResultSet rs = pstmt.executeQuery();
            barrier.await();
            JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
            rs.close();
          } catch (Exception e) {
            ex[0] = e;
          } finally {
            try {
              pstmt.close();
            } catch (Exception ignored) {
              // ignored
            }
            try {
              netConn.commit();
              netConn.close();
            } catch (Exception ignored) {
              // ignored
            }
          }
        }
      });
    }
    for (Thread thr : threads) {
      thr.start();
    }
    for (Thread thr : threads) {
      thr.join();
    }
    if (ex[0] != null) {
      throw ex[0];
    }

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR "
        + "'myexamples.MergeSortProcessor'");

    String sql = "CALL MergeSort() "
        + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sql);
    cs.execute();

    cs.getResultSet();

    // check for data on other side
    stmt.execute("call sys.WAIT_FOR_SENDER_QUEUE_FLUSH('SECTSYNC', 1, 0)");
    ResultSet rs = netStmt.executeQuery("select * from EMP.PARTITIONTESTTABLE");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs.close();

    stmt.execute("ALTER TABLE EMP.PARTITIONTESTTABLE SET ASYNCEVENTLISTENER()");
    stmt.execute("drop ASYNCEVENTLISTENER sectsync");

    stmt.execute("call sqlj.remove_jar('app.sample2', 0)");
    stmt.execute("call sqlj.remove_jar('app.dbsync', 0)");
  }

  public void testBug47863() throws Exception {
    startClientVMs(1, 0, null, null);
    startServerVMs(2, 0, "DBSYNC", null);

    // another server to sync data
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    startServerVMs(1, 0, null, props);
    int netPort = startNetworkServer(3, null, props);

    props.setProperty("user", "testuser");
    props.setProperty("password", "testpass");
    Connection conn = TestUtil.getConnection(props);
    Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();
    Statement netStmt = netConn.createStatement();
    stmt.execute("create schema testsch");
    stmt.execute("call sqlj.install_jar('" + dbsyncjar
        + "', 'testsch.dbsync', 0)");

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, CB CLOB, "
        + "DT BLOB, PRIMARY KEY (SECONDID, THIRDID)) "
        + "PARTITION BY COLUMN (ID) PERSISTENT";

    stmt.execute(ddl);

    // create a custom DBSynchronizer
    stmt.execute("create asynceventlistener SECTSYNC("
        + "listenerclass 'com.jpmorgan.tss.securitas.strategic.logsynctable."
        + "db.gemfirexd.callbacks.SectDBSynchronizer' initparams "
        + "'com.pivotal.gemfirexd.jdbc.ClientDriver,jdbc:gemfirexd://localhost:" +
        + netPort + "' ENABLEPERSISTENCE true MANUALSTART false "
        + "ALERTTHRESHOLD 5000) SERVER GROUPS(DBSYNC)");

    stmt.execute("ALTER TABLE EMP.PARTITIONTESTTABLE SET ASYNCEVENTLISTENER("
        + "SECTSYNC)");

    stmt.execute("call sqlj.install_jar('" + myjar + "', 'sample2', 0)");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3', "
        + "'CLOB1', X'0a0a0a')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3', "
        + "'CLOB2', X'0b0b0b')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4', "
        + "'CLOB3', X'0c0c0c')");

    Object[][] expectedOutput = new Object[][] {
        new Object[] { 2, 2, "4", "CLOB3", "0c0c0c" },
        new Object[] { 3, 3, "3", "CLOB2", "0b0b0b" },
        new Object[] { 2, 2, "3", "CLOB1", "0a0a0a" } };

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR "
        + "'myexamples.MergeSortProcessor'");

    String sortSQL = "CALL MergeSort() "
        + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sortSQL);
    cs.execute();
    cs.getResultSet();

    // check for data on other side
    stmt.execute("call sys.WAIT_FOR_SENDER_QUEUE_FLUSH('SECTSYNC', 1, 0)");
    ResultSet rs = netStmt.executeQuery("select * from EMP.PARTITIONTESTTABLE");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs.close();

    // restart the servers and try again
    stopVMNums(-1, -2);
    restartServerVMNums(new int[] { 1, 2}, 0, "DBSYNC", null);

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (4, 4, '5', "
        + "'CLOB4', X'0d0d0d')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (5, 3, '5', "
        + "'CLOB5', X'0e0e0e')");

    expectedOutput = new Object[][] {
        new Object[] { 4, 4, "5", "CLOB4", "0d0d0d" },
        new Object[] { 3, 3, "3", "CLOB2", "0b0b0b" },
        new Object[] { 2, 2, "3", "CLOB1", "0a0a0a" },
        new Object[] { 5, 3, "5", "CLOB5", "0e0e0e" },
        new Object[] { 2, 2, "4", "CLOB3", "0c0c0c" }, };

    cs = conn.prepareCall(sortSQL);
    cs.execute();
    cs.getResultSet();

    // check for data on other side
    stmt.execute("call sys.WAIT_FOR_SENDER_QUEUE_FLUSH('SECTSYNC', 1, 0)");
    rs = netStmt.executeQuery("select * from EMP.PARTITIONTESTTABLE");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs.close();

    stmt.execute("ALTER TABLE EMP.PARTITIONTESTTABLE SET ASYNCEVENTLISTENER()");
    stmt.execute("drop ASYNCEVENTLISTENER sectsync");

    stmt.execute("call sqlj.remove_jar('testuser.sample2', 0)");
    stmt.execute("call sqlj.remove_jar('testsch.dbsync', 0)");
  }

  private final String myAsyncJar = TestUtil.getResourcesDir()
      + "/lib/myAsyncJar.jar";

  private final String myNewAsyncJar = TestUtil.getResourcesDir()
      + "/lib/myNewAsyncJar.jar";

  public void testAsyncListenerInvalidationOnJarReplaceRemove()
      throws Exception {
    startClientVMs(1, 0, null, null);
    startServerVMs(2, 0, "ASYNC", null);
    startServerVMs(1, 0, null, null);
    Connection conn = TestUtil.getConnection();

    Statement stmt = conn.createStatement();
    stmt.execute("create schema EMP");
    stmt.execute("call sqlj.install_jar('" + myAsyncJar + "', 'EMP.ASYNC', 0)");

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (ID)) "
        + "PARTITION BY COLUMN (ID) ";

    stmt.execute(ddl);

    stmt.execute("create asynceventlistener ASYNCLIS("
        + "listenerclass 'jarSource.AsyncListener' initparams ''"
        + " ENABLEPERSISTENCE true MANUALSTART false "
        + "ALERTTHRESHOLD 5000) SERVER GROUPS(ASYNC)");

    stmt.execute("ALTER TABLE EMP.PARTITIONTESTTABLE SET ASYNCEVENTLISTENER("
        + "ASYNCLIS)");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (4, 2, '4')");

    VM firstvm = getServerVM(1);
    VM secondvm = getServerVM(2);
    Boolean ret = (Boolean)firstvm.invoke(GfxdJarInstallationDUnit.class,
        "verifyOldEventsProcessed");
    if (!ret) {
      ret = (Boolean)secondvm.invoke(GfxdJarInstallationDUnit.class,
          "verifyOldEventsProcessed");
      assertTrue(ret);
    }

    stmt.execute("call sqlj.replace_jar('" + myNewAsyncJar + "', 'EMP.ASYNC')");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (10, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (11, 3, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (12, 2, '4')");

    ret = (Boolean)firstvm.invoke(GfxdJarInstallationDUnit.class,
        "verifyNewEventsProcessed");
    if (!ret) {
      ret = (Boolean)secondvm.invoke(GfxdJarInstallationDUnit.class,
          "verifyNewEventsProcessed");
      assertTrue(ret);
    }

    try {
      stmt.execute("call sqlj.remove_jar('EMP.ASYNC', 0)");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("X0Y25"));
    }
  }

  public static Boolean verifyOldEventsProcessed() {
    GfxdGatewayEventListener lis = (GfxdGatewayEventListener)Misc
        .getGemFireCache().getAsyncEventQueue("ASYNCLIS")
        .getAsyncEventListener();
    com.pivotal.gemfirexd.callbacks.AsyncEventListener alis = lis
        .getAsyncEventListenerForTest();
    Class<?> c = alis.getClass();
    try {
      Method exceptionGetter = c.getMethod("getThrowable");
      Object exc = exceptionGetter.invoke(alis);
      assertNull(exc);
      Misc.getCacheLogWriter().info("listener is " + alis);
      assertTrue(alis.toString().equals("OLD"));
      return true;
    } catch (Throwable t) {
      Misc.getCacheLogWriter().info("got exception 0", t);
      return false;
    }
  }

  public static Boolean verifyNewEventsProcessed() {
    GfxdGatewayEventListener lis = (GfxdGatewayEventListener)Misc
        .getGemFireCache().getAsyncEventQueue("ASYNCLIS")
        .getAsyncEventListener();
    com.pivotal.gemfirexd.callbacks.AsyncEventListener alis = lis
        .getAsyncEventListenerForTest();
    Class<?> c = alis.getClass();
    try {
      Method exceptionGetter = c.getMethod("getThrowable");
      Object exc = exceptionGetter.invoke(alis);
      assertNull(exc);
      Misc.getCacheLogWriter().info("listener is " + alis);
      assertTrue(alis.toString().equals("NEW"));
      return true;
    } catch (Throwable t) {
      Misc.getCacheLogWriter().info("got exception 1", t);
      return false;
    }
  }

  public static Boolean verifyNoListenerAttachedToTable() {
    GfxdGatewayEventListener lis = (GfxdGatewayEventListener)Misc
        .getGemFireCache().getAsyncEventQueue("ASYNCLIS")
        .getAsyncEventListener();
    com.pivotal.gemfirexd.callbacks.AsyncEventListener alis = lis
        .getAsyncEventListenerForTest();
    try {
      assertNull(alis);
      return true;
    } catch (Throwable t) {
      Misc.getCacheLogWriter().info("got exception 2", t);
      return false;
    }
  }
}
