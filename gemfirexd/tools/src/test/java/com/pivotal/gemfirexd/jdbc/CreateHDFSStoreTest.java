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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedOplogStatistics;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import io.snappydata.test.dunit.AvailablePortHelper;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;

/**
*
* @author jianxiachen
*
*/

public class CreateHDFSStoreTest extends JdbcTestBase {

int port;
MiniDFSCluster cluster;
FileSystem fs = null;

public static void main(String[] args)
{
  TestRunner.run(new TestSuite(SimpleAppTest.class));
}

public CreateHDFSStoreTest(String name) {
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

static void delete(File file) {
  if (!file.exists()) {
    return;
  }
  if (file.isDirectory()) {
    if (file.list().length == 0) {
      file.delete();
    }
    else {
      File[] files = file.listFiles();
      for (File f : files) {
        delete(f);
      }        
      file.delete();        
    }
  }
  else {
    file.delete();
  }
}

// Assume no other thread creates the directory at the same time
static void checkDirExistence(String path, FileSystem fs) throws IOException {
  Path pathOnHdfs = new Path(path);
  if (fs.exists(pathOnHdfs)) {
    fs.delete(pathOnHdfs, true);
  }
  File dir = new File(path);
  if (dir.exists()) {
    delete(dir);
  }
}

private void checkDirExistence(String path) throws IOException {
  checkDirExistence(path, fs);
}

static void deleteMiniClusterDir() throws Exception {
  File clusterDir = new File("hdfs-test-cluster");
  delete(clusterDir);
  System.clearProperty("test.build.data");
}

static MiniDFSCluster initMiniCluster(int port, int numDN) throws Exception {
  HashMap<String, String> map = new HashMap<String, String>();
  map.put(DFSConfigKeys.DFS_REPLICATION_KEY, "1");
  return initMiniCluster(port, numDN, map);
}

static MiniDFSCluster initMiniCluster(int port, int numDN, HashMap<String, String> map) throws Exception {
  System.setProperty("test.build.data", "hdfs-test-cluster");
  Configuration hconf = new HdfsConfiguration();
  for (Map.Entry<String, String> entry : map.entrySet()) {
    hconf.set(entry.getKey(), entry.getValue());
  }

  MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(hconf);
  builder.numDataNodes(numDN);
  builder.nameNodePort(port);
  MiniDFSCluster cluster = builder.build();
  return cluster;
}

// check HDFS iterator with ORDER BY clause
// First, check if all expected results are returned
// Second, check if results are ordered
static void checkHDFSIteratorResultSet(ResultSet rs, int expectedSize) throws Exception{
  Vector<Object> v = new Vector<Object>();
  while (rs.next()) {
    v.add(rs.getObject(1));
  }
  Object[] arr = v.toArray();
  Arrays.sort(arr);
  assertEquals(expectedSize, arr.length);
  for (int i = 0; i < expectedSize; i++) {
    assertEquals(i, ((Integer) arr[i]).intValue());
  }
}

public void testQueryHDFS() throws Exception {
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
  
  ps = conn.prepareStatement("select * from t1 where col1 = ?");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == false by default, not allow pk-based queries to HDFS
//    assertTrue(rs.next());
//    assertEquals(i + 1, rs.getInt(2));
    assertFalse(rs.next());
  }
      
  //now test with query hint queryHDFS = false
  ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n where col1 = ?");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == false, not allow pk-based queries to HDFS
    assertFalse(rs.next());
  }
  
  //now query again without the query hint, it should query HDFS
  ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = ?");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == false by default, allow pk-based queries to HDFS
    assertTrue(rs.next());
    assertEquals(i + 1, rs.getInt(2));
    assertFalse(rs.next());
  }  
  
  //now test with query hint with queryHDFS = false again
  ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n where col1 = ? ");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == false, not allow pk-based queries to HDFS
    assertFalse(rs.next());
  }
  
  st = conn.createStatement();
  st.execute("drop table t1");
  st.execute("drop hdfsstore myhdfs");
  delete(new File("./myhdfs"));
}

public void testQueryHDFS2() throws Exception {
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
      
  //now test with query hint to enable HDFS iterator
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
  rs = st.getResultSet();
  checkHDFSIteratorResultSet(rs, NUM_ROWS);
  
  //now query again without the query hint, it should return nothing
  st.execute("select * from t1");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  //now test with query hint to enable HDFS iterator again
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
  rs = st.getResultSet();
  checkHDFSIteratorResultSet(rs, NUM_ROWS);
  
  st.execute("drop table t1");
  st.execute("drop hdfsstore myhdfs");
  delete(new File("./myhdfs"));
}

public void testConnectionProperties() throws Exception {   
  Connection conn = TestUtil.getConnection();
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs");
  st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
  st.execute("create table t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
  
  //populate the table
  PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
  int NUM_ROWS = 100;
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    ps.setInt(2, i + 1);
    ps.executeUpdate();
  }

  //make sure data is written to HDFS
  String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
  st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
  //Thread.sleep(10000);

  shutDown();
  
  //restart to clean the memory
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort)); 
  props.put("query-HDFS", "true"); 
  conn = TestUtil.getConnection(props);
  st = conn.createStatement();
  
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n");
  rs = st.getResultSet();
  //nothing in memory
  assertFalse(rs.next());
  
  // now since query-HDFS = true by connection properties
  // it should query HDFS without using query hint
  st.execute("select * from t1 order by col1");
  rs = st.getResultSet();
  checkHDFSIteratorResultSet(rs, NUM_ROWS);
    
  //query hint should override the connection property
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n");
  rs = st.getResultSet();
  //nothing in memory
  assertFalse(rs.next());
  
  //query hint should override the connection property
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=false \n");
  rs = st.getResultSet();
  //nothing in memory
  assertFalse(rs.next());
  
  shutDown();
  
  //restart to clean the memory
  props = new Properties();
  mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort)); 
  props.put("query-HDFS", "false");
  conn = TestUtil.getConnection(props);
  st = conn.createStatement();
  
  st.execute("select * from t1");
  rs = st.getResultSet();
  //nothing in memory
  assertFalse(rs.next());
  
  ps = conn.prepareStatement("select * from t1 where col1 = ? ");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == false, not allow pk-based queries to HDFS
    assertFalse(rs.next());
  }
  
  ps = conn.prepareStatement("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = ?");
  for (int i = 0; i < NUM_ROWS; i++) {
    ps.setInt(1, i);
    rs = ps.executeQuery();    
    //queryHDFS == true, allow pk-based queries to HDFS
    assertTrue(rs.next());
    assertEquals(i + 1, rs.getInt(2));
    assertFalse(rs.next());
  } 
  
  st.execute("select * from t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
  rs = st.getResultSet();
  checkHDFSIteratorResultSet(rs, NUM_ROWS);
  
  st.execute("drop table t1");
  st.execute("drop hdfsstore myhdfs");
  delete(new File("./myhdfs"));
}

public void testDDLConflation() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();    

  checkDirExistence("./myhdfs");
  st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");    
  st.execute("drop hdfsstore myhdfs");
  st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
  shutDown();    
  //DDL replay
  conn = TestUtil.getConnection(props);
  st = conn.createStatement();          
  st.execute("drop hdfsstore myhdfs");
  delete(new File("./myhdfs"));
}
  
public void testSYSHDFSSTORE_AllAttributes() throws Exception {
  
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs");
  // create hdfs store with all attributes
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' HomeDir './myhdfs'" +
      " QueuePersistent true DiskSynchronous false MaxQueueMemory 123 BatchSize 7 BatchTimeInterval 5678 seconds DiskStoreName mydisk " +
      " MinorCompact false MajorCompact false MaxInputFileSize 678 MinInputFileCount 9 " +
      "MaxInputFileCount 20 MinorCompactionThreads 20 MajorCompactionInterval 360 minutes MajorCompactionThreads 14 MaxWriteOnlyFileSize 6 " + 
      "writeonlyfilerolloverinterval 7seconds BlockCacheSize 5 ClientConfigFile './file1' PurgeInterval 360 minutes DispatcherThreads 10");
  
  st.execute("select * from sys.syshdfsstores");
  
  rs = st.getResultSet();
  
  assertTrue(rs.next());    
  
  //hdfs store name
  assertEquals("MYHDFS", rs.getString(1));
  
  //name node url
  assertEquals("hdfs://127.0.0.1:" + port + "", rs.getString(2));
  
  //home dir
  assertEquals(GfxdConstants.SYS_HDFS_ROOT_DIR_DEF + "/./myhdfs", rs.getString(3));
  
  //max queue memory 
  assertEquals(123, rs.getInt(4));
  
  //batch size 
  assertEquals(7, rs.getInt(5));
  
  //batch interval milliseconds
  assertEquals(5678000, rs.getInt(6));
  
  //is persistence enabled 
  assertTrue(rs.getBoolean(7));
  
  //is disk synchronous 
  assertFalse(rs.getBoolean(8));
  
  //disk store name 
  assertEquals("MYDISK", rs.getString(9));
  
  //is auto compact 
  assertFalse(rs.getBoolean(10));
  
  //is auto major compact  
  assertFalse(rs.getBoolean(11));
  
  //max input file size 
  assertEquals(678, rs.getInt(12));
  
  //min input file count 
  assertEquals(9, rs.getInt(13));
  
  //max input file count
  assertEquals(20, rs.getInt(14));
  
  //max concurrency 
  assertEquals(20, rs.getInt(15));
  
  //major compaction interval minutes
  assertEquals(360, rs.getInt(16));
  
  //major compaction concurrency 
  assertEquals(14, rs.getInt(17));

  //HDFS client config file
  assertEquals("./file1", rs.getString(18));
  
  //block cache size
  assertEquals(5, rs.getInt(19));
     
  // max file size write only 
  assertEquals(6, rs.getInt(20));
  
  // time for rollover
  assertEquals(7, rs.getInt(21));
  
  //purge interval
  assertEquals(360, rs.getInt(22));
  
  //dispatcherthreads
  assertEquals(10, rs.getInt(23));
  
  assertFalse(rs.next());
  
  st.execute("select purgeintervalmins from sys.syshdfsstores");
  
  rs = st.getResultSet();
  
  assertTrue(rs.next());
  
  assertEquals(360, rs.getInt(1));
  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
      
  delete(new File("./myhdfs"));
}

public void testSYSHDFSSTORE_TimeUnitConversion() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs");
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' PurgeInterval 360 minutes");  
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(360, rs.getInt("PURGEINTERVALMINS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' PurgeInterval 360 seconds");  
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(6, rs.getInt("PURGEINTERVALMINS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' PurgeInterval 120000 milliseconds");  
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(2, rs.getInt("PURGEINTERVALMINS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' PurgeInterval 3 hours");  
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(180, rs.getInt("PURGEINTERVALMINS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' MAJORCOMPACTIONINTERVAL 1000 days");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals((1000 * 24 * 60), rs.getInt("MAJORCOMPACTIONINTERVALMINS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' writeonlyfilerolloverinterval 10 days");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals((10 * 24 * 60 * 60), rs.getInt("writeonlyfilerolloverintervalsecs"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' writeonlyfilerolloverinterval 3600 milliseconds");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(3, rs.getInt("writeonlyfilerolloverintervalsecs"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' writeonlyfilerolloverinterval 3600 seconds");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(3600, rs.getInt("writeonlyfilerolloverintervalsecs"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' BATCHTIMEINTERVAL 1 hours");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals((60 * 60 * 1000), rs.getInt("BATCHTIMEINTERVALMILLIS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  // create hdfs store
  st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' BATCHTIMEINTERVAL 1 milliseconds");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());    
  //purge interval
  assertEquals(1, rs.getInt("BATCHTIMEINTERVALMILLIS"));
  assertFalse(rs.next());  
  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  delete(new File("./myhdfs"));
}

public void testSYSHDFSSTORE_InvalidTimeInterval() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs");
  
  try {
    st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' PurgeInterval -2 minutes");
    fail();
  } catch (SQLException e) {
    // expected
  }
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  try {
    st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' Purgeinterval 59 seconds");  
    fail();
  } catch (SQLException e) {
    // expected
  }
  conn = TestUtil.getConnection(props);
  st = conn.createStatement();
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
    
  try {
    st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' Purgeinterval 999 milliseconds");  
    fail();
  } catch (SQLException e) {
    // expected
  }
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  try {
    st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' BATCHTIMEINTERVAL -2 milliseconds");  
    fail();
  } catch (SQLException e) {
    // expected
  }
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  try {
    st.execute("create HDFSStore myhdfs NameNode 'hdfs://127.0.0.1:" + port + "' writeonlyfilerolloverinterval 999 milliseconds");  
    fail();
  } catch (SQLException e) {
    // expected
  }
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  delete(new File("./myhdfs"));
}

public void testSYSHDFSSTORE_DefaultAttributes() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./gemfire");
  //create hdfs store with all default attributes
  st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "'");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  
  assertTrue(rs.next());
  
  //hdfs store name
  assertEquals("MYHDFS", rs.getString(1));
  
  //name node url
  assertEquals("hdfs://127.0.0.1:" + port + "", rs.getString(2));
  
  //home dir
  assertEquals(GfxdConstants.SYS_HDFS_ROOT_DIR_DEF + "/MYHDFS", rs.getString(3));
  
  //max queue memory 100mb
  assertEquals(100, rs.getInt(4));
  
  //batch size 5    
  assertEquals(32, rs.getInt(5));
  
  //batch interval 5000 milliseconds
  assertEquals(60000, rs.getInt(6));
  
  //is persistence enabled false
  assertFalse(rs.getBoolean(7));
  
  //is disk synchronous true
  assertTrue(rs.getBoolean(8));
  
  //disk store name null
  assertNull(rs.getString(9));
  
  //is auto compact true
  assertTrue(rs.getBoolean(10));
  
  //is auto major compact true 
  assertTrue(rs.getBoolean(11));
  
  //max input file size 512mb
  assertEquals(512, rs.getInt(12));
  
  //min input file count 3
  assertEquals(4, rs.getInt(13));
  
  //max file input count for compaction 10
  assertEquals(10, rs.getInt(14));
  
  //max concurrency 10
  assertEquals(10, rs.getInt(15));
  
  //major compaction interval 720 minutes
  assertEquals(720, rs.getInt(16));
  
  //major compaction concurrency 2
  assertEquals(2, rs.getInt(17));

  //HDFS client config file
  assertNull(rs.getString(18));
  
  //block cache size
  assertEquals(10, rs.getInt(19));
  
  // max file size write only 
  assertEquals(256, rs.getLong(20));
  
  // time for rollover
  assertEquals(3600, rs.getLong(21));
  
  // purge interval
  assertEquals(30, rs.getLong(22));
  
  // Dispatcher threads
  assertEquals(5, rs.getLong(23));
      
  assertFalse(rs.next());

  //drop hdfs store
  st.execute("drop hdfsstore myhdfs");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
     
  delete(new File("./myhdfs"));
}

public void testSYSHDFSSTORE_AddAndDelete() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs1");
  checkDirExistence("./myhdfs2");
  //create one hdfs store
  st.execute("create hdfsstore myhdfs1 namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs1'" +
      " queuepersistent true disksynchronous false maxqueuememory 123 batchsize 7 batchtimeinterval 5678 milliseconds diskstorename mydisk " +
      " minorcompact false majorcompact false maxinputfilesize 678 mininputfilecount 9 " +
      "maxinputfilecount 20 MinorCompactionThreads 20 majorcompactioninterval 360 minutes majorcompactionThreads 14");    
  st.execute("select * from sys.syshdfsstores");    
  rs = st.getResultSet();    
  assertTrue(rs.next());    
  assertEquals("MYHDFS1", rs.getString(1));    
  assertFalse(rs.next());
  
  //create another hdfs store
  st.execute("create hdfsstore myhdfs2 namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs2'");
  st.execute("select count(*) from sys.syshdfsstores");
  rs = st.getResultSet();    
  assertTrue(rs.next());
  assertEquals(2, rs.getInt(1));
  assertFalse(rs.next());
  
  st.execute("select * from sys.syshdfsstores where name = 'MYHDFS1'");
  rs = st.getResultSet();    
  assertTrue(rs.next());
  assertEquals("MYHDFS1", rs.getString(1));
  assertFalse(rs.next());
  
  st.execute("select * from sys.syshdfsstores where name = 'MYHDFS2'");
  rs = st.getResultSet();    
  assertTrue(rs.next());
  assertEquals("MYHDFS2", rs.getString(1));
  assertFalse(rs.next());
  
  //drop one hdfs store
  st.execute("drop hdfsstore myhdfs2");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertTrue(rs.next());
  assertEquals("MYHDFS1", rs.getString(1));
  assertFalse(rs.next());
  
  //drop another hdfs store
  st.execute("drop hdfsstore myhdfs1");
  st.execute("select * from sys.syshdfsstores");
  rs = st.getResultSet();
  assertFalse(rs.next());
  
  delete(new File("./myhdfs1"));
  delete(new File("./myhdfs2"));
}

//partitioned table test
public void testPartitionHDFSStore() throws Exception {
  Properties props = new Properties();
  int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
  props.put("mcast-port", String.valueOf(mcastPort));    
  props.put(DistributionConfig.MCAST_TTL_NAME, "0");
  Connection conn = TestUtil.getConnection(props);
  Statement st = conn.createStatement();
  ResultSet rs = null;
  
  checkDirExistence("./myhdfs");
  st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs' BatchTimeInterval 100 milliseconds");
  st.execute("create table mytab (col1 int primary key) hdfsstore (myhdfs) enable concurrency checks");
//    try {
//      st.execute("drop hdfsstore myhdfs");
//    }
//    catch (SQLException stde) {
//      if (!stde.getSQLState().equals("X0Y25")) {
//        throw stde;        
//      }
//    }
    
    PreparedStatement ps = conn.prepareStatement("insert into mytab values (?)");
    final int NUM_ROWS = 1;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.executeUpdate();
    }    
    
    st.execute("select * from mytab order by col1");
    rs = st.getResultSet();
    int i = 0;
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      i++;
    }
    assertEquals(NUM_ROWS, i);
    
    //Wait for the data to be written to HDFS.
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    //Thread.sleep(15000);

    shutDown();
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    int count = 0;
    for (i = 0; i < NUM_ROWS; i++) {
      st.execute("select * from mytab where col1 = " + i);
      rs = st.getResultSet();

      while (rs.next()) {
        assertEquals(i, rs.getInt(1));
        count++;
      }
//      assertEquals(i + 1, count);
      //queryHDFS = false by default
      assertEquals(0, count);
    }
    
    st.execute("drop table mytab");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }
  
//Replicated table test
  public void testReplicatedHDFSStore() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put(DistributionConfig.MCAST_TTL_NAME, "0");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    try {
      st.execute("create table mytab (col1 int primary key) replicate hdfsstore (myhdfs) enable concurrency checks");
      fail("Expected SQLException");
    } catch (SQLException se) {
      if (se.getCause() instanceof UnsupportedOperationException) {
        assertTrue(se
            .getMessage()
            .contains(
                LocalizedStrings.HDFSSTORE_IS_USED_IN_REPLICATED_TABLE
                    .toLocalizedString()));
      }
      se.printStackTrace();
    }
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
  }

  public void testNoHDFSReadIfNoEvictionCriteria() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    
    checkDirExistence("./myhdfs");
    st.execute("create hdfsstore myhdfs namenode 'hdfs://127.0.0.1:" + port + "' homedir './myhdfs'");
    st.execute("create schema hdfs");
    doNoHDFSReadIfNoEvicttionCriteriaWork(conn, st, false);
    st.execute("drop table hdfs.t1");
    doNoHDFSReadIfNoEvicttionCriteriaWork(conn, st, true);

    st.execute("drop table hdfs.t1");
    st.execute("drop hdfsstore myhdfs");
  }

  private void doNoHDFSReadIfNoEvicttionCriteriaWork(Connection conn, Statement st, boolean isPersistent) throws Exception {
    String persistStr = isPersistent ? " persistent " : "";
    st.execute("create table hdfs.t1 (col1 int primary key, col2 int) partition by primary key buckets 5" +
        		persistStr+" hdfsstore (myhdfs)");
    PreparedStatement ps = conn.prepareStatement("insert into hdfs.t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }
    SortedOplogStatistics stats = HDFSRegionDirector.getInstance().getHdfsRegionStats("/HDFS/T1");
    assertEquals(isPersistent ? 0 : 100, stats.getRead().getCount());
  }
}
