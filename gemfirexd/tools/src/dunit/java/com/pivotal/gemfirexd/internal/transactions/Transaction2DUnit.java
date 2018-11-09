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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXEntryState;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.TransactionObserver;
import com.gemstone.gemfire.internal.cache.TransactionObserverAdapter;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.transactions.TransactionDUnit;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

@SuppressWarnings("MagicConstant")
public class Transaction2DUnit extends DistributedSQLTestBase {

  @SuppressWarnings("WeakerAccess")
  public Transaction2DUnit(String name) {
    super(name);
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  protected String getSuffix() {
    return "";
  }

  public void testBug41694() throws Exception {
    startServerVMs(2, 0, "sg1");
    startClientVMs(1, 0, null);
    Connection conn = TestUtil.jdbcConn;
    Statement st = conn.createStatement();
    st.execute("create schema test default server groups (sg1, sg2)");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null, "
        + "col3 int, col4 int, col5 varchar(10),col6 int, col7 int, col8 int, "
        + "col9 int, col10 int, col11 int, col12 int, col13 int, col14 int, "
        + "col15 int, col16 int, col17 int, col18 int, col19 int, col20 int, "
        + "col21 int,col22 int, col23 int, col24 int, col25 int, col26 int, "
        + "col27 int, col28 int, col29 int, col30 int, col31 int, col32 int,"
        + " col33 int, col34 int, col35 int, col36 int, col37 int, col38 int, "
        + "col39 int, col40 int, col41 int, col42 int, col43 int, col44 int, "
        + "col45 int, col46 int, col47 int, col48 int, col49 int, col50 int, "
        + "col51 int, col52 int, col53 int, col54 int, col55 int, col56 int, "
        + "col57 int, col58 int, col59 int, col60 int, col61 int, col62 int, "
        + "col63 int, col64 int, col65 int, col66 int, col67 int, col68 int, "
        + "col69 int, col70 int, col71 int, col72 int, col73 int, col74 int, "
        + "col75 int, col76 int, col77 int, col78 int, col79 int, col80 int, "
        + "col81 int, col82 int, col83 int, col84 int, col85 int, col86 int, "
        + "col87 int, col88 int, col89 int, col90 int, col91 int, col92 int, "
        + "col93 int, col94 int, col95 int, col96 int, col97 int, col98 int, "
        + "col99 int, col100 int, Primary Key (PkCol1) ) "
        + "Partition by Primary Key server groups (sg1) redundancy 1" + getSuffix());
    conn.commit();
    st.execute("create index IndexCol4 on test.t1 (col4)");
    conn.commit();

    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    final int numRows = 1;
    PreparedStatement psInsert = conn.prepareStatement("insert into test.t1 "
        + "values(?, 1000, 1000, 1000, 'XXXX1'"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000 "
        + " , 1000, 1000, 1000, 1000, 1000 "
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000"
        + " , 1000, 1000, 1000, 1000, 1000 )");
    // st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    for (int i = 0; i < numRows; i++) {
      psInsert.setInt(1, i);
      psInsert.executeUpdate();
      conn.commit();
    }

    PreparedStatement psUpdate = conn.prepareStatement("update test.t1 set "
        + "col3 = 20 where PkCol1=?");
    // st.execute("update test.t1 set col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=10");
    for (int i = 0; i < 1000; i++) {
      // Update the same row over and over should not cause #41694,
      // negative bucket size(memory consumed by bucket).
      psUpdate.setInt(1, 0);
      psUpdate.executeUpdate();
      conn.commit();
    }

    st.close();
    conn.commit();
  }

  public void testBug41873_1() throws Exception {
    // Create the controller VM as client which belongs to default server group
    startClientVMs(1, 0, null);
    startServerVMs(2, -1, "SG1");
    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    // create table
    clientSQLExecute(1, "Create table t1 (c1 int not null , c2 int not null, "
        + "c3 int not null, c4 int not null) redundancy 1 "
        + "partition by column (c1) " + getSuffix());
    conn.commit();
    Statement st = conn.createStatement();
    st.execute("insert into t1 values (1, 1,1,1)");
    st.execute("insert into t1 values (114, 114,114,114)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c1 =1");
    st.execute("update t1 set c3 =3 where c1 =1");
    st.execute("update t1 set c4 =4 where c1 =1");
    st.execute("update t1 set c2 =3 where c1 = 114");
    st.execute("update t1 set c3 =4 where c1 =114");
    st.execute("update t1 set c4 =5 where c1 =114");
    conn.commit();
    ResultSet rs = st.executeQuery("Select * from t1 where c1 = 1");
    rs.next();
    assertEquals(1, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertEquals(3, rs.getInt(3));
    assertEquals(4, rs.getInt(4));

    rs = st.executeQuery("Select * from t1 where c1 = 114");
    rs.next();
    assertEquals(114, rs.getInt(1));
    assertEquals(3, rs.getInt(2));
    assertEquals(4, rs.getInt(3));
    assertEquals(5, rs.getInt(4));
    conn.commit();
  }

  public void testBug42067_1() throws Exception {
    // Create the controller VM as client which belongs to default server group
    startClientVMs(1, 0, null);
    startServerVMs(2, -1, "SG1");
    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    // create table
    clientSQLExecute(1, "Create table t1 (c1 int not null, "
        + "c2 int not null, c3 int not null, c4 int not null) "
        + "redundancy 1 partition by column (c1) " + getSuffix());
    conn.commit();
    Statement st = conn.createStatement();
    st.execute("insert into t1 values (1, 1,1,1)");
    st.execute("insert into t1 values (114, 114,114,114)");
    conn.commit();
    st.execute("delete from t1 where c1 =1 and c3 =1");
    st.execute("update t1 set c2 =2 where c1 =1 and c3 =1");
    conn.commit();
  }

  public void testBug42067_2() throws Exception {
    // Create the controller VM as client which belongs to default server group
    startClientVMs(1, 0, null);
    startServerVMs(2, -1, "SG1");
    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    // create table
    clientSQLExecute(1, "Create table t1 (c1 int not null primary key, "
        + "c2 int not null, c3 int not null, c4 int not null) "
        + "redundancy 1 partition by column (c1) " + getSuffix());
    conn.commit();
    Statement st = conn.createStatement();
    st.execute("insert into t1 values (1, 1,1,1)");
    st.execute("insert into t1 values (114, 114,114,114)");
    conn.commit();
    st.execute("delete from t1 where c1 =1 and c3 =1");
    st.execute("update t1 set c2 =2 where c1 =1 and c3 =1");
    conn.commit();
    ResultSet rs = st.executeQuery("select * from t1");
    assertTrue(rs.next());
    assertEquals(114, rs.getInt(1));
    assertFalse(rs.next());
  }

  public void testBug41970_43473() throws Throwable {
    startVMs(1, 1);
    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("create table customers (cid int not null, cust_name "
        + "varchar(100),  addr varchar(100), tid int, primary key (cid))");
    st.execute("create table trades (tid int, cid int, eid int, primary Key "
        + "(tid), foreign key (cid) references customers (cid))" + getSuffix());
    PreparedStatement pstmt = conn
        .prepareStatement("insert into customers values(?,?,?,?)");
    pstmt.setInt(1, 1);
    pstmt.setString(2, "name1");
    pstmt.setString(3, "add1");
    pstmt.setInt(4, 1);
    pstmt.executeUpdate();
    pstmt.setInt(1, 2);
    pstmt.setString(2, "name2");
    pstmt.setString(3, "add2");
    pstmt.setInt(4, 1);
    pstmt.executeUpdate();
    conn.commit();

    ResultSet rs = st.executeQuery("Select * from customers");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed.
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    rs.close();
    conn.commit();

    // test for #43473
    st.execute("create table sellorders (oid int not null primary key, "
        + "cid int, order_time timestamp, status varchar(10), "
        + "constraint ch check (status in ('cancelled', 'open', 'filled')))" + getSuffix());
    pstmt = conn.prepareStatement("insert into sellorders values (?, ?, ?, ?)");
    final long currentTime = System.currentTimeMillis();
    final Timestamp ts = new Timestamp(currentTime - 100);
    final Timestamp now = new Timestamp(currentTime);
    for (int id = 1; id <= 100; id++) {
      pstmt.setInt(1, id);
      pstmt.setInt(2, id * 2);
      pstmt.setTimestamp(3, ts);
      pstmt.setString(4, "open");
      pstmt.execute();
    }
    conn.commit();

    final CyclicBarrier barrier = new CyclicBarrier(2);
    final Throwable[] failure = new Throwable[1];
    Thread t = new Thread(() -> {
      try {
        Connection conn2 = TestUtil.getConnection();
        conn2.setTransactionIsolation(getIsolationLevel());
        conn2.setAutoCommit(false);
        PreparedStatement pstmt2 = conn2
            .prepareStatement("update sellorders set cid = ? where oid = ?");
        pstmt2.setInt(1, 7);
        pstmt2.setInt(2, 3);
        assertEquals(1, pstmt2.executeUpdate());
        pstmt2.setInt(1, 3);
        pstmt2.setInt(2, 1);
        assertEquals(1, pstmt2.executeUpdate());

        // use a barrier to force txn1 to wait after first EX lock upgrade
        // and txn2 to wait before EX_SH lock acquisition
        getServerVM(1).invoke(Transaction2DUnit.class, "installObservers");
        barrier.await();
        conn2.commit();
      } catch (Throwable t1) {
        failure[0] = t1;
      }
    });
    t.start();

    pstmt = conn.prepareStatement("update sellorders "
        + "set status = 'cancelled' where order_time < ? and status = 'open'");
    pstmt.setTimestamp(1, now);
    barrier.await();
    try {
      pstmt.executeUpdate();
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    conn.close();

    t.join();

    if (failure[0] != null) {
      throw failure[0];
    }

    // clear the observers
    serverExecute(1, new SerializableRunnable() {
      @Override
      public void run() {
        GemFireCacheImpl.getExisting().getTxManager().setObserver(null);
        GemFireXDQueryObserverHolder.clearInstance();
      }
    });
  }

  public void testBug42031IsolationAndTXData() throws Exception {
    // Create the controller VM as client which belongs to default server group
    startClientVMs(1, 0, null);
    startServerVMs(1, -1, "SG1");
    // create table
    clientSQLExecute(1, "create table TESTTABLE (ID int not null primary key, "
        + "DESCRIPTION varchar(1024), ADDRESS varchar(1024), ID1 int)" + getSuffix());

    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();
    // Do an insert in sql fabric. This will create a primary bucket on the lone
    // server VM
    // with bucket ID =1
    stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");

    stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
    stmt.executeUpdate("Insert into TESTTABLE values(227,'desc227','Add227',227)");
    stmt.executeUpdate("Insert into TESTTABLE values(340,'desc340','Add340',340)");
    conn.rollback();
    stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");
    stmt.executeUpdate("Insert into TESTTABLE values(2,'desc1','Add1',1)");
    stmt.executeUpdate("Insert into TESTTABLE values(224,'desc227','Add227',227)");
    stmt.executeUpdate("Insert into TESTTABLE values(331,'desc340','Add340',340)");
    conn.commit();
    // Bulk Update
    stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
    ResultSet rs = stmt.executeQuery("select ID1 from  TESTTABLE");
    Set<Integer> expected = new HashSet<>();
    expected.add(1);
    expected.add(227);
    expected.add(340);
    expected.add(114);
    Set<Integer> expected2 = new HashSet<>();
    expected2.add(2);
    expected2.add(228);
    expected2.add(341);
    expected2.add(115);

    int numRows = 0;
    while (rs.next()) {
      int got = rs.getInt(1);
      assertTrue(expected2.contains(got));
      ++numRows;
    }
    assertEquals(expected2.size(), numRows);

    // rollback and check original values
    conn.rollback();

    rs = stmt.executeQuery("select ID1 from TESTTABLE");
    numRows = 0;
    while (rs.next()) {
      int got = rs.getInt(1);
      assertTrue(expected.contains(got));
      ++numRows;
    }
    assertEquals(expected.size(), numRows);

    // now commit and check success
    stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
    rs = stmt.executeQuery("select ID1 from TESTTABLE");
    numRows = 0;
    while (rs.next()) {
      int got = rs.getInt(1);
      assertTrue(expected2.contains(got));
      ++numRows;
    }
    assertEquals(expected2.size(), numRows);

    conn.commit();

    rs = stmt.executeQuery("select ID1 from TESTTABLE");
    numRows = 0;
    while (rs.next()) {
      int got = rs.getInt(1);
      assertTrue(expected2.contains(got));
      ++numRows;
    }
    assertEquals(expected2.size(), numRows);
  }

  public void testIndexMaintenanceOnPrimaryAndSecondary() throws Exception {
    startServerVMs(2, 0, "sg1");
    startClientVMs(1, 0, null);
    Properties props = new Properties();
    props.setProperty(Attribute.TX_SYNC_COMMITS, "true");
    final Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema test default server groups (sg1, sg2)");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int "
        + "not null , col3 int, col4 int, col5 varchar(10), Primary Key(PkCol1)"
        + ") Partition by Primary Key server groups (sg1) redundancy 1" + getSuffix());
    conn.commit();
    st.execute("create index IndexCol4 on test.t1 (col4)");
    conn.commit();

    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    final int numRows = 10;
    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(TransactionDUnit.class, "installIndexObserver",
        new Object[]{"test.IndexCol4", null});
    server2.invoke(TransactionDUnit.class, "installIndexObserver",
        new Object[]{"test.IndexCol4", null});
    PreparedStatement psInsert = conn.prepareStatement("insert into test.t1 "
        + "values(?, 10, 10, 10, 'XXXX1')");
    for (int i = 0; i < numRows; i++) {
      psInsert.setInt(1, i);
      psInsert.executeUpdate();
      conn.commit();
    }

    server1.invoke(TransactionDUnit.class, "checkIndexAndReset",
        new Object[]{Integer.valueOf(numRows), Integer.valueOf(0)});
    server2.invoke(TransactionDUnit.class, "checkIndexAndReset",
        new Object[]{Integer.valueOf(numRows), Integer.valueOf(0)});

    PreparedStatement psUpdate = conn.prepareStatement("update test.t1 set "
        + "col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=?");
    for (int i = 0; i < numRows; i++) {
      psUpdate.setInt(1, i);
      psUpdate.executeUpdate();
      conn.commit();
    }

    server1.invoke(TransactionDUnit.class, "checkIndexAndReset", new Object[]{
        Integer.valueOf(numRows * 2), Integer.valueOf(numRows)});
    server2.invoke(TransactionDUnit.class, "checkIndexAndReset", new Object[]{
        Integer.valueOf(numRows * 2), Integer.valueOf(numRows)});

    server1.invoke(TransactionDUnit.class, "resetIndexObserver");
    server2.invoke(TransactionDUnit.class, "resetIndexObserver");

    st.close();
    conn.close();
  }

  public void testNonColocatedInsertByPartitioning() throws Exception {
    startServerVMs(1, 0, "sg1");
    startServerVMs(1, 0, "sg2");
    startClientVMs(1, 0, null);
    // TestUtil.loadDriver();

    Connection conn = TestUtil.jdbcConn;
    System.out.println("XXXX the type of conneciton :  " + conn);
    Statement st = conn.createStatement();
    st.execute("create schema test default server groups (sg1, sg2)");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , "
        + "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1, PkCol2) ) "
        + "Partition by column (PkCol1) server groups (sg1)" + getSuffix());

    st.execute("create table test.t2 (PkCol1 int not null, PkCol2 int not null, "
        + " col3 int, col4 varchar(10)) Partition by column (PkCol1)"
        + " server groups (sg2)" + getSuffix());
    conn.commit();
    // conn.setTransactionIsolation(getIsolationLevel());
    st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    st.execute("insert into test.t2 values(10, 10, 10, 'XXXX1')");
    conn.commit();
  }

  /**
   * Test updates on tables partitioned by PK.
   */
  public void testTransactionalKeyBasedUpdatePartitionedByPk() throws Exception {
    startServerVMs(2, 0, "sg1");
    startClientVMs(1, 0, null);
    Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("create schema test default server groups (sg1, sg2)");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , "
        + "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1) ) "
        + "Partition by Primary Key server groups (sg1) redundancy 1" + getSuffix());

    conn.setTransactionIsolation(getIsolationLevel());
    PreparedStatement psInsert = conn.prepareStatement("insert into test.t1 "
        + "values(?, 10, 10, 10, 'XXXX1')");
    // st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    for (int i = 0; i < 1000; i++) {
      psInsert.setInt(1, i);
      psInsert.executeUpdate();
      conn.commit();
    }
    ResultSet rs = st.executeQuery("select * from test.t1");
    int numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should be 10", 10, rs.getInt(3));
      assertEquals("Column value should be 10", 10, rs.getInt(4));
      assertEquals("Column value should be XXXX1", "XXXX1", rs.getString(5)
          .trim());
      numRows++;
    }
    assertEquals("Numbers of rows in resultset should be one", 1000, numRows);

    PreparedStatement psUpdate = conn.prepareStatement("update test.t1 set "
        + "col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=?");
    // st.execute("update test.t1 set col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=10");
    for (int i = 0; i < 1000; i++) {
      psUpdate.setInt(1, i);
      psUpdate.executeUpdate();
      conn.commit();
    }

    rs = st.executeQuery("select * from test.t1");
    numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should change", 20, rs.getInt(3));
      assertEquals("Columns value should change", 20, rs.getInt(4));
      assertEquals("Columns value should change", "changed", rs.getString(5)
          .trim());
      numRows++;
    }
    assertEquals("Numbers of rows in resultset should be one", 1000, numRows);
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  /**
   * Test transactional key based updates.
   */
  public void testTransactionalKeyBasedUpdates() throws Exception {
    startServerVMs(2, 0, "sg1");
    startClientVMs(1, 0, null);
    Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    System.out.println("XXXX the type of conneciton :  " + conn);
    Statement st = conn.createStatement();
    st.execute("create schema test default server groups (sg1, sg2)");
    st.execute("create table test.t1 ( PkCol1 int not null, PkCol2 int not null , "
        + "col3 int, col4 int, col5 varchar(10), Primary Key (PkCol1) ) "
        + "Partition by column (PkCol1) server groups (sg1)" + getSuffix());
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    PreparedStatement psInsert = conn.prepareStatement("insert into test.t1 "
        + "values(?, 10, 10, 10, 'XXXX1')");
    // st.execute("insert into test.t1 values(10, 10, 10, 10, 'XXXX1')");
    for (int i = 0; i < 1000; i++) {
      psInsert.setInt(1, i);
      psInsert.executeUpdate();
      conn.commit();
    }
    // conn.commit();
    ResultSet rs = st.executeQuery("select * from test.t1");
    int numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should be 10", 10, rs.getInt(3));
      assertEquals("Column value should be 10", 10, rs.getInt(4));
      assertEquals("Column value should be XXXX1", "XXXX1", rs.getString(5)
          .trim());
      numRows++;
    }
    assertEquals("Numbers of rows in resultset should be one", 1000, numRows);
    // conn.commit();
    PreparedStatement psUpdate = conn.prepareStatement("update test.t1 set "
        + "col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=?");
    // st.execute("update test.t1 set col3 = 20, col4 = 20, col5 = 'changed' where PkCol1=10");
    for (int i = 0; i < 1000; i++) {
      psUpdate.setInt(1, i);
      psUpdate.executeUpdate();
      conn.commit();
    }
    // conn.commit();
    rs = st.executeQuery("select * from test.t1");
    numRows = 0;
    while (rs.next()) {
      assertEquals("Column value should change", 20, rs.getInt(3));
      assertEquals("Columns value should change", 20, rs.getInt(4));
      assertEquals("Columns value should change", "changed", rs.getString(5)
          .trim());
      numRows++;
    }
    assertEquals("Numbers of rows in resultset should be one", 1000, numRows);
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  @SuppressWarnings("unused")
  public static void installObservers() {
    final CyclicBarrier testBarrier = new CyclicBarrier(2);
    final ConcurrentHashMap<TXStateProxy, Boolean> waitDone =
        new ConcurrentHashMap<>(2);

    TransactionObserver txOb1 = new TransactionObserverAdapter() {
      boolean firstCall = true;

      @Override
      public void beforeIndividualLockUpgradeInCommit(TXStateProxy tx,
          TXEntryState entry) {
        if (this.firstCall) {
          this.firstCall = false;
          return;
        }
        if (waitDone.putIfAbsent(tx, Boolean.TRUE) == null) {
          SanityManager.DEBUG_PRINT("info:TEST",
              "TXObserver: waiting on testBarrier, count="
                  + testBarrier.getNumberWaiting());
          try {
            testBarrier.await();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }

      @Override
      public void afterIndividualRollback(TXStateProxy tx, Object callbackArg) {
        // release the barrier for the committing TX
        if (waitDone.putIfAbsent(tx, Boolean.TRUE) == null) {
          try {
            testBarrier.await();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    };

    GemFireXDQueryObserver ob2 = new GemFireXDQueryObserverAdapter() {
      @Override
      public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
          RegionEntry entry, boolean writeLock) {
        if (!writeLock
            && ExclusiveSharedSynchronizer.isExclusive(entry.getState())
            && waitDone.putIfAbsent(tx, Boolean.TRUE) == null) {
          SanityManager.DEBUG_PRINT("info:TEST",
              "GFXDObserver: waiting on testBarrier, count="
                  + testBarrier.getNumberWaiting());
          try {
            testBarrier.await();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    };

    final TXManagerImpl txMgr = GemFireCacheImpl.getExisting().getTxManager();
    for (TXStateProxy tx : txMgr.getHostedTransactionsInProgress()) {
      tx.setObserver(txOb1);
    }
    txMgr.setObserver(txOb1);
    GemFireXDQueryObserverHolder.setInstance(ob2);
  }
}
