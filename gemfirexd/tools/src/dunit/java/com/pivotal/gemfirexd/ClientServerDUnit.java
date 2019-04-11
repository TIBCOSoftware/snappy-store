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

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.client.am.DisconnectException;
import com.pivotal.gemfirexd.internal.client.net.NetAgent;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;
import io.snappydata.app.TestThrift;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import io.snappydata.thrift.internal.ClientConnection;
import org.apache.derby.drda.NetworkServerControl;

/**
 * Test that client and server are being correctly configured with different
 * combinations of the "gemfirexd.jdbc.client" property.
 * 
 * @author swale
 * @since 6.0
 */
@SuppressWarnings("serial")
public class ClientServerDUnit extends ClientServerTestBase {

  public ClientServerDUnit(String name) {
    super(name);
  }

  // -----------------------  Tests in this class start below

  // Check that the client and server are correctly setup in default case of
  // starting servers first and then the client
  public void testDefaultClient() throws Exception {
    // Start a couple of servers
    TestUtil.deletePersistentFiles = true;
    AsyncVM async1 = invokeStartServerVM(1, 0, null, null);
    AsyncVM async2 = invokeStartServerVM(2, 0, null, null);
    // Use this VM as the client
    TestUtil.loadDriver();
    Properties props = new Properties();
    super.setCommonProperties(props, 0, null, null);
    Connection conn = null;

    props.setProperty("host-data", "false");
    conn = DriverManager.getConnection(TestUtil.getProtocol(), props);

    // wait for servers to finish startup
    joinVMs(true, async1, async2);

    Statement stmt = conn.createStatement();

    // Some DB meta-data checks
    checkDBMetadata(conn, TestUtil.getProtocol());

    // Create a table
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");
    checkTestTableProperties(null);

    // check again with non-GemFire role property
    TestUtil.shutDown();
    TestUtil.loadDriver();
    props = new Properties();
    super.setCommonProperties(props, 0, null, null);
    props.setProperty("host-data", "false");
    conn = DriverManager.getConnection(TestUtil.getProtocol(), props);
    stmt = conn.createStatement();
    // Create a table
    stmt.execute("drop table TESTTABLE");
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");
    checkTestTableProperties(null);

    // now check that with no property set this is a datastore
    TestUtil.shutDown();
    TestUtil.loadDriver();
    props = new Properties();
    super.setCommonProperties(props, 0, null, null);
    conn = DriverManager.getConnection(TestUtil.getProtocol(), props);
    stmt = conn.createStatement();
    // Create a table
    stmt.execute("drop table TESTTABLE");
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");
    checkTestTableProperties(null, true);
  }

  // Check that the client and server are correctly setup when starting the
  // client first with "host-data" specifically set
  public void testNondefaultClient() throws Exception {
    // Use this VM as the client
    Properties props = new Properties();
    props.setProperty("host-data", "false");
    setCommonProperties(props, 0, null, null);
    TestUtil.loadDriver();
    TestUtil.setupConnection(props);

    // Start the server
    startServerVMs(1, 0, null);

    // We also check if replay of initial DDL works correctly

    // Create a schema
    TestUtil.sqlExecute("create schema EMP",
        Boolean.TRUE /* use prep statement */);

    // Create a set of expected region attributes for the schema
    RegionAttributesCreation expectedSchemaAttrs = new RegionAttributesCreation();
    expectedSchemaAttrs.setScope(Scope.DISTRIBUTED_NO_ACK);
    expectedSchemaAttrs.setDataPolicy(DataPolicy.EMPTY);
    expectedSchemaAttrs.setConcurrencyChecksEnabled(false);
    expectedSchemaAttrs.setAllHasFields(true);
    expectedSchemaAttrs.setHasDiskDirs(false);
    expectedSchemaAttrs.setHasDiskWriteAttributes(false);
    // Test the schema region attributes on client and first server
    TestUtil.verifyRegionProperties("EMP", null, TestUtil
        .regionAttributesToXML(expectedSchemaAttrs));
    serverVerifyRegionProperties(1, "EMP", null, expectedSchemaAttrs);

    // Create a table in the schema
    TestUtil.sqlExecute("create table EMP.TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null primary key)",
        Boolean.TRUE /* use prep statement */);

    // Create a set of expected region attributes for the table
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    PartitionAttributes<?, ?> pa = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    expectedAttrs.setPartitionAttributes(pa);
    expectedAttrs.setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Check the table attributes on the first server
    serverVerifyRegionProperties(1, "EMP", "TESTTABLE", expectedAttrs);

    // Start the second server
    startServerVMs(1, 0, null);

    // Check the schema attributes on the second server
    serverVerifyRegionProperties(2, "EMP", null, expectedSchemaAttrs);
    // Check the table attributes on the second server
    serverVerifyRegionProperties(2, "EMP", "TESTTABLE", expectedAttrs);

    // Check that the local-max-memory PR attribute is zero on the client
    pa = new PartitionAttributesFactory<Object, Object>(expectedAttrs
        .getPartitionAttributes()).setLocalMaxMemory(0).create();
    expectedAttrs.setPartitionAttributes(pa);
    TestUtil.verifyRegionProperties("EMP", "TESTTABLE", TestUtil
        .regionAttributesToXML(expectedAttrs));

    // now try putting a unicode string (#41673)
    final Connection conn = TestUtil.jdbcConn;
    PreparedStatement pstmt = conn
        .prepareStatement("insert into EMP.TESTTABLE values(?,?)");

    final String s = "test\u0905";
    pstmt.setInt(1, 1);
    pstmt.setString(2, s);
    pstmt.execute();
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));

    ResultSet rs = conn.createStatement().executeQuery(
        "select * from EMP.TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    final String resultStr = rs.getString(2);
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());
  }

  /** check Gemfire properties passed in the URL string; see bug #40155 */
  public void testURLGemfireProperties() throws Exception {
    TestUtil.deletePersistentFiles = true;
    Properties props = new Properties();
    setOtherCommonProperties(props,0, null);
    TestUtil.loadDriver();
    Connection conn = DriverManager.getConnection(TestUtil.getProtocol()
        + ";mcast-port=0;host-data=true;locators=" + getLocatorString());

    // check the Gemfire properties
    DistributedSystem sys = InternalDistributedSystem.getConnectedInstance();
    Properties sysProps = sys.getProperties();
    assertEquals("Expected mcast-port to be 0", "0", sysProps
        .getProperty("mcast-port"));
    assertEquals("Unexpected value for locators", getLocatorString().replace(
        '@', ':'), sysProps.getProperty("locators"));
    assertTrue("Expected host-data to be true", ServerGroupUtils.isDataStore());

    Statement stmt = conn.createStatement();
    // Create a table
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    // Create a set of expected region attributes for the table
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    /*
    PartitionAttributes<?, ?> pa = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    expectedAttrs.setPartitionAttributes(pa);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    */
    expectedAttrs.setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Check the table attributes on the VM; it should have data like a server
    TestUtil.verifyRegionProperties(SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME,
        "TESTTABLE", TestUtil.regionAttributesToXML(expectedAttrs));

    // Drop the table
    stmt.execute("drop table TESTTABLE");
  }

  /** check Gemfire properties passed in the connection properties [#40155] */
  public void testConnGemfireProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", getLocatorString());
    Properties props1 = new Properties();
    setOtherCommonProperties(props1,0, null);
    TestUtil.deletePersistentFiles = true;
    TestUtil.loadDriver();
    Connection conn = DriverManager
        .getConnection(TestUtil.getProtocol(), props);

    // check the Gemfire properties
    DistributedSystem sys = InternalDistributedSystem.getConnectedInstance();
    Properties sysProps = sys.getProperties();
    assertEquals("Expected mcast-port to be 0", "0", sysProps
        .getProperty("mcast-port"));
    assertEquals("Unexpected value for locators", getLocatorString().replace(
        '@', ':'), sysProps.getProperty("locators"));
    assertTrue("Expected host-data to be true", ServerGroupUtils.isDataStore());

    Statement stmt = conn.createStatement();
    // Create a table
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    // Create a set of expected region attributes for the table
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    /*
    PartitionAttributes<?, ?> pa = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    expectedAttrs.setPartitionAttributes(pa);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    */
    expectedAttrs.setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Check the table attributes on the VM; it should have data like a server
    TestUtil.verifyRegionProperties(SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME,
        "TESTTABLE", TestUtil.regionAttributesToXML(expectedAttrs));

    // Drop the table
    stmt.execute("drop table TESTTABLE");
  }

  private static volatile HashMap<String, ArrayList<Integer>> batchExecutions =
      new HashMap<String, ArrayList<Integer>>();
  private static volatile boolean hasQueryObservers = false;
  private static volatile int preparedExec = 0;
  private static volatile int unpreparedExec = 0;

  public void testNetworkClient() throws Exception {
    networkClientTests(null, null);
  }

  private int[] networkClientTests(Properties extraProps,
      Properties extraConnProps) throws Exception {
    // start a couple of servers
    startVMs(0, 2);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, extraProps);
    final int netPort2 = startNetworkServer(2, null, extraProps);
    final VM serverVM = this.serverVMs.get(0);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    Connection conn, conn2;
    final Properties connProps = new Properties();
    connProps.setProperty("load-balance", "false");
    if (extraConnProps != null) {
      connProps.putAll(extraConnProps);
    }
    final InetAddress localHost = InetAddress.getByName("localhost");
    String url = TestUtil.getNetProtocol(localHost.getHostName(), netPort);
    conn = DriverManager.getConnection(url, connProps);
    conn2 = DriverManager.getConnection(
        TestUtil.getNetProtocol(localHost.getHostName(), netPort2), connProps);

    // Some sanity checks for DB meta-data
    String expectedUrl = TestUtil.getNetProtocol(localHost.getHostName(),
        netPort);
    checkDBMetadata(conn, expectedUrl);

    PreparedStatement pstmt;
    CallableStatement cstmt;
    ResultSet rs;
    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    final int numOps = 20;
    final String schemaName = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    final GemFireXDQueryObserver batchExec = new GemFireXDQueryObserverAdapter() {
      @Override
      public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
          throws SQLException {
        ArrayList<Integer> executions = batchExecutions.get(stmt.getSQLText());
        if (executions == null) {
          executions = new ArrayList<Integer>();
          batchExecutions.put(stmt.getSQLText(), executions);
        }
        executions.add(batchSize);
      }
    };
    final GemFireXDQueryObserver queryExec = new GemFireXDQueryObserverAdapter() {
      @Override
      public boolean afterQueryExecution(CallbackStatement stmt, SQLException sqle) {
        if (stmt.isPrepared()) {
          preparedExec++;
        }
        else if (sqle == null) {
          unpreparedExec++;
        }
        return false;
      }
    };
    final String indexName = "INDEX10_TEST";
    final GemFireXDQueryObserver errorGen = new GemFireXDQueryObserverAdapter() {
      private Integer batchElement;

      @Override
      public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
          throws SQLException {
        this.batchElement = null;
        // try throwing conflict exception to reproduce #44240
        if (!stmt.getSQLText().startsWith("call SYSIBM.SQLCAMESSAGE")
            && !stmt.getSQLText().startsWith("SET ")) {
          // try on a random batch element
          if (PartitionedRegion.rand.nextBoolean()) {
            throw PublicAPI.wrapStandardException(StandardException
                .newException("X0Z02", new Throwable(
                    "simulating a conflict exception")));
          }
          else {
            this.batchElement = Integer
                .valueOf(PartitionedRegion.rand.nextInt(numOps - 5) + 1);
          }
        }
      }

      @Override
      public void beforeQueryExecution(EmbedStatement stmt,
          Activation activation) throws SQLException {
        // don't throw exception when client tries to get the exception message
        // itself, else it goes into a frenzy ...
        if (!stmt.getSQLText().startsWith("call SYSIBM.SQLCAMESSAGE")
            && !stmt.getSQLText().startsWith("SET ")) {
          if (this.batchElement != null) {
            if (this.batchElement.intValue() > 0) {
              this.batchElement--;
            }
            else {
              throw PublicAPI.wrapStandardException(StandardException
                  .newException("X0Z02", new Throwable(
                      "simulating a conflict exception")));
            }
          }
          else {
            throw PublicAPI.wrapStandardException(StandardException
                .newException("42X65", indexName));
          }
        }
      }
    };

    final SerializableRunnable checkAttrs = new SerializableRunnable(
        "check region attributes") {
      @Override
      public void run() {
        final RegionAttributesCreation attrs = getServerTestTableProperties();
        TestUtil.verifyRegionProperties(schemaName, "TESTTABLE",
            TestUtil.regionAttributesToXML(attrs));
        // also attach batch and query execution observers
        if (hasQueryObservers) {
          preparedExec = 0;
          unpreparedExec = 0;
        }
        else {
          GemFireXDQueryObserverHolder.putInstance(batchExec);
          GemFireXDQueryObserverHolder.putInstance(queryExec);
          hasQueryObservers = true;
        }
      }
    };
    serverVM.invoke(checkAttrs);

    // test batch insert execution
    pstmt = conn
        .prepareStatement("insert into testtable values(?, ?)");
    for (int id = 1; id <= numOps; id++) {
      pstmt.setInt(1, id);
      pstmt.setString(2, "test" + id);
      pstmt.addBatch();
    }
    int[] results = pstmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }

    // check batch and prepared statement execution on server
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(1, batchExecutions.size());
        ArrayList<Integer> batchExecs = batchExecutions
            .get("insert into testtable values(?, ?)");
        assertNotNull(batchExecs);
        assertEquals(1, batchExecs.size());
        assertEquals(numOps, batchExecs.get(0).intValue());
        batchExecutions.clear();

        assertEquals(numOps, preparedExec);
        assertEquals(0, unpreparedExec);
        preparedExec = 0;
      }
    });

    // check behaviour of unprepared statements with exceptions after the
    // fix for #44208
    try {
      // check for statements that will fail in "prepare"
      rs = stmt.executeQuery("select * from testtab order by id");
      fail("expected exception for non-existent table");
    } catch (SQLException sqle) {
      if (!"42X05".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      rs = stmt.executeQuery("select * from testtable order by id2");
      fail("expected exception for non-existent table");
    } catch (SQLException sqle) {
      if (!"42X04".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
        }
        else {
          // prepared executions for the two error messages
          assertEquals(2, preparedExec);
        }
        assertEquals(0, unpreparedExec);
        preparedExec = 0;
      }
    });

    // check with select
    rs = stmt.executeQuery("select * from testtable order by id");
    for (int id = 1; id <= numOps; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      assertEquals("test" + id, rs.getString(2));
    }
    assertFalse(rs.next());
    // no batch executions for unprepared statements yet
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, preparedExec);
        // every unprepared query will execute 2 times, one as the
        // top-level query and other as looped back for real execution
        assertEquals(2, unpreparedExec);
        unpreparedExec = 0;
      }
    });

    // now with statement batch update execution
    for (int id = 1; id <= numOps; id++) {
      stmt.addBatch("update testtable set description='newtest" + id
          + "' where id=" + id);
    }
    results = stmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(1, batchExecutions.size());
          ArrayList<Integer> batchExecs = batchExecutions
              .get(null /* no string for unprepared execs */);
          assertNotNull(batchExecs);
          assertEquals(1, batchExecs.size());
          assertEquals(numOps, batchExecs.get(0).intValue());
          batchExecutions.clear();
        }
        else {
          // no batch executions for unprepared statements yet
          assertEquals(0, batchExecutions.size());
        }

        // every unprepared query will execute 2 times, one as the
        // top-level query and other as looped back for real execution
        assertEquals(0, preparedExec);
        assertEquals(numOps * 2, unpreparedExec);
        unpreparedExec = 0;
      }
    });

    // check with select
    rs = stmt.executeQuery("select * from testtable order by id");
    for (int id = 1; id <= numOps; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      assertEquals("newtest" + id, rs.getString(2));
    }
    assertFalse(rs.next());    
    rs = stmt.executeQuery("select * from testtable where id > 10 "
        + "order by id");
    for (int id = 11; id <= numOps; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      assertEquals("newtest" + id, rs.getString(2));
    }
    assertFalse(rs.next());
    // check for unprepared executions
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, preparedExec);
        assertEquals(4, unpreparedExec);
        unpreparedExec = 0;
      }
    });

    // drop the table and close the connection
    stmt.execute("drop table TESTTABLE");
    stmt.close();
    conn.close();

    // Also check for failure giving a dummy database name in URL
    // ODBC driver does not allow for passing database name
    if (!TestUtil.USE_ODBC_BRIDGE) {
      url = TestUtil.getNetProtocol(localHost.getHostName(), netPort) + "test";
      try {
        DriverManager.getConnection(url);
      } catch (SQLException ex) {
        if (!"XJ028".equals(ex.getSQLState())
            && !"XJ212".equals(ex.getSQLState())) {
          throw ex;
        }
      }
    }

    // Check that trailing slash is not required in the URL
    url = "jdbc:gemfirexd://" + localHost.getHostName() + ':' + netPort;
    conn = DriverManager.getConnection(url, connProps);

    // Some sanity checks for DB meta-data
    expectedUrl = TestUtil.getNetProtocol(localHost.getHostName(), netPort);
    checkDBMetadata(conn, expectedUrl);

    // Create a table
    stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    serverVM.invoke(checkAttrs);

    // now try putting a unicode string (#41673)
    pstmt = conn.prepareStatement("insert into TESTTABLE values(?,?)");

    final String s = "test\u0905";
    pstmt.setInt(1, 1);
    pstmt.setString(2, s);
    pstmt.execute();

    pstmt.setInt(1, 2);
    pstmt.setString(2, s);
    pstmt.execute();

    rs = conn.createStatement().executeQuery("select * from TESTTABLE");
    assertTrue(rs.next());
    String resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertTrue(rs.next());
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());
    // check for unprepared executions
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(2, preparedExec);
        assertEquals(2, unpreparedExec);
        preparedExec = 0;
        unpreparedExec = 0;
      }
    });

    stmt.execute("delete from testtable where 1=1");

    // check for proper exception string on the client for an exception
    // that happens on second hop from this client
    // insert some data first
    for (int id = 1; id <= numOps; ++id) {
      pstmt.setInt(1, id);
      pstmt.setString(2, "test" + id);
      pstmt.execute();
    }
    // check for unprepared executions
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(numOps, preparedExec);
        assertEquals(2, unpreparedExec);
        preparedExec = 0;
        unpreparedExec = 0;
      }
    });
    // attach an observer that will always throw an index not found exception
    serverExecute(2, new SerializableRunnable("attach observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder.setInstance(errorGen);
      }
    });
    // first a DML execution
    final String expectedMessage = PublicAPI.wrapStandardException(
        StandardException.newException("42X65", indexName))
        .getLocalizedMessage();
    getLogWriter().info("Expected exception message: " + expectedMessage);
    addExpectedException(null, new int[] { 1, 2 }, new Object[] {
        SQLException.class, "java.sql.SQLSyntaxErrorException", "42Y03",
        "42X65", "X0Z02" });
    try {
      stmt.execute("select * from TESTTABLE");
      fail("expected an SQLException due to the observer");
    } catch (SQLException ex) {
      if (!"42X65".equals(ex.getSQLState())) {
        throw ex;
      }
      // compare the localized strings
      assertTrue(ex.getLocalizedMessage(),
          ex.getLocalizedMessage().contains(expectedMessage));
    }
    // then a DDL execution
    try {
      stmt.execute("create index " + indexName + " on TESTTABLE (ID)");
      fail("expected an SQLException due to the observer");
    } catch (SQLException ex) {
      if (!"42X65".equals(ex.getSQLState())) {
        throw ex;
      }
      // compare the localized strings
      assertTrue(ex.getLocalizedMessage(),
          ex.getLocalizedMessage().contains(expectedMessage));
    }
    // remove the observer
    serverExecute(2, new SerializableRunnable("remove observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder.clearInstance();
      }
    });

    // try invoking a non-existing procedure which should throw a proper
    // exception
    stmt.execute("create procedure proc.test(DP1 Integer) PARAMETER STYLE JAVA"
        + " LANGUAGE JAVA EXTERNAL NAME '" + this.getClass().getName()
        + ".procTest(java.lang.Integer)'");
    cstmt = conn.prepareCall("call proc.test(?)");
    cstmt.setInt(1, 1);
    cstmt.execute();
    stmt.execute("drop procedure proc.test");
    try {
      cstmt = conn.prepareCall("call proc.test(?)");
      cstmt.setInt(1, 10);
      cstmt.execute();
    } catch (SQLException ex) {
      if (!"42Y03".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    stmt.execute("create procedure proc.test(DP1 Integer) PARAMETER STYLE JAVA"
        + " LANGUAGE JAVA EXTERNAL NAME '" + this.getClass().getName()
        + ".procTest(java.lang.Integer)'");

    // test batch update execution
    pstmt = conn
        .prepareStatement("update testtable set description=? where id=?");
    for (int id = 1; id <= numOps; id++) {
      pstmt.setString(1, "newtest" + id);
      pstmt.setInt(2, id);
      pstmt.addBatch();
    }
    results = pstmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }
    // check batch execution on server
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(1, batchExecutions.size());
        ArrayList<Integer> batchExecs = batchExecutions
            .get("update testtable set description=? where id=?");
        assertNotNull(batchExecs);
        assertEquals(1, batchExecs.size());
        assertEquals(numOps, batchExecs.get(0).intValue());
        batchExecutions.clear();
      }
    });

    // check with select
    rs = stmt.executeQuery("select * from testtable order by id");
    for (int id = 1; id <= numOps; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      assertEquals("newtest" + id, rs.getString(2));
    }
    assertFalse(rs.next());

    // now with statement batch update execution
    for (int id = 1; id <= numOps; id++) {
      stmt.addBatch("update testtable set description='new2test" + id
          + "' where id=" + id);
    }
    results = stmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(1, batchExecutions.size());
          ArrayList<Integer> batchExecs = batchExecutions
              .get(null /* no string for unprepared execs */);
          assertNotNull(batchExecs);
          assertEquals(1, batchExecs.size());
          assertEquals(numOps, batchExecs.get(0).intValue());
          batchExecutions.clear();
        }
        else {
          // no batch executions for unprepared statements yet
          assertEquals(0, batchExecutions.size());
        }

        preparedExec = 0;
        unpreparedExec = 0;
      }
    });

    // check behaviour of unprepared statements with exceptions after the
    // fix for #44208
    try {
      // check for statements that will fail in "prepare"
      rs = stmt.executeQuery("select * from testtab order by id");
      fail("expected exception for non-existent table");
    } catch (SQLException sqle) {
      if (!"42X05".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      rs = stmt.executeQuery("select * from testtable order by id2");
      fail("expected exception for non-existent table");
    } catch (SQLException sqle) {
      if (!"42X04".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
        }
        else {
          // prepared executions for the two error messages
          assertEquals(2, preparedExec);
        }
        assertEquals(0, unpreparedExec);
        preparedExec = 0;
      }
    });

    // now check behaviour in exception during execution using an observer
    serverExecute(1, new SerializableRunnable("attach error generator") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder.setInstance(errorGen);
      }
    });
    try {
      rs = stmt.executeQuery("select * from testtable order by id");
      fail("expected exception in query due to observer");
    } catch (SQLException sqle) {
      if (!"42X65".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // also check exceptions during batch statement execution
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    pstmt = conn
        .prepareStatement("update testtable set description=? where id=?");
    for (int id = 1; id <= numOps; id++) {
      pstmt.setString(1, "new3test" + id);
      pstmt.setInt(2, id);
      pstmt.addBatch();
    }
    try {
      pstmt.executeBatch();
      fail("expected exception in query due to observer");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    } finally {
      pstmt.clearBatch();
    }

    // exceptions during prepare in batch statements
    try {
      pstmt = conn
          .prepareStatement("update testtable set description2=? where id=?");
      fail("expected syntax error");
    } catch (SQLException sqle) {
      if (!"42X14".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, preparedExec);
        assertEquals(0, unpreparedExec);

        GemFireXDQueryObserverHolder.clearInstance();
        GemFireXDQueryObserverHolder.putInstance(batchExec);
        GemFireXDQueryObserverHolder.putInstance(queryExec);
        hasQueryObservers = true;
      }
    });

    // now for actual conflicts between two batch statements
    conn.commit();
    conn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    final Connection conn11 = conn;
    final Connection conn12 = conn2;
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Throwable[] failed = new Throwable[1];
    final AtomicInteger numConflicts = new AtomicInteger(0);
    final Runnable conflictRun = new Runnable() {
      public void run() {
        try {
          final int tnum = barrier.await();
          final PreparedStatement pstmt;
          if (tnum == 0) {
            pstmt = conn11.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          else {
            pstmt = conn12.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          for (int id = 1; id <= numOps; id++) {
            pstmt.setString(1, "new2test" + id);
            pstmt.setInt(2, id);
            pstmt.addBatch();
          }
          try {
            pstmt.executeBatch();
          } catch (SQLException sqle) {
            if (!"X0Z02".equals(sqle.getSQLState())) {
              throw sqle;
            }
            else {
              numConflicts.incrementAndGet();
            }
          } finally {
            try {
              pstmt.clearBatch();
            } catch (SQLException sqle) {
              // just log a warning here
              getLogWriter().warn(sqle);
            }
          }
        } catch (Throwable t) {
          failed[0] = t;
        }
      }
    };
    Thread t1 = new Thread(conflictRun);
    Thread t2 = new Thread(conflictRun);
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    if (failed[0] != null) {
      fail("unexpected exception " + failed[0], failed[0]);
    }
    assertEquals(1, numConflicts.get());

    conn.commit();
    conn2.commit();
    conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);

    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        batchExecutions.clear();
        preparedExec = 0;
        unpreparedExec = 0;
      }
    });

    stmt.clearBatch();
    stmt.addBatch("insert into testtable values (1000, 'desc1000')");
    stmt.addBatch("insert into testtab values (20, 'desc20')");
    stmt.addBatch("insert into testtable values (2000, 'desc2000')");
    try {
      stmt.executeBatch();
      fail("expected syntax error");
    } catch (SQLException sqle) {
      if (!"42X05".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    stmt.execute("delete from testtable where id > " + numOps);
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
          // three unpreparedExec due to successes in batch statement and delete
          assertEquals(3, unpreparedExec);
          assertEquals(1, batchExecutions.size());
        }
        else {
          // one preparedExec due to exception message
          assertEquals(1, preparedExec);
          // four unpreparedExec due to one success in batch statement and
          // delete and two for each due to local execution also
          assertEquals(4, unpreparedExec);
        }
        preparedExec = 0;
        unpreparedExec = 0;
        batchExecutions.clear();
      }
    });

    conn.commit();
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);

    removeExpectedException(null, new int[] { 1, 2 }, new Object[] {
        SQLException.class, "java.sql.SQLSyntaxErrorException", "42Y03",
        "42X65", "X0Z02" });

    // check with select
    rs = stmt.executeQuery("select * from testtable order by id");
    for (int id = 1; id <= numOps; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      assertEquals("new2test" + id, rs.getString(2));
    }
    assertFalse(rs.next());
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, preparedExec);
        if (ClientSharedUtils.isThriftDefault()) {
          // 2 for the selects
          assertEquals(2, unpreparedExec);
        }
        else {
          // one for setTransactionIsolation and 2 for the select
          assertEquals(3, unpreparedExec);
        }
        unpreparedExec = 0;
      }
    });

    // now with CallableStatement batch execution
    cstmt = conn.prepareCall("call proc.test(?)");
    for (int id = 1; id <= numOps; id++) {
      cstmt.setInt(1, id);
      cstmt.addBatch();
    }
    results = cstmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      if (ClientSharedUtils.isThriftDefault()) {
        assertEquals(0, res);
      }
      else {
        assertEquals(-1, res);
      }
    }
    // check batch execution on server
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(numOps, preparedExec);
        assertEquals(0, unpreparedExec);
        preparedExec = 0;
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(1, batchExecutions.size());
          ArrayList<Integer> batchExecs = batchExecutions
              .get("call proc.test(?)");
          assertNotNull(batchExecs);
          assertEquals(1, batchExecs.size());
          assertEquals(numOps, batchExecs.get(0).intValue());
          batchExecutions.clear();
        }
        else {
          // no batch executions for callablestatements in DRDA (#44209)
          assertEquals(0, batchExecutions.size());
        }
      }
    });

    // next with unprepared call execution
    assertEquals(false, stmt.execute("call proc.test(10)"));

    // check call execution on server
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, batchExecutions.size());
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
          assertEquals(1, unpreparedExec);
        }
        else {
          assertEquals(1, preparedExec);
          assertEquals(0, unpreparedExec);
        }
        unpreparedExec = 0;
      }
    });
    try {
      stmt.execute("call proc.test2(10)");
      fail("expected procedure not found exception");
    } catch (SQLException ex) {
      if (!"42Y03".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    // check call execution on server
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
        }
        else {
          // for the exception
          assertEquals(2, preparedExec);
        }
        assertEquals(0, unpreparedExec);
        preparedExec = 0;
        unpreparedExec = 0;
      }
    });
    // check if stmt is still usable
    assertEquals(false, stmt.execute("call proc.test(20)"));
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        assertEquals(0, batchExecutions.size());
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(0, preparedExec);
          assertEquals(1, unpreparedExec);
        }
        else {
          assertEquals(1, preparedExec);
          assertEquals(0, unpreparedExec);
        }
        unpreparedExec = 0;
      }
    });

    // remove the observer
    serverVM.invoke(new SerializableRunnable("remove observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder.clearInstance();
        batchExecutions.clear();
        hasQueryObservers = false;
        preparedExec = 0;
        unpreparedExec = 0;
      }
    });

    // drop the table and close the connection
    stmt.execute("drop table TESTTABLE");
    stmt.close();
    conn.close();

    return new int[] { netPort, netPort2 };
  }

  public void testThriftFramedProtocol() throws Exception {
    // only if thrift is being used
    if (!ClientSharedUtils.isThriftDefault()) return;
    // first check with server and JDBC client with framed protocol
    Properties props = new Properties();
    props.setProperty(Attribute.THRIFT_USE_FRAMED_TRANSPORT, "true");
    Properties connProps = new Properties();
    connProps.setProperty("framed-transport", "true");
    int[] netPorts = networkClientTests(props, connProps);
    // also check with direct thrift client
    TestThrift.run("localhost", netPorts[1], true);
  }

  public void test44240() throws Exception {
    /*
    System.setProperty("gemfirexd.debug.true", "TraceClientHA");
    System.setProperty("snappydata.client.traceDirectory",
        getSysDirName(getGemFireDescription()));
    */

    // this test generate too many logs, so reduce the log-level
    final Properties props = new Properties();
    props.setProperty("log-level", "severe");
    props.setProperty("gemfirexd.debug.false", "TraceFunctionException");
    // start a couple of servers
    startVMs(0, 2, 0, null, props);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    Connection conn, conn2;
    final Properties connProps = new Properties();
    connProps.setProperty("load-balance", "false");
    String url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url, connProps);
    conn2 = DriverManager.getConnection(
        TestUtil.getNetProtocol("localhost", netPort2),
        TestUtil.getNetProperties(connProps));

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    final int numOps = 20;

    // test batch insert execution
    PreparedStatement pstmt = conn
        .prepareStatement("insert into testtable values(?, ?)");
    for (int id = 1; id <= numOps; id++) {
      pstmt.setInt(1, id);
      pstmt.setString(2, "test" + id);
      pstmt.addBatch();
    }
    int[] results = pstmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }

    // now for actual conflicts between two batch statements
    conn.commit();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    addExpectedException(null, new int[] { 1, 2 }, "Conflict detected");
    final Connection conn11 = conn;
    final Connection conn12 = conn2;
    // run below over and over again to reproduce #44240
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Throwable[] failed = new Throwable[1];
    final AtomicInteger numConflicts = new AtomicInteger(0);
    final int totalTries = 200;
    final Runnable conflictRun = new Runnable() {
      public void run() {
        try {
          final int tnum = barrier.await();
          final PreparedStatement pstmt;
          if (tnum == 0) {
            pstmt = conn11.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          else {
            pstmt = conn12.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          for (int tries = 1; tries <= totalTries; tries++) {
            getLogWriter().info("tries=" + tries);
            barrier.await(30, TimeUnit.SECONDS);
            for (int id = 1; id <= numOps; id++) {
              pstmt.setString(1, "new2test" + id);
              pstmt.setInt(2, id);
              pstmt.addBatch();
            }
            try {
              pstmt.executeBatch();
            } catch (SQLException sqle) {
              if (!"X0Z02".equals(sqle.getSQLState())) {
                throw sqle;
              }
              else {
                numConflicts.incrementAndGet();
              }
            } finally {
              try {
                pstmt.clearBatch();
              } catch (SQLException sqle) {
                getLogWriter().error(sqle);
              }
            }
          }
        } catch (Throwable t) {
          failed[0] = t;
          getLogWriter().error(t);
        }
      }
    };

    Thread t1 = new Thread(conflictRun);
    Thread t2 = new Thread(conflictRun);
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    if (failed[0] != null) {
      fail("unexpected exception " + failed[0], failed[0]);
    }
    assertEquals(totalTries, numConflicts.get());

    removeExpectedException(null, new int[] { 1, 2 }, "Conflict detected");

    conn.commit();
    conn2.commit();
  }

  public void test44240_LOB() throws Exception {
    /*
    System.setProperty("gemfirexd.debug.true", "TraceClientHA");
    System.setProperty("snappydata.client.traceDirectory",
        getSysDirName(getGemFireDescription()));
    */

    // this test generate too many logs, so reduce the log-level
    final Properties props = new Properties();
    props.setProperty("log-level", "severe");
    props.setProperty("gemfirexd.debug.false", "TraceFunctionException");
    // start a couple of servers
    startVMs(0, 2, 0, null, props);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    Connection conn, conn2;
    final Properties connProps = new Properties();
    connProps.setProperty("load-balance", "false");
    String url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connProps));
    conn2 = DriverManager.getConnection(
        TestUtil.getNetProtocol("localhost", netPort2), connProps);

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION clob not null)");

    final int numOps = 20;

    // test batch insert execution
    PreparedStatement pstmt = conn
        .prepareStatement("insert into testtable values(?, ?)");
    for (int id = 1; id <= numOps; id++) {
      pstmt.setInt(1, id);
      pstmt.setString(2, "test" + id);
      pstmt.addBatch();
    }
    int[] results = pstmt.executeBatch();
    assertEquals(numOps, results.length);
    for (int res : results) {
      assertEquals(1, res);
    }

    // now for actual conflicts between two batch statements
    conn.commit();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    addExpectedException(null, new int[] { 1, 2 }, "Conflict detected");
    final Connection conn11 = conn;
    final Connection conn12 = conn2;
    // run below over and over again to reproduce #44240
    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Throwable[] failed = new Throwable[1];
    final AtomicInteger numConflicts = new AtomicInteger(0);
    final int totalTries = 200;
    final Runnable conflictRun = new Runnable() {
      public void run() {
        try {
          final int tnum = barrier.await();
          final PreparedStatement pstmt;
          if (tnum == 0) {
            pstmt = conn11.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          else {
            pstmt = conn12.prepareStatement("update testtable set "
                + "description=? where id=?");
          }
          for (int tries = 1; tries <= totalTries; tries++) {
            getLogWriter().info("tries=" + tries);
            barrier.await(30, TimeUnit.SECONDS);
            for (int id = 1; id <= numOps; id++) {
              pstmt.setString(1, "new2test" + id);
              pstmt.setInt(2, id);
              pstmt.addBatch();
            }
            try {
              pstmt.executeBatch();
            } catch (SQLException sqle) {
              if (!"X0Z02".equals(sqle.getSQLState())) {
                throw sqle;
              }
              else {
                numConflicts.incrementAndGet();
              }
            } finally {
              try {
                pstmt.clearBatch();
              } catch (SQLException sqle) {
                getLogWriter().error(sqle);
              }
            }
          }
        } catch (Throwable t) {
          failed[0] = t;
          getLogWriter().error(t);
        }
      }
    };

    Thread t1 = new Thread(conflictRun);
    Thread t2 = new Thread(conflictRun);
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    if (failed[0] != null) {
      fail("unexpected exception " + failed[0], failed[0]);
    }
    assertEquals(totalTries, numConflicts.get());

    removeExpectedException(null, new int[] { 1, 2 }, "Conflict detected");

    conn.commit();
    conn2.commit();
  }

  /**
   * Test if multiple connections from network clients failover successfully.
   */
  public void testNetworkClientFailover() throws Exception {
    // start some servers
    startVMs(1, 3);
    // Start a network server on locator and data store
    final int netPort = startNetworkServerOnLocator(null, null);
    final int netPort1 = startNetworkServer(1, null, null);

    attachConnectionListener(1, connListener);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    final InetAddress localHost = InetAddress.getByName("localhost");
    String url = TestUtil.getNetProtocol(localHost.getCanonicalHostName(),
        netPort);
    String url1 = TestUtil.getNetProtocol(localHost.getCanonicalHostName(),
        netPort1);
    Connection conn = TestUtil.getNetConnection(
        localHost.getCanonicalHostName(), netPort, null, new Properties());

    // check new connections opened on first server (control+data connections)
    assertNumConnections(-2, -1, 1);
    assertNumConnections(0, 0, 2);

    // Some sanity checks for DB meta-data
    // URL remains the first control connection one for thrift
    checkDBMetadata(conn, url, url1);

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null) redundancy 1");

    // now try putting a unicode string (#41673)
    PreparedStatement pstmt = conn
        .prepareStatement("insert into TESTTABLE values(?,?)");

    final String s = "test\u0905";
    pstmt.setInt(1, 1);
    pstmt.setString(2, s);
    pstmt.execute();

    ResultSet rs = conn.createStatement().executeQuery(
        "select * from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    String resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    assertNumConnections(-2, -1, 1);
    assertNumConnections(0, 0, 2);

    // start another network server
    final int netPort2 = startNetworkServer(2, null, null);
    String url2 = TestUtil.getNetProtocol(localHost.getCanonicalHostName(),
        netPort2);
    attachConnectionListener(2, connListener);

    // now open another connection
    final Connection conn2 = TestUtil.getNetConnection(
        localHost.getCanonicalHostName(), netPort, null, new Properties());

    // check new connection opened on second server
    assertNumConnections(-3, -1, 1);
    assertNumConnections(-1, -1, 2);

    // Some sanity checks for DB meta-data
    // URL remains the first control connection one for thrift
    checkDBMetadata(conn2, url, url1, url2);

    stmt = conn2.createStatement();
    rs = stmt.executeQuery("select * from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    assertNumConnections(-3, -1, 1);
    assertNumConnections(-1, -1, 2);

    // now a third connection
    final Connection conn3 = TestUtil.getNetConnection(
        localHost.getCanonicalHostName(), netPort, null, new Properties());

    // Some sanity checks for DB meta-data
    checkDBMetadata(conn3, url, url1, url2);

    assertNumConnections(-4, -1, 1);
    assertNumConnections(-2, -1, 2);

    // close the first connection
    conn.close();

    // check connection closed on first server
    assertNumConnections(-4, -2, 1);
    assertNumConnections(-2, -1, 2);

    PreparedStatement pstmt3 = conn3
        .prepareStatement("select * from sys.members where kind = ?");
    pstmt3.setString(1, "datastore(normal)");
    rs = pstmt3.executeQuery();
    assertTrue("expected three rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertTrue("expected three rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertTrue("expected three rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertFalse(rs.next());

    // keep one statement prepared with args to check if it works fine
    // after failover
    PreparedStatement pstmt31 = conn3
        .prepareStatement("select * from sys.members where kind = ?");
    pstmt31.setString(1, "datastore(normal)");
    // add expected exception for server connection failure
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        java.net.ConnectException.class,
        "com.pivotal.gemfirexd.internal.impl.drda.DRDAProtocolException" });
    addExpectedException(null, new Object[] { DisconnectException.class,
        SQLNonTransientConnectionException.class });

    // now stop the first server and check for successful failover to second
    stopVMNums(-1);
    pstmt = conn2.prepareStatement("insert into TESTTABLE values(?,?)");

    final String s2 = "test\u0906";
    pstmt.setInt(1, 2);
    pstmt.setString(2, s2);
    pstmt.execute();

    // check connections opened on second server
    assertNumConnections(-4, -2, 1);
    // no failover for conn3 yet since no operation has been performed
    assertNumConnections(-2, -1, 2);

    // check failover for conn3 too
    checkDBMetadata(conn3, url, url1, url2);

    stmt = conn3.createStatement();
    rs = stmt.executeQuery("select * from TESTTABLE where ID = 1");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    rs = pstmt3.executeQuery();
    assertTrue("expected two rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertTrue("expected two rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertFalse(rs.next());

    rs = pstmt31.executeQuery();
    assertTrue("expected two rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertTrue("expected two rows in meta-data query", rs.next());
    assertEquals("datastore(normal)", rs.getString(2));
    getLogWriter().info("Got sysProps=" + rs.getString(13));
    getLogWriter().info("Got gfeProps=" + rs.getString(14));
    getLogWriter().info("Got gfxdProps=" + rs.getString(15));
    assertFalse(rs.next());

    // check connections opened on second server
    assertNumConnections(-4, -2, 1);
    assertNumConnections(-3, -1, 2);

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { java.net.ConnectException.class,
            "com.pivotal.gemfirexd.internal.impl.drda.DRDAProtocolException" });
    removeExpectedException(null, new Object[] { DisconnectException.class,
        SQLNonTransientConnectionException.class });

    // create another connection and run a query to check results
    conn = TestUtil.getNetConnection(
        localHost.getCanonicalHostName(), netPort2, null, new Properties());

    rs = conn.createStatement().executeQuery(
        "select * from TESTTABLE order by ID");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s2.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s2.length(), resultStr.length());
    assertEquals(s2.charAt(s2.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s2, resultStr);
    assertFalse(rs.next());

    // check connection opened on second server
    assertNumConnections(-4, -2, 1);
    assertNumConnections(-4, -1, 2);

    // now drop the table and close the connections
    stmt.execute("drop table TESTTABLE");
    stmt.close();
    conn2.close();

    assertNumConnections(-4, -2, 1);
    assertNumConnections(-4, -2, 2);

    conn.close();

    assertNumConnections(-4, -2, 1);
    assertNumConnections(-4, -3, 2);

    conn3.close();

    assertNumConnections(-4, -2, 1);
    assertNumConnections(-4, -4, 2);

    // stop the network server on locator (rest will get stopped in tearDown)
    stopNetworkServerOnLocator();
  }

  /**
   * Test if multiple connections from network clients failover successfully.
   */
  public void testNetworkClientFailoverWithCurrentSchemaSetting() throws Exception {
    // start some servers not using locator
    startVMs(0, 2);
    // Start two network servers
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    Connection conn = TestUtil.getNetConnection(
        "localhost", netPort, null, new Properties());

    int port;
    if (ClientSharedUtils.isThriftDefault()) {
      ClientConnection clientConn = (ClientConnection)conn;
      port = clientConn.getClientService().getCurrentHostConnection()
          .hostAddr.getPort();
    } else {
      com.pivotal.gemfirexd.internal.client.am.Connection clientConn
          = (com.pivotal.gemfirexd.internal.client.am.Connection)conn;
      NetAgent agent = (NetAgent)clientConn.agent_;
      port = agent.getPort();
    }
    assertTrue(port == netPort || port == netPort2);
    boolean connectedToFirst = true;
    if (port == netPort2) {
      connectedToFirst = false;
    }

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create schema CURRSCHEMA");
    stmt.execute("set current schema CURRSCHEMA");
    stmt.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null) redundancy 1");

    // now try putting a unicode string (#41673)
    PreparedStatement pstmt = conn
        .prepareStatement("insert into TESTTABLE values(?,?)");

    final String s = "test\u0905";
    pstmt.setInt(1, 1);
    pstmt.setString(2, s);
    pstmt.execute();

    ResultSet rs = conn.createStatement().executeQuery(
        "select * from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    String resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    pstmt.setInt(1, 10);
    pstmt.setString(2, s);
    pstmt.execute();
    // now stop the server to which the conn is connected to.
    if (connectedToFirst) {
      stopVMNums(-1);
    }
    else {
      stopVMNums(-2);
    }

    // Now try inserting one more into the table without resetting the current schema
    pstmt.setInt(1, 30);
    pstmt.setString(2, s);
    pstmt.execute();

    rs = conn.createStatement().executeQuery(
        "select count(*) from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertFalse(rs.next());
    conn.close();
  }

  /**
   * Test if multiple connections from network clients are load-balanced across
   * multiple servers using GFE's ServerLocator. Also check for the failover.
   */
  public void testNetworkClientLoadBalancing() throws Exception {
    // always use thrift for this test
    SerializableRunnable setThrift = new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfirexd." +
            ClientSharedUtils.USE_THRIFT_AS_DEFAULT_PROP, "true");
        ClientSharedUtils.setThriftDefault(true);
      }
    };
    setThrift.run();
    for (int i = 0; i <= 3; i++) {
      Host.getHost(0).getVM(i).invoke(setThrift);
    }
    try {
      runTestNetworkClientLoadBalancing();
    } finally {
      SerializableRunnable resetThrift = new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty("gemfirexd." +
              ClientSharedUtils.USE_THRIFT_AS_DEFAULT_PROP, "false");
          ClientSharedUtils.setThriftDefault(false);
        }
      };
      resetThrift.run();
      for (int i = 0; i <= 3; i++) {
        Host.getHost(0).getVM(i).invoke(resetThrift);
      }
    }
  }

  private void runTestNetworkClientLoadBalancing() throws Exception {
    // start the GemFireXD locator
    final VM locator = Host.getHost(0).getVM(3);
    final int netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final InetAddress localHost = InetAddress.getByName("localhost");
    Properties props = new Properties();
    setCommonProperties(props, 0, null, null);
    props.remove("locators");
    final int locatorPort = (Integer)locator.invoke(TestUtil.class,
        "startLocator", new Object[] { localHost.getHostAddress(), netPort,
            props });

    // start a couple of servers
    props = new Properties();
    props.setProperty("locators", localHost.getCanonicalHostName() + '['
        + locatorPort + ']');
    startVMs(0, 2, 0, null, props);
    // Start a couple of network servers
    final int netPort1 = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);

    attachConnectionListener(1, connListener);
    attachConnectionListener(2, connListener);
    attachConnectionListener(locator, connListener);

    // check that load-balance is false by default when connecting directly to servers
    // and true when connecting to locator
    for (int i = 0; i < 10; i++) {
      Connection conn = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
          netPort, null, new Properties());
      assertEquals("true",
          ((ClientConnection)conn).getConnectionProperties().get(ClientAttribute.LOAD_BALANCE));
      assertNumConnections(-1, 0, locator);
      conn.close();
    }
    // reattach listener to clear the counts
    attachConnectionListener(1, connListener);
    attachConnectionListener(2, connListener);
    attachConnectionListener(locator, connListener);
    final SerializableCallable<Object> getDSID = new SerializableCallable<Object>() {
      @Override
      public Object call() throws Exception {
        return GemFireStore.getMyId().getId();
      }
    };
    String dsid1 = (String)serverExecute(1, getDSID);
    String dsid2 = (String)serverExecute(2, getDSID);
    for (int i = 0; i < 10; i++) {
      Connection conn1 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
          netPort1, null, new Properties());
      Statement stmt1 = conn1.createStatement();
      ResultSet rs1 = stmt1.executeQuery("values dsid()");
      assertTrue(rs1.next());
      assertEquals(dsid1, rs1.getString(1));
      assertFalse(rs1.next());

      Connection conn2 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
          netPort2, null, new Properties());
      Statement stmt2 = conn2.createStatement();
      ResultSet rs2 = stmt2.executeQuery("values dsid()");
      assertTrue(rs2.next());
      assertEquals(dsid2, rs2.getString(1));
      assertFalse(rs2.next());

      assertEquals("false",
          ((ClientConnection)conn1).getConnectionProperties().get(ClientAttribute.LOAD_BALANCE));
      assertEquals("false",
          ((ClientConnection)conn2).getConnectionProperties().get(ClientAttribute.LOAD_BALANCE));
      assertNumConnections(0, 0, locator);
      stmt1.close();
      stmt2.close();
      conn1.close();
      conn2.close();
      assertNumConnections(0, 0, locator);
    }
    // reattach listener to clear the counts
    attachConnectionListener(1, connListener);
    attachConnectionListener(2, connListener);
    attachConnectionListener(locator, connListener);

    // Use this VM as the network client
    Connection conn = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort, null, new Properties());

    // check new connections opened on locator and servers
    assertNumConnections(-1, 0, locator);
    assertNumConnections(1, -1, 1, 2);

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null) redundancy 1");

    // now try putting a unicode string (#41673)
    PreparedStatement pstmt = conn
        .prepareStatement("insert into TESTTABLE values(?,?)");

    final String s = "test\u0905";
    pstmt.setInt(1, 1);
    pstmt.setString(2, s);
    pstmt.execute();

    ResultSet rs = conn.createStatement().executeQuery(
        "select * from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    String resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1), resultStr
        .charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    assertNumConnections(-1, 0, locator);
    assertNumConnections(1, -1, 1, 2);

    // now open another connection with server1 URL
    final Connection conn2 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort1, null, new Properties());

    // check new connection opened on servers successfully load-balanced
    assertNumConnections(-1, 0, locator);
    assertNumConnections(-3, -1, 1, 2);

    stmt = conn2.createStatement();
    rs = stmt.executeQuery("select * from TESTTABLE");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertFalse(rs.next());

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-3, -1, 1, 2);

    // now a third connection
    Connection conn3 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort, null, new Properties());

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-4, -1, 1, 2);

    // add expected exception for server connection failure
    addExpectedException(null, new Object[] { java.net.ConnectException.class,
        DisconnectException.class, SQLNonTransientConnectionException.class });

    // now stop the first server and check for successful failover to second
    stopVMNums(-1);
    pstmt = conn.prepareStatement("insert into TESTTABLE values(?,?)");

    final String s2 = "test\u0906";
    pstmt.setInt(1, 2);
    pstmt.setString(2, s2);
    pstmt.execute();

    // check failover for conn3 too
    conn3.createStatement().execute("select count(ID) from TESTTABLE");

    // conn2 should be invalid now due to load-balance=false by default
    try {
      conn2.createStatement().execute("select count(ID) from TESTTABLE");
      fail("excepted connection to fail without load balancing");
    } catch (SQLException sqle) {
      if (!"X0Z01".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // check connections opened on second server
    assertNumConnections(-1, 0, locator);
    assertNumConnections(-4, -2, 2);

    removeExpectedException(null, new Object[] {
        java.net.ConnectException.class, DisconnectException.class,
        SQLNonTransientConnectionException.class });

    // create another connection and run a query to check results
    final Connection conn4 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort, null, new Properties());

    rs = conn4.createStatement().executeQuery(
        "select * from TESTTABLE order by ID");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s.length(), resultStr.length());
    assertEquals(s.charAt(s.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s, resultStr);
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    resultStr = rs.getString(2);
    getLogWriter().info("sent chars: " + Arrays.toString(s2.toCharArray()));
    getLogWriter().info(
        "got chars: " + Arrays.toString(resultStr.toCharArray()));
    assertEquals(s2.length(), resultStr.length());
    assertEquals(s2.charAt(s2.length() - 1),
        resultStr.charAt(resultStr.length() - 1));
    assertEquals(s2, resultStr);
    assertFalse(rs.next());

    // check connection opened on second server
    assertNumConnections(-1, 0, locator);
    assertNumConnections(-5, -2, 2);

    // now drop the table and close the connections
    stmt = conn.createStatement();
    stmt.execute("drop table TESTTABLE");
    stmt.close();
    conn.close();

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-5, -3, 2);

    conn2.close();

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-5, -3, 2);

    conn3.close();

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-5, -4, 2);

    conn4.close();

    assertNumConnections(-1, 0, locator);
    assertNumConnections(-5, -5, 2);
  }

  /** test for exception when there is no data member available */
  public void test41320_41723() throws Exception {
    // start a client VM
    startVMs(1, 0);

    // ------------------ first test for partitioned tables
    run41320_41723("", 1, null);

    // ------------------ now do the same for replicated tables
    stopVMNum(-1);
    run41320_41723(" replicate", 1, null);

    // ------------------ now the same as above with server groups
    run41320_41723(" server groups (sg1)", 2, "SG1");

    // ------------------ now for replicated tables
    stopVMNum(-2);
    run41320_41723(" replicate server groups (sg2)", 2, "SG2");
  }

  private void run41320_41723(String createSuffix, int serverNum, String serverGroups)
      throws Exception {

    // creating table with no datastore should throw an exception (#41723)
    try {
      clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
          + "addr varchar(100))" + createSuffix);
      fail("expected exception when creating table with no datastore");
    } catch (SQLException ex) {
      if (!"X0Z08".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // now try again after starting a new datastore and stopping it
    if (this.serverVMs.size() < serverNum) {
      startServerVMs(1, 0, serverGroups);
    }
    else {
      restartServerVMNums(new int[] { serverNum }, 0, serverGroups, null);
    }
    try {
      clientSQLExecute(1, "create schema EMP");
    } catch (SQLException sqle) {
      // ignore duplicate schema exception
      if (!"X0Y68".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
        + "addr varchar(100))" + createSuffix);
    clientSQLExecute(1, "drop table EMP.TESTTABLE");
    // try to drop the table again and check exception type
    try {
      clientSQLExecute(1, "drop table EMP.TESTTABLE");
    } catch (SQLException ex) {
      if (!"42Y55".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
        + "addr varchar(100))" + createSuffix);
    stopVMNum(-serverNum);
    // now trying to insert data in table should throw an exception
    addExpectedException(new int[] { 1 }, null,
        new Object[] { PartitionedRegionStorageException.class,
            GemFireXDRuntimeException.class });
    try {
      clientSQLExecute(1, "insert into EMP.TESTTABLE values (1, 'ONE')");
      fail("expected exception when inserting with no datastore");
    } catch (SQLException ex) {
      if (!"X0Z08".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    removeExpectedException(new int[] { 1 }, null,
        new Object[] { PartitionedRegionStorageException.class,
            GemFireXDRuntimeException.class });

    // also expect an exception when trying to create an index
    try {
      clientSQLExecute(1, "create index EMP.TESTIDX ON EMP.TESTTABLE(addr)");
      fail("expected exception when creating index with no datastore");
    } catch (SQLException ex) {
      if (!"X0Z08".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    if (serverNum > 1) {
      try {
        serverSQLExecute(1, "create index EMP.TESTIDX ON EMP.TESTTABLE(addr)");
        fail("expected exception when creating index with no datastore");
      } catch (RMIException rmiex) {
        assertTrue("expected SQLException: " + rmiex.getCause(),
            rmiex.getCause() instanceof SQLException);
        SQLException sqle = (SQLException)rmiex.getCause();
        if (!"X0Z08".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }

    // now try after starting a new datastore and keeping it
    restartServerVMNums(new int[] { serverNum }, 0, serverGroups, null);
    clientSQLExecute(1, "drop table EMP.TESTTABLE");
    clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
        + "addr varchar(100))" + createSuffix);
    clientSQLExecute(1, "drop table EMP.TESTTABLE");

    // now trying to insert data in table should not throw an exception
    clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
        + "addr varchar(100))" + createSuffix);
    clientSQLExecute(1, "create index EMP.TESTIDX ON EMP.TESTTABLE(addr)");
    serverSQLExecute(1, "create index EMP.TESTIDX2 ON EMP.TESTTABLE(id)");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (1, 'ONE')");
    serverSQLExecute(serverNum, "insert into EMP.TESTTABLE values (2, 'TWO')");

    // drop everything
    serverSQLExecute(1, "drop index EMP.TESTIDX2");
    serverSQLExecute(serverNum, "drop index EMP.TESTIDX");
    clientSQLExecute(1, "drop table EMP.TESTTABLE");
  }

  public void testUseCase8Ticket5996_1() throws Exception {

    // Start a network server
    final int netPort = startNetworkServer(1, null, null);
    // start another server
    startVMs(0, 1);
    // Use this VM as the network client
    TestUtil.deletePersistentFiles = true;
    final Connection conn = TestUtil.getNetConnection(netPort, null, null);

    Statement stmt = conn.createStatement();
    stmt.execute("CREATE PROCEDURE TEST_5996(IN var Integer ) PARAMETER STYLE "
        + "JAVA LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd."
        + "ClientServerDUnit.proc5996(java.lang.Integer)'");
    CallableStatement cs = conn.prepareCall("CALL TEST_5996(?)");
    try {
      cs.setInt(1, 1);
      cs.execute();
      fail("Test should have failed");
    } catch (SQLException sqle) {
      if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
        throw sqle;
      }
    } catch (Exception e) {
      fail("unexpected exception", e);
    }

    try {
      cs.setInt(1, 2);
      cs.execute();
      fail("Test should have failed");
    } catch (SQLException sqle) {
      if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
        throw sqle;
      }
    } catch (Exception e) {
      fail("unexpected exception", e);
    }

    try {
      cs.setInt(1, 3);
      cs.execute();
      fail("Test should have failed");
    } catch (SQLException sqle) {
      if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
        throw sqle;
      }
      if (!"38000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    } catch (Exception e) {
      fail("unexpected exception", e);
    }
  }

  public void testUseCase8Ticket5996_2() throws Exception {
    try {
      // Start a network server in the controller
      int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
      _startNetworkServer(this.getClass().getName(), this.getName(), 0,
          netPort, null, null, false);
      TestUtil.deletePersistentFiles = true;
      // start two server VMs
      startVMs(0, 2);
      // Use this controller VM as the network client too
      final Connection conn = TestUtil.getNetConnection(netPort, null, null);
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE PROCEDURE TEST_5996(IN var Integer ) "
          + "PARAMETER STYLE JAVA  LANGUAGE JAVA EXTERNAL NAME "
          + "'com.pivotal.gemfirexd.ClientServerDUnit.proc5996("
          + "java.lang.Integer)'");
      CallableStatement cs = conn.prepareCall("CALL TEST_5996(?)");
      try {
        cs.setInt(1, 1);
        cs.execute();
        fail("Test should have failed");
      } catch (SQLException sqle) {
        if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
          throw sqle;
        }
      } catch (Exception e) {
        fail("unexpected exception", e);
      }

      try {
        cs.setInt(1, 2);
        cs.execute();
        fail("Test should have failed");
      } catch (SQLException sqle) {
        if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
          throw sqle;
        }
      } catch (Exception e) {
        fail("unexpected exception", e);
      }

      try {
        cs.setInt(1, 3);
        cs.execute();
        fail("Test should have failed");
      } catch (SQLException sqle) {
        if (sqle.getLocalizedMessage().indexOf("5996") == -1) {
          throw sqle;
        }
        if (!"38000".equals(sqle.getSQLState())) {
          throw sqle;
        }
      } catch (Exception e) {
        fail("unexpected exception", e);
      }
    } finally {
      shutDownNetworkServer();
    }
  }

  public void testVarbinaryFailure() throws Exception {
    // Start a network server
    final int netPort = startNetworkServer(2, null, null);

    // Use this VM as the network client
    final Connection conn = TestUtil.getNetConnection(netPort, null, null);

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table Varbinary_Tab (VARBINARY_VAL varchar(384) "
        + "for bit data NULL)");

    PreparedStatement ps = conn
        .prepareStatement("insert into Varbinary_Tab values(?)");
    ps.setBytes(1, new byte[] { (byte)11101011 });
    ps.execute();

    ps = conn.prepareStatement("update Varbinary_Tab set VARBINARY_VAL=?");
    ps.setBytes(1, new byte[] { (byte)10111101 });
    ps.executeUpdate();

    // drop the table and close the connection
    stmt.execute("drop table Varbinary_Tab");
    stmt.close();
    conn.close();
  }

  public void testLocatorStartupAPIWithBUILTINAuthentication() throws Exception {
    final Properties startProps = doSecuritySetup(new Properties(), true);
    final Properties stopProps = doSecuritySetup(new Properties(), false);

    final Properties userProps = new Properties();
    userProps.setProperty(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, "sysUser1");
    userProps.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR,
        "pwd_sysUser1");

    // first check with explicit bind-address
    FabricLocator fabapi = FabricServiceManager.getFabricLocatorInstance();
    final Properties props = new Properties();
    setCommonProperties(props, 0, null, startProps);
    props.remove("locators");
    final int port = TestUtil.startLocator("localhost", -1,
        props);

    final Properties serverProps = doSecuritySetup(new Properties(), true);
    serverProps.setProperty("locators", "localhost[" + port + ']');
    startServerVMs(1, 0, null, serverProps);

    // start off network servers
    final int netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final NetworkInterface ni = fabapi.startNetworkServer(null, netPort,
        userProps);
    try {
      // first create a DB user
      serverExecute(1, new SerializableRunnable() {
        @Override
        public void run() {
          try {
            final int netPort = AvailablePort
                .getRandomAvailablePort(AvailablePort.SOCKET);
            FabricServiceManager.getFabricServerInstance().startNetworkServer(
                null, netPort, null);
            final Connection conn = TestUtil.getConnection(userProps);
            final Statement stmt = conn.createStatement();
            stmt.execute("call sys.create_user('"
                + Property.USER_PROPERTY_PREFIX + "gem1', 'gem1')");
            stmt.close();
            conn.close();
          } catch (SQLException sqle) {
            final TestException te = new TestException("unexpected exception");
            te.initCause(sqle);
            throw te;
          }
        }
      });

      // connect using this DB user
      userProps.setProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, "gem1");
      userProps.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "gem1");

      // verify that no tables can be created in this VM
      final Connection conn = TestUtil.getConnection(userProps);
      final Statement stmt = conn.createStatement();
      TestUtil.addExpectedException(GemFireXDRuntimeException.class);
      try {
        stmt.execute("create table testapi(id int)");
        fail("expected DDL execution to fail");
      } catch (SQLException ex) {
        if (!"38000".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      TestUtil.removeExpectedException(GemFireXDRuntimeException.class);
      // verify that meta-data tables can be queried successfully
      final ResultSet rs = stmt
          .executeQuery("select KIND, LOCATOR from SYS.MEMBERS order by KIND");
      assertTrue("expected two rows in meta-data query", rs.next());
      assertEquals("datastore(normal)", rs.getString(1));
      assertNull(rs.getString(2));
      assertTrue("expected two rows in meta-data query", rs.next());
      assertEquals("locator(normal)", rs.getString(1));
      assertEquals("127.0.0.1[" + port + ']', rs.getString(2));
      assertFalse("expected no more than two rows from SYS.MEMBERS", rs.next());
    } finally {
      try {
        serverExecute(1, new SerializableRunnable() {
          @Override
          public void run() {
            TestException te = null;
            try {
              userProps.setProperty(
                  com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, "sysUser1");
              userProps.setProperty(
                  com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "pwd_sysUser1");
              final Connection conn = TestUtil.getConnection(userProps);
              final Statement stmt = conn.createStatement();
              stmt.execute("call sys.drop_user('gem1')");
              stmt.close();
              conn.close();
            } catch (SQLException sqle) {
              te = new TestException("unexpected exception");
              te.initCause(sqle);
            }
            try {
              FabricServiceManager.getFabricServerInstance()
                  .stopAllNetworkServers();
              TestUtil.shutDown(stopProps);
            } catch (SQLException sqle) {
              te = new TestException("unexpected exception");
              te.initCause(sqle);
            }
            if (te != null) {
              throw te;
            }
          }
        });
        ni.stop();
        fabapi.stop(stopProps);
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  }

  public void testV3EncryptionScheme() throws Exception {
    // test that both v2 and v3 encrypted passwords work
    Properties authProps = new Properties();
    // v3
    authProps.setProperty("gemfirexd.user.admin", AuthenticationServiceBase
        .encryptUserPassword("admin", "admin", true, false, true));
    // v2
    authProps.setProperty("gemfirexd.user.root", AuthenticationServiceBase
        .encryptUserPassword("root", "root", true, true, false));
    File authDir = new File("sec");
    authDir.mkdir();
    File authFile = new File(authDir, "gfxd-security.properties");
    FileOutputStream fos = new FileOutputStream(authFile);
    authProps.store(fos, null);
    Connection conn2 = null;
    Statement systemUser_stmt = null;
    try {
      // start a locator and some severs with auth enabled
      final int locPort = AvailablePort
          .getRandomAvailablePort(AvailablePort.SOCKET);
      final Properties props = new Properties();
      props.setProperty("start-locator", "localhost[" + locPort + ']');
      props.setProperty("auth-provider", "BUILTIN");
      props.setProperty("gemfirexd.properties", authFile.getAbsolutePath());
      props.setProperty("user", "admin");
      props.setProperty("password", "admin");
      startVMs(0, 1, 0, null, props);
      props.remove("start-locator");
      props.setProperty("locators", "localhost[" + locPort + ']');
      props.setProperty("user", "root");
      props.setProperty("password", "root");
      startVMs(1, 2, 0, null, props);

      // connect as system user 'admin'
      final Properties props2 = new Properties();
      props2.setProperty("user", "admin");
      props2.setProperty("password", "admin");
      Connection conn1 = TestUtil.getConnection(props2);

      // connect as system user 'root'
      props2.setProperty("user", "root");
      props2.setProperty("password", "root");
      conn2 = TestUtil.getConnection(props2);
      systemUser_stmt = conn2.createStatement();

      // create a new user
      systemUser_stmt.execute("call sys.create_user('user1', 'a')");

      // connect as user 'user1'
      props2.setProperty("user", "user1");
      props2.setProperty("password", "a");
      Connection conn3 = TestUtil.getConnection(props2);

      // check failure with incorrect password
      props2.setProperty("user", "root");
      props2.setProperty("password", "admin");
      try {
        TestUtil.getConnection(props2);
        fail("expected exception in connection");
      } catch (SQLException sqle) {
        if (!"08004".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      props2.setProperty("user", "admin");
      props2.setProperty("password", "root");
      try {
        TestUtil.getConnection(props2);
        fail("expected exception in connection");
      } catch (SQLException sqle) {
        if (!"08004".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      conn1.close();
      conn3.close();
    } finally {
      try {
        if (conn2 != null) {
          if (systemUser_stmt != null) {
            systemUser_stmt.execute("call sys.drop_user('user1')");
          }
          conn2.close();
        }
      } finally {
        fos.close();
        if (!authFile.delete()) {
          authFile.deleteOnExit();
        }
        if (!authDir.delete()) {
          authDir.deleteOnExit();
        }
      }
    }
  }

  public static void proc5996(Integer throwSqlException) throws SQLException {
    if (throwSqlException.intValue() == 1) {
      throw new SQLException("IGNORE_EXCEPTION_test test bug 5996");
    }
    else if (throwSqlException.intValue() == 2) {
      throw new GemFireXDRuntimeException("IGNORE_EXCEPTION_test test bug 5996");
    }
    else if (throwSqlException.intValue() == 3) {
      throw new SQLException("IGNORE_EXCEPTION_test test bug 5996", "38000");
    }
  }

  public static void procTest(Integer arg) {
    getGlobalLogger().info("Invoked procTest with arg: " + arg);
  }

  private Properties doSecuritySetup(final Properties props,
      final boolean defineSysUser) {

    if (defineSysUser) {
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");

      props.setProperty(DistributionConfig.GEMFIRE_PREFIX
          + DistributionConfig.SECURITY_LOG_LEVEL_NAME, "finest");

      props.setProperty(Monitor.DEBUG_TRUE, GfxdConstants.TRACE_AUTHENTICATION
          + "," + GfxdConstants.TRACE_SYS_PROCEDURES + ","
          + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);

      props.setProperty(Property.USER_PROPERTY_PREFIX + "sysUser1",
          "pwd_sysUser1");
      props.setProperty(com.pivotal.gemfirexd.Attribute.AUTH_PROVIDER,
          com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_BUILTIN);
    }
    props.setProperty(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, "sysUser1");
    props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "pwd_sysUser1");

    return props;
  }

  public static void waitForDerbyInitialization(NetworkServerControl server)
      throws InterruptedException {
    for (int tries = 1; tries <= 20; tries++) {
      try {
        server.ping();
        break;
      } catch (Throwable t) {
        Thread.sleep(1000);
      }
    }
  }
}
