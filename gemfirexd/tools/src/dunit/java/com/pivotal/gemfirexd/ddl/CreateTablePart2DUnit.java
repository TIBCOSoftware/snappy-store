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

import java.io.File;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.ToursDBUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.entry.*;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import junit.framework.AssertionFailedError;
import org.apache.derbyTesting.junit.JDBC;

@SuppressWarnings("serial")
public class CreateTablePart2DUnit extends DistributedSQLTestBase {

  private static boolean signalThread = false;
  
  public CreateTablePart2DUnit(String name) {
    super(name);
  }

  public void testInsertBySelects() throws Exception {
    this.basicInsertBySelects(false);
  }
 
  public void testInsertBySelectsOffheap() throws Exception {
    this.basicInsertBySelects(true);
  }
  
  private String getConditionalOffheapSuffix(boolean enableOffheapForTable) {
    if(enableOffheapForTable) {
      return " offheap ";
    }else {
      return " ";
    }
  }

  /**
   * Insert with sub-select should work now.
   */

  private void basicInsertBySelects(boolean enableOffheapForTable) throws Exception {
    Properties extra =null;
    if(enableOffheapForTable) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startClientVMs(2, 0, null, extra);
    startServerVMs(3, 0, "SG1", extra);

    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_ONE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 59, "
            + "VALUES BETWEEN 60 and 80 )" + getConditionalOffheapSuffix(enableOffheapForTable));
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (20, 'Second')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (30, 'Third')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (40, 'Fourth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (60, 'Sixth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (70, 'Seventh')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (75, 'Eighth')");

    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_TWO (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 59, "
            + "VALUES BETWEEN 60 and 80 )" + getConditionalOffheapSuffix(enableOffheapForTable));
    clientSQLExecute(1, "insert into EMP.PARTITIONTESTTABLE_TWO "
        + "(select * from EMP.PARTITIONTESTTABLE_ONE)");

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select * from EMP.PARTITIONTESTTABLE_TWO", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_TWO", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_THREE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 59, "
            + "VALUES BETWEEN 60 and 80 )" + getConditionalOffheapSuffix(enableOffheapForTable));
    clientSQLExecute(1, "insert into EMP.PARTITIONTESTTABLE_THREE "
        + "(select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_ONE)");

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select * from EMP.PARTITIONTESTTABLE_THREE", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_THREE", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    clientSQLExecute(1, "create table EMP.REPLICATETESTTABLE_ONE "
        + "(ID int not null, DESCRIPTION varchar(1024) not null, "
        + "primary key (ID)) REPLICATE" + getConditionalOffheapSuffix(enableOffheapForTable));

    clientSQLExecute(1, "insert into EMP.REPLICATETESTTABLE_ONE "
        + "(select * from EMP.PARTITIONTESTTABLE_ONE)");

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select * from EMP.REPLICATETESTTABLE_ONE", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select ID, DESCRIPTION from EMP.REPLICATETESTTABLE_ONE", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    clientSQLExecute(1, "create table EMP.REPLICATETESTTABLE_TWO "
        + "(ID int not null, DESCRIPTION varchar(1024) not null, "
        + "primary key (ID)) REPLICATE" + getConditionalOffheapSuffix(enableOffheapForTable));

    clientSQLExecute(1, "insert into EMP.REPLICATETESTTABLE_TWO "
        + "(select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_ONE)");

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select * from EMP.REPLICATETESTTABLE_TWO", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 2 },
        "select ID, DESCRIPTION from EMP.REPLICATETESTTABLE_TWO", TestUtil.getResourcesDir()
            + "/lib/checkQuery.xml", "insert_as_select",
        false /* do not use prep stmt */,
        false /* do not check for type information */);
  }
  
  public void testBug42820_ReplicateNPEWhenPopulateThruLoader()
      throws Exception {
    this.basicBug42820_ReplicateNPEWhenPopulateThruLoader(false);
  }
  
  public void testBug42820_ReplicateNPEWhenPopulateThruLoaderOffHeap()
      throws Exception {
    this.basicBug42820_ReplicateNPEWhenPopulateThruLoader(true);
  }
  
  private void basicBug42820_ReplicateNPEWhenPopulateThruLoader(boolean enableOffHeap)
      throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    
    String createTableDDL = "create table trade.buyorders(oid int not null "
        + "constraint buyorders_pk primary key, cid int, sid int, qty int, "
        + "bid decimal (30, 20), ordertime timestamp, status varchar(10), "
        + "tid int, constraint bo_qty_ck check (qty>=0))  replicate" + getConditionalOffheapSuffix(enableOffHeap);
    startClientVMs(2, 0, null,extra);
    startServerVMs(2, 0, "SG1", extra);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute(createTableDDL);
    GfxdCallbacksTest
    .addLoader(
        "trade",
        "buyorders",
        "com.pivotal.gemfirexd.ddl.TestBuyOrdersLoader",
        "");
    stmt.execute("select * from trade.buyorders where oid = 1");
    ResultSet rs = stmt.getResultSet();
    assertFalse(rs.next());
  }

  public void testBug48284() throws Exception {
    reduceLogLevelForTest("config");
    testVersionedRegionEntries();
    tearDown();
    for (int i = 0; i < 5; i++) {
      setUp();
      testVersionedRegionEntries();
      tearDown();
    }
  }
  /**
   * Verifies that the create table DDL extension ENABLE CONCURRENCY CHECKS sets
   * a flag correctly on the underlying region, and verifies that the
   * AbstractRegionMap creates RegionEntry objects of the correct type with and
   * without concurrency checks being enabled.
   * 
   * @throws Exception
   */
  public void testVersionedRegionEntries() throws Exception {
    startVMs(1, 4);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    Statement s = conn.createStatement();

    /*
     *  With concurrency checks NOT enabled
     */
    
    // partitioned table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 ");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMBucketRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with persistence
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 " + "persistent synchronous ");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "partition by primary key redundancy 1 " + "eviction by lrucount 1 "
        + "evictaction overflow ");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMBucketRowLocationThinLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with persistence and overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "partition by primary key redundancy 1 " + "eviction by lrucount 1 "
        + "evictaction overflow " + "persistent synchronous ");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "replicate");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with persistence
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "replicate persistent");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + " replicate eviction by lrucount 1 evictaction overflow");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with persistence and overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + " replicate eviction by lrucount 1 evictaction overflow "
        + " persistent synchronous ");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinDiskLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    /*
     *  With concurrency checks enabled
     */
    
    // partitioned table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 "
        + "enable concurrency checks");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with persistence
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 " + "persistent synchronous "
        + "enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "partition by primary key redundancy 1 " + "eviction by lrucount 1 "
        + "evictaction overflow " + "enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // partitioned table with persistence and overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "partition by primary key redundancy 1 " + "eviction by lrucount 1 "
        + "evictaction overflow " + "persistent synchronous "
        + "enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "replicate enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with persistence
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + "replicate persistent enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + " replicate eviction by lrucount 1 evictaction overflow enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // replicated table with persistence and overflow
    s.execute("create table APP.T1(ID INT PRIMARY KEY, NAME VARCHAR(20)) "
        + " replicate eviction by lrucount 1 evictaction overflow "
        + " persistent synchronous " + "enable concurrency checks");
    getServerVM(1).invoke(
        CreateTableDUnit.class,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinDiskLRURegionEntryHeap.class });
    s.execute("drop table APP.T1");

  }

  public void testBug45372() throws Exception {
    this.basicBug45372(false);
  }
  
  public void testBug45372_OffHeap() throws Exception {
    this.basicBug45372(true);
  }
  
  private void basicBug45372(boolean enableOffHeap) throws Exception {
    // start a client and couple of servers
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 3,-1,null, extra);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers  (cid int not null GENERATED BY DEFAULT AS IDENTITY,tid int not null, primary key (cid))"+ getConditionalOffheapSuffix(enableOffHeap));
    stmt.execute("alter table trade.customers ALTER cid RESTART WITH 1");

    stmt.execute("insert into trade.customers (tid) values (1)");

    stopVMNum(-1);
    startVMs(0, 1, -1, null, extra);

    try {
      stmt.execute("insert into trade.customers (tid) values (2)");
    }
    catch (SQLException e) {
      e.printStackTrace();
      if(e.getCause() instanceof EntryExistsException){
        e.getCause().printStackTrace();
        fail("Test failed with " + e.getMessage());
      }
    }

    stmt.execute("drop table trade.customers");
  }

  public void testBug49357() throws Exception {
    Properties props = new Properties();
    props.put("gemfire.off-heap-memory-size","500m");
    startServerVMs(1, 0, null, props);
    serverSQLExecute(1, "CREATE TABLE usertable (YCSB_KEY VARCHAR(100) PRIMARY KEY) partition by (YCSB_KEY) redundancy 1 offheap PERSISTENT");
    startClientVMs(1, 0, null, null);
  }

  public void test40100() throws Exception {
    this.basic40100(false);
  }
  public void test40100_OffHeap() throws Exception {
    this.basic40100(true);
  }
  /**
   * test for 40100 with Server groups.
   */
  private void basic40100(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 1, -1, null, extra);
    startServerVMs(1, 0, "SG1", extra);
    Connection conn = TestUtil.jdbcConn;
    Statement s = conn.createStatement();

    s.execute("create schema trade");// default server groups (SG1)");
    s.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid)) replicate"+ getConditionalOffheapSuffix(enableOffHeap));
    
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    expectedAttrs.setEnableOffHeapMemory(enableOffHeap);
    // Test the region attributes on servers
    serverVerifyRegionProperties(1, "TRADE", "CUSTOMERS", expectedAttrs);
    serverVerifyRegionProperties(2, "TRADE", "CUSTOMERS", expectedAttrs);
    
    s.execute("insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)");
   s.close();
    s = conn.createStatement();
    
    ResultSet rs = s.executeQuery("select * from trade.customers where since=1");
    
    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      assertEquals("XXXX1", rs.getString(2).trim());
      assertEquals(1, rs.getInt(3));
      assertEquals("BEAV1", rs.getString(4));
      s.close();
      s = conn.createStatement();
      int numUpdate = s.executeUpdate("update trade.customers " +
                "set since=2 where cid=1");

      assertEquals("Should update one row", 1, numUpdate);
      s.close();
      s = conn.createStatement();

      rs = s.executeQuery("select * from trade.customers where since=1");
      while (rs.next()) {
        getLogWriter().info("XXXX col1 : " + rs.getInt(1) + " #2 : "
            + rs.getString(2).trim() + " #3 : " + rs.getInt(3) + " #4 "
            + rs.getString(4) + " #5 : " + rs.getInt(5));
        throw new AssertionFailedError("Should not return any rows");
      }
    }
  }

  public void test39937() throws Exception {

    startClientVMs(1, 0, null);
    startServerVMs(1, 0, "SG1");
    startServerVMs(2, 0, "SG2");
    serverSQLExecute(1, "create diskstore teststore compactionthreshold 100 " +
        " TimeInterval 20 MaxLogSize 1048576 WriteBufferSize 1024 autocompact true" +
        " queuesize  1024 ");
    // INVALIDATE not supported in GemFireXD - test changed to use DESTROY
    clientSQLExecute(1, "create table EMP.TESTTABLE_ONE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE SERVER GROUPS (sg1, sg2) INITSIZE 100 "
        + "EXPIRE TABLE WITH TIMETOLIVE 100 ACTION DESTROY "
        + "EXPIRE ENTRY WITH IDLETIME 10 ACTION DESTROY");

    clientSQLExecute(1, "create table EMP.TESTTABLE_TWO (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE SERVER GROUPS (sg1) "
        + "EVICTION BY LRUMEMSIZE 1000 EVICTACTION OVERFLOW "
        + " ASYNCHRONOUS 'teststore'");
  }
  
  /**
   * DDLs with schema.
   * @throws Exception
   */
  public void test40494() throws Exception { 
    this.basic40494(false);
  }
  
  public void test40494_OffHeap() throws Exception { 
    this.basic40494(true);
  }

  /**
   * DDLs with schema.
   * @throws Exception
   */
  private void basic40494(boolean enableOffHeap) throws Exception { 
    
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 1, -1, null, extra);
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, " +
          "symbol varchar(10) not null, price decimal (30, 20), " +
          "exchange varchar(10) not null, tid int, " +
          "constraint sec_pk primary key (sec_id), " +
          "constraint sec_uq unique (symbol, exchange), " +
          "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex'," +
          " 'lse', 'fse', 'hkse', 'tse'))) replicate"+ getConditionalOffheapSuffix(enableOffHeap));
    clientSQLExecute(1, "create index index_sym on trade.securities(symbol)");
    clientSQLExecute(1, "drop table trade.securities");
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, " +
        "symbol varchar(10) not null, price decimal (30, 20), " +
        "exchange varchar(10) not null, tid int, " +
        "constraint sec_pk primary key (sec_id), " +
        "constraint sec_uq unique (symbol, exchange), " +
        "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex'," +
        " 'lse', 'fse', 'hkse', 'tse')))" + getConditionalOffheapSuffix(enableOffHeap));
    startVMs(0, 1, -1, null, extra);
    stopVMNums(-1);
    clientSQLExecute(1,
        "insert into trade.securities values (1, 'A', 10.0, 'nye', 1)");
    try {
      clientSQLExecute(1,
          "insert into trade.securities values (2, 'B', 20.0, 'TSE', 2)");
      fail("expected a check constraint violation");
    } catch (SQLException ex) {
      if (!"23513".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      clientSQLExecute(1,
          "insert into trade.securities values (2, 'B', 20.0, 'tse', 2), "
              + "(3, 'C', 30.0, 'tsee', 3)");
      fail("expected a check constraint violation");
    } catch (SQLException ex) {
      if (!"23513".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    clientSQLExecute(1,
        "insert into trade.securities values (3, 'C', 30.0, 'fse', 3), "
            + "(4, 'D', 25.0, 'lse', 4)");
    clientSQLExecute(1, "select * from trade.securities");
  }
  
  /**
   * Test without schema.
   * 
   * @throws Exception
   */
  public void test40494_WithOutSchema() throws Exception {
    this.basic40494_WithOutSchema(false);
  
  }

  public void test40494_WithOutSchema_OffHeap() throws Exception {
    this.basic40494_WithOutSchema(true);
  
  }
  /**
   * Test without schema.
   * 
   * @throws Exception
   */
  private void basic40494_WithOutSchema(boolean enableOffHeap) throws Exception {

    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 1, -1, null, extra);
    clientSQLExecute(1, "create table securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, "
        + "constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
        + " 'lse', 'fse', 'hkse', 'tse'))) replicate"+ getConditionalOffheapSuffix(enableOffHeap));
    clientSQLExecute(1, "create index index_sym on securities(symbol)");
    clientSQLExecute(1, "drop table securities");
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, "
        + "constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
        + " 'lse', 'fse', 'hkse', 'tse')))"+ getConditionalOffheapSuffix(enableOffHeap));
    startVMs(0, 1, -1, null, extra);
    stopVMNums(-1);
    clientSQLExecute(1,
        "insert into trade.securities values (1, 'A', 10.0, 'nye', 1)");
    clientSQLExecute(1,
        "insert into trade.securities values (2, 'B', 20.0, 'tse', 2)");
    clientSQLExecute(1, "select * from trade.securities");
  }
  /**
   * Test with explicit schema creation and deletion.
   * @throws Exception
   */
 public void test40494_WithCreateSchema() throws Exception {
    this.basic40494_WithCreateSchema(false);
  }
  
  public void test40494_WithCreateSchema_OffHeap() throws Exception {
    this.basic40494_WithCreateSchema(true);
  }
  /**
   * Test with explicit schema creation and deletion.
   * @throws Exception
   */
  private void basic40494_WithCreateSchema(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 1, -1, null, extra);
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, " +
          "symbol varchar(10) not null, price decimal (30, 20), " +
          "exchange varchar(10) not null, tid int, " +
          "constraint sec_pk primary key (sec_id), " +
          "constraint sec_uq unique (symbol, exchange), " +
          "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex'," +
          " 'lse', 'fse', 'hkse', 'tse'))) replicate"+ getConditionalOffheapSuffix(enableOffHeap));
    clientSQLExecute(1, "create index index_sym on trade.securities(symbol)");
    clientSQLExecute(1, "drop table trade.securities");
    clientSQLExecute(1, "drop schema trade RESTRICT");
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, " +
        "symbol varchar(10) not null, price decimal (30, 20), " +
        "exchange varchar(10) not null, tid int, " +
        "constraint sec_pk primary key (sec_id), " +
        "constraint sec_uq unique (symbol, exchange), " +
        "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex'," +
        " 'lse', 'fse', 'hkse', 'tse')))" + getConditionalOffheapSuffix(enableOffHeap));
    startVMs(0, 1, -1, null, extra);
    stopVMNums(-1);
    clientSQLExecute(1,
        "insert into trade.securities values (1, 'A', 10.0, 'nye', 1)");
    clientSQLExecute(1,
        "insert into trade.securities values (2, 'B', 20.0, 'tse', 2)");
    clientSQLExecute(1, "select * from trade.securities");
  }

  public void test41524() throws Exception {
    // start three network servers
    startVMs(0, 3);
    final int netPort = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);
    startNetworkServer(3, null, null);

    // setup the scripts to be executed
    final String createTablesScripts = TestUtil.getResourcesDir()
        + "/lib/useCase8/create_colocated_schema.sql";
    final String[] loadDataScriptNames = new String[] { "loadCOUNTRIES.sql",
        "loadCITIES.sql", "loadAIRLINES.sql", "loadFLIGHTS1.sql",
        "loadFLIGHTS2.sql", "loadFLIGHTAVAILABILITY1.sql",
        "loadFLIGHTAVAILABILITY2.sql" };
    final File qsDir = ToursDBUtil.getQuickstartDir();
    final String[] loadDataScripts = new String[loadDataScriptNames.length];
    for (int index = 0; index < loadDataScriptNames.length; ++index) {
      loadDataScripts[index] = new File(qsDir, loadDataScriptNames[index])
          .toString();
    }

    // now load data into the servers using this VM as network client
    final Connection conn = TestUtil.getNetConnection(netPort,
        "/;user=q;password=q", null);
    GemFireXDUtils.executeSQLScripts(conn,
        new String[] { createTablesScripts }, false, getLogWriter(), null, null, false);
    GemFireXDUtils
        .executeSQLScripts(conn, loadDataScripts, false, getLogWriter(), null, null, false);

    // some sanity checks on the expected servers as seen in MEMBERS VTI
    final Statement stmt = conn.createStatement();
    final HashSet<String> servers = new HashSet<String>();
    ResultSet rs = stmt
        .executeQuery("select ID from sys.members where hostdata=1");
    while (rs.next()) {
      servers.add(rs.getString("ID"));
    }
    checkServers(servers, 3);
    // check that the tables have been created on all the servers and have some
    // data
    int totalRows = 542;
    for (String server : servers) {
      int numRows = 0;
      rs = stmt.executeQuery("select * from flights where DSID() = '" + server
          + "'");
      while (rs.next()) {
        ++numRows;
      }
      assertTrue("expected at least 100 rows on each server but found "
          + numRows + " on " + server, numRows > 100);
      totalRows -= numRows;
    }
    assertEquals(0, totalRows);
  }

  /**
   * Check that the IDs as given in "servers" set matches that of the started
   * server VMs.
   */
  private void checkServers(Set<String> servers, int expectedNumServers) {
    assertEquals("expected exactly " + expectedNumServers
        + " servers but got: " + servers, expectedNumServers, servers.size());
    for (int vmNum = 0; vmNum < expectedNumServers; ++vmNum) {
      final VM vm = this.serverVMs.get(vmNum);
      for (Map.Entry<DistributedMember, VM> memberEntry : this.members
          .entrySet()) {
        if (vm == memberEntry.getValue()) {
          assertTrue(servers.contains(memberEntry.getKey().toString()));
          break;
        }
      }
    }
  }

  /**
   * Test for UseCase8's hang scenario of bring up servers with redundancy not
   * satisfied and inserts in progress. Also test for UseCase8's hang when
   * restarting servers (support ticket #5965, GFE bugs #41600, #41556).
   */
  public void testUseCase8HangStandaloneServer_5965() throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");

    // start a datastore and create tables using initial script
    final Properties props = new Properties();
    final String ksDir = TestUtil.getResourcesDir() + "/lib/useCase8";
    final String createTablesScripts = ksDir + "/create_colocated_schema.sql";
    props.setProperty(Attribute.INIT_SCRIPTS, createTablesScripts);
    // currently not using recovery-delay (see #41472)
    props.setProperty(Attribute.DEFAULT_RECOVERY_DELAY_PROP, "-1");
    // increase lock timeout for GFXD index manager locks in bucket recovery
    // causing suspect strings in this test (default 10secs for tests is small)
    props.setProperty(GfxdConstants.MAX_LOCKWAIT, "120000");
    startServerVMs(1, 0, null, props);

    // now load data into the server while bringing up other nodes
    final VM firstVM = this.serverVMs.get(0);
    final String[] loadDataScriptNames = new String[] { "loadCOUNTRIES.sql",
        "loadCITIES.sql", "loadAIRLINES.sql", "loadFLIGHTS1.sql",
        "loadFLIGHTS2.sql", "loadFLIGHTAVAILABILITY1.sql",
        "loadFLIGHTAVAILABILITY2.sql" };
    final File qsDir = ToursDBUtil.getQuickstartDir();
    final String[] loadDataScripts = new String[loadDataScriptNames.length];
    String loadScriptName;
    for (int index = 0; index < loadDataScriptNames.length; ++index) {
      loadScriptName = loadDataScriptNames[index];
      if (loadScriptName.startsWith("loadFLIGHTS")) {
        loadDataScripts[index] = new File(ksDir, loadScriptName).toString();
      }
      else {
        loadDataScripts[index] = new File(qsDir, loadScriptName).toString();
      }
    }
    final SerializableRunnable fireQuery = new SerializableRunnable(
        "fire query to check proper data in region") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          final ResultSet rs = conn.createStatement().executeQuery(
              "select * from flights");
          int numResults = 0;
          while (rs.next()) {
            ++numResults;
          }
          assertEquals(542, numResults);
        } catch (Throwable t) {
          throw new CacheException(t) {
          };
        }
      }
    };
    final SerializableRunnable loadData = new SerializableRunnable(
        "load initial data using scripts") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          GemFireXDUtils.executeSQLScripts(conn, loadDataScripts, false, Misc
              .getCacheLogWriter(), null, null, false);
          // now fire a select to check that everything is in order
          fireQuery.run();
        } catch (Throwable t) {
          throw new CacheException(t) {
          };
        }
      }
    };
    // start four more servers while inserts are in progress
    props.remove(Attribute.INIT_SCRIPTS);
    props.setProperty("host-data", "true");
    final AsyncInvocation loadDataAsync = firstVM.invokeAsync(loadData);
    startClientVMs(1, 0, null, props);
    startServerVMs(3, 0, null, props);

    // wait for end of execution of the scripts
    joinAsyncInvocation(loadDataAsync, firstVM);

    // now stop and restart a couple of servers
    // we could get CacheClosedExceptions during deserializations etc.
    addExpectedException(null, new int[] { 3, 4 }, new Object[] {
        CacheClosedException.class,
        DistributedSystemDisconnectedException.class,
        ForceReattemptException.class });

    stopVMNums(-3, -4);
    restartServerVMNums(new int[] { 3, 4 }, 0, null, props);

    removeExpectedException(null, new int[] { 3, 4 }, new Object[] {
        CacheClosedException.class,
        DistributedSystemDisconnectedException.class,
        ForceReattemptException.class });

    // fire queries to check everything is in order
    firstVM.invoke(fireQuery);
  }

  /**
   * Test for UseCase8's hang scenario of bring up servers with redundancy not
   * satisfied and inserts in progress. Also test for UseCase8's hang when
   * restarting servers (support ticket #5965, GFE bugs #41600, #41556).
   * 
   * This one is with blob/clob columns to check for byte[][] storage format.
   */
  public void testUseCase8HangStandaloneServer_5965_2() throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");

    // start a datastore and create tables using initial script
    final Properties props = new Properties();
    final String ksDir = TestUtil.getResourcesDir() + "/lib/useCase8";
    final String createTablesScripts = ksDir + "/create_colocated_schema2.sql";
    props.setProperty(Attribute.INIT_SCRIPTS, createTablesScripts);
    // currently not using recovery-delay (see #41472)
    props.setProperty(Attribute.DEFAULT_RECOVERY_DELAY_PROP, "-1");
    startServerVMs(1, 0, null, props);

    // now load data into the server while bringing up other nodes
    final VM firstVM = this.serverVMs.get(0);
    final String[] loadDataScriptNames = new String[] { "loadCOUNTRIES.sql",
        "loadCITIES.sql", "loadAIRLINES.sql", "loadFLIGHTS1.sql",
        "loadFLIGHTS2.sql", "loadFLIGHTAVAILABILITY1.sql",
        "loadFLIGHTAVAILABILITY2.sql" };
    final File qsDir = ToursDBUtil.getQuickstartDir();
    final String[] loadDataScripts = new String[loadDataScriptNames.length];
    String loadScriptName;
    for (int index = 0; index < loadDataScriptNames.length; ++index) {
      loadScriptName = loadDataScriptNames[index];
      if (loadScriptName.startsWith("loadFLIGHTS")) {
        loadDataScripts[index] = new File(ksDir, loadScriptName).toString();
      }
      else {
        loadDataScripts[index] = new File(qsDir, loadScriptName).toString();
      }
    }
    final SerializableCallable fireQuery = new SerializableCallable(
        "fire query to check proper data in region") {
      @Override
      public Object call() {
        final Connection conn = TestUtil.jdbcConn;
        try {
          final ResultSet rs = conn.createStatement().executeQuery(
              "select * from flights");
          int numResults = 0;
          while (rs.next()) {
            // just do a get on other columns
            getLogWriter().info(String.format("Columns: FLIGHT_ID=%s, "
                + "SEGMENT_NUMBER=%s, ORIG_AIRPORT=%s, DEPART_TIME=%s, "
                + "DEST_AIRPORT=%s, ARRIVE_TIME=%s, MEAL=%s, FLYING_TIME=%s, "
                + "MILES=%s, AIRCRAFT=%s", rs.getString("FLIGHT_ID"),
                rs.getInt(2), rs.getString(3), rs.getTime("DEPART_TIME"),
                rs.getString(5), rs.getTimestamp(6), rs.getString("MEAL"),
                rs.getDouble(8), rs.getString(9), rs.getString("AIRCRAFT")));
            // some sanity checks
            assertEquals("unexpected value: " + rs.getString(1), 6, rs
                .getString(1).length());
            assertTrue("unexpected value " + rs.getInt("SEGMENT_NUMBER"),
                rs.getInt(2) == 1 || rs.getInt(2) == 2);
            assertEquals(3, rs.getString("ORIG_AIRPORT").length());
            assertEquals(3, rs.getString("DEST_AIRPORT").length());
            assertEquals("unexpected value " + rs.getString("MEAL"), 1, rs
                .getString(7).length());
            assertTrue("unexpected value: " + rs.getString("AIRCRAFT"), rs
                .getString(10).length() <= 6);
            // check for the blob column
            final Blob bb = rs.getBlob("PATH_MAP");
            final int bbLen;
            if (bb != null && (bbLen = (int)bb.length()) > 0) {
              assertEquals("0031",
                  StringUtil.toHexString(bb.getBytes(1, bbLen), 0, bbLen));
            }
            ++numResults;
          }
          assertEquals(542, numResults);
        } catch (Throwable t) {
          throw new CacheException(t) {
          };
        }
        // now try with projection on Blob data
        try {
          final ResultSet rs = conn.createStatement().executeQuery(
              "select flight_id, path_map, aircraft, miles from flights");
          int numNonNullBlobs = 0;
          int numResults = 0;
          while (rs.next()) {
            // just do a get on other columns
            getLogWriter().info(String.format("Columns: FLIGHT_ID=%s, "
                + "MILES=%s, AIRCRAFT=%s", rs.getString("FLIGHT_ID"),
                rs.getInt("MILES"), rs.getString(3)));
            // some sanity checks
            assertEquals("unexpected value: " + rs.getString("FLIGHT_ID"), 6,
                rs.getString(1).length());
            assertTrue("unexpected value " + rs.getString("AIRCRAFT"), rs
                .getString(3).length() <= 6);
            // check for the blob column
            final Blob bb = rs.getBlob(2);
            if (bb != null && bb.length() > 0) {
              assertEquals("0031", rs.getString(2));
              ++numNonNullBlobs;
            }
            ++numResults;
          }
          assertEquals(542, numResults);
          return Integer.valueOf(numNonNullBlobs);
        } catch (Throwable t) {
          throw new CacheException(t) {
          };
        }
      }
    };
    final SerializableRunnable loadData = new SerializableRunnable(
        "load initial data using scripts") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          GemFireXDUtils.executeSQLScripts(conn, loadDataScripts, false, Misc
              .getCacheLogWriter(), null, null, false);
          // now fire a select to check that everything is in order
          fireQuery.call();
        } catch (Throwable t) {
          throw new CacheException(t) {
          };
        }
      }
    };
    // start four more servers while inserts are in progress
    props.remove(Attribute.INIT_SCRIPTS);
    props.setProperty("host-data", "true");
    final AsyncInvocation loadDataAsync = firstVM.invokeAsync(loadData);
    startClientVMs(1, 0, null, props);
    startServerVMs(3, 0, null, props);

    // fire updates while inserts are in progress
    final Connection conn = TestUtil.jdbcConn;
    int numResults = conn.createStatement().executeUpdate(
        "update flights set miles = miles + 1500 where miles <= 1000");
    if (numResults == 0) {
      // check with a query (can happen due to retry where update was applied
      //   but bucket moved, then retry updates nothing)
      ResultSet rs = conn.createStatement().executeQuery(
          "select * from flights where miles <= 1000");
      JDBC.assertFullResultSet(rs, new Object[0][], false);
      rs.close();
    }

    getLogWriter().info("updated " + numResults + " rows");

    numResults = conn.createStatement().executeUpdate("update flights "
        + "set path_map = cast(X'0031' as blob(102400)) where miles <= 2500");
    getLogWriter().info("updated " + numResults + " blobs");

    // wait for end of execution of the scripts
    joinAsyncInvocation(loadDataAsync, firstVM);

    // now stop and restart a couple of servers
    // we could get CacheClosedExceptions during deserializations etc.
    addExpectedException(null, new int[] { 3, 4 }, new Object[] {
        CacheClosedException.class,
        DistributedSystemDisconnectedException.class,
        ForceReattemptException.class });

    stopVMNums(-3, -4);
    restartServerVMNums(new int[] { 3, 4 }, 0, null, props);

    removeExpectedException(null, new int[] { 3, 4 }, new Object[] {
        CacheClosedException.class,
        DistributedSystemDisconnectedException.class,
        ForceReattemptException.class });

    // fire queries to check everything is in order
    final Integer numNonNullBlobs = (Integer)firstVM.invoke(fireQuery);
    // we expect at least one blob to have been updated
    getLogWriter().info("Number of blobs updated: " + numNonNullBlobs);
    assertTrue(numNonNullBlobs.intValue() > 0);
  }

  public void testUseCase8_6131() throws Exception {
    this.basicUseCase8_6131(false);
  }

  public void testUseCase8_6131_OffHeap() throws Exception {
    this.basicUseCase8_6131(true);
  }
  /**
   * Despite the name, this test does not reproduce UseCase8's issue #6131 yet.
   */
  private void basicUseCase8_6131(boolean enableOffHeap) throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(1, 2, -1, null, extra);

    final int startIndex = 1000;
    final int totalInserts = 20000;
    clientSQLExecute(1, "create table EMP.PARTITIONTESTTABLE (ID int "
        + "not null, SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))" + getConditionalOffheapSuffix(enableOffHeap));
    for (int index = 1; index <= startIndex; ++index) {
      clientSQLExecute(1, "insert into EMP.PARTITIONTESTTABLE values (" + index
          + ", " + index + ", " + index + ')');
    }

    //final PreparedStatement pstmt = TestUtil.jdbcConn.prepareStatement(
    //    "insert into EMP.PARTITIONTESTTABLE values (?, ?, ?");
    final AtomicInteger threadId = new AtomicInteger(1);
    final int numThreads = 20;
    final Object waitForInserts = new Object();
    final Runnable doInsert = new Runnable() {
      @Override
      public void run() {
        try {
          int myId = threadId.getAndIncrement();
          getLogWriter().info("My thread ID is: " + myId);
          final PreparedStatement pstmt = TestUtil.getConnection()
              .prepareStatement(
                  "insert into EMP.PARTITIONTESTTABLE values (?, ?, ?)");
          synchronized (waitForInserts) {
            while (!signalThread) {
              waitForInserts.wait();
            }
          }
          for (int index = startIndex + myId; index <= totalInserts;
              index += numThreads) {
            pstmt.setInt(1, index);
            pstmt.setInt(2, index);
            pstmt.setInt(3, index);
            pstmt.execute();
            getLogWriter().info("Executed for index: " + index);
          }
        } catch (Throwable t) {
          getLogWriter().error("Unexpected exception", t);
          throw new TestException("failed in execution", t);
        }
      }
    };

    signalThread = false;
    Thread[] threads = new Thread[numThreads];
    for (int index = 0; index < numThreads; ++index) {
      threads[index] = new Thread(doInsert, "testThread-" + index);
    }
    for (int index = 0; index < numThreads; ++index) {
      threads[index].start();
    }
    // now signal all the threads
    Thread.sleep(3000);
    synchronized (waitForInserts) {
      waitForInserts.notifyAll();
      signalThread = true;
    }
    // wait for all threads
    for (int index = 0; index < numThreads; ++index) {
      threads[index].join();
    }

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1 },
        "select count(*) from EMP.PARTITIONTESTTABLE", null, String
            .valueOf(totalInserts));
  }


}
