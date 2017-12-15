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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;

import com.pivotal.gemfirexd.internal.engine.distributed.query.HeapThresholdHelper;
import io.snappydata.test.dunit.VM;

@SuppressWarnings("serial")
public class GfxdLRUDUnit extends DistributedSQLTestBase {

  public GfxdLRUDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  private void insertNElements(int n, Statement s) throws SQLException {
    for (int i = 0; i < n; i++) {
      String insertStr = "insert into trade.customers values (" + i + ", 'name"+ i +"')";
      s.execute(insertStr);
    }
  }

  private void insertNBigElements(int n, PreparedStatement ps, int baseCnt) throws SQLException {
    StringBuilder dummyPrefix = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      dummyPrefix.append("jjjjjjjjjj");
    }
    for (int i = baseCnt; i < n+baseCnt; i++) {
      String name = dummyPrefix + "_name"+i;
      String addr = dummyPrefix + "_addr"+(i+baseCnt);
      String addr2 = dummyPrefix + "_addr2"+(i+baseCnt);
      ps.setInt(1, i+baseCnt);
      ps.setString(2, name);
      ps.setString(3, addr);
      ps.setString(4, addr2);
      ps.executeUpdate();
    }
  }
  
  private void insertNBigElements2(int n, PreparedStatement ps, int baseCnt) throws SQLException {
    //String dummyPrefix = "";
    StringBuilder sb = new StringBuilder(1000000);
    for(int i=0; i<100000; i++) {
      sb.append("jjjjjjjjjj");
    }
    for (int i = baseCnt; i < n+baseCnt; i++) {
      String addr = sb.toString() + "_addr"+(i+baseCnt);
      ps.setInt(1, i+baseCnt);
      ps.setString(2, addr);
      ps.executeUpdate();
    }
  }
  
  public void testPRLRUCountDestroy() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }
    
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.customers (cid int not null, cust_name varchar(100))  EVICTION BY LRUCOUNT 100 EVICTACTION DESTROY");
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    insertNElements(200, s);
    s.execute("select count(*) from trade.customers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    assertEquals("expected 100 elements but found " + cnt, 100, cnt);
  }

  public void testPRLRUCountOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.customers (cid int not null, cust_name varchar(100)) " +
        " EVICTION BY LRUCOUNT 100 EVICTACTION overflow synchronous");
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    insertNElements(200, s);
    s.execute("select count(*) from trade.customers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    assertEquals("expected 200 elements but found " + cnt, 200, cnt);
  }

  public void testReplicatedRegionLRUCountOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.customers (cid int not null, cust_name varchar(100)) " +
        " replicate EVICTION BY LRUCOUNT 100 EVICTACTION overflow synchronous");
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    insertNElements(200, s);
    s.execute("select count(*) from trade.customers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    assertEquals("expected 200 elements but found " + cnt, 200, cnt);
  }
  
  public void testReplicatedRegionLRUCountOverflowAsync() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.customers (cid int not null, cust_name varchar(100)) " +
        " replicate EVICTION BY LRUCOUNT 100 EVICTACTION overflow asynchronous");
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    insertNElements(200, s);
    Thread.sleep(5000);
    s.execute("select count(*) from trade.customers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    assertEquals("expected 200 elements but found " + cnt, 200, cnt);
  }

  public void testPRLRUMemDestroy() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }

    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        " MAXPARTSIZE 1 EVICTION BY LRUMEMSIZE 1 EVICTACTION DESTROY");
    Connection conn = TestUtil.getConnection();
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");
    insertNBigElements(2000, ps, 0);
    Statement s = conn.createStatement();
    s.execute("select count(*) from trade.bigcustomers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    TestUtil.getLogger().info("cnt: "+cnt);
    assertTrue(cnt < 2000);
  }

  public void testPRLRUMemOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        " MAXPARTSIZE 1 EVICTION BY LRUMEMSIZE 1 EVICTACTION overflow synchronous");
    Connection conn = TestUtil.getConnection();
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");
    insertNBigElements(2000, ps, 0);
    Statement s = conn.createStatement();
    s.execute("select count(*) from trade.bigcustomers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    TestUtil.getLogger().info("cnt: "+cnt);
    assertEquals("expected 2000 elements but found " + cnt, 2000, cnt);
  }

  public void testReplicatedRegionLRUMemOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        " replicate EVICTION BY LRUMEMSIZE 1 EVICTACTION overflow synchronous");
    Connection conn = TestUtil.getConnection();
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");
    insertNBigElements(2000, ps, 0);
    Statement s = conn.createStatement();
    s.execute("select count(*) from trade.bigcustomers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    TestUtil.getLogger().info("cnt: "+cnt);
    assertEquals("expected 2000 elements but found " + cnt, 2000, cnt);
  }
  
  /**
   * Added a test for defect #50112: EVICTION BY LRUMEMSIZE Not Work Correctly When Creating Table
   */
  public void testPartitionedRegionEvictionLRUMemSize() throws Exception {
	startVMs(0, 1);
	Connection conn = TestUtil.getConnection();
	Statement s = conn.createStatement();
	s.execute(" create table trade.test3 (OrderId INT NOT NULL,ItemId INT) " +
		"PARTITION BY COLUMN (OrderId) MAXPARTSIZE 200 EVICTION BY LRUMEMSIZE 431 EVICTACTION DESTROY ");
	
	Statement s1 = conn.createStatement();
	s1.execute("select EVICTIONATTRS from sys.systables where tablename = 'TEST3'");
	ResultSet rs = s1.getResultSet();
	String evictAttributesAfterCreateTable = null;
	if (rs.next()) {
		evictAttributesAfterCreateTable = rs.getString(1);
	}
	assertNotNull(evictAttributesAfterCreateTable);
	TestUtil.getLogger().info("Eviction attributes after create table: " + evictAttributesAfterCreateTable);
	assertFalse(evictAttributesAfterCreateTable.contains("431"));//should be reset to localMaxMemory
	assertTrue(evictAttributesAfterCreateTable.contains("200"));//localMaxMemory for PartitionedTable
	
	s.execute("ALTER TABLE trade.test3 SET EVICTION MAXSIZE 876");
	s.execute("select EVICTIONATTRS from sys.systables where tablename = 'TEST3'");
	rs = s.getResultSet();
	String evictAttributesAfterAlterTable = null;
	while (rs.next()) {
		evictAttributesAfterAlterTable = rs.getString(1);
	}
	assertNotNull(evictAttributesAfterAlterTable);
	assertFalse(evictAttributesAfterAlterTable.contains("876"));//should be reset to localMaxMemory
	assertTrue(evictAttributesAfterAlterTable.contains("200"));//localMaxMemory for PartitionedTable
	
	assertTrue("Eviction attributes after create table and alter table should match..both should be equal to localMaxMemory", 
		(evictAttributesAfterCreateTable.equals(evictAttributesAfterAlterTable)));
  }
  
  public static void assertHeapPercentage(Float perc) {
    float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
    assertEquals(perc.floatValue(), evictionHeapPercentage);
  }
  
  public static void logVMHeapSizeAndCurrentHeapSize() {
    Runtime rt = Runtime.getRuntime();
    long totmem = rt.totalMemory();
    long freemem = rt.freeMemory();
    Misc.getCacheLogWriter().info(
        "logVMHeapSizeAndCurrentHeapSize tot mem in bytes: " + totmem + " and free mem: " + freemem
            + " so used mem: " + (totmem - freemem) +" and heap max size: "+rt.maxMemory());
  }

  // Suranjan: MVCC destroy keeps the old value in the oldEntryMap. The test fails.
  public void testPRLRUHeapPercDestroy() throws Exception {
    try {
      // The test is valid only for transaction isolation level NONE.
      if (isTransactional) {
        return;
      }

      startVMs(1, 1);
      clientSQLExecute(1, "create table trade.bigcustomers (cid int not null, cust_addr clob) " +
          "EVICTION BY LRUHEAPPERCENT EVICTACTION destroy");
      Connection conn = TestUtil.getConnection();
      CallableStatement cs = conn.prepareCall("call sys.set_critical_heap_percentage_sg(?, ?)");
      cs.setInt(1, 90);
      cs.setNull(2, Types.VARCHAR);
      cs.execute();
      cs = conn.prepareCall("call sys.set_eviction_heap_percentage_sg(?, ?)");
      cs.setInt(1, 5);
      cs.setNull(2, Types.VARCHAR);
      cs.execute();
      float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
      assertEquals(Float.valueOf(5), evictionHeapPercentage);
      VM servervm = this.serverVMs.get(0);
      servervm.invoke(GfxdLRUDUnit.class, "assertHeapPercentage", new Object[]{Float.valueOf(evictionHeapPercentage)});
      servervm.invoke(GfxdLRUDUnit.class, "setDummytestBytes");
      PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?)");

      insertNBigElements2(300, ps, 0);
      final Statement s = conn.createStatement();

      servervm.invoke(GfxdLRUDUnit.class, "logVMHeapSizeAndCurrentHeapSize");
      final WaitCriterion waitCond = new WaitCriterion() {
        @Override
        public boolean done() {
          try {
            s.execute("select count(*) from trade.bigcustomers");
            ResultSet rs = s.getResultSet();
            int cnt = 0;
            if (rs.next()) {
              cnt = rs.getInt(1);
            }
            TestUtil.getLogger().info("cnt: " + cnt);
            return (cnt < 300);
          } catch (SQLException sqle) {
            fail("unexpected exception " + sqle, sqle);
            return false;
          }
        }

        @Override
        public String description() {
          return "waiting for LRU destroy";
        }
      };
      waitForCriterion(waitCond, 120000, 500, true);
    } finally {
      // resetting it to default value
      HeapMemoryMonitor.setTestBytesUsedForThresholdSet(-1);
    }
  }

  public static void setDummytestBytes() {
    HeapMemoryMonitor.setTestBytesUsedForThresholdSet(100);
  }

  public void testPRLRUHeapPercDestroy_1() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }

    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        "  EVICTION BY LRUHEAPPERCENT EVICTACTION destroy");
    Connection conn = TestUtil.getConnection();
    CallableStatement cs = conn.prepareCall("call sys.set_eviction_heap_percentage(?)");
    cs.setInt(1, 25);
    cs.execute();
    float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
    TestUtil.getLogger().info("evictionHeapPercentage: "+evictionHeapPercentage);
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");

    insertNBigElements(1000, ps, 0);
    final Statement s = conn.createStatement();
    VM servervm = this.serverVMs.get(0);
    servervm.invoke(GfxdLRUDUnit.class, "raiseFakeHeapEvictorOnEvent");
    insertNBigElements(1000, ps, 1000);

    final WaitCriterion waitCond = new WaitCriterion() {
      @Override
      public boolean done() {
        try {
          s.execute("select count(*) from trade.bigcustomers");
          ResultSet rs = s.getResultSet();
          int cnt = 0;
          if (rs.next()) {
            cnt = rs.getInt(1);
          }
          TestUtil.getLogger().info("cnt: " + cnt);
          return (cnt < 2000);
        } catch (SQLException sqle) {
          fail("unexpected exception " + sqle, sqle);
          return false;
        }
      }

      @Override
      public String description() {
        return "waiting for LRU destroy";
      }
    };
    waitForCriterion(waitCond, 120000, 500, true);
  }

  public void testPRLRUHeapPercOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        " EVICTION BY LRUHEAPPERCENT EVICTACTION overflow synchronous");
    Connection conn = TestUtil.getConnection();
    CallableStatement cs = conn.prepareCall("call sys.set_eviction_heap_percentage(?)");
    cs.setInt(1, 25);
    cs.execute();
    float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
    TestUtil.getLogger().info("evictionHeapPercentage: "+evictionHeapPercentage);
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");
    
    insertNBigElements(1000, ps, 0);
    VM servervm = this.serverVMs.get(0);
    servervm.invoke(GfxdLRUDUnit.class, "raiseFakeHeapEvictorOnEvent");
    Statement s = conn.createStatement();
    insertNBigElements(1000, ps, 1000);
    
    s.execute("select count(*) from trade.bigcustomers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    TestUtil.getLogger().info("cnt: "+cnt);
    assertEquals("expected 2000 elements but found " + cnt, 2000, cnt);
  }

  public void testReplicatedRegionLRUHeapPercOverflow() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(
        1," create diskstore teststore 'temp' ");
    clientSQLExecute(
        1,
        " create table trade.bigcustomers (cid int not null, cust_name varchar(2000), cust_addr varchar(2000), cust_addr2 varchar(2000)) " +
        " replicate EVICTION BY LRUHEAPPERCENT EVICTACTION overflow synchronous 'teststore' ");
    Connection conn = TestUtil.getConnection();
    CallableStatement cs = conn.prepareCall("call sys.set_eviction_heap_percentage(?)");
    cs.setInt(1, 25);
    cs.execute();
    float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
    TestUtil.getLogger().info("evictionHeapPercentage: "+evictionHeapPercentage);
    PreparedStatement ps = conn.prepareStatement("insert into trade.bigcustomers values(?, ?, ?, ?)");
    
    insertNBigElements(1000, ps, 0);
    VM servervm = this.serverVMs.get(0);
    servervm.invoke(GfxdLRUDUnit.class, "raiseFakeHeapEvictorOnEvent");
    Statement s = conn.createStatement();
    insertNBigElements(1000, ps, 1000);
    
    s.execute("select count(*) from trade.bigcustomers");
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    if (rs.next()) {
      cnt = rs.getInt(1);
    }
    TestUtil.getLogger().info("cnt: "+cnt);
    assertEquals("expected 2000 elements but found " + cnt, 2000, cnt);
  }

  public static void raiseFakeHeapEvictorOnEvent() {
    Misc.getGemFireCache().getHeapEvictor().testAbortAfterLoopCount = 1;
    HeapMemoryMonitor.setTestDisableMemoryUpdates(true);
    try {
      System.setProperty("gemfire.memoryEventTolerance", "0");

      Misc.getGemFireCache().getResourceManager().setEvictionHeapPercentage(85);
      HeapMemoryMonitor hmm = Misc.getGemFireCache().getResourceManager()
          .getHeapMonitor();
      hmm.setTestMaxMemoryBytes(100);

      hmm.updateStateAndSendEvent(90);
    } finally {
      System.clearProperty("gemfire.memoryEventTolerance");
      HeapMemoryMonitor.setTestDisableMemoryUpdates(false);
    }
  }
}
