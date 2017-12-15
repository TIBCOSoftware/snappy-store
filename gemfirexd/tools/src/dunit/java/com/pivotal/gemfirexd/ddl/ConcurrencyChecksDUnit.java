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

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InitialImageOperation;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionMap;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;

import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

@SuppressWarnings("serial")
public class ConcurrencyChecksDUnit extends DistributedSQLTestBase {

  public ConcurrencyChecksDUnit(String name) {
    super(name);
  }

//  @Override
//  protected String reduceLogging() {
//    return "finer";
//  }
//  
  
  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();

    invokeInEveryVM(ConcurrencyChecksDUnit.class, "nullGIIRequestsTestLatch");
    nullGIIRequestsTestLatch();
    
    invokeInEveryVM(ConcurrencyChecksDUnit.class, "nullGIIProcessingTestLatch");
    nullGIIProcessingTestLatch();
    
    invokeInEveryVM(ConcurrencyChecksDUnit.class,
        "nullApplyAllSuspectsTestLatch");
    nullApplyAllSuspectsTestLatch();
  }

  public void testSuspects() throws Exception {
    // The test is not valid for transactions
    if (isTransactional) {
      return;
    }
    startVMs(1, 1);
    Connection conn = TestUtil.getConnection();
    String ddl = "create table APP.TEST_DIST("
        + "c1 int not null primary key, c2 int not null) replicate";
    runSuspects(conn, ddl);
  }

  public void testSuspects_REPLICATED_PERSISTENT() throws Exception {
    // The test is not valid for transactions
    if (isTransactional) {
      return;
    }
    startVMs(1, 1);
    Connection conn = TestUtil.getConnection();
    String ddl = "create table APP.TEST_DIST("
        + "c1 int not null primary key, c2 int not null) replicate persistent";
    runSuspects(conn, ddl);
  }

  private void runSuspects(Connection conn, String createTableDdl)
      throws Exception {
    Statement s = conn.createStatement();
    s.execute(createTableDdl);
    s.execute("alter table APP.TEST_DIST "
        + "add constraint const_uk unique (c2)");

    s.execute("insert into APP.TEST_DIST values(1, 1)");

    VM serverVM = getServerVM(1);
    getLogWriter().info("Going to suspend GII requests in vm: " + serverVM);
    serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Object[] {
        Boolean.TRUE, "/APP/TEST_DIST" });
    AsyncVM async1;
    try {
      async1 = invokeStartServerVM(1, 0, null, null);

      // wait for GII to start
      waitForCriterion(new WaitCriterion() {
        @Override
        public boolean done() {
          VM serverVM = getServerVM(1);
          return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
              "testLatchWaiting"));
        }

        @Override
        public String description() {
          return "waiting for GII to start";
        }
      }, 180000, 500, true);

      try {
        s.execute("insert into APP.TEST_DIST values(2, 1)");
        fail("insert should have failed");
      } catch (SQLException se) {
        getLogWriter().info(
            "failure sqlstate is on dup insert: " + se.getSQLState(), se);
        assertTrue("23505".equalsIgnoreCase(se.getSQLState()));
      }
      s.execute("update APP.TEST_DIST set c2 = 5 where c2 = 1");

      // wait for list to become size 2
      waitForCriterion(new WaitCriterion() {
        @Override
        public boolean done() {
          VM newVM = null;
          newVM = Host.getHost(0).getVM(1);
          getLogWriter().info("Going to call testMapSize on vm: " + newVM);
          return Boolean.TRUE.equals(newVM.invoke(ConcurrencyChecksDUnit.class,
              "testMapSize",
              new Object[] { "/APP/TEST_DIST", Integer.valueOf(2) }));
        }

        @Override
        public String description() {
          return "waiting for suspect events to queue up";
        }
      }, 180000, 500, true);

    } finally {
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
          Boolean.FALSE, null });
    }
    joinVM(true, async1);

    serverVM.invoke(ConcurrencyChecksDUnit.class, "nullGIIRequestsTestLatch");

    final int netPort2 = startNetworkServer(2, null, null);
    String url2 = TestUtil.getNetProtocol("localhost", netPort2);
    Connection conn2 = DriverManager.getConnection(url2,
        TestUtil.getNetProperties(new Properties()));

    Statement s2 = conn2.createStatement();
    s2.execute("select * from APP.TEST_DIST");

    ResultSet rs = s2.getResultSet();
    assertTrue(rs.next());

    assertEquals(5, rs.getInt(2));
    assertFalse(rs.next());

    s.execute("drop table APP.TEST_DIST");
  }
  
  public static Boolean testMapSize(String region, Integer size) {
    try {
      Cache c = Misc.getGemFireCacheNoThrow();
      if (c == null) {
        return false;
      }
      LocalRegion lr = (LocalRegion)Misc.getRegionByPath(region, false);
      if (lr == null) {
        return false;
      }
      RegionMap arm = lr.entries;
      if (arm == null) {
        return false;
      }
      final Map<?, ?> susMap = arm.getTestSuspectMap();
      int sz = susMap != null ? susMap.size() : 0;
      return sz == size.intValue();
    } catch (Throwable t) {
      // ignore
    }
    return false;
  }

  public static void suspendAllGIIRequests(Boolean giistop, String testRegionName) {
    if (giistop) {
      InitialImageOperation.RequestImageMessage.testLatch = new CountDownLatch(1);
      InitialImageOperation.RequestImageMessage.testLatchWaiting = false;
      InitialImageOperation.RequestImageMessage.TEST_REGION_NAME = testRegionName;
    }
    else {
      InitialImageOperation.RequestImageMessage.testLatch.countDown();
    }
  }
  
  public static void nullGIIRequestsTestLatch() {
      InitialImageOperation.RequestImageMessage.testLatch = null;
      InitialImageOperation.RequestImageMessage.testLatchWaiting = false;
      InitialImageOperation.RequestImageMessage.TEST_REGION_NAME = null;
  }
  
  public static void suspendAllGIIProcessing(Boolean giistop, String testRegionName) {
    if (giistop) {
      InitialImageOperation.imageProcessorTestLatch = new CountDownLatch(1);
      InitialImageOperation.imageProcessorTestLatchWaiting = false;
      InitialImageOperation.imageProcessorTEST_REGION_NAME = testRegionName;
    }
    else {
      InitialImageOperation.imageProcessorTestLatch.countDown();
    }
  }
  
  public static void nullGIIProcessingTestLatch() {
      InitialImageOperation.imageProcessorTestLatch = null;
      InitialImageOperation.imageProcessorTestLatchWaiting = false;
      InitialImageOperation.imageProcessorTEST_REGION_NAME = null;
  }

  public static Boolean testLatchWaiting() {
    return Boolean
        .valueOf(InitialImageOperation.RequestImageMessage.testLatchWaiting);
  }
  
  public static Boolean testLatchWaitingGIIComplete() {
    return Boolean.valueOf(DistributedRegion.testLatchWaiting);
  }
  
  public static Boolean testLatchWaitingGIIProcess() {
    return Boolean
        .valueOf(InitialImageOperation.imageProcessorTestLatchWaiting);
  }
  
  public static void suspendApplyAllSuspects(Boolean doSuspend,
      String testRegionName) {
    if (doSuspend) {
      LocalRegion.applyAllSuspectTestLatch = new CountDownLatch(1);
      LocalRegion.applyAllSuspectTestLatchWaiting = false;
      LocalRegion.applyAllSuspectTEST_REGION_NAME = testRegionName;
    }
    else {
      LocalRegion.applyAllSuspectTestLatch.countDown();
    }
  }

  public static void nullApplyAllSuspectsTestLatch() {
    LocalRegion.applyAllSuspectTestLatch = null;
    LocalRegion.applyAllSuspectTestLatchWaiting = false;
    LocalRegion.applyAllSuspectTEST_REGION_NAME = null;
  }
  
  public static Boolean testLatchWaitingApplyAllSuspects() {
    return Boolean.valueOf(LocalRegion.applyAllSuspectTestLatchWaiting);
  }

  public void testBug48315() throws Exception {
    // Start one client and two servers
    // We intend to bring the second server down and when it restarts
    // we want to increase the time period between applyAllSuspects() finishing
    // and the region being marked as initialized, and in that time period
    // we want to fire inserts on the first server and let the distribute happen
    // to second server.  At the end the second server should have the same rows.
    
    getLogWriter().info("Starting one client and two servers");
    startVMs(1, 2);
    
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    getLogWriter().info("Inserting data");

    st.execute("create table APP.T1_BUGTEST(id int primary key, name varchar(20), address varchar(50)) persistent replicate");
    st.execute("insert into APP.T1_BUGTEST values(1, 'name1', 'addr1')");
    st.execute("insert into APP.T1_BUGTEST values(2, 'name2', 'addr2')");
    st.execute("insert into APP.T1_BUGTEST values(3, 'name3', 'addr3')");
    st.execute("insert into APP.T1_BUGTEST values(4, 'name4', 'addr4')");

    getLogWriter().info("Stopping second server");
    
    VM vm = getServerVM(2);
    
    stopVMNums(-2);

    // Some more inserts
    st.execute("insert into APP.T1_BUGTEST values(9, 'name9', 'addr9')");
    st.execute("insert into APP.T1_BUGTEST values(10, 'name10', 'addr10')");
    
    getLogWriter().info("Installing latch on second server");
    
    vm.invoke(new SerializableRunnable("") {
      @Override
      public void run() throws CacheException {
        DistributedRegion.testLatch = new CountDownLatch(1);
        DistributedRegion.testRegionName = "T1_BUGTEST";
      }
    });
    
    
    getLogWriter().info("Restarting second server async");
    AsyncVM asyncVM = restartServerVMAsync(2, 0, null, null);

    getLogWriter().info("Waiting until the test latch point arrives");
    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        VM serverVM = getServerVM(2);
        return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
            "testLatchWaitingGIIComplete"));
      }

      @Override
      public String description() {
        return "waiting for applyAllSuspects to finish";
      }
    }, 180000, 500, true);
    
    getLogWriter().info("Doing in-flight inserts");

    // Fire more inserts
    st.execute("insert into APP.T1_BUGTEST values(5, 'name5', 'addr5')");
    st.execute("insert into APP.T1_BUGTEST values(6, 'name6', 'addr6')");
    st.execute("insert into APP.T1_BUGTEST values(7, 'name7', 'addr7')");
    st.execute("insert into APP.T1_BUGTEST values(8, 'name8', 'addr8')");
    
    getLogWriter().info("Releasing the test latch");

    // Release the latch so that the GII finishes.
    this.getServerVM(2).invoke(new SerializableRunnable("") {
      @Override
      public void run() throws CacheException {
        DistributedRegion.testLatch.countDown();
        DistributedRegion.testLatch = null;
        DistributedRegion.testLatchWaiting = false;
        DistributedRegion.testRegionName = null;
      }
    });
    
    getLogWriter().info("Waiting for the second server to restart completely");

    joinVM(true, asyncVM);
    
    // Verify that the second server does have the in-flight inserts we just fired.
    // Query the second server directly since the table is replicated and the query
    // shouldn't go elsewhere.

    getLogWriter().info("Verifying data on the second server");
    
    String goldenTextFile = TestUtil.getResourcesDir() + "/lib/checkQuery.xml";

    sqlExecuteVerify(null, new int[] {2}, "select * from APP.T1_BUGTEST where id > 4 and id < 9", goldenTextFile, "result_t1");
    
  }
  
  // tests for #48092 
    // Also added #47945 part 2 (update fired during
    // GII results in inconsistent index. Old key also continues
    // to exist in the index) 
    public void testBug48092_47945() throws Exception {
  //    Properties props = new Properties();
  //    props.setProperty("gemfirexd.debug.true", "TraceIndex");
      startVMs(1, 2);
  //    startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();
  
      Statement s = conn.createStatement();
      s.execute("create table APP.T2_48092("
          + "c1 int not null primary key, c2 int not null) "
          + "replicate persistent SYNCHRONOUS");
      s.execute("create index APP.INDEX1 on APP.T2_48092 (c2)"); 
      s.execute("insert into APP.T2_48092 values(1, 1)");
      
      stopVMNum(-2);
      
      // pause the GII request for T2_48092 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Object[] {
          Boolean.TRUE, "/APP/T2_48092" });
  
      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, null);
  //      restartServerVMAsync(2, 0, null, props);
  
        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
                "testLatchWaiting"));
          }
  
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
        
        // execute an update query while the GII is paused 
        // #48092: should not throw an exception
        s.execute("update APP.T2_48092 set c2 = 2");
        s.execute("update APP.T2_48092 set c2 = 3");
      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
            Boolean.FALSE, null });
      }
      
      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);
  
      s.execute("select count(*) from APP.T2_48092");
      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
      
      // this query should use index scan
      // #47945: count(*) should be 1
      s.execute("select count(*) from APP.T2_48092 where c2 > 0");
      r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
      
      // verify the contents 
      s.execute("select * from APP.T2_48092 where c2 > 0");
      ResultSet r2 = s.getResultSet();
      assertTrue(r2.next());
      assertEquals(1, r2.getInt(1));
      assertEquals(3, r2.getInt(2));
      assertFalse(r2.next());
    }

    // #47945_1: an update is fired while a node in the DS is down. When
    // the node is back up again it adds the new key due to the update, but
    // did not remove the old key. 
    public void testBug47945_1() throws Exception {
      // Start one client and three servers
      startVMs(1, 3);
    
      clientSQLExecute(1, "create schema trade");
    
      // Create a table
      clientSQLExecute(
          1,
          "create table trade.portfolio (cid int not null, sid int not null, "
          + "qty int not null, availQty int not null, subTotal int, "
          + "tid int, constraint portf_pk primary key (cid, sid), "
          + "constraint qty_ck check (qty>=0), "
          + "constraint avail_ch check (availQty>=0 and availQty<=qty))  "
          + "partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), "
          + "VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))  "
          + "REDUNDANCY 2 PERSISTENT SYNCHRONOUS");
    
      // create an index
      clientSQLExecute(1,
          "create index trade.index_1 on portfolio (subTotal asc)");
    
      Connection conn = TestUtil.getConnection();
      PreparedStatement ps = conn.prepareStatement(
          "insert into trade.portfolio values(?, ?, ?, ?, ?, ?)");
    
      for (int i = 1; i <= 17; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.setInt(3, i * 10);
        ps.setInt(4, i * 10 - 2);
        ps.setInt(5, i*1000);
        ps.setInt(6, i);
        assertEquals(1, ps.executeUpdate());
      }
      conn.commit();
    
      Statement st = conn.createStatement();
      // stop a VM
      stopVMNums(-3);
    
      // change subtotal to 12001 from 12000 while a VM is down
      st.execute("update trade.portfolio set subtotal = subtotal + 1 where" +
          " cid = 12 and sid = 12 and tid = 12");
      conn.commit();
      // when the VM comes up it should get the above update
      restartVMNums(-3);
    
      // count should be 0
      sqlExecuteVerify(
          null,
          new int[] { 3 },
          "select count(*) as count from trade.portfolio where subtotal = 12000" +
          " and tid = 12",
          null,
          "0");
    
      // count should be 1
      sqlExecuteVerify(
          null,
          new int[] { 3 },
          "select count(*) as count from trade.portfolio where subtotal = 12001" +
          " and tid = 12",
          null,
          "1");
    
      // verify the actual data
      sqlExecuteVerify(
          null,
          new int[] { 3 },
          "select cid, sid, subtotal from trade.portfolio where subtotal = 12001" +
          " and tid = 12",
          TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
          "testBug47945");
    }

    // Also added #47945 part 2 (update fired during
    // GII results in inconsistent index. Old key also continues
    // to exist in the index)
    public void testBug47945_2() throws Exception {
      //    Properties props = new Properties();
      //    props.setProperty("gemfirexd.debug.true", "TraceIndex");
      startVMs(1, 2);
      //    startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();
    
      Statement s = conn.createStatement();
      s.execute("create table APP.T2_47945("
          + "c1 int not null primary key, c2 int not null) "
          + "replicate persistent SYNCHRONOUS");
      s.execute("create index APP.INDEX1 on APP.T2_47945 (c2)"); 
      s.execute("insert into APP.T2_47945 values(1, 1)");
    
      stopVMNum(-2);
    
      // pause the GII request for T2_47945 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Object[] {
        Boolean.TRUE, "/APP/T2_47945" });
    
      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, null);
        //      restartServerVMAsync(2, 0, null, props);
    
        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
                "testLatchWaiting"));
          }
    
        @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
    
        // execute an update query while the GII is paused 
        s.execute("update APP.T2_47945 set c2 = 2");
        s.execute("update APP.T2_47945 set c2 = 3");
      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
          Boolean.FALSE, null });
      }
    
      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);
    
      s.execute("select count(*) from APP.T2_47945");
      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
    
      // this query should use index scan
      // #47945: count(*) should be 1
      s.execute("select count(*) from APP.T2_47945 where c2 > 0");
      r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
    
      // verify the contents 
      s.execute("select * from APP.T2_47945 where c2 > 0");
      ResultSet r2 = s.getResultSet();
      assertTrue(r2.next());
      assertEquals(1, r2.getInt(1));
      assertEquals(3, r2.getInt(2));
      assertFalse(r2.next());
    }

    public void testBugFKCheckReplicatePartition_44004() throws Exception {
      startVMs(1, 2);
      Connection conn = TestUtil.getConnection();
      Statement s = conn.createStatement();
      s.execute("create table parent_partition"
          + "(c1 int not null primary key, c2 int not null) partition by column(c2)");
      s.execute("create table child_replicate"
          + "(cc1 int not null references parent_partition(c1), cc2 int not null) replicate");
      for (int i = 0; i < 2; i++) {
        try {
          if (i > 0) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          }
          s.execute("insert into child_replicate values(1, 1)");
          fail("insert should throw fk violation exception");
        } catch (SQLException e) {
          assertTrue("23503".equalsIgnoreCase(e.getSQLState()));
        }
      }
    }

    public void dumpResults(int vmNum, String verifySql) throws Exception {
      final int netPort = startNetworkServer(vmNum, null, null);
      Connection connClient = TestUtil.getNetConnection(
          "localhost", netPort, null, null);
      ResultSet rs = connClient.createStatement().executeQuery(verifySql);
      StringBuilder res = new StringBuilder("Result node" + vmNum + " : ");
      int count = 0;
      while (rs.next()) {
        res.append(rs.getInt(1));
        res.append(",");
        res.append(rs.getInt(2));
        res.append(",");
        res.append(rs.getString(3));
        res.append(",");
        res.append(rs.getString(4));
        res.append(";");
        count++;
      }
      res.append("count=" + count);
      getLogWriter().info("viveklog:" + res);
      stopNetworkServer(vmNum);
    }

    /**
     * Start one client and two server. We intend to bring the second server down
     * and apply few updates. When it restarts we want hold GII message
     * processing, and in that time period we want to fire update on the first
     * server and let the distribution happen to second server. At the end the
     * second server should have the same rows. Done for a table with Index
     * constraint
     */
    public void testBug48725_1() throws Exception {
      getLogWriter().info("48725_1: Starting one client and three servers");
      startVMs(1, 2);
    
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      getLogWriter().info("48725_1: Inserting data");
    
      // Primary Table
      st.execute(" create table APP.T1_TABLE (sec_id int not null, "
          + "symbol varchar(10) not null," + "exchange varchar(10) not null,"
          + "constraint sec_pk primary key (sec_id)" + " ) ");
      st.execute("insert into APP.T1_TABLE values(1, 'name1', 'addr1')");
      st.execute("insert into APP.T1_TABLE values(2, 'name2', 'addr2')");
      st.execute("insert into APP.T1_TABLE values(3, 'name3', 'addr3')");
      st.execute("insert into APP.T1_TABLE values(4, 'name4', 'addr4')");
      
      // Secondary Table
      st.execute("create table APP.T2_TABLE (id int primary key, fid int, name varchar(20), address varchar(50)"
          + " ,constraint comp_fk foreign key (fid) "
          + "references APP.T1_TABLE  (sec_id) on delete restrict)"
          + " persistent replicate");
      st.execute("insert into APP.T2_TABLE values(1, 1, 'name1', 'addr1')");
      st.execute("insert into APP.T2_TABLE values(2, 2, 'name2', 'addr2')");
      st.execute("insert into APP.T2_TABLE values(3, 3, 'name3', 'addr3')");
      st.execute("insert into APP.T2_TABLE values(4, 4, 'name4', 'addr4')");
      
      st.execute("create index  APP.ndx_nm_addr on APP.T2_TABLE (name, address)");
      st.execute("create index  APP.ndx_id_nm_addr on APP.T2_TABLE (id, name, address)");
    
      getLogWriter().info("48725_1: Stopping second server");    
      stopVMNums(-2);
    
      // First Update
      st.execute("update APP.T2_TABLE set name = 'name31' where" + " id = 3 ");
      st.execute("update APP.T2_TABLE set name = 'name41' where" + " id = 4 ");
    
      getLogWriter().info("48725_1: Installing latch on second server");
      final VM serverVM = getServerVM(2);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing", new Object[] {
          Boolean.TRUE, "/APP/T2_TABLE" });
    
      AsyncVM asyncVM = null;
      try {
        getLogWriter().info("48725_1: Restarting second server async");
        asyncVM = restartServerVMAsync(2, 0, null, null);
  
        getLogWriter().info("48725_1: Waiting until the test latch point arrives");
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(2);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaitingGIIProcess"));
          }
  
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
  
        getLogWriter().info("48725_1: Doing in-flight updates");
  
        // Fire more updates
        st.execute("update APP.T2_TABLE set address = 'addr31' where"
            + " id = 3 ");
        st.execute("update APP.T2_TABLE set address = 'addr41' where"
            + " id = 4 ");
      } finally {
        getLogWriter().info("48725_1: Releasing the test latch");
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing",
            new Boolean[] { Boolean.FALSE, null });
      }

      getLogWriter().info("48725_1: Waiting for the second server to restart completely");
      joinVM(true, asyncVM);

      String verifySql = "select * from APP.T2_TABLE";
      // dumpResults(1, verifySql);
      // dumpResults(2, verifySql);
    
      // Verify results
      getLogWriter().info("48725_1: Verifying data on the second server");
    
      String goldenTextFile = TestUtil.getResourcesDir()
          + "/lib/checkQuery.xml";
    
      sqlExecuteVerify(null, new int[] { 2 }, verifySql,
          goldenTextFile, "result_48725");
    }

    /**
     * Start one client and two server. We intend to bring the second server down.
     * When it restarts we want hold GII message processing, and in that time
     * period we want to fire update on the first server and let the distribution
     * happen to second server. At the end the second server should have the same
     * rows. Done for a table without any Index constraint
     */
    public void testBug48725_2() throws Exception {   
      getLogWriter().info("48725_2: Starting one client and three servers");
      startVMs(1, 2);
    
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      getLogWriter().info("48725_2: Inserting data");
    
      // Table
      st.execute("create table APP.T2_TABLE (id int primary key, fid int, name varchar(20), address varchar(50)"
          + " ) persistent replicate");
      st.execute("insert into APP.T2_TABLE values(1, 1, 'name1', 'addr1')");
      st.execute("insert into APP.T2_TABLE values(2, 2, 'name2', 'addr2')");
      st.execute("insert into APP.T2_TABLE values(3, 3, 'name31', 'addr3')");
      st.execute("insert into APP.T2_TABLE values(4, 4, 'name41', 'addr4')");
      
      st.execute("create index  APP.ndx_nm_addr on APP.T2_TABLE (name, address)");
      st.execute("create index  APP.ndx_id_nm_addr on APP.T2_TABLE (id, name, address)");
    
      getLogWriter().info("48725_2: Stopping second server");    
      stopVMNums(-2);
        
      getLogWriter().info("48725_2: Installing latch on second server");
      final VM serverVM = getServerVM(2);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing", new Object[] {
          Boolean.TRUE, "/APP/T2_TABLE" });
    
      AsyncVM asyncVM = null;
      try {
        getLogWriter().info("48725_2: Restarting second server async");
        asyncVM = restartServerVMAsync(2, 0, null, null);
    
        getLogWriter().info("48725_2: Waiting until the test latch point arrives");
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(2);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaitingGIIProcess"));
          }
    
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
    
        getLogWriter().info("48725_2: Doing in-flight updates");
    
        // Do updates
        st.execute("update APP.T2_TABLE set address = 'addr31' where"
            + " id = 3 ");
        st.execute("update APP.T2_TABLE set address = 'addr41' where"
            + " id = 4 ");
      } finally {
        getLogWriter().info("48725_2: Releasing the test latch");
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing",
            new Boolean[] { Boolean.FALSE, null });
      }
    
      getLogWriter().info("48725_2: Waiting for the second server to restart completely");
      joinVM(true, asyncVM);
    
      String verifySql = "select * from APP.T2_TABLE";
      // dumpResults(1, verifySql);
      // dumpResults(2, verifySql);
    
      // Verify results
      getLogWriter().info("48725_2: Verifying data on the second server");
    
      String goldenTextFile = TestUtil.getResourcesDir()
          + "/lib/checkQuery.xml";
    
      sqlExecuteVerify(null, new int[] { 2 }, verifySql,
          goldenTextFile, "result_48725");
    }

    /**
     * Verification for testBug48725_2
     */
    public void testBug48725_3() throws Exception {
      getLogWriter().info("48725_3: Starting one client and three servers");
      startVMs(1, 2);
  
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
  
      getLogWriter().info("48725_3: Inserting data");
  
      // Primary Table
      st.execute("create table APP.T2_TABLE (id int primary key, fid int, name varchar(20), address varchar(50)"
          + " ) persistent replicate");
      st.execute("insert into APP.T2_TABLE values(1, 1, 'name1', 'addr1')");
      st.execute("insert into APP.T2_TABLE values(2, 2, 'name2', 'addr2')");
      st.execute("insert into APP.T2_TABLE values(3, 3, 'name3', 'addr3')");
      st.execute("insert into APP.T2_TABLE values(4, 4, 'name4', 'addr4')");
  
      st.execute("create index  APP.ndx_nm_addr on APP.T2_TABLE (name, address)");
      st.execute("create index  APP.ndx_id_nm_addr on APP.T2_TABLE (id, name, address)");
  
      getLogWriter().info("48725_3: Stopping second server");
      stopVMNums(-2);
      
      // Fire some updates
      st.execute("update APP.T2_TABLE set name = 'name31' where" + " id = 3 ");
      st.execute("update APP.T2_TABLE set name = 'name41' where" + " id = 4 ");
  
      getLogWriter().info("48725_3: Installing latch on second server");
      final VM serverVM = getServerVM(2);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing",
          new Object[] { Boolean.TRUE, "/APP/T2_TABLE" });
  
      AsyncVM asyncVM = null;
      try {
        getLogWriter().info("48725_3: Restarting second server async");
        asyncVM = restartServerVMAsync(2, 0, null, null);
  
        getLogWriter()
            .info("48725_3: Waiting until the test latch point arrives");
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(2);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaitingGIIProcess"));
          }
  
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
  
        getLogWriter().info("48725_3: Doing in-flight updates");
  
        // Fire more updates
        st.execute("update APP.T2_TABLE set address = 'addr32' where"
            + " id = 3 ");
        st.execute("update APP.T2_TABLE set address = 'addr42' where"
            + " id = 4 ");
        st.execute("update APP.T2_TABLE set address = 'addr31' where"
            + " id = 3 ");
        st.execute("update APP.T2_TABLE set address = 'addr41' where"
            + " id = 4 ");
      } finally {
        getLogWriter().info("48725_3: Releasing the test latch");
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIProcessing",
            new Boolean[] { Boolean.FALSE, null });
      }
  
      getLogWriter().info(
          "48725_3: Waiting for the second server to restart completely");
      joinVM(true, asyncVM);
  
      String verifySql = "select * from APP.T2_TABLE";
      // dumpResults(1, verifySql);
      // dumpResults(2, verifySql);
  
      // Verify results
      getLogWriter().info("48725_3: Verifying data on the second server");
  
      String goldenTextFile = TestUtil.getResourcesDir()
          + "/lib/checkQuery.xml";
  
      sqlExecuteVerify(null, new int[] { 2 }, verifySql, goldenTextFile,
          "result_48725");
    }
    
    public void testOffHeapBug_48875_1() throws Exception {
      this.configureDefaultOffHeap(true);
      //    Properties props = new Properties();
      //    props.setProperty("gemfirexd.debug.true", "TraceIndex");
      startVMs(1, 2);
      //    startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();

      Statement s = conn.createStatement();
      s.execute("create table APP.T2_X("
          + "c1 int not null primary key, c2 int not null) "
          + "replicate offheap" );
      s.execute("create index APP.INDEX1 on APP.T2_X (c2)"); 
      s.execute("insert into APP.T2_X values(1, 1)");

      stopVMNum(-2);

      // pause the GII request for T2_47945 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Object[] {
        Boolean.TRUE, "/APP/T2_X" });

      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, null);
        //      restartServerVMAsync(2, 0, null, props);

        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
                "testLatchWaiting"));
          }

        @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);

        // execute an update query while the GII is paused 
        s.execute("update APP.T2_X set c2 = 2");
        s.execute("update APP.T2_X set c2 = 3");
      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
          Boolean.FALSE, null });
      }

      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);

      s.execute("select * from APP.T2_X");
      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(3, r1.getInt(2));

      
      
    }
    
    public void testOffHeapBug_48875_2() throws Exception {
      this.configureDefaultOffHeap(true);
      //    Properties props = new Properties();
      //    props.setProperty("gemfirexd.debug.true", "TraceIndex");
      startVMs(1, 2);
      //    startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();

      Statement s = conn.createStatement();
      s.execute("create table APP.T2_X1("
          + "c1 int not null primary key, c2 int not null) "
          + "replicate offheap" );
      s.execute("create index APP.INDEX1 on APP.T2_X1 (c2)"); 
      s.execute("insert into APP.T2_X1 values(1, 1)");

      stopVMNum(-2);

      // pause the GII request for T2_47945 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Object[] {
        Boolean.TRUE, "/APP/T2_X1" });

      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, null);
        //      restartServerVMAsync(2, 0, null, props);

        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
                "testLatchWaiting"));
          }

        @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);

        // execute an update query while the GII is paused 
        s.execute("update APP.T2_X1 set c2 = 2");
        s.execute("update APP.T2_X1 set c2 = 3");
        s.execute("update APP.T2_X1 set c2 = 4");
        s.execute("insert into APP.T2_X1 values(5, 5)");

      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
          Boolean.FALSE, null });
      }

      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);

      s.execute("select * from APP.T2_X1");
      Set<Integer> expected = new HashSet<Integer>();
      expected.add(4);
      expected.add(5);

      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      r1.getInt(1);
      assertTrue(expected.remove(r1.getInt(2)));
      assertTrue(r1.next());
      
      
      assertTrue(expected.remove(r1.getInt(2)));
      assertTrue(expected.isEmpty());
    }

    /**
     * Start one client and two server. We intend to bring the second server down.
     * While second server is down, we have halted GII request processing on first
     * server. Now first server restarts, and we want fire an insert, followed by
     * delete on the first server and let the distribution happen to second
     * server. At the end the second server should have the same rows. constraint
     */
    public void testBug49221_1() throws Exception {
      Properties props = new Properties();
      startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();
      getLogWriter().info("49221_1: Create table and insert data");
      Statement s = conn.createStatement();
      s.execute("create table APP.T1_49221(c1 int primary key, c2 int) replicate");
      s.execute("create table APP.T2_49221(c1 int primary key, c2 int"
          + " ,constraint comp_fk foreign key (c2) "
          + "references APP.T1_49221  (c1) on delete restrict) replicate");
      s.execute("insert into APP.T1_49221 values(1, 1)");
      s.execute("insert into APP.T1_49221 values(2, 2)");
      s.execute("insert into APP.T2_49221 values(1, 1)");
      getLogWriter().info("49221_1: Stop 2nd server");
      stopVMNum(-2);
  
      // pause the GII request for T2_49221 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests",
          new Object[] { Boolean.TRUE, "/APP/T2_49221" });
      getLogWriter().info("49221_1: Suspend GII request");
      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, props);
        // restartServerVMAsync(2, 0, null, props);
        getLogWriter().info("49221_1: Second VM started request");
        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaiting"));
          }
  
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
  
        getLogWriter().info("49221_1: Doing in-flight insert");
        // execute an insert query while the GII is paused
        s.execute("insert into APP.T2_49221 values(2, 2)");
  
        getLogWriter().info("49221_1: Doing in-flight delete");
        // execute delete on query while the GII is paused
        s.execute("delete from APP.T2_49221 where c2 = 2");
      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests",
            new Boolean[] { Boolean.FALSE, null });
      }
      getLogWriter().info("49221_1: GII restarted");
      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);
      getLogWriter().info("49221_1: Verify data");
      s.execute("select count(*) from APP.T2_49221");
      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
  
      // this query should use index scan
      // #47945: count(*) should be 1
      s.execute("select count(*) from APP.T2_49221 where c2 > 0");
      r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(1, r1.getInt(1));
  
      // verify the contents
      s.execute("select * from APP.T2_49221 where c2 > 0");
      ResultSet r2 = s.getResultSet();
      assertTrue(r2.next());
      assertEquals(1, r2.getInt(1));
      assertEquals(1, r2.getInt(2));
      assertFalse(r2.next());
    }

    /*
     * Start one client and two servers. We intend to bring the second server down 
     * and when it restart we want to halt it before applyAllSuspects() finishes.
     * In this time period we want to fire inserts on the first server and let the 
     * distribute happen to second server. At the end the second server should have the same rows.
     */
    public void _testBug49506_1() throws Exception {
      Properties props = new Properties();
      startVMs(1, 2, 0, null, props);
      Connection conn = TestUtil.getConnection();
      getLogWriter().info("49506_1: Create table and insert data");
      Statement s = conn.createStatement();
      s.execute("create table APP.T1_49506(c1 int primary key, c2 int) replicate");
      s.execute("create table APP.T2_49506(c1 int primary key, c2 int"
          + " ,constraint comp_fk foreign key (c2) "
          + "references APP.T1_49506  (c1) on delete restrict) replicate");
      s.execute("insert into APP.T1_49506 values(1, 1)");
      s.execute("insert into APP.T1_49506 values(2, 2)");
      s.execute("insert into APP.T1_49506 values(3, 3)");
      s.execute("insert into APP.T2_49506 values(1, 1)");
      getLogWriter().info("49506_1: Stop 2nd server");
      stopVMNum(-2);
    
      // pause the GII request for T2_49506 when VM 2 will be restarted
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests",
          new Object[] { Boolean.TRUE, "/APP/T2_49506" });
      getLogWriter().info("49506_1: Suspend GII request");
      
      getLogWriter().info("49506_1:Installing latch on second server");
      getServerVM(2).invoke(ConcurrencyChecksDUnit.class,
          "suspendApplyAllSuspects",
          new Object[] { Boolean.TRUE, "/APP/T2_49506" });
      
      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(2, 0, null, props);
        // restartServerVMAsync(2, 0, null, props);
        getLogWriter().info("49506_1: Second VM started request");
        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaiting"));
          }
    
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
    
        getLogWriter().info("49506_1: Doing in-flight insert; val=2");
        s.execute("insert into APP.T2_49506 values(2, 2)");
      } finally {
        getLogWriter().info("49506_1: reached finally block - 1");
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests",
            new Boolean[] { Boolean.FALSE, null });
      }
      getLogWriter().info("49506_1: GII restarted");
      
      try {
        getLogWriter().info("Waiting until applySuspects latch point arrives");
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(2);
            return Boolean.TRUE.equals(serverVM.invoke(
                ConcurrencyChecksDUnit.class, "testLatchWaitingApplyAllSuspects"));
          }
  
          @Override
          public String description() {
            return "waiting for applyAllSuspects to finish";
          }
        }, 180000, 500, true);
  
        getLogWriter().info("49506_1: Doing in-flight insert; val=3");
        s.execute("insert into APP.T2_49506 values(3, 3)");
      } finally {
        getLogWriter().info("49506_1: reached finally block - 2");
        // Release the latch so that the applyAllSuspects finishes.
        getServerVM(2).invoke(ConcurrencyChecksDUnit.class,
            "suspendApplyAllSuspects", new Boolean[] { Boolean.FALSE, null });
      }
      
      getLogWriter().info("49506_1: applyAllSuspects restarted");
      
      // stop VM 1 to verify the table contents on the restarted VM (2)
      stopVMNum(-1);
      // wait for server 2 to restart fully before firing the query
      joinVM(true, async1);
      getLogWriter().info("49506_1: Verify data");
      s.execute("select count(*) from APP.T2_49506");
      ResultSet r1 = s.getResultSet();
      assertTrue(r1.next());
      assertEquals(3, r1.getInt(1));
    
      // verify the contents
      s.execute("select c1, c2 from APP.T2_49506 where c2 > 2");
      ResultSet r2 = s.getResultSet();
      assertTrue(r2.next());
      assertEquals(3, r2.getInt(1));
      assertFalse(r2.next());
    }

    public void testBug51427() throws Exception {
      // Start one client and three servers
      startVMs(1, 3);

      clientSQLExecute(1, "create schema trade");

      // Create a table
      clientSQLExecute(
          1,
          "create table trade.portfolio (cid int not null, sid int not null, "
          + "qty int not null, availQty int not null, subTotal int, "
          + "tid int, constraint portf_pk primary key (cid)) "
          + "partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), "
          + "VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))  "
          + "REDUNDANCY 2 PERSISTENT");

      Connection conn = TestUtil.getConnection();
      Statement s = conn.createStatement();
      s.execute("create unique index uniq_idx on trade.portfolio(sid, tid)");
      s.execute("insert into trade.portfolio values(1, 1, 1, 1, 1, 1)");
      stopVMNum(-3);
      s.execute("update trade.portfolio set sid = 2 where tid = 1");

      stopVMNum(-2);
      final VM serverVM = getServerVM(1);
      serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests",
          new Object[] { Boolean.TRUE, "/__PR/_B__TRADE_PORTFOLIO_0" });

      AsyncVM async1;
      try {
        async1 = restartServerVMAsync(3, 0, null, null);
        // restartServerVMAsync(2, 0, null, props);
  
        // wait for GII to start
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            VM serverVM = getServerVM(1);
            return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
                "testLatchWaiting"));
          }
  
          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 180000, 500, true);
        
        // insert a new row such that it would have violated the unique constraint
        // had the previous update would not have happened
        s.execute("insert into trade.portfolio values(2, 1, 1, 1, 1, 1)");
      } finally {
        // continue the GII
        serverVM.invoke(ConcurrencyChecksDUnit.class, "suspendAllGIIRequests", new Boolean[] {
            Boolean.FALSE, null });
      }
      // wait for GII to complete
      joinVM(true, async1);
      serverExecute(3, new SerializableRunnable("wait for GII") {
        @Override
        public void run() {
          try {
            PartitionedRegion pr = (PartitionedRegion)GemFireCacheImpl
                .getExisting().getRegionByPath("/TRADE/PORTFOLIO", false);
            pr.createBucket(0, 0, null);
            pr.getDataStore().getCreatedBucketForId(null, 0).waitForData();
          } catch (Exception e) {
            fail("unexpected exception waiting for bucket region 0", e);
          }
        }
      });

      // verify results
      String goldenTextFile = TestUtil.getResourcesDir()
          + "/lib/checkQuery.xml";

      sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 3 },
          "select * from TRADE.PORTFOLIO where tid = 1", goldenTextFile,
          "result_51427");
      sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 3 },
          "select * from TRADE.PORTFOLIO", goldenTextFile, "result_51427");
    }
}
