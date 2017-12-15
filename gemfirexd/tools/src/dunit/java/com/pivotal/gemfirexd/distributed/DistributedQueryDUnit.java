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
/**
 * 
 */
package com.pivotal.gemfirexd.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Calendar;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.Instantiator;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.TestUtil.ScanType;
import com.pivotal.gemfirexd.TestUtil.ScanTypeQueryObserver;
import com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.MemScanController;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.query.QueryChecksTest;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatterTest.NullHolder;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDate;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTime;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derbyTesting.junit.JDBC;

/**
 * Tests the basic functionality of distribution of the query across the PR
 * nodes & fetching of the result
 * 
 * @author Asif
 * @since 6.0
 */
@SuppressWarnings("serial")
public class DistributedQueryDUnit extends DistributedSQLTestBase {

  public DistributedQueryDUnit(String name) {
    super(name);
  }

  public void testDistributedQuery() throws Exception {
    // Start one client a one server
    startVMs(1, 1);

    // Create a schema
    clientSQLExecute(1, "create schema EMP");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, primary key (ID))");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (1, 'First')");

    clientSQLExecute(1, "create synonym synForEmpTestTable for EMP.TESTTABLE");

    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID, DESCRIPTION from synForEmpTestTable", null, null,
        false /* do not use prep stmnt */, false /* do not check for type information */);
    clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
  }

  /**
   * Uses  Uses <TEST>/lib/distributedQuery.xml
   * ResultSet ID = "q_1"
   * @throws Exception
   */
  public void testBasicDistributedQueryUsingPreparedStatement() throws Exception {
    // Start one client a two servers
    startServerVMs(2, 0, "SG1");
    startClientVMs(1, 0, null);

    // Create a schema with default server groups GemFire extension
//    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, primary key (ID))");
    clientSQLExecute(1, "insert into TESTTABLE values (1, 'First')");
    clientSQLExecute(1, "insert into TESTTABLE values (2, 'Second')");

    clientSQLExecute(1, "create synonym synForTestTable for TESTTABLE");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID, DESCRIPTION from synForTestTable",  TestUtil.getResourcesDir() + "/lib/distributedQuery.xml", "q_1",
        true /* do not use prep stmnt */, false /* do not check for type information */);
    clientSQLExecute(1, "Drop table TESTTABLE ");
  }


  /**
   * Uses  Uses <TEST>/lib/distributedQuery.xml
   * ResultSet ID = "q_1"
   * @throws Exception
   */
  public void testBasicDistributedQueryUsingPreparedStatementWithParamterization()
      throws Exception {
    // Start one client a two servers
    startServerVMs(2, 0, "SG1");
    startClientVMs(1, 0, null);

    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, primary key (ID))");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (1, 'First')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (2, 'Second')");
    // Set up a new connection so that we do not use the connection used for ddl
    // statement
    /** get a new connection with default properties */
    Connection conn = TestUtil.getConnection();
    PreparedStatement ps = conn
        .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE where id > ? ");
    ps.setInt(1, 0);
    ps.execute();
    TestUtil.verifyResults(true, ps, false, TestUtil.getResourcesDir()
        + "/lib/distributedQuery.xml", "q_1");
    clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
  }

  /**
   * Test for proper locking with DDL and DML mix.
   */
  public void testDDLDML() throws Exception {

    // Start a client and three servers
    startVMs(1, 3);

    // Create a schema and table in it
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid))");

    // Create an index and drop it
    clientSQLExecute(1, "create index tid_idx on trade.customers (TID)");
    clientSQLExecute(1, "drop index trade.tid_idx");

    // rebalancing may see an occasional RegionDestroyedException due to
    // multiple creates/drops below
    // Occasional LockTimeout's below in resource manager rebalancing due to
    // possible deadlock and the retry mechanism for deadlock resolution
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        RegionDestroyedException.class, LockTimeoutException.class });

    // Drop the table
    clientSQLExecute(1, "drop table trade.customers");

    // Create tables and do some inserts and updates
    createTablesAndPopulateData(0);
    serverSQLExecute(1, "update trade.customers set tid = 10 where cid = 1");

    // Check for iteration of multiple ResultSets simultaneously
    SerializableRunnable multiIter = new SerializableRunnable(
        "iterate multiple open ResultSets") {
      @Override
      public void run() throws CacheException {
        try {
          final Connection conn = TestUtil.jdbcConn;
          final PreparedStatement pstmt1 = conn
              .prepareStatement("select * from trade.customers");
          final PreparedStatement pstmt2 = conn
              .prepareStatement("select * from trade.portfolio");
          final ResultSet rs1 = pstmt1.executeQuery();
          final ResultSet rs2 = pstmt2.executeQuery();
          int numResults1 = 0;
          int numResults2 = 0;
          for (int cnt = 1; cnt <= 10; ++cnt) {
            rs1.next();
            ++numResults1;
            rs2.next();
            ++numResults2;
          }
          while (rs1.next()) {
            ++numResults1;
          }
          while (rs2.next()) {
            ++numResults2;
          }
          assertEquals(100, numResults1);
          assertEquals(45, numResults2);
        } catch (SQLException ex) {
          throw new CacheException(ex) {
          };
        }
      }
    };

    serverExecute(1, multiIter);
    serverExecute(2, multiIter);
    serverExecute(3, multiIter);
    multiIter.run();

    // check successful lock and unlock without explicit ResultSet.close()
    // consuming all results
    PreparedStatement prepStmt = TestUtil.jdbcConn
        .prepareStatement("select * from trade.customers");
    clientSQLExecute(1, "delete from trade.customers where cid=2");
    clientSQLExecute(1, "drop table trade.portfolio");
    prepStmt.execute();
    ResultSet rs = prepStmt.getResultSet();
    for (int i = 0; i < 20 && rs.next(); ++i) {
      getLogWriter().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    while (rs.next()) {
      getLogWriter().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    serverSQLExecute(2, "drop table trade.customers");

    // check successful lock and unlock with ResultSet.close()
    createTablesAndPopulateData(0);

    serverSQLExecute(1,
        "create synonym synForTradeCustomers for trade.customers");
    serverSQLExecute(3, "delete from synForTradeCustomers where cid=2");

    serverSQLExecute(2, "drop table trade.portfolio");

    prepStmt = TestUtil.jdbcConn
        .prepareStatement("select * from trade.customers");
    prepStmt.execute();
    rs = prepStmt.getResultSet();
    for (int i = 0; i < 20 && rs.next(); ++i) {
      getLogWriter().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    rs.close();
    serverSQLExecute(1, "drop table trade.customers");

    // check lock release with just inserts
    createTablesAndPopulateData(0);
    serverSQLExecute(1, "drop table trade.portfolio");
    serverSQLExecute(2, "drop table trade.customers");

    // check lock release with DMLs throwing exceptions
    createTablesAndPopulateData(0);
    checkKeyViolation(-2, "update trade.portfolio set qty=qty-100 where "
        + "cid>=0", "23513", "check constraint violation");
    serverSQLExecute(2, "drop table trade.portfolio");
    serverSQLExecute(3, "drop table trade.customers");

    // check no lock timeout with no ResultSet.close() and not consuming all
    // results without explicitly closing RS
    createTablesAndPopulateData(0);

    serverSQLExecute(1, "delete from trade.customers where cid=2");
    serverSQLExecute(3, "drop table trade.portfolio");
    prepStmt = TestUtil.jdbcConn
        .prepareStatement("select * from trade.customers");
    prepStmt.execute();
    rs = prepStmt.getResultSet();
    int numResults = 0;
    while (rs.next()) {
      getLogWriter().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
      numResults++;
    }
    assertEquals(99, numResults);
    serverSQLExecute(2, "drop table trade.customers");

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { RegionDestroyedException.class,
            LockTimeoutException.class });
  }

  public void testBug43359() throws Exception {

    // Not valid for transactions
    if (isTransactional) {
      return;
    }

    // Start a client and one servers
    startVMs(1, 1);
    // Create a schema and table in it
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid)) redundancy 1");

    // Create an index and drop it
    clientSQLExecute(1, "create index tid_idx on trade.customers (TID)");
    //This will create a bucket B1 on server1 which will be primary
    clientSQLExecute(1, "insert into trade.customers values(1,'name_1','addr_1',1)");
   //Attach a sql observer on server 1

    SerializableRunnable dbShutter = new SerializableRunnable(
        "attach observer on server1") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserver sqo = new GemFireXDQueryObserverAdapter() {
          @Override
          public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
              EntryEventImpl event, RegionEntry entry) {
            Thread th = new Thread(new Runnable() {
              @Override
              public void run() {

                  Misc.getGemFireCache().close("no reason", null,
                      false, true);

              }
            });
            th.start();
            try {
              th.join();
            } catch (InterruptedException ignore) {

            }
          }
        };
        GemFireXDQueryObserverHolder.setInstance(sqo);
      }
    };

    startServerVMs(1, 0, null);
    Thread.sleep(5000);
    serverExecute(1, dbShutter);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("insert into trade.customers values(114,'name_114','addr_114',1)");

    ResultSet rs = stmt.executeQuery("select * from trade.customers where cid >= 0");
    int numRows = 0;
    while(rs.next()) {
      ++numRows;
    }
    assertEquals(2,numRows);

  }


  /** Test for bug #40595. */
  public void test40595() throws Exception {

    // Start a client and a server
    startVMs(1, 1);

    // Create a table
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid))");

    // Perform dummy selects and drop the table
    PreparedStatement prepStmt = TestUtil.jdbcConn
        .prepareStatement("select * from trade.customers");
    prepStmt.execute();
    prepStmt.getResultSet().close();
    clientSQLExecute(1, "drop table trade.customers");

    // Create the table again
    clientSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid))");
    // Try to do the selects again using the same PreparedStatement
    prepStmt.execute();
    prepStmt.getResultSet().close();
    clientSQLExecute(1, "drop table trade.customers");
  }

  /**
   * test serialization/deserialization using IDS.writeSignedVL for normal GFE
   * regions
   */
  public void test41179_1() throws Exception {
    // start a couple of VMs to use as datastore and this VM as accessor
    startVMs(1, 2);

    VM vm0 = this.serverVMs.get(0);
    VM vm1 = this.serverVMs.get(1);

    try {
      // create region for puts/gets
      vm0.invoke(DistributedQueryDUnit.class, "createRegionForTest",
          new Object[] { Boolean.FALSE });
      vm1.invoke(DistributedQueryDUnit.class, "createRegionForTest",
          new Object[] { Boolean.FALSE });
      Cache cache = CacheFactory.getAnyInstance();
      String regionName = createRegionForTest(Boolean.TRUE);
      Region<SerializableCompactLong, SerializableCompactLong> region = cache
          .getRegion(regionName);

      // test all combos of valueToTest and + and -offsets
      long[] valuesToTest = new long[] { 0, Byte.MAX_VALUE, Byte.MIN_VALUE,
          Short.MAX_VALUE, Short.MIN_VALUE, Integer.MAX_VALUE,
          Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE };
      long[] offsets = new long[] { 0, 1, 4, 9, 14, 15, 16, -1, -4, -9, -14,
          -15, -16 };

      // do puts of all combos
      for (long valueToTest : valuesToTest) {
        for (long offset : offsets) {
          if (valueToTest == Long.MAX_VALUE && offset > 0) {
            continue;
          }
          if (valueToTest == Long.MIN_VALUE && offset < 0) {
            continue;
          }
          long val = valueToTest + offset;
          SerializableCompactLong compactVal = new SerializableCompactLong(val);
          region.put(compactVal, compactVal);
        }
      }

      // now do gets and check the return values
      for (long valueToTest : valuesToTest) {
        for (long offset : offsets) {
          if (valueToTest == Long.MAX_VALUE && offset > 0) {
            continue;
          }
          if (valueToTest == Long.MIN_VALUE && offset < 0) {
            continue;
          }
          long expectedVal = valueToTest + offset;
          SerializableCompactLong compactVal = new SerializableCompactLong(
              expectedVal);
          SerializableCompactLong val = region.get(compactVal);
          assertEquals(expectedVal, val.val);
        }
      }
    } finally {
      stopAllVMs();
    }
  }

  /**
   * Test serialization/deserialization using IDS.writeSignedVL for GemFireXD.
   * Was disabled due to bug #41196 and now is also a testcase for that bug.
   */
  public void test41179_2() throws Exception {
    // start a couple of VMs to use as datastore and this VM as accessor
    startVMs(1, 2);

    // create table for insert/query
    clientSQLExecute(1,
        "create table TestLong (id bigint primary key, tid int)");
    serverSQLExecute(1, "create table TestInt (id int primary key, tid bigint)");

    // test all combos of valueToTest and + and -offsets
    long[] longValuesToTest = new long[] { 0, Byte.MAX_VALUE, Byte.MIN_VALUE,
        Short.MAX_VALUE, Short.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
        Long.MAX_VALUE, Long.MIN_VALUE };
    int[] intValuesToTest = new int[] { 0, Byte.MAX_VALUE, Byte.MIN_VALUE,
        Short.MAX_VALUE, Short.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE };
    int[] offsets = new int[] { 0, 1, 4, 9, 14, 15, 16, -1, -4, -9, -14, -15,
        -16 };

    // do inserts of all combos for longs
    for (long longValueToTest : longValuesToTest) {
      for (int offset : offsets) {
        if (longValueToTest == Long.MAX_VALUE && offset > 0) {
          continue;
        }
        if (longValueToTest == Long.MIN_VALUE && offset < 0) {
          continue;
        }
        long val = longValueToTest + offset;
        int intVal = (int)val;
        clientSQLExecute(1, "insert into TestLong values (" + val + ", "
            + intVal + ")");
      }
    }

    // now do queries and check the return values
    PreparedStatement pstmt = TestUtil.jdbcConn
        .prepareStatement("select tid from TestLong where id=?");
    for (long longValueToTest : longValuesToTest) {
      for (int offset : offsets) {
        if (longValueToTest == Long.MAX_VALUE && offset > 0) {
          continue;
        }
        if (longValueToTest == Long.MIN_VALUE && offset < 0) {
          continue;
        }
        long val = longValueToTest + offset;
        int expectedVal = (int)val;
        sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
            "select tid from TestLong where id=" + val, null, String
                .valueOf(expectedVal));
        // also check using prepared statement
        if (val <= Integer.MAX_VALUE && val >= Integer.MIN_VALUE) {
          pstmt.setInt(1, (int)val);
        }
        else {
          pstmt.setLong(1, val);
        }
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expectedVal, rs.getInt(1));
        assertFalse(rs.next());
      }
    }

    // do inserts of all combos for ints
    for (int intValueToTest : intValuesToTest) {
      for (int offset : offsets) {
        if (intValueToTest == Integer.MAX_VALUE && offset > 0) {
          continue;
        }
        if (intValueToTest == Integer.MIN_VALUE && offset < 0) {
          continue;
        }
        int val = intValueToTest + offset;
        long longVal = val;
        clientSQLExecute(1, "insert into TestInt values (" + val + ", "
            + longVal + ")");
      }
    }

    // now do queries and check the return values
    pstmt = TestUtil.jdbcConn
        .prepareStatement("select tid from TestInt where id=?");
    for (int intValueToTest : intValuesToTest) {
      for (int offset : offsets) {
        if (intValueToTest == Integer.MAX_VALUE && offset > 0) {
          continue;
        }
        if (intValueToTest == Integer.MIN_VALUE && offset < 0) {
          continue;
        }
        int val = intValueToTest + offset;
        long expectedVal = val;
        sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
            "select tid from TestInt where id=" + val, null, String
                .valueOf(expectedVal));
        // also check using prepared statement
        pstmt.setInt(1, val);
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expectedVal, rs.getInt(1));
        assertFalse(rs.next());
      }
    }
  }

  public void testBug41262() throws Exception {
     //  start a couple of VMs to use as datastore and this VM as accessor
    startVMs(1, 3);

    final boolean [] failed = new boolean[]{false};
    final Statement stmt = TestUtil.jdbcConn.createStatement();
    try {
      stmt.execute("create table TestInt (id int primary key, tid int)");
      stmt.execute("insert into TestInt values(1,1)");
      stmt.execute("update table TestInt set tid =2");
      stmt.execute( "create table TestLong (id bigint primary key, tid int, id int)");
      fail("Table creation should have failed");
    }catch(Exception expected) {
    }
    Thread th = new Thread( new  Runnable() {
      public void run() {
         try {
          ResultSet rs = stmt.executeQuery("select  * from TestInt");
          assertTrue(rs.next());
          assertFalse(rs.next());
        }
        catch (SQLException e) {
          e.printStackTrace();
          failed[0] = true;
        }
      }
    });
    th.start();
    th.join();
    assertFalse(failed[0]);
  }

  /**
   * Test for member going down during ResultSet iteration (bug #41471).
   *
   * Also added test for bug #41661 that will verify that client does not hang
   * if {@link Function#execute} throws a runtime exception.
   */
  public void testRSNextHA_41471_41661() throws Exception {
    // start some servers and use this VM as accessor
    startVMs(1, 4);

    // Create tables and do some inserts and updates
    createTablesAndPopulateData(2);

    // servers being brought down can cause shutdown exceptions in errors.grep
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        new Object[] { CacheClosedException.class, "No current connection",
            "Failed to start database 'gemfirexd'" });

    // attach the observer that will bring down the VM during ResultHolder
    // iteration on one of the VMs
    serverExecute(3, new SerializableRunnable("attach observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new ResultHolderIterationObserver());
      }
    });

    // now fire a query and expect failure with streaming on
    final Properties props = new Properties();
    System.clearProperty(GfxdConstants.GFXD_DISABLE_STREAMING);
    props.setProperty(Attribute.DISABLE_STREAMING, "false");
    Connection conn = TestUtil.getConnection(props);
    PreparedStatement prepStmt = conn
        .prepareStatement("select * from trade.customers");
    int numResults = 0;
    int numIters = 0;
    for (;;) {
      getLogWriter().info("Into iteration " + (++numIters));
      final ResultSet rs = prepStmt.executeQuery();
      numResults = 0;
      try {
        while (rs.next()) {
          getLogWriter().info(
              "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
          ++numResults;
        }
        if (numIters == 1) {
          fail("expected an SQLException due to node going down during "
              + "first ResultSet iteration");
        }
        break;
      } catch (SQLException ex) {
        if ("X0Z01".equals(ex.getSQLState())) {
          getLogWriter().info("Got expected exception", ex);
        }
        else {
          throw ex;
        }
      }
      // sleep a bit before retry
      Thread.sleep(500);
    }
    assertEquals(100, numResults);

    // Attach the observer to the second server that will bring down the VM
    // during ResultHolder iteration on one of the VMs. This time streaming
    // is disabled so we don't expect an exception rather transparent retries.
    serverExecute(2, new SerializableRunnable("attach observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new ResultHolderIterationObserver());
      }
    });

    // now fire a query and expect no failure with streaming off
    props.setProperty(Attribute.DISABLE_STREAMING, "true");
    conn = TestUtil.getConnection(props);
    prepStmt = conn.prepareStatement("select * from trade.customers");
    final ResultSet rs = prepStmt.executeQuery();
    numResults = 0;
    while (rs.next()) {
      getLogWriter().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
      ++numResults;
    }
    assertEquals(100, numResults);

    // now bring down all the servers and check that query execution on
    // non-existent buckets should return zero results and should not hang
    // (see bug #41661)

    // first execute the query with servers available
    assertFalse(conn.createStatement().executeQuery(
        "select * from trade.customers where cid=1000 and tid>100").next());
//    // now bring down the remaining servers and try again
//    stopVMNums(-1, -4);
//    // get a normal connection with streaming on
//    props.setProperty(Attribute.DISABLE_STREAMING, "false");
//    conn = TestUtil.getConnection(props);
//    clientSQLExecute(1, "create synonym synForTradeCustomers for trade.customers");
//    try {
//      ResultSet rs1 = conn.createStatement().executeQuery(
//          "select * from synForTradeCustomers where cid=1000 and tid>100");
//      rs1.next();
//      fail("Test should have failed due to lack of data stores");
//    } catch (SQLException sqle) {
//      assertEquals(sqle.getSQLState(), "X0Z08");
//    }
//    clientSQLExecute(1, "drop table trade.portfolio");
//    clientSQLExecute(1, "drop table trade.customers");

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        new Object[] { CacheClosedException.class, "No current connection",
            "Failed to start database 'gemfirexd'" });
  }

  /**
   * Test whether SQL date, time and timestamp are using local timezone using
   * network client and peer client. Also added parameter conversion for
   * BigDecimal.
   */
  public void testDateTimeTypes_and_42478_DEFAULT() throws Exception {
    // start a server with a network server
    startVMs(1, 1);
    final int netPort = startNetworkServer(1, null, null);

    // use this VM as network client and also peer client
    final Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    final Statement stmt = netConn.createStatement();

    // create table with SQL date, time and timestamp fields
    clientSQLExecute(1, "create table s.timetest(id int primary key, "
        + "ts timestamp not null, t time not null, dt date not null,"
        + " price decimal(30,20), qty int)");
    // another table to check for DEFAULT value inserts
    clientSQLExecute(1, "create table s.defaulttest(id int primary key, "
        + " price decimal(30,20) default null, qty int default 0, "
        + "name varchar(10) default 'none')");
    String insertStr = "insert into s.defaulttest (id, price, qty, name) "
        + "values(?, ?, DEFAULT, DEFAULT)";
    PreparedStatement insertStmt = netConn.prepareStatement(insertStr);
    for (int id = 1; id < 5; id++) {
      insertStmt.setInt(1, id);
      insertStmt.setBigDecimal(2, new BigDecimal("20." + id));
      assertEquals(1, insertStmt.executeUpdate());
    }
    for (int id = 5; id < 10; id++) {
      assertEquals(1, stmt.executeUpdate("insert into s.defaulttest "
          + "(id, price, qty, name) values(" + id + ", "
          + new BigDecimal("20." + id) + ", DEFAULT, DEFAULT)"));
    }
    insertStmt = netConn.prepareStatement("insert into s.defaulttest "
        + "values(?, DEFAULT, DEFAULT, ?)");
    for (int id = 10; id < 15; id++) {
      insertStmt.setInt(1, id);
      insertStmt.setString(2, "name_" + id);
      assertEquals(1, insertStmt.executeUpdate());
    }
    for (int id = 15; id < 20; id++) {
      assertEquals(1, stmt.executeUpdate("insert into s.defaulttest "
          + "values(" + id + ", DEFAULT, DEFAULT, 'name_" + id + "')"));
    }
    ResultSet rs = stmt.executeQuery("select * from s.defaulttest order by id");
    for (int id = 1; id < 20; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
      if (id < 10) {
        assertEquals(new BigDecimal("20." + id).setScale(20),
            rs.getBigDecimal(2));
        assertEquals("none", rs.getString(4));
      }
      else {
        assertNull(rs.getBigDecimal(2));
        assertNull(rs.getString(2));
        assertEquals("name_" + id, rs.getString(4));
      }
      assertEquals(0, rs.getInt(3));
    }
    assertFalse(rs.next());

    clientSQLExecute(1, "create synonym synForsTimetest for s.timetest");
    // try inserts using the client driver and then with embedded driver with
    // different time and date fields for PreparedStatements and verify
    insertStr = "insert into synForsTimetest values(?, ?, ?, ?, ?, ?)";
    final String updateStr = "update synForsTimetest set price = ? * qty "
        + "where id=?";
    final String selectStr = "select * from synForsTimetest";
    final String select2Str = "select price from synForsTimetest where id=?";
    insertStmt = netConn.prepareStatement(insertStr);
    PreparedStatement updateStmt = netConn.prepareStatement(updateStr);
    PreparedStatement selectStmt = TestUtil.jdbcConn
        .prepareStatement(selectStr);
    PreparedStatement select2Stmt = TestUtil.jdbcConn
        .prepareStatement(select2Str);
    for (int cnt = 1; cnt <= 2; ++cnt) {
      // do the insert
      insertStmt.setInt(1, 1);
      insertStmt.setTimestamp(2, java.sql.Timestamp
          .valueOf("2005-01-01 01:01:01"));
      insertStmt.setTime(3, java.sql.Time.valueOf("12:12:12"));
      insertStmt.setDate(4, java.sql.Date.valueOf("2005-01-01"));
      insertStmt.setBigDecimal(5, new BigDecimal("20.22"));
      insertStmt.setInt(6, 20);
      insertStmt.execute();
      // another insert with current time
      final long currentTime = System.currentTimeMillis();
      insertStmt.setInt(1, 2);
      insertStmt.setTimestamp(2, new java.sql.Timestamp(currentTime));
      insertStmt.setTime(3, new java.sql.Time(currentTime));
      insertStmt.setDate(4, new java.sql.Date(currentTime));
      insertStmt.setBigDecimal(5, new BigDecimal("30.33"));
      insertStmt.setInt(6, 30);
      insertStmt.execute();
      insertStmt.setInt(1, 3);
      final Calendar cal = Calendar.getInstance();
      cal.clear();
      cal.set(2005, 0, 1, 14, 14, 14);
      final long utcMillis = cal.getTimeInMillis();
      insertStmt.setTimestamp(2, new java.sql.Timestamp(utcMillis), cal);
      insertStmt.setTime(3, new java.sql.Time(utcMillis), cal);
      insertStmt.setDate(4, new java.sql.Date(utcMillis), cal);
      insertStmt.setBigDecimal(5, new BigDecimal("40.44"));
      insertStmt.setInt(6, 40);
      insertStmt.execute();
      // now select and verify
      rs = selectStmt.executeQuery();
      for (int index = 1; index <= 3; ++index) {
        assertTrue(rs.next());
        final int id = rs.getInt(1);
        if (id == 1) {
          assertEquals("2005-01-01 01:01:01.0", rs.getTimestamp("TS")
              .toString());
          assertEquals("12:12:12", rs.getTime(3).toString());
          assertEquals("2005-01-01", rs.getDate(4).toString());
          // also check on server
          cal.clear();
          cal.setTimeInMillis(java.sql.Timestamp.valueOf(
              "2005-01-01 01:01:01").getTime());
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 1, 2, cal.get(Calendar.YEAR),
                  cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE),
                  cal.get(Calendar.HOUR_OF_DAY),
                  cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
                  0 });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 1, 3, -1, -1, -1, 12, 12, 12, 0 });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 1, 4, 2005, 1, 1, -1, -1, -1, -1 });
        }
        else if (id == 2) {
          assertEquals(new java.sql.Timestamp(currentTime), rs.getTimestamp(2));
          assertEquals(new java.sql.Time(currentTime).toString(), rs.getTime(
              "T").toString());
          assertEquals(new java.sql.Date(currentTime).toString(), rs.getDate(
              "DT").toString());
          // also check on server
          cal.clear();
          cal.setTimeInMillis(currentTime);
          cal.clear();
          cal.setTimeInMillis(currentTime);
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 2, 2, cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND) });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 2, 3, -1, -1, -1,
                  cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
                  cal.get(Calendar.SECOND), 0 });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 2, 4, cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE),
            -1, -1, -1, -1 });
        }
        else if (id == 3) {
          assertEquals(new java.sql.Timestamp(utcMillis), rs.getTimestamp(2));
          assertEquals("14:14:14", rs.getTime(3).toString());
          assertEquals("2005-01-01", rs.getDate(4).toString());
          // also check on server
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 3, 2, 2005, 1, 1, 14, 14, 14, 0 });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 3, 3, -1, -1, -1, 14, 14, 14, 0 });
          this.serverVMs.get(0).invoke(getClass(), "checkDateTimeFields",
              new Object[] { "s.timetest", 3, 4, 2005, 1, 1, -1, -1, -1, -1 });
        }
        else {
          fail("unexpected id " + id);
        }
      }
      assertFalse(rs.next());
      // update decimal field and check
      updateStmt.setBigDecimal(1, new BigDecimal("1.17"));
      updateStmt.setInt(2, 2);
      updateStmt.execute();
      // verify
      select2Stmt.setInt(1, 2);
      rs = select2Stmt.executeQuery();
      assertTrue(rs.next());
      // TODO: uncomment below when #42013 is fixed
      //assertEquals(new BigDecimal("35.10000000000000000000"),
      //    rs.getBigDecimal(1));
      assertEquals(new BigDecimal("30.00000000000000000000"),
          rs.getBigDecimal(1));
      assertFalse(rs.next());

      // delete all data
      netConn.createStatement().execute("delete from synForsTimetest");
      // now with the embedded driver
      insertStmt = TestUtil.jdbcConn.prepareStatement(insertStr);
      updateStmt = TestUtil.jdbcConn.prepareStatement(updateStr);
      selectStmt = netConn.prepareStatement(selectStr);
      select2Stmt = netConn.prepareStatement(select2Str);
    }
  }

  public void testConnectionSevereExceptionPropagation() throws Exception {
    // start a server with a network server
    startVMs(0, 2);

    SerializableRunnable r = new SerializableRunnable() {
      @Override
      public void run() {

        try {
          Connection conn = TestUtil.getConnection();

          Statement st = conn.createStatement();

          ResultSet rs = conn.getMetaData().getTables((String)null, null,
              "course".toUpperCase(), new String[] { "TABLE" });
          final boolean found = rs.next();
          rs.close();

          if (found) {
            st.execute("drop table course ");
          }

          st.execute("create table course ("
              + "course_id int, i int, j int, course_name varchar(2048), "
              + " primary key(course_id)" + ") partition by column (i) ");

          String sql = "insert into course values( ";
          StringBuilder ins = new StringBuilder(sql);
          for (int i = 0; i < 100; i++) {
            ins.append(i).append(",");
            ins.append(i + PartitionedRegion.rand.nextInt(1000)).append(",");
            ins.append(1).append(", '");
            ins.append(TestUtil.numstr(i)).append("' )");
            if (i % 2 == 0) {
              st.execute(ins.toString());
              ins = new StringBuilder(sql);
            }
            else {
              ins.append(", ( ");
            }
          }

          try {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter() {
                  private boolean raiseException = false;

                  @Override
                  public void beforeQueryExecution(EmbedStatement stmt,
                      Activation activation) throws SQLException {
                    raiseException = true;
                  }

                  @Override
                  public void scanControllerOpened(Object sc,
                      Conglomerate conglom) {

                    if (!raiseException) {
                      return;
                    }

                    if (!(sc instanceof MemScanController)
                        || !(conglom instanceof MemConglomerate)) {
                      return;
                    }

                    MemConglomerate c = (MemConglomerate)conglom;

                    if (c.getGemFireContainer().getTableName()
                        .equalsIgnoreCase("course")) {
                      getLogWriter()
                          .info(
                              "Raising fake ClassCastException connection severe exception");
                      throw new ClassCastException(
                          "Expected this exception to propagate out to query node");
                    }
                  }
                });

            ResultSet results = st.executeQuery("select * from course");
            while (results.next())
              ;
            fail("must have received an exception");
          } catch (SQLException expected) {
            if (!"XJ001".equals(expected.getSQLState())
                || !expected.getMessage().contains("ClassCastException")) {
              throw new GemFireXDRuntimeException(expected);
            }
          } finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }

        } catch (SQLException unexpected) {
          throw new GemFireXDRuntimeException(unexpected);
        }
      }
    };

    // run remotely on server.
    serverExecute(1, r);
  }

  public void testCorrelatedSubQueryOnPR_PR_UNSUPPORTED() throws Exception {
    startVMs(1, 4);
    int clientPort = startNetworkServer(1, null, null);
    final int derbyPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    Connection conn = TestUtil.getNetConnection(clientPort, null, null);
    Connection conn2 = TestUtil.jdbcConn;
    String derbyDbUrl = "jdbc:derby://localhost:" + derbyPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    final String derbyUrl = derbyDbUrl;

    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    final NetworkServerControl server = DBSynchronizerTestBase
        .startNetworkServer(derbyPort);
    Connection derbyConn = DriverManager.getConnection(derbyUrl);

    Statement stmt = conn.createStatement();
    final Statement stmt2 = conn2.createStatement();
    Statement derbyStmt = derbyConn.createStatement();

    stmt.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, "
        + "ADDRESS1 clob not null, DATA1 blob not null, primary key (ID1)) "
        + "PARTITION BY COLUMN ( ID1 )");
    stmt.execute("create table TESTTABLE2 (ID2 int not null,"
        + " DESCRIPTION2 varchar(1024) not null,"
        + " ADDRESS2 clob not null, primary key (ID2))");
    derbyStmt.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, "
        + "ADDRESS1 clob not null, DATA1 blob not null, primary key (ID1))");
    derbyStmt.execute("create table TESTTABLE2 (ID2 int not null,"
        + " DESCRIPTION2 varchar(1024) not null,"
        + " ADDRESS2 clob not null, primary key (ID2))");

    String query = "select ID1, DESCRIPTION1, ADDRESS1, DATA1"
        + " from TESTTABLE1 where ID1 IN ( Select AVG(ID2) from Testtable2"
        + "   where description2 > description1)";

    stmt.execute("insert into TESTTABLE1 values (1, 'ONE', 'ADDR1', "
        + "X'1234567890ABCDEF')");
    stmt.execute("insert into TESTTABLE1 values (2, 'TWO', 'ADDR2', "
        + "X'234567890ABCDEF1')");
    stmt.execute("insert into TESTTABLE1 values (3, 'THREE', 'ADDR3', "
        + "cast (X'34567890ABCDEF12' as blob))");
    stmt.execute("insert into TESTTABLE2 values (1, 'ZONE', 'ADDR1')");
    stmt.execute("insert into TESTTABLE2 values (2, 'ATWO', 'ADDR2')");
    stmt.execute("insert into TESTTABLE2 values (3, 'ATHREE', 'ADDR3')");
    conn.commit();

    try {
      try {
        stmt.execute(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }
      try {
        stmt2.execute(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }

      // also check for prepared statements
      try {
        conn.prepareStatement(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }

      // also check for prepared statements
      try {
        conn2.prepareStatement(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }

      // use the observer to execute the query against Derby and get the results
      final GemFireXDQueryObserver delegate = new GemFireXDQueryObserverAdapter() {
        private final ConcurrentHashMap<Connection, Connection> dbConnMap =
            new ConcurrentHashMap<Connection, Connection>();

        @Override
        public PreparedStatement afterQueryPrepareFailure(Connection conn,
            String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability, int autoGeneratedKeys,
            int[] columnIndexes, String[] columnNames, SQLException sqle)
            throws SQLException {
          if (sqle != null && "0A000".equals(sqle.getSQLState())) {
            return getDBConnection(conn).prepareStatement(sql);
          }
          return null;
        }

        @Override
        public boolean afterQueryExecution(CallbackStatement stmt,
            SQLException sqle) throws SQLException {
          if (sqle != null && "0A000".equals(sqle.getSQLState())) {
            stmt.setResultSet(getDBConnection(stmt.getConnection())
                .createStatement().executeQuery(stmt.getSQLText()));
            return true;
          }
          return false;
        }

        @Override
        public void afterCommit(Connection conn) {
          final Connection dbConn = this.dbConnMap.get(conn);
          if (dbConn != null) {
            try {
              dbConn.commit();
            } catch (SQLException sqle) {
              // ignore exceptions at this point
              Logger.getLogger("com.pivotal.gemfirexd").warning(
                  "Ignored exception in commit: " + sqle);
            }
          }
        }

        @Override
        public void afterRollback(Connection conn) {
          final Connection dbConn = this.dbConnMap.get(conn);
          if (dbConn != null) {
            try {
              dbConn.rollback();
            } catch (SQLException sqle) {
              // ignore exceptions at this point
              Logger.getLogger("com.pivotal.gemfirexd").warning(
                  "Ignored exception in rollback: " + sqle);
            }
          }
        }

        @Override
        public void afterConnectionClose(Connection conn) {
          final Connection dbConn = this.dbConnMap.get(conn);
          if (dbConn != null) {
            try {
              dbConn.close();
            } catch (SQLException sqle) {
              // ignore exceptions at this point
              Logger.getLogger("com.pivotal.gemfirexd").warning(
                  "Ignored exception in close: " + sqle);
            }
          }
        }

        private Connection getDBConnection(final Connection conn)
            throws SQLException {
          Connection dbConn = this.dbConnMap.get(conn);
          if (dbConn != null) {
            return dbConn;
          }
          dbConn = DriverManager.getConnection(derbyUrl);
          dbConn.setAutoCommit(conn.getAutoCommit());
          int isolationLevel = conn.getTransactionIsolation();
          if (dbConn.getMetaData().supportsTransactionIsolationLevel(
              isolationLevel)) {
            dbConn.setTransactionIsolation(isolationLevel);
          }
          Connection oldConn = dbConnMap.putIfAbsent(conn, dbConn);
          return oldConn == null ? dbConn : oldConn;
        }
      };

      getServerVM(1).invoke(new SerializableRunnable() {
        @Override
        public void run() {
          GemFireXDQueryObserverHolder.setInstance(delegate);
        }
      });
      GemFireXDQueryObserverHolder.setInstance(delegate);

      derbyStmt.execute("insert into TESTTABLE1 values (1, 'ONE', 'ADDR1', "
          + "cast (X'1234567890ABCDEF' as blob))");
      derbyStmt.execute("insert into TESTTABLE1 values (2, 'TWO', 'ADDR2', "
          + "cast (X'234567890ABCDEF1' as blob))");
      derbyStmt.execute("insert into TESTTABLE1 values (3, 'THREE', 'ADDR3', "
          + "cast (X'34567890ABCDEF12' as blob))");
      derbyStmt.execute("insert into TESTTABLE2 values (1, 'ZONE', 'ADDR1')");
      derbyStmt.execute("insert into TESTTABLE2 values (2, 'ATWO', 'ADDR2')");
      derbyStmt.execute("insert into TESTTABLE2 values (3, 'ATHREE', 'ADDR3')");
      derbyConn.commit();

      ResultSet rs = stmt.executeQuery(query);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      Clob clob = rs.getClob(3);
      StringBuilder sb = new StringBuilder();
      Reader rdr = clob.getCharacterStream();
      int ch;
      while ((ch = rdr.read()) != -1) {
        sb.append((char)ch);
      }
      assertEquals("ADDR1", sb.toString());
      assertFalse(rs.next());
      rs.close();

      rs = stmt2.executeQuery(query);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      clob = rs.getClob(3);
      sb = new StringBuilder();
      rdr = clob.getCharacterStream();
      while ((ch = rdr.read()) != -1) {
        sb.append((char)ch);
      }
      assertEquals("ADDR1", sb.toString());
      assertFalse(rs.next());
      rs.close();

      PreparedStatement ps = conn.prepareStatement(query);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      clob = rs.getClob(3);
      sb = new StringBuilder();
      rdr = clob.getCharacterStream();
      while ((ch = rdr.read()) != -1) {
        sb.append((char)ch);
      }
      assertEquals("ADDR1", sb.toString());
      assertFalse(rs.next());
      rs.close();
      ps.close();

      ps = conn2.prepareStatement(query);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      clob = rs.getClob(3);
      sb = new StringBuilder();
      rdr = clob.getCharacterStream();
      while ((ch = rdr.read()) != -1) {
        sb.append((char)ch);
      }
      assertEquals("ADDR1", sb.toString());
      assertFalse(rs.next());
      rs.close();
      ps.close();
    } finally {
      if (!conn.isClosed()) {
        conn.commit();
      }
      if (!conn2.isClosed()) {
        conn2.commit();
      }
      if (!derbyConn.isClosed()) {
        derbyConn.commit();
      }

      derbyStmt.execute("drop table TESTTABLE1");
      derbyStmt.execute("drop table TESTTABLE2");
      server.shutdown();
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
      getServerVM(1).invoke(new SerializableRunnable() {
        @Override
        public void run() {
          GemFireXDQueryObserverHolder.clearInstance();
        }
      });
    }
  }

 //Disabling due to bug : Contact Neeraj
 public void _testOrList_45041() throws Exception {
    // start 3 servers including one in this VM
    Properties props = new Properties();
    props.setProperty("host-data", "true");
    startVMs(1, 2, 0, null, props);

    final Connection conn = TestUtil.jdbcConn;
    final Statement stmt = conn.createStatement();


    stmt.execute("create table test1 (id int primary key, "
        + "name varchar(100) not null)");
    stmt.execute("create index idx1 on test1(name)");

    for (int id = 1; id < 100; id++) {
      stmt.execute("insert into test1 values (" + id + ", 'name" + id + "')");
    }

    // OR queries which should use a MultiColumnTableScanRS
    ResultSet rs;
    int i;

    // Set the observer to check that proper scans are being opened
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    rs = stmt.executeQuery("select * from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals(i, rs.getInt(1));
      assertEquals("name" + i, rs.getString(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select id from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals(i, rs.getInt(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    // with overlapping ranges
    Object[][] expectedRows = new Object[][] {
        new Object[] { 7, "name7" },
        new Object[] { 8, "name8" },
        new Object[] { 9, "name9" },
        new Object[] { 61, "name61" },
        new Object[] { 62, "name62" },
        new Object[] { 63, "name63" },
        new Object[] { 64, "name64" },
        new Object[] { 65, "name65" },
        new Object[] { 66, "name66" },
        new Object[] { 67, "name67" },
        new Object[] { 68, "name68" },
        new Object[] { 69, "name69" },
        new Object[] { 70, "name70" },
        new Object[] { 71, "name71" },
        new Object[] { 72, "name72" },
        new Object[] { 73, "name73" },
        new Object[] { 74, "name74" },
        new Object[] { 75, "name75" },
        new Object[] { 76, "name76" },
        new Object[] { 77, "name77" },
        new Object[] { 78, "name78" },
        new Object[] { 79, "name79" },
        new Object[] { 80, "name80" },
        new Object[] { 81, "name81" },
        new Object[] { 82, "name82" },
        new Object[] { 83, "name83" },
        new Object[] { 84, "name84" },
        new Object[] { 85, "name85" },
        new Object[] { 86, "name86" },
        new Object[] { 87, "name87" },
        new Object[] { 88, "name88" },
        new Object[] { 89, "name89" },
    };

    rs = stmt.executeQuery("select id from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
                        "order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals(i, rs.getInt(1));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
                        "order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
                        "order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select id, name from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90')");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select * from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90')");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    // and multiple columns
    rs = stmt.executeQuery("select name from test1 where (name < 'name80'"
        + " and id > 60) or (name > 'name70' and id < 90) " +
                        "order by id");
    i = 8;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(90, i);

    // TODO: PERF: currently above query will use table scan instead of index
    // scan due to inablity to pass table level qualifiers to index scans

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where (name < 'name80'"
        + " and id > 60) or (id > 70 and name < 'name90') " +
                        "order by id");
    i = 61;
    while (rs.next()) {
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.TABLE);
    observer.checkAndClear();
  }

  public void testUseCase1CaseInsensitiveSearch() throws Exception {
    reduceLogLevelForTest("config");
    /*
    SerializableRunnable optimTrace = new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfirexd.optimizer.trace", "true");
        GemFireXDUtils.setOptimizerTrace(true);
      }
    };
    invokeInEveryVM(optimTrace);
    optimTrace.run();
    */
    // start 3 servers and a client
    startVMs(1, 3);

    // with peer client connection there will be no scans
    QueryChecksTest.checkCaseInsensitiveSearch(TestUtil.jdbcConn, false, true);
    // check for queries and scan types from a data store
    serverExecute(1, new SerializableRunnable() {
      @Override
      public void run() {
        try {
          QueryChecksTest.checkCaseInsensitiveSearch(TestUtil.jdbcConn, true,
              false);
        } catch (Exception e) {
          fail("failure in running queries", e);
        }
      }
    });

    // now restart the servers and check again
    stopVMNums(-1, -2, -3);
    restartVMNums(-1, -2, -3);

    // check for queries and scan types from a data store
    SerializableRunnable checkData = new SerializableRunnable() {
      @Override
      public void run() {
        try {
          QueryChecksTest.caseInsensitiveQueries(TestUtil.jdbcConn, true);
        } catch (Exception e) {
          fail("failure in running queries", e);
        }
      }
    };
    serverExecute(1, checkData);
    serverExecute(2, checkData);
    serverExecute(3, checkData);
  }

  public static void checkDateTimeFields(String tableName, int id,
      int columnIndex, int year, int month, int date, int hour, int minute,
      int second, int millisecond) throws Exception {
    final Connection conn = TestUtil.jdbcConn;
    EmbedResultSet ers = (EmbedResultSet)conn.createStatement().executeQuery(
        "select * from " + tableName);
    boolean foundId = false;
    NullHolder wasNull = new NullHolder();
    while (!foundId && ers.next()) {
      final ExecRow row = ers.getCurrentRow();
      assertTrue("Expected CompactExecRow but got " + row.getClass().getName(),
          row instanceof AbstractCompactExecRow);
      final AbstractCompactExecRow compactRow = (AbstractCompactExecRow)row;
      if (compactRow.getAsInt(1, wasNull) == id) {
        assertFalse(wasNull.wasNull());
        if (year >= 0) {
          if (hour >= 0) {
            // both date and time is set so expect SQL TIMESTAMP
            int[] ints = compactRow.getRowFormatter().readAsIntArray(
                columnIndex, compactRow, 3);
            // compare encoded dates
            assertEquals(SQLDate.computeEncodedDate(year, month, date), ints[0]);
            // compare encoded times
            assertEquals(SQLTime.computeEncodedTime(hour, minute, second),
                ints[1]);
            // nanos part is calculated from milliseconds
            assertEquals(millisecond * 1000000, ints[2]);
          }
          else {
            // only date set so expect SQL DATE
            int[] ints = compactRow.getRowFormatter().readAsIntArray(
                columnIndex, compactRow, 1);
            // compare encoded dates
            assertEquals(SQLDate.computeEncodedDate(year, month, date), ints[0]);
          }
        }
        else {
          // only time expected to be set so SQL TIME
          int[] ints = compactRow.getRowFormatter().readAsIntArray(columnIndex,
              compactRow, 2);
          // compare encoded times
          assertEquals(SQLTime.computeEncodedTime(hour, minute, second),
              ints[0]);
          // currently fractional secs part is always zero in SQLTime
          assertEquals(0, ints[1]);
        }
        foundId = true;
      }
    }
    assertTrue("failed to find ID: " + id, foundId);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static String createRegionForTest(Boolean accessor) {
    Instantiator.register(new Instantiator(SerializableCompactLong.class, 79) {
      @Override
      public DataSerializable newInstance() {
        return new SerializableCompactLong();
      }
    });

    AttributesFactory attr = new AttributesFactory();
    attr.setDataPolicy(DataPolicy.PARTITION);
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    paf.setRedundantCopies(1);
    if (accessor.booleanValue()) {
      paf.setLocalMaxMemory(0);
    }
    PartitionAttributes prAttr = paf.create();
    attr.setPartitionAttributes(prAttr);
    Cache cache = CacheFactory.getAnyInstance();
    Region reg = cache.createRegion("TESTLONG", attr.create());
    return reg.getFullPath();
  }

  public static class SerializableCompactLong implements DataSerializable {

    private long val;

    public SerializableCompactLong() {
    }

    public SerializableCompactLong(long val) {
      this.val = val;
    }

    public void toData(DataOutput out) throws IOException {
      InternalDataSerializer.writeSignedVL(this.val, out);
    }

    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      this.val = InternalDataSerializer.readSignedVL(in);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof SerializableCompactLong) {
        return this.val == ((SerializableCompactLong)obj).val;
      }
      return false;
    }

    @Override
    public int hashCode() {
      return (int)(this.val ^ (this.val >>> 32));
    }

    @Override
    public String toString() {
      return String.valueOf(this.val);
    }
  }

  private void createTablesAndPopulateData(int redundancy) throws Exception {
    String redundancyStr = "";
    if (redundancy > 0) {
      redundancyStr = " redundancy " + redundancy;
    }
    clientSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid))" + redundancyStr);
    for (int i = 0; i < 100; ++i) {
      String insertStmt = "insert into trade.customers values (" + i + ", '"
          + ("CUST" + i) + "', '" + ("ADDR" + i) + "', " + (i + 1) + ")";
      if (i % 2 == 0) {
        clientSQLExecute(1, insertStmt);
      }
      else {
        serverSQLExecute(((i % 4) + 1) / 2, insertStmt);
      }
    }

    // add fk table to test locking with GemFireDeleteResultSet having
    // referenced key checking
    serverSQLExecute(2, "create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid), constraint cust_fk "
        + "foreign key (cid) references trade.customers (cid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ch check (availQty >= 0 and availQty <= qty))"
        + redundancyStr);
    for (int i = 5; i < 50; ++i) {
      String insertStmt = "insert into trade.portfolio values (" + i + ", "
          + (i + 2) + ", " + (i * 10) + ", " + (i * 9) + ", " + (i + 1) + ")";
      if (i % 2 == 0) {
        clientSQLExecute(1, insertStmt);
      }
      else {
        serverSQLExecute(((i % 4) + 1) / 2, insertStmt);
      }
    }
  }

  private static class ResultHolderIterationObserver extends
      GemFireXDQueryObserverAdapter {

    private int numIterations;

    private Thread stopperThread;

    @Override
    public void afterResultHolderIteration(GfxdConnectionWrapper wrapper,
        EmbedStatement es) {
      try {
        if (++this.numIterations == 5) {
          // delay things a bit to force this VMs results be last in queue
          Thread.sleep(2000);
          // spawn off another thread that will bring this VM down
          this.stopperThread = new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                TestUtil.shutDown();
                getGlobalLogger().info("completed shutdown");
              } catch (SQLException ex) {
                throw new TestException("failed in shutdown", ex);
              }
            }
          });
          this.stopperThread.start();
          throw new CacheClosedException("forcing cache closed exception");
        }
      } catch (InterruptedException ie) {
        throw new CacheClosedException("got thread interrupt", ie);
      }
    }

    @Override
    public void afterResultHolderExecution(GfxdConnectionWrapper wrapper,
        EmbedStatement es, String query) {
      this.numIterations = 0;
    }
  }
}
