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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.SocketCreator;
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
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import io.snappydata.app.TestThrift;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import io.snappydata.thrift.internal.ClientConnection;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derbyTesting.junit.JDBC;

/**
 * Test that client and server are being correctly configured with different
 * combinations of the "gemfirexd.jdbc.client" property.
 * 
 * @author swale
 * @since 6.0
 */
@SuppressWarnings("serial")
public class ClientServerDUnit extends DistributedSQLTestBase {

  public ClientServerDUnit(String name) {
    super(name);
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
    // delete the top-level datadictionary created by some tests in this suite
    File dir = new File("datadictionary");
    boolean result = TestUtil.deleteDir(dir);
    TestUtil.getLogger().info(
        "For Test: " + getClassName() + ":" + getTestName()
            + " found and deleted stray datadictionarydir at: "
            + dir.toString() + " : " + result);
  }

  private RegionAttributesCreation getServerTestTableProperties() {

    // Create a set of expected region attributes for the table
    RegionAttributesCreation serverAttrs = new RegionAttributesCreation();
    serverAttrs.setDataPolicy(DataPolicy.PARTITION);
    serverAttrs.setConcurrencyChecksEnabled(false);
    PartitionAttributes<?, ?> pa = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    serverAttrs.setPartitionAttributes(pa);
    serverAttrs.setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    serverAttrs.setAllHasFields(true);
    serverAttrs.setHasScope(false);
    serverAttrs.setHasDiskDirs(false);
    serverAttrs.setHasDiskWriteAttributes(false);

    return serverAttrs;
  }

  private RegionAttributesCreation[] checkTestTableProperties(String schemaName)
      throws Exception {
    return checkTestTableProperties(schemaName, false);
  }

  private RegionAttributesCreation[] checkTestTableProperties(String schemaName,
      boolean isDataStore) throws Exception {

    RegionAttributesCreation serverAttrs = getServerTestTableProperties();

    if (schemaName == null) {
      schemaName = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    }
    // Check the table attributes on the servers and the client
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", serverAttrs);
    serverVerifyRegionProperties(2, schemaName, "TESTTABLE", serverAttrs);

    // Check that the local-max-memory PR attribute is zero on the client
    RegionAttributesCreation clientAttrs = new RegionAttributesCreation(
        serverAttrs, false);
    final PartitionAttributes<?, ?> pa;
    if (isDataStore) {
      pa = new PartitionAttributesFactory<Object, Object>(clientAttrs
          .getPartitionAttributes()).setLocalMaxMemory(
          PartitionAttributesFactory.LOCAL_MAX_MEMORY_DEFAULT).create();
    } else {
      pa = new PartitionAttributesFactory<Object, Object>(clientAttrs
          .getPartitionAttributes()).setLocalMaxMemory(0).create();
    }
    clientAttrs.setPartitionAttributes(pa);
    TestUtil.verifyRegionProperties(schemaName, "TESTTABLE", TestUtil
        .regionAttributesToXML(clientAttrs));
    return new RegionAttributesCreation[] { serverAttrs, clientAttrs };
  }

  private void checkVMsDown(VM... vms) {
    SerializableRunnable noGFE = new SerializableRunnable("GFE down") {
      @Override
      public void run() throws CacheException {
        try {
          CacheFactory.getAnyInstance();
          fail("expected the cache to be closed");
        } catch (CacheClosedException ex) {
          // expected
        }
        DistributedSystem sys = InternalDistributedSystem
            .getConnectedInstance();
        assertNull("expected the distributed system to be down", sys);
      }
    };
    for (VM vm : vms) {
      if (vm == null) {
        noGFE.run();
      }
      else {
        vm.invoke(noGFE);
      }
    }
  }

  // Try some metadata calls
  private void checkDBMetadata(Connection conn, String... urls) throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();
    String actualUrl = dbmd.getURL();
    // remove any trailing slash
    getLogWriter().info("Got DB " + dbmd.getDatabaseProductName() + ' '
        + dbmd.getDatabaseProductVersion() + " using URL " + actualUrl);
    actualUrl = actualUrl.replaceFirst("/$", "");
    boolean foundMatch = false;
    for (String url : urls) {
      url = url.replaceFirst("/$", "");
      if (url.equals(actualUrl)) {
        foundMatch = true;
        break;
      }
    }
    if (!foundMatch) {
      fail("Expected one of the provided URLs "
          + java.util.Arrays.toString(urls) + " to match " + actualUrl);
    }
    ResultSet rs = dbmd.getCatalogs();
    while (rs.next()) {
      getLogWriter().info("Got DB catalog: " + rs.getString(1));
    }
    rs.close();
    rs = dbmd.getSchemas();
    while (rs.next()) {
      getLogWriter().info("Got DB schema: " + rs.getString(1)
          + " in catalog=" + rs.getString(2));
    }
    rs.close();
    rs = dbmd.getProcedures(null, null, null);
    while (rs.next()) {
      getLogWriter().info("Got Procedure " + rs.getString(3) + " in catalog="
          + rs.getString(1) + ", schema=" + rs.getString(2));
    }
    rs.close();
    // also check for a few flags that are failing over network connection
    assertTrue(dbmd.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
    assertTrue(dbmd.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
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
    final InetAddress localHost = SocketCreator.getLocalHost();
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
    TestThrift.run(SocketCreator.getLocalHost().getHostName(),
        netPorts[1], true);
  }

  public void test44240() throws Exception {
    /*
    System.setProperty("gemfirexd.debug.true", "TraceClientHA");
    System.setProperty("gemfirexd.client.traceDirectory",
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
    final InetAddress localHost = SocketCreator.getLocalHost();
    String url = TestUtil.getNetProtocol(localHost.getHostName(), netPort);
    conn = DriverManager.getConnection(url, connProps);
    conn2 = DriverManager.getConnection(
        TestUtil.getNetProtocol(localHost.getHostName(), netPort2),
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
    System.setProperty("gemfirexd.client.traceDirectory",
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
    final InetAddress localHost = SocketCreator.getLocalHost();
    String url = TestUtil.getNetProtocol(localHost.getHostName(), netPort);
    conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connProps));
    conn2 = DriverManager.getConnection(
        TestUtil.getNetProtocol(localHost.getHostName(), netPort2), connProps);

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
    final InetAddress localHost = SocketCreator.getLocalHost();
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
    assertNumConnections(1, -1, 2);

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
    assertNumConnections(1, -1, 2);

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
    final InetAddress localHost = SocketCreator.getLocalHost();
    Connection conn = TestUtil.getNetConnection(
        localHost.getCanonicalHostName(), netPort, null, new Properties());

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
   * Test if client is successfully able to failover to secondary locators for
   * control connection specified with "secodary-locators" property if the
   * primary one is unavailable at the time of the first connection (#47486).
   */
  public void testNetworkClientFailoverMultipleLocators() throws Throwable {
    // start some locators and couple of servers
    int locPort1 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    int locPort2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    int locPort3 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);

    final String address = SocketCreator.getLocalHost().getHostAddress();
    // try with both host:port and host[port] for client connection
    String locators = address + '[' + locPort1 + "]," + address + '['
        + locPort2 + "]," + address + '[' + locPort3 + ']';

    Properties props = new Properties();
    props.setProperty("locators", locators);
    props.setProperty("mcast-port", "0");
    startLocatorVM(address, locPort1, null, props);
    startLocatorVM(address, locPort2, null, props);
    startLocatorVM(address, locPort3, null, props);

    // now a server and a peer client
    startVMs(1, 1, 0, null, props);

    // start network servers on all members
    final int netPort1 = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    startNetworkServer(4, null, null);

    // now client connections using all locators, second and third locators and
    // only third locator
    final String secondaryLocators = address + ':' + netPort2 + ',' + address
        + ':' + netPort3;
    final String secondaryLocators2 = address + '[' + netPort2 + "]," + address
        + '[' + netPort3 + ']';

    Connection netConn1 = TestUtil.getNetConnection(address, netPort1,
        ";secondary-locators=" + secondaryLocators, null);
    Statement stmt = netConn1.createStatement();
    stmt.execute("create table T.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null) redundancy 1");

    final AtomicInteger id = new AtomicInteger(1);
    SerializableCallable testConn = new SerializableCallable() {
      @Override
      public Object call() {
        /*
        System.setProperty("gemfirexd.debug.true", "TraceClientHA");
        SanityManager.TraceClientHA = true;
        */
        try {
          final Connection netConn;
          final Properties props;
          final int currId = id.get();
          switch (currId % 4) {
            case 1:
              netConn = TestUtil.getNetConnection(address, netPort1,
                  ";secondary-locators=" + secondaryLocators2, null);
              break;
            case 2:
              props = new Properties();
              props.setProperty("secondary-locators", secondaryLocators);
              netConn = TestUtil.getNetConnection(address, netPort1, null,
                  props);
              break;
            case 3:
              props = new Properties();
              props.setProperty("secondary-locators", secondaryLocators2);
              netConn = TestUtil.getNetConnection(address, netPort1, null,
                  props);
              break;
            default:
              netConn = TestUtil.getNetConnection(address, netPort1,
                  ";secondary-locators=" + secondaryLocators, null);
              break;
          }
          Statement stmt = netConn.createStatement();
          assertEquals(1, stmt.executeUpdate("insert into T.TESTTABLE values ("
              + currId + ",'DESC" + currId + "')"));
          return id.incrementAndGet();
        } catch (Throwable t) {
          return t;
        }
      }
    };

    // stop the locators and test for net connections from all JVMs
    checkAndSetId(id, testConn.call());
    stopVMNum(-1);
    checkAndSetId(id, testConn.call());

    checkAndSetId(id, serverExecute(1, testConn));
    stopVMNum(-2);
    checkAndSetId(id, serverExecute(1, testConn));

    checkAndSetId(id, serverExecute(2, testConn));
    stopVMNum(-3);
    checkAndSetId(id, serverExecute(2, testConn));

    checkAndSetId(id, testConn.call());
    checkAndSetId(id, serverExecute(1, testConn));
    checkAndSetId(id, serverExecute(2, testConn));

    // executing from new server must fail since no locator is up at this point
    try {
      checkAndSetId(id, serverExecute(4, testConn));
      fail("expected connection failure with no locator available");
    } catch (SQLException sqle) {
      if (!"08006".equals(sqle.getSQLState())
          && !"X0Z01".equals(sqle.getSQLState())
          && !"40XD0".equals(sqle.getSQLState())) {
        fail("unexpected exception", sqle);
      }
    }

    // finally verify the results
    SerializableCallable verify = new SerializableCallable() {
      @Override
      public Object call() {
        try {
          final Connection netConn = TestUtil.getNetConnection(address,
              netPort1, ";secondary-locators=" + secondaryLocators, null);
          Statement stmt = netConn.createStatement();
          ResultSet rs = stmt.executeQuery("select * from T.TESTTABLE");
          final Object[][] expectedResults = new Object[][] {
              new Object[] { 1, "DESC1" }, new Object[] { 2, "DESC2" },
              new Object[] { 3, "DESC3" }, new Object[] { 4, "DESC4" },
              new Object[] { 5, "DESC5" }, new Object[] { 6, "DESC6" },
              new Object[] { 7, "DESC7" }, new Object[] { 8, "DESC8" },
              new Object[] { 9, "DESC9" }, };
          JDBC.assertUnorderedResultSet(rs, expectedResults, false);
          return id.get();
        } catch (Throwable t) {
          return t;
        }
      }
    };
    checkAndSetId(id, verify.call());
    checkAndSetId(id, serverExecute(1, verify));
    checkAndSetId(id, serverExecute(2, verify));
  }

  private void checkAndSetId(final AtomicInteger id, Object result)
      throws Throwable {
    if (result instanceof Integer) {
      id.set((Integer)result);
    }
    else if (result instanceof Throwable) {
      throw (Throwable)result;
    }
    else {
      fail("unexpected result " + result);
    }
  }

  /**
   * Test if multiple connections from network clients are load-balanced across
   * multiple servers using GFE's ServerLocator. Also check for the failover.
   */
  public void testNetworkClientLoadBalancing() throws Exception {
    // start the GemFireXD locator
    final VM locator = Host.getHost(0).getVM(3);
    final int netPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final InetAddress localHost = SocketCreator.getLocalHost();
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
    startNetworkServer(2, null, null);

    attachConnectionListener(1, connListener);
    attachConnectionListener(2, connListener);
    attachConnectionListener(locator, connListener);

    // Use this VM as the network client
    Connection conn = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort, null, new Properties());

    // check new connections opened on locator and servers
    assertNumConnections(1, 0, locator);
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

    assertNumConnections(1, 0, locator);
    assertNumConnections(1, -1, 1, 2);

    // now open another connection with server1 URL
    final Connection conn2 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort1, null, new Properties());

    // check new connection opened on servers successfully load-balanced
    assertNumConnections(1, 0, locator);
    assertNumConnections(-2, -1, 1);
    assertNumConnections(-2, -1, 2);

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

    assertNumConnections(1, 0, locator);
    assertNumConnections(-2, -1, 1);
    assertNumConnections(-2, -1, 2);

    // now a third connection
    Connection conn3 = TestUtil.getNetConnection(localHost.getCanonicalHostName(),
        netPort, null, new Properties());

    assertNumConnections(1, 0, locator);
    assertNumConnections(-4, -1, 1, 2);

    // add expected exception for server connection failure
    addExpectedException(null, new Object[] { java.net.ConnectException.class,
        DisconnectException.class, SQLNonTransientConnectionException.class });

    // now stop the first server and check for successful failover to second
    stopVMNums(-1);
    pstmt = conn2.prepareStatement("insert into TESTTABLE values(?,?)");

    final String s2 = "test\u0906";
    pstmt.setInt(1, 2);
    pstmt.setString(2, s2);
    pstmt.execute();

    // check failover for conn, conn3 too
    conn.createStatement().execute("select count(*) from TESTTABLE");
    conn3.createStatement().execute("select count(ID) from TESTTABLE");

    // check connections opened on second server
    assertNumConnections(1, 0, locator);
    assertNumConnections(-4, -1, 2);

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
    assertNumConnections(1, 0, locator);
    assertNumConnections(-5, -1, 2);

    // now drop the table and close the connections
    stmt.execute("drop table TESTTABLE");
    stmt.close();
    conn.close();

    assertNumConnections(1, 0, locator);
    assertNumConnections(-5, -2, 2);

    conn2.close();

    assertNumConnections(1, 0, locator);
    assertNumConnections(-5, -3, 2);

    conn3.close();

    assertNumConnections(1, 0, locator);
    assertNumConnections(-5, -4, 2);

    conn4.close();

    assertNumConnections(1, 0, locator);
    assertNumConnections(-5, -5, 2);
  }

  public void testPersistentDD() throws Exception {
    // Start one server
    AsyncVM async1 = invokeStartServerVM(1, 0, null, null);
    // Start a second server with DD persistence
    Properties props = new Properties();
    props.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, "SYS");
    AsyncVM async2 = invokeStartServerVM(2, 0, null, props);

    // Start a client
    startClientVMs(1, 0, null);

    // wait for servers to start
    joinVMs(true, async1, async2);

    // Create a table
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    // Also try creating the same table with a different user name
    String userName = "TesT1";
    // just in the very remote case this clashes with random user name
    if (userName.equalsIgnoreCase(TestUtil.currentUserName)) {
      userName = "TestT2";
    }
    final String userSchemaName = StringUtil.SQLToUpperCase(userName);
    executeForUser(userName, "create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null)");

    final String schemaName = getCurrentDefaultSchemaName();
    // Check the region properties
    RegionAttributesCreation[] expectedAttrs = checkTestTableProperties(
        schemaName);
    RegionAttributesCreation[] userExpectedAttrs = checkTestTableProperties(
        userSchemaName);

    // Restart everything and recheck
    stopVMNums(-1, -2, 1);
    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0),
        this.serverVMs.get(1));

    async1 = restartServerVMAsync(1, 0, null, null);
    async2 = restartServerVMAsync(2, 0, null, props);
    joinVMs(false, async1, async2);
    restartVMNums(1);

    // Check the region properties on server with DD persistence
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[0]);
    serverVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        userExpectedAttrs[0]);
    // Check that region exists on other server and client due to GII from
    // persisted server
    serverVerifyRegionProperties(2, schemaName, "TESTTABLE", expectedAttrs[0]);
    serverVerifyRegionProperties(2, userSchemaName, "TESTTABLE",
        userExpectedAttrs[0]);
    clientVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[1]);
    clientVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        userExpectedAttrs[1]);

    // Also check that stale DD persisted data is overridden.
    stopVMNums(-1, 1);

    serverSQLExecute(2, "drop table TESTTABLE");
    executeOnServerForUser(2, userName, "drop table testtable");
    serverSQLExecute(2, "create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) not null) replicate");
    executeOnServerForUser(2, userName, "create table TESTTABLE (ID int "
        + "not null, DESCRIPTION varchar(1024) not null) replicate");

    restartVMNums(1, -1);

    expectedAttrs[0] = new RegionAttributesCreation();
    expectedAttrs[0].setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs[0].setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs[0].setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    expectedAttrs[0].setConcurrencyChecksEnabled(false);
    expectedAttrs[0].setAllHasFields(true);
    expectedAttrs[0].setHasDiskDirs(false);
    expectedAttrs[0].setHasDiskWriteAttributes(false);
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[0]);
    serverVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        expectedAttrs[0]);
    serverVerifyRegionProperties(2, userSchemaName, "TESTTABLE",
        expectedAttrs[0]);
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[0]);

    expectedAttrs[1] = new RegionAttributesCreation(expectedAttrs[0], false);
    expectedAttrs[1].setDataPolicy(DataPolicy.EMPTY);
    expectedAttrs[1].setConcurrencyChecksEnabled(false);
    expectedAttrs[1].setHasDiskDirs(false);
    expectedAttrs[1].setHasDiskWriteAttributes(false);
    clientVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[1]);
    clientVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        expectedAttrs[1]);

    // Stop everything and check that new table properties are being persisted.
    stopVMNums(-1, 1);
    stopVMNums(-2);
    // start persisted DD VM first using other VM's DD
    joinVM(false, restartServerVMAsync(1, 0, null, props));
    props = new Properties();
    props.setProperty(com.pivotal.gemfirexd.Attribute.GFXD_PERSIST_DD, "true");
    async2 = restartServerVMAsync(2, 0, null, props);
    joinVM(false, async2);
    restartVMNums(1);

    // Check the region properties on server with DD persistence
    serverVerifyRegionProperties(2, schemaName, "TESTTABLE", expectedAttrs[0]);
    serverVerifyRegionProperties(2, userSchemaName, "TESTTABLE",
        expectedAttrs[0]);
    // Check that region exists on other server and client due to GII from
    // persisted server
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[0]);
    serverVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        expectedAttrs[0]);
    clientVerifyRegionProperties(1, schemaName, "TESTTABLE", expectedAttrs[1]);
    clientVerifyRegionProperties(1, userSchemaName, "TESTTABLE",
        expectedAttrs[1]);

    // Drop the table
    clientSQLExecute(1, "drop table TESTTABLE");
    executeForUser(userName, "drop table testTable");
  }

  public void testInitialScripts() throws Exception {
    String testsDir = TestUtil.getResourcesDir();

    TestUtil.deletePersistentFiles = true;
    // Start one server
    AsyncVM async1 = invokeStartServerVM(1, 0, null, null);
    // Start a second server with initial script
    Properties props = new Properties();
    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript.sql");
    AsyncVM async2 = invokeStartServerVM(2, 0, null, props);

    // wait for servers to start
    joinVMs(true, async1, async2);

    // Start a client
    startVMs(1, 0);

    // check that regions have been created but with no data
    String ckFile = testsDir + "/lib/checkQuery.xml";

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, addr, tid from trade.customers", ckFile, "empty");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, qty, tid from trade.portfolio", ckFile, "empty");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select tc.cid, tp.tid, cust_name, availQty from trade.portfolio tp, "
            + "trade.customers tc where tp.cid=tc.cid", ckFile, "empty");

    // drop the tables before restart
    clientSQLExecute(1, "drop table trade.portfolio");
    clientSQLExecute(1, "drop table trade.customers");

    // Restart with both the initial SQL scripts
    stopVMNums(1, -1, -2);
    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0), this.serverVMs
        .get(1));

    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript.sql," + testsDir
        + "/lib/checkInitialScript2.sql");

    async2 = restartServerVMAsync(2, 0, null, props);
    async1 = restartServerVMAsync(1, 0, null, null);
    restartVMNums(1);
    joinVMs(false, async1, async2);

    // check that data has been correctly populated
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select tc.cid, tp.tid, cust_name, availQty from trade.portfolio tp, "
            + "trade.customers tc where tp.cid=tc.cid", ckFile, "is_cust_port");

    // Restart and check failure with SQL scripts in incorrect order
    stopVMNums(-2);

    // drop the tables before restart
    clientSQLExecute(1, "drop table trade.portfolio");
    clientSQLExecute(1, "drop table trade.customers");

    stopVMNums(1, -1);

    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0), this.serverVMs
        .get(1));

    restartVMNums(-1, 1);

    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript2.sql," + testsDir
        + "/lib/checkInitialScript.sql");
    try {
      joinVM(false, restartServerVMAsync(2, 0, null, props));
      fail("Expected an SQLException while starting the VM.");
    } catch (RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if (!"XJ040".equals(sqlEx.getSQLState())) {
          throw ex;
        }
        else {
          // Explicitly delete the newly timestamped persistent file.
          this.serverVMs.get(1).invoke(DistributedSQLTestBase.class,
              "deleteDataDictionaryDir");
        }
      }
      else {
        throw ex;
      }
    }
    // verify that failed server is not running
    checkVMsDown(this.serverVMs.get(1));

    // Restart everything and check that init script fails
    // with already existing table
    stopVMNums(-1, 1);
    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0), this.serverVMs
        .get(1));

    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript.sql");
    async2 = restartServerVMAsync(2, 0, null, props);
    restartVMNums(1);
    joinVM(false, async2);

    addExpectedException(new int[] { 1 }, new int[] { 2 }, SQLException.class);
    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript.sql," + testsDir
        + "/lib/checkInitialScript2.sql");
    try {
      joinVM(false, restartServerVMAsync(1, 0, null, props));
      fail("Expected an SQLException while starting the VM.");
    } catch (RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if (!"XJ040".equals(sqlEx.getSQLState())) {
          throw ex;
        }
        else {
          // Explicitly delete the newly timestamped persistent file.
          this.serverVMs.get(0).invoke(DistributedSQLTestBase.class,
              "deleteDataDictionaryDir");
        }
      }
      else {
        throw ex;
      }
    }
    // verify that failed server is not running
    checkVMsDown(this.serverVMs.get(0));
    removeExpectedException(new int[] { 1 }, new int[] { 2 },
        SQLException.class);

    // Restart everything and check that init script succeeds
    // with already existing table when loading only data
    stopVMNums(1, -2);
    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0), this.serverVMs
        .get(1));

    restartVMNums(1, -2);

    addExpectedException(new int[] { 1 }, new int[] { 2 }, SQLException.class);
    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript2.sql");
    joinVM(false, restartServerVMAsync(1, 0, null, props));
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select tc.cid, tp.tid, cust_name, availQty from trade.portfolio tp, "
            + "trade.customers tc where tp.cid=tc.cid", ckFile, "is_cust_port");

    // Drop tables, start and recheck to see everything is in order.
    serverSQLExecute(2, "drop table trade.portfolio");
    serverSQLExecute(2, "drop table trade.customers");
    stopVMNums(-1);
    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript.sql," + testsDir
        + "/lib/checkInitialScript2.sql");
    joinVM(false, restartServerVMAsync(1, 0, null, props));
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select tc.cid, tp.tid, cust_name, availQty from trade.portfolio tp, "
            + "trade.customers tc where tp.cid=tc.cid", ckFile, "is_cust_port");
  }
  
  
  /**
   * Bug#46682 test: do not allow a node to join the cluster if its locale is
   * different from the cluster.
   * The derby 'territory' property allows us to change the locale of the 
   * database.
   */  
  public void testStartUpWithLocaleSet() throws Exception {
    stopAllVMs();
    Properties props = new Properties();
    // start some servers
    startVMs(0, 1);
    startVMs(0, 1);
    startVMs(0, 1);
    // now change the 'territory' (and hence the database locale) for the next
    // VM
    String locale1Str = new Locale("fr", "CA").toString();
    // choose a different locale than the one already set
    if (!Locale.getDefault().toString().equals(locale1Str)) {
      props.setProperty("territory", locale1Str);
    } else {
      props.setProperty("territory", new Locale("en", "GB").toString());
    }
    try {
      startVMs(0, 1, 0, null, props);
      fail("This test should have failed with GemFireXDRuntimeException."
          + "Locale of all nodes in the cluser needs to be same");
    } catch (RMIException e) {
      if (!e.getCause().getCause().getMessage()
          .startsWith("Locale should be same on all nodes in the cluster")) {
        fail("Test failed with unexpected exception :", e);
      }
    } finally {
      stopAllVMs();
    }
  }

  /**
   * Test for checking routing using server groups
   * {@link ServerGroupUtils#onServerGroups}.
   */
  public void testServerGroupsRouting() throws Exception {
    // start some servers in different server groups and a client
    AsyncVM async1 = invokeStartServerVM(1, 0, "SG1", null);
    AsyncVM async2 = invokeStartServerVM(2, 0, "SG2", null);
    AsyncVM async3 = invokeStartServerVM(3, 0, "SG1, SG3", null);
    startClientVMs(1, 0, "SG2, SG4", null);
    // wait for servers to start
    joinVMs(true, async1, async2, async3);

    // register the function on all the VMs
    SerializableRunnable registerFn = new SerializableRunnable(
        "register function") {
      @Override
      public void run() throws CacheException {
        FunctionService.registerFunction(new TestFunction());
      }
    };
    serverExecute(1, registerFn);
    serverExecute(2, registerFn);
    serverExecute(3, registerFn);
    clientExecute(1, registerFn);

    DistributedMember server1 = getMemberForVM(this.serverVMs.get(0));
    DistributedMember server2 = getMemberForVM(this.serverVMs.get(1));
    DistributedMember server3 = getMemberForVM(this.serverVMs.get(2));
    DistributedMember client1 = getMemberForVM(this.clientVMs.get(0));

    List<?> resultMembers = executeOnServerGroups("SG3");
    checkMembersEqual(resultMembers, server3);

    resultMembers = executeOnServerGroups("SG2");
    checkMembersEqual(resultMembers, server2, client1);

    resultMembers = executeOnServerGroups("SG4");
    checkMembersEqual(resultMembers, client1);

    resultMembers = executeOnServerGroups("SG2,SG1");
    checkMembersEqual(resultMembers, server1, server2, server3, client1);

    resultMembers = executeOnServerGroups("SG3, SG2");
    checkMembersEqual(resultMembers, server2, server3, client1);

    resultMembers = executeOnServerGroups("SG3, SG4");
    checkMembersEqual(resultMembers, server3, client1);

    // check for execution on all servers with no server groups
    resultMembers = executeOnServerGroups("");
    checkMembersEqual(resultMembers, server1, server2, server3);
    resultMembers = executeOnServerGroups(null);
    checkMembersEqual(resultMembers, server1, server2, server3);
    resultMembers = executeOnServerGroups("  ");
    checkMembersEqual(resultMembers, server1, server2, server3);

    // check for exception in case of no members with given server groups
    try {
      resultMembers = executeOnServerGroups("SG5");
      fail("expected function exception");
    } catch (FunctionException ex) {
      // ignore expected exception
    }

    // check for null results too
    GfxdListResultCollector gfxdRC = new GfxdListResultCollector();
    ResultCollector<?, ?> rc = ServerGroupUtils
        .onServerGroups("SG1, SG2, SG3, SG4", false).withCollector(gfxdRC)
        .execute(TestFunction.ID);
    List<?> result = (List<?>)rc.getResult();
    assertEquals("expected number of results: 4", 4, result.size());
    for (Object res : result) {
      assertNull("expected null result", res);
    }
  }

  /**
   * Test for checking execution on server groups and nodes using GROUPS() and
   * ID() builtin.
   */
  public void testNodeAndServerGroupsExecution() throws Exception {
    // start some servers in different server groups and a client
    AsyncVM async1 = invokeStartServerVM(1, 0, "SG1", null);
    AsyncVM async2 = invokeStartServerVM(2, 0, "SG2", null);
    AsyncVM async3 = invokeStartServerVM(3, 0, "SG1, SG3", null);
    startClientVMs(1, 0, "SG2, SG4", null);
    // wait for servers to start
    joinVMs(true, async1, async2, async3);

    // create a table and insert some rows
    clientSQLExecute(1, "create table EMP.TESTTABLE (id int primary key, "
        + "addr varchar(100))");
    final PreparedStatement pstmt = TestUtil.jdbcConn
        .prepareStatement("insert into EMP.TESTTABLE values (?, ?)");
    for (int index = 1; index <= 100; ++index) {
      pstmt.setInt(1, index);
      pstmt.setString(2, "ADDR" + index);
      pstmt.execute();
    }

    // now try execute of a query using GROUPS()
    int numResults = 0;
    final Statement stmt = TestUtil.jdbcConn.createStatement();
    ResultSet rs = stmt.executeQuery("select * from EMP.TESTTABLE "
        + "where GROUPS() like 'SG1%'");
    while (rs.next()) {
      ++numResults;
    }
    rs = stmt.executeQuery("select * from EMP.TESTTABLE "
        + "where GROUPS() = 'SG2'");
    while (rs.next()) {
      ++numResults;
    }
    assertEquals(100, numResults);

    numResults = 0;
    rs = stmt.executeQuery("select * from EMP.TESTTABLE where GROUPS() like "
        + "'%SG3' or 'SG2' = GROUPS() or GROUPS() = 'SG1'");
    while (rs.next()) {
      ++numResults;
    }
    assertEquals(100, numResults);

    // check zero results for server groups with no servers
    rs = stmt.executeQuery("select * from EMP.TESTTABLE "
        + "where GROUPS() like '%SG4'");
    assertFalse(rs.next());
    rs = stmt.executeQuery("select * from EMP.TESTTABLE " +
    		"where GROUPS() = 'SG3'");
    assertFalse(rs.next());

    // get the member IDs
    DistributedMember server1 = getMemberForVM(this.serverVMs.get(0));
    DistributedMember server2 = getMemberForVM(this.serverVMs.get(1));
    DistributedMember server3 = getMemberForVM(this.serverVMs.get(2));
    DistributedMember client1 = getMemberForVM(this.clientVMs.get(0));

    // query execution using DSID() builtin
    numResults = 0;
    rs = stmt.executeQuery("select * from EMP.TESTTABLE where DSID() = '"
        + server1.toString() + "' or DSID() = '" + server2.toString()
        + "' or DSID() = '" + server3.toString() + "'");
    while (rs.next()) {
      ++numResults;
    }
    assertEquals(100, numResults);

    // check zero results on client
    rs = stmt.executeQuery("select * from EMP.TESTTABLE where DSID() = '"
        + client1.toString() + "'");
    assertFalse(rs.next());
    rs = stmt.executeQuery("select * from EMP.TESTTABLE where DSID() = '1'");
    assertFalse(rs.next());
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
    final InetAddress localHost = SocketCreator.getLocalHost();
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
    final int port = TestUtil.startLocator(localHost.getHostAddress(), -1,
        props);

    final Properties serverProps = doSecuritySetup(new Properties(), true);
    serverProps.setProperty("locators", localHost.getHostName() + '[' + port
        + ']');
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
      assertEquals(localHost.getHostAddress() + '[' + port + ']',
          rs.getString(2));
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

  private void checkMembersEqual(List<?> resultMembers,
      DistributedMember... expectedMembers) {
    assertEquals("expected number of results: " + expectedMembers.length,
        expectedMembers.length, resultMembers.size());
    for (DistributedMember member : expectedMembers) {
      assertTrue("expected to find VM in result: " + member,
          resultMembers.contains(member));
    }
  }

  private List<?> executeOnServerGroups(String serverGroups) {
    GfxdListResultCollector gfxdRC = new GfxdListResultCollector();
    ResultCollector<?, ?> rc = ServerGroupUtils
        .onServerGroups(serverGroups, false).withArgs(Boolean.TRUE)
        .withCollector(gfxdRC).execute(TestFunction.ID);
    List<?> result = (List<?>)rc.getResult();
    return result;
  }

  public static void executeForUser(String userName, String sql)
      throws SQLException {
    final Properties userProps = new Properties();
    userProps.setProperty(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, userName);
    userProps.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, userName);
    final Connection userConn = TestUtil.getConnection(userProps);
    userConn.createStatement().execute(sql);
  }

  private void executeOnServerForUser(int serverNum, final String userName,
      final String sql) throws Exception {
    serverExecute(serverNum, new SerializableRunnable("executing " + sql
        + " with userName " + userName) {
      @Override
      public void run() throws CacheException {
        try {
          executeForUser(userName, sql);
        } catch (SQLException ex) {
          throw new CacheException(ex) {
          };
        }
      }
    });
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

  @Override
  protected void setCommonProperties(Properties props, int mcastPort,
      String serverGroups, Properties extraProps) {
    super.setCommonProperties(props, mcastPort, serverGroups, extraProps);
    if (props != null) {
      props.setProperty(TestUtil.TEST_SKIP_DEFAULT_INITIAL_CAPACITY, "true");
    }
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

  private static final class TestFunction implements Function {

    private static final String ID = "ClientServerDUnit.TestFunction";

    public void execute(FunctionContext context) {
      Object args = context.getArguments();
      if (args instanceof Boolean && ((Boolean)args).booleanValue()) {
        InternalDistributedMember myId = Misc.getGemFireCache().getMyId();
        context.getResultSender().lastResult(myId);
      }
      else {
        context.getResultSender().lastResult(null);
      }
    }

    public String getId() {
      return ID;
    }

    public boolean hasResult() {
      return true;
    }

    public boolean optimizeForWrite() {
      return false;
    }

    public boolean isHA() {
      return true;
    }
  }
}
