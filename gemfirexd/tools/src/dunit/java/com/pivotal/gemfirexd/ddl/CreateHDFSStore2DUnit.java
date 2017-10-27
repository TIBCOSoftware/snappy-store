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

package com.pivotal.gemfirexd.ddl;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.AbstractHoplogOrganizer;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HdfsSortedOplogOrganizer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

public class CreateHDFSStore2DUnit extends DistributedSQLTestBase {

  public CreateHDFSStore2DUnit(String name) {
    super(name);
  }

  /**
   * use the old value of HLL_CONSTANT to write some hoplogs.
   * stop the servers, upgrade the value of HLL_CONSTANT make
   * sure that a major compaction automatically runs and validate
   * the count_estimate()
   */
  public void testUpgradeHLLConstant() throws Exception {
    dotestUpgradeHLLConstant(false);
  }

  /**
   * test that major compaction occurs even when there is only
   * one major compacted file
   */
  public void testUpgradeHLLConstantOneHoplog() throws Exception {
    dotestUpgradeHLLConstant(true);
  }

  private void dotestUpgradeHLLConstant(final boolean compactBeforeShutdown) throws Exception {
    // Start one client a two servers
    // use the old HLL_CONSTANT
    invokeInEveryVM(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        HdfsSortedOplogOrganizer.HLL_CONSTANT = 0.1;
        return null;
      }
    });

    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs;
    checkDirExistence(homeDir);
    assertEquals(0, getNumberOfMajorCompactedFiles(homeDir));

    st.execute("create schema hdfs");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 1000 milliseconds ");
    st.execute("create table hdfs.m1 (col1 int primary key , col2 int) partition"
        + " by primary key buckets 2 persistent hdfsstore (myhdfs)");

    for (int i = 1; i < 150; i++) {
      st.execute("insert into hdfs.m1 values (" + i + ", " + i * 10 + ")");
      if (i % 10 == 0) {
        // flush 10 ops in each file
        String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
        st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
      }
    }

    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    if (compactBeforeShutdown) {
      st.execute("call SYS.HDFS_FORCE_COMPACTION('hdfs.m1', 0)");
      assertEquals(2, getNumberOfMajorCompactedFiles(homeDir)); // one per bucket
    }

    //shutdown and restart
    stopAllVMs();

    // update the HLL_CONSTANT
    invokeInEveryVM(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        HdfsSortedOplogOrganizer.HLL_CONSTANT = 0.03;
        return null;
      }
    });

    long timeBeforeRestart = System.currentTimeMillis();

    restartVMNums(-1, -2);
    restartVMNums(1);

    // wait for the compaction to complete
    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        return getNumberOfMajorCompactedFiles(homeDir) == 2; // one per bucket
      }

      @Override
      public String description() {
        return "expected 2 major compacted files, found " + getNumberOfMajorCompactedFiles(homeDir);
      }
    }, 30 * 1000, 1000, true);

    conn = TestUtil.getConnection();
    st = conn.createStatement();

    assertTrue(st.execute("values SYS.HDFS_LAST_MAJOR_COMPACTION('hdfs.m1')"));
    rs = st.getResultSet();
    rs.next();
    assertTrue(rs.getTimestamp(1).getTime() >= timeBeforeRestart);

    st.execute("values COUNT_ESTIMATE('hdfs.m1')");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
      assertTrue("estimate:" + rs.getLong(1), Math.abs(rs.getLong(1) - 150) < 6); //3.25% error
    }
    assertEquals(1, count);
    st.execute("drop table hdfs.m1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  public void testCount() throws Exception {
    // Start one client a three servers
    startVMs(1, 3);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs;

    checkDirExistence(homeDir);
    st.execute("create schema hdfs");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "'  BatchTimeInterval 100 milliseconds");
    st.execute("create table hdfs.m1 (col1 int primary key , col2 int) partition by primary key redundancy 1 hdfsstore (myhdfs)");

    for (int i = 0; i < 300; i++) {
      st.execute("insert into hdfs.m1 values (" + i + ", " + i * 10 + ")");
    }

    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    for (int i = 300; i < 600; i++) {
      st.execute("insert into hdfs.m1 values (" + i + ", " + i * 10 + ")");
    }

    //make sure data is written to HDFS
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    st.execute("select count(*) from hdfs.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();
    rs.next();
    assertEquals(600, rs.getInt(1));

    //shutdown and restart
    stopAllVMs();
    restartVMNums(-1, -2, -3);
    restartVMNums(1);

    conn = TestUtil.getConnection();
    st = conn.createStatement();
    st.execute("select count(*) from hdfs.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
      assertEquals(600, rs.getLong(1));
    }
    assertEquals(1, count);

    stopVMNum(-1);
    Thread.sleep(3000);
    st.execute("select count(*) from hdfs.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
      assertEquals(600, rs.getLong(1));
    }
    assertEquals(1, count);

    st.execute("drop table hdfs.m1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  public void testForceCompact() throws Exception {
    doForceCompact(false);
  }

  public void testSyncForceCompact() throws Exception {
    doForceCompact(true);
  }

  private void doForceCompact(final boolean isSynchronous) throws Exception {
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    checkDirExistence(homeDir);
    assertEquals(0, getNumberOfMajorCompactedFiles(homeDir));
    st.execute("create schema hdfs");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 1000 milliseconds");
    st.execute("create table hdfs.m1 (col1 int primary key , col2 int) partition" +
        " by primary key buckets 2 hdfsstore (myhdfs)");

    // create hoplogs
    for (int i = 1; i <= 120; i++) {
      st.execute("insert into hdfs.m1 values(" + i + ", " + i * 10 + ")");
      if (i % 10 == 0) {
        // flush 10 ops in each file
        String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
        st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
      }
    }
    assertEquals(0, getNumberOfMajorCompactedFiles(homeDir));

    assertTrue(st.execute("values SYS.HDFS_LAST_MAJOR_COMPACTION('hdfs.m1')"));
    ResultSet rs = st.getResultSet();
    rs.next();
    assertEquals(0, rs.getTimestamp(1).getTime());
    long b4Compaction = System.currentTimeMillis();
    st.execute("call SYS.HDFS_FORCE_COMPACTION('hdfs.m1', " + (isSynchronous ? 0 : 1) + ")");
    if (isSynchronous) {
      // for synchronous compaction also check the last major compaction time
      assertTrue(st.execute("values SYS.HDFS_LAST_MAJOR_COMPACTION('hdfs.m1')"));
      rs = st.getResultSet();
      rs.next();
      assertTrue(rs.getTimestamp(1).getTime() >= b4Compaction);
    } else {
      // wait for the compaction to complete
      waitForCriterion(new WaitCriterion() {
        @Override
        public boolean done() {
          return getNumberOfMajorCompactedFiles(homeDir) == 2; // one per bucket
        }

        @Override
        public String description() {
          return "expected 2 major compacted files, found " +
              getNumberOfMajorCompactedFiles(homeDir);
        }
      }, 30 * 1000, 1000, true);
    }
    assertEquals(2, getNumberOfMajorCompactedFiles(homeDir));

    st.execute("drop table hdfs.m1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  public void testFlushQueue() throws Exception {
    doFlushQueue(false, false);
  }

  public void testFlushQueueColocate() throws Exception {
    doFlushQueue(false, true);
  }

  public void testFlushQueueWO() throws Exception {
    doFlushQueue(true, false);
  }

  private void doFlushQueue(boolean wo, boolean colo) throws Exception {
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    checkDirExistence(homeDir);
    st.execute("create schema hdfs");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 300000 milliseconds");
    st.execute("create table hdfs.m1 (col1 int primary key , col2 int) partition" +
        " by primary key buckets 2 hdfsstore (myhdfs) " + (wo ? "writeonly" : ""));

    // create queued entries
    for (int i = 1; i <= 120; i++) {
      st.execute("insert into hdfs.m1 values(" + i + ", " + i * 10 + ")");
    }

    if (!wo && colo) {
      st.execute("create table hdfs.m2 (col1 int primary key , col2 int) partition" +
          " by primary key colocate with (hdfs.m1) buckets 2 hdfsstore (myhdfs)");
      for (int i = 1; i <= 120; i++) {
        st.execute("insert into hdfs.m2 values(" + i + ", " + i * 10 + ")");
      }
    }

    // flush queue to hoplogs
    st.execute("call SYS.HDFS_FLUSH_QUEUE('hdfs.m1', 30000)");

    Runnable verify = new SerializableRunnable() {
      @Override
      public void run() {
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            return getQueueSize() == 0;
          }

          @Override
          public String description() {
            return "expected queue size == 0, found " + getQueueSize();
          }

          private int getQueueSize() {
            return ((PartitionedRegion)Misc.getGemFireCache().getRegion("/HDFS/M1"))
                .getHDFSEventQueueStats().getEventQueueSize();
          }
        }, 30000, 1000, true);
      }
    };

    serverExecute(1, verify);
    serverExecute(2, verify);

    if (colo) {
      st.execute("drop table hdfs.m2");
    }
    st.execute("drop table hdfs.m1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  public void testForceFileRollover() throws Exception {
    // Start one client and two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    checkDirExistence(homeDir);
    clientSQLExecute(1, "create schema hdfs");
    clientSQLExecute(1, "create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 1 milliseconds");
    clientSQLExecute(1, "create table hdfs.m1 (col1 int primary key , col2 int) partition" +
        " by primary key hdfsstore (myhdfs) writeonly buckets 73");

    for (int i = 1; i <= 200; i++) {
      clientSQLExecute(1, "insert into hdfs.m1 values(" + i + ", " + i * 10 + ")");
    }

    // create a colocated table
    serverSQLExecute(1, "create table hdfs.m2 (col1 int primary key , col2 int) partition" +
        " by primary key colocate with (hdfs.m1) buckets 73 hdfsstore (myhdfs) writeonly");
    for (int i = 1; i <= 200; i++) {
      serverSQLExecute(1, "insert into hdfs.m2 values(" + i + ", " + i * 10 + ")");
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
    serverSQLExecute(1, "CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop.tmp", true, "HDFS_M1"));
    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop", false, "HDFS_M1"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop.tmp", true, "HDFS_M2"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop", false, "HDFS_M2"));

    // rollover files from server
    serverSQLExecute(1, "call SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('hdfs.m1', 0)");

    // only files of single HDFS.M1 would be rolled over
    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop.tmp", false, "HDFS_M1"));
    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop", true, "HDFS_M1"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop.tmp", true, "HDFS_M2"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop", false, "HDFS_M2"));

    // rollover files from client
    clientSQLExecute(1, "call SYS.HDFS_FORCE_WRITEONLY_FILEROLLOVER('HDFS.M2', 0)");

    // now files of HDFS.M2 would also be rolled over
    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop.tmp", false, "HDFS_M1"));
    serverExecute(1, verifyExtensionCount("MYHDFS", ".shop", true, "HDFS_M1"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop.tmp", false, "HDFS_M1"));
    serverExecute(2, verifyExtensionCount("MYHDFS", ".shop", true, "HDFS_M2"));

    clientSQLExecute(1, "drop table hdfs.m2");
    clientSQLExecute(1, "drop table hdfs.m1");
    clientSQLExecute(1, "drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  public void testBug48928() throws Exception {
    startVMs(1, 2);
    int netPort = startNetworkServer(2, null, null);

    Properties props = new Properties();
    props.put("skip-constraint-checks", "true");
    props.put("sync-commits", "true");
    Connection conn = TestUtil.getConnection(props);
    Connection conn2 = TestUtil.getConnection(props);
    runBug48928(conn, conn2);

    conn = TestUtil.getNetConnection(netPort, null, props);
    conn2 = TestUtil.getConnection(props);
    runBug48928(conn, conn2);

    conn = TestUtil.getNetConnection(netPort, null, props);
    conn2 = TestUtil.getNetConnection(netPort, null, props);
    runBug48928(conn, conn2);

    // also check with transactions
    conn = TestUtil.getConnection(props);
    conn2 = TestUtil.getNetConnection(netPort, null, props);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    runBug48928(conn, conn2);

    conn = TestUtil.getConnection(props);
    conn2 = TestUtil.getConnection(props);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn2.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    runBug48928(conn, conn2);

    conn = TestUtil.getNetConnection(netPort, null, props);
    conn2 = TestUtil.getNetConnection(netPort, null, props);
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    conn2.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    runBug48928(conn, conn2);
  }

  /**
   * Test for bug 51516
   */
  public void testPeerClientWithUniqueConstraint() throws Exception {

    // Start one client and two servers
    startVMs(0, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Properties props = new Properties();
    props.setProperty("host-data", "false");
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", getLocatorString());
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    checkDirExistence(homeDir);
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "'");
    st.execute("create table app.m1 (col1 int, col2 int, col3 int, primary key (col1, col2, col3), constraint cus_uq unique (col1, col2)) persistent hdfsstore (myhdfs) partition by (col1)");

    //Test violating the unique constraint
    st.execute("insert into app.m1 values (11, 22, 33)");
    try {
      st.execute("insert into app.m1 values (11, 22, 34)");
      fail("Should have seen a unique constraint violation");
    } catch (SQLException e) {
      //Make sure we saw a unique constraint violation.
      if (!e.getSQLState().equals("23505")) {
        throw e;
      }
    }

    //If the peer client has a PR but the datastores don't this will fail
    st.execute("call sys.rebalance_all_buckets()");

    st.execute("drop table app.m1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }

  private void runBug48928(final Connection conn, final Connection conn2)
      throws Exception {

    ResultSet rs;
    Statement st = conn.createStatement();
    st.execute("create table trade.securities (sec_id int primary key) "
        + "partition by primary key");
    st.execute("create table trade.customers (cid int primary key, "
        + "tid int, constraint cus_uq unique (tid)) "
        + "partition by primary key");
    st.execute("create table trade.buyorders (oid int primary key, "
        + "cid int, sid int, tid int, "
        + "constraint bo_cust_fk foreign key (cid) "
        + "references trade.customers (cid), "
        + "constraint bo_sec_fk foreign key (sid) "
        + "references trade.securities (sec_id), "
        + "constraint bo_cust_fk2 foreign key (tid) "
        + "references trade.customers (tid)) partition by primary key");
    st.execute("insert into trade.securities values (11)");

    st.execute("insert into trade.customers values (12, 15)");
    st.execute("insert into trade.customers values (12, 16)");
    st.execute("insert into trade.customers values (13, 15)");

    st.execute("insert into trade.buyorders values (1, 10, 14, 18)");
    st.execute("update trade.buyorders set cid = 24 where oid = 1");
    st.execute("update trade.buyorders set sid = 24 where cid = 24");
    st.execute("update trade.buyorders set tid = 28 where oid = 1");

    st.execute("insert into trade.securities values (11)");
    conn.commit();

    // verify results
    st = conn2.createStatement();
    rs = st.executeQuery("select * from trade.securities");
    assertTrue(rs.next());
    assertEquals(11, rs.getInt(1));
    assertFalse(rs.next());

    Object[][] expectedOutput = new Object[][]{new Object[]{12, 16},
        new Object[]{13, 15}};
    rs = st.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    st.execute("delete from trade.customers where tid=15");
    expectedOutput = new Object[][]{new Object[]{12, 16}};
    rs = st.executeQuery("select * from trade.customers where tid=16");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.customers where cid=12");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);

    expectedOutput = new Object[][]{new Object[]{1, 10, 14, 18}};
    st.execute("insert into trade.buyorders values (1, 10, 14, 18)");
    rs = st.executeQuery("select * from trade.buyorders");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where cid=10");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where sid=14");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where tid=18");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);

    st.execute("put into trade.buyorders values (1, 10, 14, 18)");
    conn2.commit();
    rs = st.executeQuery("select * from trade.buyorders");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where cid=10");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where sid=14");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);
    rs = st.executeQuery("select * from trade.buyorders where tid=18");
    JDBC.assertUnorderedResultSet(rs, expectedOutput, false);

    conn2.commit();

    st.execute("drop table trade.buyorders");
    st.execute("drop table trade.customers");
    st.execute("drop table trade.securities");

    conn2.commit();
  }

  @SuppressWarnings("SameParameterValue")
  private SerializableRunnable verifyExtensionCount(final String hdfsstore,
      final String extension, final boolean nonzerocount, final String tablepath) throws Exception {
    return new SerializableRunnable() {
      @Override
      public void run() {
        try {
          int extensionCount = getExtensionCount();
          if (nonzerocount) assertTrue(extensionCount > 0);
          else assertEquals(extensionCount, 0);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

      private int getExtensionCount() throws Exception {
        int counter = 0;
        HDFSStoreImpl hdfsStore = GemFireCacheImpl.getInstance().
            findHDFSStore(hdfsstore);
        FileSystem fs = hdfsStore.getFileSystem();
        try {
          Path basePath = new Path(hdfsStore.getHomeDir() + "/" + tablepath);

          RemoteIterator<LocatedFileStatus> files = fs.listFiles(basePath, true);

          while (files.hasNext()) {
            LocatedFileStatus next = files.next();
            if (next.getPath().getName().endsWith(extension))
              counter++;
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
        return counter;
      }
    };
  }

  private int getNumberOfMajorCompactedFiles(String path) {
    File dir = new File(path);
    if (!dir.exists()) {
      return 0;
    }
    List<String> expired = new ArrayList<>();
    getExpiredMarkers(dir, expired);
    List<String> majorCompacted = new ArrayList<>();
    getMajorCompactedFiles(dir, majorCompacted);
    majorCompacted.removeIf(f ->
        expired.contains(f + AbstractHoplogOrganizer.EXPIRED_HOPLOG_EXTENSION));
    return majorCompacted.size();
  }

  private void getExpiredMarkers(File file, List<String> expired) {
    if (file.isFile()) {
      if (!file.isHidden() && file.getName().endsWith(
          AbstractHoplogOrganizer.EXPIRED_HOPLOG_EXTENSION)) {
        expired.add(file.getName());
      }
      return;
    }
    File[] files = file.listFiles();
    if (files != null) {
      for (File f : files) {
        getExpiredMarkers(f, expired);
      }
    }
  }

  private void getMajorCompactedFiles(File file, List<String> majorCompactedFiles) {
    if (file.isFile()) {
      if (!file.isHidden() && file.getName().endsWith(
          AbstractHoplogOrganizer.MAJOR_HOPLOG_EXTENSION)) {
        majorCompactedFiles.add(file.getName());
      }
      return;
    }
    File[] files = file.listFiles();
    if (files != null) {
      for (File f : files) {
        getMajorCompactedFiles(f, majorCompactedFiles);
      }
    }
  }

  // Assume no other thread creates the directory at the same time
  private void checkDirExistence(String path) {
    File dir = new File(path);
    if (dir.exists()) {
      delete(dir);
    }
  }
}
