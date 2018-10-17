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
package com.pivotal.gemfirexd.internal.hadoop;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer.DDLHoplog;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.AsyncEventListener;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;

/**
 * Class to test the persistence of DDLs on HDFS 
 * @author hemantb
 *
 */
public class DDLPersistenceHDFSTest extends JdbcTestBase {

  private String HDFS_DIR = "./myhdfs";

  public DDLPersistenceHDFSTest(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    FileUtils.deleteQuietly(new File(HDFS_DIR));
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    FileUtils.deleteQuietly(new File(HDFS_DIR));
    FileUtils.deleteQuietly(new File("./mynewhdfs"));
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testBackwardCompatibility() throws Exception {
    
    // 1. Create a gfxd server and add few ddls 
    // 2. Fetch the ddlconflatables and hack the code to rewrite a new ddl hoplog with version as GFXD_101.
    // 3. Start a loner which loads the new ddlhoplog. The ddl version should be set as that of 1302 (ordinal 28) and not any junk number. 
    
    Properties props = new Properties();
    System.setProperty(ResolverUtils.GFXD_USE_PRE1302_HASHCODE, "true");
    
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "'");
    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");

    ArrayList<DDLConflatable> ddlListTobePersisted = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    HDFSStoreImpl hdfsstore = GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS");
    
    ArrayList<byte[]> keyList = new ArrayList<byte[]>();
    ArrayList<byte[]> valueList = new ArrayList<byte[]>();
    try {
      for (DDLConflatable ddlstmt : ddlListTobePersisted) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
            "EmbedStatement: Persisting all statements on HDFS " + ddlstmt.getValueToConflate());
        // serialize the ddls with an older version. 
        byte[] valueBytes = BlobHelper.serializeToBlob(ddlstmt, Version.GFXD_101);
        byte[] keyBytes = BlobHelper.serializeToBlob(ddlstmt.getId(), Version.GFXD_101);
        keyList.add(keyBytes);
        valueList.add(valueBytes);
      }
    } catch(IOException e) {
      throw new InternalGemFireError("Could not serialize DDL statement", e);
    }
    
    // write to ddlhoplog with an older version. 
    hdfsstore.getDDLHoplogOrganizer().flush(keyList.iterator(),
        valueList.iterator(), Version.GFXD_101);
    
  TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    
    Configuration conf = new Configuration();
    Iterator<Entry<String, String>> confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    ArrayList<DDLConflatable> ddlList = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    
    for (DDLConflatable ddl : ddlList){
      assertEquals(ddl.getDDLVersion(), Version.GFXD_13.ordinal());
    }

    TestUtil.shutDown();

  }
  public void testDDLPersistenceOnHDFS() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema emp");
    st.execute("set schema emp");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "'");
    ArrayList<DDLConflatable> ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    assertTrue(ddlconflatables.size() == 2);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    
    st.execute("create table mytab1 (col1 int primary key)");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    
    assertTrue(ddlconflatables.size() == 2);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    
    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    
    assertTrue(ddlconflatables.size() == 3);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    
    st.execute("create table mytab2 (col1 int primary key) hdfsstore (myhdfs) writeonly");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB2"));
    
    st.execute("create hdfsstore mynewhdfs namenode 'localhost' homedir './mynewhdfs'");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB2"));
    
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYNEWHDFS"));
    assertTrue(ddlconflatables.size() == 2);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    
    st.execute("create table mytab3 (col1 int primary key) hdfsstore (mynewhdfs) writeonly");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYNEWHDFS"));
    assertTrue(ddlconflatables.size() == 3);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB2"));
    
    st.execute("drop table mytab");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB2"));
    
    st.execute("create table mytab4 (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB2"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB4"));
    
    st.execute("create alias x for 'y'");
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS"));
    assertTrue(ddlconflatables.size() == 5);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(2).getTableName().equals("MYTAB2"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getTableName().equals("MYTAB4"));
    assertTrue(ddlconflatables.get(4).getValueToConflate().startsWith("create alias"));
    
    ddlconflatables = getDDLConflatables(GemFireCacheImpl.getInstance().findHDFSStore("MYNEWHDFS"));
    assertTrue(ddlconflatables.size() == 4);
    assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
    assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
    assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
    assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create alias"));
    
    TestUtil.shutDown();
  }

  /**
   * Test the DDL replay by playing the ddls once then stopping and restarting the server
   * @throws Exception
   */
  public void testDDLReplayOnHDFS() throws Exception {
    
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    /****************PLAY THE DDLS START****************************************/
    st.execute("create schema emp");
    st.execute("set schema emp");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "'");
    st.execute("create table mytab1 (col1 int primary key)");
    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    st.execute("create table mytab2 (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    st.execute("create hdfsstore mynewhdfs namenode 'localhost' homedir './mynewhdfs'");
    st.execute("create table mytab3 (col1 int primary key) hdfsstore (mynewhdfs) writeonly");
    st.execute("drop table mytab");
    st.execute("create table mytab4 (col1 int primary key) hdfsstore (myhdfs) writeonly");
    st.execute("create alias x for 'y'");
    
    TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    Configuration conf = new Configuration();
    Iterator<Entry<String, String>> confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    
    st.execute("select * from sys.syshdfsstores");
    java.sql.ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    //hdfs store name
    assertEquals("MYHDFS", rs.getString(1));
    
    // the second store should not be found
    assertFalse(rs.next());
    
    // mytab2 should be found
    st.execute("select * from sys.systables where tablename='MYTAB2'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    // mytab2 should be found
    st.execute("select * from sys.systables where tablename='MYTAB4'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    // mytab and mytab3 should not be found 
    st.execute("select * from sys.systables where tablename='MYTAB'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from sys.systables where tablename='MYTAB3'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    TestUtil.shutDown();
    /****************REPLAY THE DDLS FOR MYHDFS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYNEWHDFS - START ****************************************/
    
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./mynewhdfs");
    conf = new Configuration();
    confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    
    st.execute("select * from sys.syshdfsstores");
    rs = st.getResultSet();
    assertTrue(rs.next());
    //hdfs store name
    assertEquals("MYNEWHDFS", rs.getString(1));
    
    // the second store should not be found
    assertFalse(rs.next());
    
    // mytab3 should be found 
    st.execute("select * from sys.systables where tablename='MYTAB3'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("MYTAB3", rs.getString(2));
    
    //mytab and mytab2 should not be found 
    st.execute("select * from sys.systables where tablename='MYTAB'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from sys.systables where tablename='MYTAB2'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from sys.systables where tablename='MYTAB4'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    /****************REPLAY THE DDLS FOR MYNEWHDFS - END ****************************************/
  }
  /**
   * Test the DDL replay by playing the ddls once then stopping and restarting the server
   * @throws Exception
   */
  public void testDDLReplayOfTwoHDFSStores() throws Exception {
    
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    /****************PLAY THE DDLS START****************************************/
    st.execute("create schema emp");
    st.execute("set schema emp");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "'");
    st.execute("create table mytab1 (col1 int primary key)");
    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    st.execute("create table mytab2 (col1 int primary key) persistent hdfsstore (myhdfs) writeonly");
    st.execute("create hdfsstore mynewhdfs namenode 'localhost' homedir './mynewhdfs'");
    st.execute("create table mytab3 (col1 int primary key) hdfsstore (mynewhdfs) writeonly");
    st.execute("drop table mytab");
    st.execute("create table mytab4 (col1 int primary key) hdfsstore (myhdfs) writeonly");
    st.execute("create alias x for 'y'");
    
    TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR Both the HDFSStores - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "localhost");
    
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs,./mynewhdfs");
    Configuration conf = new Configuration();
    Iterator<Entry<String, String>> confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    
    st.execute("select * from sys.syshdfsstores where name='MYHDFS'");
    java.sql.ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    
    st.execute("select * from sys.syshdfsstores where name='MYNEWHDFS'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    st.execute("select * from sys.systables where tablename='MYTAB1'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from sys.systables where tablename='MYTAB'");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    // mytab2 should be found
    st.execute("select * from sys.systables where tablename='MYTAB2'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    // mytab2 should be found
    st.execute("select * from sys.systables where tablename='MYTAB4'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    
    // mytab3 should be found 
    st.execute("select * from sys.systables where tablename='MYTAB3'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("MYTAB3", rs.getString(2));
    
    
    /****************REPLAY THE DDLS FOR MYNEWHDFS - END ****************************************/
  }
  /**
   * Test the DDL replay by playing the ddls once then stopping and restarting the server
   * @throws Exception
   */
  public void testDDLReplayColocatedRegions() throws Exception {
    
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    /****************PLAY THE DDLS START****************************************/
    st.execute("create schema emp");
    st.execute("set schema emp");
    st.execute("create diskstore hdfsStoreDisk 'hdfsStoreData' autocompact false");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' DISKSTORENAME hdfsStoreDisk");
    st.execute("create table mytab (col1 int primary key) persistent hdfsstore (myhdfs)");
    //The foreign key constraint falls afoul of our changes to enable eviction.
//    st.execute("create table mytab_colocated (col2 int primary key, col1 int CONSTRAINT col1_fk REFERENCES mytab (col1) ) persistent hdfsstore (myhdfs) partition by column (col1) colocate with (mytab)");
    st.execute("create table mytab_colocated (col2 int primary key, col1 int) persistent hdfsstore (myhdfs) partition by column (col1) colocate with (mytab)");
    
    TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    Configuration conf = new Configuration();
    Iterator<Entry<String, String>> confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    
    st.execute("select * from sys.syshdfsstores");
    java.sql.ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    //hdfs store name
    assertEquals("MYHDFS", rs.getString(1));
    
    // mytab should be found
    st.execute("select * from sys.systables where tablename='MYTAB'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    // mytab_colocated should be found
    st.execute("select * from sys.systables where tablename='MYTAB_COLOCATED'");
    rs = st.getResultSet();
    assertTrue(rs.next());
  }
  
  /**
   * Test the DDL replay by playing the ddls once then stopping and restarting the server
   * @throws Exception
   */
  public void testDDLReplayHDFSRegionColocatedWithNonHDFS() throws Exception {
    
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    /****************PLAY THE DDLS START****************************************/
    st.execute("create schema emp");
    st.execute("set schema emp");
    st.execute("create diskstore hdfsStoreDisk 'hdfsStoreData' autocompact false");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' DISKSTORENAME hdfsStoreDisk");
    st.execute("create table mytab (col1 int primary key) hdfsstore (myhdfs) persistent");
    st.execute("create table mytab_colocated (col2 int primary key, col1 int) persistent partition by column (col1) colocate with (mytab)");
//    st.execute("create table mytab (col1 int primary key) persistent");
    //The foreign key constraint falls afoul of our changes to enable eviction.
//    st.execute("create table mytab_colocated (col2 int primary key, col1 int CONSTRAINT col1_fk REFERENCES mytab (col1) ) persistent hdfsstore (myhdfs) partition by column (col1) colocate with (mytab)");
//    st.execute("create table mytab_colocated (col2 int primary key, col1 int) persistent hdfsstore (myhdfs) partition by column (col1) colocate with (mytab)");
    
    st.execute("insert into mytab (col1) values (1)");
    st.execute("insert into mytab_colocated (col1,col2) values (1,1)");
    
    TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    Configuration conf = new Configuration();
    Iterator<Entry<String, String>> confentries = conf.iterator();
    
    while (confentries.hasNext()){
      Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }
    
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    
    st.execute("select * from sys.syshdfsstores");
    java.sql.ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    //hdfs store name
    assertEquals("MYHDFS", rs.getString(1));
    
    // mytab should be found
    st.execute("select * from sys.systables where tablename='MYTAB'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    
    // mytab_colocated should not be found
    st.execute("select * from sys.systables where tablename='MYTAB_COLOCATED'");
    rs = st.getResultSet();
    assertFalse(rs.next());
  }

  /**
   * Executes a set of properties using create table commands. Then run it in 
   * gfxd loner mode and ensure that after replay none of the constraints 
   * are being replayed
   * 
   */
  public void testCreateTableConstraintsDuringReplay() throws Exception {
      
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("server-groups", "SG1");
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    /****************PLAY THE DDLS START****************************************/
    st.execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.internal.hadoop.DDLPersistenceHDFSTest$TestAsyncListener' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");
    
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs'");
    
    st.execute("create table Child ( id2 int primary key, sector_id2 int unique, " +
        "subsector_id2 int  ) persistent hdfsstore (myhdfs) buckets 11 eviction by criteria (id2 > 1000) " +
        "evict incoming ASYNCEVENTLISTENER(myListener)");
    
    boolean exceptionthrown = false;
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (1, 1, 1)");
    // since we have value 
    try {
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (1, 2, 2)");
    } catch (Exception e){
      exceptionthrown = true;
    }
    assertTrue(exceptionthrown);
    exceptionthrown = false;
    try {
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (2, 1, 2)");
    } catch (Exception e){
      exceptionthrown = true;
    }
    assertTrue(exceptionthrown);
    
    LocalRegion r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/CHILD", false);
    assertTrue(r.getCustomEvictionAttributes().isEvictIncoming());
    assertEquals(r.getHDFSStoreName(), "MYHDFS");
    assertTrue((r.getDiskStoreName() != null && !r.getDiskStoreName().equals("")));
    assertEquals(((PartitionedRegion)r).getTotalNumberOfBuckets(), 11);
    Set<String> ids =  r.getAsyncEventQueueIds();
    assertTrue(ids.contains("MYLISTENER"));
    
    TestUtil.shutDown();
    
    /****************PLAY THE DDLS - END ****************************************/
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    conn = TestUtil.getConnection(props);
    
    // The inserts following this are only to verify that the primary key constraints are enforced.
    // In actual scenarios we don't expect inserts.
    // We make them non-transactional so it doesn't throw errors due to eviction action not being set.
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    
    st = conn.createStatement();
    
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (1, 1, 1)");
    try {
      //Primary key constraints are replayed, so we expect them to be enforced.
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (1, 2, 2)");
    } catch (SQLException expected) {
      if(!expected.getSQLState().equals("23505")) {
        throw expected;
      }
    }
    //Other constraints are not replayed.
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (2, 1, 2)");
    
    r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/CHILD", false);
    assertEquals(r.getHDFSStoreName(), "MYHDFS");
    assertEquals(r.getEvictionAttributes().getAlgorithm(), EvictionAlgorithm.LRU_HEAP);
    assertTrue((r.getDiskStoreName() == null || r.getDiskStoreName().equals("")));
    assertEquals(((PartitionedRegion)r).getTotalNumberOfBuckets(), 11);
    ids =  r.getAsyncEventQueueIds();
    assertFalse(ids.contains("MYLISTENER"));
    
    TestUtil.shutDown();
      
  }
  
  /**
   * Executes a set of properties using alter commands. Then run it in gfxd loner mode
   * and ensure that after replay none of the constraints are being replayed
   * 
   */
  public void testAlterTableConstraintsDuringReplay() throws Exception {
    TestUtil.shutDown();
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    props.put("server-groups", "SG1");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    /****************PLAY THE DDLS START****************************************/
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs'");
    st.execute("create table sector (sector_id int primary key, sector_info int)");
    st.execute("create table Child ( id2 int not null, sector_id2 int ) persistent hdfsstore (myhdfs) buckets 11 ");
    st.execute("alter table Child add constraint child_pk primary key (id2)");
    st.execute("alter table Child add constraint child_uk unique (sector_id2)");
    st.execute("alter table Child add column subsector_id2 int");
    st.execute("ALTER TABLE Child "
        + "ADD CONSTRAINT FK_sector_id FOREIGN KEY (sector_id2) "
        + "REFERENCES sector (sector_id) ON DELETE RESTRICT");
    st.execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.internal.hadoop.DDLPersistenceHDFSTest$TestAsyncListener' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");
    
    st.execute(
        "ALTER TABLE Child SET ASYNCEVENTLISTENER (myListener) ");
    boolean exceptionthrown = false;
    
    st.execute("insert into sector (sector_id, sector_info)"
        + " values (1, 1)");
    
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (1, 1, 1)");
    // primary key violation. exception should be thrown 
    try {
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (1, 2, 2)");
    } catch (Exception e){
      exceptionthrown = true;
    }
    assertTrue(exceptionthrown);
    exceptionthrown = false;
    // unique key violation. exception should be thrown 
    try {
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (2, 1, 2)");
    } catch (Exception e){
      exceptionthrown = true;
    }
    assertTrue(exceptionthrown);
    exceptionthrown = false;
    // foreign key violation. exception should be thrown 
    try {
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
          + " values (2, 3, 2)");
    } catch (Exception e){
      exceptionthrown = true;
    }
    assertTrue(exceptionthrown);
    
    LocalRegion r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/CHILD", false);
    assertEquals(r.getHDFSStoreName(), "MYHDFS");
    assertTrue((r.getDiskStoreName() != null && !r.getDiskStoreName().equals("")));
    assertEquals(((PartitionedRegion)r).getTotalNumberOfBuckets(), 11);
    Set<String> ids =  r.getAsyncEventQueueIds();
    assertTrue(ids.contains("MYLISTENER"));
    
    TestUtil.shutDown();
    
    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();
    
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    conn = TestUtil.getConnection(props);
    
    // The inserts following this are only to verify that the primary key constraints are enforced.
    // In actual scenarios we don't expect inserts.
    // We make them non-transactional so it doesn't throw errors due to eviction action not being set.
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);

    
    st = conn.createStatement();
    
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (1, 1, 1)");
    
    // unique and foreign violations should not throw exceptions as we have 
    // not replayed the constraints
    try {
      //primary key violation will throw exceptions because we replayed those constraints 
      st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (1, 2, 2)");
    } catch (SQLException expected) {
      if(!expected.getSQLState().equals("23505")) {
        throw expected;
      }
    }
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (2, 1, 2)");
    st.execute("insert into Child (id2, sector_id2, subsector_id2)"
        + " values (3, 3, 2)");
    
    r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/CHILD", false);
    assertEquals(r.getHDFSStoreName(), "MYHDFS");
    assertEquals(r.getEvictionAttributes().getAlgorithm(), EvictionAlgorithm.LRU_HEAP);
    assertTrue((r.getDiskStoreName() == null || r.getDiskStoreName().equals("")));
    assertEquals(((PartitionedRegion)r).getTotalNumberOfBuckets(), 11);
    
    ids =  r.getAsyncEventQueueIds();
    assertFalse(ids.contains("MYLISTENER"));
      
  }

  private  ArrayList<DDLConflatable> getDDLConflatables(HDFSStoreImpl store) throws IOException,
      ClassNotFoundException {
    DDLHoplogOrganizer organizer = store.getDDLHoplogOrganizer();
    DDLHoplog ddlhoplog = organizer.getDDLStatementsForReplay();
    ArrayList<byte[]> ddls = ddlhoplog.getDDLStatements();
    ArrayList<DDLConflatable> ddlconflatables = new ArrayList<DDLConflatable>(); 
    for (byte[] ddl : ddls) {
      ddlconflatables.add((DDLConflatable)BlobHelper.deserializeBlob(ddl, ddlhoplog.getDDLVersion(), null));
    }
    return ddlconflatables;
  }

  public static class TestAsyncListener implements AsyncEventListener {
    public TestAsyncListener() {
    }
    public boolean processEvents(List<Event> events) {
      return true;
    }
    public void close() {
    }
    @Override
    public void init(String initParamStr) {
    }
    @Override
    public void start() {
    }
  }
}
