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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.PartitionedRegionStorageException;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import io.snappydata.test.dunit.SerializableRunnable;

/**
 * 
 * @author Asif
 * 
 */
@SuppressWarnings("serial")
public class PersistentPartitionPreparedStatementDUnit extends
    PreparedStatementDUnit {
  private final static String DISKSTORE = "TestPersistenceDiskStore";

  public PersistentPartitionPreparedStatementDUnit(String name) {
    super(name);
  }

  @Override
  public String getSuffix() throws Exception {
    String suffix = " PERSISTENT " + "'" + DISKSTORE + "'";
    return suffix;
  }

  @Override
  public void createDiskStore(boolean useClient, int vmNum) throws Exception {
    SerializableRunnable csr = getDiskStoreCreator(DISKSTORE);
    if (useClient) {
      if (vmNum == 1) {
        csr.run();
      }
      else {
        clientExecute(vmNum, csr);
      }
    }
    else {
      serverExecute(vmNum, csr);
    }
  }

  @Override
  protected String[] testSpecificDirectoriesForDeletion() {
    return new String[] {"test_dir"};
  }

  public void testDataPersistenceOfPRWithRangePartitioning() throws Exception {
    String prClause = " partition by range (cid) ( VALUES BETWEEN 0 AND 10,"
        + " VALUES BETWEEN 10 AND 20, VALUES BETWEEN 20 AND 30 )  ";
    dataPersistenceOfPR(prClause);
  }

  public void testDataPersistenceOfPRWithDefaultPartitioning() throws Exception {
    String prClause = " ";
    dataPersistenceOfPR(prClause);
  }

  public void testDataPersistenceOfPRWithColumnPartitioning() throws Exception {
    String prClause = " partition by column (cid) ";
    dataPersistenceOfPR(prClause);
  }

  public void testDataPersistenceOfPRWithPrimaryKeyPartitioning()
      throws Exception {
    String prClause = " partition by primary key ";
    dataPersistenceOfPR(prClause);
  }

  public void testDataPersistenceOfPRWithListPartitioning() throws Exception {
    String prClause = " PARTITION BY LIST ( cid ) ( VALUES (0, 10 ), "
        + " VALUES (11, 20), VALUES (21, 45) ) ";
    dataPersistenceOfPR(prClause);
  }

  public void testDiskTableNodeRecycle() throws Exception {
     //create a persistence disk table with a unique constraint.
    //Set redundancy 0. Insert some data . bring one of nodes down & then restart.
    // Add some data which is expected to throw constraint violation.
    startVMs(1, 3);
    createDiskStore(true,1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(
        1,
        "create table trade.customers (cid int not null, cust_name varchar(100), tid int, " +
        "uniq int unique not null, primary key (cid))   "+   getSuffix());
    Connection conn = TestUtil.getConnection();
    //Add 1 to 10 fields, with  1- 10 as uniq field
    Set<Integer> cids = new HashSet<Integer>();
    PreparedStatement ps = conn.prepareStatement("insert into trade.customers values (?,?,?,?)");
    for( int i = 1;i <11 ; ++i) {
      ps.setInt(1, i);
      ps.setString(2, "name"+i);
      ps.setInt(3, i);
      ps.setInt(4, i);
      ps.executeUpdate();
      cids.add(i);
    }
    stopVMNum(-3);
    //Find out the missing data unavaialble due to vm 3 shutdown
    Statement stmt = conn.createStatement();
    ResultSet rs = null;
    try {
      rs = stmt.executeQuery("select cid from trade.customers");
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }
    restartServerVMNums(new int[] { 3 }, 0, null, null);
    conn = TestUtil.getConnection();
    Iterator<Integer> itr = cids.iterator();
    int i =11;
    while( itr.hasNext()) {
      ps.setInt(1, i);
      ps.setString(2, "name"+i);
      ps.setInt(3, i);
      ps.setInt(4,itr.next() );
      try {
        ps.executeUpdate();
        fail("Constraint violation expected");
      }catch(SQLIntegrityConstraintViolationException sqle) {       
        //Expected constraint violation
      }      
      ++i;
    }  
    //Add some more
    i =11;
    for(;i <21;++i){
      ps.setInt(1, i);
      ps.setString(2, "name"+i);
      ps.setInt(3, i);
      ps.setInt(4,i );      
      assertEquals(1,  ps.executeUpdate());      
    }
    stopVMNums(1);
    stopVMNums(-3,-2,-1);    
    restartVMNums(-1, -2, -3);
    restartVMNums(1);
    conn = TestUtil.getConnection();
    stmt = conn.createStatement();
    rs = stmt.executeQuery("select cid from trade.customers");
    Set<Integer> result = new HashSet<Integer>();
    for (int j = 1; j < 21; ++j) {
      result.add(j);
    }
    while (rs.next()) {
      result.remove(rs.getInt(1));
    }
    assertTrue(result.isEmpty());
    itr = cids.iterator();
    i = 21;
    ps = conn.prepareStatement("insert into trade.customers values (?,?,?,?)");
    while (itr.hasNext()) {
      ps.setInt(1, i);
      ps.setString(2, "name" + i);
      ps.setInt(3, i);
      ps.setInt(4, itr.next());
      try {
        ps.executeUpdate();
        fail("Constraint violation expected");
      } catch (SQLIntegrityConstraintViolationException sqle) {
        // Expected constraint violation
      }
      ++i;
    }
  }

  private void dataPersistenceOfPR(String partitionClause) throws Exception {
    startVMs(1, 3);

    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) "
        + partitionClause + getSuffix());
    Connection conn = TestUtil.getConnection();
    PreparedStatement ps = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 31; ++i) {
      ps.setInt(1, i);
      ps.setString(2, "name" + i);
      ps.setInt(3, i);
      ps.executeUpdate();
    }
    
    stopVMNum(1);
    stopVMNums(-3, -2, -1);
    restartVMNums(-3, -2, -1);
    restartVMNums(1);
    stopVMNums(-3, -2, -1);
    conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);
    ResultSet rs = null;
    try {
      rs = stmt.executeQuery("select * from trade.customers");
      rs.next();
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);
    restartVMNums(-1, -2, -3);

    stmt = conn.createStatement();
    rs = stmt.executeQuery("select * from trade.customers");
    int expected = 465;
    int actual = 0;
    while (rs.next()) {
      int val = rs.getInt(1);
      actual += val;
    }
    assertEquals(expected, actual);
  }

  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   * 
   * @throws Exception
   */
  public void xxtestPartitionOfflineBehaviourBug42447_1() throws Exception {

    startVMs(1, 3);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 31; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "name" + i);
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
    }

    stopVMNum(1);
    stopVMNums(-3, -2, -1);
    restartVMNums(-3, -2, -1);
    restartVMNums(1);
    stopVMNums(-3, -2, -1);

    // start a server VM start in background
    // AsyncVM async1 = restartServerVMAsync(1, 0, null, null);

    conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);

    // Test bulk operations
    try {
      stmt.executeQuery("select * from trade.customers");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("select * from trade.customers " + "where tid > ?");
      ps.setInt(1, 0);
      ps.executeQuery();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      stmt.executeUpdate("update trade.customers set tid = 5 where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("update trade.customers set tid = ?"
              + " where tid > ?");
      ps.setInt(1, 5);
      ps.setInt(2, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      stmt.executeUpdate(" delete from trade.customers  where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement(" delete from trade.customers  where tid > ?");
      ps.setInt(1, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    // Test PK based operations
    try {
      ResultSet rs = stmt
          .executeQuery("select * from trade.customers where cid  = 3");
      rs.next();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals("X0Z08", sqle.getSQLState());
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("select * from trade.customers " + "where cid = ?");
      ps.setInt(1, 3);
      ResultSet rs = ps.executeQuery();
      rs.next();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      stmt.executeUpdate("update trade.customers set tid = 5 where cid = 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("update trade.customers set tid = ?"
              + " where cid = ?");
      ps.setInt(1, 5);
      ps.setInt(2, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      stmt.executeUpdate(" delete from trade.customers  where  cid = 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement(" delete from trade.customers  where cid = ?");
      ps.setInt(1, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    restartVMNums(-1, -2, -3);
    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);
  }

  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   * 
   * @throws Exception
   */
  public void testPartitionOfflineBehaviourBug42447_2() throws Exception {
    startVMs(1, 1);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 31; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "name" + i);
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
    }
    // start another server and add some data so that it goes to node 2
    startServerVMs(1, 0, null);

    for (int i = 31; i < 41; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "name" + i);
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
    }

    // Now stop the newly started server
    stopVMNum(-2);

    conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null, PartitionOfflineException.class);

    // Test bulk operations
    try {
      stmt.executeQuery("select * from trade.customers");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("select * from trade.customers " + "where tid > ?");
      ps.setInt(1, 0);
      ps.executeQuery();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate("update trade.customers set tid = 5 where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("update trade.customers set tid = ?"
              + " where tid > ?");
      ps.setInt(1, 5);
      ps.setInt(2, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate(" delete from trade.customers  where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement(" delete from trade.customers  where tid > ?");
      ps.setInt(1, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    // Test PK based operations
    ResultSet rs = stmt
        .executeQuery("select * from trade.customers where cid  = 1");
    assertTrue(rs.next());

    PreparedStatement ps = conn
        .prepareStatement("select * from trade.customers " + "where cid = ?");
    ps.setInt(1, 1);
    rs = ps.executeQuery();
    assertTrue(rs.next());

    int num = stmt
        .executeUpdate("update trade.customers set tid = 5 where cid = 3");
    assertEquals(1, num);

    ps = conn.prepareStatement("update trade.customers set tid = ?"
        + " where cid = ?");
    ps.setInt(1, 5);
    ps.setInt(2, 3);
    ps.executeUpdate();
    assertEquals(1, num);

    num = stmt.executeUpdate(" delete from trade.customers  where  cid = 3");
    assertEquals(1, num);

    ps = conn.prepareStatement(" delete from trade.customers  where cid = ?");
    ps.setInt(1, 4);
    num = ps.executeUpdate();
    assertEquals(1, num);

    removeExpectedException(new int[] { 1 }, null,
        PartitionOfflineException.class);
  }

  /**
   * This is currently disabled because of a possible bug in GFE ,whereby if the 
   * data store holding the primary is offline, the exception thrown is 
   * EntryNotFound for PK based update
   * @throws Exception
   */
  public void __testPartitionOfflineBehaviourBug42447_3() throws Exception {

    startVMs(1, 1);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 11; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "name" + i);
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
    }
    // start one more server VM
    startServerVMs(1, 0, null, null);

    // Stop first server VM
    stopVMNum(-1);

    conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null, PartitionOfflineException.class);

    // Test bulk operations
    try {
      stmt.executeQuery("select * from trade.customers");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("select * from trade.customers " + "where tid > ?");
      ps.setInt(1, 0);
      ps.executeQuery();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate("update trade.customers set tid = 5 where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("update trade.customers set tid = ?"
              + " where tid > ?");
      ps.setInt(1, 5);
      ps.setInt(2, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate(" delete from trade.customers  where tid > 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement(" delete from trade.customers  where tid > ?");
      ps.setInt(1, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    // Test PK based operations
    try {
      ResultSet rs = stmt
          .executeQuery("select * from trade.customers where cid  = 1");
      rs.next();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals("X0Z09", sqle.getSQLState());
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("select * from trade.customers " + "where cid = ?");
      ps.setInt(1, 1);
      ResultSet rs = ps.executeQuery();
      rs.next();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate("update trade.customers set tid = 5 where cid = 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement("update trade.customers set tid = ?"
              + " where cid = ?");
      ps.setInt(1, 5);
      ps.setInt(2, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      stmt.executeUpdate(" delete from trade.customers  where  cid = 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }

    try {
      PreparedStatement ps = conn
          .prepareStatement(" delete from trade.customers  where cid = ?");
      ps.setInt(1, 3);
      ps.executeUpdate();
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z09");
    }
    removeExpectedException(new int[] { 1 }, null,
        PartitionOfflineException.class);
  }
  
  public void testPartitionOfflineBehaviourBug42443_1() throws Exception {

    startVMs(1, 1);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int unique, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();    

    // stop the server
    stopVMNum(-1);

    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null, PartitionedRegionStorageException.class);

    // Test bulk operations
    try {
      stmt.executeQuery("select * from trade.customers where tid = 3");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);

    // restart the server to enable dropping the diskstore in teardown
    restartVMNums(-1);
  }

  public void testPartitionOfflineBehaviourBug42443_2() throws Exception {

    startVMs(1, 1);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int unique, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();    

    // stop the server
    stopVMNum(-1);

    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null, PartitionedRegionStorageException.class);

    // Test bulk operations
    try {
      stmt.executeUpdate("insert into trade.customers values(1,'cust_1',1)");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);

    // restart the server to enable dropping the diskstore in teardown
    restartVMNums(-1);
  }
  
  public void testPartitionOfflineBehaviourBug49563() throws Exception {
    stopAllVMs();
    startVMs(0, 1);
    startVMs(1,0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.buyorders(oid int not null constraint buyorders_pk primary key, cid int, sid int)  "
        + "partition by range   (sid) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1477, "
        + "VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 100000)  REDUNDANCY 1 RECOVERYDELAY -1 STARTUPRECOVERYDELAY -1 " + getSuffix());
    Connection conn = TestUtil.getConnection();    

    PreparedStatement ps = conn.prepareStatement("insert into trade.buyorders values (?, ?, ?)");
    
    //Create bucket 1 on the existing servers.
    ps.setInt(1, 1);
    ps.setInt(2, 1);
    ps.setInt(3, 1);
    ps.execute();


    //start another server.
    startVMs(0, 1);
    
    // stop the first server
    stopVMNum(-1);

    //Now create a putall that will put a few entries into each bucket. Bucket 1 should
    //get a partition offline exception.
    
    //bucket 2
    ps.setInt(1, 2);
    ps.setInt(2, 2);
    ps.setInt(3, 500);
    ps.addBatch();
    
    //bucket 1
    ps.setInt(1, 3);
    ps.setInt(2, 3);
    ps.setInt(3, 400);
    ps.addBatch();
    
    //bucket 2
    ps.setInt(1, 4);
    ps.setInt(2, 4);
    ps.setInt(3, 600);
    ps.addBatch();
    addExpectedException(new int[] { 1 }, null, PartitionOfflineException.class);
    try {
      ps.executeBatch();
      fail("Should have failed with a partition offline exception.");
    } catch(SQLException sql) {
      assertEquals("X0Z09",sql.getSQLState());
    }

    removeExpectedException(new int[] { 1 }, null,
        PartitionOfflineException.class);

    // restart the server to enable dropping the diskstore in teardown
    restartVMNums(-1);
  }

  public void testPartitionOfflineBehaviourBug42443_3() throws Exception {

    startVMs(1, 1);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int unique, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    
    clientSQLExecute(1, "create table trade.portfolio ( pid int not null, " +
    		"cid int not null, "
        + "pf_name varchar(100), tid int unique, primary key (pid)," +
	"constraint cust_newt_fk foreign key (cid) references " +
	"trade.customers (cid) on delete restrict ) "
        + "partition by column(cid)" + getSuffix());
    Connection conn = TestUtil.getConnection();    

    // stop the server
    stopVMNum(-1);

    Statement stmt = conn.createStatement();
    addExpectedException(new int[] { 1 }, null, PartitionedRegionStorageException.class);

    // Test bulk operations
    try {
      stmt.executeUpdate("delete from trade.customers where tid > 0");
      fail("Test should fail due to insufficient data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }
    removeExpectedException(new int[] { 1 }, null,
        PartitionedRegionStorageException.class);

    // restart the server to enable dropping the diskstore in teardown
    restartVMNums(-1);
  }
  
  public void testBug49193_1() throws Exception {
    startVMs(1, 2);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create table customers (cid int not null, "
        + "cust_name varchar(100), col1 int , col2 int not null unique ,"
        + "tid int, primary key (cid)) " + " partition by range(tid)"
        + "   ( values between 0  and 10" + "    ,values between 10  and 100)"
        + getSuffix());
    this.basicTest49193();

  }

  public void testBug49193_2() throws Exception {
    startVMs(1, 2);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create table customers (cid int not null, "
        + "cust_name varchar(100), col1 int , col2 int not null unique ,"
        + "tid int, primary key (cid)) " + " partition by range(tid)"
        + "   ( values between 0  and 10" + "    ,values between 10  and 100)"
        + getSuffix());

    clientSQLExecute(1, "create index i1 on customers(col1)");
    this.basicTest49193();
  }
  
  private void basicTest49193() throws Exception {
    Connection conn = TestUtil.getConnection();
    PreparedStatement psInsert = conn
        .prepareStatement("insert into customers values (?,?,?,?,?)");
    try {
      for (int i = 1; i < 20; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }
      // Start another server , this will be the third node
      // it will contain global index buckets.
      startServerVMs(1, -1, "");
      // insert some more data so that bucket on third server holding global
      // index gets created
      for (int i = 20; i < 90; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }

      // Now stop the third server
      stopVMNums(-3);
      conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();
      addExpectedException(new int[] { 1 }, new int[] { 1, 2 },
          PartitionOfflineException.class);
      addExpectedException(new int[] { 1 }, new int[] { 1, 2 },
          SQLException.class);
      Runnable optimizerOverride = new SerializableRunnable() {
        @Override
        public void run() {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {

                @Override
                public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                    OpenMemIndex memIndex, double optimizerEvalutatedCost) {
                  return Double.MAX_VALUE;
                }

                @Override
                public double overrideDerbyOptimizerCostForMemHeapScan(
                    GemFireContainer gfContainer, double optimizerEvalutatedCost) {
                  return Double.MIN_VALUE;
                }

              });

        }
      };
        
      
       
      serverExecute(1, optimizerOverride);
      serverExecute(2, optimizerOverride);
      // Test bulk operations
      try {
        stmt.executeUpdate("delete from customers where cid > 50");
        fail("Test should fail due to problem in global index maintenance");
      } catch (Exception sqle) {
        removeExpectedException(new int[] { 1 }, new int[] { 1, 2 },
            PartitionOfflineException.class);
        removeExpectedException(new int[] { 1 }, new int[] { 1, 2 },
            SQLException.class);

      }
      // re start the 3 rd vm.
      restartVMNums(-3);
      stmt.executeUpdate("delete from customers where cid > 50");
    } finally {
      Runnable reset = new SerializableRunnable() {
        @Override
        public void run() {
          GemFireXDQueryObserverHolder.clearInstance();
        }
      };
      serverExecute(1, reset);
      serverExecute(2, reset);

    }

  }

  public void testBug43104_1() throws Exception {
    startVMs(1, 2);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int unique, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    
    clientSQLExecute(1, "create table trade.portfolio ( cid int not null, " +
                "sid int not null,  tid int unique, constraint pk primary key (cid,sid)," +
        "constraint cust_newt_fk foreign key (cid) references " +
        "trade.customers (cid) on delete restrict ) "
        + " replicate " + getSuffix());
    
    clientSQLExecute(1, "create table trade.sellorders (oid int not null constraint orders_pk primary key," +
     "cid int, sid int, tid int, constraint portf_fk foreign key (cid, sid) references " +
     "trade.portfolio (cid, sid) on delete restrict)  partition by list (tid)" +
     " (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) " +
         getSuffix());
    
    PreparedStatement psCust = TestUtil.getPreparedStatement("insert into trade.customers values(?,?,?)");
    PreparedStatement psPortf = TestUtil.getPreparedStatement("insert into trade.portfolio values(?,?,?)");
    PreparedStatement psSellOrders = TestUtil.getPreparedStatement("insert into trade.sellorders values(?,?,?,?)");
    for(int i = 0 ; i < 10;++i) {
      psCust.setInt(1, i);
      psCust.setString(2, "name"+i);
      psCust.setInt(3, i);
      psCust.executeUpdate();
    }
    
    for(int i = 0 ; i < 10;++i) {
      psPortf.setInt(1, i);
      psPortf.setInt(2, 121);      
      psPortf.setInt(3, i);
      psPortf.executeUpdate();      
    }
    
    for(int i = 0 ; i < 10;++i) {
      psSellOrders.setInt(1, i);
      psSellOrders.setInt(2, i);      
      psSellOrders.setInt(3, 121);
      psSellOrders.setInt(4, i);
      psSellOrders.executeUpdate();      
    }
    
    // Stop first server VM
    stopVMNum(-1);
    
    //Now try to delete the rows of portfolio  one by one.
    //None should succeed
    PreparedStatement psPFDelete = TestUtil.getPreparedStatement("delete from trade.portfolio where cid = ? and sid = ? and tid =?");
    for(int i = 0; i <10;++i) {
      psPFDelete.setInt(1, i);
      psPFDelete.setInt(2, 121);
      psPFDelete.setInt(3, i);
      try {
        psPFDelete.executeUpdate();
        fail("The delete should have thrown exception");
      }catch(SQLIntegrityConstraintViolationException sqle) {
        //ok       
      } catch (SQLException sqle) {
        assertEquals(sqle.getSQLState(), "X0Z09");
      }
      
    }
    
  }
  
  public void testBug43104_2() throws Exception {
    startVMs(1, 2);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int unique, primary key (cid)) "
        + "partition by column(cid)" + getSuffix());
    
    clientSQLExecute(1, "create table trade.portfolio ( cid int not null, " +
                "sid int not null,  tid int unique, constraint pk primary key (cid,sid)," +
        "constraint cust_newt_fk foreign key (cid) references " +
        "trade.customers (cid) on delete restrict ) "
        + " replicate " + getSuffix());
    
    clientSQLExecute(1, "create table trade.sellorders (oid int not null constraint orders_pk primary key," +
     "cid int, sid int, tid int, constraint portf_fk foreign key (cid, sid) references " +
     "trade.portfolio (cid, sid) on delete restrict)  partition by list (tid)" +
     " (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) " +
         getSuffix());
    
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, symbol varchar(10) not null, " +
    " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), " +
    "constraint sec_uq unique (symbol, exchange), " +
    "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) " +
            getSuffix());
    
    
    PreparedStatement psSec = TestUtil.getPreparedStatement("insert into trade.securities values(?,?,?,?)");
    
    for(int i = 0 ; i < 10;++i) {
      psSec.setInt(1, i);
      psSec.setString(2, "symb"+i);
      psSec.setString(3, "nasdaq");
      psSec.setInt(4, i);
      psSec.executeUpdate();
    }
    
    
    // Stop first server VM
    stopVMNum(-1);
    
    //Now try to add rows in securities which ideally should throw constraint violation    
    for(int i = 0; i <10;++i) {
      psSec.setInt(1, i+10);
      psSec.setString(2, "symb"+i);
      psSec.setString(3, "nasdaq");
      psSec.setInt(4, i);
      
      try {
        psSec.executeUpdate();
        fail("The insert should have thrown exception");
      }catch(SQLIntegrityConstraintViolationException sqle) {
        //ok       
      } catch (SQLException sqle) {
        assertEquals(sqle.getSQLState(), "X0Z09");
      }
      
    }
    
  }
}
