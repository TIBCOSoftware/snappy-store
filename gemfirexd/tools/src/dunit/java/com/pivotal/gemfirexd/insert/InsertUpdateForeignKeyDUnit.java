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
package com.pivotal.gemfirexd.insert;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.util.TestException;

/**
 * Test class for foreign key constraint checks with inserts and updates.
 * 
 * @author rdubey
 * 
 */
@SuppressWarnings("serial")
public class InsertUpdateForeignKeyDUnit extends DistributedSQLTestBase {

  public InsertUpdateForeignKeyDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  /**
   * Test inserts an invalid foreign key.
   */
  public void testInvalidKeyInsert() throws Exception {

    startVMs(1, 1);

    clientSQLExecute(1, "create table t1(id int not null, primary key(id))");
    clientSQLExecute(1,
        "create table t2(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t1(id))");
    clientSQLExecute(1, "insert into t1 values(1)");
    clientSQLExecute(1, "insert into t2 values(1, 1)");

    addExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException" });

    for (int i = 20; i < 30; i++) {
      // all illegal fksl, should use prepared statement.
      checkFKViolation(i % 2 == 0 ? 1 : -1, "insert into t2 values(2, " + i
          + " )");
    }

    removeExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException" });

    clientSQLExecute(1, "drop table t2");
    clientSQLExecute(1, "drop table t1");
  }

  /**
   * Test update to an invalid foreign key.
   */
  public void testInvalidKeyUpdate() throws Exception {

    startClientVMs(1, 0, null);
    startServerVMs(1, 0, "SG1");

    clientSQLExecute(1, "create table t1(id int not null, primary key(id))");
    // avoid default colocation/partitioning else update will fail due to
    // update on partitioning column
    clientSQLExecute(1,
        "create table t2(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t1(id)) "
            + "server groups (sg1)");
    clientSQLExecute(1, "insert into t1 values(1)");
    clientSQLExecute(1, "insert into t2 values(1, 1)");

    addExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException",
        FunctionException.class });

    for (int i = 20; i < 30; i++) {
      checkFKViolation(i % 2 == 0 ? 1 : -1, "update t2 set t2.fkId=" + i
          + " where t2.fkId=1");
    }

    removeExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionException.class, FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException" });

    clientSQLExecute(1, "drop table t2");
    clientSQLExecute(1, "drop table t1");
  }

  /**
   * Test inserts an invalid foreign key.
   */
  public void testInvalidKeyDelete() throws Exception {

    startVMs(1, 1);

    clientSQLExecute(1, "create table t1(id int not null, id2 int not null, primary key(id))");
    clientSQLExecute(1,
        "create table t2(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t1(id))");
    clientSQLExecute(1, "insert into t1 values(1, 1)");
    clientSQLExecute(1, "insert into t2 values(1, 1)");

    addExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException" });

    String sql = "delete from t1 where id = 1";
    String expectedState = "23503";

    Thread t = doUpdateInThread();

    try {
      Connection conn = TestUtil.jdbcConn;
      Statement s = conn.createStatement();
      s.execute(sql);
      fail("should have got a foreign key violation exception for SQL: "
          + sql);
    } catch (SQLException ex) {
      if (!expectedState.equals(ex.getSQLState())) {
        throw ex;
      }
    } catch (RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if (!expectedState.equals(sqlEx.getSQLState())) {
          throw ex;
        }
      } else {
        throw ex;
      }
    }
    t.join();
    removeExpectedException(new int[]{1}, new int[]{1}, new Object[]{
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException"});

    clientSQLExecute(1, "drop table t2");
    clientSQLExecute(1, "drop table t1");
  }

  private Thread doUpdateInThread() throws Exception {
    final Connection conn2 = TestUtil.getConnection();
    final Exception[] tx = new Exception[1];

    Runnable r = new Runnable() {
      @Override
      public void run() {
        for (int i = 0; i < 10000; i++) {
          try {
            Statement st = conn2.createStatement();
            conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
            conn2.setAutoCommit(false);
            st.execute("update t1 set id2=2 where id = 1");
            conn2.commit();
          } catch (SQLException e) {
            e.printStackTrace();
            tx[0] = e;
          }
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
    //t.join();
    if (tx[0] != null) {
      throw tx[0];
    }
    return t;
  }

  /**
   * Test inserts an invalid foreign key. FK pointing to PK of foreign table but
   * foreign table partitioned on some other column.
   */
  public void testInsertForeignTablePartitionedByColumnNotPk()
      throws Exception {

    startVMs(1, 1);

    clientSQLExecute(1,
        "create table t1(id int not null, partitionId int not null, "
            + "primary key(id)) PARTITION BY COLUMN ( partitionId )");
    clientSQLExecute(1,
        "create table t2(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t1(id))");
    clientSQLExecute(1, "insert into t1 values(1, 1)");
    clientSQLExecute(1, "insert into t2 values(1, 1)");

    addExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException",
        FunctionException.class, EntryNotFoundException.class });

    try {
      checkFKViolation(1, "insert into t2 values(2, 2)");
    } finally {
      removeExpectedException(new int[] { 1 }, new int[] { 1 }, new Object[] {
          FunctionException.class, EntryNotFoundException.class,
          FunctionExecutionException.class,
          "java.sql.SQLIntegrityConstraintViolationException" });
      clientSQLExecute(1, "drop table t2");
      clientSQLExecute(1, "drop table t1");
    }
  }

  /**
   * test to check for foreign key constraint on a datastore node having empty
   * accessor for replicate table as the FK table
   */
  public void testServerGroupsReplicatedAccessorFK() throws Exception {
    final int initialCapacity = 10;
    final Properties props = new Properties();
    props.setProperty(Attribute.DEFAULT_INITIAL_CAPACITY_PROP,
        String.valueOf(initialCapacity));
    AsyncVM async1 = invokeStartServerVM(1, 0, "SG1,SG2", props);
    AsyncVM async2 = invokeStartServerVM(2, 0, "SG1", props);
    AsyncVM async3 = invokeStartServerVM(3, 0, "SG2", props);
    startClientVMs(1, 0, null, props);
    joinVMs(true, async1, async2, async3);

    clientSQLExecute(1,
        "create table t1(id int not null, partitionId int not null, "
            + "primary key(id)) replicate server groups (sg1)");
    serverSQLExecute(1,
        "create table t2(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t1(id)) "
            + "server groups (sg1, sg2)");
    serverSQLExecute(2,
        "create table t3(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t2(id)) "
            + "server groups (sg2)");
    serverSQLExecute(3,
        "create table t4(id int not null, fkId int not null, "
            + "primary key(id), foreign key (fkId) references t2(id)) "
            + "server groups (sg1, sg2)");

    // verify region properties
    RegionAttributesCreation replAttrs = new RegionAttributesCreation();
    replAttrs.setDataPolicy(DataPolicy.REPLICATE);
    replAttrs.setScope(Scope.DISTRIBUTED_ACK);
    replAttrs.setInitialCapacity(initialCapacity);
    replAttrs.setConcurrencyChecksEnabled(false);
    replAttrs.setAllHasFields(true);
    replAttrs.setHasDiskDirs(false);
    replAttrs.setHasDiskWriteAttributes(false);

    RegionAttributesCreation emptyAttrs = new RegionAttributesCreation();
    emptyAttrs.setDataPolicy(DataPolicy.EMPTY);
    emptyAttrs.setScope(Scope.DISTRIBUTED_ACK);
    emptyAttrs.setInitialCapacity(initialCapacity);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setAllHasFields(true);
    emptyAttrs.setHasDiskDirs(false);
    emptyAttrs.setHasDiskWriteAttributes(false);
    clientVerifyRegionProperties(1, null, "t1", emptyAttrs);
    serverVerifyRegionProperties(1, null, "t1", replAttrs);
    serverVerifyRegionProperties(2, null, "t1", replAttrs);
    serverVerifyRegionProperties(3, null, "t1", emptyAttrs);

    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    GfxdPartitionResolver resolver = new GfxdPartitionByExpressionResolver();
    PartitionAttributes pattrs = new PartitionAttributesFactory()
        .setPartitionResolver(resolver).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    expectedAttrs.setInitialCapacity(initialCapacity);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);

    emptyAttrs.setHasDiskDirs(false);
    emptyAttrs.setHasDiskWriteAttributes(false);

    emptyAttrs = new RegionAttributesCreation();
    emptyAttrs.setDataPolicy(DataPolicy.PARTITION);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    pattrs = new PartitionAttributesFactory().setPartitionResolver(resolver)
        .setLocalMaxMemory(0).create();
    emptyAttrs.setPartitionAttributes(pattrs);
    emptyAttrs.setInitialCapacity(initialCapacity);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setAllHasFields(true);
    emptyAttrs.setHasScope(false);
    emptyAttrs.setHasDiskDirs(false);
    emptyAttrs.setHasDiskWriteAttributes(false);
    clientVerifyRegionProperties(1, null, "t2", emptyAttrs);
    serverVerifyRegionProperties(1, null, "t2", expectedAttrs);
    serverVerifyRegionProperties(2, null, "t2", expectedAttrs);
    serverVerifyRegionProperties(3, null, "t2", expectedAttrs);

    clientVerifyRegionProperties(1, null, "t3", emptyAttrs);
    serverVerifyRegionProperties(1, null, "t3", expectedAttrs);
    serverVerifyRegionProperties(2, null, "t3", emptyAttrs);
    serverVerifyRegionProperties(3, null, "t3", expectedAttrs);

    String colocatedT2 = '/' + getCurrentDefaultSchemaName() + "/T2";
    pattrs = new PartitionAttributesFactory().setPartitionResolver(resolver)
        .setColocatedWith(colocatedT2).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    pattrs = new PartitionAttributesFactory().setPartitionResolver(resolver)
        .setLocalMaxMemory(0).setColocatedWith(colocatedT2).create();
    emptyAttrs.setPartitionAttributes(pattrs);
    clientVerifyRegionProperties(1, null, "t4", emptyAttrs);
    serverVerifyRegionProperties(1, null, "t4", expectedAttrs);
    serverVerifyRegionProperties(2, null, "t4", expectedAttrs);
    serverVerifyRegionProperties(3, null, "t4", expectedAttrs);

    // some inserts
    clientSQLExecute(1, "insert into t1 values(1, 1)");
    serverSQLExecute(3, "insert into t2 values(1, 1)");
    serverSQLExecute(2, "insert into t3 values(1, 1)");
    serverSQLExecute(1, "insert into t4 values(1, 1)");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        FunctionExecutionException.class,
        "java.sql.SQLIntegrityConstraintViolationException" });

    // trying with large number of keys to ensure that at least some hit
    // the server having accessors for t1
    try {
      for (int key = 2; key <= 200; ++key) {
        String keyStr = String.valueOf(key);
        // check fk violation on a server that has accessor for referenced table
        checkFKViolation(-3, "insert into t2 values(" + keyStr + ", "
            + keyStr + ")");
        // check fk violation on a server that has accessor for insert table
        checkFKViolation(-2, "insert into t3 values(" + keyStr + ", "
            + keyStr + ")");
        // check fk violation on a server that is datastore for all tables
        checkFKViolation(-1, "insert into t4 values(" + keyStr + ", "
            + keyStr + ")");
        // check fk violation on client
        checkFKViolation(1, "insert into t3 values(" + keyStr + ", " + keyStr
            + ")");

        // same checks with updates
        checkFKViolation(-3, "update t2 set fkId=" + keyStr + " where id=1");
        checkFKViolation(-3, "update t2 set fkId=" + keyStr + " where fkId=1");
        checkFKViolation(-2, "update t3 set fkId=" + keyStr + " where id=1");
        checkFKViolation(-2, "update t3 set fkId=" + keyStr + " where fkId=1");
        checkFKViolation(-1, "update t3 set fkId=" + keyStr + " where id=1");
        checkFKViolation(-1, "update t3 set fkId=" + keyStr + " where fkId=1");
        checkFKViolation(1, "update t2 set fkId=" + keyStr + " where id=1");
        checkFKViolation(1, "update t2 set fkId=" + keyStr + " where fkId=1");
      }
    } finally {
      removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          new Object[] { FunctionExecutionException.class,
              "java.sql.SQLIntegrityConstraintViolationException" });
    }

    // check no fk violation
    for (int key = 2; key <= 200; ++key) {
      String keyStr = String.valueOf(key);
      clientSQLExecute(1, "insert into t1 values(" + keyStr + ", " + keyStr
          + ")");
      serverSQLExecute(3, "insert into t2 values(" + keyStr + ", " + keyStr
          + ")");
      serverSQLExecute(2, "update t2 set fkId=" + keyStr + " where id=1");
      serverSQLExecute(2, "insert into t4 values(" + keyStr + ", " + keyStr
          + ")");
      serverSQLExecute(1, "update t3 set fkId=" + keyStr + " where id=1");
      serverSQLExecute(3, "insert into t3 values(" + keyStr + ", " + keyStr
          + ")");
    }
  }

  /**
   * test that produced 40171.
   */
  public void test40171() throws Exception {
    startVMs(1, 1);
    Connection conn = TestUtil.jdbcConn;
    Statement s = conn.createStatement();

    s.execute("create schema trade");// default server groups (SG1)");
    s.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid)) replicate");

    s.execute("insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)");

    ResultSet rs = s
        .executeQuery("select * from trade.customers where since=1");

    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      assertEquals("XXXX1", rs.getString(2).trim());
      assertEquals(1, rs.getInt(3));
      assertEquals("BEAV1", rs.getString(4));
      assertEquals(1, rs.getInt(5));
    }

    int numUpdate = s
        .executeUpdate("update trade.customers set since=2 where cid=1");

    assertEquals("Should update one row", 1, numUpdate);
    rs = s.executeQuery("select * from trade.customers where since=1");
    while (rs.next()) {
      getLogWriter().info("XXXX col1 : " + rs.getInt(1) + " #2 : "
          + rs.getString(2).trim() + " #3 : " + rs.getInt(3) + " #4 "
          + rs.getString(4) + " #5 : " + rs.getInt(5));
      throw new TestException("Should not return any rows");
    }
  }
  
  /**
   * Test for 40234 (Performing updates on all indexes irrespective of 
   * index keys being updated or not)
   */
  public void test40234() throws Exception{
    startVMs(1, 1);
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, " +
    		"symbol varchar(10) not null, price decimal (30, 20), " +
    		"exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), " +
    		"constraint sec_uq unique (symbol, exchange), " +
    		"constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  ");
    
    clientSQLExecute(1,"create table trade.customers (cid int not null, " +
    		"cust_name varchar(100), since date, addr varchar(100), " +
    		"tid int, primary key (cid))");
    
    clientSQLExecute( 1, "create table trade.buyorders(oid int not null constraint buyorders_pk primary key, " +
    		"cid int, sid int, qty int, bid decimal (30, 20), " +
    		"ordertime timestamp, status varchar(10), tid int, " +
    		"constraint bo_cust_fk foreign key (cid) references trade.customers (cid), " +
    		"constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id), " +
    		"constraint bo_qty_ck check (qty>=0))");
    
    clientSQLExecute(1,"insert into trade.securities values (1,'IBM', " +
    		"25.25, 'nasdaq', 5)");
    java.sql.Date since = new java.sql.Date(System.currentTimeMillis());
    Connection conn = TestUtil.jdbcConn;
    PreparedStatement psInsertCust = conn.prepareStatement("insert into trade.customers values (?,?,?,?,?)");
    psInsertCust.setInt(1, 1);
    psInsertCust.setString(2, "XXXX1");
    psInsertCust.setDate(3, since);
    psInsertCust.setString(4, "XXXX1");
    psInsertCust.setInt(5,1);
    psInsertCust.executeUpdate();
    
    PreparedStatement psInsertBuy = conn.prepareStatement("insert into trade.buyorders values (?,?,?,?,?,?,?,?)");
    BigDecimal value = new BigDecimal (Double.toString((new Random().nextInt(10000)+1) * .01));
    java.sql.Timestamp tstmp = new java.sql.Timestamp(System.currentTimeMillis());
    psInsertBuy.setInt(1,1);
    psInsertBuy.setInt(2,1);
    psInsertBuy.setInt(3,1);
    psInsertBuy.setInt(4, 10);
    psInsertBuy.setBigDecimal(5, value);
    psInsertBuy.setTimestamp(6, tstmp);
    psInsertBuy.setString(7, "open");
    psInsertBuy.setInt(8, 1);
    psInsertBuy.executeUpdate();
   // clientSQLExecute(1, "insert into trade.buyorders(1, 1, 1, 10, "+
   //     new BigDecimal (Double.toString((new Random().nextInt(10000)+1) * .01))+", ' " +
   //     		""+new java.sql.Timestamp(System.currentTimeMillis())+" ', 'open', 1)");
    PreparedStatement psUpdateBuy = conn.prepareStatement("update trade.buyorders set status = 'cancelled' " +
    		"where (ordertime >? or sid=?) and status = 'open' and tid =?");
    psUpdateBuy.setTimestamp(1, tstmp);
    psUpdateBuy.setInt(2, 1);
    psUpdateBuy.setInt(3,1);
    int numUpdate = psUpdateBuy.executeUpdate();
    assertEquals("Should update one row",1, numUpdate);
    
  }
  
  /**
   * Test index scan on multivm queries on tables with redundancy.
   * @throws Exception
   */
  public void testIndexScans () throws Exception {
    startVMs(2, 2);
    Connection conn = TestUtil.jdbcConn;
    Statement s = conn.createStatement();
    PreparedStatement psInsertBrokerTickets = null;
    
    s.execute("Create table broker_tickets (id int not null, ticketPrice int not null ," +
        " firmId int not null ,  price double, quantity int, ticker varchar(20)) " +
        " redundancy 1");
    
    psInsertBrokerTickets = conn.prepareStatement("insert into broker_tickets " +
        "(id, ticketPrice, firmId,  price, quantity, ticker ) " +
        "values (?, ?, ?, ?, ?, ?)");
    
    int numInserts = 1000;
    int key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts; i++) {
      psInsertBrokerTickets.setInt(1, key + 10);
      if ((i % 2) == 0) {
        psInsertBrokerTickets.setInt(2, 10);
      } else {
        psInsertBrokerTickets.setInt(2, 20);
      }
      psInsertBrokerTickets.setInt(3, key + 10);
      psInsertBrokerTickets.setDouble(4, price);
      psInsertBrokerTickets.setInt(5, quantity);
      psInsertBrokerTickets.setString(6, "YYYY" + key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals("Insert should return 1.", 1, rt) ; 
      key += 10;
      price += 0.01;

    }
    s.execute("create index index_ticketPrice on broker_tickets (ticketPrice)");
    ResultSet rs = s.executeQuery("select avg(distinct ticketPrice) from " +
        "broker_tickets");
    while (rs.next()) {
      assertEquals("Result should match ", 15, rs.getInt(1));
    }
       
  }

  /**
   * Test foreign key constraint with null values for FK field.
   * 
   * @throws Exception
   */
  public void testBug41168() throws Exception {

    // Start two clients and three servers
    startVMs(2, 3);

    // Create the table with self-reference FKs
    clientSQLExecute(1, "create table BinaryTree (id int primary key, "
        + "leftId int, rightId int, depth int not null,"
        + " foreign key (leftId) references BinaryTree(id),"
        + " foreign key (rightId) references BinaryTree(id)) replicate");

    doBinaryTreeChecks(true);

    // now do the same for partitioned table
    serverSQLExecute(2, "drop table BinaryTree");
    clientSQLExecute(1, "create table BinaryTree (id int primary key, "
        + "leftId int, rightId int, depth int not null,"
        + " foreign key (leftId) references BinaryTree(id),"
        + " foreign key (rightId) references BinaryTree(id))");

    doBinaryTreeChecks(false);
  }

  private void doBinaryTreeChecks(final boolean doSelfJoins) throws Exception {
    // some inserts with null values
    clientSQLExecute(2, "insert into BinaryTree values (3, null, null, 2)");
    serverSQLExecute(1, "insert into BinaryTree values (2, null, null, 1)");
    serverSQLExecute(2, "insert into BinaryTree values (1, 3, null, 1)");
    serverSQLExecute(3, "insert into BinaryTree values (0, 1, 2, 0)");

    // test FK violation for the self FK
    checkFKViolation(1, "insert into BinaryTree values (4, 5, null, 2)");
    checkFKViolation(2, "insert into BinaryTree values (5, null, 4, 2)");
    checkFKViolation(-1, "update BinaryTree set leftId=4 where id=2");
    checkFKViolation(-2, "update BinaryTree set rightId=5 where id=2");

    // some inserts with inserted row being the one that satisfies the
    // constraint (#43159)    
    clientSQLExecute(2, "insert into BinaryTree values (10, 10, 10, 3)");
    serverSQLExecute(1, "insert into BinaryTree values (11, 10, 11, 4)");
    serverSQLExecute(2, "insert into BinaryTree values (12, 11, 12, 3)");
    serverSQLExecute(3, "insert into BinaryTree values (13, 13, 3, 4)");

    // now update the tree with two proper nodes
    serverSQLExecute(3, "insert into BinaryTree values (4, null, null, 2)");
    serverSQLExecute(2, "insert into BinaryTree values (5, null, null, 2)");
    serverSQLExecute(1, "update BinaryTree set leftId=4 where id=2");
    clientSQLExecute(2, "update BinaryTree set rightId=5 where id=2");

    // run a self-join query and verify the results
    Statement stmt = TestUtil.jdbcConn.createStatement();
    ResultSet rs;
    if (doSelfJoins) {
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 3");
      assertTrue("expected one result", rs.next());
      assertEquals(1, rs.getInt(1));
      assertFalse("expected one result", rs.next());
    }

    // finally check that delete works for null FK column rows as well and
    // verify the results again
    addExpectedException(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        SQLIntegrityConstraintViolationException.class);
    checkFKViolation(-2, "delete from BinaryTree where id=4");
    checkFKViolation(2, "delete from BinaryTree where id=5");
    removeExpectedException(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        SQLIntegrityConstraintViolationException.class);

    // check the deletes of "self-referential" rows
    serverSQLExecute(1, "delete from BinaryTree where id=13");
    checkFKViolation(1, "delete from BinaryTree where id=11");
    serverSQLExecute(2, "delete from BinaryTree where id=12");
    checkFKViolation(-3, "delete from BinaryTree where id=10");

    try {
      clientSQLExecute(1, "update BinaryTree set depth=null where id=2");
    } catch (SQLException sqle) {
      assertEquals(sqle.toString(), "23502", sqle.getSQLState());
    }
    serverSQLExecute(3, "update BinaryTree set leftId=null where id=2");
    clientSQLExecute(2, "update BinaryTree set rightId=null where id=2");
    serverSQLExecute(1, "delete from BinaryTree where id=5");
    serverSQLExecute(2, "delete from BinaryTree where id=4");
    if (doSelfJoins) {
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 4");
      assertFalse("expected no result", rs.next());
      rs = stmt.executeQuery("select t1.id from BinaryTree t1, "
          + "BinaryTree t2 where t1.leftId = t2.id and t2.id = 5");
      assertFalse("expected no result", rs.next());
    }
  }

  public void testBug43104() throws Exception {
    if (isTransactional) {
      return;
    }
    startVMs(1, 1);
    Connection conn = TestUtil.jdbcConn;
    Statement s = conn.createStatement();

    s.execute("create table app.customers (cid int not null, "
        + "cust_name varchar(100), tid int, "
        + "primary key (cid)) redundancy 1");
    s.execute("create table app.portfolio (cid int not null, tid int, "
        + "primary key (cid), constraint pf_cust_fk"
        + " foreign key (cid) references app.customers (cid)) redundancy 1 ");

    s.execute("insert into app.customers values (1, 'XXXX1',1)");
    startServerVMs(1, 0, null);
    Thread.sleep(5000);
    // now insert hook on server VM1 on customer partitoned region.
    SerializableRunnable cacheCloser = new SerializableRunnable(
        "CacheCloser") {
      @Override
      public void run() throws CacheException {
        try {
          PartitionedRegion pr = (PartitionedRegion)Misc
              .getRegionForTable("APP.CUSTOMERS", true);

          pr.getDataStore().setBucketReadHook(new Runnable() {
            @Override
            public void run() {
              Misc.getGemFireCache().close("", null, false, true);
            }
          });
        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    // install the hook on server 1
    serverExecute(1, cacheCloser);
    // now insert into portfolio
    s.execute("insert into app.portfolio values (1, 1)");
  }
}
