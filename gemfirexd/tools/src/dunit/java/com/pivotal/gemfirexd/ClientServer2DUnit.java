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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.derbyTesting.junit.JDBC;

public class ClientServer2DUnit extends ClientServerTestBase {

  public ClientServer2DUnit(String name) {
    super(name);
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

    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, addr, tid from trade.customers", ckFile, "empty");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, qty, tid from trade.portfolio", ckFile, "empty");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
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
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
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
        } else {
          // Explicitly delete the newly timestamped persistent file.
          this.serverVMs.get(1).invoke(DistributedSQLTestBase.class,
              "deleteDataDictionaryDir");
        }
      } else {
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

    addExpectedException(new int[]{1}, new int[]{2}, SQLException.class);
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
        } else {
          // Explicitly delete the newly timestamped persistent file.
          this.serverVMs.get(0).invoke(DistributedSQLTestBase.class,
              "deleteDataDictionaryDir");
        }
      } else {
        throw ex;
      }
    }
    // verify that failed server is not running
    checkVMsDown(this.serverVMs.get(0));
    removeExpectedException(new int[]{1}, new int[]{2},
        SQLException.class);

    // Restart everything and check that init script succeeds
    // with already existing table when loading only data
    stopVMNums(1, -2);
    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0), this.serverVMs
        .get(1));

    restartVMNums(1, -2);

    addExpectedException(new int[]{1}, new int[]{2}, SQLException.class);
    props.setProperty(com.pivotal.gemfirexd.Attribute.INIT_SCRIPTS, testsDir
        + "/lib/checkInitialScript2.sql");
    joinVM(false, restartServerVMAsync(1, 0, null, props));
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
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
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, addr, tid from trade.customers", ckFile, "dd_cust_insert");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
        "select cid, qty, tid from trade.portfolio", ckFile, "is_port");
    sqlExecuteVerify(new int[]{1}, new int[]{1, 2},
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
      executeOnServerGroups("SG5");
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

    final String address = "localhost";
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
          final Object[][] expectedResults = new Object[][]{
              new Object[]{1, "DESC1"}, new Object[]{2, "DESC2"},
              new Object[]{3, "DESC3"}, new Object[]{4, "DESC4"},
              new Object[]{5, "DESC5"}, new Object[]{6, "DESC6"},
              new Object[]{7, "DESC7"}, new Object[]{8, "DESC8"},
              new Object[]{9, "DESC9"},};
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
      } else {
        vm.invoke(noGFE);
      }
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
        .withCollector(gfxdRC).execute(ClientServer2DUnit.TestFunction.ID);
    return (List<?>)rc.getResult();
  }

  private static void executeForUser(String userName, String sql)
      throws SQLException {
    final Properties userProps = new Properties();
    userProps.setProperty(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, userName);
    userProps.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, userName);
    final Connection userConn = TestUtil.getConnection(userProps);
    userConn.createStatement().execute(sql);
  }

  @SuppressWarnings("SameParameterValue")
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

  private static final class TestFunction implements Function {

    private static final String ID = "ClientServerDUnit.TestFunction";

    public void execute(FunctionContext context) {
      Object args = context.getArguments();
      if (args instanceof Boolean && (Boolean)args) {
        InternalDistributedMember myId = Misc.getGemFireCache().getMyId();
        context.getResultSender().lastResult(myId);
      } else {
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
