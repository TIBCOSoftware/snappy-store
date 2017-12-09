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

import java.sql.*;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import javax.sql.rowset.serial.SerialBlob;

import com.gemstone.gemfire.cache.CacheException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireResultSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;
import com.pivotal.gemfirexd.jdbc.TestRowLoader;
import io.snappydata.test.dunit.SerializableRunnable;

/**
 * Tests for GetAllConvertible Queries
 * 
 * @author vivekb
 * 
 *         Some tests have corresponding junit tests in
 * @see com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfoInternalsTest
 */
@SuppressWarnings("serial")
public class GetAllLocalIndexQueryDUnit extends DistributedSQLTestBase {
  /* Note:
   * Make this 'true' while running performance test @see _testINperf 
   */
  private static boolean changeDefaultTestProperties = false;

  // Use with THin Client Test
  static boolean[] remoteCallbackInvoked = new boolean[] { false, false, false, false, false };

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    if (changeDefaultTestProperties) {
      return "config";
    }
    else {
      return super.reduceLogging();
    }
  }
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    // Do Nothing
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        // Do Nothing
      }
    });
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
      // Do Nothing
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        // Do Nothing
      }
    });
  }

  public GetAllLocalIndexQueryDUnit(String name) {
    super(name);
  }

  public void testINWithMultipleParameters() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
    		"partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(3, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);

      // insert data
      s.execute("Insert into  t1 values(1,'asif',2)");

      {
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(2, rs.getInt(1));
        assertEquals("asif", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testINWithMultipleParameters_noData() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(3, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);

      { // first with no data
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testINWithMultipleParameters_ThinClient_singleHopEnabled() throws Exception {
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "true");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    String logLevel = getLogLevel();
    connSHOPProps.setProperty("log-level", logLevel);
    if (logLevel.startsWith("fine")) {
      connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
    }

    String url = TestUtil.getNetProtocol("localhost", netPort);
    Connection conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connSHOPProps));

    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";

    final GemFireXDQueryObserver getAllObserver = new GemFireXDQueryObserverAdapter() {
      @Override
      public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
          GenericPreparedStatement gps, LanguageConnectionContext lcc) {
        if (qInfo instanceof SelectQueryInfo) {
          remoteCallbackInvoked[0] = true;
          assertTrue(qInfo instanceof SelectQueryInfo);
          SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
          assertFalse(sqi.isPrimaryKeyBased());
          assertTrue(sqi.isGetAllOnLocalIndex());
          assertTrue(sqi.isDynamic());
          assertEquals(3, sqi.getParameterCount());
          Object[] pks = (Object[])sqi.getPrimaryKey();
          assertNull(pks);
          try {
            assertTrue(sqi.createGFEActivation());
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }
        }
      }

      @Override
      public void createdGemFireXDResultSet(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
        if (rs instanceof GemFireResultSet) {
          remoteCallbackInvoked[1] = true;
        }
      }

      @Override
      public void getAllInvoked(int numElements) {
        remoteCallbackInvoked[3] = true;
      }
      
      @Override
      public void getAllLocalIndexExecuted() {
        remoteCallbackInvoked[4] = true;
      }
      
      @Override
      public void getAllLocalIndexInvoked(int numElements) {
        remoteCallbackInvoked[2] = true;
        assertEquals(3, numElements);
      }
    };

    SerializableRunnable getAllObsSet = new SerializableRunnable(
        "Set GetAll Observer") {
      @Override
      public void run() throws CacheException {
        remoteCallbackInvoked[0] = false;
        remoteCallbackInvoked[1] = false;
        remoteCallbackInvoked[2] = false;
        remoteCallbackInvoked[3] = false;
        remoteCallbackInvoked[4] = false;
        GemFireXDQueryObserverHolder.setInstance(getAllObserver);
      }
    };

    SerializableRunnable getAllObsReset = new SerializableRunnable(
        "Reset GetAll Observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
            });
      }
    };
    
    SerializableRunnable clearStmtCache = new SerializableRunnable(
        "Clear Stmt Cache") {
      @Override
      public void run() throws CacheException {
        try {
          TestUtil.clearStatementCache();
          remoteCallbackInvoked[3] = true;
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    };
    
    SerializableRunnable verifyStmtCache = new SerializableRunnable(
        "Verify Stmt Cache") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[3]);
      }
    };

    SerializableRunnable getAllObsVerify_noData = new SerializableRunnable(
        "Verify GetAll Observer") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertTrue(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertTrue(remoteCallbackInvoked[4]);
      }
    };

    SerializableRunnable getAllObsVerify = new SerializableRunnable(
        "Verify GetAll Observer") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertTrue(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertTrue(remoteCallbackInvoked[4]);
      }
    };
    
    try {
      { // first with no data
        serverExecute(1, getAllObsSet);
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        assertFalse(rs.next());
        serverExecute(1, getAllObsVerify_noData);
        serverExecute(1, clearStmtCache);
        serverExecute(1, verifyStmtCache);
      }

      // insert data
      s.execute("Insert into  t1 values(1,'asif',1)");
      s.execute("Insert into  t1 values(2,'asif',2)");
      s.execute("Insert into  t1 values(3,'asif',3)");
      s.execute("Insert into  t1 values(4,'asif',4)");
      s.execute("Insert into  t1 values(5,'asif',5)");

      { // after data insertion
        serverExecute(1, getAllObsSet);
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        Set<Integer> hashi = new HashSet<Integer>();
        hashi.add(1);
        hashi.add(2);
        hashi.add(3);
        while (rs.next()) {
          assertTrue(hashi.remove(rs.getInt(1)));
        }
        assertTrue(hashi.isEmpty());
        serverExecute(1, getAllObsVerify);
        rs.close();
      }
    } finally {
      serverExecute(1, getAllObsReset);
    }
  }
  
  public void testINWithMultipleParameters_ThinClient_singleHopDisabled() throws Exception {
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "false");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    String logLevel = getLogLevel();
    connSHOPProps.setProperty("log-level", logLevel);
    if (logLevel.startsWith("fine")) {
      connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
    }

    String url = TestUtil.getNetProtocol("localhost", netPort);
    Connection conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connSHOPProps));

    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";

    final GemFireXDQueryObserver getAllObserver = new GemFireXDQueryObserverAdapter() {
      @Override
      public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
          GenericPreparedStatement gps, LanguageConnectionContext lcc) {
        if (qInfo instanceof SelectQueryInfo) {
          remoteCallbackInvoked[0] = true;
          assertTrue(qInfo instanceof SelectQueryInfo);
          SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
          assertFalse(sqi.isPrimaryKeyBased());
          assertTrue(sqi.isGetAllOnLocalIndex());
          assertTrue(sqi.isDynamic());
          assertEquals(3, sqi.getParameterCount());
          Object[] pks = (Object[])sqi.getPrimaryKey();
          assertNull(pks);
          try {
            assertTrue(sqi.createGFEActivation());
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }
        }
      }

      @Override
      public void createdGemFireXDResultSet(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
        if (rs instanceof GemFireResultSet) {
          remoteCallbackInvoked[1] = true;
        }
      }

      @Override
      public void getAllInvoked(int numElements) {
        remoteCallbackInvoked[3] = true;
      }
      
      @Override
      public void getAllLocalIndexExecuted() {
        remoteCallbackInvoked[4] = true;
      }
      
      @Override
      public void getAllLocalIndexInvoked(int numElements) {
        remoteCallbackInvoked[2] = true;
        assertEquals(3, numElements);
      }
    };

    SerializableRunnable getAllObsSet = new SerializableRunnable(
        "Set GetAll Observer to false") {
      @Override
      public void run() throws CacheException {
        remoteCallbackInvoked[0] = false;
        remoteCallbackInvoked[1] = false;
        remoteCallbackInvoked[2] = false;
        remoteCallbackInvoked[3] = false;
        remoteCallbackInvoked[4] = false;
        GemFireXDQueryObserverHolder.setInstance(getAllObserver);
      }
    };

    SerializableRunnable getAllObsReset = new SerializableRunnable(
        "Reset GetAll Observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
            });
      }
    };
    
    SerializableRunnable clearStmtCache = new SerializableRunnable(
        "Clear Stmt Cache") {
      @Override
      public void run() throws CacheException {
        try {
          TestUtil.clearStatementCache();
          remoteCallbackInvoked[3] = true;
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    };
    
    SerializableRunnable verifyStmtCache = new SerializableRunnable(
        "Verify Stmt Cache") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[3]);
      }
    };

    SerializableRunnable getAllObsVerify_noData = new SerializableRunnable(
        "Verify GetAll Observer") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertTrue(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertTrue(remoteCallbackInvoked[4]);
      }
    };

    SerializableRunnable getAllObsVerify = new SerializableRunnable(
        "Verify GetAll Observer") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertTrue(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertTrue(remoteCallbackInvoked[4]);
      }
    };
    
    try {
      { // first with no data
        serverExecute(1, getAllObsSet);
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        assertFalse(rs.next());
        serverExecute(1, getAllObsVerify_noData);
        serverExecute(1, clearStmtCache);
        serverExecute(1, verifyStmtCache);
      }

      // insert data
      s.execute("Insert into  t1 values(1,'asif',1)");
      s.execute("Insert into  t1 values(2,'asif',2)");
      s.execute("Insert into  t1 values(3,'asif',3)");
      s.execute("Insert into  t1 values(4,'asif',4)");
      s.execute("Insert into  t1 values(5,'asif',5)");
            
      { // after data insertion
        serverExecute(1, getAllObsSet);
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        Set<Integer> hashi = new HashSet<Integer>();
        hashi.add(1);
        hashi.add(2);
        hashi.add(3);
        while (rs.next()) {
          assertTrue(hashi.remove(rs.getInt(1)));
        }
        assertTrue(hashi.isEmpty());
        serverExecute(1, getAllObsVerify);
        rs.close();
      }
    } finally {
      serverExecute(1, getAllObsReset);
    }
  }
  
  public void testINWithMultipleParameters_Replicated() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) "
        + "replicate");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireDistributedResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(0, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);

      { // first with no data
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
      }

      // insert data
      s.execute("Insert into  t1 values(1,'asif',2)");

      {
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(2, rs.getInt(1));
        assertEquals("asif", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testINWithMultipleParametersAndDuplicateKeys() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',2)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(3, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 1);
      ps1.setInt(3, 1);
      ResultSet rs = ps1.executeQuery();
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(2));
      assertEquals(2, rs.getInt(1));
      assertEquals("asif", rs.getString(3));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testINWithConstantAndParameterizedFields() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',2)");
    String query = "select type, id, name from t1 where id IN (?,?,3,4)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(2, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(4, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(2));
      assertEquals(2, rs.getInt(1));
      assertEquals("asif", rs.getString(3));
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testINWithMultipleConstantFields() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
    Properties props = new Properties();
    props.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(props);
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
        "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',2)");

    String query = "select id, name from t1 "
        + "where id IN (1, 2, 1000, 2000)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertFalse(sqi.isDynamic());
                assertEquals(0, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(4, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ResultSet rs = ps1.executeQuery();
      Set<String> hashs = new HashSet<String>();
      hashs.add("asif");
      Set<Integer> hashi = new HashSet<Integer>();
      hashi.add(1);
      while (rs.next()) {
        assertTrue(hashi.remove(rs.getInt(1)));
        assertTrue(hashs.remove(rs.getString(2)));
      }
      assertFalse(rs.next());
      assertTrue(hashi.isEmpty());
      assertTrue(hashs.isEmpty());
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_1() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
    
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");
    s.execute("create table t1 ( id int, name varchar(10), type int primary key, address varchar(50))"
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',21, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',22, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',23, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',24, 'J 604')");
    String query = null;
    ResultSet rs = null;
    GemFireXDQueryObserver old = null;
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmt = conn.createStatement();
      query = "select substr(name,1,2) from t1 where  id IN (1,1)";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      Set<String> hash = new HashSet<String>();
      hash.add("as");
      while (rs.next()) {
        assertTrue(hash.remove(rs.getString(1)));
      }
      assertTrue(hash.isEmpty());
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1, 3)";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("604", rs.getString(1));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "order by id  asc , address desc";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "group by id, address   order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type)  from t1 where id IN (1,3) "
          + "group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type), MAX(type)  from t1 "
          + "where id IN (1,3) group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address, id  from t1 where  id IN (1,3) "
          + "group by id, address  order by id";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address  from t1 where  id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address  from t1 where  id IN (1,3)  group by address ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      /* TODO: Optimizer issue
       * Index is not selected with select id ...
       */
      query = "select type from t1 where  id IN (3,1,4,2)  order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      for (int i = 21; i < 25; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (1,3,4,2)  order by id desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      for (int i = 24; i > 20; --i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (3,1,4,2)  order by id asc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      for (int i = 21; i < 25; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      if (systemconn != null) {
        systemconn.close();
      }
    }
  }
  
  public void testBug40413_2() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");
    s.execute("create table t1 ( id int, name varchar(10), type int primary key, address varchar(50))"
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',21, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',22, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',23, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',24, 'J 604')");
    String query = "select substr(name,1,2) from t1 where  id = 1";
    GemFireXDQueryObserver old = null;
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      Set<String> hash = new HashSet<String>();
      hash.add("as");
      while (rs.next()) {
        assertTrue(hash.remove(rs.getString(1)));
      }
      assertTrue(hash.isEmpty());
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1, 3,1000)";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("604", rs.getString(1));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3,1000) "
          + "order by id  asc , address desc";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3,1000) "
          + "group by id, address   order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type)  from t1 where id IN (1,3,1000) "
          + "group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type), MAX(type)  from t1 "
          + "where id IN (1,3,1000) group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address, id  from t1 where  id IN (1,3,1000) "
          + "group by id, address  order by id";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address  from t1 where  id IN (1,3,1000) ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address  from t1 where  id IN (1,3,1000)  group by address ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (3,1,4,2,1000)  order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      for (int i = 21; i < 25; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (1,3,4,2,1000)  order by id desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      for (int i = 24; i > 20; --i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (3,1,4,2,1000)  order by id asc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);;
      assertTrue(callbackInvoked[2]);
      for (int i = 21; i <= 24; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      if (systemconn != null) {
        systemconn.close();
      }
    }
  }
  
  /**
   * @author vivekb
   *
   */
  public static class TestBug40413_2_RowLoader extends TestRowLoader {
    @Override
    public Object getRow(String schemaName, String tableName, Object[] primaryKey)
        throws SQLException {
      SanityManager.DEBUG_PRINT("GfxdTestRowLoader", "load called with key="
          + primaryKey[0] + " in VM "
          + Misc.getDistributedSystem().getDistributedMember());
      if (primaryKey[0].equals(1000)) {
        return new Object[] { 1000, "rowLoad", 25, "J 604"};
      }
      return null;
    }
  }

  public void testBug40413_2_RowLoader() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");
    s.execute("create table t1 ( id int, name varchar(10), type int primary key, address varchar(50))"
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    /*  Note:
     *  Only get based query on primary key works with loader
     *  For GetAll on LocalIndex, loader would not work
     */
    GfxdCallbacksTest.addLoader(null, "t1",
        "com.pivotal.gemfirexd.internal.engine.distributed.GetAllLocalIndexQueryDUnit$TestBug40413_2_RowLoader", "");

    s.execute("Insert into  t1 values(1,'asif',21, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',22, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',23, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',24, 'J 604')");
    String query = "select substr(name,1,2) from t1 where  id = 1";
    GemFireXDQueryObserver old = null;
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertFalse(callbackInvoked[2]);
      Set<String> hash = new HashSet<String>();
      hash.add("as");
      while (rs.next()) {
        assertTrue(hash.remove(rs.getString(1)));
      }
      assertTrue(hash.isEmpty());
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1, 3,1000)";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("604", rs.getString(1));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3,1000) "
          + "order by id  asc , address desc";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address, id  from t1 where  id IN (1,3,1000) "
          + "group by id, address   order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type)  from t1 where id IN (1,3,1000) "
          + "group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  distinct type, AVG(type), MAX(type)  from t1 "
          + "where id IN (1,3,1000) group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address, id  from t1 where  id IN (1,3,1000) "
          + "group by id, address  order by id";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address  from t1 where  id IN (1,3,1000) ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select  address  from t1 where  id IN (1,3,1000)  group by address ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (3,1,4,2,1000)  order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      for (int i = 21; i < 25; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (1,3,4,2,1000)  order by id desc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      for (int i = 24; i > 20; --i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select type from t1 where  id IN (3,1,4,2,1000)  order by id asc ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);;
      assertFalse(callbackInvoked[2]);
      for (int i = 21; i <= 24; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertFalse(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_3() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key, address varchar(50))"
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("Insert into  t1 values(1,'asif',8, 'J 601')");
    s.execute("Insert into  t1 values(2,'neeraj',9, 'J 602')");
    s.execute("Insert into  t1 values(4,'sumedh',11, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',10, 'J 603')");

    String query = "select substr(address,4,5),substr(name,1,2) from t1 where "
        + " id  IN (1,3,4) order by name desc";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("04", rs.getString(1));
      assertEquals("su", rs.getString(2));
      assertTrue(rs.next());
      assertEquals("03", rs.getString(1));
      assertEquals("sh", rs.getString(2));
      assertTrue(rs.next());
      assertEquals("01", rs.getString(1));
      assertEquals("as", rs.getString(2));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      if (systemconn != null) {
        systemconn.close();
      }
    }
  }

  public void testBug40413_4() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s1 = conn.createStatement();
    s1.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key, address varchar(50))"
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    s.execute("create index i1 on t1 (type)");
    s.execute("Insert into  t1 values(1,'asif',3, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',4, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',5, 'J 604')");
    String query = "select type,TestUDF(name,1,4) from t1 where id IN (1,3) "
        + "order by name desc";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals(5, rs.getInt(1));
      assertEquals("hou", rs.getString(2));
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertEquals("sif", rs.getString(2));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct address from t1 where  id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("J 604", rs.getString(1));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
      callbackInvoked[0] = false;
      callbackInvoked[1] = false;
      callbackInvoked[2] = false;
      callbackInvoked[3] = false;
      callbackInvoked[4] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
      assertTrue(rs.next());
      assertEquals("604", rs.getString(1));
      assertFalse(rs.next());
      assertFalse(callbackInvoked[3]);
      assertTrue(callbackInvoked[4]);
      rs.close();
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      if (systemconn != null) {
        systemconn.close();
      }
    }
  }
  
  public void testEQWithMultipleIndexes() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, did int, name varchar(10), type int primary key) " +
                "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    /* For #47292 Index selection from Optimizer is broken
     */
    //s.execute("create INDEX t2index on t1(id,did)");
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertTrue(sqi.getParameterCount() == 1
                    || sqi.getParameterCount() == 2);
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(1, numElements);
            }
          });

      // insert data
      s.execute("Insert into  t1 values(1, 1, 'a1111', 11)");
      s.execute("Insert into  t1 values(1, 2, 'a1222', 22)");
      s.execute("Insert into  t1 values(1, 3, 'a1333', 33)");
      s.execute("Insert into  t1 values(2, 1, 'a2121', 21)");
      
      {
        String query = "select type, id, name from t1 where id = ?";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(2));
        assertEquals(21, rs.getInt(1));
        assertEquals("a2121", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
      s.execute("create INDEX t2index on t1(id,did)");
      {
        String query = "select type, id, name from t1 where id = ? and did = ?";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(22, rs.getInt(1));
        assertEquals("a1222", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test the IN operator Performance.
   */
  public void _testINperf() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);

    clientSQLExecute(1,
        "create table t1 ( id int, name varchar(10), type int primary key) "
            + "partition by primary key");
    clientSQLExecute(1, "create INDEX t1index on t1(id)");
    for (int i = 0; i < 10000; i++) {
      String s = "n" + i;
      clientSQLExecute(1, "Insert into  t1 values(" + i + ",'" + s + "'," + 2
          * i + ")");
    }

    {
      Connection conn = TestUtil.getConnection();
      String query = "select type, id, name from t1 where id = ?";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      for (int i = 0; i < times; i++) {
        ps1.setInt(1, i + 1);
        ResultSet rs = ps1.executeQuery();
        assertTrue(rs.next());
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, time taken was " + diff + " ns");
    }

    {
      Connection conn = TestUtil.getConnection();
      String query = "select type, id, name from t1 where id IN (?,?)";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      int insize = 2;
      for (int i = 0; i < times; i++) {
        for (int j = 0; j < insize; j++) {
          ps1.setInt(j + 1, i + j);
        }
        ResultSet rs = ps1.executeQuery();
        for (int j = 0; j < insize; j++) {
          assertTrue(rs.next());
        }
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, with in list size " + insize + " time taken was "
              + diff + " ns");
    }

    {
      Connection conn = TestUtil.getConnection();
      String query = "select type, id, name from t1 where id IN (?,?,?,?,?)";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      int insize = 5;
      for (int i = 0; i < times; i++) {
        for (int j = 0; j < insize; j++) {
          ps1.setInt(j + 1, i + j);
        }
        ResultSet rs = ps1.executeQuery();
        for (int j = 0; j < insize; j++) {
          assertTrue(rs.next());
        }
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, with in list size " + insize + " time taken was "
              + diff + " ns");
    }
  }

  /**
   * Test the IN operator Performance.
   */
  public void _testINperf_thinClient() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG2");
    startClientVMs(1, 0, null);

    clientSQLExecute(1,
        "create table t1 ( id int, name varchar(10), type int primary key) "
            + "partition by primary key");
    clientSQLExecute(1, "create INDEX t1index on t1(id)");
    for (int i = 0; i < 10000; i++) {
      String s = "n" + i;
      clientSQLExecute(1, "Insert into  t1 values(" + i + ",'" + s + "'," + 2
          * i + ")");
    }

    int clientPort = startNetworkServer(1, null, null);
   
    {
      Connection conn = TestUtil.getNetConnection(clientPort, null, null);
      String query = "select type, id, name from t1 where id = ?";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      for (int i = 0; i < times; i++) {
        ps1.setInt(1, i + 1);
        ResultSet rs = ps1.executeQuery();
        assertTrue(rs.next());
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, time taken was " + diff + " ns");
    }

    {
      Connection conn = TestUtil.getNetConnection(clientPort, null, null);
      String query = "select type, id, name from t1 where id IN (?,?)";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      int insize = 2;
      for (int i = 0; i < times; i++) {
        for (int j = 0; j < insize; j++) {
          ps1.setInt(j + 1, i + j);
        }
        ResultSet rs = ps1.executeQuery();
        for (int j = 0; j < insize; j++) {
          assertTrue(rs.next());
        }
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, with in list size " + insize + " time taken was "
              + diff + " ns");
    }

    {
      Connection conn = TestUtil.getNetConnection(clientPort, null, null);
      String query = "select type, id, name from t1 where id IN (?,?,?,?,?)";
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      long starttime = System.nanoTime();
      int times = 100;
      int insize = 5;
      for (int i = 0; i < times; i++) {
        for (int j = 0; j < insize; j++) {
          ps1.setInt(j + 1, i + j);
        }
        ResultSet rs = ps1.executeQuery();
        for (int j = 0; j < insize; j++) {
          assertTrue(rs.next());
        }
        assertFalse(rs.next());
      }
      long endtime = System.nanoTime();
      long diff = endtime - starttime;
      getLogWriter().info(
          "logTestINperf: " + "for executing " + query + " " + times
              + " times, with in list size " + insize + " time taken was "
              + diff + " ns");
    }
  }
  
  public void testBug48246() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s.execute("create table trade.customer "
        + "(c_balance int not null, c_first int not null, c_middle int not null, "
        + "c_id int not null, c_last int not null, c_d_id int not null, c_w_id int not null) "
        + "partition by column (c_balance)");
    s.execute("alter table trade.customer add constraint "
                + "trade.pk_customer primary key (c_w_id, c_d_id, c_id)");
    s.execute("create index trade.ndx_customer_name"
                + "  on trade.customer (c_w_id, c_d_id, c_last)");
    
    { // insert values
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.customer values (?, ?, ?, ?, ?, ?, ?)");
      for (int i = 0; i < 3; i++) {
        psInsert.setInt(1, i * 1);
        psInsert.setInt(2, i * 2);
        psInsert.setInt(3, i * 3);
        psInsert.setInt(4, i * 4);
        psInsert.setInt(5, 5);
        psInsert.setInt(6, 6);
        psInsert.setInt(7, 7);
        psInsert.executeUpdate();
      }
    }
    
    String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
        + "WHERE c_last = ? AND c_d_id = ? AND c_w_id = ? "
        + "ORDER BY c_w_id, c_d_id, c_last, c_first";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(1, numElements);
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      for (int i = 0; i < 5; i++) {
        ps1.setInt(1, 5);
        ps1.setInt(2, 6);
        ps1.setInt(3, 7);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          assertEquals(count, rs.getInt(2));
          count = count + 2;
        }
        assertEquals(6, count);
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      if (systemconn != null) {
        systemconn.close();
      }
    }
  }
  
  public void testBug48233_48380() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s.execute("create table trade.customer "
        + "(c_balance int not null, c_first int not null, c_middle varchar(10), "
        + "c_id int not null, c_last int not null, c_d_id int not null, c_w_id int not null) "
        + "partition by column (c_balance)");
    s.execute("alter table trade.customer add constraint "
        + "trade.pk_customer primary key (c_w_id, c_d_id, c_id)");
    s.execute("create index trade.ndx_customer_name"
        + "  on trade.customer (c_w_id, c_d_id, c_last)");
    s.execute("create index trade.ndx_customer_balance"
        + "  on trade.customer (c_balance)");

    { // insert values
      String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
          "DELL", "HP", "SMALL1", "SMALL2" };
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.customer values (?, ?, ?, ?, ?, ?, ?)");
      for (int i = 0; i < 5; i++) {
        psInsert.setInt(1, 1);
        psInsert.setInt(2, i * 2);
        psInsert.setString(3, securities[i % 9]);
        psInsert.setInt(4, i * 4);
        psInsert.setInt(5, 5);
        psInsert.setInt(6, 6);
        psInsert.setInt(7, 7);
        psInsert.executeUpdate();
      }
    }
    
    s.execute("create table trade.portfolio "
        + "(c_balance int not null, c_first int not null, c_middle varchar(10), "
        + "c_id int primary key, c_last int not null, c_d_id int not null, c_w_id int not null) "
        + "partition by primary key");
    { // insert values
      String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
          "DELL", "HP", "SMALL1", "SMALL2" };
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.portfolio values (?, ?, ?, ?, ?, ?, ?)");
      for (int i = 0; i < 5; i++) {
        psInsert.setInt(1, 1);
        psInsert.setInt(2, i * 2);
        psInsert.setString(3, securities[i % 9]);
        psInsert.setInt(4, i * 4);
        psInsert.setInt(5, 5);
        psInsert.setInt(6, 6);
        psInsert.setInt(7, 7);
        psInsert.executeUpdate();
      }
    }
    
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(2, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(1, numElements);
            }
          });

      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_first = ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? offset ? row fetch first row only";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(1, rs.getInt(1));
        }
        assertEquals(1, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_middle like ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setString(2, "M%");
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ?"
            + " AND c_id IN (select c_id from trade.portfolio where c_first = ? )";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 4);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          count = count + 4;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(4, count);
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  private static byte[] getByteArray(int arrayLength) {
    byte[] byteArray = new byte[arrayLength];
    for (int j = 0; j < arrayLength; j++) {
      byteArray[j] = (byte)(j % 256);
    }
    return byteArray;
  }

  public void testBug49028() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name blob, type int primary key) "
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");

    final boolean[] callbackInvoked = new boolean[] { false, false, false,
        false, false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }

            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }

            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }

            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(3, numElements);
            }
          });

      // insert data
      {
        PreparedStatement psInsert = conn
            .prepareStatement("insert into t1 values (?, ?, ?)");
        for (int i = 0; i < 10; i++) {
          psInsert.setInt(1, i % 4);
          psInsert.setBlob(2, new SerialBlob(getByteArray(10000)));
          psInsert.setInt(3, i);
          psInsert.executeUpdate();
        }
      }

      {
        String query = "select type, name from t1 where id IN (?,?,?)";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          assertTrue(rs.getInt(1) > 0);
          Blob blob = rs.getBlob(2);
          assertTrue(blob.length() > 0);
          TestUtil.convertByteArrayToString(blob.getBytes(1,
              (int)blob.length()));
          count++;
        }
        assertEquals(7, count);
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }

      {
        String query = "select name from t1 where id IN (?,?,?)";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          Blob blob = rs.getBlob(1);
          assertTrue(blob.length() > 0);
          TestUtil.convertByteArrayToString(blob.getBytes(1,
              (int)blob.length()));
          count++;
        }
        assertEquals(7, count);
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug49028_thinClient() throws Exception {
    // start some servers
    startVMs(0, 4, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    final Properties connSHOPProps = new Properties();
    connSHOPProps.setProperty("single-hop-enabled", "false");
    connSHOPProps.setProperty("single-hop-max-connections", "5");
    String logLevel = getLogLevel();
    connSHOPProps.setProperty("log-level", logLevel);
    if (logLevel.startsWith("fine")) {
      connSHOPProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
    }

    String url = TestUtil.getNetProtocol("localhost", netPort);
    Connection conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(connSHOPProps));

    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name blob, type int primary key) "
        + "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");

    try {
      // insert data
      {
        PreparedStatement psInsert = conn
            .prepareStatement("insert into t1 values (?, ?, ?)");
        for (int i = 0; i < 10; i++) {
          psInsert.setInt(1, i % 4);
          psInsert.setBlob(2, new SerialBlob(getByteArray(10000)));
          psInsert.setInt(3, i);
          psInsert.executeUpdate();
        }
      }

      { // after data insertion
        String query = "select type, name from t1 where id IN (?,?,?)";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          assertTrue("count=" + count, rs.getInt(1) > 0);
          Blob blob = rs.getBlob(2);
          assertTrue("count=" + count, blob.length() > 0);
          TestUtil.convertByteArrayToString(blob.getBytes(1,
              (int)blob.length()));
          count++;
        }
        assertEquals(7, count);
        rs.close();
      }

      { // after data insertion
        String query = "select name from t1 where id IN (?,?,?)";
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          Blob blob = rs.getBlob(1);
          assertTrue("count=" + count, blob.length() > 0);
          TestUtil.convertByteArrayToString(blob.getBytes(1,
              (int)blob.length()));
          count++;
        }
        assertEquals(7, count);
        rs.close();
      }
    } finally {
      //
    }
  }
  
  // make sure that query on view also gets converted to get-all
  public void testBug51466() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();

    Statement st = conn.createStatement();

    st.execute("create table test (col1 int, col2 int, col3 int) partition "
        + "by range (col1)(VALUES BETWEEN 0 AND 3, VALUES BETWEEN 3 AND 5)");
    st.execute("create index test_index on test(col3)");
    st.execute("create view test_v1 as select * from test");
    st.execute("create view test_v2 as select * from test_v1");

    st.execute("insert into test values(1,1,1), (2,2,2), (3,3,3), " +
    		"(4,4,4), (5,5,5)");

    GemFireXDQueryObserver old = null;
    try {
      final boolean[] getAllInvoked = new boolean[] { false };
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void getAllLocalIndexExecuted() {
              getAllInvoked[0] = true;
            }
          });

      ResultSet rs1 = st
          .executeQuery("select col2 from test_v1 where col3 = 2");
      assertTrue(rs1.next());
      assertEquals(2, rs1.getInt(1));
      rs1.close();
      assertTrue(getAllInvoked[0]);

      getAllInvoked[0] = false;
      ResultSet rs2 = st
          .executeQuery("select col2 from test_v2 where col3 = 4");
      assertTrue(rs2.next());
      assertEquals(4, rs2.getInt(1));
      rs2.close();
      assertTrue(getAllInvoked[0]);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug51466_reExecute() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
  
    Statement st = conn.createStatement();
  
    st.execute("create table test (col1 int, col2 int, col3 int) partition "
        + "by range (col1)(VALUES BETWEEN 0 AND 3, VALUES BETWEEN 3 AND 5)");
    st.execute("create index test_index on test(col3)");
    st.execute("create view test_v1 as select * from test");
    st.execute("create view test_v2 as select * from test_v1");
  
    st.execute("insert into test values(1,1,1), (2,2,2), (3,3,3), " +
                "(4,4,4), (5,5,5)");
  
    GemFireXDQueryObserver old = null;
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "true");
        }
      });

      final boolean[] getAllInvoked = new boolean[] { false };
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void getAllLocalIndexExecuted() {
              getAllInvoked[0] = true;
            }
          });
  
      {
        ResultSet rs1 = st
            .executeQuery("select col2 from test_v1 where col3 = 2");
        assertTrue(rs1.next());
        assertEquals(2, rs1.getInt(1));
        rs1.close();
        assertTrue(getAllInvoked[0]);
      }

      {
        getAllInvoked[0] = false;
        ResultSet rs2 = st
            .executeQuery("select col2 from test_v2 where col3 = 4");
        assertTrue(rs2.next());
        assertEquals(4, rs2.getInt(1));
        rs2.close();
        assertTrue(getAllInvoked[0]);
      }

      {
        getAllInvoked[0] = false;
        ResultSet rs1 = st
            .executeQuery("select col2 from test_v1 where col3 = 2");
        assertTrue(rs1.next());
        assertEquals(2, rs1.getInt(1));
        rs1.close();
        assertTrue(getAllInvoked[0]);
      }

      {
        getAllInvoked[0] = false;
        ResultSet rs2 = st
            .executeQuery("select col2 from test_v2 where col3 = 4");
        assertTrue(rs2.next());
        assertEquals(4, rs2.getInt(1));
        rs2.close();
        assertTrue(getAllInvoked[0]);
      }
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "false");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "false");
        }
      });
    }
  }

  public void testINWithMultipleParameters_reExecute() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
  
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int, name varchar(10), type int primary key) " +
                "partition by primary key");
    s.execute("create INDEX t1index on t1(id)");
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "true");
        }
      });
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(3, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
  
            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }
  
            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(3, numElements);
            }
          });
  
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);
  
      // insert data
      s.execute("Insert into  t1 values(1,'asif',2)");
  
      {
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(2, rs.getInt(1));
        assertEquals("asif", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
      
      {
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(2, rs.getInt(1));
        assertEquals("asif", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
      
      {
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertTrue(callbackInvoked[1]);
        assertTrue(callbackInvoked[2]);
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(2));
        assertEquals(2, rs.getInt(1));
        assertEquals("asif", rs.getString(3));
        assertFalse(rs.next());
        assertFalse(callbackInvoked[3]);
        assertTrue(callbackInvoked[4]);
        rs.close();
      }
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "false");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "false");
        }
      });
    }
  }

  public void testBug48233_48380_reExecute() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
  
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s.execute("create table trade.customer "
        + "(c_balance int not null, c_first int not null, c_middle varchar(10), "
        + "c_id int not null, c_last int not null, c_d_id int not null, c_w_id int not null) "
        + "partition by column (c_balance)");
    s.execute("alter table trade.customer add constraint "
        + "trade.pk_customer primary key (c_w_id, c_d_id, c_id)");
    s.execute("create index trade.ndx_customer_name"
        + "  on trade.customer (c_w_id, c_d_id, c_last)");
    s.execute("create index trade.ndx_customer_balance"
        + "  on trade.customer (c_balance)");
  
    { // insert values
      String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
          "DELL", "HP", "SMALL1", "SMALL2" };
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.customer values (?, ?, ?, ?, ?, ?, ?)");
      for (int i = 0; i < 5; i++) {
        psInsert.setInt(1, 1);
        psInsert.setInt(2, i * 2);
        psInsert.setString(3, securities[i % 9]);
        psInsert.setInt(4, i * 4);
        psInsert.setInt(5, 5);
        psInsert.setInt(6, 6);
        psInsert.setInt(7, 7);
        psInsert.executeUpdate();
      }
    }
    
    s.execute("create table trade.portfolio "
        + "(c_balance int not null, c_first int not null, c_middle varchar(10), "
        + "c_id int primary key, c_last int not null, c_d_id int not null, c_w_id int not null) "
        + "partition by primary key");
    { // insert values
      String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
          "DELL", "HP", "SMALL1", "SMALL2" };
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.portfolio values (?, ?, ?, ?, ?, ?, ?)");
      for (int i = 0; i < 5; i++) {
        psInsert.setInt(1, 1);
        psInsert.setInt(2, i * 2);
        psInsert.setString(3, securities[i % 9]);
        psInsert.setInt(4, i * 4);
        psInsert.setInt(5, 5);
        psInsert.setInt(6, 6);
        psInsert.setInt(7, 7);
        psInsert.executeUpdate();
      }
    }
    
    final boolean[] callbackInvoked = new boolean[] { false, false, false, false, false };
    GemFireXDQueryObserver old = null;
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "true");
        }
      });
      
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                callbackInvoked[0] = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertFalse(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isGetAllOnLocalIndex());
                assertTrue(sqi.isDynamic());
                assertEquals(2, sqi.getParameterCount());
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertNull(pks);
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
  
            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              if (rs instanceof GemFireResultSet) {
                callbackInvoked[1] = true;
              }
            }
  
            @Override
            public void getAllInvoked(int numElements) {
              callbackInvoked[3] = true;
            }
            
            @Override
            public void getAllLocalIndexExecuted() {
              callbackInvoked[4] = true;
            }
            
            @Override
            public void getAllLocalIndexInvoked(int numElements) {
              callbackInvoked[2] = true;
              assertEquals(1, numElements);
            }
          });
  
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_first = ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? offset ? row fetch first row only";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(1, rs.getInt(1));
        }
        assertEquals(1, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_middle like ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setString(2, "M%");
        ResultSet rs = ps1.executeQuery();
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ?"
            + " AND c_id IN (select c_id from trade.portfolio where c_first = ? )";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 4);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          count = count + 4;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(4, count);
        assertTrue(callbackInvoked[0]);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_first = ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? offset ? row fetch first row only";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count++;
          assertEquals(1, rs.getInt(1));
        }
        assertEquals(1, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ? AND c_middle like ?";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setString(2, "M%");
        ResultSet rs = ps1.executeQuery();
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        int count = 0;
        while (rs.next()) {
          count = count + 2;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(2, count);
        assertFalse(callbackInvoked[3]);
        assertFalse(callbackInvoked[4]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
        callbackInvoked[3] = false;
        callbackInvoked[4] = false;
      }
      
      {
        String query = "SELECT c_balance, c_first, c_middle, c_id FROM trade.customer "
            + "WHERE c_balance = ?"
            + " AND c_id IN (select c_id from trade.portfolio where c_first = ? )";
        // Creating a statement object that we can use for running various
        // SQL statements commands against the database.
        PreparedStatement ps1 = conn.prepareStatement(query);
        ps1.setInt(1, 1);
        ps1.setInt(2, 4);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          count = count + 4;
          assertEquals(count, rs.getInt(2));
        }
        assertEquals(4, count);
        assertFalse(callbackInvoked[1]);
        assertFalse(callbackInvoked[2]);
        rs.close();
        callbackInvoked[0] = false;
        callbackInvoked[1] = false;
        callbackInvoked[2] = false;
      }
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "false");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,
              "false");
        }
      });
    }
  }
}
