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
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.cache.partition.PartitionMemberInfo;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.partition.PartitionRegionInfo;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.DiskRegionStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionStats;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedOplogStatistics;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import io.snappydata.test.dunit.AvailablePortHelper;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSCluster.Builder;

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

private void delete(File file) {
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
private void checkDirExistence(String path) throws IOException {
  Path pathOnHdfs = new Path(path);
  if (fs.exists(pathOnHdfs)) {
    fs.delete(pathOnHdfs, true);
  }
  File dir = new File(path);
  if (dir.exists()) {
    delete(dir);
  }
}

// check HDFS iterator with ORDER BY clause
// First, check if all expected results are returned
// Second, check if results are ordered
private void checkHDFSIteratorResultSet(ResultSet rs, int expectedSize) throws Exception{
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
      {"create hdfsstore hs1 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs1'", null},
      {"create hdfsstore hs2 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs2' queuepersistent true diskstorename mydisk", null},
      {"create hdfsstore hs3 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs3' MinorCompact false MinorCompactionThreads 5", null},
      {"create hdfsstore hs4 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs4' batchsize 14 majorcompactionThreads 3", null},
      {"create hdfsstore hs5 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs5' blockcachesize 23 majorcompactionThreads 3", null},
      {"create hdfsstore hs6 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs6' clientconfigfile './file1' blockcachesize 78", null},
      {"create hdfsstore hs7 namenode 'hdfs://127.0.0.1:" + port + "' homedir './hs6' maxWriteOnlyfilesize 6 WriteOnlyfilerolloverinterval 7 seconds", null}};
  
  JDBC.SQLUnitTestHelper(st, CreateHDFSStoreDDL);
  
  Object[][] DropHDFSStoreDDL = {        
      {"drop hdfsstore hs1", null},
      {"drop hdfsstore hs2", null},
      {"drop hdfsstore hs3", null},
      {"drop hdfsstore hs4", null},
      {"drop hdfsstore hs5", null},
      {"drop hdfsstore hs6", null},
      {"drop hdfsstore hs7", null}};
  
  JDBC.SQLUnitTestHelper(st, DropHDFSStoreDDL);
  
  for (int i = 1; i <= 7; i++) {
    String fn = new String("./hs" + i);
    delete(new File(fn));
  }
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
  
  public void testHDFSEventQueuePersistence() throws Exception {
    
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
    }
    catch (SQLException stde) {
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
      }
      else {
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
      }
      else {
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
      }
      else {
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
      }
      else {
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
    while(rs.next()) {
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
    } catch(SQLException exected) {
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
      ps.setInt(2, i+1);
      ps.addBatch();
    }
    ps.executeBatch();    
    
    ps = conn.prepareStatement("update app.t1 set col2 = ? where col1 = ?");

    for (int i = 0; i < 2; i++) {
      ps.setInt(1, i+2);
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
    }
    catch (SQLException sqle) {
      if (sqle.getSQLState().compareTo("23505") != 0) {
        throw sqle;
      }
    }
    try {
      st.execute("put into t1 values (1, 11)");
      fail("Expect unique constraint violation");
    }
    catch (SQLException sqle) {
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
    }
    catch (SQLException sqle) {
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
      ps.setInt(2, i+1);
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
      ps.setInt(2, i+1);
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
          
          
          
          Object[][] expectedOutput1 = new Object[][] { new Object[] { 12, 56 }, new Object[] {78, 90} };
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

  
  public static MiniDFSCluster initMiniCluster(int port, int numDN) throws Exception {
    HashMap<String, String> map = new HashMap<String, String>();
    map.put(DFSConfigKeys.DFS_REPLICATION_KEY, "1");
    return initMiniCluster(port, numDN, map);
  }

  public static MiniDFSCluster initMiniCluster(int port, int numDN, HashMap<String, String> map) throws Exception {
    System.setProperty("test.build.data", "hdfs-test-cluster");
    Configuration hconf = new HdfsConfiguration();
    for (Entry<String, String> entry : map.entrySet()) {
      hconf.set(entry.getKey(), entry.getValue());
    }

    Builder builder = new MiniDFSCluster.Builder(hconf);
    builder.numDataNodes(numDN);
    builder.nameNodePort(port);
    MiniDFSCluster cluster = builder.build();
    return cluster;
  }

  public void deleteMiniClusterDir() throws Exception {
    File clusterDir = new File("hdfs-test-cluster");
    delete(clusterDir);
    System.clearProperty("test.build.data");
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
      ps.setInt(2, i+1);
      ps.addBatch();
    }
    ps.executeBatch();    
    
    ps = conn.prepareStatement("update app.t1 set col2 = ? where col1 = ?");

    for (int i = 0; i < 2; i++) {
      ps.setInt(1, i+2);
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
    for(PartitionRegionInfo info : infos) {
      if(info.getRegionPath().contains("/APP/T1")) {
        PartitionMemberInfo memberInfo = info.getPartitionMemberInfo().iterator().next();
        bytesFromInfo = memberInfo.getSize();
      }
    }
    
    PartitionedRegion region = (PartitionedRegion) cache.getRegion("/APP/T1");
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
  int index=0;
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

protected int  getExtensioncount(final String hdfsstore, final String extension) throws Exception {
  int counter =0 ;
  HDFSStoreImpl hdfsStore = (HDFSStoreImpl) GemFireCacheImpl.getInstance().findHDFSStore(hdfsstore);
  FileSystem fs = hdfsStore.getFileSystem();
  try {
    Path basePath = new Path(hdfsStore.getHomeDir());
    
    RemoteIterator<LocatedFileStatus> files = fs.listFiles(basePath, true);
        
    while(files.hasNext()) {
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
