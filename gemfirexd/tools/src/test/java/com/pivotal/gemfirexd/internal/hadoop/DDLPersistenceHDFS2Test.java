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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
import java.net.ConnectException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.*;

import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.PersistedEventImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.GFInputFormat;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.HDFSSplitIterator;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.store.entry.HDFSEventRowLocationRegionEntry;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TableScanResultSet;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import io.snappydata.test.dunit.AvailablePortHelper;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;

public class DDLPersistenceHDFS2Test extends JdbcTestBase {

  private String HDFS_DIR = "./myhdfs";

  final String expectedExceptions = InterruptedException.class.getName() + "||" + IOException.class.getName();

  public DDLPersistenceHDFS2Test(String name) {
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

  /**
   * Test that we can use the replayed DDL and loaner instance
   * to read part of a hoplog for a write only table. This
   * test doesn't include a blob column, so the storage
   * format for gemfirexd is different. Make sure we can still deserialize
   * the entry.
   */
  public void testReadHoplogSplitNoLobs() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    /****************PLAY THE DDLS START****************************************/
    st.execute("create schema emp");
    st.execute("set schema emp");
    //Create an HDFS store with a batch size of 5 MB
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchsize 5");
    //create row with 128K entries
    st.execute("create table emp.mytab1 (col1 int primary key, col2 int) persistent hdfsstore (myhdfs)");

    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    int NUM_ENTRIES = 100;
    for (int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.execute();
    }
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/EMP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    TestUtil.shutDown();

    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();

    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./myhdfs");
    {
      Configuration conf = new Configuration();
      Iterator<Map.Entry<String, String>> confentries = conf.iterator();

      while (confentries.hasNext()) {
        Map.Entry<String, String> entry = confentries.next();
        props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
      }
    }

    conn = TestUtil.getConnection(props);

    //Use the GF input format to generate a set of splits
    HDFSStoreImpl hdfsStore = GemFireCacheImpl.getInstance().findHDFSStore("MYHDFS");
    FileSystem fs = hdfsStore.getFileSystem();
    Configuration conf = hdfsStore.getFileSystem().getConf();
    GFInputFormat gfInputFormat = new GFInputFormat();
    Job job = Job.getInstance(conf, "test");

    conf = job.getConfiguration();
    conf.set(GFInputFormat.INPUT_REGION, "/EMP/MYTAB1");
    conf.set(GFInputFormat.HOME_DIR, "myhdfs");
    conf.setBoolean(GFInputFormat.CHECKPOINT, false);

    List<InputSplit> splits = gfInputFormat.getSplits(job);

    Set<Integer> seenEntries = new TreeSet<Integer>();
    for (InputSplit split : splits) {

      CombineFileSplit cSplit = (CombineFileSplit)split;
      Path[] paths = cSplit.getPaths();
      long[] starts = cSplit.getStartOffsets();
      long[] lengths = cSplit.getLengths();
      HDFSSplitIterator splitIterator = HDFSSplitIterator.newInstance(fs, paths, starts, lengths, 0, 0);

      LanguageConnectionContext context = ((EmbedConnection)conn).getLanguageConnectionContext();
      context.setHDFSSplit(splitIterator);
      //TODO - set the connection properties here
      st = conn.createStatement();
      ResultSet rs = st.executeQuery("select * from emp.mytab1");
      while (rs.next()) {
        if (!seenEntries.add(rs.getInt("col1"))) {
          fail("Did not expect to see a duplicate primary key. key=" + rs.getInt("col1") + ", seen=" + seenEntries);
        }

        //Here's how you extract the persisted event metadata for this row
        EmbedResultSet embedSet = (EmbedResultSet)rs;
        embedSet.getCurrentRow();
        TableScanResultSet sourceSet = (TableScanResultSet)embedSet.getSourceResultSet();
        HDFSEventRowLocationRegionEntry rowLocation = (HDFSEventRowLocationRegionEntry)sourceSet.getRowLocation();
        PersistedEventImpl event = rowLocation.getEvent();
        assertTrue(event.getOperation().isCreate());
      }
    }

    assertEquals("Did not find all of the entries. seen entries - " + seenEntries, NUM_ENTRIES, seenEntries.size());
  }

  /**
   * Test the foreign key constraint replay should not throw any exception
   *
   * @throws Exception
   */
  public void testForeignKeyConstraint() throws Exception {

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    /****************PLAY THE DDLS START****************************************/
    st.execute("CREATE HDFSSTORE airlines " +
        "NAMENODE '' " +
        "HOMEDIR 'gemfirexd' " +
        "BATCHTIMEINTERVAL 1000 milliseconds");
    // table that is not hDFS persistent
    st.execute("CREATE TABLE FLIGHTS   ( " +
        "FLIGHT_ID CHAR(6) NOT NULL , " +
        "SEGMENT_NUMBER INTEGER NOT NULL , " +
        "ORIG_AIRPORT CHAR(3), " +
        "DEPART_TIME TIME, " +
        "DEST_AIRPORT CHAR(3), " +
        "ARRIVE_TIME TIME, " +
        "MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, " +
        "MILES INTEGER, " +
        "AIRCRAFT VARCHAR(6), " +
        "CONSTRAINT FLIGHTS_PK PRIMARY KEY ( FLIGHT_ID, SEGMENT_NUMBER), " +
        "CONSTRAINT MEAL_CONSTRAINT CHECK (meal IN ('B', 'L', 'D', 'S')))");
    // HDFS persistent table that refers a non HDFS persistent table
    st.execute("CREATE TABLE FLIGHTAVAILABILITY ( " +
        "FLIGHT_ID CHAR(6) NOT NULL , " +
        "SEGMENT_NUMBER INTEGER NOT NULL , " +
        "FLIGHT_DATE DATE NOT NULL , " +
        "ECONOMY_SEATS_TAKEN INTEGER DEFAULT 0, " +
        "BUSINESS_SEATS_TAKEN INTEGER DEFAULT 0, " +
        "FIRSTCLASS_SEATS_TAKEN INTEGER DEFAULT 0, " +
        "CONSTRAINT FLIGHTAVAIL_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER,FLIGHT_DATE), " +
        "      CONSTRAINT FLIGHTS_FK2 Foreign Key ( FLIGHT_ID, SEGMENT_NUMBER)" +
        "         REFERENCES FLIGHTS (FLIGHT_ID, SEGMENT_NUMBER) ) " +
        "PARTITION BY COLUMN (FLIGHT_ID) " +
        "REDUNDANCY 1 " +
        "RECOVERYDELAY 1000 BUCKETS 5  HDFSSTORE (airlines)");
    TestUtil.shutDown();

    /****************PLAY THE DDLS - END ****************************************/

    /****************REPLAY THE DDLS FOR MYHDFS - START ****************************************/
    props = new Properties();

    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "");
    props.put(Property.GFXD_HD_HOMEDIR, "./gemfirexd");
    Configuration conf = new Configuration();
    Iterator<Map.Entry<String, String>> confentries = conf.iterator();

    while (confentries.hasNext()) {
      Map.Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }

    try {
      conn = TestUtil.getConnection(props);
      // replay happens here. The non hDFS persistent table will not be found but
      // no exception should be thrown
      st = conn.createStatement();
    } catch (Exception e) {
      fail("Exception thrown while replaying DDLs", e);
    }

    TestUtil.shutDown();
  }

  public void testCannotConnect() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema emp");
    st.execute("set schema emp");
    int port = AvailablePortHelper.getRandomAvailableTCPPort();
    addExpectedException(ConnectException.class);
    try {
      st.execute("create hdfsstore myhdfs namenode 'hdfs://localhost:" + port
          + "' homedir '" + HDFS_DIR + "'");
      fail("expected exception in connecting to unavailable HDFS store");
    } catch (SQLException e) {
      if (!"X0Z30".equals(e.getSQLState())) {
        throw e;
      }
      if (!HDFSIOException.class.equals(e.getCause().getClass())) {
        throw e;
      }
    }
    removeExpectedException(ConnectException.class);
  }

  public void testInsertWithHDFSDown() throws Exception {
    int clusterPort = AvailablePortHelper.getRandomAvailableTCPPort();
    System.setProperty("test.build.data", HDFS_DIR);
    Configuration hconf = new HdfsConfiguration();
    // hconf.set("hadoop.log.dir", "/tmp/hdfs/logs");
    hconf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 1);
    MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(hconf);
    builder.numDataNodes(2);
    builder.nameNodePort(clusterPort);
    MiniDFSCluster cluster = builder.build();

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema emp");
    st.execute("set schema emp");
    addExpectedException(ConnectException.class);
    st.execute("create hdfsstore myhdfs namenode 'hdfs://localhost:" + clusterPort
        + "' homedir '" + HDFS_DIR + "' BATCHTIMEINTERVAL 1 milliseconds");
    GemFireCacheImpl.getInstance().getLogger().info("<ExpectedException action=add>" + expectedExceptions + "</ExpectedException>");
    st.execute("create table mytab (col1 int primary key) hdfsstore (myhdfs) eviction by criteria (col1 < 1000) evict incoming");
    st.execute("insert into mytab values (5)");

    //Wait for data to be flushed to hdfs
    Thread.sleep(5000);

    //query hdfs, which will open a reader
    st.execute("select * from mytab  -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1=5");
    cluster.shutdownNameNodes();

//    try {
//      st.execute("insert into mytab values (118)");
//      fail("expected exception in connecting to unavailable HDFS store");
//    } catch (SQLException e) {
//      if (!"X0Z30".equals(e.getSQLState())) {
//        throw e;
//      }
//      if (!HDFSIOException.class.equals(e.getCause().getClass())) {
//        throw e;
//      }
//    }

    cluster.restartNameNode();
    cluster.restartDataNodes();

    //Wait for namenode to leave safe mode
    Thread.sleep(10000);

    st.execute("insert into mytab values (118)");

    //query hdfs to trigger scan
    st.execute("select * from mytab  -- GEMFIREXD-PROPERTIES queryHDFS=true \n");

    GemFireCacheImpl.getInstance().getLogger().info("<ExpectedException action=remove>" + expectedExceptions + "</ExpectedException>");
    st.execute("drop table mytab");
    st.execute("drop hdfsstore myhdfs");
    cluster.shutdownDataNodes();
    cluster.shutdownNameNodes();
    TestUtil.shutDown();
    System.clearProperty("test.build.data");
  }

  public void testBug50574() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();


    st.execute(" create hdfsstore sensorStore  NameNode 'localhost'  HomeDir './sensorStore' " +
        "BatchTimeInterval 10 milliseconds WriteOnlyFileRolloverInterval 1 seconds");

    st.execute(" create table raw_sensor(   id bigint primary key,   timestamp bigint, house_id integer   ) " +
        "partition by column (house_id) persistent hdfsstore (sensorStore) writeonly");
    st.execute("insert into raw_sensor (   id,   timestamp , house_id ) values (1,1,1)");
    st.execute("insert into raw_sensor (   id,   timestamp , house_id ) values (11,11,11)");
    Thread.sleep(2000);

    TestUtil.shutDown();
    deleteOplogs();
    props.put("mcast-port", "0");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Property.GFXD_HD_NAMENODEURL, "localhost");
    props.put(Property.GFXD_HD_HOMEDIR, "./sensorStore");
    Configuration conf = new Configuration();
    Iterator<Map.Entry<String, String>> confentries = conf.iterator();

    while (confentries.hasNext()) {
      Map.Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }

    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    noOplogsCreatedCheck();

    TestUtil.shutDown();
  }

  public void testEvictionCriteriaFunction() throws Exception {

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    /****************PLAY THE DDLS START****************************************/
    st.execute("create diskstore hdfsStoreDisk 'hdfsStoreData' autocompact false");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' DISKSTORENAME hdfsStoreDisk");
    st.execute("create function testsubstring(str varchar(100), startIndex integer, endIndex integer) "
        + "returns varchar(100) parameter style java no sql language java "
        + "external name 'com.pivotal.gemfirexd.functions.TestFunctions.substring'");

    st.execute(" create table raw_sensor ( id bigint, timestamp bigint, "
        + "age integer, sensortype varchar(10))"
        + "eviction by criteria (testsubstring(sensortype, 0, 3) = 'DEL')"
        + " eviction frequency 600 seconds"
        + " hdfsstore (myhdfs) writeonly;");

    //eviction start time and frequency is getting changed
    SimpleDateFormat sdf = new SimpleDateFormat();
    sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    String gmtStrDate = sdf.format(Calendar.getInstance().getTime());
    Date date = new Date(gmtStrDate);
    st.execute("ALTER TABLE raw_sensor SET EVICTION FREQUENCY 5 SECONDS START T '" + new Time(date.getTime()) + "'");

    st.execute("insert into raw_sensor (id, timestamp, age, sensortype) values (1, 100, 7, 'DEL_RECORD')");
    st.execute("insert into raw_sensor (id, timestamp, age, sensortype) values (1, 200, 3, 'ADD_RECORD')");

    LocalRegion r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/RAW_SENSOR", false);
    assertNotNull(r.getCustomEvictionAttributes());


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
    Iterator<Map.Entry<String, String>> confentries = conf.iterator();

    while (confentries.hasNext()) {
      Map.Entry<String, String> entry = confentries.next();
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(), entry.getValue());
    }

    conn = TestUtil.getConnection(props);
    st = conn.createStatement();

    r = GemFireCacheImpl.getInstance().getRegionByPath("/APP/RAW_SENSOR", false);
    assertNull(r.getCustomEvictionAttributes());
    TestUtil.shutDown();

  }

  private void deleteOplogs() {
    String currDir = System.getProperty("user.dir");
    File cdir = new File(currDir);
    String[] files = cdir.list();
    if (files != null) {
      for (String file : files) {
        if (file.startsWith("DRLK") ||
            file.startsWith("BACKUP") ||
            file.startsWith("OVERFLOW")) {
          File f = new File(file);
          f.delete();
        }
      }
    }
  }

  private void noOplogsCreatedCheck() {
    String currDir = System.getProperty("user.dir");
    File cdir = new File(currDir);
    String[] files = cdir.list();
    if (files != null) {
      for (String file : files) {
        if (file.startsWith("DRLK") ||
            file.startsWith("BACKUP") ||
            file.startsWith("OVERFLOW")) {
          assertTrue("GemFire created an oplog on local disk. Oplog name: " + file, false);
        }
      }
    }
  }
}
