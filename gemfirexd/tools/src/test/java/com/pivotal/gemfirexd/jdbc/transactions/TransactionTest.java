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
package com.pivotal.gemfirexd.jdbc.transactions;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.sql.Connection;

import java.sql.ResultSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gnu.trove.TIntHashSet;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeapScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.jdbc.CreateTableTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.transactions.TransactionDUnit;

/**
 * Test class for GemFireXD transactions.
 * 
 * @author rdubey
 */
public class TransactionTest extends JdbcTestBase {

  private GemFireCacheImpl cache;

  private boolean gotConflict = false;

  private volatile Throwable threadEx;

  public TransactionTest(String name) {
    super(name);
  }

  // Kept here for debugging
  public void _testDebugTwoDeltas() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate"+getSuffix());
    conn.commit();
    conn = getConnection();

    conn.setTransactionIsolation(getIsolationLevel());
    st = conn.createStatement();
    st.execute("insert into t1 values (10, 10)");

    conn.commit();// rollback.

    st.execute("update t1 set c2 = 20 where c1 = 10");
    st.execute("update t1 set c2 = 30 where c1 = 10");

    conn.commit(); // commit two rows.
    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  /**
   * Test commit and rollback.
   * @throws Exception on failure.
   */
  public void testCommitOnReplicatedTable() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate"+getSuffix());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);

    st = conn.createStatement();
    st.execute("insert into t1 values (10, 10)");

    conn.rollback();// rollback.

    ResultSet rs = st.executeQuery("Select * from t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    rs = st.executeQuery("Select * from t1");

    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    rs = st.executeQuery("Select * from t1 where c1=10");
    numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    // withing tx also the row count should be 2
    assertEquals("ResultSet should contain two rows ", 1, numRows);

    conn.commit(); // commit two rows.
    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    conn.commit();
    st.execute("delete from t1 where c1=10");
    conn.commit();
    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 1, numRows);


    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  /**
   * Test commit and rollback.
   * @throws Exception on failure.
   */
  public void testCommitOnReplicatedTable2() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate"+getSuffix());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    
    st = conn.createStatement();
    st.execute("insert into t1 values (10, 10)");

    conn.rollback();// rollback.

    ResultSet rs = st.executeQuery("Select * from t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    conn.commit(); // commit two rows.
    rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  public void testTransactionalInsertOnReplicatedTable() throws Exception {
    Connection conn= getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate"+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    st.execute("insert into t1 values (10, 10)");

    conn.rollback();// rollback.

    ResultSet rs = st.executeQuery("Select * from t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    conn.commit(); // commit two rows.
    rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  public void Debug_testGFCommit() throws Exception {
    createCache();
    CacheTransactionManager ctm = this.cache.getCacheTransactionManager();
    for (int i = 0; i < 10; i++) {
      TXManagerImpl txManager = (TXManagerImpl)ctm;

      txManager.begin();
      Misc.getCacheLogWriter().info(
          "XXXX started transaction : " + txManager.getTransactionId());
      final TXStateInterface txState = txManager.internalSuspend();
      Misc.getCacheLogWriter().info(
          "XXXX started transaction : " + txState.getTransactionId());
    }
    //ctm.begin();
    //Region<Object, Object> region = Misc.getRegion("TXTest");
    //region.put("Key", "value");
    //assertTrue("XXXX should have a transactional state", ctm.exists() );
    //ctm.rollback();
    //assertNull("XXXX should return null value ", this.region.get("key"));
  }

  private void createCache() throws Exception {
    Properties p = new Properties();
    p.setProperty("mcast-port", "0"); // loner
    this.cache = (GemFireCacheImpl)new CacheFactory(p).create();
    createRegion("TXTest");
  }

  protected final void createRegion(String name) throws Exception {
    final AttributesFactory<?, ?> af = new AttributesFactory<Object, Object>();
    af.setScope(Scope.DISTRIBUTED_NO_ACK);
    af.setIndexMaintenanceSynchronous(true);
    af.setDataPolicy(DataPolicy.REPLICATE);
    af.setPartitionAttributes(new PartitionAttributesFactory<Object, Object>()
        .setLocalMaxMemory(0).create());
    this.cache.createRegion(name, af.create());
  }

  private void doT1T2Inserts(final Statement stmt) throws SQLException {
    stmt.execute("insert into t1 values(1, 1, 1)");
    stmt.execute("insert into t1 values(2, 2, 2)");
    stmt.execute("insert into t2 values(1, 1, 1)");
    stmt.execute("insert into t2 values(2, 2, 2)");
  }

  /**
   * Test commit with conflict.
   */
  public void testCommitWithConflicts() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate" +getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    st.execute("insert into tran.t1 values (10, 1)");
    Cache cache = Misc.getGemFireCache();
    final TXManagerImpl txMgrImpl = (TXManagerImpl)cache
        .getCacheTransactionManager();
    // Region r= cache.getRegion("APP/T1");
    final Region<Object, Object> r = Misc.getRegionForTable("TRAN.T1", true);
    final Object key = getGemFireKey(10, r);
    this.gotConflict = false;
    Thread thread = new Thread(new Runnable() {

      @Override
      public void run() {
        assertNotNull(r);
        txMgrImpl.begin();
        try {
          r.put(key, new SQLInteger(20)); // create a conflict here.
          fail("expected a conflict here");
        } catch (ConflictException ce) {
          gotConflict = true;
        }
        assertNull(r.get(key));
        TXStateInterface txi = txMgrImpl.internalSuspend();
        assertNotNull(txi);

        txMgrImpl.resume(txi);
        txMgrImpl.commit();
      }
    });
    thread.start();
    thread.join();

    conn.commit();
    st.close();

    assertTrue("expected conflict", this.gotConflict);
    this.gotConflict = false;

    // Important : Remove the key - value pair.
    r.destroy(key);

    Statement st2 = conn.createStatement();
    st2.execute("insert into tran.t1 values (10, 10)");
    conn.commit();
    ResultSet rs = st2.executeQuery("select * from tran.t1");
    int numRow = 0;
    while (rs.next()) {
      numRow++;
      assertEquals("Primary Key coloumns should be 10 ", 10, rs.getInt(1));
      assertEquals("Second columns should be 10 , ", 10, rs.getInt(2));
    }
    assertEquals("ResultSet should have two rows ", 1, numRow);

    rs.close();
    st2.close();
    conn.commit();
    conn.close();
  }

  /**
   * Test commit with conflict on global index for PK != partitioning.
   */
  public void testCommitWithConflictsOnGlobalIndex() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table tran.t1 (c1 int not null, c2 int not null, "
        + "c3 int not null, primary key(c1), unique(c3)) "
        + "partition by column (c2)"+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    st.execute("insert into tran.t1 values (10, 1, 2)");

    Connection conn2 = getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    Statement st2 = conn2.createStatement();
    // expect a conflict here
    try {
      st2.execute("insert into tran.t1 values (10, 20, 30)");
      conn2.commit();
      fail("expected conflict");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    conn.commit();
    ResultSet rs = st2.executeQuery("select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
      assertEquals("Primary Key coloumn should be 10 ", 10, rs.getInt(1));
      assertEquals("Second column should be 1", 1, rs.getInt(2));
      assertEquals("Third column should be 2", 2, rs.getInt(3));
    }
    assertEquals("ResultSet should have one row ", 1, numRows);
    conn2.commit();

    // now check conflict on update; we should get proper conflict and not
    // constraint violation
    st.execute("insert into tran.t1 values (20, 2, 3)");
    st2.execute("insert into tran.t1 values (30, 30, 40)");
    try {
      st2.execute("update tran.t1 set c3=3 where c1=30");
      fail("expected conflict");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    conn.rollback();

    // now check the same with committed data but now there should be constraint
    // violation
    st2.execute("insert into tran.t1 values (20, 2, 3)");
    try {
      st2.execute("update tran.t1 set c3=2 where c1=20");
      fail("expected constraint violation");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // constraint violation will abort TX so insert again (#43170)
    st2.execute("insert into tran.t1 values (20, 2, 3)");
    st2.execute("insert into tran.t1 values (30, 3, 4)");
    conn2.commit();

    // check for committed data
    rs = st.executeQuery("select * from tran.t1 order by c1");
    numRows = 0;
    while (rs.next()) {
      numRows++;
      assertEquals("Primary Key coloumn should be " + numRows * 10,
          numRows * 10, rs.getInt(1));
      assertEquals("Second column should be " + numRows, numRows, rs.getInt(2));
      assertEquals("Third column should be " + (numRows + 1), numRows + 1,
          rs.getInt(3));
    }
    assertEquals("ResultSet should have one row ", 3, numRows);
    conn.commit();

    conn.close();
    conn2.close();
  }

  /**
   * Test transactional insert on Partitioned and Replicated Tables.
   * @throws Exception on failure.
   */
  public void testCommitOnPartitionedAndReplicatedTables() throws Exception {
    Connection conn= getConnection();
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, " +
    		"primary key(c1)) replicate"+getSuffix());
    // partitioned table.
    st.execute("Create table t2 (c1 int not null , c2 int not null, " +
    		"primary key(c1)) "+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    // operations on partitioned followed by replicated.
    st.execute("insert into t2 values(10,10)");
    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");
        
    conn.commit(); // commit three rows.
    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    numRows = 0;
    rs = st.executeQuery("Select * from t2");
    while(rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should have one row", 1, numRows);
    // Close connection, resultset etc...
    rs.close();
    st.close();

    checkConnCloseExceptionForReadsOnly(conn);

    conn.commit();
    conn.close();
  }
  
  /**
   * Test commit with indexes. This is for future work.
   * @throws Exception on failure.
   */
  public void testCommitWithIndex() throws Exception {
    Connection conn= getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, primary key(c1)) replicate"+getSuffix());
    st.execute("Create index c2Index on t1(c2)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    
    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");
    
    conn.commit(); // commit two rows.
    ResultSet rs = st.executeQuery("Select * from t1 where t1.c2 = 10");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 1, numRows);
    
    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }
  
  
  /**
   * Test transactional updates.
   * @throws Exception
   */
  public void testTransactionalKeyBasedUpdates() throws Exception {
    Connection conn= getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema test");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , " +
        "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1) ) "+
        "Partition by column (PkCol1)"+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    this.doOffHeapValidations();
    conn.commit();
    this.doOffHeapValidations();
    ResultSet rs = st.executeQuery("select * from test.t1");
    int numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should be 10", 10, rs.getInt(3));
      assertEquals("Column value should be 10", 10, rs.getInt(4));
      assertEquals("Column value should be XXXX1", "XXXX1", rs.getString(5).trim());
      numRows++;
    }    
    this.doOffHeapValidations();
    assertEquals("Numbers of rows in resultset should be one", 1, numRows);
    conn.commit();
    this.doOffHeapValidations();
    st.execute("update test.t1 set col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=10");
    conn.commit();
    this.doOffHeapValidations();
    //Region<?, ?> r= Misc.getRegionForTable("TEST.T1");
    rs = st.executeQuery("select * from test.t1");
    numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should change", 20, rs.getInt(3));
      assertEquals("Columns value should change", 20, rs.getInt(4));
      assertEquals("Columns value should change", "changed", rs.getString(5).trim());
      numRows++;
    }    
    this.doOffHeapValidations();
    assertEquals("Numbers of rows in resultset should be one", 1, numRows);
    rs.close();
    st.close();
    conn.commit();
    conn.close();
    
  }
  
  /**
   * Test truncate with transaction.
   */
  public void testTruncate() throws Exception {
    Connection conn= getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema test");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , " +
        "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1) ) "+
        "Partition by column (PkCol1)"+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    conn.commit();
    st.execute("truncate table test.t1");
    conn.commit();
    ResultSet rs = st.executeQuery("select * from test.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should have zero rows", 0, numRows);
    st.close();
    conn.commit();
  }

  /**
   * Check supported isolation levels.
   */
  public void testIsolationLevels() throws Exception {
    // try {
    Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

    try {
      conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      fail("expected failure in unsupported isolation-level SERIALIZABLE");
    } catch (SQLException ex) {
      if (!ex.getSQLState().equalsIgnoreCase("XJ045")) {
        throw ex;
      }
    }
    conn.close();
  }

  public void testNewGFETransactionNotCreatedForPKBasedOp() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema trade");    
    
    st.execute("create table trade.securities (sec_id int not null, " +
        "symbol varchar(10) not null, price decimal (30, 20), " +
        "exchange varchar(10) not null, tid int, " +
        "constraint sec_pk primary key (sec_id) ) " +
        " partition by column (tid) "+getSuffix());
    
    conn.setTransactionIsolation(getIsolationLevel());    
    PreparedStatement ps = conn.prepareStatement("insert into trade.securities values " +
        "(?, ?, ?, ?, ?)");
    for (int i = 0; i< 1 ; i++) {
      ps.setInt(1, i);
      ps.setString(2, "XXXX"+i);
      ps.setDouble(3, i);
      ps.setString(4, "nasdaq");
      ps.setInt(5, i);
      ps.executeUpdate();
    }
    //Get TxID of the transaction
    /*
    GemFireTransaction txn = TestUtil.getGFT((EmbedConnection)conn);
    TransactionId  tid = txn.getGFETransactionID();
    assertNull(GfxdConnectionHolder.getHolder().getConnectionID(tid));
    */
    conn.commit();
  }

  /**
   * Transactions with indexes.
   */
  public void testTransactionsAndIndexMaintenance() throws Exception {
    Connection conn= getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema test");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , " +
        "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1) ) "+
        "Partition by column (PkCol1)"+getSuffix());
    conn.commit();
    st.execute("create index IndexCol4 on test.t1 (col4)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    final int rows = 1000;
    
    final CheckIndexOperations checkIndex = new CheckIndexOperations("Test.IndexCol4");
    try {
      GemFireXDQueryObserverHolder.putInstance(checkIndex);
      PreparedStatement psInsert = conn.prepareStatement("insert into test.t1 "
          + "values (?,10,10,10,'XXXX1')");
      for (int i = 0; i < rows; i++) {
        psInsert.setInt(1, i);
        psInsert.executeUpdate();
        conn.commit();
      }
      checkIndex.checkNumInserts(rows);
      
    } finally {      
      GemFireXDQueryObserverHolder.removeObserver(checkIndex);      
    }
    //conn.commit();
    ResultSet rs = st.executeQuery("select * from test.t1 where col4 = 10");
    int numRows = 0;
    while (rs.next()) {
      assertEquals("Should return correct result ", 10, rs.getInt("COL4"));
      numRows++;
    }
    assertEquals("Should return 1000 rows ", rows, numRows);
    rs.close();
    PreparedStatement psUpdate = conn.prepareStatement("update test.t1 set " +
    "col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=?");
    try {
      GemFireXDQueryObserverHolder.putInstance(checkIndex);
    for(int i = 0; i < rows ; i++) {
      psUpdate.setInt(1, i);
      psUpdate.executeUpdate();
      conn.commit();
    }
    } finally {
      GemFireXDQueryObserverHolder.removeObserver(checkIndex);      
    }
    // TODO fix the observer index maintenance increment logic with the new model.
    //checkIndex.checkNumInserts(2 * rows);
    //checkIndex.checkNumDeletes(rows);
    rs = st.executeQuery("select * from test.t1 where col4 = 20 " +
    		"order by PkCol1 asc");
    numRows = 0;
    while (rs.next()) {
      assertEquals("Should return correct result ", 20, rs.getInt("COL4"));
      assertEquals("Should return correct result ", 20, rs.getInt("COL3"));
      assertEquals("Should return correct result ", "changed",
          rs.getString("COL5").trim());
      assertEquals("Should return correct result ", numRows,
          rs.getInt("PKCOL1"));
      numRows++;
    }
    assertEquals("Should return 1000 rows ", rows, numRows);
    st.close();
    conn.commit();
  }

  /**
   * Internal test class for checking index operations.
   * 
   * @author rdubey
   */
  @SuppressWarnings("serial")
  class CheckIndexOperations extends GemFireXDQueryObserverAdapter {

    int numInserts = 0;

    int numDeletes = 0;

    private final String indexName;

    private CheckIndexOperations(String name) {
      this.indexName = name;
    }

    @Override
    public void keyAndContainerAfterLocalIndexInsert(Object key,
        Object rowLocation, GemFireContainer container) {

      assertNotNull(key);
      assertNotNull(rowLocation);
      assertTrue(this.indexName.equalsIgnoreCase(container.getSchemaName()
          + "." + container.getTableName().toString()));
      this.numInserts++;
    }

    @Override
    public void keyAndContainerAfterLocalIndexDelete(Object key,
        Object rowLocation, GemFireContainer container) {
      assertNotNull(key);
      assertNotNull(rowLocation);
      assertTrue(this.indexName.equalsIgnoreCase(container.getSchemaName()
          + "." + container.getTableName().toString()));
      this.numDeletes++;
    }

    void checkNumInserts(int expected) {
      System.out.println("XXXX the number of call : " + this.numInserts);
      assertEquals(expected, this.numInserts);
    }

    void checkNumDeletes(int expected) {
      assertEquals(expected, this.numDeletes);
    }
  }

  public void testTransactionalDeletes() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1))"+getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    st.execute("insert into tran.t1 values (10, 10)");
    conn.commit();

    ResultSet rs = st.executeQuery("select * from tran.t1");
    assertTrue(rs.next());
    assertFalse(rs.next());

    st.execute("delete from tran.t1 where c1 = 10");
    rs = st.executeQuery("select * from tran.t1");
    // selects should reflect TX state
    assertFalse(rs.next());
    conn.commit();
    rs = st.executeQuery("select * from tran.t1");
    assertFalse(rs.next());
    conn.commit();
    rs.close();
    st.close();
    conn.close();
  }

  public void testTransactionalDeleteWithLocalIndexes() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1))"+getSuffix());
    conn.commit();
    st.execute("create index IndexCol2 on tran.t1 (c2)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    int numRows = 1000;
    PreparedStatement ps = conn.prepareStatement("insert into tran.t1 values " +
    		"(?, ?)");
    for (int i = 0; i< numRows ; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.executeUpdate();
    }
    conn.commit();
    final CheckIndexOperations checkIndex = new CheckIndexOperations("Tran.IndexCol2");
    PreparedStatement psDelete = conn.prepareStatement("delete from tran.t1 " +
    		"where c1 = ?");
    try {
      GemFireXDQueryObserverHolder.putInstance(checkIndex);
      for (int i = 0 ; i < numRows ; i++) {
        psDelete.setInt( 1, i);
        psDelete.executeUpdate();
      }
      conn.commit();
      // TODO fix the observer index maintenance increment logic with the new model.
      //checkIndex.checkNumDeletes(numRows);
    
    } finally {
      GemFireXDQueryObserverHolder.removeObserver(checkIndex);      
    }
    
    st.close();
    conn.close();    
  }

  public void testTransactionsAndUniqueIndexes() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "c3 int not null, primary key(c1), "
        + "constraint C3_Unique unique (c3)) replicate"+getSuffix());
    conn.commit();
    st.execute("create index IndexCol2 on tran.t1 (c2)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    int numRows = 2;
    PreparedStatement ps = conn.prepareStatement("insert into tran.t1 values "
        + "(?, ?, ?)");
    try {
      for (int i = 0; i < numRows; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.setInt(3, 2);
        ps.executeUpdate();
      }
      conn.commit();
      fail("Commit should throw an exception for unique key violation");
    } catch (SQLException ex) {
      if (!"23505".equalsIgnoreCase(ex.getSQLState())) {
        throw ex;
      }
    }
    conn.rollback();
    conn.close();
    /*
    final CheckIndexOperations checkIndex = new CheckIndexOperations(
        "Tran.IndexCol2");
    GemFireXDQueryObserver old = null;
    PreparedStatement psDelete = conn.prepareStatement("delete from tran.t1 "
        + "where c1 = ?");
    try {
      old = GemFireXDQueryObserverHolder.setInstance(checkIndex);
      for (int i = 0; i < numRows; i++) {
        psDelete.setInt(1, i);
        psDelete.executeUpdate();
      }
      conn.commit();
      checkIndex.checkNumDeletes(numRows);

    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      else {
        GemFireXDQueryObserverHolder.clearInstance();
      }
    }

    st.close();
    conn.close();
    */
  }
 
  public void testTransactionalUpdate() throws Exception {
    
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema trade");    
    
    st.execute("create table trade.securities (sec_id int not null, " +
        "symbol varchar(10) not null, price decimal (30, 20), " +
        "exchange varchar(10) not null, tid int, " +
        "constraint sec_pk primary key (sec_id) ) " +
        " partition by column (tid) "+getSuffix());
    
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    final int numRows = 5;
    PreparedStatement ps = conn.prepareStatement("insert into trade.securities values " +
        "(?, ?, ?, ?, ?)");
    for (int i = 0; i< numRows ; i++) {
      ps.setInt(1, i);
      ps.setString(2, "XXXX"+i);
      ps.setDouble(3, i);
      ps.setString(4, "nasdaq");
      ps.setInt(5, i);
      ps.executeUpdate();
    }
    conn.commit();
    this.doOffHeapValidations();
    //InternalDistributedSystem.getAnyInstance().getLogWriter().info("XXXX starting the update");
    PreparedStatement psUpdate = conn.prepareStatement("update trade.securities " +
        "set symbol = ? where sec_id = ? and tid = ?");
    for (int i = 0 ; i < numRows ; i++) {
      psUpdate.setString(1, "YYY"+i);
      psUpdate.setInt(2, i);
      psUpdate.setInt(3, i);
      psUpdate.executeUpdate();
    }
  
    //psUpdate.executeUpdate();
    //InternalDistributedSystem.getAnyInstance().getLogWriter().info("XXXX update is done");
    ResultSet rs = st.executeQuery("select * from trade.securities");
    int numRowsReturned = 0;
    while (rs.next()) {
      assertTrue("Value should be YYY",
          (rs.getString("SYMBOL").trim()).startsWith("YYY"));
      numRowsReturned++;
    }
  
    assertEquals("Expected "+numRows+" row but found "+numRowsReturned, 
        numRows, numRowsReturned);

    // rollback and check that old values are restored
    conn.rollback();
    this.doOffHeapValidations();
    rs = st.executeQuery("select * from trade.securities");
    numRowsReturned = 0;
    while (rs.next()) {
      assertTrue("Value should be XXXX",
          (rs.getString("SYMBOL").trim()).startsWith("XXXX"));
      numRowsReturned++;
    }
    this.doOffHeapValidations();
    assertEquals("Expected "+numRows+" row but found "+numRowsReturned, 
        numRows, numRowsReturned);

    // now commit and check for updated values
    for (int i = 0; i < numRows; i++) {
      psUpdate.setString(1, "YYY" + i);
      psUpdate.setInt(2, i);
      psUpdate.setInt(3, i);
      psUpdate.executeUpdate();
    }
    conn.commit();
    this.doOffHeapValidations();
    rs = st.executeQuery("select * from trade.securities");
    assertTrue(rs.next());
    assertTrue(rs.getString("SYMBOL").trim().startsWith("YYY"));
    conn.commit();
    rs.close();
    this.doOffHeapValidations();
    st.close();
    psUpdate.close();
    ps.close();
    conn.close();
  }  

  public void testBug42178() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
                " c4 int not null , c5 int not null ," +
                " primary key(c1)) " + getSuffix());
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?,?,?,?)");
    for(int i = 1 ;i < 21;++i) {
      pstmt.setInt(1,i);
      pstmt.setInt(2,i);
      pstmt.setInt(3,i);
      pstmt.setInt(4,i);
      pstmt.setInt(5,i);
      pstmt.executeUpdate();
      
    }    
   
    @SuppressWarnings("serial")
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.putInstance(observer);   
    assertEquals(1,st.executeUpdate("update t1 set c5 =5 where c5 =1"));
    //Two rows should get modified . one in commited c5 = 5 and the other in tx area c5 =1
    assertEquals(2,st.executeUpdate("update t1 set c2 = 8  where c5 = 5"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c2 from t1 where c5 =5");     
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(8,rs.getInt(1));
      conn.commit();
    }
    finally {
      GemFireXDQueryObserverHolder.removeObserver(observer);
    }  
  }
  
  public void testBug42323() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "c3 int not null, c4 int not null , c5 int not null, "
        + "primary key(c1)) partition by column(c2)"+getSuffix());
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    PreparedStatement pstmt = conn
        .prepareStatement("insert into t1 values(?,?,?,?,?)");
    for (int i = 1; i < 3; ++i) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i);
      pstmt.setInt(3, i);
      pstmt.setInt(4, i);
      pstmt.setInt(5, i);
      pstmt.executeUpdate();
    }
    conn.commit();
    final boolean[] validTestPath = { false };
    final Exception[] e = new Exception[] { null, null };
    @SuppressWarnings("serial")
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost) {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        return 1;
      }

      @Override
      public void beforeInvokingContainerGetTxRowLocation(
          RowLocation regionEntry) {
        if (!validTestPath[0]) {
          Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                java.sql.Connection conn1 = getConnection();
                conn1.setTransactionIsolation(getIsolationLevel());
                final Statement st1 = conn1.createStatement();
                assertEquals(1,
                    st1.executeUpdate("delete from T1 where c5 = 1"));
                conn1.commit();
              } catch (SQLException excep) {
                e[0] = excep;
              }
            }
          });
          validTestPath[0] = true;
          th.start();

          try {
            th.join();
          } catch (InterruptedException ie) {
            e[1] = ie;
          }
        }
      }
    };
    st.executeUpdate("update T1 set c3 = 7 where c2 = 2");
    GemFireXDQueryObserverHolder.putInstance(observer);
    try {
      assertEquals(0, st.executeUpdate("delete from T1 where c5 = 1"));
      conn.commit();
      assertTrue(validTestPath[0]);
      if (e[0] != null) {
        fail(e[0].toString());
      }
      else if (e[1] != null) {
        fail(e[1].toString());
      }
    } finally {
      GemFireXDQueryObserverHolder.removeObserver(observer);
    }
  }

  public void testBugDifferentThreadDifferentConnection42323() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null)"+getSuffix());
    stmt.execute("Create table t2 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "foreign key (c1) references t1(c1))"+getSuffix());
    stmt.execute("insert into t1 values(1, 1, 1)");
    
    stmt = conn.createStatement();
    stmt.execute("insert into t2 values(1, 1, 1)");
    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("the above insert should have failed");
    } catch (SQLException ex) {
      // TestUtil.getLogger().info("exception state is: " + ex.getSQLState(),
      // ex);
      assertTrue("23503".equalsIgnoreCase(ex.getSQLState()));
    }
    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);

    this.threadEx = null;
    Thread th = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          Connection newConn = TestUtil.getConnection();
          newConn
              .setTransactionIsolation(getIsolationLevel());
          newConn.setAutoCommit(false);
          Statement newstmt = newConn.createStatement();
          try {
            newstmt.execute("insert into t2 values(2, 2, 2)");
            fail("ConflictException should have come");
          } catch (SQLException sqle) {
            if (!"X0Z02".equals(sqle.getSQLState())) {
              throw sqle;
            }
          }
        } catch (Throwable t) {
          threadEx = t;
          fail("unexpected exception", t);
        }
      }
    });

    th.start();
    th.join();
    if (this.threadEx != null) {
      fail("unexpected exception in thread", this.threadEx);
    }
    ResultSet rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    stmt.execute("insert into t1 values(3, 3, 3)");
    rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    conn.commit();
    rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    conn.commit();
  }

  public void testBugSameThreadDifferentConnection42323() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null)"+getSuffix());
    stmt.execute("Create table t2 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "foreign key (c1) references t1(c1))"+getSuffix());
    stmt.execute("insert into t1 values(1, 1, 1)");
    //conn.setTransactionIsolation(getIsolationLevel());
    //conn.setAutoCommit(false);
    stmt = conn.createStatement();
    stmt.execute("insert into t2 values(1, 1, 1)");
    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("the above insert should have failed");
    } catch (SQLException ex) {
      assertTrue("23503".equalsIgnoreCase(ex.getSQLState()));
    }
    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);

    Connection newConn = TestUtil.getConnection();

    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("constraint violation should have come");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    newConn.setTransactionIsolation(getIsolationLevel());
    newConn.setAutoCommit(false);
    
    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);
    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("constraint violation should have come");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    Statement newstmt = newConn.createStatement();

    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);
    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("constraint violation should have come");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);
    try {
      newstmt.execute("insert into t2 values(2, 2, 2)");
      fail("ConflictException should have come");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("insert into t2 values(2, 2, 2)");
      fail("constraint violation should have come");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // constraint violation will abort TX so insert again (#43170)
    doT1T2Inserts(stmt);
    ResultSet rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    stmt.execute("insert into t1 values(3, 3, 3)");
    rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    conn.commit();
    rs = stmt.executeQuery("select count(*) from t1");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    conn.commit();
  }

  // #42905 -- GemFireXD failed to throw foreign key constraint exception
  // for a delete operation following an insert of the child table in same tx
  public void testBug42905() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setAutoCommit(false);
    // conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null)"+getSuffix());
    stmt.execute("Create table t2 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "foreign key (c1) references t1(c1))"+getSuffix());
    stmt.execute("insert into t1 values(1, 1, 1)");
    stmt = conn.createStatement();
    // stmt.execute("insert into t2 values(1, 1, 1)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    // stmt.execute("delete from t2 where c1 = 1");
    boolean gotException = false;
    try {
      stmt.execute("insert into t2 values(1, 1, 1)");
      stmt.execute("delete from t1 where c1 = 1");
      fail("the above delete should have thrown a constraint violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
      gotException = true;
    }
    assertTrue(gotException);
  }

  private static Statement childStmnt = null;

  // #42915 -- gemfirexd failed to detect conflict exception when one 
  // tx inserts into child table with another tx inserts into parent table
  // or updates the parent table entry
  public void testBug42915() throws Exception {
    Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null)"+getSuffix());
    stmt.execute("Create table t2 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "foreign key (c1) references t1(c1))"+getSuffix());
    conn.commit();

    Connection childConn = getConnection();
    childConn.setTransactionIsolation(getIsolationLevel());
    childConn.setAutoCommit(false);
    childStmnt = childConn.createStatement();
    childStmnt.execute("insert into t1 values(1, 1, 1)");

    stmt = conn.createStatement();
    boolean gotException = false;
    try {
      stmt.execute("insert into t2 values(1, 1, 1)");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
      gotException = true;
    }
    assertTrue(gotException);

    // now commit in second TX, then insert in second should succeed
    childConn.commit();
    stmt.execute("insert into t2 values(1, 1, 1)");

    // if now parent update is tried then it should conflict
    try {
      childStmnt.execute("update t1 set c2 = 2 where c1 = 1");
      childConn.commit();
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // and so too should delete
    try {
      childStmnt.execute("delete from t1 where c1 = 1");
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // after rollback in first TX the update and delete should then pass
    conn.rollback();
    childStmnt.execute("update t1 set c2 = 2 where c1 = 1");
    childStmnt.execute("delete from t1 where c1 = 1");
    childConn.commit();

    // now try from the same transaction and it should not fail
    stmt.execute("delete from t2 where c1 = 1");
    stmt.execute("insert into t1 values(1, 1, 1)");
    conn.commit();
    stmt.execute("insert into t2 values(1, 1, 1)");
    // delete should still fail
    try {
      stmt.execute("delete from t1 where c1 = 1");
      fail("expected a constraint violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // update of row should be fine
    stmt.execute("update t1 set c2 = 3 where c1 = 1");
    conn.commit();

    // verify the final values
    ResultSet rs = stmt.executeQuery("select c2, c3 from t1 where c1 = 1");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertEquals(1, rs.getInt(2));
    assertFalse(rs.next());
    rs.close();
    conn.commit();
  }

  public void testBug42923_uniqButUpdateRequiresFnExn() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    java.sql.Connection conn = getConnection();
    conn.setAutoCommit(false);
    // conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, c2 int not null, "
            + "c3 int not null, constraint C3_Unique unique (c3))"+getSuffix());
    
    stmt.execute("insert into t1 values (1, 1, 1), (2, 2, 2), (3, 3, 3)");
    conn.commit();
    this.doOffHeapValidations();
    final boolean [] shouldThreadCommit = new boolean[]{false};
    Runnable r = new Runnable() {

      @Override
      public void run() {
        try {
          java.sql.Connection childConn = getConnection();
          childConn.setTransactionIsolation(getIsolationLevel());
          childConn.setAutoCommit(false);
          childStmnt = childConn.createStatement();
          childStmnt.execute("update t1 set c3 = 4 where c3 > 2");
          latch.countDown();
          synchronized(TransactionTest.this) {
            if(!shouldThreadCommit[0]) {
              TransactionTest.this.wait();
            }
          }
          childConn.commit();
        } catch (Exception e) {
          fail("got exception in the spawned thread", e);
        }
      }
    };
    
    Thread t = new Thread(r);
    t.start();
    latch.await();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    stmt = conn.createStatement();
    boolean gotException = false;
    try {
      stmt.execute("update t1 set c3 = 4 where c3 > 2");
      conn.commit();
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
      gotException = true;
    }
    synchronized(this) {
      shouldThreadCommit[0] = true;
      this.notify();
    }
    assertTrue(gotException);
    t.join();
  }
  
  public void testBug42923_uniqButUpdateIsPutConvertible() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    java.sql.Connection conn = getConnection();
    conn.setAutoCommit(false);
    // conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, c2 int not null, "
            + "c3 int not null, constraint C3_Unique unique (c3))"+getSuffix());
    
    stmt.execute("insert into t1 values (1, 1, 1), (2, 2, 2), (3, 3, 3)");
    conn.commit();
    final boolean [] shouldThreadCommit = new boolean[]{false};
    
    Runnable r = new Runnable() {

      @Override
      public void run() {
        try {
          java.sql.Connection childConn = getConnection();
          childConn.setTransactionIsolation(getIsolationLevel());
          childConn.setAutoCommit(false);
          childStmnt = childConn.createStatement();
          childStmnt.execute("update t1 set c3 = 4 where c1 = 2");
          latch.countDown();
          synchronized(TransactionTest.this) {
            if(!shouldThreadCommit[0]) {
              TransactionTest.this.wait();
            }
          }
          childConn.commit();
        } catch (Exception e) {
          getLogger().error("unexpected exception", e);
          fail("got exception in the spawned thread", e);
        }
      }
    };
    
    Thread t = new Thread(r);
    t.start();
    latch.await();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    stmt = conn.createStatement();
    boolean gotException = false;
    try {
      stmt.execute("update t1 set c3 = 4 where c1 = 2");
      conn.commit();
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
      gotException = true;
    }
    synchronized(this) {
      shouldThreadCommit[0] = true;
      this.notify();
    }
    assertTrue(gotException);
    t.join();
  }

  /**
   * Test for conflict in two batch updates from same thread.
   */
  public void testTransactionErrorBatch() throws Exception {
    // conn is just default connection
    Connection conn = getConnection();
    Connection conn2 = getConnection();
    Connection conn3 = startNetserverAndGetLocalNetConnection();

    conn.setAutoCommit(false);
    conn2.setAutoCommit(false);

    conn.setTransactionIsolation(getIsolationLevel());
    conn2.setTransactionIsolation(getIsolationLevel());
    conn3.setTransactionIsolation(getIsolationLevel());

    Statement stmt = conn.createStatement();
    Statement stmt2 = conn2.createStatement();
    Statement stmt3 = conn3.createStatement();

    getLogger().info("Negative Statement: statement testing eager fail "
        + "while getting the lock in the batch");

    stmt.execute("create table t1 (c1 int not null primary key, c2 int)"+getSuffix());
    conn.commit();

    stmt.execute("insert into t1 values(1, 1)");
    stmt2.execute("insert into t1 values(2, 2)");
    stmt.addBatch("update t1 set c2=3 where c1=2");
    stmt2.addBatch("update t1 set c2=4 where c1=1");
    try {
      stmt.executeBatch();
      fail("Batch is expected to fail");
    } catch (SQLException sqle) {
      // we should get a conflict wrapped in BatchUpdateException
      if (!"X0Z02".equals(sqle.getSQLState())
          || !(sqle instanceof BatchUpdateException)) {
        throw sqle;
      }
    }
    // also check with network connection (#43109)
    stmt3.addBatch("update t1 set c2=3 where c1=2");
    stmt3.addBatch("update t1 set c2=3 where c1=2");
    try {
      stmt3.executeBatch();
      fail("Batch is expected to fail");
    } catch (SQLException sqle) {
      // we should get a conflict wrapped in BatchUpdateException
      if (!"X0Z02".equals(sqle.getSQLState())
          || !(sqle instanceof BatchUpdateException)) {
        throw sqle;
      }
    }
    int[] updateCount = stmt2.executeBatch();
    assertNotNull(updateCount);
    assertEquals(1, updateCount.length);
    assertEquals(0, updateCount[0]);

    conn.rollback();
    conn2.rollback();
    conn3.rollback();
    stmt.clearBatch();
    stmt2.clearBatch();
    stmt3.clearBatch();
    stmt.close();
    stmt2.close();
    stmt3.close();
    conn.close();
    conn2.close();
    conn3.close();
  }

  public void testTxnDeleteParentRow() throws Exception {
    Connection conn = getConnection();
    Statement stmtp1 = conn.createStatement();
    stmtp1.execute("Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "constraint C3_Unique unique (c3))"+getSuffix());

    stmtp1.execute("Create table t2 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, "
        + "foreign key (c1) references t1(c1))"+getSuffix());

    stmtp1.execute("insert into t1 values(1, 1, 1)");
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    
    Statement stmtp2 = conn.createStatement();
    stmtp2.execute("delete from t1 where c1 = 1");

    Connection childConn = TestUtil.getConnection();
    childConn.setTransactionIsolation(getIsolationLevel());
    Statement childStmnt = childConn.createStatement();
    addExpectedException(ConflictException.class);
    try {
      childStmnt.execute("insert into t2 values(1, 1, 1)");
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    removeExpectedException(ConflictException.class);

    // after rollback of parent, there should be no conflict
    conn.rollback();
    childStmnt.execute("insert into t2 values(1, 1, 1)");
    childConn.commit();
  }

  public void testTxnDeleteParentRow_bothCaseOfChildCommittedAndUncommitted() throws Exception {
    Connection conn = getConnection();
    Connection conn2 = getConnection();
    Connection conn3 = getConnection();
    Statement stmt = conn.createStatement();
    stmt
        .execute("Create table t1 (c1 int not null primary key, c2 int not null, "
            + "c3 int not null, constraint C3_Unique unique (c3))"+getSuffix());

    stmt
        .execute("Create table t2 (c1 int not null primary key, c2 int not null, "
            + "c3 int not null, foreign key (c1) references t1(c1))"+getSuffix());

    stmt
        .execute("Create table t3 (c1 int not null primary key, c2 int not null, "
            + "c3 int not null, foreign key (c1) references t1(c1))"+getSuffix());

    stmt.execute("insert into t1 values(1, 1, 1), (2, 2, 2)");

    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    conn3.setTransactionIsolation(getIsolationLevel());
    conn3.setAutoCommit(false);

    Statement stmt2 = conn2.createStatement();
    Statement stmt3 = conn2.createStatement();

    stmt2.execute("insert into t2 values(1, 1, 1), (2, 2, 2)");
    stmt3.execute("insert into t3 values(1, 1, 1), (2, 2, 2)");

    for (int i = 0; i < 2; i++) {
      if (i == 1) {
        conn2.commit();
        conn3.commit();
      }
      try {
        stmt.execute("delete from t1");
      } catch (SQLException e) {
        if (i == 0) {
          assertEquals("X0Z02", e.getSQLState());
        }
        else {
          assertEquals("23503", e.getSQLState());
        }
      }
    }
  }
  
  static volatile int updateCnt = 0;

  public void testTxnUpdateBehaviorForLocking() throws Exception {

    final CountDownLatch latch = new CountDownLatch(1);
    final CountDownLatch barrier = new CountDownLatch(2);
    final java.sql.Connection childConn = getConnection();
    Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int not null primary key, "
        + "qty int not null, c3 int not null, exchange varchar(20), "
        + "c4 int not null, constraint C3_Unique unique (c3), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex')), "
        + "constraint qty_ck check (qty >= 5))"+getSuffix());

    // stmt.execute("create index idx on t1(c4)");
    stmt.execute("insert into t1 values(1, 10, 1, 'nye', 4), "
        + "(2, 20, 2, 'amex', 4)");

    conn.commit();

    MemHeapScanController.setWaitObjectAfterFirstQualifyForTEST(latch);
    MemHeapScanController.setWaitBarrierBeforeFirstScanForTEST(barrier);
    MemHeapScanController.setWaitForLatchForTEST(0);

    try {
      final Exception[] ex = new Exception[] { null };
      Runnable r = new Runnable() {

        @Override
        public void run() {
          try {
            childConn.setTransactionIsolation(getIsolationLevel());
            Statement cstmt = childConn.createStatement();
            MemHeapScanController.setWaitForLatchForTEST(3);
            cstmt.execute("update t1 set c4 = 40 where c4 = 4");
            updateCnt = cstmt.getUpdateCount();
          } catch (Exception e) {
            ex[0] = e;
          } finally {
            MemHeapScanController.setWaitForLatchForTEST(0);
          }
        }
      };

      Thread t = new Thread(r);
      t.start();

      // set stop after first qualify
      // start second txn and fire update such that to change the columns which
      // will no more satisfy the above condition
      Connection conn2 = getConnection();
      conn2.setTransactionIsolation(getIsolationLevel());
      Statement stmt2 = conn2.createStatement();
      stmt2.execute("update t1 set c4 = 10");
      conn2.commit();
      latch.countDown();
      // move forward with the first txn and it should not update the row
      t.join();
      assertEquals(0, updateCnt);
      // expect a conflict in other thread since it takes SH lock before qualify
      // that will block commit, and then it will try to upgrade to EX_SH which
      // will conflict with this thread
      assertNotNull(ex[0]);
      if (!(ex[0] instanceof SQLException)) {
        throw ex[0];
      }
      if (!"X0Z02".equals(((SQLException)ex[0]).getSQLState())) {
        throw ex[0];
      }
    } finally {

      MemHeapScanController.setWaitBarrierBeforeFirstScanForTEST(null);
      MemHeapScanController.setWaitObjectAfterFirstQualifyForTEST(null);
    }
    childConn.commit();
  }

  public void testTxnUpdateToSameValueOnSameRow() throws Exception {
    Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    stmt.execute("Create table t1 (c1 int primary key, c2 int not null, "
        + "exchange varchar(20), symbol varchar(20), "
        + "constraint sym_ex unique (symbol, exchange))"+getSuffix());

    stmt.execute("insert into t1 values(1, 10, 'nyse', 'o'), (2, 20, 'amex', 'g')");

    conn.commit();
    stmt.execute("update t1 set exchange = 'nyse', symbol = 'o' where c1 = 1");
    conn.commit();
  }

  public void testTxnUpdateGettingLostBug2_43222_44096() throws Exception {
    Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema TXN");
    String sql = " create table TXN.customer "
        + "(c_w_id integer not null, c_d_id integer not null, "
        + "c_id integer not null, "
        + "c_last varchar(16), c_first varchar(16), "
        + "c_balance decimal(12,2), c_delivery_cnt integer)"
        + " partition by column(c_w_id) redundancy 1"+getSuffix();
    stmt.execute(sql);

    sql = "alter table TXN.customer add constraint "
        + "pk_customer primary key (c_w_id, c_d_id, c_id)";
    stmt.execute(sql);

    stmt.execute("insert into TXN.customer values"
        + "(1, 1, 2, 'bara', 'alan', -10.0, 0), "
        + "(2, 5, 5, 'para', 'bhalan', -10.0, 0), "
        + "(2, 5, 17, 'cara', 'jhalan', -10.0, 0)");
    conn.commit();

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.putInstance(observer);

    Connection conn2 = getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    Statement stmt2 = conn2.createStatement();
    String sql2 = "UPDATE TXN.customer SET c_balance = 4510.63 "
        + "WHERE c_w_id = 2 AND c_d_id = 5 AND c_id = 5";
    stmt2.executeUpdate(sql2);

    observer.addExpectedScanType("TXN.customer", ScanType.NONE);
    observer.checkAndClear();

    conn2.commit();

    sql = " UPDATE TXN.customer SET c_balance = c_balance + 3415.52, "
        + "c_delivery_cnt = c_delivery_cnt + 1 "
        + "WHERE c_id = 5 AND c_d_id = 5 AND c_w_id = 2";

    int cnt = stmt.executeUpdate(sql);
    assertEquals(1, cnt);

    observer.addExpectedScanType("TXN.customer", ScanType.HASH1INDEX);
    observer.checkAndClear();

    conn.commit();

    stmt.execute("create index TXN.ndx_customer_name on "
        + "customer (c_w_id, c_d_id, c_last, c_first)");

    cnt = stmt.executeUpdate(sql);
    assertEquals(1, cnt);

    try {
      stmt2.executeUpdate(sql);
      fail("expected conflict exception");
    } catch (SQLException sq) {
      if (!"X0Z02".equals(sq.getSQLState())) {
        throw sq;
      }
    }

    sql = "select * from TXN.customer "
        + "WHERE c_id = 5 AND c_d_id = 5 AND c_w_id = 2";
    stmt.execute(sql);

    ResultSet rs = stmt.getResultSet();
    int sel_cnt = 0;
    while (rs.next()) {
      sel_cnt++;
      System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", "
          + rs.getObject(3) + ", " + rs.getObject(4) + ", " + rs.getObject(5)
          + ", " + rs.getObject(6) + ", " + rs.getObject(7));
    }
    assertEquals(1, sel_cnt);

    conn.commit();
  }

  public void testTxnUpdateGettingLostBug_43222() throws Exception {
    Connection conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();

    stmt.execute("create schema TXN");
    String sql = " create table TXN.customer "
        + "(c_w_id integer not null, c_d_id integer not null, "
        + "c_id integer not null, "
        + "c_last varchar(16), c_first varchar(16), "
        + "c_balance decimal(12,2), c_delivery_cnt integer)"
        + " partition by column(c_w_id) redundancy 1"+getSuffix();
    stmt.execute(sql);

    sql = "alter table TXN.customer add constraint pk_customer primary key "
        + "(c_w_id, c_d_id, c_id)";
    stmt.execute(sql);

    sql = "create index TXN.ndx_customer_name on customer "
        + "(c_w_id, c_d_id, c_last, c_first)";
    stmt.execute(sql);

    stmt.execute("insert into TXN.customer values"
        + "(1, 1, 2, 'bara', 'alan', -10.0, 0), "
        + "(2, 6, 13, 'para', 'bhalan', -10.0, 0), "
        + "(2, 5, 17, 'cara', 'jhalan', -10.0, 0)");

    conn.commit();

    sql = "UPDATE TXN.customer SET c_balance = c_balance + 56503.58, "
        + "c_delivery_cnt = c_delivery_cnt + 1 "
        + "WHERE c_id = 13 AND c_d_id = 6 AND c_w_id = 2";

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.putInstance(observer);

    int cnt = stmt.executeUpdate(sql);

    observer.addExpectedScanType("TXN.customer", ScanType.HASH1INDEX);
    observer.checkAndClear();

    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);

    Statement stmt2 = conn2.createStatement();
    try {
      stmt2.executeUpdate(sql);
      fail("expected conflict exception");
    } catch (SQLException sq) {
      if (!"X0Z02".equals(sq.getSQLState())) {
        throw sq;
      }
    }

    assertEquals(1, cnt);

    sql = "select * from TXN.customer WHERE "
        + "c_id = 13 AND c_d_id = 6 AND c_w_id = 2";
    stmt.execute(sql);

    ResultSet rs = stmt.getResultSet();
    int sel_cnt = 0;
    while (rs.next()) {
      sel_cnt++;
    }

    assertEquals(1, sel_cnt);

    conn.commit();
  }

  public void testDeleteLockBeforeReferenceKeyCheck() throws Exception {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    // create customers and portfolio tables and load data
    CreateTableTest.createTables(conn);
    CreateTableTest.populateData(conn, false, false);

    conn.commit();
    // couple of indexes to check sorted map index locking
    stmt.execute("create index cust_1 on trade.customers(tid)");
    stmt.execute("create index port_1 on trade.portfolio(sid)");

    // create second connection that will conflict with the above one
    final Connection conn2 = getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    final Statement stmt2 = conn2.createStatement();

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.putInstance(observer);

    // fire a PK based update on customers first
    assertEquals(1, stmt2.executeUpdate("update trade.customers "
        + "set addr = 'addr20' where cid = 10"));

    observer.addExpectedScanType("trade.customers", ScanType.NONE);
    observer.checkAndClear();

    checkConflictsWithDifferentScanTypes(stmt, 10, observer);

    // now for updates using index/table scans
    assertEquals(1, stmt2.executeUpdate("update trade.customers "
        + "set tid = tid + 100 where cid = 11"));

    observer.addExpectedScanType("trade.customers", ScanType.HASH1INDEX);
    observer.checkAndClear();

    checkConflictsWithDifferentScanTypes(stmt, 11, observer);

    assertEquals(1, stmt2.executeUpdate("update trade.customers "
        + "set tid = tid + 100 where cid > 11 and cid < 13"));

    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    checkConflictsWithDifferentScanTypes(stmt, 12, observer);

    // tid=13 was incremented to 113 in previous update where cid=12
    assertEquals(6, stmt2.executeUpdate("update trade.customers "
        + "set tid = tid + 100 where tid >= 13 and tid < 20"));

    observer.addExpectedScanType("trade.customers", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    checkConflictsWithDifferentScanTypes(stmt, 14, observer);

    conn.rollback();
    conn2.rollback();
  }

  public void testWrongConsecutiveUpdates_42647() throws SQLException {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    conn.setTransactionIsolation(getIsolationLevel());
    stmt.execute("create schema TXN");
    String sql = " create table TXN.customer "
        + "(c_w_id integer not null, c_d_id integer not null, "
        + "c_id integer not null, "
        + "c_last varchar(16), c_first varchar(16), "
        + "c_balance decimal(12,2), c_delivery_cnt integer)"
        + " partition by column(c_w_id) redundancy 1"+getSuffix();

    stmt.execute(sql);
    stmt.execute("create index cust_idx1 on TXN.customer(c_w_id)");
    conn.commit();

    stmt.execute("insert into TXN.customer values"
        + "(1, 1, 2, 'bara', 'alan', -10.0, 0), "
        + "(2, 6, 13, 'para', 'bhalan', -10.0, 0), "
        + "(2, 5, 17, 'cara', 'jhalan', -10.0, 0)");
    conn.commit();

    stmt.execute("update TXN.customer set c_balance = c_balance - 40.6 "
        + "where c_w_id = 1");

    stmt.execute("update TXN.customer set c_balance = c_balance - 49.4 "
        + "where c_w_id = 1");

    conn.commit();

    stmt.execute("select * from TXN.customer where c_w_id = 1");

    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(-100.0, rs.getDouble(6));
    assertFalse(rs.next());
  }

  class ObserverForBug43152 extends GemFireXDQueryObserverAdapter {
    private boolean methodCalled;
    @Override
    public void callAtOldValueSameAsNewValueCheckInSM2IIOp() {
      methodCalled = true;
    }
    boolean wasMethodCalled() {
      return this.methodCalled;
    }
  }
  
  public void testTransactionsAndUniqueIndexes_43152() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    
    Connection nonTxconn = getConnection();
    nonTxconn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    Statement nonTxst = nonTxconn.createStatement();
    
    Connection conn2 = getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    Statement st2 = conn2.createStatement();
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "c3 int not null, primary key(c1), "
        + "constraint C3_Unique unique (c3)) replicate "+getSuffix());
    conn.commit();

    st.execute("insert into tran.t1 values(1, 1, 1)");
    
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    st.execute("update tran.t1 set c3 = 1, c2 = 4 where c1 = 1");

    st.execute("select * from tran.t1 where c3 = 1");
    
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(1, rs.getInt(1));
      assertEquals(4, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
    }
    assertEquals(1, cnt);
    
    nonTxst.execute("select * from tran.t1");
    
    ResultSet rsNonTx = nonTxst.getResultSet();
    int cntNonTx = 0;
    while(rsNonTx.next()) {
      cntNonTx++;
      assertEquals(1, rsNonTx.getInt(1));
      assertEquals(1, rsNonTx.getInt(2));
      assertEquals(1, rsNonTx.getInt(3));
    }
    assertEquals(1, cntNonTx);
    
    st2.execute("select * from tran.t1");
    
    ResultSet rsTx2 = st2.getResultSet();
    int cntTx2 = 0;
    while(rsTx2.next()) {
      cntTx2++;
      assertEquals(1, rsTx2.getInt(1));
      assertEquals(1, rsTx2.getInt(2));
      assertEquals(1, rsTx2.getInt(3));
    }
    assertEquals(1, cntTx2);
    
    st2.execute("select * from tran.t1 where c3 = 1");
    
    rsTx2 = st2.getResultSet();
    cntTx2 = 0;
    while(rsTx2.next()) {
      cntTx2++;
      assertEquals(1, rsTx2.getInt(1));
      assertEquals(1, rsTx2.getInt(2));
      assertEquals(1, rsTx2.getInt(3));
    }
    assertEquals(1, cntTx2);
    
    try {
      st2.execute("update tran.t1 set c3 = 1, c2 = 5 where c1 = 1");
      fail("the above update should have thrown conflict exception");
    }
    catch(SQLException sqle) {
      assertTrue("X0Z02".equals(sqle.getSQLState()));
    }
    
    Connection conn3 = getConnection();
    conn3.setTransactionIsolation(getIsolationLevel());
    Statement st3 = conn3.createStatement();
    
    try {
      st3.execute("delete from tran.t1");
      fail("the above delete should have thrown conflict exception");
    }
    catch(SQLException sqle) {
      assertTrue("X0Z02".equals(sqle.getSQLState()));
    }
    
    Connection conn4 = getConnection();
    conn4.setTransactionIsolation(getIsolationLevel());
    Statement st4 = conn4.createStatement();
    
    try {
      st4.execute("delete from tran.t1 where c1 = 1");
      fail("the above delete should have thrown conflict exception");
    }
    catch(SQLException sqle) {
      assertTrue("X0Z02".equals(sqle.getSQLState()));
    }
    
    st.execute("update tran.t1 set c3 = 1, c2 = 5 where c1 = 1");
    
    st.execute("select * from tran.t1");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(1, rs.getInt(1));
      assertEquals(5, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
    }
    assertEquals(1, cnt);
    
    nonTxst.execute("select * from tran.t1");
    
    rsNonTx = nonTxst.getResultSet();
    cntNonTx = 0;
    while(rsNonTx.next()) {
      cntNonTx++;
      assertEquals(1, rsNonTx.getInt(1));
      assertEquals(1, rsNonTx.getInt(2));
      assertEquals(1, rsNonTx.getInt(3));
    }
    assertEquals(1, cntNonTx);
    
    conn.commit();
    
    nonTxst.execute("select * from tran.t1");
    
    rsNonTx = nonTxst.getResultSet();
    cntNonTx = 0;
    while(rsNonTx.next()) {
      cntNonTx++;
      assertEquals(1, rsNonTx.getInt(1));
      assertEquals(5, rsNonTx.getInt(2));
      assertEquals(1, rsNonTx.getInt(3));
    }
    assertEquals(1, cntNonTx);
    
    ObserverForBug43152 observer = new ObserverForBug43152();
    GemFireXDQueryObserverHolder.putInstance(observer);
    conn.commit();
    assertFalse(observer.wasMethodCalled());
  }

  public void testPkNonPkUpdates() throws Exception {
    Connection conn = getConnection();
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(getIsolationLevel());
    Statement stmt = conn.createStatement();
    // create table with primary key
    stmt.execute("create table t1 (id int primary key, addr varchar(20))"+getSuffix());
    // create table without primary key
    stmt.execute("create table t2 (id int, addr varchar(20))"+getSuffix());

    // some inserts
    PreparedStatement pstmt = conn
        .prepareStatement("insert into t1 values(?, ?)");
    for (int i = 0; i < 1000; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "addr" + i);
      assertEquals(1, pstmt.executeUpdate());
    }
    pstmt = conn.prepareStatement("insert into t2 values(?, ?)");
    for (int i = 0; i < 1000; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "addr" + i);
      assertEquals(1, pstmt.executeUpdate());
    }
    conn.commit();

    // now transactional updates
    pstmt = conn.prepareStatement("update t1 set addr = ? where id = ?");
    for (int i = 100; i < 200; i++) {
      pstmt.setString(1, "address" + i);
      pstmt.setInt(2, i);
      assertEquals(1, pstmt.executeUpdate());
    }
    pstmt = conn.prepareStatement("update t2 set addr = ? where id = ?");
    for (int i = 100; i < 200; i++) {
      pstmt.setString(1, "address" + i);
      pstmt.setInt(2, i);
      assertEquals(1, pstmt.executeUpdate());
    }

    ResultSet rs;
    int id;
    String addr;
    TIntHashSet ids = new TIntHashSet(100);
    // check transactional data
    ids.clear();
    rs = stmt.executeQuery("select * from t1 where id >= 100 and id < 200");
    while (rs.next()) {
      id = rs.getInt(1);
      addr = rs.getString(2);
      assertFalse("unexpected duplicate id=" + id, ids.contains(id));
      assertEquals("address" + id, addr);
      ids.add(id);
    }
    assertEquals(100, ids.size());

    ids.clear();
    rs = stmt.executeQuery("select * from t2 where id >= 100 and id < 200");
    while (rs.next()) {
      id = rs.getInt(1);
      addr = rs.getString(2);
      assertFalse("unexpected duplicate id=" + id, ids.contains(id));
      assertEquals("address" + id, addr);
      ids.add(id);
    }
    assertEquals(100, ids.size());
    conn.commit();

    // now after commit
    rs = stmt.executeQuery("select * from t1 where id >= 100 and id < 200");
    ids.clear();
    while (rs.next()) {
      id = rs.getInt(1);
      addr = rs.getString(2);
      assertFalse("unexpected duplicate id=" + id, ids.contains(id));
      assertEquals("address" + id, addr);
      ids.add(id);
    }
    assertEquals(100, ids.size());

    ids.clear();
    rs = stmt.executeQuery("select * from t2 where id >= 100 and id < 200");
    while (rs.next()) {
      id = rs.getInt(1);
      addr = rs.getString(2);
      assertFalse("unexpected duplicate id=" + id, ids.contains(id));
      assertEquals("address" + id, addr);
      ids.add(id);
    }
    assertEquals(100, ids.size());
  }

  public void testExceptionAtCommit() throws Exception {
    Connection conn = getConnection();
    conn.setAutoCommit(false);

    Statement stmt1 = conn.createStatement();

    stmt1.execute("create table BlogsStatsUser "
        + "( statsUserId bigint not null primary key,        "
        + "groupId bigint, companyId bigint,"
        + "userId bigint,  entryCount integer,     "
        + "ratingsTotalEntries integer)"+getSuffix());

    stmt1.execute("create index IX1"
        + " on BlogsStatsUser (companyId, entryCount)");
    
    stmt1.execute("create index IX2"
        + " on BlogsStatsUser (companyId, ratingsTotalEntries)");
    
    stmt1.execute("create unique index IX3 on BlogsStatsUser (groupId, userId)");
    
    stmt1.execute("create index IX4"
        + " on BlogsStatsUser (groupId, ratingsTotalEntries)");
    
    conn.setTransactionIsolation(getIsolationLevel());
   
    Statement stmt = conn.createStatement();

    stmt.execute("insert into BlogsStatsUser values"
        + "(1, 1, 1, 1, 1, 1)");

    stmt.execute("update BlogsStatsUser set groupId=1, companyId=10, "
        + "userId=1, entryCount=1, ratingsTotalEntries=7 where statsUserId=1");

//    stmt.execute("update BlogsStatsUser set groupId=2, companyId=1, "
//        + "userId=5, entryCount=1, ratingsTotalEntries=7 where statsUserId=1");
    
    conn.commit();
    
    stmt = conn.createStatement();
    
    stmt.execute("update BlogsStatsUser set "
        + "groupId=3, companyId=3, userId=7, entryCount=50, "
        + "ratingsTotalEntries=100 where statsUserId=1");
    conn.commit();
  }
  
  /**
   * Test for bug 44080
   * @throws Exception
   */
  public void testOverflowTableWithNoEviction() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c_w_id int not null, c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by (c_w_id) redundancy 1 " 
        + " eviction by lruheappercent evictaction overflow "
        + " synchronous;");
    conn.commit();
    conn = getConnection();

    conn.setTransactionIsolation(getIsolationLevel());
    st = conn.createStatement();
    st.execute("insert into t1 values (5, 10, 15)");

    conn.commit();
    
    st.execute("update t1 set c2 = 16 where c_w_id=5");

    conn.commit();

    ResultSet rs = st.executeQuery("Select * from t1");
    assertTrue(rs.next());
    assertEquals(5, rs.getInt("c_w_id"));
    assertEquals(10, rs.getInt("c1"));
    assertEquals(16, rs.getInt("c2"));
    assertFalse(rs.next());
  }
  
  /**
   * Test for bug 44080
   * @throws Exception
   */
  public void testSelectOnOverflowTableWithEviction() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c_w_id int not null, c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by (c_w_id) redundancy 1 " 
        + " eviction by lrucount 1 evictaction overflow "
        + " synchronous;");
    conn.commit();
    conn = getConnection();

    conn.setTransactionIsolation(getIsolationLevel());
    st = conn.createStatement();
    st.execute("insert into t1 values (5, 10, 15)");
    st.execute("insert into t1 values (5, 11, 16)");

    conn.commit();
    
    st.execute("update t1 set c2 = 17 where c_w_id=5");
    
    conn.commit();

    ResultSet rs = st.executeQuery("Select * from t1 order by c1");
    assertTrue(rs.next());
    assertEquals(5, rs.getInt("c_w_id"));
    assertEquals(10, rs.getInt("c1"));
    assertEquals(17, rs.getInt("c2"));
    assertTrue(rs.next());
    assertEquals(5, rs.getInt("c_w_id"));
    assertEquals(11, rs.getInt("c1"));
    assertEquals(17, rs.getInt("c2"));
    assertFalse(rs.next());
  }

  public void testMultipleInsertFromThinClient_bug44242() throws Exception {
    setupConnection();
    int port = startNetserverAndReturnPort("create schema emp");
    for (int i = 0; i < 2; i++) {
      Connection netConn1 = TestUtil.getNetConnection(port, null, null);
      
      Connection netConn2 = TestUtil.getNetConnection(port, null, null);

      Statement s = netConn1.createStatement();
      String ext = "";
      if (i == 1) {
        ext = "replicate";
      }
      s.execute("create table emp.EMPLOYEE_parent(lastname varchar(30) "
          + "primary key, depId int)" + ext +getSuffix());
      s.execute("create table emp.EMPLOYEE(lastname varchar(30) primary key, "
          + "depId int, foreign key(lastname) references "
          + "emp.EMPLOYEE_parent(lastname) on delete restrict)" + ext+getSuffix());
      s.execute("insert into emp.EMPLOYEE_parent values('Jones', 10), "
          + "('Rafferty', 50), ('Robinson', 100)");

      netConn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      netConn2.setAutoCommit(false);
      Statement s2 = netConn2.createStatement();
      s2.execute("delete from emp.EMPLOYEE_parent");
      s2.execute("select * from emp.employee_parent");
      netConn1.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      netConn1.setAutoCommit(false);
      PreparedStatement pstmnt = netConn1
          .prepareStatement("INSERT INTO emp.employee VALUES (?, ?)");

      pstmnt.setString(1, "Jones");
      pstmnt.setInt(2, 33);
      pstmnt.addBatch();

      pstmnt.setString(1, "Rafferty");
      pstmnt.setInt(2, 31);
      pstmnt.addBatch();

      pstmnt.setString(1, "Robinson");
      pstmnt.setInt(2, 34);
      pstmnt.addBatch();

      try {
        pstmnt.executeBatch();
        netConn1.commit();
        fail("commit should have failed");
      } catch (SQLException e) {
        assertEquals("X0Z02", e.getSQLState());
      }
      netConn2.commit();

      s.execute("drop table emp.employee");
      this.waitTillAllClear();
      s.execute("drop table emp.employee_parent");
      this.waitTillAllClear();
    }
  }

  public void testBug44312() throws Exception {
    setupConnection();
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // create the table and load data
    stmt.execute("create table trade.customers (cid int primary key, "
        + "cust_name varchar(100) not null, tid int not null)"+getSuffix());
    for (int id = 1; id <= 40; id++) {
      stmt.execute("insert into trade.customers values (" + id + ", 'cust" + id
          + "', " + (id * 2) + ')');
    }

    // fire deletes that do not qualify the row at the top-level
    conn.setTransactionIsolation(getIsolationLevel());
    for (int id = 10; id <= 20; id++) {
      int updated = stmt.executeUpdate("delete from trade.customers where cid="
          + id + " and tid <=" + id);
      assertEquals(0, updated);
    }
    // now try a delete on those rows and it should not throw a conflict
    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    Statement stmt2 = conn2.createStatement();
    for (int id = 5; id <= 15; id++) {
      assertEquals(1,
          stmt2.executeUpdate("delete from trade.customers where cid=" + id));
    }
    conn2.commit();
    for (int id = 5; id <= 15; id++) {
      stmt.execute("insert into trade.customers values (" + id + ", 'cust" + id
          + "', " + (id * 2) + ')');
    }
    conn.commit();

    // now the same with updates
    for (int id = 10; id <= 20; id++) {
      int updated = stmt
          .executeUpdate("update trade.customers set cust_name='customer" + id
              + "' where cid=" + id + " and tid <=" + id);
      assertEquals(0, updated);
    }

    // now try a delete on those rows and it should not throw a conflict
    for (int id = 5; id <= 15; id++) {
      assertEquals(1,
          stmt2.executeUpdate("delete from trade.customers where cid=" + id));
    }

    conn2.commit();
    conn.commit();

    // verify the final results
    ResultSet rs;
    for (int id = 1; id <= 40; id++) {
      rs = stmt.executeQuery("select cust_name, tid from trade.customers "
          + "where cid=" + id);
      if (id >= 5 && id <= 15) {
        assertFalse(rs.next());
      }
      else {
        assertTrue(rs.next());
        assertEquals("cust" + id, rs.getString(1));
        assertEquals(id * 2, rs.getInt(2));
        assertFalse(rs.next());
      }
    }
  }
  
  public void test45938() throws SQLException {
    Properties cp = new Properties();
    cp.setProperty("host-data", "true");
    cp.setProperty("mcast-port", String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    
    Connection conn = TestUtil.getConnection(cp);
    
    Statement stmt = conn.createStatement();
    
    stmt.execute("create table customer (oid int, cid int, sid int, tid int, primary key(oid)) partition by column(cid, sid)");
    ResultSet rs = stmt.executeQuery("select count(*) from customer");
    rs.next();
    assertEquals(0, rs.getInt(1));
    rs.close();
    
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    PreparedStatement ps = conn.prepareStatement("insert into customer values (?, ?, ?, ?)");
    ps.setInt(1, 1);
    ps.setInt(2, 1);
    ps.setInt(3, 1);
    ps.setInt(4, 1);
    ps.execute();

    PreparedStatement dps = conn.prepareStatement("delete from customer where sid = ? and tid = ?");
    dps.setInt(1, 1);
    dps.setInt(2, 1);
    dps.execute();
    conn.commit();
    
    rs = stmt.executeQuery("select count(*) from customer");
    rs.next();
    assertEquals(0, rs.getInt(1));
    Region r = Misc.getRegionForTable(
        Misc.getSchemaName(null,
            ((EmbedConnection)conn).getLanguageConnection())
            + ".customer".toUpperCase(), true);
    assertEquals(0, r.size());
    rs.close();
  }

  public void testBug41168() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    conn.setTransactionIsolation(getIsolationLevel());

    // Create the table with self-reference FKs
    stmt.execute("create table BinaryTree (id int primary key, "
        + "leftId int, rightId int, depth int not null,"
        + " foreign key (leftId) references BinaryTree(id),"
        + " foreign key (rightId) references BinaryTree(id)) replicate"+getSuffix());

    addExpectedException(SQLIntegrityConstraintViolationException.class);
    doBinaryTreeChecks(conn, true);
    removeExpectedException(SQLIntegrityConstraintViolationException.class);

    // now do the same for partitioned table
    stmt.execute("drop table BinaryTree");
    this.waitTillAllClear();
    stmt.execute("create table BinaryTree (id int primary key, "
        + "leftId int, rightId int, depth int not null,"
        + " foreign key (leftId) references BinaryTree(id),"
        + " foreign key (rightId) references BinaryTree(id))"+getSuffix());

    addExpectedException(SQLIntegrityConstraintViolationException.class);
    doBinaryTreeChecks(conn, true);
    removeExpectedException(SQLIntegrityConstraintViolationException.class);
  }

  // for testing purpose
  public void Debug_tests_43222() throws SQLException {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    conn.setTransactionIsolation(getIsolationLevel());
    stmt.execute("create table warehouse (    w_id        integer   not null,"
        + "    w_ytd       decimal(12,2),"
        + "    w_tax       decimal(4,4),"
        + "    w_name      varchar(10)) replicate"+getSuffix());

    stmt.execute("alter table warehouse add constraint pk_warehouse "
        + "primary key (w_id)");

    stmt.execute("insert into warehouse values(1, 0.0, 0.0, 'name1'), "
        + "(2, 0.2, 0.0, 'name2')");
    conn.commit();

    int i = stmt.executeUpdate("UPDATE warehouse SET w_ytd = w_ytd + 4998.73 "
        + "WHERE w_id = 1");
    System.out.println(i);
    SQLNonTransientConnectionException ex = new SQLNonTransientConnectionException();
    System.out.println("ex.getSQLState returned: " + ex.getSQLState());
  }

  public static void doBinaryTreeChecks(final Connection conn,
      final boolean doSelfJoins) throws Exception {
    Statement stmt = conn.createStatement();
    conn.commit();

    // some inserts with null values
    stmt.execute("insert into BinaryTree values (3, null, null, 2)");
    stmt.execute("insert into BinaryTree values (2, null, null, 1)");
    stmt.execute("insert into BinaryTree values (1, 3, null, 1)");
    stmt.execute("insert into BinaryTree values (0, 1, 2, 0)");
    // TODO: SW: these commits should not be required once #43170 is fixed
    conn.commit();

    // test FK violation for the self FK
    checkFKViolation(stmt, "insert into BinaryTree values (4, 5, null, 2)");
    checkFKViolation(stmt, "insert into BinaryTree values (5, null, 4, 2)");
    checkFKViolation(stmt, "update BinaryTree set leftId=4 where id=2");
    checkFKViolation(stmt, "update BinaryTree set rightId=5 where id=2");

    // some inserts with inserted row being the one that satisfies the
    // constraint (#43159)    
    stmt.execute("insert into BinaryTree values (10, 10, 10, 3)");
    stmt.execute("insert into BinaryTree values (11, 10, 11, 4)");
    stmt.execute("insert into BinaryTree values (12, 11, 12, 3)");
    stmt.execute("insert into BinaryTree values (13, 13, 3, 4)");

    // now update the tree with two proper nodes
    stmt.execute("insert into BinaryTree values (4, null, null, 2)");
    stmt.execute("insert into BinaryTree values (5, null, null, 2)");
    stmt.execute("update BinaryTree set leftId=4 where id=2");
    stmt.execute("update BinaryTree set rightId=5 where id=2");

    // run a self-join query and verify the results
    ResultSet rs;
    if (doSelfJoins) {
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 3");
      assertTrue("expected one result", rs.next());
      assertEquals(1, rs.getInt(1));
      assertFalse("expected one result", rs.next());
      rs.close();
    }
    conn.commit();

    // finally check that delete works for null FK column rows as well and
    // verify the results again
    checkFKViolation(stmt, "delete from BinaryTree where id=4");
    checkFKViolation(stmt, "delete from BinaryTree where id=5");

    // check the deletes of "self-referential" rows
    stmt.execute("delete from BinaryTree where id=13");
    checkFKViolation(stmt, "delete from BinaryTree where id=11");
    stmt.execute("delete from BinaryTree where id=12");
    checkFKViolation(stmt, "delete from BinaryTree where id=10");

    try {
      stmt.execute("update BinaryTree set depth=null where id=2");
    } catch (SQLException sqle) {
      assertEquals(sqle.toString(), "23502", sqle.getSQLState());
    }
    stmt.execute("update BinaryTree set leftId=null where id=2");
    stmt.execute("update BinaryTree set rightId=null where id=2");
    stmt.execute("delete from BinaryTree where id=5");
    stmt.execute("delete from BinaryTree where id=4");
    if (doSelfJoins) {
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 4");
      assertFalse("expected no result", rs.next());
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 5");
      assertFalse("expected no result", rs.next());
      rs.close();
    }
    conn.commit();
  }

  public static void checkFKViolation(Statement stmt, String sql)
      throws SQLException {
    try {
      stmt.execute(sql);
      fail("expected FK violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
    
  }

  protected void checkConnCloseExceptionForReadsOnly(Connection conn)
      throws SQLException {
  }

  private void checkConflictsWithDifferentScanTypes(Statement stmt, int cid,
      ScanTypeQueryObserver observer) throws SQLException {

    // now a delete on customers should throw conflict rather than constraint
    // violation
    try {
      stmt.execute("delete from trade.customers where cid = " + cid);
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    observer.addExpectedScanType("trade.customers", ScanType.HASH1INDEX);
    observer.checkAndClear();

    // also try non-PK based
    try {
      stmt.execute("delete from trade.customers where cid >= 10 and cid < 15");
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    observer.addExpectedScanType("trade.customers", ScanType.TABLE);
    observer.checkAndClear();

    try {
      stmt.execute("delete from trade.customers where tid >= 10 and tid < 20");
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    observer.addExpectedScanType("trade.customers", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
  }
  
  
  public void waitTillAllClear() {}
  
  protected String getSuffix() {
    return " ";
  }
}
