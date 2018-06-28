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
package com.pivotal.gemfirexd.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;

import com.pivotal.gemfirexd.TestUtil;
import io.snappydata.test.util.TestException;

@SuppressWarnings("serial")
public class TransactionRRDUnit extends TransactionDUnit {

  public TransactionRRDUnit(String name) {
    super(name);
  }

  /**
   * Test for bug #42822 that fails when reading TX deleted rows (due to deletes
   * or updates) in another TX.
   */
  @Override
  public void test42822() throws Exception {
    Connection conn = TestUtil.jdbcConn;
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    Statement stmt = conn.createStatement();
    stmt.execute("create table oorder ("
        + "o_w_id       integer      not null,"
        + "o_d_id       integer      not null,"
        + "o_id         integer      not null,"
        + "o_c_id       integer,"
        + "o_carrier_id integer,"
        + "o_ol_cnt     decimal(2,0),"
        + "o_all_local  decimal(1,0),"
        + "o_entry_d    timestamp"
        + ") partition by column (o_w_id)");
    stmt.execute("create index oorder_carrier1 on oorder (o_w_id)");
    stmt.execute("create index oorder_carrier2 on oorder (o_d_id)");
    stmt.execute("create index oorder_carrier3 on oorder (o_c_id)");
    // some inserts, deletes and updates
    stmt.execute("insert into oorder (o_w_id, o_d_id, o_id, o_c_id) values "
        + "(1, 2, 2, 1), (2, 4, 4, 2), (3, 6, 6, 3), (4, 8, 8, 4)");
    conn.commit();
    stmt.execute("delete from oorder where o_d_id > 6");
    stmt.execute("update oorder set o_c_id = o_w_id + 2 where o_w_id < 2");
    stmt.execute("update oorder set o_c_id = o_id + 1 where o_w_id >= 3");
    // now a select with open transaction using another connection
    this.threadEx = null;
    final CyclicBarrier barrier = new CyclicBarrier(2);
    Thread t = new Thread(new Runnable() {

      @Override
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(getIsolationLevel());
          conn.setAutoCommit(false);
          checkResultsFor42822(conn, false);
          // wait for other thread to sync up
          barrier.await();
          // wait for other thread to complete commit
          barrier.await();
          // for repeatable read isolation level we expect no change
          checkResultsFor42822(conn, false);
          conn.commit();
          // now check for updated results again after commit
          barrier.await();
          checkResultsFor42822(conn, true);
          conn.commit();
        } catch (Throwable t) {
          getLogWriter().error("unexpected exception", t);
          threadEx = t;
        }
      }
    });
    t.start();
    checkResultsFor42822(conn, true);
    barrier.await();
    if (this.threadEx != null) {
      throw new TestException("unexpected exception in thread", this.threadEx);
    }
    // for repeatable read isolation level, we expect a read-write conflict
    try {
      conn.commit();
      fail("expected conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // check for no updated results in this thread or other
    checkResultsFor42822(conn, false);
    barrier.await();

    // now update and check again
    stmt.execute("delete from oorder where o_d_id > 6");
    stmt.execute("update oorder set o_c_id = o_w_id + 2 where o_w_id < 2");
    stmt.execute("update oorder set o_c_id = o_id + 1 where o_w_id >= 3");
    checkResultsFor42822(conn, true);
    conn.commit();
    // now check for updated results in this thread and other
    checkResultsFor42822(conn, true);
    barrier.await();
    t.join();
    if (this.threadEx != null) {
      throw new TestException("unexpected exception in thread", this.threadEx);
    }
  }

  /**
   * Test that reads are really repeatable and that intervening writes get
   * conflicts during commit and do not get conflicts in case the read txns
   * commit before its commit.
   */
  public void testRepeatableRead() throws Exception {
    Connection conn = TestUtil.jdbcConn;
    Statement st = conn.createStatement();
    ResultSet rs;
    st.execute("Create table tran.t1 (c1 int not null, c2 int not null, "
        + "primary key(c1)) partition by primary key redundancy 2");
    conn.commit();

    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);
    PreparedStatement pstmt = conn
        .prepareStatement("insert into tran.t1 values (?, ?)");
    for (int c1 = 10; c1 <= 100; c1 += 10) {
      pstmt.setInt(1, c1);
      pstmt.setInt(2, c1 / 2);
      pstmt.execute();
    }
    conn.commit();

    // first try with get convertible reads
    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    conn2.setAutoCommit(false);
    PreparedStatement pstmt2 = conn2
        .prepareStatement("select * from tran.t1 where c1 = ?");
    for (int c1 = 50; c1 <= 80; c1 += 10) {
      pstmt2.setInt(1, c1);
      rs = pstmt2.executeQuery();
      assertTrue(rs.next());
      assertEquals(c1, rs.getInt(1));
      assertEquals(c1 / 2, rs.getInt(2));
      assertFalse(rs.next());
    }

    st = conn.createStatement();
    st.execute("delete from tran.t1 where c1 > 70");
    try {
      conn.commit();
      fail("expected a conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    for (int c1 = 50; c1 <= 80; c1 += 10) {
      st.execute("delete from tran.t1 where c1 = " + c1);
      try {
        conn.commit();
        fail("expected a conflict exception");
      } catch (SQLException sqle) {
        if (!"X0Z02".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }
    st.execute("update tran.t1 set c2 = 50 where c1 = 70");
    try {
      conn.commit();
      fail("expected a conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // check repeats in reads
    for (int c1 = 10; c1 <= 100; c1 += 10) {
      pstmt2.setInt(1, c1);
      rs = pstmt2.executeQuery();
      assertTrue(rs.next());
      assertEquals(c1, rs.getInt(1));
      assertEquals(c1 / 2, rs.getInt(2));
      assertFalse(rs.next());
    }

    // check that if reads finish then updates can go ahead
    conn2.commit();

    assertEquals(3, st.executeUpdate("delete from tran.t1 where c1 > 70"));
    conn.commit();
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (80, 40)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (90, 45)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (100, 50)"));
    conn.commit();
    assertEquals(3, st.executeUpdate("delete from tran.t1 where c1 > 70"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (80, 40)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (90, 45)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (100, 50)"));
    conn.commit();

    // now check the above with bulk reads
    Statement st2 = conn2.createStatement();
    rs = st2.executeQuery("select * from tran.t1 "
        + "where c1 > 40 and c1 <= 80 order by c2");
    for (int c1 = 50; c1 <= 80; c1 += 10) {
      assertTrue(rs.next());
      assertEquals(c1, rs.getInt(1));
      assertEquals(c1 / 2, rs.getInt(2));
    }
    assertFalse(rs.next());

    st.execute("delete from tran.t1 where c1 > 70");
    try {
      conn.commit();
      fail("expected a conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    for (int c1 = 50; c1 <= 80; c1 += 10) {
      st.execute("delete from tran.t1 where c1 = " + c1);
      try {
        conn.commit();
        fail("expected a conflict exception");
      } catch (SQLException sqle) {
        if (!"X0Z02".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }
    st.execute("update tran.t1 set c2 = 50 where c1 = 70");
    try {
      conn.commit();
      fail("expected a conflict exception");
    } catch (SQLException sqle) {
      if (!"X0Z02".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // check repeats in reads
    for (int c1 = 10; c1 <= 100; c1 += 10) {
      pstmt2.setInt(1, c1);
      rs = pstmt2.executeQuery();
      assertTrue(rs.next());
      assertEquals(c1, rs.getInt(1));
      assertEquals(c1 / 2, rs.getInt(2));
      assertFalse(rs.next());
    }

    // check that if reads finish then updates can go ahead
    conn2.commit();

    assertEquals(3, st.executeUpdate("delete from tran.t1 where c1 > 70"));
    conn.commit();
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (80, 40)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (90, 45)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (100, 50)"));
    conn.commit();
    assertEquals(3, st.executeUpdate("delete from tran.t1 where c1 > 70"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (80, 40)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (90, 45)"));
    assertEquals(1, st.executeUpdate("insert into tran.t1 values (100, 50)"));

    conn.commit();
    conn.close();
  }

  @Override
  protected int getIsolationLevel() {
    return Connection.TRANSACTION_REPEATABLE_READ;
  }

  @Override
  protected void checkConnCloseExceptionForReadsOnly(Connection conn)
      throws SQLException {
    try {
      conn.close();
      fail("expected connection active exception in close");
    } catch (SQLException e) {
      String state = e.getSQLState();
      if (!state.equals("25001")) {
        throw e;
      }
    }
  }
}
