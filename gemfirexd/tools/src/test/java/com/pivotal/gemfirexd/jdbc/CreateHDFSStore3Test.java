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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.partition.PartitionMemberInfo;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.partition.PartitionRegionInfo;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.DiskRegionStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionStats;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import io.snappydata.test.dunit.AvailablePortHelper;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.MiniDFSCluster;

import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.checkHDFSIteratorResultSet;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.delete;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.deleteMiniClusterDir;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.initMiniCluster;

public class CreateHDFSStore3Test extends JdbcTestBase {

  public CreateHDFSStore3Test(String name) {
    super(name);
  }

  private int port;
  private MiniDFSCluster cluster;
  private FileSystem fs = null;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    port = AvailablePortHelper.getRandomAvailableTCPPort();
    cluster = initMiniCluster(port, 1);
    fs = FileSystem.get(URI.create("hdfs://localhost:" + port), new Configuration());
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    delete(new File("./myhdfs"));
    delete(new File("./gemfire"));
    if (cluster != null) {
      cluster.getFileSystem().close();
      cluster.shutdown();
    }
    deleteMiniClusterDir();
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  private void checkDirExistence(String path) throws IOException {
    CreateHDFSStoreTest.checkDirExistence(path, fs);
  }

  public void testThinClientConnectionProperties() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");

    // populate the table
    PreparedStatement ps = conn
        .prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    // make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    shutDown();

    // restart to clean the memory
    setupConnection();
    Properties props = new Properties();
    props.setProperty("query-HDFS", "true");
    conn = startNetserverAndGetLocalNetConnection(props);

    st = conn.createStatement();
    final boolean[] callbackInvoked = new boolean[] { false, false, false };
    GemFireXDQueryObserverAdapter gfxdAdapter = new GemFireXDQueryObserverAdapter() {
      @Override
      public void beforeQueryExecution(GenericPreparedStatement stmt,
          LanguageConnectionContext lcc) {
        if (stmt != null) {
          callbackInvoked[0] = stmt.hasQueryHDFS();
          callbackInvoked[1] = stmt.getQueryHDFS();
        }
        if (lcc != null) {
          callbackInvoked[2] = lcc.getQueryHDFS();
        }
      }
    };
    GemFireXDQueryObserverHolder nullHolder = new GemFireXDQueryObserverHolder();

    // now since query-HDFS = true by connection properties
    // it should query HDFS without using query hint
    try {
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      st.execute("select * from t1 order by col1");
      rs = st.getResultSet();
      checkHDFSIteratorResultSet(rs, NUM_ROWS);
      assertFalse(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    try {
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      ps = conn.prepareStatement("select * from t1 where col1 = ?");
      for (int i = 0; i < NUM_ROWS; i++) {
        ps.setInt(1, i);
        rs = ps.executeQuery();
        // queryHDFS == true
        assertTrue(rs.next());
        assertEquals(i + 1, rs.getInt(2));
        assertFalse(rs.next());
      }
      assertFalse(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    // query hint should override the connection property
    try {
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n");
      rs = st.getResultSet();
      // nothing in memory
      assertFalse(rs.next());
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    // query hint should override the connection property
    try {
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n");
      rs = st.getResultSet();
      // nothing in memory
      assertFalse(rs.next());
      assertTrue(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertTrue(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    shutDown();

    // restart to clean the memory
    setupConnection();
    props = new Properties();
    props.setProperty("query-HDFS", "false");
    conn = startNetserverAndGetLocalNetConnection(props);
    st = conn.createStatement();

    try {
      callbackInvoked[2] = true;
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      st.execute("select * from t1");
      rs = st.getResultSet();
      // nothing in memory
      assertFalse(rs.next());
      assertFalse(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertFalse(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    try {
      callbackInvoked[2] = true;
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      ps = conn.prepareStatement("select * from t1 where col1 = ? ");
      for (int i = 0; i < NUM_ROWS; i++) {
        ps.setInt(1, i);
        rs = ps.executeQuery();
        // queryHDFS == false
        assertFalse(rs.next());
      }
      assertFalse(callbackInvoked[0]);
      assertFalse(callbackInvoked[1]);
      assertFalse(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    try {
      callbackInvoked[2] = true;
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      ps = conn
          .prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = ?");
      for (int i = 0; i < NUM_ROWS; i++) {
        ps.setInt(1, i);
        rs = ps.executeQuery();
        // queryHDFS == true
        assertTrue(rs.next());
        assertEquals(i + 1, rs.getInt(2));
        assertFalse(rs.next());
      }
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertFalse(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    try {
      callbackInvoked[2] = true;
      GemFireXDQueryObserverHolder.setInstance(gfxdAdapter);
      st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
      rs = st.getResultSet();
      checkHDFSIteratorResultSet(rs, NUM_ROWS);
      assertTrue(callbackInvoked[0]);
      assertTrue(callbackInvoked[1]);
      assertFalse(callbackInvoked[2]);
    } finally {
      callbackInvoked[0] = callbackInvoked[1] = callbackInvoked[2] = false;
      GemFireXDQueryObserverHolder.setInstance(nullHolder);
    }

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug48983_1() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    props.put("query-HDFS", "true");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'  BatchTimeInterval 100 milliseconds");

    st.execute("create table trade.securities (sec_id int primary key) partition by primary key hdfsstore (myhdfs)");
    st.execute("create table trade.buyorders(oid int primary key, cid int, sid int, qty int, bid int, tid int, " +
        "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id) on delete restrict) " +
        "partition by primary key hdfsstore (myhdfs)");
    st.execute("insert into trade.securities values (123)");
    st.execute("insert into trade.buyorders values (1, 11, 123, 10, 10, 1)");
    st.execute("insert into trade.buyorders values (2, 11, 123, 11, 11, 1)");
    st.execute("insert into trade.buyorders values (3, 22, 123, 10, 10, 1)");
    st.execute("insert into trade.buyorders values (4, 22, 123, 11, 11, 1)");
    st.execute("insert into trade.buyorders values (5, 33, 123, 12, 12, 2)");

    PreparedStatement ps = conn.prepareStatement("select cid, max(qty*bid) as largest_order from trade.buyorders " +
        "where tid =?  " +
        "GROUP BY cid HAVING max(qty*bid) > 20 " +
        "ORDER BY largest_order DESC, cid DESC");
    ps.setInt(1, 1);
    rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(22, rs.getInt(1));
    assertEquals(121, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(11, rs.getInt(1));
    assertEquals(121, rs.getInt(2));
    assertFalse(rs.next());

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/TRADE/SECURITIES");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    qname = HDFSStoreFactoryImpl.getEventQueueName("/TRADE/BUYORDERS");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    shutDown();

    //restart
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props2 = new Properties();
    props2.put("query-HDFS", "true");
    conn = TestUtil.getConnection(props2);

    ps = conn.prepareStatement("insert into trade.buyorders values (?, 33, 123, 12, 12, 2)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i + 6);
      ps.executeUpdate();
    }

    ps = conn.prepareStatement("select cid, max(qty*bid) as largest_order from trade.buyorders " +
        "where tid =?  " +
        "GROUP BY cid HAVING max(qty*bid) > 20 " +
        "ORDER BY largest_order DESC, cid DESC");
    ps.setInt(1, 1);
    rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(22, rs.getInt(1));
    assertEquals(121, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(11, rs.getInt(1));
    assertEquals(121, rs.getInt(2));
    assertFalse(rs.next());

    st = conn.createStatement();
    st.execute("drop table trade.buyorders");
    st.execute("drop table trade.securities");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug49277() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table trade.customers (cid int primary key) partition by primary key");
    st.execute("create table trade.securities (sec_id int primary key) partition by primary key");
    st.execute("create table trade.buyorders (oid int primary key, cid int, sid int, bid int, note varchar(10), " +
        "constraint bo_cust_fk foreign key (cid) references trade.customers (cid), " +
        "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)) " +
        "partition by column (bid)");
    st.execute("create table trade.buyorders_fulldataset (oid int primary key, cid int, sid int, bid int, note varchar(10), " +
        "constraint bo_cust_fk2 foreign key (cid) references trade.customers (cid), " +
        "constraint bo_sec_fk2 foreign key (sid) references trade.securities (sec_id)) " +
        "partition by column (bid)");
    st.execute(" CREATE  TRIGGER  trade.buyorders_INSERTTRIGGER AFTER INSERT ON trade.buyorders " +
        "REFERENCING NEW AS NEWROW FOR EACH ROW   " +
        "INSERT INTO TRADE.BUYORDERS_FULLDATASET  " +
        "VALUES (NEWROW.OID, NEWROW.CID, NEWROW.SID, NEWROW.BID, NEWROW.NOTE)");

    st.execute("insert into trade.customers values (11)");
    st.execute("insert into trade.securities values (22)");
    PreparedStatement ps = conn.prepareStatement("put into trade.buyorders values (?, ?, ?, ?, ?)");
    ps.setInt(1, 33);
    ps.setInt(2, 11);
    ps.setInt(3, 22);
    ps.setInt(4, 44);
    ps.setString(5, null);
    ps.executeUpdate();
  }

  public void test48641() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    s.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'  BatchTimeInterval 100 milliseconds");

    s.execute("create table m1 (col11 int primary key , col12 int) partition by primary key hdfsstore (myhdfs)");

    s.execute("create table m2 (col21 int primary key, col22 int, constraint fk1 foreign key (col22) references m1 (col11)) " +
        "partition by primary key colocate with (m1) hdfsstore (myhdfs)");

    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/M1");
    s.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    shutDown();

    //restart and do DDL replay
    conn = TestUtil.getConnection();

    //clean
    s = conn.createStatement();
    s.execute("drop table m2");
    s.execute("drop table m1");
    s.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug49294() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    props.put("query-HDFS", "true");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");

    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' ");

    st.execute("create table t1 (col1 int primary key, col2 int) " +
        "partition by column (col2) " +
        "eviction by criteria (col2 > 0) " +
        "evict incoming " +
        "hdfsstore (myhdfs)");

    st.execute("insert into t1 values (1, 11)");

    st.execute("select * from t1 --GEMFIREXD-PROPERTIES queryHDFS=false \n");

    rs = st.getResultSet();

    assertFalse(rs.next());

    st.execute("select * from t1 --GEMFIREXD-PROPERTIES queryHDFS=true \n");

    rs = st.getResultSet();

    assertTrue(rs.next());

    assertEquals(11, rs.getInt(2));

    assertFalse(rs.next());

    st.execute("CREATE PROCEDURE QUERY_TABLE() " +
        "LANGUAGE JAVA " +
        "PARAMETER STYLE JAVA " +
        "MODIFIES SQL DATA " +
        "DYNAMIC RESULT SETS 1 " +
        "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.Rollup.queryTable'");

    CallableStatement cs = conn.prepareCall("CALL QUERY_TABLE() ON TABLE T1");

    cs.execute();

    rs = cs.getResultSet();

    assertTrue(rs.next());

    assertEquals(11, rs.getInt(2));

    assertFalse(rs.next());

    st.execute("drop table t1");

    st.execute("drop hdfsstore myhdfs");

    delete(new File("./myhdfs"));
  }

  public void testRootDirSysProperty() throws Exception {
    // default root dir path testing
    boolean dirStatus = fs.exists(new Path(GfxdConstants.SYS_HDFS_ROOT_DIR_DEF));
    assertFalse(dirStatus);

    Properties props = new Properties();
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir 'myhdfs' ");
    dirStatus = fs.exists(new Path(GfxdConstants.SYS_HDFS_ROOT_DIR_DEF + "/myhdfs"));
    assertTrue(dirStatus);
    st.execute("drop hdfsstore myhdfs");
    st.close();
    conn.close();
    shutDown();

    // connection property for root dir
    dirStatus = fs.exists(new Path("/xd"));
    assertFalse(dirStatus);

    props = new Properties();
    props.put("hdfs-root-dir", "/xd");
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir 'myhdfs' ");
    dirStatus = fs.exists(new Path("/xd/myhdfs"));
    assertTrue(dirStatus);
    st.execute("drop hdfsstore myhdfs");
    st.close();
    conn.close();
    shutDown();
    fs.delete(new Path("/xd"), true);

    // connection property for root dir with absolute path
    dirStatus = fs.exists(new Path("/xd"));
    assertFalse(dirStatus);
    dirStatus = fs.exists(new Path("/absolute"));
    assertFalse(dirStatus);

    props = new Properties();
    props.put("hdfs-root-dir", "/xd");
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir '/absolute' ");
    dirStatus = fs.exists(new Path("/absolute"));
    assertTrue(dirStatus);
    st.execute("drop hdfsstore myhdfs");
    st.close();
    conn.close();
    shutDown();

    // connection property for root dir with absolute path
    dirStatus = fs.exists(new Path("/xd/relative"));
    assertFalse(dirStatus);

    props = new Properties();
    props.put("hdfs-root-dir", "/xd/");
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './relative' ");
    dirStatus = fs.exists(new Path("/xd/relative"));
    assertTrue(dirStatus);
    st.execute("drop hdfsstore myhdfs");
    st.close();
    conn.close();
    shutDown();
  }

  public void testBug48894() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    try {
      st.execute("create table t1 (col1 int primary key, col2 int) partition by column (col2)");
      st.execute("put into t1 values (11, 33)");
      st.execute("put into t1 values (11, 44)");
      fail("PUT DML is updating the partitioning column");
    } catch (SQLException e) {
      if (e.getSQLState().compareTo("0A000") != 0) {
        throw e;
      }
    }
  }

  public void testBug49788() throws Exception {
    Properties props = new Properties();
    props.setProperty("skip-constraint-checks", "true");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table t1 (co1 int primary key, col2 int) partition by primary key");
    st.execute("insert into t1 values (11, 12)");
    st.execute("insert into t1 values (11, 34)");
    st.execute("insert into t1 values (11, 12), (11, 34)");
  }

  public void testBug49661_QueryHint() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table trade.txhistory(cid int, tid int) " +
        "partition by list (tid) " +
        "(VALUES (0, 1, 2, 3, 4, 5), " +
        "VALUES (6, 7, 8, 9, 10, 11), " +
        "VALUES (12, 13, 14, 15, 16, 17))  " +
        "PERSISTENT SYNCHRONOUS " +
        "EVICTION BY CRITERIA ( CID >= 20 ) " +
        "EVICT INCOMING HDFSSTORE (myhdfs)");
    st.execute("create  index index_11 on trade.txhistory ( TID desc  )");
    st.execute("create index index_17 on trade.txhistory ( CID   )");
    PreparedStatement ps = conn.prepareStatement("insert into trade.txhistory values (?, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }
    Thread.sleep(10000);
    st.execute("select * from TRADE.TXHISTORY -- GEMFIREXD-PROPERTIES queryHDFS=true \n where TID >= 0  or CID >= 0");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(100, count);

    st.execute("drop table trade.txhistory");
    st.execute("drop hdfsstore myhdfs");
  }

  public void testBug49661_ConnectionProperty() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put("query-HDFS", "true");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table trade.txhistory(cid int, tid int) " +
        "partition by list (tid) " +
        "(VALUES (0, 1, 2, 3, 4, 5), " +
        "VALUES (6, 7, 8, 9, 10, 11), " +
        "VALUES (12, 13, 14, 15, 16, 17))  " +
        "PERSISTENT SYNCHRONOUS " +
        "EVICTION BY CRITERIA ( CID >= 20 ) " +
        "EVICT INCOMING HDFSSTORE (myhdfs)");
    st.execute("create  index index_11 on trade.txhistory ( TID desc  )");
    st.execute("create index index_17 on trade.txhistory ( CID   )");
    PreparedStatement ps = conn.prepareStatement("insert into trade.txhistory values (?, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }
    Thread.sleep(10000);
    st.execute("select * from TRADE.TXHISTORY where TID >= 0  or CID >= 0");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(100, count);

    st.execute("drop table trade.txhistory");
    st.execute("drop hdfsstore myhdfs");
  }

  public void testRowExpiration() throws Exception {
    Connection conn = TestUtil.getConnection();

    if (isTransactional) {
      return;
    }

    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table t1 (col1 int primary key, col2 int) expire entry with timetolive 15 action destroy hdfsstore (myhdfs)");

    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    // make sure the starting position is correct
    rs = st.executeQuery("select count(*) from t1");
    rs.next();
    assertEquals(NUM_ROWS, rs.getInt(1));
    rs.close();

    // wait for the flush
    st.execute("CALL SYS.HDFS_FLUSH_QUEUE('APP.T1', 0)");

    // wait for expiry
    Thread.sleep(2 * 15000);

    // no rows in memory
    rs = st.executeQuery("select count(*) from t1");
    rs.next();
    assertEquals(0, rs.getInt(1));
    rs.close();

    // all rows in hdfs
    rs = st.executeQuery("select count(*) from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true");
    rs.next();
    assertEquals(NUM_ROWS, rs.getInt(1));
    rs.close();

    st = conn.createStatement();
    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug49794() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table t1 (col1 int primary key, col2 int) partition by primary key");

//          st.execute("create table t2 (col1 int primary key, col2 int) partition by primary key");

    st.execute("create table t3 (col1 int primary key, col2 int) partition by primary key");

//          st.execute("create trigger tg1 NO CASCADE BEFORE INSERT on t1 referencing NEW_TABLE as newtable for each STATEMENT " +
//          "insert into t2 values (select * from newtable)");

//          st.execute("create trigger tg2 NO CASCADE BEFORE INSERT on t1 referencing new as newinsert for each ROW " +
//              "insert into t2 values (newinsert.col1, newinsert.col2)");

    st.execute("create trigger tg3 AFTER INSERT on t1 referencing new as newinsert for each ROW " +
        "insert into t3 values (newinsert.col1, newinsert.col2)");

//          st.execute("create trigger tg4 AFTER INSERT on t1 referencing NEW_TABLE as newtable for each STATEMENT " +
//          "insert into t3 values (select * from newtable)");

//          st.execute("create trigger tg5 NO CASCADE BEFORE UPDATE on t1 referencing OLD_TABLE as oldtable for each STATEMENT " +
//          "update t2 set col2 = col2 + 1 where col1 in (select col1 from oldtable)");

//          st.execute("create trigger tg6 NO CASCADE BEFORE UPDATE on t1 referencing new as newupdate for each ROW " +
//              "update t2 set col2 = col2 + 1 where col1 = newupdate.col1");

    st.execute("create trigger tg7 AFTER UPDATE on t1 referencing new as newupdate for each ROW " +
        "update t3 set col2 = col2 + 1 where col1 = newupdate.col1");

//          st.execute("create trigger tg8 AFTER UPDATE on t1 referencing OLD_TABLE as oldtable for each STATEMENT " +
//          "update t3 set col2 = col2 + 1 where col1 in (select col1 from oldtable)");

    st.execute("insert into t1 values (12, 34)");

    st.execute("update t1 set col2 = col2 + 1 where col1 = 12");

    //PUT DML as UPDATE
    st.execute("put into t1 values (12, 56)");

    //PUT DML as INSERT
    st.execute("put into t1 values (78, 90)");


    Object[][] expectedOutput1 = new Object[][] { new Object[] { 12, 56 }, new Object[] { 78, 90 } };
//          Object[][] expectedOutput2 = new Object[][] { new Object[] { 12, 35 } };
    Object[][] expectedOutput3 = new Object[][] { new Object[] { 12, 35 } };

    st.execute("select * from t1");
    rs = st.getResultSet();
    JDBC.assertUnorderedResultSet(rs, expectedOutput1, false);

//          st.execute("select * from t2");
//          rs = st.getResultSet();
//          JDBC.assertUnorderedResultSet(rs, expectedOutput2, false);

    st.execute("select * from t3");
    rs = st.getResultSet();
    JDBC.assertUnorderedResultSet(rs, expectedOutput3, false);
  }

  public void testStats() throws Exception {
    Properties props = new Properties();
    props.setProperty("mcast-port", AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS) + "");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");

    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs) persistent");
    PreparedStatement ps = conn.prepareStatement("insert into app.t1 values (?, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.addBatch();
    }
    ps.executeBatch();

    ps = conn.prepareStatement("update app.t1 set col2 = ? where col1 = ?");

    for (int i = 0; i < 2; i++) {
      ps.setInt(1, i + 2);
      ps.setInt(2, i);
      ps.addBatch();
    }
    ps.executeBatch();

    LogWriterI18n logger = GemFireCacheImpl.getExisting().getLoggerI18n();
    TestUtil.shutDown();

    conn = TestUtil.getConnection(props);
    st = conn.createStatement();

    //Wait for values to be recovered from disk
    Thread.sleep(5000);

    GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
    Set<PartitionRegionInfo> infos = PartitionRegionHelper.getPartitionRegionInfo(cache);
    long bytesFromInfo = -1;
    for (PartitionRegionInfo info : infos) {
      if (info.getRegionPath().contains("/APP/T1")) {
        PartitionMemberInfo memberInfo = info.getPartitionMemberInfo().iterator().next();
        bytesFromInfo = memberInfo.getSize();
      }
    }

    PartitionedRegion region = (PartitionedRegion)cache.getRegion("/APP/T1");
    DiskRegionStats drStats = region.getDiskRegionStats();
    PartitionedRegionStats prStats = region.getPrStats();
    assertEquals(0, drStats.getNumOverflowOnDisk());
    assertEquals(bytesFromInfo, prStats.getDataStoreBytesInUse());

    st.execute("select * from app.t1 where col1=5");


    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));

  }

  public void testWarningWhenTableNotPersistent() throws Exception {

    Properties props = new Properties();
    //  props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema emp");
    st.execute("set schema emp");
    checkDirExistence("./myhdfs");

    st.execute("create hdfsstore myhdfs namenode 'localhost" + port + "' homedir './myhdfs'");
    st.execute("create table mytab1 (col1 int primary key)");

    SQLWarning sw = st.getWarnings();
    assertNull(sw);


    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs) ");
    sw = st.getWarnings();
    assertNull(sw);


    st.execute("create DiskStore testDiskStore");

    st.execute("create table mytab2 (col1 int primary key) persistent 'testDiskStore' hdfsstore (myhdfs) writeonly");

    sw = st.getWarnings();
    assertNull(sw);

    st.execute("create table mytab3 (col1 int primary key) persistent 'testDiskStore' ");

    sw = st.getWarnings();
    assertNull(sw);

    st.execute("create table mytab4 (col1 int primary key)  hdfsstore (myhdfs) ");

    sw = st.getWarnings();
    String sqlState = sw.getSQLState();
    assertEquals(sqlState, "0150A");
    assertTrue(sw.getMessage().contains(" MYTAB4 "));
    assertNull(sw.getNextWarning());

    st.execute("create table mytab5 (col1 int primary key)  hdfsstore (myhdfs) writeonly");

    sw = st.getWarnings();
    sqlState = sw.getSQLState();
    assertEquals(sqlState, "0150A");
    assertTrue(sw.getMessage().contains(" MYTAB5 "));
    assertNull(sw.getNextWarning());

    st.execute("drop table mytab");
    st.execute("drop table mytab1");
    st.execute("drop table mytab2");
    st.execute("drop table mytab3");
    st.execute("drop table mytab5");
    st.execute("drop table mytab4");

    st.execute("drop hdfsstore myhdfs");

    delete(new File("./myhdfs"));
  }

  public void testIteratorReturnsRemovedQueueEvents() throws Exception {
    reduceLogLevelForTest("config");

    setupConnection();

    Connection conn = jdbcConn;
    conn.setAutoCommit(false);

    Statement stmt = conn.createStatement();
    checkDirExistence("./myhdfs");

    stmt.execute("create hdfsstore hdfsdata namenode 'hdfs://localhost:" + port + "' homedir './myhdfs' "
        + "batchtimeinterval 100000 seconds");

    stmt.execute("create table t1("
        + "id int"
        + ")  persistent hdfsstore (hdfsdata) buckets 1");

    // some inserts
    for (int i = 1; i <= 100; i++) {
      stmt.executeUpdate("insert into t1 values (" + i + " )");
    }

    // run a query against HDFS data. Since nothing is dispatched to hdfs yet, it will
    // create a queue iterator
    assertTrue(stmt.execute("select * from  t1 -- GEMFIREXD-PROPERTIES queryHDFS=true"));
    ResultSet rs = stmt.getResultSet();
    int index = 0;
    Set<Integer> ids = new HashSet<Integer>();
    // fetch few items
    while (rs.next()) {
      ids.add(rs.getInt("id"));
      if (index > 10)
        break;
      index++;
    }

    // flush the queue after few fetches from the iterator.
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    Statement stmt1 = conn.createStatement();

    stmt1.execute("call SYS.HDFS_FLUSH_QUEUE('APP.T1', 30000)");
    stmt1.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    // the items that are now returned are removed from the queue but should
    // be available to the iterator.
    while (rs.next()) {
      ids.add(rs.getInt("id"));
    }

    // verify that all items are fetched by the iterator
    assertEquals(100, ids.size());

    for (int i = 1; i <= 100; i++) {
      assertTrue(ids.contains(i));
    }

    conn.commit();
    stmt.execute("drop table t1");
    stmt.execute("drop hdfsstore hdfsdata");
    delete(new File("./myhdfs"));
  }

  // SNAP-1706
  public void _testforcefilerollover() throws Exception {

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;


    st.execute("create hdfsstore myhdfs namenode 'hdfs://localhost:" + port + "' homedir './myhdfs'  batchtimeinterval 1 milliseconds ");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs) writeonly buckets 73");

    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");

    int NUM_ROWS = 300;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    assertEquals(getExtensioncount("MYHDFS", ".shop.tmp"), 73);
    assertEquals(getExtensioncount("MYHDFS", ".shop"), 0);

    // rollover files greater than 100 KB. Since none of them would be bigger
    // than 100K. No rollover should occur
    st.execute("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('" + "APP.t1" + "', 100)");
    assertEquals(getExtensioncount("MYHDFS", ".shop.tmp"), 73);
    assertEquals(getExtensioncount("MYHDFS", ".shop"), 0);

    st.execute("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('" + "APP.t1" + "' , 0)");

    assertEquals(getExtensioncount("MYHDFS", ".shop.tmp"), 0);
    assertEquals(getExtensioncount("MYHDFS", ".shop"), 73);

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  protected int getExtensioncount(final String hdfsstore, final String extension) throws Exception {
    int counter = 0;
    HDFSStoreImpl hdfsStore = (HDFSStoreImpl)GemFireCacheImpl.getInstance().findHDFSStore(hdfsstore);
    FileSystem fs = hdfsStore.getFileSystem();
    try {
      Path basePath = new Path(hdfsStore.getHomeDir());

      RemoteIterator<LocatedFileStatus> files = fs.listFiles(basePath, true);

      while (files.hasNext()) {
        HashMap<String, String> entriesMap = new HashMap<String, String>();
        LocatedFileStatus next = files.next();
        if (next.getPath().getName().endsWith(extension))
          counter++;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return counter;
  }

  public void testforcefilerolloverexceptions() throws Exception {

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;


    st.execute("create hdfsstore myhdfs namenode 'hdfs://localhost:" + port + "' homedir './myhdfs'  batchtimeinterval 1 milliseconds ");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs) writeonly");
    st.execute("create table t2 (col1 int primary key, col2 int) hdfsstore (myhdfs) ");
    st.execute("create table t3 (col1 int primary key, col2 int)");

    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");

    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    boolean exThrown = false;

    try {
      st.execute("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('" + "APP.T2" + "', 0)");
    } catch (java.sql.SQLFeatureNotSupportedException e) {
      exThrown = true;
    }
    assertTrue("Should have thrown exception for non write only table", exThrown);
    exThrown = false;


    try {
      st.execute("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('" + "APP.t3" + "', 0)");
    } catch (java.sql.SQLFeatureNotSupportedException e) {
      exThrown = true;
    }
    assertTrue("Should have thrown exception for non hdfs table", exThrown);
    exThrown = false;

    // Delete the underlying folder to get an IOException
    delete(new File("./hdfs-test-cluster"));

    addExpectedException(java.io.IOException.class);
    addExpectedException(java.io.EOFException.class);
    addExpectedException(java.io.FileNotFoundException.class);

    try {
      st.execute("CALL SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('" + "APP.T1" + "', 0)");
    } catch (java.sql.SQLException e) {
      assertTrue("Should be a IOException",
          e.getCause() instanceof java.io.IOException);
      exThrown = true;
    }
    assertTrue("Should fail with an IOException", exThrown);
    exThrown = false;

    st.execute("drop table t1");
    st.execute("drop table t2");
    st.execute("drop table t3");
    st.execute("drop hdfsstore myhdfs");

    tearDown();

    removeExpectedException(java.io.IOException.class);
    removeExpectedException(java.io.EOFException.class);
    removeExpectedException(java.io.FileNotFoundException.class);
  }
}
