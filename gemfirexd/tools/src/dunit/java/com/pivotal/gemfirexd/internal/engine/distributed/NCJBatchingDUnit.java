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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;

import io.snappydata.test.dunit.SerializableRunnable;

/**
 * Non Collocated Join Functional Test.
 * 
 * Test all changes related to batching for NC Join
 * 
 * Batching property is set using Connection i.e.
 * props.put("gemfirexd.ncj-batch-size", String.valueOf(5)); over connection
 * 
 * @author vivekb
 */
@SuppressWarnings("serial")
public class NCJBatchingDUnit extends DistributedSQLTestBase {

  public NCJBatchingDUnit(String name) {
    super(name);
    // TODO Auto-generated constructor stub
  }
  
  @Override
  public void setUp() throws Exception {
    System.setProperty(GfxdConstants.OPTIMIZE_NON_COLOCATED_JOIN, "true");
    System.clearProperty("ncj-batch-size");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty(GfxdConstants.OPTIMIZE_NON_COLOCATED_JOIN, "true");
        System.clearProperty("ncj-batch-size");
      }
    });
    super.setUp();
  }

  @Override
  public void tearDown2() throws java.lang.Exception {
    System.setProperty(GfxdConstants.OPTIMIZE_NON_COLOCATED_JOIN, "false");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty(GfxdConstants.OPTIMIZE_NON_COLOCATED_JOIN, "false");
      }
    });
    super.tearDown2();
  }

  static int remoteCallbackInvokeCount = 0;

  final GemFireXDQueryObserver ncjPullResultSetOpenCoreObserver = new GemFireXDQueryObserverAdapter() {
    @Override
    public void ncjPullResultSetVerifyBatchSize(int value) {
      remoteCallbackInvokeCount = value;
    }
  };
  
  class NcjPullResultsetTestCacheSerializableRunnable extends
      SerializableRunnable {
    private int verifyCount = 0;

    public SerializableRunnable setVerifyCount(int param) {
      this.verifyCount = param;
      return this;
    }

    public NcjPullResultsetTestCacheSerializableRunnable(String name) {
      super(name);
    }

    @Override
    public void run() throws CacheException {
      assertTrue("remoteCallbackInvokeCount=" + remoteCallbackInvokeCount
          + " ,verifyCount=" + verifyCount,
          remoteCallbackInvokeCount == this.verifyCount);
    }
  }

  NcjPullResultsetTestCacheSerializableRunnable ncjPullResultSetOpenCoreObserverVerify = new NcjPullResultsetTestCacheSerializableRunnable(
      "Verify ncjPullResultSetOpenCoreObserver");

  SerializableRunnable ncjPullResultSetOpenCoreObserverSet = new SerializableRunnable(
      "Set ncjPullResultSetOpenCoreObserver") {
    @Override
    public void run() throws CacheException {
      remoteCallbackInvokeCount = 0;
      GemFireXDQueryObserverHolder
          .setInstance(ncjPullResultSetOpenCoreObserver);
    }
  };

  SerializableRunnable ncjPullResultSetOpenCoreObserverReset = new SerializableRunnable(
      "Reset ncjPullResultSetOpenCoreObserver") {
    @Override
    public void run() throws CacheException {
      remoteCallbackInvokeCount = 0;
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
          });
    }
  };

  private void execute_and_verify_batchSize(PreparedStatement prepSt,
      HashSet<Integer> expected, int batchSize) throws Exception {
    serverExecute(1, ncjPullResultSetOpenCoreObserverSet);

    ResultSet rs = prepSt.executeQuery();
    while (rs.next()) {
      assertTrue(expected.remove(rs.getInt(1)));
    }
    assertTrue(expected.isEmpty());
    rs.close();

    serverExecute(1,
        ncjPullResultSetOpenCoreObserverVerify.setVerifyCount(batchSize));
    serverExecute(1, ncjPullResultSetOpenCoreObserverReset);
  }

  public void test_Batching_Properties() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(2, null, null);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1,
        "create table trade.ORDERS (OID int primary key, OSID int not null, "
            + "OVID varchar(10)) partition by primary key");
    clientSQLExecute(1,
        "create table trade.DUPLI (DID int primary key, DSID int not null, "
            + "DVID varchar(10)) partition by column (DSID)");
    clientSQLExecute(1,
        "create table trade.TRIPLI (TID int not null, TSID int primary key, "
            + "TVID varchar(10)) partition by primary key");
       
    String[] securities = { "IBM", "INTC", "MOT", "TEK", "AMD", "CSCO", "DELL",
        "HP", "SMALL1", "SMALL2" };
  
    for (int i = 1; i < 11; i++) {
      clientSQLExecute(1, "Insert into trade.ORDERS values(" + i + "," + i + ",'"
          + securities[i % 10] + "'" + ")");
    }
    for (int i = 4; i < 14; i++) {
      clientSQLExecute(1, "Insert into trade.DUPLI values(" + i + "," + i + ",'"
          + securities[i % 10] + "'" + ")");
    }
    for (int i = 7; i < 17; i++) {
      clientSQLExecute(1, "Insert into trade.TRIPLI values(" + i + "," + i + ",'"
          + securities[i % 10] + "'" + ")");
    }
    
    { 
      HashSet<Integer> expected = new HashSet<Integer>();
      String query = 
          "Select A.OID, A.OVID, B.DID, B.DVID, C.TID, C.TVID from "
          + " trade.ORDERS A "
          + " inner join trade.DUPLI B " 
          + "on A.OID = B.DID "
          + " inner join trade.TRIPLI C " 
          + "on B.DID = C.TSID "
          ;
  
      Properties props = new Properties();
      props.put("gemfirexd.ncj-batch-size", String.valueOf(50));
      Connection conn = TestUtil.getConnection(props);
      PreparedStatement s1 = conn.prepareStatement(query);
      
      // First call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 50);

      // Second call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 50);
    }
    
    { 
      HashSet<Integer> expected = new HashSet<Integer>();
      String query = 
          "Select A.OID, A.OVID, B.DID, B.DVID, C.TID, C.TVID from "
          + " trade.ORDERS A "
          + " inner join trade.DUPLI B " 
          + "on A.OID = B.DID "
          + " inner join trade.TRIPLI C " 
          + "on B.DSID = C.TSID "
          ;
  
      Connection conn = TestUtil.getConnection();
      PreparedStatement s1 = conn.prepareStatement(query);

      // First call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 0);

      // Second call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 0);
    }
    
    { 
      HashSet<Integer> expected = new HashSet<Integer>();
      String query = 
          "Select A.OID, A.OVID, B.DID, B.DVID, C.TID, C.TVID from "
          + " trade.ORDERS A "
          + " inner join trade.DUPLI B " 
          + "on A.OID = B.DID "
          + " inner join trade.TRIPLI C " 
          + "on B.DID = C.TSID "
          ;
  
      Properties props = new Properties();
      props.setProperty("ncj-batch-size", "500");
      //props.setProperty("gemfirexd.debug.true", "TraceClientHA");
      String url = TestUtil.getNetProtocol("localhost", netPort);
      Connection conn = DriverManager.getConnection(url,
          TestUtil.getNetProperties(props));
      PreparedStatement s1 = conn.prepareStatement(query);
      
      // First call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 500);

      // Second call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 500);
    }
    
    { 
      HashSet<Integer> expected = new HashSet<Integer>();
      String query = 
          "Select A.OID, A.OVID, B.DID, B.DVID, C.TID, C.TVID from "
          + " trade.ORDERS A "
          + " inner join trade.DUPLI B " 
          + "on A.OID = B.DID "
          + " inner join trade.TRIPLI C " 
          + "on B.DID = C.TSID "
          ;
  
      Properties props = new Properties();
      props.put("gemfirexd.ncj-batch-size", String.valueOf(500));
      Connection conn = TestUtil.getConnection(props);
      PreparedStatement s1 = conn.prepareStatement(query);
      
      // First call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 500);

      // Second call
      expected.clear();
      expected.add(7);
      expected.add(8);
      expected.add(9);
      expected.add(10);
      execute_and_verify_batchSize(s1, expected, 500);
    }
    
    clientSQLExecute(1, "drop table trade.TRIPLI");
    clientSQLExecute(1, "drop table trade.DUPLI");
    clientSQLExecute(1, "drop table trade.orders");
    clientSQLExecute(1, "drop schema trade restrict");
  }
  
  public void test_Three_Tables() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    clientSQLExecute(1, "create schema trade");
    clientSQLExecute(1,
        "create table trade.ORDERS (OID int not null, OSID int primary key, "
            + "OVID varchar(10)) partition by primary key");
    clientSQLExecute(1,
        "create table trade.DUPLI (DID int not null, DSID int primary key, "
            + "DVID varchar(10)) partition by column (DSID)");
    clientSQLExecute(1,
        "create table trade.TRIPLI (TID int primary key, TSID int not null, "
            + "TVID varchar(10)) partition by primary key");
       
    String[] securities = { "IBM", "INTC", "MOT", "TEK", "AMD", "CSCO", "DELL",
        "HP", "SMALL1", "SMALL2" };
    
    for (int j = 0; j < 10; j++) {
      int k = j * 100;
      for (int i = 1; i < 51; i++) {
        clientSQLExecute(1, "Insert into trade.ORDERS values(" + (i + k) + ","
            + (i + k) + ",'" + securities[i % 10] + "'" + ")");
      }
      for (int i = 21; i < 71; i++) {
        clientSQLExecute(1, "Insert into trade.DUPLI values(" + (i + k) + ","
            + (i + k) + ",'" + securities[i % 10] + "'" + ")");
      }
      for (int i = 41; i < 91; i++) {
        clientSQLExecute(1, "Insert into trade.TRIPLI values(" + (i + k) + ","
            + (i + k) + ",'" + securities[i % 10] + "'" + ")");
      }
    }
    
    { 
      HashSet<Integer> expected = new HashSet<Integer>();
      for (int j = 0; j < 10; j++) {
        int k = j * 100;
        for (int i = 41; i < 51; i++) {
          expected.add(k + i);
        }
      }
      
      String query = 
          "Select A.OID, A.OVID, B.DID, B.DVID, C.TID, C.TVID from "
          + " trade.ORDERS A "
          + " inner join trade.DUPLI B " 
          + "on A.OID = B.DID "
          + " inner join trade.TRIPLI C " 
          + "on B.DSID = C.TSID "
          ;
  
      Connection conn = TestUtil.getConnection();
      PreparedStatement s1 = conn.prepareStatement(query);
      ResultSet rs = s1.executeQuery();
      while (rs.next()) {
        assertTrue(expected.remove(rs.getInt(1)));
      }
      assertTrue(expected.isEmpty());
      s1.close();
    }
        
    clientSQLExecute(1, "drop table trade.TRIPLI");
    clientSQLExecute(1, "drop table trade.DUPLI");
    clientSQLExecute(1, "drop table trade.orders");
    clientSQLExecute(1, "drop schema trade restrict");
  }
}  

