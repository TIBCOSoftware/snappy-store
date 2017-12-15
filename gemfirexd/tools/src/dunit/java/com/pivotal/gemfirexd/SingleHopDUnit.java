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
package com.pivotal.gemfirexd;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.client.am.SingleHopPreparedStatement;

import io.snappydata.test.dunit.SerializableRunnable;

/**
 * 
 * @author kneeraj
 * 
 */
@SuppressWarnings("serial")
public class SingleHopDUnit extends DistributedSQLTestBase {

  public SingleHopDUnit(String name) {
    super(name);
  }

  public void testSingleHopDuplicateResults() throws Exception {
	  if(isTransactional) {
		  return;
	  }
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

    connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);

    Statement st = connSHOP.createStatement();
    st.execute("create schema trade");
    String ddl = "create table trade.sellorders (oid int not null constraint orders_pk primary key, " +
    		"cid int, sid int, qty int, status varchar(10), tid int, " +
    		"constraint status_ch check (status in ('cancelled', 'open', 'filled')))  " +
    		"partition by range (cid) ( " +
    		"VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, " +
    		"VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, " +
    		"VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)  REDUNDANCY 1";
    st.execute(ddl);
    
    PreparedStatement pInsert = connSHOP.prepareStatement("insert into trade.sellorders values(?, ?, ?, ?, ?, ?)");
    for(int i=0; i<20; i++) {
      pInsert.setInt(1, i);
      if (i < 10) {
        pInsert.setInt(2, (1251 + i));
      }
      else {
        pInsert.setInt(2, (1577 + (i%10)));
      }
      pInsert.setInt(3, i);
      pInsert.setInt(4, (i*100));
      pInsert.setString(5, "open");
      pInsert.setInt(6, 10);
      int cnt = pInsert.executeUpdate();
      assertEquals(1, cnt);
    }
    
    PreparedStatement pSelect = connSHOP.prepareStatement("select oid, sid, cid, status from trade.sellorders  where status = ? and cid IN (?, ?, ?, ?, ?) and tid =?");
    pSelect.setString(1, "open");
    pSelect.setInt(2, 1251);
    pSelect.setInt(3, 1577);
    pSelect.setInt(4, 1253);
    pSelect.setInt(5, 1580);
    pSelect.setInt(6, 1255);
    pSelect.setInt(7, 10);
    ResultSet rs = pSelect.executeQuery();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      System.out.println(rs.getInt(1) + ", " + rs.getInt(2) + ", " + rs.getInt(3) + ", " + rs.getString(4));
    }
    rs.close();
    assertEquals(5, cnt);
  }
  
  public void testSingleHopHA_clientParamType() throws Exception {
    // Return if the test is with product defaults as isolation level RC and autocommit true.
    // since single hop with txn is not implemented yet. 
    // Allow the test when it is implemented
    if (isTransactional) {
      return;
    }

    final Properties locatorProps = new Properties();
    setMasterCommonProperties(locatorProps);
    String locatorBindAddress = "localhost";
    int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    _startNewLocator(this.getClass().getName(), getName(), locatorBindAddress,
        locatorPort, null, locatorProps);
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    TestUtil.startNetServer(netPort, null);

    // Start network server on the VMs
    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", locatorBindAddress + '[' + locatorPort
        + ']');
    startVMs(0, 1, 0, null, serverProps);
    final int netPort1 = startNetworkServer(1, null, null);
    Connection conn;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("read-timeout", "10");

    conn = TestUtil.getNetConnection(locatorBindAddress, netPort, null, connSHOPProps);

    
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

    String updStmnt = "update trade.portfolio set subTotal=? * qty "
        + "where cid = ? and sid = ?";

    String selStmnt = "select * from trade.securities where sec_id = ?";
    PreparedStatement ps2 = conn.prepareStatement(selStmnt);
    ps2.setInt(1, 1204);
    ResultSet rs = ps2.executeQuery();
    assertTrue(rs.next());
    BigDecimal price = rs.getBigDecimal(3);
    assertFalse(rs.next());

    rs.close();
    String updStmnt2 = "update trade.portfolio set subTotal = ? * qty  where cid = ? and sid = ?  and tid= ?";
    PreparedStatement ps = conn.prepareStatement(updStmnt2);
    ps.setBigDecimal(1, price);
    // ps.setBigDecimal(1, new BigDecimal("33.9"));
    ps.setInt(2, 508);
    ps.setInt(3, 1204);
    ps.setInt(4, 53);
    ps.executeUpdate();

    ps.executeUpdate();
    startVMs(0, 2, 0, null, serverProps);
    final int netPort2 = startNetworkServer(2, null, null);
    stopVMNum(-1);
    ps.executeUpdate();
  }
  
  public void test_Bug47392() throws Exception {
	  if(isTransactional) {
		  return;
	  }
    // start some servers
    startVMs(0, 4, 0, null, null);

    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
    
    Connection connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);
    
    Statement st = connSHOP.createStatement();
    
    { // ddl
      st.execute("create schema trade");
      String ddl = "create table trade.portfolio (cid int not null, sid int not null, "
          + "qty int not null, availQty int not null, subTotal decimal(30,20), tid int,"
          + " constraint portf_pk primary key (cid, sid), "
          + " constraint qty_ck check (qty>=0),"
          + " constraint avail_ch check (availQty>=0 and availQty<=qty)) partition by column (tid)";
      st.execute(ddl);
    }

    { // insert
      PreparedStatement psInsert = connSHOP
          .prepareStatement("insert into trade.portfolio values (?, ?,?,?,?,?)");
      for (int i = 1; i < 5; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(3, i + 10000);
        psInsert.setInt(4, i + 1000);
        psInsert.setFloat(5, 30.40f);
        psInsert.setInt(6, 2);
        for (int j = -2; j < 0; ++j) {
          psInsert.setInt(2, j);
          assertEquals(1, psInsert.executeUpdate());
        }
      }
    }

    { // query
      String query = "select count(distinct cid) as num_distinct_cid from trade.portfolio where (subTotal<? or subTotal >=?) and tid =?";
      System.out.println(query);
      PreparedStatement ps = connSHOP.prepareStatement(query);
      ps.setObject(1, 50, java.sql.Types.DECIMAL);
      ps.setObject(2, 0, java.sql.Types.DECIMAL);
      ps.setInt(3, 2);
      System.out.println("Results: ");
      ResultSet rs = ps.executeQuery();
      int cnt = 0;
      while (rs.next()) {
        cnt++;
        System.out.print("[" + rs.getString(1) + "] ");
        assertEquals(4, rs.getInt(1));
      }
      rs.close();
      assertEquals(1, cnt);
      System.out.println();
    }

    {
      st.execute("drop table trade.portfolio");
      st.execute("drop schema trade restrict");
    }
  }
  
  public void testSingleHopHA() throws Exception {
	  // if this test is enabled
	  if(isTransactional) {
		  return;
	  }
    // Start a locator in this VM
    final Properties locatorProps = new Properties();
    setMasterCommonProperties(locatorProps);
    String locatorBindAddress = "localhost";
    int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    _startNewLocator(this.getClass().getName(), getName(), locatorBindAddress,
        locatorPort, null, locatorProps);
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    TestUtil.startNetServer(netPort, null);

    // Start network server on the VMs
    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", locatorBindAddress + '[' + locatorPort
        + ']');
    startVMs(0, 4, 0, null, serverProps);
    final int netPort1 = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    final THashMap serverNumberToNetworkPort = new THashMap();
    serverNumberToNetworkPort.put(1, netPort1);
    serverNumberToNetworkPort.put(2, netPort2);
    serverNumberToNetworkPort.put(3, netPort3);
    serverNumberToNetworkPort.put(4, netPort4);

    //invokeInEveryVM(SingleHopDUnit.class, "setDRDADebugToTrue");
    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("read-timeout", "10");
    
    connSHOP = TestUtil.getNetConnection(locatorBindAddress, netPort, null, connSHOPProps);
    
    Statement st = connSHOP.createStatement();
    String ddl = "create table emp.partitiontesttable (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(20) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID) redundancy 2";
    st.execute(ddl);
    populate(st, 10000);

    String selectDML = "select * from emp.partitiontesttable where id = ?";
    String selectNoSHOP_DML = "select * from emp.partitiontesttable";
    String updateDML = "update emp.partitiontesttable set secondid = secondid + 1 where id = ?";
    String deleteDML = "delete from emp.partitiontesttable where id = ?";
    String insertDML = "insert into emp.partitiontesttable values(?, ?, ?)";

    final String[] dmls = new String[] { selectDML, selectNoSHOP_DML, updateDML, deleteDML,
        insertDML };

    final PreparedStatement pselect = connSHOP.prepareStatement(selectDML);
    final PreparedStatement pselectNOSHOP = connSHOP.prepareStatement(selectNoSHOP_DML);
    final PreparedStatement pupdate = connSHOP.prepareStatement(updateDML);
    final PreparedStatement pdelete = connSHOP.prepareStatement(deleteDML);
    final PreparedStatement pinsert = connSHOP.prepareStatement(insertDML);
    final ArrayList<PreparedStatement> pslist = new ArrayList<PreparedStatement>();

    final Throwable[] failure = new Throwable[1];
    final int totOps = 20000;
    pslist.add(pselect);
    pslist.add(pselectNOSHOP);
    pslist.add(pupdate);
    pslist.add(pdelete);
    pslist.add(pinsert);

    final int[] serverStopStartStatus = new int[] { 0 };
    final Random randomServerSelector = new Random();

    final AtomicBoolean stopServerFlag = new AtomicBoolean(false);

    Thread stopStartTask = new Thread(new SerializableRunnable() {
      public void run() {
        while (!stopServerFlag.get()) {
          int serverNumber = randomServerSelector.nextInt(4) + 1;
          int netport = ((Integer)serverNumberToNetworkPort.get(serverNumber));
          try {
            getLogWriter().info(
                "going to stop server vm: "
                    + serverNumber
                    + " with netport: " + netport);
            stopVMNum(-serverNumber);
            getLogWriter().info(
                "stopped server vm: " + serverNumber
                    + " and slept for 20 ms, going to restart servervm: "
                    + serverNumber);
            Thread.sleep(20);
            restartServerVMNums(new int[] { serverNumber }, 0, null, serverProps);
            getLogWriter().info(
                "starting network server on vm: " + serverNumber);
            final int netPort = startNetworkServer(serverNumber, null, null);
            getLogWriter().info(
                "started network server on vm: " + serverNumber + " netport: "
                    + netPort + " and putting in map");
            serverNumberToNetworkPort.put(serverNumber, netPort);
            // wait a bit before next stop/restart
            Thread.sleep(1000);
          } catch (Exception e) {
            serverStopStartStatus[0] = 1;
            getLogWriter().error(
                "problem encountered in stopping vm: " + serverNumber, e);
            return;
          }
        }
      }
    });

    stopStartTask.start();

    int numOps = 0;
    while (numOps < totOps) {
      int idx = randomServerSelector.nextInt(5);
      try {
        getLogWriter().info(
            "going to execute prep statement corresponding to dml: "
                + dmls[idx]);
        boolean ret = executePreparedStatement(pslist, idx,
            randomServerSelector, totOps, dmls);
        if (!ret) {
          failure[0] = new Exception("false result");
          break;
        }
      } catch (SQLException e) {
        getLogWriter().error("got error in executing dmlOPs", e);
        failure[0] = e;
        break;
      }
      numOps++;
    }

    stopServerFlag.set(true);
    stopStartTask.join();

    if (failure[0] != null) {
      fail("problem encountered in executing dmlOps", failure[0]);
    }
    if (serverStopStartStatus[0] != 0) {
      fail("problem encountered in executing stop start server task");
    }
  }

  private boolean executePreparedStatement(ArrayList<PreparedStatement> pslist,
      int idx, Random randomServerSelector, int totalOPs, String[] dmls)
      throws SQLException {
    int val = randomServerSelector.nextInt(totalOPs);
    getLogWriter().info(
        "going to execute prep statement corresponding to dml: " + dmls[idx]
            + " with val: " + val);
    PreparedStatement ps = pslist.get(idx);
    switch (idx) {
      case 0:// select
        ps.setInt(1, val);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          if (!rs.getString(3).equalsIgnoreCase("number" + val)) {
            getLogWriter().info(
                "returning false as 3rd column is not equal to: "
                    + ("number" + val) + " but it is: " + rs.getString(3));
            rs.close();
            return false;
          }
        }
        rs.close();
        return true;

      case 1:// no shop select
        ///*
        rs = ps.executeQuery();
        if (rs.next()) {
          int c1 = rs.getInt(1);
          getLogWriter().info(
              "col1 is: " + c1 + " and col3 is: " + rs.getString(3));
          assertTrue(rs.getString(3).equalsIgnoreCase("number" + c1));
        }
        rs.close();
        //*/
        return true;
      case 2:// update
      case 3:// delete
        ps.setInt(1, val);
        int cnt = ps.executeUpdate();
        return true;

      case 4:// insert
        val = val + 1000;
        ps.setInt(1, val);
        ps.setInt(2, val);
        ps.setString(3, "number"+val);
        // insert can throw duplicate exception
        cnt = 0;
        try {
          cnt = ps.executeUpdate();
        }
        catch (SQLException sqle) {
          if (!"23505".equalsIgnoreCase(sqle.getSQLState())) {
            throw sqle;
          }
          else {
            return true;
          }
        }
        if (cnt != 1) {
          getLogWriter().info(
              "returning false as cnt not equal to 1 but: " + cnt
                  + " for val: " + val);
          return false;
        }
        return true;
      default:
        return false;
    }
  }

  private static void setDRDADebugToTrue() {
    System.setProperty("gemfirexd.drda.debug", "true");
  }

  public void testSingleHopDataCorrectness_allDMLs() throws Exception {
    // Return if the test is with product defaults as isolation level RC and autocommit true.
    // since single hop with txn is not implemented yet. 
    // Allow the test when it is implemented
    if (isTransactional) {
      return;
    }
    
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP, connSimple;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

    connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);
    
    Statement st = connSHOP.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(20) not null,"
        + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID) redundancy 2";
    st.execute(ddl);
    populate(st, 10);

    String selectDML = "select * from EMP.PARTITIONTESTTABLE";
    PreparedStatement ps = connSHOP.prepareStatement(selectDML);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
      int firstField = rs.getInt(1);
      int expectedSecondField = firstField;
      String expectedThirdField = "number" + firstField;
      assertEquals(expectedSecondField, rs.getInt(2));
      assertEquals(expectedThirdField, rs.getString(3));
    }
    rs.close();
    assertEquals(10, cnt);

    selectDML = "select * from EMP.PARTITIONTESTTABLE where id = ? or id = ?";
    ps = connSHOP.prepareStatement(selectDML);
    ps.setInt(1, 0);
    ps.setInt(2, 5);
    ps.execute();
    rs = ps.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      int firstField = rs.getInt(1);
      int secondField = rs.getInt(2);
      String thirdField = rs.getString(3);
      if (firstField == 0) {
        assertEquals(0, rs.getInt(2));
        assertEquals("number0", rs.getString(3));
      }
      else if (firstField == 5) {
        assertEquals(5, rs.getInt(2));
        assertEquals("number5", rs.getString(3));
      }
      else {
        fail("expected field 1 to be 0 or 5 but got: " + firstField);
      }
    }
    rs.close();
    assertEquals(2, cnt);

    // fire 2 updates on 0th and 5th row
    String updateDML = "update EMP.PARTITIONTESTTABLE set secondid = ? where id = ? or id = ?";
    ps = connSHOP.prepareStatement(updateDML);
    ps.setInt(1, 100);
    ps.setInt(2, 0);
    ps.setInt(3, 5);
    cnt = ps.executeUpdate();
    assertEquals(2, cnt);

    selectDML = "select * from EMP.PARTITIONTESTTABLE";
    ps = connSHOP.prepareStatement(selectDML);
    ps.execute();
    rs = ps.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      int firstGot = rs.getInt(1);
      int secondGot = rs.getInt(2);
      if (firstGot == 0 || firstGot == 5) {
        assertEquals(100, secondGot);
      }
      else {
        assertEquals(firstGot, secondGot);
      }
    }
    rs.close();
    assertEquals(10, cnt);

    // fire 2 deletes on 0th and 5th and 9th row
    String deleteDML = "delete from EMP.PARTITIONTESTTABLE where id = ? or id = ? or id = 9";
    ps = connSHOP.prepareStatement(deleteDML);

    ps.setInt(1, 0);
    ps.setInt(2, 5);

    cnt = ps.executeUpdate();
    assertEquals(3, cnt);

    selectDML = "select * from EMP.PARTITIONTESTTABLE";
    ps = connSHOP.prepareStatement(selectDML);
    ps.execute();
    rs = ps.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      int firstGot = rs.getInt(1);
      if (firstGot == 0 || firstGot == 5 || firstGot == 9) {
        fail("got deleted rows");
      }
    }
    rs.close();
    assertEquals(7, cnt);
  }

  // This is disabled because with gfxd-precheckin time increases by this test
  // Only the above test of data correctness runs with gfxd-precheckin.
  public void testCompareSingleHopVsSimpleClientConnection_allDMLs()
      throws Exception {
    if (isTransactional) {
      return;
    }
    int numTimes = 500;
    final boolean warmup = numTimes > 100 ? true : false;
    final Properties props = new Properties();
    props.put("log-level", "config");
    if (warmup) {
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          DistributionManager.VERBOSE = false;
        }
      });
    }
    // start some servers
    startVMs(0, 4, 0, null, props);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP, connSimple;
    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

    connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);
    connSimple = TestUtil.getNetConnection("localhost", netPort, null, new Properties());

    Statement st = connSimple.createStatement();

    for (int itrNum = 0; itrNum < 6; itrNum++) {
      String dml = getDMLFromItrNum(itrNum, false);
      int redundancy = getRedundancyFromItrNum(itrNum);
      String ddlNoRedundancy = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
          + " SECONDID int not null, THIRDID varchar(20) not null,"
          + " PRIMARY KEY (ID)) PARTITION BY COLUMN (ID)";
      String ddl = redundancy > 0 ? (ddlNoRedundancy + " redundancy " + redundancy)
          : ddlNoRedundancy;
      st.execute(ddl);

      populate(st, numTimes);

      PreparedStatement psimple = connSimple.prepareStatement(dml);
      PreparedStatement pshop = connSHOP.prepareStatement(dml);

      getLogWriter().info(
          "executing dml: " + dml + " with redundancy: " + redundancy
              + " and ddl: " + ddl);
      // warmup
      boolean isDelete = dml.startsWith("delete");
      if (warmup) {
        int numTimesToWarmUp = isDelete ? numTimes : (numTimes * 5);
        int itrs = isDelete ? 5 : 1;
        for (int i = 0; i < itrs; i++) {
          if (isDelete && i > 0) {
            // repopulate
            populate(st, numTimes);
          }
          executeNTimesAndLogTime(psimple, numTimesToWarmUp, "SIMPLE", false,
              numTimes, dml, redundancy, (warmup == false));
          if (isDelete) {
            // repopulate
            populate(st, numTimes);
          }
          executeNTimesAndLogTime(pshop, numTimesToWarmUp, "SINHOP", false,
              numTimes, dml, redundancy, (warmup == false));
        }
      }

      for (int i = 0; i < 6; i++) {
        if ((warmup == true && isDelete)
            || (warmup == false && isDelete && i > 0)) {
          // repopulate
          getLogWriter().info(
              "repopulating before executing dml: " + dml
                  + " with redundancy: " + redundancy + " and ddl: " + ddl);
          populate(st, numTimes);
        }
        if (i % 2 == 0) {
          executeNTimesAndLogTime(pshop, numTimes, "SINHOP", true, numTimes,
              dml, redundancy, (warmup == false));
        }
        else {
          executeNTimesAndLogTime(psimple, numTimes, "SIMPLE", true, numTimes,
              dml, redundancy, (warmup == false));
        }
      }
      st.execute("drop table EMP.PARTITIONTESTTABLE");
    }
  }

  public void testCompareSingleHopVsSimpleClientConnection_allDMLs_noPK()
      throws Exception {
    if (isTransactional) {
      return;
    }
    int numTimes = 500;
    final boolean warmup = numTimes > 100 ? true : false;
    final Properties props = new Properties();
    props.put("log-level", "config");
    if (warmup) {
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          DistributionManager.VERBOSE = false;
        }
      });
    }
    // start some servers
    startVMs(0, 4, 0, null, props);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    Connection connSHOP, connSimple;
    final Properties connSHOPProps = new Properties();
    
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");

    connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);
    connSimple = TestUtil.getNetConnection("localhost", netPort, null, new Properties());
    
    Statement st = connSimple.createStatement();

    for (int itrNum = 0; itrNum < 6; itrNum++) {
      String dml = getDMLFromItrNum_NOPK(itrNum, false);
      int redundancy = getRedundancyFromItrNum(itrNum);
      String ddlNoRedundancy = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
          + " SECONDID int not null, THIRDID varchar(20) not null,"
          + " PRIMARY KEY (SECONDID)) PARTITION BY COLUMN (ID)";
      String ddl = redundancy > 0 ? (ddlNoRedundancy + " redundancy " + redundancy)
          : ddlNoRedundancy;
      st.execute(ddl);

      populate(st, numTimes);

      PreparedStatement psimple = connSimple.prepareStatement(dml);
      PreparedStatement pshop = connSHOP.prepareStatement(dml);

      getLogWriter().info(
          "executing dml: " + dml + " with redundancy: " + redundancy
              + " and ddl: " + ddl);
      // warmup
      boolean isDelete = dml.startsWith("delete");
      if (warmup) {
        int numTimesToWarmUp = isDelete ? numTimes : (numTimes * 5);
        int itrs = isDelete ? 5 : 1;
        for (int i = 0; i < itrs; i++) {
          if (isDelete && i > 0) {
            // repopulate
            populate(st, numTimes);
          }
          executeNTimesAndLogTime(psimple, numTimesToWarmUp, "SIMPLE", false,
              numTimes, dml, redundancy, (warmup == false));
          if (isDelete) {
            // repopulate
            populate(st, numTimes);
          }
          executeNTimesAndLogTime(pshop, numTimesToWarmUp, "SINHOP", false,
              numTimes, dml, redundancy, (warmup == false));
        }
      }

      for (int i = 0; i < 6; i++) {
        if ((warmup == true && isDelete)
            || (warmup == false && isDelete && i > 0)) {
          // repopulate
          getLogWriter().info(
              "repopulating before executing dml: " + dml
                  + " with redundancy: " + redundancy + " and ddl: " + ddl);
          populate(st, numTimes);
        }
        if (i % 2 == 0) {
          executeNTimesAndLogTime(pshop, numTimes, "SINHOP", true, numTimes,
              dml, redundancy, (warmup == false));
        }
        else {
          executeNTimesAndLogTime(psimple, numTimes, "SIMPLE", true, numTimes,
              dml, redundancy, (warmup == false));
        }
      }
      st.execute("drop table EMP.PARTITIONTESTTABLE");
    }
  }

  private void populate(Statement st, int numTimes) throws SQLException {
    String insertSQL = "insert into EMP.PARTITIONTESTTABLE values (?,?,?)";
    PreparedStatement pstmt = st.getConnection().prepareStatement(insertSQL);
    for (int i = 0; i < numTimes; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2,  i);
      pstmt.setString(3, "number" + i);
      pstmt.addBatch();
      if (((i + 1) % 1000) == 0) {
        pstmt.executeBatch();
      }
    }
    if ((numTimes % 1000) != 0) {
      pstmt.executeBatch();
    }
  }

  private int getRedundancyFromItrNum(int itrNum) {
    if (itrNum % 2 == 0) {
      return 0;
    }
    return 1;
  }

  private String getDMLFromItrNum(int itrNum, boolean debug) {
    if (!debug) {
      switch (itrNum) {
        case 0:
        case 1:
          return "select * from EMP.PARTITIONTESTTABLE where id = ?";

        case 2:
        case 3:
          return "update EMP.PARTITIONTESTTABLE set secondid = secondid + 1 where id = ?";

        case 4:
        case 5:
          return "delete from EMP.PARTITIONTESTTABLE where id = ?";
      }
      return null;
    }
    else {
      switch (itrNum) {
        case 0:
          return "select * from EMP.PARTITIONTESTTABLE where id = ?";

        case 1:
          return "update EMP.PARTITIONTESTTABLE set secondid = secondid + 1 where id = ?";

        case 2:
          return "delete from EMP.PARTITIONTESTTABLE where id = ?";
      }
      return null;
    }
  }

  private String getDMLFromItrNum_NOPK(int itrNum, boolean debug) {
    if (!debug) {
      switch (itrNum) {
        case 0:
        case 1:
          return "select * from EMP.PARTITIONTESTTABLE where id = ?";

        case 2:
        case 3:
          return "update EMP.PARTITIONTESTTABLE set thirdid = 'updated' where id = ?";

        case 4:
        case 5:
          return "delete from EMP.PARTITIONTESTTABLE where id = ?";
      }
      return null;
    }
    else {
      switch (itrNum) {
        case 0:
          return "select * from EMP.PARTITIONTESTTABLE where id = ?";

        case 1:
          return "update EMP.PARTITIONTESTTABLE set thirdid = 'updated' where id = ?";

        case 2:
          return "delete from EMP.PARTITIONTESTTABLE where id = ?";
      }
      return null;
    }
  }
  
  private void executeNTimesAndLogTime(PreparedStatement ps, int times,
      String type, boolean log, int noOfInserts, String dml, int redundancy,
      boolean logEach) throws SQLException {
    boolean callExecuteUpdate = shouldCallTypeBeExecuteUpdate(dml);
    long starttime = 0;
    long endtime = 0;
    if (log) {
      starttime = System.nanoTime();
    }
    for (int j = 0; j < times; j++) {
      int i = j % noOfInserts;
      ps.setInt(1, i);
      if (callExecuteUpdate) {
        int cnt = ps.executeUpdate();
        if (logEach) {
          getLogWriter().info(
              "KN: execute update called on dml: " + dml + " and i: " + i
                  + " type: " + type + " and cnt: " + cnt);
        }
        assertEquals(1, cnt);
      }
      else {
        ps.execute();
        ResultSet rs = ps.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          if (logEach) {
            getLogWriter().info(
                "KN: (" + type + ") for i = " + i + " result is: "
                    + rs.getInt(1) + ", " + rs.getInt(2) + ", "
                    + rs.getString(3));
          }
          cnt++;
        }
        rs.close();
        assertEquals(1, cnt);
      }
    }
    if (log) {
      endtime = System.nanoTime();
    }
    long diff = endtime - starttime;
    if (log) {
      getLogWriter().info(
          "for executing " + dml + " " + times + " times, with redundancy "
              + redundancy + " time taken by " + type + " was " + diff + " ns");
    }
    if (type.equals("SINHOP")) {
      assertTrue(((SingleHopPreparedStatement)ps)
          .wasSingleHopCalledInExecution());
    }
  }

  private boolean shouldCallTypeBeExecuteUpdate(String dml) {
    if (dml.startsWith("delete") || dml.startsWith("update")) {
      return true;
    }
    return false;
  }
  
  public void testBug48553() throws Exception {
	  if(isTransactional) {
		  return;
	  }
    startVMs(1, 4);
    
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    final int netPort2 = startNetworkServer(2, null, null);
    final int netPort3 = startNetworkServer(3, null, null);
    final int netPort4 = startNetworkServer(4, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
  
    try {
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
      st.execute("create schema trade");
  
      // Create tables
      st.execute("create table trade.customer "
          + "(c_balance int not null, c_first int not null, c_middle varchar(10), c_next varchar(10),"
          + "c_id int  primary key, c_tid int not null) "
          + "partition by column (c_tid)");
  
      st.execute("create table trade.customerrep "
          + "(c_balance int not null, c_first int not null, c_middle varchar(10), c_next varchar(10), "
          + "c_id int  primary key, c_tid int not null) " + "replicate");
      
      st.execute("create INDEX t1index on trade.customer(c_tid)");
      st.execute("create INDEX trep1index on trade.customer(c_tid)");
      
      { // insert
        String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
            "DELL", "HP", "SMALL1", "SMALL2" };
        PreparedStatement psInsert = conn
            .prepareStatement("insert into trade.customer values (?, ?, ?, ?, ?, ?)");
        PreparedStatement psInsert2 = conn
            .prepareStatement("insert into trade.customerrep values (?, ?, ?, ?, ?, ?)");
        for (int i = 0; i < 30; i++) {
          psInsert.setInt(1, i);
          psInsert2.setInt(1, i);
  
          psInsert.setInt(2, i % 3);
          psInsert2.setInt(2, i % 3);
  
          psInsert.setString(3, securities[i % 9]);
          psInsert2.setString(3, securities[i % 9]);
          
          psInsert.setString(4, securities[(2 * i) % 9]);
          psInsert2.setString(4, securities[(2 * i) % 9]);
  
          psInsert.setInt(5, i * 4);
          psInsert2.setInt(5, i * 4);
          
          psInsert.setInt(6, i % 10);
          psInsert2.setInt(6, i % 10);
  
          psInsert.executeUpdate();
          psInsert2.executeUpdate();
        }
      }
  
      {
        final Properties connSHOPProps = new Properties();
        connSHOPProps.setProperty("single-hop-enabled", "true");
        connSHOPProps.setProperty("single-hop-max-connections", "5");
        connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
        
        Connection connSHOP = TestUtil.getNetConnection("localhost", netPort, null, connSHOPProps);

        {
          String query = "select c_first, max(c_id) as amount "
              + "from trade.customer " + "where c_tid = ? "
              + "GROUP BY c_first, c_balance ";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 0;
            while (r.next()) {
              assertEquals(rowcount, r.getInt(1));
              rowcount++;
            }
            assertEquals(3, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
        
        {
          String query = "select c_first, max(c_id * c_balance) as largest_order "
              + "from trade.customer "
              + "where c_tid = ? "
              + "GROUP BY c_first "
              + "HAVING max(c_id * c_balance) > 20 "
              + "ORDER BY max(c_id * c_balance) DESC";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 0;
            while (r.next()) {
              assertEquals(rowcount, r.getInt(1));
              rowcount = rowcount + 2;
            }
            assertEquals(4, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
        
        {
          String query = "select c_first, cast(avg(c_id * c_balance) as decimal (30, 20)) as amount "
              + "from trade.customer "
              + "where c_tid = ? "
              + "GROUP BY c_first " + "ORDER BY amount";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 1;
            while (r.next()) {
              assertEquals(rowcount % 3, r.getInt(1));
              rowcount++;
            }
            assertEquals(4, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
        
        {
          String query = "select c_first, max(c_id) as amount "
              + "from trade.customerrep " + "where c_tid = ? "
              + "GROUP BY c_first, c_balance ";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 0;
            while (r.next()) {
              assertEquals(rowcount, r.getInt(1));
              rowcount++;
            }
            assertEquals(3, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
        
        {
          String query = "select c_first, max(c_id * c_balance) as largest_order "
              + "from trade.customerrep "
              + "where c_tid = ? "
              + "GROUP BY c_first "
              + "HAVING max(c_id * c_balance) > 20 "
              + "ORDER BY max(c_id * c_balance) DESC";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 0;
            while (r.next()) {
              assertEquals(rowcount, r.getInt(1));
              rowcount = rowcount + 2;
            }
            assertEquals(4, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
        
        {
          String query = "select c_first, cast(avg(c_id * c_balance) as decimal (30, 20)) as amount "
              + "from trade.customerrep "
              + "where c_tid = ? "
              + "GROUP BY c_first " + "ORDER BY amount";
          PreparedStatement pst = connSHOP.prepareStatement(query);
          for (int i = 0; i < 2; i++) {
            pst.setInt(1, 1);
            ResultSet r = pst.executeQuery();
            int rowcount = 1;
            while (r.next()) {
              assertEquals(rowcount % 3, r.getInt(1));
              rowcount++;
            }
            assertEquals(4, rowcount);
            // column count
            assertEquals(2, r.getMetaData().getColumnCount());
            r.close();
          }
        }
      }

      {
        Connection conn1 = TestUtil.getConnection();
        Statement st1 = conn1.createStatement();
        st1.execute("drop table trade.customer");
        st1.execute("drop table trade.customerrep");
        st1.execute("drop schema trade restrict");
      }
    } finally {
      TestUtil.shutDown();
    }
  }
}
