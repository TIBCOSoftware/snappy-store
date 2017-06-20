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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.control.RebalanceOperation;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PRHARedundancyProvider;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.client.am.DisconnectException;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.error.ShutdownException;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

@SuppressWarnings({ "serial" })
public class GemFireXDHADUnit extends DistributedSQLTestBase {

  public GemFireXDHADUnit(String name) {
    super(name);
  }

  public static void joinAsyncInvocation(AsyncInvocation async, long ms) {
    if (async.isAlive()) {
      join(async, ms, getGlobalLogger());
    }
  }

  // This test hangs. Need to investigate
  public void __testSelectsDataLoss() throws Exception {
    startVMs(2, 3);
    clientSQLExecute(1, "create table Account ("
        + " id varchar(10) primary key, name varchar(100), type int )"
        + " partition by range(id)" + "   ( values between 'A'  and 'B'"
        + "    ,values between 'C'  and 'D'"
        + "    ,values between 'E'  and 'F'"
        + "    ,values between 'G'  and 'Z'"
        + "    ,values between '1'  and '5'"
        + "    ,values between '5'  and '15'"
        + "    ,values between '15' and '20'" + "   ) redundancy 1");
    // Insert values 1 to 20
    for (int i = 1; i < 21; ++i) {
      clientSQLExecute(1, "insert into Account values ('" + i + "', 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class });
    List<AsyncInvocation> ainvoke = new ArrayList<AsyncInvocation>();
    selectQueryAndExecute(this.clientVMs.get(1), ainvoke);
    List<VM> serverVMsForDisconnect = new ArrayList<VM>();
    serverVMsForDisconnect.add(this.serverVMs.get(1));
    serverVMsForDisconnect.add(this.serverVMs.get(2));
    disconnectVMs(serverVMsForDisconnect, ainvoke);

    for (int i = 0; i < ainvoke.size(); i++) {
      joinAsyncInvocation(ainvoke.get(i), 1200 * 1000);
    }

    for (int i = 0; i < ainvoke.size(); i++) {
      if (ainvoke.get(i).exceptionOccurred()) {
        fail("exception during " + i, ainvoke.get(i).getException());
      }
    }
    removeExpectedException(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class });
  }

  /** tests for bugs #41407 and #41408 */
  public void testRedundancyBug41407_41408() throws Exception {
    // start a client and a server
    startVMs(1, 1);

    clientSQLExecute(1, "create table Account (id int primary key, "
        + "name varchar(100), type int) redundancy 1");
    clientSQLExecute(1, "create table Account2 (id int primary key, "
        + "name varchar(100), type int) redundancy 1 recoverydelay -1");
    clientSQLExecute(1, "create table Account3 (id int primary key, "
        + "name varchar(100), type int) redundancy 1 recoverydelay 1500");
    // This will create primaries on Server1
    serverSQLExecute(1, "insert into Account values (114, 'testBug1', 114)");
    serverSQLExecute(1, "insert into Account2 values (114, 'testBug1', 114)");
    clientSQLExecute(1, "insert into Account3 values (114, 'testBug1', 114)");
    startServerVMs(1, 0, null);
    serverSQLExecute(2, "insert into Account values (2, 'testBug2', 2)");
    serverSQLExecute(1, "insert into Account2 values (2, 'testBug2', 2)");
    clientSQLExecute(1, "insert into Account3 values (2, 'testBug2', 2)");

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account2", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account3", null, "2");

    // stop both servers and check that there is no data
    stopVMNums(-1, -2);
    addExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);
    try {
      sqlExecuteVerify(new int[] { 1 }, null, "select * from Account", null,
          "0");
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    try {
      sqlExecuteVerify(new int[] { 1 }, null, "select * from Account2", null,
          "0");
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    try {
      sqlExecuteVerify(new int[] { 1 }, null, "select * from Account3", null,
          "0");
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);
    // restart the two servers
    restartVMNums(-1, -2);

    // insert a couple of rows again
    serverSQLExecute(2, "insert into Account values (1, 'testBug1', 1)");
    serverSQLExecute(1, "insert into Account2 values (1, 'testBug1', 1)");
    clientSQLExecute(1, "insert into Account3 values (1, 'testBug1', 1)");
    clientSQLExecute(1, "insert into Account values (115, 'testBug2', 115)");
    serverSQLExecute(1, "insert into Account2 values (115, 'testBug2', 115)");
    serverSQLExecute(2, "insert into Account3 values (115, 'testBug2', 115)");

    // By this time the redundancy would be satisfied as the there are two VMs
    // now. Then start Server3 : This will have no buckets at this point
    startServerVMs(1, 0, null);

    // Now stop Server1 and then after some time Server2.
    // The data should be present on server 3. However, it should not be
    // present for table where recovery delay is disabled.
    stopVMNums(-1);
    Thread.sleep(3000);
    stopVMNums(-2);
    sqlExecuteVerify(new int[] { 1 }, new int[] { 3 },
        "select count(*) from Account", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 3 },
        "select count(*) from Account2", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 3 },
        "select count(*) from Account3", null, "2");
  }

  public void testStartupRecoveryDelay() throws Exception {
    // start a client and a server
    startVMs(1, 1);

    clientSQLExecute(1, "create table Account (id int primary key, "
        + "name varchar(100), type int) redundancy 1");
    clientSQLExecute(1, "create table Account2 (id int primary key, "
        + "name varchar(100), type int) redundancy 1 startuprecoverydelay -1");
    clientSQLExecute(1, "create table Account3 (id int primary key, "
        + "name varchar(100), type int) redundancy 1 startuprecoverydelay 1500");
    // This will create primaries on Server1
    serverSQLExecute(1, "insert into Account values (114, 'testBug1', 114)");
    serverSQLExecute(1, "insert into Account2 values (114, 'testBug1', 114)");
    clientSQLExecute(1, "insert into Account3 values (114, 'testBug1', 114)");
    serverSQLExecute(1, "insert into Account values (2, 'testBug2', 2)");
    serverSQLExecute(1, "insert into Account2 values (2, 'testBug2', 2)");
    clientSQLExecute(1, "insert into Account3 values (2, 'testBug2', 2)");
    
    startServerVMs(1, 0, null);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account2", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from Account3", null, "2");

    // stop both server1 after some time.
    Thread.sleep(3000);
    stopVMNums(-1);

    // The data should be present on server 2. However, it should not be
    // present for table where startup recovery delay is disabled.
    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select count(*) from Account", null, "2");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select count(*) from Account2", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select count(*) from Account3", null, "2");
  }

  public void testSelects() throws Exception {
    sop("testSelects started");
    startServerVMs(4, 0, null);

    serverSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " partition by range(id)" + "   ( values between 0  and 3"
        + "    ,values between 3  and 8" + "    ,values between 8  and 12"
        + "    ,values between 12  and 17" + "    ,values between 17  and 30"
        + "   ) redundancy 1");

    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(null, new int[] { 1, 2, 3, 4 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
    int serverVmToDisconnect = 3;
    this.serverVMs.get(serverVmToDisconnect).invoke(GemFireXDHADUnit.class,
        "disconnect");

    this.serverVMs.get(2).invoke(GemFireXDHADUnit.class, "fireSelects");
    sop("testSelects ended");
    removeExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
  }

  public void runUpdateDMLsIncludingOnClient(final String testName,
      final String dmlMethodName, int[] changeValues5) throws Exception {

    sop(testName + " started");
    final Method dmlMethod = this.getClass().getMethod(dmlMethodName,
        Integer.class, Boolean.class, Integer.class, java.sql.Connection.class);
    dmlMethod.setAccessible(true);

    startServerVMs(4, 0, null);
    // start network server on the VM that will be shutdown
    final int netPort4 = startNetworkServer(4, null, null);
    // start another network server for failover
    final int netPort2 = startNetworkServer(2, null, null);
    // attach the listener to the network servers
    attachConnectionListener(4, connListener);
    attachConnectionListener(2, connListener);

    final Connection conn = TestUtil.getNetConnection(netPort4, null, null);
    // TODO: TX: only valid for non-transactional ops for now
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    conn.setAutoCommit(false);
    conn.createStatement().execute(
        "create table Account ("
            + " id int primary key, name varchar(100), type int )"
            + " partition by range(id)"
            + "   ( values between 0  and 3"
            + "    ,values between 3  and 8"
            + "    ,values between 8  and 12"
            + "    ,values between 12  and 17"
            + "    ,values between 17  and 30"
            + "   ) redundancy 1");

    // check the number of connections
    assertNumConnections(-2, 0, 4); // 1 control connection + 1 data connection
    assertNumConnections(-1, -1, 2);

    serverExecute(1, new SerializableRunnable() {
      @Override
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          // TODO: TX: only valid for non-transactional ops for now
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          conn.setAutoCommit(false);
          Statement stmt = conn.createStatement();
          for (int i = 1; i < 21; ++i) {
            stmt.executeUpdate("insert into Account values (" + i
                + ", 'Account " + i + "'," + (i % 2) + " )");
          }
        } catch (SQLException sqle) {
          fail("unexpected exception in insert", sqle);
        }
      }
    });

    // Runnable to wait for rebalancing on the new servers to complete, else
    // stopping of another server can result in data loss
    final SerializableRunnable rebalanceWait = new SerializableRunnable() {
      @Override
      public void run() {
        try {
          final ResourceManager rm = Misc.getGemFireCache()
              .getResourceManager();
          for (RebalanceOperation op : rm.getRebalanceOperations()) {
            op.getResults();
          }
          rm.createRebalanceFactory().start().getResults();
          for (RebalanceOperation op : rm.getRebalanceOperations()) {
            op.getResults();
          }
        } catch (Exception ex) {
          fail("unexpected exception", ex);
        }
      }
    };

    // Part 1: synchronously stop and see if failover succeeds
    addExpectedException(null, new int[] { 1, 2, 3, 4 }, new Object[] {
        ShutdownException.class, CacheClosedException.class,
        DistributedSystemDisconnectedException.class });
    addExpectedException(null, new Object[] { DisconnectException.class,
        SQLNonTransientConnectionException.class });

    int serverVmToDisconnect = 4;
    this.serverVMs.get(serverVmToDisconnect - 1).invoke(TestUtil.class,
        "shutDown");
    // start another network server where the client will failover later
    final int netPort1 = startNetworkServer(1, null, null);
    attachConnectionListener(1, connListener);

    dmlMethod.invoke(this, netPort2, Boolean.FALSE, changeValues5[0], null);
    // check the connections
    assertNumConnections(-2, 0, 4);
    // 1 control conn on locator + 1 data conn + data connection closed
    // no close message is sent rather only closed on client which maybe
    // detected by server after sometime during the read
    assertNumConnections(-3, -2, 2);
    assertNumConnections(-2, -2, 1);

    sop(testName + " part1 ended");

    // wait for rebalancing to finish
    serverExecute(1, rebalanceWait);
    serverExecute(2, rebalanceWait);
    serverExecute(3, rebalanceWait);

    // Part 2: synchronously stop and see if failover succeeds to new network
    // servers started later
    serverVmToDisconnect = 2;
    this.serverVMs.get(serverVmToDisconnect - 1).invoke(TestUtil.class,
        "shutDown");
    // start another network server where the client will failover later
    startNetworkServer(3, null, null);
    attachConnectionListener(3, connListener);

    dmlMethod.invoke(this, netPort1, Boolean.FALSE, changeValues5[1], null);
    assertNumConnections(-3, 0, 4);
    assertNumConnections(-3, -3, 2);
    // 1 control connection + 1 data connection + data connection closed
    assertNumConnections(-4, -3, 1);
    assertNumConnections(-2, -3, 3);

    sop(testName + " part2 ended");

    // wait for rebalancing to finish
    serverExecute(1, rebalanceWait);
    serverExecute(3, rebalanceWait);

    // Part 3: check if failover succeeds on an old connection
    dmlMethod.invoke(this, netPort4, Boolean.FALSE, changeValues5[2], conn);
    assertNumConnections(-3, 0, 4);
    // we expect connection close to be detected now that server is down
    assertNumConnections(-3, -3, 2);
    // 1 data connection
    assertNumConnections(-6, -5, 1, 3);

    sop(testName + " part3 ended");

    restartVMNums(new int[] { -2, -4 }, 0, null, null);

    // wait for rebalancing to finish
    serverExecute(2, rebalanceWait);
    serverExecute(4, rebalanceWait);

    serverVmToDisconnect = 1;
    this.serverVMs.get(serverVmToDisconnect - 1).invoke(GemFireXDHADUnit.class,
        "disconnect");

    dmlMethod.invoke(this, netPort1, Boolean.FALSE, changeValues5[3], null);
    assertNumConnections(-3, 0, 4);
    assertNumConnections(-3, -3, 2);
    // 1 data connection to vm1 opened and then closed by server
    // 1 new control connection (optional since the control connection to vm1
    // may work) + 1 data connection + data connection closed to vm3
    // number of closed connections can be 3 or 4 depending on whether the
    // close was registered on the server going down
    // negative value indicates that number of connections should be <= limit
    assertNumConnections(-9, -6, 1, 3);

    // closing a now "invalid" connection should be fine and cause no failover
    conn.close();
    assertNumConnections(-3, 0, 4);
    assertNumConnections(-3, -3, 2);
    assertNumConnections(-9, -7, 1, 3);

    sop(testName + " part4 ended");

    // Last part: check normal HA for server-side operations

    // wait for rebalancing to finish
    serverExecute(2, rebalanceWait);
    serverExecute(4, rebalanceWait);

    serverVmToDisconnect = 3;
    this.serverVMs.get(serverVmToDisconnect - 1).invoke(GemFireXDHADUnit.class,
        "disconnect");

    this.serverVMs.get(1).invoke(GemFireXDHADUnit.class, dmlMethodName,
        new Object[] { null, Boolean.FALSE, changeValues5[4], null });

    sop(testName + " ended");

    removeExpectedException(null, new Object[] { DisconnectException.class,
        SQLNonTransientConnectionException.class });
    removeExpectedException(null, new int[] { 2, 4 }, new Object[] {
        ShutdownException.class, CacheClosedException.class,
        DistributedSystemDisconnectedException.class });
  }

  public void testUpdatesIncludingOnClient() throws Exception {
    runUpdateDMLsIncludingOnClient("testUpdatesIncludingOnClient",
        "fireUpdates", new int[] { 1, 2, 3, 4, 5 });
  }

  public void __testUpdatesVerify() throws Exception {
    startServerVMs(4, 0, null);

    serverSQLExecute(1,
        "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " partition by range(id)"
        + "   ( values between 0  and 3"
        + "    ,values between 3  and 8"
        + "    ,values between 8  and 12"
        + "    ,values between 12  and 17"
        + "    ,values between 17  and 30"
        + "   ) redundancy 1");

    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(null, new int[] { 1, 2, 3, 4 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
    int serverVmToDisconnect = 3;
    this.serverVMs.get(serverVmToDisconnect).invoke(GemFireXDHADUnit.class,
        "disconnect");
    this.serverVMs.get(2).invoke(GemFireXDHADUnit.class, "fireUpdates",
        new Object[] { null, Boolean.TRUE, Integer.valueOf(1), null });
    removeExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
  }

  public void testDeletesIncludingOnClient() throws Exception {
    runUpdateDMLsIncludingOnClient("testDeletesIncludingOnClient",
        "fireDeletes", new int[] { 15, 12, 8, 5, 2 });
  }

  public void __testDeletesVerify() throws Exception {
    startServerVMs(4, 0, null);

    serverSQLExecute(1,
        "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " partition by range(id)"
        + "   ( values between 0  and 3"
        + "    ,values between 3  and 8"
        + "    ,values between 8  and 12"
        + "    ,values between 12  and 17"
        + "    ,values between 17  and 30"
        + "   ) redundancy 1");

    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(null, new int[] { 1, 2, 3, 4 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
    int serverVmToDisconnect = 3;
    this.serverVMs.get(serverVmToDisconnect).invoke(GemFireXDHADUnit.class,
        "disconnect");

    this.serverVMs.get(2).invoke(GemFireXDHADUnit.class, "fireDeletes",
        new Object[] { null, Boolean.TRUE, Integer.valueOf(5), null });
    removeExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
  }

  public void testInsertsIncludingOnClient() throws Exception {
    
    // Bug #51833
    if (isTransactional) {
      return;
    }
    
    runUpdateDMLsIncludingOnClient("testInsertsIncludingOnClient",
        "fireInserts", new int[] { 21, 26, 32, 36, 52 });
  }

  /**
   * Test for member going down during EndBucketCreationMessage send from
   * primary elect (bug #42429).
   */
  public void testPKHang42429() throws Exception {
    // start some servers and use this VM as accessor
    startVMs(1, 2);

    serverSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " partition by range(id)"
        + "   ( values between 0  and 3"
        + "    ,values between 3  and 8"
        + "    ,values between 8  and 12"
        + "    ,values between 12  and 17"
        + "    ,values between 17  and 30"
        + "   ) redundancy 1");


    // PRHARP.EndObserver to wait after sending EndBucketCreationMessage
    final int serverVmToDisconnect = 1;
    final VM serverVM = this.serverVMs.get(serverVmToDisconnect - 1);
    serverVM.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PRHARedundancyProvider.setTestEndBucketCreationObserver(
            new PRHARedundancyProvider.EndBucketCreationObserver() {
              @Override
              public void afterEndBucketCreationMessageSend(
                  PartitionedRegion pr, int bucketId) {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException ie) {
              // this is expected due to shutdown; don't set the interrupted
              // flag else subsequent tests will fail due to main thread
              // in interrupted state
            } finally {
              PRHARedundancyProvider
                  .setTestEndBucketCreationObserver(null);
            }
          }

          @Override
          public void afterEndBucketCreation(PartitionedRegion pr,
              int bucketId) {
          }
        });
      }
    });

    // Now invoke inserts asynchronously from that VM to create buckets
    AsyncInvocation async1 = serverVM.invokeAsync(new SerializableRunnable() {
      @Override
      public void run() {
        try {
          for (int i = 1; i < 3; ++i) {
            Statement stmt = TestUtil.getStatement();
            stmt.execute("insert into Account values (" + i + ", 'Account " + i
                + "'," + (i % 2) + " )");
          }
        } catch (Exception ex) {
          // ignored
        }
      }
    });

    // Shutdown the VM in parallel
    AsyncInvocation async2 = serverVM.invokeAsync(new SerializableRunnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(3000);
          TestUtil.shutDown();
        } catch (Exception ex) {
          // ignored
        }
      }
    });

    // Some inserts from the VM that is alive in parallel
    this.serverVMs.get(1).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        try {
          for (int i = 3; i < 8; ++i) {
            Statement stmt = TestUtil.getStatement();
            executeUpdate(stmt, "insert into Account values (" + i
                + ", 'Account " + i + "'," + (i % 2) + " )");
          }
        } catch (Exception ex) {
          // ignored
        }
      }
    });

    // Now force iteration over all buckets from the alive VM
    this.serverVMs.get(1).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        try {
          Statement stmt = TestUtil.getStatement();
          executeUpdate(stmt, "update Account set type=2");
        } catch (SQLException sqle) {
          throw new RuntimeException(sqle);
        }
      }
    });

    async1.join();
    async2.join();
  }

  public void testNoNewNodeParticipationPR() throws Exception {
    startVMs(1, 3);
    clientSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " partition by range(id)" + "   ( values between 0  and 3"
        + "    ,values between 3  and 8" + "    ,values between 8  and 12"
        + "    ,values between 12  and 17" + "    ,values between 17  and 30"
        + "   ) redundancy 1");
    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
    for (int i = 0; i < 100; i++) {
      String createStmnt = "create table testTable" + i + "(id" + i
          + " int, name" + i + " varchar(100))";
      clientSQLExecute(1, createStmnt);
    }
    List<AsyncInvocation> ainvoke = new ArrayList<AsyncInvocation>();
    sop("client vm size = " + this.clientVMs.size());
    selectQueryAndExecute(this.clientVMs.get(0), ainvoke);
    disconnectAndConnectAServerVM(2, 10, false, ainvoke);
    for (int i = 0; i < ainvoke.size(); i++) {
      joinAsyncInvocation(ainvoke.get(i), 1200 * 1000);
    }
    for (int i = 0; i < ainvoke.size(); i++) {
      if (ainvoke.get(i).exceptionOccurred()) {
        fail("exception during " + i, ainvoke.get(i).getException());
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class });
  }

  public void testNoNewNodeParticipationDR() throws Exception {
    startVMs(1, 3);
    clientSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " replicate");
    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class });
    for (int i = 0; i < 100; i++) {
      String createStmnt = "create table testTable" + i + "(id" + i
          + " int, name" + i + " varchar(100))";
      clientSQLExecute(1, createStmnt);
    }
    List<AsyncInvocation> ainvoke = new ArrayList<AsyncInvocation>();
    sop("client vm size = "+this.clientVMs.size());
    selectQueryAndExecute(this.clientVMs.get(0), ainvoke);
    disconnectAndConnectAServerVM(2, 10, false, ainvoke);
    for (int i = 0; i < ainvoke.size(); i++) {
      joinAsyncInvocation(ainvoke.get(i), 1200 * 1000);
    }
    for (int i = 0; i < ainvoke.size(); i++) {
      if (ainvoke.get(i).exceptionOccurred()) {
        fail("exception during " + i, ainvoke.get(i).getException());
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class });
  }

  public void test41261() throws Exception {
    // start a client and three servers
    startVMs(1, 4);

    // create a couple of tables to have referenced keys
    clientSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int ) redundancy 2");
    clientSQLExecute(1, "create table AccountRef ("
        + " id int primary key, name varchar(100), aid int,"
        + " foreign key (aid) references Account(id)) redundancy 2");

    for (int i = 1; i <= 40; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    for (int i = 1; i <= 40; ++i) {
      serverSQLExecute(1, "insert into AccountRef values (" + i + ", 'Account "
          + i + "'," + i + " )");
    }

    // now fire a bunch of deletes while bringing down a couple of servers
    PreparedStatement pstmt1 = TestUtil
        .getPreparedStatement("delete from Account where id=?");
    PreparedStatement pstmt2 = TestUtil
        .getPreparedStatement("delete from AccountRef where id=?");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        ShutdownException.class, CacheClosedException.class,
        FunctionException.class,
        "java.sql.SQLIntegrityConstraintViolationException",
        ShutdownException.class, CacheClosedException.class });

    // fire a few deletes upfront
    for (int i = 1; i <= 2; ++i) {
      getLogWriter().info("Deleting id " + i + " in AccountRef");
      pstmt2.setInt(1, i);
      assertEquals(1, pstmt2.executeUpdate());
      getLogWriter().info("Deleting id " + i + " in Account");
      pstmt1.setInt(1, i);
      assertEquals(1, pstmt1.executeUpdate());
    }

    // bring down two servers asynchronously
    List<AsyncInvocation> ainvoke = new ArrayList<AsyncInvocation>();
    final VM serverVM1 = this.serverVMs.get(0);
    final VM serverVM2 = this.serverVMs.get(2);
    ainvoke.add(serverVM1.invokeAsync(TestUtil.class, "shutDown"));
    ainvoke.add(serverVM2.invokeAsync(TestUtil.class, "shutDown"));

    // fire the deletes in parallel

    // first successful deletes
    Statement stmt1 = TestUtil.getStatement();
    Statement stmt2 = TestUtil.getStatement();
    for (int i = 3; i <= 20; ++i) {
      getLogWriter().info("Deleting id " + i + " in AccountRef");
      assertEquals(1,
          executeUpdate(stmt2, "delete from AccountRef where id=" + i));
      assertEquals(1,
          executeUpdate(stmt1, "delete from Account where id=" + i));
    }
    // next some deletes that should throw constraint violation exceptions
    for (int i = 21; i <= 40; ++i) {
      getLogWriter().info("Deleting id " + i + " in Account");
      pstmt1.setInt(1, i);
      try {
        executeUpdate(pstmt1, null);
        fail("expected to throw FK violation");
      } catch (SQLException ex) {
        if (!"23503".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      getLogWriter().info("Deleting id " + i + " in AccountRef");
      pstmt2.setInt(1, i);
      executeUpdate(pstmt2, null);
    }
    // finally deletes that should fail with no deleted rows
    for (int i = 21; i <= 40; ++i) {
      getLogWriter().info("Deleting id " + i + " in AccountRef");
      pstmt2.setInt(1, i);
      assertEquals("expected delete to fail", 0, executeUpdate(pstmt2, null));
      getLogWriter().info("Deleting id " + i + " in Account");
      pstmt1.setInt(1, i);
      executeUpdate(pstmt1, null);
    }

    for (int index = 0; index < ainvoke.size(); ++index) {
      joinAsyncInvocation(ainvoke.get(index), 1200 * 1000);
    }
    for (int index = 0; index < ainvoke.size(); ++index) {
      if (ainvoke.get(index).exceptionOccurred()) {
        fail("exception during " + index, ainvoke.get(index).getException());
      }
    }

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class,
            FunctionException.class,
            "java.sql.SQLIntegrityConstraintViolationException" });
  }

  public static void disconnect() {
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {

      @Override
      public void beforeQueryExecutionByPrepStatementQueryExecutor(
          GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt,
          String query) {
        doShutDown();
      }

      @Override
      public void beforeQueryExecutionByStatementQueryExecutor(
          GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
        doShutDown();
      }

      @Override
      public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
          EntryEventImpl event, RegionEntry entry) {
        doShutDown();
      }

      private void doShutDown() {
        // shutdown in a separate thread else this thread will block the
        // shutdown of this VM at the end
        new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              final Properties shutdownProperties = new Properties();
              shutdownProperties.setProperty(FabricService.STOP_NETWORK_SERVERS,
                  "false");
              TestUtil.shutDown(shutdownProperties);
            } catch (SQLException se) {
              fail("shutdown failed", se);
            }
          }
        }).start();
        // wait for sometime
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          // ignore
        }
        throw new DistributedSystemDisconnectedException(
            "throwing disconnected exception for test after shutdown");
        // Neeraj: Just throwing CacheClosedException does not work due to bug
        // #40612 and #40481
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
  }

  public static void dumpAll() {
    try {
      GfxdDumpLocalResultMessage msg = new GfxdDumpLocalResultMessage();
      InternalDistributedSystem sys = InternalDistributedSystem
          .getConnectedInstance();
      msg.send(sys, null);
      msg.executeLocally(sys.getDistributionManager(), false);
      GfxdDumpLocalResultMessage.sendBucketInfoDumpMsg(null, false);
    } catch (Throwable t) {
      throw new RuntimeException(t.getMessage());
    }
  }

  private static void fireOps(Integer clientPort, Integer verifyCnt,
      java.sql.Connection conn, String dmlStmt, String verifyStmt,
      final int verifyStmtCnt) {
    try {
      boolean doClose = false;
      if (conn == null) {
        if (clientPort != null && clientPort.intValue() > 0) {
          getGlobalLogger().info("creating new client connection for current VM");
          conn = TestUtil.getNetConnection(clientPort.intValue(), null, null);
          getGlobalLogger().info("created new client connection for current VM");
        }
        else {
          getGlobalLogger().info("creating new server connection for current VM");
          conn = TestUtil.getConnection();
          getGlobalLogger().info("created new server connection for current VM");
        }
        // TODO: TX: only valid for non-transactional ops for now
        conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
        conn.setAutoCommit(false);
        doClose = true;
      }
      assertEquals(Connection.TRANSACTION_NONE, conn.getTransactionIsolation());
      assertFalse(conn.getAutoCommit());
      getGlobalLogger().info("Executing DML: " + dmlStmt);
      // choose randomly between a prepared statement or normal one
      final boolean usePrepStatement = PartitionedRegion.rand.nextBoolean();
      int cnt = -1;
      PreparedStatement ps;
      final Statement stmt = conn.createStatement();
      if (usePrepStatement) {
        ps = conn.prepareStatement(dmlStmt);
        if (!ps.execute()) {
          cnt = ps.getUpdateCount();
        }
        ps.close();
      }
      else {
        if (!stmt.execute(dmlStmt)) {
          cnt = stmt.getUpdateCount();
        }
      }
      if (verifyCnt != null) {
        assertEquals(verifyCnt.intValue(), cnt);
      }
      // verify that change has actually taken place correctly
      if (verifyStmt != null) {
        getGlobalLogger().info("Verifying with DML: " + verifyStmt);
        ProcessResultSet<Object> verifyResult = new ProcessResultSet<Object>() {
          @Override
          public Object process(final ResultSet rs) throws SQLException {
            assertTrue(rs.next());
            if (verifyStmtCnt != rs.getInt(1)) {
              // if we don't get expected results then dump contents
              // try on all VMs until we succeed
              try {
                final Host host = Host.getHost(0);
                for (int vmNum = 0; vmNum <= host.getVMCount(); ++vmNum) {
                  try {
                    final VM vm = host.getVM(vmNum);
                    vm.invoke(GemFireXDHADUnit.class, "dumpAll");
                    break;
                  } catch (Throwable t) {
                    // ignore
                  }
                }
              } catch (Throwable t) {
                // ignore
              }
              fail("Expected " + verifyStmtCnt + " results but got "
                  + rs.getInt(1));
            }
            assertFalse(rs.next());
            return null;
          }
        };
        runSelects(conn, stmt, verifyStmt, verifyResult);
        // check with prepared statement too
        ps = conn.prepareStatement(verifyStmt);
        runSelects(conn, ps, null, verifyResult);
        ps.close();
      }

      // cleanup stuff
      stmt.close();
      if (doClose) {
        getGlobalLogger().info("closing the new connection");
        conn.close();
        getGlobalLogger().info("closed the new connection");
      }
    } catch (SQLException e) {
      fail("unexpected exception occured in fireOps: ", e);
    }
  }

  public static void fireUpdates(Integer clientPort, Boolean doVerifyCnt,
      Integer startCnt, java.sql.Connection conn) {
    if (startCnt == null) {
      startCnt = Integer.valueOf(1);
    }
    fireOps(clientPort, (doVerifyCnt ? Integer.valueOf(10) : null), conn,
        "update Account set type=" + (startCnt + 1) + " where type=" + startCnt,
        "select count(*) from Account where type=" + (startCnt + 1), 10);
  }

  public static void fireDeletes(Integer clientPort, Boolean doVerifyCnt,
      Integer lastCnt, java.sql.Connection conn) {
    fireOps(clientPort, (doVerifyCnt ? Integer.valueOf(20 - lastCnt) : null),
        conn, "delete from Account where id > " + lastCnt.intValue(),
        "select count(*) from Account", lastCnt);
  }

  public static void fireInserts(Integer clientPort, Boolean doVerifyCnt,
      Integer startCnt, java.sql.Connection conn) {
    StringBuilder insertStmt = new StringBuilder("insert into Account values");
    // one or two inserts at a time to test both put and putAll
    final int numInserts = PartitionedRegion.rand.nextInt(2) + 1;
    for (int i = startCnt; i < startCnt + numInserts; ++i) {
      if (i > startCnt) {
        insertStmt.append(',');
      }
      insertStmt.append(" (").append(i).append(",'Account ").append(i)
          .append("',").append(i % 2).append(')');
    }
    fireOps(clientPort, (doVerifyCnt ? Integer.valueOf(numInserts) : null),
        conn, insertStmt.toString(),
        "select count(*) from Account where id >= " + startCnt, numInserts);
  }

  public static void fireSelects() throws Exception {
    assertEquals(20, doSelects(1));
  }

  private static interface ProcessResultSet<T> {
    T process(final ResultSet rs) throws SQLException;
  }

  private static int doSelects(int times) {
    int cnt = 0;
    try {
      final java.sql.Connection conn = TestUtil.getConnection();
      PreparedStatement ps = conn.prepareStatement("select * from Account");
      // now that streaming is enabled by default we need to be able to
      // cater to a user-level exception that denotes that a node is going
      // down and retry at application level
      for (int i = 1; i <= times; ++i) {
        cnt = runSelects(conn, ps, null, new ProcessResultSet<Integer>() {
          @Override
          public Integer process(ResultSet rs) throws SQLException {
            int cnt = 0;
            while (rs.next()) {
              ++cnt;
            }
            return Integer.valueOf(cnt);
          }
        });
      }
    } catch (SQLException e) {
      fail("unexpected exception occured in doSelects", e);
    }
    return cnt;
  }

  private static <T> T runSelects(Connection conn, Statement stmt, String sql,
      ProcessResultSet<T> processRS) throws SQLException {
    // now that streaming is enabled by default we need to be able to
    // cater to a user-level exception that denotes that a node is going
    // down and retry at application level
    for (;;) {
      final ResultSet rs;
      if (sql != null) {
        rs = stmt.executeQuery(sql);
      }
      else {
        rs = ((PreparedStatement)stmt).executeQuery();
      }
      try {
        return processRS.process(rs);
      } catch (SQLException ex) {
        if (!"X0Z01".equals(ex.getSQLState())) {
          throw ex;
        }
      } finally {
        rs.close();
      }
      // we should never reach here for non-streaming case
      if (!(conn instanceof EmbedConnection)
          || !((EmbedConnection)conn).getLanguageConnection()
              .streamingEnabled()) {
        fail("unexpected retry exception for non-streaming case");
      }
      // sleep a bit before retry
      try {
        Thread.sleep(500);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static void selectQueryAndExecute(VM vm, List<AsyncInvocation> ainvoke) {
    final SerializableRunnable createSelects = new SerializableRunnable(
        "createSelects") {
      @Override
      public void run() throws CacheException {
        doSelects(1000);
        //assertEquals(20, doSelects(1000));
      }
    };
    if (vm != null) {
      ainvoke.add(vm.invokeAsync(createSelects));
    } else {
      AsyncInvocation ai = new AsyncInvocation(createSelects, "run",
          new Runnable() {
            public void run() {
              createSelects.run();
            }
          });
      ai.start();
      ainvoke.add(ai);
    }
  }

  private static void sop(String s) {
    getGlobalLogger().info(s);
  }

  public static void disconnectVMs(List<VM> vms, List<AsyncInvocation> ainvoke) {
    SerializableRunnable disconnect = new SerializableRunnable(
        "disconnect") {
      @Override
      public void run() throws CacheException {
        try {
          TestUtil.shutDown();
        } catch (SQLException e) {
          throw new CacheException(e.toString()) {
          };
        }
      }
    };
    for (int i = 0; i < vms.size(); i++) {
      ainvoke.add(vms.get(i).invokeAsync(disconnect));
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        fail("unexpected exception occured in disconnectVMs: ", e);
      }
    }
  }

  @SuppressWarnings("deprecation")
  public static void assertEquals(int expected, int actual) {
    getGlobalLogger().info("comparing " + expected + " with actual " + actual);
    junit.framework.Assert.assertEquals(expected, actual);
  }
}
