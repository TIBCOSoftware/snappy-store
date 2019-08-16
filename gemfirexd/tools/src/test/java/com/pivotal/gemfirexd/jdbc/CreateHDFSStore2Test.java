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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import io.snappydata.test.dunit.AvailablePortHelper;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;

import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.checkHDFSIteratorResultSet;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.delete;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.deleteMiniClusterDir;
import static com.pivotal.gemfirexd.jdbc.CreateHDFSStoreTest.initMiniCluster;

public class CreateHDFSStore2Test extends JdbcTestBase {

  private int port;
  private MiniDFSCluster cluster;
  private FileSystem fs = null;

  public CreateHDFSStore2Test(String name) {
    super(name);
  }

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

  //various syntax tests
  public void testHDFSStoreDDLSyntax() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put(DistributionConfig.MCAST_TTL_NAME, "0");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    for (int i = 1; i < 7; i++) {
      String fn = new String("./hs" + i);
      checkDirExistence(fn);
    }
    //TODO: need more tests
    Object[][] CreateHDFSStoreDDL = {
        { "create hdfsstore hs1 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs1'", null },
        { "create hdfsstore hs2 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs2' queuepersistent true diskstorename mydisk", null },
        { "create hdfsstore hs3 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs3' MinorCompact false MinorCompactionThreads 5", null },
        { "create hdfsstore hs4 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs4' batchsize 14 majorcompactionThreads 3", null },
        { "create hdfsstore hs5 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs5' blockcachesize 23 majorcompactionThreads 3", null },
        { "create hdfsstore hs6 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs6' clientconfigfile './file1' blockcachesize 78", null },
        { "create hdfsstore hs7 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs6' maxWriteOnlyfilesize 6 WriteOnlyfilerolloverinterval 7 seconds", null } };

    JDBC.SQLUnitTestHelper(st, CreateHDFSStoreDDL);

    Object[][] DropHDFSStoreDDL = {
        { "drop hdfsstore hs1", null },
        { "drop hdfsstore hs2", null },
        { "drop hdfsstore hs3", null },
        { "drop hdfsstore hs4", null },
        { "drop hdfsstore hs5", null },
        { "drop hdfsstore hs6", null },
        { "drop hdfsstore hs7", null } };

    JDBC.SQLUnitTestHelper(st, DropHDFSStoreDDL);

    for (int i = 1; i <= 7; i++) {
      String fn = new String("./hs" + i);
      delete(new File(fn));
    }
  }

  public void testHDFSWriteOnly() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put(DistributionConfig.MCAST_TTL_NAME, "0");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
    st.execute("create table mytab (col1 int primary key) hdfsstore (myhdfs) writeonly");
    LocalRegion region = (LocalRegion)Misc.getRegion("APP/MYTAB", true, false);
    RegionAttributes<?, ?> ra = region.getAttributes();
    assertTrue(ra.getHDFSWriteOnly());
    try {
      st.execute("drop hdfsstore myhdfs");
    } catch (SQLException stde) {
      if (!stde.getSQLState().equals("X0Y25")) {
        throw stde;
      }
    }

    PreparedStatement ps = conn.prepareStatement("insert into mytab values (?)");
    final int NUM_ROWS = 200;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.executeUpdate();
    }

    // make sure data is written to HDFS store
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(15000);

    st.execute("drop table mytab");
    st.execute("drop hdfsstore myhdfs");

    //Comment out for #48511
    delete(new File("./myhdfs"));
  }

  public void testPutDML() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put(DistributionConfig.MCAST_TTL_NAME, "0");
    Connection conn = TestUtil.getConnection(props);
    // Connection conn = getConnection();
    Statement s = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    s.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds ");
    s.execute("create table m1 (col1 int primary key , col2 int) "
        + " hdfsstore (myhdfs)");
    // check impact on GfxdIndexManager
    s.execute("create index idx1 on m1(col2)");
    s.execute("insert into m1 values (11, 22)");
    // put as update
    s.execute("put into m1 values (11, 33)");
    // put as insert
    s.execute("put into m1 values (66, 77)");

    //verify
    s.execute("select * from m1 where col2 = 22");
    rs = s.getResultSet();
    assertFalse(rs.next());

    s.execute("select * from m1 where col2 = 33");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(33, rs.getInt(2));

    s.execute("select * from m1 where col2 = 77");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(77, rs.getInt(2));

    s.execute("select * from m1");
    rs = s.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);

    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/M1");
    s.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
//    Thread.sleep(10000);

    shutDown();

    //restart
    Properties props2 = new Properties();
    mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props2.put("mcast-port", String.valueOf(mcastPort));
    props2.put(DistributionConfig.MCAST_TTL_NAME, "0");
    conn = TestUtil.getConnection(props2);

    s = conn.createStatement();
    //nothing in memory
    s.execute("select * from m1");
    rs = s.getResultSet();
    assertFalse(rs.next());

    //verify
    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 11");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(33, rs.getInt(2));

    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 66");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(77, rs.getInt(2));

    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = s.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);

    s.execute("drop table m1");
    s.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testPutDML_Loner() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    s.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'  BatchTimeInterval 100 milliseconds");
    s.execute("create table m1 (col1 int primary key , col2 int) "
        + " hdfsstore (myhdfs)");
    // check impact on GfxdIndexManager
    s.execute("create index idx1 on m1(col2)");
    s.execute("insert into m1 values (11, 22)");
    // put as update
    s.execute("put into m1 values (11, 33)");
    // put as insert
    s.execute("put into m1 values (66, 77)");

    //verify
    s.execute("select * from m1 where col2 = 22");
    rs = s.getResultSet();
    assertFalse(rs.next());

    s.execute("select * from m1 where col2 = 33");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(33, rs.getInt(2));

    s.execute("select * from m1 where col2 = 77");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(77, rs.getInt(2));

    s.execute("select * from m1");
    rs = s.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);

    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/M1");
    s.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
//    Thread.sleep(10000);

    shutDown();

    //restart
    conn = TestUtil.getConnection();

    //verify
    s = conn.createStatement();

    //nothing in memory
    s.execute("select * from m1");
    rs = s.getResultSet();
    assertFalse(rs.next());

    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 11");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(33, rs.getInt(2));

    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 66");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals(77, rs.getInt(2));

    s.execute("select * from m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = s.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);

    s.execute("drop table m1");
    s.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testTruncateTableHDFS() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'  BatchTimeInterval 100 milliseconds");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(10000);

    shutDown();

    //restart to clean the memory
    conn = TestUtil.getConnection();

    st = conn.createStatement();
    st.execute("select * from t1");
    rs = st.getResultSet();
    //nothing in memory
    assertFalse(rs.next());

    //now test with query hint to enable HDFS iterator, make sure data is in HDFS
    st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
    rs = st.getResultSet();
    checkHDFSIteratorResultSet(rs, NUM_ROWS);

    // TRUNCATE TABLE should also remove data in HDFS
    st.execute("truncate table t1");

    //now query again without the query hint, it should return nothing
    st.execute("select * from t1");
    rs = st.getResultSet();
    assertFalse(rs.next());

    //now test with query hint to enable HDFS iterator again, it should return nothing
    st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
    rs = st.getResultSet();
    checkHDFSIteratorResultSet(rs, 0);

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  //UPDATE/DELETE should always go to HDFS regardless of query hint or connection property
  public void testUpdateDeletePrimaryKeyHDFS() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    PreparedStatement ps1 = conn.prepareStatement("update t1 set col2 = ? where col1 = ?");
    PreparedStatement ps2 = conn.prepareStatement("delete from t1 where col1 = ?");
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps1.setInt(1, i + 10);
        ps1.setInt(2, i);
        ps1.executeUpdate();
      } else {
        ps2.setInt(1, i);
        ps2.executeUpdate();
      }
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(10000);

    shutDown();

    //restart to clean the memory
    conn = TestUtil.getConnection();

    st = conn.createStatement();
    st.execute("select * from t1");
    rs = st.getResultSet();
    //nothing in memory
    assertFalse(rs.next());

    //now check the data is in HDFS, queryHDFS = false by default
    ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = ?");
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      rs = ps.executeQuery();
      if (i % 2 == 0) {
        assertTrue(rs.next());
        assertEquals(i + 10, rs.getInt(2));
        assertFalse(rs.next());
      } else {
        assertFalse(rs.next());
      }
    }

    ps1 = conn.prepareStatement("update t1 set col2 = ? where col1 = ?");
    //update again, note the memory is empty, data in HDFS only
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps1.setInt(1, i + 20);
        ps1.setInt(2, i);
        ps1.executeUpdate();
      }
    }

    //after update, check the data in HDFS
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps.setInt(1, i);
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(i + 20, rs.getInt(2));
        assertFalse(rs.next());
      }
    }

    ps2 = conn.prepareStatement("delete from t1 where col1 = ?");
    //delete again, note the memory is empty, data in HDFS only
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps2.setInt(1, i);
        ps2.executeUpdate();
      }
    }

    //after delete, check the data in HDFS
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      rs = ps.executeQuery();
      assertFalse(rs.next());
    }

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  //UPDATE/DELETE should always go to HDFS regardless of query hint or connection property
  public void testUpdateDeleteNonPrimaryKeyHDFS() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
    st.execute("create table t1 (col1 int primary key, col2 int, col3 int) hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.setInt(3, i + 10);
      ps.executeUpdate();
    }

    PreparedStatement ps1 = conn.prepareStatement("update t1 set col2 = ? where col3 = ?");
    PreparedStatement ps2 = conn.prepareStatement("delete from t1 where col3 = ?");
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps1.setInt(1, i + 100);
        ps1.setInt(2, i + 10);
        ps1.executeUpdate();
      } else {
        ps2.setInt(1, i + 10);
        ps2.executeUpdate();
      }
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(10000);

    shutDown();

    //restart to clean the memory
    conn = TestUtil.getConnection();

    st = conn.createStatement();
    st.execute("select * from t1");
    rs = st.getResultSet();
    //nothing in memory
    assertFalse(rs.next());

    //now check the data is in HDFS, queryHDFS = false by default
    ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = ?");
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      rs = ps.executeQuery();
      if (i % 2 == 0) {
        assertTrue(rs.next());
        assertEquals(i + 100, rs.getInt(2));
        assertFalse(rs.next());
      } else {
        assertFalse(rs.next());
      }
    }

    ps1 = conn.prepareStatement("update t1 set col2 = ? where col3 = ?");
    //update again, note the memory is empty, data in HDFS only
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps1.setInt(1, i + 200);
        ps1.setInt(2, i + 10);
        ps1.executeUpdate();
      }
    }

    //after update, check the data in HDFS
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps.setInt(1, i);
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(i + 200, rs.getInt(2));
        assertFalse(rs.next());
      }
    }

    ps2 = conn.prepareStatement("delete from t1 where col3 = ?");
    //delete again, note the memory is empty, data in HDFS only
    for (int i = 0; i < NUM_ROWS; i++) {
      if (i % 2 == 0) {
        ps2.setInt(1, i + 10);
        ps2.executeUpdate();
      }
    }

    //after delete, check the data in HDFS
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      rs = ps.executeQuery();
      assertFalse(rs.next());
    }

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }


  /**
   * Test of what happens to the tombstone count when we read a tombstone from HDFS
   */
  public void testReadTombstoneFromHDFS() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BATCHTIMEINTERVAL 10 milliseconds");
    st.execute("create table t1 (col1 int primary key, col2 int, col3 int) hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?, ?)");

    //Insert 2 rows
    int NUM_ROWS = 2;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.setInt(3, i + 10);
      ps.addBatch();
    }
    ps.executeBatch();


    Thread.sleep(1000);

    //delete one of them
    PreparedStatement ps2 = conn.prepareStatement("delete from t1 where col1 = ?");
    ps2.setInt(1, 1);
    ps2.executeUpdate();

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(10000);

    //We could wait for the tombstone to expire, but it's easier just
    //to restart to clear the operational data.
    shutDown();
    conn = TestUtil.getConnection();

    st = conn.createStatement();
    st.execute("select * from t1");
    rs = st.getResultSet();
    //nothing in memory
    assertFalse(rs.next());

    //Make sure the in memory count is zero
    st.execute("select count(*) from t1");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    //nothing in memory
    assertFalse(rs.next());

    //This will read some tombstones
    st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
      assertEquals(0, rs.getInt(1));
    }
    assertEquals(1, count);
    st.close();
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    st = conn.createStatement();
    //Do a query to make sure we read and then remove a tombstone
    st.executeUpdate("delete from t1 where col3=5\n");
    conn.commit();

    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);

    //Make sure the in memory count didn't get screwed up.
    st.execute("select count(*) from t1");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    //nothing in memory
    assertFalse(rs.next());


    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testDropStore() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
    try {
      st.execute("drop hdfsstore myhdfs");
      fail("Should have received an exception");
    } catch (SQLException exected) {
      //do nothing
    }

    //insert some data
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    st.execute("drop table t1");
    //Now this should work
    st.execute("drop hdfsstore myhdfs");
  }

  public void testPutDML_ReplicatedTable() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    ResultSet rs = null;

    s.execute("create table hdfsCustomer " +
        "(warehouseId integer not null, " +
        "districtId integer not null, " +
        "districtBalance decimal(12,2)) " +
        "replicate");
    s.execute("alter table hdfsCustomer add constraint pk_hdfsCustomer primary key (warehouseId, districtId)");
    s.execute("put into hdfsCustomer values (1, 1, 1)");
    s.execute("put into hdfsCustomer values (1, 1, 2)");

    //#48894
    s.execute("create table test2 (col1 int primary key) replicate");
    s.execute("insert into test2 values (5), (4), (3)");

    s.execute("create table test3 (col1 int primary key) replicate ");
    s.execute("insert into test3 values (1), (2), (3)");

    // put as update
    s.execute("put into test2 values (3)");

    // put with sub-select
    s.execute("put into test2 select * from test3 where col1 <= 2");

    s.execute("put into test2 select * from test3");
  }

  public void testEvictionSyntaxHDFSTable() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    s.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' ");

    // eviction of data more than 10 seconds old
    s.execute("create table evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ") partition by column(id) "
        + "persistent hdfsstore (myhdfs) "
        + "eviction by criteria (" + "{fn TIMESTAMPDIFF(SQL_TSI_SECOND, "
        + "ts, CURRENT_TIMESTAMP)} > 10"
        + ") eviction frequency 5 seconds");


    try {
      s.execute("create table m1 (col1 int primary key , col2 int) "
          + " hdfsstore (myhdfs) EVICTION BY LRUMEMSIZE 1000 EVICTACTION DESTROY");
      fail("Expect a failure. Reason: LRU eviction is not supported for HDFS tables.");
    } catch (SQLException e) {
      if (e.getSQLState().compareTo("0A000") != 0) {
        throw e;
      }
    }
    try {
      s.execute("create table m1 (col1 int primary key , col2 int) "
          + " hdfsstore (myhdfs) EVICTION BY LRUCOUNT 2 EVICTACTION DESTROY");
      fail("Expect a failure. Reason: LRU eviction is not supported for HDFS tables.");
    } catch (SQLException e) {
      if (e.getSQLState().compareTo("0A000") != 0) {
        throw e;
      }
    }
    try {
      s.execute("create table m1 (col1 int primary key , col2 int) "
          + " hdfsstore (myhdfs) EVICTION BY LRUHEAPPERCENT EVICTACTION DESTROY");
      fail("Expect a failure. Reason: LRU eviction is not supported for HDFS tables.");
    } catch (SQLException e) {
      if (e.getSQLState().compareTo("0A000") != 0) {
        throw e;
      }
    }

    s.execute("drop table evictTable");
    s.execute("drop hdfsstore myhdfs");
  }

  public void testBatchUpdateHDFS() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");

    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into app.t1 values (?, ?)");
    for (int i = 0; i < 10; i++) {
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

    st.execute("drop table t1");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));

  }

  public void testBug48939() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create table mytab (col1 varchar(5) primary key) partition by primary key persistent hdfsstore (myhdfs) writeonly");
    st.execute("insert into mytab values ('abc3')");

    PreparedStatement ps = conn.prepareStatement("delete from mytab where col1 >= ? and col1 <= ?");
    ps.setString(1, "abc0");
    ps.setString(2, "abc5");
    int i = ps.executeUpdate();
    assertEquals(1, i);

    st.execute("drop table mytab");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug48944_Replicate() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table trade.securities (sec_id int primary key) replicate");
    st.execute("create table trade.customers (cid int primary key) replicate");
    st.execute("create table trade.buyorders (oid int primary key, cid int, sid int, " +
        "constraint bo_cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, " +
        "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)) replicate");
    st.execute("insert into trade.securities values (11)");
    st.execute("insert into trade.customers values (12)");
    st.execute("put into trade.buyorders values (1, 12, 11)");
    st.execute("put into trade.buyorders values (1, 12, 11)");
  }

  public void testBug48944_Partition() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table trade.securities (sec_id int primary key) ");
    st.execute("create table trade.customers (cid int primary key) ");
    st.execute("create table trade.buyorders (oid int primary key, cid int, sid int, " +
        "constraint bo_cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, " +
        "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)) ");
    st.execute("insert into trade.securities values (11)");
    st.execute("insert into trade.customers values (12)");
    st.execute("put into trade.buyorders values (1, 12, 11)");
    st.execute("put into trade.buyorders values (1, 12, 11)");

    st.execute("create table trade.portfolio (cid int, sid int, tid int, constraint portf_pk primary key (cid, sid)) " +
        "partition by column (tid)");
    st.execute("create table trade.sellorders (oid int primary key, cid int, sid int, " +
        "constraint so_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict) " +
        "partition by primary key");
    st.execute("insert into trade.portfolio values (1, 11, 111)");
    st.execute("put into trade.sellorders values (2, 1, 11)");
    st.execute("put into trade.sellorders values (3, 1, 11)");
    st.execute("put into trade.sellorders values (3, 1, 11)");

    st.execute("create table t1 (col1 int primary key, col2 int, constraint t1_uq unique (col2))");
    st.execute("put into t1 values (1, 11)");
    st.execute("put into t1 values (2, 22)");
    try {
      st.execute("put into t1 values (1, 22)");
      fail("Expect unique constraint violation");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().compareTo("23505") != 0) {
        throw sqle;
      }
    }
    try {
      st.execute("put into t1 values (1, 11)");
      fail("Expect unique constraint violation");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().compareTo("23505") != 0) {
        throw sqle;
      }
    }

    st.execute("create table t2 (col1 int primary key, col2 int) partition by column (col2)");
    st.execute("put into t2 values (1, 11)");
    st.execute("put into t2 values (1, 11)");
    try {
      st.execute("insert into t2 values (1, 22)");
      fail("Expect primary key (unique) constraint violation");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().compareTo("23505") != 0) {
        throw sqle;
      }
    }
  }

  public void testBug49004() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("CREATE TABLE trade (t_id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY, " +
        " t_st_id CHAR(4) NOT NULL,  " +
        "t_is_cash SMALLINT NOT NULL CHECK (t_is_cash in (0, 1))) " +
        "EVICTION BY CRITERIA ( t_st_id = 'CMPT') " +
        "EVICT INCOMING HDFSSTORE (myhdfs)");

    st.execute("drop table trade");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testBug48983() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");

    st.execute("create table trade.customers (cid int primary key) partition by primary key hdfsstore (myhdfs)");
    st.execute("create table trade.securities (sec_id int primary key) partition by primary key hdfsstore (myhdfs)");
    st.execute("create table trade.buyorders (oid int primary key, cid int, sid int, bid int, " +
        "constraint bo_cust_fk foreign key (cid) references trade.customers (cid), " +
        "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)) " +
        "partition by column (bid) hdfsstore (myhdfs)");

    st.execute("insert into trade.customers values (1)");
    st.execute("insert into trade.securities values (11)");

    PreparedStatement ps = conn.prepareStatement("insert into trade.buyorders values (?, 1, 11, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.executeUpdate();
    }
    st.execute("select * from trade.buyorders");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(100, count);

    //make sure data is written to HDFS
    String qname = null;
    qname = HDFSStoreFactoryImpl.getEventQueueName("/TRADE/CUSTOMERS");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    qname = HDFSStoreFactoryImpl.getEventQueueName("/TRADE/SECURITIES");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    qname = HDFSStoreFactoryImpl.getEventQueueName("/TRADE/BUYORDERS");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    shutDown();

    //restart
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    conn = TestUtil.getConnection();
    st = conn.createStatement();

    st.execute("insert into trade.customers values (2)");
    st.execute("insert into trade.securities values (22)");
    ps = conn.prepareStatement("insert into trade.buyorders values (?, 2, 22, ?)");
    for (int i = 100; i < 200; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.executeUpdate();
    }

    st.execute("select * from trade.buyorders");
    rs = st.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(100, count);

    //Now with connection property query-HDFS=true
    //Make sure that it doesn't pick up cached query plan with query-HDFS=false
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props2 = new Properties();
    props2.put("query-HDFS", "true");
    conn = TestUtil.getConnection(props2);
    st = conn.createStatement();

    st.execute("select * from trade.buyorders");
    rs = st.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(200, count);

    st.execute("drop table trade.buyorders");
    st.execute("drop table trade.customers");
    st.execute("drop table trade.securities");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }
}
