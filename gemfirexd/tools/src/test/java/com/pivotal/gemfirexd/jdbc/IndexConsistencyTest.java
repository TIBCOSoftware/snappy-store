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
package com.pivotal.gemfirexd.jdbc;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

import org.apache.derbyTesting.junit.JDBC;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;

@SuppressWarnings("serial")
public class IndexConsistencyTest extends JdbcTestBase {
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(IndexConsistencyTest.class));
  }

  public IndexConsistencyTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("drop schema if exists trade restrict");
  }
  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testOnPartitionTable() throws Exception {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Check for PARTITION BY RANGE
    s
        .execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
            + " SECONDID int not null, THIRD int not null, FOURTH int not null, FIFTH int not null,"
            + " primary key (ID, SECONDID))" + " PARTITION BY COLUMN (ID)" + getSuffix());
    s
        .execute("create unique index third_index on EMP.PARTITIONTESTTABLE (THIRD)");
    s.execute("create index fourth_index on EMP.PARTITIONTESTTABLE (FOURTH)");
    s
        .execute("create unique index fifth_index on EMP.PARTITIONTESTTABLE (fifth)");

    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,2,3,4, 10)");
    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(4,5,6,4, 11)");
    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(7,8,9,4, 12)");
    addExpectedException(EntryExistsException.class);
    try {
      s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,3,3,4,13)");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    this.doOffHeapValidations();
    ResultSet rs = null;
    rs = s
        .executeQuery("Select * from EMP.PARTITIONTESTTABLE where id=1 and secondid=3");
    JDBC.assertDrainResults(rs, 0);
    this.doOffHeapValidations();
    String[][] expectedRows = { { "1", "2", "3", "4", "10" } };
    rs = s.executeQuery("Select * from EMP.PARTITIONTESTTABLE where THIRD=3");
    JDBC.assertFullResultSet(rs, expectedRows);
    
    sqlExecuteVerifyText("Select * from EMP.PARTITIONTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", false, false);
    this.doOffHeapValidations();
    try {
      s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,2,5,4,13)");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    this.doOffHeapValidations();
    rs = s
        .executeQuery("Select * from EMP.PARTITIONTESTTABLE where id=1 and secondid=2");
    JDBC.assertFullResultSet(rs, expectedRows);
    this.doOffHeapValidations();
    rs = s.executeQuery("Select * from EMP.PARTITIONTESTTABLE where THIRD=5");
    JDBC.assertDrainResults(rs, 0);

    sqlExecuteVerifyText("Select * from EMP.PARTITIONTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", true, false);
    this.doOffHeapValidations();
    try {
      s
          .execute("UPDATE EMP.PARTITIONTESTTABLE SET third=6, fifth=20 where id=1 and secondid=2");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    this.doOffHeapValidations();
    rs = s
        .executeQuery("Select * from EMP.PARTITIONTESTTABLE where id=1 and secondid=2");
    JDBC.assertFullResultSet(rs, expectedRows);
    this.doOffHeapValidations();
    rs = s.executeQuery("Select * from EMP.PARTITIONTESTTABLE where THIRD=5");
    JDBC.assertDrainResults(rs, 0);

    sqlExecuteVerifyText("Select * from EMP.PARTITIONTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", true, false);
    this.doOffHeapValidations();
    s.execute("DELETE FROM EMP.PARTITIONTESTTABLE WHERE id=1");
    rs = s
        .executeQuery("Select * from EMP.PARTITIONTESTTABLE where id=1 and secondid=2");
    JDBC.assertDrainResults(rs, 0);
    this.doOffHeapValidations();
    // drop the table
    s.execute("drop table EMP.PARTITIONTESTTABLE");
    this.waitTillAllClear();
    // drop schema and shutdown
    s.execute("drop schema EMP RESTRICT");
    this.waitTillAllClear();
    this.doOffHeapValidations();
    

  }

  public void testOnReplicatedTable() throws Exception {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Check for PARTITION BY RANGE
    s
        .execute("create table EMP.REPLICATEDTESTTABLE (ID int not null, "
            + " SECONDID int not null, THIRD int not null, FOURTH int not null, FIFTH int not null,"
            + " primary key (SECONDID)) REPLICATE" + getSuffix());
    s
        .execute("create unique index third_index on EMP.REPLICATEDTESTTABLE (THIRD)");
    s.execute("create index fourth_index on EMP.REPLICATEDTESTTABLE (FOURTH)");
    s
        .execute("create unique index fifth_index on EMP.REPLICATEDTESTTABLE (fifth)");

    s.execute("INSERT INTO EMP.REPLICATEDTESTTABLE values(1,2,3,4, 10)");
    s.execute("INSERT INTO EMP.REPLICATEDTESTTABLE values(4,5,6,4, 11)");
    s.execute("INSERT INTO EMP.REPLICATEDTESTTABLE values(7,8,9,4, 12)");
    addExpectedException(EntryExistsException.class);
    try {
      s.execute("INSERT INTO EMP.REPLICATEDTESTTABLE values(1,3,3,4,13)");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    ResultSet rs = null;
    rs = s
        .executeQuery("Select * from EMP.REPLICATEDTESTTABLE where id=1 and secondid=3");
    JDBC.assertDrainResults(rs, 0);

    String[][] expectedRows = { { "1", "2", "3", "4", "10" } };
    rs = s.executeQuery("Select * from EMP.REPLICATEDTESTTABLE where THIRD=3");
    JDBC.assertFullResultSet(rs, expectedRows);

    sqlExecuteVerifyText("Select * from EMP.REPLICATEDTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", true, false);

    try {
      s.execute("INSERT INTO EMP.REPLICATEDTESTTABLE values(1,2,5,4,13)");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    rs = s
        .executeQuery("Select * from EMP.REPLICATEDTESTTABLE where id=1 and secondid=2");
    JDBC.assertFullResultSet(rs, expectedRows);

    rs = s.executeQuery("Select * from EMP.REPLICATEDTESTTABLE where THIRD=5");
    JDBC.assertDrainResults(rs, 0);

    sqlExecuteVerifyText("Select * from EMP.REPLICATEDTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", false, false);

    try {
      s
          .execute("UPDATE EMP.REPLICATEDTESTTABLE SET third=6, fifth=20 where id=1 and secondid=2");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    rs = s
        .executeQuery("Select * from EMP.REPLICATEDTESTTABLE where id=1 and secondid=2");
    JDBC.assertFullResultSet(rs, expectedRows);

    rs = s.executeQuery("Select * from EMP.REPLICATEDTESTTABLE where THIRD=5");
    JDBC.assertDrainResults(rs, 0);

    sqlExecuteVerifyText("Select * from EMP.REPLICATEDTESTTABLE where FOURTH=4",
        TestUtil.getResourcesDir() + "/lib/checkIndex.xml",
        "testtable_four", false, false);

    s.execute("DELETE FROM EMP.REPLICATEDTESTTABLE WHERE id=1");
    rs = s
        .executeQuery("Select * from EMP.REPLICATEDTESTTABLE where id=1 and secondid=2");
    JDBC.assertDrainResults(rs, 0);

    // drop the table
    s.execute("drop table EMP.REPLICATEDTESTTABLE");
    this.waitTillAllClear();
    // drop schema and shutdown
    s.execute("drop schema EMP RESTRICT");
  }

  private void checkInsertUpdate(boolean hasGlobalIndex) throws Exception {
    // Perform an insert
    sqlExecute("insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)", false);
    sqlExecute("insert into trade.test values (1, 1)", true);

    // TODO: the TABLE scan type below should be changed after fix for #40980
    ScanType uniqueScanType = (hasGlobalIndex ? ScanType.TABLE
        : ScanType.SORTEDMAPINDEX);

    // Set the observer to check that proper scans are being opened
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.putInstance(observer);

    // Verify the inserted value using primary key lookup
    sqlExecuteVerifyText("select * from trade.customers where cid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_insert", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    sqlExecute("select * from trade.test t, trade.customers c "
        + "where t.tid = c.tid", false);
    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.addExpectedScanType("trade.test", uniqueScanType);
    observer.checkAndClear();

    sqlExecute("select * from trade.test t, trade.customers c "
        + "where t.tid > c.tid", false);
    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.addExpectedScanType("trade.test", uniqueScanType);
    observer.checkAndClear();

    sqlExecute("select c.*, t.tid from trade.test t, trade.customers c "
        + "where t.tid = c.tid", false);
    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.addExpectedScanType("trade.test", uniqueScanType);
    observer.checkAndClear();

    sqlExecute("select * from trade.test t where t.tid = 1", false);
    // Check the scan types opened
    observer.addExpectedScanType("trade.test", uniqueScanType);
    observer.checkAndClear();

    PreparedStatement pstmt = getPreparedStatement("select * from trade.test "
        + "where tid > ?");
    pstmt.setInt(1, 0);
    pstmt.executeQuery();
    pstmt.close();
    // Check the scan types opened
    observer.addExpectedScanType("trade.test", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_insert", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    sqlExecuteVerifyText("select * from trade.customers where since>=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_insert", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_insert", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value by region iteration
    sqlExecuteVerifyText("select * from trade.customers", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_insert", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Check scan types for queries which have a covering index
    // Now we don't favour an index if it is covering one since indexes
    // need to go to the table row in any case.
    sqlExecute("select tid from trade.customers", false);
    sqlExecute("select tid from trade.test", true);
    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.addExpectedScanType("trade.test", ScanType.TABLE);
    observer.checkAndClear();

    // Now update one column of the table and perform the selects again
    sqlExecute("update trade.customers set since=2 where cid=1", false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    // Verify the inserted value using primary key lookup
    sqlExecuteVerifyText(
        "select cust_name, cid, since, addr, tid from trade.customers "
            + "where cid=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value by region iteration
    sqlExecuteVerifyText("select * from trade.customers", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Now update two columns of the table using unique key
    // and perform the selects again
    sqlExecute("update trade.customers set since=3, "
        + "cust_name='XXXX3' where tid=1", true);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using primary key lookup
    sqlExecuteVerifyText(
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update2", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=3", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update2", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update2", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value by region iteration
    sqlExecuteVerifyText("select * from trade.customers", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update2", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Then update three columns of the table using unique key
    // and perform the selects again
    sqlExecute("update trade.customers set tid=2, since=2, "
        + "cust_name='XXXX2' where tid=1", false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using primary key lookup
    sqlExecuteVerifyText(
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update3", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update3", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=3", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update3", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value by region iteration
    sqlExecuteVerifyText("select * from trade.customers", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update3", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Finally update three columns of the table using non-key field
    // and perform the selects again
    sqlExecute("update trade.customers set tid=4, since=4, "
        + "cust_name='XXXX4' where since=2", false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using primary key lookup
    sqlExecuteVerifyText(
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update4", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=3", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerifyText("select * from trade.customers where since=4", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update4", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=1", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=2", getResourcesDir()
        + "/lib/checkCreateTable.xml", "empty", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value using unique key field lookup
    sqlExecuteVerifyText("select * from trade.customers where tid=4", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update4", false, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", uniqueScanType);
    observer.checkAndClear();

    // Verify the inserted value by region iteration
    sqlExecuteVerifyText("select * from trade.customers", getResourcesDir()
        + "/lib/checkCreateTable.xml", "trade_update4", true, false);

    // Check the scan types opened
    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();
  }

  // test for insert/select/update in replicated tables
  public void testOnPRAndReplicatedTable2() throws Exception {
    setupConnection();
    // Create a schema

    sqlExecute("create schema  trade", false);

    // Create a set of expected region attributes for the schema
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setScope(Scope.DISTRIBUTED_NO_ACK);
    expectedAttrs.setDataPolicy(DataPolicy.EMPTY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
   
    // Test the schema region attributes on client and servers
    verifyRegionProperties("TRADE", null, regionAttributesToXML(expectedAttrs));

    // Create a replicated table in the above schema
    sqlExecute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid), unique (tid)) replicate" + getSuffix(), true);

    // Create a set of expected region attributes for the table
    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    additionalTableAttributes(expectedAttrs);
    // disk store name will be default for overflow
    // Test the region attributes on servers
    verifyRegionProperties("TRADE", "CUSTOMERS",
        regionAttributesToXML(expectedAttrs));

    sqlExecute("create table trade.test (cid int, tid int, primary key (cid),"
        + " unique (tid)) replicate" + getSuffix(), false);

    checkInsertUpdate(false);

    // Now recreate the table as a PR and check inserts/updates again
    sqlExecute("drop table trade.customers", false);
    this.waitTillAllClear();
    sqlExecute("drop table trade.test", false);
    this.waitTillAllClear();
    // Create a partitioned table in the above schema
    sqlExecute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid), unique (tid))"+ getSuffix(), true);

    // Create a set of expected region attributes for the table
    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    PartitionAttributesFactory pattrsFact = new PartitionAttributesFactory();
    if(getSuffix().toLowerCase().indexOf("offheap") != -1) {
      pattrsFact.setLocalMaxMemory(500);
    }
    pattrsFact.setPartitionResolver(new GfxdPartitionByExpressionResolver());
    PartitionAttributes< ?, ?> pattrs = pattrsFact.create();
        
    expectedAttrs.setPartitionAttributes(pattrs);
    additionalTableAttributes(expectedAttrs);

    // Test the region attributes on servers
    verifyRegionProperties("TRADE", "CUSTOMERS",
        regionAttributesToXML(expectedAttrs));

    sqlExecute("create table trade.test (cid int, tid int, primary key (cid),"
        + " unique (tid))"+ getSuffix(), false);

    checkInsertUpdate(true);
    sqlExecute("drop table if exists trade.test", false);
    sqlExecute("drop table if exists trade.customers", false);
    sqlExecute("drop schema if exists trade restrict ", false);

  }

  protected void additionalTableAttributes(
      RegionAttributesCreation expectedAttrs) {
  }

  public void testConsistencyChecker() throws Exception {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
  
    // create a table with some indexes
    s.execute("create table t1(i int, s smallint, c10 char(10), vc10 varchar(10), dc decimal(5,2))"+getSuffix());
    s.execute("create index t1_i on t1(i)");;
    s.execute("create index t1_s on t1(s)");
    s.execute("create index t1_c10 on t1(c10)");
    s.execute("create index t1_vc10 on t1(vc10)");
    s.execute("create index t1_dc on t1(dc)");


    // populate the tables
    s.execute("insert into t1 values (1, 11, '1 1', '1 1 1 ', 111.11)");
    s.execute("insert into t1 values (2, 22, '2 2', '2 2 2 ', 222.22)");
    s.execute("insert into t1 values (3, 33, '3 3', '3 3 3 ', 333.33)");
    s.execute("insert into t1 values (4, 44, '4 4', '4 4 4 ', 444.44)");

    //-- verify that everything is alright
    final String schemaName = getCurrentDefaultSchemaName();
    ResultSet rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('"
        + schemaName + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("CREATE PROCEDURE RFHR(P1 VARCHAR(128), P2 VARCHAR(128)) LANGUAGE JAVA EXTERNAL NAME 'org.apache.derbyTesting.functionTests.util.T_ConsistencyChecker.reinsertFirstHeapRow' PARAMETER STYLE JAVA");
    s.execute("CREATE PROCEDURE DFHR(P1 VARCHAR(128), P2 VARCHAR(128)) LANGUAGE JAVA EXTERNAL NAME 'org.apache.derbyTesting.functionTests.util.T_ConsistencyChecker.deleteFirstHeapRow' PARAMETER STYLE JAVA");
    s.execute("CREATE PROCEDURE NFHR(P1 VARCHAR(128), P2 VARCHAR(128)) LANGUAGE JAVA EXTERNAL NAME 'org.apache.derbyTesting.functionTests.util.T_ConsistencyChecker.nullFirstHeapRow' PARAMETER STYLE JAVA");


    //autocommit off;

    //-- differing row counts
    s.execute("call RFHR('" + schemaName + "', 'T1')");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");
   // -- drop and recreate each index to see differing count move to next index
    s.execute("drop index t1_i");
    s.execute("create index t1_i on t1(i)");

    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_s");
    s.execute("create index t1_s on t1(s)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_c10");
    s.execute("create index t1_c10 on t1(c10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_vc10");
    s.execute("create index t1_vc10 on t1(vc10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_dc");
    s.execute("create index t1_dc on t1(dc)");
   // -- everything should be back to normal
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    //-- delete 1st row from heap
    s.execute("call DFHR('" + schemaName + "', 'T1')");
   rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");
   // -- drop and recreate each index to see differing count move to next index
    s.execute("drop index t1_i");
    s.execute("create index t1_i on t1(i)");

    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_s");
    s.execute("create index t1_s on t1(s)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_c10");
    s.execute("create index t1_c10 on t1(c10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_vc10");
    s.execute("create index t1_vc10 on t1(vc10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_dc");
    s.execute("create index t1_dc on t1(dc)");
   // -- everything should be back to normal
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("call NFHR('" + schemaName + "', 'T1')");
    rs =s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    // -- drop and recreate each index to see differing count move to next index
    s.execute("drop index t1_i");
    s.execute("create index t1_i on t1(i)");

    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_s");
    s.execute("create index t1_s on t1(s)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_c10");
    s.execute("create index t1_c10 on t1(c10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_vc10");
    s.execute("create index t1_vc10 on t1(vc10)");
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

    s.execute("drop index t1_dc");
    s.execute("create index t1_dc on t1(dc)");
    // -- everything should be back to normal
    rs = s.executeQuery("values SYSCS_UTIL.CHECK_TABLE('" + schemaName
        + "', 'T1')");
    JDBC.assertSingleValueResultSet(rs, "1");

/*    -- RESOLVE - Next test commented out due to inconsistency in store error
    -- message (sane vs. insane).  Check every index once store returns
    -- consistent error.
    -- insert a row with a bad row location into index
    -- call org.apache.derbyTesting.functionTests.util.T_ConsistencyChecker::insertBadRowLocation('" + schemaName + "', 'T1', 'T1_I');
    -- values SYSCS_UTIL.CHECK_TABLE('" + schemaName + "', 'T1');
*/
  //  -- cleanup
    s.execute("drop table t1");
    this.waitTillAllClear();
  }

  public void testBug40879_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create schema  trade ");
    s.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, "
        + "exchange), constraint exc_ch check (exchange in ('nasdaq', "
        + "'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");
    s.execute(" create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))  partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, "
        + "VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, "
        + "VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)");
    s.execute("create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint cust_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id) "
        + "on delete restrict, constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty)) "
        + "partition by range (cid) (VALUES BETWEEN 0 AND 699, "
        + "VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, "
        + "VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, "
        + "VALUES BETWEEN 1800 AND 10000) colocate with (trade.customers)");

    PreparedStatement secInsert = conn
        .prepareStatement("Insert into trade.securities values (?,?,?,?,?)");
    PreparedStatement custInsert = conn
        .prepareStatement("Insert into trade.customers values (?,?,?,?,?)");
    PreparedStatement pfInsert = conn
        .prepareStatement("Insert into trade.portfolio values (?,?,?,?,?,?)");
    //Security
    for (int i = 1; i < 25; ++i) {
      secInsert.setInt(1, i);
      secInsert.setString(2, "xfv" + i);
      secInsert.setFloat(3, 27.1F);
      secInsert.setString(4, "tse");
      secInsert.setInt(5, 17);
      assertEquals(secInsert.executeUpdate(), 1);
    }
    //Customer
    for (int i = 1; i < 50; ++i) {
      custInsert.setInt(1, i);
      custInsert.setString(2, "name" + i);
      custInsert.setDate(3, Date.valueOf("2005-12-16"));
      custInsert.setString(4, "" + i);
      custInsert.setInt(5, 17);
      assertEquals(custInsert.executeUpdate(), 1);
    }
    //Portfolio
    for (int j = 1; j < 50; ++j) {
      for (int k = 1; k < 11; ++k) {
        pfInsert.setInt(1, j);
        pfInsert.setInt(2, k);
        pfInsert.setInt(3, 147);
        pfInsert.setInt(4, 147);
        pfInsert.setDouble(5, 12011.37000000000147000000);
        pfInsert.setInt(6, 17);
        assertEquals(pfInsert.executeUpdate(), 1);
      }
    }
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public final double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
              OpenMemIndex memIndex, double optimzerEvalutatedCost) {
            if (memIndex.getBaseContainer().getRegion().getFullPath()
                .toLowerCase().indexOf("/trade/customer") != -1) {
              return Double.MAX_VALUE;
            }
            else if (memIndex.getBaseContainer().getRegion().getFullPath()
                .toLowerCase().indexOf("/trade/portfolio") != -1) {
              return 1;
            }
            else {
              return optimzerEvalutatedCost;
            }
          }

          @Override
          public final double overrideDerbyOptimizerCostForMemHeapScan(
              GemFireContainer gfContainer, double optimzerEvalutatedCost) {
            if (gfContainer.getRegion().getFullPath().toLowerCase()
                .indexOf("/trade/customer") != -1) {
              return 1;
            }
            else {
              return optimzerEvalutatedCost;
            }
          }

          @Override
          public final double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
              OpenMemIndex memIndex, double optimzerEvalutatedCost) {
            if (memIndex.getBaseContainer().getRegion().getFullPath()
                .toLowerCase().indexOf("/trade/portfolio") != -1) {
              return Double.MAX_VALUE;
            }
            else {
              return optimzerEvalutatedCost;
            }
          }
        });
    String sql = "select c.cust_name,c.since,c.addr, f.cid,f.sid,f.subTotal, "
        + "f.tid from trade.customers c, trade.securities, trade.portfolio f "
        + "where c.cid = f.cid and f.sid = sec_id and f.tid = ? and c.cid = ?";
    PreparedStatement query = conn.prepareStatement(sql);
    query.setInt(1, 17);
    query.setInt(2, 10);
    //query.setInt(3, 11);
    ResultSet rs = query.executeQuery();
    int len = 0;
    while (rs.next()) {
      ++len;
    }
    assertEquals(10, len);
    sqlExecute("drop table if exists trade.portfolio", false);
    sqlExecute("drop table if exists trade.customers", false);
    sqlExecute("drop table if exists trade.securities", false);
    sqlExecute("drop schema if exists trade restrict", false);

  }

  public void testBug40879_2() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create schema  trade ");
    s
        .execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");
    s
        .execute(" create table trade.customers (cid int not null, cust_name varchar(100), since date, addr varchar(100), tid int, primary key (cid))  partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)");
    s
        .execute(" create table trade.portfolio (cid int not null, sid int not null, qty int not null, availQty int not null, subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), constraint cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, constraint sec_fk foreign key (sid) references trade.securities (sec_id) on delete restrict, constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))  partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)  colocate with (trade.customers)");
    PreparedStatement secInsert = conn
        .prepareStatement("Insert into trade.securities values (?,?,?,?,?)");
    PreparedStatement custInsert = conn
        .prepareStatement("Insert into trade.customers values (?,?,?,?,?)");
    PreparedStatement pfInsert = conn
        .prepareStatement("Insert into trade.portfolio values (?,?,?,?,?,?)");
    //Security
    for (int i = 1; i < 5; ++i) {
      secInsert.setInt(1, i);
      secInsert.setString(2, "xfv" + i);
      secInsert.setFloat(3, 27.1F);
      secInsert.setString(4, "tse");
      secInsert.setInt(5, 17);
      assertEquals(secInsert.executeUpdate(), 1);
    }
    //Customer
    for (int i = 1; i < 5; ++i) {
      custInsert.setInt(1, i);
      custInsert.setString(2, "name" + i);
      custInsert.setDate(3, Date.valueOf("2005-12-16"));
      custInsert.setString(4, "" + i);
      custInsert.setInt(5, 17);
      assertEquals(custInsert.executeUpdate(), 1);
    }
    //Portfolio
    for (int j = 1; j < 5; ++j) {
      for (int k = 1; k < 5; ++k) {
        pfInsert.setInt(1, j);
        pfInsert.setInt(2, k);
        pfInsert.setInt(3, 147);
        pfInsert.setInt(4, 147);
        pfInsert.setDouble(5, 12011.37000000000147000000);
        pfInsert.setInt(6, 17);
        assertEquals(pfInsert.executeUpdate(), 1);
      }
    }
    GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
      @Override
      public final double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        if(memIndex.getBaseContainer().getRegion().getFullPath().toLowerCase().indexOf("/trade/customer") != -1) {
         return Double.MAX_VALUE; 
        }else if(memIndex.getBaseContainer().getRegion().getFullPath().toLowerCase().indexOf("/trade/portfolio") != -1) {
         return 1; 
        }
        else {
          return optimzerEvalutatedCost;
        }
      }

      @Override
      public final double overrideDerbyOptimizerCostForMemHeapScan(GemFireContainer gfContainer,double optimzerEvalutatedCost)
      {
        if(gfContainer.getRegion().getFullPath().toLowerCase().indexOf("/trade/customer") != -1) {
          return 1; 
        }else {
          return optimzerEvalutatedCost;
        }
      }

      @Override
      public final double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        if(memIndex.getBaseContainer().getRegion().getFullPath().toLowerCase().indexOf("/trade/portfolio") != -1) {
          return Double.MAX_VALUE; 
         }else {
           return optimzerEvalutatedCost;
         }
      }
    });
   // GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    String sql = "select c.cust_name,c.since,c.addr, f.cid,f.sid,f.subTotal, f.tid from trade.customers c, trade.securities , trade.portfolio f where c.cid= f.cid "
        + "and f.sid = sec_id and f.tid = ? and c.cid = ? ";
    PreparedStatement query = conn.prepareStatement(sql);
    query.setInt(1, 17);
    query.setInt(2, 1);
    //query.setInt(3, 11);
    ResultSet rs = query.executeQuery();
    int len = 0;
    while (rs.next()) {
      ++len;
    }
    assertEquals(4, len);
    s.execute("drop table if exists trade.portfolio");
    s.execute("drop table if exists trade.customers");
    s.execute("drop table if exists trade.securities");
    s.execute("drop schema if exists trade restrict");


  }

  public void testBug40998() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute(" create table INSTRUMENTS (id varchar(20) not null "
        + "primary key, sector_id int) PARTITION BY PRIMARY KEY");
    s.execute(" create table POSITIONS (id int not null primary key, "
        + "instrument varchar(20) not null) PARTITION BY COLUMN "
        + "(instrument) COLOCATE WITH (INSTRUMENTS)");

    PreparedStatement instruInsert = conn
        .prepareStatement("Insert into instruments values (?, ?)");
    PreparedStatement posInsert = conn
        .prepareStatement("Insert into positions values (?, ?)");
    // Instruments
    for (int i = 1; i < 51; ++i) {
      instruInsert.setString(1, i + "");
      instruInsert.setInt(2, i + 1);

      assertEquals(instruInsert.executeUpdate(), 1);
    }
    // Positions
    for (int i = 1; i < 51; ++i) {
      posInsert.setInt(1, 50 + i);
      posInsert.setString(2, "" + i);
      assertEquals(posInsert.executeUpdate(), 1);
    }

    String sql = "select * from INSTRUMENTS i,POSITIONS p "
        + "where i.id = p.instrument and i.id in (?, ?, ?)";
    PreparedStatement query = conn.prepareStatement(sql);
    query.setInt(1, 1);
    query.setInt(2, 2);
    query.setInt(3, 3);

    // Set the observer to check that proper scans are being opened
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.putInstance(observer);

    ResultSet rs = query.executeQuery();
    int len = 0;
    while (rs.next()) {
      ++len;
    }
    assertEquals(3, len);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".INSTRUMENTS", ScanType.HASH1INDEX);
    observer.checkAndClear();
  }

  public String getSuffix() {
    return  " ";
  }
  
  public void waitTillAllClear() { }
}
