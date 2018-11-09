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

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.client.am.SingleHopPreparedStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;

public class SingleHopTest extends JdbcTestBase {

  public SingleHopTest(String name) {
    super(name);
  }

  @SuppressWarnings("serial")
  public static class RoutingKeysObserver extends GemFireXDQueryObserverAdapter {

    private Set<Object> routingKeys;
    
    @Override
    public void setRoutingObjectsBeforeExecution(Set<Object> routingKeys) {
      this.routingKeys = routingKeys;
    }

    public Set<Object> getRoutingKeys() {
      return this.routingKeys;
    }

    @Override
    public void reset() {
      this.routingKeys = null;
    }
    
    public void afterSingleRowInsert(Object routingObj) {
      if (this.routingKeys == null) {
        this.routingKeys = new HashSet<Object>();
      }
      this.routingKeys.add(routingObj);
    }
  }

  public void test0MaxConnectionsAndSetSchema() throws Exception {
    System.setProperty("snappydata.client.single-hop-max-connections", "100");
    com.pivotal.gemfirexd.internal.client.am.Connection.initialize();
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    conn.createStatement().execute("create schema trade");
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    assertEquals(100, com.pivotal.gemfirexd.internal.client.am.Connection.SINGLE_HOP_MAX_CONN_PER_SERVER);
    Statement s = connShop.createStatement();
    s.execute("set current schema trade");
    s.execute("create table example(c1 int not null, c2 int not null primary key) partition by primary key");
    s.execute("insert into trade.example values(1, 2), (2, 3)");
    PreparedStatement ps = connShop.prepareStatement("select * from example where c2 = ?");
    ps.setInt(1, 2);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    rs.next();
    assertEquals(2, rs.getInt(2));
    System.clearProperty("snappydata.client.single-hop-max-connections");
    com.pivotal.gemfirexd.internal.client.am.Connection.initialize();
  }

  public void testexcnIdDebug() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    conn.createStatement().execute("create schema trade");
    Properties props = new Properties();
    Connection tc = startNetserverAndGetLocalNetConnection(props);
    tc.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement s = tc.createStatement();
    s.execute("set current schema trade");
    s.execute("create table example(c1 int not null, c2 int not null primary key) partition by primary key");
    s.execute("insert into trade.example values(1, 2), (2, 3)");
    PreparedStatement ps = tc.prepareStatement("select * from example where c2 = ?");
    ps.setInt(1, 2);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    rs.next();
    assertEquals(2, rs.getInt(2));
    System.clearProperty("snappydata.client.single-hop-max-connections");
    
    s.execute("select * from example");
    s.execute("update example set c1 = 100 where c2 = 200");
    tc.commit();
    // Thread.sleep(60 * 1000);
    // tc.rollback();
  }
  
  public void testDebug() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    // System.setProperty("gemfirexd.drda.debug", "true");
    System
        .setProperty("gemfirexd.debug.true", "TraceSingleHop,QueryDistribution");
    DMLQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    Statement s = conn.createStatement();
    String ddl = "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint "
        + "sec_pk primary key (sec_id), constraint sec_uq unique "
        + "(symbol, exchange), constraint exc_ch check (exchange in "
        + "('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + " partition by column (sec_id, price)  REDUNDANCY 2";

    s.execute(ddl);

    ddl = "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty))"
        + "  partition by range (cid) "
        + "( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, "
        + "VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, "
        + "VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)  REDUNDANCY 2";
    s.execute(ddl);

    s.execute("insert into trade.securities values "
        + "(1204, 'lo', 33.9, 'hkse', 53)");

    s.execute("insert into trade.portfolio values (508, 1204, 1080, 1080, "
        + "36612.00000000000000000000, 100)");

    //String updStmnt = "update trade.portfolio set subTotal=? * qty "
    //    + "where cid = ? and sid = ?";

    String selStmnt = "select * from trade.securities where sec_id = ?";
    PreparedStatement ps2 = connShop.prepareStatement(selStmnt);
    ps2.setInt(1, 1204);
    ResultSet rs = ps2.executeQuery();
    assertTrue(rs.next());
    BigDecimal price = rs.getBigDecimal(3);
    assertFalse(rs.next());

    String updStmnt2 = "update trade.portfolio set subTotal = ? * qty  where cid = ? and sid = ?  and tid= ?";
    PreparedStatement ps = connShop.prepareStatement(updStmnt2);
    ps.setBigDecimal(1, price);
    // ps.setBigDecimal(1, new BigDecimal("33.9"));
    ps.setInt(2, 508);
    ps.setInt(3, 1204);
    ps.setInt(4, 53);
    ps.executeUpdate();
    
    //((com.pivotal.gemfirexd.internal.client.am.PreparedStatement)ps).prepare();
    //((com.pivotal.gemfirexd.internal.client.am.PreparedStatement)ps).flowExecute(4);
    System.out.println(ps.executeUpdate());
    System.out.println(ps.executeUpdate());
    System.out.println(ps.executeUpdate());
    System.out.println(ps.executeUpdate());
  }

  // This is being made non hoppable for 1.0.2 as proper code to handle this
  // type
  // need to be added.
  public void testHoppForTheSelectCaseGiven() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    // System.setProperty("gemfirexd.drda.debug", "true");
    System
        .setProperty("gemfirexd.debug.true", "TraceSingleHop,QueryDistribution");
    DMLQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    // props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    // Connection connNet = startNetserverAndGetLocalNetConnection(props);
    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();

    String ddl = "create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))";
    String selstmnt = "select type, id, name from t1 where id IN (?,?,?) AND name IN (?,?,?)";

    s.execute(ddl);

    connShop.prepareStatement(selstmnt);
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_VarChar_maxwidth()
      throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(10) NOT NULL,"
        + " SECONDID varchar(40) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', "
        + "'zero'), ('one1', 'one1', 'one'), ('two2', 'two2', 'two'), "
        + "('tree', 'tree', 'three'), ('two2two2tw', 'two2two2two2', 'two2two2tw')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "two2two2two2");
    psimple.execute();
    ResultSet rs = psimple.getResultSet(); 
    assertFalse(rs.next()); 

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2two2two2");
    pshop.execute();

    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    String selstmnt_const =
        "select * from EMP.PARTITIONTESTTABLE where id = 'two2two2two2'";
    psimple = conn.prepareStatement(selstmnt_const);
    psimple.execute();

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt_const);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.execute();

    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();
    compareSetEqulas(rkeys, shopset, false);
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_Integer() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // partition by primary key

    ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY PRIMARY KEY";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), "
        + "(1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID int,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.INTEGER);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setInt(2, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.INTEGER);
    pshop.setInt(2, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setInt(1, 2);
    psimple.setInt(2, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.setInt(2, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(2, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID int,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setInt(1, 90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.INTEGER);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID int,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setInt(1, 90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.INTEGER);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID int,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setInt(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_LongInt() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint NOT NULL,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setLong(1, 2);
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2);
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while (rs.next()) {

    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint NOT NULL,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setLong(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.BIGINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setLong(2, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.BIGINT);
    pshop.setLong(2, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setLong(1, 2);
    psimple.setLong(2, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2);
    pshop.setLong(2, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(2, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setLong(1, 2L);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2L);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setLong(1, 90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.BIGINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setLong(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setLong(1, 90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.BIGINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID bigint,"
        + " SECONDID bigint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setLong(1, 2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setLong(1, 2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());
    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_SmallInt() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint NOT NULL,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setShort(1, (short)10);
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)10);
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint NOT NULL,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), "
        + "(1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setShort(1, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    // assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.SMALLINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setShort(2, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.SMALLINT);
    pshop.setShort(2, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    assertEquals(0, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setShort(1, (short)2);
    psimple.setShort(2, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)2);
    pshop.setShort(2, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    // assertEquals(2, shopset.iterator().next());
    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setShort(1, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setShort(1, (short)90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.SMALLINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setShort(1, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setShort(1, (short)90);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)90);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.SMALLINT);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID smallint,"
        + " SECONDID smallint not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setShort(1, (short)2);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setShort(1, (short)2);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_Decimal() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10) NOT NULL,"
        + " SECONDID decimal(20, 10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setBigDecimal(1, BigDecimal.valueOf(2.22));
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2.22));
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10) NOT NULL,"
        + " SECONDID decimal(20, 10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), "
        + "(1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setBigDecimal(1, BigDecimal.valueOf(2.22));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2.22));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    // assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10),"
        + " SECONDID decimal(20, 10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DECIMAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setBigDecimal(2, BigDecimal.valueOf(2.22));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DECIMAL);
    pshop.setBigDecimal(2, BigDecimal.valueOf(2.22));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setBigDecimal(1, BigDecimal.valueOf(2.22));
    psimple.setBigDecimal(2, BigDecimal.valueOf(2.22));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2.22));
    pshop.setBigDecimal(2, BigDecimal.valueOf(2.22));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10),"
        + " SECONDID decimal(20, 10), THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setBigDecimal(1, BigDecimal.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setBigDecimal(1, BigDecimal.valueOf(9.99));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(9.99));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DECIMAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10),"
        + " SECONDID decimal(20, 10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setBigDecimal(1, BigDecimal.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setBigDecimal(1, BigDecimal.valueOf(9));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(9));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DECIMAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID decimal(20, 10),"
        + " SECONDID decimal(20, 10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setBigDecimal(1, BigDecimal.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setBigDecimal(1, BigDecimal.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_Double() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID double NOT NULL,"
        + " SECONDID double not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID double NOT NULL,"
        + " SECONDID double not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), "
        + "(1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    // assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID double,"
        + " SECONDID double not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DOUBLE);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setDouble(2, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DOUBLE);
    pshop.setDouble(2, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setDouble(1, Double.valueOf(2));
    psimple.setDouble(2, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.setDouble(2, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID double,"
        + " SECONDID double, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setDouble(1, Double.valueOf(100));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(100));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DOUBLE);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID double,"
        + " SECONDID double not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setDouble(1, Double.valueOf(9));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(9));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.DOUBLE);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID double,"
        + " SECONDID double not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setDouble(1, Double.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_Real() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID real NOT NULL,"
        + " SECONDID real not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setFloat(1, Float.valueOf("2.22"));
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf("2.22"));
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID real NOT NULL,"
        + " SECONDID real not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), "
        + "(1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setDouble(1, Double.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf("2.22"));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    // assertEquals(2, shopset.iterator().next());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID real,"
        + " SECONDID real not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0.0, 0.0, 'zero'), (1.11, 1.11, 'one'), (2.22, 2.22, 'two'), (3.333, 3.333, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.REAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());
    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setFloat(2, Float.valueOf("2.22"));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.REAL);
    pshop.setFloat(2, Float.valueOf("2.22"));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setFloat(1, Float.valueOf("2.22"));
    psimple.setFloat(2, Float.valueOf("2.22"));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf("2.22"));
    pshop.setFloat(2, Float.valueOf("2.22"));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID real,"
        + " SECONDID real, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 0 and 1, values between 1 and 2, values between 2 and 3)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setFloat(1, Float.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setFloat(1, Float.valueOf(200));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf(200));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.REAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID real,"
        + " SECONDID real not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES (0, 10), VALUES (20, 70), VALUES (1, 100) )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setFloat(1, Float.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setFloat(1, Float.valueOf(200));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf(200));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.REAL);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID real,"
        + " SECONDID real not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (ID + 4)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values (0, 0, 'zero'), (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setFloat(1, Float.valueOf(2));
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setFloat(1, Float.valueOf(2));
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_Char() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID char(4) NOT NULL,"
        + " SECONDID char(4) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), ('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "two2");
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(4) NOT NULL,"
        + " SECONDID char(4) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), "
        + "('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(4),"
        + " SECONDID char(4) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), ('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.CHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setString(2, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.CHAR);
    pshop.setString(2, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setString(1, "two2");
    psimple.setString(2, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.setString(2, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(2),"
        + " SECONDID char(2), THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 'AA' and 'BB', values between 'BB' and 'CC', values between 'CC' and 'DD')";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "BB");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "BB");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.CHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(2),"
        + " SECONDID char(2) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES ('AA', 'aa'), VALUES ('BB', 'bb'), VALUES ('CC', 'cc') )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "BB");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "BB");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with list ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.CHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(2),"
        + " SECONDID char(2) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (trim(ID))";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  @SuppressWarnings("unchecked")
  public void testRoutingObjectCompatibility_VarChar() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }

    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    // single column partitioning key
    Statement s = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(10) NOT NULL,"
        + " SECONDID varchar(40) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), ('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    PreparedStatement psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "two2");
    psimple.execute();
    psimple.close();

    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.execute();
    ResultSet rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    compareSetEqulas(rkeys, shopset, false);
    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(40) NOT NULL,"
        + " SECONDID varchar(40) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), "
        + "('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------------------------- null value
    // -----------------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(10),"
        + " SECONDID varchar(10) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('zero', 'zero', 'zero'), ('one1', 'one1', 'one'), ('two2', 'two2', 'two'), ('tree', 'tree', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.VARCHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // -------------------------------- and condition with one more int
    // --------------------------------

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ? and secondid = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setNull(1, Types.NULL);
    psimple.setString(2, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.VARCHAR);
    pshop.setString(2, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    psimple.setString(1, "two2");
    psimple.setString(2, "two2");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "two2");
    pshop.setString(2, "two2");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    s.execute("drop table EMP.PARTITIONTESTTABLE");
    // -------------- range resolver
    // --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID char(2),"
        + " SECONDID char(2), THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY RANGE (ID) (values between 'AA' and 'BB', values between 'BB' and 'CC', values between 'CC' and 'DD')";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "BB");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "BB");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of range

    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.VARCHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- list resolver --------------------------------------------
    ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(20),"
        + " SECONDID varchar(20) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY LIST (ID) ( VALUES ('AA', 'aa'), VALUES ('BB', 'bb'), VALUES ('CC', 'cc') )";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "BB");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "BB");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // one out of list

    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();

    // ------------------ null with range ----------------------------
    psimple.setNull(1, Types.NULL);
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setNull(1, Types.VARCHAR);
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(1, shopset.size());

    compareSetEqulas(rkeys, shopset, false);

    rkeys.clear();
    shopset.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");

    // -------------- partition by expression should not go single hop.
    ddl = "create table EMP.PARTITIONTESTTABLE (ID varchar(20),"
        + " SECONDID varchar(20) not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY (trim(lower(ID)))";
    s.execute(ddl);

    s.execute("insert into EMP.PARTITIONTESTTABLE values ('AA', 'AA', 'zero'), ('BB', 'BB', 'one'), ('CC', 'CC', 'two'), ('DD', 'DD', 'three')");

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";
    psimple = conn.prepareStatement(selstmnt);
    psimple.setString(1, "FF");
    psimple.execute();

    rs = psimple.getResultSet();
    while (rs.next()) {

    }

    rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "FF");
    pshop.execute();
    rs = pshop.getResultSet();
    while(rs.next()) {
      
    }
    shopset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();

    assertEquals(0, shopset.size());

    rkeys.clear();
    s.execute("drop table EMP.PARTITIONTESTTABLE");
  }

  public static void compareSetEqulas(Set<Object> rkeys, Set<Object> shopset,
      boolean anyorder) {
    if (rkeys == null) {
      assertNull(shopset);
      return;
    }
    else {
      assertNotNull(shopset);
      assertEquals(rkeys.size(), shopset.size());
    }
    if (!anyorder) {
      for (int i = 0; i < shopset.size(); i++) {
        assertEquals(rkeys.toArray()[i], shopset.toArray()[i]);
        System.out.println(shopset.toArray()[i]);
      }
    }
    else {
      for (Object rk : rkeys) {
        assertTrue(shopset.contains(rk));
      }
    }
  }

  @SuppressWarnings("unchecked")
  public void testUndoableSingleHopCases() throws Exception {
    // Bug #51846
    if (isTransactional) {
      return;
    }
    
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);

    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID int not null,"
        + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";

    PreparedStatement ps = connShop.prepareStatement(ddl);
    ps.execute();

    Set<Object> rset = ((SingleHopPreparedStatement)ps).getRoutingObjectSet();
    assertNull(rset);

    String insertSQL = "insert into EMP.PARTITIONTESTTABLE values (?, ?, ?)";
    ps = connShop.prepareStatement(insertSQL);

    for (int i = 0; i < 3; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setInt(3, i);
      int cnt = ps.executeUpdate();
      assertEquals(1, cnt);
    }

    Statement s = connShop.createStatement();
    s.execute("select * from EMP.PARTITIONTESTTABLE");
    ResultSet rs = s.getResultSet();

    while (rs.next()) {
      System.out.println(rs.getInt(1) + ", " + rs.getInt(2) + ", "
          + rs.getInt(3));
    }

    rset = ((SingleHopPreparedStatement)ps).getRoutingObjectSet();
    assertNull(rset);

    String selstmnt = "select * from EMP.PARTITIONTESTTABLE where id = ?";

    PreparedStatement pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 2);
    pshop.execute();

    rset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();
    assertNotNull(rset);
    ((SingleHopPreparedStatement)pshop).setRoutingObjectSetToNull();

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id IN (2, ?, ?)";

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 0);
    pshop.setInt(2, 10);
    pshop.execute();

    rset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();
    assertNotNull(rset);
    ((SingleHopPreparedStatement)pshop).setRoutingObjectSetToNull();

    rs = pshop.getResultSet();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(2, cnt);

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id > ? and id < ?";

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 0);
    pshop.setInt(2, 2);
    pshop.execute();

    rset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();
    assertEquals(0, rset.size());
    rs = pshop.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(1, cnt);

    selstmnt = "select * from EMP.PARTITIONTESTTABLE where id > ? or id = ?";

    pshop = connShop.prepareStatement(selstmnt);
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setInt(1, 1);
    pshop.setInt(2, 0);
    pshop.execute();

    rset = ((SingleHopPreparedStatement)pshop).getRoutingObjectSet();
    assertEquals(0, rset.size());

    rs = pshop.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(2, cnt);

  }

  @SuppressWarnings("unchecked")
  public void testBug47520() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    Statement st = connShop.createStatement();
    ResultSet rs = null;

    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    st
        .execute("create table trade.securities(sid int not null, symbol varchar(10), exchange varchar(10), "
            + "constraint sec_pk primary key (sid), constraint sec_uq unique (symbol, exchange)) "
            + "partition by primary key");
    st
        .execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companyname char(10), tid int, "
            + "constraint comp_pk primary key (symbol, exchange), "
            + "constraint comp_fk foreign key (symbol, exchange) references trade.securities (symbol, exchange) on delete restrict) "
            + "partition by column (companyname)");
    st.execute("insert into trade.securities values (1, 'zv0', 'tse')");
    st.execute("insert into trade.securities values (2, 'cv1', 'fse')");
    st.execute("create index indexcompaniestid on trade.companies (tid)");
    st.execute("insert into trade.companies values ('zv0', 'tse', 'abc', 1)");
    st.execute("insert into trade.companies values ('cv1', 'fse', 'def', 1)");

    // non-single-hop execution
    PreparedStatement ps = conn
        .prepareStatement("select * from trade.companies where companyname = ? and tid = ?");
    ps.setString(1, "abc");
    ps.setInt(2, 1);
    rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals("zv0", rs.getString(1));

    // get the routing object of non-single-hop execution
    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    // single-hop execution
    PreparedStatement pshop = connShop
        .prepareStatement("select * from trade.companies where companyname = ? and tid = ?");
    ((SingleHopPreparedStatement)pshop).createNewSetForTesting();
    pshop.setString(1, "abc");
    pshop.setInt(2, 1);
    rs = pshop.executeQuery();
    assertTrue(rs.next());
    assertEquals("zv0", rs.getString(1));

    // get the routing object of single-hop execution
    Set<Object> shopset = ((SingleHopPreparedStatement)pshop)
        .getRoutingObjectSet();

    // compare routing objects of non-single-hop and single-hop executions
    compareSetEqulas(rkeys, shopset, false);
  }
  
  @SuppressWarnings("unchecked")
  public void testBug48416_PartitionByList() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    
    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    st.execute("create table app.m1 (col1 int primary key , col2 int, col3 smallint, col4 int) "
            + "partition by list (col3) (VALUES (0, 1), VALUES (2), VALUES (3), VALUES (5, 7))");

    st.execute("create index app.idx1 on app.m1(col2)");

    st.execute("insert into app.m1 values (1, 2, 3, 4)");

    st.execute("insert into app.m1 values (3, 4, 5, 6)");
    
    // non-single-hop execution
    PreparedStatement ps = conn
        .prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    int ret = ps.executeUpdate();
    assertEquals(1, ret);
    // get the routing object of non-single-hop execution
    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    // single-hop execution
    ps = connShop.prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ((SingleHopPreparedStatement) ps).createNewSetForTesting();
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    ret = ps.executeUpdate();
    assertEquals(1, ret);
    
    // get the routing object of single-hop execution
    Set<Object> shopset = ((SingleHopPreparedStatement)ps)
        .getRoutingObjectSet();

    // compare routing objects of non-single-hop and single-hop executions
    compareSetEqulas(rkeys, shopset, false);

  }

  
  @SuppressWarnings("unchecked")
  public void testBug48416_PartitionByRange() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    
    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    st.execute("create table app.m1 (col1 int primary key , col2 int, col3 smallint, col4 int) "
        + "partition by range (col3) (values between 0 and 2, values between 3 and 5, values between 6 and 7)");

    st.execute("create index app.idx1 on app.m1(col2)");

    st.execute("insert into app.m1 values (1, 2, 3, 4)");

    st.execute("insert into app.m1 values (4, 5, 6, 7)");
    
    // non-single-hop execution
    PreparedStatement ps = conn
        .prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    int ret = ps.executeUpdate();
    assertEquals(1, ret);
    // get the routing object of non-single-hop execution
    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    // single-hop execution
    ps = connShop.prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ((SingleHopPreparedStatement) ps).createNewSetForTesting();
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    ret = ps.executeUpdate();
    assertEquals(1, ret);
    
    // get the routing object of single-hop execution
    Set<Object> shopset = ((SingleHopPreparedStatement)ps)
        .getRoutingObjectSet();

    // compare routing objects of non-single-hop and single-hop executions
    compareSetEqulas(rkeys, shopset, false);

  }
  
  @SuppressWarnings("unchecked")
  public void testBug48416_PartitionByColumn() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    
    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    st.execute("create table app.m1 (col1 int primary key , col2 int, col3 smallint, col4 int) "
            + "partition by column (col3) ");

    st.execute("create index app.idx1 on app.m1(col2)");

    st.execute("insert into app.m1 values (1, 2, 3, 4)");

    st.execute("insert into app.m1 values (3, 4, 5, 6)");
    
    // non-single-hop execution
    PreparedStatement ps = conn
        .prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    int ret = ps.executeUpdate();
    assertEquals(1, ret);
    // get the routing object of non-single-hop execution
    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    // single-hop execution
    ps = connShop.prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ((SingleHopPreparedStatement) ps).createNewSetForTesting();
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    ret = ps.executeUpdate();
    assertEquals(1, ret);
    
    // get the routing object of single-hop execution
    Set<Object> shopset = ((SingleHopPreparedStatement)ps)
        .getRoutingObjectSet();

    // compare routing objects of non-single-hop and single-hop executions
    compareSetEqulas(rkeys, shopset, false);

  }
  
  @SuppressWarnings("unchecked")
  public void testBug48416_PartitionByExpression() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    Properties props = new Properties();
    props.setProperty("single-hop-enabled", "true");
    props.setProperty("single-hop-max-connections", "5");
    Connection connShop = startNetserverAndGetLocalNetConnection(props);
    
    RoutingKeysObserver observer = new RoutingKeysObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    st.execute("create table app.m1 (col1 int primary key , col2 int, col3 smallint, col4 int) "
            + "partition by (col3 + 3) ");

    st.execute("create index app.idx1 on app.m1(col2)");

    st.execute("insert into app.m1 values (1, 2, 3, 4)");

    st.execute("insert into app.m1 values (3, 4, 5, 6)");
    
    // non-single-hop execution
    PreparedStatement ps = conn
        .prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    int ret = ps.executeUpdate();
    assertEquals(1, ret);
    // get the routing object of non-single-hop execution
    Set<Object> rkeys = observer.getRoutingKeys();
    assertEquals(1, rkeys.size());

    // single-hop execution
    ps = connShop.prepareStatement("update app.m1 set col4 = ? where col2 = ? and col3 = ?");
    ((SingleHopPreparedStatement) ps).createNewSetForTesting();
    ps.setInt(1, 1234);
    ps.setInt(2, 2);
    ps.setInt(3, 3);
    ret = ps.executeUpdate();
    assertEquals(1, ret);
    
    // get the routing object of single-hop execution
    Set<Object> shopset = ((SingleHopPreparedStatement)ps)
        .getRoutingObjectSet();

    // partition by expression should not go single hop.
    assertEquals(0, shopset.size());
  }
}
