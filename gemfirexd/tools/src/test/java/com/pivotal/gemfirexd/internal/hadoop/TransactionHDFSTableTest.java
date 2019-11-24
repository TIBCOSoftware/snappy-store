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
package com.pivotal.gemfirexd.internal.hadoop;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

public class TransactionHDFSTableTest extends JdbcTestBase {

  
  static final String HDFS_DIR = "./myhdfs";

  public TransactionHDFSTableTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  /**
   * tx operations with RR and Eviction
   * 1. Do tx-insert
   * 2. commit 
   * 3. check the row
   * 4. update the row with pk-based update
   * 5. check the row
   * 4. delete the row in tx( pk-based operation)
   * 5. check the row
   */
  
  public void testRRTransactionalInsertUpdateDeleteWithoutEvictionCriteriaPKbasedOperation() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs)");
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    st.executeUpdate("update tran.t1 set c2=20 where c1=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertTrue(rs.next());
    assertEquals(20,rs.getInt("c2"));
    rs.close();
    
    st.execute("delete from tran.t1 where c1 =10");
    conn.commit();

    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  /**
   * tx operations with RR and Eviction
   * 1. Do tx-insert
   * 2. commit 
   * 3. check the row
   * 4. update the row with table scan
   * 5. check the row
   * 4. delete the row in tx( with table scan)
   * 5. check the row
   */
  
  public void testRRTransactionalInsertUpdateDeleteWithoutEvictionCriteriaTableScan() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs)");
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    st.executeUpdate("update tran.t1 set c2=20 where c2=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertTrue(rs.next());
    assertEquals(20,rs.getInt("c2"));
    assertFalse(rs.next());
    rs.close();
    
    st.execute("delete from tran.t1 where c2 = 20");
    conn.commit();

    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");

    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  /**
   * tx operations with RC and Eviction
   * 1. Do tx-insert
   * 2. commit 
   * 3. check the row
   * 4. delete the row in tx( pk-based operation)
   * 5. check the row
   */
  
  public void testRCTransactionalInsertDeleteWithoutEvictionCriteriaPKbasedOperation() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs)");
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();

    st.executeUpdate("update tran.t1 set c2=20 where c1=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertTrue(rs.next());
    assertEquals(20,rs.getInt("c2"));
    rs.close();
    
    st.execute("delete from tran.t1 where c1 =10");
    conn.commit();

    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  /**
   * tx operations with RC and Eviction
   * 1. Do tx-insert
   * 2. commit 
   * 3. check the row
   * 4. delete the row in tx( with table scan)
   * 5. check the row
   */
  
  public void testRCTransactionalInsertDeleteWithoutEvictionCriteriaTableScan() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs)");
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    //insert
    st.execute("insert into tran.t1 values (10, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    //update
    st.executeUpdate("update tran.t1 set c2=20 where c2=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertTrue(rs.next());
    assertEquals(20,rs.getInt("c2"));
    rs.close();
    //delete
    st.execute("delete from tran.t1 where c2 =20");
    conn.commit();

    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  
  /**
   * tx operations with RR and Eviction
   * 1. Do 2 tx-insert such that one operational and none non-operational
   * 2. commit 
   * 3. check the row for operational and non-operational
   * 4. update the row with pk-based update and keep them as it is with respect to operational and non-operational
   * 5. check the row for operational and non-operational
   * 6. update the row with pk-based update and keep them opposite with respect to operational and non-operational
   * 7. check the row for operational and non-operational
   * 8. delete the row in tx( pk-based operation)
   * 9. check the row
   */
  
  public void testRRTransactionalInsertUpdateDeleteWithEvictionCriteriaPKbasedOperation() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs) " + " eviction by criteria ( c2 > 10 ) EVICT INCOMING");
    
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    st.execute("insert into tran.t1 values (20, 20)");
    conn.commit();
    
    // check operational data
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    // check all data
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    rs.close();    
    // update operational data and keep it operational
    st.executeUpdate("update tran.t1 set c2=4 where c1=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c1=10");
    assertTrue(rs.next());
    assertEquals(4,rs.getInt("c2"));
    rs.close();
    // update evicted data and keep it evicted
    st.executeUpdate("update tran.t1 set c2=40 where c1=20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c1=20");
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where c1=20 ");
    assertTrue(rs.next());
    assertEquals(40,rs.getInt("c2"));
    rs.close();
    
    
    // update the operational data so that it evicts
    st.executeUpdate("update tran.t1 set c2=30 where c1=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c1=10");
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where c1=10 ");
    assertTrue(rs.next());
    assertEquals(30,rs.getInt("c2"));
    rs.close();
    
    // update the evicted data so that it comes back in memory
    st.executeUpdate("update tran.t1 set c2=5 where c1=20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c1=20");
    assertTrue(rs.next());
    assertEquals(5,rs.getInt("c2"));
    rs.close();
    
    // delete both the row and check operational and evicted data
    // to verify
    st.execute("delete from tran.t1 where c1 = 10");
    st.execute("delete from tran.t1 where c1 = 20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  /**
   * tx operations with RR and Eviction
   * 1. Do 2 tx-insert such that one operational and none non-operational
   * 2. commit 
   * 3. check the row for operational and non-operational
   * 4. update the row with pk-based update and keep them as it is with respect to operational and non-operational
   * 5. check the row for operational and non-operational
   * 6. update the row with pk-based update and keep them opposite with respect to operational and non-operational
   * 7. check the row for operational and non-operational
   * 8. delete the row in tx( pk-based operation)
   * 9. check the row
   */
  
  public void testRRTransactionalInsertUpdateDeleteWithEvictionCriteriaTableScan() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();

    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir "
        + "'./myhdfs' queuepersistent true");

    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs) "
        + " eviction by criteria ( c2 > 10 ) EVICT INCOMING");

    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    st.execute("insert into tran.t1 values (20, 20)");
    conn.commit();
    
    // check operational data
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    // check all data
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    rs.close();    
    // update operational data and keep it operational
    st.executeUpdate("update tran.t1 set c2=4 where c2=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c2=4");
    assertTrue(rs.next());
    assertEquals(10,rs.getInt("c1"));
    rs.close();
    // update evicted data and keep it evicted
    st.executeUpdate("update tran.t1 set c2=40 where c2=20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c2=40");
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where c2=40 ");
    assertTrue(rs.next());
    assertEquals(40,rs.getInt("c2"));
    rs.close();
    
    
    // update the operational data so that it evicts
    st.executeUpdate("update tran.t1 set c2=30 where c2=4");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c2=30");
    assertFalse(rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where c2=30 ");
    assertTrue(rs.next());
    assertEquals(30,rs.getInt("c2"));
    rs.close();
    
    // update the evicted data so that it comes back in memory
    st.executeUpdate("update tran.t1 set c2=5 where c1=20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 where c1=20");
    assertTrue(rs.next());
    assertEquals(5,rs.getInt("c2"));
    rs.close();
    
    // delete both the row and check operational and evicted data
    // to verify
    st.execute("delete from tran.t1 where c1 = 10");
    st.execute("delete from tran.t1 where c1 = 20");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  
  public void testRRTransactionalInsertUpdateDeleteWithoutEvictionCriteriaUniqueIndex() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("create table tran.t1( id int primary key, qty int, abc int, constraint uq unique (qty,abc))"
              + " persistent hdfsstore (myhdfs)");
    
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    // insert the same partition column again.
    try {
      st.execute("insert into tran.t1 values (20, 10, 10)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  public void testRRTransactionalInsertUpdateDeleteWithEvictionCriteriaUniqueIndexNoEviction() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("create table tran.t1( id int primary key, qty int, abc int, constraint uq unique (qty,abc))"
              + " persistent hdfsstore (myhdfs)" + " eviction by criteria ( qty > 10 ) EVICT INCOMING");
    
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 5, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 -- GFXDIRE-PROPERTIES queryHDFS=true \n");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    // insert the same partition column again.
    try {
      st.execute("insert into tran.t1 values (20, 5, 10)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  public void testRRTransactionalInsertUpdateDeleteWithEvictionCriteriaUniqueIndex() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("create table tran.t1( id int primary key, qty int, abc int, constraint uq unique (qty,abc))"
              + " persistent hdfsstore (myhdfs)" + " eviction by criteria ( qty > 10 ) EVICT INCOMING");
    
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 20, 10)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();
    
    // insert the same partition column again.
    try {
      st.execute("insert into tran.t1 values (20, 20, 10)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  /**
   * Insert multiple row which will be evicted
   * do a scan operation which will change one row to make in non-operational
   * check if that row is in operational or not.
   * @throws Exception
   */
  public void testRRTransactionalInsertUpdateDeleteWithEvictionCriteriaUniqueIndex2() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("create table tran.t1( id int primary key, qty int, abc int, constraint uq unique (qty))"
              + " persistent hdfsstore (myhdfs)" + " eviction by criteria ( qty > 10 ) EVICT INCOMING");
    
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 20, 10)");
    st.execute("insert into tran.t1 values (20, 30, 20)");
    st.execute("insert into tran.t1 values (30, 40, 20)");
    st.execute("insert into tran.t1 values (40, 50, 20)");
    st.execute("insert into tran.t1 values (50, 60, 20)");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 5, numRows);
    rs.close();
    
    st.execute("update tran.t1 set qty=1 where abc=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1 ");
    
    assertTrue(rs.next());
    assertEquals(1,rs.getInt("qty"));
    conn.commit();
    rs.close();
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
     st.execute("drop schema tran restrict");
    st.close();
    conn.commit(); // not sure why it is getting region destroyed exception
    conn.close();
  }
  
  /**
   * Insert and Read
   * Check if rows are evicted/in-memory
   * @throws Exception
   */
  public void testRCTransactionalInsertUpdateDeleteReadPKbasedOperation() throws Exception {
    setupConnection();
    
    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) " + " persistent hdfsstore (myhdfs)" + " eviction by criteria ( c2 > 10 ) EVICT INCOMING");
    conn.commit();
    
    ResultSet rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into tran.t1 values (10, 10)");
    st.execute("insert into tran.t1 values (20, 20)");
    
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs.close();

    st.executeUpdate("update tran.t1 set c2=20 where c1=10");
    conn.commit();
    
    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    st.execute("delete from tran.t1 where c1 =10");
    conn.commit();

    rs = st.executeQuery("Select * from tran.t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();
    
    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  
  
  public void testEvictIncomingWithLocalIndexes() throws Exception {
    setupConnection();

    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    stmt.execute("create hdfsstore hdfsdata namenode 'localhost' homedir '"
        + HDFS_DIR + "' QUEUEPERSISTENT true");

    stmt.execute("create table trade.customers (cid int not null, cust_name int, addr int, primary key (cid)) " 
        + "persistent hdfsstore (hdfsdata) "
        + "eviction by criteria ( cust_name > 5 ) EVICT INCOMING ");
    
    // index on cust_name and addr
    stmt.execute("create index idx1 on trade.customers (cust_name)");
    stmt.execute("create index idx2 on trade.customers (addr)");
    conn.commit();
    // some inserts
    // will be evicted
    stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
    stmt.executeUpdate("insert into trade.customers values (" + 13 + ", " + (13 * 10) +", " + (13 * 100) + ")");
    // will not be evicted
    stmt.executeUpdate("insert into trade.customers values (" + 1 + ", " + (1 * 1) +", " + (1 * 100) + ")");
    // check if inserting an evicted row gives error
    conn.commit();
//    try {
//      stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
//      fail("Expected Exception as the row was already inserted. ");
//    }
//    catch (Exception e) {
//      
//    }
    
    // verify the operational data.
    // 1 1 100
    ResultSet rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    assertEquals(120, rs.getInt("cust_name"));
    assertEquals(1200, rs.getInt("addr"));    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=1");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=100");
    assertTrue(rs.next());
    assertEquals(100, rs.getInt("addr"));

    // 12, 120, 1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=120");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertFalse(rs.next());
    // 13, 130, 1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=13");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=130");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1300");
    assertFalse(rs.next());
    
    // verify the non operational data.
    // 12,120,1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=120");
    assertTrue(rs.next());
    assertEquals(120, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    // 13,130,1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=13");
    assertTrue(rs.next());
    assertEquals(13, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=130");
    assertTrue(rs.next());
    assertEquals(130, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1300");
    assertTrue(rs.next());
    assertEquals(1300, rs.getInt("addr"));
    
    
    // making a change to evicted row such that EvictionCriteria is not satisfied.
    stmt.executeUpdate("update trade.customers set cust_name=4 where cid=12");
    conn.commit();
    // it should be back in operational data
    // 12,4,1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertTrue(rs.next());
    assertEquals(4, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    
    // making a change to evicted row such that EvictionCriteria is satisfied.
    stmt.executeUpdate("update trade.customers set cust_name=15 where cid=13");
    conn.commit();
    // 13, 15, 1300 in non-operational    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=13");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=15");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1300");
    assertFalse(rs.next());
    
    // 13,15,1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=13");
    assertTrue(rs.next());
    assertEquals(13, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=15");
    assertTrue(rs.next());
    assertEquals(15, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1300");
    assertTrue(rs.next());
    assertEquals(1300, rs.getInt("addr"));
//    
//    LocalRegion lr = (LocalRegion)Misc.getRegion("/TRADE/CUSTOMERS", true);
    //TODO: check if this assumtion is correct!
    //assertEquals(2, lr.size());
  }

  
    /**
     * tx operations with RC and Without Eviction
     * 1. Do tx-insert
     * 2. check the row it should be availalble
     * 4. delete the row in tx( with table scan)
     * 5. check the row
     * 6. commit
     * 7. Nothing should be in the table.
     */

    public void testRCTransactionalInsertDeleteWithoutEvictionCriteria() throws Exception {
      setupConnection();
      
      Connection conn = jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement st = conn.createStatement();
      
     st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
      
      st.execute("create schema tran");
      st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
          + "primary key(c1))" + " persistent hdfsstore (myhdfs)");
      conn.commit();
      ResultSet rs;
  //    ResultSet rs = st.executeQuery("Select * from tran.t1");
  //    assertFalse("ResultSet should be empty ", rs.next());
  //    rs.close();
      //insert
      st.execute("insert into tran.t1 values (10, 10)");
      
      rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n ");
      int numRows = 0;
      while (rs.next()) {
        numRows++;
      }
      assertEquals("ResultSet should contain one row ", 1, numRows);
      rs.close();
      
      //update
      st.executeUpdate("update tran.t1 set c2=20 where c2=10");
      
      rs = st.executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
      assertTrue(rs.next());
      assertEquals(20,rs.getInt("c2"));
      rs.close();
      //delete
      st.execute("delete from tran.t1 where c2 =20");
      conn.commit();
  
      rs = st.executeQuery("Select * from tran.t1");
      assertFalse("ResultSet should be empty ", rs.next());
      rs.close();
      
      // Close connection, resultset etc...
      st.execute("drop table tran.t1");
      st.execute("drop hdfsstore myhdfs");
      st.execute("drop schema tran restrict");
      st.close();
      conn.commit();
      conn.close();
    }
  
    
  /**
   * tx operations with RC and Without Eviction 
   * 1. Do tx-insert 3 rows 
   * 2. check the count of rows it should be availalble 
   * 3. update the row in tx
   * 4 count should be same 
   * 5. delete one row 
   * 6. commit 
   * 7. count should be 2
   */

  public void testRCTransactionalInsertDeleteWithoutEvictionCriteriaCount()
      throws Exception {
    setupConnection();

    Connection conn = jdbcConn;
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();

    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");

    st.execute("create schema tran");
    st.execute("Create table tran.t1 (c1 int not null , c2 int not null, "
        + "primary key(c1))" + " persistent hdfsstore (myhdfs)");
    conn.commit();
    ResultSet rs;
    // ResultSet rs = st.executeQuery("Select * from tran.t1");
    // assertFalse("ResultSet should be empty ", rs.next());
    // rs.close();
    // insert
    st.execute("insert into tran.t1 values (10, 10)");
    st.execute("insert into tran.t1 values (20, 20)");
    st.execute("insert into tran.t1 values (30, 30)");

    rs = st
        .executeQuery("Select * from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n ");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 3, numRows);
    rs.close();

    rs = st
        .executeQuery("Select count(*) from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n ");
    rs.next();
    numRows = rs.getInt(1);
    assertEquals("ResultSet should contain one row ", 3, numRows);
    rs.close();

    rs = st.executeQuery("Select count(*) from tran.t1 \n ");
    rs.next();
    numRows = rs.getInt(1);
    assertEquals("ResultSet should contain one row ", 3, numRows);
    rs.close();

    // update
    st.executeUpdate("update tran.t1 set c2=20 where c2=10");

    rs = st
        .executeQuery("Select count(*) from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n ");
    rs.next();
    numRows = rs.getInt(1);
    assertEquals("ResultSet should contain three row ", 3, numRows);
    rs.close();

    // delete
    st.execute("delete from tran.t1 where c2 =20");
    conn.commit();

    rs = st
        .executeQuery("Select count(*) from tran.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n ");
    rs.next();
    numRows = rs.getInt(1);
    assertEquals("ResultSet should contain two row ", 1, numRows);
    rs.close();

    // Close connection, resultset etc...
    st.execute("drop table tran.t1");
    st.execute("drop hdfsstore myhdfs");
    st.execute("drop schema tran restrict");
    st.close();
    conn.commit();
    conn.close();
  }
  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    delete(new File(HDFS_DIR));
  }

  private void delete(File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      if (file.list().length == 0) {
        file.delete();
      } else {
        File[] files = file.listFiles();
        for (File f : files) {
          delete(f);
        }
        file.delete();
      }
    } else {
      file.delete();
    }
  }
}
